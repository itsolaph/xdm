package com.olaph.xdm.media

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFilesTest {
    private fun file(name: String, mime: String? = null, kind: String = DownloadKind.HTTP) =
        DownloadEntity(url = "https://example.com/x", fileName = name, mimeType = mime, kind = kind)

    @Test fun timesAreFormattedLikeAPlayer() {
        assertEquals("0:00", MediaFiles.formatTime(-5))
        assertEquals("0:07", MediaFiles.formatTime(7_999))
        assertEquals("12:34", MediaFiles.formatTime(754_000))
        assertEquals("1:02:03", MediaFiles.formatTime(3_723_000))
    }

    @Test fun mediaIsDecidedByNameThenByServerType() {
        assertTrue(MediaFiles.isMedia(file("a.mp4")))
        assertTrue(MediaFiles.isMedia(file("a.mp3")))
        assertTrue(MediaFiles.isMedia(file("download", mime = "video/webm")))
        assertFalse(MediaFiles.isMedia(file("a.zip", mime = "video/mp4")))
        assertFalse(MediaFiles.isMedia(file("a.pdf")))
        assertTrue(MediaFiles.isMedia(file("stream", kind = DownloadKind.HLS)))
    }

    @Test fun audioIsMediaButNotVideo() {
        assertTrue(MediaFiles.isVideo(file("a.mkv")))
        assertFalse(MediaFiles.isVideo(file("a.flac")))
        assertTrue(MediaFiles.isVideo(file("stream", kind = DownloadKind.HLS)))
    }
}
