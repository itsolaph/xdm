package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadDao
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.SegmentEntity
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.EngineRequest
import com.olaph.xdm.engine.FatalDownloadException
import com.olaph.xdm.engine.ProbeResult
import com.olaph.xdm.engine.ProgressStore
import com.olaph.xdm.engine.SavedState
import com.olaph.xdm.engine.SegmentState
import com.olaph.xdm.remux.DashInput
import com.olaph.xdm.remux.DashMuxer
import com.olaph.xdm.remux.RemuxException
import com.olaph.xdm.youtube.FormatSelector
import com.olaph.xdm.youtube.OptionKind
import com.olaph.xdm.youtube.StreamKind
import com.olaph.xdm.youtube.YoutubeErrors
import com.olaph.xdm.youtube.YoutubeException
import com.olaph.xdm.youtube.YoutubeHeaders
import com.olaph.xdm.youtube.YoutubeResolver
import com.olaph.xdm.youtube.YtErrorKind
import com.olaph.xdm.youtube.YtInfo
import com.olaph.xdm.youtube.YtPlan
import com.olaph.xdm.youtube.YtStream
import kotlinx.coroutines.delay
import java.io.File
import java.util.Properties

/** What a finished YouTube job hands to the file saver. */
class YoutubeResult(val file: File, val fileName: String, val mimeType: String, val totalBytes: Long, val note: String? = null)

/**
 * One YouTube download from "resolve" to "single file ready to be saved": looks the video up (always again, URLs
 * expire), downloads the chosen streams one after the other with the multi-segment engine in capped chunks, and
 * merges them with the [DashMuxer]. Progress of all streams is one number for the download row. Everything on disk
 * lives in `yt-<id>/` under the temp dir, so pause, resume and a process restart continue from the bytes there.
 */
