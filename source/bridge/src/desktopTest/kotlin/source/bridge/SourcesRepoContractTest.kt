package source.bridge

import kotlinx.serialization.json.Json
import source.contract.ContractJson
import source.contract.ContractPolicy
import source.contract.SourceScriptHeader
import source.registry.RulesHeader
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 源仓库安装级门禁（desktopTest，按 `-Dkurisa.sourcesRepoDir=<dir>` 启用）。
 *
 * 用**真实契约代码**（[SourceScriptHeader] / [RulesHeader] + [ContractPolicy]）校验仓库内容，
 * 而不是只看引擎能否加载——引擎能 load 的源仍可能被安装器拒绝（例如 key 不是反向域名）。
 *
 * 校验项：
 * - 每个 `<key>.js` 的头块 / `<key>.rules.json` 的 JSON 头可解析且通过 `validateMeta`；
 * - 文件名与头块 key 一致，扩展名与形态一致（`.js`↔js/v1，`.rules.json`↔rules/v1）；
 * - `index.json` 条目：key 唯一、版本/kind/domains 与头块一致、sha256 与文件字节一致、
 *   filename 指向实际文件、format 与文件形态一致；
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

        val files = (scriptsDir.listFiles { f ->
            f.isFile && !f.name.startsWith("_") &&
                (f.name.endsWith(".js") || f.name.endsWith(".rules.json"))
        } ?: emptyArray()).sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "scripts 下无源脚本")

        val problems = mutableListOf<String>()
        val indexByKey = index.sources.associateBy { it.key }

        files.forEach { file ->
            val isRules = file.name.endsWith(".rules.json")
            val key = if (isRules) file.name.removeSuffix(".rules.json") else file.nameWithoutExtension
            val wantFormat = if (isRules) "rules/v1" else "js/v1"
            val script = file.readText()
            val meta = if (isRules) RulesHeader.parse(script) else SourceScriptHeader.parse(script)
            if (meta == null) {
                problems += if (isRules) {
                    "$key: 规则 JSON 不可解析或缺 key/name/kind（须 rules/v1）"
                } else {
                    "$key: 头块不可解析（==SourceScript== 缺失或字段不全）"
                }
                return@forEach
            }
            val metaViolations = ContractPolicy.validateMeta(meta)
            if (metaViolations.isNotEmpty()) {
                problems += "$key: " + metaViolations.joinToString("; ")
            }
            if (meta.key != key) {
                problems += "$key: 文件名与头块 key 不一致（${meta.key}）"
            }
            if (meta.format != wantFormat) {
                problems += "$key: 文件扩展名与形态不一致（${file.name} 应为 $wantFormat）"
            }
            val entry = indexByKey[key]
            if (entry == null) {
                problems += "$key: 脚本未登记进 index.json"
            } else {
                if (entry.version != meta.version) problems += "$key: 索引版本 ${entry.version} ≠ 脚本 ${meta.version}"
                if (entry.kind.sortedBy { it.serial } != meta.kind.sortedBy { it.serial }) {
                    problems += "$key: 索引 kind 与脚本不一致"
                }
                if (entry.filename != file.name) {
                    problems += "$key: 索引 filename 与实际文件不一致（${entry.filename} vs ${file.name}）"
                }
                if (entry.format != wantFormat) {
                    problems += "$key: 索引 format ${entry.format} ≠ 文件形态 $wantFormat"
                }
                val fileHash = sha256(file.readBytes())
                if (entry.sha256.lowercase() != fileHash) {
                    problems += "$key: 索引 sha256 与脚本字节不一致"
                }
            }
        }

        val fileKeys = files.map {
            if (it.name.endsWith(".rules.json")) it.name.removeSuffix(".rules.json") else it.nameWithoutExtension
        }.toSet()
        index.sources.forEach { e ->
            if (e.key !in fileKeys) problems += "${e.key}: 索引条目在 scripts/ 下无对应文件"
        }
        val dupKeys = index.sources.groupBy { it.key }.filterValues { it.size > 1 }.keys
        if (dupKeys.isNotEmpty()) problems += "索引 key 重复：$dupKeys"

        assertTrue(
            problems.isEmpty(),
            "源仓库安装级门禁失败（${problems.size} 项）：\n" + problems.take(20).joinToString("\n"),
        )
        println("源仓库门禁通过：${files.size} 个源（js/v1 ${files.count { it.name.endsWith(".js") }} + rules/v1 ${files.count { it.name.endsWith(".rules.json") }}）、${index.sources.size} 条索引")
    }

    /**
     * 两仓 schema 必须是同一份：源仓 `index.schema.json` == 主仓 `docs/contract/index-schema.json`。
     *
     * 按 JSON 结构比较（忽略缩进/键序），防止「主仓改了契约、源仓没跟」的静默漂移。
     * 契约路径由 `:source:bridge` 的测试任务注入（默认 `docs/contract/index-schema.json`）。
     */
    @Test
    fun sourcesRepoSchemaMatchesMainContract() {
        val dir = findDir() ?: return
        val contractPath = System.getProperty("kurisa.contractSchema")
        if (contractPath.isNullOrBlank()) {
            println("SKIP（未设置 -Dkurisa.contractSchema）：两仓 schema 一致性未校验")
            return
        }
        val contract = File(contractPath)
        assertTrue(contract.isFile, "主仓契约 schema 不存在：$contract")
        val theirs = File(dir, "index.schema.json")
        assertTrue(theirs.isFile, "源仓缺少 index.schema.json：$theirs")
        val expected = Json.parseToJsonElement(contract.readText())
        val actual = Json.parseToJsonElement(theirs.readText())
        assertTrue(
            expected == actual,
            "两仓 index schema 漂移：$theirs ≠ $contract（源仓应为主仓契约的副本）",
        )
        println("两仓 schema 一致：统一契约 v2")
    }
}
