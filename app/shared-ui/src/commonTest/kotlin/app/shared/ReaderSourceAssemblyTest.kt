package app.shared

import core.model.SourceRecord
import kotlinx.coroutines.test.runTest
import source.bridge.SourceCalls
import source.contract.ContentKind
import source.contract.SourceScriptHeader
import source.registry.SourceInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RegistryChapterProvider 单测（commonTest，无 DB/Native 依赖）。
 *
 * 覆盖：
 * - 无源时退化为 Noop 语义（空目录 + payload 抛错 + resolvePage 直通）；
 * - 有源时委托 Bridge（章节映射 + onImageLoad 改写）；
 * - domains 为 enabled 源并集。
 */
class ReaderSourceAssemblyTest {

    private fun record(
        key: String = "com.example.a",
        kinds: String = "[\"manga\"]",
        domains: String = "[\"example.com\"]",
        caps: String = "[\"search\"]",
        path: String = "/tmp/a.js",
    ) = SourceRecord(
        key = key, name = "A", version = "1.0.0", apiVersion = 2,
        kindsJson = kinds, capabilitiesJson = caps, domainsJson = domains,
        installPath = path, sha256 = "x", signatureOk = false,
        enabled = true, settingsJson = "{}", updatedAtEpochMs = 0L,
    )

    private class FakeLookup(val records: List<SourceRecord>) : ActiveSourceLookup {
        override fun enabledKeys(): List<String> =
            records.filter { it.enabled }.map { it.key }
        override fun allKeys(): List<String> = records.map { it.key }
        override fun get(key: String): SourceRecord? = records.firstOrNull { it.key == key }
    }

