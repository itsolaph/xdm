package com.olaph.xdm.youtube

import java.net.URLDecoder
import java.net.URLEncoder

enum class OptionKind { VIDEO, PROGRESSIVE, AUDIO }

/** One row of the format sheet. [video] and [audio] are what actually gets downloaded for it. */
data class YtOption(
    val id: String,
    val kind: OptionKind,
    /** "1080p", "720p60", "360p", "Audio only". */
    val label: String,
    /** Second line: codec, container, bitrate. */
    val detail: String,
    /** Frame height, 0 for audio. */
    val height: Int,
    val sizeBytes: Long,
    /** True when [sizeBytes] was computed from bitrate × duration because a stream has no length. */
    val sizeEstimated: Boolean,
    val video: YtStream?,
    val audio: YtStream?,
) {
    val streams: List<YtStream> get() = listOfNotNull(video, audio)

    /** What goes into the file name: "720p" or "" for audio. */
    val fileLabel: String get() = if (kind == OptionKind.AUDIO) "" else label

    val extension: String get() = if (kind == OptionKind.AUDIO) "m4a" else "mp4"
}

/**
 * Chooses the streams for a video. Pure functions over [YtInfo]: the sheet lists [options], the "Default YouTube
 * quality" setting goes through [pick], and a stored [YtPlan] is mapped back onto fresh streams by [resolve].
 *
 * Video comes from the video-only DASH streams (H.264 preferred; [allowedCodecs] says what else the muxer may take)
 * plus the best AAC audio; the progressive 360p stream, which is one file already, is offered as the fast path.
 */
object FormatSelector {
    /** Order of preference between codecs at the same resolution: H.264 plays everywhere. */
    private val CODEC_RANK = mapOf(VideoCodec.H264 to 0, VideoCodec.AV1 to 1, VideoCodec.VP9 to 2)

    /**
     * Video codecs that end up in an MP4. H.264 (YouTube offers it up to 1080p) and AV1 (1440p and 2160p, in MP4) pass
     * the muxer's tests; VP9, which YouTube only serves in WebM, does not (see `Mp4DashMuxer`).
     */
    val DEFAULT_CODECS: Set<VideoCodec> = setOf(VideoCodec.H264, VideoCodec.AV1)

    fun bestAudio(info: YtInfo): YtStream? = info.streams
        .filter { it.kind == StreamKind.AUDIO_ONLY && it.aac && it.container == "m4a" }
        .maxWithOrNull(compareBy<YtStream>({ it.originalTrack }, { it.bitrate }, { it.contentLength }))

    fun options(info: YtInfo, allowedCodecs: Set<VideoCodec> = DEFAULT_CODECS): List<YtOption> {
        val audio = bestAudio(info)
        val out = ArrayList<YtOption>()

        if (audio != null) {
            // One row per (height, 60fps or not); inside it the most compatible codec wins.
            val candidates = info.streams.filter {
                it.kind == StreamKind.VIDEO_ONLY && it.height > 0 && it.videoCodec in allowedCodecs && it.container in setOf("mp4", "webm")
            }
            val rows = candidates.groupBy { it.height to (it.fps > 30) }.map { (_, group) ->
                group.minWith(compareBy<YtStream>({ CODEC_RANK[it.videoCodec] ?: 9 }, { -it.bitrate }))
            }.sortedWith(compareByDescending<YtStream> { it.height }.thenByDescending { it.fps })
            for (v in rows) out += videoOption(v, audio, info.durationSeconds)
        }

        info.streams.filter { it.kind == StreamKind.MUXED && it.container == "mp4" && it.height > 0 }
            .sortedByDescending { it.height }
            .forEach { p ->
                out += YtOption(
                    id = "p:${p.itag}", kind = OptionKind.PROGRESSIVE,
                    label = heightLabel(p), detail = "Fast: already one file, no merging",
                    height = p.height, sizeBytes = p.contentLength,
                    sizeEstimated = false, video = p, audio = null,
                )
            }

        if (audio != null) {
            val size = sizeOf(listOf(audio), info.durationSeconds)
            out += YtOption(
                id = "a:${audio.itag}", kind = OptionKind.AUDIO, label = "Audio only",
                detail = "M4A (AAC)" + if (audio.bitrate > 0) " · ${audio.bitrate / 1000} kbps" else "",
                height = 0, sizeBytes = size.first, sizeEstimated = size.second, video = null, audio = audio,
            )
        }
        return out
    }

    private fun videoOption(v: YtStream, a: YtStream, durationSeconds: Long): YtOption {
        val size = sizeOf(listOf(v, a), durationSeconds)
        val codec = if (v.videoCodec == VideoCodec.H264) "" else "${v.videoCodec.label} · "
        return YtOption(
            id = "v:${v.itag}+${a.itag}", kind = OptionKind.VIDEO,
            label = heightLabel(v) + if (v.videoCodec == VideoCodec.H264) "" else " ${v.videoCodec.label}",
            detail = "${codec}MP4 · video and audio merged",
            height = v.height, sizeBytes = size.first, sizeEstimated = size.second, video = v, audio = a,
        )
    }

