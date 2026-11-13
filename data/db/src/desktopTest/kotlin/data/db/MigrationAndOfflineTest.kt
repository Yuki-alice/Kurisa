package data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 Gate：迁移测试通过（含回滚）+ 断网书架可用（desktop/JVM，in-memory）。
 */
class MigrationAndOfflineTest {

    private fun openEmpty(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        return driver
    }

    @Test
    fun migrateV1ToV2PreservesData() {
        val driver = openEmpty()
        try {
            // v1 基线建表 + 写入一行（无 settings_json）
            driver.execute(null, RollbackScripts.V1_SOURCE, 0)
            driver.execute(
                null,
                "INSERT INTO source(`key`,name,version,api_version,kinds,capabilities,domains,install_path,sha256,signature_ok,enabled,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                12,
            ) {
                bindString(0, "com.example.novel"); bindString(1, "N"); bindString(2, "1.2.0")
                bindLong(3, 2); bindString(4, "[\"novel\"]"); bindString(5, "[\"search\"]")
                bindString(6, "[\"example.com\"]"); bindString(7, ""); bindString(8, "a".repeat(64))
                bindLong(9, 1); bindLong(10, 1); bindLong(11, 1_760_000_000_000L)
            }
            // 执行 1.sqm（与文件同语义）
            driver.execute(
                null,
                "ALTER TABLE source ADD COLUMN settings_json TEXT NOT NULL DEFAULT '{}'",
                0,
            )
            // 新列默认值生效，旧数据保留
            var settings = ""
            var name = ""
            val q = driver.executeQuery(
                null,
                "SELECT name, settings_json FROM source WHERE `key`=?",
                { c ->
                    QueryResult.Value(
                        if (c.next().value) c.getString(0) to c.getString(1)
                        else null to null,
                    )
                },
                1,
            ) { bindString(0, "com.example.novel") }
            val v = (q as QueryResult.Value<Pair<String?, String?>>).value
            name = v.first.orEmpty(); settings = v.second.orEmpty()
            assertEquals("N", name)
            assertEquals("{}", settings)
        } finally {
            driver.close()
        }
    }

