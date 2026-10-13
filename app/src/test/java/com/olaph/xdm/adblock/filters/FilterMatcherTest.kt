package com.olaph.xdm.adblock.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching semantics on small inline rule sets (real PSL). */
class FilterMatcherTest {
    private fun engine(vararg rules: String, hosts: Collection<String> = emptyList()) = TestLists.engineOf(*rules, hosts = hosts)

    private fun FilterEngine.blocks(url: String, page: String = "https://news.example.org/", type: Int = RequestType.SCRIPT) =
        match(url, type, page(page)).blocked

    // ---- anchors, separators, wildcards ----

    @Test fun hostAnchorMatchesHostAndSubdomainsOnly() {
        val e = engine("||ads.com^\$script")
        assertTrue(e.blocks("https://ads.com/x.js"))
        assertTrue(e.blocks("https://cdn.ads.com/x.js"))
        assertTrue(e.blocks("http://ads.com:8080/x.js"))
        assertFalse(e.blocks("https://notads.com/x.js"))
        assertFalse(e.blocks("https://ads.com.evil.org/x.js"))
        assertFalse("not in the path", e.blocks("https://example.com/ads.com/x.js"))
    }

    @Test fun plainHostRulesGoThroughTheHostSet() {
        val e = engine("||popads.example^", "||adassets.example^\$third-party")
        assertTrue(e.blocks("https://go.popads.example/smartpop/abc", type = RequestType.UNKNOWN))
        assertTrue(e.blocks("https://assets.adassets.example/a.png", type = RequestType.IMAGE))
        assertFalse("first-party", e.blocks("https://assets.adassets.example/a.png", page = "https://www.adassets.example/", type = RequestType.IMAGE))
    }

    @Test fun separatorMatchesPunctuationAndTheEnd() {
        val e = engine("||example.com^ads^")
        assertTrue(e.blocks("https://example.com/ads/x"))
        assertTrue(e.blocks("https://example.com/ads?x=1"))
        assertTrue(e.blocks("https://example.com/ads"))
        assertFalse(e.blocks("https://example.com/adsx"))
        assertFalse("'.' is not a separator", e.blocks("https://example.com/ads.js"))
    }

    @Test fun startAndEndAnchors() {
        val e = engine("|https://track.", "/pixel.gif|")
        assertTrue(e.blocks("https://track.example.com/a"))
        assertFalse(e.blocks("https://example.com/?u=https://track.x"))
        assertTrue(e.blocks("https://example.com/img/pixel.gif"))
        assertFalse(e.blocks("https://example.com/img/pixel.gif?x=1"))
    }

    @Test fun wildcardsAndSubstrings() {
        val e = engine("/banners/*/ad_", "-ad-300x250.")
        assertTrue(e.blocks("https://site.com/banners/2024/ad_1.png"))
        assertFalse(e.blocks("https://site.com/banners/ad_1.png"))
        assertTrue(e.blocks("https://cdn.site.com/img/top-ad-300x250.jpg"))
        assertFalse(e.blocks("https://cdn.site.com/img/top-ad-300x251.jpg"))
    }

    @Test fun matchingIsCaseInsensitiveUnlessMatchCase() {
        assertTrue(engine("/AdServer/").blocks("https://x.com/adserver/a"))
        val mc = engine("/AdServer/\$match-case")
        assertTrue(mc.blocks("https://x.com/AdServer/a"))
        assertFalse(mc.blocks("https://x.com/adserver/a"))
    }

    @Test fun regexRules() {
        val e = engine("/^https?:\\/\\/[a-z]{8}\\.com\\/[a-z0-9]{6}\\.js\$/\$script", "/[unclosed/")
        assertTrue(e.blocks("https://abcdefgh.com/a1b2c3.js"))
        assertFalse(e.blocks("https://abcdefgh.com/a1b2c3.css"))
        assertFalse(e.blocks("https://abc.com/a1b2c3.js"))
        assertFalse("an invalid regex never matches (and never throws)", e.blocks("https://x.com/[unclosed"))
    }

