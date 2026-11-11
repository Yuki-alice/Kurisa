package source.bridge

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import core.model.toUtf8Bytes
import data.network.CookieStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind
import source.hostapi.HostApi
import source.hostapi.HostResponse
import kotlin.random.Random

/** 源桥异常（JS 侧错误原文保留在 message，便于源作者排查）。 */
class SourceBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 宿主缺 native 库（androidHostTest 的 JVM 起 Android 构件、无可加载 .so；
 * M1 同坑沿用 sentinel 思路，此处显式类型化）。
 * 单测捕获后直接 return（桌面/iOS 有原生库，全量不断言打折）。
 */
class HostMissingException(message: String) : Exception(message)

/**
 * quickjs-kt 引擎（架构 4.6 / docs/plugin-engine.md §2）。
 *
 * 单发模型：quickjs-kt 的实例构造器私有，`quickJs {}` 块结束即 close，
 * 因此每次调用都新建实例（polyfill + 脚本 + 实例化 + 调用片段一次求值）。
 * 跨调用状态必须经 loadData/saveData 落宿主 KV（Venera 源本就如此编写，
 * 宿主随时可能重建上下文——这是被迫的优点，不是缺陷）。
 *
 * - 同步桥（`__host.kv/html/convert/cookie/utils/log`）：本地白名单方法，
 *   与 [callSync] 共用同一套 handler（JS 内调与 Kotlin 直调同语义）。
 * - 异步桥（`__host.request` 兼容 + `__host.hostCall` + `__host.delay`）：网络/UI/定时器走宿主协程。
 * - 超时三件套：`evaluationTimeoutMillis` + `withTimeout` + `interruptEvaluation()`；块结束自动 `close()`。
 * - polyfill 对齐 Venera `ComicSource` 形状（声明式设置 / sendMessage 同步桥 /
 *   `fetch` / `HtmlElement` / 受控定时器），只提炼设计，不复制 GPL 代码。
 */
