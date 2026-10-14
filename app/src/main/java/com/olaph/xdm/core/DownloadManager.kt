package com.olaph.xdm.core

import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadDao
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.SegmentEntity
import com.olaph.xdm.data.SettingsSource
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.EngineProgress
import com.olaph.xdm.engine.EngineRequest
import com.olaph.xdm.engine.FatalDownloadException
import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.engine.HeaderCodec
import com.olaph.xdm.engine.HlsEngine
import com.olaph.xdm.engine.HlsProgress
import com.olaph.xdm.engine.HlsRequest
import com.olaph.xdm.engine.HttpStatusException
import com.olaph.xdm.engine.ProbeResult
import com.olaph.xdm.engine.ProgressStore
import com.olaph.xdm.engine.SavedState
import com.olaph.xdm.engine.SegmentState
import com.olaph.xdm.engine.SpeedMeter
import com.olaph.xdm.remux.RemuxResult
import com.olaph.xdm.remux.Remuxer
import com.olaph.xdm.remux.DashMuxer
import com.olaph.xdm.remux.Mp4DashMuxer
import com.olaph.xdm.remux.TsMp4Remuxer
import com.olaph.xdm.youtube.FormatSelector
import com.olaph.xdm.youtube.YoutubeResolver
import com.olaph.xdm.youtube.YoutubeUrls
import com.olaph.xdm.youtube.YtPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Notifications raised from the manager so they work independently of the service lifecycle. */
interface DownloadNotifier {
    fun onCompleted(download: DownloadEntity)
    fun onFailed(download: DownloadEntity)
}

/** A saved file that is still to be deleted by the system's confirmation dialog. */
data class DeleteTarget(val id: Long, val location: String, val bytes: Long)

/**
 * Outcome of [DownloadManager.deleteMany]. [removed] records are gone, [freedBytes] is what the deleted files
 * occupied, [failed] files could not be deleted (their records stay), [needsPermission] are waiting for the user.
 */
data class DeleteReport(val removed: Int, val freedBytes: Long, val failed: Int, val needsPermission: List<DeleteTarget>)

data class DeleteProgress(val done: Int, val total: Int)

/** Live (non-persisted) numbers for a running download. */
data class LiveProgress(
    val downloaded: Long,
    val total: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long,
    /** HLS only: finished segments / segments in the playlist. */
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    /** 0..100 while a finished HLS download is being converted to MP4 (then [downloaded]/[total] are bytes read); -1 otherwise. */
    val convertPercent: Int = -1,
    /** Replaces the word "Downloading" in the status line ("Getting video info…", "Downloading audio"); YouTube stages. */
    val statusText: String? = null,
    /** What the [convertPercent] phase is called. */
    val convertLabel: String = "Converting to MP4…",
) {
    val converting: Boolean get() = convertPercent >= 0
}

/**
 * Owns the queue: decides which downloads run (max-parallel, Wi-Fi only), drives the engine, and keeps
 * Room in sync. The UI observes Room for state and [live] for speed/ETA.
 */
