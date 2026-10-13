package com.olaph.xdm.engine

/** Small JVM-only extension <-> MIME table so the engine does not depend on android.webkit. */
object MimeTypes {
    const val APK = "application/vnd.android.package-archive"

    private val byExtension = mapOf(
        "apk" to APK,
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "bz2" to "application/x-bzip2",
        "xz" to "application/x-xz",
        "iso" to "application/x-iso9660-image",
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "epub" to "application/epub+zip",
        "json" to "application/json",
        "txt" to "text/plain",
        "html" to "text/html",
        "csv" to "text/csv",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "m4v" to "video/x-m4v",
        "flv" to "video/x-flv",
        "ts" to "video/mp2t",
        "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "wav" to "audio/x-wav",
        "flac" to "audio/flac",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "exe" to "application/vnd.microsoft.portable-executable",
        "msi" to "application/x-msi",
        "dmg" to "application/x-apple-diskimage",
        "deb" to "application/vnd.debian.binary-package",
    )

    // First entry wins when several extensions share a MIME type (jpeg -> jpg).
    private val byMime: Map<String, String> = buildMap {
        for ((ext, mime) in byExtension) putIfAbsent(mime, ext)
        put("image/jpeg", "jpg")
        put("application/x-zip-compressed", "zip")
        put("application/x-rar-compressed", "rar")
        put("audio/x-mpeg", "mp3")
        put("audio/mp3", "mp3")
        put("audio/wav", "wav")
    }

    const val OCTET_STREAM = "application/octet-stream"

    fun forExtension(ext: String): String? = byExtension[ext.lowercase().removePrefix(".")]

    fun forFileName(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return null
        return forExtension(name.substring(dot + 1))
    }

    fun extensionFor(mime: String?): String? {
        if (mime == null) return null
        return byMime[normalize(mime)]
    }

    /** Strips parameters ("; charset=...") and lowercases. */
    fun normalize(contentType: String): String = contentType.substringBefore(';').trim().lowercase()
}
