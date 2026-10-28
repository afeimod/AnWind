package com.anwind.apps.containers.launcher

import android.content.Context
import android.os.SystemClock
import com.anwind.apps.containers.ContainerManager
import com.anwind.apps.x11.X11WindowController
import java.io.File

/**
 * X11 显示方案联动（原生启动链的显示环节 —— AnWind 与 WinFusion 的
 * 核心差异点：**WinFusion 用进程内 Weston/Wayland，AnWind 保留
 * termux-x11/libXlorie 的 X11 方案**）。
 *
 * X server 是 APK 内的 CmdEntryPoint + libXlorie（经 app_process 拉起），
 * unix socket 在 App 侧 `tmp/.X11-unix/Xn`（默认 :1）；rootfs 内的
 * libxcb/xtrans/libX11 已按该路径打补丁，wine 凭 `DISPLAY=:1` 直连。
 *
 * 本类从旧 WineSessionLauncher 平移 X11 联动逻辑并做两处原生化增强：
 *  1. X 服务自动拉起改用 [NativeShellExecutor]（同参数同日志文件，
 *     输出重定向文件而非管道 —— 常驻进程写满管道缓冲会卡死）；
 *  2. socket 就绪等待从"发射后不管"改为**显式轮询**（最多 10 秒，
 *     对齐脚本 ensure_x11 的 20×0.5s），wine 拉起前确保 X 可连 ——
 *     消除"X 未就绪 wine 先连 → 黑屏"的冷启动竞态。
 */
object X11Support {

    /** X 服务自动拉起的去抖窗口（冷启动 socket 未就绪期间防重复）。 */
    private const val SERVICE_START_DEBOUNCE_MS = 8000L

    /** X socket 就绪等待（对齐 anwind-wine ensure_x11：20 × 0.5s）。 */
    private const val SOCKET_WAIT_ROUNDS = 20
    private const val SOCKET_WAIT_INTERVAL_MS = 500L

    @Volatile
    private var lastServiceStartAt = 0L

    /** 当前 DISPLAY（:1，ANWIND_DISPLAY 可覆盖）。 */
    fun display(): String {
        val override = System.getenv("ANWIND_DISPLAY")
        return if (!override.isNullOrBlank()) override else ":1"
    }

    /** DISPLAY 对应的 socket 文件（X11_SOCK_DIR/X<n>）。 */
    private fun socketFor(display: String): File {
        var num = display.removePrefix(":").substringBefore('.')
        if (num.isEmpty() || num.any { !it.isDigit() }) num = "1"
        return File(ContainerManager.X11_SOCK_DIR, "X$num")
    }

    /** 当前是否存在可用的 X11 socket（App 侧）。 */
    fun x11SocketReady(): Boolean {
        val dir = File(ContainerManager.X11_SOCK_DIR)
        return dir.isDirectory &&
            dir.listFiles { f -> f.name.startsWith("X") }?.isNotEmpty() == true
    }

    /**
     * 确保 X11 桌面窗口就位：自动拉起 X 服务（若未运行）+ 打开桌面窗口。
     *
     * 拉起失败不阻断流程（等待页持续重试，doctor 可诊断）。
     */
    fun ensureX11Window(context: Context) {
        try {
            ensureX11Service(context)
        } catch (_: Exception) {
        }
        try {
            X11WindowController.openWindow(context)
        } catch (_: Exception) {
        }
    }

    /**
     * 自动拉起内置 X 服务（显示号固定 :1）。
     *
     * 判定：App 侧 X11 socket 已存在 → 服务在跑，直接复用；否则原生
     * 执行 `$PREFIX/bin/termux-x11 :1`（脚本内部自取宿主 APK CLASSPATH +
     * XKB 数据并经 app_process 启动 CmdEntryPoint，与终端 anwind-x11
     * 完全同源），输出写 `$PREFIX/tmp/anwind-x11-autostart.log` 供排障。
     * 8 秒去抖防重复拉起。
     */
    fun ensureX11Service(context: Context) {
        val now = SystemClock.elapsedRealtime()
        val sock = socketFor(display())
        if (sock.exists()) return
        if (now - lastServiceStartAt < SERVICE_START_DEBOUNCE_MS) return
        lastServiceStartAt = now

        val prefix = ContainerManager.APP_PREFIX
        val client = File("$prefix/bin/termux-x11")
        val sh = File("$prefix/bin/sh")
        if (!client.isFile || !sh.isFile) return

        val log = File("$prefix/tmp/anwind-x11-autostart.log")
        try {
            // App 侧家目录 = files/home（对齐 TermuxEnvironment.homePath）
            val appHome = File(prefix).parentFile?.let { File(it, "home").absolutePath }
                ?: "$prefix/home"
            NativeShellExecutor.create()
                .setCommand(sh.absolutePath, client.absolutePath, display())
                // 输出全部落日志文件（不用管道：X 服务常驻进程写满
                // 管道缓冲会卡死）
                .redirectOutputTo(log)
                .putEnv(
                    mapOf(
                        "PATH" to "$prefix/bin:/system/bin",
                        "HOME" to appHome,
                        "TMPDIR" to "$prefix/tmp"
                    )
                )
                .exec()
            // 脚本自身判断重复启动（anwindx11 进程探测），无需等待
        } catch (_: Exception) {
        }
    }

    /**
     * 等待 X socket 就绪（wine 启动前的显式同步点）。
     *
     * @return true 就绪；false 超时（打警告，不阻断 —— 与脚本行为一致）
     */
    fun awaitSocketReady(display: String, log: (String) -> Unit = {}): Boolean {
        val sock = socketFor(display)
        if (sock.exists()) return true
        repeat(SOCKET_WAIT_ROUNDS) {
            if (sock.exists()) return true
            runCatching { Thread.sleep(SOCKET_WAIT_INTERVAL_MS) }
        }
        if (sock.exists()) return true
        log("警告: X 服务拉起后 socket 未就绪（$sock）——画面可能无法显示；" +
            "排障: cat ${ContainerManager.APP_PREFIX}/tmp/anwind-x11-autostart.log")
        return false
    }
}
