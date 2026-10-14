package com.olaph.xdm.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    // Hashing at full strength is slow on purpose; the logic is the same with fewer rounds.
    private val fast = 1_000

    @Test fun defaultStrengthIsAtLeast120kRoundsOfPbkdf2Sha256() {
        assertTrue(PinHasher.ITERATIONS >= 120_000)
        val h = PinHasher.hash("2468")
        assertEquals(PinHasher.ITERATIONS, h.iterations)
        assertEquals("256-bit key", 32, h.hash.size)
        assertEquals(16, h.salt.size)
        assertTrue(PinHasher.verify("2468", h))
    }

    @Test fun verifiesTheRightPinOnly() {
        val h = PinHasher.hash("135790", iterations = fast)
        assertTrue(PinHasher.verify("135790", h))
        assertFalse(PinHasher.verify("135791", h))
        assertFalse(PinHasher.verify("13579", h))
        assertFalse(PinHasher.verify("", h))
        assertFalse("not a PIN at all", PinHasher.verify("13579a", h))
    }

    @Test fun saltMakesEqualPinsHashDifferently() {
        val a = PinHasher.hash("1111", iterations = fast)
        val b = PinHasher.hash("1111", iterations = fast)
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.hash.contentEquals(b.hash))
        assertTrue(PinHasher.verify("1111", a) && PinHasher.verify("1111", b))
    }

    @Test fun sameSaltAndRoundsGiveTheSameHash() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHasher.hash("4321", salt, fast), PinHasher.hash("4321", salt, fast))
        assertNotEquals(PinHasher.hash("4321", salt, fast), PinHasher.hash("4321", salt, fast + 1))
    }

    @Test fun onlyFourToEightDigitsArePins() {
        listOf("1234", "12345678", "0000").forEach { assertTrue(it, PinHasher.isValidPin(it)) }
        listOf("123", "123456789", "12 34", "abcd", "１２３４", "").forEach { assertFalse(it, PinHasher.isValidPin(it)) }
    }

    @Test fun storedFormRoundTripsAndRejectsGarbage() {
        val h = PinHasher.hash("9876", iterations = fast)
        val decoded = PinHash.decode(h.encode())
        assertEquals(h, decoded)
        assertTrue(PinHasher.verify("9876", decoded!!))
        assertFalse("the PIN itself is never stored", h.encode().contains("9876"))
        listOf(null, "", "x", "1000:abc", "zz:AAAA:AAAA", "0:AAAA:AAAA").forEach { assertNull(it, PinHash.decode(it)) }
    }

    @Test fun aTamperedHashNeverVerifies() {
        val h = PinHasher.hash("5555", iterations = fast)
        val flipped = h.hash.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(PinHasher.verify("5555", h.copy(hash = flipped)))
        assertFalse(PinHasher.verify("5555", h.copy(hash = h.hash.copyOf(16))))
    }
}

class PinAttemptPolicyTest {
    private val t0 = 1_700_000_000_000L

    private fun fail(times: Int, start: AttemptState = AttemptState(), now: Long = t0): AttemptState =
        (1..times).fold(start) { s, _ -> PinAttemptPolicy.onFailure(s, now) }

    @Test fun fourWrongPinsCostNothing() {
        val s = fail(4)
        assertEquals(4, s.failures)
        assertEquals(0, PinAttemptPolicy.remaining(s, t0))
    }

    @Test fun theFifthWrongPinStartsA30SecondPause() {
        val s = fail(5)
        assertEquals(30_000, PinAttemptPolicy.remaining(s, t0))
        assertEquals(10_000, PinAttemptPolicy.remaining(s, t0 + 20_000))
        assertEquals(0, PinAttemptPolicy.remaining(s, t0 + 30_000))
    }

