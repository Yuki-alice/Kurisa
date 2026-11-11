package source.bridge

import core.model.TextFormat
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind
import source.contract.ContractPolicy
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
 * v4 地基覆盖（全网站类型合成源，全部本地 Mock，无外部站点）。
 * 类型矩阵：JSON-API / 服务端 HTML / 页码分页 / 游标分页 / 防盗链图片 /
 * HMAC 签名 / Cloudflare 分类 / 登录过期重放 / 声明式设置 / 小说 html/plain/markdown。
 */
class V4HelpersTest {

    companion object {
        const val V4_JS = """
class V extends ComicSource {
  constructor() {
    super();
    this.name = 'V'; this.key = 'com.example.v4'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['manga', 'novel']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic', 'novel', 'novelChapter', 'htmlHelper', 'settings'];
    this.settings = { quality: { title: '画质', type: 'select', options: [{ value: 'orig' }], default: 'orig' } };
    this.search = {
      optionList: [{ options: ['new-最新'] }],
      load: async (keyword, options, page) => {
        if (keyword === 'page-test') {
          return await this.search.page(keyword, options, page);
        }
        const r = await Network.get('https://cdn.example.com/s?q=' + keyword);
        const j = JSON.parse(r.body);
        return { works: [{ id: 'w1', title: j.title, kind: 'manga' }], maxPage: 5 };
      },
      loadNext: async (keyword, options, next) => {
        return { works: [{ id: 'w-cursor', title: 'cursor-' + next }], next: null };
      },
      page: async (keyword, options, pageOrNext) => {
        if (typeof pageOrNext === 'number') {
          return await this.search.load(keyword, options, pageOrNext);
        }
        return await this.search.loadNext(keyword, options, pageOrNext);
      }
    };
    this.comic = {
      loadInfo: async (id) => ({ title: 'W', chapters: [{ id: 'c1', title: '第一话' }] }),
      loadEp: async (w, c) => ({ images: ['https://cdn.example.com/1.jpg'] }),
      onImageLoad: (url, w, c) => ({ url: url, headers: { Referer: 'https://cdn.example.com/' }, maxRetries: 2 })
    };
    this.novel = {
      loadInfo: async (id) => ({ title: 'Nov', chapters: [{ id: 'ch1', title: '第一章' }] }),
      loadChapter: async (w, c) => {
        if (c === 'plain') return { text: 'plain body', format: 'plain' };
        if (c === 'md') return { content: '# t', format: 'markdown' };
        return { content: '<p>hi<img src="https://cdn.example.com/i.jpg"/></p>', format: 'html' };
      },
      onImageResolve: (url) => ({ url: url, headers: { Referer: 'https://cdn.example.com/' } })
    };
    this.probe = async () => {
      const list = Html.list('<div><a href="/x">a</a><a href="/y">b</a></div>', 'a', (el) => el.text);
      const t = Html.text('<div><p>hello</p></div>', 'p');
      const a = Html.attr('<a href="/x">a</a>', 'a', 'href');
      const missing = Html.attr('<div></div>', 'a', 'href');
      const cleaned = cleanNovelHtml('<h1>标题</h1><script>evil()</script><p>第一段</p>');
      const sig = Convert.hmacString('key', 'value', 'sha256');
      const s1 = Convert.sha1('abc');
      const s512 = Convert.sha512('abc');
      return { list: list, t: t, a: a, missing: missing, cleaned: cleaned, sig: sig, s1: s1, s512: s512 };
    };
    this.boomLogin = async () => { throwLoginExpired('need relogin'); };
    this.boomRate = async () => { throwRateLimited('slow down'); };
    this.boomNotFound = async () => { throwNotFound('gone'); };
  }
}
"""
        const val FULL_MATRIX_JS = """
class M extends ComicSource {
  constructor() {
    super();
    this.name = 'M'; this.key = 'com.example.matrix'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['manga', 'novel']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic', 'novel', 'explore', 'category', 'account', 'favorites', 'settings', 'translation', 'imageLoad'];
    this.translation = { 'zh_CN': { 'hello': '你好' } };
    this.settings = { quality: { title: '画质', type: 'select', options: [{ value: 'orig' }], default: 'orig' } };
    this.search = { load: async (k, o, p) => ({ works: [] }) };
    this.comic = {
      loadInfo: async (id) => ({ title: 'W', chapters: [{ id: 'c1', title: 't' }] }),
      loadEp: async (w, c) => ({ images: ['https://cdn.example.com/1.jpg'] }),
      onImageLoad: (url, w, c) => ({ url: url })
    };
    this.novel = {
      loadInfo: async (id) => ({ title: 'N', chapters: [{ id: 'ch1', title: 't' }] }),
      loadChapter: async (w, c) => ({ content: 'body', format: 'plain' })
    };
    this.explore = [{ title: 'h', type: 'multiPageComicList', load: async (p) => ({ comics: [] }) }];
    this.category = { title: 'c' };
    this.categoryComics = { load: async (c, p, o, pg) => ({ works: [] }) };
    this.account = { loginWithCookies: { fields: ['a'] } };
    this.favorites = { addOrDel: async () => ({}) };
  }
}
"""
        const val TRUNC_JS = """
class Tr extends ComicSource {
  constructor() {
    super();
    this.name = 'Tr'; this.key = 'com.example.trunc'; this.version = '1.0.0';
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
    this.search = {
      load: async (keyword, options, page) => {
        const r = await Network.get('https://cdn.example.com/big');
        return { works: [], n: r.body.length };
      }
    };
  }
}
"""
        const val INIT_COUNT_JS = """
class C extends ComicSource {
  constructor() {
    super();
    this.name = 'C'; this.key = 'com.example.initc'; this.version = '1.0.0';
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
  }
  init = async () => {
    const n = Number(this.loadData('initN') || '0');
    this.saveData('initN', String(n + 1));
  };
  probe = async () => { return this.loadData('initN') || '0'; };
}
"""
        const val PAGE_ONLY_JS = """
class P extends ComicSource {
  constructor() {
    super();
    this.name = 'P'; this.key = 'com.example.pageonly'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 4;
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic'];
    this.search = {
      optionList: [{ options: ['new-最新'] }],
      page: async (keyword, options, pageOrNext) => {
        if (typeof pageOrNext === 'number') {
          return { works: [{ id: 'p' + pageOrNext, title: 'T' }], maxPage: 9 };
        }
        return { works: [{ id: 'n-' + pageOrNext, title: 'C' }], next: null };
      }
    };
    this.comic = {
      loadInfo: async (id) => ({ title: 'W', chapters: [] }),
      loadEp: async (w, c) => ({ images: [] })
    };
  }
}
"""
    }

