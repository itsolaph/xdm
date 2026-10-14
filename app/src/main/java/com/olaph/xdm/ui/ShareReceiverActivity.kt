package com.olaph.xdm.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.olaph.xdm.youtube.YoutubeUrls
import com.olaph.xdm.XdmApp
import com.olaph.xdm.core.PendingDownload
import com.olaph.xdm.data.AppSettings
import com.olaph.xdm.engine.UrlUtils

/**
 * Transparent entry point for "Share → XDM" and "Open with → XDM": shows only the add sheet on top of
 * the calling app, so the user never has to open the full UI.
 */
class ShareReceiverActivity : ComponentActivity() {
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = when (intent?.action) {
            Intent.ACTION_SEND -> UrlUtils.extractFirstUrl(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { UrlUtils.isHttpUrl(it) }
            else -> null
        }
        if (url == null) {
            Toast.makeText(this, "No download link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (savedInstanceState == null) requestRuntimePermissions(this, permissions::launch)
        val container = (application as XdmApp).container
        container.releaseQueue()

        // A shared YouTube link (the app's Share button, youtu.be, shorts, a playlist) gets the YouTube sheet.
        val youtube = YoutubeUrls.parse(url)

        setContent {
            val settings by produceState<AppSettings?>(initialValue = null) { value = container.settings.current() }
            var link by remember { mutableStateOf(youtube) }
            settings?.let { s ->
                XdmTheme(s.theme) {
                    val current = link
                    if (current != null) {
                        YoutubeSheet(
                            container = container,
                            link = current,
                            defaultQuality = s.youtubeQuality,
                            connections = s.connections,
                            onDismiss = { finish() },
                            onDone = {
                                Toast.makeText(this@ShareReceiverActivity, it, Toast.LENGTH_SHORT).show()
                                finish()
                            },
                        )
                    } else {
                        AddDownloadSheet(
                            container = container,
                            prefill = PendingDownload(url),
                            defaultConnections = s.connections,
                            onDismiss = { finish() },
                            onAdded = {
                                Toast.makeText(this@ShareReceiverActivity, "Download added", Toast.LENGTH_SHORT).show()
                                finish()
                            },
                            onYoutube = { link = it },
                        )
                    }
                }
            }
        }
    }
}
