package com.olaph.xdm.core

import com.olaph.xdm.core.DownloadManagerTest.FakeDao
import com.olaph.xdm.core.DownloadManagerTest.FakeFiles
import com.olaph.xdm.core.DownloadManagerTest.FakeSettings
import com.olaph.xdm.core.DownloadManagerTest.RecordingNotifier
import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.PauseNote
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.DownloadEngineTest
import com.olaph.xdm.engine.LinkMatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
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
import java.io.IOException
import java.time.LocalTime
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The manager's reliability behaviour: the schedule, Android's background time limit, network changes, a new link for an
 * expired one, and what happens when the save step or the free space fails. The clock and the charger are fakes that the
 * tests move by hand; nothing here waits for real time to pass.
 */
class DownloadManagerReliabilityTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Net : NetworkState {
        override val metered = MutableStateFlow(false)
        override val online = MutableStateFlow(true)
        val switchFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        override val switches: Flow<Unit> = switchFlow
    }

    private class Power : PowerState {
        override val charging = MutableStateFlow(true)
    }

    /** A saver that can be told to fail, and that adds a note like the real one does when the chosen folder is gone. */
    private class FlakyFiles(private val inner: FakeFiles) : FileStore {
        @Volatile var failures = 0
        @Volatile var note: String? = null
        val calls = AtomicInteger()
        override fun save(temp: File, desiredName: String, mimeType: String?): Saved {
            calls.incrementAndGet()
            if (failures > 0) { failures--; throw IOException("storage is full") }
            return inner.save(temp, desiredName, mimeType).copy(note = note)
        }
        override fun delete(location: String) = inner.delete(location)
        override fun open(location: String) = inner.open(location)
    }

    private lateinit var server: MockWebServer
    private lateinit var http: DownloadEngineTest.TestDispatcher
    private lateinit var dao: FakeDao
    private lateinit var settings: FakeSettings
    private val net = Net()
    private val power = Power()
    private lateinit var files: FakeFiles
    private lateinit var flaky: FlakyFiles
    private lateinit var notifier: RecordingNotifier
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManager
    private lateinit var tempDir: File
    @Volatile private var now: LocalTime = LocalTime.of(12, 0)
    @Volatile private var freeBytes = Long.MAX_VALUE
    @Volatile private var targetFree: Long? = null

    @Before fun setUp() {
        http = DownloadEngineTest.TestDispatcher(ByteArray(3 * 1024 * 1024).also { Random(21).nextBytes(it) })
        server = MockWebServer().apply { dispatcher = http; start() }
        dao = FakeDao()
        settings = FakeSettings(AppSettings(connections = 2, maxParallel = 2, convertHlsToMp4 = false))
        files = FakeFiles(tmp.newFolder("public"))
        flaky = FlakyFiles(files)
        notifier = RecordingNotifier()
        tempDir = tmp.newFolder("temp")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = newManager(DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40))
    }

    private fun newManager(engine: DownloadEngine) = DownloadManager(
        scope, dao, settings, engine, flaky, tempDir, net, notifier,
        freeSpace = { freeBytes },
        power = power,
        timeOfDay = { now },
        targetFreeSpace = { targetFree },
    )

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun url(name: String = "file.bin") = server.url("/$name").toString()
    private fun status(id: Long) = dao.find(id)?.status
    private fun schedule(start: Int, end: Int, charging: Boolean = false) {
        settings.state.value = settings.state.value.copy(
            scheduleEnabled = true, scheduleStartMinute = start * 60, scheduleEndMinute = end * 60, scheduleOnlyCharging = charging,
        )
    }

    private suspend fun waitUntil(what: String, timeoutMs: Long = 20_000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what; rows=${dao.rows.value.map { it.id to it.status }}")
        }
    }

    // ---- schedule ----

    @Test fun aQueuedDownloadWaitsOutsideTheWindowAndRunsWhenItOpens() = runBlocking {
        schedule(23, 7)
        val id = manager.add(url(), "s.bin", 2, startNow = false)
        assertEquals("held by the schedule", DownloadStatus.QUEUED, status(id))
        assertEquals(Hold.SCHEDULE, manager.hold.value)

        now = LocalTime.of(23, 30)
        manager.reevaluate()
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertArrayEquals(http.payload, files.saved.single().readBytes())
        assertNull(manager.hold.value)
    }

    @Test fun aRunningDownloadGoesBackToTheQueueWhenTheWindowCloses() = runBlocking {
        now = LocalTime.of(23, 30)
        schedule(23, 7)
        http.throttleBytes = 32 * 1024
        val id = manager.add(url(), "w.bin", 2, startNow = false)
        waitUntil("progress") { (dao.find(id)?.downloadedBytes ?: 0) > 100_000 }

        now = LocalTime.of(7, 0)
        manager.reevaluate()
        waitUntil("parked") { status(id) == DownloadStatus.QUEUED && manager.live.value[id] == null }
        assertTrue("progress is kept", dao.getSegments(id).isNotEmpty())
        assertEquals(Hold.SCHEDULE, manager.hold.value)

        http.throttleBytes = 0
        now = LocalTime.of(23, 5)
        manager.reevaluate()
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertArrayEquals(http.payload, files.saved.single().readBytes())
    }

    @Test fun startNowIgnoresTheScheduleBeforeAndDuringTheRun() = runBlocking {
        schedule(23, 7)
        http.throttleBytes = 32 * 1024
        val held = manager.add(url("held.bin"), "held.bin", 2, startNow = false)
        val forced = manager.add(url("forced.bin"), "forced.bin", 2, startNow = true)
        waitUntil("progress") { (dao.find(forced)?.downloadedBytes ?: 0) > 100_000 }
        assertEquals(DownloadStatus.QUEUED, status(held))

        manager.reevaluate() // still outside the window; the forced run is not parked
        assertEquals(DownloadStatus.RUNNING, status(forced))
        http.throttleBytes = 0
        waitUntil("forced completed") { status(forced) == DownloadStatus.COMPLETED }
        assertEquals("the other one still waits", DownloadStatus.QUEUED, status(held))
    }

    @Test fun onlyWhileChargingWaitsForTheCharger() = runBlocking {
        schedule(0, 0, charging = true)
        power.charging.value = false
        val id = manager.add(url(), "c.bin", 2, startNow = false)
        assertEquals(DownloadStatus.QUEUED, status(id))
        assertEquals(Hold.CHARGING, manager.hold.value)

        power.charging.value = true
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
    }

    @Test fun anOpeningWindowWithNothingRunningHoldsTheQueueUntilAServiceReleasesIt() = runBlocking {
        schedule(23, 7)
        val woke = AtomicInteger()
        manager.onScheduleReady = { woke.incrementAndGet() }
        val id = manager.add(url(), "wake.bin", 2, startNow = false)

        now = LocalTime.of(23, 0)
        manager.reevaluate()
        waitUntil("wake-up requested") { woke.get() == 1 }
        assertFalse("the queue is held", manager.queueReleased.value)
        assertEquals("nothing starts without a service", DownloadStatus.QUEUED, status(id))

        manager.releaseQueue() // what the service's start does
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(1, woke.get())
    }

    @Test fun withoutAWakeHookAnOpeningWindowJustStarts() = runBlocking {
        schedule(23, 7)
        val id = manager.add(url(), "plain.bin", 2, startNow = false)
        now = LocalTime.of(2, 0)
        manager.reevaluate()
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
    }

    @Test fun turningTheScheduleOffReleasesWhatWaited() = runBlocking {
        schedule(23, 7)
        val id = manager.add(url(), "off.bin", 2, startNow = false)
        assertEquals(DownloadStatus.QUEUED, status(id))
        settings.state.value = settings.state.value.copy(scheduleEnabled = false)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
    }

    @Test fun wifiOnlyStillBeatsStartNowDuringTheSchedule() = runBlocking {
        schedule(23, 7)
        settings.state.value = settings.state.value.copy(wifiOnly = true)
        net.metered.value = true
        val id = manager.add(url(), "wifi.bin", 2, startNow = true)
        waitUntil("flag") { manager.waitingForWifi.value }
        assertEquals(DownloadStatus.QUEUED, status(id))
        assertEquals(Hold.WIFI, manager.hold.value)
        net.metered.value = false
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
    }

    // ---- Android's background time limit ----

    @Test fun theSystemTimeLimitPausesEverythingCleanlyAndOnlyThatComesBack() = runBlocking {
        settings.state.value = settings.state.value.copy(maxParallel = 2)
        http.throttleBytes = 32 * 1024
        val a = manager.add(url("a.bin"), "a.bin", 2, startNow = false)
        val b = manager.add(url("b.bin"), "b.bin", 2, startNow = false)
        val queued = manager.add(url("q.bin"), "q.bin", 2, startNow = false)
        val user = manager.add(url("u.bin"), "u.bin", 2, startNow = false)
        waitUntil("a and b running") { status(a) == DownloadStatus.RUNNING && status(b) == DownloadStatus.RUNNING }
        waitUntil("progress") { (dao.find(a)?.downloadedBytes ?: 0) > 100_000 }
        manager.pause(user)
        waitUntil("user pause") { status(user) == DownloadStatus.PAUSED }

        val affected = manager.pauseForSystem()
        assertEquals("two running and one waiting", 3, affected)
        for (id in listOf(a, b, queued)) {
            assertEquals(DownloadStatus.PAUSED, status(id))
            assertEquals(PauseNote.SYSTEM_LIMIT, dao.find(id)!!.error)
        }
        assertNull("a user pause carries no note", dao.find(user)!!.error)
        assertTrue("segments are saved", dao.getSegments(a).isNotEmpty())
        assertEquals(0, manager.live.value.size)

        http.throttleBytes = 0
        manager.resumeSystemPaused()
        waitUntil("the three finish") { listOf(a, b, queued).all { status(it) == DownloadStatus.COMPLETED } }
        assertEquals("the user's pause stays", DownloadStatus.PAUSED, status(user))
        assertArrayEquals(http.payload, File(dao.find(a)!!.savedLocation!!).readBytes())
    }

    @Test fun pausingForTheSystemWithNothingToDoReportsZero() = runBlocking {
        assertEquals(0, manager.pauseForSystem())
    }

    @Test fun aSystemPausedDownloadCanAlsoBeResumedByHand() = runBlocking {
        http.throttleBytes = 32 * 1024
        val id = manager.add(url(), "h.bin", 2, startNow = true)
        waitUntil("progress") { (dao.find(id)?.downloadedBytes ?: 0) > 50_000 }
        manager.pauseForSystem()
        http.throttleBytes = 0
        manager.resume(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertNull(dao.find(id)!!.error)
    }

    // ---- network changes ----

    /** Answers the first range request with nothing (a dead connection) and tells the test when it arrived. */
    private class StallingServer(@Volatile var payload: ByteArray) : Dispatcher() {
        val stalled = CountDownLatch(1)
        val stallNext = AtomicBoolean(true)
        @Volatile var etag = "\"v1\""

        override fun dispatch(request: RecordedRequest): MockResponse {
            val base = MockResponse().setHeader("ETag", etag)
            val data = payload
            if (request.method == "HEAD") {
                return base.setResponseCode(200).setHeader("Content-Length", data.size).setHeader("Accept-Ranges", "bytes")
            }
            if (stallNext.compareAndSet(true, false)) {
                stalled.countDown()
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                ?: return base.setResponseCode(200).setBody(Buffer().write(data))
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(data.size - 1L) ?: (data.size - 1L)
            return base.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${data.size}")
                .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
        }
    }

    private fun stallingManager(payload: ByteArray): Pair<StallingServer, MockWebServer> {
        val dispatcher = StallingServer(payload)
        val s = MockWebServer().apply { this.dispatcher = dispatcher; start() }
        // No retries and a minute of read timeout: only an aborted connection can get this download moving.
        manager = newManager(
            DownloadEngine(
                OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build(),
                maxRetries = 0, backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40,
                awaitNetwork = { net.online.first { it } },
            ),
        )
        return dispatcher to s
    }

    @Test fun aNetworkSwitchUnsticksARunningDownload() = runBlocking {
        val payload = ByteArray(400 * 1024).also { Random(4).nextBytes(it) }
        val (stall, stallServer) = stallingManager(payload)
        try {
            val id = manager.add(stallServer.url("/f.bin").toString(), "f.bin", 1, startNow = true)
            assertTrue(withContext(Dispatchers.IO) { stall.stalled.await(15, TimeUnit.SECONDS) })
            net.switchFlow.emit(Unit)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertArrayEquals(payload, files.saved.single().readBytes())
        } finally {
            stallServer.shutdown()
        }
    }

    @Test fun aFileThatChangedDuringTheSwitchIsDownloadedAgainFromZero() = runBlocking {
        val first = ByteArray(400 * 1024).also { Random(5).nextBytes(it) }
        val second = ByteArray(400 * 1024).also { Random(6).nextBytes(it) }
        val (stall, stallServer) = stallingManager(first)
        try {
            val id = manager.add(stallServer.url("/f.bin").toString(), "f.bin", 1, startNow = true)
            assertTrue(withContext(Dispatchers.IO) { stall.stalled.await(15, TimeUnit.SECONDS) })
            stall.payload = second
            stall.etag = "\"v2\""
            net.switchFlow.emit(Unit)
            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertArrayEquals("no byte of the old file is mixed in", second, files.saved.single().readBytes())
        } finally {
            stallServer.shutdown()
        }
    }

    // ---- a new link ----

    /** Serves the file under any path; a path prefix stops working (403) after a number of range answers. */
    private class LinkServer(val payload: ByteArray) : Dispatcher() {
        val limits = ConcurrentHashMap<String, Int>()
        private val served = ConcurrentHashMap<String, AtomicInteger>()
        val ranges = CopyOnWriteArrayList<Pair<String, Long>>()
        @Volatile var etag: String? = "\"v1\""
        @Volatile var size = payload.size
        @Volatile var throttle = 0L

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!.substringBefore('?')
            val prefix = path.split('/')[1]
            val base = MockResponse().apply { etag?.let { setHeader("ETag", it) } }
            if (request.method == "HEAD") {
                return base.setResponseCode(200).setHeader("Content-Length", size).setHeader("Accept-Ranges", "bytes")
            }
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                ?: return base.setResponseCode(200).setBody(Buffer().write(payload))
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(size - 1L) ?: (size - 1L)
            val n = served.getOrPut(prefix) { AtomicInteger() }.getAndIncrement()
            if (limits[prefix]?.let { n >= it } == true) return MockResponse().setResponseCode(403)
            ranges += path to start
            return base.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/$size")
                .setBody(Buffer().write(payload, start.toInt(), (end - start + 1).toInt()))
                .apply { if (throttle > 0) throttleBody(throttle, 50, TimeUnit.MILLISECONDS) }
        }
    }

    private fun linkServer(payload: ByteArray): Pair<LinkServer, MockWebServer> {
        val d = LinkServer(payload)
        return d to MockWebServer().apply { dispatcher = d; start() }
    }

    /**
     * A download that got part of the way through the "old" link, was paused, and whose link then expired: resuming it
     * fails with 403 and leaves the saved segments in place. Returns the id and the progress that was saved.
     */
    private suspend fun expiredHalfway(link: LinkServer, srv: MockWebServer): Pair<Long, List<com.olaph.xdm.data.SegmentEntity>> {
        link.throttle = 32 * 1024
        val oldUrl = srv.url("/old/f.bin").toString()
        val id = manager.add(
            oldUrl, "f.bin", 2, startNow = true, probe = manager.probe(oldUrl),
            headers = mapOf("Referer" to "https://site.test/page"),
        )
        waitUntil("progress") { (dao.find(id)?.downloadedBytes ?: 0) > 100_000 }
        manager.pause(id)
        waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
        link.throttle = 0
        link.limits["old"] = 0 // the link has expired
        manager.resume(id)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        return id to dao.getSegments(id)
    }

    @Test fun anExpiredLinkIsReplacedAndTheDownloadContinuesWithoutFetchingAgain() = runBlocking {
        val payload = ByteArray(3 * 1024 * 1024 + 11).also { Random(8).nextBytes(it) }
        val (link, srv) = linkServer(payload)
        try {
            val (id, saved) = expiredHalfway(link, srv)
            val d = dao.find(id)!!
            assertTrue(d.error!!.contains("403"))
            assertEquals("the Referer is remembered as the source page", "https://site.test/page", d.sourcePage)
            assertTrue(LinkRefresh.offerRefresh(d))
            assertTrue("part of the file is kept", saved.sumOf { it.downloaded } in 1 until payload.size)

            val newUrl = srv.url("/new/f.bin").toString()
            val check = manager.checkLink(id, newUrl)
            assertEquals(LinkMatch.Verdict.Same, check.verdict)
            assertTrue(manager.replaceLink(id, newUrl, check.probe, sourcePage = "https://site.test/other"))
            assertEquals("https://site.test/other", dao.find(id)!!.sourcePage)

            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertArrayEquals(payload, files.saved.single().readBytes())
            val newStarts = link.ranges.filter { it.first.startsWith("/new/") }.map { it.second }
            assertTrue("the new link fetched something", newStarts.isNotEmpty())
            assertTrue(
                "no byte that was already on disk was requested again",
                newStarts.none { s -> saved.any { seg -> s >= seg.start && s < seg.start + seg.downloaded } },
            )
        } finally {
            srv.shutdown()
        }
    }

    @Test fun aReplacementWithAnotherEtagOffersARestartAndHonoursIt() = runBlocking {
        val payload = ByteArray(3 * 1024 * 1024).also { Random(9).nextBytes(it) }
        val (link, srv) = linkServer(payload)
        try {
            val (id, _) = expiredHalfway(link, srv)
            link.etag = "\"v2\""
            val newUrl = srv.url("/new/f.bin").toString()
            val check = manager.checkLink(id, newUrl)
            assertEquals(LinkMatch.Verdict.RestartOnly(LinkMatch.Reason.ETAG_CHANGED), check.verdict)
            assertTrue(manager.replaceLink(id, newUrl, check.probe, restart = true))

            waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
            assertArrayEquals(payload, files.saved.single().readBytes())
            assertEquals("started over at byte 0", 0L, link.ranges.filter { it.first.startsWith("/new/") }.minOf { it.second })
        } finally {
            srv.shutdown()
        }
    }

    @Test fun aReplacementOfAnotherSizeIsReportedAsADifferentFile() = runBlocking {
        val payload = ByteArray(2 * 1024 * 1024).also { Random(10).nextBytes(it) }
        val (link, srv) = linkServer(payload)
        try {
            val (id, _) = expiredHalfway(link, srv)
            link.size = payload.size - 5
            val check = manager.checkLink(id, srv.url("/new/f.bin").toString())
            assertEquals(LinkMatch.Verdict.Different(payload.size.toLong(), payload.size - 5L), check.verdict)
        } finally {
            srv.shutdown()
        }
    }

    @Test fun changeUrlWorksOnAPausedDownloadAndRefusesAFinishedOne() = runBlocking {
        http.throttleBytes = 32 * 1024
        val id = manager.add(url("a.bin"), "a.bin", 2, startNow = true)
        waitUntil("progress") { (dao.find(id)?.downloadedBytes ?: 0) > 100_000 }
        manager.pause(id)
        waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
        http.throttleBytes = 0
        val check = manager.checkLink(id, url("b.bin"))
        assertEquals(LinkMatch.Verdict.Same, check.verdict)
        assertTrue(manager.replaceLink(id, url("b.bin"), check.probe))
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(url("b.bin"), dao.find(id)!!.url)
        assertFalse("a finished download takes no new link", manager.replaceLink(id, url("c.bin"), check.probe))
    }

    // ---- saving and space ----

    @Test fun aFailedSaveKeepsTheDownloadedFileForARetry() = runBlocking {
        flaky.failures = 1
        val id = manager.add(url(), "keep.bin", 2, startNow = true)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertTrue(dao.find(id)!!.error!!.contains("storage is full"))
        assertTrue("the finished temp file is still there", manager.tempFile(id).isFile)
        assertTrue(files.saved.isEmpty())

        manager.retry(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertArrayEquals(http.payload, files.saved.single().readBytes())
        assertFalse(manager.tempFile(id).exists())
        assertEquals("only the saving was repeated", 2, flaky.calls.get())
    }

    @Test fun aNoteFromTheSaverShowsOnTheFinishedItem() = runBlocking {
        flaky.note = FileSaver.FOLDER_UNAVAILABLE_NOTE
        val id = manager.add(url(), "note.bin", 2, startNow = true)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals("Saved to Download/XDM: chosen folder unavailable", dao.find(id)!!.error)
    }

    @Test fun notEnoughRoomForThePartialFileFailsBeforeAnythingIsFetched() = runBlocking {
        freeBytes = 1_000
        val probe = manager.probe(url())
        val id = manager.add(url(), "big.bin", 2, startNow = true, probe = probe)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertTrue(dao.find(id)!!.error!!.startsWith("Not enough free space"))
        assertTrue("nothing was downloaded", http.dataGets().isEmpty())
    }

    @Test fun aFullCustomFolderVolumeFailsEarlyToo() = runBlocking {
        targetFree = 1_000
        val probe = manager.probe(url())
        val id = manager.add(url(), "big.bin", 2, startNow = true, probe = probe)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertTrue(dao.find(id)!!.error!!.contains("chosen folder"))
        assertTrue(http.dataGets().isEmpty())
    }

    @Test fun anUnknownTargetVolumeIsSkippedQuietly() = runBlocking {
        targetFree = null
        val probe = manager.probe(url())
        val id = manager.add(url(), "ok.bin", 2, startNow = true, probe = probe)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertNotNull(files.saved.singleOrNull())
    }

    @Test fun theSourcePageComesFromTheCaptureOrTheReferer() = runBlocking {
        val a = manager.add(url("1.bin"), "1.bin", 1, startNow = false, sourcePage = "https://tab.test/p", headers = mapOf("Referer" to "https://ref.test/"))
        val b = manager.add(url("2.bin"), "2.bin", 1, startNow = false, headers = mapOf("referer" to "https://ref.test/x"))
        val c = manager.add(url("3.bin"), "3.bin", 1, startNow = false)
        val d = manager.add(url("4.bin"), "4.bin", 1, startNow = false, sourcePage = "not a url")
        assertEquals("https://tab.test/p", dao.find(a)!!.sourcePage)
        assertEquals("https://ref.test/x", dao.find(b)!!.sourcePage)
        assertNull(dao.find(c)!!.sourcePage)
        assertNull(dao.find(d)!!.sourcePage)
    }
}
