package com.olaph.xdm.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerGesturesTest {
    private val w = 1000f
    private val h = 500f
    private val slop = 16f

    @Test fun nothingIsDecidedInsideTheTouchSlop() {
        assertNull(PlayerGestures.classify(100f, w, 10f, -12f, slop))
    }

    @Test fun horizontalSeeksVerticalAdjustsBrightnessLeftAndVolumeRight() {
        assertEquals(DragKind.SEEK, PlayerGestures.classify(100f, w, 40f, 10f, slop))
        assertEquals(DragKind.SEEK, PlayerGestures.classify(900f, w, -40f, -39f, slop))
        assertEquals(DragKind.BRIGHTNESS, PlayerGestures.classify(100f, w, 5f, -40f, slop))
        assertEquals(DragKind.VOLUME, PlayerGestures.classify(600f, w, 5f, 40f, slop))
        assertEquals("the middle belongs to the right half", DragKind.VOLUME, PlayerGestures.classify(500f, w, 0f, 40f, slop))
    }

    @Test fun aFullWidthDragCoversNinetySecondsOrTheWholeShortVideo() {
        assertEquals(100_000L, PlayerGestures.seekTarget(10_000, w, w, 3_600_000))
        assertEquals(55_000L, PlayerGestures.seekTarget(10_000, w / 2, w, 3_600_000))
        assertEquals("a 20 s clip: the whole width is 20 s", 15_000L, PlayerGestures.seekTarget(5_000, w / 2, w, 20_000))
        assertEquals(1_000L, PlayerGestures.seekTarget(10_000, -w / 10, w, 3_600_000))
    }

    @Test fun seekingStaysInsideTheVideo() {
        assertEquals(0L, PlayerGestures.seekTarget(5_000, -w, w, 60_000))
        assertEquals(60_000L, PlayerGestures.seekTarget(50_000, w, w, 60_000))
        assertEquals("unknown duration: only the start is a limit", 95_000L, PlayerGestures.seekTarget(5_000, w, w, -1))
        assertEquals(0L, PlayerGestures.seekTarget(5_000, -w, w, -1))
        assertEquals("no size yet", 5_000L, PlayerGestures.seekTarget(5_000, 300f, 0f, 60_000))
    }

    @Test fun draggingUpBrightensAndTheFullHeightIsTheWholeRange() {
        assertEquals(0.75f, PlayerGestures.brightness(0.5f, -h / 4, h), 1e-6f)
        assertEquals(0.25f, PlayerGestures.brightness(0.5f, h / 4, h), 1e-6f)
        assertEquals(1f, PlayerGestures.brightness(0.5f, -h, h), 1e-6f)
        assertEquals("never fully black", PlayerGestures.MIN_BRIGHTNESS, PlayerGestures.brightness(0.5f, h, h), 1e-6f)
    }

    @Test fun draggingUpIsLouderInWholeSteps() {
        assertEquals(10, PlayerGestures.volume(5, 15, -h / 3, h))
        assertEquals(0, PlayerGestures.volume(5, 15, h, h))
        assertEquals(15, PlayerGestures.volume(5, 15, -h, h))
        assertEquals(6, PlayerGestures.volume(5, 15, -h / 20, h)) // 0.75 of a step rounds up
        assertEquals("no stream info", 5, PlayerGestures.volume(5, 0, -h, h))
    }

    @Test fun doubleTapGoesTenSecondsBackOnTheLeftAndForwardOnTheRight() {
        assertEquals(20_000L, PlayerGestures.doubleTapTarget(100f, w, 30_000, 60_000))
        assertEquals(40_000L, PlayerGestures.doubleTapTarget(900f, w, 30_000, 60_000))
        assertEquals(0L, PlayerGestures.doubleTapTarget(100f, w, 4_000, 60_000))
        assertEquals(60_000L, PlayerGestures.doubleTapTarget(900f, w, 55_000, 60_000))
    }

    @Test fun timesAreFormattedLikeAPlayer() {
        assertEquals("0:00", PlayerGestures.formatTime(-5))
        assertEquals("0:07", PlayerGestures.formatTime(7_999))
        assertEquals("12:34", PlayerGestures.formatTime(754_000))
        assertEquals("1:02:03", PlayerGestures.formatTime(3_723_000))
    }
}

class ResumePolicyTest {
    @Test fun resumesBetweenFiveSecondsAndNinetyFivePercent() {
        assertEquals(60_000L, ResumePolicy.startPosition(60_000, 600_000))
        assertEquals("only 5 s in: start over", 0L, ResumePolicy.startPosition(5_000, 600_000))
        assertEquals(5_001L, ResumePolicy.startPosition(5_001, 600_000))
        assertEquals("95 % watched counts as finished", 0L, ResumePolicy.startPosition(570_000, 600_000))
        assertEquals(569_999L, ResumePolicy.startPosition(569_999, 600_000))
        assertEquals("unknown duration: trust the saved position", 60_000L, ResumePolicy.startPosition(60_000, -1))
        assertEquals(0L, ResumePolicy.startPosition(0, 600_000))
    }

    @Test fun whatIsStored() {
        assertEquals(120_000L, ResumePolicy.toSave(120_000, 600_000, ended = false))
        assertEquals("finished clears it", 0L, ResumePolicy.toSave(599_000, 600_000, ended = true))
        assertEquals("nearly finished clears it", 0L, ResumePolicy.toSave(580_000, 600_000, ended = false))
        assertEquals("barely started is not worth keeping", 0L, ResumePolicy.toSave(3_000, 600_000, ended = false))
        assertEquals(30_000L, ResumePolicy.toSave(30_000, -1, ended = false))
    }

    @Test fun whatIsStoredIsWhatResumes() {
        for (pos in listOf(0L, 4_000L, 6_000L, 300_000L, 569_000L, 571_000L)) {
            val saved = ResumePolicy.toSave(pos, 600_000, ended = false)
            assertEquals("pos=$pos", saved, ResumePolicy.startPosition(saved, 600_000))
        }
    }
}
