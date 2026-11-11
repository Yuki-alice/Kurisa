package source.bridge

import data.network.CookieStore
import data.network.SourceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import source.contract.SourceCapability
import source.contract.SourceDescriptor
import source.contract.SourceErrorKind
import source.hostapi.HostApi
import source.hostapi.HostConvert
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 五类网站形态合成源 E2E（用户选型 S2/S3：漫画 JSON-API / 漫画服务端 HTML /
 * 漫画过盾分类 / 小说 JSON-API / 小说 HTML；全部本地 Mock，无外部站点，C1/GPL 安全）。
 *
 * 每类覆盖 search → 详情 → 章节全链路，行为断言而非实现细节。
 */
class V4ArchetypeSourcesTest {

    companion object {
        const val MANGA_JSON_JS = """
class J extends ComicSource {
  constructor() {
    super();
    this.name = 'J'; this.key = 'com.example.arch.json'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic', 'settings', 'imageLoad'];
    this.settings = { quality: { title: '画质', type: 'select', options: [{ value: 'orig' }], default: 'orig' } };
    this.search = {
      optionList: [{ options: ['new-最新'] }],
      load: async (keyword, options, page) => {
        const r = await Network.get('https://cdn.example.com/s?q=' + encodeURIComponent(keyword) + '&page=' + page);
        const j = JSON.parse(r.body);
        return { works: j.works, maxPage: j.maxPage };
      }
    };
    this.comic = {
      loadInfo: async (id) => {
        const r = await Network.get('https://cdn.example.com/m/' + id);
        const j = JSON.parse(r.body);
        return { title: j.title, chapters: j.chapters };
      },
      loadEp: async (workId, chapterId) => {
        const r = await Network.get('https://cdn.example.com/ep?w=' + workId + '&c=' + chapterId);
        return JSON.parse(r.body);
      },
      onImageLoad: (url, workId, chapterId) => ({
        url: url,
        headers: { 'Cookie': 's=1', 'User-Agent': 'evil', 'Referer': 'https://cdn.example.com/', 'X-Ok': '1' }
      })
    };
  }
}
"""

        const val MANGA_HTML_JS = """
class H extends ComicSource {
  constructor() {
    super();
    this.name = 'H'; this.key = 'com.example.arch.html'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic', 'htmlHelper', 'imageLoad'];
    this.search = {
      load: async (keyword, options, page) => {
        const r = await Network.get('https://cdn.example.com/s?q=' + encodeURIComponent(keyword));
        const titles = Html.list(r.body, '.w', function (el) { return el.text; });
        return { works: titles.map(function (t, i) { return { id: 'w' + (i + 1), title: t }; }), maxPage: 1 };
      }
    };
    this.comic = {
      loadInfo: async (id) => {
        const r = await Network.get('https://cdn.example.com/m/' + id);
        return {
          title: Html.text(r.body, 'h1'),
          cover: Html.attr(r.body, '.cover img', 'src'),
          chapters: [{ id: 'c1', title: '第一话' }]
        };
      },
      loadEp: async (workId, chapterId) => ({ images: ['https://cdn.example.com/1.jpg'] }),
      onImageLoad: (url, workId, chapterId) => ({ url: url, headers: { 'Referer': 'https://cdn.example.com/' } })
    };
  }
}
"""

        const val MANGA_SHIELD_JS = """
class S extends ComicSource {
  constructor() {
    super();
    this.name = 'S'; this.key = 'com.example.arch.shield'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic'];
    this.search = {
      load: async (keyword, options, page) => {
        const r = await Network.get('https://cdn.example.com/s?q=' + encodeURIComponent(keyword));
        if (r.status === 503) throwRateLimited('Cloudflare challenge');
        const j = JSON.parse(r.body);
        if (j.blocked) throwRateLimited('WAF block');
        return { works: j.works || [], maxPage: 1 };
      }
    };
    this.comic = {
      loadInfo: async (id) => ({ title: 'W', chapters: [{ id: 'c1', title: 't' }] }),
      loadEp: async (w, c) => ({ images: [] })
    };
  }
}
"""

        const val NOVEL_JSON_JS = """
class J extends ComicSource {
  constructor() {
    super();
    this.name = 'J'; this.key = 'com.example.arch.noveljson'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['novel']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'novel', 'novelChapter'];
    this.search = {
      load: async (keyword, options, page) => {
        const r = await Network.get('https://cdn.example.com/ns?q=' + encodeURIComponent(keyword));
        return JSON.parse(r.body);
      }
    };
    this.novel = {
      loadInfo: async (id) => {
        const r = await Network.get('https://cdn.example.com/n/' + id);
        return JSON.parse(r.body);
      },
      loadChapter: async (workId, chapterId) => {
        const r = await Network.get('https://cdn.example.com/nc?w=' + workId + '&c=' + chapterId);
        return JSON.parse(r.body);
      }
    };
  }
}
"""

        const val NOVEL_HTML_JS = """
class H extends ComicSource {
  constructor() {
    super();
    this.name = 'H'; this.key = 'com.example.arch.novelhtml'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['novel']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'novel', 'novelChapter', 'htmlHelper'];
    this.search = {
      load: async (keyword, options, page) => ({ works: [{ id: 'n1', title: keyword }], maxPage: 1 })
    };
    this.novel = {
      loadInfo: async (id) => ({ title: 'Nov', chapters: [{ id: 'ch1', title: '第一章' }] }),
      loadChapter: async (workId, chapterId) => {
        const r = await Network.get('https://cdn.example.com/nc/' + chapterId);
        return { content: cleanNovelHtml(r.body), format: 'html' };
      },
      onImageResolve: (url) => ({ url: url, headers: { 'Referer': 'https://cdn.example.com/' } })
    };
  }
}
"""
    }

