package com.ai.assistance.operit.terminal.transport

import android.util.Log
import com.ai.assistance.operit.terminal.Pty
import com.ai.assistance.operit.terminal.PtyMode
import com.jcraft.jsch.ChannelShell
import java.io.InputStream
import java.io.OutputStream

/**
 * 由 SSH 通道驱动的 Pty。
 *
 * 终端视图通过 Pty 做两件事：下发窗口尺寸、检测输入模式。远程会话必须在这里
 * 给出真实实现，否则：
 *
 * - resize 会静默失效（基类在 ptyMaster <= 0 时直接返回 false），vim、top 会按
 *   错误尺寸绘制并错位
 * - getPtyMode 的 availableBytes 恒为 0，OutputProcessor 的交互式提示判定会把
 *   命令执行期间的每一行输出都当成“等待输入”
 *
 * @param channel 已连接的 shell 通道
 * @param stdout 通道输出流，必须在 connect() 之前取得
 * @param stdin 通道输入流，必须在 connect() 之前取得
 * @param availableBytes 当前可读字节数，取自通道输出流的 available()
 */
class SshChannelPty(
    private val channel: ChannelShell,
    stdout: InputStream,
    stdin: OutputStream,
    private val availableBytes: () -> Int
) : Pty(
    process = SshChannelProcess(channel),
    masterFd = null,
    ptyMaster = -1,
    stdout = stdout,
    stdin = stdin,
    pid = -1
) {

    companion object {
        private const val TAG = "SshChannelPty"
    }

    /**
     * 发送 window-change 请求。
     *
     * JSch 的参数顺序是 (列, 行)，与 Pty 的 (行, 列) 相反，
     * 写反会让远端把行列对调，全屏程序立刻错位。
     */
    override fun setWindowSize(rows: Int, cols: Int): Boolean {
        return try {
            channel.setPtySize(cols, rows, 0, 0)
            Log.d(TAG, "Requested window-change to ${cols}x${rows}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request window-change to ${cols}x${rows}", e)
            false
        }
    }

    /**
     * 远程无法用 ioctl 读取终端模式，模式信息由通道缓冲推导：
     * 规范模式视为开启，是否有待读数据由可读字节数给出。
     */
    override fun getPtyMode(): PtyMode {
        val pending = try {
            availableBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read available bytes from SSH channel", e)
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
