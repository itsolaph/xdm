package com.olaph.xdm.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.olaph.xdm.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.OffsetDateTime

enum class TestReportResult { SENT, RETRY_LATER, REJECTED, DISABLED, UNAVAILABLE }

/**
 * Local crash reporting: the uncaught-exception handler only writes a file; uploading happens on the next start.
 * The on/off flag is kept in SharedPreferences (not DataStore) so the handler can read it synchronously.
 * [available] is false in builds without a relay URL: nothing is written or sent, and Settings says so.
 */
class CrashReporter(
    private val queue: CrashQueue,
    private val prefs: SharedPreferences,
    private val uploader: CrashUploader,
    private val env: () -> CrashEnv,
    private val extra: () -> CrashExtra,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now() },
    val available: Boolean = true,
) {
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        // Turning it off also drops anything still waiting: the user said they don't want it sent.
        if (!value) runCatching { queue.clear() }
    }

    /** Wraps [previous] so the system still shows its normal crash dialog / kills the process. */
    fun handler(previous: Thread.UncaughtExceptionHandler?) = Thread.UncaughtExceptionHandler { thread, error ->
        try {
            if (available && _enabled.value) queue.write(CrashPayload.build(error, thread.name, true, clock(), env(), extra()))
        } catch (_: Throwable) {
            // The reporter must never make a crash worse.
        }
        previous?.uncaughtException(thread, error)
    }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler(previous))
    }

    /** Sends whatever earlier runs left behind. Safe to call from any thread; never throws. */
    suspend fun flushPending() = withContext(Dispatchers.IO) {
        try {
            if (available && _enabled.value) queue.flush(uploader) else queue.clear()
        } catch (_: Exception) {
        }
    }

    /** Non-fatal report from a synthetic exception, for the Settings button. */
    suspend fun sendTest(): TestReportResult = withContext(Dispatchers.IO) {
        if (!available) return@withContext TestReportResult.UNAVAILABLE
        if (!_enabled.value) return@withContext TestReportResult.DISABLED
        try {
            val body = CrashPayload.build(
                RuntimeException("test report from settings"), Thread.currentThread().name, false, clock(), env(), extra(),
            )
            when (uploader.upload(body.toByteArray(Charsets.UTF_8))) {
                UploadResult.ACCEPTED -> TestReportResult.SENT
                UploadResult.REJECTED -> TestReportResult.REJECTED
                UploadResult.RETRY -> {
                    queue.write(body)
                    TestReportResult.RETRY_LATER
                }
            }
        } catch (_: Exception) {
            TestReportResult.RETRY_LATER
        }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"

        fun create(app: Application): CrashReporter {
            val foreground = ForegroundTracker().also { app.registerActivityLifecycleCallbacks(it) }
            return CrashReporter(
                queue = CrashQueue(File(app.filesDir, "crash")),
                prefs = app.getSharedPreferences("crash_reporter", Context.MODE_PRIVATE),
                uploader = HttpCrashUploader(),
                available = BuildConfig.CRASH_RELAY_URL.isNotEmpty(),
                env = {
                    CrashEnv(
                        version = BuildConfig.VERSION_NAME,
                        build = BuildConfig.VERSION_CODE,
                        device = "${Build.MANUFACTURER} ${Build.MODEL}",
                        android = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    )
                },
                extra = {
                    val mem = ActivityManager.MemoryInfo()
                    (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
                    CrashExtra(
                        memFreeMb = mem.availMem shr 20,
                        memTotalMb = mem.totalMem shr 20,
                        foreground = foreground.started > 0,
                        uptimeSeconds = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000,
                    )
                },
            )
        }
    }
}

private class ForegroundTracker : Application.ActivityLifecycleCallbacks {
    @Volatile var started = 0
        private set

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started-- }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
