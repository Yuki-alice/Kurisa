package app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.shared.App
import app.shared.CallbackDownloadSink
import app.shared.createDownloadController
import app.shared.downloadTaskDirName
import app.shared.createShelfBindings
import app.shared.createSourceCatalog
import core.model.ChapterPayload
import data.db.ReaderDatabase
import data.download.KtorRangeFetcher
import data.network.CookieStore
import data.network.defaultHttpClient
import data.localimport.LOCAL_WORK_PREFIX
import data.localimport.LocalChapterProvider
import data.localimport.LocalRoutingChapterProvider
import data.localimport.LocalSourceFileSystem
import data.repository.createBookmarkRepository
import data.repository.createLocalBookRepository
import data.repository.createProgressRepository
import data.repository.createReaderSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File

/** 桌面入口（产品显示名只落配置层，此处标题即一例）。 */
fun main() {
    val database = openReaderDatabase()
    val progress = createProgressRepository(database)
    val bookmarks = createBookmarkRepository(database)
    val localBooks = createLocalBookRepository(database)
    val shelf = createShelfBindings(database) { System.currentTimeMillis() }
    val settings = createReaderSettingsStore(
        db = database,
        nowMs = { System.currentTimeMillis() },
    )
    // 真实源装配（P0）：registry + runtime 复用同一 CookieStore/HttpClient，
    // 无安装源时 RegistryChapterProvider 退化为 Noop 语义（C1）。
    val cookies = CookieStore()
    val http = defaultHttpClient()
    val appDir = File(System.getProperty("user.home"), ".kurisa").apply { mkdirs() }
    // 本地库（P2 阅读闭环：`local:` 前缀作品走本地源；目录不存在即空库，可直接放入 TXT/EPUB/CBZ/PDF/目录）。
    val booksDir = File(appDir, "books").apply { mkdirs() }
    val localFs = LocalSourceFileSystem.fromDirectory(booksDir.absolutePath)
    val localChapters = LocalChapterProvider(localFs) { it.removePrefix(LOCAL_WORK_PREFIX) }
    val sourcesDir = File(appDir, "sources").apply { mkdirs() }
    val bindings = createSourceCatalog(
        db = database,
        httpClient = http,
        cookies = cookies,
        appVersion = "1.0.0",
        loadScript = { path -> File(path).readText() },
        nowMs = { System.currentTimeMillis() },
    )
    val readerChapters = LocalRoutingChapterProvider(localChapters, bindings.chapters)
    // 下载编排（BE-A1）：分片落本地目录，抓取仍走 KtorRangeFetcher 的白名单 + 配对 UA。
    val downloadsDir = File(appDir, "downloads").apply { mkdirs() }
    val downloads = createDownloadController(
        db = database,
        fetcher = KtorRangeFetcher(
            client = http,
            cookies = cookies,
            nowMs = { System.currentTimeMillis() },
        ),
        sink = CallbackDownloadSink(
            listParts = { taskId ->
                withContext(Dispatchers.IO) {
                    File(downloadsDir, downloadTaskDirName(taskId)).listFiles { f -> f.name.endsWith(".part") }
                        ?.mapNotNull { it.name.removeSuffix(".part").toIntOrNull() }
                        ?.sorted().orEmpty()
                }
            },
            readBytes = { taskId ->
                withContext(Dispatchers.IO) {
                    File(downloadsDir, downloadTaskDirName(taskId)).listFiles { f -> f.name.endsWith(".part") }
                        ?.sumOf { it.length() } ?: 0L
                }
            },
            writePart = { taskId, index, bytes ->
                withContext(Dispatchers.IO) {
                    val dir = File(downloadsDir, downloadTaskDirName(taskId)).apply { mkdirs() }
                    File(dir, "$index.part").writeBytes(bytes)
                }
            },
            clear = { taskId ->
                withContext(Dispatchers.IO) {
                    File(downloadsDir, downloadTaskDirName(taskId)).deleteRecursively()
                }
            },
        ),
        resolveUrls = { task ->
            val payload = runCatching { readerChapters.payload(task.workId, task.chapterId) }
                .getOrNull() as? ChapterPayload.Images
            // 本地书 URL 形如 `path!/entry`，走不了 Range 抓取，只放行 http(s)。
            payload?.urls.orEmpty().filter {
                it.startsWith("https://") || it.startsWith("http://")
            }
        },
        domains = { bindings.currentDomains() },
    )
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kurisa",
            state = rememberWindowState(width = 1000.dp, height = 720.dp),
        ) {
            App(
                readerChapters = readerChapters,
                readerDomains = bindings.currentDomains(),
                readerProgress = progress,
                readerSettings = settings,
                bookmarkRepository = bookmarks,
                localFs = localFs,
                localBooks = localBooks,
                shelf = shelf,
                pickLocalFiles = {
                    withContext(Dispatchers.IO) {
                        val dialog = FileDialog(null as java.awt.Frame?, "导入本地书", FileDialog.LOAD)
                        dialog.isMultipleMode = true
                        dialog.filenameFilter = java.io.FilenameFilter { _, name ->
                            name.substringAfterLast('.', "").lowercase() in
                                setOf("txt", "epub", "cbz", "zip", "pdf")
                        }
                        dialog.isVisible = true
                        val chosen = dialog.files?.toList().orEmpty()
                        dialog.dispose()
                        chosen.mapNotNull { f ->
                            runCatching { f.name to f.readBytes() }.getOrNull()
                        }
                    }
                },
                sourceCatalog = bindings,
                saveSourceScript = { key, version, script, format ->
                    val ext = if (format == "rules/v1") "$version.rules.json" else "$version.js"
                    val f = File(File(sourcesDir, key), ext)
                    f.parentFile.mkdirs()
                    f.writeText(script)
                    f.absolutePath
                },
                downloads = downloads,
                // 源仓库索引只读本地文件，不发起任意 URL 请求（fail-closed）。
                fetchRepoIndex = {
                    withContext(Dispatchers.IO) {
                        val index = File(sourcesDir, "index.json")
                        if (!index.isFile) error("未找到本地源仓库索引：${index.absolutePath}")
                        index.readText()
                    }
                },
            )
        }
    }
}

/**
 * 桌面数据库：新库全量建表；老库按 user_version 前向迁移。
 * 极老库（1.sqm 合入基线前创建）缺 settings_json 列，migrate 不会补，
 * 此处幂等补列，保证三代库都能打开。
 */
private fun openReaderDatabase(): ReaderDatabase {
    val dir = File(System.getProperty("user.home"), ".kurisa").apply { mkdirs() }
    val file = File(dir, "reader.db")
    val existed = file.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    if (!existed) {
        ReaderDatabase.Schema.create(driver)
    } else {
        val old = readUserVersion(driver)
        val current = ReaderDatabase.Schema.version
        if (old < current) {
            ReaderDatabase.Schema.migrate(driver, old, current)
        }
        ensureSettingsJsonColumn(driver)
    }
    return ReaderDatabase(driver)
}

private fun readUserVersion(driver: JdbcSqliteDriver): Long {
    val result = driver.executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            QueryResult.Value(
                if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
            )
        },
        0,
    )
    return (result as QueryResult.Value<Long>).value
}

private fun ensureSettingsJsonColumn(driver: JdbcSqliteDriver) {
    runCatching {
        driver.execute(
            null,
            "ALTER TABLE source ADD COLUMN settings_json TEXT NOT NULL DEFAULT '{}'",
            0,
        )
    }
}
