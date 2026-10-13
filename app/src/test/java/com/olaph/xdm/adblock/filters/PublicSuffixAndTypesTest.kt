package com.olaph.xdm.adblock.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class PublicSuffixAndTypesTest {
    private val psl = TestLists.psl

    // ---- Public Suffix List ----

    @Test fun bundledListIsLoaded() {
        assertTrue("rules: ${psl.size}", psl.size > 5_000)
    }

    @Test fun registrableDomains() {
        assertEquals("example.com", psl.registrableDomain("www.example.com"))
        assertEquals("example.co.uk", psl.registrableDomain("a.b.example.co.uk"))
        assertEquals("loja.com.br", psl.registrableDomain("www.loja.com.br"))
        assertEquals("alice.github.io", psl.registrableDomain("alice.github.io"))
        assertEquals("videohub.example", psl.registrableDomain("videohub.example"))
        assertEquals("1.2.3.4", psl.registrableDomain("1.2.3.4"))
        assertNull("a public suffix itself", psl.registrableDomain("co.uk"))
        assertNull(psl.registrableDomain("github.io"))
        assertNull(psl.registrableDomain(""))
    }

    @Test fun wildcardAndExceptionRules() {
        // *.ck with !www.ck
        assertEquals("www.ck", psl.registrableDomain("www.ck"))
        assertEquals("site.foo.ck", psl.registrableDomain("a.site.foo.ck"))
    }

    @Test fun thirdPartyComparison() {
        assertFalse(psl.isThirdParty("a.example.co.uk", "b.example.co.uk"))
        assertTrue(psl.isThirdParty("example.co.uk", "other.co.uk"))
        assertFalse(psl.isThirdParty("cdn.shop.com.br", "www.shop.com.br"))
        assertTrue(psl.isThirdParty("alice.github.io", "bob.github.io"))
        assertTrue(psl.isThirdParty("cdn.videohub-media.example", "videohub.example"))
    }

    @Test fun entityNames() {
        assertEquals(listOf("www.example", "example"), psl.entityNames("www.example.co.uk"))
        assertEquals(listOf("example"), psl.entityNames("example.com"))
        assertEquals(emptyList<String>(), psl.entityNames("co.uk"))
    }

    @Test fun fallbackCoversTheCommonCases() {
        val f = PublicSuffixList.FALLBACK
        assertEquals("example.co.uk", f.registrableDomain("a.example.co.uk"))
        assertEquals("loja.com.br", f.registrableDomain("www.loja.com.br"))
        assertEquals("alice.github.io", f.registrableDomain("x.alice.github.io"))
        assertEquals("example.com", f.registrableDomain("www.example.com"))
    }

    // ---- request type inference ----

    private fun infer(url: String, main: Boolean = false, headers: Map<String, String> = emptyMap()) =
        RequestTypeInference.infer(url, main, headers)

    @Test fun mainFrameIsADocument() {
        assertEquals(RequestType.DOCUMENT, infer("https://x.com/", main = true))
    }

    @Test fun secFetchDestWins() {
        assertEquals(RequestType.SCRIPT, infer("https://x.com/a.png", headers = mapOf("Sec-Fetch-Dest" to "script")))
        assertEquals(RequestType.SUBDOCUMENT, infer("https://x.com/a", headers = mapOf("sec-fetch-dest" to "iframe")))
        assertEquals(RequestType.XHR, infer("https://x.com/api", headers = mapOf("Sec-Fetch-Dest" to "empty")))
        assertEquals(RequestType.MEDIA, infer("https://x.com/v", headers = mapOf("Sec-Fetch-Dest" to "video")))
    }

    @Test fun headersThenExtension() {
        assertEquals(RequestType.XHR, infer("https://x.com/api", headers = mapOf("X-Requested-With" to "XMLHttpRequest")))
        assertEquals("WebView's own X-Requested-With is the app package", RequestType.UNKNOWN, infer("https://x.com/api", headers = mapOf("X-Requested-With" to "com.olaph.xdm")))
        assertEquals(RequestType.STYLESHEET, infer("https://x.com/s", headers = mapOf("Accept" to "text/css,*/*;q=0.1")))
        assertEquals(RequestType.IMAGE, infer("https://x.com/i", headers = mapOf("Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")))
        assertEquals(RequestType.SUBDOCUMENT, infer("https://x.com/f", headers = mapOf("Accept" to "text/html,application/xhtml+xml")))
        assertEquals(RequestType.SCRIPT, infer("https://x.com/a/b.js?v=1"))
        assertEquals(RequestType.SCRIPT, infer("https://x.com/m.mjs#x"))
        assertEquals(RequestType.STYLESHEET, infer("https://x.com/a.CSS"))
        assertEquals(RequestType.IMAGE, infer("https://x.com/a.webp"))
        assertEquals(RequestType.FONT, infer("https://x.com/a.woff2"))
        assertEquals(RequestType.MEDIA, infer("https://x.com/master.m3u8"))
        assertEquals(RequestType.MEDIA, infer("https://x.com/seg-1.ts"))
        assertEquals(RequestType.MEDIA, infer("https://x.com/v.mp4"))
        assertEquals(RequestType.WEBSOCKET, infer("wss://x.com/socket"))
        assertEquals(RequestType.UNKNOWN, infer("https://x.com/api/models?limit=2"))
        assertEquals(RequestType.UNKNOWN, infer("https://x.com"))
        assertEquals(RequestType.UNKNOWN, infer("https://x.com/path.with.dots/"))
    }

    // ---- compact host set ----

    @Test fun compactDomainSetWalksParents() {
        val s = CompactDomainSet.build(listOf("ads.com", "tracker.example.co.uk", "1.2.3.4", "BAD.com", "x".repeat(300), "2.3.4"))
        assertEquals(4, s.size)
        assertEquals("ads.com", s.matchEntry("a.b.ads.com"))
        assertEquals("ads.com", s.matchEntry("ads.com"))
        assertNull(s.matchEntry("notads.com"))
        assertNull("a bare TLD is never tested", s.matchEntry("com"))
        assertEquals("tracker.example.co.uk", s.matchEntry("x.tracker.example.co.uk"))
        assertNull(s.matchEntry("example.co.uk"))
        assertEquals("1.2.3.4", s.matchEntry("1.2.3.4"))
        assertNull("IPs never walk (9.2.3.4 is not under 2.3.4)", s.matchEntry("9.2.3.4"))
        assertEquals(4, s.toList().size)
    }

    @Test fun compactDomainSetRoundTrip() {
        val names = (1..5_000).map { "host$it.example$it.com" }
        val s = CompactDomainSet.build(names)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { s.write(it) }
        val r = CompactDomainSet.read(DataInputStream(bytes.toByteArray().inputStream()))
        assertEquals(5_000, r.size)
        assertTrue(names.all { it in r })
        assertFalse("other.com" in r)
    }
}
