# AnWind Bionic 修复包 v2（2026-09-19）

> **v3.3.10（2026-11-07，App v2.38.33 / versionCode 96）**：FEXCore 独立目录 + 桌面图标精简/菜单拖拽 + box64 按版本跳过补丁 + wine mf 补丁分档 + Wrapper 驱动（WinNative/yuzu 同型 Turnip）。
>
> ## ① 【用户问题 1】"fexcore 同目录理解错了，是 opt/fexcore，逻辑和 dxvk 一样"
>
> **修正**：v3.3.9 曾把 FEXCore 的 tar 槽位放在 `/opt/dxvk1`（与 DXVK 同目录）。v3.3.10 起独立为 **`/opt/fexcore`**，管理逻辑与 DXVK 完全一致：扫描 `/opt/fexcore/*.tar.gz` → 运行时解压到对应容器 C 盘 `windows`（prefix/drive_c/windows，装 FEX 仿真器 PE DLL：libarm64ecfex.dll / libwow64fex.dll 升级版）。改动点：`RootfsLauncher` 启动链步骤 0 的槽位目录、`ContainerScreens` 图形驱动页扫描目录、`ContainerModels` 注释。
>
> ## ② 【用户问题 2】"桌面上的图标太多了，保留六件，其余收进左下角菜单；支持菜单图标自定义拖动到桌面"
>
> **桌面精简**：只保留 **此电脑 / 浏览器 / 终端 / X11 桌面 / 云音乐 / Wine 容器** 六件（`file_explorer/browser/terminal/x11/music/wine-containers`），其余九个内置应用（图片查看器/计算器/QQ游戏/时钟/媒体播放器/扫雷/记事本/设置/任务管理器）`pinnedToDesktop=false` —— 全部仍在左下角开始菜单（开始菜单本来就列全部应用）。
>
> **菜单拖拽到桌面**（Windows 11 同款交互）：
> - **开始菜单瓦片长按 ~400ms 即可拖出** → 菜单自动关闭 → 半透明放大影子跟手 → 桌面任意位置松手即固定（拖到任务栏区域 = 取消）；
> - 落位即所见：图标出现在松手位置（像素级 Free 位置持久化，与桌面图标 v5 自由摆放同一套坐标）；
> - 固定集合持久化（`SettingsStore.desktopPinnedApps`），**桌面图标右键新增"从桌面移除"**（应用回到仅菜单可见，随时拖回）、**桌面右键新增"恢复默认图标"**（一键回六件套）；
> - 实现链：`DesktopPins`（固定集合管理）+ `DesktopDragState`（跨组件拖拽总线：菜单瓦片发起 → DesktopEnvironment 全屏观察层接管跟踪 → DesktopIconGrid 落位回调）—— 菜单关闭会销毁瓦片手势协程，跟踪与松手判定由常驻的环境层完成，长按后立即松手也有正确落点（瓦片 root 坐标兜底）。
>
> ## ③ 【用户问题 3】"修复 box64 构建；0.4.4 以前的 box64 不能接受一些补丁，在选择时跳过；wine 构建添加 9.2 及 9.2-10.0 的 mf 补丁"
>
> **box64 构建修复**（用户上传的 build-box64-aarch64.log：`0001-anwind-android-shmem-native.patch` 在 `src/elfs/elfparser.c` Hunk #1 失败 → 整个 CI 阵亡）：
> - **构建框架**（`build-rootfs-arch.sh` `apply_patches`）新增项目级标志 `patchOptional=1`：补丁 dry-run 失败时**警告跳过**而非 exit 1，并记录 `patchesApplied` / `patchesSkipped`；
> - **box64 配方**：`revision` 版本解析 —— **< v0.4.4 直接 `doNotApplyPatch=1`（0.4.4 以前的版本不接受 0001 补丁，按选择跳过）**；≥ v0.4.4 正常应用 + `patchOptional=1` 兜底（master/未来版本 hunk 漂移时跳过而非阵亡）。沙箱已验证版本判断：v0.4.3/v0.3.9/v0.4.2 → 跳过；v0.4.4/v0.4.5/v0.5.0/master → 正常+兜底；
> - **install() 冒烟断言条件化**：shm 四符号/.dynsym 导出/编译单元三项断言仅在补丁实际在链时执行（源码树 `ANW_EXPORT` 特征判定，兼容源码复用分支），补丁跳过时打大字警告说明后果（shm 断链 + Android RELR 静默跳过的 wineboot SIGSEGV 风险面）；DYNAREC/NOLOADADDR 断言照常保护；
> - **CI 冒烟**（build-box64-bionic.yml）：补丁 dry-run 失败 → `::warning::` + 冒烟树跳过补丁继续构建，静态 shm 冒烟与 ANDROID RELR 冒烟（均依赖补丁）随之跳过，版本/动态实跑冒烟照常。
>
> **wine mf 补丁**（参考用户 wine-wow64-no.yml，两配方 wine + hangover-wine 同步）：
> - **< 9.10（9.2 等）**→ `fix_wine9.2_mfplat.sh` AVI 档（`CLSID_AVIByteStreamHandler`，afeimod/linbox 原脚本原行为）；
> - **≥ 9.10（9.10~10.0 及以上）**→ MPEG4 档（`CLSID_MPEG4ByteStreamHandlerPlugin`，wine_do_not_create_dxgi_manager2.patch 的等价实现）；
> - 两档共通：`resolver_create_gstreamer_handler` → `resolver_create_default_handler`（bytestream handler 回退内置，GStreamer handler 在 Android/bionic 上常缺）+ **`MFCreateDXGIDeviceManager` 开头注入 `WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER` 环境变量检查**（设非空 → E_NOTIMPL stub out —— mfplat 强建 DX manager 崩溃的标准规避）+ 补 `wine/mfinternal.h` 头；
> - 载体为**内容锚点脚本**（sed+python 按函数名/CLSID 名匹配，无行号依赖）：上游 patch 的 hunk 行号随版本漂移必失败，脚本对 9.x/10.x/11.x 任何树通吃且幂等（沙箱已实测 AVI/MPEG4 双档 + 幂等重跑）；`revision` 剥前缀解析（hangover-11.16 / official-9.2 / proton-10.0 / custom-10.18 / 裸 9.2 全验证）；
> - **生效硬校验**：`main.c` 无 `WINE_DO_NOT_CREATE_DXGI_DEVICE_MANAGER` → `::error::` 构建失败（防脚本静默落空）。
>
> ## ④ 【用户问题 4】"参考 WinNative 让 bionic 支持 wrapper 及 yuzu 同型驱动代替 Mesa（两种都要），补全依赖，加入容器管理图形驱动界面"
>
> **Wrapper 驱动槽位**（`/opt/wrapper`，WinNative DriverPackages 同款 ICD 机制）：
> - **放包**：把 yuzu 社区 / WinNative 的预编译 Turnip 驱动 tar（含 `libvulkan_*.so`；WinNative 布局的 meta.json、单顶层目录壳、自带 ICD json 均自动兼容）放进 `rootfs/opt/wrapper/`；
> - **安装**（启动链步骤 0.5 / CLI `apply_wrapper_driver`）：解压归一化到 **rootfs 独立目录 `usr/lib/anwind-wrapper/<名>/`**（不落 usr/lib —— 不覆盖 Mesa 的 libvulkan_freedreno.so，**两种驱动共存**）+ 自动生成专属 Vulkan ICD 清单 `usr/share/vulkan/icd.d/anwind-wrapper-<名>.json`（library_path 绝对指向驱动 .so）+ **依赖补全**（目录缺 `libc++_shared.so` 时从 rootfs/usr/lib 复制 —— NDK bionic 驱动普遍 DT_NEEDED 它；libc/libm/libdl/liblog/libandroid/libz 系统库免处理）；
> - **切换**：启动环境注入 `VK_ICD_FILENAMES=<ICD 清单>` —— 标准 Vulkan loader 只加载清单指向的驱动；**清除 Wrapper 槽位 = 回 rootfs 自带 Mesa Turnip**（loader 默认扫 icd.d 的 freedreno 清单）。配 Zink（GALLIUM_DRIVER=zink）用效果更佳（GL on Vulkan 吃同一个驱动）；
> - **UI**：容器编辑 → 图形驱动选项卡新增 **"Wrapper 驱动 (yuzu/WinNative 型 Turnip)"** 下拉（与 Turnip/Zink/DXVK/WineD3D/FEXCore 并列）；更多设置·环境变量页开放 `VK_ICD_FILENAMES` / `VK_DRIVER_FILES` 键（高级用户可手动覆盖）；
> - **双端**：App 启动链（`WrapperDriverSupport` 新模块）与 rootfs 终端 `anwind-wine` CLI（`apply_wrapper_driver`，ICD 未就位时自动从 /opt/wrapper 安装）都支持；会话日志全程可见（安装落点/ICD 路径/激活状态/失败回退提示）。
>
> ---

> **v3.3.9（2026-11-06）**：驱动解压落点/启动选项/分辨率虚拟桌面三组问题（详见 git log）。
>
> ---

> **v3.3.7（2026-11-06，App v2.38.32 / versionCode 95）**：高级设置页结构修复（对齐 wineguanli.py）+ arm64ec 双根因修复（locale 补丁缺失 / wine-9.x noexec 补丁形态缺失）+ 补丁生效可检测（WinePatchDetector + CI 硬校验）。
>
> ## ① 【用户问题 1】“容器管理里的高级设置只有个生效容器，还占用特别大，缺失 wineguanli.py 里的所有高级设置内容”
>
> **根因（结构级，非渲染崩溃）**：`GuanliAdvancedPage` 页头的“生效容器”选择器 `GComboBox` **没传 width → 默认 `fillMaxWidth()` 占满整行**（同行后面的说明文字被挤到 0 宽不可见 → “占用特别大”的实体）；更关键的是 `sel == null`（无容器/列表未加载）时 `return@Column` **直接掐掉全部四个选项卡** —— 而 wineguanli.py 的高级设置页（`create_advanced_widget`）**根本没有“生效容器”这个概念也没有任何门禁**：DY 配置 / Box 配置 / Wine 安装是 `/opt` 全局配置（无容器也能用），只有 MANGOHUD 需要选容器 —— 无容器时打开本页看到的就只剩那一条选择器（“只有个生效容器”）。
>
> **修复**：删页头选择器与门禁，四个选项卡（⚡DY配置 / 📦Box配置 / 🍷Wine安装 / 📊MANGOHUD配置）**恒渲染**；容器选择器收进 MANGOHUD 选项卡内部（唯一需要它的地方，紧凑定宽 200dp，跟随主页选中项也可页内切换）。DY/Box/Wine 安装在无容器状态下照常可用（与 wineguanli.py 语义一致）。
>
> ## ② 【用户问题 2】“arm64ec 的 wine 不仅丢失了中文环境”
>
> **根因（配方补丁缺失，源码级定位）**：`0009-android-locale-lc-all.patch`（bionic 下 `unix_to_win_locale` 直读 LC_ALL → zh-CN → LCID 0x0804 → 界面中文 + ACP=936）自 v3.3.2 起就打在 **`projects/wine`（x86_64 形态）**上 —— 但 **`projects/hangover-wine`（arm64ec 形态）一直没带**。bionic setlocale 恒返回 `"C.UTF-8"`（bionic 只内置 C/POSIX/C.UTF-8）→ wine 把它解析成 win_name="C" → locale.nls 查表失败 → user_lcid 落空回落英语。所以用户看到：**x86_64 wine 中文正常、arm64ec wine 丢失中文环境** —— 同一份 App 语言链，两种 wine 包的差别。
>
> **修复**：补 `projects/hangover-wine/0009b-android-locale-lc-all.patch`（与 0009 同源；`unix_to_win_locale` 函数区段在 9.2~11.16 各代树逐字一致 —— 本机对 wine-9.2 / wine-10.10 / hangover-11.16 三棵源码树实测干净应用）。
>
> ## ③ 【用户问题 2 续】“还是 midea 报错，根本没解决 noexec 问题”
>
> **根因（补丁形态缺失）**：hangover-wine 的 noexec 回退补丁此前只有 `0014（11.x 树）/ 0014b（10.x 树）`两份 —— 但设备会话日志实证用户装的 arm64ec wine 是 **wine-9.2 时代包**（“wine-9.2-20261105”会话名）。本机对 wine-9.2 源码树实测：**0014 四个 hunk、0014b 三个 hunk 全部干净失败**（9.x 的 `map_file_into_view` 是另一套结构）—— 9.x 形态一直裸奔。
>
> wine 9.2 的失败机理（与 brunodev85 的 winlator wine-9.2-custom 对照核实）：`flags = MAP_FIXED | (WRITECOPY ? MAP_PRIVATE : MAP_SHARED)` —— 一切**不带 WRITECOPY**的映射（游戏对自家 media/数据文件的只读 SECTION 映射就是这种）都是 MAP_SHARED；游戏开 DEP-off 后 `force_exec_prot` 把所有可读映射强加执行位 → noexec 上 mmap EPERM → **EPERM 分支对 MAP_SHARED 硬失败** → `virtual_map_section` 失败 → "Could not load required media"。**注意：失败的是数据文件映射，与 PE 件无关 —— 落地 exe/dll 到内部存储也救不了它**（这正是 v3.3.5 PE 落地方案对 arm64ec 无效的原因，也反证真·零拷贝 + 补丁才是对的路）。
>
> **修复**：补 `projects/hangover-wine/0014c-fix-noexec-mmap-fallback-wine9x.patch`（brunodev85 同款：删 EPERM 分支对共享映射的硬失败，落入 wine 自带的匿名 mmap + pread + mprotect 回退）。现在 0014/0014b/0014c 三份按树形态互斥命中其一，9.x/10.x/11.x 全覆盖。
>
> ## ④ 补丁生效可检测（存量包对账 + CI 防裸奔）
>
> - **标记串**：全部 noexec/locale 补丁的 WARN 格式串内嵌 `AnWind-noexec-fallback` / `AnWind-locale-lc-all`（WARN 宏把格式串**无条件编进 ntdll.so**，运行期才按调试级别过滤）；
> - **App 侧 `WinePatchDetector`（新增）**：grep wine 槽位 `lib/wine/**` 的 ntdll unix .so 检测标记，结果按槽位 mtime 缓存。**启动链 3.5 步**：arm64ec/hangover 后端 + 缺 noexec 回退 → 会话日志输出可照做的重装指引（GitHub → Actions → build-wine-bionic → Run workflow → 重装）；容器语言为中日韩 + 缺 locale 补丁 → 同样指引。**体检**逐槽位列出缺补丁的旧包；
> - **CI 硬校验**（wine + hangover-wine 两个 build.sh）：补丁应用后 grep 源码标记 —— `virtual.c` 无 noexec 标记 / `env.c` 无 locale 标记 → `::error::` 直接构建失败。**CI 从此不可能产出缺补丁的裸奔 wine 包**；
> - 生效路径：App（APK）立即生效；wine 补丁随**下一次 CI 构建 wine 包**生效（arm64ec 形态跑一次 build-wine-bionic 即可，新包含双标记，检测指引自动消失）。

> **v3.3.6（2026-11-06，App v2.38.31 / versionCode 94）**：真·零拷贝（废除 PE 落地执行区）+ Z: 盘 rootfs 补齐 /storage、/sdcard 入口 + wine noexec 回退补丁（0011/0014 系列）+ noexec 机制勘误。
>
> ## ① 【用户定稿】“真·完全零拷贝 —— Winlator 和 MiceWine 都没有这种 PE 落地机制，都是直接从 /storage/emulated/0 跑游戏”
>
> v3.3.5 的混合方案（数据件软链直读 + exe/dll PE 件落地容器 games/ 执行区）被用户否决。**本轮先做机制勘察再动代码**，全部结论经源码/实验逐项核实：
>
> - **Winlator 直跑机制（源码级）**：`WineUtils.createDosdevicesSymlinks` 建 `z:→../../../..（rootfs 根）`、`D:→/storage/emulated/0/Download`、`E:→/data/data/com.winlator/storage`，游戏经盘符路径直接启动，**全程无任何拷贝/落地机制**；其 rootfs.tzst 顶目只有 `bin/etc/home/lib/opt/tmp/usr/var`，**没有 /storage、/sdcard 入口**；
> - **Winlator 为什么跑得动 noexec（wine 源码级）**：`brunodev85/wine-9.2-custom` 对 `dlls/ntdll/unix/virtual.c` 的 `map_file_into_view` 恰有一处定制 —— **删掉 noexec EPERM 分支里共享映射的硬失败**（`if (flags & MAP_SHARED) { ERR(...); return STATUS_ACCESS_DENIED; }` 整块移除），使一切 noexec 失败落入 wine 自带的通用回退：**匿名 mmap(RW) + pread() 读入 + mprotect(按需含执行位)** —— 匿名页加执行位不受 noexec 限制（本机内核实验：noexec 挂载上文件映射 mmap(RWX)/mprotect(+EXEC) 分别 EPERM/EACCES，**匿名页 mprotect(+EXEC) 照常可执行**）。wine-10.10-custom 未改此处（box64 形态下执行位是装饰性的：CPU 不直接执行映射内代码，box64 翻译执行仅需读权限）；
> - **AnWind 补丁侧排查（用户问“是不是我的补丁破坏了 noexec”）**：`bionic-rootfs/projects/wine` 与 `projects/hangover-wine` 的全部补丁**均不触碰** `virtual.c`/loader 的 noexec/PROT_EXEC 逻辑（唯一 mmap 相关补丁是 box64 共享内存转发）—— **用户补丁无责**；此前的 noexec 失败是上游 wine 自身行为：9.x/10.x 对“共享可写 + 执行位”映射硬失败（游戏开 DEP-off（`SetProcessInformation(ProcessExecuteFlags)` → `force_exec_prot`）后所有可读映射被强加执行位，撞上 noexec 即整映射被拒 → `virtual_map_section ... failed` → "Could not load required media"）；11.x（含 hangover-11.16）则改为“先 RW 文件映射、事后 mprotect 加执行位” —— noexec 上该 mprotect 失败，原生执行的 PE 段不可执行。
>
> **修复**：
> - **Kotlin 零拷贝**（`RootfsLauncher.kt`：`prepareNoexecDirectRead` 替代 `directReadNoexecIfNeeded`，PE 镜像函数群全部删除）：exe 在外部存储时**按原路径直接启动**，不复制任何文件；保留安卓直读权限预检（大声指引「所有文件访问」授权）；**旧两代执行区残留（v3.2.23~v3.3.4 整目录导入区、v3.3.5 `<容器>/games/` 镜像区）首次启动一次性清理释放**（`SafeFileOps.deleteTree` 不跟随链接，绝不波及原件），此后不再产生；`ContainerManager` 的 remove/clone/rename 注释与语义同步（克隆仍排除遗留 games/）；
> - **wine noexec 回退补丁**（`bionic-rootfs`，构建时按树形态自动套用、`--forward` 容错）：`projects/wine/0011（9.x 形态，与 wine-9.2-custom 逐字同义）/0011b（10.x）/0011c（11.x）` + `projects/hangover-wine/0014（11.x）/0014b（10.x）` —— ① 撤销 noexec 共享映射硬失败（对齐 brunodev85）；② 11.x 恢复“执行段带执行位 mmap”（`map_image_into_view` 为 `IMAGE_SCN_MEM_EXECUTE` 段传 `VPROT_EXEC`，`map_file_into_view` 尊重之）→ noexec 上 mmap 失败自动落入匿名+pread 回退，**原生执行（arm64ec/FEX 形态）也能从 noexec 零拷贝加载执行**；③ 回退尾部共享映射拒绝软化为私有读拷贝（数据可达优先）。本机对 9.2/10.10/hangover-11.16 三形态源码实测：补丁干净应用、字母序叠加无冲突、最终代码语义正确；
> - **生效提示**：wine 补丁随**下一次 CI 构建 wine 包**生效；存量 wine 包上零拷贝对多数游戏即刻可用（FES/box64 翻译执行不依赖执行位），若仍见 `noexec filesystem?` 硬错误（wine-9.2 会话日志特征）需重装含补丁的 wine 包 —— 启动日志已内置该指引行。
>
> ## ② 【用户指令】“Z 盘（内部 rootfs）如果缺少 /storage 或者 sdcard 等访问入口，参考 Winlator 和 MiceWine 的 rootfs 正确逻辑补齐”
>
> **勘察结论**：Winlator rootfs 无此类入口（外部存储仅经 d:/e: 盘符）—— 但其 d:/e: 也因此只能覆盖 Download 与内部存储两个目录；AnWind 按用户指令**补齐**（`WineRegistry.ensureRootfsStorageEntries`，随 `ensureDosdevices` 幂等执行）：rootfs 根下建 `storage → /storage`（`Z:\storage\emulated\0\…` 与 Android 原生路径逐段一致，**可达外部存储任意目录**）与 `sdcard → /storage/emulated/0`（经典别名 `Z:\sdcard\Download\…`）两条符号链接。
>
> - **配套路径改写**（`RootfsLauncher.normalizeStorageExePath`）：wine 只能启动可换算成 dosdevices 盘符的路径 —— 游戏放在外部存储**非** Download/exagear 目录时，其 Unix 路径（如 `/storage/emulated/0/Games/a.exe`）不被任何盘符覆盖；启动链自动改写为 `Z:\storage\emulated\0\Games\a.exe` 形态（同一份文件，零拷贝；工作目录随 dosdevices 解析，media 相对路径照常可达）；
> - **删除安全（用户红线，复核）**：链接本体在 rootfs 内部 —— `SafeFileOps.deleteTree`（walkFileTree 不带 FOLLOW_LINKS）+ `/data/data/com.anwind` 删除走廊双保险，删容器/删 rootfs 只删链接本身，**绝不穿透波及 /storage/emulated/0 下任何文件**；z: 直链内部 rootfs 语义不变，外部存储主通道仍是 d:/e:。
>
> ## ③ v3.3.5 注释勘误（本轮实验核实后修正）
>
> v3.3.5 注释称“noexec 上 mmap(PROT_EXEC) 是内核硬约束、执行件必须落地内部 ext4” —— **不准确**：noexec 拦的是**文件映射**的执行位（mmap 带执行位 EPERM；先 RW 映射再 mprotect(+EXEC) 同样 EACCES），**匿名内存不受限**；wine 对失败映射有官方回退（匿名页 + pread + 再加执行位），Winlator 正是靠它（外加删共享硬失败）实现零拷贝直跑。`RootfsLauncher.isNoexecPath` 注释已按实验结论改写，相关机制说明同步修正。

