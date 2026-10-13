package com.olaph.xdm.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class M3u8Test {

    private val base = "https://cdn.example.com/video/master.m3u8?token=abc"

    private fun master(text: String) = M3u8.parse(text.trimIndent(), base) as MasterPlaylist
    private fun media(text: String, url: String = "https://cdn.example.com/video/720/index.m3u8") =
        M3u8.parse(text.trimIndent(), url) as MediaPlaylist

    // ---- master ----

    @Test fun masterWithSeveralVariantsAndRelativeUris() {
        val m = master(
            """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-STREAM-INF:BANDWIDTH=800000,AVERAGE-BANDWIDTH=700000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",FRAME-RATE=29.970
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
            /abs/720/index.m3u8?sig=1,2
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            https://other.example.com/1080.m3u8
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="iframes.m3u8"
            """,
        )
        assertEquals(3, m.variants.size)
        val v360 = m.variants[0]
        assertEquals("https://cdn.example.com/video/360/index.m3u8", v360.uri)
        assertEquals(800_000L, v360.bandwidth)
        assertEquals(700_000L, v360.averageBandwidth)
        assertEquals(700_000L, v360.estimateBandwidth)
        assertEquals(640, v360.width)
        assertEquals(360, v360.height)
        assertEquals("avc1.4d401e,mp4a.40.2", v360.codecs)
        assertEquals(29.97, v360.frameRate!!, 0.001)
        assertEquals("https://cdn.example.com/abs/720/index.m3u8?sig=1,2", m.variants[1].uri)
        assertEquals("https://other.example.com/1080.m3u8", m.variants[2].uri)
        // i-frame playlists are not variants
        assertTrue(m.variants.none { it.uri.contains("iframes") })
    }

    @Test fun masterAudioRenditionsDecideWhichVariantsHaveAudio() {
        val m = master(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subs/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS="avc1.640028",AUDIO="aud"
            video-only-1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
            muxed-720.m3u8
            """,
        )
        assertEquals(2, m.renditions.size)
        assertEquals("https://cdn.example.com/video/audio/en.m3u8", m.renditions[0].uri)
        assertTrue(m.renditions[0].isDefault)
        val (v1080, v720) = m.variants
        assertFalse("separate audio group → video only", m.hasMuxedAudio(v1080))
        assertTrue(m.hasMuxedAudio(v720))
        // The best variant to download is the one that carries its own audio.
        assertEquals(v720.uri, m.bestVariant()!!.uri)
    }

    @Test fun audioOnlyVariantIsRecognised() {
        val m = master(
            """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            audio.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
            v.m3u8
            """,
        )
        assertTrue(m.isAudioOnly(m.variants[0]))
        assertFalse(m.isAudioOnly(m.variants[1]))
        assertEquals(m.variants[1].uri, m.bestVariant()!!.uri)
    }

    @Test fun attributeListsHandleQuotedCommasAndCase() {
        val a = M3u8.parseAttributes("""BANDWIDTH=100,CODECS="a,b",name=x y,URI="u?a=1,2",EMPTY=""")
        assertEquals("100", a["BANDWIDTH"])
        assertEquals("a,b", a["CODECS"])
        assertEquals("x y", a["NAME"])
        assertEquals("u?a=1,2", a["URI"])
        assertEquals("", a["EMPTY"])
    }

    // ---- media ----

    @Test fun mediaPlaylistBasics() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:9.009,first
            seg0.ts
            #EXTINF:8,
            sub/seg1.ts?x=1
            #EXTINF:3.5,
            https://elsewhere.test/seg2.ts
            #EXT-X-ENDLIST
            """,
        )
        assertEquals(10.0, p.targetDuration, 0.0)
        assertEquals(7L, p.mediaSequence)
        assertTrue(p.endList)
        assertEquals("VOD", p.playlistType)
        assertEquals(3, p.segments.size)
        assertEquals("https://cdn.example.com/video/720/seg0.ts", p.segments[0].uri)
        assertEquals("https://cdn.example.com/video/720/sub/seg1.ts?x=1", p.segments[1].uri)
        assertEquals("https://elsewhere.test/seg2.ts", p.segments[2].uri)
        assertEquals(listOf(7L, 8L, 9L), p.segments.map { it.sequence })
        assertEquals(20.509, p.duration, 0.0001)
        assertEquals("ts", p.outputExtension)
        assertFalse(p.hasMap)
        assertNull(p.unsupportedEncryption())
    }

    @Test fun livePlaylistHasNoEndList() {
        val p = media("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts")
        assertFalse(p.endList)
    }

    @Test fun byteRangesContinueFromThePreviousRange() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4,
            #EXT-X-BYTERANGE:1000@0
            big.ts
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            big.ts
            #EXTINF:4,
            #EXT-X-BYTERANGE:700
            big.ts
            #EXTINF:4,
            #EXT-X-BYTERANGE:100@5000
            big.ts
            #EXTINF:4,
            #EXT-X-BYTERANGE:50
            other.ts
            #EXT-X-ENDLIST
            """,
        )
        assertEquals(ByteRange(1000, 0), p.segments[0].byteRange)
        assertEquals(ByteRange(500, 1000), p.segments[1].byteRange)
        assertEquals(ByteRange(700, 1500), p.segments[2].byteRange)
        assertEquals(ByteRange(100, 5000), p.segments[3].byteRange)
        assertEquals("other resource starts at 0", ByteRange(50, 0), p.segments[4].byteRange)
        assertEquals("bytes=1000-1499", p.segments[1].byteRange!!.header())
    }

    @Test fun mapGivesInitSegmentToFollowingSegmentsAndOutputIsMp4() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXTINF:4,
            s1.m4s
            #EXTINF:4,
            s2.m4s
            #EXT-X-DISCONTINUITY
            #EXT-X-MAP:URI="init2.mp4"
            #EXTINF:4,
            s3.m4s
            #EXT-X-ENDLIST
            """,
        )
        assertTrue(p.hasMap)
        assertEquals("mp4", p.outputExtension)
        assertEquals("https://cdn.example.com/video/720/init.mp4", p.segments[0].map!!.uri)
        assertEquals(ByteRange(720, 0), p.segments[0].map!!.byteRange)
        assertEquals(p.segments[0].map!!.identity, p.segments[1].map!!.identity)
        assertEquals("https://cdn.example.com/video/720/init2.mp4", p.segments[2].map!!.uri)
        assertTrue(p.segments[2].discontinuity)
        assertFalse(p.segments[1].discontinuity)
    }

    @Test fun keyChangesApplyFromTheTagOnwardsAndNoneResets() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-KEY:METHOD=AES-128,URI="k1.bin",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:4,
            a.ts
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.test/k2.bin"
            #EXTINF:4,
            b.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            c.ts
            #EXT-X-ENDLIST
            """,
        )
        val k1 = p.segments[0].key!!
        assertTrue(k1.isAes128)
        assertEquals("https://cdn.example.com/video/720/k1.bin", k1.uri)
        assertArrayEquals(ByteArray(16) { it.toByte() }, k1.iv)
        val k2 = p.segments[1].key!!
        assertEquals("https://keys.test/k2.bin", k2.uri)
        assertNull("no IV attribute → media sequence number is used", k2.iv)
        assertNull(p.segments[2].key)
        assertNull(p.unsupportedEncryption())
    }

    @Test fun shortIvIsLeftPadded() {
        assertArrayEquals(ByteArray(16).also { it[15] = 1 }, M3u8.parseIv("0x1"))
        assertNull(M3u8.parseIv("0xZZ"))
        assertNull(M3u8.parseIv("0x" + "1".repeat(33)))
    }

    @Test fun sampleAesAndDrmKeyFormatsAreReportedAsUnsupported() {
        val sample = media("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST")
        assertNotNull(sample.unsupportedEncryption())
        val drm = media(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST",
        )
        assertEquals("DRM-protected stream", drm.unsupportedEncryption())
        val widevine = media(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\",KEYFORMAT=\"urn:uuid:edef8ba9\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST",
        )
        assertEquals("DRM-protected stream", widevine.unsupportedEncryption())
    }

    @Test fun discontinuityAndUnknownTagsAreIgnoredSafely() {
        val p = media(
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-DISCONTINUITY-SEQUENCE:3
            #EXT-X-PROGRAM-DATE-TIME:2024-01-01T00:00:00Z
            #EXT-X-CUSTOM-THING:whatever
            #EXTINF:4,
            a.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-DATERANGE:ID="ad",START-DATE="2024-01-01T00:00:00Z"
            #EXTINF:4,
            b.ts
            #EXTINF:garbage,
            c.ts
            #EXT-X-ENDLIST
            """,
        )
        assertEquals(3, p.segments.size)
        assertFalse(p.segments[0].discontinuity)
        assertTrue(p.segments[1].discontinuity)
        assertEquals(0.0, p.segments[2].duration, 0.0)
    }

    @Test fun packedAudioPlaylistsGetAudioExtensions() {
        assertEquals("aac", media("#EXTM3U\n#EXTINF:4,\na.aac?x=1\n#EXT-X-ENDLIST").outputExtension)
        assertEquals("mp3", media("#EXTM3U\n#EXTINF:4,\na.mp3\n#EXT-X-ENDLIST").outputExtension)
    }

    @Test fun nonPlaylistIsRejected() {
        try {
            M3u8.parse("<html>not a playlist</html>", base)
            fail("expected M3u8Exception")
        } catch (_: M3u8Exception) {
        }
    }

    @Test fun bomAndWindowsLineEndingsAreAccepted() {
        val p = M3u8.parse(M3u8.BOM + "#EXTM3U\r\n#EXT-X-TARGETDURATION:4\r\n#EXTINF:4,\r\na.ts\r\n#EXT-X-ENDLIST\r\n", base) as MediaPlaylist
        assertEquals(1, p.segments.size)
        assertTrue(p.endList)
    }
}
