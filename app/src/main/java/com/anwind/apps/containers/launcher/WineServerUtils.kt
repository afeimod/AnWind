package com.anwind.apps.containers.launcher

import android.util.Log
import com.anwind.apps.containers.ContainerData
import com.anwind.apps.containers.ContainerManager
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wine 服务进程生命周期工具（移植 WinFusion WineServerUtils，适配
 * AnWind 的容器/多版本 wine 布局）。
 *
 * wineserver 会把注册表缓存在内存中并异步回写 user.reg 等文件；同时它
 * 是容器内全部 Windows 进程（services.exe / winedevice.exe / rpcss.exe /
 * explorer 桌面 / 游戏进程）的"会话锚点"。因此：
 *
 *  - **启动前必须清**：上次被系统回收时残留的 wineserver 会让新 wine
 *    客户端连上旧会话 —— 现象是"第一次能启动，之后再也起不来"；
 *  - **停止时必须真杀**：只 destroy wine 客户端进程不等于停容器，
 *    `-k` 让 server 自己走 kill_all_processes() 收掉整棵进程树；
 *  - **超时语义**：`-k` 只是"把退出请求递到 server"，server 收尾
 *    （杀进程 + flush 注册表）正常 2 秒出头；其内部兜底（锁不放时
 *    补 SIGKILL）累计约 10.5 秒 —— 外层等待必须覆盖它（12 秒），
 *    否则真卡死时再没人硬杀，残留留到下一次启动。`-w` 只等锁放开
 *    （确认旧会话真没了），6 秒足够。
 */
object WineServerUtils {

    private const val TAG = "WineServerUtils"

    private const val KILL_TIMEOUT_SECONDS = 12L
    private const val WAIT_TIMEOUT_SECONDS = 6L

    /**
     * 结束指定容器的 wine 会话并等待退出（注册表随之落盘）。
     *
     * @param resolved  wine 运行时定位结果（可为 null —— 依已保存的
     *                  会话信息重新解析；两处调用方语义见下）
     * @param container 容器配置（提供 WINEPREFIX 与 wine= 槽位）
     * @param env       会话环境（作为 wineserver 的运行环境）
     */
    fun stop(resolved: WineRuntime.Resolved?, container: ContainerData, env: Map<String, String>) {
        val active = resolved ?: runCatching { WineRuntime.resolve(container) }.getOrNull()
        if (active == null) {
            Log.e(TAG, "wineserver cannot be located for container=${container.name}; " +
                    "stale wine processes may be left behind.")
            return
        }
        val wineServerBin = WineRuntime.hostToolBin(active, "wineserver")
        if (!wineServerBin.isFile) {
            Log.e(TAG, "wineserver binary not found at $wineServerBin.")
            return
        }

        val prefix = container.prefixPath
        for (option in listOf("-k", "-w")) {
            val command = NativeShellExecutor.create()
                .putEnv(env)
                .putEnv("WINEPREFIX", prefix)
            try {
                command.setCommand(
                    *WineRuntime.hostCommand(active, wineServerBin).toTypedArray(),
                    option
                )
                command.exec()
                val timeout = if (option == "-k") KILL_TIMEOUT_SECONDS else WAIT_TIMEOUT_SECONDS
                if (!command.waitFor(timeout, TimeUnit.SECONDS)) {
                    command.stop(true)
                    Log.w(TAG, "wineserver $option timed out after ${timeout}s.")
                }
            } catch (e: Exception) {
                command.stop(true)
                Log.w(TAG, "Failed to run wineserver $option.", e)
            }
        }
    }

    /**
     * 静默兜底：容器配置缺失（wine 已被卸载等）时，仅按 rootfs 主槽位
     * 尝试停服务。失败只记日志，绝不抛出 —— 清理失败不能阻断启动。
     */
    fun stopBestEffort(containerName: String) {
        runCatching {
            val container = com.anwind.apps.containers.ContainerManager.get(containerName)
                ?: return
            val env = WineEnvironment.buildBaseEnv(
                container,
                File(ContainerManager.WINE_OPT_DIR, "wine")
            )
            stop(null, container, env)
        }
    }
}
