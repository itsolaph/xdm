package com.olaph.xdm.media

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.engine.MimeTypes
import kotlin.math.max

/** What a downloaded file is, for thumbnails and durations in the lists. */
object MediaFiles {
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

    /** "0:07", "12:34", "1:02:03". */
    fun formatTime(ms: Long): String {
        val total = max(0, ms) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
