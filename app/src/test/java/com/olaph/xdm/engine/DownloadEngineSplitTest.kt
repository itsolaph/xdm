package com.olaph.xdm.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dynamic segment splitting. Slow connections are simulated with requests the server holds back until the test lets
 * them go, so what gets split (and where) does not depend on how fast the machine is.
 */
class DownloadEngineSplitTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var http: StallingDispatcher
    private val counter = ConcurrencyCounter()
    private val client = OkHttpClient.Builder().addInterceptor(counter).build()

    @Before fun setUp() {
        http = StallingDispatcher(randomBytes(2 * MIB, seed = 3))
        server = MockWebServer().apply { dispatcher = http; start() }
    }

    @After fun tearDown() {
        http.releaseAll()
        server.shutdown()
    }

    // ---- test infrastructure ----

    /**
     * Serves [payload] with ranges. A GET whose range starts at an offset in [holds] is answered only once its latch
     * opens (the reader sees no byte until then). [ignoreEnd] answers every range up to EOF, like servers that only
     * honour the start of a range.
     */
    class StallingDispatcher(@Volatile var payload: ByteArray) : Dispatcher() {
        @Volatile var supportRanges = true
        @Volatile var ignoreEnd = false
        val holds = ConcurrentHashMap<Long, CountDownLatch>()
        /** Ranged GETs in arrival order, as "start-end" (end empty when open). */
        val ranges = CopyOnWriteArrayList<String>()
        /** Called after each ranged GET is recorded (before it is answered); [count] numbers them from 1. */
        @Volatile var onRange: (count: Int, start: Long) -> Unit = { _, _ -> }

        fun releaseAll() = holds.values.forEach { it.countDown() }

        override fun dispatch(request: RecordedRequest): MockResponse {
            val data = payload
            if (request.method == "HEAD") {
                return MockResponse().setHeader("Content-Length", data.size).apply {
                    if (supportRanges) setHeader("Accept-Ranges", "bytes")
                }
            }
            val range = request.getHeader("Range")
            if (!supportRanges || range == null) return MockResponse().setBody(Buffer().write(data))
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range) ?: return MockResponse().setResponseCode(416)
            val start = m.groupValues[1].toLong()
            if (range != "bytes=0-0") {
                val count = synchronized(ranges) {
                    ranges += "$start-${m.groupValues[2]}"
                    ranges.size
                }
                onRange(count, start)
                holds[start]?.await(30, TimeUnit.SECONDS)
            }
            val asked = m.groupValues[2].toLongOrNull()?.coerceAtMost(data.size - 1L) ?: (data.size - 1L)
            val end = if (ignoreEnd) data.size - 1L else asked
            val len = (end - start + 1).toInt()
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                .setBody(Buffer().write(data, start.toInt(), len))
        }
    }

    /** Counts requests that are open (from sending until the body is closed) and remembers the peak. */
    class ConcurrencyCounter : Interceptor {
        private val open = AtomicInteger()
        val peak = AtomicInteger()

        override fun intercept(chain: Interceptor.Chain): Response {
            peak.accumulateAndGet(open.incrementAndGet(), ::maxOf)
            val response = try {
                chain.proceed(chain.request())
            } catch (e: Throwable) {
                open.decrementAndGet()
                throw e
            }
            val body = response.body ?: return response.also { open.decrementAndGet() }
            val closed = AtomicBoolean()
            val source = object : ForwardingSource(body.source()) {
                override fun close() {
                    if (closed.compareAndSet(false, true)) open.decrementAndGet()
                    super.close()
                }
            }
            return response.newBuilder().body(source.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
        }
    }

    class MemoryStore : ProgressStore {
        @Volatile var probe: ProbeResult? = null
        @Volatile var segments: List<SegmentState> = emptyList()
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) {
            this.probe = probe; this.segments = segments
        }
        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) {
            this.segments = segments
        }
        fun saved() = probe!!.let { SavedState(it.contentLength, it.acceptRanges, it.etag, it.lastModified, segments) }
    }

    private fun randomBytes(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }

    private fun engine(minSplit: Long) = DownloadEngine(
        client, maxRetries = 2, backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40, minSplitBytes = minSplit,
    )

    private fun request(file: File, connections: Int, saved: SavedState? = null, maxSegmentBytes: Long = 0) =
        EngineRequest(1, server.url("/f.bin").toString(), "XDM-Test", file, connections, saved, maxSegmentBytes = maxSegmentBytes)

    private fun hold(start: Long) = CountDownLatch(1).also { http.holds[start] = it }

    private fun assertCovers(segments: List<SegmentState>) =
        assertTrue("segments cover the file exactly: $segments", SegmentPlanner.coversExactly(segments, http.payload.size.toLong()))

    // ---- tests ----

    @Test fun slowSegmentIsSplitAndTheRestIsFetchedByTheFreeConnection() = runBlocking {
        // Two halves of 1 MiB. The second one stalls before sending anything; the first is answered only once the
        // second has been asked for, so the stalled request carries its original range.
        val second = hold(MIB.toLong())
        val firstGate = hold(0)
        http.onRange = { n, _ ->
            if (n == 2) firstGate.countDown()
            if (n == 5) second.countDown() // three splits: then the stalled half has less than minSplit left
        }
        val file = File(tmp.root, "split.part")
        val store = MemoryStore()
        engine(minSplit = 256 * KIB.toLong()).download(request(file, connections = 2), store)

        assertArrayEquals(http.payload, file.readBytes())
        val ranges = http.ranges.toList()
        assertEquals(setOf("0-1048575", "1048576-2097151"), ranges.take(2).toSet())
        // Each split halves what the stalled request still owes; the free connection fetches the halves in turn.
        assertEquals(listOf("1572864-2097151", "1310720-1572863", "1179648-1310719"), ranges.drop(2))
        assertCovers(store.segments)
        assertTrue(store.segments.all { it.isComplete })
        // The slow connection only had to deliver 128 KiB of its 1 MiB; the free one fetched the rest.
        val slow = store.segments.single { it.index == 1 }
        assertEquals(MIB.toLong(), slow.start)
        assertEquals(1179647L, slow.end)
        assertEquals(5, store.segments.size)
    }

    @Test fun serverThatIgnoresTheEndOfTheRangeStillGivesAnExactFile() = runBlocking {
        http.ignoreEnd = true
        val second = hold(MIB.toLong())
        val firstGate = hold(0)
        http.onRange = { n, _ ->
            if (n == 2) firstGate.countDown()
            if (n == 3) second.countDown()
        }
        val file = File(tmp.root, "ignore.part")
        val store = MemoryStore()
        engine(minSplit = 256 * KIB.toLong()).download(request(file, connections = 2), store)

        assertArrayEquals(http.payload, file.readBytes())
        assertCovers(store.segments)
        assertTrue("was split: ${store.segments}", store.segments.size >= 3)
        assertTrue(store.segments.all { it.isComplete })
    }

    @Test fun pauseRightAfterASplitThenResumeGivesTheSameFile() = runBlocking {
        val second = hold(MIB.toLong())
        val firstGate = hold(0)
        val split = hold(MIB + MIB / 2L) // the new half stalls too, so the pause lands right after the split
        val splitAsked = CountDownLatch(1)
        http.onRange = { n, _ ->
            if (n == 2) firstGate.countDown()
            if (n == 3) splitAsked.countDown()
        }
        val file = File(tmp.root, "pause.part")
        val store = MemoryStore()
        val job = launch(Dispatchers.Default) { engine(minSplit = 256 * KIB.toLong()).download(request(file, connections = 2), store) }
        assertTrue("split request seen", withContext(Dispatchers.IO) { splitAsked.await(20, TimeUnit.SECONDS) })
        job.cancelAndJoin()

        val paused = store.segments
        assertCovers(paused)
        assertEquals(
            listOf(SegmentState(0, 0, MIB - 1L, MIB.toLong()), SegmentState(1, MIB.toLong(), MIB + MIB / 2L - 1, 0), SegmentState(2, MIB + MIB / 2L, 2L * MIB - 1, 0)),
            paused,
        )

        listOf(second, split).forEach { it.countDown() }
        http.holds.clear()
        http.ranges.clear()
        http.onRange = { _, _ -> }
        engine(minSplit = SegmentPlanner.MIN_DYNAMIC_SPLIT).download(request(file, connections = 2, saved = store.saved()), store)

        assertArrayEquals(http.payload, file.readBytes())
        assertEquals("only the two open halves were fetched", setOf("1048576-1572863", "1572864-2097151"), http.ranges.toSet())
        assertCovers(store.segments)
        assertTrue(store.segments.all { it.isComplete })
    }

    @Test fun neverMoreRequestsInFlightThanConnections() = runBlocking {
        http.payload = randomBytes(3 * MIB, seed = 4)
        val third = hold(2L * MIB)
        // Both free connections split the stalled third: at least two splits always happen.
        http.onRange = { n, _ -> if (n == 5) third.countDown() }
        val file = File(tmp.root, "peak.part")
        val store = MemoryStore()
        engine(minSplit = 128 * KIB.toLong()).download(request(file, connections = 3), store)

        assertArrayEquals(http.payload, file.readBytes())
        assertCovers(store.segments)
        assertTrue("was split: ${store.segments}", store.segments.size >= 5)
        assertTrue("peak ${counter.peak.get()}", counter.peak.get() <= 3)
    }

    @Test fun splittingKeepsWorkingWithCappedSegments() = runBlocking {
        // Capped like YouTube chunks: six segments of 512 KiB, two connections.
        http.payload = randomBytes(3 * MIB, seed = 5)
        val chunk = 512L * KIB
        val last = hold(5 * chunk)
        http.onRange = { _, start -> if (start % chunk != 0L) last.countDown() }
        val file = File(tmp.root, "capped.part")
        val store = MemoryStore()
        engine(minSplit = 64 * KIB.toLong()).download(request(file, connections = 2, maxSegmentBytes = chunk), store)

        assertArrayEquals(http.payload, file.readBytes())
        assertCovers(store.segments)
        assertTrue("was split: ${store.segments}", store.segments.size > 6)
        assertTrue(store.segments.all { it.length <= chunk })
        assertTrue("peak ${counter.peak.get()}", counter.peak.get() <= 2)
    }

    @Test fun noSplitWhenTheServerHasNoRanges() = runBlocking {
        http.supportRanges = false
        val file = File(tmp.root, "noranges.part")
        val store = MemoryStore()
        engine(minSplit = 1024).download(request(file, connections = 4), store)

        assertArrayEquals(http.payload, file.readBytes())
        assertEquals(1, store.segments.size)
        assertTrue(http.ranges.isEmpty())
        assertEquals(1, counter.peak.get())
    }

    @Test fun noSplitWhenTheSizeIsUnknown() = runBlocking {
        // Ranges work, but neither HEAD nor the range probe tells the size.
        val data = http.payload
        val gets = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "HEAD" -> MockResponse().setHeader("Accept-Ranges", "bytes").removeHeader("Content-Length")
                request.getHeader("Range") == "bytes=0-0" ->
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/*").setBody(Buffer().write(data, 0, 1))
                else -> MockResponse().setChunkedBody(Buffer().write(data), 8192).also { gets.incrementAndGet() }
            }
        }
        val file = File(tmp.root, "unknown.part")
        val store = MemoryStore()
        val result = engine(minSplit = 1024).download(request(file, connections = 4), store)

        assertEquals(data.size.toLong(), result.totalBytes)
        assertArrayEquals(data, file.readBytes())
        assertEquals(listOf(SegmentState(0, 0, -1, data.size.toLong())), store.segments)
        assertEquals(1, gets.get())
    }

    @Test fun singleConnectionNeverSplits() = runBlocking {
        val file = File(tmp.root, "one.part")
        val store = MemoryStore()
        engine(minSplit = 1024).download(request(file, connections = 1), store)

        assertArrayEquals(http.payload, file.readBytes())
        assertEquals(1, store.segments.size)
        assertEquals(1, counter.peak.get())
    }

    private companion object {
        const val KIB = 1024
        const val MIB = 1024 * 1024
    }
}
