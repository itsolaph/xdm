package com.olaph.xdm.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeUrlsTest {
    private val id = "dQw4w9WgXcQ"
    private val shortId = "aqz-KE-bpKQ"
    private val list = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"

    private fun video(url: String) = YoutubeUrls.parse(url) as? YoutubeLink.Video

    private fun assertVideo(url: String, expected: String = id, listId: String? = null) {
        val link = YoutubeUrls.parse(url)
        assertEquals("video link from $url", YoutubeLink.Video(expected, listId), link)
    }

    // ---- video links ----

    @Test fun watchLinksOnEveryYoutubeHost() {
        assertVideo("https://www.youtube.com/watch?v=$id")
        assertVideo("https://youtube.com/watch?v=$id")
        assertVideo("http://www.youtube.com/watch?v=$id")
        assertVideo("https://m.youtube.com/watch?v=$id")
        assertVideo("https://music.youtube.com/watch?v=$id")
        assertVideo("https://www.youtube-nocookie.com/watch?v=$id")
        assertVideo("https://WWW.YOUTUBE.COM/watch?v=$id")
        assertVideo("https://www.youtube.com/watch?v=$shortId", shortId)
    }

    @Test fun extraQueryParametersAreIgnored() {
        assertVideo("https://www.youtube.com/watch?v=$id&t=42s&feature=share")
        assertVideo("https://www.youtube.com/watch?feature=share&v=$id")
        assertVideo("https://m.youtube.com/watch?v=$id&si=AbCdEf&pp=ygUF")
        assertVideo("https://www.youtube.com/watch?v=$id#t=10")
    }

    @Test fun shortLinks() {
        assertVideo("https://youtu.be/$id")
        assertVideo("https://youtu.be/$id?si=AbCdEfGhIjKlMnOp")
        assertVideo("https://youtu.be/$id?t=30")
        assertVideo("http://youtu.be/$id/")
    }

    @Test fun shortsEmbedLiveAndOldPaths() {
        assertVideo("https://www.youtube.com/shorts/$id")
        assertVideo("https://m.youtube.com/shorts/$id?feature=share")
        assertVideo("https://www.youtube.com/embed/$id")
        assertVideo("https://www.youtube-nocookie.com/embed/$id")
        assertVideo("https://www.youtube.com/live/$id")
        assertVideo("https://www.youtube.com/live/$id?si=abc&feature=share")
        assertVideo("https://www.youtube.com/v/$id")
        assertVideo("https://www.youtube.com/watch/$id")
    }

    @Test fun linksWithoutAScheme() {
        assertVideo("youtu.be/$id")
        assertVideo("www.youtube.com/watch?v=$id")
        assertVideo("m.youtube.com/shorts/$id")
        assertVideo("music.youtube.com/watch?v=$id")
    }

    @Test fun textAsSharedFromTheYoutubeApp() {
        assertVideo("Never Gonna Give You Up https://youtu.be/$id?si=abcdef")
        assertVideo("Look at this:\nhttps://www.youtube.com/watch?v=$id\nthanks")
        assertVideo("(see https://youtu.be/$id).")
        assertVideo("https://youtu.be/$id.")
        assertVideo("  https://youtu.be/$id  ")
    }

    @Test fun theAttributionRedirectIsUnwrapped() {
        assertVideo("https://www.youtube.com/attribution_link?a=xyz&u=%2Fwatch%3Fv%3D$id%26feature%3Dshare")
    }

    // ---- playlists ----

    @Test fun playlistLinks() {
        assertEquals(YoutubeLink.Playlist(list), YoutubeUrls.parse("https://www.youtube.com/playlist?list=$list"))
        assertEquals(YoutubeLink.Playlist(list), YoutubeUrls.parse("https://m.youtube.com/playlist?list=$list&si=abc"))
        assertEquals(YoutubeLink.Playlist("OLAK5uy_kXyz1234567890abcdefghijklmn"), YoutubeUrls.parse("https://music.youtube.com/playlist?list=OLAK5uy_kXyz1234567890abcdefghijklmn"))
        assertEquals(YoutubeLink.Playlist(list), YoutubeUrls.parse("https://www.youtube.com/watch?list=$list"))
        assertEquals(YoutubeLink.Playlist(list), YoutubeUrls.parse("www.youtube.com/playlist?list=$list"))
    }

    @Test fun aVideoInsideAPlaylistIsTheVideoWithTheListOnTheSide() {
        assertVideo("https://www.youtube.com/watch?v=$id&list=$list&index=3", id, list)
        assertVideo("https://youtu.be/$id?list=$list", id, list)
        assertVideo("https://music.youtube.com/watch?v=$id&list=RDAMVM$id", id, "RDAMVM$id")
    }

    @Test fun playlistIdKinds() {
        assertTrue(YoutubeUrls.isRealPlaylist(list))
        assertTrue(YoutubeUrls.isRealPlaylist("OLAK5uy_abc"))
        assertFalse("auto-generated mix", YoutubeUrls.isRealPlaylist("RDdQw4w9WgXcQ"))
        assertFalse("watch later", YoutubeUrls.isRealPlaylist("WL"))
        assertFalse("liked videos", YoutubeUrls.isRealPlaylist("LL"))
    }

    // ---- not downloadable / not YouTube ----

    @Test fun otherYoutubePagesAreNotLinks() {
        for (u in listOf(
            "https://www.youtube.com/", "https://www.youtube.com", "https://m.youtube.com/",
            "https://www.youtube.com/results?search_query=cats", "https://www.youtube.com/@somechannel",
            "https://www.youtube.com/channel/UCuAXFkgsw1L7xaCfnd5JJOw", "https://www.youtube.com/feed/subscriptions",
            "https://www.youtube.com/playlist", "https://www.youtube.com/watch", "https://www.youtube.com/shorts/",
            "https://www.youtube.com/gaming", "https://youtu.be/", "https://youtu.be",
        )) assertNull(u, YoutubeUrls.parse(u))
    }

    @Test fun badVideoIdsAreRejected() {
        assertNull(YoutubeUrls.parse("https://www.youtube.com/watch?v=short"))
        assertNull(YoutubeUrls.parse("https://www.youtube.com/watch?v=waytoolongforavideoid"))
        assertNull(YoutubeUrls.parse("https://youtu.be/not%20an%20id!"))
        assertNull(YoutubeUrls.parse("https://www.youtube.com/shorts/abc"))
        assertNull(YoutubeUrls.parse("https://www.youtube.com/watch?v="))
    }

    @Test fun otherSitesAreNotYoutube() {
        for (u in listOf(
            "https://example.com/watch?v=$id", "https://notyoutube.com/watch?v=$id", "https://youtube.com.evil.example/watch?v=$id",
            "https://evilyoutu.be/$id", "https://example.com/?u=https://youtu.be/$id", "https://vimeo.com/123456789",
            "https://www.youtube.example.org/watch?v=$id", "ftp://youtube.com/watch?v=$id",
        )) assertNull(u, YoutubeUrls.parse(u))
    }

    @Test fun nothingAtAll() {
        assertNull(YoutubeUrls.parse(null))
        assertNull(YoutubeUrls.parse(""))
        assertNull(YoutubeUrls.parse("   "))
        assertNull(YoutubeUrls.parse("just some text"))
        assertNull(YoutubeUrls.parse("https://"))
    }

    // ---- helpers ----

    @Test fun hostChecks() {
        assertTrue(YoutubeUrls.isYoutubeHost("www.youtube.com"))
        assertTrue(YoutubeUrls.isYoutubeHost("m.youtube.com"))
        assertTrue(YoutubeUrls.isYoutubeHost("music.youtube.com"))
        assertTrue(YoutubeUrls.isYoutubeHost("youtu.be"))
        assertTrue(YoutubeUrls.isYoutubeHost("www.youtube-nocookie.com"))
        assertFalse(YoutubeUrls.isYoutubeHost("notyoutube.com"))
        assertFalse(YoutubeUrls.isYoutubeHost("youtube.com.evil.example"))
        assertFalse(YoutubeUrls.isYoutubeHost(null))
    }

    @Test fun browserPagesThatGetTheDownloadButton() {
        assertTrue(YoutubeUrls.isDownloadablePage("https://m.youtube.com/watch?v=$id"))
        assertTrue(YoutubeUrls.isDownloadablePage("https://m.youtube.com/shorts/$id"))
        assertTrue(YoutubeUrls.isDownloadablePage("https://www.youtube.com/playlist?list=$list"))
        assertFalse("home page stays quiet", YoutubeUrls.isDownloadablePage("https://m.youtube.com/"))
        assertFalse(YoutubeUrls.isDownloadablePage("https://www.youtube.com/results?search_query=x"))
        assertFalse(YoutubeUrls.isDownloadablePage(null))
    }

    @Test fun canonicalUrls() {
        assertEquals("https://www.youtube.com/watch?v=$id", YoutubeUrls.canonicalUrl(YoutubeLink.Video(id)))
        assertEquals("https://www.youtube.com/playlist?list=$list", YoutubeUrls.canonicalUrl(YoutubeLink.Playlist(list)))
        // and they parse back to the same thing
        assertEquals(YoutubeLink.Video(id), YoutubeUrls.parse(YoutubeUrls.watchUrl(id)))
        assertEquals(YoutubeLink.Playlist(list), YoutubeUrls.parse(YoutubeUrls.playlistUrl(list)))
    }

    @Test fun theSpaRouteChangesWithoutAPageLoad() {
        // m.youtube.com navigates with pushState: the same tab shows different links over time
        assertEquals(null, YoutubeUrls.parse("https://m.youtube.com/"))
        assertEquals(YoutubeLink.Video(id), YoutubeUrls.parse("https://m.youtube.com/watch?v=$id"))
        assertEquals(YoutubeLink.Video(shortId), YoutubeUrls.parse("https://m.youtube.com/shorts/$shortId"))
    }
}
