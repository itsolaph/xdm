package com.olaph.xdm.core

import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadDao
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.PauseNote
import com.olaph.xdm.data.SegmentEntity
import com.olaph.xdm.data.SettingsSource
import com.olaph.xdm.engine.DownloadEngine
import com.olaph.xdm.engine.EngineProgress
import com.olaph.xdm.engine.EngineRequest
import com.olaph.xdm.engine.FatalDownloadException
import com.olaph.xdm.engine.FileChangedException
import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.engine.HeaderCodec
import com.olaph.xdm.engine.HlsEngine
import com.olaph.xdm.engine.HlsProgress
import com.olaph.xdm.engine.HlsRequest
import com.olaph.xdm.engine.HttpStatusException
import com.olaph.xdm.engine.LinkMatch
import com.olaph.xdm.engine.ProbeResult
import com.olaph.xdm.engine.ProgressStore
import com.olaph.xdm.engine.SavedState
import com.olaph.xdm.engine.SegmentState
import com.olaph.xdm.engine.SpeedMeter
import com.olaph.xdm.engine.UrlUtils
import com.olaph.xdm.remux.RemuxResult
import com.olaph.xdm.remux.Remuxer
import com.olaph.xdm.remux.DashMuxer
import com.olaph.xdm.remux.Mp4DashMuxer
import com.olaph.xdm.remux.TsMp4Remuxer
import com.olaph.xdm.vault.VaultStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalTime
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

/** Outcome of moving a finished file into or out of the private folder. */
sealed interface MoveResult {
    data class Moved(val fileName: String) : MoveResult

    /**
     * The file is in the private folder and the record points there, but the public copy is still in Download/XDM:
     * [askSystem] = the app may not delete it and needs the system's confirmation, otherwise deleting it failed.
     */
    data class PublicCopyLeft(val target: DeleteTarget, val askSystem: Boolean) : MoveResult

    /** Nothing changed (apart from a removed partial copy). */
    data class Failed(val reason: String) : MoveResult
}

/** A candidate replacement link, probed and compared with the download it is meant for. */
class LinkCheck(val probe: ProbeResult, val verdict: LinkMatch.Verdict)

