package com.olaph.xdm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.olaph.xdm.XdmApp
import com.olaph.xdm.core.RestartAction
import com.olaph.xdm.core.RestartResume
import com.olaph.xdm.core.RestartTrigger
import com.olaph.xdm.core.RunGate
import com.olaph.xdm.data.DownloadStatus
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * After a reboot or an app update, puts downloads that were running or queued back to work (see [RestartResume]).
 * The app's start already moved RUNNING rows back to QUEUED and holds the queue; this decides how it gets released.
 *
 * It also re-arms the schedule's alarm: alarms do not survive a reboot or an update, and a changed clock, time zone or
 * exact-alarm access moves or cancels the next one.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> RestartTrigger.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> RestartTrigger.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, ACTION_EXACT_ALARM_ACCESS_CHANGED -> null
            else -> return
        }
        val app = context.applicationContext as XdmApp
        val c = app.container
        val pending = goAsync()
        c.scope.launch {
            try {
                val s = c.settings.current()
                ScheduleAlarm.reschedule(app, s.schedule)
                if (trigger == null) return@launch
                // Waiting for the schedule's window is not an interruption: the alarm set above starts them in time.
                val held = RunGate.holdFor(LocalTime.now(), c.power.charging.value, false, false, s.schedule, forceStart = false)
                val rows = if (held != null) emptyList() else c.dao.getByStatus(DownloadStatus.RUNNING) + c.dao.getByStatus(DownloadStatus.QUEUED)
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

    companion object {
        /** `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, sent on Android 12+. */
        const val ACTION_EXACT_ALARM_ACCESS_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
