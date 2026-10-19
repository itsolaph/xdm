package com.olaph.xdm.engine

/** The subfolders of "Sort into subfolders by type". */
enum class FileCategory(val folder: String) {
    VIDEO("Video"),
    AUDIO("Audio"),
    IMAGES("Images"),
    DOCUMENTS("Documents"),
    ARCHIVES("Archives"),
    APPS("Apps"),
    OTHER("Other");

    companion object {
        private val archives = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso", "cab")
        private val apps = setOf("apk", "apks", "xapk", "aab", "exe", "msi", "dmg", "deb", "rpm", "appimage")
        private val documents = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "csv", "epub", "mobi", "md",
        )

        /** The extension decides first (servers often say octet-stream); the MIME type is the fallback. */
        fun of(fileName: String, mimeType: String?): FileCategory {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mime = MimeTypes.forFileName(fileName) ?: mimeType?.let { MimeTypes.normalize(it) }.orEmpty()
            return when {
                ext in apps || mime == MimeTypes.APK -> APPS
                ext in archives || mime in ARCHIVE_MIMES -> ARCHIVES
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime.startsWith("image/") -> IMAGES
                ext in documents || mime == "application/pdf" || mime.startsWith("text/") -> DOCUMENTS
                else -> OTHER
            }
        }

        private val ARCHIVE_MIMES = setOf(
            "application/zip", "application/x-zip-compressed", "application/vnd.rar", "application/x-rar-compressed",
            "application/x-7z-compressed", "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz",
            "application/x-iso9660-image",
        )
    }
}
