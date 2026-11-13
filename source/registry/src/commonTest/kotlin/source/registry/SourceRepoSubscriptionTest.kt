package source.registry

import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRepoSubscriptionTest {

    /** commonTest 未引入 kotlinx-coroutines-test，用 stdlib 同步启动协程跑 suspend 用例。 */
    private fun <T> runSuspend(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "协程未同步完成" }.getOrThrow()
    }

    @Test
    fun parseObjectFormAndTopLevelArray() {
        val obj = SourceIndexParser.parse(
            """{"version":2,"apiVersion":9,"sources":[
                {"key":"com.example.a","version":"1.0.0","name":"A","url":"https://cdn.example.com/a.js"},
                {"key":"com.example.b","version":"2.0.0","filename":"b.js"}
            ]}"""
        )
        // 顶层 version 优先于 apiVersion（源仓真实 index.json 用 version）。
        assertEquals(2, obj.version)
        assertEquals(listOf("com.example.a", "com.example.b"), obj.sources.map { it.key })
        assertEquals("https://cdn.example.com/a.js", obj.sources[0].url)
        assertEquals("b.js", obj.sources[1].filename)
        assertEquals("js/v1", obj.sources[0].format) // 缺省 format
        assertEquals("", obj.sources[1].name)        // 缺省 name

        // docs 契约形态：顶层只有 apiVersion 也能读成索引格式版本。
        val apiOnly = SourceIndexParser.parse("""{"apiVersion":4,"sources":[]}""")
        assertEquals(4, apiOnly.version)
        assertTrue(apiOnly.sources.isEmpty())

        // 顶层数组形态。
        val arr = SourceIndexParser.parse(
            """[
                {"key":"com.example.a","version":"1.0.0"},
                {"key":"com.example.b","version":"2.0.0","apiVersion":4}
            ]"""
        )
        assertEquals(1, arr.version) // 顶层数组无格式版本，取缺省 1
        assertEquals(listOf("com.example.a", "com.example.b"), arr.sources.map { it.key })
    }

    @Test
    fun skipEntriesMissingKeyOrVersionAndIgnoreUnknownFields() {
        val idx = SourceIndexParser.parse(
            """{"sources":[
                {"key":"com.example.ok","version":"1.0.0","unknown":"x","kind":["manga"],"sha256":"00"},
                {"version":"9.9.9","name":"no-key"},
                {"key":"com.example.no-version","name":"no-ver"},
                {"key":"","version":"1.0.0"},
                {"key":"com.example.blank","version":"  "}
            ]}"""
        )
        // 未知字段被忽略，缺 key/version/空白值的条目被跳过。
        assertEquals(listOf("com.example.ok"), idx.sources.map { it.key })
        assertEquals("1.0.0", idx.sources[0].version)
    }

    @Test
    fun invalidJsonThrowsIllegalArgumentException() {
        val e = assertFailsWith<IllegalArgumentException> {
            SourceIndexParser.parse("""{"sources": [""")
        }
        assertTrue(e.message!!.startsWith("index-parse:"))
    }

    @Test
    fun planNewUpgradeAndSkipSameVersion() {
        val idx = RepoIndex(
            sources = listOf(
                RepoSourceEntry(key = "com.example.new", version = "1.0.0"),
                RepoSourceEntry(key = "com.example.up", version = "2.0.0"),
                RepoSourceEntry(key = "com.example.same", version = "1.0.0"),
            )
        )
        val installed = listOf(
            "com.example.up" to "1.0.0",
            "com.example.same" to "1.0.0",
        )
        val result = SourceRepoPlanner.plan(idx, installed)
        assertEquals(listOf("com.example.new", "com.example.up"), result.map { it.entry.key })
        assertEquals(listOf("new", "upgrade"), result.map { it.reason })
        assertNull(result[0].installedVersion)
        assertEquals("1.0.0", result[1].installedVersion)
    }

    @Test
    fun semanticVersionComparisonAndDowngradeSkipped() {
        // 数值比较而非字典序、前导 v 归一化、缺段补 0、非数字段按字符串。
        assertTrue(SourceRepoPlanner.compareVersions("1.10.0", "1.9.0") > 0)
        assertEquals(0, SourceRepoPlanner.compareVersions("v1.0", "1.0"))
        assertEquals(0, SourceRepoPlanner.compareVersions("1.2", "1.2.0"))
        assertTrue(SourceRepoPlanner.compareVersions("1.0.0-beta", "1.0.0-alpha") > 0)

        // 远端版本低于已装：降级不列入；相同版本也不列入。
        val idx = RepoIndex(
            sources = listOf(
                RepoSourceEntry(key = "com.example.down", version = "1.9.0"),
                RepoSourceEntry(key = "com.example.keep", version = "1.0.0"),
            )
        )
        val installed = listOf(
            "com.example.down" to "1.10.0",
            "com.example.keep" to "1.0.0",
        )
        assertTrue(SourceRepoPlanner.plan(idx, installed).isEmpty())
    }

    @Test
    fun minAppAboveAppVersionSkipped() {
        val idx = RepoIndex(
            sources = listOf(
                RepoSourceEntry(key = "com.example.future", version = "2.0.0", minApp = "2.0.0"),
                RepoSourceEntry(key = "com.example.ok", version = "1.0.0", minApp = "1.0.0"),
                RepoSourceEntry(key = "com.example.noMin", version = "1.0.0"),
            )
        )
        val result = SourceRepoPlanner.plan(idx, emptyList(), appVersion = "1.5.0")
        assertEquals(listOf("com.example.noMin", "com.example.ok"), result.map { it.entry.key })
        assertTrue(result.all { it.reason == "new" })
    }

    @Test
    fun fetchFailureReturnsErrorWithEmptyCandidates() {
        val sub = SourceRepoSubscription { throw RuntimeException("boom") }
        val r = runSuspend { sub.refresh(installed = listOf("com.example.a" to "1.0.0")) }
        assertNotNull(r.error)
        assertTrue(r.candidates.isEmpty())
        assertTrue(r.index.sources.isEmpty())
    }

    @Test
    fun refreshSuccessReturnsParsedCandidates() {
        val sub = SourceRepoSubscription {
            """{"version":1,"sources":[{"key":"com.example.a","version":"2.0.0","url":"https://cdn.example.com/a.js"}]}"""
        }
        val r = runSuspend { sub.refresh(installed = listOf("com.example.a" to "1.0.0")) }
        assertNull(r.error)
        assertEquals(1, r.candidates.size)
        assertEquals("upgrade", r.candidates[0].reason)
    }

    @Test
    fun resultSortedByKeyAndDuplicateKeysKeepLast() {
        val idx = SourceIndexParser.parse(
            """{"sources":[
                {"key":"com.example.z","version":"1.0.0"},
                {"key":"com.example.a","version":"1.0.0"},
                {"key":"com.example.m","version":"9.0.0"},
                {"key":"com.example.a","version":"3.0.0"},
                {"key":"com.example.m","version":"1.0.0"}
            ]}"""
        )
        // 去重保留最后一条：a=3.0.0、m=1.0.0、z=1.0.0；结果按 key 有序。
        val result = SourceRepoPlanner.plan(idx, emptyList())
        assertEquals(listOf("com.example.a", "com.example.m", "com.example.z"), result.map { it.entry.key })
        assertEquals("3.0.0", result[0].entry.version)
        assertEquals("1.0.0", result[1].entry.version)
    }
}
