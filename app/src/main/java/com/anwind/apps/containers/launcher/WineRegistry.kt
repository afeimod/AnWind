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
     * 确保前缀的 dosdevices 盘符映射完整（v3.1 新增；v3.2.24 扩展）。
     *
     * wineboot 建前缀时会创建 c: → ../drive_c 与 z: → /，但个别精简
     * 构建 / 异常中断的前缀会缺 z: —— 之后：
     *  - 用户从 App 传入的 Unix 绝对路径（/storage/emulated/0/…）wine
     *    自动按 Z: 盘换算，z: 缺失即整个宿主文件系统在 wine 内不可达；
     *  - 游戏按绝对路径（Z:\\storage\\…）找 media 等资源时直接报
     *    "找不到 media"。
     *
     * v3.2.24（用户问题 3，对齐 startwine.sh / startfex.sh 的盘符矩阵）：
     *  - d: → /storage/emulated/0/Download（存在时）：参考链路的
     *    D 盘 —— 用户下载的 exe/游戏/资源的主入口，wine 安装器与游戏
     *    打开/另存对话框默认可达；目录不存在（无外部存储权限机型）
     *    时跳过，不留死链。
     *  - e: → /storage/emulated/0/exagear（存在时）：参考链路的 E 盘，
     *    老用户从 exagear 目录迁移数据时的习惯路径。
     *
     * 幂等：仅在链接缺失时补建（z: → / 绝对符号链接；c: 缺失时指向
     * 前缀内 drive_c，与 wine 默认布局一致；d:/e: 同样仅在目标目录
     * 存在且链接缺失时建）。前缀尚未初始化（无 dosdevices 目录）时
     * 跳过 —— 归 wineboot 管。
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

            // v3.2.24（用户问题 3）：d:/e: 盘符（对齐 startwine.sh 的
            // ln -sf /storage/emulated/0/Download …dosdevices/d: 等）。
            // 仅目标目录存在且盘符缺失时补建 —— 不覆盖用户已有映射
            //（用户可能把 d: 指到别处），目标不存在时跳过不留死链。
            listOf(
                "d:" to "/storage/emulated/0/Download",
                "e:" to "/storage/emulated/0/exagear"
            ).forEach { (drive, targetPath) ->
                val target = File(targetPath)
                val link = File(dd, drive)
                if (!link.exists() && target.isDirectory) {
                    try {
                        android.system.Os.symlink(targetPath, link.absolutePath)
                        log("已补 dosdevices $drive → $targetPath（参考 startwine.sh 盘符矩阵）")
                    } catch (e: Exception) {
                        log("警告: $drive 盘补建失败（${e.message}）")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // v3.2.11（用户问题 3）：wine 内建窗口现代化外观
    // ------------------------------------------------------------------

    /** 现代化外观已应用的标记文件（容器前缀内；失败不落标记，下次自动重试）。 */
    private fun modernUiMarker(prefix: File) = File(prefix, ".anwind-modern-ui")

    /**
     * wine 创建的所有窗口现代化（一次成型，幂等标记）。
     *
     * 作用域：wine 自绘窗口/对话框/菜单 —— wineboot 进度框、winecfg、
     * regedit、notepad、explorer 虚拟桌面、文件公共对话框、消息框，以及
     * 老式 Win32 程序的控件。（游戏/现代应用自绘皮肤不在此列。）
     *
     * 五件套（对齐现代 Windows 观感）：
     *  1) **ClearType 字体平滑** —— wine 默认 FontSmoothing=0，锯齿文本
     *     观感停留在 Win95；置 FontSmoothing=2 + Type=2（RGB 子像素）
     *     + Gamma=1000，文本立变现代；
     *  2) **拖动显示窗口内容** —— wine 默认 DragFullWindows=0，拖动只画
     *     一片虚线外框，非常 DOS；置 1；
     *  3) **视觉样式主题** —— 激活 wine 9.x+/10 自带的 light.msstyles
     *     扁平现代主题（ThemeActive=1）；旧前缀无 light 时回退
     *     aero.msstyles（Win7 风）；两者皆无则跳过主题键（尊重树形态）；
     *  4) **现代配色** —— 选区/热跟踪用 Windows 10/11 强调蓝
     *     （0 120 215 / 0 102 204），桌面兜底色柔和深蓝灰；
     *  5) **渐变壁纸** —— 程序化生成 24bit BMP（深空蓝→湖蓝渐变 + 右上
     *     辉光）写入 drive_c/anwind/，注册到 Control Panel\Desktop。
     *     explorer 虚拟桌面（dmode=v / 前缀构建）经 wine 自带壁纸管线
     *     绘制（BMP 为 wine 唯一保真格式）；渲染不出时桌面显示第 4 项
     *     兜底色，无副作用。
     *
     * 单次 wine regedit /s 导入全部键（一个 wine 进程完成，不逐键起进程）。
     * @return true 表示已就位（含标记命中）。
     */
    fun ensureModernLook(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return true   // 前缀未建：留给 wineboot 后的下次启动
        if (modernUiMarker(prefix).isFile) return true
        log("应用 wine 窗口现代化外观（ClearType / 拖动内容 / light 主题 / 现代配色 / 壁纸）……")
        return try {
            val anwindDir = File(prefix, "drive_c/anwind")
            anwindDir.mkdirs()

            // ---- 第 5 件：程序化渐变壁纸（24bit BMP） ----
            val wallpaperWin = if (generateModernWallpaper(File(anwindDir, "wallpaper.bmp"))) {
                "C:\\\\anwind\\\\wallpaper.bmp"
            } else ""

            // ---- 第 3 件：主题文件探测（light 优先，aero 兜底；大小写不敏感——
            //      Linux 文件系统区分大小写，个别 wine 包目录形态与 wine.inf 不一致）----
            val theme = findModernTheme(prefix)
            // ---- 汇总 .reg（REGEDIT4 / ASCII，无编码坑） ----
            val reg = buildString {
                appendLine("REGEDIT4")
                appendLine()
                appendLine("[HKEY_CURRENT_USER\\Control Panel\\Desktop]")
                appendLine("\"FontSmoothing\"=\"2\"")
                appendLine("\"FontSmoothingType\"=dword:00000002")
                appendLine("\"FontSmoothingGamma\"=dword:000003e8")
                appendLine("\"FontSmoothingOrientation\"=dword:00000001")
                appendLine("\"DragFullWindows\"=\"1\"")
                if (wallpaperWin.isNotEmpty()) {
                    appendLine("\"Wallpaper\"=\"$wallpaperWin\"")
                    appendLine("\"WallpaperStyle\"=\"2\"")
                    appendLine("\"TileWallpaper\"=\"0\"")
                }
                appendLine()
                appendLine("[HKEY_CURRENT_USER\\Control Panel\\Colors]")
                appendLine("\"Background\"=\"47 54 68\"")
                appendLine("\"Highlight\"=\"0 120 215\"")
                appendLine("\"HotTrackingColor\"=\"0 102 204\"")
                appendLine()
                if (theme != null) {
                    appendLine("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\ThemeManager]")
                    appendLine("\"ThemeActive\"=\"1\"")
                    appendLine("\"DllName\"=\"${theme.second.replace("\\", "\\\\")}\"")
                    appendLine("\"ColorName\"=\"${theme.third}\"")
                    appendLine("\"SizeName\"=\"NormalSize\"")
                    appendLine()
                }
            }
            val regFile = File(anwindDir, "modern-ui.reg")
            regFile.writeText(reg)

            val cmd = NativeShellExecutor.create()
                .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(),
                            "regedit", "/s", "C:\\anwind\\modern-ui.reg")
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.HOME_DIR)
                .exec()
            // 超时即告失败（不阻塞启动链；不落标记 → 下次启动自动重试）
            val finished = runCatching { cmd.waitFor(120, TimeUnit.SECONDS) }.getOrDefault(false)
            val code = if (finished) runCatching { cmd.waitFor() }.getOrDefault(-1) else -1
            if (finished && code == 0) {
                runCatching { modernUiMarker(prefix).writeText(buildString {
                    appendLine("applied=1")
                    appendLine("wallpaper=${wallpaperWin.isNotEmpty()}")
                    appendLine("theme=${theme?.first ?: "none"}")
                }) }
                log("wine 窗口现代化外观已生效（主题=${theme?.first ?: "未启用（前缀无内置主题文件）"}，壁纸=${if (wallpaperWin.isNotEmpty()) "已安装" else "生成失败跳过"}）")
                true
            } else {
                log("警告: 现代化外观导入未确认成功（退出码=$code；下次启动自动重试）")
                false
            }
        } catch (e: Exception) {
            log("警告: 现代化外观应用异常：${e.message}（下次启动自动重试）")
            false
        }
    }

    // ------------------------------------------------------------------
    // v3.2.14（用户问题 3）：HKCU\Software\Wine\DllOverrides 注入
    // ------------------------------------------------------------------

    /** DllOverrides 已写入的标记文件（容器前缀内）。 */
    private fun dllOverridesMarker(prefix: File) = File(prefix, ".anwind-dlloverrides")

    /**
     * v3.2.14（用户问题 3）：构建前缀时注入 Software\Wine\DllOverrides ——
     * DXVK/DirectX 组件组合拳的注册表配置（d3d9/dxgi/wined3d 等 native 优先、
     * xinput/quartz/dinput builtin 优先，共 147 项，列表与用户提供的
     * 配置逐字一致）。
     *
     * 单次 wine regedit /s 导入（一个 wine 进程完成全部键值，与
     * [ensureModernLook] 同一模式），不逐键起进程。幂等标记
     * .anwind-dlloverrides；失败不阻断启动链（下次自动重试）。
     *
     * native,builtin 语义：前缀内 system32/syswow64 有对应 DLL（如注入的
     * DXVK）则优先加载，否则回退 wine 内建 —— 未注入 DXVK 的容器该配置
     * 无副作用，无需按容器开关区分。
     */
    fun ensureDllOverrides(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) return true   // 前缀未建：留给 wineboot 后的下次启动
        if (dllOverridesMarker(prefix).isFile) return true
        log("注入 Direct3D/DXVK DLL 覆盖注册表（HKCU\\Software\\Wine\\DllOverrides，147 项）……")
        return try {
            val anwindDir = File(prefix, "drive_c/anwind")
            anwindDir.mkdirs()
            val reg = buildString {
                appendLine("REGEDIT4")
                appendLine()
                appendLine("[HKEY_CURRENT_USER\\Software\\Wine\\DllOverrides]")
                for ((name, value) in DLL_OVERRIDE_ENTRIES) {
                    appendLine("\"$name\"=\"$value\"")
                }
            }
            val regFile = File(anwindDir, "dlloverrides.reg")
            regFile.writeText(reg)

            val cmd = NativeShellExecutor.create()
                .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(),
                            "regedit", "/s", "C:\\anwind\\dlloverrides.reg")
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.HOME_DIR)
                .exec()
            val finished = runCatching { cmd.waitFor(120, TimeUnit.SECONDS) }.getOrDefault(false)
            val code = if (finished) runCatching { cmd.waitFor() }.getOrDefault(-1) else -1
            if (finished && code == 0) {
                runCatching { dllOverridesMarker(prefix).writeText("applied=1\nentries=${DLL_OVERRIDE_ENTRIES.size}") }
                log("DllOverrides 已写入（${DLL_OVERRIDE_ENTRIES.size} 项，DXVK native 优先组合）")
                true
            } else {
                log("警告: DllOverrides 导入未确认成功（退出码=$code；下次启动自动重试）")
                false
            }
        } catch (e: Exception) {
            log("警告: DllOverrides 注入异常：${e.message}（下次启动自动重试）")
            false
        }
    }

    /** v3.2.14：DllOverrides 键值表（用户提供配置逐字转换，勿随意改动）。 */
    private val DLL_OVERRIDE_ENTRIES: List<Pair<String, String>> = listOf(
        "amstream" to "native,builtin",
        "atl100" to "native,builtin",
        "d3d10" to "native,builtin",
        "d3d10_1" to "native,builtin",
        "d3d10core" to "native,builtin",
        "d3d11" to "native,builtin",
        "d3d12" to "native,builtin",
        "d3d12core" to "native,builtin",
        "d3d8" to "native,builtin",
        "d3d9" to "native,builtin",
        "d3dcompiler_33" to "native,builtin",
        "d3dcompiler_34" to "native,builtin",
        "d3dcompiler_35" to "native,builtin",
        "d3dcompiler_36" to "native,builtin",
        "d3dcompiler_37" to "native,builtin",
        "d3dcompiler_38" to "native,builtin",
        "d3dcompiler_39" to "native,builtin",
        "d3dcompiler_40" to "native,builtin",
        "d3dcompiler_41" to "native,builtin",
        "d3dcompiler_42" to "native,builtin",
        "d3dcompiler_43" to "native,builtin",
        "d3dcsx_42" to "native,builtin",
        "d3dcsx_43" to "native,builtin",
        "d3dx10" to "native,builtin",
        "d3dx10_33" to "native,builtin",
        "d3dx10_34" to "native,builtin",
        "d3dx10_35" to "native,builtin",
        "d3dx10_36" to "native,builtin",
        "d3dx10_37" to "native,builtin",
        "d3dx10_38" to "native,builtin",
        "d3dx10_39" to "native,builtin",
        "d3dx10_40" to "native,builtin",
        "d3dx10_41" to "native,builtin",
        "d3dx10_42" to "native,builtin",
        "d3dx10_43" to "native,builtin",
        "d3dx11_42" to "native,builtin",
        "d3dx11_43" to "native,builtin",
        "d3dx9_24" to "native,builtin",
        "d3dx9_25" to "native,builtin",
        "d3dx9_26" to "native,builtin",
        "d3dx9_27" to "native,builtin",
        "d3dx9_28" to "native,builtin",
        "d3dx9_29" to "native,builtin",
        "d3dx9_30" to "native,builtin",
        "d3dx9_31" to "native,builtin",
        "d3dx9_32" to "native,builtin",
        "d3dx9_33" to "native,builtin",
        "d3dx9_34" to "native,builtin",
        "d3dx9_35" to "native,builtin",
        "d3dx9_36" to "native,builtin",
        "d3dx9_37" to "native,builtin",
        "d3dx9_38" to "native,builtin",
        "d3dx9_39" to "native,builtin",
        "d3dx9_40" to "native,builtin",
        "d3dx9_41" to "native,builtin",
        "d3dx9_42" to "native,builtin",
        "d3dx9_43" to "native,builtin",
        "ddraw" to "native,builtin",
        "dinput" to "builtin,native",
        "dinput8" to "native,builtin",
        "dmband" to "native,builtin",
        "dmcompos" to "native,builtin",
        "dmime" to "native,builtin",
        "dmloader" to "native,builtin",
        "dmscript" to "native,builtin",
        "dmstyle" to "native,builtin",
        "dmsynth" to "native,builtin",
        "dmusic" to "native,builtin",
        "dmusic32" to "native,builtin",
        "dplaysvr.exe" to "native,builtin",
        "dplayx" to "native,builtin",
        "dpmodemx" to "native,builtin",
        "dpnet" to "native,builtin",
        "dpnhpast" to "native,builtin",
        "dpnhupnp" to "native,builtin",
        "dpnsvr.exe" to "native,builtin",
        "dpwsockx" to "native,builtin",
        "dsound" to "native,builtin",
        "dswave" to "native,builtin",
        "dxgi" to "native,builtin",
        "mf" to "native,builtin",
        "msvcm80" to "native,builtin",
        "msvcp100" to "native,builtin",
        "msvcp80" to "native,builtin",
        "msvcr100" to "native,builtin",
        "msvcr80" to "native,builtin",
        "qasf" to "native,builtin",
        "qcap" to "native,builtin",
        "qdvd" to "native,builtin",
        "qedit" to "native,builtin",
        "quartz" to "builtin,native",
        "vcomp" to "native,builtin",
        "vcomp100" to "native,builtin",
        "version" to "native,builtin",
        "wined3d" to "native,builtin",
        "winhttp" to "native,builtin",
        "wmadmod" to "native,builtin",
        "wmasf" to "native,builtin",
        "wmvcore" to "native,builtin",
        "wmvdecod" to "native,builtin",
        "x3daudio1_0" to "native,builtin",
        "x3daudio1_1" to "native,builtin",
        "x3daudio1_2" to "native,builtin",
        "x3daudio1_3" to "native,builtin",
        "x3daudio1_4" to "native,builtin",
        "x3daudio1_5" to "native,builtin",
        "x3daudio1_6" to "native,builtin",
        "x3daudio1_7" to "native,builtin",
        "xactengine2_0" to "native,builtin",
        "xactengine2_1" to "native,builtin",
        "xactengine2_10" to "native,builtin",
        "xactengine2_2" to "native,builtin",
        "xactengine2_3" to "native,builtin",
        "xactengine2_4" to "native,builtin",
        "xactengine2_5" to "native,builtin",
        "xactengine2_6" to "native,builtin",
        "xactengine2_7" to "native,builtin",
        "xactengine2_8" to "native,builtin",
        "xactengine2_9" to "native,builtin",
        "xactengine3_0" to "native,builtin",
        "xactengine3_1" to "native,builtin",
        "xactengine3_2" to "native,builtin",
        "xactengine3_3" to "native,builtin",
        "xactengine3_4" to "native,builtin",
        "xactengine3_5" to "native,builtin",
        "xactengine3_6" to "native,builtin",
        "xactengine3_7" to "native,builtin",
        "xapofx1_0" to "native,builtin",
        "xapofx1_1" to "native,builtin",
        "xapofx1_2" to "native,builtin",
        "xapofx1_3" to "native,builtin",
        "xapofx1_4" to "native,builtin",
        "xapofx1_5" to "native,builtin",
        "xaudio2_0" to "native,builtin",
        "xaudio2_1" to "native,builtin",
        "xaudio2_2" to "native,builtin",
        "xaudio2_3" to "native,builtin",
        "xaudio2_4" to "native,builtin",
        "xaudio2_5" to "native,builtin",
        "xaudio2_6" to "native,builtin",
        "xaudio2_7" to "native,builtin",
        "xinput1_1" to "builtin,native",
        "xinput1_2" to "builtin,native",
        "xinput1_3" to "builtin,native",
        "xinput1_4" to "builtin,native",
        "xinput9_1_0" to "builtin,native",
        "xinputuap" to "builtin,native"
    )

    /**
     * 探测前缀内可用的现代视觉主题（大小写不敏感；Linux 文件系统区分大小写，
     * 注册表 DllName 必须与实际目录/文件名逐字一致才能被 uxtheme 打开）。
     * light（wine 9.x+/10 扁平现代主题）优先，aero（Win7 风）兑底。
     * @return (label, Windows 路径, ColorName)；无可用主题返回 null。
     */
    private fun findModernTheme(prefix: File): Triple<String, String, String>? {
        return runCatching {
            val windows = File(prefix, "drive_c/windows")
            if (!windows.isDirectory) return@runCatching null
            val res = windows.listFiles().orEmpty()
                .firstOrNull { it.isDirectory && it.name.equals("resources", true) }
            val themes = (res ?: windows).listFiles().orEmpty()
                .firstOrNull { it.isDirectory && it.name.equals("themes", true) }
                ?: return@runCatching null
            for ((label, color) in listOf("light" to "Blue", "aero" to "NormalColor")) {
                val dir = themes.listFiles().orEmpty()
                    .firstOrNull { it.isDirectory && it.name.equals(label, true) } ?: continue
                val f = dir.listFiles().orEmpty()
                    .firstOrNull { it.isFile && it.name.equals("$label.msstyles", true) } ?: continue
                // 从 msstyles 文件回溯到 windows 目录，用实际名拼 Windows 路径
                // （保真大小写，避免注册表值指向不存在的路径）
                val parts = ArrayDeque<String>()
                var node: File? = f
                while (node != null && node != windows) {
                    parts.addFirst(node.name)
                    node = node.parentFile
                }
                if (parts.isEmpty()) continue
                return@runCatching Triple(label, "C:\\windows\\" + parts.joinToString("\\"), color)
            }
            null
        }.getOrNull()
    }

    /**
     * 生成现代化渐变壁纸（24bit BMP，1600×900，bottom-up，BI_RGB）。
     * 深空蓝（16,26,44）→ 湖蓝（38,74,110）垂直渐变，右上角叠加青蓝辉光，
     * 底部一条微光地平线 —— 无外部依赖、纯像素数学，写入前缀一次。
     * @return true 写入成功。
     */
    private fun generateModernWallpaper(out: File): Boolean {
        return try {
            val w = 1600
            val h = 900
            val rowBytes = w * 3
            val pad = (4 - (rowBytes % 4)) % 4
            val imageSize = (rowBytes + pad) * h
            val bos = java.io.ByteArrayOutputStream(54 + imageSize)
            fun le32(v: Int) { bos.write(v and 0xff); bos.write((v shr 8) and 0xff); bos.write((v shr 16) and 0xff); bos.write((v shr 24) and 0xff) }
            fun le16(v: Int) { bos.write(v and 0xff); bos.write((v shr 8) and 0xff) }
            // BITMAPFILEHEADER
            bos.write('B'.code); bos.write('M'.code)
            le32(54 + imageSize); le32(0); le32(54)
            // BITMAPINFOHEADER
            le32(40); le32(w); le32(h); le16(1); le16(24); le32(0)
            le32(imageSize); le32(2835); le32(2835); le32(0); le32(0)
            // 像素（BMP 自底向上）
            val glowX = w * 0.76f; val glowY = h * 0.24f; val glowR = h * 0.92f
            fun cap(v: Float) = v.coerceIn(0f, 255f).toInt()
            for (y in h - 1 downTo 0) {
                val ty = y.toFloat() / (h - 1)
                for (x in 0 until w) {
                    var r = 16f + (38f - 16f) * ty
                    var g = 26f + (74f - 26f) * ty
                    var b = 44f + (110f - 44f) * ty
                    // 右上辉光
                    val dx = x - glowX; val dy = y - glowY
                    val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (dist < glowR) {
                        val f = (1f - dist / glowR); val glow = f * f * f
                        r += glow * 26f; g += glow * 64f; b += glow * 92f
                    }
                    // 底部地平线微光
                    val hz = ty * ty * 14f
                    r += hz * 0.3f; g += hz * 0.6f; b += hz
                    bos.write(cap(b)); bos.write(cap(g)); bos.write(cap(r))   // BGR
                }
                repeat(pad) { bos.write(0) }
            }
            out.writeBytes(bos.toByteArray())
            true
        } catch (e: Exception) {
            false
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
     * v3.2.1（用户问题 5）新增三道防线：
     * ③ wineboot 前先跑【加载器冒烟测试】（`wine --version`，60 秒限时）——
     *   proton x86_64 "直接退出没任何日志"、arm64ec "无输出挂死" 这类
     *   静默阵亡，根因在 loader/早期初始化而非 wineboot 逻辑；冒烟测试
     *   把它们拦在前级并给出可操作结论（退出码 159=SIGSYS/fsync 探测、
     *   127/126=可执行体缺失、超时=loader 挂死）。
     * ④ 无输出看门狗：4 分钟零输出且前缀未落盘时提前抓取 /proc 挂死
     *   现场（不必等到 15 分钟超时；mono/gecko 提示框等待用户确认属
     *   正常场景，看门狗只取证不杀进程）。
     * ⑤ 退出码解读表直接写进前缀日志。
     *
     * v3.2.1（用户问题 3）：[vdResolution] —— 容器配置的分辨率；wineboot 的
     * explorer 虚拟桌面与握手分辨率一致（无则维持旧“当前屏幕”语义）。
     *
     * @return true 表示初始化完成（或已有前缀）。
     */
    fun initializePrefixIfNeeded(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        vdResolution: String = "",
        log: (String) -> Unit = {}
    ): Boolean {
        if (File(container.prefixPath, "system.reg").isFile) return true
        log("首次使用，初始化 wine 前缀: ${container.prefixPath}")
        log("提示: 构建过程中的 mono/gecko 等安装提示框将显示在『X11 桌面』窗口（explorer 虚拟桌面）内，请在该窗口确认")
        return try {
            // v3.2.1 ③：加载器冒烟测试 —— 静默阵亡拦在前级并给出结论
            if (!loaderSmokeTest(resolved, container, env, log)) {
                log("→ 加载器冒烟测试未通过，已放弃 wineboot（结论见上；请先解决该问题再重建容器）")
                return false
            }
            // v3.1.4：统一经 wine loader（box64 形态自动包装），不再直连
            // bin/wineboot 宿主文件（脚本/符号链接形态均不可靠）。
            // v3.2.0（用户诉求 4）：包 explorer /desktop 虚拟桌面 —— 无 WM
            // 裸窗口下 mono/gecko 提示框堆在 (0,0) 黑屏角落（用户实测“x11
            // 没有任何画面、提示框看不到、无法继续构建”）；虚拟桌面铺满 X
            // 屏幕，子窗口由 wine 自管，提示框可见可点。
            // v3.2.1：分辨率优先用容器配置值（与握手一致）。
            val vdRes = vdResolution.ifEmpty { RootfsLauncher.currentXScreenResolution() }
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
            // v3.2.1 ④：无输出看门狗 —— 4 分钟零输出且前缀未落盘时提前取证
            val WATCHDOG_MIN = 4L
            var lastCode = -1
            for (attempt in 1..2) {
                var gotOutput = false
                val watchdog = Thread({
                    try { Thread.sleep(WATCHDOG_MIN * 60_000L) } catch (_: InterruptedException) { return@Thread }
                    if (!gotOutput && !File(container.prefixPath, "system.reg").isFile) {
                        log("wineboot -i 已 ${WATCHDOG_MIN} 分钟无任何输出且 system.reg 未落盘 ——")
                        log("（mono/gecko 安装提示框等待确认属正常；否则疑似挂死）提前抓取进程现场：")
                        dumpWineHangSnapshot(resolved, container, log)
                    }
                }, "anwind-prefix-watchdog").apply { isDaemon = true; start() }
                val executor = NativeShellExecutor.create()
                    .setCommand(*cmdPrefix.toTypedArray(), "wineboot", "-i")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.HOME_DIR)
                    .onStdOut { gotOutput = true; log(it) }
                    .onStdErr { gotOutput = true; log(it) }
                    .exec()
                val finished = runCatching {
                    executor.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)
                }.getOrDefault(false)
                watchdog.interrupt()
                if (!finished) {
                    // v3.2.0：超时先抓挂死现场（进程 state/wchan）再杀 ——
                    // 无输出挂死从此有内核级证据（R=死循环嫌疑、
                    // S+wchan=futex=等锁死锁、D=IO 阻塞）。
                    dumpWineHangSnapshot(resolved, container, log)
                    runCatching { executor.stopAndWait(true, 10, TimeUnit.SECONDS) }
                    lastCode = -1
                    log("wineboot -i 第 ${attempt} 次超时（>${TIMEOUT_MIN} 分钟无退出），已强制结束" +
                        if (attempt == 1) "，清残留后重试一次…" else "")
                    log("→ 典型原因：残留/冻结的 wineserver 使客户端阻塞在握手（v3.3.1 已自动硬清理）/ 提示框等待确认 / 早期初始化崩溃；请把本窗口日志反馈给开发者")
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
                    // v3.2.1 ⑤：退出码解读（信号位 = 128+信号号）
                    when (lastCode) {
                        159 -> log("→ 159 = SIGSYS：fsync 的 futex_waitv 能力探测被 Android seccomp 击杀 —— 旧包无 __ANDROID__ 守卫，请用 v3.1.4+ CI 重编")
                        139 -> log("→ 139 = SIGSEGV：loader/早期初始化段错误（贴日志给开发者）")
                        13 -> log("→ 13 = SIGPIPE：X 连接断开（X 服务未起/中途退出）")
                        126, 127 -> log("→ $lastCode = 可执行体无法启动（box64/wine 路径或权限问题）")
                    }
                }
                if (attempt == 1) {
                    // v3.3.1：重试前硬清 wineserver（旧版仅 -k/-w —— 冻结/挂死的
                    // 守护 -k 杀不掉，重试必再挂在同一处；决策行进前缀日志）
                    runCatching {
                        WineServerUtils.ensureCleanBeforeStart(resolved, container, env, log = { log(it) })
                    }
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
     * v3.2.1 ③：wine 加载器冒烟测试（`wine --version`，限时 60 秒）。
     *
     * 动机（设备实测两份日志）：proton x86_64 前缀构建"直接退出没任何日志"、
     * arm64ec“无任何输出挂死”—— 这类静默阵亡发生在 loader/早期初始化，
     * 与 wineboot 逻辑无关；wineboot 包裹 explorer 虚拟桌面后，被信号
     * 杀死的进程连 stderr 缓冲都来不及刷（explorer 不透传退出码），
     * 前缀日志窗口从此“什么都没有”。冒烟测试直连 loader：
     *
     * - 退出码 0 + 版本号 → 一切就绪，顺带把版本写进前缀日志；
     * - 159（SIGSYS）→ fsync 探测被 seccomp 击杀（旧包，无 __ANDROID__ 守卫）；
     * - 秒退无输出 → loader 阶段阵亡（v3.2.3 定位：wine-preloader 预加载器
     *   在 bionic/box64 下交接失败；旧包缺 x86_64-bionic 闭包时 box64 的
     *   "Symbol … not found" stderr 也可能被信号丢失）；
     * - 超时 → loader 本身挂死（arm64ec：预加载器交接后的第二遍
     *   __wine_main 挂在 virtual_init/早期初始化 —— v3.2.3 构建侧
     *   disable_preloader_android 根治）。
     *
     * 注意：--version 在 wine ≤10 树由 check_command_line 处理，位于
     * 预加载器再执行**之前** —— 冒烟测试通过不代表预加载器路径健康
     * （v3.2.3 设备日志实证：proton arm64ec --version 可过、wineboot
     * 挂死）。它只覆盖 loader/ntdll 加载层的其它故障（符号缺失、
     * SIGSYS、路径错误）。
     *
     * 结果按（wine 目录 + 容器）记忆：同一运行时只测一次，不拖慢正常启动。
     */
    private val smokeTested = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * v3.2.3：internal —— 手动 wineboot 会话（RootfsLauncher 8b 步）复用
     * 同一测试与缓存，不再私有。
     */
    internal fun loaderSmokeTest(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit
    ): Boolean {
        val cacheKey = "${resolved.wineDir.absolutePath}|${resolved.backend.id}|${container.name}"
        smokeTested[cacheKey]?.let { return it }
        log("加载器冒烟测试: ${WineRuntime.wineCommand(resolved).joinToString(" ")} --version …")
        // v3.2.3：剥离 WINELOADERNOEXEC —— wine ≤10 树的 --version 分发
        // （check_command_line）与该变量同一守卫块，不剥离会把
        // "--version" 当程序名误报失败。同时这不失为更严格的检验：
        // 直连 loader 的原始链路被真实地测到。
        val testEnv = LinkedHashMap(env)
        testEnv.remove("WINELOADERNOEXEC")
        val output = StringBuilder()
        return try {
            val executor = NativeShellExecutor.create()
                .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(), "--version")
                .putEnv(testEnv)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.HOME_DIR)
                .onStdOut { synchronized(output) { output.append(it).append('\n') } }
                .onStdErr { synchronized(output) { output.append(it).append('\n') } }
                .exec()
            val finished = runCatching {
                executor.waitFor(60, TimeUnit.SECONDS)
            }.getOrDefault(false)
            val code = runCatching { executor.waitFor() }.getOrDefault(-1)
            val text = synchronized(output) { output.toString().trim() }
            val ok = finished && code == 0 && text.isNotEmpty()
            if (ok) {
                log("加载器正常: ${text.lineSequence().first().take(60)}")
            } else if (!finished) {
                runCatching { executor.stopAndWait(true, 5, TimeUnit.SECONDS) }
                log("加载器冒烟测试超时（60 秒无退出）—— loader 本身挂死，非 wineboot 逻辑问题")
                log("→ 高度可疑：wine ≤10 树（proton_8/9/10 等）的 wine-preloader 预加载器交接（v3.2.3 已在启动链停用 bin/*-preloader，若仍挂请发回下方现场）；hangover-10.18+/wine-11 树无此路径")
                dumpWineHangSnapshot(resolved, container, log)
            } else {
                log("加载器冒烟测试失败: 退出码 $code，输出：${if (text.isEmpty()) "（无任何输出）" else text.take(400)}")
                when (code) {
                    159 -> log("→ 159 = SIGSYS：fsync futex_waitv 探测被 seccomp 击杀（旧包无 __ANDROID__ 守卫；启动链 1.5 步的探测改写通常已免重编根治，若仍出现请反馈）")
                    139 -> log("→ 139 = SIGSEGV：loader 段错误")
                    126, 127 -> log("→ $code = 可执行体无法启动（路径/权限）")
                    else -> {
                        if (text.isEmpty()) {
                            log("→ 秒退且无输出：box64/wine loader 阶段阵亡 —— 两类高发：①旧包缺 lib/x86_64-bionic 依赖闭包（box64 \"Symbol shm_open not found\"，stderr 常被丢失）；②wine-preloader 预加载器在 box64 仿真下跑不通（v3.2.3 已在启动链停用）。仍需重编时请用 v3.2.3+ CI（闭包 + 探测守卫 + 预加载器禁用）")
                        }
                    }
                }
            }
            // 只缓存成功结果：失败不缓存（用户重装/重编 wine 包后下次自动重测）
            if (ok) smokeTested[cacheKey] = true
            ok
        } catch (e: Exception) {
            log("加载器冒烟测试异常: ${e.message}")
            // 异常不代表 loader 坏（可能是执行器问题）—— 放行，让 wineboot 自己给证据
            true
        }
    }

    /**
     * v3.2.0：wineboot 超时挂死现场抓取 —— 扫 /proc 找 wine 进程树
     * （cmdline 含 wine 目录或 WINEPREFIX 特征），把每个进程的调度状态
     * （R 运行/S 睡眠/D 不可中断IO/Z 僵尸）与各线程的内核等待点（wchan）
     * 写进前缀日志。proton10 arm64ec“无任何输出永久挂死”下一轮就有
     * 内核级证据面（R=死循环嫌疑、S+wchan=futex=等锁死锁、D=IO 阻塞）。
     *
     * v3.2.3：internal —— 手动 wineboot 会话（RootfsLauncher 的前缀构建
     * 流）的看门狗同样需要抓现场，不再私有。
     */
    internal fun dumpWineHangSnapshot(
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
            // v3.3.1：wineserver 运行目录状态（socket/lock 残留 = 握手阻塞
            // 判定的另一面证据：socket 在而守护无响应）—— 先于进程清单打印：
            // 无任何 wine 进程但 socket 仍在 = 死 server 残留；两者俱全而
            // 客户端无输出 = 冻结/挂死 server 使握手阻塞
            val serverRoot = File(container.prefixPath, ".wineserver")
            if (serverRoot.isDirectory) {
                val subdirs = serverRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
                log("wineserver 目录: ${serverRoot.absolutePath}（${subdirs.size} 个 server 会话目录）")
                for (dir in subdirs) {
                    val sock = File(dir, "socket").exists()
                    val lock = File(dir, "lock").exists()
                    log("    ${dir.name}: socket=${if (sock) "存在" else "无"} lock=${if (lock) "存在" else "无"}")
                }
            } else {
                log("wineserver 目录: 不存在（$serverRoot —— 客户端将拉起全新 server）")
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
                // v3.3.1：wineserver 守护的 cwd（$WINEPREFIX/.wineserver/…）
                // —— 判定其归属前缀与是否残留的权威证据
                if (cmdline.contains("wineserver")) {
                    val cwd = runCatching { android.system.Os.readlink("/proc/${proc.name}/cwd") }.getOrNull()
                    if (cwd != null) log("    wineserver cwd=$cwd")
                }
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
