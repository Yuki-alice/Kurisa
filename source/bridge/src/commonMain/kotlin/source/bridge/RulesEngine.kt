package source.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind
import source.contract.ImageLoadConfig
import source.contract.RulesPackage
import source.contract.RulesPackageJson
import source.hostapi.HostApi

/**
 * 一体包规则子集执行器（P2 最小闭环，见 `docs/plugin-system/SOURCE-PACKAGE.md`）。
 *
 * 范围冻结：HTML + CSS 选择器子集；JSONPath/XPath 显式 deferred。
 * 纯对象走 Kotlin（不起 QuickJS），任一字段为函数即按 `js/v1` 走长驻引擎（逃生舱，不在本文件）。
 * 复用同一 `HostApi`（白名单/Cookie/UA/审计）、同一返回形状（`works/maxPage`、`chapters` 数组、
 * `{images}` / `{content,format}`），`BridgeChapterProvider` 与 `SourceRuntime` 能力门零改动。
 *
 * 数据模型见 `source.contract.RulesPackage`（L1 纯 Kotlin，安装期解析共用同一形状）。
 */

/** 规则字段迷你语言：`selector[@attr] [| alt-source...] [| filter...]`，过滤器仅 `trim/absUrl/fileName`。 */
internal object RulesField {
    private val filters = setOf("trim", "absUrl", "fileName")
    private val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    fun encodeKeyword(raw: String): String = buildString {
        // encodeURIComponent 近似：未保留字符原样，其余按 UTF-8 百分号编码（含 multibyte）。
        for (ch in raw) {
            if (ch in unreserved) append(ch)
            else {
                val bytes = ch.toString().encodeToByteArray()
                for (b in bytes) {
                    append('%')
                    append("0123456789ABCDEF"[(b.toInt() and 0xFF) shr 4])
                    append("0123456789ABCDEF"[b.toInt() and 0x0F])
                }
            }
        }
    }

    fun absUrl(base: String, href: String): String =
        source.contract.ModelNormalize.absUrl(base, href)

    fun fileName(url: String): String =
        source.contract.ModelNormalize.fileName(url)

    fun renderUrl(
        template: String,
        baseUrl: String,
        vars: Map<String, String>,
        settings: Map<String, String> = emptyMap(),
    ): String {
        var out = template
            .replace("@keyword", "__KW__")
            .replace("@page", vars["page"].orEmpty())
            .replace("@id", vars["id"].orEmpty())
            .replace("@chapter", vars["chapter"].orEmpty())
            .replace("@source", vars["source"].orEmpty())
        if ("__KW__" in out) {
            out = out.replace("__KW__", encodeKeyword(vars["keyword"].orEmpty()))
        }
        // `@setting:key` 按当前设置快照展开（用户值 → default → ""）。
        if ("@setting:" in out && settings.isNotEmpty()) {
            Regex("""@setting:([A-Za-z0-9_]+)""").findAll(out).toList().reversed().forEach { m ->
                out = out.replaceRange(m.range, settings[m.groupValues[1]].orEmpty())
            }
        }
        val trimmed = out.trim()
        if (trimmed.isEmpty()) return baseUrl
        return absUrl(baseUrl, trimmed)
    }

    /** 对单条 item（HostHtml select 行：text/html/attr:*）求字段值。 */
    fun evalItem(
        host: source.hostapi.HostHtml,
        item: Map<String, String>,
        expr: String,
        baseUrl: String,
    ): String {
        val segments = expr.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ""
        val sources = mutableListOf<String>()
        val ops = mutableListOf<String>()
        for (s in segments) {
            // 含空格或已知过滤器名 → 过滤器；否则是有备选源（如 @data-src、a@href）。
            if (s in filters) ops += s
            else sources += s
        }
        var value = ""
        for (src in sources) {
            value = evalSource(host, item, src)
            if (value.isNotEmpty()) break
        }
        for (op in ops) {
            value = when (op) {
                "trim" -> value.trim()
                "absUrl" -> absUrl(baseUrl, value)
                "fileName" -> fileName(value)
                else -> value
            }
        }
        return value
    }

