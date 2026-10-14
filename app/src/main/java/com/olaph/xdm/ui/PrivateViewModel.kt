package com.olaph.xdm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olaph.xdm.AppContainer
import com.olaph.xdm.XdmApp
import com.olaph.xdm.core.MoveResult
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.vault.AttemptState
import com.olaph.xdm.vault.UnlockResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The private folder's screen: lock, its items (finished and in progress), and what can be done with them. */
class PrivateViewModel(app: Application) : AndroidViewModel(app) {
    val container: AppContainer = (app as XdmApp).container
    private val manager = container.manager
    private val lock = container.vaultLock

    val unlocked: StateFlow<Boolean> = lock.unlocked
    /** Null until known. */
    val pinSet: StateFlow<Boolean?> = lock.pinSet.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val biometricEnabled: StateFlow<Boolean> = lock.biometricEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val attempts: StateFlow<AttemptState> = lock.attempts.stateIn(viewModelScope, SharingStarted.Eagerly, AttemptState())

    /** Nothing private is read from the database while the folder is locked. */
    val items: StateFlow<List<DownloadItem>> =
        combine(container.dao.observeAll(), manager.live, lock.unlocked) { list, live, open ->
            if (!open) emptyList() else list.filter { it.isPrivate }.map { DownloadItem(it, live[it.id]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Shows [text] in the screen's snackbar. */
    fun say(text: String) {
        viewModelScope.launch { _messages.send(text) }
    }

    // ---- lock ----

    suspend fun setupPin(pin: String): Boolean = lock.setPin(pin)
    suspend fun unlock(pin: String): UnlockResult = lock.tryUnlock(pin)
    fun unlockWithBiometric() = lock.unlockWithBiometric()
    fun lockNow() {
        clearSelection()
        lock.lock()
    }

    suspend fun changePin(old: String, new: String): UnlockResult = lock.changePin(old, new)
    fun setBiometricEnabled(enabled: Boolean) = viewModelScope.launch { lock.setBiometricEnabled(enabled) }

    /** Forgotten PIN: every private download (running ones too), every private file and the PIN go. No way back. */
    fun reset() {
        clearSelection()
        container.scope.launch {
            manager.deleteMany(container.dao.privateIds(), deleteFiles = true, privateItems = true)
            container.vault.wipe()
            Thumbnails.forgetPrivate(getApplication())
            lock.clear()
            _messages.send("The private folder was reset")
        }
    }

    // ---- items ----

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected

    fun toggleSelected(id: Long) = _selected.update { if (id in it) it - id else it + id }
    fun selectAll(ids: Collection<Long>) { _selected.value = ids.toSet() }
    fun clearSelection() { _selected.value = emptySet() }

    fun pause(id: Long) = manager.pause(id)
    fun resume(id: Long) = manager.resume(id)
    fun cancel(id: Long) = manager.cancel(id)

    /** Deletes private items only (files included); runs in the app scope so leaving the screen does not cut it short. */
    fun delete(ids: Collection<Long>) {
        _selected.update { it - ids.toSet() }
        container.scope.launch {
            val report = manager.deleteMany(ids, deleteFiles = true, privateItems = true)
            Thumbnails.forget(getApplication(), ids)
            _messages.send(DeleteScope.message(report.removed, report.freedBytes, report.failed))
        }
    }

    fun moveOut(d: DownloadEntity) {
        container.scope.launch {
            when (val r = manager.moveOutOfPrivate(d.id)) {
                is MoveResult.Moved -> {
                    Thumbnails.forget(getApplication(), listOf(d.id))
                    _messages.send("Moved to Download/XDM: ${r.fileName}")
                }
                is MoveResult.Failed -> _messages.send("Could not move it out (${r.reason}). It stays in the private folder.")
                is MoveResult.PublicCopyLeft -> Unit // only happens when moving in
            }
        }
    }

    fun rename(d: DownloadEntity, name: String) {
        viewModelScope.launch {
            val newName = manager.renamePrivate(d.id, name)
            if (newName == null) say("Could not rename the file") else Thumbnails.forget(getApplication(), listOf(d.id))
        }
    }
}
