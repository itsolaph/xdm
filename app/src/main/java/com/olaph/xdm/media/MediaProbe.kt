package com.olaph.xdm.media

import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.engine.HeaderCodec
import com.olaph.xdm.engine.M3u8
import com.olaph.xdm.engine.M3u8Exception
import com.olaph.xdm.engine.MasterPlaylist
import com.olaph.xdm.engine.MediaPlaylist
import com.olaph.xdm.engine.MimeTypes
import com.olaph.xdm.engine.buildRequest
import com.olaph.xdm.engine.checkStatus
import com.olaph.xdm.engine.executeAndUse
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Verifies a [MediaCandidate] with a light request: HEAD (or `Range: bytes=0-0`) for files, a playlist
 * GET for HLS/DASH. Uses the tab's Cookie / User-Agent / Referer so protected media answers like it does
 * for the page. Transient failures (timeouts, 5xx) are thrown as [IOException]; "not media" is [MediaAnalysis.Rejected].
 */
class MediaProbe(client: OkHttpClient) {
    private val http: OkHttpClient = client.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(
        url: String,
        kind: MediaClassifier.Kind,
        userAgent: String,
        headers: Map<String, String>,
    ): MediaAnalysis = try {
        when (kind) {
            MediaClassifier.Kind.HLS -> analyzeHls(url, userAgent, headers)
            MediaClassifier.Kind.DASH -> analyzeDash(url, userAgent, headers)
            MediaClassifier.Kind.SEGMENT, MediaClassifier.Kind.IGNORE -> MediaAnalysis.Rejected
            else -> analyzeFile(url, kind, userAgent, headers)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: com.olaph.xdm.engine.FatalDownloadException) {
        MediaAnalysis.Rejected // 4xx and friends: nothing XDM could fetch either
    }

    // ---- plain files ----

    private class Head(val contentType: String?, val length: Long)

    private suspend fun head(url: String, ua: String, headers: Map<String, String>): Head? {
        try {
            val r = http.newCall(buildRequest(url, ua, head = true, headers = headers)).executeAndUse { r ->
                if (!r.isSuccessful) null
                else Head(r.header("Content-Type")?.let { MimeTypes.normalize(it) }, r.header("Content-Length")?.toLongOrNull() ?: -1)
            }
            if (r != null && r.contentType != null && r.length >= 0) return r
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // HEAD is rejected or broken on some servers; the ranged GET below decides.
        }
        return null
    }

    private suspend fun analyzeFile(url: String, kind: MediaClassifier.Kind, ua: String, headers: Map<String, String>): MediaAnalysis {
        val h = head(url, ua, headers) ?: http.newCall(buildRequest(url, ua, range = "bytes=0-0", headers = headers)).executeAndUse { r ->
            checkStatus(r.code)
            val total = if (r.code == 206) r.header("Content-Range")?.substringAfter('/', "")?.trim()?.toLongOrNull() ?: -1
            else r.header("Content-Length")?.toLongOrNull() ?: -1
            Head(r.header("Content-Type")?.let { MimeTypes.normalize(it) }, total)
        }
        val mime = h.contentType.orEmpty()
        val urlExt = MediaClassifier.extensionOf(url.substringBefore('?').substringBefore('#'))

        // The response may reveal a manifest behind a bare URL (`/hls/abc`).
        if (mime in HLS_TYPES) return analyzeHls(url, ua, headers)
        if (mime == "application/dash+xml") return analyzeDash(url, ua, headers)

        val isMedia = mime.startsWith("video/") || mime.startsWith("audio/") ||
            (mime in GENERIC_TYPES && kind != MediaClassifier.Kind.UNKNOWN_MEDIA) ||
            ((mime in GENERIC_TYPES || mime.isEmpty()) && urlExt in MEDIA_EXT)
        if (!isMedia || mime == "video/mp2t" || mime == "video/mp4-fragment") return MediaAnalysis.Rejected
        if (h.length in 0 until MIN_BYTES) return MediaAnalysis.Rejected // tracking stubs, empty placeholders

        val audio = mime.startsWith("audio/") || kind == MediaClassifier.Kind.AUDIO_FILE
        val ext = urlExt.takeIf { it in MEDIA_EXT } ?: MimeTypes.extensionFor(mime) ?: if (audio) "mp3" else "mp4"
        val typeLabel = if (audio) "Audio" else typeLabelFor(ext)
        return MediaAnalysis.Found(
            MediaKind.FILE, typeLabel,
            listOf(
                MediaOption(
                    url = url,
                    label = typeLabel,
                    sizeBytes = h.length,
                    extension = ext,
                    mimeType = mime.takeIf { it.contains('/') && it !in GENERIC_TYPES },
                    audioOnly = audio,
                ),
            ),
        )
    }

    // ---- HLS ----

    private suspend fun analyzeHls(url: String, ua: String, headers: Map<String, String>): MediaAnalysis {
        val text = fetchText(url, ua, headers) ?: return MediaAnalysis.Rejected
        if (!text.trimStart(M3u8.BOM, ' ', '\n', '\r', '\t').startsWith("#EXTM3U")) return MediaAnalysis.Rejected
        val playlist = try {
            M3u8.parse(text, url)
        } catch (_: M3u8Exception) {
            return MediaAnalysis.Rejected
        }
        return when (playlist) {
            is MediaPlaylist -> {
                val option = HlsOptions.forMedia(playlist) ?: return MediaAnalysis.Rejected
                MediaAnalysis.Found(MediaKind.HLS, "HLS", listOf(option))
            }
            is MasterPlaylist -> {
                if (playlist.variants.isEmpty()) return MediaAnalysis.Rejected
                // One variant's media playlist gives duration, live/VOD and the container for the whole master.
                val best = playlist.bestVariant()
                val probed = best?.let {
                    try {
                        fetchText(it.uri, ua, HeaderCodec.scopeToHost(headers, url, it.uri))?.let { t -> M3u8.parse(t, it.uri) as? MediaPlaylist }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null // sizes just stay unknown
                    }
                }
                MediaAnalysis.Found(MediaKind.HLS, "HLS", HlsOptions.forMaster(playlist, probed))
            }
        }
    }

    // ---- DASH ----

    private suspend fun analyzeDash(url: String, ua: String, headers: Map<String, String>): MediaAnalysis {
        val text = fetchText(url, ua, headers) ?: return MediaAnalysis.Rejected
        if (!text.contains("<MPD")) return MediaAnalysis.Rejected
        val reason = when {
            text.contains("ContentProtection", ignoreCase = true) && text.contains("urn:uuid", ignoreCase = true) ->
                "DRM-protected stream"
            text.contains("type=\"dynamic\"") -> HlsOptions.LIVE
            else -> "DASH not supported yet"
        }
        return MediaAnalysis.Found(
            MediaKind.DASH, "DASH",
            listOf(MediaOption(url = url, label = "DASH", extension = "mp4", unsupportedReason = reason)),
        )
    }

    private suspend fun fetchText(url: String, ua: String, headers: Map<String, String>): String? =
        http.newCall(buildRequest(url, ua, headers = headers)).executeAndUse { r ->
            checkStatus(r.code)
            val body = r.body ?: return@executeAndUse null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            body.byteStream().use { input ->
                while (out.size() <= MAX_TEXT_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            if (out.size() > MAX_TEXT_BYTES) null else out.toString(Charsets.UTF_8.name())
        }

    private fun typeLabelFor(ext: String) = when (ext) {
        "mp4", "m4v" -> "MP4"
        "webm" -> "WEBM"
        "mkv" -> "MKV"
        "mov" -> "MOV"
        "3gp" -> "3GP"
        "flv" -> "FLV"
        else -> FilenameUtils.sanitize(ext).uppercase()
    }

    private companion object {
        val HLS_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl", "application/mpegurl")
        val GENERIC_TYPES = setOf("application/octet-stream", "binary/octet-stream", "application/mp4", "application/ogg", "application/x-download")
        val MEDIA_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv", "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
        const val MIN_BYTES = 8L * 1024
        const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    }
}
