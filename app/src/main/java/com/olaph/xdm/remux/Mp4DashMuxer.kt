package com.olaph.xdm.remux

import android.media.MediaCodec
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.MuxerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.ArrayDeque

/** One downloaded elementary stream: a fragmented MP4 (`mp4`, `m4a`) or a WebM file as YouTube serves them. */
class DashInput(val file: File, val container: String)

/** Merges the video-only and audio-only files of a DASH download into one ordinary file. */
interface DashMuxer {
    /**
     * Writes [output] from [video] and/or [audio] by copying their samples (no re-encoding). The result is an `mp4`
     * with a video track (plus the audio track if given) or an `m4a` when there is only audio. Throws
     * [RemuxException] with a short reason when the input cannot be used; [output] does not exist afterwards then.
     */
    suspend fun mux(video: DashInput?, audio: DashInput?, output: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): RemuxResult
}

/**
 * [DashMuxer] on media3: [FragmentedMp4Extractor] / [MatroskaExtractor] read each file, [Mp4Muxer] writes the MP4.
 * The two files are read in step (always the sample with the earlier timestamp next), so audio and video end up
 * interleaved and the player does not have to seek back and forth.
 *
 * H.264, H.265 and AV1 video and AAC audio are copied; anything else (VP9, Opus, ...) is rejected with a message.
 * (VP9 was tried: [Mp4Muxer] insists on a `vpcC` configuration record that the WebM files YouTube serves do not carry.)
 */
@OptIn(UnstableApi::class)
class Mp4DashMuxer : DashMuxer {

    override suspend fun mux(video: DashInput?, audio: DashInput?, output: File, onProgress: (Long, Long) -> Unit): RemuxResult =
        withContext(Dispatchers.IO) {
            val inputs = listOfNotNull(video, audio)
            if (inputs.isEmpty()) throw RemuxException("nothing to merge")
            val total = inputs.sumOf { it.file.length() }
            if (inputs.any { !it.file.isFile || it.file.length() <= 0 }) throw RemuxException("a downloaded part is empty")
            output.delete()
            val session = Session(output)
            try {
                session.run(video, audio, total, onProgress)
            } catch (e: CancellationException) {
                session.abort()
                throw e
            } catch (e: Throwable) {
                session.abort()
                throw when (e) {
                    is RemuxException -> e
                    is ParserException -> RemuxException("corrupt video data (${e.message})", e)
                    is MuxerException -> RemuxException(
                        if (e.hasNoSpace()) "not enough free space" else "could not write the MP4 (${e.cause?.message ?: e.message})", e,
                    )
                    is IOException -> RemuxException(if (e.hasNoSpace()) "not enough free space" else "I/O error (${e.message})", e)
                    is IllegalArgumentException, is IllegalStateException -> RemuxException("unsupported stream (${e.message})", e)
                    else -> throw e
                }
            }
        }

    private fun Throwable.hasNoSpace(): Boolean =
        generateSequence(this) { it.cause }.any { it.message?.contains("No space left", ignoreCase = true) == true }

    private class Sample(val track: Track, val timeUs: Long, val flags: Int, val data: ByteArray)

    /** The single audio or video stream of one file. Other track types (subtitles, metadata) are swallowed. */
    private class Track(val type: Int, val owner: Source) : TrackOutput {
        val wanted = type == C.TRACK_TYPE_VIDEO || type == C.TRACK_TYPE_AUDIO
        var format: Format? = null
        var muxerTrackId = -1
        var samplesIn = 0
        var samplesOut = 0

        private var data = ByteArray(64 * 1024)
        private var end = 0

        override fun format(format: Format) {
            if (wanted && this.format == null) this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (!wanted) return skip(input, length, allowEndOfInput)
            ensureCapacity(length)
            val n = input.read(data, end, length)
            if (n == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return n
                throw EOFException()
            }
            end += n
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (!wanted) {
                data.skipBytes(length)
                return
            }
            ensureCapacity(length)
            data.readBytes(this.data, end, length)
            end += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!wanted) return
            val start = end - offset - size
            if (start < 0 || size <= 0) return
            val sample = data.copyOfRange(start, start + size)
            System.arraycopy(data, start + size, data, 0, offset)
            end = offset
            if (cryptoData != null) throw RemuxException("the video is encrypted")
            if (format == null || timeUs == C.TIME_UNSET) return
            samplesIn++
            owner.queue.add(Sample(this, timeUs, flags, sample))
        }

