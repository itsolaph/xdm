package com.olaph.xdm.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olaph.xdm.AppContainer
import com.olaph.xdm.core.Formatters
import com.olaph.xdm.youtube.FormatSelector
import com.olaph.xdm.youtube.OptionKind
import com.olaph.xdm.youtube.YoutubeErrors
import com.olaph.xdm.youtube.YoutubeLink
import com.olaph.xdm.youtube.YoutubeQuality
import com.olaph.xdm.youtube.YoutubeUrls
import com.olaph.xdm.youtube.YtInfo
import com.olaph.xdm.youtube.YtOption
import com.olaph.xdm.youtube.YtPlan
import com.olaph.xdm.youtube.YtPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

private sealed interface YtState {
    data object Loading : YtState
    data class Failed(val message: String) : YtState
    data class Video(val info: YtInfo, val options: List<YtOption>, val playlistId: String?) : YtState
    data class Playlist(val list: YtPlaylist) : YtState
}

/** Qualities offered for a whole playlist (the "ask" entry makes no sense there). */
private val PLAYLIST_QUALITIES = listOf(YoutubeQuality.BEST, YoutubeQuality.P1080, YoutubeQuality.P720, YoutubeQuality.P480, YoutubeQuality.AUDIO)

/**
 * The YouTube sheet: looks the link up and offers the formats (video, playlist). With a default quality set in
 * Settings a single video is added straight away and the sheet only shows what it is doing. [onDone] gets a short
 * message for the caller to toast once something was added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeSheet(
    container: AppContainer,
    link: YoutubeLink,
    defaultQuality: YoutubeQuality,
    connections: Int,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var target by remember(link) { mutableStateOf(link) }
    var state by remember(link) { mutableStateOf<YtState>(YtState.Loading) }
    var rememberChoice by remember(link) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var playlistQuality by remember { mutableStateOf(defaultQuality.takeIf { it != YoutubeQuality.ASK } ?: YoutubeQuality.P720) }

    suspend fun addOption(info: YtInfo, option: YtOption, options: List<YtOption>, rememberIt: Boolean) {
        container.manager.addYoutube(YtPlan.forOption(info.videoId, option), info.title, startNow = false, connections = connections)
        if (rememberIt) FormatSelector.qualityFor(option, options)?.let { container.settings.setYoutubeQuality(it) }
        onDone("Added: " + FormatSelector.fileName(info.title, option))
    }

    LaunchedEffect(target) {
        state = YtState.Loading
        try {
            when (val t = target) {
                is YoutubeLink.Video -> {
                    val info = container.youtube.resolve(t.id)
                    if (info.live) {
                        state = YtState.Failed(com.olaph.xdm.youtube.YtErrorKind.LIVE.message)
                        return@LaunchedEffect
                    }
                    val options = FormatSelector.options(info)
                    if (options.isEmpty()) {
                        state = YtState.Failed("YouTube didn't offer any format XDM can download for this video.")
                        return@LaunchedEffect
                    }
                    val picked = if (defaultQuality == YoutubeQuality.ASK) null else FormatSelector.pick(info, defaultQuality, options)
                    if (picked != null) {
                        addOption(info, picked, options, rememberIt = false)
                    } else {
                        state = YtState.Video(info, options, t.listId?.takeIf { YoutubeUrls.isRealPlaylist(it) })
                    }
                }
                is YoutubeLink.Playlist -> state = YtState.Playlist(container.youtube.playlist(t.id))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            state = YtState.Failed(YoutubeErrors.from(t).message.orEmpty())
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            when (val s = state) {
                YtState.Loading -> {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (target is YoutubeLink.Playlist) "Reading playlist…" else "Getting video info…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is YtState.Failed -> {
                    Text("Can't download this", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Close") }
                }
                is YtState.Video -> VideoChoices(
                    container = container,
                    info = s.info,
                    options = s.options,
                    playlistId = s.playlistId,
                    defaultQuality = defaultQuality,
                    remember = rememberChoice,
                    onRemember = { rememberChoice = it },
                    busy = busy,
                    onPlaylist = { target = YoutubeLink.Playlist(it) },
                    onPick = { option ->
                        if (!busy) {
                            busy = true
                            scope.launch { addOption(s.info, option, s.options, rememberChoice) }
                        }
                    },
                )
                is YtState.Playlist -> PlaylistChoices(
                    list = s.list,
                    quality = playlistQuality,
                    onQuality = { playlistQuality = it },
                    busy = busy,
                    onDownloadAll = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                for (item in s.list.items) {
                                    container.manager.addYoutube(YtPlan.deferred(item.videoId, playlistQuality), item.title, startNow = false, connections = connections)
                                }
                                onDone("Added ${s.list.items.size} videos from the playlist")
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VideoChoices(
    container: AppContainer,
    info: YtInfo,
    options: List<YtOption>,
    playlistId: String?,
    defaultQuality: YoutubeQuality,
    remember: Boolean,
    onRemember: (Boolean) -> Unit,
    busy: Boolean,
    onPlaylist: (String) -> Unit,
    onPick: (YtOption) -> Unit,
) {
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(container, info.thumbnailUrl)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(info.channel, durationText(info.durationSeconds)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(8.dp))

    val video = options.filter { it.kind == OptionKind.VIDEO }
    val progressive = options.filter { it.kind == OptionKind.PROGRESSIVE }
    val audio = options.filter { it.kind == OptionKind.AUDIO }
    if (video.isNotEmpty()) {
        SectionTitle("Video (MP4)")
        video.forEach { OptionRow(it, enabled = !busy, onPick) }
    }
    if (progressive.isNotEmpty()) {
        SectionTitle("Fast")
        progressive.forEach { OptionRow(it, enabled = !busy, onPick) }
    }
    if (audio.isNotEmpty()) {
        SectionTitle("Audio only")
        audio.forEach { OptionRow(it, enabled = !busy, onPick) }
    }

    HorizontalDivider(Modifier.padding(top = 8.dp))
    Row(
        Modifier.fillMaxWidth().clickable { onRemember(!remember) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = remember, onCheckedChange = onRemember)
        Column {
            Text("Remember my choice", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Default now: ${defaultQuality.label}. Change it any time in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (playlistId != null) {
        TextButton(onClick = { onPlaylist(playlistId) }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("This video is part of a playlist — download the whole playlist")
        }
    }
}

@Composable
private fun PlaylistChoices(
    list: YtPlaylist,
    quality: YoutubeQuality,
    onQuality: (YoutubeQuality) -> Unit,
    busy: Boolean,
    onDownloadAll: () -> Unit,
) {
    Text(
        list.title.ifBlank { "Playlist" },
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    val count = if (list.totalCount >= 0) list.totalCount else list.items.size.toLong()
    Text(
        listOf(list.channel, "$count videos").filter { it.isNotBlank() }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
    if (list.truncated) {
        Text(
            "Only the first ${list.items.size} videos are added (the limit per playlist).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
    SectionTitle("Quality for every video")
    PLAYLIST_QUALITIES.forEach { q ->
        Row(
            Modifier.fillMaxWidth().clickable { onQuality(q) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = quality == q, onClick = { onQuality(q) })
            Text(q.label)
        }
    }
    Text(
        "A video that doesn't come in the chosen resolution gets the closest lower one. Each video is its own download.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Button(
        onClick = onDownloadAll,
        enabled = !busy && list.items.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) { Text("Download all (${list.items.size})") }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun OptionRow(option: YtOption, enabled: Boolean, onPick: (YtOption) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onPick(option) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(option.label, style = MaterialTheme.typography.titleSmall)
                if (option.sizeBytes > 0) {
                    Text(
                        "  " + (if (option.sizeEstimated) "~" else "") + Formatters.bytes(option.sizeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (option.detail.isNotBlank()) {
                Text(option.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Thumbnail(container: AppContainer, url: String?) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bitmap = loadThumbnail(container, url) }
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(width = 112.dp, height = 63.dp)) {
        bitmap?.let {
            Image(it, null, Modifier.clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        } ?: Box(Modifier)
    }
}

private suspend fun loadThumbnail(container: AppContainer, url: String?): ImageBitmap? {
    if (url == null) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            container.httpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
                r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
        }.getOrNull()
    }
}

private fun durationText(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
