package data.repository

import core.model.LocalBook
import data.db.ReaderDatabase

/**
 * 本地书仓库（M4 导入落库：`local_book` 表，在线书同构展示的数据底座）。
 *
 * `id` 由调用方按内容寻址（推荐 `"local:" + sha256(path)"`，换路径即新书）；
 * `format` 取 `FormatDetector` 扩展名小写（txt/epub/cbz/cbr/pdf/dir）。
 * 与书架 `work` 表互不耦合：同构展示由调用方按 `local:` 前缀路由（见 `LocalRoutingChapterProvider`）。
 */
interface LocalBookRepository {
    suspend fun list(): List<LocalBook>
    suspend fun find(id: String): LocalBook?
    suspend fun upsert(book: LocalBook)
    suspend fun remove(id: String)
}

internal class LocalBookRepositoryImpl(private val db: ReaderDatabase) : LocalBookRepository {
    override suspend fun list(): List<LocalBook> =
        db.localBookQueries.selectLocalBooks().executeAsList().map { it.toDomain() }

    override suspend fun find(id: String): LocalBook? =
        db.localBookQueries.selectLocalBooks().executeAsList()
            .firstOrNull { it.id == id }?.toDomain()

    override suspend fun upsert(book: LocalBook) {
        db.localBookQueries.upsertLocalBook(
            id = book.id,
            path = book.path,
            format = book.format,
            title = book.title,
            cover = book.cover,
            imported_at = book.importedAtEpochMs,
        )
    }

    override suspend fun remove(id: String) {
        db.localBookQueries.deleteLocalBook(id)
    }

    private fun data.db.Local_book.toDomain(): LocalBook = LocalBook(
        id = id,
        path = path,
        format = format,
        title = title,
        cover = cover,
        importedAtEpochMs = imported_at,
    )
}

fun createLocalBookRepository(db: ReaderDatabase): LocalBookRepository =
    LocalBookRepositoryImpl(db)

/** 无持久化实现（预览/单测/App 默认，保证既有调用方零改动）。 */
object NoopLocalBookRepository : LocalBookRepository {
    override suspend fun list(): List<LocalBook> = emptyList()
    override suspend fun find(id: String): LocalBook? = null
    override suspend fun upsert(book: LocalBook): Unit = Unit
    override suspend fun remove(id: String): Unit = Unit
}