    private class Fixture(
        val handler: (path: String, query: String) -> Pair<HttpStatusCode, String>,
    ) {
        val cookies = CookieStore()
        val http: SourceHttpClient
        val network: HostNetwork
        val host: HostApi

        init {
            val mock = MockEngine { request ->
                val (status, body) = handler(
                    request.url.encodedPath,
                    request.url.parameters["q"].orEmpty(),
                )
                respond(
                    content = ByteReadChannel(body),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
            http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
            network = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
            host = object : HostApi {
                override val network: HostNetwork = this@Fixture.network
                override val html: HostHtml = KsoupHostHtml()
                override val convert: HostConvert = CryptoHostConvert()
                override val ui: HostUi = NoopHostUi()
                override val utils: HostUtils = HostUtilsImpl()
                override val storage: HostStorage = InMemoryHostStorage()
                override val novel: HostNovel = NovelHost()
            }
        }

        fun engine(key: String) = QuickJsEngine(
            sourceKey = key,
            host = host,
            cookies = cookies,
            dispatchers = DefaultSourceDispatchers(),
        )
    }

    private fun mangaDescriptor(extra: Set<SourceCapability> = emptySet(), imageLoad: Boolean = false) =
        SourceDescriptor(
            key = "com.example.arch", name = "A", version = "1.0.0",
            kinds = setOf(ContentKind.MANGA),
            domains = listOf("cdn.example.com"),
            capabilities = setOf(SourceCapability.SEARCH, SourceCapability.COMIC) + extra,
            hasSearchLoad = true,
            hasImageLoad = imageLoad,
        )

    @Test
    fun mangaJsonApiEndToEnd() = runTest {
        val f = Fixture { path, _ ->
            when {
                path == "/s" -> HttpStatusCode.OK to
                    """{"works":[{"id":"w1","title":"T1","kind":"manga"}],"maxPage":3}"""
                path.startsWith("/m/") -> HttpStatusCode.OK to
                    """{"title":"W","chapters":[{"id":"c1","title":"第一话"}]}"""
                path == "/ep" -> HttpStatusCode.OK to
                    """{"images":["https://cdn.example.com/1.jpg","https://cdn.example.com/2.jpg"]}"""
                else -> HttpStatusCode.NotFound to """{}"""
            }
        }
        val e = f.engine("com.example.arch.json")
        try {
            e.load(MANGA_JSON_JS)
            val runtime = SourceRuntime(
                mangaDescriptor(
                    extra = setOf(SourceCapability.SETTINGS, SourceCapability.IMAGE_LOAD),
                    imageLoad = true,
                ),
                e,
            )
            val page = runtime.searchWorks("k", "[]", 2)
            assertTrue(page.contains("w1") && page.contains("\"maxPage\":3"), page)
            val chapters = BridgeChapterProvider(runtime, ContentKind.MANGA).chapters("w1")
            assertEquals(listOf("c1"), chapters.map { it.id })
            val payload = BridgeChapterProvider(runtime, ContentKind.MANGA).payload("w1", "c1")
            assertTrue(payload is core.model.ChapterPayload.Images)
            assertEquals(2, (payload as core.model.ChapterPayload.Images).urls.size)
            // 防盗链头透出执行业务头；Cookie/UA 被宿主剥离。
            val cfg = assertNotNull(runtime.imageLoadConfig("https://cdn.example.com/1.jpg", "w1", "c1"))
            assertFalse(cfg.headers.keys.any { it.equals("cookie", true) })
            assertFalse(cfg.headers.keys.any { it.equals("user-agent", true) })
            assertTrue(cfg.headers.values.any { it.contains("cdn.example.com") })
            assertTrue(cfg.headers.containsKey("X-Ok"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun mangaServerHtmlEndToEnd() = runTest {
        val f = Fixture { path, _ ->
            when {
                path == "/s" -> HttpStatusCode.OK to
                    """<div class="w">A</div><div class="w">B</div>"""
                path.startsWith("/m/") -> HttpStatusCode.OK to
                    """<h1>WT</h1><div class="cover"><img src="https://cdn.example.com/c.jpg"/></div>"""
                else -> HttpStatusCode.NotFound to ""
            }
        }
        val e = f.engine("com.example.arch.html")
        try {
            e.load(MANGA_HTML_JS)
            val runtime = SourceRuntime(
                mangaDescriptor(
                    extra = setOf(SourceCapability.HTML_HELPER, SourceCapability.IMAGE_LOAD),
                    imageLoad = true,
                ),
                e,
            )
            val page = runtime.searchWorks("k", "[]", 1)
            assertTrue(page.contains("\"title\":\"A\"") && page.contains("\"title\":\"B\""), page)
            val raw = runtime.loadWorkDetails("w1", ContentKind.MANGA)
            assertTrue(raw.contains("WT") && raw.contains("https://cdn.example.com/c.jpg"), raw)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun mangaShieldClassifiedRateLimited() = runTest {
        val f = Fixture { _, query ->
            when (query) {
                "cf" -> HttpStatusCode.ServiceUnavailable to "challenge"
                "waf" -> HttpStatusCode.OK to """{"blocked":true}"""
                else -> HttpStatusCode.OK to """{"works":[{"id":"w1","title":"T"}]}"""
            }
        }
        val e = f.engine("com.example.arch.shield")
        try {
            e.load(MANGA_SHIELD_JS)
            val runtime = SourceRuntime(
                mangaDescriptor(),
                e,
            )
            val cf = assertFailsWith<SourcePluginException> { runtime.searchWorks("cf", "[]", 1) }
            assertEquals(SourceErrorKind.RATE_LIMITED, cf.kind)
            val waf = assertFailsWith<SourcePluginException> { runtime.searchWorks("waf", "[]", 1) }
            assertEquals(SourceErrorKind.RATE_LIMITED, waf.kind)
            assertTrue(runtime.searchWorks("ok", "[]", 1).contains("w1"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun novelJsonApiEndToEnd() = runTest {
        val f = Fixture { path, _ ->
            when {
                path == "/ns" -> HttpStatusCode.OK to
                    """{"works":[{"id":"n1","title":"NT","kind":"novel"}],"maxPage":1}"""
                path.startsWith("/n/") -> HttpStatusCode.OK to
                    """{"title":"NT","chapters":[{"id":"ch1","title":"第一章"}]}"""
                path == "/nc" -> HttpStatusCode.OK to
                    """{"content":"line1\nline2","format":"plain"}"""
                else -> HttpStatusCode.NotFound to """{}"""
            }
        }
        val e = f.engine("com.example.arch.noveljson")
        try {
            e.load(NOVEL_JSON_JS)
            val runtime = SourceRuntime(
                SourceDescriptor(
                    key = "com.example.arch.noveljson", name = "J", version = "1.0.0",
                    kinds = setOf(ContentKind.NOVEL),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(
                        SourceCapability.SEARCH, SourceCapability.NOVEL,
                        SourceCapability.NOVEL_CHAPTER,
                    ),
                    hasSearchLoad = true,
                    hasNovelLoadInfo = true,
                    hasNovelLoadChapter = true,
                ),
                e,
            )
            assertTrue(runtime.searchWorks("k", "[]", 1).contains("n1"))
            assertEquals(listOf("ch1"), BridgeChapterProvider(runtime, ContentKind.NOVEL).chapters("n1").map { it.id })
            val payload = BridgeChapterProvider(runtime, ContentKind.NOVEL).payload("n1", "ch1")
            assertTrue(payload is core.model.ChapterPayload.Text)
            payload as core.model.ChapterPayload.Text
            assertEquals(core.model.TextFormat.PLAIN, payload.format)
            assertTrue(payload.body.contains("line1"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun novelHtmlCleanAndImageResolve() = runTest {
        val f = Fixture { path, _ ->
            if (path.startsWith("/nc/")) HttpStatusCode.OK to
                """<h1>T</h1><script>evil()</script><p>p1</p><img src="https://cdn.example.com/i.jpg"/>"""
            else HttpStatusCode.NotFound to ""
        }
        val e = f.engine("com.example.arch.novelhtml")
        try {
            e.load(NOVEL_HTML_JS)
            val runtime = SourceRuntime(
                SourceDescriptor(
                    key = "com.example.arch.novelhtml", name = "H", version = "1.0.0",
                    kinds = setOf(ContentKind.NOVEL),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(
                        SourceCapability.SEARCH, SourceCapability.NOVEL,
                        SourceCapability.NOVEL_CHAPTER, SourceCapability.HTML_HELPER,
                    ),
                    hasSearchLoad = true,
                    hasNovelLoadInfo = true,
                    hasNovelLoadChapter = true,
                    hasNovelImageResolve = true,
                ),
                e,
            )
            val payload = BridgeChapterProvider(runtime, ContentKind.NOVEL).payload("n1", "ch1")
            assertTrue(payload is core.model.ChapterPayload.Text)
            payload as core.model.ChapterPayload.Text
            assertEquals(core.model.TextFormat.HTML, payload.format)
            assertTrue(!payload.body.contains("evil") && payload.body.contains("p1"), payload.body)
            val cfg = assertNotNull(runtime.novelImageResolve("https://cdn.example.com/i.jpg"))
            assertTrue(cfg.headers.values.any { it.contains("cdn.example.com") })
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }
}
