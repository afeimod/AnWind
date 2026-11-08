package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.Box64Preset
import com.anwind.apps.containers.ContainerLangs
import com.anwind.apps.containers.ContainerData
import com.anwind.apps.containers.ContainerManager
import java.io.File

/**
 * rootfs 环境白名单构建器 —— 原生启动的"隔离"核心。
 *
 * 完整移植 anwind-wine 脚本的 isolate_env / apply_gpu_env / apply_box64_env /
 * apply_extra_env 四段，但实现方式彻底改变：**不再把 Termux 环境传进 pty
 * 再让 bash 脚本逐个 unset/export 清洗**，而是由 App 端一次性构建出
 * rootfs 视角的完整白名单，经 [NativeShellExecutor] 的 ProcessBuilder
 * environment() 直接写入子进程。wine 看到的环境与终端会话完全无关，
 * 串扰问题（LD_PRELOAD 劫持、Termux lib 污染 Mesa 符号解析、残留
 * DISPLAY 连错 X 导致黑屏）从结构上根除。
 *
 * 变量清单与脚本 v2.3 基线逐字对齐（见各函数内注释）。
 */
object WineEnvironment {

    private val RFS_ROOT = ContainerManager.ROOTFS_ROOT
    private val RFS_USR = "$RFS_ROOT/usr"
    private val APP_PREFIX = ContainerManager.APP_PREFIX

    /** 容器运行的家目录（rootfs home，与脚本一致）。 */
    val HOME_DIR = File("$RFS_ROOT/home")

    /** XDG_RUNTIME_DIR / TMPDIR（rootfs 自管 tmp）。 */
    val RUNTIME_DIR = File("$RFS_USR/tmp")

    // ------------------------------------------------------------------
    // 1) 基础环境（isolate_env 移植）
    // ------------------------------------------------------------------

    /**
     * 构建 rootfs 基础环境（不含 WINEPREFIX / 音频 / GPU / box64 ——
     * 那些由 [RootfsLauncher] 在链路对应步骤叠加）。
     */
    fun buildBaseEnv(container: ContainerData, wineDir: File): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        // --- 前缀 / 临时目录 / 家目录 → rootfs 自己的一套（isolate_env §2） ---
        env["PREFIX"] = RFS_USR
        env["TMPDIR"] = RUNTIME_DIR.absolutePath
        env["HOME"] = HOME_DIR.absolutePath
        env["XDG_RUNTIME_DIR"] = RUNTIME_DIR.absolutePath
        env["XDG_CONFIG_HOME"] = "$RFS_ROOT/home/.config"
        env["XDG_CACHE_HOME"] = "$RFS_ROOT/home/.cache"
        env["XDG_DATA_HOME"] = "$RFS_ROOT/home/.local/share"

        // --- 语言（§3）：容器显式语言 > auto 档读 /opt/locale.conf > zh_CN.UTF-8
        //     （v3.3.4：默认档 = 简体中文；跟随 /opt/locale.conf 仅在显式
        //      选择 auto 档时生效，作为选项保留）
        //     （v3.3.2 恢复 container.lang —— v3.2.29 曾移除语言选择项，
        //     用户反馈"语言设置你给去掉了？那个更改后可以更改启动配置
        //     语言"）。startwine.sh 参考链路 `export LC_ALL="$(cat
        //     /opt/locale.conf)"`：auto 档同语义取 rootfs/opt/locale.conf
        //     首行；显式档（简体中文/繁體/English/日本語/한국어）LANG 与
        //     LC_ALL 同用容器值。注意：bionic 无 locale 数据，libc 的
        //     setlocale 对 zh_CN.UTF-8 失效 —— wine 界面语言由 ntdll 的
        //     locale 解析决定，0009-android-locale-lc-all 补丁（用户
        //     wine 10.5 参考树同款）让 __ANDROID__ 下直接读 LC_ALL，
        //     本变量的值从此真正决定 wine 的 LCID/界面语言/ACP。
        //     conf/*.conf 若显式 export LANG/LC_ALL，由 applyOptConfEnv
        //     通用逻辑再覆盖；容器 env= 仍最高。
        // v3.3.4（用户问题 4："默认不要跟随 opt/locale，默认简体中文即可，
        // 跟随 opt/locale 作为一个选项"）：三档判定 ——
        //   显式语言档（简体中文/English/…）→ 直接用它；
        //   auto（显式选择"跟随 /opt/locale.conf"）→ 读生态包 locale.conf，
        //     无则兜底 zh_CN.UTF-8；
        //   其余（缺 lang= 键的旧容器 / 非法值）→ 一律 zh_CN.UTF-8，
        //     不再隐式跟随 /opt/locale.conf。
        val lang = when {
            container.lang == ContainerLangs.AUTO -> readOptLocale(wineDir) ?: "zh_CN.UTF-8"
            container.lang in ContainerLangs.CHOICES -> container.lang
            else -> "zh_CN.UTF-8"
        }
        env["LANG"] = lang
        env["LC_ALL"] = lang

        // --- 动态库搜索路径 → 仅 rootfs（§4；不追加继承值，防外部 lib
        //     污染 Mesa/ARM 驱动符号解析） ---
        val lp = buildList {
            add("$RFS_USR/lib")
            if (File("$RFS_USR/local/lib").isDirectory) add("$RFS_USR/local/lib")
            if (File(wineDir, "lib").isDirectory) add("${wineDir.absolutePath}/lib")
            if (File("$RFS_USR/opt/lib").isDirectory) add("$RFS_USR/opt/lib")
            if (File("$RFS_USR/opt/fex/lib").isDirectory) add("$RFS_USR/opt/fex/lib")
        }
        env["LD_LIBRARY_PATH"] = lp.joinToString(":")

        // --- PATH → rootfs 优先，App 前缀仅兜底（§5；curl/zstd 等下载工具） ---
        env["PATH"] =
            "$RFS_USR/bin:$RFS_USR/local/bin:/system/bin:/system/xbin:$APP_PREFIX/bin"

        // --- X11：立即强制 :1（§6；不沿用继承值——残留空/错 DISPLAY 会让
        //     wine 连错地方 → 黑屏。ANWIND_DISPLAY 保留为覆盖口） ---
        val override = System.getenv("ANWIND_DISPLAY")
        env["DISPLAY"] = if (!override.isNullOrBlank()) override else ":1"

        // --- XKB / Mesa 基础（§7；rootfs 自带的一套） ---
        env["XKB_CONFIG_ROOT"] = "$RFS_USR/share/X11/xkb"
        env["MESA_SHADER_CACHE_DISABLE"] = "false"
        env["MESA_SHADER_CACHE_MAX_SIZE"] = "512MB"
        env["mesa_glthread"] = "true"
        // v3.2.13（用户问题 4：所有 wine 游戏画面移动一抖一抖）：撤销
        // aarch64 无条件注入的 TU_DEBUG=noconform,sysmem。
        //   * sysmem 强制 Turnip 把 buffer 对象落在系统内存（UW 内存路径
        //     绕过）—— GPU 访存带宽骤降，游戏滚屏/镜头移动持续掉帧、
        //     顿挫感明显，是"全游戏都卡"的系统性放大器；
        //   * noconform（关闭 Vulkan 一致性绕行）历史上用于规避旧 Turnip
        //     在 AHB 路径的崩溃 —— 仅在使用 Turnip（zink/驱动内 Vulkan）
        //     时才有意义，经典 freedreno GL 路径完全不读它。
        // 现改为：基础环境不再注入任何 TU_*；zink 档（applyGpuEnv，Turnip
        // 真正参与时）只补 noconform（稳定性兜底），sysmem 一律不设。
        // 用户 env= 仍可整体覆盖。

