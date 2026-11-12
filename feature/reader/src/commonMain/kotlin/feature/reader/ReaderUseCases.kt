package feature.reader

import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterProvider
import core.reader.ChapterRef
import data.repository.ProgressRepository

/**
 * 进度用例（ViewModel 只依赖用例，不直达 Repository）。
 * 单类单 `invoke`，无透传例外（bright-line 规则，可机械检查）。
 */
class SaveReadingProgressUseCase(
    private val repo: ProgressRepository,
) {
    suspend operator fun invoke(progress: ReadingProgress) {
        repo.save(progress)
    }
}

class LoadReadingProgressUseCase(
    private val repo: ProgressRepository,
) {
    suspend operator fun invoke(workId: String): ReadingProgress? =
        repo.load(workId)
}

/** 空进度仓（壳/预览模式：不持久化；真实仓由平台装配层注入）。 */
object NoopProgressRepository : ProgressRepository {
    override suspend fun save(progress: ReadingProgress) = Unit
    override suspend fun load(workId: String): ReadingProgress? = null
}

/**
 * 空章节源（无安装源时的诚实状态：目录为空，阅读器展示“暂无章节”错误。
 * C1 禁止本体内置任何站点/示例 URL；真实内容只能来自用户安装的源）。
 */
object NoopChapterProvider : ChapterProvider {
    override suspend fun chapters(workId: String): List<ChapterRef> = emptyList()

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
        throw IllegalStateException("无可用源")
}
