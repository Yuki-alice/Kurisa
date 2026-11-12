package app.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.db.ReaderDatabase
import data.network.CookieStore
import feature.sources.InstallOutcomeUi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SourceCatalog 集成测试（desktopTest，in-memory DB + MockEngine）。
 *
 * 覆盖 toggle 全链路：写 DB → 失效运行时 → 快照/白名单刷新。
 */
class SourceCatalogTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun insert(
        db: ReaderDatabase,
        key: String,
        domains: String,
        enabled: Boolean,
    ) {
        db.sourceQueries.upsertSource(
            key, "N-$key", "1.0.0", 2L,
            "[\"manga\"]", "[\"search\"]", domains,
            "sources/$key/1.0.0.js", "0".repeat(64),
            0L, if (enabled) 1L else 0L, "{}", 1L,
        )
    }

    private fun catalogOf(db: ReaderDatabase): SourceCatalog {
        val mock = MockEngine { respond("{}", HttpStatusCode.OK) }
        return createSourceCatalog(
            db = db,
            httpClient = HttpClient(mock),
            cookies = CookieStore(),
            // 全禁用后活跃路由为空，脚本不应被加载；此处抛错即证明未触达引擎。
            loadScript = { error("不应读脚本") },
            nowMs = { 7L },
        )
    }

    @Test
    fun toggleDisablesAndRefreshes() = runTest {
        val db = openDb()
        insert(db, "a.com.example", "[\"a.com\"]", enabled = true)
        insert(db, "b.com.example", "[\"b.com\"]", enabled = true)
        val catalog = catalogOf(db)

        assertEquals(2, catalog.snapshotUi().size)
        assertEquals(setOf("a.com", "b.com"), catalog.currentDomains())

        assertTrue(catalog.setEnabled("a.com.example", false))
        assertEquals(
            listOf(false, true),
            catalog.snapshotUi().map { it.enabled },
        )
        // 白名单即刻收窄。
        assertEquals(setOf("b.com"), catalog.currentDomains())

        // 全部禁用后视同无源：空目录，不触达脚本/引擎（loadScript 会抛错）。
        assertTrue(catalog.setEnabled("b.com.example", false))
        assertEquals(emptyList(), catalog.chapters.chapters("w"))

        assertTrue(catalog.setEnabled("a.com.example", true))
        assertEquals(setOf("a.com"), catalog.currentDomains())
    }

    @Test
    fun toggleUnknownKeyReturnsFalse() = runTest {
        val catalog = catalogOf(openDb())
        assertFalse(catalog.setEnabled("missing", false))
    }

    private fun script(version: String = "1.0.0") = """
        // ==SourceScript==
        // @name        T
        // @key         com.example.t
        // @version     $version
        // @apiVersion  2
        // @minApp      1.0.0
        // @kind        manga
        // @domains     example.com
        // @capabilities search
        // @url         https://example.com/t.js
        // ==/SourceScript==
        class T extends ComicSource {}
    """.trimIndent()

    @Test
    fun installPersistsAndActivatesImmediately() = runTest {
        val db = openDb()
        val catalog = catalogOf(db)
        val saved = mutableListOf<Triple<String, String, String>>()

        // 未签名源必须二次确认：未勾选时回 NeedsConfirm，不写库不落盘。
        val needs = catalog.installSource(
            script(),
            saveScript = { k, v, s, f -> saved += Triple(k, v, s); "/s/$k/$v.js" },
            confirmed = false,
        )
        assertTrue(needs is InstallOutcomeUi.NeedsConfirm)
        assertTrue(saved.isEmpty())
        assertTrue(catalog.snapshotUi().isEmpty())

        // 确认后落盘写库一次完成；返回记录字段完整（白名单当场可用）。
        val done = catalog.installSource(
            script(),
            saveScript = { k, v, s, f -> saved += Triple(k, v, s); "/s/$k/$v.js" },
            confirmed = true,
        )
        assertTrue(done is InstallOutcomeUi.Installed)
        assertEquals(listOf(Triple("com.example.t", "1.0.0", script())), saved)
        assertEquals("T", done.item.name)
        assertEquals(true, done.item.enabled)
        assertEquals(setOf("example.com"), catalog.currentDomains())
        assertEquals(listOf("com.example.t"), catalog.snapshotUi().map { it.key })
        // confirm() 返回记录字段完整（修复前 kinds/domains 为空导致当场白名单缺失）。
        assertEquals("[\"example.com\"]", catalog.snapshot().single().domainsJson)

        // 已装同 key 新版本 → 预览报更新。
        assertEquals("更新", catalog.previewInstall(script("1.1.0")).updateLabel)

        // 非法脚本直接拒绝，不写库。
        val bad = catalog.installSource(
            "class X extends ComicSource {}",
            saveScript = { k, v, s, f -> "/s/$k/$v.js" },
            confirmed = true,
        )
        assertTrue(bad is InstallOutcomeUi.Rejected)
        assertEquals(1, catalog.snapshotUi().size)
    }

    @Test
    fun testRulesRunsThreeStepDiagnose() = runTest {
        val searchHtml = "<div class=\"book-li\"><a href=\"/comic/w1.html\">T1</a></div>"
        val detailHtml = "<html><body><h1>W1</h1><div id=\"chapters\"><a href=\"/comic/w1/c1.html\">第一话</a></div></body></html>"
        val contentHtml = "<div id=\"images\"><img src=\"/p1.jpg\"/></div>"
        val mock = MockEngine { request ->
            val p = request.url.encodedPath
            val body = when {
                p.startsWith("/s") -> searchHtml
                p == "/comic/w1" -> detailHtml
                p == "/comic/w1/c1.html" -> contentHtml
                else -> return@MockEngine respond("{}", HttpStatusCode.NotFound)
            }
            respond(body, HttpStatusCode.OK)
        }
        val scripts = mutableMapOf<String, String>()
        val catalog = createSourceCatalog(
            db = openDb(),
            httpClient = HttpClient(mock),
            cookies = CookieStore(),
            loadScript = { path -> scripts[path] ?: error("missing script: $path") },
            appVersion = "1.0.0",
            nowMs = { 7L },
        )
        val rules = """
        {
          "format": "rules/v1", "key": "ex.test", "name": "T", "version": "1.0.0",
          "kind": ["manga"], "domains": ["cdn.example.com"], "baseUrl": "https://cdn.example.com/",
          "search": {
            "request": {"url": "/s?q=@keyword&page=@page"}, "mode": "html", "list": ".book-li",
            "fields": {"id": "a@href | fileName", "title": "a@text | trim"},
            "pagination": {"maxPage": "1"}
          },
          "detail": {
            "request": {"url": "/comic/@id"},
            "title": "h1@text",
            "chapters": {"list": "#chapters a", "id": "@href | fileName", "title": "@text | trim"}
          },
          "content": {"images": {"list": "#images img", "value": "@src | absUrl"}}
        }
        """.trimIndent()
        val installed = catalog.installSource(
            rules,
            saveScript = { k, v, s, f ->
                val ext = if (f == "rules/v1") "$v.rules.json" else "$v.js"
                val path = "/s/$k/$ext"
                scripts[path] = s
                path
            },
            confirmed = true,
        )
        assertTrue(installed is InstallOutcomeUi.Installed)
        val raw = catalog.testRules("ex.test", "T", 1, "w1", "/comic/w1/c1.html")
        val o = Json.parseToJsonElement(raw).jsonObject
        val search = o["search"]!!.jsonObject
        assertEquals("w1", search["sample"]!!.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.content)
        val detail = o["detail"]!!.jsonObject
        assertEquals("W1", detail["title"]?.jsonPrimitive?.content)
        assertEquals("c1", detail["sample"]!!.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.content)
        val content = o["content"]!!.jsonObject
        assertEquals("images", content["mode"]?.jsonPrimitive?.content)
        assertTrue(content["sample"]!!.jsonArray[0].jsonPrimitive.content.endsWith("p1.jpg"))
        // 未知源 key 显式拒绝。
        assertFailsWith<source.bridge.SourcePluginException> {
            catalog.testRules("nope", "T", 1, "w1", "c1")
        }
    }

    @Test
    fun testRulesRejectsJsSource() = runTest {
        val scripts = mutableMapOf<String, String>()
        val mock = MockEngine { respond("{}", HttpStatusCode.OK) }
        val catalog = createSourceCatalog(
            db = openDb(),
            httpClient = HttpClient(mock),
            cookies = CookieStore(),
            loadScript = { path -> scripts[path] ?: error("missing script: $path") },
            appVersion = "1.0.0",
            nowMs = { 7L },
        )
        val saved = mutableListOf<String>()
        val done = catalog.installSource(
            script(),
            saveScript = { k, v, s, f ->
                saved += f
                val path = "/s/$k/$v.js"
                scripts[path] = s
                path
            },
            confirmed = true,
        )
        assertTrue(done is InstallOutcomeUi.Installed)
        assertEquals(listOf("js/v1"), saved)
        // JS 源不支持测试面板（走日志排查），显式 CONTRACT 而非静默空结果。
        val err = assertFailsWith<source.bridge.SourcePluginException> {
            catalog.testRules("com.example.t", "T", 1, "w", "c")
        }
        assertEquals(source.contract.SourceErrorKind.CONTRACT, err.kind)
    }

    @Test
    fun installRulesUsesRulesJsonPath() = runTest {
        val db = openDb()
        val catalog = catalogOf(db)
        val formats = mutableListOf<String>()
        val script = """
        {
          "format": "rules/v1", "key": "ex.rules", "name": "Rules",
          "version": "1.0.0", "kind": ["manga"], "domains": ["cdn.example.com"],
          "baseUrl": "https://cdn.example.com/",
          "search": {"request": {"url": "/s"}, "list": ".a", "fields": {}},
          "detail": {"request": {"url": "/d"}, "chapters": {"list": ".c"}},
          "content": {"images": {"list": "img", "value": "@src | absUrl"}}
        }
        """.trimIndent()
        // 无签名规则包同样需二次确认。
        val needs = catalog.installSource(
            script,
            saveScript = { k, v, s, f -> "/s/$k/$v.js" },
            confirmed = false,
        )
        assertTrue(needs is InstallOutcomeUi.NeedsConfirm)
        // 平台按 format 分发扩展名（与 MainActivity/Main.kt 同规则）。
        val done = catalog.installSource(
            script,
            saveScript = { k, v, s, f ->
                formats += f
                val ext = if (f == "rules/v1") "$v.rules.json" else "$v.js"
                "/s/$k/$ext"
            },
            confirmed = true,
        )
        assertTrue(done is InstallOutcomeUi.Installed)
        assertEquals(listOf("rules/v1"), formats)
        assertEquals("/s/ex.rules/1.0.0.rules.json", catalog.snapshot().single().installPath)
        assertEquals(setOf("cdn.example.com"), catalog.currentDomains())
    }

    @Test
    fun reinstallHotReloadsRuntime() = runTest {
        // 热重载 = 重装（同 key 新版本）→ 运行时失效 → 下次调用装配新脚本，无需重启。
        fun rules(title: String, version: String) = """
        {
          "format": "rules/v1", "key": "ex.hot", "name": "Hot",
          "version": "$version", "kind": ["manga"], "domains": ["cdn.example.com"],
          "baseUrl": "https://cdn.example.com/",
          "search": {"request": {"url": "/s"}, "mode": "html", "list": ".a", "fields": {
            "id": "a@href | fileName", "title": "a@text | trim"
          }, "pagination": {"maxPage": "1"}},
          "detail": {
            "request": {"url": "/d"}, "mode": "html", "title": "h1@text",
            "chapters": {"list": "#ch a", "id": "@href | fileName", "title": "@text | trim"}
          },
          "content": {"images": {"list": "img", "value": "@src | absUrl"}}
        }
        """.trimIndent()
        val detailBody = { title: String ->
            "<html><body><h1>$title</h1><div id=\"ch\"><a href=\"/c1\">第一话</a></div></body></html>"
        }
        var detail = detailBody("V1")
        val mock = MockEngine { request ->
            val p = request.url.encodedPath
            val body = when {
                p.startsWith("/s") -> "<div class=\"a\"><a href=\"/w1\">T</a></div>"
                p == "/d" -> detail
                else -> return@MockEngine respond("{}", HttpStatusCode.NotFound)
            }
            respond(body, HttpStatusCode.OK)
        }
        val scripts = mutableMapOf<String, String>()
        val catalog = createSourceCatalog(
            db = openDb(),
            httpClient = HttpClient(mock),
            cookies = CookieStore(),
            loadScript = { path -> scripts[path] ?: error("missing script: $path") },
            appVersion = "1.0.0",
            nowMs = { 7L },
        )
        val saver: suspend (String, String, String, String) -> String = { k, v, s, f ->
            val path = "/s/$k/$v.rules.json"
            scripts[path] = s
            path
        }
        assertTrue(catalog.installSource(rules("V1", "1.0.0"), saver, confirmed = true) is InstallOutcomeUi.Installed)
        assertEquals(1, catalog.chapters.chapters("w1").size)
        assertEquals("V1", detailTitle(catalog.testRules("ex.hot", "T", 1, "w1", "c1")))
        // 更新到 v2 并改服务端内容：下次调用即新脚本，不重启。
        detail = detailBody("V2")
        assertTrue(catalog.installSource(rules("V2", "2.0.0"), saver, confirmed = true) is InstallOutcomeUi.Installed)
        assertEquals("V2", detailTitle(catalog.testRules("ex.hot", "T", 1, "w1", "c1")))
        assertEquals(1, catalog.chapters.chapters("w1").size)
    }

    private fun detailTitle(combined: String): String =
        kotlinx.serialization.json.Json.parseToJsonElement(combined).jsonObject["detail"]!!
            .jsonObject["title"]!!.jsonPrimitive.content
}
