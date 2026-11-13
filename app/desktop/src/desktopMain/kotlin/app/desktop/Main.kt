package app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.shared.App
import app.shared.AppContainer
import app.shared.CallbackDownloadSink
import app.shared.DbCookiePersistence
import app.shared.createDownloadController
import app.shared.createShelfBindings
import app.shared.createSourceCatalog
import app.shared.downloadTaskDirName
import core.model.ChapterPayload
import data.download.KtorRangeFetcher
import data.db.ReaderDatabase
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
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import app.shared.CookieImportHostUi
import app.shared.CookiePaste

/**
 * 桌面 Cookie 导入兜底（BE-C3 / ADR-009）：无内嵌浏览器时，让用户从 DevTools 粘贴
 * Cookie 整串，并收集与之配对的浏览器 UA（缺 UA 的 cf_clearance 会被拒绝）。
 * 与 pickLocalDirectory 同形：Swing 必须走 EDT，整体放 IO 线程；headless 返回 null 不崩。
 */
private suspend fun requestCookiePaste(domain: String): CookiePaste? = withContext(Dispatchers.IO) {
    runCatching {
        var raw: String? = null
        var agent: String? = null
        SwingUtilities.invokeAndWait {
            raw = JOptionPane.showInputDialog(
                null,
                "本平台无内置浏览器。请从浏览器 DevTools 复制 $domain 的 Cookie 整串后粘贴：",
                "导入 Cookie",
                JOptionPane.PLAIN_MESSAGE,
            )
            if (!raw.isNullOrBlank()) {
                agent = JOptionPane.showInputDialog(
                    null,
                    "粘贴与该 Cookie 配对的浏览器 User-Agent（cf_clearance 必填）：",
                    "配对 User-Agent",
                    JOptionPane.PLAIN_MESSAGE,
                )
            }
        }
        val text = raw?.takeIf { it.isNotBlank() } ?: return@runCatching null
        CookiePaste(text, agent?.trim()?.takeIf { it.isNotEmpty() })
    }.getOrNull()
}

/** 桌面入口（产品显示名只落配置层，此处标题即一例）。 */
fun main() {
    val database = openReaderDatabase()
    val progress = createProgressRepository(database)
    val bookmarks = createBookmarkRepository(database)
    val localBooks = createLocalBookRepository(database)
    val settings = createReaderSettingsStore(
        db = database,
        nowMs = { System.currentTimeMillis() },
    )
    // 真实源装配（P0）：registry + runtime 复用同一 CookieStore/HttpClient，
    // 无安装源时 RegistryChapterProvider 退化为 Noop 语义（C1）。
    // BE-B1 / BE-C2：Cookie 只以 AES-256-GCM 密文落 reader.db，密钥留在用户私有目录；
    // 重启后恢复 cf_clearance 与配对 UA，不再每次重开都重新过盾。
    val appDir = File(System.getProperty("user.home"), ".kurisa").apply { mkdirs() }
    val cookies = CookieStore(
        persistence = DbCookiePersistence(database),
        cipher = FileSecretCipher(File(appDir, "cookie.key")),
    )
    val http = defaultHttpClient()
    // 本地库（P2 阅读闭环：`local:` 前缀作品走本地源；目录不存在即空库，可直接放入 TXT/EPUB/CBZ/PDF/目录）。
    val booksDir = File(appDir, "books").apply { mkdirs() }
    val localFs = LocalSourceFileSystem.fromDirectory(booksDir.absolutePath)
    val localChapters = LocalChapterProvider(localFs) { it.removePrefix(LOCAL_WORK_PREFIX) }
    val sourcesDir = File(appDir, "sources").apply { mkdirs() }
    // S2：共享容器（CookieStore/HttpClient/ImageFetcher 一份，阅读器与源侧配对一致）。
    val container = AppContainer(cookies, http)
    val bindings = createSourceCatalog(
        db = database,
        httpClient = http,
        cookies = cookies,
        appVersion = "1.0.0",
        loadScript = { path -> File(path).readText() },
        nowMs = { System.currentTimeMillis() },
        // BE-C3：桌面按 ADR-009 走 Cookie 导入兜底；白名单取每个源自己声明的 domains。
        hostUiFor = { domains ->
            CookieImportHostUi(
                cookies = cookies,
                allowedDomains = domains,
                requestPaste = ::requestCookiePaste,
                nowMs = { System.currentTimeMillis() },
                openExternal = { url ->
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                },
            )
        },
    )
    val readerChapters = LocalRoutingChapterProvider(localChapters, bindings.chapters)
    // 在线书架（A2）：数据层已就绪，装配层只做映射。
    val shelf = createShelfBindings(database) { System.currentTimeMillis() }
    // 下载编排（A1）：分片落本地目录，抓取仍走 KtorRangeFetcher 的白名单 + 配对 UA。
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
                // B1：桌面用系统目录选择器（Swing 在 IO 线程弹，避免阻塞 Compose 的 AWT 主线程）。
                onPickLocalDirectory = { pickLocalDirectory() },
                // 启动时直接打开指定库：`-Dkurisa.library=<dir>` 或 `KURISA_LIBRARY=<dir>`。
                // 用途一是「设了就不用每次点选目录」，二是自动化验证能起真窗口看真实库。
                localLibraryRoot = System.getenv("KURISA_LIBRARY")
                    ?: System.getProperty("kurisa.library"),
                shelf = shelf,
                downloads = downloads,
                // 源仓库索引只读本地文件：本轮不引入「用户任意 URL」的网络链路。
                fetchRepoIndex = {
                    withContext(Dispatchers.IO) {
                        File(sourcesDir, "index.json").takeIf { it.isFile }?.readText()
                    }
                },
                container = container,
            )
        }
    }
}

/**
 * 桌面目录选择器（B1）。
 *
 * `JFileChooser` 必须在 EDT 上调用，而 Compose Desktop 的 AWT 主线程就是 EDT；
 * 直接在组合线程里 `invokeAndWait` 会自锁，因此整体放到 IO 线程再投递到 EDT。
 * 无图形环境（headless）时返回 null，由书架显示「未选择本地库」，不崩。
 */
private suspend fun pickLocalDirectory(): String? = withContext(Dispatchers.IO) {
    runCatching {
        var picked: String? = null
        SwingUtilities.invokeAndWait {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "选择漫画目录"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                picked = chooser.selectedFile?.absolutePath
            }
        }
        picked
    }.getOrNull()
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
