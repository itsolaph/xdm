package com.olaph.xdm.remux

import com.olaph.xdm.remux.TsTimeline.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsTimelineTest {
    private val s = 1_000_000L
    private val frame = 40_000L // 25 fps
    private val aac = 23_220L

    private fun timeline(zero: Long = 0, vararg kinds: Kind): Pair<TsTimeline, List<Int>> {
        val t = TsTimeline()
        val ids = kinds.map { t.addTrack(it) }
        t.begin(zero)
        return t to ids
    }

    @Test fun firstSampleLandsOnZeroAndTimeFlowsOn() {
        val (t, ids) = timeline(1_480_000, Kind.VIDEO, Kind.AUDIO)
        assertEquals(0L, t.map(ids[0], 1_480_000, true))
        assertEquals(40_000L, t.map(ids[0], 1_520_000, false))
        assertEquals(0L, t.map(ids[1], 1_480_000, false))
        assertEquals(0, t.discontinuities)
    }

    @Test fun samplesBeforeTheStartAreDropped() {
        val (t, ids) = timeline(1_000_000, Kind.AUDIO)
        assertEquals(TsTimeline.DROP, t.map(ids[0], 990_000, false))
        assertEquals(10_000L, t.map(ids[0], 1_010_000, false))
    }

    @Test fun reorderedFramesAreNotMistakenForAJump() {
        val (t, ids) = timeline(0, Kind.VIDEO)
        // decode order I P B B: presentation times 0, 3, 1, 2 frames
        val out = listOf(0L to true, 3 * frame to false, frame to false, 2 * frame to false, 6 * frame to false, 4 * frame to false, 5 * frame to false)
            .map { (raw, key) -> t.map(ids[0], raw, key) }
        assertEquals(listOf(0L, 3 * frame, frame, 2 * frame, 6 * frame, 4 * frame, 5 * frame), out)
        assertEquals(0, t.discontinuities)
    }

    @Test fun audioIsAlwaysStrictlyIncreasing() {
        val (t, ids) = timeline(0, Kind.AUDIO)
        t.map(ids[0], 0, false)
        val a = t.map(ids[0], aac, false)
        // a repeated timestamp (re-anchoring at a PES boundary) is nudged forward, not duplicated
        val b = t.map(ids[0], aac, false)
        assertTrue(b > a)
    }

    @Test fun clockResetToZeroContinuesWhereTheContentEnded() {
        val (t, ids) = timeline(1_400_000, Kind.VIDEO, Kind.AUDIO)
        var lastV = 0L
        for (i in 0 until 75) lastV = t.map(ids[0], 1_400_000 + i * frame, i % 25 == 0)
        var lastA = 0L
        for (i in 0 until 130) lastA = t.map(ids[1], 1_400_000 + i * aac, false)
        assertEquals(74 * frame, lastV)
        // second clip starts at the same raw time again
        val v0 = t.map(ids[0], 1_400_000, true)
        val a0 = t.map(ids[1], 1_400_000, false)
        assertEquals(1, t.discontinuities)
        assertEquals("the new clip starts after the longest track (audio) of the old one", 130 * aac, v0)
        assertTrue("audio is not placed before its own last frame: $a0 vs $lastA", a0 > lastA)
        assertTrue("and stays within one frame of the picture", Math.abs(a0 - v0) < 2 * frame)
        assertEquals(v0 + frame, t.map(ids[0], 1_400_000 + frame, false))
    }

    @Test fun aBigForwardJumpDoesNotLeaveAHole() {
        val (t, ids) = timeline(0, Kind.VIDEO, Kind.AUDIO)
        for (i in 0 until 50) t.map(ids[0], i * frame, i % 25 == 0)
        for (i in 0 until 86) t.map(ids[1], i * aac, false)
        val jump = 3_600 * s
        val v = t.map(ids[0], jump, true)
        val a = t.map(ids[1], jump, false)
        assertEquals(1, t.discontinuities)
        assertEquals(50 * frame, v)
        assertTrue(a in (50 * frame - 5 * frame)..(50 * frame + 5 * frame))
        assertEquals(51 * frame, t.map(ids[0], jump + frame, false))
    }

    @Test fun aSparseVideoTrackIsNotCutShortWhenAudioKeepsTheClockFresh() {
        val (t, ids) = timeline(0, Kind.VIDEO, Kind.AUDIO)
        t.map(ids[0], 0, true)
        // 30 s of audio, then a key frame 30 s after the last one: that is content, not a clock jump
        for (i in 0 until 1300) t.map(ids[1], i * aac, false)
        val v = t.map(ids[0], 30 * s, true)
        assertEquals(0, t.discontinuities)
        assertEquals(30 * s, v)
    }

    @Test fun theLateTailOfTheOldClockStaysWithTheOldClock() {
        // Video jumps first; the previous audio PES (still on the old clock) is only emitted afterwards.
        val (t, ids) = timeline(0, Kind.VIDEO, Kind.AUDIO)
        for (i in 0 until 50) t.map(ids[0], i * frame, i % 25 == 0)
        for (i in 0 until 80) t.map(ids[1], i * aac, false) // audio so far ends at 1.86 s, video at 2.0 s
        val v0 = t.map(ids[0], 0, true) // video clock reset
        assertEquals(1, t.discontinuities)
        assertEquals(50 * frame, v0)
        val tail = (80 until 86).map { t.map(ids[1], it * aac, false) } // old clock, below the video's end
        assertEquals(80 * aac, tail.first())
        val a0 = t.map(ids[1], 0, false) // audio reaches the reset
        assertEquals("joins the epoch the video created", 1, t.discontinuities)
        assertTrue("audio stays increasing: $a0 after ${tail.last()}", a0 > tail.last())
        assertTrue("within a couple of frames of the video start: $a0 vs $v0", a0 - v0 < 3 * frame)
    }

    @Test fun twoResetsGiveThreeEpochs() {
        val (t, ids) = timeline(0, Kind.VIDEO)
        var last = 0L
        for (clip in 0 until 3) for (i in 0 until 50) last = t.map(ids[0], i * frame, i % 25 == 0)
        assertEquals(2, t.discontinuities)
        assertEquals(149 * frame, last)
        assertEquals(150 * frame, t.endUs())
    }

    @Test fun anUnusuallyLongBackJumpOfKeyFrameOnlyFiresForKeyFrames() {
        val (t, ids) = timeline(0, Kind.VIDEO)
        for (i in 0 until 10) t.map(ids[0], i * frame, i == 0)
        // a non-key frame a little before the newest one is just reordering
        assertEquals(7 * frame, t.map(ids[0], 7 * frame, false))
        assertEquals(0, t.discontinuities)
        // a key frame that is not after everything before it is a new clock
        t.map(ids[0], 2 * frame, true)
        assertEquals(1, t.discontinuities)
    }
}
