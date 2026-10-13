package com.olaph.xdm.crash

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.OffsetDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CrashReporterTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeUploader(var result: UploadResult = UploadResult.ACCEPTED) : CrashUploader {
        val bodies = mutableListOf<String>()
        override fun upload(body: ByteArray): UploadResult {
            bodies += body.toString(Charsets.UTF_8)
            return result
        }
    }

    private val uploader = FakeUploader()
    private lateinit var dir: File
    private lateinit var reporter: CrashReporter

    @Before fun setUp() {
        dir = File(tmp.root, "crash")
        val prefs = prefs()
        prefs.edit().clear().commit()
        reporter = CrashReporter(
            queue = CrashQueue(dir),
            prefs = prefs,
            uploader = uploader,
            env = { CrashEnv("0.5.1", 6, "Test Device", "15 (SDK 35)") },
            extra = { CrashExtra(1, 2, false, 3) },
            clock = { OffsetDateTime.parse("2026-10-12T09:30:15+02:00") },
        )
    }

    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences("crash-test", Context.MODE_PRIVATE)

    private fun pending() = dir.listFiles { f -> f.name.startsWith("pending-") }.orEmpty()

    @Test fun `default is on`() {
        assertTrue(reporter.enabled.value)
    }

    @Test fun `handler writes a fatal report and then calls the previous handler`() {
        var seen: Pair<Thread, Throwable>? = null
        val error = IllegalStateException("boom")
        val h = reporter.handler { t, e -> seen = t to e }

        h.uncaughtException(Thread.currentThread(), error)

        assertSame(error, seen?.second)
        val files = pending()
        assertEquals(1, files.size)
        assertTrue(files[0].name.matches(Regex("pending-\\d+\\.json")))
        val j = JSONObject(files[0].readText())
        assertTrue(j.getBoolean("fatal"))
        assertEquals(Thread.currentThread().name, j.getString("thread"))
        assertTrue(j.getString("stacktrace").contains("IllegalStateException: boom"))
        assertTrue(uploader.bodies.isEmpty()) // no network inside the handler
    }

    @Test fun `switch off writes nothing but still calls the previous handler`() {
        reporter.setEnabled(false)
        var called = false
        reporter.handler { _, _ -> called = true }.uncaughtException(Thread.currentThread(), RuntimeException("x"))

        assertTrue(called)
        assertEquals(0, pending().size)
    }

    @Test fun `switch off sends nothing and drops what was waiting`() {
        reporter.handler(null).uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertEquals(1, pending().size)

        reporter.setEnabled(false)
        runBlocking { reporter.flushPending() }

        assertTrue(uploader.bodies.isEmpty())
        assertEquals(0, pending().size)
    }

    @Test fun `a build without a relay writes and sends nothing`() {
        val off = CrashReporter(
            queue = CrashQueue(dir), prefs = prefs(), uploader = uploader,
            env = { CrashEnv("v", 1, "d", "a") }, extra = { CrashExtra(0, 0, false, 0) }, available = false,
        )
        var called = false
        off.handler { _, _ -> called = true }.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        runBlocking { off.flushPending() }

        assertTrue(called)
        assertEquals(0, pending().size)
        assertEquals(TestReportResult.UNAVAILABLE, runBlocking { off.sendTest() })
        assertTrue(uploader.bodies.isEmpty())
    }

    @Test fun `handler survives a failing report and still chains`() {
        // The directory path is a regular file, so writing must fail.
        val blocked = File(tmp.root, "blocked").also { it.writeText("x") }
        val broken = CrashReporter(
            CrashQueue(File(blocked, "crash")), prefs(),
            uploader, { CrashEnv("v", 1, "d", "a") }, { CrashExtra(0, 0, false, 0) },
        )
        var called = false
        broken.handler { _, _ -> called = true }.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertTrue(called)
    }

    @Test fun `flush on next start uploads the pending report and deletes it`() {
        reporter.handler(null).uncaughtException(Thread.currentThread(), RuntimeException("old crash"))
        runBlocking { reporter.flushPending() }

        assertEquals(1, uploader.bodies.size)
        assertTrue(JSONObject(uploader.bodies[0]).getString("stacktrace").contains("old crash"))
        assertEquals(0, pending().size)
    }

    @Test fun `test report is non-fatal with the settings message and is sent directly`() {
        val result = runBlocking { reporter.sendTest() }

        assertEquals(TestReportResult.SENT, result)
        val j = JSONObject(uploader.bodies.single())
        assertFalse(j.getBoolean("fatal"))
        assertTrue(j.getString("stacktrace").contains("test report from settings"))
        assertEquals(0, pending().size)
    }

    @Test fun `test report is queued for retry when the relay is unreachable`() {
        uploader.result = UploadResult.RETRY
        assertEquals(TestReportResult.RETRY_LATER, runBlocking { reporter.sendTest() })
        assertEquals(1, pending().size)
    }

    @Test fun `test report rejected with 403 is not queued`() {
        uploader.result = UploadResult.REJECTED
        assertEquals(TestReportResult.REJECTED, runBlocking { reporter.sendTest() })
        assertEquals(0, pending().size)
    }

    @Test fun `test report does nothing when the switch is off`() {
        reporter.setEnabled(false)
        assertEquals(TestReportResult.DISABLED, runBlocking { reporter.sendTest() })
        assertTrue(uploader.bodies.isEmpty())
        assertEquals(0, pending().size)
    }
}