    private fun heightLabel(s: YtStream) = "${s.height}p" + if (s.fps > 30) s.fps.toString() else ""

    /** Total bytes of [streams]; falls back to bitrate × duration (estimated) for streams without a length. */
    private fun sizeOf(streams: List<YtStream>, durationSeconds: Long): Pair<Long, Boolean> {
        var total = 0L
        var estimated = false
        for (s in streams) {
            if (s.contentLength > 0) {
                total += s.contentLength
            } else if (s.bitrate > 0 && durationSeconds > 0) {
                total += s.bitrate.toLong() * durationSeconds / 8
                estimated = true
            } else {
                return -1L to true
            }
        }
        return total to estimated
    }

    /** The row the "Default YouTube quality" setting stands for; null for [YoutubeQuality.ASK] or when nothing is downloadable. */
    fun pick(info: YtInfo, quality: YoutubeQuality, options: List<YtOption> = options(info)): YtOption? {
        val video = options.filter { it.kind == OptionKind.VIDEO }
        val progressive = options.filter { it.kind == OptionKind.PROGRESSIVE }
        val audio = options.firstOrNull { it.kind == OptionKind.AUDIO }
        return when (quality) {
            YoutubeQuality.ASK -> null
            YoutubeQuality.AUDIO -> audio ?: video.lastOrNull() ?: progressive.lastOrNull()
            YoutubeQuality.BEST -> video.firstOrNull() ?: progressive.firstOrNull() ?: audio
            else -> {
                val h = quality.height
                video.firstOrNull { it.height == h } ?: video.firstOrNull { it.height < h }
                    ?: progressive.firstOrNull { it.height <= h }
                    ?: video.lastOrNull() ?: progressive.lastOrNull() ?: audio
            }
        }
    }

    /** Which setting a chosen row corresponds to, for "remember this choice"; null when it matches none of them. */
    fun qualityFor(option: YtOption, options: List<YtOption>): YoutubeQuality? = when {
        option.kind == OptionKind.AUDIO -> YoutubeQuality.AUDIO
        option.kind == OptionKind.VIDEO && option.id == options.firstOrNull { it.kind == OptionKind.VIDEO }?.id -> YoutubeQuality.BEST
        option.kind == OptionKind.VIDEO -> when (option.height) {
            1080 -> YoutubeQuality.P1080
            720 -> YoutubeQuality.P720
            480 -> YoutubeQuality.P480
            else -> null
        }
        else -> null
    }

    /**
     * Maps a stored [plan] onto the streams of a freshly resolved [info]. The same itag is used when YouTube still
     * offers it; otherwise a stream with the same kind, height and codec; otherwise the plan is gone
     * ([YtErrorKind.FORMAT_GONE]), because the bytes already on disk would not match a different stream.
     */
    fun resolve(info: YtInfo, plan: YtPlan, allowedCodecs: Set<VideoCodec> = DEFAULT_CODECS): YtOption {
        if (!plan.hasStreams) {
            val q = plan.quality ?: YoutubeQuality.BEST
            return pick(info, q, options(info, allowedCodecs)) ?: throw YoutubeException(YtErrorKind.UNAVAILABLE)
        }
        val videoKind = if (plan.mode == YtPlan.Mode.PROGRESSIVE) StreamKind.MUXED else StreamKind.VIDEO_ONLY
        val video = if (plan.videoItag > 0) findVideo(info, plan.videoItag, videoKind, plan.videoHeight, plan.videoCodec) else null
        val audio = if (plan.audioItag > 0) {
            info.streams.firstOrNull { it.itag == plan.audioItag && it.kind == StreamKind.AUDIO_ONLY } ?: bestAudio(info)
        } else null
        if ((plan.videoItag > 0 && video == null) || (plan.audioItag > 0 && audio == null)) {
            throw YoutubeException(YtErrorKind.FORMAT_GONE)
        }
        val kind = when (plan.mode) {
            YtPlan.Mode.MUX -> OptionKind.VIDEO
            YtPlan.Mode.PROGRESSIVE -> OptionKind.PROGRESSIVE
            YtPlan.Mode.AUDIO -> OptionKind.AUDIO
        }
        val size = sizeOf(listOfNotNull(video, audio), info.durationSeconds)
        return YtOption(
            id = plan.optionId, kind = kind, label = plan.label.ifEmpty { "Audio only" }, detail = "",
            height = video?.height ?: 0, sizeBytes = size.first, sizeEstimated = size.second, video = video, audio = audio,
        )
    }

    /** The stream with this itag, or (the response of another client may name formats differently) one of the same height and codec. */
    private fun findVideo(info: YtInfo, itag: Int, kind: StreamKind, height: Int, codec: VideoCodec): YtStream? =
        info.streams.firstOrNull { it.itag == itag && it.kind == kind }
            ?: if (height > 0) info.streams.firstOrNull { it.kind == kind && it.height == height && it.videoCodec == codec } else null

