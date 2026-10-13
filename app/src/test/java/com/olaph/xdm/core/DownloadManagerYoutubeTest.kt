package com.olaph.xdm.core

import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.remux.DashInput
import com.olaph.xdm.remux.DashMuxer
import com.olaph.xdm.remux.RemuxException
import com.olaph.xdm.remux.RemuxResult
import com.olaph.xdm.youtube.FormatSelector
import com.olaph.xdm.youtube.OptionKind
import com.olaph.xdm.youtube.StreamKind
import com.olaph.xdm.youtube.VideoCodec
import com.olaph.xdm.youtube.YoutubeException
import com.olaph.xdm.youtube.YoutubeResolver
import com.olaph.xdm.youtube.YoutubeQuality
import com.olaph.xdm.youtube.YtErrorKind
import com.olaph.xdm.youtube.YtInfo
import com.olaph.xdm.youtube.YtPlan
import com.olaph.xdm.youtube.YtPlaylist
import com.olaph.xdm.youtube.YtStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import java.util.Properties
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube items through the real [DownloadManager], engine and queue; only YouTube itself (the resolver), the muxer and
 * the file store are fakes. The server is MockWebServer; "expired" URLs and a mid-download stall happen at exact points
 * (after N answers / at a given chunk), not after delays.
 */
class DownloadManagerYoutubeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val chunk = 64L * 1024
    private val video = ByteArray(330 * 1024 + 17).also { Random(1).nextBytes(it) } // 6 chunks
    private val audio = ByteArray(120 * 1024 + 5).also { Random(2).nextBytes(it) } // 2 chunks
    private val muxed360 = ByteArray(200 * 1024 + 9).also { Random(3).nextBytes(it) }
    private val videoId = "dQw4w9WgXcQ"

    /** Where the engine's segments start (equal parts, none above the cap): 6 for the video, 2 for the audio. */
    private val videoStarts = com.olaph.xdm.engine.SegmentPlanner.plan(video.size.toLong(), 1, chunk).map { it.start }
    private val audioSegments = com.olaph.xdm.engine.SegmentPlanner.plan(audio.size.toLong(), 1, chunk)

    // ---- the server ----

    data class Hit(val generation: String, val name: String, val start: Long, val end: Long, val status: Int, val headers: Map<String, String>)

    private inner class Media : Dispatcher() {
        val hits = CopyOnWriteArrayList<Hit>()

        /** Generation → answers before it turns 403 (counted over all files of the generation). */
        val limits = ConcurrentHashMap<String, Int>()
        private val used = ConcurrentHashMap<String, AtomicInteger>()

        /** `(generation, name, chunk start)` → leave the request unanswered; [stalled] completes when one arrives. */
        @Volatile var stallWhen: ((String, String, Long) -> Boolean)? = null
        @Volatile var stalled = CompletableDeferred<Unit>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val parts = request.path!!.substringBefore('?').split('/')
            val generation = parts[1]
            val name = parts[2]
            val data = when (name) { "video" -> video; "audio" -> audio; else -> muxed360 }
            val m = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                ?: return MockResponse().setResponseCode(400)
            val start = m.groupValues[1].toLong()
            val end = m.groupValues[2].toLongOrNull()?.coerceAtMost(data.size - 1L) ?: (data.size - 1L)
            val seen = mapOf("User-Agent" to request.getHeader("User-Agent").orEmpty(), "Referer" to request.getHeader("Referer").orEmpty(), "Origin" to request.getHeader("Origin").orEmpty())
            if (stallWhen?.invoke(generation, name, start) == true) {
                hits += Hit(generation, name, start, end, -1, seen)
                stalled.complete(Unit)
                return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val n = used.getOrPut(generation) { AtomicInteger() }.getAndIncrement()
            val limit = limits[generation]
            if (limit != null && n >= limit) {
                hits += Hit(generation, name, start, end, 403, seen)
                return MockResponse().setResponseCode(403)
            }
            hits += Hit(generation, name, start, end, 206, seen)
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
        }

        fun served(name: String) = hits.filter { it.name == name && it.status == 206 }
    }

    // ---- the fakes ----

    private inner class FakeResolver : YoutubeResolver {
        val calls = AtomicInteger()
        @Volatile var generation: (Int) -> String = { n -> if (n == 1) "g1" else "g2" }
        @Volatile var failure: (Int) -> Throwable? = { null }
        @Volatile var live = false
        @Volatile var dropItags: Set<Int> = emptySet()

        fun info(gen: String): YtInfo {
            fun url(name: String) = server.url("/$gen/$name").toString()
            fun v(itag: Int, h: Int, codec: VideoCodec = VideoCodec.H264) =
                YtStream(itag, StreamKind.VIDEO_ONLY, "mp4", codec, width = h * 16 / 9, height = h, fps = 30, bitrate = h * 1000, contentLength = video.size.toLong(), url = url("video"))
            val streams = listOf(
                v(137, 1080), v(136, 720), v(135, 480),
                YtStream(140, StreamKind.AUDIO_ONLY, "m4a", aac = true, bitrate = 128_000, contentLength = audio.size.toLong(), url = url("audio")),
                YtStream(18, StreamKind.MUXED, "mp4", VideoCodec.H264, aac = true, width = 640, height = 360, fps = 30, bitrate = 500_000, contentLength = muxed360.size.toLong(), url = url("prog")),
            ).filter { it.itag !in dropItags }
            return YtInfo(videoId, "Some Title", "Some Channel", 212, null, streams, live)
        }

        /** When set, a lookup waits for it: holds a download at "Getting video info" for as long as a test wants. */
        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun resolve(videoId: String): YtInfo {
            val n = calls.incrementAndGet()
            gate?.await()
            failure(n)?.let { throw it }
            return info(generation(n))
        }

        override suspend fun playlist(playlistId: String): YtPlaylist = throw UnsupportedOperationException()
    }

    /** Output = "MUX:" + video bytes + audio bytes, so a test can tell what was merged. */
    private class FakeMuxer : DashMuxer {
        enum class Mode { SUCCESS, FAIL }
        @Volatile var mode = Mode.SUCCESS
        val calls = AtomicInteger()
        @Volatile var video: ByteArray? = null
        @Volatile var audio: ByteArray? = null
        @Volatile var containers: Pair<String?, String?> = null to null

        override suspend fun mux(video: DashInput?, audio: DashInput?, output: File, onProgress: (Long, Long) -> Unit): RemuxResult {
            calls.incrementAndGet()
            this.video = video?.file?.readBytes()
            this.audio = audio?.file?.readBytes()
            containers = video?.container to audio?.container
            if (mode == Mode.FAIL) throw RemuxException("boom")
            onProgress(50, 100)
            output.writeBytes("MUX:".toByteArray() + (this.video ?: ByteArray(0)) + (this.audio ?: ByteArray(0)))
            onProgress(100, 100)
            return RemuxResult(if (video != null) "mp4" else "m4a", if (video != null) "video/mp4" else "audio/mp4", 212_000_000, 1, 1, 1, 1, 0)
        }
    }

    // ---- fixture ----

    private lateinit var server: MockWebServer
    private lateinit var media: Media
    private lateinit var dao: DownloadManagerTest.FakeDao
    private lateinit var settings: DownloadManagerTest.FakeSettings
    private lateinit var files: DownloadManagerTest.FakeFiles
    private lateinit var notifier: DownloadManagerTest.RecordingNotifier
    private lateinit var scope: CoroutineScope
    private lateinit var manager: DownloadManager
    private lateinit var tempDir: File
    private lateinit var resolver: FakeResolver
    private val muxer = FakeMuxer()
    @Volatile private var freeBytes = Long.MAX_VALUE

    @Before fun setUp() {
        media = Media()
        server = MockWebServer().apply { dispatcher = media; start() }
        dao = DownloadManagerTest.FakeDao()
        settings = DownloadManagerTest.FakeSettings(AppSettings(connections = 1, maxParallel = 2))
        files = DownloadManagerTest.FakeFiles(tmp.newFolder("public"))
        notifier = DownloadManagerTest.RecordingNotifier()
        tempDir = tmp.newFolder("temp")
        resolver = FakeResolver()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager = DownloadManager(
            scope, dao, settings,
            DownloadEngine(OkHttpClient(), backoffMs = { 1 }, tickMs = 20, persistIntervalMs = 40),
            files, tempDir, DownloadManagerTest.FakeNetwork(), notifier,
            freeSpace = { freeBytes },
            youtube = resolver, dashMuxer = muxer, youtubeRetryDelayMs = 0, youtubeChunkBytes = chunk,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun status(id: Long) = dao.find(id)?.status

    private suspend fun waitUntil(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what; rows=${dao.rows.value.map { it.id to it.status to it.error }}")
        }
    }

    private fun optionAt(height: Int) = FormatSelector.options(resolver.info("g1")).first { it.kind == OptionKind.VIDEO && it.height == height }
    private fun planFor(option: com.olaph.xdm.youtube.YtOption) = YtPlan.forOption(videoId, option)
    private fun muxed() = "MUX:".toByteArray() + video + audio

    private suspend fun addVideo(height: Int = 720, startNow: Boolean = true) =
        manager.addYoutube(planFor(optionAt(height)), "Some Title", startNow, connections = 1)

    // ---- the happy paths ----

    @Test fun videoAndAudioAreDownloadedMergedAndSavedAsOneItem() = runBlocking {
        val id = addVideo(720)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

        assertEquals("one row for the whole thing", 1, dao.rows.value.size)
        val d = dao.find(id)!!
        assertEquals("Some Title (720p).mp4", d.fileName)
        assertEquals("video/mp4", files.mimes.single())
        assertArrayEquals(muxed(), files.saved.single().readBytes())
        assertEquals(1, muxer.calls.get())
        assertArrayEquals("the muxer got the video as downloaded", video, muxer.video)
        assertArrayEquals("and the audio", audio, muxer.audio)
        assertEquals("mp4" to "m4a", muxer.containers)
        assertEquals(listOf(id), notifier.completed.toList())
        assertEquals("looked up once", 1, resolver.calls.get())
        assertFalse("parts and merged file are gone", File(tempDir, "yt-$id").exists())
        assertTrue(dao.getSegments(id).isEmpty())
        assertEquals(DownloadStatus.COMPLETED, d.status)
        assertEquals(muxed().size.toLong(), d.totalBytes)
    }

    @Test fun theRowCarriesThePinnedPlanSoAResumeUsesTheSameStreams() = runBlocking {
        val id = addVideo(480)
        val plan = YtPlan.decode(dao.find(id)!!.extra)!!
        assertEquals(videoId, plan.videoId)
        assertEquals(listOf(135, 140), listOf(plan.videoItag, plan.audioItag))
        assertEquals(com.olaph.xdm.data.DownloadKind.YOUTUBE, dao.find(id)!!.kind)
        assertTrue(dao.find(id)!!.url.startsWith("https://www.youtube.com/watch?v=$videoId"))
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
    }

    @Test fun everyRequestIsAChunkWithinTheCapAndCarriesYoutubeHeaders() = runBlocking {
        val id = addVideo(720)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        val hits = media.hits.toList()
        assertEquals("6 video chunks + 2 audio chunks and nothing else (no probe)", 8, hits.size)
        assertTrue(hits.all { it.end - it.start + 1 <= chunk })
        assertEquals(video.size.toLong(), media.served("video").sumOf { it.end - it.start + 1 })
        assertEquals(audio.size.toLong(), media.served("audio").sumOf { it.end - it.start + 1 })
        assertTrue("video comes before audio", hits.indexOfLast { it.name == "video" } < hits.indexOfFirst { it.name == "audio" })
        assertTrue(hits.all { it.headers["Referer"] == "https://www.youtube.com/" && it.headers["Origin"] == "https://www.youtube.com" })
        assertTrue(hits.all { it.headers["User-Agent"]!!.startsWith("Mozilla/5.0") })
    }

    @Test fun theFastProgressiveStreamIsSavedAsIsWithoutMerging() = runBlocking {
        val option = FormatSelector.options(resolver.info("g1")).first { it.kind == OptionKind.PROGRESSIVE }
        val id = manager.addYoutube(planFor(option), "Some Title", true, 1)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals("Some Title (360p).mp4", dao.find(id)!!.fileName)
        assertArrayEquals(muxed360, files.saved.single().readBytes())
        assertEquals("no merge", 0, muxer.calls.get())
        assertTrue(media.hits.all { it.name == "prog" })
    }

    @Test fun audioOnlyIsRewrittenAsAnM4aWithoutAVideoStream() = runBlocking {
        val option = FormatSelector.options(resolver.info("g1")).first { it.kind == OptionKind.AUDIO }
        val id = manager.addYoutube(planFor(option), "Some Title", true, 1)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals("Some Title.m4a", dao.find(id)!!.fileName)
        assertEquals("audio/mp4", files.mimes.single())
        assertNull(muxer.video)
        assertArrayEquals("MUX:".toByteArray() + audio, files.saved.single().readBytes())
        assertTrue(media.hits.all { it.name == "audio" })
    }

    @Test fun aPlaylistEntryPicksItsStreamsWhenItStarts() = runBlocking {
        val gate = CompletableDeferred<Unit>().also { resolver.gate = it }
        val id = manager.addYoutube(YtPlan.deferred(videoId, YoutubeQuality.P480), "Playlist entry", true, 1)
        waitUntil("lookup started") { resolver.calls.get() == 1 }
        assertFalse("nothing chosen yet", YtPlan.decode(dao.find(id)!!.extra)!!.hasStreams)
        assertEquals("named after the entry until it starts", "Playlist entry (480p).mp4", dao.find(id)!!.fileName)
        gate.complete(Unit)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals("Some Title (480p).mp4", dao.find(id)!!.fileName)
        assertArrayEquals(muxed(), files.saved.single().readBytes())
        assertEquals(135, YtPlan.decode(dao.find(id)!!.extra)!!.videoItag)
    }

    @Test fun twoVideosRunSideBySideInTheNormalQueue() = runBlocking {
        val a = addVideo(720, startNow = false)
        val b = addVideo(480, startNow = false)
        waitUntil("both completed") { status(a) == DownloadStatus.COMPLETED && status(b) == DownloadStatus.COMPLETED }
        assertEquals(setOf("Some Title (720p).mp4", "Some Title (480p).mp4"), files.saved.map { it.name }.toSet())
    }

    // ---- expired URLs ----

    @Test fun anExpiredUrlMidDownloadIsReResolvedAndTheBytesAlreadyOnDiskAreKept() = runBlocking {
        media.limits["g1"] = 4 // four video chunks, then YouTube says 403
        val id = addVideo(720)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

        assertArrayEquals(muxed(), files.saved.single().readBytes())
        assertEquals("the first lookup and one renewal, shared with the audio stream", 2, resolver.calls.get())
        assertEquals(videoStarts.take(4), media.served("video").filter { it.generation == "g1" }.map { it.start }.sorted())
        assertEquals("the new url continues at chunk 4", videoStarts[4], media.served("video").filter { it.generation == "g2" }.minOf { it.start })
        assertEquals("no video byte was fetched twice", video.size.toLong(), media.served("video").sumOf { it.end - it.start + 1 })
        assertEquals("the audio started on the fresh url straight away", setOf("g2"), media.served("audio").map { it.generation }.toSet())
        assertEquals(1, media.hits.count { it.status == 403 })
    }

    @Test fun aServerThatKeepsRefusingEndsInAPlainMessage() = runBlocking {
        media.limits["g1"] = 0
        media.limits["g2"] = 0
        val id = addVideo(720)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertEquals(YtErrorKind.EXPIRED.message, dao.find(id)!!.error)
        assertEquals(1, notifier.failed.size)
    }

    // ---- pause / resume across a new lookup ----

    @Test fun progressIsOneNumberAndPauseThenResumeReResolvesAndKeepsEverythingFetched() = runBlocking {
        // Audio stalls at its second chunk: by then the whole video and one audio chunk are on disk.
        media.stallWhen = { _, name, start -> name == "audio" && start > 0 }
        val id = addVideo(720)
        withTimeout(30_000) { media.stalled.await() }

        val total = (video.size + audio.size).toLong()
        val live = manager.live.value[id]!!
        assertEquals("one total for both streams", total, live.total)
        assertEquals("Downloading audio", live.statusText)
        assertTrue("combined progress covers the finished video: ${live.downloaded}", live.downloaded >= video.size)
        assertTrue(live.downloaded < total)

        manager.pause(id)
        waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
        val d = dao.find(id)!!
        assertEquals("the item row holds the combined bytes", video.size + audioSegments[0].length, d.downloadedBytes)
        val segs = dao.getSegments(id)
        assertTrue("segments of both streams are kept", segs.any { it.idx < 100_000 } && segs.any { it.idx >= 100_000 })
        assertTrue("the video's segments are all complete", segs.filter { it.idx < 100_000 }.all { it.downloaded == it.end - it.start + 1 })
        assertTrue(File(tempDir, "yt-$id").isDirectory)

        // Back later, from another "network": a new lookup hands out new URLs, and only the rest is fetched.
        resolver.generation = { "g9" }
        media.stallWhen = null
        media.hits.clear()
        manager.resume(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }

        assertEquals("looked up again for the resume", 2, resolver.calls.get())
        assertTrue("the video was not fetched again", media.hits.none { it.name == "video" })
        assertEquals("audio continues after its first chunk", audioSegments[1].start, media.served("audio").minOf { it.start })
        assertEquals(setOf("g9"), media.hits.map { it.generation }.toSet())
        assertArrayEquals(muxed(), files.saved.single().readBytes())
    }

    @Test fun deletingAPausedYoutubeItemRemovesItsParts() = runBlocking {
        media.stallWhen = { _, name, start -> name == "video" && start == videoStarts[2] }
        val id = addVideo(720)
        withTimeout(30_000) { media.stalled.await() }
        manager.pause(id)
        waitUntil("paused") { status(id) == DownloadStatus.PAUSED }
        assertTrue(File(tempDir, "yt-$id/video.part").isFile)

        manager.deleteMany(listOf(id), deleteFiles = true)
        assertFalse(File(tempDir, "yt-$id").exists())
        assertNull(dao.find(id))
    }

    // ---- merging ----

    @Test fun aFailedMergeFailsTheItemInWordsAndARetryDoesNotFetchAgain() = runBlocking {
        muxer.mode = FakeMuxer.Mode.FAIL
        val id = addVideo(720)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertEquals("Couldn't merge video and audio: boom", dao.find(id)!!.error)
        assertTrue("the parts stay for the retry", File(tempDir, "yt-$id/video.part").isFile && File(tempDir, "yt-$id/audio.part").isFile)

        muxer.mode = FakeMuxer.Mode.SUCCESS
        media.hits.clear()
        manager.retry(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertTrue("nothing was downloaded a second time", media.hits.isEmpty())
        assertArrayEquals(muxed(), files.saved.single().readBytes())
    }

    @Test fun anAudioOnlyFileIsStillSavedWhenTheRewriteFails() = runBlocking {
        muxer.mode = FakeMuxer.Mode.FAIL
        val option = FormatSelector.options(resolver.info("g1")).first { it.kind == OptionKind.AUDIO }
        val id = manager.addYoutube(planFor(option), "Some Title", true, 1)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertArrayEquals("saved as downloaded", audio, files.saved.single().readBytes())
        assertTrue(dao.find(id)!!.error!!.contains("boom"))
    }

    @Test fun aMergeThatFinishedBeforeARestartIsNotRedone() = runBlocking {
        // State left by a run that merged and was stopped before the file was saved.
        val plan = YtPlan.forOption(videoId, optionAt(720))
        val id = dao.insert(
            DownloadEntity(
                url = "https://www.youtube.com/watch?v=$videoId", fileName = "Some Title (720p).mp4", connections = 1,
                status = DownloadStatus.PAUSED, kind = DownloadKind.YOUTUBE, extra = plan.encode(),
            ),
        )
        val dir = File(tempDir, "yt-$id").apply { mkdirs() }
        val merged = "MUX:finished earlier".toByteArray()
        File(dir, "merged.part").writeBytes(merged)
        Properties().apply { setProperty("bytes", merged.size.toString()); setProperty("name", "Some Title (720p).mp4"); setProperty("mime", "video/mp4") }
            .also { p -> File(dir, "merged.properties").outputStream().use { p.store(it, null) } }
        val callsBefore = resolver.calls.get()
        media.hits.clear()

        manager.resume(id)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertArrayEquals(merged, files.saved.single().readBytes())
        assertTrue("no download", media.hits.isEmpty())
        assertEquals("no lookup either", callsBefore, resolver.calls.get())
        assertEquals(0, muxer.calls.get())
    }

    // ---- errors in plain words ----

    private suspend fun failsWith(kind: YtErrorKind, throwable: Throwable) {
        resolver.failure = { throwable }
        val id = addVideo(720)
        waitUntil("failed with $kind") { status(id) == DownloadStatus.FAILED }
        assertEquals(kind.message, dao.find(id)!!.error)
        assertTrue("nothing was requested from googlevideo", media.hits.isEmpty())
        assertFalse(dao.find(id)!!.error!!.contains("Exception"))
    }

    @Test fun ageRestrictedPrivateMembersOnlyGeoBlockAndBotCheckEachGetTheirOwnMessage() = runBlocking {
        failsWith(YtErrorKind.AGE_RESTRICTED, YoutubeException(YtErrorKind.AGE_RESTRICTED))
        failsWith(YtErrorKind.PRIVATE, YoutubeException(YtErrorKind.PRIVATE))
        failsWith(YtErrorKind.MEMBERS_ONLY, YoutubeException(YtErrorKind.MEMBERS_ONLY))
        failsWith(YtErrorKind.GEO_BLOCKED, YoutubeException(YtErrorKind.GEO_BLOCKED))
        failsWith(YtErrorKind.BOT_CHECK, YoutubeException(YtErrorKind.BOT_CHECK))
        assertEquals(5, notifier.failed.size)
    }

    @Test fun anythingTheLibraryThrowsBecomesTheUpdateMessageNotACrash() = runBlocking {
        failsWith(YtErrorKind.EXTRACTOR_CHANGED, org.schabi.newpipe.extractor.exceptions.ParsingException("Could not get name"))
        failsWith(YtErrorKind.EXTRACTOR_CHANGED, IllegalStateException("kaboom"))
        failsWith(YtErrorKind.EXTRACTOR_CHANGED, NoClassDefFoundError("javax/script/ScriptEngine"))
    }

    @Test fun aLiveBroadcastIsRefused() = runBlocking {
        resolver.live = true
        val id = addVideo(720)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertEquals(YtErrorKind.LIVE.message, dao.find(id)!!.error)
        assertTrue(media.hits.isEmpty())
    }

    @Test fun aNetworkHiccupDuringTheLookupIsRetried() = runBlocking {
        resolver.failure = { n -> if (n <= 2) YoutubeException(YtErrorKind.NETWORK) else null }
        resolver.generation = { "g1" }
        val id = addVideo(720)
        waitUntil("completed") { status(id) == DownloadStatus.COMPLETED }
        assertEquals(3, resolver.calls.get())
    }

    @Test fun aNetworkThatStaysDownEndsWithTheNetworkMessage() = runBlocking {
        resolver.failure = { YoutubeException(YtErrorKind.NETWORK) }
        val id = addVideo(720)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertEquals(YtErrorKind.NETWORK.message, dao.find(id)!!.error)
        assertEquals("one try and two retries", 3, resolver.calls.get())
    }

    @Test fun aQualityThatYoutubeNoLongerOffersFailsInWords() = runBlocking {
        val plan = planFor(optionAt(720))
        resolver.dropItags = setOf(136)
        val id = manager.addYoutube(plan, "Some Title", true, 1)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertEquals(YtErrorKind.FORMAT_GONE.message, dao.find(id)!!.error)
    }

    @Test fun notEnoughFreeSpaceIsReportedBeforeAnythingIsFetched() = runBlocking {
        freeBytes = 1024 * 1024
        val id = addVideo(720)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertTrue(dao.find(id)!!.error!!.startsWith("Not enough free space"))
        assertTrue(media.hits.isEmpty())
    }

    @Test fun withoutAResolverYoutubeItemsFailInsteadOfCrashing() = runBlocking {
        val plain = DownloadManager(
            scope, dao, settings, DownloadEngine(OkHttpClient(), backoffMs = { 1 }), files, tempDir,
            DownloadManagerTest.FakeNetwork(), notifier,
        )
        val id = plain.addYoutube(YtPlan.deferred(videoId, YoutubeQuality.BEST), "x", true, 1)
        waitUntil("failed") { status(id) == DownloadStatus.FAILED }
        assertNotNull(dao.find(id)!!.error)
    }
}
