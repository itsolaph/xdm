package com.olaph.xdm.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Extra request headers that travel with a download (Cookie, User-Agent, Referer, ...). They are stored
 * in Room as one string so that probes and every segment request, also after a resume or an app
 * restart, carry the same context the browser had.
 */
object HeaderCodec {
    /** Headers the engine controls itself; a page-supplied value would break range handling. */
    private val RESERVED = setOf(
        "range", "accept-encoding", "host", "content-length", "connection", "transfer-encoding",
        "if-range", "if-match", "if-none-match", "if-modified-since", "if-unmodified-since", "upgrade", "te",
    )

    private const val TOKEN_SYMBOLS = "!#\$%&'*+-.^_`|~"

    /** Drops reserved, blank, or non-ASCII/multi-line headers (OkHttp rejects those with an exception). */
    fun sanitize(headers: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((rawName, rawValue) in headers) {
            val name = rawName.trim()
            val value = rawValue.trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (name.lowercase() in RESERVED) continue
            if (!name.all { it.isTokenChar() }) continue
            if (!value.all { it == '\t' || it in ' '..'~' }) continue
            // Case-insensitive de-duplication; the last one wins.
            out.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { out.remove(it) }
            out[name] = value
        }
        return out
    }

    /** `name=value&name=value`, both percent-encoded. null when there is nothing to store. */
    fun encode(headers: Map<String, String>): String? {
        val clean = sanitize(headers)
        if (clean.isEmpty()) return null
        return clean.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
    }

    fun decode(encoded: String?): Map<String, String> {
        if (encoded.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in encoded.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val name = runCatching { URLDecoder.decode(pair.substring(0, i), "UTF-8") }.getOrNull() ?: continue
            val value = runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrNull() ?: continue
            out[name] = value
        }
        return sanitize(out)
    }

    /**
     * Playlists point at segments, keys and variants on other hosts (CDNs). The Cookie header was captured
     * for [capturedFor] only, so it is dropped for URLs on an unrelated host; other headers stay.
     */
    fun scopeToHost(headers: Map<String, String>, capturedFor: String, url: String): Map<String, String> {
        val cookieName = headers.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) } ?: return headers
        val from = capturedFor.toHttpUrlOrNull()?.host?.lowercase()
        val to = url.toHttpUrlOrNull()?.host?.lowercase()
        val related = from != null && to != null && (from == to || from.endsWith(".$to") || to.endsWith(".$from"))
        return if (related) headers else headers - cookieName
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun Char.isTokenChar() = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z' || this in TOKEN_SYMBOLS
}

/** Builds the header set a browser would send for a download started from a page. */
object DownloadHeaders {
    fun fromBrowser(cookie: String?, userAgent: String?, referer: String?, withOrigin: Boolean = false): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!cookie.isNullOrBlank()) out["Cookie"] = cookie
        if (!userAgent.isNullOrBlank()) out["User-Agent"] = userAgent
        if (!referer.isNullOrBlank() && UrlUtils.isHttpUrl(referer)) {
            out["Referer"] = referer
            // Players fetch streams with CORS requests, which carry Origin; some CDNs insist on it.
            if (withOrigin) origin(referer)?.let { out["Origin"] = it }
        }
        return HeaderCodec.sanitize(out)
    }

    /** `https://host[:port]` of a page URL, or null when it is not an http(s) URL. */
    fun origin(pageUrl: String?): String? {
        if (pageUrl == null || !UrlUtils.isHttpUrl(pageUrl)) return null
        val schemeEnd = pageUrl.indexOf("://") + 3
        val end = pageUrl.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd).let { if (it < 0) pageUrl.length else it }
        return pageUrl.substring(0, end).takeIf { it.length > schemeEnd }
    }
}
