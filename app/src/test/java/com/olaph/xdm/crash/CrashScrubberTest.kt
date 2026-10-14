package com.olaph.xdm.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashScrubberTest {

    @Test fun `http and https urls are replaced`() {
        val out = CrashScrubber.scrub("failed for https://example.com/a/b.mp4?token=1 and http://x.test/y end")
        assertEquals("failed for <url> and <url> end", out)
    }

    @Test fun `file and content uris are replaced too`() {
        val out = CrashScrubber.scrub("bad uri content://media/external/video/media/42 or file:///storage/emulated/0/Movies/a.mp4")
        assertFalse(out.contains("media/external"))
        assertFalse(out.contains("a.mp4"))
    }

    @Test fun `storage and sdcard paths are replaced including names with spaces`() {
        val out = CrashScrubber.scrub(
            "java.io.FileNotFoundException: /storage/emulated/0/Download/My Secret Video.mp4: open failed: ENOENT\n" +
                "Caused by: java.io.IOException: cannot write /sdcard/Movies/x.ts: no space",
        )
        assertEquals(
            "java.io.FileNotFoundException: <path>: open failed: ENOENT\n" +
                "Caused by: java.io.IOException: cannot write <path>: no space",
            out,
        )
    }

    @Test fun `path at end of line is replaced`() {
        assertEquals("cannot open <path>", CrashScrubber.scrub("cannot open /storage/emulated/0/Android/data/com.olaph.xdm/files/downloads/clip 1.part"))
    }

    @Test fun `private folder paths and names never reach a report`() {
        val out = CrashScrubber.scrub(
            "java.io.FileNotFoundException: /data/user/0/com.olaph.xdm/files/private/My Holiday.mp4: open failed: EACCES\n" +
                "Caused by: java.io.IOException: rename failed /data/data/com.olaph.xdm/files/private/.My Holiday.mp4.part\n" +
                "Caused by: java.lang.IllegalStateException: bad file:///data/user/0/com.olaph.xdm/files/private/My%20Holiday.mp4",
        )
        assertFalse(out, out.contains("Holiday"))
        assertFalse(out, out.contains("private/"))
        assertTrue(out.contains("FileNotFoundException: <path>: open failed: EACCES"))
    }

    @Test fun `class names and code frames are left alone`() {
        val frame = "\tat com.olaph.xdm.engine.DownloadEngine.run(DownloadEngine.kt:120)"
        assertEquals(frame, CrashScrubber.scrub(frame))
    }

    @Test fun `host names in network exception messages are removed`() {
        val out = CrashScrubber.scrub(
            "java.net.UnknownHostException: Unable to resolve host \"some-site.example\": No address associated with hostname\n" +
                "Caused by: javax.net.ssl.SSLPeerUnverifiedException: Hostname other-site.example not verified",
        )
        assertFalse(out.contains("some-site"))
        assertFalse(out.contains("other-site"))
        assertTrue(out.contains("java.net.UnknownHostException: <host>"))
        assertTrue(out.contains("Caused by: javax.net.ssl.SSLPeerUnverifiedException: <host>"))
    }

    @Test fun `url scanning edge cases`() {
        assertEquals("a <url> b <url>", CrashScrubber.scrub("a svn+ssh://host/x?y=1 b x-y.z1://q"))
        assertEquals("1://x stays: not a scheme", CrashScrubber.scrub("1://x stays: not a scheme"))
        assertEquals("no host after :// stays https://", CrashScrubber.scrub("no host after :// stays https://"))
        assertEquals("<url>", CrashScrubber.scrub("ftp://a"))
        assertEquals("(<url>", CrashScrubber.scrub("(https://a.test/b)")) // a URL runs to the next white space
        assertEquals("x <url>\nnext <url>", CrashScrubber.scrub("x http://a.test/1\nnext https://b.test/2"))
        assertEquals("1<url>", CrashScrubber.scrub("1http://x.test/y"))
    }

    // ---- YouTube ----

    private val vid = "dQw4w9WgXcQ"

    @Test fun `youtube video ids after v= and the short link paths are replaced`() {
        for (text in listOf(
            "failed for https://www.youtube.com/watch?v=$vid&t=1",
            "failed for www.youtube.com/watch?v=$vid", "query was v=$vid&feature=share", "link youtu.be/$vid?si=abc",
            "bare youtu.be/$vid", "page /shorts/$vid failed", "shorts/$vid", "embed/$vid", "live/$vid", "thumb i.ytimg.com/vi/$vid/hqdefault.jpg",
        )) {
            val out = CrashScrubber.scrub(text)
            assertFalse("id still in: $out", out.contains(vid))
        }
        assertEquals("watch?v=<id>", CrashScrubber.scrub("watch?v=$vid"))
        assertEquals("see youtu.be/<id> now", CrashScrubber.scrub("see youtu.be/$vid now"))
        assertEquals("a shorts/<id>.", CrashScrubber.scrub("a shorts/$vid."))
    }

    @Test fun `playlist ids are replaced and ordinary words are not`() {
        assertEquals("list=<id>", CrashScrubber.scrub("list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
        assertEquals("dev=abcdefghijklmnop rev=abcdefghijk", CrashScrubber.scrub("dev=abcdefghijklmnop rev=abcdefghijk"))
        assertEquals("version v=1.2", CrashScrubber.scrub("version v=1.2"))
        assertEquals("the live/stream thing", CrashScrubber.scrub("the live/stream thing"))
    }

    @Test fun `googlevideo hosts and urls are replaced`() {
        val url = "https://rr3---sn-abcd-xyz.googlevideo.com/videoplayback?expire=1&id=o-ABC&itag=137&sig=SECRET"
        assertEquals("GET <url> failed", CrashScrubber.scrub("GET $url failed"))
        val bare = CrashScrubber.scrub("connect to rr3---sn-abcd-xyz.googlevideo.com:443 failed")
        assertFalse(bare, bare.contains("googlevideo"))
        assertFalse(bare, bare.contains("sn-abcd"))
        val unknown = CrashScrubber.scrub("java.net.UnknownHostException: Unable to resolve host \"rr1---sn-q.googlevideo.com\": No address associated with hostname")
        assertFalse(unknown, unknown.contains("googlevideo"))
    }

    @Test fun `extractor exception messages are cut but the class and the frames stay`() {
        val trace = """
            org.schabi.newpipe.extractor.exceptions.ParsingException: Could not get name of "Never Gonna Give You Up (Official Video)" by Rick Astley
            	at org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.getName(YoutubeStreamExtractor.java:180)
            	at com.olaph.xdm.youtube.NewPipeResolver.resolve(NewPipeResolver.kt:60)
            Caused by: org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException: Got error UNPLAYABLE: "Rick Astley - Never Gonna Give You Up"
            	at org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.checkPlayabilityStatus(YoutubeParsingHelper.java:1290)
            	... 12 more
        """.trimIndent()
        val out = CrashScrubber.scrub(trace)
        assertFalse(out, out.contains("Rick"))
        assertFalse(out, out.contains("Never Gonna"))
        assertTrue(out, out.contains("org.schabi.newpipe.extractor.exceptions.ParsingException: <redacted>"))
        assertTrue(out, out.contains("Caused by: org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException: <redacted>"))
        assertTrue("frames are kept", out.contains("at org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.getName(YoutubeStreamExtractor.java:180)"))
        assertTrue(out.contains("at com.olaph.xdm.youtube.NewPipeResolver.resolve(NewPipeResolver.kt:60)"))
        assertTrue(out.contains("... 12 more"))
    }

    @Test fun `multi-line extractor messages and the script and parser libraries are cut too`() {
        val trace = "org.mozilla.javascript.EvaluatorException: syntax error (<Unknown Source>#12)\n" +
            "var title = \"Secret Video Title\";\n" +
            "              ^\n" +
            "\tat org.mozilla.javascript.Parser.parse(Parser.java:1)\n" +
            "Caused by: com.grack.nanojson.JsonParserException: Unexpected token 'S' (line 1, char 8) near Secret Channel\n" +
            "\tat com.grack.nanojson.JsonTokener.next(JsonTokener.java:1)\n" +
            "Caused by: org.jsoup.UncheckedIOException: Secret Channel page\n" +
            "\tat org.jsoup.Jsoup.parse(Jsoup.java:1)"
        val out = CrashScrubber.scrub(trace)
        assertFalse(out, out.contains("Secret"))
        assertTrue(out, out.contains("org.mozilla.javascript.EvaluatorException: <redacted>"))
        assertTrue(out, out.contains("com.grack.nanojson.JsonParserException: <redacted>"))
        assertTrue(out, out.contains("org.jsoup.UncheckedIOException: <redacted>"))
        assertTrue(out.contains("at org.mozilla.javascript.Parser.parse(Parser.java:1)"))
    }

    @Test fun `an extractor exception without a message is left alone`() {
        val line = "org.schabi.newpipe.extractor.exceptions.ReCaptchaException"
        assertEquals(line, CrashScrubber.scrub(line))
    }

    @Test fun `okhttp thread names carrying a url are reduced`() {
        assertEquals("OkHttp", CrashScrubber.threadName("OkHttp https://secret.example/video.m3u8"))
        assertEquals("OkHttp Dispatcher", CrashScrubber.threadName("OkHttp Dispatcher"))
        assertEquals("worker <url>", CrashScrubber.threadName("worker https://a.test/x"))
        assertEquals("main", CrashScrubber.threadName("main"))
    }
}