> **v3.3.5（2026-11-06，App v2.38.30 / versionCode 93）**：noexec 直读执行（游戏文件零拷贝）+ z: 盘直链内部 rootfs + 删除走廊硬守卫 + AppDefaults DllOverrides 剥除。〔注：本版的 PE 件落地 games/ 执行区方案已在 v3.3.6 废除〕
>
> ## ① 【用户诉求】“运行 exe 时把游戏文件全都复制到 rootfs/home/.anwind/games/<容器> 下了，内存可受不得，直接读取游戏文件不要复制”
>
> **根因**：v3.2.23 的 `stageNoexecExeIfNeeded` 在 exe 位于 noexec 存储（/storage/emulated 的 sdcardfs 恒 noexec、无法 remount）时把**整个游戏目录**复制进内部执行区 —— 大游戏（数十 GB）直接双倍吃光内部存储；media 变更还可能触发 GB 级重拷。
>
> **修复**（`RootfsLauncher.kt` 重写为 `directReadNoexecIfNeeded`，直读执行）：
> - **数据件零拷贝直读**：media\ / data\ / 音视频等一切非 PE 文件、以及不含 PE 的整棵子目录，在执行区镜像里全部是符号链接指回 /storage/emulated/0 原件 —— wine 打开即直读（sdcardfs 的读取不受 noexec 影响，只有 PE 映像 mmap(PROT_EXEC) 不行）；游戏目录里新增 mod/补丁经根级增量补链（`reconcileMirrorRoot`）即时可见；
> - **仅 PE 执行件落地**（exe/dll/sys/drv/ocx/cpl/scr）：noexec 上无法映射可执行段是内核硬约束，执行件通常只占几十~几百 MB，GB 级数据不再进内部存储；
> - **执行区搬进容器目录**（`<容器>/games/<源目录名>`，与前缀平级）：删除/重命名容器自动处置（`clone()` 排除 games/ 不复制执行件），不再有容器外孤儿副本；**旧版导入区 rootfs/home/.anwind/games/<容器> 首次启动自动清理（删容器时也清理），立刻释放历史占用**；
> - **安卓直读权限预检**：源目录不可读时大声指引授予「所有文件访问」权限（无读权限正是很多游戏 media 错误的源头）；指纹只统计 PE 件 —— media 增删不再触发重拷，命中零拷贝秒启。
>
> ## ② 【用户诉求】“z 盘不要直链 /storage/emulated/0/，z 盘直链内部 rootfs，否则删除容器可能会删除 /storage/emulated/0 下文件；用 d e 盘直链，好像有 download 和 exagear”
>
> **修复**（`WineRegistry.ensureDosdevices` + `SafeFileOps` + `ContainerManager`）：
> - **z: → 内部 rootfs**（`/data/data/com.anwind/files/rootfs`）：旧 z:→宿主 / 经 /storage 即触达外部存储；wine 需要的 HOME、/opt、/usr 等本来就在 rootfs 内，外部存储统一走 d:/e:。已存在的旧链接（含 wineboot 默认的 z:→/）自动重指，用户自建目录不碰；
> - **d: → /storage/emulated/0/Download、e: → /storage/emulated/0/exagear** 保持直读盘（目标目录存在且盘符缺失时补建，不覆盖用户已有映射）—— z: 收回后 wine 侧访问外部存储游戏文件的直读通道；
> - **删除走廊硬守卫**：`SafeFileOps.deleteTree` 拒绝一切字面路径不在 `/data/data/com.anwind` 之内的删除目标（与 walkFileTree 不跟随符号链接叠加成双保险）—— **删容器绝无可能波及 /storage/emulated/0 下任何文件**；`remove()` 顺带清理旧版容器外导入区、`rename()` 同步搬移。
>
> ## ③ 【用户诉求】“arm64ec wine 构建时还是有 AppDefaults 的 DllOverrides 注册表打入，不应该有的”（截图：user.reg 里 s2_mp64_ship.exe / ShadowOfWar.exe / Spider-Man.exe 的 atiadlxx=builtin、TDUSC.exe / WRCG.exe 的 xinput1_3=native,builtin 等）
>
> **根因**：App 侧 v3.2.27 起 App 不写任何 DllOverrides，这些键随 WinFusion 风格生态包的 /opt/prefix/user.reg **原样导入**进前缀 —— 为个别游戏预置的加载序（atiadlxx=builtin / amd_ags_x64=disabled 等）对其它游戏是加载序污染源，错误的原生/内建选择直接诱发 media 错误。
>
> **修复**（`WineRegistry.kt` 双保险）：
> - **导入侧剥段**：`normalizeRegForBionic` 在编码/头行规范化时同步剥除 `[Software\\Wine\\AppDefaults\\<exe>\\DllOverrides]` 整段（段体直到下一节头；AppDefaults 里非 DllOverrides 的键原样保留），规范化副本导入、/opt 源文件不动；
> - **存量清洗**：`purgeAppDefaultsDllOverrides` 解析前缀 user.reg 枚举已打入的段与值名（含 `@=` 默认值），生成 `"值"=-` 删除注册表经 wine regedit /S 导入（与 v3.2.27 清洗同一通路，wineserver 一致性有保障），幂等标记 `.anwind-appdefaults-purged`；
> - 用户经容器管理「函数库」功能手动写入的 CNC/Direct3D 覆盖（显式用户动作）不受影响。

> **v3.3.4（2026-11-06，App v2.38.29 / versionCode 92）**：更多设置真正读写 /opt/conf 配置文件（MESA 变量名修正 + env.conf/cores.conf 直读直写）+ DY/Box 配置按 wineguanli.py 重制（逐行控件，不再拍平进一个框）+ 界面字号放大（框体保持紧凑）+ 容器名支持中文 + 默认语言改简体中文（跟随 /opt/locale 降为选项）+ wineboot 构建链顺序对齐 startwine.sh（先解压注册再 taskmgr；arm64ec 跳过两个 xz 预置包）。
>
> ## ① 【用户诉求】"更多设置根本没有读取和更改配置文件，怎么改都没反应"（截图：更多设置·856 环境变量页全部"默认"）
>
> **双重根因**：① MESA 两个变量名写错 —— 旧版写 `MESA_GL_VERSION` /
> `MESA_GLSL_VERSION`，Mesa 实际读的是 **`MESA_GL_VERSION_OVERRIDE` /
> `MESA_GLSL_VERSION_OVERRIDE`**（wineguanli.py get_settings 用的就是后者），
> 改了也永远没反应；② 旧版只落容器 env=，从不读写 /opt/conf/env.conf ——
> 用户生态包的配置在 env.conf 里，界面却显示"默认"。
> **修复**（`EnvVarsTab` 重写 + `MoreSettingsDialog` 三路落盘）：
> - **读取**：显示值 = 容器 env= 覆盖 > /opt/conf/env.conf 当前值（参考
>   load_current_settings 直接读 env.conf）；env.conf 的其他变量（本页不管
>   理的）也在页尾列出 —— "读取配置文件"直观可见；
> - **写回**：保存时按参考 save_advanced_settings 语义写 env.conf（非空
>   replace-or-append，选"（默认）"= 删行）；CPU 核心页同步双写
>   cores.conf（PRIMARY/SECONDARY_CORES）；**容器 env= 同名键（含旧错误
>   键名）一并清理** —— 容器 env= 优先级最高，不清理则 /opt 改动永远被压着；
> - 无 /opt 生态时自动回落合并进容器 env=（applyExtraEnv 注入），设置照样
>   生效；MANGOHUD 组对齐参考（MANGOHUD=1/0 + MANGOHUD_CONFIG + 详细配置
>   入口）。
>
> ## ② 【用户诉求】"wine容器界面字体太小，而且好多放在高级设置里的东西你全放在了一个框框里了，不好选啊，根据参考py来，比如box选项，dy配置选项等"
>
> **字体**：v3.2.21/v3.3.3 两轮整体收紧把字号也缩到 68%（13sp 标题实际渲染
> ~8.8sp）。新增 `GuanliFontBoost=1.35` 字号补偿系数 —— LocalDensity 覆盖
> 改为 `Density(density×GuanliUiK, fontScale×GuanliFontBoost)`，框体保持
> 紧凑（0.72）而字号补回设计尺寸（≈0.97）；主窗与弹窗两层同步生效。
> **DY 配置**（`DyConfigTab` 按 wineguanli DynarecEditor 逐面重制）：旧版把
> BOX64_* 拍平成容器 env= 的纯文本行（KEY = VALUE + 删除），不好选也改不到
> 点上。现改为：数据源 = **/opt/conf/custom.conf（定义表）+ dynarec_active.conf
> （当前值）**（正是启动链 readOptConfs source 的那两份）；0/1 变量渲染
> "启用"勾选、多值变量渲染候选值下拉；三预设（兼容/性能/平衡）+ 重置默认 +
> 添加/删除变量（写 custom.conf）；应用 = 整文件重写 dynarec_active.conf，并
> **自动清理各容器 env= 里的旧 BOX64_* 覆盖**（否则容器残留压着新配置，
> 又是"没反应"）。
>
> ## ③ 【用户诉求】"创建容器竟然不支持中文文本名，肯定不行啊"
>
> `ContainerManager.NAME_RE` 从 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` 放宽为
> **`[\p{L}\p{N}][\p{L}\p{N}._-]{0,63}`**（任意 Unicode 字母/数字开头，
> 含中文；路径穿越字符仍拒绝）。快捷方式 id（ShortcutManager 只认安全字符）
> 消毒为 ASCII，shortLabel/longLabel 保留中文名，桌面显示不受影响。
>
> ## ④ 【用户诉求】"默认不要跟随 opt/locale，默认简体中文即可，跟随 opt/locale 作为一个选项"
>
> `ContainerLangs.DEFAULT` 从 auto 改为 **zh_CN.UTF-8**，"跟随
> /opt/locale.conf"降为下拉可选项；`buildBaseEnv` 三档判定：显式档直接用 →
> auto 档读 /opt/locale.conf（无则兜底 zh_CN）→ 其余（缺键/非法值）一律
> zh_CN，不再隐式跟随。配套：CJK 字体安装判定改用**实际生效语言**
> （env["LANG"]）—— 修复 lang=auto + 中文生态下字体被跳过、中文全方块的问题。
>
> ## ⑤ 【用户诉求】"wineboot i 后运行 taskmgr 不是你那样啊，是先进行解压和注册再启动 taskmgr 啊，而且乱七八糟的，特别是会报无法访问的错误…arm64ec 架构的要跳过两个 xz 的解压，否则无法运行的"
>
> **顺序对齐 startwine.sh**：撤销 v3.2.29 的 taskmgr 前置保活（taskmgr 在
> 半成品前缀上与 regedit 并发争注册表 = "无法访问"报错的来源；参考链路里
> 解压/注册的每一步都是独立 wine 客户端，wineserver 按需拉起，无需保活）。
> 新顺序：wineboot -u（手动入口同步 -i→-u）→ **解压 + 注册表导入 + 盘符 +
> EmulateModeset + 现代化 + DXVK + 函数库** → **全部完成后才启动 taskmgr**
> （`startwine taskmgr` 同款，作为"容器就绪"的可视信号保持运行并登记会话，
> 可停止）。游戏会话不启动 taskmgr（游戏本身就是启动目标）。
> **arm64ec 跳过两个 xz**：`bootstrapOptPrefix` 对 AARCH64/ARM64EC 跳过
> drive_c.tar.xz / directx.tar.xz 预置解压与 install.bat（startfex.sh 事实：
> arm64 路径本就没有这两步；x86 预置包解进 arm64 前缀会无法运行），fix-fonts
> 与 dxvk、注册表三连照常。

> **v3.3.3（2026-11-05，App v2.38.28 / versionCode 91）**：media 报错根治（noexec 导入空间误判）+ wineboot -u 对齐 startfex.sh + /opt 注册表 bionic 兼容规范化 + 容器编辑页 /opt conf 直读直写 + 界面按图三（linbox）比例紧凑化（本轮日志 wine-9.2 会话 + 截图逐项定位 + wineguanli.py / startfex.sh 参考移植）。
>
> ## ① 【用户诉求】"很多还是 media 报错，你还是没清理完善，根据 2.38.16 分支作为参考继续修复"
>
> **根因**（本轮日志 wine-9.2-20261105 会话逐行核实）——**不是注册表**：
> `0130:err:virtual:map_file_into_view failed to set PROT_EXEC on file map, noexec filesystem?` →
> 游戏"Could not load required media"退出（137）。exe 在 /storage/emulated/0
> （FUSE noexec 挂载），wine 映射 PE 可执行段被拒。App 本有"noexec 游戏自动导入
> 内部执行区"机制，但日志显示 **"需约 3MB，可用 0MB"** —— 设备并未满（wine 全程
> 正常写文件）：`stageNoexecExeIfNeeded` 的空间预检查的是**尚不存在**的 stageDir，
> Java `File.usableSpace()` 对不存在路径恒返回 0 → **导入永远被拒** → 每次都按
> 原路径启动 → 必现 media 报错。
> **修复**：预检改查必然存在的 rootfs 根（`ContainerManager.ROOTFS_ROOT`）；
> 查询 ≤0（无法判定）时不再硬拒，转尽力拷贝（失败由外层兜底回退）。
> 2.38.16 参考结论：该版本无此机制（终端链路 glibc-runner 时代 exe 经 app 问题
> 不同源），本轮修复保持现架构不回退。
>
> ## ② 【用户诉求】"构建前缀不要在 wineboot -i 后就结束，要继续运行 taskmgr，startfex.sh 可参考，好像用的是 wineboot u，还有一些符号什么的"
>
> **日志真相**：taskmgr 保活其实启动了，但 113ms 后"前缀构建链完成"——
> `bootstrapOptPrefix` 的幂等标记（.anwind-opt-prefix）命中后**完全静默跳过**，
> 用户看到的"零日志秒结束"就是它（不是没跑，是跑了但一声不吭）。
> **修复**：①标记命中也大声日志（"已注入过——跳过；如需重打删标记或重建前缀"）；
> ②wineboot -i → **-u**（startfex.sh 同款：rm 前缀后 wineboot -u 安装，
> 前缀缺失时 -u 一样初始化），并附 `WINEDLLOVERRIDES="mscoree=disabled"`
> （参考同款）压掉 mono 安装器弹窗（fresh init 与 system.reg 缺失重跑两处）；
> ③dosdevices 符号链接（"还有一些符号"）v3.2.28 已含 z:/c:/d:/e:/x: 全套
> （startfex.sh 盘符矩阵），本轮核对无缺。
>
> ## ③ 【用户诉求】"opt.zip 里的两个 reg 注册表总是报错，是不是格式不适合 bionic，帮我改一下"
>
> **根因**：wine regedit 对导入文件要求头行必须是 "WINE REGISTRY Version 2" /
> "REGEDIT4" / "Windows Registry Editor Version 5.00" 之一，且编码需可识别
> （Windows 导出的 5.00 文件是 UTF-16LE+BOM；生态包手工维护的 .reg 常缺头行/
> 乱编码 → 逐键报错弹窗）。**修复**（`WineRegistry.normalizeRegForBionic`，
> 导入前自动规范化，**不改 /opt 源文件**——副本落前缀 .anwind-regtmp 再导入）：
> UTF-16LE→UTF-8、剥 UTF-8 BOM、CRLF→LF、缺头行时按首个键行反斜杠形态智能补头
> （含 `\\` 转义 → wine 原生头；否则 → 5.00 头）；每个文件的规范化动作与导入
> 退出码全部大声日志（成功也打 ✓）。生态包无需重新打包，旧包直接受益。
>
> ## ④ 【用户诉求】"容器编辑所有东西都没有读取 opt 下的相关配置，也无法更改选项来改变 opt 下的配置"
>
> **修复**（新增 `OptConfSupport` + 编辑页"⚙️ /opt 生态配置"组）：
> - **直读**：locale.conf（语言跟随档的 LANG/LC_ALL 来源）、cores.conf 的
>   PRIMARY_CORES、env.conf 全部 KEY=VALUE，进编辑页即显示当前生效值（含
>   /opt 根路径，"未检测到生态"也明说）；
> - **直写**：三处均可编辑保存 —— replace-or-append 语义与 wineguanli.py
>   逐字节同款（save_mangohud_config 的逐行替换/追加/删除），env.conf 支持
>   增改删一行保存；原子落盘（tmp+rename）；路径复用启动链 `optRoot()` 同源
>   探测 —— **改的就是启动 wine 时 source 的那份**，下次启动即生效；
> - 容器级字段（语言显式档/帧数等）优先级不变：容器显式值 > /opt/conf >
>   兜底（v3.3.2 语义）。
>
> ## ⑤ 【用户诉求】"容器编辑界面比例太大了，按照图三（linbox）这种比例重新做，包括窗口和字体"
>
> **修复**（沿用 v3.2.21 设计的全局缩放体系，三常量联动）：
> - 容器管理窗口 900×600 → **800×560**（ContainerApp.defaultWidth/Height）；
> - 字号缩放基准 GuanliFontBaseDp 900 → 800（默认窗下 fontScale=1）；
> - 全局收紧系数 **GuanliUiK 0.8 → 0.68** —— 全部控件/字体/弹窗再线性收紧
>   15%，密度对齐图三；后续微调仍只动这一个常量。
>
> **v3.3.2-r2（2026-11-05 hotfix，同日重发，App 版本不变 v2.38.27 / versionCode 90）**：修 CI 编译错 `RootfsLauncher.kt:964:63 Unresolved reference: exitValue` —— v3.3.2 新增的"wineboot 失败自动重跑"代码把 `NativeShellExecutor` 句柄当 `java.lang.Process` 调了（执行器 API 只有 `waitFor()/stopAndWait()`，没有 `exitValue()`）。修复：`NativeShellExecutor` 补 `exitValue(): Int`（语义对齐 `java.lang.Process`：进程仍在运行抛 `IllegalThreadStateException`，退出码从 exec Future 取 —— 句柄在 exec 完成时会被收尾置空，只有 Future 里存着真实退出码）。调用点零改动；全仓其余 5 处 `exitValue` 均在 `java.lang.Process` 上，不受影响。全仓 executor 方法调用比对审计：除该处外与类 API 全部匹配，无其他编译错。
>
> **v3.3.2（2026-11-05，源码包对应 App v2.38.27 / versionCode 90）**：wine 语言根治补丁（LC_ALL 直读）+ midimap 内置 FluidSynth 软合成 + 容器语言下拉恢复 + wineboot 失败自动重跑 + home 乱码文件隔离（用户本轮实测反馈 + 用户提供的 wine 10.5 参考树逐文件比对移植）。
>
> ## ① 【用户诉求】"wine 依旧无法中文环境运行，一直是英文……查看 wine 和 rootfs 的 bionic 构建是否缺少相关文件，设置 zh_CN.utf8 没反应"
>
> **根因**（wine-9.2 源码 `dlls/ntdll/unix/env.c` 逐行核实 + 设备日志交叉印证）：
> wine 的界面语言/LCID 由 ntdll `init_locale()` 决定，而它**依赖 libc 的 setlocale**：
> `setlocale(LC_ALL, "")` 在 bionic 上只认 C/POSIX/C.UTF-8 —— "zh_CN.UTF-8" 不被
> 识别 → `setlocale(LC_CTYPE, NULL)` 返回 "C" → `unix_to_win_locale("C")` 产出的
> win_name 在 locale.nls 里查不到 → system_lcid/user_lcid 落空 → 回落
> `MAKELANGID(LANG_ENGLISH, SUBLANG_DEFAULT)` → **界面英文 + ACP=1252**。
> glibc 构建的参考环境（startwine.sh）setlocale 正常返回 zh_CN.UTF-8 所以一切正常
> —— bionic rootfs 没有 locale 数据，App 端设 LANG/LC_ALL 多少次都没用。
> wine.mo（v3.2.27 起已装 ×6）只覆盖 Unix 侧消息，救不了 PE 资源语言。
>
> **修复**（新补丁 `bionic-rootfs/projects/wine/0009-android-locale-lc-all.patch`，
> 与用户提供的 wine 10.5 参考树逐字节同款 —— 参考树与上游 wine-10.5 的 env.c
> 全部差异就这 6 行）：`unix_to_win_locale()` 开头加 `__ANDROID__` 分支，环境变量
> LC_ALL 非空时直接作为 unix locale 名参与解析，绕开 bionic 失效的 setlocale ——
> LC_ALL=zh_CN.UTF-8 → "zh-CN" → locale.nls → LCID 0x0804 → PE 资源中文 + ACP=936。
> **适用性已在七棵树逐一实测**（git apply --check 全过）：official wine-9.2/9.7/10.5/11.0、
> proton_10.0、hangover-11.16/10.18 —— 项目构建的**全部树/版本通用**（函数区段各代一致）。
>
> ## ② 【用户诉求】参考 wine 10.5 修改树的 midi 音效修改，制作补丁
>
> 参考树相对上游 wine-10.5 的 midimap 改动完整移植（新补丁
> `bionic-rootfs/projects/wine/0010-midimap-fluidsynth.patch`）：
> - `dlls/midimap/synth.h / synth.c / synth_unix.c` 新增 —— 内置 "Wine Software
>   Synth" 软合成端口：unix 侧运行时 dlopen libfluidsynth（.so/.so.3/.so.2 自动探测），
>   加载 SoundFont，经 wine 音频驱动（pulse 等）出声；缺库/缺音色时 setup 失败、
>   静默回落（无内置端口，行为同未打补丁，不影响构建与启动）；
> - `midimap.c` 端口枚举追加合成端口（MIDIMAP_Port* 五个分发助手），真实设备照旧
>   走 midiOut*；`Makefile.in` 加 UNIXLIB=midimap.so / UNIX_LIBS=$(PTHREAD_LIBS) / 两个新源文件；
> - **适用树**（git apply --check 实测全过）：official wine-10.5/11.0、proton_10.0、
>   hangover-11.16/10.18 —— midimap 的 unix 库架构是 wine 10.x+ 结构，official 9.x
>   旧树该文件结构不同（hunk 失败仅告警不中断，--forward），建议 9.2 用户用 CI 另建
>   official-10.5 槽位（顺带获得 10.x 对 39 位地址空间的更好适配 —— 11-04 日志里
>   wineboot 的 `err:virtual:map_fixed_area out of memory` 正是 39 位 VA 挤占）；
> - **App 侧配套**（`WineEnvironment.applyMidiSoundfontEnv`，启动链自动执行）：扫描
>   rootfs 的 /usr/share/soundfonts、/usr/local/share/soundfonts、/usr/share/sounds/sf2、
>   /opt/soundfonts 下的 .sf2（>1MB），命中即设 `WINE_MIDI_SOUNDFONT`（补丁侧同名
>   环境变量语义）；未命中零动作（rootfs 构建若已把音色装到系统目录，补丁侧自动扫描同样生效）。
>
> ## ③ 【用户诉求】"语言设置你给去掉了？那个更改后可以更改启动配置语言"
>
> v3.2.29 曾把容器语言选择项整体移除（当时 wine 语言改由 /opt/locale.conf 独家决定）。
> v3.3.2 恢复并真正生效：
> - **编辑页 + 新建容器对话框**各恢复"语言"下拉：跟随 /opt/locale.conf（auto，默认）/
>   简体中文 / English / 繁體中文 / 日本語 / 한국어（对齐 wineguanli.py 语言映射）；
> - `WineEnvironment.buildBaseEnv` 语言优先级：**容器显式 lang > /opt/locale.conf >
>   zh_CN.UTF-8 兜底** —— 显式档直接作为 LANG/LC_ALL（配合 0009 补丁在 bionic 上
>   真正决定 wine 界面语言），auto 档即 startwine.sh 的 `export LC_ALL="$(cat
>   /opt/locale.conf)"` 语义；conf/*.conf 显式 export 的 LANG/LC_ALL 仍可再覆盖，
>   容器 env= 最高。
>
> ## ④ 【用户诉求】"根本没有清理干净，还是很多报 media 错误"+ 注册表注入被跳过的闭环
>
> **2.38.16 以后 main 全量审计结论**（b794d3e^..HEAD 62 文件 +8427/-2814，注册表相关）：
> - `WineRegistry.ensureDllOverrides`（147 项内置 DLL 覆盖表）：b0c1e69（2.38.23）改为
>   按前缀内**实际存在**的 native DLL 过滤写入 → 07de0c7（2.38.24）**整体删除** +
>   purgeLegacyDllOverrides 旧前缀清洗 → 5daef52（2.38.29）连清洗机制也一并移除
>   —— 现在 App 对 DllOverrides **零动作**，前缀 DLL 加载序完全由 /opt/prefix
>   安装包的 user.reg/system.reg/fix-services.reg 决定（WineLibrary 仅写 DPI/Windows 版本）；
> - `bootstrapOptPrefix`（/opt/prefix 三注册表导入链）：早退分支全部改为大声日志、
>   探测候选扩到 wine 槽位/套层 opt/（2.38.25/28）；
> - **media 错误残留的真因**（设备日志 wine-9.2-20261104-200746 实证）：wineboot -i 因
>   `err:virtual:map_fixed_area out of memory`（39 位地址空间挤占）+ 服务自启失败退出，
>   **system.reg 未生成** → /opt/prefix 三个注册表注入被"前缀未就绪"跳过 → 游戏 DLL
>   加载序无安装包配置 → media 错误依旧。**本轮闭环**（`RootfsLauncher.postPrefixBootChain`
>   开头新增）：system.reg 缺失时**自动重跑一次 wineboot -i**（wineboot 可重入，300s
>   超时保护），重试后大声报告 system.reg 状态；仍失败才放行（下次启动自动重试）。
> - `regedit` 导入命令补 `/S` 静默（GUI 错误弹窗不再阻塞前缀构建窗口，退出码非 0
>   照旧大声告警）——"打注册表时这种报错需要点击"的直接缓解。
>
> ## ⑤ 【用户诉求】home 目录出现乱码文件（"pE@ঈ@8@@@hh肨肕ll@…"）
>
> 字节特征是内存转储被当文件名落盘（孟加拉/泰米尔字符混 @、pE、ll 等重复片段）——
> 历史会话的工作目录是 rootfs/home，wine/box64 子进程崩溃时转储/日志直接写 home 顶层。
> **修复双管**：①全部 wine 会话 CWD 从 `WineEnvironment.HOME_DIR` 改到
> `WineEnvironment.RUNTIME_DIR`（rootfs/usr/tmp，17 处：注册表导入/函数库/CJK 字体/
> modern-UI/保活/前缀链等）—— 后续崩溃转储不再落 home；②启动链新增
> `quarantineHomeGarbageFiles`：home 顶层文件名含 U+FFFD / 南亚东南亚文字块 /
> CJK 扩展 B / 超长（>96 字符）的存量乱码文件移入 `home/.anwind/quarantine/`
>（保留原名可甄别，**不自动删除**任何文件）。
>
> ## ⑥ 语法修复与版本
>
> - faccbf3「修构建」已修复 WineEnvironment.kt 的 CI 报错（681:6 Missing '}' /
>   851:1 Unclosed comment）；本轮全仓 141 个 .kt 经 ktlint 0.50（Kotlin 1.9 PSI）
>   解析级检查：**零语法错误**（输出全部为 trailing-comma/indent 等风格项，编译器不 care）。
> - App v2.38.26 → v2.38.27（versionCode 89 → 90）。
>
> **v3.2.27（2026-11-04，源码包对应 App v2.38.24 / versionCode 87）**：DllOverrides 彻底移除 + wineboot 后续链同会话执行 + /opt conf source 语义 + rootfs 中文依赖（用户 v3.2.26 实测反馈 + 参考 startwine.sh / wine-wow64 yml）。
>
> ## ① 【用户强烈要求】APK 自带 DllOverrides 注册彻底去掉，完全按照 rootfs/opt/prefix/ 下的注册表来
>
> **用户原话**："求求你把 apk 自带的 DllOverrides 注册去掉吧，自从有了他很多游戏都报 media 错误，完全按照 rootfs/opt/prefix/ 下的注册表来。"
>
> **修复**：
> - **整体删除** `WineRegistry.ensureDllOverrides` + 147 项 `DLL_OVERRIDE_ENTRIES` 内置表 —— App 从此不写任何 DllOverrides；前缀的 DLL 加载序完全由 `rootfs/opt/prefix/` 安装包的 `user.reg` / `system.reg` / `fix-services.reg` 决定（startwine.sh 同源语义：安装包注册表在 wineboot 后导入，无内置表覆盖）；
> - **旧前缀一次性清洗**（`purgeLegacyDllOverrides`，幂等标记 `.anwind-dlloverrides-purged`）：v3.2.14~v3.2.26 构建过的容器启动时自动 `regedit` 导入 `"键"=-` 删除语法清掉 147 个键值（恢复 wine 默认加载序），随后**重导** /opt/prefix 的三个注册表（旧链路里内置表在安装包之后导入、把调好的值盖掉了，这里按参考脚本顺序补回来）；
> - `WineLibrary`（函数库设置）不再写 dsound/quartz 的 DllOverrides 键 —— 仅保留 DPI（LogPixels）与 Windows 版本三件套；容器「函数库」页 UI 同步改为说明文案；
> - CLI 侧 `anwind-container` 的 `install-dxvk` / `install-vkd3d` 保持不变（用户显式安装时的定向 d3d8/d3d9/d3d10core/d3d11/dxgi/d3d12 键，语义正确）。
>
> ## ② wineboot -i 结束后同会话继续解压 + 注册表（对齐 startwine.sh 301-369 行）
>
> **用户原话**："wineboot i 后依旧是结束构建前缀了，啥也没有继续，下次启动软件才进行解压和注册表，参考 sh 你是一点也不看。"
>
> **根因**：`wineboot` 会话被归为"宿主工具命令"（`isHostToolCommand`）→ 整条 ensure 链（含 /opt/prefix 资产注入）被跳过，wineboot 进程一退出会话就结束；那些步骤只在**下次**启动游戏（非宿主工具命令）时才跑。
>
> **修复**（`RootfsLauncher.postPrefixBootChain`，两条入口共用一份实现）：
> - `wineboot -i` 进程退出后在 **onExit 回调里继续**前缀构建链：/opt/prefix 资产解压（drive_c/directx/fix-fonts 归档 + start 开始菜单 + marlett.ttf）→ user/system/fix-services 注册表导入 → dosdevices → EmulateModeset → ModernLook → DXVK → 旧 DllOverrides 清洗 → 函数库设置，**每步日志进前缀构建窗口**，最后统一打点"前缀构建完成"；
> - 游戏会话路径（前缀不存在时 initializePrefixIfNeeded 同步建前缀）同样走 `postPrefixBootChain`，消除两份重复实现。
>
> ## ③ /opt conf 读取改为 source 语义（"你根本没有 env 或者 export"）
>
> **用户原话**："启动 wine 依旧没有读取 opt 下的任何 conf 配置，你根本没有 env 或者 export，更没有执行 sh 里那种 . /路径。"
>
> **根因**：v3.2.26 的白名单（BOX64_*/WINEESYNC/WINEFSYNC/WINEDEBUG/WINEDLLOVERRIDES/cores）太窄 —— env.conf 里的 MESA_*/GALLIUM_*/自定义调优变量全部被丢弃，等于没读。
>
> **修复**（`WineEnvironment.applyOptConfEnv` 重写）：
> - `. /opt/conf/*.conf` 的 source 语义：**全部 KEY=VALUE 都 export** 给 wine 进程，逐项日志（`opt 生态: conf N 项已 source（export M 项: KEY=VALUE…）`）；
> - 仅 App 链路生存关键变量黑名单不透传（WINEPREFIX/WINE/WINELOADER/WINESERVER、DISPLAY/XAUTHORITY、HOME/TMPDIR、LD_PRELOAD/LD_LIBRARY_PATH/LD_DEBUG、BOX64_PATH/BOX64_LD_LIBRARY_PATH —— 覆盖即断链，日志里大声说明）；
> - `PATH` 合并非覆盖（conf PATH 里缺失的目录前置补入，等价 sh 的 `export PATH=/usr/bin:$PATH`）；
> - `GLIBC_BIN` 兜底导出（sh 同款，指向 rootfs 的 usr/local/bin）；
> - locale.conf → `LC_ALL`/`LANG`；容器 env= 仍在链路最后可覆盖一切；cores.conf 的 PRIMARY_CORES 走通用导出（容器 cores 配置仍优先）。
>
> ## ④ rootfs / wine 构建中文依赖（"locales language-pack-zh-hans 这两个依赖没有，所以一直没有中文"）
>
> 对照用户提供的 wine-wow64 参考 yml 的依赖与"设置中文语言环境"步骤：
> - `host-deps.sh` debian/ubuntu 分支：**探测式安装 `language-pack-zh-hans`**（Ubuntu 专属包名，Debian 无此包不炸）+ `locale-gen zh_CN.UTF-8` + `update-locale LANG=zh_CN.UTF-8`；arch 分支 locale.gen 同步启用 zh_CN.UTF-8（`locales` 包此前已装）；
> - wine 构建已有 `--enable-nls` + zh_CN/wine.mo 落包校验（msgfmt 缺失时 CI 日志提示，App 侧 WineLocaleSupport 从 APK assets 兜底安装 zh_CN/zh_TW/ja/ko）；
> - rootfs-locale 配方（glibc locale-archive 含 zh_CN.UTF-8）+ rootfs-fonts（CJK 字体）维持既有链路；
> - **mfplat 补丁**（yml 的 wine_do_not_create_dxgi_manager2.patch）：核查后**不适用** —— 该补丁针对 wine-10.4/proton 树的 mfplat/main.c，wine 11（hangover-11.16）已重构删除 `resolver_create_gstreamer_handler` / `MFCreateDXGIDeviceManager`，hunk 无处落地；我们的 gstreamer 全家桶（good/bad/ugly/libav）已启用，媒体链路走 winegstreamer。
>
> ## ⑤ wine 构建：PE 交叉编译器修正（x86_64 形态 wine ≤9.x 收尾）
>
> - `projects/wine/build.sh`：`i386_CC`/`x86_64_CC` 由 NDK clang（`$CC`）改指 **llvm-mingw 包装器 clang**（i686/x86_64-w64-mingw32-clang，自带 mingw target）—— wine ≤9.x 旧 configure 会把环境预置的 CC 当 PE 编译器（探测被跳过），NDK clang 目标是 *-linux-android 编不出 PE 对象；wine 10+/hangover 树由 `--with-mingw=<dir>/clang` 统一覆盖（无害）；
> - 退出码传播（make / make install / pre_package / build_system / make_pkg 五层硬失败）与 `-mabi=ms` 剥除（wine ≤9.x MSVCRTFLAGS）此前已在 v3.2.24 落地，本轮核查确认。

