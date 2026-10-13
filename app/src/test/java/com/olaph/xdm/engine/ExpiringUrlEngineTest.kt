package com.olaph.xdm.engine

import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Signed URLs (YouTube's googlevideo) expire: the server starts answering 403/410. The engine asks the request's
 * `refreshUrl` for a new one and carries on with the bytes already on disk. The first path segment of a URL names
 * a "generation" (`/old/...`, `/new/...`); the server turns a generation into 403 after an exact number of answers, so
 * the expiry happens at a precise point and nothing depends on timing.
 */
class ExpiringUrlEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(700 * 1024 + 321).also { Random(11).nextBytes(it) }
    private val chunk = 100L * 1024

    /** Where the engine's segments start: equal parts, none larger than [chunk] (8 of them for this payload). */
    private val starts = SegmentPlanner.plan(payload.size.toLong(), 1, chunk).map { it.start }

    /** One range request that reached the server and the status it got. */
    data class Hit(val path: String, val start: Long, val end: Long, val status: Int)

    private class Server(val payload: ByteArray) : Dispatcher() {
        val hits = CopyOnWriteArrayList<Hit>()
        val heads = AtomicInteger()
        val noRange = AtomicInteger()

        /** Generation → number of answers it gives before it answers [expiredCode]. */
        val limits = ConcurrentHashMap<String, Int>()
        private val used = ConcurrentHashMap<String, AtomicInteger>()

        /** The answer (0-based) of a generation that is cut off half-way through its body. */
        @Volatile var cutOff: Pair<String, Int>? = null
        @Volatile var expiredCode = 403

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.substringBefore('?')
            if (request.method == "HEAD") { heads.incrementAndGet(); return MockResponse().setResponseCode(405) }
            val range = request.getHeader("Range")
            val m = range?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it) }
            if (m == null) { noRange.incrementAndGet(); return MockResponse().setResponseCode(200).setBody(Buffer().write(payload)) }
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(payload.size - 1L) ?: (payload.size - 1L)
            val generation = path.split('/')[1]
            val n = used.getOrPut(generation) { AtomicInteger() }.getAndIncrement()
            val limit = limits[generation]
            if (limit != null && n >= limit) {
                hits += Hit(path, start, end, expiredCode)
                return MockResponse().setResponseCode(expiredCode)
            }
            hits += Hit(path, start, end, 206)
            val response = MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                .setBody(Buffer().write(payload, start.toInt(), (end - start + 1).toInt()))
            return if (cutOff == generation to n) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else response
        }

        fun ok(generation: String) = hits.filter { it.path.startsWith("/$generation/") && it.status == 206 }
    }

    private lateinit var server: MockWebServer
    private lateinit var http: Server

    @Before fun setUp() {
        http = Server(payload)
        server = MockWebServer().apply { dispatcher = http; start() }
    }

    @After fun tearDown() = server.shutdown()

    private class Store : ProgressStore {
        @Volatile var segments: List<SegmentState> = emptyList()
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) { this.segments = segments }
        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) { this.segments = segments }
    }

    private fun engine() = DownloadEngine(OkHttpClient(), maxRetries = 3, backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40)

    private fun request(
        file: File, url: String, connections: Int = 1, refresh: (suspend (String) -> String)? = null,
        known: Long = payload.size.toLong(), saved: SavedState? = null,
    ) = EngineRequest(
        id = 1, url = url, userAgent = "test", file = file, connections = connections, saved = saved,
        maxSegmentBytes = chunk, knownLength = known, refreshUrl = refresh,
    )

    private fun url(path: String) = server.url(path).toString()

    // ---- the expired URL ----

    @Test fun anExpiredUrlIsReplacedAndTheBytesAlreadyWrittenAreKept() = runBlocking {
        http.limits["old"] = 3 // chunks 0, 1, 2 are served, the next request gets 403
        val stale = CopyOnWriteArrayList<String>()
        val file = File(tmp.root, "out.part")
        engine().download(
            request(file, url("/old/video"), refresh = { s -> stale += s; url("/new/video") }),
            Store(),
        )

        assertArrayEquals("the file is complete and identical", payload, file.readBytes())
        assertEquals("one renewal", 1, stale.size)
        assertTrue("the stale url is the old one: ${stale[0]}", stale[0].contains("/old/video"))

        assertEquals(starts.take(3), http.ok("old").map { it.start }.sorted())
        val fresh = http.ok("new")
        assertEquals("the new url starts exactly where the old one stopped", starts[3], fresh.minOf { it.start })
        assertEquals(
            "every byte was fetched exactly once",
            payload.size.toLong(), (http.ok("old") + fresh).sumOf { it.end - it.start + 1 },
        )
        assertEquals("403 was seen once", 1, http.hits.count { it.status == 403 })
    }

    @Test fun halfAChunkThatArrivedBeforeTheUrlExpiredIsNotFetchedAgain() = runBlocking {
        http.limits["old"] = 3
        http.cutOff = "old" to 2 // the third chunk is cut off in the middle of its body, then the url is dead
        val file = File(tmp.root, "out.part")
        engine().download(request(file, url("/old/video"), refresh = { url("/new/video") }), Store())

        assertArrayEquals(payload, file.readBytes())
        val firstFresh = http.ok("new").minOf { it.start }
        assertTrue("resumed inside the cut chunk, after the bytes that did arrive: $firstFresh", firstFresh > starts[2])
        assertTrue(firstFresh < starts[3])
    }

    @Test fun gone410IsTreatedLikeForbidden() = runBlocking {
        http.limits["old"] = 1
        http.expiredCode = 410
        val file = File(tmp.root, "out.part")
        engine().download(request(file, url("/old/v"), refresh = { url("/new/v") }), Store())
        assertArrayEquals(payload, file.readBytes())
        assertTrue(http.hits.any { it.status == 410 })
    }

    @Test fun parallelSegmentsAllSwitchToTheNewUrlWithOneRenewal() = runBlocking {
        http.limits["old"] = 2
        val calls = AtomicInteger()
        val file = File(tmp.root, "out.part")
        engine().download(
            request(file, url("/old/v"), connections = 4, refresh = { calls.incrementAndGet(); url("/new/v") }),
            Store(),
        )
        assertArrayEquals(payload, file.readBytes())
        assertEquals("segments failing together share one renewal", 1, calls.get())
    }

    @Test fun withoutARefreshHookA403StillFailsLikeBefore() = runBlocking {
        http.limits["old"] = 0
        try {
            engine().download(request(File(tmp.root, "x.part"), url("/old/v")), Store())
            fail("expected failure")
        } catch (e: FatalDownloadException) {
            assertEquals(403, e.httpCode)
        }
    }

    @Test fun aServerThatRefusesEveryUrlStopsAfterAFewRenewals() = runBlocking {
        http.limits["old"] = 0
        http.limits["new"] = 0
        val calls = AtomicInteger()
        try {
            engine().download(request(File(tmp.root, "x.part"), url("/old/v"), refresh = { calls.incrementAndGet(); url("/new/v") }), Store())
            fail("expected failure")
        } catch (e: FatalDownloadException) {
            assertEquals(403, e.httpCode)
        }
        assertEquals("gave up after three renewals that did not help", 3, calls.get())
    }

    @Test fun renewalsThatKeepTheTransferGoingAreNotCountedAgainstTheLimit() = runBlocking {
        // every URL lives for one chunk: eight chunks need seven renewals, far above the limit of three fruitless ones
        for (g in 0..8) http.limits["g$g"] = 1
        val calls = AtomicInteger()
        val file = File(tmp.root, "out.part")
        engine().download(request(file, url("/g0/v"), refresh = { url("/g${calls.incrementAndGet()}/v") }), Store())
        assertArrayEquals(payload, file.readBytes())
        assertEquals(7, calls.get())
    }

    // ---- chunking and the known length ----

    @Test fun theFileIsFetchedInCappedChunksWithNoProbeRequest() = runBlocking {
        val file = File(tmp.root, "out.part")
        val store = Store()
        engine().download(request(file, url("/new/v"), connections = 3), store)
        assertArrayEquals(payload, file.readBytes())

        assertEquals("no HEAD", 0, http.heads.get())
        assertEquals("no range-less request", 0, http.noRange.get())
        assertTrue("no probe for bytes=0-0", http.hits.none { it.start == 0L && it.end == 0L })
        val sizes = http.hits.map { it.end - it.start + 1 }
        assertTrue("no chunk above the cap: $sizes", sizes.all { it <= chunk })
        assertEquals("8 chunks for 700 KB at 100 KB", 8, http.hits.size)
        assertEquals(8, store.segments.size)
        assertTrue(store.segments.all { it.isComplete })
    }

    @Test fun neverMoreConnectionsThanRequested() = runBlocking {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                maxSeen.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                try {
                    return http.dispatch(request).setBodyDelay(20, TimeUnit.MILLISECONDS)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        val file = File(tmp.root, "out.part")
        engine().download(request(file, url("/new/v"), connections = 2), Store())
        assertArrayEquals(payload, file.readBytes())
        assertTrue("at most 2 requests were handled at a time, saw ${maxSeen.get()}", maxSeen.get() <= 2)
    }

    @Test fun anUnknownLengthIsProbedLikeBefore() = runBlocking {
        val file = File(tmp.root, "out.part")
        engine().download(request(file, url("/new/v"), known = -1), Store())
        assertArrayEquals(payload, file.readBytes())
        assertTrue("HEAD or a bytes=0-0 request happened", http.heads.get() > 0 || http.hits.any { it.start == 0L && it.end == 0L })
    }

    // ---- resuming from what is on disk ----

    @Test fun aSecondRunContinuesWithTheSavedSegmentsAndRefetchesNothing() = runBlocking {
        val file = File(tmp.root, "out.part")
        val store = Store()
        http.limits["old"] = 3
        try {
            engine().download(request(file, url("/old/v")), store) // no hook: dies with 403 after three chunks
            fail("expected failure")
        } catch (e: FatalDownloadException) {
            assertEquals(403, e.httpCode)
        }
        val saved = SavedState(payload.size.toLong(), true, null, null, store.segments)
        assertEquals("three chunks are on disk", 3, store.segments.count { it.isComplete })

        http.hits.clear()
        engine().download(request(file, url("/new/v"), saved = saved), Store())
        assertArrayEquals(payload, file.readBytes())
        assertEquals("only the missing chunks were requested", starts[3], http.ok("new").minOf { it.start })
        assertFalse(http.hits.any { it.start < starts[3] })
    }

    // ---- the planner's cap ----

    @Test fun plannerCapsTheSegmentSizeAndKeepsTheRangesContiguous() {
        val cap = 10L * 1024 * 1024
        for (size in listOf(1L, cap - 1, cap, cap + 1, 35L * 1024 * 1024, 100L * 1024 * 1024, 100L * 1024 * 1024 + 7, 3L * 1024 * 1024 * 1024)) {
            val segs = SegmentPlanner.plan(size, 8, cap)
            assertTrue("size $size: largest ${segs.maxOf { it.length }}", segs.all { it.length <= cap })
            assertEquals(0L, segs.first().start)
            assertEquals(size - 1, segs.last().end)
            segs.zipWithNext { a, b -> assertEquals(a.end + 1, b.start) }
        }
        assertEquals("small files keep the usual split", 1, SegmentPlanner.plan(512 * 1024, 8, cap).size)
        assertEquals("fewer segments than connections stay as before", 8, SegmentPlanner.plan(35L * 1024 * 1024, 8, cap).size)
        assertEquals(10, SegmentPlanner.plan(100L * 1024 * 1024, 8, cap).size)
        assertEquals(11, SegmentPlanner.plan(100L * 1024 * 1024 + 1, 8, cap).size)
        assertEquals("no cap: unchanged", 8, SegmentPlanner.plan(100L * 1024 * 1024, 8).size)
    }
}
