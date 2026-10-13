package com.olaph.xdm.media

/** A URL that passed the cheap checks and still has to be verified over the network. */
class MediaCandidate(
    val url: String,
    val key: String,
    val kind: MediaClassifier.Kind,
    /** Frame that asked for it (Referer of the request), when known. */
    val referer: String?,
    /** [MediaCollector.epoch] at the time; results of an earlier page are dropped. */
    val epoch: Int,
)

/**
 * Media found on one browser tab. Pure and thread-safe: it is fed from the WebView's network thread, from
 * the JavaScript bridge, and from the coroutines verifying candidates.
 *
 * A top-level navigation to another page [reset]s it. Segment URLs of a stream are only counted.
 */
class MediaCollector(private val maxCandidates: Int = MAX_CANDIDATES) {
    private val lock = Any()
    private val items = LinkedHashMap<String, DetectedMedia>()
    private val seen = HashSet<String>()
    private val children = HashSet<String>()
    private var seq = 0L
    private var playingKey: String? = null
    private var manifestSeen = false
    private var segments = 0

    var pageUrl: String? = null
        private set
    var epoch: Int = 0
        private set

    /** Number of stream chunks that were skipped for the current page. */
    val groupedSegments: Int get() = synchronized(lock) { segments }

    /** Starts a new page. Returns false (and keeps everything) when [url] is the page that is already shown. */
    fun reset(url: String?): Boolean = synchronized(lock) {
        val key = url?.let { pageKey(it) }
        if (key != null && key == pageUrl) return false
        pageUrl = key
        epoch++
        items.clear(); seen.clear(); children.clear()
        seq = 0; playingKey = null; manifestSeen = false; segments = 0
        true
    }

    /** Looks at one URL; returns a candidate when it is worth verifying and was not seen before. */
    fun offer(url: String, referer: String? = null): MediaCandidate? = synchronized(lock) {
        val kind = MediaClassifier.classify(url)
        when (kind) {
            MediaClassifier.Kind.IGNORE -> return null
            MediaClassifier.Kind.SEGMENT -> { segments++; return null }
            MediaClassifier.Kind.HLS, MediaClassifier.Kind.DASH -> manifestSeen = true
            MediaClassifier.Kind.AUDIO_FILE, MediaClassifier.Kind.VIDEO_FILE ->
                if (manifestSeen && MediaClassifier.isChunkLike(url)) { segments++; return null }
            MediaClassifier.Kind.UNKNOWN_MEDIA -> Unit
        }
        val key = MediaClassifier.normalize(url)
        if (key in children || key in seen) return null
        if (seen.size >= maxCandidates) return null
        seen += key
        MediaCandidate(url, key, kind, referer, epoch)
    }

    /** Stores a verified result. Returns true when the list changed (a new item appeared). */
    fun add(candidate: MediaCandidate, analysis: MediaAnalysis.Found): Boolean = synchronized(lock) {
        if (candidate.epoch != epoch) return false
        if (candidate.key in items) return false
        // A master playlist replaces the media playlists of its own variants that were sniffed earlier.
        for (child in analysis.childUrls) {
            val childKey = MediaClassifier.normalize(child)
            if (childKey == candidate.key) continue // a bare media playlist lists itself
            children += childKey
            items.remove(childKey)
        }
        if (analysis.options.isEmpty()) return false
        items[candidate.key] = DetectedMedia(
            key = candidate.key,
            url = candidate.url,
            kind = analysis.kind,
            typeLabel = analysis.typeLabel,
            options = analysis.options,
            seq = ++seq,
            referer = candidate.referer,
        )
        true
    }

    /** The check failed for a transient reason (timeout...): allow the URL to be offered again. */
    fun release(candidate: MediaCandidate): Unit = synchronized(lock) {
        if (candidate.epoch == epoch) seen -= candidate.key
    }

    /** The page's player started [url] (from a `play` event). Unknown URLs are remembered until they show up. */
    fun markPlaying(url: String?): Unit = synchronized(lock) {
        playingKey = url?.takeIf { it.isNotBlank() }?.let { MediaClassifier.normalize(it) }
    }

    /** Player on a `blob:` source (MSE): the best guess is the newest manifest found on the page. */
    fun markPlayingManifest(): Unit = synchronized(lock) {
        playingKey = items.values.filter { it.kind != MediaKind.FILE }.maxByOrNull { it.seq }?.key ?: playingKey
    }

    fun isEmpty(): Boolean = synchronized(lock) { items.isEmpty() }

    /** Playing item first, then downloadable ones, newest first. */
    fun snapshot(): List<DetectedMedia> = synchronized(lock) {
        items.values
            .map { it.copy(playing = it.key == playingKey) }
            .sortedWith(compareByDescending<DetectedMedia> { it.playing }.thenByDescending { it.supported }.thenByDescending { it.seq })
    }

    private fun pageKey(url: String) = url.substringBefore('#')

    companion object {
        const val MAX_CANDIDATES = 150
    }
}
