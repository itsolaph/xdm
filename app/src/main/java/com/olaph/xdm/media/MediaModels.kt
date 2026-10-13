package com.olaph.xdm.media

/** What a detected URL points at. */
enum class MediaKind { FILE, HLS, DASH }

/** One thing the user can pick in the "download video" sheet (a file, or one HLS quality / audio rendition). */
data class MediaOption(
    /** The URL handed to the downloader (for HLS: the media playlist of this quality). */
    val url: String,
    /** "1080p", "MP4", "Audio · English"... */
    val label: String,
    /** "2.8 Mbps · H.264 · video only" */
    val detail: String? = null,
    /** -1 when unknown. */
    val sizeBytes: Long = -1,
    /** True when [sizeBytes] is bandwidth × duration rather than a real Content-Length. */
    val sizeEstimated: Boolean = false,
    val durationSec: Double = -1.0,
    /** Extension of the file this option will produce (mp4, ts, mp3...). */
    val extension: String = "mp4",
    val mimeType: String? = null,
    val hls: Boolean = false,
    val audioOnly: Boolean = false,
    val videoOnly: Boolean = false,
    /** Non-null = shown but not selectable ("Live stream — not supported"). */
    val unsupportedReason: String? = null,
) {
    val supported: Boolean get() = unsupportedReason == null
}

/** A media URL found on the current page, after it was checked (content type / playlist parsed). */
data class DetectedMedia(
    /** Normalized URL, used for de-duplication. */
    val key: String,
    val url: String,
    val kind: MediaKind,
    /** "MP4", "WEBM", "HLS", "DASH", "Audio" */
    val typeLabel: String,
    val options: List<MediaOption>,
    /** Order of discovery; newer is larger. */
    val seq: Long,
    /** Page (or frame) that requested it; sent as Referer / Origin when downloading. */
    val referer: String? = null,
    val playing: Boolean = false,
) {
    /** At least one option can be downloaded. */
    val supported: Boolean get() = options.any { it.supported }
}

/** Outcome of checking a candidate URL. */
sealed interface MediaAnalysis {
    data object Rejected : MediaAnalysis
    class Found(val kind: MediaKind, val typeLabel: String, val options: List<MediaOption>) : MediaAnalysis {
        /** URLs of child playlists (HLS variants / renditions) so that they are not listed a second time. */
        val childUrls: List<String> get() = if (kind == MediaKind.HLS) options.filter { it.hls }.map { it.url } else emptyList()
    }
}
