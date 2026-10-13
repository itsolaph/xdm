package com.olaph.xdm.remux

import java.io.File

/** What a successful remux produced. Counts are samples of the *input* (`...In`) and of the output (`...Out`). */
class RemuxResult(
    /** `mp4` (video, optionally with audio) or `m4a` (audio only). */
    val extension: String,
    val mimeType: String,
    val durationUs: Long,
    val videoIn: Int,
    val videoOut: Int,
    val audioIn: Int,
    val audioOut: Int,
    /** Timeline resets (EXT-X-DISCONTINUITY style) that were stitched into one continuous timeline. */
    val discontinuities: Int,
)

/** The input cannot be converted; [message] is short and meant for the user ("unsupported audio codec AC-3"). */
class RemuxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Converts a downloaded MPEG-TS file into an MP4 without re-encoding. */
interface Remuxer {
    /**
     * Writes [output] (replaced if present). [onProgress] gets `(bytes read, input size)`. Cancelling the
     * coroutine stops promptly and deletes the partial [output]. Throws [RemuxException] when the input
     * cannot be converted; [output] does not exist afterwards in that case either.
     */
    suspend fun remux(input: File, output: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): RemuxResult
}
