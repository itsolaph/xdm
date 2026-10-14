package com.olaph.xdm.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

/** What the controls show; written by [PlayerActivity], read by Compose. */
class PlayerUi {
    var player by mutableStateOf<Player?>(null)
    var title by mutableStateOf("")
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var hasVideo by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var preview by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var rotationLocked by mutableStateOf(false)
    var speed by mutableFloatStateOf(1f)
    var tracks by mutableStateOf(Tracks.EMPTY)
    var hasNext by mutableStateOf(false)
    var hasPrevious by mutableStateOf(false)
}

interface PlayerActions {
    fun close()
    fun togglePlay()
    fun seekTo(positionMs: Long)
    fun next()
    fun previous()
    fun setSpeed(speed: Float)
    fun toggleRotationLock()
    /** [choice] null = switch that track type off (subtitles). */
    fun selectTrack(choice: TrackChoice?, type: Int)
    fun brightness(): Float
    fun setBrightness(value: Float)
    fun volume(): Int
    fun maxVolume(): Int
    fun setVolume(value: Int)
}

data class TrackChoice(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun trackChoices(tracks: Tracks, type: Int): List<TrackChoice> {
    var n = 0
    return tracks.groups.filter { it.type == type }.flatMap { g ->
        (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
            n++
            TrackChoice(g, i, trackLabel(g.getTrackFormat(i), n), g.isTrackSelected(i))
        }
    }
}

private fun trackLabel(f: Format, n: Int): String {
    val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
        ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
    val parts = listOfNotNull(f.label?.takeIf { it.isNotBlank() }, language, f.channelCount.takeIf { it > 0 }?.let { "$it ch" })
    return parts.distinct().joinToString(" · ").ifBlank { "Track $n" }
}

/** What a running gesture shows in the middle of the screen. */
private sealed interface Feedback {
    /** [transient]: a double tap, which has no "finger up" to clear it. */
    data class Seek(val targetMs: Long, val durationMs: Long, val deltaMs: Long, val transient: Boolean = false) : Feedback
    data class Level(val brightness: Boolean, val percent: Int) : Feedback
}

@Composable
fun PlayerScreen(ui: PlayerUi, actions: PlayerActions) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var controls by remember { mutableStateOf(true) }
        var feedback by remember { mutableStateOf<Feedback?>(null) }
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(0L) }
        var poke by remember { mutableIntStateOf(0) } // any touch on the controls restarts the hide timer
        val player = ui.player

        BackHandler { actions.close() }

        LaunchedEffect(player) {
            while (player != null) {
                position = player.currentPosition
                duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
                delay(200)
            }
        }
        LaunchedEffect(feedback) {
            val f = feedback
            if (f is Feedback.Seek && f.transient) {
                delay(900)
                feedback = null
            }
        }
        LaunchedEffect(controls, ui.isPlaying, poke) {
            if (controls && ui.isPlaying) {
                delay(HIDE_CONTROLS_MS)
                controls = false
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoSurface(player)
            if (!ui.hasVideo && player != null && ui.error == null) {
                Icon(Icons.Filled.MusicNote, null, Modifier.size(96.dp).align(Alignment.Center), tint = Color.White.copy(alpha = 0.6f))
            }
            if (!ui.inPip) {
                GestureLayer(
                    player = player,
                    actions = actions,
                    onTap = { controls = !controls },
                    onFeedback = { feedback = it },
                )
            }
            if (ui.buffering) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            feedback?.let { FeedbackBubble(it, Modifier.align(Alignment.Center)) }
            ui.error?.let { message ->
                Text(
                    message,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(16.dp),
                )
            }
            AnimatedVisibility(
                visible = controls && !ui.inPip,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Controls(ui, actions, position, duration, onInteract = { poke++ })
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: Player?) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun GestureLayer(
    player: Player?,
    actions: PlayerActions,
    onTap: () -> Unit,
    onFeedback: (Feedback?) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { offset ->
                        val p = player ?: return@detectTapGestures
                        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: -1
                        val target = PlayerGestures.doubleTapTarget(offset.x, size.width.toFloat(), p.currentPosition, duration)
                        onFeedback(Feedback.Seek(target, duration, target - p.currentPosition, transient = true))
                        p.seekTo(target)
                    },
                )
            }
            .pointerInput(player) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val p = player
                    val width = size.width.toFloat()
                    val height = size.height.toFloat()
                    val startPosition = p?.currentPosition ?: 0
                    val duration = p?.duration?.takeIf { it != C.TIME_UNSET } ?: -1
                    val startBrightness = actions.brightness()
                    val startVolume = actions.volume()
                    val maxVolume = actions.maxVolume()
                    var kind: DragKind? = null
                    var seekTarget = startPosition
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (kind == null) kind = PlayerGestures.classify(down.position.x, width, dx, dy, viewConfiguration.touchSlop)
                        when (kind) {
                            DragKind.SEEK -> if (p != null) {
                                seekTarget = PlayerGestures.seekTarget(startPosition, dx, width, duration)
                                onFeedback(Feedback.Seek(seekTarget, duration, seekTarget - startPosition))
                            }
                            DragKind.BRIGHTNESS -> {
                                val b = PlayerGestures.brightness(startBrightness, dy, height)
                                actions.setBrightness(b)
                                onFeedback(Feedback.Level(true, (b * 100).toInt()))
                            }
                            DragKind.VOLUME -> {
                                val v = PlayerGestures.volume(startVolume, maxVolume, dy, height)
                                actions.setVolume(v)
                                onFeedback(Feedback.Level(false, if (maxVolume > 0) v * 100 / maxVolume else 0))
                            }
                            null -> Unit
                        }
                        if (kind != null) change.consume()
                    }
                    if (kind == DragKind.SEEK && p != null) p.seekTo(seekTarget)
                    if (kind != null) onFeedback(null)
                }
            },
    )
}

