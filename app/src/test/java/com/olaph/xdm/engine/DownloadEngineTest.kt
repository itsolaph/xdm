package com.olaph.xdm.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DownloadEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dispatcher: TestDispatcher
    private val client = OkHttpClient()

    @Before fun setUp() {
        dispatcher = TestDispatcher(randomBytes(5 * 1024 * 1024 + 123, seed = 1))
        server = MockWebServer().apply { dispatcher = this@DownloadEngineTest.dispatcher; start() }
    }

    @After fun tearDown() = server.shutdown()

    // ---- test infrastructure ----

    class TestDispatcher(@Volatile var payload: ByteArray) : Dispatcher() {
        @Volatile var supportRanges = true
        @Volatile var etag: String? = "\"v1\""
        @Volatile var throttleBytes = 0L
        @Volatile var contentDisposition: String? = null
        @Volatile var chunkedNoLength = false
        val failFirstGets = AtomicInteger(0) // answer this many GETs with 503
        @Volatile var errorCode = 0 // if set, every GET answers with this status
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val bytesServed = AtomicLong()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val data = payload
            val common = MockResponse().apply {
                etag?.let { setHeader("ETag", it) }
                contentDisposition?.let { setHeader("Content-Disposition", it) }
                setHeader("Content-Type", "application/octet-stream")
            }
            if (request.method == "HEAD") {
                if (errorCode != 0) return MockResponse().setResponseCode(errorCode)
                return common.apply {
                    if (!chunkedNoLength) setHeader("Content-Length", data.size)
                    if (supportRanges) setHeader("Accept-Ranges", "bytes")
                }
            }
            if (errorCode != 0) return MockResponse().setResponseCode(errorCode)
            val range = request.getHeader("Range")
            if (range != null && range != "bytes=0-0" && failFirstGets.get() > 0 && failFirstGets.getAndDecrement() > 0) {
                return MockResponse().setResponseCode(503)
            }
            if (!supportRanges || range == null) {
                bytesServed.addAndGet(data.size.toLong())
                return common.apply {
                    if (chunkedNoLength) setChunkedBody(Buffer().write(data), 8192) else setBody(Buffer().write(data))
                    throttle()
                }
            }
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range) ?: return MockResponse().setResponseCode(416)
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(data.size - 1L) ?: (data.size - 1L)
            if (start > end || start >= data.size) return MockResponse().setResponseCode(416)
            val len = (end - start + 1).toInt()
            bytesServed.addAndGet(len.toLong())
            return common.setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                .setBody(Buffer().write(data, start.toInt(), len))
                .apply { throttle() }
        }

        private fun MockResponse.throttle() {
            if (throttleBytes > 0) throttleBody(throttleBytes, 50, TimeUnit.MILLISECONDS)
        }

        fun rangeGets() = requests.filter { it.method == "GET" && it.getHeader("Range") != null && it.getHeader("Range") != "bytes=0-0" }
        fun dataGets() = requests.filter { it.method == "GET" && it.getHeader("Range") != "bytes=0-0" }
    }

    class MemoryStore : ProgressStore {
        @Volatile var probe: ProbeResult? = null
        @Volatile var segments: List<SegmentState> = emptyList()
        val saves = AtomicInteger()
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) {
            this.probe = probe; this.segments = segments
        }
        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) {
            this.segments = segments; saves.incrementAndGet()
        }
        fun saved() = probe!!.let {
            SavedState(it.contentLength, it.acceptRanges, it.etag, it.lastModified, segments)
        }
        fun downloaded() = segments.sumOf { it.downloaded }
    }

    private fun randomBytes(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").let { md ->
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().toHex()
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun engine(backoff: Long = 1) = DownloadEngine(
        client, maxRetries = 5, backoffMs = { backoff }, tickMs = 20, persistIntervalMs = 40,
    )

    private fun request(file: File, connections: Int = 8, saved: SavedState? = null) =
        EngineRequest(1, server.url("/files/test.bin").toString(), "XDM-Test", file, connections, saved)

    // ---- tests ----

    @Test fun downloadsWithEightSegmentsAndMatchingHash() = runBlocking {
        val file = File(tmp.root, "out.part")
        val store = MemoryStore()
        val result = engine().download(request(file), store)

        assertEquals(dispatcher.payload.size.toLong(), result.totalBytes)
        assertEquals(sha256(dispatcher.payload), sha256(file))
        assertEquals(8, store.segments.size)
        assertTrue(store.segments.all { it.isComplete })
        // 8 distinct ranged requests, one per segment
        val ranges = dispatcher.rangeGets().map { it.getHeader("Range") }
        assertEquals(8, ranges.size)
        assertEquals(8, ranges.toSet().size)
    }

    @Test fun reportsProgressMonotonically() = runBlocking {
        dispatcher.throttleBytes = 256 * 1024
        val seen = CopyOnWriteArrayList<Long>()
        engine().download(request(File(tmp.root, "p.part")), MemoryStore()) { seen += it.downloaded }
        assertTrue(seen.size >= 3)
        assertEquals(seen.sorted(), seen.toList())
        assertEquals(dispatcher.payload.size.toLong(), seen.last())
    }

    @Test fun pauseMidwayThenResumeKeepsHashAndSkipsDownloadedBytes() = runBlocking {
        dispatcher.throttleBytes = 64 * 1024 // ~0.5 s per 640 KB segment
        val file = File(tmp.root, "resume.part")
        val store = MemoryStore()
        val total = dispatcher.payload.size.toLong()
        val progress = AtomicLong()

        val job = launch {
            engine().download(request(file), store) { progress.set(it.downloaded) }
        }
        withTimeout(20_000) { while (progress.get() < total / 3) delay(10) }
        job.cancelAndJoin() // "pause"

        val partial = store.downloaded()
        assertTrue("some progress persisted ($partial)", partial > 0)
        assertTrue("not finished ($partial)", partial < total)

        dispatcher.throttleBytes = 0
        dispatcher.bytesServed.set(0)
        dispatcher.requests.clear()
        engine().download(request(file, saved = store.saved()), store)

        assertEquals(sha256(dispatcher.payload), sha256(file))
        assertTrue(store.segments.all { it.isComplete })
        val servedOnResume = dispatcher.bytesServed.get()
        assertTrue("resume fetched only the missing part ($servedOnResume of $total, had $partial)", servedOnResume <= total - partial + 1)
        assertTrue(servedOnResume < total)
    }

    @Test fun serverWithoutRangeSupportUsesSingleConnection() = runBlocking {
        dispatcher.supportRanges = false
        val file = File(tmp.root, "single.part")
        val store = MemoryStore()
        engine().download(request(file), store)

        assertEquals(sha256(dispatcher.payload), sha256(file))
        assertEquals(1, store.segments.size)
        assertEquals(false, store.probe!!.acceptRanges)
        assertEquals(false, store.probe!!.resumable)
        assertEquals(1, dispatcher.dataGets().size)
        assertTrue(dispatcher.dataGets().single().getHeader("Range") == null)
    }

    @Test fun unknownSizeStreamsToEnd() = runBlocking {
        dispatcher.supportRanges = false
        dispatcher.chunkedNoLength = true
        val file = File(tmp.root, "chunked.part")
        val result = engine().download(request(file), MemoryStore())
        assertEquals(dispatcher.payload.size.toLong(), result.totalBytes)
        assertEquals(sha256(dispatcher.payload), sha256(file))
    }

    @Test fun smallFileUsesOneSegment() = runBlocking {
        dispatcher.payload = randomBytes(300 * 1024, seed = 7)
        val file = File(tmp.root, "small.part")
        val store = MemoryStore()
        engine().download(request(file, connections = 16), store)
        assertEquals(1, store.segments.size)
        assertEquals(sha256(dispatcher.payload), sha256(file))
    }

    @Test fun changedEtagRestartsFromZero() = runBlocking {
        dispatcher.throttleBytes = 128 * 1024
        val file = File(tmp.root, "changed.part")
        val store = MemoryStore()
        val progress = AtomicLong()
        val job = launch { engine().download(request(file), store) { progress.set(it.downloaded) } }
        withTimeout(20_000) { while (progress.get() < dispatcher.payload.size / 4) delay(10) }
        job.cancelAndJoin()
        assertTrue(store.downloaded() > 0)

        // Same size, different content and ETag: resuming would corrupt the file.
        dispatcher.throttleBytes = 0
        dispatcher.payload = randomBytes(dispatcher.payload.size, seed = 99)
        dispatcher.etag = "\"v2\""
        engine().download(request(file, saved = store.saved()), store)

        assertEquals(sha256(dispatcher.payload), sha256(file))
    }

    @Test fun retriesServerErrorsPerSegment() = runBlocking {
        dispatcher.failFirstGets.set(5)
        val file = File(tmp.root, "retry.part")
        engine().download(request(file), MemoryStore())
        assertEquals(sha256(dispatcher.payload), sha256(file))
    }

    @Test fun givesUpAfterRepeatedServerErrors() = runBlocking {
        dispatcher.failFirstGets.set(1000)
        val file = File(tmp.root, "fail5xx.part")
        try {
            engine().download(request(file), MemoryStore())
            fail("expected failure")
        } catch (e: HttpStatusException) {
            assertEquals(503, e.httpCode)
        }
        // 1 try + 5 retries for each failing segment, not an endless loop
        assertTrue(dispatcher.rangeGets().size <= 8 * 6 + 8)
    }

    @Test fun clientErrorFailsImmediatelyWithoutRetry() = runBlocking {
        dispatcher.errorCode = 404
        try {
            engine().download(request(File(tmp.root, "404.part")), MemoryStore())
            fail("expected failure")
        } catch (e: FatalDownloadException) {
            assertEquals(404, e.httpCode)
        }
        assertEquals(2, dispatcher.requests.size) // HEAD + one GET probe, nothing more
    }

    @Test fun probeReadsFilenameFromContentDisposition() = runBlocking {
        dispatcher.contentDisposition = "attachment; filename=\"fallback.bin\"; filename*=UTF-8''r%C3%A9sum%C3%A9%20v2.zip"
        val probe = Prober(client).probe(server.url("/dl?id=3").toString(), "XDM-Test")
        assertEquals("résumé v2.zip", probe.fileName)
        assertEquals(dispatcher.payload.size.toLong(), probe.contentLength)
        assertTrue(probe.acceptRanges)
        assertEquals("\"v1\"", probe.etag)
    }

    @Test fun probeFallsBackToUrlName() = runBlocking {
        val probe = Prober(client).probe(server.url("/some/dir/archive.7z").toString(), "XDM-Test")
        assertEquals("archive.7z", probe.fileName)
    }

    @Test fun probeDetectsRangeSupportWithoutAcceptRangesHeader() = runBlocking {
        // HEAD says nothing about ranges, but `Range: bytes=0-0` answers 206.
        val noAdvert = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "HEAD") MockResponse().setHeader("Content-Length", 1000)
                else MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/1000").setBody("x")
        }
        server.dispatcher = noAdvert
        val probe = Prober(client).probe(server.url("/x").toString(), "XDM-Test")
        assertTrue(probe.acceptRanges)
        assertEquals(1000L, probe.contentLength)
    }

    @Test fun cancelStopsPromptlyEvenWhenServerStalls() = runBlocking {
        dispatcher.throttleBytes = 1024 // painfully slow: would take minutes
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            engine().download(request(File(tmp.root, "stall.part")), MemoryStore())
        }
        delay(500)
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("cancel took $ms ms", ms < 3_000)
        assertNotNull(job)
    }
}
