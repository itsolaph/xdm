package com.olaph.xdm.vault

import kotlin.math.min

/**
 * Whether the private folder is open. Pure: times come from the caller (a monotonic clock in the app), so every
 * transition is unit-tested. Locked is the starting state and where every doubt ends up.
 */
data class LockState(val unlocked: Boolean = false, val backgroundSince: Long? = null) {
    fun unlock() = LockState(unlocked = true)

    fun lock() = LockState()

    /** The app left the screen. With no delay that locks at once; otherwise the clock starts. */
    fun onBackground(now: Long, delayMs: Long): LockState = when {
        !unlocked -> this
        delayMs <= 0 -> lock()
        backgroundSince != null -> this
        else -> copy(backgroundSince = now)
    }

    /** Back on screen: locked if the delay ran out meanwhile (the timer may not have fired, e.g. in deep sleep). */
    fun onForeground(now: Long, delayMs: Long): LockState = when {
        !unlocked -> this
        expired(now, delayMs) -> lock()
        else -> copy(backgroundSince = null)
    }

    /** The background timer fired. */
    fun onTimeout(now: Long, delayMs: Long): LockState = if (unlocked && expired(now, delayMs)) lock() else this

    fun onScreenOff(): LockState = lock()

    private fun expired(now: Long, delayMs: Long): Boolean {
        val since = backgroundSince ?: return false
        // A clock that went backwards (should not happen with a monotonic one) counts as expired.
        return now - since >= delayMs || now < since
    }
}

/** Wrong-PIN bookkeeping, persisted so that restarting the app does not reset it. */
data class AttemptState(val failures: Int = 0, val lockedUntil: Long = 0)

/** After [FREE_ATTEMPTS] wrong PINs in a row: 30 s, and every further wrong PIN doubles it (up to an hour). */
object PinAttemptPolicy {
    const val FREE_ATTEMPTS = 5
    const val BASE_COOLDOWN_MS = 30_000L
    const val MAX_COOLDOWN_MS = 3_600_000L

    fun cooldownFor(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0
        val doublings = min(failures - FREE_ATTEMPTS, 20)
        return min(BASE_COOLDOWN_MS shl doublings, MAX_COOLDOWN_MS)
    }

    /** Milliseconds until the next try is allowed (0 = now). Wall-clock time: a clock set back cannot extend it past the cooldown. */
    fun remaining(state: AttemptState, now: Long): Long {
        val left = state.lockedUntil - now
        return if (left <= 0) 0 else min(left, cooldownFor(state.failures))
    }

    fun onFailure(state: AttemptState, now: Long): AttemptState {
        val failures = state.failures + 1
        val cooldown = cooldownFor(failures)
        return AttemptState(failures, if (cooldown > 0) now + cooldown else 0)
    }

    fun onSuccess(): AttemptState = AttemptState()
}
