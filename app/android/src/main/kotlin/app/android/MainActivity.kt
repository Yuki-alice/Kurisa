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
        // 本地库（P2 阅读闭环：`local:` 前缀作品走本地源；filesDir/books 可直接放入 TXT/EPUB/CBZ/PDF/目录）。
        val booksDir = File(filesDir, "books").apply { mkdirs() }
        val localFs = LocalSourceFileSystem.fromDirectory(booksDir.absolutePath)
        val localChapters = LocalChapterProvider(localFs) { it.removePrefix(LOCAL_WORK_PREFIX) }
        val settings = createReaderSettingsStore(
            db = database,
            nowMs = { System.currentTimeMillis() },
        )
        // 真实源装配（P0）：与桌面同形，无源时退化为 Noop（C1）。
        // BE-B1 / BE-C2：密钥由 Android Keystore 持有，Cookie 只以密文落 reader.db。
        val cookies = CookieStore(
            persistence = DbCookiePersistence(database),
            cipher = KeystoreSecretCipher(),
        )
        val http = defaultHttpClient()
        val container = AppContainer(cookies, http)
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
        // 在线书架（A2）与下载编排（A1）：与桌面同形，只是根目录换成 filesDir。
        val shelf = createShelfBindings(database) { System.currentTimeMillis() }
        val downloadsDir = File(filesDir, "downloads").apply { mkdirs() }
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
                        File(downloadsDir, downloadTaskDirName(taskId))
                            .listFiles { f -> f.name.endsWith(".part") }
                            ?.mapNotNull { it.name.removeSuffix(".part").toIntOrNull() }
                            ?.sorted().orEmpty()
                    }
                },
                readBytes = { taskId ->
                    withContext(Dispatchers.IO) {
                        File(downloadsDir, downloadTaskDirName(taskId))
                            .listFiles { f -> f.name.endsWith(".part") }
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
        setContent {
            App(
                readerChapters = readerChapters,
                readerDomains = bindings.currentDomains(),
                readerProgress = progress,
                readerSettings = settings,
                bookmarkRepository = bookmarks,
                localFs = localFs,
                localBooks = localBooks,
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
                shelf = shelf,
                downloads = downloads,
                // 源仓库索引只读 app 私有目录，不联网。
                fetchRepoIndex = {
                    withContext(Dispatchers.IO) {
                        File(File(filesDir, "sources"), "index.json").takeIf { it.isFile }?.readText()
                    }
                },
                container = container,
                onPickLocalDirectory = null,
                saveSourceScript = { key, version, script, format ->
                    val ext = if (format == "rules/v1") "$version.rules.json" else "$version.js"
                    val f = File(File(File(filesDir, "sources"), key), ext)
                    f.parentFile?.mkdirs()
                    f.writeText(script)
                    f.absolutePath
                },
            )
        }
    }
}
