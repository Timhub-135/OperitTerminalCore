package com.ai.assistance.operit.terminal.transport

import android.util.Log
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * SSH shell 通道会话的传输实现。
 *
 * 与本地实现的关键差别：没有本地进程、没有 pid、退出码来自通道的 exit-status。
 * 会话 I/O 直接对接通道流，因此不再需要本地 proot、Ubuntu 内的 ssh 客户端与 sshpass。
 *
 * 流对象由调用方在建通道时取好传入：JSch 要求在 connect() 之前获取，
 * 连接后再取只会得到告警并留下不可用的数据通路。
 *
 * @param channel 已连接并申请了 PTY 的 shell 通道
 * @param stdout 通道输出流
 * @param stdin 通道输入流
 * @param pty 由该通道驱动的 Pty
 */
class SshChannelTransport(
    private val channel: ChannelShell,
    override val stdout: InputStream,
    override val stdin: OutputStream,
    override val pty: SshChannelPty
) : TerminalTransport {

    companion object {
        private const val TAG = "SshChannelTransport"

        /** 通道关闭后的退出状态轮询间隔与上限，避免会话结束时无限等待 */
        private const val EXIT_POLL_INTERVAL_MS = 20L
        private const val EXIT_WAIT_TIMEOUT_MS = 10_000L
    }

    override val pid: Int? get() = null

    override fun isAlive(): Boolean = channel.isConnected && !channel.isClosed

    override fun destroy() {
        channel.disconnect()
    }

    override suspend fun awaitExit(): Int? =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + EXIT_WAIT_TIMEOUT_MS
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(EXIT_POLL_INTERVAL_MS)
            }
            val status = channel.exitStatus
            if (status < 0) {
                Log.w(TAG, "SSH channel closed without an exit status")
                null
            } else {
                status
            }
        }
}
