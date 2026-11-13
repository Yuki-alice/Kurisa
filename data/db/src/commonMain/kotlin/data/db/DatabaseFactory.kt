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

    /**
     * v4 → v3：去掉 `reading_progress.char_offset` / `layout_key`，其余列全保留。
     * 执行前调用方须备份 DB 文件；逐条按 ';' 分割执行（与 V2_TO_V1 同约定）。
     */
    val V4_TO_V3 = """
        CREATE TABLE reading_progress_v3 (
          work_id TEXT PRIMARY KEY,
          chapter_id TEXT NOT NULL,
          page_index INTEGER,
          scroll_offset REAL,
          percent REAL NOT NULL,
          updated_at INTEGER NOT NULL,
          synced INTEGER NOT NULL DEFAULT 0
        );
        INSERT INTO reading_progress_v3
          (work_id, chapter_id, page_index, scroll_offset, percent, updated_at, synced)
        SELECT work_id, chapter_id, page_index, scroll_offset, percent, updated_at, synced
          FROM reading_progress;
        DROP TABLE reading_progress;
        ALTER TABLE reading_progress_v3 RENAME TO reading_progress;
    """.trimIndent()

    /** v3 基线（无锚点列），供迁移测试建旧库。 */
    val V3_PROGRESS = """
        CREATE TABLE reading_progress (
          work_id TEXT PRIMARY KEY,
          chapter_id TEXT NOT NULL,
          page_index INTEGER,
          scroll_offset REAL,
          percent REAL NOT NULL,
          updated_at INTEGER NOT NULL,
          synced INTEGER NOT NULL DEFAULT 0
        );
    """.trimIndent()

    /** v5 → v4：去掉 `highlight` 表（M4 划线；纯新增表，回滚即删表）。 */
    val V5_TO_V4 = "DROP TABLE highlight;"

    /** v5 基线的 cookie 表（明文 value + domain/name 主键），供 5.sqm 迁移测试建旧库。 */
    val V5_COOKIE = """
        CREATE TABLE cookie (
          domain TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
          expires_at INTEGER, PRIMARY KEY(domain, name)
        );
    """.trimIndent()

    /**
     * v6 → v5：重建旧 cookie 表。加密记录不回滚成明文（密钥轮换后也无从解密），
     * 直接丢弃；其余 12 张表一列不动。执行前调用方须备份 DB 文件。
     */
    val V6_TO_V5 = """
        CREATE TABLE cookie_v5 (
          domain TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
          expires_at INTEGER, PRIMARY KEY(domain, name)
        );
        DROP TABLE cookie;
        ALTER TABLE cookie_v5 RENAME TO cookie;
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
