package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.reader.ReadingConfig
import core.reader.ReadingDirection
import core.reader.ReadingMode
import data.db.ReaderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun defaultConfigIsValid() {
        val config = ReadingConfig()
        assertEquals(ReadingMode.PAGED, config.mode)
        assertEquals(ReadingDirection.LTR, config.direction)
    }
}
