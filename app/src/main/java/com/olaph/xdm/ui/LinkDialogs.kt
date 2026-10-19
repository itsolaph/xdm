package com.olaph.xdm.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.engine.LinkMatch
import com.olaph.xdm.engine.UrlUtils

/** "Change URL…": paste a new link for a download that has not finished. */
@Composable
fun ChangeUrlDialog(download: DownloadEntity, onDismiss: () -> Unit, onCheck: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    val trimmed = url.trim()
    val valid = UrlUtils.isHttpUrl(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change URL") },
        text = {
            Column {
                Text(download.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    "The new link must lead to the same file. XDM checks its size first and keeps what is already downloaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("New link") },
                    maxLines = 3,
                    isError = url.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            url = UrlUtils.extractFirstUrl(text) ?: text.trim()
                        }) { Icon(Icons.Filled.ContentPaste, "Paste") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCheck(trimmed) }, enabled = valid) { Text("Check link") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The answer to a replacement link: use it, start over with it, or why not. */
@Composable
fun ReplacementDialog(offer: ReplacementOffer, onConfirm: (restart: Boolean) -> Unit, onDismiss: () -> Unit) {
    val check = offer.check
    val verdict = check?.verdict
    val title = when {
        check == null && offer.error == null -> "Checking the link…"
        offer.error != null -> "The link didn't work"
        verdict is LinkMatch.Verdict.Same -> "Use this link?"
        verdict is LinkMatch.Verdict.Different -> "That's a different file"
        else -> "Start over with this link?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(offer.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Spacer(Modifier.size(8.dp))
                when {
                    offer.error != null -> Text(offer.error, style = MaterialTheme.typography.bodyMedium)
                    check == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Asking the server…")
                    }
                    verdict is LinkMatch.Verdict.Same -> Text(
                        "It is the same size, so the download continues from the part that is already saved.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    verdict is LinkMatch.Verdict.Different -> Text(
                        "The new link is ${Formatters.bytes(verdict.newSize)}, the download is ${Formatters.bytes(verdict.oldSize)}. " +
                            "The link was not used.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    verdict is LinkMatch.Verdict.RestartOnly -> Text(
                        when (verdict.reason) {
                            LinkMatch.Reason.ETAG_CHANGED ->
                                "The server says this file has changed. Mixing it with the part already saved could corrupt it, so the download would start from zero."
                            LinkMatch.Reason.SIZE_UNKNOWN ->
                                "The server doesn't tell how big the file is, so XDM can't check that it is the same one. The download would start from zero."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            when (verdict) {
                is LinkMatch.Verdict.Same -> TextButton(onClick = { onConfirm(false) }) { Text("Replace link") }
                is LinkMatch.Verdict.RestartOnly -> TextButton(onClick = { onConfirm(true) }) { Text("Restart from zero") }
                else -> if (check != null || offer.error != null) TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (verdict is LinkMatch.Verdict.Same || verdict is LinkMatch.Verdict.RestartOnly || check == null && offer.error == null) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
