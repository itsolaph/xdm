package com.olaph.xdm.vault

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.olaph.xdm.data.SettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UnlockResult {
    data object Ok : UnlockResult
    /** Wrong PIN; [cooldownMs] > 0 when that one started a cooldown. */
    data class Wrong(val attemptsLeft: Int, val cooldownMs: Long) : UnlockResult
    data class CoolingDown(val remainingMs: Long) : UnlockResult
    data object NoPin : UnlockResult
}

private val Context.vaultStore: DataStore<Preferences> by preferencesDataStore(name = "vault")

/**
 * The private folder's lock: PIN (salted PBKDF2 hash in its own DataStore), wrong-PIN cooldown, and auto-lock when the
 * app goes to the background (after the delay chosen in Settings) or the screen turns off. The rules are in [LockState]
 * and [PinAttemptPolicy]; this class only feeds them events.
 */
class VaultLock(
    private val app: Application,
    private val scope: CoroutineScope,
    settings: SettingsSource,
) {
    private object Keys {
        val pin = stringPreferencesKey("pin")
        val failures = intPreferencesKey("failures")
        val lockedUntil = longPreferencesKey("locked_until")
        val biometric = booleanPreferencesKey("biometric")
    }

    private val store get() = app.vaultStore
    private val state = MutableStateFlow(LockState())
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    @Volatile private var delayMs = 0L
    private var timer: Job? = null

    val pinSet: Flow<Boolean> = store.data.map { PinHash.decode(it[Keys.pin]) != null }
    val biometricEnabled: Flow<Boolean> = store.data.map { it[Keys.biometric] == true }
    val attempts: Flow<AttemptState> = store.data.map { AttemptState(it[Keys.failures] ?: 0, it[Keys.lockedUntil] ?: 0) }

    init {
        scope.launch { settings.flow.collect { delayMs = it.autoLockDelay.millis } }
    }

    private fun now() = SystemClock.elapsedRealtime()

    private fun transition(f: (LockState) -> LockState) {
        state.update(f)
        _unlocked.value = state.value.unlocked
    }

    /** Call once on the main thread (app start). */
    fun start() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                val d = delayMs
                transition { it.onBackground(now(), d) }
                timer?.cancel()
                if (d > 0 && state.value.unlocked) {
                    timer = scope.launch {
                        delay(d)
                        transition { it.onTimeout(now(), d) }
                    }
                }
            }

            override fun onStart(owner: LifecycleOwner) {
                timer?.cancel()
                transition { it.onForeground(now(), delayMs) }
            }
        })
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) transition { it.onScreenOff() }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun isUnlocked(): Boolean = state.value.unlocked

    fun lock() = transition { it.lock() }

    /** After a successful BiometricPrompt. */
    fun unlockWithBiometric() = transition { it.unlock() }

    suspend fun hasPin(): Boolean = pinSet.first()

    /** First-time setup; refuses to replace an existing PIN (that needs [changePin]). */
    suspend fun setPin(pin: String): Boolean {
        if (!PinHasher.isValidPin(pin)) return false
        val hash = withContext(Dispatchers.Default) { PinHasher.hash(pin) }
        var stored = false
        store.edit {
            if (PinHash.decode(it[Keys.pin]) == null) {
                it[Keys.pin] = hash.encode()
                it[Keys.failures] = 0
                it[Keys.lockedUntil] = 0
                stored = true
            }
        }
        if (stored) transition { it.unlock() }
        return stored
    }

    suspend fun tryUnlock(pin: String): UnlockResult {
        val result = check(pin)
        if (result == UnlockResult.Ok) transition { it.unlock() }
        return result
    }

    suspend fun changePin(old: String, new: String): UnlockResult {
        if (!PinHasher.isValidPin(new)) return UnlockResult.Wrong(PinAttemptPolicy.FREE_ATTEMPTS, 0)
        val result = check(old)
        if (result == UnlockResult.Ok) {
            val hash = withContext(Dispatchers.Default) { PinHasher.hash(new) }
            store.edit { it[Keys.pin] = hash.encode() }
        }
        return result
    }

    private suspend fun check(pin: String): UnlockResult {
        val prefs = store.data.first()
        val stored = PinHash.decode(prefs[Keys.pin]) ?: return UnlockResult.NoPin
        val wall = System.currentTimeMillis()
        val attempt = AttemptState(prefs[Keys.failures] ?: 0, prefs[Keys.lockedUntil] ?: 0)
        PinAttemptPolicy.remaining(attempt, wall).takeIf { it > 0 }?.let { return UnlockResult.CoolingDown(it) }
        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, stored) }
        val next = if (ok) PinAttemptPolicy.onSuccess() else PinAttemptPolicy.onFailure(attempt, wall)
        store.edit {
            it[Keys.failures] = next.failures
            it[Keys.lockedUntil] = next.lockedUntil
        }
        if (ok) return UnlockResult.Ok
        val left = (PinAttemptPolicy.FREE_ATTEMPTS - next.failures).coerceAtLeast(0)
        return UnlockResult.Wrong(left, PinAttemptPolicy.remaining(next, wall))
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        store.edit { it[Keys.biometric] = enabled }
    }

    /** "Reset private folder": the PIN and every setting of the lock go; the caller deletes the files. */
    suspend fun clear() {
        store.edit { it.clear() }
        lock()
    }
}
