package source.contract

/**
 * 模型归一化（`HOST-SDK.md § Model`，P2 SDK 余组）。
 *
 * 位置说明：纯 Kotlin、零引擎依赖（L1），供规则执行器、Provider 适配层、
 * 仓库 CI 共用同一套语义。JS 源内的 `parseCard` 样板逐步收敛到此（DSL 侧对齐，
 * 不改冻结契约形状）。
 */
object ModelNormalize {
    /** 相对转绝对（协议相对 `//` 按 https 补齐；根路径/相对路径按 base 拼接）。 */
    fun absUrl(base: String, href: String): String {
        val h = href.trim()
        if (h.isEmpty()) return ""
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        if (h.startsWith("//")) return "https:$h"
        val b = base.trimEnd('/')
        if (h.startsWith("/")) return b + h
        return "$b/$h"
    }

    /** 取稳定业务 id：末段文件名去扩展名与查询串（如 `/comic/w1.html` → `w1`）。 */
    fun fileName(url: String): String {
        val noQuery = url.substringBefore('?').substringBefore('#')
        val seg = noQuery.trimEnd('/').substringAfterLast('/')
        if (seg.isEmpty()) return ""
        // 去常见章节扩展名，保留站点 id 语义。
        return seg.substringBeforeLast('.', seg)
    }

    /** 缺省标题归一：去首尾空白 + 折叠内部分隔空白；仍为空返回 [fallback]。 */
    fun titleOr(raw: String, fallback: String): String {
        val t = raw.trim().replace(Regex("\\s+"), " ")
        return t.ifBlank { fallback }
    }

    /** 章节号解析：`第12话/章/回/卷`、`Chapter/Ch/Episode/E + 数字`、尾部数字；失败回 null（调用方按序回退 ordinal）。 */
    fun chapterNumber(title: String): Int? {
        val t = title.trim()
        if (t.isEmpty()) return null
        val cjk = Regex("""第\s*([0-9０-９]+)\s*[话章回卷节话]""").find(t)
            ?: Regex("""第\s*([0-9０-９]+)""").find(t)
        cjk?.let { return it.groupValues[1].map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("").toIntOrNull() }
        val en = Regex("""(?i)\b(?:chapter|ch\.?|episode|ep\.?|e)\s*(\d{1,5})\b""").find(t)
            ?: Regex("""(\d{1,5})\s*$""").find(t)
        return en?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** 去重（保序，首现保留；id 为空的条目丢弃）。 */
    fun <T> dedupeById(items: List<T>, idOf: (T) -> String): List<T> {
        val seen = LinkedHashSet<String>()
        return items.filter { item ->
            val id = idOf(item).trim()
            if (id.isEmpty() || !seen.add(id)) false else true
        }
    }

    /** kind 归一：非法值回退到调用方默认（规则包单 kind 为主，多 kind 首项为准）。 */
    fun normalizeKind(raw: String?, fallback: ContentKind): ContentKind =
        raw?.let { ContentKind.of(it) } ?: fallback
}
