package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.ContainerData
import java.io.File
import java.util.concurrent.TimeUnit

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
        log("提示: 构建过程中的 mono/gecko 等安装提示框将显示在『X11 桌面』窗口（explorer 虚拟桌面）内，请在该窗口确认")
        return try {
            // v3.1.4：统一经 wine loader（box64 形态自动包装），不再直连
            // bin/wineboot 宿主文件（脚本/符号链接形态均不可靠）。
            // v3.2.0（用户诉求 4）：包 explorer /desktop 虚拟桌面 —— 无 WM
            // 裸窗口下 mono/gecko 提示框堆在 (0,0) 黑屏角落（用户实测“x11
            // 没有任何画面、提示框看不到、无法继续构建”）；虚拟桌面铺满 X
            // 屏幕，子窗口由 wine 自管，提示框可见可点。分辨率与普通会话
            // 同源（RootfsLauncher.currentXScreenResolution）。
            val vdRes = RootfsLauncher.currentXScreenResolution()
            val cmdPrefix = WineRuntime.wineCommand(resolved) +
                listOf("explorer", "/desktop=AnWind,$vdRes")
            // v3.1.6：每次尝试限时 —— wineboot 正常 1~3 分钟（arm64ec
            // 原生 / box64 仿真最慢也 <10 分钟）；超时强杀并如实报错。
            // 设备实测 proton10 arm64ec wineboot 无任何输出永久挂死
            // （会话无退出码），旧版 waitFor() 无限等 → 启动链卡死、
            // UI 无反馈。超时杀掉后错误行进前缀日志窗口，问题可见。
            // v3.2.0：10 → 15 分钟（用户需在 X11 窗口内确认安装提示框，
            // 人为交互时间不可控）。
            val TIMEOUT_MIN = 15L
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
                val finished = runCatching {
                    executor.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)
                }.getOrDefault(false)
                if (!finished) {
                    // v3.2.0：超时先抓挂死现场（进程 state/wchan）再杀 ——
                    // 无输出挂死从此有内核级证据（R=死循环嫌疑、
                    // S+wchan=futex=等锁死锁、D=IO 阻塞）。
                    dumpWineHangSnapshot(resolved, container, log)
                    runCatching { executor.stopAndWait(true, 10, TimeUnit.SECONDS) }
                    lastCode = -1
                    log("wineboot -i 第 ${attempt} 次超时（>${TIMEOUT_MIN} 分钟无退出），已强制结束" +
                        if (attempt == 1) "，清残留后重试一次…" else "")
                    log("→ 典型原因：wineserver 握手挂起 / wow64 初始化崩溃 / 提示框等待确认；请把本窗口日志反馈给开发者")
                } else {
                    lastCode = runCatching { executor.waitFor() }.getOrDefault(-1)
                }
                // v3.2.0：成败以 system.reg 落盘为准 —— explorer 虚拟桌面
                // 不保证透传 wineboot 退出码，system.reg 是前缀创建完成
                // 的权威信号（官方 wineboot 成功必落盘）。
                if (File(container.prefixPath, "system.reg").isFile) break
                if (lastCode == 0) break
                if (lastCode != 0 && finished) {
                    log("wineboot -i 第 ${attempt} 次退出码 $lastCode（前缀 system.reg 未落盘）" +
                        if (attempt == 1) "，重试一次…" else "")
                }
                if (attempt == 1) {
                    // 残留 wineserver 可能让重试挂死——清一次再试
                    runCatching { WineServerUtils.stop(resolved, container, env) }
                }
            }
            if (lastCode == 0 || File(container.prefixPath, "system.reg").isFile) {
                log("wineboot -i 完成（前缀 system.reg 已落盘）")
                true
            } else {
                log("wineboot -i 失败（退出码 $lastCode，159=SIGSYS/旧包fsync、1=加载失败、-1=超时强杀）")
                log("→ 请用 v3.1.4+ 构建的 wine 包（fsync 守卫 + 自包含依赖库）后重建容器")
                false
            }
        } catch (e: Exception) {
            log("wineboot 初始化异常：${e.message}")
            false
        }
    }

    /**
     * v3.2.0：wineboot 超时挂死现场抓取 —— 扫 /proc 找 wine 进程树
     * （cmdline 含 wine 目录或 WINEPREFIX 特征），把每个进程的调度状态
     * （R 运行/S 睡眠/D 不可中断IO/Z 僵尸）与各线程的内核等待点（wchan）
     * 写进前缀日志。proton10 arm64ec“无任何输出永久挂死”下一轮就有
     * 内核级证据面（R=死循环嫌疑、S+wchan=futex=等锁死锁、D=IO 阻塞）。
     */
    private fun dumpWineHangSnapshot(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        log: (String) -> Unit
    ) {
        runCatching {
            // wine 的 PE 子进程（wineboot/services/explorer…）cmdline 是
            // Windows 风格（不含 unix 路径），需按 exe 名一并匹配；
            // /proc 下非本 UID 进程读取即失败，自动被过滤，无越权风险。
            val markers = listOf(
                resolved.wineDir.absolutePath,
                container.prefixPath,
                "wineboot", "wineserver", "explorer.exe",
                "services.exe", "winedevice.exe", "plugplay.exe", "start.exe"
            )
            val procs = File("/proc").listFiles { f -> f.name.toIntOrNull() != null }
                .orEmpty().mapNotNull { proc ->
                    val cmdline = runCatching {
                        File(proc, "cmdline").readText().replace('\u0000', ' ').trim()
                    }.getOrNull() ?: return@mapNotNull null
                    if (markers.any { cmdline.contains(it) }) proc to cmdline else null
                }
            if (procs.isEmpty()) {
                log("挂死现场: 未找到 wine 相关进程（可能已退出/被杀）")
                return
            }
            log("挂死现场（${procs.size} 个 wine 相关进程；R=运行 S=睡眠 D=IO阻塞 Z=僵尸）:")
            for ((proc, cmdline) in procs) {
                val state = runCatching {
                    File(proc, "stat").readText().split(" ").getOrNull(2) ?: "?"
                }.getOrDefault("?")
                log("  pid=${proc.name} state=$state cmd=${cmdline.take(120)}")
                val tasks = runCatching {
                    File(proc, "task").listFiles { f -> f.name.toIntOrNull() != null }
                }.getOrNull() ?: continue
                for (task in tasks.take(24)) {
                    val tid = task.name
                    val tState = runCatching {
                        File(task, "stat").readText().split(" ").getOrNull(2) ?: "?"
                    }.getOrDefault("?")
                    val wchan = runCatching {
                        File(task, "wchan").readText().trim().ifEmpty { "?" }
                    }.getOrDefault("?")
                    log("    tid=$tid state=$tState wchan=$wchan")
                }
            }
        }
    }
}
