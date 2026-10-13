package com.olaph.xdm.media

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

/**
 * Decides from a URL alone whether a request is worth looking at. Nothing here touches the network; the
 * candidates it lets through are verified afterwards (content type, playlist contents).
 */
object MediaClassifier {

    enum class Kind {
        VIDEO_FILE, AUDIO_FILE, HLS, DASH,

        /** No telling from the URL (`videoplayback?mime=video/mp4`, `/hls/abc`): the response decides. */
        UNKNOWN_MEDIA,

        /** `.ts` / `.m4s` chunk of a stream: counted, never listed. */
        SEGMENT,
        IGNORE,
    }

    private val VIDEO_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv")
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val SEGMENT_EXT = setOf("ts", "m4s", "cmfv", "cmfa", "fmp4")
    private val IGNORED_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "svg", "ico", "bmp", "avif", "js", "mjs", "css", "map", "woff", "woff2",
        "ttf", "otf", "eot", "html", "htm", "json", "xml", "txt", "vtt", "srt", "webvtt", "wasm", "pdf",
    )

    /**
     * Hosts whose streams are signed/ranged chunks of YouTube. Sniffing never lists them (a single `videoplayback`
     * request is a few seconds of one track, not a video); YouTube downloads go through the extractor instead.
     */
    private val YOUTUBE_STREAM_HOSTS = listOf("googlevideo.com")
    private val YOUTUBE_PAGE_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")

    fun classify(url: String): Kind {
        val http = url.toHttpUrlOrNull() ?: return Kind.IGNORE
        val host = http.host.lowercase()
        if (YOUTUBE_STREAM_HOSTS.any { host == it || host.endsWith(".$it") }) return Kind.IGNORE

        val path = http.encodedPath.lowercase()
        val ext = extensionOf(path)
        when (ext) {
            in IGNORED_EXT -> return Kind.IGNORE
            "m3u8" -> return Kind.HLS
            "mpd" -> return Kind.DASH
            in VIDEO_EXT -> return Kind.VIDEO_FILE
            in AUDIO_EXT -> return Kind.AUDIO_FILE
            in SEGMENT_EXT -> return Kind.SEGMENT
        }
        if (hasMediaPattern(http, path)) return Kind.UNKNOWN_MEDIA
        return Kind.IGNORE
    }

    private fun hasMediaPattern(http: HttpUrl, path: String): Boolean {
        val query = http.encodedQuery?.lowercase().orEmpty()
        val decodedQuery = runCatching { URLDecoder.decode(query, "UTF-8") }.getOrDefault(query)
        if ("mime=video" in decodedQuery || "mime=audio" in decodedQuery) return true
        if ("videoplayback" in path) return true
        if (path.contains("/hls/") || path.contains("playlist.m3u8") || path.contains("master.m3u8")) return true
        // A manifest passed as a parameter: player.php?src=https://cdn/x/master.m3u8
        return decodedQuery.split('&').any { param ->
            val value = param.substringAfter('=', "").substringBefore('#')
            value.endsWith(".m3u8") || value.endsWith(".mpd") || value.contains(".m3u8?") || value.contains(".mpd?")
        }
    }

    /** True for an `.m3u8` URL (also with a query string). */
    fun isHlsUrl(url: String): Boolean = url.toHttpUrlOrNull()?.encodedPath?.lowercase()?.endsWith(".m3u8") == true

    /**
     * A chunk of a stream rather than a standalone file: used once a manifest was seen on the page, so
     * that `seg-12.m4s`, `chunk_0003.mp4` or `video_1080/frag-5.aac` do not flood the list.
     */
    fun isChunkLike(url: String): Boolean {
        val http = url.toHttpUrlOrNull() ?: return false
        val path = http.encodedPath.lowercase()
        val ext = extensionOf(path)
        if (ext in SEGMENT_EXT) return true
        if (ext != "aac" && ext != "mp4" && ext != "m4a" && ext != "m4v" && ext != "webm") return false
        val name = path.substringAfterLast('/')
        return CHUNK_NAME.containsMatchIn(name)
    }

    private val CHUNK_NAME = Regex("^(seg|segment|chunk|frag|fragment|init|filesequence)\\b|(seg|segment|chunk|frag|fragment)[-_]?\\d+")

    /** Case-insensitive extension of the last path segment, or "" (at most 5 alphanumeric characters). */
    fun extensionOf(path: String): String {
        val last = path.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val dot = last.lastIndexOf('.')
        if (dot < 0 || dot == last.length - 1) return ""
        val ext = last.substring(dot + 1).lowercase()
        return if (ext.length <= 5 && ext.all { it.isLetterOrDigit() }) ext else ""
    }

    /**
     * Key used to de-duplicate: lower-case scheme and host, default port and fragment dropped. The query
     * string stays as it is because it usually carries the token that makes the URL work.
     */
    fun normalize(url: String): String {
        val http = url.toHttpUrlOrNull() ?: return url.substringBefore('#')
        return http.newBuilder().fragment(null).build().toString()
    }

    /**
     * A YouTube page. The player's own requests are not sniffed there (they are signed googlevideo fragments); the
     * browser's download button opens the YouTube sheet instead (see `YoutubeUrls`).
     */
    fun isYoutubePage(pageUrl: String?): Boolean {
        val host = pageUrl?.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return YOUTUBE_PAGE_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
