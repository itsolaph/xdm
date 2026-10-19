package com.olaph.xdm.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Virtual time throughout: the limiter reads the test scheduler's clock and sleeps with the test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedLimiterTest {
    private fun TestScope.limiter() = SpeedLimiter(nanoTime = { testScheduler.currentTime * 1_000_000L })

    /** [readers] loops that take [chunk]-byte slices for as long as they are allowed; returns the shared byte counter. */
    private fun TestScope.startReaders(limiter: SpeedLimiter, readers: Int, chunk: Int): AtomicLong {
        val total = AtomicLong()
        repeat(readers) {
            launch {
                while (true) {
                    limiter.acquire(chunk)
                    total.addAndGet(chunk.toLong())
                }
            }
        }
        return total
    }

    @Test fun severalReadersTogetherStayWithinTenPercentOfTheCap() = runTest {
        val limiter = limiter().apply { bytesPerSecond = 1_000_000 }
        val total = startReaders(limiter, readers = 6, chunk = 16 * 1024)
        advanceTimeBy(20_000)
        runCurrent()
        val rate = total.get() / 20.0
        assertTrue("rate $rate", rate in 900_000.0..1_100_000.0)
        coroutineContext.cancelChildren()
    }

    @Test fun aSingleSlowCapIsHeldAsWell() = runTest {
        val limiter = limiter().apply { bytesPerSecond = 256 * 1024 }
        val total = startReaders(limiter, readers = 1, chunk = 8 * 1024)
        advanceTimeBy(30_000)
        runCurrent()
        val rate = total.get() / 30.0
        assertTrue("rate $rate", rate in (256 * 1024 * 0.9)..(256 * 1024 * 1.1))
        coroutineContext.cancelChildren()
    }

    @Test fun offTakesNeitherTheClockNorASleep() = runTest {
        var clockReads = 0
        var sleeps = 0
        val limiter = SpeedLimiter(nanoTime = { clockReads++; 0L }, sleep = { sleeps++ })
        repeat(1000) { limiter.acquire(64 * 1024) }
        assertEquals(0, clockReads)
        assertEquals(0, sleeps)
        assertFalse(limiter.active)
        assertEquals(64 * 1024, limiter.maxChunk(64 * 1024))
    }

    @Test fun aNewLimitAppliesToReadersThatAreAlreadyRunning() = runTest {
        val limiter = limiter().apply { bytesPerSecond = 100_000 }
        val total = startReaders(limiter, readers = 3, chunk = 4 * 1024)
        advanceTimeBy(10_000)
        runCurrent()
        val slow = total.get()
        assertTrue("slow $slow", slow in 900_000L..1_100_000L)

        limiter.bytesPerSecond = 400_000
        advanceTimeBy(10_000)
        runCurrent()
        val fast = total.get() - slow
        assertTrue("fast $fast", fast in 3_600_000L..4_400_000L)

        limiter.bytesPerSecond = 50_000
        advanceTimeBy(10_000)
        runCurrent()
        val slowAgain = total.get() - slow - fast
        assertTrue("slowAgain $slowAgain", slowAgain in 450_000L..550_000L)
        coroutineContext.cancelChildren()
    }

    @Test fun switchingTheLimitOffLetsReadsThroughAtOnce() = runTest {
        val limiter = limiter().apply { bytesPerSecond = 1_000 }
        limiter.acquire(10) // inside the burst allowance, no wait
        limiter.bytesPerSecond = 0
        val before = testScheduler.currentTime
        repeat(100) { limiter.acquire(1_000_000) }
        assertEquals(before, testScheduler.currentTime)
    }

    @Test fun readsGetSmallerWithTheCapSoWaitsStayShort() {
        val limiter = SpeedLimiter()
        limiter.bytesPerSecond = 200_000
        assertEquals(20_000, limiter.maxChunk(64 * 1024))
        limiter.bytesPerSecond = 2_000
        assertEquals(1024, limiter.maxChunk(64 * 1024))
        limiter.bytesPerSecond = 50_000_000
        assertEquals(64 * 1024, limiter.maxChunk(64 * 1024))
    }

    @Test fun anIdlePeriodEarnsOnlyAShortBurst() = runTest {
        val limiter = limiter().apply { bytesPerSecond = 100_000 }
        testScheduler.advanceTimeBy(60_000) // a long pause does not build up a minute of credit
        val total = startReaders(limiter, readers = 1, chunk = 1000)
        advanceTimeBy(1_000)
        runCurrent()
        // one second at the cap plus the 0.25 s burst
        assertTrue("total ${total.get()}", total.get() in 100_000L..130_000L)
        coroutineContext.cancelChildren()
    }
}
