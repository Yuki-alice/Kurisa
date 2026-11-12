package feature.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 规则测试面板映射单测（纯函数；面板展示只依赖本映射，Compose 层保持薄）。
 */
class RuleTestMappingTest {

    private fun combined(search: String, detail: String, content: String) =
        """{"search":$search,"detail":$detail,"content":$content}"""

    @Test
    fun splitsThreeSections() {
        val r = parseRuleTestResult(
            combined(
                """{"requestUrl":"https://cdn.example.com/s?q=T"}""",
                """{"title":"W"}""",
                """{"mode":"images"}""",
            ),
        )
        assertNull(r.error)
        assertTrue(r.searchJson.contains("cdn.example.com/s"))
        assertTrue(r.detailJson.contains("\"title\""))
        assertTrue(r.contentJson.contains("images"))
    }

    @Test
    fun missingSectionIsError() {
        val r = parseRuleTestResult("""{"search":{},"detail":{}}""")
        assertEquals("诊断缺 content 段", r.error)
        assertTrue(r.searchJson.isEmpty())
    }

    @Test
    fun invalidJsonIsError() {
        val r = parseRuleTestResult("not-json{{{")
        assertEquals("诊断返回非法 JSON", r.error)
    }

    @Test
    fun errorPassthroughKeepsRaw() {
        // 后端失败通道 {error,code} 按段透传：面板展示原文，不二次分类。
        val raw = combined(
            """{"error":"规则搜索失败: 404","code":"CONTRACT"}""",
            """{"error":"规则详情请求失败: 404","code":"CONTRACT"}""",
            """{"error":"规则内容请求失败: 404","code":"CONTRACT"}""",
        )
        val r = parseRuleTestResult(raw)
        assertNull(r.error)
        assertTrue(r.searchJson.contains("404"))
    }
}
