package source.registry

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.toUtf8Bytes
import data.db.ReaderDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2 Gate：签名/权限校验生效 + 源生命周期（禁用/回滚）+ 审计可查。
 */
class RegistryLifecycleTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun script(version: String = "1.2.0") = """
        // ==SourceScript==
        // @name        N
        // @key         com.example.novel
        // @version     $version
        // @apiVersion  2
        // @minApp      1.0.0
        // @kind        novel
        // @domains     example.com
        // @capabilities search
        // @url         https://example.com/n.js
        // ==/SourceScript==
        class N extends ComicSource {}
    """.trimIndent()

    @Test
    fun installDisableRollback() {
        val db = openDb()
        val registry = SourceRegistry(db)

        // v1 安装
        val s1 = script("1.2.0")
        val p1 = SourceInstaller.plan(s1) as SourceInstaller.PlanResult.Ok
        val r1 = SourceInstaller.confirm(db, p1.plan, s1, "sources/com.example.novel/1.2.0.js", nowMs = 1L)
        registry.put(r1)
        assertEquals("1.2.0", registry.get("com.example.novel")?.version)

        // v2 更新
        val s2 = script("1.3.0")
        val p2 = SourceInstaller.plan(s2, installedVersion = "1.2.0") as SourceInstaller.PlanResult.Ok
        assertTrue(p2.plan.isUpdate)
        val r2 = SourceInstaller.confirm(db, p2.plan, s2, "sources/com.example.novel/1.3.0.js", nowMs = 2L)
        registry.put(r2)
        assertEquals("1.3.0", registry.get("com.example.novel")?.version)

        // 禁用
        assertTrue(registry.setEnabled("com.example.novel", false, 3L))
        assertFalse(registry.get("com.example.novel")?.enabled ?: true)
        assertTrue(registry.enabledKeys().none { it == "com.example.novel" })

        // 回滚到 v1
        val back = registry.rollback("com.example.novel", 4L)
        assertEquals("1.2.0", back?.version)
        assertTrue(back?.enabled ?: false)
    }

    @Test
    fun allListsInstalledRegardlessOfEnabled() {
        val db = openDb()
        val registry = SourceRegistry(db)

        val s1 = script("1.2.0")
        val p1 = SourceInstaller.plan(s1) as SourceInstaller.PlanResult.Ok
        registry.put(SourceInstaller.confirm(db, p1.plan, s1, "sources/a/1.2.0.js", nowMs = 1L))
        val s2 = script("2.0.0").replace("com.example.novel", "com.example.other")
        val p2 = SourceInstaller.plan(s2) as SourceInstaller.PlanResult.Ok
        registry.put(SourceInstaller.confirm(db, p2.plan, s2, "sources/b/2.0.0.js", nowMs = 2L))

        // ORDER BY key：全量有序
        assertEquals(listOf("com.example.novel", "com.example.other"), registry.allKeys())
        assertEquals(2, registry.all().size)

        // 禁用后仍在全量列表，仅退出 enabled 集合
        assertTrue(registry.setEnabled("com.example.novel", false, 3L))
        assertEquals(listOf("com.example.novel", "com.example.other"), registry.allKeys())
        assertEquals(
            listOf(false, true),
            registry.all().map { it.enabled },
        )
        assertEquals(listOf("com.example.other"), registry.enabledKeys())
    }
}
