package com.ai.assistance.operit.terminal.vm

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** VM 的运行参数 */
data class VmConfig(
    /** vCPU 数量。TCG 下 4 个是实测甜点，更多不会更快 */
    val cpus: Int = 4,
    /** guest 内存（MB） */
    val ramMb: Int = 3072,
    /** 持久盘大小。稀疏文件，实际占用随使用增长 */
    val storageSizeBytes: Long = 4L * 1024 * 1024 * 1024,
    /** guest 的 22 端口映射到宿主回环的端口，用于应用侧执行与文件访问 */
    val guestSshHostPort: Int = 9022
)

/** 启动阶段。标记来自 guest 的串口输出，顺序与 OpenRC 的服务顺序一致 */
enum class VmBootStage {
    STOPPED,
    LAUNCHING,
    KERNEL,
    INITRAMFS,
    SERVICES,
    NETWORK,
    SSH,
    ALMOST_READY,
    READY,
    FAILED
}

data class VmState(
    val running: Boolean = false,
    val stage: VmBootStage = VmBootStage.STOPPED,
    val qemuPid: Int = -1,
    val error: String = ""
)

/**
 * QEMU 子进程的启动、串口日志读取与阶段检测。
 *
 * guest 的启动标记写在 PL011 串口上，QEMU 把它接到 `serial.sock`；我们必须始终连着
 * 这个 socket，因为 QEMU 是用 `nowait` 起的：没有客户端时输出直接丢掉。检测逻辑
 * 按 Podroid 的经验在滚动缓冲上匹配，而不是按每次 read 的块——快速设备会把
 * `Ready!` 这类标记切在两次 read 之间。
 */
class QemuVmEngine private constructor(private val context: Context) {

    companion object {
        private const val TAG = "QemuVmEngine"

        /** 串口日志的滚动检测窗口 */
        private const val MARKER_WINDOW = 8192

        /**
         * 启动失败后的冷却时间。
         *
         * 启动一个起不来的 VM 会依次做资产校验、建持久盘、拉子进程，代价不小；而
         * "打开终端""执行工具""MCP 建会话"这些入口都会去要 provider，失败时每个入口
         * 都会重试一遍。没有冷却时实测在一秒多里连拉了四次 QEMU，日志被刷满。
         * 冷却期内直接返回上次的失败原因，真实重试（用户重新操作）在冷却后照常进行。
         */
        private const val FAILURE_COOLDOWN_MS = 15_000L

        @Volatile private var instance: QemuVmEngine? = null

        fun getInstance(context: Context): QemuVmEngine =
            instance ?: synchronized(this) {
                instance ?: QemuVmEngine(context.applicationContext).also { instance = it }
            }
    }

