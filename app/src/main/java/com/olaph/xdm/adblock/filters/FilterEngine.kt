package com.olaph.xdm.adblock.filters

import com.olaph.xdm.adblock.HostNames

/** Outcome of matching one request. */
class MatchResult private constructor(val verdict: Verdict, private val ruleRef: Any?) {
    enum class Verdict {
        /** No rule matched. */
        NONE,
        BLOCK,
        /** A block rule matched but an exception (or a page-level `@@…$document`) cancelled it. */
        ALLOW,
    }

    val blocked: Boolean get() = verdict == Verdict.BLOCK

    /** The deciding rule in filter syntax (for tests and diagnostics). */
    val rule: String? get() = ruleRef?.toString()

    companion object {
        val NONE = MatchResult(Verdict.NONE, null)
        fun block(rule: Any) = MatchResult(Verdict.BLOCK, rule)
        fun allow(rule: Any?) = MatchResult(Verdict.ALLOW, rule)
    }
}

/** The top-level page as the engine sees it: host info plus page-level exception flags. */
class PageContext(val info: PageInfo, val flags: Int) {
    /** `@@||site^$document`: nothing is blocked or hidden on this page. */
    val documentAllowed: Boolean get() = flags and NetworkRule.PAGE_DOCUMENT != 0
    val cosmeticEnabled: Boolean get() = flags and (NetworkRule.PAGE_DOCUMENT or NetworkRule.PAGE_ELEMHIDE) == 0
    val genericCosmeticEnabled: Boolean get() = cosmeticEnabled && flags and NetworkRule.PAGE_GENERICHIDE == 0
    val specificCosmeticEnabled: Boolean get() = cosmeticEnabled && flags and NetworkRule.PAGE_SPECIFICHIDE == 0

    companion object {
        val NONE = PageContext(PageInfo.NONE, 0)
    }
}

/** Counts shown in Settings and printed by the benchmark. */
data class EngineStats(
    val hostRules: Int = 0,
    val networkRules: Int = 0,
    val cosmeticRules: Int = 0,
    val skipped: Int = 0,
    val noTokenRules: Int = 0,
    val highGeneric: Int = 0,
)

/** Rule counts of one list after parsing (what Settings shows next to each list). */
data class ListCounts(val network: Int = 0, val cosmetic: Int = 0, val hosts: Int = 0, val skipped: Int = 0) {
    val total: Int get() = network + cosmetic + hosts
    operator fun plus(o: ListCounts) = ListCounts(network + o.network, cosmetic + o.cosmetic, hosts + o.hosts, skipped + o.skipped)
}

/**
 * Immutable, thread-safe compiled filter set: host sets for host-level rules, token indexes for the other network
 * rules, and the cosmetic index. Precedence: `important` block > exception > block.
 *
 * Host-level blocks (hosts files, `||host^`) never block a page's own resources when the page itself is covered
 * by the same entry, so visiting a listed site directly still works.
 */
