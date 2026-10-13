package com.olaph.xdm.media

import com.olaph.xdm.engine.HlsVariant
import com.olaph.xdm.engine.MasterPlaylist
import com.olaph.xdm.engine.MediaPlaylist
import java.util.Locale

/** Turns parsed HLS playlists into the choices shown to the user. Pure, so it is unit tested. */
object HlsOptions {
    const val LIVE = "Live stream — not supported"

    /** Why this media playlist cannot be downloaded, or null. */
    fun problem(media: MediaPlaylist?): String? = when {
        media == null -> null
        !media.endList -> LIVE
        media.segments.isEmpty() -> "Empty playlist"
        else -> media.unsupportedEncryption()
    }

    /** Subtitle playlists (WebVTT chunks) are not media the user wants. */
    fun isSubtitles(media: MediaPlaylist): Boolean {
        val ext = media.segments.firstOrNull()?.uri?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()
        return ext == "vtt" || ext == "webvtt" || ext == "srt" || ext == "ttml" || ext == "dfxp"
    }

    /**
     * One option per video variant (best first, variants with audio before "video only" ones at equal
     * quality), then the audio-only renditions. [probed] is the media playlist of one variant: it supplies
     * the duration (for the size estimate), live/encryption status and the container.
     */
    fun forMaster(master: MasterPlaylist, probed: MediaPlaylist?): List<MediaOption> {
        val duration = probed?.duration?.takeIf { it > 0 } ?: -1.0
        val problem = problem(probed)
        val ext = probed?.outputExtension ?: "ts"
        val out = ArrayList<MediaOption>()

        val order = compareByDescending<HlsVariant> { it.height }
            .thenByDescending { master.hasMuxedAudio(it) }
            .thenByDescending { it.bandwidth }
        val seen = HashSet<String>()
        for (v in master.variants.sortedWith(order)) {
            if (!seen.add(v.uri)) continue
            val audioOnly = master.isAudioOnly(v)
            val videoOnly = !audioOnly && !master.hasMuxedAudio(v)
            val bw = v.estimateBandwidth
            val size = if (duration > 0 && bw > 0) (bw * duration / 8).toLong() else -1L
            out += MediaOption(
                url = v.uri,
                label = if (audioOnly) "Audio" else qualityLabel(v),
                detail = listOfNotNull(
                    bitrate(bw),
                    codecName(v.codecs),
                    if (videoOnly) "video only" else null,
                ).joinToString(" · ").ifEmpty { null },
                sizeBytes = size,
                sizeEstimated = size > 0,
                durationSec = duration,
                extension = ext,
                hls = true,
                audioOnly = audioOnly,
                videoOnly = videoOnly,
                unsupportedReason = problem,
            )
        }

        val variantUrls = master.variants.map { it.uri }.toSet()
        val audioSeen = HashSet<String>()
        for (r in master.renditions) {
            val uri = r.uri ?: continue
            if (r.type != "AUDIO" || uri in variantUrls || !audioSeen.add(uri)) continue
            out += MediaOption(
                url = uri,
                label = "Audio · " + (r.name ?: r.language ?: r.groupId ?: "track"),
                detail = r.language?.takeIf { it != r.name },
                durationSec = duration,
                extension = "m4a",
                hls = true,
                audioOnly = true,
                unsupportedReason = problem,
            )
        }
        return out
    }

    /** A bare media playlist: a single stream of unknown quality. Null for subtitle playlists. */
    fun forMedia(media: MediaPlaylist): MediaOption? {
        if (isSubtitles(media)) return null
        val problem = problem(media)
        val duration = media.duration
        val details = listOfNotNull(
            if (duration > 0) duration(duration) else null,
            "${media.segments.size} segments",
            if (media.segments.any { it.key?.isAes128 == true }) "AES-128" else null,
        )
        return MediaOption(
            url = media.url,
            label = "Stream",
            detail = details.joinToString(" · "),
            durationSec = if (duration > 0) duration else -1.0,
            extension = media.outputExtension,
            hls = true,
            audioOnly = media.outputExtension == "aac" || media.outputExtension == "mp3",
            unsupportedReason = problem,
        )
    }

    fun qualityLabel(v: HlsVariant): String {
        val fps = v.frameRate?.takeIf { it >= 49 }?.let { Math.round(it).toInt() }
        return when {
            v.height > 0 -> "${v.height}p" + (fps?.toString() ?: "")
            v.width > 0 -> "${v.width}w"
            v.bandwidth > 0 -> bitrate(v.estimateBandwidth).orEmpty()
            else -> "Stream"
        }
    }

    fun bitrate(bps: Long): String? = when {
        bps <= 0 -> null
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        else -> "${bps / 1000} kbps"
    }

    /** A readable name for the first video/audio codec in an RFC 6381 list. */
    fun codecName(codecs: String?): String? {
        if (codecs.isNullOrBlank()) return null
        val parts = codecs.lowercase().split(',').map { it.trim() }
        parts.firstOrNull { it.startsWith("avc") }?.let { return "H.264" }
        parts.firstOrNull { it.startsWith("hvc") || it.startsWith("hev") }?.let { return "H.265" }
        parts.firstOrNull { it.startsWith("vp09") || it.startsWith("vp9") }?.let { return "VP9" }
        parts.firstOrNull { it.startsWith("av01") }?.let { return "AV1" }
        parts.firstOrNull { it.startsWith("mp4a") }?.let { return "AAC" }
        return null
    }

    fun duration(sec: Double): String {
        val total = sec.toLong()
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
