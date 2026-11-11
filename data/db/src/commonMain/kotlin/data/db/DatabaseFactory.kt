package data.db

import app.cash.sqldelight.db.SqlDriver

/**
 * 数据库工厂（无 expect/actual，ADR-006）。
 * 调用方按平台构造 driver 后传入：
 * - Android: `AndroidSqliteDriver(ReaderDatabase.Schema, context, "reader.db")`
 * - iOS: `NativeSqliteDriver(ReaderDatabase.Schema, "reader.db")`
 * - Desktop: `JdbcSqliteDriver("jdbc:sqlite:reader.db")` / in-memory 测试
 * - commonTest 不直连 driver（纯逻辑），集成测试进 desktopTest / androidHostTest。
 */
fun createDatabase(driver: SqlDriver): ReaderDatabase = ReaderDatabase(driver)

/**
 * 回滚脚本（AGENTS 硬规则：migration 必须可回滚）。
 * SQLDelight 只执行前向 `.sqm`；此处提供 v2→v1 的有损回滚（仅丢 `settings_json`，
 * 核心列全部保留），供降级与测试使用。执行前调用方须备份 DB 文件。
 */
object RollbackScripts {
    /** v2 → v1：去掉 `source.settings_json`，保留其余 12 列。 */
    val V2_TO_V1 = """
        CREATE TABLE source_v1 (
          `key` TEXT PRIMARY KEY, name TEXT NOT NULL, version TEXT NOT NULL,
          api_version INTEGER NOT NULL, kinds TEXT NOT NULL, capabilities TEXT NOT NULL,
          domains TEXT NOT NULL, install_path TEXT NOT NULL, sha256 TEXT NOT NULL,
          signature_ok INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
          updated_at INTEGER NOT NULL
        );
        INSERT INTO source_v1
          (`key`, name, version, api_version, kinds, capabilities, domains,
           install_path, sha256, signature_ok, enabled, updated_at)
        SELECT `key`, name, version, api_version, kinds, capabilities, domains,
           install_path, sha256, signature_ok, enabled, updated_at FROM source;
        DROP TABLE source;
        ALTER TABLE source_v1 RENAME TO source;
    """.trimIndent()

    /** v1 基线（无 settings_json），供迁移测试建 v1 库。 */
    val V1_SOURCE = """
        CREATE TABLE source (
          `key` TEXT PRIMARY KEY, name TEXT NOT NULL, version TEXT NOT NULL,
          api_version INTEGER NOT NULL, kinds TEXT NOT NULL, capabilities TEXT NOT NULL,
          domains TEXT NOT NULL, install_path TEXT NOT NULL, sha256 TEXT NOT NULL,
          signature_ok INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
          updated_at INTEGER NOT NULL
        );
    """.trimIndent()
}
