package com.olaph.xdm.engine

object UrlUtils {
    private val URL_REGEX = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}>"

    /** First http(s) URL found in [text], with trailing sentence punctuation removed. */
    fun extractFirstUrl(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL_REGEX.find(text) ?: return null
        val url = match.value.trimEnd { it in TRAILING_PUNCTUATION }
        return url.takeIf { isHttpUrl(it) }
    }

    fun isHttpUrl(s: String): Boolean {
        val rest = when {
            s.startsWith("http://", ignoreCase = true) -> s.substring(7)
            s.startsWith("https://", ignoreCase = true) -> s.substring(8)
            else -> return false
        }
        return rest.isNotEmpty() && !rest.startsWith("/") && rest.none { it.isWhitespace() }
    }

    /** Heuristic used by the clipboard offer: does the URL path end in a typical download extension? */
    fun looksDownloadable(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?')
        val ext = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
        return ext in DOWNLOAD_EXTENSIONS
    }

    val DOWNLOAD_EXTENSIONS = setOf(
        "apk", "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "pdf", "mp4", "mkv", "webm", "avi",
        "mov", "mp3", "m4a", "flac", "wav", "ogg", "exe", "msi", "dmg", "deb", "epub", "doc", "docx",
        "xls", "xlsx", "ppt", "pptx",
    )
}
