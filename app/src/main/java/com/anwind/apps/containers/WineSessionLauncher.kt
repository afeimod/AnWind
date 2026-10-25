package com.anwind.apps.containers

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.anwind.apps.terminal.termux.TermuxEnvironment
import com.anwind.apps.x11.X11WindowController
import com.anwind.termux.terminal.TerminalSession
import com.anwind.termux.terminal.TerminalSessionClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 *  4. 会话日志（v2.24）：每次运行 wine/exe 全程留痕 —— Download/AnWind
 *     下按时间戳建 wine-<容器>-<时间>.log，整条会话（bash →
 *     anwind-container → anwind-wine → wine）的 stdout/stderr 经 tee
 *     同步落盘 + 终端回显；anwind-wine 识别 ANWIND_SESSION_LOG 复用同一
 *     文件并补记环境决策链；退出码由 App 侧补记。生命周期事件同步打
 *     logcat（TAG=AnWindWine）。公共 Download 不可写时退回 App 外部私有
 *     目录，全部失败则不接 tee（仅 lastLogs/logcat，零功能回归）。
 *
 * 容器解析/后端选择/DISPLAY 探测/PulseAudio 注入均由脚本侧完成，
 * App 端只负责拼装环境与展示。
 */
object WineSessionLauncher {

    /** 允许的最大并发会话数（超龄会话先回收）。 */
    private const val MAX_SESSIONS = 8

    /** X 服务自动拉起的去抖窗口（冷启动 socket 未就绪期间防重复）。 */
    private const val SERVICE_START_DEBOUNCE_MS = 8000L

    /** wine 会话生命周期的 logcat TAG。 */
    private const val TAG = "AnWindWine"