> **v3.2.26（2026-11-04，源码包对应 App v2.38.23 / versionCode 86）**：数据丢失事故根治 + /opt 生态路径修正轮（用户 v3.2.25 实测反馈）。
>
> ## ① 【严重】删除容器把 Download / exagear 整目录内容删光 —— 根因与根治
>
> **用户实测**：删除容器"直接把内存删了，download 和 exagear 全都被删"。
>
> **根因**（本机 kotlin-stdlib 1.9.25 实证复现）：Kotlin 的 `File.deleteRecursively()` / `copyRecursively()`（FileTreeWalk）会**穿透符号链接递归操作目标目录内容**。v3.2.24 起容器前缀的 `dosdevices/` 里有三个指向 rootfs 之外的符号链接：`d:→/storage/emulated/0/Download`、`e:→/storage/emulated/0/exagear`、`z:→/` —— 删容器时 walk 穿过 d:/e: 把用户存储里的文件全部删除（z:→/ 因系统目录只读/权限未造成更大扩散，但同等危险）。
>
> **修复**（新增 `com/anwind/util/SafeFileOps.kt`）：
> - `deleteTree()`：NIO `Files.walkFileTree` **不带 FOLLOW_LINKS** —— 符号链接按"文件"处理、只删链接本身绝不删目标，目录后序删除；
> - `copyTree()`：同语义复制 —— 遇符号链接在目标位置**重建同指向链接**（dosdevices 盘符链接原样保留），普通文件正常复制；
> - 替换工程内**全部**危险调用点：`ContainerManager.remove`（删容器）/ `clone`（克隆容器，旧版会把 Download 深拷进新容器）、`GuanliSupport.WineOps`（wine 槽位安装回滚 ×4 + 删除槽位）、`WineServerUtils`（wineserver 运行区）、`RootfsLauncher`（noexec 游戏导入暂存区）、`AnWindTzstAssets`（DXVK 解压暂存 ×2）、`TermuxBootstrapInstaller`（debfix 缓存）、`WineLocaleSupport`（fontconfig 缓存 ×2）、`WineRegistry`（/opt/prefix 开始菜单复制）。
>
> ## ② /opt 生态路径修正 + 全程大声日志（用户："会不会路径搞错了，包括 opt/locale.conf 都没有执行，应该是 /data/data/com.anwind/files/rootfs/opt"）
>
> **修复**（`WineEnvironment` + `WineRegistry` + `anwind-wine` v2.14 三端同源）：
> - **探测候选**：`rootfs/opt`（用户指定主路径）→ **wine 槽位目录**（WinFusion 风格包把 conf/prefix 与 bin/ 平级打进 wine 目录树、经 App「wine 版本管理」`--strip-components=1` 解包后落在槽位内的布局）→ `rootfs/usr/opt`（旧布局兜底）；判定条件放宽到 conf/、locale.conf、prefix/、dxvk1/ 任一存在；
> - **wine_path.conf 按用户要求剔除**：wine 目录一律由 AnWind 槽位体系管理（容器 wine= / default_wine / 槽位扫描），conf 解析跳过该文件（双端）；
> - **全程大声日志**：`opt 生态: 未检测到（探测: rootfs/opt ✗；wine槽位 wine-proton10-x86 ✗；rootfs/usr/opt ✗）` 这样的逐候选明细直接进会话日志/前缀构建窗口 —— 路径对不对、读了哪里、应用了哪些键（`opt locale.conf → LC_ALL=…`、`opt 生态: 已应用 N 项（BOX64_…=…）`）一目了然，不再黑盒。
>
> ## ③ 前缀构建"wineboot -i 结束后什么都不显示"修复
>
> **用户诉求**：构建前缀时 wineboot -i 结束后，后续的解压以及注册表全部没有显示（参考 sh 不是这样的）。
>
> **修复**：
> - `bootstrapOptPrefix` 全部早退分支从**静默 return** 改为大声日志：前缀未就绪（system.reg 缺失 → wineboot 未成功 + 附存储空间提示）/ 未检测到 /opt 生态（附逐候选探测明细 + 期望位置）/ 有生态但无 prefix/ 目录；
> - 构建前新增**存储空间预警**（RootfsLauncher）：可用空间 < 512MB 时提前大声警告 —— 用户实测"可用 0MB"仍继续构建，wineboot 需写入上千文件，磁盘满是"wineboot 静默失败 → 后续步骤全部无声跳过"的设备侧根因；
> - bash 侧 `bootstrap_opt_prefix` 同款大声化。
>
> ## ④ DllOverrides 注册表修正（用户："apk 的 DllOverrides 注册表注册就是错误的，乱七八糟的"）
>
> **根因**：v3.2.14 的 147 项"native 优先"组合拳是无条件写入 —— 前缀里**实际不存在**对应 native DLL 时（directx 运行时未解压、DXVK 未注入），wine 按 native 优先找不到就回落 builtin，而 d3dx9_*/d3dcompiler_* 等组件回落路径不可靠 → D3DX 加载失败 → DX9 示例"Could not load required media"。且安装包自带的 user.reg（随包调好的 DllOverrides）会被内置表**覆盖**。
>
> **修复**（`WineRegistry.ensureDllOverrides`）：
> - **安装包优先**：`/opt/prefix` 的 user.reg 已导入（`.anwind-opt-prefix` 标记存在）→ 内置表整体跳过，以安装包配置为准；
> - **按需过滤**：无安装包时，"native,builtin" 键仅在前缀 system32/syswow64 **确实存在**该 DLL 时写入（builtin,native 恒安全保留），其余跳过 —— 杜绝"强制 native 但 DLL 不存在"；
> - **顺序修正**：APK 内置 DXVK 注入移到 DllOverrides 之前（过滤依赖 DLL 已落盘，顺序颠倒会把 DXVK 键全部过滤掉）。
>
> ## ⑤ 顺带
>
> - anwind-wine v2.14：`load_opt_confs` 支持传入 wine 槽位候选、探测结果 `info()` 可见（终端 stdout + 会话日志双通道）；`apply_opt_locale` 同步 info 化。

> **v3.2.25（2026-11-04，源码包对应 App v2.38.22 / versionCode 85）**：arm64ec 构建根治 + /opt 生态接入轮。
>
> ## ① bionic wine 9.7 arm64ec 构建失败（rpcrt4 EC 符号链接阵亡）根治
>
> **用户日志**（wine-build-log-arm64ec (13).zip）：
> ```
> lld-link: error: undefined symbol: stubless_thunks (EC symbol)
> lld-link: error: undefined symbol: #stubless_thunks (EC symbol)
> lld-link: error: undefined symbol: vtbl_thunks (EC symbol)
> winegcc: .../llvm-mingw-22/bin/clang failed
> make: *** [dlls/rpcrt4/aarch64-windows/rpcrt4.dll] Error 2
> ```
>
> **根因**：`stubless_thunks` / `vtbl_thunks` 在 cproxy.c / cstub.c 内由 `__ASM_GLOBAL_FUNC` 裸汇编定义、由 C 代码取址引用（fill_stubless_table / delegating_vtbl 初始化）。ARM64EC 目标文件对符号有"风味"要求 —— C 侧取址产生 **EC symbol** 引用（期望带 x64(EC)→native 入口跳板元数据的符号），裸 asm `.globl` 符号没有该元数据 → lld-link 视为未定义。v3.2.24 的 0007c 曾把 EC 趟改成空桩（THUNK_ENTRY_SIZE=0），但空桩的符号风味问题依旧 —— 链接照炸（本次日志实证）。
>
> **修复**（新补丁 `0013-fix-arm64ec-rpcrt4-thunks-wine97.patch`，对齐 wine-9.7 树内已被同一 CI 工具链实证可编的 `__arm64ec__ naked` 函数模式 —— ndr_stubless.c 的 NdrClientCall2 等四处入口）：
> - cstub.c：每个 `NdrProxyForwardingFunction##num` 独立 naked 函数（体照抄 aarch64 版：x0=this 换 `pvServerObject` 后尾跳服务器 vtbl 槽位；x16/x17 为 IP 暂存寄存器，两种调用约定均可自由使用）；`delegating_vtbl.methods` 改存函数指针（wine-10 thunks.c 同构）。
> - cproxy.c：`call_stubless_func` naked 实现 + `ObjectStublessClient##num` 独立 naked thunk + `stubless_thunk_table` 函数指针数组；`fill_stubless_table` EC 趟查表填充。参数区视图构造与同文件 EC NdrClientCall2 同款（[this, rdx, r8, r9, 栈...] 连续视图）。
> - 编译器为 naked C 函数生成正确的 EC 符号，lld-link 自动合成入口跳板 → vtable 条目可被 EC(x64) 侧代码直接调用，**链接与运行双修**。
> - build.sh 增加 0013 生效校验（cstub/cproxy 无 naked thunk 标记时构建前置报错，不再浪费 28 分钟 CI 撞同一链接错误）。
> - 本地验证：CI 补丁链全量重放后 patch --dry-run 全 hunk 命中 + 应用后字节一致；宏结构独立复制品 gcc -S 验证通过。
>
> ## ② wine 启动读取 rootfs/opt/conf 与 locale.conf（startwine.sh / startfex.sh 参考链路）
>
> **用户诉求**：wine 启动没有读取 rootfs/opt 的 conf 下的配置，以及 opt 下的 locale.conf。
>
> **修复**（脚本 `anwind-wine` v2.13 + App 侧 `WineEnvironment.applyOptConfEnv` 双端同源）：
> - **opt/conf 解析**：固定顺序（startfex.sh 同款：wine_path → env → cores → path → dynarec_fixmpeg → dynarec_active）+ 其余 .conf 兜底；逐行 KEY=VALUE 白名单提取（**不 source 不 eval** —— 配置文件不能执行任意代码，也碰不到容器隔离变量）；
> - **wine_path.conf**：WINE_PATH（chroot 视角 `/opt/...` → 设备路径换算）成为 wine 目录候选（容器 wine= 未显式指定时生效，`WineRuntime.resolveWineDir` 同源支持）；
> - **env.conf / dynarec_*.conf**：`BOX64_*` / `WINEESYNC` / `WINEFSYNC` / `WINEDEBUG` / `WINEDLLOVERRIDES` 白名单导出，覆盖 AnWind 安全默认/预设（用户安装的调优包优先）；
> - **cores.conf**：PRIMARY_CORES/SECONDARY_CORES 导出 + 脚本侧 `taskset -p` 自亲和（wine 及全部子进程继承；rootfs 无 taskset 时静默跳过，参考脚本同款告警语义）；
> - **locale.conf**：LC_ALL/LANG（wine 按 LC_ALL 决定 ANSI 代码页，CJK 程序必需；非法内容拦截）；
> - **优先级**：容器 env= > opt conf > 内置默认（三级，双端一致）；rootfs/opt（WinFusion 风格包落盘处）优先、usr/opt 兜底。
>
> ## ③ 前缀构建解压 rootfs/opt/prefix 文件与注册表
>
> **用户诉求**：构建前缀也没有解压 rootfs/opt/prefix/ 下的文件和注册表注册。
>
> **修复**（脚本 `bootstrap_opt_prefix` + App 侧 `WineRegistry.bootstrapOptPrefix` 同源双保险，两端各自幂等、标记互认）—— startwine.sh 301-369 行完整移植：
> 1. `drive_c.tar.xz` / `directx.tar.xz` → drive_c/（双后缀候选 .tar.xz/.txz/.tar + 魔数嗅探 gzip/xz/zstd/plain）；
> 2. `start/` → ProgramData/Microsoft/Windows/Start Menu（开始菜单快捷方式）；
> 3. `marlett.ttf` → windows/Fonts；
> 4. `user.reg` / `system.reg` / `fix-services.reg` → wine regedit 导入（与 EmulateModeset 同一通路）；
> 5. `fix-fonts.tar.xz` → windows/；
> 6. `opt/dxvk1/` 下 dxvk 归档 → windows/（`opt/virgl/virgl-enabled` 标记存在时跳过，参考脚本同款条件）；
> 7. wineserver -w/-k 落盘注册表 + `.update-timestamp=disable`（参考脚本同款防重跑）。
> App 侧在 `initializePrefixIfNeeded` 成功后自动执行（RootfsLauncher 8a 步）；脚本侧在 wineboot -i 后执行。无 /opt/prefix 时零动作；失败不阻断（下次重试）。

