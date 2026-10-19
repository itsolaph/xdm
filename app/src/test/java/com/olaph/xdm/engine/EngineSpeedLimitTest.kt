package com.olaph.xdm.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * The cap really sits on the read loop of the multi-connection engine. The limiter's clock only moves when it sleeps, so
 * the time a limit costs is exact and the test does not wait for it.
 */
class EngineSpeedLimitTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(3 * 1024 * 1024).also { Random(31).nextBytes(it) }
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { dispatcher = DownloadEngineTest.TestDispatcher(payload); start() }
    }

    @After fun tearDown() = server.shutdown()

    private class Store : ProgressStore {
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) = Unit
        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) = Unit
    }

    private fun run(limiter: SpeedLimiter, file: File) = runBlocking {
        DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, limiter = limiter).download(
            EngineRequest(1, server.url("/f.bin").toString(), "test", file, connections = 1), Store(),
        )
    }

    @Test fun aCapSlowsTheDownloadToTheRateAndKeepsTheBytesExact() {
        var nanos = 0L
        val limiter = SpeedLimiter(nanoTime = { nanos }, sleep = { nanos += it }).apply { bytesPerSecond = 1024 * 1024 }
        val file = File(tmp.root, "out.part")
        run(limiter, file)
        assertArrayEquals(payload, file.readBytes())
        val seconds = nanos / 1e9
        assertTrue("took $seconds s for 3 MB at 1 MB/s", seconds in 2.7..3.0)
    }

    @Test fun withoutACapTheLimiterIsNeverConsulted() {
        var reads = 0
        val limiter = SpeedLimiter(nanoTime = { reads++; 0L }, sleep = { reads++ })
        val file = File(tmp.root, "free.part")
        run(limiter, file)
        assertArrayEquals(payload, file.readBytes())
        assertEquals(0, reads)
    }

    @Test fun theCapCanChangeWhileDownloadsRun() {
        var nanos = 0L
        lateinit var limiter: SpeedLimiter
        limiter = SpeedLimiter(
            nanoTime = { nanos },
            // After the first second of (virtual) download the cap is raised tenfold, from inside the running download.
            sleep = { nanos += it; if (nanos >= 1_000_000_000L) limiter.bytesPerSecond = 5L * 1024 * 1024 },
        ).apply { bytesPerSecond = 512 * 1024 }
        val file = File(tmp.root, "var.part")
        run(limiter, file)
        assertArrayEquals(payload, file.readBytes())
        val seconds = nanos / 1e9
        // 1 s at 512 KB/s, the remaining 2.5 MB at 5 MB/s: about 1.5 s, far from the 6 s a constant 512 KB/s would take
        assertTrue("took $seconds s", seconds in 1.0..2.5)
    }
}
