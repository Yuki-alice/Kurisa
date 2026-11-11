package source.registry

/**
 * 静态扫描（安装时，纯文本，不执行代码）。
 * 真正的安全边界在运行时白名单（:data:network），此处只做提示与二次确认。
 */
object SourceStaticScan {
    data class Verdict(
        val blocked: Boolean,
        val blockedReason: String = "",
        val warnings: List<String> = emptyList(),
        val requiresDoubleConfirm: Boolean = false,
    )

    fun scan(script: String, declaredDomains: List<String>): Verdict {
        val bytes = script.toUtf8BytesLen()
        if (bytes > SourceSignature.MAX_SCRIPT_BYTES) {
            return Verdict(true, "脚本超大（>${SourceSignature.MAX_SCRIPT_BYTES}B，拒绝）")
        }
        val warnings = mutableListOf<String>()
        var confirm = false

        if (script.contains("eval(") || script.contains("new Function")) {
            warnings += "含动态 eval/Function 构造"
            confirm = true
        }
        if (Regex("""while\s*\(\s*true\s*\)""").containsMatchIn(script)) {
            warnings += "含 while(true) 模式（运行时有 10s 中断保护）"
            confirm = true
        }
        // 外发域名 vs 声明白名单
        val hosts = Regex("""https://([A-Za-z0-9.\-]+)""").findAll(script)
            .map { it.groupValues[1].lowercase() }.distinct().toList()
        val declared = declaredDomains.map { it.lowercase().removePrefix(".") }.toSet()
        val outsiders = hosts.filter { h ->
            declared.none { d -> h == d || h.endsWith(".$d") }
        }
        if (outsiders.isNotEmpty()) {
            warnings += "脚本内出现白名单外域名：${outsiders.take(5).joinToString(",")}"
            confirm = true
        }
        // 混淆特征：超长单行 / 大量 \x 转义
        val maxLine = script.lines().maxOfOrNull { it.length } ?: 0
        if (maxLine > 5000) {
            warnings += "存在超长单行（疑似混淆，${maxLine} 字符）"
            confirm = true
        }
        val escapes = Regex("""\\x[0-9a-fA-F]{2}""").findAll(script).count()
        if (escapes > 20) {
            warnings += "大量 \\x 转义（${escapes} 处，疑似混淆）"
            confirm = true
        }
        return Verdict(false, "", warnings, confirm)
    }

    private fun String.toUtf8BytesLen(): Int {
        // 近似字节数（ASCII 占多数场景足够判定 512KB；精确值由调用方按 UTF-8 计算）
        var n = 0
        for (c in this) n += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() || c.isLowSurrogate() -> 2 // 代理项按 2 计（近似）
            else -> 3
        }
        return n
    }
}
