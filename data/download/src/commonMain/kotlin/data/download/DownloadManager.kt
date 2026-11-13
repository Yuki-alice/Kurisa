package data.download

import core.model.DownloadStatus
import core.model.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * 任务持久化口（与 `:data:repository` 的 `DownloadTaskRepository` 同形）。
 * 由 App 层做三行委托适配，避免 `:data:download` 反向依赖 `:data:repository` / SQLDelight。
 */
interface DownloadTaskStore {
    fun observeTasks(): Flow<List<DownloadTask>>
    suspend fun upsert(task: DownloadTask)
    suspend fun delete(id: String)
}

/** 内存 store（预览 / 单测用；生产由 App 层委托给 SQLDelight 实现）。 */
class InMemoryDownloadTaskStore : DownloadTaskStore {
    private val _flow = MutableStateFlow(emptyList<DownloadTask>())
    override fun observeTasks(): Flow<List<DownloadTask>> = _flow
    override suspend fun upsert(task: DownloadTask) {
        _flow.update { list -> list.filterNot { it.id == task.id } + task }
    }
    override suspend fun delete(id: String) {
        _flow.update { list -> list.filterNot { it.id == id } }
    }
}

/**
 * 分片落盘口（平台壳注入：桌面 / Android 各写自己的目录，不新增 expect/actual）。
 * 分片粒度 = 章节内一张图片；暂停/重启后靠 [completedParts] 的**真实索引集合**跳过已落盘分片续传。
 *
 * BE-B7：返回 `Set<Int>` 而非数量，取消「分片从 0 连续」的隐含假设；
 * 并发落盘会产生稀疏、乱序的索引（例如先完成 2、0），实现方必须返回实际存在的分片号。
 */
interface DownloadSink {
    suspend fun completedParts(taskId: String): Set<Int>
    suspend fun completedBytes(taskId: String): Long
    suspend fun append(taskId: String, partIndex: Int, bytes: ByteArray)
    suspend fun discard(taskId: String)
}

/** 内存 sink（单测 / 预览用）。 */
class InMemoryDownloadSink : DownloadSink {
    private val parts = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    override suspend fun completedParts(taskId: String): Set<Int> =
        parts[taskId]?.keys?.toSet() ?: emptySet()
    override suspend fun completedBytes(taskId: String): Long =
        parts[taskId]?.values?.sumOf { it.size.toLong() } ?: 0L
    override suspend fun append(taskId: String, partIndex: Int, bytes: ByteArray) {
        parts.getOrPut(taskId) { mutableMapOf() }[partIndex] = bytes
    }
    override suspend fun discard(taskId: String) {
        parts.remove(taskId)
    }
    fun bytesOf(taskId: String, partIndex: Int): ByteArray? = parts[taskId]?.get(partIndex)
}

private enum class PartOutcome { WRITTEN, SKIPPED }

/**
 * 下载编排：把「队列 / 优先级」「任务持久化」「Range 字节抓取」「分片落盘」接成一条链路。
 *
 * 语义（不依赖任何 UI）：
 * - enqueue → 落库 QUEUED → 按 priority 降序、同级按创建时间升序串行消费；
 * - 每片经 [RangeDownloadExecutor] 抓取 → [DownloadSink.append] 落盘 → 进度回写 store；
 * - BE-B7：任务内分片可配置 [concurrency] 并发，[Semaphore] 限流；乱序完成不丢片，
 *   进度累计由 [Mutex] 串行化；已完成分片按真实索引集合跳过，稀疏断点也能续传；
 * - pause 是**分片边界协作式暂停**，已落盘分片保留；resume 按 [DownloadSink.completedParts] 续传；
 *   暂停/取消发生在落盘之后时，不得把 PAUSED 覆盖成 RUNNING，也不得写 DONE；
 * - cancel 删除任务并清空分片；resolveUrls 抛错或返回空 → FAILED 且带 error，不吞错；
 * - 重启恢复：遗留 RUNNING 重置为 QUEUED 后重新 drain，断点由 sink 决定。
 *
 * [tasks] 是 store 的同步镜像（每次写入后立刻刷新），不依赖常驻 Flow collect，
 * 因此调用方读到的一定是本次写入后的状态。[runPending] 供测试与手动触发确定性执行。
 *
 * [resolveUrls] 由 App 层注入（章节 → 图片 URL 列表），本模块因此不依赖 ChapterProvider。
 * [concurrency] 默认 1 保持单测/预览的确定性；生产工厂 `createDownloadController` 默认 4。
 */
