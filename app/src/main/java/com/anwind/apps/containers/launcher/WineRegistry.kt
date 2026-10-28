package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerData
import java.io.File

/**
 * Wine 注册表操作（经 wine reg 客户端执行 —— 与脚本 ensure_emulate_modeset /
 * clear_wine_blue_desktop 同一通路，注册表一致性由 wineserver 保证）。
 *
 * **EmulateModeset（v2.3 核心修复，必须保留）**：
 * AnWind 的 X 服务（termux-x11/libXlorie）不支持 RandR 动态模式切换
 * （分辨率由 .anwind-x11-res 握手文件调整），而 wine 10+ 的显示栈在
 * explorer 初始化显示设置时经 x11drv 调 RandR 设模式 →
 * NtUserChangeDisplaySettings 返回 -2 → 模式表未写入注册表 → 游戏
 * NtUserEnumDisplaySettings 连续报 "display mode not found" → D3D
 * 设备创建失败弹错（所有游戏全挂）。wine 上游为无 RandR 环境提供的
 * 官方开关 EmulateModeset 开启后，模式切换在 win32u 内虚拟化成功。
 * 幂等写入，以 WINEPREFIX 下的标记文件避免每次启动重复起 wine reg。
 */
object WineRegistry {

    /** EmulateModeset 已写入的标记文件（容器前缀内）。 */
    private fun marker(prefix: File) = File(prefix, ".anwind-emulate-modeset")

    /**
     * 确保注册表已写入 EmulateModeset=Y。
     *
     * @return true 表示已就位（含标记命中）；false 表示本次写入失败
     *         （下次启动自动重试，不阻断容器启动）。
     */
    fun ensureEmulateModeset(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (marker(prefix).isFile) return true
        log("写入 EmulateModeset=Y（显示模式虚拟化——修复游戏枚举不到显示模式/设备创建失败）……")
        return try {
            val cmd = NativeShellExecutor.create()
                .setCommand(
                    *WineRuntime.wineCommand(resolved).toTypedArray(),
                    "reg", "add",
                    "HKCU\\Software\\Wine\\X11 Driver",
                    "/v", "EmulateModeset",
                    "/t", "REG_SZ",
                    "/d", "Y",
                    "/f"
                )
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.HOME_DIR)
                .exec()
            val code = cmd.waitFor()
            if (code == 0) {
                runCatching { marker(prefix).createNewFile() }
                true
            } else {
                log("警告: EmulateModeset 写入失败（wine 未就绪？下次启动自动重试）")
                false
            }
        } catch (e: Exception) {
            log("警告: EmulateModeset 写入异常：${e.message}")
            false
        }
    }

    /**
     * dmode=f 的注册表蓝底桌面清理（对齐 glibc-runner -f / 脚本
     * clear_wine_blue_desktop）：删除 wine 桌面注册项，强制窗口直出。
     */
    fun clearBlueDesktop(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>
    ) {
        val targets = listOf(
            arrayOf("reg", "delete", "HKCU\\Software\\Wine\\Explorer", "/v", "Desktop", "/f"),
            arrayOf("reg", "delete", "HKCU\\Software\\Wine\\Explorer\\Desktops", "/f")
        )
        for (args in targets) {
            runCatching {
                NativeShellExecutor.create()
                    .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(), *args)
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.HOME_DIR)
                    .exec()
                    .waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    /**
     * 首次运行自动初始化前缀（run_wine 的 wineboot -i 分支）：
     * 前缀无 system.reg 时同步执行 wineboot -i（宿主二进制直连）。
     *
     * @return true 表示初始化完成或已有前缀。
     */
    fun initializePrefixIfNeeded(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        if (File(container.prefixPath, "system.reg").isFile) return true
        log("首次使用，初始化 wine 前缀: ${container.prefixPath}")
        return try {
            val wineboot = WineRuntime.hostToolBin(resolved, "wineboot")
            NativeShellExecutor.create()
                .setCommand(
                    *WineRuntime.hostCommand(resolved, wineboot).toTypedArray(),
                    "-i"
                )
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.HOME_DIR)
                .onStdOut { log(it) }
                .onStdErr { log(it) }
                .exec()
                .waitFor()
            log("wineboot -i 完成")
            true
        } catch (e: Exception) {
            log("wineboot 初始化异常：${e.message}")
            false
        }
    }
}
