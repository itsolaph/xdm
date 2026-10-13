package com.olaph.xdm.adblock.filters

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * `domain=` / cosmetic domain lists: host names and `example.*` entities, each either included or excluded
 * (`~`). Sorted arrays + binary search keep long lists cheap in memory.
 *
 * Semantics: the most specific hit while walking the page host from itself up to its parents decides
 * (`example.com|~ads.example.com` excludes `ads.example.com`); entities are checked after host names. With no
 * hit the rule applies only if nothing is included (an exclusion-only list).
 */
class DomainConstraint(
    internal val include: Array<String>,
    internal val exclude: Array<String>,
    internal val includeEntities: Array<String>,
    internal val excludeEntities: Array<String>,
) {
    val hasIncludes: Boolean get() = include.isNotEmpty() || includeEntities.isNotEmpty()
    val hasExclusions: Boolean get() = exclude.isNotEmpty() || excludeEntities.isNotEmpty()

    /** Positive host names (for indexing rules by domain). */
    fun includedHosts(): Array<String> = include
    fun includedEntities(): Array<String> = includeEntities

    fun matches(page: PageInfo): Boolean {
        val host = page.host ?: return !hasIncludes
        var start = 0
        while (true) {
            if (exclude.isNotEmpty() && hit(exclude, host, start)) return false
            if (include.isNotEmpty() && hit(include, host, start)) return true
            val dot = host.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
        }
        if (excludeEntities.isNotEmpty() || includeEntities.isNotEmpty()) {
            for (e in page.entities) {
                if (excludeEntities.binarySearch(e) >= 0) return false
                if (includeEntities.binarySearch(e) >= 0) return true
            }
        }
        return !hasIncludes
    }

    private fun hit(sorted: Array<String>, host: String, start: Int): Boolean =
        sorted.binarySearch(if (start == 0) host else host.substring(start)) >= 0

    companion object {
        /**
         * Parses `a.com|~b.a.com|example.*` ([separator] `|`) or `a.com,~b.com` (cosmetic, `,`). Returns null when
         * the list is empty or contains something that is not a host name (then the rule is skipped).
         */
        fun parse(list: String, separator: Char): DomainConstraint? {
            val inc = ArrayList<String>()
            val exc = ArrayList<String>()
            val incE = ArrayList<String>()
            val excE = ArrayList<String>()
            for (raw in list.split(separator)) {
                var d = raw.trim().lowercase()
                if (d.isEmpty()) continue
                val negated = d.startsWith("~")
                if (negated) d = d.substring(1)
                d = d.trimEnd('.')
                if (d.endsWith(".*")) {
                    val name = d.substring(0, d.length - 2)
                    if (!isHostLike(name)) return null
                    (if (negated) excE else incE) += name
                } else {
                    if (!isHostLike(d)) return null
                    (if (negated) exc else inc) += d
                }
            }
            if (inc.isEmpty() && exc.isEmpty() && incE.isEmpty() && excE.isEmpty()) return null
            fun List<String>.arr() = distinct().sorted().toTypedArray()
            return DomainConstraint(inc.arr(), exc.arr(), incE.arr(), excE.arr())
        }

        /** Letters, digits, `-`, `_`, `.`; also accepts IP literals and non-ASCII (IDN) labels, never wildcards or paths. */
        fun isHostLike(s: String): Boolean {
            if (s.isEmpty() || s.length > 253 || s.startsWith('.') || s.contains("..")) return false
            for (c in s) {
                if (c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == ':' || c.code > 0x7F) continue
                return false
            }
            return true
        }
    }
}

/** What the engine needs to know about the top-level page, computed once per page. */
class PageInfo(
    /** Normalized page host, null for non-http pages. */
    val host: String?,
    /** Registrable domain of [host] (eTLD+1). */
    val site: String?,
    /** `example.*` entity names of [host], most specific first. */
    val entities: List<String>,
) {
    companion object {
        val NONE = PageInfo(null, null, emptyList())
    }
}

/**
 * One compiled network rule (anything that is not a plain host rule). Patterns are pre-lowercased unless
 * `match-case`. The rule is immutable; the lazily compiled regex is published through a volatile field.
 */
