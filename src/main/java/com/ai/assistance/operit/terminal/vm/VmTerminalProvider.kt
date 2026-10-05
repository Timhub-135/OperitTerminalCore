package com.ai.assistance.operit.terminal.vm

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ai.assistance.operit.terminal.TerminalSession
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.provider.type.TerminalProvider
import com.ai.assistance.operit.terminal.utils.HostKeyChallenge
import com.ai.assistance.operit.terminal.utils.HostKeyStore
import com.ai.assistance.operit.terminal.utils.SSHFileConnectionManager
import com.ai.assistance.operit.terminal.utils.SecretStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地目标的 VM 实现：应用内以 qemu-system-aarch64（TCG）启动一台 Alpine 虚拟机。
 *
 * 分工：
 *
 * - 可见终端：guest 的 hvc0（终端通道），应用先自动登录再把手交给终端视图
 * - 不可见执行与文件访问：guest 自带的 dropbear（22 端口映射到宿主回环），
 *   复用既有的 SSH 连接管理器，因此退出码、超时与 SFTP 都是已经在用的实现
 * - 生命周期：VM 由本 provider 与前台服务管理，关掉一个终端会话不会停 VM
 *
 * 首次启动会做一次初始化：把镜像出厂的 root 口令换成每安装随机口令，并把 guest 的
 * dropbear 主机公钥读回来写进 known_hosts。之所以要换口令，是因为 guest 的 22 端口
 * 映射在设备回环上，其他应用也能连；镜像出厂口令是公开的，不换等于留一个后门。
 */
