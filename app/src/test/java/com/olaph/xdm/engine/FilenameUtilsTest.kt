package com.olaph.xdm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameUtilsTest {

    // --- Content-Disposition ---

    @Test fun quotedFilename() {
        assertEquals("report.pdf", FilenameUtils.fromContentDisposition("attachment; filename=\"report.pdf\""))
    }

    @Test fun unquotedFilename() {
        assertEquals("a.zip", FilenameUtils.fromContentDisposition("attachment; filename=a.zip"))
    }

    @Test fun semicolonInsideQuotes() {
        assertEquals("a;b.zip", FilenameUtils.fromContentDisposition("attachment; filename=\"a;b.zip\"; size=3"))
    }

    @Test fun escapedQuote() {
        assertEquals("he said \"hi\".txt", FilenameUtils.fromContentDisposition("attachment; filename=\"he said \\\"hi\\\".txt\""))
    }

    @Test fun rfc5987Utf8() {
        assertEquals(
            "€ rates.pdf",
            FilenameUtils.fromContentDisposition("attachment; filename*=UTF-8''%E2%82%AC%20rates.pdf"),
        )
    }

    @Test fun rfc5987WinsOverPlainFilename() {
        val h = "attachment; filename=\"fallback.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"
        assertEquals("résumé.pdf", FilenameUtils.fromContentDisposition(h))
    }

    @Test fun rfc5987Latin1() {
        assertEquals("é.txt", FilenameUtils.fromContentDisposition("attachment; filename*=ISO-8859-1'en'%E9.txt"))
    }

    @Test fun rfc5987WithLanguageTagAndSpacesAroundEquals() {
        assertEquals("x y.bin", FilenameUtils.fromContentDisposition("attachment ; filename* = utf-8'de'x%20y.bin"))
    }

    @Test fun percentEncodedPlainFilename() {
        assertEquals("my file.zip", FilenameUtils.fromContentDisposition("attachment; filename=\"my%20file.zip\""))
    }

    @Test fun plusIsNotDecodedAsSpace() {
        assertEquals("a+b.zip", FilenameUtils.fromContentDisposition("attachment; filename=\"a+b.zip\""))
    }

    @Test fun noFilename() {
        assertNull(FilenameUtils.fromContentDisposition("inline"))
        assertNull(FilenameUtils.fromContentDisposition(null))
        assertNull(FilenameUtils.fromContentDisposition(""))
        assertNull(FilenameUtils.fromContentDisposition("attachment; filename=\"\""))
    }

    // --- URL ---

    @Test fun nameFromUrlPath() {
        assertEquals("file.zip", FilenameUtils.fromUrl("https://example.com/a/b/file.zip?token=1#x"))
    }

    @Test fun nameFromUrlIsDecoded() {
        assertEquals("my file (1).mp4", FilenameUtils.fromUrl("https://example.com/my%20file%20(1).mp4"))
    }

    @Test fun hostOnlyUrlHasNoName() {
        assertNull(FilenameUtils.fromUrl("https://example.com"))
        assertNull(FilenameUtils.fromUrl("https://example.com/"))
    }

    @Test fun trailingSlashUsesLastSegment() {
        assertEquals("dir", FilenameUtils.fromUrl("https://example.com/dir/"))
    }

    // --- resolve ---

    @Test fun resolvePrefersContentDisposition() {
        assertEquals(
            "real.zip",
            FilenameUtils.resolve("attachment; filename=real.zip", "https://x.org/download.php", "application/zip"),
        )
    }

    @Test fun resolveFallsBackToUrl() {
        assertEquals("movie.mkv", FilenameUtils.resolve(null, "https://x.org/v/movie.mkv", "video/x-matroska"))
    }

    @Test fun resolveFallsBackToMime() {
        assertEquals("download.pdf", FilenameUtils.resolve(null, "https://x.org/", "application/pdf"))
    }

    @Test fun resolveAddsExtensionFromMime() {
        assertEquals("get.zip", FilenameUtils.resolve(null, "https://x.org/get", "application/zip"))
    }

    @Test fun resolveWithNothing() {
        assertEquals("download", FilenameUtils.resolve(null, "https://x.org/", null))
        assertEquals("download", FilenameUtils.resolve(null, "https://x.org/", "application/octet-stream"))
    }

    // --- sanitize ---

    @Test fun sanitizeStripsPathTraversal() {
        assertEquals("passwd", FilenameUtils.sanitize("../../etc/passwd"))
        assertEquals("evil.exe", FilenameUtils.sanitize("..\\..\\evil.exe"))
    }

    @Test fun sanitizeReplacesIllegalCharacters() {
        assertEquals("a_b_c_d_e_f_g_h.txt", FilenameUtils.sanitize("a:b*c?d\"e<f>g|h.txt"))
        assertEquals("a_b.txt", FilenameUtils.sanitize("a\u0000b.txt"))
    }

    @Test fun sanitizeTrimsDotsAndSpaces() {
        assertEquals("name", FilenameUtils.sanitize("  ..name.. "))
        assertEquals("download", FilenameUtils.sanitize(".."))
        assertEquals("download", FilenameUtils.sanitize("   "))
        assertEquals("download", FilenameUtils.sanitize(""))
    }

    @Test fun sanitizeCollapsesWhitespace() {
        assertEquals("a b c.txt", FilenameUtils.sanitize("a   b \t c.txt"))
    }

    @Test fun sanitizeLimitsLengthKeepingExtension() {
        val n = FilenameUtils.sanitize("x".repeat(400) + ".mp4")
        assertTrue(n.length <= 180)
        assertTrue(n.endsWith(".mp4"))
    }

    @Test fun sanitizeKeepsUnicode() {
        assertEquals("日本語 🎵.mp3", FilenameUtils.sanitize("日本語 🎵.mp3"))
    }

    // --- collisions ---

    @Test fun uniqueNameNoCollision() {
        assertEquals("a.zip", FilenameUtils.uniqueName("a.zip") { false })
    }

    @Test fun uniqueNameAppendsCounter() {
        val taken = setOf("a.zip", "a (1).zip", "a (2).zip")
        assertEquals("a (3).zip", FilenameUtils.uniqueName("a.zip") { it in taken })
    }

    @Test fun uniqueNameWithoutExtension() {
        assertEquals("README (1)", FilenameUtils.uniqueName("README") { it == "README" })
    }

    @Test fun uniqueNameKeepsTarGz() {
        assertEquals("src (1).tar.gz", FilenameUtils.uniqueName("src.tar.gz") { it == "src.tar.gz" })
    }

    @Test fun uniqueNameDotInBaseName() {
        assertEquals("v1.2 final (1).zip", FilenameUtils.uniqueName("v1.2 final.zip") { it == "v1.2 final.zip" })
    }

    @Test fun splitExtensionIgnoresLeadingDotAndSentences() {
        assertEquals(".bashrc" to "", FilenameUtils.splitExtension(".bashrc"))
        assertEquals("Mr. Smith report" to "", FilenameUtils.splitExtension("Mr. Smith report"))
    }
}
