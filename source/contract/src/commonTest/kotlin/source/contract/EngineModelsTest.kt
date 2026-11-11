package source.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 插件引擎契约模型（纯 Kotlin，三端一致，不依赖 quickjs native）。 */
class EngineModelsTest {

    private val probe = """
        {
          "key": "com.example.novel", "name": "N", "version": "1.2.0",
          "minAppVersion": "1.0.0", "apiVersion": 3,
          "kinds": ["manga", "novel"], "domains": ["example.com", "cdn.example.com"],
          "capabilities": ["search", "imageLoad"],
          "settings": {
            "quality": {
              "title": "画质", "type": "select",
              "options": [{"value": "orig", "text": "原图"}, {"value": "low"}],
              "default": "orig"
            },
            "adult": {"title": "R18", "type": "switch", "default": false},
            "domain": {"title": "域名", "type": "input", "default": "example.com", "validator": "^https"},
            "refresh": {"title": "刷新", "type": "callback", "hasCallback": true}
          },
          "translationLocales": ["zh_CN", "en"],
          "hasInit": true, "hasImageLoad": true, "hasSearchNext": false,
          "hasSearch": true, "hasComic": true, "hasExplore": true,
          "hasCategory": false, "hasAccount": false, "hasFavorites": false
        }
    """.trimIndent()

    @Test
    fun descriptorParsesProbe() {
        val d = SourceDescriptor.fromJson(probe)
        assertEquals("com.example.novel", d.key)
        assertEquals("1.2.0", d.version)
        assertEquals(setOf(ContentKind.MANGA, ContentKind.NOVEL), d.kinds)
        assertEquals(listOf("example.com", "cdn.example.com"), d.domains)
        assertEquals(4, d.settings.size)
        assertEquals(SettingType.SELECT, d.setting("quality")?.type)
        assertEquals("orig", d.setting("quality")?.default)
        assertEquals("low", d.setting("quality")?.options?.get(1)?.text, "缺省文本回退到 value")
        assertEquals("false", d.setting("adult")?.default)
        assertEquals("^https", d.setting("domain")?.validator)
        assertTrue(d.setting("refresh")?.hasCallback ?: false)
        assertEquals(listOf("zh_CN", "en"), d.translationLocales)
        assertTrue(d.hasInit)
        assertTrue(d.hasImageLoad)
        assertTrue(d.hasExplore)
        assertTrue(d.has(SourceCapability.SETTINGS))
        assertTrue(d.has(SourceCapability.IMAGE_LOAD))
        assertTrue(d.has(SourceCapability.EXPLORE))
        assertFalse(d.has(SourceCapability.CATEGORY))
    }

    @Test
    fun streamPlaybackFrozenAndSanitized() {
        // U6 字段冻结：序列化往返稳定，危险头与 ImageLoadConfig 同口径剥离。
        val s = StreamPlayback(
            url = "https://cdn.example.com/v.m3u8",
            streamType = StreamType.HLS,
            headers = mapOf("Cookie" to "s=1", "Referer" to "https://cdn.example.com/"),
            subtitles = listOf(StreamSubtitle("zh", "中文", "https://cdn.example.com/z.srt")),
            quality = "1080p",
        )
        val raw = ContractJson.json.encodeToString(StreamPlayback.serializer(), s)
        val back: StreamPlayback = ContractJson.json.decodeFromString(raw)
        assertEquals(s, back)
        val clean = s.sanitized()
        assertTrue(clean.headers.keys.none { it.equals("cookie", true) })
        assertEquals("https://cdn.example.com/", clean.headers["Referer"])
        assertEquals(1, clean.subtitles.size)
        // 未知流类型显式未知（调用方按 CONTRACT 拒绝，不静默降级）。
        assertNull(StreamType.of("rtmp"))
        assertEquals(StreamType.MP4, StreamType.of(" MP4 "))
    }

    @Test
    fun descriptorHonoursHeaderDeclarationAndVersion() {
        // 头块只声明 search + imageLoad：脚本探测出的 settings/explore 被收窄。
        val d = SourceDescriptor.fromJson(
            probe,
            declared = setOf(SourceCapability.SEARCH, SourceCapability.IMAGE_LOAD),
        )
        assertEquals(
            setOf(SourceCapability.SEARCH, SourceCapability.IMAGE_LOAD),
            d.capabilities,
        )
        // v2 上界：imageLoad/settings/explore 全部降级。
        val v2 = SourceDescriptor.fromJson(probe.replace("\"apiVersion\": 3", "\"apiVersion\": 2"))
        assertEquals(setOf(SourceCapability.SEARCH, SourceCapability.COMIC), v2.capabilities)
    }

    @Test
    fun descriptorIsDefensiveAgainstBadPayload() {
        val bad = SourceDescriptor.fromJson("not json at all")
        assertEquals("", bad.key)
        assertEquals("", bad.name)
        assertTrue(bad.capabilities.isEmpty())
        assertTrue(bad.settings.isEmpty())
        assertNull(bad.setting("anything"))
        // 缺字段时按 manga 兜底（Venera 老源无 kind 字段）。
        assertEquals(setOf(ContentKind.MANGA), SourceDescriptor.fromJson("{}").kinds)
    }

    @Test
    fun imageConfigDropsForbiddenHeaders() {
        val sanitized = ImageLoadConfig(
            url = "https://cdn.example.com/1.jpg",
            method = "post",
            headers = mapOf(
                "Cookie" to "cf_clearance=stolen",
                "User-Agent" to "evil-ua",
                "Host" to "evil.com",
                "X-Site" to "ok",
            ),
            referer = "https://example.com/",
            maxRetries = 99,
        ).sanitized()
        assertEquals("POST", sanitized.method)
        assertEquals(mapOf("X-Site" to "ok", "Referer" to "https://example.com/"), sanitized.headers)
        assertEquals(3, sanitized.maxRetries)
        // 不允许的 method 收敛为 GET（当前宿主出口只有 GET/POST）。
        assertEquals("GET", ImageLoadConfig(method = "TRACE").sanitized().method)
    }
}
