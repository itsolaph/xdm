package com.olaph.xdm.player

import android.content.Context
import android.content.Intent
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.MimeTypes

/** Opens [PlayerActivity]; also decides what it can play. */
object PlayerLauncher {
    /** A video or audio file (by name first, then by the server's type), or an HLS stream. */
    fun isMedia(d: DownloadEntity): Boolean {
        if (d.isHls) return true
        val type = MimeTypes.forFileName(d.fileName) ?: d.mimeType.orEmpty()
        return type.startsWith("video/") || type.startsWith("audio/")
    }

    fun isVideo(d: DownloadEntity): Boolean {
        if (d.isHls) return true
        val type = MimeTypes.forFileName(d.fileName) ?: d.mimeType.orEmpty()
        return type.startsWith("video/")
    }

    /** Streaming the original link only works for plain HTTP files and HLS; YouTube links expire and need the resolver. */
    fun canPreview(d: DownloadEntity): Boolean =
        d.status != DownloadStatus.COMPLETED && !d.isYoutube && isMedia(d)

    /** Plays [startId]; the other finished media files in [playlist] become next/previous. */
    fun play(context: Context, playlist: List<DownloadEntity>, startId: Long) {
        val items = playlist.filter { it.status == DownloadStatus.COMPLETED && isMedia(it) }
            .ifEmpty { return }
        val start = items.firstOrNull { it.id == startId } ?: return
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_IDS, items.map { it.id }.toLongArray())
                .putExtra(PlayerActivity.EXTRA_START_ID, start.id)
                .putExtra(PlayerActivity.EXTRA_PRIVATE, items.any { it.isPrivate })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Streams the original link of an unfinished download with its saved request headers. */
    fun preview(context: Context, d: DownloadEntity) {
        if (!canPreview(d)) return
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_IDS, longArrayOf(d.id))
                .putExtra(PlayerActivity.EXTRA_START_ID, d.id)
                .putExtra(PlayerActivity.EXTRA_PREVIEW, true)
                .putExtra(PlayerActivity.EXTRA_PRIVATE, d.isPrivate)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
