package app.shared

import core.common.AppLog
import core.common.ChapterStamp
import core.common.IntervalUpdateTrigger
import core.common.UpdateSource
import core.common.UpdateTrigger
import data.db.ReaderDatabase
import data.repository.createChapterCacheRepository
import feature.settings.SettingsPrefs
import feature.settings.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import source.bridge.ChapterCachePort
import source.bridge.TelemetryRecorder

/**
 * B 批后端能力的装配收口（A-5）。
 *
 * 双端壳各构造一份，同时喂给 [createSourceCatalog]（章节缓存 / 遥测 / 日志）与
 * [App]（设置持久化 / 版本号 / 缓存清理），并由壳启动 [core.common.UpdateScheduler]。
 * 这样「数据层已实装但没接上」的 5 处断点在一个地方闭环，不把装配散落到 feature 模块。
 *
 * 所有依赖都是接口或纯 Kotlin 实现，不新增 expect/actual（ADR-006）。
 */
class BackendWiring(
    val chapterCache: ChapterCachePort,
    val telemetry: TelemetryRecorder,
    val appLog: AppLog,
    val settingsStore: SettingsStore,
    val updateSource: UpdateSource,
    val updateTrigger: UpdateTrigger,
    val appVersion: String,
    /** null = 未装配图片缓存清理，Settings 屏不渲染该入口，不伪装能力。 */
    val clearImageCache: (suspend () -> Long)? = null,
)

/** 生产装配入口；[catalogProvider] 用 lambda 延迟获取，避开 catalog ↔ wiring 的构造环。 */
fun createBackendWiring(
    db: ReaderDatabase,
    catalogProvider: () -> SourceCatalog,
    appLog: AppLog,
    nowMs: () -> Long,
    appVersion: String = "1.0.0",
    clearImageCache: (suspend () -> Long)? = null,
): BackendWiring = BackendWiring(
    chapterCache = createChapterCacheRepository(db),
    telemetry = TelemetryRecorder(),
    appLog = appLog,
    settingsStore = DbSettingsStore(db),
    updateSource = DbUpdateSource(db, catalogProvider, nowMs),
    updateTrigger = IntervalUpdateTrigger(DEFAULT_UPDATE_INTERVAL_MS),
    appVersion = appVersion,
    clearImageCache = clearImageCache,
)

/** 6 小时一轮；平台壳可换成 WorkManager / 系统定时器，接口不变。 */
const val DEFAULT_UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L

private const val SETTINGS_SCOPE = "host"
private const val SETTINGS_KEY = "settings.prefs"

/** 设置偏好落 `source_kv`（宿主命名空间，跨源隔离规则不冲突），三端同一份 SQLDelight 实现。 */
private class DbSettingsStore(private val db: ReaderDatabase) : SettingsStore {
    override suspend fun load(): SettingsPrefs {
        val raw = runCatching {
            db.sourceKvQueries.getKv(SETTINGS_SCOPE, SETTINGS_KEY).executeAsOneOrNull()
        }.getOrNull() ?: return SettingsPrefs()
        return runCatching {
            val o = Json.parseToJsonElement(raw).jsonObject
            SettingsPrefs(
                meteredNetworkRestricted = o["meteredNetworkRestricted"]?.jsonPrimitive?.boolean ?: false,
                imageCacheLimitMb = o["imageCacheLimitMb"]?.jsonPrimitive?.int ?: 0,
            )
        }.getOrDefault(SettingsPrefs())
    }

    override suspend fun save(prefs: SettingsPrefs) {
        val json = buildJsonObject {
            put("meteredNetworkRestricted", prefs.meteredNetworkRestricted)
            put("imageCacheLimitMb", prefs.imageCacheLimitMb)
        }.toString()
        db.sourceKvQueries.putKv(SETTINGS_SCOPE, SETTINGS_KEY, json)
    }
}

/**
 * [UpdateSource] 的数据库适配器（BE-B3）。
 *
 * 口径：`cachedWorkIds` 返回**源内 id**（`work.source_work_id`），远端目录才能直接调
 * `providerFor(sourceKey).chapters(workId)`；本地目录则先反查宿主 `work.id` 再读 chapter 表。
 * 远端失败由 `UpdateScheduler` 记入 errors；若 provider 因缓存回退拿到本地目录，diff 为空、
 * 不会误报更新（保守但安全）。
 */
private class DbUpdateSource(
    private val db: ReaderDatabase,
    private val catalogProvider: () -> SourceCatalog,
    private val nowMs: () -> Long,
) : UpdateSource {

    override suspend fun activeSourceKeys(): List<String> =
        db.sourceQueries.selectEnabledSources().executeAsList().map { it.key }

    override suspend fun cachedWorkIds(sourceKey: String): List<String> =
        db.workQueries.selectCachedWorks(sourceKey).executeAsList().map { it.source_work_id }

    override suspend fun localChapters(sourceKey: String, workId: String): List<ChapterStamp> {
        val row = db.workQueries.selectWorkBySourceWorkId(sourceKey, workId).executeAsOneOrNull()
            ?: return emptyList()
        return db.chapterQueries.selectChapters(row.id).executeAsList().map {
            ChapterStamp(id = it.source_chapter_id, title = it.title, updatedAtEpochMs = it.updated_at)
        }
    }

    override suspend fun remoteChapters(sourceKey: String, workId: String): List<ChapterStamp> {
        val remote = catalogProvider().providerFor(sourceKey).chapters(workId)
        val stamp = nowMs()
        return remote.map { ChapterStamp(id = it.id, title = it.title, updatedAtEpochMs = stamp) }
    }
}
