package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.UrlUtils

/** When "Refresh link" / "Change URL..." make sense for a download. */
object LinkRefresh {
    /** The server refused the link as expired or gone: the failure text the manager stores for HTTP 403 / 410. */
    fun isExpiredFailure(error: String?): Boolean = error != null && EXPIRED.containsMatchIn(error)

    /** Plain files only: YouTube re-resolves its own links and HLS segments are signed per playlist. */
    fun canChangeLink(d: DownloadEntity): Boolean =
        d.kind == DownloadKind.HTTP && !d.isPrivate &&
            d.status != DownloadStatus.COMPLETED && d.status != DownloadStatus.CANCELLED

    /** The page the link came from can be opened to capture a new one. */
    fun canRefreshFromPage(d: DownloadEntity): Boolean =
        canChangeLink(d) && d.sourcePage?.let { UrlUtils.isHttpUrl(it) } == true &&
            (d.status == DownloadStatus.FAILED || d.status == DownloadStatus.PAUSED)

    /** A failed download whose link looks expired and that can be refreshed from its page. */
    fun offerRefresh(d: DownloadEntity): Boolean =
        d.status == DownloadStatus.FAILED && isExpiredFailure(d.error) && canRefreshFromPage(d)

    private val EXPIRED = Regex("""HTTP (403|410)\b""")
}
