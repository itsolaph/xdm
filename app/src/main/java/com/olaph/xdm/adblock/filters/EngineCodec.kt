package com.olaph.xdm.adblock.filters

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.IdentityHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Compact binary form of a compiled [FilterEngine], so the app parses the text lists only when they change
 * (first start, update, list toggled) and otherwise loads the index in a fraction of the time.
 *
 * Layout (gzip): magic, version, the caller's fingerprint, a string table, the host sets (raw arrays), the network
 * rule indexes (rules + tokens; buckets are rebuilt), the cosmetic maps, and the stats. Strings are written once
 * and referenced by number. Any mismatch or damage makes [read] fail, and the caller rebuilds from the lists.
 */
object EngineCodec {
    private const val MAGIC = 0x58444D46 // "XDMF"
    const val VERSION = 1

    fun write(engine: FilterEngine, fingerprint: String, output: OutputStream) {
        val strings = LinkedHashMap<String, Int>()
        fun id(s: String): Int = strings.getOrPut(s) { strings.size }

        val constraints = IdentityHashMap<DomainConstraint, Int>()
        val constraintList = ArrayList<DomainConstraint>()
        fun cid(c: DomainConstraint?): Int = if (c == null) -1 else constraints.getOrPut(c) { constraintList += c; constraintList.size - 1 }

        val indexes = listOf(
            engine.blocks, engine.important, engine.exceptions, engine.importantExceptions,
            engine.pageDocument, engine.pageElemhide, engine.pageGenerichide, engine.pageSpecifichide,
        )
        // Rules are shared between indexes (an `@@…$document` rule is in two): one table, referenced by number.
        val rules = IdentityHashMap<NetworkRule, Int>()
        val ruleList = ArrayList<NetworkRule>()
        for (ix in indexes) for (r in ix.rules) if (rules.putIfAbsent(r, ruleList.size) == null) ruleList += r

        val cos = engine.cosmetic
        val cosMaps = listOf(cos.specificByHost, cos.specificByEntity, cos.exceptionsByHost, cos.exceptionsByEntity, cos.genericByKey)
        val cosRules = IdentityHashMap<CosmeticRule, Int>()
        val cosList = ArrayList<CosmeticRule>()
        fun cosId(r: CosmeticRule): Int = cosRules.getOrPut(r) { cosList += r; cosList.size - 1 }
        for (m in cosMaps) for (arr in m.values) for (r in arr) cosId(r)
        for (r in cos.highGeneric) cosId(r)

        // Assign string ids in a first pass so the table can be written up front.
        for (r in ruleList) { id(r.pattern); r.regexSource?.let(::id); cid(r.domains) }
        for (r in cosList) { id(r.selector); cid(r.domains) }
        for (c in constraintList) for (a in arrayOf(c.include, c.exclude, c.includeEntities, c.excludeEntities)) a.forEach(::id)
        for (m in cosMaps) m.keys.forEach(::id)

        val out = DataOutputStream(BufferedOutputStream(GZIPOutputStream(output, 1 shl 16), 1 shl 16))
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(fingerprint)

        out.writeInt(strings.size)
        for (s in strings.keys) out.writeUTF(s)

        for (set in listOf(engine.hosts, engine.hosts3p, engine.dnsHosts, engine.dnsAllow)) set.write(out)

        out.writeInt(constraintList.size)
        for (c in constraintList) for (a in arrayOf(c.include, c.exclude, c.includeEntities, c.excludeEntities)) {
            out.writeInt(a.size)
            for (s in a) out.writeInt(strings.getValue(s))
        }

        out.writeInt(ruleList.size)
        for (r in ruleList) {
            out.writeInt(strings.getValue(r.pattern))
            out.writeByte(r.anchor)
            out.writeBoolean(r.anchorEnd)
            out.writeInt(r.flags)
            out.writeInt(r.types)
            out.writeInt(cid(r.domains))
            out.writeInt(r.regexSource?.let { strings.getValue(it) } ?: -1)
        }
        for (ix in indexes) {
            out.writeInt(ix.rules.size)
            for (r in ix.rules) out.writeInt(rules.getValue(r))
            writeInts(out, ix.ruleTokens)
        }

        out.writeInt(cosList.size)
        for (r in cosList) {
            out.writeInt(strings.getValue(r.selector))
            out.writeInt(cid(r.domains))
        }
        for (m in cosMaps) {
            out.writeInt(m.size)
            for ((k, arr) in m) {
                out.writeInt(strings.getValue(k))
                out.writeInt(arr.size)
                for (r in arr) out.writeInt(cosRules.getValue(r))
            }
        }
        out.writeInt(cos.highGeneric.size)
        for (r in cos.highGeneric) out.writeInt(cosRules.getValue(r))

        val s = engine.stats
        for (v in listOf(s.hostRules, s.networkRules, s.cosmeticRules, s.skipped, s.noTokenRules, s.highGeneric)) out.writeInt(v)
        out.writeInt(MAGIC)
        out.flush()
        out.close()
    }

