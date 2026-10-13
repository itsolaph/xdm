package com.olaph.xdm.adblock.filters

/**
 * Network rules bucketed by one literal token each (the rarest "whole word" of the pattern, as uBO and
 * adblock-rust do). A URL is split into its alphanumeric runs and only the buckets of those runs are checked,
 * so a request is tested against a handful of rules instead of tens of thousands. Rules without a usable token
 * but limited to some sites (`domain=`) are bucketed by those sites; the rest go to a small "no token" bucket.
 */
class RuleIndex private constructor(
    /** The indexed rules and their token hashes (parallel arrays), kept for serialization. */
    internal val rules: Array<NetworkRule>,
    internal val ruleTokens: IntArray,
    private val keys: IntArray,
    private val buckets: Array<Array<NetworkRule>?>,
    private val noToken: Array<NetworkRule>,
    private val byDomain: Map<String, Array<NetworkRule>>,
    private val byEntity: Map<String, Array<NetworkRule>>,
) {
    val size: Int get() = rules.size
    private val mask = keys.size - 1

    val noTokenCount: Int get() = noToken.size
    val domainBucketCount: Int get() = byDomain.values.sumOf { it.size } + byEntity.values.sumOf { it.size }

    /** First rule matching the request, or null. [checkTypes] is false for page-level exception lookups. */
    fun find(req: RequestContext, checkTypes: Boolean = true): NetworkRule? {
        if (size == 0) return null
        val tokens = req.tokens
        for (i in 0 until req.tokenCount) {
            val bucket = bucket(tokens[i]) ?: continue
            for (r in bucket) if (test(r, req, checkTypes)) return r
        }
        for (r in noToken) if (test(r, req, checkTypes)) return r
        if (byDomain.isNotEmpty()) {
            val host = req.page.host
            if (host != null) {
                var start = 0
                while (true) {
                    byDomain[if (start == 0) host else host.substring(start)]?.let { b -> for (r in b) if (test(r, req, checkTypes)) return r }
                    val dot = host.indexOf('.', start)
                    if (dot < 0) break
                    start = dot + 1
                }
            }
        }
        if (byEntity.isNotEmpty()) {
            for (e in req.page.entities) byEntity[e]?.let { b -> for (r in b) if (test(r, req, checkTypes)) return r }
        }
        return null
    }

    private fun test(r: NetworkRule, req: RequestContext, checkTypes: Boolean): Boolean {
        if (checkTypes) {
            if (!r.appliesTo(req.type, req.thirdParty, req.page)) return false
        } else if (r.domains != null && !r.domains.matches(req.page)) {
            return false
        }
        return r.matchesUrl(req.url, req.lower, req.hostStart, req.hostEnd)
    }

    private fun bucket(hash: Int): Array<NetworkRule>? {
        var slot = mix(hash) and mask
        while (true) {
            val k = keys[slot]
            if (k == EMPTY_KEY) return null
            if (k == hash) return buckets[slot]
            slot = (slot + 1) and mask
        }
    }

    internal companion object {
        const val EMPTY_KEY = 0

        fun mix(h: Int): Int = (h * -0x61c88647) xor (h ushr 15)

        /** [tokens] holds each rule's token hash, or [EMPTY_KEY] for rules without a usable token. */
        fun build(rules: Array<NetworkRule>, tokens: IntArray): RuleIndex {
            val byToken = HashMap<Int, MutableList<NetworkRule>>()
            val noToken = ArrayList<NetworkRule>()
            val byDomain = HashMap<String, MutableList<NetworkRule>>()
            val byEntity = HashMap<String, MutableList<NetworkRule>>()
            for ((i, r) in rules.withIndex()) {
                val t = tokens[i]
                if (t != EMPTY_KEY) {
                    byToken.getOrPut(t) { ArrayList(2) } += r
                } else if (r.domains != null && r.domains.hasIncludes) {
                    for (d in r.domains.includedHosts()) byDomain.getOrPut(d) { ArrayList(2) } += r
                    for (e in r.domains.includedEntities()) byEntity.getOrPut(e) { ArrayList(2) } += r
                } else {
                    noToken += r
                }
            }
            var cap = 16
            while (cap < byToken.size * 2) cap = cap shl 1
            val keys = IntArray(cap)
            val buckets = arrayOfNulls<Array<NetworkRule>>(cap)
            for ((k, list) in byToken) {
                var slot = mix(k) and (cap - 1)
                while (keys[slot] != EMPTY_KEY) slot = (slot + 1) and (cap - 1)
                keys[slot] = k
                buckets[slot] = list.toTypedArray()
            }
            return RuleIndex(
                rules, tokens, keys, buckets, noToken.toTypedArray(),
                byDomain.mapValues { it.value.toTypedArray() }, byEntity.mapValues { it.value.toTypedArray() },
            )
        }
    }
}

