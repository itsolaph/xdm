package com.olaph.xdm.core

import java.time.Instant
import java.time.ZoneId

/** When the app has to wake up for the schedule. Plain function, tested on the JVM; the alarm code only calls it. */
object ScheduleWake {
    /**
     * Epoch milliseconds of the next moment the window opens after [nowMillis] in [zone], or null when there is nothing to
     * wake up for (schedule off, or a window that is always open). The window closing needs no wake-up: it only matters
     * while downloads run, and then the process is alive.
     */
    fun nextStart(nowMillis: Long, zone: ZoneId, plan: SchedulePlan): Long? {
        if (!plan.enabled || plan.startMinute == plan.endMinute) return null
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        fun at(date: java.time.LocalDate) = date.atTime(plan.startMinute / 60, plan.startMinute % 60).atZone(zone)
        var start = at(now.toLocalDate())
        if (!start.isAfter(now)) start = at(now.toLocalDate().plusDays(1))
        return start.toInstant().toEpochMilli()
    }
}
