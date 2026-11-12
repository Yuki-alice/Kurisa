package source.bridge

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import core.model.toUtf8Bytes
import data.network.CookieStore
import data.network.SourceHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import source.hostapi.HostConvert
import source.hostapi.HostCookie
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostRequest
import source.hostapi.HostResponse
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import kotlin.random.Random

/**
 * HostApi 七命名空间实现（M1 harness 产品化；行为与冻结语义一致）。
 *
 * - Network：经 [SourceHttpClient]，白名单/Cookie/配对 UA/审计全继承；
 *   域名在引擎创建时按源版本绑定（不可变，防运行时篡改）。
 * - Html：Ksoup 受限解析；`dispose()` 后复用抛错（M1 冻结语义）。
 * - Convert：korlibs（与 M0 同族库），纯本地。
 * - Storage：调用方注入（生产为 `:data:repository` 的 DbKvStore，测试用内存版）。
 * - UI：默认拒绝并指路（WebView 登录需用户手势+独立上下文，M6 落地）。
 */
class KtorHostNetwork(
    private val http: SourceHttpClient,
    private val cookies: CookieStore,
    private val domains: Set<String>,
    /** P1 分层缓存：仅缓存 GET 200；TTL 到期或超上限淘汰最老。 */
    private val responseTtlMs: Long = 60_000,
    private val maxCacheEntries: Int = 128,
    /** P1 并发分层：宿主 IO 可并发；JS 求值仍走 per-source 串行 dispatcher（见 SourceDispatchers）。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val perHostPermits: Int = 4,
    private val nowMs: () -> Long = { 0L },
) : HostNetwork {
    private data class CacheEntry(val response: HostResponse, val expiresAt: Long)

    private val cacheMutex = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>()
    private val inflightMutex = Mutex()
    private val inflight = mutableMapOf<String, kotlinx.coroutines.Deferred<HostResponse>>()
    private val hostMutex = Mutex()
    private val hostSemaphores = mutableMapOf<String, Semaphore>()
    // P3 可观测性：net 段计数（并发写经 statsMutex 串行化；读快照同理）。
    private val statsMutex = Mutex()
    private var statRequests: Long = 0
    private var statCacheHits: Long = 0
    private var statCoalesced: Long = 0
    private var statNetMs: Long = 0

    /** P3 可观测性：net 段快照（调用方按源 key 聚合展示；只含计数与耗时）。 */
    suspend fun networkTelemetry(): NetworkTelemetry = statsMutex.withLock {
        NetworkTelemetry(statRequests, statCacheHits, statCoalesced, statNetMs)
    }

    private suspend fun countRequest() = statsMutex.withLock { statRequests++ }
    private suspend fun countCacheHit() = statsMutex.withLock { statCacheHits++ }
    private suspend fun countCoalesced() = statsMutex.withLock { statCoalesced++ }
    private suspend fun addNetMs(ms: Long) = statsMutex.withLock { statNetMs += ms }

    private fun map(r: SourceHttpClient.FetchResult): HostResponse = HostResponse(
        status = r.status,
        body = r.body,
        headers = r.headers,
        finalUrl = r.finalUrl,
        verdict = r.verdict.kind.name,
    )

    private fun cacheKey(method: String, url: String, headers: Map<String, String>, data: String?): String =
        buildString {
            append(method.uppercase()).append('|').append(url).append('|')
            headers.entries.sortedBy { it.key }.forEach { (k, v) -> append(k).append('=').append(v).append(';') }
            append('|').append(data.orEmpty())
        }

    private suspend fun hostSemaphore(host: String): Semaphore = hostMutex.withLock {
        hostSemaphores.getOrPut(host) { Semaphore(perHostPermits) }
    }

    private suspend fun getCached(key: String): HostResponse? = cacheMutex.withLock {
        val e = cache[key] ?: return@withLock null
        val now = nowMs()
        if (responseTtlMs > 0 && now != 0L && now > e.expiresAt) {
            cache.remove(key)
            return@withLock null
        }
        // LRU：命中上浮，保证上限淘汰最久未用。
        cache.remove(key)
        cache[key] = e
        e.response
    }

    private suspend fun putCached(key: String, response: HostResponse) {
        if (responseTtlMs <= 0) return
        cacheMutex.withLock {
            if (cache.size >= maxCacheEntries) {
                val eldest = cache.keys.firstOrNull()
                if (eldest != null) cache.remove(eldest)
            }
            val now = nowMs()
            val expiresAt = if (now == 0L) Long.MAX_VALUE else now + responseTtlMs
            cache.remove(key)
            cache[key] = CacheEntry(response, expiresAt)
        }
    }

    private suspend fun executeDeduped(
        method: String,
        url: String,
        headers: Map<String, String>,
        data: String?,
    ): HostResponse {
        val m = method.uppercase()
        val key = cacheKey(m, url, headers, data)
        countRequest()
        // 仅 GET 200 进缓存；写操作永不缓存、不参与去重（防副作用合并）。
        if (m == "GET") {
            val hit = getCached(key)
            if (hit != null) {
                countCacheHit()
                return hit
            }
        }
        if (m != "GET") {
            return sendAndMap(m, url, headers, data)
        }
        // in-flight 去重（占位式）：同 key 并发只发一次请求。先在锁内占位，
        // 抢到占位者执行网络，其余 await 同一 deferred，消除检查-插入竞态。
        val mine: kotlinx.coroutines.CompletableDeferred<HostResponse>
        inflightMutex.withLock {
            val existing = inflight[key]
            if (existing != null) {
                countCoalesced()
                return existing.await()
            }
            mine = kotlinx.coroutines.CompletableDeferred()
            inflight[key] = mine
        }
        return try {
            // P1 并发分层：网络 IO 跳 ioDispatcher，不占用 per-source 串行 dispatcher；
            // JS 求值仍串行，宿主 IO 可并发 + per-host 限流。
            val host = runCatching { CookieStore.hostOf(url) }.getOrElse { url }
            val res = withContext(ioDispatcher) {
                val mark = TimeSource.Monotonic.markNow()
                try {
                    hostSemaphore(host).withPermit {
                        map(http.send(m, url, domains, headers, data))
                    }
                } finally {
                    addNetMs(mark.elapsedNow().inWholeMilliseconds)
                }
            }
            if (res.status == 200) putCached(key, res)
            mine.complete(res)
            res
        } catch (e: Throwable) {
            // 取消/异常不缓存，由等待者共同承担；占位必须完成否则 await 挂起。
            if (!mine.completeExceptionally(e)) throw e
            throw e
        } finally {
            inflightMutex.withLock {
                if (inflight[key] === mine) inflight.remove(key)
            }
        }
    }

    /** 非去重路径的单次发送（含计数与耗时；调用方已做方法分派）。 */
    private suspend fun sendAndMap(
        method: String,
        url: String,
        headers: Map<String, String>,
        data: String?,
    ): HostResponse {
        countRequest()
        val mark = TimeSource.Monotonic.markNow()
        return try {
            map(http.send(method, url, domains, headers, data))
        } finally {
            addNetMs(mark.elapsedNow().inWholeMilliseconds)
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>): HostResponse =
        executeDeduped("GET", url, headers, null)

    override suspend fun post(url: String, headers: Map<String, String>, data: String?): HostResponse =
        sendAndMap("POST", url, headers, data)

    override suspend fun sendRequest(method: String, url: String, headers: Map<String, String>, data: String?): HostResponse {
        val m = method.uppercase()
        return if (m == "GET") executeDeduped(m, url, headers, data)
        else sendAndMap(m, url, headers, data)
    }

    override suspend fun requestAll(requests: List<HostRequest>): List<HostResponse> = coroutineScope {
        // 保序并发：JS 串行求值不被阻塞，宿主 IO 在 ioDispatcher 并发 + per-host 限流；
        // 相同条目自动合并为一次网络请求（in-flight 去重），GET 200 命中 TTL 缓存。
        requests.map { req ->
            async(ioDispatcher) {
                val m = req.method.uppercase()
                if (m == "GET") executeDeduped(m, req.url, req.headers, req.data)
                else sendAndMap(m, req.url, req.headers, req.data)
            }
        }.awaitAll()
    }

    override suspend fun getCookies(url: String): List<HostCookie> =
        cookies.matching(url, nowMs()).map { HostCookie(it.name, it.value, it.domain) }

    override suspend fun setCookies(url: String, list: List<HostCookie>): Boolean {
        val host = CookieStore.hostOf(url)
        list.forEach { c ->
            cookies.put(
                core.model.StoredCookie(
                    domain = c.domain.ifEmpty { host },
                    name = c.name,
                    value = c.value,
                    obtainedAtEpochMs = nowMs(),
                    source = core.model.CookieSource.NETWORK,
                ),
            )
        }
        return true
    }

    override suspend fun deleteCookies(url: String): Boolean {
        cookies.remove(CookieStore.hostOf(url))
        return true
    }
}

class KsoupHostHtml(
    private val maxDocs: Int = 32,
    private val nowMs: () -> Long = { 0L },
    private val docTtlMs: Long = 60_000,
) : HostHtml {
    private data class Entry(val doc: Document, val createdAt: Long)
    private val docs = mutableMapOf<Int, Entry>()
    private var nextDoc = 1
    // P3 可观测性：parse 段计数与累计耗时（调用方按源 key 聚合展示；只计次数耗时，不含 HTML 内容）。
    private var statParses: Long = 0
    private var statSelects: Long = 0
    private var statSelectManys: Long = 0
    private var statDisposes: Long = 0
    private var statMsTotal: Long = 0

    /** P3 可观测性：parse 段快照。 */
    fun htmlTelemetry(): HtmlTelemetry = HtmlTelemetry(
        parses = statParses,
        selects = statSelects,
        selectManys = statSelectManys,
        disposes = statDisposes,
        parseSelectMsTotal = statMsTotal,
    )

    override fun parse(html: String): Int {
        evictExpired()
        if (docs.size >= maxDocs) {
            // 长驻后调用结束兜底 dispose 仍可能漏网，此处按最老插入淘汰，保证内存有界。
            val eldest = docs.keys.minOrNull()
            if (eldest != null) docs.remove(eldest)
        }
        val id = nextDoc++
        val mark = TimeSource.Monotonic.markNow()
        docs[id] = Entry(Ksoup.parse(html), nowMs())
        statParses++
        statMsTotal += mark.elapsedNow().inWholeMilliseconds
        return id
    }

    override fun select(doc: Int, css: String): List<Map<String, String>> {
        val mark = TimeSource.Monotonic.markNow()
        try {
            val e = docs[doc] ?: throw IllegalStateException("HtmlDocument disposed-or-unknown: $doc")
            statSelects++
            return e.doc.select(css).map { el ->
                buildMap {
                    put("text", el.text())
                    put("html", el.html())
                    el.attributes().forEach { a -> put("attr:" + a.key, a.value) }
                }
            }
        } finally {
            statMsTotal += mark.elapsedNow().inWholeMilliseconds
        }
    }

    override fun selectMany(doc: Int, queries: List<String>): List<List<Map<String, String>>> {
        // 复用同一 Document 批量查询；计 1 次 selectMany，不重复计入 selects（跨边界次数口径）。
        val mark = TimeSource.Monotonic.markNow()
        try {
            val e = docs[doc] ?: throw IllegalStateException("HtmlDocument disposed-or-unknown: $doc")
            statSelectManys++
            return queries.map { css ->
                e.doc.select(css).map { el ->
                    buildMap {
                        put("text", el.text())
                        put("html", el.html())
                        el.attributes().forEach { a -> put("attr:" + a.key, a.value) }
                    }
                }
            }
        } finally {
            statMsTotal += mark.elapsedNow().inWholeMilliseconds
        }
    }

    override fun dispose(doc: Int) {
        docs.remove(doc)
        statDisposes++
    }

    fun liveCount(): Int = docs.size

    private fun evictExpired() {
        if (docTtlMs <= 0) return
        val now = nowMs()
        if (now == 0L) return
        val expired = docs.filterValues { now - it.createdAt > docTtlMs }.keys.toList()
        expired.forEach { docs.remove(it) }
    }
}

class CryptoHostConvert : HostConvert {
    override fun md5(text: String): String = Md5.hex(text.toUtf8Bytes())
    override fun sha256(text: String): String = core.model.sha256Hex(text)
    override fun encodeBase64(bytes: ByteArray): String = Base64Codec.encode(bytes)
    override fun decodeBase64(text: String): ByteArray =
        Base64Codec.decode(text) ?: throw IllegalArgumentException("base64 非法")
}

class NoopHostUi(private val reason: String = "WebView 登录需 M6 平台实现") : HostUi {
    override suspend fun showMessage(text: String) = Unit
    override suspend fun launchUrl(url: String): Unit =
        throw UnsupportedOperationException(reason)
}

class HostUtilsImpl(
    private val logSink: (String) -> Unit = {},
) : HostUtils {
    override fun createUuid(): String {
        // 非安全随机（M7 切 SecureRandom；此处仅满足契约形状，禁止用于密钥）。
        val r = Random.Default
        return buildString {
            repeat(8) { append("0123456789abcdef"[r.nextInt(16)]) }
            append('-')
            repeat(4) { append("0123456789abcdef"[r.nextInt(16)]) }
            append("-4")
            repeat(3) { append("0123456789abcdef"[r.nextInt(16)]) }
            append('-')
            append("89ab"[r.nextInt(4)])
            repeat(3) { append("0123456789abcdef"[r.nextInt(16)]) }
            append('-')
            repeat(12) { append("0123456789abcdef"[r.nextInt(16)]) }
        }
    }

    override fun log(text: String) = logSink(text)
}

class InMemoryHostStorage : HostStorage {
    private val map = mutableMapOf<String, String>()
    override fun get(sourceKey: String, key: String): String? = map["$sourceKey::$key"]
    override fun set(sourceKey: String, key: String, value: String) {
        map["$sourceKey::$key"] = value
    }

    override fun delete(sourceKey: String, key: String) {
        map.remove("$sourceKey::$key")
    }
}

class NovelHost : HostNovel {
    /**
     * 标签剥离（纯文本抽取；排版分页与受限标签白名单由渲染层负责，见 M4）。
     * script/style 内容一并丢弃（防正文污染）。
     */
    override fun stripHtml(html: String): String {
        // ReDoS 门：超长输入先截断（章节 HTML 正常 <1MB；恶意源的 MB 级载荷不值得全文正则）。
        val capped = if (html.length > 2_000_000) html.take(2_000_000) else html
        val noScript = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(capped, " ")
        // 块级标签先换行（段落语义），再清剩余行内标签。
        val blocked = Regex("(?i)</?(p|div|h[1-6]|li|tr|br)[^>]*>").replace(noScript, "\n")
        val noTags = Regex("<[^>]+>").replace(blocked, " ")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
