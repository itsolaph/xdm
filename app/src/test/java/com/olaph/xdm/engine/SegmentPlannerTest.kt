package com.olaph.xdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlannerTest {

    private fun assertCovers(size: Long, segs: List<SegmentState>) {
        assertEquals(0L, segs.first().start)
        assertEquals(size - 1, segs.last().end)
        segs.zipWithNext { a, b -> assertEquals("gap/overlap between ${a.index} and ${b.index}", a.end + 1, b.start) }
        assertEquals(size, segs.sumOf { it.length })
        segs.forEachIndexed { i, s -> assertEquals(i, s.index); assertTrue(s.length > 0) }
    }

    @Test fun evenSplit() {
        val segs = SegmentPlanner.plan(8L * 1024 * 1024, 8)
        assertEquals(8, segs.size)
        assertTrue(segs.all { it.length == 1024L * 1024 })
        assertCovers(8L * 1024 * 1024, segs)
    }

    @Test fun sizeNotDivisible() {
        val size = 10L * 1024 * 1024 + 7
        val segs = SegmentPlanner.plan(size, 8)
        assertEquals(8, segs.size)
        assertCovers(size, segs)
        // remainder is spread: lengths differ by at most one byte
        assertTrue(segs.maxOf { it.length } - segs.minOf { it.length } <= 1)
    }

    @Test fun tinyFileUsesOneConnection() {
        val segs = SegmentPlanner.plan(1024L * 1024 - 1, 16)
        assertEquals(1, segs.size)
        assertCovers(1024L * 1024 - 1, segs)
    }

    @Test fun exactlyThresholdIsSplit() {
        val segs = SegmentPlanner.plan(SegmentPlanner.SPLIT_THRESHOLD, 4)
        assertEquals(4, segs.size)
        assertCovers(SegmentPlanner.SPLIT_THRESHOLD, segs)
    }

    @Test fun singleThread() {
        val segs = SegmentPlanner.plan(50L * 1024 * 1024, 1)
        assertEquals(1, segs.size)
        assertCovers(50L * 1024 * 1024, segs)
    }

    @Test fun oneByteFile() {
        val segs = SegmentPlanner.plan(1, 8)
        assertEquals(listOf(SegmentState(0, 0, 0)), segs)
    }

    @Test fun connectionCountIsClamped() {
        assertEquals(16, SegmentPlanner.plan(100L * 1024 * 1024, 99).size)
        assertEquals(1, SegmentPlanner.plan(100L * 1024 * 1024, 0).size)
        assertEquals(1, SegmentPlanner.plan(100L * 1024 * 1024, -5).size)
    }

    @Test fun unknownSizeIsOpenEnded() {
        val segs = SegmentPlanner.plan(-1, 8)
        assertEquals(1, segs.size)
        assertEquals(-1L, segs[0].end)
        assertEquals(-1L, segs[0].length)
        assertTrue(!segs[0].isComplete)
    }

    @Test fun hugeFile() {
        val size = 40L * 1024 * 1024 * 1024 + 123
        val segs = SegmentPlanner.plan(size, 16)
        assertEquals(16, segs.size)
        assertCovers(size, segs)
    }
}
