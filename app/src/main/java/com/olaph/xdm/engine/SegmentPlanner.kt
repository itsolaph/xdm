package com.olaph.xdm.engine

object SegmentPlanner {
    const val MIN_CONNECTIONS = 1
    const val MAX_CONNECTIONS = 16

    /** Files smaller than this are always fetched with a single connection. */
    const val SPLIT_THRESHOLD = 1L * 1024 * 1024

    /**
     * Splits [size] bytes into contiguous, non-overlapping, inclusive ranges covering [0, size-1].
     * Unknown size (<= 0) yields one open-ended segment.
     */
    fun plan(size: Long, connections: Int, maxSegmentBytes: Long = 0): List<SegmentState> {
        if (size <= 0) return listOf(SegmentState(0, 0, -1))
        var n = if (size < SPLIT_THRESHOLD) 1 else connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)
        // A cap on the segment size (YouTube throttles big single ranges) means more segments than connections;
        // the engine only runs `connections` of them at a time.
        if (maxSegmentBytes > 0 && size > maxSegmentBytes) n = maxOf(n.toLong(), (size + maxSegmentBytes - 1) / maxSegmentBytes).toInt()
        val base = size / n
        val remainder = size % n
        val result = ArrayList<SegmentState>(n)
        var start = 0L
        for (i in 0 until n) {
            val len = base + if (i < remainder) 1 else 0
            result += SegmentState(i, start, start + len - 1)
            start += len
        }
        return result
    }
}
