package source.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 内容类型。`kind` 数组至少含一项。 */
@Serializable
enum class ContentKind(val serial: String) {
    @SerialName("manga")
    MANGA("manga"),

    @SerialName("novel")
    NOVEL("novel");

    companion object {
        fun of(raw: String): ContentKind? =
            entries.firstOrNull { it.serial == raw.trim().lowercase() }
    }
}

/** 统一作品实体（漫画与小说同构，差异收敛到章节载荷）。 */
@Serializable
data class Work(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val cover: String? = null,
    val tags: List<String> = emptyList(),
    val description: String? = null,
    val language: String? = null,
    val kind: ContentKind,
)

/** 章节元数据（不含正文/图片，正文经 novel/comic 命名空间另取）。 */
@Serializable
data class Chapter(
    val id: String,
    val title: String,
    val ordinal: Int,
)

@Serializable
data class WorkDetails(
    val work: Work,
    val chapters: List<Chapter> = emptyList(),
)

@Serializable
data class SearchPage(
    val works: List<Work> = emptyList(),
    val maxPage: Int = 1,
)

/** 源元数据：`// ==SourceScript==` 注释块 + 类字段的规范化结果。 */
@Serializable
data class SourceMeta(
    val name: String,
    val key: String,
    val version: String,
    val minAppVersion: String,
    val apiVersion: Int = ContractPolicy.CURRENT_API_VERSION,
    val kind: Set<ContentKind> = emptySet(),
    val domains: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val url: String = "",
)

/** 账号声明：`account.loginWithWebview` / `loginWithCookies` 的静态可读部分。 */
@Serializable
data class AccountDecl(
    val webviewUrl: String? = null,
    val cookieFields: List<String> = emptyList(),
) {
    val hasWebview: Boolean get() = !webviewUrl.isNullOrBlank()
    val hasCookieLogin: Boolean get() = cookieFields.isNotEmpty()
}

/** 仓库索引条目（`docs/contract/index-schema.json` 的 Kotlin 映射）。 */
@Serializable
data class IndexedSource(
    val name: String,
    val key: String,
    val url: String? = null,
    val filename: String? = null,
    val version: String,
    val description: String = "",
    val kind: Set<ContentKind> = emptySet(),
    val domains: List<String> = emptyList(),
    val sha256: String = "",
    val signature: String? = null,
)

@Serializable
data class SourceIndex(
    val apiVersion: Int = ContractPolicy.CURRENT_API_VERSION,
    val sources: List<IndexedSource> = emptyList(),
)
