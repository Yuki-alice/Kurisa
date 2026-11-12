package app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.shared.App
import data.db.ReaderDatabase
import data.repository.createProgressRepository
import data.repository.createReaderSettingsStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平台侧唯一允许创建 driver 的位置（ADR-006：不新增 expect/actual）。
        val database = ReaderDatabase(
            AndroidSqliteDriver(ReaderDatabase.Schema, applicationContext, "reader.db"),
        )
        val progress = createProgressRepository(database)
        val settings = createReaderSettingsStore(
            db = database,
            nowMs = { System.currentTimeMillis() },
        )
        setContent {
            App(
                readerProgress = progress,
                readerSettings = settings,
            )
        }
    }
}
