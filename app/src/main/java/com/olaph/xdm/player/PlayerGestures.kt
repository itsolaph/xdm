package com.olaph.xdm.player

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DragKind { SEEK, BRIGHTNESS, VOLUME }

/** The player's touch math, kept free of Android so it is unit-tested. Coordinates are pixels, times milliseconds. */
object PlayerGestures {
    const val SEEK_STEP_MS = 10_000L
    /** A drag across the whole width moves this far (or the whole video if it is shorter). */
    const val MAX_SEEK_SPAN_MS = 90_000L
    /** Window brightness never goes fully black: 0 would look like the screen switched off. */
    const val MIN_BRIGHTNESS = 0.01f

    /**
     * Which control a drag adjusts, decided once the finger is past [slop]: mostly horizontal = seek; vertical = brightness
     * on the left half, volume on the right half. Null while undecided.
     */
    fun classify(startX: Float, width: Float, dx: Float, dy: Float, slop: Float): DragKind? {
        if (abs(dx) < slop && abs(dy) < slop) return null
        return when {
            abs(dx) >= abs(dy) -> DragKind.SEEK
            startX < width / 2 -> DragKind.BRIGHTNESS
            else -> DragKind.VOLUME
        }
    }

    fun seekTarget(startMs: Long, dx: Float, width: Float, durationMs: Long): Long {
        if (width <= 0f) return startMs
        val span = if (durationMs > 0) min(durationMs, MAX_SEEK_SPAN_MS) else MAX_SEEK_SPAN_MS
        return clampPosition(startMs + (dx / width * span).toLong(), durationMs)
    }

    /** Dragging up brightens; the full height covers the whole range. */
    fun brightness(start: Float, dy: Float, height: Float): Float {
        if (height <= 0f) return start
        return (start - dy / height).coerceIn(MIN_BRIGHTNESS, 1f)
    }

    /** Dragging up is louder; the full height covers 0..[maxVolume]. */
    fun volume(start: Int, maxVolume: Int, dy: Float, height: Float): Int {
        if (height <= 0f || maxVolume <= 0) return start
        return (start - dy / height * maxVolume).roundToInt().coerceIn(0, maxVolume)
    }

    /** Double tap on the left half goes back [SEEK_STEP_MS], on the right half forward. */
    fun doubleTapTarget(x: Float, width: Float, positionMs: Long, durationMs: Long): Long {
        val step = if (x < width / 2) -SEEK_STEP_MS else SEEK_STEP_MS
        return clampPosition(positionMs + step, durationMs)
    }

    private fun clampPosition(ms: Long, durationMs: Long): Long =
        if (durationMs > 0) ms.coerceIn(0, durationMs) else max(0, ms)

    /** "0:07", "12:34", "1:02:03". */
    fun formatTime(ms: Long): String {
        val total = max(0, ms) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/** When the player picks up where it stopped, and what it stores. */
object ResumePolicy {
    const val MIN_RESUME_MS = 5_000L
    const val FINISHED_FRACTION = 0.95

    /** Where to start: the saved position if it is past the first 5 s and before the last 5 % (unknown duration: trust it). */
    fun startPosition(savedMs: Long, durationMs: Long): Long = when {
        savedMs <= MIN_RESUME_MS -> 0
        durationMs > 0 && savedMs >= durationMs * FINISHED_FRACTION -> 0
        else -> savedMs
    }

    /** What to store when playback stops at [positionMs]: finished (or nearly) and barely started both store 0. */
    fun toSave(positionMs: Long, durationMs: Long, ended: Boolean): Long = when {
        ended -> 0
        positionMs <= MIN_RESUME_MS -> 0
        durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION -> 0
        else -> positionMs
    }
}
