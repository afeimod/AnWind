package com.anwind.apps.terminal.termux

import android.content.Context
import android.system.Os
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * AnWind Termux 移植版的 Bootstrap 安装器。
 *
 * 职责（对应上游 TermuxInstaller 的功能，代码为面向 AnWind 的独立实现）：
 *
 * 1. 从 APK assets 读取官方 bootstrap（termux-packages 官方构建产物，
 *    SHA-256 校验）；
 * 2. 解压到 `$filesDir/usr-staging`；
 * 3. **同长度路径重写**：把 bootstrap 中所有硬编码的
 *    `/data/data/com.termux/` 前缀改写为 `/data/data/com.anwind/`——
 *    包括 227 个 ELF 二进制内部（.rodata/.dynstr 的编译期路径）、
 *    501 个文本文件（shebang / apt 配置 / dpkg 数据库）以及
 *    SYMLINKS.txt 中的绝对路径符号链接。由于 `com.anwind` 与
 *    `com.termux` 逐字节等长，替换不改变任何文件长度与 ELF 结构，
 *    等效于"以 AnWind 的包名与路径重新编译"了整个根文件系统；
 * 4. 按 Termux 官方规则设置可执行权限（bin/、libexec/、apt 辅助程序）；
 * 5. 创建官方 SYMLINKS.txt 中声明的符号链接；
 * 6. 原子重命名 staging → `$filesDir/usr`；
 * 7. 安装 AnWind 专属增强：profile.d/anwind.sh（theme/start 等桌面命令
 *    注入真实 bash）、命令 FIFO、AnWind 版 motd。
 */
object TermuxBootstrapInstaller {

    /**
     * 0700 的十进制值（Kotlin 不支持八进制字面量）：
     * 目录/可执行文件的属主读写执行权限，对齐官方 TermuxInstaller。
     */
    private const val PERMISSION_0700 = 448

    /** 安装状态（Compose 界面订阅渲染）。 */
    sealed class InstallState {
        object NotInstalled : InstallState()
        data class Installing(val progress: Float, val message: String) : InstallState()
        object Installed : InstallState()
        data class Failed(val message: String) : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(InstallState.NotInstalled)
    val state: StateFlow<InstallState> = _state

    private val installLock = Any()

    /** 全部普通文件入口计数（用于进度估算）。 */
    private val rewriteStats = intArrayOf(0)

    /**
     * bootstrap 是否已安装完成（$PREFIX 存在且含 bin/sh 可执行）。
     */
    fun isInstalled(context: Context): Boolean {
        val sh = File(TermuxEnvironment.binDir(context), "sh")
        return sh.isFile && sh.canExecute()
    }

    /**
     * 入口：按需安装 bootstrap。已安装则直接回调。
     * 结果通过 [state] 与回调双通道通知。
     */
    fun installIfNeeded(
        context: Context,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        synchronized(installLock) {
            if (isInstalled(context)) {
                _state.value = InstallState.Installed
                onDone()
                return
            }
            if (_state.value is InstallState.Installing) return

            _state.value = InstallState.Installing(0f, "准备安装…")
        }

        val appContext = context.applicationContext
        Thread({
            try {
                installInternal(appContext)
                _state.value = InstallState.Installed
                android.util.Log.i(TAG, "Termux bootstrap installed successfully " +
                        "(rewritten files: ${rewriteStats[0]})")
                onDone()
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Bootstrap install failed", e)
                _state.value = InstallState.Failed(e.message ?: e.javaClass.simpleName)
                onError(e.message ?: e.javaClass.simpleName)
            }
        }, "termux-bootstrap-installer").start()
    }

    // ------------------------------------------------------------------
    // 核心安装流程
    // ------------------------------------------------------------------

