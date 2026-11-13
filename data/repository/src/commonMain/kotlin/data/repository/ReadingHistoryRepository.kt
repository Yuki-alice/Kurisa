package data.repository

import data.db.ReaderDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import source.contract.ContentKind
import kotlin.time.Clock

/** UTC 天（毫秒）。展示侧的本地时区格式化由 UI 负责，仓储只给 UTC 对齐的天起点。 */
const val MILLIS_PER_DAY = 86_400_000L

/**
 * 单次阅读上报（B-4 · BE-B5 历史与阅读统计）。
 * 调用方节流（每章或每分钟至多一次）；一次 record 计一次会话。
 */
data class ReadingEvent(
    val workId: String,
    val atEpochMs: Long,
    /** 本次阅读时长（毫秒，>=0）。 */
    val durationMs: Long,
)

/** 作品累计统计。 */
data class ReadingStats(
    val workId: String,
    val totalDurationMs: Long,
    val sessionCount: Int,
    val lastReadAtEpochMs: Long?,
)

/** 按 UTC 日聚合的一天（只含当天有阅读的作品）。 */
data class ReadingDay(
    val dayStartEpochMs: Long,
    val workIds: List<String>,
    val totalDurationMs: Long,
)

/** 续读入口（按 last_read_at 倒序，调用方取首个即“继续阅读”）。 */
data class RecentWork(
    val workId: String,
    val title: String,
    val lastReadAtEpochMs: Long?,
)

@Serializable
private data class WorkHistoryValue(
    val totalMs: Long = 0L,
    val sessions: Int = 0,
    val last: Long = 0L,
    /** 天键 = UTC 天序号字符串（避开日期库依赖）；只保留最近 90 天。 */
    val days: Map<String, Long> = emptyMap(),
)

/**
 * 历史 / 阅读统计仓储（B-4 · BE-B5；`data/repository` 只新增本文件）。
 *
 * 三个写目标，全部复用现有字段（不新增表/列/.sqm）：
 * - `work.last_read_at` 写透（续读与书架排序用；书架行已存在时只改该列，其余原样写回）；
 * - 时长累计落 `source_kv` 保留命名空间（不与真实源 key 冲突，无表结构改动）；
 * - 续读顺序另维护一个 kv 索引（`recent` 不依赖新查询，`Work.sq` 不用加语句）。
 *
 * 写透的级联风险：`upsertWork` 是 INSERT OR REPLACE，外键打开时删旧行会级联删
 * `chapter` 行——因此写前先读出章节，同一事务内补回去；FK 开/关两种环境目录都不丢。
 */
interface ReadingHistoryRepository {
    /** 上报一次阅读：写透 last_read_at + 累计时长 + 刷新续读索引。 */
    suspend fun record(event: ReadingEvent, kind: ContentKind = ContentKind.MANGA): ReadingStats

    /** 作品累计（无记录时 total/sessions 为 0，last 回退到 work 行）。 */
    suspend fun stats(workId: String): ReadingStats

    /** 最近 days 个 UTC 日的分组（只含当天有阅读的天，新的在前）。 */
    suspend fun history(
        days: Int = 30,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): List<ReadingDay>

    /** 最近在读（按 last_read_at 倒序，最多 limit 个）。 */
    suspend fun recent(limit: Int = 20): List<RecentWork>
}

