package com.olaph.xdm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olaph.xdm.core.BatchCheck
import com.olaph.xdm.core.DuplicateCheck
import com.olaph.xdm.core.DuplicateMatch
import com.olaph.xdm.core.DuplicateReason

/**
 * One download that looks like one already in the list. A paused or failed match gets "Resume existing" as the main
 * button, any other "Open existing"; "Download again" adds it anyway (with a unique name, like any other).
 */
@Composable
fun DuplicateDialog(
    match: DuplicateMatch,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onAddAgain: () -> Unit,
    onCancel: () -> Unit,
) {
    val existing = match.existing
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("This file may already be in your list") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(existing.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(DuplicateCheck.describe(existing), style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (match.reason) {
                        DuplicateReason.SAME_LINK -> "Same link"
                        DuplicateReason.SAME_NAME -> "Same file name in the same folder"
                        DuplicateReason.SAME_SIZE -> "Same size and similar name"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                if (match.resumable) {
                    Button(onClick = onResume) { Text("Resume existing") }
                    TextButton(onClick = onOpen) { Text("Open existing") }
                } else {
                    Button(onClick = onOpen) { Text("Open existing") }
                }
                TextButton(onClick = onAddAgain) { Text("Download again") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

/** Several downloads at once (a playlist), some of them already in the list: skip those, or add everything. */
@Composable
fun BatchDuplicateDialog(
    check: BatchCheck<*>,
    onSkip: () -> Unit,
    onAddAll: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(check.summary.orEmpty()) },
        text = {
            Text(
                if (check.duplicates == check.total) "Skipping them adds nothing." else "Skip them, or add all of them again?",
                modifier = Modifier.padding(top = 4.dp),
            )
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Button(onClick = onSkip) { Text("Skip duplicates") }
                TextButton(onClick = onAddAll) { Text("Add all") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}
