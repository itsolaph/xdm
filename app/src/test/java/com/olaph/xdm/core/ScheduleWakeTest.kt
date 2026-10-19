package com.olaph.xdm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class ScheduleWakeTest {
    private val utc = ZoneOffset.UTC
    private val plan = SchedulePlan(true, 23 * 60, 7 * 60, false)

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = utc) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun beforeTodaysStartTheNextWakeIsToday() {
        assertEquals(millis(2026, 3, 10, 23), ScheduleWake.nextStart(millis(2026, 3, 10, 12), utc, plan))
    }

    @Test fun afterTodaysStartTheNextWakeIsTomorrow() {
        assertEquals(millis(2026, 3, 11, 23), ScheduleWake.nextStart(millis(2026, 3, 10, 23, 30), utc, plan))
    }

    @Test fun exactlyAtTheStartTheNextWakeIsTomorrow() {
        assertEquals(millis(2026, 3, 11, 23), ScheduleWake.nextStart(millis(2026, 3, 10, 23), utc, plan))
    }

    @Test fun overNightInsideTheWindowTheNextWakeIsTheComingEvening() {
        assertEquals(millis(2026, 3, 10, 23), ScheduleWake.nextStart(millis(2026, 3, 10, 2), utc, plan))
    }

    @Test fun monthAndYearRollOver() {
        assertEquals(millis(2027, 1, 1, 23), ScheduleWake.nextStart(millis(2026, 12, 31, 23, 59), utc, plan))
    }

    @Test fun itFollowsTheLocalTimeOfTheZone() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(millis(2026, 1, 15, 23, zone = berlin), ScheduleWake.nextStart(millis(2026, 1, 15, 20, zone = berlin), berlin, plan))
    }

    @Test fun nothingToWakeForWhenOffOrAlwaysOpen() {
        assertNull(ScheduleWake.nextStart(0, utc, plan.copy(enabled = false)))
        assertNull(ScheduleWake.nextStart(0, utc, plan.copy(endMinute = plan.startMinute)))
    }
}
