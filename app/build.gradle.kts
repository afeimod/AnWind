plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ============================================================
// v2.14.8：ruffle 资产白名单过滤（打包期剔除仓库残留旧文件）
// ============================================================
// 背景：历次交付均为 zip 覆盖 app/，zip 无法表达"删除" —— 仓库
// assets/ruffle/ 会残留旧引擎文件（0.3.0 的双 wasm + 双 core.js，
// 约 28MB 死重）。Ruffle 自托管包本身为"双核"结构（ruffle.js 按
// 设备特性二选一加载 core+wasm），下列白名单即当前引擎的可加载
// 全集（供给层按文件名映射 assets/ruffle/<file>，加载器只会请求
// ruffle.js 里固化的哈希名）；白名单之外的文件不可能被请求，
// 打包期直接剔除，仓库无需手工清理。
// ============================================================
val ruffleAssetWhitelist = setOf(
    "ruffle.js",
    "simhei.ttf",
    "1ef41ff58c9763bed027.wasm",
    "63468f5322aed2e768a8.wasm",
    "core.ruffle.0875e44536e955474b0c.js",
    "core.ruffle.831c4f4a93befb9e84af.js"
)
val filteredAssetsDir = layout.buildDirectory.dir("filteredAssets")
val filterRuffleAssets = tasks.register<Sync>("filterRuffleAssets") {
    description = "同步 src/main/assets 到过滤目录，ruffle/ 只保留白名单文件"
    into(filteredAssetsDir)
    from("src/main/assets") {
        exclude("ruffle/*")
    }
    from("src/main/assets/ruffle") {
        into("ruffle")
        include(ruffleAssetWhitelist)
    }
}
// 保险丝：preBuild / merge*Assets 先行触发同步（双锚点保证过滤目录在
// 资源合并前就绪，不依赖单一任务链假设）
tasks.matching {
    it.name == "preBuild" ||
        (it.name.startsWith("merge") && it.name.endsWith("Assets"))
}.configureEach { dependsOn(filterRuffleAssets) }

// ============================================================
// v2.22.1：anwind-reprefix 可执行构建（打包为 lib*.so 形式）
// ============================================================
// 背景：APK 的 native 库目录只收 lib/<abi>/*.so，而 anwind-reprefix
// 是可执行程序；ndk-build 的 BUILD_EXECUTABLE 又禁止模块文件名带
// .so 扩展名（"LOCAL_MODULE_FILENAME must not contain a file
// extension"，见 Android.mk 内说明），无法在 mk 内完成伪装。
// 方案：Gradle 直接调用同一 NDK 的 clang 包装器（与 ndkBuild 完全
// 同源的工具链：Bionic libc、API 24），逐 ABI 编译
// src/main/cpp/termux/anwind_reprefix.c，产出
//   build/reprefix/<abi>/libanwind_reprefix.so
// 该目录加入 jniLibs sourceSets 后与普通 so 一样进 APK 的
// lib/<abi>/；stripReleaseDebugSymbols 对可执行 ELF 同样只是去符号，
// 不影响其可执行性。安装期由 TermuxBootstrapInstaller 从
// nativeLibraryDir 拷贝到 $PREFIX/bin/anwind-reprefix 并 chmod 0700。
// ============================================================
val reprefixAbis = mapOf(
    // AGP ABI 名 → NDK clang 目标三元组（+ minSdk API 级别）
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7a-linux-androideabi",
    "x86" to "i686-linux-android",
    "x86_64" to "x86_64-linux-android"
)
val reprefixApiLevel = 24
val reprefixSource = file("src/main/cpp/termux/anwind_reprefix.c")
val reprefixOutDir = layout.buildDirectory.dir("reprefix")

