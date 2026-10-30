package com.anwind.apps.containers.launcher

import android.content.Context
import android.os.Build
import com.anwind.apps.containers.ContainerAudio
import com.anwind.apps.containers.ContainerBackend
import com.anwind.apps.containers.ContainerData
import com.anwind.apps.containers.ContainerManager
import com.anwind.apps.containers.DisplayMode
// v3.1.7 无 WM 补偿：wine 虚拟桌面几何取当前 X 屏幕尺寸（实时值 → 落盘值 → 默认）
import com.anwind.apps.x11.X11FitClient
import com.anwind.apps.x11.X11ResolutionLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    // ------------------------------------------------------------------
    // v3.1.6 前缀构建日志（仅 wineboot -i 阶段；用户诉求：构建前缀日志显示）
    // ------------------------------------------------------------------

    /** 前缀构建进行中的容器名（null = 无）；UI 据此自动弹出前缀日志窗口。 */
    private val _prefixBuildFlow = MutableStateFlow<String?>(null)
    val prefixBuildFlow: StateFlow<String?> get() = _prefixBuildFlow

    /** 前缀构建专用日志通道（容器名 → 累积文本，与主会话日志分离）。 */
    private val prefixLogLock = Any()
    private val prefixLogs = LinkedHashMap<String, StringBuilder>()

    internal fun prefixLogLine(key: String, line: String) {
        synchronized(prefixLogLock) {
            prefixLogs.getOrPut(key) { StringBuilder() }.append(line).append('\n')
        }
    }

    /** 读取容器的前缀构建日志（无则空串）。 */
    fun prefixLogOf(key: String): String = synchronized(prefixLogLock) {
        prefixLogs[key]?.toString() ?: ""
    }

    /** 清除容器的前缀构建日志（容器删除时）。 */
    fun clearPrefixLog(key: String) = synchronized(prefixLogLock) { prefixLogs.remove(key) }

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
        if (_prefixBuildFlow.value == name) _prefixBuildFlow.value = null
        clearPrefixLog(name)
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

            // 1.5) v3.2.0 wine 10.17+ loader 布局自愈：hangover/official 10.17+
            //      上游把 loader 装进 lib/wine/<arch>-unix/，v3.1.6 曾物化成
            //      bin/wine 副本 —— 副本按自身位置找 bin/ntdll.so 必失败
            //      （设备实测 wine-10-18 两形态 "could not load ntdll.so"）。
            //      此处就地换回相对符号链接，旧包免重装即修复。
            //      v3.2.3 起同时停用 bin/*-preloader（直连 loader 官方兕底）
            //      并改写 futex_waitv 探测（proton 旧包 159 静默阵亡根治）。
            WineLayoutRepair.ensure(resolved.wineDir) { sink.stamp(it) }
            WineProbePatch.apply(resolved.wineDir) { sink.stamp(it) }

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
                // v3.1.4：wineDir 参数 —— wine 包自带的 x86_64-bionic 依赖闭包
                // 目录进 BOX64_LD_LIBRARY_PATH（缺它 box64 加载 wine unix .so
                // 直接 "Symbol ... not found"）
                WineEnvironment.applyBox64Env(env, container, resolved.wineDir)
                // v3.2.0：闭包缺失诊断（设备实测 proton10-x86_64 旧包仍报
                // "Symbol shm_open not found"）—— 旧包没有 x86_64-bionic 目录，
                // 环境变量救不了缺文件，把“请重装 v3.1.4+ 构建的 wine 包”
                // 直接写进会话日志，不再让用户对着 box64 报错猜原因。
                if (!File(resolved.wineDir, "lib/x86_64-bionic").isDirectory) {
                    sink.stamp(
                        "警告: 该 wine 包缺少 lib/x86_64-bionic 依赖闭包（v3.1.4 之前构建的旧包）——" +
                            "box64 加载 unix .so 时将报 Symbol shm_open not found；" +
                            "请用新 CI 重跑 build-wine-bionic（x86_64 形态）并重装该包"
                    )
                }
            }

            // 6b) Android 安全默认 + arm64ec 仿真器 DLL（v3.1.4）：
            //     WINEESYNC/WINEFSYNC=0（fsync 探测 SIGSYS 兜底）、
            //     HODLL64/HODLL 探测（libarm64ecfex/libwow64fex 存在即默认）
            WineEnvironment.applyWineCompatEnv(
                env, resolved.arch, resolved.wineDir, container.env
            )

            // v3.1.6：wineboot -i（前缀构建）会话不注入 HODLL64/HODLL ——
            // 前缀创建只执行 aarch64 PE（wineboot/services/explorer 全部
            // 原生 arm64ec 代码），仿真器 DLL 在 wow64 层的早期加载纯属
            // 额外风险面，却是 proton10 arm64ec "无任何输出挂死"的高度
            // 可疑点（同环境 hangover 11.16 正常，wine 10 与 11 的 wow64
            // 初始化路径不同）。构建完成后的游戏/程序会话照常注入。
            if (wineArgs.firstOrNull() == "wineboot") {
                env.remove("HODLL64")
                env.remove("HODLL")
                sink.stamp("前缀构建会话：跳过 HODLL64/HODLL 注入（仅 aarch64 PE，规避仿真器早期加载风险）")
                // v3.2.0（用户诉求 4）：前缀构建画面指引 —— 构建已包 explorer
                // 虚拟桌面（见 buildCommand），mono/gecko 等安装提示框从此在
                // 『X11 桌面』窗口内可见可点，不再是无 WM 裸窗口堆在 (0,0) 黑屏角落。
                sink.stamp(
                    "前缀构建画面输出在『X11 桌面』窗口（explorer 虚拟桌面）——" +
                        "mono/gecko 等安装提示框请在该窗口中确认，构建日志同时在本窗口滚动"
                )
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

            // 7.5) v3.2.1（用户问题 3）：显示模式握手提前到前缀初始化之前 ——
            //      握手写 .anwind-x11-res → X11ResolutionLink 即时把 X 屏幕
            //      切到目标分辨率；随后 wineboot / 应用的 explorer 虚拟桌面
            //      以同一分辨率创建。旧顺序（前缀构建后才握手）下 VD 取到的
            //      是握手生效前的旧屏幕尺寸（跟随窗口值），随后 X11FitClient
            //      的“贴合”又把 X 屏缩回 VD 尺寸 —— 配置的分辨率被吞掉，
            //      表现为“分辨率选择无效果、依旧跟随窗口”。
            val (dmode, resolution) = applyDisplayMode(container)

            // 7.6) v3.2.1（用户问题 4）：wine 本地化支持 —— wine.mo 界面翻译
            //      （安装到 wine 目录 share/locale 双路径：槽位 + 编译期默认）+
            //      CJK 字体（Android 系统字体 → 前缀 drive_c/windows/Fonts +
            //      注册表 SimSun/微软雅黑/MS Shell Dlg 替换）。翻译先于前缀构
            //      建安装（wineboot 的 mono/gecko 提示框直接是中文）；字体需
            //      前缀就绪，未就绪时由后续启动自动补装（幂等）。
            WineLocaleSupport.ensure(context, resolved, container, env) { sink.stamp(it) }

            // 8) 首次运行初始化前缀（wineboot -i，宿主二进制直连）
            // 9) EmulateModeset 注册表（无 RandR X 环境的显示模式虚拟化，
            //    游戏显示模式枚举/D3D 设备创建的关键修复；幂等）
            // wineserver / wineboot 直连命令跳过 8/9（对齐脚本 run_wine
            // 特殊分支：宿主工具不经 wine 客户端准备流程）
            // v3.1.6：wineboot -i 会话与首次前缀初始化统一登记前缀构建
            // 进度流 + 专用日志通道（UI 自动弹出「构建 Wine 前缀」窗口，
            // 逐行显示 wineboot 输出 —— proton arm64ec 无输出挂死这类
            // 问题从此有直接证据面）。
            val isHostToolCommand = wineArgs.firstOrNull() == "wineserver" ||
                wineArgs.firstOrNull() == "wineboot"
            val isPrefixSession = wineArgs.firstOrNull() == "wineboot"
            if (isPrefixSession) _prefixBuildFlow.value = key
            if (!isHostToolCommand) {
                try {
                    // v3.2.1：把配置分辨率传给前缀构建 —— wineboot 的 explorer
                    // 虚拟桌面与握手分辨率一致（无分辨率时维持旧“当前屏幕”语义）
                    WineRegistry.initializePrefixIfNeeded(
                        resolved, container, env,
                        vdResolution = resolution
                    ) { line ->
                        sink.stamp(line)
                        prefixLogLine(key, line)
                    }
                    // v3.1：dosdevices 兑底（z: → / 等）—— /storage/emulated/0
                    // 这类宿主路径经 Z: 盘可达；缺失时某些精简 wine 构建不建
                    // z: 盘，游戏按绝对路径找资源文件会全挂（media 找不到的
                    // 另一诱因）
                    WineRegistry.ensureDosdevices(container) { sink.stamp(it) }
                    WineRegistry.ensureEmulateModeset(resolved, container, env) { sink.stamp(it) }
                } finally {
                    _prefixBuildFlow.value = null
                }
            }

            // 8b) v3.2.3：手动 wineboot 会话补齐加载器冒烟测试 —— 此前该
            //     路径（runWineCmd "wineboot -i"）不走 initializePrefixIfNeeded，
            //     无冒烟、无看门狗，proton 两形态 loader 阵亡时会话日志
            //     戛然而止无从排查。结论直接写进会话日志与前缀日志双通道。
            //     同一运行时仅首次实测（WineRegistry 侧按 wine 目录缓存）。
            if (isPrefixSession) {
                if (!WineRegistry.loaderSmokeTest(resolved, container, env) { line ->
                        sink.stamp(line)
                        prefixLogLine(key, line)
                    }) {
                    sink.stamp("→ 加载器冒烟测试未通过，已放弃本次 wineboot（结论见上；请先解决该问题再重试）")
                    prefixLogLine(key, "加载器冒烟测试未通过，wineboot 未执行")
                    _prefixBuildFlow.value = null
                    return
                }
            }

            // 10) dmode=f 的注册表蓝底清理（需前缀就绪；分辨率握手已在 7.5 提前完成）
            if (dmode == "f" && resolution.isNotEmpty()) {
                WineRegistry.clearBlueDesktop(resolved, container, env)
            }
            if (resolution.isNotEmpty()) {
                sink.stamp("显示模式 dmode=$dmode 分辨率=$resolution（X11 握手已写入）")
            } else if (dmode != DisplayMode.OFF.id) {
                // v3.2.1（用户问题 3）：选了显示模式但没选分辨率（native）时
                // 握手什么也不改 —— 如实告知，不再让用户对着“无效果”猜。
                sink.stamp("显示模式 dmode=$dmode 但未配置分辨率（跟随窗口）——请在容器设置选择虚拟分辨率")
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

            // v3.2.3：前缀构建会话看门狗信号量 —— onStdOut/onStdErr/onExit
            // 回调置位（供下方 4 分钟无输出看门狗判定；普通会话开销为零）。
            val gotSessionOutput = java.util.concurrent.atomic.AtomicBoolean(false)
            val sessionEnded = java.util.concurrent.atomic.AtomicBoolean(false)
            val executor = NativeShellExecutor.create()
                .setCommand(*command.toTypedArray())
                .putEnv(env)
                .setWorkingDir(workingDir)
                .onStdOut {
                    gotSessionOutput.set(true)
                    sink.log(it)
                    if (isPrefixSession) prefixLogLine(key, it)
                }
                .onStdErr {
                    gotSessionOutput.set(true)
                    sink.log(it)
                    if (isPrefixSession) prefixLogLine(key, it)
                }
                .onExit { code ->
                    sessionEnded.set(true)
                    onSessionExit(key, code, sink)
                    if (isPrefixSession) _prefixBuildFlow.value = null
                }
                .exec()

            // v3.2.3：手动 wineboot 会话的无输出看门狗（WineRegistry 侧
            // initializePrefixIfNeeded 同款语义）—— 4 分钟零输出且
            // system.reg 未落盘时自动抓取 /proc 进程现场（R/S/D/Z +
            // 各线程 wchan），只取证不杀进程（mono/gecko 提示框等待
            // 用户确认属正常场景）。proton 旧包“预加载器交接挂死”这类
            // 日志戛然而止的静默故障从此有内核级证据面。
            if (isPrefixSession) {
                val prefixMarker = File(container.prefixPath, "system.reg")
                Thread({
                    try { Thread.sleep(4 * 60_000L) } catch (_: InterruptedException) { return@Thread }
                    if (!sessionEnded.get() && !gotSessionOutput.get() && !prefixMarker.isFile) {
                        sink.stamp("wineboot 会话已 4 分钟无任何输出且 system.reg 未落盘 —— 疑似挂死，抓取进程现场：")
                        WineRegistry.dumpWineHangSnapshot(resolved, container) { sink.stamp(it) }
                        sink.stamp("→ 排障提示：v3.2.3 已自动停用预加载器并改写 futex_waitv 探测（免重编作用于旧包）；")
                        sink.stamp("→ 若现场中 wine 进程仍在而 wineserver 缺席/已死 = 握手阻塞（把上面几行现场发回即可精确定位）；若 R=死循环/S+futex=死锁，同样发回现场")
                    }
                }, "anwind-prefix-session-watchdog").apply { isDaemon = true; start() }
            }

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
     * 构建最终命令（run_wine 移植 + v3.1.4 wineboot 修正）。
     *
     * - `wineserver` 是真实 Unix ELF 宿主程序 → 直接执行宿主二进制
     *   （x86_64 形态经 box64/FEX 包装）；
     * - `wineboot`：wine ≤9.18/proton 树的 bin/wineboot 是 shell 脚本
     *   （box64 读不到 ELF 头，脚本原生 exec 又绕开 box64 → ENOEXEC），
     *   wine 9.19+ 是指向 loader 的符号链接 → 统一改走 wine loader：
     *   `[box64] bin/wine wineboot …`（loader 的 check_command_line 按
     *   argv[0] basename 分发到同名 .exe，官方机制，任何树都成立）；
     * - dmode=v + WxH → `wine explorer /desktop=AnWind,WxH <args>`；
     * - v3.1.7 无 WM 补偿：其余"普通运行"（off/d/无分辨率的 v）也统一
     *   包 `explorer /desktop=AnWind,<当前 X 屏幕尺寸>` —— AnWind 的
     *   X 服务（termux-x11/libXlorie）没有窗口管理器，裸窗口模式下
     *   wine 顶层窗口全部堆在 (0,0)（用户实测"跟随窗口一直在左上角、
     *   点击位置不对、整体上移"）。wine 虚拟桌面让 wine 在桌面窗口内
     *   自管子窗口几何（位置/装饰/点击换算），桌面窗口本体铺满整个
     *   X 屏幕尺寸 → 跟随窗口模式随窗口变化（RandR 改屏幕时 wine
     *   自适应缩放桌面窗口）。
     *   - 尺寸来源：X11FitClient 当前值（本进程 LorieView 回调）→
     *     .anwind-x11-screen 落盘值（上次会话/早于首帧回调）→ 1280x720；
     *   - dmode=f（窗口直出）不包 —— 用户明确要求裸窗口直出；
     *   - wineserver/wineboot（宿主工具/前缀构建）不包。
     */
    private fun buildCommand(
        resolved: WineRuntime.Resolved,
        wineArgs: List<String>,
        dmode: String,
        resolution: String
    ): List<String> {
        val first = wineArgs.firstOrNull()
        if (first == "wineserver") {
            val bin = WineRuntime.hostToolBin(resolved, first)
            return WineRuntime.hostCommand(resolved, bin) + wineArgs.drop(1)
        }
        val cmd = WineRuntime.wineCommand(resolved)
        if (first == "wineboot") {
            // v3.1.4：经 wine loader 执行（argv[0] basename 分发），
            // 不再直连 bin/wineboot（脚本/符号链接形态均不可靠）。
            // v3.2.0（用户诉求 4）：包 explorer /desktop 虚拟桌面 —— 无 WM 裸
            // 窗口下 wineboot 的 mono/gecko 提示框全部堆在 (0,0)（用户实测
            // “x11 没有显示任何画面/提示框看不到，无法继续构建”）；虚拟桌面
            // 铺满 X 屏幕自带桌面底色，子窗口由 wine 自管（可见/可点/可置前），
            // 构建画面从此直观可见。退出码语义：explorer 可能不透传 wineboot
            // 退出码，成败以 WINEPREFIX/system.reg 落盘为准（WineRegistry
            // 同步适配）。
            val vdRes = currentXScreenResolution()
            return cmd + listOf("explorer", "/desktop=AnWind,$vdRes") + wineArgs
        }
        if (dmode == "v" && resolution.isNotEmpty()) {
            return cmd + listOf("explorer", "/desktop=AnWind,$resolution") + wineArgs
        }
        // v3.1.7：无 WM 补偿 —— 见方法 KDoc。explorer 自引用防重包。
        // v3.2.1（用户问题 3）：有配置分辨率（dmode=d/off + WxH）时虚拟
        // 桌面直接用配置值 —— 旧实现取“当前 X 屏幕尺寸”（握手生效前的
        // 旧值/窗口尺寸），VD 与随后切换的 X 屏幕不一致，X11FitClient 的
        // 自适应又把 X 屏缩回 VD 尺寸 —— 配置的分辨率被吞掉。无分辨率时
        // 维持旧“当前屏幕”语义（跟随窗口）。
        if (dmode != "f" && first != "explorer") {
            val vdRes = resolution.ifEmpty { currentXScreenResolution() }
            return cmd + listOf("explorer", "/desktop=AnWind,$vdRes") + wineArgs
        }
        return cmd + wineArgs
    }

    /**
     * 当前 X 屏幕尺寸（用于"跟随窗口"模式的 wine 虚拟桌面几何）：
     * X11FitClient 实时值 → 落盘值（跨会话/启动链早于视图回调）→ 默认。
     * v3.2.0：internal —— WineRegistry 的前缀构建（wineboot 包虚拟桌面）
     * 复用同一分辨率源。
     */
    internal fun currentXScreenResolution(): String {
        val sw = X11FitClient.screenW
        val sh = X11FitClient.screenH
        if (sw > 0 && sh > 0) return "${sw}x${sh}"
        X11ResolutionLink.readScreenSize()?.let { return it }
        return X11ResolutionLink.DEFAULT_RES
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
