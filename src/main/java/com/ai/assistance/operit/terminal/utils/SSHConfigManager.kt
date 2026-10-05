package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SSH 配置管理器（单一配置）
 *
 * 只管理一个 SSH 连接配置。
 *
 * 密码、私钥口令与手机侧 SSHD 口令一律经 [SecretStore] 加密后落盘：这些字段此前
 * 是明文写进同一个 SharedPreferences 文件的。字段名保持不变，靠 [SECRET_FORMAT_KEY]
 * 区分新旧格式，读取到旧格式时就地加密并改写，因此老安装升级后不需要重新输入凭据。
 */
class SSHConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ssh_config",
        Context.MODE_PRIVATE
    )

    private val secretStore = SecretStore()

    companion object {
        private const val TAG = "SSHConfigManager"
        private const val KEY_CONFIG = "config"
        private const val KEY_ENABLED = "ssh_enabled"

        /** 凭据字段的存储格式标记，缺失表示旧版明文格式 */
        private const val SECRET_FORMAT_KEY = "secretFormat"
        private const val SECRET_FORMAT_KEYSTORE_GCM = "keystore-gcm"
    }

    /**
     * 获取 SSH 配置
     */
    suspend fun getConfig(): SSHConfig? = withContext(Dispatchers.IO) {
        val configJson = prefs.getString(KEY_CONFIG, null)
        Log.d(TAG, "getConfig: config present = ${configJson != null}")

        if (configJson == null) {
            return@withContext null
        }

        try {
            val json = JSONObject(configJson)
            val migrated = migratePlaintextSecrets(json)
            val config = parseConfig(migrated.json)
            if (migrated.changed) {
                persist(migrated.json)
            }
            Log.d(TAG, "getConfig: parsed config for ${config.username}@${config.host}")
            config
        } catch (e: Exception) {
            Log.e(TAG, "getConfig: Failed to parse config", e)
            null
        }
    }

    /**
     * 保存 SSH 配置
     */
    suspend fun saveConfig(config: SSHConfig) = withContext(Dispatchers.IO) {
        Log.d(TAG, "saveConfig: Saving config for ${config.username}@${config.host}:${config.port}")
        val json = toJson(config)
        persist(json)
    }

    /**
     * 删除 SSH 配置
     */
    suspend fun deleteConfig() = withContext(Dispatchers.IO) {
        Log.d(TAG, "deleteConfig: Deleting SSH config")
        val success = prefs.edit().remove(KEY_CONFIG).commit()
        Log.d(TAG, "deleteConfig: Delete result = $success")
    }

    /**
     * 检查是否有配置
     */
    suspend fun hasConfig(): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(KEY_CONFIG)
    }

    /**
     * 获取 SSH 是否启用
     */
    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    /**
     * 设置 SSH 是否启用
     */
    fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "setEnabled: $enabled")
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 把旧版明文凭据就地加密。
     *
     * 不迁移的话，升级后的第一次读取会把明文原样留在磁盘上，加密就形同虚设。
     * 无法加密（Keystore 不可用）时保留原值，避免把用户唯一的凭据丢掉。
     */
    private fun migratePlaintextSecrets(json: JSONObject): MigrationResult {
        val alreadyEncrypted = json.optString(SECRET_FORMAT_KEY, "") == SECRET_FORMAT_KEYSTORE_GCM
        if (alreadyEncrypted) {
            return MigrationResult(json, changed = false)
        }

        var changed = false
        listOf("password", "passphrase", "localSshPassword").forEach { field ->
            val plain = json.optString(field, "")
            if (plain.isEmpty()) {
                return@forEach
            }
            val encrypted = secretStore.encrypt(plain)
            if (encrypted == null) {
                Log.e(TAG, "Failed to encrypt legacy $field; leaving it untouched")
                return@forEach
            }
            json.put(field, encrypted)
            changed = true
        }

        if (changed) {
            json.put(SECRET_FORMAT_KEY, SECRET_FORMAT_KEYSTORE_GCM)
            Log.i(TAG, "Migrated plaintext SSH secrets to Keystore-backed storage")
        }
        return MigrationResult(json, changed)
    }

    private fun persist(json: JSONObject) {
        val success = prefs.edit().putString(KEY_CONFIG, json.toString()).commit()
        Log.d(TAG, "persist: Save result = $success")
    }

    private suspend fun parseConfig(json: JSONObject): SSHConfig {
        val encrypted = json.optString(SECRET_FORMAT_KEY, "") == SECRET_FORMAT_KEYSTORE_GCM
        val localSshPassword =
            readSecret(json, "localSshPassword", encrypted).takeIf { it.isNotEmpty() }
                ?: ensureLocalSshPassword()

        return SSHConfig(
            host = json.getString("host"),
            port = json.optInt("port", 22),
            username = json.getString("username"),
            authType = SSHAuthType.valueOf(json.getString("authType")),
            password = readSecret(json, "password", encrypted).takeIf { it.isNotEmpty() },
            privateKeyPath = json.optString("privateKeyPath", "").takeIf { it.isNotEmpty() },
            passphrase = readSecret(json, "passphrase", encrypted).takeIf { it.isNotEmpty() },
            // 反向隧道配置
            enableReverseTunnel = json.optBoolean("enableReverseTunnel", false),
            remoteTunnelPort = json.optInt("remoteTunnelPort", 8888),
            localSshPort = json.optInt("localSshPort", 8022),
            localSshUsername = json.optString("localSshUsername", "root"),
            localSshPassword = localSshPassword
        )
    }

    /**
     * 读取凭据字段。
     *
     * 解密失败返回空串：调用方据此判定“凭据不可用”，由用户重新输入，
     * 而不是把一个坏值继续当作口令使用。
     */
    private fun readSecret(json: JSONObject, field: String, encrypted: Boolean): String {
        val raw = json.optString(field, "")
        if (raw.isEmpty()) {
            return ""
        }
        if (!encrypted) {
            return raw
        }
        val plain = secretStore.decrypt(raw)
        if (plain == null) {
            Log.e(TAG, "Failed to decrypt $field; user must re-enter it")
            return ""
        }
        return plain
    }

    /**
     * 生成并保存手机侧 SSHD 的口令。
     *
     * 该口令曾经硬编码为 "3688368398"，每台设备相同，等于没有口令；
     * 现在改为每安装随机生成，只有本机知道。
     */
    private suspend fun ensureLocalSshPassword(): String {
        val generated = secretStore.newRandomSecret()
        val encrypted = secretStore.encrypt(generated)
        if (encrypted == null) {
            Log.e(TAG, "Failed to persist generated local SSHD password")
            return generated
        }
        val json = prefs.getString(KEY_CONFIG, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null) {
            return generated
        }
        json.put("localSshPassword", encrypted)
        json.put(SECRET_FORMAT_KEY, SECRET_FORMAT_KEYSTORE_GCM)
        persist(json)
        Log.i(TAG, "Generated a per-install local SSHD password")
        return generated
    }

    private fun toJson(config: SSHConfig): JSONObject {
        val json = JSONObject()
        json.put("host", config.host)
        json.put("port", config.port)
        json.put("username", config.username)
        json.put("authType", config.authType.name)
        putSecret(json, "password", config.password)
        config.privateKeyPath?.let { json.put("privateKeyPath", it) }
        putSecret(json, "passphrase", config.passphrase)
        // 反向隧道配置
        json.put("enableReverseTunnel", config.enableReverseTunnel)
        json.put("remoteTunnelPort", config.remoteTunnelPort)
        json.put("localSshPort", config.localSshPort)
        json.put("localSshUsername", config.localSshUsername)
        putSecret(json, "localSshPassword", config.localSshPassword.takeIf { it.isNotEmpty() })
        json.put(SECRET_FORMAT_KEY, SECRET_FORMAT_KEYSTORE_GCM)
        return json
    }

    private fun putSecret(json: JSONObject, field: String, value: String?) {
        if (value == null) {
            return
        }
        val encrypted = secretStore.encrypt(value)
        if (encrypted == null) {
            Log.e(TAG, "Failed to encrypt $field; refusing to store it in clear text")
            return
        }
        json.put(field, encrypted)
    }

    private data class MigrationResult(val json: JSONObject, val changed: Boolean)
}
