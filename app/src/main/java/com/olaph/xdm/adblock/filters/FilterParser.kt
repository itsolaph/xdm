package com.olaph.xdm.adblock.filters

/** Result of parsing one filter-list line. */
sealed class ParsedFilter {
    /**
     * A network rule. [plainHost] is set for `||host^` (optionally `$third-party`) block rules without any other
     * constraint: those go into the host sets instead of the token index.
     */
    class Network(
        val rule: NetworkRule,
        val plainHost: String?,
        /** The line with the `badfilter` option removed (only for `$badfilter` rules) or the line itself. */
        val key: String,
        val badfilter: Boolean,
    ) : ParsedFilter()

    /** `##sel` / `dom##sel` / `#@#` element hiding. */
    class Cosmetic(val selector: String, val domains: DomainConstraint?, val exception: Boolean) : ParsedFilter()

    class Skipped(val reason: SkipReason) : ParsedFilter()

    data object Comment : ParsedFilter()
}

enum class SkipReason {
    /** Scriptlets, CSS injection, HTML filtering, JS rules. */
    UNSUPPORTED_COSMETIC,
    /** `:has-text()`, `:upward()`, `:xpath()`, `:style()`… or anything that is not plain CSS. */
    PROCEDURAL,
    /** An option we cannot honour without changing the rule's meaning (`csp`, `removeparam`, `header`, …). */
    UNSUPPORTED_OPTION,
    /** Syntax we do not understand (or a rule that would match everything). */
    INVALID,
}

/**
 * Parser for the EasyList / AdGuard / uBlock Origin subset described in the README. Pure and total: it never
 * throws; anything it cannot represent faithfully is returned as [ParsedFilter.Skipped].
 */
object FilterParser {
    private val skippedCache = SkipReason.entries.associateWith { ParsedFilter.Skipped(it) }
    private fun skip(r: SkipReason) = skippedCache.getValue(r)

    /** Unsupported cosmetic separators (checked after the domain part). */
    private val UNSUPPORTED_MARKERS = listOf("#?#", "#@?#", "#$#", "#@$#", "#%#", "#@%#", "#$?#", "#@$?#")

    /** Procedural / extended pseudo-classes and AdGuard extended-CSS syntax. */
    private val PROCEDURAL = listOf(
        ":has-text(", ":contains(", ":-abp-", ":upward(", ":xpath(", ":matches-css", ":matches-attr(",
        ":matches-path(", ":matches-prop", ":matches-media(", ":min-text-length(", ":nth-ancestor(", ":others(",
        ":remove(", ":remove-attr(", ":remove-class(", ":style(", ":watch-attr(", ":watch-attrs(", ":if(", ":if-not(",
        ":shadow", ":spath(", "[-ext-", ":properties(", ":-ext-",
    )

    /** Options that change what a rule does in ways we cannot reproduce: the rule is dropped. */
    private val UNSUPPORTED_OPTIONS = setOf(
        "csp", "removeparam", "queryprune", "replace", "header", "removeheader", "permissions", "cookie", "jsonprune",
        "xmlprune", "hls", "urltransform", "uritransform", "referrerpolicy", "rewrite", "inline-script", "inline-font",
        "webrtc", "method", "to", "denyallow", "app", "network", "content", "jsinject", "extension", "urlblock",
        "genericblock", "stealth", "cname", "ipaddress", "client", "dnstype", "dnsrewrite", "ctag", "redirect-rule",
        "empty", "mp4", "frame-ancestors",
    )

    /** Accepted but meaningless here. */
    private val IGNORED_OPTIONS = setOf("collapse", "~collapse", "reason", "~match-case")

    fun parse(line: String): ParsedFilter {
        val t = line.trim()
        if (t.isEmpty() || t[0] == '!' || t[0] == '[' && t.startsWith("[Adblock") || t == "#" || t.startsWith("# ")) {
            return ParsedFilter.Comment
        }
        if (t.length > MAX_LINE) return skip(SkipReason.INVALID)
        if (t.startsWith("[$")) return skip(SkipReason.UNSUPPORTED_COSMETIC) // AdGuard `[$path=…]` modifiers
        cosmeticSplit(t)?.let { return it }
        if (t.contains("$$") || t.contains("$@$")) return skip(SkipReason.UNSUPPORTED_COSMETIC) // HTML filtering
        return parseNetwork(t)
    }

    // ---- cosmetic ----

    /** Returns a parse result if [t] is a cosmetic rule of any kind, or null for network rules. */
    private fun cosmeticSplit(t: String): ParsedFilter? {
        var idx = t.indexOf('#')
        while (idx >= 0) {
            val domainPart = t.substring(0, idx)
            if (!isDomainList(domainPart)) return null // `#` inside a URL pattern
            val rest = t.substring(idx)
            if (UNSUPPORTED_MARKERS.any { rest.startsWith(it) }) return skip(SkipReason.UNSUPPORTED_COSMETIC)
            val exception = rest.startsWith("#@#")
            if (exception || rest.startsWith("##")) {
                val selector = rest.substring(if (exception) 3 else 2).trim()
                return cosmetic(domainPart, selector, exception)
            }
            idx = t.indexOf('#', idx + 1)
        }
        return null
    }

