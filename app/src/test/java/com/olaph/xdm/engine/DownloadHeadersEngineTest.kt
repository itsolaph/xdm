package com.olaph.xdm.engine

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A "protected" file server: it answers 403 unless the request carries the browser's Cookie and
 * Referer. Proves that the probe and every segment request forward the stored headers, also after a
 * pause and a simulated app restart (headers come back from their stored string form).
 */
class DownloadHeadersEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cookie = "session=s3cr3t; theme=dark"
    private val referer = "https://files.example.test/listing"
    private val browserUa = "Mozilla/5.0 (Linux; Android 14) Browser/1.0"
    private val headers = mapOf("Cookie" to cookie, "Referer" to referer, "User-Agent" to browserUa)

    private val payload = ByteArray(4 * 1024 * 1024 + 7).also { Random(42).nextBytes(it) }
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val forbidden = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var throttleBytes = 0L
    private val client = OkHttpClient()

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (request.getHeader("Cookie") != cookie || request.getHeader("Referer") != referer) {
                        forbidden += request
                        return MockResponse().setResponseCode(403)
                    }
                    val base = MockResponse().setHeader("Content-Type", "application/octet-stream").setHeader("ETag", "\"v1\"")
                    if (request.method == "HEAD") {
                        return base.setHeader("Content-Length", payload.size).setHeader("Accept-Ranges", "bytes")
                    }
                    val range = request.getHeader("Range")
                        ?: return base.setBody(Buffer().write(payload)).apply { throttle() }
                    val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range) ?: return MockResponse().setResponseCode(416)
                    val start = m.groupValues[1].toInt()
                    val end = m.groupValues[2].toIntOrNull()?.coerceAtMost(payload.size - 1) ?: (payload.size - 1)
                    if (start > end) return MockResponse().setResponseCode(416)
                    return base.setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .setBody(Buffer().write(payload, start, end - start + 1))
                        .apply { throttle() }
                }

                private fun MockResponse.throttle() {
                    if (throttleBytes > 0) throttleBody(throttleBytes, 50, TimeUnit.MILLISECONDS)
                }
            }
            start()
        }
    }

    @After fun tearDown() = server.shutdown()

    private class Store : ProgressStore {
        @Volatile var probe: ProbeResult? = null
        @Volatile var segments: List<SegmentState> = emptyList()
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) {
            this.probe = probe; this.segments = segments
        }
        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) { this.segments = segments }
        fun saved() = probe!!.let { SavedState(it.contentLength, it.acceptRanges, it.etag, it.lastModified, segments) }
    }

    private fun engine() = DownloadEngine(client, maxRetries = 2, backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40)

    private fun request(file: File, hdrs: Map<String, String>, saved: SavedState? = null) =
        EngineRequest(1, server.url("/secret/file.bin").toString(), "XDM-Default-UA", file, 4, saved, hdrs)

    private fun assertAllCarryHeaders(list: List<RecordedRequest>) {
        assertTrue("expected requests", list.isNotEmpty())
        for (r in list) {
            assertEquals("Cookie on ${r.method} ${r.getHeader("Range")}", cookie, r.getHeader("Cookie"))
            assertEquals("Referer on ${r.method} ${r.getHeader("Range")}", referer, r.getHeader("Referer"))
            assertEquals("browser UA overrides the engine default", browserUa, r.getHeader("User-Agent"))
        }
    }

    @Test fun withoutHeadersTheServerRefusesAndTheDownloadFailsFast() = runBlocking {
        try {
            engine().download(request(File(tmp.root, "a.part"), emptyMap()), Store())
            fail("expected a 403")
        } catch (e: FatalDownloadException) {
            assertEquals(403, e.httpCode)
        }
    }

    @Test fun probeAndEverySegmentRequestCarryTheHeaders() = runBlocking {
        val file = File(tmp.root, "b.part")
        val store = Store()
        engine().download(request(file, headers), store)

        assertTrue("no request was refused", forbidden.isEmpty())
        assertEquals(payload.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(payload))
        assertAllCarryHeaders(requests.toList())
        // HEAD probe + 4 segment GETs
        assertEquals(1, requests.count { it.method == "HEAD" })
        assertEquals(4, requests.count { it.method == "GET" && it.getHeader("Range") != null })
    }

    @Test fun headersSurvivePauseResumeAndRestart() = runBlocking {
        throttleBytes = 64 * 1024
        val file = File(tmp.root, "c.part")
        val store = Store()
        val progress = AtomicLong()
        val total = payload.size.toLong()

        val job = launch { engine().download(request(file, headers), store) { progress.set(it.downloaded) } }
        withTimeout(20_000) { while (progress.get() < total / 3) delay(10) }
        job.cancelAndJoin() // pause
        val partial = store.segments.sumOf { it.downloaded }
        assertTrue("partial progress ($partial)", partial in 1 until total)

        // "App restart": the only thing that survives is the stored string form of the headers.
        val persisted = HeaderCodec.encode(headers)
        val restored = HeaderCodec.decode(persisted)

        throttleBytes = 0
        requests.clear()
        engine().download(request(file, restored, store.saved()), store)

        assertTrue("no request was refused", forbidden.isEmpty())
        assertTrue(file.readBytes().contentEquals(payload))
        assertAllCarryHeaders(requests.toList())
        assertTrue("resume issued ranged GETs", requests.any { it.method == "GET" && it.getHeader("Range") != null })
    }

    @Test fun pageSuppliedRangeHeaderCannotBreakSegmentation() = runBlocking {
        val file = File(tmp.root, "d.part")
        engine().download(request(file, headers + mapOf("Range" to "bytes=0-0", "Accept-Encoding" to "gzip")), Store())
        assertTrue(file.readBytes().contentEquals(payload))
        assertTrue(requests.none { it.getHeader("Accept-Encoding") == "gzip" })
    }
}