class DownloadManager(
    private val store: DownloadTaskStore,
    private val fetcher: ByteFetcher,
    private val sink: DownloadSink,
    private val resolveUrls: suspend (DownloadTask) -> List<String>,
    private val domains: () -> Set<String> = { emptySet() },
    private val concurrency: Int = 1,
    scope: CoroutineScope? = null,
) : DownloadController, AutoCloseable {

    private val ownsScope = scope == null
    private val scope: CoroutineScope = scope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val drainLock = Mutex()

    /** 被 pause / cancel 的任务 id（原子快照，跨 UI 协程与 worker 可见）。 */
    private val stopped = MutableStateFlow<Set<String>>(emptySet())
    private val cancelled = MutableStateFlow<Set<String>>(emptySet())
    private val _tasks = MutableStateFlow(emptyList<DownloadTask>())

    override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    init {
        this.scope.launch { runPending() }
    }

    /** 显式跑一轮「重置遗留 RUNNING + 消费队列」；生产由 init / kick 自动触发，测试可直接调用。 */
    suspend fun runPending() {
        restorePending()
        drain()
    }

    private suspend fun restorePending() {
        // 进程被杀后 RUNNING 永远停在中途：重启即回到队列，分片由 sink 续传。
        store.observeTasks().first()
            .filter { it.status == DownloadStatus.RUNNING }
            .forEach { persist(it.copy(status = DownloadStatus.QUEUED)) }
    }

    private suspend fun persist(task: DownloadTask) {
        store.upsert(task)
        _tasks.value = store.observeTasks().first()
    }

    private suspend fun remove(id: String) {
        store.delete(id)
        _tasks.value = store.observeTasks().first()
    }

    override suspend fun enqueue(task: DownloadTask) {
        stopped.update { it - task.id }
        cancelled.update { it - task.id }
        persist(task.copy(status = DownloadStatus.QUEUED))
        kick()
    }

    override suspend fun pause(id: String) {
        val task = store.observeTasks().first().firstOrNull { it.id == id } ?: return
        stopped.update { it + id }
        persist(task.copy(status = DownloadStatus.PAUSED))
    }

    override suspend fun resume(id: String) {
        val task = store.observeTasks().first().firstOrNull { it.id == id } ?: return
        stopped.update { it - id }
        persist(task.copy(status = DownloadStatus.QUEUED))
        kick()
    }

    override suspend fun cancel(id: String) {
        stopped.update { it + id }
        cancelled.update { it + id }
        remove(id)
        sink.discard(id)
    }

    private fun kick() {
        this.scope.launch { drain() }
    }

    private fun isStopped(id: String): Boolean = id in stopped.value || id in cancelled.value

    /** 串行消费队列；每次都从 store 读最新快照，避免状态滞后导致重复拾取。 */
    private suspend fun drain() = drainLock.withLock {
        while (true) {
            val next = store.observeTasks().first()
                .filter { it.status == DownloadStatus.QUEUED }
                .sortedWith(
                    compareByDescending<DownloadTask> { it.priority }
                        .thenBy { it.createdAtEpochMs },
                )
                .firstOrNull() ?: break
            execute(next)
        }
    }

    private suspend fun execute(task: DownloadTask) {
        persist(task.copy(status = DownloadStatus.RUNNING))
        try {
            val urls = resolveUrls(task)
            if (urls.isEmpty()) {
                persist(task.copy(status = DownloadStatus.FAILED, error = "章节无可下载内容"))
                return
            }
            // 真实索引集合：并发落盘形成的稀疏/乱序断点不会重复抓取、也不会漏片。
            val completed = sink.completedParts(task.id)
            val pending = (0 until urls.size).filter { it !in completed }
            var done = sink.completedBytes(task.id)
            var total = 0L
            val progressLock = Mutex()
            val permits = Semaphore(concurrency.coerceAtLeast(1))

            val outcomes: List<PartOutcome> = coroutineScope {
                pending.map { index ->
                    async {
                        permits.withPermit {
                            if (isStopped(task.id)) return@withPermit PartOutcome.SKIPPED
                            val result = RangeDownloadExecutor(fetcher)
                                .download(urls[index], domains(), 0)
                            // 抓取途中暂停/取消：本片不落盘，已落盘分片保留供续传。
                            if (isStopped(task.id)) return@withPermit PartOutcome.SKIPPED
                            sink.append(task.id, index, result.bytes)
                            progressLock.withLock {
                                done += result.bytes.size
                                total = result.total ?: total
                                // 暂停可能发生在落盘之后：不得把 PAUSED 覆盖成 RUNNING。
                                if (!isStopped(task.id)) {
                                    persist(
                                        task.copy(
                                            status = DownloadStatus.RUNNING,
                                            done = done,
                                            total = total,
                                        ),
                                    )
                                }
                            }
                            PartOutcome.WRITTEN
                        }
                    }
                }.awaitAll()
            }
            if (outcomes.any { it == PartOutcome.SKIPPED } || isStopped(task.id)) return
            persist(task.copy(status = DownloadStatus.DONE, done = done, total = total, error = null))
        } catch (t: Throwable) {
            persist(task.copy(status = DownloadStatus.FAILED, error = t.message ?: "下载失败"))
        } finally {
            // cancel 与在途分片可能竞态：所有 async 已 awaitAll 结束后再清一次，保证不留孤儿文件。
            if (task.id in cancelled.value) sink.discard(task.id)
        }
    }

    override fun close() {
        if (ownsScope) this.scope.cancel()
    }
}
