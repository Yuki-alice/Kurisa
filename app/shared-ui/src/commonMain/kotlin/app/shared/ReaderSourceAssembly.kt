package app.shared

import core.model.ChapterPayload
import core.model.SourceRecord
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderPageRequest
import data.db.ReaderDatabase
import data.network.CookieStore
import data.network.SourceHttpClient
import data.repository.createKvStore
import feature.sources.InstallOutcomeUi
import feature.sources.InstallPreviewUi
import feature.sources.SourceUiItem
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import feature.detail.ChapterUiItem
import feature.detail.WorkDetailUi
import feature.search.SearchPageUi
import feature.search.SearchWorkUi
import source.bridge.BridgeChapterProvider
import source.bridge.CryptoHostConvert
import source.bridge.HostUtilsImpl
import source.bridge.InMemoryHostStorage
import source.bridge.KsoupHostHtml
import source.bridge.KtorHostNetwork
import source.bridge.KurisaNativePluginEngineFactory
import source.bridge.NoopHostUi
import source.bridge.NovelHost
import source.bridge.PluginHostProvider
import source.bridge.PluginRuntimeSpec
import source.bridge.QuickJsPluginEngineFactory
import source.bridge.SourceCalls
import source.bridge.SourceRuntimeManager
import source.bridge.VersionedPluginEngineFactory
import source.contract.ContentKind
import source.contract.SourceCapability
import source.contract.SourceScriptHeader
import source.hostapi.HostApi
import source.hostapi.HostStorage
import source.registry.SourceIndexParser
import source.registry.SourceInstaller
import source.registry.SourceRegistry
import source.registry.SourceRepoPlanner
import source.registry.SourceRepoSubscription
import source.registry.UpdateCandidate

/**
 * 源仓库更新建议（App 层纯展示行）。
 *
 * 与 feature 层的 `SourceUiItem` 同属 UI 行，但为避免 feature 反向依赖
 * `:source:registry`，本行定义在 App 层，字段均为基础/可空类型。
 * [downloadUrl] 取索引条目的 `url`，缺省回退 `filename`（二者皆无时为 null）。
 */
data class SourceUpdateUi(
    val key: String,
    val version: String,
    val installedVersion: String?,
    val reason: String,
    val downloadUrl: String?,
)

/** 一次订阅刷新的 App 层结果：候选展示行 + 失败原因（成功时为 null）。 */
data class RepoRefreshUi(
    val candidates: List<SourceUpdateUi>,
    val error: String?,
)

/**
 * 阅读器真实源装配（P0，composition root）。
 *
 * 位置说明（模块边界）：
 * - `:feature:reader` 只依赖 [ChapterProvider] 接口，不认识 bridge/registry；
 * - `:source:*` 不持有 Repository/Database（架构 3.1.4）；
 * - 此处是 App 层装配点，可以同时认识两边，是唯一允许的接线位置。
 *
 * 路由策略（P3 按作品路由 + 单活跃源回退）：
 * - `workSourceKey(workId)` 命中可用源时走作品源；否则取 registry 首个 enabled 源；
 *   无源时退化为 Noop 语义（空目录 + payload 抛错），与 [feature.reader.NoopChapterProvider] 一致，保 C1。
 * - B4 已接线：`ReaderRoute` / `DetailRoute` 携带 sourceKey，App 层经
 *   [SourceCatalog.bindWork] 写入 [WorkSourceIndex]，本提供方据此命中作品源。
 */
data class ReaderSourceBindings(
    val chapters: ChapterProvider,
    val domains: Set<String>,
)

/** 无源时的诚实空绑定（C1：主仓零预置源）。 */
fun noopReaderBindings(): ReaderSourceBindings =
    ReaderSourceBindings(NoopChapters, emptySet())

private object NoopChapters : ChapterProvider {
    override suspend fun chapters(workId: String): List<ChapterRef> = emptyList()
    override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
        throw IllegalStateException("无可用源")
}

