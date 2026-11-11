package source.bridge

import source.contract.ContentKind
import source.hostapi.HostApi

/**
 * 源 JS 引擎（quickjs-kt 实例封装，架构 4.6）。
 * - 每个源一个实例（`memoryLimit = 32 MB` + `evaluationTimeoutMillis` 中断）；
 * - 单实例 confined 到单个串行 dispatcher（与 quickjs-kt 约束天然一致）；
 * - 销毁时先等待在途 Promise 收敛或强制中断。
 */
interface SourceEngine : AutoCloseable {
    /** anekdot：加载 `.js` → 类发现 → 实例化 → 读字段生成描述（M1 harness 为参考实现）。 */
    suspend fun load(script: String)

    /** 同步桥：仅本地白名单方法（KV/设置/日志/编码/HTML），见架构 4.6.1。 */
    fun callSync(method: String, payload: String): String

    /** 异步桥：`__hostCall(method, payload)` → Promise（网络/UI/图片变换）。 */
    suspend fun callAsync(method: String, payload: String, timeoutMs: Long = 15_000): String
}

/** 源运行时装配（HostApi 实现由宿主注入；此接口冻结装配形状）。 */
interface SourceRuntimeFactory {
    fun create(sourceKey: String, hostApi: HostApi): SourceEngine
}

/**
 * 类型化源调用（搜索引擎可 fake；[QuickJsEngine] 为唯一实现）。
 * 返回源原始 JSON（归一化在 provider 适配层，不在此）。
 */
interface SourceCalls {
    suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String
    suspend fun defaultSearchOptions(): String
    suspend fun loadWorkDetails(workId: String, kind: ContentKind): String
    suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind): String

    /**
     * 通用调用（`await __source.<path>(...args)` → JSON；契约增长与工具调试入口）。
     * path 仅允许标识符点分（P6：JS 侧输入校验）。
     */
    suspend fun invoke(path: String, argsJson: String): String
}
