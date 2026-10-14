package com.olaph.xdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentTableTest {

    @Test fun unstartedSegmentsComeFirstInFileOrder() {
        val table = SegmentTable(listOf(SegmentState(1, 500, 999), SegmentState(0, 0, 499, 499), SegmentState(2, 1000, 1499, 1500 - 1000)))
        // 2 is complete and skipped; 1 and 0 are handed out by start offset.
        assertEquals(0, table.next(0)!!.index)
        assertEquals(1, table.next(0)!!.index)
        assertNull("splitting disabled", table.next(0))
    }

    @Test fun splitsTheBusiestRunningSegmentAtTheMiddleOfWhatIsLeft() {
        val table = SegmentTable(listOf(SegmentState(0, 0, 999), SegmentState(1, 1000, 1999)))
        val a = table.next(100)!!
        val b = table.next(100)!!
        table.begin(a)
        table.begin(b)
        assertEquals(200, table.claim(b, 1000, 200)) // b has 800 left, a 1000
        val tail = table.next(100)!!
        assertEquals(2, tail.index)
        assertEquals(500L, tail.start)
        assertEquals(999L, tail.end)
        assertEquals(499L, a.end)
        assertTrue(SegmentPlanner.coversExactly(table.snapshot(), 2000))

        // b now has the most left (800 against 500 and 500).
        val tail2 = table.next(100)!!
        assertEquals(1600L, tail2.start)
        assertEquals(1599L, b.end)
        assertTrue(SegmentPlanner.coversExactly(table.snapshot(), 2000))
    }

    @Test fun readerNeverWritesPastTheMovedEnd() {
        val table = SegmentTable(listOf(SegmentState(0, 0, 999), SegmentState(1, 1000, 1999)))
        val a = table.next(10)!!
        val b = table.next(10)!!
        table.begin(a)
        table.begin(b)
        assertEquals(500, table.claim(b, 1000, 500))
        assertEquals(100, table.claim(a, 0, 100))
        val tail = table.next(10)!! // a: 900 left from 100 -> split at 550
        assertEquals(550L, tail.start)
        assertEquals(450, table.claim(a, 100, 1000))
        assertEquals(0, table.claim(a, 550, 10))
    }

    @Test fun noSplitBelowTheThresholdOrForOpenEndedSegments() {
        val small = SegmentTable(listOf(SegmentState(0, 0, 99)))
        small.next(100)
        assertNull(small.next(101))
        val open = SegmentTable(listOf(SegmentState(0, 0, -1)))
        open.next(1)
        assertNull(open.next(1))
    }

    @Test fun finishedSegmentsAreNotSplit() {
        val table = SegmentTable(listOf(SegmentState(0, 0, 999)))
        val a = table.next(10)!!
        table.finished(a)
        assertNull(table.next(10))
    }

    @Test fun coverageCheck() {
        assertTrue(SegmentPlanner.coversExactly(listOf(SegmentState(1, 50, 99), SegmentState(0, 0, 49)), 100))
        assertFalse("gap", SegmentPlanner.coversExactly(listOf(SegmentState(0, 0, 48), SegmentState(1, 50, 99)), 100))
        assertFalse("overlap", SegmentPlanner.coversExactly(listOf(SegmentState(0, 0, 50), SegmentState(1, 50, 99)), 100))
        assertFalse("short", SegmentPlanner.coversExactly(listOf(SegmentState(0, 0, 49)), 100))
        assertFalse("unknown size", SegmentPlanner.coversExactly(listOf(SegmentState(0, 0, -1)), -1))
    }
}
