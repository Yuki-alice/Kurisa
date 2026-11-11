package source.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 插件引擎侧契约模型（纯 Kotlin，零引擎依赖，三端一致）。
 *
 * 与 M1 冻结的 [SourceMeta] 分工：元数据来自「不执行脚本」的头注释块，
 * 描述符来自引擎 `describe()` 对实例化后字段的只读求值结果。
 * 两者在装配时比对：头块是安装期可信输入，描述符是运行期能力事实。
 */

/** 能力位。`@capabilities` 声明与运行时描述符共用同一套名字。 */
@Serializable
enum class SourceCapability(val serial: String) {
    SEARCH("search"),
    EXPLORE("explore"),
    CATEGORY("category"),
    COMIC("comic"),
    NOVEL("novel"),
    ACCOUNT("account"),
    FAVORITES("favorites"),
    SETTINGS("settings"),
    TRANSLATION("translation"),
    IMAGE_LOAD("imageLoad"),
    /** v4 新增：小说双位齐备（loadInfo + loadChapter）才授予，对标 hasSearchLoad/Next。 */
    NOVEL_CHAPTER("novelChapter"),
    /** v4 新增：Html.list/text/attr 自动 dispose helper 可用。 */
    HTML_HELPER("htmlHelper"),
    /** v4 新增：AES 转正（U1 定案后），转正前宿主显式拒绝。 */
    CRYPTO_AES("cryptoAes"),
    /** v4 预留：视频源（渲染 deferred，见 ADR-011 D4；仅类型与能力扩展点）。 */
    VIDEO("video"),
    /** v4 预留：音频源（渲染 deferred，见 ADR-011 D4；仅类型与能力扩展点）。 */
    AUDIO("audio");

    companion object {
        fun of(raw: String): SourceCapability? =
            entries.firstOrNull { it.serial == raw.trim() }
    }
}

/** JS 字符串异常在桥边界的分类；重放策略见 docs/plugin-engine.md §6。 */
@Serializable
enum class SourceErrorKind {
    LOGIN_EXPIRED,
    RATE_LIMITED,
    NOT_FOUND,
    PERMISSION,
    TIMEOUT,
    CONTRACT,
    EXECUTION,
}

@Serializable
enum class SettingType(val serial: String) {
    SELECT("select"),
    SWITCH("switch"),
    INPUT("input"),
    CALLBACK("callback");

    companion object {
        fun of(raw: String): SettingType =
            entries.firstOrNull { it.serial == raw.trim().lowercase() } ?: INPUT
    }
}

@Serializable
data class SettingOption(val value: String, val text: String = value)

/** 声明式源设置。callback 函数不跨桥，仅记录存在性。 */
@Serializable
data class SourceSetting(
    val key: String,
    val title: String = "",
    val type: SettingType = SettingType.INPUT,
    val options: List<SettingOption> = emptyList(),
    /** 未设置时的回退值；switch 的布尔值统一字符串化。 */
    val default: String? = null,
    val validator: String? = null,
    val buttonText: String? = null,
    val hasCallback: Boolean = false,
)

/** `comic.onImageLoad` 归一化结果。危险头在 [sanitized] 中剥离。 */
@Serializable
data class ImageLoadConfig(
    val url: String? = null,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val referer: String? = null,
    val data: String? = null,
    val maxRetries: Int = 1,
) {
    /** UA / Cookie 由宿主统一注入；cf_clearance 的 UA 配对是架构红线，源不得覆盖。 */
    fun sanitized(): ImageLoadConfig {
        val forbidden = setOf("cookie", "user-agent", "host", "content-length", "proxy-authorization")
        val kept = headers.filterKeys { it.lowercase() !in forbidden }
        val withReferer = if (referer.isNullOrBlank() || kept.keys.any { it.equals("referer", true) }) {
            kept
        } else {
            kept + ("Referer" to referer)
        }
        return copy(
            headers = withReferer,
            method = method.uppercase().let { if (it in ALLOWED_METHODS) it else "GET" },
            maxRetries = maxRetries.coerceIn(0, 3),
        )
    }

    companion object {
        val ALLOWED_METHODS = setOf("GET", "POST")
    }
}

