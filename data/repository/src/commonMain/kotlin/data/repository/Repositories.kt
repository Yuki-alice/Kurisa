package data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import core.model.DownloadStatus
import core.model.DownloadTask
import core.model.ReadingProgress
import core.model.Shelf
import core.model.workStableId
import data.db.ReaderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import source.contract.ContentKind
import source.contract.Work

/**
 * 书架仓库（离线优先，cache-first）。
 *
 * Source of truth：本地 `work` / `shelf_item`（SQLDelight Flow）。
 * - `observeShelf` 纯响应式，永不触发网络（repository skill 反模式 #3）；
 * - `refresh` 失败不得清空缓存；
 * - DTO/Entity 不出 `:data`（mapper 在此边界收口）。
 *
 * 单一消费者阶段不预拆 `:api`（见 M1 CONTRACT-REVIEW）：接口与实现同模块，
 * 实现类 internal，对外只暴露接口 + 工厂。
 */
interface ShelfRepository {
    fun observeShelf(shelfId: String): Flow<List<ShelfWork>>
    suspend fun addToShelf(shelfId: String, sourceKey: String, work: Work, nowMs: Long)
    suspend fun removeFromShelf(shelfId: String, workId: String)
    suspend fun ensureShelf(shelf: Shelf)
}

/** 书架展示条目（契约 Work + 本地元数据，跨层组装体）。 */
data class ShelfWork(
    val work: Work,
    val workId: String,
    val addedAtEpochMs: Long,
    val lastReadAtEpochMs: Long?,
)

internal class ShelfRepositoryImpl(
    private val db: ReaderDatabase,
) : ShelfRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun observeShelf(shelfId: String): Flow<List<ShelfWork>> =
        db.shelfItemQueries.selectShelfItems(shelfId)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { items ->
                items.mapNotNull { item ->
                    val row = db.workQueries.selectWork(item.work_id).executeAsOneOrNull()
                        ?: return@mapNotNull null
                    ShelfWork(
                        work = row.toContractWork(),
                        workId = row.id,
                        addedAtEpochMs = item.added_at,
                        lastReadAtEpochMs = row.last_read_at,
                    )
                }
            }

    override suspend fun addToShelf(shelfId: String, sourceKey: String, work: Work, nowMs: Long) {
        // 宿主 ID 规则：禁止用 URL 作主键
        val stableId = workStableId(sourceKey, work.id)
        db.workQueries.upsertWork(
            id = stableId,
            source_key = sourceKey,
            source_work_id = work.id,
            kind = work.kind.serial,
            title = work.title,
            subtitle = work.subtitle,
            cover = work.cover,
            description = work.description,
            tags_json = json.encodeToString(work.tags),
            stars = null,
            language = work.language,
            url = null,
            added_at = nowMs,
            last_read_at = null,
        )
        db.shelfItemQueries.addToShelf(shelfId, stableId, nowMs)
    }

    override suspend fun removeFromShelf(shelfId: String, workId: String) {
        db.shelfItemQueries.removeFromShelf(shelfId, workId)
    }

    override suspend fun ensureShelf(shelf: Shelf) {
        db.shelfQueries.upsertShelf(shelf.id, shelf.name, shelf.ordinal.toLong(), shelf.sourceFolderId)
    }

    private fun data.db.Work.toContractWork(): Work = Work(
        id = source_work_id,
        title = title,
        subtitle = subtitle,
        cover = cover,
        tags = runCatching {
            json.decodeFromString<List<String>>(tags_json)
        }.getOrElse { emptyList() },
        description = description,
        language = language,
        kind = ContentKind.of(kind) ?: ContentKind.MANGA,
    )
}

/** 进度仓库（last-write-wins，synced 供未来同步）。 */
interface ProgressRepository {
    suspend fun save(progress: ReadingProgress)
    suspend fun load(workId: String): ReadingProgress?
}

internal class ProgressRepositoryImpl(private val db: ReaderDatabase) : ProgressRepository {
    override suspend fun save(progress: ReadingProgress) {
        db.progressQueries.upsertProgress(
            work_id = progress.workId,
            chapter_id = progress.chapterId,
            page_index = progress.pageIndex?.toLong(),
            scroll_offset = progress.scrollOffset?.toDouble(),
            percent = progress.percent.toDouble(),
            updated_at = progress.updatedAtEpochMs,
            synced = if (progress.synced) 1L else 0L,
        )
    }

    override suspend fun load(workId: String): ReadingProgress? {
        val row = db.progressQueries.selectProgress(workId).executeAsOneOrNull() ?: return null
        return ReadingProgress(
            workId = row.work_id,
            chapterId = row.chapter_id,
            pageIndex = row.page_index?.toInt(),
            scrollOffset = row.scroll_offset?.toFloat(),
            percent = row.percent.toFloat(),
            updatedAtEpochMs = row.updated_at,
            synced = row.synced == 1L,
        )
    }
}

fun createShelfRepository(db: ReaderDatabase): ShelfRepository = ShelfRepositoryImpl(db)
fun createProgressRepository(db: ReaderDatabase): ProgressRepository = ProgressRepositoryImpl(db)

/** 下载任务仓库（任务状态落 `download_task`，执行引擎见 `:data:download`）。 */
interface DownloadTaskRepository {
    fun observeTasks(): Flow<List<DownloadTask>>
    suspend fun upsert(task: DownloadTask)
    suspend fun delete(id: String)
}

internal class DownloadTaskRepositoryImpl(private val db: ReaderDatabase) : DownloadTaskRepository {
    override fun observeTasks(): Flow<List<DownloadTask>> =
        db.downloadQueries.selectDownloads()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map { row ->
                    DownloadTask(
                        id = row.id,
                        workId = row.work_id,
                        chapterId = row.chapter_id,
                        status = runCatching { DownloadStatus.valueOf(row.status) }
                            .getOrElse { DownloadStatus.QUEUED },
                        priority = row.priority.toInt(),
                        done = row.done,
                        total = row.total,
                        error = row.error,
                        createdAtEpochMs = row.created_at,
                    )
                }
            }

    override suspend fun upsert(task: DownloadTask) {
        db.downloadQueries.upsertDownload(
            id = task.id,
            work_id = task.workId,
            chapter_id = task.chapterId,
            status = task.status.name,
            priority = task.priority.toLong(),
            done = task.done,
            total = task.total,
            error = task.error,
            created_at = task.createdAtEpochMs,
        )
    }

    override suspend fun delete(id: String) {
        db.downloadQueries.deleteDownload(id)
    }
}

fun createDownloadTaskRepository(db: ReaderDatabase): DownloadTaskRepository =
    DownloadTaskRepositoryImpl(db)
