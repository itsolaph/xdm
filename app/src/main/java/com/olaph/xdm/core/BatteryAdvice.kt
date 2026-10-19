package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus

/** When to suggest exempting XDM from battery optimisation, and which phones need the extra autostart screen. */
object BatteryAdvice {
    /** A download this large is worth keeping alive in the background. */
    const val LARGE_DOWNLOAD_BYTES = 200L * 1024 * 1024

    /** A queue longer than this (running + waiting) is as well. */
    const val LONG_QUEUE = 3

    /**
     * Show the card once there is something worth protecting: an unfinished download above [LARGE_DOWNLOAD_BYTES], or
     * more than [LONG_QUEUE] downloads running or queued. Never when already exempt or dismissed.
     */
    fun shouldShowCard(exempt: Boolean, dismissed: Boolean, downloads: List<DownloadEntity>): Boolean {
        if (exempt || dismissed) return false
        val open = downloads.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED
        }
        if (open.any { it.totalBytes > LARGE_DOWNLOAD_BYTES }) return true
        return open.count { it.status != DownloadStatus.PAUSED } > LONG_QUEUE
    }

    /** Xiaomi's brands (MIUI / HyperOS) keep their own "Autostart" list on top of the battery settings. */
    fun isXiaomiFamily(manufacturer: String?, brand: String?): Boolean {
        val names = setOf("xiaomi", "redmi", "poco")
        return manufacturer?.trim()?.lowercase() in names || brand?.trim()?.lowercase() in names
    }
}
