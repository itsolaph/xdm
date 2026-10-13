package com.olaph.xdm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olaph.xdm.browser.BrowserTab
import com.olaph.xdm.browser.BrowserViewModel
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.media.DetectedMedia
import com.olaph.xdm.media.MediaKind
import com.olaph.xdm.media.MediaOption
import com.olaph.xdm.youtube.YoutubeUrls

/** Floating "download video" button: badge = number of detected items, pulses when a new one shows up. */
@Composable
fun MediaButton(vm: BrowserViewModel, tab: BrowserTab, modifier: Modifier = Modifier) {
    val count = tab.mediaItems.size

    // On a YouTube video, Shorts or playlist page the button opens the YouTube sheet (the page's own player requests
    // are not sniffed). Home, search and channel pages stay quiet.
    if (vm.isYoutube(tab)) {
        if (!YoutubeUrls.isDownloadablePage(tab.url)) return
        FloatingActionButton(onClick = { vm.openYoutube(tab) }, modifier = modifier) {
            Icon(Icons.Filled.Download, "Download this YouTube video")
        }
        return
    }
    if (count == 0) return

    val scale = remember { Animatable(1f) }
    LaunchedEffect(tab.mediaPulse) {
        if (tab.mediaPulse > 0) {
            scale.animateTo(1.3f, tween(160))
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    FloatingActionButton(onClick = { tab.mediaSheetOpen = true }, modifier = modifier.scale(scale.value)) {
        BadgedBox(badge = { Badge { Text(count.toString()) } }) {
            Icon(Icons.Filled.Download, "Download video ($count found)")
        }
    }
}

/** Bottom sheet with everything found on the page; tapping a row opens the usual "Add download" sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSheet(vm: BrowserViewModel, tab: BrowserTab) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val items = tab.mediaItems
    val title = tab.displayTitle

    ModalBottomSheet(onDismissRequest = { tab.mediaSheetOpen = false }, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Videos on this page", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Saved as “$title”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            if (items.isEmpty()) {
                Text(
                    "Nothing found yet. Start playing the video and it will show up here.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(items, key = { it.key }) { media ->
                    MediaGroup(media) { option -> vm.downloadMedia(tab, media, option) }
                    HorizontalDivider()
                }
            }
            Text(
                "DRM-protected streams (Netflix, Disney+, …) can't be downloaded. " +
                    "Live streams and DASH are not supported yet. Streams in HLS are saved as .ts (or .mp4 for fMP4) without conversion.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun MediaGroup(media: DetectedMedia, onPick: (MediaOption) -> Unit) {
    // A master playlist gets a small header with its qualities underneath; a single file is one row.
    if (media.options.size > 1) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TypeChip(media.typeLabel)
            Spacer(Modifier.width(10.dp))
            Text(
                media.url.substringAfter("://").substringBefore('?').takeLast(46),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (media.playing) PlayingMark()
        }
        media.options.forEach { OptionRow(media, it, onPick, indent = true) }
    } else {
        media.options.firstOrNull()?.let { OptionRow(media, it, onPick, indent = false) }
    }
}

@Composable
private fun OptionRow(media: DetectedMedia, option: MediaOption, onPick: (MediaOption) -> Unit, indent: Boolean) {
    val enabled = option.supported
    val alpha = if (enabled) 1f else 0.5f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onPick(option) }
            .padding(start = if (indent) 44.dp else 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!indent) {
            TypeChip(media.typeLabel)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
                sizeText(option)?.let {
                    Text(
                        "  $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    )
                }
                if (!indent && media.playing) {
                    Spacer(Modifier.width(8.dp))
                    PlayingMark()
                }
            }
            val sub = option.unsupportedReason ?: option.detail
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (option.unsupportedReason != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (media.kind == MediaKind.FILE && sub == null) {
                Text(
                    media.url.substringAfter("://").substringBefore('?').takeLast(48),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (enabled) Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
    }
}

private fun sizeText(o: MediaOption): String? =
    o.sizeBytes.takeIf { it > 0 }?.let { (if (o.sizeEstimated) "~" else "") + Formatters.bytes(it) }

@Composable
private fun TypeChip(label: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun PlayingMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Playing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
