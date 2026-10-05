package com.ai.assistance.operit.terminal.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SSH 凭据的加密存储。
 *
 * 此前密码、私钥口令与本地 SSHD 口令都以明文写进 `SharedPreferences("ssh_config")`，
 * 任何能读到应用数据目录的进程都能直接拿到明文口令。这里改用 Android Keystore 中的
 * AES-256-GCM 密钥加密后再落盘：密钥不出 Keystore，落盘的只有 IV 与密文。
 *
 * 加解密失败时返回 null，由调用方要求用户重新输入。这里不做任何明文回落，
 * 否则加密层只是摆设，明文仍会留在同一个文件里。
 */
class SecretStore {

    companion object {
        private const val TAG = "SecretStore"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "operit_terminal_secret_store"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val KEY_BYTES = 256

        /** 生成的本地口令长度，去掉易混字符后约 1.1e18 组合 */
        private const val RANDOM_SECRET_LENGTH = 24
        private const val RANDOM_SECRET_ALPHABET =
            "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Base64(iv + ciphertext) 至少要有 IV 与一个 GCM tag */
        private const val MIN_ENCODED_BYTES = IV_BYTES + (GCM_TAG_BITS / 8)
    }

    private val secureRandom = SecureRandom()

    /**
     * 加密明文，返回 Base64(iv + ciphertext)。
     *
     * @return 加密结果；Keystore 不可用时返回 null
     */
    fun encrypt(plain: String): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + cipherText
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt secret", e)
            null
        }
    }

    /**
     * 解密 [encrypt] 产生的字符串。
     *
     * @return 明文；密钥丢失或数据被篡改时返回 null
     */
    fun decrypt(encoded: String): String? {
        return try {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size < MIN_ENCODED_BYTES) {
                Log.e(TAG, "Encrypted secret is too short: ${payload.size} bytes")
                return null
            }
            val iv = payload.copyOfRange(0, IV_BYTES)
            val cipherText = payload.copyOfRange(IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt secret", e)
            null
        }
    }

    /**
     * 生成一个随机口令，用于手机侧 SSHD 这类需要每安装独立凭据的场景。
     */
    fun newRandomSecret(): String {
        val builder = StringBuilder(RANDOM_SECRET_LENGTH)
        repeat(RANDOM_SECRET_LENGTH) {
            builder.append(RANDOM_SECRET_ALPHABET[secureRandom.nextInt(RANDOM_SECRET_ALPHABET.length)])
        }
        return builder.toString()
    }

    /**
     * 取 Keystore 中的密钥，不存在时创建。
     *
     * 密钥只在首次使用时生成，之后一直复用；应用被卸载或密钥被清除时，
     * 已落盘的密文将无法解密，调用方需要让用户重新输入凭据。
     */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { entry ->
            return entry.secretKey
        }

        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BYTES)
                // SSH 凭据要在后台连接时可用，因此不要求屏幕解锁或指纹
                .setUserAuthenticationRequired(false)
                .build()
        )
        Log.d(TAG, "Created Keystore secret for terminal credentials")
        return keyGenerator.generateKey()
    }
}
