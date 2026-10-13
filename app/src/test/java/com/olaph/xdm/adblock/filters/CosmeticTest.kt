package com.olaph.xdm.adblock.filters

import com.olaph.xdm.adblock.AdBlocker
import com.olaph.xdm.adblock.CosmeticCss
import com.olaph.xdm.browser.AdBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmeticTest {
    private fun engine(vararg rules: String) = TestLists.engineOf(*rules)

    @Test fun specificSelectorsByHostSubdomainAndEntity() {
        val e = engine("site.com##.a", "~m.site.com,site.com##.b", "shop.*##.c", "other.org##.d")
        assertEquals(listOf(".a", ".b"), e.specificSelectors("site.com", e.page("https://site.com/")))
        assertEquals(listOf(".a", ".b"), e.specificSelectors("www.site.com", e.page("https://www.site.com/")))
        assertEquals("negated subdomain", listOf(".a"), e.specificSelectors("m.site.com", e.page("https://m.site.com/")))
        assertEquals(listOf(".c"), e.specificSelectors("shop.co.uk", e.page("https://shop.co.uk/")))
        assertEquals(emptyList<String>(), e.specificSelectors("unrelated.net", e.page("https://unrelated.net/")))
    }

    @Test fun exceptionsRemoveSelectorsOnThatSite() {
        val e = engine("site.com##.a", "site.com##.b", "site.com#@#.a", "##.generic-ad", "site.com#@#.generic-ad")
        val page = e.page("https://site.com/")
        assertEquals(listOf(".b"), e.specificSelectors("site.com", page))
        assertEquals(emptyList<String>(), e.genericSelectors("site.com", page, listOf(".generic-ad")))
        assertEquals(listOf(".generic-ad"), e.genericSelectors("other.com", e.page("https://other.com/"), listOf(".generic-ad")))
    }

    @Test fun genericRulesAreKeyedByClassOrId() {
        val e = engine("##.ad-box", "###banner", "##div.sponsor > a", "##.x:not(.y)", "##a[href^=\"https://ads.\"]", "##div > [data-ad]", "~safe.com##.promo")
        val page = e.page("https://news.org/")
        assertEquals(listOf(".ad-box"), e.genericSelectors("news.org", page, listOf(".ad-box", ".unrelated")))
        assertEquals(listOf("#banner"), e.genericSelectors("news.org", page, listOf("#banner")))
        assertEquals(listOf("div.sponsor > a"), e.genericSelectors("news.org", page, listOf(".sponsor")))
        assertEquals(listOf(".x:not(.y)"), e.genericSelectors("news.org", page, listOf(".x")))
        assertEquals("not keyed by a class inside :not()", emptyList<String>(), e.genericSelectors("news.org", page, listOf(".y")))
        assertEquals("ids and classes are different keys", emptyList<String>(), e.genericSelectors("news.org", page, listOf("#ad-box")))
        // Simple key-less selectors are always applied; complex ones are skipped.
        assertEquals(listOf("a[href^=\"https://ads.\"]"), e.highGenericSelectors("news.org", page))
        // Generic rule with an excluded site.
        assertEquals(listOf(".promo"), e.genericSelectors("news.org", page, listOf(".promo")))
        assertEquals(emptyList<String>(), e.genericSelectors("safe.com", e.page("https://safe.com/"), listOf(".promo")))
    }

    @Test fun pageLevelCosmeticExceptions() {
        val e = engine("##.ad", "##[data-ad]", "site.com##.a", "@@||nogeneric.com^\$generichide", "@@||nohide.com^\$elemhide", "nogeneric.com##.own")
        val ng = e.page("https://nogeneric.com/")
        assertTrue(e.genericSelectors("nogeneric.com", ng, listOf(".ad")).isEmpty())
        assertTrue(e.highGenericSelectors("nogeneric.com", ng).isEmpty())
        assertEquals("specific rules still apply", listOf(".own"), e.specificSelectors("nogeneric.com", ng))
        val nh = e.page("https://www.nohide.com/x")
        assertFalse(nh.cosmeticEnabled)
        assertTrue(e.specificSelectors("nohide.com", nh).isEmpty())
    }

    @Test fun genericKeyExtraction() {
        assertEquals(".ad", CosmeticIndex.genericKey(".ad"))
        assertEquals(".ad-box", CosmeticIndex.genericKey("div.ad-box > span"))
        assertEquals("#top_ad", CosmeticIndex.genericKey("#top_ad"))
        assertEquals(".b", CosmeticIndex.genericKey("a[href=\"#x.y\"] .b"))
        assertNull(CosmeticIndex.genericKey("a[href^=\"http\"]"))
        assertNull(CosmeticIndex.genericKey(":not(.x)"))
        assertNull(CosmeticIndex.genericKey(".md\\:hidden"))
        assertEquals(listOf(".a", "#b", "div[x=\"1,2\"]"), CosmeticIndex.splitList(".a, #b,div[x=\"1,2\"]"))
        assertTrue(CosmeticIndex.isSimple("a[href^=\"https://x y\"]"))
        assertFalse(CosmeticIndex.isSimple("div > a"))
    }

    @Test fun cssIsOneRulePerSelector() {
        assertEquals(".a{display:none!important}\n#b > c{display:none!important}\n", CosmeticCss.rules(listOf(".a", "#b > c")))
        assertEquals("", CosmeticCss.rules(emptyList()))
    }

    @Test fun blockerServesCssOnlyWhenActive() {
        val b = AdBlocker().apply { configure(true, emptySet()); setEngine(engine("site.com##.a", "##.g", "##[data-ad-slot]")) }
        val page = b.page("https://site.com/")
        assertEquals(".a{display:none!important}\n[data-ad-slot]{display:none!important}\n", b.cosmeticCss("site.com", page))
        assertEquals(".g{display:none!important}\n", b.genericCss("site.com", page, listOf(".g")))
        b.configure(true, setOf("site.com"))
        assertEquals("allowlisted site", "", b.cosmeticCss("site.com", page))
        assertEquals("", b.genericCss("site.com", page, listOf(".g")))
        b.configure(false, emptySet())
        assertEquals("switch off", "", b.cosmeticCss("site.com", page))
    }

    @Test fun bridgeIsNarrowAndCapped() {
        val seenKeys = ArrayList<List<String>>()
        val seenHosts = ArrayList<String?>()
        val bridge = AdBridge(
            active = { true },
            css = { host -> seenHosts += host; "css" },
            generic = { _, keys -> seenKeys += keys; "g" },
        )
        assertEquals("css", bridge.css("VideoHub.Example"))
        assertEquals("css", bridge.css("evil host<script>"))
        assertEquals(listOf("videohub.example", null), seenHosts)
        assertEquals("g", bridge.generic("videohub.example", ".a #b c .d " + "x".repeat(300)))
        assertEquals(listOf(".a", "#b", ".d"), seenKeys.single())
        assertEquals("", bridge.generic("videohub.example", null))
        assertEquals("oversized input is refused", "", bridge.generic("videohub.example", ".a ".repeat(30_000)))
        val many = (1..5_000).joinToString(" ") { ".k$it" }
        bridge.generic("videohub.example", many)
        assertEquals(AdBridge.MAX_KEYS, seenKeys.last().size)
        // A failing lookup never throws into the page.
        val broken = AdBridge({ true }, { error("boom") }, { _, _ -> error("boom") })
        assertEquals("", broken.css("x.com"))
        assertEquals("", broken.generic("x.com", ".a"))
    }
}
