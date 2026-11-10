package spike

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 响应数据，跨平台最小契约。 */
data class SourceHttpResponse(val status: Int, val body: String)

/** 平台网络能力注入点（对应 architecture.md 7.x 的平台适配层）。 */
fun interface SourceHttp {
    suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): SourceHttpResponse
}

/**
 * 真实 Venera 源加载 spike。
 *
 * 目的不是把某个站点跑通，而是验证**契约层能否在 quickjs-kt 上成立**：
 * 真实 .js 源能否被解析、`class X extends ComicSource` 能否被发现、
 * 宿主注入的 `Network` / `fetch` / `Comic` / `ComicDetails` / `loadData` 能否被源正确调用。
 *
 * 注意：quickjs-kt 的 typed `asyncFunction` 只接受**单个**参数，
 * 因此 Host API 统一收一个 JSON 载荷字符串（与 architecture.md 4.6.2 的
 * `__hostCall(method, payload)` 形状一致），不用多参数重载。
 */
class VeneraSourceRunner(
    private val http: SourceHttp,
    /**
     * 预置 Cookie（host -> name -> value）。
     *
     * 这是桌面端「手工导入 Cookie」方案的验证入口：如果宿主能把用户从系统浏览器
     * 复制的 Cookie 写进 CookieJar，且源的 `Network.getCookies` 与后续请求都能看到它，
     * 那么 `loginWithWebview` 在语义上就完全可被替代——因为源判断登录态本来就读 Cookie。
     */
    seedCookies: Map<String, Map<String, String>> = emptyMap(),
) {

    private val kv = mutableMapOf<String, String>()

    /** 宿主侧请求日志，回填进结果 JSON，便于做「有无 Cookie」的对照观察。 */
    private val hostLogs = mutableListOf<String>()

    /** host -> (cookie name -> value)，对应真实架构里的 `cookie` 表。 */
    private val cookieJar: MutableMap<String, MutableMap<String, String>> =
        seedCookies.mapValues { it.value.toMutableMap() }.toMutableMap()

    private fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

    /** 简化版域匹配：精确命中，或 cookie host 作为父域后缀命中。 */
    private fun cookiesFor(url: String): Map<String, String> {
        val host = hostOf(url)
        val out = linkedMapOf<String, String>()
        cookieJar.forEach { (cookieHost, jar) ->
            val h = cookieHost.removePrefix(".").lowercase()
            if (host == h || host.endsWith(".$h")) out.putAll(jar)
        }
        return out
    }

    private fun cookieHeaderFor(url: String): String? =
        cookiesFor(url).takeIf { it.isNotEmpty() }
            ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    private fun handleCookieOp(payload: String): String {
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return "[]"
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return "[]"
        val host = hostOf(url)
        return when (obj["op"]?.jsonPrimitive?.contentOrNull) {
            "set" -> {
                val jar = cookieJar.getOrPut(host) { mutableMapOf() }
                (obj["list"] as? kotlinx.serialization.json.JsonArray)?.forEach { el ->
                    val o = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    jar[name] = o["value"]?.jsonPrimitive?.contentOrNull ?: ""
                }
                println("[host] cookie set -> $host (${jar.size} 条)")
                "[]"
            }
            "delete" -> {
                val removed = cookieJar.remove(host)?.size ?: 0
                println("[host] cookie delete -> $host (清除 $removed 条)")
                "[]"
            }
            else -> buildJsonObject {
                put("cookies", kotlinx.serialization.json.buildJsonArray {
                    cookiesFor(url).forEach { (k, v) ->
                        add(buildJsonObject {
                            put("name", k)
                            put("value", v)
                            put("domain", host)
                        })
                    }
                })
            }.let { it["cookies"].toString() }
        }
    }

    suspend fun run(
        sourceCode: String,
        keyword: String,
        /** 只跑元数据 / init / account 诊断，跳过 search 与 detail（用于登录态对照实验）。 */
        probeOnly: Boolean = false,
    ): String {
        val className = Regex("""class\s+(\w+)\s+extends\s+ComicSource""")
            .find(sourceCode)?.groupValues?.get(1)
            ?: error("未找到 `class X extends ComicSource` 定义")

        val kw = JsonPrimitive(keyword).toString()

        return quickJs {
            memoryLimit = 32L * 1024 * 1024
            maxStackSize = 256L * 1024
            evaluationTimeoutMillis = 60_000

            define("__host") {
                // 异步桥：网络
                asyncFunction("request") { payload: String -> handleRequest(payload) }
                // 同步桥：源私有 KV（load / save 合一，保持单参数）
                function("kv") { payload: String -> handleKv(payload) }
                // 同步桥：Cookie 管理（getCookies / setCookies / deleteCookies）
                function("cookieOp") { payload: String -> handleCookieOp(payload) }
                function("log") { msg: String ->
                    println("[source] $msg")
                    msg.length
                }
            }

            evaluate<String>(POLYFILL + "\n" + sourceCode + "\n" + driver(className, kw, probeOnly))
        }.let { raw ->
            val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (obj == null) raw else JsonObject(
                obj + ("hostLogs" to JsonArray(hostLogs.map { JsonPrimitive(it) }))
            ).toString()
        }
    }

    private suspend fun handleRequest(payload: String): String {
        val req = runCatching { Json.parseToJsonElement(payload).jsonObject }
            .getOrElse { return errorJson("BAD_PAYLOAD: $payload") }

        val method = req["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val url = req["url"]?.jsonPrimitive?.contentOrNull ?: return errorJson("NO_URL")
        val headers: Map<String, String> = runCatching {
            (req["headers"]?.jsonObject ?: JsonObject(emptyMap()))
                .mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
                .toMap()
        }.getOrDefault(emptyMap())
        val body = req["body"]?.jsonPrimitive?.contentOrNull

        // 宿主负责 Cookie 注入（源不显式传 Cookie 头），这是与 Venera 行为一致的关键点
        val injected = cookieHeaderFor(url)
        val finalHeaders = if (injected != null) headers + ("Cookie" to injected) else headers

        val resp = try {
            http.fetch(method, url, finalHeaders, body?.takeIf { it != "null" })
        } catch (e: Exception) {
            val mark = if (injected != null) " (cookie=yes)" else " (cookie=none)"
            val line = "[host] ERROR $method $url$mark -> ${e::class.simpleName}: ${e.message}"
            hostLogs += line
            println(line)
            return errorJson("HOST_ERROR: ${e::class.simpleName}: ${e.message}")
        }

        val cookieMark = if (injected != null) " [cookie: ${injected.length} chars]" else " [cookie: none]"
        val line = "[host] $method $url -> ${resp.status} (${resp.body.length} chars)$cookieMark"
        hostLogs += line
        println(line)
        return buildJsonObject {
            put("status", resp.status)
            // 控制跨桥体积，避免 32 MB 内存上限被大响应撞穿
            put("body", if (resp.body.length > 300_000) resp.body.take(300_000) else resp.body)
        }.toString()
    }

    private fun handleKv(payload: String): String {
        val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return ""
        val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return ""
        return when (obj["op"]?.jsonPrimitive?.contentOrNull) {
            "save" -> {
                kv[key] = obj["value"]?.jsonPrimitive?.contentOrNull ?: ""
                "1"
            }
            else -> kv[key] ?: ""
        }
    }

    private fun errorJson(message: String) = buildJsonObject {
        put("status", 0)
        put("body", message)
    }.toString()

    private fun driver(className: String, keywordJson: String, probeOnly: Boolean) = """
        const __probeOnly = $probeOnly;
        const __inst = new $className();
        const __meta = {
          name: __inst.name,
          key: __inst.key,
          version: __inst.version,
          minAppVersion: __inst.minAppVersion,
          capabilities: {
            search: !!__inst.search,
            explore: Array.isArray(__inst.explore),
            exploreTitles: (__inst.explore || []).map(function (e) { return e.title; }),
            comic: !!__inst.comic,
            novel: !!__inst.novel,
            account: !!__inst.account
          }
        };
        let __search = null;
        let __err = null;
        const __account = __inst.account ? {
          hasWebview: !!__inst.account.loginWithWebview,
          hasCookieLogin: !!__inst.account.loginWithCookies,
          cookieFields: (__inst.account.loginWithCookies && __inst.account.loginWithCookies.fields) || null,
          webviewUrl: (__inst.account.loginWithWebview && __inst.account.loginWithWebview.url) || null
        } : null;
        let __cookies = null;
        try {
          if (typeof __inst.init === 'function') {
            try { await __inst.init(); } catch (e) { __host.log('init failed: ' + e); }
          }
          try {
            const __probeUrl = __inst.baseUrl || (__account && __account.webviewUrl) || '';
            __cookies = __probeUrl ? Network.getCookies(__probeUrl) : [];
          } catch (e) { __cookies = 'ERR: ' + e; }
          if (!__probeOnly && __inst.search && __inst.search.load) {
            // 真实宿主按源声明的 optionList 生成默认选项，取每项第一个的 value 段
            const __opts = (__inst.search.optionList || []).map(function (o) {
              const list = o.options || [];
              if (list.length === 0) return null;
              const first = list[0];
              const label = (first && typeof first === 'object') ? first.value : first;
              return String(label).split('-')[0];
            });
            __search = await __inst.search.load($keywordJson, __opts, 1);
          }
        } catch (e) {
          __err = (e && e.message)
            ? (String(e) + ' || stack: ' + String(e.stack))
            : String(e);
        }
        let __detail = null;
        let __detailErr = null;
        try {
          if (__search && __search.comics && __search.comics.length > 0 &&
              __inst.comic && __inst.comic.loadInfo) {
            const __id = __search.comics[0].id;
            const __d = await __inst.comic.loadInfo(__id);
            __detail = {
              title: __d.title,
              subtitle: __d.subtitle,
              chapterCount: (__d.chapters instanceof Map)
                ? __d.chapters.size
                : Object.keys(__d.chapters || {}).length,
              tagNamespaces: (__d.tags instanceof Map)
                ? Array.from(__d.tags.keys())
                : Object.keys(__d.tags || {}),
              maxPage: __d.maxPage
            };
          }
        } catch (e) {
          __detailErr = String(e && e.message ? e : e);
        }
        JSON.stringify(
          { meta: __meta, account: __account, cookiesSeen: __cookies, logs: __logs,
            searchSample: __search, detailSample: __detail, detailError: __detailErr, error: __err },
          function (k, v) { return v instanceof Map ? Object.fromEntries(v) : v; }
        );
    """.trimIndent()

    companion object {
        /**
         * 契约层 polyfill：真实源依赖这些全局符号存在。
         * 形状对齐 architecture.md 4.5，只实现最小集。
         */
        private val POLYFILL = """
            const __logs = [];
            const APP = { locale: 'zh_CN', version: '1.6.5', platform: 'desktop' };
            class ComicSource {
              constructor() {}
              loadData(key) {
                return __host.kv(JSON.stringify({ op: 'load', key: this.key + '\u0000' + key })) || null;
              }
              saveData(key, value) {
                __host.kv(JSON.stringify({ op: 'save', key: this.key + '\u0000' + key, value: String(value) }));
              }
            }
            class Comic { constructor(o) { Object.assign(this, o || {}); } }
            class ComicDetails { constructor(o) { Object.assign(this, o || {}); } }
            class Novel { constructor(o) { Object.assign(this, o || {}); } }
            class Cookie { constructor(o) { Object.assign(this, o || {}); } }
            const Network = {
              get: async function (url, headers) {
                const r = await __host.request(JSON.stringify({
                  method: 'GET', url: url, headers: headers || {}, body: null
                }));
                return JSON.parse(r);
              },
              post: async function (url, headers, data) {
                const r = await __host.request(JSON.stringify({
                  method: 'POST', url: url, headers: headers || {},
                  body: data === undefined ? null : data
                }));
                return JSON.parse(r);
              },
              getCookies: function (url) {
                return JSON.parse(__host.cookieOp(JSON.stringify({ op: 'list', url: url })));
              },
              setCookies: function (url, list) {
                return __host.cookieOp(JSON.stringify({ op: 'set', url: url, list: list || [] })) && true;
              },
              deleteCookies: function (url) {
                return __host.cookieOp(JSON.stringify({ op: 'delete', url: url })) && true;
              }
            };
            // 浏览器形状的 fetch：MangaDex 等源直接用它，不走 Network.*
            async function fetch(url, options) {
              const opt = options || {};
              const r = await __host.request(JSON.stringify({
                method: opt.method || 'GET',
                url: url,
                headers: opt.headers || {},
                body: (opt.body === undefined || opt.body === null) ? null : opt.body
              }));
              const parsed = JSON.parse(r);
              return {
                ok: parsed.status >= 200 && parsed.status < 300,
                status: parsed.status,
                url: url,
                text: async function () { return parsed.body; },
                json: async function () { return JSON.parse(parsed.body); }
              };
            }
            function randomInt(a, b) { return a + Math.floor(Math.random() * (b - a + 1)); }
            function randomDouble(a, b) { return a + Math.random() * (b - a); }
            const console = { log: function () {
              const s = Array.prototype.slice.call(arguments).map(String).join(' ');
              __logs.push(s);
              __host.log(s);
            } };
            async function compute(script, buffer) { return buffer; }
        """.trimIndent()
    }
}
