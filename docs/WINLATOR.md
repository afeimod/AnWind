# AnWind × Winlator 容器集成（v2.23）

AnWind 桌面现已完整集成 [Winlator Cmod 7.1.4x](https://github.com/Pipetto-crypto/winlator)
引擎（重新集成，替换 v2.22 时代残留的部分移植代码），并提供 **桌面容器创建
（全套 Winlator 设置）** 与 **exe 一键运行**。游戏画面统一显示在 AnWind
自带的 **X11 浮动窗口**（termux-x11/lorie + LorieView），不再使用 Winlator
自带的 XServer/GLRenderer 显示栈。

```
┌────────────────────── AnWind 桌面（com.anwind） ──────────────────────┐
│                                                                      │
│  ┌─ Winlator 容器（Compose UI）─┐      ┌─ X11 浮动窗口（LorieView）─┐  │
│  │ 容器列表 / 创建 / 全套设置   │      │  游戏画面在这里渲染        │  │
│  └──────────────┬───────────────┘      └────────────▲───────────────┘  │
│                 │ runExe                            │ 连接 fd          │
│  ┌──────────────▼────────────────────────┐          │                  │
│  │ WinlatorSession（:winlator 模块）      │   ┌──────┴───────────┐      │
│  │ ImageFs 安装 → 容器激活 → wineprefix   │   │ lorie X server   │      │
│  │ 系统文件 → DXVK/turnip 环境 → 启动     │──▶│ (app_process     │      │
│  └──────────────┬────────────────────────┘   │  CmdEntryPoint)  │      │
│                 │ exec box64 wine            └──────▲───────────┘      │
│  ┌──────────────▼────────────────────────┐          │ X11 socket       │
│  │ bionic Wine + Box64（imagefs）         │──────────┘                  │
│  │ DISPLAY=:0 → usr/tmp/.X11-unix/X0 ─────┼──▶ 符号链接到 lorie socket  │
│  └────────────────────────────────────────┘                            │
└────────────────────────────────────────────────────────────────────────┘
```

## 一、快速上手

1. **安装引擎资产**：打开 AnWind 桌面 → 「Winlator 容器」→ 右上角
   「安装引擎资产」。首次解压 imagefs（约 183MB 压缩包）需 2-5 分钟。
2. **新建容器**：「＋ 新建容器」→ 填写名称、选择分辨率/图形驱动/DX 包装器
   等全套设置（见下文）→ 保存。
3. **运行 exe**：容器卡片 → 「▶ 运行 exe」→ 在弹出的文件资源管理器
   （exe 选择模式）里点击任意 `exe/msi/bat` → 容器启动，**AnWind 桌面自动
   弹出 X11 窗口并连接 lorie，游戏画面即在其中**（可拖拽/缩放/真全屏）。
4. **生成桌面快捷方式**：容器卡片 → 「生成快捷方式」→ 选择 exe →
   AnWind 桌面出现游戏图标，双击直接启动容器游戏。
5. **停止**：容器页「停止会话」，或直接关闭 X11 窗口（关闭即清理 wine）。

## 二、容器设置项（Winlator 全套）

| 分组 | 设置项 | 取值 | 说明 |
|------|--------|------|------|
| 常规 | 名称 | 任意 | 容器标识 |
| 常规 | 屏幕分辨率 | 800x600 / 1280x720 / 1280x800 / 1920x1080 / native | `native`=X 屏幕跟随窗口（推荐）；固定值经**分辨率握手**映射到 X 屏幕 |
| 图形 | 图形驱动 | turnip / wrapper / virgl / llvmpipe | **turnip 推荐**（Adreno GPU）；wrapper/virgl 依赖 Winlator 自带 X server 的呈现路径，在 AnWind X11 下自动回落 turnip；llvmpipe 为软件渲染 |
| 图形 | DX 包装器 | dxvk / vkd3d / wined3d | dxvk（DX9-11→Vulkan）/ vkd3d（DX12→Vulkan）/ wined3d（GL） |
| 图形 | DXVK 配置 | `version=…,framerate=…,maxDeviceMemory=…,async=…,asyncCache=…` | 与 Winlator DXVKConfigDialog 格式一致 |
| 图形 | DDraw 包装器 | wined3d / cnc-ddraw | 老游戏 DirectDraw |
| 图形 | 音频驱动 | alsa / pulseaudio | alsa 经内置 ALSA 服务；pulseaudio 经 imagefs 内置服务 |
| 系统 | Wine 版本 | proton-9.0-x86_64 / proton-9.0-arm64ec | arm64ec 需要 FEXCore 模拟器 |
| 系统 | WoW64 模式 | 开/关 | 32 位游戏支持方式 |
| 系统 | 启动服务 | 正常 / 必要 / 激进 | 对应 STARTUP_SELECTION_* |
| 组件 | direct3d / directsound / directmusic / directshow / directplay / vcrun2010 | 原生 / 内置 | 与 Winlator wincomponents 一致（原生=解压 DLL 覆盖） |
| 高级 | 盘符管理 | `D:/path/E:/path2` | 与 Winlator drives 协议一致；运行时自动补 c:/z:；exe 在 /storage 时自动绑 y: |
| 高级 | 环境变量 | `KEY=value …` | 追加进 wine 进程环境 |
| 高级 | CPU 亲和 | 如 `0-3` | taskset 绑核 |
| 高级 | Box64 预设 | performance / compatibility / intermediate / stability / custom | 与 Winlator Box86_64Preset 一致 |
| 高级 | 模拟器 | box64 / fexcore | fexcore 配套 FEXCore 版本 |

## 三、显示管线（本次集成的核心改动）

**Winlator 原版**：wine → DISPLAY=:0 → 内置 XServer（Java）→ XServerView/GLRenderer
→ 自绘 Surface。

**本集成**：wine → DISPLAY=:0 → `imagefs/usr/tmp/.X11-unix/X0`（**符号链接**）
→ lorie X server socket（`$PREFIX/tmp/.X11-unix/X1`）→ Xwayland/lorie →
`CmdEntryPoint` 广播 → `X11WindowController` 自动打开 AnWind X11 浮动窗口 →
`LorieView` 渲染。

- wine 的 libX11 按 `TMPDIR` 解析 X socket（与 Winlator 原生行为一致），
  因此仅以符号链接即可把显示端整体替换为 lorie，wine 进程零改动。
- 容器「屏幕分辨率」经 `X11ResolutionLink` 分辨率握手切换 X 屏幕
  （RandR），显示层拉伸铺满无黑边；窗口化游戏由 `X11FitClient` 自适应贴合。
- 输入：触摸（SmartTouchBridge 智能鼠标桥）/ 键盘 / 外接键鼠 /
  桌面虚拟手柄（DInput 直注 X + WinHandler UDP 7947 XInput 通道）全部沿用
  AnWind 现有管线。

### GPU 加速说明（重要）

- **DXVK / VKD3D + turnip 是主力 GPU 路径**：Vulkan 直连 Adreno GPU，
  呈现走 Mesa WSI 无 DRI3 的拷贝路径（`MESA_VK_WSI_DEBUG=sw`，XShm/XPutImage），
  与 Termux 生态 turnip+DXVK 方案一致。
- lorie X server **不提供 GLX**：`wined3d`（GL 路径）游戏将以软件渲染
  （llvmpipe）运行，大作建议改用 DXVK。
- `wrapper`（Winlator 闭源 Vulkan 包装层）与 `virgl` 依赖 Winlator 自带
  X server 的私有呈现通道，在本集成下不可用（UI 中选择会自动回落 turnip）。

## 四、构建（GitHub Actions）

`.github/workflows/build.yml`（手动触发）已加入
**「Fetch Winlator engine assets」** 步骤：构建时从
`Pipetto-crypto/winlator` 匿名拉取三个 Git LFS 大文件并放进
`winlator/src/main/assets/`：

| 文件 | 作用 | 体积 |
|------|------|------|
| `imagefs.txz` | bionic Wine + Box64 根文件系统 | ~183MB |
| `proton-9.0-x86_64.txz` | Wine 本体（x86_64） | ~8.7MB |
| `proton-9.0-arm64ec.txz` | Wine 本体（arm64ec） | ~10MB |

其余引擎资产（dxwrapper / graphics_driver / wincomponents / box86_64 /
wowbox64 / fexcore / container_pattern 等，共约 274MB）已直接提交在
`winlator/src/main/assets/`，无需额外步骤。

> 若 Actions 中 LFS 拉取失败（网络/限流），构建会明确报错；也可手动下载
> 三个文件放进 `winlator/src/main/assets/` 后提交到自己的 LFS/Release。

原生库：`libwinlator.so`（xconnector epoll + ALSA client，CMake 源码构建）
与 `libandroid-sysvshm.so`（guest 侧 SysV shm → ashmem，运行时拷入 imagefs）
随 APK 分发，仅 arm64-v8a（与 imagefs 内二进制一致）。

## 五、目录结构

```
winlator/                          # :winlator 引擎模块（新）
  src/main/java/com/winlator/cmod/
    core/                          # FileUtils/EnvVars/WineInfo/WineUtils/…
    container/                     # Container / ContainerManager / Shortcut
    box86_64/ (+rc/)               # Box64 预设与 per-app RC
    contents/                      # 内容包管理（Wine/DXVK 自定义版本）
    xenvironment/                  # ImageFs / ImageFsInstaller / XEnvironment
      components/                  # BionicProgramLauncher / SysVSHM / ALSA / Pulse …
    x11/                           # AnWindX11 显示桥（替代 XServerComponent）
    session/                       # WinlatorSession 会话编排
    winhandler/ sysvshm/ alsaserver/ xconnector/
  src/main/cpp/                    # libwinlator.so（epoll + alsa_client）
  src/main/assets/                 # 引擎资产（274MB；imagefs 由 CI 补齐）
app/src/main/java/com/anwind/apps/winlator/
  WinlatorApp.kt                   # 桌面应用定义 + 容器主页
  ContainerEditorSheet.kt          # 创建/编辑全套设置
  WinlatorController.kt            # 会话宿主 / 快捷方式 / 资产安装
  X11SessionStarter（同文件）       # app_process 拉起 lorie
termux-x11/                        # AnWind X11（清残留后纯化）
docs/WINLATOR.md                   # 本文
```

## 六、与 Winlator 原版的差异清单

| 项 | Winlator 原版 | AnWind 集成 |
|----|--------------|-------------|
| 显示端 | 内置 XServer + XServerView + GLRenderer | AnWind X11（lorie + LorieView 浮动窗口） |
| virgl / wrapper 图形 | GLX / drawable 共享通道 | 不可用（自动回落 turnip） |
| wined3d GL | 经 XServer GLX（可 virgl 加速） | lorie 无 GLX → llvmpipe 软件渲染 |
| 输入覆盖层 | InputControlsView 虚拟手柄 | AnWind 桌面虚拟手柄（DInput 直注 X / XInput 经 WinHandler） |
| 文件管理器 | wfm.exe（wine 内） | AnWind 文件资源管理器（exe 选择模式 + SAF） |
| 容器 UI | Fragment/XML | Compose（字段与 .container 协议完全兼容） |
| 进程管理 | TaskManagerDialog | 容器页会话横幅（停止/状态）；进程管理入口预留 |
| MIDI / Big Picture / XR / steamgrid | 有 | 未移植（保持引擎精简） |
| `.container` 配置 / 容器目录结构 | — | **完全一致**，Winlator 容器可互换 |

## 七、故障排查

- **X11 窗口未弹出/黑屏**：终端执行 `anwind-x11 doctor` 体检；或先
  `anwind-x11-stop` 再从容器页重新启动。
- **游戏无声音**：容器音频驱动选 `pulseaudio` 时依赖 imagefs 内置服务
  （已随资产就位）；`alsa` 路径检查 `ANDROID_ALSA_SERVER` socket。
- **GL 游戏极慢**：正常现象（lorie 无 GLX）——改用 DXVK 包装器。
- **32 位游戏**：开启 WoW64；arm64ec Wine 需模拟器选 fexcore。
- **wineprefix 损坏**：删除容器重建；或重装引擎资产（会重建 imagefs 并
  标记容器 wineprefixNeedsUpdate）。
