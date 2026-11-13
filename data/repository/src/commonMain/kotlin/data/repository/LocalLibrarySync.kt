package data.repository

import core.model.LocalBook
import data.localimport.LocalOrderBy
import data.localimport.LocalSourceScanner

/** 本地库扫描结果（入库新增/更新数 + 失效清理数）。 */
data class LocalLibrarySyncResult(val upserted: Int, val pruned: Int)

/**
 * 本地库扫描入库（P2 落库半边；展示侧由书架/详情后续接）。
 *
 * 约定（与 [data.localimport.LocalRoutingChapterProvider] 同构）：
 * - 仅扫描库根一级目录（Komikku `base/manga/chapters` 形态），行 `id = "local:<目录名>"`；
 * - 标题优先 ComicInfo 解析结果，回退目录名；封面取扫描器 coverPath；
 * - 首扫时间保留（已存在行不刷新 `imported_at`）；
 * - 仅清理 `local:` 前缀且已消失的行（其他来源行不动）。
 */
class LocalLibrarySync(
    private val scanner: LocalSourceScanner,
    private val books: LocalBookRepository,
    private val nowMs: () -> Long,
) {
    suspend fun sync(): LocalLibrarySyncResult {
        val dirs = scanner.scanBase(LocalOrderBy.TITLE)
        val seen = mutableSetOf<String>()
        var upserted = 0
        for (entry in dirs) {
            val id = "local:${entry.name}"
            seen += id
            val existing = books.find(id)
            val scanned = runCatching { scanner.scanManga(entry.name, entry.name) }.getOrNull()
            val title = scanned?.details?.title?.takeIf { it.isNotBlank() } ?: entry.name
            books.upsert(
                LocalBook(
                    id = id,
                    path = entry.name,
                    format = "dir",
                    title = title,
                    cover = scanned?.coverPath,
                    importedAtEpochMs = existing?.importedAtEpochMs ?: nowMs(),
                ),
            )
            upserted++
        }
        var pruned = 0
        for (row in books.list()) {
            if (row.id.startsWith("local:") && row.id !in seen) {
                books.remove(row.id)
                pruned++
            }
        }
        return LocalLibrarySyncResult(upserted, pruned)
    }
}

fun createLocalLibrarySync(
    scanner: LocalSourceScanner,
    books: LocalBookRepository,
    nowMs: () -> Long,
): LocalLibrarySync = LocalLibrarySync(scanner, books, nowMs)
