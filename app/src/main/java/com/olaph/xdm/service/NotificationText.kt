package com.olaph.xdm.service

import com.olaph.xdm.core.Formatters
import com.olaph.xdm.data.DownloadEntity

/** Notification wording. A private download never shows its name, error text (it may quote a link) or a preview. */
object NotificationText {
    const val PRIVATE_TITLE = "Private download"

    fun title(d: DownloadEntity): String = if (d.isPrivate) PRIVATE_TITLE else d.fileName

    fun completed(d: DownloadEntity): String =
        if (d.isPrivate) "Download complete · in the private folder" else "Download complete · ${Formatters.bytes(d.totalBytes)}"

    fun failed(d: DownloadEntity): String =
        if (d.isPrivate) "Download failed" else "Download failed" + (d.error?.let { ": $it" } ?: "")
}
