package com.olaph.xdm.adblock.filters

import com.olaph.xdm.adblock.Gzip
import com.olaph.xdm.adblock.HostNames
import java.io.InputStream

/**
 * Registrable-domain ("eTLD+1") lookup backed by the Public Suffix List (https://publicsuffix.org, MPL-2.0).
 * Used to tell first-party from third-party requests (`a.example.co.uk` and `b.example.co.uk` are one site,
 * `alice.github.io` and `bob.github.io` are not) and to resolve `example.*` entity wildcards.
 *
 * Rules are expected in ASCII (punycode); the build-prep task converts the upstream file. Immutable and thread-safe.
 */
class PublicSuffixList private constructor(
    private val rules: Set<String>,
    private val wildcards: Set<String>,
    private val exceptions: Set<String>,
) {
    val size: Int get() = rules.size + wildcards.size + exceptions.size

    /** The public suffix of [host] (`co.uk` for `a.b.co.uk`); the last label when no rule matches (`*` rule). */
    fun publicSuffix(host: String): String {
        var i = 0
        while (true) {
            val s = if (i == 0) host else host.substring(i)
            if (s in exceptions) return s.substring(s.indexOf('.') + 1)
            if (s in rules) return s
            val dot = s.indexOf('.')
            if (dot < 0) return s
            if (s.substring(dot + 1) in wildcards) return s
            i += dot + 1
        }
    }

    /**
     * The registrable domain of [host] (`example.co.uk` for `www.example.co.uk`), the host itself for IP
     * literals, or null when the host is itself a public suffix (`co.uk`) or blank.
     */
    fun registrableDomain(host: String?): String? {
        val h = HostNames.normalize(host) ?: return null
        if (HostNames.isIpLiteral(h)) return h
        val suffix = publicSuffix(h)
        if (suffix.length >= h.length) return null
        val head = h.substring(0, h.length - suffix.length - 1)
        val start = head.lastIndexOf('.') + 1
        return h.substring(start)
    }

    /**
     * Entity names of [host] for `example.*` rules: the labels left of the public suffix, from the most specific
     * (`www.example` for `www.example.co.uk`) to the least (`example`). Empty for IPs and bare suffixes.
     */
    fun entityNames(host: String?): List<String> {
        val h = HostNames.normalize(host) ?: return emptyList()
        if (HostNames.isIpLiteral(h)) return emptyList()
        val suffix = publicSuffix(h)
        if (suffix.length >= h.length) return emptyList()
        var head = h.substring(0, h.length - suffix.length - 1)
        val out = ArrayList<String>(3)
        while (true) {
            out += head
            val dot = head.indexOf('.')
            if (dot < 0) break
            head = head.substring(dot + 1)
        }
        return out
    }

    /** Same site (equal registrable domains)? Used for the `third-party` option. */
    fun isThirdParty(requestHost: String?, pageHost: String?): Boolean {
        val r = registrableDomain(requestHost) ?: HostNames.normalize(requestHost) ?: return false
        val p = registrableDomain(pageHost) ?: HostNames.normalize(pageHost) ?: return false
        return r != p
    }

    companion object {
        /** Parses the PSL text format (comments `//`, `*.` wildcards, `!` exceptions). Never throws. */
        fun parse(lines: Sequence<String>): PublicSuffixList {
            val rules = HashSet<String>()
            val wildcards = HashSet<String>()
            val exceptions = HashSet<String>()
            for (raw in lines) {
                val line = raw.trim().substringBefore(' ').lowercase()
                if (line.isEmpty() || line.startsWith("//")) continue
                when {
                    line.startsWith("!") -> exceptions += line.substring(1)
                    line.startsWith("*.") -> wildcards += line.substring(2)
                    else -> rules += line
                }
            }
            return PublicSuffixList(rules, wildcards, exceptions)
        }

        /** Reads the bundled `public_suffix_list.dat.gz` (gzip or, as packaged in the APK, plain text). */
        fun read(input: InputStream): PublicSuffixList =
            Gzip.maybeDecompress(input).bufferedReader(Charsets.UTF_8).use { parse(it.lineSequence()) }

        /**
         * Used until (or if) the bundled list cannot be read: generic TLDs plus the most common second-level
         * registries and hosting suffixes, so `co.uk`, `com.br` and `github.io` still behave.
         */
        val FALLBACK: PublicSuffixList by lazy {
            val common = listOf(
                "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk",
                "com.br", "net.br", "org.br", "gov.br", "com.au", "net.au", "org.au", "edu.au", "gov.au",
                "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "co.kr", "or.kr", "co.nz", "org.nz", "co.za", "org.za",
                "co.in", "net.in", "org.in", "firm.in", "gen.in", "ind.in", "com.cn", "net.cn", "org.cn", "gov.cn",
                "com.tw", "org.tw", "com.hk", "org.hk", "com.sg", "com.my", "co.id", "or.id", "web.id", "ac.id", "go.id",
                "com.mx", "com.ar", "com.co", "com.pe", "com.tr", "com.ua", "com.ru", "com.pl", "co.il", "co.th", "in.th",
                "com.vn", "com.ph", "com.pk", "com.sa", "com.eg", "com.ng", "co.ke",
                "github.io", "githubusercontent.com", "gitlab.io", "blogspot.com", "appspot.com", "herokuapp.com",
                "netlify.app", "vercel.app", "pages.dev", "workers.dev", "web.app", "firebaseapp.com",
                "cloudfront.net", "azurewebsites.net", "s3.amazonaws.com", "wordpress.com", "tumblr.com",
            )
            parse(common.asSequence())
        }
    }
}
