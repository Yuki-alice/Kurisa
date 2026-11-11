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

/** 空进度仓（壳/预览模式：不持久化；M3 接线传入真实仓）。 */
internal object NoopProgressRepository : ProgressRepository {
    override suspend fun save(progress: ReadingProgress) = Unit
    override suspend fun load(workId: String): ReadingProgress? = null
}

/**
 * 演示章节源（picsum 真图，可点通全链路抓取→渲染→缩放；无鉴权场景）。
 * M-bridge（源 bridge 落地）替换即删；domains 由调用方显式传入。
 */
object DemoChapterProvider : ChapterProvider {
    val domains: Set<String> = setOf("picsum.photos")

    private val chapters = listOf(
        ChapterRef("c1", "第1话", 1),
        ChapterRef("c2", "第2话", 2),
    )

    override suspend fun chapters(workId: String): List<ChapterRef> = chapters

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
        ChapterPayload.Images(
            List(3) { i -> "https://picsum.photos/seed/$workId-$chapterId-$i/800/1200" },
        )
}
