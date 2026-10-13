package com.olaph.xdm.crash

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class CrashUploaderTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun uploader() = HttpCrashUploader(server.url("/v1/crash").toString(), "test-key")

    @Test fun `posts json body with the app key header`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = uploader().upload("""{"app":"xdm"}""".toByteArray())

        assertEquals(UploadResult.ACCEPTED, result)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", req.method)
        assertEquals("/v1/crash", req.path)
        assertEquals("test-key", req.getHeader("X-App-Key"))
        assertEquals("application/json", req.getHeader("Content-Type"))
        assertEquals("""{"app":"xdm"}""", req.body.readUtf8())
    }

    @Test fun `any 2xx is accepted`() {
        server.enqueue(MockResponse().setResponseCode(202))
        assertEquals(UploadResult.ACCEPTED, uploader().upload(ByteArray(1)))
    }

    @Test fun `403 is rejected`() {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(UploadResult.REJECTED, uploader().upload(ByteArray(1)))
    }

    @Test fun `500 and 429 and redirects are retried`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://elsewhere.invalid/"))
        assertEquals(UploadResult.RETRY, uploader().upload(ByteArray(1)))
        assertEquals(UploadResult.RETRY, uploader().upload(ByteArray(1)))
        assertEquals(UploadResult.RETRY, uploader().upload(ByteArray(1)))
    }

    @Test fun `unreachable server is a retry, not an exception`() {
        val url = server.url("/v1/crash").toString()
        server.shutdown()
        assertEquals(UploadResult.RETRY, HttpCrashUploader(url, "k").upload(ByteArray(1)))
    }
}
