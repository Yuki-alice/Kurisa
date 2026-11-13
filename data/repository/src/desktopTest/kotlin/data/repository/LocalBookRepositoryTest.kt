package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.LocalBook
import data.db.ReaderDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 本地书仓库单测（desktop/JVM，真实 SQLDelight in-memory）。
 * 语义锁死：upsert 替换、按 id 查询删除、列表按导入时间倒序。
 */
class LocalBookRepositoryTest {

    private fun openRepo(): LocalBookRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return createLocalBookRepository(ReaderDatabase(driver))
    }

    private fun sample(id: String = "local:abc", at: Long = 2L) = LocalBook(
        id = id,
        path = "/books/$id",
        format = "txt",
        title = "本地书 $id",
        importedAtEpochMs = at,
    )

    @Test
    fun upsertFindRemove() = runTest {
        val repo = openRepo()
        assertNull(repo.find("local:abc"))
        repo.upsert(sample())
        assertEquals("本地书 local:abc", repo.find("local:abc")?.title)
        repo.upsert(sample().copy(title = "改名"))
        assertEquals("改名", repo.find("local:abc")?.title)
        assertEquals(1, repo.list().size)
        repo.remove("local:abc")
        assertNull(repo.find("local:abc"))
        assertTrue(repo.list().isEmpty())
    }

    @Test
    fun listOrdersByImportedDesc() = runTest {
        val repo = openRepo()
        repo.upsert(sample("local:old", at = 1L))
        repo.upsert(sample("local:new", at = 3L))
        assertEquals(listOf("local:new", "local:old"), repo.list().map { it.id })
    }
}
