package app.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.ChapterPayload
import data.db.ReaderDatabase
import data.network.CookieStore
import feature.sources.InstallOutcomeUi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 网络漫画源端到端 walkthrough（`docs/comic-e2e-plan.md` §3.2 的自动回归）。
 *
 * 全程 `example.*` 合成域名 + MockEngine，不触真实站点（C1）。
 * 覆盖：安装 → 跨源搜索 → 详情 → 绑定作品归属 → 章节 → 正文图片，
 * 并锁死「绑定后按作品命中源、未绑定时回退首个 enabled 源」的 B4 语义。
 */
class ComicWalkthroughTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun rules(key: String, domain: String) = """
        {
          "format": "rules/v1", "key": "$key", "name": "$key", "version": "1.0.0",
          "kind": ["manga"], "domains": ["$domain"], "baseUrl": "https://$domain/",
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

    private fun mockByHost() = MockEngine { request ->
        val tag = request.url.host.first().toString()
        val path = request.url.encodedPath
        val body = when {
            path.startsWith("/s") ->
                "<div class=\"book-li\"><a href=\"/w1\">$tag-Title</a></div>"
            path.startsWith("/comic/") ->
                "<html><body><h1>$tag-W1</h1><div id=\"chapters\">" +
                    "<a href=\"/${tag}1\">$tag-第一话</a></div></body></html>"
            else -> "<div id=\"images\"><img src=\"/p1.jpg\"/></div>"
        }
        respond(body, HttpStatusCode.OK)
    }

    private suspend fun installAll(catalog: SourceCatalog, scripts: MutableMap<String, String>) {
        for ((key, domain) in listOf("ex.a" to "a.example.com", "ex.b" to "b.example.com")) {
            val outcome = catalog.installSource(
                rules(key, domain),
                saveScript = { k, v, s, f ->
                    val ext = if (f == "rules/v1") "$v.rules.json" else "$v.js"
                    val path = "/s/$k/$ext"
                    scripts[path] = s
                    path
                },
                confirmed = true,
            )
            assertTrue(outcome is InstallOutcomeUi.Installed, "$key 安装失败：$outcome")
        }
    }

    @Test
    fun networkWalkthroughInstallSearchDetailChaptersPayload() = runTest {
        val scripts = mutableMapOf<String, String>()
        val catalog = createSourceCatalog(
            db = openDb(),
            httpClient = HttpClient(mockByHost()),
            cookies = CookieStore(),
            loadScript = { path -> scripts[path] ?: error("missing script: $path") },
            appVersion = "1.0.0",
            nowMs = { 7L },
        )
        installAll(catalog, scripts)
        assertEquals(listOf("ex.a", "ex.b"), catalog.enabledSourceKeys())

        // 1) 跨源聚合搜索：结果带 sourceKey，单源失败不拖垮全局。
        val page = catalog.search("W", 1)
        assertTrue(page.errors.isEmpty(), "搜索错误：${page.errors}")
        assertEquals(2, page.works.size)
        assertTrue(page.works.any { it.sourceKey == "ex.a" && it.workId == "w1" && it.title == "a-Title" })
        assertTrue(page.works.any { it.sourceKey == "ex.b" && it.workId == "w1" && it.title == "b-Title" })

        // 2) 详情按 sourceKey 精确取源。
        val detail = catalog.detail("w1", "ex.b")
        assertEquals(null, detail.error)
        assertEquals("b-W1", detail.title)
        assertEquals(listOf("b1"), detail.chapters.map { it.id })

        // 3) 未绑定时保持旧行为：回退首个 enabled 源（ex.a）。
        assertEquals(listOf("a1"), catalog.chapters.chapters("w1").map { it.id })

        // 4) B4：绑定后按作品命中 ex.b，章节与正文图片都来自该源。
        catalog.bindWork("w1", "ex.b")
        assertEquals(listOf("b1"), catalog.chapters.chapters("w1").map { it.id })
        val payload = catalog.chapters.payload("w1", "b1")
        require(payload is ChapterPayload.Images) { "漫画章节应返回图片：${payload::class.simpleName}" }
        assertEquals(listOf("https://b.example.com/p1.jpg"), payload.urls)

        // 5) 白名单并集来自两个已启用源，图片出口逐跳复核用。
        assertEquals(setOf("a.example.com", "b.example.com"), catalog.currentDomains())
    }

    @Test
    fun searchIsolatesSingleSourceFailure() = runTest {
        val scripts = mutableMapOf<String, String>()
        val catalog = createSourceCatalog(
            db = openDb(),
            httpClient = HttpClient(mockByHost()),
            cookies = CookieStore(),
            loadScript = { path -> scripts[path] ?: error("missing script: $path") },
            appVersion = "1.0.0",
            nowMs = { 7L },
        )
        installAll(catalog, scripts)
        // 损坏其中一个源的脚本：该源报错，另一源仍返回结果。
        scripts["/s/ex.a/1.0.0.rules.json"] = "{ 非法 JSON"
        val page = catalog.search("W", 1)
        assertEquals(1, page.errors.size)
        assertTrue(page.works.any { it.sourceKey == "ex.b" })
    }
}
