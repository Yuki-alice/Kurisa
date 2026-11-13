package feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 源管理（安装/更新/权限/设置/日志/一键禁用，见 M2；UI 随 M5）。 */
@Serializable
data object SourcesRoute : NavKey

/**
 * 规则测试面板路由（P3 工具链；纯调试入口，不承载业务状态）。
 * 后端见 `SourceCatalog.testRules`（三步 diagnose 一次返回）；UI 只做展示。
 */
@Serializable
data object RuleTestRoute : NavKey

/**
 * 三步诊断展示行（请求 → 解析 → 内容；失败时三段均为 `{error,code}` 透传原文）。
 * 由 App 层从 `testRules` 返回的组合 JSON 映射，feature 只收展示字符串。
 */
data class RuleTestResultUi(
    val searchJson: String = "",
    val detailJson: String = "",
    val contentJson: String = "",
    val error: String? = null,
)

/**
 * 源列表 UI 行（跨层组装体：由 App 层从 `SourceRecord` 映射，禁止把 DB 行直传 UI）。
 * 纯展示字段，开关状态由 [enabled] 表达。
 */
data class SourceUiItem(
    val key: String,
    val name: String,
    val version: String,
    val enabled: Boolean,
    /** 人类可读的类型行，如 "manga · search, novel" */
    val kindsLabel: String = "",
    /** 人类可读的域名行，如 "example.com, cdn.example.com" */
    val domainsLabel: String = "",
    /** 校验行：已签名 / 未验证（旧仓库兼容） */
    val verifiedLabel: String = "",
)

/**
 * 安装预览 UI 行（由 App 层从 `InstallPlan` 映射；拒绝时仅 [error] 非空）。
 * 白名单域名必须向用户明示：安装即授权该源访问这些域名（架构 4.2）。
 */
data class InstallPreviewUi(
    val name: String = "",
    val key: String = "",
    val version: String = "",
    val kindsLabel: String = "",
    val domainsLabel: String = "",
    val capabilitiesLabel: String = "",
    val warnings: List<String> = emptyList(),
    val verifiedLabel: String = "",
    /** 新装 / 更新 / 降级（将覆盖现有版本） */
    val updateLabel: String = "",
    val requiresDoubleConfirm: Boolean = false,
    val error: String? = null,
)

/** 安装结果（一次性，不进 State；失败原因直达 UI，不吞错）。 */
sealed interface InstallOutcomeUi {
    data class Installed(val item: SourceUiItem) : InstallOutcomeUi
    data class NeedsConfirm(val preview: InstallPreviewUi) : InstallOutcomeUi
    data class Rejected(val reason: String) : InstallOutcomeUi
}

/** 源仓库更新候选（纯展示行；App 层从 `SourceUpdateUi` 映射，feature 不依赖装配层）。 */
data class RepoUpdateRowUi(
    val key: String,
    val version: String,
    val installedVersion: String?,
    val reason: String,
)

/** 一次「检查更新」的结果：候选展示行 + 失败原因（成功时为 null）。 */
data class RepoUpdatesUi(
    val rows: List<RepoUpdateRowUi> = emptyList(),
    val error: String? = null,
)

@Composable
fun SourcesScreen(
    items: List<SourceUiItem> = emptyList(),
    onToggle: (key: String, enabled: Boolean) -> Unit = { _, _ -> },
    onPreviewInstall: suspend (script: String) -> InstallPreviewUi =
        { InstallPreviewUi(error = "未装配") },
    onConfirmInstall: suspend (script: String, confirmed: Boolean) -> InstallOutcomeUi =
        { _, _ -> InstallOutcomeUi.Rejected("未装配") },
    onOpenRuleTest: () -> Unit = {},
    /** 检查源仓库更新（null = 不展示入口；由 App 层接订阅出口）。 */
    onCheckUpdates: (suspend () -> RepoUpdatesUi)? = null,
) {
    SourcesContent(
        items = items,
        onToggle = onToggle,
        onPreviewInstall = onPreviewInstall,
        onConfirmInstall = onConfirmInstall,
        onOpenRuleTest = onOpenRuleTest,
        onCheckUpdates = onCheckUpdates,
    )
}

