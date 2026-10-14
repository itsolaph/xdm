package com.olaph.xdm.debug

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.olaph.xdm.XdmApp
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.player.ResumePolicy
import com.olaph.xdm.vault.VaultStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Debug-only check of the private folder and the player on a real device or emulator:
 * `adb shell am start -n com.olaph.xdm/.debug.VaultTestActivity`. Puts the bundled 10 s test video
 * (`src/debug/assets/vault-test.mp4`, H.264 + AAC) into the private folder the way a finished private download gets
 * there, checks MediaStore cannot see it, plays it with ExoPlayer (no UI) until the position moves, stores and reads
 * back a resume position, deletes it through the normal delete path, then logs exactly one line (tag `VAULTTEST`) and
 * finishes:
 *
 *  - `VAULTTEST PASS <details>`
 *  - `VAULTTEST FAIL <reason>`
 *
 * Needs no network and no PIN (the PIN guards the UI, not the storage).
 */
class VaultTestActivity : ComponentActivity() {
    private val container get() = (application as XdmApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply {
            setPadding(32, 96, 32, 32)
            text = "Private folder self-test…"
        }
        setContentView(text)
        lifecycleScope.launch {
            val line = try {
                withTimeout(TIMEOUT_MS) { run() }
            } catch (e: TimeoutCancellationException) {
                "VAULTTEST FAIL timeout after ${TIMEOUT_MS / 1000} s"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "VAULTTEST FAIL ${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)}"
            }
            Log.i(TAG, line)
            text.text = line
            finish()
        }
    }

    private suspend fun run(): String {
        val c = container
        val name = "vault-selftest-${System.currentTimeMillis()}.mp4"

        // A finished download sits in a temp file; the completion step moves it into the private folder.
        val temp = File(cacheDir, "vaulttest.part")
        withContext(Dispatchers.IO) { assets.open(ASSET).use { input -> temp.outputStream().use { input.copyTo(it) } } }
        val size = temp.length()
        val saved = withContext(Dispatchers.IO) { c.vault.save(temp, name, "video/mp4") }
        val id = c.dao.insert(
            DownloadEntity(
                url = "https://selftest.invalid/vault-test.mp4", fileName = saved.fileName, mimeType = "video/mp4",
                totalBytes = size, downloadedBytes = size, status = DownloadStatus.COMPLETED, completedAt = System.currentTimeMillis(),
                savedLocation = saved.location, savedPath = saved.displayPath, isPrivate = true,
            ),
        )
        var cleaned = false
        try {
            val file = File(saved.location)
            val vaultDir = File(filesDir, VaultStore.DIR_NAME).canonicalFile
            if (file.canonicalFile.parentFile != vaultDir) return "VAULTTEST FAIL file is not in the private folder"
            if (file.length() != size) return "VAULTTEST FAIL size ${file.length()} != $size"
            @Suppress("DEPRECATION")
            if (file.absolutePath.startsWith(Environment.getExternalStorageDirectory().absolutePath)) {
                return "VAULTTEST FAIL private file is on shared storage"
            }
            if (temp.exists()) return "VAULTTEST FAIL temp file left behind"

            val hits = withContext(Dispatchers.IO) { mediaStoreHits(saved.fileName) }
            if (hits != 0) return "VAULTTEST FAIL visible in MediaStore ($hits rows)"

            val first = play(file, 0)

            // Resume: a position past 5 s and before 95 % is stored, read back and played from.
            val want = 6_000L
            c.dao.setPlaybackPosition(id, want)
            val stored = c.dao.get(id)?.playbackPositionMs ?: -1
            val start = ResumePolicy.startPosition(stored, first.durationMs)
            if (stored != want || start != want) return "VAULTTEST FAIL resume position stored=$stored start=$start"
            val resumed = play(file, start)
            if (resumed.readyAtMs < want - 250) return "VAULTTEST FAIL resumed at ${resumed.readyAtMs} ms, wanted $want"
            // Finished: the position is cleared.
            c.dao.setPlaybackPosition(id, ResumePolicy.toSave(first.durationMs, first.durationMs, ended = true))
            if (c.dao.get(id)?.playbackPositionMs != 0L) return "VAULTTEST FAIL position not cleared"

            val report = c.manager.deleteMany(listOf(id), deleteFiles = true, privateItems = true)
            cleaned = true
            if (report.removed != 1 || file.exists() || c.dao.get(id) != null) {
                return "VAULTTEST FAIL cleanup removed=${report.removed} fileLeft=${file.exists()}"
            }
            return "VAULTTEST PASS size=${size}B mediastore=0 ready=${first.readyAfterMs}ms advanced=${first.advancedMs}ms " +
                "duration=${first.durationMs}ms resume=${resumed.readyAtMs}ms cleaned"
        } finally {
            if (!cleaned) {
                withContext(Dispatchers.IO) { c.vault.delete(saved.location) }
                c.dao.delete(id)
            }
            temp.delete()
        }
    }

    /** Rows MediaStore has under this name, anywhere on external storage. */
    private fun mediaStoreHits(name: String): Int =
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { it.count } ?: 0

    private class Playback(val readyAfterMs: Long, val readyAtMs: Long, val advancedMs: Long, val durationMs: Long)

    /** Plays [file] from [startMs] without a surface, muted, until it is ready and the clock moved 500 ms. */
    private suspend fun play(file: File, startMs: Long): Playback {
        val player = ExoPlayer.Builder(this).build()
        try {
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), startMs)
            player.prepare()
            player.play()
            val t0 = SystemClock.elapsedRealtime()
            while (player.playbackState != Player.STATE_READY) {
                player.playerError?.let { throw it }
                delay(POLL_MS)
            }
            val readyAfter = SystemClock.elapsedRealtime() - t0
            val readyAt = player.currentPosition
            while (player.currentPosition < readyAt + 500) {
                player.playerError?.let { throw it }
                if (player.playbackState == Player.STATE_ENDED) break
                delay(POLL_MS)
            }
            val advanced = player.currentPosition - readyAt
            if (advanced < 500) throw IllegalStateException("position advanced only $advanced ms")
            val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: -1
            return Playback(readyAfter, readyAt, advanced, duration)
        } finally {
            player.release()
        }
    }

    private companion object {
        const val TAG = "VAULTTEST"
        const val ASSET = "vault-test.mp4"
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 50L
    }
}
