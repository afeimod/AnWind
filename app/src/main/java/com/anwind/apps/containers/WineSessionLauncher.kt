package com.anwind.apps.containers

import android.content.Context
import android.os.SystemClock
import com.anwind.apps.terminal.termux.TermuxEnvironment
import com.anwind.apps.x11.X11WindowController
import com.anwind.termux.terminal.TerminalSession
import com.anwind.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * AnWind Wine 容器 — 启动器（App 侧）。
 *
 * 职责：
 *  1. 拉起/聚焦 AnWind 内置 X11 桌面窗口（X11 方案：X server = App 内嵌
 *     termux-x11/libXlorie，socket 在 $PREFIX/tmp/.X11-unix/Xn，默认 :1）；
 *  2. 以 App 侧 bash 执行 rootfs 内 anwind-wine / anwind-container 脚本
 *     （bionic 模型：rootfs 内二进制直接原生运行，无需 chroot/proot）；
 *  3. 用无界面（headless）TerminalSession 承载 wine 进程，日志可在
 *     "Wine 输出"面板查看；wineserver 随会话存活，可用"停止"一键关闭。
 *
 * 容器解析/后端选择/DISPLAY 探测/PulseAudio 注入均由脚本侧完成，
 * App 端只负责拼装环境与展示。
 */
object WineSessionLauncher {

    /** 允许的最大并发会话数（超龄会话先回收）。 */
    private const val MAX_SESSIONS = 8

    /** X 服务自动拉起的去抖窗口（冷启动 socket 未就绪期间防重复）。 */
    private const val SERVICE_START_DEBOUNCE_MS = 8000L

    /** 活跃会话表：容器名 → 会话（供"停止"与状态展示）。 */
    val activeSessions = LinkedHashMap<String, TerminalSession>()

