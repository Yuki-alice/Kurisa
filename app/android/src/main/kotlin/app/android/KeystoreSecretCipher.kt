package app.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import core.model.SecretCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android 端 AES-256-GCM 加密器（BE-C2）。
 *
 * 密钥由 Android Keystore 生成并持有，不可导出；`setRandomizedEncryptionRequired(true)`
 * 要求 IV 由 Keystore 生成，因此这里不传 GCMParameterSpec，改用 `cipher.iv` 取回随机 IV。
 * 输出格式与桌面一致：Base64(iv || ciphertext || tag)，便于跨平台迁移密文记录。
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    private val key: SecretKey = loadOrCreateKey()

    private fun loadOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(payload: String): String? = runCatching {
        val raw = Base64.decode(payload, Base64.NO_WRAP)
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
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "kurisa_cookie_key"
        const val KEY_BITS = 256
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val TAG_BITS = 128
    }
}
