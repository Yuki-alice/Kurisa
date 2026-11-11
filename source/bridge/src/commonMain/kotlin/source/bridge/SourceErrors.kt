package source.bridge

import source.contract.ContractPolicy
import source.contract.SourceErrorKind

/**
 * 插件引擎对宿主暴露的统一错误面。
 * JS 的 `throw` 字符串、桥内部异常、超时与协程取消都在这里收敛为
 * `kind + 原始 message`，上层只按 [kind] 决定重试/提示，不解析文本。
 */
class SourcePluginException(
    message: String,
    val kind: SourceErrorKind,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 桥异常 → 插件错误；已分类的不再重复分类，原始文本始终保留在 message。 */
internal fun Throwable.asPluginError(): SourcePluginException =
    if (this is SourcePluginException) this
    else SourcePluginException(
        message = message ?: this::class.simpleName.orEmpty(),
        kind = ContractPolicy.classifyError(message.orEmpty()),
        cause = this,
    )
