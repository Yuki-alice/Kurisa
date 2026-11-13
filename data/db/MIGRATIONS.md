# 数据层迁移与 schema 快照

> 实施依据：`docs/work-plan.md`（唯一活计划位）、`docs/architecture.md`（ADR / 模块边界）、
> `docs/plugin-system/DESIGN.md`。本文只描述 `:data:db` 的迁移校验机制，不改变上述文档的口径。

## 1. 当前状态

`data/db/build.gradle.kts` 中 `verifyMigrations.set(true)` 已打开。
`src/commonMain/sqldelight/databases/` 下的 `.db` 文件是 SQLDelight 迁移校验的**起点快照**，
文件名即起始版本号；Gradle 任务 `verifyCommonMainReaderDatabaseMigration` 会把每个快照
从该版本迁移到当前版本，并与 `.sq` 定义的当前 schema 逐项比对。

| 文件 | 起始版本 | 校验覆盖的迁移 |
| --- | --- | --- |
| `2.db` | v2 | `2.sqm` → `3.sqm` → `4.sqm` → `5.sqm` |
| `3.db` | v3 | `3.sqm` → `4.sqm` → `5.sqm` |
| `4.db` | v4 | `4.sqm` → `5.sqm` |
| `5.db` | v5 | `5.sqm`（BE-B1 的 cookie 表重建） |
| `6.db` | v6 | 当前 schema 基线（由 Gradle 生成） |

**没有 `1.db`**：`1.sqm` 是 `ALTER TABLE source ADD COLUMN settings_json`，追加列的
`ordinalPosition` 与 `.sq` 定义（该列在 `updated_at` 之前）不一致，且 SQLDelight 会逐字比较
`CREATE TABLE` 定义文本（`ALTER ... RENAME TO` 还会给表名加引号），因此 v1 起点无法通过自动校验。
强行放入 `1.db` 会让 `verify...Migration` 失败。v1→v2 与回滚仍由
`MigrationAndOfflineTest.migrateV1ToV2PreservesData` / `rollbackV2ToV1PreservesCore` 手工验证，
这两条用例在 `desktopTest` 中无条件执行。

回滚脚本集中在 `DatabaseFactory.kt` 的 `RollbackScripts`（`V2_TO_V1` / `V4_TO_V3` /
`V5_TO_V4` / `V6_TO_V5`），每个迁移都可回滚。

## 2. 快照怎么生成

先由 Gradle 生成当前版本基线（会写 `6.db`）：

```bash
./gradlew :data:db:generateCommonMainReaderDatabaseSchema --console=plain --no-daemon
```

再按迁移脚本**逆序**从 `6.db` 推导历史快照。下面是本轮使用的脚本，新增迁移时需同步扩展：

```python
import os, shutil, sqlite3
D = r'F:/Kurisa/data/db/src/commonMain/sqldelight/databases'

def step(src, dst, sql):
    shutil.copyfile(os.path.join(D, src), os.path.join(D, dst))
    c = sqlite3.connect(os.path.join(D, dst))
    c.executescript(sql)
    c.commit()
    c.close()

# v6 -> v5：cookie 表回到明文 value 的旧结构（对应 V6_TO_V5）
step('6.db', '5.db', """
CREATE TABLE cookie_v5 (
  domain TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
  expires_at INTEGER, PRIMARY KEY(domain, name)
);
DROP TABLE cookie;
ALTER TABLE cookie_v5 RENAME TO cookie;
""")
# v5 -> v4：4.sqm 创建的整表，回滚即删表
step('5.db', '4.db', "DROP TABLE highlight;")
# v4 -> v3：3.sqm 追加的两个锚点列
step('4.db', '3.db', """
CREATE TABLE reading_progress_v3 (
  work_id TEXT PRIMARY KEY, chapter_id TEXT NOT NULL, page_index INTEGER,
  scroll_offset REAL, percent REAL NOT NULL, updated_at INTEGER NOT NULL,
  synced INTEGER NOT NULL DEFAULT 0
);
INSERT INTO reading_progress_v3 (work_id, chapter_id, page_index, scroll_offset, percent, updated_at, synced)
SELECT work_id, chapter_id, page_index, scroll_offset, percent, updated_at, synced FROM reading_progress;
DROP TABLE reading_progress;
ALTER TABLE reading_progress_v3 RENAME TO reading_progress;
""")
# v3 -> v2：2.sqm 创建的整表
step('3.db', '2.db', "DROP TABLE reader_setting;")
```

注意：`chapter` / `work` 上的三个索引不受上述重建影响；不要改动它们的定义，
否则校验会报索引差异。快照不含业务数据，只有 schema。

## 3. 新增迁移时的流程

1. 在 `migrations/` 加 `N.sqm`，在 `RollbackScripts` 补 `V(N+1)_TO_N`。
2. 重跑 `generateCommonMainReaderDatabaseSchema` 生成新的当前版本 `.db`。
3. 按上一节逆序重新生成全部历史快照（或只补新版本起点）。
4. 跑 `./gradlew :data:db:verifyCommonMainReaderDatabaseMigration --rerun-tasks`
   （任务可能对新增文件判定 `UP-TO-DATE`，务必带 `--rerun-tasks`）。
5. 跑 `./gradlew :data:db:desktopTest` 确认迁移/回滚用例仍绿。
