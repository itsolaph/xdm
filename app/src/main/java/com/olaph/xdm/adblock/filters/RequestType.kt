package com.olaph.xdm.adblock.filters

/**
 * Request types as bit flags, matching the filter-list option names. A rule stores the set of types it applies
 * to as a mask; a request has exactly one type.
 */
object RequestType {
    const val OTHER = 1
    const val SCRIPT = 1 shl 1
    const val IMAGE = 1 shl 2
    const val STYLESHEET = 1 shl 3
    const val XHR = 1 shl 4
    const val SUBDOCUMENT = 1 shl 5
    const val MEDIA = 1 shl 6
    const val FONT = 1 shl 7
    const val OBJECT = 1 shl 8
    const val PING = 1 shl 9
    const val WEBSOCKET = 1 shl 10

    /** A top-level page load (the address bar, links, redirects). Only `$document` / host-level rules apply. */
    const val DOCUMENT = 1 shl 11

    /** A window opened by the page. Only `$popup` / `$document` / host-level rules apply. */
    const val POPUP = 1 shl 12

    /**
     * Sub-resource of unknown type (WebView does not tell): only rules without type options match it, so a
     * `$image` rule never blocks a request we merely guessed to be an image.
     */
    const val UNKNOWN = 1 shl 13

    /** Every sub-resource type: what `~script` style negations are taken from. */
    const val RESOURCES = OTHER or SCRIPT or IMAGE or STYLESHEET or XHR or SUBDOCUMENT or MEDIA or FONT or OBJECT or PING or WEBSOCKET

    /** Mask of a rule without type options. */
    const val TYPELESS = RESOURCES or UNKNOWN

    /** `$all`. */
    const val ALL = TYPELESS or DOCUMENT or POPUP

    /** Option name → type bit (aliases included). */
    val BY_OPTION: Map<String, Int> = mapOf(
        "script" to SCRIPT,
        "image" to IMAGE,
        "stylesheet" to STYLESHEET, "css" to STYLESHEET,
        "xmlhttprequest" to XHR, "xhr" to XHR,
        "subdocument" to SUBDOCUMENT, "frame" to SUBDOCUMENT,
        "media" to MEDIA,
        "font" to FONT,
        "object" to OBJECT, "object-subrequest" to OBJECT,
        "ping" to PING, "beacon" to PING,
        "websocket" to WEBSOCKET,
        "other" to OTHER,
        "document" to DOCUMENT, "doc" to DOCUMENT,
        "popup" to POPUP, "popunder" to POPUP,
    )

    fun name(type: Int): String = when (type) {
        OTHER -> "other"; SCRIPT -> "script"; IMAGE -> "image"; STYLESHEET -> "stylesheet"; XHR -> "xhr"
        SUBDOCUMENT -> "subdocument"; MEDIA -> "media"; FONT -> "font"; OBJECT -> "object"; PING -> "ping"
        WEBSOCKET -> "websocket"; DOCUMENT -> "document"; POPUP -> "popup"; UNKNOWN -> "unknown"
        else -> "mixed"
    }
}

/**
 * Infers the type of a WebView request. `shouldInterceptRequest` does not say what a request is for, so this
 * uses, in order of reliability: main frame, `Sec-Fetch-Dest`, `X-Requested-With: XMLHttpRequest`, the
 * `Accept` header, and the file extension. Anything else is [RequestType.UNKNOWN]. Pure; header names are
 * matched case-insensitively.
 */
object RequestTypeInference {
    fun infer(url: String, isMainFrame: Boolean, headers: Map<String, String> = emptyMap()): Int {
        if (isMainFrame) return RequestType.DOCUMENT
        val scheme = url.substringBefore(':', "").lowercase()
        if (scheme == "ws" || scheme == "wss") return RequestType.WEBSOCKET

        header(headers, "Sec-Fetch-Dest")?.let { dest ->
            fromFetchDest(dest.trim().lowercase())?.let { return it }
        }
        if (header(headers, "X-Requested-With").equals("XMLHttpRequest", ignoreCase = true)) return RequestType.XHR

        header(headers, "Accept")?.lowercase()?.let { accept ->
            when {
                accept.startsWith("text/css") -> return RequestType.STYLESHEET
                accept.startsWith("text/html") || accept.startsWith("application/xhtml") -> return RequestType.SUBDOCUMENT
                accept.startsWith("image/") -> return RequestType.IMAGE
                accept.startsWith("application/json") -> return RequestType.XHR
            }
        }
        return fromExtension(url)
    }

    fun fromFetchDest(dest: String): Int? = when (dest) {
        "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" -> RequestType.SCRIPT
        "style", "xslt" -> RequestType.STYLESHEET
        "image" -> RequestType.IMAGE
        "font" -> RequestType.FONT
        "iframe", "frame", "fencedframe", "document" -> RequestType.SUBDOCUMENT
        "audio", "video", "track" -> RequestType.MEDIA
        "object", "embed" -> RequestType.OBJECT
        "empty" -> RequestType.XHR
        "report" -> RequestType.PING
        "manifest" -> RequestType.OTHER
        else -> null
    }

    fun fromExtension(url: String): Int {
        val path = url.substringBefore('#').substringBefore('?')
        val slash = path.lastIndexOf('/')
        val name = if (slash >= 0) path.substring(slash + 1) else path
        val dot = name.lastIndexOf('.')
        if (dot < 0 || slash < path.indexOf("://") + 3) return RequestType.UNKNOWN // no extension, or only a host
        return when (name.substring(dot + 1).lowercase()) {
            "js", "mjs", "cjs" -> RequestType.SCRIPT
            "css" -> RequestType.STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "apng", "jfif" -> RequestType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> RequestType.FONT
            "mp4", "m4v", "m4s", "webm", "mkv", "mov", "m3u8", "m3u", "ts", "mpd", "mp3", "m4a", "aac", "ogg", "oga",
            "ogv", "opus", "flac", "wav", "vtt" -> RequestType.MEDIA
            "swf" -> RequestType.OBJECT
            else -> RequestType.UNKNOWN
        }
    }

    private fun header(headers: Map<String, String>, name: String): String? {
        headers[name]?.let { return it }
        for ((k, v) in headers) if (k.equals(name, ignoreCase = true)) return v
        return null
    }
}