    private class Bundle {
        val seenUrls = mutableListOf<String>()
        val cookies = CookieStore()
        val storage: HostStorage = InMemoryHostStorage()
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
            host = object : HostApi {
                override val network: HostNetwork = this@Bundle.network
                override val html: HostHtml = KsoupHostHtml()
                override val convert: HostConvert = CryptoHostConvert()
                override val ui: HostUi = NoopHostUi()
                override val utils: HostUtils = HostUtilsImpl()
                override val storage: HostStorage = this@Bundle.storage
                override val novel: HostNovel = NovelHost()
            }
        }

        fun engine() = QuickJsEngine(
            sourceKey = "com.example.v4",
            host = host,
            cookies = cookies,
            dispatchers = DefaultSourceDispatchers(),
        )
    }

    @Test
    fun v4ContractAcceptsApi4() {
        assertTrue(4 in ContractPolicy.SUPPORTED_API_VERSIONS)
        assertTrue(SourceCapability.HTML_HELPER in ContractPolicy.capabilitiesFor(4))
        assertTrue(SourceCapability.NOVEL_CHAPTER in ContractPolicy.capabilitiesFor(4))
        assertTrue(SourceCapability.CRYPTO_AES in ContractPolicy.capabilitiesFor(4))
    }

    @Test
    fun describeDetectsNovelDouble() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val raw = e.describe()
            val d = SourceDescriptor.fromJson(raw, apiVersionOverride = 4)
            assertEquals(4, d.apiVersion)
            assertTrue(d.hasNovelLoadInfo, raw)
            assertTrue(d.hasNovelLoadChapter, raw)
            assertTrue(d.has(SourceCapability.NOVEL_CHAPTER), d.capabilities.toString())
            // 单边缺失不授予 novelChapter。
            val half = SourceDescriptor.fromJson(
                """{"key":"k","name":"n","hasNovelLoadInfo":true}""",
                apiVersionOverride = 4,
            )
            assertTrue(SourceCapability.NOVEL_CHAPTER !in half.capabilities)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun dxHelpersWorkEndToEnd() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val raw = e.invoke("probe", "[]")
            val o = Json.parseToJsonElement(raw).jsonObject
            val list = o["list"]?.toString() ?: ""
            assertTrue(list.contains("a") && list.contains("b"), raw)
            assertEquals("hello", o["t"]?.jsonPrimitive?.contentOrNull)
            assertEquals("/x", o["a"]?.jsonPrimitive?.contentOrNull)
            assertEquals(null, o["missing"]?.jsonPrimitive?.contentOrNull)
            val cleaned = o["cleaned"]?.jsonPrimitive?.contentOrNull.orEmpty()
            assertTrue(cleaned.contains("标题") && cleaned.contains("第一段"), cleaned)
            assertTrue(!cleaned.contains("evil"), cleaned)
            assertEquals(64, o["sig"]?.jsonPrimitive?.contentOrNull?.length)
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", o["s1"]?.jsonPrimitive?.contentOrNull)
            assertTrue(o["s512"]?.jsonPrimitive?.contentOrNull?.length == 128, raw)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun throwHelpersClassify() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val runtime = SourceRuntime(
                SourceDescriptor(
                    key = "com.example.v4", name = "V", version = "1.0.0",
                    kinds = setOf(ContentKind.MANGA, ContentKind.NOVEL),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(SourceCapability.SEARCH, SourceCapability.COMIC, SourceCapability.NOVEL),
                ),
                e,
            )
            val login = assertFailsWith<SourcePluginException> { runtime.invoke("boomLogin", "[]") }
            assertEquals(SourceErrorKind.LOGIN_EXPIRED, login.kind)
            val rate = assertFailsWith<SourcePluginException> { runtime.invoke("boomRate", "[]") }
            assertEquals(SourceErrorKind.RATE_LIMITED, rate.kind)
            val nf = assertFailsWith<SourcePluginException> { runtime.invoke("boomNotFound", "[]") }
            assertEquals(SourceErrorKind.NOT_FOUND, nf.kind)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun paginationPageAndCursor() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            // 页码路径。
            val page = e.invoke("search.page", """["k", [], 2]""")
            assertTrue(page.contains("w1"), page)
            // 游标路径。
            val next = e.invoke("search.page", """["k", [], "cursor-1"]""")
            assertTrue(next.contains("cursor-cursor-1"), next)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun novelFormatIsPreserved() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val providerHtml = BridgeChapterProvider(e, ContentKind.NOVEL)
            // 注意：BridgeChapterProvider 直接调 loadChapterPayload，需要先 load。
            // 这里用通用 invoke 校验原始 JSON 的 format 必透出。
            val html = e.invoke("novel.loadChapter", """["w","ch-html"]""")
            assertTrue(html.contains("\"format\":\"html\"") || html.contains("\"format\": \"html\"") || html.contains("html"), html)
            val plain = e.invoke("novel.loadChapter", """["w","plain"]""")
            assertTrue(plain.contains("plain"), plain)
            val md = e.invoke("novel.loadChapter", """["w","md"]""")
            assertTrue(md.contains("markdown"), md)
            // provider 层映射到 TextFormat。
            val payloadHtml = providerHtml.payload("w", "ch-html")
            assertTrue(payloadHtml is core.model.ChapterPayload.Text)
            assertEquals(TextFormat.HTML, (payloadHtml as core.model.ChapterPayload.Text).format)
            assertEquals(TextFormat.PLAIN, (providerHtml.payload("w", "plain") as core.model.ChapterPayload.Text).format)
            assertEquals(TextFormat.MARKDOWN, (providerHtml.payload("w", "md") as core.model.ChapterPayload.Text).format)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun strictV4RejectsUnreadyCryptoWithContract() = runTest {
        val b = Bundle()
        val strict = QuickJsEngine(
            sourceKey = "com.example.v4",
            host = b.host,
            cookies = b.cookies,
            dispatchers = DefaultSourceDispatchers(),
            strictV4 = true,
        )
        try {
            strict.load(V4_JS)
            // 经同步桥直接打 Convert，避免 invoke 的 __source 前缀干扰。
            val aes = assertFailsWith<SourceBridgeException> {
                strict.callSync("convert", """{"op":"decryptAesEcb","value":"a","key":"k"}""")
            }
            assertTrue(aes.message!!.contains("契约"), aes.message)
            val gbk = assertFailsWith<SourceBridgeException> {
                strict.callSync("convert", """{"op":"decodeGbk","value":"abc"}""")
            }
            assertTrue(gbk.message!!.contains("契约"), gbk.message)
            val hmac = assertFailsWith<SourceBridgeException> {
                strict.callSync("convert", """{"op":"hmacString","key":"k","value":"v","hash":"md5"}""")
            }
            assertTrue(hmac.message!!.contains("契约"), hmac.message)
            // sha256 在严格模式下仍可用。
            assertEquals(64, strict.callSync("convert", """{"op":"hmacString","key":"k","value":"v","hash":"sha256"}""").length)
            // AES-ECB 有效向量在严格模式下解密成功（U1 转正）。
            val keyB64 = "MDEyMzQ1Njc4OWFiY2RlZg=="
            val plainBytes = "chapter-secret".toByteArray()
            val keyBytes = Base64Codec.decode(keyB64)!!
            val cipherB64 = Base64Codec.encode(
                korlibs.crypto.AES.encryptAesEcb(
                    plainBytes, keyBytes, korlibs.crypto.CipherPadding.PKCS7Padding,
                ),
            )
            assertEquals(
                "chapter-secret",
                strict.callSync("convert", """{"op":"decryptAesEcb","value":"$cipherB64","key":"$keyB64"}"""),
            )
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun lenientShimKeepsShapeStubsForCompat() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            // 垫片为保 34 源兼容：AES 回空串，GBK 回输入本身，非 sha256 hmac 回空串。
            assertEquals("", e.callSync("convert", """{"op":"decryptAesEcb","value":"a","key":"k"}"""))
            assertEquals("abc", e.callSync("convert", """{"op":"decodeGbk","value":"abc"}"""))
            assertEquals("", e.callSync("convert", """{"op":"hmacString","key":"k","value":"v","hash":"md5"}"""))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun nativeFactoryAcquiresV4EndToEnd() = runTest {
        val b = Bundle()
        val cookies = b.cookies
        val legacy = QuickJsPluginEngineFactory(cookies)
        val native = KurisaNativePluginEngineFactory(cookies)
        val engines = VersionedPluginEngineFactory(legacy, native)
        val manager = SourceRuntimeManager(
            engines = engines,
            hosts = PluginHostProvider { b.host },
        )
        try {
            val runtime = manager.acquire(
                PluginRuntimeSpec(
                    key = "com.example.v4",
                    script = V4_JS,
                    domains = setOf("cdn.example.com"),
                    apiVersion = 4,
                    appVersion = "1.0.0",
                ),
            )
            assertEquals(4, runtime.descriptor.apiVersion)
            assertTrue(runtime.descriptor.hasNovelLoadInfo)
            assertTrue(runtime.descriptor.hasNovelLoadChapter)
            // v4 主链路可用。
            val page = runtime.searchWorks("k", "[]", 1)
            assertTrue(page.contains("w1"), page)
            val payload = BridgeChapterProvider(runtime, ContentKind.NOVEL).payload("w", "plain")
            assertTrue(payload is core.model.ChapterPayload.Text)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun fullCapabilityMatrixDetected() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(FULL_MATRIX_JS)
            val raw = e.describe()
            val d = SourceDescriptor.fromJson(raw, apiVersionOverride = 4)
            assertTrue(d.has(SourceCapability.SEARCH))
            assertTrue(d.has(SourceCapability.COMIC))
            assertTrue(d.has(SourceCapability.NOVEL))
            assertTrue(d.has(SourceCapability.EXPLORE))
            assertTrue(d.has(SourceCapability.CATEGORY))
            assertTrue(d.has(SourceCapability.ACCOUNT))
            assertTrue(d.has(SourceCapability.FAVORITES))
            assertTrue(d.has(SourceCapability.SETTINGS))
            assertTrue(d.has(SourceCapability.TRANSLATION))
            assertTrue(d.has(SourceCapability.IMAGE_LOAD))
            assertTrue(d.hasNovelLoadInfo)
            assertTrue(d.hasNovelLoadChapter)
            // 未声明能力门禁：favorites 未在下面 runtime 授权时调用应 CONTRACT。
            val gated = SourceRuntime(
                d.copy(capabilities = setOf(SourceCapability.SEARCH)),
                e,
            )
            val err = assertFailsWith<SourcePluginException> { gated.invoke("favorites.addOrDel", "[]") }
            assertEquals(SourceErrorKind.CONTRACT, err.kind)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun runtimeSearchPageRoutes() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val runtime = SourceRuntime(
                SourceDescriptor(
                    key = "com.example.v4", name = "V", version = "1.0.0",
                    kinds = setOf(ContentKind.MANGA),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(SourceCapability.SEARCH, SourceCapability.COMIC),
                    hasSearchLoad = true,
                    hasSearchNext = true,
                ),
                e,
            )
            // 无 next 走页码。
            assertTrue(runtime.searchPage("k", "[]", page = 2).contains("w1"))
            // 有 next 走游标。
            assertTrue(runtime.searchPage("k", "[]", next = "c1").contains("cursor-c1"))
            // 缺游标能力时抛 CONTRACT。
            val noNext = SourceRuntime(
                SourceDescriptor(
                    key = "k", name = "n", version = "1.0.0",
                    kinds = setOf(ContentKind.MANGA),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(SourceCapability.SEARCH),
                    hasSearchLoad = true,
                    hasSearchNext = false,
                ),
                e,
            )
            val err = assertFailsWith<SourcePluginException> { noNext.searchPage("k", "[]", next = "c1") }
            assertEquals(SourceErrorKind.CONTRACT, err.kind)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun novelImageResolveSanitized() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val runtime = SourceRuntime(
                SourceDescriptor(
                    key = "com.example.v4", name = "V", version = "1.0.0",
                    kinds = setOf(ContentKind.NOVEL),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(SourceCapability.NOVEL),
                    hasNovelLoadInfo = true,
                    hasNovelLoadChapter = true,
                    hasNovelImageResolve = true,
                ),
                e,
            )
            val cfg = runtime.novelImageResolve("https://cdn.example.com/i.jpg")
            assertNotNull(cfg)
            assertEquals("https://cdn.example.com/i.jpg", cfg.url)
            // 未声明时返回 null，不触发脚本。
            val absent = SourceRuntime(
                SourceDescriptor(
                    key = "k", name = "n", version = "1.0.0",
                    kinds = setOf(ContentKind.NOVEL),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(SourceCapability.NOVEL),
                ),
                e,
            )
            assertEquals(null, absent.novelImageResolve("https://cdn.example.com/i.jpg"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun errorTaxonomyAndGates() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val runtime = SourceRuntime(
                SourceDescriptor(
                    key = "com.example.v4", name = "V", version = "1.0.0",
                    kinds = setOf(ContentKind.MANGA, ContentKind.NOVEL),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(SourceCapability.SEARCH, SourceCapability.COMIC, SourceCapability.NOVEL),
                ),
                e,
            )
            // 未声明 explore 调用应 CONTRACT。
            val denied = assertFailsWith<SourcePluginException> { runtime.invoke("explore.load", """[1]""") }
            assertEquals(SourceErrorKind.CONTRACT, denied.kind)
            // 翻译未声明时回 key 本身。
            assertEquals("hello", runtime.translate("hello"))
            // 默认搜索选项不崩。
            assertTrue(runtime.defaultSearchOptions().contains("new"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun oversizedResponseTruncatedAndLoggedWithoutBody() = runTest {
        val big = "x".repeat(QuickJsEngine.MAX_BODY_CHARS + 1)
        val logs = mutableListOf<String>()
        val cookies = CookieStore()
        val mock = MockEngine {
            respond(
                content = ByteReadChannel(big),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val host = object : HostApi {
            override val network: HostNetwork = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
            override val html: HostHtml = KsoupHostHtml()
            override val convert: HostConvert = CryptoHostConvert()
            override val ui: HostUi = NoopHostUi()
            override val utils: HostUtils = HostUtilsImpl(logSink = logs::add)
            override val storage: HostStorage = InMemoryHostStorage()
            override val novel: HostNovel = NovelHost()
        }
        val e = QuickJsEngine(
            sourceKey = "com.example.trunc",
            host = host,
            cookies = cookies,
            dispatchers = DefaultSourceDispatchers(),
        )
        try {
            e.load(TRUNC_JS)
            val page = e.searchWorks("k", "[]", 1)
            // 超限正文被截断到上限（U2），不静默。
            assertTrue(page.contains(QuickJsEngine.MAX_BODY_CHARS.toString()), page)
            // 脱敏日志：有源 key 与长度，无正文/URL 泄露。
            assertTrue(logs.any { it.contains("截断") && it.contains("com.example.trunc") }, logs.toString())
            assertTrue(logs.none { "x".repeat(100) in it })
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun initRunsPerEvaluationSingleShot() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            // U3 决议：单发每次求值重建全新 context，init 在本次上下文重跑；
            // KV 是唯一跨调用状态通道（load→1、invoke→2/3）。
            e.load(INIT_COUNT_JS)
            assertEquals("\"2\"", e.invoke("probe", "[]"))
            assertEquals("\"3\"", e.invoke("probe", "[]"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun pageOnlySourceRoutedViaPage() = runTest {
        val b = Bundle()
        val e = b.engine()
        try {
            e.load(PAGE_ONLY_JS)
            val raw = e.describe()
            val d = SourceDescriptor.fromJson(raw, apiVersionOverride = 4)
            assertTrue(d.hasSearchPage, raw)
            assertFalse(d.hasSearchLoad, raw)
            assertFalse(d.hasSearchNext, raw)
            val runtime = SourceRuntime(d, e)
            // 无 load/loadNext 也不再抛 CONTRACT，一律走 page。
            assertTrue(runtime.searchWorks("k", "[]", 2).contains("p2"))
            assertTrue(runtime.searchNext("k", "[]", "c1").contains("n-c1"))
            assertTrue(runtime.searchPage("k", "[]", page = 5).contains("p5"))
            assertTrue(runtime.searchPage("k", "[]", next = "c9").contains("n-c9"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }

    @Test
    fun videoAudioKindsFailClosed() = runTest {        val b = Bundle()
        val e = b.engine()
        try {
            e.load(V4_JS)
            val runtime = SourceRuntime(
                SourceDescriptor(
                    key = "com.example.v4", name = "V", version = "1.0.0",
                    kinds = setOf(ContentKind.VIDEO, ContentKind.AUDIO),
                    domains = listOf("cdn.example.com"),
                    capabilities = setOf(SourceCapability.SEARCH, SourceCapability.VIDEO, SourceCapability.AUDIO),
                ),
                e,
            )
            val details = assertFailsWith<SourcePluginException> {
                runtime.loadWorkDetails("w", ContentKind.VIDEO)
            }
            assertEquals(SourceErrorKind.CONTRACT, details.kind)
            assertTrue(details.message!!.contains("deferred"), details.message)
            val payload = assertFailsWith<SourcePluginException> {
                runtime.loadChapterPayload("w", "c", ContentKind.AUDIO)
            }
            assertEquals(SourceErrorKind.CONTRACT, payload.kind)
            // 描述符可解析 video/audio kind（扩展点先行，仓库索引暂不接纳）。
            val d = SourceDescriptor.fromJson(
                """{"key":"k","name":"n","kinds":["video","audio"]}""",
                apiVersionOverride = 4,
            )
            assertTrue(ContentKind.VIDEO in d.kinds)
            assertTrue(ContentKind.AUDIO in d.kinds)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        }
    }
}
