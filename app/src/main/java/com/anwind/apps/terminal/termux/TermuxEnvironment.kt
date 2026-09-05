package com.anwind.apps.terminal.termux

import android.content.Context
import android.os.Build
import java.io.File

/**
 * AnWind Termux 移植版的环境定义。
 *
 * 对应上游 termux-shared 的 TermuxConstants / TermuxShellUtils
 * （GPLv3），本文件为面向 AnWind 的独立精简实现：
 * 所有路径均由 AnWind 自己的 applicationId 派生。
 *
 * 关键事实：
 * - 官方 bootstrap 里的二进制/脚本把 `/data/data/com.termux/files/usr`
 *   硬编码进了编译产物（ELF 的 .rodata 与脚本 shebang）。
 * - AnWind 的 applicationId 为 `com.anwind`，与 `com.termux` 恰好
 *   同为 10 字符，因此安装期的**同长度字节重写**（见
 *   [TermuxBootstrapInstaller]）在文本与 ELF 上都完全安全——
 *   等效于"按 com.anwind 的路径重新编译"了整个根文件系统。
 */
object TermuxEnvironment {

    /** 官方 bootstrap 中被硬编码的旧前缀（末尾不带分隔符）。 */
    const val LEGACY_TERMUX_APP_PACKAGE = "com.termux"
    const val LEGACY_TERMUX_FILES_PREFIX = "/data/data/com.termux/files"

    /** AnWind 自身的新前缀（末尾不带分隔符）。与旧前缀**等长**。 */
    const val ANWIND_APP_PACKAGE = "com.anwind"
    const val ANWIND_FILES_PREFIX = "/data/data/com.anwind/files"

    /** bootstrap 归档文件名（assets/termux/ 下）。 */
    const val BOOTSTRAP_ASSET_DIR = "termux"
    const val BOOTSTRAP_ASSET_PREFIX = "$BOOTSTRAP_ASSET_DIR/bootstrap-"

    /** bootstrap 来源（供文档与"关于"信息展示）。 */
    const val TERMUX_APP_VERSION = "0.118.0"
    const val BOOTSTRAP_BUILD_VERSION = "2022.01.07-r1"
    const val BOOTSTRAP_SOURCE_URL =
        "https://github.com/termux/termux-packages/releases/download/bootstrap-$BOOTSTRAP_BUILD_VERSION/bootstrap-%s.zip"
    const val BOOTSTRAP_AARCH64_SHA256 =
        "0fe6d0159d12fcb8baf7750ce9072b9b36f742662b02ad4da145ab85873614cd"

    // ------------------------------------------------------------------
    // 运行期路径（全部由 Context 派生，避免硬编码二次引入）
    // ------------------------------------------------------------------

    fun filesDir(context: Context): File = File(context.applicationInfo.dataDir, "files")

    fun prefixDir(context: Context): File = File(filesDir(context), "usr")

    fun prefixPath(context: Context): String = prefixDir(context).absolutePath

    fun stagingPrefixDir(context: Context): File = File(filesDir(context), "usr-staging")

    fun binDir(context: Context): File = File(prefixDir(context), "bin")

    fun etcDir(context: Context): File = File(prefixDir(context), "etc")

    fun varDir(context: Context): File = File(prefixDir(context), "var")

    fun tmpDir(context: Context): File = File(prefixDir(context), "tmp")

    fun homeDir(context: Context): File = File(filesDir(context), "home")

    fun homePath(context: Context): String = homeDir(context).absolutePath

    /** shell → App 命令桥的 FIFO 路径。 */
    fun commandFifoPath(context: Context): String =
        File(varDir(context), "anwind.cmd").absolutePath

    // ------------------------------------------------------------------
    // 架构映射
    // ------------------------------------------------------------------

