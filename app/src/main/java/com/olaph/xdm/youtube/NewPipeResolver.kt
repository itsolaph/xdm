package com.olaph.xdm.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit

/** Turns a video or playlist id into streams. The real one is [NewPipeResolver]; tests plug in a fake. */
interface YoutubeResolver {
    /** Every failure is a [YoutubeException]; the library's own exceptions never escape. */
    suspend fun resolve(videoId: String): YtInfo

    suspend fun playlist(playlistId: String): YtPlaylist

    companion object {
        /** A playlist is cut here; the sheet says so. */
        const val PLAYLIST_CAP = 200
    }
}

/**
 * NewPipeExtractor on top of the app's OkHttp client. The library does the hard parts (player requests, signature and
 * `n` parameter deciphering); this class only maps its objects onto XDM's small model and its exceptions onto
 * [YoutubeException].
 */
class NewPipeResolver(client: OkHttpClient) : YoutubeResolver {
    private val downloader = NewPipeDownloader(client)

    private var initialized = false

    @Synchronized
    private fun ensureInit() {
        if (initialized) return
        // Locale only steers which regional variant of YouTube is asked; "ID" is what the app was set up with.
        NewPipe.init(downloader, Localization("en", "ID"), ContentCountry("ID"))
        initialized = true
    }

    override suspend fun resolve(videoId: String): YtInfo = withContext(Dispatchers.IO) {
        try {
            ensureInit()
            map(StreamInfo.getInfo(ServiceList.YouTube, YoutubeUrls.watchUrl(videoId)), videoId)
        } catch (t: Throwable) {
            throw YoutubeErrors.from(t)
        }
    }

    override suspend fun playlist(playlistId: String): YtPlaylist = withContext(Dispatchers.IO) {
        try {
            ensureInit()
            val url = YoutubeUrls.playlistUrl(playlistId)
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val items = ArrayList<YtPlaylistItem>()
            var more = false
            fun add(list: List<StreamInfoItem>) {
                for (i in list) {
                    val id = (YoutubeUrls.parse(i.url) as? YoutubeLink.Video)?.id ?: continue
                    if (items.size >= YoutubeResolver.PLAYLIST_CAP) { more = true; return }
                    items += YtPlaylistItem(id, i.name.orEmpty(), i.uploaderName.orEmpty(), i.duration)
                }
            }
            add(info.relatedItems)
            var next = info.nextPage
            while (!more && next != null && items.size < YoutubeResolver.PLAYLIST_CAP) {
                val page = PlaylistInfo.getMoreItems(ServiceList.YouTube, url, next)
                add(page.items)
                next = page.nextPage
            }
            if (next != null && items.size >= YoutubeResolver.PLAYLIST_CAP) more = true
            val total = info.streamCount
            YtPlaylist(
                id = playlistId,
                title = info.name.orEmpty(),
                channel = info.uploaderName.orEmpty(),
                totalCount = if (total >= 0) total else items.size.toLong(),
                items = items,
                truncated = more || total > items.size && items.size >= YoutubeResolver.PLAYLIST_CAP,
                thumbnailUrl = bestThumbnail(info.thumbnails),
            )
        } catch (t: Throwable) {
            throw YoutubeErrors.from(t)
        }
    }

    private fun map(info: StreamInfo, videoId: String): YtInfo {
        val streams = ArrayList<YtStream>()
        info.videoOnlyStreams.orEmpty().forEach { video(it, StreamKind.VIDEO_ONLY)?.let(streams::add) }
        info.videoStreams.orEmpty().forEach { video(it, StreamKind.MUXED)?.let(streams::add) }
        info.audioStreams.orEmpty().forEach { audio(it)?.let(streams::add) }
        return YtInfo(
            videoId = videoId,
            title = info.name.orEmpty(),
            channel = info.uploaderName.orEmpty(),
            durationSeconds = info.duration,
            thumbnailUrl = bestThumbnail(info.thumbnails),
            streams = streams,
            live = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM,
        )
    }

    /** Only plain HTTP(S) streams with a URL; manifests (DASH/HLS) are not what the segment engine can fetch. */
    private fun usable(s: Stream) = s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.isUrl && !s.content.isNullOrBlank()

    private fun video(s: VideoStream, kind: StreamKind): YtStream? {
        if (!usable(s)) return null
        val item = s.itagItem
        return YtStream(
            itag = s.itag,
            kind = kind,
            container = s.format?.suffix ?: "mp4",
            videoCodec = VideoCodec.fromCodecString(s.codec),
            width = s.width, height = s.height, fps = s.fps.coerceAtLeast(0),
            bitrate = s.bitrate.coerceAtLeast(0),
            contentLength = item?.contentLength?.takeIf { it > 0 } ?: -1,
            url = s.content,
        )
    }

    private fun audio(s: AudioStream): YtStream? {
        if (!usable(s)) return null
        val format = s.format?.suffix.orEmpty()
        val item = s.itagItem
        return YtStream(
            itag = s.itag,
            kind = StreamKind.AUDIO_ONLY,
            container = format.ifEmpty { "m4a" },
            aac = format == "m4a" && (s.codec.orEmpty().startsWith("mp4a") || s.codec.isNullOrEmpty()),
            bitrate = if (s.bitrate > 0) s.bitrate else (s.averageBitrate.coerceAtLeast(0) * 1000),
            contentLength = item?.contentLength?.takeIf { it > 0 } ?: -1,
            url = s.content,
            originalTrack = s.audioTrackType == null || s.audioTrackType == AudioTrackType.ORIGINAL,
        )
    }

    private fun bestThumbnail(images: List<Image>?): String? = images.orEmpty()
        .filter { it.url.isNotBlank() }
        .maxByOrNull { if (it.height > 0) it.height.coerceAtMost(720) else 0 }?.url
}

/** NewPipe's HTTP layer: every request the extractor makes goes through the app's OkHttp client. */
class NewPipeDownloader(base: OkHttpClient) : Downloader() {
    // The download client is pinned to HTTP/1.1 so that segments use separate connections; the extractor wants the
    // ordinary client (HTTP/2, transparent gzip).
    private val client = base.newBuilder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()
        val method = request.httpMethod()
        val builder = okhttp3.Request.Builder().url(request.url())
        val requestBody = when {
            body != null -> body.toRequestBody(null)
            method.equals("POST", true) || method.equals("PUT", true) -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        builder.method(method, requestBody)
        var userAgent = false
        for ((name, values) in request.headers()) {
            if (name.equals("User-Agent", true)) userAgent = true
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        if (!userAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
