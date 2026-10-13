package com.olaph.xdm.crash

import java.io.File

/**
 * Reports waiting for upload, one JSON file each: `pending-<millis>.json`, or `pending-<millis>-<n>.json` after
 * n failed uploads (the attempt count lives in the name, so no sidecar is needed).
 */
class CrashQueue(
    private val dir: File,
    private val maxFiles: Int = MAX_PENDING_REPORTS,
    private val maxAttempts: Int = MAX_UPLOAD_ATTEMPTS,
) {
    private class Entry(val file: File, val millis: Long, val attempts: Int)

    private val name = Regex("""pending-(\d+)(?:-(\d+))?\.json""")

    /** Plain blocking file IO and no locks, so it is safe to call from the crash handler. */
    fun write(json: String, nowMillis: Long = System.currentTimeMillis()): File? {
        dir.mkdirs()
        var millis = nowMillis
        while (File(dir, "pending-$millis.json").exists()) millis++
        val target = File(dir, "pending-$millis.json")
        // Written under a name the listing ignores, then renamed, so a kill mid-write leaves no half report.
        val tmp = File(dir, "pending-$millis.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }
        trim()
        return target
    }

    fun pendingCount(): Int = entries().size

    fun clear() {
        entries().forEach { it.file.delete() }
    }

    /** Uploads oldest first; deletes on 2xx/403, counts a failure on anything else. */
    @Synchronized
    fun flush(uploader: CrashUploader) {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        trim()
        for (e in entries()) {
            val body = runCatching { e.file.readBytes() }.getOrNull()
            if (body == null || body.isEmpty()) {
                e.file.delete()
                continue
            }
            when (uploader.upload(body)) {
                UploadResult.ACCEPTED, UploadResult.REJECTED -> e.file.delete()
                UploadResult.RETRY -> {
                    val failed = e.attempts + 1
                    if (failed >= maxAttempts) {
                        e.file.delete()
                    } else {
                        e.file.renameTo(File(dir, "pending-${e.millis}-$failed.json"))
                    }
                }
            }
        }
    }

    private fun entries(): List<Entry> =
        dir.listFiles().orEmpty().mapNotNull { f ->
            val m = name.matchEntire(f.name) ?: return@mapNotNull null
            Entry(f, m.groupValues[1].toLong(), m.groupValues[2].toIntOrNull() ?: 0)
        }.sortedBy { it.millis }

    private fun trim() {
        val all = entries()
        all.take((all.size - maxFiles).coerceAtLeast(0)).forEach { it.file.delete() }
    }
}
