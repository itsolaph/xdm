package com.olaph.xdm.adblock

import com.olaph.xdm.adblock.filters.FilterEngine
import com.olaph.xdm.adblock.filters.FilterEngineBuilder
import com.olaph.xdm.adblock.filters.MatchResult
import com.olaph.xdm.adblock.filters.PageContext
import com.olaph.xdm.adblock.filters.PublicSuffixList
import com.olaph.xdm.adblock.filters.RequestType

/** What the browser does with one sub-resource request. */
enum class RequestAction {
    /** Ad/tracker: answer with an empty response. Never reaches the media sniffer. */
    BLOCK,

    /** Let it load, and show it to the media sniffer (GET sub-resources). */
    SNIFF,

    /** Let it load untouched (top-level page loads, non-GET). */
    PASS,
}

/**
 * Thread-safe holder of the ad-block state, read from WebView's network threads: the master switch, the
 * per-site allowlist, the pop-up mode and the compiled [FilterEngine] (null until it is loaded, which means
 * "allow everything").
 *
 * The decision functions are pure and need no Android classes, so ordering (block before sniff) and
 * precedence (allowlist before lists, `important` > exception > block) are covered by JVM tests.
 */
class AdBlocker {
    @Volatile private var engine: FilterEngine? = null

    // Off until the settings have been read once, so a page opened right at startup is never blocked by mistake.
    @Volatile var enabled: Boolean = false
        private set
    @Volatile var allowedSites: Set<String> = emptySet()
        private set
    @Volatile var popupMode: PopupMode = PopupMode.ALL
        private set

    val isLoaded: Boolean get() = engine != null
    val currentEngine: FilterEngine? get() = engine

    /** Atomically replaces the filter set (the previous one keeps serving requests until this returns). */
    fun setEngine(newEngine: FilterEngine?) {
        engine = newEngine
        clearCssCache()
    }

    /** Hosts-only filter set (used by tests and as the smallest possible engine). */
    fun setMatcher(matcher: DomainMatcher?, psl: PublicSuffixList = PublicSuffixList.FALLBACK) {
        setEngine(matcher?.let { FilterEngineBuilder(psl).apply { addHosts(it.domains) }.build() })
    }

    fun configure(enabled: Boolean, allowedSites: Set<String>, popupMode: PopupMode = this.popupMode) {
        this.enabled = enabled
        this.allowedSites = allowedSites
        this.popupMode = popupMode
        clearCssCache()
    }

    /** The allowlist entry that covers [pageHost] (the host itself or a parent domain), or null. */
    fun allowedEntry(pageHost: String?): String? = HostNames.matchWalk(pageHost, allowedSites)

    fun isSiteAllowed(pageHost: String?): Boolean = allowedEntry(pageHost) != null

    /** True when ads are filtered on this page: switch on, lists loaded and the site not allowlisted. */
    fun isActiveFor(pageHost: String?): Boolean = enabled && engine != null && !isSiteAllowed(pageHost)

    /** Engine view of the top-level page (host, site, page-level `@@` exceptions). Cheap; compute once per page. */
    fun page(pageUrl: String?): PageContext = engine?.page(pageUrl) ?: PageContext.NONE

    private fun pageForHost(pageHost: String?): PageContext = page(pageHost?.let { "https://$it/" })

    // ---- requests ----

    /** Full match for a request of [type] (see [RequestType]); NONE while inactive for the page. */
    fun match(url: String, type: Int, page: PageContext): MatchResult {
        val e = engine ?: return MatchResult.NONE
        if (!enabled || isSiteAllowed(page.info.host)) return MatchResult.NONE
        return e.match(url, type, page)
    }

    fun shouldBlockRequest(url: String, type: Int, page: PageContext): Boolean = match(url, type, page).blocked

    /**
     * The one place that orders blocking before media sniffing. Main-frame requests are never blocked here:
     * the user chose to visit them (self-started navigations are handled by [shouldBlockNavigation]), and explicit
     * downloads (download listener, long-press, video sheet) do not go through this path at all.
     */
    fun decide(url: String, type: Int, page: PageContext, isMainFrame: Boolean, isGet: Boolean): RequestAction = when {
        isMainFrame -> RequestAction.PASS
        shouldBlockRequest(url, type, page) -> RequestAction.BLOCK
        isGet -> RequestAction.SNIFF
        else -> RequestAction.PASS
    }

    /** Media URLs reported by page scripts (DOM scan) get the same treatment as network requests. */
    fun shouldBlockMediaUrl(url: String, page: PageContext): Boolean = shouldBlockRequest(url, RequestType.MEDIA, page)

