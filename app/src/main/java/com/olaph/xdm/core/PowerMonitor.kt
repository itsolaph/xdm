package com.olaph.xdm.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface PowerState {
    /** True while the device is on a charger. */
    val charging: StateFlow<Boolean>
}

/** For callers that do not care about the charger (tests, the default of the manager). */
object AlwaysCharging : PowerState {
    override val charging: StateFlow<Boolean> = MutableStateFlow(true)
}

/** Follows the charger for as long as the process lives. */
class PowerMonitor(context: Context) : PowerState {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val _charging = MutableStateFlow(read())

    override val charging: StateFlow<Boolean> = _charging

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        _charging.value = when (intent.action) {
                            Intent.ACTION_POWER_CONNECTED -> true
                            Intent.ACTION_POWER_DISCONNECTED -> false
                            else -> read()
                        }
                    }
                },
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun read(): Boolean = runCatching { battery.isCharging }.getOrDefault(false)
}
