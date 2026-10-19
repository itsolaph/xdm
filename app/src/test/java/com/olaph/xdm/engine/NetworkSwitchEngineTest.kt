package com.olaph.xdm.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Wi-Fi <-> mobile handoff leaves requests hanging on a dead connection. The server here answers the first range
 * request with nothing at all, the test then reports a network change, and the segment has to go on at once: no wait for
 * a timeout (the client's read timeout is a minute), no retry attempt used (the engine is allowed none), and the file on
 * the server is compared with the one the download started from before any byte is added.
 */
class NetworkSwitchEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(300 * 1024 + 5).also { Random(9).nextBytes(it) }

    private class Server(val payload: ByteArray) : Dispatcher() {
        val stallNext = AtomicBoolean()
        val stalled = CountDownLatch(1)
        val heads = AtomicInteger()
        val ranges = AtomicInteger()
        @Volatile var etag = "\"v1\""

        override fun dispatch(request: RecordedRequest): MockResponse {
            val base = MockResponse().setHeader("ETag", etag)
            if (request.method == "HEAD") {
                heads.incrementAndGet()
                return base.setResponseCode(200).setHeader("Content-Length", payload.size).setHeader("Accept-Ranges", "bytes")
            }
            if (stallNext.compareAndSet(true, false)) {
                stalled.countDown()
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            ranges.incrementAndGet()
            // A segment that spans the whole file is requested without a Range header.
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                ?: return base.setResponseCode(200).setBody(Buffer().write(payload))
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(payload.size - 1L) ?: (payload.size - 1L)
            return base.setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                .setBody(Buffer().write(payload, start.toInt(), (end - start + 1).toInt()))
        }
    }

    private class Store : ProgressStore {
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) = Unit
        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) = Unit
    }

    private lateinit var server: MockWebServer
    private lateinit var http: Server
    private val gateCalls = AtomicInteger()

    @Before fun setUp() {
        http = Server(payload)
        server = MockWebServer().apply { dispatcher = http; start() }
    }

    @After fun tearDown() = server.shutdown()

    /** No retries at all, and a read timeout that would fail the test long before it fired. */
    private fun engine() = DownloadEngine(
        OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
        maxRetries = 0, backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40,
        awaitNetwork = { gateCalls.incrementAndGet() },
    )

    private fun request(file: File) = EngineRequest(
        id = 1, url = server.url("/file.bin").toString(), userAgent = "test", file = file, connections = 1,
    )

    @Test fun aNetworkChangeUnsticksAHangingSegmentWithoutUsingARetry() = runBlocking {
        val file = File(tmp.root, "out.part")
        val engine = engine()
        http.stallNext.set(true)
        val job = async { engine.download(request(file), Store()) }
        assertTrue(withContext(Dispatchers.IO) { http.stalled.await(15, TimeUnit.SECONDS) })

        engine.interruptConnections()
        withTimeout(20_000) { job.await() }

        assertArrayEquals(payload, file.readBytes())
        assertEquals("the retried request was served", 1, http.ranges.get())
        assertEquals("the network gate was consulted before the retry", 1, gateCalls.get())
    }

    @Test fun theFileIsComparedWithTheOriginalBeforeTheSegmentGoesOn() = runBlocking {
        val file = File(tmp.root, "out.part")
        val engine = engine()
        http.stallNext.set(true)
        val job = async { engine.download(request(file), Store()) }
        assertTrue(withContext(Dispatchers.IO) { http.stalled.await(15, TimeUnit.SECONDS) })

        engine.interruptConnections()
        withTimeout(20_000) { job.await() }

        assertEquals("one probe at the start, one after the network change", 2, http.heads.get())
    }

    @Test fun aFileThatChangedWhileTheNetworkWasAwayIsNeverStitched() = runBlocking {
        val file = File(tmp.root, "out.part")
        val engine = engine()
        http.stallNext.set(true)
        // Its own supervisor: the failure is the expected outcome and must not take the test down with it.
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).async { engine.download(request(file), Store()) }
        assertTrue(withContext(Dispatchers.IO) { http.stalled.await(15, TimeUnit.SECONDS) })

        http.etag = "\"v2\""
        engine.interruptConnections()
        try {
            withTimeout(20_000) { job.await() }
            fail("a changed ETag must stop the download")
        } catch (e: FileChangedException) {
            assertEquals("no range was fetched from the changed file", 0, http.ranges.get())
        }
    }

    @Test fun anInterruptWithNothingRunningDoesNothing() = runBlocking {
        val file = File(tmp.root, "out.part")
        val engine = engine()
        engine.interruptConnections()
        engine.download(request(file), Store())
        assertArrayEquals(payload, file.readBytes())
        assertEquals(0, gateCalls.get())
    }
}