    /** `Title (720p).mp4`, `Title.m4a`. The title is sanitized and shortened so the whole name stays below the file-system limit. */
    fun fileName(title: String, option: YtOption): String = fileName(title, option.fileLabel, option.extension)

    fun fileName(title: String, label: String, extension: String): String {
        val cleaned = title.replace('/', '-').replace('\\', '-').replace(Regex("[\\u0000-\\u001f]"), " ").trim()
        val base = if (cleaned.isEmpty()) "YouTube video" else com.olaph.xdm.engine.FilenameUtils.sanitize(cleaned)
            .take(140).trim().trimEnd('.', ' ').ifEmpty { "YouTube video" }
        val suffix = if (label.isEmpty()) "" else " ($label)"
        return com.olaph.xdm.engine.FilenameUtils.sanitize("$base$suffix.$extension")
    }
}

/**
 * What an enqueued YouTube download is: the video, how it is built from streams, and the bytes expected. Stored on the
 * download row ([com.olaph.xdm.data.DownloadEntity.extra]) so that a resume after a restart or hours later re-resolves
 * the same streams by itag instead of relying on a URL that expired.
 *
 * A playlist entry starts with only a [quality] ("deferred"): the streams are chosen when the download starts.
 */
data class YtPlan(
    val videoId: String,
    val mode: Mode = Mode.MUX,
    val videoItag: Int = 0,
    val audioItag: Int = 0,
    val videoBytes: Long = -1,
    val audioBytes: Long = -1,
    val videoHeight: Int = 0,
    val videoCodec: VideoCodec = VideoCodec.OTHER,
    /** "720p"; empty for audio. */
    val label: String = "",
    val quality: YoutubeQuality? = null,
) {
    enum class Mode { MUX, PROGRESSIVE, AUDIO }

    val hasStreams: Boolean get() = videoItag > 0 || audioItag > 0

    val optionId: String
        get() = when (mode) {
            Mode.MUX -> "v:$videoItag+$audioItag"
            Mode.PROGRESSIVE -> "p:$videoItag"
            Mode.AUDIO -> "a:$audioItag"
        }

    /** Sum of the streams' sizes, -1 as soon as one is unknown. */
    val totalBytes: Long
        get() = listOf(videoBytes.takeIf { videoItag > 0 }, audioBytes.takeIf { audioItag > 0 })
            .filterNotNull().let { l -> if (l.isEmpty() || l.any { it <= 0 }) -1L else l.sum() }

    fun encode(): String = listOf(
        "id" to videoId, "mode" to mode.name, "vi" to videoItag.toString(), "ai" to audioItag.toString(),
        "vb" to videoBytes.toString(), "ab" to audioBytes.toString(), "vh" to videoHeight.toString(),
        "vc" to videoCodec.name, "label" to label, "q" to (quality?.name ?: ""),
    ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }

    companion object {
        fun forOption(videoId: String, option: YtOption): YtPlan = YtPlan(
            videoId = videoId,
            mode = when (option.kind) {
                OptionKind.VIDEO -> Mode.MUX
                OptionKind.PROGRESSIVE -> Mode.PROGRESSIVE
                OptionKind.AUDIO -> Mode.AUDIO
            },
            videoItag = option.video?.itag ?: 0,
            audioItag = option.audio?.itag ?: 0,
            videoBytes = option.video?.contentLength ?: -1,
            audioBytes = option.audio?.contentLength ?: -1,
            videoHeight = option.video?.height ?: 0,
            videoCodec = option.video?.videoCodec ?: VideoCodec.OTHER,
            label = option.fileLabel,
        )

        /** A playlist entry: the streams are picked from [quality] when the download starts. */
        fun deferred(videoId: String, quality: YoutubeQuality): YtPlan = YtPlan(videoId, quality = quality)

        fun decode(text: String?): YtPlan? {
            if (text.isNullOrEmpty()) return null
            val m = HashMap<String, String>()
            for (pair in text.split('&')) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                m[pair.substring(0, i)] = runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }.getOrDefault("")
            }
            val id = m["id"]?.takeIf { it.isNotEmpty() } ?: return null
            return YtPlan(
                videoId = id,
                mode = m["mode"]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.MUX,
                videoItag = m["vi"]?.toIntOrNull() ?: 0,
                audioItag = m["ai"]?.toIntOrNull() ?: 0,
                videoBytes = m["vb"]?.toLongOrNull() ?: -1,
                audioBytes = m["ab"]?.toLongOrNull() ?: -1,
                videoHeight = m["vh"]?.toIntOrNull() ?: 0,
                videoCodec = m["vc"]?.let { runCatching { VideoCodec.valueOf(it) }.getOrNull() } ?: VideoCodec.OTHER,
                label = m["label"].orEmpty(),
                quality = m["q"]?.takeIf { it.isNotEmpty() }?.let { runCatching { YoutubeQuality.valueOf(it) }.getOrNull() },
            )
        }
    }
}
