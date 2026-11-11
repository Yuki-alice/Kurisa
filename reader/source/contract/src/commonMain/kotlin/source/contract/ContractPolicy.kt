package source.contract

/**
 * 契约策略常量与校验（M1 冻结版 v1）。
 * 所有“源是否合法”的判定收口在这里，宿主与源仓库 CI 共用同一套规则。
 */
object ContractPolicy {
    const val CURRENT_API_VERSION = 2

    private val KEY_RE = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")
    private val SEMVER_RE = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")
    private val SHA256_RE = Regex("""^[0-9a-fA-F]{64}$""")

    /** 元数据合法性，返回违规列表（空 = 通过）。 */
    fun validateMeta(meta: SourceMeta): List<String> = buildList {
        if (meta.name.isBlank()) add("name 为空")
        if (!KEY_RE.matches(meta.key)) add("key 非法（须为反向域名）: ${meta.key}")
        if (!SEMVER_RE.matches(meta.version)) add("version 非 semver: ${meta.version}")
        if (!SEMVER_RE.matches(meta.minAppVersion)) add("minAppVersion 非 semver: ${meta.minAppVersion}")
        if (meta.apiVersion != CURRENT_API_VERSION) {
            add("apiVersion 非当前版本（期望 $CURRENT_API_VERSION，实际 ${meta.apiVersion}）")
        }
        if (meta.kind.isEmpty()) add("kind 为空（至少 manga/novel 之一）")
        if (meta.domains.isEmpty()) add("domains 为空（网络白名单缺失）")
        if (meta.domains.any { it.isBlank() }) add("domains 含空条目")
    }

    /** 仓库索引合法性（含 url/filename 二选一）。 */
    fun validateIndex(index: SourceIndex): List<String> = buildList {
        if (index.apiVersion != CURRENT_API_VERSION) {
            add("index.apiVersion 非当前版本（期望 $CURRENT_API_VERSION）")
        }
        index.sources.forEachIndexed { i, s ->
            val hasUrl = !s.url.isNullOrBlank()
            val hasFile = !s.filename.isNullOrBlank()
            if (hasUrl == hasFile) add("sources[$i] 须 url/filename 二选一（key=${s.key}）")
            if (!KEY_RE.matches(s.key)) add("sources[$i].key 非法: ${s.key}")
            if (!SEMVER_RE.matches(s.version)) add("sources[$i].version 非 semver: ${s.version}")
            if (s.kind.isEmpty()) add("sources[$i].kind 为空")
            if (s.domains.isEmpty()) add("sources[$i].domains 为空")
            if (s.sha256.isNotEmpty() && !SHA256_RE.matches(s.sha256)) {
                add("sources[$i].sha256 非法")
            }
        }
    }

    /**
     * optionList 默认值（M0 缺口 #3 的冻结语义）：
     * 取每组首个选项的 value 段（`"any-Any"` → `"any"`）；空组返回 null。
     * 宿主必须生成该数组后传入 `search.load(keyword, options, page)`，传 null 会崩。
     */
    fun defaultOptions(optionGroups: List<List<String>>): List<String?> =
        optionGroups.map { group -> group.firstOrNull()?.let { it.split('-')[0] } }

    /** APP.locale 形状（宿主 polyfill：`{ locale, version, platform }`）。 */
    fun isLocaleShape(locale: String): Boolean =
        locale.matches(Regex("[a-z]{2}_[A-Z]{2}"))
}
