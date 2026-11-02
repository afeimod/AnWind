package com.anwind.apps.containers

import java.io.File

/**
 * AnWind Wine 容器 — 数据模型与 container.conf 编解码（App 侧）。
 *
 * 存储布局（与 rootfs 侧 anwind-container CLI 完全同源，双端互通）：
 *   容器根目录 : /data/data/com.anwind/files/rootfs/home/.anwind/containers
 *   单个容器   : <name>/container.conf + <name>/prefix (WINEPREFIX)
 *   全局配置   : /data/data/com.anwind/files/rootfs/usr/etc/anwind/container.conf
 *
 * conf 为 key=value 行格式（# 注释），键集与 CLI write_new_conf 一一对应：
 *   name / backend / preset / screen / dmode / gallium / env / dxvk /
 *   vkd3d / dxoverrides / wine / lang / audio / created
 *
 * v3.2.15（wineguanli.py 重制）新增键：
 *   frame（帧率限制 DXVK_FRAME_RATE）/ cores（主核心集）/ dsound /
 *   dshow（DLL 加载序）/ dpi（LogPixels）/ winver（Windows 版本）/
 *   mangohud（MANGOHUD_CONFIG）/ flags（高级启动开关 csv）/
 *   turnip / zink / wined3d（驱动版本槽位，对接 /opt 生态）
 *
 * 显示方案（重要）：本方案为 X11 —— X 服务为 AnWind 内置 termux-x11/
 * libXlorie，unix socket 位于 App 侧 $PREFIX/tmp/.X11-unix/Xn（默认 :1）。
 * rootfs 内 libX11/xtrans/libxcb 已按该 socket 路径打补丁，容器内程序
 * 直连 App 侧 X 服务，无 Wayland/Weston 参与。
 */

// ---------------------------------------------------------------------------
// CPU 翻译后端（与 anwind-wine 的后端选择逻辑一致）
// ---------------------------------------------------------------------------

/**
 * 容器使用的 CPU 翻译后端。
 *
 * @property id    写入 container.conf 的 backend= 值
 * @property label UI 展示名
 * @property desc  一句话说明（UI 副标题）
 */
