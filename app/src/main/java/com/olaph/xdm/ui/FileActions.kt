package com.olaph.xdm.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.olaph.xdm.AppContainer
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.engine.MimeTypes

object FileActions {
    private fun mimeOf(d: DownloadEntity): String =
        MimeTypes.forFileName(d.fileName) ?: d.mimeType?.takeIf { it != MimeTypes.OCTET_STREAM } ?: "*/*"

    fun open(context: Context, container: AppContainer, d: DownloadEntity, chooser: Boolean = false) {
        val location = d.savedLocation
        if (location == null || !container.fileSaver.exists(location)) {
            Toast.makeText(context, "File not found (moved or deleted)", Toast.LENGTH_SHORT).show()
            return
        }
        val mime = mimeOf(d)
        if (mime == MimeTypes.APK && !context.packageManager.canRequestPackageInstalls()) {
            // Android asks once per source app. Send the user to XDM's "Install unknown apps" switch;
            // tapping Open again afterwards goes straight to the installer.
            Toast.makeText(context, "Allow XDM to install apps, then tap Open again", Toast.LENGTH_LONG).show()
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(settings)
                return
            } catch (_: ActivityNotFoundException) {
                // some ROMs hide the per-app screen; let the installer explain instead
            }
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(container.fileSaver.uriFor(location), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(if (chooser) Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    /** "Open with…": always asks which app, instead of using the default one. */
    fun openWith(context: Context, container: AppContainer, d: DownloadEntity) = open(context, container, d, chooser = true)

    fun share(context: Context, container: AppContainer, d: DownloadEntity) {
        val location = d.savedLocation
        if (location == null || !container.fileSaver.exists(location)) {
            Toast.makeText(context, "File not found (moved or deleted)", Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(d))
            .putExtra(Intent.EXTRA_STREAM, container.fileSaver.uriFor(location))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${d.fileName}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Shares every completed file in [items] that still exists; returns how many were offered. */
    fun shareMany(context: Context, container: AppContainer, items: List<DownloadEntity>): Int {
        val uris = items.mapNotNull { d ->
            d.savedLocation?.takeIf { container.fileSaver.exists(it) }?.let { container.fileSaver.uriFor(it) }
        }
        if (uris.isEmpty()) {
            Toast.makeText(context, "Nothing to share (files moved or deleted)", Toast.LENGTH_SHORT).show()
            return 0
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${uris.size} files").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return uris.size
    }

    fun copyLink(context: Context, url: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }
}
