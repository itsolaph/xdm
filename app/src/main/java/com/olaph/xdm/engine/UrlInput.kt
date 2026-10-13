package com.olaph.xdm.engine

import java.net.URLEncoder

enum class SearchEngine(val label: String, private val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s");

    fun searchUrl(query: String): String = template.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))
}

/** Turns what the user typed in the address bar into something a WebView can load. */
object UrlInput {
    private val SCHEME_WITH_SLASHES = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://.+""")
    private val PORT_ONLY = Regex("""^\d{1,5}$""")
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val DOMAIN = Regex("""^([a-zA-Z0-9]([a-zA-Z0-9\-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$""")

    /** Schemes without `//` that are passed through (handled by [com.olaph.xdm.browser.ExternalLinks]). */
    private val OPAQUE_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo", "about")

    /** Schemes that must never be typed into / loaded from the address bar. */
    private val BLOCKED_SCHEMES = setOf("javascript", "data", "file", "content", "blob", "vbscript")

    /**
     * - `http://x`, `https://x`: kept as is
     * - `example.com`, `example.com/path`: `https://` is added
     * - `localhost:8080`, `192.168.1.1`: `http://` is added (local servers rarely have TLS)
     * - anything else (spaces, no dot): a search with [engine]
     */
    fun resolve(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return "about:blank"

        val scheme = text.substringBefore(':', "").lowercase()
        if (text.contains(':') && scheme.isNotEmpty() && scheme[0].isLetter() && !text.any { it.isWhitespace() }) {
            val rest = text.substringAfter(':')
            val looksLikeHostPort = rest.isNotEmpty() && PORT_ONLY.matches(rest.substringBefore('/').substringBefore('?'))
            if (!looksLikeHostPort) {
                when {
                    scheme in BLOCKED_SCHEMES -> return engine.searchUrl(text)
                    SCHEME_WITH_SLASHES.matches(text) -> return text
                    scheme in OPAQUE_SCHEMES && rest.isNotEmpty() -> return text
                }
            }
        }
        if (text.any { it.isWhitespace() }) return engine.searchUrl(text)

        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
        val (host, port) = splitHostPort(authority)
        if (host == null) return engine.searchUrl(text)
        return when {
            host.equals("localhost", ignoreCase = true) || IPV4.matches(host) || host.startsWith("[") -> "http://$text"
            DOMAIN.matches(host) && (port == null || port.matches(PORT_ONLY)) -> "https://$text"
            else -> engine.searchUrl(text)
        }
    }

    private fun splitHostPort(authority: String): Pair<String?, String?> {
        if (authority.startsWith("[")) { // IPv6 literal
            val close = authority.indexOf(']')
            if (close < 0) return null to null
            return authority.substring(0, close + 1) to authority.substring(close + 1).removePrefix(":").ifEmpty { null }
        }
        val i = authority.lastIndexOf(':')
        if (i < 0) return authority.ifEmpty { null } to null
        return authority.substring(0, i).ifEmpty { null } to authority.substring(i + 1)
    }

    /** Host of an http(s) URL without a leading `www.`, for compact display. */
    fun displayHost(url: String?): String? {
        if (url == null || !UrlUtils.isHttpUrl(url)) return null
        val afterScheme = url.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        return authority.removePrefix("www.").ifEmpty { null }
    }
}

/** Mobile ⇄ desktop User-Agent switching for the "Desktop site" toggle. */
object UserAgents {
    private val ANDROID_TOKEN = Regex("""\(Linux; Android[^)]*\)""")

    fun desktop(mobileUa: String): String {
        if (mobileUa.isBlank()) return DEFAULT_DESKTOP
        val replaced = ANDROID_TOKEN.replace(mobileUa, "(X11; Linux x86_64)")
            .replace(" Mobile Safari", " Safari")
            .replace(" Mobile ", " ")
        return if (replaced == mobileUa) DEFAULT_DESKTOP else replaced
    }

    const val DEFAULT_DESKTOP =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
}
