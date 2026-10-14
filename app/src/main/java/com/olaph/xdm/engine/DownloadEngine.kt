package com.olaph.xdm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * Multi-connection HTTP downloader. Pure Kotlin/JVM: it reads and writes a [File] and reports state
 * through [ProgressStore], so it can be tested without Android.
 *
 * Cancelling the coroutine running [run] is how a download is paused: segment progress is persisted
 * one last time and a later [run] with the stored [SavedState] continues where it stopped.
 */
class DownloadEngine(
    client: OkHttpClient,
    private val maxRetries: Int = 5,
    private val backoffMs: (attempt: Int) -> Long = { attempt -> min(30_000L, 1_000L shl (attempt - 1)) },
    private val tickMs: Long = 250,
    private val persistIntervalMs: Long = 500,
    private val persistBytes: Long = 1024 * 1024,
    /** See [SegmentPlanner.MIN_DYNAMIC_SPLIT]; tests use small values. */
    private val minSplitBytes: Long = SegmentPlanner.MIN_DYNAMIC_SPLIT,
) {
    private val httpClient = client.withScopedCookies()
    val prober = Prober(httpClient)

    suspend fun download(
        request: EngineRequest,
        store: ProgressStore,
        onProgress: (EngineProgress) -> Unit = {},
    ): EngineResult {
        val urls = UrlHolder(request.url, request.refreshUrl)
        val probe = if (request.knownLength > 0) {
            ProbeResult(request.url, request.knownLength, acceptRanges = true, etag = null, lastModified = null, fileName = "", mimeType = null)
        } else {
            renewing(urls) { url -> withRetry { prober.probe(url, request.userAgent, request.headersFor?.invoke(url) ?: request.headers) } }
        }
        val file = request.file
        val total = if (probe.contentLength > 0) probe.contentLength else -1L

        val resumed = request.saved?.takeIf { canResume(it, probe, file) }
        val segments: List<SegmentState> = resumed?.segments ?: run {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            val fresh = if (probe.resumable) {
                SegmentPlanner.plan(total, request.connections, request.maxSegmentBytes)
            } else {
                // Single connection; known size is still recorded so progress can be shown.
                listOf(SegmentState(0, 0, if (total > 0) total - 1 else -1))
            }
            RandomAccessFile(file, "rw").use { raf -> if (total > 0) raf.setLength(total) }
            fresh
        }
        store.savePlan(request.id, probe, segments)

        val table = SegmentTable(segments)
        fun snapshot() = table.snapshot()
        fun sum() = table.downloaded()
        urls.progress = ::sum
        onProgress(EngineProgress(sum(), total, snapshot()))
        val connections = request.connections.coerceIn(SegmentPlanner.MIN_CONNECTIONS, SegmentPlanner.MAX_CONNECTIONS)
        // Splitting needs ranges and a known end; a single connection has nobody to hand the other half to.
        val minSplit = if (probe.resumable && connections > 1) minSplitBytes else 0L

        val channel = RandomAccessFile(file, "rw").channel
        try {
            coroutineScope {
                val ticker = launch {
                    var lastPersistTime = System.nanoTime()
                    var lastPersistBytes = sum()
                    while (true) {
                        delay(tickMs)
                        val snap = snapshot()
                        val done = snap.sumOf { it.downloaded }
                        onProgress(EngineProgress(done, total, snap))
                        val now = System.nanoTime()
                        if ((now - lastPersistTime) / 1_000_000 >= persistIntervalMs ||
                            done - lastPersistBytes >= persistBytes
                        ) {
                            // Data first, bookkeeping second: persisted progress never exceeds durable bytes.
                            withContext(Dispatchers.IO) { channel.force(false) }
                            store.saveProgress(request.id, snap)
                            lastPersistTime = now
                            lastPersistBytes = done
                        }
                    }
                }
                try {
                    // One worker per connection. Each takes the next unstarted segment (front to back; there are more
                    // segments than connections when their size is capped), and once none are left it splits the
                    // running segment with the most still to fetch, so a slow connection near the end gets help.
                    val workers = if (minSplit > 0) connections else min(connections, table.size)
                    List(workers) {
                        async {
                            while (true) {
                                val seg = table.next(minSplit) ?: break
                                try {
                                    runSegment(request, probe, table, seg, channel, urls)
                                } finally {
                                    table.finished(seg)
                                }
                            }
                        }
                    }.awaitAll()
                } finally {
                    ticker.cancel()
                }
            }
            withContext(Dispatchers.IO) {
                if (total <= 0) channel.truncate(sum())
                channel.force(true)
            }
        } finally {
            val snap = snapshot()
            withContext(NonCancellable) {
                runCatching { channel.force(false) }
                runCatching { store.saveProgress(request.id, snap) }
                runCatching { channel.close() }
            }
        }

        val done = sum()
        val finalTotal = if (total > 0) total else done
        if (total > 0 && done < total) throw IOException("Incomplete download: $done of $total bytes")
        onProgress(EngineProgress(done, finalTotal, snapshot()))
        return EngineResult(finalTotal, probe)
    }

    private fun canResume(saved: SavedState, probe: ProbeResult, file: File): Boolean {
        if (!probe.resumable || !saved.acceptRanges || saved.segments.isEmpty()) return false
        if (saved.totalBytes != probe.contentLength) return false
        if (saved.etag != null && probe.etag != null && saved.etag != probe.etag) return false
        if (saved.lastModified != null && probe.lastModified != null && saved.lastModified != probe.lastModified) return false
        if (!file.exists() || file.length() != probe.contentLength) return false
        // Split segments make the saved list irregular; a list with a gap or an overlap is not worth trusting.
        if (!SegmentPlanner.coversExactly(saved.segments, probe.contentLength)) return false
        return saved.segments.all { it.downloaded in 0..(if (it.end < 0) Long.MAX_VALUE else it.length) }
    }

    private suspend fun runSegment(
        request: EngineRequest,
        probe: ProbeResult,
        table: SegmentTable,
        seg: SegmentTable.Live,
        channel: FileChannel,
        urls: UrlHolder,
    ) {
        val counter = seg.downloaded
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val before = counter.get()
            val used = urls.url
            try {
                transferSegment(request, used, probe, table, seg, channel)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalDownloadException) {
                // Signed URL expired: get a new one and carry on from the bytes already written.
                if (e.isExpiredUrl() && urls.renew(used)) continue
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // an aborted call surfaces as IOException("Canceled")
                if (isDiskFull(e)) throw FatalDownloadException("No space left on device", cause = e)
                if (probe.resumable) {
                    if (counter.get() > before) attempt = 0 // made progress; the failure is not "stuck"
                } else {
                    counter.set(0) // cannot resume mid-way: start over on this connection
                }
                attempt++
                if (attempt > maxRetries) throw e
                delay(backoffMs(attempt))
            }
        }
    }

    private suspend fun transferSegment(
        request: EngineRequest,
        url: String,
        probe: ProbeResult,
        table: SegmentTable,
        seg: SegmentTable.Live,
        channel: FileChannel,
    ) {
        val offset = table.begin(seg)
        val end = seg.end
        if (offset >= seg.limit) return

        // A segment starting at 0 that spans the whole file needs no Range header.
        val wholeFile = offset == 0L && (end < 0 || end + 1 == probe.contentLength)
        val range = if (wholeFile) null else "bytes=$offset-${if (end >= 0) end else ""}"

        val ctx = currentCoroutineContext()
        val call = httpClient.newCall(buildRequest(url, request.userAgent, range = range, headers = request.headersFor?.invoke(url) ?: request.headers))
        call.executeAndUse { r ->
            checkStatus(r.code)
            if (range != null) {
                if (r.code != 206) {
                    throw FatalDownloadException("Server ignored the Range header (HTTP ${r.code})", r.code)
                }
                val start = r.header("Content-Range")?.substringAfter("bytes ", "")?.substringBefore('-')
                    ?.trim()?.toLongOrNull()
                if (start != null && start != offset) {
                    throw FatalDownloadException("Server returned wrong range: $start != $offset")
                }
            }
            val body = r.body ?: throw IOException("Empty response body")
            val input = body.byteStream()
            val buf = ByteArray(BUFFER_SIZE)
            var pos = offset
            // The end can move down while this runs (the segment was split). Bytes past it belong to another
            // connection: they are never written, and the response is closed as soon as the end is reached.
            while (pos < seg.limit) {
                val want = min(buf.size.toLong(), seg.limit - pos).toInt()
                val n = input.read(buf, 0, want)
                if (n < 0) break
                val allowed = table.claim(seg, pos, n)
                if (allowed > 0) {
                    writeFully(channel, buf, allowed, pos)
                    pos += allowed
                    seg.downloaded.addAndGet(allowed.toLong())
                }
                ctx.ensureActive()
            }
            if (seg.end >= 0 && pos < seg.limit) throw IOException("Connection closed early at byte $pos of ${seg.end}")
        }
    }

    private fun writeFully(channel: FileChannel, buf: ByteArray, len: Int, position: Long) {
        val bb = ByteBuffer.wrap(buf, 0, len)
        var pos = position
        while (bb.hasRemaining()) pos += channel.write(bb, pos)
    }

    private fun isDiskFull(e: IOException): Boolean =
        e.message?.contains("ENOSPC", ignoreCase = true) == true ||
            e.message?.contains("No space left", ignoreCase = true) == true

    /** Retries IO and 5xx failures with exponential backoff; 4xx and cancellation pass straight through. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalDownloadException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                attempt++
                if (attempt > maxRetries) throw e
                delay(backoffMs(attempt))
            }
        }
    }

    /** Runs [block] with the current URL; a 403/410 (expired signed URL) swaps in a fresh one and tries again. */
    private suspend fun <T> renewing(urls: UrlHolder, block: suspend (String) -> T): T {
        while (true) {
            val used = urls.url
            try {
                return block(used)
            } catch (e: FatalDownloadException) {
                if (!(e.isExpiredUrl() && urls.renew(used))) throw e
            }
        }
    }

    private fun FatalDownloadException.isExpiredUrl() = httpCode == 403 || httpCode == 410

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/**
 * The URL a download currently uses. When the server refuses it as expired every segment notices at about the same
 * time; the first one asks [refresh] for a new URL, the others see that it changed and just retry with it.
 * Renewals that did not lead to any new bytes are limited, so a server that refuses every URL cannot loop forever.
 */
internal class UrlHolder(initial: String, private val refresh: (suspend (staleUrl: String) -> String)?) {
    @Volatile var url: String = initial
        private set

    /** Bytes written so far; lets [renew] tell whether the last renewal helped. */
    @Volatile var progress: () -> Long = { 0L }

    private val mutex = Mutex()
    private var renewals = 0
    private var bytesAtRenewal = -1L

    /** True if [stale] is no longer the current URL (renewed now or earlier) and the caller should retry. */
    suspend fun renew(stale: String): Boolean {
        val refresh = refresh ?: return false
        return mutex.withLock {
            if (url != stale) return@withLock true
            val now = progress()
            if (now > bytesAtRenewal) renewals = 0
            if (renewals >= MAX_RENEWALS_WITHOUT_PROGRESS) return@withLock false
            renewals++
            bytesAtRenewal = now
            url = refresh(stale)
            true
        }
    }

    private companion object {
        const val MAX_RENEWALS_WITHOUT_PROGRESS = 3
    }
}
