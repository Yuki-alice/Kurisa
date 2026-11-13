package app.shared

import core.model.SourceRecord
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

    /** 与生产同形的替身：把原始串当作 workId，sourceKey 回填来源。 */
    private val parse: (String, SourceRecord) -> List<ExploreHit> = { raw, rec ->
        listOf(ExploreHit(sourceKey = rec.key, workId = raw, title = raw))
    }

    @Test
    fun noSourceReturnsExplicitError() = runTest {
        val result = aggregateExplore(
            records = emptyList(),
            call = { error("无源时不应触发引擎调用") },
            parse = parse,
        )
        assertTrue(result.hits.isEmpty())
        assertEquals(listOf("无可用源"), result.errors)
    }

    @Test
    fun successfulSourceYieldsHitsWithSourceKey() = runTest {
        val result = aggregateExplore(
            records = listOf(record("com.example.a")),
            call = { "w1" },
            parse = parse,
        )
        assertEquals(listOf("w1"), result.hits.map { it.workId })
        assertEquals("com.example.a", result.hits.single().sourceKey)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun failingSourceDoesNotBreakOtherSources() = runTest {
        val a = record("com.example.a")
        val result = aggregateExplore(
            records = listOf(a, record("com.example.b")),
            call = { rec -> if (rec.key == a.key) error("能力未声明") else "w2" },
            parse = parse,
        )
        assertEquals(listOf("w2"), result.hits.map { it.workId })
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().contains("能力未声明"))
        assertTrue(result.errors.single().contains(a.key))
    }
}
