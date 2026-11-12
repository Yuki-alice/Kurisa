package source.registry

import core.model.SourceRecord
import data.db.ReaderDatabase

/**
 * 源注册表（安装/禁用/回滚/生命周期）。
 * LRU 缓存按 key 串行（调用方保证同源调用不并发，与 SourceDispatcher 约定一致）。
 */
class SourceRegistry(private val db: ReaderDatabase, private val maxCache: Int = 20) {
    // 可移植 LRU（LinkedHashMap.removeEldestEntry 在 Kotlin/Native 不存在）：
    // 插入序 + 访问时 remove+重插，被访问 key 自然沉底；超限从头淘汰。
    private val cache = LinkedHashMap<String, SourceRecord>()
    private val history = mutableMapOf<String, SourceRecord>()

    private fun cachePut(key: String, value: SourceRecord) {
        cache.remove(key)
        cache[key] = value
        while (cache.size > maxCache) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)
        }
    }

    private fun rowToRecord(row: data.db.Source): SourceRecord = SourceRecord(
        key = row.key, name = row.name, version = row.version, apiVersion = row.api_version.toInt(),
        kindsJson = row.kinds, capabilitiesJson = row.capabilities, domainsJson = row.domains,
        installPath = row.install_path, sha256 = row.sha256,
        signatureOk = row.signature_ok == 1L, enabled = row.enabled == 1L,
        settingsJson = row.settings_json, updatedAtEpochMs = row.updated_at,
    )

    fun get(key: String): SourceRecord? {
        cache.remove(key)?.let {
            cache[key] = it
            return it
        }
        val row = db.sourceQueries.selectSource(key).executeAsOneOrNull() ?: return null
        val rec = rowToRecord(row)
        cachePut(key, rec)
        return rec
    }

    fun put(record: SourceRecord) {
        get(record.key)?.let { history[record.key] = it }
        cachePut(record.key, record)
    }

    fun setEnabled(key: String, enabled: Boolean, nowMs: Long): Boolean {
        val cur = get(key) ?: return false
        db.sourceQueries.setSourceEnabled(if (enabled) 1L else 0L, nowMs, key)
        cachePut(key, cur.copy(enabled = enabled, updatedAtEpochMs = nowMs))
        return true
    }

    /** 安装确认：写 `source` 表 + 进缓存（含回滚历史）。调用方负责先落盘脚本。 */
    fun confirmInstall(
        plan: SourceInstaller.InstallPlan,
        script: String,
        installPath: String,
        settingsJson: String = "{}",
        nowMs: Long,
    ): SourceRecord {
        val rec = SourceInstaller.confirm(db, plan, script, installPath, settingsJson, nowMs)
        put(rec)
        return rec
    }

    /** 回滚到上一个版本（保留当前为历史，调用方负责恢复脚本文件）。 */
    fun rollback(key: String, nowMs: Long): SourceRecord? {
        val prev = history[key] ?: return null
        val cur = get(key)
        db.sourceQueries.upsertSource(
            prev.key, prev.name, prev.version, prev.apiVersion.toLong(),
            prev.kindsJson, prev.capabilitiesJson, prev.domainsJson,
            prev.installPath, prev.sha256, if (prev.signatureOk) 1L else 0L,
            1L, prev.settingsJson, nowMs,
        )
        if (cur != null) history[key] = cur
        val restored = prev.copy(enabled = true, updatedAtEpochMs = nowMs)
        cachePut(key, restored)
        return restored
    }

    fun enabledKeys(): List<String> =
        db.sourceQueries.selectEnabledSources().executeAsList().map { it.key }

    /** 全量安装列表（含禁用，源管理页用；ORDER BY key 与 SQL 一致，保证三端同序）。 */
    fun allKeys(): List<String> =
        db.sourceQueries.selectAllSources().executeAsList().map { it.key }

    /** 全量记录（读穿缓存；调用方在后台线程调用，避免阻塞主线程）。 */
    fun all(): List<SourceRecord> =
        db.sourceQueries.selectAllSources().executeAsList().map { row ->
            cache[row.key] ?: rowToRecord(row).also { cachePut(row.key, it) }
        }
}
