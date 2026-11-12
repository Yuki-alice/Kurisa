package data.localimport

import com.fleeksoft.ksoup.Ksoup
import data.localimport.XmlLeaves.attrValue
import data.localimport.XmlLeaves.firstText

/**
 * EPUB 解析。对齐 Komikku `core/archive EpubReader` + `EpubReaderExtensions.fillMetadata`：
 * container → OPF（manifest + spine）→ 页面 xhtml → 图片。
 * 解包走 [ArchiveReader]（zip 系），XML 走 [XmlLeaves]，页面走 Ksoup。
 */
class EpubReader(private val archive: ArchiveReader) {

    suspend fun packagePath(): String {
        val entries = archive.fileEntries()
        val container = entries.firstOrNull { it.endsWith("container.xml", ignoreCase = true) }
        if (container != null) {
            val xml = archive.readEntry(container).decodeToString()
            XmlLeaves.parse(xml).attrValue("rootfile", "full-path")?.let { return it }
        }
        // 回退（对齐 Komikku）：OEBPS/content.opf
        return "OEBPS/content.opf"
    }

    /** spine 顺序的 xhtml 页面路径（已按 manifest id 解析并归一化）。 */
    suspend fun pagePaths(): List<String> {
        val opfPath = packagePath()
        val opfDir = opfPath.substringBeforeLast('/', "")
        fun resolve(href: String) = if (opfDir.isEmpty()) href else resolveZipPath("$opfDir/", href)

        val opf = archive.readEntry(opfPath).decodeToString()
        // manifest: id -> href（仅 xhtml 系）
        val idToHref = LinkedHashMap<String, String>()
        Regex("""<item\b[^>]*>""").findAll(opf).forEach { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            val media = attr(tag, "media-type") ?: ""
            if (media.contains("xhtml") || media.contains("html")) {
                idToHref[id] = resolve(href)
            }
        }
        // spine: idref 顺序
        val ordered = ArrayList<String>()
        Regex("""<itemref\b[^>]*>""").findAll(opf).forEach { m ->
            val idref = attr(m.value, "idref") ?: return@forEach
            idToHref[idref]?.let { ordered.add(it) }
        }
        return ordered.ifEmpty { idToHref.values.toList() }
    }

    /** 单页内图片（img@src + image@xlink:href，相对页面归一化）。 */
    suspend fun pageImages(pagePath: String): List<String> {
        val html = archive.readEntry(pagePath).decodeToString()
        val doc = Ksoup.parse(html)
        val out = LinkedHashSet<String>()
        doc.select("img").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) out.add(resolveZipPath(pagePath, src))
        }
        doc.select("image").forEach { el ->
            val href = el.attr("xlink:href").ifBlank { el.attr("href") }
            if (href.isNotBlank()) out.add(resolveZipPath(pagePath, href))
        }
        return out.toList()
    }

    /** OPF 元数据（对齐 `fillMetadata`：creator/description/title/publisher/date）。 */
    suspend fun metadata(): EpubMetadata {
        val leaves = XmlLeaves.parse(archive.readEntry(packagePath()).decodeToString())
        val dateRaw = leaves.firstText("date")
            ?: leaves.firstText("modified")
            ?: leaves.firstText("meta", "property", "dcterms:modified")
        return EpubMetadata(
            title = leaves.firstText("title"),
            creator = leaves.firstText("creator"),
            description = leaves.firstText("description"),
            publisher = leaves.firstText("publisher"),
            dateRaw = dateRaw,
            dateMillis = dateRaw?.let(::parseEpubDate),
        )
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*"([^"]*)"""").find(tag)?.groupValues?.get(1)

    companion object {
        /** zip 路径归一：相对页面解析 href，处理 .././、去 fragment/query、前导 /。 */
        fun resolveZipPath(basePath: String, href: String): String {
            val clean = href.substringBefore('#').substringBefore('?')
            if (clean.startsWith('/')) return clean.trimStart('/')
            val base = basePath.substringBeforeLast('/', "")
            val parts = ArrayDeque<String>()
            val joined = if (base.isEmpty()) clean else "$base/$clean"
            joined.split('/').forEach {
                when (it) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.add(it)
                }
            }
            return parts.joinToString("/")
        }

        /**
         * EPUB 日期：`yyyy-MM-dd'T'HH:mm:ss[.SSS](Z|±HH:MM)`，对齐 Komikku
         * `yyyy-MM-dd'T'HH:mm:ssZ` 解析（纯 Kotlin，不依赖时区库）。
         */
        fun parseEpubDate(raw: String): Long? {
            return try {
                val m = Regex(
                    """(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?""",
                ).find(raw.trim()) ?: return null
                val (y, mo, d, h, mi, s) = m.destructured
                var offsetMinutes = 0
                val zone = m.groupValues.getOrNull(7).orEmpty()
                if (zone.isNotEmpty() && zone != "Z") {
                    val sign = if (zone.startsWith("-")) -1 else 1
                    val digits = zone.drop(1).replace(":", "")
                    offsetMinutes = sign * (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt())
                }
                val days = daysFromCivil(y.toInt(), mo.toInt(), d.toInt())
                days * 86_400_000L + (h.toInt() * 3600L + mi.toInt() * 60L + s.toInt()) * 1000L -
                    offsetMinutes * 60_000L
            } catch (_: Exception) {
                null
            }
        }

        /** Howard Hinnant days-from-civil（1970-01-01 = day 0）。 */
        private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
            var yy = y
            val mm: Int
            if (m <= 2) {
                yy -= 1
                mm = m + 12
            } else {
                mm = m
            }
            val era = (if (yy >= 0) yy else yy - 399) / 400
            val yoe = yy - era * 400
            val doy = (153 * (mm - 3) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468L
        }
    }
}

data class EpubMetadata(
    val title: String? = null,
    val creator: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val dateRaw: String? = null,
    val dateMillis: Long? = null,
)
