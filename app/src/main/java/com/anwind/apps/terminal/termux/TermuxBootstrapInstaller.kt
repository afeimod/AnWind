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
 * 3. **同长度路径重写**：把 bootstrap 中所有硬编码的 `com.termux`
 *    字节串改写为 `com.anwind`（裸包名模式，覆盖任意出现位置：
 *    `/data/data/com.termux/...` 路径、`com.termux/cache` 缓存路径、
 *    intent 组件名等）——包括 227 个 ELF 二进制内部（.rodata/.dynstr
 *    的编译期路径）、572 个文本文件（shebang / apt 配置 / dpkg 数据库）
 *    以及 SYMLINKS.txt。由于 `com.anwind` 与 `com.termux` 逐字节等长，
 *    替换不改变任何文件长度与 ELF 结构，等效于"以 AnWind 的包名与
 *    路径重新编译"了整个根文件系统；
 * 4. 按 Termux 官方规则设置可执行权限（bin/、libexec/、apt 辅助程序）；
 * 5. 创建官方 SYMLINKS.txt 中声明的符号链接；
 * 6. 原子重命名 staging → `$filesDir/usr`；
 * 7. 安装 AnWind 专属增强（installAnWindExtras）：profile.d/anwind.sh
 *    （theme/start 等桌面命令注入真实 bash）、命令 FIFO、motd；
 * 8. 安装包工具链（installPackageToolchain）：原生 anwind-reprefix
 *    重写工具 + dpkg 包装器 + anwind-debfix 重打包器 + anwind-mirror
 *    源体检工具 + anwind-glibc 一键脚本——官方源的 deb
 *    按 com.termux 前缀构建（tar 成员路径即绝对路径），安装前自动
 *    重打包、装完再增量重写，保证 pkg/apt 装的软件开箱即用；
 * 9. 存量安装增量迁移（migrateIfNeeded）：按修订号检测旧版本安装，
 *    免清数据升级增强组件并全量重写既有文件。
 */
object TermuxBootstrapInstaller {

    /**
     * 0700 的十进制值（Kotlin 不支持八进制字面量）：
     * 目录/可执行文件的属主读写执行权限，对齐官方 TermuxInstaller。
     */
    private const val PERMISSION_0700 = 448
    private const val PERMISSION_0600 = 384

