package com.olaph.xdm.crash

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Robolectric only for a working org.json; the plain unit-test android.jar returns defaults.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CrashPayloadTest {

    private val env = CrashEnv("0.5.1", 6, "Google Pixel 8", "15 (SDK 35)")
    private val extra = CrashExtra(memFreeMb = 1200, memTotalMb = 7800, foreground = true, uptimeSeconds = 42)
    private val at = OffsetDateTime.of(2026, 10, 12, 9, 30, 15, 0, ZoneOffset.ofHours(2))

    private fun build(t: Throwable, fatal: Boolean = true, thread: String = "main") =
        JSONObject(CrashPayload.build(t, thread, fatal, at, env, extra))

    @Test fun `all contract fields are present with the right types`() {
        val j = build(IllegalStateException("boom"))
        assertEquals("xdm", j.getString("app"))
        assertEquals("0.5.1", j.getString("version"))
        assertEquals(6, j.getInt("build"))
        assertEquals("Google Pixel 8", j.getString("device"))
        assertEquals("15 (SDK 35)", j.getString("android"))
        assertEquals("main", j.getString("thread"))
        assertEquals("2026-10-12T09:30:15+02:00", j.getString("crashed_at"))
        assertTrue(j.getBoolean("fatal"))
        assertTrue(j.getString("stacktrace").startsWith("java.lang.IllegalStateException: boom"))
        assertEquals(
            setOf("app", "version", "build", "device", "android", "thread", "crashed_at", "fatal", "stacktrace", "extra"),
            j.keys().asSequence().toSet(),
        )
    }

    @Test fun `extra carries only memory, foreground and uptime`() {
        val e = build(RuntimeException("x")).getJSONObject("extra")
        assertEquals(setOf("mem_free_mb", "mem_total_mb", "foreground", "uptime_s"), e.keys().asSequence().toSet())
        assertEquals(1200, e.getLong("mem_free_mb"))
        assertEquals(7800, e.getLong("mem_total_mb"))
        assertTrue(e.getBoolean("foreground"))
        assertEquals(42, e.getLong("uptime_s"))
    }

    @Test fun `fatal flag is passed through`() {
        assertFalse(build(RuntimeException("x"), fatal = false).getBoolean("fatal"))
    }

    @Test fun `every nested Caused by is kept and its message scrubbed`() {
        val root = java.io.FileNotFoundException("/storage/emulated/0/Download/secret clip.mp4: open failed: ENOENT")
        val mid = java.io.IOException("download of https://site.example/v/1.m3u8?sig=abc failed", root)
        val top = RuntimeException("wrapper", mid)
        val trace = build(top).getString("stacktrace")

        assertTrue(trace.contains("java.lang.RuntimeException: wrapper"))
        assertTrue(trace.contains("Caused by: java.io.IOException: download of <url> failed"))
        assertTrue(trace.contains("Caused by: java.io.FileNotFoundException: <path>: open failed: ENOENT"))
        assertFalse(trace.contains("site.example"))
        assertFalse(trace.contains("secret clip"))
    }

    @Test fun `a youtube extractor failure leaves no title, channel, id or stream url in the payload`() {
        val title = "My Private Holiday Video 2026"
        val id = "dQw4w9WgXcQ"
        val failure = RuntimeException(
            "resolving failed",
            org.schabi.newpipe.extractor.exceptions.ParsingException(
                "Could not get name for \"$title\" from https://www.youtube.com/watch?v=$id by channel Secret Channel",
                java.io.IOException("GET https://rr3---sn-x.googlevideo.com/videoplayback?id=$id&sig=SECRET failed"),
            ),
        )
        val body = JSONObject(CrashPayload.build(failure, "OkHttp https://rr3---sn-x.googlevideo.com/videoplayback?id=$id", true, at, env, extra))
        val everything = body.toString()
        for (secret in listOf(title, id, "Secret Channel", "googlevideo", "youtube.com/watch", "SECRET")) {
            assertFalse("payload leaks '$secret'", everything.contains(secret))
        }
        assertEquals("OkHttp", body.getString("thread"))
        val trace = body.getString("stacktrace")
        assertTrue(trace, trace.contains("org.schabi.newpipe.extractor.exceptions.ParsingException: <redacted>"))
        assertTrue("the cause chain is kept for debugging", trace.contains("Caused by: java.io.IOException: GET <url> failed"))
    }

    @Test fun `stacktrace is cut at 100000 chars`() {
        val trace = build(RuntimeException("a".repeat(250_000))).getString("stacktrace")
        assertEquals(100_000, trace.length)
    }

    @Test fun `body stays under the relay cap even with wide characters`() {
        val body = CrashPayload.build(RuntimeException("é中".repeat(150_000)), "main", true, at, env, extra)
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= 200_000)
    }
}
