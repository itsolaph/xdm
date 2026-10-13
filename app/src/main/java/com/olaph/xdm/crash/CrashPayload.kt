package com.olaph.xdm.crash

import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class CrashEnv(val version: String, val build: Int, val device: String, val android: String)

/** The only extras a report may carry. Nothing here can hold a URL, file name or user data. */
data class CrashExtra(val memFreeMb: Long, val memTotalMb: Long, val foreground: Boolean, val uptimeSeconds: Long)

object CrashScrubber {
    // Any scheme, not just http(s): file:// and content:// URIs carry file names too. A URL is `scheme://` plus everything
    // up to the next white space. Found with indexOf instead of the regex `[A-Za-z][A-Za-z0-9+.\-]*://\S+`, which takes
    // quadratic time on a long run of letters (a 250 KB exception message once kept the crash handler busy for minutes).
    private fun cutUrls(text: String): String {
        var sep = text.indexOf("://")
        if (sep < 0) return text
        val out = StringBuilder(text.length)
        var copied = 0
        while (sep >= 0) {
            var start = sep
            while (start > copied && (text[start - 1].isAsciiLetterOrDigit() || text[start - 1] in "+.-")) start--
            while (start < sep && !text[start].isAsciiLetter()) start++ // the scheme begins with a letter
            var end = sep + 3
            while (end < text.length && !text[end].isWhitespace()) end++
            if (start == sep || end == sep + 3) {
                sep = text.indexOf("://", sep + 3) // no scheme in front, or nothing behind: not a URL
                continue
            }
            out.append(text, copied, start).append("<url>")
            copied = end
            sep = text.indexOf("://", end)
        }
        return out.append(text, copied, text.length).toString()
    }

    private fun Char.isAsciiLetter() = this in 'A'..'Z' || this in 'a'..'z'
    private fun Char.isAsciiLetterOrDigit() = isAsciiLetter() || this in '0'..'9'

    // File names may contain spaces, so a path runs to the next ": " or the end of the line. That also drops
    // trailing text on the same line, which is the safe side to err on.
    private val path = Regex(
        """(?m)(?<![\w/.\-])/(?:storage|sdcard|mnt|data/(?:user|data|media))(?:/.*?)?(?=: |\r|$)""",
    )

    // Host names in network exception messages ("Unable to resolve host ...") are browsing history too.
    private val netMessage = Regex(
        """(?m)^(\s*(?:Caused by: |Suppressed: )?(?:java\.net|javax\.net\.ssl)\.[\w${'$'}.]+): .*$""",
    )

    // YouTube. Exception messages of the extractor (and of Rhino, which runs YouTube's player script, and of the JSON/HTML
    // parsers) quote what they parsed: titles, channel names, script text. They are cut after the class name, up to the next
    // stack frame / "Caused by", so multi-line messages go too. The class name and the frames stay for debugging.
    // Done line by line instead of with one big regex: messages can be hundreds of kilobytes, and this runs in the crash handler.
    private val extractorHead = Regex(
        """\s*(?:Caused by: |Suppressed: )?(?:org\.schabi\.newpipe\.extractor|org\.mozilla\.javascript|com\.grack\.nanojson|org\.jsoup)""" +
            """\.[\w${'$'}.]+: """,
    )
    private val frameStart = Regex("""\s*(?:at |Caused by: |Suppressed: |\.\.\. \d+ (?:more|common))""")

    private fun cutExtractorMessages(text: String): String {
        if (!text.contains("org.") && !text.contains("com.grack.")) return text
        val out = StringBuilder(text.length)
        var skipping = false
        var first = true
        for (line in text.split('\n')) {
            if (skipping) {
                if (frameStart.matchAt(line, 0) == null) continue // more lines of the same message
                skipping = false
            }
            if (!first) out.append('\n')
            first = false
            val head = extractorHead.matchAt(line, 0)
            if (head != null) {
                out.append(line, 0, head.range.last + 1).append("<redacted>")
                skipping = true
            } else {
                out.append(line)
            }
        }
        return out.toString()
    }

    // Video ids (11 characters) after "v=", "youtu.be/", "shorts/" (and the other player paths), playlist ids, and the
    // ids inside thumbnail paths (ytimg.com/vi/ID/...). Also covers links that lost their scheme and so escaped [url].
    private val youtubeId = Regex("""(?<![A-Za-z0-9])(v=|youtu\.be/|shorts/|embed/|live/|vi/|vi_webp/)[A-Za-z0-9_-]{11,}""")
    private val youtubeList = Regex("""(?<![A-Za-z0-9])(list=)[A-Za-z0-9_-]{2,}""")

    // googlevideo hosts name the CDN node of a stream ("rr3---sn-x.googlevideo.com"); with or without a scheme. The host
    // name in front of the domain goes too. (indexOf, not a regex with a leading `[A-Za-z0-9.-]*`, which is quadratic.)
    private const val STREAM_DOMAIN = "googlevideo.com"

    private fun cutStreamHosts(text: String): String {
        var i = text.indexOf(STREAM_DOMAIN, ignoreCase = true)
        if (i < 0) return text
        val out = StringBuilder(text.length)
        var copied = 0
        while (i >= 0) {
            var start = i
            while (start > copied && (text[start - 1].isLetterOrDigit() || text[start - 1] == '.' || text[start - 1] == '-')) start--
            out.append(text, copied, start).append("<host>")
            copied = i + STREAM_DOMAIN.length
            i = text.indexOf(STREAM_DOMAIN, copied, ignoreCase = true)
        }
        return out.append(text, copied, text.length).toString()
    }

    fun scrub(text: String): String =
        cutUrls(cutExtractorMessages(text))
            .let(::cutStreamHosts)
            .replace(youtubeId, "${'$'}1<id>")
            .replace(youtubeList, "${'$'}1<id>")
            .replace(path, "<path>")
            .replace(netMessage, "${'$'}1: <host>")

    /** OkHttp renames its call threads after the request URL, so the thread name can leak a link too. */
    fun threadName(name: String): String {
        if (name.startsWith("OkHttp ") && name != "OkHttp Dispatcher") return "OkHttp"
        return scrub(name).take(100)
    }
}

object CrashPayload {
    fun build(
        t: Throwable,
        thread: String,
        fatal: Boolean,
        at: OffsetDateTime,
        env: CrashEnv,
        extra: CrashExtra,
    ): String {
        var limit = MAX_TRACE_CHARS
        while (true) {
            val body = toJson(t, thread, fatal, at, env, extra, limit)
            // Non-ASCII text can push the UTF-8 size past the relay's cap even under the char limit.
            if (body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES || limit <= 10_000) return body
            limit /= 2
        }
    }

    private fun toJson(
        t: Throwable,
        thread: String,
        fatal: Boolean,
        at: OffsetDateTime,
        env: CrashEnv,
        extra: CrashExtra,
        traceLimit: Int,
    ): String {
        val trace = CrashScrubber.scrub(stackTrace(t)).take(traceLimit)
        return JSONObject()
            .put("app", "xdm")
            .put("version", env.version)
            .put("build", env.build)
            .put("device", env.device)
            .put("android", env.android)
            .put("thread", CrashScrubber.threadName(thread))
            .put("crashed_at", at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("fatal", fatal)
            .put("stacktrace", trace)
            .put(
                "extra",
                JSONObject()
                    .put("mem_free_mb", extra.memFreeMb)
                    .put("mem_total_mb", extra.memTotalMb)
                    .put("foreground", extra.foreground)
                    .put("uptime_s", extra.uptimeSeconds),
            )
            .toString()
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }
}
