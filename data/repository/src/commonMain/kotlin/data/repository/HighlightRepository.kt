package data.repository

import core.model.Highlight
import data.db.ReaderDatabase

/**
 * 划线仓库（M4 段落级 MVP：以块为单位存取字符区间）。
 *
 * 语义：`id = "$workId:$chapterId:$start-$end"` 确定性主键，重复划线为替换；
 * 区间为规范化全文坐标（与进度锚点/书签同系，重排后由分页映射定位）。
 */
interface HighlightRepository {
    suspend fun list(workId: String, chapterId: String): List<Highlight>
    suspend fun upsert(highlight: Highlight)
    suspend fun remove(id: String)
}

internal class HighlightRepositoryImpl(private val db: ReaderDatabase) : HighlightRepository {
    override suspend fun list(workId: String, chapterId: String): List<Highlight> =
        db.highlightQueries.selectHighlights(workId, chapterId).executeAsList().map { it.toDomain() }

    override suspend fun upsert(highlight: Highlight) {
        db.highlightQueries.upsertHighlight(
            id = highlight.id,
            work_id = highlight.workId,
            chapter_id = highlight.chapterId,
            start = highlight.start.toLong(),
            end = highlight.end.toLong(),
            excerpt = highlight.excerpt,
            note = highlight.note,
            created_at = highlight.createdAtEpochMs,
        )
    }

    override suspend fun remove(id: String) {
        db.highlightQueries.deleteHighlight(id)
    }

    private fun data.db.Highlight.toDomain(): Highlight = Highlight(
        id = id,
        workId = work_id,
        chapterId = chapter_id,
        start = start.toInt(),
        end = end.toInt(),
        excerpt = excerpt,
        note = note,
        createdAtEpochMs = created_at,
    )
}

fun createHighlightRepository(db: ReaderDatabase): HighlightRepository =
    HighlightRepositoryImpl(db)

/** 无持久化实现（预览/单测默认）。 */
object NoopHighlightRepository : HighlightRepository {
    override suspend fun list(workId: String, chapterId: String): List<Highlight> = emptyList()
    override suspend fun upsert(highlight: Highlight): Unit = Unit
    override suspend fun remove(id: String): Unit = Unit
}
