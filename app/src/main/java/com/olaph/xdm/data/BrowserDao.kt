package com.olaph.xdm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BrowserDao {
    // ---- bookmarks ----

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    abstract fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun addBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE url = :url")
    abstract suspend fun removeBookmark(url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    abstract suspend fun removeBookmark(id: Long)

    // ---- history ----

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    abstract fun observeHistory(limit: Int = 500): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertHistory(entry: HistoryEntity): Long

    @Query("DELETE FROM history WHERE id = :id")
    abstract suspend fun removeHistory(id: Long)

    @Query("DELETE FROM history")
    abstract suspend fun clearHistory()

    /** Keeps the table from growing without bound. */
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visitedAt DESC LIMIT :keep)")
    abstract suspend fun trimHistory(keep: Int = 2000)
}