@Composable
private fun FeedbackBubble(f: Feedback, modifier: Modifier) {
    val text = when (f) {
        is Feedback.Seek -> {
            val sign = if (f.deltaMs < 0) "−" else "+"
            val target = PlayerGestures.formatTime(f.targetMs)
            val total = if (f.durationMs > 0) " / " + PlayerGestures.formatTime(f.durationMs) else ""
            "$target$total\n$sign${PlayerGestures.formatTime(abs(f.deltaMs))}"
        }
        is Feedback.Level -> "${f.percent} %"
    }
    Row(
        modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (f is Feedback.Level) {
            Icon(if (f.brightness) Icons.Filled.BrightnessMedium else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

private fun abs(v: Long) = if (v < 0) -v else v

@Composable
private fun Controls(ui: PlayerUi, actions: PlayerActions, position: Long, duration: Long, onInteract: () -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var tracksDialog by remember { mutableStateOf(false) }
    val audioChoices = trackChoices(ui.tracks, C.TRACK_TYPE_AUDIO)
    val textChoices = trackChoices(ui.tracks, C.TRACK_TYPE_TEXT)
    val scrim = Color.Black.copy(alpha = 0.45f)

    Box(Modifier.fillMaxSize().background(scrim).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(ui.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (ui.preview) {
                    Text("Preview · streaming the original link", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
            Box {
                TextButton(onClick = { onInteract(); speedMenu = true }) { Text(speedLabel(ui.speed), color = Color.White) }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    SPEEDS.forEach { s ->
                        DropdownMenuItem(text = { Text(speedLabel(s)) }, onClick = { speedMenu = false; actions.setSpeed(s) })
                    }
                }
            }
            if (audioChoices.size > 1 || textChoices.isNotEmpty()) {
                IconButton(onClick = { onInteract(); tracksDialog = true }) { Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = Color.White) }
            }
            IconButton(onClick = { onInteract(); actions.toggleRotationLock() }) {
                Icon(
                    if (ui.rotationLocked) Icons.Filled.ScreenLockRotation else Icons.Filled.ScreenRotation,
                    if (ui.rotationLocked) "Unlock rotation" else "Lock rotation",
                    tint = Color.White,
                )
            }
        }

        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (ui.hasPrevious || ui.hasNext) {
                IconButton(onClick = { onInteract(); actions.previous() }, enabled = ui.hasPrevious) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
            IconButton(onClick = { onInteract(); actions.togglePlay() }, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (ui.isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp),
                )
            }
            if (ui.hasPrevious || ui.hasNext) {
                IconButton(onClick = { onInteract(); actions.next() }, enabled = ui.hasNext) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }

        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val shown = dragging?.let { (it * duration).toLong() } ?: position
            Row(Modifier.fillMaxWidth()) {
                Text(PlayerGestures.formatTime(shown), color = Color.White, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) PlayerGestures.formatTime(duration) else "--:--", color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
            Slider(
                value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { onInteract(); dragging = it },
                onValueChangeFinished = {
                    dragging?.let { actions.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
            )
        }
    }

    if (tracksDialog) {
        TracksDialog(audioChoices, textChoices, actions, onDismiss = { tracksDialog = false })
    }
}

private fun speedLabel(s: Float): String =
    if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"

@Composable
private fun TracksDialog(audio: List<TrackChoice>, text: List<TrackChoice>, actions: PlayerActions, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Audio and subtitles") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (audio.size > 1) {
                    Text("Audio", style = MaterialTheme.typography.titleSmall)
                    audio.forEach { c -> ChoiceRow(c.label, c.selected) { actions.selectTrack(c, C.TRACK_TYPE_AUDIO); onDismiss() } }
                }
                if (text.isNotEmpty()) {
                    Text("Subtitles", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    ChoiceRow("Off", text.none { it.selected }) { actions.selectTrack(null, C.TRACK_TYPE_TEXT); onDismiss() }
                    text.forEach { c -> ChoiceRow(c.label, c.selected) { actions.selectTrack(c, C.TRACK_TYPE_TEXT); onDismiss() } }
                }
            }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

private const val HIDE_CONTROLS_MS = 3_500L
