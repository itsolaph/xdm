package com.olaph.xdm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.olaph.xdm.XdmApp
import com.olaph.xdm.core.RestartAction
import com.olaph.xdm.core.RestartResume
import com.olaph.xdm.core.RestartTrigger
import com.olaph.xdm.data.DownloadStatus
import kotlinx.coroutines.launch

/**
 * After a reboot or an app update, puts downloads that were running or queued back to work (see [RestartResume]).
 * The app's start already moved RUNNING rows back to QUEUED and holds the queue; this decides how it gets released.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> RestartTrigger.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> RestartTrigger.PACKAGE_REPLACED
            else -> return
        }
        val app = context.applicationContext as XdmApp
        val c = app.container
        val pending = goAsync()
        c.scope.launch {
            try {
                val s = c.settings.current()
                val rows = c.dao.getByStatus(DownloadStatus.RUNNING) + c.dao.getByStatus(DownloadStatus.QUEUED)
                val action = RestartResume.decide(rows, s.resumeAfterRestart, trigger, Build.VERSION.SDK_INT, s.wifiOnly, c.network.metered.value)
                when (action) {
                    is RestartAction.StartService ->
                        if (!DownloadService.start(app)) c.notifications.showResumePrompt(action.count, waitingForWifi = false)
                    is RestartAction.AskUser -> c.notifications.showResumePrompt(action.count, action.waitingForWifi)
                    RestartAction.None -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }
}
