package spike

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * M0 P0 符号冒烟：引用各库的稳定公开符号，让编译器真正链接 klib / jar，
 * 而不只是解析 Gradle metadata。其余 P0 库先验证三 target 依赖可解析性。
 *
 * 注意：kotlinx-datetime 0.8.0 的 Clock / Instant 已迁移到 Kotlin 2.4 的
 * kotlin.time，故此处用标准库 Clock；该库本身仍保留在三 target 依赖矩阵中。
 */
@Suppress("unused")
internal object DependencySmoke {
    fun jsonCodec(): Json = Json { ignoreUnknownKeys = true }
    fun timestamp(): Long = Clock.System.now().toEpochMilliseconds()
    fun koinModule(): Module = module { }
    fun clientName(client: HttpClient): String = client.toString()
    fun driverName(driver: SqlDriver): String = driver.toString()
}
