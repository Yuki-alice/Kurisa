package data.repository

import core.model.Bookmark
import data.db.ReaderDatabase

/**
 * 书签仓库（M4：`bookmark.position` 复用为字符锚点，重排/换设备后可定位）。
 *
 * 语义：每章节最多一条书签（`id = "$workId:$chapterId"` 确定性主键，重复 toggle 为替换）。
 * `excerpt` 仅存正文片段（上限 120 字，本地 DB，不出设备，见 privacy-audit 数据最小化）。
 *
 * 单一消费者阶段不预拆 `:api`（沿 M1 约定）：接口与实现同模块，实现类 internal。
 */
interface BookmarkRepository {
    suspend fun findByChapter(workId: String, chapterId: String): Bookmark?
    suspend fun listForWork(workId: String): List<Bookmark>
    suspend fun upsert(bookmark: Bookmark)
    suspend fun removeByChapter(workId: String, chapterId: String)
    suspend fun remove(id: String)
}

internal class BookmarkRepositoryImpl(private val db: ReaderDatabase) : BookmarkRepository {
    override suspend fun findByChapter(workId: String, chapterId: String): Bookmark? =
        db.bookmarkQueries.selectBookmarks(workId).executeAsList()
            .firstOrNull { it.chapter_id == chapterId }?.toDomain()

    override suspend fun listForWork(workId: String): List<Bookmark> =
        db.bookmarkQueries.selectBookmarks(workId).executeAsList().map { it.toDomain() }

    override suspend fun upsert(bookmark: Bookmark) {
        db.bookmarkQueries.upsertBookmark(
            id = bookmark.id,
            work_id = bookmark.workId,
            chapter_id = bookmark.chapterId,
            position = bookmark.position.toLong(),
            excerpt = bookmark.excerpt,
            note = bookmark.note,
            created_at = bookmark.createdAtEpochMs,
        )
    }

    override suspend fun removeByChapter(workId: String, chapterId: String) {
        db.bookmarkQueries.deleteBookmarksByChapter(workId, chapterId)
    }

    override suspend fun remove(id: String) {
        db.bookmarkQueries.deleteBookmark(id)
    }

    private fun data.db.Bookmark.toDomain(): Bookmark = Bookmark(
        id = id,
        workId = work_id,
        chapterId = chapter_id,
        position = position.toInt(),
        excerpt = excerpt,
        note = note,
        createdAtEpochMs = created_at,
    )
}

fun createBookmarkRepository(db: ReaderDatabase): BookmarkRepository =
    BookmarkRepositoryImpl(db)

/** 无持久化实现（预览/单测默认，保证既有调用方零改动）。 */
object NoopBookmarkRepository : BookmarkRepository {
    override suspend fun findByChapter(workId: String, chapterId: String): Bookmark? = null
    override suspend fun listForWork(workId: String): List<Bookmark> = emptyList()
    override suspend fun upsert(bookmark: Bookmark): Unit = Unit
    override suspend fun removeByChapter(workId: String, chapterId: String): Unit = Unit
    override suspend fun remove(id: String): Unit = Unit
}
