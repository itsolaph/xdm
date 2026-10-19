package com.olaph.xdm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.olaph.xdm.XdmApp
import com.olaph.xdm.core.Hold
import com.olaph.xdm.core.LiveProgress
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (dataSync) that exists only while downloads are queued or running. It renders the
 * summary + per-download notifications; the downloads themselves are driven by DownloadManager.
 *
 * On Android 15 a dataSync service gets about six hours in any 24: the system then calls [onTimeout] and the service must
 * be gone within seconds, or the app is killed with a crash. Downloads are paused (saved, not failed) and a notification
 * offers to carry on; opening the app resets the system's clock.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    /** Ids with a notification this service posted, so they can be cleared when they leave the queue. */
    private val shown = HashSet<Long>()
    private val lastPosted = HashMap<Long, Long>()

    private val container get() = (application as XdmApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = container
        // Must happen within seconds of startForegroundService().
        val first = c.notifications.summary(0, 0, 0, false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NotificationHelper.SUMMARY_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NotificationHelper.SUMMARY_ID, first)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (started from the background without an exemption, by a boot receiver
            // on Android 15+, or with the dataSync time limit used up) or a missing permission. Nothing is lost: the downloads
            // stay as they are and the user is told how to go on.
            c.scope.launch { announceBlockedStart() }
            stopSelf()
            return START_NOT_STICKY
        }
        c.notifications.cancelAttention()
        c.releaseQueue()

        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        val manager = c.manager
        when (intent?.action) {
            ACTION_PAUSE -> manager.pause(id)
            ACTION_RESUME -> manager.resume(id)
            ACTION_CANCEL -> manager.cancel(id)
            ACTION_PAUSE_ALL -> manager.pauseAll()
            ACTION_RESUME_ALL -> manager.resumeAll()
            ACTION_RESUME_SYSTEM_PAUSED -> manager.resumeSystemPaused()
        }
        if (collector == null) collector = scope.launch { observe() }
        return START_STICKY
    }

    /** The service could not start: say what is waiting, in the way that fits the reason. */
    private suspend fun announceBlockedStart() {
        val c = container
        val systemPaused = c.dao.getSystemPaused().size
        val waiting = c.dao.getByStatus(DownloadStatus.QUEUED).size + c.dao.getByStatus(DownloadStatus.RUNNING).size
        when {
            systemPaused > 0 -> c.notifications.showSystemPaused(systemPaused)
            waiting > 0 && c.manager.hold.value == Hold.SCHEDULE -> c.notifications.showScheduledReady(waiting)
            waiting > 0 -> c.notifications.showResumePrompt(waiting, waitingForWifi = false)
        }
    }

    /**
     * Android 15+: the dataSync time limit is up. Pause everything cleanly (a few seconds at most), post the notice and
     * stop. Whatever goes wrong here, the service must still stop: the alternative is a crash.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val c = container
        scope.launch {
            try {
                val affected = c.dao.getByStatus(DownloadStatus.RUNNING).size + c.dao.getByStatus(DownloadStatus.QUEUED).size
                withTimeoutOrNull(PAUSE_BUDGET_MS) { c.manager.pauseForSystem() }
                if (affected > 0) c.notifications.showSystemPaused(affected)
            } catch (_: Throwable) {
                // fall through to stopping
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private suspend fun observe() {
        val c = container
        combine(
            c.dao.observeOpen(), c.manager.live, c.manager.hold, c.network.online,
            c.settings.flow.map { it.speedLimitBytesPerSec }.distinctUntilChanged(),
        ) { open, live, hold, online, limit -> Snapshot(open, live, hold, online, limit) }.collectLatest { (open, live, hold, online, limit) ->
            val running = open.filter { it.status == DownloadStatus.RUNNING }
            val queued = open.count { it.status == DownloadStatus.QUEUED }
            val speed = running.sumOf { live[it.id]?.bytesPerSecond ?: 0L }
            c.notifications.postSummary(c.notifications.summary(running.size, queued, speed, hold == Hold.WIFI, !online, limit))

            val now = System.currentTimeMillis()
            for (d in open) {
                when (d.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                        if (now - (lastPosted[d.id] ?: 0) < 800 && d.id in shown) continue
                        c.notifications.progress(d, live[d.id]); shown += d.id; lastPosted[d.id] = now
                    }
                    // Only keep a "paused" card for downloads that were visible as active.
                    DownloadStatus.PAUSED -> if (d.id in shown) {
                        c.notifications.progress(d, live[d.id]); lastPosted.remove(d.id)
                    }
                    else -> Unit
                }
            }
            val openIds = open.map { it.id }.toSet()
            shown.filter { it !in openIds }.forEach { c.notifications.cancel(it); shown -= it; lastPosted.remove(it) }

            // Downloads that wait for the schedule or the charger need no service: it would only use up the system's time.
            val waitingForClock = hold == Hold.SCHEDULE || hold == Hold.CHARGING
            if (running.isEmpty() && (queued == 0 || waitingForClock)) {
                // Debounce: a resume tapped on a notification arrives a moment after the service starts.
                delay(STOP_GRACE_MS)
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private data class Snapshot(
        val open: List<DownloadEntity>,
        val live: Map<Long, LiveProgress>,
        val hold: Hold?,
        val online: Boolean,
        val limit: Long,
    )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "download_id"
        const val ACTION_PAUSE = "com.olaph.xdm.PAUSE"
        const val ACTION_RESUME = "com.olaph.xdm.RESUME"
        const val ACTION_CANCEL = "com.olaph.xdm.CANCEL"
        const val ACTION_PAUSE_ALL = "com.olaph.xdm.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.olaph.xdm.RESUME_ALL"

        /** "Resume" on the after-restart notification: starting the service is all it takes, the queue does the rest. */
        const val ACTION_RESUME_INTERRUPTED = "com.olaph.xdm.RESUME_INTERRUPTED"

        /** "Resume" on the notice that Android's time limit paused the downloads. */
        const val ACTION_RESUME_SYSTEM_PAUSED = "com.olaph.xdm.RESUME_SYSTEM_PAUSED"
        private const val STOP_GRACE_MS = 1500L

        /** Long enough for the engines to save their segments, short enough for the system's few seconds. */
        private const val PAUSE_BUDGET_MS = 3_000L

        /** Safe to call from anywhere; false if the system refused the start (e.g. from the background). */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }.isSuccess
    }
}
