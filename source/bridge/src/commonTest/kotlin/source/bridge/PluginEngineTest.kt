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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertTrue

/**
 * 插件引擎端到端（真 quickjs）：描述符探测、声明式设置、Venera 兼容 polyfill、
 * 网络参数透传、错误重放、图片加载配置、装配缓存。
 * 与 BridgeEngineTest 同样遵守 native 缺失跳过约定（androidHostTest 不打折纯 Kotlin 断言）。
 */
class PluginEngineTest {

    companion object {
        /** 合成源：覆盖 M1 冻结形状 + 本次新增能力。 */
        const val FULL_JS = """
class Full extends ComicSource {
  constructor() {
    super();
    this.name = 'Full'; this.key = 'com.example.full'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 3;
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic', 'settings', 'imageLoad', 'translation'];
    this.translation = { 'zh_CN': { 'hello': '你好' } };
    this.settings = {
      quality: { title: '画质', type: 'select',
                 options: [{ value: 'orig', text: '原图' }, { value: 'low' }], default: 'orig' },
      adult: { title: 'R18', type: 'switch', default: false }
    };
    this.explore = [{ title: '首页', type: 'multiPageComicList',
                      load: async (p) => { return { comics: [], maxPage: 1 }; } }];
    this.init = async () => { this.saveData('inited', '1'); };
    this.search = {
      optionList: [{ options: ['new-最新'] }],
      load: async (keyword, options, page) => {
        const r = await Network.get('https://cdn.example.com/s?q=' + keyword, { 'X-From': 'plugin' });
        return { comics: [{ id: 'w1', title: JSON.parse(r.body).title }], maxPage: 1 };
      }
    };
    this.comic = {
      loadInfo: async (id) => ({ title: 'W', chapters: new Map([['c1', '第一话']]) }),
      loadEp: async (w, c) => ({ images: ['https://cdn.example.com/1.jpg'] }),
      onImageLoad: (url, w, c) => ({
        url: url.replace('example.com', 'cdn.example.com'),
        method: 'GET',
        headers: { 'Cookie': 'stolen', 'User-Agent': 'evil',
                   'Referer': 'https://cdn.example.com/', 'X-Site': 'ok' },
        maxRetries: 2
      })
    };
  }
}
"""

        /** Venera 兼容形状：sendMessage 同步桥 / fetch / HtmlElement / Convert / 定时器。 */
        const val COMPAT_JS = """
class Compat extends ComicSource {
  constructor() {
    super();
    this.name = 'Compat'; this.key = 'com.example.compat'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 3;
    this.kind = ['manga']; this.domains = ['cdn.example.com'];
    this.capabilities = ['search', 'comic', 'settings', 'translation'];
    this.probe = async () => {
      sendMessage({ method: 'save_data', data_key: 'k', data: 'v' });
      const kv = sendMessage({ method: 'load_data', data_key: 'k' });
      const rejected = (() => {
        try { sendMessage({ method: 'compute' }); return 'not-rejected'; }
        catch (e) { return String(e); }
      })();
      const setting = this.loadSetting('quality');
      const logged = sendMessage({ method: 'isLogged' });
      const locale = sendMessage({ method: 'getLocale' });
      const f = await fetch('https://cdn.example.com/f');
      const fetched = await f.json();
      const h = new HtmlDocument('<a id="l" class="c" href="/x">text</a>');
      const a = h.querySelector('a');
      const html = (a ? a.attributes['href'] : '') + '|' + (a ? a.text : '') +
                   '|' + (a ? a.id : '') + '|' + (a ? a.innerHTML : '') +
                   '|' + (a ? a.classNames.join(',') : '');
      h.dispose();
      let timer = 'no';
      await setTimeout(function () { timer = 'yes'; }, 0);
      return {
        kv: kv, rejected: rejected, setting: setting, logged: logged, locale: locale,
        fetched: fetched.title, html: html, timer: timer,
        md5: Convert.md5('abc'), hex: Convert.hexEncode('ab'),
        b64: Convert.encodeBase64('ab'), uuid: Utils.createUuid().length,
        translated: this.translate('hello')
      };
    };
  }
}
"""

        const val FLAKY_JS = """
class Flaky extends ComicSource {
  constructor() {
    super();
    this.name = 'Flaky'; this.key = 'com.example.flaky'; this.version = '1.0.0';
    this.minAppVersion = '1.0.0'; this.apiVersion = 3;
    this.kind = ['manga'];
    this.capabilities = ['search'];
    this.flaky = async () => {
      const n = Number(this.loadData('n') || '0');
      this.saveData('n', String(n + 1));
      if (n === 0) throw 'Login expired';
      return { n: n };
    };
  }
}
"""
    }

