package com.olaph.xdm.adblock.filters

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Loads every bundled list and matches 10k URLs, printing the timings. The bounds are deliberately generous
 * (CI runners vary a lot); the printed numbers are what matter.
 */
class FilterBenchmarkTest {
    private fun urls(count: Int): List<Pair<String, Int>> {
        val rnd = Random(42)
        val hosts = listOf(
            "www.example.com", "cdn.example.com", "static.news-site.co.uk", "i.imgur.com", "fonts.gstatic.com",
            "ajax.googleapis.com", "www.google-analytics.com", "securepubads.g.doubleclick.net", "go.popads.example",
            "creative.adnet-one.example", "cdn.videohub-media.example", "videohub.example", "img.thumbs-cdn.example", "pagead2.googlesyndication.com",
            "cdnjs.cloudflare.com", "connect.facebook.net", "platform.twitter.com", "www.youtube.com", "i.ytimg.com",
            "media-hls.stream-edge.example", "ads.pubmatic.com", "s.yimg.com", "user.github.io", "api.shop.com.br",
        )
        val words = listOf("ads", "banner", "img", "static", "js", "v2", "player", "assets", "pop", "track", "pixel", "user", "content", "video", "thumb", "widget", "lib", "main", "app", "cdn")
        val exts = listOf(".js", ".css", ".png", ".jpg", ".gif", ".m3u8", ".ts", "", ".html", ".json", ".woff2", ".mp4")
        val types = listOf(RequestType.SCRIPT, RequestType.IMAGE, RequestType.STYLESHEET, RequestType.XHR, RequestType.SUBDOCUMENT, RequestType.MEDIA, RequestType.UNKNOWN)
        return List(count) {
            val host = hosts[rnd.nextInt(hosts.size)]
            val path = (0 until 1 + rnd.nextInt(4)).joinToString("/") { words[rnd.nextInt(words.size)] + if (rnd.nextBoolean()) rnd.nextInt(1000) else "" }
            val ext = exts[rnd.nextInt(exts.size)]
            val query = if (rnd.nextInt(3) == 0) "?id=${rnd.nextInt(100000)}&ref=${words[rnd.nextInt(words.size)]}&cb=${rnd.nextLong()}" else ""
            "https://$host/$path$ext$query" to types[rnd.nextInt(types.size)]
        }
    }

    private fun usedHeap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test fun loadAndMatchTenThousandUrls() {
        val before = usedHeap()
        val t0 = System.nanoTime()
        val engine = TestLists.build()
        val buildMs = (System.nanoTime() - t0) / 1e6
        val stats = engine.stats
        println("[bench] build all lists: %.0f ms; %s".format(buildMs, stats))
        // Rough (GC-dependent) retained size of the compiled engine, for the record only.
        println("[bench] retained heap ≈ %.1f MB".format((usedHeap() - before) / 1e6))
        for (spec in FilterLists.ALL) {
            val ta = System.nanoTime()
            val b = FilterEngineBuilder(TestLists.psl)
            val c = TestLists.add(b, spec)
            val tb = System.nanoTime()
            b.build()
            println("[bench] %s: parse %.0f ms, build %.0f ms, %s".format(spec.id, (tb - ta) / 1e6, (System.nanoTime() - tb) / 1e6, c))
        }
        val t2 = System.nanoTime()
        TestLists.build()
        println("[bench] warm full build: %.0f ms".format((System.nanoTime() - t2) / 1e6))

        // The compiled cache the app loads instead of re-parsing.
        val bytes = java.io.ByteArrayOutputStream()
        val t3 = System.nanoTime()
        EngineCodec.write(engine, "bench", bytes)
        val writeMs = (System.nanoTime() - t3) / 1e6
        var loaded: FilterEngine? = null
        repeat(3) {
            val t4 = System.nanoTime()
            loaded = EngineCodec.read(bytes.toByteArray().inputStream(), "bench", TestLists.psl)
            println("[bench] cache: %,d bytes, write %.0f ms, load %.0f ms".format(bytes.size(), writeMs, (System.nanoTime() - t4) / 1e6))
        }
        assertTrue(loaded!!.stats == stats)

        val pages = listOf("https://videohub.example/", "https://www.example.com/article/1", "https://news-site.co.uk/", "https://clipsite.example/123.html")
            .map { engine.page(it) }
        val list = urls(10_000)
        // Warm-up (JIT), then the measured run.
        repeat(2) { list.forEachIndexed { i, (u, t) -> engine.match(u, t, pages[i % pages.size]) } }
        val t1 = System.nanoTime()
        var blocked = 0
        list.forEachIndexed { i, (u, t) -> if (engine.match(u, t, pages[i % pages.size]).blocked) blocked++ }
        val perRequestMs = (System.nanoTime() - t1) / 1e6 / list.size
        println("[bench] match 10k URLs: %.4f ms/request (%d blocked)".format(perRequestMs, blocked))

        assertTrue("rules loaded: $stats", stats.networkRules > 10_000 && stats.hostRules > 100_000 && stats.cosmeticRules > 10_000)
        assertTrue("matching is far too slow: $perRequestMs ms/request", perRequestMs < 2.0)
        assertTrue("building is far too slow: $buildMs ms", buildMs < 60_000)
    }
}
