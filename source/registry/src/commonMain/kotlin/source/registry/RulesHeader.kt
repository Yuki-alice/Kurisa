package source.registry

import source.contract.ContentKind
import source.contract.ContractPolicy
import source.contract.RulesPackageJson
import source.contract.SourceMeta

/**
 * 规则包头解析（`rules/v1` JSON，不执行脚本）。
 *
 * 安装期可信输入：顶层 `format/key/name/version/kind/domains`，其余执行期字段忽略。
 * 与 `SourceScriptHeader`（JS 注释块）互斥：调用方先按首字符分派，JSON 走此，JS 走头块。
 */
object RulesHeader {
    fun parse(script: String): SourceMeta? {
        val pkg = runCatching { RulesPackageJson.decode(script) }.getOrNull() ?: return null
        if (pkg.format != "rules/v1") return null
        if (pkg.key.isBlank() || pkg.name.isBlank()) return null
        val kinds = pkg.kind.mapNotNull { ContentKind.of(it) }.toSet()
        if (kinds.isEmpty()) return null
        val capabilities = buildList {
            add("search")
            if (pkg.content?.images != null) add("comic")
            if (pkg.content?.text != null) add("novel")
        }
        return SourceMeta(
            name = pkg.name,
            key = pkg.key,
            version = pkg.version.ifBlank { "1.0.0" },
            minAppVersion = "1.0.0",
            apiVersion = ContractPolicy.LATEST_CAPABILITY_VERSION,
            kind = kinds,
            domains = pkg.domains,
            capabilities = capabilities,
            url = "",
            format = "rules/v1",
        )
    }
}
