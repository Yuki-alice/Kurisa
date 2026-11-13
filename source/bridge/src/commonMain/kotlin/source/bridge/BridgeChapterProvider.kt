package source.bridge

import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderPageRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind
import kotlin.time.Clock

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
/**
 * 章节列表缓存出口（BE-B2 端口；适配器在 :data:repository，由装配层注入）。
 *
 * 实现（data.repository.ChapterCacheRepository）把目录写透到 chapter 表：
 * - saveChapters 全量替换（远端不再列出的章节视为下架，一并清理）；
 * - loadChapters 无缓存返回 null（与空目录可区分，不伪装数据）。
 */
interface ChapterCachePort {
    suspend fun saveChapters(
        workId: String,
        kind: ContentKind,
        chapters: List<ChapterRef>,
        updatedAtEpochMs: Long,
    )

    suspend fun loadChapters(workId: String): List<ChapterRef>?
}

class BridgeChapterProvider(
    private val calls: SourceCalls,
    private val kind: ContentKind,
    /**
     * 章节缓存（null = 未装配缓存，纯在线；由装配层注入仓储适配器）。
     * 装配契约见协作 3：构造新增参数一律带默认值，旧调用点零改动。
     */
    private val chapterCache: ChapterCachePort? = null,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * 缓存写入失败的透出钩子（默认丢弃，写透失败不破坏一次成功的在线读取；
     * 生产装配可接 AppLog，单测可断言触发）。
     */
    private val onChapterCacheError: (Throwable) -> Unit = {},
) : ChapterProvider {
    private val json = Json

    override suspend fun chapters(workId: String): List<ChapterRef> {
        // 在线优先：成功即写透缓存；远端失败才回退缓存，保证离线可读不断章。
        // 取消与宿主缺 native 原样抛出（结构化并发与测试跳过语义，不误判为源错误）。
        val remote = try {
            fetchRemoteChapters(workId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostMissingException) {
            throw e
        } catch (e: Exception) {
            val cached = runCatching { chapterCache?.loadChapters(workId) }.getOrNull()
            if (cached != null) return cached
            throw e
        }
        runCatching { chapterCache?.saveChapters(workId, kind, remote, nowMs()) }
            .onFailure { onChapterCacheError(it) }
        return remote
    }

    private suspend fun fetchRemoteChapters(workId: String): List<ChapterRef> {
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

    /** 小说文内图片接线：`novel.onImageResolve` 重写 URL / 附加已脱敏头 / 重试次数。
     * 未声明时直通原文；返回的 headers 已由 `ImageLoadConfig.sanitized()` 剥离
     * UA/Cookie（宿主出口统一注入），调用方不得自行补传。 */
    override suspend fun resolveNovelImage(url: String): ReaderPageRequest {
        val cfg = calls.novelImageResolve(url) ?: return ReaderPageRequest(url)
        return ReaderPageRequest(
            url = url,
            rewriteUrl = cfg.url,
            headers = cfg.headers,
            maxRetries = cfg.maxRetries,
        )
    }

    /** `comic.onImageLoad` 接线：重写 URL / 附加已脱敏头 / 重试次数。 */
    override suspend fun resolvePage(
        workId: String,
        chapterId: String,
        url: String,
    ): ReaderPageRequest {
        val cfg = calls.imageLoadConfig(url, workId, chapterId) ?: return ReaderPageRequest(url)
        return ReaderPageRequest(
            url = url,
            rewriteUrl = cfg.url,
            headers = cfg.headers,
            maxRetries = cfg.maxRetries,
        )
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
        } else if (kind == ContentKind.VIDEO) {
            // BE-C4：类型化视频 payload；不伪装成漫画 images，避免误导调用方。
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: throw SourceBridgeException("视频章节缺 url")
            ChapterPayload.Video(
                url = url,
                mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull,
                headers = stringMap(obj["headers"]),
                subtitles = obj["subtitles"]?.jsonArray
                    ?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    .orEmpty(),
            )
        } else {
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
                ?: throw SourceBridgeException("音频章节缺 url")
            ChapterPayload.Audio(
                url = url,
                mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull,
                headers = stringMap(obj["headers"]),
                title = obj["title"]?.jsonPrimitive?.contentOrNull,
                durationMs = runCatching {
                    obj["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                }.getOrNull(),
            )
        }
    }

    /** 不可信脚本输出的对象字段容错取字符串，非法形状一律忽略。 */
    private fun stringMap(element: kotlinx.serialization.json.JsonElement?): Map<String, String> =
        (element as? JsonObject)?.entries?.mapNotNull { (k, v) ->
            runCatching { v.jsonPrimitive.contentOrNull }.getOrNull()?.let { k to it }
        }?.toMap().orEmpty()
}
