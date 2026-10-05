package com.ai.assistance.operit.terminal

import com.ai.assistance.operit.terminal.transport.TerminalTransport
import java.io.InputStream
import java.io.OutputStream

/**
 * 一个活动中的终端会话。
 *
 * 会话不再持有 java.lang.Process：远程 SSH 会话没有本地进程，用 Process 表达
 * 生命周期会把远程实现挡在门外。I/O、窗口尺寸与生命周期统一由 [transport] 提供，
 * [stdout]、[stdin]、[pty] 只是它的转发，保证视图、读取循环与退出处理读的是同一份状态。
 *
 * @property transport 会话的 I/O 与生命周期实现，本地为 PTY，远程为 SSH 通道
 */
data class TerminalSession(
    val transport: TerminalTransport
) {
    val stdout: InputStream get() = transport.stdout

    val stdin: OutputStream get() = transport.stdin

    val pty: Pty get() = transport.pty
}
