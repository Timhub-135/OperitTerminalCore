package com.ai.assistance.operit.terminal.data

/**
 * 终端会话的执行目标。
 *
 * [REMOTE] 是默认目标：会话建立在 SSH shell 通道上，不需要本地 proot、rootfs 与
 * Ubuntu 内的 ssh 客户端；未配置主机时界面引导去填写主机信息，而不是静默退回本地。
 *
 * [LOCAL] 是显式选择的本地方案，行为与历史版本一致；老安装若已经装有本地环境，
 * 首次升级后仍保持本地目标，不会被静默切走。
 */
enum class TerminalTarget {
    REMOTE,
    LOCAL
}

/**
 * 远端目标缺少主机配置。
 *
 * 会话启动时抛出，由界面引导用户填写主机信息；连接层不会退回本地方案，
 * 因为静默切换执行位置比直接失败更难发现。
 */
class SshTargetNotConfiguredException :
    Exception("Remote terminal target requires an enabled SSH host configuration")