    private val paths = VmPaths(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 同一时刻只允许一次启动尝试，避免多个入口并发拉起同一个 VM */
    private val startMutex = Mutex()

    /** 上次失败的时间与原因，用于冷却期内快速返回 */
    @Volatile private var lastFailure: Pair<Long, String>? = null

    private val _state = MutableStateFlow(VmState())
    val state: StateFlow<VmState> = _state.asStateFlow()

    private var process: Process? = null
    private var consoleJob: Job? = null
    private val stopping = AtomicBoolean(false)

    /** 最近一次运行的串口日志尾部，用于排错展示 */
    private val consoleTail = StringBuilder()

    val isRunning: Boolean
        get() = process?.isAlive == true

    val qemuPid: Int
        get() = _state.value.qemuPid

    /** 当前 QEMU 子进程句柄；会话需要它来回答"存活/退出码"。 */
    fun processHandle(): Process? = process

    /**
     * 确保资产与持久盘就位并启动 VM，等待到 [VmBootStage.READY]。
     *
     * @param timeoutMs 等待就绪的上限；TCG 下冷启动（首次要格式化持久盘）明显更慢
     */
    suspend fun start(
        config: VmConfig,
        timeoutMs: Long = 180_000L,
        onProgress: (VmAssets.Progress) -> Unit = {}
    ): Result<Unit> = startMutex.withLock {
        if (isRunning) {
            Log.d(TAG, "VM already running")
            return@withLock awaitReady(timeoutMs)
        }

        lastFailure?.let { (at, reason) ->
            val elapsed = System.currentTimeMillis() - at
            if (elapsed < FAILURE_COOLDOWN_MS) {
                Log.d(TAG, "VM start skipped, last failure was ${elapsed}ms ago")
                return@withLock Result.failure(IllegalStateException(reason))
            }
        }

        VmAssets.ensureInstalled(context, paths, onProgress).onFailure { error ->
            recordFailure(error.message ?: "assets")
            return@withLock Result.failure(error)
        }

        ensureStorage(config.storageSizeBytes).onFailure { error ->
            recordFailure(error.message ?: "storage")
            return@withLock Result.failure(error)
        }

        try {
            paths.ensureDirectories()
            paths.clearStaleSockets()
            consoleTail.setLength(0)
            paths.qemuLog.delete()

            val argv = buildCommand(config)
            Log.d(TAG, "Starting QEMU: ${argv.joinToString(" ")}")

            val builder = ProcessBuilder(argv)
            builder.directory(paths.root)
            // QEMU 通过动态库搜索路径找 libslirp：它在 nativeLibraryDir 里，
            // 名字仍是打包时的 libslirp.so
            builder.environment()["LD_LIBRARY_PATH"] = paths.nativeLibraryDir.absolutePath
            builder.redirectErrorStream(true)
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(paths.qemuLog))

            val started = builder.start()
            process = started
            stopping.set(false)
            _state.value =
                VmState(running = true, stage = VmBootStage.LAUNCHING, qemuPid = pidOf(started))

            readConsoleLog()
            monitorProcess(started)

            val ready = awaitReady(timeoutMs)
            if (ready.isSuccess) {
                lastFailure = null
            } else {
                recordFailure(ready.exceptionOrNull()?.message ?: "VM did not become ready")
            }
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            stop()
            val reason = e.message ?: "start failed"
            recordFailure(reason)
            Result.failure(e)
        }
    }

    /** 记下失败原因，供冷却期内的调用方直接拿到同一份说明 */
    private fun recordFailure(reason: String) {
        lastFailure = System.currentTimeMillis() to reason
        val current = _state.value
        if (current.stage != VmBootStage.FAILED) {
            _state.value = current.copy(running = false, stage = VmBootStage.FAILED, error = reason)
        }
    }

    /** 等待 guest 报就绪；超时或失败时返回带诊断信息的错误 */
    suspend fun awaitReady(timeoutMs: Long = 180_000L): Result<Unit> {
        val ready =
            withTimeoutOrNull(timeoutMs) {
                var last = _state.value
                while (isActive) {
                    last = _state.value
                    if (last.stage == VmBootStage.READY) return@withTimeoutOrNull true
                    if (last.stage == VmBootStage.FAILED || !isRunning) return@withTimeoutOrNull false
                    delay(200)
                }
                false
            }
        return when (ready) {
            true -> Result.success(Unit)
            false ->
                Result.failure(
                    IllegalStateException(
                        "VM failed to become ready: ${_state.value.error.ifBlank { consoleTail.takeLast(400) }}"
                    )
                )
            null ->
                Result.failure(
                    IllegalStateException(
                        "VM did not reach Ready! within ${timeoutMs / 1000}s; last stage=${_state.value.stage}"
                    )
                )
        }
    }

