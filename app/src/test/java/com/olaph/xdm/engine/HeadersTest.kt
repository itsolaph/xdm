package com.olaph.xdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadersTest {

    @Test fun roundTripPreservesNamesValuesAndOrder() {
        val headers = linkedMapOf(
            "Cookie" to "sid=abc123; theme=dark; token=a%3Db&c=d",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36",
            "Referer" to "https://example.com/files/page?id=7&x=y#frag",
        )
        val encoded = HeaderCodec.encode(headers)!!
        assertEquals(headers.toList(), HeaderCodec.decode(encoded).toList())
    }

    @Test fun valuesWithSeparatorsAndPercentSignsSurvive() {
        val headers = mapOf("X-Test" to "a=b&c=d%20e+f ; g")
        assertEquals(headers, HeaderCodec.decode(HeaderCodec.encode(headers)))
    }

    @Test fun emptyAndNullInputs() {
        assertNull(HeaderCodec.encode(emptyMap()))
        assertEquals(emptyMap<String, String>(), HeaderCodec.decode(null))
        assertEquals(emptyMap<String, String>(), HeaderCodec.decode(""))
        assertEquals(emptyMap<String, String>(), HeaderCodec.decode("garbage-without-equals&=novalue"))
    }

    @Test fun reservedAndUnsafeHeadersAreDropped() {
        val clean = HeaderCodec.sanitize(
            mapOf(
                "Range" to "bytes=0-1",
                "accept-encoding" to "gzip",
                "Host" to "evil.example",
                "Content-Length" to "5",
                "Cookie" to "ok=1",
                "X-Newline" to "a\r\nInjected: yes",
                "X-Unicode" to "café",
                "Bad Name" to "v",
                "Empty" to "  ",
            ),
        )
        assertEquals(mapOf("Cookie" to "ok=1"), clean)
    }

    @Test fun duplicateNamesAreCaseInsensitiveLastWins() {
        val clean = HeaderCodec.sanitize(linkedMapOf("cookie" to "a=1", "Cookie" to "b=2"))
        assertEquals(mapOf("Cookie" to "b=2"), clean)
    }

    @Test fun decodedHeadersAreSanitizedAgain() {
        assertEquals(emptyMap<String, String>(), HeaderCodec.decode("Range=bytes%3D0-1"))
    }

    @Test fun fromBrowserKeepsOnlyUsefulHeaders() {
        val h = DownloadHeaders.fromBrowser(cookie = "a=b", userAgent = "UA/1", referer = "https://site.test/page")
        assertEquals(mapOf("Cookie" to "a=b", "User-Agent" to "UA/1", "Referer" to "https://site.test/page"), h)
        assertEquals(emptyMap<String, String>(), DownloadHeaders.fromBrowser(null, " ", "about:blank"))
    }

    @Test fun originOfPageUrl() {
        assertEquals("https://example.com", DownloadHeaders.origin("https://example.com/a/b?x=1"))
        assertEquals("http://localhost:8080", DownloadHeaders.origin("http://localhost:8080/p"))
        assertEquals("https://example.com", DownloadHeaders.origin("https://example.com?x"))
        assertNull(DownloadHeaders.origin("about:blank"))
        assertNull(DownloadHeaders.origin(null))
    }

    @Test fun desktopUserAgentDropsAndroidAndMobile() {
        val mobile = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        val desktop = UserAgents.desktop(mobile)
        assertTrue(desktop, "Android" !in desktop && "Mobile" !in desktop && "X11; Linux x86_64" in desktop)
        assertTrue("Chrome/130.0.0.0" in desktop)
        assertEquals(UserAgents.DEFAULT_DESKTOP, UserAgents.desktop(""))
    }
}