@Composable
fun SourcesContent(
    items: List<SourceUiItem>,
    onToggle: (key: String, enabled: Boolean) -> Unit,
    onPreviewInstall: suspend (script: String) -> InstallPreviewUi,
    onConfirmInstall: suspend (script: String, confirmed: Boolean) -> InstallOutcomeUi,
    onOpenRuleTest: () -> Unit = {},
    onCheckUpdates: (suspend () -> RepoUpdatesUi)? = null,
    modifier: Modifier = Modifier,
) {
    var installOpen by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var updates by remember { mutableStateOf<RepoUpdatesUi?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("源管理", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
                TextButton(onClick = onOpenRuleTest) { Text("规则测试") }
                if (onCheckUpdates != null) {
                    TextButton(
                        enabled = !checking,
                        onClick = {
                            scope.launch {
                                checking = true
                                updates = runCatching { onCheckUpdates() }
                                    .getOrElse { RepoUpdatesUi(error = it.message ?: "检查更新失败") }
                                checking = false
                            }
                        },
                    ) { Text(if (checking) "检查中…" else "检查更新") }
                }
                OutlinedButton(onClick = { installOpen = true }) { Text("安装") }
            }
        }
        updates?.let { result ->
            result.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (result.rows.isEmpty() && result.error == null) {
                Text("无可用更新", style = MaterialTheme.typography.bodySmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                result.rows.forEach { row ->
                    Text(
                        "${row.key} → v${row.version}" +
                            (row.installedVersion?.let { "（已装 v$it）" } ?: "") +
                            " · ${row.reason}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (items.isEmpty()) {
            Text(
                "尚未安装任何源。应用本体不预置源（C1），请从可信仓库订阅后安装。",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
                items(items, key = { it.key }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(Tokens.spacingM),
                            horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${item.key} · v${item.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (item.kindsLabel.isNotBlank()) {
                                    Text(item.kindsLabel, style = MaterialTheme.typography.bodySmall)
                                }
                                if (item.domainsLabel.isNotBlank()) {
                                    Text(item.domainsLabel, style = MaterialTheme.typography.bodySmall)
                                }
                                if (item.verifiedLabel.isNotBlank()) {
                                    Text(item.verifiedLabel, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { onToggle(item.key, it) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (installOpen) {
        InstallDialog(
            onPreviewInstall = onPreviewInstall,
            onConfirmInstall = onConfirmInstall,
            onClose = { installOpen = false },
        )
    }
}

/**
 * 组合诊断 JSON → 展示行（纯函数，可单测）。
 * `testRules` 返回 `{"search":{...},"detail":{...},"content":{...}}`；
 * 任一段缺失即视为后端契约破裂，整体标 error（面板不猜字段）。
 */
fun parseRuleTestResult(raw: String): RuleTestResultUi {
    val root: JsonObject = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return RuleTestResultUi(error = "诊断返回非法 JSON")
    fun section(name: String): String? {
        val el: JsonElement = root[name] ?: return null
        if (el !is JsonObject) return null
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), el)
    }
    val search = section("search") ?: return RuleTestResultUi(error = "诊断缺 search 段")
    val detail = section("detail") ?: return RuleTestResultUi(error = "诊断缺 detail 段")
    val content = section("content") ?: return RuleTestResultUi(error = "诊断缺 content 段")
    return RuleTestResultUi(searchJson = search, detailJson = detail, contentJson = content)
}

/**
 * 规则测试面板（三步：请求 / 解析 / 内容；失败走 `{error,code}` 原文展示，不抛错）。
 * 诊断在后台线程执行（规则网络 + 解析开销与阅读链路同级）。
 */
@Composable
fun RuleTestScreen(
    sources: List<SourceUiItem> = emptyList(),
    onBack: () -> Unit = {},
    onDiagnose: suspend (key: String, keyword: String, page: Int, workId: String, chapterId: String) -> RuleTestResultUi =
        { _, _, _, _, _ -> RuleTestResultUi(error = "未装配") },
) {
    RuleTestContent(sources = sources, onBack = onBack, onDiagnose = onDiagnose)
}

@Composable
fun RuleTestContent(
    sources: List<SourceUiItem>,
    onBack: () -> Unit,
    onDiagnose: suspend (key: String, keyword: String, page: Int, workId: String, chapterId: String) -> RuleTestResultUi,
    modifier: Modifier = Modifier,
) {
    var key by remember(sources) { mutableStateOf(sources.firstOrNull()?.key.orEmpty()) }
    var keyword by remember { mutableStateOf("") }
    var pageText by remember { mutableStateOf("1") }
    var workId by remember { mutableStateOf("") }
    var chapterId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RuleTestResultUi?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("规则测试", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack) { Text("返回") }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it; result = null },
            label = { Text("源 key") },
            supportingText = { Text("已安装：${sources.joinToString { it.key }.ifBlank { "（无）" }}") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it; result = null },
                label = { Text("关键词") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = pageText,
                onValueChange = { pageText = it.filter(Char::isDigit).take(4); result = null },
                label = { Text("页码") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            OutlinedTextField(
                value = workId,
                onValueChange = { workId = it; result = null },
                label = { Text("作品 id") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = chapterId,
                onValueChange = { chapterId = it; result = null },
                label = { Text("章节 id") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        val err = result?.error
        if (err != null) {
            Text(err, style = MaterialTheme.typography.bodyMedium)
        }
        if (busy) CircularProgressIndicator()
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    result = onDiagnose(key, keyword, pageText.toIntOrNull() ?: 1, workId, chapterId)
                    busy = false
                }
            },
            enabled = !busy && key.isNotBlank(),
        ) { Text("运行诊断") }
        result?.takeIf { it.error == null }?.let { r ->
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
            ) {
                RuleTestSection("请求 / 解析", r.searchJson)
                RuleTestSection("详情 / 目录", r.detailJson)
                RuleTestSection("内容 / 正文", r.contentJson)
            }
        }
    }
}

@Composable
private fun RuleTestSection(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(Tokens.spacingM),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 安装对话框（三段：粘贴 → 权限预览 → 确认；拒绝/失败原因原样展示）。
 * 脚本在预览阶段不执行（`plan()` 只解析头块 + 静态扫描）。
 */
@Composable
private fun InstallDialog(
    onPreviewInstall: suspend (script: String) -> InstallPreviewUi,
    onConfirmInstall: suspend (script: String, confirmed: Boolean) -> InstallOutcomeUi,
    onClose: () -> Unit,
) {
    var script by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<InstallPreviewUi?>(null) }
    var doubleConfirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(Tokens.spacingL),
                verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
            ) {
                Text("安装源", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = script,
                    onValueChange = { script = it; preview = null; result = null },
                    label = { Text("粘贴源脚本（.js 全文）") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                val err = preview?.error
                if (err != null) {
                    Text(err, style = MaterialTheme.typography.bodyMedium)
                }
                preview?.takeIf { it.error == null }?.let { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${p.name} · v${p.version}", style = MaterialTheme.typography.titleMedium)
                        Text(p.key, style = MaterialTheme.typography.bodySmall)
                        Text("类型：${p.kindsLabel}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "网络白名单：${p.domainsLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (p.capabilitiesLabel.isNotBlank()) {
                            Text("能力：${p.capabilitiesLabel}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("校验：${p.verifiedLabel}", style = MaterialTheme.typography.bodySmall)
                        Text("变更：${p.updateLabel}", style = MaterialTheme.typography.bodySmall)
                        p.warnings.forEach { w ->
                            Text("注意：$w", style = MaterialTheme.typography.bodySmall)
                        }
                        if (p.requiresDoubleConfirm) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = doubleConfirmed,
                                    onCheckedChange = { doubleConfirmed = it },
                                )
                                Text(
                                    "我确认来源可信，仍要安装",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (busy) CircularProgressIndicator()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onClose) { Text("关闭") }
                    if (preview?.error == null && result == null) {
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    preview = onPreviewInstall(script)
                                    busy = false
                                }
                            },
                            enabled = !busy && script.isNotBlank(),
                        ) { Text(if (preview == null) "解析" else "重新解析") }
                    }
                    val p = preview
                    if (p != null && p.error == null && result == null) {
                        TextButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    when (val o = onConfirmInstall(script, doubleConfirmed)) {
                                        is InstallOutcomeUi.Installed ->
                                            result = "已安装 ${o.item.name} v${o.item.version}"
                                        is InstallOutcomeUi.NeedsConfirm ->
                                            result = "该源未经签名验证，请勾选确认后重试"
                                        is InstallOutcomeUi.Rejected -> result = o.reason
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy && (!p.requiresDoubleConfirm || doubleConfirmed),
                        ) { Text("确认安装") }
                    }
                }
            }
        }
    }
}
