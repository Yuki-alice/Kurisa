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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
import source.registry.SourceInstaller
import source.registry.SourceRegistry

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
 * - 导航侧 `ReaderRoute` 携带 sourceKey 为后续项（需导航签名变更，见 ADR-008）；
 *   接线后调用方传入映射即可，本提供方无需再改。
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
    private val appVersion: String = "0.0.0",
    /**
     * 按作品路由（P3）：`workId → sourceKey`（调用方由书架/详情的作品归属提供，
     * 后续 `ReaderRoute` 携带 sourceKey 后直传；导航未接线前保持默认）。
     * 返回 null/未知 key/被禁用源时回退首个 enabled 源（现行为零变）。
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

    /** 装配输入收口（阅读链路与测试面板共用同一签名/路由语义）。 */
    internal fun specFor(record: SourceRecord, script: String): PluginRuntimeSpec {
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
    appVersion: String = "0.0.0",
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
    private val appVersion: String = "0.0.0",
) {
    fun snapshot(): List<SourceRecord> = registry.all()

    suspend fun setEnabled(key: String, enabled: Boolean): Boolean {
        val ok = registry.setEnabled(key, enabled, nowMs())
        if (ok) manager.invalidate(key)
        return ok
    }

    fun currentDomains(): Set<String> =
        lookup.enabledKeys().flatMapTo(mutableSetOf()) { key ->
            lookup.get(key)?.let { parseStringList(it.domainsJson) }.orEmpty()
        }

    /** 源管理页快照（Record → UI 行，映射收口于此，feature 只收纯展示字段）。 */
    fun snapshotUi(): List<SourceUiItem> = snapshot().map { it.toUiItem() }

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

fun createSourceCatalog(
    db: ReaderDatabase,
    httpClient: HttpClient,
    cookies: CookieStore,
    loadScript: suspend (installPath: String) -> String,
    appVersion: String = "0.0.0",
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
    val provider = RegistryChapterProvider(
        lookup = lookup,
        acquire = { spec -> manager.acquire(spec) },
        loadScript = loadScript,
        appVersion = appVersion,
    )
    return SourceCatalog(registry, manager, provider, lookup, nowMs, loadScript, appVersion)
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
