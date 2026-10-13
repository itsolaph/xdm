package com.olaph.xdm.crash

// The relay URL and key come from the build (BuildConfig.CRASH_RELAY_URL / CRASH_RELAY_KEY, see README).
// Without a URL, crash reporting is off in that build.
internal const val MAX_PENDING_REPORTS = 10
internal const val MAX_UPLOAD_ATTEMPTS = 5
internal const val MAX_TRACE_CHARS = 100_000
internal const val MAX_BODY_BYTES = 200_000