/**
 * 作品归属索引（B4）：`workId → sourceKey`。
 *
 * 搜索/发现页拿到结果后由 App 层 [SourceCatalog.bindWork] 写入；`RegistryChapterProvider`
 * 只认 workId（ChapterProvider 契约），因此按作品命中源必须经过这层映射。
 * 用 [MutableStateFlow] 承载不可变快照，`update` 原子写、`value` 同步读，
 * 跨 UI 线程与 Dispatchers.Default 可见，且不引入平台相关锁。
 */
class WorkSourceIndex {
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())

    fun bind(workId: String, sourceKey: String) {
        if (workId.isBlank() || sourceKey.isBlank()) return
        bindings.update { it + (workId to sourceKey) }
    }

    fun keyFor(workId: String): String? = bindings.value[workId]

    fun size(): Int = bindings.value.size
}

/** 活跃源查询抽象（RegistryChapterProvider 只依赖此口，可单测）。 */
interface ActiveSourceLookup {
    fun enabledKeys(): List<String>
    fun allKeys(): List<String>
    fun get(key: String): SourceRecord?
}

private class RegistryLookup(private val registry: SourceRegistry) : ActiveSourceLookup {
    override fun enabledKeys(): List<String> = registry.enabledKeys()
    override fun allKeys(): List<String> = registry.allKeys()
    override fun get(key: String): SourceRecord? = registry.get(key)
}

/**
 * 基于注册表的章节源（懒装配：脚本只在首次阅读时加载）。
 *
 * - domains 取所有 enabled 源 domainsJson 的并集（同步读 DB，无 IO）；
 * - 章节/正文/图片配置按作品源 Bridge（映射缺失/源不可用时回退首个 enabled 源；
 *   脚本缺失时抛错进 Error 态）。
 */
class RegistryChapterProvider(
    private val lookup: ActiveSourceLookup,
    private val acquire: suspend (PluginRuntimeSpec) -> SourceCalls,
    private val loadScript: suspend (installPath: String) -> String,
    private val appVersion: String = "1.0.0",
    /**
     * 按作品路由（P3 + B4）：`workId → sourceKey`，生产装配接 [WorkSourceIndex]；
     * 未绑定 / 未知 key / 被禁用源时回退首个 enabled 源（现行为零变）。
     */
    private val workSourceKey: (workId: String) -> String? = { null },
) : ChapterProvider {
    // P0: 脚本内容按 installPath 缓存（key = sha256），命中缓存不再每页读盘。
    // 记录更新/回滚后 sha 变化即失效重读；并发重复加载仅多一次 IO，结果一致。
    private val scriptCache = mutableMapOf<String, Pair<String, String>>()

    private fun activeRecord(): SourceRecord? =
        lookup.enabledKeys().firstOrNull()?.let { lookup.get(it) }

    /** 按作品路由：映射命中且源可用时走作品源，否则回退首个 enabled 源。 */
    private fun recordFor(workId: String): SourceRecord? {
        val mapped = runCatching { workSourceKey(workId) }.getOrNull()
        if (!mapped.isNullOrBlank() && lookup.enabledKeys().contains(mapped)) {
            lookup.get(mapped)?.let { return it }
        }
        return activeRecord()
    }

    private suspend fun cachedScript(record: SourceRecord): String {
        val cached = scriptCache[record.installPath]
        if (cached != null && cached.first == record.sha256 && record.sha256.isNotBlank()) {
            return cached.second
        }
        val script = loadScript(record.installPath)
        // 仅缓存带稳定 sha 的记录；无 sha（极老库）仍每次读盘，保证正确。
        if (record.sha256.isNotBlank()) {
            if (scriptCache.size >= 16) scriptCache.remove(scriptCache.keys.firstOrNull())
            scriptCache[record.installPath] = record.sha256 to script
        }
        return script
    }

    private suspend fun bridgeFor(record: SourceRecord): BridgeChapterProvider {
        val kind = parseKinds(record.kindsJson).firstOrNull() ?: ContentKind.MANGA
        val runtime = acquire(specFor(record, cachedScript(record)))
        return BridgeChapterProvider(runtime, kind)
    }

    /** 装配输入收口（阅读链路、测试面板与搜索/详情出口共用同一签名/路由语义）。 */
    internal fun specFor(record: SourceRecord, script: String): PluginRuntimeSpec =
        runtimeSpecFor(record, script, appVersion)

    override suspend fun chapters(workId: String): List<ChapterRef> {
        val rec = recordFor(workId) ?: return emptyList()
        return bridgeFor(rec).chapters(workId)
    }

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload {
        val rec = recordFor(workId) ?: throw IllegalStateException("无可用源")
        return bridgeFor(rec).payload(workId, chapterId)
    }

    override suspend fun resolvePage(
        workId: String,
        chapterId: String,
        url: String,
    ): ReaderPageRequest {
        val rec = recordFor(workId) ?: return ReaderPageRequest(url)
        return bridgeFor(rec).resolvePage(workId, chapterId, url)
    }

    /** 所有 enabled 源的域名白名单并集（图片出口用）。 */
    fun currentDomains(): Set<String> =
        lookup.enabledKeys().flatMapTo(mutableSetOf()) { key ->
            lookup.get(key)?.let { parseStringList(it.domainsJson) }.orEmpty()
        }
}

