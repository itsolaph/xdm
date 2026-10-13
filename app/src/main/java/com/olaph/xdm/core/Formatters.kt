package com.olaph.xdm.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Formatters {
    fun bytes(n: Long): String {
        if (n < 0) return "?"
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = n.toDouble()
        var i = -1
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024; i++
        }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    fun eta(seconds: Long): String = when {
        seconds < 0 -> "--"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        seconds < 86_400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 86_400}d ${(seconds % 86_400) / 3600}h"
    }

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
