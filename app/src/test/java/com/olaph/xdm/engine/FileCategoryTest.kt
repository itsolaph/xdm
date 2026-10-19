package com.olaph.xdm.engine

import com.olaph.xdm.engine.FileCategory.APPS
import com.olaph.xdm.engine.FileCategory.ARCHIVES
import com.olaph.xdm.engine.FileCategory.AUDIO
import com.olaph.xdm.engine.FileCategory.DOCUMENTS
import com.olaph.xdm.engine.FileCategory.IMAGES
import com.olaph.xdm.engine.FileCategory.OTHER
import com.olaph.xdm.engine.FileCategory.VIDEO
import org.junit.Assert.assertEquals
import org.junit.Test

class FileCategoryTest {
    private fun of(name: String, mime: String? = null) = FileCategory.of(name, mime)

    @Test fun theExtensionDecides() {
        assertEquals(VIDEO, of("clip.mp4"))
        assertEquals(VIDEO, of("clip.MKV"))
        assertEquals(AUDIO, of("song.flac"))
        assertEquals(IMAGES, of("photo.jpeg"))
        assertEquals(DOCUMENTS, of("paper.pdf"))
        assertEquals(DOCUMENTS, of("notes.txt"))
        assertEquals(DOCUMENTS, of("book.epub"))
        assertEquals(ARCHIVES, of("backup.zip"))
        assertEquals(ARCHIVES, of("a.tar.gz"))
        assertEquals(ARCHIVES, of("disk.iso"))
        assertEquals(APPS, of("app.apk"))
        assertEquals(APPS, of("setup.exe"))
    }

    @Test fun anExtensionBeatsAMisleadingServerType() {
        assertEquals(VIDEO, of("clip.mp4", "application/octet-stream"))
        assertEquals(ARCHIVES, of("data.zip", "text/html"))
    }

    @Test fun theMimeTypeIsUsedWhenTheNameHasNoUsefulExtension() {
        assertEquals(VIDEO, of("download", "video/webm"))
        assertEquals(AUDIO, of("track.bin", "audio/ogg"))
        assertEquals(IMAGES, of("pic", "image/png; charset=binary"))
        assertEquals(DOCUMENTS, of("page", "text/html"))
        assertEquals(DOCUMENTS, of("file", "application/pdf"))
        assertEquals(APPS, of("file", MimeTypes.APK))
        assertEquals(ARCHIVES, of("file", "application/x-rar-compressed"))
    }

    @Test fun everythingElseIsOther() {
        assertEquals(OTHER, of("data.bin"))
        assertEquals(OTHER, of("noext"))
        assertEquals(OTHER, of("x.unknownext", "application/octet-stream"))
        assertEquals(OTHER, of(".hidden"))
    }

    @Test fun folderNames() {
        assertEquals(
            listOf("Video", "Audio", "Images", "Documents", "Archives", "Apps", "Other"),
            FileCategory.entries.map { it.folder },
        )
    }
}