> **v3.2.23（2026-11-04，源码包对应 App v2.38.20 / versionCode 83）**：七诉求修复轮。
>
> ## ① tar.gz / tar.xz 资产在 APK 内保留原始文件名
>
> **用户诉求**：v3.2.22 把 assets 里的 `*.tar.gz` / `*.tar.xz` 同步改名成 `.tgz` / `.txz` —— 打包时应该改回原名才对。
>
> **取证复核**（本项目 AGP 8.5.2 完全同版 aapt2 `8.5.2-11315950` 直测）：`aapt2 link -A`、`-0 gz -0 tgz -0 xz -0 zst` noCompress、`aapt2 optimize`（含 `--collapse-resource-names`）全链路 **均不剥 .gz 后缀、不改名、字节级 cmp 一致**。v3.2.22 引用的"AOSP Package.cpp 剥层"是误诊（那是 aapt v1 / 三方重打包场景）。用户 APK 里出现 `.tar` 更可能来自本地工具链或云端重打包 gunzip。
>
> **修复**：`app/build.gradle.kts` 的 `filterRuffleAssets` 撤销 `.tgz` 同步改名（`assets/dxvk/dxvk-2.3.1.tar.gz`、`assets/termux/x11-xkb.tar.gz` 在 APK 内恢复原名）。**防御纵深**（不信任后缀，重打包剥层也不坏）：
> - `AnWindTzstAssets.kt`：新增 `sniffType` 魔数直判（gzip `1F 8B` / xz `FD 37 7A 58 5A 00` / zstd `28 B5 2F FD` / 纯 tar 偏移 257 `ustar`），`resolveAssetType` 魔数优先、后缀兜底；
> - `XkbExtractor.kt`：xkb 包解压前 mark/reset 读头判型（gzip 或纯 tar 容错）；
> - `TermuxBootstrapInstaller.kt`：xkb 回退引用改双名候选（`x11-xkb.tar.gz` 优先，`.tgz` 兜底）。
>
> ## ② noexec 存储上运行 exe 报 media 加载失败（PROT_EXEC 根治）
>
> **用户日志**：`0120:err:virtual:map_file_into_view failed to set PROT_EXEC on file map, noexec filesystem?` + `0120:err:virtual:virtual_map_section mapping 0xe10000 1b000 0 failed`。
>
> **根因**：exe 位于 `/storage/emulated/0`（sdcardfs/FUSE，Android 上恒为 noexec 且应用无法 remount）。wine 加载 PE 映像需 `mmap(PROT_EXEC)`，在 noexec 挂载上必败；映像段映射失败进程即退，"Could not load required media" 只是首个表象 —— 有没有音频文件都与它无关。
>
> **修复**（`RootfsLauncher.kt` 新增 `stageNoexecExeIfNeeded`）：启动链读 `/proc/self/mounts`（最长前缀挂载点 + `\040` 等转义还原）判定 noexec → exe 所在目录**整体导入**内部执行区 `rootfs/home/.anwind/games/<容器>/<源目录名>`（/data/data 内部 ext4，可 exec；同目录 DLL / `media\` / `data\` 一并可用，CWD 随新路径解析）→ 以内部副本启动。指纹（文件数/总字节/exe mtime）命中零拷贝秒启；空间预检（可用 - 需求 > 64MB 余量）不足则警告并按原路径尝试；导入失败不阻断启动。
>
> ## ③ 点最小化变成关闭（任务栏不保留）
>
> **根因（三处叠加）**：①任务栏四种主题（Win95/XP/Aero7/Win10）`wm.windows.filter { it.isVisible }` 把最小化窗口滤掉；②桌面图标/开始菜单一律 `wm.open`，最小化窗口永无恢复路径，只会叠开重复窗口；③X11 窗口 `onDispose` 一律 `detachView(userClosed = true)`，最小化被误标"用户关闭"（抑制自动重连）。
>
> **修复**：`Taskbar.kt` 窗口列表不再滤 isVisible（最小化窗口恒列任务栏，非激活态渲染，点击即恢复）+ `TASKBAR_PINNED_IDS` 补 `wine-containers`；`WindowManager.kt` 新增 `openOrRestore`（存在最小化窗口优先 focus 恢复，可见窗口保持多开语义），`DesktopIconGrid.kt` / `StartMenu.kt` 改走该入口；`X11Surface.kt` 最小化时 `detachView(userClosed = false)`（会话保持可自动重连，还原即恢复画面）。
>
> ## ④ 虚拟手柄重制（悬浮球 + 触摸穿透 + 映射 UI）
>
> - **常驻悬浮球**（新文件 `GamepadFloatingBall.kt`）：默认右上角炫彩半透明圆球（径向渐变 + 高光 + 球心指示），单击展开控制菜单（显示/收起手柄、手柄设置、编辑布局、关闭手柄），拖动移位（松手持久化 `gamepad_config` v3 的 `ballX/ballY`），菜单展开时点外部收起。「关闭手柄」连球一起退场（gamepadEnabled=false，设置页/快捷开关可重开）。
> - **旧迷你工具条（⚙✎✕）移除**：`GamepadOverlay.kt` 删除 `GamepadMiniToolbar` / `MiniToolButton`，控制入口全部收拢到悬浮球。
> - **触摸穿透修复**：此前按手柄按键会点到底下窗口 —— ①`WindowFocusHook.raiseAt` 与 `TrackpadRouter.onMotionEvent` 按 `GamepadController.isOverPadUi` 直判（元素 ∪ 悬浮球 ∪ 展开菜单区域不置前/不当手势）；②悬浮球本体与展开菜单登记 X11 触摸剥离命中区（`registerToolbarHit` / 新增 `registerMenuHit`）+ 触控板直通区；③手柄元素收起时 `releaseAllKeys` + 清命中矩形（防联键与陈旧矩形）。
> - **映射界面重制**（`GamepadSettingsWindow.kt` 重写）：单层底栏面板 —— 顶栏一排（预设/添加/编辑模式/完成）+ 元素行列表；点元素行**原地展开**编辑区：按钮元素直接点分组 Tab + 按键网格**点键即完成映射**（当前映射高亮，不再进二级页）；摇杆/十字键方向模式一键切换；尺寸 −/+ 步进（替代滑杆）；去 posX/posY 滑杆（编辑模式直接拖元素定位）与标签配色 chips。
>
> ## ⑤ Wine 容器主界面太长太宽
>
> `ContainerApp.kt` 默认尺寸 1060×680 → **900×600**。`ContainerScreens.kt` 字号缩放与尺寸缩放分离（新 `LocalGuanliFontScale`，字号基准 900）：默认窗下字号与旧 1060dp 窗**完全一致**（字体保持默认），仅窗口与布局尺寸收紧；窗口拖到比 900 窄时字号才同步缩（窄窗适配保留）。弹窗经 `GuanliDialogScale(winScale, winFontScale)` 同步双系数（9 处弹窗调用点全部更新）。
>
> ## ⑥ arm64ec wine 启动 exe 太慢
>
> 两个提速点（不改语义）：
> - **wineserver 暖启动**（`WineServerUtils.kt`）：无运行中会话且非前缀构建时，存活 server 通过真实客户端健康探测（`wine cmd /c exit`，6 秒超时）即**复用**——跳过 `-k`/`-w` 与硬清理，内存注册表缓存、rpcss/explorer 后台服务全保留（arm64ec 冷启动 server + 重读注册表是二次启动明显偏慢的主因）；无残留直接返回（免 -k/-w 进程拉起）；探测超时（冻结/挂死）退回原三段式全量清理（v3.3.1 挂死根治语义不变）。顺带修复"运行 exe 会把同容器正在跑的 X11 桌面会话连根杀掉"。
> - **注册表批量化**（`WineLocaleSupport.kt`）：CJK 字体注册从逐条 `wine reg add`（9~13 次进程拉起，arm64ec 上白花十几秒）改为 REGEDIT4 文本一次 `wine regedit /s` 导入（与 `WineRegistry.ensureDllOverrides` 同模式）。
>
> ## ⑦ rootfs + wine 中文支持（字体放置路径 · v3.2.24 更新）
>
> **用户字体目录（v3.2.24 起）：`rootfs/usr/share/fonts/anwind-user/`**（即 `/data/data/com.anwind/files/rootfs/usr/share/fonts/anwind-user/`，经 AnWind 文件管理器或终端可直接访问）。把自己的中文字体（.ttf/.otf/.ttc）放进去即可 —— 该目录本身就在 fontconfig 扫描路径内（`/usr/share/fonts`），启动链只需补 GDI 侧（`WineLocaleSupport.kt` 的 `importUserFonts`）：
> - rootfs fontconfig 侧（X11/freetype 渲染）：目录本身即扫描路径；旧 `内部存储/AnWind/fonts/`（`/storage/emulated/0/AnWind/fonts/`）里的字体仍会合并导入（兼容源）；
> - wine 前缀侧（GDI 渲染）：复制进 `drive_c/windows/Fonts` 并按文件名写注册表（REGEDIT4 单次导入）。
>
> 另 v3.2.24：系统 CJK 字体（NotoSansCJK/MiSans 等）除装进前缀外，同步落到 `rootfs/usr/share/fonts/anwind-cjk/`（此前 rootfs 侧一份都没有，freetype/X11 路径中文全方块）。
>
> 幂等指纹（文件名:长度），增删字体后下次启动自动同步；目录不存在/为空静默跳过，无字体时回落内置 CJK 方案（fontconfig 别名 + 注册表 Fonts/FontSubstitutes，SimSun/SimHei/Microsoft YaHei 等）。
>
> ## 验证
>
> 沙箱搭 Android SDK 34 + Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.25（与 CI 同栈）全量实编：`:app:compileReleaseKotlin` BUILD SUCCESSFUL。aapt2 打包行为以同版二进制全链实验定论（资产原名原字节保留）。设备侧验证：装新 APK 后检查 ①APK 内 `assets/dxvk/dxvk-2.3.1.tar.gz` 原名；②noexec 路径跑 exe 会话日志出现"正在导入内部执行区"；③最小化后任务栏图标还在、点击恢复；④手柄悬浮球拖动/菜单；⑤容器窗 900×600；⑥二次启动 exe 明显加快且 X11 桌面不被杀；⑦`rootfs/usr/share/fonts/anwind-user/`（或旧 `AnWind/fonts/`）放字体后启动 wine 显示中文。

> **v3.2.20（2026-11-03）**：CI 实报双故障修复轮（用户反馈：①APK 构建 `ContainerScreens.kt` 8 处编译错误；②box64 运行"还是老样子"—— wineboot 启动即 SIGSEGV，wine 会话日志 `wine-9.2-20261103-095002-606.log`）。
>
> ## 修复 1：ContainerScreens.kt 编译错误（APK 构建失败）
>
> **根因**：v3.2.19 修掉 GuanliSupport.kt 的未闭合注释后，Kotlin 解析终于通过，类型检查暴露出 ContainerScreens.kt 里 8 处语义错误（上一轮解析失败时整轮编译在语法阶段就被中止，这些错误根本没被报出来）。
>
> **逐条修复**（沙箱搭 Android SDK 34 + Gradle 8.7 + AGP 8.5.2 + Kotlin 1.9.25 + compose-bom 2024.06.00 全量实编验证，`:app:compileReleaseKotlin` 与 `assembleRelease` 均 BUILD SUCCESSFUL，产物 app-release.apk 97MB）：
>
> | CI 报错 | 修复 |
> |---|---|
> | 337:39 `'val maxWidth: Dp' can't be called in this context by implicit receiver` | `maxWidth` 在 BoxWithConstraints 作用域内捕获为局部 `val availW`，嵌套 lambda 按闭包捕获使用（第 274 行同源引用不受影响） |
> | 709:14 `'weight' cannot be invoked as a function` | `weight()` 只在 Row/Column 内容作用域可用：LeftContainerListBox 增加 `modifier: Modifier = Modifier` 参数，LazyColumn 改用注入的 modifier，`Modifier.weight(1f)` 移到调用点（Column 内）注入 |
> | 2896/2920 GField "Too many arguments" + "String 但 () -> Unit" + 2897/2921 "@Composable invocations" 级联 | GField 增加可选 `note: String? = null` 参数（Qt 表单说明小字），三参调用形态 `GField(标签, 说明) { … }` 合法化，24 处旧二参调用不受影响 |
> | 3166:9 `Cannot find a parameter with this name: containerColor` | material3 1.2.1 的 DropdownMenu 尚无 `containerColor` 参数（1.3 才加）；改为内容层包一层 `Column(Modifier.background(themeCard()))`，视觉等效 |
>
> ## 修复 2：wineboot 启动即 SIGSEGV（"box64 运行还是老样子"的真正根因）
>
> **设备日志特征**：`x86pc=ntdll.so+0x770a8`，`x64opcode=49 8B 4F 10 4C 39 E1 77`（`mov rcx,[r15+0x10]; cmp rcx,r12; ja`），访问 `0x928e8` 段错误；寄存器 `R15=0x00000000000928d8`（链期裸地址）而 `RAX=0x0000003f000928d8`（正确重定位值）。
>
> **根因（用 bionic-wine-official-9.2-x86_64 发布包的 ntdll.so 逐字节核实）**：崩溃点是 wine 9.2 virtual.c 的 `mmap_add_reserved_area(0x10000, 0x8000)`（box64 WINE prereserve 的第一段）里 `LIST_FOR_EACH(&reserved_areas)` 读 `area->base` —— `reserved_areas` 是 `static struct list = LIST_INIT(reserved_areas)`（.data 自指针）。**NDK r29 lld 默认给 bionic 链接的 .so 发 Android 标签的 RELR 打包重定位**（`DT_ANDROID_RELR=0x6fffe000` / `RELRSZ=0x6fffe001` / `RELRENT=0x6fffe003`，编码与标准 RELR 完全相同、仅动态标签号不同），该 ntdll.so 的 `.rela.dyn` 只剩 7 项，几百个 R_X86_64_RELATIVE 全打包进 `.relr.dyn`（SHT_ANDROID_RELR，14 个打包项）。**box64 v0.4.4 的 elfparser 只认标准 `DT_RELR/DT_RELRSZ/DT_RELRENT`，Android 标签被当未知标签忽略 → 打包的相对重定位全部静默跳过** → `reserved_areas.next` 保持裸值 `0x928d8` → `LIST_ENTRY` 把它当 `struct reserved_area*` → 读 `[0x928d8+0x10]` 即 `0x928e8` 段错误。wine 全部 x86_64-unix/*.so（avicap32 到 opengl32 逐个 readelf 核实）都是同款标签，只是 ntdll 最先死。`wine --version` 能过是因为它只依赖 JUMP_SLOT/GLOB_DAT（未打包）与原生 wrapped 库符号，不碰自指针。
>
> **修复**：0001 补丁给 `src/elfs/elfparser.c` 与 `elfparser32.c` 的 `DT_RELR/DT_RELRSZ/DT_RELRENT` case 各加 `0x6fffe000/0x6fffe001/0x6fffe003` 别名（Android RELR 与标准 RELR 编码一致，别名即可）。**用户设备上现有的 wine9.2 无需重装**，重刷 box64 即可生效。
>
> **验证（三层）**：
> ① 最小复现：gcc 构造 `static struct list = {&self,&self}` 的 .so（`-Wl,-z,pack-relative-relocs` 生成标准 DT_RELR），python 把三个动态标签翻成 Android 风味 —— 旧 box64 实跑 `RELR_FAIL_UNRELOCATED`（bug 本地复现），打补丁后 `RELR_OK`，标准标签回归不变；
> ② 真实二进制：同一加载器驱动 dlopen 用户在用的 bionic ntdll.so —— 旧 box64 日志无 "Applying N Relocation(s) without Addend" 行（重定位被跳过实锤），打补丁后 `Applying 14 Relocation(s) without Addend` 全量应用（PLT/GLOB_DAT 原有 7+196 项不变）；
> ③ CI 回归：`build-box64-bionic.yml` 冒烟新增 `[ANDROID RELR]` 用例（无需 lld：gcc 标签 + python 翻转 + box64 实跑断言 `ANWIND_RELR_OK`），"补丁在源"校验同步加 `6fffe000` 内容检查，缺失即禁止放行。
>
> ## 附带清理：`cc1: note: unrecognized command-line option '-Wno-pointer-type-mismatch'`
>
> box64 上游 CMakeLists.txt 在 `CI=ON` 时无条件加 gcc 专属的 `-Wno-pointer-type-mismatch`，clang（宿主冒烟构建）打印 note 噪音。0001 补丁将条件收窄为 `CI AND CMAKE_C_COMPILER_ID MATCHES "GNU"`，gcc 行为零变化，clang 噪音消失。

> **v3.2.19（2026-11-03）**：CI 实报双故障修复轮（用户反馈：①APK 构建 `GuanliSupport.kt:484:1 Unclosed comment`；②box64 冒烟 `SMOKE_FAIL_SHM`，即时构建成功也发不出 Release）。
>
> ## 修复 1：GuanliSupport.kt 未闭合注释（APK 构建失败）
>
> **根因**：v3.2.18 新增的 GuanliSupport.kt 第 16 行 KDoc 注释里写了 `/opt/conf/*.conf` —— `conf/` 与 `*.conf` 相邻构成 `/*` 序列。**Kotlin 块注释支持嵌套**，`/**`（第 14 行）内出现 `/*`（第 16 行）会再开一层注释，第 35 行的 `*/` 只闭合了内层，外层 `/**` 吞掉文件剩余全部内容直到 EOF → `GuanliSupport.kt:484:1 Unclosed comment`。
>
> **修复**：第 16 行改写为无 `/*` 序列的措辞（`/opt/conf/*.conf` → `/opt/conf 目录下的 .conf 文件`），语义不变。
>
> **验证（防同批文件再炸）**：全项目 107 个 .kt 过两道自研扫描 —— ①注释嵌套扫描（块注释深度配平 + 注释内 `/*` 检出，对修复前文件可复现报错、修复后归零）；②括号/字符串配平扫描（模式栈词法器：code/str/tpl 三态、`${` 压栈标记、块注释任意模式内消费）—— 修复后全仓库零问题。另对 GuanliSupport 的全部对外引用（DY_PRESETS/WineOps/AppDefaults/MangoHud/Shortcuts 等 22 个符号）逐项对照定义与签名（NativeShellExecutor.setCommand/putEnv/setWorkingDir、WineEnvironment.HOME_DIR: File、WineRuntime.wineCommand: List&lt;String&gt; 等均匹配）。
>
> ## 修复 2：box64 冒烟 SMOKE_FAIL_SHM（构建成功也发不出 Release）
>
> **根因（不是构建问题，是冒烟步骤自身漏打补丁）**：`build-box64-bionic.yml` 步骤 5.7「源码级冒烟」`git clone` 一份全新的 vanilla box64 v0.4.4 后**直接构建实跑，从未应用 0001-anwind-android-shmem-native.patch**。冒烟注释写着"补丁后必须通过"，实现却漏了 `patch` 这一步 —— vanilla v0.4.4 的 x64syscall.c 本来就没有 case 29/30/31/67，静态链接 x86_64 冒烟程序必然 `shmget: ENOSYS` → `SMOKE_FAIL_SHM` → 步骤 exit 1 → Release 永远发不出去（aarch64 交付构建本身成功，install() 符号校验全过；冒烟树与交付树不同源，测了个寂寞）。
>
> **修复**：冒烟树在 cmake 之前补打 0001 补丁（`patch -p1 --dry-run` 预检 + 应用），与 aarch64 交付构建同源。补丁在非 ANDROID 宿主构建下只激活 x64syscall.c 的 `#if defined(__linux__)` 转发分支与 private 头 GO 原分支（ANDROID 段均编译为空）—— 打补丁后本步才真正成为补丁的"宿主侧回归测试"（原注释声明的意图）。
>
> **沙箱端到端实测（x86_64 宿主，与冒烟步骤同参数 -DCMAKE_BUILD_TYPE=Release -DARM_DYNAREC=OFF -DCI=ON）**：①`patch -p1 --dry-run` 对 vanilla v0.4.4 四文件全部干净应用；②补丁树宿主构建成功；③动态冒烟（wrapped-libc 层）`ANWIND_SMOKE_OK 16963`；④**静态冒烟（syscall 层，CI 失败点）`ANWIND_SMOKE_OK 16963`** —— 0001 补丁的 x64syscall.c 转发实证生效。
>
> **顺手加固**：`build-box64.yml`（glibc 形态独立工作流）裸 clone 同样不打补丁 —— 静态 x86_64 程序在 glibc box64 下同样吃 ENOSYS。同步应用 0001 补丁（glibc 侧只激活 `#if defined(__linux__)` 转发，ANDROID 段编译为空，行为安全）；上游 hunk 漂移时 `--dry-run` 预检直接失败（早暴露优于静默丢补丁）。
>
> **附注**：CI 日志里 `cc1: note: unrecognized command-line option '-Wno-pointer-type-mismatch'` 来自 box64 上游 CMakeLists.txt:325 自带的 gcc 专用参数，NDK clang 仅打印 note（仅在有其它诊断时出现，非致命）—— 与构建失败无关，无需处理。
>
> APK 版本 v2.38.17→v2.38.18（versionCode 80→81）
>
> **v3.2.18（2026-11-03）**：两诉求轮 —— ①box64「缺的东西」补齐（用户 CI 日志 box64log2 实锤：`错误: box64 符号表缺 shmget/shmat/shmctl → install() 中止`，与 SAFEFLAGS 无关，v3.2.17 诊断结论修正）②容器管理界面按 wineguanli.py **完全重制**（用户反馈 v3.2.17 的"重制"有 700dp 宽度门槛，手机上根本看不到新界面；wineguanli.py 本身可以实现所有宽度，本轮去掉门槛：等比缩放 + 横向滑动）。
>
> ## 修复 1：box64 内建 SysV shm（补齐用户点破的"缺东西"）
>
> **证据链（上游 v0.4.4 与 main 全量沙箱复现，非推断）**：
> 1. box64 对 x86_64 程序是 **wrap libc** 模型（wrappedlibc_private.h: `GO(shmget, iFiLi)` 等四符号）：程序的 shm 调用桥接到宿主 libc 的 dlsym。glibc 宿主能解析（x86_64 CI 冒烟通过的原因）；**bionic 宿主根本没有 shm 符号** → "function not found" —— 用户日志"符号表缺 shmget"指向的正是这个缺口；
> 2. box64 的 syscall 层（x64Syscall_linux）**没有 case 29/30/31/67**：静态链接 x86_64 程序的 shmget 一律 ENOSYS（沙箱实测 vanilla v0.4.4：`shmget: Function not implemented`）；
> 3. Android 内核普遍无 CONFIG_SYSVIPC，直接转发内核也不通；
> 4. wrappedandroidshmem.c 只是 dlopen libandroid-shmem.so 的壳（rootfs 里也没有该库），帮不了 glibc 链接的 x86_64 wine。
>
> **修复（`bionic-rootfs/projects/box64/0001-anwind-android-shmem-native.patch`，4 文件）**：
> - 新增 `src/libtools/android_shmem.c`：memfd_create 后端的完整 SysV shm 实现（/dev/ashmem 回退；IPC_PRIVATE/key 查找/SHM_RDONLY/SHM_RND/引用计数/IPC_RMID 最后脱离后销毁），导出 `shmget/shmat/shmctl/shmdt`（真实符号，进符号表与 .dynsym）+ `my_*` 别名；非 ANDROID 构建编译为空；
> - `wrappedlibc_private.h`：shm 四符号在 ANDROID 下 GO→GOM（桥接到 my_* 内建实现，动态链接 x86_64 程序的 shm 恢复可用）；
> - `x64syscall.c`：主 switch 补 case 29/30/31/67（ANDROID 转发内建实现，glibc 宿主直用 libc —— 静态程序也修好，glibc 形态构建同样受益）；
> - 单元测试（沙箱实跑）：模拟逻辑 8 组断言全过（双 attach 共享可见/nattch/延迟 RMID/EEXIST/ENOENT/尺寸校验/错误路径/200 轮建销压测）；补丁树 x86_64 宿主构建后动态+静态 shm 冒烟均 ANWIND_SMOKE_OK（vanilla 树静态项复现失败）；
> - `build.sh` install() 校验恢复**合法的**符号直查：shmget/shmat/shmctl/shmdt/getDB/FreeDynablock（缺符号 = 补丁没生效，正是本轮要挡住的事故面）；workflow 冒烟加静态链接变体（syscall 转发回归测试）+ 交付树补丁存在性校验。
> - **v3.2.17 SAFEFLAGS 结论修正**：v3.2.17 把 wineboot SIGSEGV 归因 SAFEFLAGS=0 并给出"env= 加 BOX64_DYNAREC_SAFEFLAGS=2 自救"—— 用户实测无效并指出真正缺的是 shm 链路。本轮以日志证据为准修正：**根因是 shm 两条链路全断**（上链 ①②）。SAFEFLAGS 调整（PERFORMANCE 预设 0→1、wineboot 会话强制 2）作为无害加固保留，不再声称是根因。
>
> ## 修复 2：容器管理界面 wineguanli.py 完全重制（全宽度）
>
> **v3.2.15-17 的问题**：重制面板挂在 `maxWidth >= 700.dp` 门槛后 —— 手机（~411dp）永远走旧卡片列表，用户看到的"UI 没变"完全成立；且五选项卡内容与 wineguanli.py 结构不对齐（无架构/分辨率/语言下拉、无 EXE 文件组、图形驱动是手填槽位而非 /opt 扫描、高级设置页整个缺失）。
>
> **v3.2.18 重制（ContainerScreens.kt 全量重写 + GuanliSupport.kt 新增）**：
> - **全宽度**：缩放系数 = 窗口宽/1024（wineguanli setMinimumSize(1024,600) 的等比适配），夹 0.6..1.0；内容最小宽 1024×scale，放得下恰好铺满、放不下横向滑动 + 底部提示条；全部组件经 LocalGuanliScale（gdp/gsp）等比缩小 —— 横屏平板 1:1、横屏手机 ~0.8、竖屏手机 0.6+滑动；
> - **主窗口**：QToolBar 同款工具栏（☰ 菜单：容器管理/高级设置/体检/退出 + 标题 + ➕创建容器 + 📄快捷方式）；左列 280dp（📦 已创建的容器 + 列表[点击选中/长按菜单：运行/安装前缀/设置/切换语言/删除/重命名/快捷方式] + 新建/删除/重命名 + 创建快捷方式）；右列五选项卡 + 底部 ▶️运行/🔧安装前缀/💾保存配置；
> - **五选项卡**（与 wineguanli.py 逐组对齐）：📋基本设置（容器名称/架构/Wine版本/分辨率[8档+自定义]/语言/帧数限制/窗口模式独立窗口/EXE文件[文件选择器回填]/高级启动选项七勾选[fexmode 新增]）；🎮图形驱动（Turnip/Zink/DXVK/WineD3D 四下拉，/opt/tz、/opt/zink、/opt/dxvk1、/opt/vkord3d 自动扫描 tar 包，默认/不使用/包名三态，运行前自动解压）；🖥️CPU核心（真实核心网格+主副核心信息+说明）；📚函数库（dsound/dshow/DPI 滑块五预设/Windows 版本/说明/立即应用）；🔧更多配置（说明+打开更多设置+当前配置状态+刷新）；
> - **高级设置页**（wineguanli 高级视图）：⚡DY配置（兼容/性能/平衡三预设 9 变量逐字移植 + 变量列表增删 + 应用 → 容器 env=）；📦Box配置（/opt/box、/opt/box86 扫描 + box64 -v 探活 + 应用）；🍷Wine安装（本地/URL 安装到 /usr/opt 槽位 + 列表/⭐设默认/🗑️删除）；📊MANGOHUD配置；
> - **更多设置对话框**（wineguanli 650×950 五选项卡 + AnWind 扩展页）：CPU核心/函数库/环境变量（MESA版本/GLSL版本/VKD3D特性下拉 + MESA扩展/DXVK HUD/TU_DEBUG 多选对话框 + MANGOHUD 开关）/高级功能（CNC DDraw 应用/清除）/Direct3D（强制 D3D9/D3D11 + 目标 EXE）/🧩AnWind 原生（完整后端集/Box64 预设/渲染器/DXVK 注入/音频 —— 不丢任何既有能力）；
> - **对话框族**：新建容器（wineguanli 逐字段 + 创建后询问装前缀）/MANGOHUD 配置（八指标+横屏+字体10-36+四角位置+透明度+无显示模式 → 串解析回填）/多选/重命名（ContainerManager.rename 新增：目录改名+name=归一+默认容器指针跟随）/快捷方式（ShortcutManager 固定到启动器，MainActivity extra 驱动打开窗口并自动运行）/运行 exe（保留文件选择回填）；
> - **container.conf 新键**：`exe=`（wineguanli custom_exe_path 等价物，默认运行程序；快捷方式与 ▶️运行 共用）；flags 新增 fexmode；turnip/zink/wined3d 语义升级为 /opt 扫描槽位（空=默认/off=不使用/包名）。
> - **窗口与桌面**：容器窗口默认 1060×680（对齐 wineguanli 主窗）；MainActivity 处理 `anwind_run_container` extra。
>
> APK 版本 v2.38.16→v2.38.17（versionCode 79→80）
>
> **v3.2.17（2026-11-03）**：bionic x86_64 wine 前缀构建修复 —— v3.2.16 后 box64 终于打通（wine --version 冒烟通过），wineboot -i 推进到 ntdll 早期初始化即 SIGSEGV（退出码 139）。根因不是 wine/box64 构建物，而是**运行时环境注入**：PERFORMANCE 预设注入 `BOX64_DYNAREC_SAFEFLAGS=0`。上游 box64 USAGE.md 语义：0 = "Treat CALL/RET as if it never needs any flags" —— wine 加载器与 ntdll 初始化大量依赖 CALL/RET 之后的条件分支，flags 不保存 → jcc 拿陈旧标志走错分支 → 寄存器装入未重定位的裸值 → 访问未映射小地址。崩溃现场完全吻合：`mov rcx,[r15+0x10]` 处 RAX=0x3f000928d8（带加载基址的 ntdll 内正确地址）与 R15=0x928d8（裸 vaddr）**低 32 位完全一致 = 错误分支后 64 位寄存器高位丢失**，访问 0x928e8（R15+0x10）必炸；而 `wine --version` 快速路径恰好不触碰深层 flags 敏感代码所以冒烟能过。修复：①PERFORMANCE 预设 SAFEFLAGS 0→1（上游默认值，也是 Winlator wine 组合 BIGBLOCK=1/FASTNAN=1/FASTROUND=1/SAFEFLAGS=1/STRONGMEM=0 的实证底线，其余激进项保留）；②wineboot 前缀构建会话在 applyBox64Env 后强制 SAFEFLAGS=2（"All CALL/RET will need flags. All edge cases are also handled"，与 COMPATIBILITY 同档）—— 前缀构建是容器可用性的关键一次性路径，构建期分钟级性能不敏感，取最稳档；用户容器 env=（applyExtraEnv 链路最后叠加）仍可覆盖。APK 版本 v2.38.15→v2.38.16（versionCode 78→79）【v3.2.18 勘误：SAFEFLAGS 归因已被用户实测否定，真正根因是 shm 链路缺失（见 v3.2.18 修复 1）；SAFEFLAGS 调整作为无害加固保留】
>
> **v3.2.16（2026-11-03）**：编译错误修复轮 —— v3.2.15 交付源在用户 CI 实报 `compileReleaseKotlin FAILED`（5 处）+ box64 冒烟测试宿主构建炸，全部修复：①`ContainerScreens.kt:951` `Modifier.weight` 是 RowScope/ColumnScope 成员扩展，组件函数体内直调解析到 foundation 内部 `RowColumnParentData.weight` 属性（报 "Expression 'weight' cannot be invoked"）→ ContainerConfigPanel 增 `modifier: Modifier = Modifier` 参数，宽屏 Row 调用处传 `Modifier.weight(1f)`；②`ContainerScreens.kt:1139/1146` WinOptionPills.options 传 `List<String>` 而签名为 `List<Pair<value,label>>` → dsound/dshow DLL 加载序选项改 Pair；③`WineEnvironment.kt:400` Kotlin 赋值不是表达式，`start = end = sorted[i]` 链式赋值非法（formatCoreSpec 连续段折叠）→ 拆两条语句；④`MusicPlayerApp.kt:137` `musicSettings` 声明于首次使用（searchSource 初始化）之后 → 播放器设置中心块上移到搜索状态之前；⑤`MusicPlayerApp.kt:413/416` `Color.luminance()` 缺 `import androidx.compose.ui.graphics.luminance` → 补齐（背景明暗自适应的纯色/渐变分支）；⑥box64 冒烟（build-box64-bionic.yml）x86_64 宿主错开 `-DARM_DYNAREC=ON`，dynarec_native.c 生成 aarch64 汇编（`mrs ctr_el0` / `dc cvau` / `ic ivau` / `isb sy`）被 x86_64 汇编器拒收（CI 实报 Assembler messages: no such instruction）→ 冒烟构建改 `-DARM_DYNAREC=OFF`（ARM dynarec 仅 aarch64 宿主可编；冒烟只验源码级编译/链接/实跑链路，aarch64 正式构建含 ARM_DYNAREC 不受影响）。全项目 137 个 .kt 经结构校验（括号/注释/字符串配平）零问题；APK 版本 v2.38.14→v2.38.15（versionCode 77→78）
>
> **v3.2.15（2026-11-02）**：六诉求轮 —— ①box64 bionic 构建修复（v3.2.14 符号校验假设错误：纯 GO 型符号非 STATICBUILD 下不进符号表；改为编译单元在链 + libandroid-shmem.so 注册字符串双证据）②启动动画去倍速原速播放 ③assets tar.gz/tar.xz 被 .tar 化的运行时兼容（aapt2 实验证明其不改名，扫描器补 .tar.xz/纯 .tar 识别 + TarCompressorUtils PLAIN 类型）④容器管理按 wineguanli.py 重制（左右分栏 + 五选项卡 + container.conf 十二新键 + 函数库注册表落地，/opt 清单见 docs/WINEGUANLI-OPT-FOLDERS.md）⑤云音乐 AnWindMusic 回移（双音源/本地元数据/黑胶歌词/背景视频/明暗自适应）⑥X11 方向跟随桌面 + 桌面默认横屏
>
> **v3.2.14（2026-11-02）**：三诉求轮 —— ①wine9.7 arm64ec faudio SIMD 修复（0007f 补丁）+ box64 冒烟校验误报修正 + 框架 install() fail-fast ②start.mp4 启动动画（3 秒加速、可关闭、可自定义）③DXVK 资产注入（assets/dxvk → C:\windows）+ 容器组件配置（box64/fexcore/mesa/dxvk）+ DllOverrides 注册表（147 项）
>
> ## 修复 1：wine9.7 arm64ec 构建 — FAudio SIMD（CI 实报：wine-build-log-arm64ec (10).zip）
>
> **炸点**：`libs/faudio/arm64ec-windows/src/FAudio_internal_simd.o` 于
> llvm-mingw-22（clang 21.1.7）的 `mmintrin.h` 连报 20 个
> `invalid conversion between vector type '__v2di' and integer type 'int'`。
>
> **根因**（与 0007/0007c/0007d/0007e 同族的第五处 x86 宏错位）：
> FAudio 的 SIMD 分发首分支
> `#if defined(__x86_64__) || defined(_M_X64)` 自定 `__SSE2__` 并 include
> `<emmintrin.h>` —— llvm-mingw arm64ec 目标同时预定义 `__arm64ec__` 与
> `__x86_64__`（不预定义 `__aarch64__`）→ EC 编译趟落入 x86 SSE 分支，而
> clang 的 AArch64 后端不提供任何 x86 向量 builtins → 硬性类型错。
>
> **修复**（`0007f-fix-arm64ec-faudio-simd-wine97.patch`，2 hunks）：
> SIMD 分发最前插入 arm64ec 分支（silicon 是 AArch64，NEON 必有 ——
> 定义 `__ARM_NEON__`、`NEED_SCALAR_CONVERTER_FALLBACKS=0`，x86 分支降为
> `#elif`）+ NEON intrinsics 段判断补 `__arm64ec__/_M_ARM64EC`（原判断只认
> `__aarch64__/_M_ARM64`）。x86_64/aarch64/i386/无-SIMD 四种组合行为与补丁
> 前逐位一致（gcc `-E -dM` 分支矩阵验证：arm64ec 组合补丁前 EMM_INC=1 复现
> 炸点、补丁后 NEON_INC=1/EMM_INC=0）。配套 build.sh 生效校验（arm64ec 形态
> 下 faudio simd 无 `__arm64ec__` 字样即报错，锚点对 wine-10+/hangover 树
> 自然跳过）。已对 wine-9.7 全树扫描确认 faudio 是唯一使用 x86 intrinsics
> 的外置库（dlls/ 零命中）。
>
> ## 修复 2：box64 冒烟校验误报 + 框架 install() fail-fast
>
> **box64（CI 实报：box64-build-log-aarch64 (1).zip）**：v3.2.13 的冒烟校验
> a)（`functions_list.txt 应含 shmget`）是**错误假设** —— 上游实证（v0.4.4
> 与最新 main 全量重跑 + git diff）：shmget/shmat/shmctl 的包装在
> `wrappedandroidshmem.c` **编译期**由 GO 宏 + wrappedlib_init.h 机制绑定，
> 与 rebuild_wrappers.py 的生成物无关；functions_list.txt 只是该脚本的
> 变更缓存对比文件（仅含 E 型/GOM 型符号 + 结构元数据，纯 GO 型符号从不
> 进入），grep 它找 shmget 必然空 → 构建产物本身正常却被误报"错误"
> （且框架对 install() 返回值不检查，出现"错误"字样后仍"构建完成"的矛盾
> 输出）。**修正**：删错误校验 a)；符号校验改为产物二进制符号表直查
> （nm/readelf 兜底：shmget/shmat/shmctl/getDB/FreeDynablock —— 全部为
> v0.4.4 与最新 main 均稳定存在的符号，不使用版本敏感符号防跨 ref 误报）；
> pre_setup 删预生成清单操作保留（确定性无害）但注释勘误。
>
> **框架（build-rootfs-arch.sh）**：cmake/meson/autotools 三处 install()
> 调用补 `|| { err; exit 1; }` fail-fast（与 pre_package 的 v3.1.5 修复
> 同款）—— 此前 install() return 1 被忽略，CI 绿灯发布带错误字样的残废包。
>
> ## 新增 3：start.mp4 启动动画（3 秒 / 可关闭 / 可自定义）
>
> - **播放**（`core/boot/StartupAnimation.kt`）：App 冷启动全屏播放
>   `assets/anwind/boot/startup.mp4`（项目根 start.mp4 打入 APK）。用户
>   明确要求"5 秒太长加速为 3 秒"：按 duration/3s 倍速播放
>   （MediaPlayer `PlaybackParams.setSpeed`，内置 5s 视频约 1.667 倍速）；
>   自定义视频超 3 秒同样加速收敛到 3 秒内。点击任意位置跳过；3.6 秒硬
>   保底看门狗（视频损坏/解码失败静默直进桌面）；280ms 淡出转场
>   （TextureView 而非 SurfaceView —— 后者独立合成层会盖住 alpha）。
> - **一次性**：`AnWindApp.bootAnimationPlayed` 进程级标记 —— Activity
>   旋转/重建不重播，冷启动重新播放。
> - **设置**（设置→个性化→启动动画）：「播放启动动画」开关（DataStore
>   `boot_animation_enabled`，默认开）+「自定义启动动画」（文件资源管理器
>   pickMode=boot_video 选视频，持久化 `custom_boot_animation`）+「恢复默认」。
> - APK 打包：`noCompress` 增加 mp4（openFd 播放要求未压缩存储）。
>
> ## 新增 4：DXVK 资产注入 + 容器组件配置 + DllOverrides 注册表
>
> **DXVK 自动注入**（用户约定 `assets/dxvk/dxvk-2.3.1.tar.gz` →
> `C:\windows`）：
> - 资产源扩展：`assets/dxvk/` 目录（`.tar.gz`/`.tgz`/`.tzst`/`.tar.zst`，
>   TarCompressorUtils 新增 GZIP 类型支持）+ 旧契约 `assets/anwind/dxvk-*.tzst`
>   并存合并应用；包内布局自动探测（system32+syswow64 直解 / 上游 x64+x32
>   重映射 / 单根下钻 / 裸 dll→system32）。
> - **注入时机**：前缀构建（wineboot）完成后立即注入（RootfsLauncher 启动
>   链第 8 步新增）；App 启动/进容器界面自动补齐（指纹幂等，资产更新重铺）。
> - **容器开关**：创建/编辑容器对话框「图形与 Wine」新增「DXVK 图形加速」
>   药丸（`dxvk=auto` 开 / `dxvk=off` 关，默认开；容器组件配置至此全覆盖：
>   box64/fexcore（后端）、mesa（渲染器）、dxvk（图形加速））。注入成功回写
>   container.conf 版本戳 + `dxoverrides=`。
>
> **DllOverrides 注册表**（用户提供的 147 项配置逐字转换）：
> 前缀构建时注入 `HKCU\Software\Wine\DllOverrides`（d3d9/dxgi/wined3d 等
> native 优先、xinput/quartz/dinput builtin 优先），单次 `wine regedit /s`
> 导入（与 ensureModernLook 同模式），幂等标记 `.anwind-dlloverrides`，
> 失败不阻断启动链。native,builtin 语义下未注入 DXVK 的容器自动回退 wine
> 内建，无副作用。
>
> **涉及文件**：`0007f-*.patch`、`hangover-wine/build.sh`、`box64/build.sh`、
> `build-rootfs-arch.sh`、`StartupAnimation.kt`（新）、`MainActivity.kt`、
> `AnWindApp.kt`、`SettingsStore.kt`、`SettingsApp.kt`、`FileExplorerApp.kt`、
> `ContainerModels.kt`、`ContainerScreens.kt`、`WineRegistry.kt`、
> `RootfsLauncher.kt`、`AnWindTzstAssets.kt`、`TarCompressorUtils.java`、
> `assets/anwind/boot/startup.mp4`（新）、`assets/dxvk/`（新）、
> `app/build.gradle.kts`（noCompress + 版本 76/2.38.13）。
>
> ---
>
> **v3.2.13（2026-11-02）**：五诉求产品轮 —— ①三窗口 UI 重做（容器新建/编辑、容器管理主窗口、X11 设置）②运行 exe 加文件管理器选择 ③文件管理器双击 exe/bat 调出「选择容器运行」④全游戏画面抖动修复（mesa/X11 显示链）⑤bionic box64 构建修复（启动零日志）
>
> ## 诉求 1：容器创建/编辑、容器管理主窗口、X11 设置窗口 UI 重做
>
> **旧形态**：三个窗口共用两种"丑"—— 容器新建/编辑是十来行 FilterChip
> 单行横滚的纵向长条（标签互相挤压、关联选项不相邻、无分组）；容器卡片
> 是"标题 + 一整行摘要串 + 六个按钮"；X11 设置是 340dp 窄窗里的小字
> 文本按钮排。
>
> **新形态**（三窗口同一视觉语言，`ContainerScreens.kt` / `X11SettingsPanel.kt`）：
> - `WinSectionCard` 分区卡片：左侧强调条 + 标题 + 副标题 + 圆角内容区。
>   新建/编辑容器按「基本信息 / 运行环境 / 显示与分辨率 / 图形与 Wine /
>   语言与音频 / 高级」六分区组织；X11 面板按「显示 / 窗口 / 键盘 / 触摸 /
>   剪贴板 / 操作」六分区组织；
> - `WinOptionPills` 药丸组（FlowRow 自动换行）替代 FilterChip 横滚行；
>   X11 面板用连体分段控件（`XpSegmented`：整组一个圆角边框，选中项
>   强调色填充）替代文本按钮排；
> - 容器卡片：🍷 图标 + 名称行（★ 默认 / ● 运行中 / 创建时间）+ 六格
>   信息徽章（后端/显示/渲染/wine/音频/图形API，`WinInfoBadge`）+ 精简
>   按钮行（运行 exe / winecfg / 日志 / 运行中才显示的停止 / ⋯）——
>   前缀日志、regedit、wineboot、编辑、克隆、删除收进 ⋯ 菜单；运行中
>   容器整卡加强调色描边；
> - 主窗口标题行：图标 + "N 个容器 · 运行中 M" 徽章；
> - X11 面板 340 → 420dp；顶部"当前 X 屏幕"升级为状态卡（游戏握手时
>   强调色高亮）。
>
> ## 诉求 2：「运行 exe」窗口缺少文件管理器直接选择 exe/bat（含路径）
>
> **修复**：`RunContainerDialog` 程序路径右侧新增「浏览…」按钮 → 拉起
> 文件资源管理器（新增 `pickMode=exe`，直达 Download → AnWind → 内部
> 存储根）→ 点选 exe/bat/msi/cmd/com/scr/lnk → 完整路径（含目录）经
> `FilePickBus`（记事本/媒体播放器同一条选择总线）回填本窗口的程序
> 路径输入框。Unix 绝对路径直接可用（`resolveExeWorkingDir` 已支持，
> CWD 自动切到 exe 所在目录）。
>
> ## 诉求 3：文件管理器（此电脑）双击 exe/bat 调出选择框，选择容器运行（含分辨率/窗口设置/启动参数）
>
> **旧形态**：`openRealFile` 对 .exe 落到系统 `ACTION_VIEW` Intent ——
> 必然"未找到可打开 .exe 文件的应用"。
>
> **修复**：新增 `ExeRunChooserApp`（`exe_run_chooser`，已注册
> AppBootstrap）。双击（网格/列表单击同效）exe/msi/bat/cmd/com/scr/lnk
> 打开「选择容器运行」窗口：
> - **选择容器**：容器单选列表（名称 + ★默认 + ●运行中 + 后端/显示/
>   wine 摘要，默认容器预选，切换容器时回填其显示配置）；
> - **分辨率设置**：虚拟分辨率药丸组（跟随窗口 + 10 档预设）；
> - **窗口设置**：显示模式药丸组（跟随窗口 / 固定分辨率(-d) / 虚拟桌面(-v)
>   / 窗口直出(-f)），与容器编辑同款联动（选非"跟随窗口"分辨率自动切
>   固定分辨率）；
> - **启动参数**：自由文本透传。
> 运行走 `container.copy(dmode=…, screen=…)` 的**会话级覆盖**（不回写
> container.conf，单次运行参数不污染持久配置），原生链路自动拉起 X11、
> 初始化前缀。
>
> ## 诉求 4：所有 wine 游戏画面移动一抖一抖（mesa 驱动 + X11 显示逻辑）
>
> 三处修复（`WineEnvironment.kt` / `X11Surface.kt`）：
> 1. **撤销 aarch64 无条件 `TU_DEBUG=noconform,sysmem`**：`sysmem` 强制
>    Turnip 把 buffer 对象落在系统内存 —— GPU 访存带宽骤降，滚屏/镜头
>    移动持续掉帧，是"全游戏都抖"的系统性放大器。现 zink 档（Turnip
>    真正参与时）仅保留 `noconform`（崩溃规避），sysmem 一律不设；
>    经典 freedreno GL 路径完全不读 TU_*；
> 2. **softpipe 软渲染兜底补 `vblank_mode=0`**：软渲染帧率本就低于屏幕
>    刷新率，GLX 交换再等 vblank 只会引入 0~1 帧随机延迟（节奏抖动的
>    直接来源）；关闭后按最快节奏上屏；
> 3. **LorieView Surface 帧率对齐**（API 30+ `setFrameRate(FIXED_SOURCE)`）：
>    把 X11 桌面 Surface 的帧率显式钉到当前显示刷新率，引导
>    SurfaceFlinger 以该节奏做合成调度，降低 PRESENT 上帧与合成时刻的
>    相位抖动。
>
> ## 诉求 5：bionic box64 构建问题（启动时没有任何日志 = 构建根本不对，无法启动，冒烟测试不对）
>
> 五处修复（配方 + CI + App 三层）：
>
> ### 5a. box64 wrapper 再生成 mtime 竞态（配方根治）
>
> **根因**：box64 仓库把 `src/wrapped/generated/functions_list.txt`（及
> wrapper.c）**预生成后随源码提交**，且提交版是**无 ANDROID 特性位**的
> 生成物（v0.4.4 实测 7651 行，无 shmget/androidshmem）。CMake 的
> `add_custom_command(OUTPUT functions_list.txt)` 按 mtime 判断是否重跑：
> git clone 后全部文件 mtime 都是"现在"，是否重跑纯看检出写入顺序 ——
> 若 `functions_list.txt` 恰好不旧于 `wrappedandroidshmem.c`，make 跳过
> 再生成 → 构建出的 box64 **wrapper 表缺 Android shm 系列**（wine 的
> X11/unix 侧大量依赖 SysV shm）→ 运行时符号解析错乱、加载即死、
> 零输出 —— 与"启动时没有任何日志、无法启动"完全同形。
>
> **修复**：`bionic-rootfs/projects/box64/build.sh` 的 `pre_setup` 删除
> 预生成的 `functions_list.txt`（OUTPUT 缺失 = make 无条件执行再生成，
> ANDROID 特性位随 CMake 命令行带入）。
>
> ### 5b. `NOLOADADDR=ON`（对齐 Winlator/MiceWine）
>
> 上游默认经 lld `--image-base=0x34800000` 钉低地址（桌面 glibc 手法）。
> Android linker64 对 PIE 不遵守 image-base（ASLR 随机化加载基址），
> 该参数设备上零收益，低地址段在应用进程布局里反而与 zygote/heap 常驻
> 区相邻、存在加载即死风险。Winlator（glibc 链）与 MiceWine（NDK
> bionic 链）**均显式 NOLOADADDR=ON**，本配方对齐。
>
> ### 5c. 冒烟测试重做（用户点名"冒烟测试估计就不对"）
>
> - **配方 install() 三道 ANDROID 专项冒烟**：① `functions_list.txt`
>   必须含 shmget（wrapper 按 ANDROID 特性位再生成的直接证据）；
>   ② 未 strip 二进制符号表必须含 `shmget` + `getDB`（ANDROID shm
>   包装 + DYNAREC 编译单元链接进来了）③ 链接命令不得残留
>   `--image-base`/`-no-pie`（NOLOADADDR 生效确证）。
> - **CI 源码级实跑冒烟**（build-box64-bionic.yml 新步骤）：同 ref
>   独立 clone + 宿主 x86_64 构建（上游 CI 同款 CI=ON）→ 实跑
>   `box64 --version`（断言版本输出）→ 编译最小 x86_64 测试程序（含
>   SysV shmget/shmat/shmdt 路径）经 box64 执行断言输出 ——
>   "能链接出来但根本跑不了"的坏产物从此过不了 CI。
>
> ### 5d. App 侧日志与 rc 修复
>
> - `BOX64_LOG` 0 → 1：旧值 0 + `NOBANNER=1` 把 box64 的全部日志
>   （含错误报告）整体静音 —— box64 加载失败时会话日志一行不吐，正是
>   "没有任何日志"的直接成因。LOG=1 只出 error 级，正常启动依旧安静；
> - 新增 `BOX64_ENVFILE` 指向 rootfs 的 `/usr/etc/box64.box64rc`：
>   上游 box64 只读 `/etc/box64.box64rc` 或 `/data/data/com.termux/…`
>   两个内置路径（v0.4.4 env.c 实证），rootfs 内 rc 一直是死文件 ——
>   `BOX64_ENVFILE` 是上游官方入口（InitializeEnvFiles 首优先级）。
>
> ### 5e. 体检（doctor）box64 运行探活
>
> 旧体检只查 `canExecute()` —— "构建根本不对、加载即死"的 box64 一样
> 全绿。现真实 exec `box64 --version`（waitFor 5 秒限时，挂死按失败
> 处理）：成功 → 版本串进详情；失败 → 退出码 + 前 3 行输出（或"无任何
> 输出 —— 动态链接失败或加载即崩"）+ 取证指引直接给用户。体检整体移
> IO 线程（加载态提示，探活不再卡 UI）。

> **v3.2.12（2026-11-02）**：三阻断修复轮 —— APK 编译失败（Kotlin 空安全）+ official-9.7 arm64ec 构建新阻断（wbemprox/mshtml）+ i386 编译趟既有阻断（0007 __fastfail x86 分支误留 ARM64 代码）
>
> ## 问题 1：APK 构建报错 `Only safe (?.) or non-null asserted (!!.) calls are allowed`（WineRegistry.kt:268/270/274/276）
>
> **根因**：v3.2.11 新增的 `findModernTheme`（wine 窗口现代化）里四处
> `listFiles()?.orEmpty().firstOrNull { … }` —— Kotlin 把 `a()?.b().c()` 解析为
> `(a()?.b()).c()`：`?.` 之后整条链仍是**可空类型**，后面裸调 `.firstOrNull`
> 违反空安全。`orEmpty()` 本身就是可空扩展函数（`Array<out T>?.orEmpty()`），
> 不需要 `?.` 前缀。v3.2.11 的 APK 因此**从未编译通过**，四个报错行与
> CI 日志逐字一致。
>
> **修复**：四处改为 `listFiles().orEmpty().firstOrNull { … }`（其余
> `?.filter ?: emptyList()` 等既有用法不受影响，全文件复查无同类问题）。
>
> ## 问题 2：official-9.7 arm64ec 构建仍失败（wbemprox `__cpuid` + 本地预判的 mshtml 汇编）
>
> 1. **实证阻断**：CI 日志（wine-build-log-arm64ec (9).zip）唯一错误：
>    `dlls/wbemprox/builtin.c:3391: call to undeclared function '__cpuid'`
>    （arm64ec-windows/builtin.o）。wine-9.7 的 `do_cpuid` 用
>    `#if defined(__i386__) || defined(__x86_64__)` 分派，而 llvm-mingw
>    arm64ec 趟同时预定义 `__arm64ec__` 与 `__x86_64__`（clang 21
>    AArch64Targets.cpp：EC 定义 `__x86_64__`/`__amd64__`/`__arm64ec__`，
>    **不定义** `__aarch64__`——wine 自己的 include/msvcrt/intrin.h 都有
>    `&& !defined(__arm64ec__)` 排除，wbemprox 没同步）→ EC 趟进 x86 分支，
>    `__cpuid` 内建函数在 EC 目标不存在 → 隐式声明即炸。
> 2. **预判阻断**（修 wbemprox 后的下一个必炸点）：`dlls/mshtml/ifacewrap.c`
>    的 `DEFINE_WRAPPER_FUNC` 分支链只有 i386/x86_64 两档 + C 存根
>    `#else`，无任何 ARM/EC 分支 → EC 趟激活 `movq 8(%rcx),%rcx` x64
>    AT&T 汇编，ARM64 代码生成器连报 21 errors。
>    **验证方法**：下载 CI 同款 llvm-mingw-20251202（clang 21.1.7）+
>    wine-9.7 全补丁树本地 configure（`--enable-archs=arm64ec,aarch64,i386`）
>    + 逐目标编译：修复前 builtin.o/ifacewrap.o 双双失败（与 CI 报错
>    逐字一致），修复后通过。
>
> **修复**：新增 `0007e-fix-arm64ec-wbemprox-mshtml-wine97.patch` —— 两处
> `__x86_64__` 守卫均追加 `&& !defined(__arm64ec__)`，EC 趟落 `#else`：
> wbemprox 走 FIXME 空桩（与原生 ARM 趟一致，WMI CPUID 数据本就不采集）、
> mshtml 走 C 存根（E_NOTIMPL，与 aarch64-windows 趟一直编译的分支相同）。
> wine-9.2 两文件与 9.7 逐字相同（diff 实证）一并受益；wine-10+/hangover
> 树形态不同锚点不命中，静默跳过。**全量核查**：wine-9.7 全树 3541 个
> arm64ec-windows 对象目标逐一编译扫描 + 30 个高危对象（vcomp/math/
> string/ndr_stubless/cstub/cproxy/loader/debug/module/thread/relay/
> setjmp/handler4/vartype/typelib/device×2/process/unwind/cpu_x86_64/
> dinput 等）复测无回归。build.sh 同步加 0007e 生效校验（锚点漂移时
> 1 秒内报清根因，不再烧 28 分钟 CI）。
>
> **附注**：本轮复核更正一处旧注释 —— 0007d 头注称"EC 趟落 __aarch64__
> 分支"，实测 clang 的 arm64ec 目标**不定义** `__aarch64__`（0007 头注
> 才是对的）；实际落点是链尾 `#else` 的 `#warning` C 存根（编译通过，
> RPC 无 ThunkTable 的服务端分发在 EC 形态暂为空桩 —— 与 wine-9.x EC
> 实验形态定位一致，上游 wine-10 已用 thunks.c + arm64ec_x64 趟根治）。
>
> ## 问题 3：i386 编译趟阻断 —— 0007 的 `__fastfail` x86 分支误留 ARM64 代码（v3.2.4 起既有 bug）
>
> **根因**：0007 补丁（v3.2.4 起）重排 `__fastfail` 分支（aarch64/arm64ec
> 前置）时，x86 分支（`#elif __x86_64__ || __i386__`）的体误留了 ARM64
> 代码（`x0` 寄存器 + `brk #0xf003`）而丢失上游的 `int $0x29`。hangover
> 配方 `--enable-archs=arm64ec,aarch64,i386` 的 i386 编译趟里，
> `__fastfail` 真实调用者（wbemprox/mshtml/ntdll/wined3d 等）必炸
> `unknown register name 'x0' in asm`（llvm-mingw-20251202 本地实证）。
> 此前一直未暴露：arm64ec/aarch64 趟走前置首分支不受影响，而编译趟
> 顺序 arm64ec 在 i386 之前 —— v3.2.11 及更早都被问题 2 的 __cpuid/
> x64 汇编错误挡在 arm64ec 趟，从未走到 i386 趟。
>
> **修复**：直接修正 0007 本体的该 hunk —— x86 分支恢复 `int $0x29`
> （与 wine-11.16 上游形态逐字一致）。**为什么不能追加到 0007e**：CI
> 的 locale 排序会把 0007c/d/e 系列排在 0007 之前应用（上轮 diff -rq
> 已证明两种顺序最终树相同 —— 因为此前所有 hunk 锚点均为纯净形态、
> 顺序无关），而 __fastfail 修复 hunk 的上下文是"0007 已应用后的形
> 态"，追加式 hunk 在 CI 顺序里永不命中；修复内联在 0007 里则与顺序
> 无关。另复核 0007 全部 hunk：这是唯一一处改动词句体（非守卫）的
> hunk，i386 风险面就此一处。
>
> **受益范围**：0007 的 winnt.h 三 hunk 实测同时命中 official-9.2/
> official-9.7/proton_10.0 树（proton_10.0 的 i386 趟同样被此 bug
> 阻断）；hangover-11.16 树原生即为正确形态且 hunk 全不命中，不受影
> 响。build.sh 加 mangled 形态校验（`#elif __x86_64__ || __i386__` 后
> 跟 `__asm__("x0")` 即报错）且不限 arm64ec 形态 —— 降级 aarch64,i386
> 形态同样有 i386 编译趟。
>
> **验证**：wine-9.7 全补丁树双顺序（C / locale collation）应用复核最
> 终形态一致且正确；__fastfail 修复后 i386/x86_64 分支 gcc -m32/-m64
> 语法级编译通过（ARM64 分支上轮已随 3541 个 EC 目标全量扫描验证）。

> **v3.2.11（2026-11-02）**：三诉求修复轮 —— 容器误删根治 + wine 9.7/proton 10 arm64ec 构建双修复 + wine 窗口现代化
>
> ## 问题 1：点三个点删除一个容器，结果所有容器都被删了
>
> **根因**：全仓唯一能"一次删光"的向量是 `name` 为空串时
> `File(CONTAINERS_ROOT, "")` 解析为**容器根本身**——`deleteRecursively()`
> 连根删光全部容器。空名容器由 `ContainerData.fromConf` 旧逻辑
> `kv["name"] ?: 目录名` 产生（`name=` 键**存在但值为空**时 `?:` 不兜底，
> 旧版写入中断/手工编辑/历史版本遗留均可触发）；同名缺陷也在 CLI
> `cmd_remove`（空名时 `[ -d "$CONT_ROOT/" ]` 判真 → `rm -rf` 删光）。
> 附带发现：重名容器（conf name= 与目录名不一致）会直接崩 LazyColumn
> 的 key 去重断言。
>
> **修复（三层防御，双端一致）**：
> 1. `ContainerData.fromConf` 改为**目录名优先**（容器身份 = 目录名，
>    conf 内 `name=` 仅为冗余字段；`update()` 下次保存自动归一）——
>    从源头消除空名/重名容器；
> 2. `ContainerManager.remove` 拒空名/含 `/`、`\`/`.`/`..`，并校验解析后
>    目录必须是容器根的**直接子目录**（canonical 路径双保险，杜绝符号
>    链接逃逸）；
> 3. CLI `cmd_remove` 同款 case 防御（`""`/`.`/`..`/非法字符/含 `/` 全拒）
>    + `rm -rf --`。
>
> ## 问题 2：官方 wine 9.7 与 proton 10 的 arm64ec 构建仍失败
>
> 两份 CI 日志（wine-build-log-arm64ec (6)/(7).zip）各只有一个阵亡点：
>
> 1. **official-9.7**：`dlls/rpcrt4/arm64ec-windows/ndr_stubless.o` ——
>    `call_server_func` 的 x64 AT&T 内联汇编（pushq/movq/.seh_pushreg）在
>    EC 编译趟误激活。wine-9.7 的 ndr_stubless.c 已被上游重构：四处入口
>    （NdrClientCall2/3/AsyncClientCall/Ndr64AsyncClientCall）自带
>    `__arm64ec__` naked 分支，0007（9.2 锚点）5 hunk 全失配属预期良性；
>    唯 call_server_func 四分支（i386/x86_64/arm/aarch64）无 EC 排除——
>    llvm-mingw arm64ec 同时预定义 `__x86_64__` 与 `__aarch64__` → x64
>    汇编喂进 ARM64 代码生成器即炸。
>    **修复**：新增 `0007d-fix-arm64ec-x64-asm-wine97-ndr.patch` ——
>    `#elif defined __x86_64__` → `#elif defined __x86_64__ && !defined
>    __arm64ec__`（0007 对 wine-9.2 的同款手法），EC 趽落 aarch64 分支
>    （4 个入参全在 x0-x3，两约定映射一致）。锚点为 9.7 四参签名（含
>    `const NDR_PROC_HEADER_EXTS *ext`），与 0007（9.2 三参）、wine-10+
>    （asm 已迁 thunks.c + `#pragma makedep arm64ec_x64`）天然互斥，
>    双向应用顺序（byte/en_US collation）均本地实测通过。
> 2. **proton_10.0**：`dlls/wow64/process.c:1095` "call to undeclared
>    function 'RtlWow64SuspendThread'" —— v3.2.10 的 0011 补丁改了
>    process.c 调用点、ntdll.spec 导出与 signal_arm64{,ec}.c 定义，却漏了
>    **声明**（wow64 是独立 PE dll，wine-9.x/10 树无任何公共头声明该函数；
>    wine-11.16 vanilla 起才有 include/wow64.h）。signal 双锚点 hunk 经
>    本地模拟确认"各命中其一"（fuzz 吸收 proton 树注释风格漂移，定义已
>    落树），非失败即成功。
>    **修复**：0011 的 process.c hunk 内就位
>    `extern NTSTATUS WINAPI RtlWow64SuspendThread(HANDLE, ULONG*);` ——
>    同一 hunk 原子生效；official-9.7 同样受益（其 process.c 也命中该
>    hunk，修复 ndr 后必炸同点）；wine-11/hangover-11.16 因调用点 hunk
>    本就不命中而天然互斥。
>
> **补丁工程加固**：本轮发现编辑工具会把补丁文件里的 TAB 上下文误转
> 空格、丢 diff 标记空格，导致 fuzz 余量超限/静默失配——0007d/0011 全部
> 经字节级脚本编辑 + `git diff` 逐行核对（TAB 行、hunk 计数头
> `@@ -1089,13 +1089,20 @@`）+ 本地三树模拟（wine-9.7 双向顺序 /
> proton_10.0 分段 / wine-11 空壳互斥）验证后才入库。
>
> ## 问题 3：美化 wine 创建的所有窗口
>
> **方案**：`WineRegistry.ensureModernLook`（启动链 EmulateModeset 后接入，
> 单次 `wine regedit /s` 导入 + 前缀内 `.anwind-modern-ui` 幂等标记，
> 失败下次自动重试、不阻断启动）。五件套：
>
> 1. **ClearType 字体平滑**（wine 默认全关，文本锯齿感停留在 Win95）：
>    FontSmoothing=2 / Type=2（RGB 子像素）/ Gamma=1000 / Orientation=1；
> 2. **拖动显示窗口内容**（默认 DragFullWindows=0，拖动只画虚线外框）；
> 3. **light.msstyles 扁平现代主题**（wine 9.x+/10 前缀自带，wine.inf 默认
>    即激活；本修复顺带归一用户手动调回经典/老前缀缺失的场景；探测大小写
>    不敏感，aero 兑底，两者皆无则跳过主题键）；
> 4. **现代配色**：选区高亮/热跟踪用 Windows 10/11 强调蓝（0 120 215 /
>    0 102 204），桌面兜底色柔和深蓝灰（47 54 68）；
> 5. **程序化渐变壁纸**：1600×900 24bit BMP（深空蓝→湖蓝垂直渐变 + 右上
>    辉光 + 底部地平线微光，纯像素数学无外部依赖；视觉评审无色带无瑕疵），
>    写入 `drive_c/anwind/wallpaper.bmp` 并注册 Wallpaper/WallpaperStyle=2。
>    explorer 虚拟桌面（dmode=v / 前缀构建）经 wine 自带壁纸管线绘制
>    （BMP 为 wine 唯一保真格式）；渲染不出时仅显示第 4 项底色，无副作用。
>
> 作用域说明：以上作用于 **wine 自绘窗口**（wineboot 进度框、winecfg、
> regedit、notepad、explorer 桌面、文件公共对话框、消息框与老式 Win32
> 程序的控件）；游戏/现代应用的自绘皮肤不在此列（那些由应用自己渲染）。
>
> ## 验证建议
>
> 1. App 2.38.10：新建 → 删除单个容器，其余容器完好；终端
>    `anwind-container remove` 空名/`..` 被拒；
> 2. CI official-9.7 arm64ec：ndr_stubless 编译通过（0007d 生效后无
>    "invalid operand pushq" 连片报错）；proton_10.0：wow64/process.c
>    编译通过（0011 声明就位）；
> 3. 前缀构建完成后第二次启动任意 wine 程序（winecfg 最直观）：文本
>    变 ClearType、按钮/对话框走 light 主题、选区变强调蓝、虚拟桌面
>    显示渐变壁纸。

> **v3.3.1（2026-11-01）**：proton arm64ec 前缀构建"无任何输出挂死"根治
>
> ## 根因（2026-11-01 设备会话日志 + proton_10.0 源码逐行核实）
>
> **冻结/挂死的残留 wineserver 守护使新客户端永久阻塞在握手。**
>
> 证据链（用户会话日志，wine-proton / aarch64 / arm64ec）：
>
> 1. 客户端打出 `anwind: bionic heap tagging disabled (TBI 0xB4
>    free-check off)`（0006 补丁插在 `__wine_main` 第一行）之后**戛然
>    而止**——无输出、无退出码、system.reg 永不落盘；
> 2. **wineserver 侧的 heap-tagging 打印（0006 的 server/main.c hunk，
>    插在 wineserver main() 第一行）从未出现**—— wineserver stderr 经
>    fork/posix_spawn 链继承自客户端，会照常进会话日志。它不出现 =
>    **本次会话根本没有拉起新 wineserver**：客户端在 `server_connect()`
>    发现 socket 文件已存在（上游 wine 10 布局：Android 构建的 server
>    目录为 `$WINEPREFIX/.wineserver/server-<dev>-<ino>`），直接 connect
>    成功（内核 backlog 对"活而冻结"的监听 socket 照样放行）后阻塞在
>    `receive_fd()` 的 `recvmsg()` 上，永久无响应；
> 3. wineserver 守护在 `server/request.c open_master_socket()` 里
>    `setsid()` 脱离会话树后自管生命周期——**它不在 App 会话执行器的
>    进程组里**。上次挂死会话被停止 / Android cgroup freezer 冻结后台
>    应用全组时，它以"socket 仍在监听、但进程永不应答"的形态残留；
> 4. `wineserver -k` 救不了它：-k 客户端同样阻塞在这个 socket 上直到
>    App 的 12s 超时把它杀掉，冻结的守护毫发无损——**于是"挂死一次 =
>    每次重试都挂在同一处"**（用户日志里 X11 与 PulseAudio 的陈旧
>    socket 都被 App 清理了，唯独 wineserver 没有任何清理机制——
>    手动 wineboot 会话 freshSession=false 连 -k 都不跑）。
>
> 同设备 hangover-11.16 arm64ec 正常的原因：它跑在**别的容器/前缀**上，
> 各前缀 server 目录互相隔离——被毒化的只是出问题前缀的 `.wineserver`。
>
> ## 修复（三层，App 侧即刻生效、无需重编 wine）
>
> 1. **`WineServerUtils` 三段式清理**（`-k`/`-w` 优雅停止 → **/proc 扫描
>    SIGKILL 残留守护** → 删除 `.wineserver` 残留 socket/lock）。识别判据：
>    cmdline 含 wineserver **且** `/proc/<pid>/cwd` 位于
>    `$WINEPREFIX/.wineserver/` 下 —— 守护启动时 chdir 进 server 目录且
>    永不离开，是「属于本前缀」的权威特征；他 UID 的 /proc 条目读取即
>    失败天然过滤。绝不能用「cmdline 含 wine 目录」兜底 —— 同 wine 安装
>    可同时跑多个容器，那会误杀别的前缀的活 server。幂等；全新前缀
>    （无 .wineserver 目录）零成本。
> 2. **`RootfsLauncher`**：wineboot（前缀构建）会话**无条件**清理（旧版
>    仅 freshSession 清，工具会话完全不清 = 挂死循环的温床）；挂死
>    看门狗升级为 **4 分钟取证 + 8 分钟自动恢复**（终止会话 + 硬清
>    wineserver + 明确指引，旧版只取证不处置，手动 wineboot 路径连
>    超时都没有）。
> 3. **`WineRegistry`**：wineboot 两次尝试之间改用三段式硬清（旧版仅
>    -k/-w，冻结守护杀不掉，重试必再挂）；挂死现场抓取新增 wineserver
>    cwd 与 `.wineserver` socket/lock 状态（证据面升级：socket 在而守护
>    无响应 = 握手阻塞的铁证）。
>
> ## 构建侧兜底（重编 wine 包后生效）
>
> 新增 `0008-android-wineserver-handshake-timeout.patch`（hangover-wine
> 与 wine 两配方同步放置，proton_10.0 源码实测干净套用、幂等）：
> - `server_init_process()` 握手 `receive_fd()` 前给 fd_socket 设
>   **SO_RCVTIMEO=60s**、握手完成立即清零（只影响首次握手，后续正常
>   server 通信零影响）；
> - `receive_fd()` 对 EAGAIN（超时）给出可操作致命错误（删
>   `.wineserver` 或 `wineserver -k` 后重试），替代笼统的
>   "recvmsg: Resource temporarily unavailable"。
>
> 即使在旧 App / 纯终端用法下，冻结守护导致的挂死也会在 60 秒内转为
> 可见错误退出，不再永久静默挂死。
>
> ## 终端链路同步
>
> `anwind-wine` 脚本（assets 与 rootfs 两份同步，v2.12 → v2.13）：
> `run_wine` 入口新增 `clean_stale_wineserver`（同款 /proc 扫描 +
> SIGKILL + 残留清理；无 .wineserver 目录零成本）。
>
> ## 验证
>
> 1. 在原挂死容器上直接重跑"构建前缀"（App 2.38.8）——会话日志应出现
>    `检测到残留 wineserver（pid=…）… SIGKILL 清理` 决策行，随后
>    wineserver 侧 heap-tagging 打印出现（= 新 server 真正被拉起），
>    wineboot 正常推进、system.reg 落盘；
> 2. 若 8 分钟仍无输出：看门狗会自动恢复并把 wineserver cwd + socket
>    状态现场写进日志——把该日志反馈即可精确定位剩余问题；
> 3. 终端：`anwind-wine --container <名> wineboot -i` 应看到同款清理行。