class QuickJsEngine(
    private val sourceKey: String,
    private val host: HostApi,
    private val cookies: CookieStore,
    private val dispatchers: SourceDispatchers,
    private val memoryLimitMb: Long = 32,
    private val evalTimeoutMs: Long = 10_000,
    private val appVersion: String = "0.0.0",
    private val locale: String = "zh_CN",
    private val platform: String = "common",
    private val nowMs: () -> Long = { 0L },
) : SourcePluginEngine {

    private var loadedScript: String? = null
    private var className: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun dispatcher() = dispatchers.dispatcherFor(sourceKey)

    override suspend fun load(script: String) {
        val name = findClass(script)
            ?: throw IllegalArgumentException("未找到 `class X extends ComicSource`")
        runCall(
            name, script,
            "if (typeof __source.init === 'function') { await __source.init(); } 'ok';",
        )
        loadedScript = script
        className = name
    }

    /** 只读实例字段：函数只取存在位，不调用任何能力方法、不触发网络。 */
    override suspend fun describe(): String = runCall(DESCRIBE_SNIPPET)

    /**
     * 设置预热：把「default + 用户值」合并后的 JSON 写进源私有 KV 的 `setting:` 命名空间。
     * 键前缀由 Kotlin 侧强制，脚本侧无法伪造或覆盖（架构 4.7）。
     */
    override fun applySettings(settingsJson: String) {
        val o = runCatching { json.parseToJsonElement(settingsJson).jsonObject }.getOrNull() ?: return
        o.entries.forEach { (key, value) ->
            if (key.isBlank()) return@forEach
            val text = if (value is JsonPrimitive) value.content else value.toString()
            host.storage.set(sourceKey, SETTING_PREFIX + key, text)
        }
    }

    override fun callSync(method: String, payload: String): String = when (method) {
        "kv" -> handleKv(payload)
        "html" -> handleHtml(payload)
        "convert" -> handleConvert(payload)
        "cookie" -> handleCookie(payload)
        "utils" -> handleUtils(payload)
        else -> throw SourceBridgeException("未知同步方法：$method")
    }

    override suspend fun callAsync(method: String, payload: String, timeoutMs: Long): String =
        when (method) {
            "request" -> handleRequest(payload)
            "hostCall" -> handleHostCall(payload)
            "delay" -> handleDelay(payload)
            else -> throw SourceBridgeException("未知异步方法：$method")
        }

    override fun close() {
        loadedScript = null
        className = null
    }

    // ---------- 类型化调用（搜索/详情/章节；返回源原始 JSON，归一化在 provider 适配层） ----------

    override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String {
        val kw = Json.encodeToString(kotlinx.serialization.serializer<String>(), keyword)
        return runCall(
            "await __source.search.load($kw, $optionsJson, $page)" +
                ".then(function (r) { return JSON.stringify(r); })"
        )
    }

    /** 读源声明的 optionList 并按 M1 冻结语义生成默认值数组（传 null 会崩，宿主侧生成）。 */
    override suspend fun defaultSearchOptions(): String {
        val raw = runCall(
            "JSON.stringify((__source.search && __source.search.optionList) || [])"
        )
        val groups = runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)?.map { g ->
                (g as? kotlinx.serialization.json.JsonObject)
                    ?.get("options") as? JsonArray
                    ?: JsonArray(emptyList())
            } ?: emptyList()
        }.getOrElse { emptyList() }
        val defaults = source.contract.ContractPolicy.defaultOptions(
            groups.map { g -> g.mapNotNull { it.jsonPrimitive.contentOrNull } }
        )
        return buildJsonArray {
            defaults.forEach { v ->
                add(v?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }.toString()
    }

    override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String {
        val id = Json.encodeToString(kotlinx.serialization.serializer<String>(), workId)
        val ns = if (kind == ContentKind.NOVEL) "novel" else "comic"
        return runCall(
            "await __source.$ns.loadInfo($id).then(function (r) { return JSON.stringify(r, " +
                "function (k, v) { return v instanceof Map ? Object.fromEntries(v) : v; }); })"
        )
    }

    override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind): String {
        val w = Json.encodeToString(kotlinx.serialization.serializer<String>(), workId)
        val c = Json.encodeToString(kotlinx.serialization.serializer<String>(), chapterId)
        val call = if (kind == ContentKind.NOVEL) {
            "await __source.novel.loadChapter($w, $c)"
        } else {
            "await __source.comic.loadEp($w, $c)"
        }
        return runCall("$call.then(function (r) { return JSON.stringify(r); })")
    }

    override suspend fun invoke(path: String, argsJson: String): String {
        require(path.matches(Regex("""[A-Za-z_$][\w$.]*"""))) { "非法调用路径：$path" }
        val args = runCatching { json.parseToJsonElement(argsJson) }.getOrNull()
            ?: throw SourceBridgeException("参数须为 JSON 数组")
        require(args is JsonArray) { "参数须为 JSON 数组" }
        // Promise.resolve 包一层：onImageLoad / translate 等能力允许同步返回对象或字符串。
        return runCall(
            "await Promise.resolve(__source.$path(...$argsJson))" +
                ".then(function (r) { return JSON.stringify(r); })"
        )
    }

    // ---------- 内部 ----------

    private suspend fun runCall(snippet: String): String {
        val name = className
            ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        val script = loadedScript
            ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        return runCall(name, script, snippet)
    }

    private suspend fun runCall(className: String, script: String, snippet: String): String =
        withContext(dispatcher()) {
            try {
                withTimeout(evalTimeoutMs) {
                    quickJs(RejectModuleLoader) {
                        memoryLimit = memoryLimitMb * 1024 * 1024
                        maxStackSize = 256L * 1024
                        evaluationTimeoutMillis = evalTimeoutMs
                        define("__host") {
                            function("kv") { payload: String -> handleKv(payload) }
                            function("html") { payload: String -> handleHtml(payload) }
                            function("convert") { payload: String -> handleConvert(payload) }
                            function("cookie") { payload: String -> handleCookie(payload) }
                            function("utils") { payload: String -> handleUtils(payload) }
                            function("log") { msg: String ->
                                host.utils.log(msg)
                                msg.length
                            }
                            asyncFunction("request") { payload: String -> handleRequest(payload) }
                            asyncFunction("hostCall") { payload: String -> handleHostCall(payload) }
                            asyncFunction("delay") { payload: String -> handleDelay(payload) }
                        }
                        evaluate<String>(polyfillOf(appVersion, locale, platform))
                        evaluate<String>(script)
                        evaluate<String>("const __source = new $className();")
                        evaluate<String>(snippet)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw SourceBridgeException("源调用超时（${evalTimeoutMs}ms）：$sourceKey", e)
            } catch (e: CancellationException) {
                // 调用方取消（协程 teardown）永不包装，结构化并发不断。
                throw e
            } catch (t: Throwable) {
                // native 缺失是 Error 分支（UnsatisfiedLinkError 等 JVM 专有类型，
                // commonMain 不可直引），按类名识别；其余 Errors 原样抛出（M1 同式）。
                if (isHostMissing(t)) throw HostMissingException("quickjs native 缺失：${t.message}")
                if (t is Exception) throw asBridgeError(t)
                throw t
            }
        }

    private fun findClass(script: String): String? =
        Regex("""class\s+(\w+)\s+extends\s+ComicSource""")
            .find(script)?.groupValues?.get(1)

    private fun isHostMissing(t: Throwable): Boolean {
        // 只认链接错误类名（M1 实录教训：按 "quickjs" 子串匹配会把所有
        // QuickJsException 都误判为宿主缺失——类名本身就含 quickjs）。
        val names = generateSequence(t) { it.cause }.mapNotNull {
            it::class.simpleName
        }.toSet()
        return names.any {
            it.contains("UnsatisfiedLink") || it.contains("NoClassDefFound") ||
                it.contains("InitializerError")
        }
    }

    private fun asBridgeError(t: Throwable): Exception =
        if (t is SourceBridgeException) t
        else SourceBridgeException("源执行失败 [$sourceKey]：${t.message}", t)

    // ---------- 同步桥 handler（JS 绑定与 callSync 共用） ----------

    private fun handleKv(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return ""
        val op = o["op"]?.jsonPrimitive?.contentOrNull ?: return ""
        if (op == "isLogged") {
            return if (host.storage.get(sourceKey, "loggedIn") == "1") "1" else "0"
        }
        val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return ""
        // 脚本不得触碰设置命名空间，也不得用 msg.key 冒充别的源（前缀在 Kotlin 侧强制）。
        if (op == "setting") {
            return host.storage.get(sourceKey, SETTING_PREFIX + key).orEmpty()
        }
        if (key.startsWith(SETTING_PREFIX)) return ""
        return when (op) {
            "save" -> {
                if (key == "setting") throw SourceBridgeException("setting is not allowed to be saved")
                host.storage.set(sourceKey, key, o["value"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "1"
            }
            "delete" -> {
                host.storage.delete(sourceKey, key)
                "1"
            }
            else -> host.storage.get(sourceKey, key).orEmpty()
        }
    }

    private fun handleHtml(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "parse" -> {
                val html = o["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
                """{"doc":${host.html.parse(html)}}"""
            }
            "select" -> {
                val doc = o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return """{"error":"disposed-or-unknown"}"""
                try {
                    // 压平输出：与 HostHtml 契约键一致（"text"/"html"/"attr:*"），
                    // JS 侧 `els[0]["attr:src"]` 直取，不做二次整形。
                    val els = host.html.select(doc, o["css"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    buildString {
                        append('[')
                        els.forEachIndexed { i, el ->
                            if (i > 0) append(',')
                            append("{")
                            append("\"text\":")
                            append(Json.encodeToString(kotlinx.serialization.serializer<String>(), el["text"].orEmpty()))
                            append(",\"html\":")
                            append(Json.encodeToString(kotlinx.serialization.serializer<String>(), el["html"].orEmpty()))
                            el.entries.filter { it.key.startsWith("attr:") }.forEach { e ->
                                append(',')
                                append(Json.encodeToString(kotlinx.serialization.serializer<String>(), e.key))
                                append(':')
                                append(Json.encodeToString(kotlinx.serialization.serializer<String>(), e.value))
                            }
                            append("}")
                        }
                        append(']')
                    }
                } catch (e: Exception) {
                    // disposed 复用抛 IllegalStateException；Ksoup 选择器解析失败等一律收敛为同一错误面
                    //（P6：JS 侧输入永不抛原生异常穿透，只返回错误 JSON）。
                    """{"error":"disposed-or-unknown"}"""
                }
            }
            "dispose" -> {
                o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { host.html.dispose(it) }
                """{"ok":true}"""
            }
            else -> """{"error":"unknown-op"}"""
        }
    }

    private fun handleConvert(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return ""
        val op = o["op"]?.jsonPrimitive?.contentOrNull ?: return ""
        val input = o["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (op) {
            "md5" -> host.convert.md5(input)
            "sha256" -> host.convert.sha256(input)
            "encodeBase64" -> host.convert.encodeBase64(input.toUtf8Bytes())
            "decodeBase64" -> runCatching {
                host.convert.decodeBase64(input).decodeToString()
            }.getOrDefault("")
            "encodeUtf8", "decodeUtf8" -> input
            "hexEncode" -> hexOf(input)
            // sha1/sha512/AES/RSA 属扩展能力，当前宿主不开放；返回空串而非伪造结果。
            else -> ""
        }
    }

    private fun hexOf(text: String): String {
        val digits = "0123456789abcdef"
        return buildString {
            text.toUtf8Bytes().forEach { b ->
                val v = b.toInt() and 0xff
                append(digits[v ushr 4])
                append(digits[v and 0x0f])
            }
        }
    }

    private fun handleUtils(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return ""
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "uuid" -> host.utils.createUuid()
            "randomInt" -> {
                val min = o["min"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val max = o["max"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: Long.MAX_VALUE
                if (max <= min) min.toString() else Random.nextLong(min, max).toString()
            }
            "randomDouble" -> Random.nextDouble().toString()
            else -> ""
        }
    }

    private fun handleCookie(payload: String): String {
        // 同步 Cookie 三件套（内存态，不挂起；与 KtorHostNetwork 共用同一 CookieStore）。
        // JS 置入记 CookieSource.NETWORK（脚本基于网络数据断言，非用户粘贴/测试预置；
        // 配对 UA 为空——cf_clearance 的 UA 配对只走 WebView/手动导入路径，见架构红线）。
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return "[]"
        val url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "set" -> {
                val host = CookieStore.hostOf(url)
                val list = o["list"] as? JsonArray ?: return "0"
                list.forEach { el ->
                    val c = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    cookies.put(
                        core.model.StoredCookie(
                            domain = c["domain"]?.jsonPrimitive?.contentOrNull?.ifEmpty { host } ?: host,
                            name = c["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            value = c["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            obtainedAtEpochMs = nowMs(),
                            source = core.model.CookieSource.NETWORK,
                        ),
                    )
                }
                "1"
            }
            "delete" -> {
                cookies.remove(CookieStore.hostOf(url))
                "1"
            }
            else -> {
                val arr = cookies.matching(url, nowMs()).map { c ->
                    """{"name":${
                        Json.encodeToString(kotlinx.serialization.serializer<String>(), c.name)
                    },"value":${
                        Json.encodeToString(kotlinx.serialization.serializer<String>(), c.value)
                    },"domain":${
                        Json.encodeToString(kotlinx.serialization.serializer<String>(), c.domain)
                    }}"""
                }
                "[${arr.joinToString(",")}]"
            }
        }
    }

    // ---------- 异步桥 handler ----------

    private suspend fun handleDelay(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return "0"
        val ms = (o["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L)
            .coerceIn(0L, MAX_TIMER_MS)
        if (ms > 0L) delay(ms)
        return "1"
    }

    private suspend fun handleRequest(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"status":0,"body":"BAD_PAYLOAD","headers":{}}"""
        val method = (o["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return """{"status":0,"body":"NO_URL","headers":{}}"""
        val headers = (o["headers"] as? kotlinx.serialization.json.JsonObject)
            ?.entries?.associate { it.key to (it.value.jsonPrimitive.contentOrNull.orEmpty()) }
            .orEmpty()
        val data = o["data"]?.jsonPrimitive?.contentOrNull
        return try {
            val r: HostResponse = when (method) {
                "GET" -> host.network.get(url, headers)
                "POST" -> host.network.post(url, headers, data)
                // 安全出口当前只开放 GET/POST（SourceHttpClient 的白名单/重定向/审计路径）。
                else -> HostResponse(0, "UNSUPPORTED_METHOD:$method")
            }
            val body = if (r.body.length > 300_000) r.body.take(300_000) else r.body
            """{"status":${r.status},"body":${
                Json.encodeToString(kotlinx.serialization.serializer<String>(), body)
            },"headers":{}}"""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保留原始原因（domain-blocked 等）供错误分类；脚本只见 status 0。
            """{"status":0,"body":${
                Json.encodeToString(
                    kotlinx.serialization.serializer<String>(),
                    "HOST_ERROR:${e.message.orEmpty()}",
                )
            },"headers":{}}"""
        }
    }

    private suspend fun handleHostCall(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        return when (o["method"]?.jsonPrimitive?.contentOrNull) {
            "network.get", "network.post", "network.put", "network.patch", "network.delete",
            "network.sendRequest", "network.fetchBytes", "http" -> handleRequest(payload)
            "delay" -> handleDelay(payload)
            "ui.showMessage" -> {
                host.ui.showMessage(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                """{"ok":true}"""
            }
            else -> """{"error":"unsupported-hostcall"}"""
        }
    }

    companion object {
        internal const val SETTING_PREFIX = "setting:"
        private const val MAX_TIMER_MS = 30_000L

        /**
         * 描述符探测片段（只读字段，不调用能力方法）。
         * 函数字段一律转成布尔存在位；`describe()` 输出交给
         * `SourceDescriptor.fromJson` 在纯 Kotlin 侧解析。
         */
        private val DESCRIBE_SNIPPET = """
            (function () {
              var s = __source;
              var asArray = function (v) {
                if (Array.isArray(v)) return v;
                if (v && typeof v === 'object' && typeof v[Symbol.iterator] === 'function') {
                  return Array.from(v);
                }
                return [];
              };
              var keys = function (v) {
                if (!v || typeof v !== 'object') return [];
                if (v instanceof Map) return Array.from(v.keys());
                return Object.keys(v);
              };
              var settings = {};
              var raw = (s.settings && typeof s.settings === 'object') ? s.settings : {};
              Object.keys(raw).forEach(function (k) {
                var it = raw[k] || {};
                settings[k] = {
                  title: String(it.title || ''),
                  type: String(it.type || 'input'),
                  options: asArray(it.options).map(function (o) {
                    return typeof o === 'string'
                      ? { value: o, text: o }
                      : { value: String(o.value), text: String(o.text || o.value) };
                  }),
                  default: (it.default === undefined || it.default === null) ? null : String(it.default),
                  validator: it.validator ? String(it.validator) : null,
                  buttonText: it.buttonText ? String(it.buttonText) : null,
                  hasCallback: typeof it.callback === 'function'
                };
              });
              var kinds = asArray(s.kind).map(String);
              return JSON.stringify({
                key: String(s.key || ''),
                name: String(s.name || ''),
                version: String(s.version || ''),
                minAppVersion: String(s.minAppVersion || ''),
                apiVersion: Number(s.apiVersion) || 2,
                kinds: kinds.length ? kinds : ['manga'],
                domains: asArray(s.domains).map(String),
                capabilities: asArray(s.capabilities).map(String),
                settings: settings,
                translationLocales: keys(s.translation),
                hasInit: typeof s.init === 'function',
                hasSearch: !!s.search,
                hasSearchNext: !!(s.search && s.search.loadNext),
                hasComic: !!s.comic,
                hasNovel: !!s.novel,
                hasImageLoad: !!(s.comic && s.comic.onImageLoad),
                hasExplore: Array.isArray(s.explore) && s.explore.length > 0,
                hasCategory: !!(s.category || s.categoryComics),
                hasAccount: !!s.account,
                hasFavorites: !!s.favorites
              });
            })()
        """.trimIndent()

        /**
         * 宿主 polyfill（M1 冻结形状 + Venera 兼容层）。
         * 直接插值（String.format 是 JVM 专有，commonMain 不可用）。
         * KV 命名空间在 Kotlin 侧以前缀强制（脚本传参不可信），此处不占位。
         * 注意：JS 里一律不用模板字符串，避免与 Kotlin 字符串模板的 `$` 冲突。
         */
        private fun polyfillOf(appVersion: String, locale: String, platform: String): String = """
            const APP = { locale: '$locale', version: '$appVersion', platform: '$platform' };
            class ComicSource {
              constructor() {
                this.name = ''; this.key = ''; this.version = ''; this.minAppVersion = '';
                this.apiVersion = 2; this.kind = ['manga']; this.domains = [];
                this.capabilities = []; this.translation = {};
              }
              loadData(key) {
                return __host.kv(JSON.stringify({ op: 'load', key: key })) || null;
              }
              saveData(key, value) {
                if (key === 'setting' || String(key).indexOf('setting:') === 0) {
                  throw 'setting is not allowed to be saved';
                }
                __host.kv(JSON.stringify({ op: 'save', key: key, value: String(value) }));
              }
              deleteData(key) {
                __host.kv(JSON.stringify({ op: 'delete', key: key }));
              }
              loadSetting(key) {
                const v = __host.kv(JSON.stringify({ op: 'setting', key: key }));
                return (v === '' || v === null || v === undefined) ? null : v;
              }
              get isLogged() {
                return __host.kv(JSON.stringify({ op: 'isLogged' })) === '1';
              }
              translate(key) {
                const map = (this.translation || {})[APP.locale] || {};
                return map[key] || key;
              }
              init() {}
            }
            ComicSource.sources = {};
            class Comic { constructor(o) { Object.assign(this, o || {}); } }
            class ComicDetails { constructor(o) { Object.assign(this, o || {}); } }
            function ImageLoadingConfig(o) { Object.assign(this, o || {}); }

            class HtmlElement {
              constructor(flat) {
                const f = flat || {};
                this.text = f.text || '';
                this.innerHtml = f.html || '';
                this.innerHTML = this.innerHtml;
                this.attributes = {};
                this.children = [];
                this.id = null;
                this.classNames = [];
                this.localName = '';
                Object.keys(f).forEach(function (k) {
                  if (k.indexOf('attr:') === 0) {
                    const name = k.substring(5);
                    this.attributes[name] = f[k];
                    this[k] = f[k];
                    if (name === 'id') this.id = f[k];
                    if (name === 'class') this.classNames = String(f[k]).split(/\s+/).filter(Boolean);
                  }
                }, this);
              }
            }
            class HtmlDocument {
              constructor(html) {
                const r = JSON.parse(__host.html(JSON.stringify({ op: 'parse', html: String(html) })));
                this.__doc = r.doc;
              }
              static fromId(id) {
                const d = Object.create(HtmlDocument.prototype);
                d.__doc = id;
                return d;
              }
              querySelectorAll(css) {
                const r = __host.html(JSON.stringify({ op: 'select', doc: this.__doc, css: css }));
                if (typeof r === 'string' && r.indexOf('disposed') >= 0) throw 'HtmlDocument disposed';
                return JSON.parse(r).map(function (f) { return new HtmlElement(f); });
              }
              querySelector(css) {
                const a = this.querySelectorAll(css);
                return a[0] || null;
              }
              getElementById(id) { return this.querySelector('#' + id); }
              dispose() { __host.html(JSON.stringify({ op: 'dispose', doc: this.__doc })); }
            }

            const Network = {
              get: async function (url, headers) {
                const r = await __host.request(JSON.stringify({ method: 'GET', url: url, headers: headers || {} }));
                return JSON.parse(r);
              },
              post: async function (url, headers, data) {
                const r = await __host.request(JSON.stringify({
                  method: 'POST', url: url, headers: headers || {},
                  data: (data === undefined || data === null) ? null : String(data)
                }));
                return JSON.parse(r);
              },
              put: async function (url, headers, data) {
                return Network.sendRequest('PUT', url, headers, data);
              },
              patch: async function (url, headers, data) {
                return Network.sendRequest('PATCH', url, headers, data);
              },
              delete: async function (url, headers) {
                return Network.sendRequest('DELETE', url, headers, null);
              },
              sendRequest: async function (method, url, headers, data) {
                const r = await __host.request(JSON.stringify({
                  method: String(method || 'GET').toUpperCase(), url: url, headers: headers || {},
                  data: (data === undefined || data === null) ? null : String(data)
                }));
                return JSON.parse(r);
              },
              fetchBytes: async function (method, url, headers, data) {
                const r = await __host.request(JSON.stringify({
                  method: String(method || 'GET').toUpperCase(), url: url, headers: headers || {},
                  data: (data === undefined || data === null) ? null : String(data)
                }));
                const o = JSON.parse(r);
                return { status: o.status, headers: o.headers || {}, body: o.body || '' };
              },
              getCookies: function (url) {
                return JSON.parse(__host.cookie(JSON.stringify({ op: 'get', url: url })));
              },
              setCookies: function (url, list) {
                return __host.cookie(JSON.stringify({ op: 'set', url: url, list: list })) === '1';
              },
              deleteCookies: function (url) {
                return __host.cookie(JSON.stringify({ op: 'delete', url: url })) === '1';
              }
            };

            async function fetch(url, options) {
              const o = options || {};
              const r = await __host.request(JSON.stringify({
                method: String(o.method || 'GET').toUpperCase(), url: url, headers: o.headers || {},
                data: (o.body === undefined || o.body === null) ? null : String(o.body)
              }));
              const parsed = JSON.parse(r);
              return {
                ok: parsed.status >= 200 && parsed.status < 300,
                status: parsed.status,
                url: url,
                headers: parsed.headers || {},
                text: async function () { return parsed.body || ''; },
                json: async function () { return JSON.parse(parsed.body || '{}'); }
              };
            }

            const Convert = {
              md5: function (s) { return __host.convert(JSON.stringify({ op: 'md5', value: String(s) })); },
              sha256: function (s) { return __host.convert(JSON.stringify({ op: 'sha256', value: String(s) })); },
              encodeBase64: function (s) { return __host.convert(JSON.stringify({ op: 'encodeBase64', value: String(s) })); },
              decodeBase64: function (s) { return __host.convert(JSON.stringify({ op: 'decodeBase64', value: String(s) })); },
              encodeUtf8: function (s) { return __host.convert(JSON.stringify({ op: 'encodeUtf8', value: String(s) })); },
              decodeUtf8: function (s) { return __host.convert(JSON.stringify({ op: 'decodeUtf8', value: String(s) })); },
              hexEncode: function (s) { return __host.convert(JSON.stringify({ op: 'hexEncode', value: String(s) })); }
            };

            function createUuid() { return __host.utils(JSON.stringify({ op: 'uuid' })); }
            function randomInt(min, max) {
              return Number(__host.utils(JSON.stringify({ op: 'randomInt', min: min, max: max })));
            }
            function randomDouble() { return Number(__host.utils(JSON.stringify({ op: 'randomDouble' }))); }
            const Utils = { createUuid: createUuid, randomInt: randomInt, randomDouble: randomDouble };

            const console = {
              log: function () { __host.log(Array.prototype.slice.call(arguments).join(' ')); },
              error: function () { __host.log('[error] ' + Array.prototype.slice.call(arguments).join(' ')); },
              warn: function () { __host.log('[warn] ' + Array.prototype.slice.call(arguments).join(' ')); }
            };

            function setTimeout(callback, delayMs) {
              const ms = Math.max(0, Math.min(Number(delayMs) || 0, 30000));
              return __host.delay(JSON.stringify({ time: ms })).then(function () {
                if (typeof callback === 'function') callback();
              });
            }

            /**
             * Venera 兼容同步桥：只放行本地白名单方法；网络/UI/定时器返回 Promise。
             * 白名单外直接抛错（架构 4.6.1），防误用伪装成宿主能力。
             */
            function sendMessage(msg) {
              const m = msg && msg.method;
              switch (m) {
                case 'load_data':
                  return __host.kv(JSON.stringify({ op: 'load', key: String(msg.data_key) })) || null;
                case 'save_data':
                  if (String(msg.data_key) === 'setting') throw 'setting is not allowed to be saved';
                  __host.kv(JSON.stringify({ op: 'save', key: String(msg.data_key), value: String(msg.data) }));
                  return null;
                case 'delete_data':
                  __host.kv(JSON.stringify({ op: 'delete', key: String(msg.data_key) }));
                  return null;
                case 'load_setting':
                  return __host.kv(JSON.stringify({ op: 'setting', key: String(msg.setting_key) })) || null;
                case 'isLogged':
                  return __host.kv(JSON.stringify({ op: 'isLogged' })) === '1';
                case 'getLocale': return APP.locale;
                case 'getPlatform': return APP.platform;
                case 'uuid': return createUuid();
                case 'random':
                  return msg.type === 'double' ? randomDouble() : randomInt(msg.min || 0, msg.max || 100);
                case 'log': console.log(String(msg.content || '')); return null;
                case 'cookie':
                  return JSON.parse(__host.cookie(JSON.stringify({
                    op: msg.function === 'set' ? 'set' : (msg.function === 'delete' ? 'delete' : 'get'),
                    url: String(msg.url || ''), list: msg.cookies || []
                  })));
                case 'convert': {
                  const t = msg.type;
                  const op = t === 'base64' ? (msg.isEncode ? 'encodeBase64' : 'decodeBase64')
                    : (t === 'md5' ? 'md5' : (t === 'sha256' ? 'sha256'
                    : (t === 'utf8' || t === 'gbk' ? (msg.isEncode ? 'encodeUtf8' : 'decodeUtf8') : '')));
                  return op ? __host.convert(JSON.stringify({ op: op, value: String(msg.value) })) : '';
                }
                case 'html': {
                  const fn = msg.function;
                  if (fn === 'parse') return JSON.parse(
                    __host.html(JSON.stringify({ op: 'parse', html: String(msg.data || '') }))
                  ).doc;
                  if (fn === 'dispose') {
                    __host.html(JSON.stringify({ op: 'dispose', doc: msg.key }));
                    return null;
                  }
                  if (fn === 'querySelector' || fn === 'querySelectorAll' || fn === 'getElementById') {
                    const d = HtmlDocument.fromId(msg.key);
                    return fn === 'querySelectorAll'
                      ? d.querySelectorAll(String(msg.query || ''))
                      : d.querySelector(String(msg.query || '#' + msg.id));
                  }
                  return null;
                }
                case 'http':
                  return __host.request(JSON.stringify({
                    method: String(msg.http_method || 'GET').toUpperCase(),
                    url: String(msg.url || ''), headers: msg.headers || {}, data: msg.data || null
                  })).then(function (r) { return JSON.parse(r); });
                case 'delay':
                  return setTimeout(function () {}, msg.time || 0);
                default:
                  throw 'sendMessage method not allowed: ' + String(m);
              }
            }
            // quickjs-kt 的 evaluate<String> 对函数对象没有类型转换器：
            // polyfill 必须以字符串表达式收尾，否则最后一个函数声明会让求值返回 JsObject。
            'ok';
        """.trimIndent()

        /**
         * 模块导入拒绝器（源脚本禁止 import 外部模块：供应链收口，见 C1）。
         */
        private object RejectModuleLoader : com.dokar.quickjs.ModuleLoader {
            override fun load(name: String): com.dokar.quickjs.ModuleContent =
                throw SourceBridgeException("模块导入不支持：$name")
        }
    }
}
