package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAdviceTest {
    private val mb = 1024L * 1024

    private fun d(status: DownloadStatus, size: Long = 10 * mb) =
        DownloadEntity(url = "https://x.test/f", fileName = "f", totalBytes = size, status = status)

    private fun show(items: List<DownloadEntity>, exempt: Boolean = false, dismissed: Boolean = false) =
        BatteryAdvice.shouldShowCard(exempt, dismissed, items)

    @Test fun nothingToProtectNothingToShow() {
        assertFalse(show(emptyList()))
        assertFalse(show(listOf(d(DownloadStatus.RUNNING))))
    }

    @Test fun aLargeUnfinishedDownloadTriggersIt() {
        assertTrue(show(listOf(d(DownloadStatus.RUNNING, 201 * mb))))
        assertTrue(show(listOf(d(DownloadStatus.QUEUED, 2000 * mb))))
        assertTrue(show(listOf(d(DownloadStatus.PAUSED, 500 * mb))))
    }

    @Test fun exactlyTheLimitIsNotLarge() {
        assertFalse(show(listOf(d(DownloadStatus.RUNNING, 200 * mb))))
    }

    @Test fun aFinishedOrFailedLargeDownloadDoesNot() {
        assertFalse(show(listOf(d(DownloadStatus.COMPLETED, 900 * mb), d(DownloadStatus.FAILED, 900 * mb), d(DownloadStatus.CANCELLED, 900 * mb))))
    }

    @Test fun moreThanThreeActiveDownloadsTriggerIt() {
        assertFalse(show(List(3) { d(DownloadStatus.QUEUED) }))
        assertTrue(show(List(4) { d(DownloadStatus.QUEUED) }))
        assertTrue(show(List(2) { d(DownloadStatus.RUNNING) } + List(2) { d(DownloadStatus.QUEUED) }))
    }

    @Test fun pausedOnesDoNotCountTowardsTheQueue() {
        assertFalse(show(List(6) { d(DownloadStatus.PAUSED) }))
    }

    @Test fun neverWhenExemptOrDismissed() {
        val big = listOf(d(DownloadStatus.RUNNING, 900 * mb))
        assertFalse(show(big, exempt = true))
        assertFalse(show(big, dismissed = true))
    }

    @Test fun xiaomiBrandsAreRecognised() {
        assertTrue(BatteryAdvice.isXiaomiFamily("Xiaomi", "Redmi"))
        assertTrue(BatteryAdvice.isXiaomiFamily("Xiaomi", "POCO"))
        assertTrue(BatteryAdvice.isXiaomiFamily("xiaomi", null))
        assertTrue(BatteryAdvice.isXiaomiFamily(" Unknown ", "redmi"))
        assertFalse(BatteryAdvice.isXiaomiFamily("samsung", "samsung"))
        assertFalse(BatteryAdvice.isXiaomiFamily(null, null))
    }
}