    private class FakeCalls(
        var details: String = """{"title":"W","chapters":{"c1":"第一话"}}""",
        var chapter: String = """{"images":["u1"]}""",
        var imageRewrite: String? = null,
    ) : SourceCalls {
        var lastSearchKind: ContentKind? = null
        override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int) = ""
        override suspend fun defaultSearchOptions() = "[]"
        override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String {
            lastSearchKind = kind
            return details
        }
        override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind) = chapter
        override suspend fun invoke(path: String, argsJson: String) = ""
        override suspend fun imageLoadConfig(url: String, workId: String, chapterId: String) =
            imageRewrite?.let {
                source.contract.ImageLoadConfig(url = it)
            }
    }

    @Test
    fun noSourceDegradesToNoop() = runTest {
        val p = RegistryChapterProvider(
            lookup = FakeLookup(emptyList()),
            acquire = { error("不应装配引擎") },
            loadScript = { error("不应读脚本") },
        )
        assertEquals(emptyList(), p.chapters("w"))
        assertFailsWith<IllegalStateException> { p.payload("w", "c") }
        val req = p.resolvePage("w", "c", "https://example.com/a.jpg")
        assertEquals("https://example.com/a.jpg", req.effectiveUrl)
        assertEquals(emptySet(), p.currentDomains())
    }

    @Test
    fun delegatesToBridgeWithKindAndImageRewrite() = runTest {
        val calls = FakeCalls(imageRewrite = "https://cdn.example.com/r.jpg")
        var loadedPath: String? = null
        val p = RegistryChapterProvider(
            lookup = FakeLookup(listOf(record())),
            acquire = { spec ->
                assertEquals("com.example.a", spec.key)
                assertEquals(setOf("example.com"), spec.domains)
                calls
            },
            loadScript = { path ->
                loadedPath = path
                "// fake source"
            },
        )
        val chapters = p.chapters("w")
        assertEquals(listOf("c1"), chapters.map { it.id })
        assertEquals(ContentKind.MANGA, calls.lastSearchKind)
        assertEquals("/tmp/a.js", loadedPath)

        val req = p.resolvePage("w", "c1", "https://example.com/o.jpg")
        assertEquals("https://cdn.example.com/r.jpg", req.effectiveUrl)
    }

    @Test
    fun novelKindRouted() = runTest {
        val calls = FakeCalls(
            details = """{"title":"N","chapters":[{"id":"n1","title":"楔子"}]}""",
            chapter = """{"content":"正文","format":"plain"}""",
        )
        val p = RegistryChapterProvider(
            lookup = FakeLookup(
                listOf(record(kinds = "[\"novel\"]")),
            ),
            acquire = { calls },
            loadScript = { "// novel" },
        )
        assertEquals("n1", p.chapters("w").single().id)
        val body = (p.payload("w", "n1") as core.model.ChapterPayload.Text).body
        assertEquals("正文", body)
    }

    @Test
    fun domainsUnionAcrossSources() {
        val p = RegistryChapterProvider(
            lookup = FakeLookup(
                listOf(
                    record(key = "a", domains = "[\"a.com\"]"),
                    record(key = "b", domains = "[\"b.com\",\"a.com\"]"),
                ),
            ),
            acquire = { error("x") },
            loadScript = { "" },
        )
        assertEquals(setOf("a.com", "b.com"), p.currentDomains())
    }

    @Test
    fun parseHelpersTolerant() {
        assertEquals(emptyList(), parseStringList("not json"))
        assertEquals(emptyList(), parseKinds("[\"unknown\"]"))
        assertTrue(parseKinds("[\"manga\",\"novel\"]").contains(ContentKind.NOVEL))
    }

    @Test
    fun detectFormatPrefersRulesSuffixAndContent() {
        assertEquals("rules/v1", detectFormat("s/x.rules.json", "class T extends ComicSource {}"))
        assertEquals(
            "rules/v1",
            detectFormat("s/x.js", """{"format": "rules/v1", "key": "a"}"""),
        )
        assertEquals("js/v1", detectFormat("s/x.js", "class T extends ComicSource {}"))
    }

    @Test
    fun disabledSourceExcludedFromProvider() = runTest {
        val p = RegistryChapterProvider(
            lookup = FakeLookup(
                listOf(record().copy(enabled = false)),
            ),
            acquire = { error("不应装配已禁用源") },
            loadScript = { error("不应读脚本") },
        )
        // 全部禁用 = 视同无源（Noop 语义），不抛装配错。
        assertEquals(emptyList(), p.chapters("w"))
        assertEquals(emptySet(), p.currentDomains())
        assertFailsWith<IllegalStateException> { p.payload("w", "c") }
    }

    @Test
    fun routesToMappedSourceWithFallback() = runTest {
        val usedKeys = mutableListOf<String>()
        val callsA = FakeCalls(details = """{"title":"A","chapters":{"a1":"A1"}}""")
        val callsB = FakeCalls(details = """{"title":"B","chapters":{"b1":"B1"}}""")
        val p = RegistryChapterProvider(
            lookup = FakeLookup(
                listOf(
                    record(key = "com.example.a", path = "/tmp/a.js"),
                    record(key = "com.example.b", path = "/tmp/b.js"),
                ),
            ),
            acquire = { spec ->
                usedKeys += spec.key
                if (spec.key == "com.example.b") callsB else callsA
            },
            loadScript = { "// fake" },
            workSourceKey = { workId -> if (workId == "w-b") "com.example.b" else null },
        )
        // 映射命中走作品源。
        assertEquals(listOf("b1"), p.chapters("w-b").map { it.id })
        // 无映射回退首个 enabled 源。
        assertEquals(listOf("a1"), p.chapters("w-other").map { it.id })
        assertEquals(listOf("com.example.b", "com.example.a"), usedKeys)
    }

    @Test
    fun mappedDisabledOrUnknownFallsBack() = runTest {
        val callsA = FakeCalls(details = """{"title":"A","chapters":{"a1":"A1"}}""")
        val p = RegistryChapterProvider(
            lookup = FakeLookup(
                listOf(
                    record(key = "com.example.a", path = "/tmp/a.js"),
                    record(key = "com.example.off", path = "/tmp/off.js").copy(enabled = false),
                ),
            ),
            acquire = { callsA },
            loadScript = { "// fake" },
            workSourceKey = { workId ->
                when (workId) {
                    "w-off" -> "com.example.off"
                    "w-ghost" -> "com.example.ghost"
                    else -> null
                }
            },
        )
        // 被禁用 / 未知 key 一律回退首个 enabled 源，不抛装配错。
        assertEquals(listOf("a1"), p.chapters("w-off").map { it.id })
        assertEquals(listOf("a1"), p.chapters("w-ghost").map { it.id })
    }

    @Test
    fun preferredSourceWinsOverMappingAndOrder() = runTest {
        val usedKeys = mutableListOf<String>()
        val callsA = FakeCalls(details = """{"title":"A","chapters":{"a1":"A1"}}""")
        val callsB = FakeCalls(details = """{"title":"B","chapters":{"b1":"B1"}}""")
        val lookup = FakeLookup(
            listOf(
                record(key = "com.example.a", path = "/tmp/a.js"),
                record(key = "com.example.b", path = "/tmp/b.js"),
            ),
        )
        val p = RegistryChapterProvider(
            lookup = lookup,
            acquire = { spec ->
                usedKeys += spec.key
                if (spec.key == "com.example.b") callsB else callsA
            },
            loadScript = { "// fake" },
            workSourceKey = { "com.example.a" },
            // 路由指定 b：必须压过 workSourceKey 映射与「首个 enabled 源」两条回退。
            preferredSourceKey = "com.example.b",
        )
        assertEquals(listOf("b1"), p.chapters("w").map { it.id })
        assertEquals(listOf("com.example.b"), usedKeys)
    }

    @Test
    fun preferredDisabledFallsBackToMapping() = runTest {
        val usedKeys = mutableListOf<String>()
        val callsA = FakeCalls(details = """{"title":"A","chapters":{"a1":"A1"}}""")
        val p = RegistryChapterProvider(
            lookup = FakeLookup(
                listOf(
                    record(key = "com.example.a", path = "/tmp/a.js"),
                    record(key = "com.example.off", path = "/tmp/off.js").copy(enabled = false),
                ),
            ),
            acquire = { spec -> usedKeys += spec.key; callsA },
            loadScript = { "// fake" },
            preferredSourceKey = "com.example.off",
        )
        assertEquals(listOf("a1"), p.chapters("w").map { it.id })
        assertEquals(listOf("com.example.a"), usedKeys)
    }

    @Test
    fun recordToUiItemMapping() {
        val item = record().copy(signatureOk = true).toUiItem()
        assertEquals("com.example.a", item.key)
        assertEquals("A", item.name)
        assertEquals(true, item.enabled)
        assertEquals("manga", item.kindsLabel)
        assertEquals("example.com", item.domainsLabel)
        assertEquals("已签名", item.verifiedLabel)

        val unverified = record().copy(signatureOk = false).toUiItem()
        assertTrue(unverified.verifiedLabel.contains("未验证"))

        val multi = record(kinds = "[\"manga\",\"novel\"]", domains = "[\"a.com\",\"b.com\"]").toUiItem()
        assertEquals("manga · novel", multi.kindsLabel)
        assertEquals("a.com, b.com", multi.domainsLabel)
    }

    private fun script(
        key: String = "com.example.t",
        version: String = "1.0.0",
    ) = """
        // ==SourceScript==
        // @name        T
        // @key         $key
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
    fun previewInstallOkWithoutIndex() {
        // 安装编排的纯映射部分（SourceInstaller.plan → UI）；有 DB 的整链路见 SourceCatalogTest：
        val ui = SourceInstaller.plan(script()).toPreviewUi()
        assertEquals("T", ui.name)
        assertEquals("com.example.t", ui.key)
        assertEquals("1.0.0", ui.version)
        assertEquals("manga", ui.kindsLabel)
        assertEquals("example.com", ui.domainsLabel)
        assertEquals("search", ui.capabilitiesLabel)
        // 无索引条目 = 未验证，要求二次确认。
        assertTrue(ui.requiresDoubleConfirm)
        assertEquals(null, ui.error)
        assertEquals("新装", ui.updateLabel)
    }

    @Test
    fun previewInstallRejectedWithoutHeader() {
        val ui = SourceInstaller.plan("class X extends ComicSource {}").toPreviewUi()
        assertTrue(!ui.error.isNullOrBlank())
    }

    @Test
    fun previewInstallDetectsUpdate() {
        val installed = record(key = "com.example.t")
        val lookup = FakeLookup(listOf(installed))
        val installedVersion = SourceScriptHeader.parse(script(version = "1.1.0"))?.key
            ?.let { lookup.get(it)?.version }
        val ui = SourceInstaller.plan(script(version = "1.1.0"), installedVersion = installedVersion).toPreviewUi()
        assertEquals(null, ui.error)
        assertEquals("更新", ui.updateLabel)
    }
}
