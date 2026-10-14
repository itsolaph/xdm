package com.olaph.xdm.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2: per-download request headers, bookmarks, history. Existing downloads are untouched. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `headers` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `url` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_url` ON `bookmarks` (`url`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `url` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_url` ON `history` (`url`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_visitedAt` ON `history` (`visitedAt`)")
    }
}

/** v2 → v3: HLS downloads (kind + segment counters). Existing rows become plain HTTP downloads. */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'HTTP'")
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `segmentsDone` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `segmentsTotal` INTEGER NOT NULL DEFAULT 0")
    }
}

/** v3 → v4: YouTube downloads keep their plan (video id, itags) in a nullable `extra` column. */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `extra` TEXT")
    }
}

/** v4 → v5: the private-folder flag and the player's resume position. Existing rows are public and start at 0. */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `isPrivate` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `downloads` ADD COLUMN `playbackPositionMs` INTEGER NOT NULL DEFAULT 0")
    }
}

val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
