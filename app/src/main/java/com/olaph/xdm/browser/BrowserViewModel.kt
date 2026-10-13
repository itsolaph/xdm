package com.olaph.xdm.browser

import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.olaph.xdm.XdmApp
import com.olaph.xdm.adblock.BlockedResponse
import com.olaph.xdm.adblock.HostNames
import com.olaph.xdm.adblock.PopupDecision
import com.olaph.xdm.adblock.PopupMode
import com.olaph.xdm.adblock.RequestAction
import com.olaph.xdm.adblock.filters.PageContext
import com.olaph.xdm.adblock.filters.RequestTypeInference
import com.olaph.xdm.core.PendingDownload
import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.BookmarkEntity
import com.olaph.xdm.data.HistoryEntity
import com.olaph.xdm.engine.DownloadHeaders
import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.engine.UrlInput
import com.olaph.xdm.engine.UrlUtils
import com.olaph.xdm.engine.UserAgents
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.youtube.YoutubeLink
import com.olaph.xdm.youtube.YoutubeUrls
import com.olaph.xdm.media.DetectedMedia
import com.olaph.xdm.media.MediaAnalysis
import com.olaph.xdm.media.MediaCandidate
import com.olaph.xdm.media.MediaClassifier
import com.olaph.xdm.media.MediaOption
import com.olaph.xdm.media.MediaProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream

enum class BrowserPage { TAB, TABS, BOOKMARKS, HISTORY }

/** What was long-pressed: a link, an image, or an image inside a link. */
class LinkTarget(val tabId: Int, val linkUrl: String?, val imageUrl: String?, val videoUrl: String? = null)

class FileChooserRequest(
    val callback: ValueCallback<Array<Uri>>,
    val params: WebChromeClient.FileChooserParams,
) {
    /** The picker was started for this request (guards against re-launching after recomposition). */
    var launched = false
}

/**
 * All browser state. Lives in the activity's ViewModel store, so tabs (and their WebViews, hence the
 * pages) survive rotation and switching to the Downloads tab and back.
 *
 * WebViews are created with a [MutableContextWrapper]: its base is the application while detached and
 * the activity while attached, so no destroyed activity is ever retained.
 */
