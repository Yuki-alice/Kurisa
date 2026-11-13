package app.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
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
import java.io.File

class MainActivity : ComponentActivity() {
    /** SAF 导入桥：suspend picker 等待用户选择后读字节回传。 */
    private var importContinuation: Continuation<List<Uri>>? = null
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        importContinuation?.let {
            importContinuation = null
            it.resume(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平台侧唯一允许创建 driver 的位置（ADR-006：不新增 expect/actual）。
        // AndroidSqliteDriver(ReaderDatabase.Schema, ...) 内部按 user_version
        // 自动 create/migrate，无需手写迁移（桌面 Jdbc 侧见 Main.kt）。
        val database = ReaderDatabase(
            AndroidSqliteDriver(ReaderDatabase.Schema, applicationContext, "reader.db"),
        )
        val progress = createProgressRepository(database)
        val bookmarks = createBookmarkRepository(database)
        val localBooks = createLocalBookRepository(database)
        val shelf = createShelfBindings(database) { System.currentTimeMillis() }
        // 本地库（P2 阅读闭环：`local:` 前缀作品走本地源；filesDir/books 可直接放入 TXT/EPUB/CBZ/PDF/目录）。
        val booksDir = File(filesDir, "books").apply { mkdirs() }
        val localFs = LocalSourceFileSystem.fromDirectory(booksDir.absolutePath)
        val localChapters = LocalChapterProvider(localFs) { it.removePrefix(LOCAL_WORK_PREFIX) }
        val settings = createReaderSettingsStore(
            db = database,
            nowMs = { System.currentTimeMillis() },
        )
        // 真实源装配（P0）：与桌面同形，无源时退化为 Noop（C1）。
        val cookies = CookieStore()
        val http = defaultHttpClient()
        val bindings = createSourceCatalog(
            db = database,
            httpClient = http,
            cookies = cookies,
            appVersion = "1.0.0",
            loadScript = { path ->
                val f = File(path)
                if (f.isAbsolute) f.readText()
                else File(File(filesDir, "sources"), path).readText()
            },
            nowMs = { System.currentTimeMillis() },
        )
        val readerChapters = LocalRoutingChapterProvider(localChapters, bindings.chapters)
        // 下载编排（BE-A1）：分片落 filesDir/downloads，抓取走白名单 + 配对 UA。
        val downloadsDir = File(filesDir, "downloads").apply { mkdirs() }
        val sourcesDir = File(filesDir, "sources")
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
                payload?.urls.orEmpty().filter {
                    it.startsWith("https://") || it.startsWith("http://")
                }
            },
            domains = { bindings.currentDomains() },
        )
        setContent {
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
                    val uris = suspendCancellableCoroutine<List<Uri>> { cont ->
                        importContinuation?.resume(emptyList())
                        importContinuation = cont
                        importLauncher.launch(arrayOf("*/*"))
                    }
                    withContext(Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            runCatching {
                                val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "book"
                                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: return@runCatching null
                                name to bytes
                            }.getOrNull()
                        }
                    }
                },
                sourceCatalog = bindings,
                saveSourceScript = { key, version, script, format ->
                    val ext = if (format == "rules/v1") "$version.rules.json" else "$version.js"
                    val f = File(File(File(filesDir, "sources"), key), ext)
                    f.parentFile?.mkdirs()
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
