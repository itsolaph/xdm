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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class HlsRequest(
    val id: Long,
    /** Media playlist (or a master playlist, in which case the best variant with audio is used). */
    val playlistUrl: String,
    val userAgent: String,
    /** Holds the finished segments; survives pause, crashes and restarts. Deleted by the caller. */
    val workDir: File,
    /** The assembled stream is written here. */
    val output: File,
    val concurrency: Int,
    val headers: Map<String, String> = emptyMap(),
)

class HlsProgress(
    val segmentsDone: Int,
    val segmentsTotal: Int,
    /** Bytes on disk, including the partially received segments. */
    val bytes: Long,
    /** Extrapolated size of the finished stream; -1 until one segment is complete. */
    val estimatedTotalBytes: Long,
)

class HlsResult(val totalBytes: Long, val extension: String, val mimeType: String, val segments: Int)

/**
 * HLS (VOD) downloader. Pure Kotlin/JVM. Segments are fetched in parallel into [HlsRequest.workDir]
 * (one file per segment, renamed into place when complete — that directory *is* the resume state), then
 * concatenated in playlist order: fMP4 (`#EXT-X-MAP`) becomes an `.mp4`, everything else a `.ts`
 * (or `.aac`/`.mp3` for packed audio). AES-128 is decrypted while downloading.
 *
 * Cancelling the coroutine pauses the download; calling [download] again continues with the segments
 * already on disk, as long as the playlist still lists the same segments.
 */
