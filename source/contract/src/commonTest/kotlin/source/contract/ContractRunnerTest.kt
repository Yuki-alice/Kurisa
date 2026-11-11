package source.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 门槛：漫画 + 小说各跑通一条完整链路（三端：desktop/androidHost/iosSimulator）。
 * androidHost 无原生 quickjs 时按约定跳过（M1 真机补）。
 */
class ContractRunnerTest {

    private fun harness() = ContractHarness { _, _ -> ExampleSources.SEARCH_HTML }

    @Test
    fun mangaSearchDetailEp() = runBlocking(Dispatchers.Default) {
        val h = harness()
        val raw = h.run(ExampleSources.MANGA_JS, "one")
        if (raw == "__HOST_MISSING__") return@runBlocking
        val o = Json.parseToJsonElement(raw).jsonObject
        val errEl = o["error"]
        val err = if (errEl == null || errEl is JsonNull) null else errEl.jsonPrimitive.content
        assertEquals(null, err, "runner error: $err")
        val search = o["search"]!!.jsonObject
        val comics = search["comics"]!!.jsonArray
        assertEquals(2, comics.size)
        // option 默认值 + APP.locale 双断言
        assertTrue(comics[0].jsonObject["title"]!!.jsonPrimitive.content.contains("|any|zh_CN"))
        assertEquals(3, search["maxPage"]!!.jsonPrimitive.intOrNull)
        val detail = o["detail"]!!.jsonObject
        assertEquals("Demo Title", detail["title"]!!.jsonPrimitive.content)
        val extra = o["extra"]!!.jsonObject
        val images = extra["images"]!!.jsonArray
        assertEquals(1, images.size)
        assertTrue(images[0].jsonPrimitive.content.endsWith("/w1/c1/p1.jpg"))
        assertTrue(h.disposedCount() >= 2, "HtmlDocument 应被 dispose")
    }

    @Test
    fun novelSearchDetailChapter() = runBlocking(Dispatchers.Default) {
        val h = harness()
        val raw = h.run(ExampleSources.NOVEL_JS, "赘婿")
        if (raw == "__HOST_MISSING__") return@runBlocking
        val o = Json.parseToJsonElement(raw).jsonObject
        val search = o["search"]!!.jsonObject
        val works = search["works"]!!.jsonArray
        assertEquals(1, works.size)
        assertTrue(works[0].jsonObject["title"]!!.jsonPrimitive.content.contains("|any"))
        val detail = o["detail"]!!.jsonObject
        assertEquals("Demo Novel", detail["title"]!!.jsonPrimitive.content)
        val extra = o["extra"]!!.jsonObject
        assertEquals("html", extra["format"]!!.jsonPrimitive.content)
        assertTrue(extra["text"]!!.jsonPrimitive.content.contains("第一段"))
        assertTrue(h.disposedCount() >= 2, "HtmlDocument 应被 dispose")
    }
}
