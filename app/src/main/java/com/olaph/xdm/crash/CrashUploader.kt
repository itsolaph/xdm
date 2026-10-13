package com.olaph.xdm.crash

import com.olaph.xdm.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

enum class UploadResult { ACCEPTED, REJECTED, RETRY }

/** Blocking; callers run it off the main thread. Must not throw. */
fun interface CrashUploader {
    fun upload(body: ByteArray): UploadResult
}

class HttpCrashUploader(
    private val url: String = BuildConfig.CRASH_RELAY_URL,
    private val key: String = BuildConfig.CRASH_RELAY_KEY,
) : CrashUploader {
    override fun upload(body: ByteArray): UploadResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-App-Key", key)
            }
            conn.outputStream.use { it.write(body) }
            when (conn.responseCode) {
                in 200..299 -> UploadResult.ACCEPTED
                403 -> UploadResult.REJECTED
                else -> UploadResult.RETRY
            }
        } catch (_: Exception) {
            UploadResult.RETRY
        } finally {
            conn?.disconnect()
        }
    }
}
