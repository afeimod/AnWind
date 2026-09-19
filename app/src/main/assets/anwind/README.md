# assets/anwind/ — 内置 tzst 资产目录（自解压契约）

本目录存放 AnWind 的**离线 tzst 资产**（tar + zstd，后缀 `.tzst` 或 `.tar.zst`）。
APK 启动 / bootstrap 安装完成后由 `AnWindTzstAssets` **自动自解压**，无需手工导入。

## 命名契约（文件名含关键词即归类，其余部分任意）

| 文件名含关键词 | 用途 | 解压目标 | 时机 |
|-----------|------|---------|------|
| `rootfs` | bionic rootfs | `/data/data/com.anwind/files/rootfs` | 启动即解压（指纹幂等） |
| `mesa`   | Mesa GL（libGL/EGL/gbm + dri/ + ICD） | 同上（usr/ 布局叠加） | 启动即解压（指纹幂等） |
| `box64`  | box64（usr/local/bin/box64） | 同上 | 启动即解压（指纹幂等） |
| `turnip` | Turnip Vulkan 驱动（usr/lib + icd.d） | 同上 | 启动即解压（指纹幂等） |
| `dxvk`   | DXVK DLL | `<wine前缀>/drive_c/windows` | **前缀构建后**自动解压 |

命名同时兼容简短式（`dxvk-2.4.tzst`、`rootfs-main.tzst`）与 CI 产物原名
（`anwind-bionic-rootfs-20260919-aarch64.tzst`、`anwind-mesa-25.1-aarch64-bionic.tzst`、
`anwind-box64-v0.2.9-aarch64-bionic.tzst`）。wine 发行包不在此自解压范围
（仍用 `anwind-container install-wine <tzst|tar.gz>` 安装）。

## 归档成员路径布局（两种均支持）

1. **CI 发行包布局**（data/data 开头，build-bionic-rootfs / build-mesa-bionic /
   build-box64-bionic 产物原样可用）：

       data/data/com.anwind/files/rootfs/usr/...
       data/data/com.anwind/files/rootfs/usr/local/bin/box64

   成员前缀自动剥壳后落入 rootfs 根。

2. **相对布局**（usr/、etc/、home/ 开头）：

       usr/lib/libGL.so.1
       usr/local/bin/box64

   直接落入 rootfs 根。

## DXVK 资产布局（顶层目录决定落位）

- **system32/ + syswow64/**（本项目约定样式，推荐）：
  直接归位 → `drive_c/windows/system32`（64 位 DLL）与
  `drive_c/windows/syswow64`（32 位 DLL）。
- x64/ + x32|x86/（上游 DXVK 发布包）：自动重映射，落位同上。
- 裸 *.dll：按 64 位归入 system32。

## 幂等与更新

- 每个资产按「文件名 + 长度 + CRC32」指纹记账
  （`rootfs/usr/etc/anwind/.tzst-assets-fingerprint`）；
  资产内容不变时不重复解压；更新资产（换名/换内容）后自动重铺。
- DXVK 每个前缀按 `anwindmeta/.anwind-dxvk-applied` 记账（与终端侧
  glibc-runner 双端互认），并回写 container.conf 的 `dxvk=` 版本戳。
- wine 前缀未构建（无 system.reg）时 DXVK **不会**预解压；
  前缀由 wineboot 构建完成后的首次 App 启动 / 进入容器界面即自动补齐。
