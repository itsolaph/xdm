package com.olaph.xdm.media

import com.olaph.xdm.media.MediaClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {

    private fun kind(url: String) = MediaClassifier.classify(url)

    @Test fun directVideoFilesByExtension() {
        for (ext in listOf("mp4", "m4v", "webm", "mkv", "mov", "3gp", "flv", "MP4")) {
            assertEquals(ext, Kind.VIDEO_FILE, kind("https://cdn.example.com/a/b/clip.$ext"))
        }
        assertEquals(Kind.VIDEO_FILE, kind("https://cdn.example.com/clip.mp4?token=abc&expires=123"))
        assertEquals(Kind.VIDEO_FILE, kind("https://cdn.example.com/dir.with.dots/My%20Clip.final.mp4#t=10"))
    }

    @Test fun audioFilesByExtension() {
        for (ext in listOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")) {
            assertEquals(ext, Kind.AUDIO_FILE, kind("https://cdn.example.com/song.$ext"))
        }
    }

    @Test fun manifests() {
        assertEquals(Kind.HLS, kind("https://cdn.example.com/hls/master.m3u8"))
        assertEquals(Kind.HLS, kind("https://cdn.example.com/v/index.m3u8?sig=1"))
        assertEquals(Kind.DASH, kind("https://cdn.example.com/v/manifest.mpd"))
        assertTrue(MediaClassifier.isHlsUrl("https://x.test/a.M3U8?x=1"))
        assertFalse(MediaClassifier.isHlsUrl("https://x.test/a.mp4"))
    }

    @Test fun patternsWithoutExtension() {
        assertEquals(Kind.UNKNOWN_MEDIA, kind("https://r1.cdn.example.com/videoplayback?id=1&mime=video%2Fmp4"))
        assertEquals(Kind.UNKNOWN_MEDIA, kind("https://cdn.example.com/stream?mime=audio/mp4&x=1"))
        assertEquals(Kind.UNKNOWN_MEDIA, kind("https://cdn.example.com/hls/abc123/index"))
        assertEquals(Kind.UNKNOWN_MEDIA, kind("https://cdn.example.com/player.php?src=https%3A%2F%2Fcdn.example.com%2Fx%2Fmaster.m3u8"))
    }

    @Test fun obviousNonMediaIsIgnored() {
        val ignored = listOf(
            "https://example.com/",
            "https://example.com/index.html",
            "https://example.com/app.js",
            "https://example.com/style.css?v=3",
            "https://example.com/font.woff2",
            "https://example.com/logo.png",
            "https://example.com/photo.JPG",
            "https://example.com/sprite.svg",
            "https://example.com/favicon.ico",
            "https://example.com/api/data.json",
            "https://example.com/captions.vtt",
            "https://example.com/some/path/without/extension",
            "ftp://example.com/clip.mp4",
            "not a url",
            "blob:https://example.com/3c1f9d9a",
            "data:video/mp4;base64,AAAA",
        )
        for (u in ignored) assertEquals(u, Kind.IGNORE, kind(u))
    }

    @Test fun trackingPixelsAreNotMedia() {
        assertEquals(Kind.IGNORE, kind("https://ads.example.com/pixel.gif?event=video_start&src=a.mp4"))
        assertEquals(Kind.IGNORE, kind("https://stats.example.com/collect?v=1&t=event&ea=play&el=clip.mp4"))
        assertEquals(Kind.IGNORE, kind("https://t.example.com/beacon.png?media=movie.mp4"))
        assertEquals(Kind.IGNORE, kind("https://t.example.com/track?video=true"))
    }

    @Test fun youtubeStreamsAreNeverOffered() {
        assertEquals(Kind.IGNORE, kind("https://rr3---sn-abc.googlevideo.com/videoplayback?mime=video%2Fmp4&itag=22"))
        assertEquals(Kind.IGNORE, kind("https://googlevideo.com/videoplayback?mime=audio%2Fwebm"))
    }

    @Test fun tsAndM4sChunksAreSegments() {
        assertEquals(Kind.SEGMENT, kind("https://cdn.example.com/hls/seg-001.ts"))
        assertEquals(Kind.SEGMENT, kind("https://cdn.example.com/v/chunk_0003.m4s?x=1"))
    }

    @Test fun chunkLikeNamesAreRecognisedOnlyForStreamingExtensions() {
        assertTrue(MediaClassifier.isChunkLike("https://x.test/a/seg-12.ts"))
        assertTrue(MediaClassifier.isChunkLike("https://x.test/a/frag-5.aac"))
        assertTrue(MediaClassifier.isChunkLike("https://x.test/a/segment3.mp4"))
        assertTrue(MediaClassifier.isChunkLike("https://x.test/a/init.mp4"))
        assertTrue(MediaClassifier.isChunkLike("https://x.test/a/chunk_0007.m4a"))
        assertFalse(MediaClassifier.isChunkLike("https://x.test/a/holiday.mp4"))
        assertFalse(MediaClassifier.isChunkLike("https://x.test/a/podcast-episode-12.mp3"))
        assertFalse(MediaClassifier.isChunkLike("https://x.test/a/song.aac"))
    }

    @Test fun normalizationKeepsQueryButDropsFragmentAndCase() {
        assertEquals(
            MediaClassifier.normalize("HTTPS://CDN.Example.com:443/a.mp4?token=Abc#t=5"),
            MediaClassifier.normalize("https://cdn.example.com/a.mp4?token=Abc"),
        )
        assertNotEquals(
            MediaClassifier.normalize("https://cdn.example.com/a.mp4?token=1"),
            MediaClassifier.normalize("https://cdn.example.com/a.mp4?token=2"),
        )
    }

    @Test fun youtubePages() {
        assertTrue(MediaClassifier.isYoutubePage("https://www.youtube.com/watch?v=abc"))
        assertTrue(MediaClassifier.isYoutubePage("https://m.youtube.com/watch?v=abc"))
        assertTrue(MediaClassifier.isYoutubePage("https://youtu.be/abc"))
        assertTrue(MediaClassifier.isYoutubePage("https://www.youtube-nocookie.com/embed/abc"))
        assertFalse(MediaClassifier.isYoutubePage("https://notyoutube.com/watch?v=abc"))
        assertFalse(MediaClassifier.isYoutubePage("https://example.com/youtube.com"))
        assertFalse(MediaClassifier.isYoutubePage(null))
    }

    @Test fun extensionOfHandlesOddPaths() {
        assertEquals("mp4", MediaClassifier.extensionOf("/a/b/c.MP4"))
        assertEquals("", MediaClassifier.extensionOf("/a/b/c"))
        assertEquals("", MediaClassifier.extensionOf("/a/b/c."))
        assertEquals("", MediaClassifier.extensionOf("/a/b.d/c"))
        assertEquals("", MediaClassifier.extensionOf("/a/file.verylongextension"))
    }
}
