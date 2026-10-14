package com.olaph.xdm.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olaph.xdm.AppContainer
import com.olaph.xdm.XdmApp
import com.olaph.xdm.adblock.FilterListsInfo
import com.olaph.xdm.adblock.PopupMode
import com.olaph.xdm.adblock.UpdateState
import com.olaph.xdm.core.DeleteProgress
import com.olaph.xdm.core.DeleteReport
import com.olaph.xdm.core.DeleteTarget
import com.olaph.xdm.core.LiveProgress
import com.olaph.xdm.core.PendingDownload
import com.olaph.xdm.crash.TestReportResult
import com.olaph.xdm.engine.SearchEngine
import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.SegmentEntity
import com.olaph.xdm.data.ThemeMode
import com.olaph.xdm.engine.UrlUtils
import com.olaph.xdm.youtube.YoutubeLink
import com.olaph.xdm.youtube.YoutubeQuality
import com.olaph.xdm.youtube.YoutubeUrls
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadItem(val entity: DownloadEntity, val live: LiveProgress?) {
    val downloaded: Long get() = live?.downloaded ?: entity.downloadedBytes
    val total: Long get() = live?.total?.takeIf { it > 0 } ?: entity.totalBytes
    val fraction: Float?
        get() = when {
            live?.converting == true -> live.convertPercent / 100f
            // HLS: progress is "segments done / segments listed"; byte totals are only estimates.
            entity.isHls -> segmentsTotal.takeIf { it > 0 }?.let { (segmentsDone.toFloat() / it).coerceIn(0f, 1f) }
            total > 0 -> (downloaded.toFloat() / total).coerceIn(0f, 1f)
            else -> null
        }
    val segmentsDone: Int get() = live?.takeIf { it.segmentsTotal > 0 }?.segmentsDone ?: entity.segmentsDone
    val segmentsTotal: Int get() = live?.segmentsTotal?.takeIf { it > 0 } ?: entity.segmentsTotal
}

enum class Filter(val label: String) {
    ALL("All"), ACTIVE("Active"), COMPLETED("Completed"), FAILED("Failed");