        private fun ensureCapacity(extra: Int) {
            if (end + extra > data.size) data = data.copyOf(maxOf(data.size * 2, end + extra))
        }

        private fun skip(input: DataReader, length: Int, allowEndOfInput: Boolean): Int {
            val scratch = ByteArray(minOf(length, 8192).coerceAtLeast(1))
            val n = input.read(scratch, 0, minOf(length, scratch.size))
            if (n == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
            return n
        }
    }

    /** One input file with its extractor; [peek] reads ahead just far enough to know the next sample. */
    private class Source(val input: DashInput, val kind: Int) : ExtractorOutput {
        val queue = ArrayDeque<Sample>()
        val tracks = ArrayList<Track>()
        private val raf = RandomAccessFile(input.file, "r")
        private val reader = BufferedFile(raf)
        private val extractor: Extractor = if (input.container == "webm") {
            MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        } else {
            FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        }
        private var extractorInput = reader.open(0)
        private val position = PositionHolder()
        private var ended = false
        val consumed: Long get() = reader.position

        init {
            extractor.init(this)
        }

        override fun track(id: Int, type: Int): TrackOutput = Track(type, this).also { tracks += it }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit

        /** The next sample of this file, or null at its end. */
        fun peek(): Sample? {
            while (queue.isEmpty() && !ended) {
                val r = extractor.read(extractorInput, position)
                when (r) {
                    Extractor.RESULT_END_OF_INPUT -> ended = true
                    Extractor.RESULT_SEEK -> extractorInput = reader.open(position.position)
                }
            }
            return queue.peekFirst()
        }

        fun poll(): Sample = queue.pollFirst()

        fun close() {
            runCatching { extractor.release() }
            runCatching { raf.close() }
        }
    }

    /** Buffered, seekable reads of a file for [DefaultExtractorInput] (extractors read atom headers a few bytes at a time). */
    private class BufferedFile(private val raf: RandomAccessFile) {
        private val buf = ByteArray(1 shl 20)
        private var bufStart = 0L
        private var bufLen = 0
        var position = 0L
            private set

        fun open(at: Long): DefaultExtractorInput {
            position = at
            return DefaultExtractorInput({ buffer, offset, length -> read(buffer, offset, length) }, at, raf.length())
        }

