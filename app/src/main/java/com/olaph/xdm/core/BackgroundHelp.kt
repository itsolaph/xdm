package com.olaph.xdm.core

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Pointers to the system screens that decide whether downloads survive in the background. Every intent is optional:
 * ROMs move or remove these screens, so each one is tried in turn and a missing one is never an error.
 */
object BackgroundHelp {
    enum class Opened { REQUEST, BATTERY_LIST, AUTOSTART, APP_DETAILS, NOTHING }

    /** True when XDM is exempt from battery optimisation. Unknown counts as exempt, so nobody gets a pointless card. */
    fun isExempt(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(true)

    /**
     * Not exempt: the system dialog that offers the exemption. Already exempt: the system list, where it can be taken
     * back. (This app is not distributed on Google Play, which restricts the direct request.)
     */
    @android.annotation.SuppressLint("BatteryLife") // Play policy only; the app is not on Google Play
    fun openBatterySettings(context: Context): Opened {
        if (!isExempt(context)) {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            if (start(context, request)) return Opened.REQUEST
        }
        if (start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return Opened.BATTERY_LIST
        return if (appDetails(context)) Opened.APP_DETAILS else Opened.NOTHING
    }

    /** The manufacturer's autostart screen on Xiaomi / Redmi / POCO, the app's details page everywhere else. */
    fun openBackgroundTips(context: Context): Opened {
        if (BatteryAdvice.isXiaomiFamily(Build.MANUFACTURER, Build.BRAND)) {
            val attempts = listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
            )
            if (attempts.any { start(context, it) }) return Opened.AUTOSTART
        }
        return if (appDetails(context)) Opened.APP_DETAILS else Opened.NOTHING
    }

    /** Where "Allow exact alarms" lives (Android 12+); false if there is no such screen. */
    fun openExactAlarmSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return start(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
    }

    private fun appDetails(context: Context) =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    /** Starts [intent]; false when nothing can handle it or the system refuses it (non-exported OEM screens). */
    private fun start(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
