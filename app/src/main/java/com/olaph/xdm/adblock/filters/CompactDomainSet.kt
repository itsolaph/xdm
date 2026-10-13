package com.olaph.xdm.adblock.filters

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Memory-lean immutable set of lower-case ASCII host names with parent-domain lookup, for the ~250k host-level
 * rules (hosts file, AdGuard DNS, `||host^` rules). All names live in one byte array behind an open-addressing
 * table, about 30 bytes per name instead of ~100 for a `HashSet<String>`. Lookups hash suffixes of the queried
 * host in place, so a parent walk allocates nothing until a match is returned.
 */
class CompactDomainSet private constructor(
    private val data: ByteArray,
    /** Slot → offset into [data] of a length-prefixed name, or -1 when empty. Size is a power of two. */
    private val table: IntArray,
    val size: Int,
) {
    private val mask = table.size - 1

    operator fun contains(host: String): Boolean = find(host, 0) >= 0

    /**
     * The entry covering [host]: the host itself or its closest listed parent (`a.b.ads.com` → `ads.com`).
     * Whole labels only (`notads.com` is not under `ads.com`); a bare TLD is never tested.
     */
    fun matchEntry(host: String?): String? {
        val start = matchStart(host ?: return null)
        return if (start < 0) null else host.substring(start)
    }

    /** Like [matchEntry] but returns the start index of the matching suffix in [host], or -1. No allocation. */
    fun matchStart(host: String): Int {
        if (size == 0 || host.isEmpty()) return -1
        if (isIp(host)) return if (find(host, 0) >= 0) 0 else -1
        var start = 0
        while (true) {
            val dot = host.indexOf('.', start)
            if (dot < 0) return -1 // the remaining suffix is a single label (TLD)
            if (find(host, start) >= 0) return start
            start = dot + 1
        }
    }

    private fun find(host: String, start: Int): Int {
        val len = host.length - start
        if (len <= 0 || len > MAX_NAME) return -1
        var slot = hash(host, start) and mask
        while (true) {
            val off = table[slot]
            if (off < 0) return -1
            if (equalsAt(off, host, start, len)) return off
            slot = (slot + 1) and mask
        }
    }

    private fun equalsAt(off: Int, host: String, start: Int, len: Int): Boolean {
        if ((data[off].toInt() and 0xFF) != len) return false
        for (i in 0 until len) if (data[off + 1 + i].toInt() != host[start + i].code) return false
        return true
    }

    /** All names (for tests and stats). */
    fun toList(): List<String> {
        val out = ArrayList<String>(size)
        for (off in table) if (off >= 0) out += String(data, off + 1, data[off].toInt() and 0xFF, Charsets.US_ASCII)
        return out
    }

    fun write(out: DataOutputStream) {
        out.writeInt(size)
        out.writeInt(data.size)
        out.write(data)
        EngineCodec.writeInts(out, table)
    }

    companion object {
        private const val MAX_NAME = 253

        /** Builds the set; names that are not plain lower-case ASCII or longer than 253 chars are dropped. */
        fun build(names: Collection<String>): CompactDomainSet {
            val unique = LinkedHashSet<String>(names.size * 2)
            for (n in names) if (n.isNotEmpty() && n.length <= MAX_NAME && n.all { it.code in 0x21..0x7E && it !in 'A'..'Z' }) unique += n
            var cap = 16
            while (cap < unique.size * 2) cap = cap shl 1
            val table = IntArray(cap) { -1 }
            val bytes = java.io.ByteArrayOutputStream(unique.sumOf { it.length + 1 })
            val mask = cap - 1
            for (n in unique) {
                val off = bytes.size()
                bytes.write(n.length)
                for (c in n) bytes.write(c.code)
                var slot = hash(n, 0) and mask
                while (table[slot] >= 0) slot = (slot + 1) and mask
                table[slot] = off
            }
            return CompactDomainSet(bytes.toByteArray(), table, unique.size)
        }

        fun read(input: DataInputStream): CompactDomainSet {
            val size = input.readInt()
            val dataSize = input.readInt()
            if (size < 0 || dataSize < 0 || dataSize > 100_000_000) throw java.io.IOException("corrupt domain set")
            val data = ByteArray(dataSize)
            input.readFully(data)
            val table = EngineCodec.readInts(input)
            val n = table.size
            if (n == 0 || n and (n - 1) != 0 || table.any { it >= dataSize }) throw java.io.IOException("corrupt domain table")
            return CompactDomainSet(data, table, size)
        }

        /** FNV-1a over the chars of [s] from [start] on, then mixed so linear probing spreads well. */
        private fun hash(s: String, start: Int): Int {
            var h = -0x7ee3623b // 2166136261
            for (i in start until s.length) {
                h = h xor s[i].code
                h *= 16777619
            }
            return h xor (h ushr 16)
        }

        private fun isIp(host: String): Boolean = host.contains(':') || (host.last() in '0'..'9' && com.olaph.xdm.adblock.HostNames.isIpLiteral(host))
    }
}
