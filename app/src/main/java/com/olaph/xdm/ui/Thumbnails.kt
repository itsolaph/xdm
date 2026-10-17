package com.olaph.xdm.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.media.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** A frame (or cover art) and the duration of a finished media file; [durationMs] -1 = unknown. */
class MediaInfo(val image: ImageBitmap?, val durationMs: Long)

/**
 * Thumbnails for finished videos (and cover art for audio): one frame via MediaMetadataRetriever, scaled down and kept
 * as a small JPEG in the app's cache, plus an in-memory cache. Private items get their own name prefix so a reset of
 * the private folder can drop them.
 */
object Thumbnails {
    private const val MAX_WIDTH = 320
    private val memory = LruCache<String, MediaInfo>(80)
    private val gate = Semaphore(2)

    private fun dir(context: Context) = File(context.cacheDir, "thumbs")

    private fun key(d: DownloadEntity): String =
        (if (d.isPrivate) "p" else "") + "${d.id}-" + Integer.toHexString(d.savedLocation.orEmpty().hashCode())

    suspend fun load(context: Context, d: DownloadEntity): MediaInfo? {
        if (d.status != DownloadStatus.COMPLETED || !MediaFiles.isMedia(d)) return null
        val location = d.savedLocation ?: return null
        val key = key(d)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val dir = dir(context).apply { mkdirs() }
                val jpg = File(dir, "$key.jpg")
                val meta = File(dir, "$key.txt")
                val info = if (meta.isFile) {
                    MediaInfo(if (jpg.isFile) BitmapFactory.decodeFile(jpg.path)?.asImageBitmap() else null, meta.readText().toLongOrNull() ?: -1)
                } else {
                    extract(context, location, MediaFiles.isVideo(d), jpg, meta)
                }
                info?.also { memory.put(key, it) }
            }
        }
    }

    private fun extract(context: Context, location: String, video: Boolean, jpg: File, meta: File): MediaInfo? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            if (location.startsWith("content://")) r.setDataSource(context, Uri.parse(location)) else r.setDataSource(location)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
            val frame: Bitmap? = if (video) {
                // A tenth into the video (at most 10 s): the very first frame is often black.
                val atMs = if (duration > 0) (duration / 10).coerceAtMost(10_000) else 1_000
                r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime
            } else {
                r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            val small = frame?.let { scaled(it) }
            small?.let { b -> jpg.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
            meta.writeText(duration.toString())
            MediaInfo(small?.asImageBitmap(), duration)
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    private fun scaled(b: Bitmap): Bitmap {
        if (b.width <= MAX_WIDTH) return b
        val h = (b.height.toLong() * MAX_WIDTH / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, MAX_WIDTH, h, true).also { if (it !== b) b.recycle() }
    }

    /** Drops the cached thumbnails of [ids] (public and private variants). */
    fun forget(context: Context, ids: Collection<Long>) {
        if (ids.isEmpty()) return
        memory.evictAll()
        val prefixes = ids.flatMap { listOf("$it-", "p$it-") }
        dir(context).listFiles()?.filter { f -> prefixes.any { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    /** "Reset private folder": every private thumbnail goes. */
    fun forgetPrivate(context: Context) {
        memory.evictAll()
        dir(context).listFiles()?.filter { it.name.startsWith("p") }?.forEach { it.delete() }
    }
}

/** The thumbnail of a finished video or audio file, or [fallback] while loading / when there is none. */
@Composable
private fun rememberMediaInfo(d: DownloadEntity): MediaInfo? {
    val context = LocalContext.current
    var info by remember(d.id, d.savedLocation, d.status) { mutableStateOf<MediaInfo?>(null) }
    LaunchedEffect(d.id, d.savedLocation, d.status) { info = Thumbnails.load(context, d) }
    return info
}

@Composable
fun MediaThumbnail(d: DownloadEntity, modifier: Modifier, fallback: @Composable () -> Unit) {
    val image = rememberMediaInfo(d)?.image
    if (image != null) {
        Image(image, null, modifier.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
    } else {
        Box(modifier) { fallback() }
    }
}

/** Duration of a finished media file once its thumbnail is known; -1 otherwise. */
@Composable
fun mediaDuration(d: DownloadEntity): Long = rememberMediaInfo(d)?.durationMs ?: -1
