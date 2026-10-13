package com.olaph.xdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsTest {
    @Test fun extractsFirstUrlFromSharedText() {
        assertEquals(
            "https://example.com/file.zip",
            UrlUtils.extractFirstUrl("Check this out https://example.com/file.zip and http://other.com/x"),
        )
    }

    @Test fun stripsTrailingPunctuation() {
        assertEquals("https://example.com/a.zip", UrlUtils.extractFirstUrl("see (https://example.com/a.zip)."))
        assertEquals("https://example.com/a?x=1", UrlUtils.extractFirstUrl("https://example.com/a?x=1,"))
    }

    @Test fun noUrl() {
        assertNull(UrlUtils.extractFirstUrl("just some text"))
        assertNull(UrlUtils.extractFirstUrl("ftp://example.com/a"))
        assertNull(UrlUtils.extractFirstUrl(null))
        assertNull(UrlUtils.extractFirstUrl("https://"))
    }

    @Test fun httpUrlValidation() {
        assertTrue(UrlUtils.isHttpUrl("http://a.b/c"))
        assertTrue(UrlUtils.isHttpUrl("HTTPS://a.b"))
        assertFalse(UrlUtils.isHttpUrl("a.b/c"))
        assertFalse(UrlUtils.isHttpUrl("https:///x"))
        assertFalse(UrlUtils.isHttpUrl("https://a b"))
    }

    @Test fun looksDownloadable() {
        assertTrue(UrlUtils.looksDownloadable("https://x.org/a/b.APK?x=1"))
        assertFalse(UrlUtils.looksDownloadable("https://x.org/page.html"))
        assertFalse(UrlUtils.looksDownloadable("https://x.org/"))
    }

    @Test fun speedMeterRollingAverage() {
        val m = SpeedMeter(windowMs = 5_000)
        assertEquals(0, m.bytesPerSecond())
        m.add(0, 0)
        assertEquals(0, m.bytesPerSecond())
        m.add(1_000, 1_000)
        m.add(2_000, 2_000)
        assertEquals(1_000, m.bytesPerSecond())
        // speed doubles; old samples fall out of the window
        for (t in 3..12) m.add(t * 1_000L, 2_000L + (t - 2) * 2_000L)
        assertEquals(2_000, m.bytesPerSecond())
    }

    @Test fun speedMeterResetsWhenCounterGoesBack() {
        val m = SpeedMeter()
        m.add(0, 0); m.add(1_000, 5_000)
        m.add(2_000, 0)
        assertEquals(0, m.bytesPerSecond())
    }

    @Test fun eta() {
        assertEquals(10, SpeedMeter.etaSeconds(10_000, 1_000))
        assertEquals(11, SpeedMeter.etaSeconds(10_001, 1_000))
        assertEquals(-1, SpeedMeter.etaSeconds(10_000, 0))
        assertEquals(-1, SpeedMeter.etaSeconds(-1, 1_000))
    }

    @Test fun mimeTypes() {
        assertEquals("application/zip", MimeTypes.forFileName("a.ZIP"))
        assertNull(MimeTypes.forFileName("noext"))
        assertEquals("jpg", MimeTypes.extensionFor("image/jpeg; charset=binary"))
        assertNull(MimeTypes.extensionFor("application/octet-stream"))
    }
}
