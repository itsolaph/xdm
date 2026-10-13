package com.olaph.xdm.adblock

import com.olaph.xdm.media.MediaCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {
    private fun blocker(enabled: Boolean = true, allowed: Set<String> = emptySet(), domains: Set<String> = emptySet()) = AdBlocker().apply {
        configure(enabled, allowed)
        setMatcher(DomainMatcher(domains))
    }

    private val default get() = blocker(domains = setOf("ads.example.com", "adnet.io"))

    // ---- block / pass ----

    @Test fun blocksListedHostAndSubdomains() {
        val b = default
        assertTrue(b.shouldBlock("ads.example.com", "news.org"))
        assertTrue(b.shouldBlock("cdn.adnet.io", "news.org"))
        assertFalse(b.shouldBlock("example.com", "news.org"))
        assertFalse(b.shouldBlock("notadnet.io", "news.org"))
    }

    @Test fun nothingIsBlockedUntilTheListIsLoaded() {
        val b = AdBlocker().apply { configure(true, emptySet()) }
        assertFalse(b.isLoaded)
        assertFalse(b.shouldBlock("ads.example.com", "news.org"))
        b.setMatcher(DomainMatcher(setOf("ads.example.com")))
        assertTrue(b.shouldBlock("ads.example.com", "news.org"))
    }

    @Test fun nothingIsBlockedBeforeSettingsAreRead() {
        val b = AdBlocker().apply { setMatcher(DomainMatcher(setOf("ads.example.com"))) }
        assertFalse(b.shouldBlock("ads.example.com", "news.org"))
    }

    @Test fun masterSwitchOffDisablesEverything() {
        val b = blocker(enabled = false, domains = setOf("ads.example.com"))
        assertFalse(b.shouldBlock("ads.example.com", "news.org"))
        assertFalse(b.isActiveFor("news.org"))
        assertEquals(RequestAction.SNIFF, b.decide("ads.example.com", "news.org", isMainFrame = false, isGet = true))
    }

    @Test fun swappingTheListTakesEffectImmediately() {
        val b = default
        assertTrue(b.shouldBlock("ads.example.com", "news.org"))
        b.setMatcher(DomainMatcher(setOf("other.net")))
        assertFalse(b.shouldBlock("ads.example.com", "news.org"))
        assertTrue(b.shouldBlock("other.net", "news.org"))
    }

    // ---- allowlist precedence ----

    @Test fun allowlistedSiteIsNeverFiltered() {
        val b = blocker(allowed = setOf("news.org"), domains = setOf("ads.example.com"))
        assertFalse(b.shouldBlock("ads.example.com", "news.org"))
        assertFalse(b.shouldBlock("ads.example.com", "m.news.org")) // subdomain of an allowed site
        assertTrue(b.shouldBlock("ads.example.com", "other.org"))
        assertFalse(b.isActiveFor("news.org"))
        assertTrue(b.isActiveFor("other.org"))
    }

    @Test fun allowlistBeatsBlocklistEvenWhenTheSiteItselfIsListed() {
        val b = blocker(allowed = setOf("ads.example.com"), domains = setOf("ads.example.com", "tracker.net"))
        assertFalse(b.shouldBlock("tracker.net", "ads.example.com"))
    }

    @Test fun allowlistEntryForASubdomainDoesNotCoverTheParent() {
        val b = blocker(allowed = setOf("m.news.org"), domains = setOf("ads.example.com"))
        assertFalse(b.shouldBlock("ads.example.com", "m.news.org"))
        assertTrue(b.shouldBlock("ads.example.com", "news.org"))
    }

    @Test fun allowedEntryReportsWhichEntryMatched() {
        val b = blocker(allowed = setOf("news.org"))
        assertEquals("news.org", b.allowedEntry("a.b.news.org"))
        assertNull(b.allowedEntry("othernews.org"))
    }

    @Test fun configureReplacesTheAllowlist() {
        val b = default
        b.configure(true, setOf("news.org"))
        assertFalse(b.shouldBlock("ads.example.com", "news.org"))
        b.configure(true, emptySet())
        assertTrue(b.shouldBlock("ads.example.com", "news.org"))
    }

    // ---- a page never blocks itself ----

    @Test fun visitingAListedSiteKeepsItsOwnResources() {
        val b = default
        assertFalse(b.shouldBlock("ads.example.com", "ads.example.com"))
        assertFalse(b.shouldBlock("static.ads.example.com", "www.ads.example.com")) // same blocklist entry
        assertTrue(b.shouldBlock("adnet.io", "ads.example.com")) // but other listed hosts still go
    }

    // ---- decide: ordering of block vs. sniff ----

    @Test fun mainFrameIsNeverBlocked() {
        assertEquals(RequestAction.PASS, default.decide("ads.example.com", "news.org", isMainFrame = true, isGet = true))
    }

    @Test fun blockedSubresourceIsNeverSniffed() {
        assertEquals(RequestAction.BLOCK, default.decide("ads.example.com", "news.org", isMainFrame = false, isGet = true))
        assertEquals(RequestAction.BLOCK, default.decide("ads.example.com", "news.org", isMainFrame = false, isGet = false))
    }

    @Test fun cleanGetSubresourceIsSniffedAndPostIsPassed() {
        assertEquals(RequestAction.SNIFF, default.decide("cdn.news.org", "news.org", isMainFrame = false, isGet = true))
        assertEquals(RequestAction.PASS, default.decide("cdn.news.org", "news.org", isMainFrame = false, isGet = false))
    }

    @Test fun allowlistedPageSniffsAdHostsToo() {
        val b = blocker(allowed = setOf("news.org"), domains = setOf("ads.example.com"))
        assertEquals(RequestAction.SNIFF, b.decide("ads.example.com", "news.org", isMainFrame = false, isGet = true))
    }

    /** What BrowserViewModel.shouldInterceptRequest does with the decision. */
    private fun run(b: AdBlocker, collector: MediaCollector, pageHost: String, urls: List<String>): List<String> {
        val blocked = mutableListOf<String>()
        for (url in urls) {
            when (b.decide(HostNames.hostOf(url), pageHost, isMainFrame = false, isGet = true)) {
                RequestAction.BLOCK -> blocked += url
                RequestAction.SNIFF -> collector.offer(url)
                RequestAction.PASS -> Unit
            }
        }
        return blocked
    }

    @Test fun blockedAdVideosNeverReachTheMediaCollector() {
        val b = blocker(domains = setOf("adnet.io", "videoads.example.com"))
        val collector = MediaCollector()
        val urls = listOf(
            "https://cdn.adnet.io/preroll/ad-1234.mp4",
            "https://videoads.example.com/vast/master.m3u8",
            "https://videoads.example.com/hls/segment-1.ts",
            "https://cdn.news.org/video/episode-1.mp4",
        )
        val blocked = run(b, collector, "news.org", urls)
        assertEquals(urls.take(3), blocked)

        // The collector saw only the real video: only that URL was ever offered as a candidate.
        assertNotNull(collector.offer("https://cdn.news.org/video/episode-2.mp4"))
        assertNull(collector.offer("https://cdn.news.org/video/episode-1.mp4")) // already offered by the pipeline
        assertNotNull(collector.offer("https://cdn.adnet.io/preroll/ad-1234.mp4")) // proves the collector itself would take it
    }

    @Test fun withTheBlockerOffTheAdVideoIsOffered() {
        val b = blocker(enabled = false, domains = setOf("adnet.io"))
        val collector = MediaCollector()
        run(b, collector, "news.org", listOf("https://cdn.adnet.io/preroll/ad-1234.mp4"))
        assertNull(collector.offer("https://cdn.adnet.io/preroll/ad-1234.mp4")) // seen => it was offered before
    }

    @Test fun mediaUrlsReportedByThePageAreFilteredToo() {
        val b = default
        val page = b.page("https://news.org/")
        assertTrue(b.shouldBlockMediaUrl("https://x.adnet.io/ad.mp4", page))
        assertFalse(b.shouldBlockMediaUrl("https://cdn.news.org/video.mp4", page))
        assertFalse(b.shouldBlockMediaUrl("garbage", page))
    }

    // ---- navigation ----

    @Test fun navigationToAnAdHostIsBlocked() {
        // Script redirects, server redirects and links alike (the address bar never goes through this check; a tap
        // gets an "Open anyway" offer in the browser).
        val b = default
        assertTrue(b.shouldBlockNavigation("https://adnet.io/click?id=1", b.page("https://news.org/story")))
        assertTrue(b.shouldBlockNavigation("https://x.ads.example.com/", b.page("https://news.org/")))
    }

    @Test fun navigationToNormalSitesAndWithinAListedSiteIsAllowed() {
        val b = default
        assertFalse(b.shouldBlockNavigation("https://news.org/next", b.page("https://news.org/")))
        assertFalse(b.shouldBlockNavigation("https://example.com/", b.page("https://news.org/")))
        assertFalse("same blocklist entry as the page", b.shouldBlockNavigation("https://adnet.io/page2", b.page("https://adnet.io/")))
        assertFalse(b.shouldBlockNavigation("intent://scan/#Intent;end", b.page("https://news.org/")))
    }

    @Test fun navigationIsNotBlockedOnAllowlistedSitesOrWithTheSwitchOff() {
        assertFalse(blocker(allowed = setOf("news.org"), domains = setOf("adnet.io")).let { it.shouldBlockNavigation("https://adnet.io/", it.page("https://news.org/")) })
        assertFalse(blocker(enabled = false, domains = setOf("adnet.io")).let { it.shouldBlockNavigation("https://adnet.io/", it.page("https://news.org/")) })
    }

    // ---- pop-ups through the blocker ----

    @Test fun adPopupsAreDiscardedSilentlyInBothBlockingModes() {
        for (mode in listOf(PopupMode.ALL, PopupMode.ADS_ONLY)) {
            val b = default.apply { configure(true, emptySet(), mode) }
            assertEquals(PopupDecision.BLOCK_SILENT, b.decidePopup("https://adnet.io/pop", null, hasGesture = true, userInitiated = false, page = b.page("https://news.org/")))
        }
    }

    @Test fun popupFromAThirdPartyAdFrameIsAnAd() {
        val b = default.apply { configure(true, emptySet(), PopupMode.ADS_ONLY) }
        val page = b.page("https://news.org/")
        assertEquals(PopupDecision.BLOCK_SILENT, b.decidePopup("https://shop.example.net/", "https://ads.example.com/frame.html", true, false, page))
        assertEquals(PopupDecision.ALLOW, b.decidePopup("https://shop.example.net/", "https://news.org/article", true, false, page))
    }

    @Test fun popupModeStillAppliesOnAllowlistedSitesButAdsAreNotSingledOut() {
        val b = blocker(allowed = setOf("news.org"), domains = setOf("adnet.io")).apply { configure(true, setOf("news.org"), PopupMode.ALL) }
        assertEquals(PopupDecision.BLOCK_OFFER, b.decidePopup("https://adnet.io/pop", null, true, false, b.page("https://news.org/")))
        b.configure(true, setOf("news.org"), PopupMode.OFF)
        assertEquals(PopupDecision.ALLOW, b.decidePopup("https://adnet.io/pop", null, false, false, b.page("https://news.org/")))
    }

    // ---- blocked response ----

    @Test fun blockedResponseMimeFollowsTheExtension() {
        assertEquals("application/javascript", BlockedResponse.mimeFor("https://a.com/x/ad.js?v=1"))
        assertEquals("text/css", BlockedResponse.mimeFor("https://a.com/ad.CSS"))
        assertEquals("image/gif", BlockedResponse.mimeFor("https://a.com/pixel.gif#frag"))
        assertEquals("text/html", BlockedResponse.mimeFor("https://a.com/frame.html"))
        assertEquals("text/plain", BlockedResponse.mimeFor("https://a.com/path"))
        assertEquals("text/plain", BlockedResponse.mimeFor("https://a.example.com"))
    }
}
