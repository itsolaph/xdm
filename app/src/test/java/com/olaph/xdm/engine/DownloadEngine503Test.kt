package com.olaph.xdm.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DownloadEngine503Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun repeatedOverlap503FallsBackWithoutLosingSparseProgress() = runBlocking { overlapRecovery(false) }

    @Test fun pauseDuringFallbackNetworkWaitPersistsSparseProgressAndResumes() = runBlocking { overlapRecovery(true) }

    @Test fun networkChangeDuringReductionStillValidatesFileIdentity() = runBlocking {
        overlapRecovery(false, changeOnReduction = true)
    }

    private suspend fun overlapRecovery(pause: Boolean, changeOnReduction: Boolean = false) = coroutineScope {
        val data = ByteArray(256 * 1024).also { Random(503).nextBytes(it) }
        val fallbackWaiting = CompletableDeferred<Unit>()
        val refuseSerial = AtomicBoolean(pause)
        val firstStarted = CountDownLatch(1)
        val firstCancelled = AtomicBoolean()
        val refused = AtomicInteger()
        val first = AtomicBoolean(true)
        val active = AtomicInteger()
        val serialPeak = AtomicInteger()
        val changed = AtomicBoolean()
        var onCancelled: () -> Unit = {}
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).eventListener(object : EventListener() {
            override fun callStart(call: Call) {
                val count = active.incrementAndGet()
                if (firstCancelled.get()) serialPeak.accumulateAndGet(count, ::maxOf)
            }
            override fun callEnd(call: Call) { active.decrementAndGet() }
            override fun callFailed(call: Call, ioe: IOException) {
                active.decrementAndGet()
                if (call.request().header("Range") == "bytes=4096-65535" && firstCancelled.compareAndSet(false, true)) {
                    onCancelled()
                }
            }
        }).build()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "HEAD") return MockResponse()
                    .setHeader("Content-Length", data.size).setHeader("Accept-Ranges", "bytes").setHeader("ETag", if (changed.get()) "changed" else "fixture")
                val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range")!!)!!
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                if (start == 4096 && first.compareAndSet(true, false)) {
                    firstStarted.countDown()
                    return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                check(firstStarted.await(5, TimeUnit.SECONDS))
                if (!firstCancelled.get()) {
                    refused.incrementAndGet()
                    return MockResponse().setResponseCode(503)
                }
                if (refuseSerial.get()) return MockResponse().setResponseCode(503)
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data, start, end - start + 1))
            }
        }
        server.start()
        try {
            val segments = (0..3).map { i -> SegmentState(i, i * 65536L, (i + 1) * 65536L - 1, if (i % 2 == 0) 4096 else 0) }
            val file = File(tmp.root, "sparse.part")
            java.io.RandomAccessFile(file, "rw").use { out ->
                out.setLength(data.size.toLong())
                segments.forEach { seg -> out.seek(seg.start); out.write(data, seg.start.toInt(), seg.downloaded.toInt()) }
            }
            val store = DownloadEngineSplitTest.MemoryStore()
            val engine = DownloadEngine(client, maxRetries = 2, backoffMs = { 1 }, minSplitBytes = 1024,
                awaitNetwork = {
                    if (firstCancelled.get() && refuseSerial.get()) {
                        fallbackWaiting.complete(Unit)
                        awaitCancellation()
                    }
                })
            if (changeOnReduction) onCancelled = {
                changed.set(true)
                engine.interruptConnections()
            }
            fun request(saved: SavedState) = EngineRequest(1, server.url("/fixture").toString(), "Test", file, 2, saved)
            val saved = SavedState(data.size.toLong(), true, "fixture", null, segments)
            if (changeOnReduction) {
                try {
                    engine.download(request(saved), store)
                    fail("network change during reduction must validate the ETag")
                } catch (_: FileChangedException) {
                    assertEquals(segments, store.segments)
                    assertEquals(0, active.get())
                }
                return@coroutineScope
            }
            if (pause) {
                val job = launch { engine.download(request(saved), store) }
                try {
                    withTimeout(5_000) { fallbackWaiting.await() }
                } finally {
                    withTimeout(5_000) { job.cancelAndJoin() }
                }
                assertEquals("all sparse progress persisted on pause", segments, store.segments)
                assertEquals("pause left no calls open", 0, active.get())
                refuseSerial.set(false)
                // A new run may choose its own connection count; this resume is deliberately serial.
                engine.download(EngineRequest(1, server.url("/fixture").toString(), "Test", file, 1, store.saved()), store)
            } else {
                engine.download(request(saved), store)
            }
            assertTrue(firstCancelled.get())
            assertEquals(2, refused.get())
            assertEquals("one connection throughout fallback", 1, serialPeak.get())
            assertEquals("no calls left open", 0, active.get())
            assertArrayEquals(data, file.readBytes())
            assertEquals(segments.map { it.copy(downloaded = it.length) }, store.segments)
        } finally {
            server.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun permanent503StillExhaustsABoundedBudget() = runBlocking {
        val gets = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    gets.incrementAndGet()
                    return MockResponse().setResponseCode(503)
                }
            }
            start()
        }
        try {
            val store = DownloadEngineSplitTest.MemoryStore()
            try {
                DownloadEngine(OkHttpClient(), maxRetries = 2, backoffMs = { 1 }, minSplitBytes = 0).download(
                    EngineRequest(1, server.url("/fixture").toString(), "Test", File(tmp.root, "permanent.part"),
                        2, knownLength = 4L * 1024 * 1024), store,
                )
                fail("permanent 503 must fail")
            } catch (e: HttpStatusException) {
                assertEquals(503, e.httpCode)
            }
            // At most two refusals per parallel worker, then one serial attempt plus two retries.
            assertTrue("bounded requests: ${gets.get()}", gets.get() in 5..7)
            assertEquals(0L, store.segments.sumOf { it.downloaded })
            assertTrue(SegmentPlanner.coversExactly(store.segments, 4L * 1024 * 1024))
        } finally {
            server.shutdown()
        }
    }

    @Test fun oneTransient503RetriesWithoutCancellingItsSibling() = runBlocking {
        val data = ByteArray(256 * 1024).also { Random(504).nextBytes(it) }
        val firstStarted = CountDownLatch(1)
        val retrySeen = CountDownLatch(1)
        val secondGets = AtomicInteger()
        val client = OkHttpClient()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val range = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.getHeader("Range")!!)!!
                    val start = range.groupValues[1].toInt()
                    val end = range.groupValues[2].toInt()
                    if (start == 0) {
                        firstStarted.countDown()
                        check(retrySeen.await(5, TimeUnit.SECONDS))
                    } else {
                        check(firstStarted.await(5, TimeUnit.SECONDS))
                        if (secondGets.incrementAndGet() == 1) return MockResponse().setResponseCode(503)
                        retrySeen.countDown()
                    }
                    return MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                        .setBody(Buffer().write(data, start, end - start + 1))
                }
            }
            start()
        }
        try {
            val file = File(tmp.root, "transient.part")
            DownloadEngine(client, maxRetries = 2, backoffMs = { 1 }, minSplitBytes = 0).download(
                EngineRequest(1, server.url("/fixture").toString(), "Test", file, 2,
                    maxSegmentBytes = data.size / 2L, knownLength = data.size.toLong()),
                DownloadEngineSplitTest.MemoryStore(),
            )
            assertEquals(2, secondGets.get())
            // executeAndUse cancels completed calls too; a refetch of the held first range would betray a reduction.
            assertEquals(3, server.requestCount)
            assertArrayEquals(data, file.readBytes())
        } finally {
            retrySeen.countDown()
            server.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun aSingleRangeWithIdleWorkersKeepsItsOriginalRetryBudget() = runBlocking {
        val gets = AtomicInteger()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    gets.incrementAndGet()
                    return MockResponse().setResponseCode(503)
                }
            }
            start()
        }
        try {
            try {
                DownloadEngine(OkHttpClient(), maxRetries = 2, backoffMs = { 1 }).download(
                    EngineRequest(1, server.url("/fixture").toString(), "Test", File(tmp.root, "single.part"),
                        8, knownLength = 256L * 1024), DownloadEngineSplitTest.MemoryStore(),
                )
                fail("permanent 503 must fail")
            } catch (e: HttpStatusException) {
                assertEquals(503, e.httpCode)
            }
            assertEquals("one transfer: no extra serial retry phase", 3, gets.get())
        } finally {
            server.shutdown()
        }
    }

}
