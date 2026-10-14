package com.olaph.xdm.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Runs the real [MIGRATION_1_2] against a SQLite database built from the exported v1 schema (with
 * data in it) and checks the result equals a database created from the exported v2 schema.
 *
 * Plain JVM test: sqlite-jdbc stands in for Android's SQLite, and the Room [androidx.room.migration.Migration]
 * only needs `execSQL`. (Room's own MigrationTestHelper needs an emulator.)
 */
class MigrationTest {
    private lateinit var migrated: Connection
    private lateinit var fresh: Connection

    @Before fun setUp() {
        migrated = DriverManager.getConnection("jdbc:sqlite::memory:")
        fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() {
        migrated.close()
        fresh.close()
    }

    private fun schemaFile(version: Int): File {
        val rel = "schemas/com.olaph.xdm.data.XdmDatabase/$version.json"
        return listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("exported schema $rel not found (cwd=${File(".").absolutePath})")
    }

    /** CREATE statements (tables first, then indices) from a Room schema json. */
    private fun createStatements(version: Int): List<String> {
        val text = schemaFile(version).readText()
        val tables = Regex("\"tableName\": \"(\\w+)\"").findAll(text).toList()
        val out = mutableListOf<String>()
        tables.forEachIndexed { i, m ->
            val chunk = text.substring(m.range.first, tables.getOrNull(i + 1)?.range?.first ?: text.length)
            Regex("\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"").findAll(chunk)
                .forEach { out += it.groupValues[1].replace("\${TABLE_NAME}", m.groupValues[1]) }
        }
        return out.sortedBy { if (it.startsWith("CREATE TABLE")) 0 else 1 }
    }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun supportDb(c: Connection): SupportSQLiteDatabase =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            when (method.name) {
                "execSQL" -> { c.exec(args[0] as String); null }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SupportSQLiteDatabase

    private fun tableColumns(c: Connection, table: String): List<List<String?>> =
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildList {
                    while (rs.next()) add(listOf(rs.getString("name"), rs.getString("type"), rs.getString("notnull"), rs.getString("dflt_value"), rs.getString("pk")))
                }
            }
        }

    private fun userTables(c: Connection): List<String> = c.createStatement().use { st ->
        st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }

    private fun indices(c: Connection, table: String): List<String> = c.createStatement().use { st ->
        val names = st.executeQuery("PRAGMA index_list(`$table`)").use { rs ->
            buildList { while (rs.next()) if (rs.getString("origin") == "c") add(rs.getString("name") + "/unique=" + rs.getString("unique")) }
        }
        names.sorted().map { entry ->
            val name = entry.substringBefore('/')
            val cols = c.createStatement().use { s2 ->
                s2.executeQuery("PRAGMA index_info(`$name`)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
            }
            "$entry$cols"
        }
    }

    @Test fun exportedSchemasMatchTheDeclaredVersions() {
        assertTrue(schemaFile(1).readText().contains("\"version\": 1"))
        assertTrue(schemaFile(2).readText().contains("\"version\": 2"))
        assertTrue(schemaFile(3).readText().contains("\"version\": 3"))
        assertTrue(schemaFile(4).readText().contains("\"version\": 4"))
        assertTrue(schemaFile(5).readText().contains("\"version\": 5"))
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5), ALL_MIGRATIONS.map { it.startVersion to it.endVersion })
    }

    @Test fun migration4To5MakesEveryExistingDownloadPublicAndUnwatched() {
        createStatements(4).forEach { migrated.exec(it) }
        migrated.exec(
            "INSERT INTO downloads (id, url, fileName, mimeType, totalBytes, downloadedBytes, status, acceptRanges, etag, lastModified, " +
                "connections, createdAt, completedAt, error, savedLocation, savedPath, forceStart, headers, kind, segmentsDone, segmentsTotal, extra) VALUES " +
                "(9, 'https://x.test/v.mp4', 'v.mp4', 'video/mp4', 900, 900, 'COMPLETED', 1, NULL, NULL, 4, 77, 88, NULL, 'content://media/1', " +
                "'Download/XDM/v.mp4', 0, NULL, 'HTTP', 0, 0, NULL), " +
                "(10, 'https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'T.mp4', NULL, -1, 0, 'PAUSED', 1, NULL, NULL, 4, 78, NULL, NULL, NULL, NULL, 0, NULL, 'YOUTUBE', 0, 0, 'id=dQw4w9WgXcQ')",
        )
        migrated.exec("INSERT INTO segments (downloadId, idx, start, `end`, downloaded) VALUES (10, 0, 0, 99, 50)")

        MIGRATION_4_5.migrate(supportDb(migrated))

        migrated.createStatement().use { st ->
            st.executeQuery("SELECT id, status, savedLocation, kind, extra, isPrivate, playbackPositionMs FROM downloads ORDER BY id").use { rs ->
                assertTrue(rs.next())
                assertEquals(9L, rs.getLong("id"))
                assertEquals("COMPLETED", rs.getString("status"))
                assertEquals("content://media/1", rs.getString("savedLocation"))
                assertEquals("existing downloads are public", 0, rs.getInt("isPrivate"))
                assertEquals(0L, rs.getLong("playbackPositionMs"))
                assertTrue(rs.next())
                assertEquals("YOUTUBE", rs.getString("kind"))
                assertEquals("id=dQw4w9WgXcQ", rs.getString("extra"))
                assertEquals(0, rs.getInt("isPrivate"))
            }
            st.executeQuery("SELECT COUNT(*) FROM segments").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
        }
        // the new columns are writable; old-style inserts get the defaults
        migrated.exec("UPDATE downloads SET isPrivate = 1, playbackPositionMs = 61000 WHERE id = 9")
        migrated.exec("INSERT INTO downloads (url, fileName, connections, createdAt, status, forceStart, downloadedBytes, totalBytes) VALUES ('https://x.test/n', 'n', 4, 1, 'QUEUED', 0, 0, -1)")
    }

    @Test fun migratedV5SchemaEqualsFreshV5Schema() {
        createStatements(4).forEach { migrated.exec(it) }
        MIGRATION_4_5.migrate(supportDb(migrated))
        createStatements(5).forEach { fresh.exec(it) }
        assertSameSchema()
    }

    @Test fun migration3To4AddsTheExtraColumnAndKeepsEverythingElse() {
        createStatements(3).forEach { migrated.exec(it) }
        migrated.exec(
            "INSERT INTO downloads (id, url, fileName, mimeType, totalBytes, downloadedBytes, status, acceptRanges, etag, lastModified, " +
                "connections, createdAt, completedAt, error, savedLocation, savedPath, forceStart, headers, kind, segmentsDone, segmentsTotal) VALUES " +
                "(5, 'https://x.test/i.m3u8', 'i.ts', NULL, 900, 450, 'PAUSED', NULL, NULL, NULL, 4, 77, NULL, NULL, NULL, NULL, 0, 'Cookie=a%3Db', 'HLS', 3, 9)",
        )
        migrated.exec("INSERT INTO segments (downloadId, idx, start, `end`, downloaded) VALUES (5, 0, 0, 899, 450)")

        MIGRATION_3_4.migrate(supportDb(migrated))

        migrated.createStatement().use { st ->
            st.executeQuery("SELECT url, status, kind, segmentsDone, segmentsTotal, headers, extra FROM downloads WHERE id = 5").use { rs ->
                assertTrue(rs.next())
                assertEquals("HLS", rs.getString("kind"))
                assertEquals(3, rs.getInt("segmentsDone"))
                assertEquals("PAUSED", rs.getString("status"))
                assertEquals("Cookie=a%3Db", rs.getString("headers"))
                assertNull("old rows have no extra", rs.getString("extra"))
            }
            st.executeQuery("SELECT COUNT(*) FROM segments").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
        }
        // a YouTube row can be stored
        migrated.exec(
            "INSERT INTO downloads (url, fileName, connections, createdAt, status, forceStart, downloadedBytes, totalBytes, kind, extra) VALUES " +
                "('https://www.youtube.com/watch?v=dQw4w9WgXcQ', 'T (720p).mp4', 4, 1, 'QUEUED', 0, 0, -1, 'YOUTUBE', 'id=dQw4w9WgXcQ&mode=MUX')",
        )
    }

    @Test fun migratedV4SchemaEqualsFreshV4Schema() {
        createStatements(3).forEach { migrated.exec(it) }
        MIGRATION_3_4.migrate(supportDb(migrated))
        createStatements(4).forEach { fresh.exec(it) }
        assertSameSchema()
    }

    @Test fun migration2To3KeepsDownloadsAndMakesThemPlainHttp() {
        createStatements(2).forEach { migrated.exec(it) }
        migrated.exec(
            "INSERT INTO downloads (id, url, fileName, mimeType, totalBytes, downloadedBytes, status, acceptRanges, etag, lastModified, " +
                "connections, createdAt, completedAt, error, savedLocation, savedPath, forceStart, headers) VALUES " +
                "(3, 'https://old.test/b.zip', 'b.zip', 'application/zip', 2000, 2000, 'COMPLETED', 1, NULL, NULL, 4, 222, 333, NULL, 'content://x/1', 'Download/XDM/b.zip', 0, 'Cookie=a%3Db')",
        )
        migrated.exec("INSERT INTO segments (downloadId, idx, start, `end`, downloaded) VALUES (3, 0, 0, 1999, 2000)")
        migrated.exec("INSERT INTO bookmarks (title, url, createdAt) VALUES ('bm', 'https://bm.test', 5)")

        MIGRATION_2_3.migrate(supportDb(migrated))

        migrated.createStatement().use { st ->
            st.executeQuery("SELECT url, fileName, status, headers, kind, segmentsDone, segmentsTotal FROM downloads WHERE id = 3").use { rs ->
                assertTrue(rs.next())
                assertEquals("https://old.test/b.zip", rs.getString("url"))
                assertEquals("COMPLETED", rs.getString("status"))
                assertEquals("Cookie=a%3Db", rs.getString("headers"))
                assertEquals("existing rows are plain HTTP downloads", "HTTP", rs.getString("kind"))
                assertEquals(0, rs.getInt("segmentsDone"))
                assertEquals(0, rs.getInt("segmentsTotal"))
            }
            st.executeQuery("SELECT COUNT(*) FROM segments WHERE downloadId = 3").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
            st.executeQuery("SELECT COUNT(*) FROM bookmarks").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
        }
        // rows inserted without the new columns (old code paths) get the defaults; HLS rows can be stored
        migrated.exec("INSERT INTO downloads (url, fileName, connections, createdAt, status, forceStart, downloadedBytes, totalBytes) VALUES ('https://x.test/i.m3u8', 'i.ts', 4, 1, 'QUEUED', 0, 0, -1)")
        migrated.exec("UPDATE downloads SET kind = 'HLS', segmentsDone = 7, segmentsTotal = 20 WHERE url = 'https://x.test/i.m3u8'")
    }

    @Test fun migratedV3SchemaEqualsFreshV3Schema() {
        createStatements(2).forEach { migrated.exec(it) }
        MIGRATION_2_3.migrate(supportDb(migrated))
        createStatements(3).forEach { fresh.exec(it) }
        assertSameSchema()
    }

    @Test fun fullChainFromV1EqualsFreshV5Schema() {
        createStatements(1).forEach { migrated.exec(it) }
        migrated.exec(
            "INSERT INTO downloads (id, url, fileName, mimeType, totalBytes, downloadedBytes, status, acceptRanges, etag, lastModified, " +
                "connections, createdAt, completedAt, error, savedLocation, savedPath, forceStart) VALUES " +
                "(1, 'https://old.test/a.zip', 'a.zip', NULL, -1, 0, 'QUEUED', NULL, NULL, NULL, 8, 1, NULL, NULL, NULL, NULL, 0)",
        )
        ALL_MIGRATIONS.sortedBy { it.startVersion }.forEach { it.migrate(supportDb(migrated)) }
        createStatements(5).forEach { fresh.exec(it) }
        assertSameSchema()
        migrated.createStatement().use { st ->
            st.executeQuery("SELECT kind, headers, extra, isPrivate, playbackPositionMs FROM downloads WHERE id = 1").use { rs ->
                assertTrue(rs.next()); assertEquals("HTTP", rs.getString("kind")); assertNull(rs.getString("headers"))
                assertNull(rs.getString("extra")); assertEquals(0, rs.getInt("isPrivate")); assertEquals(0L, rs.getLong("playbackPositionMs"))
            }
        }
    }

    @Test fun everyStartingVersionReachesTheCurrentSchema() {
        createStatements(5).forEach { fresh.exec(it) }
        for (start in 1..4) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
                createStatements(start).forEach { db.exec(it) }
                ALL_MIGRATIONS.filter { it.startVersion >= start }.sortedBy { it.startVersion }.forEach { it.migrate(supportDb(db)) }
                assertEquals(userTables(fresh), userTables(db))
                for (t in userTables(fresh)) {
                    assertEquals("v$start: columns of $t", tableColumns(fresh, t), tableColumns(db, t))
                    assertEquals("v$start: indices of $t", indices(fresh, t), indices(db, t))
                }
            }
        }
    }

    private fun assertSameSchema() {
        val tables = userTables(fresh)
        assertEquals(listOf("bookmarks", "downloads", "history", "segments"), tables)
        assertEquals(tables, userTables(migrated))
        for (t in tables) {
            assertEquals("columns of $t", tableColumns(fresh, t), tableColumns(migrated, t))
            assertEquals("indices of $t", indices(fresh, t), indices(migrated, t))
        }
    }

    @Test fun migrationKeepsExistingDownloadsAndSegments() {
        createStatements(1).forEach { migrated.exec(it) }
        migrated.exec(
            "INSERT INTO downloads (id, url, fileName, mimeType, totalBytes, downloadedBytes, status, acceptRanges, etag, lastModified, " +
                "connections, createdAt, completedAt, error, savedLocation, savedPath, forceStart) VALUES " +
                "(7, 'https://old.test/a.zip', 'a.zip', 'application/zip', 1000, 400, 'PAUSED', 1, '\"e\"', NULL, 8, 111, NULL, NULL, NULL, NULL, 0)",
        )
        migrated.exec("INSERT INTO segments (downloadId, idx, start, `end`, downloaded) VALUES (7, 0, 0, 499, 400), (7, 1, 500, 999, 0)")

        MIGRATION_1_2.migrate(supportDb(migrated))

        migrated.createStatement().use { st ->
            st.executeQuery("SELECT id, url, fileName, totalBytes, downloadedBytes, status, headers FROM downloads").use { rs ->
                assertTrue(rs.next())
                assertEquals(7L, rs.getLong("id"))
                assertEquals("https://old.test/a.zip", rs.getString("url"))
                assertEquals("a.zip", rs.getString("fileName"))
                assertEquals(1000L, rs.getLong("totalBytes"))
                assertEquals(400L, rs.getLong("downloadedBytes"))
                assertEquals("PAUSED", rs.getString("status"))
                assertNull("old rows get no extra headers", rs.getString("headers"))
            }
            st.executeQuery("SELECT COUNT(*), SUM(downloaded) FROM segments WHERE downloadId = 7").use { rs ->
                rs.next(); assertEquals(2, rs.getInt(1)); assertEquals(400L, rs.getLong(2))
            }
        }
        // the new column is writable and the new tables work
        migrated.exec("UPDATE downloads SET headers = 'Cookie=a%3Db' WHERE id = 7")
        migrated.exec("INSERT INTO bookmarks (title, url, createdAt) VALUES ('t', 'https://x.test', 1)")
        migrated.exec("INSERT INTO history (title, url, visitedAt) VALUES ('t', 'https://x.test', 1)")
        try {
            migrated.exec("INSERT INTO bookmarks (title, url, createdAt) VALUES ('dup', 'https://x.test', 2)")
            fail("bookmark url must be unique")
        } catch (_: SQLException) {
        }
    }

    @Test fun migratedSchemaEqualsFreshV2Schema() {
        createStatements(1).forEach { migrated.exec(it) }
        MIGRATION_1_2.migrate(supportDb(migrated))
        createStatements(2).forEach { fresh.exec(it) }

        val tables = userTables(fresh)
        assertEquals(listOf("bookmarks", "downloads", "history", "segments"), tables)
        assertEquals(tables, userTables(migrated))
        for (t in tables) {
            // ALTER TABLE appends the new column last, which is also where the entity declares it.
            assertEquals("columns of $t", tableColumns(fresh, t), tableColumns(migrated, t))
            assertEquals("indices of $t", indices(fresh, t), indices(migrated, t))
        }
    }
}
