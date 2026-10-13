package com.olaph.xdm.adblock.filters

import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.olaph.xdm.adblock.Gzip
import java.util.zip.GZIPOutputStream

/** How a list's lines are interpreted. */
enum class ListFormat {
    /** Hosts file (`0.0.0.0 host`) or one domain per line. */
    HOSTS,
    /** EasyList / AdGuard syntax. */
    FILTERS,
    /** Whole-host rules only (`||host^`, `@@||host^`), as in the AdGuard DNS filter. */
    DNS,
}

/** One filter list XDM knows about. */
data class FilterListSpec(
    val id: String,
    val title: String,
    val format: ListFormat,
    /** Bundled snapshot in assets (gzip unless it ends in `.txt`). */
    val asset: String,
    /** Where "Update filter lists" downloads it from; null = bundled only. */
    val url: String?,
    val homepage: String,
    val license: String,
    /** A download with fewer usable rules is rejected as broken. */
    val minRules: Int,
    /** Optional bundled assets whose rules are added to this list; a missing one is skipped silently. */
    val appendAssets: List<String> = emptyList(),
)

/** Generated at build time from `privateRulesFile` (see README); absent in a plain checkout. */
const val PRIVATE_RULES_ASSET = "xdm-private.txt"

object FilterLists {
    val EASYLIST = FilterListSpec(
        id = "easylist", title = "EasyList", format = ListFormat.FILTERS,
        asset = "filters/easylist.txt.gz", url = "https://easylist.to/easylist/easylist.txt",
        homepage = "https://easylist.to/", license = "GPLv3 / CC BY-SA 3.0", minRules = 10_000,
    )
    val ADGUARD_BASE = FilterListSpec(
        id = "adguard-base", title = "AdGuard Base (without EasyList)", format = ListFormat.FILTERS,
        asset = "filters/adguard-base.txt.gz", url = "https://filters.adtidy.org/extension/ublock/filters/2_without_easylist.txt",
        homepage = "https://github.com/AdguardTeam/AdguardFilters", license = "GPLv3", minRules = 10_000,
    )
    val ADGUARD_DNS = FilterListSpec(
        id = "adguard-dns", title = "AdGuard DNS filter (domains)", format = ListFormat.DNS,
        asset = "filters/adguard-dns.txt.gz", url = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
        homepage = "https://github.com/AdguardTeam/AdGuardSDNSFilter", license = "GPLv3", minRules = 10_000,
    )
    val STEVENBLACK = FilterListSpec(
        id = "stevenblack", title = "StevenBlack unified hosts", format = ListFormat.HOSTS,
        asset = "blocklist.txt.gz", url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        homepage = "https://github.com/StevenBlack/hosts", license = "MIT", minRules = 1_000,
    )
    val XDM_EXTRA = FilterListSpec(
        id = "xdm-extra", title = "XDM extra rules", format = ListFormat.FILTERS,
        asset = "xdm-extra.txt", url = null,
        homepage = "built in", license = "part of XDM", minRules = 1,
        appendAssets = listOf(PRIVATE_RULES_ASSET),
    )

    val ALL: List<FilterListSpec> = listOf(EASYLIST, ADGUARD_BASE, ADGUARD_DNS, STEVENBLACK, XDM_EXTRA)

    fun byId(id: String): FilterListSpec? = ALL.firstOrNull { it.id == id }

    const val PSL_ASSET = "filters/public_suffix_list.dat.gz"
}

/**
 * Stored list format (bundled snapshots and downloaded updates alike): gzip text whose first line is
 * `! xdm-list updated=<epoch ms>` followed by the preprocessed rules. The hosts list keeps its own
 * `# xdm-blocklist` header ([com.olaph.xdm.adblock.BlocklistFormat]).
 */
object FilterListFormat {
    private val HEADER = Regex("^[!#/]+\\s*xdm-(?:list|blocklist|psl)\\s+updated=(\\d+)")

    class Loaded(val lines: List<String>, val updatedAtMillis: Long)

    /** Reads a stored list; plain-text (non-gzip) input is accepted too (the built-in list ships as `.txt`). */
    fun read(input: InputStream): Loaded {
        Gzip.maybeDecompress(input).bufferedReader(Charsets.UTF_8).use { reader ->
            val lines = ArrayList<String>()
            var updated = 0L
            var first = true
            while (true) {
                val line = reader.readLine() ?: break
                if (first) {
                    first = false
                    val m = HEADER.find(line.removePrefix(BOM))
                    if (m != null) { updated = m.groupValues[1].toLongOrNull() ?: 0L; continue }
                }
                lines += line
            }
            if (updated == 0L) updated = listDate(lines.asSequence()) ?: 0L
            return Loaded(lines, updated)
        }
    }

    fun write(lines: Sequence<String>, updatedAtMillis: Long, file: File) {
        GZIPOutputStream(file.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("! xdm-list updated=$updatedAtMillis\n")
            for (l in lines) { w.write(l); w.write("\n") }
        }
    }

    private val UNSUPPORTED = listOf("#$#", "#@$#", "#%#", "#@%#", "#?#", "#@?#", "#$?#", "#@$?#", "##+js(", "#@#+js(", "##^", "#@#^", "$$", "$@$")

    /** Drops comments and rule kinds the engine never uses (same filter as the `updateFilterLists` Gradle task). */
    fun keepFilterLine(raw: String): Boolean {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[Adblock") || line.startsWith("[$")) return false
        if (line == "#" || line.startsWith("# ")) return false
        return UNSUPPORTED.none { line.contains(it) }
    }

    /** DNS lists are stored as `host` / `@@host` lines. */
    fun compactDns(list: DnsList): Sequence<String> = list.blocks.sorted().asSequence() + list.allows.sorted().asSequence().map { "@@$it" }

    /**
     * The list's own date from its header (`! Last modified: 09 Oct 2026 18:18 UTC`,
     * `! TimeUpdated: 2026-10-09T17:05:11+00:00`, `! Last modified: 2026-10-09T18:18:53.763Z`, hosts `# Date: …`).
     */
    fun listDate(lines: Sequence<String>): Long? {
        for (raw in lines.take(60)) {
            val line = raw.trim()
            val v = DATE_LINE.find(line)?.groupValues?.get(1)?.trim() ?: continue
            runCatching { return OffsetDateTime.parse(v).toInstant().toEpochMilli() }
            runCatching { return Instant.parse(v).toEpochMilli() }
            runCatching {
                return LocalDateTime.parse(v.removeSuffix(" UTC"), DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH))
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            }
            runCatching {
                return LocalDateTime.parse(v.removeSuffix(" (UTC)"), DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm:ss", Locale.ENGLISH))
                    .toInstant(ZoneOffset.UTC).toEpochMilli()
            }
        }
        return null
    }

    private val DATE_LINE = Regex("^[!#] (?:Last modified|TimeUpdated|Date): (.+)$")
    private val BOM: String = Char(0xFEFF).toString()
}
