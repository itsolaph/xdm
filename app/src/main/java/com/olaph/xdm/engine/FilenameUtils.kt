package com.olaph.xdm.engine

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

object FilenameUtils {
    const val FALLBACK_NAME = "download"
    private const val MAX_NAME_LENGTH = 180
    private const val FORBIDDEN = "\\/:*?\"<>|"

    /**
     * Picks the best file name: Content-Disposition, then last URL path segment, then a name derived
     * from the MIME type. The result is always sanitized and never empty.
     */
    fun resolve(contentDisposition: String?, url: String?, mimeType: String?): String {
        val raw = fromContentDisposition(contentDisposition) ?: url?.let { fromUrl(it) }
        var name = sanitize(raw ?: FALLBACK_NAME)
        if (!hasExtension(name)) {
            MimeTypes.extensionFor(mimeType)?.let { name = "$name.$it" }
        }
        return name
    }

    /** Extracts `filename*` (RFC 5987/6266) in preference to `filename`. */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val params = parseParams(header)
        params.firstOrNull { it.first == "filename*" }?.second?.let { decodeExtValue(it) }
            ?.takeIf { it.isNotBlank() }?.let { return it }
        val plain = params.firstOrNull { it.first == "filename" }?.second?.takeIf { it.isNotBlank() }
            ?: return null
        return if (PCT.containsMatchIn(plain)) percentDecode(plain, Charsets.UTF_8) else plain
    }

    private val PCT = Regex("%[0-9A-Fa-f]{2}")

    private fun parseParams(header: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        val n = header.length
        // skip the disposition type ("attachment", "inline", ...)
        while (i < n && header[i] != ';') i++
        while (i < n) {
            while (i < n && (header[i] == ';' || header[i].isWhitespace())) i++
            val nameStart = i
            while (i < n && header[i] != '=' && header[i] != ';') i++
            val name = header.substring(nameStart, i).trim().lowercase()
            if (i >= n || header[i] != '=') continue
            i++ // '='
            while (i < n && header[i].isWhitespace()) i++
            val value = StringBuilder()
            if (i < n && header[i] == '"') {
                i++
                while (i < n && header[i] != '"') {
                    if (header[i] == '\\' && i + 1 < n) i++
                    value.append(header[i])
                    i++
                }
                i++ // closing quote
            } else {
                val vs = i
                while (i < n && header[i] != ';') i++
                value.append(header.substring(vs, i).trim())
            }
            if (name.isNotEmpty()) out += name to value.toString()
        }
        return out
    }

    /** `UTF-8'en'%E2%82%AC%20rates.pdf` */
    private fun decodeExtValue(value: String): String? {
        val first = value.indexOf('\'')
        val second = if (first >= 0) value.indexOf('\'', first + 1) else -1
        if (first < 0 || second < 0) return percentDecode(value, Charsets.UTF_8)
        val charset = runCatching { Charset.forName(value.substring(0, first).trim()) }
            .getOrDefault(Charsets.UTF_8)
        return percentDecode(value.substring(second + 1), charset)
    }

    /** Percent-decoding only; unlike URLDecoder, '+' is left alone. */
    fun percentDecode(s: String, charset: Charset = Charsets.UTF_8): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                val len = if (Character.isHighSurrogate(c) && i + 1 < s.length) 2 else 1
                out.write(s.substring(i, i + len).toByteArray(charset))
                i += len
            }
        }
        return String(out.toByteArray(), charset)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Last non-empty path segment of the URL, percent-decoded. Null if there is none. */
    fun fromUrl(url: String): String? {
        val noFragment = url.substringBefore('#').substringBefore('?')
        val afterScheme = noFragment.substringAfter("://", noFragment)
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return null // host only
        val segment = afterScheme.substring(slash + 1).trimEnd('/').substringAfterLast('/')
        if (segment.isBlank()) return null
        return percentDecode(segment).takeIf { it.isNotBlank() }
    }

    fun hasExtension(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        return dot > 0 && dot < name.length - 1 && name.length - dot <= 12 && !name.substring(dot).contains(' ')
    }

    /** Removes path components, characters that are illegal on common filesystems and length excess. */
    fun sanitize(name: String): String {
        var n = name.substringAfterLast('/').substringAfterLast('\\')
        n = buildString(n.length) {
            for (c in n) append(
                when {
                    c == '\t' || c == '\n' || c == '\r' -> ' '
                    c.code < 32 || c.code == 127 || FORBIDDEN.indexOf(c) >= 0 -> '_'
                    else -> c
                },
            )
        }
        n = n.replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (n.isEmpty()) return FALLBACK_NAME
        if (n.length > MAX_NAME_LENGTH) {
            val (base, ext) = splitExtension(n)
            val keepExt = if (ext.length <= 20) ext else ""
            n = base.take(MAX_NAME_LENGTH - keepExt.length).trimEnd('.', ' ') + keepExt
        }
        return n
    }

    /** Splits "a.tar.gz" into ("a", ".tar.gz") and "a.b" into ("a", ".b"). No extension -> (name, ""). */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || name.length - dot > 12 || name.substring(dot).contains(' ')) return name to ""
        var base = name.substring(0, dot)
        var ext = name.substring(dot)
        if (base.endsWith(".tar", ignoreCase = true)) {
            ext = base.takeLast(4) + ext
            base = base.dropLast(4)
        }
        return base to ext
    }

    /** `name`, or `name (1).ext`, `name (2).ext`... for the first candidate [exists] rejects. */
    fun uniqueName(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val (base, ext) = splitExtension(name)
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (!exists(candidate)) return candidate
            i++
        }
    }
}
