package com.olaph.xdm.engine

/**
 * Rolling-average transfer speed over the last [windowMs] milliseconds.
 * Feed it cumulative byte counts with a monotonic timestamp.
 */
class SpeedMeter(private val windowMs: Long = 5_000) {
    private val times = ArrayDeque<Long>()
    private val bytes = ArrayDeque<Long>()

    @Synchronized
    fun add(timeMs: Long, totalBytes: Long) {
        // A counter that went backwards (restart from zero) invalidates the history.
        if (bytes.isNotEmpty() && totalBytes < bytes.last()) {
            times.clear(); bytes.clear()
        }
        times.addLast(timeMs); bytes.addLast(totalBytes)
        while (times.size > 2 && timeMs - times.first() > windowMs) {
            times.removeFirst(); bytes.removeFirst()
        }
    }

    /** Bytes per second, 0 if not enough data. */
    @Synchronized
    fun bytesPerSecond(): Long {
        if (times.size < 2) return 0
        val dt = times.last() - times.first()
        if (dt <= 0) return 0
        return (bytes.last() - bytes.first()) * 1000 / dt
    }

    @Synchronized
    fun reset() {
        times.clear(); bytes.clear()
    }

    companion object {
        /** Seconds remaining, or -1 when unknown. */
        fun etaSeconds(remainingBytes: Long, bytesPerSecond: Long): Long =
            if (remainingBytes < 0 || bytesPerSecond <= 0) -1 else (remainingBytes + bytesPerSecond - 1) / bytesPerSecond
    }
}
