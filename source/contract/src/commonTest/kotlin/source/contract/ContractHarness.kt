package source.contract

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * M1 最小可执行 harness（仅 commonTest）：证明冻结契约“能跑”。
 * 覆盖：类发现 → init() → optionList 默认值 → 异步 search →
 * loadInfo → loadChapter/loadEp → HtmlDocument 解析+dispose。
 * 完整 Host API（Cookie/Convert/UI/Storage/Novel）是 M2 bridge 范围。
 */
class ContractHarness(
    /** fake 网络：宿主注入 Cookie/UA 的位置（M1 fixture 不断言 Cookie，仅占位）。 */
    private val http: suspend (method: String, url: String) -> String = { _, _ -> "{}" },
) {
    private val kv = mutableMapOf<String, String>()
    private val docs = mutableMapOf<Int, Document>()
    private var nextDoc = 1
    private var disposed = 0

    private fun isHostMissing(t: Throwable): Boolean =
        (t.message ?: "").contains("quickjs", ignoreCase = true)

    suspend fun run(sourceCode: String, keyword: String): String {
        val className = Regex("""class\s+(\w+)\s+extends\s+ComicSource""")
            .find(sourceCode)?.groupValues?.get(1)
            ?: error("未找到 `class X extends ComicSource`")
        val kw = Json.encodeToString(keyword)
        return try {
            quickJs {
                memoryLimit = 32L * 1024 * 1024
                maxStackSize = 256L * 1024
                evaluationTimeoutMillis = 30_000
                define("__host") {
                    asyncFunction("request") { payload: String -> handleRequest(payload) }
                    function("kv") { payload: String -> handleKv(payload) }
                    function("html") { payload: String -> handleHtml(payload) }
                    function("log") { msg: String -> msg.length }
                }
                evaluate<String>(POLYFILL + "\n" + sourceCode + "\n" + driver(className, kw))
            }
        } catch (t: Throwable) {
            if (isHostMissing(t)) "__HOST_MISSING__" else throw t
        }
    }

    fun disposedCount(): Int = disposed

    private suspend fun handleRequest(payload: String): String {
        val o = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"status":0,"body":"BAD_PAYLOAD"}"""
        val method = o["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return """{"status":0,"body":"NO_URL"}"""
        val body = try {
            http(method, url)
        } catch (e: Exception) {
            return """{"status":0,"body":"HOST_ERROR"}"""
        }
        val cut = if (body.length > 300_000) body.take(300_000) else body
        return buildJsonObject {
            put("status", 200)
            put("body", cut)
        }.toString()
    }

    private fun handleKv(payload: String): String {
        val o = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return ""
        val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return ""
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "save" -> {
                kv[key] = o["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
                "1"
            }
            else -> kv[key].orEmpty()
        }
    }

    private fun handleHtml(payload: String): String {
        val o = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "parse" -> {
                val html = o["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val id = nextDoc++
                docs[id] = Ksoup.parse(html)
                """{"doc":$id}"""
            }
            "select" -> {
                val id = o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                val doc = id?.let { docs[it] }
                    ?: return """{"error":"disposed-or-unknown"}"""
                val css = o["css"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val els = doc.select(css)
                buildJsonArray {
                    els.forEach { el ->
                        add(
                            buildJsonObject {
                                put("text", el.text())
                                put("html", el.html())
                                put(
                                    "attrs",
                                    buildJsonObject {
                                        el.attributes().forEach { a ->
                                            put(a.key, a.value)
                                        }
                                    },
                                )
                            },
                        )
                    }
                }.toString()
            }
            "dispose" -> {
                val id = o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (id != null && docs.remove(id) != null) disposed++
                """{"ok":true}"""
            }
            else -> """{"error":"unknown-op"}"""
        }
    }

    private fun driver(className: String, keywordJson: String) = """
        const __inst = new $className();
        let __search = null; let __err = null; let __detail = null; let __extra = null;
        try {
          if (typeof __inst.init === 'function') { await __inst.init(); }
          const __opts = (__inst.search.optionList || []).map(function (o) {
            const list = o.options || [];
            if (list.length === 0) return null;
            return String(list[0]).split('-')[0];
          });
          __search = await __inst.search.load($keywordJson, __opts, 1);
          const __first = (__search.comics && __search.comics[0]) || (__search.works && __search.works[0]);
          if (__first && __inst.comic && __inst.comic.loadInfo) {
            __detail = await __inst.comic.loadInfo(__first.id);
            const __ch = (__detail.chapters instanceof Map)
              ? Array.from(__detail.chapters.values())[0]
              : (__detail.chapters || [])[0];
            if (__ch && __inst.comic.loadEp) __extra = await __inst.comic.loadEp(__first.id, __ch.id);
          } else if (__first && __inst.novel && __inst.novel.loadInfo) {
            __detail = await __inst.novel.loadInfo(__first.id);
            const __ch = (__detail.chapters instanceof Map)
              ? Array.from(__detail.chapters.values())[0]
              : (__detail.chapters || [])[0];
            if (__ch && __inst.novel.loadChapter) __extra = await __inst.novel.loadChapter(__first.id, __ch.id);
          }
        } catch (e) { __err = String(e && e.stack || e); }
        JSON.stringify({ search: __search, detail: __detail, extra: __extra, error: __err },
          function (k, v) { return v instanceof Map ? Object.fromEntries(v) : v; });
    """.trimIndent()

    companion object {
        private val POLYFILL = """
            const APP = { locale: 'zh_CN', version: '1.0.0', platform: 'contract-test' };
            class ComicSource {
              loadData(key) {
                return __host.kv(JSON.stringify({ op: 'load', key: this.key + '::' + key })) || null;
              }
              saveData(key, value) {
                __host.kv(JSON.stringify({ op: 'save', key: this.key + '::' + key, value: String(value) }));
              }
            }
            class Comic { constructor(o) { Object.assign(this, o || {}); } }
            class ComicDetails { constructor(o) { Object.assign(this, o || {}); } }
            const Network = {
              get: async function (url, headers) {
                const r = await __host.request(JSON.stringify({ method: 'GET', url: url }));
                return JSON.parse(r);
              },
              post: async function (url, headers, data) {
                const r = await __host.request(JSON.stringify({ method: 'POST', url: url }));
                return JSON.parse(r);
              }
            };
            class HtmlDocument {
              constructor(html) {
                const r = JSON.parse(__host.html(JSON.stringify({ op: 'parse', html: html })));
                this.__doc = r.doc;
              }
              querySelectorAll(css) {
                const r = __host.html(JSON.stringify({ op: 'select', doc: this.__doc, css: css }));
                if (typeof r === 'string' && r.indexOf('disposed') >= 0) throw 'HtmlDocument disposed';
                return JSON.parse(r);
              }
              querySelector(css) {
                const a = this.querySelectorAll(css);
                return a[0] || null;
              }
              dispose() { __host.html(JSON.stringify({ op: 'dispose', doc: this.__doc })); }
            }
            const console = { log: function () { __host.log(Array.prototype.slice.call(arguments).join(' ')); } };
        """.trimIndent()
    }
}