    private fun installInternal(context: Context) {
        val arch = TermuxEnvironment.deviceBootstrapArch()
            ?: throw IllegalStateException(
                "此设备架构（${android.os.Build.SUPPORTED_ABIS.firstOrNull()}）没有对应的离线 bootstrap。" +
                        "当前离线包仅内置 aarch64（arm64-v8a）。"
            )

        val staging = TermuxEnvironment.stagingPrefixDir(context)
        val prefix = TermuxEnvironment.prefixDir(context)

        // 1. 清理旧的 staging / 残缺 prefix
        _state.value = InstallState.Installing(0.02f, "清理旧安装…")
        deleteRecursive(staging)
        deleteRecursive(prefix)
        TermuxEnvironment.filesDir(context).mkdirs()
        staging.mkdirs()
        Os.chmod(staging.absolutePath, PERMISSION_0700)

        // 2. 从 assets 拷贝 bootstrap 到 cache 并校验 SHA-256
        _state.value = InstallState.Installing(0.05f, "读取 bootstrap 归档…")
        val bootstrapFile = File(context.cacheDir, "termux-bootstrap-${arch}.zip")
        copyAssetToFile(context, arch, bootstrapFile)
        verifyChecksum(bootstrapFile, arch)

        // 3. 预扫描：总字节数（进度分母）
        var entryList: List<ZipEntry> = emptyList()
        var totalUncompressed = 0L
        ZipFile(bootstrapFile).use { zip ->
            val collected = mutableListOf<ZipEntry>()
            val en = zip.entries()
            while (en.hasMoreElements()) collected.add(en.nextElement())
            entryList = collected
            totalUncompressed = collected.sumOf { it.size }
        }

        // 4. 解压 + 路径重写 + 权限
        _state.value = InstallState.Installing(0.08f, "解压并重写路径…")
        var writtenBytes = 0L
        val symlinks = mutableListOf<Pair<String, String>>() // (target, linkPath)

        ZipFile(bootstrapFile).use { zip ->
            val entries = entryList
            var processed = 0
            for (entry in entries) {
                processed++
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    // 符号链接表：重写其中的绝对路径 target
                    val text = zip.getInputStream(entry).readBytes().decodeToString()
                    for (rawLine in text.lineSequence()) {
                        val line = rawLine.trim()
                        if (line.isEmpty()) continue
                        val parts = line.split("←")
                        if (parts.size != 2) continue
                        var target = parts[0]
                        // 绝对路径符号链接的 target 指向旧前缀 → 重写到 AnWind
                        if (target.startsWith(TermuxEnvironment.LEGACY_TERMUX_FILES_PREFIX) &&
                            TermuxEnvironment.LEGACY_TERMUX_FILES_PREFIX !=
                            TermuxEnvironment.ANWIND_FILES_PREFIX
                        ) {
                            target = target.replace(
                                TermuxEnvironment.LEGACY_TERMUX_FILES_PREFIX,
                                TermuxEnvironment.ANWIND_FILES_PREFIX
                            )
                        }
                        val linkPath = File(staging, parts[1]).absolutePath
                        File(linkPath).parentFile?.mkdirs()
                        symlinks.add(target to linkPath)
                    }
                    writtenBytes += entry.size
                    continue
                }

                val targetFile = File(staging, name)
                if (entry.isDirectory) {
                    targetFile.mkdirs()
                    continue
                }
                targetFile.parentFile?.mkdirs()

                val raw = zip.getInputStream(entry).readBytes()
                // **核心**：同长度字节重写 com.termux → com.anwind
                val (rewritten, count) = rewriteLegacyPaths(raw)
                if (count > 0) rewriteStats[0]++
                FileOutputStream(targetFile).use { it.write(rewritten) }

                // 官方权限规则：bin / libexec / apt 辅助程序可执行
                if (name.startsWith("bin/") || name.startsWith("libexec") ||
                    name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                ) {
                    Os.chmod(targetFile.absolutePath, PERMISSION_0700)
                }

                writtenBytes += entry.size
                if (processed % 64 == 0) {
                    val frac = 0.08f + 0.82f * (writtenBytes.toFloat() / totalUncompressed.toFloat())
                    _state.value = InstallState.Installing(
                        frac.coerceAtMost(0.9f),
                        "解压并重写路径… $processed/${entries.size}"
                    )
                }
            }
        }

        if (symlinks.isEmpty()) throw IllegalStateException("bootstrap 中缺少 SYMLINKS.txt")

        // 5. 创建符号链接
        _state.value = InstallState.Installing(0.92f, "创建符号链接…")
        for ((target, linkPath) in symlinks) {
            val link = File(linkPath)
            if (link.exists()) link.delete()
            try {
                Os.symlink(target, linkPath)
            } catch (e: Exception) {
                // 个别符号链接失败不致命（如目标为可选组件）
                android.util.Log.w(TAG, "symlink 创建失败: $linkPath -> $target (${e.message})")
            }
        }

