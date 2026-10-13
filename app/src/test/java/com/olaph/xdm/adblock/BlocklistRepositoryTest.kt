package com.olaph.xdm.adblock

import com.olaph.xdm.adblock.filters.FilterListFormat
import com.olaph.xdm.adblock.filters.FilterListSpec
import com.olaph.xdm.adblock.filters.ListFormat
import com.olaph.xdm.adblock.filters.RequestType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.GZIPOutputStream

/**
 * [FilterListRepository]: bundled snapshots, downloads (MockWebServer), atomic swap, compiled cache, fallbacks.
 * (Successor of the hosts-only BlocklistRepository tests; the hosts list is one of the lists now.)
 */
class FilterListRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val blocker = AdBlocker().apply { configure(true, emptySet()) }
    private var clock = 2_000_000L

    /** Responses by path; a missing entry is a 404. */
    private val responses = HashMap<String, MockResponse>()

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = responses[request.path] ?: MockResponse().setResponseCode(404)
            }
            start()
        }
        dir = File(tmp.root, "adblock")
    }

    @After fun tearDown() = server.shutdown()

    private fun gz(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private val hostsSpec = FilterListSpec("stevenblack", "Hosts", ListFormat.HOSTS, "hosts.gz", "/hosts", "", "MIT", minRules = 1_000)
    private val filtersSpec = FilterListSpec("easylist", "EasyList", ListFormat.FILTERS, "easylist.gz", "/easylist", "", "GPLv3", minRules = 3)

    private fun bundledHosts(vararg domains: String, updated: Long = 1_000L) =
        gz("# xdm-blocklist updated=$updated\n" + domains.joinToString("\n") + "\n")

    private fun bundledFilters(vararg rules: String, updated: Long = 1_000L) =
        gz("! xdm-list updated=$updated\n" + rules.joinToString("\n") + "\n")

    private fun hosts(count: Int, prefix: String = "d") =
        "# Title: StevenBlack/hosts\r\n127.0.0.1 localhost\r\n" + (1..count).joinToString("") { "0.0.0.0 $prefix$it.example.com\r\n" }

    private val defaultAssets: Map<String, ByteArray>
        get() = mapOf(
            "hosts.gz" to bundledHosts("old.example.com"),
            "easylist.gz" to bundledFilters("||oldfilter.net^", "example.org##.old-ad"),
        )

    private fun repo(
        assets: Map<String, ByteArray> = defaultAssets,
        reason: String? = null,
        maxBytes: Long = FilterListRepository.MAX_DOWNLOAD_BYTES,
        target: AdBlocker = blocker,
        specs: List<FilterListSpec> = listOf(hostsSpec, filtersSpec),
    ) = FilterListRepository(
        dir = dir,
        openAsset = { name -> ByteArrayInputStream(assets[name] ?: throw FileNotFoundException(name)) },
        client = OkHttpClient(),
        adBlocker = target,
        updateBlockedReason = { reason },
        specs = specs,
        urlFor = { spec -> spec.url?.let { server.url(it).toString() } },
        now = { clock },
        maxDownloadBytes = maxBytes,
    )

    private val all = setOf("stevenblack", "easylist")
    private fun blocks(host: String) = blocker.shouldBlock(host, "news.org")
    private fun info(r: FilterListRepository, id: String) = r.info.value.lists.first { it.id == id }

    @Test fun loadsTheBundledSnapshots() = runBlocking {
        val r = repo()
        assertFalse(blocker.isLoaded)
        r.load(all)
        assertTrue(blocks("old.example.com"))
        assertTrue(blocks("cdn.oldfilter.net"))
        assertTrue(r.info.value.loaded)
        assertEquals(ListInfo("stevenblack", "Hosts", true, 1, 1_000L, bundled = true), info(r, "stevenblack"))
        assertEquals(2, info(r, "easylist").ruleCount)
        assertEquals(3, r.info.value.totalRules)
    }

    @Test fun assetsAsPackagedInTheApkAreFound() = runBlocking {
        // The APK packager stores `hosts.gz` decompressed as `hosts`: the repository must find and read it.
        val r = repo(
            assets = mapOf(
                "hosts" to "# xdm-blocklist updated=1000\nold.example.com\n".toByteArray(),
                "easylist" to "! xdm-list updated=1000\n||oldfilter.net^\n".toByteArray(),
            ),
        )
        r.load(all)
        assertTrue(blocks("old.example.com"))
        assertTrue(blocks("oldfilter.net"))
        assertEquals(1_000L, info(r, "stevenblack").updatedAtMillis)
    }

    @Test fun missingAssetsLeaveEverythingAllowed() = runBlocking {
        val r = repo(assets = emptyMap())
        r.load(all)
        assertFalse(blocker.isLoaded)
        assertFalse(r.info.value.loaded)
    }

    private val extraSpec = FilterListSpec(
        "xdm-extra", "Extra", ListFormat.FILTERS, "extra.txt", null, "", "part of XDM", minRules = 1,
        appendAssets = listOf("private.txt"),
    )

    private fun extraRepo(vararg assets: Pair<String, String>, target: AdBlocker = blocker) =
        repo(assets = assets.associate { (k, v) -> k to v.toByteArray() }, target = target, specs = listOf(extraSpec))

    @Test fun missingPrivateRulesAreSkippedQuietly() = runBlocking {
        val r = extraRepo("extra.txt" to "! Title: Extra\n||public-ad.example^\n")
        r.load(setOf("xdm-extra"))
        assertTrue(r.info.value.loaded)
        assertTrue(blocks("public-ad.example"))
        assertEquals(1, info(r, "xdm-extra").ruleCount)
    }

    @Test fun presentPrivateRulesAreAddedToTheList() = runBlocking {
        val r = extraRepo("extra.txt" to "! Title: Extra\n||public-ad.example^\n", "private.txt" to "! mine\n||private-ad.example^\n")
        r.load(setOf("xdm-extra"))
        assertTrue(blocks("public-ad.example"))
        assertTrue(blocks("private-ad.example"))
        assertEquals(2, info(r, "xdm-extra").ruleCount)
    }

    @Test fun changedPrivateRulesInvalidateTheCompiledCache() = runBlocking {
        val base = "extra.txt" to "! Title: Extra\n"
        extraRepo(base, "private.txt" to "||first.example^\n").load(setOf("xdm-extra"))
        val fresh = AdBlocker().apply { configure(true, emptySet()) }
        val r = extraRepo(base, "private.txt" to "||second.example^\n", target = fresh)
        r.load(setOf("xdm-extra"))
        assertFalse(r.info.value.fromCache)
        assertTrue(fresh.shouldBlock("second.example", "news.org"))
        assertFalse(fresh.shouldBlock("first.example", "news.org"))
    }

    @Test fun disabledListsAreNotLoaded() = runBlocking {
        val r = repo()
        r.load(setOf("easylist"))
        assertFalse(blocks("old.example.com"))
        assertTrue(blocks("oldfilter.net"))
        assertFalse(info(r, "stevenblack").enabled)
        r.load(all)
        assertTrue("re-enabled", blocks("old.example.com"))
    }

    @Test fun secondLoadUsesTheCompiledCache() = runBlocking {
        repo().load(all)
        assertTrue(File(dir, FilterListRepository.CACHE_NAME).isFile)
        val fresh = AdBlocker().apply { configure(true, emptySet()) }
        val r2 = repo(target = fresh)
        r2.load(all)
        assertTrue(r2.info.value.fromCache)
        assertTrue(fresh.shouldBlock("old.example.com", "news.org"))
        assertEquals("counts survive the cache", 2, info(r2, "easylist").ruleCount)
        // A different set of lists is a different cache key.
        r2.load(setOf("easylist"))
        assertFalse(r2.info.value.fromCache)
        assertFalse(fresh.shouldBlock("old.example.com", "news.org"))
    }

    @Test fun corruptCacheIsRebuilt() = runBlocking {
        dir.mkdirs()
        File(dir, FilterListRepository.CACHE_NAME).writeText("garbage")
        val r = repo()
        r.load(all)
        assertFalse(r.info.value.fromCache)
        assertTrue(blocks("old.example.com"))
        assertTrue(File(dir, FilterListRepository.CACHE_NAME).length() > 20)
    }

    @Test fun updateDownloadsPreprocessesStoresAndSwaps() = runBlocking {
        responses["/hosts"] = MockResponse().setBody(hosts(2_000))
        responses["/easylist"] = MockResponse().setBody(
            "[Adblock Plus 2.0]\n! Last modified: 09 Oct 2026 18:18 UTC\n! comment\n||newfilter.com^\nexample.org##.ad\n" +
                "example.org##+js(set, a, 1)\nexample.org#\$#body { color: red }\n/banner/*\$image\n@@||good.newfilter.com^\n",
        )
        val r = repo()
        r.load(all)
        assertTrue(blocks("old.example.com"))

        val result = r.update()

        assertTrue(result.toString(), result is UpdateState.Done)
        assertEquals(2, (result as UpdateState.Done).updated)
        assertTrue(result.failures.isEmpty())
        assertFalse("old hosts list is replaced", blocks("old.example.com"))
        assertTrue(blocks("d17.example.com"))
        assertFalse("localhost is not a domain", blocks("localhost"))
        assertFalse("old filter list is replaced", blocks("oldfilter.net"))
        assertTrue(blocks("x.newfilter.com"))
        val page = blocker.page("https://news.org/")
        assertFalse("its exception works", blocker.shouldBlockRequest("https://good.newfilter.com/a.js", RequestType.SCRIPT, page))
        assertTrue(blocker.shouldBlockRequest("https://cdn.news.org/banner/1.png", RequestType.IMAGE, page))

        val hostsInfo = info(r, "stevenblack")
        assertEquals(2_000, hostsInfo.ruleCount)
        assertFalse(hostsInfo.bundled)
        assertEquals("no date in the hosts download: download time", clock, hostsInfo.updatedAtMillis)
        assertEquals("the list's own date", 1_791_569_880_000L, info(r, "easylist").updatedAtMillis)

        // Stored copies: preprocessed (comments, scriptlets and CSS injection dropped), atomically moved into place.
        val stored = File(dir, "easylist.txt.gz").inputStream().use(FilterListFormat::read).lines
        assertEquals(listOf("||newfilter.com^", "example.org##.ad", "/banner/*\$image", "@@||good.newfilter.com^"), stored)
        assertTrue(File(dir, FilterListRepository.LEGACY_HOSTS_NAME).isFile)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun updatedListsSurviveARestart() = runBlocking {
        responses["/hosts"] = MockResponse().setBody(hosts(1_500))
        responses["/easylist"] = MockResponse().setBody("||a.com^\n||b.com^\n||c.com^\n")
        repo().apply { load(all); update() }

        val freshBlocker = AdBlocker().apply { configure(true, emptySet()) }
        val restarted = repo(target = freshBlocker)
        restarted.load(all)

        assertTrue(freshBlocker.shouldBlock("d9.example.com", "news.org"))
        assertTrue(freshBlocker.shouldBlock("b.com", "news.org"))
        assertEquals(1_500, info(restarted, "stevenblack").ruleCount)
        assertFalse(info(restarted, "stevenblack").bundled)
    }

    @Test fun aFresherBundledSnapshotBeatsAnOlderDownload() = runBlocking {
        responses["/hosts"] = MockResponse().setBody(hosts(1_200))
        repo().apply { load(all); update() } // stored at clock = 2_000_000

        val newerApp = repo(assets = defaultAssets + ("hosts.gz" to bundledHosts("new.example.com", updated = 9_000_000L)))
        newerApp.load(all)

        assertTrue(blocks("new.example.com"))
        assertFalse(blocks("d5.example.com"))
        assertTrue(info(newerApp, "stevenblack").bundled)
    }

    @Test fun serverErrorKeepsTheCurrentLists() = runBlocking {
        responses["/hosts"] = MockResponse().setResponseCode(503)
        responses["/easylist"] = MockResponse().setResponseCode(503)
        val r = repo()
        r.load(all)
        val result = r.update()
        assertTrue(result is UpdateState.Failed)
        assertTrue((result as UpdateState.Failed).message.contains("503"))
        assertTrue(blocks("old.example.com"))
        assertTrue(blocks("oldfilter.net"))
        assertFalse(File(dir, FilterListRepository.LEGACY_HOSTS_NAME).exists())
    }

    @Test fun oneFailedListDoesNotStopTheOthers() = runBlocking {
        responses["/hosts"] = MockResponse().setResponseCode(503)
        responses["/easylist"] = MockResponse().setBody("||a.com^\n||b.com^\n||c.com^\n")
        val r = repo()
        r.load(all)
        val result = r.update() as UpdateState.Done
        assertEquals(1, result.updated)
        assertEquals(setOf("Hosts"), result.failures.keys)
        assertTrue(blocks("old.example.com"))
        assertTrue(blocks("a.com"))
    }

    @Test fun tinyOrGarbageListsAreRejected() = runBlocking {
        responses["/hosts"] = MockResponse().setBody("<html>captive portal</html>")
        responses["/easylist"] = MockResponse().setBody("! only comments\n")
        val r = repo()
        r.load(all)
        assertTrue(r.update() is UpdateState.Failed)
        responses["/hosts"] = MockResponse().setBody(hosts(10))
        assertTrue(r.update() is UpdateState.Failed)
        assertTrue("current list untouched", blocks("old.example.com"))
        assertEquals(1, info(r, "stevenblack").ruleCount)
    }

    @Test fun oversizedDownloadIsAborted() = runBlocking {
        responses["/hosts"] = MockResponse().setBody(hosts(5_000))
        val r = repo(maxBytes = 10_000)
        r.load(all)
        val result = r.update()
        assertTrue(result is UpdateState.Failed)
        assertTrue(blocks("old.example.com"))
    }

    @Test fun wifiOnlyOnMeteredNetworkDoesNotTouchTheNetwork() = runBlocking {
        val r = repo(reason = "Wi-Fi only is on")
        r.load(all)
        val result = r.update()
        assertEquals(UpdateState.Failed("Wi-Fi only is on"), result)
        assertEquals(0, server.requestCount)
    }

    @Test fun onlyEnabledListsAreDownloaded() = runBlocking {
        responses["/easylist"] = MockResponse().setBody("||a.com^\n||b.com^\n||c.com^\n")
        val r = repo()
        r.load(setOf("easylist"))
        assertTrue(r.update() is UpdateState.Done)
        assertEquals(1, server.requestCount)
        assertEquals("/easylist", server.takeRequest().path)
    }

    @Test fun corruptStoredFileFallsBackToTheBundledSnapshot() = runBlocking {
        dir.mkdirs()
        File(dir, FilterListRepository.LEGACY_HOSTS_NAME).writeText("this is not gzip")
        val r = repo()
        r.load(all)
        assertTrue(blocks("old.example.com"))
        assertFalse("bad file is removed", File(dir, FilterListRepository.LEGACY_HOSTS_NAME).exists())
    }

    @Test fun formatRoundTripIsSortedAndKeepsTheDate() {
        val f = tmp.newFile("x.gz")
        BlocklistFormat.write(setOf("b.example.com", "a.example.com"), 1234L, f)
        val loaded = f.inputStream().use(BlocklistFormat::read)
        assertEquals(setOf("a.example.com", "b.example.com"), loaded.domains)
        assertEquals(1234L, loaded.updatedAtMillis)
    }

    @Test fun headerlessFileStillParses() {
        val loaded = BlocklistFormat.read(ByteArrayInputStream(gz("first.example.com\nsecond.example.com\n")))
        assertEquals(setOf("first.example.com", "second.example.com"), loaded.domains)
        assertEquals(0L, loaded.updatedAtMillis)
    }

    @Test fun readersAcceptGzipAndPlainText() {
        val text = "# xdm-blocklist updated=7\na.example.com\n"
        assertEquals(setOf("a.example.com"), BlocklistFormat.read(ByteArrayInputStream(gz(text))).domains)
        assertEquals(setOf("a.example.com"), BlocklistFormat.read(ByteArrayInputStream(text.toByteArray())).domains)
        assertEquals(7L, BlocklistFormat.read(ByteArrayInputStream(text.toByteArray())).updatedAtMillis)
        assertEquals(listOf("||a.com^"), FilterListFormat.read(ByteArrayInputStream("! xdm-list updated=1\n||a.com^\n".toByteArray())).lines)
        val psl = com.olaph.xdm.adblock.filters.PublicSuffixList.read(ByteArrayInputStream("// c\nuk\nco.uk\n".toByteArray()))
        assertEquals("x.co.uk", psl.registrableDomain("a.x.co.uk"))
        assertEquals("", Gzip.maybeDecompress(ByteArrayInputStream(ByteArray(0))).readBytes().decodeToString())
    }

    @Test fun filterListFormatRoundTripAndPlainText() {
        val f = tmp.newFile("y.gz")
        FilterListFormat.write(sequenceOf("||a.com^", "##.ad"), 42L, f)
        val loaded = f.inputStream().use(FilterListFormat::read)
        assertEquals(listOf("||a.com^", "##.ad"), loaded.lines)
        assertEquals(42L, loaded.updatedAtMillis)
        // The built-in list ships as plain text.
        val plain = FilterListFormat.read(ByteArrayInputStream("! Title: x\n||b.com^\n".toByteArray()))
        assertEquals(listOf("! Title: x", "||b.com^"), plain.lines)
    }

    @Test fun listDatesFromTheKnownHeaders() {
        assertEquals(1_791_569_880_000L, FilterListFormat.listDate(sequenceOf("! Last modified: 09 Oct 2026 18:18 UTC")))
        assertEquals(1_791_565_511_000L, FilterListFormat.listDate(sequenceOf("! TimeUpdated: 2026-10-09T17:05:11+00:00")))
        assertEquals(1_791_569_933_763L, FilterListFormat.listDate(sequenceOf("! Last modified: 2026-10-09T18:18:53.763Z")))
        assertEquals(1_791_394_496_000L, FilterListFormat.listDate(sequenceOf("# Date: 07 October 2026 17:34:56 (UTC)")))
        assertEquals(null, FilterListFormat.listDate(sequenceOf("! Title: none")))
    }
}
