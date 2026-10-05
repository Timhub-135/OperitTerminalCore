package com.ai.assistance.operit.terminal.vm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * VM 资产的清单与安装。
 *
 * 本地目标不再解压 proot 的 rootfs，而是把 guest 侧的三件套落到应用私有目录：
 * 内核、initramfs、只读系统层（squashfs）。
 *
 * QEMU 与 slirp 不在这里：它们以 `jniLibs` 形式随包分发，安装时由系统提取到
 * `nativeLibraryDir`，从那里执行是 Android 官方支持的路径（应用私有目录里的可执行
 * 文件在部分版本上会被 SELinux 拦下）。本工程的 app 模块已经开启
 * `useLegacyPackaging`，所以这条路径可用。
 *
 * 完整性：首次安装后按 SHA-256 校验一次，之后只比对大小（274 MB 每次启动都做哈希
 * 会白白花掉几秒）。校验失败直接报错，不做"换一个文件继续"的处理：资产坏了就是坏了。
 */
object VmAssets {

    private const val TAG = "VmAssets"

    /** APK assets 内的目录前缀 */
    private const val ASSET_DIR = "vm"

    private const val BUFFER_SIZE = 1 shl 20

    /**
     * 一套资产的定义。
     *
     * @param assetName APK assets 内的文件名
     * @param fileName 落地后的文件名
     * @param sha256 期望的 SHA-256（十六进制小写）
     * @param sizeBytes 期望大小
     * @param executable 是否需要可执行权限
     * @param subDir 落地后的子目录，null 表示放在 VM 根目录
     */
    data class Spec(
        val assetName: String,
        val fileName: String,
        val sha256: String,
        val sizeBytes: Long,
        val executable: Boolean = false,
        val subDir: String? = null
    )

    /**
     * guest 侧三件套，与 Podroid v1.2.9 release 制品一致。哈希是抽取时核对过的，
     * 换版本必须同时换哈希与 [VmPaths.ASSET_VERSION]。
     */
    val SPECS: List<Spec> =
        listOf(
            Spec(
                assetName = "vmlinuz-virt",
                fileName = "vmlinuz-virt",
                sha256 = "6c4a6b1fff352b618cd661c8938803e5a4e16a124efbe7c0450199c2ef42eea6",
                sizeBytes = 20_929_723L
            ),
            Spec(
                assetName = "initrd.img",
                fileName = "initrd.img",
                sha256 = "57ae98b7aa54271da846e8c57d9c31f5474b452e77561a69263309931ce403dc",
                sizeBytes = 42_624_931L
            ),
            Spec(
                assetName = "alpine-rootfs.squashfs",
                fileName = "alpine-rootfs.squashfs",
                sha256 = "04b7dfdaebeb1dccce6824a22c8cceaa1483aef6a7643bb9ccd96feee3618121",
                sizeBytes = 223_838_208L
            )
        )

    /** 安装进度：用于首启的进度与文案 */
    data class Progress(val assetName: String, val index: Int, val total: Int, val fraction: Float)

    /**
     * 确保资产已就位。已经安装过且大小一致的资产会被跳过；
     * 首次安装或大小不符时重新从 APK 抽取并做哈希校验。
     *
     * @param onProgress 逐个资产的进度回调，可以是耗时操作，调用方自行切线程
     * @return 失败时返回异常信息（缺资产、校验不符、写入失败）
     */
    suspend fun ensureInstalled(
        context: Context,
        paths: VmPaths,
        onProgress: (Progress) -> Unit = {}
    ): Result<Unit> {
        val marker = paths.installedMarker
        val installedVersion = if (marker.isFile) marker.readText().trim() else ""
        val upToDate = installedVersion == VmPaths.ASSET_VERSION && SPECS.all { spec ->
            val target = paths.fileFor(spec)
            target.isFile && target.length() == spec.sizeBytes
        }
        if (upToDate) {
            Log.d(TAG, "VM assets already installed (${VmPaths.ASSET_VERSION})")
            return Result.success(Unit)
        }

        paths.ensureDirectories()
        SPECS.forEachIndexed { index, spec ->
            val target = paths.fileFor(spec)
            if (target.isFile && target.length() == spec.sizeBytes) {
                // 大小一致仍然校验一次哈希：这一步只有在版本变更或文件被动过时才会走到，
                // 成本只有一次读取，换来的是"装好的资产一定是清单里那一份"。
                if (hashOf(target) == spec.sha256) {
                    applyMode(target, spec)
                    onProgress(Progress(spec.assetName, index, SPECS.size, 1f))
                    return@forEachIndexed
                }
                Log.w(TAG, "Hash mismatch for ${spec.fileName}, re-extracting")
            }
            extract(context, spec, target)?.let { error ->
                return Result.failure(IllegalStateException(error))
            }
            applyMode(target, spec)
            onProgress(Progress(spec.assetName, index, SPECS.size, 1f))
        }

        marker.writeText(VmPaths.ASSET_VERSION)
        Log.i(TAG, "VM assets installed: ${VmPaths.ASSET_VERSION}")
        return Result.success(Unit)
    }

    /** 从 APK assets 抽取单个文件并校验哈希，失败返回错误信息 */
    private fun extract(context: Context, spec: Spec, target: File): String? {
        val assetPath = "$ASSET_DIR/${spec.assetName}"
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        return try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                    }
                    output.fd.sync()
                    if (written != spec.sizeBytes) {
                        return "Asset $assetPath has $written bytes, expected ${spec.sizeBytes}"
                    }
                }
            }
            val actual = hashOf(temp)
            if (actual != spec.sha256) {
                temp.delete()
                return "Asset $assetPath hash mismatch: $actual"
            }
            if (target.exists() && !target.delete()) {
                temp.delete()
                return "Cannot replace ${target.absolutePath}"
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return "Cannot move ${temp.absolutePath} to ${target.absolutePath}"
            }
            null
        } catch (e: Exception) {
            temp.delete()
            Log.e(TAG, "Failed to extract $assetPath", e)
            e.message ?: "Failed to extract $assetPath"
        }
    }

    private fun applyMode(file: File, spec: Spec) {
        if (spec.executable) {
            if (!file.setExecutable(true, true)) {
                Log.w(TAG, "Failed to mark ${file.name} executable")
            }
        } else {
            file.setReadable(true, true)
        }
    }

    private fun hashOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
