package com.olaph.xdm.ui

import com.olaph.xdm.core.Formatters
import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadStatus

/** What a bulk delete is about to touch; shown in the confirmation dialog. Pure, so it is unit-tested. */
data class DeleteSummary(
    val count: Int,
    /** Running, queued or paused: cancelled first. */
    val activeCount: Int,
    val completedCount: Int,
    /** Size of the finished files that "also delete files" would remove. */
    val bytesOnDisk: Long,
)

object DeleteScope {
    /** The items "Delete all" acts on: exactly those the selected tab shows, and never anything private. */
    fun inFilter(items: List<DownloadEntity>, filter: Filter): List<DownloadEntity> =
        items.filter { !it.isPrivate && filter.matches(it.status) }

    fun summarize(items: List<DownloadEntity>): DeleteSummary {
        val completed = items.filter { it.status == DownloadStatus.COMPLETED }
        return DeleteSummary(
            count = items.size,
            activeCount = items.count { it.status in ACTIVE },
            completedCount = completed.size,
            bytesOnDisk = completed.sumOf { it.totalBytes.coerceAtLeast(0) },
        )
    }

    /** "Deleted 3 downloads (1.2 GB freed)", plus how many could not be deleted. */
    fun message(removed: Int, freedBytes: Long, failed: Int): String = buildString {
        append("Deleted ").append(removed).append(if (removed == 1) " download" else " downloads")
        if (freedBytes > 0) append(" (").append(Formatters.bytes(freedBytes)).append(" freed)")
        if (failed > 0) append(" · ").append(failed).append(if (failed == 1) " file could not be deleted" else " files could not be deleted")
    }

    private val ACTIVE = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.PAUSED)
}