class VmTerminalProvider(
    private val context: Context,
    private val config: VmConfig = VmConfig()
) : TerminalProvider {

    companion object {
        private const val TAG = "VmTerminalProvider"

        /** VM 自己的 SSH 连接在连接管理器里的 id，与远端目标的连接互不干扰 */
        private const val VM_CONNECTION_ID = "vm_local"

        private const val PREFS_NAME = "vm_state"
        private const val KEY_PROVISIONED = "provisioned"
        private const val KEY_ROOT_SECRET = "root_secret"

        private const val GUEST_HOST = "127.0.0.1"
    }

    private val engine = QemuVmEngine.getInstance(context)
    private val paths = VmPaths(context)
    private val controlChannel = VmControlChannel(engine, paths)
    private val consoleShell = VmConsoleShell(engine, paths)
    private val secretStore = SecretStore()
    private val hostKeyStore = HostKeyStore(context)
    private val sshManager = SSHFileConnectionManager.getInstance(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val sessions = ConcurrentHashMap<String, VmConsoleTransport>()

    @Volatile private var sshConnectionId: String? = null

    /** known_hosts 里的主机名形式：非 22 端口要带方括号，与 JSch 的写法一致 */
    private val guestHostEntry: String
        get() = "[$GUEST_HOST]:${config.guestSshHostPort}"

    override suspend fun isConnected(): Boolean =
        engine.state.value.stage == VmBootStage.READY && sshConnectionId != null

    /**
     * 启动 VM 并完成初始化与 SSH 连接。
     *
     * 这是"本地环境"的入口：资产安装、持久盘准备、guest 启动、口令轮换、主机密钥
     * 信任、SSH 建连都在这里一次做完，失败原因原样抛出，不做降级。
     */
    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        engine.start(config).getOrElse { error ->
            return@withContext Result.failure(error)
        }

        provisionIfNeeded()?.let { error ->
            return@withContext Result.failure(error)
        }

        connectGuestSsh()
    }

    override suspend fun disconnect() {
        sessions.keys.toList().forEach { closeSession(it) }
        controlChannel.close()
        sshConnectionId?.let { id ->
            sshManager.disconnect(id)
        }
        sshConnectionId = null
        engine.stop()
    }

    override suspend fun startSession(sessionId: String): Result<TerminalSession> =
        withContext(Dispatchers.IO) {
            val secret = storedSecret()
                ?: return@withContext Result.failure(
                    IllegalStateException("VM root credential is missing; the environment was not provisioned")
                )
            engine.awaitReady().getOrElse { error ->
                return@withContext Result.failure(error)
            }
            val process = engine.processHandle()
                ?: return@withContext Result.failure(IllegalStateException("QEMU process is not running"))

            val session = consoleShell.open()
            try {
                if (!session.login(secret)) {
                    session.close()
                    return@withContext Result.failure(
                        IllegalStateException("Guest login on the console channel failed")
                    )
                }
                // 登录期间读到的横幅不再回放：终端视图从干净的 root 提示符开始
                session.drain()
                val transport =
                    VmConsoleTransport(
                        engine = engine,
                        control = controlChannel,
                        socket = session.socket,
                        qemuProcess = process,
                        pid = engine.qemuPid
                    )
                sessions[sessionId] = transport
                Log.d(TAG, "VM terminal session started on console channel: $sessionId")
                Result.success(TerminalSession(transport))
            } catch (e: Exception) {
                session.close()
                Log.e(TAG, "Failed to start VM terminal session", e)
                Result.failure(e)
            }
        }

    override suspend fun closeSession(sessionId: String) {
        sessions.remove(sessionId)?.destroy()
    }

    override suspend fun executeHiddenCommand(
        command: String,
        executorKey: String,
        timeoutMs: Long
    ): HiddenExecResult {
        val id = sshConnectionId
            ?: return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.EXECUTION_ERROR,
                error = "VM SSH connection is not established"
            )
        return sshManager
            .executeCommand(command = command, timeoutMs = timeoutMs, connectionId = id)
            .getOrElse { error ->
                Log.e(TAG, "Hidden command failed in the VM", error)
                HiddenExecResult(
                    output = "",
                    exitCode = -1,
                    state = HiddenExecResult.State.EXECUTION_ERROR,
                    error = error.message ?: "Failed to execute command in the VM"
                )
            }
    }

    override fun getFileSystemProvider(): FileSystemProvider {
        val id = sshConnectionId
            ?: throw IllegalStateException("VM SSH connection is not established")
        return sshManager.getFileSystemProvider(id)
            ?: throw IllegalStateException("VM file system provider is not available")
    }

    override suspend fun getWorkingDirectory(): String = "/root"

    override fun getEnvironment(): Map<String, String> =
        mapOf(
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )

    // ------------------------------------------------------------ 初始化与连接

    /**
     * 首次启动的初始化。
     *
     * 判定依据是应用侧的标记而不是 guest 的状态：标记为真时口令已经换过，直接信任
     * 主机密钥；标记为空时用镜像出厂口令登录一次、换口令、再信任主机密钥。
     */
    private fun provisionIfNeeded(): Exception? {
        val provisioned = prefs.getBoolean(KEY_PROVISIONED, false)
        if (!provisioned) {
            val secret = secretStore.newRandomSecret()
            val rotate =
                consoleShell.runOnce(
                    password = VmConsoleShell.BOOTSTRAP_PASSWORD,
                    command = "printf '%s\\n' 'root:$secret' | chpasswd"
                )
            if (!rotate.completed || rotate.exitCode != 0) {
                return IllegalStateException(
                    "Failed to rotate the guest root password (completed=${rotate.completed}, " +
                        "exit=${rotate.exitCode})"
                )
            }
            val encrypted = secretStore.encrypt(secret)
                ?: return IllegalStateException("Failed to store the VM root credential")
            prefs.edit()
                .putBoolean(KEY_PROVISIONED, true)
                .putString(KEY_ROOT_SECRET, encrypted)
                .apply()
            Log.i(TAG, "VM provisioned: guest root credential rotated")
        }

        val secret = storedSecret()
            ?: return IllegalStateException("VM root credential is missing after provisioning")
        return trustGuestHostKeys(secret)
    }

    /**
     * 把 guest 的 dropbear 主机公钥读回来写进 known_hosts。
     *
     * 这不是"跳过校验"：密钥是从我们自己启动的 guest 上读出来的，指纹由读到的字节
     * 现算，之后 JSch 握手时仍然按 known_hosts 严格比对，只是省掉了让用户为
     * 127.0.0.1 上一个自己刚启动的 VM 点确认这一步。
     */
    private fun trustGuestHostKeys(secret: String): Exception? {
        val result = consoleShell.runOnce(secret, "cat /etc/dropbear/*_host_key.pub")
        if (!result.completed || result.exitCode != 0 || result.output.isBlank()) {
            return IllegalStateException(
                "Failed to read the guest host keys (completed=${result.completed}, exit=${result.exitCode})"
            )
        }

        val known = hostKeyStore.knownEntries()
            .filter { it.host == guestHostEntry }
        var trusted = 0
        result.output.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val keyType = parts[0]
            if (!keyType.startsWith("ssh-") && !keyType.startsWith("ecdsa-")) return@forEach
            val keyBase64 = parts[1]
            if (known.any { it.keyType == keyType && it.keyBase64 == keyBase64 }) return@forEach
            val fingerprint = fingerprintOf(keyBase64)
            hostKeyStore.trust(
                HostKeyChallenge(
                    host = guestHostEntry,
                    keyType = keyType,
                    keyBase64 = keyBase64,
                    fingerprintSha256 = fingerprint,
                    mismatch = false
                )
            )
            trusted++
        }
        Log.i(TAG, "Guest host keys trusted: $trusted (host $guestHostEntry)")
        return null
    }

    private suspend fun connectGuestSsh(): Result<Unit> {
        val secret = storedSecret()
            ?: return Result.failure(IllegalStateException("VM root credential is missing"))

        val params =
            SSHFileConnectionManager.ConnectionParams(
                host = GUEST_HOST,
                port = config.guestSshHostPort,
                username = "root",
                password = secret,
                privateKeyPath = null,
                passphrase = null,
                enableKeepAlive = true,
                keepAliveInterval = 30,
                enablePortForwarding = false,
                localForwardPort = 0,
                remoteForwardPort = 0,
                enableReverseTunnel = false,
                remoteTunnelPort = 0,
                localSshPort = 0,
                localSshUsername = "root",
                localSshPassword = secret,
                connectionId = VM_CONNECTION_ID
            )

        return sshManager.connect(params).map { id ->
            sshConnectionId = id
            Log.d(TAG, "VM SSH connection established: $id")
        }
    }

    private fun storedSecret(): String? {
        val encrypted = prefs.getString(KEY_ROOT_SECRET, null) ?: return null
        return secretStore.decrypt(encrypted)
    }

    /** SHA256: 前缀的指纹，格式与 HostKeyStore 的展示一致 */
    private fun fingerprintOf(keyBase64: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Base64.decode(keyBase64, Base64.DEFAULT))
        val encoded = Base64.encodeToString(digest.digest(), Base64.NO_WRAP or Base64.NO_PADDING)
        return "SHA256:$encoded"
    }
}
