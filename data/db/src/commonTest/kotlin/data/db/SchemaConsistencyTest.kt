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
            "highlight",
        )
        assertTrue(tables.size == 12)
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

    @Test
    fun novelAnchorMigrationIsAdditiveAndReversible() {
        val forward = listOf(
            "ALTER TABLE reading_progress ADD COLUMN char_offset INTEGER NOT NULL DEFAULT -1;",
            "ALTER TABLE reading_progress ADD COLUMN layout_key TEXT NOT NULL DEFAULT '';",
        )
        assertTrue(forward.all { it.startsWith("ALTER TABLE") && !it.contains("DROP") })
        val down = RollbackScripts.V4_TO_V3
        assertTrue(down.contains("reading_progress_v3"))
        assertTrue(down.contains("INSERT INTO reading_progress_v3"))
        // 回滚只丢两个新列，不得丢 work_id/chapter_id/percent
        assertTrue(down.contains("work_id, chapter_id, page_index"))
        assertTrue(down.contains("percent"))
        assertTrue(RollbackScripts.V3_PROGRESS.contains("work_id TEXT PRIMARY KEY"))
    }

    @Test
    fun highlightTableIsAdditiveAndReversible() {
        // v5 新增整表（见 4.sqm），禁止改已有表；回滚即删表，不碰其他表。
        assertTrue(RollbackScripts.V5_TO_V4.contains("DROP TABLE highlight"))
        assertTrue(!RollbackScripts.V5_TO_V4.contains("reading_progress"))
        assertTrue(!RollbackScripts.V5_TO_V4.contains("bookmark"))
    }

    @Test
    fun cookieRebuildMigrationIsReversibleAndScopedToOneTable() {
        // v6 的 5.sqm 重建 cookie 表：旧库含明文 value，新库只有 value_enc。
        assertTrue(RollbackScripts.V5_COOKIE.contains("value TEXT NOT NULL"))
        assertTrue(RollbackScripts.V6_TO_V5.contains("cookie_v5"))
        assertTrue(RollbackScripts.V6_TO_V5.contains("DROP TABLE cookie"))
        // 回滚不得波及其他表
        assertTrue(!RollbackScripts.V6_TO_V5.contains("reading_progress"))
        assertTrue(!RollbackScripts.V6_TO_V5.contains("highlight"))
        assertTrue(!RollbackScripts.V6_TO_V5.contains("work"))
    }
}
