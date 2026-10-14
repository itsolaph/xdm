package com.olaph.xdm.vault

import com.olaph.xdm.core.DeleteStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class VaultStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var vault: VaultStore

    @Before fun setUp() {
        vault = VaultStore(File(tmp.root, "files/private"))
    }

    private fun names() = vault.dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun saveMovesTheFileInUnderAUniqueName() {
        val a = tmp.newFile("1.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = tmp.newFile("2.part").apply { writeBytes(byteArrayOf(4)) }
        val s1 = vault.save(a, "clip.mp4", "video/mp4")
        val s2 = vault.save(b, "clip.mp4", "video/mp4")
        assertEquals("clip.mp4", s1.fileName)
        assertEquals("clip (1).mp4", s2.fileName)
        assertEquals("Private folder/clip.mp4", s1.displayPath)
        assertEquals(File(vault.dir, "clip.mp4").absolutePath, s1.location)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(s1.location).readBytes())
        assertFalse("moved, not copied", a.exists())
        assertEquals(listOf("clip (1).mp4", "clip.mp4"), names())
    }

    @Test fun importChecksTheSizeAndLeavesNothingBehindOnFailure() {
        val ok = vault.importStream(ByteArray(1000).inputStream(), "ok.bin", 1000)
        assertEquals(1000L, File(ok.location).length())
        try {
            vault.importStream(ByteArray(10).inputStream(), "short.bin", 1000)
            fail("a short copy must fail")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
        val broken = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 100) 1 else throw IOException("gone")
        }
        try {
            vault.importStream(broken, "broken.bin", -1)
            fail("a read error must fail")
        } catch (_: IOException) {
        }
        assertEquals("no partial files", listOf("ok.bin"), names())
    }

    @Test fun namesAreSanitized() {
        val s = vault.importStream(byteArrayOf(1).inputStream(), "../../../etc/passwd", -1)
        assertEquals("passwd", s.fileName)
        assertEquals(vault.dir.canonicalFile, File(s.location).canonicalFile.parentFile)
    }

    @Test fun deleteAndOpenOnlyWorkInsideTheFolder() {
        val outside = tmp.newFile("public.mp4").apply { writeText("x") }
        assertEquals(DeleteStatus.FAILED, vault.delete(outside.absolutePath))
        assertTrue(outside.exists())
        try {
            vault.open(outside.absolutePath)
            fail("must not read outside the private folder")
        } catch (_: IOException) {
        }
        assertEquals(DeleteStatus.FAILED, vault.delete(File(vault.dir, "../public.mp4").path))

        val s = vault.importStream(byteArrayOf(7).inputStream(), "a.bin", 1)
        assertEquals(7, vault.open(s.location).use { it.read() })
        assertEquals(DeleteStatus.DELETED, vault.delete(s.location))
        assertEquals(DeleteStatus.MISSING, vault.delete(s.location))
    }

    @Test fun renameKeepsTheFileInTheFolder() {
        val a = vault.importStream(byteArrayOf(1).inputStream(), "a.mp4", 1)
        vault.importStream(byteArrayOf(2).inputStream(), "b.mp4", 1)
        assertEquals("a.mp4", vault.rename(a.location, "a.mp4")?.fileName)
        val renamed = vault.rename(a.location, "b.mp4")!!
        assertEquals("b (1).mp4", renamed.fileName)
        assertNull("old location is gone", vault.rename(a.location, "c.mp4"))
        assertNull(vault.rename(tmp.newFile("x").absolutePath, "y"))
        assertEquals(listOf("b (1).mp4", "b.mp4"), names())
    }

    @Test fun wipeRemovesEverything() {
        vault.importStream(byteArrayOf(1).inputStream(), "a.mp4", 1)
        assertTrue(vault.wipe())
        assertFalse(vault.dir.exists())
        assertTrue("works again afterwards", File(vault.importStream(byteArrayOf(1).inputStream(), "b.mp4", 1).location).exists())
    }
}