enum class ContainerBackend(
    val id: String,
    val label: String,
    val desc: String
) {
    /** 按设备架构与已装组件自动挑选（aarch64: arm64ec → box64 → hangover；x86_64: native）。 */
    AUTO("auto", "自动选择", "自动挑选可用后端（推荐）"),

    /** aarch64 Wine 原生运行（bionic-arm64ec 构建，new WoW64，无需模拟器）。 */
    ARM64EC("arm64ec", "ARM64EC 原生", "aarch64 Wine 原生运行（new WoW64，无模拟开销）"),

    /** box64 + x86_64 Wine（构建系统 wine 包 = x86_64 新 WoW64，主力路径）。 */
    BOX64("box64", "Box64", "box64 运行 x86_64 Wine，性能最佳"),

    /** aarch64 Wine + 内建 QEMU 模拟（hangover 构建，兼容性兜底）。 */
    HANGOVER("hangover", "Hangover", "aarch64 Wine + QEMU，兼容兜底"),

    /** FEX-Emu (FEXInterpreter) + x86_64 Wine（需 rootfs 内已安装 FEX）。 */
    FEXCORE("fexcore", "FEXCore", "FEX-Emu 运行 x86_64 Wine（需已安装）"),

    /** x86_64 设备直接运行，不经翻译层。 */
    NATIVE("native", "原生 x86_64", "x86_64 设备直连，无翻译层");

    companion object {
        fun fromId(id: String?): ContainerBackend =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

// ---------------------------------------------------------------------------
// Box64 动态重编译预设（对齐 anwind-wine 的 BOX64_DYNAREC_* 注入）
// ---------------------------------------------------------------------------

/**
 * Box64 DYNAREC 预设。
 *
 * PERFORMANCE    : 速度优先，适合多数游戏/应用
 * COMPATIBILITY  : 强内存序 + 安全野指针，适合崩溃的程序
 */
enum class Box64Preset(
    val id: String,
    val label: String,
    val desc: String
) {
    PERFORMANCE("performance", "性能优先", "STRONGMEM=0，速度快"),
    COMPATIBILITY("compatibility", "兼容优先", "STRONGMEM=1，更稳定");

    companion object {
        fun fromId(id: String?): Box64Preset =
            entries.firstOrNull { it.id == id } ?: PERFORMANCE
    }
}

// ---------------------------------------------------------------------------
// Mesa/Gallium 渲染器选项（对齐 anwind-wine 的 GALLIUM_DRIVER 注入）
// ---------------------------------------------------------------------------

/**
 * 渲染器选择。
 *
 * AUTO       : 不强制指定，走 Mesa 默认（aarch64 上通常为 freedreno/turnip）
 * ZINK       : OpenGL-on-Vulkan（配 DXVK/VKD3D 时推荐）
 * FREEDRENO  : Adreno 驱动（msm/kgsl）
 * SOFTPIPE   : 软渲染兜底（无 GPU 加速时；rootfs 的 aarch64/x86_64
 *              mesa 均未构建 llvmpipe —— 交叉构建不带 LLVM，
 *              GALLIUM_DRIVER=llvmpipe 是无效驱动名，v3.1.6 改为
 *              实际存在的 softpipe；旧容器的 "llvmpipe" 值自动归一）
 */
enum class GalliumDriver(val id: String, val label: String) {
    AUTO("auto", "自动"),
    ZINK("zink", "Zink (Vulkan)"),
    FREEDRENO("freedreno", "Freedreno (Adreno)"),
    SOFTPIPE("softpipe", "Softpipe (软渲染)");

    companion object {
        fun fromId(id: String?): GalliumDriver = when (id?.trim()?.lowercase()) {
            // v3.1.6：旧值归一（llvmpipe 未构建，一律归 softpipe）
            "llvmpipe", "softpipe" -> SOFTPIPE
            "zink" -> ZINK
            "freedreno" -> FREEDRENO
            "auto", "", null -> AUTO
            else -> AUTO
        }
    }
}

// ---------------------------------------------------------------------------
// 显示模式（对齐终端 glibc-runner 的 -d/-v/-f，v2.26 新增）
// ---------------------------------------------------------------------------

/**
 * 容器显示模式（container.conf 的 dmode= 键）。
 *
 * OFF  : 跟随 X11 窗口（native，桌面会话默认）
 * D    : 固定 X 屏幕分辨率（glibc-runner -d：游戏真实全屏渲染 + App 侧贴合拉伸）
 * V    : wine 虚拟桌面（-v：explorer /desktop，DDraw 老游戏必需）
 * F    : 强制窗口直出（-f：清除注册表 wine 蓝底桌面 + 固定分辨率）
 */
enum class DisplayMode(val id: String, val label: String, val desc: String) {
    OFF("off", "跟随窗口", "X 屏幕跟随窗口尺寸（桌面/办公）"),
    D("d", "固定分辨率 (-d)", "X 屏幕锁定指定分辨率，游戏真实全屏渲染"),
    V("v", "虚拟桌面 (-v)", "wine explorer /desktop，DDraw 老游戏兼容"),
    F("f", "窗口直出 (-f)", "强制窗口直出并清除 wine 蓝底桌面");

    companion object {
        fun fromId(id: String?): DisplayMode =
            entries.firstOrNull { it.id == id } ?: OFF
    }
}

// ---------------------------------------------------------------------------
// 容器语言选项（container.conf 的 lang= 键，v2.26 新增）
// ---------------------------------------------------------------------------

/** 容器语言（wine 代码页/IME 行为由它决定；与 Termux 会话语言完全隔离）。 */
object ContainerLangs {
    val CHOICES: List<String> = listOf(
        "zh_CN.UTF-8", "en_US.UTF-8", "zh_TW.UTF-8",
        "ja_JP.UTF-8", "ko_KR.UTF-8"
    )
    val DISPLAY: List<String> = listOf(
        "简体中文", "English", "繁體中文", "日本語", "한국어"
    )
    const val DEFAULT = "zh_CN.UTF-8"
}

// ---------------------------------------------------------------------------
// 容器音频模式（container.conf 的 audio= 键，v2.26 新增）
// ---------------------------------------------------------------------------

/** 容器音频模式（音频由 rootfs 自带 PulseAudio 承载，与 Termux 侧隔离）。 */
enum class ContainerAudio(val id: String, val label: String, val desc: String) {
    PULSE("pulse", "PulseAudio", "rootfs 自带 PulseAudio（unix socket 隔离，推荐）"),
    OFF("off", "禁用", "关闭容器音频（不出声也不拉起服务）");

    companion object {
        fun fromId(id: String?): ContainerAudio =
            entries.firstOrNull { it.id == id } ?: PULSE
    }
}

// ---------------------------------------------------------------------------
// v3.2.15：Windows 版本表（wineguanli.py update_windows_version_in_system_reg
// 的版本映射表移植 —— system.reg 的 CurrentVersion/ProductName/CurrentBuild）
// ---------------------------------------------------------------------------

/** 一个 Windows 版本的注册表三件套。 */
data class WindowsVersion(
    val id: String,
    val label: String,
    val currentVersion: String,
    val productName: String,
    val currentBuild: String
)

object WindowsVersions {
    val ALL: List<WindowsVersion> = listOf(
        WindowsVersion("win10", "Windows 10", "10.0", "Windows 10", "19045"),
        WindowsVersion("win81", "Windows 8.1", "6.3", "Windows 8.1", "9600"),
        WindowsVersion("win8", "Windows 8", "6.2", "Windows 8", "9200"),
        WindowsVersion("win7", "Windows 7", "6.1", "Windows 7", "7601"),
        WindowsVersion("vista", "Windows Vista", "6.0", "Windows Vista", "6002"),
        WindowsVersion("winxp", "Windows XP", "5.1", "Windows XP", "2600"),
        WindowsVersion("win2000", "Windows 2000", "5.0", "Windows 2000", "2195"),
        WindowsVersion("win98", "Windows 98", "4.1", "Windows 98", "2222"),
        WindowsVersion("win95", "Windows 95", "4.0", "Windows 95", "950")
    )
    val DEFAULT_ID = "win10"
    fun fromId(id: String?): WindowsVersion =
        ALL.firstOrNull { it.id == id?.trim()?.lowercase() } ?: ALL.first()
}

// ---------------------------------------------------------------------------
// v3.2.15：高级启动开关（wineguanli.py 基本设置勾选组的 AnWind 语义映射）
// ---------------------------------------------------------------------------

/**
 * 高级启动开关（container.conf 的 flags= csv 子集）。
 *
 * 语义映射（wineguanli.py → AnWind 原生启动链）：
 * - UNITY（Unity 单线程渲染）→ env BOX64_UNITYPLAYER=1（box64 官方开关）
 * - OPENGL（OpenGL 驱动）→ 保持记录；不强制 DXVK 时 wine 自走 wined3d/OpenGL
 * - F5TASK / IB / DEBUG / INDEPENDENT / CLOSESERVICE → 保留槽位，供用户
 *   自带的 /opt 生态脚本（startwine 类）读取 container.conf 时消费。
 */
object ContainerFlags {
    const val UNITY = "unity"
    const val OPENGL = "opengl"
    const val F5TASK = "f5task"
    const val IB = "ib"
    const val DEBUG = "debug"
    const val INDEPENDENT = "independent"
    const val CLOSESERVICE = "closeservice"

    /** 全部合法开关（UI 勾选组展示顺序）。 */
    val ALL = listOf(UNITY, OPENGL, F5TASK, IB, DEBUG, INDEPENDENT, CLOSESERVICE)

    fun parse(csv: String?): Set<String> =
        csv?.split(',', ';').orEmpty()
            .map { it.trim().lowercase() }
            .filter { it in ALL }.toSet()

    fun serialize(flags: Set<String>): String =
        ALL.filter { it in flags }.joinToString(",")
}

// ---------------------------------------------------------------------------
// 容器数据模型
// ---------------------------------------------------------------------------

/**
 * 单个 Wine 容器的全部配置。
 *
 * 字段与 container.conf 的键一一对应；缺省值与 CLI create 相同，
 * 保证 App 侧新建的容器能被终端里的 anwind-container / anwind-wine
 * 直接读取，反之亦然。
 */
data class ContainerData(
    /** 容器名（目录名，同名字段冗余存储便于人工查看）。 */
    val name: String = "",
    /** CPU 翻译后端。 */
    val backend: ContainerBackend = ContainerBackend.AUTO,
    /** Box64 DYNAREC 预设（backend 为 box64 时生效）。 */
    val preset: Box64Preset = Box64Preset.PERFORMANCE,
    /** 虚拟分辨率："native"（跟随 X11 桌面）或 "宽x高"。 */
    val screen: String = DEFAULT_SCREEN,
    /** 显示模式（对齐 glibc-runner -d/-v/-f；screen=WxH 时生效）。 */
    val dmode: DisplayMode = DisplayMode.OFF,
    /** Mesa 渲染器强制项，"auto" 表示不干预。 */
    val gallium: String = DEFAULT_GALLIUM,
    /** 附加环境变量（空格分隔 K=V 列表，原样透传 anwind-wine）。 */
    val env: String = "",
    /**
     * DXVK 注入开关与版本戳（v3.2.14 语义）：
     * "off" = 明确关闭（不注入）；"auto" = 开启（前缀构建时自动解压
     * APK 内置 assets/dxvk/dxvk-<ver>.tar.gz 到 C:\\windows）；
     * 具体版本号（如 "2.3.1"）= 已注入的版本戳（注入器回写，仍视为开启）。
     */
    val dxvk: String = DXVK_AUTO,
    /** VKD3D-Proton 版本戳（"off" = 未安装）。 */
    val vkd3d: String = "off",
    /** win32.drv 替换（DLL 覆盖列表，DXVK/VKD3D 安装器会自动改写）。 */
    val dxoverrides: String = "",
    /** 指定 wine 版本目录名（空 = 用全局 default_wine → /usr/opt/wine）。 */
    val wine: String = "",
    /** 容器语言（LANG/LC_ALL；默认简体中文，与 Termux 会话语言隔离）。 */
    val lang: String = ContainerLangs.DEFAULT,
    /** 容器音频模式（rootfs 自带 PulseAudio / 禁用）。 */
    val audio: ContainerAudio = ContainerAudio.PULSE,
    /** 创建时间（仅记录，人类可读）。 */
    val created: String = "",
    // ---- v3.2.15（wineguanli.py 重制）新增字段 ----
    /** 帧率限制（DXVK_FRAME_RATE）："0"=不限 / "60" / "120"。 */
    val frame: String = FRAME_DEFAULT,
    /** 主核心集（如 "0-3" 或 "0,1,2,3"）；空 = 不隔离，全核心。 */
    val cores: String = "",
    /** DirectSound DLL 加载序（user.reg dsound）。 */
    val dsound: String = "builtin,native",
    /** DirectShow(quartz) DLL 加载序（user.reg quartz）。 */
    val dshow: String = "builtin,native",
    /** DPI（user.reg LogPixels），60..240。 */
    val dpi: String = DPI_DEFAULT,
    /** Windows 版本（system.reg 三件套）：见 [WindowsVersions]。 */
    val winver: String = WindowsVersions.DEFAULT_ID,
    /** MANGOHUD_CONFIG 串（fps,cpu_stats,...）；空 = MangoHud 关。 */
    val mangohud: String = "",
    /** 高级启动开关 csv（见 [ContainerFlags]）。 */
    val flags: String = "",
    /** Turnip 驱动版本槽位（对接 /opt/tz 包名，用户生态自行消费）。 */
    val turnip: String = "",
    /** Zink 驱动版本槽位（对接 /opt/zink）。 */
    val zink: String = "",
    /** WineD3D 版本槽位（对接 /opt/vkord3d）。 */
    val wined3d: String = ""
) {
    /** WINEPREFIX 绝对路径（容器目录内 prefix/）。 */
    val prefixPath: String get() = "${ContainerManager.CONTAINERS_ROOT}/$name/prefix"

    /** 容器目录绝对路径。 */
    val containerDir: String get() = "${ContainerManager.CONTAINERS_ROOT}/$name"

    /** v3.2.15：flags csv → 开关集合。 */
    val flagSet: Set<String> get() = ContainerFlags.parse(flags)

    /** v3.2.15：是否启用指定开关。 */
    fun hasFlag(flag: String): Boolean = flag in flagSet

    /**
     * 以键值对形式导出（顺序与 CLI write_new_conf 一致），
     * 供 conf 序列化使用。
     */
    fun toSortedPairs(): List<Pair<String, String>> = listOf(
        "name" to name,
        "backend" to backend.id,
        "preset" to preset.id,
        "screen" to screen,
        "dmode" to dmode.id,
        "gallium" to gallium,
        "env" to env,
        "dxvk" to dxvk,
        "vkd3d" to vkd3d,
        "dxoverrides" to dxoverrides,
        "wine" to wine,
        "lang" to lang,
        "audio" to audio.id,
        "created" to created,
        // v3.2.15：wineguanli.py 重制新增键（未知键的老 CLI 忽略即可）
        "frame" to frame,
        "cores" to cores,
        "dsound" to dsound,
        "dshow" to dshow,
        "dpi" to dpi,
        "winver" to winver,
        "mangohud" to mangohud,
        "flags" to flags,
        "turnip" to turnip,
        "zink" to zink,
        "wined3d" to wined3d
    )

    /** 序列化为 container.conf 文本（# 头注释 + key=value）。 */
    fun toConfText(): String = buildString {
        appendLine("# AnWind Wine 容器配置（App 端 / anwind-container 共用）")
        appendLine("# 键集与 anwind-container CLI 完全一致，勿随意手改")
        toSortedPairs().forEach { (k, v) -> appendLine("$k=$v") }
    }

    companion object {
        const val DEFAULT_SCREEN = "native"
        const val DEFAULT_GALLIUM = "auto"

        /** v3.2.15：帧率限制/DPI 缺省值。 */
        const val FRAME_DEFAULT = "0"
        const val DPI_DEFAULT = "96"
        val FRAME_CHOICES: List<String> = listOf("0", "60", "120")
        val DPI_CHOICES: List<Int> = listOf(72, 96, 120, 144, 192)

        /** v3.2.14：DXVK 注入开关的开启值（用户需求：构建前缀时自动打入
         *  assets/dxvk 内的 dxvk-2.3.1.tar.gz）。 */
        const val DXVK_AUTO = "auto"
        const val DXVK_OFF = "off"

        /** 常用虚拟分辨率（"native" 表示跟随 X11 桌面窗口）。 */
        val SCREEN_CHOICES: List<String> = listOf(
            "native", "640x480", "800x600", "1024x768",
            "1280x720", "1280x800", "1366x768",
            "1600x900", "1920x1080", "2560x1440"
        )

        /** 从 container.conf 文件解析；文件不存在返回 null。 */
        fun fromConf(confFile: File): ContainerData? {
            if (!confFile.isFile) return null
            val kv = HashMap<String, String>()
            confFile.readLines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                kv[line.substring(0, eq)] = line.substring(eq + 1)
            }
            // v3.2.11（用户问题 1）：容器身份以【目录名】为准，conf 内 name= 仅是
            // 便于人工查看的冗余字段。旧逻辑 kv["name"] ?: 目录名 在 name= 为空值
            // 或与目录不一致时会产生空名/重名容器：空名容器在删除时
            // File(CONTAINERS_ROOT, "") 解析为容器根本身 → deleteRecursively()
            // 连根删光全部容器（用户实测“点三个点删一个容器结果所有容器都没了”）；
            // 重名容器则直接崩 LazyColumn 的 key 去重断言。目录名永远非空且唯一，
            // 从根上消除两类事故（update() 下次保存时会把 conf 内 name= 归一）。
            val dirName = confFile.parentFile?.name.orEmpty()
            return ContainerData(
                name = dirName.ifBlank { kv["name"].orEmpty() },
                backend = ContainerBackend.fromId(kv["backend"]),
                preset = Box64Preset.fromId(kv["preset"]),
                screen = kv["screen"] ?: DEFAULT_SCREEN,
                dmode = DisplayMode.fromId(kv["dmode"]),
                gallium = kv["gallium"] ?: DEFAULT_GALLIUM,
                env = kv["env"] ?: "",
                // v3.2.14：无键 = 未配置 → 默认开启（auto）；旧容器显式
                // dxvk=off 的保持关闭（用户明确选择）
                dxvk = kv["dxvk"] ?: DXVK_AUTO,
                vkd3d = kv["vkd3d"] ?: "off",
                dxoverrides = kv["dxoverrides"] ?: "",
                wine = kv["wine"] ?: "",
                lang = kv["lang"] ?: ContainerLangs.DEFAULT,
                audio = ContainerAudio.fromId(kv["audio"]),
                created = kv["created"] ?: "",
                // v3.2.15：wineguanli.py 重制键（缺键/非法值 → 缺省）
                frame = kv["frame"]?.trim()?.takeIf { it in FRAME_CHOICES } ?: FRAME_DEFAULT,
                cores = kv["cores"] ?: "",
                dsound = kv["dsound"]?.takeIf { it == "native,builtin" } ?: "builtin,native",
                dshow = kv["dshow"]?.takeIf { it == "native,builtin" } ?: "builtin,native",
                dpi = kv["dpi"]?.trim()?.toIntOrNull()?.coerceIn(60, 240)?.toString() ?: DPI_DEFAULT,
                winver = kv["winver"]?.takeIf { v -> WindowsVersions.ALL.any { it.id == v } } ?: WindowsVersions.DEFAULT_ID,
                mangohud = kv["mangohud"] ?: "",
                flags = kv["flags"] ?: "",
                turnip = kv["turnip"] ?: "",
                zink = kv["zink"] ?: "",
                wined3d = kv["wined3d"] ?: ""
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 体检结果模型（doctor）
// ---------------------------------------------------------------------------

/** 一条体检结论。 */
data class DoctorCheck(
    val ok: Boolean,
    val title: String,
    val detail: String
)

// ---------------------------------------------------------------------------
// Wine 多版本安装项（v2.26：/usr/opt/<wine|wine-名> 槽位）
// ---------------------------------------------------------------------------

/**
 * 一个已安装的 wine 版本槽位（ContainerManager.listWines 产出）。
 *
 * @property name      槽位名（目录名：wine 或 wine-9.8 等）
 * @property dir       绝对路径（/usr/opt/<name>）
 * @property version   .anwind-wine-info 里的 version=（wine --version 输出）
 * @property installed 安装时间戳
 */
data class WineInstall(
    val name: String,
    val dir: String,
    val version: String = "",
    val installed: String = ""
) {
    /** 选择器展示文本（含版本号）。 */
    val displayLabel: String
        get() = if (version.isNotEmpty()) "$name（$version）" else name
}
