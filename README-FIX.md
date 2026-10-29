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
