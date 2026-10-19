package com.olaph.xdm.core

import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadDao
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.PauseNote
import com.olaph.xdm.data.SegmentEntity
import com.olaph.xdm.data.SettingsSource
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.DownloadEngineSplitTest
import com.olaph.xdm.engine.DownloadEngineTest
import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.engine.HeaderCodec
import com.olaph.xdm.remux.RemuxException
import com.olaph.xdm.remux.RemuxResult
import com.olaph.xdm.remux.Remuxer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
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
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class DownloadManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    // ---- fakes ----

    internal class FakeDao : DownloadDao() {
        val rows = MutableStateFlow<List<DownloadEntity>>(emptyList())
        val segs = MutableStateFlow<Map<Long, List<SegmentEntity>>>(emptyMap())
        private val ids = AtomicLong()

        fun mutate(id: Long, f: (DownloadEntity) -> DownloadEntity) = rows.update { l -> l.map { if (it.id == id) f(it) else it } }
        fun find(id: Long) = rows.value.firstOrNull { it.id == id }

        override fun observeAll(): Flow<List<DownloadEntity>> = rows.map { it.sortedByDescending { d -> d.id } }
        override fun observe(id: Long): Flow<DownloadEntity?> = rows.map { l -> l.firstOrNull { it.id == id } }
        override fun observeOpen(): Flow<List<DownloadEntity>> = rows.map { l ->
            l.filter { it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED) }
        }
        override fun observeActiveCount(): Flow<Int> = rows.map { l ->
            l.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING }
        }
        override suspend fun get(id: Long) = find(id)
        override suspend fun getByStatus(status: DownloadStatus) = rows.value.filter { it.status == status }.sortedBy { it.id }
        override suspend fun insert(entity: DownloadEntity): Long {
            val id = ids.incrementAndGet()
            rows.update { it + entity.copy(id = id) }
            return id
        }
        override suspend fun delete(id: Long) { rows.update { l -> l.filter { it.id != id } }; segs.update { it - id } }
        override suspend fun setStatus(id: Long, status: DownloadStatus, error: String?) {
            mutate(id) { it.copy(status = status, error = error, forceStart = false) }
        }
        override suspend fun requeue(id: Long, force: Boolean) {
            mutate(id) { it.copy(status = DownloadStatus.QUEUED, error = null, forceStart = force) }
        }
        override suspend fun requeueRunning() {
            rows.update { l -> l.map { if (it.status == DownloadStatus.RUNNING) it.copy(status = DownloadStatus.QUEUED) else it } }
        }
        override suspend fun pauseAllNow() = Unit
        override suspend fun resumeAllNow() = Unit
        override suspend fun updatePlan(id: Long, total: Long, ranges: Boolean, etag: String?, lastModified: String?, mime: String?) {
            mutate(id) { it.copy(totalBytes = total, acceptRanges = ranges, etag = etag, lastModified = lastModified, mimeType = it.mimeType ?: mime) }
        }
        override suspend fun setDownloaded(id: Long, downloaded: Long) { mutate(id) { it.copy(downloadedBytes = downloaded) } }
        override suspend fun markCompleted(id: Long, at: Long, location: String, path: String, name: String, total: Long, note: String?) {
            mutate(id) {
                it.copy(
                    status = DownloadStatus.COMPLETED, completedAt = at, savedLocation = location, savedPath = path,
                    fileName = name, totalBytes = total, downloadedBytes = total, error = note,
                )
            }
        }
        override suspend fun rename(id: Long, name: String) { mutate(id) { it.copy(fileName = name) } }
        override suspend fun setYoutubePlan(id: Long, extra: String?, name: String, total: Long, mime: String?) {
            mutate(id) { it.copy(extra = extra, fileName = name, totalBytes = total, mimeType = mime, acceptRanges = true) }
        }
        override suspend fun setHlsProgress(id: Long, done: Int, total: Int, bytes: Long) {
            mutate(id) { it.copy(segmentsDone = done, segmentsTotal = total, downloadedBytes = bytes) }
        }
        override suspend fun setSavedFile(id: Long, location: String, path: String, name: String, isPrivate: Boolean) {
            mutate(id) { it.copy(savedLocation = location, savedPath = path, fileName = name, isPrivate = isPrivate) }
        }
        override suspend fun privateIds(): List<Long> = rows.value.filter { it.isPrivate }.map { it.id }
        override suspend fun getSystemPaused(): List<DownloadEntity> =
            rows.value.filter { it.status == DownloadStatus.PAUSED && it.error == PauseNote.SYSTEM_LIMIT }.sortedBy { it.id }
        override suspend fun setLink(id: Long, url: String, headers: String?, sourcePage: String?) {
            mutate(id) { it.copy(url = url, headers = headers, sourcePage = sourcePage ?: it.sourcePage) }
        }
        override suspend fun getSegments(id: Long) = segs.value[id].orEmpty().sortedBy { it.idx }
        override fun observeSegments(id: Long): Flow<List<SegmentEntity>> = segs.map { it[id].orEmpty() }
        override suspend fun upsertSegments(segments: List<SegmentEntity>) {
            segs.update { m ->
                val out = m.toMutableMap()
                for (s in segments) out[s.downloadId] = out[s.downloadId].orEmpty().filter { it.idx != s.idx } + s
                out
            }
        }
        override suspend fun deleteSegments(id: Long) { segs.update { it - id } }
    }

    internal class FakeSettings(initial: AppSettings) : SettingsSource {
        val state = MutableStateFlow(initial)
        override val flow: Flow<AppSettings> = state
        override suspend fun current() = state.value
    }

    internal class FakeNetwork : NetworkState {
        override val metered = MutableStateFlow(false)
    }

    /** Stands in for the media3 remuxer; what it does is chosen per test, and [started] is a deterministic gate. */
    private class FakeRemuxer : Remuxer {
        enum class Mode { SUCCESS, FAIL, CRASH, BLOCK }
        @Volatile var mode = Mode.SUCCESS
        @Volatile var extension = "mp4"
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val inputs = CopyOnWriteArrayList<ByteArray>()
        /** Completed when a BLOCK conversion has reported 50% and is waiting. */
        @Volatile var started = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun remux(input: File, output: File, onProgress: (Long, Long) -> Unit): RemuxResult {
            calls.incrementAndGet()
            inputs += input.readBytes()
            when (mode) {
                Mode.FAIL -> throw RemuxException("unsupported audio codec (AC-3)")
                Mode.CRASH -> {
                    output.writeText("half")
                    throw IllegalStateException("muxer exploded")
                }
                Mode.BLOCK -> {
                    output.writeText("partial")
                    onProgress(50, 100)
                    started.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
                Mode.SUCCESS -> {
                    onProgress(50, 100)
                    output.writeBytes("MP4:".toByteArray() + input.readBytes())
                    onProgress(100, 100)
                }
            }
            val audioOnly = extension == "m4a"
            return RemuxResult(extension, if (audioOnly) "audio/mp4" else "video/mp4", 1_000_000, 10, 10, 20, 20, 0)
        }
    }

    internal class FakeFiles(private val dir: File) : FileStore {
        val saved = CopyOnWriteArrayList<File>()
        val mimes = CopyOnWriteArrayList<String?>()
        override fun save(temp: File, desiredName: String, mimeType: String?): Saved {
            val name = FilenameUtils.uniqueName(desiredName) { File(dir, it).exists() }
            val target = File(dir, name)
            temp.copyTo(target)
            saved += target
            mimes += mimeType
            return Saved(target.absolutePath, "Download/XDM/$name", name)
        }
        /** Locations the "system" refuses to delete without a confirmation, and ones that fail outright. */
        val denied = CopyOnWriteArrayList<String>()
        val failing = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<String>()
        override fun delete(location: String): DeleteStatus {
            if (location in denied) return DeleteStatus.DENIED
            if (location in failing) return DeleteStatus.FAILED
            val f = File(location)
            if (!f.exists()) return DeleteStatus.MISSING
            deleted += location
            return if (f.delete()) DeleteStatus.DELETED else DeleteStatus.FAILED
        }
        /** Locations whose content cannot be read (the copy into the private folder fails). */
        val unreadable = CopyOnWriteArrayList<String>()
        override fun open(location: String): java.io.InputStream {
            if (location in unreadable) throw java.io.IOException("read error")
            return File(location).inputStream()
        }
    }

    internal class RecordingNotifier : DownloadNotifier {
        val completed = CopyOnWriteArrayList<Long>()
        val completedEntities = CopyOnWriteArrayList<DownloadEntity>()
        val failed = CopyOnWriteArrayList<DownloadEntity>()
        override fun onCompleted(download: DownloadEntity) { completedEntities += download; completed += download.id }
        override fun onFailed(download: DownloadEntity) { failed += download }
    }

    // ---- fixture ----

    private lateinit var server: MockWebServer
    private lateinit var http: DownloadEngineTest.TestDispatcher
    private lateinit var dao: FakeDao
    private lateinit var settings: FakeSettings
    private lateinit var network: FakeNetwork
    private lateinit var files: FakeFiles
    private lateinit var notifier: RecordingNotifier
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManager
    private lateinit var tempDir: File
    private val remuxer = FakeRemuxer()
    @Volatile private var freeBytes = Long.MAX_VALUE

    @Before fun setUp() {
        http = DownloadEngineTest.TestDispatcher(ByteArray(3 * 1024 * 1024).also { Random(5).nextBytes(it) })
        server = MockWebServer().apply { dispatcher = http; start() }
        dao = FakeDao()
        // MP4 conversion has its own tests (DownloadManagerRemuxTest); here HLS output stays a plain .ts
        settings = FakeSettings(AppSettings(connections = 2, maxParallel = 2, convertHlsToMp4 = false))
        network = FakeNetwork()
        files = FakeFiles(tmp.newFolder("public"))
        notifier = RecordingNotifier()
        tempDir = tmp.newFolder("temp")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = DownloadManager(
            scope, dao, settings,
            DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40),
            files, tempDir, network, notifier,
            remuxer = remuxer, freeSpace = { freeBytes },
        )
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun url(name: String = "file.bin") = server.url("/$name").toString()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toList()
    private fun status(id: Long) = dao.find(id)?.status
    private fun count(s: DownloadStatus) = dao.rows.value.count { it.status == s }

    private suspend fun waitUntil(what: String, timeoutMs: Long = 20_000, pollMs: Long = 20, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(pollMs) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what; rows=${dao.rows.value.map { it.id to it.status }}")
        }
    }

    // ---- tests ----

    @Test fun downloadsAndSavesFile() = runBlocking {
        val id = manager.add(url("hello.bin"), "hello.bin", 2, startNow = false)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

        val d = dao.find(id)!!
        assertEquals(http.payload.size.toLong(), d.totalBytes)
        assertEquals(sha(http.payload), sha(files.saved.single().readBytes()))
        assertEquals("hello.bin", d.fileName)
        assertNotNull(d.savedLocation)
        assertEquals(listOf(id), notifier.completed.toList())
        assertFalse("temp file removed", manager.tempFile(id).exists())
        assertTrue("segments cleaned up", dao.getSegments(id).isEmpty())
    }

    @Test fun respectsMaxParallelAndRunsQueueInOrder() = runBlocking {
        http.throttleBytes = 48 * 1024
        val ids = (1..4).map { manager.add(url("f$it.bin"), "f$it.bin", 2, startNow = false) }

        waitUntil("two running") { count(DownloadStatus.RUNNING) == 2 }
        assertEquals(setOf(ids[0], ids[1]), dao.rows.value.filter { it.status == DownloadStatus.RUNNING }.map { it.id }.toSet())
        assertEquals(2, count(DownloadStatus.QUEUED))
        // never more than the limit while the rest drain
        var maxSeen = 0
        http.throttleBytes = 0
        waitUntil("all done") { maxSeen = maxOf(maxSeen, count(DownloadStatus.RUNNING)); count(DownloadStatus.COMPLETED) == 4 }
        assertTrue("max running was $maxSeen", maxSeen <= 2)
        // the row flips to COMPLETED just before the notifier is called, so wait for it
        waitUntil("4 completion notices") { notifier.completed.size == 4 }
    }

    @Test fun startNowBypassesTheLimit() = runBlocking {
        settings.state.value = settings.state.value.copy(maxParallel = 1)
        http.throttleBytes = 32 * 1024
        val a = manager.add(url("a.bin"), "a.bin", 2, startNow = false)
        val b = manager.add(url("b.bin"), "b.bin", 2, startNow = false)
        val c = manager.add(url("c.bin"), "c.bin", 2, startNow = true)
        waitUntil("a and c running") { status(a) == DownloadStatus.RUNNING && status(c) == DownloadStatus.RUNNING }
        assertEquals(DownloadStatus.QUEUED, status(b))
    }

    @Test fun pauseKeepsProgressAndResumeFinishesWithSameContent() = runBlocking {
        http.throttleBytes = 32 * 1024
        val id = manager.add(url(), "p.bin", 2, startNow = true)
        waitUntil("some progress") { (dao.find(id)?.downloadedBytes ?: 0) > 100_000 }

        manager.pause(id)
        waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
        val pausedAt = dao.find(id)!!.downloadedBytes
        assertTrue(pausedAt in 1 until http.payload.size)
        assertTrue("segments kept for resume", dao.getSegments(id).isNotEmpty())
        assertEquals("no job left running", 0, count(DownloadStatus.RUNNING))

        http.throttleBytes = 0
        http.bytesServed.set(0)
        manager.resume(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(sha(http.payload), sha(files.saved.single().readBytes()))
        assertTrue("resume fetched less than the whole file", http.bytesServed.get() < http.payload.size)
    }

    @Test fun browserHeadersArePersistedAndSentOnProbeAndAllSegmentsAcrossPauseResume() = runBlocking {
        val headers = mapOf("Cookie" to "sid=abc; x=1", "Referer" to "https://site.test/page", "User-Agent" to "WebView-UA/1")
        http.throttleBytes = 32 * 1024
        val id = manager.add(url("secret.bin"), "secret.bin", 2, startNow = true, headers = headers)
        assertEquals(headers, HeaderCodec.decode(dao.find(id)!!.headers))

        waitUntil("some progress") { (dao.find(id)?.downloadedBytes ?: 0) > 100_000 }
        manager.pause(id)
        waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
        assertEquals("headers stay on the row while paused", headers, HeaderCodec.decode(dao.find(id)!!.headers))

        http.throttleBytes = 0
        manager.resume(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

        val sent = http.requests.toList()
        assertTrue(sent.size >= 4)
        for (r in sent) {
            assertEquals(headers["Cookie"], r.getHeader("Cookie"))
            assertEquals(headers["Referer"], r.getHeader("Referer"))
            assertEquals(headers["User-Agent"], r.getHeader("User-Agent"))
        }
    }

    @Test fun downloadsAddedWithoutHeadersKeepWorkingAsBefore() = runBlocking {
        val id = manager.add(url(), "plain.bin", 2, startNow = true)
        assertEquals(null, dao.find(id)!!.headers)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertTrue(http.requests.none { it.getHeader("Cookie") != null || it.getHeader("Referer") != null })
    }

    @Test fun pauseThenImmediateResumeDoesNotEndUpPaused() = runBlocking {
        http.throttleBytes = 32 * 1024
        val id = manager.add(url(), "q.bin", 2, startNow = true)
        waitUntil("running") { (dao.find(id)?.downloadedBytes ?: 0) > 50_000 }
        manager.pause(id)
        manager.resume(id)
        http.throttleBytes = 0
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
    }

    @Test fun cancelRemovesPartialDataAndRetryRestarts() = runBlocking {
        http.throttleBytes = 32 * 1024
        val id = manager.add(url(), "c.bin", 2, startNow = true)
        waitUntil("progress") { (dao.find(id)?.downloadedBytes ?: 0) > 50_000 }
        manager.cancel(id)
        waitUntil("cancelled") { status(id) == DownloadStatus.CANCELLED }
        assertEquals(0, dao.find(id)!!.downloadedBytes)
        assertFalse(manager.tempFile(id).exists())

        http.throttleBytes = 0
        manager.retry(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(sha(http.payload), sha(files.saved.single().readBytes()))
    }

    // CI once lost a retry that came in while the cancelled job was still unwinding. Hit that window many times.
    @Test fun retryRightAfterCancelIsNeverLost() = runBlocking {
        http.throttleBytes = 32 * 1024
        val id = manager.add(url(), "r.bin", 2, startNow = true)
        repeat(25) { round ->
            waitUntil("running #$round") { status(id) == DownloadStatus.RUNNING }
            manager.cancel(id)
            waitUntil("cancelled #$round", pollMs = 1) { status(id) == DownloadStatus.CANCELLED }
            manager.retry(id)
            waitUntil("requeued #$round", timeoutMs = 5_000) { status(id) != DownloadStatus.CANCELLED }
        }
        http.throttleBytes = 0
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(sha(http.payload), sha(files.saved.single().readBytes()))
    }

    @Test fun clientErrorMarksFailedAndRetryWorksAfterServerRecovers() = runBlocking {
        http.errorCode = 404
        val id = manager.add(url(), "x.bin", 2, startNow = true)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertTrue(dao.find(id)!!.error!!.contains("404"))
        assertEquals(1, notifier.failed.size)

        http.errorCode = 0
        manager.retry(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
    }

    @Test fun wifiOnlyWaitsOnMeteredNetworkThenRuns() = runBlocking {
        settings.state.value = settings.state.value.copy(wifiOnly = true)
        network.metered.value = true
        waitUntil("blocked flag") { manager.waitingForWifi.value }

        val id = manager.add(url(), "w.bin", 2, startNow = true)
        delay(400)
        assertEquals("must not start on metered network", DownloadStatus.QUEUED, status(id))

        network.metered.value = false
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertFalse(manager.waitingForWifi.value)
    }

    @Test fun runningDownloadIsParkedWhenNetworkBecomesMetered() = runBlocking {
        settings.state.value = settings.state.value.copy(wifiOnly = true)
        http.throttleBytes = 32 * 1024
        val id = manager.add(url(), "m.bin", 2, startNow = true)
        waitUntil("progress") { (dao.find(id)?.downloadedBytes ?: 0) > 50_000 }

        network.metered.value = true
        waitUntil("parked in queue") { status(id) == DownloadStatus.QUEUED && manager.live.value[id] == null }

        http.throttleBytes = 0
        network.metered.value = false
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(sha(http.payload), sha(files.saved.single().readBytes()))
    }

    @Test fun restoreRequeuesInterruptedDownloadsAndResumesThem() = runBlocking {
        // A row left RUNNING by a killed process
        val id = dao.insert(
            DownloadEntity(url = url(), fileName = "r.bin", connections = 2, status = DownloadStatus.RUNNING),
        )
        manager.restore()
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(sha(http.payload), sha(files.saved.single().readBytes()))
    }

    @Test fun deleteRemovesRowTempFileAndOptionallyTheSavedFile() = runBlocking {
        val keep = manager.add(url("keep.bin"), "keep.bin", 2, startNow = true)
        waitUntil("keep done") { status(keep) == DownloadStatus.COMPLETED }
        val savedFile = File(dao.find(keep)!!.savedLocation!!)
        manager.delete(keep, deleteFile = false)
        waitUntil("row gone") { dao.find(keep) == null }
        assertTrue("file kept", savedFile.exists())

        val gone = manager.add(url("gone.bin"), "gone.bin", 2, startNow = true)
        waitUntil("gone done") { status(gone) == DownloadStatus.COMPLETED }
        val savedFile2 = File(dao.find(gone)!!.savedLocation!!)
        manager.delete(gone, deleteFile = true)
        waitUntil("row gone 2") { dao.find(gone) == null }
        waitUntil("file deleted") { !savedFile2.exists() } // the row goes first, the file right after
    }

    @Test fun deleteWhileRunningStopsTheTransfer() = runBlocking {
        http.throttleBytes = 16 * 1024
        val id = manager.add(url(), "d.bin", 2, startNow = true)
        waitUntil("progress") { (dao.find(id)?.downloadedBytes ?: 0) > 20_000 }
        manager.delete(id, deleteFile = true)
        waitUntil("row gone") { dao.find(id) == null }
        waitUntil("temp gone") { !manager.tempFile(id).exists() }
        assertTrue(notifier.completed.isEmpty())
    }

    // ---- bulk delete ----

    private suspend fun completedRow(name: String, size: Int, exists: Boolean = true): Pair<Long, File> {
        val f = File(tmp.root, "public/$name")
        if (exists) f.writeBytes(ByteArray(size))
        val id = dao.insert(
            DownloadEntity(
                url = "http://x/$name", fileName = name, totalBytes = size.toLong(), status = DownloadStatus.COMPLETED,
                savedLocation = f.absolutePath, savedPath = "Download/XDM/$name",
            ),
        )
        return id to f
    }

    private suspend fun failedRow(name: String): Long =
        dao.insert(DownloadEntity(url = "http://x/$name", fileName = name, status = DownloadStatus.FAILED, error = "boom"))

    @Test fun deleteManyScopedByFilterRemovesOnlyThoseItems() = runBlocking {
        val (c1, f1) = completedRow("a.mp4", 1000)
        val (c2, f2) = completedRow("b.mp4", 2000)
        val failed = failedRow("bad.bin")

        val inTab = com.olaph.xdm.ui.DeleteScope.inFilter(dao.rows.value, com.olaph.xdm.ui.Filter.COMPLETED)
        assertEquals(setOf(c1, c2), inTab.map { it.id }.toSet())
        val summary = com.olaph.xdm.ui.DeleteScope.summarize(inTab)
        assertEquals(2, summary.count)
        assertEquals(3000L, summary.bytesOnDisk)

        val report = manager.deleteMany(inTab.map { it.id }, deleteFiles = true)
        assertEquals(2, report.removed)
        assertEquals(3000L, report.freedBytes)
        assertEquals(0, report.failed)
        assertNull(dao.find(c1)); assertNull(dao.find(c2))
        assertFalse(f1.exists()); assertFalse(f2.exists())
        assertNotNull("the failed item is outside the Completed tab", dao.find(failed))
    }

    @Test fun deleteManyWithoutFilesKeepsTheFiles() = runBlocking {
        val (id, f) = completedRow("keep.mp4", 500)
        val report = manager.deleteMany(listOf(id), deleteFiles = false)
        assertEquals(1, report.removed)
        assertEquals(0L, report.freedBytes)
        assertNull(dao.find(id))
        assertTrue(f.exists())
    }

    @Test fun deleteManyToleratesMissingFiles() = runBlocking {
        val (gone, _) = completedRow("gone.mp4", 700, exists = false)
        val (here, f) = completedRow("here.mp4", 300)
        val report = manager.deleteMany(listOf(gone, here), deleteFiles = true)
        assertEquals(2, report.removed)
        assertEquals("a missing file frees nothing", 300L, report.freedBytes)
        assertEquals(0, report.failed)
        assertNull(dao.find(gone)); assertNull(dao.find(here))
        assertFalse(f.exists())
    }

    @Test fun deleteManyCancelsActiveDownloadsAndRemovesTheirTempData() = runBlocking {
        http.throttleBytes = 16 * 1024
        settings.state.value = settings.state.value.copy(maxParallel = 1)
        val running = manager.add(url("run.bin"), "run.bin", 2, startNow = false)
        val queued = manager.add(url("queue.bin"), "queue.bin", 2, startNow = false)
        waitUntil("progress") { (dao.find(running)?.downloadedBytes ?: 0) > 20_000 }
        assertEquals(DownloadStatus.QUEUED, status(queued))
        // leftovers of an HLS/conversion run that must go with it
        manager.hlsDir(queued).apply { mkdirs(); File(this, "seg0.ts").writeBytes(ByteArray(10)) }
        manager.convertedFile(queued).writeBytes(ByteArray(5))
        manager.tempFile(queued).writeBytes(ByteArray(5))

        val report = manager.deleteMany(listOf(running, queued), deleteFiles = true)

        assertEquals(2, report.removed)
        assertTrue(dao.rows.value.isEmpty())
        assertFalse(manager.tempFile(running).exists())
        assertFalse(manager.tempFile(queued).exists())
        assertFalse(manager.convertedFile(queued).exists())
        assertFalse(manager.hlsDir(queued).exists())
        assertTrue("nothing finished or was saved", notifier.completed.isEmpty() && files.saved.isEmpty())
        assertNull(manager.deleteProgress.value)
    }

    @Test fun deleteManyReturnsDeniedItemsForTheSystemRequestInsteadOfDroppingThem() = runBlocking {
        val (ok, okFile) = completedRow("ok.mp4", 100)
        val (denied, deniedFile) = completedRow("old-install.mp4", 400)
        val (stuck, stuckFile) = completedRow("stuck.mp4", 50)
        files.denied += deniedFile.absolutePath
        files.failing += stuckFile.absolutePath

        val report = manager.deleteMany(listOf(ok, denied, stuck), deleteFiles = true)

        assertEquals(1, report.removed)
        assertEquals(100L, report.freedBytes)
        assertEquals(1, report.failed)
        assertEquals(listOf(DeleteTarget(denied, deniedFile.absolutePath, 400)), report.needsPermission)
        assertNull(dao.find(ok)); assertFalse(okFile.exists())
        assertNotNull("denied record is kept until the system deleted the file", dao.find(denied))
        assertNotNull("failed record is kept", dao.find(stuck))

        // the user confirmed the system dialog
        val second = manager.removeRecords(report.needsPermission)
        assertEquals(1, second.removed)
        assertEquals(400L, second.freedBytes)
        assertNull(dao.find(denied))
    }

    @Test fun singleDeleteUsesTheSamePath() = runBlocking {
        val (id, f) = completedRow("one.mp4", 10)
        manager.delete(id, deleteFile = true)
        waitUntil("row gone") { dao.find(id) == null }
        waitUntil("file gone") { !f.exists() }
        assertEquals(listOf(f.absolutePath), files.deleted.toList())
    }

    @Test fun deleteMessageAndSummary() {
        assertEquals("Deleted 1 download", com.olaph.xdm.ui.DeleteScope.message(1, 0, 0))
        assertEquals("Deleted 12 downloads (1.0 MB freed)", com.olaph.xdm.ui.DeleteScope.message(12, 1024 * 1024, 0))
        assertEquals(
            "Deleted 3 downloads (2.0 KB freed) · 2 files could not be deleted",
            com.olaph.xdm.ui.DeleteScope.message(3, 2048, 2),
        )
        val items = listOf(
            DownloadEntity(id = 1, url = "u", fileName = "a", status = DownloadStatus.RUNNING),
            DownloadEntity(id = 2, url = "u", fileName = "b", status = DownloadStatus.PAUSED),
            DownloadEntity(id = 3, url = "u", fileName = "c", status = DownloadStatus.COMPLETED, totalBytes = 42),
        )
        assertEquals(com.olaph.xdm.ui.DeleteSummary(3, 2, 1, 42), com.olaph.xdm.ui.DeleteScope.summarize(items))
        assertEquals(
            setOf(1L, 2L),
            com.olaph.xdm.ui.DeleteScope.inFilter(items, com.olaph.xdm.ui.Filter.ACTIVE).map { it.id }.toSet(),
        )
    }

    @Test fun pauseAllAndResumeAll() = runBlocking {
        http.throttleBytes = 32 * 1024
        settings.state.value = settings.state.value.copy(maxParallel = 1)
        val ids = (1..3).map { manager.add(url("n$it.bin"), "n$it.bin", 2, startNow = false) }
        waitUntil("first running") { status(ids[0]) == DownloadStatus.RUNNING }

        manager.pauseAll()
        waitUntil("all paused") { ids.all { status(it) == DownloadStatus.PAUSED } }

        http.throttleBytes = 0
        manager.resumeAll()
        waitUntil("all completed") { ids.all { status(it) == DownloadStatus.COMPLETED } }
    }

    // ---- HLS through the manager ----

    private class HlsServer(count: Int) {
        val segments = (0 until count).map { i -> ByteArray(40_000 + i).also { Random(900L + i).nextBytes(it) } }
        @Volatile var segmentDelayMs = 0L
        /** Segments with index >= stallFrom are served very slowly (deterministic pause point). */
        @Volatile var stallFrom = Int.MAX_VALUE
        val hits = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
        val server = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    val path = request.path!!.substringBefore('?')
                    hits.computeIfAbsent(path) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
                    if (request.getHeader("Cookie") != "sid=1") return okhttp3.mockwebserver.MockResponse().setResponseCode(403)
                    if (path == "/v.m3u8") {
                        val pl = buildString {
                            append("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
                            segments.indices.forEach { append("#EXTINF:4,\ns$it.ts\n") }
                            append("#EXT-X-ENDLIST\n")
                        }
                        return okhttp3.mockwebserver.MockResponse().setBody(pl)
                    }
                    val i = Regex("/s(\\d+)\\.ts").matchEntire(path)?.groupValues?.get(1)?.toInt()
                        ?: return okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                    return okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(segments[i]))
                        .setBodyDelay(if (i >= stallFrom) 4_000L else segmentDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            start()
        }
    }

    @Test fun hlsDownloadRunsThroughTheQueueAndIsSavedAsTs() = runBlocking {
        val hls = HlsServer(12)
        try {
            val id = manager.add(
                hls.server.url("/v.m3u8").toString(), "My video.mp4", 3, startNow = true,
                headers = mapOf("Cookie" to "sid=1"), kind = com.olaph.xdm.data.DownloadKind.HLS,
            )
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

            val d = dao.find(id)!!
            assertEquals("the playlist turned out to be MPEG-TS, so the name follows", "My video.ts", d.fileName)
            assertTrue(d.isHls)
            assertEquals(12, d.segmentsTotal)
            val expected = hls.segments.fold(ByteArray(0)) { a, b -> a + b }
            assertEquals(sha(expected), sha(files.saved.single().readBytes()))
            assertEquals(expected.size.toLong(), d.totalBytes)
            waitUntil("segments dir removed") { !manager.hlsDir(id).exists() && !manager.tempFile(id).exists() }
            assertEquals(listOf(id), notifier.completed.toList())
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun hlsPauseKeepsFinishedSegmentsAndResumeFinishes() = runBlocking {
        val hls = HlsServer(30).also { it.stallFrom = 8 }
        try {
            settings.state.value = settings.state.value.copy(connections = 2)
            val id = manager.add(
                hls.server.url("/v.m3u8").toString(), "p.ts", 2, startNow = true,
                headers = mapOf("Cookie" to "sid=1"), kind = com.olaph.xdm.data.DownloadKind.HLS,
            )
            waitUntil("some segments") { (dao.find(id)?.segmentsDone ?: 0) >= 5 }
            manager.pause(id)
            waitUntil("paused") { status(id) == DownloadStatus.PAUSED }

            val onDisk = manager.hlsDir(id).listFiles { f -> f.name.endsWith(".seg") }!!.size
            assertTrue("segments kept while paused: $onDisk", onDisk in 5 until 30)
            assertTrue((dao.find(id)?.segmentsDone ?: 0) in 1 until 30)

            hls.stallFrom = Int.MAX_VALUE
            manager.resume(id)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            val expected = hls.segments.fold(ByteArray(0)) { a, b -> a + b }
            assertEquals(sha(expected), sha(files.saved.single().readBytes()))
        } finally {
            runCatching { hls.server.shutdown() } // stalled bodies may still be draining
        }
    }

    @Test fun hlsLiveStreamFailsWithAClearMessage() = runBlocking {
        val live = MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\na.ts\n")
            }
            start()
        }
        try {
            val id = manager.add(live.url("/live.m3u8").toString(), "live.ts", 2, startNow = true, kind = com.olaph.xdm.data.DownloadKind.HLS)
            waitUntil("failed") { status(id) == DownloadStatus.FAILED }
            assertTrue(dao.find(id)!!.error!!.contains("Live stream"))
            assertEquals(1, notifier.failed.size)
        } finally {
            live.shutdown()
        }
    }

    // ---- HLS .ts -> .mp4 conversion (fake remuxer; the real one is tested in TsMp4RemuxerTest) ----

    private fun enableConversion() { settings.state.value = settings.state.value.copy(convertHlsToMp4 = true) }

    private suspend fun addHls(hls: HlsServer, name: String = "My video.mp4") = manager.add(
        hls.server.url("/v.m3u8").toString(), name, 3, startNow = true,
        headers = mapOf("Cookie" to "sid=1"), kind = com.olaph.xdm.data.DownloadKind.HLS,
    )

    private fun joined(hls: HlsServer) = hls.segments.fold(ByteArray(0)) { a, b -> a + b }

    @Test fun hlsTsIsConvertedAndSavedAsMp4() = runBlocking {
        enableConversion()
        val hls = HlsServer(6)
        try {
            val id = addHls(hls)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

            val d = dao.find(id)!!
            assertEquals("the extension follows the converted file", "My video.mp4", d.fileName)
            assertNull("no note on success", d.error)
            val saved = files.saved.single()
            assertEquals("MP4:", saved.readBytes().copyOf(4).decodeToString())
            assertEquals(listOf("video/mp4"), files.mimes.toList())
            assertEquals(1, remuxer.calls.get())
            assertEquals("the remuxer got the concatenated .ts", sha(joined(hls)), sha(remuxer.inputs.single()))
            assertEquals(saved.length(), d.totalBytes)
            waitUntil("temp files removed") { !manager.tempFile(id).exists() && !manager.convertedFile(id).exists() && !manager.hlsDir(id).exists() }
            assertEquals(listOf(id), notifier.completed.toList())
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun audioOnlyConversionIsNamedM4a() = runBlocking {
        enableConversion()
        remuxer.extension = "m4a"
        val hls = HlsServer(3)
        try {
            val id = addHls(hls, "Podcast.mp4")
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertEquals("Podcast.m4a", dao.find(id)!!.fileName)
            assertEquals(listOf("audio/mp4"), files.mimes.toList())
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun existingNameGetsTheUsualCounterAfterConversion() = runBlocking {
        enableConversion()
        val hls = HlsServer(3)
        try {
            File(tmp.root, "public").also { it.mkdirs() }.resolve("My video.mp4").writeText("already there")
            val id = addHls(hls)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertEquals("My video (1).mp4", dao.find(id)!!.fileName)
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun failedConversionKeepsTheTsAndSaysWhy() = runBlocking {
        enableConversion()
        remuxer.mode = FakeRemuxer.Mode.FAIL
        val hls = HlsServer(6)
        try {
            val id = addHls(hls)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

            val d = dao.find(id)!!
            assertEquals("My video.ts", d.fileName)
            assertEquals("Saved as .ts (conversion failed: unsupported audio codec (AC-3))", d.error)
            assertEquals(sha(joined(hls)), sha(files.saved.single().readBytes()))
            assertEquals(listOf("video/mp2t"), files.mimes.toList())
            assertEquals("the failure is not a failure of the download", emptyList<DownloadEntity>(), notifier.failed.toList())
            waitUntil("temp files removed") { !manager.tempFile(id).exists() && !manager.convertedFile(id).exists() }
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun anUnexpectedErrorInTheConverterIsAlsoNonFatal() = runBlocking {
        enableConversion()
        remuxer.mode = FakeRemuxer.Mode.CRASH
        val hls = HlsServer(3)
        try {
            val id = addHls(hls, "a.mp4")
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertEquals("a.ts", dao.find(id)!!.fileName)
            assertEquals("Saved as .ts (conversion failed: muxer exploded)", dao.find(id)!!.error)
            assertFalse("the half-written MP4 is removed", manager.convertedFile(id).exists())
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun notEnoughFreeSpaceSkipsConversionWithANote() = runBlocking {
        enableConversion()
        freeBytes = 1_000
        val hls = HlsServer(6)
        try {
            val id = addHls(hls)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

            val d = dao.find(id)!!
            assertEquals("My video.ts", d.fileName)
            assertTrue(d.error!!, d.error!!.startsWith("Saved as .ts (not enough free space to convert"))
            assertEquals("the converter is never started", 0, remuxer.calls.get())
            assertEquals(sha(joined(hls)), sha(files.saved.single().readBytes()))
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun conversionCanBeSwitchedOff() = runBlocking {
        val hls = HlsServer(3)
        try {
            val id = addHls(hls) // fixture default: convertHlsToMp4 = false
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertEquals("My video.ts", dao.find(id)!!.fileName)
            assertNull(dao.find(id)!!.error)
            assertEquals(0, remuxer.calls.get())
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun progressIsPublishedWhileConvertingAndCancelDiscardsEverything() = runBlocking {
        enableConversion()
        remuxer.mode = FakeRemuxer.Mode.BLOCK
        val hls = HlsServer(6)
        try {
            val id = addHls(hls)
            withTimeout(20_000) { remuxer.started.await() }
            val live = manager.live.value[id]!!
            assertTrue(live.converting)
            assertEquals(50, live.convertPercent)
            assertEquals(DownloadStatus.RUNNING, status(id))
            assertTrue("the joined .ts is there to convert from", manager.tempFile(id).length() > 0)

            manager.cancel(id)
            waitUntil("cancelled") { status(id) == DownloadStatus.CANCELLED }
            waitUntil("everything removed") {
                !manager.tempFile(id).exists() && !manager.convertedFile(id).exists() && !manager.hlsDir(id).exists()
            }
            assertTrue("nothing was saved", files.saved.isEmpty())
            assertNull("the live entry is gone", manager.live.value[id])
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun pausingDuringConversionKeepsTheTsAndResumeConvertsAgainWithoutRefetching() = runBlocking {
        enableConversion()
        remuxer.mode = FakeRemuxer.Mode.BLOCK
        val hls = HlsServer(6)
        try {
            val id = addHls(hls)
            withTimeout(20_000) { remuxer.started.await() }
            val hitsBefore = hls.hits.mapValues { it.value.get() }

            manager.pause(id)
            waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
            assertEquals("the joined .ts survives the pause", sha(joined(hls)), sha(manager.tempFile(id).readBytes()))
            assertFalse("the partial MP4 is dropped", manager.convertedFile(id).exists())
            assertTrue(files.saved.isEmpty())

            remuxer.mode = FakeRemuxer.Mode.SUCCESS
            manager.resume(id)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

            assertEquals("My video.mp4", dao.find(id)!!.fileName)
            assertEquals(2, remuxer.calls.get())
            assertEquals("converted from the same .ts", sha(joined(hls)), sha(remuxer.inputs.last()))
            assertEquals("no playlist or segment was requested again", hitsBefore, hls.hits.mapValues { it.value.get() })
            assertEquals("MP4:", files.saved.single().readBytes().copyOf(4).decodeToString())
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun aKilledProcessDuringConversionRestartsFromTheTs() = runBlocking {
        enableConversion()
        remuxer.mode = FakeRemuxer.Mode.BLOCK
        val hls = HlsServer(4)
        try {
            val id = addHls(hls)
            withTimeout(20_000) { remuxer.started.await() }
            // Simulate the process dying: the row stays RUNNING, files stay on disk, a new manager takes over.
            scope.cancel()
            waitUntil("old job unwound") { manager.live.value[id] == null }
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            remuxer.mode = FakeRemuxer.Mode.SUCCESS
            val m2 = DownloadManager(
                scope2, dao, settings, DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40),
                files, tempDir, network, notifier, remuxer = remuxer,
            )
            val hitsBefore = hls.hits.mapValues { it.value.get() }
            m2.restore()
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertEquals("My video.mp4", dao.find(id)!!.fileName)
            assertEquals(hitsBefore, hls.hits.mapValues { it.value.get() })
            scope2.cancel()
        } finally {
            hls.server.shutdown()
        }
    }

    @Test fun afterARestartTheHeldQueueOnlyRunsOnceReleased() = runBlocking {
        // What the database looks like after the phone went down mid-download.
        val running = dao.insert(DownloadEntity(url = url("r.bin"), fileName = "r.bin", connections = 2, status = DownloadStatus.RUNNING))
        val queued = dao.insert(DownloadEntity(url = url("q.bin"), fileName = "q.bin", connections = 2, status = DownloadStatus.QUEUED))
        val paused = dao.insert(DownloadEntity(url = url("p.bin"), fileName = "p.bin", connections = 2, status = DownloadStatus.PAUSED))
        scope.cancel() // the fixture's manager is not part of this
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val held = DownloadManager(
                scope2, dao, settings, DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40),
                files, tempDir, network, notifier, holdQueue = true,
            )
            held.restore()
            waitUntil("requeued") { status(running) == DownloadStatus.QUEUED }
            delay(200)
            assertEquals("nothing started while held", 0, http.requests.size)
            assertFalse(held.queueReleased.value)

            held.releaseQueue()
            waitUntil("both finished") { status(running) == DownloadStatus.COMPLETED && status(queued) == DownloadStatus.COMPLETED }
            assertEquals("paused by the user stays paused", DownloadStatus.PAUSED, status(paused))
        } finally {
            scope2.cancel()
        }
    }

    // ---- dynamic segment splitting ----

    private val mib = 1024L * 1024

    private fun splittingManager(scope: CoroutineScope) = DownloadManager(
        scope, dao, settings,
        DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40, minSplitBytes = 256 * 1024),
        files, tempDir, network, notifier, remuxer = remuxer,
    )

    /**
     * Starts a 2 MiB download on two connections whose second half stalls. The first half finishes, its connection
     * splits the second half, and the new range stalls as well; returns once that split is in the database.
     */
    private suspend fun startStalledSplit(
        m: DownloadManager,
        split: DownloadEngineSplitTest.StallingDispatcher,
    ): Long {
        split.holds[mib] = java.util.concurrent.CountDownLatch(1)
        split.holds[mib + mib / 2] = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.CountDownLatch(1).also { split.holds[0] = it }
        split.onRange = { n, _ -> if (n == 2) first.countDown() }
        server.dispatcher = split
        val id = m.add(url("split.bin"), "split.bin", 2, startNow = true)
        waitUntil("split persisted") { dao.segs.value[id].orEmpty().size == 3 }
        return id
    }

    private fun assertSplitRows(id: Long) {
        val rows = dao.segs.value[id].orEmpty().sortedBy { it.idx }
        assertEquals(
            "the first half is done, the stalled half shrank, the new range starts where it ends",
            listOf(SegmentEntity(id, 0, 0, mib - 1, mib), SegmentEntity(id, 1, mib, mib + mib / 2 - 1, 0), SegmentEntity(id, 2, mib + mib / 2, 2 * mib - 1, 0)),
            rows,
        )
    }

    private fun releaseStalls(split: DownloadEngineSplitTest.StallingDispatcher) {
        split.releaseAll()
        split.holds.clear()
        split.onRange = { _, _ -> }
        split.ranges.clear()
    }

    @Test fun pauseAfterASplitKeepsTheSplitAndResumeFinishesTheFile() = runBlocking {
        val split = DownloadEngineSplitTest.StallingDispatcher(ByteArray(2 * mib.toInt()).also { Random(9).nextBytes(it) })
        val m = splittingManager(scope)
        try {
            val id = startStalledSplit(m, split)
            m.pause(id)
            waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
            assertSplitRows(id)

            releaseStalls(split)
            m.resume(id)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertEquals(sha(split.payload), sha(files.saved.single().readBytes()))
            val ranges = split.ranges.toList()
            assertTrue("both open ranges were asked for: $ranges", ranges.containsAll(listOf("1048576-1572863", "1572864-2097151")))
            assertTrue("the finished half was not fetched again: $ranges", ranges.all { it.substringBefore('-').toLong() >= mib })
        } finally {
            split.releaseAll()
        }
    }

    @Test fun aKilledProcessAfterASplitResumesFromTheSavedRanges() = runBlocking {
        val split = DownloadEngineSplitTest.StallingDispatcher(ByteArray(2 * mib.toInt()).also { Random(10).nextBytes(it) })
        val m = splittingManager(scope)
        try {
            val id = startStalledSplit(m, split)
            // The process dies: the row stays RUNNING and a new manager takes over from what is in the database.
            scope.cancel()
            waitUntil("old job unwound") { m.live.value[id] == null }
            assertEquals(DownloadStatus.RUNNING, status(id))
            assertSplitRows(id)

            releaseStalls(split)
            val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                splittingManager(scope2).restore()
                waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            } finally {
                scope2.cancel()
            }
            assertEquals(sha(split.payload), sha(files.saved.single().readBytes()))
            assertTrue("the finished half was not fetched again", split.ranges.none { it.startsWith("0-") })
        } finally {
            split.releaseAll()
        }
    }
}
