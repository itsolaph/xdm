package com.olaph.xdm.core

import java.time.LocalTime

/**
 * The daily schedule from Settings. Times are minutes since local midnight; a window may cross midnight, and
 * start == end means "always".
 */
data class SchedulePlan(
    val enabled: Boolean = false,
    val startMinute: Int = 23 * 60,
    val endMinute: Int = 7 * 60,
    val onlyWhileCharging: Boolean = false,
) {
    /** True when the plan can ever hold a download back. */
    val restricts: Boolean get() = enabled && (startMinute != endMinute || onlyWhileCharging)
}

/** Why a queued download is not allowed to start (or keep running) right now. */
enum class Hold { WIFI, SCHEDULE, CHARGING }

/**
 * "May this download run now?" in one plain function, so the rules are tested on the JVM and the manager only feeds in
 * the clock and the device state.
 */
object RunGate {
    private const val DAY_SECONDS = 24 * 3600L

    fun inWindow(minuteOfDay: Int, startMinute: Int, endMinute: Int): Boolean = when {
        startMinute == endMinute -> true
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    /**
     * What holds a download back, or null when it may run. "Wi-Fi only" binds everything, including an item the user
     * started with "Start now"; the schedule (window and charger) is overridden by [forceStart].
     */
    fun holdFor(
        now: LocalTime,
        charging: Boolean,
        wifiOnly: Boolean,
        metered: Boolean,
        plan: SchedulePlan,
        forceStart: Boolean,
    ): Hold? {
        if (wifiOnly && metered) return Hold.WIFI
        if (!plan.enabled || forceStart) return null
        if (!inWindow(now.hour * 60 + now.minute, plan.startMinute, plan.endMinute)) return Hold.SCHEDULE
        if (plan.onlyWhileCharging && !charging) return Hold.CHARGING
        return null
    }

    fun mayRun(now: LocalTime, charging: Boolean, wifiOnly: Boolean, metered: Boolean, plan: SchedulePlan, forceStart: Boolean): Boolean =
        holdFor(now, charging, wifiOnly, metered, plan, forceStart) == null

    /** Seconds until the window opens or closes next, or null when the clock alone never changes the answer. */
    fun secondsUntilChange(now: LocalTime, plan: SchedulePlan): Long? {
        if (!plan.enabled || plan.startMinute == plan.endMinute) return null
        val target = if (inWindow(now.hour * 60 + now.minute, plan.startMinute, plan.endMinute)) plan.endMinute else plan.startMinute
        val diff = target * 60L - now.toSecondOfDay()
        return if (diff > 0) diff else diff + DAY_SECONDS
    }

    /** "07:30" */
    fun clock(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}
