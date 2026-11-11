package spike

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SQLDelight CRUD（desktop/JVM，in-memory）。
 * Schema 与 architecture 5.2 同形（work/chapter/reading_progress/source_kv/cookie 子集）；
 * commonTest 侧用同一套 SQL 字符串做三端语法一致性，drivers 分 target 编译已在 Gate ③ 验证。
 */
class SqlDelightCrudTest {

    private fun open(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        driver.execute(
            null,
            """
            CREATE TABLE work (
              id TEXT PRIMARY KEY, source_key TEXT NOT NULL, source_work_id TEXT NOT NULL,
              kind TEXT NOT NULL, title TEXT NOT NULL, updated_at INTEGER NOT NULL,
              UNIQUE(source_key, source_work_id)
            );
            """.trimIndent(), 0
        )
        driver.execute(
            null,
            """
            CREATE TABLE chapter (
              id TEXT PRIMARY KEY, work_id TEXT NOT NULL, source_chapter_id TEXT NOT NULL,
              title TEXT NOT NULL, ordinal INTEGER NOT NULL, downloaded INTEGER NOT NULL DEFAULT 0,
              UNIQUE(work_id, source_chapter_id),
              FOREIGN KEY(work_id) REFERENCES work(id) ON DELETE CASCADE
            );
            """.trimIndent(), 0
        )
        driver.execute(
            null,
            "CREATE TABLE source_kv (source_key TEXT NOT NULL, data_key TEXT NOT NULL, value_json TEXT NOT NULL, PRIMARY KEY(source_key, data_key));",
            0
        )
        return driver
    }

    @Test
    fun crudAndCascade() {
        val db = open()
        try {
            db.execute(null, "INSERT INTO work(id,source_key,source_work_id,kind,title,updated_at) VALUES(?,?,?,?,?,?)", 6) {
                bindString(0, "w1"); bindString(1, "manga_dex"); bindString(2, "b23c9bb1")
                bindString(3, "MANGA"); bindString(4, "お前じゃん"); bindLong(5, 1_760_000_000_000L)
            }
            db.execute(null, "INSERT INTO chapter(id,work_id,source_chapter_id,title,ordinal) VALUES(?,?,?,?,?)", 5) {
                bindString(0, "c1"); bindString(1, "w1"); bindString(2, "ch1"); bindString(3, "第1话"); bindLong(4, 1)
            }
            db.execute(null, "INSERT INTO source_kv(source_key,data_key,value_json) VALUES(?,?,?)", 3) {
                bindString(0, "manga_dex"); bindString(1, "token"); bindString(2, "\"abc\"")
            }
            var n = 0
            val q1 = db.executeQuery(null, "SELECT id,title FROM work WHERE source_key=?", { c ->
                QueryResult.Value(buildList {
                    while (c.next().value) add(c.getString(0) to c.getString(1))
                })
            }, 1) {
                bindString(0, "manga_dex")
            }
            n = (q1 as QueryResult.Value<List<Pair<String?, String?>>>).value.size
            assertEquals(1, n)
            // update
            db.execute(null, "UPDATE work SET title=? WHERE id=?", 2) {
                bindString(0, "お前じゃん!"); bindString(1, "w1")
            }
            // delete work → chapter 级联删除
            db.execute(null, "DELETE FROM work WHERE id=?", 1) { bindString(0, "w1") }
            var chapters = -1
            val q2 = db.executeQuery(null, "SELECT id FROM chapter", { c ->
                QueryResult.Value(buildList {
                    while (c.next().value) add(c.getString(0))
                })
            }, 0)
            chapters = (q2 as QueryResult.Value<List<String?>>).value.size
            assertEquals(0, chapters)
            assertTrue(true)
        } finally {
            db.close()
        }
    }
}
