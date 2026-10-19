package com.olaph.xdm.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.olaph.xdm.R
import com.olaph.xdm.core.DownloadNotifier
import com.olaph.xdm.core.FileSaver
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.core.LinkRefresh
import com.olaph.xdm.core.LiveProgress
import com.olaph.xdm.core.RefreshNotifier
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.MimeTypes
import com.olaph.xdm.ui.MainActivity

class NotificationHelper(
    private val context: Context,
    private val fileSaver: FileSaver,
) : DownloadNotifier, RefreshNotifier {

    private val nm = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sys = context.getSystemService(NotificationManager::class.java)
            sys.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Active downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
            sys.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
            )
            sys.createNotificationChannel(
                NotificationChannel(CHANNEL_RESUME, "Resume after restart", NotificationManager.IMPORTANCE_DEFAULT),
            )
            sys.createNotificationChannel(
                NotificationChannel(CHANNEL_ATTENTION, "Paused and scheduled downloads", NotificationManager.IMPORTANCE_DEFAULT),
            )
            sys.createNotificationChannel(
                NotificationChannel(CHANNEL_REFRESHED, "Refreshed links", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission") // guarded by canNotify()
    private fun notify(id: Int, n: Notification) {
        if (canNotify()) runCatching { nm.notify(id, n) }
    }

    fun cancel(downloadId: Long) = nm.cancel(progressId(downloadId))

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The private folder's screen, which asks for the PIN first; never the file itself. */
    private fun openPrivate(): PendingIntent = PendingIntent.getActivity(
        context, PRIVATE_REQUEST,
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PRIVATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun NotificationCompat.Builder.privacy(d: DownloadEntity) = apply {
        if (d.isPrivate) setVisibility(NotificationCompat.VISIBILITY_SECRET)
    }

    private fun action(action: String, id: Long?, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(action)
        if (id != null) intent.putExtra(DownloadService.EXTRA_ID, id)
        return PendingIntent.getForegroundService(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun requestCode(id: Long, salt: Int) = (id.toInt() * 4 + salt)

    // ---- foreground summary ----

    fun summary(
        active: Int,
        queued: Int,
        bytesPerSecond: Long,
        waitingForWifi: Boolean,
        offline: Boolean = false,
        speedLimit: Long = 0,
    ): Notification {
        val text = when {
            waitingForWifi -> "Waiting for Wi-Fi"
            offline -> "Waiting for network"
            active == 0 && queued > 0 -> "$queued queued"
            else -> buildString {
                append("$active downloading")
                if (queued > 0) append(" · $queued queued")
                if (bytesPerSecond > 0) append(" · ${Formatters.speed(bytesPerSecond)}")
                if (speedLimit > 0) append(" · limit ${Formatters.speed(speedLimit)}")
            }
        }
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("XDM")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(GROUP).setGroupSummary(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Pause all", action(DownloadService.ACTION_PAUSE_ALL, null, 1))
            .build()
    }

    fun postSummary(n: Notification) = notify(SUMMARY_ID, n)

    // ---- per download ----

    fun progress(d: DownloadEntity, live: LiveProgress?) {
        val done = live?.downloaded ?: d.downloadedBytes
        val total = live?.total ?: d.totalBytes
        val running = d.status == DownloadStatus.RUNNING
        val b = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(if (running) R.drawable.ic_stat_download else R.drawable.ic_stat_pause)
            .setContentTitle(NotificationText.title(d))
            .setContentIntent(if (d.isPrivate) openPrivate() else openApp())
            .privacy(d)
            .setOngoing(running || d.status == DownloadStatus.QUEUED)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup(GROUP)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when (d.status) {
            DownloadStatus.RUNNING -> if (live?.converting == true) {
                b.setProgress(100, live.convertPercent, false)
                b.setContentText("${live.convertLabel} ${live.convertPercent}%")
                b.addAction(0, "Pause", action(DownloadService.ACTION_PAUSE, d.id, requestCode(d.id, 0)))
            } else {
                if (total > 0) b.setProgress(100, (done * 100 / total).toInt(), false) else b.setProgress(0, 0, true)
                val parts = mutableListOf<String>()
                live?.statusText?.let { parts += it }
                parts += if (total > 0) "${Formatters.bytes(done)} / ${Formatters.bytes(total)}" else Formatters.bytes(done)
                live?.takeIf { it.bytesPerSecond > 0 }?.let {
                    parts += Formatters.speed(it.bytesPerSecond)
                    if (it.etaSeconds >= 0) parts += Formatters.eta(it.etaSeconds)
                }
                b.setContentText(parts.joinToString(" · "))
                b.addAction(0, "Pause", action(DownloadService.ACTION_PAUSE, d.id, requestCode(d.id, 0)))
            }
            DownloadStatus.PAUSED -> {
                if (total > 0) b.setProgress(100, (done * 100 / total).toInt(), false)
                b.setContentText("Paused · ${Formatters.bytes(done)}")
                b.addAction(0, "Resume", action(DownloadService.ACTION_RESUME, d.id, requestCode(d.id, 1)))
            }
            else -> { // QUEUED
                b.setContentText("Queued")
                b.setProgress(0, 0, true)
                b.addAction(0, "Pause", action(DownloadService.ACTION_PAUSE, d.id, requestCode(d.id, 0)))
            }
        }
        b.addAction(0, "Cancel", action(DownloadService.ACTION_CANCEL, d.id, requestCode(d.id, 2)))
        notify(progressId(d.id), b.build())
    }

    // ---- after a reboot ----

    /**
     * "N downloads ready to resume". Used when the service may not start on its own (Android 15+ after a reboot, or
     * any refused start). Both the Resume button and a tap count as the user acting, which lets the service start.
     */
    fun showResumePrompt(count: Int, waitingForWifi: Boolean) {
        val title = if (count == 1) "1 download ready to resume" else "$count downloads ready to resume"
        val text = if (waitingForWifi) "They were interrupted by the restart and will wait for Wi-Fi" else "They were interrupted by the restart"
        val n = NotificationCompat.Builder(context, CHANNEL_RESUME)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "Resume", action(DownloadService.ACTION_RESUME_INTERRUPTED, null, RESUME_PROMPT_REQUEST))
            .build()
        notify(RESUME_PROMPT_ID, n)
    }

    fun cancelResumePrompt() = nm.cancel(RESUME_PROMPT_ID)

    // ---- Android's time limit, and the schedule ----

    /**
     * Android ended the background time of the download service and XDM paused [count] downloads. Resume restarts the
     * service (which resumes exactly these); a tap opens the app, which resumes them as well.
     */
    fun showSystemPaused(count: Int) {
        val title = if (count == 1) "Android paused 1 download (background time limit)" else "Android paused $count downloads (background time limit)"
        val n = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle(title)
            .setContentText("Open XDM or tap Resume to continue where they stopped")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "Resume", action(DownloadService.ACTION_RESUME_SYSTEM_PAUSED, null, SYSTEM_PAUSED_REQUEST))
            .build()
        notify(SYSTEM_PAUSED_ID, n)
    }

    /** The schedule window opened (or the charger was plugged in) but the system would not let XDM start the service. */
    fun showScheduledReady(count: Int) {
        val text = if (count == 1) "1 download is waiting to start" else "$count downloads are waiting to start"
        val n = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_pause)
            .setContentTitle("Scheduled downloads are ready")
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "Resume", action(DownloadService.ACTION_RESUME_INTERRUPTED, null, SCHEDULED_READY_REQUEST))
            .build()
        notify(SCHEDULED_READY_ID, n)
    }

    /** Both notices above are obsolete once the service is up. */
    fun cancelAttention() {
        nm.cancel(SYSTEM_PAUSED_ID)
        nm.cancel(SCHEDULED_READY_ID)
    }

    /** Opens the app on a download whose link expired, which starts the "refresh link" flow in the browser. */
    private fun refreshLink(id: Long): PendingIntent = PendingIntent.getActivity(
        context, requestCode(id, 3),
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_REFRESH_LINK)
            .putExtra(DownloadService.EXTRA_ID, id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ---- results (DownloadNotifier) ----

    override fun onCompleted(download: DownloadEntity) {
        cancel(download.id)
        val location = download.savedLocation
        val open = if (download.isPrivate) openPrivate() else location?.let {
            val uri = fileSaver.uriFor(it)
            val mime = MimeTypes.forFileName(download.fileName) ?: download.mimeType ?: "*/*"
            PendingIntent.getActivity(
                context, requestCode(download.id, 3),
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } ?: openApp()
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle(NotificationText.title(download))
            .setContentText(NotificationText.completed(download))
            .setContentIntent(open)
            .privacy(download)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        notify(doneId(download.id), n)
    }

    override fun onFailed(download: DownloadEntity) {
        cancel(download.id)
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(NotificationText.title(download))
            .setContentText(NotificationText.failed(download))
            .setContentIntent(if (download.isPrivate) openPrivate() else openApp())
            .privacy(download)
            .setAutoCancel(true)
            .addAction(0, "Retry", action(DownloadService.ACTION_RESUME, download.id, requestCode(download.id, 1)))
            .apply { if (LinkRefresh.offerRefresh(download)) addAction(0, "Refresh link", refreshLink(download.id)) }
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        notify(doneId(download.id), n)
    }

    // ---- automatic link refresh (RefreshNotifier) ----

    override fun refreshed(download: DownloadEntity) {
        val n = NotificationCompat.Builder(context, CHANNEL_REFRESHED)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Link refreshed, download resumed: ${download.fileName}")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        // Replaces the failure notice of the same download, if one is still showing.
        notify(doneId(download.id), n)
    }

    override fun couldNotRefresh(download: DownloadEntity) {
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle("Couldn't refresh the link for ${download.fileName}")
            .setContentText(NotificationText.failed(download))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .addAction(0, "Open page", refreshLink(download.id))
            .addAction(0, "Retry", action(DownloadService.ACTION_RESUME, download.id, requestCode(download.id, 1)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        notify(doneId(download.id), n)
    }

    override fun failed(download: DownloadEntity) = onFailed(download)

    companion object {
        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_REFRESHED = "refreshed_links"
        const val CHANNEL_DONE = "completed"
        const val CHANNEL_RESUME = "resume_after_restart"
        const val CHANNEL_ATTENTION = "paused_scheduled"
        const val GROUP = "xdm_downloads"
        const val SUMMARY_ID = 1
        const val RESUME_PROMPT_ID = 2
        const val SYSTEM_PAUSED_ID = 3
        const val SCHEDULED_READY_ID = 4
        // Per-download request codes are id * 4 + 0..3 (ids start at 1), so -1 never collides.
        private const val RESUME_PROMPT_REQUEST = -1
        private const val PRIVATE_REQUEST = -2
        private const val SYSTEM_PAUSED_REQUEST = -3
        private const val SCHEDULED_READY_REQUEST = -4
        fun progressId(id: Long) = 10_000 + (id % 1_000_000).toInt()
        fun doneId(id: Long) = 2_000_000 + (id % 1_000_000).toInt()
    }
}
