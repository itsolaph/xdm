package com.olaph.xdm.core

/** Turns what the user types in "Speed limit" (a number and KB/s or MB/s) into bytes per second, and back. */
object SpeedLimitInput {
    private const val KB = 1024L
    private const val MB = 1024L * 1024
    private const val MAX_BYTES_PER_SEC = 10_000L * MB

    /** Null when [text] is not a positive number (or absurdly large). Decimals are fine ("1.5" MB/s); a comma works too. */
    fun parse(text: String, megabytes: Boolean): Long? {
        val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite() || value <= 0) return null
        val bytes = value * (if (megabytes) MB else KB)
        if (bytes > MAX_BYTES_PER_SEC) return null
        return bytes.toLong().coerceAtLeast(1)
    }

    /** The text and unit to show for a stored limit: whole MB/s when it divides evenly, KB/s otherwise. */
    fun display(bytesPerSec: Long): Pair<String, Boolean> = when {
        bytesPerSec >= MB && bytesPerSec % MB == 0L -> (bytesPerSec / MB).toString() to true
        bytesPerSec >= MB -> "%.1f".format(java.util.Locale.US, bytesPerSec / MB.toDouble()) to true
        else -> (bytesPerSec / KB).coerceAtLeast(1).toString() to false
    }
}
