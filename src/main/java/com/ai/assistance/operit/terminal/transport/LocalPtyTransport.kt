package com.ai.assistance.operit.terminal.transport

import com.ai.assistance.operit.terminal.Pty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * 本地 PTY 会话的传输实现。
 *
 * 生命周期仍由 [Pty] 承担：Pty.start 经 libpty 创建子进程并持有 master fd，
 * destroy 通过 SIGHUP/SIGKILL 结束整个进程组。这里只做转发，不复制状态，
 * 以免出现“传输认为存活、PTY 认为已退出”这类两份判断互相打架的情况。
 */
class LocalPtyTransport(private val session: Pty) : TerminalTransport {

    override val stdout: InputStream get() = session.stdout

    override val stdin: OutputStream get() = session.stdin

    override val pty: Pty get() = session

    override val pid: Int? get() = session.pid.takeIf { it > 0 }

    override fun isAlive(): Boolean = session.process.isAlive

    override fun destroy() = session.destroy()

    override suspend fun awaitExit(): Int? =
        withContext(Dispatchers.IO) {
            // waitFor 走的是阻塞的 waitpid，放到 IO 调度器上，避免占用调用方线程。
            runCatching { session.waitFor() }.getOrNull()
        }
}
