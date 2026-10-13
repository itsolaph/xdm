package com.olaph.xdm.adblock.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterParserTest {
    private fun net(line: String): ParsedFilter.Network {
        val f = FilterParser.parse(line)
        assertTrue("$line → $f", f is ParsedFilter.Network)
        return f as ParsedFilter.Network
    }

    private fun cos(line: String): ParsedFilter.Cosmetic {
        val f = FilterParser.parse(line)
        assertTrue("$line → $f", f is ParsedFilter.Cosmetic)
        return f as ParsedFilter.Cosmetic
    }

    private fun skipped(line: String): SkipReason {
        val f = FilterParser.parse(line)
        assertTrue("$line should be skipped, got $f", f is ParsedFilter.Skipped)
        return (f as ParsedFilter.Skipped).reason
    }

    // ---- comments ----

    @Test fun commentsAndHeaders() {
        for (l in listOf("", "   ", "! comment", "[Adblock Plus 2.0]", "# hosts style comment", "#")) {
            assertSame(l, ParsedFilter.Comment, FilterParser.parse(l))
        }
    }

    // ---- network: anchors, wildcards ----

    @Test fun hostAnchorWithSeparatorIsAPlainHostRule() {
        val n = net("||popads.example^")
        assertEquals("popads.example", n.plainHost)
        assertEquals(NetworkRule.ANCHOR_HOST, n.rule.anchor)
        assertEquals(RequestType.TYPELESS, n.rule.types)
    }

    @Test fun thirdPartyHostRuleIsStillAPlainHost() {
        val n = net("||adassets.example^\$third-party")
        assertEquals("adassets.example", n.plainHost)
        assertTrue(n.rule.flags and NetworkRule.THIRD_PARTY != 0)
        assertEquals("adassets.example", net("||adassets.example^\$3p").plainHost)
    }

    @Test fun hostRulesWithMoreThanAHostAreTokenRules() {
        assertNull(net("||example.com/ads/").plainHost)
        assertNull(net("||example.com^\$script").plainHost)
        assertNull(net("||example.com^\$domain=a.com").plainHost)
        assertNull(net("||example.com^\$important").plainHost)
        assertNull("no ^: prefix match, not a host", net("||example.com").plainHost)
        assertNull(net("||example.com^|").plainHost)
    }

    @Test fun anchorsAndWildcardsAreNormalized() {
        net("|https://ads.").rule.let { assertEquals(NetworkRule.ANCHOR_START, it.anchor); assertEquals("https://ads.", it.pattern) }
        net("/banner.gif|").rule.let { assertEquals(NetworkRule.ANCHOR_NONE, it.anchor); assertTrue(it.anchorEnd); assertEquals("/banner.gif", it.pattern) }
        net("*/ads/**/x.js*").rule.let { assertEquals("/ads/*/x.js", it.pattern); assertFalse(it.anchorEnd) }
        assertEquals("lowercased", "/adbanner/*.gif", net("/AdBanner/*.gif").rule.pattern)
        assertEquals("kept with match-case", "/AdBanner/*.gif", net("/AdBanner/*.gif\$match-case").rule.pattern)
        // `/word/` is a regex in ABP/uBO syntax; without metacharacters it is matched as the plain substring `word`.
        assertEquals("adbanner", net("/AdBanner/").rule.pattern)
    }

    @Test fun regexRules() {
        val r = net("/\\.[a-z]{2,6}\\/[0-9A-Za-z]{5,8}\\.js\$/\$script,3p,domain=clipsite.example").rule
        assertTrue(r.isRegex)
        assertEquals(RequestType.SCRIPT, r.types)
        assertTrue(r.flags and NetworkRule.THIRD_PARTY != 0)
        assertNotNull(r.domains)
        // A `$` inside the regex is not the option separator.
        assertTrue(net("/ads\\.js\$/").rule.isRegex)
        // Slashes without regex syntax are a plain substring.
        assertFalse(net("/adbanner/").rule.isRegex)
    }

    @Test fun exceptions() {
        val n = net("@@||example.com/ads.js\$script")
        assertTrue(n.rule.isException)
        assertNull(n.plainHost)
    }

    // ---- network: options ----

    @Test fun typeOptionsAndAliases() {
        assertEquals(RequestType.SCRIPT or RequestType.IMAGE, net("/x/\$script,image").rule.types)
        assertEquals(RequestType.XHR, net("/x/\$xmlhttprequest").rule.types)
        assertEquals(RequestType.XHR, net("/x/\$xhr").rule.types)
        assertEquals(RequestType.SUBDOCUMENT, net("/x/\$frame").rule.types)
        assertEquals(RequestType.STYLESHEET, net("/x/\$css").rule.types)
        assertEquals(RequestType.DOCUMENT, net("||x.com^\$doc").rule.types)
        assertEquals(RequestType.POPUP, net("||popads.example^\$popup").rule.types)
        assertEquals(RequestType.ALL, net("||x.com^\$all").rule.types)
        for (o in listOf("media", "font", "object", "ping", "websocket", "other")) assertEquals(RequestType.BY_OPTION.getValue(o), net("/x/\$$o").rule.types)
    }

    @Test fun negatedTypes() {
        val t = net("/x/\$~image,~script").rule.types
        assertEquals(0, t and RequestType.IMAGE)
        assertEquals(0, t and RequestType.SCRIPT)
        assertTrue(t and RequestType.XHR != 0)
        assertEquals("negations never include unknown/doc/popup", 0, t and (RequestType.UNKNOWN or RequestType.DOCUMENT or RequestType.POPUP))
    }

    @Test fun partyOptions() {
        assertTrue(net("/x/\$~third-party").rule.flags and NetworkRule.FIRST_PARTY != 0)
        assertTrue(net("/x/\$1p").rule.flags and NetworkRule.FIRST_PARTY != 0)
        assertTrue(net("/x/\$first-party").rule.flags and NetworkRule.FIRST_PARTY != 0)
        assertTrue(net("/x/\$~first-party").rule.flags and NetworkRule.THIRD_PARTY != 0)
    }

    @Test fun domainOption() {
        val d = net("/x/\$domain=a.com|~b.a.com|example.*|~foo.*").rule.domains!!
        assertTrue(d.hasIncludes)
        assertTrue(d.hasExclusions)
        assertEquals(listOf("a.com"), d.includedHosts().toList())
        assertEquals(listOf("example"), d.includedEntities().toList())
        assertTrue(skipped("/x/\$domain=") == SkipReason.INVALID)
        assertTrue(skipped("/x/\$domain=a.com/path") == SkipReason.INVALID)
    }

    @Test fun importantMatchCaseRedirect() {
        assertTrue(net("||x.com^\$important").rule.isImportant)
        assertTrue(net("/X/\$match-case").rule.flags and NetworkRule.MATCH_CASE != 0)
        // redirect= blocks the rule's types (no resources are substituted)
        val r = net("||go.popads.example/smartpop/\$xhr,redirect=noopvast-3.0").rule
        assertEquals(RequestType.XHR, r.types)
        assertFalse(r.isException)
        assertTrue(r.flags and NetworkRule.REDIRECT != 0)
    }

    @Test fun pageLevelExceptions() {
        assertTrue(net("@@||site.com^\$document").rule.flags and NetworkRule.PAGE_DOCUMENT != 0)
        assertTrue(net("@@||site.com^\$elemhide").rule.flags and NetworkRule.PAGE_ELEMHIDE != 0)
        assertTrue(net("@@||site.com^\$generichide").rule.flags and NetworkRule.PAGE_GENERICHIDE != 0)
        assertTrue(net("@@||site.com^\$ghide").rule.flags and NetworkRule.PAGE_GENERICHIDE != 0)
        assertTrue(net("@@||site.com^\$specifichide").rule.flags and NetworkRule.PAGE_SPECIFICHIDE != 0)
        assertEquals("elemhide only makes sense on exceptions", SkipReason.INVALID, skipped("||site.com^\$elemhide"))
    }

    @Test fun badfilter() {
        val n = net("||ads.example.com^\$third-party,badfilter")
        assertTrue(n.badfilter)
        assertEquals("||ads.example.com^\$third-party", n.key)
        assertEquals("||x.com^", net("||x.com^\$badfilter").key)
    }

    @Test fun unsupportedOptionsSkipTheRule() {
        for (o in listOf("csp=script-src 'none'", "removeparam=utm_source", "replace=/a/b/", "header=x-ads", "permissions=camera=()",
            "cookie=x", "jsonprune=a", "redirect-rule=noop.js", "denyallow=x.com", "to=x.com", "method=post", "empty", "mp4")) {
            assertEquals(o, SkipReason.UNSUPPORTED_OPTION, skipped("||x.com^\$$o"))
        }
        assertEquals(SkipReason.INVALID, skipped("||x.com^\$nonsenseoption"))
        assertEquals(SkipReason.UNSUPPORTED_COSMETIC, skipped("example.com\$\$script[data-x]"))
    }

    @Test fun ruleMatchingEverythingIsRejectedUnlessScoped() {
        assertEquals(SkipReason.INVALID, skipped("*"))
        assertEquals(SkipReason.INVALID, skipped("\$image"))
        assertNotNull(net("*\$script,domain=example.com").rule.domains)
        assertNotNull(net("\$script,3p,domain=example.com").rule.domains)
    }

    // ---- cosmetic ----

    @Test fun genericAndSpecificHiding() {
        cos("##.ad-banner").let { assertNull(it.domains); assertFalse(it.exception); assertEquals(".ad-banner", it.selector) }
        cos("###ad").let { assertEquals("#ad", it.selector) }
        cos("videohub.example##.banner-container").let { assertEquals(listOf("videohub.example"), it.domains!!.includedHosts().toList()) }
        cos("a.com,~b.a.com,example.*##.x").let {
            assertEquals(listOf("a.com"), it.domains!!.includedHosts().toList())
            assertTrue(it.domains!!.hasExclusions)
            assertEquals(listOf("example"), it.domains!!.includedEntities().toList())
        }
        cos("example.com#@#.ad").let { assertTrue(it.exception) }
        cos("clipsite.example##.video-wrap > div.right").let { assertEquals(".video-wrap > div.right", it.selector) }
        cos("videohub.example##div[class*=\"bottomRight--\"][class*=\"root--\"]")
        cos("##a[href^=\"https://go.adnet.example/\"]")
        cos("example.com##div:has(> .ad)")
    }

    @Test fun scriptletsInjectionsAndHtmlFiltersAreSkipped() {
        for (l in listOf(
            "example.com##+js(set, ads, false)", "example.com#@#+js(nobab)", "example.com#\$#body { overflow: auto !important; }",
            "example.com#%#window.x=1", "example.com#?#div:has-text(Sponsored)", "example.com#@?#.x", "example.com#@\$#.x { }",
            "example.com##^script:has-text(ads)", "example.com\$\$script[tag-content=\"ads\"]", "[\$path=/page]example.com##.ad",
        )) {
            assertEquals(l, SkipReason.UNSUPPORTED_COSMETIC, skipped(l))
        }
    }

    @Test fun proceduralSelectorsAreSkipped() {
        for (sel in listOf(
            "div:has-text(Ad)", "div:upward(2)", "div:xpath(//a)", ".x:style(display: none)", ".x:remove()", "div:matches-css(top: 0)",
            "div:-abp-has(.ad)", "div:contains(Sponsored)", "div:min-text-length(10)", "div[-ext-has=\".ad\"]", "div:others()",
            "div:matches-path(/x)", "div:watch-attr(class)", "div:nth-ancestor(2)",
        )) {
            assertEquals(sel, SkipReason.PROCEDURAL, skipped("example.com##$sel"))
        }
    }

    @Test fun selectorsThatCouldBreakTheStylesheetAreRejected() {
        assertFalse(FilterParser.isPlainCss("div } body { display: none"))
        assertFalse(FilterParser.isPlainCss("div /* comment"))
        assertFalse(FilterParser.isPlainCss("div[title=\"unclosed]"))
        assertFalse(FilterParser.isPlainCss("div:not(.a"))
        assertTrue(FilterParser.isPlainCss("a[href*=\"}\"]") == false) // braces are refused even inside strings
        assertTrue(FilterParser.isPlainCss("div[data-x=\"a,b\"] > .y:not(.z)"))
        assertTrue(FilterParser.isPlainCss(".md\\:hidden"))
    }

    @Test fun neverThrowsOnJunk() {
        val junk = listOf(
            "@@", "||", "|", "^", "\$", "\$\$", "##", "#@#", "@@\$", "||\$", "/", "//", "/[/", "/(/\$script", "##+js(", "a##",
            "~##.x", ",##.x", "@@||x^\$", "||x^\$,", "||x^\$~", "||x^\$domain=|", "x".repeat(10_000), "\u0000\u0001", "##‮",
        )
        for (l in junk) FilterParser.parse(l) // must not throw
    }
}
