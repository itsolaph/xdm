package com.olaph.xdm.core

import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.SettingsSource
import com.olaph.xdm.engine.FatalDownloadException
import com.olaph.xdm.engine.ProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AutoRefreshTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    // ---- fakes ----

    private class Settings(@Volatile var on: Boolean = true) : SettingsSource {
        override val flow: Flow<AppSettings> get() = flowOf(AppSettings(autoRefreshLinks = on))
        override suspend fun current() = AppSettings(autoRefreshLinks = on)
    }

    private class Port : RefreshPort {
        val items = ConcurrentHashMap<Long, DownloadEntity>()
        val applied = CopyOnWriteArrayList<Long>()
        @Volatile var accepts = true
        override suspend fun get(id: Long) = items[id]
        override suspend fun apply(id: Long, found: RefreshOutcome.Found): Boolean {
            applied += id
            return accepts
        }
    }

    private class Notes : RefreshNotifier {
        val events = LinkedBlockingQueue<String>()
        override fun refreshed(download: DownloadEntity) { events.add("refreshed:${download.id}") }
        override fun couldNotRefresh(download: DownloadEntity) { events.add("couldNot:${download.id}") }
        override fun failed(download: DownloadEntity) { events.add("failed:${download.id}") }
        fun next(): String? = events.poll(5, TimeUnit.SECONDS)
        fun nothingMore(): Boolean = events.poll(300, TimeUnit.MILLISECONDS) == null
    }

    private val settings = Settings()
    private val port = Port()
    private val notes = Notes()
    @Volatile private var now = 1_000_000L

    private fun probe(size: Long) = ProbeResult("https://cdn.test/new.bin", size, true, "\"e\"", null, "f.bin", "video/mp4")

    private fun found(size: Long = 1000) = RefreshOutcome.Found("https://cdn.test/new.bin", mapOf("Cookie" to "a=b"), "https://site.test/p", probe(size))

    private fun download(
        id: Long = 1,
        total: Long = 1000,
        error: String? = "Server error: HTTP 403",
        status: DownloadStatus = DownloadStatus.FAILED,
        kind: String = DownloadKind.HTTP,
        isPrivate: Boolean = false,
        page: String? = "https://site.test/p",
    ) = DownloadEntity(
        id = id, url = "https://cdn.test/old.bin", fileName = "f$id.bin", status = status, error = error, totalBytes = total,
        kind = kind, isPrivate = isPrivate, sourcePage = page,
    ).also { port.items[id] = it }

    private fun autoRefresh(timeoutMs: Long = 5_000, finder: LinkFinder) = AutoRefresh(
        scope, settings, port, finder, notes, clock = { now }, timeoutMs = timeoutMs,
    )

    // ---- a match ----

    @Test fun anExactMatchIsAppliedAndAnnounced() = runBlocking {
        val seen = CopyOnWriteArrayList<Pair<String, RefreshTarget>>()
        val auto = autoRefresh { page, target -> seen += page to target; found() }
        val d = download()
        assertTrue(auto.onFailed(d))
        assertEquals("refreshed:1", notes.next())
        assertEquals(listOf(1L), port.applied)
        assertEquals("https://site.test/p", seen.single().first)
        assertEquals(1000L, seen.single().second.totalBytes)
        assertFalse("the target is spent afterwards", seen.single().second.isOpen)
    }

    @Test fun aMatchTheDownloadCannotTakeIsAFailure() = runBlocking {
        port.accepts = false // e.g. the ETag changed: only the user may decide
        val auto = autoRefresh { _, _ -> found() }
        auto.onFailed(download())
        assertEquals("couldNot:1", notes.next())
    }

    // ---- no match ----

    @Test fun aDifferentSizeIsNeverApplied() = runBlocking {
        val auto = autoRefresh { _, _ -> found(size = 999) }
        auto.onFailed(download(total = 1000))
        assertEquals("couldNot:1", notes.next())
        assertTrue(port.applied.isEmpty())
    }

    @Test fun nothingFoundLeavesTheItemFailedAndSaysSo() = runBlocking {
        val auto = autoRefresh { _, _ -> RefreshOutcome.NotFound }
        auto.onFailed(download())
        assertEquals("couldNot:1", notes.next())
        assertTrue(port.applied.isEmpty())
        assertEquals(DownloadStatus.FAILED, port.items[1]!!.status)
    }

    @Test fun aSlowPageTimesOutAndIsStopped() = runBlocking {
        val cancelled = AtomicBoolean(false)
        var target: RefreshTarget? = null
        val auto = autoRefresh(timeoutMs = 150) { _, t ->
            target = t
            try {
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
        auto.onFailed(download())
        assertEquals("couldNot:1", notes.next())
        assertTrue("the finder was cancelled so it can clean up", cancelled.get())
        assertFalse("a match arriving late is refused", target!!.claim(1000))
    }

    // ---- quiet cases ----

    @Test fun withoutAWebViewTheOrdinaryFailureNoticeApplies() = runBlocking {
        val auto = autoRefresh { _, _ -> RefreshOutcome.Unavailable }
        auto.onFailed(download())
        assertEquals("failed:1", notes.next())
        assertTrue(notes.nothingMore())
    }

    @Test fun aFinderThatBreaksFallsBackToTheOrdinaryNotice() = runBlocking {
        val auto = autoRefresh { _, _ -> throw IllegalStateException("webview crashed") }
        auto.onFailed(download())
        assertEquals("failed:1", notes.next())
    }

    @Test fun theSettingTurnsItOff() = runBlocking {
        settings.on = false
        val calls = AtomicInteger()
        val auto = autoRefresh { _, _ -> calls.incrementAndGet(); found() }
        assertFalse(auto.onFailed(download()))
        assertTrue(notes.nothingMore())
        assertEquals(0, calls.get())
        assertFalse(auto.active.value)
    }

    @Test fun theSettingTurnedOffWhileWaitingIsHonoured() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val auto = autoRefresh { _, _ -> calls.incrementAndGet(); started.complete(Unit); gate.await(); RefreshOutcome.NotFound }
        download(1); download(2)
        auto.onFailed(port.items[1]!!)
        started.await() // the first one is under way, the second waits its turn
        auto.onFailed(port.items[2]!!)
        settings.on = false
        gate.complete(Unit)
        assertEquals("couldNot:1", notes.next())
        assertEquals("failed:2", notes.next())
        assertEquals(1, calls.get())
    }

    @Test fun onlyPlainFilesWithAnExpiredLinkAPageAndASizeQualify() = runBlocking {
        val calls = AtomicInteger()
        val auto = autoRefresh { _, _ -> calls.incrementAndGet(); found() }
        assertFalse("private", auto.onFailed(download(isPrivate = true)))
        assertFalse("HLS", auto.onFailed(download(kind = DownloadKind.HLS)))
        assertFalse("YouTube", auto.onFailed(download(kind = DownloadKind.YOUTUBE)))
        assertFalse("not expired", auto.onFailed(download(error = "Server error: HTTP 404")))
        assertFalse("another error", auto.onFailed(download(error = "Not enough free space")))
        assertFalse("no page", auto.onFailed(download(page = null)))
        assertFalse("size unknown: nothing to recognise the file by", auto.onFailed(download(total = -1)))
        assertFalse("not failed", auto.onFailed(download(status = DownloadStatus.PAUSED)))
        assertTrue(notes.nothingMore())
        assertEquals(0, calls.get())
        assertTrue("410 counts as expired", auto.onFailed(download(error = "Server error: HTTP 410")))
        assertEquals("refreshed:1", notes.next())
    }

    @Test fun aDownloadRetriedByHandInTheMeantimeIsLeftAlone() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val auto = autoRefresh { _, _ -> gate.await(); found() }
        download(1); download(2)
        auto.onFailed(port.items[1]!!)
        auto.onFailed(port.items[2]!!)
        port.items[2] = port.items[2]!!.copy(status = DownloadStatus.QUEUED, error = null) // the user tapped Retry
        gate.complete(Unit)
        assertEquals("refreshed:1", notes.next())
        assertTrue(notes.nothingMore())
        assertEquals(listOf(1L), port.applied)
    }

    // ---- one at a time, and not too often ----

    @Test fun refreshesRunOneAtATimeInOrder() = runBlocking {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val order = CopyOnWriteArrayList<String>()
        val auto = autoRefresh { page, _ ->
            val now = running.incrementAndGet()
            most.updateAndGet { maxOf(it, now) }
            order += page
            delay(80)
            running.decrementAndGet()
            found()
        }
        for (id in 1L..3L) {
            download(id, page = "https://site.test/p$id")
            assertTrue(auto.onFailed(port.items[id]!!))
        }
        assertTrue(auto.active.value)
        assertEquals(listOf("refreshed:1", "refreshed:2", "refreshed:3"), List(3) { notes.next() })
        assertEquals(1, most.get())
        assertEquals(listOf("https://site.test/p1", "https://site.test/p2", "https://site.test/p3"), order)
        withIdle(auto)
    }

    @Test fun aFailureAlreadyBeingHandledIsNotQueuedTwice() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val auto = autoRefresh { _, _ -> calls.incrementAndGet(); gate.await(); found() }
        val d = download()
        assertTrue(auto.onFailed(d))
        assertTrue("taken over, but not started again", auto.onFailed(d))
        gate.complete(Unit)
        assertEquals("refreshed:1", notes.next())
        assertTrue(notes.nothingMore())
        assertEquals(1, calls.get())
    }

    @Test fun aDownloadGetsTwoAutomaticAttemptsPerHalfHour() = runBlocking {
        val auto = autoRefresh { _, _ -> RefreshOutcome.NotFound }
        val d = download()
        assertTrue(auto.onFailed(d))
        assertEquals("couldNot:1", notes.next())
        withIdle(auto)
        now += 60_000
        assertTrue(auto.onFailed(d))
        assertEquals("couldNot:1", notes.next())
        withIdle(auto)
        now += 60_000
        assertFalse("the third within 30 minutes is left to the user", auto.onFailed(d))
        assertTrue(notes.nothingMore())

        assertTrue("another download has its own allowance", auto.onFailed(download(id = 2)))
        assertEquals("couldNot:2", notes.next())
        withIdle(auto)

        now += 29 * 60_000 // 31 minutes after the first attempt
        assertTrue(auto.onFailed(d))
        assertEquals("couldNot:1", notes.next())
    }

    @Test fun theLimitCountsAttemptsNotSuccessesOnly() = runBlocking {
        val auto = autoRefresh { _, _ -> found() }
        val d = download()
        assertTrue(auto.onFailed(d))
        assertEquals("refreshed:1", notes.next())
        withIdle(auto)
        assertTrue(auto.onFailed(d))
        assertEquals("refreshed:1", notes.next())
        withIdle(auto)
        assertFalse(auto.onFailed(d))
    }

    // ---- the keep-alive flag ----

    @Test fun itIsActiveWhileAnythingIsQueuedOrRunning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val auto = autoRefresh { _, _ -> gate.await(); found() }
        assertFalse(auto.active.value)
        auto.onFailed(download())
        assertTrue(auto.active.value)
        gate.complete(Unit)
        assertEquals("refreshed:1", notes.next())
        withIdle(auto)
    }

    private suspend fun withIdle(auto: AutoRefresh) {
        val deadline = System.currentTimeMillis() + 5_000
        while (auto.active.value && System.currentTimeMillis() < deadline) delay(10)
        assertFalse(auto.active.value)
    }

    // ---- the matcher both refresh flows share ----

    private fun target(total: Long = 1000) = RefreshTarget(1, "f.bin", total)

    @Test fun theMatcherTakesTheFirstFileOfExactlyTheRightSize() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val matcher = RefreshMatcher(target()) { url, _ -> asked += url; probe(if (url.endsWith("/video")) 1000 else 4242) }
        assertNull(matcher.check("https://cdn.test/ad", emptyMap()))
        assertNull(matcher.check("https://cdn.test/thumb", emptyMap()))
        assertNotNull(matcher.check("https://cdn.test/video", emptyMap()))
        assertNull("one winner only", matcher.check("https://cdn.test/video2", emptyMap()).also { assertEquals(3, asked.size) })
    }

    @Test fun theMatcherAsksAboutAUrlOnce() = runBlocking {
        val calls = AtomicInteger()
        val matcher = RefreshMatcher(target()) { _, _ -> calls.incrementAndGet(); probe(5) }
        repeat(3) { assertNull(matcher.check("https://cdn.test/a#x", emptyMap())) }
        assertNull(matcher.check("https://CDN.test/a", emptyMap()))
        assertEquals(1, calls.get())
    }

    @Test fun aRefusedUrlIsNotAskedAgainButAPassingErrorIs() = runBlocking {
        val calls = AtomicInteger()
        val matcher = RefreshMatcher(target()) { url, _ ->
            calls.incrementAndGet()
            if (url.endsWith("/refused")) throw FatalDownloadException("no", 403)
            if (calls.get() <= 2) throw IOException("timeout")
            probe(1000)
        }
        assertNull(matcher.check("https://cdn.test/refused", emptyMap()))
        assertNull(matcher.check("https://cdn.test/refused", emptyMap()))
        assertEquals(1, calls.get())
        assertNull(matcher.check("https://cdn.test/flaky", emptyMap())) // timeout
        assertNotNull("tried again after the timeout", matcher.check("https://cdn.test/flaky", emptyMap()).also { assertEquals(3, calls.get()) })
    }

    @Test fun theMatcherNeedsAKnownSizeAndAnOpenTarget() = runBlocking {
        val calls = AtomicInteger()
        val unknown = RefreshMatcher(target(total = -1)) { _, _ -> calls.incrementAndGet(); probe(1000) }
        assertNull(unknown.check("https://cdn.test/a", emptyMap()))
        val closed = target().also { it.close() }
        val shut = RefreshMatcher(closed) { _, _ -> calls.incrementAndGet(); probe(1000) }
        assertNull(shut.check("https://cdn.test/a", emptyMap()))
        assertEquals("not even asked", 0, calls.get())
    }

    @Test fun twoCandidatesOfTheRightSizeCannotBothWin() = runBlocking {
        val matcher = RefreshMatcher(target()) { _, _ -> probe(1000) }
        val results = listOf("a", "b", "c").map { matcher.check("https://cdn.test/$it", emptyMap()) }
        assertEquals(1, results.count { it != null })
    }
}
