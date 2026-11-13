package data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BE-B1：5.sqm（v5 → v6）重建 cookie 表。
 * 验收点：明文旧行一律不搬进新库；新列与 (domain,name,path) 主键齐全；回滚不碰其他表。
 */
class CookieMigrationTest {

    private fun openEmpty(): JdbcSqliteDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).apply {
            execute(null, "PRAGMA foreign_keys=ON", 0)
        }

    /** 与 migrations/5.sqm 同语义，逐条执行。 */
    private val forward = listOf(
        """
        CREATE TABLE cookie_v6 (
          domain TEXT NOT NULL,
          name TEXT NOT NULL,
          path TEXT NOT NULL DEFAULT '/',
          value_enc TEXT NOT NULL,
          expires_at INTEGER,
          paired_user_agent TEXT,
          source TEXT NOT NULL DEFAULT 'SEED',
          invalid_reason TEXT,
          obtained_at INTEGER NOT NULL DEFAULT 0,
          http_only INTEGER NOT NULL DEFAULT 0,
          secure INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY(domain, name, path)
        )
        """.trimIndent(),
        "DROP TABLE cookie",
        "ALTER TABLE cookie_v6 RENAME TO cookie",
    )

    private fun columns(driver: JdbcSqliteDriver, table: String): List<String> {
        val result = driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names += cursor.getString(1).orEmpty()
                QueryResult.Value(names.toList())
            },
            0,
        )
        return (result as QueryResult.Value<List<String>>).value
    }

    private fun longQuery(driver: JdbcSqliteDriver, sql: String): Long {
        val result = driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            0,
        )
        return (result as QueryResult.Value<Long>).value
    }

    @Test
    fun v5PlaintextRowsAreNotCarriedIntoEncryptedTable() {
        val driver = openEmpty()
        try {
            driver.execute(null, RollbackScripts.V5_COOKIE, 0)
            driver.execute(
                null,
                "INSERT INTO cookie(domain,name,value,expires_at) VALUES('example.net','cf_clearance','PLAINTEXT-SECRET',123)",
                0,
            )
            forward.forEach { driver.execute(null, it, 0) }

            assertEquals(0L, longQuery(driver, "SELECT count(*) FROM cookie"))
            val cols = columns(driver, "cookie")
            assertTrue(cols.containsAll(listOf("domain", "name", "path", "value_enc", "paired_user_agent")))
            assertFalse(cols.contains("value"), "明文 value 列不得残留")

            // 新表可写入密文记录，且主键允许同 domain/name 的不同 path 共存。
            driver.execute(
                null,
                "INSERT INTO cookie(domain,name,path,value_enc,paired_user_agent) VALUES('example.net','k','/','enc-a','UA-A')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO cookie(domain,name,path,value_enc,paired_user_agent) VALUES('example.net','k','/sub','enc-b','UA-B')",
                0,
            )
            assertEquals(2L, longQuery(driver, "SELECT count(*) FROM cookie WHERE domain='example.net'"))
        } finally {
            driver.close()
        }
    }

    @Test
    fun rollbackV6ToV5OnlyTouchesCookieTable() {
        val driver = openEmpty()
        try {
            ReaderDatabase.Schema.create(driver)
            driver.execute(
                null,
                "INSERT INTO cookie(domain,name,path,value_enc,paired_user_agent) VALUES('example.net','k','/','enc-a','UA-A')",
                0,
            )
            RollbackScripts.V6_TO_V5.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { driver.execute(null, it, 0) }

            val cols = columns(driver, "cookie")
            assertTrue(cols.contains("value"))
            assertFalse(cols.contains("value_enc"))
            assertEquals(0L, longQuery(driver, "SELECT count(*) FROM cookie"))
            // 其余表结构不受影响
            assertTrue(columns(driver, "reading_progress").contains("percent"))
            assertTrue(columns(driver, "work").contains("last_read_at"))
        } finally {
            driver.close()
        }
    }
}
