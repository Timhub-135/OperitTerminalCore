package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.io.File
import java.security.MessageDigest

/**
 * 被拒绝的主机密钥，供 UI 展示并让用户决定是否信任。
 *
 * @param host 目标主机
 * @param keyType 密钥类型，例如 ssh-ed25519
 * @param keyBase64 公钥内容（OpenSSH known_hosts 中的第三列）
 * @param fingerprintSha256 SHA256: 前缀的指纹，用户可用 ssh-keygen -lf 核对
 * @param mismatch true 表示该主机已有密钥且与新密钥不一致，即可能存在中间人
 */
data class HostKeyChallenge(
    val host: String,
    val keyType: String,
    val keyBase64: String,
    val fingerprintSha256: String,
    val mismatch: Boolean
)

/**
 * 主机密钥校验失败。
 *
 * 连接层把该异常原样返回给调用方，由 UI 展示 [challenge] 并让用户显式信任，
 * 而不是在连接层自行接受未知密钥。
 */
class HostKeyVerificationException(val challenge: HostKeyChallenge) :
    Exception(
        if (challenge.mismatch) {
            "Host key for ${challenge.host} changed (${challenge.fingerprintSha256})"
        } else {
            "Host key for ${challenge.host} is not trusted yet (${challenge.fingerprintSha256})"
        }
    )

/**
 * 主机密钥存储（known_hosts）。
 *
 * 此前连接配置写死 `StrictHostKeyChecking=no`，任何能劫持网络或 DNS 的一方都能冒充
 * 目标服务器，而用户看不到任何提示。这里把主机密钥落成标准 OpenSSH 格式的
 * known_hosts 文件：未知主机返回挑战、由用户核对指纹后才写入，指纹变化直接中断连接。
 *
 * 只处理明文主机名的条目；外部导入的哈希主机名条目（`|1|...`）不参与匹配，
 * 也不会被误判成“密钥变化”。条目里的主机名按连接时使用的主机名原样保存，
 * 与 IP 连接的场景一致。
 */
class HostKeyStore(context: Context) : HostKeyRepository {

    companion object {
        private const val TAG = "HostKeyStore"

        /** known_hosts 条目：主机名、密钥类型、Base64 公钥，可选注释 */
        private const val FIELD_SEPARATOR = " "
    }

    private val knownHostsFile = File(File(context.filesDir, "ssh"), "known_hosts")

    /** check() 在握手线程上被调用，把被拒的密钥记下来交给连接层 */
    @Volatile
    private var lastRejected: HostKeyChallenge? = null

    /**
     * 取出并清空最近一次被拒绝的主机密钥。
     */
    fun consumeRejected(): HostKeyChallenge? {
        val challenge = lastRejected
        lastRejected = null
        return challenge
    }

    /**
     * 信任一个主机密钥。
     *
     * 仅在用户看到指纹并确认后调用。密钥变化场景下会替换旧条目，
     * 因为用户已经明确表示接受新密钥。
     */
    fun trust(challenge: HostKeyChallenge) {
        synchronized(this) {
            val entries = readEntries().toMutableList()
            entries.removeAll { it.host == challenge.host && it.keyType == challenge.keyType }
            entries.add(Entry(challenge.host, challenge.keyType, challenge.keyBase64))
            writeEntries(entries)
            Log.i(
                TAG,
                "Trusted ${challenge.keyType} key for ${challenge.host} (${challenge.fingerprintSha256})"
            )
        }
    }

    /**
     * 列出全部已知主机条目，供设置页展示。
     */
    fun knownEntries(): List<Entry> = synchronized(this) { readEntries().toList() }

    /**
     * 忘记某个主机的全部密钥，下次连接会重新询问。
     */
    fun forget(host: String) {
        synchronized(this) {
            val entries = readEntries().filterNot { it.host == host }
            writeEntries(entries)
            Log.i(TAG, "Forgot host keys for $host")
        }
    }

