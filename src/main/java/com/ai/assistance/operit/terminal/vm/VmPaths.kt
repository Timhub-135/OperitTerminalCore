package com.ai.assistance.operit.terminal.vm

import android.content.Context
import com.ai.assistance.operit.terminal.vm.VmAssets.Spec
import java.io.File

/**
 * VM 的目录布局与文件位置。
 *
 * 全部落在 `filesDir/vm` 下：内核、initramfs、只读系统层、可执行文件、持久盘与
 * 四条 socket 通道都在这里。放在同一个目录下便于排错，也和参照实现（PC 上的
 * `tmp/podroid/rig`）保持一一对应，出问题时可以直接对照。
 */
class VmPaths(context: Context) {

    companion object {
        /**
         * 资产清单版本。换了任何一件资产的哈希或大小都要同时改这里，
         * 否则已安装的用户不会重新抽取。
         */
        const val ASSET_VERSION = "podroid-v1.2.9"

        // QEMU 通道名。guest 侧固定把三条 virtio-console 认作 hvc0/hvc1/hvc2，
        // 顺序不能改，否则终端、尺寸、宿主桥会互相串线。
        const val CHANNEL_TERMINAL = "terminal.sock"
        const val CHANNEL_CONTROL = "ctrl.sock"
        const val CHANNEL_HOST = "host.sock"
        const val CHANNEL_SERIAL = "serial.sock"

        const val STORAGE_IMAGE = "storage.img"
        const val CONSOLE_LOG = "console.log"
        const val QEMU_LOG = "qemu.log"
    }

    val root: File = File(context.filesDir, "vm")

    /**
     * QEMU 与 slirp 来自 `nativeLibraryDir`：那里是系统提取原生库的位置，
     * 也是唯一在所有受支持版本上都能执行的地方。名字仍是打包时的 `lib*.so`。
     */
    val nativeLibraryDir: File = File(context.applicationInfo.nativeLibraryDir)
    val qemuBinary: File = File(nativeLibraryDir, "libqemu-system-aarch64.so")
    val slirpLibrary: File = File(nativeLibraryDir, "libslirp.so")

    val kernel: File = File(root, "vmlinuz-virt")
    val initrd: File = File(root, "initrd.img")
    val rootfs: File = File(root, "alpine-rootfs.squashfs")

    val storage: File = File(root, STORAGE_IMAGE)
    val consoleLog: File = File(root, CONSOLE_LOG)
    val qemuLog: File = File(root, QEMU_LOG)

    /** 安装完成标记，内容是 [ASSET_VERSION] */
    val installedMarker: File = File(root, ".installed")

    val terminalSocket: File = File(root, CHANNEL_TERMINAL)
    val controlSocket: File = File(root, CHANNEL_CONTROL)
    val hostSocket: File = File(root, CHANNEL_HOST)
    val serialSocket: File = File(root, CHANNEL_SERIAL)

    fun fileFor(spec: Spec): File = File(root, spec.fileName)

    fun ensureDirectories() {
        root.mkdirs()
    }

    /**
     * 清理上一次运行留下的 socket。
     *
     * QEMU 以 `server=on` 创建这些文件，残留的文件会让新进程无法绑定同名地址，
     * 表现为"进程起来了但连不上终端"。
     */
    fun clearStaleSockets() {
        listOf(terminalSocket, controlSocket, hostSocket, serialSocket).forEach { socket ->
            if (socket.exists() && !socket.delete()) {
                throw IllegalStateException("Cannot remove stale socket ${socket.absolutePath}")
            }
        }
    }
}