class DownloadManager(
    private val scope: CoroutineScope,
    private val dao: DownloadDao,
    private val settings: SettingsSource,
    private val engine: DownloadEngine,
    private val fileSaver: FileStore,
    private val tempDir: File,
    private val network: NetworkState,
    private val notifier: DownloadNotifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hlsEngine: HlsEngine = HlsEngine(OkHttpClient()),
    private val remuxer: Remuxer = TsMp4Remuxer(),
    /** Free bytes where [tempDir] lives; replaceable so the low-space path can be tested. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
    /** Looks YouTube videos up; null disables YouTube downloads (they fail with a message). */
    private val youtube: YoutubeResolver? = null,
    private val dashMuxer: DashMuxer = Mp4DashMuxer(),
    private val youtubeRetryDelayMs: Long = 2_000,
    /** Largest range requested from googlevideo in one go (tests use small values). */
    private val youtubeChunkBytes: Long = YoutubeJob.CHUNK_BYTES,
    /**
     * Start with the queue on hold: nothing runs until [releaseQueue]. The app process can be started by the system
     * (after a reboot, say) at a moment when it may not run a foreground service, and downloads must not run without one.
     */
    holdQueue: Boolean = false,
) {
    private enum class StopReason { PAUSE, CANCEL, DELETE, NETWORK, REQUEUE }

    private val jobs = ConcurrentHashMap<Long, Job>()
    /**
     * Why a job was stopped, per job rather than per download: the next job of the same download can already be
     * running (and be stopped again) while the previous one is still unwinding.
     */
    private val stopReasons = ConcurrentHashMap<Job, StopReason>()
    private val meters = ConcurrentHashMap<Long, SpeedMeter>()
    private val pumpMutex = Mutex()

    private val _live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<Long, LiveProgress>> = _live

    private val _deleteProgress = MutableStateFlow<DeleteProgress?>(null)
    /** Non-null while [deleteMany] runs. */
    val deleteProgress: StateFlow<DeleteProgress?> = _deleteProgress

    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi

    private val _queueReleased = MutableStateFlow(!holdQueue)
    val queueReleased: StateFlow<Boolean> = _queueReleased

    /** Lets the queue run (an activity is showing, or the foreground service is up). Idempotent. */
    fun releaseQueue() {
        if (_queueReleased.compareAndSet(false, true)) scope.launch { pump() }
    }

    fun tempFile(id: Long) = File(tempDir, "$id.part")

    /** Finished HLS segments of download [id]; this directory is the HLS resume state. */
    fun hlsDir(id: Long) = File(tempDir, "hls-$id")

    /** The MP4 being written from the concatenated .ts; only exists during (and right after) a conversion. */
    fun convertedFile(id: Long) = File(tempDir, "$id.mp4.part")

    private val youtubeJob: YoutubeJob? = youtube?.let { YoutubeJob(engine, dao, it, dashMuxer, tempDir, freeSpace, youtubeRetryDelayMs, youtubeChunkBytes) }

    private fun deleteTemp(id: Long) {
        tempFile(id).delete()
        convertedFile(id).delete()
        hlsDir(id).deleteRecursively()
        File(tempDir, "yt-$id").deleteRecursively()
    }

    private val store = object : ProgressStore {
        override suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>) {
            dao.updatePlan(id, probe.contentLength, probe.acceptRanges, probe.etag, probe.lastModified, probe.mimeType)
            dao.replaceSegments(id, segments.map { it.toEntity(id) })
            dao.setDownloaded(id, segments.sumOf { it.downloaded })
        }

        override suspend fun saveProgress(id: Long, segments: List<SegmentState>) {
            dao.saveProgress(id, segments.map { it.toEntity(id) }, segments.sumOf { it.downloaded })
        }
    }

    init {
        // Re-evaluate the queue whenever the limits or the network change.
        scope.launch {
            combine(settings.flow, network.metered) { s, metered -> Triple(s.maxParallel, s.wifiOnly, metered) }
                .distinctUntilChanged()
                .collect { pump() }
        }
    }

    /** Call once at app start: anything that was RUNNING when the process died goes back to the queue. */
    fun restore() {
        scope.launch {
            dao.requeueRunning()
            pump()
        }
    }

    // ---- public API ----

    suspend fun probe(url: String, headers: Map<String, String> = emptyMap()): ProbeResult =
        engine.prober.probe(url.trim(), settings.current().userAgent, headers)

    suspend fun add(
        url: String,
        fileName: String,
        connections: Int,
        startNow: Boolean,
        probe: ProbeResult? = null,
        headers: Map<String, String> = emptyMap(),
        kind: String = DownloadKind.HTTP,
    ): Long {
        val name = FilenameUtils.sanitize(fileName.ifBlank { FilenameUtils.resolve(null, url, probe?.mimeType) })
        val id = pumpMutex.withLock {
            val newId = dao.insert(
                DownloadEntity(
                    url = url.trim(),
                    fileName = name,
                    mimeType = probe?.mimeType,
                    totalBytes = probe?.contentLength ?: -1,
                    acceptRanges = probe?.acceptRanges,
                    etag = probe?.etag,
                    lastModified = probe?.lastModified,
                    connections = connections.coerceIn(1, 16),
                    createdAt = clock(),
                    forceStart = startNow,
                    headers = HeaderCodec.encode(headers),
                    kind = kind,
                ),
            )
            pumpLocked()
            newId
        }
        return id
    }

    /**
     * Queues a YouTube video. [plan] says which streams (or, for a playlist entry, which quality) to use; the
     * streams are looked up again when the download starts, so nothing here depends on an URL that could expire.
     * [title] and [quality] only name the item until then.
     */
    suspend fun addYoutube(plan: YtPlan, title: String, startNow: Boolean, connections: Int): Long {
        val ext = if (plan.mode == YtPlan.Mode.AUDIO) "m4a" else "mp4"
        val label = plan.label.ifEmpty { plan.quality?.takeIf { it.height > 0 }?.let { "${it.height}p" }.orEmpty() }
        val name = FormatSelector.fileName(title, label, ext)
        return pumpMutex.withLock {
            val newId = dao.insert(
                DownloadEntity(
                    url = YoutubeUrls.watchUrl(plan.videoId),
                    fileName = name,
                    mimeType = if (plan.mode == YtPlan.Mode.AUDIO) "audio/mp4" else "video/mp4",
                    totalBytes = plan.totalBytes,
                    acceptRanges = true,
                    connections = connections.coerceIn(1, 16),
                    createdAt = clock(),
                    forceStart = startNow,
                    kind = DownloadKind.YOUTUBE,
                    extra = plan.encode(),
                ),
            )
            pumpLocked()
            newId
        }
    }

    /** Runs [block] serialized with the scheduler, then re-evaluates the queue. */
    private fun act(block: suspend () -> Unit) = scope.launch {
        pumpMutex.withLock {
            block()
            pumpLocked()
        }
    }

    fun pause(id: Long) = act {
        val d = dao.get(id) ?: return@act
        when (d.status) {
            DownloadStatus.RUNNING -> if (jobs.containsKey(id)) stop(id, StopReason.PAUSE) else dao.setStatus(id, DownloadStatus.PAUSED)
            DownloadStatus.QUEUED -> dao.setStatus(id, DownloadStatus.PAUSED)
            else -> Unit
        }
    }

    fun resume(id: Long) = act {
        val d = dao.get(id) ?: return@act
        when (d.status) {
            DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED -> {
                val job = jobs[id]
                if (job != null) {
                    // The stopped job is still unwinding (its finally block has not run yet). Leave REQUEUE for it,
                    // then look again: if the job got removed meanwhile, whoever takes REQUEUE out of the map first
                    // does the requeue, so it happens exactly once and never gets lost.
                    stopReasons[job] = StopReason.REQUEUE
                    if (jobs[id] !== job && stopReasons.remove(job, StopReason.REQUEUE)) requeueStopped(id)
                } else {
                    requeueStopped(id)
                }
            }
            DownloadStatus.RUNNING -> jobs[id]?.let { stopReasons.replace(it, StopReason.PAUSE, StopReason.REQUEUE) }
            else -> Unit
        }
    }

    fun retry(id: Long) = resume(id)

    fun cancel(id: Long) = act {
        val d = dao.get(id) ?: return@act
        if (d.status == DownloadStatus.COMPLETED || d.status == DownloadStatus.CANCELLED) return@act
        if (jobs.containsKey(id)) stop(id, StopReason.CANCEL) else finishCancelled(id)
    }

    /** Same path as [deleteMany]; the outcome is dropped (the detail screen and tests only watch the records). */
    fun delete(id: Long, deleteFile: Boolean) = scope.launch { deleteMany(listOf(id), deleteFile) }

    /**
     * Deletes [ids]: running/queued ones are stopped first, then temp files, partial data and HLS segment dirs
     * go; with [deleteFiles] the saved files of completed ones too. A record is removed once its file is gone
     * (or was already missing). Files the app may not delete ([DeleteStatus.DENIED]) keep their record and come
     * back in [DeleteReport.needsPermission] for the system's delete request, then [removeRecords].
     */
    suspend fun deleteMany(ids: Collection<Long>, deleteFiles: Boolean): DeleteReport {
        val wanted = ids.distinct()
        _deleteProgress.value = DeleteProgress(0, wanted.size)
        var removed = 0
        var freed = 0L
        var failed = 0
        var done = 0
        val denied = ArrayList<DeleteTarget>()
        try {
            // Active ones first and under the scheduler lock, so the queue cannot start a download we are about to delete.
            val completed = ArrayList<DownloadEntity>()
            pumpMutex.withLock {
                for (id in wanted) {
                    val d = dao.get(id)
                    if (d == null) { done++; continue }
                    jobs[id]?.let {
                        stop(id, StopReason.DELETE)
                        it.join() // the job's cleanup does not need the scheduler lock
                    }
                    if (d.status == DownloadStatus.COMPLETED) { completed += d; continue }
                    dao.delete(id)
                    withContext(Dispatchers.IO) { deleteTemp(id) }
                    removed++
                    _deleteProgress.value = DeleteProgress(++done, wanted.size)
                }
                pumpLocked()
            }
            for (d in completed) {
                val location = d.savedLocation
                val status = if (deleteFiles && location != null) withContext(Dispatchers.IO) { fileSaver.delete(location) } else DeleteStatus.MISSING
                val size = d.totalBytes.coerceAtLeast(0)
                when (status) {
                    DeleteStatus.DELETED -> { freed += size; removed++; removeRecord(d.id) }
                    DeleteStatus.MISSING -> { removed++; removeRecord(d.id) }
                    DeleteStatus.DENIED -> denied += DeleteTarget(d.id, location!!, size)
                    DeleteStatus.FAILED -> failed++
                }
                _deleteProgress.value = DeleteProgress(++done, wanted.size)
            }
        } finally {
            _deleteProgress.value = null
        }
        return DeleteReport(removed, freed, failed, denied)
    }

    /** After the user approved the system's delete request for [targets]: their files are gone, drop the records. */
    suspend fun removeRecords(targets: List<DeleteTarget>): DeleteReport {
        targets.forEach { removeRecord(it.id) }
        return DeleteReport(targets.size, targets.sumOf { it.bytes }, 0, emptyList())
    }

    private suspend fun removeRecord(id: Long) {
        dao.delete(id)
        withContext(Dispatchers.IO) { deleteTemp(id) }
    }

    fun pauseAll() = act {
        jobs.keys.forEach { stop(it, StopReason.PAUSE) }
        // Queued items without a job are paused straight in the database.
        dao.getByStatus(DownloadStatus.QUEUED).filter { !jobs.containsKey(it.id) }
            .forEach { dao.setStatus(it.id, DownloadStatus.PAUSED) }
    }

    fun resumeAll() = act {
        stopReasons.keys.forEach { stopReasons.replace(it, StopReason.PAUSE, StopReason.REQUEUE) }
        dao.getByStatus(DownloadStatus.PAUSED).filter { !jobs.containsKey(it.id) }.forEach { dao.requeue(it.id) }
    }

    // ---- scheduling ----

    private fun stop(id: Long, reason: StopReason) {
        val job = jobs[id] ?: return
        stopReasons[job] = reason
        job.cancel()
    }

    private suspend fun pump() = pumpMutex.withLock { pumpLocked() }

    private suspend fun pumpLocked() {
        if (!_queueReleased.value) return
        val s = settings.current()
        val blocked = s.wifiOnly && network.metered.value
        _waitingForWifi.value = blocked
        if (blocked) {
            jobs.forEach { (id, job) -> if (stopReasons[job] == null) stop(id, StopReason.NETWORK) }
            return
        }
        var running = jobs.size
        for (d in dao.getByStatus(DownloadStatus.QUEUED)) {
            if (jobs.containsKey(d.id)) continue
            if (running >= s.maxParallel && !d.forceStart) continue
            start(d.id)
            running++
        }
    }

    private suspend fun start(id: Long) {
        dao.setStatus(id, DownloadStatus.RUNNING)
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) { runDownload(id) }
        jobs[id] = job
        // A job stopped before it got to run skips runDownload() and its cleanup altogether: do that part here.
        job.invokeOnCompletion {
            if (jobs.remove(id, job)) {
                scope.launch {
                    withContext(NonCancellable) { onStopped(id, stopReasons.remove(job)) }
                    pump()
                }
            }
        }
        job.start()
    }

    // ---- one download ----

    private suspend fun runDownload(id: Long) {
        val self = currentCoroutineContext()[Job]!!
        try {
            val d = dao.get(id) ?: return
            val s = settings.current()
            val temp = tempFile(id).also { it.parentFile?.mkdirs() }
            meters[id] = SpeedMeter()

            val result: Finished = if (d.isYoutube) {
                runYoutube(d, s)
            } else if (d.isHls) {
                runHls(d, s, temp)
            } else {
                val segments = dao.getSegments(id)
                val saved = if (segments.isNotEmpty() && d.totalBytes > 0) {
                    SavedState(d.totalBytes, d.acceptRanges == true, d.etag, d.lastModified, segments.map { it.toState() })
                } else null
                val r = engine.download(
                    EngineRequest(id, d.url, s.userAgent, temp, d.connections, saved, HeaderCodec.decode(d.headers)),
                    store,
                ) { p -> publish(id, p) }
                Finished(r.totalBytes, r.probe.mimeType ?: d.mimeType, d.fileName, file = temp)
            }

            // Moving a large file into MediaStore takes a while; show it as 100% / no speed meanwhile.
            _live.update { it + (id to LiveProgress(result.totalBytes, result.totalBytes, 0, 0, result.segments, result.segments)) }
            val target = withContext(Dispatchers.IO) { fileSaver.save(result.file, result.fileName, result.mimeType) }
            dao.markCompleted(id, clock(), target.location, target.displayPath, target.fileName, result.totalBytes, result.note)
            dao.deleteSegments(id)
            withContext(Dispatchers.IO) { deleteTemp(id) }
            dao.get(id)?.let { notifier.onCompleted(it) }
        } catch (e: CancellationException) {
            // The reason stays in the map until `finally`: a resume arriving while the status is still RUNNING
            // turns PAUSE into REQUEUE there instead of being lost.
            withContext(NonCancellable) { onStopped(id, stopReasons[self]) }
            throw e
        } catch (e: Throwable) {
            // A stop request can surface as an I/O error instead of a CancellationException (the socket is
            // closed under the reader). The job is cancelled then: treat it as the stop, not as a failure.
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) { onStopped(id, stopReasons[self]) }
                throw CancellationException("stopped").apply { initCause(e) }
            }
            withContext(NonCancellable) {
                stopReasons.remove(self)
                dao.setStatus(id, DownloadStatus.FAILED, describe(e))
                dao.get(id)?.let { notifier.onFailed(it) }
            }
        } finally {
            jobs.remove(id)
            // A resume/retry that came in after onStopped() saw the job still registered and only left REQUEUE
            // behind. Order matters: the job is removed first, then the reason (resume() relies on it).
            if (stopReasons.remove(self) == StopReason.REQUEUE) withContext(NonCancellable) { requeueStopped(id) }
            meters.remove(id)
            _live.update { it - id }
            scope.launch { pump() }
        }
    }

    /** [file] is what gets saved: the downloaded file, or the converted MP4. [note] is a non-fatal remark for the item. */
    private class Finished(
        val totalBytes: Long,
        val mimeType: String?,
        val fileName: String,
        val segments: Int = 0,
        val file: File,
        val note: String? = null,
    )

    private suspend fun runYoutube(d: DownloadEntity, s: AppSettings): Finished {
        val job = youtubeJob ?: throw FatalDownloadException("YouTube downloads are not available in this build")
        val id = d.id
        val r = job.run(
            d, s.userAgent,
            object : YoutubeJob.Sink {
                override fun status(text: String) {
                    _live.update { it + (id to LiveProgress(d.downloadedBytes, d.totalBytes, 0, -1, statusText = text)) }
                }

                override fun bytes(done: Long, total: Long, stage: String) = publishBytes(id, done, total, "Downloading $stage")

                override fun merging(done: Long, total: Long) {
                    val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                    _live.update { it + (id to LiveProgress(done, total, 0, -1, convertPercent = pct, convertLabel = "Merging video and audio…")) }
                }
            },
        )
        return Finished(r.totalBytes, r.mimeType, r.fileName, file = r.file, note = r.note)
    }

    /** What the HLS engine produced, remembered in [ASSEMBLED] so a resumed download does not fetch or join anything twice. */
    private class Assembled(val bytes: Long, val extension: String, val mimeType: String, val segments: Int)

    private fun readAssembled(id: Long, temp: File): Assembled? = runCatching {
        val props = java.util.Properties().apply { File(hlsDir(id), ASSEMBLED).inputStream().use { load(it) } }
        Assembled(
            props.getProperty("bytes").toLong(), props.getProperty("extension"), props.getProperty("mime"), props.getProperty("segments").toInt(),
        ).takeIf { temp.isFile && temp.length() == it.bytes }
    }.getOrNull()

    private fun writeAssembled(id: Long, a: Assembled) {
        val dir = hlsDir(id)
        val props = java.util.Properties().apply {
            setProperty("bytes", a.bytes.toString()); setProperty("extension", a.extension)
            setProperty("mime", a.mimeType); setProperty("segments", a.segments.toString())
        }
        File(dir, "$ASSEMBLED.tmp").outputStream().use { props.store(it, null) }
        File(dir, "$ASSEMBLED.tmp").renameTo(File(dir, ASSEMBLED))
        // The joined file now holds everything; the separate segments only cost disk space (3x -> 2x).
        dir.listFiles()?.filter { it.name != ASSEMBLED }?.forEach { it.deleteRecursively() }
    }

    /**
     * Downloads the segments and joins them into [temp] (skipped when an earlier run already did), then — for
     * MPEG-TS and if enabled — converts that to an MP4. The result is named after what was produced.
     */
    private suspend fun runHls(d: DownloadEntity, s: AppSettings, temp: File): Finished {
        val id = d.id
        var assembled = withContext(Dispatchers.IO) { readAssembled(id, temp) }
        if (assembled == null) {
            var lastPersist = 0L
            val result = hlsEngine.download(
                HlsRequest(
                    id = id,
                    playlistUrl = d.url,
                    userAgent = s.userAgent,
                    workDir = hlsDir(id),
                    output = temp,
                    concurrency = d.connections,
                    headers = HeaderCodec.decode(d.headers),
                ),
            ) { p ->
                publishHls(id, p)
                val now = clock()
                if (now - lastPersist >= HLS_PERSIST_MS || p.segmentsDone == p.segmentsTotal) {
                    lastPersist = now
                    dao.setHlsProgress(id, p.segmentsDone, p.segmentsTotal, p.bytes)
                }
            }
            assembled = Assembled(result.totalBytes, result.extension, result.mimeType, result.segments)
            withContext(Dispatchers.IO) { writeAssembled(id, assembled) }
        }

        if (assembled.extension == "ts" && s.convertHlsToMp4) {
            val converted = convertToMp4(d, temp, assembled)
            if (converted.file != null) {
                val r = converted.result!!
                return Finished(
                    converted.file.length(), r.mimeType, withStreamExtension(d.fileName, r.extension), assembled.segments, converted.file,
                )
            }
            return Finished(
                assembled.bytes, assembled.mimeType, withStreamExtension(d.fileName, assembled.extension), assembled.segments, temp, converted.note,
            )
        }
        return Finished(assembled.bytes, assembled.mimeType, withStreamExtension(d.fileName, assembled.extension), assembled.segments, temp)
    }

    private class Conversion(val file: File?, val result: RemuxResult?, val note: String?)

    /** Never fails the download: if the stream cannot be converted the .ts is kept and [Conversion.note] says why. */
    private suspend fun convertToMp4(d: DownloadEntity, ts: File, assembled: Assembled): Conversion {
        val id = d.id
        val out = convertedFile(id)
        val need = ts.length()
        if (freeSpace(tempDir) < need) {
            return Conversion(null, null, "Saved as .ts (not enough free space to convert: ${Formatters.bytes(need)} needed)")
        }
        publishConversion(id, 0, need, assembled.segments)
        try {
            val r = remuxer.remux(ts, out) { done, total -> publishConversion(id, done, total, assembled.segments) }
            return Conversion(out, r, null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { out.delete() }
            throw e
        } catch (e: Exception) {
            out.delete()
            val why = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return Conversion(null, null, "Saved as .ts (conversion failed: $why)")
        }
    }

    private fun publishConversion(id: Long, done: Long, total: Long, segments: Int) {
        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        _live.update { it + (id to LiveProgress(done, total, 0, -1, segments, segments, convertPercent = pct)) }
    }

    /** Swaps a stream extension (.ts/.mp4/...) for the one the assembled file really has. */
    private fun withStreamExtension(name: String, ext: String): String {
        val (base, current) = FilenameUtils.splitExtension(name)
        val cur = current.removePrefix(".").lowercase()
        if (cur == ext) return name
        return if (cur.isEmpty() || cur in STREAM_EXTENSIONS) "$base.$ext" else name
    }

    private fun publishHls(id: Long, p: HlsProgress) {
        val meter = meters[id] ?: return
        meter.add(clock(), p.bytes)
        val speed = meter.bytesPerSecond()
        val est = p.estimatedTotalBytes
        val eta = if (est > 0) SpeedMeter.etaSeconds((est - p.bytes).coerceAtLeast(0), speed) else -1
        _live.update { it + (id to LiveProgress(p.bytes, est, speed, eta, p.segmentsDone, p.segmentsTotal)) }
    }

    private suspend fun onStopped(id: Long, reason: StopReason?) {
        when (reason) {
            StopReason.PAUSE -> dao.setStatus(id, DownloadStatus.PAUSED)
            StopReason.REQUEUE, StopReason.NETWORK -> dao.requeue(id)
            StopReason.CANCEL -> finishCancelled(id)
            StopReason.DELETE -> Unit
            null -> Unit // scope cancelled (process shutting down): stays RUNNING, restore() requeues it
        }
    }

    /** Puts a stopped item (paused, failed or cancelled) back in the queue; a cancelled one starts from zero. */
    private suspend fun requeueStopped(id: Long) {
        val d = dao.get(id) ?: return
        if (d.status !in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)) return
        if (d.status == DownloadStatus.CANCELLED) dao.resetProgress(id)
        dao.requeue(id)
    }

    private suspend fun finishCancelled(id: Long) {
        dao.setStatus(id, DownloadStatus.CANCELLED)
        dao.resetProgress(id)
        withContext(Dispatchers.IO) { deleteTemp(id) }
    }

    private fun publish(id: Long, p: EngineProgress) = publishBytes(id, p.downloaded, p.total)

    private fun publishBytes(id: Long, downloaded: Long, total: Long, statusText: String? = null) {
        val meter = meters[id] ?: return
        meter.add(clock(), downloaded)
        val speed = meter.bytesPerSecond()
        val eta = if (total > 0) SpeedMeter.etaSeconds(total - downloaded, speed) else -1
        _live.update { it + (id to LiveProgress(downloaded, total, speed, eta, statusText = statusText)) }
    }

    private fun describe(e: Throwable): String = when (e) {
        is FatalDownloadException -> if (e.httpCode > 0) "Server error: HTTP ${e.httpCode}" else e.message ?: "Download failed"
        is HttpStatusException -> "Server error: HTTP ${e.httpCode}"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private companion object {
        const val HLS_PERSIST_MS = 1_000L
        const val ASSEMBLED = "assembled.properties"
        val STREAM_EXTENSIONS = setOf("ts", "mp4", "m4v", "m4a", "aac", "mp3", "m3u8", "m3u")
    }

    private fun SegmentState.toEntity(id: Long) = SegmentEntity(id, index, start, end, downloaded)
    private fun SegmentEntity.toState() = SegmentState(idx, start, end, downloaded)
}