    private class Bundle {
        val seenUrls = mutableListOf<String>()
        val seenHeaders = mutableMapOf<String, String>()
        val cookies = CookieStore()
        val storage = InMemoryHostStorage()

        fun hostFor(domains: Set<String> = setOf("cdn.example.com")): HostApi {
            val mock = MockEngine { request ->
                seenUrls += request.url.toString()
                request.headers.entries().forEach { (k, values) ->
                    seenHeaders[k] = values.joinToString(",")
                }
                respond(
                    content = ByteReadChannel("""{"title":"T"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
            val network: HostNetwork = KtorHostNetwork(http, cookies, domains) { 0L }
            val convert: HostConvert = CryptoHostConvert()
            val html: HostHtml = KsoupHostHtml()
            val ui: HostUi = NoopHostUi()
            val utils: HostUtils = HostUtilsImpl()
            val kv: HostStorage = storage
            val novel: HostNovel = NovelHost()
            return object : HostApi {
                override val network: HostNetwork = network
                override val html: HostHtml = html
                override val convert: HostConvert = convert
                override val ui: HostUi = ui
                override val utils: HostUtils = utils
                override val storage: HostStorage = kv
                override val novel: HostNovel = novel
            }
        }

        fun engine(key: String, domains: Set<String> = setOf("cdn.example.com")) = QuickJsEngine(
            sourceKey = key,
            host = hostFor(domains),
            cookies = cookies,
            dispatchers = DefaultSourceDispatchers(),
            evalTimeoutMs = 10_000,
            appVersion = "1.0.0",
        )

        fun manager() = SourceRuntimeManager(
            engines = QuickJsPluginEngineFactory(cookies, DefaultSourceDispatchers()),
            hosts = PluginHostProvider { spec -> hostFor(spec.domains) },
        )
    }

    private fun descriptorOf(raw: String) = SourceDescriptor.fromJson(
        raw, emptySet(), 3,
    )

    @Test
    fun descriptorProbeAndSettings() = runTest {
        val b = Bundle()
        val e = b.engine("com.example.full")
        try {
            e.load(FULL_JS)
            val d = descriptorOf(e.describe())
            assertEquals("com.example.full", d.key)
            assertEquals("Full", d.name)
            assertEquals(3, d.apiVersion)
            assertTrue(d.has(SourceCapability.SETTINGS))
            assertTrue(d.has(SourceCapability.IMAGE_LOAD))
            assertTrue(d.hasImageLoad)
            assertTrue(d.hasExplore)
            assertEquals("orig", d.setting("quality")?.default)
            assertEquals("false", d.setting("adult")?.default)
            assertEquals("low", d.setting("quality")?.options?.get(1)?.text)

            val runtime = SourceRuntime(d, e)
            runtime.updateSettings("""{"quality":"low"}""")
            val translated = runtime.translate("hello")
            assertEquals("你好", translated)
            // init() 在 load() 时执行，跨调用状态经 KV 而非 JS 全局（单发模型）。
            assertEquals("1", b.storage.get("com.example.full", "inited"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun networkHeadersAndImageConfig() = runTest {
        val b = Bundle()
        val e = b.engine("com.example.full")
        try {
            e.load(FULL_JS)
            val d = descriptorOf(e.describe())
            val runtime = SourceRuntime(d, e)
            val page = runtime.searchWorks("abc", "[\"new\"]", 1)
            assertTrue(page.contains("\"title\":\"T\""), page)
            assertTrue(b.seenUrls.any { it.contains("/s?q=abc") })
            assertEquals("plugin", b.seenHeaders["X-From"])

            val cfg = runtime.imageLoadConfig("https://example.com/1.jpg", "w1", "c1")!!
            assertEquals("https://cdn.example.com/1.jpg", cfg.url)
            assertFalse(cfg.headers.keys.any { it.equals("cookie", true) || it.equals("user-agent", true) })
            assertEquals("ok", cfg.headers["X-Site"])
            assertEquals("https://cdn.example.com/", cfg.headers["Referer"])
            val request = runtime.imageRequest(
                "https://example.com/1.jpg", "w1", "c1", setOf("cdn.example.com"),
            )
            assertEquals("https://cdn.example.com/1.jpg", request.config?.url)
            assertEquals(2, request.config?.maxRetries)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun veneraCompatPolyfill() = runTest {
        val b = Bundle()
        val e = b.engine("com.example.compat")
        try {
            e.load(COMPAT_JS)
            val d = descriptorOf(e.describe())
            val runtime = SourceRuntime(d, e)
            runtime.updateSettings("""{"quality":"low"}""")
            val raw = runtime.call("probe", "[]")
            val o = Json.parseToJsonElement(raw).jsonObject
            assertEquals("v", o["kv"]?.jsonPrimitive?.contentOrNull)
            assertTrue((o["rejected"]?.jsonPrimitive?.contentOrNull.orEmpty()).contains("not allowed"))
            assertEquals("low", o["setting"]?.jsonPrimitive?.contentOrNull)
            assertEquals(false, o["logged"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true)
            assertEquals("zh_CN", o["locale"]?.jsonPrimitive?.contentOrNull)
            assertEquals("T", o["fetched"]?.jsonPrimitive?.contentOrNull)
            assertEquals("/x|text|l|text|c", o["html"]?.jsonPrimitive?.contentOrNull)
            assertEquals("yes", o["timer"]?.jsonPrimitive?.contentOrNull)
            assertEquals("900150983cd24fb0d6963f7d28e17f72", o["md5"]?.jsonPrimitive?.contentOrNull)
            assertEquals("6162", o["hex"]?.jsonPrimitive?.contentOrNull)
            assertEquals("YWI=", o["b64"]?.jsonPrimitive?.contentOrNull)
            assertEquals(36, o["uuid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull())
            assertEquals("hello", o["translated"]?.jsonPrimitive?.contentOrNull)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun loginExpiredReplayEndToEnd() = runTest {
        val b = Bundle()
        val e = b.engine("com.example.flaky")
        try {
            e.load(FLAKY_JS)
            var relogins = 0
            val runtime = SourceRuntime(
                descriptorOf(e.describe()), e,
                onRelogin = { relogins++; true },
            )
            val raw = runtime.call("flaky", "[]")
            assertTrue(raw.contains("\"n\":1"), raw)
            assertEquals(1, relogins)
            // KV 跨调用持久化：两次求值分别计数。
            assertEquals("2", b.storage.get("com.example.flaky", "n"))
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun managerAssemblesRealRuntime() = runTest {
        val b = Bundle()
        val manager = b.manager()
        val spec = PluginRuntimeSpec(
            key = "com.example.full",
            script = FULL_JS,
            domains = setOf("cdn.example.com"),
            apiVersion = 3,
            appVersion = "1.0.0",
            capabilities = emptySet(),
        )
        try {
            val runtime = manager.acquire(spec)
            assertTrue(runtime.has(SourceCapability.SEARCH))
            assertTrue(runtime.has(SourceCapability.SETTINGS))
            val page = runtime.searchWorks("abc", "[\"new\"]", 1)
            assertTrue(page.contains("\"title\":\"T\""), page)
            assertEquals(listOf("com.example.full"), manager.cachedKeys())
            val error = assertFailsWith<SourcePluginException> {
                runtime.call("novel.loadChapter", "[\"w\",\"c\"]")
            }
            assertEquals(SourceErrorKind.CONTRACT, error.kind)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            manager.close()
        }
    }
}
