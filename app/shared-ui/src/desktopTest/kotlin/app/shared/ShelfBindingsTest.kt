package app.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.workStableId
import data.db.ReaderDatabase
import feature.search.SearchResultUi
import source.contract.ContentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 书架装配回归（in-memory DB）：ensure → 加入 → 响应式读出 → 移除。 */
class ShelfBindingsTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    @Test
    fun addFromSearchPersistsStableIdAndCanBeRemoved() = runTest {
        val bindings = createShelfBindings(openDb()) { 7L }
        bindings.ensureDefault()

        val work = SearchResultUi(workId = "w1", title = "B-W1")
        bindings.addFromSearch(work, sourceKey = "ex.b")

        val items = bindings.observe().first()
        assertEquals(1, items.size)
        assertEquals("B-W1", items.single().work.title)
        assertEquals(workStableId("ex.b", "w1"), items.single().workId)
        assertEquals(7L, items.single().addedAtEpochMs)

        // 幂等：同作品重复加入不产生第二行。
        bindings.addFromSearch(work, sourceKey = "ex.b")
        assertEquals(1, bindings.observe().first().size)

        bindings.remove(items.single().workId)
        assertEquals(0, bindings.observe().first().size)
    }

    @Test
    fun removeFromSourceRebuildsStableId() = runTest {
        val bindings = createShelfBindings(openDb()) { 7L }
        bindings.ensureDefault()
        val work = SearchResultUi(workId = "w1", title = "B-W1")
        bindings.addFromSearch(work, sourceKey = "ex.b")
        assertEquals(1, bindings.observeUi().first().size)

        // UI 行只有源内 id + sourceKey，删除必须真正生效。
        val row = bindings.observeUi().first().single()
        bindings.removeFromSource(row.sourceKey, row.workId)
        assertEquals(0, bindings.observe().first().size)
    }

    @Test
    fun novelKindIsPreserved() = runTest {
        val bindings = createShelfBindings(openDb()) { 9L }
        bindings.ensureDefault()
        bindings.addFromSearch(
            SearchResultUi(workId = "n1", title = "小说一"),
            sourceKey = "ex.novel",
            kind = ContentKind.NOVEL,
        )
        assertEquals("novel", bindings.observe().first().single().work.kind.serial)
    }
}
