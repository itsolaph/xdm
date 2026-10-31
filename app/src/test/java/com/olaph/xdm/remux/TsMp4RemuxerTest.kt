package com.olaph.xdm.remux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Random

/**
 * Runs the real remuxer (media3 TsExtractor + Mp4Muxer on Robolectric's framework classes) over ffmpeg-made
 * MPEG-TS fixtures and checks the result with an independent MP4 parser. The expected sample counts are the
 * packet counts `ffprobe -count_packets` reports for the same inputs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class) // not XdmApp: its start-up loads the ad-block engine
class TsMp4RemuxerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/remux/$name")) { "missing fixture $name" }.use { it.readBytes() }

    private fun fixture(vararg names: String): File {
        val f = tmp.newFile()
        f.outputStream().use { out -> names.forEach { out.write(resource(it)) } }
        return f
    }

    @get:Rule val testName = org.junit.rules.TestName()

    private fun remux(input: File, out: File = File(tmp.root, "out-${System.nanoTime()}.mp4")): Pair<RemuxResult, Mp4File> {
        val r = runBlocking { TsMp4Remuxer().remux(input, out) }
        // Manual check on a machine with ffprobe: create app/build/remux-out and the outputs are kept there.
        File("build/remux-out").takeIf { it.isDirectory }?.let { out.copyTo(File(it, "${testName.methodName}.${r.extension}"), overwrite = true) }
        return r to Mp4File(out.readBytes())
    }

    private fun Mp4File.video() = traks.single { it.handler == "vide" }
    private fun Mp4File.audio() = traks.single { it.handler == "soun" }

    /** Everything that must hold for any output of this remuxer. */
    private fun assertWellFormed(mp4: Mp4File) {
        assertEquals("ftyp", mp4.topLevel.first().type)
        assertTrue("brands ${mp4.brands}", mp4.brands.any { it == "isom" || it == "mp42" || it == "iso2" })
        assertTrue("seekable: moov present", mp4.topLevel.any { it.type == "moov" })
        val mdat = mp4.topLevel.first { it.type == "mdat" }
        for (t in mp4.traks) {
            assertEquals("stts covers every sample", t.sampleCount, t.sttsDeltas.size)
            val offsets = t.sampleOffsets()
            for (i in offsets.indices) {
                assertTrue("sample $i inside mdat", offsets[i] >= mdat.payload && offsets[i] + t.sizes[i] <= mdat.end)
            }
            val dts = t.decodeTimes()
            for (i in 1 until dts.size) assertTrue("DTS is monotonic", dts[i] >= dts[i - 1])
            if (t.cttsOffsets != null) {
                val pts = LongArray(dts.size) { dts[it] + t.cttsOffsets[it] }
                assertTrue("no negative presentation time", pts.min() >= 0)
            }
        }
    }

    private fun assertVideoOk(mp4: Mp4File, frames: Int, fourcc: String, configBox: String) {
        val v = mp4.video()
        assertEquals(fourcc, v.entryType)
        assertTrue("$configBox present: ${v.entryChildren}", configBox in v.entryChildren)
        assertEquals(frames, v.sampleCount)
        assertEquals(320, v.width)
        assertEquals(240, v.height)
        assertTrue("first video sample is a key frame", v.isSync(1))
        assertNotNull("stss (sync sample table) is present", v.syncSamples)
        assertEquals(1, v.syncSamples!!.first())
        // The sample is a real access unit: length-prefixed NAL units that tile it exactly.
        val first = mp4.nalUnits(v.sampleOffsets()[0], v.sizes[0])
        val types = first.map { if (fourcc == "avc1") it[0].toInt() and 0x1F else (it[0].toInt() shr 1) and 0x3F }
        if (fourcc == "avc1") assertTrue("IDR slice in first sample: $types", 5 in types) else assertTrue("IRAP in first sample: $types", types.any { it in 16..21 })
    }

    private fun assertAacOk(mp4: Mp4File, frames: Int) {
        val a = mp4.audio()
        assertEquals("mp4a", a.entryType)
        assertEquals(0x40, a.esdsObjectType)
        assertEquals(frames, a.sampleCount)
        val first = mp4.bytes.copyOfRange(a.sampleOffsets()[0].toInt(), a.sampleOffsets()[0].toInt() + 2)
        assertFalse("raw AAC, no ADTS header", (first[0].toInt() and 0xFF) == 0xFF && (first[1].toInt() and 0xF0) == 0xF0)
        assertTrue("AAC frames are small", a.sizes.all { it in 1..2000 })
    }

    private fun assertDuration(t: Mp4File.Trak, seconds: Double, tolerance: Double = 0.2) {
        assertEquals("track duration", seconds, t.durationSeconds, tolerance)
    }

    // ---- fixtures ----

    @Test fun aac44100IsMuxedOnItsExactGrid() {
        val (r, mp4) = remux(fixture("av.ts"))
        assertEquals("mp4", r.extension)
        assertWellFormed(mp4)
        val a = mp4.audio()
        // media3 pre-1.9 hardcoded a 48 kHz audio timescale, silently stretching 44.1 kHz audio (~+4 s
        // over a 2.5 h capture) until it lagged the video. The timescale must be the real sample rate.
        assertEquals(44100, a.timescale)
        // And the audio sits on its exact content grid: every frame is 1024 ticks no matter what the
        // microsecond jitter of the source stamps is, so the span is frames*1024.
        assertTrue("uniform 1024-tick frames: ${a.sttsDeltas.toList().distinct()}", a.sttsDeltas.all { it == 1024L })
        val dts = a.decodeTimes()
        assertEquals((a.sampleCount - 1) * 1024L, dts.last() - dts.first())
        // ...which keeps audio locked to video: both tracks end within 100 ms of each other.
        val v = mp4.video()
        val audioEndUs = dts.last() * 1_000_000 / a.timescale
        val videoEndUs = v.decodeTimes().last() * 1_000_000 / v.timescale
        assertTrue("audio ends ${audioEndUs - videoEndUs} us from video", Math.abs(audioEndUs - videoEndUs) <= 100_000)
    }

    @Test fun h264AacInOneTsFile() {
        val (r, mp4) = remux(fixture("av.ts"))
        assertEquals("mp4", r.extension)
        assertEquals("video/mp4", r.mimeType)
        assertEquals(125, r.videoIn); assertEquals(125, r.videoOut)
        assertEquals(217, r.audioIn); assertEquals(217, r.audioOut)
        assertEquals(0, r.discontinuities)

        assertWellFormed(mp4)
        assertEquals(2, mp4.traks.size)
        assertVideoOk(mp4, 125, "avc1", "avcC")
        assertAacOk(mp4, 217)
        assertDuration(mp4.video(), 5.0)
        assertDuration(mp4.audio(), 5.0)
        assertEquals(5.0, mp4.movieDuration.toDouble() / mp4.timescale, 0.2)
        assertEquals(5.0, r.durationUs / 1e6, 0.2)
        assertTrue("moov is in front of mdat (plays while downloading)", mp4.moovBeforeMdat)
        // B-frames are in the stream, so there are composition offsets.
        assertNotNull(mp4.video().cttsOffsets)
    }

    @Test fun hlsSegmentsConcatenatedBehaveLikeOneStream() {
        val (r, mp4) = remux(fixture("hls-seg0.ts", "hls-seg1.ts", "hls-seg2.ts"))
        assertEquals(125, r.videoOut); assertEquals(217, r.audioOut)
        assertEquals("segments carry continuous timestamps", 0, r.discontinuities)
        assertWellFormed(mp4)
        assertVideoOk(mp4, 125, "avc1", "avcC")
        assertAacOk(mp4, 217)
        assertDuration(mp4.video(), 5.0)
        assertDuration(mp4.audio(), 5.0)
        // the encoder puts a key frame every second (and every 2 s segment starts with one)
        assertEquals(5, mp4.video().syncSamples!!.size)
    }

    @Test fun startsAtALargePtsAndCrossesThe33BitWrap() {
        val (r, mp4) = remux(fixture("wrap.ts"))
        assertEquals(125, r.videoOut); assertEquals(217, r.audioOut)
        assertEquals("the wrap is not a discontinuity", 0, r.discontinuities)
        assertWellFormed(mp4)
        assertVideoOk(mp4, 125, "avc1", "avcC")
        assertDuration(mp4.video(), 5.0)
        assertDuration(mp4.audio(), 5.0)
        assertTrue("normalized to start at 0, not 26 hours", mp4.movieDuration.toDouble() / mp4.timescale < 6.0)
    }

    @Test fun aTimelineResetInTheMiddleIsStitchedIntoOneMonotonicTimeline() {
        val (r, mp4) = remux(fixture("disc-a.ts", "disc-b.ts"))
        assertEquals(125, r.videoOut); assertEquals(219, r.audioOut)
        assertEquals(1, r.discontinuities)
        assertWellFormed(mp4)
        assertVideoOk(mp4, 125, "avc1", "avcC")
        assertEquals(219, mp4.audio().sampleCount)
        // 3 s + 2 s, no gap and no overlap
        assertDuration(mp4.video(), 5.0)
        assertDuration(mp4.audio(), 5.0)
        // the second clip's key frame sits right after the first clip's last frame
        val v = mp4.video()
        val pts = LongArray(v.sampleCount) { v.decodeTimes()[it] + (v.cttsOffsets?.get(it) ?: 0) }
        val second = pts[v.syncSamples!![3] - 1] // key frames at 0, 1, 2 s, then the second clip
        assertEquals(3.0, second.toDouble() / v.timescale, 0.1)
    }

    @Test fun audioOnlyAacBecomesM4a() {
        val (r, mp4) = remux(fixture("audio.ts"))
        assertEquals("m4a", r.extension)
        assertEquals("audio/mp4", r.mimeType)
        assertEquals(217, r.audioOut); assertEquals(0, r.videoIn)
        assertWellFormed(mp4)
        assertEquals(1, mp4.traks.size)
        assertAacOk(mp4, 217)
        assertDuration(mp4.audio(), 5.0)
    }

    @Test fun h265IsCopiedToo() {
        val (r, mp4) = remux(fixture("hevc.ts"))
        assertEquals(125, r.videoOut); assertEquals(217, r.audioOut)
        assertWellFormed(mp4)
        assertVideoOk(mp4, 125, "hvc1", "hvcC")
        assertAacOk(mp4, 217)
        assertDuration(mp4.video(), 5.0)
    }

    @Test fun mp3AudioIsMarkedAsMpegAudioInTheEsds() {
        val (r, mp4) = remux(fixture("mp3.ts"))
        assertEquals(75, r.videoOut); assertEquals(116, r.audioOut)
        assertWellFormed(mp4)
        val a = mp4.audio()
        assertEquals("mp4a", a.entryType)
        assertEquals("object type patched from AAC (0x40) to MPEG-1 audio (0x6B)", 0x6B, a.esdsObjectType)
        assertEquals(116, a.sampleCount)
        // MP3 frames keep their header (sync word 0xFFE/0xFFF) inside the MP4 sample
        val off = a.sampleOffsets()[0].toInt()
        assertEquals(0xFF, mp4.bytes[off].toInt() and 0xFF)
        assertEquals(0xE0, mp4.bytes[off + 1].toInt() and 0xE0)
        assertDuration(a, 3.0)
    }

    @Test fun anUnsupportedAudioCodecFailsInsteadOfDroppingTheSound() {
        val input = fixture("ac3.ts")
        val out = File(tmp.root, "ac3.mp4")
        try {
            runBlocking { TsMp4Remuxer().remux(input, out) }
            fail("AC-3 cannot go into this MP4 path")
        } catch (e: RemuxException) {
            assertTrue(e.message, e.message!!.contains("AC-3"))
        }
        assertFalse("no partial output", out.exists())
        assertTrue("input untouched", input.length() > 0)
    }

    @Test fun anUnsupportedSecondAudioStreamIsLeftOutWhenAnotherOneCanBeCopied() {
        val (r, mp4) = remux(fixture("aac-ac3.ts"))
        assertEquals(50, r.videoOut); assertEquals(95, r.audioOut)
        assertWellFormed(mp4)
        assertEquals(2, mp4.traks.size)
        assertAacOk(mp4, 95)
    }

    // ---- failures ----

    @Test fun garbageIsReportedAndLeavesNoOutput() {
        val input = tmp.newFile().also { f -> f.writeBytes(ByteArray(200_000).also { Random(3).nextBytes(it) }) }
        val out = File(tmp.root, "garbage.mp4")
        try {
            runBlocking { TsMp4Remuxer().remux(input, out) }
            fail("garbage must not convert")
        } catch (e: RemuxException) {
            assertTrue(e.message, e.message!!.contains("MPEG-TS"))
        }
        assertFalse(out.exists())
        assertEquals(200_000L, input.length())
    }

    @Test fun anEmptyFileAndAFileThatIsTooShortAreReported() {
        for (size in listOf(0, 100, 600)) {
            val input = tmp.newFile().also { it.writeBytes(ByteArray(size)) }
            val out = File(tmp.root, "short$size.mp4")
            try {
                runBlocking { TsMp4Remuxer().remux(input, out) }
                fail("size $size must not convert")
            } catch (e: RemuxException) {
                assertNotNull(e.message)
            }
            assertFalse(out.exists())
        }
    }

    @Test fun validTransportPacketsWithoutAnyAudioOrVideoAreReported() {
        // 100 null packets (PID 0x1FFF): a real TS, nothing in it.
        val nulls = ByteArray(188 * 100)
        for (i in 0 until 100) { nulls[i * 188] = 0x47; nulls[i * 188 + 1] = 0x1F; nulls[i * 188 + 2] = 0xFF.toByte(); nulls[i * 188 + 3] = 0x10 }
        val input = tmp.newFile().also { it.writeBytes(nulls) }
        try {
            runBlocking { TsMp4Remuxer().remux(input, File(tmp.root, "nulls.mp4")) }
            fail()
        } catch (e: RemuxException) {
            assertTrue(e.message, e.message!!.contains("no audio or video"))
        }
    }

    @Test fun aTruncatedDownloadStillConvertsWhatIsThere() {
        val full = resource("av.ts")
        val cut = tmp.newFile().also { it.writeBytes(full.copyOf(full.size / 2 + 77)) } // mid-packet
        val (r, mp4) = remux(cut)
        assertTrue("some video: ${r.videoOut}", r.videoOut in 40..100)
        assertWellFormed(mp4)
        assertEquals(r.videoOut, mp4.video().sampleCount)
        assertEquals(r.audioOut, mp4.audio().sampleCount)
    }

    @Test fun cuttingLeadingBytesStillFindsTheStream() {
        val full = resource("av.ts")
        val shifted = tmp.newFile().also { it.writeBytes(full.copyOfRange(500, full.size)) } // starts mid-packet and mid-GOP
        val (r, mp4) = remux(shifted)
        assertTrue("the first (damaged) GOP is skipped: ${r.videoOut}", r.videoOut in 90..100)
        assertWellFormed(mp4)
        assertTrue("starts on a key frame", mp4.video().isSync(1))
    }

    // ---- progress / cancellation ----

    @Test fun progressIsMonotonicAndEndsAtTheFileSize() {
        val input = fixture("av.ts", "av.ts", "av.ts", "av.ts", "av.ts", "av.ts") // 1 MB: several progress steps
        val seen = ArrayList<Pair<Long, Long>>()
        val out = File(tmp.root, "p.mp4")
        runBlocking { TsMp4Remuxer().remux(input, out) { done, total -> seen += done to total } }
        assertTrue("several updates: ${seen.size}", seen.size >= 4)
        assertEquals(0L, seen.first().first)
        assertEquals(input.length() to input.length(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertTrue(seen.all { it.second == input.length() && it.first <= it.second })
    }

    @Test fun cancellingStopsTheConversionAndDeletesThePartialFile() {
        val input = fixture("av.ts", "av.ts", "av.ts", "av.ts", "av.ts", "av.ts")
        val out = File(tmp.root, "cancelled.mp4")
        var started = false
        runBlocking {
            lateinit var job: Job
            job = async {
                TsMp4Remuxer().remux(input, out) { done, _ ->
                    // deterministic: the first progress callback (before any byte is read) cancels the job
                    if (!started) { started = true; job.cancel() }
                }
            }
            try {
                job.await()
                fail("cancelled")
            } catch (e: CancellationException) {
                // expected
            }
        }
        assertTrue(started)
        assertFalse("partial output removed", out.exists())
        assertTrue("the .ts is untouched", input.length() > 0)
    }

    @Test fun outputOverwritesAnExistingFile() {
        val out = tmp.newFile("existing.mp4").also { it.writeBytes(ByteArray(10) { 7 }) }
        val (_, mp4) = remux(fixture("audio.ts"), out)
        assertEquals(1, mp4.traks.size)
    }
}