    /**
     * 增强组件修订号：anwind.sh / 工具链 / motd / apt 源修复内容变更时 +1。
     * 已安装的 bootstrap 检测到修订号落后时会自动增量迁移（免清数据）。
     * rev 3：新增 anwind-mirror（pool 级源体检/切源）+ 存量安装的
     * sources.list 自动修复（老版 pkg 轮换到的 packages-cf 镜像对
     * pool 目录的 .deb 一律 403，导致 pkg update 与 anwind-glibc 全部失败）。
     * rev 4：修复 dpkg 包自升级击杀 bin/dpkg 包装器的致命缺陷（fix7）。
     * 旧版包装器只部署在 bin/dpkg 一处，dpkg 包升级时 deb 内的真身 ELF
     * 会覆盖它，此后所有 deb 绕过 anwind-debfix 重打包，pkg update /
     * pkg install 全线报 "unable to stat './data/data/com.termux':
     * Permission denied"。rev 4 起包装器本体改驻 libexec/anwind/dpkg
     * （无任何包会覆盖），apt 经 apt.conf.d/99anwind 的 Dir::Bin::dpkg
     * 固定走包装器；bin/dpkg 保留副本并由包装器/会话启动自愈；
     * anwind-debfix 同步刷新 dpkg.real 并修复盖章污染。
     * rev 5：新增 dpkg path-exclude 双保险（fix8）。官方 deb 中偶有
     * 未经 anwind-debfix 重写的 /data/data/com.termux 成员（典型如裸
     * 目录条目 ./data/data/com.termux，无尾斜杠），dpkg lstat 无权访问
     * 报 Permission denied；写入 etc/dpkg/dpkg.cfg.d/99-anwind-fix 与
     * etc/apt/apt.conf.d/99-anwind-fix（path-exclude + force-confold）
     * 让 dpkg 跳过这些成员而非报错，并消除配置文件交互提示。
     * rev 6：anwind-debfix v3（fix8.1）。实测 dpkg 的 filter 检查发生在
     * lstat 之后，path-exclude 对 EACCES 场景无效——官方前缀成员必须
     * 在落盘前消灭。debfix v3 把全部静默失败路径改为 fail-loud + 不盖章
     * 可重试，并新增目录改名降级保底（reprefix 失效时 unpack 仍可通过）
     * 与重建成品验收（dpkg-deb -c 复查无 com.termux 成员才替换）。
     * rev 7：anwind-debfix v4（fix8.2）记账免疫 + apt 缓存切断。
     * 实测定性：v2 时代静默失败盖章的官方 deb 一直留在 apt 下载缓存
     * 中（mtime 恒定），apt 硬链接复用时记账命中直接跳过重写——
     * 同一批包永远失败且无任何提示。v4 在记账命中时也快检成员
     * （dpkg-deb -c + grep -m1，官方 deb 的 com.termux 条目在 tar
     * 前部秒停），残留即强制重跑；同时迁移时清 apt archives 缓存，
     * 双保险切断污染闭环。
     * rev 8：reprefix v5 + debfix v5（fix8.3）引擎级收口。
     * reprefix：只读权限文件（0444/0555）内容改写曾因 EACCES 被静默
     * 跳过，现临时加写位重试后还原；树内改名失败不再静默（--tree
     * 非零退出，debfix 走降级路径）；新增 --verify 复检与 --version
     * 指纹。debfix：验收/记账 grep 收紧为任意 com.termux 成员；
     * 重写后 --verify 复检残留并大声告警；anwind-debfix version
     * 可在设备端一键核验部署状态（会话启动也会打印部署指纹行）。
     * rev 9：debfix v6 + 包装器 v3（fix8.4）检测层根治。
     * rev 8 上机实测：pcre 完整重写成功（count=7），同批 13 包全部
     * 静默失败且零警告——唯一自洽分支 = 记账命中后的 dpkg-deb -c
     * 快检（-c 的成员列表依赖外部 tar，列表为空/失败时 grep 误判
     * 干净→脏 deb 永久静默跳过）。v6 改用字节级扫描（dpkg-deb
     * --fsys-tarfile 内建解压不依赖外部 tar + grep 扫描 com.termux
     * 字节，"无法验证"一律按脏处理），成品验收增加树路径残余硬门；
     * 包装器在 dpkg 失败且确有修复发生时自动重试一次，同一命令内
     * 自愈。迁移照例清 debfix 记账与 apt archives 缓存。
     * rev 10：可观测层 + 独立第二路径（fix8.5）。rev 9 上机复测：v6 摘要
     * 行照常打印（libcrypt/pcre），但同批 13 包依旧零输出失败（其中
     * command-not-found_3.5.0-10 为全新版本，不可能有记账——v6 对全新
     * 官方 deb 必然打印摘要行）。对策：① 文件日志 pkgfix.log：包装器
     * 每次调用的参数全量与 debfix 每个决策（含静默跳过）全部落盘，
     * cat 即可定位（终端丢行不再影响诊断）；② Pre-Install-Pkgs 钩子
     * （本函数随 apt.conf 一并写入）：apt 调 dpkg 前把全部 deb 路径经
     * stdin 喂给 anwind-debfix，绕过包装器循环；③ 包装器自检：不信任
     * 记账，字节级复查每个 deb 参数，残留即可见地再修；④ apt 缓存
     * 预扫描：包装器每次调用先把缓存中的官方 deb 重写为净品；
     * ⑤ 会话启动一次性 anwind-reprefix --full 全量重写 dpkg 数据库
     * （info 目录下的 *.list、status 历史残留 com.termux 路径）。
     * rev 11：dpkg 真身晋升安全门 + 动态库解析保障（fix8.6）。
     * 事故定性（pkgfix.log 2026-09-06）：debfix 在 Pre-Install 钩子
     * 里把重写树中的新 dpkg 1.22.6-5 直接换入 dpkg.real，而新版
     * 官方包改用 DT_RUNPATH（bionic 对主执行文件不认）且 App 会话
     * 环境缺 LD_LIBRARY_PATH（上游 Termux 必设，AnWind 遗漏），
     * 新真身首次链接即 CANNOT LINK EXECUTABLE ... libmd.so not
     * found，事务内所有后续 dpkg 调用全部 rc=1（pkg upgrade 整体
     * 失败、无任何包装上）。对策：① TermuxEnvironment.build-
     * Environment 补设 LD_LIBRARY_PATH=$PREFIX/lib（对齐上游，
     * /system 二进制走系统命名空间不受影响）；② 包装器无条件兜底
     * 导出同一变量，覆盖 failsafe/adb 等绕过 App 环境的调用方；
     * ③ sync_dpkg_real 与包装器晋升路径均先跑 --version 自检，
     * 不可运行则暂存 dpkg.real.pending、保留旧真身继续干活，待
     * 依赖（如随事务解包的 libmd）就绪后自动晋升——dpkg 永远
     * 不会再被换入的真身打死。
     * rev 12：内置 X11 桌面客户端（v2.22.2）—— 部署 termux-x11 /
     * anwind-x11 / anwind-x11-stop 三个终端命令与宿主组件定位文件
     * etc/anwind-x11.env（终端侧 anwind-x11 经 app_process 拉起
     * CmdEntryPoint X server，App 端全屏 X11 桌面自动弹出）。
     * rev 13：X11 客户端防覆盖——脚本主副本落盘 etc/anwind/x11/；
     * debfix v6.3 对 termux-x11-nightly 包强制替换 bin/termux-x11
     * 为内置客户端（官方包会覆盖脚本且其类名经重写后不存在），
     * anwind-x11 启动前也按标记自愈。
     */
    private const val EXTRAS_REVISION = 13

