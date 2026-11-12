package data.localimport

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming

/** 单个 XML 元素：标签（localName，已去命名空间前缀）+ 属性 + 累积文本。 */
data class XmlLeaf(
    val tag: String,
    val attrs: Map<String, String>,
    val text: String,
)

/**
 * 容错 XML 叶子解析器（ComicInfo / OPF / container 共用）。
 * 只收集一层元素的三元组，不建模 schema，未知/嵌套元素自动忽略；
 * 空元素（如 `<rootfile full-path="…"/>`）同样记录（text 为空串）。
 */
object XmlLeaves {
    fun parse(xml: String): List<XmlLeaf> {
        val out = ArrayList<XmlLeaf>()
        val reader = xmlStreaming.newReader(xml)
        var tag: String? = null
        var attrs: Map<String, String> = emptyMap()
        val text = StringBuilder()
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    tag = reader.localName
                    attrs = buildMap {
                        for (i in 0 until reader.attributeCount) {
                            put(reader.getAttributeLocalName(i), reader.getAttributeValue(i))
                        }
                    }
                    text.clear()
                }
                EventType.TEXT, EventType.CDSECT -> {
                    if (!reader.isWhitespace()) text.append(reader.text)
                }
                EventType.END_ELEMENT -> {
                    tag?.let { out.add(XmlLeaf(it, attrs, text.toString())) }
                    tag = null
                    attrs = emptyMap()
                }
                else -> {}
            }
        }
        return out
    }

    /** 首个匹配元素的文本（空串视为缺失）；attrName/attrValue 可选做属性过滤。 */
    fun List<XmlLeaf>.firstText(
        tag: String,
        attrName: String? = null,
        attrValue: String? = null,
    ): String? = firstOrNull {
        it.tag.equals(tag, ignoreCase = true) &&
            (attrName == null || it.attrs[attrName] == attrValue)
    }?.text?.ifEmpty { null }

    /** 首个匹配元素的属性值。 */
    fun List<XmlLeaf>.attrValue(
        tag: String,
        attr: String,
        filterName: String? = null,
        filterValue: String? = null,
    ): String? = firstOrNull {
        it.tag.equals(tag, ignoreCase = true) &&
            (filterName == null || it.attrs[filterName] == filterValue)
    }?.attrs?.get(attr)
}
