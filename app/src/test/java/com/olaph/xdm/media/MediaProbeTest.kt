package com.olaph.xdm.media

import com.olaph.xdm.media.MediaClassifier.Kind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class MediaProbeTest {
    private lateinit var server: MockWebServer
    private val routes = HashMap<String, (RecordedRequest) -> MockResponse>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private val probe = MediaProbe(OkHttpClient())

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    val h = routes[request.path!!.substringBefore('?')] ?: return MockResponse().setResponseCode(404)
                    return h(request)
                }
            }
            start()
        }
    }

    @After fun tearDown() = server.shutdown()

    private fun url(path: String) = server.url(path).toString()

    /** Answers HEAD and `Range: bytes=0-0` like a normal file server. */
    private fun fileRoute(path: String, type: String?, length: Long, allowHead: Boolean = true) {
        routes[path] = { r ->
            when {
                r.method == "HEAD" && !allowHead -> MockResponse().setResponseCode(405)
                r.method == "HEAD" -> MockResponse().apply {
                    type?.let { setHeader("Content-Type", it) }
                    setHeader("Content-Length", length)
                }
                r.getHeader("Range") != null -> MockResponse().setResponseCode(206).apply {
                    type?.let { setHeader("Content-Type", it) }
                    setHeader("Content-Range", "bytes 0-0/$length")
                    setBody("x")
                }
                else -> MockResponse().setBody("x")
            }
        }
    }

    private suspend fun analyze(path: String, kind: Kind, headers: Map<String, String> = emptyMap()) =
        probe.analyze(url(path), kind, "ua", headers)

    private fun MediaAnalysis.found() = this as MediaAnalysis.Found

    // ---- files ----

    @Test fun videoFileWithSize() = runBlocking {
        fileRoute("/a.mp4", "video/mp4", 12_345_678)
        val f = analyze("/a.mp4", Kind.VIDEO_FILE).found()
        assertEquals(MediaKind.FILE, f.kind)
        assertEquals("MP4", f.typeLabel)
        val o = f.options.single()
        assertEquals(12_345_678L, o.sizeBytes)
        assertFalse(o.sizeEstimated)
        assertEquals("mp4", o.extension)
        assertEquals("video/mp4", o.mimeType)
        assertTrue(o.supported)
    }

    @Test fun webmAndAudioLabels() = runBlocking {
        fileRoute("/a.webm", "video/webm", 900_000)
        assertEquals("WEBM", analyze("/a.webm", Kind.VIDEO_FILE).found().typeLabel)
        fileRoute("/s.mp3", "audio/mpeg", 4_000_000)
        val audio = analyze("/s.mp3", Kind.AUDIO_FILE).found()
        assertEquals("Audio", audio.typeLabel)
        assertTrue(audio.options.single().audioOnly)
        assertEquals("mp3", audio.options.single().extension)
    }

    @Test fun nonMediaContentTypesAreDropped() = runBlocking {
        fileRoute("/page.mp4", "text/html; charset=utf-8", 50_000) // looks like a video, is an HTML error page
        assertEquals(MediaAnalysis.Rejected, analyze("/page.mp4", Kind.VIDEO_FILE))
        fileRoute("/img.mp4", "image/png", 50_000)
        assertEquals(MediaAnalysis.Rejected, analyze("/img.mp4", Kind.VIDEO_FILE))
        fileRoute("/seg.mp4", "video/mp2t", 500_000) // a transport-stream chunk
        assertEquals(MediaAnalysis.Rejected, analyze("/seg.mp4", Kind.VIDEO_FILE))
    }

    @Test fun tinyFilesAreDropped() = runBlocking {
        fileRoute("/stub.mp4", "video/mp4", 300)
        assertEquals(MediaAnalysis.Rejected, analyze("/stub.mp4", Kind.VIDEO_FILE))
    }

    @Test fun octetStreamIsAcceptedOnlyWhenTheUrlLooksLikeMedia() = runBlocking {
        fileRoute("/blob.mp4", "application/octet-stream", 2_000_000)
        assertEquals("mp4", analyze("/blob.mp4", Kind.VIDEO_FILE).found().options.single().extension)
        fileRoute("/videoplayback", "application/octet-stream", 2_000_000)
        assertEquals(MediaAnalysis.Rejected, analyze("/videoplayback", Kind.UNKNOWN_MEDIA))
        fileRoute("/noheader.mp4", null, 2_000_000)
        assertTrue(analyze("/noheader.mp4", Kind.VIDEO_FILE) is MediaAnalysis.Found)
    }

    @Test fun patternUrlsAreDecidedByTheContentType() = runBlocking {
        fileRoute("/videoplayback", "video/mp4", 3_000_000)
        val f = analyze("/videoplayback", Kind.UNKNOWN_MEDIA).found()
        assertEquals("MP4", f.typeLabel)
        assertEquals("mp4", f.options.single().extension)
    }

    @Test fun serversThatRefuseHeadAreProbedWithARangeRequest() = runBlocking {
        fileRoute("/nohead.mp4", "video/mp4", 7_000_000, allowHead = false)
        val f = analyze("/nohead.mp4", Kind.VIDEO_FILE).found()
        assertEquals(7_000_000L, f.options.single().sizeBytes)
        assertTrue(seen.any { it.method == "GET" && it.getHeader("Range") == "bytes=0-0" })
    }

    @Test fun cookieRefererAndUserAgentAreSent() = runBlocking {
        routes["/private.mp4"] = { r ->
            if (r.getHeader("Cookie") != "sid=9" || r.getHeader("Referer") != "https://page.test/") MockResponse().setResponseCode(403)
            else MockResponse().setHeader("Content-Type", "video/mp4").setHeader("Content-Length", 1_000_000)
        }
        assertEquals(MediaAnalysis.Rejected, analyze("/private.mp4", Kind.VIDEO_FILE))
        val ok = analyze("/private.mp4", Kind.VIDEO_FILE, mapOf("Cookie" to "sid=9", "Referer" to "https://page.test/"))
        assertTrue(ok is MediaAnalysis.Found)
        assertTrue(seen.all { it.getHeader("User-Agent") == "ua" })
    }

    @Test fun serverErrorsAreTransientNotRejections() = runBlocking {
        routes["/boom.mp4"] = { MockResponse().setResponseCode(503) }
        try {
            analyze("/boom.mp4", Kind.VIDEO_FILE)
            fail("expected an IOException so the URL can be retried later")
        } catch (_: IOException) {
        }
    }

    // ---- HLS ----

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
        360.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
        720.m3u8
    """.trimIndent()

    private fun playlist(text: String) = MockResponse().setBody(text).setHeader("Content-Type", "application/vnd.apple.mpegurl")

    @Test fun hlsMasterListsEveryQualityWithAnEstimatedSize() = runBlocking {
        routes["/hls/master.m3u8"] = { playlist(master) }
        routes["/hls/720.m3u8"] = { playlist("#EXTM3U\n#EXT-X-TARGETDURATION:100\n#EXTINF:100,\na.ts\n#EXTINF:100,\nb.ts\n#EXT-X-ENDLIST") }
        val f = analyze("/hls/master.m3u8", Kind.HLS).found()
        assertEquals(MediaKind.HLS, f.kind)
        assertEquals("HLS", f.typeLabel)
        assertEquals(listOf("720p", "360p"), f.options.map { it.label })
        assertEquals(2_400_000L * 200 / 8, f.options[0].sizeBytes)
        assertTrue(f.options.all { it.sizeEstimated })
        assertEquals(listOf(url("/hls/720.m3u8"), url("/hls/360.m3u8")), f.options.map { it.url })
        assertEquals(f.options.map { it.url }, f.childUrls)
    }

    @Test fun hlsMasterStillListedWhenTheVariantPlaylistFails() = runBlocking {
        routes["/hls/master.m3u8"] = { playlist(master) }
        // 720.m3u8 is a 404
        val f = analyze("/hls/master.m3u8", Kind.HLS).found()
        assertEquals(2, f.options.size)
        assertTrue(f.options.all { it.sizeBytes < 0 })
    }

    @Test fun hlsLiveStreamIsShownAsUnsupported() = runBlocking {
        routes["/live.m3u8"] = { playlist("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:900\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts") }
        val o = analyze("/live.m3u8", Kind.HLS).found().options.single()
        assertFalse(o.supported)
        assertEquals("Live stream — not supported", o.unsupportedReason)
    }

    @Test fun bareHlsUrlsAreRecognisedByContentType() = runBlocking {
        val body = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST"
        routes["/hls/abc123/index"] = { r ->
            if (r.method == "HEAD") MockResponse().setHeader("Content-Type", "application/x-mpegURL").setHeader("Content-Length", body.length)
            else playlist(body)
        }
        val f = analyze("/hls/abc123/index", Kind.UNKNOWN_MEDIA).found()
        assertEquals(MediaKind.HLS, f.kind)
        assertEquals("Stream", f.options.single().label)
    }

    @Test fun m3u8UrlThatServesHtmlIsRejected() = runBlocking {
        routes["/fake.m3u8"] = { MockResponse().setBody("<html><body>Please log in</body></html>").setHeader("Content-Type", "text/html") }
        assertEquals(MediaAnalysis.Rejected, analyze("/fake.m3u8", Kind.HLS))
    }

    @Test fun subtitlePlaylistsAreRejected() = runBlocking {
        routes["/subs.m3u8"] = { playlist("#EXTM3U\n#EXT-X-TARGETDURATION:60\n#EXTINF:60,\nen0.vtt\n#EXT-X-ENDLIST") }
        assertEquals(MediaAnalysis.Rejected, analyze("/subs.m3u8", Kind.HLS))
    }

    // ---- DASH ----

    @Test fun dashIsDetectedButMarkedUnsupported() = runBlocking {
        routes["/m.mpd"] = { MockResponse().setBody("""<?xml version="1.0"?><MPD type="static" mediaPresentationDuration="PT10M"><Period/></MPD>""") }
        val f = analyze("/m.mpd", Kind.DASH).found()
        assertEquals(MediaKind.DASH, f.kind)
        assertEquals("DASH", f.typeLabel)
        assertEquals("DASH not supported yet", f.options.single().unsupportedReason)
        assertFalse(f.options.single().supported)
    }

    @Test fun dashWithContentProtectionIsReportedAsDrm() = runBlocking {
        routes["/drm.mpd"] = {
            MockResponse().setBody(
                """<MPD type="static"><Period><AdaptationSet><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/></AdaptationSet></Period></MPD>""",
            )
        }
        assertEquals("DRM-protected stream", analyze("/drm.mpd", Kind.DASH).found().options.single().unsupportedReason)
    }

    @Test fun segmentsAndIgnoredKindsAreNeverProbed() = runBlocking {
        assertEquals(MediaAnalysis.Rejected, analyze("/x.ts", Kind.SEGMENT))
        assertEquals(MediaAnalysis.Rejected, analyze("/x.js", Kind.IGNORE))
        assertTrue(seen.isEmpty())
    }
}
