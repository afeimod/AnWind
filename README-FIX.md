# AnWind Bionic 修复包 v2（2026-09-19）

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