class NetworkRule(
    /** Pattern body without anchors; `*` and `^` keep their filter meaning. Empty = matches every URL. */
    val pattern: String,
    val anchor: Int,
    val anchorEnd: Boolean,
    val flags: Int,
    /** Types this rule applies to (see [RequestType]). */
    val types: Int,
    val domains: DomainConstraint?,
    /** For regex rules: the regex source (between the slashes). */
    internal val regexSource: String? = null,
) {
    @Volatile private var compiled: Any? = null

    val isException: Boolean get() = flags and EXCEPTION != 0
    val isImportant: Boolean get() = flags and IMPORTANT != 0
    val isRegex: Boolean get() = regexSource != null

    /** Type, party and domain checks; the pattern is checked by [matchesUrl]. */
    fun appliesTo(type: Int, thirdParty: Boolean, page: PageInfo): Boolean {
        if (types and type == 0) return false
        if (flags and THIRD_PARTY != 0 && !thirdParty) return false
        if (flags and FIRST_PARTY != 0 && thirdParty) return false
        return domains == null || domains.matches(page)
    }

    /**
     * [url] is the original URL, [lower] its lower-case form; [hostStart]/[hostEnd] delimit the host in both.
     */
    fun matchesUrl(url: String, lower: String, hostStart: Int, hostEnd: Int): Boolean {
        val subject = if (flags and MATCH_CASE != 0) url else lower
        if (regexSource != null) {
            val re = regex() ?: return false
            // List regexes are written for JS engines; java.util.regex recurses on some of them, so a pathological
            // URL must not take the network thread down.
            return try {
                re.matcher(if (subject.length > MAX_REGEX_INPUT) subject.substring(0, MAX_REGEX_INPUT) else subject).find()
            } catch (_: StackOverflowError) {
                false
            }
        }
        return when (anchor) {
            ANCHOR_HOST -> {
                var pos = hostStart
                while (pos < hostEnd) {
                    if (Glob.matches(pattern, subject, pos, anchorEnd, floating = false)) return true
                    val dot = subject.indexOf('.', pos)
                    if (dot < 0 || dot >= hostEnd) return false
                    pos = dot + 1
                }
                false
            }
            ANCHOR_START -> Glob.matches(pattern, subject, 0, anchorEnd, floating = false)
            else -> Glob.matches(pattern, subject, 0, anchorEnd, floating = true)
        }
    }

    private fun regex(): Pattern? {
        when (val c = compiled) {
            is Pattern -> return c
            INVALID -> return null
        }
        val p = try {
            Pattern.compile(regexSource!!, if (flags and MATCH_CASE != 0) 0 else Pattern.CASE_INSENSITIVE)
        } catch (_: PatternSyntaxException) {
            null
        } catch (_: StackOverflowError) {
            null
        }
        compiled = p ?: INVALID
        return p
    }

    override fun toString(): String = buildString {
        if (isException) append("@@")
        append(if (anchor == ANCHOR_HOST) "||" else if (anchor == ANCHOR_START) "|" else "")
        append(regexSource?.let { "/$it/" } ?: pattern)
        if (anchorEnd) append('|')
    }

    companion object {
        const val ANCHOR_NONE = 0
        const val ANCHOR_START = 1
        const val ANCHOR_HOST = 2

        const val EXCEPTION = 1
        const val IMPORTANT = 1 shl 1
        const val MATCH_CASE = 1 shl 2
        const val THIRD_PARTY = 1 shl 3
        const val FIRST_PARTY = 1 shl 4
        /** Page-level exception flags (`@@...$document`, `$elemhide`, `$generichide`, `$specifichide`). */
        const val PAGE_DOCUMENT = 1 shl 5
        const val PAGE_ELEMHIDE = 1 shl 6
        const val PAGE_GENERICHIDE = 1 shl 7
        const val PAGE_SPECIFICHIDE = 1 shl 8
        const val PAGE_FLAGS = PAGE_DOCUMENT or PAGE_ELEMHIDE or PAGE_GENERICHIDE or PAGE_SPECIFICHIDE
        /** The rule was a `redirect=` rule: here it simply blocks. */
        const val REDIRECT = 1 shl 9

        private val INVALID = Any()
        private const val MAX_REGEX_INPUT = 4096
    }
}

/**
 * Adblock-pattern matcher: `*` = any run of characters, `^` = a separator (anything but a letter, digit or
 * `_ - . %`) or the end of the URL. Iterative with single-star backtracking, so no regex and no allocation.
 */
object Glob {
    fun matches(p: String, u: String, start: Int, anchoredEnd: Boolean, floating: Boolean): Boolean {
        val plen = p.length
        val ulen = u.length
        var pi = 0
        var ui = start
        var backPi = if (floating) 0 else -1
        var backUi = start
        while (true) {
            if (pi < plen) {
                val c = p[pi]
                if (c == '*') {
                    pi++
                    backPi = pi
                    backUi = ui
                    continue
                }
                if (ui < ulen) {
                    val uc = u[ui]
                    if (c == uc || (c == '^' && isSeparator(uc))) {
                        pi++; ui++
                        continue
                    }
                } else if (c == '^') {
                    pi++ // `^` also matches the end of the address
                    continue
                }
            } else if (!anchoredEnd || ui == ulen) {
                return true
            }
            if (backPi < 0) return false
            backUi++
            if (backUi > ulen) return false
            pi = backPi
            ui = backUi
        }
    }

    fun isSeparator(c: Char): Boolean =
        !(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' || c == '%' || c.code > 0x7F)
}
