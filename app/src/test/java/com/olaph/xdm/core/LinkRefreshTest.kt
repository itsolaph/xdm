package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.HeaderCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRefreshTest {
    private fun download(
        sourcePage: String? = null,
        headers: Map<String, String> = emptyMap(),
        status: DownloadStatus = DownloadStatus.FAILED,
        error: String? = "Server error: HTTP 403",
        kind: String = DownloadKind.HTTP,
        isPrivate: Boolean = false,
    ) = DownloadEntity(
        id = 1, url = "https://cdn.test/v.mp4", fileName = "v.mp4", status = status, error = error, totalBytes = 1000,
        sourcePage = sourcePage, headers = HeaderCodec.encode(headers), kind = kind, isPrivate = isPrivate,
    )

    // ---- the page to open ----

    @Test fun theSourcePageIsThePageToOpen() {
        val d = download(sourcePage = "https://site.test/watch", headers = mapOf("Referer" to "https://other.test/"))
        assertEquals("https://site.test/watch", LinkRefresh.refreshPage(d))
    }

    @Test fun anOldDownloadFallsBackToItsReferer() {
        val d = download(headers = mapOf("Cookie" to "a=b", "referer" to "https://site.test/old"))
        assertEquals("https://site.test/old", LinkRefresh.refreshPage(d))
        assertTrue(LinkRefresh.canRefreshFromPage(d))
        assertTrue(LinkRefresh.offerRefresh(d))
    }

    @Test fun withoutAnyPageThereIsNothingToOpen() {
        val d = download(headers = mapOf("Cookie" to "a=b"))
        assertNull(LinkRefresh.refreshPage(d))
        assertFalse(LinkRefresh.canRefreshFromPage(d))
        assertFalse(LinkRefresh.offerRefresh(d))
    }

    @Test fun onlyHttpPagesCount() {
        assertNull(LinkRefresh.refreshPage(download(sourcePage = "ftp://site.test/x")))
        assertNull(LinkRefresh.refreshPage(download(headers = mapOf("Referer" to "android-app://com.example"))))
        // a bad source page does not hide a good Referer
        val d = download(sourcePage = "file:///x", headers = mapOf("Referer" to "https://site.test/p"))
        assertEquals("https://site.test/p", LinkRefresh.refreshPage(d))
    }

    @Test fun excludedDownloadsStayExcluded() {
        val page = "https://site.test/p"
        assertFalse(LinkRefresh.canRefreshFromPage(download(sourcePage = page, isPrivate = true)))
        assertFalse(LinkRefresh.canRefreshFromPage(download(sourcePage = page, kind = DownloadKind.HLS)))
        assertFalse(LinkRefresh.canRefreshFromPage(download(sourcePage = page, kind = DownloadKind.YOUTUBE)))
        assertFalse(LinkRefresh.canRefreshFromPage(download(sourcePage = page, status = DownloadStatus.COMPLETED)))
        assertTrue(LinkRefresh.canRefreshFromPage(download(sourcePage = page, status = DownloadStatus.PAUSED)))
    }

    // ---- the Retry button ----

    @Test fun retryOnAnExpiredLinkRefreshesIt() {
        val page = "https://site.test/p"
        assertTrue(LinkRefresh.retryRefreshesLink(download(sourcePage = page, error = "Server error: HTTP 403")))
        assertTrue(LinkRefresh.retryRefreshesLink(download(sourcePage = page, error = "Server error: HTTP 410")))
    }

    @Test fun retryOnOtherFailuresIsAPlainRetry() {
        val page = "https://site.test/p"
        assertFalse(LinkRefresh.retryRefreshesLink(download(sourcePage = page, error = "Server error: HTTP 404")))
        assertFalse(LinkRefresh.retryRefreshesLink(download(sourcePage = page, error = "Server error: HTTP 4030")))
        assertFalse(LinkRefresh.retryRefreshesLink(download(sourcePage = page, error = "Network error: timeout")))
        assertFalse(LinkRefresh.retryRefreshesLink(download(sourcePage = page, error = null)))
        assertFalse(LinkRefresh.retryRefreshesLink(download(sourcePage = page, status = DownloadStatus.CANCELLED)))
    }

    @Test fun retryOnAnExpiredLinkWithoutAPageIsAPlainRetry() {
        assertFalse(LinkRefresh.retryRefreshesLink(download()))
        assertFalse(LinkRefresh.retryRefreshesLink(download(sourcePage = "https://site.test/p", isPrivate = true)))
    }

    // ---- matching a candidate ----

    @Test fun anExactSizeMatches() {
        val t = RefreshTarget(1, "v.mp4", 3_500_000_000L)
        assertTrue(t.claim(3_500_000_000L))
        assertFalse("the target is spent", t.isOpen)
    }

    @Test fun anotherSizeNeverMatches() {
        val t = RefreshTarget(1, "v.mp4", 1000)
        assertFalse(t.claim(999))
        assertFalse(t.claim(1001))
        assertFalse(t.claim(0))
        assertFalse(t.claim(-1))
        assertTrue("a miss does not end the search", t.isOpen)
        assertTrue(t.claim(1000))
    }

    @Test fun anUnknownTotalNeverAppliesAnything() {
        for (total in listOf(-1L, 0L)) {
            val t = RefreshTarget(1, "v.mp4", total)
            assertFalse(t.canAutoMatch)
            assertFalse(t.claim(total))
            assertFalse(t.claim(1000))
        }
    }

    @Test fun theFirstMatchWins() {
        val t = RefreshTarget(1, "v.mp4", 1000)
        assertTrue(t.claim(1000))
        assertFalse(t.claim(1000))
    }

    @Test fun nothingMatchesAfterRefreshModeEnded() {
        val t = RefreshTarget(1, "v.mp4", 1000)
        t.close()
        assertFalse(t.claim(1000))
    }

    @Test fun aUrlIsOnlyProbedOncePerRefreshUnlessItFailedForNow() {
        val t = RefreshTarget(1, "v.mp4", 1000)
        assertTrue(t.firstSighting("https://cdn.test/v.mp4"))
        assertFalse(t.firstSighting("https://cdn.test/v.mp4"))
        t.forget("https://cdn.test/v.mp4")
        assertTrue(t.firstSighting("https://cdn.test/v.mp4"))
    }
}
