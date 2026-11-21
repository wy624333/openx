package com.wy.openx.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据加密：用 Android Keystore 里的一把不可导出 AES 密钥做 AES/GCM。
 *
 * 为什么不直接把密码写进私有目录：应用私有目录虽受沙箱保护，但 root、备份工具、
 * 以及某些机型的「应用数据导出」都能读到。Keystore 的密钥存在 TEE/StrongBox 里，
 * 导出不来，密文离开这台设备就没用。
 *
 * 密钥首次使用时生成，之后一直复用；用户清除应用数据会连带删掉密钥，
 * 那时旧密文自然解不开——调用方要按「解密失败 = 凭据需重填」处理，不要当致命错误。
 */
internal object RemoteSecretBox {

    private const val KEY_ALIAS = "openx_remote_credentials"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** 加密为 Base64（`iv + 密文`）。空串原样返回，免得无凭据的条目也要走一遍加密。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    /** 解密 [encrypt] 的结果；密文损坏或密钥已失效时返回空串。 */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 不做用户认证绑定：后台同步/重连时没人解锁屏幕，要求认证会直接失败。
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