    /**
     * 设备主 ABI → bootstrap 归档架构名。
     * 返回 null 表示本机构架未打包（当前离线包仅含 aarch64）。
     */
    fun deviceBootstrapArch(): String? {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when (primaryAbi) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a", "armeabi" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> null
        }
    }

    /** 本 APK 是否内置了当前设备架构的 bootstrap。 */
    fun isArchSupportedOffline(): Boolean = deviceBootstrapArch() != null

    // ------------------------------------------------------------------
    // 子进程环境变量（对应上游 TermuxShellUtils.buildEnvironment）
    // ------------------------------------------------------------------

    /**
     * 构建 Termux 子进程环境。保持与官方一致的变量集合，
     * 但 PREFIX/HOME/PATH 指向 AnWind 自己的目录。
     */
    fun buildEnvironment(context: Context, isFailSafe: Boolean): List<String> {
        homeDir(context).mkdirs()
        val prefix = prefixPath(context)
        val bin = "$prefix/bin"
        val workingDirectory = homePath(context)

        val env = mutableListOf<String>()
        env.add("TERMUX_VERSION=$TERMUX_APP_VERSION")
        env.add("TERM=xterm-256color")
        env.add("COLORTERM=truecolor")
        env.add("HOME=${homePath(context)}")
        env.add("PREFIX=$prefix")
        env.add("BOOTCLASSPATH=${System.getenv("BOOTCLASSPATH") ?: ""}")
        env.add("ANDROID_ROOT=${System.getenv("ANDROID_ROOT") ?: "/system"}")
        env.add("ANDROID_DATA=${System.getenv("ANDROID_DATA") ?: "/data"}")
        env.add("EXTERNAL_STORAGE=${System.getenv("EXTERNAL_STORAGE") ?: "/storage/emulated/legacy"}")

        // Android 10+ 必需（ART 运行时根路径）
        addIfPresent(env, "ANDROID_ART_ROOT")
        addIfPresent(env, "DEX2OATBOOTCLASSPATH")
        addIfPresent(env, "ANDROID_I18N_ROOT")
        addIfPresent(env, "ANDROID_RUNTIME_ROOT")
        addIfPresent(env, "ANDROID_TZDATA_ROOT")

        if (isFailSafe) {
            // failsafe 会话保留系统 PATH，保证系统工具可用
            env.add("PATH=${System.getenv("PATH") ?: "/system/bin:/system/xbin"}")
        } else {
            env.add("LANG=en_US.UTF-8")
            env.add("PATH=$bin")
            env.add("PWD=$workingDirectory")
            env.add("TMPDIR=$prefix/tmp")
        }
        return env
    }

    private fun addIfPresent(env: MutableList<String>, name: String) {
        System.getenv(name)?.let { env.add("$name=$it") }
    }

    /**
     * 选择可执行文件的实际启动方式（对应上游 setupProcessArgs）：
     * - ELF → 直接执行
     * - shebang 为 /usr/... 或 /bin/... → 换成 $PREFIX/bin/ 下的同名解释器
     * - 无 shebang 的脚本 → 用 $PREFIX/bin/sh 执行
     */
    fun setupProcessArgs(context: Context, fileToExecute: String, arguments: Array<String>): Array<String> {
        var interpreter: String? = null
        try {
            val file = File(fileToExecute)
            if (file.isFile) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(256)
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 4) {
                        when {
                            buffer[0] == 0x7F.toByte() && buffer[1] == 'E'.code.toByte() &&
                                buffer[2] == 'L'.code.toByte() && buffer[3] == 'F'.code.toByte() -> {
                                // ELF，直接执行
                            }
                            buffer[0] == '#'.code.toByte() && buffer[1] == '!'.code.toByte() -> {
                                val builder = StringBuilder()
                                var i = 2
                                while (i < bytesRead) {
                                    val c = buffer[i].toChar()
                                    if (c == ' ' || c == '\n') {
                                        if (builder.isNotEmpty()) {
                                            val executable = builder.toString()
                                            if (executable.startsWith("/usr") || executable.startsWith("/bin")) {
                                                val binary = executable.substring(executable.lastIndexOf('/') + 1)
                                                interpreter = "${binDir(context).absolutePath}/$binary"
                                            }
                                            break
                                        }
                                    } else {
                                        builder.append(c)
                                    }
                                    i++
                                }
                            }
                            else -> interpreter = "${binDir(context).absolutePath}/sh"
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 读取失败按原样执行
        }

        val result = mutableListOf<String>()
        interpreter?.let { result.add(it) }
        result.add(fileToExecute)
        result.addAll(arguments)
        return result.toTypedArray()
    }
}
