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
import com.olaph.xdm.core.LiveProgress
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.MimeTypes
import com.olaph.xdm.ui.MainActivity

class NotificationHelper(
    private val context: Context,
    private val fileSaver: FileSaver,
) : DownloadNotifier {

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

    private fun action(action: String, id: Long?, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(action)
        if (id != null) intent.putExtra(DownloadService.EXTRA_ID, id)
        return PendingIntent.getForegroundService(
            context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun requestCode(id: Long, salt: Int) = (id.toInt() * 4 + salt)

    // ---- foreground summary ----

    fun summary(active: Int, queued: Int, bytesPerSecond: Long, waitingForWifi: Boolean): Notification {
        val text = when {
            waitingForWifi -> "Waiting for Wi-Fi"
            active == 0 && queued > 0 -> "$queued queued"
            else -> buildString {
                append("$active downloading")
                if (queued > 0) append(" · $queued queued")
                if (bytesPerSecond > 0) append(" · ${Formatters.speed(bytesPerSecond)}")
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
            .setContentTitle(d.fileName)
            .setContentIntent(openApp())
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

    // ---- results (DownloadNotifier) ----

    override fun onCompleted(download: DownloadEntity) {
        cancel(download.id)
        val location = download.savedLocation
        val open = location?.let {
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
            .setContentTitle(download.fileName)
            .setContentText("Download complete · ${Formatters.bytes(download.totalBytes)}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        notify(doneId(download.id), n)
    }

    override fun onFailed(download: DownloadEntity) {
        cancel(download.id)
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(download.fileName)
            .setContentText("Download failed" + (download.error?.let { ": $it" } ?: ""))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .addAction(0, "Retry", action(DownloadService.ACTION_RESUME, download.id, requestCode(download.id, 1)))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        notify(doneId(download.id), n)
    }

    companion object {
        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_DONE = "completed"
        const val GROUP = "xdm_downloads"
        const val SUMMARY_ID = 1
        fun progressId(id: Long) = 10_000 + (id % 1_000_000).toInt()
        fun doneId(id: Long) = 2_000_000 + (id % 1_000_000).toInt()
    }
}
