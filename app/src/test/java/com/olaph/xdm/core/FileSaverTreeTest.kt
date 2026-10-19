package com.olaph.xdm.core

import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.util.Random

/**
 * [FileSaver] against a chosen folder (a storage-access-framework tree), with a small documents provider over a temp
 * directory standing in for the SD card. Covers saving, sorting, reading, deleting, and the fallback to Download/XDM when
 * the folder cannot be used. (The default MediaStore location needs a device; the debug SaveTestActivity checks that.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class) // 28: the default folder is a plain directory here
class FileSaverTreeTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A folder tree on disk exposed as documents. Document ids are paths below the root, the root's id is "root". */
    class TestProvider : DocumentsProvider() {
        override fun onCreate() = true

        private fun file(id: String): File = if (id == "root") root else File(root, id.removePrefix("root/"))
        private fun id(f: File): String = if (f.canonicalFile == root.canonicalFile) "root" else "root/" + f.relativeTo(root).path

        private fun row(c: MatrixCursor, f: File) {
            if (!f.exists()) throw FileNotFoundException(f.path)
            val flags = if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
            else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            c.newRow().add(Document.COLUMN_DOCUMENT_ID, id(f)).add(Document.COLUMN_DISPLAY_NAME, f.name.ifEmpty { "SD card" })
                .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
                .add(Document.COLUMN_FLAGS, flags).add(Document.COLUMN_SIZE, f.length()).add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
        }

        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
            MatrixCursor(projection ?: DOC_COLUMNS).also { row(it, file(documentId)) }

        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection ?: DOC_COLUMNS).also { c -> file(parentDocumentId).listFiles().orEmpty().forEach { row(c, it) } }

        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if (File(documentId).name in failWrites) throw FileNotFoundException("simulated write failure")
            return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
        }

        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val f = File(file(parentDocumentId), displayName)
            if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
            return id(f)
        }

        override fun deleteDocument(documentId: String) {
            // like the real providers: a document that is not there is an error, not a success
            if (!file(documentId).exists() || !file(documentId).deleteRecursively()) throw FileNotFoundException(documentId)
        }

        override fun isChildDocument(parentDocumentId: String, documentId: String) =
            file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath)

        companion object {
            const val AUTHORITY = "com.olaph.xdm.test.docs"
            lateinit var root: File
            val failWrites = mutableSetOf<String>()
            private val DOC_COLUMNS = arrayOf(
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
            )
        }
    }

    /** The tests also run on Windows, where paths use backslashes. */
    private fun String.unix() = replace('\\', '/')

    private val context get() = RuntimeEnvironment.getApplication()
    private val tree: Uri get() = DocumentsContract.buildTreeDocumentUri(TestProvider.AUTHORITY, "root")
    private val payload = ByteArray(300 * 1024 + 9).also { Random(2).nextBytes(it) }
    private var fallbackCalls = 0
    private lateinit var temp: File
    private lateinit var card: File

    @Before fun setUp() {
        card = tmp.newFolder("card")
        TestProvider.root = card
        TestProvider.failWrites.clear()
        val info = ProviderInfo().apply {
            authority = TestProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        Robolectric.buildContentProvider(TestProvider::class.java).create(info)
        temp = File(tmp.root, "download.part").also { it.writeBytes(payload) }
        fallbackCalls = 0
    }

    private fun grant() {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private fun saver(sort: Boolean = false, treeUri: Uri? = tree) =
        FileSaver(context, target = { SaveTarget(treeUri?.toString(), sort) }, onFolderUnavailable = { fallbackCalls++ })

    // ---- the chosen folder ----

    @Test fun savesIntoTheChosenFolderAndReadsItBack() {
        grant()
        val s = saver()
        val saved = s.save(temp, "movie.bin", "application/octet-stream")
        assertEquals("movie.bin", saved.fileName)
        assertNull(saved.note)
        assertTrue("a document Uri inside the tree: ${saved.location}", saved.location.contains("/tree/"))
        assertArrayEquals(payload, File(card, "movie.bin").readBytes())
        assertTrue(s.exists(saved.location))
        assertArrayEquals(payload, s.open(saved.location).use { it.readBytes() })
        assertEquals(0, fallbackCalls)
    }

    @Test fun twoFilesWithTheSameNameGetUniqueNames() {
        grant()
        val s = saver()
        val a = s.save(temp, "same.bin", null)
        val b = s.save(temp, "same.bin", null)
        assertEquals("same.bin", a.fileName)
        assertEquals("same (1).bin", b.fileName)
        assertTrue(File(card, "same (1).bin").isFile)
    }

    @Test fun sortingPutsFilesIntoTypeSubfoldersThatAreReused() {
        grant()
        val s = saver(sort = true)
        val video = s.save(temp, "clip.mp4", "video/mp4")
        val video2 = s.save(temp, "other.mkv", "video/x-matroska")
        val doc = s.save(temp, "paper.pdf", "application/pdf")
        val unknown = s.save(temp, "blob.xyz", null)
        assertTrue(File(card, "Video/clip.mp4").isFile)
        assertTrue(File(card, "Video/other.mkv").isFile)
        assertTrue(File(card, "Documents/paper.pdf").isFile)
        assertTrue(File(card, "Other/blob.xyz").isFile)
        assertEquals("one Video folder, not two", 1, card.listFiles { f -> f.name.startsWith("Video") }!!.size)
        assertTrue(video.displayPath.endsWith("/Video/clip.mp4"))
        assertTrue(video2.displayPath.endsWith("/Video/other.mkv"))
        assertTrue(doc.displayPath.endsWith("/Documents/paper.pdf"))
        assertTrue(unknown.displayPath.endsWith("/Other/blob.xyz"))
    }

    @Test fun deleteMapsToTheStatuses() {
        grant()
        val s = saver()
        val saved = s.save(temp, "gone.bin", null)
        assertEquals(DeleteStatus.DELETED, s.delete(saved.location))
        assertFalse(File(card, "gone.bin").exists())
        assertFalse(s.exists(saved.location))
        assertEquals("already gone", DeleteStatus.MISSING, s.delete(saved.location))
    }

    @Test fun aMixOfSavedPlacesCanBeDeletedTogether() {
        grant()
        val s = saver()
        val inTree = s.save(temp, "tree.bin", null).location
        val legacy = File(tmp.root, "legacy.bin").also { it.writeBytes(payload) }.absolutePath
        val missingLegacy = File(tmp.root, "never.bin").absolutePath
        val results = listOf(inTree, legacy, missingLegacy).map { s.delete(it) }
        assertEquals(listOf(DeleteStatus.DELETED, DeleteStatus.DELETED, DeleteStatus.MISSING), results)
        assertTrue(s.deleteRequest(listOf(inTree, legacy)) == null) // nothing here the system dialog could handle
    }

    @Test fun aFailedWriteLeavesNoPartialDocumentBehind() {
        grant()
        TestProvider.failWrites += "broken.bin"
        val s = saver()
        val saved = s.save(temp, "broken.bin", null) // falls back to the default folder
        assertFalse("no half-written file in the chosen folder", File(card, "broken.bin").exists())
        assertEquals(FileSaver.FOLDER_UNAVAILABLE_NOTE, saved.note)
        assertEquals(1, fallbackCalls)
    }

    // ---- the folder is gone ----

    @Test fun withoutTheAccessTheFileGoesToDownloadXdmWithANote() {
        // no takePersistableUriPermission: the access was revoked
        val s = saver()
        val saved = s.save(temp, "kept.bin", null)
        assertEquals("Saved to Download/XDM: chosen folder unavailable", saved.note)
        assertEquals(1, fallbackCalls)
        assertTrue("the file is in the default folder: ${saved.location}", saved.location.unix().contains("/Download/XDM/"))
        assertArrayEquals(payload, File(saved.location).readBytes())
        assertFalse(File(card, "kept.bin").exists())
    }

    @Test fun aFolderThatNoLongerExistsFallsBackToo() {
        grant()
        card.deleteRecursively() // the card was pulled out
        val saved = saver().save(temp, "late.bin", null)
        assertEquals(FileSaver.FOLDER_UNAVAILABLE_NOTE, saved.note)
        assertArrayEquals(payload, File(saved.location).readBytes())
    }

    @Test fun noChosenFolderMeansNoNoteAndNoWarning() {
        val saved = saver(treeUri = null).save(temp, "plain.bin", null)
        assertNull(saved.note)
        assertEquals(0, fallbackCalls)
        assertTrue(saved.location.unix().contains("/Download/XDM/"))
    }

    @Test fun sortingAlsoAppliesToTheDefaultFolder() {
        val saved = saver(sort = true, treeUri = null).save(temp, "song.mp3", "audio/mpeg")
        assertTrue(saved.location, saved.location.unix().contains("/Download/XDM/Audio/song.mp3"))
        assertEquals("Download/XDM/Audio/song.mp3", saved.displayPath)
    }

    @Test fun folderNameAndAvailability() {
        assertFalse("without the access the folder is not usable", saver().folderAvailable(tree.toString()))
        grant()
        assertTrue(saver().folderAvailable(tree.toString()))
        assertEquals("card", saver().folderLabel(tree.toString()))
    }
}
