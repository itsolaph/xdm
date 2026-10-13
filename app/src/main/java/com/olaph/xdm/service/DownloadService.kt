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
import com.olaph.xdm.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (dataSync) that exists only while downloads are queued or running. It renders the
 * summary + per-download notifications; the downloads themselves are driven by DownloadManager.
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
        val notifications = container.notifications
        // Must happen within seconds of startForegroundService().
        val first = notifications.summary(0, 0, 0, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NotificationHelper.SUMMARY_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.SUMMARY_ID, first)
        }

        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        val manager = container.manager
        when (intent?.action) {
            ACTION_PAUSE -> manager.pause(id)
            ACTION_RESUME -> manager.resume(id)
            ACTION_CANCEL -> manager.cancel(id)
            ACTION_PAUSE_ALL -> manager.pauseAll()
            ACTION_RESUME_ALL -> manager.resumeAll()
        }
        if (collector == null) collector = scope.launch { observe() }
        return START_STICKY
    }

    private suspend fun observe() {
        val c = container
        combine(c.dao.observeOpen(), c.manager.live, c.manager.waitingForWifi) { open, live, wifi ->
            Triple(open, live, wifi)
        }.collectLatest { (open, live, wifi) ->
            val running = open.filter { it.status == DownloadStatus.RUNNING }
            val queued = open.count { it.status == DownloadStatus.QUEUED }
            val speed = running.sumOf { live[it.id]?.bytesPerSecond ?: 0L }
            c.notifications.postSummary(c.notifications.summary(running.size, queued, speed, wifi))

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

            if (running.isEmpty() && queued == 0) {
                // Debounce: a resume tapped on a notification arrives a moment after the service starts.
                delay(STOP_GRACE_MS)
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

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
        private const val STOP_GRACE_MS = 1500L

        /** Safe to call from anywhere; ignores the "not allowed to start from background" case. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }
    }
}
