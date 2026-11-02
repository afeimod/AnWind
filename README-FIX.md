# AnWind Bionic 修复包 v2（2026-09-19）

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