    @Test fun everyFurtherWrongPinDoublesThePauseUpToAnHour() {
        assertEquals(0, PinAttemptPolicy.cooldownFor(4))
        assertEquals(30_000, PinAttemptPolicy.cooldownFor(5))
        assertEquals(60_000, PinAttemptPolicy.cooldownFor(6))
        assertEquals(120_000, PinAttemptPolicy.cooldownFor(7))
        assertEquals(240_000, PinAttemptPolicy.cooldownFor(8))
        assertEquals(PinAttemptPolicy.MAX_COOLDOWN_MS, PinAttemptPolicy.cooldownFor(12))
        assertEquals("no overflow", PinAttemptPolicy.MAX_COOLDOWN_MS, PinAttemptPolicy.cooldownFor(500))

        val afterSix = PinAttemptPolicy.onFailure(fail(5), t0 + 30_000)
        assertEquals(60_000, PinAttemptPolicy.remaining(afterSix, t0 + 30_000))
    }

    @Test fun theRightPinResetsEverything() {
        val s = PinAttemptPolicy.onSuccess()
        assertEquals(AttemptState(), s)
        assertEquals(0, PinAttemptPolicy.remaining(s, t0))
        assertEquals("counting starts over", 0, PinAttemptPolicy.remaining(fail(4, s), t0))
    }

    @Test fun aClockSetBackCannotStretchThePause() {
        val s = fail(5)
        assertEquals(30_000, PinAttemptPolicy.remaining(s, t0 - 3_600_000))
    }
}

class LockStateTest {
    private val minute = 60_000L

    @Test fun startsLockedAndUnlocks() {
        assertFalse(LockState().unlocked)
        assertTrue(LockState().unlock().unlocked)
        assertFalse(LockState().unlock().lock().unlocked)
    }

    @Test fun immediatelyLocksAsSoonAsTheAppLeaves() {
        assertFalse(LockState().unlock().onBackground(now = 100, delayMs = 0).unlocked)
    }

    @Test fun withADelayComingBackInTimeKeepsItOpen() {
        val bg = LockState().unlock().onBackground(now = 1_000, delayMs = minute)
        assertTrue(bg.unlocked)
        val fg = bg.onForeground(now = 1_000 + minute - 1, delayMs = minute)
        assertTrue(fg.unlocked)
        assertNull("the clock is reset", fg.backgroundSince)
        // ...and a later trip to the background starts a fresh delay
        val again = fg.onBackground(now = 10 * minute, delayMs = minute)
        assertTrue(again.onForeground(now = 10 * minute + 30_000, delayMs = minute).unlocked)
    }

    @Test fun withADelayComingBackLateLocks() {
        val bg = LockState().unlock().onBackground(now = 1_000, delayMs = 5 * minute)
        assertFalse(bg.onForeground(now = 1_000 + 5 * minute, delayMs = 5 * minute).unlocked)
    }

    @Test fun theTimerLocksOnlyAfterTheDelay() {
        val bg = LockState().unlock().onBackground(now = 0, delayMs = minute)
        assertTrue("fired early", bg.onTimeout(now = minute - 1, delayMs = minute).unlocked)
        assertFalse(bg.onTimeout(now = minute, delayMs = minute).unlocked)
        assertTrue("a timer left over from an earlier trip does nothing in the foreground",
            bg.onForeground(now = 10, delayMs = minute).onTimeout(now = 2 * minute, delayMs = minute).unlocked)
    }

    @Test fun repeatedBackgroundEventsDoNotRestartTheClock() {
        val bg = LockState().unlock().onBackground(now = 0, delayMs = minute).onBackground(now = 50_000, delayMs = minute)
        assertEquals(0L, bg.backgroundSince)
        assertFalse(bg.onForeground(now = minute, delayMs = minute).unlocked)
    }

    @Test fun screenOffAlwaysLocks() {
        assertFalse(LockState().unlock().onScreenOff().unlocked)
        assertFalse(LockState().unlock().onBackground(now = 0, delayMs = 5 * minute).onScreenOff().unlocked)
    }

    @Test fun lockedStaysLockedWhateverHappens() {
        val s = LockState()
        assertEquals(s, s.onBackground(0, minute))
        assertEquals(s, s.onForeground(10 * minute, minute))
        assertEquals(s, s.onTimeout(10 * minute, minute))
    }

    @Test fun aClockGoingBackwardsLocks() {
        val bg = LockState().unlock().onBackground(now = 10_000, delayMs = minute)
        assertFalse(bg.onForeground(now = 5_000, delayMs = minute).unlocked)
    }
}