class FilterEngine internal constructor(
    val psl: PublicSuffixList,
    internal val hosts: CompactDomainSet,
    internal val hosts3p: CompactDomainSet,
    internal val dnsHosts: CompactDomainSet,
    internal val dnsAllow: CompactDomainSet,
    internal val blocks: RuleIndex,
    internal val important: RuleIndex,
    internal val exceptions: RuleIndex,
    internal val importantExceptions: RuleIndex,
    internal val pageDocument: RuleIndex,
    internal val pageElemhide: RuleIndex,
    internal val pageGenerichide: RuleIndex,
    internal val pageSpecifichide: RuleIndex,
    val cosmetic: CosmeticIndex,
    val stats: EngineStats,
) {
    private val tokenBuffer = ThreadLocal.withInitial { IntArray(RequestContext.MAX_TOKENS) }

    fun pageInfo(host: String?): PageInfo {
        val h = HostNames.normalize(host) ?: return PageInfo.NONE
        return PageInfo(h, psl.registrableDomain(h), psl.entityNames(h))
    }

    /** Page info plus page-level exceptions for the top-level [pageUrl]. */
    fun page(pageUrl: String?): PageContext {
        val info = pageInfo(HostNames.hostOf(pageUrl))
        if (info.host == null || pageUrl == null) return PageContext(info, 0)
        val req = RequestContext(pageUrl, RequestType.DOCUMENT, info, psl, tokenBuffer.get()!!)
        var flags = 0
        if (pageDocument.find(req, checkTypes = false) != null) flags = flags or NetworkRule.PAGE_DOCUMENT
        if (pageElemhide.find(req, checkTypes = false) != null) flags = flags or NetworkRule.PAGE_ELEMHIDE
        if (pageGenerichide.find(req, checkTypes = false) != null) flags = flags or NetworkRule.PAGE_GENERICHIDE
        if (pageSpecifichide.find(req, checkTypes = false) != null) flags = flags or NetworkRule.PAGE_SPECIFICHIDE
        return PageContext(info, flags)
    }

    /** Matches a request of [type] (see [RequestType]) made by [page]. */
    fun match(url: String, type: Int, page: PageContext): MatchResult {
        if (page.documentAllowed) return MatchResult.allow("@@\$document")
        val req = RequestContext(url, type, page.info, psl, tokenBuffer.get()!!)
        if (req.host == null) return MatchResult.NONE
        important.find(req)?.let { rule ->
            importantExceptions.find(req)?.let { return MatchResult.allow(it) }
            return MatchResult.block(rule)
        }
        val block: Any = hostBlock(req) ?: blocks.find(req) ?: return MatchResult.NONE
        exceptions.find(req)?.let { return MatchResult.allow(it) }
        return MatchResult.block(block)
    }

    fun shouldBlock(url: String, type: Int, page: PageContext): Boolean = match(url, type, page).blocked

    /**
     * A window the page opened: is it an ad? `$popup` rules, `$document` rules and host-level rules count (uBO's
     * "strict blocking"); plain URL-pattern rules do not.
     */
    fun isAdPopup(url: String, page: PageContext): Boolean =
        shouldBlock(url, RequestType.POPUP, page) || shouldBlock(url, RequestType.DOCUMENT, page)

    /** The host-level entry blocking [host] (hosts file / `||host^` / DNS list), ignoring page context. */
    fun hostEntry(host: String?): String? {
        val h = HostNames.normalize(host) ?: return null
        hosts.matchEntry(h)?.let { return it }
        if (dnsAllow.matchStart(h) < 0) dnsHosts.matchEntry(h)?.let { return it }
        return null
    }

    private fun hostBlock(req: RequestContext): String? {
        val host = req.host ?: return null
        val pageHost = req.page.host
        val s = hosts.matchStart(host)
        if (s >= 0 && !sameEntry(hosts, host, s, pageHost)) return "||${host.substring(s)}^"
        if (req.thirdParty) {
            val t = hosts3p.matchStart(host)
            if (t >= 0) return "||${host.substring(t)}^\$third-party"
        }
        val d = dnsHosts.matchStart(host)
        if (d >= 0 && dnsAllow.matchStart(host) < 0 && !sameEntry(dnsHosts, host, d, pageHost)) return "||${host.substring(d)}^ (dns)"
        return null
    }

    /** Is [pageHost] covered by the same entry as the suffix of [host] at [start]? (A page never blocks its own listed domain.) */
    private fun sameEntry(set: CompactDomainSet, host: String, start: Int, pageHost: String?): Boolean {
        if (pageHost == null) return false
        val ps = set.matchStart(pageHost)
        if (ps < 0) return false
        val len = host.length - start
        return pageHost.length - ps == len && pageHost.regionMatches(ps, host, start, len)
    }

    // ---- cosmetic ----

    /** Site-specific selectors for a document on [host], respecting the top-level page's exceptions. */
    fun specificSelectors(host: String?, page: PageContext): List<String> {
        if (!page.specificCosmeticEnabled) return emptyList()
        return cosmetic.specificSelectors(pageInfo(host))
    }

    fun highGenericSelectors(host: String?, page: PageContext): List<String> {
        if (!page.genericCosmeticEnabled) return emptyList()
        return cosmetic.highGenericSelectors(pageInfo(host))
    }

    /** Generic selectors for the classes/ids (`.cls`, `#id`) a document on [host] reported. */
    fun genericSelectors(host: String?, page: PageContext, keys: Collection<String>): List<String> {
        if (!page.genericCosmeticEnabled || keys.isEmpty()) return emptyList()
        return cosmetic.genericSelectors(pageInfo(host), keys)
    }
}

