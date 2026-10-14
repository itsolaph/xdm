package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RestartResumeTest {

    private fun row(id: Long, status: DownloadStatus) = DownloadEntity(id = id, url = "https://x.test/$id", fileName = "$id.bin", status = status)

    private val mixed = listOf(
        row(1, DownloadStatus.RUNNING),
        row(2, DownloadStatus.QUEUED),
        row(3, DownloadStatus.PAUSED),
        row(4, DownloadStatus.COMPLETED),
        row(5, DownloadStatus.FAILED),
        row(6, DownloadStatus.CANCELLED),
        row(7, DownloadStatus.RUNNING),
    )

    private fun decide(
        downloads: List<DownloadEntity> = mixed,
        enabled: Boolean = true,
        trigger: RestartTrigger = RestartTrigger.BOOT,
        sdk: Int = 34,
        wifiOnly: Boolean = false,
        metered: Boolean = false,
    ) = RestartResume.decide(downloads, enabled, trigger, sdk, wifiOnly, metered)

    @Test fun onlyRunningAndQueuedDownloadsAreResumed() {
        assertEquals(listOf(1L, 2L, 7L), RestartResume.interrupted(mixed).map { it.id })
    }

    @Test fun beforeAndroid15TheServiceStartsStraightAfterBoot() {
        assertEquals(RestartAction.StartService(3), decide(sdk = 26))
        assertEquals(RestartAction.StartService(3), decide(sdk = 34))
    }

    @Test fun fromAndroid15BootOnlyPostsTheNotification() {
        assertEquals(RestartAction.AskUser(3, waitingForWifi = false), decide(sdk = 35))
        assertEquals(RestartAction.AskUser(3, waitingForWifi = false), decide(sdk = 36))
    }

    @Test fun anAppUpdateMayStartTheServiceOnEveryVersion() {
        assertEquals(RestartAction.StartService(3), decide(trigger = RestartTrigger.PACKAGE_REPLACED, sdk = 35))
        assertEquals(RestartAction.StartService(3), decide(trigger = RestartTrigger.PACKAGE_REPLACED, sdk = 30))
    }

    @Test fun switchedOffOrNothingInterruptedDoesNothing() {
        assertEquals(RestartAction.None, decide(enabled = false))
        assertEquals(RestartAction.None, decide(enabled = false, sdk = 35))
        assertEquals(RestartAction.None, decide(downloads = mixed.filter { it.status == DownloadStatus.PAUSED }))
        assertEquals(RestartAction.None, decide(downloads = emptyList(), sdk = 35))
    }

    @Test fun wifiOnlyOnAMeteredNetworkIsMentioned() {
        assertEquals(RestartAction.AskUser(3, waitingForWifi = true), decide(sdk = 35, wifiOnly = true, metered = true))
        assertEquals(RestartAction.AskUser(3, waitingForWifi = false), decide(sdk = 35, wifiOnly = true, metered = false))
        assertEquals(RestartAction.AskUser(3, waitingForWifi = false), decide(sdk = 35, wifiOnly = false, metered = true))
        // The service itself waits for Wi-Fi (the queue checks the setting), so it is started all the same.
        assertEquals(RestartAction.StartService(3), decide(sdk = 34, wifiOnly = true, metered = true))
    }
}
