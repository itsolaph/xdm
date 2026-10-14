package com.olaph.xdm.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.olaph.xdm.R
import com.olaph.xdm.XdmApp
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.engine.HeaderCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * The built-in player: finished downloads (public or private) and "Preview" of unfinished ones, which streams the
 * original link with the download's saved request headers. Fullscreen, gestures (see [PlayerGestures]), resume position
 * per download (see [ResumePolicy]), picture-in-picture. Private content sets FLAG_SECURE and closes when the private
 * folder locks.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerActions {

    private val container get() = (application as XdmApp).container
    private val ui = PlayerUi()
    private var player: ExoPlayer? = null
    private var entities: Map<Long, DownloadEntity> = emptyMap()
    private val savedPositions = HashMap<Long, Long>()
    private val durations = HashMap<Long, Long>()
    private val resumeChecked = HashSet<Long>()
    private var preview = false
    private var secure = false
    private var autoOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    private var orientationDecided = false
    private var loadJob: Job? = null
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_TOGGLE) togglePlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything is drawn: a private file must never reach a screenshot or the recents thumbnail.
        if (intent.getBooleanExtra(EXTRA_PRIVATE, false)) setSecure(true)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()
        volumeControlStream = AudioManager.STREAM_MUSIC
        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { PlayerScreen(ui, this) }
        lifecycleScope.launch {
            container.vaultLock.unlocked.collect { open -> if (!open && secure) finishAndRemoveTask() }
        }
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PRIVATE, false)) setSecure(true)
        load(intent)
    }

    private fun load(intent: Intent) {
        savePosition()
        releasePlayer()
        val ids = intent.getLongArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        val startId = intent.getLongExtra(EXTRA_START_ID, -1)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val loaded = ids.mapNotNull { container.dao.get(it) }
            if (loaded.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Nothing to play", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
                return@launch
            }
            if (loaded.any { it.isPrivate }) {
                setSecure(true)
                if (!container.vaultLock.isUnlocked()) {
                    finishAndRemoveTask()
                    return@launch
                }
            }
            val userAgent = container.settings.current().userAgent
            start(loaded, startId, userAgent)
        }
    }

    private fun start(loaded: List<DownloadEntity>, startId: Long, defaultUserAgent: String) {
        entities = loaded.associateBy { it.id }
        savedPositions.clear()
        durations.clear()
        resumeChecked.clear()
        loaded.forEach { savedPositions[it.id] = it.playbackPositionMs }

        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setUserAgent(defaultUserAgent)
        if (preview) {
            // The same Cookie / User-Agent / Referer / Origin the download itself sends.
            val headers = HeaderCodec.decode(loaded.first().headers)
            headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { http.setUserAgent(it.value) }
            http.setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        }
        val items = loaded.mapNotNull { mediaItem(it) }
        if (items.isEmpty()) {
            ui.error = "The file was not found (moved or deleted)."
            return
        }
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(listener)
        val startIndex = items.indexOfFirst { it.mediaId == startId.toString() }.coerceAtLeast(0)
        val first = items[startIndex].mediaId.toLong()
        val startPosition = if (preview) 0 else ResumePolicy.startPosition(savedPositions[first] ?: 0, -1)
        p.setMediaItems(items, startIndex, startPosition)
        p.prepare()
        p.playWhenReady = true
        player = p
        ui.player = p
        ui.preview = preview
        ui.error = null
        onItemChanged()
        lifecycleScope.launch {
            // A crash or a killed process should not lose more than a few seconds of the resume position.
            while (player === p) {
                delay(SAVE_EVERY_MS)
                if (p.isPlaying) savePosition()
            }
        }
    }

    private fun mediaItem(d: DownloadEntity): MediaItem? {
        val b = MediaItem.Builder().setMediaId(d.id.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(d.fileName).build())
        if (preview) {
            b.setUri(d.url)
            if (d.isHls) b.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        } else {
            val location = d.savedLocation ?: return null
            b.setUri(if (location.startsWith("content://")) Uri.parse(location) else Uri.fromFile(File(location)))
        }
        return b.build()
    }

    private val currentId: Long? get() = player?.currentMediaItem?.mediaId?.toLongOrNull()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.isPlaying = isPlaying
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isPlaying) savePosition()
            updatePip()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            ui.buffering = playbackState == Player.STATE_BUFFERING
            when (playbackState) {
                Player.STATE_READY -> onReady()
                Player.STATE_ENDED -> currentId?.let { store(it, 0) }
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                ui.hasVideo = true
                if (!orientationDecided) {
                    orientationDecided = true
                    autoOrientation = if (videoSize.width > videoSize.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    applyOrientation()
                }
            }
            updatePip()
        }

        override fun onTracksChanged(tracks: Tracks) {
            ui.tracks = tracks
            ui.hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onItemChanged()

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
            val oldId = oldPosition.mediaItem?.mediaId?.toLongOrNull() ?: return
            val ended = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            store(oldId, ResumePolicy.toSave(oldPosition.positionMs, durations[oldId] ?: -1, ended))
        }

        override fun onPlayerError(error: PlaybackException) {
            ui.error = when {
                preview -> "Can't stream this link: ${error.errorCodeName}. The server may refuse playback outside the page."
                else -> "Can't play this file: ${error.errorCodeName}"
            }
        }
    }

    private fun onItemChanged() {
        val p = player ?: return
        ui.title = currentId?.let { entities[it]?.fileName }.orEmpty()
        ui.hasNext = p.hasNextMediaItem()
        ui.hasPrevious = p.hasPreviousMediaItem()
    }

    private fun onReady() {
        val p = player ?: return
        val id = currentId ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: -1
        durations[id] = duration
        if (!preview && resumeChecked.add(id)) {
            val want = ResumePolicy.startPosition(savedPositions[id] ?: 0, duration)
            if (abs(p.currentPosition - want) > RESUME_TOLERANCE_MS) p.seekTo(want)
        }
    }

    private fun savePosition() {
        if (preview) return
        val p = player ?: return
        val id = currentId ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: -1
        store(id, ResumePolicy.toSave(p.currentPosition, duration, ended = p.playbackState == Player.STATE_ENDED))
    }

    private fun store(id: Long, positionMs: Long) {
        if (preview || savedPositions[id] == positionMs) return
        savedPositions[id] = positionMs
        container.scope.launch { container.dao.setPlaybackPosition(id, positionMs) }
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        ui.player = null
    }

    // ---- window ----

    private fun setSecure(on: Boolean) {
        secure = secure || on
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun applyOrientation() {
        requestedOrientation = if (ui.rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else autoOrientation
    }

    // ---- lifecycle ----

    override fun onStart() {
        super.onStart()
        if (secure && !container.vaultLock.isUnlocked()) finishAndRemoveTask()
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        // Also the way out of picture-in-picture when its window is closed: nothing keeps playing unseen.
        player?.pause()
    }

    override fun onDestroy() {
        savePosition()
        releasePlayer()
        runCatching { unregisterReceiver(pipReceiver) }
        super.onDestroy()
    }

    // ---- picture-in-picture ----

    private fun canPip(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val p = player
        val b = PictureInPictureParams.Builder()
        val size = p?.videoSize
        if (size != null && size.width > 0 && size.height > 0) {
            // The system rejects ratios outside 1:2.39 .. 2.39:1.
            val ratio = (size.width.toFloat() * size.pixelWidthHeightRatio / size.height).coerceIn(1 / 2.39f, 2.39f)
            b.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        val playing = p?.isPlaying == true
        val label = if (playing) "Pause" else "Play"
        val intent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
        b.setActions(listOf(RemoteAction(icon, label, label, intent)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(playing && ui.hasVideo)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun updatePip() {
        if (!canPip()) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters by itself (auto-enter in the params).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && canPip() && player?.isPlaying == true && ui.hasVideo) {
            runCatching { enterPictureInPictureMode(pipParams()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.inPip = isInPictureInPictureMode
        if (!isInPictureInPictureMode && lifecycle.currentState == Lifecycle.State.CREATED) {
            finishAndRemoveTask() // the picture-in-picture window was closed
        }
    }

    // ---- PlayerActions (called by the controls) ----

    override fun close() = finishAndRemoveTask()

    override fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    override fun next() {
        savePosition()
        player?.seekToNextMediaItem()
    }

    override fun previous() {
        savePosition()
        player?.seekToPreviousMediaItem()
    }

    override fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        ui.speed = speed
    }

    override fun toggleRotationLock() {
        ui.rotationLocked = !ui.rotationLocked
        applyOrientation()
    }

    override fun selectTrack(choice: TrackChoice?, type: Int) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (choice == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(androidx.media3.common.TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index))
        }
        p.trackSelectionParameters = b.build()
    }

    override fun brightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0) return current
        // Not overridden yet: start from the system level (its scale is 0..255 on most devices).
        return runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            .getOrDefault(0.5f).coerceIn(PlayerGestures.MIN_BRIGHTNESS, 1f)
    }

    override fun setBrightness(value: Float) {
        // Only this window: the system setting is never changed.
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    override fun volume(): Int = audio.getStreamVolume(AudioManager.STREAM_MUSIC)

    override fun maxVolume(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    override fun setVolume(value: Int) {
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) } // refused in Do Not Disturb on some devices
    }

    companion object {
        const val EXTRA_IDS = "ids"
        const val EXTRA_START_ID = "start_id"
        const val EXTRA_PREVIEW = "preview"
        /** Set when anything in the list is private, so FLAG_SECURE is on before the first frame. */
        const val EXTRA_PRIVATE = "private"
        private const val ACTION_PIP_TOGGLE = "com.olaph.xdm.player.PIP_TOGGLE"
        private const val SAVE_EVERY_MS = 10_000L
        private const val RESUME_TOLERANCE_MS = 1_500L
    }
}
