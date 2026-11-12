package source.bridge

import source.contract.ContractPolicy
import source.contract.SourceErrorKind
import source.hostapi.HostApi

/** 原生引擎版本（apiVersion=4；能力位见 `ContractPolicy.V4_CAPABILITIES`）。 */
const val NATIVE_API_VERSION = 4

/**
 * 版本路由工厂（纯委托，零行为变；语义见 `docs/plugin-system/DESIGN.md` §11）。
 *
 * - apiVersion 2/3 进垫片（QuickJs 路径，冻结）；
 * - apiVersion 4 进原生引擎（`strictV4`，未转正密码学显式拒绝，不静默降级）；
 * - 未知版本 fail-closed：求值前拒绝，不触发脚本执行。
 *
 * 安全说明（security-audit 自查）：本改动不新增桥面、不碰网络/Cookie/白名单；
 * 未知版本提前拒绝只缩小攻击面。威胁模型与隔离语义沿用垫片现状。
 */
class VersionedPluginEngineFactory(
    private val legacy: PluginEngineFactory,
    private val native: PluginEngineFactory? = null,
) : PluginEngineFactory {
    override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine {
        if (spec.apiVersion == NATIVE_API_VERSION) {
            return native?.create(spec, host)
                ?: throw SourcePluginException(
                    "原生引擎未就绪（apiVersion=4）",
                    SourceErrorKind.CONTRACT,
                )
        }
        if (spec.apiVersion in ContractPolicy.SUPPORTED_API_VERSIONS) return legacy.create(spec, host)
        throw SourcePluginException(
            "不支持的 apiVersion=${spec.apiVersion}（垫片仅支持 ${ContractPolicy.SUPPORTED_API_VERSIONS.sorted()}）",
            SourceErrorKind.CONTRACT,
        )
    }
}