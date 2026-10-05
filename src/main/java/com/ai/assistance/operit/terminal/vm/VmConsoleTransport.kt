package com.ai.assistance.operit.terminal.vm

import android.net.LocalSocket
import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.PtyMode
import com.ai.assistance.operit.terminal.transport.TerminalTransport
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 尺寸通道（hvc1）。
 *
 * 契约与参照实现一致：一行 `RESIZE rows cols`，guest 侧的守护进程对 hvc0 执行 stty
 * 并写 `/run/term_size`，下一次登录时 `podroid-login` 会把它再应用一遍。这是本方案里
 * 与 SSH 的 window-change 等价的东西，写反行列会让全屏程序立刻错位。
 */
class VmControlChannel(private val engine: QemuVmEngine, private val paths: VmPaths) {

    companion object {
        private const val TAG = "VmControlChannel"
    }

    private val lock = Any()
    private var socket: LocalSocket? = null

    fun sendResize(rows: Int, cols: Int): Boolean {
        if (rows <= 0 || cols <= 0) return false
        return try {
            synchronized(lock) {
                val channel = ensureSocket() ?: return false
                channel.outputStream.write("RESIZE $rows $cols\n".toByteArray(Charsets.UTF_8))
                channel.outputStream.flush()
            }
            Log.d(TAG, "Requested VM resize to ${cols}x${rows}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send resize to the VM", e)
            synchronized(lock) {
                runCatching { socket?.close() }
                socket = null
            }
            false
        }
    }

    fun close() {
        synchronized(lock) {
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun ensureSocket(): LocalSocket? {
        socket?.let { existing ->
            if (existing.isConnected) return existing
            runCatching { existing.close() }
            socket = null
        }
        val created = engine.connectChannel(paths.controlSocket)
        socket = created
        return created
    }
}

/**
 * 由 virtio-console 通道驱动的 Pty。
 *
 * 与 SSH 通道同样的理由：终端视图要通过 Pty 下发窗口尺寸并检测输入模式，
 * 留空实现会让 resize 静默失效、让交互式提示判定把每行输出都当成等待输入。
 */
class VmConsolePty(
    process: Process,
    stdout: InputStream,
    stdin: OutputStream,
    private val control: VmControlChannel,
    private val availableBytes: () -> Int,
    pid: Int
) : Pty(
    process = process,
    masterFd = null,
    ptyMaster = -1,
    stdout = stdout,
    stdin = stdin,
    pid = pid
) {

    override fun setWindowSize(rows: Int, cols: Int): Boolean = control.sendResize(rows, cols)

    override fun getPtyMode(): PtyMode {
        val pending = try {
            availableBytes()
        } catch (e: Exception) {
            0
        }
        return PtyMode(
            isCanonicalMode = true,
            isEchoEnabled = true,
            isSignalEnabled = true,
            isExtendedEnabled = true,
            availableBytes = pending
        )
    }
}

/**
 * 会话的传输实现：I/O 走终端通道，生命周期由 VM 决定。
 *
 * 关闭会话只关闭这条通道，不停 VM——VM 的生死由 provider 与前台服务管理，
 * 把 `Pty.destroy()` 直接接到 QEMU 进程上会让"关掉一个终端标签"顺手杀掉整台机器。
 */
class VmConsoleTransport(
    private val engine: QemuVmEngine,
    private val control: VmControlChannel,
    private val socket: LocalSocket,
    qemuProcess: Process,
    pid: Int
) : TerminalTransport {

    companion object {
        private const val TAG = "VmConsoleTransport"
    }

    private val shim = VmProcessShim(qemuProcess)

    override val stdout: InputStream = socket.inputStream

    override val stdin: OutputStream = socket.outputStream

    override val pty: Pty =
        VmConsolePty(
            process = shim,
            stdout = stdout,
            stdin = stdin,
            control = control,
            availableBytes = {
                try {
                    socket.inputStream.available()
                } catch (e: Exception) {
                    0
                }
            },
            pid = pid
        )

    override val pid: Int = pid

    override fun isAlive(): Boolean = socket.isConnected && engine.isRunning

    override fun destroy() {
        runCatching { socket.close() }
        control.close()
        Log.d(TAG, "Closed VM console session")
    }

    /**
     * guest 的交互式 shell 没有"退出码"这个概念；只有 VM 整体退出时才有 QEMU 的退出码。
     * 返回 null 表示不可得，由调用方按"无退出码"呈现。
     */
    override suspend fun awaitExit(): Int? = withContext(Dispatchers.IO) {
        val process = shim.delegate
        try {
            if (process.isAlive) process.waitFor() else process.exitValue()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 只透传存活与等待、但拒绝被销毁的进程外壳。
 *
 * `Pty.destroy()` 在关闭会话时会被调用；这里必须拦住它，否则 QEMU 会被连带杀掉。
 */
private class VmProcessShim(val delegate: Process) : Process() {
    override fun getOutputStream(): OutputStream = delegate.outputStream
    override fun getInputStream(): InputStream = delegate.inputStream
    override fun getErrorStream(): InputStream = delegate.errorStream
    override fun waitFor(): Int = delegate.waitFor()
    override fun exitValue(): Int = delegate.exitValue()
    override fun isAlive(): Boolean = delegate.isAlive
    override fun destroy() {
        // 故意留空：VM 生命周期由 provider 负责
    }
    override fun destroyForcibly(): Process = this
}