    private fun evalSource(
        host: source.hostapi.HostHtml,
        item: Map<String, String>,
        src: String,
    ): String {
        val at = src.lastIndexOf('@')
        // 纯 @text/@html/@attr（无选择器）：直接读 item 行。
        if (at == 0 || (at > 0 && src.substring(0, at).isBlank())) {
            val attr = src.substring(at + 1).trim()
            return when (attr) {
                "text" -> item["text"].orEmpty()
                "html", "innerHtml", "innerHTML" -> item["html"].orEmpty()
                else -> item["attr:$attr"].orEmpty()
            }
        }
        val selector: String
        val attr: String
        if (at < 0) {
            selector = src.trim()
            attr = "text"
        } else {
            selector = src.substring(0, at).trim()
            attr = src.substring(at + 1).trim()
            if (selector.isEmpty()) {
                return when (attr) {
                    "text" -> item["text"].orEmpty()
                    "html", "innerHtml", "innerHTML" -> item["html"].orEmpty()
                    else -> item["attr:$attr"].orEmpty()
                }
            }
        }
        if (selector.isEmpty()) return ""
        // 以 item 内层 HTML 建临时文档查询（与 Venera HtmlElement 元素级查询同构），用完即 dispose。
        val doc = host.parse(item["html"].orEmpty())
        return try {
            val els = host.select(doc, selector)
            val el = els.firstOrNull() ?: return ""
            when (attr) {
                "text" -> el["text"].orEmpty()
                "html", "innerHtml", "innerHTML" -> el["html"].orEmpty()
                else -> el["attr:$attr"].orEmpty()
            }
        } finally {
            host.dispose(doc)
        }
    }
}

/**
 * 规则引擎（`SourcePluginEngine` 实现，与 `QuickJsEngine` 同接口，可被 `SourceRuntime` 直接包装）。
 *
 * - `load(script)`：解析规则 JSON（`rules/v1`，未知字段忽略）；`format` 非 `rules/v1` 即 `CONTRACT`。
 * - `describe()`：合成描述符 JSON（存在位），供 `SourceDescriptor.fromJson` 走同一能力交集语义。
 * - 主链路经 `HostApi.network/host.html`，缺段（无 search/detail/content 对应段）按 `CONTRACT` 显式失败。
 */
