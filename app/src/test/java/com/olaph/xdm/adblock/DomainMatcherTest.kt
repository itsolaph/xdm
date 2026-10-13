package com.olaph.xdm.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {
    private val matcher = DomainMatcher(setOf("ads.example.com", "tracker.net", "evil.co.uk"))

    @Test fun matchesExactHost() {
        assertTrue(matcher.isBlocked("ads.example.com"))
        assertTrue(matcher.isBlocked("tracker.net"))
    }

    @Test fun walksParentDomains() {
        assertEquals("ads.example.com", matcher.matchedEntry("a.b.ads.example.com"))
        assertEquals("ads.example.com", matcher.matchedEntry("b.ads.example.com"))
        assertEquals("tracker.net", matcher.matchedEntry("cdn.eu.tracker.net"))
        assertEquals("evil.co.uk", matcher.matchedEntry("x.evil.co.uk"))
    }

    @Test fun parentOfAnEntryIsNotBlocked() {
        assertFalse(matcher.isBlocked("example.com"))
        assertFalse(matcher.isBlocked("www.example.com"))
        assertFalse(matcher.isBlocked("co.uk"))
        assertFalse(matcher.isBlocked("net"))
    }

    @Test fun onlyWholeLabelsMatch() {
        val m = DomainMatcher(setOf("ads.com"))
        assertTrue(m.isBlocked("ads.com"))
        assertTrue(m.isBlocked("x.ads.com"))
        assertFalse(m.isBlocked("notads.com"))
        assertFalse(m.isBlocked("myads.com"))
        assertFalse(m.isBlocked("ads.com.evil.org"))
        assertFalse(m.isBlocked("xads.com"))
    }

    @Test fun isCaseInsensitive() {
        assertTrue(matcher.isBlocked("ADS.Example.COM"))
        assertTrue(matcher.isBlocked("Sub.Tracker.NET"))
    }

    @Test fun ignoresTrailingDots() {
        assertTrue(matcher.isBlocked("ads.example.com."))
        assertTrue(matcher.isBlocked("x.tracker.net.."))
        assertFalse(matcher.isBlocked("."))
    }

    @Test fun ipHostsAreNeverMatchedByWalking() {
        val m = DomainMatcher(setOf("2.3.4", "example.com"))
        assertFalse(m.isBlocked("1.2.3.4"))
        assertFalse(m.isBlocked("192.168.0.1"))
        assertFalse(m.isBlocked("[::1]"))
        assertFalse(m.isBlocked("2001:db8::1"))
    }

    @Test fun nullAndBlankHostsAreNotBlocked() {
        assertFalse(matcher.isBlocked(null))
        assertFalse(matcher.isBlocked(""))
        assertFalse(matcher.isBlocked("   "))
        assertNull(matcher.matchedEntry(null))
    }

    @Test fun emptyMatcherBlocksNothing() {
        assertFalse(DomainMatcher(emptySet()).isBlocked("ads.example.com"))
    }

    @Test fun hostOfParsesUrls() {
        assertEquals("ads.example.com", HostNames.hostOf("https://ADS.example.com:8443/x?y=1#z"))
        assertEquals("[::1]".trim('[', ']'), HostNames.hostOf("http://[::1]:8080/"))
        assertNull(HostNames.hostOf("not a url"))
        assertNull(HostNames.hostOf(null))
    }

    @Test fun siteKeyDropsLeadingWww() {
        assertEquals("example.com", HostNames.siteKey("www.Example.com"))
        assertEquals("m.example.com", HostNames.siteKey("m.example.com"))
        assertEquals("www.com", HostNames.siteKey("www.com")) // "www" is the whole name part here, keep it
        assertEquals("10.0.0.1", HostNames.siteKey("10.0.0.1"))
    }
}
