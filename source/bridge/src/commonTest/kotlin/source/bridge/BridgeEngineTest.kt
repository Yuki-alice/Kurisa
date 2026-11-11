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
import kotlin.test.assertTrue

/**
 * 引擎端到端（真 quickjs，三端可跑；合成源覆盖搜索/详情/章节/KV/Convert/Html/Cookie）。
 * 合成源写法紧贴 M1 ExampleSources（箭头函数保 this、Map 章节、text 小说体）。
 */
class BridgeEngineTest {

    companion object {
        const val MANGA_JS = """
class T extends ComicSource {
  constructor() { super(); this.key = 't'; this.name = 'T'; this.version = '1.0.0'; this.baseUrl = 'https://cdn.example.com'; }
  search = {
    optionList: [{ title: '排序', options: ['最新', '最热'] }],
    load: async (keyword, options, page) => {
      const r = await Network.get(this.baseUrl + '/s?q=' + keyword);
      const j = JSON.parse(r.body);
      return { comics: [{ id: 'w1', title: j.title, cover: '', tags: [], description: '', url: '' }], maxPage: 1 };
    }
  };
  comic = {
    loadInfo: async (id) => {
      const seen = this.loadData('seen') || '0';
      this.saveData('seen', '1');
      Network.setCookies('https://cdn.example.com/', [{ name: 's', value: '1', domain: '' }]);
      const ck = Network.getCookies('https://cdn.example.com/');
      const h = new HtmlDocument('<div><p>简介</p><img src="https://cdn.example.com/1.jpg"/></div>');
      const img = h.querySelector('img');
      const src = img ? img['attr:src'] : '';
      h.dispose();
      return { title: 'W', cover: '', description: 'D', tags: [], chapters: { 'c1': '第一话' }, img: src, ck: ck.length, seen: seen };
    },
    loadEp: async (workId, chapterId) => {
      const sig = Convert.md5('abc');
      return { images: ['https://cdn.example.com/1.jpg', 'https://cdn.example.com/2.jpg'], sig: sig };
    }
  };
}
"""
        const val NOVEL_JS = """
class N extends ComicSource {
  constructor() { super(); this.key = 'n'; this.name = 'N'; this.version = '1.0.0'; }
  novel = {
    loadInfo: async (id) => {
      return { title: 'Nov', chapters: [{ id: 'ch1', title: '第一章' }] };
    },
    loadChapter: async (workId, chapterId) => {
      return { text: '正文第一段' };
    }
  };
}
"""
        const val SPIN_JS = """
class L extends ComicSource {
  constructor() { super(); this.key = 'l'; this.name = 'L'; this.version = '1.0.0'; }
  spin = async () => { while (true) {} };
}
"""
    }

    private class Bundle(
        val seenUrls: MutableList<String> = mutableListOf(),
        val cookies: CookieStore = CookieStore(),
        val storage: HostStorage = InMemoryHostStorage(),
    ) {
        val http: SourceHttpClient
        val network: HostNetwork
        val host: HostApi

        init {
            val engine = MockEngine { request ->
                seenUrls += request.url.toString()
                respond(
                    content = ByteReadChannel("""{"title":"T"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            http = SourceHttpClient(HttpClient(engine), cookies) { 0L }
            network = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
            val convert = CryptoHostConvert()
            val html = KsoupHostHtml()
            host = object : HostApi {
                override val network: HostNetwork = this@Bundle.network
                override val html: HostHtml = html
                override val convert: HostConvert = convert
                override val ui: HostUi = NoopHostUi()
                override val utils: HostUtils = HostUtilsImpl()
                override val storage: HostStorage = this@Bundle.storage
                override val novel: HostNovel = NovelHost()
            }
        }

        fun engine(
            key: String = "t",
            evalTimeoutMs: Long = 10_000,
        ) = QuickJsEngine(
            sourceKey = key,
            host = host,
            cookies = cookies,
            dispatchers = DefaultSourceDispatchers(),
            evalTimeoutMs = evalTimeoutMs,
        )
    }

    @Test
    fun mangaEndToEnd() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(MANGA_JS)
            val opts = e.defaultSearchOptions()
            assertTrue(opts.contains("最新"), opts)
            val page = e.searchWorks("T", opts, 1)
            assertTrue(page.contains("\"title\":\"T\""), page)
            assertTrue(b.seenUrls.any { it.contains("/s?q=T") })
            val d1 = e.loadWorkDetails("w1", ContentKind.MANGA)
            assertTrue(d1.contains("\"seen\":\"0\""), d1)
            assertTrue(d1.contains("https://cdn.example.com/1.jpg"), d1)
            assertTrue(d1.contains("\"ck\":1"), d1)
            val d2 = e.loadWorkDetails("w1", ContentKind.MANGA)
            assertTrue(d2.contains("\"seen\":\"1\""), d2)
            val ep = e.loadChapterPayload("w1", "c1", ContentKind.MANGA)
            assertTrue(ep.contains("900150983cd24fb0d6963f7d28e17f72"), ep)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun novelShapes() = runTest {
        val b = Bundle()
        val e = b.engine("n")
        try {
            e.load(NOVEL_JS)
            val provider = BridgeChapterProvider(e, ContentKind.NOVEL)
            val chapters = provider.chapters("n1")
            assertEquals(1, chapters.size)
            assertEquals("ch1", chapters[0].id)
            val payload = provider.payload("n1", "ch1")
            assertTrue(payload is core.model.ChapterPayload.Text)
            assertEquals("正文第一段", (payload as core.model.ChapterPayload.Text).body)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun mangaProviderMapping() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(MANGA_JS)
            val provider = BridgeChapterProvider(e, ContentKind.MANGA)
            val chapters = provider.chapters("w1")
            assertEquals(listOf("c1"), chapters.map { it.id })
            assertEquals("第一话", chapters[0].title)
            val payload = provider.payload("w1", "c1")
            assertTrue(payload is core.model.ChapterPayload.Images)
            assertEquals(2, (payload as core.model.ChapterPayload.Images).urls.size)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun offAllowlistFailsClosed() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(MANGA_JS)
            // 白名单外：请求不出门（MockEngine 零记录），源拿到 status 0。
            val evil = """
class E extends ComicSource {
  constructor() { super(); this.key = 'e'; this.name = 'E'; this.version = '1.0.0'; }
  search = {
    optionList: [],
    load: async (k, o, p) => {
      const r = await Network.get('https://evil.com/s');
      return { comics: [], maxPage: 0, evilStatus: r.status };
    }
  };
}
"""
            e.load(evil)
            val r = e.searchWorks("x", "[]", 1)
            assertTrue(r.contains("\"evilStatus\":0"), r)
            assertTrue(b.seenUrls.none { it.contains("evil.com") })
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun spinInterrupted() = runTest {
        val b = Bundle()
        val e = b.engine("l", evalTimeoutMs = 800)
        try {
            e.load(SPIN_JS)
            assertFailsWith<SourceBridgeException> {
                e.invoke("spin", "[]")
            }
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun kvSyncBridge() {
        val storage = InMemoryHostStorage()
        storage.set("s", "k", "v")
        assertEquals("v", storage.get("s", "k"))
        assertEquals(null, storage.get("other", "k"))
        storage.delete("s", "k")
        assertEquals(null, storage.get("s", "k"))
    }
}
