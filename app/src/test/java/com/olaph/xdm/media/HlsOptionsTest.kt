package com.olaph.xdm.media

import com.olaph.xdm.engine.M3u8
import com.olaph.xdm.engine.MasterPlaylist
import com.olaph.xdm.engine.MediaPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsOptionsTest {

    private val base = "https://cdn.example.com/v/master.m3u8"

    private fun master(text: String) = M3u8.parse(text.trimIndent(), base) as MasterPlaylist
    private fun media(text: String, url: String = "https://cdn.example.com/v/720.m3u8") =
        M3u8.parse(text.trimIndent(), url) as MediaPlaylist

    /** Two segments, 600 s in total. */
    private val vod = media("#EXTM3U\n#EXT-X-TARGETDURATION:300\n#EXTINF:300,\na.ts\n#EXTINF:300,\nb.ts\n#EXT-X-ENDLIST")

    private val sampleMaster = master(
        """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Deutsch",LANGUAGE="de",URI="audio/de.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
        360.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,AVERAGE-BANDWIDTH=2400000,RESOLUTION=1280x720,FRAME-RATE=59.94,CODECS="avc1.64001f,mp4a.40.2"
        720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120",AUDIO="aud"
        1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
        audio-only.m3u8
        """,
    )

    @Test fun masterOptionsAreSortedAndLabelled() {
        val opts = HlsOptions.forMaster(sampleMaster, vod)
        // 1080 (video only), 720, 360, then the audio-only variant, then the two audio renditions
        assertEquals(listOf("1080p", "720p60", "360p", "Audio", "Audio · English", "Audio · Deutsch"), opts.map { it.label })

        val p1080 = opts[0]
        assertTrue(p1080.videoOnly)
        assertTrue("detail mentions video only", p1080.detail!!.contains("video only"))
        assertTrue(p1080.detail!!.contains("H.265"))
        assertTrue(p1080.detail!!.contains("6.0 Mbps"))

        val p720 = opts[1]
        assertFalse(p720.videoOnly)
        assertTrue(p720.detail!!.contains("2.4 Mbps")) // average bandwidth preferred
        assertTrue(p720.detail!!.contains("H.264"))

        assertTrue(opts[3].audioOnly)
        assertTrue(opts[4].audioOnly)
        assertTrue(opts.all { it.hls })
        assertEquals("https://cdn.example.com/v/audio/en.m3u8", opts[4].url)
    }

    @Test fun sizeIsEstimatedFromBandwidthTimesDuration() {
        val opts = HlsOptions.forMaster(sampleMaster, vod) // 600 s
        val p720 = opts.first { it.label.startsWith("720") }
        assertTrue(p720.sizeEstimated)
        assertEquals(2_400_000L * 600 / 8, p720.sizeBytes)
        assertEquals(600.0, p720.durationSec, 0.001)
    }

    @Test fun withoutAProbedMediaPlaylistSizesStayUnknown() {
        val opts = HlsOptions.forMaster(sampleMaster, null)
        assertTrue(opts.all { it.sizeBytes < 0 && !it.sizeEstimated })
        assertTrue(opts.all { it.supported })
    }

    @Test fun liveMasterIsListedButNotSelectable() {
        val live = media("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts")
        val opts = HlsOptions.forMaster(sampleMaster, live)
        assertTrue(opts.isNotEmpty())
        assertTrue(opts.all { !it.supported && it.unsupportedReason == "Live stream — not supported" })
    }

    @Test fun containerFollowsTheProbedPlaylist() {
        val fmp4 = media("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MAP:URI=\"i.mp4\"\n#EXTINF:4,\na.m4s\n#EXT-X-ENDLIST")
        // (separate audio renditions get their real extension from the engine when they are downloaded)
        fun variants(probed: com.olaph.xdm.engine.MediaPlaylist) =
            HlsOptions.forMaster(sampleMaster, probed).filterNot { it.label.startsWith("Audio ·") }
        assertTrue(variants(fmp4).all { it.extension == "mp4" })
        assertTrue(variants(vod).all { it.extension == "ts" })
    }

    @Test fun singleMediaPlaylist() {
        val o = HlsOptions.forMedia(vod)!!
        assertEquals("Stream", o.label)
        assertEquals(600.0, o.durationSec, 0.001)
        assertTrue(o.detail!!.contains("10:00"))
        assertTrue(o.detail!!.contains("2 segments"))
        assertEquals("ts", o.extension)
        assertTrue(o.supported)
        assertTrue(o.hls)
    }

    @Test fun liveAndEncryptedMediaPlaylistsExplainWhy() {
        val live = media("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts")
        assertEquals("Live stream — not supported", HlsOptions.forMedia(live)!!.unsupportedReason)
        val drm = media("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
        assertEquals("DRM-protected stream", HlsOptions.forMedia(drm)!!.unsupportedReason)
        val aes = media("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST")
        val o = HlsOptions.forMedia(aes)!!
        assertNull(o.unsupportedReason)
        assertTrue(o.detail!!.contains("AES-128"))
    }

    @Test fun subtitlePlaylistsAreNotMedia() {
        val subs = media("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\ncaptions0.vtt\n#EXT-X-ENDLIST")
        assertNull(HlsOptions.forMedia(subs))
    }

    @Test fun helpers() {
        assertEquals("1.5 Mbps", HlsOptions.bitrate(1_500_000))
        assertEquals("800 kbps", HlsOptions.bitrate(800_000))
        assertNull(HlsOptions.bitrate(0))
        assertEquals("1:01:05", HlsOptions.duration(3665.0))
        assertEquals("2:05", HlsOptions.duration(125.0))
        assertEquals("AV1", HlsOptions.codecName("av01.0.05M.08,mp4a.40.2"))
        assertEquals("VP9", HlsOptions.codecName("vp09.00.10.08"))
        assertNull(HlsOptions.codecName(null))
        assertNotNull(HlsOptions.codecName("mp4a.40.2"))
    }
}
