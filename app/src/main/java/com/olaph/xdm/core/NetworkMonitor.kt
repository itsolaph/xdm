package com.olaph.xdm.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

private val ALWAYS_ONLINE: StateFlow<Boolean> = MutableStateFlow(true)

interface NetworkState {
    /** True while the active network is metered (mobile data, hotspot...). */
    val metered: StateFlow<Boolean>

    /** False while the device has no default network at all. */
    val online: StateFlow<Boolean> get() = ALWAYS_ONLINE

    /** Emits when the default network changes (Wi-Fi <-> mobile) or drops: connections bound to the old one are suspect. */
    val switches: Flow<Unit> get() = emptyFlow()
}

/** Tracks the default network: whether it is metered, whether there is one, and when it changes. */
class NetworkMonitor(context: Context) : NetworkState {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var current: Network? = runCatching { cm.activeNetwork }.getOrNull()
    private val _metered = MutableStateFlow(readMetered())
    private val _online = MutableStateFlow(current != null || !canTell())
    private val _switches = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val metered: StateFlow<Boolean> = _metered
    override val online: StateFlow<Boolean> = _online
    override val switches: Flow<Unit> = _switches

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = current != network
                    current = network
                    _online.value = true
                    _metered.value = readMetered(network)
                    if (changed) _switches.tryEmit(Unit)
                }

                override fun onLost(network: Network) {
                    // The new default network is usually announced before the old one is lost: only the current one counts.
                    if (current != network) return
                    current = null
                    _online.value = false
                    _switches.tryEmit(Unit)
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (network == current) _metered.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            })
        }
    }

    /** If the connectivity service cannot be asked, "offline" would stall every download: assume online then. */
    private fun canTell() = runCatching { cm.activeNetwork; true }.getOrDefault(false)

    private fun readMetered(network: Network? = null): Boolean = runCatching {
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (caps != null) !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) else cm.isActiveNetworkMetered
    }.getOrDefault(false)
}
