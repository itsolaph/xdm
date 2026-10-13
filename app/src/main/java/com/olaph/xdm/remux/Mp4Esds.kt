package com.olaph.xdm.remux

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Minimal MP4 box walker for one job: media3's muxer has no MPEG audio (MP3) support, so MP3 is written as an
 * AAC-shaped `mp4a` track and the `esds` object type is switched from 0x40 (AAC) to 0x6B (MPEG-1 audio) afterwards.
 * The patch overwrites a single byte in place, so no box size or chunk offset changes.
 */
internal object Mp4Esds {
    const val OBJECT_TYPE_AAC = 0x40
    const val OBJECT_TYPE_MPEG1_AUDIO = 0x6B

    /** Returns how many `mp4a` tracks were patched. */
    fun patchObjectType(file: File, from: Int, to: Int): Int {
        RandomAccessFile(file, "rw").use { raf ->
            val moov = findTopLevel(raf, "moov") ?: throw IOException("no moov box")
            var patched = 0
            forEachChild(raf, moov) { trak ->
                if (trak.type != "trak") return@forEachChild
                val stsd = descend(raf, trak, "mdia", "minf", "stbl", "stsd") ?: return@forEachChild
                // stsd: version/flags (4) + entry_count (4), then the sample entries.
                val entries = Box("stsd", stsd.payload + 8, stsd.end, stsd.payload + 8)
                forEachChild(raf, entries) { entry ->
                    if (entry.type != "mp4a") return@forEachChild
                    // AudioSampleEntry: 8 byte header already skipped by Box.payload, then 28 bytes of fixed fields.
                    val children = Box("mp4a", entry.payload + 28, entry.end, entry.payload + 28)
                    forEachChild(raf, children) { child ->
                        if (child.type == "esds" && patchEsds(raf, child, from, to)) patched++
                    }
                }
            }
            return patched
        }
    }

    private fun patchEsds(raf: RandomAccessFile, esds: Box, from: Int, to: Int): Boolean {
        var pos = esds.payload + 4 // version and flags
        if (readByte(raf, pos++) != 0x03) return false
        pos += varintLength(raf, pos) + 3 // ES_ID (2) + flags (1)
        if (readByte(raf, pos++) != 0x04) return false
        pos += varintLength(raf, pos)
        if (readByte(raf, pos) != from) return false
        raf.seek(pos)
        raf.write(to)
        return true
    }

    /** Length of an MPEG-4 descriptor size field (1-4 bytes, high bit = more follow). */
    private fun varintLength(raf: RandomAccessFile, at: Long): Int {
        var n = 1
        while (n < 4 && (readByte(raf, at + n - 1) and 0x80) != 0) n++
        return n
    }

    private fun readByte(raf: RandomAccessFile, at: Long): Int {
        raf.seek(at)
        return raf.read().also { if (it < 0) throw IOException("unexpected end of file") }
    }

    private class Box(val type: String, val start: Long, val end: Long, val payload: Long)

    private fun readBox(raf: RandomAccessFile, at: Long, limit: Long): Box? {
        if (at + 8 > limit) return null
        raf.seek(at)
        val h = ByteArray(8)
        raf.readFully(h)
        var size = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        val type = String(h, 4, 4, Charsets.ISO_8859_1)
        var header = 8L
        if (size == 1L) {
            if (at + 16 > limit) return null
            size = raf.readLong()
            header = 16
        } else if (size == 0L) {
            size = limit - at
        }
        if (size < header || at + size > limit) return null
        return Box(type, at, at + size, at + header)
    }

    private fun findTopLevel(raf: RandomAccessFile, type: String): Box? {
        var at = 0L
        val limit = raf.length()
        while (true) {
            val b = readBox(raf, at, limit) ?: return null
            if (b.type == type) return b
            at = b.end
        }
    }

    private inline fun forEachChild(raf: RandomAccessFile, parent: Box, action: (Box) -> Unit) {
        var at = parent.payload
        while (true) {
            val b = readBox(raf, at, parent.end) ?: return
            action(b)
            at = b.end
        }
    }

    private fun descend(raf: RandomAccessFile, from: Box, vararg path: String): Box? {
        var cur = from
        for (name in path) {
            var next: Box? = null
            var at = cur.payload
            while (next == null) {
                val b = readBox(raf, at, cur.end) ?: return null
                if (b.type == name) next = b
                at = b.end
            }
            cur = next
        }
        return cur
    }
}
