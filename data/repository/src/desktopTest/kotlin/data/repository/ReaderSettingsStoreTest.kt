package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.reader.ReadingConfig
import core.reader.ReadingDirection
import core.reader.ReadingMode
import data.db.ReaderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderSettingsStoreTest {

    @Test
    fun updatePersistsJsonToDatabase() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        val db = ReaderDatabase(driver)
        val store = createReaderSettingsStore(
            db = db,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMs = { 123L },
        )

        store.update {
            it.copy(
                mode = ReadingMode.CONTINUOUS,
                direction = ReadingDirection.RTL,
                doublePage = true,
                dimLevel = 20,
            )
        }

        val row = db.readerSettingQueries
            .selectReaderSetting("global")
            .executeAsOneOrNull()
        assertNotNull(row)
        assertEquals(123L, row.updated_at)
        assertTrue(row.config_json.contains("CONTINUOUS"))
        assertTrue(row.config_json.contains("RTL"))
        assertTrue(row.config_json.contains("\"doublePage\":true"))
        assertTrue(row.config_json.contains("\"dimLevel\":20"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bookScopeIsolatesOverridesWithGlobalFallback() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        val db = ReaderDatabase(driver)
        // 与既有单测同调度：Unconfined 直接执行，launch 无悬挂点即同步完成。
        val store = createReaderSettingsStore(
            db = db,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            nowMs = { 7L },
        )
        // 全局改字号 24（含排版落盘）。
        store.update { it.copy(typography = it.typography.copy(fontSizeSp = 24f)) }
        runCurrent()
        // 进入本书：缺行回退全局值。
        store.enterScope("book:w1")
        runCurrent()
        assertEquals("book:w1", store.currentScope())
        assertEquals(24f, store.settings.value.typography.fontSizeSp)
        // 本书改 20：全局行不受影响。
        store.update { it.copy(typography = it.typography.copy(fontSizeSp = 20f)) }
        runCurrent()
        assertEquals(20f, store.settings.value.typography.fontSizeSp)
        val globalRow = db.readerSettingQueries.selectReaderSetting("global").executeAsOneOrNull()
        assertNotNull(globalRow)
        assertTrue(globalRow.config_json.contains("24"))
        // 另一本书仍回退全局。
        store.enterScope("book:w2")
        runCurrent()
        assertEquals(24f, store.settings.value.typography.fontSizeSp)
        // 重置本书：删行并回退全局。
        store.resetScope("book:w1")
        runCurrent()
        store.enterScope("book:w1")
        runCurrent()
        assertEquals(24f, store.settings.value.typography.fontSizeSp)
        assertNull(db.readerSettingQueries.selectReaderSetting("book:w1").executeAsOneOrNull())
        // 全局作用域不受重置影响。
        store.resetScope("global")
        runCurrent()
        assertNotNull(db.readerSettingQueries.selectReaderSetting("global").executeAsOneOrNull())
    }

    @Test
    fun defaultConfigIsValid() {
        val config = ReadingConfig()
        assertEquals(ReadingMode.PAGED, config.mode)
        assertEquals(ReadingDirection.LTR, config.direction)
    }
}
