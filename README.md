# AnWind Bionic 全家桶（rootfs + Wine/Box64/Mesa 构建 + 容器管理 App 源码）


本包是 **AnWind（com.anwind）bionic 方案的完整交付**，一次打包、目录即架构：
解压后按仓库结构直接合并到 AnWind 仓库根目录即可，无需再挑选文件。

显示方案：**X11**（AnWind 内置 termux-x11 / libXlorie X 服务，socket 位于
App 侧 `/data/data/com.anwind/files/usr/tmp/.X11-unix/X1`，默认显示号 `:1`）。
全链路 **无 Weston、无 Wayland**：rootfs 内 libX11/xtrans/libxcb 已按 App 侧
X11 socket 路径打补丁，容器内程序直连内置 X 服务。

## 一、包目录结构（＝AnWind 仓库结构）

```
.github/workflows/
  build-bionic-rootfs.yml   → bionic rootfs 主构建（aarch64 / x86_64 / all）
  build-wine-bionic.yml     → Wine 构建（x86_64 / 新WoW64 / X11 / box64-ready）
  build-box64-bionic.yml    → Box64 构建（aarch64 / ARM_DYNAREC / ANDROID=1）
  build-mesa-bionic.yml     → Mesa 构建（X11；aarch64=zink+freedreno+turnip）
bionic-rootfs/              → 完整构建系统（77 配方，含全部 X11 包）
  projects/wine/            → 新增配方：bionic Wine（x86_64 新 WoW64）
  projects/anwind-container/→ 新增配方：容器管理 CLI + Wine 启动器
  projects/mesa/|box64/     → 更新配方（ANWIND_MESA_REF / ANWIND_BOX64_REF 可覆盖）
app/src/main/java/com/anwind/
  apps/AppBootstrap.kt      → 更新：注册 ContainersApp（覆盖仓库同名文件）
  apps/containers/          → 新增模块：Wine 容器管理 Kotlin 源码（5 个文件）
```

## 二、App 侧容器管理（新增 Kotlin 源码，app/src/main/java/com/anwind/apps/containers/）

| 文件 | 职责 |
|---|---|
| `ContainerApp.kt` | 桌面应用注册（"Wine 容器"，emoji:📦 图标，免新增资源） |
| `ContainerModels.kt` | 容器数据模型 + container.conf 编解码（后端/预设/分辨率/渲染器枚举） |
| `ContainerManager.kt` | 容器 CRUD / 默认容器 / 本地体检（与 CLI 共享同一存储） |
| `WineSessionLauncher.kt` | Wine 会话启动：自动打开 X11 桌面窗口 → headless TerminalSession 跑 anwind-wine，日志回传 |
| `ContainerScreens.kt` | Compose 管理界面：列表/新建/编辑/克隆/删除/运行/winecfg/wineboot/regedit/停止/日志/体检 |

关键设计：

1. **双端同源**：App 直接读写 rootfs 里的
   `home/.anwind/containers/<name>/container.conf`，键集与终端
   `anwind-container` CLI 逐字对齐 —— App 建的容器终端可跑，终端建的容器
   App 可见，`default_container=` 全局配置双向生效。
2. **X11 联动**：每次"运行"先 `X11WindowController.openWindow()` 打开内置
   X11 桌面窗口（X server = libXlorie），再孵化 wine 会话；DISPLAY 由
   脚本侧自动探测（默认 `:1`），PulseAudio TCP `127.0.0.1:4713` 同步注入。
3. **后端可选**：容器级 backend = box64 / fexcore / hangover / native / auto，
   Box64 预设 performance / compatibility（对应 BOX64_DYNAREC 注入），
   FEXCore 为探测槽位（rootfs 内有 FEXInterpreter 才可选）。
   体检面板（X11 socket / rootfs / CLI / wine / box64 / FEX / 容器数）
   可一键诊断。

## 三、rootfs 侧容器管理（已并入 bionic-rootfs/projects/anwind-container/）

- `anwind-container`：create / list / info / set / default / clone / remove /
  run / cmd / stop / install-wine / install-dxvk / install-vkd3d / doctor
- `anwind-wine`：后端感知启动器（auto/box64/hangover/fexcore/native），
  X11 DISPLAY 自动探测、分辨率握手（.anwind-x11-res）、首启 wineboot -i、
  Mesa/Zink/TU_DEBUG 调优注入、BOX64_DYNAREC 预设。

## 四、四条构建流水线（全部 workflow_dispatch 手动触发）

| Workflow | 架构 | 产物 | 设备安装 |
|---|---|---|---|
| build-bionic-rootfs | aarch64/x86_64/all | `anwind-rootfs-<arch>.tar.gz` | 解包至 `/`（data/data/... 路径自动就位） |
| build-wine-bionic | x86_64 | `anwind-wine-<ver>-x86_64-bionic.tar.gz` | `anwind-container install-wine <tar.gz>` |
| build-box64-bionic | aarch64 | `anwind-box64-<ver>-aarch64-bionic.tar.gz` | 解包至 `/` |
| build-mesa-bionic | aarch64+x86_64 | `anwind-mesa-<ver>-<arch>-bionic.tar.gz` | 解包至 `/` |

要点：

1. **Wine**：Unix 侧 `x86_64-linux-android`（bionic）+ PE 侧 `i386,x86_64`
   （新 WoW64）+ llvm-mingw PE + 宿主工具链先行。`--with-x` 全套 X 扩展、
   `--without-wayland`；产物校验强制断言 `winex11.drv` 存在且无
   `winewayland.drv`。aarch64 设备经 box64 加载运行（MiceWine 同款模型）。
2. **Box64**：`-DANDROID=1 -DARM_DYNAREC=1 -DBAD_SIGNAL=1`，aarch64 专用。
3. **Mesa**：`platforms=x11 / glx=dri`；aarch64 =
   swrast + freedreno(MSM/KGSL) + zink，vulkan = freedreno/turnip。
4. **依赖自动补齐**：所有流水线走 `./build-rootfs-arch.sh --build <pkg>`，
   构建系统按拓扑序自动先补缺失依赖（wine 全闭包 71 包、mesa 22 包）。

## 五、使用顺序

1. 把 `.github/workflows/`、`bionic-rootfs/`、`app/src/main/java/com/anwind/`
   三处内容合并提交到 AnWind 仓库（同名覆盖）；
2. GitHub Actions 依次手动触发：build-bionic-rootfs → build-wine-bionic /
   build-box64-bionic / build-mesa-bionic；
3. 设备端：导入 rootfs tar.gz → 解包 box64/mesa tar.gz →
   `anwind-container install-wine <wine tar.gz>`；
4. 打开 App 桌面"📦 Wine 容器"→ 新建容器 → 运行 exe（或终端
   `anwind-container run <name> <exe>`），画面输出在内置 X11 桌面。
