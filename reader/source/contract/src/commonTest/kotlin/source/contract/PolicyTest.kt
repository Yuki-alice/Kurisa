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
        val badApi = ContractPolicy.validateMeta(goodMeta().copy(apiVersion = 3))
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
                    kind = setOf(ContentKind.MANGA), domains = listOf("ex.com"),
                    sha256 = "a".repeat(64), url = url, filename = file,
                )
            )
        )
        assertTrue(ContractPolicy.validateIndex(idx("https://ex.com/n.js", null)).isEmpty())
        assertTrue(ContractPolicy.validateIndex(idx(null, "n.js")).isEmpty())
        assertFalse(ContractPolicy.validateIndex(idx(null, null)).isEmpty())
        assertFalse(ContractPolicy.validateIndex(idx("https://ex.com/n.js", "n.js")).isEmpty())
        assertFalse(
            ContractPolicy.validateIndex(
                idx("https://ex.com/n.js", null).copy(
                    sources = listOf(
                        IndexedSource(
                            name = "N", key = "bad key", version = "1.0.0",
                            kind = setOf(ContentKind.MANGA), domains = listOf("ex.com"),
                            sha256 = "xyz", url = "https://ex.com/n.js",
                        )
                    )
                )
            ).isEmpty()
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
}
