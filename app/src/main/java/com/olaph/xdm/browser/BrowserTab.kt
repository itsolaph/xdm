package com.olaph.xdm.browser

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One browser tab. The Compose-visible fields are snapshot state; the [webView] is created lazily and
 * owned by [BrowserViewModel], so it survives configuration changes and switching bottom tabs.
 */
class BrowserTab(
    val id: Int,
    val incognito: Boolean,
    /** Opened by `window.open` / `target=_blank`; closing it goes back to [openerId]. */
    val openedByPage: Boolean = false,
    val openerId: Int = -1,
) {
    var webView: WebView? = null
    var url by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** Shows the speed-dial home page instead of the WebView. */
    var showHome by mutableStateOf(true)
    var desktop by mutableStateOf(false)

    /** True once a page finished loading in this tab. */
    var hasPage = false

    /** Set when the WebView's renderer died: reload this URL into a fresh WebView. */
    var restoreUrl: String? = null

    /** Bumped when [webView] is replaced so the Compose host re-attaches. */
    var webViewEpoch by mutableIntStateOf(0)
    var baseUserAgent: String? = null

    var findActive by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findIndex by mutableIntStateOf(0)
    var findCount by mutableIntStateOf(0)

    /** Media found on the current page (network sniffing + DOM); thread-safe, reset on navigation. */
    val media = com.olaph.xdm.media.MediaCollector()

    /** [media] as Compose state, for the download button and its sheet. */
    var mediaItems by mutableStateOf<List<com.olaph.xdm.media.DetectedMedia>>(emptyList())

    /** Bumped when a new item appears so the button can pulse. */
    var mediaPulse by mutableIntStateOf(0)
    var mediaSheetOpen by mutableStateOf(false)

    /** URL / normalized host of the page being shown (set on WebView's network thread when the main frame is requested). */
    @Volatile var pageUrl: String? = null
    @Volatile var pageHost: String? = null

    /** The filter engine's view of [pageUrl], cached per page and engine instance (see BrowserViewModel.contextFor). */
    @Volatile var pageContext: com.olaph.xdm.adblock.filters.PageContext? = null
    @Volatile var pageContextKey: Any? = null

    /**
     * What the ad blocker stopped on this page, by kind. The atomics are bumped off-thread; the Compose ints are
     * published at most every 250 ms. [blockedCount] (the shield badge) is their sum.
     */
    val blockedRequestCounter = java.util.concurrent.atomic.AtomicInteger()
    val blockedPopupCounter = java.util.concurrent.atomic.AtomicInteger()
    val blockedRedirectCounter = java.util.concurrent.atomic.AtomicInteger()
    val blockedPublishPending = java.util.concurrent.atomic.AtomicBoolean(false)
    var blockedRequests by mutableIntStateOf(0)
    var blockedPopups by mutableIntStateOf(0)
    var blockedRedirects by mutableIntStateOf(0)
    val blockedCount: Int get() = blockedRequests + blockedPopups + blockedRedirects

    /** Set on a window opened by a page until the pop-up blocker has decided about it (the tab is not shown yet). */
    @Volatile var pendingPopup: PendingPopup? = null

    /** The user chose "Open anyway" after a blocked navigation: let this page load (and its redirects) through. */
    @Volatile var bypassNavigationBlock = false

    /** Last `<video>` the user touched (reported by the page script) for the long-press menu. */
    @Volatile var touchedVideo: String? = null
    @Volatile var touchedAt: Long = 0

    val displayTitle: String
        get() = title.ifBlank { com.olaph.xdm.engine.UrlInput.displayHost(url) ?: "New tab" }
}

/** A window a page opened, held back (not in the tab list) until its first URL is known and judged. */
class PendingPopup(
    val opener: BrowserTab,
    val hasGesture: Boolean,
    val openerContext: com.olaph.xdm.adblock.filters.PageContext,
) {
    val decided = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile var decision: com.olaph.xdm.adblock.PopupDecision = com.olaph.xdm.adblock.PopupDecision.ALLOW
}

/** Something the blocker stopped that the user may still want: shown as a snackbar with an action. */
class BlockNotice(val kind: Kind, val url: String, val tabId: Int) {
    enum class Kind { POPUP, NAVIGATION }
}