    /** 每容器最近一次启动的日志（简易环形，UI 查看）。 */
    val lastLogs = LinkedHashMap<String, StringBuilder>()

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 在指定容器中运行 exe（等价终端 `anwind-container run <name> <exe>`）。
     *
     * @param exe    Windows 程序路径（容器内 C: 相对路径或绝对路径均可）
     * @param args   传给 exe 的参数
     * @return null 表示已拉起；否则错误信息（rootfs 未就绪等）
     */
    fun runExe(context: Context, container: ContainerData, exe: String, args: String = ""): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
        }
        val cmd = buildString {
            append("exec ${ContainerManager.CLI} run ")
            append(shellQuote(container.name)).append(' ')
            append(shellQuote(exe))
            if (args.isNotBlank()) append(' ').append(args.trim())
        }
        return spawn(context, container.name, cmd)
    }

    /**
     * 运行 wine 子命令（wineboot / winecfg / reg / cmd ...）。
     * 等价终端 `anwind-container cmd <name> <winecmd> [...]`。
     */
    fun runWineCmd(context: Context, container: ContainerData, wineCmd: String): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
        }
        val cmd = "exec ${ContainerManager.CLI} cmd " +
            "\"${container.name}\" ${wineCmd.trim()}"
        return spawn(context, container.name, cmd)
    }

    /** 首次初始化前缀（wineboot -i）。 */
    fun initializePrefix(context: Context, container: ContainerData): String? =
        runWineCmd(context, container, "wineboot -i")

    /** 打开 winecfg 配置面板。 */
    fun openWineCfg(context: Context, container: ContainerData): String? =
        runWineCmd(context, container, "winecfg")

    /** 打开注册表编辑器。 */
    fun openRegEdit(context: Context, container: ContainerData): String? =
        runWineCmd(context, container, "regedit")

    /**
     * 停止容器内全部 wine 进程（wineserver -k）。
     * 前台调用时弹 toast 提示；失败静默（容器可能本来就没在跑）。
     */
    fun stopContainer(context: Context, name: String): String? {
        activeSessions.remove(name)?.finishIfRunning()
        lastLogs.remove(name)
        if (!ContainerManager.rootfsReady()) return null
        return try {
            val pb = ProcessBuilder(
                "${ContainerManager.APP_PREFIX}/bin/bash", "-c",
                "PATH=${ContainerManager.ROOTFS_ROOT}/usr/bin:\$PATH " +
                    "exec ${ContainerManager.CLI} stop \"${name}\""
            )
            pb.environment()?.put("ANWIND_CONTAINER", name)
            pb.start().waitFor()
            null
        } catch (e: Exception) {
            "停止失败：${e.message}"
        }
    }

    /** 供 ContainerManager 内部调用的静默停止（不弹任何 UI）。 */
    fun stopContainerQuietly(name: String) {
        try {
            activeSessions.remove(name)?.finishIfRunning()
            lastLogs.remove(name)
            val f = java.io.File(ContainerManager.CLI)
            if (f.isFile) {
                ProcessBuilder(
                    "${ContainerManager.APP_PREFIX}/bin/bash", "-c",
                    "PATH=${ContainerManager.ROOTFS_ROOT}/usr/bin:\$PATH " +
                        "exec ${ContainerManager.CLI} stop \"${name}\" 2>/dev/null"
                ).start()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 通过 CLI 安装 wine 发行包（v2.27：支持构建目录类别）。
     *
     * @param src 类别名（bionic-x86_64 / bionic-arm64ec / wine-x86_64 /
     *            wine-arm64 / proton，由 CLI wine-catalog 解析最新 Release
     *            资产并自动命名槽位）、完整 URL 或本地包路径
     * @param name 槽位名（null = CLI 自动从资产名取）
     * @param mkDefault 是否同时设为全局默认 wine
     * 走前台会话以便用户看到 解析/下载（直连失败自动走加速镜像）/解包 进度。
     */
    fun installWine(
        context: Context,
        src: String,
        name: String? = null,
        mkDefault: Boolean = false
    ): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
        }
        val placeholder = ContainerData(name = "__install__")
        val cmd = buildString {
            append("exec ${ContainerManager.CLI} install-wine ")
            append(shellQuote(src.trim()))
            if (!name.isNullOrBlank()) append(" --name ").append(shellQuote(name.trim()))
            if (mkDefault) append(" --default")
        }
        return spawn(context, placeholder.name, cmd)
    }

    // ------------------------------------------------------------------
    // X11 桌面联动
    // ------------------------------------------------------------------

    /**
     * 确保 X11 桌面窗口处于打开状态（X11 显示方案的核心联动点）。
     *
     * AnWind 的 X server 即 App 内嵌的 termux-x11（libXlorie）：
     * 打开 X11 桌面窗口 → X 服务就绪 → 容器内 wine 经
     * $PREFIX/tmp/.X11-unix/Xn 直连。无需 Weston/Wayland。
     *
     * v2.26：启动容器时同步自动拉起 X 服务（若尚未运行）——
     * 显示号固定 :1，不再依赖用户先在终端手敲 anwind-x11，
     * 也不再让 X11 窗口停在"等待连接页"（等待页不再提供
     * 兼容全屏入口，兼容 Activity 会抢走连接 fd 导致黑屏）。
     */
    fun ensureX11Window(context: Context) {
        try {
            ensureX11Service(context)
        } catch (_: Exception) {
            // 服务拉起失败不阻断流程：等待页持续重试，doctor 可诊断
        }
        try {
            X11WindowController.openWindow(context)
        } catch (_: Exception) {
            // X11 窗口未就绪时 wine 会连接失败，doctor 可诊断；
            // 此处静默，避免阻断启动流程
        }
    }

    private var lastServiceStartAt = 0L

    /**
     * 自动拉起内置 X 服务（显示号固定 :1）。
     *
     * 判定：App 侧 X11 socket（tmp/.X11-unix/X1）已存在 → 服务在跑，
     * 直接复用；否则后台执行内置客户端脚本
     *   $PREFIX/bin/termux-x11 :1
     * （脚本内部自取宿主 APK CLASSPATH + XKB 数据并经 app_process
     * 启动 CmdEntryPoint，与终端 anwind-x11 完全同源），输出写入
     * $PREFIX/tmp/anwind-x11-autostart.log 供排障。
     * 8 秒去抖防重复拉起（socket 未就绪的冷启动窗口期）。
     */
    private fun ensureX11Service(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val sock = File("${ContainerManager.X11_SOCK_DIR}/X1")
        if (sock.exists()) return                      // 服务已就绪
        if (now - lastServiceStartAt < SERVICE_START_DEBOUNCE_MS) return
        lastServiceStartAt = now

        val prefix = TermuxEnvironment.prefixPath(context)
        val client = File("$prefix/bin/termux-x11")
        val sh = File("$prefix/bin/sh")
        if (!client.isFile) return                     // 组件未迁移完成

        val log = File("$prefix/tmp/anwind-x11-autostart.log")
        log.parentFile?.mkdirs()
        val pb = ProcessBuilder(
            sh.absolutePath, client.absolutePath, ":1"
        )
        // 输出全部落日志文件（不用 PIPE：X 服务常驻进程写满管道缓冲会卡死）
        pb.redirectErrorStream(true)
        pb.redirectOutput(log)
        // 补全脚本运行所需的最小环境（脚本内部自处理 CLASSPATH/XKB）
        pb.environment()?.apply {
            putIfAbsent("PATH", "$prefix/bin:/system/bin")
            putIfAbsent("HOME", TermuxEnvironment.homePath(context))
            putIfAbsent("TMPDIR", "$prefix/tmp")
        }
        pb.start()   // 脚本自身判断重复启动（anwindx11 进程探测），无需等待
    }

    /** 当前是否存在可用的 X11 socket（App 侧）。 */
    fun x11SocketReady(): Boolean {
        val dir = File(ContainerManager.X11_SOCK_DIR)
        return dir.isDirectory &&
            (dir.listFiles { f -> f.name.startsWith("X") }?.isNotEmpty() == true)
    }

    // ------------------------------------------------------------------
    // 会话孵化（headless TerminalSession）
    // ------------------------------------------------------------------

    /**
     * 以 App 侧 bash -c 执行命令，输出写入 lastLogs。
     *
     * 环境：TermuxEnvironment 标准环境 + ANWIND_CONTAINER +
     * rootfs bin 前置 PATH（与 profile.d/anwind-container.sh 一致）。
     */
    private fun spawn(context: Context, containerName: String, shellCmd: String): String? {
        // X11 桌面窗口先就位（X11 显示方案联动；同步自动拉起 X 服务）
        ensureX11Window(context)

        // v2.26：容器脚本覆盖安装（assets/anwind/scripts → rootfs/usr/bin）
        // 每次会话前执行，保证脚本与 APK 版本一致（幂等，仅 rootfs 就绪时生效）
        runCatching {
            com.anwind.apps.terminal.termux.AnWindScriptAssets
                .deployScriptsIfNeeded(context.applicationContext)
        }

        // wineserver 运行时目录兜底：wine 按 termux 风格构建，把 /tmp 硬编码为
        // $PREFIX/tmp（= rootfs/usr/tmp），启动时要在其下创建 .wine-<uid>；
        // 部分用户导入的 rootfs 缺这个空目录 → "wineserver: mkdir .../.wine-xxx:
        // No such file or directory"。App 进程与 rootfs 同 UID，直接补建。
        // App 侧 X11 socket 目录同理（wine 客户端经它连接内置 X 服务）。
        runCatching {
            File("${ContainerManager.ROOTFS_ROOT}/usr/tmp").mkdirs()
            File("${ContainerManager.APP_PREFIX}/tmp/.X11-unix").mkdirs()
        }

        val prefix = TermuxEnvironment.prefixPath(context)
        val bash = File("$prefix/bin/bash")
        if (!bash.isFile) return "未找到 App 侧 bash：$prefix/bin/bash"

        val env = mutableListOf<String>().apply {
            addAll(TermuxEnvironment.buildEnvironment(context, isFailSafe = false))
            add("ANWIND_CONTAINER=$containerName")
            // 与 rootfs profile.d 注入一致的 PATH（rootfs bin 优先）
            add("PATH=${ContainerManager.ROOTFS_ROOT}/usr/bin:${TermuxEnvironment.prefixPath(context)}/bin")
            // v2.26：X11 显示号立即固定 :1 —— 容器启动即面向内置 X
            // 服务（anwind-wine 侧同名兑底，此处保证 CLI 阶段也生效），
            // 不再等探测/等待页；环境隔离的最终清洗由 anwind-wine
            // isolate_env 完成（PREFIX/HOME/LD_LIBRARY_PATH/LANG/音频）
            add("DISPLAY=:1")
        }

        val args = arrayOf("-c", shellCmd)
        val processArgs = TermuxEnvironment.setupProcessArgs(context, bash.absolutePath, args)
        val executable = processArgs.first()

        // 同名旧会话先回收
        activeSessions.remove(containerName)?.finishIfRunning()

        val log = StringBuilder("[AnWind] $shellCmd\n")
        lastLogs[containerName] = log
            .let { if (it.length > 4096) StringBuilder(it.substring(it.length - 4096)) else it }

        val session = TerminalSession(
            executable,
            TermuxEnvironment.homePath(context),
            // JNI.createSubprocess 直接把该数组用作 execvp() 的 argv（argv[0] = 程序名）。
            // 必须传含 argv[0] 的完整 processArgs；若 drop(1) 丢掉它，bash 将以
            // $0="-c" 启动（被解析为登录 shell 名而非 -c 选项），整条 shellCmd 被
            // 当作脚本文件名打开 → "No such file or directory" + exit 127。
            processArgs,
            env.toTypedArray(),
            TRANSCRIPT_ROWS,
            HeadlessClient(containerName)
        )
        // initializeEmulator 内部即完成子进程孵化（JNI.createSubprocess），
        // 无需额外 start 调用
        session.initializeEmulator(80, 24)

        activeSessions[containerName] = session
        trimSessions()
        return null
    }

    /** 回收最早结束/超量的会话。 */
    private fun trimSessions() {
        while (activeSessions.size > MAX_SESSIONS) {
            val eldest = activeSessions.entries.firstOrNull() ?: break
            eldest.value.finishIfRunning()
            activeSessions.remove(eldest.key)
        }
    }

    private const val TRANSCRIPT_ROWS = 200

    /** shell 参数引用：包裹双引号并转义内嵌双引号。 */
    private fun shellQuote(s: String): String =
        "\"" + s.replace("\"", "\\\"") + "\""

    // ------------------------------------------------------------------
    // headless 会话客户端（无终端视图，仅收集日志与退出状态）
    // ------------------------------------------------------------------

    /**
     * 最小 TerminalSessionClient 实现：
     * 输出变化时把缓冲尾部追加到 lastLogs，退出时打标记。
     */
    private class HeadlessClient(private val key: String) : TerminalSessionClient {

        private fun appendLog(session: TerminalSession) {
            val log = lastLogs[key] ?: return
            val emu = session.emulator
            if (emu == null) {
                log.append('\n')
                return
            }
            try {
                val rows = emu.mRows
                val cols = emu.mColumns
                for (r in 0 until rows) {
                    val line = emu.getSelectedText(0, r, cols - 1, r) ?: continue
                    log.append(line.trimEnd()).append('\n')
                }
                if (log.length > 16384) {
                    lastLogs[key] = StringBuilder(log.substring(log.length - 8192))
                }
            } catch (_: Exception) {
            }
        }

        override fun onTextChanged(changedSession: TerminalSession) = appendLog(changedSession)

        override fun onTitleChanged(changedSession: TerminalSession) {}

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val log = lastLogs[key] ?: return
            val code = if (finishedSession.isRunning) "?" else "${finishedSession.exitStatus}"
            log.append("\n[进程退出码: $code]\n")
            if (key != "__install__") {
                // 容器会话退出后清理登记，供 UI 刷新"运行中"状态
                activeSessions.remove(key)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(session: TerminalSession) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 0

        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }
}
