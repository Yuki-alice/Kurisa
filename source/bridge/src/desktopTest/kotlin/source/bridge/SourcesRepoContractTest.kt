package source.bridge

import source.contract.ContractJson
import source.contract.ContractPolicy
import source.contract.SourceScriptHeader
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源仓库安装级门禁（desktopTest，按 `-Dkurisa.sourcesRepoDir=<dir>` 启用）。
 *
 * 用**真实契约代码**（[SourceScriptHeader] + [ContractPolicy]）校验仓库内容，
 * 而不是只看引擎能否加载——引擎能 load 的源仍可能被安装器拒绝（例如 key 不是反向域名）。
 *
 * 校验项：
 * - 每个 `<key>.js` 的头块可解析且通过 `validateMeta`（key/version/apiVersion/kind/domains）；
 * - 文件名与头块 `@key` 一致（仓库布局约定）；
 * - `index.json` 条目：key 唯一、版本/kind/domains 与头块一致、sha256 与文件字节一致；
 * - 索引中不存在的多余脚本（除 `_template` 前缀）视为违规。
 *
 * 未提供目录时 SKIP（CI 与其它机器零影响）。
 */
class SourcesRepoContractTest {

    private fun findDir(): File? {
        // 系统属性优先，其次环境变量（Gradle 的 -D 不会自动转发到测试 JVM，故两条通道都支持）。
        val p = System.getProperty("kurisa.sourcesRepoDir") ?: System.getenv("KURISA_SOURCES_REPO")
        if (p.isNullOrBlank()) return null
        val d = File(p)
        return if (d.isDirectory) d else null
    }

    private fun sha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun sourcesRepoPassesInstallerContract() {
        val dir = findDir()
        if (dir == null) {
            println("SKIP（未设置 -Dkurisa.sourcesRepoDir）：源仓库门禁仅本地执行")
            return
        }
        val scriptsDir = File(dir, "scripts")
        assertTrue(scriptsDir.isDirectory, "缺少 scripts 目录：$scriptsDir")

        val index = ContractJson.decodeIndex(File(dir, "index.json").readText())
        val indexViolations = ContractPolicy.validateIndex(index)
        assertTrue(indexViolations.isEmpty(), "index.json 违规：\n" + indexViolations.take(10).joinToString("\n"))

        val files = (scriptsDir.listFiles { f -> f.isFile && f.name.endsWith(".js") && !f.name.startsWith("_") } ?: emptyArray())
            .sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "scripts 下无源脚本")

        val problems = mutableListOf<String>()
        val indexByKey = index.sources.associateBy { it.key }

        files.forEach { file ->
            val key = file.nameWithoutExtension
            val script = file.readText()
            val meta = SourceScriptHeader.parse(script)
            if (meta == null) {
                problems += "$key: 头块不可解析（==SourceScript== 缺失或字段不全）"
                return@forEach
            }
            val metaViolations = ContractPolicy.validateMeta(meta)
            if (metaViolations.isNotEmpty()) {
                problems += "$key: " + metaViolations.joinToString("; ")
            }
            if (meta.key != key) {
                problems += "$key: 文件名与头块 @key 不一致（${meta.key}）"
            }
            val entry = indexByKey[key]
            if (entry == null) {
                problems += "$key: 脚本未登记进 index.json"
            } else {
                if (entry.version != meta.version) problems += "$key: 索引版本 ${entry.version} ≠ 头块 ${meta.version}"
                if (entry.kind.sortedBy { it.serial } != meta.kind.sortedBy { it.serial }) {
                    problems += "$key: 索引 kind 与头块不一致"
                }
                val fileHash = sha256(file.readBytes())
                if (entry.sha256.lowercase() != fileHash) {
                    problems += "$key: 索引 sha256 与脚本字节不一致"
                }
            }
        }

        val fileKeys = files.map { it.nameWithoutExtension }.toSet()
        index.sources.forEach { e ->
            if (e.key !in fileKeys) problems += "${e.key}: 索引条目在 scripts/ 下无对应文件"
        }
        val dupKeys = index.sources.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (dupKeys.isNotEmpty()) problems += "索引 key 重复：$dupKeys"

        assertTrue(
            problems.isEmpty(),
            "源仓库安装级门禁失败（${problems.size} 项）：\n" + problems.take(20).joinToString("\n"),
        )
        println("源仓库门禁通过：${files.size} 个源、${index.sources.size} 条索引")
    }
}
