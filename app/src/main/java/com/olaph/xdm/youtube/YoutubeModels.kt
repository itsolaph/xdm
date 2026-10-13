package com.olaph.xdm.youtube

enum class StreamKind { MUXED, VIDEO_ONLY, AUDIO_ONLY }

enum class VideoCodec(val label: String) {
    H264("H.264"), VP9("VP9"), AV1("AV1"), OTHER("other");

    companion object {
        /** From the codec string YouTube reports ("avc1.640028", "vp09.00.40.08", "av01.0.12M.08", "vp9"). */
        fun fromCodecString(codec: String?): VideoCodec {
            val c = codec?.lowercase().orEmpty()
            return when {
                c.startsWith("avc1") || c.startsWith("avc3") || c.startsWith("h264") -> H264
                c.startsWith("vp09") || c.startsWith("vp9") -> VP9
                c.startsWith("av01") || c.startsWith("av1") -> AV1
                else -> OTHER
            }
        }
    }
}

/**
 * One downloadable stream of a video, as far as XDM cares. The [url] is signed, IP-bound and expires after a few
 * hours, so only [itag] (with the video id) is kept for later; the URL is fetched again when needed.
 */
data class YtStream(
    val itag: Int,
    val kind: StreamKind,
    /** File extension of the container: `mp4`, `webm` (video) or `m4a`, `webm` (audio). */
    val container: String,
    val videoCodec: VideoCodec = VideoCodec.OTHER,
    /** Audio streams: true for AAC (`mp4a`), the only audio codec XDM muxes. */
    val aac: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    /** Bits per second, 0 if unknown. */
    val bitrate: Int = 0,
    /** Bytes, -1 if unknown. */
    val contentLength: Long = -1,
    val url: String,
    /** False for dubbed / descriptive audio tracks of multi-language videos. */
    val originalTrack: Boolean = true,
) {
    // Never print the signed URL (logs, assertion messages, crash reports).
    override fun toString(): String =
        "YtStream(itag=$itag, $kind, $container, $videoCodec, ${width}x$height@$fps, br=$bitrate, len=$contentLength)"
}

data class YtInfo(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val streams: List<YtStream>,
    /** A live stream in progress: not downloadable. */
    val live: Boolean = false,
)

data class YtPlaylistItem(val videoId: String, val title: String, val channel: String, val durationSeconds: Long)

data class YtPlaylist(
    val id: String,
    val title: String,
    val channel: String,
    /** What YouTube says the playlist holds; -1 if unknown. */
    val totalCount: Long,
    /** At most [YoutubeResolver.PLAYLIST_CAP] entries. */
    val items: List<YtPlaylistItem>,
    /** More entries exist than [items] holds. */
    val truncated: Boolean,
    val thumbnailUrl: String? = null,
)

/** The "Default YouTube quality" setting. */
enum class YoutubeQuality(val label: String) {
    ASK("Ask every time"), BEST("Best available"), P1080("1080p"), P720("720p"), P480("480p"), AUDIO("Audio only (M4A)");

    /** Target height for the fixed-resolution choices. */
    val height: Int get() = when (this) {
        P1080 -> 1080
        P720 -> 720
        P480 -> 480
        else -> 0
    }
}
