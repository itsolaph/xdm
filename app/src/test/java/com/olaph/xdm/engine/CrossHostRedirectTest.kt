package com.olaph.xdm.engine

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList

/** The stored Cookie must not follow a redirect to another host; Referer and User-Agent still do. */
class CrossHostRedirectTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = ByteArray(2 * 1024 * 1024 + 3).also { Random(9).nextBytes(it) }
    private lateinit var origin: MockWebServer
    private lateinit var cdn: MockWebServer
    private val originRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val cdnRequests = CopyOnWriteArrayList<RecordedRequest>()

    @Before fun setUp() {
        cdn = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    cdnRequests += request
                    val base = MockResponse().setHeader("Content-Type", "application/octet-stream")
                    if (request.method == "HEAD") return base.setHeader("Content-Length", payload.size).setHeader("Accept-Ranges", "bytes")
                    val range = request.getHeader("Range") ?: return base.setBody(Buffer().write(payload))
                    val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)!!
                    val start = m.groupValues[1].toInt()
                    val end = m.groupValues[2].toIntOrNull()?.coerceAtMost(payload.size - 1) ?: (payload.size - 1)
                    return base.setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .setBody(Buffer().write(payload, start, end - start + 1))
                }
            }
            start()
        }
        origin = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    originRequests += request
                    // Different host name than the origin ("127.0.0.1" vs "localhost") = a cross-host redirect.
                    return MockResponse().setResponseCode(302).setHeader("Location", "http://localhost:${cdn.port}/blob.bin")
                }
            }
            start()
        }
    }

    @After fun tearDown() {
        origin.shutdown()
        cdn.shutdown()
    }

    @Test fun cookieStaysOnTheOriginHostButOtherHeadersFollow() = runBlocking {
        val headers = mapOf("Cookie" to "sid=secret", "Referer" to "https://page.test/", "User-Agent" to "Browser-UA")
        val file = File(tmp.root, "x.part")
        val url = "http://127.0.0.1:${origin.port}/start"
        val engine = DownloadEngine(OkHttpClient(), maxRetries = 1, backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40)
        engine.download(
            EngineRequest(1, url, "default-ua", file, 2, null, headers),
            object : ProgressStore {
                override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) = Unit
                override suspend fun saveProgress(id: Long, segments: List<SegmentState>) = Unit
            },
        )

        assertTrue(file.readBytes().contentEquals(payload))
        assertTrue(originRequests.isNotEmpty())
        assertTrue("the origin host received the cookie", originRequests.all { it.getHeader("Cookie") == "sid=secret" })
        assertTrue(cdnRequests.isNotEmpty())
        for (r in cdnRequests) {
            assertNull("cookie must not reach the redirect target", r.getHeader("Cookie"))
            assertEquals("https://page.test/", r.getHeader("Referer"))
            assertEquals("Browser-UA", r.getHeader("User-Agent"))
        }
    }
}
