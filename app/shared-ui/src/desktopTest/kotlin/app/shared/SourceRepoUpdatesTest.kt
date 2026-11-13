package app.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.db.ReaderDatabase
import data.network.CookieStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 源仓库订阅出口的集成测试（desktopTest，in-memory DB + MockEngine）。
 *
 * 覆盖：`SourceCatalog.planRepoUpdates` 的解析/版本计划/minApp 过滤/fail-closed，
 * 以及 `refreshRepoUpdates` 把拉取失败收敛为 error 而非抛出。
 * 全部索引为内存字符串，URL 只用 `example.*`（C1：主仓零真实站点）。
 */
class SourceRepoUpdatesTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun insertInstalled(db: ReaderDatabase, key: String, version: String) {
        db.sourceQueries.upsertSource(
            key, "N-$key", version, 2L,
            "[\"manga\"]", "[\"search\"]", "[\"cdn.example.com\"]",
            "sources/$key/$version.js", "0".repeat(64),
            0L, 1L, "{}", 1L,
        )
    }

    private fun catalogOf(db: ReaderDatabase): SourceCatalog = createSourceCatalog(
        db = db,
        httpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
        cookies = CookieStore(),
        loadScript = { error("订阅计划不应加载脚本") },
        nowMs = { 7L },
    )

    @Test
    fun emptyIndexProducesNoCandidates() {
        val catalog = catalogOf(openDb())

        assertEquals(
            emptyList(),
            catalog.planRepoUpdates("""{"version":1,"sources":[]}"""),
        )
    }

    @Test
    fun newAndUpgradeCandidatesAreSortedByKey() {
        val db = openDb()
        insertInstalled(db, "a.example", "1.0.0")
        insertInstalled(db, "b.example", "2.0.0")
        val catalog = catalogOf(db)
        val raw = """
            {"version":1,"sources":[
              {"key":"b.example","version":"2.0.0","url":"https://cdn.example.com/b.js"},
              {"key":"c.example","version":"1.0.0","filename":"c.js"},
              {"key":"a.example","version":"1.1.0","url":"https://cdn.example.com/a.js"}
            ]}
        """.trimIndent()

        val rows = catalog.planRepoUpdates(raw)
        assertEquals(listOf("a.example", "c.example"), rows.map { it.key })
        assertEquals(listOf("upgrade", "new"), rows.map { it.reason })
        assertEquals("1.0.0", rows.first().installedVersion)
        assertEquals("https://cdn.example.com/a.js", rows.first().downloadUrl)
        assertEquals("c.js", rows.last().downloadUrl)
    }

    @Test
    fun brokenIndexIsFailClosedNotThrown() {
        val catalog = catalogOf(openDb())

        assertEquals(emptyList(), catalog.planRepoUpdates("{ 这不是 JSON"))
        assertTrue(catalog.planRepoUpdates("").isEmpty())
    }

    @Test
    fun minAppAboveHostVersionIsFilteredOut() {
        val catalog = catalogOf(openDb())
        val raw = """
            {"sources":[
              {"key":"future.example","version":"1.0.0","minApp":"2.0.0","url":"https://cdn.example.com/f.js"},
              {"key":"ok.example","version":"1.0.0","minApp":"1.0.0","url":"https://cdn.example.com/o.js"}
            ]}
        """.trimIndent()

        assertEquals(listOf("ok.example"), catalog.planRepoUpdates(raw).map { it.key })
        // 显式降低宿主版本后，两条都应被过滤。
        assertEquals(emptyList(), catalog.planRepoUpdates(raw, appVersionOverride = "0.9.0"))
    }

    @Test
    fun refreshSurfacesFetchFailureAsError() = runTest {
        val catalog = catalogOf(openDb())

        val ok = catalog.refreshRepoUpdates {
            """{"sources":[{"key":"x.example","version":"1.0.0","url":"https://cdn.example.com/x.js"}]}"""
        }
        assertEquals(null, ok.error)
        assertEquals(listOf("x.example"), ok.candidates.map { it.key })

        val failed = catalog.refreshRepoUpdates { error("network-down") }
        assertNotNull(failed.error)
        assertTrue(failed.candidates.isEmpty())
    }
}
