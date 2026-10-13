package com.olaph.xdm.debug

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.olaph.xdm.XdmApp
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.EngineRequest
import com.olaph.xdm.engine.FatalDownloadException
import com.olaph.xdm.engine.ProbeResult
import com.olaph.xdm.engine.ProgressStore
import com.olaph.xdm.engine.SegmentState
import com.olaph.xdm.remux.DashInput
import com.olaph.xdm.remux.Mp4DashMuxer
import com.olaph.xdm.remux.RemuxException
import com.olaph.xdm.youtube.FormatSelector
import com.olaph.xdm.youtube.StreamKind
import com.olaph.xdm.youtube.VideoCodec
import com.olaph.xdm.youtube.YoutubeErrors
import com.olaph.xdm.youtube.YoutubeException
import com.olaph.xdm.youtube.YoutubeHeaders
import com.olaph.xdm.youtube.YtErrorKind
import com.olaph.xdm.youtube.YtStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.roundToInt

/**
 * Debug-only end-to-end check of the YouTube path on a real (emulated) device, started by CI's smoke test:
 * `adb shell am start -n com.olaph.xdm/.debug.YoutubeTestActivity --es id <videoId>` (default: Big Buck Bunny,
 * Creative Commons). Resolves the video with the extractor, downloads the smallest H.264 video-only MP4 and the
 * smallest AAC audio in small chunks (several Range requests per file), merges them with media3, re-opens the result
 * with the platform's MediaExtractor, and logs exactly one line (tag `YTTEST`):
 *
 *  - `YTTEST PASS <w>x<h> <duration>s tracks=2`
 *  - `YTTEST BLOCKED <reason>`: YouTube refused this network (bot check, sign-in, region, no connection). Says nothing about the app.
 *  - `YTTEST FAIL <reason>`
 */
class YoutubeTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra("id")?.takeIf { it.isNotBlank() } ?: DEFAULT_ID
        val text = TextView(this).apply {
            textSize = 15f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 96, 32, 32)
            setTextColor(0xFF000000.toInt())
            text = "YouTube self-test\n$videoId\nstarting…"
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(0xFFFFFFFF.toInt())
                addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
        lifecycleScope.launch {
            val line = try {
                withTimeout(TIMEOUT_MS) { run(videoId) { text.text = "YouTube self-test\n$videoId\n$it" } }
            } catch (e: TimeoutCancellationException) {
                "YTTEST FAIL timeout after ${TIMEOUT_MS / 1000} s"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "YTTEST FAIL ${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)}"
            }
            Log.i("YTTEST", line)
            text.text = "YouTube self-test\n$videoId\n\n$line"
        }
    }

    private suspend fun run(videoId: String, progress: (String) -> Unit): String {
        val container = (application as XdmApp).container
        progress("resolving…")
        val info = try {
            container.youtube.resolve(videoId)
        } catch (e: YoutubeException) {
            return when (e.kind) {
                YtErrorKind.BOT_CHECK, YtErrorKind.AGE_RESTRICTED, YtErrorKind.GEO_BLOCKED, YtErrorKind.NETWORK ->
                    "YTTEST BLOCKED ${e.kind}: ${e.message}"
                else -> "YTTEST FAIL resolve ${e.kind}: ${e.cause?.javaClass?.simpleName ?: e.message}"
            }
        }
        val video = info.streams.filter { it.kind == StreamKind.VIDEO_ONLY && it.container == "mp4" && it.videoCodec == VideoCodec.H264 }
            .minWithOrNull(compareBy<YtStream>({ it.height }, { it.bitrate }))
        val audio = info.streams.filter { it.kind == StreamKind.AUDIO_ONLY && it.aac }.minByOrNull { it.bitrate }
        if (video == null || audio == null) {
            val v = info.streams.count { it.kind == StreamKind.VIDEO_ONLY }
            val a = info.streams.count { it.kind == StreamKind.AUDIO_ONLY }
            return "YTTEST FAIL no usable streams (video-only=$v audio-only=$a total=${info.streams.size} best-audio=${FormatSelector.bestAudio(info) != null})"
        }

        val dir = File(cacheDir, "yttest").apply { deleteRecursively(); mkdirs() }
        val engine = DownloadEngine(container.httpClient)
        val store = object : ProgressStore {
            override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) = Unit
            override suspend fun saveProgress(id: Long, segments: List<SegmentState>) = Unit
        }
        val files = HashMap<YtStream, File>()
        for ((n, stream) in listOf(video, audio).withIndex()) {
            val file = File(dir, if (n == 0) "video.part" else "audio.part").also { files[stream] = it }
            val label = if (n == 0) "video ${stream.height}p" else "audio"
            try {
                engine.download(
                    EngineRequest(
                        id = n.toLong(), url = stream.url, userAgent = "", file = file, connections = 4,
                        maxSegmentBytes = 1L * 1024 * 1024, // small chunks: several Range requests per file
                        knownLength = stream.contentLength,
                        refreshUrl = { _ ->
                            container.youtube.resolve(videoId).streams.firstOrNull { it.itag == stream.itag }?.url
                                ?: throw YoutubeException(YtErrorKind.FORMAT_GONE)
                        },
                        headersFor = { url -> YoutubeHeaders.forStream(url) },
                    ),
                    store,
                ) { p -> progress("$label ${p.downloaded / 1024} / ${p.total / 1024} KB") }
            } catch (e: FatalDownloadException) {
                return "YTTEST FAIL download $label: ${e.message}"
            } catch (e: YoutubeException) {
                return "YTTEST FAIL download $label: ${e.kind}"
            }
        }

        progress("merging…")
        val out = File(dir, "merged.mp4")
        try {
            Mp4DashMuxer().mux(DashInput(files.getValue(video), video.container), DashInput(files.getValue(audio), audio.container), out)
        } catch (e: RemuxException) {
            return "YTTEST FAIL merge: ${e.message}"
        }

        // Look at the result the way a player would.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.absolutePath)
            var width = 0
            var height = 0
            var durationUs = 0L
            var tracks = 0
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) tracks++
                if (mime.startsWith("video/")) {
                    width = f.getInteger(MediaFormat.KEY_WIDTH)
                    height = f.getInteger(MediaFormat.KEY_HEIGHT)
                }
                if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
            }
            val seconds = (durationUs / 1_000_000.0).roundToInt()
            if (tracks != 2) return "YTTEST FAIL merged file has $tracks tracks"
            if (info.durationSeconds > 0 && kotlin.math.abs(seconds - info.durationSeconds) > 3) {
                return "YTTEST FAIL duration ${seconds}s, expected about ${info.durationSeconds}s"
            }
            return "YTTEST PASS ${width}x$height ${seconds}s tracks=$tracks"
        } finally {
            extractor.release()
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val DEFAULT_ID = "aqz-KE-bpKQ"
        const val TIMEOUT_MS = 170_000L
    }
}
