package com.olaph.xdm.engine

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** `#EXT-X-BYTERANGE` / `BYTERANGE=` — [length] bytes starting at [offset] of the resource. */
data class ByteRange(val length: Long, val offset: Long) {
    /** Value for an HTTP `Range` header. */
    fun header() = "bytes=$offset-${offset + length - 1}"
}

/** `#EXT-X-KEY`. [iv] is null when the tag has no IV (the media sequence number is used then). */
class HlsKey(val method: String, val uri: String?, val iv: ByteArray?, val keyFormat: String?) {
    val isAes128: Boolean get() = method == "AES-128"

    /** Widevine / FairPlay and friends use a non-"identity" KEYFORMAT. */
    val isIdentityFormat: Boolean get() = keyFormat == null || keyFormat.equals("identity", ignoreCase = true)
}

/** `#EXT-X-MAP`: fMP4 initialization section. [key] is the key that was active at the tag. */
class HlsMap(val uri: String, val byteRange: ByteRange?, val key: HlsKey?) {
    val identity: String get() = uri + "|" + (byteRange?.let { "${it.offset}+${it.length}" } ?: "")
}

class HlsSegment(
    val index: Int,
    /** Media sequence number of this segment (used as the default AES IV). */
    val sequence: Long,
    val uri: String,
    val duration: Double,
    val byteRange: ByteRange?,
    val key: HlsKey?,
    val map: HlsMap?,
    val discontinuity: Boolean,
)

sealed interface HlsPlaylist {
    val url: String
}

class MediaPlaylist(
    override val url: String,
    val targetDuration: Double,
    val mediaSequence: Long,
    val segments: List<HlsSegment>,
    /** True for VOD / finished playlists; false means a live or event stream still being written. */
    val endList: Boolean,
    val playlistType: String?,
) : HlsPlaylist {
    val duration: Double get() = segments.sumOf { it.duration }
    val hasMap: Boolean get() = segments.any { it.map != null }

    /** Extension of the assembled file: fMP4 → mp4, packed audio → aac/mp3, MPEG-TS → ts. */
    val outputExtension: String
        get() {
            if (hasMap) return "mp4"
            return when (segments.firstOrNull()?.uri?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase()) {
                "aac" -> "aac"
                "mp3" -> "mp3"
                else -> "ts"
            }
        }

    /** First usable key problem, or null when every segment can be fetched (clear or AES-128). */
    fun unsupportedEncryption(): String? {
        val keys = segments.mapNotNull { it.key } + segments.mapNotNull { it.map?.key }
        for (k in keys) {
            if (k.method == "NONE") continue
            if (!k.isIdentityFormat) return "DRM-protected stream"
            if (k.method == "SAMPLE-AES" || k.method.startsWith("SAMPLE-AES")) return "SAMPLE-AES encrypted stream is not supported"
            if (!k.isAes128) return "Unsupported encryption (${k.method})"
            if (k.uri.isNullOrBlank()) return "Encrypted stream without a key URI"
        }
        return null
    }
}

class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val averageBandwidth: Long,
    val width: Int,
    val height: Int,
    val codecs: String?,
    val frameRate: Double?,
    val audioGroup: String?,
) {
    /** Bandwidth to use for size estimates: the average when given, otherwise the peak. */
    val estimateBandwidth: Long get() = if (averageBandwidth > 0) averageBandwidth else bandwidth
}

class HlsRendition(
    val type: String,
    val groupId: String?,
    val name: String?,
    val language: String?,
    val uri: String?,
    val isDefault: Boolean,
)

class MasterPlaylist(
    override val url: String,
    val variants: List<HlsVariant>,
    val renditions: List<HlsRendition>,
) : HlsPlaylist {
    /** Separate audio playlists (renditions with a URI) per group id. */
    fun separateAudioGroups(): Set<String> =
        renditions.filter { it.type == "AUDIO" && !it.uri.isNullOrBlank() }.mapNotNull { it.groupId }.toSet()

    /** The variant carries its own audio when no separate audio playlist is referenced. */
    fun hasMuxedAudio(v: HlsVariant): Boolean {
        val group = v.audioGroup
        if (group != null && group in separateAudioGroups()) return false
        val codecs = v.codecs?.lowercase() ?: return true // unknown codecs: assume muxed
        return AUDIO_CODECS.any { codecs.contains(it) }
    }

    fun isAudioOnly(v: HlsVariant): Boolean {
        val codecs = v.codecs?.lowercase() ?: return false
        return v.height <= 0 && AUDIO_CODECS.any { codecs.contains(it) } && !VIDEO_CODECS.any { codecs.contains(it) }
    }

    /** Best variant with audio included (highest resolution, then bandwidth); falls back to the best of all. */
    fun bestVariant(): HlsVariant? {
        val order = compareByDescending<HlsVariant> { it.height }.thenByDescending { it.bandwidth }
        val playable = variants.filter { !isAudioOnly(it) }
        return playable.filter { hasMuxedAudio(it) }.sortedWith(order).firstOrNull()
            ?: playable.sortedWith(order).firstOrNull()
            ?: variants.sortedWith(order).firstOrNull()
    }

    private companion object {
        val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "opus", "flac", "mp3", "vorbis")
        val VIDEO_CODECS = listOf("avc", "hvc", "hev", "vp0", "vp9", "av01", "dvh", "mp4v")
    }
}

class M3u8Exception(message: String) : IllegalArgumentException(message)