    override fun check(host: String, key: ByteArray): Int {
        val keyType = keyTypeOf(key)
        if (keyType == null) {
            // 无法解析的密钥无法比对，按未收录处理，由调用方中断连接
            Log.e(TAG, "Unable to parse host key type for $host")
            lastRejected = null
            return HostKeyRepository.NOT_INCLUDED
        }

        val presented = Base64.encodeToString(key, Base64.NO_WRAP)
        val known = synchronized(this) {
            readEntries().filter { it.host == host && it.keyType == keyType }
        }
        if (known.isEmpty()) {
            lastRejected = HostKeyChallenge(
                host = host,
                keyType = keyType,
                keyBase64 = presented,
                fingerprintSha256 = fingerprintOf(key),
                mismatch = false
            )
            return HostKeyRepository.NOT_INCLUDED
        }
        if (known.any { it.keyBase64 == presented }) {
            return HostKeyRepository.OK
        }

        lastRejected = HostKeyChallenge(
            host = host,
            keyType = keyType,
            keyBase64 = presented,
            fingerprintSha256 = fingerprintOf(key),
            mismatch = true
        )
        return HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        synchronized(this) {
            val entries = readEntries().toMutableList()
            entries.removeAll { it.host == hostkey.host && it.keyType == hostkey.type }
            entries.add(Entry(hostkey.host, hostkey.type, hostkey.key))
            writeEntries(entries)
        }
    }

    override fun remove(host: String, type: String) {
        synchronized(this) {
            val entries = readEntries().filterNot { it.host == host && it.keyType == type }
            writeEntries(entries)
        }
    }

    override fun remove(host: String, type: String, key: ByteArray) {
        synchronized(this) {
            val presented = Base64.encodeToString(key, Base64.NO_WRAP)
            val entries = readEntries().filterNot {
                it.host == host && it.keyType == type && it.keyBase64 == presented
            }
            writeEntries(entries)
        }
    }

    override fun getKnownHostsRepositoryID(): String = knownHostsFile.absolutePath

    override fun getHostKey(): Array<HostKey> = entriesAsHostKeys(knownEntries())

    override fun getHostKey(host: String, type: String?): Array<HostKey> =
        entriesAsHostKeys(
            knownEntries().filter { it.host == host && (type == null || it.keyType == type) }
        )

    /**
     * SSH 公钥是一个 `string keyType` 开头的二进制块，先读 4 字节长度再取类型名。
     */
    private fun keyTypeOf(key: ByteArray): String? {
        if (key.size < 5) {
            return null
        }
        val length = ((key[0].toInt() and 0xFF) shl 24) or
            ((key[1].toInt() and 0xFF) shl 16) or
            ((key[2].toInt() and 0xFF) shl 8) or
            (key[3].toInt() and 0xFF)
        if (length <= 0 || 4 + length > key.size) {
            return null
        }
        return String(key, 4, length, Charsets.US_ASCII)
    }

    private fun fingerprintOf(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun entriesAsHostKeys(entries: List<Entry>): Array<HostKey> {
        return entries.mapNotNull { entry ->
            runCatching {
                HostKey(entry.host, Base64.decode(entry.keyBase64, Base64.NO_WRAP))
            }.getOrElse { error ->
                Log.w(TAG, "Skipping unparsable known_hosts entry for ${entry.host}", error)
                null
            }
        }.toTypedArray()
    }

    private fun readEntries(): List<Entry> {
        if (!knownHostsFile.isFile) {
            return emptyList()
        }
        return runCatching {
            knownHostsFile.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("@") }
                .mapNotNull { line ->
                    val fields = line.split(FIELD_SEPARATOR).filter { it.isNotEmpty() }
                    if (fields.size < 3) {
                        null
                    } else {
                        Entry(host = fields[0], keyType = fields[1], keyBase64 = fields[2])
                    }
                }
                .toList()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to read known_hosts", error)
            emptyList()
        }
    }

    private fun writeEntries(entries: List<Entry>) {
        runCatching {
            knownHostsFile.parentFile?.mkdirs()
            val content = entries.joinToString(separator = "\n", postfix = "\n") { entry ->
                "${entry.host}$FIELD_SEPARATOR${entry.keyType}$FIELD_SEPARATOR${entry.keyBase64}"
            }
            knownHostsFile.writeText(content)
        }.onFailure { error ->
            Log.e(TAG, "Failed to write known_hosts", error)
        }
    }

    data class Entry(
        val host: String,
        val keyType: String,
        val keyBase64: String
    ) {
        /**
         * SHA256: 形式的指纹，与 OpenSSH 的 `ssh-keygen -lf` 输出一致，
         * 便于用户在设置页核对已知主机。
         */
        val fingerprintSha256: String
            get() = runCatching {
                val digest =
                    MessageDigest.getInstance("SHA-256")
                        .digest(Base64.decode(keyBase64, Base64.NO_WRAP))
                "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
            }.getOrElse { error ->
                Log.w(TAG, "Failed to compute fingerprint for ${host}", error)
                ""
            }
    }
}
