package source.contract

import kotlinx.serialization.Serializable

/**
 * 一体包规则子集数据模型（`docs/plugin-system/SOURCE-PACKAGE.md` §3）。
 *
 * 位置说明：纯 Kotlin、零引擎依赖，供 `:source:registry` 安装期解析（不执行脚本）
 * 与 `:source:bridge` 规则执行器复用同一形状。字段求值迷你语言（`@attr | filter`）
 * 与执行语义见 bridge 侧 `RulesEngine`，此处只冻结传输形状。
 */
@Serializable
data class RulesPackage(
    val format: String = "rules/v1",
    val key: String = "",
    val name: String = "",
    val version: String = "1.0.0",
    val kind: List<String> = listOf("manga"),
    val domains: List<String> = emptyList(),
    val baseUrl: String = "",
    val search: RulesSearch? = null,
    val detail: RulesDetail? = null,
    val content: RulesContent? = null,
    /** 声明式设置（`HOST-SDK.md § Store` 回退链：用户值 → default → ""）。 */
    val settings: Map<String, RulesSetting> = emptyMap(),
)

@Serializable
data class RulesRequest(
    val method: String = "GET",
    val url: String = "",
)

@Serializable
data class RulesSearch(
    val request: RulesRequest = RulesRequest(),
    val mode: String = "html",
    val list: String = "",
    val fields: Map<String, String> = emptyMap(),
    val pagination: RulesPagination? = null,
)

@Serializable
data class RulesPagination(
    val maxPage: String = "auto",
)

@Serializable
data class RulesDetail(
    val request: RulesRequest = RulesRequest(),
    val mode: String = "html",
    val title: String = "h1@text",
    val cover: String = "",
    val chapters: RulesChapters = RulesChapters(),
)

@Serializable
data class RulesChapters(
    val list: String = "",
    val id: String = "@href | fileName",
    val title: String = "@text | trim",
)

@Serializable
data class RulesContent(
    val images: RulesImages? = null,
    val text: RulesText? = null,
)

@Serializable
data class RulesImages(
    val list: String = "",
    val value: String = "@data-src|@src | absUrl",
)

@Serializable
data class RulesText(
    val select: String = "",
    val clean: Boolean = true,
)

@Serializable
data class RulesOption(
    val value: String = "",
    val text: String = "",
)

@Serializable
data class RulesSetting(
    val title: String = "",
    val type: String = "select",
    val options: List<RulesOption> = emptyList(),
    val default: String = "",
)

object RulesPackageJson {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun decode(raw: String): RulesPackage = json.decodeFromString(raw)
}
