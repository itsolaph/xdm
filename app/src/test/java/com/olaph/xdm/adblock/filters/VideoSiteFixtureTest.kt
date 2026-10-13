package com.olaph.xdm.adblock.filters

import com.olaph.xdm.adblock.HostNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Requests of a video site with a live-cam ad widget, in the shape Chrome reports them
 * (`xdm-fixture-videosite.json`, invented hosts), checked against the real bundled lists plus inline rules for the
 * invented ad networks: every request to those networks is blocked, the site's own content and CDNs are not.
 */
class VideoSiteFixtureTest {
    private class Entry(val page: String, val type: String, val url: String)

    private val entries: List<Entry> by lazy {
        val text = File("src/test/resources/xdm-fixture-videosite.json").readText()
        // Unrolled string pattern: no per-character recursion in java.util.regex on the long DOM snapshot strings.
        val str = "\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\""
        val re = Regex("\\{\\s*\"page\":\\s*$str,\\s*\"type\":\\s*$str,\\s*\"url\":\\s*$str\\s*}")
        re.findAll(text).map { m -> Entry(unescape(m.groupValues[1]), m.groupValues[2], unescape(m.groupValues[3])) }.toList()
    }

    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { out.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue }
                'n' -> out.append('\n')
                't' -> out.append('\t')
                else -> out.append(n)
            }
            i += 2
        }
        return out.toString()
    }

    private val livecamSites = "~livecam-a.example|~livecam-b.example"

    /** What the bundled lists do not know about the invented networks, written the way a list author would. */
    private val inlineRules = listOf(
        "||adnet.example^",
        "||popads.example^",
        "||popads2.example^",
        "||clickpass.example^",
        "||spotcdn.example^\$third-party,domain=$livecamSites",
        "||spotmedia.example^\$third-party,domain=$livecamSites",
        "||spothls.example^\$third-party,domain=$livecamSites",
        "||livecam-a.example^\$third-party",
        "||livecam-b.example^\$third-party",
        "||livecam-c.example^\$third-party",
        "||livecam-d.example^\$third-party",
        "videohub.example##.banner-container",
        "videohub.example##.banner-widget",
        "videohub.example##div[class*=\"bottomRight--\"][class*=\"root--\"]",
        "clipsite.example##.simpleToast",
        "clipsite.example##.video-wrap > div.right",
        "/api/spots/*\$domain=clipsite.example",
        "/\\.[a-z]{2,6}\\/[0-9A-Za-z]{5,8}\\.js\$/\$script,3p,domain=clipsite.example",
    )

    private val engine by lazy { TestLists.buildWith(inlineRules) }

    private val adHosts = listOf(
        "adnet.example", "popads.example", "popads2.example", "clickpass.example", "spotcdn.example", "spotmedia.example",
        "spothls.example", "livecam-a.example", "livecam-b.example", "livecam-c.example", "livecam-d.example",
    )
    private val siteHosts = listOf(
        "videohub.example", "cdn.videohub-media.example", "cdn.videohub-static.example",
        "cdnjs.cloudflare.com", "fonts.googleapis.com", "fonts.gstatic.com",
    )

    private fun isAd(url: String): Boolean {
        val host = HostNames.hostOf(url) ?: return false
        return adHosts.any { host == it || host.endsWith(".$it") }
    }

    /** Chrome's resource types → ours; a "Document" that is not the page itself is an iframe. */
    private fun chromeType(e: Entry): Int = when (e.type) {
        "Script" -> RequestType.SCRIPT
        "Stylesheet" -> RequestType.STYLESHEET
        "Image" -> RequestType.IMAGE
        "Font" -> RequestType.FONT
        "Fetch", "XHR" -> RequestType.XHR
        "Media" -> RequestType.MEDIA
        "Document" -> if (e.url == e.page) RequestType.DOCUMENT else RequestType.SUBDOCUMENT
        "Ping" -> RequestType.PING
        "WebSocket" -> RequestType.WEBSOCKET
        else -> RequestType.OTHER
    }

    private val requests get() = entries.filter { it.type != "DomSnapshot" && it.url != it.page }

    /** Requests the capture does not contain (assets, HLS and config calls of the live-cam widget). */
    private val extraAdRequests = listOf(
        "https://assets.spotcdn.example/assets/icons/favicon-livecam.png" to RequestType.IMAGE,
        "https://img.spotcdn.example/thumbs/1700000003/10000003_webp" to RequestType.IMAGE,
        "https://media-hls.spothls.example/b-hls-12/10000001/10000001_240p.m3u8" to RequestType.MEDIA,
        "https://edge-hls.spothls.example/hls/10000001/master/10000001_auto.m3u8?playlistType=lowLatency" to RequestType.XHR,
        "https://edge-hls.spothls.example/hls/10000001/10000001_240p_1234.mp4" to RequestType.UNKNOWN,
        "https://livecam-a.example/api/front/v2/config" to RequestType.XHR,
        "https://livecam-b.example/checkUrl" to RequestType.XHR,
    )

    @Test fun fixtureLoads() {
        assertTrue("fixture has ${entries.size} entries", entries.size > 100)
        assertEquals(2, entries.count { it.type == "DomSnapshot" })
        assertTrue(requests.count { isAd(it.url) } >= 40)
    }

    @Test fun everyAdRequestIsBlockedWithChromeTypes() {
        for (e in requests.filter { isAd(it.url) }) {
            val page = engine.page(e.page)
            val r = engine.match(e.url, chromeType(e), page)
            assertTrue("${e.type} ${e.url} should be blocked", r.blocked)
        }
    }

    @Test fun everyAdRequestIsBlockedWithInferredTypes() {
        // In the WebView we only have the URL (and sometimes headers): the inferred type must do as well.
        for (e in requests.filter { isAd(it.url) }) {
            val type = RequestTypeInference.infer(e.url, isMainFrame = false)
            assertTrue("${e.url} (inferred ${RequestType.name(type)}) should be blocked", engine.match(e.url, type, engine.page(e.page)).blocked)
        }
    }

    @Test fun adNetworkRequestsMissingFromTheCaptureAreBlockedToo() {
        val page = engine.page("https://videohub.example/")
        for ((url, type) in extraAdRequests) assertTrue("$url should be blocked", engine.match(url, type, page).blocked)
    }

    @Test fun theSiteAndItsCdnsAreNeverBlocked() {
        for (e in requests.filter { r -> HostNames.hostOf(r.url).let { h -> siteHosts.any { h == it } } }) {
            val page = engine.page(e.page)
            val r1 = engine.match(e.url, chromeType(e), page)
            assertFalse("${e.url} must not be blocked (${r1.rule})", r1.blocked)
            val inferred = RequestTypeInference.infer(e.url, isMainFrame = false)
            val r2 = engine.match(e.url, inferred, page)
            assertFalse("${e.url} must not be blocked as ${RequestType.name(inferred)} (${r2.rule})", r2.blocked)
        }
        // Also the top-level navigation itself.
        assertFalse(engine.match("https://videohub.example/", RequestType.DOCUMENT, engine.page("https://www.google.com/")).blocked)
    }

    @Test fun adHlsNeverReachesTheVideoList() {
        // The browser pipeline: AdBlocker.decide() first, only SNIFF results go to the media collector.
        val blocker = com.olaph.xdm.adblock.AdBlocker().apply { configure(true, emptySet()); setEngine(engine) }
        val collector = com.olaph.xdm.media.MediaCollector()
        val page = blocker.page("https://videohub.example/watch/clip-0414/")
        val urls = listOf(
            "https://media-hls.spothls.example/b-hls-12/10000001/10000001_240p.m3u8",
            "https://edge-hls.spothls.example/hls/10000001/master/10000001_auto.m3u8",
            "https://edge-hls.spothls.example/hls/10000001/10000001_240p_1234.ts",
            "https://video.streamplayer.example/hls/movie/master.m3u8",
        )
        val offered = ArrayList<String>()
        for (u in urls) {
            val type = RequestTypeInference.infer(u, isMainFrame = false)
            when (blocker.decide(u, type, page, isMainFrame = false, isGet = true)) {
                com.olaph.xdm.adblock.RequestAction.SNIFF -> if (collector.offer(u) != null) offered += u
                else -> Unit
            }
            // URLs reported by the page's DOM go through the same filter.
            if (!blocker.shouldBlockMediaUrl(u, page)) collector.offer(u)
        }
        assertEquals(listOf("https://video.streamplayer.example/hls/movie/master.m3u8"), offered)
        assertTrue(offered.none { it.contains("spothls") })
    }

    @Test fun smartpopPopupsAreAds() {
        val page = engine.page("https://videohub.example/")
        assertTrue(engine.isAdPopup("https://go.popads.example/smartpop/21f8eb5d?userId=1", page))
        assertTrue(engine.isAdPopup("https://go.popads2.example/smartpop/4c5f3853?userId=1", page))
        assertTrue(engine.isAdPopup("https://go.adnet.example/?realDomain=creative.adnet.example&campaignId=1", page))
        assertFalse(engine.isAdPopup("https://videohub.example/watch/clip-0414/", page))
        assertFalse(engine.isAdPopup("https://github.com/", page))
    }

    @Test fun cosmeticRulesForTheVideoSite() {
        val page = engine.page("https://videohub.example/")
        val selectors = engine.specificSelectors("videohub.example", page)
        assertTrue(selectors.toString(), ".banner-container" in selectors)
        assertTrue(".banner-widget" in selectors)
        val widget = "div[class*=\"bottomRight--\"][class*=\"root--\"]"
        assertTrue(widget in selectors)
        // The selector really describes the captured widget: its classes contain both fragments.
        val snapshotClasses = entries.filter { it.type == "DomSnapshot" }.map { Regex("\"cls\": \"([^\"]*)\"").find(it.url)!!.groupValues[1] }
        assertTrue(snapshotClasses.all { it.contains("bottomRight--") && it.contains("root--") })
        // …and works on subdomains of the site too.
        assertTrue(widget in engine.specificSelectors("www.videohub.example", engine.page("https://www.videohub.example/")))
        assertFalse(widget in engine.specificSelectors("example.com", engine.page("https://example.com/")))
    }

    @Test fun cosmeticRulesForASecondSite() {
        val page = engine.page("https://clipsite.example/")
        val selectors = engine.specificSelectors("clipsite.example", page)
        assertTrue(selectors.toString(), ".simpleToast" in selectors)
        assertTrue(".video-wrap > div.right" in selectors)
    }

    @Test fun bundledListsStillHideSiteSpecificElements() {
        // A real EasyList rule (`nhl.com##.-leaderboard`), so the fixture engine keeps using the bundled cosmetic rules.
        val selectors = engine.specificSelectors("nhl.com", engine.page("https://nhl.com/"))
        assertTrue(selectors.toString(), ".-leaderboard" in selectors)
    }

    @Test fun adApiAndThirdPartyScriptsAreBlockedOnTheSite() {
        val page = engine.page("https://clipsite.example/123456.html")
        // `/api/spots/*$domain=clipsite.example`
        assertTrue(engine.match("https://clipsite.example/api/spots/12?x=1", RequestType.XHR, page).blocked)
        // A regex rule: third-party scripts named like /<5-8 chars>.js, only on that site.
        assertTrue(engine.match("https://randomads.xyz/AbC12x.js", RequestType.SCRIPT, page).blocked)
        assertFalse(engine.match("https://randomads.xyz/AbC12x.js", RequestType.SCRIPT, engine.page("https://example.org/")).blocked)
    }
}