    /** Reads an engine written by [write] with the same [fingerprint]; throws [IOException] otherwise. */
    fun read(input: InputStream, fingerprint: String, psl: PublicSuffixList): FilterEngine {
        val inp = DataInputStream(BufferedInputStream(GZIPInputStream(input, 1 shl 16), 1 shl 16))
        try {
            if (inp.readInt() != MAGIC || inp.readInt() != VERSION) throw IOException("not an XDM filter cache")
            if (inp.readUTF() != fingerprint) throw IOException("filter cache is stale")

            val strings = Array(count(inp)) { inp.readUTF() }
            fun str(i: Int): String = strings.getOrNull(i) ?: throw IOException("bad string ref")

            val hosts = CompactDomainSet.read(inp)
            val hosts3p = CompactDomainSet.read(inp)
            val dnsHosts = CompactDomainSet.read(inp)
            val dnsAllow = CompactDomainSet.read(inp)

            val constraints = Array(count(inp)) {
                val parts = Array(4) { Array(count(inp)) { str(inp.readInt()) } }
                DomainConstraint(parts[0], parts[1], parts[2], parts[3])
            }
            fun con(i: Int): DomainConstraint? = if (i < 0) null else constraints.getOrNull(i) ?: throw IOException("bad constraint ref")

            val rules = Array(count(inp)) {
                val pattern = str(inp.readInt())
                val anchor = inp.readByte().toInt()
                val anchorEnd = inp.readBoolean()
                val flags = inp.readInt()
                val types = inp.readInt()
                val domains = con(inp.readInt())
                val regex = inp.readInt().let { if (it < 0) null else str(it) }
                NetworkRule(pattern, anchor, anchorEnd, flags, types, domains, regex)
            }
            val indexes = List(8) {
                val picked = Array(count(inp)) { rules.getOrNull(inp.readInt()) ?: throw IOException("bad rule ref") }
                val tokens = readInts(inp)
                if (tokens.size != picked.size) throw IOException("corrupt index")
                RuleIndex.build(picked, tokens)
            }

            val cosRules = Array(count(inp)) { CosmeticRule(str(inp.readInt()), con(inp.readInt())) }
            fun cosMap(): Map<String, Array<CosmeticRule>> {
                val n = count(inp)
                val m = HashMap<String, Array<CosmeticRule>>(n * 2)
                repeat(n) {
                    val key = str(inp.readInt())
                    m[key] = Array(count(inp)) { cosRules.getOrNull(inp.readInt()) ?: throw IOException("bad cosmetic ref") }
                }
                return m
            }
            val cosmetic = CosmeticIndex(
                specificByHost = cosMap(), specificByEntity = cosMap(), exceptionsByHost = cosMap(), exceptionsByEntity = cosMap(),
                genericByKey = cosMap(),
                highGeneric = Array(count(inp)) { cosRules.getOrNull(inp.readInt()) ?: throw IOException("bad cosmetic ref") },
            )
            val stats = EngineStats(inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt(), inp.readInt())
            if (inp.readInt() != MAGIC) throw IOException("truncated filter cache")
            return FilterEngine(
                psl, hosts, hosts3p, dnsHosts, dnsAllow,
                indexes[0], indexes[1], indexes[2], indexes[3], indexes[4], indexes[5], indexes[6], indexes[7],
                cosmetic, stats,
            )
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("corrupt filter cache: ${e.message}", e)
        } finally {
            runCatching { inp.close() }
        }
    }

    private fun count(inp: DataInputStream): Int {
        val n = inp.readInt()
        if (n < 0 || n > 50_000_000) throw IOException("corrupt count $n")
        return n
    }

    internal fun writeInts(out: DataOutputStream, values: IntArray) {
        out.writeInt(values.size)
        val buf = ByteBuffer.allocate(values.size * 4)
        buf.asIntBuffer().put(values)
        out.write(buf.array())
    }

    internal fun readInts(inp: DataInputStream): IntArray {
        val n = count(inp)
        val bytes = ByteArray(n * 4)
        inp.readFully(bytes)
        val out = IntArray(n)
        ByteBuffer.wrap(bytes).asIntBuffer().get(out)
        return out
    }
}