internal class ReadingHistoryRepositoryImpl(
    private val db: ReaderDatabase,
) : ReadingHistoryRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun record(event: ReadingEvent, kind: ContentKind): ReadingStats {
        require(event.durationMs >= 0) { "durationMs must be >= 0" }
        writeLastReadAt(event.workId, event.atEpochMs, kind)
        val cur = readValue(event.workId) ?: WorkHistoryValue()
        val dayKey = (event.atEpochMs / MILLIS_PER_DAY).toString()
        val merged = cur.days + (dayKey to ((cur.days[dayKey] ?: 0L) + event.durationMs))
        val pruned = if (merged.size > 90) {
            merged.toList().sortedBy { it.first.toLong() }.takeLast(90).toMap()
        } else {
            merged
        }
        val next = cur.copy(
            totalMs = cur.totalMs + event.durationMs,
            sessions = cur.sessions + 1,
            last = maxOf(cur.last, event.atEpochMs),
            days = pruned,
        )
        db.sourceKvQueries.putKv(NS, event.workId, json.encodeToString(next))
        touchIndex(event.workId)
        return toStats(event.workId, next)
    }

    override suspend fun stats(workId: String): ReadingStats {
        val v = readValue(workId)
        if (v == null) {
            val row = db.workQueries.selectWork(workId).executeAsOneOrNull()
            return ReadingStats(workId, 0L, 0, row?.last_read_at)
        }
        return toStats(workId, v)
    }

    override suspend fun history(days: Int, nowMs: Long): List<ReadingDay> {
        require(days > 0) { "days must be > 0" }
        val ids = readIndex()
        if (ids.isEmpty()) return emptyList()
        val today = nowMs / MILLIS_PER_DAY
        val out = mutableListOf<ReadingDay>()
        for (k in 0 until days) {
            val day = today - k
            val key = day.toString()
            var total = 0L
            val works = mutableListOf<String>()
            for (id in ids) {
                val ms = readValue(id)?.days?.get(key) ?: 0L
                if (ms > 0) {
                    works += id
                    total += ms
                }
            }
            if (works.isNotEmpty()) out += ReadingDay(day * MILLIS_PER_DAY, works, total)
        }
        return out
    }

    override suspend fun recent(limit: Int): List<RecentWork> {
        require(limit > 0) { "limit must be > 0" }
        return readIndex().take(limit).mapNotNull { id ->
            val row = db.workQueries.selectWork(id).executeAsOneOrNull() ?: return@mapNotNull null
            RecentWork(workId = id, title = row.title, lastReadAtEpochMs = row.last_read_at)
        }
    }

    private fun toStats(workId: String, v: WorkHistoryValue) =
        ReadingStats(workId, v.totalMs, v.sessions, v.last.takeIf { it > 0 })

    private fun readValue(workId: String): WorkHistoryValue? {
        val raw = db.sourceKvQueries.getKv(NS, workId).executeAsOneOrNull() ?: return null
        return runCatching { json.decodeFromString<WorkHistoryValue>(raw) }.getOrNull()
    }

    private fun readIndex(): List<String> {
        val raw = db.sourceKvQueries.getKv(NS, INDEX_KEY).executeAsOneOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrElse { emptyList() }
    }

    private fun touchIndex(workId: String) {
        val ids = readIndex().toMutableList()
        ids.remove(workId)
        ids.add(0, workId)
        db.sourceKvQueries.putKv(NS, INDEX_KEY, json.encodeToString(ids.take(200)))
    }

    private fun writeLastReadAt(workId: String, atMs: Long, kind: ContentKind) {
        val row = db.workQueries.selectWork(workId).executeAsOneOrNull()
        if (row == null) {
            db.workQueries.upsertWork(
                id = workId,
                source_key = "",
                source_work_id = workId,
                kind = kind.serial,
                title = workId,
                subtitle = null,
                cover = null,
                description = null,
                tags_json = "[]",
                stars = null,
                language = null,
                url = null,
                added_at = atMs,
                last_read_at = atMs,
            )
            return
        }
        val lastRead = row.last_read_at
        if (lastRead != null && lastRead >= atMs) return
        // INSERT OR REPLACE 在 FK 打开时删旧行会级联删 chapter 行：先读出再补回。
        val chapters = db.chapterQueries.selectChapters(workId).executeAsList()
        db.transaction {
            db.workQueries.upsertWork(
                id = row.id,
                source_key = row.source_key,
                source_work_id = row.source_work_id,
                kind = row.kind,
                title = row.title,
                subtitle = row.subtitle,
                cover = row.cover,
                description = row.description,
                tags_json = row.tags_json,
                stars = row.stars,
                language = row.language,
                url = row.url,
                added_at = row.added_at,
                last_read_at = atMs,
            )
            chapters.forEach { c ->
                db.chapterQueries.upsertChapter(
                    id = c.id,
                    work_id = c.work_id,
                    source_chapter_id = c.source_chapter_id,
                    title = c.title,
                    ordinal = c.ordinal,
                    payload_json = c.payload_json,
                    downloaded = c.downloaded,
                    updated_at = c.updated_at,
                )
            }
        }
    }

    companion object {
        /** source_kv 保留命名空间（真实源 key 不会取该值，跨源隔离由 schema 保证）。 */
        const val NS = "host:reading-history"
        private const val INDEX_KEY = "__recent_index__"
    }
}

fun createReadingHistoryRepository(db: ReaderDatabase): ReadingHistoryRepository =
    ReadingHistoryRepositoryImpl(db)
