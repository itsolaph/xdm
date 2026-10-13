package com.olaph.xdm.remux

import java.nio.ByteBuffer

/** Independent MP4 reader for the tests (no media3): just enough of ISO 14496-12 to check the sample tables. */
class Mp4File(val bytes: ByteArray) {
    class Box(val type: String, val start: Int, val payload: Int, val end: Int)

    class Trak(
        val handler: String,
        val timescale: Long,
        val mdhdDuration: Long,
        val entryType: String,
        val entryChildren: List<String>,
        val esdsObjectType: Int,
        val sizes: IntArray,
        val sttsDeltas: LongArray,
        val cttsOffsets: LongArray?,
        val syncSamples: IntArray?,
        val sampleToChunk: List<Triple<Int, Int, Int>>,
        val chunkOffsets: LongArray,
        val hasEdts: Boolean,
        val width: Int,
        val height: Int,
    ) {
        val durationSeconds: Double get() = mdhdDuration.toDouble() / timescale
        val sampleCount: Int get() = sizes.size

        /** Offset in the file of every sample, derived from stsc / chunk offsets / stsz. */
        fun sampleOffsets(): LongArray {
            val offsets = LongArray(sizes.size)
            var sample = 0
            for (chunk in chunkOffsets.indices) {
                val perChunk = sampleToChunk.last { it.first <= chunk + 1 }.second
                var off = chunkOffsets[chunk]
                repeat(perChunk) {
                    if (sample < sizes.size) {
                        offsets[sample] = off
                        off += sizes[sample]
                        sample++
                    }
                }
            }
            check(sample == sizes.size) { "stsc/stco describe $sample samples but stsz has ${sizes.size}" }
            return offsets
        }

        /** Decode timestamps in track timescale units. */
        fun decodeTimes(): LongArray {
            val t = LongArray(sizes.size)
            var acc = 0L
            for (i in sizes.indices) { t[i] = acc; acc += sttsDeltas[i] }
            return t
        }

        fun isSync(sample1Based: Int) = syncSamples == null || syncSamples.contains(sample1Based)
    }

    val topLevel: List<Box> = children(0, bytes.size)
    val brands: List<String>
    val timescale: Long
    val movieDuration: Long
    val traks: List<Trak>

    init {
        val ftyp = topLevel.first { it.type == "ftyp" }
        brands = (ftyp.payload until ftyp.end step 4).filter { it + 4 <= ftyp.end }.map { String(bytes, it, 4, Charsets.ISO_8859_1) }
        val moov = topLevel.first { it.type == "moov" }
        val mvhd = children(moov).first { it.type == "mvhd" }
        val v = bytes[mvhd.payload].toInt()
        timescale = u32(mvhd.payload + if (v == 1) 20 else 12)
        movieDuration = if (v == 1) u64(mvhd.payload + 24) else u32(mvhd.payload + 16)
        traks = children(moov).filter { it.type == "trak" }.map { parseTrak(it) }
    }

    val moovBeforeMdat: Boolean get() = topLevel.indexOfFirst { it.type == "moov" } < topLevel.indexOfFirst { it.type == "mdat" }