/** v4 预留流类型（U6 冻结；未知值由调用方按 CONTRACT 拒绝，保持前向收敛）。 */
@Serializable
enum class StreamType(val serial: String) {
    HLS("hls"),
    MP4("mp4"),
    DASH("dash");

    companion object {
        fun of(raw: String): StreamType? =
            entries.firstOrNull { it.serial == raw.trim().lowercase() }
    }
}

@Serializable
data class StreamSubtitle(
    val language: String? = null,
    val title: String = "",
    val url: String = "",
)

/**
 * v4 预留：流媒体播放描述（U6 字段冻结；渲染 deferred，见 ADR-011 D4）。
 * 仅类型与归一化语义先行，不接播放器、不触发播放依赖（T4 编译门）。
 * 未知 streamType 在解析侧显式拒绝，不静默降级。
 */
@Serializable
data class StreamPlayback(
    val url: String = "",
    val streamType: StreamType = StreamType.HLS,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StreamSubtitle> = emptyList(),
    val quality: String? = null,
) {
    /** 与 [ImageLoadConfig] 同一危险头集合：UA / Cookie 由宿主统一注入，源不得覆盖。 */
    fun sanitized(): StreamPlayback {
        val forbidden = setOf("cookie", "user-agent", "host", "content-length", "proxy-authorization")
        return copy(headers = headers.filterKeys { it.lowercase() !in forbidden })
    }
}

/**
 * 源运行期描述符。函数字段一律化为存在位，序列化体里不含函数对象。
 * [capabilities] 已与应用版本上界、头块声明求交，宿主只按此集合放行调用。
 */
