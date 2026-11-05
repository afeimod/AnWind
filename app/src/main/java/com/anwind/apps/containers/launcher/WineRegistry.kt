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
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
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
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
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

            // v3.2.28（startwine.sh 同款）：x: 盘 → 前缀内 drive_x
            // （mkdir -p $WINEPREFIX/drive_x; ln -sf …dosdevices/x: ——
            // 安装包脚本/快捷方式按 x: 引用前缀内资源时可达）。
            runCatching {
                val driveX = File(prefix, "drive_x")
                if (!driveX.isDirectory) driveX.mkdirs()
                val link = File(dd, "x:")
                if (!link.exists()) {
                    android.system.Os.symlink(driveX.absolutePath, link.absolutePath)
                    log("已补 dosdevices x: → $driveX（startwine.sh 同款）")
                }
            }.onFailure { log("警告: x: 盘补建失败（${it.message}）") }
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
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
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
    // v3.2.29（用户指令「把 apk 自带的都去掉，完全使用 conf 配置文件」）：
    // APK 不携带、不注入、也不清洗任何 DllOverrides —— v3.2.14~v3.2.28
    // 的内置 DllOverrides 表、purgeLegacyDllOverrides 清洗机制、
    // migrateApkBundledDxOverrides 迁移机制（及三个标记/扫描辅助）
    // 全部移除。前缀注册表的唯一来源是用户 /opt 生态
    // （bootstrapOptPrefix 导入 /opt/prefix/{user,system,fix-services}.reg）；
    // DLL 加载序如需临时覆盖，用容器 env= 的 WINEDLLOVERRIDES。
    // ------------------------------------------------------------------
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
                        log("wineboot 已 ${WATCHDOG_MIN} 分钟无任何输出且 system.reg 未落盘 ——")
                        log("（mono/gecko 安装提示框等待确认属正常；否则疑似挂死）提前抓取进程现场：")
                        dumpWineHangSnapshot(resolved, container, log)
                    }
                }, "anwind-prefix-watchdog").apply { isDaemon = true; start() }
                // v3.3.3（用户问题 3，startfex.sh 同款）：-i → -u —— 参考链
                // rm -rf 前缀后 wineboot -u 安装（前缀缺失时 -u 一样初始化），
                // 且附 WINEDLLOVERRIDES="mscoree=disabled"（参考同款）压掉
                // mono 安装器弹窗 —— 与下面 explorer 虚拟桌面配合，提示框
                // 不再阻塞构建。
                val executor = NativeShellExecutor.create()
                    .setCommand(*cmdPrefix.toTypedArray(), "wineboot", "-u")
                    .putEnv(env)
                    .putEnv("WINEDLLOVERRIDES", "mscoree=disabled")
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
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
                    log("wineboot 第 ${attempt} 次超时（>${TIMEOUT_MIN} 分钟无退出），已强制结束" +
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
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
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

    // ------------------------------------------------------------------
    // /opt/prefix 前缀预置资产注入（v3.2.25，startwine.sh 301-369 行移植）
    // ------------------------------------------------------------------

    /** opt/prefix 注入完成标记（幂等）。 */
    private fun optPrefixMarker(prefix: File) = File(prefix, ".anwind-opt-prefix")

    /**
     * v3.3.3：/opt/prefix 注册表 bionic regedit 兼容性规范化（用户问题 4：
     * “opt 生态包里的 reg 注册表总是报错，是不是格式不适合 bionic”），
     *
     * wine regedit 对导入文件的要求（reg_import.c 同源规则）：
     *  - 头行必须是 "WINE REGISTRY Version 2"（wine 原生）/"REGEDIT4"/
     *    "Windows Registry Editor Version 5.00" 之一；生态包手工维护的
     *    .reg 常缺头行 → regedit 逐键报错（GUI 弹窗即由此来）；
     *  - Windows regedit 导出的 5.00 文件常为 UTF-16LE+BOM，生态包二次
     *    保存可能丢 BOM / 换成带 BOM 的 UTF-8 → 解析失败；
     *  - CRLF 兼容，但 UTF-16 无 BOM 时首键会被吞。
     *
     * 规范化（不改 /opt 源文件 —— 规范化副本落前缀 .anwind-regtmp 再导入）：
     *  1. UTF-16LE(BOM) → UTF-8；UTF-8 BOM 剥离；
     *  2. CRLF/CR → LF；
     *  3. 缺头行时按首个键行的反斜杠形态补头：含 "\\"（双反斜杠转义）
     *     → "WINE REGISTRY Version 2"；否则 → "Windows Registry Editor
     *     Version 5.00"（hex(2)/hex(7) 值类型全兼容）；
     *  4. 已合法 → 不生成副本（返回 null，调用方原样导入）。
     *
     * @return 规范化副本路径；无需改动/读取失败时 null（调用方回退原文件）。
     */
    private fun normalizeRegForBionic(src: File, workDir: File, log: (String) -> Unit): File? {
        return runCatching {
            val raw = src.readBytes()
            if (raw.isEmpty()) return null
            val actions = mutableListOf<String>()
            val body: String = when {
                raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte() -> {
                    actions.add("UTF-16LE→UTF-8")
                    String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
                }
                raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte() -> {
                    actions.add("剥离 UTF-8 BOM")
                    String(raw, 3, raw.size - 3, Charsets.UTF_8)
                }
                else -> String(raw, Charsets.UTF_8)
            }
            val unified = body.replace("\r\n", "\n").replace('\r', '\n')
            if (unified != body) actions.add("CRLF→LF")
            val firstLine = unified.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val hasHeader = firstLine.startsWith("WINE REGISTRY") ||
                firstLine.equals("REGEDIT4", ignoreCase = true) ||
                firstLine.startsWith("Windows Registry Editor")
            val finalText = if (hasHeader) unified else {
                // 首个键行的反斜杠形态：wine 原生格式键路径双反斜杠转义
                //（[Software\\Wine\\...]），Windows 5.00 格式单反斜杠。
                val firstKey = unified.lineSequence()
                    .firstOrNull { it.trim().startsWith("[") }?.trim().orEmpty()
                val wineStyle = firstKey.contains("\\\\")
                actions.add(
                    if (wineStyle) "补 WINE REGISTRY Version 2 头行"
                    else "补 Windows Registry Editor Version 5.00 头行"
                )
                (if (wineStyle) "WINE REGISTRY Version 2\n\n" else "Windows Registry Editor Version 5.00\n\n") + unified
            }
            if (actions.isEmpty()) return null
            workDir.mkdirs()
            val out = File(workDir, src.name)
            out.writeText(finalText)
            log("→ 规范化: ${src.name}（${actions.joinToString("、")}）—— 以副本导入，/opt 源文件未改")
            out
        }.getOrNull()
    }

    /**
     * WinFusion 风格 wine 发行包的前缀预置资产注入（用户问题：
     * "构建前缀也没有解压 rootfs/opt/prefix/ 下的文件和注册表注册"）。
     *
     * 参考链路（startwine.sh 前缀构建段）：
     *  1. drive_c.tar.xz / directx.tar.xz → drive_c/
     *  2. start 目录 → ProgramData/Microsoft/Windows/Start Menu（开始菜单）
     *  3. marlett.ttf → windows/Fonts
     *  4. user.reg / system.reg / fix-services.reg → wine regedit 导入
     *  5. fix-fonts.tar.xz → windows/
     *  6. opt/dxvk1 下的 dxvk 归档 → windows/（virgl-enabled 标记存在时跳过）
     *  7. .update-timestamp=disable（参考脚本同款防重跑语义）
     *
     * 约定：
     *  - 仅当 opt/prefix 目录存在时生效（未安装 /opt 生态包零动作）；
     *  - 幂等（.anwind-opt-prefix 标记）；任一步失败仅记录、不阻断启动
     *    （与参考脚本容错语义一致；失败步骤下次重建容器时重试）；
     *  - 归档双后缀候选（.tar.xz/.txz/.tar）+ 魔数嗅探（tar.gz 强解压
     *    防御的运行期语义，与 AnWindTzstAssets 同源思路）；
     *  - 与终端侧 anwind-wine bootstrap_opt_prefix 同源双保险 —— 两端
     *    各自幂等，先到先应用（标记互认）。
     *
     * v3.2.26（用户问题："wineboot -i 结束后后续的解压以及注册表全都没
     * 有显示"）：全部早退分支改为**大声日志** —— 前缀未就绪 / 未检测到
     * /opt 生态 / 已注入，每一步都在前缀构建窗口可见；探测候选加入 wine
     * 槽位目录（rootfs/opt → 槽位 → rootfs/usr/opt），包内 conf/prefix
     * 与 bin/ 平级落进槽位的布局也能命中。
     *
     * @return true 表示无需注入（无 /opt/prefix / 已注入 / 前缀未就绪）。
     */
    fun bootstrapOptPrefix(
        resolved: WineRuntime.Resolved,
        container: ContainerData,
        env: Map<String, String>,
        log: (String) -> Unit = {}
    ): Boolean {
        val prefix = File(container.prefixPath)
        if (!File(prefix, "system.reg").isFile) {
            // 大声：这一步就是"wineboot 之后什么都不显示"的隐形早退之一
            log("跳过 /opt/prefix 预置资产注入：前缀未就绪（system.reg 缺失 —— wineboot 未成功）")
            val freeMb = runCatching { prefix.usableSpace / 1024 / 1024 }.getOrDefault(-1L)
            if (freeMb in 0..64) {
                log("→ 疑似根因：内部存储空间仅剩 ${freeMb}MB（wineboot 需写入上千个文件），请先清理空间再重建容器")
            }
            return true
        }
        if (optPrefixMarker(prefix).isFile) {
            // v3.3.3（用户日志实证：标记命中后每次启动零日志，用户误读为
            // “构建前缀在 wineboot 后就结束了/注册表没打”）—— 幂等跳过
            // 也要大声说出来了。
            log("/opt/prefix 已注入过（.anwind-opt-prefix 标记命中）—— 跳过重复注入")
            log("（如需重打注册表/资产：删除前缀内 .anwind-opt-prefix 标记或重建前缀）")
            return true
        }
        // v3.2.28：注入前先跑 /opt 生态自愈（rootfs/opt 顶层生态 tar 包
        // 自动解包 + 套层/槽位 opt/ 候选）—— 生态包没解开时这里是最后一道补救
        OptEcosystemSupport.ensure(resolved.wineDir, log)
        val root = WineEnvironment.optRoot(resolved.wineDir)
        if (root == null) {
            // 大声：探测了哪些路径、各有没有内容，一目了然（用户核对路径用）
            log("跳过 /opt/prefix 预置资产注入：未检测到 /opt 生态")
            log("→ 探测明细: ${WineEnvironment.optProbeReport(resolved.wineDir)}")
            log("→ 期望位置: /data/data/com.anwind/files/rootfs/opt/{conf,locale.conf,prefix,dxvk1} 或 wine 槽位目录内同名布局")
            return true
        }
        val pd = File(root, "prefix")
        if (!pd.isDirectory) {
            log("跳过 /opt/prefix 预置资产注入：$root 有 /opt 生态但无 prefix/ 目录（仅应用 conf/locale.conf）")
            return true
        }

        log("检测到 /opt/prefix 预置资产（${pd.absolutePath}），开始注入……")
        val driveC = File(prefix, "drive_c")
        val windows = File(driveC, "windows")

        // ① drive_c / directx 归档
        extractOptArchive(pd, "drive_c", driveC, log)
        extractOptArchive(pd, "directx", driveC, log)

        // ② 开始菜单快捷方式
        val startDir = File(pd, "start")
        if (startDir.isDirectory) {
            val menu = File(driveC, "ProgramData/Microsoft/Windows/Start Menu")
            runCatching {
                menu.mkdirs()
                startDir.let { src ->
                    com.anwind.util.SafeFileOps.copyTree(src, menu).also {
                        if (!it) log("警告: 开始菜单复制未完成（部分文件失败）")
                    }
                }
                log("开始菜单快捷方式 → ${menu.absolutePath}")
            }.onFailure { log("警告: 开始菜单复制失败: ${it.message}") }
        }

        // ③ marlett 字体
        File(pd, "marlett.ttf").takeIf { it.isFile }?.let { src ->
            runCatching {
                File(windows, "Fonts").mkdirs()
                src.copyTo(File(windows, "Fonts/marlett.ttf"), overwrite = true)
                log("marlett.ttf → windows/Fonts")
            }.onFailure { log("警告: marlett.ttf 复制失败: ${it.message}") }
        }

        // ④ 注册表导入（wine regedit 客户端 —— 与 ensureEmulateModeset 同一通路）
        val regTmpDir = File(prefix, ".anwind-regtmp")
        listOf("user.reg", "system.reg", "fix-services.reg").forEach { name ->
            val reg = File(pd, name)
            if (!reg.isFile) return@forEach
            log("导入注册表: $name")
            runCatching {
                // v3.3.3（用户问题 4）：bionic regedit 兼容性规范化 ——
                // 编码/BOM/头行/换行自动修复后以副本导入（见函数注释）。
                val importFile = normalizeRegForBionic(reg, regTmpDir, log) ?: reg
                val cmd = NativeShellExecutor.create()
                    // v3.3.2：补 /S 静默 —— regedit 导入失败时的错误弹窗
                    //（GUI 对话框）会阻塞前缀构建窗口，静默化后错误只进
                    // 日志（退出码非 0 照旧大声告警）。
                    .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(), "regedit", "/S", importFile.absolutePath)
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                val code = cmd.waitFor()
                if (code != 0) log("警告: regedit $name 退出码 $code（继续其余步骤）")
                else log("导入注册表: $name ✓")
            }.onFailure { log("警告: regedit $name 异常: ${it.message}") }
        }

        // ⑤ fix-fonts 归档 → windows/
        extractOptArchive(pd, "fix-fonts", windows, log)

        // ⑤b v3.2.28（startwine.sh 同款）：安装包自带运行库批处理
        //     （Physx 9.10 / 7-zip / VC++ 可再发行包 —— /opt/apps/install.bat，
        //     640x480 虚拟桌面静默跑，完后 wineserver -k 收尾落盘）。
        //     安装包不存在该文件时零动作；失败只记日志不阻断（与参考
        //     脚本容错语义一致）。
        val installBat = File(root, "apps/install.bat")
        if (installBat.isFile) {
            log("安装运行库（install.bat：Physx/7-zip/VC++，640x480 虚拟桌面）……")
            runCatching {
                val cmd = NativeShellExecutor.create()
                    .setCommand(*WineRuntime.wineCommand(resolved).toTypedArray(),
                                "explorer", "/desktop=shell,640x480",
                                "start", "/unix", "/opt/apps/install.bat")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                val finished = runCatching { cmd.waitFor(600, TimeUnit.SECONDS) }.getOrDefault(false)
                if (!finished) {
                    runCatching { cmd.stopAndWait(true, 10, TimeUnit.SECONDS) }
                    log("警告: install.bat 执行超时（600s），已终止（继续其余步骤）")
                } else {
                    log("install.bat 执行结束（退出码 ${runCatching { cmd.waitFor() }.getOrDefault(-1)}）")
                }
                // 参考脚本同款：运行库装完立即收尾 wineserver，注册表落盘
                val serverBin = WineRuntime.hostToolBin(resolved, "wineserver")
                NativeShellExecutor.create()
                    .setCommand(serverBin.absolutePath, "-k")
                    .putEnv(env)
                    .putEnv("WINEPREFIX", container.prefixPath)
                    .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                    .exec()
                    .waitFor(15, TimeUnit.SECONDS)
            }.onFailure { log("警告: install.bat 执行异常: ${it.message}（继续）") }
        }

        // ⑤c v3.2.28（参考脚本 wineserver -k 同款）：注册表/字体写完先收尾
        //     wineserver 落盘，再进 dxvk 解压 —— 顺序对齐 startwine.sh。
        runCatching {
            val serverBin = WineRuntime.hostToolBin(resolved, "wineserver")
            NativeShellExecutor.create()
                .setCommand(serverBin.absolutePath, "-k")
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                .exec()
                .waitFor(15, TimeUnit.SECONDS)
        }

        // ⑥ dxvk（virgl 启用时跳过 —— 参考脚本同款条件）
        if (!File(root, "virgl/virgl-enabled").exists()) {
            val dxvkDir = File(root, "dxvk1")
            val archive = dxvkDir.takeIf { it.isDirectory }
                ?.listFiles { f ->
                    f.isFile && (
                        f.name.endsWith(".tar.gz") || f.name.endsWith(".tgz") ||
                            f.name.endsWith(".tar.xz") || f.name.endsWith(".txz") ||
                            f.name.endsWith(".tar")
                        )
                }
                ?.maxByOrNull { it.name }
            if (archive != null) {
                log("安装 dxvk: ${archive.name}")
                extractOptArchive(dxvkDir, archive.name, windows, log, explicit = archive.name)
            }
        }

        // ⑦ wineserver 落盘注册表（参考脚本 wineserver -k 同款）
        runCatching {
            val serverBin = WineRuntime.hostToolBin(resolved, "wineserver")
            NativeShellExecutor.create()
                .setCommand(serverBin.absolutePath, "-w")
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                .exec()
                .waitFor(20, TimeUnit.SECONDS)
            NativeShellExecutor.create()
                .setCommand(serverBin.absolutePath, "-k")
                .putEnv(env)
                .putEnv("WINEPREFIX", container.prefixPath)
                .setWorkingDir(WineEnvironment.RUNTIME_DIR)
                .exec()
                .waitFor(10, TimeUnit.SECONDS)
        }

        runCatching { optPrefixMarker(prefix).createNewFile() }
        runCatching { File(prefix, ".update-timestamp").writeText("disable") }
        log("/opt/prefix 预置资产注入完成")
        return true
    }

    /**
     * opt/prefix 归档解压：按双后缀候选（.tar.xz/.txz/.tar）+ 魔数嗅探
     * 选解压器（gzip/xz/zstd/plain tar），经 TarCompressorUtils 落盘。
     */
    private fun extractOptArchive(
        dir: File,
        base: String,
        dest: File,
        log: (String) -> Unit,
        explicit: String? = null
    ) {
        val candidates = if (explicit != null) {
            listOf(File(dir, explicit))
        } else {
            listOf(".tar.xz", ".txz", ".tar").map { File(dir, "$base$it") }
        }
        val archive = candidates.firstOrNull { it.isFile } ?: return
        runCatching {
            val type = sniffTarType(archive)
                ?: run {
                    log("警告: ${archive.name} 魔数无法识别，跳过")
                    return
                }
            dest.mkdirs()
            val ok = com.termux.x11.controller.core.TarCompressorUtils.extract(type, archive, dest)
            if (ok) {
                log("解压 ${archive.name} → ${dest.absolutePath}")
            } else {
                log("警告: ${archive.name} 解压失败（下次重建容器时重试）")
            }
        }.onFailure { log("警告: ${archive.name} 解压异常: ${it.message}") }
    }

    /** 归档魔数嗅探（与 AnWindTzstAssets.sniffType 同源）。 */
    private fun sniffTarType(file: File): com.termux.x11.controller.core.TarCompressorUtils.Type? {
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(6)
                val n = raf.read(head)
                val b = head.toList()
                when {
                    n >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte() ->
                        com.termux.x11.controller.core.TarCompressorUtils.Type.GZIP
                    n >= 6 && b[0] == 0xfd.toByte() && b[1] == 0x37.toByte() &&
                        b[2] == 0x7a.toByte() && b[3] == 0x58.toByte() &&
                        b[4] == 0x5a.toByte() && b[5] == 0x00.toByte() ->
                        com.termux.x11.controller.core.TarCompressorUtils.Type.XZ
                    n >= 4 && b[0] == 0x28.toByte() && b[1] == 0xb5.toByte() &&
                        b[2] == 0x2f.toByte() && b[3] == 0xfd.toByte() ->
                        com.termux.x11.controller.core.TarCompressorUtils.Type.ZSTD
                    else -> {
                        // 纯 tar：ustar 魔数在 257 偏移
                        raf.seek(257)
                        val magic = ByteArray(5)
                        val m = raf.read(magic)
                        if (m == 5 && String(magic) == "ustar") {
                            com.termux.x11.controller.core.TarCompressorUtils.Type.PLAIN
                        } else null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