/**
 * Collects lists and compiles them into a [FilterEngine]. Not thread-safe; use one builder per build.
 */
class FilterEngineBuilder(private val psl: PublicSuffixList, private val maxHighGeneric: Int = MAX_HIGH_GENERIC) {
    private class PlainHost(val key: String, val host: String, val thirdParty: Boolean)

    private val hostFile = ArrayList<String>()
    private val plainHosts = ArrayList<PlainHost>()
    private val network = ArrayList<ParsedFilter.Network>()
    private val badKeys = HashSet<String>()
    private val dnsBlocks = ArrayList<String>()
    private val dnsAllows = ArrayList<String>()
    private val cosmetic = CosmeticIndex.Builder(maxHighGeneric)
    private var skipped = 0

    /** Plain host names (hosts-file lists). */
    fun addHosts(domains: Collection<String>): ListCounts {
        hostFile.addAll(domains)
        return ListCounts(hosts = domains.size)
    }

    /** A DNS-style list: blocked hosts and their own exceptions (which only lift this list's blocks). */
    fun addDnsList(list: DnsList): ListCounts {
        dnsBlocks.addAll(list.blocks)
        dnsAllows.addAll(list.allows)
        return ListCounts(hosts = list.blocks.size)
    }

    /** EasyList/AdGuard-syntax lines. */
    fun addFilters(lines: Sequence<String>): ListCounts {
        var net = 0
        var cos = 0
        var hostCount = 0
        var skip = 0
        for (line in lines) {
            when (val f = FilterParser.parse(line)) {
                is ParsedFilter.Network -> when {
                    f.badfilter -> badKeys += f.key
                    f.plainHost != null -> {
                        plainHosts += PlainHost(f.key, f.plainHost, f.rule.flags and NetworkRule.THIRD_PARTY != 0)
                        hostCount++
                    }
                    else -> { network += f; net++ }
                }
                is ParsedFilter.Cosmetic -> { cosmetic.add(f); cos++ }
                is ParsedFilter.Skipped -> skip++
                ParsedFilter.Comment -> Unit
            }
        }
        skipped += skip
        return ListCounts(network = net, cosmetic = cos, hosts = hostCount, skipped = skip)
    }

