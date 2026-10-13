package com.olaph.xdm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** Stored as text in [DownloadEntity.kind]. */
object DownloadKind {
    const val HTTP = "HTTP"
    const val HLS = "HLS"

    /** A YouTube video: [DownloadEntity.extra] holds the plan (video id + itags), the streams are resolved when it runs. */
    const val YOUTUBE = "YOUTUBE"
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    val totalBytes: Long = -1,
    val downloadedBytes: Long = 0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    /** null = not probed yet. */
    val acceptRanges: Boolean? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val connections: Int = 8,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val error: String? = null,
    /** content:// Uri (API 29+) or absolute path (API 26-28) of the finished file. */
    val savedLocation: String? = null,
    /** Human readable location, e.g. "Download/XDM/file.zip". */
    val savedPath: String? = null,
    /** "Start now": ignore the max-parallel limit once. */
    val forceStart: Boolean = false,
    /** Extra request headers (Cookie, Referer, User-Agent) encoded by [com.olaph.xdm.engine.HeaderCodec]. */
    val headers: String? = null,
    /** [DownloadKind.HTTP] (byte-range file) or [DownloadKind.HLS] (playlist of segments). */
    @ColumnInfo(defaultValue = "HTTP") val kind: String = DownloadKind.HTTP,
    /** HLS only: segments finished / listed in the playlist (0 until the playlist is read). */
    @ColumnInfo(defaultValue = "0") val segmentsDone: Int = 0,
    @ColumnInfo(defaultValue = "0") val segmentsTotal: Int = 0,
    /** Kind-specific state; for [DownloadKind.YOUTUBE] the encoded [com.olaph.xdm.youtube.YtPlan]. */
    val extra: String? = null,
) {
    val isHls: Boolean get() = kind == DownloadKind.HLS
    val isYoutube: Boolean get() = kind == DownloadKind.YOUTUBE

    /** True once we know the server cannot continue a partial transfer (so pause = start over). */
    val notResumable: Boolean get() = acceptRanges == false || (acceptRanges == true && totalBytes <= 0)
}

@Entity(
    tableName = "segments",
    primaryKeys = ["downloadId", "idx"],
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("downloadId")],
)
data class SegmentEntity(
    val downloadId: Long,
    val idx: Int,
    val start: Long,
    val end: Long,
    val downloaded: Long,
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One row per URL; revisiting a page only moves it to the top. */
@Entity(tableName = "history", indices = [Index(value = ["url"], unique = true), Index("visitedAt")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val visitedAt: Long = System.currentTimeMillis(),
)
