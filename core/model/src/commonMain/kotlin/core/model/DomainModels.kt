package core.model

import kotlinx.serialization.Serializable

/**
 * M2 领域模型（持久化侧）。
 *
 * 放置说明（偏离架构图的记录，与 M1 的 CONTRACT-REVIEW 同例）：
 * - `:source:contract` 已冻结（Work/Chapter/ContentKind/SourceMeta），不可搬移；
 * - `:core:model` 只定义持久化侧新增类型（书架/进度/下载/书签/源记录/章节载荷），
 *   不重复定义契约类型，避免两套 Work 漂移；
 * - `:core:model` 不依赖 `:source:contract`（保持 core 独立，依赖规则见架构 3.1）；
 *   两者的映射收口在 `:data:repository`（见 Mappers）。
 * - 全部纯 Kotlin，零平台依赖；时间一律 epoch millis（Long），SQLite INTEGER。
 */

/** 书架分类（默认书架 id = "default"）。 */
@Serializable
data class Shelf(
    val id: String,
    val name: String,
    val ordinal: Int = 0,
    val sourceFolderId: String? = null,
)

/** 书架条目（JOIN 壳，落 `shelf_item`）。 */
@Serializable
data class ShelfItem(
    val shelfId: String,
    val workId: String,
    val addedAtEpochMs: Long,
)

/** 阅读进度（last-write-wins，`synced` 供后续端到端同步，本期不实现服务端）。 */
@Serializable
data class ReadingProgress(
    val workId: String,
    val chapterId: String,
    val pageIndex: Int? = null,
    val scrollOffset: Float? = null,
    val percent: Float = 0f,
    val updatedAtEpochMs: Long,
    val synced: Boolean = false,
    /** 小说正文字符锚点（-1 = 漫画/未启用）；重排后据此恢复位置。 */
    val charOffset: Int = -1,
    /** 生成该锚点时的排版键（LayoutKey.signature）。 */
    val layoutKey: String = "",
) {
    init {
        require(percent in 0f..1f) { "percent 越界: $percent" }
    }
}

/** 下载状态（执行引擎为 Ketch，宿主只做编排与持久化，见架构第 8 章）。 */
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

@Serializable
data class DownloadTask(
    val id: String,
    val workId: String,
    val chapterId: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val priority: Int = 0,
    val done: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val createdAtEpochMs: Long,
)

/** 书签/笔记（排版分页后的 position，excerpt 脱敏不含 Cookie）。 */
@Serializable
data class Bookmark(
    val id: String,
    val workId: String,
    val chapterId: String,
    val position: Int,
    val excerpt: String? = null,
    val note: String? = null,
    val createdAtEpochMs: Long,
)

/** 划线（块字符区间，锚点坐标与进度/书签同系；点选整段/长按圈词/搜索批量已落地，手势拖拽待后续）。 */
@Serializable
data class Highlight(
    val id: String,
    val workId: String,
    val chapterId: String,
    val start: Int,
    val end: Int,
    val excerpt: String? = null,
    val createdAtEpochMs: Long,
    val note: String? = null,
)

/** 本地书（M4 导入后与在线作品同构展示，来源标记为 local）。 */
@Serializable
data class LocalBook(
    val id: String,
    val path: String,
    val format: String,
    val title: String,
    val cover: String? = null,
    val importedAtEpochMs: Long,
)

/** 源安装记录（落 `source` 表，脚本本体由调用方经 FileKit 落盘，本模块不碰文件）。 */
@Serializable
data class SourceRecord(
    val key: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val kindsJson: String = "[]",
    val capabilitiesJson: String = "[]",
    val domainsJson: String = "[]",
    val installPath: String = "",
    val sha256: String = "",
    val signatureOk: Boolean = false,
    val enabled: Boolean = true,
    val settingsJson: String = "{}",
    val updatedAtEpochMs: Long,
)
