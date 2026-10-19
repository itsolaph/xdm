package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.FatalDownloadException
import com.olaph.xdm.engine.HeaderCodec
import com.olaph.xdm.engine.ProbeResult
import com.olaph.xdm.engine.UrlUtils
import com.olaph.xdm.media.MediaClassifier
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** When "Refresh link" / "Change URL..." make sense for a download. */
object LinkRefresh {
    /** The server refused the link as expired or gone: the failure text the manager stores for HTTP 403 / 410. */
    fun isExpiredFailure(error: String?): Boolean = error != null && EXPIRED.containsMatchIn(error)

    /** Plain files only: YouTube re-resolves its own links and HLS segments are signed per playlist. */
    fun canChangeLink(d: DownloadEntity): Boolean =
        d.kind == DownloadKind.HTTP && !d.isPrivate &&
            d.status != DownloadStatus.COMPLETED && d.status != DownloadStatus.CANCELLED

    /** The page to open for a new link: where the download came from, else the Referer it was started with. */
    fun refreshPage(d: DownloadEntity): String? =
        d.sourcePage?.takeIf { UrlUtils.isHttpUrl(it) }
            ?: HeaderCodec.decode(d.headers).entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                ?.takeIf { UrlUtils.isHttpUrl(it) }

    /** The page the link came from can be opened to capture a new one. */
    fun canRefreshFromPage(d: DownloadEntity): Boolean =
        canChangeLink(d) && refreshPage(d) != null &&
            (d.status == DownloadStatus.FAILED || d.status == DownloadStatus.PAUSED)

    /** A failed download whose link looks expired and that can be refreshed from its page. */
    fun offerRefresh(d: DownloadEntity): Boolean =
        d.status == DownloadStatus.FAILED && isExpiredFailure(d.error) && canRefreshFromPage(d)

    /** The round button on a failed row: with an expired link a plain retry would only fail again. */
    fun retryRefreshesLink(d: DownloadEntity): Boolean = offerRefresh(d)

    private val EXPIRED = Regex("""HTTP (403|410)\b""")
}

/**
 * The download the browser is waiting a new link for ("refresh mode"). A candidate is applied by itself only when its
 * size equals [totalBytes] exactly; [claim] allows that once, and never after [close].
 */
class RefreshTarget(val downloadId: Long, val fileName: String, val totalBytes: Long) {
    private val open = AtomicBoolean(true)
    private val tried = HashSet<String>()

    /** The size is known, so a candidate can be recognised without asking the user. */
    val canAutoMatch: Boolean get() = totalBytes > 0

    val isOpen: Boolean get() = open.get()

    /** True for the first candidate of exactly [totalBytes]; any later call, and any call after [close], gets false. */
    fun claim(size: Long): Boolean = canAutoMatch && size == totalBytes && open.compareAndSet(true, false)

    /** Refresh mode ended (matched, cancelled, replaced by another): nothing is accepted any more. */
    fun close() {
        open.set(false)
    }

    /** True the first time [key] is seen in this refresh, so a URL is not probed twice. */
    fun firstSighting(key: String): Boolean = synchronized(tried) { tried.add(key) }

    /** The probe failed for a passing reason: let the URL be tried again if it shows up again. */
    fun forget(key: String) {
        synchronized(tried) { tried.remove(key) }
    }
}

/**
 * Checks the files a page requests while a link is being refreshed (the visible browser and the background refresh
 * alike): each URL is asked about once, and the first whose size is exactly [RefreshTarget.totalBytes] wins.
 */
class RefreshMatcher(
    private val target: RefreshTarget,
    private val probe: suspend (url: String, headers: Map<String, String>) -> ProbeResult,
) {
    /** The server's answer for [url] if it is the file, null for anything else (also for a URL seen before). */
    suspend fun check(url: String, headers: Map<String, String>): ProbeResult? {
        if (!target.canAutoMatch || !target.isOpen) return null
        val key = MediaClassifier.normalize(url)
        if (!target.firstSighting(key)) return null
        val result = try {
            probe(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (_: FatalDownloadException) {
            return null // refused (403, 404...): the same URL will not do better
        } catch (_: Exception) {
            target.forget(key) // timeout, 5xx: a later request for the URL may try again
            return null
        }
        return result.takeIf { target.claim(it.contentLength) }
    }
}