> **v3.1.6 增量（2026-10-29）**：修复 v3.1.5 三线 CI 失败 + D3D 软渲染回退 + 前缀日志 UI
>
> ## CI 三线失败根因（v3.1.5 日志实锤）
>
> 1. **hangover-10.18 两形态**：wine 10.17+ 上游改了安装布局 —— loader
>    装进 `lib/wine/<arch>-unix/`，`bin/` 只留 12 个指向 `wine` 的悬空
>    符号链接、**bin/wine 本体不再创建**（构建日志：`install -L wine`
>    12 次、无任何 bin/wine 行；hangover-11.16 对照组正常装 bin/wine）。
>    CI 步骤 8 校验 `bin/wine` 必 MISS。**修复**：两个 wine 配方
>    （wine / hangover-wine）的 pre_package 新增"loader 物化"——
>    lib 侧 loader 存在而 bin/wine 缺失/为符号链接时，物化为真实副本
>    （含 wine-preloader），旧布局不受影响。
> 2. **proton10 x86_64**：步骤 8.5a 关键库校验硬编码 glibc 风格版本名
>    （libpulse.so.0 / libasound.so.2 / libX11.so.6），但 rootfs 的
>    bionic 库为无版本安装（构建日志实证 `Installing src/pulse/
>    libpulse.so`、libtool `-avoid-version` 的 libasound.so / libX11.so）
>    → 校验必然 MISS 退出。**修复**：校验改"任一形态存在即通过"
>    （glob 匹配 libpulse.so* 等），wine 的 DT_NEEDED 本就指向 configure
>    期探测到的同一名录，存在性即可靠性。
>
> ## 运行时修复（设备侧）
>
> 3. **D3D "无兼容 Direct3D 设备"（默认 wine 跑 3D 样例）**：rootfs
>    mesa（aarch64: softpipe,freedreno,zink；x86_64: softpipe —— 交叉
>    构建不带 LLVM，**llvmpipe 从未构建**）。两处连锁错误：
>    a) 容器渲染器选 "LLVMpipe" 时 GALLIUM_DRIVER=llvmpipe = 无效驱动名
>    → GL 初始化失败；b) auto 档盲选 freedreno，而 freedreno 依赖
>    /dev/dri 渲染节点（Android 应用沙箱在多数设备无权打开）→ GL
>    整体失败 → wined3d 枚举不到适配器。**修复**：llvmpipe 一律归一
>    softpipe（旧容器值自动归一）；auto 档先探 /dev/dri 可达性
>    （renderD128/card0/kgsl-3d0），可达才 freedreno，否则
>    softpipe + LIBGL_ALWAYS_SOFTWARE=1（软渲染必出画面）。
> 4. **X11 跟随窗口"画面缩在左上角"**：握手文件早于 LorieView 挂载
>    写入时，apply() 的 regenerate() 打不到任何视图（activeView 为
>    null），X server native 端从未收到 sendWindowChange → 按旧屏幕
>    尺寸 1:1 绘制在左上角。**修复**：attachView 挂载即重放握手文件
>    并兜底开启 displayStretch；会话从未握手时同样强制拉伸
>    （跟随窗口的设计语义即"拉伸铺满"）。
> 5. **proton10 arm64ec 前缀挂死**：wineboot -i 无任何输出、无退出码
>    （进程永久挂起）。fsync 守卫已确认生效（proton_10.0 的
>    dlls/ntdll/unix/fsync.c 与 server/fsync.c 均含锚点且构建通过），
>    挂点在更早的 wow64 初始化。v3.1.6 三管齐下：
>    a) **前缀构建会话不注入 HODLL64/HODLL**（前缀创建只跑 aarch64
>    PE，仿真器 DLL 的 wow64 早期加载是最大嫌疑风险面；游戏会话照常
>    注入）；
>    b) **wineboot -i 加 10 分钟超时**强杀 + 如实报错（旧版无限等，
>    启动链卡死无反馈）；
>    c) **前缀构建日志窗口**（见下），挂死/报错从此有直接证据面。
>
> ## App UI（用户诉求 3/3b）
>
> 6. **创建容器等弹窗几何修复**：AlertDialog usePlatformDefaultWidth=
>    false + 内容 widthIn(620dp)/heightIn(430dp) + 竖向滚动 —— 旧版
>    表单字段超出弹窗高度被直接裁掉（"没有窗口显示"），横屏尤其严重；
>    新建/编辑/运行/日志弹窗全部适配，日志类弹窗换等宽字体。
> 7. **前缀构建日志（仅限构建前缀）**：新增独立日志通道
>    （RootfsLauncher.prefixLogs + prefixBuildFlow），构建进行中自动
>    弹出「构建 Wine 前缀」窗口逐行滚动显示 wineboot 输出；容器卡片
>    新增「前缀日志」按钮可回看最近一次构建全文。
>
> App 版本 2.38.1 → 2.38.2（versionCode 65）。

