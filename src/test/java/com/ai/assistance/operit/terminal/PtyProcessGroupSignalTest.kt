package com.ai.assistance.operit.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyProcessGroupSignalTest {
    private val calls = mutableListOf<String>()

    private val recordingKill: (Int, Int) -> Unit = { target, signal ->
        calls.add("kill($target,$signal)")
    }
    private val recordingFallback: (Int, Int) -> Unit = { target, signal ->
        calls.add("pid($target,$signal)")
    }

    @Test
    fun signalTargetsTheChildProcessGroupOnly() {
        Pty.sendSignalToProcessGroup(4242, 1, recordingKill, recordingFallback)
        assertEquals(listOf("kill(-4242,1)"), calls)
    }

    @Test
    fun groupSignalFailureFallsBackToTheChildPid() {
        val failingKill: (Int, Int) -> Unit = { target, signal ->
            calls.add("kill($target,$signal)")
            throw IllegalStateException("ESRCH")
        }
        Pty.sendSignalToProcessGroup(4242, 9, failingKill, recordingFallback)
        assertEquals(listOf("kill(-4242,9)", "pid(4242,9)"), calls)
    }

    @Test
    fun unknownPidNeverSignalsAProcessGroup() {
        Pty.sendSignalToProcessGroup(-1, 1, recordingKill, recordingFallback)
        assertEquals(listOf("pid(-1,1)"), calls)
    }

    @Test
    fun zeroPidNeverSignalsAProcessGroup() {
        Pty.sendSignalToProcessGroup(0, 1, recordingKill, recordingFallback)
        assertEquals(listOf("pid(0,1)"), calls)
    }

    @Test
    fun failedFallbackDoesNotEscapeDestruction() {
        val failingEverything: (Int, Int) -> Unit = { _, _ ->
            throw IllegalStateException("EPERM")
        }
        Pty.sendSignalToProcessGroup(7, 9, failingEverything, failingEverything)
        // Reaching this line means no exception escaped: destruction must stay best-effort.
        assertTrue(true)
    }
}
