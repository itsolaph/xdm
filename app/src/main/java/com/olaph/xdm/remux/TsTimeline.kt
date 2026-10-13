package com.olaph.xdm.remux

/**
 * Turns the presentation timestamps of the tracks of one transport stream (microseconds, already corrected
 * for the 33-bit PTS wraparound by the extractor) into one continuous timeline that starts at 0.
 *
 * HLS playlists can carry `EXT-X-DISCONTINUITY`: the clock then jumps (back to 0, or far ahead) in the middle of
 * the concatenated file. A jump starts a new *epoch*; the epoch is placed right after the end of whatever the
 * tracks have produced so far, so the output never has a gap or goes backwards. The epoch is shared by all tracks
 * (a track joins it when its own clock jumps), which keeps audio and video in sync across the cut.
 *
 * Pure Kotlin; no media3 types so it can be tested exhaustively.
 */
internal class TsTimeline {
    enum class Kind { VIDEO, AUDIO }

    private class Epoch(val rawLo: Long, var rawHi: Long, val outBase: Long)

    private class Track(val kind: Kind) {
        var epoch = 0
        var count = 0
        var lastRaw = 0L
        var maxRaw = 0L
        var lastOut = 0L
        var maxOut = 0L
        /** Smallest positive step between consecutive output timestamps: one frame. */
        var frameUs = Long.MAX_VALUE
    }

    private val epochs = ArrayList<Epoch>()
    private val tracks = ArrayList<Track>()

    /** Number of times the clock was found to jump. */
    var discontinuities = 0
        private set

    fun addTrack(kind: Kind): Int {
        tracks += Track(kind)
        return tracks.size - 1
    }

    /** Raw timestamp that becomes output time 0; the earliest first sample of all tracks. Call once, before [map]. */
    fun begin(zeroRawUs: Long) {
        check(epochs.isEmpty()) { "already started" }
        epochs += Epoch(zeroRawUs, zeroRawUs, 0)
    }

    /** Output timestamp of the sample in microseconds, or [DROP] if it lies before the start and must be skipped. */
    fun map(track: Int, rawUs: Long, keyFrame: Boolean): Long {
        val t = tracks[track]
        if (t.count > 0 && isJump(t, rawUs, keyFrame)) {
            t.epoch = joinOrCreate(t, rawUs)
            t.maxRaw = rawUs
            t.lastRaw = rawUs
        }
        val ep = epochs[t.epoch]
        if (rawUs > ep.rawHi) ep.rawHi = rawUs
        t.lastRaw = rawUs
        if (rawUs > t.maxRaw || t.count == 0) t.maxRaw = rawUs

        var out = ep.outBase + (rawUs - ep.rawLo)
        if (out < 0) return DROP
        // Audio frames never reorder: two with the same time would be one too many for the player.
        if (t.kind == Kind.AUDIO && t.count > 0 && out <= t.lastOut) out = t.lastOut + MIN_STEP_US
        if (t.count > 0) {
            val step = out - t.lastOut
            if (step > 0 && step < t.frameUs) t.frameUs = step
        }
        t.lastOut = out
        if (t.count == 0 || out > t.maxOut) t.maxOut = out
        t.count++
        return out
    }

    /** End of the content so far (the last sample's start plus one frame), over all tracks. */
    fun endUs(): Long = tracks.maxOfOrNull { trackEnd(it) } ?: 0L

    private fun trackEnd(t: Track): Long = if (t.count == 0) 0L else t.maxOut + (if (t.frameUs == Long.MAX_VALUE) 0L else t.frameUs)

    private fun isJump(t: Track, rawUs: Long, keyFrame: Boolean): Boolean {
        val ep = epochs[t.epoch]
        val ahead = maxOf(ep.rawHi, t.maxRaw)
        if (rawUs > ahead + FORWARD_JUMP_US) return true
        return when (t.kind) {
            // Audio frames come in order: any step back is a reset.
            Kind.AUDIO -> rawUs < t.lastRaw - AUDIO_TOLERANCE_US
            // Reordered (B) frames go back by a few frames at most, but a key frame is shown after everything before it.
            Kind.VIDEO -> if (keyFrame) rawUs < t.maxRaw - KEY_FRAME_TOLERANCE_US else rawUs < t.maxRaw - REORDER_LIMIT_US
        }
    }

    /** The epoch after the track's current one that [rawUs] belongs to (made by another track's jump), or a new one. */
    private fun joinOrCreate(t: Track, rawUs: Long): Int {
        for (i in t.epoch + 1 until epochs.size) {
            val e = epochs[i]
            if (rawUs >= e.rawLo - JOIN_BACK_US && rawUs <= e.rawHi + FORWARD_JUMP_US) return i
        }
        val base = tracks.maxOf { trackEnd(it) }
        epochs += Epoch(rawUs, rawUs, base)
        discontinuities++
        return epochs.size - 1
    }

    companion object {
        const val DROP = Long.MIN_VALUE

        private const val FORWARD_JUMP_US = 10_000_000L
        private const val AUDIO_TOLERANCE_US = 2_000L
        private const val KEY_FRAME_TOLERANCE_US = 100_000L
        private const val REORDER_LIMIT_US = 5_000_000L
        private const val JOIN_BACK_US = 5_000_000L
        private const val MIN_STEP_US = 1_000L
    }
}