/** Parser for HLS playlists (RFC 8216). Unknown tags and discontinuities are ignored. */
object M3u8 {
    /** Byte-order mark some servers put in front of `#EXTM3U`. */
    internal val BOM: Char = 0xFEFF.toChar()

    fun parse(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.trimStart(BOM).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) throw M3u8Exception("Not an HLS playlist (missing #EXTM3U)")
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        return if (isMaster) parseMaster(lines, baseUrl) else parseMedia(lines, baseUrl)
    }

    private fun parseMaster(lines: List<String>, baseUrl: String): MasterPlaylist {
        val variants = ArrayList<HlsVariant>()
        val renditions = ArrayList<HlsRendition>()
        var pending: Map<String, String>? = null
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> pending = parseAttributes(line.substringAfter(':'))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    renditions += HlsRendition(
                        type = a["TYPE"]?.uppercase().orEmpty(),
                        groupId = a["GROUP-ID"],
                        name = a["NAME"],
                        language = a["LANGUAGE"],
                        uri = a["URI"]?.takeIf { it.isNotBlank() }?.let { resolve(baseUrl, it) },
                        isDefault = a["DEFAULT"].equals("YES", ignoreCase = true),
                    )
                }
                line.startsWith("#") -> Unit
                else -> {
                    val attrs = pending ?: continue
                    pending = null
                    val res = attrs["RESOLUTION"]?.split('x', 'X')
                    variants += HlsVariant(
                        uri = resolve(baseUrl, line),
                        bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
                        averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0,
                        width = res?.getOrNull(0)?.toIntOrNull() ?: 0,
                        height = res?.getOrNull(1)?.toIntOrNull() ?: 0,
                        codecs = attrs["CODECS"],
                        frameRate = attrs["FRAME-RATE"]?.toDoubleOrNull(),
                        audioGroup = attrs["AUDIO"],
                    )
                }
            }
        }
        return MasterPlaylist(baseUrl, variants, renditions)
    }

    private fun parseMedia(lines: List<String>, baseUrl: String): MediaPlaylist {
        var targetDuration = 0.0
        var mediaSequence = 0L
        var endList = false
        var playlistType: String? = null

        var key: HlsKey? = null
        var map: HlsMap? = null
        var duration = 0.0
        var range: Pair<Long, Long?>? = null // length, optional offset
        var discontinuity = false
        val lastRangeEnd = HashMap<String, Long>()
        val segments = ArrayList<HlsSegment>()

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") -> targetDuration = line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> playlistType = line.substringAfter(':').trim().uppercase()
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> discontinuity = true
                line.startsWith("#EXTINF:") ->
                    duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> range = parseRange(line.substringAfter(':'))
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    val method = a["METHOD"]?.uppercase() ?: "NONE"
                    key = if (method == "NONE") null else HlsKey(
                        method = method,
                        uri = a["URI"]?.takeIf { it.isNotBlank() }?.let { resolve(baseUrl, it) },
                        iv = a["IV"]?.let { parseIv(it) },
                        keyFormat = a["KEYFORMAT"],
                    )
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    val uri = a["URI"]?.takeIf { it.isNotBlank() } ?: continue
                    val r = a["BYTERANGE"]?.let { parseRange(it) }
                    val resolved = resolve(baseUrl, uri)
                    map = HlsMap(resolved, r?.let { ByteRange(it.first, it.second ?: 0) }, key)
                }
                line.startsWith("#") -> Unit
                else -> {
                    val uri = resolve(baseUrl, line)
                    val br = range?.let { (len, off) ->
                        // Without an offset the range continues right after the previous one of the same resource.
                        ByteRange(len, off ?: lastRangeEnd[uri] ?: 0)
                    }
                    if (br != null) lastRangeEnd[uri] = br.offset + br.length
                    val index = segments.size
                    segments += HlsSegment(index, mediaSequence + index, uri, duration, br, key, map, discontinuity)
                    duration = 0.0
                    range = null
                    discontinuity = false
                }
            }
        }
        return MediaPlaylist(baseUrl, targetDuration, mediaSequence, segments, endList, playlistType)
    }

    /** `n` or `n@o`. */
    private fun parseRange(s: String): Pair<Long, Long?>? {
        val v = s.trim().trim('"')
        val len = v.substringBefore('@').toLongOrNull() ?: return null
        val off = if (v.contains('@')) v.substringAfter('@').toLongOrNull() else null
        return len to off
    }

    /** `0x...` hex IV, left-padded with zeros to 16 bytes. */
    internal fun parseIv(s: String): ByteArray? {
        val hex = s.trim().removePrefix("0x").removePrefix("0X")
        if (hex.isEmpty() || hex.length > 32 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val padded = hex.padStart(32, '0')
        return ByteArray(16) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Parses `KEY=value,KEY="quoted, value"` lists. */
    internal fun parseAttributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < s.length) {
            while (i < s.length && (s[i] == ',' || s[i] == ' ')) i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val name = s.substring(i, eq).trim().uppercase()
            i = eq + 1
            val value: String
            if (i < s.length && s[i] == '"') {
                val close = s.indexOf('"', i + 1)
                val end = if (close < 0) s.length else close
                value = s.substring(i + 1, end)
                i = end + 1
            } else {
                val comma = s.indexOf(',', i)
                val end = if (comma < 0) s.length else comma
                value = s.substring(i, end).trim()
                i = end
            }
            if (name.isNotEmpty()) out[name] = value
        }
        return out
    }

    fun resolve(base: String, ref: String): String =
        base.toHttpUrlOrNull()?.resolve(ref)?.toString() ?: ref
}
