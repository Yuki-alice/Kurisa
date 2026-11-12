package app.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.shared.App
import data.db.ReaderDatabase
import data.repository.createProgressRepository
import data.repository.createReaderSettingsStore
import java.io.File

/** 桌面入口（产品显示名只落配置层，此处标题即一例）。 */
fun main() {
    val database = openReaderDatabase()
    val progress = createProgressRepository(database)
    val settings = createReaderSettingsStore(
        db = database,
        nowMs = { System.currentTimeMillis() },
    )
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kurisa",
            state = rememberWindowState(width = 1000.dp, height = 720.dp),
        ) {
            App(
                readerProgress = progress,
                readerSettings = settings,
            )
        }
    }
}

/**
 * 桌面数据库：首次启动建全量 schema；已有开发库只补新增表。
 * 正式迁移（旧版本升级）由 SQLDelight Schema.migrate 在后续版本接入。
 */
private fun openReaderDatabase(): ReaderDatabase {
    val dir = File(System.getProperty("user.home"), ".kurisa").apply { mkdirs() }
    val file = File(dir, "reader.db")
    val existed = file.exists()
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    if (existed) {
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS reader_setting (
              scope TEXT NOT NULL PRIMARY KEY,
              config_json TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
    } else {
        ReaderDatabase.Schema.create(driver)
    }
    return ReaderDatabase(driver)
}
