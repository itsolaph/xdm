package com.olaph.xdm.adblock

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Stored format of the hosts list (StevenBlack): gzip, first line `# xdm-blocklist updated=<epoch ms>`, then one
 * domain per line. Loaded and updated by [FilterListRepository] together with the filter lists.
 */
object BlocklistFormat {
    private val HEADER = Regex("^#\\s*xdm-blocklist\\s+updated=(\\d+)")

    class Loaded(val domains: Set<String>, val updatedAtMillis: Long)

    fun read(input: InputStream): Loaded {
        Gzip.maybeDecompress(input).bufferedReader(Charsets.UTF_8).use { reader ->
            val first = reader.readLine().orEmpty().removePrefix(HostsParser.BOM)
            val header = HEADER.find(first)
            val rest = sequence {
                if (header == null) yield(first) // no header: the first line is data
                while (true) yield(reader.readLine() ?: break)
            }
            return Loaded(HostsParser.parse(rest), header?.groupValues?.get(1)?.toLongOrNull() ?: 0L)
        }
    }

    fun write(domains: Set<String>, updatedAtMillis: Long, file: File) {
        GZIPOutputStream(file.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("# xdm-blocklist updated=$updatedAtMillis\n")
            domains.sorted().forEach { w.write(it); w.write("\n") }
        }
    }
}

/**
 * The APK packager stores `*.gz` assets decompressed, under the name without `.gz` (`blocklist.txt.gz` ends up as
 * `assets/blocklist.txt`). Readers therefore accept both forms, decided by the gzip magic number.
 */
object Gzip {
    fun maybeDecompress(input: InputStream): InputStream {
        val buffered = input.buffered()
        buffered.mark(2)
        val magic = buffered.read() or (buffered.read() shl 8)
        buffered.reset()
        return if (magic == 0x8b1f) GZIPInputStream(buffered) else buffered
    }
}
