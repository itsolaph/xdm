package com.olaph.xdm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.data.SegmentEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val waitingForWifi by vm.waitingForWifi.collectAsStateWithLifecycle()
    val segmentsFlow = remember(id) { vm.segments(id) }
    val segments by segmentsFlow.collectAsStateWithLifecycle(emptyList())
    val item = items?.firstOrNull { it.entity.id == id }
    var confirmDelete by remember { mutableStateOf(false) }

    // The item disappears after deletion: go back instead of showing an empty screen.
    val gone = item == null && items != null
    LaunchedEffect(gone) { if (gone) onBack() }
    if (item == null) return
    val d = item.entity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download details") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { RowActions(d, vm, onDelete = { confirmDelete = true }) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileTypeIcon(d.fileName, d.mimeType)
                Spacer(Modifier.width(12.dp))
                if (d.isHls || d.isYoutube) {
                    HlsChip(if (d.isYoutube) "YouTube" else "HLS")
                    Spacer(Modifier.width(8.dp))
                }
                Text(d.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))

            val fraction = item.fraction
            if (d.status != DownloadStatus.COMPLETED) {
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else if (d.status == DownloadStatus.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            Text(sizeLine(item), style = MaterialTheme.typography.bodyMedium)
            Text(
                statusLine(item, waitingForWifi),
                style = MaterialTheme.typography.bodyMedium,
                color = if (d.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            if (d.isHls) {
                Text("Segments", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                val total = item.segmentsTotal
                val done = if (d.status == DownloadStatus.COMPLETED) total else item.segmentsDone
                Text(if (total > 0) "$done of $total segments downloaded" else "Reading playlist…", style = MaterialTheme.typography.bodyMedium)
            } else if (d.isYoutube) {
                Text("Parts", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Video and audio are fetched in chunks of up to 10 MB, one part after the other, then merged into one file. " +
                        "Pausing keeps the bytes already fetched.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("Connections", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                SegmentBar(d.status, d.totalBytes, effectiveConnections(d), segments)
            }

            Spacer(Modifier.height(20.dp))
            InfoRow("URL", d.url, selectable = true)
            InfoRow("Saved to", d.savedPath ?: "Download/XDM/${d.fileName} (when finished)")
            InfoRow("Size", if (d.totalBytes > 0) "${Formatters.bytes(d.totalBytes)} (${d.totalBytes} bytes)" else "Unknown")
            InfoRow("Downloaded", Formatters.bytes(item.downloaded))
            InfoRow("Resume support", when {
                d.isHls -> "Yes — finished segments are kept"
                d.isYoutube -> "Yes — the link is looked up again, the bytes already fetched are kept"
                d.acceptRanges == true -> if (d.totalBytes > 0) "Yes" else "No (unknown size)"
                d.acceptRanges == false -> "No — single connection, pausing restarts the file"
                else -> "Not checked yet"
            })
            InfoRow(if (d.isHls) "Parallel segments" else "Connections", effectiveConnections(d).toString())
            d.mimeType?.let { InfoRow("Type", it) }
            InfoRow("Added", Formatters.dateTime(d.createdAt))
            d.completedAt?.let { InfoRow("Completed", Formatters.dateTime(it)) }
            d.error?.let { InfoRow(if (d.status == DownloadStatus.COMPLETED) "Note" else "Error", it) }
        }
    }

    if (confirmDelete) {
        DeleteDialog(d, onDismiss = { confirmDelete = false }) { alsoFile ->
            confirmDelete = false
            vm.delete(d.id, alsoFile)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, selectable: Boolean = false) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selectable) SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
        else Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One cell per byte range in file order, filled according to how much of it has been written. Ranges split while
 * downloading add cells, so their number changes as the download goes on.
 */
@Composable
private fun SegmentBar(status: DownloadStatus, total: Long, connections: Int, segments: List<SegmentEntity>) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        // [fraction of the whole file, fraction filled] for each cell
        val cells: List<Pair<Float, Float>> = when {
            status == DownloadStatus.COMPLETED -> List(connections.coerceAtLeast(1)) { 1f / connections.coerceAtLeast(1) to 1f }
            segments.isNotEmpty() && total > 0 && segments.all { it.end >= 0 } ->
                segments.sortedBy { it.start }.map { s ->
                    val len = (s.end - s.start + 1).coerceAtLeast(1)
                    (len.toFloat() / total) to (s.downloaded.toFloat() / len).coerceIn(0f, 1f)
                }
            else -> listOf(1f to 0f)
        }
        // Narrower gaps once there are many cells, so small ranges stay visible.
        val gap = (if (cells.size > 24) 1.dp else 3.dp).toPx()
        val radius = CornerRadius((if (cells.size > 24) 1.dp else 3.dp).toPx())
        val usable = (size.width - gap * (cells.size - 1)).coerceAtLeast(0f)
        var x = 0f
        for ((share, done) in cells) {
            val w = usable * share
            drawRoundRect(track, Offset(x, 0f), Size(w, size.height), radius)
            if (done > 0f) drawRoundRect(fill, Offset(x, 0f), Size(w * done, size.height), radius)
            x += w + gap
        }
    }
}