    /** 会话日志保留份数（wine-*.log 按修改时间轮转，超限删最旧）。 */
    private const val LOG_KEEP = 30

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
            val msg = "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
            logSpawnFailure(context, container.name, "runExe", msg)
            return msg
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
            val msg = "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
            logSpawnFailure(context, container.name, "runWineCmd", msg)
            return msg
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
            val rc = pb.start().waitFor()
            Log.i(TAG, "stopContainer '$name' rc=$rc")
            null
        } catch (e: Exception) {
            Log.w(TAG, "stopContainer '$name' failed: ${e.message}")
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
            val msg = "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
            logSpawnFailure(context, "__install__", "installWine", msg)
            return msg
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
        } catch (e: Exception) {
            // 服务拉起失败不阻断流程：等待页持续重试，doctor 可诊断
            Log.w(TAG, "ensureX11Service failed (non-blocking): ${e.message}")
        }
        try {
            X11WindowController.openWindow(context)
        } catch (e: Exception) {
            // X11 窗口未就绪时 wine 会连接失败，doctor 可诊断；
            // 此处仅 logcat 留痕，避免阻断启动流程
            Log.w(TAG, "X11WindowController.openWindow failed (non-blocking): ${e.message}")
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
        if (!client.isFile) {
            Log.w(TAG, "X11 client script missing: ${client.absolutePath}")
            return                     // 组件未迁移完成
        }

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
        Log.i(TAG, "X11 service autostart triggered (:1), log: ${log.absolutePath}")
    }

    /** 当前是否存在可用的 X11 socket（App 侧）。 */
    fun x11SocketReady(): Boolean {
        val dir = File(ContainerManager.X11_SOCK_DIR)
        return dir.isDirectory &&
            (dir.listFiles { f -> f.name.startsWith("X") }?.isNotEmpty() == true)
    }

    // ------------------------------------------------------------------
    // 会话日志（v2.24：wine/exe 运行全程留痕，排障不再靠猜）
    // ------------------------------------------------------------------

    /**
     * 会话日志目录：
     *  1. 公共 Download/AnWind —— 用户用任意文件管理器可直接取走
     *     （需已授予「所有文件访问」，与本应用文件管理器同权限）；
     *  2. 不可写时退回 App 外部私有目录（无需任何权限：
     *     /storage/emulated/0/Android/data/com.anwind/files/wine-logs）。
     * 每次会话实时探测（自愈：用户补授权后立即回到公共 Download）。
     */
    private fun sessionLogDir(context: Context): File? {
        val primary = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AnWind"
        )
        if (probeWritableDir(primary)) return primary
        val fallback = context.getExternalFilesDir(null)
            ?.let { File(it, "wine-logs") }
        if (fallback != null && probeWritableDir(fallback)) return fallback
        return null
    }

    /** 真实建删探针文件（FUSE 上 canWrite() 不可靠）。 */
    private fun probeWritableDir(dir: File): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val probe = File(dir, ".anwind-probe")
        val created = probe.createNewFile()
        if (created) probe.delete()
        created || probe.canWrite()
    }.getOrDefault(false)

    /** 新建会话日志文件（容器名 + 时间戳），并按 mtime 轮转旧日志。 */
    private fun newSessionLog(context: Context, containerName: String): File? = runCatching {
        val dir = sessionLogDir(context) ?: return@runCatching null
        rotateLogs(dir)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val safe = containerName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40).ifBlank { "default" }
        File(dir, "wine-$safe-$stamp.log").apply { createNewFile() }
    }.getOrNull()

    /** 只轮转本机制产生的 wine-*.log，保留最新 LOG_KEEP 份。 */
    private fun rotateLogs(dir: File) {
        val logs = dir.listFiles { f -> f.isFile && f.name.startsWith("wine-") && f.name.endsWith(".log") }
            ?: return
        if (logs.size <= LOG_KEEP) return
        logs.sortedByDescending { it.lastModified() }
            .drop(LOG_KEEP)
            .forEach { runCatching { it.delete() } }
    }

    private fun appendToFile(file: File, text: String) {
        runCatching { file.appendText(text) }
    }

    /** 未起会话的错误（rootfs 未就绪 / bash 缺失等）也要留痕。 */
    private fun logSpawnFailure(context: Context, containerName: String, where: String, message: String) {
        Log.w(TAG, "[$where/$containerName] $message")
        val f = newSessionLog(context, containerName) ?: return
        appendToFile(
            f,
            "===== AnWind wine 会话失败 ${nowStamp()} =====\n" +
                "容器: $containerName\n" +
                "阶段: $where\n" +
                "结果: $message\n\n"
        )
    }

    private fun nowStamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun appVersionLine(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        "com.anwind ${info.versionName ?: "?"}"
    }.getOrDefault("com.anwind ?")

    /** shell 参数引用：单引号包裹（内嵌单引号转义，路径含空格也安全）。 */
    private fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------
    // 会话孵化（headless TerminalSession）
    // ------------------------------------------------------------------

    /**
     * 以 App 侧 bash -c 执行命令，输出写入 lastLogs 并全程落盘会话日志。
     *
     * 环境：TermuxEnvironment 标准环境 + ANWIND_CONTAINER +
     * rootfs bin 前置 PATH（与 profile.d/anwind-container.sh 一致）。
     *
     * v2.24 会话日志：Download/AnWind 下按时间戳建 wine-<容器>-<时间>.log，
     * 启动头（容器/命令/App 版本/设备）由 App 写入，整条会话（bash →
     * anwind-container → anwind-wine → wine）的 stdout/stderr 经进程替换
     * tee 同步落盘 + 终端回显（保留 exec 语义，会话 pid/信号/kill 行为
     * 不变）；anwind-wine 识别 ANWIND_SESSION_LOG 复用同一文件并补记环境
     * 决策链；退出码在 onSessionFinished 补记。进程替换自检失败时按原样
     * 直跑（零功能回归）。公共 Download 不可写时退回 App 外部私有目录，
     * 全部失败则不接 tee（输出仍进 lastLogs 与 logcat）。
     */
    private fun spawn(context: Context, containerName: String, shellCmd: String): String? {
        // X11 桌面窗口先就位（X11 显示方案联动；同步自动拉起 X 服务）
        ensureX11Window(context)

        // v2.26：容器脚本覆盖安装（assets/anwind/scripts → rootfs/usr/bin）
        // 每次会话前执行，保证脚本与 APK 版本一致（幂等，仅 rootfs 就绪时生效）
        runCatching {
            com.anwind.apps.terminal.termux.AnWindScriptAssets
                .deployScriptsIfNeeded(context.applicationContext)
        }.onFailure {
            Log.w(TAG, "deployScriptsIfNeeded failed: ${it.message}")
        }

        // wineserver 运行时目录兜底：wine 按 termux 风格构建，把 /tmp 硬编码为
        // $PREFIX/tmp（= rootfs/usr/tmp），启动时要在其下创建 .wine-<uid>；
        // 部分用户导入的 rootfs 缺这个空目录 → "wineserver: mkdir .../.wine-xxx:
        // No such file or directory"。App 进程与 rootfs 同 UID，直接补建。
        // App 侧 X11 socket 目录同理（wine 客户端经它连接内置 X 服务）。
        runCatching {
            File("${ContainerManager.ROOTFS_ROOT}/usr/tmp").mkdirs()
            File("${ContainerManager.APP_PREFIX}/tmp/.X11-unix").mkdirs()
        }.onFailure {
            Log.w(TAG, "runtime dirs mkdir failed: ${it.message}")
        }

        val prefix = TermuxEnvironment.prefixPath(context)
        val bash = File("$prefix/bin/bash")
        if (!bash.isFile) {
            val msg = "未找到 App 侧 bash：$prefix/bin/bash"
            logSpawnFailure(context, containerName, "spawn", msg)
            return msg
        }

        // ---- v2.24 会话日志：建文件 + 接管整条会话输出 ----
        val sessionLog = newSessionLog(context, containerName)
        val logPath = sessionLog?.absolutePath
        // 进程替换自检：不可用（无 /dev/fd 且 FIFO 兜底失败）时按原样直跑
        // （exec 语义不变，仅本次不落盘）——零功能回归
        val sessionCmd = if (logPath == null) shellCmd else
            "if bash -c ': > >(true)' 2>/dev/null; then " +
                "$shellCmd > >(tee -a ${shSingleQuote(logPath)}) 2>&1; " +
                "else $shellCmd; fi"
        if (sessionLog != null) {
            appendToFile(
                sessionLog,
                buildString {
                    append("===== AnWind wine 会话 ${nowStamp()} =====\n")
                    append("容器: $containerName\n")
                    append("命令: $shellCmd\n")
                    append("日志: $logPath\n")
                    append("App: ${appVersionLine(context)}\n")
                    append("设备: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.MODEL})\n")
                    append('\n')
                }
            )
            Log.i(TAG, "[$containerName] session log -> $logPath")
        } else {
            Log.w(
                TAG,
                "[$containerName] no writable log dir (Download/AnWind and fallback both failed); " +
                    "output only in lastLogs/logcat"
            )
        }

        val env = mutableListOf<String>().apply {
            addAll(TermuxEnvironment.buildEnvironment(context, isFailSafe = false))
            add("ANWIND_CONTAINER=$containerName")
            // anwind-wine 见此变量即复用同一日志文件（补记环境决策链，
            // 不再自建 tee，避免双写）
            logPath?.let { add("ANWIND_SESSION_LOG=$it") }
            // 与 rootfs profile.d 注入一致的 PATH（rootfs bin 优先）
            add("PATH=${ContainerManager.ROOTFS_ROOT}/usr/bin:${TermuxEnvironment.prefixPath(context)}/bin")
            // v2.26：X11 显示号立即固定 :1 —— 容器启动即面向内置 X
            // 服务（anwind-wine 侧同名兑底，此处保证 CLI 阶段也生效），
            // 不再等探测/等待页；环境隔离的最终清洗由 anwind-wine
            // isolate_env 完成（PREFIX/HOME/LD_LIBRARY_PATH/LANG/音频）
            add("DISPLAY=:1")
        }

        val args = arrayOf("-c", sessionCmd)
        val processArgs = TermuxEnvironment.setupProcessArgs(context, bash.absolutePath, args)
        val executable = processArgs.first()

        // 同名旧会话先回收
        activeSessions.remove(containerName)?.finishIfRunning()

        val log = StringBuilder("[AnWind] $shellCmd\n")
        // 日志文件路径进面板首行：用户在"Wine 输出"里即可看到去哪取完整日志
        if (logPath != null) log.append("[AnWind] 会话日志: $logPath\n")
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
            HeadlessClient(containerName, sessionLog)
        )
        // initializeEmulator 内部即完成子进程孵化（JNI.createSubprocess），
        // 无需额外 start 调用
        session.initializeEmulator(80, 24)
        Log.i(TAG, "[$containerName] session started, pid=${session.pid}, log=${logPath ?: "-"}")

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
     * 输出变化时把缓冲尾部追加到 lastLogs，退出时打标记；
     * 会话日志文件由外部（spawn）创建并接 tee，这里只补记退出码。
     */
    private class HeadlessClient(
        private val key: String,
        private val logFile: File?
    ) : TerminalSessionClient {

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
            val code = if (finishedSession.isRunning) "?" else "${finishedSession.exitStatus}"
            lastLogs[key]?.append("\n[进程退出码: $code]\n")
            // 退出码补记进会话日志文件（tee 侧无生命周期概念，这里收尾；
            // 根因定位三要素齐了：启动头 + 全程输出 + 退出码）
            logFile?.let {
                appendToFile(it, "\n[进程退出码: $code] 会话结束 ${nowStamp()}\n\n")
            }
            Log.i(TAG, "session '$key' finished, exitCode=$code, log=${logFile?.absolutePath ?: "-"}")
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

        override fun logError(tag: String, message: String) { Log.e(tag, message) }
        override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
        override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
        override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
        override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
            Log.e(tag, message, e)
        }
        override fun logStackTrace(tag: String, e: Exception) {
            Log.e(tag, "uncaught", e)
        }
    }
}
