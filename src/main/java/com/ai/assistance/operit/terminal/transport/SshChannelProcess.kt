package com.ai.assistance.operit.terminal.transport

import com.jcraft.jsch.ChannelShell
import java.io.InputStream
import java.io.OutputStream

/**
 * 把 SSH shell 通道包装成终端核心认得的进程对象。
 *
 * [com.ai.assistance.operit.terminal.Pty] 的构造与生命周期接口都建立在
 * java.lang.Process 上，本地会话用的是 libpty 造的替身进程。远程会话没有本地进程，
 * 这里给出同样的替身：存活与退出一律问通道，而不是伪造一个 pid —— 伪造 pid 会让
 * 性能监控把远端会话画进本地进程树。
 */
class SshChannelProcess(private val channel: ChannelShell) : Process() {

    override fun destroy() {
        channel.disconnect()
    }

    override fun exitValue(): Int {
        if (!channel.isClosed) {
            throw IllegalThreadStateException("SSH channel is still open")
        }
        return channel.exitStatus
    }

    override fun getErrorStream(): InputStream? = null

    override fun getInputStream(): InputStream? = null

    override fun getOutputStream(): OutputStream? = null

    override fun waitFor(): Int {
        while (!channel.isClosed) {
            Thread.sleep(20)
        }
        return channel.exitStatus
    }
}
