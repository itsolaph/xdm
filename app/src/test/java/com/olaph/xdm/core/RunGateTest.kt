package com.olaph.xdm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class RunGateTest {
    private fun t(h: Int, m: Int = 0, s: Int = 0) = LocalTime.of(h, m, s)
    private fun plan(start: Int, end: Int, charging: Boolean = false, enabled: Boolean = true) =
        SchedulePlan(enabled, start * 60, end * 60, charging)

    private fun hold(
        now: LocalTime, plan: SchedulePlan, charging: Boolean = true, wifiOnly: Boolean = false, metered: Boolean = false, force: Boolean = false,
    ) = RunGate.holdFor(now, charging, wifiOnly, metered, plan, force)

    // ---- the window ----

    @Test fun aDaytimeWindowIncludesItsStartAndExcludesItsEnd() {
        val p = plan(9, 17)
        assertEquals(Hold.SCHEDULE, hold(t(8, 59), p))
        assertNull(hold(t(9, 0), p))
        assertNull(hold(t(12), p))
        assertNull(hold(t(16, 59), p))
        assertEquals(Hold.SCHEDULE, hold(t(17, 0), p))
        assertEquals(Hold.SCHEDULE, hold(t(23, 59), p))
        assertEquals(Hold.SCHEDULE, hold(t(0, 0), p))
    }

    @Test fun aWindowThatCrossesMidnightRunsOnBothSides() {
        val p = plan(23, 7)
        assertEquals(Hold.SCHEDULE, hold(t(22, 59), p))
        assertNull(hold(t(23, 0), p))
        assertNull(hold(t(23, 59), p))
        assertNull(hold(t(0, 0), p))
        assertNull(hold(t(6, 59), p))
        assertEquals(Hold.SCHEDULE, hold(t(7, 0), p))
        assertEquals(Hold.SCHEDULE, hold(t(12), p))
    }

    @Test fun minutesDecideNotSeconds() {
        val p = SchedulePlan(true, 9 * 60 + 30, 10 * 60, false)
        assertNull(hold(t(9, 30, 0), p))
        assertNull(hold(t(9, 59, 59), p))
        assertEquals(Hold.SCHEDULE, hold(t(10, 0, 0), p))
    }

    @Test fun equalStartAndEndMeansAlwaysOn() {
        val p = plan(8, 8)
        for (h in 0..23) assertNull("at $h:00", hold(t(h), p))
        assertNull(hold(t(23, 59, 59), p))
    }

    @Test fun aWindowOfOneMinuteWorks() {
        val p = SchedulePlan(true, 100, 101, false)
        assertEquals(Hold.SCHEDULE, hold(t(1, 39), p))
        assertNull(hold(t(1, 40), p))
        assertEquals(Hold.SCHEDULE, hold(t(1, 41), p))
    }

    @Test fun aWindowEndingAtMidnightIsClosedAtMidnight() {
        val p = SchedulePlan(true, 22 * 60, 0, false)
        assertNull(hold(t(23, 59), p))
        assertEquals(Hold.SCHEDULE, hold(t(0, 0), p))
    }

    // ---- switched off ----

    @Test fun aDisabledScheduleNeverHoldsAnything() {
        val p = plan(9, 17, charging = true, enabled = false)
        assertNull(hold(t(3), p, charging = false))
        assertFalse(p.restricts)
    }

    // ---- charging ----

    @Test fun onlyWhileChargingNeedsTheChargerInsideTheWindow() {
        val p = plan(23, 7, charging = true)
        assertNull(hold(t(2), p, charging = true))
        assertEquals(Hold.CHARGING, hold(t(2), p, charging = false))
    }

    @Test fun outsideTheWindowTheWindowIsTheReasonEvenWhenAlsoUnplugged() {
        val p = plan(23, 7, charging = true)
        assertEquals(Hold.SCHEDULE, hold(t(12), p, charging = false))
    }

    @Test fun onlyWhileChargingWithAnAlwaysOnWindowIsJustAChargerRule() {
        val p = plan(0, 0, charging = true)
        assertEquals(Hold.CHARGING, hold(t(14), p, charging = false))
        assertNull(hold(t(14), p, charging = true))
        assertTrue(p.restricts)
    }

    @Test fun anAlwaysOnWindowWithoutTheChargerRuleRestrictsNothing() {
        assertFalse(plan(5, 5).restricts)
    }

    // ---- Wi-Fi only ----

    @Test fun wifiOnlyOnAMeteredNetworkHoldsEverything() {
        val p = plan(9, 17)
        assertEquals(Hold.WIFI, hold(t(12), p, wifiOnly = true, metered = true))
        assertEquals(Hold.WIFI, hold(t(3), p, wifiOnly = true, metered = true))
        assertEquals(Hold.WIFI, hold(t(12), SchedulePlan(), wifiOnly = true, metered = true))
    }

    @Test fun wifiOnlyOnWifiOrWithTheSettingOffDoesNotHold() {
        assertNull(hold(t(12), plan(9, 17), wifiOnly = true, metered = false))
        assertNull(hold(t(12), plan(9, 17), wifiOnly = false, metered = true))
    }

    // ---- Start now ----

    @Test fun startNowSkipsTheWindowAndTheCharger() {
        val p = plan(23, 7, charging = true)
        assertNull(hold(t(12), p, charging = false, force = true))
        assertEquals(Hold.SCHEDULE, hold(t(12), p, charging = false, force = false))
    }

    @Test fun startNowDoesNotSkipWifiOnly() {
        assertEquals(Hold.WIFI, hold(t(12), plan(9, 17), wifiOnly = true, metered = true, force = true))
    }

    @Test fun mayRunIsTheSameQuestion() {
        assertTrue(RunGate.mayRun(t(12), true, false, false, plan(9, 17), false))
        assertFalse(RunGate.mayRun(t(20), true, false, false, plan(9, 17), false))
    }

    // ---- when does the answer change ----

    @Test fun secondsUntilTheWindowOpens() {
        val p = plan(9, 17)
        assertEquals(3600L, RunGate.secondsUntilChange(t(8), p))
        assertEquals(1L, RunGate.secondsUntilChange(t(8, 59, 59), p))
        // after the window: tomorrow morning
        assertEquals(15 * 3600L, RunGate.secondsUntilChange(t(18), p))
    }

    @Test fun secondsUntilTheWindowCloses() {
        val p = plan(9, 17)
        assertEquals(5 * 3600L, RunGate.secondsUntilChange(t(12), p))
        // at the closing minute the window is already shut: the next change is tomorrow's opening
        assertEquals(16 * 3600L, RunGate.secondsUntilChange(t(17), p))
    }

    @Test fun secondsUntilChangeAcrossMidnight() {
        val p = plan(23, 7)
        assertEquals(2 * 3600L, RunGate.secondsUntilChange(t(21), p)) // opens at 23:00
        assertEquals(1 * 3600L, RunGate.secondsUntilChange(t(6), p)) // closes at 07:00
        assertEquals(7 * 3600L, RunGate.secondsUntilChange(t(0), p))
    }

    @Test fun nothingChangesWithTheClockWhenOffOrAlwaysOn() {
        assertNull(RunGate.secondsUntilChange(t(12), plan(9, 17, enabled = false)))
        assertNull(RunGate.secondsUntilChange(t(12), plan(4, 4)))
    }

    @Test fun clockFormatting() {
        assertEquals("07:05", RunGate.clock(7 * 60 + 5))
        assertEquals("23:00", RunGate.clock(23 * 60))
        assertEquals("00:00", RunGate.clock(0))
    }
}