/** Result of [DownloadManager.applyRefreshedLink]. */
sealed interface AppliedLink {
    data object Replaced : AppliedLink
    data object Failed : AppliedLink
    class NeedsAnswer(val check: LinkCheck) : AppliedLink
}

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
    /** The private folder: where private downloads end up instead of [fileSaver]. Null = private downloads fail. */
    private val privateStore: VaultStore? = null,
    /** The charger, for the schedule's "only while charging". */
    private val power: PowerState = AlwaysCharging,
    /** Local time of day, for the schedule's window. */
    private val timeOfDay: () -> LocalTime = { LocalTime.now() },
    /** Free bytes on the volume of a custom save folder; null = not known (or no custom folder). */
    private val targetFreeSpace: () -> Long? = { null },
    /** The folder a new file of that name would be saved into (blocking); null = not known. For the duplicate check. */
    private val plannedFolder: (String) -> String? = { null },
) {
    /** NETWORK and SCHEDULE put the item back in the queue, SYSTEM pauses it with a note so it can resume by itself. */
    private enum class StopReason { PAUSE, CANCEL, DELETE, NETWORK, SCHEDULE, SYSTEM, REQUEUE }

    private val jobs = ConcurrentHashMap<Long, Job>()
    /** Downloads started with "Start now": the schedule does not apply to them while they run. */
    private val forced = ConcurrentHashMap.newKeySet<Long>()
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

    private val _hold = MutableStateFlow<Hold?>(null)
    /** Why queued downloads (without "Start now") are not starting right now; null = nothing holds them back. */
    val hold: StateFlow<Hold?> = _hold
    private var lastHold: Hold? = null

    /**
     * Called when the schedule window opens (or the charger is plugged in) and nothing is running: the queue is put on
     * hold and this should start the foreground service (its start releases the queue) or tell the user. Null = the
     * downloads just start.
     */
    @Volatile var onScheduleReady: (() -> Unit)? = null

    /**
     * Asked first when a download fails (with the stored error): true = someone deals with it and announces the outcome
     * themselves, so no failure notification is posted here.
     */
    @Volatile var interceptFailure: (suspend (DownloadEntity) -> Boolean)? = null

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

    private data class QueueInputs(val maxParallel: Int, val wifiOnly: Boolean, val plan: SchedulePlan, val metered: Boolean, val charging: Boolean)

    init {
        // Re-evaluate the queue whenever the limits, the schedule, the network or the charger change.
        scope.launch {
            combine(settings.flow, network.metered, power.charging) { s, metered, charging ->
                QueueInputs(s.maxParallel, s.wifiOnly, s.schedule, metered, charging)
            }.distinctUntilChanged().collect { pump() }
        }
        // The window opens and closes by the clock: look again at each of those moments (and at least once a minute,
        // in case the clock was changed).
        scope.launch {
            settings.flow.map { it.schedule }.distinctUntilChanged().collectLatest { plan ->
                while (true) {
                    val wait = RunGate.secondsUntilChange(timeOfDay(), plan) ?: break
                    delay(minOf(wait, MAX_TICK_SECONDS) * 1000 + 500)
                    pump()
                }
            }
        }
        // Connections bound to the previous network can hang for a long time: abort them, the segments retry on the new one.
        scope.launch {
            network.switches.collect {
                engine.interruptConnections()
                hlsEngine.interruptConnections()
            }
        }
    }

    /** Looks at the queue again; for callers that changed something the manager cannot observe (the clock, mostly). */
    suspend fun reevaluate() = pump()

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
        isPrivate: Boolean = false,
        /** The page the link was captured on; without one, a Referer header (if any) stands in. */
        sourcePage: String? = null,
    ): Long {
        val name = FilenameUtils.sanitize(fileName.ifBlank { FilenameUtils.resolve(null, url, probe?.mimeType) })
        val page = (sourcePage ?: headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value)
            ?.takeIf { UrlUtils.isHttpUrl(it) }
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
                    isPrivate = isPrivate,
                    sourcePage = page,
                ),
            )
            pumpLocked()
            newId
        }
        return id
    }

    private fun youtubeFileName(plan: YtPlan, title: String): String {
        val ext = if (plan.mode == YtPlan.Mode.AUDIO) "m4a" else "mp4"
        val label = plan.label.ifEmpty { plan.quality?.takeIf { it.height > 0 }?.let { "${it.height}p" }.orEmpty() }
        return FormatSelector.fileName(title, label, ext)
    }

    // ---- duplicates ----

    /** What [addYoutube] would add, for [findDuplicates]. */
    fun youtubeCandidate(plan: YtPlan, title: String, isPrivate: Boolean = false) = DuplicateCandidate(
        url = YoutubeUrls.watchUrl(plan.videoId),
        fileName = youtubeFileName(plan, title),
        totalBytes = plan.totalBytes,
        isPrivate = isPrivate,
        extra = plan.encode(),
        anyQuality = !plan.hasStreams,
    )

    /** The existing download that [candidate] would repeat, if any. Call before [add] / [addYoutube]. */
    suspend fun findDuplicate(candidate: DuplicateCandidate): DuplicateMatch? = findDuplicates(listOf(candidate)).single()

    /** One entry per candidate, null = not in the list yet (see [DuplicateCheck]). */
    suspend fun findDuplicates(candidates: List<DuplicateCandidate>): List<DuplicateMatch?> {
        if (candidates.all { it.isPrivate }) return candidates.map { null }
        val existing = dao.observeAll().first()
        return withContext(Dispatchers.IO) {
            val check = DuplicateCheck(fileSaver::exists, plannedFolder)
            candidates.map { check.find(it, existing) }
        }
    }

    /**
     * Queues a YouTube video. [plan] says which streams (or, for a playlist entry, which quality) to use; the
     * streams are looked up again when the download starts, so nothing here depends on an URL that could expire.
     * [title] and [quality] only name the item until then.
     */
    suspend fun addYoutube(plan: YtPlan, title: String, startNow: Boolean, connections: Int, isPrivate: Boolean = false): Long {
        val name = youtubeFileName(plan, title)
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
                    isPrivate = isPrivate,
                ),
            )
            pumpLocked()
            newId
        }
    }

    /**
     * Runs [block] serialized with the scheduler, then re-evaluates the queue. Undispatched, so the actions queue up for
     * the lock in the order they were called ("pause" followed by "resume" must not swap places on another thread).
     */
    private fun act(block: suspend () -> Unit) = scope.launch(start = CoroutineStart.UNDISPATCHED) {
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

    // ---- Android's background time limit ----

    /**
     * Android ended the foreground service's time (dataSync limit): pauses everything that runs or waits, cleanly (the
     * engines save their segments on the way out), and marks it with [PauseNote.SYSTEM_LIMIT] so it can be picked up by
     * [resumeSystemPaused]. Returns how many downloads were affected; suspends until the running ones are stopped.
     */
    suspend fun pauseForSystem(): Int = pumpMutex.withLock {
        val running = jobs.entries.map { it.key to it.value }
        running.forEach { (id, _) -> stop(id, StopReason.SYSTEM) }
        val waiting = dao.getByStatus(DownloadStatus.QUEUED).filter { !jobs.containsKey(it.id) }
        waiting.forEach { dao.setStatus(it.id, DownloadStatus.PAUSED, PauseNote.SYSTEM_LIMIT) }
        running.forEach { (_, job) -> job.join() } // the job's cleanup does not need the scheduler lock
        running.size + waiting.size
    }

    /** The user is back (app opened, or Resume tapped): what Android paused carries on. User-paused items stay paused. */
    fun resumeSystemPaused() {
        scope.launch { dao.getSystemPaused().forEach { resume(it.id) } }
    }

    // ---- a new link for a download that stopped ----

    /**
     * Probes [url] and compares it with download [id]: same size and no ETag change = the bytes on disk can be kept.
     * Throws when the link cannot be reached (the caller shows the message).
     */
    suspend fun checkLink(id: Long, url: String, headers: Map<String, String>? = null): LinkCheck {
        val d = dao.get(id) ?: throw FatalDownloadException("The download is gone")
        val probe = engine.prober.probe(url.trim(), settings.current().userAgent, headers ?: HeaderCodec.decode(d.headers))
        return judgeLink(id, probe)
    }

    /** Compares an already made [probe] with download [id] (the browser probes candidates itself). */
    suspend fun judgeLink(id: Long, probe: ProbeResult): LinkCheck {
        val d = dao.get(id) ?: throw FatalDownloadException("The download is gone")
        return LinkCheck(probe, LinkMatch.decide(d.totalBytes, d.etag, probe.contentLength, probe.etag))
    }

    /**
     * A link of the right size was found and already probed: swaps it in and carries on from the bytes on disk. Only a
     * file that is the same as far as the server tells ([LinkMatch.Verdict.Same]) is taken without asking; otherwise
     * [AppliedLink.NeedsAnswer] hands back the comparison for the user to decide.
     */
    suspend fun applyRefreshedLink(id: Long, url: String, probe: ProbeResult, headers: Map<String, String>?, sourcePage: String?): AppliedLink {
        val check = judgeLink(id, probe)
        if (check.verdict !is LinkMatch.Verdict.Same) return AppliedLink.NeedsAnswer(check)
        return if (replaceLink(id, url, probe, headers, sourcePage)) AppliedLink.Replaced else AppliedLink.Failed
    }

    /**
     * Swaps the link (and, when given, the headers and source page of the new capture) of download [id]. Without
     * [restart] the segments already on disk stay and the download continues; with it everything starts from zero.
     * The item goes back in the queue. False if the download cannot take a new link.
     */
    suspend fun replaceLink(
        id: Long,
        url: String,
        probe: ProbeResult,
        headers: Map<String, String>? = null,
        sourcePage: String? = null,
        restart: Boolean = false,
    ): Boolean = pumpMutex.withLock {
        val d = dao.get(id) ?: return@withLock false
        if (!LinkRefresh.canChangeLink(d)) return@withLock false
        jobs[id]?.let {
            stop(id, StopReason.PAUSE)
            it.join()
        }
        dao.setLink(id, url.trim(), if (headers != null) HeaderCodec.encode(headers) else d.headers, sourcePage?.takeIf { UrlUtils.isHttpUrl(it) })
        if (restart) {
            dao.resetProgress(id)
            withContext(Dispatchers.IO) { deleteTemp(id) }
            dao.updatePlan(id, probe.contentLength, probe.acceptRanges, probe.etag, probe.lastModified, probe.mimeType)
        }
        dao.requeue(id)
        pumpLocked()
        true
    }

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
     *
     * Only items on the side named by [privateItems] are touched: the public list's "delete all" can never reach into
     * the private folder, and the private folder's own delete never touches public files.
     */
    suspend fun deleteMany(ids: Collection<Long>, deleteFiles: Boolean, privateItems: Boolean = false): DeleteReport {
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
                    if (d == null || d.isPrivate != privateItems) { done++; continue }
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
                val store = if (d.isPrivate) privateStore else fileSaver
                val status = when {
                    !deleteFiles || location == null -> DeleteStatus.MISSING
                    store == null -> DeleteStatus.FAILED
                    else -> withContext(Dispatchers.IO) { store.delete(location) }
                }
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

    // ---- private folder ----

    /**
     * Copies a finished public file into the private folder, checks the size, points the record there and then deletes
     * the public copy. If the copy fails, the partial copy goes and nothing else changes; if only the delete fails, both
     * copies stay and [MoveResult.PublicCopyLeft] says so.
     */
    suspend fun moveToPrivate(id: Long): MoveResult = withContext(Dispatchers.IO) {
        val vault = privateStore ?: return@withContext MoveResult.Failed("The private folder is not available")
        val d = dao.get(id) ?: return@withContext MoveResult.Failed("The download is gone")
        val location = d.savedLocation
        if (d.isPrivate || d.status != DownloadStatus.COMPLETED || location == null) {
            return@withContext MoveResult.Failed("Only finished downloads can be moved")
        }
        val saved = try {
            fileSaver.open(location).use { vault.importStream(it, d.fileName, d.totalBytes.takeIf { n -> n > 0 } ?: -1) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext MoveResult.Failed(describe(e))
        }
        // The record follows the private copy before the public one is touched: whatever happens next, it points at a file.
        dao.setSavedFile(id, saved.location, saved.displayPath, saved.fileName, isPrivate = true)
        val target = DeleteTarget(id, location, d.totalBytes.coerceAtLeast(0))
        when (fileSaver.delete(location)) {
            DeleteStatus.DELETED, DeleteStatus.MISSING -> MoveResult.Moved(saved.fileName)
            DeleteStatus.DENIED -> MoveResult.PublicCopyLeft(target, askSystem = true)
            DeleteStatus.FAILED -> MoveResult.PublicCopyLeft(target, askSystem = false)
        }
    }

    /** "Move out": the file goes back to Download/XDM through the normal saver, then leaves the private folder. */
    suspend fun moveOutOfPrivate(id: Long): MoveResult = withContext(Dispatchers.IO) {
        val vault = privateStore ?: return@withContext MoveResult.Failed("The private folder is not available")
        val d = dao.get(id) ?: return@withContext MoveResult.Failed("The download is gone")
        val file = d.savedLocation?.let { vault.file(it) }
        if (!d.isPrivate || d.status != DownloadStatus.COMPLETED || file == null || !file.isFile) {
            return@withContext MoveResult.Failed("File not found")
        }
        val saved = try {
            fileSaver.save(file, d.fileName, d.mimeType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext MoveResult.Failed(describe(e))
        }
        dao.setSavedFile(id, saved.location, saved.displayPath, saved.fileName, isPrivate = false)
        vault.delete(file.absolutePath) // already gone if the saver could simply rename it
        MoveResult.Moved(saved.fileName)
    }

    /** Renames a finished private file; returns the name it got, or null. */
    suspend fun renamePrivate(id: Long, newName: String): String? = withContext(Dispatchers.IO) {
        val vault = privateStore ?: return@withContext null
        val d = dao.get(id)?.takeIf { it.isPrivate && it.status == DownloadStatus.COMPLETED } ?: return@withContext null
        val saved = d.savedLocation?.let { vault.rename(it, newName) } ?: return@withContext null
        dao.setSavedFile(id, saved.location, saved.displayPath, saved.fileName, isPrivate = true)
        saved.fileName
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
        val plan = s.schedule
        val now = timeOfDay()
        val charging = power.charging.value
        val metered = network.metered.value
        fun holdFor(force: Boolean) = RunGate.holdFor(now, charging, s.wifiOnly, metered, plan, force)

        val general = holdFor(false)
        _hold.value = general
        _waitingForWifi.value = general == Hold.WIFI

        // Whatever runs but may not any more goes back to the queue (Wi-Fi gone, window closed, charger unplugged).
        jobs.forEach { (id, job) ->
            if (stopReasons[job] != null) return@forEach
            when (holdFor(id in forced)) {
                Hold.WIFI -> stop(id, StopReason.NETWORK)
                Hold.SCHEDULE, Hold.CHARGING -> stop(id, StopReason.SCHEDULE)
                null -> Unit
            }
        }

        val queued = dao.getByStatus(DownloadStatus.QUEUED)
        val wake = onScheduleReady
        val opened = (lastHold == Hold.SCHEDULE || lastHold == Hold.CHARGING) && general == null
        lastHold = general
        if (wake != null && opened && jobs.isEmpty() && queued.any { !it.forceStart }) {
            // Nothing is running, so there is no foreground service: the queue waits until one is up (its start releases it).
            _queueReleased.value = false
            wake()
            return
        }

        var running = jobs.size
        for (d in queued) {
            if (jobs.containsKey(d.id)) continue
            if (holdFor(d.forceStart) != null) continue
            if (running >= s.maxParallel && !d.forceStart) continue
            start(d.id, d.forceStart)
            running++
        }
    }

    private suspend fun start(id: Long, force: Boolean = false) {
        dao.setStatus(id, DownloadStatus.RUNNING)
        if (force) forced += id
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) { runDownload(id) }
        jobs[id] = job
        // A job stopped before it got to run skips runDownload() and its cleanup altogether: do that part here.
        job.invokeOnCompletion {
            if (jobs.remove(id, job)) {
                forced -= id
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
                checkSpace(d)
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
            // A private download never touches MediaStore: the finished file moves straight into the private folder.
            val destination: FileStore = if (d.isPrivate) {
                privateStore ?: throw FatalDownloadException("The private folder is not available")
            } else {
                fileSaver
            }
            val target = withContext(Dispatchers.IO) { destination.save(result.file, result.fileName, result.mimeType) }
            val note = listOfNotNull(result.note, target.note).joinToString("\n").ifEmpty { null }
            dao.markCompleted(id, clock(), target.location, target.displayPath, target.fileName, result.totalBytes, note)
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
                if (e is FileChangedException) {
                    // The server's file is not the one on disk any more: the old bytes are worthless, begin again.
                    dao.resetProgress(id)
                    deleteTemp(id)
                    dao.requeue(id)
                } else {
                    dao.setStatus(id, DownloadStatus.FAILED, describe(e))
                    dao.get(id)?.let { failed ->
                        val handled = try {
                            interceptFailure?.invoke(failed) == true
                        } catch (_: Exception) {
                            false
                        }
                        if (!handled) notifier.onFailed(failed)
                    }
                }
            }
        } finally {
            jobs.remove(id)
            forced -= id
            // A resume/retry that came in after onStopped() saw the job still registered and only left REQUEUE
            // behind. Order matters: the job is removed first, then the reason (resume() relies on it).
            if (stopReasons.remove(self) == StopReason.REQUEUE) withContext(NonCancellable) { requeueStopped(id) }
            meters.remove(id)
            _live.update { it - id }
            scope.launch { pump() }
        }
    }

    /**
     * Fails early, with the numbers, when the bytes still to fetch do not fit where the partial file lives, or (for a
     * custom folder, when its volume can be read) when the finished file will not fit where it goes.
     */
    private fun checkSpace(d: DownloadEntity) {
        if (d.totalBytes <= 0) return
        val remaining = (d.totalBytes - d.downloadedBytes).coerceAtLeast(0)
        val here = freeSpace(tempDir)
        if (remaining > here) {
            throw FatalDownloadException("Not enough free space: ${Formatters.bytes(remaining)} needed, ${Formatters.bytes(here)} free")
        }
        if (d.isPrivate) return
        targetFreeSpace()?.let { there ->
            if (d.totalBytes > there) {
                throw FatalDownloadException(
                    "Not enough free space in the chosen folder: ${Formatters.bytes(d.totalBytes)} needed, ${Formatters.bytes(there)} free",
                )
            }
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
            StopReason.SYSTEM -> dao.setStatus(id, DownloadStatus.PAUSED, PauseNote.SYSTEM_LIMIT)
            StopReason.REQUEUE, StopReason.NETWORK, StopReason.SCHEDULE -> dao.requeue(id)
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
        is FatalDownloadException ->
            if (e.httpCode >= 300 || e.httpCode in 1..199) "Server error: HTTP ${e.httpCode}" else e.message ?: "Download failed"
        is HttpStatusException -> "Server error: HTTP ${e.httpCode}"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    private companion object {
        const val HLS_PERSIST_MS = 1_000L
        const val MAX_TICK_SECONDS = 60L
        const val ASSEMBLED = "assembled.properties"
        val STREAM_EXTENSIONS = setOf("ts", "mp4", "m4v", "m4a", "aac", "mp3", "m3u8", "m3u")
    }

    private fun SegmentState.toEntity(id: Long) = SegmentEntity(id, index, start, end, downloaded)
    private fun SegmentEntity.toState() = SegmentState(idx, start, end, downloaded)
}
