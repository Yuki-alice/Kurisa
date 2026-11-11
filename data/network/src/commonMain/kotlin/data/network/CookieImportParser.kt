package data.network

import core.model.CookieSource
import core.model.StoredCookie
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ParseResult(
    val cookies: List<StoredCookie>,
    val format: String,
    val warnings: List<String>,
)

/**
 * 粘贴解析器（M0 CookieImportParser 产品化，5 种格式收口）。
 * 桌面端 `loginWithWebview` 缺失时的主路径：用户从浏览器 DevTools 复制，宿主解析后写入 CookieJar。
 */
object CookieImportParser {
    fun parse(raw: String, domain: String, nowMs: Long, pairedUserAgent: String?): ParseResult {
        val text = raw.trim().removeSurrounding("\"\"\"")
        val warnings = mutableListOf<String>()
        if (text.isEmpty()) return ParseResult(emptyList(), "empty", listOf("粘贴内容为空"))

        val pairs: List<Pair<String, String>> = when {
            text.startsWith("[") || text.startsWith("{") -> {
                val fromJson = parseJson(text, warnings)
                return ParseResult(
                    fromJson.map { (k, v) -> mk(domain, k, v, nowMs, pairedUserAgent) },
                    "json",
                    if (fromJson.isEmpty()) warnings + "JSON 中未找到 name/value 字段" else warnings,
                )
            }
            text.contains('\t') -> {
                text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                    val cols = line.split('\t')
                    if (cols.size < 2) null else cols[0].trim() to cols[1].trim()
                }.also { warnings += "按 DevTools 表格（制表符分隔）解析" }
            }
            text.lines().count { it.contains('=') && it.isNotBlank() } > 1 -> {
                text.lines().filter { it.isNotBlank() && it.contains('=') }.map {
                    it.substringBefore('=').trim() to it.substringAfter('=').trim()
                }.also { warnings += "按多行 name=value 解析" }
            }
            else -> {
                text.split(';').mapNotNull { part ->
                    val p = part.trim()
                    if (!p.contains('=')) null else p.substringBefore('=').trim() to p.substringAfter('=').trim()
                }
            }
        }

        val cleaned = pairs
            .filter { it.first.isNotEmpty() && it.first.matches(Regex("""[A-Za-z0-9_\-.#$%]+""")) }
            .distinctBy { it.first }

        if (cleaned.isEmpty()) warnings += "未解析出任何合法 Cookie 名"

        val format = when {
            text.contains('\t') -> "tab-separated"
            text.lines().count { it.contains('=') } > 1 -> "multi-line"
            text.contains(';') -> "cookie-header"
            else -> "single-pair"
        }

        return ParseResult(cleaned.map { (k, v) -> mk(domain, k, v, nowMs, pairedUserAgent) }, format, warnings)
    }

    private fun mk(domain: String, name: String, value: String, nowMs: Long, ua: String?) = StoredCookie(
        domain = domain,
        name = name,
        value = value,
        obtainedAtEpochMs = nowMs,
        source = CookieSource.MANUAL_IMPORT,
        pairedUserAgent = ua,
    )

    private fun parseJson(text: String, warnings: MutableList<String>): List<Pair<String, String>> {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            ?: run { warnings += "JSON 解析失败"; return emptyList() }
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["cookies"] as? JsonArray) ?: JsonArray(listOf(element))
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val n = o["name"]?.jsonPrimitive?.contentOrNull
            val v = o["value"]?.jsonPrimitive?.contentOrNull
            if (n.isNullOrBlank() || v == null) null else n to v
        }
    }
}
