package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.media.MediaClassifier

/** What is about to be added. [totalBytes] <= 0 = size not known yet. */
data class DuplicateCandidate(
    val url: String,
    val fileName: String,
    val totalBytes: Long = -1,
    val isPrivate: Boolean = false,
    /** YouTube: the encoded plan. Several qualities of one video share a link, so only the same plan is the same link. */
    val extra: String? = null,
    /** YouTube plan that leaves the quality open (a playlist entry): any quality of the same video is the same link. */
    val anyQuality: Boolean = false,
)

enum class DuplicateReason { SAME_LINK, SAME_NAME, SAME_SIZE }

data class DuplicateMatch(val existing: DownloadEntity, val reason: DuplicateReason) {
    /** Paused or failed: "Resume existing" does something useful. */
    val resumable: Boolean get() = existing.status == DownloadStatus.PAUSED || existing.status == DownloadStatus.FAILED
}

/** The outcome for a batch: [matches] has one entry per item, null = not in the list yet. */
class BatchCheck<T>(val items: List<T>, val matches: List<DuplicateMatch?>) {
    val total: Int get() = items.size
    val duplicates: Int get() = matches.count { it != null }

    /** "2 of 5 already in your list"; null when nothing matched. */
    val summary: String? get() = if (duplicates == 0) null else "$duplicates of $total already in your list"

    fun withoutDuplicates(): List<T> = items.filterIndexed { i, _ -> matches[i] == null }
}

/**
 * Decides whether a download about to be added is already in the list. Pure: the file system is reached through
 * [fileExists] (is the saved file still there) and [folderFor] (the folder a new file of that name would land in, null =
 * not known, which counts as the same folder).
 *
 * Only public downloads are compared with each other: a private candidate is never checked and private items never match,
 * so the dialog can't reveal what the private folder holds.
 */
class DuplicateCheck(
    private val fileExists: (location: String) -> Boolean,
    private val folderFor: (fileName: String) -> String? = { null },
) {
    fun find(candidate: DuplicateCandidate, existing: List<DownloadEntity>): DuplicateMatch? {
        if (candidate.isPrivate) return null
        val key = MediaClassifier.normalize(candidate.url.trim())
        val wantedName = candidate.fileName.trim().lowercase()
        val wantedFolder by lazy { folderFor(candidate.fileName) }
        return existing.asSequence()
            .filter { !it.isPrivate && it.status != DownloadStatus.CANCELLED }
            .mapNotNull { e -> reason(candidate, e, key, wantedName) { wantedFolder }?.let { DuplicateMatch(e, it) } }
            // The closest kind of match first; then an unfinished one (it can be resumed), then the newest.
            .sortedWith(
                compareBy<DuplicateMatch> { it.reason.ordinal }
                    .thenBy { if (it.existing.status == DownloadStatus.COMPLETED) 1 else 0 }
                    .thenByDescending { it.existing.id },
            )
            .firstOrNull()
    }

    fun <T> findAll(items: List<T>, existing: List<DownloadEntity>, candidate: (T) -> DuplicateCandidate): BatchCheck<T> =
        BatchCheck(items, items.map { find(candidate(it), existing) })

    private fun reason(c: DuplicateCandidate, e: DownloadEntity, key: String, wantedName: String, folder: () -> String?): DuplicateReason? {
        // A finished download whose file is gone is history, not a copy.
        if (e.status == DownloadStatus.COMPLETED && !hasFile(e)) return null
        return when {
            sameLink(c, e, key) -> DuplicateReason.SAME_LINK
            e.fileName.trim().lowercase() == wantedName && sameFolder(e, folder) -> DuplicateReason.SAME_NAME
            sameSizeAndStem(c, e) -> DuplicateReason.SAME_SIZE
            else -> null
        }
    }

    private fun hasFile(e: DownloadEntity): Boolean = e.savedLocation?.let(fileExists) ?: false

    private fun sameLink(c: DuplicateCandidate, e: DownloadEntity, key: String): Boolean {
        if (MediaClassifier.normalize(e.url.trim()) != key) return false
        return c.extra == null || c.anyQuality || e.kind != DownloadKind.YOUTUBE || e.extra == c.extra
    }

    /** An unfinished download will be saved into the current folder; a finished one is where it was saved. */
    private fun sameFolder(e: DownloadEntity, folder: () -> String?): Boolean {
        if (e.status != DownloadStatus.COMPLETED) return true
        val planned = folder() ?: return true
        val actual = e.savedPath?.takeIf { '/' in it }?.substringBeforeLast('/') ?: return true
        return planned.equals(actual, ignoreCase = true)
    }

    private fun sameSizeAndStem(c: DuplicateCandidate, e: DownloadEntity): Boolean {
        if (c.totalBytes <= 0 || e.totalBytes != c.totalBytes) return false
        val (wantedStem, wantedExt) = stemAndExtension(c.fileName)
        val (stem, ext) = stemAndExtension(e.fileName)
        return stem.isNotEmpty() && stem == wantedStem && ext == wantedExt
    }

    /** Lower-case name without the extension and without the " (2)" a clashing name gets. */
    private fun stemAndExtension(name: String): Pair<String, String> {
        val (base, ext) = FilenameUtils.splitExtension(name.trim().lowercase())
        return base.replace(COPY_SUFFIX, "") to ext
    }

    companion object {
        private val COPY_SUFFIX = Regex("""\s\(\d+\)$""")

        /** One line about [d] for the dialog: "Paused · 3 MB of 12 MB (25%)". */
        fun describe(d: DownloadEntity): String {
            val size = d.totalBytes
            return when (d.status) {
                DownloadStatus.COMPLETED -> "Completed" + if (size > 0) " · ${Formatters.bytes(size)}" else ""
                DownloadStatus.RUNNING -> "Downloading" + progress(d)
                DownloadStatus.QUEUED -> "Waiting in the queue" + progress(d)
                DownloadStatus.PAUSED -> "Paused" + progress(d)
                DownloadStatus.FAILED -> "Failed" + progress(d)
                DownloadStatus.CANCELLED -> "Cancelled"
            }
        }

        private fun progress(d: DownloadEntity): String = when {
            d.isHls && d.segmentsTotal > 0 -> " · ${d.segmentsDone} of ${d.segmentsTotal} segments"
            d.totalBytes > 0 -> " · ${Formatters.bytes(d.downloadedBytes)} of ${Formatters.bytes(d.totalBytes)} (${(d.downloadedBytes * 100 / d.totalBytes).coerceIn(0, 100)}%)"
            d.downloadedBytes > 0 -> " · ${Formatters.bytes(d.downloadedBytes)}"
            else -> ""
        }
    }
}