        // --- GStreamer（§8；插件路径显式指向 rootfs，注册表缓存 fork 在
        //     Android 上偶发卡死，直接关掉） ---
        if (File("$RFS_USR/lib/gstreamer-1.0").isDirectory) {
            env["GST_PLUGIN_SYSTEM_PATH"] = "$RFS_USR/lib/gstreamer-1.0"
            env["GST_REGISTRY_FORK"] = "false"
        }
        return env
    }

    // ------------------------------------------------------------------
    // 2) GPU / Mesa（apply_gpu_env 移植）
    // ------------------------------------------------------------------

    /** 依容器 gallium 配置注入 Mesa 渲染器变量。 */
    fun applyGpuEnv(env: MutableMap<String, String>, container: ContainerData) {
        // v3.1.6：两处修正 ——
        //   1. "llvmpipe" 一律归一为 "softpipe"（rootfs 的 aarch64/x86_64
        //      mesa 交叉构建均未包含 llvmpipe——不带 LLVM；GALLIUM_DRIVER
        //      指到未构建的驱动名 = GL 初始化直接失败，等宽于"无 GL"，
        //      wined3d 枚举不到任何适配器 → "无兼容 Direct3D 设备"）；
        //   2. auto 档不再盲选 freedreno：freedreno（GLES/DRM 渲染节点）
        //      依赖 /dev/dri 可访问，Android 应用沙箱在相当多设备/ROM 上
        //      无权打开 → GL 整体初始化失败。auto 现在先探 /dev/dri
        //      渲染节点可达性，不可达回退 softpipe + LIBGL_ALWAYS_SOFTWARE
        //      （D3D 样例/茶壶类程序从此必出画面，慢但活）。
        // v3.2.13（用户问题 4，画面抖动专项）：
        //   * zink 档 TU_DEBUG 只保留 noconform（崩溃规避），不再带 sysmem
        //     （系统内存强制 —— GPU 带宽腰斩，滚屏/移动持续掉帧的直接
        //     元凶，见 buildBaseEnv §7 注释）；
        //   * softpipe 兜底档补 vblank_mode=0：软渲染帧率本就低于屏幕
        //     刷新率，GLX 交换再等 vblank 只会引入 0~1 帧的随机延迟，
        //     表现为"移动一抖一抖"；关闭后按最快节奏上屏，节奏均匀。
        val raw = if (container.gallium.equals("auto", true) || container.gallium.isBlank())
            "auto" else container.gallium.trim().lowercase()
        val gallium = when (raw) {
            "llvmpipe" -> "softpipe"
            "auto" -> if (isAarch64() && hasDrmAccess()) "freedreno" else "softpipe"
            else -> raw
        }
        when (gallium) {
            "zink" -> {
                env["GALLIUM_DRIVER"] = "zink"
                env["ZINK_DESCRIPTORS"] = "lazy"
                env["ZINK_DEBUG"] = "compact"
                // Turnip 崩溃规避仅此一项（sysmem 已撤销，见上）
                env.putIfAbsent("TU_DEBUG", "noconform")
            }
            "freedreno" -> env["GALLIUM_DRIVER"] = "freedreno"
            else -> {
                // softpipe（含旧 llvmpipe 归一值）—— 软件渲染节奏配套
                env["GALLIUM_DRIVER"] = "softpipe"
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
                env.putIfAbsent("vblank_mode", "0")
            }
        }
    }

    /** v3.1.6：/dev/dri 渲染节点可达性探测（freedreno/zink 的 auto 档依据）。 */
    private fun hasDrmAccess(): Boolean = try {
        File("/dev/dri/renderD128").canRead() ||
            File("/dev/dri/card0").canRead() ||
            File("/dev/kgsl-3d0").canRead()
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------
    // 3) Box64（apply_box64_env 移植 + v3.1.4 自包含依赖库路径）
    // ------------------------------------------------------------------

    /**
     * box64 / fexcore 后端的 DYNAREC 与运行库变量。
     *
     * v3.1.4：BOX64_LD_LIBRARY_PATH 前插 wine 发行包自带的 x86_64-bionic
     * 依赖库目录（`<wineDir>/lib/x86_64-bionic`，CI 打包步骤 8.5a 注入）——
     * aarch64 rootfs 只有 aarch64 库，x86_64 形态 wine 的 unix .so（及其
     * DT_NEEDED：libpulse/libasound/libX11/libandroid-shmem/gstreamer…）
     * 由 box64 仿真加载，库文件只能从这个私有目录找，缺它 wine 直接
     * "Symbol ... not found" 起不来。gstreamer 插件路径同步指向包内
     * x86_64 版（rootfs 里的插件是 aarch64，x86_64 libgstreamer 加载不了）。
     */
    fun applyBox64Env(env: MutableMap<String, String>, container: ContainerData, wineDir: File) {
        // v3.2.24（用户问题 3，对齐 startwine.sh / startfex.sh）：
        //   export BOX64_PATH=$WINE_PATH/bin
        //   export BOX64_LD_LIBRARY_PATH=$WINEPATH/lib/wine/i386-unix:$WINEPATH/lib/wine/x86_64-unix
        // （BOX64_LOG 的 v3.2.13 说明：由 0 改 1，error 级输出保留可诊断性）
        env["BOX64_LOG"] = "1"
        env["BOX64_NOBANNER"] = "1"
        env["BOX64_DYNAREC_TRACE"] = "0"
        // v3.2.13：BOX64_ENVFILE —— 让 rootfs 的 /usr/etc/box64.box64rc
        // 真正生效。上游 box64（v0.4.4 env.c InitializeEnvFiles）只读
        // /etc/box64.box64rc 或 /data/data/com.termux/... 两个内置路径，
        // rootfs 内 etc/box64.box64rc（com.anwind 路径）天然读不到 =
        // 系统级调优开关一直是死文件。BOX64_ENVFILE 是上游官方入口
        // （InitializeEnvFiles 首优先级），存在即注入；用户 env= 可覆盖。
        val box64rc = File("$RFS_USR/etc/box64.box64rc")
        if (box64rc.isFile && !container.env.contains("BOX64_ENVFILE=")) {
            env["BOX64_ENVFILE"] = box64rc.absolutePath
        }
        // v3.2.24（用户问题 3）：BOX64_PATH —— wine 槽位自身的 bin 目录。
        // 未注入前：wine（box64 仿真）内 exec 子进程（wineserver/wineboot/
        // 安装器 exe 再拉起的 x86_64 子进程）时，box64 只能在 PATH 里找
        // x86_64 目标；而 $WINE_DIR/bin 不在 PATH —— 参考脚本
        // startwine.sh/startfex.sh 的同款导出，这是参考链路能跑、
        // AnWind 链路部分场景子进程拉不起的差异点之一。
        // 注入后：box64 对仿真进程的 exec 直接在 wine 自己的 bin 里找，
        // 与 MiceWine/Winlator 的 BOX64_PATH 用法一致。仅目录存在时写入。
        val wineBinDir = File(wineDir, "bin")
        if (wineBinDir.isDirectory) {
            env["BOX64_PATH"] = wineBinDir.absolutePath
        }
        // v3.2.24（用户问题 3）：BOX64_LD_LIBRARY_PATH 前插 wine 槽位
        // 自身的 unix 库目录（lib/wine/i386-unix + x86_64-unix，存在才加）。
        // startwine.sh：export BOX64_LD_LIBRARY_PATH=…/i386-unix:…/x86_64-unix ——
        // wine 的 unix 侧 .so（ntdll.so 背后的 unix 库、各 *.so 驱动）由
        // box64 仿真加载时，优先在 wine 自己的 lib/wine/<arch>-unix 里
        // 解析；旧链路只给了 x86_64-bionic 闭包目录，wine 自身 unix 库
        // 目录缺位（对齐参考脚本的第二差异点）。顺序：wine unix 目录 →
        // x86_64-bionic 闭包（下方）→ glibc 目录（下方）。
        val wineUnixLibDirs = listOf(
            File(wineDir, "lib/wine/i386-unix"),
            File(wineDir, "lib/wine/x86_64-unix")
        ).filter { it.isDirectory }
        if (wineUnixLibDirs.isNotEmpty()) {
            val existing0 = env["BOX64_LD_LIBRARY_PATH"]
            val prefix0 = wineUnixLibDirs.joinToString(":") { it.absolutePath }
            env["BOX64_LD_LIBRARY_PATH"] = if (existing0.isNullOrBlank()) prefix0
            else "$prefix0:$existing0"
        }
        // wine 包自带的 x86_64-bionic 依赖闭包（存在才追加；旧版包无此目录）
        val bionicLibDir = File(wineDir, "lib/x86_64-bionic")
        val bionicGstDir = File(bionicLibDir, "gstreamer-1.0")
        if (bionicLibDir.isDirectory) {
            env["BOX64_LD_LIBRARY_PATH"] = bionicLibDir.absolutePath +
                (env["BOX64_LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
            if (bionicGstDir.isDirectory) {
                // x86_64 gstreamer 只认 x86_64 插件；rootfs 的 aarch64 插件目录对它无效
                env["GST_PLUGIN_SYSTEM_PATH"] = bionicGstDir.absolutePath
            }
        }
        // x86_64 glibc 运行库（bionic-rootfs 的 glibc-x86_64 配方安装）：
        // 本 wine 是 bionic 链接不需要它；但容器内跑常规 glibc x86_64
        // Linux 程序时 box64 要在这些目录找 libc/ld-linux，缺它直接报
        // "cannot open shared object file"。仅目录存在时追加。
        val glibcDir = File("$RFS_USR/lib/x86_64-linux-gnu")
        if (glibcDir.isDirectory) {
            val existing = env["BOX64_LD_LIBRARY_PATH"]
            env["BOX64_LD_LIBRARY_PATH"] = if (existing.isNullOrBlank()) {
                glibcDir.absolutePath
            } else {
                "${glibcDir.absolutePath}:$existing"
            }
        }
        // v3.3.16（用户要求对齐 WinNative 性能调用）：四档 DYNAREC 预设
        // —— 变量值逐字对齐 WinNative Box64PresetManager.getEnvVars 的
        // STABILITY / COMPATIBILITY / INTERMEDIATE / PERFORMANCE。
        // 注：WinNative PERFORMANCE 的 SAFEFLAGS=1（非 0）与本仓 v3.2.17
        // 的 wineboot SIGSEGV 教训（SAFEFLAGS=0 导致 flags 错乱）一致保留 1。
        when (container.preset) {
            Box64Preset.STABILITY -> {
                env["BOX64_DYNAREC_SAFEFLAGS"] = "2"
                env["BOX64_DYNAREC_FASTNAN"] = "0"
                env["BOX64_DYNAREC_FASTROUND"] = "0"
                env["BOX64_DYNAREC_X87DOUBLE"] = "1"
                env["BOX64_DYNAREC_BIGBLOCK"] = "0"
                env["BOX64_DYNAREC_STRONGMEM"] = "2"
                env["BOX64_DYNAREC_FORWARD"] = "128"
                env["BOX64_DYNAREC_CALLRET"] = "0"
                env["BOX64_DYNAREC_WAIT"] = "0"
            }
            Box64Preset.COMPATIBILITY -> {
                env["BOX64_DYNAREC_BIGBLOCK"] = "0"
                env["BOX64_DYNAREC_SAFEFLAGS"] = "2"
                env["BOX64_DYNAREC_FASTNAN"] = "0"
                env["BOX64_DYNAREC_FASTROUND"] = "0"
                env["BOX64_DYNAREC_X87DOUBLE"] = "1"
                env["BOX64_DYNAREC_STRONGMEM"] = "1"
                env["BOX64_DYNAREC_FORWARD"] = "128"
                env["BOX64_DYNAREC_CALLRET"] = "0"
                env["BOX64_DYNAREC_WAIT"] = "1"
            }
            Box64Preset.INTERMEDIATE -> {
                env["BOX64_DYNAREC_BIGBLOCK"] = "2"
                env["BOX64_DYNAREC_SAFEFLAGS"] = "2"
                env["BOX64_DYNAREC_FASTNAN"] = "1"
                env["BOX64_DYNAREC_FASTROUND"] = "0"
                env["BOX64_DYNAREC_X87DOUBLE"] = "1"
                env["BOX64_DYNAREC_STRONGMEM"] = "0"
                env["BOX64_DYNAREC_FORWARD"] = "256"
                env["BOX64_DYNAREC_CALLRET"] = "1"
                env["BOX64_DYNAREC_WAIT"] = "1"
            }
            Box64Preset.PERFORMANCE -> {
                env["BOX64_DYNAREC_BIGBLOCK"] = "3"
                // v3.2.17（设备实报：wine9.2 x86_64 形态 wineboot -i 于 ntdll
                // 早期初始化 SIGSEGV，退出码 139）：SAFEFLAGS 由 0 改 1。
                // 上游 box64 USAGE.md（SAFEFLAGS 条目）：0 = "Treat CALL/RET
                // as if it never needs any flags" —— wine 加载器与 ntdll 初始化
                // 大量依赖 CALL/RET 之后的条件分支，flags 不保存 = jcc 拿到
                // 陈旧标志走错分支 → 寄存器装入未重定位的裸值 → 访问 0x928e8
                // 类未映射小地址段错误（崩溃现场实证：RAX=0x3f000928d8 带加载
                // 基址的正确地址 与 R15=0x928d8 裸 vaddr 低 32 位完全一致 =
                // 错误分支后 64 位寄存器高位丢失，典型 flags 错乱后遗症）。
                // 1 是上游默认值，也是 Winlator/WinNative wine 组合的实证底线；
                // wineboot 前缀构建会话另由 RootfsLauncher 叠加最稳的 2。
                env["BOX64_DYNAREC_SAFEFLAGS"] = "1"
                env["BOX64_DYNAREC_FASTNAN"] = "1"
                env["BOX64_DYNAREC_FASTROUND"] = "1"
                env["BOX64_DYNAREC_X87DOUBLE"] = "0"
                env["BOX64_DYNAREC_STRONGMEM"] = "0"
                env["BOX64_DYNAREC_FORWARD"] = "512"
                env["BOX64_DYNAREC_CALLRET"] = "1"
                env["BOX64_DYNAREC_WAIT"] = "1"
            }
        }
    }

    // ------------------------------------------------------------------
    // 3b) Android 安全默认 + arm64ec 仿真器 DLL（v3.1.4）
    // ------------------------------------------------------------------

    /**
     * Android 沙箱兼容默认（v3.1.4）+ arm64ec 仿真器 DLL 选择。
     *
     * ① WINEESYNC/WINEFSYNC 默认 0：
     *    - fsync 的 futex_waitv 能力探测在 Android 上被 seccomp 以
     *      SIGSYS 击杀（构建侧已加 __ANDROID__ 守卫根治；此处环境默认
     *      再兜一层——旧版 wine 包没打守卫补丁时也能活）；
     *    - esync 依赖 /dev/shm 与 eventfd，Android 沙箱行为不可预期；
     *    - 两者对游戏兼容性收益为零（Winlator/MiceWine 同款默认）。
     *    用户可在容器 env= 里显式覆盖（applyExtraEnv 在链路最后叠加）。
     *
     * ①b WINELOADERNOEXEC=1（v3.2.3，用户问题「proton 的 wine 都无法
     *    构建前缀」的运行期缓解；proton_10.0 loader.c:2690 源码核实）：
     *    - wine ≤10 树的 `__wine_main` 首遍逻辑是
     *      `if (!getenv("WINELOADERNOEXEC")) { check_command_line();
     *      if (pre_exec()) putenv("WINELOADERNOEXEC=1") → 经
     *      wine-preloader 重新执行一遍自身 }` —— 置 1 = 首进程直接跳过
     *      重执行（地址空间保留由 virtual_init 的无预加载器分支自完）。
     *    - 代价：该变量与 check_command_line（--help/--version 分发）
     *      同一守卫块 —— [WineRegistry.loaderSmokeTest] 已在测试前剥离，
     *      不受影响。
     *    - 子进程不经该变量（preloader_exec 不检查它）—— 子进程的
     *      预加载器风险由 [WineLayoutRepair] 停用 bin 目录下的
     *      `*-preloader` 文件根治（ENOENT 官方兜底直连 loader）；
     *      本变量让首进程先不走弯路。
     *
     * ①c（v3.2.8 二次勘误，恢复 v3.2.3 误删的变量）：重新导出
     *    WINE_DISABLE_KERNEL_WRITEWATCH=1。v3.2.3 的"勘误"声称全 wine
     *    源码均无此变量而将其移除 —— 该结论【只对 hangover-11.16 成立】
     *    （wine 11 删除了该变量、改为编译期 `!defined(__ANDROID__)` 排除）；
     *    proton_10.0 / wine-10.0 树的 virtual.c:3847 实测存在：
     *      if (!((env_var = getenv( "WINE_DISABLE_KERNEL_WRITEWATCH" )) && atoi( env_var )))
     *          kernel_writewatch_init();
     *    而 kernel_writewatch_init() 内的裸 syscall(__NR_userfaultfd)
     *    （aarch64 nr 374）不在 Android 应用 seccomp 白名单 → SIGSYS(31)
     *    → 退出码 159 —— 正是 proton arm64ec "打印一行 heap-tagging 后
     *    秒退 159"的真正根因（fsync 探测守卫早已生效，159 依旧）。
     *    本变量让 wine 10 系旧包【免重装免重编】跳过该探测；wine 11 树
     *    不读它，零影响。用户 env= 可覆盖（putIfAbsent 语义）。
     *
     * ② HODLL64/HODLL（仅 arm64ec 形态）：arm64ec 跑 x86_64/i386 程序
     *    靠仿真器 DLL —— 上游 official/proton 树默认是 xtajit64 桨（只报
     *    不支持），hangover 树默认 wowbox64（需 box64 PE）。wine 包自带
     *    libarm64ecfex + libwow64fex（FEX）：
     *    - HODLL64=libarm64ecfex.dll —— arm64ec 运行必需（x64 仿真器
     *      DLL），包内存在即注入；用户 HODLL64 覆盖优先。
     *    - HODLL=libwow64fex.dll —— **FEX 模式开关**（v3.2.29 用户指令，
     *      startfex.sh / wineguanli.py 勾选菜单同款）：仅当容器勾选
     *      「FEX模式」时注入 —— arm64ec wine 既可直接跑（x86_64/i386
     *      走 wine 自带 wow64），也可勾选 FEX 让它们经 FEX 仿真 DLL
     *      运行（`HODLL=libwow64fex.dll wine …`）。旧版「文件存在即
     *      无条件注入」使勾选项形同虚设，已废；用户 env= 显式 HODLL=
     *      仍最高优先。
     *    【v3.2.10】official/proton 树已由 0010/0011 补丁移植了 HODLL/
     *    HODLL64 读取机制（对齐 hangover-11.16）：此前 vanilla 树根本不
     *    读这两个变量，xtajit(.64).dll 在 Linux 不存在 → 32 位 wow64 进程
     *    死于 c0000135（syswow64 无法生成）、EC 进程装载 x64 仿真器失败。
     *    本函数注入的变量【必须】在 wineboot 前缀构建会话保留（Wow64Install
     *    段靠 32 位 rundll32 执行，见 RootfsLauncher v3.2.10 注）。
     */
    fun applyWineCompatEnv(
        env: MutableMap<String, String>,
        arch: WineRuntime.Arch,
        wineDir: File,
        userEnv: String = "",
        fexMode: Boolean = false
    ) {
        env.putIfAbsent("WINEESYNC", "0")
        env.putIfAbsent("WINEFSYNC", "0")
        // v3.2.3：预加载器首进程规避（详见 KDoc ①b；子进程风险由
        // WineLayoutRepair 停用 bin/*-preloader + WineProbePatch 改写
        // futex_waitv 探测兕底，两者均免重编作用于旧包）
        env.putIfAbsent("WINELOADERNOEXEC", "1")
        // v3.2.8：userfaultfd 写监视探测的 Android seccomp SIGSYS(159)
        // 根治（详见 KDoc ①c）—— wine 10 系树（proton_10.0/wine-10.0）
        // 在 virtual_init 读取本变量，置 1 即跳过 kernel_writewatch_init
        // 的裸 syscall(__NR_userfaultfd)。wine 11 树不读此变量（编译期已
        // 排除），导出无副作用。
        env.putIfAbsent("WINE_DISABLE_KERNEL_WRITEWATCH", "1")
        if (arch != WineRuntime.Arch.AARCH64) return
        val peDir = File(wineDir, "lib/wine/aarch64-windows")
        val hasUserHodll64 = userEnv.contains("HODLL64=")
        val hasUserHodll = userEnv.contains("HODLL=")
        if (!hasUserHodll64 && File(peDir, "libarm64ecfex.dll").isFile) {
            env["HODLL64"] = "libarm64ecfex.dll"
        }
        // HODLL=libwow64fex.dll 仅在容器勾选「FEX模式」时注入
        //（startfex.sh 同款；未勾选 = arm64ec wine 直接跑）
        if (!hasUserHodll && fexMode && File(peDir, "libwow64fex.dll").isFile) {
            env["HODLL"] = "libwow64fex.dll"
        }
    }

    // ------------------------------------------------------------------
    // 4) 音频 / 用户附加环境（audio_setup / apply_extra_env 的注入端）
    // ------------------------------------------------------------------

    /**
     * v3.2.15（wineguanli.py 重制）：容器调优环境注入。
     *
     *  - frame != "0" → DXVK_FRAME_RATE（wineguanli.py 帧数限制同款变量）
     *  - flags 含 unity → BOX64_UNITYPLAYER=1（box64 官方 Unity 兼容开关，
     *    对应 wineguanli.py「Unity 单线程渲染」勾选）
     *  - mangohud 非空 → MANGOHUD=1 + MANGOHUD_CONFIG（rootfs 内置 MangoHud
     *    时生效；对应 wineguanli.py 的 MangoHud 配置）
     *  - cores 非空 → PRIMARY_CORES / SECONDARY_CORES（wineguanli.py 的
     *    /opt/conf/cores.conf 同款语义；AnWind 原生链不做亲和性绑定，
     *    变量供用户自带的 /opt 生态脚本与 box64 配置消费）
     *
     * 放在 applyExtraEnv 之前 —— 用户 env= 仍可覆盖一切。
     */
    fun applyTuningEnv(env: MutableMap<String, String>, container: ContainerData) {
        val frame = container.frame.trim()
        if (frame.isNotEmpty() && frame != "0") {
            env["DXVK_FRAME_RATE"] = frame
        }
        if (container.hasFlag(com.anwind.apps.containers.ContainerFlags.UNITY)) {
            env["BOX64_UNITYPLAYER"] = "1"
        }
        val mh = container.mangohud.trim()
        if (mh.isNotEmpty()) {
            env["MANGOHUD"] = "1"
            env["MANGOHUD_CONFIG"] = mh
        }
        val cores = container.cores.trim()
        if (cores.isNotEmpty()) {
            env["PRIMARY_CORES"] = cores
            val total = Runtime.getRuntime().availableProcessors()
            val primary = parseCoreSpec(cores).filter { it in 0 until total }.toSortedSet()
            val secondary = (0 until total).filter { it !in primary }
            env["SECONDARY_CORES"] = formatCoreSpec(secondary)
        }
    }

    /** "0-3" / "0,1,2" → [0,1,2,3]（wineguanli.py parse_core_string 移植）。 */
    internal fun parseCoreSpec(spec: String): List<Int> {
        val out = mutableListOf<Int>()
        spec.split(',', ';').forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach
            if (p.contains('-')) {
                val range = p.split('-', limit = 2)
                val s = range.getOrNull(0)?.trim()?.toIntOrNull()
                val e = range.getOrNull(1)?.trim()?.toIntOrNull()
                if (s != null && e != null && s <= e) {
                    out.addAll(s..e)
                }
            } else {
                p.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    /** [0,1,2,3] → "0-3"（wineguanli.py format_cores 移植：连续段折叠）。 */
    internal fun formatCoreSpec(cores: List<Int>): String {
        if (cores.isEmpty()) return ""
        val sorted = cores.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) {
                end = sorted[i]
            } else {
                parts.add(if (start != end) "$start-$end" else "$start")
                // v3.2.16 修复：Kotlin 赋值不是表达式，链式赋值 start = end = x 非法，拆开
                end = sorted[i]
                start = sorted[i]
            }
        }
        parts.add(if (start != end) "$start-$end" else "$start")
        return parts.joinToString(",")
    }

    /** 音频关闭（audio=off）：清空 PULSE_SERVER，wine 走无声。 */
    fun applyAudioOff(env: MutableMap<String, String>) {
        env["PULSE_SERVER"] = ""
    }

    // ------------------------------------------------------------------
    // 5) /opt 生态配置（v3.2.25 引入，v3.2.26 修正路径探测 + 去掉 wine_path.conf）
    // ------------------------------------------------------------------

    /**
     * /opt 生态根目录探测（v3.2.26 修正：wine 槽位目录也纳入候选）。
     *
     * 候选顺序（用户指定主路径优先）：
     *  1. rootfs/opt —— 用户明确的路径（/data/data/com.anwind/files/rootfs/opt）
     *  2. wine 槽位目录（resolved.wineDir，如 rootfs/usr/opt/wine-proton10-x86）
     *     —— WinFusion 风格包安装时把 conf/prefix/locale.conf 一并打进
     *     wine 目录树的场景（App 的 wine 版本管理以 --strip-components=1
     *     解包，包内与 bin/ 平级的 conf/、prefix/ 就落在这里）
     *  3. rootfs/opt/opt —— v3.2.28：生态包根是 opt/ 时用户解到了
     *     rootfs/opt 下，多套一层（原始解包动作不改用户文件，按套层读）
     *  4. 槽位内 opt/ 子目录 —— v3.2.28：包根是 opt/ 的生态包经
     *     --strip-components=1 装进槽位后，conf/prefix/locale.conf 落在
     *     槽位 opt/ 子目录（与 bin/ 平级的是 wine9.2-x64/ 这类 wine 树）
     *  5. rootfs/usr/opt —— 旧版布局兑底
     *
     * 判定条件：conf 目录 / locale.conf / prefix 目录 / dxvk1 目录任一
     * 存在。无则 null（全部 /opt 功能自动旁路）。
     *
     * @param wineDir 当前 wine 槽位目录（无解析结果时可传 null）
     */
    fun optRoot(wineDir: File? = null): File? =
        listOfNotNull(
            File("$RFS_ROOT/opt"),
            wineDir,
            wineDir?.let { File(it, "opt") },
            File("$RFS_ROOT/opt/opt"),
            File("$RFS_USR/opt")
        ).firstOrNull { optHasMarkers(it) }

    /** /opt 生态标记物（任一存在即认定该候选是生态根）。 */
    private fun optHasMarkers(root: File): Boolean =
        File(root, "conf").isDirectory ||
            File(root, "locale.conf").isFile ||
            File(root, "prefix").isDirectory ||
            File(root, "dxvk1").isDirectory

    /**
     * /opt 生态探测结果描述（会话日志可见，回答“路径对不对”一目了然）。
     * 返回形如 "rootfs/opt ✓（conf 5 项, prefix ✓, locale.conf ✓）" 的
     * 逐候选明细，供启动链写进会话日志。
     *
     * v3.2.28：候选目录**存在但没有标记物**时，列出目录内实际内容
     * （≤8 项，目录带 / 后缀）—— 用户把生态包整包/压缩包放错层时，
     * 日志直接显示“里面到底有什么”，不再只有一个无解释的 ✗。
     */
    fun optProbeReport(wineDir: File? = null): String {
        val candidates = listOfNotNull(
            "rootfs/opt" to File("$RFS_ROOT/opt"),
            wineDir?.let { "wine槽位 ${it.name}" to it },
            "rootfs/opt/opt" to File("$RFS_ROOT/opt/opt"),
            wineDir?.let { "wine槽位/${it.name}/opt" to File(it, "opt") },
            "rootfs/usr/opt" to File("$RFS_USR/opt")
        )
        return candidates.joinToString("；") { (label, root) ->
            if (optHasMarkers(root)) {
                val bits = mutableListOf<String>()
                File(root, "conf").takeIf { it.isDirectory }?.let {
                    bits.add("conf ${it.listFiles { f -> f.name.endsWith(".conf") }?.size ?: 0} 项")
                }
                File(root, "prefix").takeIf { it.isDirectory }?.let { bits.add("prefix ✓") }
                File(root, "locale.conf").takeIf { it.isFile }?.let { bits.add("locale.conf ✓") }
                File(root, "dxvk1").takeIf { it.isDirectory }?.let { bits.add("dxvk1 ✓") }
                "$label ✓（${bits.joinToString(", ")}）"
            } else if (root.isDirectory) {
                val listing = root.listFiles()
                    ?.sortedBy { it.name }
                    ?.take(8)
                    ?.joinToString(" ") { f -> if (f.isDirectory) "${f.name}/" else f.name }
                    .orEmpty()
                if (listing.isEmpty()) "$label ✗（目录存在但为空）"
                else "$label ✗（无 conf/locale.conf/prefix/dxvk1 —— 实际内容: $listing）"
            } else "$label ✗（目录不存在）"
        }
    }

    /**
     * 解析 opt/conf 内全部 .conf 的 KEY=VALUE（与 anwind-wine 脚本
     * load_opt_confs 同源语义）：
     *  - 固定顺序（startfex.sh 同款，v3.2.26 按用户要求**剔除 wine_path.conf**
     *    —— wine 目录由 AnWind 槽位体系管理，不读包内声明）：env / cores /
     *    path / dynarec_fixmpeg / dynarec_active；
     *  - 其余 *.conf（dynarec_* 等）按名兜底，但**排除两类非环境文件**：
     *    wine_path.conf（槽位自管）与 custom.conf（v3.2.29 —— 它是 DY
     *    取值定义表 `export BOX64_DYNAREC_BIGBLOCK=0,1,2,3`，逗号候选值
     *    不是真值，sh source 语义下根本不加载它，旧版当环境导出是错的）；
     *  - 仅提取 `KEY=VALUE` / `export KEY=VALUE` 行（行内注释/引号剥离），
     *    不执行 —— 配置文件无法注入任意代码，也碰不到容器隔离变量；
     *  - **同名键后写覆盖**（v3.2.29 修复）：sh 逐文件 source 时后载入的
     *    文件覆盖先载入的（startfex.sh 顺序 path.conf 在 dynarec_active.conf
     *    之前 → active 的值必须胜出；旧版 putIfAbsent 先写优先，方向反了）。
     *
     * @return 键值映射；无 /opt/conf 时为空 map。
     */
    fun readOptConfs(wineDir: File? = null): Map<String, String> {
        val confDir = optRoot(wineDir)?.let { File(it, "conf") } ?: return emptyMap()
        if (!confDir.isDirectory) return emptyMap()
        val fixed = listOf(
            "env.conf", "cores.conf", "path.conf",
            "dynarec_fixmpeg.conf", "dynarec_active.conf"
        )
        // 非 KEY=VALUE 环境文件：wine_path.conf（槽位自管）+ custom.conf（DY 取值定义表）
        val skip = setOf("wine_path.conf", "custom.conf")
        val files = LinkedHashMap<String, File>()
        fixed.forEach { name -> File(confDir, name).takeIf { it.isFile }?.let { files[name] = it } }
        confDir.listFiles { f -> f.isFile && f.name.endsWith(".conf") && f.name !in skip }
            ?.sortedBy { it.name }
            ?.forEach { files.putIfAbsent(it.name, it) }
        val out = LinkedHashMap<String, String>()
        files.values.forEach { f ->
            f.readLines().forEach { raw ->
                val parsed = parseConfLine(raw) ?: return@forEach
                out[parsed.first] = parsed.second   // 后写覆盖（sh source 语义）
            }
        }
        return out
    }

    /**
     * 单行 KEY=VALUE 解析（v3.2.29）：export 前缀剥离 → 键名校验 →
     * **行内注释剥离**（env.conf 的 `export WINEDLLOVERRIDES="dxgi=n,b"  # 如果使用DXVK`
     * 旧版把注释全部吞进值里）→ 成对引号剥离。
     */
    internal fun parseConfLine(raw: String): Pair<String, String>? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return null
        line = line.removePrefix("export ").trim()
        val eq = line.indexOf('=')
        if (eq <= 0) return null
        val key = line.substring(0, eq).trim()
        if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null
        return key to stripInlineComment(line.substring(eq + 1).trim())
    }

    /** 值的行内注释剥离（引号区段外的 ` #…` 截断）+ 成对引号剥离。 */
    internal fun stripInlineComment(value: String): String {
        val out = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (quote == null && (c == '"' || c == '\'')) {
                quote = c; out.append(c); i++; continue
            }
            if (quote != null) {
                if (c == quote) quote = null
                out.append(c); i++; continue
            }
            if (c == '#' && (i == 0 || value[i - 1].isWhitespace())) break
            out.append(c); i++
        }
        var v = out.toString().trim()
        if (v.length >= 2 &&
            ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
        ) {
            v = v.substring(1, v.length - 1).trim()
        }
        return v
    }

    /**
     * sh `$VAR` / `${VAR}` 引用展开（v3.2.29）：path.conf 的
     * `export LD_LIBRARY_PATH=…:$LD_LIBRARY_PATH` 旧版原样透传字符串
     * `$LD_LIBRARY_PATH`，wine 拿到的是字面美元符。按 [scope]（当前
     * 已构建的 env + conf 已解析值）展开；scope 中不存在的引用保持
     * 字面（比 sh 的静默展开为空更可诊断）。
     */
    internal fun expandShellVars(value: String, scope: Map<String, String>): String {
        if (!value.contains('$')) return value
        val sb = StringBuilder(value.length + 32)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '$') { sb.append(c); i++; continue }
            if (i + 1 >= value.length) { sb.append(c); break }
            val braced = value[i + 1] == '{'
            val nameStart = if (braced) i + 2 else i + 1
            var j = nameStart
            while (j < value.length && (value[j].isLetterOrDigit() || value[j] == '_')) j++
            val name = value.substring(nameStart, j)
            if (name.isEmpty()) { sb.append(c); i++; continue }
            val closeOk = !braced || (j < value.length && value[j] == '}')
            if (!closeOk) { sb.append(c); i++; continue }
            sb.append(scope[name] ?: "$$name")
            i = if (braced) j + 1 else j
        }
        return sb.toString()
    }

    /**
     * v3.3.2：MIDI SoundFont 自动探测（配合 0010-midimap-fluidsynth
     * wine 补丁 —— midimap 内置 "Wine Software Synth" 软合成端口）。
     *
     * rootfs 预置 .sf2 音色库（FluidR3_GM 等）时自动设置
     * WINE_MIDI_SOUNDFONT，midimap 的 unix 侧据此 dlopen libfluidsynth
     * 并加载音色（缺失时补丁侧静默回落 —— 无内置合成端口，行为与
     * 未打补丁一致，不影响构建与启动）。容器 env= 仍可覆盖。
     */
    fun applyMidiSoundfontEnv(env: MutableMap<String, String>, log: (String) -> Unit = {}) {
        if (!env["WINE_MIDI_SOUNDFONT"].isNullOrBlank()) return
        // 用户 env/conf 已显式指定 MIDI_SF2_PATH（生态包惯例：
        // env.conf 的 MIDI_SF2_PATH=/…/opt/midi_server/sndfnt.sf2）→ 直接映射
        env["MIDI_SF2_PATH"]?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile && f.length() > 0) {
                env["WINE_MIDI_SOUNDFONT"] = f.absolutePath
                log("MIDI 音色库: ${f.name}（来自 MIDI_SF2_PATH）→ WINE_MIDI_SOUNDFONT")
                return
            }
        }
        val dirs = listOf(
            "$RFS_ROOT/usr/share/soundfonts",
            "$RFS_ROOT/usr/local/share/soundfonts",
            "$RFS_ROOT/usr/share/sounds/sf2",
            "$RFS_ROOT/opt/soundfonts",
            "$RFS_ROOT/opt/midi_server"
        )
        val sf2 = dirs.asSequence()
            .map { File(it) }
            .filter { it.isDirectory }
            .flatMap { it.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".sf2") }?.asSequence() ?: emptySequence() }
            .firstOrNull { it.length() > 1024 * 1024 } ?: return
        env["WINE_MIDI_SOUNDFONT"] = sf2.absolutePath
        log("MIDI 音色库: ${sf2.name} → WINE_MIDI_SOUNDFONT（midimap 内置软合成可用）")
    }

    /**
     * opt/locale.conf → 语言（startwine.sh：export LC_ALL="$(cat /opt/locale.conf)"）。
     *
     * @return 合法 locale 字符串；无文件/内容非法为 null。
     */
    fun readOptLocale(wineDir: File? = null): String? {
        val f = optRoot(wineDir)?.let { File(it, "locale.conf") } ?: return null
        if (!f.isFile) return null
        val raw = runCatching { f.readLines().firstOrNull() }.getOrNull() ?: return null
        val value = raw.trim().trim('"', '\'')
        if (value.isEmpty()) return null
        // C / C.UTF-8 / POSIX / language / language_REGION.charset / @modifier
        return value.takeIf {
            it.matches(Regex("[A-Za-z]{2,3}(_[A-Za-z]{2,4})?(\\.[A-Za-z0-9-]+)?(@[A-Za-z0-9-]+)?"))
        }
    }

    /**
     * 应用 /opt 生态环境（conf **source 语义** + locale.conf）。
     *
     * v3.2.27（用户原话："启动 wine 依旧没有读取 opt 下的任何 conf 配置，
     * 你根本没有 env 或者 export，更没有执行 sh 里那种. /路径"）：
     * 旧实现的 BOX64_*、WINEESYNC 等白名单太窄 —— env.conf 里的
     * MESA_*、GALLIUM_*、自定义调优变量全部被丢弃，等于没读。现按
     * startwine.sh 逐个 source /opt/conf 下全部 *.conf 的语义执行：
     *  - **全部 KEY=VALUE 都 export** 给 wine 进程（逐项日志可见），
     *    仅 App 链路生存所需的关键变量（WINEPREFIX/加载器/X11/动态库
     *    路径等，见 [CONF_BLOCKED_KEYS]）不透传并大声说明；
     *  - PATH 特殊处理：合并而非覆盖 —— conf PATH 中 env PATH 缺失的
     *    目录前置补入（等价 sh 的 `export PATH=/usr/bin:$PATH` 追加语义）；
     *  - GLIBC_BIN 兜底导出（sh：`export GLIBC_BIN=/usr/local/bin`）——
     *    指向 rootfs 的 usr/local/bin，供 rootfs 脚本定位 box64/wineserver；
     *  * 容器 env=（applyExtraEnv，链路最后）仍可覆盖一切；
     *  * locale.conf → LANG/LC_ALL 已上移 buildBaseEnv（v3.2.29：语言
     *    唯一来源 /opt/locale.conf；conf 目录各 .conf 显式 export 的
     *    LANG/LC_ALL 走下方通用覆盖逻辑）；
     *  * cores.conf 的 PRIMARY_CORES/SECONDARY_CORES 走通用导出；
     *    applyTuningEnv 在其后，容器 cores 配置始终优先。
     */
    fun applyOptConfEnv(
        env: MutableMap<String, String>,
        container: ContainerData,
        wineDir: File? = null,
        log: (String) -> Unit = {}
    ) {
        val root = optRoot(wineDir)
        if (root == null) {
            log("opt 生态: 未检测到（探测: ${optProbeReport(wineDir)}）—— conf/locale.conf 全部旁路")
            log("→ 期望位置: /data/data/com.anwind/files/rootfs/opt/{conf,locale.conf,prefix,dxvk1} 或 wine 槽位目录内同名布局")
            return
        }
        val conf = readOptConfs(wineDir)
        // locale.conf → LANG/LC_ALL 已上移 buildBaseEnv（v3.2.29：语言
        // 唯一来源 /opt/locale.conf；conf/*.conf 若显式 export LANG/
        // LC_ALL，走下方通用 conf 覆盖逻辑，容器 env= 仍最高优先）。
        if (conf.isEmpty()) {
            if (File(root, "conf").isDirectory)
                log("opt 生态: $root/conf 内无可解析的 KEY=VALUE 配置")
            return
        }
        val userEnv = container.env
        // v3.3.18（用户实报：“选择 wrapper 驱动依旧是 turnip，是不是和我的
        // conf 配置有关？”—— 正是）：Vulkan ICD 选择保护。启动链顺序是
        // 6a 注入容器所选驱动的 VK_ICD_FILENAMES → 6c source opt/conf ——
        // 用户 path.conf 常见硬编码 turnip 的 freedreno ICD（如
        // VK_ICD_FILENAMES=…/freedreno_icd.aarch64.json），旧版此处无差别
        // env[k]=v 覆盖 → 选了 wrapper 也会被顶回 turnip。现在容器选中
        // wrapper / turnip 槽位时，VK_ICD_FILENAMES / VK_DRIVER_FILES 两个
        // ICD 指定键【不得被 conf 覆盖】（VK_DRIVER_FILES 是新 loader 的
        // 优先键，留着它同样能压过注入值）。conf 与选择的同步由
        // GuanliSupport.syncPathConfVkIcd 负责（UI 选择/运行时激活均落
        // conf），此处仅防护手工编辑/多容器等不同步的残余情形。未选槽位
        //（Mesa 内置档）时 conf 值照常透传（用户 conf 自定 ICD 仍生效）。
        val vkSlotActive = (container.wrapper.isNotBlank() && container.wrapper != "off") ||
            (container.turnip.isNotBlank() && container.turnip != "off")
        val vkGuarded = mutableListOf<String>()
        val applied = mutableListOf<String>()
        val blocked = mutableListOf<String>()
        // $VAR 展开作用域：当前 env 优先，conf 自身已解析值作第二来源
        // （sh 逐文件 source 时先载入的变量对后载入的行可见 —— 同一语义）
        val expandScope = LinkedHashMap<String, String>(env)
        conf.forEach { (k, v) -> expandScope.putIfAbsent(k, v) }
        conf.forEach { (k, rawV) ->
            val v = expandShellVars(rawV, expandScope)
            when {
                // 容器 env= 已显式设置的键跳过（最高优先）
                userEnv.contains("$k=") -> {
                    log("opt conf: $k 被容器 env= 显式配置覆盖，保留容器值")
                }
                k in CONF_BLOCKED_KEYS -> blocked.add(k)
                vkSlotActive && (k == "VK_ICD_FILENAMES" || k == "VK_DRIVER_FILES") ->
                    vkGuarded.add(k)
                k == "PATH" -> {
                    // 合并而非覆盖：conf PATH 里 env PATH 缺失的目录前置补入
                    // （sh 的 `export PATH=/usr/bin:$PATH` 同款追加语义）
                    val cur = env["PATH"] ?: ""
                    val have = cur.split(':').filter { it.isNotBlank() }.toSet()
                    val add = v.split(':').filter { it.isNotBlank() && it !in have }
                    if (add.isNotEmpty()) {
                        env["PATH"] = (add + cur).joinToString(":")
                        applied.add("PATH+=${add.joinToString(":")}")
                    }
                }
                else -> {
                    env[k] = v
                    applied.add("$k=$v")
                }
            }
        }
        if (blocked.isNotEmpty()) {
            log("opt 生态: ${blocked.joinToString(", ")} 属 App 链路关键变量（WINEPREFIX/加载器/X11/动态库路径），conf 值不透传")
        }
        if (vkGuarded.isNotEmpty()) {
            log(
                "opt 生态: ${vkGuarded.joinToString(", ")} 被容器 Vulkan 驱动选择接管" +
                    "（wrapper=${container.wrapper.ifBlank { "—" }} / turnip=${container.turnip.ifBlank { "—" }}）" +
                    "，conf 值不透传 —— 启动链已注入所选驱动 ICD（选了 wrapper 不再被 path.conf 顶回 turnip）"
            )
        }
        // wine_path.conf 已按用户要求剔除：wine 目录由 AnWind 槽位体系管理
        log(
            if (applied.isEmpty()) "opt 生态: conf ${conf.size} 项解析后无新增导出（全部被容器 env= 覆盖或为关键变量）"
            else "opt 生态: conf ${conf.size} 项已 source（export ${applied.size} 项: ${applied.joinToString(", ").take(400)}）"
        )
        // GLIBC_BIN（startwine.sh：export GLIBC_BIN=/usr/local/bin）——
        // 指向 rootfs 的 usr/local/bin（box64/wineserver 包装所在地），
        // conf/env 均未设置且目录存在时兜底导出，供 rootfs 脚本消费。
        if (!env.containsKey("GLIBC_BIN") && !userEnv.contains("GLIBC_BIN=")) {
            val glibcBin = File("$RFS_USR/local/bin")
            if (glibcBin.isDirectory) {
                env["GLIBC_BIN"] = glibcBin.absolutePath
                log("opt 生态: export GLIBC_BIN=${glibcBin.absolutePath}（startwine.sh 同款）")
            }
        }
    }

    /**
     * conf 值不得覆盖 App 链路关键变量（wine/box64 进程生存所需）。
     * source 语义的黑名单：WINEPREFIX/加载器与 X11 会话/动态库搜索路径
     * （LD_* 会破坏 aarch64 宿主二进制加载）/BOX64_*_PATH（App 按 wine
     * 槽位精心构造，覆盖即断链）。其余变量全部透传。
     */
    private val CONF_BLOCKED_KEYS = setOf(
        "WINEPREFIX", "WINE", "WINELOADER", "WINESERVER", "WINEDLLPATH", "WINEDLLDIR",
        "WINELOADERNOEXEC", "WINEBOOT",
        "DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR",
        "HOME", "PWD", "SHELL", "USER", "LOGNAME", "TERM",
        "TMPDIR", "TEMP", "TMP", "PULSE_SERVER",
        "LD_PRELOAD", "LD_LIBRARY_PATH", "LD_DEBUG",
        "BOX64_PATH", "BOX64_LD_LIBRARY_PATH"
    )

    /**
     * 用户附加环境 + DLL 覆盖（apply_extra_env 移植）。
     *
     * env= 为空格分隔的 K=V 列表，原样透传 —— 语义与脚本的
     * `export $_extra`（词切分后逐个 export）一致；放在构建链最后，
     * 用户可覆盖任何上游变量（含 BOX64_* / GALLIUM_DRIVER / WINEDEBUG）。
     */
    fun applyExtraEnv(env: MutableMap<String, String>, container: ContainerData) {
        val extra = container.env.trim()
        if (extra.isNotEmpty()) {
            extra.split(Regex("\\s+")).forEach { token ->
                val eq = token.indexOf('=')
                if (eq > 0) {
                    env[token.substring(0, eq)] = token.substring(eq + 1)
                }
            }
        }
        val ovr = container.dxoverrides.trim()
        if (ovr.isNotEmpty()) {
            val existing = env["WINEDLLOVERRIDES"]
            env["WINEDLLOVERRIDES"] = if (existing.isNullOrBlank()) {
                ovr
            } else {
                "$ovr:$existing"
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun isAarch64(): Boolean {
        val arch = System.getProperty("os.arch", "aarch64").lowercase()
        return arch == "aarch64" || arch == "arm64"
    }

    /** 兜底目录创建（脚本 mkdir -p 矩阵的移植）。 */
    fun ensureRuntimeDirs(container: ContainerData) {
        runCatching {
            // wineserver 运行时目录（wine 把 /tmp 硬编码为 $RFS_USR/tmp，
            // 启动时要在其下创建 .wine-<uid>）
            RUNTIME_DIR.mkdirs()
            // App 侧 X11 socket 目录（wine 客户端经它连内置 X 服务）
            File(ContainerManager.X11_SOCK_DIR).mkdirs()
            // pulse unix socket 目录
            File(RUNTIME_DIR, "pulse").mkdirs()
            // XDG 家目录
            File("$RFS_ROOT/home/.config").mkdirs()
            File("$RFS_ROOT/home/.cache").mkdirs()
            File("$RFS_ROOT/home/.local/share").mkdirs()
            // 容器前缀与日志
            File(container.prefixPath).mkdirs()
            File("${ContainerManager.CONTAINERS_ROOT}/logs").mkdirs()
        }
    }
}
