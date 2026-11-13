package app.desktop

import core.model.SecretCipher
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * 桌面端 AES-256-GCM 加密器（BE-C2）。
 *
 * 密钥是用户私有目录 `~/.kurisa/cookie.key` 下的 32 字节随机密钥，不随代码分发；
 * POSIX 系统上收紧为仅 owner 可读写，Windows 无 POSIX 权限模型时依赖用户目录 ACL。
 * 每次加密由 provider 生成独立随机 IV，输出 Base64(iv || ciphertext || tag)。
 * 密钥文件丢失即视为密钥轮换：旧密文解密失败，由 CookieStore fail-closed 丢弃。
 */
class FileSecretCipher(private val keyFile: File) : SecretCipher {

    private val key: SecretKey = loadOrCreateKey()

    private fun loadOrCreateKey(): SecretKey {
        keyFile.parentFile?.mkdirs()
        val bytes = if (keyFile.isFile && keyFile.length() == KEY_BYTES.toLong()) {
            keyFile.readBytes()
        } else {
            val generator = KeyGenerator.getInstance("AES")
            generator.init(KEY_BITS)
            val generated = generator.generateKey().encoded
            keyFile.writeBytes(generated)
            restrictOwnerOnly(keyFile)
            generated
        }
        return SecretKeySpec(bytes, "AES")
    }

    private fun restrictOwnerOnly(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        // Windows / 非 POSIX 文件系统：无 setPosixFilePermissions，依赖用户目录隔离。
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    override fun decrypt(payload: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(payload)
        require(raw.size > IV_SIZE + TAG_SIZE) { "ciphertext-too-short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            javax.crypto.spec.GCMParameterSpec(TAG_BITS, raw, 0, IV_SIZE),
        )
        String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val KEY_BYTES = 32
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val TAG_BITS = 128
    }
}