    /** 安装状态（Compose 界面订阅渲染）。 */
    sealed class InstallState {
        object NotInstalled : InstallState()
        data class Installing(val progress: Float, val message: String) : InstallState()
        object Installed : InstallState()
        data class Failed(val message: String) : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(InstallState.NotInstalled)
    val state: StateFlow<InstallState> = _state

    /**
     * 增强组件（anwind.sh / dpkg 包装器 / 重写工具）是否就绪。
     * 存量迁移在后台进行，完成前终端区域显示准备界面，避免首帧
     * 会话读到尚未重写的旧配置。
     */
    private val _extrasReady = MutableStateFlow(false)
    val extrasReady: StateFlow<Boolean> = _extrasReady

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
        val (entryList, totalUncompressed) = ZipFile(bootstrapFile).use { zip ->
            val collected = mutableListOf<ZipEntry>()
            val en = zip.entries()
            while (en.hasMoreElements()) collected.add(en.nextElement())
            collected to collected.sumOf { it.size }
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
                    // 符号链接表：整表走包名等长重写（覆盖 target 中任意旧包名出现）
                    val (rewritten, _) = rewriteLegacyPaths(zip.getInputStream(entry).readBytes())
                    val text = rewritten.decodeToString()
                    for (rawLine in text.lineSequence()) {
                        val line = rawLine.trim()
                        if (line.isEmpty()) continue
                        val parts = line.split("←")
                        if (parts.size != 2) continue
                        val target = parts[0]
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

        // 6.5 apt/dpkg 运行期目录（libapt-pkg 编译的缓存路径在 App cache 下）
        File(context.cacheDir, "apt/archives/partial").mkdirs()
        File(prefix, "var/lib/apt/lists/partial").mkdirs()
        File(prefix, "var/cache/apt/archives/partial").mkdirs()
        File(prefix, "var/lib/anwind/debfix").mkdirs()

        // 7. AnWind 专属增强 + 包工具链
        _state.value = InstallState.Installing(0.98f, "配置 AnWind 集成…")
        installAnWindExtras(context)
        installPackageToolchain(context)
        revisionFile(context).writeText("$EXTRAS_REVISION\n")
        _extrasReady.value = true

        // 8. 清理缓存归档
        bootstrapFile.delete()
    }

    // ------------------------------------------------------------------
    // 路径重写引擎
    // ------------------------------------------------------------------

    /**
     * 旧/新包名，作为字节模式（等长）。裸包名匹配可覆盖任意出现位置：
     * /data/data/com.termux/... 路径、/data/data/com.termux/cache 缓存
     * 路径（files/ 之外，旧版仅重写 files/ 前缀时被遗漏——正是
     * "E: Archives directory ... Permission denied" 的根因）、
     * am/intent 组件名 com.termux/... 等。
     */
    private val legacyPathBytes =
        TermuxEnvironment.LEGACY_TERMUX_APP_PACKAGE.toByteArray(Charsets.UTF_8)
    private val anwindPathBytes =
        TermuxEnvironment.ANWIND_APP_PACKAGE.toByteArray(Charsets.UTF_8)

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
            TermuxEnvironment.LEGACY_TERMUX_APP_PACKAGE,
            TermuxEnvironment.ANWIND_APP_PACKAGE
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

        // 桌面命令集成：anwind.sh（assets 定稿文件，$# 参数检查已验证）
        copyAssetScript(
            context, "termux/scripts/anwind.sh",
            File(profileDir, "anwind.sh"), executable = false
        )
        // AnWind 版 motd（含 pkg / anwind-glibc 用速）
        copyAssetScript(
            context, "termux/scripts/motd",
            File(TermuxEnvironment.etcDir(context), "motd"), executable = false
        )

        // 命令 FIFO
        val fifoPath = TermuxEnvironment.commandFifoPath(context)
        try {
            com.anwind.termux.terminal.TermuxBridge.createFifo(fifoPath)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "FIFO 创建失败（桌面命令桥不可用）: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // 包工具链：官方 deb 的前缀重打包 + 安装后增量重写
    // ------------------------------------------------------------------

    /**
     * 安装包工具链（全新安装与存量迁移共用）。
     *
     * 背景：官方仓库的 deb 按 /data/data/com.termux 前缀构建，tar 成员
     * 路径本身就是绝对路径 data/data/com.termux/...，而 dpkg 以
     * instdir=/ 按成员路径落盘——不重写就会写进别的应用的数据目录。
     * 所以：
     * - 原生 anwind-reprefix（libanwind_reprefix.so，等长重写引擎）；
     * - dpkg 包装器：参数中的 *.deb 先经 anwind-debfix 重打包为
     *   com.anwind 前缀，dpkg.real 执行后再做增量重写（安全网）；
     * - dpkg path-exclude 双保险（fix8，writeDpkgPathExclude）：跳过
     *   官方 deb 中漏经重写的 com.termux 成员，消除 Permission denied；
     * - anwind-glibc：官方 gpkg 流程（glibc-repo + glibc-runner）。
     */
    private fun installPackageToolchain(context: Context) {
        val prefix = TermuxEnvironment.prefixDir(context)

        // (1) 原生重写工具（以 lib*.so 命名才会被 AGP 打包进 APK）
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val src = File(nativeDir, "libanwind_reprefix.so")
        if (src.isFile) {
            val dst = File(prefix, "bin/anwind-reprefix")
            src.copyTo(dst, overwrite = true)
            Os.chmod(dst.absolutePath, PERMISSION_0700)
        } else {
            android.util.Log.w(TAG, "libanwind_reprefix.so 缺失，安装后自动重写不可用")
        }

        // (2) dpkg 包装器三层布局（fix7，对 dpkg 包自升级免疫）：
        //     libexec/anwind/dpkg       包装器本体（apt 经 Dir::Bin::dpkg
        //                               固定调用；libexec/anwind 不属于
        //                               任何软件包，升级永不覆盖）；
        //     libexec/anwind/dpkg.real  dpkg 真身（anwind-debfix 在 dpkg
        //                               包自升级时验证可运行后同步，
        //                               fix8.6 起不可运行则暂存 pending）；
        //     bin/dpkg                  包装器副本（用户直接调用入口；
        //                               被 dpkg 升级覆盖后由包装器与
        //                               anwind.sh 会话启动自愈恢复）。
        val realDpkg = File(prefix, "libexec/anwind/dpkg.real")
        realDpkg.parentFile?.mkdirs()
        val dpkg = File(prefix, "bin/dpkg")
        if (dpkg.isFile && !isOurWrapper(dpkg)) {
            // bin/dpkg 是真身 ELF：可能是全新 bootstrap，也可能是被
            // dpkg 包升级覆盖后的新真身——提升为 dpkg.real（覆盖旧版），
            // 保证 dpkg.real 与系统内 dpkg 版本一致
            if (!dpkg.renameTo(realDpkg)) {
                dpkg.copyTo(realDpkg, overwrite = true)
                dpkg.delete()
            }
            // dpkg.real 不在任何 .list 清单里，显式补丁一次（存量迁移场景）
            runReprefix(context, listOf("--file", realDpkg.absolutePath, "--quiet"))
        }
        copyAssetScript(
            context, "termux/scripts/anwind-dpkg",
            File(prefix, "libexec/anwind/dpkg"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/anwind-dpkg",
            File(prefix, "bin/dpkg"), executable = true
        )
        writeAptDpkgPin(context)
        writeDpkgPathExclude(context)

        // (3) deb 重打包器 + glibc 一键脚本 + 源体检工具
        // 迁移场景同时清理旧版 debfix 的盖章缓存（v2 已修复盖章污染，
        // 清掉历史盖章让缓存中的 deb 重新走一遍完整处理，幂等无害）
        File(prefix, "var/lib/anwind/debfix").deleteRecursively()
        File(prefix, "var/lib/anwind/debfix").mkdirs()
        copyAssetScript(
            context, "termux/scripts/anwind-debfix",
            File(prefix, "bin/anwind-debfix"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/anwind-glibc",
            File(prefix, "bin/anwind-glibc"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/anwind-mirror",
            File(prefix, "bin/anwind-mirror"), executable = true
        )

        // (4) 存量安装的 apt 源修复（全新安装时 bootstrap 已内置好源，此处无操作）
        fixAptSources(context)

        // (5) 清 apt 下载缓存（rev 7）：debfix 静默失败时代盖过章的官方
        // deb 若留在缓存中，apt 会原样硬链接复用（mtime 不变→记账命中→
        // 跳过重写）。清掉强制重新下载，与 debfix v4 记账免疫双保险。
        try {
            val archives = File(context.cacheDir, "apt/archives")
            archives.listFiles { f -> f.isFile && f.name.endsWith(".deb") }?.forEach { it.delete() }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "清理 apt 缓存失败: ${e.message}")
        }

        // (6) v2.22.2 内置 X11 桌面客户端（终端侧命令 + 宿主定位文件）
        installX11Client(context)
    }

    /**
     * 部署内置 X11 桌面的终端侧组件（rev 13，全新安装与存量迁移共用）：
     * - bin/termux-x11：X server 客户端（app_process 拉起宿主 APK 内的
     *   com.termux.x11.CmdEntryPoint，即 lorie 合成器 + Xwayland）；
     * - bin/anwind-x11：一键启动（服务 + 自动尝试 xfce4 等桌面会话）；
     * - bin/anwind-x11-stop：停止服务（nice-name 精确匹配，不影响
     *   官方 Termux:X11 应用的进程）；
     * - etc/anwind-x11.env：宿主 APK 路径与 nativeLibraryDir（APK 内的
     *   libXlorie.so 在 useLegacyPackaging=true 下被压缩，CmdEntryPoint
     *   需从解压目录加载）。APK 路径随每次升级变化，[refreshX11Env]
     *   在应用启动时自愈。
     */
    private fun installX11Client(context: Context) {
        val prefix = TermuxEnvironment.prefixDir(context)
        copyAssetScript(
            context, "termux/scripts/termux-x11",
            File(prefix, "bin/termux-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/anwind-x11",
            File(prefix, "bin/anwind-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/anwind-x11-stop",
            File(prefix, "bin/anwind-x11-stop"), executable = true
        )
        // 主副本（rev13 防覆盖自愈源）：`pkg install termux-x11-nightly`
        // 会用官方客户端覆盖 bin/termux-x11（重写后引用的类名不存在，
        // 启动即崩）。debfix v6.3 替换 deb 内客户端、anwind-x11 启动
        // 前自检恢复，都从这里取审定版本。
        val masterDir = File(prefix, "etc/anwind/x11")
        masterDir.mkdirs()
        copyAssetScript(
            context, "termux/scripts/termux-x11",
            File(masterDir, "termux-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/anwind-x11",
            File(masterDir, "anwind-x11"), executable = true
        )
        copyAssetScript(
            context, "termux/scripts/anwind-x11-stop",
            File(masterDir, "anwind-x11-stop"), executable = true
        )
        refreshX11Env(context)
    }

    /**
     * 刷新 etc/anwind-x11.env（宿主 APK 路径 / native 库目录）。
     * 公开给 AnWindApp 在每次应用启动时调用（APK 升级后路径变化自愈），
     * bootstrap 未安装时静默跳过。
     */
    fun refreshX11Env(context: Context) {
        if (!isInstalled(context)) return
        try {
            val envFile = File(TermuxEnvironment.prefixDir(context), "etc/anwind-x11.env")
            envFile.parentFile?.mkdirs()
            val nativeDir = context.applicationInfo.nativeLibraryDir ?: ""
            envFile.writeText(
                "# 由 AnWind 自动生成（内置 X11 客户端定位宿主组件，勿手工编辑）\n" +
                "ANWIND_APK_PATH=${context.packageCodePath}\n" +
                "ANWIND_NATIVE_DIR=$nativeDir\n"
            )
            Os.chmod(envFile.absolutePath, PERMISSION_0600)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "X11 客户端环境文件写入失败: ${e.message}")
        }
    }

    /**
     * apt 钉扎（fix7）：写入 etc/apt/apt.conf.d/99anwind，把 apt/pkg
     * 的 dpkg 子进程固定到 libexec/anwind/dpkg 包装器。
     *
     * 背景：dpkg 包自升级会用 deb 内的真身 ELF 覆盖 bin/dpkg（包装器
     * 旧部署点），此后 apt 直接调用裸 dpkg，官方 deb 的
     * data/data/com.termux tar 成员按绝对路径落盘，全部安装报
     * "unable to stat './data/data/com.termux': Permission denied"。
     * apt.conf.d/99anwind 不属于任何软件包，apt/dpkg 升级都不会覆盖；
     * apt 读取 apt.conf.d 时后读的文件优先生效，99_ 前缀保证排序最后。
     */
    private fun writeAptDpkgPin(context: Context) {
        val confDir = File(TermuxEnvironment.etcDir(context), "apt/apt.conf.d")
        confDir.mkdirs()
        val wrapper = File(TermuxEnvironment.prefixDir(context), "libexec/anwind/dpkg")
        val content = buildString {
            appendLine("// AnWind: apt/pkg 固定经由 dpkg 包装器（官方 deb 前缀重打包）。")
            appendLine("// dpkg 包自升级会用新真身覆盖 bin/dpkg（包装器副本），")
            appendLine("// 本文件保证 apt 永远走 libexec/anwind/dpkg，不受影响。")
            appendLine("Dir::Bin::dpkg \"${wrapper.absolutePath}\";")
        }
        try {
            File(confDir, "99anwind").writeText(content)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "写入 apt.conf.d/99anwind 失败: ${e.message}")
        }
    }

    /**
     * dpkg 兜底配置（fix8）：写入 etc/dpkg/dpkg.cfg.d/99-anwind-fix 与
     * etc/apt/apt.conf.d/99-anwind-fix（path-exclude + force-confold）。
     *
     * 背景：官方 deb 中偶有未经 anwind-debfix 重写的
     * /data/data/com.termux 成员（典型如裸目录条目
     * ./data/data/com.termux，无尾斜杠），dpkg 以 instdir=/ 按成员路径
     * lstat 时对官方包名前缀无权访问，报 "unable to stat ... Permission
     * denied"。path-exclude 让 dpkg 直接跳过这些成员（它们本就不该落
     * 入本应用沙箱，跳过无副作用）；force-confold 自动保留本地配置
     * 文件，消除 bash.bashrc / profile 升级时的 Y/I/N/O/D/Z 交互。
     *
     * 双保险：dpkg.cfg.d 由 dpkg 真身直接读取；apt.conf.d 令 apt 在
     * 命令行补同样参数，即使 dpkg.cfg.d 意外丢失也兜得住。
     *
     * ⚠ 两份配置含 com.termux 字面量，【不能】打进 bootstrap zip——
     * 解压时 rewriteLegacyPaths 会把 com.termux 改写为 com.anwind 使
     * 配置失效，只能在运行时写入。同样内容的三路写入互为兜底：
     * 本函数（全新安装 + 存量迁移 rev 5）、profile.d/anwind.sh
     * （每次会话启动）、anwind-dpkg 包装器（每次 dpkg 调用，缺失才写）。
     */
    private fun writeDpkgPathExclude(context: Context) {
        val dpkgCfg = File(TermuxEnvironment.etcDir(context), "dpkg/dpkg.cfg.d/99-anwind-fix")
        val aptCfg = File(TermuxEnvironment.etcDir(context), "apt/apt.conf.d/99-anwind-fix")
        try {
            dpkgCfg.parentFile?.mkdirs()
            dpkgCfg.writeText(buildString {
                appendLine("# AnWind (com.anwind) dpkg 兜底修复（fix8.5）")
                appendLine("# 跳过官方 deb 中漏经重写的 /data/data/com.termux 成员")
                appendLine("# （如裸目录条目），否则 dpkg 落盘时报 Permission denied。")
                appendLine("path-exclude=/data/data/com.termux")
                appendLine("path-exclude=/data/data/com.termux/*")
                appendLine("force-confold")
            })
            aptCfg.parentFile?.mkdirs()
            aptCfg.writeText(buildString {
                appendLine("// AnWind (com.anwind) dpkg 兜底修复（fix8.5）——双保险")
                appendLine("// 即使 etc/dpkg/dpkg.cfg.d/99-anwind-fix 丢失，apt 调起的")
                appendLine("// dpkg 也带同样的 path-exclude / force-confold。")
                appendLine("DPkg::Options:: \"--path-exclude=/data/data/com.termux\";")
                appendLine("DPkg::Options:: \"--path-exclude=/data/data/com.termux/*\";")
                appendLine("DPkg::Options:: \"--force-confold\";")
                // fix8.5：apt 级独立第二路径——dpkg 前把全部 deb 交给 debfix 重写
                appendLine("DPkg::Pre-Install-Pkgs:: \"/data/data/com.anwind/files/usr/bin/anwind-debfix\";")
            })
        } catch (e: Exception) {
            android.util.Log.w(TAG, "写入 dpkg path-exclude 配置失败: ${e.message}")
        }
    }

    /**
     * 首选镜像仓库根（清华 TUNA）。
     *
     * 选它有三个原因：pool 级下载稳定；对国内网络速度快；
     * 域名以 .cn 结尾，老版 termux-tools 的 pkg select_mirror 见到
     * .cn 源会直接跳过轮换，避免再次被加权随机切到坏镜像。
     */
    private const val PREFERRED_MIRROR_ROOT =
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt"

    /**
     * 存量安装的 apt 源修复（纯文本替换、不联网）。
     *
     * 背景：老版 termux-tools 的 pkg select_mirror 只测 dists/Release
     * 就把源加权轮换到 packages-cf.termux.org（Cloudflare），该镜像
     * dists 可读、pool 目录的 .deb 却一律 403 Forbidden——apt update 正常、
     * 所有包下载全部失败，pkg update 与 anwind-glibc 因此报错。
     *
     * 这里把 sources.list 中已知的坏源/老源/轮换源统一重写到
     * [PREFERRED_MIRROR_ROOT]（.cn 域名同时让轮换永久跳过本源）。
     * pool 级验证与 sources.list.d 附加源（gpkg）同步由
     * bin/anwind-mirror 负责（anwind-glibc 安装前自动调用）。
     */
    private fun fixAptSources(context: Context) {
        val list = File(TermuxEnvironment.prefixDir(context), "etc/apt/sources.list")
        if (!list.isFile) return
        val old = try {
            list.readText()
        } catch (_: Exception) {
            return
        }
        val root = PREFERRED_MIRROR_ROOT
        val mainSuffix = "$root/termux-main"
        val new = old
            .replace(Regex("https?://packages-cf\\.termux\\.org/apt"), root)
            .replace(Regex("https?://packages\\.termux\\.org/apt"), root)
            .replace(Regex("https?://packages\\.termux\\.dev/apt"), root)
            .replace(Regex("https?://deb\\.kcubeterm\\.me/termux-main"), mainSuffix)
            .replace(Regex("https?://termux\\.mentality\\.rip/termux-main"), mainSuffix)
            .replace(Regex("https?://termux\\.librehat\\.com/apt/termux-main"), mainSuffix)
            .replace(Regex("https?://grimler\\.se/termux-packages-24"), mainSuffix)
        if (new != old) {
            try {
                list.writeText(new)
                android.util.Log.i(TAG, "apt 源已修复到 TUNA 镜像（原为坏镜像/轮换源）")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "apt 源修复写入失败: ${e.message}")
            }
        }
    }

    /** bin/dpkg 是否已是本安装器写入的包装器（防重复移动真身）。
     *  读取头部 512 字节匹配包装器标记（旧版仅 128 字节，会因脚本
     *  头注释较长而漏判，导致把包装器误当真身移走）。 */
    private fun isOurWrapper(f: File): Boolean {
        val head = f.inputStream().use { input ->
            val buf = ByteArray(512)
            val n = input.read(buf)
            if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
        }
        return head.contains("anwind-dpkg") || head.contains("dpkg.real")
    }

    /**
     * 存量安装的增量迁移（免清数据）：bootstrap 已装但增强组件修订号
     * 落后时，重写 anwind.sh / motd（修复旧版本生成文件的语法问题）、
     * 部署 dpkg 包装器与重写工具，并对既有前缀做一次全量重写——清理
     * libapt-pkg.so 里 /data/data/com.termux/cache 等旧版仅重写 files/
     * 前缀时遗漏的路径（正是 "E: Archives directory ... Permission
     * denied" 报错的根因）。幂等：完成后写入修订号。
     */
    fun migrateIfNeeded(context: Context) {
        if (!isInstalled(context)) return
        if (isRevisionCurrent(context)) {
            _extrasReady.value = true
            return
        }
        synchronized(installLock) {
            if (isRevisionCurrent(context)) {
                _extrasReady.value = true
                return
            }
            try {
                // rev13 顺序修正：先跑全量重写，再部署审定脚本。旧顺序
                // （先部署后重写）下，若用户装过官方 termux-x11-nightly，
                // bin/termux-x11 属包清单成员，--full 的内容改写会把刚
                // 部署的内置客户端里 com.termux.x11.CmdEntryPoint 类名
                // 改成不存在的 com.anwind.x11.*——X11 启动必崩（用户实测）。
                // 先重写存量树（历史残留/被覆盖脚本一并清理），随后部署的
                // 脚本不再被任何重写波及（部署内容本就按 com.anwind 编写，
                // 其中 com.termux.x11.CmdEntryPoint 是宿主 APK 真实类名，
                // 属合法保留字串）。
                runReprefix(context, listOf("--full"))
                installPackageToolchain(context)
                installAnWindExtras(context)
                revisionFile(context).writeText("$EXTRAS_REVISION\n")
                android.util.Log.i(TAG, "Termux extras migrated to revision $EXTRAS_REVISION")
            } catch (e: Exception) {
                // 迁移失败不永久阻塞终端（降级为旧行为，bootstrap 本体完好）
                android.util.Log.e(TAG, "extras migration failed", e)
            } finally {
                _extrasReady.value = true
            }
        }
    }

    private fun isRevisionCurrent(context: Context): Boolean {
        val rev = revisionFile(context)
        return rev.isFile && rev.readText().trim() == EXTRAS_REVISION.toString()
    }

    private fun revisionFile(context: Context): File =
        File(TermuxEnvironment.prefixDir(context), "var/lib/anwind/install-revision")

    /** 执行原生重写工具（失败不抛出——重写是尽力而为的安全网）。 */
    private fun runReprefix(context: Context, args: List<String>) {
        try {
            val bin = File(TermuxEnvironment.binDir(context), "anwind-reprefix")
            if (!bin.isFile || !bin.canExecute()) return
            val process = ProcessBuilder(listOf(bin.absolutePath) + args).start()
            process.inputStream.use { it.readBytes() }
            process.errorStream.use { it.readBytes() }
            process.waitFor()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "anwind-reprefix 执行失败: ${e.message}")
        }
    }

    /** 从 assets 拷贝脚本并按需赋予执行权限（内容为发布时审定的定稿）。 */
    private fun copyAssetScript(
        context: Context,
        assetName: String,
        dest: File,
        executable: Boolean
    ) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (executable) Os.chmod(dest.absolutePath, PERMISSION_0700)
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
