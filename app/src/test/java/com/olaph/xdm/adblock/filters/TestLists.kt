package com.olaph.xdm.adblock.filters

import com.olaph.xdm.adblock.BlocklistFormat
import java.io.File

/** The real bundled lists (unit tests run with the module directory as working directory). */
object TestLists {
    private val assets = File("src/main/assets")

    fun file(asset: String) = File(assets, asset)

    val psl: PublicSuffixList by lazy { file(FilterLists.PSL_ASSET).inputStream().use(PublicSuffixList::read) }

    /** Feeds one bundled list into [builder] the way the app does. */
    fun add(builder: FilterEngineBuilder, spec: FilterListSpec): ListCounts = when (spec.format) {
        ListFormat.HOSTS -> builder.addHosts(file(spec.asset).inputStream().use(BlocklistFormat::read).domains)
        ListFormat.DNS -> builder.addDnsList(DnsList.parse(file(spec.asset).inputStream().use(FilterListFormat::read).lines.asSequence()))
        ListFormat.FILTERS -> builder.addFilters(file(spec.asset).inputStream().use(FilterListFormat::read).lines.asSequence())
    }

    /** All bundled lists compiled once and shared by the tests that need the real thing. */
    val engine: FilterEngine by lazy { build() }

    fun build(specs: List<FilterListSpec> = FilterLists.ALL): FilterEngine {
        val b = FilterEngineBuilder(psl)
        specs.forEach { add(b, it) }
        return b.build()
    }

    /** The bundled lists plus inline [rules], for tests that need rules a plain checkout does not bundle. */
    fun buildWith(rules: List<String>, specs: List<FilterListSpec> = FilterLists.ALL): FilterEngine {
        val b = FilterEngineBuilder(psl)
        specs.forEach { add(b, it) }
        b.addFilters(rules.asSequence())
        return b.build()
    }

    /** A small engine from inline rules (for semantic tests). */
    fun engineOf(vararg rules: String, hosts: Collection<String> = emptyList()): FilterEngine {
        val b = FilterEngineBuilder(psl)
        if (hosts.isNotEmpty()) b.addHosts(hosts)
        b.addFilters(rules.asSequence())
        return b.build()
    }
}
