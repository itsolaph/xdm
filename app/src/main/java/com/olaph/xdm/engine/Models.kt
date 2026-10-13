package com.olaph.xdm.engine

import java.io.File
import java.io.IOException

/**
 * One byte range of a download. [end] is inclusive; -1 means "until EOF" (unknown size).
 * [downloaded] counts bytes already written to the temp file for this range.
 */
data class SegmentState(
    val index: Int,
    val start: Long,
    val end: Long,
    val downloaded: Long = 0,
) {
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val isComplete: Boolean get() = end >= 0 && downloaded >= length
    val nextOffset: Long get() = start + downloaded
}

/** Result of probing a URL. [contentLength] is -1 when unknown. */
data class ProbeResult(
    val finalUrl: String,
    val contentLength: Long,
    val acceptRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val fileName: String,
    val mimeType: String?,
) {
    /** Downloads can be split and resumed only if ranges work and the size is known. */
    val resumable: Boolean get() = acceptRanges && contentLength > 0
}

/** What the engine knows from a previous run of the same download. */
data class SavedState(
    val totalBytes: Long,
    val acceptRanges: Boolean,
    val etag: String?,
    val lastModified: String?,
    val segments: List<SegmentState>,
)

class EngineRequest(
    val id: Long,
    val url: String,
    val userAgent: String,
    val file: File,
    val connections: Int,
    val saved: SavedState? = null,
    /** Extra headers (Cookie, Referer, User-Agent...) sent on the probe and on every segment request. */
    val headers: Map<String, String> = emptyMap(),
    /** If > 0, no segment is larger than this (the rest of the file becomes more, smaller segments). */
    val maxSegmentBytes: Long = 0,
    /** The size is already known and ranges are supported (YouTube tells both up front): skip the probe request. */
    val knownLength: Long = -1,
    /**
     * Gets a fresh URL for [url] when the server answers 403/410 (signed URLs expire). Bytes already on disk are kept
     * and the transfer continues from where it stopped. Null = such a response fails the download.
     */
    val refreshUrl: (suspend (staleUrl: String) -> String)? = null,
    /** Headers that depend on the URL in use (a refreshed URL may come from another YouTube client); replaces [headers] when set. */
    val headersFor: ((url: String) -> Map<String, String>)? = null,
)

data class EngineProgress(
    val downloaded: Long,
    val total: Long,
    val segments: List<SegmentState>,
)

data class EngineResult(val totalBytes: Long, val probe: ProbeResult)

/** Persistence hooks used by the engine; implemented with Room on Android. */
interface ProgressStore {
    /** Called once the probe and segment plan are known (before any data is transferred). */
    suspend fun savePlan(id: Long, probe: ProbeResult, segments: List<SegmentState>)

    /** Called (throttled) while downloading and once more when the engine stops. */
    suspend fun saveProgress(id: Long, segments: List<SegmentState>)
}

/** Non-retryable failure (4xx, invalid URL, server ignoring Range, disk full...). */
class FatalDownloadException(message: String, val httpCode: Int = 0, cause: Throwable? = null) :
    IOException(message, cause)

/** Retryable HTTP failure (5xx, 408, 429). */
class HttpStatusException(val httpCode: Int) : IOException("HTTP $httpCode")