        // 6. 原子重命名 staging → prefix
        _state.value = InstallState.Installing(0.96f, "完成安装…")
        if (!staging.renameTo(prefix)) {
            throw IllegalStateException("移动 staging 目录到 prefix 失败")
        }
        Os.chmod(prefix.absolutePath, PERMISSION_0700)

        // home / tmp
        val home = TermuxEnvironment.homeDir(context)
        home.mkdirs()
        val tmp = TermuxEnvironment.tmpDir(context)
        tmp.mkdirs()

        // 7. AnWind 专属增强
        _state.value = InstallState.Installing(0.98f, "配置 AnWind 集成…")
        installAnWindExtras(context)

        // 8. 清理缓存归档
        bootstrapFile.delete()
    }

    // ------------------------------------------------------------------
    // 路径重写引擎
    // ------------------------------------------------------------------

    /** 旧前缀（含尾部斜杠）与旧包名，作为字节模式。 */
    private val legacyPathBytes =
        (TermuxEnvironment.LEGACY_TERMUX_FILES_PREFIX + "/").toByteArray(Charsets.UTF_8)
    private val anwindPathBytes =
        (TermuxEnvironment.ANWIND_FILES_PREFIX + "/").toByteArray(Charsets.UTF_8)

    /**
     * 同长度字节重写：把 [legacyPathBytes] 的所有出现替换为
     * [anwindPathBytes]。两者等长时为纯原地替换（文件长度、ELF
     * 段偏移、任何二进制结构都不受影响）；若不等长（防御未来包名
     * 变更），退化为文本 String 替换，并要求新前缀不长于旧前缀。
     *
     * @return (重写后的字节数组, 替换次数)
     */
    private fun rewriteLegacyPaths(raw: ByteArray): Pair<ByteArray, Int> {
        if (legacyPathBytes.size == anwindPathBytes.size) {
            // 快速路径：等长原地替换（文本与二进制通用）
            var count = 0
            val first = legacyPathBytes[0]
            var i = 0
            outer@ while (i <= raw.size - legacyPathBytes.size) {
                if (raw[i] == first && matchesAt(raw, i)) {
                    System.arraycopy(anwindPathBytes, 0, raw, i, anwindPathBytes.size)
                    count++
                    i += legacyPathBytes.size
                    continue@outer
                }
                i++
            }
            return raw to count
        }

        // 防御路径：前缀不等长（当前 AnWind 不会走到这里）
        if (anwindPathBytes.size > legacyPathBytes.size) {
            throw IllegalStateException(
                "新前缀比旧前缀长，无法对二进制做安全重写 " +
                        "(${TermuxEnvironment.ANWIND_FILES_PREFIX} vs ${TermuxEnvironment.LEGACY_TERMUX_FILES_PREFIX})"
            )
        }
        val text = raw.toString(Charsets.UTF_8)
        val replaced = text.replace(
            TermuxEnvironment.LEGACY_TERMUX_FILES_PREFIX,
            TermuxEnvironment.ANWIND_FILES_PREFIX
        )
        return replaced.toByteArray(Charsets.UTF_8) to
                (text.length - replaced.length).coerceAtLeast(0)
    }

