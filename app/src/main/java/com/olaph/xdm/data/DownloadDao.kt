package com.olaph.xdm.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY id DESC")
    abstract fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    abstract fun observe(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED') ORDER BY id ASC")
    abstract fun observeOpen(): Flow<List<DownloadEntity>>

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'RUNNING')")
    abstract fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM downloads WHERE id = :id")
    abstract suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY id ASC")
    abstract suspend fun getByStatus(status: DownloadStatus): List<DownloadEntity>

    @Insert
    abstract suspend fun insert(entity: DownloadEntity): Long

    @Query("DELETE FROM downloads WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE downloads SET status = :status, error = :error, forceStart = 0 WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: DownloadStatus, error: String? = null)

    @Query("UPDATE downloads SET status = 'QUEUED', error = NULL, forceStart = :force WHERE id = :id")
    abstract suspend fun requeue(id: Long, force: Boolean = false)

    @Query("UPDATE downloads SET status = 'QUEUED' WHERE status = 'RUNNING'")
    abstract suspend fun requeueRunning()

    @Query("UPDATE downloads SET status = 'PAUSED', forceStart = 0 WHERE status IN ('QUEUED', 'RUNNING')")
    abstract suspend fun pauseAllNow()

    @Query("UPDATE downloads SET status = 'QUEUED', error = NULL WHERE status = 'PAUSED'")
    abstract suspend fun resumeAllNow()

    @Query(
        "UPDATE downloads SET totalBytes = :total, acceptRanges = :ranges, etag = :etag, " +
            "lastModified = :lastModified, mimeType = COALESCE(mimeType, :mime) WHERE id = :id",
    )
    abstract suspend fun updatePlan(id: Long, total: Long, ranges: Boolean, etag: String?, lastModified: String?, mime: String?)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded WHERE id = :id")
    abstract suspend fun setDownloaded(id: Long, downloaded: Long)

    @Query(
        "UPDATE downloads SET status = 'COMPLETED', completedAt = :at, savedLocation = :location, " +
            "savedPath = :path, fileName = :name, totalBytes = :total, downloadedBytes = :total, error = :note WHERE id = :id",
    )
    /** [note] is a non-fatal remark shown with the finished item (e.g. "Saved as .ts (conversion failed: ...)"). */
    abstract suspend fun markCompleted(id: Long, at: Long, location: String, path: String, name: String, total: Long, note: String? = null)

    @Query("UPDATE downloads SET fileName = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    /** YouTube: the plan and the name/size that follow from the streams chosen when the download started. */
    @Query("UPDATE downloads SET extra = :extra, fileName = :name, totalBytes = :total, mimeType = :mime, acceptRanges = 1 WHERE id = :id")
    abstract suspend fun setYoutubePlan(id: Long, extra: String?, name: String, total: Long, mime: String?)

    /** A finished file changed place (moved into or out of the private folder, renamed there). */
    @Query("UPDATE downloads SET savedLocation = :location, savedPath = :path, fileName = :name, isPrivate = :isPrivate WHERE id = :id")
    abstract suspend fun setSavedFile(id: Long, location: String, path: String, name: String, isPrivate: Boolean)

    @Query("SELECT id FROM downloads WHERE isPrivate = 1")
    abstract suspend fun privateIds(): List<Long>

    /** Paused by Android's background time limit (see [PauseNote.SYSTEM_LIMIT]), not by the user. */
    @Query("SELECT * FROM downloads WHERE status = 'PAUSED' AND error = '${PauseNote.SYSTEM_LIMIT}' ORDER BY id ASC")
    abstract suspend fun getSystemPaused(): List<DownloadEntity>

    /** A new link for the same file; [sourcePage] only replaces the stored page when given. */
    @Query("UPDATE downloads SET url = :url, headers = :headers, sourcePage = COALESCE(:sourcePage, sourcePage) WHERE id = :id")
    abstract suspend fun setLink(id: Long, url: String, headers: String?, sourcePage: String?)

    /** HLS progress: finished segments, segments in the playlist, bytes on disk. */
    @Query("UPDATE downloads SET segmentsDone = :done, segmentsTotal = :total, downloadedBytes = :bytes WHERE id = :id")
    abstract suspend fun setHlsProgress(id: Long, done: Int, total: Int, bytes: Long)

    // ---- segments ----

    @Query("SELECT * FROM segments WHERE downloadId = :id ORDER BY idx ASC")
    abstract suspend fun getSegments(id: Long): List<SegmentEntity>

    @Query("SELECT * FROM segments WHERE downloadId = :id ORDER BY idx ASC")
    abstract fun observeSegments(id: Long): Flow<List<SegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSegments(segments: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE downloadId = :id")
    abstract suspend fun deleteSegments(id: Long)

    @Transaction
    open suspend fun replaceSegments(id: Long, segments: List<SegmentEntity>) {
        deleteSegments(id)
        upsertSegments(segments)
    }

    /** Replaces only the segments whose index is in [from] until [until]; a YouTube item keeps one such range per stream. */
    @Transaction
    open suspend fun replaceSegmentRange(id: Long, from: Int, until: Int, segments: List<SegmentEntity>) {
        val keep = getSegments(id).filter { it.idx < from || it.idx >= until }
        deleteSegments(id)
        upsertSegments(keep + segments)
    }

    @Transaction
    open suspend fun saveProgress(id: Long, segments: List<SegmentEntity>, downloaded: Long) {
        upsertSegments(segments)
        setDownloaded(id, downloaded)
    }

    @Transaction
    open suspend fun resetProgress(id: Long) {
        deleteSegments(id)
        setDownloaded(id, 0)
        setHlsProgress(id, 0, 0, 0)
    }
}

@Database(
    entities = [DownloadEntity::class, SegmentEntity::class, BookmarkEntity::class, HistoryEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class XdmDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun browserDao(): BrowserDao
}
