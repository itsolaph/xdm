package com.olaph.xdm.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface NetworkState {
    /** True while the active network is metered (mobile data, hotspot...). */
    val metered: StateFlow<Boolean>
}

/** Tracks whether the active network is metered. */
class NetworkMonitor(context: Context) : NetworkState {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _metered = MutableStateFlow(readMetered())

    override val metered: StateFlow<Boolean> = _metered

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = update()
                override fun onLost(network: Network) = update()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update()
            })
        }
    }

    private fun update() {
        _metered.value = readMetered()
    }

    private fun readMetered(): Boolean = runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
}