@Serializable
data class SourceDescriptor(
    val key: String = "",
    val name: String = "",
    val version: String = "",
    val minAppVersion: String = "0.0.0",
    val apiVersion: Int = ContractPolicy.CURRENT_API_VERSION,
    val kinds: Set<ContentKind> = emptySet(),
    val domains: List<String> = emptyList(),
    val capabilities: Set<SourceCapability> = emptySet(),
    val settings: List<SourceSetting> = emptyList(),
    val translationLocales: List<String> = emptyList(),
    val hasInit: Boolean = false,
    val hasImageLoad: Boolean = false,
    /** Venera 双分页模型：源可能只有 loadNext 而没有 load。 */
    val hasSearchLoad: Boolean = true,
    val hasSearchNext: Boolean = false,
    /** v4 统一分页入口（源可只实现 page；宿主有 page 时优先调用）。 */
    val hasSearchPage: Boolean = false,
    /** v4 小说双位：缺一半的实现 ADR-011 要求报 CONTRACT（对标 hasSearchLoad/Next）。 */
    val hasNovelLoadInfo: Boolean = false,
    val hasNovelLoadChapter: Boolean = false,
    /** 文内图解析存在位（novel.onImageResolve）；缺位时宿主不调用。 */
    val hasNovelImageResolve: Boolean = false,
    val hasExplore: Boolean = false,
    val hasCategory: Boolean = false,
    val hasAccount: Boolean = false,
    val hasFavorites: Boolean = false,
) {
    fun setting(key: String): SourceSetting? = settings.firstOrNull { it.key == key }

    fun has(capability: SourceCapability): Boolean = capability in capabilities

    companion object {
        /**
         * 解析引擎 `describe()` 的 JSON。输入是**不可信的脚本输出**：
         * 任何字段缺失/形状错误都走保守默认，不抛异常穿透到宿主。
         */
        fun fromJson(
            raw: String,
            declared: Set<SourceCapability> = emptySet(),
            apiVersionOverride: Int? = null,
        ): SourceDescriptor {
            val json = Json { ignoreUnknownKeys = true }
            val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return SourceDescriptor(key = "", name = "")
            fun str(k: String): String = o[k]?.str().orEmpty()
            fun bool(k: String): Boolean = o[k]?.jsonPrimitive?.booleanOrNull ?: false

            val rawKinds = o["kinds"] as? JsonArray
            val kinds = rawKinds?.mapNotNull { it.str()?.let(ContentKind::of) }?.toSet()
                ?: setOf(ContentKind.MANGA)
            val domains = (o["domains"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
            val locales = (o["translationLocales"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty()
            val declaredCaps = (o["capabilities"] as? JsonArray)
                ?.mapNotNull { it.str()?.let(SourceCapability::of) }?.toSet().orEmpty()
            val settings = (o["settings"] as? JsonObject)?.entries?.map { (key, v) ->
                val s = v as? JsonObject ?: JsonObject(emptyMap())
                val opts = (s["options"] as? JsonArray)?.mapNotNull { opt ->
                    val oo = opt as? JsonObject ?: return@mapNotNull null
                    val value = oo["value"]?.str() ?: return@mapNotNull null
                    SettingOption(value, oo["text"]?.str() ?: value)
                }.orEmpty()
                val def = s["default"]?.jsonPrimitive?.let {
                    it.contentOrNull ?: it.booleanOrNull?.toString()
                }
                SourceSetting(
                    key = key,
                    title = s["title"]?.str().orEmpty(),
                    type = SettingType.of(s["type"]?.str().orEmpty()),
                    options = opts,
                    default = def,
                    validator = s["validator"]?.str(),
                    buttonText = s["buttonText"]?.str(),
                    hasCallback = s["hasCallback"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }.orEmpty()

            // 安装期可信版本（头块/索引）优先于脚本自报，防脚本私自抬高能力上界。
            val apiVersion = apiVersionOverride
                ?: o["apiVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: ContractPolicy.CURRENT_API_VERSION
            val inferred = buildSet {
                if (bool("hasSearch") || bool("hasSearchNext")) add(SourceCapability.SEARCH)
                if (bool("hasExplore")) add(SourceCapability.EXPLORE)
                if (bool("hasCategory")) add(SourceCapability.CATEGORY)
                if (bool("hasComic")) add(SourceCapability.COMIC)
                if (bool("hasNovel")) add(SourceCapability.NOVEL)
                if (bool("hasNovelLoadInfo") && bool("hasNovelLoadChapter")) {
                    add(SourceCapability.NOVEL)
                    add(SourceCapability.NOVEL_CHAPTER)
                }
                if (bool("hasImageLoad")) add(SourceCapability.IMAGE_LOAD)
                if (bool("hasAccount")) add(SourceCapability.ACCOUNT)
                if (bool("hasFavorites")) add(SourceCapability.FAVORITES)
                if (settings.isNotEmpty()) add(SourceCapability.SETTINGS)
                if (locales.isNotEmpty()) add(SourceCapability.TRANSLATION)
            }
            val detected = if (declaredCaps.isEmpty()) inferred else declaredCaps + inferred
            val effective = ContractPolicy.effectiveCapabilities(detected, apiVersion, declared)
            return SourceDescriptor(
                key = str("key"),
                name = str("name"),
                version = str("version"),
                minAppVersion = str("minAppVersion").ifBlank { "0.0.0" },
                apiVersion = apiVersion,
                kinds = kinds,
                domains = domains,
                capabilities = effective,
                settings = settings,
                translationLocales = locales,
                hasInit = bool("hasInit"),
                hasImageLoad = bool("hasImageLoad"),
                // 旧版 describe() 输出没有该字段，缺省视为有 load（向后兼容）。
                hasSearchLoad = o["hasSearchLoad"]?.jsonPrimitive?.booleanOrNull ?: true,
                hasSearchNext = bool("hasSearchNext"),
                hasSearchPage = bool("hasSearchPage"),
                hasNovelLoadInfo = bool("hasNovelLoadInfo"),
                hasNovelLoadChapter = bool("hasNovelLoadChapter"),
                hasNovelImageResolve = bool("hasNovelImageResolve"),
                hasExplore = bool("hasExplore"),
                hasCategory = bool("hasCategory"),
                hasAccount = bool("hasAccount"),
                hasFavorites = bool("hasFavorites"),
            )
        }

        private fun JsonElement.str(): String? =
            runCatching { jsonPrimitive.contentOrNull }.getOrNull()
    }
}