    // ---- domain= ----

    @Test fun domainOptionWithNegationAndEntities() {
        val e = engine("/ads.js\$domain=site.com|~sub.site.com|example.*")
        assertTrue(e.blocks("https://cdn.x.net/ads.js", page = "https://site.com/"))
        assertTrue(e.blocks("https://cdn.x.net/ads.js", page = "https://www.site.com/"))
        assertFalse(e.blocks("https://cdn.x.net/ads.js", page = "https://sub.site.com/"))
        assertFalse(e.blocks("https://cdn.x.net/ads.js", page = "https://deep.sub.site.com/"))
        assertFalse(e.blocks("https://cdn.x.net/ads.js", page = "https://other.com/"))
        // entity: example.* matches every public suffix
        assertTrue(e.blocks("https://cdn.x.net/ads.js", page = "https://example.co.uk/"))
        assertTrue(e.blocks("https://cdn.x.net/ads.js", page = "https://www.example.com.br/"))
        assertFalse(e.blocks("https://cdn.x.net/ads.js", page = "https://notexample.com/"))
    }

    @Test fun exclusionOnlyDomainList() {
        val e = engine("/ads.js\$domain=~good.com")
        assertTrue(e.blocks("https://cdn.x.net/ads.js", page = "https://any.org/"))
        assertFalse(e.blocks("https://cdn.x.net/ads.js", page = "https://good.com/"))
    }

    @Test fun tokenlessRulesScopedToSitesAreFound() {
        val e = engine("*\$script,3p,domain=cuttlinks.com")
        assertTrue(e.blocks("https://cdn.other.net/x.js", page = "https://cuttlinks.com/abc"))
        assertFalse(e.blocks("https://cuttlinks.com/x.js", page = "https://cuttlinks.com/abc"))
        assertFalse(e.blocks("https://cdn.other.net/x.js", page = "https://elsewhere.com/"))
    }

    // ---- third-party via eTLD+1 ----

    @Test fun thirdPartyUsesTheRegistrableDomain() {
        val e = engine("||cdn.example.co.uk^\$third-party", "||alice.github.io^\$third-party")
        assertFalse(e.blocks("https://cdn.example.co.uk/a.js", page = "https://b.example.co.uk/"))
        assertTrue(e.blocks("https://cdn.example.co.uk/a.js", page = "https://other.co.uk/"))
        assertTrue("github.io is a public suffix", e.blocks("https://alice.github.io/a.js", page = "https://bob.github.io/"))
        assertFalse(e.blocks("https://alice.github.io/a.js", page = "https://alice.github.io/page"))
    }

    @Test fun firstPartyOption() {
        val e = engine("/ads/\$1p")
        assertTrue(e.blocks("https://www.site.com.br/ads/a.js", page = "https://site.com.br/"))
        assertFalse(e.blocks("https://cdn.other.com/ads/a.js", page = "https://site.com.br/"))
    }

    // ---- precedence ----

    @Test fun exceptionBeatsBlock() {
        val e = engine("||ads.com^", "@@||ads.com/allowed/\$script")
        assertTrue(e.blocks("https://ads.com/blocked.js"))
        val r = e.match("https://ads.com/allowed/x.js", RequestType.SCRIPT, e.page("https://news.org/"))
        assertEquals(MatchResult.Verdict.ALLOW, r.verdict)
        assertTrue("exception has types", e.blocks("https://ads.com/allowed/x.png", type = RequestType.IMAGE))
    }

    @Test fun importantBeatsException() {
        val e = engine("||ads.com^\$important", "@@||ads.com^")
        assertTrue(e.blocks("https://ads.com/x.js"))
        val e2 = engine("||ads.com^\$important", "@@||ads.com^\$important")
        assertFalse("important exception beats important block", e2.blocks("https://ads.com/x.js"))
    }

