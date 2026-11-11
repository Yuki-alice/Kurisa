package data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import source.contract.ContentKind

/** 跨层映射（DTO/Entity → domain，永不出 `:data`）。 */
internal object Mappers {
    private val json = Json { ignoreUnknownKeys = true }

    fun normalizeKind(raw: String): ContentKind = ContentKind.of(raw) ?: ContentKind.MANGA

    fun encodeTags(tags: List<String>): String = json.encodeToString(tags)

    fun decodeTags(raw: String): List<String> = runCatching {
        json.decodeFromString<List<String>>(raw)
    }.getOrElse { emptyList() }
}
