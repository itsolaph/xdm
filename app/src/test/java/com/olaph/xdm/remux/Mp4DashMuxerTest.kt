package com.olaph.xdm.remux

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The real [Mp4DashMuxer] (media3 FragmentedMp4Extractor / MatroskaExtractor + Mp4Muxer on Robolectric's framework
 * classes) over ffmpeg-made DASH-style fixtures (scripts/gen-youtube-fixtures.sh), checked with the independent
 * [Mp4File] parser. Packet counts are what `ffprobe -count_packets` reports for the fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class Mp4DashMuxerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val f = File(tmp.root, name)
        checkNotNull(javaClass.getResourceAsStream("/youtube/$name")) { "missing fixture $name" }.use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun mux(video: String?, audio: String?, onProgress: (Long, Long) -> Unit = { _, _ -> }): Pair<RemuxResult, Mp4File> {
        val out = File(tmp.root, "out-${System.nanoTime()}.mp4")
        val v = video?.let { DashInput(fixture(it), if (it.endsWith(".webm")) "webm" else "mp4") }
        val a = audio?.let { DashInput(fixture(it), if (it.endsWith(".webm")) "webm" else "m4a") }
        val r = runBlocking { Mp4DashMuxer().mux(v, a, out, onProgress) }
        // Manual check on a machine with ffprobe: create app/build/youtube-out and the outputs are kept there.
        File("build/youtube-out").takeIf { it.isDirectory }?.let { out.copyTo(File(it, "${video ?: "none"}+${audio ?: "none"}.${r.extension}"), overwrite = true) }
        return r to Mp4File(out.readBytes())
    }

    private fun Mp4File.video() = traks.single { it.handler == "vide" }
    private fun Mp4File.audio() = traks.single { it.handler == "soun" }

    private fun assertWellFormed(mp4: Mp4File) {
        assertEquals("ftyp", mp4.topLevel.first().type)
        assertTrue("moov present", mp4.topLevel.any { it.type == "moov" })
        val mdat = mp4.topLevel.first { it.type == "mdat" }
        for (t in mp4.traks) {
            assertEquals("stts covers every sample", t.sampleCount, t.sttsDeltas.size)
            val offsets = t.sampleOffsets()
            for (i in offsets.indices) assertTrue("sample $i inside mdat", offsets[i] >= mdat.payload && offsets[i] + t.sizes[i] <= mdat.end)
            val dts = t.decodeTimes()
            for (i in 1 until dts.size) assertTrue("DTS is monotonic", dts[i] >= dts[i - 1])
            if (t.cttsOffsets != null) {
                val pts = LongArray(dts.size) { dts[it] + t.cttsOffsets[it] }
                assertTrue("no negative presentation time", pts.min() >= 0)
            }
        }
    }

    @Test fun h264VideoAndAacAudioBecomeOneMp4WithBothTracks() {
        val (r, mp4) = mux("video-avc.mp4", "audio-aac.m4a")
        assertEquals("mp4", r.extension)
        assertEquals("video/mp4", r.mimeType)
        assertEquals(75, r.videoOut); assertEquals(75, r.videoIn)
        assertEquals(131, r.audioOut); assertEquals(131, r.audioIn)
        assertEquals(3.0, r.durationUs / 1e6, 0.2)

        assertWellFormed(mp4)
        assertEquals("one video and one audio track", listOf("vide", "soun"), mp4.traks.map { it.handler }.sorted().reversed())
        val v = mp4.video()
        assertEquals("avc1", v.entryType)
        assertTrue("avcC present: ${v.entryChildren}", "avcC" in v.entryChildren)
        assertEquals(75, v.sampleCount)
        assertEquals(192, v.width); assertEquals(108, v.height)
        assertTrue("first video sample is a key frame", v.isSync(1))
        val a = mp4.audio()
        assertEquals("mp4a", a.entryType)
        assertEquals(0x40, a.esdsObjectType)
        assertEquals(131, a.sampleCount)
        assertEquals("video duration", 3.0, v.durationSeconds, 0.2)
        assertEquals("audio duration", 3.0, a.durationSeconds, 0.2)
        assertEquals("movie duration", 3.0, mp4.movieDuration.toDouble() / mp4.timescale, 0.2)
    }

    @Test fun samplesOfBothTracksAreInterleaved() {
        val (_, mp4) = mux("video-avc.mp4", "audio-aac.m4a")
        val firstAudio = mp4.audio().sampleOffsets().min()
        val lastVideo = mp4.video().sampleOffsets().max()
        val lastAudio = mp4.audio().sampleOffsets().max()
        val firstVideo = mp4.video().sampleOffsets().min()
        assertTrue("audio starts before the last video sample ($firstAudio < $lastVideo)", firstAudio < lastVideo)
        assertTrue("video starts before the last audio sample ($firstVideo < $lastAudio)", firstVideo < lastAudio)
    }

    @Test fun theLongerTrackDecidesTheDuration() {
        val (r, mp4) = mux("video-avc-long.mp4", "audio-aac.m4a")
        assertEquals(125, r.videoOut)
        assertEquals(131, r.audioOut)
        assertEquals(5.0, r.durationUs / 1e6, 0.2)
        assertEquals(5.0, mp4.movieDuration.toDouble() / mp4.timescale, 0.2)
        assertEquals(3.0, mp4.audio().durationSeconds, 0.2)
        val (r2, mp4b) = mux("video-avc.mp4", "audio-aac-short.m4a")
        assertEquals(88, r2.audioOut)
        assertEquals(3.0, mp4b.movieDuration.toDouble() / mp4b.timescale, 0.2)
        assertEquals(2.0, mp4b.audio().durationSeconds, 0.2)
    }

    @Test fun audioOnlyBecomesAPlainM4a() {
        val (r, mp4) = mux(null, "audio-aac.m4a")
        assertEquals("m4a", r.extension)
        assertEquals("audio/mp4", r.mimeType)
        assertEquals(131, r.audioOut)
        assertEquals(0, r.videoOut)
        assertWellFormed(mp4)
        assertEquals(1, mp4.traks.size)
        assertEquals("soun", mp4.traks.single().handler)
        assertEquals(3.0, mp4.audio().durationSeconds, 0.2)
        assertFalse("not a fragmented file any more", mp4.topLevel.any { it.type == "moof" })
    }

    @Test fun theOutputIsAnOrdinaryFileNotAFragmentedOne() {
        val (_, mp4) = mux("video-avc.mp4", "audio-aac.m4a")
        assertFalse("no moof boxes", mp4.topLevel.any { it.type == "moof" })
        assertTrue("sample tables instead", mp4.video().sampleCount > 0)
    }

    @Test fun progressEndsAtTheInputSize() {
        val seen = ArrayList<Pair<Long, Long>>()
        mux("video-avc.mp4", "audio-aac.m4a") { d, t -> seen += d to t }
        assertTrue(seen.isNotEmpty())
        val total = fixture("video-avc.mp4").length() + fixture("audio-aac.m4a").length()
        assertEquals(0L to total, seen.first())
        assertEquals(total to total, seen.last())
        assertTrue("monotonic", seen.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    // ---- codecs beyond H.264 ----

    @Test fun av1InMp4IsAccepted() {
        val (r, mp4) = mux("video-av1.mp4", "audio-aac.m4a")
        assertEquals("mp4", r.extension)
        assertEquals(75, r.videoOut)
        assertWellFormed(mp4)
        assertEquals("av01", mp4.video().entryType)
        assertEquals(3.0, r.durationUs / 1e6, 0.2)
        assertEquals(192, mp4.video().width)
    }

    /** Tried and documented: media3's Mp4Muxer wants a vpcC record that YouTube's WebM files don't have, so VP9 is not offered. */
    @Test fun vp9InWebmIsRejectedWithAClearMessage() {
        try {
            mux("video-vp9.webm", "audio-aac.m4a")
            fail("VP9 is not muxed")
        } catch (e: RemuxException) {
            assertTrue(e.message, e.message!!.contains("unsupported video codec"))
        }
        assertFalse("VP9 is not in the codecs the selector offers", com.olaph.xdm.youtube.VideoCodec.VP9 in com.olaph.xdm.youtube.FormatSelector.DEFAULT_CODECS)
        assertTrue(com.olaph.xdm.youtube.VideoCodec.AV1 in com.olaph.xdm.youtube.FormatSelector.DEFAULT_CODECS)
    }

    // ---- failures ----

    @Test fun opusAudioIsRejectedWithAClearMessage() {
        try {
            mux("video-avc.mp4", "audio-opus.webm")
            fail("Opus is not muxed")
        } catch (e: RemuxException) {
            assertTrue(e.message, e.message!!.contains("unsupported audio codec"))
        }
    }

    @Test fun garbageInputFailsWithoutLeavingAnOutputFile() {
        val bad = File(tmp.root, "bad.mp4").apply { writeBytes(ByteArray(5000) { (it * 31).toByte() }) }
        val out = File(tmp.root, "bad-out.mp4")
        try {
            runBlocking { Mp4DashMuxer().mux(DashInput(bad, "mp4"), DashInput(fixture("audio-aac.m4a"), "m4a"), out) }
            fail("garbage cannot be merged")
        } catch (e: RemuxException) {
            assertFalse("no partial output", out.exists())
        }
    }

    @Test fun emptyPartIsRejected() {
        val empty = File(tmp.root, "empty.mp4").apply { writeBytes(ByteArray(0)) }
        try {
            runBlocking { Mp4DashMuxer().mux(DashInput(empty, "mp4"), DashInput(fixture("audio-aac.m4a"), "m4a"), File(tmp.root, "x.mp4")) }
            fail("empty part")
        } catch (e: RemuxException) {
            assertTrue(e.message, e.message!!.contains("empty"))
        }
    }
}
