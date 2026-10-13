package com.olaph.xdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {
    private val google = SearchEngine.GOOGLE

    private fun resolve(s: String, e: SearchEngine = google) = UrlInput.resolve(s, e)

    @Test fun bareDomainGetsHttps() {
        assertEquals("https://example.com", resolve("example.com"))
        assertEquals("https://www.example.co.uk/path?q=1", resolve("www.example.co.uk/path?q=1"))
        assertEquals("https://example.com:8443/x", resolve("example.com:8443/x"))
        assertEquals("https://example.com", resolve("  example.com  "))
    }

    @Test fun explicitHttpSchemesAreKept() {
        assertEquals("http://x", resolve("http://x"))
        assertEquals("HTTP://example.com", resolve("HTTP://example.com"))
        assertEquals("http://example.com/a?b=c#d", resolve("http://example.com/a?b=c#d"))
    }

    @Test fun localhostAndIpAddressesGetHttp() {
        assertEquals("http://localhost:8080", resolve("localhost:8080"))
        assertEquals("http://localhost", resolve("localhost"))
        assertEquals("http://localhost:3000/app", resolve("localhost:3000/app"))
        assertEquals("http://192.168.1.1", resolve("192.168.1.1"))
        assertEquals("http://192.168.1.10:8000/x", resolve("192.168.1.10:8000/x"))
        assertEquals("http://[::1]:8080/", resolve("[::1]:8080/"))
    }

    @Test fun termsBecomeASearch() {
        assertEquals("https://www.google.com/search?q=hello+world", resolve("hello world"))
        assertEquals("https://www.google.com/search?q=hello", resolve("hello"))
        assertEquals("https://www.google.com/search?q=what+is+example.com%3F", resolve("what is example.com?"))
        assertEquals("https://www.google.com/search?q=caf%C3%A9+%26+bar", resolve("café & bar"))
    }

    @Test fun searchEngineIsConfigurable() {
        assertEquals("https://duckduckgo.com/?q=kotlin+flow", resolve("kotlin flow", SearchEngine.DUCKDUCKGO))
        assertEquals("https://www.bing.com/search?q=kotlin+flow", resolve("kotlin flow", SearchEngine.BING))
        assertEquals("https://www.google.com/search?q=kotlin+flow", resolve("kotlin flow", SearchEngine.GOOGLE))
    }

    @Test fun externalSchemesPassThrough() {
        assertEquals("mailto:a@b.co", resolve("mailto:a@b.co"))
        assertEquals("tel:+123456", resolve("tel:+123456"))
        assertEquals("intent://scan/#Intent;scheme=zxing;end", resolve("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals("about:blank", resolve("about:blank"))
        assertEquals("about:blank", resolve("   "))
    }

    @Test fun dangerousSchemesAreNeverLoadedFromTheAddressBar() {
        for (s in listOf("javascript:alert(1)", "data:text/html,<b>x</b>", "file:///sdcard/x.html", "content://media/x")) {
            val r = resolve(s)
            assertEquals(s, true, r.startsWith("https://www.google.com/search?q="))
        }
    }

    @Test fun wordsWithoutADotAreSearchesEvenWithAColon() {
        assertEquals("https://www.google.com/search?q=note%3A+todo", resolve("note: todo"))
        assertEquals("https://www.google.com/search?q=foo", resolve("foo"))
    }

    @Test fun displayHostStripsWwwAndPath() {
        assertEquals("example.com", UrlInput.displayHost("https://www.example.com/a/b?c"))
        assertEquals("localhost:8080", UrlInput.displayHost("http://localhost:8080/x"))
        assertEquals("example.com", UrlInput.displayHost("https://user:pw@example.com/"))
        assertNull(UrlInput.displayHost("about:blank"))
        assertNull(UrlInput.displayHost(null))
    }
}
