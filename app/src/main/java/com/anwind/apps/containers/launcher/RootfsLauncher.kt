package com.anwind.apps.containers.launcher

import android.content.Context
import android.os.Build
import com.anwind.apps.containers.ContainerAudio
import com.anwind.apps.containers.ContainerBackend
import com.anwind.apps.containers.ContainerData
import com.anwind.apps.containers.ContainerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 原生 rootfs 启动编排器 —— AnWind 原生启动架构的中枢。
 *
 * 对齐 WinFusion Launcher 的编排模型（resolve → 目录检查 → 显示就绪 →
 * 清残留会话 → 环境构建 → 前缀/注册表准备 → exec），但**显示环节是
 * X11**（[X11Support]：App 内嵌 termux-x11/libXlorie），不是 Weston/
 * Wayland —— 这是 AnWind 与 WinFusion 的方案差异，按需求保留 X11。
 *
 * 与旧「终端链路」的本质区别：wine 是 [NativeShellExecutor] 直接 exec
 * 的原生子进程，**不再存在 bash / anwind-wine 脚本 / PTY / 终端模拟器**
 * 任何一环。脚本侧的职责全部由本类的 Kotlin 步骤等价承接：
 *
 *  ```
 *   resolveContainer / resolveWineDir     → [WineRuntime.resolve]
 *   isolate_env                           → [WineEnvironment.buildBaseEnv] + GPU/Box64/音频叠加
 *   audio_setup                           → [PulseAudioManager.ensureServer]
 *   ensure_x11                            → [X11Support.ensureX11Window] + [X11Support.awaitSocketReady]
 *   首次 wineboot -i                       → [WineRegistry.initializePrefixIfNeeded]
 *   ensure_emulate_modeset                → [WineRegistry.ensureEmulateModeset]
 *   apply_display_mode / -f 清蓝底          → [applyDisplayMode]
 *   run_wine                              → [buildCommand] + NativeShellExecutor.exec
 *  ```
 *
 * 启动前清 wineserver 残留（对齐 WinFusion launch 链）：上次会话的
 * server 若还在，新客户端会连上旧会话 —— "第一次能启动，之后再也起
 * 不来"的根因；注册表内存缓存也是它，不清则后续注册表写入被无视。
 */
object RootfsLauncher {

    private const val TAG = "RootfsLauncher"

    /** 允许的最大并发会话数（超龄会话先回收）。 */
    private const val MAX_SESSIONS = 8

    /** 真实会话表：容器名（或 "__install__"）→ 会话。 */
    private val sessions = LinkedHashMap<String, WineSession>()

    /**
     * 启动链进行中的容器 → 世代令牌（同容器并发提交时最新令牌胜出，
     * 旧链在 exec 前自检令牌失配即放弃 —— 替代旧版"同名旧会话先回收"语义）。
     */
    private val pendingLaunches =
        java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * UI 会话视图：真实会话 + 启动中的容器（后者值为 null 占位，
     * containsKey 语义成立 —— 与旧版"spawn 即登记"的运行状态即时性一致）。
     */
    val activeSessions: Map<String, WineSession?>
        get() = synchronized(sessions) {
            val view = LinkedHashMap<String, WineSession?>(sessions)
            for (k in pendingLaunches.keys) view.putIfAbsent(k, null)
            view
        }

    /** 每容器最近一次启动的日志（简易环形，UI 查看）。 */
    val lastLogs = LinkedHashMap<String, StringBuilder>()

