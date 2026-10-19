package com.olaph.xdm.engine

import kotlinx.coroutines.delay

/**
 * One cap for every connection of every download. Readers call [acquire] with the bytes they just read and are held
 * back until those bytes fit under the limit; the total stays at [bytesPerSecond] however many connections run.
 *
 * Each call reserves a slot on a shared timeline (cost = bytes / rate) and sleeps until its slot starts. A short burst
 * is allowed after idle time, so a reader that was waiting on the network is not punished twice. The limit can change at
 * any time: the next [acquire] sees it.
 */
class SpeedLimiter(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: suspend (nanos: Long) -> Unit = { delay((it + 999_999) / 1_000_000) },
) {
    /** Bytes per second; 0 or less = no limit. */
    @Volatile var bytesPerSecond: Long = 0
        set(value) {
            field = value.coerceAtLeast(0)
        }

    private val lock = Any()
    private var nextFree = Long.MIN_VALUE

    val active: Boolean get() = bytesPerSecond > 0

    /** Largest read worth issuing right now: about a tenth of a second of the cap, so waits stay short and even. */
    fun maxChunk(default: Int): Int {
        val rate = bytesPerSecond
        if (rate <= 0) return default
        return (rate / 10).coerceIn(MIN_CHUNK.toLong(), default.toLong()).toInt()
    }

    suspend fun acquire(bytes: Int) {
        val rate = bytesPerSecond
        if (rate <= 0 || bytes <= 0) return
        val wait = synchronized(lock) {
            val now = nanoTime()
            val cost = bytes * NANOS / rate
            // Idle time earns at most BURST_NANOS of credit.
            val start = maxOf(nextFree, now - BURST_NANOS)
            nextFree = start + cost
            nextFree - now
        }
        if (wait > 0) sleep(wait)
    }

    private companion object {
        const val NANOS = 1_000_000_000L
        const val BURST_NANOS = 250_000_000L
        const val MIN_CHUNK = 1024
    }
}
