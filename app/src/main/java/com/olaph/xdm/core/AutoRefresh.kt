package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.SettingsSource
import com.olaph.xdm.engine.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a background look at a download's page came up with. */
sealed interface RefreshOutcome {
    /** A file of exactly the download's size; [probe] is the server's answer for [url]. */
    class Found(val url: String, val headers: Map<String, String>, val pageUrl: String?, val probe: ProbeResult) : RefreshOutcome

    data object NotFound : RefreshOutcome

    /** The page could not be opened at all in this state (no WebView): nothing was tried. */
    data object Unavailable : RefreshOutcome
}

/** Opens [page] without showing it and reports the file whose size [target] accepts. Must clean up when cancelled. */
fun interface LinkFinder {
    suspend fun find(page: String, target: RefreshTarget): RefreshOutcome
}

/** What [AutoRefresh] needs from the download list. */
interface RefreshPort {
    suspend fun get(id: Long): DownloadEntity?

    /** Swaps in the new link and resumes; false = not applied (the file changed on the server, or the item can't take it). */
    suspend fun apply(id: Long, found: RefreshOutcome.Found): Boolean
}

interface RefreshNotifier {
    /** "Link refreshed, download resumed" (quiet). */
    fun refreshed(download: DownloadEntity)

    /** "Couldn't refresh the link", with a way to start the visible flow. */
    fun couldNotRefresh(download: DownloadEntity)

    /** The ordinary failure notice, for when the background refresh was not tried after all. */
    fun failed(download: DownloadEntity)
}

/**
 * Refreshes an expired link by itself when a plain download fails with HTTP 403/410: [finder] looks at the download's
 * page in the background and only a file of exactly the download's size is taken over. One refresh runs at a time (the
 * others wait), each gets [timeoutMs], and a download is tried at most once per failure and [maxPerWindow] times in
 * [windowMs]. Anything that does not work out leaves the item FAILED.
 */
class AutoRefresh(
    private val scope: CoroutineScope,
    private val settings: SettingsSource,
    private val port: RefreshPort,
    private val finder: LinkFinder,
    private val notifier: RefreshNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxPerWindow: Int = MAX_PER_WINDOW,
    private val windowMs: Long = WINDOW_MS,
) {
    private val lock = Any()
    private val queue = Channel<Long>(Channel.UNLIMITED)
    private val waiting = HashSet<Long>()
    private val attempts = HashMap<Long, MutableList<Long>>()

    private val _active = MutableStateFlow(false)

    /** A refresh is running or waiting its turn. */
    val active: StateFlow<Boolean> = _active

    init {
        scope.launch { for (id in queue) runOne(id) }
    }

    /**
     * A download just failed. True = taken over (the caller keeps the failure notice back: the outcome is announced by
     * [notifier]); false = nothing happens here and the ordinary failure notice applies.
     */
    suspend fun onFailed(d: DownloadEntity): Boolean {
        if (!eligible(d) || !settings.current().autoRefreshLinks) return false
        synchronized(lock) {
            if (d.id in waiting) return true // this failure is already being dealt with
            val recent = attempts.getOrPut(d.id) { ArrayList() }
            val now = clock()
            recent.removeAll { now - it >= windowMs }
            if (recent.size >= maxPerWindow) return false
            recent += now
            waiting += d.id
            _active.value = true
        }
        queue.trySend(d.id)
        return true
    }

    /** Plain files with an expired link, a page to look at and a known size (the size is how a file is recognised). */
    private fun eligible(d: DownloadEntity) = LinkRefresh.offerRefresh(d) && d.totalBytes > 0

    private suspend fun runOne(id: Long) {
        try {
            attempt(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            port.get(id)?.takeIf { it.status == DownloadStatus.FAILED }?.let(notifier::failed)
        } finally {
            synchronized(lock) {
                waiting -= id
                _active.value = waiting.isNotEmpty()
            }
        }
    }

    private suspend fun attempt(id: Long) {
        val d = port.get(id) ?: return
        // Retried, paused or changed by hand in the meantime: nothing to do, and the user is already involved.
        if (d.status != DownloadStatus.FAILED) return
        val page = LinkRefresh.refreshPage(d)
        if (page == null || !eligible(d) || !settings.current().autoRefreshLinks) {
            notifier.failed(d)
            return
        }
        val target = RefreshTarget(d.id, d.fileName, d.totalBytes)
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { finder.find(page, target) } ?: RefreshOutcome.NotFound
        } finally {
            target.close() // a late match must not be taken over
        }
        when (outcome) {
            RefreshOutcome.Unavailable -> notifier.failed(d)
            RefreshOutcome.NotFound -> notifier.couldNotRefresh(d)
            is RefreshOutcome.Found -> {
                // The finder only reports exact sizes; the check is repeated here because a wrong size must never be applied.
                val sameSize = outcome.probe.contentLength == d.totalBytes
                if (sameSize && port.apply(id, outcome)) notifier.refreshed(d) else notifier.couldNotRefresh(d)
            }
        }
    }

    companion object {
        const val TIMEOUT_MS = 60_000L
        const val MAX_PER_WINDOW = 2
        const val WINDOW_MS = 30 * 60_000L
    }
}
