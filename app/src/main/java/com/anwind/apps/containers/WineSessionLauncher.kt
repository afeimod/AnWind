package com.anwind.apps.containers

import android.content.Context
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
     * 通过 CLI 安装 wine 发行包（CI Release 的 wine tarball）。
     * 走前台会话以便用户看到解包/安装进度。
     */
    fun installWine(context: Context, tarballOrUrl: String): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
        }
        val placeholder = ContainerData(name = "__install__")
        val cmd = "exec ${ContainerManager.CLI} install-wine \"${tarballOrUrl.trim()}\""
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
     */
    fun ensureX11Window(context: Context) {
        try {
            X11WindowController.openWindow(context)
        } catch (_: Exception) {
            // X11 窗口未就绪时 wine 会连接失败，doctor 可诊断；
            // 此处静默，避免阻断启动流程
        }
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
        // X11 桌面窗口先就位（X11 显示方案联动）
        ensureX11Window(context)

        val prefix = TermuxEnvironment.prefixPath(context)
        val bash = File("$prefix/bin/bash")
        if (!bash.isFile) return "未找到 App 侧 bash：$prefix/bin/bash"

        val env = mutableListOf<String>().apply {
            addAll(TermuxEnvironment.buildEnvironment(context, isFailSafe = false))
            add("ANWIND_CONTAINER=$containerName")
            // 与 rootfs profile.d 注入一致的 PATH（rootfs bin 优先）
            add("PATH=${ContainerManager.ROOTFS_ROOT}/usr/bin:${TermuxEnvironment.prefixPath(context)}/bin")
        }

        val args = arrayOf("-c", shellCmd)
        val processArgs = TermuxEnvironment.setupProcessArgs(context, bash.absolutePath, args)
        val executable = processArgs.first()
        val realArgs = processArgs.drop(1).toTypedArray()

        // 同名旧会话先回收
        activeSessions.remove(containerName)?.finishIfRunning()

        val log = StringBuilder("[AnWind] $shellCmd\n")
        lastLogs[containerName] = log
            .let { if (it.length > 4096) StringBuilder(it.substring(it.length - 4096)) else it }

        val session = TerminalSession(
            executable,
            TermuxEnvironment.homePath(context),
            realArgs,
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
