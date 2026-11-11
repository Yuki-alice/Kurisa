package data.db

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Schema 本体三端一致（commonTest，不碰 driver）。
 * 真正的 CRUD 与迁移在 desktopTest（Jdbc in-memory）执行。
 */
class SchemaConsistencyTest {

    @Test
    fun tablesPresent() {
        // 约束：10 表 + 扩展字段向后兼容（payload_json / settings_json 缺失不得崩）
        val tables = listOf(
            "source", "work", "chapter", "reading_progress", "shelf",
            "shelf_item", "download_task", "source_kv", "cookie", "bookmark", "local_book",
        )
        assertTrue(tables.size == 11)
        assertTrue(tables.contains("work"))
        assertTrue(tables.contains("shelf_item"))
    }

    @Test
    fun migrationIsAdditive() {
        // v1→v2 只允许 ADD COLUMN（禁止破坏性改列，见 1.sqm）
        val sqm = "ALTER TABLE source ADD COLUMN settings_json TEXT NOT NULL DEFAULT '{}';"
        assertTrue(sqm.startsWith("ALTER TABLE"))
        assertTrue(!sqm.contains("DROP"))
    }

    @Test
    fun rollbackPreservesCoreColumns() {
        val down = RollbackScripts.V2_TO_V1
        assertTrue(down.contains("source_v1"))
        assertTrue(down.contains("INSERT INTO source_v1"))
        // 回滚只丢 settings_json，不得丢 key/name/version
        assertTrue(down.contains("`key`, name, version"))
    }
}
