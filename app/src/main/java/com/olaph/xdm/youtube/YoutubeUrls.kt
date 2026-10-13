package com.olaph.xdm.youtube

import com.olaph.xdm.engine.UrlUtils
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a YouTube link points at. */
sealed interface YoutubeLink {
    /**
     * One video. [listId] is set when the link also names a playlist (`watch?v=…&list=…`); the video wins, the
     * sheet offers the playlist as an alternative.
     */
    data class Video(val id: String, val listId: String? = null) : YoutubeLink

    data class Playlist(val id: String) : YoutubeLink
}

/**
 * Pure URL logic: finds out whether a string is a YouTube link and extracts the video or playlist id.
 * Nothing here touches the network or the NewPipe library.
 */
object YoutubeUrls {
    private val DOMAINS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val LIST_ID = Regex("[A-Za-z0-9_-]{2,80}")

    /** Path prefixes that carry the video id as the next segment (`/shorts/ID`, `/embed/ID`, …). */
    private val ID_PREFIXES = setOf("shorts", "embed", "live", "v", "e")

    fun isYoutubeHost(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    fun isYoutubeUrl(url: String?): Boolean = url?.toHttpUrlOrNull()?.let { isYoutubeHost(it.host) } == true

    /**
     * Parses a link out of [text] (a bare URL or text containing one, as shared by the YouTube app: "Title
     * https://youtu.be/ID?si=…"). Links without a scheme (`youtu.be/ID`) work as well. Null for everything
     * that is not a video or a playlist (home, search, channel pages, other sites).
     */
    fun parse(text: CharSequence?): YoutubeLink? {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val url = UrlUtils.extractFirstUrl(raw)?.toHttpUrlOrNull()
            ?: schemeless(raw)?.toHttpUrlOrNull()
            ?: return null
        return parse(url, depth = 0)
    }

    private fun schemeless(raw: String): String? {
        val token = raw.split(Regex("\\s+")).firstOrNull { t -> DOMAINS.any { t.lowercase().contains(it) } } ?: return null
        if (token.contains("://")) return null
        val host = token.substringBefore('/').substringBefore('?').lowercase()
        return if (isYoutubeHost(host)) "https://$token" else null
    }

    private fun parse(url: HttpUrl, depth: Int): YoutubeLink? {
        val host = url.host.lowercase().trimEnd('.')
        if (!isYoutubeHost(host)) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val list = url.queryParameter("list")?.takeIf { LIST_ID.matches(it) }

        if (host == "youtu.be" || host.endsWith(".youtu.be")) {
            val id = segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) } ?: return null
            return YoutubeLink.Video(id, list)
        }

        val first = segments.firstOrNull()?.lowercase()
        when {
            first == "watch" -> {
                // /watch?v=ID, and the old /watch/ID form
                val id = (url.queryParameter("v") ?: segments.getOrNull(1))?.takeIf { VIDEO_ID.matches(it) }
                if (id != null) return YoutubeLink.Video(id, list)
                return list?.let { YoutubeLink.Playlist(it) }
            }
            first in ID_PREFIXES && segments.size >= 2 -> {
                val id = segments[1].takeIf { VIDEO_ID.matches(it) } ?: return null
                return YoutubeLink.Video(id, list)
            }
            first == "playlist" -> return list?.let { YoutubeLink.Playlist(it) }
            first == "attribution_link" && depth == 0 -> {
                // /attribution_link?u=/watch%3Fv%3DID
                val inner = url.queryParameter("u") ?: return null
                val target = if (inner.startsWith("/")) "https://www.youtube.com$inner" else inner
                return target.toHttpUrlOrNull()?.let { parse(it, depth + 1) }
            }
        }
        return null
    }

    /** True for a page where the browser's download button should open the YouTube sheet. */
    fun isDownloadablePage(url: String?): Boolean = parse(url) != null

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    fun canonicalUrl(link: YoutubeLink): String = when (link) {
        is YoutubeLink.Video -> watchUrl(link.id)
        is YoutubeLink.Playlist -> playlistUrl(link.id)
    }

    /** Auto-generated mixes (`RD…`) and the personal lists need a sign-in or never end; they are not offered as "the playlist". */
    fun isRealPlaylist(id: String): Boolean = !id.startsWith("RD") && id != "WL" && id != "LL"
}