// Profile APIs are guarded by `profilesSupported` (WebViewFeature.MULTI_PROFILE), which lint cannot see.
@android.annotation.SuppressLint("RequiresFeature")
class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as XdmApp).container
    private val dao = container.browserDao
    private val adBlocker = container.adBlocker

    val tabs = mutableStateListOf<BrowserTab>()
    var currentId by mutableIntStateOf(-1)
        private set
    val current: BrowserTab? get() = tabs.firstOrNull { it.id == currentId }

    var page by mutableStateOf(BrowserPage.TAB)
    var settings by mutableStateOf(AppSettings())
        private set
    var darkTheme by mutableStateOf(false)
        private set
    var linkMenu by mutableStateOf<LinkTarget?>(null)
    var fileChooser by mutableStateOf<FileChooserRequest?>(null)
        private set

    /** Set by the activity UI: opens the "Add download" sheet. */
    var downloadHandler: ((PendingDownload) -> Unit)? = null

    /** Opens the YouTube sheet (set by the main screen, like [downloadHandler]). */
    var youtubeHandler: ((YoutubeLink) -> Unit)? = null

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        dao.observeBookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history: StateFlow<List<HistoryEntity>> =
        dao.observeHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var nextId = 1
    private var visits = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val incognitoHosts = mutableSetOf<String>() // only used when profiles are unavailable
    private val mediaProbe = MediaProbe(container.httpClient)
    private val probeGate = Semaphore(3)

    private val documentStartSupported: Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)

    private val profilesSupported: Boolean =
        runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    init {
        viewModelScope.launch { container.settings.flow.collect { settings = it } }
        // A previous process may have died with incognito tabs open; their profile must not outlive them.
        if (profilesSupported) runCatching { ProfileStore.getInstance().deleteProfile(INCOGNITO_PROFILE) }
        newTab()
    }

    // ---- tabs ----

    fun newTab(
        url: String? = null,
        incognito: Boolean = false,
        openedByPage: Boolean = false,
        openerId: Int = -1,
    ): BrowserTab {
        val tab = BrowserTab(nextId++, incognito, openedByPage, openerId)
        tabs.add(tab)
        currentId = tab.id
        page = BrowserPage.TAB
        if (url != null) navigate(tab, url)
        return tab
    }

    fun selectTab(id: Int) {
        if (tabs.any { it.id == id }) currentId = id
        page = BrowserPage.TAB
    }

    fun closeTab(id: Int) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        destroy(tab)
        if (tabs.isEmpty()) {
            newTab()
        } else if (currentId == id) {
            currentId = tabs.firstOrNull { it.id == tab.openerId }?.id ?: tabs[(index - 1).coerceAtLeast(0)].id
        }
        if (tab.incognito && tabs.none { it.incognito }) clearIncognitoData()
    }

    fun closeAllTabs() {
        tabs.toList().forEach { closeTab(it.id) }
    }

    // ---- navigation ----

    /** Loads what the user typed: a URL, or search terms for the configured search engine. */
    fun load(input: String) {
        val tab = current ?: return
        navigate(tab, UrlInput.resolve(input, settings.searchEngine))
    }

    fun openUrl(url: String) {
        val tab = current ?: newTab()
        navigate(tab, url)
        page = BrowserPage.TAB
    }

    private fun navigate(tab: BrowserTab, url: String) {
        tab.showHome = false
        tab.url = url
        tab.title = ""
        webViewFor(tab).loadUrl(url)
    }

    fun goBack() { current?.webView?.takeIf { it.canGoBack() }?.goBack() }
    fun goForward() { current?.webView?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { current?.webView?.reload() }
    fun stop() { current?.webView?.stopLoading() }
    fun goHome() { current?.showHome = true }

    /**
     * System back: closes overlays, then walks the WebView history, then closes a page-opened tab.
     * Returns false when the browser has nothing left to go back to (the caller leaves the screen).
     */
    fun handleBack(): Boolean {
        if (page != BrowserPage.TAB) { page = BrowserPage.TAB; return true }
        val tab = current ?: return false
        if (tab.findActive) { closeFind(tab); return true }
        if (!tab.showHome && tab.webView?.canGoBack() == true) { tab.webView?.goBack(); return true }
        if (tab.showHome && tab.hasPage) { tab.showHome = false; return true }
        if (tab.openedByPage && tabs.size > 1) { closeTab(tab.id); return true }
        return false
    }

    fun toggleDesktop(tab: BrowserTab) {
        tab.desktop = !tab.desktop
        val wv = tab.webView ?: return
        applyUserAgent(tab, wv)
        if (!tab.showHome) wv.reload()
    }

    // ---- find in page ----

    fun startFind(tab: BrowserTab) { tab.findActive = true }

    fun find(tab: BrowserTab, query: String) {
        tab.findQuery = query
        val wv = tab.webView ?: return
        if (query.isEmpty()) { wv.clearMatches(); tab.findCount = 0; tab.findIndex = 0 } else wv.findAllAsync(query)
    }

    fun findNext(tab: BrowserTab, forward: Boolean) { tab.webView?.findNext(forward) }

    fun closeFind(tab: BrowserTab) {
        tab.findActive = false
        tab.findQuery = ""
        tab.webView?.clearMatches()
    }

    // ---- bookmarks / history ----

    fun isBookmarked(url: String?): Boolean = url != null && bookmarks.value.any { it.url == url }

    fun toggleBookmark(tab: BrowserTab) {
        val url = tab.url?.takeIf { UrlUtils.isHttpUrl(it) } ?: return
        viewModelScope.launch {
            if (bookmarks.value.any { it.url == url }) dao.removeBookmark(url)
            else dao.addBookmark(BookmarkEntity(title = tab.displayTitle, url = url))
        }
    }

    fun removeBookmark(id: Long) = viewModelScope.launch { dao.removeBookmark(id) }
    fun removeHistory(id: Long) = viewModelScope.launch { dao.removeHistory(id) }
    fun clearHistory() = viewModelScope.launch { dao.clearHistory() }

    private fun recordVisit(tab: BrowserTab) {
        if (tab.incognito) return
        val url = tab.url?.takeIf { UrlUtils.isHttpUrl(it) } ?: return
        val title = tab.displayTitle
        viewModelScope.launch {
            dao.upsertHistory(HistoryEntity(title = title, url = url))
            if (++visits % 50 == 0) dao.trimHistory()
        }
    }

    // ---- downloads ----

    /** Opens the "Add download" sheet for a link/image the user picked from the context menu. */
    fun downloadLink(tabId: Int, url: String) {
        if (!UrlUtils.isHttpUrl(url)) { toast("Only http(s) links can be downloaded"); return }
        val tab = tabs.firstOrNull { it.id == tabId }
        val ua = tab?.webView?.settings?.userAgentString ?: settings.userAgent
        val headers = DownloadHeaders.fromBrowser(cookiesFor(tab, url), ua, tab?.url)
        downloadHandler?.invoke(
            PendingDownload(url, headers, FilenameUtils.resolve(null, url, null), pageUrl = tab?.url),
        )
    }

    private fun onDownloadStart(tab: BrowserTab, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long) {
        if (!UrlUtils.isHttpUrl(url)) {
            // blob: and data: URLs only exist inside the page; there is nothing to fetch from outside.
            toast("This download can't be handled outside the page")
            return
        }
        val name = FilenameUtils.resolve(contentDisposition, url, mimeType?.takeIf { it.isNotBlank() })
        if (settings.interceptDownloads) {
            val headers = DownloadHeaders.fromBrowser(cookiesFor(tab, url), userAgent, tab.url)
            downloadHandler?.invoke(PendingDownload(url, headers, name, mimeType, length, tab.url))
        } else {
            systemDownload(tab, url, userAgent, mimeType, name)
        }
        // A link that opened a blank tab only to start a download leaves nothing worth keeping.
        if (tab.openedByPage && !tab.hasPage && tabs.size > 1) closeTab(tab.id)
    }

    private fun systemDownload(tab: BrowserTab, url: String, userAgent: String?, mimeType: String?, name: String) {
        runCatching {
            val request = android.app.DownloadManager.Request(Uri.parse(url))
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            mimeType?.takeIf { it.isNotBlank() }?.let { request.setMimeType(it) }
            userAgent?.let { request.addRequestHeader("User-Agent", it) }
            cookiesFor(tab, url)?.let { request.addRequestHeader("Cookie", it) }
            tab.url?.let { request.addRequestHeader("Referer", it) }
            getApplication<Application>().getSystemService(android.app.DownloadManager::class.java).enqueue(request)
        }.onSuccess { toast("Downloading $name") }
            .onFailure { toast("Could not start download: ${it.message}") }
    }

    private fun cookiesFor(tab: BrowserTab?, url: String): String? {
        if (tab != null && tab.incognito && profilesSupported) {
            return runCatching { ProfileStore.getInstance().getProfile(INCOGNITO_PROFILE)?.cookieManager?.getCookie(url) }.getOrNull()
        }
        return CookieManager.getInstance().getCookie(url)
    }

    // ---- video detection ----

    /**
     * YouTube pages are not sniffed: the player's requests are signed googlevideo fragments that are no use as files.
     * The download button there opens the YouTube sheet ([openYoutube]) instead, which asks the extractor.
     */
    fun isYoutube(tab: BrowserTab) = MediaClassifier.isYoutubePage(tab.url)

    /**
     * The download button on a YouTube page. m.youtube.com is a single-page app, so the address is read from the
     * WebView at the moment of the tap instead of trusting what was current when the button was composed.
     */
    fun openYoutube(tab: BrowserTab) {
        val link = YoutubeUrls.parse(tab.webView?.url) ?: YoutubeUrls.parse(tab.url)
        if (link == null) {
            toast("Open a YouTube video or playlist first")
            return
        }
        youtubeHandler?.invoke(link)
    }

    /** Called for every request of the tab, on WebView's network thread. Never blocks and never alters the request. */
    private fun onMediaRequest(tab: BrowserTab, url: String, referer: String?) {
        if (isYoutube(tab)) return
        val candidate = tab.media.offer(url, referer ?: tab.url) ?: return
        mainHandler.post { launchProbe(tab, candidate, openSheetWhenFound = false) }
    }

    private fun onPlayEvent(tab: BrowserTab, url: String) {
        if (isYoutube(tab)) return
        if (url.isBlank()) tab.media.markPlayingManifest() else tab.media.markPlaying(url)
        publishMedia(tab, pulse = false)
    }

    private fun launchProbe(tab: BrowserTab, candidate: MediaCandidate, openSheetWhenFound: Boolean) {
        if (candidate.epoch != tab.media.epoch) return
        val ua = tab.webView?.settings?.userAgentString ?: settings.userAgent
        val headers = DownloadHeaders.fromBrowser(cookiesFor(tab, candidate.url), ua, candidate.referer ?: tab.url, withOrigin = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val analysis = probeGate.withPermit { mediaProbe.analyze(candidate.url, candidate.kind, ua, headers) }
                if (analysis is MediaAnalysis.Found && tab.media.add(candidate, analysis)) {
                    publishMedia(tab, pulse = true)
                    if (openSheetWhenFound) mainHandler.post { tab.mediaSheetOpen = true }
                } else if (openSheetWhenFound) {
                    mainHandler.post { toast("No downloadable video found") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                tab.media.release(candidate) // transient (timeout, 5xx): a later request for the same URL may try again
            }
        }
    }

    private fun publishMedia(tab: BrowserTab, pulse: Boolean) {
        mainHandler.post {
            tab.mediaItems = tab.media.snapshot()
            if (pulse) tab.mediaPulse++
        }
    }

    /** New top-level page (or SPA route): forget what the previous page played. */
    private fun resetMedia(tab: BrowserTab, url: String) {
        if (tab.media.reset(url)) {
            tab.touchedVideo = null
            publishMedia(tab, pulse = false)
        }
    }

    /** Long-press on a `<video>`: [url] is the element's source, or "blob" for MSE players. */
    fun downloadVideoFromMenu(tabId: Int, url: String) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        if (isYoutube(tab)) { openYoutube(tab); return }
        when {
            url == "blob" || !UrlUtils.isHttpUrl(url) ->
                if (tab.mediaItems.isNotEmpty()) tab.mediaSheetOpen = true else toast("No downloadable video found on this page yet")
            MediaClassifier.classify(url).let { it == MediaClassifier.Kind.VIDEO_FILE || it == MediaClassifier.Kind.AUDIO_FILE } ->
                downloadLink(tabId, url)
            else -> {
                val known = tab.mediaItems.any { it.url == url || it.options.any { o -> o.url == url } }
                if (known || tab.mediaItems.isNotEmpty()) {
                    tab.mediaSheetOpen = true
                } else {
                    val kind = MediaClassifier.classify(url).takeIf { it != MediaClassifier.Kind.IGNORE } ?: MediaClassifier.Kind.UNKNOWN_MEDIA
                    val c = MediaCandidate(url, MediaClassifier.normalize(url), kind, tab.url, tab.media.epoch)
                    toast("Checking video…")
                    launchProbe(tab, c, openSheetWhenFound = true)
                }
            }
        }
    }

    /** Opens the "Add download" sheet for one choice from the video list. */
    fun downloadMedia(tab: BrowserTab, media: DetectedMedia, option: MediaOption) {
        if (!option.supported) return
        val ua = tab.webView?.settings?.userAgentString ?: settings.userAgent
        val headers = DownloadHeaders.fromBrowser(cookiesFor(tab, option.url), ua, media.referer ?: tab.url, withOrigin = true)
        val title = tab.title.ifBlank { UrlInput.displayHost(tab.url).orEmpty() }
        val name = if (title.isBlank()) {
            FilenameUtils.resolve(null, option.url, option.mimeType)
        } else {
            FilenameUtils.sanitize("$title.${option.extension}")
        }
        downloadHandler?.invoke(
            PendingDownload(
                url = option.url,
                headers = headers,
                fileName = name,
                mimeType = option.mimeType,
                contentLength = option.sizeBytes,
                pageUrl = tab.url,
                kind = if (option.hls) DownloadKind.HLS else DownloadKind.HTTP,
                lengthEstimated = option.sizeEstimated,
            ),
        )
        tab.mediaSheetOpen = false
    }

    // ---- file chooser ----

    fun completeFileChooser(uris: Array<Uri>?) {
        fileChooser?.callback?.onReceiveValue(uris)
        fileChooser = null
    }

    // ---- theme ----

    fun updateDarkTheme(dark: Boolean) {
        darkTheme = dark
        tabs.forEach { t -> t.webView?.let { applyDark(it) } }
    }

    private fun applyDark(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, darkTheme)
        }
    }

    fun flushCookies() = CookieManager.getInstance().flush()

    // ---- WebView lifecycle ----

    val appContext: Context get() = getApplication()

    fun webViewFor(tab: BrowserTab): WebView {
        tab.webView?.let { return it }
        val wv = createWebView(tab)
        tab.webView = wv
        tab.restoreUrl?.let { tab.restoreUrl = null; wv.loadUrl(it) }
        return wv
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: BrowserTab): WebView {
        val wv = WebView(MutableContextWrapper(getApplication()))
        var isolated = false
        if (tab.incognito && profilesSupported) {
            isolated = runCatching { WebViewCompat.setProfile(wv, INCOGNITO_PROFILE) }.isSuccess
        }
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Pages never get to read local files or app content.
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            // Every window.open reaches onCreateWindow, where the pop-up blocker (Settings) decides and counts it.
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            if (tab.incognito && !isolated) cacheMode = WebSettings.LOAD_NO_CACHE
        }
        tab.baseUserAgent = wv.settings.userAgentString
        if (tab.desktop) applyUserAgent(tab, wv)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        applyDark(wv)

        wv.webViewClient = TabClient(tab)
        wv.webChromeClient = TabChromeClient(tab)
        wv.setDownloadListener { url, ua, cd, mime, len -> onDownloadStart(tab, url, ua, cd, mime, len) }
        wv.setFindListener { index, count, _ ->
            tab.findIndex = index
            tab.findCount = count
        }
        wv.setOnLongClickListener { v -> onLongPress(tab, v as WebView) }
        installMediaDetection(tab, wv)
        return wv
    }

    private fun installMediaDetection(tab: BrowserTab, wv: WebView) {
        wv.addJavascriptInterface(
            MediaBridge(
                // URLs the page itself reports (DOM scan) are filtered too: an ad's <video src> must not be offered.
                mediaSink = { url, _ -> if (!adBlocker.shouldBlockMediaUrl(url, contextFor(tab))) onMediaRequest(tab, url, null) },
                playSink = { url -> onPlayEvent(tab, url) },
                touchSink = { url ->
                    tab.touchedVideo = url.ifEmpty { null }
                    tab.touchedAt = SystemClock.uptimeMillis()
                },
            ),
            MediaScript.BRIDGE_NAME,
        )
        wv.addJavascriptInterface(
            AdBridge(
                active = { adBlocker.isActiveFor(tab.pageHost) && contextFor(tab).cosmeticEnabled },
                // A frame reports its own host; the top-level page's context decides exceptions and the allowlist.
                css = { host -> adBlocker.cosmeticCss(host ?: tab.pageHost, contextFor(tab)) },
                generic = { host, keys -> adBlocker.genericCss(host ?: tab.pageHost, contextFor(tab), keys) },
            ),
            AdScript.BRIDGE_NAME,
        )
        // Runs in every frame before the page's own scripts where supported; otherwise page start/commit/finish inject it.
        if (documentStartSupported) {
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, MediaScript.SOURCE, setOf("*")) }
            runCatching { WebViewCompat.addDocumentStartJavaScript(wv, AdScript.SOURCE, setOf("*")) }
        }
    }

    // ---- ad blocking ----

    private enum class Blocked { REQUEST, POPUP, REDIRECT }

    private fun countBlocked(tab: BrowserTab, kind: Blocked = Blocked.REQUEST) {
        when (kind) {
            Blocked.REQUEST -> tab.blockedRequestCounter.incrementAndGet()
            Blocked.POPUP -> tab.blockedPopupCounter.incrementAndGet()
            Blocked.REDIRECT -> tab.blockedRedirectCounter.incrementAndGet()
        }
        // WebView's network threads can fire hundreds of times a second: publish to Compose at most once per post.
        if (tab.blockedPublishPending.compareAndSet(false, true)) {
            mainHandler.postDelayed({ publishBlocked(tab) }, BLOCKED_PUBLISH_MS)
        }
    }

    private fun publishBlocked(tab: BrowserTab) {
        tab.blockedPublishPending.set(false)
        tab.blockedRequests = tab.blockedRequestCounter.get()
        tab.blockedPopups = tab.blockedPopupCounter.get()
        tab.blockedRedirects = tab.blockedRedirectCounter.get()
    }

    private fun resetBlocked(tab: BrowserTab) {
        tab.blockedRequestCounter.set(0)
        tab.blockedPopupCounter.set(0)
        tab.blockedRedirectCounter.set(0)
        tab.blockedRequests = 0
        tab.blockedPopups = 0
        tab.blockedRedirects = 0
    }

    /** New top-level page in [tab] (network thread or main thread). */
    private fun setPage(tab: BrowserTab, url: String) {
        if (tab.pageUrl == url) return
        tab.pageUrl = url
        tab.pageHost = HostNames.hostOf(url)
    }

    /**
     * The engine's view of the tab's page, recomputed when the page or the engine (list update, toggle) changes.
     * Thread-safe: racing threads compute the same value.
     */
    private fun contextFor(tab: BrowserTab): PageContext {
        val key = adBlocker.currentEngine to tab.pageUrl
        val cached = tab.pageContext
        if (cached != null && tab.pageContextKey == key) return cached
        return adBlocker.page(tab.pageUrl).also {
            tab.pageContext = it
            tab.pageContextKey = key
        }
    }

    /** Something blocked that the user may still want ("Pop-up blocked — Open"); null when nothing is offered. */
    var notice by mutableStateOf<BlockNotice?>(null)

    fun dismissNotice(n: BlockNotice) {
        if (notice === n) notice = null
    }

    /** "Open" on a blocked pop-up or navigation: the user asked for it, so it bypasses the blocker. */
    fun openNotice(n: BlockNotice) {
        dismissNotice(n)
        when (n.kind) {
            BlockNotice.Kind.POPUP -> {
                val opener = tabs.firstOrNull { it.id == n.tabId }
                newTab(n.url, incognito = opener?.incognito == true, openedByPage = true, openerId = n.tabId)
            }
            BlockNotice.Kind.NAVIGATION -> {
                val tab = tabs.firstOrNull { it.id == n.tabId } ?: return
                tab.bypassNavigationBlock = true
                selectTab(tab.id)
                navigate(tab, n.url)
            }
        }
    }

    // ---- pop-ups ----

    /**
     * Judges a page-opened window on its first URL (called from its WebView client, on the main or a network
     * thread; only the first call decides). Blocked windows are destroyed without ever being shown.
     */
    private fun decidePopup(child: BrowserTab, url: String?, referer: String?): PopupDecision {
        val p = child.pendingPopup ?: return PopupDecision.ALLOW
        if (!p.decided.compareAndSet(false, true)) return p.decision
        val target = url?.takeIf { UrlUtils.isHttpUrl(it) }
        val d = adBlocker.decidePopup(target, referer, p.hasGesture, userInitiated = false, page = p.openerContext)
        p.decision = d
        mainHandler.post { applyPopupDecision(child, target, d) }
        return d
    }

    private fun applyPopupDecision(child: BrowserTab, url: String?, d: PopupDecision) {
        val p = child.pendingPopup ?: return
        child.pendingPopup = null
        pendingPopups -= child
        val opener = p.opener
        when (d) {
            PopupDecision.ALLOW -> {
                if (opener !in tabs) { destroy(child); return }
                tabs.add(child)
                currentId = child.id
                page = BrowserPage.TAB
            }
            PopupDecision.BLOCK_SILENT -> {
                destroy(child)
                countBlocked(opener, Blocked.POPUP)
            }
            PopupDecision.BLOCK_OFFER -> {
                destroy(child)
                countBlocked(opener, Blocked.POPUP)
                if (url != null && opener in tabs) notice = BlockNotice(BlockNotice.Kind.POPUP, url, opener.id)
            }
        }
    }

    private val pendingPopups = mutableListOf<BrowserTab>()

    /** Empty 204 with a matching type: scripts/styles/images simply fail to load, frames stay blank. */
    private fun blockedResponse(url: String) = WebResourceResponse(
        BlockedResponse.mimeFor(url), "UTF-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )

    /** Is ad blocking switched off for the page in [tab] (site allowlisted)? Drives the shield icon and menu. */
    fun isSiteAllowed(tab: BrowserTab): Boolean = HostNames.matchWalk(HostNames.hostOf(tab.url), settings.adAllowedSites) != null

    /** "Allow ads on this site" toggle: persists the choice and reloads so it takes effect. */
    fun setSiteAllowed(tab: BrowserTab, allowed: Boolean) {
        val host = HostNames.hostOf(tab.url) ?: return
        val current = adBlocker.allowedSites
        val entry = if (allowed) HostNames.siteKey(host) else HostNames.matchWalk(host, current)
        if (entry == null) return
        // Apply to the live filter first: the reload below must not race the DataStore round trip.
        adBlocker.configure(adBlocker.enabled, if (allowed) current + entry else current - entry)
        viewModelScope.launch { container.settings.setAdSiteAllowed(entry, allowed) }
        tab.webView?.reload()
    }

    private fun applyUserAgent(tab: BrowserTab, wv: WebView) {
        val base = tab.baseUserAgent ?: return
        wv.settings.userAgentString = if (tab.desktop) UserAgents.desktop(base) else base
    }

    private fun onLongPress(tab: BrowserTab, wv: WebView): Boolean {
        val hit = wv.hitTestResult
        val extra = hit.extra
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                if (extra.isNullOrBlank()) return false
                linkMenu = LinkTarget(tab.id, extra, null)
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                if (extra.isNullOrBlank()) return false
                linkMenu = LinkTarget(tab.id, null, extra)
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // `extra` is the image; the link it points to has to be asked for.
                val image = extra
                val handler = Handler(Looper.getMainLooper()) { msg ->
                    val link = msg.data.getString("url")
                    linkMenu = LinkTarget(tab.id, link?.takeIf { it.isNotBlank() }, image ?: msg.data.getString("src"))
                    true
                }
                wv.requestFocusNodeHref(handler.obtainMessage())
            }
            else -> {
                // `<video>` elements have no HitTestResult type; the page script reports touches on them.
                val video = tab.touchedVideo
                if (video == null || SystemClock.uptimeMillis() - tab.touchedAt > VIDEO_TOUCH_WINDOW_MS) return false
                tab.touchedVideo = null
                linkMenu = LinkTarget(tab.id, null, null, videoUrl = video)
            }
        }
        return true
    }

    private fun destroy(tab: BrowserTab) {
        val wv = tab.webView ?: return
        tab.webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.webViewClient = WebViewClient()
        wv.setDownloadListener(null)
        wv.destroy()
    }

    private fun onRenderGone(tab: BrowserTab, wv: WebView) {
        if (tab.webView !== wv) return
        // Keep the app alive: drop the dead WebView; the next composition creates one and reloads the page.
        tab.restoreUrl = tab.url
        tab.webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
        tab.loading = false
        tab.progress = 0
        tab.webViewEpoch++ // makes the host recompose and attach a fresh WebView
        mainHandler.post { toast("The page crashed and was reloaded") }
    }

    private fun clearIncognitoData() {
        if (profilesSupported) {
            runCatching {
                val store = ProfileStore.getInstance()
                store.getProfile(INCOGNITO_PROFILE)?.let { p ->
                    p.cookieManager.removeAllCookies(null)
                    p.webStorage.deleteAllData()
                }
                // Fails while a destroyed WebView still references the profile; the data is already gone,
                // and the profile is removed again on the next start.
                store.deleteProfile(INCOGNITO_PROFILE)
            }
        } else {
            // Shared cookie jar: only expire the cookies of hosts that were visited in incognito tabs.
            val cm = CookieManager.getInstance()
            for (host in incognitoHosts) {
                for (scheme in listOf("https", "http")) {
                    val url = "$scheme://$host/"
                    cm.getCookie(url)?.split(';')?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }?.forEach { name ->
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=$host")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$host")
                    }
                }
            }
            incognitoHosts.clear()
            cm.flush()
        }
    }

    override fun onCleared() {
        pendingPopups.toList().forEach { destroy(it) }
        pendingPopups.clear()
        tabs.toList().forEach { destroy(it) }
        if (tabs.any { it.incognito }) clearIncognitoData()
        tabs.clear()
    }

    private fun toast(msg: String) = Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()

    // ---- clients ----

    private inner class TabClient(private val tab: BrowserTab) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (request.isForMainFrame && tab.pendingPopup != null) {
                // First navigation of a window the page opened: the pop-up blocker decides before anything shows.
                if (decidePopup(tab, url, request.requestHeaders["Referer"]) != PopupDecision.ALLOW) return true
            } else if (request.isForMainFrame && !tab.bypassNavigationBlock && adBlocker.shouldBlockNavigation(url, contextFor(tab))) {
                // Not typed in the address bar (loadUrl never gets here): click-jacking, pop-unders, ad redirects.
                countBlocked(tab, Blocked.REDIRECT)
                // A tap the user meant (a link to a listed site) can still be followed from the snackbar.
                if (request.hasGesture()) notice = BlockNotice(BlockNotice.Kind.NAVIGATION, url, tab.id)
                return true
            }
            val outcome = ExternalLinks.handle(view.context, url, request.hasGesture())
            return when (outcome) {
                ExternalLinks.Outcome.LoadInPage -> false
                ExternalLinks.Outcome.Consumed -> true
                is ExternalLinks.Outcome.Fallback -> {
                    if (request.isForMainFrame) view.loadUrl(outcome.url)
                    true
                }
            }
        }

        // Ad blocking comes first, so a blocked request (an ad network's pre-roll, say) never reaches the media
        // sniffer. Everything else is only observed: null = WebView loads it normally.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (request.isForMainFrame) {
                if (tab.pendingPopup != null && decidePopup(tab, url, request.requestHeaders["Referer"]) != PopupDecision.ALLOW) {
                    return blockedResponse(url)
                }
                setPage(tab, url)
            }
            // WebView does not say what a request is for: infer it (headers, extension), unknown = type-less rules only.
            val type = RequestTypeInference.infer(url, request.isForMainFrame, request.requestHeaders.orEmpty())
            return when (adBlocker.decide(url, type, contextFor(tab), request.isForMainFrame, request.method == "GET")) {
                RequestAction.BLOCK -> {
                    countBlocked(tab)
                    blockedResponse(url)
                }
                RequestAction.SNIFF -> {
                    onMediaRequest(tab, url, request.requestHeaders["Referer"])
                    null
                }
                RequestAction.PASS -> null
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            setPage(tab, url)
            resetBlocked(tab)
            // Where document-start scripts are unsupported, the cosmetic filter is injected here, on commit and on finish.
            if (!documentStartSupported) view.evaluateJavascript(AdScript.SOURCE, null)
            resetMedia(tab, url)
            tab.url = url
            tab.title = ""
            tab.loading = true
            tab.showHome = false
            if (tab.incognito) UrlInput.displayHost(url)?.let { incognitoHosts += it.substringBefore(':') }
            syncNav(view)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (!documentStartSupported) view.evaluateJavascript(AdScript.SOURCE, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.bypassNavigationBlock = false
            tab.url = url
            tab.loading = false
            tab.hasPage = true
            if (tab.title.isBlank()) tab.title = view.title.orEmpty()
            syncNav(view)
            recordVisit(tab)
            if (!isYoutube(tab)) view.evaluateJavascript(MediaScript.SOURCE, null) // idempotent; covers WebViews without document-start scripts
            view.evaluateJavascript(AdScript.SOURCE, null) // idempotent as well; late-created slots are still hidden by the style
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (!isReload) resetMedia(tab, url) // single-page apps change the route without a page load
            tab.url = url
            syncNav(view)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRenderGone(tab, view)
            return true
        }

        private fun syncNav(view: WebView) {
            tab.canGoBack = view.canGoBack()
            tab.canGoForward = view.canGoForward()
        }
    }

    private inner class TabChromeClient(private val tab: BrowserTab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            tab.loading = newProgress < 100
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title.orEmpty()
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            fileChooser?.callback?.onReceiveValue(null) // a newer request supersedes the pending one
            fileChooser = FileChooserRequest(callback, params)
            return true
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            if (adBlocker.popupMode == PopupMode.OFF) {
                val child = newTab(incognito = tab.incognito, openedByPage = true, openerId = tab.id)
                child.showHome = false
                transport.webView = webViewFor(child)
                resultMsg.sendToTarget()
                return true
            }
            // Hand the page a real WebView, but keep it out of the tab list until its first URL has been judged:
            // ad pop-ups are destroyed unseen, and an allowed window keeps its `window.opener` link.
            val child = BrowserTab(nextId++, tab.incognito, openedByPage = true, openerId = tab.id)
            child.showHome = false
            child.pendingPopup = PendingPopup(tab, isUserGesture, contextFor(tab))
            pendingPopups += child
            transport.webView = webViewFor(child)
            resultMsg.sendToTarget()
            // A window that never navigates (about:blank filled by script) is judged without a URL.
            mainHandler.postDelayed({ if (child.pendingPopup != null) decidePopup(child, null, null) }, POPUP_DECIDE_TIMEOUT_MS)
            return true
        }

        override fun onCloseWindow(window: WebView) {
            pendingPopups.firstOrNull { it.webView === window }?.let { child ->
                child.pendingPopup = null
                pendingPopups -= child
                destroy(child)
                return
            }
            tabs.firstOrNull { it.webView === window }?.let { closeTab(it.id) }
        }

        // Location, camera and microphone are not supported: deny without prompting.
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }
    }

    private companion object {
        const val INCOGNITO_PROFILE = "xdm_incognito"
        const val VIDEO_TOUCH_WINDOW_MS = 3_000L
        const val BLOCKED_PUBLISH_MS = 250L
        const val POPUP_DECIDE_TIMEOUT_MS = 3_000L
    }
}
