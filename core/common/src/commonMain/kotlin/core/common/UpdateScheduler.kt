package core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 更新调度器（BE-B3；纯 Kotlin，无平台依赖，不联网）。
 *
 * 轮询编排：按源列出已缓存作品 → 本地目录指纹 vs 远端目录指纹 → 产出更新候选。
 * 数据访问全部经 [UpdateSource] 端口（适配器在 :data:repository，见 BE-B2 的
 * ChapterCacheRepository），平台触发经 [UpdateTrigger] 注入（Android WorkManager /
 * 桌面定时器由壳实现，默认 [IntervalUpdateTrigger]）。ADR-006：不新增 expect/actual。
 *
 * 并发策略：按作品顺序扫描（远端 polite + 结果确定性），单作品失败只记 error、不
 * 中断整轮；[CancellationException] 永远原样抛出（结构化并发，不误判为源错误）。
 */
data class ChapterStamp(
    val id: String,
    val title: String,
    val updatedAtEpochMs: Long,
)

/** 更新扫描的数据出口（适配器在数据层实现；调度器本身不联网、不读库）。 */
interface UpdateSource {
    /** 本轮要扫描的源（启用源）。 */
    suspend fun activeSourceKeys(): List<String>

    /** 源内已缓存章节目录的作品 id。 */
    suspend fun cachedWorkIds(sourceKey: String): List<String>

    /** 本地已缓存的章节指纹（无缓存返回空列表）。 */
    suspend fun localChapters(sourceKey: String, workId: String): List<ChapterStamp>

    /** 远端目录指纹（失败抛异常，由调度器记入 errors）。 */
    suspend fun remoteChapters(sourceKey: String, workId: String): List<ChapterStamp>
}

/** 单部作品的更新候选。 */
data class UpdateCandidate(
    val sourceKey: String,
    val workId: String,
    val addedChapterIds: List<String>,
    val removedChapterIds: List<String>,
    val latestUpdatedAtEpochMs: Long,
)

/** 一轮扫描结果（按 sourceKey/workId 排序，三端一致）。 */
data class UpdateScan(
    val scannedWorks: Int,
    val candidates: List<UpdateCandidate>,
    val errors: List<String>,
)

/**
 * 平台触发（壳实现：到点返回；取消时抛 CancellationException，run 随即结束）。
 * 默认实现见 [IntervalUpdateTrigger]。
 */
interface UpdateTrigger {
    suspend fun awaitNextTick()
}

/** 协程定时触发（测试用虚拟时间驱动；生产由壳换成平台调度器）。 */
class IntervalUpdateTrigger(private val everyMs: Long) : UpdateTrigger {
    override suspend fun awaitNextTick() {
        delay(everyMs)
    }
}

class UpdateScheduler(
    private val source: UpdateSource,
    private val trigger: UpdateTrigger = IntervalUpdateTrigger(6 * 60 * 60 * 1000L),
) {

    /** 单轮扫描（可单独调用做手动刷新；失败隔离到作品粒度）。 */
    suspend fun scanOnce(): UpdateScan {
        val keys = try {
            source.activeSourceKeys()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return UpdateScan(0, emptyList(), listOf("sources: " + e.message))
        }
        var scanned = 0
        val candidates = mutableListOf<UpdateCandidate>()
        val errors = mutableListOf<String>()
        for (key in keys) {
            val works = try {
                source.cachedWorkIds(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += key + ": " + e.message
                continue
            }
            for (workId in works) {
                scanned++
                try {
                    val local = source.localChapters(key, workId)
                    val remote = source.remoteChapters(key, workId)
                    val (added, removed) = diffChapters(local, remote)
                    if (added.isNotEmpty() || removed.isNotEmpty()) {
                        candidates += UpdateCandidate(
                            sourceKey = key,
                            workId = workId,
                            addedChapterIds = added,
                            removedChapterIds = removed,
                            latestUpdatedAtEpochMs = remote.maxOfOrNull { it.updatedAtEpochMs } ?: 0L,
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += key + "/" + workId + ": " + e.message
                }
            }
        }
        candidates.sortWith(compareBy({ it.sourceKey }, { it.workId }))
        return UpdateScan(scanned, candidates, errors)
    }

    /** 常驻循环：每次 tick 扫一轮并回调；取消即结束（不吞 CancellationException）。 */
    suspend fun run(onScan: suspend (UpdateScan) -> Unit = {}) {
        while (currentCoroutineContext().isActive) {
            trigger.awaitNextTick()
            onScan(scanOnce())
        }
    }

    companion object {
        /** 纯函数：本地指纹 vs 远端指纹（单测与调度器共用同一判定）。 */
        fun diffChapters(
            local: List<ChapterStamp>,
            remote: List<ChapterStamp>,
        ): Pair<List<String>, List<String>> {
            val localIds = local.map { it.id }.toSet()
            val remoteIds = remote.map { it.id }.toSet()
            return Pair(
                remote.filter { it.id !in localIds }.map { it.id },
                localIds.filter { it !in remoteIds },
            )
        }
    }
}