    /**
     * 启动链线程池：cached + daemon。链内含 X11 就绪等待 / wineserver
     * 收尾（最长 18s）/ 首次 wineboot（分钟级）—— 绝不能占主线程，
     * 也不能用会饿死阻塞任务的 commonPool。
     */
    private val LAUNCH_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "anwind-launcher").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------
    // 对外入口（由 WineSessionLauncher 门面转发）
    // ------------------------------------------------------------------

    /**
     * 在指定容器中运行 exe。
     *
     * 同步预检（rootfs 就绪 / wine 可解析）立即给出错误；通过后异步
     * 执行完整启动链，日志进 lastLogs。
     *
     * @param exe  Windows 程序路径（C: 相对或绝对）
     * @param args 传给 exe 的参数（空白切分，与旧 bash 词切分语义一致）
     */
    fun runExe(context: Context, container: ContainerData, exe: String, args: String): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
        }
        val wineArgs = buildList {
            add(exe)
            args.trim().takeIf { it.isNotEmpty() }?.let {
                addAll(it.split(Regex("\\s+")))
            }
        }
        return submitLaunch(context, container, wineArgs, freshSession = true)
    }

    /**
     * 运行 wine 子命令（wineboot -i / winecfg / regedit / cmd /
     * wineserver …）。参数空白切分后作为 wine argv 传入。
     */
    fun runWineCmd(context: Context, container: ContainerData, wineCmd: String): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
        }
        val wineArgs = wineCmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (wineArgs.isEmpty()) return "空的 wine 命令"
        // 附加语义：不打断运行中的会话（对齐旧脚本 anwind-container cmd）
        return submitLaunch(context, container, wineArgs, freshSession = false)
    }

    /**
     * 通过 rootfs CLI 安装 wine 发行包（bash 工具链：下载/解包 —— 不是
     * wine 运行时，保留脚本执行；但**不经 PTY**，用原生执行器承载，
     * 进度实时流式进 lastLogs["__install__"]）。
     */
    fun installWine(
        context: Context,
        src: String,
        slotName: String?,
        mkDefault: Boolean
    ): String? {
        if (!ContainerManager.rootfsReady()) {
            return "rootfs 未就绪：请先导入包含 anwind-container 的 bionic rootfs"
        }
        val bash = File("${ContainerManager.APP_PREFIX}/bin/bash")
        if (!bash.isFile) return "未找到 App 侧 bash：${ContainerManager.APP_PREFIX}/bin/bash"

        val command = buildList {
            add(bash.absolutePath)
            add(ContainerManager.CLI)
            add("install-wine")
            add(src.trim())
            slotName?.takeIf { it.isNotBlank() }?.let {
                add("--name"); add(it.trim())
            }
            if (mkDefault) add("--default")
        }

        val key = "__install__"
        val sink = SessionLogSink(key, newSessionLog(key))
        sink.log("[AnWind] 安装 wine：$src" + (slotName?.let { "（槽位 $it）" } ?: ""))
        // 安装进度实时进 lastLogs（UI "Wine 输出"面板读取）
        lastLogs[key] = sink.text

        try {
            val executor = NativeShellExecutor.create()
                .setCommand(*command.toTypedArray())
                .putEnv(installerEnv())
                .setWorkingDir(WineEnvironment.HOME_DIR)
                .onStdOut { sink.log(it) }
                .onStdErr { sink.log(it) }
                .onExit { code -> onSessionExit(key, code, sink) }
                .exec()
            registerSession(key, WineSession(key, executor, sink))
            return null
        } catch (e: Exception) {
            unregisterSession(key)
            return "安装进程启动失败：${e.message}"
        }
    }

    /**
     * 停止容器内全部 wine 进程。
     *
     * 顺序（对齐 WinFusion clean()）：先收 wine 客户端会话，再
     * wineserver -k / -w 让 server 自己 kill_all_processes() 收掉
     * 整棵进程树（services.exe / winedevice.exe / explorer / 游戏）。
     */
    fun stopContainer(context: Context?, name: String): String? {
        // 启动链进行中：摘牌（链尾 exec 前的令牌自检会放弃登记）
        pendingLaunches.remove(name)
        synchronized(sessions) { sessions.remove(name) }?.let { it.stopAndWait() }
        lastLogs.remove(name)
        if (!ContainerManager.rootfsReady()) return null
        return try {
            val container = ContainerManager.get(name)
            if (container != null) {
                val resolved = runCatching { WineRuntime.resolve(container) }.getOrNull()
                val env = WineEnvironment.buildBaseEnv(
                    container,
                    resolved?.wineDir ?: File(ContainerManager.WINE_OPT_DIR, "wine")
                )
                WineServerUtils.stop(resolved, container, env)
            }
            null
        } catch (e: Exception) {
            "停止失败：${e.message}"
        }
    }

    /** 静默停止（容器删除等内部路径调用；不产生任何 UI 反馈）。 */
    fun stopContainerQuietly(name: String) {
        runCatching { stopContainer(null, name) }
    }

    /** 当前是否存在可用的 X11 socket（App 侧）。 */
    fun x11SocketReady(): Boolean = X11Support.x11SocketReady()

    /** X11 桌面窗口联动（外部诊断/手动路径用）。 */
    fun ensureX11Window(context: Context) = X11Support.ensureX11Window(context)

    /**
     * 解析 exe 的工作目录（v3.1 新增）。
     *
     * 游戏几乎都按 CWD 相对路径找资源（DX9/DXUT 样例的 media\、各引擎
     * 的 data\ 等）。旧版把 CWD 写死为 rootfs/home，用户从 App 运行
     * /storage/…/CubeMap.exe 时 media 永远找不到 —— 终端时代用户会先
     * cd 到游戏目录再跑 wine，App 侧一直没有等价物，这就是回归。
     *
     * 规则：
     *  - Unix 绝对路径（/storage/…）→ exe 父目录（目录不存在则兑底 HOME）；
     *  - Windows 盘符路径（Z:\… / C:\…）→ 经 dosdevices 盘符链接映射回
     *    Unix 后取父目录（z: → /，c: → drive_c）；
     *  - 相对路径 / 子命令（winecfg 等）→ null（维持 HOME，与终端一致）。
     */
    internal fun resolveExeWorkingDir(exe: String?, container: ContainerData): File? {
        if (exe == null) return null
        val p = exe.trim()
        if (p.isEmpty()) return null
        if (p.startsWith("/")) {
            return File(p).parentFile?.takeIf { it.isDirectory }
        }
        val winPath = Regex("^([A-Za-z]):[\\\\/](.*)$").find(p) ?: return null
        val drive = winPath.groupValues[1].lowercase() + ":"
        val rest = winPath.groupValues[2].replace('\\', '/')
        val base = File(File(container.prefixPath, "dosdevices"), drive)
        if (!base.isDirectory) return null
        if (rest.isBlank()) return base
        return File(base, rest).parentFile?.takeIf { it.isDirectory } ?: base
    }

    // ------------------------------------------------------------------
    // 启动链
    // ------------------------------------------------------------------

    /**
     * 提交启动链：同步快速预检（立即反馈错误），异步执行完整链路。
     */
    private fun submitLaunch(
        context: Context,
        container: ContainerData,
        wineArgs: List<String>,
        freshSession: Boolean
    ): String? {
        // ---- 同步预检：wine 可解析（文件级检查，毫秒级） ----
        val precheck = try {
            WineRuntime.resolve(container)
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        if (precheck != null) return precheck

        val token = System.nanoTime()
        pendingLaunches[container.name] = token
        LAUNCH_EXECUTOR.execute {
            launch(context.applicationContext, container, wineArgs, token, freshSession)
        }
        return null
    }

    /**
     * 完整启动链（后台线程执行）。任何步骤失败 → 决策行进会话日志，
     * 会话登记撤销；绝不抛出（链路自身兜底）。
     */
    private fun launch(
        context: Context,
        container: ContainerData,
        wineArgs: List<String>,
        token: Long,
        freshSession: Boolean
    ) {
        val key = container.name
        val sink = SessionLogSink(key, newSessionLog(key))

        // ---- 会话头（对齐旧版纯日志格式） ----
        sink.log(
            buildString {
                append("===== AnWind wine 会话（原生链路） =====\n")
                append("容器: $key\n")
                append("参数: ${wineArgs.joinToString(" ")}\n")
                append("App: ${appVersionLine(context)}\n")
                append("设备: Android ${Build.VERSION.RELEASE} " +
                    "(SDK ${Build.VERSION.SDK_INT}, ${Build.MODEL})\n")
            }
        )
        lastLogs[key] = sink.text

        try {
            // 1) 解析 wine 运行时（目录 / ELF 形态 / 后端）
            val resolved = WineRuntime.resolve(container)
            sink.stamp(
                "wine=${resolved.wineDir.name} 形态=${resolved.arch.id} " +
                    "后端=${resolved.backend.id}"
            )

            // 2) 运行时目录兜底（rootfs tmp / X11 socket / XDG / 前缀 / logs）
            WineEnvironment.ensureRuntimeDirs(container)

            // 3) 基础环境（隔离白名单）+ WINEPREFIX
            val env = LinkedHashMap(WineEnvironment.buildBaseEnv(container, resolved.wineDir))
            env["WINEPREFIX"] = container.prefixPath

            // 4) X11 就绪：打开桌面窗口 + 原生拉起 X 服务 + 显式等 socket
            //    （v3.1：X 服务由 app_process 直启，不经 sh/脚本；决策行
            //    进会话日志，失败不再静默）
            X11Support.ensureX11Window(context) { sink.stamp(it) }
            X11Support.awaitSocketReady(env["DISPLAY"] ?: ":1") { sink.stamp(it) }

            // 5) 清残留 wineserver（仅 freshSession：注册表落盘 + 断开旧
            //    会话锚点，"第一次能启动之后起不来"的根治步骤；工具类
            //    子命令跳过 —— 附加语义，不打断运行中的会话）
            if (freshSession) {
                WineServerUtils.stop(resolved, container, env)
            }

            // 6) GPU / Box64 环境叠加
            WineEnvironment.applyGpuEnv(env, container)
            if (resolved.backend == ContainerBackend.BOX64 ||
                resolved.backend == ContainerBackend.FEXCORE
            ) {
                WineEnvironment.applyBox64Env(env, container)
            }

            // 7) 音频（audio=off → 无声；pulse → 原生守护管理 + PULSE_SERVER）
            when (container.audio) {
                ContainerAudio.OFF -> {
                    WineEnvironment.applyAudioOff(env)
                    sink.stamp("音频=关闭")
                }
                else -> {
                    val server = PulseAudioManager.ensureServer { sink.stamp(it) }
                    env["PULSE_SERVER"] = server
                    sink.stamp("音频=PulseAudio($server)")
                }
            }

            // 8) 首次运行初始化前缀（wineboot -i，宿主二进制直连）
            // 9) EmulateModeset 注册表（无 RandR X 环境的显示模式虚拟化，
            //    游戏显示模式枚举/D3D 设备创建的关键修复；幂等）
            // wineserver / wineboot 直连命令跳过 8/9（对齐脚本 run_wine
            // 特殊分支：宿主工具不经 wine 客户端准备流程）
            val isHostToolCommand = wineArgs.firstOrNull() == "wineserver" ||
                wineArgs.firstOrNull() == "wineboot"
            if (!isHostToolCommand) {
                WineRegistry.initializePrefixIfNeeded(resolved, container, env) { sink.stamp(it) }
                // v3.1：dosdevices 兑底（z: → / 等）—— /storage/emulated/0
                // 这类宿主路径经 Z: 盘可达；缺失时某些精简 wine 构建不建
                // z: 盘，游戏按绝对路径找资源文件会全挂（media 找不到的
                // 另一诱因）
                WineRegistry.ensureDosdevices(container) { sink.stamp(it) }
                WineRegistry.ensureEmulateModeset(resolved, container, env) { sink.stamp(it) }
            }

            // 10) 显示模式握手（dmode=off/d/v/f + screen=native|WxH）
            val (dmode, resolution) = applyDisplayMode(container)
            if (dmode == "f" && resolution.isNotEmpty()) {
                WineRegistry.clearBlueDesktop(resolved, container, env)
            }
            if (resolution.isNotEmpty()) {
                sink.stamp("显示模式 dmode=$dmode 分辨率=$resolution（X11 握手已写入）")
            }

            // 11) 用户附加环境（最后叠加 —— 可覆盖一切上游变量）
            WineEnvironment.applyExtraEnv(env, container)

            // 12) 构建命令并 exec（wine / wineserver / wineboot 特判）
            // 令牌自检：链期间被"停止"或被更新的启动请求取代 → 放弃 exec
            if (pendingLaunches[key] != token) {
                sink.stamp("启动已取消（会话在启动链中被停止或取代），不再拉起 wine")
                return
            }
            val command = buildCommand(resolved, wineArgs, dmode, resolution)
            // v3.1：工作目录跟随 exe 所在目录（Unix 路径直取父目录 /
            // Windows 盘符路径经 dosdevices 映射）—— DX9 等大量游戏按
            // CWD 相对路径找 media\ 等资源，旧版写死 HOME 导致
            // "找不到 media" 报错（CubeMap.exe 实证）
            val workingDir = resolveExeWorkingDir(wineArgs.firstOrNull(), container)
                ?: WineEnvironment.HOME_DIR
            if (workingDir != WineEnvironment.HOME_DIR) {
                sink.stamp("工作目录=exe 所在目录 $workingDir（media 等相对路径可解析）")
            }
            sink.stamp(
                "启动: ${command.joinToString(" ")}\n" +
                    "环境: DISPLAY=${env["DISPLAY"]} WINEPREFIX=${env["WINEPREFIX"]} " +
                    "HOME=${env["HOME"]} LANG=${env["LANG"]}"
            )

            val executor = NativeShellExecutor.create()
                .setCommand(*command.toTypedArray())
                .putEnv(env)
                .setWorkingDir(workingDir)
                .onStdOut { sink.log(it) }
                .onStdErr { sink.log(it) }
                .onExit { code -> onSessionExit(key, code, sink) }
                .exec()

            registerSession(key, WineSession(key, executor, sink))
        } catch (e: Exception) {
            sink.stamp("启动失败：${e.message ?: e.javaClass.simpleName}")
            unregisterSession(key)
            // 日志保留供 UI 排障（lastLogs 已在链头登记）
        } finally {
            // 仅当令牌仍归本链所有时才清除（新链已覆盖时不误伤）
            pendingLaunches.remove(key, token)
        }
    }

    /**
     * 显示模式握手（apply_display_mode 移植）。
     *
     * 握手文件 `$APP_PREFIX/tmp/.anwind-x11-res`（App 侧
     * X11ResolutionLink FileObserver 消费）：d/v/f + 合法 WxH → 写
     * 分辨率；off / native / 非法值 → 写 native（跟随窗口）。
     *
     * @return (dmode, 生效分辨率) —— 分辨率为空表示跟随窗口
     */
    private fun applyDisplayMode(container: ContainerData): Pair<String, String> {
        val dmode = container.dmode.id
        val screen = container.screen.trim()
        val resolution = if (Regex("^\\d+x\\d+$").matches(screen)) screen else ""
        val resFile = File("${ContainerManager.APP_PREFIX}/tmp/.anwind-x11-res")
        runCatching {
            resFile.parentFile?.mkdirs()
            if ((dmode == "d" || dmode == "v" || dmode == "f") && resolution.isNotEmpty()) {
                resFile.writeText(resolution)
            } else {
                resFile.writeText("native")
            }
        }
        return dmode to resolution
    }

    /**
     * 构建最终命令（run_wine 移植）。
     *
     * - `wineserver` / `wineboot` 是 Unix 宿主程序 → 直接执行其宿主
     *   二进制（x86_64 形态经 box64/FEX 包装）；
     * - dmode=v + WxH → `wine explorer /desktop=AnWind,WxH <args>`；
     * - 其余 → `wine <args>`。
     */
    private fun buildCommand(
        resolved: WineRuntime.Resolved,
        wineArgs: List<String>,
        dmode: String,
        resolution: String
    ): List<String> {
        val first = wineArgs.firstOrNull()
        if (first == "wineserver" || first == "wineboot") {
            val bin = WineRuntime.hostToolBin(resolved, first)
            return WineRuntime.hostCommand(resolved, bin) + wineArgs.drop(1)
        }
        val cmd = WineRuntime.wineCommand(resolved)
        if (dmode == "v" && resolution.isNotEmpty()) {
            return cmd + listOf("explorer", "/desktop=AnWind,$resolution") + wineArgs
        }
        return cmd + wineArgs
    }

    // ------------------------------------------------------------------
    // 会话登记与收尾
    // ------------------------------------------------------------------

    private fun registerSession(key: String, session: WineSession) {
        synchronized(sessions) {
            sessions.remove(key)?.finishIfRunning()
            sessions[key] = session
            trimSessionsLocked()
        }
    }

    private fun unregisterSession(key: String) {
        synchronized(sessions) { sessions.remove(key) }
    }

    /** 回收超量会话（对齐旧版 trimSessions）。 */
    private fun trimSessionsLocked() {
        while (sessions.size > MAX_SESSIONS) {
            val eldest = sessions.entries.firstOrNull() ?: break
            eldest.value.finishIfRunning()
            sessions.remove(eldest.key)
        }
    }

    private fun onSessionExit(key: String, code: Int, sink: SessionLogSink) {
        sink.log(
            "\n[进程退出码: $code] 会话结束 " +
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n"
        )
        unregisterSession(key)
    }

    // ------------------------------------------------------------------
    // 日志文件（沿用旧版候选目录策略）
    // ------------------------------------------------------------------

    /** 新建会话日志文件（公共 Download → 容器 logs；全失败返回 null）。 */
    internal fun newSessionLog(containerName: String): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val safe = containerName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(40).ifEmpty { "default" }
        for (dir in listOf(
            File("/storage/emulated/0/Download/AnWind"),
            File("${ContainerManager.CONTAINERS_ROOT}/logs")
        )) {
            try {
                if (!dir.isDirectory) dir.mkdirs()
                if (!dir.isDirectory || !dir.canWrite()) continue
                val f = File(dir, "wine-$safe-$stamp.log")
                if (!f.createNewFile() && !f.isFile) continue
                return f
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun appVersionLine(context: Context?): String = try {
        val ctx = context ?: com.anwind.AnWindApp.get()
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${ctx.packageName} ${pi.versionName ?: "?"}"
    } catch (_: Exception) {
        "?"
    }

    /** installer 的最小环境（bash 工具链：下载/解包，非 wine 运行时）。 */
    private fun installerEnv(): Map<String, String> {
        val rfsUsr = "${ContainerManager.ROOTFS_ROOT}/usr"
        return mapOf(
            "HOME" to WineEnvironment.HOME_DIR.absolutePath,
            "TMPDIR" to "$rfsUsr/tmp",
            "PATH" to "$rfsUsr/bin:$rfsUsr/local/bin:/system/bin:/system/xbin:" +
                "${ContainerManager.APP_PREFIX}/bin"
        )
    }
}