    @Test
    fun rollbackV2ToV1PreservesCore() {
        val driver = openEmpty()
        try {
            val db = ReaderDatabase(driver)
            ReaderDatabase.Schema.create(driver)
            db.sourceQueries.upsertSource(
                "com.example.n", "N", "1.0.0", 2L, "[\"manga\"]", "[]", "[\"example.com\"]",
                "", "b".repeat(64), 1L, 1L, "{\"theme\":\"dark\"}", 1_760_000_000_000L,
            )
            // 逐条执行回滚脚本
            RollbackScripts.V2_TO_V1.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { sql -> driver.execute(null, sql, 0) }
            var name = ""
            val q = driver.executeQuery(
                null,
                "SELECT name FROM source WHERE `key`=?",
                { c ->
                    QueryResult.Value(
                        if (c.next().value) c.getString(0) else null,
                    )
                },
                1,
            ) { bindString(0, "com.example.n") }
            name = (q as QueryResult.Value<String?>).value.orEmpty()
            assertEquals("N", name)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrateV3ToV4PreservesData() {
        val driver = openEmpty()
        try {
            driver.execute(null, RollbackScripts.V3_PROGRESS, 0)
            driver.execute(
                null,
                "INSERT INTO reading_progress(work_id,chapter_id,page_index,percent,updated_at,synced) VALUES('w1','c1',2,0.4,1,0)",
                0,
            )
            listOf(
                "ALTER TABLE reading_progress ADD COLUMN char_offset INTEGER NOT NULL DEFAULT -1",
                "ALTER TABLE reading_progress ADD COLUMN layout_key TEXT NOT NULL DEFAULT ''",
            ).forEach { driver.execute(null, it, 0) }
            val q = driver.executeQuery(
                null,
                "SELECT percent, char_offset FROM reading_progress",
                { c ->
                    QueryResult.Value(
                        if (c.next().value) c.getDouble(0) to c.getLong(1) else null,
                    )
                },
                0,
            )
            val value = (q as QueryResult.Value<Pair<Double?, Long?>?>).value
            assertTrue(value != null && value.first == 0.4 && value.second == -1L)
        } finally {
            driver.close()
        }
    }

    @Test
    fun rollbackV4ToV3PreservesCore() {
        val driver = openEmpty()
        try {
            val db = ReaderDatabase(driver)
            ReaderDatabase.Schema.create(driver)
            db.progressQueries.upsertProgress("w1", "c1", 3L, null, 0.25, 1L, 0L, 42L, "key1")
            RollbackScripts.V4_TO_V3.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { sql -> driver.execute(null, sql, 0) }
            val q = driver.executeQuery(
                null,
                "SELECT percent FROM reading_progress WHERE work_id='w1'",
                { c -> QueryResult.Value(if (c.next().value) c.getDouble(0) else null) },
                0,
            )
            assertEquals(0.25, (q as QueryResult.Value<Double?>).value)
        } finally {
            driver.close()
        }
    }

    @Test
    fun migrateV4ToV5AddsHighlightTable() {
        val driver = openEmpty()
        try {
            val db = ReaderDatabase(driver)
            ReaderDatabase.Schema.create(driver)
            // 模拟 v4 库：删掉 highlight 表后执行 4.sqm（与文件同语义）。
            driver.execute(null, "DROP TABLE highlight", 0)
            driver.execute(
                null,
                "CREATE TABLE highlight (" +
                    "id TEXT PRIMARY KEY, work_id TEXT NOT NULL, chapter_id TEXT NOT NULL, " +
                    "start INTEGER NOT NULL, end INTEGER NOT NULL, " +
                    "excerpt TEXT, note TEXT, created_at INTEGER NOT NULL)",
                0,
            )
            db.highlightQueries.upsertHighlight("h1", "w", "c", 3L, 9L, "摘", null, 1L)
            assertEquals("摘", db.highlightQueries.selectHighlights("w", "c").executeAsOne().excerpt)
        } finally {
            driver.close()
        }
    }

    @Test
    fun rollbackV5ToV4DropsOnlyHighlight() {
        val driver = openEmpty()
        try {
            val db = ReaderDatabase(driver)
            ReaderDatabase.Schema.create(driver)
            db.highlightQueries.upsertHighlight("h1", "w", "c", 0L, 5L, null, null, 1L)
            db.bookmarkQueries.upsertBookmark("b1", "w", "c", 3L, null, null, 1L)
            RollbackScripts.V5_TO_V4.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { sql -> driver.execute(null, sql, 0) }
            val tables = driver.executeQuery(
                null,
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('highlight','bookmark')",
                { c ->
                    val names = mutableListOf<String>()
                    while (c.next().value) {
                        c.getString(0)?.let { names += it }
                    }
                    QueryResult.Value(names)
                },
                0,
            )
            assertEquals(listOf("bookmark"), (tables as QueryResult.Value<List<String>>).value)
            assertEquals("b1", db.bookmarkQueries.selectBookmarks("w").executeAsOne().id)
        } finally {
            driver.close()
        }
    }

    @Test
    fun offlineShelfAvailable() {
        val driver = openEmpty()
        try {
            val db = ReaderDatabase(driver)
            ReaderDatabase.Schema.create(driver)
            // 书架 + 作品 + 条目 + 章节 + 进度（全本地，无网络）
            db.shelfQueries.upsertShelf("default", "默认", 0L, null)
            db.workQueries.upsertWork(
                "w1", "manga_dex", "b23c9bb1", "MANGA", "お前じゃん", null, null, null,
                "[]", null, null, null, 1_760_000_000_000L, null,
            )
            db.shelfItemQueries.addToShelf("default", "w1", 1_760_000_000_000L)
            db.chapterQueries.upsertChapter(
                "c1", "w1", "ch1", "第1话", 1L, null, 0L, 1_760_000_000_000L,
            )
            db.progressQueries.upsertProgress("w1", "c1", 3L, null, 0.25, 1_760_000_000_000L, 0L, -1L, "")

            val works = db.workQueries.selectShelfWorks("default").executeAsList()
            assertEquals(1, works.size)
            assertEquals("お前じゃん", works[0].title)

            val chapters = db.chapterQueries.selectChapters("w1").executeAsList()
            assertEquals(1, chapters.size)

            val p = db.progressQueries.selectProgress("w1").executeAsOneOrNull()
            assertTrue(p != null && p.percent == 0.25)

            // 删除作品 → 章节级联（外键 ON DELETE CASCADE）
            db.workQueries.deleteWork("w1")
            assertTrue(db.chapterQueries.selectChapters("w1").executeAsList().isEmpty())
        } finally {
            driver.close()
        }
    }
}