> **v3.1.3 增量（2026-10-29）**：修复 v3.1.2 引入的两个 CI 阻断问题
>
> ## 问题 1：rootfs 构建被误判失败（实际 rootfs 完好）
>
> v3.1.2 新增的音频栈校验把 OpenSLES 模块写成了
> `libmodule-sles-sink.so`——但 pulseaudio 上游所有运行时模块**均无
> lib 前缀**（meson `shared_module` + `name_prefix:''`），实际安装产物
> 是 `usr/lib/pulseaudio/modules/module-sles-sink.so`；守护进程加载也
> 是按 `<moduledir>/<模块名>.so` 查找（pulsecore/module.c 的
> `pa_module_exists`/`lt_dlopenext` 均不带前缀）。构建日志里
> `Installing src/modules/module-sles-sink.so` 一行证明模块编译安装
> 全部成功，纯粹是校验文件名写错导致永远 MISS。
>
> **修复**：校验改为 `module-sles-sink.so`；failure-diag 诊断输出新增
> `pulseaudio/modules` 目录实际清单，再出问题一眼可见。
>
> ## 问题 2：wine 构建选 10.15 时源码 404（等 2 小时依赖编完才报错）
>
> AndreRH/wine 的 hangover tag **只在发版里程碑打点**，中间小版本不存在。
> 逐个实测（git ls-remote 权威列表）：
>
> | 源 | 可用版本 | 不可用（常见误选） |
> |---|---|---|
> | hangover | 11.16 / 11.9 / 11.4 / 11.0 / 10.18 / 10.14 / 10.11 / 10.10 / 10.9 / 10.6 / 10.4 / 10.2 / 10.0（更早 9.x、8.x 同理只里程碑有 tag） | **10.15 / 10.16 / 10.17 / 10.19 / 10.20** |
> | official | 任意 wine-X.Y tag（9.0 ~ 11.20） | 无 |
> | proton | 11.0 / 10.0 / 9.0 / 8.0 / 7.0 / 6.3 / 5.13 / 5.0（仅主版本分支） | **10.15 / 10.16**（proton 无小版本） |
>
> 即：想试 10.x 时代的 hangover 用 **10.18**；要精确的 10.15/10.16 用
> **official** 源（注意 official 树无 hangover 的 GLES/mesa 适配补丁，
> 个别 bionic 补丁 hunk 可能漂移，构建系统会容错打警告）。
>
> **修复**：`build-wine-bionic.yml` 参数解析阶段新增**源码 URL 预检**——
> 1 字节 Range 请求探测，404/410 立即失败并把上表可用版本清单打进错误
> 信息，不再白跑约 2 小时的依赖编译；`wine_version` 下拉描述同步改为
> 准确的三源版本清单。custom 源只探测不强判（5xx/超时告警放行）。
>
> 另：日志里的 `tar: .: Cannot utime: Operation not permitted` 为无害
> 噪音——构建系统把依赖包解压到 `/` 时，非 root 用户无法对 `/` 恢复
> mtime，但文件本身全部解压成功，不影响构建与产物。

