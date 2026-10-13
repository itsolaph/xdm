package com.olaph.xdm.adblock

import com.olaph.xdm.adblock.filters.DnsList
import com.olaph.xdm.adblock.filters.EngineCodec
import com.olaph.xdm.adblock.filters.FilterEngine
import com.olaph.xdm.adblock.filters.FilterEngineBuilder
import com.olaph.xdm.adblock.filters.FilterListFormat
import com.olaph.xdm.adblock.filters.FilterListSpec
import com.olaph.xdm.adblock.filters.FilterLists
import com.olaph.xdm.adblock.filters.ListCounts
import com.olaph.xdm.adblock.filters.ListFormat
import com.olaph.xdm.adblock.filters.PublicSuffixList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What Settings shows about one list. */
data class ListInfo(
    val id: String,
    val title: String,
    val enabled: Boolean,
    /** Usable rules (network + cosmetic + hosts) of the active copy; 0 while unknown or disabled. */
    val ruleCount: Int = 0,
    /** The list's own date (bundled snapshot) or the date of the downloaded copy; 0 = unknown. */
    val updatedAtMillis: Long = 0,
    /** True while the bundled snapshot is in use, false after an in-app update. */
    val bundled: Boolean = true,
    val updatable: Boolean = true,
)

data class FilterListsInfo(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val lists: List<ListInfo> = emptyList(),
    /** How long the last load took and whether it came from the compiled cache. */
    val loadMillis: Long = 0,
    val fromCache: Boolean = false,
) {
    val totalRules: Int get() = lists.filter { it.enabled }.sumOf { it.ruleCount }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Running : UpdateState
    /** [failures]: list title → reason, for lists that could not be updated (the others were). */
    data class Done(val updated: Int, val totalRules: Int, val failures: Map<String, String> = emptyMap()) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Owns the filter lists: loads the bundled snapshots (or newer downloaded copies) off the main thread, compiles
 * them into a [FilterEngine] (or loads the compiled cache when nothing changed), and swaps the engine in
 * atomically. "Update" downloads every enabled list, validates it, stores it via tmp file + atomic move and
 * rebuilds. Until the first load finishes the [AdBlocker] allows everything.
 */
class FilterListRepository(
    private val dir: File,
    /** Opens a bundled asset (throws when missing). */
    private val openAsset: (String) -> InputStream,
    private val client: OkHttpClient,
    private val adBlocker: AdBlocker,
    /** Reason the update must not run right now (e.g. Wi-Fi only on a metered network), or null. */
    private val updateBlockedReason: suspend () -> String? = { null },
    private val specs: List<FilterListSpec> = FilterLists.ALL,
    /** Download URL per list (tests point this at a mock server). */
    private val urlFor: (FilterListSpec) -> String? = { it.url },
    private val now: () -> Long = System::currentTimeMillis,
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES,
    /** Part of the cache fingerprint: a new app version always recompiles. */
    private val appVersion: Int = 0,
) {
    private val _info = MutableStateFlow(FilterListsInfo(lists = specs.map { ListInfo(it.id, it.title, true, updatable = it.url != null) }))
    val info: StateFlow<FilterListsInfo> = _info

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _update

    private val lock = Mutex()
    @Volatile private var enabledIds: Set<String> = specs.map { it.id }.toSet()
    private var psl: PublicSuffixList? = null

    private fun stored(spec: FilterListSpec): File = File(dir, if (spec.id == FilterLists.STEVENBLACK.id) LEGACY_HOSTS_NAME else "${spec.id}.txt.gz")
    private val cacheFile: File get() = File(dir, CACHE_NAME)
    private val metaFile: File get() = File(dir, META_NAME)

    /** Loads the enabled lists and installs the engine. Call from a background dispatcher; never throws. */
    suspend fun load(enabled: Set<String> = enabledIds) = withContext(Dispatchers.IO) {
        lock.withLock { loadLocked(enabled) }
    }

    private fun loadLocked(enabled: Set<String>) {
        enabledIds = enabled
        _info.value = _info.value.copy(loading = true)
        val started = System.nanoTime()
        val psl = psl ?: (runCatching { openBundled(FilterLists.PSL_ASSET).use(PublicSuffixList::read) }.getOrNull() ?: PublicSuffixList.FALLBACK)
            .also { psl = it }

        // Pick the best copy of each enabled list: a download, unless the bundled snapshot is newer.
        val sources = specs.filter { it.id in enabled }.mapNotNull { spec -> pickSource(spec)?.let { spec to it } }
        if (sources.isEmpty() && enabled.isNotEmpty()) {
            // Nothing readable at all (broken install): stay "not loaded", i.e. allow everything, as before.
            _info.value = _info.value.copy(loading = false)
            return
        }
        val fingerprint = buildString {
            append("v").append(EngineCodec.VERSION).append(";app").append(appVersion).append(";psl").append(psl.size)
            for ((spec, src) in sources) append(';').append(spec.id).append(':').append(src.fingerprint)
        }

        var engine: FilterEngine? = null
        var counts: Map<String, Int> = emptyMap()
        var fromCache = false
        if (cacheFile.isFile) {
            engine = runCatching { cacheFile.inputStream().use { EngineCodec.read(it, fingerprint, psl) } }.getOrNull()
            if (engine != null) {
                counts = readMeta(fingerprint) ?: emptyMap()
                fromCache = true
            }
        }
        if (engine == null) {
            val builder = FilterEngineBuilder(psl)
            val c = HashMap<String, Int>()
            for ((spec, src) in sources) {
                val listCounts = runCatching { src.open().use { addTo(builder, spec, it) } }.getOrElse {
                    // A damaged download falls back to the bundled snapshot.
                    if (!src.bundled) {
                        stored(spec).delete()
                        runCatching { openBundled(spec.asset).use { s -> addTo(builder, spec, s) } }.getOrNull()
                    } else {
                        null
                    }
                }
                if (listCounts != null) c[spec.id] = listCounts.total
            }
            engine = builder.build()
            counts = c
            writeCache(engine, fingerprint, counts)
        }
        adBlocker.setEngine(engine)
        val ms = (System.nanoTime() - started) / 1_000_000
        _info.value = FilterListsInfo(
            loaded = true,
            loading = false,
            lists = specs.map { spec ->
                val src = sources.firstOrNull { it.first.id == spec.id }?.second
                ListInfo(
                    id = spec.id, title = spec.title, enabled = spec.id in enabled,
                    ruleCount = counts[spec.id] ?: 0,
                    updatedAtMillis = src?.updatedAt ?: 0,
                    bundled = src?.bundled ?: true,
                    updatable = spec.url != null,
                )
            },
            loadMillis = ms,
            fromCache = fromCache,
        )
    }

    /** Downloads every enabled, updatable list and rebuilds. Never throws; the outcome is in [updateState]. */
    suspend fun update(): UpdateState = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext _update.value
        try {
            updateBlockedReason()?.let { return@withContext fail(it) }
            _update.value = UpdateState.Running
            val failures = LinkedHashMap<String, String>()
            var updated = 0
            for (spec in specs.filter { it.id in enabledIds }) {
                val url = urlFor(spec) ?: continue
                try {
                    downloadAndStore(spec, url)
                    updated++
                } catch (e: CancellationException) {
                    _update.value = UpdateState.Idle
                    throw e
                } catch (e: IOException) {
                    failures[spec.title] = e.message ?: e.javaClass.simpleName
                }
            }
            if (updated == 0) {
                return@withContext fail(failures.entries.joinToString("; ") { "${it.key}: ${it.value}" }.ifEmpty { "No list to update" })
            }
            loadLocked(enabledIds)
            UpdateState.Done(updated, _info.value.totalRules, failures).also { _update.value = it }
        } finally {
            lock.unlock()
        }
    }

    fun clearUpdateResult() {
        if (_update.value !is UpdateState.Running) _update.value = UpdateState.Idle
    }

    // ---- sources ----

    private class Source(val bundled: Boolean, val updatedAt: Long, val fingerprint: String, val open: () -> InputStream)

    private fun pickSource(spec: FilterListSpec): Source? {
        val bundledDate = runCatching { openBundled(spec.asset).use { dateOf(spec, it) } }.getOrNull()
        // Appended assets can change without the main list's date changing, so they are part of the fingerprint.
        val appended = spec.appendAssets.joinToString("") { name -> ":" + (appendedLines(name)?.hashCode() ?: 0) }
        val bundled = bundledDate?.let { d -> Source(true, d, "b$d$appended", { openBundled(spec.asset) }) }
        val file = stored(spec)
        val downloaded = if (file.isFile) {
            val d = runCatching { file.inputStream().use { dateOf(spec, it) } }.getOrNull()
            if (d == null) { file.delete(); null } else Source(false, d, "s$d:${file.length()}:${file.lastModified()}", { file.inputStream() })
        } else {
            null
        }
        // A newer app version may ship a fresher snapshot than the one downloaded earlier.
        return listOfNotNull(downloaded, bundled).maxByOrNull { it.updatedAt }
    }

    /** The date in the stored header (cheap: only the first line is read). */
    private fun dateOf(spec: FilterListSpec, input: InputStream): Long {
        val first = firstLine(input)
        val m = HEADER_DATE.find(first) ?: return if (spec.format == ListFormat.FILTERS && !spec.asset.endsWith(".gz")) 0L else throw IOException("no header")
        return m.groupValues[1].toLong()
    }

    private fun firstLine(input: InputStream): String = Gzip.maybeDecompress(input).bufferedReader(Charsets.UTF_8).readLine().orEmpty()

    /**
     * Opens a bundled asset. The APK packager stores `x.gz` decompressed as `x`, so the name without `.gz` is tried
     * too (the readers accept gzip and plain text alike).
     */
    private fun openBundled(name: String): InputStream = try {
        openAsset(name)
    } catch (e: IOException) {
        if (!name.endsWith(".gz")) throw e
        openAsset(name.removeSuffix(".gz"))
    }

    private fun addTo(builder: FilterEngineBuilder, spec: FilterListSpec, input: InputStream): ListCounts = when (spec.format) {
        ListFormat.HOSTS -> builder.addHosts(BlocklistFormat.read(input).domains)
        ListFormat.DNS -> builder.addDnsList(DnsList.parse(FilterListFormat.read(input).lines.asSequence()))
        ListFormat.FILTERS -> {
            val extra = spec.appendAssets.asSequence().flatMap { appendedLines(it).orEmpty() }
            builder.addFilters(FilterListFormat.read(input).lines.asSequence() + extra)
        }
    }

    /** Rules of an optional bundled asset, or null when the build doesn't have it. */
    private fun appendedLines(name: String): List<String>? =
        try {
            openAsset(name).use { FilterListFormat.read(it).lines }
        } catch (_: IOException) {
            null
        }

    // ---- download ----

    private fun downloadAndStore(spec: FilterListSpec, url: String) {
        val request = Request.Builder().url(url).header("Accept", "text/plain").build()
        val lines: List<String> = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            LimitedInputStream(body.byteStream(), maxDownloadBytes).bufferedReader(Charsets.UTF_8).readLines()
        }
        val listDate = FilterListFormat.listDate(lines.asSequence())
        val at = listDate ?: now()
        dir.mkdirs()
        val target = stored(spec)
        val tmp = File(dir, "${target.name}.tmp")
        try {
            when (spec.format) {
                ListFormat.HOSTS -> {
                    val domains = HostsParser.parse(lines.asSequence())
                    if (domains.size < spec.minRules) throw IOException("that doesn't look like a hosts list (${domains.size} domains)")
                    BlocklistFormat.write(domains, at, tmp)
                }
                ListFormat.DNS -> {
                    val dns = DnsList.parse(lines.asSequence())
                    if (dns.blocks.size < spec.minRules) throw IOException("only ${dns.blocks.size} usable rules")
                    FilterListFormat.write(FilterListFormat.compactDns(dns), at, tmp)
                }
                ListFormat.FILTERS -> {
                    val kept = lines.filter(FilterListFormat::keepFilterLine)
                    if (kept.size < spec.minRules) throw IOException("only ${kept.size} usable rules")
                    FilterListFormat.write(kept.asSequence(), at, tmp)
                }
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: RuntimeException) {
            tmp.delete()
            throw IOException("could not process the list: ${e.message}", e)
        }
    }

    private fun fail(message: String): UpdateState = UpdateState.Failed(message).also { _update.value = it }

    // ---- compiled cache ----

    private fun writeCache(engine: FilterEngine, fingerprint: String, counts: Map<String, Int>) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$CACHE_NAME.tmp")
            tmp.outputStream().use { EngineCodec.write(engine, fingerprint, it) }
            Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val metaTmp = File(dir, "$META_NAME.tmp")
            metaTmp.writeText(fingerprint + "\n" + counts.entries.joinToString("\n") { "${it.key}=${it.value}" })
            Files.move(metaTmp.toPath(), metaFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            File(dir, "$CACHE_NAME.tmp").delete()
            cacheFile.delete() // never leave a cache that does not match the lists
        }
    }

    private fun readMeta(fingerprint: String): Map<String, Int>? = runCatching {
        val lines = metaFile.readLines()
        if (lines.firstOrNull() != fingerprint) return null
        lines.drop(1).mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }.toMap()
    }.getOrNull()

    /** Stops reading after [limit] bytes: a hostile or broken server cannot make the parser run away. */
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) add(it) }

        private fun add(n: Int) {
            count += n
            if (count > limit) throw IOException("list is larger than the $limit byte limit")
        }
    }

    companion object {
        /** The 0.3.0 hosts download keeps its name so an earlier in-app update is still used. */
        const val LEGACY_HOSTS_NAME = "blocklist.txt.gz"
        const val CACHE_NAME = "filters.bin"
        const val META_NAME = "filters.meta"
        const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
        private val HEADER_DATE = Regex("^\\W*[!#]\\s*xdm-(?:list|blocklist)\\s+updated=(\\d+)")
    }
}