/** 装配输入归一化（顶层复用：阅读链路 / 测试面板 / 搜索详情出口）。 */
internal fun runtimeSpecFor(
    record: SourceRecord,
    script: String,
    appVersion: String,
): PluginRuntimeSpec {
    val domains = parseStringList(record.domainsJson).toSet()
    val capabilities = parseStringList(record.capabilitiesJson)
        .mapNotNull { SourceCapability.of(it) }.toSet()
    return PluginRuntimeSpec(
        key = record.key,
        script = script,
        domains = domains,
        capabilities = capabilities,
        settingsJson = record.settingsJson,
        apiVersion = record.apiVersion,
        appVersion = appVersion,
        scriptSha256 = record.sha256,
        // 过渡启发式（P2）：DB 无 format 列前，按安装路径后缀与脚本内容判定；
        // P3 增加 format 列 + 迁移后改为读列（见 SOURCE-PACKAGE §5）。
        format = detectFormat(record.installPath, script),
    )
}

internal fun parseStringList(raw: String): List<String> =
    runCatching {
        Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
    }.getOrElse {
        // 兼容老库：旧 confirm() 曾写入无引号的 `[a.com]`，JSON 解析失败时按裸列表兜底。
        val t = raw.trim()
        if (t.startsWith("[") && t.endsWith("]") && t.length >= 2) {
            t.substring(1, t.length - 1).split(",")
                .map { it.trim().trim('"') }
                .filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
    }

internal fun parseKinds(raw: String): List<ContentKind> =
    parseStringList(raw).mapNotNull { ContentKind.of(it) }

/** 安装键解析：JS 头块优先，规则 JSON 次之（两者互斥，见 `SourceInstaller.plan` 分派）。 */
internal fun lookupKey(script: String): String? =
    SourceScriptHeader.parse(script)?.key
        ?: source.registry.RulesHeader.parse(script)?.key

/**
 * 源形态过渡判定（P2，DB 无 format 列前）。
 * `*.rules.json` 或脚本含 `"format":"rules/v1"` 即按规则执行器装配，其余走 JS；
 * P3 增加 `source.format` 列 + 迁移后改为读列，不再嗅探内容。
 */
internal fun detectFormat(installPath: String, script: String): String {
    if (installPath.endsWith(".rules.json")) return "rules/v1"
    val t = script.trimStart()
    if (t.startsWith("{") && t.contains("\"rules/v1\"")) return "rules/v1"
    return "js/v1"
}

/**
 * 生产装配：DB + HttpClient + CookieStore → 可注入 App 的绑定。
 *
 * HostApi 七命名空间与 bridge harness 同形（见 RealSourceE2ETest）：
 * Network 经 SourceHttpClient（白名单/Cookie/配对 UA），Storage 落 source_kv，
 * UI 为 Noop（WebView 登录随 M6，桌面缺能力时走 Cookie 导入兜底，见 ADR-009）。
 */
fun createReaderBindings(
    db: ReaderDatabase,
    httpClient: HttpClient,
    cookies: CookieStore,
    loadScript: suspend (installPath: String) -> String,
    appVersion: String = "1.0.0",
    nowMs: () -> Long = { 0L },
): ReaderSourceBindings {
    val catalog = createSourceCatalog(db, httpClient, cookies, loadScript, appVersion, nowMs)
    return ReaderSourceBindings(catalog.chapters, catalog.currentDomains())
}

/**
 * 源目录（源管理页 + 阅读器共享的装配出口）。
 *
 * - [snapshot] 全量安装记录（含禁用，ORDER BY key）；
 * - [setEnabled] 写 DB + 失效运行时缓存（禁用源的在途复用即刻切断，
 *   下次 chapters()/payload() 走新状态；图片白名单由调用方重取 [currentDomains]）。
 */
class SourceCatalog internal constructor(
    private val registry: SourceRegistry,
    private val manager: SourceRuntimeManager,
    val chapters: ChapterProvider,
    private val lookup: ActiveSourceLookup,
    private val nowMs: () -> Long,
    private val loadScript: suspend (installPath: String) -> String = { error("未装配脚本加载器") },
    private val appVersion: String = "1.0.0",
    private val workIndex: WorkSourceIndex = WorkSourceIndex(),
    /** B3：与源请求共享的 CookieStore / HttpClient，阅读器图片出口复用（配对 UA 不脱链）。 */
    val cookies: CookieStore = CookieStore(),
    val httpClient: HttpClient? = null,
) {
    fun snapshot(): List<SourceRecord> = registry.all()

    /**
     * 绑定作品归属（B4）：搜索/发现结果进入详情或阅读前调用。
     * 绑定后 `chapters/payload/resolvePage` 按该源装配，不再回退首个 enabled 源。
     */
    fun bindWork(workId: String, sourceKey: String) {
        if (sourceKey.isBlank() || lookup.get(sourceKey) == null) return
        workIndex.bind(workId, sourceKey)
    }

    /** 已启用源 key（搜索页展示 / 单源筛选用）。 */
    fun enabledSourceKeys(): List<String> = lookup.enabledKeys()

    /**
     * 跨已启用源聚合搜索（B5）。单源失败只记入 errors，不拖垮其余源；
     * 结果行自带 sourceKey，供详情/阅读按作品路由。
     */
    suspend fun search(keyword: String, page: Int = 1, sourceKey: String? = null): SearchPageUi {
        val records = if (sourceKey.isNullOrBlank()) {
            lookup.enabledKeys().mapNotNull { lookup.get(it) }
        } else {
            listOfNotNull(lookup.get(sourceKey))
        }
        val works = mutableListOf<SearchWorkUi>()
        val errors = mutableListOf<String>()
        var maxPage = 1
        for (rec in records) {
            runCatching {
                val raw = runtimeFor(rec).searchWorks(keyword, "[]", page)
                val parsed = parseSearchPage(raw, rec)
                works += parsed.works
                maxPage = maxOf(maxPage, parsed.maxPage)
            }.onFailure { errors += "${rec.name}（${rec.key}）：${it.message ?: "搜索失败"}" }
        }
        return SearchPageUi(works = works, errors = errors, maxPage = maxPage)
    }

    /**
     * 发现 / 分类页后端出口（BE-A4）。
     *
     * 统一走契约 `categoryComics.load`（`docs/contract/source.d.ts:95`，参数
     * `category, param, options, page`；category 为空时传空串），结果与搜索同形
     * （`works/comics` 归一化）。能力未声明 / 引擎不支持该路径的源会被能力门或
     * 引擎拒绝，按「单源失败」记入 errors，不拖垮其余源。
     */
    suspend fun explore(
        sourceKey: String? = null,
        category: String = "",
        page: Int = 1,
    ): SearchPageUi {
        val records = if (sourceKey.isNullOrBlank()) {
            lookup.enabledKeys().mapNotNull { lookup.get(it) }
        } else {
            listOfNotNull(lookup.get(sourceKey))
        }
        val args = JsonArray(
            listOf(
                JsonPrimitive(category),
                JsonPrimitive(""),
                JsonArray(emptyList<JsonElement>()),
                JsonPrimitive(page),
            ),
        ).toString()
        return aggregateExplore(
            records = records,
            call = { rec -> runtimeFor(rec).invoke("categoryComics.load", args) },
            parse = ::parseSearchPage,
        )
    }

    /** 作品详情（B5）：按 sourceKey 精确取源，返回标题 + 归一化章节列表。 */
    suspend fun detail(workId: String, sourceKey: String): WorkDetailUi {
        val rec = lookup.get(sourceKey)
            ?: return WorkDetailUi(workId, sourceKey, workId, error = "未知源：$sourceKey")
        return runCatching {
            val kind = parseKinds(rec.kindsJson).firstOrNull() ?: ContentKind.MANGA
            val raw = runtimeFor(rec).loadWorkDetails(workId, kind)
            val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw IllegalArgumentException("详情非法 JSON：${it.message}") }
            val title = obj["title"]?.jsonPrimitive?.contentOrNull
                ?: (obj["work"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
                ?: workId
            WorkDetailUi(workId, sourceKey, title, parseChapterRows(obj["chapters"]))
        }.getOrElse {
            WorkDetailUi(workId, sourceKey, workId, error = it.message ?: "详情加载失败")
        }
    }

    /** 按记录装配长驻运行时（复用 manager 缓存；脚本由 loadScript 提供）。 */
    private suspend fun runtimeFor(rec: SourceRecord): SourceCalls =
        manager.acquire(runtimeSpecFor(rec, loadScript(rec.installPath), appVersion))

    /** `SearchPage.works/comics` 归一化；兼容直接返回数组的旧源。 */
    private fun parseSearchPage(raw: String, rec: SourceRecord): SearchPageUi {
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrElse {
            throw IllegalArgumentException("搜索结果非法 JSON：${it.message}")
        }
        val array = when (root) {
            is JsonObject -> (root["works"] ?: root["comics"]) as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: throw IllegalArgumentException("搜索结果缺 works/comics")
        val works = array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SearchWorkUi(
                workId = id,
                sourceKey = rec.key,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: id,
                subtitle = o["subtitle"]?.jsonPrimitive?.contentOrNull ?: "",
                cover = o["cover"]?.jsonPrimitive?.contentOrNull,
                kindLabel = o["kind"]?.jsonPrimitive?.contentOrNull ?: "manga",
                sourceLabel = rec.name,
            )
        }
        val maxPage = (root as? JsonObject)?.get("maxPage")?.jsonPrimitive?.contentOrNull
            ?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return SearchPageUi(works, emptyList(), maxPage)
    }

    /** 与 `BridgeChapterProvider` 同形的三形状归一化（object / array）。 */
    private fun parseChapterRows(el: JsonElement?): List<ChapterUiItem> = when (el) {
        is JsonObject -> el.entries.mapIndexed { i, (id, v) ->
            val title = if (v is JsonObject) {
                v["title"]?.jsonPrimitive?.contentOrNull ?: id
            } else {
                runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: id
            }
            ChapterUiItem(id, title, i + 1)
        }
        is JsonArray -> el.mapIndexedNotNull { i, item ->
            val o = item as? JsonObject ?: return@mapIndexedNotNull null
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            ChapterUiItem(
                id,
                o["title"]?.jsonPrimitive?.contentOrNull ?: id,
                o["ordinal"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: (i + 1),
            )
        }
        else -> emptyList()
    }

    suspend fun setEnabled(key: String, enabled: Boolean): Boolean {
        val ok = registry.setEnabled(key, enabled, nowMs())
        if (ok) manager.invalidate(key)
        return ok
    }

    fun currentDomains(): Set<String> =
        lookup.enabledKeys().flatMapTo(mutableSetOf()) { key ->
            lookup.get(key)?.let { parseStringList(it.domainsJson) }.orEmpty()
        }

    /**
     * 封面字节（书架缩略图）。
     *
     * 强制走 [SourceHttpClient]：首跳与逐跳重定向白名单、Cookie、配对 UA、Cloudflare
     * 分类全部继承阅读链路，禁止 UI 直接联网；任何失败返回 null（封面缺失不阻断列表）。
     * 未装配网络栈、或封面域名不在当前启用源白名单时返回 null（安全优先）。
     */
    suspend fun coverBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        val client = httpClient ?: return null
        return runCatching {
            SourceHttpClient(client, cookies, nowMs)
                .getBytes(url, currentDomains())
                .bytes
        }.getOrNull()
    }

    /** 源管理页快照（Record → UI 行，映射收口于此，feature 只收纯展示字段）。 */
    fun snapshotUi(): List<SourceUiItem> = snapshot().map { it.toUiItem() }

    /**
     * 源仓库更新计划的纯数据出口（不联网）。
     *
     * 索引原始 JSON 由调用方（宿主/源管理页）注入，本方法只做
     * `SourceIndexParser.parse` → 已装列表取自 [snapshot] → `SourceRepoPlanner.plan`
     * → 映射为 [SourceUpdateUi]，全程无 IO、不碰域名白名单。
     *
     * **fail-closed**：解析失败或计划计算抛异常时返回 `emptyList()` 而非抛出，
     * 让源管理页直接展示「无更新」，避免坏索引/脏数据把 UI 带崩。
     *
     * @param appVersionOverride 覆盖构造时的装配版本做 minApp 过滤；null 用装配版本。
     */
    fun planRepoUpdates(rawIndexJson: String, appVersionOverride: String? = null): List<SourceUpdateUi> =
        runCatching {
            val index = SourceIndexParser.parse(rawIndexJson)
            val installed = snapshot().map { it.key to it.version }
            SourceRepoPlanner.plan(index, installed, appVersionOverride ?: appVersion)
                .map { it.toUpdateUi() }
        }.getOrElse { emptyList() }

    /**
     * 订阅刷新的 UI 出口：网络拉取由宿主以 [fetchIndex] 注入，本模块不自己联网、
     * 不碰白名单。拉取/解析失败由 [SourceRepoSubscription.refresh] 收敛为
     * `RepoRefreshResult.error`（此处透传为 [RepoRefreshUi.error]），候选映射为纯展示行；
     * 协程取消（CancellationException）按结构化并发原样抛出，不吞。
     */
    suspend fun refreshRepoUpdates(fetchIndex: suspend () -> String): RepoRefreshUi {
        val result = SourceRepoSubscription(fetchIndex).refresh(
            installed = snapshot().map { it.key to it.version },
            appVersion = appVersion,
        )
        return RepoRefreshUi(
            candidates = result.candidates.map { it.toUpdateUi() },
            error = result.error,
        )
    }

    /**
     * 规则测试面板后端入口（P3 工具链；Compose UI 消费本方法）。
     * 对已安装源按 key 取记录 → 读脚本 → 同阅读链路装配 → 跑三步 diagnose。
     * JS 源抛 CONTRACT（仅规则源支持）；诊断失败走结果 JSON 的 `{error,code}`，此处不抛。
     * 禁用源亦可测试（面板场景不需要阅读链路的 enabled 门）。
     */
    suspend fun testRules(
        key: String,
        keyword: String,
        page: Int,
        workId: String,
        chapterId: String,
    ): String {
        val record = lookup.get(key)
            ?: throw source.bridge.SourcePluginException(
                "未知源：$key",
                source.contract.SourceErrorKind.CONTRACT,
            )
        val script = loadScript(record.installPath)
        val provider = RegistryChapterProvider(
            lookup = lookup,
            acquire = { spec -> manager.acquire(spec) },
            loadScript = loadScript,
            appVersion = appVersion,
        )
        val runtime = manager.acquire(provider.specFor(record, script))
        return buildString {
            append("{\"search\":")
            append(runtime.diagnoseSearch(keyword, page))
            append(",\"detail\":")
            append(runtime.diagnoseDetail(workId))
            append(",\"content\":")
            append(runtime.diagnoseContent(workId, chapterId))
            append("}")
        }
    }

    /**
     * 安装预览（纯计算：解析头块 → 查已装版本 → plan，不执行脚本、不碰文件）。
     * 调用方在后台线程执行（大脚本静态扫描为正则开销）。
     */
    fun previewInstall(script: String): InstallPreviewUi {
        val installedVersion = lookupKey(script)
            ?.let { lookup.get(it)?.version }
        return SourceInstaller.plan(script, installedVersion = installedVersion).toPreviewUi()
    }

    /**
     * 安装确认：plan →（需二次确认则回 NeedsConfirm）→ 平台落盘 → 写库 → 失效运行时。
     * 未签名/旧仓库/无索引源必须用户显式二次确认后才落盘（架构附录 A）。
     * 落盘扩展名由 plan.format 决定（`js/v1`→`.js`，`rules/v1`→`.rules.json`），调用方据此分发目录。
     */
    suspend fun installSource(
        script: String,
        saveScript: suspend (key: String, version: String, script: String, format: String) -> String,
        confirmed: Boolean,
    ): InstallOutcomeUi {
        val installedVersion = lookupKey(script)
            ?.let { lookup.get(it)?.version }
        val plan = when (val r = SourceInstaller.plan(script, installedVersion = installedVersion)) {
            is SourceInstaller.PlanResult.Rejected -> return InstallOutcomeUi.Rejected(r.reasons.joinToString("\n"))
            is SourceInstaller.PlanResult.Ok -> r.plan
        }
        if (plan.requiresDoubleConfirm && !confirmed) {
            return InstallOutcomeUi.NeedsConfirm(plan.toPreviewUi())
        }
        val path = runCatching { saveScript(plan.key, plan.version, script, plan.format) }.getOrElse {
            return InstallOutcomeUi.Rejected("脚本落盘失败：${it.message}")
        }
        val rec = registry.confirmInstall(plan, script, path, nowMs = nowMs())
        manager.invalidate(plan.key)
        return InstallOutcomeUi.Installed(rec.toUiItem())
    }
}

internal fun SourceInstaller.PlanResult.toPreviewUi(): InstallPreviewUi =
    when (this) {
        is SourceInstaller.PlanResult.Rejected -> InstallPreviewUi(error = reasons.joinToString("\n"))
        is SourceInstaller.PlanResult.Ok -> plan.toPreviewUi()
    }

internal fun SourceInstaller.InstallPlan.toPreviewUi(): InstallPreviewUi = InstallPreviewUi(
    name = name,
    key = key,
    version = version,
    kindsLabel = kinds.joinToString(" · "),
    domainsLabel = domains.joinToString(", "),
    capabilitiesLabel = capabilities.joinToString(", "),
    warnings = warnings,
    verifiedLabel = if (signatureOk) "已签名" else "未验证（旧仓库兼容，需二次确认）",
    updateLabel = when {
        isUpdate -> "更新"
        isDowngrade -> "降级（将覆盖现有版本）"
        else -> "新装"
    },
    requiresDoubleConfirm = requiresDoubleConfirm,
)

internal fun SourceRecord.toUiItem(): SourceUiItem = SourceUiItem(
    key = key,
    name = name,
    version = version,
    enabled = enabled,
    kindsLabel = parseKinds(kindsJson).joinToString(" · ") { it.serial },
    domainsLabel = parseStringList(domainsJson).joinToString(", "),
    verifiedLabel = if (signatureOk) "已签名" else "未验证（旧仓库兼容）",
)

/** `UpdateCandidate`（source:registry）→ App 层展示行，映射收口于此。 */
private fun UpdateCandidate.toUpdateUi(): SourceUpdateUi = SourceUpdateUi(
    key = entry.key,
    version = entry.version,
    installedVersion = installedVersion,
    reason = reason,
    downloadUrl = entry.url ?: entry.filename,
)

/**
 * 发现 / 分类结果的跨源聚合（BE-A4 纯逻辑，注入 call 便于三态单测）。
 * 无源返回空页 + 显式提示；单源失败只记入 errors，不拖垮其余源。
 */
internal suspend fun aggregateExplore(
    records: List<SourceRecord>,
    call: suspend (SourceRecord) -> String,
    parse: (String, SourceRecord) -> SearchPageUi,
): SearchPageUi {
    if (records.isEmpty()) return SearchPageUi(errors = listOf("无可用源"))
    val works = mutableListOf<SearchWorkUi>()
    val errors = mutableListOf<String>()
    var maxPage = 1
    for (rec in records) {
        runCatching {
            val parsed = parse(call(rec), rec)
            works += parsed.works
            maxPage = maxOf(maxPage, parsed.maxPage)
        }.onFailure { errors += "${rec.name}（${rec.key}）：${it.message ?: "发现失败"}" }
    }
    return SearchPageUi(works = works, errors = errors, maxPage = maxPage)
}

fun createSourceCatalog(
    db: ReaderDatabase,
    httpClient: HttpClient,
    cookies: CookieStore,
    loadScript: suspend (installPath: String) -> String,
    appVersion: String = "1.0.0",
    nowMs: () -> Long = { 0L },
): SourceCatalog {
    val registry = SourceRegistry(db)
    val http = SourceHttpClient(httpClient, cookies, nowMs)
    val kv: HostStorage = createKvStore(db)
    // 无 DB 的单测/预览走内存 KV；生产此处为 DbKvStore。
    val memFallback = InMemoryHostStorage()
    val kvSafe: HostStorage = kvOrMem(kv, memFallback)
    val hosts = PluginHostProvider { spec ->
        val network = KtorHostNetwork(http, cookies, spec.domains, nowMs = nowMs)
        object : HostApi {
            override val network = network
            override val html = KsoupHostHtml()
            override val convert = CryptoHostConvert()
            override val ui = NoopHostUi()
            override val utils = HostUtilsImpl()
            override val storage = kvSafe
            override val novel = NovelHost()
        }
    }
    val manager = SourceRuntimeManager(
        engines = VersionedPluginEngineFactory(
            legacy = QuickJsPluginEngineFactory(cookies),
            native = KurisaNativePluginEngineFactory(cookies),
        ),
        hosts = hosts,
    )
    val lookup = RegistryLookup(registry)
    val workIndex = WorkSourceIndex()
    val provider = RegistryChapterProvider(
        lookup = lookup,
        acquire = { spec -> manager.acquire(spec) },
        loadScript = loadScript,
        appVersion = appVersion,
        // B4：按作品路由接真映射（此前恒 null，多源下永远回退首个 enabled 源）。
        workSourceKey = { workIndex.keyFor(it) },
    )
    return SourceCatalog(
        registry, manager, provider, lookup, nowMs, loadScript, appVersion,
        workIndex = workIndex,
        cookies = cookies,
        httpClient = httpClient,
    )
}

/**
 * DbKvStore 在极老库缺 source_kv 表时抛错（表由基线创建，但用户库可能早于该表），
 * 此时退化为内存存储，保证阅读链路不因 KV 缺表而整体不可用。
 * 表缺失是安装期问题，调用方应在下次迁移中补齐，此处只保底。
 */
private fun kvOrMem(
    kv: HostStorage,
    mem: HostStorage,
): HostStorage = object : HostStorage {
    override fun get(sourceKey: String, key: String): String? =
        runCatching { kv.get(sourceKey, key) }.getOrNull()
            ?: runCatching { mem.get(sourceKey, key) }.getOrNull()

    override fun set(sourceKey: String, key: String, value: String) {
        runCatching { kv.set(sourceKey, key, value) }
            .onFailure { runCatching { mem.set(sourceKey, key, value) } }
    }

    override fun delete(sourceKey: String, key: String) {
        runCatching { kv.delete(sourceKey, key) }
        runCatching { mem.delete(sourceKey, key) }
    }
}
