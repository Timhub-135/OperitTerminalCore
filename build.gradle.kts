import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ai.assistance.operit.terminal"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = true
        compose = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.ui.graphics.android)
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.text.android)
    implementation(libs.androidx.animation.android)
    implementation(libs.androidx.ui.android)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization)
    
    // SSH 依赖：使用社区维护的 JSch 分支，包名仍是 com.jcraft.jsch。
    // 0.1.55 不支持 ed25519、rsa-sha2-256/512、curve25519，对 OpenSSH 8.8+
    // 默认关闭 ssh-rsa 的服务器直接握手失败。
    implementation("com.github.mwiede:jsch:2.27.7")
    
    // FTP服务器依赖
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.ftpserver:ftplet-api:1.2.0")
    
    // SSHD服务器依赖
    implementation("org.apache.sshd:sshd-core:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.apache.sshd:sshd-sftp:2.10.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    // BouncyCastle for SSHD on Android (avoids JMX issues)
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")
}

// ---------------------------------------------------------------------------
// VM 资产装配
//
// 本地目标现在是一台应用内启动的 Alpine 虚拟机，需要五件来自 Podroid release 制品的
// 输入：内核、initramfs、只读系统层，以及可在设备上执行的 QEMU 与它的用户态网络库。
//
// 这些文件不进 git：squashfs 单个就有 213 MB，超过 GitHub 的单文件上限，仓库也不该
// 长期背着 300 MB 二进制。改为构建期从 Podroid 的 APK 里抽取，抽取时逐个校验 SHA-256，
// 校验不过就让构建失败——宁可构建失败，也不能把半截资产打进发布包。
//
// 离线构建：用 -PpodroidApk=<path> 指定已经下好的 APK，或把它放到
// terminal/build/podroid/Podroid-v1.2.9-release.apk。
// ---------------------------------------------------------------------------

private val podroidVersion = "v1.2.9"
private val podroidApkFileName = "Podroid-$podroidVersion-release.apk"

private data class VmAssetSpec(val zipPath: String, val targetPath: String, val sha256: String)

/** 清单必须与 VmAssets.kt 中的定义一致：换版本要同时改两处 */
private val vmAssetSpecs =
    listOf(
        VmAssetSpec(
            "assets/vmlinuz-virt",
            "src/main/assets/vm/vmlinuz-virt",
            "6c4a6b1fff352b618cd661c8938803e5a4e16a124efbe7c0450199c2ef42eea6"
        ),
        VmAssetSpec(
            "assets/initrd.img",
            "src/main/assets/vm/initrd.img",
            "57ae98b7aa54271da846e8c57d9c31f5474b452e77561a69263309931ce403dc"
        ),
        VmAssetSpec(
            "assets/alpine-rootfs.squashfs",
            "src/main/assets/vm/alpine-rootfs.squashfs",
            "04b7dfdaebeb1dccce6824a22c8cceaa1483aef6a7643bb9ccd96feee3618121"
        ),
        // QEMU 与 slirp 走 jniLibs：安装时由系统提取到 nativeLibraryDir，
        // 从那里执行是 Android 支持的路径，应用私有目录里的可执行文件在部分版本上会被 SELinux 拦下
        VmAssetSpec(
            "lib/arm64-v8a/libqemu-system-aarch64.so",
            "src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so",
            "0eccc1a9fcf26906ba6e855223a22832e1c6fc614787498cc274c1099766448f"
        ),
        VmAssetSpec(
            "lib/arm64-v8a/libslirp.so",
            "src/main/jniLibs/arm64-v8a/libslirp.so",
            "349aeb91b0e998c2dc6d34e8e4a92f578402bec6210b781a0a37e36a4fa3515e"
        )
    )

private fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun extractVerified(zip: ZipFile, spec: VmAssetSpec, destination: File) {
    val entry =
        zip.getEntry(spec.zipPath)
            ?: throw GradleException(
                "Podroid APK lacks ${spec.zipPath}; expected the $podroidVersion release artifact"
            )
    destination.parentFile.mkdirs()
    val temp = File(destination.parentFile, "${destination.name}.part")
    zip.getInputStream(entry).use { input ->
        temp.outputStream().use { output -> input.copyTo(output) }
    }
    val actual = sha256Of(temp)
    if (actual != spec.sha256) {
        temp.delete()
        throw GradleException("SHA-256 mismatch for ${spec.zipPath}: got $actual, expected ${spec.sha256}")
    }
    if (destination.exists() && !destination.delete()) {
        throw GradleException("Cannot replace ${destination.absolutePath}")
    }
    if (!temp.renameTo(destination)) {
        throw GradleException("Cannot move ${temp.absolutePath} to ${destination.absolutePath}")
    }
}

val fetchVmAssets by tasks.registering {
    group = "build"
    description = "Assembles the VM assets (kernel, initrd, squashfs, QEMU, slirp) into the module"

    // 配置期取值：执行期再读 project 会破坏配置缓存
    val providedApkPath = (project.findProperty("podroidApk") as String?)
    val apkUrl =
        (project.findProperty("podroidApkUrl") as String?)
            ?: "https://ghfast.top/https://github.com/ExTV/Podroid/releases/download/" +
            "$podroidVersion/$podroidApkFileName"
    val cacheDir = layout.buildDirectory.dir("podroid").get().asFile
    val specs = vmAssetSpecs
    val moduleDir = projectDir

    doLast {
        val cachedApk = File(cacheDir, podroidApkFileName)
        val apk =
            when {
                providedApkPath != null && File(providedApkPath).isFile -> File(providedApkPath)
                cachedApk.isFile -> cachedApk
                else -> {
                    cacheDir.mkdirs()
                    logger.lifecycle("Downloading Podroid $podroidVersion APK from $apkUrl")
                    // 注意：脚本里 `java` 是 Java 插件扩展，URL 类必须用 import 进来的名字
                    URI(apkUrl).toURL().openStream().use { input ->
                        cachedApk.outputStream().use { output -> input.copyTo(output) }
                    }
                    cachedApk
                }
            }

        if (specs.all { File(moduleDir, it.targetPath).isFile }) {
            logger.lifecycle("VM assets already assembled")
            return@doLast
        }

        logger.lifecycle("Assembling VM assets from ${apk.absolutePath}")
        ZipFile(apk).use { zip ->
            specs.forEach { spec ->
                extractVerified(zip, spec, File(moduleDir, spec.targetPath))
                logger.lifecycle("  ${spec.zipPath} -> ${spec.targetPath}")
            }
        }
    }
}

// 打包前必须装配完成：少一件资产就意味发布包里少一个内核或少了 QEMU
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(fetchVmAssets) }