        private fun read(out: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position < bufStart || position >= bufStart + bufLen) {
                raf.seek(position)
                val n = raf.read(buf, 0, buf.size)
                if (n <= 0) return C.RESULT_END_OF_INPUT
                bufStart = position
                bufLen = n
            }
            val from = (position - bufStart).toInt()
            val n = minOf(length, bufLen - from)
            System.arraycopy(buf, from, out, offset, n)
            position += n
            return n
        }
    }

    private class Session(private val output: File) {
        private var muxer: Mp4Muxer? = null
        private val sources = ArrayList<Source>()

        suspend fun run(video: DashInput?, audio: DashInput?, total: Long, onProgress: (Long, Long) -> Unit): RemuxResult {
            val v = video?.let { Source(it, C.TRACK_TYPE_VIDEO) }?.also { sources += it }
            val a = audio?.let { Source(it, C.TRACK_TYPE_AUDIO) }?.also { sources += it }
            try {
                return copy(v, a, total, onProgress)
            } finally {
                sources.forEach { it.close() }
            }
        }

        private suspend fun copy(v: Source?, a: Source?, total: Long, onProgress: (Long, Long) -> Unit): RemuxResult {
            onProgress(0, total)
            // Drop leading delta frames of the video: playback has to start on a key frame.
            while (v != null) {
                val s = v.peek() ?: break
                if (s.track.type == C.TRACK_TYPE_VIDEO && s.flags and C.BUFFER_FLAG_KEY_FRAME == 0) v.poll() else break
            }
            val firstV = v?.peek()
            val firstA = a?.peek()
            if (v != null && firstV == null) throw RemuxException("no video found in the downloaded file")
            if (a != null && firstA == null) throw RemuxException("no audio found in the downloaded file")
            validate(firstV?.track)
            validate(firstA?.track)
            // Time 0 is the earliest first sample; the offset between audio and video stays as it is.
            val origin = listOfNotNull(firstV?.timeUs, firstA?.timeUs).min()

            val m = Mp4Muxer.Builder(FileOutputStream(output))
                .setSampleBatchingEnabled(true)
                .setSampleCopyingEnabled(false)
                .build()
            muxer = m
            var endUs = 0L
            val lastTime = HashMap<Track, Long>()
            var writes = 0
            var reported = 0L
            val step = maxOf(256L * 1024, total / 200)
            while (true) {
                if ((++writes and 0xFF) == 0) currentCoroutineContext().ensureActive()
                val pv = v?.peek()
                val pa = a?.peek()
                val from = when {
                    pv == null && pa == null -> break
                    pv == null -> a!!
                    pa == null -> v!!
                    pv.timeUs <= pa.timeUs -> v!!
                    else -> a!!
                }
                val s = from.poll()
                val t = s.track
                if (t.muxerTrackId < 0) t.muxerTrackId = m.addTrack(if (t.type == C.TRACK_TYPE_VIDEO) 1 else 2, muxerFormat(t))
                val rel = (s.timeUs - origin).coerceAtLeast(0)
                val info = BufferInfo(rel, s.data.size, if (s.flags and C.BUFFER_FLAG_KEY_FRAME != 0) C.BUFFER_FLAG_KEY_FRAME else 0)
                m.writeSampleData(t.muxerTrackId, ByteBuffer.wrap(s.data), info)
                t.samplesOut++
                // The last frame has no successor to measure against: it lasts about as long as the one before it.
                val prev = lastTime[t]
                val frameUs = if (prev != null && rel > prev) rel - prev else 0
                lastTime[t] = rel
                endUs = maxOf(endUs, rel + frameUs)
                val done = sources.sumOf { it.consumed }
                if (done - reported >= step) {
                    reported = done
                    onProgress(done.coerceAtMost(total), total)
                }
            }
            val videoTrack = v?.tracks?.firstOrNull { it.samplesOut > 0 }
            val audioTrack = a?.tracks?.firstOrNull { it.samplesOut > 0 }
            if (videoTrack == null && audioTrack == null) throw RemuxException("no audio or video found")
            if (v != null && videoTrack == null) throw RemuxException("no video key frame found")
            m.close()
            muxer = null
            onProgress(total, total)
            return RemuxResult(
                extension = if (videoTrack != null) "mp4" else "m4a",
                mimeType = if (videoTrack != null) "video/mp4" else "audio/mp4",
                durationUs = endUs,
                videoIn = videoTrack?.samplesIn ?: 0,
                videoOut = videoTrack?.samplesOut ?: 0,
                audioIn = audioTrack?.samplesIn ?: 0,
                audioOut = audioTrack?.samplesOut ?: 0,
                discontinuities = 0,
            )
        }

        private fun validate(t: Track?) {
            val mime = t?.format?.sampleMimeType ?: return
            val ok = if (t.type == C.TRACK_TYPE_VIDEO) {
                // VP9 (always WebM on YouTube) would need a vpcC record that Matroska files from YouTube do not carry.
                mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265 || mime == MimeTypes.VIDEO_AV1
            } else {
                mime == MimeTypes.AUDIO_AAC
            }
            if (!ok) throw RemuxException("unsupported ${if (t.type == C.TRACK_TYPE_VIDEO) "video" else "audio"} codec ($mime)")
        }

        private fun muxerFormat(t: Track): Format = checkNotNull(t.format)

        fun abort() {
            runCatching { muxer?.close() }
            muxer = null
            sources.forEach { it.close() }
            output.delete()
        }
    }
}
