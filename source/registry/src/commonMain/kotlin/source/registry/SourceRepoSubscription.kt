package source.registry

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 源仓库索引中的单条源条目（纯数据层，不联网）。
 * 兼容源仓 `index.json` 与 docs 契约两套 schema：`url`/`filename` 二选一，
 * `apiVersion`/`minApp` 等字段可选，缺省不抛错。
 */
data class RepoSourceEntry(
    val key: String,
    val version: String,
    val name: String = "",
    val url: String? = null,
    val filename: String? = null,
    val apiVersion: Int? = null,
    val minApp: String? = null,
    val format: String = "js/v1",
)

/** 源仓库索引。顶层 `version` 为索引格式版本（缺省 1）。 */
data class RepoIndex(val version: Int = 1, val sources: List<RepoSourceEntry>)

/**
 * 手工解析远端 `index.json`。
 * 两处 schema 有漂移（顶层 `version` 与 `apiVersion`、条目 `url/filename` oneOf），
 * 且顶层可能是数组或 `{sources:[...]}`，故不用 `@Serializable` 直接 decode，逐字段读取。
 * 缺 `key`/`version` 的条目跳过；非法 JSON 抛 [IllegalArgumentException]。
 */
object SourceIndexParser {

    private const val PREFIX = "index-parse:"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): RepoIndex = try {
        when (val root = json.parseToJsonElement(raw)) {
            is JsonArray -> RepoIndex(sources = parseEntries(root))
            is JsonObject -> RepoIndex(
                // 源仓真实 index.json 用 version，docs 契约用 apiVersion，两者都是整数格式版本。
                version = readInt(root["version"]) ?: readInt(root["apiVersion"]) ?: 1,
                sources = parseEntries(root["sources"] as? JsonArray),
            )
            else -> throw IllegalArgumentException("$PREFIX 顶层须为对象或数组")
        }
    } catch (e: Exception) {
        // 已是本模块带前缀的异常原样透传，其余（如 SerializationException）统一包装。
        if (e is IllegalArgumentException && e.message?.startsWith(PREFIX) == true) throw e
        throw IllegalArgumentException("$PREFIX ${e.message}", e)
    }

    private fun parseEntries(arr: JsonArray?): List<RepoSourceEntry> =
        arr?.mapNotNull { parseEntry(it as? JsonObject) } ?: emptyList()

    private fun parseEntry(obj: JsonObject?): RepoSourceEntry? {
        if (obj == null) return null
        // 坏条目直接跳过，不让整份索引因一条脏数据失效。
        val key = readString(obj["key"])?.takeIf { it.isNotBlank() } ?: return null
        val version = readString(obj["version"])?.takeIf { it.isNotBlank() } ?: return null
        return RepoSourceEntry(
            key = key,
            version = version,
            name = readString(obj["name"]) ?: "",
            url = readString(obj["url"]),
            filename = readString(obj["filename"]),
            apiVersion = readInt(obj["apiVersion"]),
            minApp = readString(obj["minApp"]),
            format = readString(obj["format"]) ?: "js/v1",
        )
    }

    private fun readString(el: JsonElement?): String? {
        if (el == null || el === JsonNull) return null
        return (el as? JsonPrimitive)?.content
    }

    private fun readInt(el: JsonElement?): Int? {
        if (el == null || el === JsonNull) return null
        return (el as? JsonPrimitive)?.content?.toIntOrNull()
    }
}

/** 单条更新建议：`reason` 为 `new`（未安装）或 `upgrade`（远端版本更高）。 */
data class UpdateCandidate(
    val entry: RepoSourceEntry,
    val installedVersion: String?,
    val reason: String,
)

/**
 * 依据索引 + 已装列表生成批量更新计划（纯计算，无 IO）。
 * 结果按 key 排序保证三端一致；重复 key 保留最后一条。
 */
object SourceRepoPlanner {

    fun plan(
        index: RepoIndex,
        installed: List<Pair<String, String>>,
        appVersion: String = "1.0.0",
    ): List<UpdateCandidate> {
        // 重复 key 去重：后写的覆盖先写的（保留最后一条）。
        val byKey = LinkedHashMap<String, RepoSourceEntry>()
        for (e in index.sources) byKey[e.key] = e
        // 已装版本映射（同样以 key 为准，重复时后者生效）。
        val installedVersion = installed.toMap()

        val result = mutableListOf<UpdateCandidate>()
        for ((key, entry) in byKey) {
            // minApp 高于当前宿主版本则跳过，防止装上宿主跑不动的源。
            val minApp = entry.minApp
            if (minApp != null && compareVersions(minApp, appVersion) > 0) continue

            val cur = installedVersion[key]
            when {
                cur == null -> result += UpdateCandidate(entry, null, "new")
                // 只有远端版本更高才升级；相同或降级都不列入。
                compareVersions(entry.version, cur) > 0 ->
                    result += UpdateCandidate(entry, cur, "upgrade")
            }
        }
        return result.sortedBy { it.entry.key }
    }

    /**
     * 语义化版本比较：去掉前导 `v`/`V`，按 `.` 分段逐段比较。
     * 全数字段按数值比（`1.10 > 1.9`），其余按字符串比；缺段按 0 补齐（`1.2 == 1.2.0`）。
     * 返回 >0 / 0 / <0 表示 a 大于 / 等于 / 小于 b。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = normalize(a)
        val pb = normalize(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { "0" }
            val y = pb.getOrElse(i) { "0" }
            val cmp = if (isNumeric(x) && isNumeric(y)) compareNumeric(x, y) else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun normalize(v: String): List<String> =
        v.trim().removePrefix("v").removePrefix("V").split(".")

    private fun isNumeric(s: String): Boolean = s.isNotEmpty() && s.all { it in '0'..'9' }

    /** 避免大数溢出：去前导零后按长度再按字典序比。 */
    private fun compareNumeric(x: String, y: String): Int {
        val nx = x.trimStart('0').ifEmpty { "0" }
        val ny = y.trimStart('0').ifEmpty { "0" }
        return if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
    }
}

/** 一次刷新结果：`fetch` 失败时 `error` 非空且 `candidates` 为空，供 UI 展示失败态。 */
data class RepoRefreshResult(
    val index: RepoIndex,
    val candidates: List<UpdateCandidate>,
    val error: String? = null,
)

/**
 * 远端订阅入口。网络拉取由宿主以 [fetchIndex] 注入，本模块不自己联网（避开域名白名单红线）。
 */
class SourceRepoSubscription(private val fetchIndex: suspend () -> String) {

    suspend fun refresh(
        installed: List<Pair<String, String>>,
        appVersion: String = "1.0.0",
    ): RepoRefreshResult = try {
        val index = SourceIndexParser.parse(fetchIndex())
        RepoRefreshResult(
            index = index,
            candidates = SourceRepoPlanner.plan(index, installed, appVersion),
        )
    } catch (e: CancellationException) {
        // 协程取消不应被吞成失败态，交回结构化并发。
        throw e
    } catch (e: Exception) {
        RepoRefreshResult(
            index = RepoIndex(sources = emptyList()),
            candidates = emptyList(),
            error = e.message?.takeIf { it.isNotBlank() } ?: "index-fetch-failed",
        )
    }
}
