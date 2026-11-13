package source.registry

import core.model.toUtf8Bytes

/**
 * 索引签名校验读取（B-8；源仓 signatures/index.sig + keys 目录 .pub 公钥）。
 *
 * 被签名消息（冻结语义）：index.json 的 UTF-8 字节（首尾空白不敏感，签名与验签统一 trim）。
 * - `signatures/index.sig`：base64 ed25519 分离签名；缺失 → [IndexSignatureStatus.Unsigned]；
 * - keys 目录 .pub 公钥：32 字节 hex（TOFU，支持轮换：逐个试，任一通过即 [Verified]）；
 * - 未签名不是失败（兼容旧仓库），UI 展示“未验证”即可；验签不过才是 [Invalid]。
 *
 * 文件 IO 由调用方（平台壳/测试）完成，此处只收字节与字符串，不新增 expect/actual。
 */
sealed interface IndexSignatureStatus {
    data object Unsigned : IndexSignatureStatus

    data class Verified(val keyName: String) : IndexSignatureStatus

    data class Invalid(val reason: String) : IndexSignatureStatus
}

object SourceIndexSignature {

    /** 被签名消息：index.json 去首尾空白后的 UTF-8 字节。 */
    fun signedBytes(indexJson: String): ByteArray = indexJson.trim().toUtf8Bytes()

    /**
     * 校验索引签名。
     * @param signatureB64 `signatures/index.sig` 全文（base64；null/空白=未签名）。
     * @param publicKeys 可用公钥（文件名去扩展名 → hex；为空=无钥可验）。
     */
    fun check(
        indexJson: String,
        signatureB64: String?,
        publicKeys: Map<String, String>,
    ): IndexSignatureStatus {
        if (signatureB64.isNullOrBlank()) return IndexSignatureStatus.Unsigned
        if (publicKeys.isEmpty()) return IndexSignatureStatus.Invalid("no-public-key")
        val msg = signedBytes(indexJson)
        for ((name, pubHex) in publicKeys) {
            if (SourceSignature.verifySignature(pubHex.trim(), msg, signatureB64.trim())) {
                return IndexSignatureStatus.Verified(name)
            }
        }
        return IndexSignatureStatus.Invalid("signature-mismatch")
    }
}
