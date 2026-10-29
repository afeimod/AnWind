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
     * 确保前缀的 dosdevices 盘符映射完整（v3.1 新增）。
     *
     * wineboot 建前缀时会创建 c: → ../drive_c 与 z: → /，但个别精简
     * 构建 / 异常中断的前缀会缺 z: —— 之后：
     *  - 用户从 App 传入的 Unix 绝对路径（/storage/emulated/0/…）wine
     *    自动按 Z: 盘换算，z: 缺失即整个宿主文件系统在 wine 内不可达；
     *  - 游戏按绝对路径（Z:\\storage\\…）找 media 等资源时直接报
     *    "找不到 media"。
     *
     * 幂等：仅在链接缺失时补建（z: → / 绝对符号链接；c: 缺失时指向
     * 前缀内 drive_c，与 wine 默认布局一致）。前缀尚未初始化（无
     * dosdevices 目录）时跳过 —— 归 wineboot 管。
     */
    fun ensureDosdevices(container: ContainerData, log: (String) -> Unit = {}) {
        runCatching {
            val prefix = File(container.prefixPath)
            val dd = File(prefix, "dosdevices")
            if (!dd.isDirectory) return

            val z = File(dd, "z:")
            if (!z.exists()) {
                try {
                    android.system.Os.symlink("/", z.absolutePath)
                    log("已补 dosdevices z: → /（/storage 等宿主路径经 Z: 盘可达）")
                } catch (e: Exception) {
                    log("警告: z: 盘补建失败（${e.message}）—— 宿主路径在 wine 内可能不可达")
                }
            }

            val c = File(dd, "c:")
            if (!c.exists()) {
                val driveC = File(prefix, "drive_c")
                driveC.mkdirs()
                try {
                    android.system.Os.symlink("../drive_c", c.absolutePath)
                    log("已补 dosdevices c: → ../drive_c")
                } catch (e: Exception) {
                    log("警告: c: 盘补建失败（${e.message}）")
                }
            }
        }
    }

    /**
     * 首次运行自动初始化前缀（run_wine 的 wineboot -i 分支；v3.1.4 修正）：
     * 前缀无 system.reg 时同步执行 wineboot -i。
     *
     * v3.1.4 两处修正：
     * ① 经 wine loader 执行（`[box64] bin/wine wineboot -i`）——旧版直连
     *   bin/wineboot，而 wine ≤9.18/proton 树该文件是 shell 脚本（box64
     *   读不到 ELF 头、脚本原生 exec 绕开 box64 → ENOEXEC），wine 9.19+
     *   是符号链接，均不可靠；loader 的 check_command_line() 按 argv[0]
     *   basename 分发到 wineboot.exe，官方机制。
     * ② 校验退出码并失败重试一次 —— 旧版无条件报"完成"（设备实测
     *   wineboot 阵亡仍打"完成"，EmulateModeset 随之连锁失败）。
     *
     * @return true 表示初始化完成（或已有前缀）。
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
            // v3.1.4：统一经 wine loader（box64 形态自动包装），不再直连
            // bin/wineboot 宿主文件（脚本/符号链接形态均不可靠）
            val cmdPrefix = WineRuntime.wineCommand(resolved)
            var lastCode = -1
            for (attempt in 1..2) {
                val executor = NativeShellExecutor.create()
                    .setCommand(*cmdPrefix.toTypedArray(), "wineboot", "-i")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.HOME_DIR)
                    .onStdOut { log(it) }
                    .onStdErr { log(it) }
                    .exec()
                lastCode = executor.waitFor()
                if (lastCode == 0) break
                log("wineboot -i 第 ${attempt} 次退出码 $lastCode" +
                    if (attempt == 1) "，重试一次…" else "")
                if (attempt == 1) {
                    // 残留 wineserver 可能让重试挂死——清一次再试
                    runCatching { WineServerUtils.stop(resolved, container, env) }
                }
            }
            if (lastCode == 0) {
                log("wineboot -i 完成（退出码 0）")
                true
            } else {
                log("wineboot -i 失败（退出码 $lastCode，159=SIGSYS/旧包fsync、1=加载失败）")
                log("→ 请用 v3.1.4+ 构建的 wine 包（fsync 守卫 + 自包含依赖库）后重建容器")
                false
            }
        } catch (e: Exception) {
            log("wineboot 初始化异常：${e.message}")
            false
        }
    }
}
