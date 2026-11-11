package data.download

import core.model.DownloadStatus
import core.model.DownloadTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 下载编排（Ketch 执行引擎接入前，以内存实现跑通优先级语义）。
 * 优先级：用户手动（priority 高）> 阅读预下载 > 后台批量；同级按创建时间。
 */
interface DownloadController {
    val tasks: StateFlow<List<DownloadTask>>
    suspend fun enqueue(task: DownloadTask)
    suspend fun pause(id: String)
    suspend fun resume(id: String)
    suspend fun cancel(id: String)
}

/** 内存实现（测试/预览用；生产替换为 Ketch 封装，接口不变）。 */
class InMemoryDownloadController : DownloadController {
    private val _tasks = MutableStateFlow(emptyList<DownloadTask>())
    override val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    override suspend fun enqueue(task: DownloadTask) {
        _tasks.update { list ->
            (list.filterNot { it.id == task.id } + task)
                .sortedWith(compareByDescending<DownloadTask> { it.priority }.thenBy { it.createdAtEpochMs })
        }
    }

    override suspend fun pause(id: String) = setStatus(id, DownloadStatus.PAUSED)

    override suspend fun resume(id: String) {
        setStatus(id, DownloadStatus.QUEUED)
    }

    override suspend fun cancel(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    private fun setStatus(id: String, status: DownloadStatus) {
        _tasks.update { list ->
            list.map { if (it.id == id) it.copy(status = status) else it }
        }
    }
}
