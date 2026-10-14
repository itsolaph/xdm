package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus

/** What woke the app up after its process was gone. */
enum class RestartTrigger { BOOT, PACKAGE_REPLACED }

sealed interface RestartAction {
    data object None : RestartAction

    /** Start the download service right away; the queue picks the downloads up. */
    data class StartService(val count: Int) : RestartAction

    /** Post a "ready to resume" notification; its Resume button (or opening the app) starts them. */
    data class AskUser(val count: Int, val waitingForWifi: Boolean) : RestartAction
}

/**
 * Decides what happens to interrupted downloads after a reboot or an app update. Pure, so it is tested on the JVM;
 * the receiver only feeds it the database rows and the device state.
 */
object RestartResume {
    /** Android 15: a BOOT_COMPLETED receiver may no longer start a dataSync foreground service. */
    const val BOOT_FGS_RESTRICTED_SDK = 35

    /** Downloads that were running or waiting in the queue; paused, failed and finished ones are left alone. */
    fun interrupted(downloads: List<DownloadEntity>): List<DownloadEntity> =
        downloads.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }

    fun decide(
        downloads: List<DownloadEntity>,
        enabled: Boolean,
        trigger: RestartTrigger,
        sdkInt: Int,
        wifiOnly: Boolean,
        metered: Boolean,
    ): RestartAction {
        if (!enabled) return RestartAction.None
        val count = interrupted(downloads).size
        if (count == 0) return RestartAction.None
        // MY_PACKAGE_REPLACED is still allowed to start the service on Android 15+.
        return if (trigger == RestartTrigger.BOOT && sdkInt >= BOOT_FGS_RESTRICTED_SDK) {
            RestartAction.AskUser(count, waitingForWifi = wifiOnly && metered)
        } else {
            RestartAction.StartService(count)
        }
    }
}
