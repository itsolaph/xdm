package com.olaph.xdm.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * An expired link (403) stops a download half-way. The link is swapped for another one that serves the same bytes and the
 * download continues from the saved segments, without fetching the finished ranges again.
 */
class LinkSwapEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(640 * 1024 + 77).also { Random(3).nextBytes(it) }
    private val chunk = 64L * 1024

    private class Hit(val path: String, val start: Long, val end: Long, val status: Int)

    private class Server(val payload: ByteArray) : Dispatcher() {
        val hits = CopyOnWriteArrayList<Hit>()

        /** Path prefix -> number of range answers before it starts answering 403. */
        val limits = ConcurrentHashMap<String, Int>()
        private val served = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var etag: String? = "\"v1\""
        @Volatile var size: Int = payload.size

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.substringBefore('?')
            val prefix = path.split('/')[1]
            val base = MockResponse().apply { etag?.let { setHeader("ETag", it) } }
            if (request.method == "HEAD") {
                return base.setResponseCode(200).setHeader("Content-Length", size).setHeader("Accept-Ranges", "bytes")
            }
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                ?: return base.setResponseCode(200).setBody(Buffer().write(payload))
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(size - 1L) ?: (size - 1L)
            val n = served.getOrPut(prefix) { AtomicInteger() }.getAndIncrement()
            val limit = limits[prefix]
            if (limit != null && n >= limit) {
                hits += Hit(path, start, end, 403)
                return MockResponse().setResponseCode(403)
            }
            hits += Hit(path, start, end, 206)
            return base.setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/$size")
                .setBody(Buffer().write(payload, start.toInt(), (end - start + 1).toInt()))
        }

        fun ok(prefix: String) = hits.filter { it.path.startsWith("/$prefix/") && it.status == 206 }
    }

    private class Store : ProgressStore {
        @Volatile var probe: ProbeResult? = null
        @Volatile var segments: List<SegmentState> = emptyList()
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) { this.probe = probe; this.segments = segments }
        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) { this.segments = segments }
    }

    private lateinit var server: MockWebServer
    private lateinit var http: Server

    @Before fun setUp() {
        http = Server(payload)
        server = MockWebServer().apply { dispatcher = http; start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun engine() = DownloadEngine(OkHttpClient(), maxRetries = 2, backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40)

    private fun url(path: String) = server.url(path).toString()

    private fun request(file: File, url: String, saved: SavedState? = null) = EngineRequest(
        id = 1, url = url, userAgent = "test", file = file, connections = 1, saved = saved, maxSegmentBytes = chunk,
    )

    @Test fun aDownloadThatDiedOn403ContinuesOnAnotherLinkWithoutFetchingFinishedRangesAgain() = runBlocking {
        http.limits["old"] = 4
        val file = File(tmp.root, "out.part")
        val store = Store()
        try {
            engine().download(request(file, url("/old/file.bin")), store)
            fail("the expired link must stop the download")
        } catch (e: FatalDownloadException) {
            assertEquals(403, e.httpCode)
        }
        val done = store.segments.sumOf { it.downloaded }
        assertTrue("some bytes arrived before the link expired: $done", done in 1 until payload.size)
        val probe = store.probe!!

        // The manager would now swap the link and hand the saved segments back.
        val saved = SavedState(probe.contentLength, probe.acceptRanges, probe.etag, probe.lastModified, store.segments)
        engine().download(request(file, url("/new/file.bin"), saved), store)

        assertArrayEquals("byte-exact", payload, file.readBytes())
        assertEquals("the new link starts right after the bytes already on disk", done, http.ok("new").minOf { it.start })
        val fetchedOld = http.ok("old").sumOf { it.end - it.start + 1 }
        val fetchedNew = http.ok("new").sumOf { it.end - it.start + 1 }
        assertEquals("every byte fetched exactly once", payload.size.toLong(), fetchedOld + fetchedNew)
        assertTrue("no range of the old link was asked for again", http.ok("new").none { n -> http.ok("old").any { o -> o.start == n.start } })
    }

    @Test fun aLinkWhoseEtagChangedStartsOverInsteadOfStitching() = runBlocking {
        http.limits["old"] = 3
        val file = File(tmp.root, "out.part")
        val store = Store()
        runCatching { engine().download(request(file, url("/old/file.bin")), store) }
        val probe = store.probe!!
        val partial = store.segments.sumOf { it.downloaded }
        assertTrue(partial in 1 until payload.size)

        http.etag = "\"v2\""
        val saved = SavedState(probe.contentLength, probe.acceptRanges, probe.etag, probe.lastModified, store.segments)
        engine().download(request(file, url("/new/file.bin"), saved), store)

        assertArrayEquals(payload, file.readBytes())
        assertEquals("a changed ETag means the old bytes are not reused", 0L, http.ok("new").minOf { it.start })
    }

    @Test fun theSizeOfTheNewProbeDecidesWhetherItIsAccepted() = runBlocking {
        val probe = engine().prober.probe(url("/new/file.bin"), "test")
        assertEquals(payload.size.toLong(), probe.contentLength)
        assertEquals(LinkMatch.Verdict.Same, LinkMatch.decide(payload.size.toLong(), "\"v1\"", probe.contentLength, probe.etag))
        http.size = payload.size - 1
        val other = engine().prober.probe(url("/new/file.bin"), "test")
        assertEquals(
            LinkMatch.Verdict.Different(payload.size.toLong(), payload.size - 1L),
            LinkMatch.decide(payload.size.toLong(), "\"v1\"", other.contentLength, other.etag),
        )
    }
}
