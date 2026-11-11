package source.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import source.contract.ContentKind
import source.contract.ContractPolicy
import source.contract.ImageLoadConfig
import source.contract.SourceCapability
import source.contract.SourceDescriptor
import source.contract.SourceErrorKind

/**
 * 单个已装配源的运行时门面（docs/plugin-engine.md §3/§4/§5/§6/§7）。
 *
 * 职责：能力门 → 引擎调用 → 错误分类 → `LOGIN_EXPIRED` 重放一次 → 原始 JSON；
 * 另负责声明式设置预热、图片加载配置归一化与翻译。
 * 不持有 Repository / Database，脚本字节与宿主配置由装配层传入（架构 §3.1.4）。
 */
class SourceRuntime(
    val descriptor: SourceDescriptor,
    private val engine: SourcePluginEngine,
    private val onRelogin: suspend () -> Boolean = { false },
    private val onError: (SourcePluginException) -> Unit = {},
) : SourceCalls, AutoCloseable {

    /**
     * 设置回退链：先写 descriptor 的 default，再用用户值覆盖；
     * 写进源私有 KV 的 `setting:` 命名空间后，JS `loadSetting` 可同步读取。
     */
    fun updateSettings(userSettingsJson: String = "{}") {
        val merged = buildJsonObject {
            descriptor.settings.forEach { setting ->
                setting.default?.let { put(setting.key, it) }
            }
            runCatching { Json.parseToJsonElement(userSettingsJson).jsonObject }
                .getOrNull()?.forEach { (key, value) -> put(key, value) }
        }
        engine.applySettings(merged.toString())
    }

    /** 通用能力调用（Venera 形状；path 仅允许标识符点分，由引擎校验）。 */
    suspend fun call(path: String, argsJson: String = "[]"): String = invoke(path, argsJson)

    /** 游标分页（`search.loadNext` 或 v4 `search.page`）；缺位时抛 CONTRACT，不静默返回空。 */
    suspend fun searchNext(keyword: String, optionsJson: String, next: String?): String {
        if (descriptor.hasSearchPage) return invokePage(keyword, optionsJson, next)
        if (!descriptor.hasSearchNext) {
            throw SourcePluginException("源未声明 search.loadNext", SourceErrorKind.CONTRACT)
        }
        return invoke(
            "search.loadNext",
            buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(keyword))
                add(Json.parseToJsonElement(optionsJson))
                add(next?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                    ?: kotlinx.serialization.json.JsonNull)
            }.toString(),
        )
    }

    /**
     * 调用侧统一分页（v4 DX）：有 next 走游标，无 next 走页码。
     * 与源侧 `search.page` 自委托互补：源作者愿意可只实现 page，
     * 调用方愿意可只调 searchPage，宿主按描述符路由，不静默降级。
     */
    suspend fun searchPage(keyword: String, optionsJson: String, page: Int? = null, next: String? = null): String {
        if (next != null) return searchNext(keyword, optionsJson, next)
        return searchWorks(keyword, optionsJson, page ?: 1)
    }

    /** `comic.onImageLoad` → 归一化配置（危险头剥离、重试有界）。未声明时返回 null。 */
    suspend fun imageLoadConfig(url: String, workId: String, epId: String): ImageLoadConfig? {
        if (!descriptor.hasImageLoad) return null
        val args = buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(url))
            add(kotlinx.serialization.json.JsonPrimitive(workId))
            add(kotlinx.serialization.json.JsonPrimitive(epId))
        }.toString()
        return parseImageConfig(invoke("comic.onImageLoad", args))
    }

    /** `novel.onImageResolve` → 归一化配置（与图片共用剥离/复核语义）。未声明时返回 null。 */
    suspend fun novelImageResolve(url: String): ImageLoadConfig? {
        if (!descriptor.hasNovelImageResolve) return null
        val args = buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(url))
        }.toString()
        return parseImageConfig(invoke("novel.onImageResolve", args))
    }

    /** 源内 i18n；未声明 translation 时返回 key 本身。 */
    suspend fun translate(key: String): String {
        if (!descriptor.has(SourceCapability.TRANSLATION)) return key
        val raw = invoke(
            "translate",
            buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(key)) }.toString(),
        )
        return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(key)
    }

    fun has(capability: SourceCapability): Boolean = descriptor.has(capability)

    override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String {
        // v4 统一入口优先：源只实现 page 时，load/loadNext 缺位也不报错。
        if (descriptor.hasSearchPage) return invokePage(keyword, optionsJson, page)
        if (!descriptor.hasSearchLoad) {
            throw SourcePluginException(
                if (descriptor.hasSearchNext) {
                    "源仅提供 search.loadNext（游标分页），请改用 searchNext()"
                } else "源未提供 search.load",
                SourceErrorKind.CONTRACT,
            )
        }
        return withPolicy("search.load") { engine.searchWorks(keyword, optionsJson, page) }
    }

    /** v4 `search.page(keyword, options, pageOrNext)` 统一调用（页码传数字，游标传字符串/null）。 */
    private suspend fun invokePage(keyword: String, optionsJson: String, pageOrNext: Any?): String {
        val args = buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive(keyword))
            add(Json.parseToJsonElement(optionsJson))
            add(
                when (pageOrNext) {
                    is Number -> kotlinx.serialization.json.JsonPrimitive(pageOrNext.toInt())
                    is String -> kotlinx.serialization.json.JsonPrimitive(pageOrNext)
                    else -> kotlinx.serialization.json.JsonNull
                },
            )
        }.toString()
        return invoke("search.page", args)
    }

    override suspend fun defaultSearchOptions(): String =
        withPolicy("search.load") { engine.defaultSearchOptions() }

    override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String =
        withPolicy(pathForDetails(kind)) {
            engine.loadWorkDetails(workId, kind)
        }

    override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind): String =
        withPolicy(pathForPayload(kind)) {
            engine.loadChapterPayload(workId, chapterId, kind)
        }

    private fun pathForDetails(kind: ContentKind): String = when (kind) {
        ContentKind.NOVEL -> "novel.loadInfo"
        ContentKind.MANGA -> "comic.loadInfo"
        ContentKind.VIDEO -> throw SourcePluginException(
            "视频源渲染 deferred（ADR-011 D4）：仅类型与能力扩展点已落地",
            SourceErrorKind.CONTRACT,
        )
        ContentKind.AUDIO -> throw SourcePluginException(
            "音频源渲染 deferred（ADR-011 D4）：仅类型与能力扩展点已落地",
            SourceErrorKind.CONTRACT,
        )
    }

    private fun pathForPayload(kind: ContentKind): String = when (kind) {
        ContentKind.NOVEL -> "novel.loadChapter"
        ContentKind.MANGA -> "comic.loadEp"
        ContentKind.VIDEO -> throw SourcePluginException(
            "视频源渲染 deferred（ADR-011 D4）：仅类型与能力扩展点已落地",
            SourceErrorKind.CONTRACT,
        )
        ContentKind.AUDIO -> throw SourcePluginException(
            "音频源渲染 deferred（ADR-011 D4）：仅类型与能力扩展点已落地",
            SourceErrorKind.CONTRACT,
        )
    }

    override suspend fun invoke(path: String, argsJson: String): String =
        withPolicy(path) { engine.invoke(path, argsJson) }

    override fun close() = engine.close()

    /**
     * 能力门 + 统一错误面 + 一次重放。
     * 结构化并发取消与「宿主缺 native」原样抛出（测试据此跳过，不误判为源错误）。
     */
    private suspend fun withPolicy(path: String, block: suspend () -> String): String {
        val needed = ContractPolicy.capabilityForPath(path)
        if (needed != null && !descriptor.has(needed)) {
            throw SourcePluginException(
                "能力未声明：$path（需 ${needed.serial}）",
                SourceErrorKind.CONTRACT,
            )
        }
        return try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException || t is HostMissingException) throw t
            val error = t.asPluginError()
            if (error.kind == SourceErrorKind.LOGIN_EXPIRED && onRelogin()) {
                try {
                    block()
                } catch (t2: Throwable) {
                    if (t2 is CancellationException || t2 is HostMissingException) throw t2
                    val replayError = t2.asPluginError()
                    onError(replayError)
                    throw replayError
                }
            } else {
                onError(error)
                throw error
            }
        }
    }

    /** 不可信脚本输出的容错解析；函数字段一律忽略。 */
    internal fun parseImageConfig(raw: String): ImageLoadConfig {
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw SourcePluginException("onImageLoad 返回非法 JSON", SourceErrorKind.CONTRACT, it)
        }
        fun primitive(key: String) = runCatching { o[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val headers = (o["headers"] as? JsonObject)?.entries
            ?.mapNotNull { (k, v) ->
                runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.let { k to it }
            }
            ?.toMap().orEmpty()
        return ImageLoadConfig(
            url = primitive("url"),
            method = primitive("method") ?: "GET",
            headers = headers,
            referer = primitive("referer") ?: headers.entries
                .firstOrNull { it.key.equals("referer", true) }?.value,
            data = primitive("data"),
            maxRetries = primitive("maxRetries")?.toIntOrNull() ?: 1,
        ).sanitized()
    }
}
