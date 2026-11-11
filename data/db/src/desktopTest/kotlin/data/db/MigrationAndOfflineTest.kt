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
            db.progressQueries.upsertProgress("w1", "c1", 3L, null, 0.25, 1_760_000_000_000L, 0L)

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
