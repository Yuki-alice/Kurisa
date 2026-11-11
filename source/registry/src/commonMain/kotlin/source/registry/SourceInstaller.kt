package source.registry

import core.model.SourceRecord
import core.model.toUtf8Bytes
import data.db.ReaderDatabase
import source.contract.ContractPolicy
import source.contract.IndexedSource
import source.contract.SourceScriptHeader

/**
 * 源安装流程（架构 4.2/4.7/4.8）：
 * 解析元数据（不执行）→ 展示权限 → 用户显式确认 → 校验哈希/签名 → 静态扫描 → 落盘。
 *
 * 文件落盘由调用方经 FileKit 完成（本模块不碰文件系统，无平台依赖）；
 * 此处只写 `source` 表 + 返回脚本字节供调用方落盘。
 */
object SourceInstaller {

    data class InstallPlan(
        val key: String,
        val name: String,
        val version: String,
        val apiVersion: Int = source.contract.ContractPolicy.CURRENT_API_VERSION,
        val domains: List<String>,
        val capabilities: List<String>,
        val kinds: List<String>,
        val sha256Ok: Boolean,
        val signatureOk: Boolean,
        val signatureMissing: Boolean,
        val warnings: List<String>,
        val requiresDoubleConfirm: Boolean,
        val isUpdate: Boolean,
        val isDowngrade: Boolean,
    )

    sealed interface PlanResult {
        data class Ok(val plan: InstallPlan) : PlanResult
        data class Rejected(val reasons: List<String>) : PlanResult
    }

    fun plan(
        script: String,
        expected: IndexedSource? = null,
        repoPubKeyHex: String? = null,
        installedVersion: String? = null,
    ): PlanResult {
        val scriptBytes = script.toUtf8Bytes()
        if (scriptBytes.size > SourceSignature.MAX_SCRIPT_BYTES) {
            return PlanResult.Rejected(listOf("脚本超大（${scriptBytes.size}B），拒绝"))
        }
        val meta = SourceScriptHeader.parse(script)
            ?: return PlanResult.Rejected(listOf("缺少合法的 ==SourceScript== 元数据块"))
        val violations = ContractPolicy.validateMeta(meta)
        if (violations.isNotEmpty()) return PlanResult.Rejected(violations)

        if (expected != null) {
            if (expected.key != meta.key) return PlanResult.Rejected(listOf("key 与仓库索引不一致"))
            if (expected.version != meta.version) {
                return PlanResult.Rejected(listOf("version 与仓库索引不一致（脚本 ${meta.version} vs 索引 ${expected.version}）"))
            }
            if (!SourceSignature.verifySha256(scriptBytes, expected.sha256)) {
                return PlanResult.Rejected(listOf("sha256 与仓库索引不一致，拒绝"))
            }
        }

        var signatureOk = false
        var signatureMissing = false
        val sig = expected?.signature
        if (sig.isNullOrBlank()) {
            signatureMissing = true // 兼容旧仓库，标记未验证（架构附录 A）
        } else if (repoPubKeyHex.isNullOrBlank()) {
            return PlanResult.Rejected(listOf("仓库条目带签名但未绑定公钥（TOFU 缺失）"))
        } else {
            val msg = SourceSignature.signedMessage(meta.key, meta.version, expected.sha256)
            signatureOk = SourceSignature.verifySignature(repoPubKeyHex, msg, sig)
            if (!signatureOk) return PlanResult.Rejected(listOf("ed25519 签名校验失败，拒绝并保留旧版本"))
        }

        val scan = SourceStaticScan.scan(script, meta.domains)
        if (scan.blocked) return PlanResult.Rejected(listOf(scan.blockedReason))

        val cmp = installedVersion?.let { compareSemver(meta.version, it) } ?: 0
        return PlanResult.Ok(
            InstallPlan(
                key = meta.key,
                name = meta.name,
                version = meta.version,
                apiVersion = meta.apiVersion,
                domains = meta.domains,
                capabilities = meta.capabilities,
                kinds = meta.kind.map { it.serial },
                // 无索引条目时脚本哈希未被任何可信源校验，标记未验证（原先恒 true 是缺陷）。
                sha256Ok = expected != null,
                signatureOk = signatureOk,
                signatureMissing = signatureMissing,
                warnings = scan.warnings,
                requiresDoubleConfirm = scan.requiresDoubleConfirm || signatureMissing || expected == null,
                isUpdate = installedVersion != null && cmp > 0,
                isDowngrade = installedVersion != null && cmp < 0,
            ),
        )
    }

    fun confirm(
        db: ReaderDatabase,
        plan: InstallPlan,
        script: String,
        installPath: String,
        settingsJson: String = "{}",
        nowMs: Long,
    ): SourceRecord {
        val sha = SourceSignature.sha256OfScript(script.toUtf8Bytes())
        db.sourceQueries.upsertSource(
            plan.key, plan.name, plan.version, plan.apiVersion.toLong(),
            plan.kinds.joinToString(",", "[", "]"),
            plan.capabilities.joinToString(",", "[", "]"),
            plan.domains.joinToString(",", "[", "]"),
            installPath, sha, if (plan.signatureOk) 1L else 0L, 1L, settingsJson, nowMs,
        )
        return SourceRecord(
            key = plan.key, name = plan.name, version = plan.version,
            apiVersion = plan.apiVersion,
            installPath = installPath, sha256 = sha,
            signatureOk = plan.signatureOk, enabled = true,
            settingsJson = settingsJson, updatedAtEpochMs = nowMs,
        )
    }

    /** semver 比较收敛到契约策略（宿主装配层与安装流程共用同一套规则）。 */
    fun compareSemver(a: String, b: String): Int = ContractPolicy.compareSemver(a, b)
}
