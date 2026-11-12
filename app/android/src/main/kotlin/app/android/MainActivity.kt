package app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.shared.App
import app.shared.createSourceCatalog
import data.db.ReaderDatabase
import data.network.CookieStore
import data.network.defaultHttpClient
import data.repository.createProgressRepository
import data.repository.createReaderSettingsStore
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平台侧唯一允许创建 driver 的位置（ADR-006：不新增 expect/actual）。
        // AndroidSqliteDriver(ReaderDatabase.Schema, ...) 内部按 user_version
        // 自动 create/migrate，无需手写迁移（桌面 Jdbc 侧见 Main.kt）。
        val database = ReaderDatabase(
            AndroidSqliteDriver(ReaderDatabase.Schema, applicationContext, "reader.db"),
        )
        val progress = createProgressRepository(database)
        val settings = createReaderSettingsStore(
            db = database,
            nowMs = { System.currentTimeMillis() },
        )
        // 真实源装配（P0）：与桌面同形，无源时退化为 Noop（C1）。
        val cookies = CookieStore()
        val bindings = createSourceCatalog(
            db = database,
            httpClient = defaultHttpClient(),
            cookies = cookies,
            appVersion = "0.0.0",
            loadScript = { path ->
                val f = File(path)
                if (f.isAbsolute) f.readText()
                else File(File(filesDir, "sources"), path).readText()
            },
            nowMs = { System.currentTimeMillis() },
        )
        setContent {
            App(
                readerChapters = bindings.chapters,
                readerDomains = bindings.currentDomains(),
                readerProgress = progress,
                readerSettings = settings,
                sourceCatalog = bindings,
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