> **v3.1.2 增量（2026-10-28）**：排查"winecfg 读取不到任何音频（pulse/alsa 全空）"
>
> ## 先纠正一个关键认知：wine 11 没有 winepulse.drv / winealsa.drv 是正常的！
>
> wine 10 起**全部音频驱动转为纯 unixlib**：PE 侧 `.drv` 文件已不存在，
> 真正的驱动是安装目录 `lib/wine/<arch>-unix/` 下的 **winepulse.so / winealsa.so**
> （x86_64 形态在 `x86_64-unix/`，arm64ec 形态在 `aarch64-unix/`）。
> 检查产物时请查 .so，不要查 .drv——查 .drv 永远是"没有"，不能说明问题。
> CI 的"校验 wine 产物"步骤已检查这两个 .so，缺失会直接构建失败。
>
> ## 完整音频链路（缺任何一环 = winecfg 音频页无设备）
>
> ```
> winecfg / 游戏(mmdevapi)
>   → dlopen <wine>/lib/wine/<arch>-unix/winepulse.so     ← wine 包
>      └ DT_NEEDED libpulse.so.0                            ← rootfs（pulseaudio 配方）
>         └ 连 PULSE_SERVER=unix:<rootfs>/usr/tmp/pulse/native
>            └ rootfs pulseaudio 守护（App 原生拉起）
>               └ default.pa: module-sles-sink → Android OpenSLES 出声
> ```
>
> ## 设备侧排查（三条命令定位断点）
>
> ```
> # ① wine 包里的音频驱动（应列出 winepulse.so 与 winealsa.so）
> tar tzf anwind-wine-*-bionic.tar.gz | grep -E "winepulse|winealsa"
>
> # ② rootfs 音频栈（旧版 rootfs 大概率缺这三样 → 这就是根因）
> ls /data/data/com.anwind/files/rootfs/usr/bin/pulseaudio
> ls /data/data/com.anwind/files/rootfs/usr/lib/libpulse.so.0
> ls /data/data/com.anwind/files/rootfs/usr/lib/libasound.so.2
>
> # ③ App 会话日志（容器"Wine 输出"面板 / Download/AnWind/*.log）
> #    看"音频="决策行：
> #    "rootfs 未内置 pulseaudio（旧版 rootfs）" → rootfs 太旧，必无声
> #    "音频=PulseAudio(unix:...)"              → 守护正常，问题在 wine 包
> ```
>
> ## 本轮改动
>
> 1. **build-bionic-rootfs.yml**：rootfs 产物校验新增音频栈强制检查
>    （`bin/pulseaudio`、`libpulse.so.0`、`libasound.so.2`、
>    `pulseaudio/modules/libmodule-sles-sink.so`、`etc/pulse/default.pa`
>    含 module-sles-sink 行）——旧版 rootfs 缺音频栈能直接在 CI 拦下。
> 2. **build-wine-bionic.yml**：新增"音频驱动探测诊断"步骤——把 configure
>    期的 `PulseAudio won't be supported` / `ALSA won't be supported` 告警
>    与正向探测证据直接亮在 Actions 步骤页（此前探测失败只藏在大日志里）。
> 3. **PulseAudioManager.kt**：旧 rootfs 兜底日志修正——明确告知
>    "4713 是死端点、需换新 rootfs"（原文案暗示 App 侧有服务端，误导排障）。
>
> ## 结论：如果设备上 ② 的三个文件缺失
>
> rootfs 是旧版（音频栈进入 projects 列表之前构建的），**重新跑
> "Build AnWind Bionic Rootfs (X11)" 工作流导出新 rootfs 并重新导入**即可。
> wine 包若 ① 正常则无需重编；若 ① 缺 .so，重跑 wine 工作流并查看新增的
> 探测诊断步骤输出。

> **v3.1.1 增量（2026-10-28）**：修复 GitHub Actions `compileReleaseKotlin` 报错
> ```
> XkbExtractor.kt:85:42 Type mismatch: inferred type is Long but Int was expected
> ```
> 根因：`parseOctal()` 返回 `Long`，`ByteArray(size)` 构造器要求 `Int`。
> 修复为 `ByteArray(size.toInt())` 并加 4MB 上限防御（防溢出成负数组）；
> 同时修掉同文件 GNU LongName 分支的**双重 skipPadding** 逻辑缺陷
> （'L' 分支内跳一次 + when 块后统一再跳一次，会把下一个 tar 头吃掉）。
> 详见 `fix-build-errors.patch` 第一段。

解压后将 `AnWind-bionic-all-in-one-v2/` 内的全部内容
（`.github/`、`bionic-rootfs/`、`app/`）直接覆盖合并到 AnWind 仓库根目录即可。

本包为**完整包**（235 个文件）：
- `.github/workflows/` 含修复后的 4 条 bionic 流水线 + 你仓库自有的
  7 条 workflow（原样带上，合并时不会被覆盖丢失）；
- `bionic-rootfs/` 为修复后的完整构建系统（217 个配方/脚本文件）；
- `app/` 附上容器管理 Kotlin 源码（本轮无改动，与已合并版本一致，
  带上只为保证包完整自洽）。

## 本轮修复内容

### 1. host-deps.sh —— 修复 rootfs/mesa/wine 构建在"工具版本"步骤报 127
你贴的报错根因：
```
ERROR: Cannot uninstall PyYAML 6.0.1, RECORD file not found.
Hint: The package was installed by debian.
host-deps.sh: line 93: meson: command not found
```
旧脚本用 `pip install --break-system-packages --upgrade ...` 触发 pip
去"卸载" apt 安装的 PyYAML/pip，而 debian 包没有 RECORD 文件必然失败，
导致 meson/ninja/mako 全都没装上。修复：
- 改用 `--ignore-installed`（直接装新副本、不卸载 apt 管理的包），
  彻底绕开 RECORD 卸载报错；
- 不再 `--upgrade pip`（同样的卸载失败会连带 mako 装不上，mesa 必需 mako）；
- 显式断言 meson / ninja / python mako / libtoolize 可用，缺了立即报错，
  不再"静默失败拖到后面才炸"；
- debianPkgs 补装 libtool / autoconf / automake（libexpat 的
  buildconf.sh 需要 libtoolize 做 autoreconf）。

### 2. build-rootfs-arch.sh —— 消除顶层 `local` 报错
`--arch` 解析处误在函数外使用 `local`（每次运行都报
"local: can only be used in a function"），改为普通变量。