/** Tokenizer shared by rules and URLs: runs of `[a-z0-9%]` (after lower-casing), hashed with FNV-1a. */
object Tokens {
    fun isTokenChar(c: Char): Boolean = c in 'a'..'z' || c in '0'..'9' || c == '%' || c in 'A'..'Z'

    fun hash(s: CharSequence, start: Int, end: Int): Int {
        var h = -0x7ee3623b
        for (i in start until end) {
            h = h xor s[i].lowercaseChar().code
            h *= 16777619
        }
        return if (h == RuleIndex.EMPTY_KEY) 1 else h
    }

    /** Fills [out] with the token hashes of [lower]; returns how many were written (capped at out.size). */
    fun tokenize(lower: String, out: IntArray): Int {
        var n = 0
        var i = 0
        val len = lower.length
        while (i < len && n < out.size) {
            if (!isTokenChar(lower[i])) { i++; continue }
            val start = i
            while (i < len && isTokenChar(lower[i])) i++
            if (i - start >= MIN_URL_TOKEN) out[n++] = hash(lower, start, i)
        }
        return n
    }

    /**
     * Candidate tokens of a pattern: whole runs that a matching URL must contain as a complete run. A run touching
     * a `*`, or the unanchored start/end of the pattern, may continue in the URL and is not usable.
     */
    fun candidates(rule: NetworkRule): List<String> {
        if (rule.isRegex) return emptyList()
        val p = rule.pattern
        val out = ArrayList<String>(4)
        var i = 0
        while (i < p.length) {
            if (!isTokenChar(p[i])) { i++; continue }
            val start = i
            while (i < p.length && isTokenChar(p[i])) i++
            val leftOk = if (start == 0) rule.anchor != NetworkRule.ANCHOR_NONE else p[start - 1] != '*'
            val rightOk = if (i == p.length) rule.anchorEnd else p[i] != '*'
            if (leftOk && rightOk && i - start >= MIN_URL_TOKEN) out += p.substring(start, i).lowercase()
        }
        return out
    }

    /** Tokens so common in URLs that a bucket keyed by them would be checked for nearly every request. */
    val COMMON = setOf(
        "http", "https", "www", "com", "net", "org", "js", "css", "html", "htm", "php", "jpg", "jpeg", "png", "gif",
        "webp", "svg", "min", "static", "cdn", "assets", "img", "images", "image", "api", "v1", "v2", "id", "json",
        "wp", "content", "uploads", "includes", "index", "default", "media", "utm", "source", "true", "false",
    )

    private const val MIN_URL_TOKEN = 2
}

/** Everything about one request the matcher needs, computed once. */
class RequestContext(
    val url: String,
    val type: Int,
    val page: PageInfo,
    psl: PublicSuffixList,
    tokenBuffer: IntArray = IntArray(MAX_TOKENS),
) {
    val lower: String = url.lowercase()
    val hostStart: Int
    val hostEnd: Int
    val host: String?
    val thirdParty: Boolean
    val tokens: IntArray = tokenBuffer
    val tokenCount: Int

    init {
        val schemeEnd = lower.indexOf("://")
        var hs = -1
        var he = -1
        if (schemeEnd > 0) {
            hs = schemeEnd + 3
            he = hs
            while (he < lower.length && lower[he] != '/' && lower[he] != '?' && lower[he] != '#') he++
            val at = lower.lastIndexOf('@', he - 1)
            if (at >= hs) hs = at + 1
            if (lower.startsWith("[", hs)) {
                val close = lower.indexOf(']', hs)
                if (close in hs until he) he = close + 1
            } else {
                val colon = lower.indexOf(':', hs)
                if (colon in hs until he) he = colon
            }
        }
        hostStart = if (hs >= 0) hs else 0
        hostEnd = if (hs >= 0) he else 0
        host = if (hs >= 0 && he > hs) lower.substring(hs, he).trimEnd('.').removePrefix("[").removeSuffix("]") else null
        thirdParty = when {
            host == null || page.host == null -> false
            host == page.host -> false
            else -> (psl.registrableDomain(host) ?: host) != (page.site ?: page.host)
        }
        tokenCount = Tokens.tokenize(lower, tokens)
    }

    companion object {
        const val MAX_TOKENS = 256
    }
}
