package com.olaph.xdm.adblock.filters

import com.olaph.xdm.adblock.BlocklistFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The bundled snapshots themselves: present, compact, parseable line by line, and a lossless compiled cache. */
class RealListsTest {
    @Test fun everyBundledListIsPresentAndCompact() {
        for (spec in FilterLists.ALL) assertTrue("${spec.asset} missing (run ./gradlew :app:updateFilterLists)", TestLists.file(spec.asset).isFile)
        assertTrue(TestLists.file(FilterLists.PSL_ASSET).isFile)
        val total = FilterLists.ALL.sumOf { TestLists.file(it.asset).length() } + TestLists.file(FilterLists.PSL_ASSET).length()
        assertTrue("bundled lists take $total bytes", total < 4_500_000)
    }

    @Test fun parserNeverThrowsOnTheFullListsAndKeepsAlmostEverything() {
        for (spec in FilterLists.ALL.filter { it.format == ListFormat.FILTERS }) {
            val lines = TestLists.file(spec.asset).inputStream().use(FilterListFormat::read).lines
            val byKind = HashMap<String, Int>()
            for (l in lines) {
                val kind = when (val f = FilterParser.parse(l)) {
                    is ParsedFilter.Network -> if (f.plainHost != null) "host" else "network"
                    is ParsedFilter.Cosmetic -> "cosmetic"
                    is ParsedFilter.Skipped -> "skip:${f.reason}"
                    ParsedFilter.Comment -> "comment"
                }
                byKind[kind] = (byKind[kind] ?: 0) + 1
            }
            println("[lists] ${spec.id}: ${lines.size} lines → $byKind")
            val skipped = byKind.filterKeys { it.startsWith("skip") }.values.sum()
            assertTrue("${spec.id}: too much skipped ($byKind)", spec.id == "xdm-extra" || skipped < lines.size / 10)
        }
    }

    @Test fun bundledListsHaveDatesAndKnownRules() {
        for (spec in listOf(FilterLists.EASYLIST, FilterLists.ADGUARD_BASE, FilterLists.ADGUARD_DNS)) {
            val loaded = TestLists.file(spec.asset).inputStream().use(FilterListFormat::read)
            assertTrue("${spec.id} date", loaded.updatedAtMillis > 1_700_000_000_000L)
            assertTrue("${spec.id} size ${loaded.lines.size}", loaded.lines.size >= spec.minRules)
        }
        val easylist = TestLists.file(FilterLists.EASYLIST.asset).inputStream().use(FilterListFormat::read).lines.toSet()
        for (r in listOf("||33across.com^\$third-party", "||360ads.com^\$third-party", "||4dsply.com^\$third-party", "||1asb.com^\$popup", "nhl.com##.-leaderboard", "bigissue.com##.-ad")) {
            assertTrue("EasyList has $r", r in easylist)
        }
        val dns = DnsList.parse(TestLists.file(FilterLists.ADGUARD_DNS.asset).inputStream().use(FilterListFormat::read).lines.asSequence())
        assertTrue(dns.blocks.size > 100_000)
        assertTrue("doubleclick.net" in dns.blocks)
        assertTrue(dns.allows.isNotEmpty())
        assertTrue(TestLists.file(FilterLists.STEVENBLACK.asset).inputStream().use(BlocklistFormat::read).domains.size > 40_000)
    }

    @Test fun dnsListSyntax() {
        val l = DnsList.parse(sequenceOf("||a.com^", "||b.com^\$important", "@@||ok.a.com^|", "||c.com^\$client=1.2.3.4", "/regex/", "||d.com^", "||d.com^\$badfilter", "plain.com", "@@compact.com", "! c"))
        assertEquals(listOf("a.com", "b.com", "plain.com"), l.blocks)
        assertEquals(listOf("ok.a.com", "compact.com"), l.allows)
        val e = FilterEngineBuilder(TestLists.psl).apply { addDnsList(l) }.build()
        val page = e.page("https://news.org/")
        assertTrue(e.match("https://x.a.com/", RequestType.SCRIPT, page).blocked)
        assertTrue("the DNS list's own exception", !e.match("https://ok.a.com/", RequestType.SCRIPT, page).blocked)
    }

    @Test fun dnsExceptionsDoNotLiftOtherListsBlocks() {
        val b = FilterEngineBuilder(TestLists.psl)
        b.addDnsList(DnsList.parse(sequenceOf("@@||cdn.taboola.com^|")))
        b.addFilters(sequenceOf("||taboola.com^"))
        val e = b.build()
        assertTrue(e.match("https://cdn.taboola.com/x.js", RequestType.SCRIPT, e.page("https://news.org/")).blocked)
    }

    @Test fun compiledCacheIsLossless() {
        val engine = TestLists.engine
        val bytes = ByteArrayOutputStream().also { EngineCodec.write(engine, "fp", it) }.toByteArray()
        val loaded = EngineCodec.read(bytes.inputStream(), "fp", TestLists.psl)
        assertEquals(engine.stats, loaded.stats)

        val pages = listOf("https://videohub.example/", "https://clipsite.example/1.html", "https://www.example.com/", "https://news.co.uk/a")
        val urls = listOf(
            "https://go.popads.example/smartpop/x", "https://clipsite.example/api/spots/1", "https://randomads.xyz/AbC12x.js",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "https://www.google-analytics.com/g/collect?v=2",
            "https://cdn.videohub-media.example/wp-content/themes/JG/a.css", "https://fonts.gstatic.com/s/x.woff2", "https://img.thumbs-cdn.example/thumbs/1/2",
            "https://example.com/ads/banner.gif", "https://securepubads.g.doubleclick.net/tag/js/gpt.js", "https://cdnjs.cloudflare.com/x.js",
        )
        val types = listOf(RequestType.SCRIPT, RequestType.IMAGE, RequestType.XHR, RequestType.SUBDOCUMENT, RequestType.UNKNOWN, RequestType.DOCUMENT, RequestType.POPUP)
        for (p in pages) {
            val a = engine.page(p)
            val b = loaded.page(p)
            assertEquals(a.flags, b.flags)
            for (u in urls) for (t in types) assertEquals("$u $t on $p", engine.match(u, t, a).verdict, loaded.match(u, t, b).verdict)
            val host = a.info.host
            assertEquals(engine.specificSelectors(host, a), loaded.specificSelectors(host, b))
            assertEquals(engine.highGenericSelectors(host, a), loaded.highGenericSelectors(host, b))
            assertEquals(engine.genericSelectors(host, a, listOf(".ad", ".banner", "#ad", ".adsbygoogle")), loaded.genericSelectors(host, b, listOf(".ad", ".banner", "#ad", ".adsbygoogle")))
        }
    }

    @Test fun cacheWithAnotherFingerprintOrDamageIsRejected() {
        val engine = TestLists.engineOf("||ads.com^", "##.ad")
        val bytes = ByteArrayOutputStream().also { EngineCodec.write(engine, "fp1", it) }.toByteArray()
        assertThrows { EngineCodec.read(bytes.inputStream(), "fp2", TestLists.psl) }
        assertThrows { EngineCodec.read(bytes.copyOf(bytes.size / 2).inputStream(), "fp1", TestLists.psl) }
        assertThrows { EngineCodec.read("not a cache".toByteArray().inputStream(), "fp1", TestLists.psl) }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        }
        throw AssertionError("expected an IOException")
    }
}
