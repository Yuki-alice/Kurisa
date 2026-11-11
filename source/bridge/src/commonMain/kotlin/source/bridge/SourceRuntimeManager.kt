package source.bridge

import data.network.CookieStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import source.contract.ContractPolicy
import source.contract.SourceCapability
import source.contract.SourceDescriptor
import source.contract.SourceErrorKind
import source.hostapi.HostApi

/** 装配一个源所需的全部外部输入；引擎不读文件、不碰数据库、不预置站点逻辑（C1）。 */
data class PluginRuntimeSpec(
    val key: String,
    val script: String,
    /** 运行期网络白名单，由安装头块/仓库索引/用户确认给出，脚本自报值不采信。 */
    val domains: Set<String>,
    val capabilities: Set<SourceCapability> = emptySet(),
    val settingsJson: String = "{}",
    val apiVersion: Int = ContractPolicy.CURRENT_API_VERSION,
    val appVersion: String = "0.0.0",
    val locale: String = "zh_CN",
    val platform: String = "common",
) {
    /** 版本/脚本/域名/设置变化都要重建运行时（禁用了旧实例后重新探测描述符）。 */
    fun signature(): String = buildString {
        append(script.length).append(':').append(script.hashCode())
        append('|').append(apiVersion)
        append('|').append(domains.sorted().joinToString(","))
        append('|').append(capabilities.map { it.serial }.sorted().joinToString(","))
        append('|').append(settingsJson.hashCode())
    }
}

interface PluginEngineFactory {
    fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine
}

/** 每个源一份 HostApi（网络出口按源绑定不可变域名白名单，防运行时篡改）。 */
fun interface PluginHostProvider {
    fun hostFor(spec: PluginRuntimeSpec): HostApi
}

class QuickJsPluginEngineFactory(
    private val cookies: CookieStore,
    private val dispatchers: SourceDispatchers = DefaultSourceDispatchers(),
    private val memoryLimitMb: Long = 32,
    private val evalTimeoutMs: Long = 10_000,
    private val nowMs: () -> Long = { 0L },
) : PluginEngineFactory {
    override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine = QuickJsEngine(
        sourceKey = spec.key,
        host = host,
        cookies = cookies,
        dispatchers = dispatchers,
        memoryLimitMb = memoryLimitMb,
        evalTimeoutMs = evalTimeoutMs,
        appVersion = spec.appVersion,
        locale = spec.locale,
        platform = spec.platform,
        nowMs = nowMs,
    )
}

/**
 * v4 原生路由最小实现（E2b）：复用 QuickJsEngine 但开 strictV4，
 * 未转正密码学显式 CONTRACT，垫片行为不变。
 */
class KurisaNativePluginEngineFactory(
    private val cookies: CookieStore,
    private val dispatchers: SourceDispatchers = DefaultSourceDispatchers(),
    private val memoryLimitMb: Long = 32,
    private val evalTimeoutMs: Long = 10_000,
    private val nowMs: () -> Long = { 0L },
) : PluginEngineFactory {
    override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine = QuickJsEngine(
        sourceKey = spec.key,
        host = host,
        cookies = cookies,
        dispatchers = dispatchers,
        memoryLimitMb = memoryLimitMb,
        evalTimeoutMs = evalTimeoutMs,
        appVersion = spec.appVersion,
        locale = spec.locale,
        platform = spec.platform,
        nowMs = nowMs,
        strictV4 = true,
    )
}

/**
 * 插件运行时装配与 LRU 缓存（架构 4.3 / docs/plugin-engine.md §3）。
 *
 * 同源串行由 [SourceDispatchers] 保证；此处再用 [Mutex] 串行化「装配」本身，
 * 避免并发首次加载重复实例化。缓存的是「脚本 + 描述符 + 宿主配置」，
 * 不是常驻 JS context（单发模型）。
 */
class SourceRuntimeManager(
    private val engines: PluginEngineFactory,
    private val hosts: PluginHostProvider,
    private val maxCached: Int = 8,
    private val onRelogin: suspend (sourceKey: String) -> Boolean = { false },
    private val onError: (sourceKey: String, error: SourcePluginException) -> Unit = { _, _ -> },
) {
    private class Entry(val signature: String, val runtime: SourceRuntime)

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Entry>()

    suspend fun acquire(spec: PluginRuntimeSpec): SourceRuntime = mutex.withLock {
        val signature = spec.signature()
        cache[spec.key]?.let { existing ->
            if (existing.signature == signature) {
                cache.remove(spec.key)
                cache[spec.key] = existing
                return@withLock existing.runtime
            }
            existing.runtime.close()
            cache.remove(spec.key)
        }
        val engine = engines.create(spec, hosts.hostFor(spec))
        try {
            engine.load(spec.script)
            val descriptor = SourceDescriptor.fromJson(
                raw = engine.describe(),
                declared = spec.capabilities,
                apiVersionOverride = spec.apiVersion,
            ).copy(
                // 头块/索引是安装期可信输入，覆盖脚本自报值。
                key = spec.key,
                domains = spec.domains.sorted(),
                apiVersion = spec.apiVersion,
            )
            if (descriptor.key.isBlank() || descriptor.name.isBlank()) {
                throw SourcePluginException("源描述符缺 name/key", SourceErrorKind.CONTRACT)
            }
            if (descriptor.minAppVersion.isNotBlank() &&
                ContractPolicy.compareSemver(descriptor.minAppVersion, spec.appVersion) > 0
            ) {
                throw SourcePluginException(
                    "源要求更高 App 版本：${descriptor.minAppVersion} > ${spec.appVersion}",
                    SourceErrorKind.CONTRACT,
                )
            }
            val runtime = SourceRuntime(
                descriptor = descriptor,
                engine = engine,
                onRelogin = { onRelogin(spec.key) },
                onError = { onError(spec.key, it) },
            )
            runtime.updateSettings(spec.settingsJson)
            cache[spec.key] = Entry(signature, runtime)
            evict()
            runtime
        } catch (t: Throwable) {
            engine.close()
            throw t
        }
    }

    /** 禁用 / 更新 / 内存压力：立刻释放该源运行时。 */
    suspend fun invalidate(key: String): Unit = mutex.withLock {
        cache.remove(key)?.runtime?.close()
    }

    suspend fun close(): Unit = mutex.withLock {
        cache.values.forEach { it.runtime.close() }
        cache.clear()
    }

    suspend fun cachedKeys(): List<String> = mutex.withLock { cache.keys.toList() }

    private fun evict() {
        while (cache.size > maxCached) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)?.runtime?.close()
        }
    }
}
