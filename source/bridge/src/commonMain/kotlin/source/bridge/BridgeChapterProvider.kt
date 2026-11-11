package source.bridge

import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ChapterRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind

/**
 * 桥接章节源（源引擎 → 阅读器内核的归一化适配）。
 *
 * 映射（M1 冻结语义 + 真实形状兼容）：
 * - `loadInfo` 的 `chapters`（Map〈id→标题|{title}〉或数组）→ [ChapterRef]；
 * - 漫画 `loadEp` 的 `images` 数组 → [ChapterPayload.Images]；
 * - 小说 `loadChapter` 的 `content`（`text` 兜底）→ [ChapterPayload.Text]。
 *
 * 缺字段即抛 [SourceBridgeException]（源实现违约，不伪装成空章节——
 * 空目录与实现错误必须可区分，见 M1 评审）。
 */
class BridgeChapterProvider(
    private val calls: SourceCalls,
    private val kind: ContentKind,
) : ChapterProvider {
    private val json = Json

    override suspend fun chapters(workId: String): List<ChapterRef> {
        val raw = calls.loadWorkDetails(workId, kind)
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw SourceBridgeException("目录解析失败：${it.message}")
        }
        val chaptersEl = obj["chapters"]
            ?: throw SourceBridgeException("目录缺失 chapters")
        // 三形状兼容（M1 合成例 Map<object> / 真实源 Map<string> / 数组）：
        // object → {id: title|{title}}；array → [{id, title}]。
        return when (chaptersEl) {
            is JsonObject -> chaptersEl.entries.mapIndexed { i, (id, v) ->
                val title = when (v) {
                    is JsonObject -> v["title"]?.jsonPrimitive?.contentOrNull ?: id
                    else -> v.jsonPrimitive.contentOrNull ?: id
                }
                ChapterRef(id, title, i + 1)
            }
            is JsonArray -> chaptersEl.mapIndexed { i, el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull
                    ?: throw SourceBridgeException("目录条目缺 id")
                ChapterRef(id, o["title"]?.jsonPrimitive?.contentOrNull ?: id, i + 1)
            }
            else -> throw SourceBridgeException("目录 chapters 形状未知")
        }
    }

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload {
        val raw = calls.loadChapterPayload(workId, chapterId, kind)
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            throw SourceBridgeException("章节解析失败：${it.message}")
        }
        return if (kind == ContentKind.NOVEL) {
            // content（真实形状，M0#5）优先，text（M1 合成例）兜底；format 必透出（v4）。
            val body = obj["content"]?.jsonPrimitive?.contentOrNull
                ?: obj["text"]?.jsonPrimitive?.contentOrNull
                ?: throw SourceBridgeException("小说章节缺 content/text")
            val format = when (obj["format"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "markdown", "md" -> core.model.TextFormat.MARKDOWN
                "plain", "text", "txt" -> core.model.TextFormat.PLAIN
                else -> core.model.TextFormat.HTML
            }
            ChapterPayload.Text(body, format)
        } else if (kind == ContentKind.MANGA) {
            val urls = obj["images"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: throw SourceBridgeException("漫画章节缺 images")
            ChapterPayload.Images(urls)
        } else {
            // VIDEO/AUDIO：类型与能力扩展点已落地，渲染接线 deferred（ADR-011 D4），
            // 此处显式失败，不伪装成漫画 images，避免误导调用方。
            throw SourceBridgeException("视频/音频渲染 deferred（ADR-011 D4）：kind=${kind.serial}")
        }
    }
}
