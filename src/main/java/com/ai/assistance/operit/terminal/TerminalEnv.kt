package com.ai.assistance.operit.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.terminal.data.PendingHostKey
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.data.TerminalTarget
import kotlinx.coroutines.launch
import android.util.Log
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

@Stable
class TerminalEnv(
    sessionsState: State<List<TerminalSessionData>>,
    currentSessionIdState: State<String?>,
    currentDirectoryState: State<String>,
    isFullscreenState: State<Boolean>,
    terminalEmulatorState: State<AnsiTerminalEmulator>,
    pendingHostKeyState: State<PendingHostKey?>,
    activeTargetState: State<TerminalTarget>,
    needsSshConfigurationState: State<Boolean>,
    private val terminalManager: TerminalManager,
    val forceShowSetup: Boolean = false
) {
    val sessions by sessionsState
    val currentSessionId by currentSessionIdState
    val currentDirectory by currentDirectoryState
    val isFullscreen by isFullscreenState
    val terminalEmulator by terminalEmulatorState

    /**
     * 等待用户确认的主机密钥；非空时由界面弹出指纹确认框。
     */
    val pendingHostKey by pendingHostKeyState

    /** 当前执行目标：远端 SSH 或本地 proot 环境 */
    val activeTarget by activeTargetState

    /** 远端目标缺少主机配置时为 true，界面据此引导用户去填写 */
    val needsSshConfiguration by needsSshConfigurationState

    var command by mutableStateOf("")

    fun onCommandChange(newCommand: String) {
        command = newCommand
    }

    fun onSendInput(inputText: String, isCommand: Boolean) {
        // 允许空输入（用于交互式场景发送回车）
        if (isCommand) {
            // 命令模式：也允许空命令（用于 SSH 等交互场景）
            terminalManager.coroutineScope.launch {
                terminalManager.sendCommand(inputText)
            }
            if (inputText == command) {
                command = ""
            }
        } else {
            // 输入模式：允许空输入（例如 ssh-keygen 直接回车使用默认路径）
            terminalManager.sendInput(inputText)
        }
    }

    fun onSetup(commands: List<String>) {
        val fullCommand = commands.joinToString(separator = " && ")
        terminalManager.coroutineScope.launch {
            terminalManager.sendCommand(fullCommand)
        }
    }

    fun onInterrupt() = terminalManager.sendInterruptSignal()
    fun onNewSession() {
        // 在terminalManager的协程作用域中异步创建会话
        terminalManager.coroutineScope.launch {
            try {
                terminalManager.createNewSession()
                Log.d("TerminalEnv", "New session created successfully")
            } catch (e: Exception) {
                Log.e("TerminalEnv", "Failed to create new session", e)
            }
        }
    }
    fun onSwitchSession(sessionId: String) = terminalManager.switchToSession(sessionId)
    fun onCloseSession(sessionId: String) = terminalManager.closeSession(sessionId)

    /** 用户核对指纹后信任该主机密钥，并重试被中断的会话 */
    fun onTrustPendingHostKey() = terminalManager.trustPendingHostKey()

    /** 用户拒绝信任，保持未连接 */
    fun onDismissPendingHostKey() = terminalManager.dismissPendingHostKey()

    /** 切换执行目标（远端 / 本地），会关闭现有会话 */
    fun onSelectTarget(target: TerminalTarget) = terminalManager.setActiveTarget(target)

    /** 收起“缺少主机配置”的引导 */
    fun onDismissSshConfigurationRequest() = terminalManager.dismissSshConfigurationRequest()
    
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) = terminalManager.saveScrollOffset(sessionId, scrollOffset)
    fun getScrollOffset(sessionId: String): Float = terminalManager.getScrollOffset(sessionId)
}

@Composable
fun rememberTerminalEnv(terminalManager: TerminalManager, forceShowSetup: Boolean = false): TerminalEnv {
    val sessionsState = terminalManager.sessions.collectAsState(initial = emptyList())
    val currentSessionIdState = terminalManager.currentSessionId.collectAsState(initial = null)
    val currentDirectoryState = terminalManager.currentDirectory.collectAsState(initial = "$ ")
    val isFullscreenState = terminalManager.isFullscreen.collectAsState(initial = false)
    val pendingHostKeyState = terminalManager.pendingHostKey.collectAsState(initial = null)
    val activeTargetState = terminalManager.activeTarget.collectAsState(initial = TerminalTarget.REMOTE)
    val needsSshConfigurationState =
        terminalManager.needsSshConfiguration.collectAsState(initial = false)
    val placeholderEmulator = remember { AnsiTerminalEmulator(screenWidth = 1, screenHeight = 1, historySize = 0) }
    val terminalEmulatorState = terminalManager.terminalEmulator.collectAsState(initial = placeholderEmulator)

    return remember(terminalManager, forceShowSetup) {
        TerminalEnv(
            sessionsState = sessionsState,
            currentSessionIdState = currentSessionIdState,
            currentDirectoryState = currentDirectoryState,
            isFullscreenState = isFullscreenState,
            terminalEmulatorState = terminalEmulatorState,
            pendingHostKeyState = pendingHostKeyState,
            activeTargetState = activeTargetState,
            needsSshConfigurationState = needsSshConfigurationState,
            terminalManager = terminalManager,
            forceShowSetup = forceShowSetup
        )
    }
} 