package com.olaph.xdm.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * The segments of one running download and who is working on them.
 *
 * Connections take segments that have not started yet, front to back. When none are left, a free connection splits
 * the running segment with the most bytes still to fetch ([next]). That moves the end of a range while its reader is
 * still running, so the end is shared state: a reader asks [claim] before writing, and [split] never cuts below what was
 * already claimed. One lock covers claims, splits and [snapshot], so a saved snapshot never shows a moved end without
 * the segment that took over the rest.
 */
internal class SegmentTable(initial: List<SegmentState>) {

    inner class Live(val index: Int, val start: Long, end: Long, downloaded: Long) {
        /** Inclusive; -1 = until EOF. Only ever shrinks, and only under [lock]. */
        @Volatile var end: Long = end

        /** Bytes written to the file for this range. */
        val downloaded = AtomicLong(downloaded)

        /** Next byte offset the reader may write. Guarded by [lock]. */
        var claimed: Long = start + downloaded

        val limit: Long get() = if (end >= 0) end + 1 else Long.MAX_VALUE
        val isComplete: Boolean get() = end >= 0 && downloaded.get() >= end - start + 1

        fun state() = SegmentState(index, start, end, downloaded.get())
    }

    private val lock = Any()
    private val all = ArrayList<Live>()
    private val pending = ArrayDeque<Live>()
    private val active = LinkedHashSet<Live>()
    private var nextIndex: Int

    init {
        initial.forEach { all += Live(it.index, it.start, it.end, it.downloaded) }
        all.filter { !it.isComplete }.sortedBy { it.start }.forEach { pending.addLast(it) }
        nextIndex = (initial.maxOfOrNull { it.index } ?: -1) + 1
    }

    val size: Int get() = synchronized(lock) { all.size }

    fun snapshot(): List<SegmentState> = synchronized(lock) { all.map { it.state() } }

    fun downloaded(): Long = synchronized(lock) { all.sumOf { it.downloaded.get() } }

    /**
     * The next range for a free connection: an unstarted segment, or else the second half of the busiest running one
     * if at least [minSplitBytes] of it is left ([minSplitBytes] <= 0 disables splitting). Null = nothing to do.
     */
    fun next(minSplitBytes: Long): Live? = synchronized(lock) {
        pending.removeFirstOrNull()?.let { active += it; return it }
        if (minSplitBytes <= 0) return null
        val victim = active.filter { it.end >= 0 }.maxByOrNull { it.end - it.claimed + 1 } ?: return null
        split(victim, minSplitBytes)?.also { active += it }
    }

    /** Moves [victim]'s end to the middle of what it still has to fetch; the rest becomes a new segment. */
    private fun split(victim: Live, minSplitBytes: Long): Live? {
        val remaining = victim.end - victim.claimed + 1
        if (remaining < maxOf(minSplitBytes, 2)) return null
        val mid = victim.claimed + remaining / 2
        val tail = Live(nextIndex++, mid, victim.end, 0)
        victim.end = mid - 1
        all += tail
        return tail
    }

    fun finished(seg: Live) = synchronized(lock) { active -= seg }

    /** A (re)started transfer begins at the first byte not yet written; returns that offset. */
    fun begin(seg: Live): Long = synchronized(lock) {
        val offset = seg.start + seg.downloaded.get()
        seg.claimed = offset
        offset
    }

    /** How many of [n] bytes read at [pos] may be written: everything up to the segment's current end. */
    fun claim(seg: Live, pos: Long, n: Int): Int = synchronized(lock) {
        val allowed = if (seg.end < 0) n else minOf(n.toLong(), seg.end + 1 - pos).coerceAtLeast(0).toInt()
        seg.claimed = pos + allowed
        allowed
    }
}
