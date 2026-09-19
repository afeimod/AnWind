# AnWind Bionic 修复包 v2（2026-09-19）

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