    private fun matchesAt(data: ByteArray, offset: Int): Boolean {
        for (k in legacyPathBytes.indices) {
            if (data[offset + k] != legacyPathBytes[k]) return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // AnWind 集成增强
    // ------------------------------------------------------------------

    /**
     * 安装 AnWind 专属组件：
     * - `$PREFIX/etc/profile.d/anwind.sh`：把 theme/start/open 等桌面
     *   命令注入每个真实 bash 会话（通过 FIFO 回传 App）；
     * - `$PREFIX/var/anwind.cmd`：命令 FIFO；
     * - `$PREFIX/etc/motd`：AnWind 版欢迎信息。
     */
    private fun installAnWindExtras(context: Context) {
        val profileDir = File(TermuxEnvironment.etcDir(context), "profile.d")
        profileDir.mkdirs()
        val fifoPath = TermuxEnvironment.commandFifoPath(context)

        File(profileDir, "anwind.sh").writeText(
            """
            # ============================================================
            # AnWind desktop integration (auto-generated by AnWind installer)
            # 在真实 Termux shell 中提供 AnWind 桌面命令：
            #   theme win95|xp|win7|win10|win11   切换桌面主题
            #   start <app>                      打开 AnWind 应用
            #   apps                             列出可打开的应用
            #   open <url>                       用 AnWind 浏览器打开网址
            #   winver                           显示 AnWind 版本信息
            # 原理：命令写入 FIFO，由 App 主进程读取并执行。
            # ============================================================

            ANWIND_CMD_FIFO="${'$'}PREFIX/var/anwind.cmd"

            _anwind_send() {
                if [ -p "${'$'}ANWIND_CMD_FIFO" ]; then
                    ( printf '%s\n' "${'$'}*" > "${'$'}ANWIND_CMD_FIFO" & ) 2>/dev/null
                else
                    echo "anwind: 命令桥不可用（App 未运行或 FIFO 缺失）" >&2
                    return 1
                fi
            }

            theme() {
                if [ ${'#'} -eq 0 ]; then
                    echo "用法: theme win95|xp|win7|win10|win11"
                    return 1
                fi
                _anwind_send "theme ${'$'}*"
            }

            start() {
                if [ ${'#'} -eq 0 ]; then
                    echo "用法: start <应用名>，运行 apps 查看列表"
                    return 1
                fi
                _anwind_send "start ${'$'}*"
            }

            apps() {
                _anwind_send "apps"
            }

            open() {
                if [ ${'#'} -eq 0 ]; then
                    echo "用法: open <url>"
                    return 1
                fi
                _anwind_send "open ${'$'}*"
            }

            winver() {
                _anwind_send "winver"
                echo "AnWind ${'$'}{ANWIND_VERSION:-2.21.5} (Termux ${'$'}{TERMUX_VERSION:-0.118.0} 移植)"
            }
            """.trimIndent()
        )

        // 命令 FIFO
        try {
            com.anwind.termux.terminal.TermuxBridge.createFifo(fifoPath)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "FIFO 创建失败（桌面命令桥不可用）: ${e.message}")
        }

        // AnWind 版 motd
        File(TermuxEnvironment.etcDir(context), "motd").writeText(
            """
            ┌────────────────────────────────────────────┐
            │  AnWind Terminal · 真实 Termux 环境        │
            └────────────────────────────────────────────┘
              pkg install <包名>   安装软件包（官方 apt 源）
              pkg search <关键字>  搜索软件包
              pkg update           更新软件源
              theme win11          切换 AnWind 桌面主题
              apps / start 浏览器  联动 AnWind 应用
              help                 查看更多

            欢迎使用由 AnWind 移植的 Termux 环境。
            本环境基于 termux-packages 官方 bootstrap，
            已重写为 AnWind 的包名与路径前缀。
            """.trimIndent() + "\n"
        )
    }

    // ------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------

    private fun copyAssetToFile(context: Context, arch: String, dest: File) {
        val assetName = "${TermuxEnvironment.BOOTSTRAP_ASSET_PREFIX}$arch.zip"
        context.assets.open(assetName).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output, 64 * 1024)
            }
        }
    }

    private fun verifyChecksum(bootstrapFile: File, arch: String) {
        val expected = when (arch) {
            "aarch64" -> TermuxEnvironment.BOOTSTRAP_AARCH64_SHA256
            else -> null // 其他架构如后续加入，填入官方 SHA-256
        } ?: return

        val digest = MessageDigest.getInstance("SHA-256")
        bootstrapFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw IllegalStateException(
                "bootstrap SHA-256 校验失败！\n期望: $expected\n实际: $actual\n" +
                        "归档可能损坏或被篡改，安装已中止。"
            )
        }
    }

    private fun deleteRecursive(file: File) {
        // 符号链接一律只删链接本身，绝不递归进入目标（lstat 语义）
        if (!java.nio.file.Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            val children = file.listFiles() ?: return
            for (child in children) deleteRecursive(child)
        }
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    private const val TAG = "TermuxBootstrapInstaller"
}
