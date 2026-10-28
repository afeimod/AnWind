package com.anwind.apps.containers

import android.content.Context
import com.anwind.apps.containers.launcher.RootfsLauncher
import com.anwind.apps.containers.launcher.WineSession

/**
 * AnWind Wine 容器 — 启动器门面（App 侧）。
 *
 * v3.0（原生重制）：本对象自 v2.x 的"终端链路孵化器"重制为**纯门面** ——
 * 所有调用转发给 [RootfsLauncher]（原生启动编排器）。对外 API（方法名、
 * 参数、返回值语义、lastLogs/activeSessions 供 UI 读取）保持兼容，
 * ContainerScreens 等调用方无需改动。
 *
 * ## 架构变更（详见 launcher 包各类文档）
 *
 * 旧链路（已退役）：
 * ```
 * App → TerminalSession(PTY, 80×24) → bash -c
 *     → anwind-container run → anwind-wine 脚本(709 行 bash)
 *     → 环境二次清洗 → exec wine
 * ```
 *
 * 新链路（对齐 WinFusion 的原生编排，显示保留 AnWind 的 X11 方案）：
 * ```
 * App → RootfsLauncher 启动链（Kotlin）
 *     1. WineRuntime.resolve        （wine 目录 + ELF 形态 + 后端）
 *     2. X11Support                 （X 服务联动 + socket 就绪等待）
 *     3. WineServerUtils.stop       （清残留 wineserver 会话）
 *     4. WineEnvironment            （rootfs 环境白名单，一次成型）
 *     5. PulseAudioManager          （原生音频守护管理）
 *     6. WineRegistry               （wineboot 初始化 + EmulateModeset）
 *     7. NativeShellExecutor.exec   （ProcessBuilder 直接 exec wine）
 * ```
 *
 * 两者本质差异：wine 不再是"终端里的一个命令"，而是 App 原生管理的
 * 子进程 —— 无 PTY、无 bash、无脚本；环境零串扰、驱动/音频链路
 * 由 App 直接构建、进程生命周期具备完整的进程语义。
 *
 * 交互式终端（TermuxSessionController）与 rootfs 侧脚本（anwind-wine/
 * anwind-container，仍由 App 启动时部署）保留供高级用户使用，但 App 的
 * wine 运行不再依赖它们。
 */
object WineSessionLauncher {

    /**
     * 活跃会话视图：真实会话 + 启动中的容器（null 占位）。
     * UI 仅用 containsKey 判"运行中"，与旧版语义一致。
     */
    val activeSessions: Map<String, WineSession?>
        get() = RootfsLauncher.activeSessions

    /** 每容器最近一次启动的日志（原生链路决策行 + wine 实时输出）。 */
    val lastLogs: LinkedHashMap<String, StringBuilder>
        get() = RootfsLauncher.lastLogs

    // ------------------------------------------------------------------
    // 对外入口（签名与 v2.x 逐字兼容）
    // ------------------------------------------------------------------

    /**
     * 在指定容器中运行 exe（原生链路：RootfsLauncher 直接 exec wine）。
     *
     * @param exe  Windows 程序路径（容器内 C: 相对路径或绝对路径均可）
     * @param args 传给 exe 的参数
     * @return null 表示已受理（启动链异步执行，进度见 lastLogs）；
     *         否则为同步预检错误（rootfs 未就绪 / wine 未安装等）
     */
    fun runExe(context: Context, container: ContainerData, exe: String, args: String = ""): String? =
        RootfsLauncher.runExe(context, container, exe, args)

    /**
     * 运行 wine 子命令（wineboot / winecfg / reg / cmd ...）。
     */
    fun runWineCmd(context: Context, container: ContainerData, wineCmd: String): String? =
        RootfsLauncher.runWineCmd(context, container, wineCmd)

    /** 首次初始化前缀（wineboot -i，宿主二进制直连）。 */
    fun initializePrefix(context: Context, container: ContainerData): String? =
        runWineCmd(context, container, "wineboot -i")

    /** 打开 winecfg 配置面板。 */
    fun openWineCfg(context: Context, container: ContainerData): String? =
        runWineCmd(context, container, "winecfg")

    /** 打开注册表编辑器。 */
    fun openRegEdit(context: Context, container: ContainerData): String? =
        runWineCmd(context, container, "regedit")

    /**
     * 停止容器内全部 wine 进程。
     *
     * 原生语义：先收 wine 客户端会话（destroyForcibly），再
     * wineserver -k / -w 由 server 收掉整棵进程树并落盘注册表 ——
     * 替代旧的 `bash -c anwind-container stop`。
     */
    fun stopContainer(context: Context, name: String): String? =
        RootfsLauncher.stopContainer(context, name)

    /** 供 ContainerManager 内部调用的静默停止（不弹任何 UI）。 */
    fun stopContainerQuietly(name: String) =
        RootfsLauncher.stopContainerQuietly(name)

    /**
     * 通过 CLI 安装 wine 发行包。
     *
     * 安装器是 bash 工具链（下载/解包，非 wine 运行时），保留脚本执行
     * 但**不经 PTY**：NativeShellExecutor 承载，进度实时流入
     * lastLogs["__install__"]。
     */
    fun installWine(
        context: Context,
        src: String,
        name: String? = null,
        mkDefault: Boolean = false
    ): String? = RootfsLauncher.installWine(context, src, name, mkDefault)

    // ------------------------------------------------------------------
    // X11 桌面联动（X11 显示方案保留；委托 X11Support）
    // ------------------------------------------------------------------

    /** 确保 X11 桌面窗口处于打开状态（含 X 服务自动拉起）。 */
    fun ensureX11Window(context: Context) =
        RootfsLauncher.ensureX11Window(context)

    /** 当前是否存在可用的 X11 socket（App 侧）。 */
    fun x11SocketReady(): Boolean = RootfsLauncher.x11SocketReady()
}
