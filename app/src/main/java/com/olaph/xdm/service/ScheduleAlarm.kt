package com.olaph.xdm.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.olaph.xdm.XdmApp
import com.olaph.xdm.core.ScheduleWake
import com.olaph.xdm.core.SchedulePlan
import com.olaph.xdm.data.DownloadStatus
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Wakes the app when the schedule window opens. An exact alarm is the one wake-up Android lets start a foreground service
 * from the background (Android 12+); without the "Alarms & reminders" access an inexact alarm still fires, and the receiver
 * then falls back to a notification when the system refuses the service.
 */
object ScheduleAlarm {
    const val ACTION = "com.olaph.xdm.SCHEDULE_START"

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, ScheduleReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching { context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false)

    /** Replaces the pending alarm by one for the next window start (or removes it when the plan has none). */
    fun reschedule(context: Context, plan: SchedulePlan, nowMillis: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        alarms.cancel(pending)
        val at = ScheduleWake.nextStart(nowMillis, ZoneId.systemDefault(), plan) ?: return
        try {
            if (canScheduleExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (_: SecurityException) {
            // The exact-alarm access was taken away between the check and the call.
            runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        }
    }
}

/** The window opened: start the service for what waits, or say so in a notification when the system does not allow it. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ScheduleAlarm.ACTION) return
        val app = context.applicationContext as XdmApp
        val c = app.container
        val pending = goAsync()
        c.scope.launch {
            try {
                val s = c.settings.current()
                ScheduleAlarm.reschedule(app, s.schedule) // tomorrow's
                val waiting = c.dao.getByStatus(DownloadStatus.QUEUED).size
                // Without the charger nothing would start anyway; plugging it in is handled while the app lives.
                val mustCharge = s.scheduleOnlyCharging && !c.power.charging.value
                if (s.scheduleEnabled && waiting > 0 && !mustCharge && !DownloadService.start(app)) {
                    c.notifications.showScheduledReady(waiting)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