    fun matches(s: DownloadStatus) = when (this) {
        ALL -> true
        ACTIVE -> s == DownloadStatus.QUEUED || s == DownloadStatus.RUNNING || s == DownloadStatus.PAUSED
        COMPLETED -> s == DownloadStatus.COMPLETED
        FAILED -> s == DownloadStatus.FAILED || s == DownloadStatus.CANCELLED
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val container: AppContainer = (app as XdmApp).container
    private val manager = container.manager

    val items: StateFlow<List<DownloadItem>?> =
        combine(container.dao.observeAll(), manager.live) { list, live ->
            list.map { DownloadItem(it, live[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> =
        container.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val waitingForWifi: StateFlow<Boolean> = manager.waitingForWifi

    fun segments(id: Long): Flow<List<SegmentEntity>> = container.dao.observeSegments(id)

    // ---- add dialog / clipboard ----

    /** What to pre-fill in the add sheet (a clipboard offer, an incoming intent, or a browser download). */
    val addSheetPrefill = MutableStateFlow<PendingDownload?>(null)
    val showAddSheet = MutableStateFlow(false)
    val clipboardOffer = MutableStateFlow<String?>(null)
    private var lastOfferedUrl: String? = null

    fun openAddSheet(url: String? = null) = openAddSheet(url?.let { PendingDownload(it) })

    fun openAddSheet(prefill: PendingDownload?) {
        if (prefill != null) lastOfferedUrl = prefill.url
        clipboardOffer.value = null
        // A YouTube link is not a file: it gets its own sheet (formats, playlist) instead of the plain URL form.
        val youtube = prefill?.let { YoutubeUrls.parse(it.url) }
        if (youtube != null) {
            openYoutube(youtube)
            return
        }
        addSheetPrefill.value = prefill
        showAddSheet.value = true
    }

    /** The YouTube sheet for [link]; replaces the add sheet if that is open. */
    val youtubeLink = MutableStateFlow<YoutubeLink?>(null)

    fun openYoutube(link: YoutubeLink) {
        closeAddSheet()
        youtubeLink.value = link
    }

    fun closeYoutube() {
        youtubeLink.value = null
    }

    fun closeAddSheet() {
        showAddSheet.value = false
        addSheetPrefill.value = null
    }

    fun dismissClipboardOffer() {
        clipboardOffer.value = null
    }

    /** Offer a URL found on the clipboard, once per distinct URL. */
    fun checkClipboard(context: Context) {
        if (showAddSheet.value) return
        val clip = runCatching {
            context.getSystemService(ClipboardManager::class.java).primaryClip
        }.getOrNull() ?: return
        if (clip.itemCount == 0 || clip.description?.hasMimeType("text/*") != true) return
        val url = UrlUtils.extractFirstUrl(clip.getItemAt(0).coerceToText(context)) ?: return
        if (url == lastOfferedUrl) return
        lastOfferedUrl = url
        clipboardOffer.value = url
    }

    // ---- actions ----

    fun pause(id: Long) = manager.pause(id)
    fun resume(id: Long) = manager.resume(id)
    fun cancel(id: Long) = manager.cancel(id)
    fun retry(id: Long) = manager.retry(id)
    fun delete(id: Long, deleteFile: Boolean) = deleteDownloads(listOf(id), deleteFile)
    fun pauseAll() = manager.pauseAll()
    fun resumeAll() = manager.resumeAll()

    // ---- selection and bulk delete ----

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected
    val deleteProgress: StateFlow<DeleteProgress?> = manager.deleteProgress

    /** One-shot UI events (snackbar text, system delete confirmation). */
    sealed interface Event {
        data class Message(val text: String) : Event
        data class SystemDelete(val request: IntentSender) : Event
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    fun toggleSelected(id: Long) = _selected.update { if (id in it) it - id else it + id }
    fun selectAll(ids: Collection<Long>) { _selected.value = ids.toSet() }
    fun clearSelection() { _selected.value = emptySet() }

    /** Records waiting for the system's delete dialog, and what the first pass already did. */
    private var awaiting: List<DeleteTarget> = emptyList()
    private var carried = DeleteReport(0, 0, 0, emptyList())

    /**
     * Single, selected and "delete all" go through here. Runs in the app scope: leaving the screen must not
     * abort a half-finished batch.
     */
    fun deleteDownloads(ids: Collection<Long>, alsoFiles: Boolean) {
        _selected.update { it - ids.toSet() }
        container.scope.launch {
            val report = manager.deleteMany(ids, alsoFiles)
            val request = if (report.needsPermission.isEmpty()) null
            else container.fileSaver.deleteRequest(report.needsPermission.map { it.location })
            if (request == null) {
                // Nothing to ask (or API < 30): what the app may not delete counts as failed.
                finishDelete(report, extraFailed = report.needsPermission.size)
            } else {
                awaiting = report.needsPermission
                carried = report
                _events.send(Event.SystemDelete(request))
            }
        }
    }

    /** Result of the system's delete dialog; [ok] = the user allowed it. */
    fun onSystemDeleteResult(ok: Boolean) {
        val targets = awaiting
        val first = carried
        awaiting = emptyList()
        carried = DeleteReport(0, 0, 0, emptyList())
        if (targets.isEmpty()) return
        container.scope.launch {
            if (ok) {
                val second = manager.removeRecords(targets)
                finishDelete(DeleteReport(first.removed + second.removed, first.freedBytes + second.freedBytes, first.failed, emptyList()), 0)
            } else {
                finishDelete(first, extraFailed = targets.size)
            }
        }
    }

    private suspend fun finishDelete(report: DeleteReport, extraFailed: Int) {
        _events.send(Event.Message(DeleteScope.message(report.removed, report.freedBytes, report.failed + extraFailed)))
    }

    fun setConnections(v: Int) = viewModelScope.launch { container.settings.setConnections(v) }
    fun setMaxParallel(v: Int) = viewModelScope.launch { container.settings.setMaxParallel(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { container.settings.setWifiOnly(v) }
    fun setResumeAfterRestart(v: Boolean) = viewModelScope.launch { container.settings.setResumeAfterRestart(v) }
    fun setTheme(v: ThemeMode) = viewModelScope.launch { container.settings.setTheme(v) }
    fun setUserAgent(v: String) = viewModelScope.launch { container.settings.setUserAgent(v) }
    fun setSearchEngine(v: SearchEngine) = viewModelScope.launch { container.settings.setSearchEngine(v) }
    fun setInterceptDownloads(v: Boolean) = viewModelScope.launch { container.settings.setInterceptDownloads(v) }
    fun setConvertHlsToMp4(v: Boolean) = viewModelScope.launch { container.settings.setConvertHlsToMp4(v) }
    fun setYoutubeQuality(v: YoutubeQuality) = viewModelScope.launch { container.settings.setYoutubeQuality(v) }

    // ---- crash reports ----

    private val crashReporter = (app as XdmApp).crashReporter
    val crashReportsEnabled: StateFlow<Boolean> = crashReporter.enabled
    val crashReportsAvailable: Boolean = crashReporter.available

    fun setCrashReportsEnabled(v: Boolean) = crashReporter.setEnabled(v)
    suspend fun sendTestCrashReport(): TestReportResult = crashReporter.sendTest()

    // ---- ad blocker ----

    val filterListsInfo: StateFlow<FilterListsInfo> = container.filterLists.info
    val filterListsUpdate: StateFlow<UpdateState> = container.filterLists.updateState

    fun setBlockAds(v: Boolean) = viewModelScope.launch { container.settings.setBlockAds(v) }
    fun setPopupMode(v: PopupMode) = viewModelScope.launch { container.settings.setPopupMode(v) }
    fun setFilterListEnabled(id: String, enabled: Boolean) = viewModelScope.launch { container.settings.setFilterListEnabled(id, enabled) }
    fun removeAllowedSite(site: String) = viewModelScope.launch { container.settings.setAdSiteAllowed(site, false) }

    /** Runs in the app scope: leaving the Settings screen must not abort a half-finished download. */
    fun updateFilterLists() {
        container.scope.launch { container.filterLists.update() }
    }

    fun clearFilterListsResult() = container.filterLists.clearUpdateResult()

    /** Text of a bundled third-party license (shown under Settings → About & licenses). */
    fun licenseText(asset: String): String = runCatching {
        getApplication<Application>().assets.open(asset).bufferedReader().use { it.readText() }
    }.getOrDefault("")
}