class HlsEngine(
    client: OkHttpClient,
    private val maxRetries: Int = 5,
    private val backoffMs: (attempt: Int) -> Long = { attempt -> min(30_000L, 1_000L shl (attempt - 1)) },
    private val tickMs: Long = 250,
    /** The cap shared with every other download (see [SpeedLimiter]). */
    private val limiter: SpeedLimiter = SpeedLimiter(),
    /** Suspends while the device has no network. */
    private val awaitNetwork: suspend () -> Unit = {},
) {
    private val http = client.withScopedCookies()
    private val inFlight = ConcurrentHashMap.newKeySet<Call>()
    private val interrupts = AtomicInteger()

    /** The default network changed: abort the transfers in flight, each segment is fetched again right away. */
    fun interruptConnections() {
        interrupts.incrementAndGet()
        http.connectionPool.evictAll()
        inFlight.forEach { it.cancel() }
    }

    private class Job(
        val file: File,
        val url: String,
        val range: ByteRange?,
        val key: HlsKey?,
        val sequence: Long,
        val isSegment: Boolean,
    )

    suspend fun download(request: HlsRequest, onProgress: suspend (HlsProgress) -> Unit = {}): HlsResult {
        val playlist = loadMediaPlaylist(request)
        if (!playlist.endList) throw FatalDownloadException("Live stream — not supported")
        if (playlist.segments.isEmpty()) throw FatalDownloadException("The playlist has no segments")
        playlist.unsupportedEncryption()?.let { throw FatalDownloadException(it) }

        val dir = request.workDir
        prepareWorkDir(dir, playlist)

        // Initialization sections first (distinct ones only), then the media segments.
        val inits = LinkedHashMap<String, HlsMap>()
        playlist.segments.forEach { s -> s.map?.let { inits.putIfAbsent(it.identity, it) } }
        val initFiles = inits.keys.mapIndexed { i, id -> id to File(dir, "init-$i.seg") }.toMap()
        val jobs = ArrayList<Job>()
        inits.forEach { (id, m) -> jobs += Job(initFiles.getValue(id), m.uri, m.byteRange, m.key, 0, isSegment = false) }
        val segmentJobs = playlist.segments.map { s ->
            Job(File(dir, "%06d.seg".format(s.index)), s.uri, s.byteRange, s.key, s.sequence, isSegment = true)
        }
        jobs += segmentJobs

        val doneCount = AtomicInteger(segmentJobs.count { it.file.exists() })
        val doneBytes = AtomicLong(segmentJobs.filter { it.file.exists() }.sumOf { it.file.length() })
        val inflight = AtomicLong()
        val total = segmentJobs.size

        fun snapshot(): HlsProgress {
            val d = doneCount.get()
            val b = doneBytes.get()
            return HlsProgress(d, total, b + inflight.get(), if (d > 0) b * total / d else -1)
        }
        onProgress(snapshot())

        val keys = KeyCache(request)
        try {
            coroutineScope {
                val ticker = launch {
                    while (true) {
                        delay(tickMs)
                        onProgress(snapshot())
                    }
                }
                try {
                    val gate = Semaphore(request.concurrency.coerceIn(1, 32))
                    jobs.map { job ->
                        async {
                            gate.withPermit {
                                if (!job.file.exists()) {
                                    val size = fetch(request, job, keys, inflight)
                                    if (job.isSegment) {
                                        doneBytes.addAndGet(size)
                                        doneCount.incrementAndGet()
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                } finally {
                    ticker.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { onProgress(snapshot()) } }
        }
        onProgress(snapshot())

        val extension = playlist.outputExtension
        val totalBytes = withContext(Dispatchers.IO) { assemble(playlist, segmentJobs, initFiles, request.output) }
        return HlsResult(totalBytes, extension, mimeFor(extension), total)
    }

    // ---- playlists ----

    private suspend fun loadMediaPlaylist(request: HlsRequest): MediaPlaylist {
        var url = request.playlistUrl
        repeat(3) {
            val text = withRetry { fetchBytes(url, request, MAX_PLAYLIST_BYTES).toString(Charsets.UTF_8) }
            val parsed = try {
                M3u8.parse(text, url)
            } catch (e: M3u8Exception) {
                throw FatalDownloadException(e.message ?: "Invalid playlist", cause = e)
            }
            when (parsed) {
                is MediaPlaylist -> return parsed
                is MasterPlaylist -> {
                    val best = parsed.bestVariant() ?: throw FatalDownloadException("The master playlist has no variants")
                    url = best.uri
                }
            }
        }
        throw FatalDownloadException("Playlist nesting is too deep")
    }

    private suspend fun fetchBytes(url: String, request: HlsRequest, limit: Int): ByteArray =
        http.newCall(buildRequest(url, request.userAgent, headers = headersFor(request, url))).executeAndUse { r ->
            checkStatus(r.code)
            val body = r.body ?: throw IOException("Empty response body")
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw FatalDownloadException("Response is too large")
                }
            }
            out.toByteArray()
        }

    private fun headersFor(request: HlsRequest, url: String) =
        HeaderCodec.scopeToHost(request.headers, request.playlistUrl, url)

    // ---- work directory / resume ----

    /**
     * Keeps the segments of an earlier run only if the playlist still lists the same segments (compared
     * without query strings: signed URLs change between sessions, the paths do not).
     */
    private fun prepareWorkDir(dir: File, playlist: MediaPlaylist) {
        val signature = signatureOf(playlist)
        val planFile = File(dir, "plan.txt")
        val matches = dir.isDirectory && planFile.isFile && runCatching { planFile.readText() == signature }.getOrDefault(false)
        if (!matches) {
            dir.deleteRecursively()
            dir.mkdirs()
            planFile.writeText(signature)
        }
        // A pause or crash can leave half-written segments behind; they are simply fetched again.
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun signatureOf(p: MediaPlaylist): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (s in p.segments) {
            md.update(s.uri.substringBefore('?').toByteArray())
            md.update(s.byteRange?.let { "@${it.offset}+${it.length}" }.orEmpty().toByteArray())
            md.update('\n'.code.toByte())
        }
        return "segments=${p.segments.size}\nsha256=" + md.digest().joinToString("") { "%02x".format(it) } + "\n"
    }

    // ---- one segment ----

    private suspend fun fetch(request: HlsRequest, job: Job, keys: KeyCache, inflight: AtomicLong): Long {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val tmp = File(job.file.path + ".tmp")
            var counted = 0L
            val interruptsBefore = interrupts.get()
            try {
                return transfer(request, job, tmp, keys) { delta -> counted += delta; inflight.addAndGet(delta) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FatalDownloadException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // an aborted call surfaces as IOException("Canceled")
                if (e.message?.contains("No space left", ignoreCase = true) == true) {
                    throw FatalDownloadException("No space left on device", cause = e)
                }
                // A transfer we aborted ourselves (network change) is fetched again at once and costs no attempt.
                if (interrupts.get() == interruptsBefore) {
                    attempt++
                    if (attempt > maxRetries) throw e
                    delay(backoffMs(attempt))
                }
                awaitNetwork()
            } finally {
                inflight.addAndGet(-counted)
                if (!job.file.exists()) tmp.delete()
            }
        }
    }

    private suspend fun transfer(
        request: HlsRequest,
        job: Job,
        tmp: File,
        keys: KeyCache,
        count: (Long) -> Unit,
    ): Long {
        val cipher = job.key?.let { key ->
            val keyBytes = keys.get(key.uri!!)
            Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(key.iv ?: sequenceIv(job.sequence)))
            }
        }
        val range = job.range
        val call = http.newCall(buildRequest(job.url, request.userAgent, range = range?.header(), headers = headersFor(request, job.url)))
        inFlight += call
        val written = try {
            call.executeAndUse { r ->
                checkStatus(r.code)
                val body = r.body ?: throw IOException("Empty response body")
                val input: InputStream = body.byteStream()
                var remaining = Long.MAX_VALUE
                if (range != null) {
                    if (r.code == 200) { // the server ignored Range: skip to the slice ourselves
                        skipFully(input, range.offset)
                    }
                    remaining = range.length
                }
                val buf = ByteArray(BUFFER_SIZE)
                var size = 0L
                tmp.parentFile?.mkdirs()
                tmp.outputStream().use { out ->
                    while (remaining > 0) {
                        val n = input.read(buf, 0, min(limiter.maxChunk(buf.size).toLong(), remaining).toInt())
                        if (n < 0) break
                        remaining -= n
                        val chunk = if (cipher != null) cipher.update(buf, 0, n) else buf.copyOf(n)
                        if (chunk != null && chunk.isNotEmpty()) { out.write(chunk); size += chunk.size }
                        count(n.toLong())
                        limiter.acquire(n)
                    }
                    if (range != null && remaining > 0) throw IOException("Connection closed early")
                    if (cipher != null) {
                        val tail = try {
                            cipher.doFinal()
                        } catch (e: GeneralSecurityException) {
                            throw FatalDownloadException("Could not decrypt segment (wrong key or corrupt data)", cause = e)
                        }
                        if (tail.isNotEmpty()) { out.write(tail); size += tail.size }
                    }
                }
                size
            }
        } finally {
            inFlight -= call
        }
        if (written == 0L) throw IOException("Empty segment")
        if (!tmp.renameTo(job.file)) {
            tmp.copyTo(job.file, overwrite = true)
            tmp.delete()
        }
        return written
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        val sink = ByteArray(BUFFER_SIZE)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) { left -= skipped; continue }
            val r = input.read(sink, 0, min(sink.size.toLong(), left).toInt())
            if (r < 0) throw IOException("Connection closed early")
            left -= r
        }
    }

    /** Default IV: the media sequence number as a big-endian 128-bit integer. */
    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
        return iv
    }

    /** Each key URI is downloaded once per run. */
    private inner class KeyCache(private val request: HlsRequest) {
        private val mutex = Mutex()
        private val cache = HashMap<String, ByteArray>()

        suspend fun get(uri: String): ByteArray = mutex.withLock {
            cache[uri] ?: withRetry { fetchBytes(uri, request, 4096) }.also {
                if (it.size != 16) throw FatalDownloadException("Invalid AES-128 key (${it.size} bytes)")
                cache[uri] = it
            }
        }
    }

    // ---- output ----

    private fun assemble(playlist: MediaPlaylist, segments: List<Job>, inits: Map<String, File>, output: File): Long {
        output.parentFile?.mkdirs()
        var written = 0L
        var lastInit: String? = null
        val buf = ByteArray(256 * 1024)
        output.outputStream().buffered(buf.size).use { out ->
            playlist.segments.forEachIndexed { i, s ->
                val initId = s.map?.identity
                if (initId != null && initId != lastInit) {
                    written += copy(inits.getValue(initId), out, buf)
                    lastInit = initId
                }
                written += copy(segments[i].file, out, buf)
            }
        }
        return written
    }

    private fun copy(file: File, out: java.io.OutputStream, buf: ByteArray): Long {
        var n = 0L
        file.inputStream().use { input ->
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                n += r
            }
        }
        return n
    }

    private fun mimeFor(ext: String) = when (ext) {
        "mp4" -> "video/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        else -> "video/mp2t"
    }

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
                awaitNetwork()
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_PLAYLIST_BYTES = 8 * 1024 * 1024
    }
}
