package source.contract

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 契约 JSON 编解码（宿主 ↔ 仓库索引 ↔ 源返回值的规范化形状）。 */
object ContractJson {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeMeta(meta: SourceMeta): String = json.encodeToString(meta)

    fun decodeMeta(raw: String): SourceMeta = json.decodeFromString(raw)

    fun decodeIndex(raw: String): SourceIndex = json.decodeFromString(raw)

    fun encodeIndex(index: SourceIndex): String = json.encodeToString(index)
}