    /** 停止 VM。QEMU 收到退出信号会同步落盘，先 SIGTERM 再在超时后强杀 */
    fun stop() {
        stopping.set(true)
        consoleJob?.cancel()
        consoleJob = null
        val running = process
        process = null
        running?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(TAG, "QEMU did not exit in time, killing")
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop QEMU", e)
            }
        }
        _state.value = VmState(running = false, stage = VmBootStage.STOPPED)
    }

    /**
     * 连接 QEMU 暴露的通道。
     *
     * QEMU 以 `server=on` 创建 socket，客户端可以晚于它启动；这里带重试，
     * 因为 socket 文件的出现总是晚于进程启动。
     */
    fun connectChannel(file: File, timeoutMs: Long = 15_000L): LocalSocket {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(file.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                return socket
            } catch (e: Exception) {
                lastError = e
                runCatching { socket.close() }
                Thread.sleep(100)
            }
        }
        throw IllegalStateException(
            "Cannot connect ${file.name}: ${lastError?.message ?: "timeout"}",
            lastError
        )
    }

    fun consoleTailOf(maxChars: Int = 2000): String = consoleTail.takeLast(maxChars).toString()

    // ---------------------------------------------------------------- 内部实现

    private fun buildCommand(config: VmConfig): List<String> {
        val append =
            buildString {
                append("console=ttyAMA0 mitigations=off ssh=1 androidip=10.0.2.15")
                val dns = deviceDnsServers()
                if (dns.isNotEmpty()) {
                    // guest 的 podroid-network 会把这两个解析器写进 resolv.conf。
                    // 不传时 guest 会用镜像自带的 10.0.2.3 与公共解析器，而 SLIRP 的
                    // 10.0.2.3 在 Android 上读不到宿主 resolv.conf，域名解析会失败，
                    // 因此设备有解析器就必须传。
                    append(" podroid.dns=").append(dns.joinToString(","))
                }
            }
        return listOf(
            paths.qemuBinary.absolutePath,
            "-M", "virt,gic-version=3",
            "-cpu", "max",
            "-accel", "tcg,thread=multi,tb-size=512",
            "-smp", config.cpus.toString(),
            "-m", config.ramMb.toString(),
            "-kernel", paths.kernel.absolutePath,
            "-initrd", paths.initrd.absolutePath,
            "-append", append,
            "-object", "iothread,id=iothread0",
            "-device", "virtio-blk-pci,drive=drive1,num-queues=${config.cpus},iothread=iothread0",
            "-drive",
            "file=${paths.storage.absolutePath},if=none,id=drive1,format=raw,cache=writeback," +
                "aio=threads,discard=unmap,detect-zeroes=unmap",
            "-object", "iothread,id=iothread1",
            "-device", "virtio-blk-pci,drive=drive2,num-queues=${config.cpus},iothread=iothread1",
            "-drive",
            "file=${paths.rootfs.absolutePath},if=none,id=drive2,format=raw,readonly=on," +
                "cache=writeback,aio=threads",
            "-netdev",
            "user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:${config.guestSshHostPort}-:22",
            "-device", "virtio-net-pci,netdev=net0,romfile=",
            "-serial", "unix:${paths.serialSocket.absolutePath},server,nowait",
            "-device", "virtio-serial-pci",
            "-chardev", "socket,id=term0,path=${paths.terminalSocket.absolutePath},server=on,wait=off",
            "-device", "virtconsole,chardev=term0,name=org.operit.term",
            "-chardev", "socket,id=ctrl0,path=${paths.controlSocket.absolutePath},server=on,wait=off",
            "-device", "virtconsole,chardev=ctrl0,name=org.operit.ctrl",
            "-chardev", "socket,id=host0,path=${paths.hostSocket.absolutePath},server=on,wait=off",
            "-device", "virtconsole,chardev=host0,name=org.operit.host",
            "-display", "none",
            // guest 崩溃时不要静默重启：重启会掩盖崩溃，也会让上层看不到真实的退出码
            "-no-reboot"
        )
    }

    private fun ensureStorage(sizeBytes: Long): Result<Unit> {
        return try {
            paths.ensureDirectories()
            val file = paths.storage
            if (!file.exists()) {
                RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
                Log.i(TAG, "Created storage image ${file.name} (${sizeBytes / (1024 * 1024)} MB, sparse)")
            } else if (file.length() < sizeBytes) {
                RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
                Log.i(TAG, "Grew storage image to ${sizeBytes / (1024 * 1024)} MB")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare storage image", e)
            Result.failure(e)
        }
    }

    /** 读串口日志：既是启动进度来源，也是排错时唯一能看到 guest 早期输出的地方 */
    private fun readConsoleLog() {
        consoleJob =
            scope.launch {
                var socket: LocalSocket? = null
                try {
                    socket = connectChannel(paths.serialSocket, 30_000L)
                    val writer = paths.consoleLog.bufferedWriter()
                    val input = socket.inputStream
                    val buffer = ByteArray(8192)
                    while (isActive && !stopping.get()) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        val chunk = String(buffer, 0, read, Charsets.UTF_8)
                        writer.write(chunk)
                        writer.flush()
                        appendConsoleTail(chunk)
                        detectStage(chunk)
                    }
                    writer.close()
                } catch (e: Exception) {
                    if (!stopping.get()) {
                        Log.e(TAG, "Console log reader stopped", e)
                        _state.value =
                            _state.value.copy(
                                stage = VmBootStage.FAILED,
                                error = "console log reader failed: ${e.message}"
                            )
                    }
                } finally {
                    runCatching { socket?.close() }
                }
            }
    }

    private fun monitorProcess(started: Process) {
        scope.launch {
            val code = try {
                started.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            if (!stopping.get()) {
                Log.w(TAG, "QEMU exited with code $code")
                // QEMU 自己的输出比串口日志更能说明问题：动态链接失败、设备参数写错这类
                // 情况根本走不到 guest，串口日志是空的，只有 qemu.log 里有原因
                val qemuLogTail = readQemuLogTail()
                val consoleTail = consoleTail.takeLast(400)
                val detail =
                    buildString {
                        append("QEMU exited with code ").append(code)
                        if (qemuLogTail.isNotBlank()) append("; qemu.log: ").append(qemuLogTail)
                        if (consoleTail.isNotBlank()) append("; console: ").append(consoleTail)
                    }
                _state.value =
                    VmState(
                        running = false,
                        stage = VmBootStage.FAILED,
                        error = detail
                    )
                recordFailure(detail)
                process = null
                consoleJob?.cancel()
                consoleJob = null
            }
        }
    }

    /** 取 QEMU 自身输出的尾部，作为启动失败的诊断信息 */
    private fun readQemuLogTail(maxChars: Int = 600): String {
        return try {
            if (!paths.qemuLog.isFile) return ""
            val text = paths.qemuLog.readText()
            val trimmed = text.trim()
            if (trimmed.length <= maxChars) trimmed else trimmed.takeLast(maxChars)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read qemu.log", e)
            ""
        }
    }

    private fun appendConsoleTail(chunk: String) {
        synchronized(consoleTail) {
            consoleTail.append(chunk)
            if (consoleTail.length > 16_384) {
                consoleTail.delete(0, consoleTail.length - MARKER_WINDOW)
            }
        }
    }

    /**
     * 在滚动缓冲上匹配阶段标记。
     *
     * 顺序即 OpenRC 的服务顺序，因此阶段只前进不回退：串口输出里可能出现重复的
     * 服务行，回退会让进度条抖动。
     */
    private fun detectStage(chunk: String) {
        val current = _state.value
        if (current.stage == VmBootStage.READY || current.stage == VmBootStage.FAILED) return

        val window =
            synchronized(consoleTail) {
                if (consoleTail.length <= MARKER_WINDOW) consoleTail.toString()
                else consoleTail.substring(consoleTail.length - MARKER_WINDOW)
            }

        val next =
            when {
                window.contains("Attempted to kill init") || window.contains("FATAL:") -> VmBootStage.FAILED
                window.contains("Ready!") -> VmBootStage.READY
                window.contains("Almost ready...") -> VmBootStage.ALMOST_READY
                window.contains("Starting SSH...") -> VmBootStage.SSH
                window.contains("Network found") -> VmBootStage.NETWORK
                window.contains("Caching service dependencies") -> VmBootStage.SERVICES
                window.contains("[podroid-init]") -> VmBootStage.INITRAMFS
                window.contains("Linux version") -> VmBootStage.KERNEL
                else -> current.stage
            }

        if (next == VmBootStage.FAILED) {
            _state.value =
                current.copy(
                    stage = VmBootStage.FAILED,
                    error = "guest reported a fatal error: ${consoleTail.takeLast(400)}"
                )
            return
        }
        if (next != current.stage) {
            Log.d(TAG, "VM boot stage: ${current.stage} -> $next")
            _state.value = current.copy(stage = next)
        }
    }

    private fun deviceDnsServers(): List<String> {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = manager?.activeNetwork ?: return emptyList()
            manager.getLinkProperties(network)
                ?.dnsServers
                ?.mapNotNull { it.hostAddress }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read device DNS servers", e)
            emptyList()
        }
    }

    private fun pidOf(process: Process): Int {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (e: Exception) {
            -1
        }
    }
}
