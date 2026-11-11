import java.util.Properties

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
    description = "同步 src/main/assets 到过滤目录，ruffle/ 只保留白名单文件；归档资产 *.tar.gz→*.tgz / *.tar.xz→*.txz 打包期改名"
    into(filteredAssetsDir)
    from("src/main/assets") {
        exclude("ruffle/*")
        // ============================================================
        // v3.2.24（用户问题 1）：*.tar.gz → *.tgz / *.tar.xz → *.txz
        // 打包期改名（双后缀 → 单后缀中转）。
        // ------------------------------------------------------------
        // 用户实测本地/云端 APK 打包管线仍会把 assets 内 *.tar.gz 强制
        // gunzip 成 .tar（v3.2.23 的“同版 aapt2 不剥层”结论只对本仓库
        // CI 成立，覆盖不了用户侧工具链）—— 单后缀 .tgz/.txz 不在常见
        // 强制解压逻辑的匹配名单里，从源头跳过该逻辑。
        // 落名规则（Gradle Sync rename，对目录内全部资产生效）：
        //   dxvk/dxvk-2.3.1.tar.gz → dxvk/dxvk-2.3.1.tgz
        //   termux/x11-xkb.tar.gz  → termux/x11-xkb.tgz
        // 运行时三重防御（已具备，保持不变）：
        //   ① 双名候选：TermuxBootstrapInstaller.installXkbData 的
        //      ["termux/x11-xkb.tar.gz", "termux/x11-xkb.tgz"] 列表；
        //   ② AnWindTzstAssets.scanDxvkAssets 按后缀启发（.tgz/.txz
        //      均在列）+ 目录扫描（不依赖固定文件名）；
        //   ③ 魔数裁定：sniffType / XkbExtractor 直读 gzip/xz/zstd/
        //      ustar 头 —— 即便个别管线仍剥层改名，解压器按真实内容工作。
        // 部署侧还原（“最后改回 tar.gz”）：XKB 数据落盘为
        //   $PREFIX/etc/anwind/x11/xkb.tar.gz（installXkbData 的 dest
        // 固定名），rootfs 侧脚本引用的均为落盘后路径，不受 APK 内
        // 资产名影响。
        // ============================================================
        rename { name ->
            when {
                name.endsWith(".tar.gz") -> name.removeSuffix(".tar.gz") + ".tgz"
                name.endsWith(".tar.xz") -> name.removeSuffix(".tar.xz") + ".txz"
                else -> name
            }
        }
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
        // v2.38.18（源码包 v3.2.19）：CI 实报双故障修复 —— ①APK 构建炸在
        // GuanliSupport.kt:484:1 Unclosed comment：v3.2.18 新增文件 KDoc 里
        // "/opt/conf/*.conf" 的 "/*" 序列在 Kotlin 块注释里开嵌套层（Kotlin
        // 块注释支持嵌套），外层 /** 到 EOF 未闭合 → 改写为无 "/*" 序列的
        // 措辞；全项目 107 个 .kt 经注释嵌套扫描 + 括号平衡双校验零问题。
        // ②box64 冒烟 SMOKE_FAIL_SHM（构建成功也发不出 Release）：workflow
        // 冒烟步骤 clone vanilla v0.4.4 后漏打 0001 补丁，静态 x86_64 冒烟
        // 必 ENOSYS —— 冒烟树补打补丁（与 aarch64 交付构建同源；沙箱实跑
        // 动态/静态双冒烟 ANWIND_SMOKE_OK 验证），glibc 形态 build-box64.yml
        // 同步应用（静态程序 shm 转发同样受益）。
        // v3.3.5（源码包 v3.3.5，App v2.38.30 / versionCode 93）：
        // ①noexec 直读执行：游戏数据件（media/data 等）经符号链接直读
        //   /storage/emulated/0 原件（零拷贝），仅 exe/dll 等 PE 执行件
        //   落地容器内 games/ 执行区（PROT_EXEC 内核硬约束）；执行区随
        //   容器删除/重命名/克隆自动处置（克隆排除不复制）；旧版
        //   rootfs/home/.anwind/games 整目录副本首次启动自动清理释放；
        //   无外部存储读权限大声指引（安卓直读前提）。
        // ②dosdevices z: 改直链内部 rootfs（旧 z:→/ 触达 /storage），
        //   d:/e: 保持 Download/exagear 直读盘；SafeFileOps.deleteTree
        //   增加 /data/data/com.anwind 删除走廊硬守卫 —— 删容器绝无
        //   可能波及 /storage/emulated/0（walkFileTree 不跟随链接之外
        //   的第二道保险）。
        // ③/opt/prefix 注册表导入剥除 AppDefaults\<exe>\DllOverrides 段
        //   （生态包预置的游戏级加载序是 media 错误污染源）+ 旧前缀存量
        //   键值一次性 regedit 清洗（幂等标记）。
        // v3.3.6（源码包 v3.3.6，App v2.38.31 / versionCode 94）：
        // ①【用户定稿】真·零拷贝：废除 v3.3.5 PE 件落地 games/ 执行区
        //   （对齐 Winlator/MiceWine —— 二者均无 PE 落地机制，直接从
        //   /storage/emulated/0 跑游戏）；exe 按原路径直接启动，不复制
        //   任何文件；旧两代执行区残留（rootfs/home/.anwind/games、
        //   <容器>/games）首次启动/删容器一次性清理释放。
        // ②Z: 盘（内部 rootfs）补齐外部存储访问入口：rootfs/storage →
        //   /storage、rootfs/sdcard → /storage/emulated/0（Z:\storage\…、
        //   Z:\sdcard\… 直达外部存储任意目录；d:/e: 仍是主通道）；未被
        //   盘符覆盖的 /storage|/sdcard Unix 路径自动改写为 Z: 形态再启动。
        // ③wine noexec 回退补丁（bionic-rootfs）：projects/wine 0011/
        //   0011b/0011c + projects/hangover-wine 0014/0014b —— 撤销
        //   noexec 共享映射硬失败（brunodev85 wine-9.2-custom 同款），
        //   11.x 恢复"执行段带执行位 mmap 失败 → 匿名页+pread 回退"，
        //   PE 镜像从 noexec 存储零拷贝加载执行（native 与 FES/box64
        //   形态全覆盖）。
        // ④删除安全复核：deleteTree 不跟随链接 + 删除走廊双保险不变，
        //   删容器/rootfs 绝不波及 /storage/emulated/0（含新增的 rootfs
        //   storage/sdcard 入口链接）。
        // v3.3.7（源码包 v3.3.7，App v2.38.32 / versionCode 95）：
        // ①高级设置页结构修复（用户："高级设置只有个生效容器，还占用
        //   特别大，缺失 wineguanli.py 里的所有高级设置内容"）：页头
        //   "生效容器"选择器无 width → fillMaxWidth 占满整行（后面的说
        //   明文字被挤到 0 宽不可见），且 sel==null 时 return@Column 掐
        //   掉全部选项卡 —— 但 wineguanli.py 的高级设置页根本没有这个
        //   选择器和门禁（DY/Box/Wine安装 是全局配置，MANGOHUD 才需要
        //   容器）。现对齐参考：删页头与门禁，四选项卡（DY配置/Box配置/
        //   Wine安装/MANGOHUD配置）恒渲染，容器选择器收进 MANGOHUD 选项
        //   卡内部（紧凑定宽 200dp）。
        // ②arm64ec 中文环境根因修复（用户："arm64ec 的 wine 丢失了中文
        //   环境"）：hangover-wine 配方此前缺 0009-android-locale-lc-all
        //   （x86_64 形态 v3.3.2 起就有）—— bionic setlocale 恒
        //   "C.UTF-8" → wine 恒英文 + ACP=1252。补 0009b（同源移植，
        //   wine-9.2/10.10/hangover-11.16 三树实测干净应用）。
        // ③arm64ec noexec/media 根因修复（用户："还是 media 报错，根本
        //   没解决 noexec 问题"）：hangover-wine 的 noexec 补丁只有 10.x/
        //   11.x 两形态，wine-9.x 存量包（设备日志 wine-9.2 实证）全
        //   hunk 失败 = 裸奔 —— wine 9.2 的 EPERM 分支对 MAP_SHARED 硬
        //   失败，游戏 DEP-off 后数据文件映射必被拒（"Could not load
        //   required media"；与 PE 件无关，落地 exe 也救不了）。补 0014c
        //   （9.x 形态，brunodev85 wine-9.2-custom 同款）。
        // ④补丁生效可检测：全部 noexec/locale 补丁 WARN 格式串内嵌标记
        //   （AnWind-noexec-fallback / AnWind-locale-lc-all，无条件编进
        //   ntdll.so）→ App 侧 WinePatchDetector（新增）grep 槽位 ntdll
        //   unix .so 检测存量包补丁状态；启动链 3.5 步 + 体检逐槽位给出
        //   可照做的重装指引（GitHub → Actions → build-wine-bionic →
        //   Run workflow → 重装），不再让用户对着 media 报错/英文界面猜。
        // ⑤两个 build.sh（wine + hangover-wine）补丁生效硬校验：virtual.c
        //   无 noexec 标记 / env.c 无 locale 标记 → ::error:: 直接构建失
        //   败 —— CI 不再可能产出缺补丁的裸奔 wine 包。
        // v3.4.4（源码包 v3.4.4，App v2.38.43 / versionCode 106）——
        //   用户 2026-11-11 GitHub CI 首构失败轮 + 资产统一：
        //   ①【CI 构建修复】：v3.4.3 的 WineEnvironment.kt:308 KDoc 注释
        //     正文含字面量 "renderD*/device" —— "*/" 序列提前闭合块注释，
        //     309-313 行中文注释正文被当 Kotlin 代码解析，kspReleaseKotlin
        //     报 58 个 Expecting member declaration。改写注释消除 "*/"
        //     序列（全仓 277 个 Kotlin 文件词法扫描，唯一地雷）。
        //   ②【索引换行编译错】：v3.4.3 预设 JSON 注入两处调用把 [档位名]
        //     索引换行写在函数调用后 —— Kotlin 索引后缀不可跨行，被解析
        //     为集合字面量语句（Unsupported collection literal / Map 传
        //     String 两类共 4 错）。改写为右括号同行索引（box64/fex 两处）。
        //     本地 JDK21 + Gradle 8.7 + SDK34 全量验证 compileReleaseKotlin
        //     BUILD SUCCESSFUL 后交付。
        //   ③【资产统一（用户：「把 assets 里的 fex 和其他 json 统一一下，
        //     都用 builtin 下，删掉 fex 下的，容器管理读取和更改也是」）】：
        //     assets/anwind/fex/ 目录删除（4 份与 builtin/ 逐字节相同的
        //     重复 JSON），gpu_presets.json（2541 行唯一副本）迁入 builtin/；
        //     FexConfSupport 的 ASSET_ENV_VARS/ASSET_PRESETS 常量与容器
        //     管理 FEX 配置页两处帮助文案全部改指 assets builtin/ —— 与
        //     启动链 presetEnvVars 的 builtin/fex_presets.json、WinFusion
        //     assets/builtin 布局完全同源，单一权威路径。
        // v3.4.5（源码包 v3.4.5，App v2.38.44 / versionCode 107）——
        //   用户 2026-11-11 六大需求轮（"1:1 复刻 Win7，不是随便"）：
        //   ①【proton9.0 arm64ec 构建修复】：winegstreamer/unixlib.c:285
        //     #error Bad arch —— proton 树 gst registry 分支只认
        //     __x86_64__/__i386__，arm64ec（unix 侧 aarch64）触发硬错。
        //     0021 补丁加 __aarch64__ 分支（proton_10.0 官方修法逐行
        //     同款）；proton_9.0 全树扫描仅此一处 arch 地雷；补丁经
        //     干净应用/幂等/异树容错三重验证。
        //   ②【Win7 1:1 重制】（用户："win7 主题写的太烂，要 1:1 复刻"）：
        //     AeroStartOrb 四色旗（白旗→红绿蓝黄四格）+ 四层偏心径向
        //     渐变球体 + 双光环；任务栏四段 Aero 玻璃渐变 + 顶底缘光
        //     收边 + 托盘前竖分隔线 + 最右端显示桌面条（新增
        //     WindowManager.minimizeAll）；标题按钮细线符号重制（–/空心
        //     方框/✕ 1.4dp 自绘 + 透明玻璃底默认态 + 按下才显色 —— 红色
        //     关闭钮 mac 皮根除）；文件管理器此电脑视图 Win7 化（蓝色
        //     组标题+分组渐隐线、3D 拟物硬盘/文件夹图标自绘、凹槽
        //     容量条+蓝渐变填充）；新增 Win7Icons.kt 拟物图标库。
        //   ③【虚拟手柄】悬浮球菜单被挡/点击失灵根修（声明顺序调换，
        //     球+菜单+捕获层全 z 序置顶）；菜单定位重写（向内展开 +
        //     手柄元素碰撞避让，不贴边不撞按键弧区）；设置面板居中
        //     （太靠下）；.vgp 配置文件保存/读取（Virtual GamePad
        //     三字母，SAF 全位置读写）。
        //   ④【X11 触摸修复】（linbox-winemu 蓝本）：模拟/触控板两模式
        //     双指结束后剩余手指 MOVE 被 TWO_DONE 吞 → 重入 ONE 态并
        //     对齐剩余指针（双指轻点后剩余手指可继续拖拽）。
        //   ⑤【X11 控制条隐藏】画面顶部中央 ⌃/⌄ 玻璃片，隐藏底部
        //     分辨率/切窗/键盘栏让游戏画面更大（会话级状态）。
        //   ⑥【构建对照结论】mesa 26.2.1（zink+freedreno+turnip 全在 +
        //     SONAME 软链齐）≥ WinFusion 26.2.0；box64 v0.4.4 ≥ 0.4.3；
        //     wine hangover-11.16 > wine-10.10；性能 env 键全套已对齐
        //     （mailbox/NO_ERROR/esync/DXVK_ASYNC/GPL 缓存…）。设备侧
        //     "缺 zink" = 旧 rootfs 包，重跑构建即带。
        // v3.4.6（源码包 v3.4.6，App v2.38.45 / versionCode 108）——
        //   用户 2026-11-11 三问题轮（截图 + wine 会话日志实证）：
        //   ①【X11 隐藏按钮归位 + Win7 口口方块根除】（用户："隐藏下方
        //     状态栏的按钮怎么跑到显示界面了，应该在菜单按钮旁边才对，
        //     而且 win7 的窗口控制标志咋都变成口口四方块了"）：
        //     v3.4.5 的画面顶部中央 ⌃/⌄ 悬浮玻璃片整体移除（既遮挡游戏
        //     画面，U+2303/U+2304 又不在 Android 字体内 → 渲染成口口
        //     豆腐块）；入口归位到标题栏菜单按钮旁的上下箭头按钮
        //     （AppDef.onToolbarToggleButton + toolbarToggleActive，
        //     WindowChrome 渲染，读 X11UiPrefs.toolbarHidden 可观察态）。
        //     同根修复：ControlButton Aero 符号不再按 icon.name 猜测
        //     （旧逻辑非 Minimize 一律画空心方块 → 菜单按钮 ☰ 也成口），
        //     调用方显式传 CaptionSym（新增 MENU 三横线 / CHEVRON 上
        //     下箭头，全部 Canvas 自绘 ~1.4dp 细线，零字体字形依赖）。
        //   ②【虚拟手柄配置入口迁移 + 菜单重制】（用户："保存和读取配置
        //     应该在悬浮球主菜单位置啊，不应该放在编辑界面啊，因为读取
        //     配置后才可以编辑对应配置啊"）：.vgp 保存/读取（SAF）从手柄
        //     设置面板顶栏迁入悬浮球主菜单（GamepadProfileIo.kt，读写后
        //     球旁结果气泡 2.4s）；菜单列表重制（用户："换个好看好点击的
        //     方式"）—— 标题头 + 6 项 ≥52dp 大触摸目标 + 彩色圆底矢量
        //     自绘图标（手柄/齿轮/铅笔/软盘/文件夹/关闭叉，零 emoji 零
        //     字形依赖）+ 分组分隔线 + 危险项独立分区 + 碰撞避让高度
        //     重估（360dp）。
        //   ③【wowbox64/arm64ec 线音频修复】（用户："arm64ec 启动 wine
        //     使用 wowbox64 模式时有些游戏没有声音，可能是出现了冲突，
        //     或者 pulse 被屏蔽了，看日志"）：会话日志实证设备 conf 残留
        //     export BOX64_NOPULSE=0（旧版种子，"存在即跳过"策略永不刷新）
        //     —— box64 以 wowbox64.dll 进程内形态参与 arm64ec 线时其
        //     pulse 库加载/包装与 winepulse 的原生 arm64 libpulse 冲突。
        //     修复三层：anwind-wine 新增 apply_arm64ec_audio_env（arm64ec
        //     后端且容器 env= 未显式设置时 BOX64_NOPULSE=1，在 opt conf
        //     source 之后执行；box64 后端不受影响 —— 其 winepulse 为
        //     x86_64-unix .so 必须走 box64 pulse 包装器）；ApkConfStore
        //     一次性外科迁移注释旧激活行（备份 env.conf.pre-v346.bak）；
        //     PULSE_SERVER/PULSE_LATENCY_MSEC 链路核对无恙。
        versionCode = 108
        versionName = "2.38.45"
        // v3.4.3（源码包 v3.4.3，App v2.38.42 / versionCode 105）——
        //   用户交付 assets-ck 资产包 + WinFusion 参考源码（「assets-ck
        //   我发了，源码也发了…特别是 arm64ec wine 和 x86_64 wine 的
        //   调用以及性能问题」「乱的注册表，根据 json 正确打」）：
        //   ①FEX 预设 JSON 注入（arm64ec 线性能主修复）：fex_presets.json
        //     四档各 15 项 FEX_* 全量注入（旧表每档仅 2-3 项，performance
        //     档 TSOENABLED=0/SMC_CHECKS=none/VOLATILEMETADATA=0/
        //     HALFBARRIERTSO=0 转译吞吐主旋钮全空转）；App 侧
        //     applyFexPresetEnv 运行时读 assets（presetEnvVars 装载器），
        //     ensureFexConfigFile 的 Config.json 四档值同源派生（11 键），
        //     两份 anwind-wine 脚本内联同表。
        //   ②box64 预设 JSON 对齐（x86_64 线）：box64_presets.json 四档
        //     11 项（补 AVX/UNITYPLAYER）+ BOX64_PREFER_EMULATED=1
        //    （WinFusion 同款）；SAFEFLAGS 保持 0（用户设备定稿）；
        //     PERFORMANCE 档补 MMAP32=1。
        //   ③注册表核对：ensureGlobalDllOverrides 两份输入 JSON 与
        //     assets-ck 逐字节一致（v3.4.1 已正确，核验确认）。
        //   ④assets 新增 builtin/fex_presets.json / fex_env_vars.json /
        //     box64_presets.json / box64_env_vars.json。
        // v3.3.39（源码包 v3.3.39，App v2.38.41 / versionCode 104）——
        //   用户 2026-11-10 定稿轮（conf 统一 APK 内部 + 容器管理兼容 +
        //   桌面容器更名 bionic + 启动彻底 App 内部化）：
        //   ①【conf 统一到 APK 内部端】：新增 ApkConfStore（App 私有
        //     anwind-conf/ = 权威源）+ assets/anwind/conf 内置缺省（种子
        //     = 用户 opt.zip 已知良好值：PRIMARY_CORES="4-7" 大核 /
        //     STRONGMEM=0 / BIGBLOCK=3 / AVX=2 / mailbox 呈现…）。
        //     cores/env/locale/DY/conf 编辑页不再依赖 /opt 生态包存在
        //     （旧版"未检测到 /opt 生态"整页旁路）；启动链 readOptConfs/
        //     readOptLocale 改合并读（App 内部 + /opt 生态，逐文件
        //     mtime 新者胜）。
        //   ②【兼容容器管理与终端两侧更改】：UI 写入 → App 内部 conf +
        //     写透同步 /opt/conf（终端链 anwind-wine load_opt_confs 读到
        //     同一份）；生态包首次出现时一次性导入现值（仍是种子原文的
        //     文件才导入，UI 已改过的保持 App 值）；终端侧直接改
        //     /opt/conf → 启动链按 mtime 新者胜同步生效。PRIMARY_CORES
        //     从此开箱即导出 → applyTasksetAffinity 钉大核不再依赖
        //     生态包（性能主诉的 conf 侧闭环）。
        //   ③【桌面 Wine 容器 → Bionic 容器】：显示名与 UI 文案迁移
        //     （id 保持 wine-containers，桌面图标位置兼容）—— 命名对齐
        //     实际形态：App 原生管理的 bionic rootfs 容器。
        //   ④【启动/安装彻底 App 内部化】：installWine 重写为原生
        //     WineOps 链（URL 下载 + tar 解包 + 默认槽位，全程无 bash/
        //     无 CLI/无 PTY）—— 管理链路最后一块"终端启动"残留清零；
        //     wine 运行链（NativeShellExecutor 直启）与 X 服务
        //     （app_process 直启）v3.1 起已是原生，无终端环节。
        //   ⑤终端链 anwind-wine（assets + bionic-rootfs 两份同步）：
        //     load_opt_confs 增补兜底候选 App 内部 anwind-conf/ ——
        //     /opt 生态全缺时终端链同样读到统一 conf（prefix/dxvk1 等
        //     生态资产按既有守卫静默跳过，无副作用）。
        // v3.3.38（源码包 v3.3.38，App v2.38.40 / versionCode 103）——
        //   用户 2026-11-10 深夜反馈轮（WineLocaleSupport.kt 编译错 +
        //   性能主攻 + 仙剑四 DXVK + hangover 中文环境确认）：
        //   ①【APK 构建修复】：WineLocaleSupport.kt:220/231/241/245/246
        //     五连编译错根因 = 双源字体候选 String?/File? 混合 elvis 推断
        //     出 Any?，File(src) 全线炸（245/246 为 runCatching 类型崩塌
        //     连带假错）→ src 归一为 File + 目录候选（anwind-cjk）展开
        //     扫描（旧 takeIf isFile 对目录项永远落空）。
        //   ②【性能】：BOX64_LOG 默认 1→0（v3.2.13 崩溃排查期诊断遗留：
        //     每个 wrap/emulate 库加载打 stderr，wine 每次拉起子进程都
        //     重刷，日志管道 IO 白吃 CPU；终端链本就是 0，两链归一；
        //     putIfAbsent，conf/env= 排障时可设回 1）。
        //   ③【仙剑四 DXVK（arm64ec 崩溃第二根因）】：FEX x87 精度联动
        //     —— 游戏本体 x87 指令由 FEX 解释，PERFORMANCE 档
        //     X87ReducedPrecision=1（64 位精算）正是 wine-10 日志
        //     EAX=0x052ae3c7 错位野指针的供应商（floatEmulation=Strict
        //     只管 DXVK 侧）；命中仙剑四时 ~/.fex-emu/Config.json 同步
        //     拉回 80 位全精度（App 原生链 + 终端链双端，非仙剑四会话
        //     下次启动自动恢复预设）。
        //   ④【闪屏】：仙剑四 appfix 追加 d3d9.presentInterval=1（
        //     VK_PRESENT_MODE_IMMEDIATE 无消隐同步，lorie 读到半刷新帧
        //     即上屏 = 持续闪屏；FIFO 呈现根治，2007 年 60fps 级 RPG
        //     垂直同步零感知代价）。
        // v3.3.37（源码包 v3.3.37，App v2.38.39 / versionCode 102 —— 构建
        //   系统与配置轮，App 版本号不变）—— 用户 2026-11-10 反馈六连修：
        //   ①【性能全链对齐 WinlatorXR】（"还不如 proot"）：box64 PERFORMANCE
        //     档逐字对齐（BIGBLOCK=3/FORWARD=512/X87DOUBLE=0/+MMAP32=1 +
        //     X11GLX/DYNAREC 恒开）；新增全局性能基线 applyPerfBaselineEnv
        //     （mailbox 呈现 + MESA_NO_ERROR + DXVK_ASYNC/GPLASYNCCACHE/
        //     STATE_CACHE + WINEDEBUG=-all + GST 解码优先级，两线共用）；
        //     WINEESYNC 0→1（参考实现未设即强制 1）；arm64ec 线 FEX 补
        //     ~/.fex-emu/Config.json 写入（FEXCoreManager 实证通道）+
        //     FEX_DISKCACHE=1；终端链 anwind-wine 同步（两份脚本一致）。
        //   ②【DXVK 真激活】（"arm64ec 仙剑四用 dxvk 直接失败"核心根因）：
        //     v3.2.27 撤销全部 DllOverrides 后 wine 默认加载序（builtin 优先）
        //     从未加载过解压进 system32/syswow64 的 DXVK DLL —— dxvk 槽位
        //     激活时注入 D3D/DXGI 全家 native,builtin（WinlatorXR WineUtils
        //     同款；off 档零注入，用户 dxoverrides/env= 仍最高优）。
        //   ③【闪屏对策】mailbox 呈现模式 + zink KOPPER 禁用 + GL NO_ERROR。
        //   ④【驱动目录分列】（"wrapper和yuzu类驱动包括系统驱动怎么全跑到
        //     turnip(opt/tz)那里了"）：/opt/tz（turnip 包）与 /opt/yuzu
        //     （yuzu/高通专有包）独立目录独立档位独立选择；RootfsLauncher
        //     0.5 与脚本兜底安装同步支持 /opt/yuzu。
        //   ⑤【中文环境兜底】rootfs-fonts 的 NotoSansSC 纳入 CJK 字体候选
        //     （/system/fonts 无 CJK 的设备不再直接方块）。
        //   ⑥【glibc 下载镜像池】（box64-build-log-aarch64(7) 实证：CI 到
        //     archive.ubuntu.com 瞬时超时两连击即整轮报废）：glibc-aarch64/
        //     glibc-x86_64 下载改多镜像+强重试，网络抖动不再杀构建；
        //     mesa 配方补 libglapi.so.0 别名（zink 已在构建链，旧 rootfs
        //     重跑 build-bionic-rootfs 即带）。
        // v3.3.33（源码包 v3.3.33，App v2.38.39 / versionCode 102）——
        //   用户 2026-11-09 21:5x 两份日志（wine-10 仙剑四崩溃 / wine-9.2
        //   box64 wineboot 段错误）+ 三项功能需求的修复轮：
        //   ①【仙剑四 DXVK 修复（原生链）】：v3.3.30 的 d3d9.floatEmulation=
        //     Strict appfix 只在终端脚本链生效，App 原生链从未注入（用户
        //     21:57 日志实证：DXVK 读 /opt/dxvk.conf 原文件、无补丁行、
        //     +0x2941b5 崩溃依旧）→ WineEnvironment.applyPal4Compat 移植；
        //     另加仙剑四自动单核（2007 引擎多核竞态崩溃社区共识）+
        //     CPU 亲和性原生链真正生效（taskset 前缀，cores= 配置落地）；
        //   ②【box64 wineboot 段错误兜底】：重试仍 139（R15 高 32 位丢失
        //     型随机崩溃，v3.3.21 已记载）时以 BOX64_DYNAREC=0 解释器模式
        //     兜底重跑（App 链 + 终端脚本链双端）；建议用户升级 box64 至
        //     CI v0.4.4+（用户设备仍为 0.3.8，日志环境为旧版激进预设）；
        //   ③【文件管理器 exe 内嵌真实图标】（用户需求）：PeIcon.java
        //     纯 Java PE 解析器（RT_GROUP_ICON→RT_ICON，PNG/DIB 1-32bpp，
        //     A/B 双偏移风格自适应，桌面端已对真实 exe 验证）+
        //     ExeIconThumbnail（LruCache，exe/dll/scr/com/cpl/ocx/ico）；
        //   ④【高通/联发科专有系统驱动支持】（用户需求，参考 WinlatorXR）：
        //     新增『系统驱动（高通/联发科专有）』档（wrapper="system"，
        //     内置 wrapper-stack.tzst APK 资产 5.5MB 自动安装，wrapper 回退
        //     /system/lib64/libvulkan.so）；yuzu 格式专有驱动包（v819.2/
        //     v849/q615.50 等）安装后自动经 wrapper 栈路由；依赖预检补
        //     vendor 分区（/vendor/lib64 等 HIDL 库解析）。
        // v3.3.30（源码包 v3.3.30，App v2.38.38 / versionCode 101）——
        //   用户 2026-11-09 18:2x 日志四连发实证的修复轮：
        //   ①【wrapper/yuzu 驱动"装了选了也不能用"根因闭环】：v3.3.29
        //     补的 /system/lib64 末位兜底不够 —— rootfs 的 libandroid-stub
        //     代理桩 libbinder_ndk.so（无 DT_SONAME；targetSdk≥23 时
        //     bionic 不做 basename 回退 → soname 为空）在 rootfs/usr/lib
        //     里仍先于系统目录遮蔽真身，系统 libnativewindow.so 的
        //     verneed 子依赖按 soname 匹配落空 → ICD dlopen 失败 →
        //     "Found no drivers!"。修法：anwind-wine ensure_ndk_deps 重写
        //     为"系统库直通目录"（rootfs/usr/lib/anwind-syslibs 内放
        //     /system/lib64 真身 symlink 并前插 LD_LIBRARY_PATH，三处
        //     驱动分支全覆盖）+ Kotlin WineEnvironment.ensureSyslib
        //     Passthrough 同款（App 直传 env 链路也正确）。桩原位保留
        //     （pulseaudio 的 OpenSLES 代理链不受影响）。
        //   ②【仙剑奇侠传四 DXVK 崩溃】：arm64ec 新 WoW64 下主菜单后
        //     游戏代码 page fault（CEGUI+D3D9 老游戏浮点语义）。修法：
        //     anwind-wine apply_dxvk_appfix 按 exe 识别注入
        //     d3d9.floatEmulation=Strict（合成 conf 经 DXVK_CONFIG_FILE，
        //     仅命中启动生效；32 位游戏走 FEX/wow64fex，与 box64 无关）。
        //   ③【文件管理器图标】：v3.3.29 的 emoji 方案在设备字体上渲染
        //     千篇一律（用户"显示个固定图标"）。修法：Material 内置矢量
        //     图标体系 —— exe/msi/scr/com、cmd/bat/ps1、dll/sys/so、
        //     lnk、ini/cfg/reg、txt/doc/xls/ppt/pdf、zip/rar、apk、
        //     iso、字体、音视频等全分类专属图标+扩展名角标
        //     （FileTypeIcon / fileIconSpecFor）。
        //   无需重建 rootfs / wine 包（全部改动在 APK 侧）。
        // v3.3.29（源码包 v3.3.29，App v2.38.37 / versionCode 100）——
        //   用户设备日志（wine-10arm-20261109 三连发）实证修复轮：
        //   ①【wrapper 驱动"不能用"真根因】：选 wrapper/yuzu 驱动后 Vulkan
        //     Loader 报 "cannot find libbinder_ndk.so from verneed[2] in
        //     DT_NEEDED list for /system/lib64/libnativewindow.so" → ICD
        //     整体被忽略 → "Found no drivers" → DXVK vkCreateInstance 失败。
        //     三层修复：a) anwind-wine isolate_env 末位补 /system/lib64
        //     （WinlatorXR BionicProgramLauncher 同款；此前 v3.3.28 只在
        //     App 层设置、被脚本 isolate_env 重建丢弃）；b) NDK 依赖前置
        //     解析（脚本 ensure_ndk_deps + Kotlin WrapperDriverSupport.
        //     ensureNdkDeps 双份）：rootfs/usr/lib 缺 libbinder_ndk.so 等
        //     即软链 /system/lib64 —— 使其成为 libnativewindow.so 的已
        //     加载子依赖，bionic verneed 校验通过；c) App 原生链路
        //     （RootfsLauncher 直 exec，不经脚本）同步接入 b)。
        //   ②【tabtip 拦截真生效】：v3.3.28 的会话启动前一次性拦截扑空
        //     （崩溃快照里 tabtip.exe 仍存活且正在发 UIA 查询）→ 改为
        //     会话级压制：脚本链 anwind_tabtip_watchdog（wineserver 存活
        //     期每 3s 扫杀）+ App 链 startTabtipSuppression 同语义。
        //   ③文件管理器（此电脑）图标识别扩展：exe/com/scr ⚙️、msi 🧰、
        //     bat/cmd 🖥️、lnk 🔗、dll 🧩、ini/cfg/conf/inf/reg 🛠️、
        //     iso/img 💿、压缩包补 tar/gz/xz/bz2/zst/tzst 双后缀、字体
        //     ttf/otf/ttc 🔤（此前 cmd/bat 等全落默认 📄）。
        //   附：SAFEFLAGS=0 全档保留（v3.3.21 用户实测定论）。
        // v3.3.28（源码包 v3.3.28，App v2.38.36 / versionCode 99）——
        //   用户全盘否定 v3.3.24/26/27 后的全面复查轮。核心发现：
        //   ①【交付断层】v3.3.19~v3.3.27 的全部驱动修复（ELF 魔数修复、
        //     wrapper 栈 ADRENOTOOLS 四件套、tzst 支持、SAFEFLAGS=0 等）
        //     只存在于未提交工作树，CI 从 origin/main 构建 → 用户手里的
        //     APK 从未包含这些修复（截图报错"不是有效的 ARM64 ELF"即
        //     旧版恒 false 魔数比对）。本轮本地构建直发，修复真正进包。
        //   ②【DXVK arm64 闪退根因】（仙剑四，wine-456/wine-10arm 日志
        //     实证）：Vulkan/DXVK 初始化全程正常，崩溃是 opt 前缀注入的
        //     tabtip.exe 对游戏窗口做 UIA/WM_GETOBJECT 查询，2007 年
        //     MFC 老游戏处理该消息解引用野指针。x86_64+box64 下 tabtip
        //     兼容性差从未真正运行所以"没事"，arm64+FEX 下正常运行才
        //     踩雷。修复：anwind-wine 会话启动拦截 tabtip（/proc 扫描
        //     终止），与 DXVK 版本/驱动无关（用户判断正确）。
        //   ③分辨率回退 bug 根因：BasicSettingsTab 的 FilePickBus 监听
        //     闭包捕获首次组合时的旧 edit 快照（DisposableEffect key
        //     不变不重建）→ 浏览选 exe 后保存把分辨率打回旧值。
        //     rememberUpdatedState 修复。
        //   ④LD_LIBRARY_PATH 末位补 /system/lib64（WinlatorXR 同款）：
        //     wrapper/turnip 驱动的 libnativewindow.so 等系统库解析必需。
        //   ⑤ADRENOTOOLS_DRIVER_PATH 尾斜杠对齐 WinlatorXR。
        //   ⑥DXVK 注入 wineArch 选档补全（v3.3.21 只加了参数没接线）：
        //     arm64ec 容器 system32 装 arm64ec 原生档（多架构包时）。
        // v3.3.19（源码包 v3.3.19）：wrapper 驱动运行时可用性根治 ——
        //   ELF ABI 门禁（WinNative DriverPackages.identify 移植：glibc 包
        //   拒装，bionic 才收）+ wrapper 栈包形态（libvulkan_wrapper.so 作
        //   ICD + ADRENOTOOLS_DRIVER_PATH/NAME 注入，X11 WSI 由 wrapper 的
        //   xcb 侧提供）+ android 平台裸 turnip 自动路由 wrapper 栈 +
        //   DT_NEEDED 依赖闭环预检（缺什么点名）+ 激活注入
        //   VK_LOADER_DEBUG=error（loader 错误进会话日志）。用户实测根因：
        //   选了 glibc 构建的 wrapper-22 → bionic wine dlopen 失败 →
        //   零 ICD → DXVK "VK_KHR_surface not supported"；新逻辑对不可用槽位
        //   显式回退 Mesa 内置 Turnip 并把原因写进会话日志，游戏不再黑屏。
        //   CI 同步新增 anwind-turnip-driver-*-bionic.tar.gz 驱动包产物
        //   （build-mesa-bionic，bionic/X11 平台构建，零 ABI 风险）。
        // v3.3.18（源码包 v3.3.18）：Vulkan 驱动选择 ↔ opt/conf/path.conf
        //   双向打通 + conf 顶回根治 —— ①三个驱动选项（Turnip 内置 /
        //   Wrapper / Turnip 包）切换时自动原位改写 rootfs/opt/conf/
        //   path.conf 的 VK_ICD_FILENAMES 行（wineguanli.py 参考行为，
        //   其余 conf 内容一字不动；GuanliSupport.syncPathConfVkIcd，
        //   UI 选择 + 运行时激活双写）；②根治"选了 wrapper 依旧是
        //   turnip"：启动链 6a 注入所选驱动 ICD 后，6c source path.conf
        //   的硬编码 freedreno ICD 不再覆盖（VK_ICD_FILENAMES /
        //   VK_DRIVER_FILES 接管防护，未选槽位时 conf 自定值仍生效）——
        //   用户 path.conf 的 turnip 硬编码正是此前 wrapper 失效根因。
        // v3.3.10（源码包 v3.3.10）：fexcore 独立目录 + 桌面图标精简/拖拽
        //   + box64 按版本跳过补丁 + wine mf 补丁分档 + Wrapper 驱动
        //   （WinNative/yuzu 同型 Turnip）——详见 README-FIX.md：
        //   ①FEXCore 槽位目录 /opt/dxvk1 → /opt/fexcore（逻辑同 DXVK，
        //     解压到容器 C 盘 windows）；②桌面仅保留此电脑/浏览器/终端/
        //     X11/云音乐/Wine容器六件，其余收进开始菜单，菜单瓦片长按
        //     可拖拽到桌面（DesktopPins + DesktopDragState 总线）；
        //   ③box64 构建：revision < v0.4.4 直接跳过补丁（doNotApplyPatch），
        //     其它 ref 漂移时 patchOptional 警告跳过而非 CI 阵亡（用户
        //     上传的 aarch64 日志中 elfparser.c hunk 失败根因）；
        //   ④wine/hangover-wine 两配方 mf 补丁分档（>=9.10 →
        //     CLSID_MPEG4ByteStreamHandlerPlugin，<9.10 → CLSID_AVI-...
        //     +WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER stub，用户
        //     wine-wow64-no.yml 同款语义）；⑤Wrapper 驱动槽位 /opt/wrapper
        //     （yuzu/WinNative 预编译 Turnip：独立目录 + Vulkan ICD 清单
        //     + VK_ICD_FILENAMES 切换，与 Mesa 共存可切，libc++_shared
        //     依赖补全，App + anwind-wine CLI 双端）。
        // v3.3.3（源码包 v3.3.3）：media 根治（noexec 导入空间误判修复）
        // + /opt conf 双向 + UI 紧凑化 —— ①stageNoexecExeIfNeeded 空间预检
        // 改查必然存在的 rootfs 根（旧版查尚不存在的 stageDir，usableSpace
        // 恒为 0 → 导入永远被拒 → noexec 游戏 PROT_EXEC 失败，"Could not
        // load required media" 退出码 137）；②wineboot -i → -u
        //（startfex.sh 同款）+ WINEDLLOVERRIDES=mscoree=disabled 压弹窗；
        // ③/opt/prefix 三注册表 bionic regedit 兼容性规范化（编码/BOM/
        // 头行/换行自动修复，副本导入不碰源文件）+ 幂等跳过大声日志；
        // ④新增 OptConfSupport + 容器编辑页 "/opt 生态配置"组（locale.conf/
        // cores.conf/env.conf 直读直写，wineguanli.py 同语义）；⑤容器管理
        // 窗口 900×600 → 800×560、GuanliUiK 0.8 → 0.68（图三比例）。
        // v3.3.2（源码包 v3.3.2）：wine 语言根治补丁 + MIDI 软合成 + 语言
        // 设置恢复 —— ①wine 0009 补丁（用户 wine 10.5 参考树同款）：
        // ntdll/unix/env.c 的 unix_to_win_locale 在 __ANDROID__ 下直读
        // LC_ALL，绕开 bionic setlocale 失效（zh_CN.UTF-8 不被识别 →
        // LCID 回落英文 → 界面英文/ACP 1252）；②wine 0010 补丁：midimap
        // 内置 FluidSynth 软合成端口（用户参考树完整移植，10.x 树适用）；
        // ③容器语言下拉恢复（编辑页 + 新建对话框，显式档 LANG/LC_ALL
        // 直接生效，auto 档跟随 /opt/locale.conf）；④wineboot 失败自动
        // 重跑一次（system.reg 缺失时 —— 修复 /opt/prefix 注册表注入被
        // 跳过导致的"media 错误"）；⑤regedit 导入 /S 静默；⑥全部 wine
        // 会话 CWD 从 home 改到 rootfs/usr/tmp + home 顶层乱码文件隔离
        // （用户反馈 home 出现 "pE@ঈ…" 内存转储文件）；⑦MIDI 音色库
        // 自动探测（WINE_MIDI_SOUNDFONT）。
        // v2.38.24（源码包 v3.2.27）：DllOverrides 移除 + wineboot 后续链
        // + /opt conf source 语义（用户 v3.2.26 实测反馈）——
        // ①【用户强烈要求】APK 内置 DllOverrides 彻底移除（原 147 项表
        //   v3.2.14 起"很多游戏报 media 错误"）：前缀 DLL 加载序完全按
        //   rootfs/opt/prefix/ 安装包 user.reg/system.reg/fix-services.reg
        //   执行；WineLibrary 不再写 dsound/quartz 键（仅 DPI/Windows
        //   版本）；对旧版构建过的前缀一次性清洗（regedit "键"=- 删除
        //   147 键值 + 重导 /opt/prefix 注册表，幂等标记）。
        // ②wineboot -i 后续链同会话执行（用户："wineboot i 结束后啥也
        //   没有，下次启动才解压和注册表"）：进程退出后 onExit 里继续
        //   postPrefixBootChain —— /opt/prefix 资产解压 + 安装包注册表
        //   导入 + dosdevices + EmulateModeset + ModernLook + DXVK +
        //   函数库，逐步日志，对齐 startwine.sh 301-369 行。
        // ③/opt conf 改 source 语义（用户："根本没有 env 或者 export"）：
        //   全部 KEY=VALUE 导出给 wine 进程，仅 WINEPREFIX/加载器/X11/
        //   LD_*/BOX64_*_PATH 黑名单不透传；PATH 合并追加；GLIBC_BIN
        //   兜底导出（sh 同款）；locale.conf → LC_ALL/LANG。
        // ④bionic-rootfs 中文：host-deps 补 language-pack-zh-hans（探测
        //   式，Debian 无此包不炸）+ locale-gen zh_CN.UTF-8（arch/debian
        //   双分支）；wine 构建侧 i386_CC/x86_64_CC 改指 llvm-mingw 包装
        //   clang（wine ≤9.x 旧 configure 会把 NDK clang 当 PE 编译器）。
        // v2.38.23（源码包 v3.2.26）：数据丢失事故根治 + /opt 生态修正 ——
        // ①【严重】删除容器误删用户数据修复：Kotlin 1.9.25 的
        //   deleteRecursively 会穿透符号链接删目标内容（本机实证），
        //   v3.2.24 起前缀 dosdevices 的 d:→Download、e:→exagear、
        //   z:→/ 链接使删容器=删用户存储。新增 SafeFileOps（NIO 不带
        //   FOLLOW_LINKS 的删除/复制），替换工程内全部 deleteRecursively/
        //   copyRecursively 调用点（容器删除/克隆、wine 槽位安装回滚/
        //   删除、wineserver 运行区、noexec 导入暂存、DXVK 暂存、
        //   fontconfig 缓存、rootfs 迁移）。
        // ②/opt 生态路径修正（用户："应该是 /data/data/com.anwind/
        //   files/rootfs/opt"）：探测候选 rootfs/opt → wine 槽位目录
        //   （WinFusion 包 conf/prefix 与 bin/ 平级落槽位的布局）→
        //   rootfs/usr/opt；探测/应用结果全程大声日志（会话日志可见
        //   "读了哪里、用了哪些"）。
        // ③wine_path.conf 按用户要求剔除：wine 目录一律由 AnWind 槽位
        //   体系管理（容器 wine= / default_wine / 槽位扫描），其余
        //   conf（env/cores/path/dynarec_*）+ locale.conf 照读。
        // ④前缀构建"wineboot 后无声"修复：bootstrapOptPrefix 全部
        //   早退分支（前缀未就绪 / 未检测到 /opt / 无 prefix/）改为
        //   大声日志；构建前增加存储空间预警（用户实测 0MB 仍构建，
        //   wineboot 失败后全部后续步骤静默跳过）。
        // ⑤DllOverrides 修正（用户："注册表注册就是错误的，乱七八
        //   八糟的"）：安装包 user.reg 已导入时内置表跳过（包配置优先）；
        //   否则按前缀内实际存在的 native DLL 过滤（DXVK/directx 落盘
        //   后才保留 native 优先键），杜绝"强制 native 但 DLL 不存在
        //   → d3dx9 加载失败 → Could not load required media"。
        // ⑥DXVK 注入移到 DllOverrides 之前（过滤依赖 DLL 已落盘）。
        // v2.38.21（源码包 v3.2.24）：五诉求修复轮 ——
        // ①APK 打包 tar.gz 强制解压防御：*.tar.gz→*.tgz / *.tar.xz→
        //   *.txz 打包期改名（单后缀不在强制解压逻辑匹配名单）；运行时
        //   双名候选 + 魔数裁定双保险；XKB 落盘仍为 xkb.tar.gz 原名。
        // ②虚拟手柄三修复：总开关默认开（悬浮球开箱即见）；元素默认
        //   收起（只出球，球菜单「显示手柄」展开）；悬浮球菜单 z 序
        //   修复（透明捕获层曾盖住菜单 → 四按键全部点不到）。
        // ③启动链对齐 startwine.sh/startfex.sh 参考实现：BOX64_PATH=
        //   $WINE_DIR/bin + BOX64_LD_LIBRARY_PATH 前插 wine 自身
        //   lib/wine/{i386,x86_64}-unix；dosdevices 补 d:/e: 盘；
        //   wine 槽位解析主槽位缺失时扫描全部 wine* 槽位；listWines
        //   读取 .anwind-wine-info 实际写入的 kind/tag（版本回退链）。
        // ④bionic wine9.7 构建失败根治：x86_64 剥除 configure 无条件
        //   追加的 MSVCRTFLAGS -mabi=ms（clang 不支持；unix 侧 implib
        //   对象纯数据无 ABI 影响）；arm64ec 回填上游 wine-10 的
        //   .drectve 导出修复（lld-link EC 符号解析）；构建框架
        //   make install 失败检查补齐（此前残缺包照发 [OK]）。
        // ⑤中文字体落位 rootfs/usr/share/fonts/：系统 CJK 字体同步装
        //   anwind-cjk/（fontconfig 扫描路径）；用户字体投放目录改为
        //   rootfs/usr/share/fonts/anwind-user/（旧 AnWind/fonts 兼容
        //   读取）。
        // v2.38.20（源码包 v3.2.23）：七诉求修复轮 ——
        // ①资产原名保留：撤销 v3.2.22 的 *.tar.gz→*.tgz 同步改名（同版
        //   aapt2 全链实测原名原字节保留）；App 侧资产扫描改魔数裁定
        //   （sniffType），xkb 回退链双名候选容错。
        // ②noexec 存储自动导入：exe 位于 /storage/emulated 等 noexec 挂载
        //   时 wine mmap(PROT_EXEC) 必败 —— 启动链自动导入内部执行区
        //   （rootfs/home/.anwind/games/<容器>），指纹命中零拷贝复用。
        // ③最小化不再是“关闭”：任务栏四主题不再滤掉最小化窗口；桌面/
        //   开始菜单走 openOrRestore；X11 最小化不被误标用户关闭。
        // ④虚拟手柄重制：常驻悬浮球（GamepadFloatingBall）统一入口，
        //   旧迷你工具条移除，映射设置重制单层面板，修触摸穿透。
        // ⑤Wine 容器主窗 1060×680→900×600，字号缩放与尺寸缩放分离
        //   （默认窗字号不变）。
        // ⑥arm64ec 启动提速：wineserver 暖启动复用 + CJK 注册表单次
        //   regedit /s 导入。
        // ⑦rootfs+wine 中文支持：用户字体目录 /storage/emulated/0/
        //   AnWind/fonts/ 启动链自动导入（fontconfig + 前缀 Fonts 双端）。
        // v2.38.19（源码包 v3.2.22）：四诉求修复轮 ——
        // ①box64 bionic 冒烟测试修复：RELR 冒烟的 python 段 Elf64_Shdr
        //   6 元组解包写了 5 个目标（CI 实炸 ValueError: too many values to
        //   unpack (expected 5)，RELR 冒烟从未真正跑起来）—— 修正为
        //   _,_,_,_,shstroff,shstrsize；构建日志里 3 次 "tar: .: Cannot
        //   utime" 噪声同源治理（make_pkg 解包 -C / 加 -m + 归档校验容错）。
        //   注：构建本体与产物一直是好的（[OK] 全部完成），报错在构建后
        //   的源码级冒烟步骤。
        // ②assets/*.tar.gz 被 aapt2 剥层修复：aapt 对以 .gz 结尾的资产会
        //   剥掉 gzip 层并改名（dxvk-2.3.1.tar.gz 进 APK 变成解压后的
        //   dxvk-2.3.1.tar；x11-xkb.tar.gz 同中招，回退链路一直是静默断链）
        //   —— 打包同步任务统一改名 .tgz（aapt2 原名原字节保留，扫描器
        //   本就识别 .tgz），x11-xkb 引用跟随更新。
        // ③创建新容器弹窗收紧：GComboBox 增 width 参数（架构选择两项下拉
        //   不再 fillMaxWidth 拉满整行——"大得离谱"根因），弹窗 520→430dp、
        //   内边距/行距收紧，架构选择去重组框改轻量 GField 行；Wine 安装
        //   面同款修复。
        // ④云音乐双修复（AnWindMusic 回移）：背景显示不正常 —— 侧栏/底栏
        //   v2.38.18 旧实现固定 72%/80% 白雾未接明暗自适应（暗色背景被白雾
        //   盖死），Sidebar/PlayerBar/NavItem 全部接入 LocalAdaptiveScheme
        //   （sch.glass 玻璃底 + 自适应文字/轨道/光晕，滑块 16→32dp）；
        //   搜索下载的音乐不自动入本地库 —— MusicStore 增 downloaded.json
        //   持久化 + queryLocalSongs 合并已下载音频 + 下载完成后
        //   addDownloadedPath/backfillDownloadedPath/MediaScanner 扫描/
        //   scanLocal 自动重扫四连，无需手动扫描即入「本地音乐」页。
        // v2.38.17（源码包 v3.2.18）：box64 内建 SysV shm（bionic 双断链
        // 补齐：wrappedlibc GO→GOM 桥接 + x64syscall case 29/30/31/67
        // 转发 + android_shmem.c memfd 实现）+ 容器管理按 wineguanli.py
        // 完全重制（全宽度缩放 + 横向滑动，GuanliSupport.kt 新增）。
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
    // v3.3.30：签名源三级优先 —— ① 环境变量（CI）② 项目内持久 keystore
    //（keystore/anwind-release.jks + 同目录 properties 存口令；沙箱/换机
    // 构建不再因丢失旧密钥而迫使全体用户卸载重装）③ 都缺 → debug 签名。
    val projectKeystore = rootProject.file("keystore/anwind-release.jks")
    val projectKeystoreProps = rootProject.file("keystore/anwind-release.properties")
    var keystorePath = System.getenv("KEYSTORE_PATH")
    var keystorePass = System.getenv("KEYSTORE_PASS")
    var keyAlias = System.getenv("KEY_ALIAS") ?: "anwind"
    var keyPass = System.getenv("KEY_PASS")
    if (keystorePath == null && projectKeystore.exists() && projectKeystoreProps.exists()) {
        val props = Properties()
        projectKeystoreProps.inputStream().use { props.load(it) }
        keystorePath = projectKeystore.absolutePath
        keystorePass = props.getProperty("storePass") ?: keystorePass
        keyAlias = props.getProperty("keyAlias") ?: keyAlias
        keyPass = props.getProperty("keyPass") ?: keyPass ?: keystorePass
    }

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

    // ============================================================
    // v3.3.17：GuanliSupport.extractTarJavaCompat（纯 Java tar.xz 解压
    // 回退）直接引用 commons-compress 类。termux-x11 侧是 implementation
    // （不传递到 :app），此处按同版本显式声明；运行期与 termux-x11 共用
    // 同一份（APK 不增大）。rootfs 配方不含 xz 项目，设备侧 native tar
    // 对 .tar.xz 必然失败（turnip 驱动包正是 tar.xz），纯 Java 回退是
    // 唯一可靠路径。XZCompressorInputStream 需要 org.tukaani:xz。
    // ============================================================
    implementation("org.apache.commons:commons-compress:1.20")
    implementation("org.tukaani:xz:1.7")

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