    private fun parseTrak(trak: Box): Trak {
        val kids = children(trak)
        val tkhd = kids.first { it.type == "tkhd" }
        val tv = bytes[tkhd.payload].toInt()
        // width/height are 16.16 fixed point at the end of tkhd
        val width = (u32(tkhd.end - 8) shr 16).toInt()
        val height = (u32(tkhd.end - 4) shr 16).toInt()
        check(tv == 0 || tv == 1)
        val mdia = kids.first { it.type == "mdia" }
        val mdiaKids = children(mdia)
        val mdhd = mdiaKids.first { it.type == "mdhd" }
        val mv = bytes[mdhd.payload].toInt()
        val ts = u32(mdhd.payload + if (mv == 1) 20 else 12)
        val dur = if (mv == 1) u64(mdhd.payload + 24) else u32(mdhd.payload + 16)
        val hdlr = mdiaKids.first { it.type == "hdlr" }
        val handler = String(bytes, hdlr.payload + 8, 4, Charsets.ISO_8859_1)
        val stbl = children(children(mdiaKids.first { it.type == "minf" }).first { it.type == "stbl" })
        fun box(t: String) = stbl.firstOrNull { it.type == t }

        val stsd = box("stsd")!!
        val entry = children(stsd.payload + 8, stsd.end).first()
        // video sample entry: 78 bytes of fixed fields; audio sample entry: 28
        val fixed = if (handler == "vide") 78 else 28
        val entryKids = children(entry.payload + fixed, entry.end)
        var objectType = -1
        entryKids.firstOrNull { it.type == "esds" }?.let { objectType = esdsObjectType(it) }

        val stsz = box("stsz")!!
        val fixedSize = u32(stsz.payload + 4).toInt()
        val count = u32(stsz.payload + 8).toInt()
        val sizes = IntArray(count) { if (fixedSize != 0) fixedSize else u32(stsz.payload + 12 + it * 4).toInt() }

        val stts = box("stts")!!
        val entries = u32(stts.payload + 4).toInt()
        val deltas = ArrayList<Long>()
        for (i in 0 until entries) {
            val c = u32(stts.payload + 8 + i * 8).toInt()
            val d = u32(stts.payload + 12 + i * 8)
            repeat(c) { deltas += d }
        }
        val ctts = box("ctts")?.let { b ->
            val version = bytes[b.payload].toInt()
            val n = u32(b.payload + 4).toInt()
            val out = ArrayList<Long>()
            for (i in 0 until n) {
                val c = u32(b.payload + 8 + i * 8).toInt()
                val raw = u32(b.payload + 12 + i * 8)
                val off = if (version == 1) raw.toInt().toLong() else raw
                repeat(c) { out += off }
            }
            out.toLongArray()
        }
        val stss = box("stss")?.let { b ->
            val n = u32(b.payload + 4).toInt()
            IntArray(n) { u32(b.payload + 8 + it * 4).toInt() }
        }
        val stsc = box("stsc")!!
        val stscCount = u32(stsc.payload + 4).toInt()
        val s2c = (0 until stscCount).map {
            Triple(u32(stsc.payload + 8 + it * 12).toInt(), u32(stsc.payload + 12 + it * 12).toInt(), u32(stsc.payload + 16 + it * 12).toInt())
        }
        val chunkOffsets: LongArray = box("co64")?.let { b ->
            val n = u32(b.payload + 4).toInt()
            LongArray(n) { u64(b.payload + 8 + it * 8) }
        } ?: box("stco")!!.let { b ->
            val n = u32(b.payload + 4).toInt()
            LongArray(n) { u32(b.payload + 8 + it * 4) }
        }
        return Trak(
            handler, ts, dur, String(bytes, entry.start + 4, 4, Charsets.ISO_8859_1), entryKids.map { it.type }, objectType,
            sizes, deltas.toLongArray(), ctts, stss, s2c, chunkOffsets, kids.any { it.type == "edts" }, width, height,
        )
    }

    private fun esdsObjectType(esds: Box): Int {
        var p = esds.payload + 4
        check(u8(p++) == 0x03)
        p += varintLen(p) + 3
        check(u8(p++) == 0x04)
        p += varintLen(p)
        return u8(p)
    }

    private fun varintLen(at: Int): Int {
        var n = 1
        while (n < 4 && u8(at + n - 1) and 0x80 != 0) n++
        return n
    }

    fun children(box: Box) = children(box.payload, box.end)

    fun children(from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(p)
            var header = 8
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) { size = u64(p + 8); header = 16 } else if (size == 0L) size = (to - p).toLong()
            check(size >= header && p + size <= to) { "bad box $type at $p size $size (limit $to)" }
            out += Box(type, p, p + header, (p + size).toInt())
            p += size.toInt()
        }
        check(p == to) { "boxes do not fill the parent: stopped at $p of $to" }
        return out
    }

    /** NAL units of an AVC/HEVC sample with 4-byte length prefixes; fails if the prefixes do not tile the sample. */
    fun nalUnits(offset: Long, size: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var p = offset.toInt()
        val end = p + size
        while (p < end) {
            val len = u32(p).toInt()
            check(len > 0 && p + 4 + len <= end) { "NAL length $len at ${p - offset} does not fit sample of $size" }
            out += bytes.copyOfRange(p + 4, p + 4 + len)
            p += 4 + len
        }
        check(p == end)
        return out
    }

    private fun u8(at: Int) = bytes[at].toInt() and 0xFF
    private fun u32(at: Int) = ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xFFFFFFFFL
    private fun u64(at: Int) = ByteBuffer.wrap(bytes, at, 8).long
}
