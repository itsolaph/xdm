package com.olaph.xdm.debug

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.olaph.xdm.XdmApp
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.vault.VaultStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Debug-only check of the private folder on a real device or emulator:
 * `adb shell am start -n com.olaph.xdm/.debug.VaultTestActivity`. Puts the bundled 10 s test video
 * (`src/debug/assets/vault-test.mp4`, H.264 + AAC) into the private folder the way a finished private download gets
 * there, checks MediaStore cannot see it, reads its duration and decodes a frame with the platform's
 * MediaMetadataRetriever (what the thumbnails use), deletes it through the normal delete path, then logs exactly one
 * line (tag `VAULTTEST`) and finishes:
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

            val probe = withContext(Dispatchers.IO) { probe(file) }

            val report = c.manager.deleteMany(listOf(id), deleteFiles = true, privateItems = true)
            cleaned = true
            if (report.removed != 1 || file.exists() || c.dao.get(id) != null) {
                return "VAULTTEST FAIL cleanup removed=${report.removed} fileLeft=${file.exists()}"
            }
            return "VAULTTEST PASS size=${size}B mediastore=0 duration=${probe.durationMs}ms " +
                "frame=${probe.frameWidth}x${probe.frameHeight} cleaned"
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

    private class Probe(val durationMs: Long, val frameWidth: Int, val frameHeight: Int)

    /** Opens [file] with MediaMetadataRetriever: it must report a duration and give back a frame. */
    private fun probe(file: File): Probe {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
            if (duration <= 0) throw IllegalStateException("no duration")
            val frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
                ?: throw IllegalStateException("no frame")
            return Probe(duration, frame.width, frame.height).also { frame.recycle() }
        } finally {
            runCatching { r.release() }
        }
    }

    private companion object {
        const val TAG = "VAULTTEST"
        const val ASSET = "vault-test.mp4"
        const val TIMEOUT_MS = 60_000L
    }
}
