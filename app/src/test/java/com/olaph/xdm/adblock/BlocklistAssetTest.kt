package com.olaph.xdm.adblock

import com.olaph.xdm.browser.AdScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the snapshot that ships in the APK (unit tests run with the module directory as working dir). */
class BlocklistAssetTest {
    private val asset = File("src/main/assets/blocklist.txt.gz")

    @Test fun bundledSnapshotParsesWithTheRealParser() {
        assertTrue("run ./gradlew :app:updateBlocklist", asset.isFile)
        val list = asset.inputStream().use(BlocklistFormat::read)
        assertTrue("expected tens of thousands of domains, got ${list.domains.size}", list.domains.size > 40_000)
        assertTrue("header date missing", list.updatedAtMillis > 1_700_000_000_000L)
        assertFalse(list.domains.any { it == "localhost" || it == "0.0.0.0" || it.startsWith("#") })
        assertTrue(list.domains.all { HostsParser.isValidDomain(it) })
    }

    @Test fun bundledSnapshotIsCompact() {
        // ~72k domains: the raw hosts file is ~2.2 MB, the compact gzip well under 1 MB.
        assertTrue("asset is ${asset.length()} bytes", asset.length() < 1_000_000)
    }

    @Test fun bundledSnapshotBlocksKnownAdHostsButNotNormalSites() {
        val m = DomainMatcher(asset.inputStream().use(BlocklistFormat::read).domains)
        for (host in listOf("pagead2.googlesyndication.com", "ad.doubleclick.net", "x.y.doubleclick.net")) {
            assertTrue("$host should be blocked", m.isBlocked(host))
        }
        for (host in listOf("www.wikipedia.org", "github.com", "raw.githubusercontent.com", "www.google.com", "example.com")) {
            assertFalse("$host must not be blocked", m.isBlocked(host))
        }
    }

    @Test fun licenseIsBundled() {
        val text = File("src/main/assets/licenses/stevenblack-hosts-LICENSE.txt").readText()
        assertTrue(text.startsWith("The MIT License"))
        assertTrue(text.contains("Steven Black"))
    }

    @Test fun cosmeticScriptUsesTheBridgeAndIsIdempotent() {
        val s = AdScript.SOURCE
        assertTrue(s.contains("window.XdmAds"))
        assertTrue(s.contains("A.active()"))
        assertTrue(s.contains("A.css(host)"))
        assertTrue(s.contains("A.generic(host,"))
        assertTrue(s.contains(AdScript.STYLE_ID))
        assertTrue("second injection only rescans", s.contains("if (window.__xdmAds)"))
        assertTrue("throttled observer", s.contains("MutationObserver") && s.contains("setTimeout(run"))
        assertTrue("batched bridge calls", s.contains("queue.splice(0, ${AdScript.BATCH})"))
        // CSS comes back from the bridge as data and is added as a text node, never parsed as HTML or code.
        assertTrue(s.contains("createTextNode(pending)"))
        assertFalse(s.contains("innerHTML") || s.contains("eval("))
        assertEquals("balanced braces", s.count { it == '{' }, s.count { it == '}' })
    }

    @Test fun licenseNoticesForTheFilterListsAreBundled() {
        val notice = File("src/main/assets/licenses/filter-lists-NOTICE.txt").readText()
        for (s in listOf("easylist.to/easylist/easylist.txt", "2_without_easylist.txt", "AdGuardSDNSFilter", "publicsuffix.org", "GPL", "MPL")) {
            assertTrue("notice mentions $s", notice.contains(s))
        }
        assertTrue(File("src/main/assets/licenses/GPL-3.0.txt").readText().contains("GNU GENERAL PUBLIC LICENSE"))
        assertTrue(File("src/main/assets/licenses/MPL-2.0.txt").readText().contains("Mozilla Public License Version 2.0"))
    }
}
