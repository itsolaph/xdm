package com.olaph.xdm.debug

import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.olaph.xdm.core.FileSaver
import com.olaph.xdm.core.Hold
import com.olaph.xdm.core.DeleteStatus
import com.olaph.xdm.core.RunGate
import com.olaph.xdm.core.SaveTarget
import com.olaph.xdm.core.SchedulePlan
import com.olaph.xdm.engine.SpeedLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.InputStream
import java.time.LocalTime
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only check of saving, the schedule decision and the speed limiter on a real device or emulator:
 * `adb shell am start -n com.olaph.xdm/.debug.SaveTestActivity`. It
 *
 *  1. saves a small file named like a video through the real saver with "sort by type" on, into the default MediaStore
 *     location, and checks it lands in Download/XDM/Video, reads back byte for byte, and deletes it again;
 *  2. runs the schedule decision for a few times of day;
 *  3. reads for two seconds from an in-process source through the speed limiter at 256 KB/s, four connections at once, and
 *     checks the total is about what the cap allows.
 *
 * It logs exactly one line (tag `XDM`) and finishes: `SAVETEST PASS <details>` or `SAVETEST FAIL <reason>`. No network, no
 * permissions beyond what the app already has. The user's settings are not touched.
 */
class SaveTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(32, 96, 32, 32)
            text = "Save, schedule and speed limit self-test…"
        }
        setContentView(text)
        lifecycleScope.launch {
            val line = try {
                withTimeout(TIMEOUT_MS) { run() }
            } catch (e: TimeoutCancellationException) {
                "SAVETEST FAIL timeout after ${TIMEOUT_MS / 1000} s"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "SAVETEST FAIL ${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)}"
            }
            Log.i(TAG, line)
            text.text = line
            finish()
        }
    }

    private suspend fun run(): String {
        val saved = checkSaving()
        if (saved.fail != null) return "SAVETEST FAIL ${saved.fail}"
        checkSchedule()?.let { return "SAVETEST FAIL $it" }
        val limiter = checkLimiter()
        if (limiter.fail != null) return "SAVETEST FAIL ${limiter.fail}"
        return "SAVETEST PASS saved=${saved.path} bytes=${saved.bytes} schedule=ok limiter=${limiter.rate}B/s"
    }

    // ---- saving ----

    private class SaveResult(val fail: String? = null, val path: String = "", val bytes: Int = 0)

    private suspend fun checkSaving(): SaveResult = withContext(Dispatchers.IO) {
        val payload = ByteArray(48 * 1024).also { Random(77).nextBytes(it) }
        val name = "savetest-${System.currentTimeMillis()}.mp4"
        val temp = File(cacheDir, "savetest.part").also { it.writeBytes(payload) }
        // The default location (MediaStore) with sorting on, whatever the user's own settings say.
        val saver = FileSaver(this@SaveTestActivity, target = { SaveTarget(treeUri = null, sortByType = true) })
        var location: String? = null
        try {
            val saved = saver.save(temp, name, "video/mp4")
            location = saved.location
            if (saved.note != null) return@withContext SaveResult("unexpected note: ${saved.note}")
            val expectedFolder = "Download/XDM/Video"
            if (saved.displayPath != "$expectedFolder/${saved.fileName}") {
                return@withContext SaveResult("display path ${saved.displayPath} is not in $expectedFolder")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relative = contentResolver.query(
                    android.net.Uri.parse(saved.location), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null,
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
                if (relative != "$expectedFolder/") return@withContext SaveResult("MediaStore says the file is in '$relative'")
            } else if (!saved.location.contains("/$expectedFolder/")) {
                return@withContext SaveResult("file is at ${saved.location}")
            }
            if (!saver.exists(saved.location)) return@withContext SaveResult("saved file does not exist")
            val back = saver.open(saved.location).use(InputStream::readBytes)
            if (!back.contentEquals(payload)) return@withContext SaveResult("read back ${back.size} bytes, content differs")

            val status = saver.delete(saved.location)
            if (status != DeleteStatus.DELETED) return@withContext SaveResult("delete returned $status")
            location = null
            if (saver.exists(saved.location)) return@withContext SaveResult("file still exists after delete")
            SaveResult(path = saved.displayPath, bytes = back.size)
        } finally {
            location?.let { runCatching { saver.delete(it) } }
            temp.delete()
        }
    }

    // ---- schedule ----

    private fun checkSchedule(): String? {
        val night = SchedulePlan(enabled = true, startMinute = 23 * 60, endMinute = 7 * 60, onlyWhileCharging = true)
        fun hold(h: Int, m: Int, charging: Boolean, force: Boolean = false, wifiOnly: Boolean = false, metered: Boolean = false) =
            RunGate.holdFor(LocalTime.of(h, m), charging, wifiOnly, metered, night, force)
        val cases = listOf(
            Triple("midday", hold(12, 0, charging = true), Hold.SCHEDULE),
            Triple("night on charger", hold(23, 30, charging = true), null),
            Triple("night unplugged", hold(2, 0, charging = false), Hold.CHARGING),
            Triple("window end", hold(7, 0, charging = true), Hold.SCHEDULE),
            Triple("start now", hold(12, 0, charging = false, force = true), null),
            Triple("wifi only", hold(23, 30, charging = true, force = true, wifiOnly = true, metered = true), Hold.WIFI),
        )
        cases.firstOrNull { it.second != it.third }?.let { return "schedule ${it.first}: got ${it.second}, expected ${it.third}" }
        if (!RunGate.mayRun(LocalTime.of(3, 0), true, false, false, night.copy(startMinute = 5, endMinute = 5), false)) {
            return "schedule: an equal start and end must always be open"
        }
        return null
    }

    // ---- speed limiter ----

    private class LimiterResult(val fail: String? = null, val rate: Long = 0)

    private suspend fun checkLimiter(): LimiterResult {
        val cap = 256L * 1024
        val limiter = SpeedLimiter().apply { bytesPerSecond = cap }
        val total = AtomicLong()
        val source = object : InputStream() { // an endless source of zeros, nothing to wait for
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len.also { java.util.Arrays.fill(b, off, off + len, 0) }
        }
        val started = System.nanoTime()
        coroutineScope {
            val deadline = started + READ_NANOS
            List(4) {
                async(Dispatchers.Default) {
                    val buf = ByteArray(64 * 1024)
                    while (System.nanoTime() < deadline) {
                        val n = source.read(buf, 0, limiter.maxChunk(buf.size))
                        limiter.acquire(n)
                        total.addAndGet(n.toLong())
                    }
                }
            }.awaitAll()
        }
        val seconds = (System.nanoTime() - started) / 1e9
        val rate = (total.get() / seconds).toLong()
        // The cap plus the short burst allowance (a quarter second) over two seconds, with a little slack either way.
        val low = cap * 0.8
        val high = cap * 1.25
        if (rate < low || rate > high) return LimiterResult("limiter measured $rate B/s at a cap of $cap B/s")
        // Off must not slow anything down.
        limiter.bytesPerSecond = 0
        val t0 = System.nanoTime()
        repeat(2000) { limiter.acquire(64 * 1024) }
        if ((System.nanoTime() - t0) > 200_000_000L) return LimiterResult("limiter off still waits")
        return LimiterResult(rate = rate)
    }

    private companion object {
        const val TAG = "XDM"
        const val TIMEOUT_MS = 60_000L
        const val READ_NANOS = 2_000_000_000L
    }
}
