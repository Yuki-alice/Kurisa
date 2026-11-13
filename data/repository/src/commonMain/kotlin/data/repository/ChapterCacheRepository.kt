package data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import core.reader.ChapterRef
import data.db.ReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import source.bridge.ChapterCachePort
import source.contract.ContentKind

/** 章节目录与远端目录的对比结果（BE-B2 更新对比；BE-B3 调度器消费同一形状）。 */
data class ChapterCacheDiff(
    /** 远端有、本地无（按 source_chapter_id 判定）。 */
    val added: List<ChapterRef>,
    /** 本地有、远端无（如下架章节 id）。 */
    val removedIds: List<String>,
)

/**
 * 章节缓存仓储（BE-B2；`data/repository` 只新增本文件）。
 *
 * 单一真相源：`chapter` 表（SQLDelight Flow）。写入是全量替换——远端不再列出的章节
 * 视为下架，一并清理，缓存目录与远端目录永远同构；`updated_at` 记录本次抓取时间，
 * 供 BE-B3 调度器做增量对比。
 *
 * `chapter.work_id` 有外键约束：缓存前先保证 `work` 行存在。只在缺失时插入最小占位行
 * （不碰已有行的任何字段，避免 INSERT OR REPLACE 误删书架已写入的元数据；
 * 详情/书架写入的完整行永远优先）。
 *
 * 不新增表、不新增列、不写 .sqm、不改 verifyMigrations（协作 S3：`data/db` 归 A）。
 */
interface ChapterCacheRepository : ChapterCachePort {
    override suspend fun saveChapters(
        workId: String,
        kind: ContentKind,
        chapters: List<ChapterRef>,
        updatedAtEpochMs: Long,
    )

    override suspend fun loadChapters(workId: String): List<ChapterRef>?

    /** 纯响应式观察（skill 反模式 #3：观察不触发网络）。 */
    fun observeChapters(workId: String): Flow<List<ChapterRef>>

    /** 把远端目录与缓存对比（不写库；调用方决定是否 saveCompared）。 */
    suspend fun compare(workId: String, remote: List<ChapterRef>): ChapterCacheDiff

    /** 对比并写透（返回本次新增/下架；空目录也会落库，保证 updated_at 前进）。 */
    suspend fun saveCompared(
        workId: String,
        kind: ContentKind,
        remote: List<ChapterRef>,
        updatedAtEpochMs: Long,
    ): ChapterCacheDiff
}

internal class ChapterCacheRepositoryImpl(
    private val db: ReaderDatabase,
) : ChapterCacheRepository {

    override suspend fun saveChapters(
        workId: String,
        kind: ContentKind,
        chapters: List<ChapterRef>,
        updatedAtEpochMs: Long,
    ) {
        ensureWorkRow(workId, kind, updatedAtEpochMs)
        db.transaction {
            db.chapterQueries.deleteChaptersOfWork(workId)
            chapters.forEach { c ->
                db.chapterQueries.upsertChapter(
                    id = chapterRowId(workId, c.id),
                    work_id = workId,
                    source_chapter_id = c.id,
                    title = c.title,
                    ordinal = c.ordinal.toLong(),
                    payload_json = null,
                    downloaded = 0,
                    updated_at = updatedAtEpochMs,
                )
            }
        }
    }

    override suspend fun loadChapters(workId: String): List<ChapterRef>? {
        val rows = db.chapterQueries.selectChapters(workId).executeAsList()
        if (rows.isEmpty()) return null
        return rows.map { row ->
            ChapterRef(id = row.source_chapter_id, title = row.title, ordinal = row.ordinal.toInt())
        }
    }

    override fun observeChapters(workId: String): Flow<List<ChapterRef>> =
        db.chapterQueries.selectChapters(workId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    ChapterRef(id = row.source_chapter_id, title = row.title, ordinal = row.ordinal.toInt())
                }
            }

    override suspend fun compare(workId: String, remote: List<ChapterRef>): ChapterCacheDiff {
        val cachedIds = (loadChapters(workId) ?: emptyList()).map { it.id }.toSet()
        val remoteIds = remote.map { it.id }.toSet()
        return ChapterCacheDiff(
            added = remote.filter { it.id !in cachedIds },
            removedIds = cachedIds.filter { it !in remoteIds },
        )
    }

    override suspend fun saveCompared(
        workId: String,
        kind: ContentKind,
        remote: List<ChapterRef>,
        updatedAtEpochMs: Long,
    ): ChapterCacheDiff {
        val diff = compare(workId, remote)
        saveChapters(workId, kind, remote, updatedAtEpochMs)
        return diff
    }

    private fun ensureWorkRow(workId: String, kind: ContentKind, nowMs: Long) {
        val existing = db.workQueries.selectWork(workId).executeAsOneOrNull()
        if (existing != null) return
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
            added_at = nowMs,
            last_read_at = null,
        )
    }

    private fun chapterRowId(workId: String, sourceChapterId: String): String =
        workId + "|" + sourceChapterId
}

fun createChapterCacheRepository(db: ReaderDatabase): ChapterCacheRepository =
    ChapterCacheRepositoryImpl(db)
