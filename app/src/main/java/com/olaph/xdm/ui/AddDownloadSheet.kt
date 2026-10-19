package com.olaph.xdm.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olaph.xdm.AppContainer
import android.widget.Toast
import com.olaph.xdm.core.DuplicateCandidate
import com.olaph.xdm.core.DuplicateMatch
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.core.PendingDownload
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.engine.DownloadHeaders
import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.engine.ProbeResult
import com.olaph.xdm.engine.SegmentPlanner
import com.olaph.xdm.engine.UrlUtils
import com.olaph.xdm.media.MediaClassifier
import com.olaph.xdm.youtube.YoutubeLink
import com.olaph.xdm.youtube.YoutubeUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface ProbeState {
    data object Idle : ProbeState
    data object Loading : ProbeState
    data class Done(val result: ProbeResult) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/** "Add download" sheet shared by the main screen and the transparent share receiver. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadSheet(
    container: AppContainer,
    prefill: PendingDownload?,
    defaultConnections: Int,
    /** A PIN exists, so the private folder can take downloads. */
    privateAvailable: Boolean,
    defaultPrivate: Boolean,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
    /** A pasted or typed YouTube link is handed over instead of being probed as a file. */
    onYoutube: (YoutubeLink) -> Unit = {},
    /** "Open existing" in the duplicate dialog: show download [id] (the caller closes the sheet afterwards). */
    onOpenExisting: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Headers from the browser (Cookie, Referer, User-Agent) apply to the probe and to the whole download.
    val headers = prefill?.headers.orEmpty()
    val origin = DownloadHeaders.origin(prefill?.pageUrl)
    var url by rememberSaveable { mutableStateOf(prefill?.url.orEmpty()) }
    var fileName by rememberSaveable { mutableStateOf(prefill?.fileName.orEmpty()) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var connections by rememberSaveable { mutableIntStateOf(defaultConnections) }
    var probe by remember { mutableStateOf<ProbeState>(ProbeState.Idle) }
    var adding by remember { mutableStateOf(false) }
    var savePrivate by rememberSaveable { mutableStateOf(prefill?.isPrivate ?: defaultPrivate) }

    val trimmed = url.trim()
    val validUrl = UrlUtils.isHttpUrl(trimmed)
    // A playlist is downloaded segment by segment; there is no byte-range probe for it.
    val hls = validUrl && ((prefill?.kind == DownloadKind.HLS && trimmed == prefill.url) || MediaClassifier.isHlsUrl(trimmed))

    LaunchedEffect(trimmed) {
        YoutubeUrls.parse(trimmed)?.let {
            onYoutube(it)
            return@LaunchedEffect
        }
        if (!validUrl || hls) {
            probe = ProbeState.Idle
            if (hls && !nameEdited && fileName.isBlank()) fileName = FilenameUtils.resolve(null, trimmed, null)
            return@LaunchedEffect
        }
        probe = ProbeState.Loading
        delay(500) // debounce typing
        try {
            val result = container.manager.probe(trimmed, headers)
            probe = ProbeState.Done(result)
            if (!nameEdited) fileName = result.fileName
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            probe = ProbeState.Failed(e.message ?: "Could not reach the server")
            // Keep the browser-provided name (from Content-Disposition) when the probe can't improve on it.
            if (!nameEdited && (trimmed != prefill?.url || fileName.isBlank())) {
                fileName = FilenameUtils.resolve(null, trimmed, null)
            }
        }
    }

    // A download that looks like one already in the list waits here for the user's answer.
    var duplicate by remember { mutableStateOf<Pair<DuplicateMatch, Boolean>?>(null) }

    suspend fun addNow(startNow: Boolean) {
        val result = (probe as? ProbeState.Done)?.result
        val name = fileName.ifBlank { result?.fileName ?: FilenameUtils.resolve(null, trimmed, null) }
        container.manager.add(
            trimmed, name, connections, startNow, result, headers,
            kind = if (hls) DownloadKind.HLS else DownloadKind.HTTP,
            isPrivate = savePrivate && privateAvailable,
        )
        onAdded()
    }

    fun submit(startNow: Boolean) {
        if (adding || !validUrl) return
        adding = true
        scope.launch {
            val result = (probe as? ProbeState.Done)?.result
            val name = fileName.ifBlank { result?.fileName ?: FilenameUtils.resolve(null, trimmed, null) }
            // A playlist's size is only an estimate: it says nothing about which file it is.
            val size = if (hls) -1L else result?.contentLength ?: prefill?.contentLength?.takeIf { trimmed == prefill.url } ?: -1L
            val match = container.manager.findDuplicate(DuplicateCandidate(trimmed, name, size, isPrivate = savePrivate && privateAvailable))
            if (match == null) {
                addNow(startNow)
            } else {
                duplicate = match to startNow
                adding = false
            }
        }
    }

    duplicate?.let { (match, startNow) ->
        val appContext = context.applicationContext
        DuplicateDialog(
            match = match,
            onResume = {
                duplicate = null
                container.manager.resume(match.existing.id)
                Toast.makeText(appContext, "Resuming ${match.existing.fileName}", Toast.LENGTH_SHORT).show()
                onDismiss()
            },
            onOpen = { duplicate = null; onOpenExisting(match.existing.id); onDismiss() },
            onAddAgain = {
                duplicate = null
                adding = true
                scope.launch { addNow(startNow) }
            },
            onCancel = { duplicate = null },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add download", style = MaterialTheme.typography.titleLarge)
            if (origin != null) {
                Text(
                    "From: $origin" + if (headers.containsKey("Cookie")) " · signed-in session is forwarded" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                singleLine = false,
                maxLines = 3,
                isError = url.isNotBlank() && !validUrl,
                supportingText = { if (url.isNotBlank() && !validUrl) Text("Enter a valid http(s) link") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = { pasteInto(context) { url = it } }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (hls) {
                val estimate = prefill?.contentLength?.takeIf { it > 0 && trimmed == prefill.url }
                Text(
                    "HLS stream · segments are fetched in parallel and joined into one file (no conversion)" +
                        (estimate?.let { " · " + (if (prefill?.lengthEstimated == true) "~" else "") + Formatters.bytes(it) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val p = probe) {
                ProbeState.Idle -> Unit
                ProbeState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    val hint = prefill?.contentLength?.takeIf { it > 0 && trimmed == prefill.url }
                    Text(
                        "  Checking link…" + if (hint != null) " (${Formatters.bytes(hint)})" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ProbeState.Done -> {
                    val r = p.result
                    Text(
                        buildString {
                            append("Size: ").append(if (r.contentLength > 0) Formatters.bytes(r.contentLength) else "unknown")
                            append(" · ").append(
                                when {
                                    !r.resumable -> "no resume support, single connection"
                                    r.contentLength < SegmentPlanner.SPLIT_THRESHOLD -> "small file, single connection"
                                    else -> "resumable"
                                },
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.resumable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                    )
                }
                is ProbeState.Failed -> Text(
                    "Could not check the link (${p.message}). You can still add it." +
                        (prefill?.contentLength?.takeIf { it > 0 && trimmed == prefill.url }?.let { " Size: ${Formatters.bytes(it)}." } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it; nameEdited = true },
                label = { Text("File name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val resumable = hls || (probe as? ProbeState.Done)?.result?.resumable != false
            Column {
                Text(
                    when {
                        hls -> "Parallel segments: $connections"
                        resumable -> "Connections: $connections"
                        else -> "Connections: 1 (server does not allow more)"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = connections.toFloat(),
                    onValueChange = { connections = it.toInt() },
                    valueRange = 1f..16f,
                    steps = 14,
                    enabled = resumable,
                )
            }

            PrivateCheckbox(checked = savePrivate && privateAvailable, available = privateAvailable, onChange = { savePrivate = it })

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { submit(false) },
                    enabled = validUrl && !adding,
                    modifier = Modifier.weight(1f),
                ) { Text("Add to queue") }
                Button(
                    onClick = { submit(true) },
                    enabled = validUrl && !adding,
                    modifier = Modifier.weight(1f),
                ) { Text("Start now") }
            }
        }
    }
}

/** "Save to private folder", shared by the add, browser video and YouTube sheets. */
@Composable
fun PrivateCheckbox(checked: Boolean, available: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(enabled = available) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = available)
        Column {
            Text("Save to private folder", style = MaterialTheme.typography.bodyMedium)
            Text(
                if (available) "Hidden from the gallery and other apps, behind your PIN"
                else "Set up the private folder first (lock icon in Downloads)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun pasteInto(context: Context, set: (String) -> Unit) {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
    if (clip.itemCount == 0) return
    val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
    set(UrlUtils.extractFirstUrl(text) ?: text.trim())
}