### 3. hangover-wine 配方（arm64ec 形态 Wine）
- llvm-mingw 升级为 **20251202**（arm64ec PE 目标需要较新工具链，
  与你 glibc 流水线 wine-Arm.yml 的验证组合一致）；
- 修正 `--with-mingw` 硬编码路径为变量；
- 源码支持 `ANWIND_HANGOVER_TAG` / `ANWIND_WINE_URL` 环境变量覆盖
  （供工作流下拉选择传入）；
- 新增 pre_package 写入 `.anwind-wine-info`（kind=arm64ec-wow64-x11）。

### 4. build-wine-bionic.yml —— wine 按 x86_64 / arm64ec 双形态重构
- 构建形态改为下拉选择：`both`（默认双形态矩阵）/ `x86_64` / `arm64ec`：
  - x86_64  → `--arch x86_64 --build wine`（unix x86_64 + PE i386/x86_64，
    aarch64 设备经 box64 加载）
  - arm64ec → `--arch aarch64 --build hangover-wine`（unix aarch64 +
    PE arm64ec/aarch64/i386，aarch64 设备原生运行）
- **源码链接改为下拉选项**（不再手填 URL）：hangover-11.16（默认）/
  11.9 / 11.4 / 11.0 / 10.18，均为 AndreRH/wine 实际存在的 tag；
- 产物命名：`anwind-wine-<ver>-x86_64-bionic.tar.gz` /
  `anwind-wine-<ver>-arm64ec-bionic.tar.gz`，校验步骤按形态检查
  对应 PE 目录（x86_64/i386 或 arm64ec/aarch64/i386）与 winex11.drv。

### 5. 四条 workflow 构建步骤新增"失败自诊断"
构建失败时自动把**日志最后 150 行 + 磁盘/内存状态**直接打印到失败的
步骤输出里（并附 `::error` 标注），下次再失败无需下载 artifact、
无需登录即可在步骤页看到具体报错，直接复制给我即可定位。

## 使用顺序建议
1. 先跑 `AnWind Bionic Mesa`（最容易复现问题的短链路，24 个包）
2. 再跑 `AnWind Bionic Wine (x86_64 + arm64ec)`（选 both）
3. 最后跑 `Build AnWind Bionic Rootfs (X11)` 与 `AnWind Bionic Box64`

mesa 跑通后，wine/rootfs 与它共享同一批依赖配方，成功率最高。

---

# v3.1.4 —— 前缀创建全链路修复（proton 双形态 / FEX 仿真器 / 自包含依赖库）

## 设备实测根因（四份会话日志逐条定位）

| 现象 | 根因 | 修复 |
|------|------|------|
| proton-10.0 **arm64ec** `wineboot -i` 退出码 **159**，前缀建不出来、syswow64 全空 | 159 = 128+31 = **SIGSYS**：proton 的 `do_fsync()`（`dlls/ntdll/unix/fsync.c` + `server/fsync.c`）在读 `WINEFSYNC` 环境变量**之前**无条件 `syscall(__NR_futex_waitv,…)` 探测能力——Android 应用沙箱 seccomp 对白名单外的 syscall 号直接 SIGSYS 击杀，进程根本活不到读 errno。**`WINEFSYNC=0` 救不了**（探测先于 env） | 构建期注入 `#if defined(__ANDROID__) return 0;` 守卫（两配方 `disable_fsync_probe`，幂等 sed 注入） |
| proton-10.0 **x86_64** `box64 …/bin/wineboot` 报 "Reading elf header … not executable: 64-bit ELF file" | wine ≤9.18/proton 树的 `bin/wineboot` 等工具装成 **shell 脚本**（`exec wine <名>`）；box64 读不到 ELF 头→原生 exec→脚本原生 exec x86_64 `bin/wine` 绕开 box64→ENOEXEC | ① App/脚本统一改走 `bin/wine wineboot`（loader 按 argv[0] basename 分发，官方机制）；② 配方 `normalize_bin_tools` 把脚本/符号链接规整成 loader 真实副本 |
| proton-10.0 **x86_64** `box64 …/bin/wine winecfg` 报 **`Symbol shm_open not found, cannot apply R_X86_64_JUMP_SLOT`** | aarch64 rootfs 只组装 aarch64 包；x86_64 形态 wine 的 unix .so 是 **x86_64-bionic** 构建，其 DT_NEEDED（libpulse/libasound/libX11/libandroid-shmem/gstreamer…）在设备上**全都不存在**——shm_open 只是第一个炸的 | CI 打包步骤 8.5a：把依赖闭包打进 `opt/wine/lib/x86_64-bionic/`；App/脚本把它加进 `BOX64_LD_LIBRARY_PATH`（gstreamer 插件路径同步指向包内 x86_64 版） |
| arm64ec 前缀建好后 **x86_64/i386 程序起不来**（缺 FEX 两个 dll） | 配方定义了 `hangoverDebUrl` 却从未提取——`libarm64ecfex.dll`（x86_64 仿真/HODLL64）、`libwow64fex.dll`（i386 仿真/HODLL）、`wowbox64.dll`（box64 PE）全缺 | 配方 `pre_package` 自动下载 Hangover 官方 `hangover_11.16_dlls.tar` 注入 `lib/wine/aarch64-windows/`（rootfs 产线共用）；App/脚本按存在性默认 `HODLL64/HODLL` |
| 日志打 "wineboot -i 完成" 但实际已阵亡 | App 无条件报完成（不看退出码） | `WineRegistry` 校验退出码 + 失败重试一次 + 明确错误提示（159=SIGSYS） |

rootfs 内置 wine 能用是因为 hangover-11 树已删 esync/fsync（无 syscall 探测）——与"是否 rootfs 构建"无关，与**源码树**有关。

## 改动文件（8 个）

1. `bionic-rootfs/projects/wine/build.sh` — `disable_fsync_probe` + `normalize_bin_tools`
2. `bionic-rootfs/projects/hangover-wine/build.sh` — 同上 + FEX/box64 dll 自动注入（arm64ec 门控）
3. `bionic-rootfs/projects/anwind-container/anwind-wine` — `apply_android_safe_env`（WINEESYNC/WINEFSYNC=0 + HODLL 探测）+ wineboot 走 loader + BOX64_LD_LIBRARY_PATH 指向 x86_64-bionic
4. `.github/workflows/build-wine-bionic.yml` — 步骤 8.5a（x86_64 依赖闭包打包 + 校验）/ 8.5b（仿真器 dll 校验）+ 发行说明
5. `.github/workflows/build-bionic-rootfs.yml` — rootfs 校验加 3 个仿真器 dll
6. `app/…/launcher/WineEnvironment.kt` — `applyBox64Env(wineDir)` + `applyWineCompatEnv`
7. `app/…/launcher/RootfsLauncher.kt` — wineboot 经 loader + 兼容环境接入
8. `app/…/launcher/WineRegistry.kt` — wineboot 退出码校验 + 重试

## 升级与验证顺序（重要）

1. **重建 rootfs**（hangover-wine 配方注入 FEX dll → rootfs 内置 wine 也获得仿真器）
2. **重建 wine 包**（fsync 守卫 + 自包含依赖库 + dll 注入；proton-10.0 两形态均应通过）
3. **更新 APK**（启动器 wineboot/环境修复在 App 侧）
4. 设备验证：
   - `anwind-container install-wine anwind-wine-<ver>-x86_64-bionic.tar.gz` 后建容器
   - 会话日志应出现 `wineboot -i 完成（退出码 0）`、无 `Symbol … not found`
   - `ls <prefix>/drive_c/windows/syswow64` 应有 i386 dll（i386-windows 拷入）
   - arm64ec 容器跑 x86_64 程序（FEX 默认），32 位程序 HODLL=libwow64fex.dll

## 技术注记

- **fsync 守卫为什么在构建期而非运行期**：探测先于 WINEFSYNC 读取，环境变量无法阻止 SIGSYS；必须在 C 源码层面短路。esync 无害（epoll 允许、/dev/shm 缺失时自动禁用），仍默认 `WINEESYNC=0`（Winlator 同款）。
- **dll 版本解耦**：FEX PE 模块自包含（纯 CPU 仿真、syscall 翻译在 wine 侧），ARM64EC/xtajit64 是 Windows-on-ARM 标准 ABI —— 固定取 hangover-11.16 的 dll 包（30MB，含 DXVK 合集，版本可后续上浮）。
- **mesa 不入 x86_64-bionic 闭包**：GL/EGL 走 box64 原生包装直达设备 GPU 驱动，捆绑仿真版反而降级回软件渲染。

---

# v3.1.5 —— 三产线 CI 阻断 + 启动器覆盖回归修复（2026-10-29）

> v3.1.4 合入后 hangover / proton / rootfs 三条产线仍全红。逐日志定位出
> 4 个独立缺陷，其中两个让 v3.1.4 的运行时修复在设备上"看起来没生效"。

## CI 阻断（三条产线全红的根因）

| # | 现象（CI 日志实证） | 根因 | 修复 |
|---|------|------|------|
| 1 | arm64ec 形态与 rootfs 产线的 hangover-wine 构建双双打印 `…/tmp/hangover-11.16-dlls/dlls.tar.tmp: No such file or directory`，仿真器 DLL 注入失败 | `pre_package` 里 `mkdir -p "${_peDir}" "${wsDir}/tmp"` **漏建 `${_fexTmp}` 本体**——只建了父目录，`wget -O` 写入 `…/hangover-11.16-dlls/dlls.tar.tmp` 时目录不存在直接失败（URL 本身有效：`hangover_11.16_dlls.tar` 28.7MB 真实存在，平铺 3 个 dll + dxvk 合集） | `mkdir -p "${_peDir}" "${_fexTmp}"`；wget 加 `--tries=3 --timeout=60` |
| 2 | 同上——DLL 下载失败后构建**照样** `[OK] 构建完成`，产出缺 dll 的残废包，下游 rootfs 校验（3 个 dll）/ wine 工作流 8.5b 校验才炸，报错位置远离根因 | `build-rootfs-arch.sh` 三处 `pre_package` 调用点**全部忽略返回值**——注入失败静默继续 `package()` | 三处统一 `pre_package \|\| { err …; exit 1; }`，失败即刻中止 |
| 3 | x86_64 形态选 hangover-10.x 源（10.18 实测）编译中止：`dlls/ntdll/unix/unix_private.h:660:26: error: call to undeclared function 'ffs'`（loadorder.o Error 1） | glibc 的 `<string.h>` 在 `_DEFAULT_SOURCE` 下附带声明 `ffs()`，**bionic 的 ffs 只在 `<strings.h>`**（POSIX 规定位置）——依赖 glibc 隐式声明的 10.x 树在 bionic 下被 `-Wimplicit-function-declaration` 判死。11.x/proton 树无 ffs 用法不受影响 | 两配方（wine / hangover-wine）新增 `inject_ffs_compat`：仅当 `unix_private.h` 使用 ffs 且未含 strings.h 时幂等注入 `#include <strings.h>` |
| 4 | （v3.1.4 遗留）`hangoverDebUrl` 定义后从未使用 | 死代码误导排查 | 已删除，注释指向 dlls.tar 方案 |

## 运行时回归：APK assets 启动器陈旧覆盖（v3.1.4 修复"设备上不生效"的真因）

`AnWindScriptAssets`（`assets/anwind/scripts/`）**每次 App 启动无条件覆盖**
`rootfs/usr/bin/anwind-wine`。v3.1.4 只更新了 rootfs 侧
（`bionic-rootfs/projects/anwind-container/anwind-wine`，762 行新版），
APK assets 里仍是 **v3.1.3 旧版（709 行）**——缺：

- `apply_android_safe_env`（WINEFSYNC/WINEESYNC=0 —— arm64ec proton
  wineboot 退出码 159/SIGSYS 的运行期兜底）
- `BOX64_LD_LIBRARY_PATH` 指向 `x86_64-bionic` 依赖闭包
  （shm_open / libpulse / gstreamer "Symbol not found" 的解药）
- HODLL64/HODLL 探测默认 + wineboot 走 loader 的分发修正

即：**rootfs 装了新版启动器 → App 一开就被 APK 里的旧版打回去**，三份
设备会话日志（wine-588 ×2 / wine-111111111 ×2）的全部症状与此吻合。

**修复**：`app/src/main/assets/anwind/scripts/anwind-wine` 与 rootfs 侧
逐字节同步；APK 升版 **2.38.1（versionCode 64）** 便于设备上区分
（会话日志头部的 "App: com.anwind 2.38.x" 直接可查）。

## 改动文件（5 个）

1. `bionic-rootfs/projects/hangover-wine/build.sh` — FEX DLL 下载 mkdir 修复 + ffs 兼容注入 + 死代码清理
2. `bionic-rootfs/projects/wine/build.sh` — ffs 兼容注入
3. `bionic-rootfs/build-rootfs-arch.sh` — pre_package 失败传播（3 处）
4. `app/src/main/assets/anwind/scripts/anwind-wine` — 同步 v3.1.4 启动器
5. `app/build.gradle.kts` — 2.38.1 / versionCode 64

## 验证顺序

1. 重跑 `build-bionic-rootfs.yml`（aarch64）—— hangover-wine 段应出现
   `已注入仿真器 DLL → lib/wine/aarch64-windows/…`，rootfs 校验 3 个 dll 全 OK
2. 重跑 `build-wine-bionic.yml` 两形态 —— 8.5b（arm64ec）3 个 dll 全 OK；
   x86_64 选 hangover-10.18 源应能编过（ffs 注入生效，日志有
   `已注入 #include <strings.h>`）
3. 更新设备 APK（2.38.1）→ 安装新 wine 包 → 建容器验证：
   - x86_64 形态：`wineboot -i` 退出码 0、无 "Symbol shm_open not found"
   - arm64ec 形态：`wineboot -i` 退出码 0（非 159）、
     `ls <prefix>/drive_c/windows/syswow64` 出现 i386 dll
   - arm64ec 跑 x86_64 程序（HODLL64=libarm64ecfex.dll 默认生效）

## 附：hangover_11.16_dlls.tar 内容（实测解包）

```
dxvk-v2.7.1.tar.gz   16475867  （DXVK 合集，按需手动取用）
libarm64ecfex.dll     4751360  （x86_64 仿真，HODLL64）
libwow64fex.dll       4214784  （i386 仿真，HODLL）
wowbox64.dll          4676096  （box64 PE，i386 备选）
```

# v3.2.10 —— wine-9.7 arm64ec 构建炸点 + proton arm64ec syswow64 空（2026-11-01）

## 设备实测根因（两份日志逐条定位）

### 问题 1：official wine-9.7 arm64ec 构建失败（v3.2.9 日志）

```
dlls/msvcrt/string.c:3056:12: error: call to undeclared function 'sse2_memmove'
make: *** [Makefile:14654: dlls/crtdll/arm64ec-windows/string.o] Error 1
```

wine-9.6/9.7 新增 SSE2 memmove 快路径：**定义段**（~2789 行）与**调用点**
（~3055 行）是两道守卫。0007（9.2 锚点）只修了定义段总守卫
`#if defined(__i386__) || defined(__x86_64__)`；EC 趟下定义被排除、
3055 行调用点的独立 `#ifdef __x86_64__` 漏网（llvm-mingw arm64ec 同时预
定义 `__arm64ec__` 与 `__x86_64__`）→ 调用未声明函数。上游 wine-10 已把
两处守卫统一为 `#if defined(__x86_64__) && !defined(__arm64ec__)`。

伴随：rpcrt4/cstub.c 的 vtbl_thunks x64 THUNK_ENTRY 裸汇编同样只在 EC 趟
炸（上游 wine-10 起整段重写为纯 C）；kernelbase/debug.c 与
rpcrt4/ndr_stubless.c 的 0007 hunk 未命中是【无害的】——9.7 上游已自带
EC 分支（.rej 仅是锚点漂移噪音）。

### 问题 2：proton arm64ec 前缀 syswow64 目录为空（设备会话日志）

```
00dc:err:environ:init_peb starting L"C:\windows\syswow64\rundll32.exe" in experimental wow64 mode
00dc:err:wow:load_64bit_module failed to load dll c0000135
```

链路：wineboot -u 为所有受支持架构派生 rundll32 执行 wine.inf 的
PreInstall / DefaultInstall / **Wow64Install** 段 —— syswow64 的全部假
DLL、注册表、字体等正是 **Wow64Install 段**（32 位 `syswow64\rundll32.exe`）
生成的。而 vanilla wine（9.x/10.x）的 wow64 CPU 仿真 dll 选择
（`dlls/wow64/syscall.c get_cpu_dll_name`）走注册表
`HKLM\Software\Microsoft\Wow64\x86`，wine.inf 写入的默认是 **xtajit.dll**
—— Linux 上不存在 → 32 位进程初始化即死（c0000135）→ Wow64Install 从未
执行 → **syswow64 整个目录无内容**。同理 EC 进程的 x64 仿真器
（`ntdll/loader.c load_arm64ec_module`）默认 **xtajit64.dll** 也不存在。

HODLL64/HODLL 是 **hangover-11.16 专属机制**（`wow64/syscall.c:941` /
`ntdll/loader.c:4317` 读环境变量选择仿真 dll）；vanilla official/proton
树根本没有读取代码 —— 启动器注了也白发。且 v3.1.6 起 wineboot 会话还
【主动剥离】了这两个变量（当时误判"前缀创建只跑 aarch64 PE"），双重
叠加导致 syswow64 恒空。

## 修复（三个新补丁 + 校验 + 启动器）

1. `0007c-fix-arm64ec-x64-asm-wine97.patch` — 9.7 构建炸点（wine-9.7 锚点）：
   string.c 调用点守卫回填上游修法 + cstub.c EC 走空桩分支
   （上游对不支持架构的 #else 兜底同款，delegating stubs 在 EC 无用户）。
2. `0010-fix-arm64ec-hodll-wow64.patch` — **HODLL 机制移植**（对齐 hangover）：
   `wow64/syscall.c get_cpu_dll_name` 读 HODLL 环境变量优先；I386 在
   ARM64/ARM64EC 宿主下默认 libwow64fex.dll（不再读 xtajit 注册表）。
   9.7 与 proton_10.0/wine-11.16 该函数逐字一致，一套 hunk 通吃；
   hangover-11.16 自带机制 → 全 hunk 静默跳过。
3. `0011-fix-arm64ec-hodll64-suspend-wine10.patch` — **HODLL64 + Wow64 线程
   挂起管道移植**（对齐 hangover，完整复刻其 11.16 与 vanilla 的全部差异
   12 文件中的 9 个）：loader.c 读 HODLL64 + 默认 libarm64ecfex.dll；
   ntdll.spec 导出 RtlWow64SuspendThread；signal_arm64.c 完整实现（远进程
   breakin 挂起）/ signal_arm64ec.c 直通桩；wow64.spec 把
   Wow64SuspendLocalThread 由 stub 转真导出 + syscall.c BTCpu 挂起转发 +
   process.c NtSuspendMethod 改走 RtlWow64SuspendThread；loader.c
   init_wow64 增取 Wow64SuspendLocalThread。**双锚点**：wine-10 形态
   （virtual_unwind 带 dump_backtrace 签名 / arm64_features 返回）与
   wine-9.x 形态（KiUserExceptionDispatcher 汇编尾 / __chkstk）各一套互斥
   hunk，同一树只命中一套；wine-11.16 vanilla 自带 RtlWow64SuspendThread
   （process.c）→ spec/桩 hunk 不命中防重复定义；hangover-11.16 全不命中。
4. `build.sh` 新增 4 道 arm64ec 防呆校验（延续 0009 校验风格）：string.c
   调用点守卫 / cstub.c EC 分支 / HODLL / HODLL64 —— 未生效立即报清
   根因中止，不再让构建死在 28 分钟后的编译深处。
5. `RootfsLauncher.kt` — **恢复 wineboot 会话 HODLL64/HODLL 注入**
   （撤销 v3.1.6 剥离）：Wow64Install 段需要 32 位 rundll32 + 仿真器；
   旧包（vanilla 树）不读这两个变量，注入零副作用。
6. `WineEnvironment.kt` — KDoc 补记 v3.2.10 机制与"前缀会话必须保留注入"。

## 四树交叉验证（本地 patch --forward 实测）

| 源码树 | 0007c | 0010 | 0011 | 结果 |
| --- | --- | --- | --- | --- |
| wine-9.7 | ✅ 全命中 | ✅ | ✅ 9.x 锚点（10 形态 hunk 落 .rej 预期） | 构建修复 + i386 wow64 仿真 |
| proton_10.0 | 全 .rej（上游已修，无害） | ✅ | ✅ 10 形态全套（9.x 锚点 .rej 预期） | syswow64 修复 + x64-in-EC |
| wine-11.16 | 全 .rej | ✅ | HODLL64/挂起管道命中；spec+桩不命中（自带 process.c 实现，防重复） | 与 11.x 自身机制协同 |
| hangover-11.16 | 全 .rej | 全 .rej | 全 .rej（原生机制保持，零重复定义） | 完全互斥 |

已知限制：wine-9.x 形态无 x64-in-EC 机制（上游 10.x 才有
load_arm64ec_module/CHPEV2），9.7 官方树 arm64ec 跑纯 x64 dll 不支持
（i386 仿真不受影响）；x86_64 flavor 本配方不构建，无 signal_x86_64.c
桩需求。

## 验证顺序

1. 重跑 `build-wine-bionic.yml` arm64ec 形态：
   - official **wine-9.7**：应能编过（不再死于 string.o 的 sse2_memmove）；
     日志不应出现 4 道 v3.2.10 校验的 ::error
   - **proton_10.0**：日志应出现 `0010/0011` 补丁应用行
     （`应用主补丁: 0010-fix-arm64ec-hodll-wow64.patch` 等且无对应 .rej）
2. 安装新 wine 包 → 删容器重建（或删 prefix）→ wineboot -i：
   - 会话日志应出现 `前缀构建会话：保持 HODLL64/HODLL 仿真器注入`
   - 不再出现 `err:wow:load_64bit_module failed to load dll c0000135`
   - `ls <prefix>/drive_c/windows/syswow64` 应有 i386 dll（v3.1.5 就
     想要验证的项，这次真的能过）
3. arm64ec 跑 x86 游戏：HODLL=libwow64fex.dll 生效；跑 x64 exe：
   HODLL64=libarm64ecfex.dll 生效（不再 NtTerminateProcess）。
