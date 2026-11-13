package app.shared

import core.model.DownloadTask
import data.db.ReaderDatabase
import data.download.ByteFetcher
import data.download.DownloadController
import data.download.DownloadManager
import data.download.DownloadSink
import data.download.DownloadTaskStore
import data.repository.DownloadTaskRepository
import data.repository.createDownloadTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * 把 `:data:repository` 的 SQLDelight 仓库适配成 `:data:download` 的持久化口，
 * 保持下载模块不反向依赖数据层（模块边界见 `docs/architecture.md` §3.1.4）。
 */
private class SqlDelightDownloadStore(
    private val repo: DownloadTaskRepository,
) : DownloadTaskStore {
    override fun observeTasks(): Flow<List<DownloadTask>> = repo.observeTasks()
    override suspend fun upsert(task: DownloadTask) = repo.upsert(task)
    override suspend fun delete(id: String) = repo.delete(id)
}

/**
 * 平台无关的落盘适配：平台壳只需提供 4 个文件操作，
 * 不新增 `expect/actual`（ADR-006），三端各自写自己的目录。
 */
class CallbackDownloadSink(
    private val listParts: suspend (String) -> List<Int>,
    private val readBytes: suspend (String) -> Long,
    private val writePart: suspend (String, Int, ByteArray) -> Unit,
    private val clear: suspend (String) -> Unit,
) : DownloadSink {
    override suspend fun completedParts(taskId: String): Int = listParts(taskId).size
    override suspend fun completedBytes(taskId: String): Long = readBytes(taskId)
    override suspend fun append(taskId: String, partIndex: Int, bytes: ByteArray) =
        writePart(taskId, partIndex, bytes)
    override suspend fun discard(taskId: String) = clear(taskId)
}

/**
 * 生产下载编排工厂（composition root）。
 *
 * [resolveUrls] 由平台壳注入「章节 → 图片 URL」；[domains] 取当前启用源白名单，
 * 图片请求仍走 `KtorRangeFetcher` 的首跳 + 逐跳白名单复核（安全红线不放宽）。
 * 构造即自动恢复遗留 RUNNING 任务并按 sink 已落盘分片续传。
 */
fun createDownloadController(
    db: ReaderDatabase,
    fetcher: ByteFetcher,
    sink: DownloadSink,
    resolveUrls: suspend (DownloadTask) -> List<String>,
    domains: () -> Set<String>,
    scope: CoroutineScope? = null,
): DownloadController = DownloadManager(
    store = SqlDelightDownloadStore(createDownloadTaskRepository(db)),
    fetcher = fetcher,
    sink = sink,
    resolveUrls = resolveUrls,
    domains = domains,
    scope = scope,
)