internal class YoutubeJob(
    private val engine: DownloadEngine,
    private val dao: DownloadDao,
    private val resolver: YoutubeResolver,
    private val muxer: DashMuxer,
    private val tempDir: File,
    private val freeSpace: (File) -> Long,
    /** Pause before retrying a lookup that failed on the network; tests pass 0. */
    private val retryDelayMs: Long = 2_000,
    private val chunkBytes: Long = CHUNK_BYTES,
) {
    /** Where progress goes: the live map of the manager. */
    interface Sink {
        fun status(text: String)
        fun bytes(done: Long, total: Long, stage: String)
        fun merging(done: Long, total: Long)
    }

    fun dir(id: Long) = File(tempDir, "yt-$id")

    suspend fun run(d: DownloadEntity, userAgent: String, sink: Sink): YoutubeResult {
        val id = d.id
        val dir = dir(id).also { it.mkdirs() }
        readMerged(dir)?.let { return it } // an earlier run finished the merge and was stopped while saving

        val stored = YtPlan.decode(d.extra) ?: throw FatalDownloadException("This YouTube download is damaged. Add the link again.")
        sink.status("Getting video info…")
        val info = resolve(stored.videoId)
        if (info.live) throw YoutubeException(YtErrorKind.LIVE)
        val option = FormatSelector.resolve(info, stored)
        val plan = YtPlan.forOption(stored.videoId, option)
        val fileName = FormatSelector.fileName(info.title, option)
        val mime = if (option.kind == OptionKind.AUDIO) "audio/mp4" else "video/mp4"
        // From here on the item is pinned to these itags: a resume continues the same bytes.
        dao.setYoutubePlan(id, plan.encode(), fileName, plan.totalBytes, mime)

        val streams = option.streams
        val knownTotal = plan.totalBytes
        if (knownTotal > 0) {
            val needed = (knownTotal - d.downloadedBytes).coerceAtLeast(0) + knownTotal + SPACE_MARGIN
            if (freeSpace(dir) < needed) {
                throw FatalDownloadException(
                    "Not enough free space: about ${Formatters.bytes(needed)} needed (video, audio and the merged file)",
                )
            }
        }

        // URLs of the latest lookup: after one stream needed a new URL, the next one starts with a fresh one as well.
        val latestUrls = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val lengths = LongArray(streams.size) { streams[it].contentLength }
        fun total() = if (lengths.all { it > 0 }) lengths.sum() else -1L
        val files = ArrayList<File>()
        var before = 0L
        for ((k, stream) in streams.withIndex()) {
            val stage = if (stream.kind == StreamKind.AUDIO_ONLY) "audio" else "video"
            val file = File(dir, if (stream.kind == StreamKind.AUDIO_ONLY) AUDIO_PART else VIDEO_PART)
            files += file
            val base = k * SEGMENT_BASE
            val store = StreamStore(base, before) { len -> lengths[k] = len }
            val request = EngineRequest(
                id = id,
                url = latestUrls[stream.itag] ?: stream.url,
                userAgent = userAgent,
                file = file,
                connections = d.connections,
                saved = savedFor(id, base, stream),
                maxSegmentBytes = chunkBytes,
                knownLength = stream.contentLength,
                refreshUrl = { _ -> freshUrl(plan, stream, latestUrls) },
                headersFor = { url -> YoutubeHeaders.forStream(url) },
            )
            val priorBytes = before
            val result = try {
                engine.download(request, store) { p -> sink.bytes(priorBytes + p.downloaded, total(), stage) }
            } catch (e: FatalDownloadException) {
                if (e.httpCode == 403 || e.httpCode == 410) throw YoutubeException(YtErrorKind.EXPIRED, e)
                throw e
            }
            lengths[k] = result.totalBytes
            before += result.totalBytes
        }

        if (option.kind == OptionKind.PROGRESSIVE) {
            return YoutubeResult(files.single(), fileName, mime, files.single().length())
        }

        // Merge the parts into one ordinary file.
        val merged = File(dir, MERGED_PART)
        val video = streams.firstOrNull { it.kind != StreamKind.AUDIO_ONLY }?.let { DashInput(files[streams.indexOf(it)], it.container) }
        val audio = streams.firstOrNull { it.kind == StreamKind.AUDIO_ONLY }?.let { DashInput(files[streams.indexOf(it)], it.container) }
        sink.merging(0, 1)
        try {
            muxer.mux(video, audio, merged) { done, total -> sink.merging(done, total) }
        } catch (e: RemuxException) {
            // Audio alone is still a usable file; a video without its sound is not worth saving.
            if (audio != null && video == null) {
                return YoutubeResult(audio.file, fileName, mime, audio.file.length(), "Saved without tidying up the file (${e.message})")
            }
            throw FatalDownloadException("Couldn't merge video and audio: ${e.message}", cause = e)
        }
        writeMerged(dir, merged.length(), fileName, mime)
        files.forEach { it.delete() }
        return YoutubeResult(merged, fileName, mime, merged.length())
    }

    private suspend fun resolve(videoId: String): YtInfo {
        var attempt = 0
        while (true) {
            try {
                return resolver.resolve(videoId)
            } catch (t: Throwable) {
                val e = YoutubeErrors.from(t) // rethrows cancellation
                if (e.kind != YtErrorKind.NETWORK || ++attempt > NETWORK_RETRIES) throw e
                delay(retryDelayMs * attempt)
            }
        }
    }

    /** A new URL for the same itag (the old one expired or no longer matches this network). */
    private suspend fun freshUrl(plan: YtPlan, old: YtStream, latest: MutableMap<Int, String>): String {
        val option = FormatSelector.resolve(resolve(plan.videoId), plan)
        option.streams.forEach { latest[it.itag] = it.url }
        return option.streams.firstOrNull { it.itag == old.itag }?.url ?: throw YoutubeException(YtErrorKind.FORMAT_GONE)
    }

    private suspend fun savedFor(id: Long, base: Int, stream: YtStream): SavedState? {
        val rows = dao.getSegments(id).filter { it.idx in base until base + SEGMENT_BASE }
        if (rows.isEmpty()) return null
        val segments = rows.map { SegmentState(it.idx - base, it.start, it.end, it.downloaded) }
        val total = stream.contentLength.takeIf { it > 0 } ?: (segments.maxOf { it.end } + 1)
        return SavedState(total, acceptRanges = true, etag = null, lastModified = null, segments = segments)
    }

    /**
     * The engine numbers its segments from 0 for every file; the table has one range of indices per stream
     * (`k * SEGMENT_BASE`), and the item's downloaded bytes cover all streams.
     */
    private inner class StreamStore(
        private val base: Int,
        private val bytesBefore: Long,
        private val onLength: (Long) -> Unit,
    ) : ProgressStore {
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) {
            if (probe.contentLength > 0) onLength(probe.contentLength)
            dao.replaceSegmentRange(id, base, base + SEGMENT_BASE, segments.map { it.toEntity(id) })
            dao.setDownloaded(id, bytesBefore + segments.sumOf { it.downloaded })
        }

        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) {
            dao.saveProgress(id, segments.map { it.toEntity(id) }, bytesBefore + segments.sumOf { it.downloaded })
        }

        private fun SegmentState.toEntity(id: Long) = SegmentEntity(id, base + index, start, end, downloaded)
    }

    // ---- the finished merge: lets a stop between "merged" and "saved" skip the whole download next time ----

    private fun writeMerged(dir: File, bytes: Long, name: String, mime: String) {
        val props = Properties().apply { setProperty("bytes", bytes.toString()); setProperty("name", name); setProperty("mime", mime) }
        File(dir, "$MERGED_DONE.tmp").outputStream().use { props.store(it, null) }
        File(dir, "$MERGED_DONE.tmp").renameTo(File(dir, MERGED_DONE))
    }

    private fun readMerged(dir: File): YoutubeResult? = runCatching {
        val props = Properties().apply { File(dir, MERGED_DONE).inputStream().use { load(it) } }
        val file = File(dir, MERGED_PART)
        val bytes = props.getProperty("bytes").toLong()
        YoutubeResult(file, props.getProperty("name"), props.getProperty("mime"), bytes).takeIf { file.isFile && file.length() == bytes }
    }.getOrNull()

    companion object {
        /** googlevideo throttles long single requests; NewPipe uses chunks of this size as well. */
        const val CHUNK_BYTES = 10L * 1024 * 1024

        /** Segment indices of stream `k` in the table are `k * SEGMENT_BASE + n`. */
        const val SEGMENT_BASE = 100_000

        private const val SPACE_MARGIN = 32L * 1024 * 1024
        private const val NETWORK_RETRIES = 2
        private const val VIDEO_PART = "video.part"
        private const val AUDIO_PART = "audio.part"
        private const val MERGED_PART = "merged.part"
        private const val MERGED_DONE = "merged.properties"
    }
}
