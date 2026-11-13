package app.shared

import core.model.Shelf
import core.model.workStableId
import data.db.ReaderDatabase
import data.repository.ShelfRepository
import data.repository.ShelfWork
import data.repository.createShelfRepository
import feature.search.SearchWorkUi
import feature.shelf.ShelfWorkUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import source.contract.ContentKind
import source.contract.Work

/**
 * 书架装配（核心数据链路，不依赖任何 UI）。
 *
 * 平台壳传入 SQLDelight DB；App 启动时 [ensureDefault]；后续详情/搜索页拿到
 * [SearchWorkUi] 后调 [addFromSearch] 即可落库，宿主 ID 由 `workStableId(sourceKey, workId)`
 * 生成（禁止用 URL 作主键，见架构 §5.1）。表现层（M5 书架屏）只需消费 [observe]。
 */
class ShelfBindings(
    private val repository: ShelfRepository,
    private val nowMs: () -> Long,
) {
    suspend fun ensureDefault() {
        repository.ensureShelf(
            Shelf(id = DEFAULT_SHELF_ID, name = "默认书架", ordinal = 0),
        )
    }

    fun observe(shelfId: String = DEFAULT_SHELF_ID): Flow<List<ShelfWork>> =
        repository.observeShelf(shelfId)

    /**
     * 书架屏消费的在线流（数据层 `ShelfWork` → 纯展示 `ShelfWorkUi`）。
     * [ShelfWorkUi.workId] 取源内作品 id（路由用），宿主稳定 id 不出展示层。
     */
    fun observeUi(shelfId: String = DEFAULT_SHELF_ID): Flow<List<ShelfWorkUi>> =
        observe(shelfId).map { works -> works.map { it.toUi() } }

    /** 从搜索/详情结果加入书架；重复加入由 work/stableId 的 upsert 保证幂等。 */
    suspend fun addFromSearch(work: SearchWorkUi, shelfId: String = DEFAULT_SHELF_ID) {
        val kind = if (work.kindLabel.equals("novel", ignoreCase = true)) {
            ContentKind.NOVEL
        } else {
            ContentKind.MANGA
        }
        repository.addToShelf(
            shelfId = shelfId,
            sourceKey = work.sourceKey,
            work = Work(
                id = work.workId,
                title = work.title,
                subtitle = work.subtitle.ifBlank { null },
                cover = work.cover,
                kind = kind,
            ),
            nowMs = nowMs(),
        )
    }

    /** 按宿主稳定 id 删除（数据层行主键；调用方需已持有 [ShelfWork.workId]）。 */
    suspend fun remove(workId: String, shelfId: String = DEFAULT_SHELF_ID) {
        repository.removeFromShelf(shelfId, workId)
    }

    /**
     * 按「源内作品 id + 归属源」删除（书架 UI 行的天然入参）。
     *
     * UI 行只暴露源内 id 与 sourceKey，宿主稳定 id 不出展示层；若直接把它传给
     * [remove] 会静默删不掉。此处在装配层重算 `workStableId`，保证闭环真的生效。
     */
    suspend fun removeFromSource(
        sourceKey: String,
        sourceWorkId: String,
        shelfId: String = DEFAULT_SHELF_ID,
    ) {
        repository.removeFromShelf(shelfId, workStableId(sourceKey, sourceWorkId))
    }

    companion object {
        const val DEFAULT_SHELF_ID = "default"
    }
}

/** 数据层书架行 → 书架屏 UI 行（映射收口在 App 层，feature 不依赖 data:repository）。 */
private fun ShelfWork.toUi(): ShelfWorkUi = ShelfWorkUi(
    workId = work.id,
    sourceKey = sourceKey,
    title = work.title,
    subtitle = work.subtitle.orEmpty(),
    cover = work.cover,
    kindLabel = work.kind.serial,
)

fun createShelfBindings(db: ReaderDatabase, nowMs: () -> Long): ShelfBindings =
    ShelfBindings(createShelfRepository(db), nowMs)
