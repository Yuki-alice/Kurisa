package source.contract

/**
 * `// ==SourceScript==` 元数据注释块解析（不执行代码）。
 * 安装流程第一步：读元数据 → 展示权限 → 用户确认 → 校验哈希/签名 → 静态扫描 → 落盘。
 */
object SourceScriptHeader {

    private const val BEGIN = "// ==SourceScript=="
    private const val END = "// ==/SourceScript=="

    /** 解析失败返回 null（调用方按“非法源”处理）。 */
    fun parse(sourceCode: String): SourceMeta? {
        val begin = sourceCode.indexOf(BEGIN)
        val end = sourceCode.indexOf(END)
        if (begin < 0 || end < 0 || end <= begin) return null
        val fields = mutableMapOf<String, String>()
        sourceCode.substring(begin + BEGIN.length, end).lines().forEach { line ->
            val t = line.trim()
            if (!t.startsWith("//")) return@forEach
            val body = t.removePrefix("//").trim()
            if (!body.startsWith("@")) return@forEach
            val key = body.substringAfter('@').substringBefore(' ').trim()
            val value = body.substringAfter(' ').trim()
            if (key.isNotEmpty()) fields[key] = value
        }
        val name = fields["name"] ?: return null
        val key = fields["key"] ?: return null
        val version = fields["version"] ?: return null
        val minApp = fields["minApp"] ?: return null
        val apiVersion = fields["apiVersion"]?.toIntOrNull()
            ?: return null
        val kind = (fields["kind"] ?: "")
            .split(',', ' ').mapNotNull { ContentKind.of(it) }.toSet()
        val domains = splitList(fields["domains"])
        val capabilities = splitList(fields["capabilities"])
        return SourceMeta(
            name = name,
            key = key,
            version = version,
            minAppVersion = minApp,
            apiVersion = apiVersion,
            kind = kind,
            domains = domains,
            capabilities = capabilities,
            url = fields["url"].orEmpty(),
        )
    }

    private fun splitList(raw: String?): List<String> =
        (raw ?: "").split(',', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
