package com.olaph.xdm.core

import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.DownloadEngineTest
import com.olaph.xdm.service.NotificationText
import com.olaph.xdm.ui.DeleteScope
import com.olaph.xdm.ui.Filter
import com.olaph.xdm.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** Private downloads end up in the private folder (never the public store), and the moves between the two. */
class DownloadManagerPrivateTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var http: DownloadEngineTest.TestDispatcher
    private lateinit var dao: DownloadManagerTest.FakeDao
    private lateinit var files: DownloadManagerTest.FakeFiles
    private lateinit var notifier: DownloadManagerTest.RecordingNotifier
    private lateinit var vault: VaultStore
    private lateinit var publicDir: File
    private lateinit var scope: CoroutineScope

    private fun manager(privateStore: VaultStore? = vault) = DownloadManager(
        scope, dao, DownloadManagerTest.FakeSettings(AppSettings(connections = 2, maxParallel = 2, convertHlsToMp4 = false)),
        DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40),
        files, tmp.newFolder("temp-${System.nanoTime()}"), DownloadManagerTest.FakeNetwork(), notifier,
        privateStore = privateStore,
    )

    @Before fun setUp() {
        http = DownloadEngineTest.TestDispatcher(ByteArray(2 * 1024 * 1024).also { Random(7).nextBytes(it) })
        server = MockWebServer().apply { dispatcher = http; start() }
        dao = DownloadManagerTest.FakeDao()
        publicDir = tmp.newFolder("public")
        files = DownloadManagerTest.FakeFiles(publicDir)
        notifier = DownloadManagerTest.RecordingNotifier()
        vault = VaultStore(File(tmp.root, "internal/private"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun url(name: String) = server.url("/$name").toString()
    private fun status(id: Long) = dao.find(id)?.status

    private suspend fun waitUntil(what: String, cond: () -> Boolean) {
        try {
            withTimeout(20_000) { while (!cond()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what; rows=${dao.rows.value.map { it.id to it.status }}")
        }
    }

    private suspend fun publicRow(name: String, bytes: ByteArray, totalBytes: Long = bytes.size.toLong()): Pair<Long, File> {
        val f = File(publicDir, name).apply { writeBytes(bytes) }
        val id = dao.insert(
            DownloadEntity(
                url = "http://x/$name", fileName = name, totalBytes = totalBytes, status = DownloadStatus.COMPLETED,
                savedLocation = f.absolutePath, savedPath = "Download/XDM/$name", playbackPositionMs = 42_000,
            ),
        )
        return id to f
    }

    private suspend fun privateRow(name: String, bytes: ByteArray): Pair<Long, File> {
        val saved = vault.importStream(bytes.inputStream(), name, bytes.size.toLong())
        val id = dao.insert(
            DownloadEntity(
                url = "http://x/$name", fileName = saved.fileName, totalBytes = bytes.size.toLong(), status = DownloadStatus.COMPLETED,
                savedLocation = saved.location, savedPath = saved.displayPath, isPrivate = true,
            ),
        )
        return id to File(saved.location)
    }

    private fun vaultFiles() = vault.dir.listFiles().orEmpty().map { it.name }.sorted()

    // ---- downloads ----

    @Test fun privateDownloadCompletesIntoTheVaultAndNeverTouchesThePublicStore() = runBlocking {
        val m = manager()
        val id = m.add(url("secret.mp4"), "secret.mp4", 2, startNow = true, isPrivate = true)
        assertTrue(dao.find(id)!!.isPrivate)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

        val d = dao.find(id)!!
        assertTrue("still private", d.isPrivate)
        assertTrue("nothing went to the public store", files.saved.isEmpty())
        assertEquals(listOf("secret.mp4"), vaultFiles())
        assertEquals(File(vault.dir, "secret.mp4").absolutePath, d.savedLocation)
        assertEquals("Private folder/secret.mp4", d.savedPath)
        assertArrayEquals(http.payload, File(d.savedLocation!!).readBytes())
        assertFalse("temp file gone", m.tempFile(id).exists())

        waitUntil("notified") { notifier.completedEntities.isNotEmpty() }
        val notified = notifier.completedEntities.single()
        assertTrue(notified.isPrivate)
        assertEquals(NotificationText.PRIVATE_TITLE, NotificationText.title(notified))
        assertFalse("no file name in the text", NotificationText.completed(notified).contains("secret"))
    }

    @Test fun publicDownloadsStillGoToThePublicStore() = runBlocking {
        val m = manager()
        val id = m.add(url("open.bin"), "open.bin", 2, startNow = true)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(1, files.saved.size)
        assertTrue(vaultFiles().isEmpty())
        assertFalse(dao.find(id)!!.isPrivate)
    }

    @Test fun withoutAPrivateFolderAPrivateDownloadFailsInsteadOfGoingPublic() = runBlocking {
        val m = manager(privateStore = null)
        val id = m.add(url("secret.bin"), "secret.bin", 2, startNow = true, isPrivate = true)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertTrue("never saved publicly", files.saved.isEmpty())
        val failed = notifier.failed.single()
        assertEquals("Download failed", NotificationText.failed(failed))
        assertEquals(NotificationText.PRIVATE_TITLE, NotificationText.title(failed))
    }

    @Test fun notificationTextIsNeutralOnlyForPrivateItems() {
        val pub = DownloadEntity(id = 1, url = "u", fileName = "holiday.mp4", totalBytes = 2048, error = "Server error: HTTP 404")
        val priv = pub.copy(isPrivate = true)
        assertEquals("holiday.mp4", NotificationText.title(pub))
        assertEquals("Download complete · 2.0 KB", NotificationText.completed(pub))
        assertEquals("Download failed: Server error: HTTP 404", NotificationText.failed(pub))
        assertEquals("Private download", NotificationText.title(priv))
        for (text in listOf(NotificationText.title(priv), NotificationText.completed(priv), NotificationText.failed(priv))) {
            assertFalse(text, text.contains("holiday") || text.contains("404"))
        }
    }

    // ---- delete scoping ----

    @Test fun publicDeleteAllIgnoresPrivateItems() = runBlocking {
        val m = manager()
        val (pub, pubFile) = publicRow("a.mp4", ByteArray(100))
        val (priv, privFile) = privateRow("b.mp4", ByteArray(200))
        val activePrivate = dao.insert(DownloadEntity(url = "http://x/c", fileName = "c.mp4", status = DownloadStatus.PAUSED, isPrivate = true))

        // What the public list's "Delete all" picks: never the private rows, whatever tab.
        for (f in Filter.entries) {
            assertTrue(f.name, DeleteScope.inFilter(dao.rows.value, f).none { it.isPrivate })
        }
        // Even when private ids are handed in, the public delete leaves them alone.
        val report = m.deleteMany(listOf(pub, priv, activePrivate), deleteFiles = true)
        assertEquals(1, report.removed)
        assertNull(dao.find(pub)); assertFalse(pubFile.exists())
        assertNotNull(dao.find(priv)); assertTrue(privFile.exists())
        assertNotNull(dao.find(activePrivate))
    }

    @Test fun privateDeleteRemovesVaultFilesAndIgnoresPublicItems() = runBlocking {
        val m = manager()
        val (pub, pubFile) = publicRow("a.mp4", ByteArray(100))
        val (priv, privFile) = privateRow("b.mp4", ByteArray(200))
        val report = m.deleteMany(listOf(pub, priv), deleteFiles = true, privateItems = true)
        assertEquals(1, report.removed)
        assertEquals(200L, report.freedBytes)
        assertNull(dao.find(priv)); assertFalse(privFile.exists())
        assertNotNull(dao.find(pub)); assertTrue(pubFile.exists())
        assertTrue("the public store was not asked to delete anything", files.deleted.isEmpty())
    }

    // ---- move to / out of the private folder ----

    @Test fun moveToPrivateCopiesVerifiesThenDeletesThePublicCopy() = runBlocking {
        val m = manager()
        val bytes = ByteArray(300_000).also { Random(3).nextBytes(it) }
        val (id, pubFile) = publicRow("clip.mp4", bytes)

        val r = m.moveToPrivate(id)

        assertEquals(MoveResult.Moved("clip.mp4"), r)
        assertFalse("public copy deleted", pubFile.exists())
        val d = dao.find(id)!!
        assertTrue(d.isPrivate)
        assertEquals(File(vault.dir, "clip.mp4").absolutePath, d.savedLocation)
        assertEquals("Private folder/clip.mp4", d.savedPath)
        assertEquals("the resume position stays", 42_000L, d.playbackPositionMs)
        assertArrayEquals(bytes, File(d.savedLocation!!).readBytes())
        assertEquals("no partial files left", listOf("clip.mp4"), vaultFiles())
    }

    @Test fun moveToPrivateKeepsEverythingWhenTheCopyFails() = runBlocking {
        val m = manager()
        val (id, pubFile) = publicRow("clip.mp4", ByteArray(1000))
        files.unreadable += pubFile.absolutePath
        val before = dao.find(id)

        val r = m.moveToPrivate(id)

        assertTrue(r is MoveResult.Failed)
        assertEquals("record unchanged", before, dao.find(id))
        assertTrue(pubFile.exists())
        assertTrue("nothing left in the vault", vaultFiles().isEmpty())
        assertTrue(files.deleted.isEmpty())
    }

    @Test fun moveToPrivateRejectsACopyOfTheWrongSize() = runBlocking {
        val m = manager()
        // The record says 5000 bytes, the file has 1000: something is off, so nothing moves.
        val (id, pubFile) = publicRow("clip.mp4", ByteArray(1000), totalBytes = 5000)
        val r = m.moveToPrivate(id)
        assertTrue(r.toString(), r is MoveResult.Failed && r.reason.contains("incomplete"))
        assertFalse(dao.find(id)!!.isPrivate)
        assertTrue(pubFile.exists())
        assertTrue(vaultFiles().isEmpty())
    }

    @Test fun moveToPrivateWhenThePublicCopyMayNotBeDeletedKeepsBothAndAsks() = runBlocking {
        val m = manager()
        val (id, pubFile) = publicRow("old-install.mp4", ByteArray(500))
        files.denied += pubFile.absolutePath

        val r = m.moveToPrivate(id)

        assertEquals(MoveResult.PublicCopyLeft(DeleteTarget(id, pubFile.absolutePath, 500), askSystem = true), r)
        assertTrue("public copy still there", pubFile.exists())
        val d = dao.find(id)!!
        assertTrue("the record follows the private copy", d.isPrivate)
        assertTrue(File(d.savedLocation!!).exists())
    }

    @Test fun moveToPrivateWhenDeletingThePublicCopyFailsReportsIt() = runBlocking {
        val m = manager()
        val (id, pubFile) = publicRow("stuck.mp4", ByteArray(500))
        files.failing += pubFile.absolutePath
        val r = m.moveToPrivate(id)
        assertTrue(r is MoveResult.PublicCopyLeft && !r.askSystem)
        assertTrue(pubFile.exists())
        assertTrue(dao.find(id)!!.isPrivate)
    }

    @Test fun onlyFinishedPublicItemsCanBeMovedIn() = runBlocking {
        val m = manager()
        val running = dao.insert(DownloadEntity(url = "http://x/r", fileName = "r.mp4", status = DownloadStatus.PAUSED))
        val (priv, _) = privateRow("p.mp4", ByteArray(10))
        assertTrue(m.moveToPrivate(running) is MoveResult.Failed)
        assertTrue(m.moveToPrivate(priv) is MoveResult.Failed)
        assertTrue(manager(privateStore = null).moveToPrivate(running) is MoveResult.Failed)
    }

    @Test fun moveOutGoesThroughThePublicStoreAndLeavesTheVault() = runBlocking {
        val m = manager()
        val bytes = ByteArray(4096).also { Random(4).nextBytes(it) }
        val (id, privFile) = privateRow("back.mp4", bytes)

        val r = m.moveOutOfPrivate(id)

        assertEquals(MoveResult.Moved("back.mp4"), r)
        assertFalse(privFile.exists())
        val d = dao.find(id)!!
        assertFalse(d.isPrivate)
        assertEquals("Download/XDM/back.mp4", d.savedPath)
        assertArrayEquals(bytes, files.saved.single().readBytes())
    }

    @Test fun renameStaysInsideTheVault() = runBlocking {
        val m = manager()
        val (id, _) = privateRow("a.mp4", ByteArray(10))
        privateRow("b.mp4", ByteArray(10))
        assertEquals("b (1).mp4", m.renamePrivate(id, "b.mp4"))
        assertEquals("../../escape.mp4 is reduced to a plain name", "escape.mp4", m.renamePrivate(id, "../../escape.mp4"))
        val d = dao.find(id)!!
        assertEquals(File(vault.dir, "escape.mp4").absolutePath, d.savedLocation)
        assertEquals(listOf("b.mp4", "escape.mp4"), vaultFiles())
    }
}