    @Test fun documentExceptionAllowsEverythingOnThatPage() {
        val e = engine("||ads.com^", "@@||trusted.org^\$document")
        assertTrue(e.blocks("https://ads.com/x.js", page = "https://news.org/"))
        assertFalse(e.blocks("https://ads.com/x.js", page = "https://trusted.org/page"))
        assertTrue(e.page("https://www.trusted.org/").documentAllowed)
    }

    @Test fun badfilterRemovesTheRuleFromAnyList() {
        val e = engine("||ads.com^", "/banner/\$image", "||ads.com^\$badfilter", "/banner/\$image,badfilter")
        assertFalse(e.blocks("https://ads.com/x.js"))
        assertFalse(e.blocks("https://x.com/banner/1.png", type = RequestType.IMAGE))
    }

    @Test fun hostsListNeverBlocksThePagesOwnEntry() {
        val e = engine(hosts = listOf("ads.example.com"))
        assertTrue(e.blocks("https://ads.example.com/a.js"))
        assertFalse(e.blocks("https://static.ads.example.com/a.js", page = "https://www.ads.example.com/"))
    }

    // ---- types ----

    @Test fun typeRestrictedRulesOnlyMatchCompatibleTypes() {
        val e = engine("/ads/\$image", "/track/")
        val img = "https://x.com/ads/1.png"
        assertTrue(e.blocks(img, type = RequestType.IMAGE))
        assertFalse(e.blocks(img, type = RequestType.SCRIPT))
        assertFalse("unknown type: only type-less rules", e.blocks("https://x.com/ads/1", type = RequestType.UNKNOWN))
        assertTrue(e.blocks("https://x.com/track/1", type = RequestType.UNKNOWN))
        assertTrue(e.blocks("https://x.com/track/1", type = RequestType.XHR))
    }

    @Test fun inferredScriptTypeLetsScriptRulesMatch() {
        val e = engine("/adlib.\$script")
        val url = "https://x.com/adlib.js"
        assertTrue(e.blocks(url, type = RequestTypeInference.infer(url, isMainFrame = false)))
        assertFalse(e.blocks("https://x.com/adlib.php", type = RequestTypeInference.infer("https://x.com/adlib.php", isMainFrame = false)))
    }

    @Test fun negatedTypes() {
        val e = engine("/promo/\$~image")
        assertTrue(e.blocks("https://x.com/promo/a.js", type = RequestType.SCRIPT))
        assertFalse(e.blocks("https://x.com/promo/a.png", type = RequestType.IMAGE))
    }

    @Test fun popupRulesOnlyApplyToPopups() {
        val e = engine("||popads.net^\$popup")
        val page = e.page("https://news.org/")
        assertFalse(e.match("https://popads.net/x.js", RequestType.SCRIPT, page).blocked)
        assertFalse(e.match("https://popads.net/", RequestType.DOCUMENT, page).blocked)
        assertTrue(e.match("https://popads.net/", RequestType.POPUP, page).blocked)
        assertTrue(e.isAdPopup("https://www.popads.net/go", page))
    }

    @Test fun documentNavigationsOnlyMatchHostLevelAndDocumentRules() {
        val e = engine("||adhost.com^", "/landing/ads/", "||clicks.net^\$document")
        val page = e.page("https://news.org/")
        assertTrue(e.match("https://adhost.com/", RequestType.DOCUMENT, page).blocked)
        assertTrue(e.match("https://clicks.net/r?id=1", RequestType.DOCUMENT, page).blocked)
        assertFalse("path rules never block whole pages", e.match("https://shop.com/landing/ads/", RequestType.DOCUMENT, page).blocked)
        assertTrue(e.isAdPopup("https://adhost.com/pop", page))
    }

    @Test fun nonHttpUrlsAreNeverMatched() {
        val e = engine("||ads.com^", "ads")
        assertFalse(e.blocks("data:text/plain,ads"))
        assertFalse(e.blocks("about:blank"))
    }

    @Test fun matchReportsTheRule() {
        val e = engine("||ads.com^")
        assertEquals("||ads.com^", e.match("https://x.ads.com/a", RequestType.SCRIPT, e.page("https://n.org/")).rule)
    }
}
