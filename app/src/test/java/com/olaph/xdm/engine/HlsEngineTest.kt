package com.olaph.xdm.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HlsEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    /** When set, every request must carry this Cookie and Referer or it gets a 403. */
    @Volatile private var requireAuth = false

    @Volatile private var segmentDelayMs = 0L
    private val failOnce = ConcurrentHashMap.newKeySet<String>()

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    seen += request
                    val path = request.path!!.substringBefore('?')
                    hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                    if (requireAuth && (request.getHeader("Cookie") != "sid=1" || request.getHeader("Referer") != "https://page.test/watch")) {
                        return MockResponse().setResponseCode(403)
                    }
                    if (failOnce.remove(path)) return MockResponse().setResponseCode(503)
                    val route = routes[path] ?: return MockResponse().setResponseCode(404)
                    return route().also { if (path.endsWith(".ts") || path.endsWith(".m4s")) it.setBodyDelay(segmentDelayMs, TimeUnit.MILLISECONDS) }
                }
            }
            start()
        }
    }

    @After fun tearDown() = server.shutdown()

    // ---- helpers ----

    private fun base(path: String) = server.url(path).toString()
    private fun bytes(n: Int, seed: Long) = ByteArray(n).also { Random(seed).nextBytes(it) }
    private fun engine() = HlsEngine(OkHttpClient(), maxRetries = 3, backoffMs = { 1 }, tickMs = 10)
    private fun body(data: ByteArray) = MockResponse().setBody(Buffer().write(data))
    private fun text(s: String) = MockResponse().setBody(s).setHeader("Content-Type", "application/vnd.apple.mpegurl")
    private fun hits(path: String) = hits[path]?.get() ?: 0

    private fun request(
        playlist: String,
        name: String = "run",
        concurrency: Int = 4,
        headers: Map<String, String> = emptyMap(),
    ) = HlsRequest(
        id = 1,
        playlistUrl = base(playlist),
        userAgent = "test-agent",
        workDir = File(tmp.root, "$name-work"),
        output = File(tmp.root, "$name.out"),
        concurrency = concurrency,
        headers = headers,
    )

    /** Registers `/seg<i>.ts` routes and returns the media playlist text. */
    private fun plainStream(count: Int, size: Int = 30_000, dir: String = ""): Pair<List<ByteArray>, String> {
        val segs = (0 until count).map { bytes(size + it * 7, seed = 100L + it) }
        segs.forEachIndexed { i, b -> routes["$dir/seg$i.ts"] = { body(b) } }
        val pl = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n")
            for (i in 0 until count) append("#EXTINF:4.0,\nseg$i.ts\n")
            append("#EXT-X-ENDLIST\n")
        }
        return segs to pl
    }

    private fun concat(parts: List<ByteArray>) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private fun encrypt(plain: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(plain)
        }

    private fun seqIv(seq: Long) = ByteArray(16).also { for (i in 0 until 8) it[15 - i] = (seq ushr (8 * i)).toByte() }

    // ---- tests ----

    @Test fun masterPlaylistPicksBestVariantAndOutputIsTheConcatenatedTs() = runBlocking {
        val (segs, pl) = plainStream(20, dir = "/hi")
        routes["/hi/index.m3u8"] = { text(pl) }
        routes["/lo/index.m3u8"] = { text("#EXTM3U\n#EXTINF:4,\nnever.ts\n#EXT-X-ENDLIST\n") }
        routes["/master.m3u8"] = {
            text(
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
                lo/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001f,mp4a.40.2"
                hi/index.m3u8
                """.trimIndent(),
            )
        }
        val progress = CopyOnWriteArrayList<HlsProgress>()
        val req = request("/master.m3u8")
        val result = engine().download(req) { progress += it }

        assertArrayEquals(concat(segs), req.output.readBytes())
        assertEquals("ts", result.extension)
        assertEquals("video/mp2t", result.mimeType)
        assertEquals(20, result.segments)
        assertEquals(req.output.length(), result.totalBytes)
        assertEquals(0, hits("/lo/index.m3u8"))
        assertEquals(0, hits("/lo/never.ts"))
        for (i in 0 until 20) assertEquals("segment $i fetched once", 1, hits("/hi/seg$i.ts"))
        val last = progress.last()
        assertEquals(20, last.segmentsDone)
        assertEquals(20, last.segmentsTotal)
        assertTrue(progress.all { it.segmentsDone <= it.segmentsTotal })
        assertTrue("progress is monotonic", progress.zipWithNext().all { (a, b) -> a.segmentsDone <= b.segmentsDone })
        // every request carried the stored User-Agent
        assertTrue(seen.all { it.getHeader("User-Agent") == "test-agent" })
    }

    @Test fun aes128WithIvFromMediaSequenceDecryptsToPlaintext() = runBlocking {
        val key = bytes(16, 7)
        val plain = (0 until 8).map { bytes(20_000 + it * 13, seed = 300L + it) }
        val firstSeq = 5L
        routes["/key.bin"] = { body(key) }
        plain.forEachIndexed { i, p -> routes["/e$i.ts"] = { body(encrypt(p, key, seqIv(firstSeq + i))) } }
        val pl = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:$firstSeq\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n")
            for (i in plain.indices) append("#EXTINF:4,\ne$i.ts\n")
            append("#EXT-X-ENDLIST\n")
        }
        routes["/index.m3u8"] = { text(pl) }

        val req = request("/index.m3u8")
        engine().download(req)

        assertArrayEquals(concat(plain), req.output.readBytes())
        assertEquals("the key is downloaded once", 1, hits("/key.bin"))
    }

    @Test fun aes128WithExplicitIvAndKeyRotation() = runBlocking {
        val keyA = bytes(16, 1)
        val keyB = bytes(16, 2)
        val ivA = bytes(16, 3)
        val ivB = bytes(16, 4)
        routes["/ka.bin"] = { body(keyA) }
        routes["/kb.bin"] = { body(keyB) }
        val plain = (0 until 10).map { bytes(10_000 + it, seed = 500L + it) }
        plain.forEachIndexed { i, p ->
            val enc = if (i < 5) encrypt(p, keyA, ivA) else encrypt(p, keyB, ivB)
            routes["/r$i.ts"] = { body(enc) }
        }
        fun hex(b: ByteArray) = "0x" + b.joinToString("") { "%02X".format(it) }
        val pl = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
            for (i in plain.indices) {
                if (i == 0) append("#EXT-X-KEY:METHOD=AES-128,URI=\"ka.bin\",IV=${hex(ivA)}\n")
                if (i == 5) append("#EXT-X-KEY:METHOD=AES-128,URI=\"kb.bin\",IV=${hex(ivB)}\n")
                append("#EXTINF:4,\nr$i.ts\n")
            }
            append("#EXT-X-ENDLIST\n")
        }
        routes["/index.m3u8"] = { text(pl) }

        val req = request("/index.m3u8")
        engine().download(req)

        assertArrayEquals(concat(plain), req.output.readBytes())
        assertEquals(1, hits("/ka.bin"))
        assertEquals(1, hits("/kb.bin"))
    }

    @Test fun wrongKeyFailsInsteadOfWritingGarbage() = runBlocking {
        val key = bytes(16, 9)
        routes["/key.bin"] = { body(bytes(16, 10)) } // not the key the segment was encrypted with
        routes["/s0.ts"] = { body(encrypt(bytes(5_000, 11), key, seqIv(0))) }
        routes["/index.m3u8"] = {
            text("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\ns0.ts\n#EXT-X-ENDLIST\n")
        }
        try {
            engine().download(request("/index.m3u8"))
            fail("expected a decryption failure")
        } catch (e: FatalDownloadException) {
            assertTrue(e.message!!, e.message!!.contains("decrypt", ignoreCase = true))
        }
    }

    @Test fun fmp4WithInitSegmentBecomesMp4() = runBlocking {
        val init = bytes(700, 21)
        val frags = (0 until 6).map { bytes(15_000 + it, seed = 600L + it) }
        routes["/init.mp4"] = { body(init) }
        frags.forEachIndexed { i, f -> routes["/f$i.m4s"] = { body(f) } }
        val pl = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MAP:URI=\"init.mp4\"\n")
            for (i in frags.indices) append("#EXTINF:4,\nf$i.m4s\n")
            append("#EXT-X-ENDLIST\n")
        }
        routes["/index.m3u8"] = { text(pl) }

        val req = request("/index.m3u8")
        val result = engine().download(req)

        assertEquals("mp4", result.extension)
        assertEquals("video/mp4", result.mimeType)
        assertArrayEquals(init + concat(frags), req.output.readBytes())
        assertEquals("init section fetched once, not once per segment", 1, hits("/init.mp4"))
        assertEquals(6, result.segments)
    }

    @Test fun byteRangeSegmentsOfOneFileAreFetchedWithRangeHeaders() = runBlocking {
        val file = bytes(9_000, 31)
        // an origin that honours Range
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path!!.substringBefore('?')
                if (path == "/index.m3u8") {
                    return text(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" +
                            "#EXTINF:4,\n#EXT-X-BYTERANGE:3000@0\nall.ts\n" +
                            "#EXTINF:4,\n#EXT-X-BYTERANGE:3000\nall.ts\n" +
                            "#EXTINF:4,\n#EXT-X-BYTERANGE:3000\nall.ts\n#EXT-X-ENDLIST\n",
                    )
                }
                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range").orEmpty())
                    ?: return MockResponse().setResponseCode(416)
                val (s, e) = range.destructured
                return MockResponse().setResponseCode(206).setBody(Buffer().write(file, s.toInt(), e.toInt() - s.toInt() + 1))
            }
        }
        val req = request("/index.m3u8")
        engine().download(req)
        assertArrayEquals(file, req.output.readBytes())
        val ranges = seen.mapNotNull { it.getHeader("Range") }.sorted()
        assertEquals(listOf("bytes=0-2999", "bytes=3000-5999", "bytes=6000-8999"), ranges)
    }

    @Test fun serverIgnoringRangeIsHandledBySlicingTheBody() = runBlocking {
        val file = bytes(6_000, 32)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                if (path == "/index.m3u8") {
                    return text(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" +
                            "#EXTINF:4,\n#EXT-X-BYTERANGE:2000@1000\nall.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:2000\nall.ts\n#EXT-X-ENDLIST\n",
                    )
                }
                return MockResponse().setBody(Buffer().write(file)) // 200 with the whole file
            }
        }
        val req = request("/index.m3u8")
        engine().download(req)
        assertArrayEquals(file.copyOfRange(1000, 5000), req.output.readBytes())
    }

    @Test fun pauseAndResumeContinuesWithTheSegmentsAlreadyOnDisk() = runBlocking {
        segmentDelayMs = 80
        val (segs, pl) = plainStream(24, size = 20_000)
        routes["/index.m3u8"] = { text(pl) }
        val req = request("/index.m3u8", concurrency = 2)

        val doneAtPause = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            engine().download(req) { doneAtPause.set(it.segmentsDone) }
        }
        withTimeout(20_000) { while (doneAtPause.get() < 6) delay(10) }
        job.cancelAndJoin() // = pause

        val onDisk = req.workDir.listFiles { f -> f.name.endsWith(".seg") }!!.map { it.name }.sorted()
        assertTrue("paused mid-way: ${onDisk.size}", onDisk.size in 6 until 24)
        assertTrue("half-written segments are removed", req.workDir.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
        val hitsBefore = (0 until 24).associateWith { hits("/seg$it.ts") }

        // resume with a fresh engine instance (as after an app restart)
        segmentDelayMs = 0
        val first = CopyOnWriteArrayList<HlsProgress>()
        engine().download(req) { first += it }

        assertArrayEquals(concat(segs), req.output.readBytes())
        assertTrue("resume starts from the stored count", first.first().segmentsDone >= onDisk.size)
        for (name in onDisk) {
            val i = name.removeSuffix(".seg").toInt()
            assertEquals("finished segment $i is not fetched again", hitsBefore.getValue(i), hits("/seg$i.ts"))
        }
    }

    @Test fun resumeWorksAfterTheProcessDiedLeavingATmpFile() = runBlocking {
        val (segs, pl) = plainStream(6)
        routes["/index.m3u8"] = { text(pl) }
        val req = request("/index.m3u8")
        engine().download(req)
        // simulate a crash during a later run: assembled output gone, one segment half written, one missing
        req.output.delete()
        File(req.workDir, "000003.seg").delete()
        File(req.workDir, "000003.seg.tmp").writeBytes(ByteArray(10))
        val before = hits("/seg3.ts")

        engine().download(req)

        assertArrayEquals(concat(segs), req.output.readBytes())
        assertEquals(before + 1, hits("/seg3.ts"))
        assertEquals("the others are reused", 1, hits("/seg2.ts"))
    }

    @Test fun staleWorkDirOfADifferentPlaylistIsDiscarded() = runBlocking {
        val (segs, pl) = plainStream(4)
        routes["/index.m3u8"] = { text(pl) }
        val req = request("/index.m3u8")
        req.workDir.mkdirs()
        File(req.workDir, "plan.txt").writeText("segments=4\nsha256=deadbeef\n")
        File(req.workDir, "000000.seg").writeBytes(ByteArray(100) { 1 })

        engine().download(req)

        assertArrayEquals(concat(segs), req.output.readBytes())
    }

    @Test fun queryStringChangesDoNotInvalidateTheResumeState() = runBlocking {
        val (segs, _) = plainStream(3)
        fun playlist(token: String) = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
            for (i in 0 until 3) append("#EXTINF:4,\nseg$i.ts?token=$token\n")
            append("#EXT-X-ENDLIST\n")
        }
        routes["/index.m3u8"] = { text(playlist("one")) }
        val req = request("/index.m3u8")
        engine().download(req)
        routes["/index.m3u8"] = { text(playlist("two")) }
        engine().download(req)
        assertArrayEquals(concat(segs), req.output.readBytes())
        for (i in 0 until 3) assertEquals("segment $i reused despite the new token", 1, hits("/seg$i.ts"))
    }

    @Test fun cookieAndRefererAreRequiredAndSentOnEveryRequest() = runBlocking {
        val (segs, pl) = plainStream(5)
        routes["/index.m3u8"] = { text(pl) }
        requireAuth = true

        try {
            engine().download(request("/index.m3u8", name = "noauth"))
            fail("expected 403")
        } catch (e: FatalDownloadException) {
            assertEquals(403, e.httpCode)
        }

        val headers = mapOf("Cookie" to "sid=1", "Referer" to "https://page.test/watch", "Origin" to "https://page.test")
        val req = request("/index.m3u8", name = "auth", headers = headers)
        seen.clear()
        engine().download(req)
        assertArrayEquals(concat(segs), req.output.readBytes())
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.getHeader("Cookie") == "sid=1" && it.getHeader("Origin") == "https://page.test" })
    }

    @Test fun cookieIsNotSentToAnUnrelatedSegmentHost() = runBlocking {
        val seg = bytes(4_000, 41)
        routes["/s0.ts"] = { body(seg) }
        // playlist is fetched via "localhost", the segment lives on "127.0.0.1" (a different host)
        val segUrl = server.url("/s0.ts").newBuilder().host("127.0.0.1").build().toString()
        routes["/index.m3u8"] = { text("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\n$segUrl\n#EXT-X-ENDLIST\n") }
        val playlistUrl = server.url("/index.m3u8").newBuilder().host("localhost").build().toString()
        val req = HlsRequest(1, playlistUrl, "ua", File(tmp.root, "w"), File(tmp.root, "o"), 2, mapOf("Cookie" to "sid=1", "Referer" to "https://page.test/"))
        engine().download(req)

        val playlistReq = seen.first { it.path!!.startsWith("/index.m3u8") }
        val segmentReq = seen.first { it.path!!.startsWith("/s0.ts") }
        assertEquals("sid=1", playlistReq.getHeader("Cookie"))
        assertNull("cookies stay with the host they were captured for", segmentReq.getHeader("Cookie"))
        assertEquals("other headers still travel", "https://page.test/", segmentReq.getHeader("Referer"))
    }

    @Test fun liveStreamsAreRejected() = runBlocking {
        routes["/s0.ts"] = { body(bytes(100, 1)) }
        routes["/live.m3u8"] = { text("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:4,\ns0.ts\n") }
        try {
            engine().download(request("/live.m3u8"))
            fail("expected a live-stream error")
        } catch (e: FatalDownloadException) {
            assertTrue(e.message!!, e.message!!.contains("Live stream"))
        }
        assertEquals("nothing was downloaded", 0, hits("/s0.ts"))
    }

    @Test fun drmAndSampleAesAreRejected() = runBlocking {
        routes["/drm.m3u8"] = {
            text("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST\n")
        }
        try {
            engine().download(request("/drm.m3u8"))
            fail("expected a DRM error")
        } catch (e: FatalDownloadException) {
            assertTrue(e.message!!, e.message!!.contains("DRM"))
        }
    }

    @Test fun transientServerErrorOnASegmentIsRetried() = runBlocking {
        val (segs, pl) = plainStream(5)
        routes["/index.m3u8"] = { text(pl) }
        failOnce += "/seg2.ts"
        val req = request("/index.m3u8")
        engine().download(req)
        assertArrayEquals(concat(segs), req.output.readBytes())
        assertEquals(2, hits("/seg2.ts"))
    }

    @Test fun permanentSegmentErrorFailsTheDownloadAndKeepsFinishedSegments() = runBlocking {
        val (_, pl) = plainStream(6)
        routes.remove("/seg4.ts") // 404
        routes["/index.m3u8"] = { text(pl) }
        val req = request("/index.m3u8", concurrency = 1)
        try {
            engine().download(req)
            fail("expected failure")
        } catch (e: FatalDownloadException) {
            assertEquals(404, e.httpCode)
        }
        // segments 0..3 were finished before the failure and are kept for a retry
        assertTrue(File(req.workDir, "000003.seg").exists())
        assertFalse(req.output.exists())
    }

    @Test fun cancellationLeavesNoHalfWrittenFiles() = runBlocking {
        segmentDelayMs = 300
        val (_, pl) = plainStream(8)
        routes["/index.m3u8"] = { text(pl) }
        val req = request("/index.m3u8", concurrency = 4)
        val job = launch(Dispatchers.Default) { engine().download(req) }
        withTimeout(10_000) { while (seen.count { it.path!!.contains("seg") } < 2) delay(10) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(req.workDir.listFiles { f -> f.name.endsWith(".tmp") }!!.isEmpty())
        req.workDir.listFiles { f -> f.name.endsWith(".seg") }!!.forEach { assertTrue(it.length() > 0) }
    }

    // ---- speed limit ----

    @Test fun theSharedSpeedLimitAppliesToSegmentDownloads() = runBlocking {
        val (segs, pl) = plainStream(5, size = 100_000)
        routes["/index.m3u8"] = { text(pl) }
        // A clock that only moves when the limiter sleeps: the time the limit costs is exact, and nothing really waits.
        var nanos = 0L
        val limiter = SpeedLimiter(nanoTime = { nanos }, sleep = { nanos += it }).apply { bytesPerSecond = 100_000 }
        val req = request("/index.m3u8", concurrency = 1)
        HlsEngine(OkHttpClient(), maxRetries = 3, backoffMs = { 1 }, tickMs = 10, limiter = limiter).download(req)

        assertArrayEquals(concat(segs), req.output.readBytes())
        val bytes = segs.sumOf { it.size }
        // bytes / rate seconds, minus the quarter second the limiter lets through at once
        val seconds = nanos / 1e9
        assertTrue("limited run took $seconds s", seconds in (bytes / 100_000.0 - 0.3)..(bytes / 100_000.0))
    }

    @Test fun aNetworkChangeRestartsHangingSegmentsWithoutCostingAnAttempt() = runBlocking {
        val (segs, pl) = plainStream(3)
        routes["/index.m3u8"] = { text(pl) }
        val stalls = java.util.concurrent.CountDownLatch(1)
        val stallOnce = java.util.concurrent.atomic.AtomicBoolean(true)
        routes["/seg1.ts"] = {
            if (stallOnce.compareAndSet(true, false)) {
                stalls.countDown()
                MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
            } else {
                body(segs[1])
            }
        }
        val engine = HlsEngine(
            OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
            maxRetries = 0, backoffMs = { 1 }, tickMs = 10,
        )
        val req = request("/index.m3u8", concurrency = 1)
        val job = async(Dispatchers.Default) { engine.download(req) }
        withContext(Dispatchers.IO) { assertTrue(stalls.await(15, TimeUnit.SECONDS)) }
        engine.interruptConnections()
        withTimeout(20_000) { job.await() }
        assertArrayEquals(concat(segs), req.output.readBytes())
    }
}
