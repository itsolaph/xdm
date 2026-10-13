package com.olaph.xdm.remux

/**
 * media3's TS reader only emits a video frame when it sees the start of the *next* one. At the end of a file the
 * last frame would be lost (the extractor's end-of-input flush only covers PES packets without a length field).
 * HLS never notices because the next segment follows; we do, so one synthetic packet is appended: a PES start on
 * the video PID that carries nothing but an access unit delimiter, which makes the reader finish the last frame.
 */
internal object TsTrailer {
    private const val PACKET = 188

    class Head(val syncOffset: Int, val videoPid: Int)

    /** Finds the packet alignment and the PID of the first video PES (stream id 0xE0..0xEF) in [head]. */
    fun inspect(head: ByteArray, length: Int): Head? {
        for (k in 0 until PACKET) {
            if (k + PACKET * 5 > length) return null
            if ((0 until 5).any { head[k + it * PACKET] != 0x47.toByte() }) continue
            var p = k
            while (p + PACKET <= length) {
                val b1 = head[p + 1].toInt() and 0xFF
                val pusi = b1 and 0x40 != 0
                val afc = (head[p + 3].toInt() shr 4) and 3
                if (pusi && afc and 1 != 0) {
                    val payload = p + 4 + if (afc and 2 != 0) 1 + (head[p + 4].toInt() and 0xFF) else 0
                    if (payload + 4 <= p + PACKET &&
                        head[payload].toInt() == 0 && head[payload + 1].toInt() == 0 && head[payload + 2].toInt() == 1 &&
                        (head[payload + 3].toInt() and 0xF0) == 0xE0
                    ) {
                        return Head(k, ((b1 and 0x1F) shl 8) or (head[p + 2].toInt() and 0xFF))
                    }
                }
                p += PACKET
            }
            return null
        }
        return null
    }

    /**
     * Bytes to append after [fileLength] bytes: padding up to the next packet boundary (a truncated last packet is
     * garbage anyway), then the delimiter packet.
     */
    fun build(head: Head, fileLength: Long, hevc: Boolean): ByteArray {
        val aud = if (hevc) byteArrayOf(0, 0, 1, 0x46, 0x01, 0x50) else byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        val pes = byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0, 0) + aud
        val packet = ByteArray(PACKET) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or (head.videoPid shr 8 and 0x1F)).toByte()
        packet[2] = head.videoPid.toByte()
        packet[3] = 0x30 // adaptation field + payload
        val adaptation = PACKET - 5 - pes.size
        packet[4] = adaptation.toByte()
        if (adaptation > 0) packet[5] = 0
        System.arraycopy(pes, 0, packet, PACKET - pes.size, pes.size)
        val misaligned = ((fileLength - head.syncOffset) % PACKET).toInt()
        val pad = if (misaligned == 0) 0 else PACKET - misaligned
        return ByteArray(pad) { 0xFF.toByte() } + packet
    }
}
