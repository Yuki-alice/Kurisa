package source.contract

/**
 * 契约策略常量与校验（M1 冻结版 v1 的向后兼容扩展）。
 * 所有“源是否合法”的判定收口在这里，宿主与源仓库 CI 共用同一套规则。
 *
 * 版本策略：`CURRENT_API_VERSION = 2` 是冻结基线（缺省值、索引校验、示例源不变）；
 * `SUPPORTED_API_VERSIONS` 额外接纳 3，表示源声明使用扩展能力集。
 * v2 源仍可安装运行，宿主按能力位降级；已冻结字段的语义变更才另起大版本。
 */
object ContractPolicy {
    const val CURRENT_API_VERSION = 2
    const val LATEST_CAPABILITY_VERSION = 4
    val SUPPORTED_API_VERSIONS = setOf(2, 3, 4)

    private val KEY_RE = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$""")
    private val SEMVER_RE = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$""")
    private val SHA256_RE = Regex("""^[0-9a-fA-F]{64}$""")

    /** v2 冻结契约覆盖的能力上界。 */
    val V2_CAPABILITIES = setOf(
        SourceCapability.SEARCH, SourceCapability.COMIC,
        SourceCapability.NOVEL, SourceCapability.ACCOUNT,
    )

    /** v3 扩展能力集（settings / 错误语义 / 图片配置 / 发现 / 分类 / 翻译 / 收藏）。 */
    val V3_CAPABILITIES: Set<SourceCapability> = V2_CAPABILITIES + setOf(
        SourceCapability.EXPLORE, SourceCapability.CATEGORY,
        SourceCapability.SETTINGS, SourceCapability.TRANSLATION,
        SourceCapability.IMAGE_LOAD, SourceCapability.FAVORITES,
    )

    /** v4 新增能力（novelChapter / htmlHelper / cryptoAes / video / audio，opt-in，不影响 v2/v3 冻结语义）。 */
    val V4_CAPABILITIES: Set<SourceCapability> = V3_CAPABILITIES + setOf(
        SourceCapability.NOVEL_CHAPTER, SourceCapability.HTML_HELPER, SourceCapability.CRYPTO_AES,
        SourceCapability.VIDEO, SourceCapability.AUDIO,
    )

    fun capabilitiesFor(apiVersion: Int): Set<SourceCapability> =
        when {
            apiVersion >= LATEST_CAPABILITY_VERSION -> V4_CAPABILITIES
            apiVersion >= 3 -> V3_CAPABILITIES
            else -> V2_CAPABILITIES
        }

    /**
     * 运行时有效能力 = 探测结果 ∩ 版本上界 ∩ 头块声明（头块为空时不额外收窄）。
     * 头块是安装期可信输入，与实例字段不一致时以头块为准。
     */
    fun effectiveCapabilities(
        detected: Set<SourceCapability>,
        apiVersion: Int,
        declared: Set<SourceCapability> = emptySet(),
    ): Set<SourceCapability> {
        val bounded = detected.intersect(capabilitiesFor(apiVersion))
        return if (declared.isEmpty()) bounded else bounded.intersect(declared)
    }

    /** 调用路径 → 所需能力；null 表示基础/调试路径，不做能力门。 */
    fun capabilityForPath(path: String): SourceCapability? = when (path.substringBefore('.')) {
        "search" -> SourceCapability.SEARCH
        "explore" -> SourceCapability.EXPLORE
        "category", "categoryComics" -> SourceCapability.CATEGORY
        "comic" -> SourceCapability.COMIC
        "novel" -> SourceCapability.NOVEL
        "video" -> SourceCapability.VIDEO
        "audio" -> SourceCapability.AUDIO
        "account" -> SourceCapability.ACCOUNT
        "favorites" -> SourceCapability.FAVORITES
        "settings" -> SourceCapability.SETTINGS
        "translate" -> SourceCapability.TRANSLATION
        else -> null
    }

    /**
     * JS 字符串异常分类（子串匹配，与 Venera 一致）。
     * 顺序敏感：`Login expired` 必须优先于其它标记；`429` 单列，方便站点直接用状态码抛错。
     */
    fun classifyError(message: String): SourceErrorKind {
        val m = message.lowercase()
        return when {
            m.contains("login expired") -> SourceErrorKind.LOGIN_EXPIRED
            m.contains("rate limited") || m.contains("429") || m.contains("cloudflare") ->
                SourceErrorKind.RATE_LIMITED
            m.contains("not found") || m.contains("notfound") -> SourceErrorKind.NOT_FOUND
            m.contains("domain-blocked") || m.contains("unsupported-hostcall") ||
                m.contains("模块导入不支持") -> SourceErrorKind.PERMISSION
            m.contains("timeout") || m.contains("超时") || m.contains("timed out") ->
                SourceErrorKind.TIMEOUT
            m.contains("disposed-or-unknown") || m.contains("bad_payload") ||
                m.contains("目录缺失") || m.contains("契约") -> SourceErrorKind.CONTRACT
            else -> SourceErrorKind.EXECUTION
        }
    }

    /** 元数据合法性，返回违规列表（空 = 通过）。 */
    fun validateMeta(meta: SourceMeta): List<String> = buildList {
        if (meta.name.isBlank()) add("name 为空")
        if (!KEY_RE.matches(meta.key)) add("key 非法（须为反向域名）: ${meta.key}")
        if (!SEMVER_RE.matches(meta.version)) add("version 非 semver: ${meta.version}")
        if (!SEMVER_RE.matches(meta.minAppVersion)) add("minAppVersion 非 semver: ${meta.minAppVersion}")
        if (meta.apiVersion !in SUPPORTED_API_VERSIONS) {
            add("apiVersion 不受支持（期望 ${SUPPORTED_API_VERSIONS.sorted()}，实际 ${meta.apiVersion}）")
        }
        if (meta.kind.isEmpty()) add("kind 为空（至少 manga/novel 之一）")
        if (meta.domains.isEmpty()) add("domains 为空（网络白名单缺失）")
        if (meta.domains.any { it.isBlank() }) add("domains 含空条目")
    }

    /** 仓库索引合法性（含 url/filename 二选一）。 */
    fun validateIndex(index: SourceIndex): List<String> = buildList {
        if (index.apiVersion !in SUPPORTED_API_VERSIONS) {
            add("index.apiVersion 不受支持（期望 ${SUPPORTED_API_VERSIONS.sorted()}）")
        }
        index.sources.forEachIndexed { i, s ->
            val hasUrl = !s.url.isNullOrBlank()
            val hasFile = !s.filename.isNullOrBlank()
            if (hasUrl == hasFile) add("sources[$i] 须 url/filename 二选一（key=${s.key}）")
            if (!KEY_RE.matches(s.key)) add("sources[$i].key 非法: ${s.key}")
            if (!SEMVER_RE.matches(s.version)) add("sources[$i].version 非 semver: ${s.version}")
            if (s.kind.isEmpty()) add("sources[$i].kind 为空")
            if (s.domains.isEmpty()) add("sources[$i].domains 为空")
            s.capabilities.forEach { c ->
                if (SourceCapability.of(c) == null) {
                    add("sources[$i].capabilities 含未知能力: $c")
                }
            }
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

    /** numeric semver 比较（预发布 < 正式）；格式已由 [validateMeta] 校验。 */
    fun compareSemver(a: String, b: String): Int {
        fun parts(v: String): Triple<List<Int>, String, Boolean> {
            val dash = v.indexOf('-')
            val core = if (dash < 0) v else v.substring(0, dash)
            val pre = if (dash < 0) "" else v.substring(dash + 1)
            return Triple(core.split('.').map { it.toIntOrNull() ?: 0 }, pre, dash >= 0)
        }
        val (ac, ap, aPre) = parts(a)
        val (bc, bp, bPre) = parts(b)
        for (i in 0 until maxOf(ac.size, bc.size)) {
            val d = (ac.getOrElse(i) { 0 }).compareTo(bc.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        if (aPre != bPre) {
            if (!aPre && bPre) return 1
            if (aPre && !bPre) return -1
            return ap.compareTo(bp)
        }
        return 0
    }
}