    fun build(): FilterEngine {
        val general = ArrayList<String>(hostFile.size + plainHosts.size)
        general.addAll(hostFile)
        val thirdParty = ArrayList<String>()
        for (p in plainHosts) {
            if (p.key in badKeys) continue
            if (p.thirdParty) thirdParty += p.host else general += p.host
        }
        val rules = network.filter { it.key !in badKeys }.map { it.rule }

        // Token choice: the rarest usable token of each pattern; very common URL words only as a last resort.
        val candidates = rules.map { Tokens.candidates(it) }
        val freq = HashMap<String, Int>()
        for (list in candidates) for (t in list) freq[t] = (freq[t] ?: 0) + 1
        val order = compareBy<String>({ if (it in Tokens.COMMON) 1 else 0 }, { freq[it] ?: 0 }, { -it.length })
        val tokens = IntArray(rules.size) { i ->
            candidates[i].minWithOrNull(order)?.let { Tokens.hash(it, 0, it.length) } ?: RuleIndex.EMPTY_KEY
        }

        fun index(filter: (NetworkRule) -> Boolean): RuleIndex {
            val picked = rules.indices.filter { filter(rules[it]) }
            return RuleIndex.build(Array(picked.size) { rules[picked[it]] }, IntArray(picked.size) { tokens[picked[it]] })
        }
        val resourceTypes = RequestType.ALL
        val blocks = index { !it.isException && !it.isImportant }
        val important = index { !it.isException && it.isImportant }
        val exceptions = index { it.isException && !it.isImportant && it.types and resourceTypes != 0 }
        val importantExceptions = index { it.isException && it.isImportant && it.types and resourceTypes != 0 }
        fun page(flag: Int) = index { it.isException && it.flags and flag != 0 }
        val cos = cosmetic.build()
        val hostSet = CompactDomainSet.build(general)
        val stats = EngineStats(
            hostRules = hostSet.size + thirdParty.size + dnsBlocks.size,
            networkRules = rules.size,
            cosmeticRules = cos.specificCount + cos.genericCount,
            skipped = skipped,
            noTokenRules = blocks.noTokenCount + exceptions.noTokenCount,
            highGeneric = cos.highGeneric.size,
        )
        return FilterEngine(
            psl = psl,
            hosts = hostSet,
            hosts3p = CompactDomainSet.build(thirdParty),
            dnsHosts = CompactDomainSet.build(dnsBlocks),
            dnsAllow = CompactDomainSet.build(dnsAllows),
            blocks = blocks,
            important = important,
            exceptions = exceptions,
            importantExceptions = importantExceptions,
            pageDocument = page(NetworkRule.PAGE_DOCUMENT),
            pageElemhide = page(NetworkRule.PAGE_ELEMHIDE),
            pageGenerichide = page(NetworkRule.PAGE_GENERICHIDE),
            pageSpecifichide = page(NetworkRule.PAGE_SPECIFICHIDE),
            cosmetic = cos,
            stats = stats,
        )
    }

    companion object {
        /** Cap on always-applied generic selectors without a class/id key. */
        const val MAX_HIGH_GENERIC = 1500
    }
}

/** A DNS-style host list: `||host^` blocks and `@@||host^` exceptions (AdGuard DNS filter). */
class DnsList(val blocks: List<String>, val allows: List<String>) {
    companion object {
        /**
         * Accepts the upstream syntax (`||host^`, `@@||host^|`, `$important`, `$badfilter`) and the compact bundled
         * form (`host` / `@@host`). Rules with other options (`$client`, `$dnstype`, `$denyallow`…), regexes and
         * partial hosts are dropped: only whole-host rules are used here.
         */
        fun parse(lines: Sequence<String>): DnsList {
            val blocks = LinkedHashSet<String>()
            val allows = LinkedHashSet<String>()
            val bad = HashSet<String>()
            for (raw in lines) {
                var t = raw.trim()
                if (t.isEmpty() || t[0] == '!' || t[0] == '#' || t[0] == '[') continue
                val allow = t.startsWith("@@")
                if (allow) t = t.substring(2)
                var options = ""
                val dollar = t.indexOf('$')
                if (dollar >= 0) { options = t.substring(dollar + 1); t = t.substring(0, dollar) }
                var badfilter = false
                if (options.isNotEmpty()) {
                    for (o in options.split(',')) {
                        when (o.trim().lowercase()) {
                            "important" -> Unit
                            "badfilter" -> badfilter = true
                            else -> { t = ""; break }
                        }
                    }
                }
                if (t.startsWith("||")) t = t.substring(2) else if (t.startsWith("|") || t.contains('/') || t.contains('*')) continue
                t = t.removeSuffix("|").removeSuffix("^").lowercase()
                if (t.isEmpty() || !com.olaph.xdm.adblock.HostsParser.isValidDomain(t)) continue
                when {
                    badfilter -> bad += t
                    allow -> allows += t
                    else -> blocks += t
                }
            }
            return DnsList(blocks.filter { it !in bad }, allows.toList())
        }
    }
}
