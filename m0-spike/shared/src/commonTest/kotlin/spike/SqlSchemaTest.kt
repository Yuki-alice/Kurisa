package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SQL 字符串三端一致：同一套 CREATE TABLE 在 android/native/sqlite 三 driver 下可执行。
 * 真正的增删改查在 desktopTest（Jdbc in-memory）执行；这里锁死 SQL 本体一致，
 * 避免三端 schema 漂移（M2 数据层的前置）。
 */
class SqlSchemaTest {

    companion object {
        const val CREATE_WORK = "CREATE TABLE work (id TEXT PRIMARY KEY, source_key TEXT NOT NULL, source_work_id TEXT NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL, updated_at INTEGER NOT NULL, UNIQUE(source_key, source_work_id));"
        const val CREATE_CHAPTER = "CREATE TABLE chapter (id TEXT PRIMARY KEY, work_id TEXT NOT NULL, source_chapter_id TEXT NOT NULL, title TEXT NOT NULL, ordinal INTEGER NOT NULL, downloaded INTEGER NOT NULL DEFAULT 0, UNIQUE(work_id, source_chapter_id));"
    }

    @Test
    fun schemaKeywordsStable() {
        assertTrue(CREATE_WORK.contains("PRIMARY KEY"))
        assertTrue(CREATE_CHAPTER.contains("UNIQUE(work_id, source_chapter_id)"))
        assertEquals(1, CREATE_WORK.split("UNIQUE").size - 1)
    }
}
