package source.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyTest {

    private fun goodMeta() = SourceMeta(
        name = "N", key = "com.example.novel", version = "1.2.0",
        minAppVersion = "1.0.0", apiVersion = 2,
        kind = setOf(ContentKind.NOVEL), domains = listOf("example.com"),
        capabilities = listOf("search"),
    )

    @Test
    fun validMetaPasses() {
        assertTrue(ContractPolicy.validateMeta(goodMeta()).isEmpty())
    }

    @Test
    fun rejectsBadKeyVersionApi() {
        val badKey = ContractPolicy.validateMeta(goodMeta().copy(key = "NoDots"))
        assertTrue(badKey.any { it.contains("key") })
        val badVer = ContractPolicy.validateMeta(goodMeta().copy(version = "1.0"))
        assertTrue(badVer.any { it.contains("version") })
        val badApi = ContractPolicy.validateMeta(goodMeta().copy(apiVersion = 5))
        assertTrue(badApi.any { it.contains("apiVersion") })
        val noKind = ContractPolicy.validateMeta(goodMeta().copy(kind = emptySet()))
        assertTrue(noKind.any { it.contains("kind") })
        val noDomains = ContractPolicy.validateMeta(goodMeta().copy(domains = emptyList()))
        assertTrue(noDomains.any { it.contains("domains") })
    }

    @Test
    fun indexOneOfUrlFilename() {
        fun idx(url: String?, file: String?) = SourceIndex(
            sources = listOf(
                IndexedSource(
                    name = "N", key = "com.example.n", version = "1.0.0",
                    kind = setOf(ContentKind.MANGA), domains = listOf("example.com"),
                    sha256 = "a".repeat(64), url = url, filename = file,
                )
            )
        )
        assertTrue(ContractPolicy.validateIndex(idx("https://example.com/n.js", null)).isEmpty())
        assertTrue(ContractPolicy.validateIndex(idx(null, "n.js")).isEmpty())
        assertFalse(ContractPolicy.validateIndex(idx(null, null)).isEmpty())
        assertFalse(ContractPolicy.validateIndex(idx("https://example.com/n.js", "n.js")).isEmpty())
        assertFalse(
            ContractPolicy.validateIndex(
                idx("https://example.com/n.js", null).copy(
                    sources = listOf(
                        IndexedSource(
                            name = "N", key = "bad key", version = "1.0.0",
                            kind = setOf(ContentKind.MANGA), domains = listOf("example.com"),
                            sha256 = "xyz", url = "https://example.com/n.js",
                        )
                    )
                )
            ).isEmpty()
        )
    }

    @Test
    fun indexCapabilitiesAreValidated() {
        val index = SourceIndex(
            sources = listOf(
                IndexedSource(
                    name = "N", key = "com.example.n", version = "1.0.0",
                    kind = setOf(ContentKind.MANGA), domains = listOf("example.com"),
                    sha256 = "a".repeat(64), url = "https://example.com/n.js",
                    capabilities = listOf("search", "bogus"),
                ),
            ),
        )
        val violations = ContractPolicy.validateIndex(index)
        assertTrue(violations.any { it.contains("capabilities") }, violations.toString())
        assertTrue(
            ContractPolicy.validateIndex(index.copy(
                sources = listOf(index.sources.first().copy(capabilities = listOf("search", "imageLoad"))),
            )).isEmpty(),
        )
    }

    @Test
    fun optionDefaultsFrozen() {
        assertEquals(
            listOf("any", "all", null),
            ContractPolicy.defaultOptions(listOf(listOf("any-Any"), listOf("all"), emptyList()))
        )
    }

    @Test
    fun localeShape() {
        assertTrue(ContractPolicy.isLocaleShape("zh_CN"))
        assertTrue(!ContractPolicy.isLocaleShape("zh-CN"))
    }

    @Test
    fun capabilityVersioning() {
        // v2 是冻结基线；v3 才启用扩展能力集；v4 在 v3 上叠加 opt-in 能力；宿主按版本启用能力。
        assertTrue(ContractPolicy.validateMeta(goodMeta().copy(apiVersion = 3)).isEmpty())
        assertTrue(ContractPolicy.validateMeta(goodMeta().copy(apiVersion = 4)).isEmpty())
        assertTrue(SourceCapability.SETTINGS in ContractPolicy.capabilitiesFor(3))
        assertFalse(SourceCapability.SETTINGS in ContractPolicy.capabilitiesFor(2))
        assertTrue(SourceCapability.HTML_HELPER in ContractPolicy.capabilitiesFor(4))
        assertTrue(SourceCapability.NOVEL_CHAPTER in ContractPolicy.capabilitiesFor(4))
        assertTrue(SourceCapability.CRYPTO_AES in ContractPolicy.capabilitiesFor(4))
        assertFalse(SourceCapability.HTML_HELPER in ContractPolicy.capabilitiesFor(3))
        // 视频/音频为 v4 预留扩展点（渲染 deferred，仅类型与能力先行）。
        assertTrue(SourceCapability.VIDEO in ContractPolicy.capabilitiesFor(4))
        assertTrue(SourceCapability.AUDIO in ContractPolicy.capabilitiesFor(4))
        assertFalse(SourceCapability.VIDEO in ContractPolicy.capabilitiesFor(3))
        assertFalse(SourceCapability.AUDIO in ContractPolicy.capabilitiesFor(3))
        assertEquals(SourceCapability.VIDEO, ContractPolicy.capabilityForPath("video.loadInfo"))
        assertEquals(SourceCapability.AUDIO, ContractPolicy.capabilityForPath("audio.loadChapter"))
        // video kind 在 v4 元数据校验中通过（仓库索引暂不接纳，fail-closed 在索引层）。
        assertTrue(
            ContractPolicy.validateMeta(
                goodMeta().copy(apiVersion = 4, kind = setOf(ContentKind.VIDEO)),
            ).isEmpty(),
        )
        val effective = ContractPolicy.effectiveCapabilities(
            detected = setOf(SourceCapability.SEARCH, SourceCapability.SETTINGS),
            apiVersion = 2,
        )
        assertEquals(setOf(SourceCapability.SEARCH), effective)
        assertEquals(
            setOf(SourceCapability.SEARCH, SourceCapability.SETTINGS),
            ContractPolicy.effectiveCapabilities(
                detected = setOf(SourceCapability.SEARCH, SourceCapability.SETTINGS),
                apiVersion = 3,
            ),
        )
        // 头块声明优先收窄：脚本私自挂 settings 也拿不到能力位。
        assertEquals(
            setOf(SourceCapability.SEARCH),
            ContractPolicy.effectiveCapabilities(
                detected = setOf(SourceCapability.SEARCH, SourceCapability.SETTINGS),
                apiVersion = 3,
                declared = setOf(SourceCapability.SEARCH),
            ),
        )
        assertEquals(SourceCapability.COMIC, ContractPolicy.capabilityForPath("comic.onImageLoad"))
        assertEquals(null, ContractPolicy.capabilityForPath("spin"))
    }

    @Test
    fun errorClassification() {
        assertEquals(
            SourceErrorKind.LOGIN_EXPIRED,
            ContractPolicy.classifyError("JSException: Login expired, please login again"),
        )
        assertEquals(SourceErrorKind.RATE_LIMITED, ContractPolicy.classifyError("Rate limited by site"))
        assertEquals(SourceErrorKind.RATE_LIMITED, ContractPolicy.classifyError("http:429"))
        assertEquals(SourceErrorKind.NOT_FOUND, ContractPolicy.classifyError("NotFound"))
        assertEquals(SourceErrorKind.PERMISSION, ContractPolicy.classifyError("domain-blocked:https://evil.com"))
        assertEquals(SourceErrorKind.PERMISSION, ContractPolicy.classifyError("unsupported-hostcall"))
        assertEquals(SourceErrorKind.TIMEOUT, ContractPolicy.classifyError("源调用超时（800ms）"))
        assertEquals(SourceErrorKind.CONTRACT, ContractPolicy.classifyError("disposed-or-unknown"))
        assertEquals(SourceErrorKind.CONTRACT, ContractPolicy.classifyError("""{"error":"unknown-op"}"""))
        assertEquals(SourceErrorKind.CONTRACT, ContractPolicy.classifyError("""{"error":"too-many-requests"}"""))
        assertEquals(SourceErrorKind.EXECUTION, ContractPolicy.classifyError("TypeError: undefined"))
    }
}
