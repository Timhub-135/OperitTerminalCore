package com.ai.assistance.operit.terminal.vm

import android.net.LocalSocket
import android.util.Log
import java.io.Closeable
import java.util.UUID

/**
 * 在 guest 的终端通道（hvc0）上登录并执行一次性命令。
 *
 * 两个用途：
 *
 * 1. 首次启动的初始化：把镜像自带的 root 口令换成每安装随机口令、把 dropbear 的
 *    主机公钥读回来存进 known_hosts
 * 2. 打开可见终端会话前的自动登录：VM 由应用自己托管，用户不该再手输一遍口令，
 *    与今天 proot 本地环境直接给出 root shell 的体验保持一致
 *
 * 之所以走终端通道而不是 SSH：SSH 需要先有口令与主机密钥，而这两样恰恰是要在这里
 * 建立的东西，先有鸡还是先有蛋。
 */
class VmConsoleShell(private val engine: QemuVmEngine, private val paths: VmPaths) {

    companion object {
        private const val TAG = "VmConsoleShell"

        /** 镜像出厂时的 root 口令，仅在首次初始化时使用一次 */
        const val BOOTSTRAP_PASSWORD = "podroid"

        private const val MARKER_PREFIX = "__OPERIT_VM_MARKER__"
    }

    /** 一个已连接的终端通道 */
    inner class Session(val socket: LocalSocket) : Closeable {

        private val input = socket.inputStream
        private val output = socket.outputStream

        /** 读一段输出，直到出现 pattern、超时或通道结束 */
        fun readUntil(pattern: Regex, timeoutMs: Long): String {
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = StringBuilder()
            val chunk = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val read = try {
                    input.read(chunk)
                } catch (e: Exception) {
                    -1
                }
                if (read < 0) break
                if (read > 0) {
                    buffer.append(String(chunk, 0, read, Charsets.UTF_8))
                    val text = buffer.toString()
                    if (pattern.containsMatchIn(text)) return text
                }
            }
            return buffer.toString()
        }

        fun write(line: String) {
            output.write((line + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        /** 读掉当前缓冲区里已有的内容（登录横幅、提示符），避免污染后续解析 */
        fun drain() {
            try {
                while (input.available() > 0) {
                    if (input.read(ByteArray(4096)) <= 0) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to drain console buffer", e)
            }
        }

        /**
         * 登录。先用 getty 的用户名提示，再用 [password] 回答口令提示。
         *
         * @return 登录成功（看到 shell 提示符）返回 true
         */
        fun login(password: String, timeoutMs: Long = 20_000L): Boolean {
            var text = readUntil(Regex("login:|[#$] ?$"), timeoutMs)
            if (!text.contains("login:") && !text.contains(Regex("[#$] ?$"))) {
                // getty 可能还没打印提示符，敲一个回车把它催出来
                write("")
                text += readUntil(Regex("login:|[#$] ?$"), timeoutMs)
            }
            if (Regex("[#$] ?$").containsMatchIn(text) && !text.contains("login:")) {
                return true
            }
            if (!text.contains("login:")) return false

            write("root")
            val passwordPrompt = readUntil(Regex("Password:"), timeoutMs)
            if (!passwordPrompt.contains("Password:")) return false

            write(password)
            val afterPassword = readUntil(Regex("[#$] ?$"), timeoutMs)
            val ok = !afterPassword.contains("Login incorrect") && Regex("[#$] ?$").containsMatchIn(afterPassword)
            if (!ok) Log.w(TAG, "Console login failed")
            return ok
        }

        /**
         * 执行一条命令并取回输出与退出码。
         *
         * 用成对标记包住命令，标记里带随机串，避免和命令自己的输出撞车；退出码由结束
         * 标记带回来，与不可见执行的思路一致。
         *
         * 解析必须按**整行**匹配：交互式 tty 会把我们写进去的命令原样回显，回显里就含有
         * 标记文本。按子串找第一次出现会命中回显那一行，把解析出来的输出污染成命令自身的
         * 文本（这也是参照实现上用 python 验证时看到的现象）。
         */
        fun run(command: String, timeoutMs: Long = 20_000L): VmCommandResult {
            val marker = MARKER_PREFIX + UUID.randomUUID().toString().replace("-", "")
            drain()
            write("printf '\\n%s\\n' '$marker'; $command; printf '%s:%s\\n' '$marker' \"\$?\"")
            val text = readUntil(Regex("$marker:\\d+"), timeoutMs)

            val exitMatch = Regex("(?m)^$marker:(\\d+)\\s*$").find(text)
                ?: return VmCommandResult(output = text, exitCode = -1, completed = false)
            val exitCode = exitMatch.groupValues[1].toIntOrNull() ?: -1

            val startMatch = Regex("(?m)^$marker\\s*$").find(text)
            if (startMatch == null || startMatch.range.first > exitMatch.range.first) {
                return VmCommandResult(output = text, exitCode = exitCode, completed = false)
            }
            val body = text.substring(startMatch.range.last + 1, exitMatch.range.first)
            return VmCommandResult(output = body.trim('\r', '\n'), exitCode = exitCode, completed = true)
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    data class VmCommandResult(val output: String, val exitCode: Int, val completed: Boolean)

    /**
     * 打开一条终端通道。QEMU 的 socket 总是晚于进程出现，因此这里带重试。
     */
    fun open(timeoutMs: Long = 30_000L): Session {
        val socket = engine.connectChannel(paths.terminalSocket, timeoutMs)
        socket.soTimeout = 500
        return Session(socket)
    }

    /**
     * 复用一条已登录的通道做一次性命令，用完即关。
     *
     * 阻塞实现：调用方必须已经在后台线程上（`connect()` 之类的入口都在
     * `Dispatchers.IO` 上），否则会把登录与读串口的等待带到主线程。
     */
    fun runOnce(password: String, command: String, timeoutMs: Long = 20_000L): VmCommandResult {
        open().use { session ->
            if (!session.login(password, timeoutMs)) {
                return VmCommandResult(output = "", exitCode = -1, completed = false)
            }
            return session.run(command, timeoutMs)
        }
    }
}
