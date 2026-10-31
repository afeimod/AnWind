package com.anwind.apps.containers.launcher

import com.anwind.apps.containers.Box64Preset
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

        // --- 语言 → 容器自己的（§3；只设 LANG 不设 LC_ALL：bionic 无
        //     locale 数据，LC_ALL 会触发 bash setlocale 噪音；wine 按 LANG
        //     识别界面语言已足够） ---
        val lang = container.lang.trim().ifEmpty { "zh_CN.UTF-8" }
        env["LANG"] = lang

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
        // aarch64：Turnip 一致性规避（对齐脚本 uname -m 分支）
        if (isAarch64()) env["TU_DEBUG"] = "noconform,sysmem"

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
            }
            "freedreno" -> env["GALLIUM_DRIVER"] = "freedreno"
            else -> {
                // softpipe（含旧 llvmpipe 归一值）
                env["GALLIUM_DRIVER"] = "softpipe"
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
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
        env["BOX64_LOG"] = "0"
        env["BOX64_NOBANNER"] = "1"
        env["BOX64_DYNAREC_TRACE"] = "0"
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
        when (container.preset) {
            Box64Preset.COMPATIBILITY -> {
                env["BOX64_DYNAREC_BIGBLOCK"] = "0"
                env["BOX64_DYNAREC_SAFEFLAGS"] = "2"
                env["BOX64_DYNAREC_FASTNAN"] = "0"
                env["BOX64_DYNAREC_FASTROUND"] = "0"
                env["BOX64_DYNAREC_X87DOUBLE"] = "1"
                env["BOX64_DYNAREC_STRONGMEM"] = "1"
            }
            Box64Preset.PERFORMANCE -> {
                env["BOX64_DYNAREC_BIGBLOCK"] = "1"
                env["BOX64_DYNAREC_SAFEFLAGS"] = "0"
                env["BOX64_DYNAREC_FASTNAN"] = "1"
                env["BOX64_DYNAREC_FASTROUND"] = "1"
                env["BOX64_DYNAREC_X87DOUBLE"] = "0"
                env["BOX64_DYNAREC_STRONGMEM"] = "0"
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
     * ①c（v3.2.3 勘误）：此前版本在此处导出过 WINE_DISABLE_KERNEL_WRITEWATCH=1，
     *    但全 wine 源码（proton_10.0 / wine-10.0 / hangover-11.16）均无此变量，
     *    属无效安慰剂 —— 已移除。写监视（userfaultfd）风险实际在构建侧
     *    双重关闭：配方参数 ac_cv_header_linux_userfaultfd_h=no +
     *    hangover-11.16 源码自身 `!defined(__ANDROID__)` 排除。
     *
     * ② HODLL64/HODLL（仅 arm64ec 形态）：arm64ec 跑 x86_64/i386 程序
     *    靠仿真器 DLL —— 上游 official/proton 树默认是 xtajit64 桩（只报
     *    不支持），hangover 树默认 wowbox64（需 box64 PE）。v3.1.4 起
     *    wine 包自带 libarm64ecfex + libwow64fex（FEX），存在即默认启用；
     *    用户 HODLL64/HODLL 覆盖优先（先探测用户值再落默认）。
     */
    fun applyWineCompatEnv(
        env: MutableMap<String, String>,
        arch: WineRuntime.Arch,
        wineDir: File,
        userEnv: String = ""
    ) {
        env.putIfAbsent("WINEESYNC", "0")
        env.putIfAbsent("WINEFSYNC", "0")
        // v3.2.3：预加载器首进程规避（详见 KDoc ①b；子进程风险由
        // WineLayoutRepair 停用 bin/*-preloader + WineProbePatch 改写
        // futex_waitv 探测兕底，两者均免重编作用于旧包）
        env.putIfAbsent("WINELOADERNOEXEC", "1")
        if (arch != WineRuntime.Arch.AARCH64) return
        val peDir = File(wineDir, "lib/wine/aarch64-windows")
        val hasUserHodll64 = userEnv.contains("HODLL64=")
        val hasUserHodll = userEnv.contains("HODLL=")
        if (!hasUserHodll64 && File(peDir, "libarm64ecfex.dll").isFile) {
            env["HODLL64"] = "libarm64ecfex.dll"
        }
        if (!hasUserHodll && File(peDir, "libwow64fex.dll").isFile) {
            env["HODLL"] = "libwow64fex.dll"
        }
    }

    // ------------------------------------------------------------------
    // 4) 音频 / 用户附加环境（audio_setup / apply_extra_env 的注入端）
    // ------------------------------------------------------------------

    /** 音频关闭（audio=off）：清空 PULSE_SERVER，wine 走无声。 */
    fun applyAudioOff(env: MutableMap<String, String>) {
        env["PULSE_SERVER"] = ""
    }

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
