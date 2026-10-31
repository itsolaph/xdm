package com.olaph.xdm.remux

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.MuxerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * MPEG-TS → MP4 without re-encoding, using media3's [TsExtractor] to read the samples and [Mp4Muxer] to write
 * them. H.264 / H.265 video and AAC / MP3 audio are copied; anything else (AC-3, MPEG-2 video, ...) makes the
 * conversion fail so the caller keeps the `.ts`. ID3 timed metadata, subtitles and the like are dropped.
 *
 * Timestamps: the extractor already unwraps the 33-bit PTS clock; [TsTimeline] moves the first video key frame
 * to 0 and stitches clock jumps into one monotonic timeline.
 */
@OptIn(UnstableApi::class)
class TsMp4Remuxer : Remuxer {

    override suspend fun remux(input: File, output: File, onProgress: (Long, Long) -> Unit): RemuxResult =
        withContext(Dispatchers.IO) {
            val total = input.length()
            if (!input.isFile || total <= 0) throw RemuxException("The downloaded file is empty")
            output.delete()
            val session = Session(output)
            try {
                session.run(input, total, onProgress)
            } catch (e: CancellationException) {
                session.abort()
                throw e
            } catch (e: Throwable) {
                session.abort()
                throw when (e) {
                    is RemuxException -> e
                    is ParserException -> RemuxException("corrupt MPEG-TS data (${e.message})", e)
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

    private class Sample(val track: Track, val rawUs: Long, val flags: Int, val data: ByteArray)

    /** One elementary stream. Non-audio/video streams get [discard] = true and only swallow their bytes. */
    private class Track(private val session: Session, val type: Int) : TrackOutput {
        val kind = if (type == C.TRACK_TYPE_VIDEO) TsTimeline.Kind.VIDEO else TsTimeline.Kind.AUDIO
        val discard = type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO
        var format: Format? = null
        /** Why this stream cannot go into an MP4, or null. */
        var rejected: String? = null
        var timelineIndex = -1
        var muxerTrackId = -1
        var sawKeyFrame = false
        var sawWritable = false
        var samplesIn = 0
        var samplesOut = 0

        // AAC audio re-stamping state: see write() for why. gridAnchor is the mapped time of the first
        // sample of the current grid segment, gridIdx counts samples inside it, gridLastRaw feeds the
        // content-gap detector.
        var gridIdx = 0L
        var gridAnchor = C.TIME_UNSET
        var gridLastRaw = C.TIME_UNSET

        private var data = ByteArray(64 * 1024)
        private var end = 0

        val usable: Boolean get() = !discard && rejected == null

        override fun format(format: Format) {
            if (discard) return
            val old = this.format
            if (old != null) {
                if (old.width != format.width || old.height != format.height ||
                    old.sampleRate != format.sampleRate || old.channelCount != format.channelCount
                ) {
                    throw RemuxException("the stream changes its ${if (kind == TsTimeline.Kind.VIDEO) "resolution" else "audio format"} part-way")
                }
                return
            }
            this.format = format
            session.onFormat(this, format)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (discard || rejected != null) return skip(input, length, allowEndOfInput)
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
            if (discard || rejected != null) {
                data.skipBytes(length)
                return
            }
            ensureCapacity(length)
            data.readBytes(this.data, end, length)
            end += length
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (discard || rejected != null) return
            val start = end - offset - size
            if (start < 0 || size <= 0) return
            val sample = data.copyOfRange(start, start + size)
            // Drop the sample, keep whatever was already received after it (the next sample's start).
            System.arraycopy(data, start + size, data, 0, offset)
            end = offset
            if (format == null || timeUs == C.TIME_UNSET) return
            samplesIn++
            session.onSample(Sample(this, timeUs, flags, sample))
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

    private class Session(private val output: File) : ExtractorOutput {
        private val tracks = LinkedHashMap<Int, Track>()
        private val timeline = TsTimeline()
        private val preroll = ArrayList<Sample>()
        private var prerollBytes = 0L
        private var started = false
        private var muxer: Mp4Muxer? = null
        private var wroteAnything = false

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track(this, type) }
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit

        suspend fun run(input: File, total: Long, onProgress: (Long, Long) -> Unit): RemuxResult {
            val extractor = TsExtractor(
                TsExtractor.MODE_HLS,
                TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                SubtitleParser.Factory.UNSUPPORTED,
                TimestampAdjuster(0),
                // HLS requires an access unit delimiter in every AVC access unit, so slice-based splitting
                // (FLAG_DETECT_ACCESS_UNITS) stays off: with AUDs present it would cut frames in two.
                DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES),
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
            )
            extractor.init(this)
            val head = FileInputStream(input).use { f ->
                val buf = ByteArray(188 * 64)
                var n = 0
                while (n < buf.size) {
                    val r = f.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                TsTrailer.inspect(buf, n)
            }
            BufferedInputStream(FileInputStream(input), 1 shl 20).use { stream ->
                var consumed = 0L
                var trailer: ByteArray? = null
                var trailerPos = 0
                val reader = DataReader { buffer, offset, length ->
                    val n = stream.read(buffer, offset, length)
                    if (n >= 0) {
                        consumed += n
                        n
                    } else {
                        // End of the file: hand over the delimiter packet once (see TsTrailer), then end the input.
                        val t = trailer ?: run {
                            val video = tracks.values.firstOrNull { it.kind == TsTimeline.Kind.VIDEO && it.usable }?.format
                            if (head == null || video == null) ByteArray(0)
                            else TsTrailer.build(head, total, hevc = video.sampleMimeType == MimeTypes.VIDEO_H265)
                        }.also { trailer = it }
                        val c = minOf(length, t.size - trailerPos)
                        if (c <= 0) {
                            C.RESULT_END_OF_INPUT
                        } else {
                            System.arraycopy(t, trailerPos, buffer, offset, c)
                            trailerPos += c
                            c
                        }
                    }
                }
                val extractorInput = DefaultExtractorInput(reader, 0, C.LENGTH_UNSET.toLong())
                val sniffed = try {
                    extractor.sniff(extractorInput)
                } catch (e: EOFException) {
                    false
                }
                if (!sniffed) throw RemuxException("not an MPEG-TS stream")

                val position = PositionHolder()
                val step = maxOf(PROGRESS_MIN_STEP, total / 200)
                var reported = 0L
                var reads = 0
                onProgress(0, total)
                while (true) {
                    if ((++reads and 0xFF) == 0) currentCoroutineContext().ensureActive()
                    val r = extractor.read(extractorInput, position)
                    if (r == Extractor.RESULT_END_OF_INPUT) break
                    if (r == Extractor.RESULT_SEEK) throw RemuxException("unexpected seek request")
                    if (consumed - reported >= step) {
                        reported = consumed
                        onProgress(consumed, total)
                    }
                }
                onProgress(total, total)
            }
            return finish()
        }

        // ---- formats ----

        fun onFormat(track: Track, format: Format) {
            val mime = format.sampleMimeType
            val supported = when (track.kind) {
                TsTimeline.Kind.VIDEO -> mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
                TsTimeline.Kind.AUDIO -> mime == MimeTypes.AUDIO_AAC || mime == MimeTypes.AUDIO_MPEG
            }
            if (!supported) {
                track.rejected = "unsupported ${if (track.kind == TsTimeline.Kind.VIDEO) "video" else "audio"} codec (${describe(mime)})"
                return
            }
            if (tracks.values.any { it !== track && it.usable && it.kind == track.kind && it.format != null }) {
                throw RemuxException("more than one ${if (track.kind == TsTimeline.Kind.VIDEO) "video" else "audio"} stream")
            }
            track.timelineIndex = timeline.addTrack(track.kind)
        }

        private fun describe(mime: String?) = when (mime) {
            MimeTypes.AUDIO_AC3 -> "AC-3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "MPEG audio layer 1/2"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2 video"
            null -> "unknown"
            else -> mime
        }

        /** A rejected stream only matters if nothing of its kind can be copied instead (e.g. AC-3 next to AAC). */
        private fun failOnRejected() {
            for (kind in TsTimeline.Kind.values()) {
                val ofKind = tracks.values.filter { !it.discard && it.kind == kind }
                if (ofKind.none { it.usable && it.format != null }) {
                    ofKind.firstNotNullOfOrNull { it.rejected }?.let { throw RemuxException(it) }
                }
            }
        }

        // ---- samples ----

        fun onSample(s: Sample) {
            val t = s.track
            val key = s.flags and C.BUFFER_FLAG_KEY_FRAME != 0
            if (!started) {
                // The first sample of a stream decides the start: video begins at its first key frame.
                if (t.kind == TsTimeline.Kind.VIDEO && !t.sawKeyFrame && !key) return
                if (t.kind == TsTimeline.Kind.VIDEO && key) t.sawKeyFrame = true
                t.sawWritable = true
                preroll += s
                prerollBytes += s.data.size
                if (readyToStart() || prerollBytes > PREROLL_MAX_BYTES || preroll.size > PREROLL_MAX_SAMPLES) start()
            } else {
                write(s)
            }
        }

        private fun readyToStart(): Boolean {
            val active = tracks.values.filter { it.usable }
            return active.isNotEmpty() && active.all { it.sawWritable }
        }

        private fun start() {
            failOnRejected()
            val firstRaw = HashMap<Track, Long>()
            for (s in preroll) firstRaw.putIfAbsent(s.track, s.rawUs)
            val video = firstRaw.keys.firstOrNull { it.kind == TsTimeline.Kind.VIDEO }
            // Time 0 is the earliest first sample (audio usually starts a few ms before the first key frame), but
            // audio that runs more than a second ahead of the picture is cut instead of showing a blank start.
            val earliest = firstRaw.values.min()
            timeline.begin(video?.let { maxOf(earliest, firstRaw.getValue(it) - VIDEO_LEAD_MAX_US) } ?: earliest)
            muxer = Mp4Muxer.Builder(FileOutputStream(output))
                .setSampleBatchingEnabled(true)
                .setSampleCopyingEnabled(false)
                .build()
            started = true
            val queued = ArrayList(preroll)
            preroll.clear()
            for (s in queued) write(s)
        }

        private fun write(s: Sample) {
            val t = s.track
            val key = s.flags and C.BUFFER_FLAG_KEY_FRAME != 0
            if (t.kind == TsTimeline.Kind.VIDEO && !t.sawKeyFrame) {
                if (!key) return
                t.sawKeyFrame = true
            }
            val out = retimeAudio(t, timeline.map(t.timelineIndex, s.rawUs, key), s.rawUs)
            if (out == TsTimeline.DROP) return
            val m = checkNotNull(muxer)
            if (t.muxerTrackId < 0) {
                t.muxerTrackId = m.addTrack(if (t.kind == TsTimeline.Kind.VIDEO) 1 else 2, muxerFormat(t))
            }
            val info = BufferInfo(out, s.data.size, if (key) C.BUFFER_FLAG_KEY_FRAME else 0)
            m.writeSampleData(t.muxerTrackId, ByteBuffer.wrap(s.data), info)
            t.samplesOut++
            wroteAnything = true
        }

        /**
         * Re-stamps AAC audio on its exact sample grid: sample k of a segment sits at
         * `anchor + k * 1024 / sampleRate` relative to the segment's first mapped time.
         *
         * The extractor hands out microsecond stamps, and 1024/44100 s is not a whole number of
         * microseconds, so every frame loses a fraction of a microsecond on its way through the muxer's
         * rounding. Individually invisible, it accumulates (~4 s of audio lag on a 2.5 h HLS capture).
         * Counting samples instead makes every frame exactly 1024 ticks at the audio timescale, which
         * locks the audio to the video for the whole file. A jump of more than four frames in the raw
         * stamps is a real content gap (the encoder dropped frames), so the grid restarts anchored at the
         * mapped time and the gap survives. MP3 keeps its raw stamps: its frames are 1152 or 576 samples
         * depending on profile and VBR captures are stamped exactly anyway.
         */
        private fun retimeAudio(t: Track, out: Long, rawUs: Long): Long {
            if (out == TsTimeline.DROP || t.kind != TsTimeline.Kind.AUDIO) return out
            val f = t.format
            if (f == null || f.sampleMimeType != MimeTypes.AUDIO_AAC || f.sampleRate <= 0) return out
            val frameUs = 1_000_000.0 * 1024 / f.sampleRate
            val jumped = t.gridLastRaw != C.TIME_UNSET && rawUs - t.gridLastRaw > 4 * frameUs
            if (t.gridAnchor == C.TIME_UNSET || jumped) {
                t.gridIdx = 0
                t.gridAnchor = out
            }
            val stamped = t.gridAnchor + Math.round(t.gridIdx * frameUs)
            t.gridIdx++
            t.gridLastRaw = rawUs
            return stamped
        }

        /** MP3 goes in as an AAC-shaped `mp4a` track (the muxer has no MPEG audio); [finish] patches the object type. */
        private fun muxerFormat(t: Track): Format {
            val f = checkNotNull(t.format)
            if (f.sampleMimeType != MimeTypes.AUDIO_MPEG) return f
            return Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(f.sampleRate)
                .setChannelCount(f.channelCount)
                .setAverageBitrate(f.averageBitrate)
                .setPeakBitrate(f.peakBitrate)
                .setInitializationData(listOf(ByteArray(2)))
                .build()
        }

        private fun finish(): RemuxResult {
            if (!started) {
                failOnRejected()
                if (preroll.isEmpty()) throw RemuxException("no audio or video found")
                start()
            }
            failOnRejected()
            val usable = tracks.values.filter { it.usable }
            usable.firstOrNull { it.kind == TsTimeline.Kind.VIDEO && it.samplesIn > 0 && it.samplesOut == 0 }
                ?.let { throw RemuxException("no video key frame found") }
            if (!wroteAnything) throw RemuxException("no audio or video found")
            val endUs = timeline.endUs()
            checkNotNull(muxer).close()
            muxer = null
            val mp3 = usable.any { it.kind == TsTimeline.Kind.AUDIO && it.format?.sampleMimeType == MimeTypes.AUDIO_MPEG && it.samplesOut > 0 }
            if (mp3 && Mp4Esds.patchObjectType(output, Mp4Esds.OBJECT_TYPE_AAC, Mp4Esds.OBJECT_TYPE_MPEG1_AUDIO) == 0) {
                throw RemuxException("could not mark the MP3 track")
            }
            val video = usable.firstOrNull { it.kind == TsTimeline.Kind.VIDEO && it.samplesOut > 0 }
            val audio = usable.firstOrNull { it.kind == TsTimeline.Kind.AUDIO && it.samplesOut > 0 }
            return RemuxResult(
                extension = if (video != null) "mp4" else "m4a",
                mimeType = if (video != null) "video/mp4" else "audio/mp4",
                durationUs = endUs,
                videoIn = video?.samplesIn ?: 0,
                videoOut = video?.samplesOut ?: 0,
                audioIn = audio?.samplesIn ?: 0,
                audioOut = audio?.samplesOut ?: 0,
                discontinuities = timeline.discontinuities,
            )
        }

        fun abort() {
            // The muxer owns the output stream; closing it also releases the file before it is deleted.
            runCatching { muxer?.close() }
            muxer = null
            output.delete()
        }
    }

    private companion object {
        const val PROGRESS_MIN_STEP = 256L * 1024
        const val PREROLL_MAX_BYTES = 8L * 1024 * 1024
        const val PREROLL_MAX_SAMPLES = 6000
        const val VIDEO_LEAD_MAX_US = 1_000_000L
    }
}