afterEvaluate {
    // NDK 目录：afterEvaluate 时 AGP 已按 ndkVersion 解析好
    //（CI 由 workflow 预装 26.3.11579264，与 externalNativeBuild 同一实例）
    val ndkDir = android.ndkDirectory
    val osName = System.getProperty("os.name").lowercase()
    val reprefixHostTag = when {
        osName.contains("windows") -> "windows-x86_64"
        osName.contains("mac") || osName.contains("darwin") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
    val reprefixClangBin = File(ndkDir, "toolchains/llvm/prebuilt/$reprefixHostTag/bin")

    val reprefixAll = tasks.register("buildReprefixExecutables") {
        group = "build"
        description = "编译 anwind-reprefix 可执行（4 ABI，产出 libanwind_reprefix.so 进 jniLibs）"
    }

    reprefixAbis.forEach { (abi, triple) ->
        val abiTaskName = "buildReprefix" + abi.split("-").joinToString("") { p ->
            p.replaceFirstChar { it.uppercase() }
        } // 例：buildReprefixArm64V8a
        val clang = File(reprefixClangBin, "$triple$reprefixApiLevel-clang")
        val outDir = reprefixOutDir.get().dir(abi).asFile
        val outFile = File(outDir, "libanwind_reprefix.so")
        val abiTask = tasks.register<Exec>(abiTaskName) {
            group = "build"
            inputs.file(reprefixSource)
            outputs.file(outFile)
            doFirst {
                outDir.mkdirs()
                if (!clang.exists()) {
                    throw GradleException("找不到 NDK clang 包装器：${clang.absolutePath}（请确认 NDK $ndkDir 已安装）")
                }
            }
            commandLine(
                clang.absolutePath,
                "-O2", "-Wall", "-Wextra",
                "-o", outFile.absolutePath,
                reprefixSource.absolutePath
            )
        }
        // TaskProvider 本身无 dependsOn 方法（上一轮 CI 编译错误根因），
        // 必须经 configure{} 在其 Task 对象上声明依赖；abiTask 以
        // TaskProvider 传入即可（Task.dependsOn 会自动解包）
        reprefixAll.configure { dependsOn(abiTask) }
    }

    // jniLibs 纳入 build/reprefix（其下 <abi>/libanwind_reprefix.so 布局
    // 会被 AGP 按标准 jniLibs 结构识别并打进 APK lib/<abi>/）
    android.sourceSets.getByName("main") {
        jniLibs.srcDir(reprefixOutDir)
    }
    // 锚点：preBuild 先行触发（保证所有 merge 任务之前 so 已就绪，
    // 与上方 ruffle 过滤的双锚点模式一致）
    tasks.named("preBuild") { dependsOn(reprefixAll) }
    tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLib") }
        .configureEach { dependsOn(reprefixAll) }
}

android {
    namespace = "com.anwind"
    compileSdk = 34

    // ============================================================
    // NDK：Termux 移植的 PTY 原生库（libtermux.so）
    //（terminal-emulator jni/termux.c + AnWind FIFO 桥）
    // ============================================================
    ndkVersion = "26.3.11579264"

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/cpp/termux/Android.mk")
        }
    }

    defaultConfig {
        applicationId = "com.anwind"
        minSdk = 24

        // ============================================================
        // ⚠⚠⚠ v2.22 Termux 移植关键约束：targetSdk 必须锁在 28 ⚠⚠⚠
        // ============================================================
        // Android 10+ 的 SELinux 策略禁止 targetSdk≥29 的应用 exec()
        // 自己数据目录里的二进制文件（W^X 限制）。Termux 环境的全部
        // 原生程序（bash/apt/pkg 及 pkg 安装的一切）都位于
        // /data/data/com.anwind/files/usr —— 只在 targetSdk≤28 时可执行。
        // 官方 Termux 也因此自 2019 年起一直锁定 targetSdk 28。
        //
        // 对 AnWind 现有功能的影响：全部兼容 ——
        // - SAF（本地 HTML/文件选择）不依赖 targetSdk
        // - Room/DataStore/Compose/WebView/Launcher 不受影响
        // - 已声明 requestLegacyExternalStorage + MANAGE_EXTERNAL_STORAGE
        // - Android 13+ 通知权限：targetSdk<33 的应用首次建渠道时系统
        //   自动弹授权（行为略有差异但可用）
        // 唯一代价：Android 10+ 安装时提示“此应用为旧版 Android 打造”。
        // ============================================================
        // v2.22.2 内置 X11 桌面（版本号随功能递增）
        // v2.24.0：桌面窗口引擎 —— 启动级联重构（MIUI/HyperOS 专有通道加速
        // + AOSP freeform 修正键名 + 卡全屏 CLEAR_TASK 修复 + 隐藏 API 豁免
        // + 设置→桌面窗口 诊断中心），见 FreeformCompat.kt / AndroidApps.kt /
        // WindowEnginePage.kt 的 v2.24.0 注释与 docs/DESKTOP_WINDOW.md
        targetSdk = 28
        // v2.38.1（源码包 v3.1.5）：APK assets 内置 anwind-wine 同步
        // v3.1.4 启动器（WINEFSYNC/WINEESYNC 守卫 + BOX64_LD_LIBRARY_PATH
        // x86_64-bionic 闭包 + HODLL 默认）——此前 assets 仍是 v3.1.3 版，
        // 每次启动覆盖部署会把 rootfs 侧新版启动器打回旧版，导致设备上
        // 前缀创建三大修复全部失效。
        // v2.38.3（源码包 v3.2.0）：六诉求修复轮 —— ①容器三点菜单紧凑化
        // ②窗口点击置前（v3.1.7 机制随本版首次发到设备——此前 v3.1.7
        // 改动未升版号，用户手里的 2.38.2 实为 v3.1.6 构建没有该修复）
        // ③桌面图标 Windows 式自由拖放 ④wineboot 包 explorer 虚拟桌面
        // （mono/gecko 提示框可见可点）⑤proton x86_64 闭包缺失诊断 +
        // esync Android 守卫（构建侧）⑥wine 10.17+ loader 布局自愈
        // （bin/wine 副本→符号链接，App+脚本+构建三端同步）。
        // v2.38.5（源码包 v3.2.2）：多窗口点击切换 View 层兜底修复 ——
        // 用户视频实锤 Compose 桌面内置窗口（浏览器/媒体播放器/扫雷）
        // 点击后面的窗口从不置前也无法操作（v3.1.7 Compose 观察器在
        // 设备上未兑现、v3.2.1 X11WindowOps 修错了层）。新增
        // WindowFocusHook：MainActivity.dispatchTouchEvent（触摸模式，
        // TrackpadRouter 之前）+ TrackpadController/TrackpadRouter（触控板
        // 模式指针处与手指轻点处）按窗口几何直判置前；WindowChrome/
        // WinDialogFrame onGloballyPositioned 上报几何；focus 加诊断日志。
        versionCode = 80
        versionName = "2.38.17"
        // v2.38.16（源码包 v3.2.17）：bionic x86_64 wine 前缀构建修复 ——
        // box64 打通后 wineboot -i 于 ntdll 早期初始化 SIGSEGV（退出码 139）。
        // 根因：PERFORMANCE 预设注入 BOX64_DYNAREC_SAFEFLAGS=0，上游 box64
        //   USAGE.md 语义 0 = "Treat CALL/RET as if it never needs any flags"
        //   —— wine 加载器/nttdll 大量依赖 CALL/RET 后条件分支，flags 不保存
        //   → jcc 拿陈旧标志走错分支 → 寄存器装入未重定位裸值 → 访问 0x928e8
        //   类未映射小地址（崩溃现场 RAX=0x3f000928d8 与 R15=0x928d8 低 32 位
        //   一致 = 高位丢失，flags 错乱典型后遗症）。修复：
        //   ① PERFORMANCE 预设 SAFEFLAGS 0→1（上游默认值，Winlator wine 组合
        //      实证底线：BIGBLOCK=1/FASTNAN=1/FASTROUND=1/SAFEFLAGS=1/
        //      STRONGMEM=0）；
        //   ② wineboot 前缀构建会话强制 SAFEFLAGS=2（"All CALL/RET will need
        //      flags. All edge cases are also handled"）—— 关键一次性路径取
        //      最稳档，构建期分钟级性能不敏感；用户容器 env=（applyExtraEnv
        //      链路最后）仍可覆盖。
        // v2.38.15（源码包 v3.2.16）：编译错误修复轮 —— v3.2.15 的 Kotlin
        // 源有 5 处编译不过（compileReleaseKotlin FAILED，用户 CI 实报）：
        //   ①ContainerScreens.kt:951 weight 非 scope 调用 → ContainerConfigPanel
        //     增加 modifier 参数，由宽屏 Row 调用处传入 Modifier.weight(1f)；
        //   ②ContainerScreens.kt:1139/1146 WinOptionPills.options 传 List<String>
        //     而签名要求 List<Pair<value,label>> → dsound/dshow 加载序改 Pair；
        //   ③WineEnvironment.kt:400 Kotlin 赋值不是表达式，start = end = x 链式
        //     赋值非法 → 拆为两条语句；
        //   ④MusicPlayerApp.kt:137 musicSettings 声明在使用之后（Unresolved）→
        //     设置中心块上移到搜索状态之前；
        //   ⑤MusicPlayerApp.kt:413/416 Color.luminance() 缺
        //     androidx.compose.ui.graphics.luminance import → 补齐。
        // ⑥box64 冒烟测试：x86_64 宿主上错开 -DARM_DYNAREC=ON，dynarec_native.c
        //   生成 aarch64 汇编（mrs ctr_el0/dc cvau/ic ivau/isb sy）被 x86_64
        //   汇编器拒绝（CI 实报 Assembler messages: no such instruction）→
        //   冒烟构建改 -DARM_DYNAREC=OFF（ARM dynarec 仅 aarch64 宿主可编，
        //   冒烟只验源码级编译/链接/实跑链路，aarch64 正式构建不受影响）。
        // v2.38.14（源码包 v3.2.15）：六诉求轮。
        // ①box64 bionic 构建：v3.2.14 的符号校验（产物应含 shmget/shmat/
        //   shmctl）是错误假设 —— 上游 v0.4.4 源码实证：wrappedandroidshmem
        //   为纯 GO 型运行时 dlsym 映射（非 STATICBUILD 下符号表不存这些
        //   符号），CI 实报"符号表缺 shmget"必挂而产物正常；改为「编译单元
        //   在链（wrappedandroidshmem.c.o）+ 二进制含 libandroid-shmem.so
        //   注册字符串」双证据校验（日志实锤两条全过）。
        // ②启动动画去倍速：按视频原始时长原速播放完整（v3.2.14 是 3 秒
        //   加速），看门狗随真实时长自适应（+1.2s 缓冲），无回调 12s 兜底。
        // ③assets tar.gz/tar.xz 打包兼容：aapt2 实验证明其不改名（三格式
        //   原样保留），改名发生在下游处理链；资产扫描器补 .tar.xz→XZ 与
        //   纯 .tar→PLAIN 识别（内容仍完整可解，不再因后缀被剥而跳过注入），
        //   TarCompressorUtils 新增 PLAIN 类型。
        // ④容器管理按 wineguanli.py 重制：宽屏左右分栏（左容器列表 + 右
        //   五选项卡：基本/CPU核心/函数库/图形与Wine/高级），container.conf
        //   新增 frame/cores/dsound/dshow/dpi/winver/mangohud/flags/turnip/
        //   zink/wined3d 键；函数库设置（dsound/quartz DLL 加载序 + LogPixels
        //   DPI + Windows 版本三件套）启动链自动 regedit 落地 + 立即应用；
        //   帧率限制/Unity/MangoHud/核心集环境注入；/opt 对接清单见
        //   docs/WINEGUANLI-OPT-FOLDERS.md。
        // ⑤云音乐按 AnWindMusic 独立版回移：双搜索音源（酷我/简音 Meting
        //   聚合，netease→kugou 回落）、本地真实元数据与内嵌封面、黑胶
        //   唱片机歌词界面、主页/歌词页背景视频、非高亮歌词颜色、背景明暗
        //   自适应配色。
        // ⑥X11 窗口方向改跟随桌面（不再直连系统传感器）；桌面默认横屏。
        // v2.38.13（源码包 v3.2.14）：①wine9.7 arm64ec faudio SIMD 修复
        //   （0007f：EC 下走 NEON 不再 include emmintrin.h）+ box64 冒烟
        //   校验误报修正 + 框架 install() fail-fast；②start.mp4 启动动画
        //   （3 秒加速播放，设置→个性化 可关闭/自定义）；③DXVK 资产机制
        //   （assets/dxvk/dxvk-<ver>.tar.gz 前缀构建时自动注入 C:\windows，
        //   容器创建/编辑「图形与 Wine」加 DXVK 开关）+ DllOverrides 注册表
        //   注入（147 项）。
        // v2.38.12（源码包 v3.2.13）：五诉求产品轮（UI 重做 + 运行链路完善）。
        // ①容器/X11 界面重做：新建/编辑容器改分区属性表（WinSectionCard
        //   分区卡 + WinOptionPills 药丸组，替代单行横滚 FilterChip）；
        //   容器卡片改图标+信息徽章网格（后端/显示/渲染/wine/音频/图形API），
        //   低频操作收进 ⋯ 菜单；X11 设置面板 340→420dp 全面重排（状态卡 +
        //   分区卡 + 分段控件），三窗口同一视觉语言。
        // ②「运行 exe」窗口新增"浏览…"按钮：拉起文件资源管理器（exe 选择
        //   模式，直达 Download），点选 exe/bat/msi/cmd/com 后完整路径经
        //   FilePickBus 回填程序路径输入框。
        // ③文件管理器（此电脑）双击 exe/bat/msi/cmd/com：新增「选择容器
        //   运行」窗口（exe_run_chooser）—— 容器单选列表 + 分辨率/显示
        //   模式药丸组 + 启动参数，会话级覆盖（不回写 container.conf）；
        //   旧版落到系统 VIEW Intent 必然失败。
        // ④画面抖动修复（所有游戏）：撤销 aarch64 无条件 TU_DEBUG=sysmem
        //   （强制系统内存访存 = GPU 带宽腰斩，滚屏/移动持续掉帧的直接
        //   元凶；zink 档仅保留 noconform）；softpipe 兜底补 vblank_mode=0
        //   （软渲染帧率低于刷新率时等 vblank 引入随机节奏抖动）；LorieView
        //   Surface 钉帧率到显示刷新率（API 30+，引导 SF 合成调度）。
        // ⑤bionic box64 构建修复（用户实测启动零日志无法启动）：
        //   a.配方强制 wrapper 再生成 —— box64 仓库提交的是无 ANDROID 特性
        //     位的预生成 wrapper，git clone 后 mtime 全"现在"，make 按写入
        //     顺序决定是否跳过再生成 → wrapper 表可能缺 Android shm 系列
        //     （wine X11/unix 侧大量依赖）→ 加载即死零输出；删除 OUTPUT
        //     文件强制必跑，install() 三道冒烟（functions_list 含 shmget /
        //     符号表含 shmget+getDB / 链接无 --image-base）。
        //   b.NOLOADADDR=ON 对齐 Winlator/MiceWine 两条已验证 Android 部署
        //     链（Android linker64 对 PIE 不遵守 image-base，桌面低地址钉死
        //     在应用进程布局上无收益有风险）。
        //   c.CI 加源码级冒烟测试：同 ref 独立 clone + 宿主 x86_64 构建 +
        //     实跑 box64 --version + x86_64 测试程序（含 SysV shm 路径），
        //     挡住"能链接出来但根本跑不了"的坏产物。
        //   d.App 侧 BOX64_LOG 0→1（错误可见；旧值+NOBANNER=1 把加载失败
        //     整体静音 = "没有任何日志"）+ BOX64_ENVFILE 指到 rootfs 的
        //     box64rc（上游只读 /etc 或 com.termux 路径，rootfs 内 rc 一直
        //     是死文件）。
        //   e.体检（doctor）box64 改运行探活：真实 exec box64 --version
        //     （5 秒限时），退出码+输出直接进体检详情，"无法启动"从猜测
        //     变成可截图反馈的证据；体检整体移 IO 线程（含加载态）。
        //
        // v2.38.11（源码包 v3.2.12）：双阻断修复轮。
        // ①APK 编译失败（CI compileReleaseKotlin FAILED，用户日志逐字实证）：
        //   WineRegistry.kt findModernTheme 四处 `listFiles()?.orEmpty().firstOrNull`
        //   —— `?.` 后整条链仍为可空类型，后面裸调 .firstOrNull 违反空安全
        //   （268/270/274/276 行 4 处）。orEmpty 本身就是可空扩展函数，
        //   去掉 `?.` 直接调用即非空：`listFiles().orEmpty().firstOrNull`。
        //   v3.2.11 的 APK 因此从未编译通过 —— 本轮为首个可安装版本。
        // ②official-9.7 arm64ec 构建阻断（CI wine-build-log-arm64ec (9).zip
        //   实证：dlls/wbemprox/arm64ec-windows/builtin.o "__cpuid 隐式声明"）：
        //   新增 0007e 补丁双修复 —— wbemprox do_cpuid 与 mshtml
        //   DEFINE_WRAPPER_FUNC 均为裸 __x86_64__ 分派无 EC 排除（llvm-mingw
        //   arm64ec 趟 __x86_64__/__arm64ec__ 同定义，clang 21
        //   AArch64Targets.cpp 实证），EC 趟落 #else 的 C 存根/FIXME 桩（与
        //   原生 ARM 趟行为一致）。mshtml ifacewrap 为修复 wbemprox 后的下一个
        //   必炸点（llvm-mingw-20251202 本地逐文件复现 21 errors；wine-9.2
        //   两文件形态相同一并受益）；全树 30 个 EC 趟高危对象逐一编译复测
        //   无回归，build.sh 加 0007e 生效校验（失败即 1 秒内报根因）。
        // ③i386 编译趟阻断（本地 llvm-mingw 实证；此前被 ② 挡在 arm64ec
        //   趟未暴露）：0007 的 __fastfail hunk（v3.2.4 起）重排分支时把
        //   x86 分支体误留为 ARM64 代码（x0/brk，丢失 int $0x29）→ i386
        //   趟真实调用者（wbemprox/mshtml/ntdll/wined3d）必炸 "unknown
        //   register name 'x0' in asm"。修复内联于 0007 本体（x86 分支
        //   恢复 int $0x29，与 wine-11.16 上游形态逐字一致；不能追加到
        //   0007e —— CI locale 排序会把 0007e 排在 0007 前应用，追加
        //   hunk 上下文永不命中），build.sh 加 mangled 形态校验。
        //   official-9.2/9.7 与 proton_10.0 树同受保护（0007 winnt.h
        //   hunk 实测命中三树；hangover-11.16 原生正确、hunk 不命中）。
        // v2.38.10（源码包 v3.2.11）：三诉求修复轮。
        // ①容器误删根治（用户实测“点三个点删一个容器把所有容器删了”）：
        //   唯一能一次删光的向量是 name 为空时 File(CONTAINERS_ROOT, "")
        //   解析为容器根本身 → deleteRecursively() 连根删光。三层防御：
        //   ContainerData.fromConf 改为目录名优先（身份=目录，conf 内 name= 仅为
        //   冗余字段，空值/漂移不再产生空名/重名容器，重名还曾崩 LazyColumn
        //   key 断言）；ContainerManager.remove 拒空名/分隔符/点路径并校验
        //   目标必须是容器根的直接子目录（canonical 双保险）；CLI
        //   cmd_remove 同款加固（旧版 rm -rf "$CONT_ROOT/" 同样能删光）。
        // ②wine 构建双修复（CI 日志 wine-build-log-arm64ec (6)/(7).zip）：
        //   official-9.7 唯一阵亡点 rpcrt4/arm64ec-windows/ndr_stubless.o
        //   （call_server_func 的 x64 内联汇在 EC 趟误激活，9.7 树该函数
        //   四分支无 EC 排除；其余四处入口 9.7 已自带 __arm64ec__ 分支）
        //   → 新增 0007d 补丁（锚点为 9.7 四参签名，与 0007（9.2 三参）/
        //   wine-10+（迁 thunks.c）天然互斥，双向应用顺序均验证）；
        //   proton_10.0 唯一阵亡点 wow64/process.c:1095
        //   “call to undeclared function 'RtlWow64SuspendThread'”（0011 改了
        //   调用点/spec 导出/signal 定义却漏了声明，wow64 是独立 PE dll 且
        //   wine-9.x/10 无公共头声明）→ 0011 process.c hunk 内就位 extern，
        //   proton-10 与 official-9.7 同修，wine-11/hangover 树因 hunk 不命中
        //   天然互斥。字节级校验补丁（TAB 上下文/hunk 计数）防编辑工具污染。
        // ③wine 窗口现代化（WineRegistry.ensureModernLook，启动链 EmulateModeset
        //   后接入，单次 regedit 导入 + 前缀内幂等标记）：ClearType 字体平滑
        //   （wine 默认全关，文本锯齿）；拖动显示窗口内容（默认只画虚线框）；
        //   light.msstyles 扁平现代主题（大小写不敏感探测，aero 兑底，无则
        //   跳过）；Windows 10/11 强调色选区/热跟踪；程序化生成 1600×900
        //   深空蓝→湖蓝渐变+右上辉光 BMP 壁纸（纯像素数学无外部依赖，
        //   视觉评审无色带无瑕疵）。作用域：wine 自绘窗口（wineboot/winecfg/
        //   regedit/公共对话框/老 Win32 控件）；游戏自绘皮肤不受影响。
        // v2.38.7（源码包 v3.2.5）：box64 glibc 形态切换 + aarch64 glibc
        // 运行库补齐 —— x86_64 形态 wine 前缀构建秒退(139)的根治。
        // [根因]（用户诊断 + 构建链核对）：bionic 形态 box64（-DANDROID=1，
        // NDK clang）冒烟可过（wine --version 轻路径）但真实 wine 负载
        // （explorer ... wineboot -i）SIGSEGV；而 glibc 形态 box64（Winlator/
        // MiceWine 同款）所需的 ld-linux-aarch64.so.1 从未构建过——rootfs
        // 只有 glibc-x86_64（amd64 deb，给被仿真的 x86_64 程序用），aarch64
        // 侧 glibc 加载器/运行库缺失，glibc box64 装上即 ENOENT。
        // [本版]：①新配方 bionic-rootfs/projects/glibc-aarch64（Ubuntu
        //      noble arm64 deb，ports.ubuntu.com → $prefix/lib/
        //      aarch64-linux-gnu/，含 ld-linux-aarch64.so.1）；②box64 配方
        //      切 glibc 交叉构建（aarch64-linux-gnu-gcc + WINLATOR_GLIBC/
        //      BAD_SIGNAL/ARM_DYNAREC）+ install 阶段 patchelf --set-
        //      interpreter/--set-rpath 指到 rootfs 设备绝对路径 + glibc
        //      运行库随包自带；③host-deps.sh 补 gcc-aarch64-linux-gnu/
        //      patchelf；④anwind-container 新增 install-box64（备份/校验/
        //      回滚，免重建 rootfs 升级）+ doctor 加载器体检；⑤App 启动链
        //      与 anwind-wine 双端加载器缺失前置诊断（glibc 形态判定：
        //      二进制头部含 ld-linux-aarch64.so.1 字符串）。
        // （v2.38.6/源码包 v3.2.3 记录见下，v2.38.5 及更早见 git 历史与
        //  README-FIX.md）
        // v2.38.6（源码包 v3.2.3）：proton 两形态前缀构建修复。
        // [根因]（proton_10.0 / hangover 两树源码逐行核对 + 设备日志对齐，
        // 本轮勘误了早前的单因归因）：
        //   ① Valve 树 start_main_thread 无条件调 fsync_init()（官方/
        //      hangover 树无此行）→ do_fsync() 在读 WINEFSYNC 之前裸
        //      syscall(__NR_futex_waitv,...) 探测 → Android 应用沙箱
        //      seccomp SIGSYS(159) 整进程击杀、零输出（arm64ec 设备日志
        //      死点：heap-tagging ×2 后戛然而止）；
        //   ② wine ≤10 树的 wine-preloader 重执行链在 box64 仿真下静默
        //      阵亡（x86_64 日志连 "_r_debug not found in ld.so" 都无 =
        //      死在预加载器进入前后）。注：hangover 树同样有 pre_exec
        //      （此前"上游已移除"说法有误），其可用而 proton 阵亡的
        //      差异在 ① 与 box64 形态。
        // [本版]：①App 侧 WineProbePatch：对已装包就地改写 futex_waitv
        //      探测立即数（449→getpid，x86_64/aarch64 双模式 + 上下文
        //      双校验防误伤）—— 旧包免重编即免疫 SIGSYS；②WineLayoutRepair
        //      停用 bin/*-preloader（preloader_exec 的 ENOENT 官方兕底
        //      直连 loader，可逆）+ WINELOADERNOEXEC=1 首进程跳过重执行；
        //      ③手动 wineboot 会话补冒烟测试 + 4 分钟无输出看门狗
        //      （/proc 现场 + wineserver 存活判定）；④构建侧两配方
        //      disable_preloader_android + pre_package 剔除 preloader 入口
        //      （新包建议用 v3.2.3+ CI 重编以对齐，但旧包已无需重编）。
        //      勘误：移除安慰剂变量 WINE_DISABLE_KERNEL_WRITEWATCH（全
        //      wine 源码无此名）；v3.2.0 对 "_r_debug not found in ld.so"
        //      的 esync 归因是误诊（消息出自预加载器 preloader.c:1516
        //      GDB 集成，非致命）。
        //      【v2.38.8 二次勘误】上一行勘误本身是误判：proton_10.0 /
        //      wine-10.0 的 virtual.c:3847 实测存在该变量检查，WINE_
        //      DISABLE_KERNEL_WRITEWATCH=1 已重新导出（见 v2.38.8 块）。
        // （v2.38.5/源码包 v3.2.2 记录见 git 历史与 README-FIX.md）

        // v2.38.8（源码包 v3.2.8）：arm64ec 159 根治 + official arm64ec
        // 构建修复 + X11 失焦丢画面修复。
        // [问题①根因]（proton_10.0 源码逐行核实 + 设备日志时序对齐）：
        //   wine 10 系树 virtual_init() 无条件调 kernel_writewatch_init()
        //   → 裸 syscall(__NR_userfaultfd)（aarch64 nr 374）不在 Android
        //   应用 seccomp 白名单 → SIGSYS(31) → 退出码 159 —— 死点在
        //   "bionic heap tagging disabled" 打印后、wineserver 握手前；
        //   wine --version 不走 virtual_init，故冒烟测试能过而真实
        //   wineboot 秒退（fsync 探测守卫早已生效，159 依旧的真因）。
        //   hangover-11.16 可用：上游 wine 11 已带 !defined(__ANDROID__)
        //   编译期排除。
        // [本版]：①WineEnvironment 重新导出 WINE_DISABLE_KERNEL_WRITEWATCH=1
        //      （wine 10 树自带该变量开关，旧包免重装即修）+ anwind-wine
        //      脚本同步导出；②两 wine 配方注入 userfaultfd __ANDROID__
        //      早退守卫（新包根治）；③hangover-wine 配方补 wine ≤9.x 树
        //      configure PE 探测程序的 _load_config_used（lld-link 对
        //      arm64ec 目标强制要求镜像含 load config，实测报
        //      "EC version of '_load_config_used' is missing"—— 上游
        //      wine 10 起测试程序已带该行，official-9.7 树落后；补后
        //      configure 实测 exit 0、PE_ARCHS=arm64ec,aarch64,i386）；
        //      ④X11 失焦丢画面双修：WindowHost 置前改 Modifier.zIndex 纯
        //      绘制分层（消除子节点重排引发的 SurfaceView detach→surface
        //      销毁链）+ X11Surface surface 死亡追踪与重获顶层时
        //      regenerate() 重建恢复（上游 termux-x11 onWindowFocusChanged
        //      同款机制）。
        // （v2.38.7/源码包 v3.2.5 记录见上方，v2.38.5 及更早见 git 历史与
        //  README-FIX.md）

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK：保留全部 ABI，让 APK 可装任意设备
        //（bootstrap 离线包仅含 aarch64；其他架构打开终端时会收到明确提示）
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // bootstrap 归档（assets/termux/*.zip）保持不压缩：
    // 避免二次压缩浪费构建时间，安装期拷贝更快
    // v3.2.14：mp4（启动动画 assets/anwind/boot/startup.mp4 经 openFd 播放，
    // 压缩存储的 asset openFd 会抛 FileNotFoundException）；gz/tgz/zst/xz
    // （DXVK 资产 assets/dxvk/dxvk-*.tar.gz 等，避免 aapt 二次压缩浪费）
    androidResources {
        noCompress += listOf("zip", "mp4", "gz", "tgz", "zst", "xz")
    }

    // ============================================================
    // 签名配置
    // ============================================================
    // 优先使用环境变量指定的 release keystore（CI 环境会自动生成）
    // 如果环境变量不存在（本地开发），回退到 debug 签名
    //
    // ⚠️ 重要：debug 签名的 APK 会被标记为 testOnly=true，
    //    Android 14 系统安装器会拒绝安装（必须 adb install -t）
    //    所以 CI 构建必须用 release keystore 签名
    // ============================================================
    val keystorePath = System.getenv("KEYSTORE_PATH")
    val keystorePass = System.getenv("KEYSTORE_PASS")
    val keyAlias = System.getenv("KEY_ALIAS") ?: "anwind"
    val keyPass = System.getenv("KEY_PASS")

    signingConfigs {
        create("release") {
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass ?: keystorePass
                println("✅ Using release keystore from: $keystorePath")
            } else {
                println("⚠️  No release keystore found, release APK will use debug signing (testOnly)")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 使用 release keystore 签名（CI 环境自动生成）
            // 如果没有 release keystore，回退到 debug 签名
            signingConfig = if (keystorePath != null && file(keystorePath).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets {
        getByName("main") {
            // v2.14.8：用白名单过滤目录整体替换默认 assets 源
            //（Sync 任务产出；上方 preBuild 保险丝保证合并前就绪，
            // 仓库残留旧 ruffle 文件不再进 APK）
            assets.setSrcDirs(listOf(filteredAssetsDir))
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        // 1.5.15 与 Kotlin 1.9.25 官方配对
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        // native 库解压到 nativeLibraryDir（AGP 8 默认 extractNativeLibs=false
        // 时库只留在 APK 内）：anwind-reprefix 可执行需要由安装器从
        // nativeLibraryDir 拷贝进 $PREFIX/bin 才能被 shell 调用
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // ============================================================
    // 构建修复：禁用 ExpiredTargetSdkVersion lint 检查
    // ============================================================
    // 现象：gradle assembleRelease 在 :app:lintVitalRelease 阶段失败：
    //   "Google Play requires that apps target API level 33 or higher.
    //    [ExpiredTargetSdkVersion]"
    // 根因：lintVitalRelease 把 targetSdk=28 判为 fatal error。但：
    // 1) targetSdk 锁 28 是 Termux 移植的硬性约束（Android 10+ SELinux
    //    W^X 限制，见上方 defaultConfig 注释），不可上调；
    // 2) 本应用经 GitHub Actions 分发 APK，不经 Google Play 分发，
    //    ExpiredTargetSdkVersion 是 Play 上架政策检查，此处不适用。
    // ============================================================
    lint {
        disable += "ExpiredTargetSdkVersion"
        // 保险丝：其余 lint 错误同样不中断 release 构建
        //（lint 报告仍会生成在 app/build/reports/，仅不再使构建失败）
        abortOnError = false
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WebView
    implementation("androidx.webkit:webkit:1.11.0")

    // v2.22.2 内置 X11 桌面：X server 显示端（lorie 合成器 + Xwayland
    // 预编译库 libXlorie.so 全 ABI）。libXlorie.so 随本模块 jniLibs 打包，
    // 与 app 的 useLegacyPackaging=true 兼容（CmdEntryPoint 有
    // nativeLibraryDir 兜底加载逻辑，见 termux-x11/CmdEntryPoint.java）。
    implementation(project(":termux-x11"))

    // ============================================================
    // v2.22.2 fix9.7：补 :app 对 androidx.appcompat / androidx.preference 的
    // 编译期依赖（CI 实证 ：app:compileReleaseKotlin 失败）
    // ============================================================
    // fix9.6 起 :app 的 Kotlin 代码直接引用 termux-x11 的公开类：
    //   AnWindApp.kt:91          LoriePreferences.prefs = Prefs(this)
    //   X11Surface.kt:63/123/... remember { LoriePreferences.prefs }、prefs != null、
    //                            prefs.displayResolutionMode.get() 等
    //   X11WindowController.kt:175 LoriePreferences.prefs?.let { view.reloadPreferences(it) }
    // 这些类的父类（LoriePreferences extends AppCompatActivity；Prefs → PrefsProto
    // extends PreferenceDataStore）必须出现在 :app 的编译类路径上，Kotlin 才能完成
    // 父类链解析。虽然 termux-x11 侧已改为 api 导出（见其 build.gradle.kts），
    // 此处按同版本再显式声明一次，双保险防再犯：
    // ============================================================
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.preference:preference:1.1.1")

    // Document file (for local HTML access via SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Test
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
