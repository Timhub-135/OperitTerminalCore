package com.ai.assistance.operit.terminal.data

import com.ai.assistance.operit.terminal.utils.HostKeyChallenge

/**
 * 等待用户确认的主机密钥。
 *
 * 会话启动时主机密钥校验失败会产生该状态：UI 展示主机、密钥类型与 SHA-256 指纹，
 * 用户确认后才写入 known_hosts 并重试 [sessionId] 对应的会话。
 *
 * @property sessionId 触发该挑战的会话，确认后按此重试
 * @property challenge 被拒绝的主机密钥及其指纹
 */
data class PendingHostKey(
    val sessionId: String,
    val challenge: HostKeyChallenge
) {
    val isMismatch: Boolean get() = challenge.mismatch
}
