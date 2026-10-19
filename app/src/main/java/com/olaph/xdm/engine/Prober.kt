package com.olaph.xdm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Executes the call on the IO dispatcher, hands the response to [block], and aborts the in-flight
 * socket operation as soon as the calling coroutine is cancelled.
 */
internal suspend fun <T> Call.executeAndUse(block: suspend (Response) -> T): T = coroutineScope {
    val call = this@executeAndUse
    val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
    try {
        withContext(Dispatchers.IO) { call.execute().use { block(it) } }
    } finally {
        watcher.cancel()
    }
}

/** Host the user's download URL points at; used to keep the Cookie header from leaking via redirects. */
internal class OriginHost(val host: String)

/**
 * Browsers only send a site's cookies to that site. The engine forwards the stored `Cookie` header, so a
 * redirect to a different host (CDN, signed URL) must not carry it along; other headers are unaffected.
 */
internal fun OkHttpClient.withScopedCookies(): OkHttpClient = newBuilder().addNetworkInterceptor { chain ->
    val req = chain.request()
    val origin = req.tag(OriginHost::class.java)
    if (origin != null && req.header("Cookie") != null && !req.url.host.equals(origin.host, ignoreCase = true)) {
        chain.proceed(req.newBuilder().removeHeader("Cookie").build())
    } else {
        chain.proceed(req)
    }
}.build()

internal fun buildRequest(
    url: String,
    userAgent: String,
    head: Boolean = false,
    range: String? = null,
    headers: Map<String, String> = emptyMap(),
): Request = try {
    Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .apply { HeaderCodec.sanitize(headers).forEach { (k, v) -> header(k, v) } } // may override the User-Agent
        .apply { url.toHttpUrlOrNull()?.let { tag(OriginHost::class.java, OriginHost(it.host)) } }
        // Never let OkHttp transparently gzip: byte ranges must address the real file.
        .header("Accept-Encoding", "identity")
        .apply {
            if (range != null) header("Range", range)
            if (head) head()
        }
        .build()
} catch (e: IllegalArgumentException) {
    throw FatalDownloadException("Invalid URL: $url", cause = e)
}

internal fun checkStatus(code: Int) {
    when {
        code in 200..299 -> Unit
        code == 408 || code == 429 || code >= 500 -> throw HttpStatusException(code)
        else -> throw FatalDownloadException("HTTP $code", code)
    }
}

class Prober(private val client: OkHttpClient) {

    /**
     * HEAD first; if that does not prove range support and a size, fall back to `GET Range: bytes=0-0`
     * which also reveals servers that support ranges without advertising `Accept-Ranges`.
     */
    suspend fun probe(url: String, userAgent: String, headers: Map<String, String> = emptyMap()): ProbeResult {
        var head: ProbeResult? = null
        try {
            head = client.newCall(buildRequest(url, userAgent, head = true, headers = headers)).executeAndUse { r ->
                if (!r.isSuccessful) null else fromResponse(r, r.header("Content-Length")?.toLongOrNull() ?: -1,
                    r.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FatalDownloadException) {
            throw e
        } catch (_: IOException) {
            // Some servers reject or break on HEAD; the GET probe decides.
        }
        if (head != null && head.resumable) return head

        val get = client.newCall(buildRequest(url, userAgent, range = "bytes=0-0", headers = headers)).executeAndUse { r ->
            when (r.code) {
                206 -> {
                    val total = r.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1
                    fromResponse(r, total, true)
                }
                in 200..299 -> fromResponse(r, r.header("Content-Length")?.toLongOrNull() ?: -1, false)
                else -> {
                    // A successful HEAD is better than a failed GET (e.g. 405/416 on odd servers).
                    if (head != null) head else {
                        checkStatus(r.code)
                        throw IOException("Unexpected HTTP ${r.code}")
                    }
                }
            }
        }
        return get
    }

    private fun fromResponse(r: Response, length: Long, ranges: Boolean): ProbeResult {
        val finalUrl = r.request.url.toString()
        val mime = r.header("Content-Type")?.let { MimeTypes.normalize(it) }?.takeIf { it.isNotEmpty() }
        val name = FilenameUtils.resolve(r.header("Content-Disposition"), finalUrl, mime)
        return ProbeResult(
            finalUrl = finalUrl,
            contentLength = length,
            acceptRanges = ranges,
            etag = r.header("ETag"),
            lastModified = r.header("Last-Modified"),
            fileName = name,
            mimeType = mime,
        )
    }
}