    /**
     * A top-level navigation that did not come from the address bar (link, script, server redirect) to a
     * destination matched by a `$document` or host-level rule: click-jacking redirects and pop-unders.
     */
    fun shouldBlockNavigation(targetUrl: String, page: PageContext): Boolean {
        if (HostNames.hostOf(targetUrl) == null) return false
        return shouldBlockRequest(targetUrl, RequestType.DOCUMENT, page)
    }

    /**
     * Pop-up decision for a window the page opened (see [PopupPolicy]). The pop-up mode is its own setting; the ad
     * switch and the per-site allowlist only decide whether ads are singled out (and discarded without asking).
     */
    fun decidePopup(targetUrl: String?, openerUrl: String?, hasGesture: Boolean, userInitiated: Boolean, page: PageContext): PopupDecision {
        val e = engine
        val active = e != null && enabled && !isSiteAllowed(page.info.host) && !page.documentAllowed
        val isAd = active && targetUrl != null && HostNames.hostOf(targetUrl) != null && e!!.isAdPopup(targetUrl, page)
        val openerIsAd = active && openerUrl != null && run {
            val openerHost = HostNames.hostOf(openerUrl)
            openerHost != null && e!!.psl.isThirdParty(openerHost, page.info.host) && e.shouldBlock(openerUrl, RequestType.SUBDOCUMENT, page)
        }
        return PopupPolicy.decide(popupMode, PopupPolicy.PopupRequest(targetUrl, userInitiated, hasGesture, isAd, openerIsAd))
    }

    // ---- cosmetic ----

    /** Per-host stylesheet cache (access-ordered LRU; every access is synchronized, bridge calls come from many threads). */
    private val cssCache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    private fun clearCssCache() = synchronized(cssCache) { cssCache.clear() }

    /**
     * Stylesheet for a document on [frameHost] (the top page or a frame) of [page]: site-specific selectors plus
     * the always-on generic ones. Empty while inactive.
     */
    fun cosmeticCss(frameHost: String?, page: PageContext): String {
        val e = engine ?: return ""
        if (!enabled || isSiteAllowed(page.info.host) || !page.cosmeticEnabled) return ""
        val key = "${HostNames.normalize(frameHost)}|${page.flags}"
        synchronized(cssCache) { cssCache[key]?.let { return it } }
        val css = CosmeticCss.rules(e.specificSelectors(frameHost, page) + e.highGenericSelectors(frameHost, page))
        synchronized(cssCache) { cssCache[key] = css }
        return css
    }

    /** Stylesheet for the generic rules keyed by the classes/ids (`.x`, `#y`) a document reported. */
    fun genericCss(frameHost: String?, page: PageContext, keys: Collection<String>): String {
        val e = engine ?: return ""
        if (!enabled || isSiteAllowed(page.info.host)) return ""
        return CosmeticCss.rules(e.genericSelectors(frameHost, page, keys))
    }

    // ---- host-level helpers (allowlist UI, tests) ----

    /**
     * Should a request to [requestHost] made by the page at [pageHost] be blocked by a host-level rule? The
     * allowlist wins; a page never blocks itself (visiting a listed domain keeps that site's own resources).
     */
    fun shouldBlock(requestHost: String?, pageHost: String?): Boolean {
        val host = HostNames.normalize(requestHost) ?: return false
        return shouldBlockRequest("https://$host/", RequestType.UNKNOWN, pageForHost(HostNames.normalize(pageHost)))
    }

    /** Host-based [decide] (tests). */
    fun decide(requestHost: String?, pageHost: String?, isMainFrame: Boolean, isGet: Boolean): RequestAction {
        val host = HostNames.normalize(requestHost)
        return when {
            isMainFrame -> RequestAction.PASS
            host != null && shouldBlock(host, pageHost) -> RequestAction.BLOCK
            isGet -> RequestAction.SNIFF
            else -> RequestAction.PASS
        }
    }
}

/** Turns selectors into CSS: one rule each, so an invalid selector only drops itself. */
object CosmeticCss {
    const val DECLARATION = "{display:none!important}"

    fun rules(selectors: Collection<String>): String {
        if (selectors.isEmpty()) return ""
        val sb = StringBuilder(selectors.size * 40)
        for (s in selectors) sb.append(s).append(DECLARATION).append('\n')
        return sb.toString()
    }
}

/** Placeholder bodies for blocked requests: an empty 204 whose type matches what the page asked for. */
object BlockedResponse {
    fun mimeFor(url: String): String {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return when (path.substringAfterLast('.', "")) {
            "js", "mjs" -> "application/javascript"
            "css" -> "text/css"
            "gif" -> "image/gif"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            else -> "text/plain"
        }
    }
}
