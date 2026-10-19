package com.olaph.xdm.ui

import com.olaph.xdm.core.Hold
import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.PauseNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusLinesTest {
    private fun line(status: DownloadStatus, hold: Hold? = null, startsAt: String? = null, error: String? = null) =
        statusLine(
            DownloadItem(DownloadEntity(url = "https://x.test/f", fileName = "f", status = status, error = error, acceptRanges = true, totalBytes = 10), null),
            QueueStatus(hold, startsAt),
        )

    @Test fun aQueuedItemSaysWhatItWaitsFor() {
        assertEquals("Queued", line(DownloadStatus.QUEUED))
        assertEquals("Waiting for Wi-Fi", line(DownloadStatus.QUEUED, Hold.WIFI))
        assertEquals("Scheduled · starts at 23:00", line(DownloadStatus.QUEUED, Hold.SCHEDULE, "23:00"))
        assertEquals("Waiting for the charger", line(DownloadStatus.QUEUED, Hold.CHARGING))
    }

    @Test fun aPauseByAndroidShowsItsReasonAndAUserPauseDoesNot() {
        assertEquals("Paused", line(DownloadStatus.PAUSED))
        assertEquals("Paused by Android (background time limit)", line(DownloadStatus.PAUSED, error = PauseNote.SYSTEM_LIMIT))
    }

    @Test fun theHeaderShowsTheLimitAndTheSchedule() {
        val none = QueueStatus(null, null)
        assertNull(limitsLine(AppSettings(), none))
        assertEquals("Speed limit 256 KB/s", limitsLine(AppSettings(speedLimitBytesPerSec = 256 * 1024), none))
        val night = AppSettings(scheduleEnabled = true, scheduleStartMinute = 23 * 60, scheduleEndMinute = 7 * 60)
        assertEquals("Schedule 23:00–07:00", limitsLine(night, none))
        assertEquals("Scheduled: starts at 23:00", limitsLine(night, QueueStatus(Hold.SCHEDULE, "23:00")))
        assertEquals(
            "Speed limit 2.0 MB/s · Scheduled: starts at 22:30",
            limitsLine(night.copy(scheduleStartMinute = 22 * 60 + 30, speedLimitBytesPerSec = 2L * 1024 * 1024), QueueStatus(Hold.SCHEDULE, "22:30")),
        )
    }

    @Test fun anAlwaysOpenWindowShowsOnlyTheChargerRule() {
        val s = AppSettings(scheduleEnabled = true, scheduleStartMinute = 0, scheduleEndMinute = 0, scheduleOnlyCharging = true)
        assertEquals("Only while charging", limitsLine(s, QueueStatus(null, null)))
        assertNull(limitsLine(s.copy(scheduleOnlyCharging = false), QueueStatus(null, null)))
    }

    @Test fun aDisabledScheduleIsNotMentioned() {
        assertNull(limitsLine(AppSettings(scheduleEnabled = false, scheduleStartMinute = 5, scheduleEndMinute = 9), QueueStatus(null, null)))
    }
}
