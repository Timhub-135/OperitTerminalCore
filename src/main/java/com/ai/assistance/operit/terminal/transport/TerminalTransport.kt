package com.ai.assistance.operit.terminal.transport

import com.ai.assistance.operit.terminal.Pty
import java.io.InputStream
import java.io.OutputStream

/**
 * 终端会话的 I/O 与生命周期抽象。
 *
 * 终端核心（读取循环、会话表、退出处理）只依赖本接口，因此本地 PTY 与远程 SSH
 * 通道可以互换。远程通道没有本地进程对象，无法再用 java.lang.Process 表达
 * “是否存活 / 等待退出 / 销毁”，这三件事必须由传输自己回答；继续用 Process
 * 表示会话会让远程实现只能靠伪造进程对象混进来。
 *
 * [pty] 放在传输上是刻意的：终端视图通过 Pty 下发窗口尺寸并检测输入模式
 * （CanvasTerminalView 的 setWindowSize / getPtyMode），远程会话拿不到对象时
 * resize 会静默失效，vim、top 这类全屏程序会按错误的尺寸绘制。
 */
interface TerminalTransport {

    /** 会话输出流，终端视图的读取循环从这里取数据 */
    val stdout: InputStream

    /** 会话输入流，终端视图与命令队列往这里写数据 */
    val stdin: OutputStream

    /** 会话对应的 Pty 视图对象，负责窗口尺寸与终端模式 */
    val pty: Pty

    /** 本地会话返回子进程 pid；远程会话没有本地进程，返回 null */
    val pid: Int?

    /** 会话是否仍然存活 */
    fun isAlive(): Boolean

    /** 结束会话并回收资源 */
    fun destroy()

    /**
     * 等待会话结束并返回退出码。
     *
     * 远程实现返回通道的退出状态；无法取得退出码时返回 null，由调用方给出默认呈现。
     */
    suspend fun awaitExit(): Int?
}
