package com.olaph.xdm.adblock

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Host-name helpers shared by the parser, the matcher and the per-site allowlist. Pure JVM. */
object HostNames {
    /** Lower-case, no brackets (IPv6 literals) and no trailing dot; null for blank input. */
    fun normalize(host: String?): String? {
        var h = host?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        h = h.trimEnd('.')
        return h.ifEmpty { null }
    }

    /** IPv6 literals contain ':'; IPv4 literals are four dotted numbers. */
    fun isIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it in '0'..'9' } }
    }

    /** Host of an http(s) URL, normalized; null for anything else. */
    fun hostOf(url: String?): String? = normalize(url?.toHttpUrlOrNull()?.host)

    /**
     * The key stored in the allowlist for [host]: normalized, without a leading `www.` (so `www.example.com`
     * and `example.com` are one site). IP literals are kept as they are; null for blank hosts.
     */
    fun siteKey(host: String?): String? {
        val h = normalize(host) ?: return null
        if (isIpLiteral(h)) return h
        return if (h.startsWith("www.") && h.indexOf('.', 4) > 0) h.substring(4) else h
    }

    /**
     * Walks `a.b.ads.example.com` → `b.ads.example.com` → `ads.example.com` → `example.com` and returns the
     * first suffix in [entries], or null. A bare TLD is never tested, and IP literals never walk (so
     * `1.2.3.4` is not a child of `2.3.4`). `notads.com` is not a child of `ads.com`: only whole labels count.
     */
    fun matchWalk(host: String?, entries: Set<String>): String? {
        if (entries.isEmpty()) return null
        val h = normalize(host) ?: return null
        if (isIpLiteral(h)) return if (h in entries) h else null
        var candidate = h
        while (true) {
            if (candidate in entries) return candidate
            val dot = candidate.indexOf('.')
            if (dot < 0) return null
            candidate = candidate.substring(dot + 1)
            if (candidate.indexOf('.') < 0) return null
        }
    }
}

/**
 * Parses hosts files (`0.0.0.0 ads.example.com # note`) and the app's own compact format (one domain per
 * line). Comments, blank lines, CRLF, localhost-style entries, IP addresses and invalid names are skipped.
 */
object HostsParser {
    private val RESERVED = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts",
    )

    fun parse(lines: Sequence<String>, maxDomains: Int = MAX_DOMAINS): Set<String> {
        val out = HashSet<String>()
        for (line in lines) {
            forEachHost(line) { if (out.size < maxDomains) out.add(it) }
        }
        return out
    }

    /** Streaming variant that does not need the whole input in memory. */
    fun parse(reader: java.io.BufferedReader, maxDomains: Int = MAX_DOMAINS): Set<String> {
        val out = HashSet<String>()
        var first = true
        while (true) {
            var line = reader.readLine() ?: break
            if (first) { line = line.removePrefix(BOM); first = false }
            forEachHost(line) { if (out.size < maxDomains) out.add(it) }
        }
        return out
    }

    /** Calls [sink] with every valid, non-reserved hostname on one line. */
    fun forEachHost(rawLine: String, sink: (String) -> Unit) {
        val line = (if (rawLine.indexOf('#') >= 0) rawLine.substringBefore('#') else rawLine).trim()
        if (line.isEmpty()) return
        if (line.none { it == ' ' || it == '\t' }) {
            // Compact "domain per line" format (the bundled snapshot): no splitting needed.
            if (isAddress(line)) return
            val host = HostNames.normalize(line) ?: return
            if (isValidDomain(host)) sink(host)
            return
        }
        val tokens = line.split(WHITESPACE)
        val first = tokens[0]
        val hosts = when {
            tokens.size == 1 && !isAddress(first) -> tokens // compact "domain per line" format
            isAddress(first) -> tokens.drop(1)              // "0.0.0.0 host [host...]"
            else -> return                                  // "something else": not a hosts line
        }
        for (token in hosts) {
            val host = HostNames.normalize(token) ?: continue
            if (isValidDomain(host)) sink(host)
        }
    }

    fun isValidDomain(host: String): Boolean {
        if (host.isEmpty() || host.length > 253 || host in RESERVED || HostNames.isIpLiteral(host)) return false
        // Label by label without allocating: 1-63 chars of [a-z0-9_-], no leading/trailing '-', at least two labels.
        var labelStart = 0
        var labels = 0
        var lastLabelNumeric = true
        for (i in 0..host.length) {
            if (i == host.length || host[i] == '.') {
                val len = i - labelStart
                if (len == 0 || len > 63 || host[labelStart] == '-' || host[i - 1] == '-') return false
                labels++
                labelStart = i + 1
                continue
            }
            val c = host[i]
            if (labelStart == i) lastLabelNumeric = true
            if (c !in '0'..'9') lastLabelNumeric = false
            if (!(c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_')) return false
        }
        // A numeric last label is never a TLD (rules out odd IPs such as 1.2.3 or 0x7f.1).
        return labels >= 2 && !lastLabelNumeric
    }

    private fun isAddress(token: String) = token.contains(':') || HostNames.isIpLiteral(token)

    private val WHITESPACE = Regex("\\s+")

    /** U+FEFF written as a code point, so no literal byte-order mark sits in the source. */
    internal val BOM: String = Char(0xFEFF).toString()
    const val MAX_DOMAINS = 1_000_000
}

/** Immutable set of blocked domains with parent-domain lookup. */
class DomainMatcher(val domains: Set<String>) {
    val size: Int get() = domains.size

    /** The blocklist entry that covers [host] (the host itself or the closest parent), or null. */
    fun matchedEntry(host: String?): String? = HostNames.matchWalk(host, domains)

    fun isBlocked(host: String?): Boolean = matchedEntry(host) != null
}
