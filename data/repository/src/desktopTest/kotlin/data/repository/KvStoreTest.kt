package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.db.ReaderDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KvStoreTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    @Test
    fun kvIsolatedBySourceKey() = runTest {
        val store = createKvStore(openDb())
        assertNull(store.get("s1", "k"))
        store.set("s1", "k", "v1")
        store.set("s2", "k", "v2")
        assertEquals("v1", store.get("s1", "k"))
        assertEquals("v2", store.get("s2", "k"))
        store.delete("s1", "k")
        assertNull(store.get("s1", "k"))
        assertEquals("v2", store.get("s2", "k"))
    }
}