    private fun cosmetic(domainPart: String, selector: String, exception: Boolean): ParsedFilter {
        if (selector.isEmpty()) return skip(SkipReason.INVALID)
        if (selector.startsWith("+js(") || selector.startsWith("^") || selector.startsWith("%#")) return skip(SkipReason.UNSUPPORTED_COSMETIC)
        if (!isPlainCss(selector)) return skip(SkipReason.PROCEDURAL)
        val domains = if (domainPart.isBlank()) null else DomainConstraint.parse(domainPart, ',') ?: return skip(SkipReason.INVALID)
        return ParsedFilter.Cosmetic(selector, domains, exception)
    }

    /** Cheap validity check: no procedural pseudo-classes and nothing that could escape a CSS rule. */
    fun isPlainCss(selector: String): Boolean {
        if (selector.length > MAX_SELECTOR) return false
        // Each selector becomes its own `sel{display:none!important}` rule: braces or a comment opener could
        // break out of it, everything else at worst makes that one rule invalid (the CSS parser drops it).
        for (c in selector) if (c == '{' || c == '}' || c == '\n' || c == '\r') return false
        if (selector.contains("/*") || selector.contains("*/")) return false
        if (PROCEDURAL.any { selector.contains(it) }) return false
        // Balanced brackets and quotes, so one bad rule cannot swallow the next in the injected stylesheet.
        var paren = 0; var bracket = 0; var quote = 0.toChar()
        for (c in selector) {
            if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar(); continue }
            when (c) {
                '"', '\'' -> quote = c
                '(' -> paren++
                ')' -> if (--paren < 0) return false
                '[' -> bracket++
                ']' -> if (--bracket < 0) return false
            }
        }
        return paren == 0 && bracket == 0 && quote == 0.toChar()
    }

    private fun isDomainList(s: String): Boolean {
        for (c in s) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_' || c == ',' ||
                c == '~' || c == '*' || c == ':' || c == ' ' || c.code > 0x7F
            ) continue
            return false
        }
        return true
    }

    // ---- network ----

    private fun parseNetwork(line: String): ParsedFilter {
        var t = line
        var flags = 0
        if (t.startsWith("@@")) {
            flags = flags or NetworkRule.EXCEPTION
            t = t.substring(2)
        }
        val (patternPart, optionPart) = splitOptions(t)
        var positiveTypes = 0
        var negativeTypes = 0
        var domains: DomainConstraint? = null
        var badfilter = false
        var key = line
        if (optionPart != null) {
            val opts = optionPart.split(',')
            for (raw in opts) {
                val o = raw.trim()
                if (o.isEmpty()) return skip(SkipReason.INVALID)
                val negated = o.startsWith('~')
                val name = (if (negated) o.substring(1) else o).substringBefore('=').lowercase()
                val value = o.substringAfter('=', "")
                if (o in IGNORED_OPTIONS || name in IGNORED_OPTIONS) continue
                val bit = RequestType.BY_OPTION[name]
                if (bit != null) {
                    if (negated) negativeTypes = negativeTypes or bit else positiveTypes = positiveTypes or bit
                    continue
                }
                when (name) {
                    "third-party", "3p", "strict3p" -> flags = flags or if (negated) NetworkRule.FIRST_PARTY else NetworkRule.THIRD_PARTY
                    "first-party", "1p", "strict1p" -> flags = flags or if (negated) NetworkRule.THIRD_PARTY else NetworkRule.FIRST_PARTY
                    "domain", "from" -> {
                        if (negated) return skip(SkipReason.INVALID)
                        domains = DomainConstraint.parse(value, '|') ?: return skip(SkipReason.INVALID)
                    }
                    "match-case" -> flags = flags or NetworkRule.MATCH_CASE
                    "important" -> flags = flags or NetworkRule.IMPORTANT
                    "all" -> positiveTypes = positiveTypes or RequestType.ALL
                    "redirect" -> flags = flags or NetworkRule.REDIRECT
                    "badfilter" -> badfilter = true
                    "elemhide", "ehide" -> flags = flags or NetworkRule.PAGE_ELEMHIDE
                    "generichide", "ghide" -> flags = flags or NetworkRule.PAGE_GENERICHIDE
                    "specifichide", "shide" -> flags = flags or NetworkRule.PAGE_SPECIFICHIDE
                    else -> return skip(if (name in UNSUPPORTED_OPTIONS) SkipReason.UNSUPPORTED_OPTION else SkipReason.INVALID)
                }
            }
            if (badfilter) key = line.substring(0, line.length - optionPart.length) + opts.filterNot { it.trim().lowercase() == "badfilter" }.joinToString(",")
            if (key.endsWith("$")) key = key.dropLast(1)
        }

        val exception = flags and NetworkRule.EXCEPTION != 0
        val pageFlags = flags and NetworkRule.PAGE_FLAGS
        if (pageFlags != 0 && !exception) return skip(SkipReason.INVALID) // `$elemhide` only makes sense on @@ rules
        if (exception && flags and NetworkRule.REDIRECT != 0) return skip(SkipReason.UNSUPPORTED_OPTION) // `@@…$redirect` only cancels redirects
        if (positiveTypes and RequestType.DOCUMENT != 0 && exception) flags = flags or NetworkRule.PAGE_DOCUMENT

        val types = when {
            positiveTypes != 0 -> positiveTypes and negativeTypes.inv()
            negativeTypes != 0 -> RequestType.RESOURCES and negativeTypes.inv()
            pageFlags != 0 -> 0 // pure page-level exception
            else -> RequestType.TYPELESS
        }
        if (types == 0 && flags and NetworkRule.PAGE_FLAGS == 0) return skip(SkipReason.INVALID)

        // Pattern
        var p = patternPart
        var anchor = NetworkRule.ANCHOR_NONE
        var anchorEnd = false
        var regex: String? = null
        if (p.length > 2 && p.startsWith('/') && p.endsWith('/')) {
            val body = p.substring(1, p.length - 1)
            if (body.none { it in REGEX_META }) {
                p = body // a "regex" without metacharacters is a plain substring
            } else {
                regex = body
                p = ""
            }
        }
        if (regex == null) {
            if (p.startsWith("||")) { anchor = NetworkRule.ANCHOR_HOST; p = p.substring(2) }
            else if (p.startsWith("|")) { anchor = NetworkRule.ANCHOR_START; p = p.substring(1) }
            if (p.endsWith("|")) { anchorEnd = true; p = p.substring(0, p.length - 1) }
            while (p.contains("**")) p = p.replace("**", "*")
            if (p.startsWith("*")) { p = p.substring(1); anchor = NetworkRule.ANCHOR_NONE }
            if (p.endsWith("*")) { p = p.substring(0, p.length - 1); anchorEnd = false }
            if (flags and NetworkRule.MATCH_CASE == 0) p = p.lowercase()
            if (p.contains(' ')) return skip(SkipReason.INVALID)
        }
        // A rule matching every URL must at least be limited to some sites or be a page-level exception.
        if (regex == null && (p.isEmpty() || p == "^") && anchor == NetworkRule.ANCHOR_NONE && domains == null && pageFlags == 0 &&
            flags and (NetworkRule.THIRD_PARTY or NetworkRule.FIRST_PARTY) == 0
        ) {
            return skip(SkipReason.INVALID)
        }

        val rule = NetworkRule(p, anchor, anchorEnd, flags, types, domains, regex)
        val plainHost = if (
            !exception && regex == null && anchor == NetworkRule.ANCHOR_HOST && !anchorEnd && domains == null &&
            types == RequestType.TYPELESS && flags and (NetworkRule.IMPORTANT or NetworkRule.FIRST_PARTY or NetworkRule.MATCH_CASE or NetworkRule.REDIRECT) == 0 &&
            p.endsWith("^") && isPlainHost(p, p.length - 1)
        ) p.substring(0, p.length - 1) else null
        return ParsedFilter.Network(rule, plainHost, key, badfilter)
    }

    /**
     * Splits `pattern$options`. The options start at the last `$` whose tail looks like an option list; a `$`
     * inside a regex (`/ads$/`) or a URL is left alone.
     */
    private fun splitOptions(t: String): Pair<String, String?> {
        val i = t.lastIndexOf('$')
        if (i < 0) return t to null
        val tail = t.substring(i + 1)
        if (tail.isEmpty()) return t.substring(0, i) to null
        val first = tail.substringBefore(',').substringBefore('=').removePrefix("~")
        if (first.isEmpty() || !first.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) return t to null
        if (t.startsWith('/') && i > 0 && t[i - 1] != '/') {
            // `/re$/…` — the `$` belongs to the regex unless it directly follows the closing slash
            if (t.indexOf('/', 1) > i) return t to null
        }
        return t.substring(0, i) to tail
    }

    private fun isPlainHost(p: String, end: Int): Boolean {
        if (end <= 0 || p.indexOf('.') !in 1 until end) return false
        for (i in 0 until end) {
            val c = p[i]
            if (c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_') continue
            return false
        }
        return p[0] != '.' && p[end - 1] != '.'
    }

    private const val REGEX_META = "\\^$.*+?()[]{}|"
    private const val MAX_LINE = 8192
    private const val MAX_SELECTOR = 1024
}
