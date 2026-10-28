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
        val gallium = when {
            container.gallium.equals("auto", true) || container.gallium.isBlank() ->
                if (isAarch64()) "freedreno" else "llvmpipe"
            else -> container.gallium.trim()
        }
        when (gallium.lowercase()) {
            "zink" -> {
                env["GALLIUM_DRIVER"] = "zink"
                env["ZINK_DESCRIPTORS"] = "lazy"
                env["ZINK_DEBUG"] = "compact"
            }
            "freedreno" -> env["GALLIUM_DRIVER"] = "freedreno"
            "llvmpipe", "softpipe" -> {
                env["GALLIUM_DRIVER"] = gallium
                env["LIBGL_ALWAYS_SOFTWARE"] = "1"
            }
        }
    }

    // ------------------------------------------------------------------
    // 3) Box64（apply_box64_env 移植）
    // ------------------------------------------------------------------

    /** box64 / fexcore 后端的 DYNAREC 与运行库变量。 */
    fun applyBox64Env(env: MutableMap<String, String>, container: ContainerData) {
        env["BOX64_LOG"] = "0"
        env["BOX64_NOBANNER"] = "1"
        env["BOX64_DYNAREC_TRACE"] = "0"
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
