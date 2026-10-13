package com.olaph.xdm.adblock.filters

/** One cosmetic rule: a CSS selector, optionally limited by (negated) domains. */
class CosmeticRule(val selector: String, val domains: DomainConstraint?)

/**
 * Element-hiding rules, organised the way uBO does it:
 *  - site-specific rules (`example.com##.ad`) by host name and `example.*` entity;
 *  - generic rules keyed by a class or id they require (`##.ad-box`, `##div#banner > a`): the page reports the
 *    classes and ids it has, and only the selectors for those are sent back ("lowly generic");
 *  - a capped set of simple generic selectors without a class/id key (`##a[href^="https://ads."]`) that are
 *    always applied ("highly generic");
 *  - `#@#` exceptions per site (global `#@#` exceptions are applied when the index is built).
 */
class CosmeticIndex internal constructor(
    internal val specificByHost: Map<String, Array<CosmeticRule>>,
    internal val specificByEntity: Map<String, Array<CosmeticRule>>,
    internal val exceptionsByHost: Map<String, Array<CosmeticRule>>,
    internal val exceptionsByEntity: Map<String, Array<CosmeticRule>>,
    internal val genericByKey: Map<String, Array<CosmeticRule>>,
    val highGeneric: Array<CosmeticRule>,
) {
    val specificCount: Int get() = specificByHost.values.sumOf { it.size } + specificByEntity.values.sumOf { it.size }
    val genericCount: Int get() = genericByKey.values.sumOf { it.size } + highGeneric.size
    val exceptionCount: Int get() = exceptionsByHost.values.sumOf { it.size } + exceptionsByEntity.values.sumOf { it.size }

    /** Site-specific selectors for the page, minus its `#@#` exceptions. */
    fun specificSelectors(page: PageInfo): List<String> {
        val out = LinkedHashSet<String>()
        collect(page, specificByHost, specificByEntity) { out += it.selector }
        if (out.isEmpty()) return emptyList()
        out.removeAll(exceptions(page))
        return out.toList()
    }

    /** Selectors excepted on this page (`example.com#@#.ad`). */
    fun exceptions(page: PageInfo): Set<String> {
        if (exceptionsByHost.isEmpty() && exceptionsByEntity.isEmpty()) return emptySet()
        val out = HashSet<String>()
        collect(page, exceptionsByHost, exceptionsByEntity) { out += it.selector }
        return out
    }

    /** Always-applied generic selectors, minus the page's exceptions. */
    fun highGenericSelectors(page: PageInfo, exceptions: Set<String> = exceptions(page)): List<String> =
        highGeneric.filter { (it.domains == null || it.domains.matches(page)) && it.selector !in exceptions }.map { it.selector }

    /**
     * Generic selectors keyed by the classes (`.name`) and ids (`#name`) in [keys], minus the page's exceptions.
     */
    fun genericSelectors(page: PageInfo, keys: Collection<String>, exceptions: Set<String> = exceptions(page)): List<String> {
        val out = ArrayList<String>()
        for (k in keys) {
            val rules = genericByKey[k] ?: continue
            for (r in rules) if ((r.domains == null || r.domains.matches(page)) && r.selector !in exceptions) out += r.selector
        }
        return out
    }

    private inline fun collect(
        page: PageInfo,
        byHost: Map<String, Array<CosmeticRule>>,
        byEntity: Map<String, Array<CosmeticRule>>,
        sink: (CosmeticRule) -> Unit,
    ) {
        val host = page.host ?: return
        if (byHost.isNotEmpty()) {
            var start = 0
            while (true) {
                byHost[if (start == 0) host else host.substring(start)]?.forEach { r -> if (r.domains == null || r.domains.matches(page)) sink(r) }
                val dot = host.indexOf('.', start)
                if (dot < 0) break
                start = dot + 1
            }
        }
        if (byEntity.isNotEmpty()) for (e in page.entities) byEntity[e]?.forEach { r -> if (r.domains == null || r.domains.matches(page)) sink(r) }
    }

    internal class Builder(private val maxHighGeneric: Int) {
        private val specific = ArrayList<ParsedFilter.Cosmetic>()
        private val exceptions = ArrayList<ParsedFilter.Cosmetic>()
        private val generic = ArrayList<ParsedFilter.Cosmetic>()
        private val globalExceptions = HashSet<String>()
        var skippedComplexGeneric = 0
            private set
        var skippedHighGenericCap = 0
            private set

        fun add(c: ParsedFilter.Cosmetic) {
            val d = c.domains
            when {
                c.exception && (d == null || !d.hasIncludes) -> globalExceptions += c.selector
                c.exception -> exceptions += c
                d != null && d.hasIncludes -> specific += c
                else -> generic += c // no domains, or only negated ones
            }
        }

        fun build(): CosmeticIndex {
            val sByHost = HashMap<String, MutableList<CosmeticRule>>()
            val sByEntity = HashMap<String, MutableList<CosmeticRule>>()
            index(specific, sByHost, sByEntity)
            val eByHost = HashMap<String, MutableList<CosmeticRule>>()
            val eByEntity = HashMap<String, MutableList<CosmeticRule>>()
            index(exceptions, eByHost, eByEntity)

            val byKey = HashMap<String, MutableList<CosmeticRule>>()
            val high = ArrayList<CosmeticRule>()
            val seen = HashSet<String>()
            for (g in generic) {
                if (g.selector in globalExceptions) continue
                val constraint = g.domains // exclusion-only, or null
                for (sel in splitList(g.selector)) {
                    if (constraint == null && !seen.add(sel)) continue
                    val rule = CosmeticRule(sel, constraint)
                    val key = genericKey(sel)
                    when {
                        key != null -> byKey.getOrPut(key) { ArrayList(1) } += rule
                        !isSimple(sel) -> skippedComplexGeneric++
                        high.size >= maxHighGeneric -> skippedHighGenericCap++
                        else -> high += rule
                    }
                }
            }
            fun Map<String, MutableList<CosmeticRule>>.frozen() = mapValues { it.value.toTypedArray() }
            return CosmeticIndex(sByHost.frozen(), sByEntity.frozen(), eByHost.frozen(), eByEntity.frozen(), byKey.frozen(), high.toTypedArray())
        }

        private fun index(
            list: List<ParsedFilter.Cosmetic>,
            byHost: HashMap<String, MutableList<CosmeticRule>>,
            byEntity: HashMap<String, MutableList<CosmeticRule>>,
        ) {
            for (c in list) {
                val d = c.domains ?: continue
                // Only rules with exclusions need the constraint at lookup time; the index key covers the rest.
                val constraint = if (d.hasExclusions) d else null
                val rule = CosmeticRule(c.selector, constraint)
                for (h in d.includedHosts()) byHost.getOrPut(h) { ArrayList(2) } += rule
                for (e in d.includedEntities()) byEntity.getOrPut(e) { ArrayList(2) } += rule
            }
        }
    }

    companion object {
        /**
         * The key of a generic selector: its first class or id outside brackets and parentheses (`.ad` for
         * `div.ad > a`, `#banner` for `#banner:not(.x)`), or null when it has none or uses escapes.
         */
        fun genericKey(selector: String): String? {
            var depthParen = 0
            var depthBracket = 0
            var quote = 0.toChar()
            var i = 0
            while (i < selector.length) {
                val c = selector[i]
                if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar(); i++; continue }
                when (c) {
                    '"', '\'' -> quote = c
                    '(' -> depthParen++
                    ')' -> depthParen--
                    '[' -> depthBracket++
                    ']' -> depthBracket--
                    '\\' -> return null
                    '.', '#' -> if (depthParen == 0 && depthBracket == 0) {
                        var j = i + 1
                        while (j < selector.length && isIdentChar(selector[j])) j++
                        if (j < selector.length && selector[j] == '\\') return null
                        if (j > i + 1) return c + selector.substring(i + 1, j)
                    }
                }
                i++
            }
            return null
        }

        /** No combinators at the top level: one compound selector, cheap enough to apply on every page. */
        fun isSimple(selector: String): Boolean {
            var depth = 0
            var quote = 0.toChar()
            for (c in selector) {
                if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar(); continue }
                when (c) {
                    '"', '\'' -> quote = c
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    ' ', '>', '+', '~' -> if (depth == 0) return false
                }
            }
            return true
        }

        /** Splits a top-level selector list (`.a, .b`). */
        fun splitList(selector: String): List<String> {
            if (!selector.contains(',')) return listOf(selector)
            val out = ArrayList<String>()
            var depth = 0
            var quote = 0.toChar()
            var start = 0
            for ((i, c) in selector.withIndex()) {
                if (quote != 0.toChar()) { if (c == quote) quote = 0.toChar(); continue }
                when (c) {
                    '"', '\'' -> quote = c
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    ',' -> if (depth == 0) { out += selector.substring(start, i).trim(); start = i + 1 }
                }
            }
            out += selector.substring(start).trim()
            return out.filter { it.isNotEmpty() }
        }

        private fun isIdentChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c.code > 0x7F
    }
}
