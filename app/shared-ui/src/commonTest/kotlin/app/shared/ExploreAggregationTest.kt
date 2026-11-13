package app.shared

import core.model.SourceRecord
import feature.search.SearchPageUi
import feature.search.SearchWorkUi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BE-A4 发现 / 分类后端出口的三态聚合测试（纯逻辑，不装配 JS 引擎）。
 *
 * 覆盖：无可用源、单源成功、以及「一个源失败但其余源仍返回结果」。
 */
class ExploreAggregationTest {

    private fun record(key: String) = SourceRecord(
        key = key,
        name = "源-$key",
        version = "1.0.0",
        apiVersion = 2,
        kindsJson = "[\"manga\"]",
        capabilitiesJson = "[\"category\"]",
        domainsJson = "[\"example.com\"]",
        installPath = "sources/$key/1.0.0.js",
        sha256 = "0".repeat(64),
        signatureOk = false,
        enabled = true,
        settingsJson = "{}",
        updatedAtEpochMs = 0L,
    )

    /** 与生产 parseSearchPage 同形的替身：把原始串当作 workId 回显。 */
    private val parse: (String, SourceRecord) -> SearchPageUi = { raw, rec ->
        SearchPageUi(
            works = listOf(SearchWorkUi(workId = raw, sourceKey = rec.key, title = raw)),
            maxPage = 2,
        )
    }

    @Test
    fun noSourceReturnsExplicitError() = runTest {
        val page = aggregateExplore(
            records = emptyList(),
            call = { error("无源时不应触发引擎调用") },
            parse = parse,
        )
        assertTrue(page.works.isEmpty())
        assertEquals(listOf("无可用源"), page.errors)
    }

    @Test
    fun successfulSourceYieldsWorksAndPageCount() = runTest {
        val page = aggregateExplore(
            records = listOf(record("com.example.a")),
            call = { "w1" },
            parse = parse,
        )
        assertEquals(listOf("w1"), page.works.map { it.workId })
        assertEquals("com.example.a", page.works.single().sourceKey)
        assertEquals(2, page.maxPage)
        assertTrue(page.errors.isEmpty())
    }

    @Test
    fun failingSourceDoesNotBreakOtherSources() = runTest {
        val a = record("com.example.a")
        val b = record("com.example.b")
        val page = aggregateExplore(
            records = listOf(a, b),
            call = { rec -> if (rec.key == a.key) error("能力未声明") else "w2" },
            parse = parse,
        )
        assertEquals(listOf("w2"), page.works.map { it.workId })
        assertEquals(1, page.errors.size)
        assertTrue(page.errors.single().contains("能力未声明"))
        assertTrue(page.errors.single().contains(a.key))
    }
}