class RulesEngine(
    private val sourceKey: String,
    private val host: HostApi,
    private val apiVersion: Int = 4,
) : SourcePluginEngine {
    private var pkg: RulesPackage? = null
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(script: String) {
        val p = runCatching { RulesPackageJson.decode(script) }.getOrElse {
            throw SourceBridgeException("规则解析失败：${it.message}")
        }
        if (p.format != "rules/v1") {
            throw SourceBridgeException("不支持的规则 format：${p.format}")
        }
        if ((p.search?.mode ?: "html") != "html" || (p.detail?.mode ?: "html") != "html") {
            throw SourceBridgeException("规则 mode 仅支持 html（json deferred）")
        }
        if (p.search == null || p.detail == null || p.content == null) {
            throw SourceBridgeException("规则缺 search/detail/content 段")
        }
        pkg = p
    }

    override suspend fun describe(): String {
        val p = pkg ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        val hasImages = p.content?.images != null
        val hasText = p.content?.text != null
        val kinds = p.kind.ifEmpty { listOf("manga") }
        fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
        fun jint(i: Int) = kotlinx.serialization.json.JsonPrimitive(i)
        fun jbool(b: Boolean) = kotlinx.serialization.json.JsonPrimitive(b)
        fun jarr(vararg ss: String) = JsonArray(ss.map { jstr(it) })
        return JsonObject(
            mapOf(
                "key" to jstr(p.key.ifBlank { sourceKey }),
                "name" to jstr(p.name),
                "version" to jstr(p.version),
                "minAppVersion" to jstr("1.0.0"),
                "apiVersion" to jint(apiVersion),
                "kinds" to JsonArray(kinds.map { jstr(it) }),
                "domains" to JsonArray(p.domains.map { jstr(it) }),
                "capabilities" to JsonArray(
                    buildList {
                        add(jstr("search"))
                        if (hasImages) add(jstr("comic"))
                        if (hasText) add(jstr("novel"))
                    },
                ),
                "settings" to JsonObject(
                    p.settings.mapValues { (_, s) ->
                        JsonObject(
                            mapOf(
                                "title" to jstr(s.title),
                                "type" to jstr(s.type.ifBlank { "select" }),
                                "options" to JsonArray(
                                    s.options.map {
                                        JsonObject(
                                            mapOf(
                                                "value" to jstr(it.value),
                                                "text" to jstr(it.text.ifBlank { it.value }),
                                            ),
                                        )
                                    },
                                ),
                                "default" to jstr(s.default),
                            ),
                        )
                    },
                ),
                "translationLocales" to JsonArray(emptyList()),
                "hasInit" to jbool(false),
                "hasImageLoad" to jbool(false),
                "hasSearch" to jbool(true),
                "hasSearchLoad" to jbool(true),
                "hasSearchNext" to jbool(false),
                "hasSearchPage" to jbool(false),
                "hasComic" to jbool(hasImages),
                "hasNovel" to jbool(hasText),
                "hasNovelLoadInfo" to jbool(hasText),
                "hasNovelLoadChapter" to jbool(hasText),
                "hasNovelImageResolve" to jbool(false),
                "hasExplore" to jbool(false),
                "hasCategory" to jbool(false),
                "hasAccount" to jbool(false),
                "hasFavorites" to jbool(false),
            ),
        ).toString()
    }

    override fun applySettings(settingsJson: String) {
        // 回退链：用户值 → 包 default → ""，写源私有 `setting:` 命名空间（与 QuickJsEngine 前缀一致）。
        val p = pkg ?: return
        if (p.settings.isEmpty()) return
        val user = runCatching { json.parseToJsonElement(settingsJson).jsonObject }.getOrNull()
        p.settings.forEach { (key, s) ->
            if (key.isBlank()) return@forEach
            val v = user?.get(key)?.jsonPrimitive?.contentOrNull ?: s.default
            host.storage.set(sourceKey, "setting:$key", v)
        }
    }

    /** 当前设置快照（用户值 → default → ""），供 URL 模板 `@setting:key` 展开。 */
    private fun currentSettings(): Map<String, String> {
        val p = pkg ?: return emptyMap()
        return p.settings.mapValues { (key, s) ->
            host.storage.get(sourceKey, "setting:$key") ?: s.default
        }
    }

    override fun callSync(method: String, payload: String): String = when (method) {
        "html" -> callHtml(payload)
        else -> throw SourceBridgeException("规则引擎未知同步方法：$method")
    }

    private fun callHtml(payload: String): String {
        // 复用 QuickJsEngine 的 html 契约形状（parse/select/selectMany/dispose），便于规则测试面板复用。
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "parse" -> """{"doc":${host.html.parse(o["html"]?.jsonPrimitive?.contentOrNull.orEmpty())}}"""
            "select" -> {
                val doc = o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return """{"error":"disposed-or-unknown"}"""
                encodeElements(host.html.select(doc, o["css"]?.jsonPrimitive?.contentOrNull.orEmpty()))
            }
            "selectMany" -> {
                val doc = o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return """{"error":"disposed-or-unknown"}"""
                val queries = (o["queries"] as? JsonArray)?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                }.orEmpty()
                buildString {
                    append('[')
                    host.html.selectMany(doc, queries).forEachIndexed { i, els ->
                        if (i > 0) append(',')
                        append(encodeElements(els))
                    }
                    append(']')
                }
            }
            "dispose" -> {
                o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { host.html.dispose(it) }
                """{"ok":true}"""
            }
            else -> """{"error":"unknown-op","code":"CONTRACT"}"""
        }
    }

    private fun encodeElements(els: List<Map<String, String>>): String = buildString {
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

    override suspend fun callAsync(method: String, payload: String, timeoutMs: Long): String =
        when (method) {
            "request" -> callNet(payload)
            "requestAll" -> callNetAll(payload)
            "delay" -> "1"
            "hostCall" -> callHostCall(payload)
            else -> throw SourceBridgeException("规则引擎未知异步方法：$method")
        }

    private suspend fun callNet(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"status":0,"body":"BAD_PAYLOAD","headers":{}}"""
        val method = (o["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        val url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val headers = (o["headers"] as? JsonObject)?.entries?.associate {
            it.key to (it.value.jsonPrimitive.contentOrNull.orEmpty())
        }.orEmpty()
        val data = o["data"]?.jsonPrimitive?.contentOrNull
        val r = host.network.sendRequest(method, url, headers, data)
        return encodeNet(r.status, r.body, r.headers, r.finalUrl, r.verdict, r.truncated)
    }

    private suspend fun callNetAll(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        val arr = (o["requests"] as? JsonArray) ?: return "[]"
        if (arr.size > 20) return """{"error":"too-many-requests","code":"CONTRACT"}"""
        val reqs = arr.mapNotNull { el ->
            val jo = el as? JsonObject ?: return@mapNotNull null
            val url = jo["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            source.hostapi.HostRequest(
                method = jo["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
                url = url,
                headers = (jo["headers"] as? JsonObject)?.entries?.associate {
                    it.key to (it.value.jsonPrimitive.contentOrNull.orEmpty())
                }.orEmpty(),
                data = jo["data"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return buildString {
            append('[')
            host.network.requestAll(reqs).forEachIndexed { i, r ->
                if (i > 0) append(',')
                append(encodeNet(r.status, r.body, r.headers, r.finalUrl, r.verdict, r.truncated))
            }
            append(']')
        }
    }

    private suspend fun callHostCall(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        return when (o["method"]?.jsonPrimitive?.contentOrNull) {
            "network.requestAll" -> callNetAll(payload)
            "network.get", "network.post", "network.put", "network.patch", "network.delete",
            "network.sendRequest", "network.fetchBytes", "http" -> callNet(payload)
            else -> """{"error":"unsupported-hostcall","code":"PERMISSION"}"""
        }
    }

    private fun encodeNet(
        status: Int,
        body: String,
        headers: Map<String, String>,
        finalUrl: String,
        verdict: String?,
        truncated: Boolean,
    ): String = buildString {
        append("""{"status":$status,"body":""")
        append(Json.encodeToString(kotlinx.serialization.serializer<String>(), body))
        append(""","headers":""")
        append(encodeMap(headers))
        append(""","finalUrl":""")
        append(Json.encodeToString(kotlinx.serialization.serializer<String>(), finalUrl))
        append(""","verdict":""")
        if (verdict == null) append("null")
        else append(Json.encodeToString(kotlinx.serialization.serializer<String>(), verdict))
        append(""","truncated":""")
        append(if (truncated) "true" else "false")
        append("}")
    }

    private fun encodeMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        return buildString {
            append('{')
            var first = true
            map.entries.sortedBy { it.key }.forEach { (k, v) ->
                if (!first) append(',')
                first = false
                append(Json.encodeToString(kotlinx.serialization.serializer<String>(), k))
                append(':')
                append(Json.encodeToString(kotlinx.serialization.serializer<String>(), v))
            }
            append('}')
        }
    }

    override fun close() {
        pkg = null
    }

    override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String {
        val p = pkg ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        val r = executeSearch(p, keyword, page)
        fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
        return JsonObject(
            mapOf(
                "works" to JsonArray(
                    r.works.map { (id, title, cover) ->
                        JsonObject(
                            buildMap {
                                put("id", jstr(id))
                                put("title", jstr(title))
                                if (cover.isNotBlank()) put("cover", jstr(cover))
                                put("kind", jstr(r.kind))
                            },
                        )
                    },
                ),
                "maxPage" to kotlinx.serialization.json.JsonPrimitive(r.maxPage),
            ),
        ).toString()
    }

    /**
     * 规则测试面板后端（P3 工具链；Kazumi `plugin_test_page` 三步对照）。
     * 返回「请求 → 解析 → 内容」诊断：请求 URL/状态、原始长度、列表命中数、
     * 字段样本（前 3 条 id/title）、maxPage。不抛 CONTRACT，用 `error` 字段承载失败，
     * 便于面板展示 rawResponse / matchedFragments / diagnostics。
     */
    suspend fun diagnose(keyword: String, page: Int): String {
        val p = pkg ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
        return try {
            val r = executeSearch(p, keyword, page)
            JsonObject(
                mapOf(
                    "requestUrl" to jstr(r.url),
                    "status" to kotlinx.serialization.json.JsonPrimitive(r.status),
                    "rawLength" to kotlinx.serialization.json.JsonPrimitive(r.rawLength),
                    "listSelector" to jstr(r.listSelector),
                    "matched" to kotlinx.serialization.json.JsonPrimitive(r.matched),
                    "kept" to kotlinx.serialization.json.JsonPrimitive(r.works.size),
                    "maxPage" to kotlinx.serialization.json.JsonPrimitive(r.maxPage),
                    "sample" to JsonArray(
                        r.works.take(3).map { (id, title, cover) ->
                            JsonObject(
                                buildMap {
                                    put("id", jstr(id))
                                    put("title", jstr(title))
                                    if (cover.isNotBlank()) put("cover", jstr(cover))
                                },
                            )
                        },
                    ),
                ),
            ).toString()
        } catch (e: SourcePluginException) {
            JsonObject(
                mapOf(
                    "error" to jstr(e.message ?: "规则诊断失败"),
                    "code" to jstr(e.kind.name),
                ),
            ).toString()
        }
    }

    private data class SearchExecution(
        val url: String,
        val status: Int,
        val rawLength: Int,
        val listSelector: String,
        val matched: Int,
        val works: List<Triple<String, String, String>>,
        val maxPage: Int,
        val kind: String,
    )

    private suspend fun executeSearch(pkg: RulesPackage, keyword: String, page: Int): SearchExecution {
        val p = pkg
        val s = p.search ?: throw SourceBridgeException("规则缺 search 段", cause = null).wrapContract()
        if (s.list.isBlank()) throw SourceBridgeException("规则 search.list 为空").wrapContract()
        val url = RulesField.renderUrl(
            s.request.url.ifBlank { "/" }, p.baseUrl,
            mapOf("keyword" to keyword, "page" to page.toString()),
            currentSettings(),
        )
        val res = host.network.sendRequest(s.request.method.ifBlank { "GET" }, url, emptyMap(), null)
        if (res.status != 200) {
            throw SourceBridgeException("规则搜索请求失败: ${res.status}").wrapContract()
        }
        val doc = host.html.parse(res.body)
        try {
            val items = host.html.select(doc, s.list)
            val kind = p.kind.firstOrNull() ?: "manga"
            val works = items.mapNotNull { item ->
                val id = RulesField.evalItem(host.html, item, s.fields["id"] ?: "@href | fileName", p.baseUrl)
                val title = RulesField.evalItem(host.html, item, s.fields["title"] ?: "@text | trim", p.baseUrl)
                if (id.isBlank() || title.isBlank()) return@mapNotNull null
                val coverRaw = s.fields["cover"]?.let { RulesField.evalItem(host.html, item, it, p.baseUrl) }.orEmpty()
                Triple(id, title, coverRaw)
            }
            val maxPage = resolveMaxPage(host.html, res.body, s.pagination?.maxPage ?: "auto", page)
            return SearchExecution(url, res.status, res.body.length, s.list, items.size, works, maxPage, kind)
        } finally {
            host.html.dispose(doc)
        }
    }

    private fun resolveMaxPage(
        html: source.hostapi.HostHtml,
        body: String,
        expr: String,
        page: Int,
    ): Int {
        val e = expr.trim()
        if (e.isEmpty() || e == "1") return 1
        e.toIntOrNull()?.let { return it.coerceAtLeast(1) }
        // auto 启发式：常见末页选择器取数字最大值，失败回退 page+1（调用方有上限保护）。
        val doc = html.parse(body)
        return try {
            val candidates = listOf(".last", ".page-last", ".pagination a", ".pages a", "a.last")
                .flatMap { sel ->
                    runCatching { html.select(doc, sel) }.getOrElse { emptyList() }
                }
            val nums = candidates.flatMap { el ->
                listOf(el["text"].orEmpty(), el["attr:href"].orEmpty(), el["attr:data-page"].orEmpty())
            }.flatMap { t -> Regex("""\d+""").findAll(t).map { it.value.toIntOrNull() ?: 0 } }
            (nums.maxOrNull() ?: (page + 1)).coerceAtLeast(1).coerceAtMost(500)
        } finally {
            html.dispose(doc)
        }
    }

    override suspend fun defaultSearchOptions(): String = "[]"

    override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String {
        val p = pkg ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        val r = executeDetail(p, workId)
        fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
        return JsonObject(
            buildMap {
                put("title", jstr(r.title))
                if (r.cover.isNotBlank()) put("cover", jstr(r.cover))
                put(
                    "chapters",
                    JsonArray(
                        r.chapters.map { (id, t) ->
                            JsonObject(mapOf("id" to jstr(id), "title" to jstr(t)))
                        },
                    ),
                )
            },
        ).toString()
    }

    /** 测试面板第二步：目录诊断（请求 → 解析 → 内容条目）。 */
    suspend fun diagnoseDetail(workId: String): String {
        val p = pkg ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
        fun jint(i: Int) = kotlinx.serialization.json.JsonPrimitive(i)
        return try {
            val r = executeDetail(p, workId)
            JsonObject(
                mapOf(
                    "requestUrl" to jstr(r.url),
                    "status" to jint(r.status),
                    "rawLength" to jint(r.rawLength),
                    "titleSelector" to jstr(r.titleSelector),
                    "title" to jstr(r.title),
                    "listSelector" to jstr(r.listSelector),
                    "matched" to jint(r.matched),
                    "kept" to jint(r.chapters.size),
                    "sample" to JsonArray(
                        r.chapters.take(3).map { (id, t) ->
                            JsonObject(mapOf("id" to jstr(id), "title" to jstr(t)))
                        },
                    ),
                ),
            ).toString()
        } catch (e: SourcePluginException) {
            JsonObject(
                mapOf(
                    "error" to jstr(e.message ?: "规则诊断失败"),
                    "code" to jstr(e.kind.name),
                ),
            ).toString()
        }
    }

    private data class DetailExecution(
        val url: String,
        val status: Int,
        val rawLength: Int,
        val titleSelector: String,
        val title: String,
        val cover: String,
        val listSelector: String,
        val matched: Int,
        val chapters: List<Pair<String, String>>,
    )

    private suspend fun executeDetail(pkg: RulesPackage, workId: String): DetailExecution {
        val p = pkg
        val d = p.detail ?: throw SourceBridgeException("规则缺 detail 段").wrapContract()
        val url = RulesField.renderUrl(
            d.request.url.ifBlank { "/" }, p.baseUrl, mapOf("id" to workId), currentSettings(),
        )
        val res = host.network.sendRequest(d.request.method.ifBlank { "GET" }, url, emptyMap(), null)
        if (res.status != 200) throw SourceBridgeException("规则详情请求失败: ${res.status}").wrapContract()
        val doc = host.html.parse(res.body)
        try {
            val title = RulesField.evalItem(
                host.html, mapOf("text" to "", "html" to res.body), d.title.ifBlank { "h1@text" }, p.baseUrl,
            ).ifBlank {
                // title 表达式可能指向整页（如 h1@text）：上面已用整页 body 求值；仍为空则按原文返回 id。
                workId
            }
            val cover = if (d.cover.isBlank()) "" else RulesField.evalItem(
                host.html, mapOf("text" to "", "html" to res.body), d.cover, p.baseUrl,
            )
            val rawItems = if (d.chapters.list.isBlank()) emptyList()
            else host.html.select(doc, d.chapters.list)
            val chapters = rawItems.mapNotNull { item ->
                val id = RulesField.evalItem(host.html, item, d.chapters.id, p.baseUrl)
                val t = RulesField.evalItem(host.html, item, d.chapters.title, p.baseUrl)
                if (id.isBlank() || t.isBlank()) return@mapNotNull null
                id to t
            }
            if (chapters.isEmpty()) throw SourceBridgeException("规则目录为空").wrapContract()
            return DetailExecution(url, res.status, res.body.length, d.title.ifBlank { "h1@text" }, title, cover, d.chapters.list, rawItems.size, chapters)
        } finally {
            host.html.dispose(doc)
        }
    }

    override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind): String {
        val p = pkg ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
        return when (val r = executeContent(p, workId, chapterId)) {
            is ContentExecution.Images -> JsonObject(
                mapOf("images" to JsonArray(r.urls.map { jstr(it) })),
            ).toString()
            is ContentExecution.Text -> JsonObject(
                mapOf("content" to jstr(r.body), "format" to jstr(r.format)),
            ).toString()
        }
    }

    /** 测试面板第三步：内容诊断（图片列表 / 正文清洗）。 */
    suspend fun diagnoseContent(workId: String, chapterId: String): String {
        val p = pkg ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        fun jstr(s: String) = kotlinx.serialization.json.JsonPrimitive(s)
        fun jint(i: Int) = kotlinx.serialization.json.JsonPrimitive(i)
        return try {
            when (val r = executeContent(p, workId, chapterId)) {
                is ContentExecution.Images -> JsonObject(
                    mapOf(
                        "requestUrl" to jstr(r.url),
                        "status" to jint(r.status),
                        "rawLength" to jint(r.rawLength),
                        "mode" to jstr("images"),
                        "listSelector" to jstr(r.listSelector),
                        "matched" to jint(r.matched),
                        "kept" to jint(r.urls.size),
                        "sample" to JsonArray(r.urls.take(3).map { jstr(it) }),
                    ),
                ).toString()
                is ContentExecution.Text -> JsonObject(
                    mapOf(
                        "requestUrl" to jstr(r.url),
                        "status" to jint(r.status),
                        "rawLength" to jint(r.rawLength),
                        "mode" to jstr("text"),
                        "select" to jstr(r.select),
                        "matched" to jint(r.matched),
                        "format" to jstr(r.format),
                        "length" to jint(r.body.length),
                        "excerpt" to jstr(r.body.take(120)),
                    ),
                ).toString()
            }
        } catch (e: SourcePluginException) {
            JsonObject(
                mapOf(
                    "error" to jstr(e.message ?: "规则诊断失败"),
                    "code" to jstr(e.kind.name),
                ),
            ).toString()
        }
    }

    private sealed interface ContentExecution {
        val url: String
        val status: Int
        val rawLength: Int
        val matched: Int

        data class Images(
            override val url: String,
            override val status: Int,
            override val rawLength: Int,
            val listSelector: String,
            override val matched: Int,
            val urls: List<String>,
        ) : ContentExecution

        data class Text(
            override val url: String,
            override val status: Int,
            override val rawLength: Int,
            val select: String,
            override val matched: Int,
            val format: String,
            val body: String,
        ) : ContentExecution
    }

    private suspend fun executeContent(
        pkg: RulesPackage,
        workId: String,
        chapterId: String,
    ): ContentExecution {
        val p = pkg
        val c = p.content ?: throw SourceBridgeException("规则缺 content 段").wrapContract()
        val url = RulesField.renderUrl(
            // 内容页默认复用详情地址模板（@id/@chapter 占位由规则 request 决定）：
            // 若规则未给独立 request，约定以 chapterId 为相对地址。
            chapterTpl(p, chapterId), p.baseUrl, mapOf("id" to workId, "chapter" to chapterId),
            currentSettings(),
        )
        val images = c.images
        val text = c.text
        if (images != null) {
            val res = host.network.sendRequest("GET", url, emptyMap(), null)
            if (res.status != 200) throw SourceBridgeException("规则内容请求失败: ${res.status}").wrapContract()
            val doc = host.html.parse(res.body)
            try {
                val rawItems = host.html.select(doc, images.list)
                val urls = rawItems.mapNotNull { item ->
                    RulesField.evalItem(host.html, item, images.value, p.baseUrl).ifBlank { null }
                }.filter { it.isNotBlank() }.distinct()
                if (urls.isEmpty()) throw SourceBridgeException("规则图片为空").wrapContract()
                return ContentExecution.Images(url, res.status, res.body.length, images.list, rawItems.size, urls)
            } finally {
                host.html.dispose(doc)
            }
        } else if (text != null) {
            val res = host.network.sendRequest("GET", url, emptyMap(), null)
            if (res.status != 200) throw SourceBridgeException("规则内容请求失败: ${res.status}").wrapContract()
            val doc = host.html.parse(res.body)
            try {
                val els = host.html.select(doc, text.select)
                val el = els.firstOrNull() ?: throw SourceBridgeException("规则正文为空").wrapContract()
                return if (text.clean) {
                    val cleaned = host.novel.stripHtml(el["html"].orEmpty())
                    if (cleaned.isBlank()) throw SourceBridgeException("规则正文为空").wrapContract()
                    ContentExecution.Text(url, res.status, res.body.length, text.select, els.size, "plain", cleaned)
                } else {
                    ContentExecution.Text(url, res.status, res.body.length, text.select, els.size, "html", el["html"].orEmpty())
                }
            } finally {
                host.html.dispose(doc)
            }
        } else {
            throw SourceBridgeException("规则 content 须含 images 或 text").wrapContract()
        }
    }

    private fun chapterTpl(p: RulesPackage, chapterId: String): String {
        // 最小闭环：chapterId 已是绝对/相对地址时直接采用；规则如需自定义模板，后续在 content 段增 request 字段。
        if (chapterId.startsWith("http://") || chapterId.startsWith("https://")) return chapterId
        return chapterId
    }

    override suspend fun imageLoadConfig(url: String, workId: String, chapterId: String): ImageLoadConfig? = null

    override suspend fun invoke(path: String, argsJson: String): String {
        require(path.matches(Regex("""[A-Za-z_$][\w$.]*"""))) { "非法调用路径：$path" }
        val args = runCatching { Json.parseToJsonElement(argsJson) }.getOrNull()
            ?: throw SourceBridgeException("参数须为 JSON 数组")
        require(args is JsonArray) { "参数须为 JSON 数组" }
        return when (path) {
            "search.load" -> {
                val kw = args.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
                val opts = args.getOrNull(1)?.toString() ?: "[]"
                val page = args.getOrNull(2)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                searchWorks(kw, opts, page)
            }
            "comic.loadInfo", "novel.loadInfo" -> {
                val id = args.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
                // kind 按包首类推断；规则包单 kind 为主，多 kind 包以首项为准（文档约束）。
                val kind = ContentKind.of(pkg?.kind?.firstOrNull().orEmpty()) ?: ContentKind.MANGA
                loadWorkDetails(id, kind)
            }
            "comic.loadEp", "novel.loadChapter" -> {
                val w = args.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
                val c = args.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty()
                val kind = ContentKind.of(pkg?.kind?.firstOrNull().orEmpty()) ?: ContentKind.MANGA
                loadChapterPayload(w, c, kind)
            }
            "translate" -> {
                val key = args.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
                Json.encodeToString(kotlinx.serialization.serializer<String>(), key)
            }
            else -> throw SourceBridgeException("规则引擎不支持调用：$path")
        }
    }

    private fun SourceBridgeException.wrapContract(): SourcePluginException =
        SourcePluginException(this.message ?: "规则违约", source.contract.SourceErrorKind.CONTRACT, this)
}
