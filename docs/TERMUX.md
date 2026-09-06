# AnWind Termux 移植技术文档

> 版本：v2.22.1 · 基于 termux-app v0.118.0 + termux-packages bootstrap 2022.01.07-r1
>
> 本文档说明 AnWind 终端的真实 Termux 移植架构，重点回答两个问题：
> **官方 Termux 的路径和包名是锁死的，如何完整迁移到 AnWind 的包名与路径？**
> **pkg / apt 装出来的软件包为什么能真实运行？**

---

## 1. 移植总览

```
┌─────────────────────────────────────────────────────────────┐
│  AnWind App (com.anwind, targetSdk 28)                       │
│                                                             │
│  ┌─────────────────┐   ┌──────────────────────────────┐     │
│  │ TerminalApp.kt   │──▶│ TermuxSessionController      │     │
│  │ (Compose UI +    │   │ (TerminalSessionClient /     │     │
│  │  ExtraKeys 快捷   │   │  TerminalViewClient 实现)     │     │
│  │  键栏 + 工具栏)   │   └──────────┬───────────────────┘     │
│  └─────────────────┘              │                         │
│                                   ▼                         │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ com.anwind.termux.terminal (移植自 termux terminal-  │    │
│  │ emulator, Apache-2.0)：TerminalSession / Emulator /  │    │
│  │ Buffer / Row / WcWidth / KeyHandler / JNI            │    │
│  └──────────────────┬──────────────────────────────────┘    │
│                     │ JNI (libtermux.so)                    │
│  ┌──────────────────▼──────────────────────────────────┐    │
│  │ cpp/termux/termux.c       ← PTY fork/exec (上游原版) │    │
│  │ cpp/termux/anwind_bridge.c← AnWind 专有 FIFO 桥      │    │
│  └──────────────────┬──────────────────────────────────┘    │
│                     │ /dev/ptmx + fork + execve             │
│  ┌──────────────────▼──────────────────────────────────┐    │
│  │ /data/data/com.anwind/files/usr/bin/login           │    │
│  │   → $PREFIX/bin/bash -l  （真实 login shell）         │    │
│  │   → pkg / apt / dpkg / curl / termux-tools 全家桶    │    │
│  │   → TUNA 镜像源（apt 增量更新，anwind-mirror 可换源） │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

组件来源（全部 GitHub 官方）：

| 组件 | 来源 | 许可证 |
|------|------|--------|
| terminal-emulator（PTY/VT100 模拟） | termux/termux-app `terminal-emulator/` | Apache-2.0 |
| terminal-view（TerminalView 渲染） | termux/termux-app `terminal-view/` | Apache-2.0 |
| bootstrap 根文件系统（25.5MB） | termux/termux-packages release `bootstrap-2022.01.07-r1` | 各包独立（GPL/BSD/MIT 混合） |
| 安装器/会话/UI/命令桥 | AnWind 自研 | MIT（随项目） |

---

## 2. 包名与路径迁移（核心问题）

### 2.1 问题：官方产物把路径"焊死"在哪里

对官方 bootstrap-aarch64.zip 的实测统计（3194 条目 / 57.5MB 未压缩）：

- **227 / 228 个 ELF 二进制**内部（`.rodata`/`.dynstr` 字符串表）硬编码了
  `/data/data/com.termux/files/usr` —— 包括 bash、apt、dpkg、coreutils 等；
- **501 个文本文件**共 5143 处硬编码 —— 脚本 shebang
  （`#!/data/data/com.termux/files/usr/bin/sh`）、apt/dpkg 配置、dpkg 数据库
  （`usr/var/lib/dpkg/`）；
- **SYMLINKS.txt** 中 3 条绝对路径符号链接。

### 2.2 解法：同长度字节重写（等效于"按 com.anwind 重新编译"）

AnWind 的 applicationId 是 `com.anwind`，与 `com.termux` **恰好同为 10 个字符**。
因此：

```
/data/data/com.termux/files/   (22 字节)
/data/data/com.anwind/files/   (22 字节)  ← 严格等长
```

安装器（`TermuxBootstrapInstaller.rewriteLegacyPaths`）在解压每个文件时执行
**原地等长替换**：

- 文本文件：替换后长度不变 → shebang / 配置 / dpkg 数据库全部指向新前缀；
- ELF 二进制：文件字节数完全不变 → 段偏移、字符串表结构、任何二进制结构
  都不受影响，动态链接器照常加载；
- 替换后无任何 `com.termux` 数据路径残留。

**在真实官方 bootstrap 上的验证结果**（scripts/verify_rewrite.py，等效算法）：

```
普通文件总数: 3016
发生重写的文件数: 500      ← 文本+二进制合计
替换总次数: 5814
长度变化文件数: 0          ← ELF 结构完好
残留旧路径文件数: 0
ELF 结构抽查通过: 5 个（bash/apt/coreutils/libapt-pkg/libcrypto）
SYMLINKS.txt 绝对路径行: 3 → 全部映射到 com.anwind
```

这等价于"把 71 个官方包全部按 `--prefix=/data/data/com.anwind/files/usr`
重新编译了一遍"的最终效果 —— 但无需 termux-packages 的 Docker 构建链，
也无需逐包重编。

> **改包名须知**：此机制要求新包名长度 ≤ `com.termux`（10 字符）。
> 若将来把 applicationId 改成更长的名字，安装器会显式报错而不是静默损坏
> （防御分支见代码）；更长的包名需要改用 termux-packages 自建 bootstrap。

### 2.3 Java/C 包名重命名

| 上游 | AnWind |
|------|--------|
| `com.termux.terminal.*`（13 类） | `com.anwind.termux.terminal.*` |
| `com.termux.view.*`（7 类） | `com.anwind.termux.view.*` |
| JNI 符号 `Java_com_termux_terminal_JNI_*` | `Java_com_anwind_termux_terminal_JNI_*` |
| 资源 `com.termux.view.R` | `com.anwind.R`（strings/drawables 已合入 app 模块） |

JNI 函数名与 Kotlin 声明严格对应（`TermuxBridge` 检查点）：
`libtermux.so` = `termux.c`（上游 PTY 原版，仅符号改名）+
`anwind_bridge.c`（AnWind 专有 FIFO 桥，非上游代码）。

---
### 2.4 官方源软件包的前缀重打包（v2.22.1 包工具链，fix7 修订）

bootstrap 重写只覆盖随 APK 内置的根文件系统；`pkg install` 从官方源下载的
deb 是**按 `com.termux` 前缀构建**的——tar 成员路径本身就是绝对路径
`./data/data/com.termux/files/usr/...`，而 dpkg 以 `instdir=/` 按成员路径
落盘，不处理就会把文件写进别的应用的数据目录（直接安装失败）。

> **fix7 修复的致命缺陷**：旧版（fix6 及以前）包装器只部署在
> `bin/dpkg` 一处。dpkg 包**自升级**时，deb 内的 `bin/dpkg` 真身 ELF
> 会覆盖 `bin/dpkg`（包装器脚本）——此后 apt/pkg 全部绕过 deb 重打包，
> `pkg update` / `pkg install` 全线报
> `unable to stat './data/data/com.termux': Permission denied`。
> 实测故障链：tar 升级成功 → dpkg 自升级成功（包装器被杀）→
> findutils 失败 → 之后所有安装失败。fix7 以"三层布局 + apt 钉扎 +
> 双重自愈"根治，并对 dpkg 升级本身免疫。

```
pkg install X
  └─ apt 下载 deb（官方源，校验签名/哈希）
       └─ apt.conf.d/99anwind: Dir::Bin::dpkg → libexec/anwind/dpkg
            （apt/pkg 永远经包装器；libexec/anwind 不属于任何包，
              升级永不覆盖 —— 与 bin/dpkg 状态无关）
            ├─ anwind-debfix <deb>          ① 安装前：重打包
            │    dpkg-deb -R 解包
            │    anwind-reprefix --tree     等长改写目录名/文件内容/链接目标
            │    dpkg 包自升级时：新真身同步刷新 dpkg.real
            │    dpkg-deb -b 原子回写       （成员路径变为 com.anwind）
            ├─ dpkg.real --unpack …         ② 解包到 /data/data/com.anwind/...
            └─ anwind-reprefix --quiet      ③ 装完后：按 dpkg info/*.list
                                               增量重写（安全网，幂等）
```

**dpkg 包装器三层布局**（`assets/termux/scripts/anwind-dpkg` v2）：

| 路径 | 角色 | 升级行为 |
|------|------|----------|
| `libexec/anwind/dpkg` | 包装器**本体**（apt 经 Dir::Bin::dpkg 固定调用） | 无任何包拥有此路径，永不覆盖 |
| `libexec/anwind/dpkg.real` | dpkg **真身** | anwind-debfix 在 dpkg 包自升级时自动同步新版本 |
| `bin/dpkg` | 包装器**副本**（用户直接调用入口） | 被 dpkg 升级覆盖后由包装器自愈恢复 |

**双重自愈**（包装器每次运行前后执行）：

- 运行前：`bin/dpkg` 若已被覆盖为真身 ELF（上一次升级后未恢复），
  先提升为 `dpkg.real`（仅当比现役真身新，保证版本最新），再从本体
  原子恢复副本；
- 运行后：本次若安装了 dpkg 包（副本刚被覆盖），再次原子恢复；
- `anwind.sh` 在每个会话启动时兜底执行同一自愈（静默）。

- **anwind-reprefix**（`cpp/termux/anwind_reprefix.c`，可执行）：等长字节
  替换引擎，`--file`（单文件）/ `--tree`（目录树）/ 清单增量（stamp 记账）
  三种模式；只对含 `com.termux` 的文件做 mmap 原地写，其余零改动。
- **等长改名**：deb 解包后 `data/data/com.termux/` 目录直接
  `rename()` 为 `com.anwind/`（同名等长，内容不改）；
- **维护者脚本权限归一**：`dpkg-deb -b` 要求 preinst/postinst/prerm/postrm
  权限在 0555–0775，社区 deb 常有 644 脚本，debfix 重建前统一 chmod；
- **幂等记账**：debfix 按 `文件名+大小+mtime` 记账（`var/lib/anwind/debfix/`）。
  fix7 加固：anwind-reprefix 缺失或执行失败时**不写 stamp**（旧版会把
  未重写的 deb 永久标记为已处理——盖章污染），下次调用自动重试；
- **覆盖面**：apt / pkg / 直接 `dpkg -i` / `grun-install` 全部走包装器；
  apt 的 `DPkg::Post-Invoke` 未使用（termux apt 的钩子 shell 路径不保证），
  机制不依赖任何 apt 钩子协议。
- **已知取舍**：① 重建后的 deb 与索引哈希不一致，apt 在后续安装时会对该
  缓存重新下载（正常现象，每次安装仅一轮）；② `dpkg --verify` 会因内容
  等长改写报 md5 差异（不影响安装与运行）。
- **dpkg 自升级全链路（fix7 后）**：升级事务中 debfix 先把新真身同步到
  `dpkg.real` → `dpkg.real` 解包覆盖 `bin/dpkg` → 包装器运行后自愈恢复
  `bin/dpkg` 副本；apt 侧始终经 `libexec/anwind/dpkg`，全程无感。


---

## 3. 为什么 targetSdk 必须降到 28

Android 10 引入的 SELinux 策略：**targetSdk ≥ 29 的应用被禁止 exec()
自己数据目录（app_data_file）中的二进制**（W^X 限制）。

- Termux 环境的一切程序都位于 `/data/data/com.anwind/files/usr/bin/`；
- 官方 Termux 为此从 2019 年起**永久锁定 targetSdk 28**；
- AnWind v2.22 起同样锁定 targetSdk 28（app/build.gradle.kts 有完整注释）。

对 AnWind 现有功能无实质影响：SAF / Room / DataStore / Compose / WebView /
Launcher / 前台服务均不依赖 targetSdk ≥ 29。唯一可见代价：Android 10+ 安装时
提示"此应用为旧版 Android 打造"（与官方 Termux 显示的提示相同）。

---

## 4. 安装流程（TermuxBootstrapInstaller）

首次打开"终端"时自动执行（进度可视化，仅需一次）：

1. 校验设备 ABI → `arm64-v8a` ↔ `bootstrap-aarch64`（离线归档内置在
   `assets/termux/bootstrap-aarch64.zip`，SHA-256 与官方构建脚本 pinned 值
   一致：`0fe6d015…14cd`，篡改/损坏会中止安装）；
2. 解压到 staging 目录 `$filesDir/usr-staging`，逐文件执行 §2.2 的路径重写；
3. 按官方规则设置权限：`bin/`、`libexec/`、`lib/apt/apt-helper`、
   `lib/apt/methods` 下 chmod 0700；staging 与 prefix 根目录 0700；
4. 解析 `SYMLINKS.txt` 创建 1070 条符号链接（绝对路径目标同步重写）；
5. 原子重命名 `usr-staging` → `usr`；创建 `$filesDir/home`、`$PREFIX/tmp`；
6. 安装 AnWind 增强层（见 §5）；
7. 清理缓存归档。

重装/修复：设置中心清除应用数据或删除 `files/usr` 后重开终端即可重装。

---

## 5. AnWind 桌面 ↔ Termux shell 命令桥

官方 bootstrap 安装完成后，安装器向真实 bash 注入
`$PREFIX/etc/profile.d/anwind.sh`，提供桌面联动命令：

| shell 命令 | 效果 |
|-----------|------|
| `theme win95\|xp\|win7\|win10\|win11` | 切换 AnWind 桌面主题 |
| `start browser`（或 files/notepad/calc/settings/music…） | 打开对应 AnWind 应用 |
| `apps` | 列出可打开的应用 |
| `open <url>` | 用 AnWind 浏览器打开网址 |
| `winver` | 显示版本信息（打开系统信息应用） |

**机制**（AnWind 专有，不依赖 termux-am/系统 am）：

```
shell 函数 → printf 命令到 $PREFIX/var/anwind.cmd（FIFO，后台子 shell 写入，
             绝不阻塞终端）
AnWind 主进程 → AnWindShellBridge 后台线程循环 read FIFO →
                Handler 主线程分发（ThemeManager / WindowManager）
```

FIFO 由 `anwind_bridge.c` 的 `TermuxBridge.createFifo()` 创建（Java 标准库
没有 mkfifo）。写端用 `( cmd > fifo & )` 后台子 shell，即使 App 进程被杀也
不会挂起终端。

---

## 6. 终端 UI 与快捷键

- **TerminalView**（官方移植版）经 Compose `AndroidView` 嵌入浮动窗口；
  pinch 双指缩放改字号、长按进入文本选择（选择手柄资源已合入 app res）。
- **会话模型**：会话不随窗口关闭销毁（与 Termux 后台会话一致）；
  `exit` 结束会话后显示"新建会话"覆盖层；窗口标题跟随 bash 的 OSC 标题。
- **快捷键栏（两排 + 符号层）**：
  - 第一排：`ESC` `CTRL` `ALT` `TAB` `←` `↑` `↓` `→`
  - 第二排：`HOME` `PGUP` `PGDN` `END` `~` `-` `⇧` `FN`
  - `SYM` 切换第二排为符号层（`| \ " ' : ; $ @ # % …` 可横滑）
- **修饰键语义与官方 ExtraKeys 一致**：点击=粘滞一次性（作用于下一个按键
  后自动释放）；**长按=锁定**；状态由 `TerminalViewClient.readControlKey()`
  等轮询消费（读后自动释放，与上游 `readSpecialButton(autoSetInActive=true)`
  语义一致）。
- **按键注入协议**与官方 `TerminalExtraKeys` 完全一致：键码键走
  `TerminalView.onKeyDown(keyCode, KeyEvent(ACTION_UP, meta))`；字符键走
  `TerminalView.inputCodePoint(cp, ctrl, alt)`。

---

## 7. 使用指南

```bash
# 软件包管理（真实 apt，官方源）
pkg update                 # 更新软件源索引
pkg install python nodejs git vim nano openssh ffmpeg …
pkg search <关键字>
apt list --installed

# glibc 运行环境（官方 gpkg 流程，v2.22.1 起开箱可用）
anwind-glibc               # 一键：pkg install glibc-repo && glibc-runner
grun ./a.out               # 以 glibc 环境运行（glibc-runner 提供）
grun-install <deb>         # 安装 glibc 版 .deb

# 与官方 Termux 的差异
# - 服务器端（termux-api 等 companion app）功能不可用（未安装对应 App）
# - termux-setup-storage 需手动执行且受限（AnWind 已有文件管理器，建议用 SAF）
# - bootstrap 为 2022.01.07-r1 官方版；pkg update 后即与最新源同步
# - 官方源 deb 由 dpkg 包装器自动重打包为 com.anwind 前缀（见 §2.4）

# AnWind 桌面联动
theme win11
start music
open https://github.com
```

已知限制：

- 离线 bootstrap 仅含 **aarch64**（arm64-v8a）。其他架构（arm/x86_64/x86）
  打开终端会收到明确的架构提示。如需扩展：下载官方对应架构 bootstrap 放入
  `assets/termux/`（命名 `bootstrap-<arch>.zip`）并在
  `TermuxEnvironment.deviceBootstrapArch()` 确认映射即可，安装器零改动。
- 会话生命周期与 App 进程一致（AnWind 作为 Launcher 通常常驻）。
  Termux 官方的 `termux-wake-lock` 防杀机制需要前台服务，v2.22 未移植。
- `~/.termux/termux.properties` 的部分键（字体/颜色）由 Termux:Styling
  companion 读取，本移植中无效；终端字体大小用工具栏 `A＋/A－` 或双指缩放。

---

## 8. 构建要求

- Android Studio + JDK 17 + **NDK 26.3.11579264**（版本在
  `app/build.gradle.kts` 锁定，SDK Manager 直接安装同名版本）
- `targetSdk 28` 是**功能性约束**，不要"顺手升级"（见 §3）
- GitHub Actions 工作流已增加 NDK 安装步骤（`.github/workflows/build.yml`）
- 原生库 `libtermux.so` 与可执行 `libanwind_reprefix.so` 经 ndkBuild 构建
  （`app/src/main/cpp/termux/Android.mk`；可执行以 lib*.so 命名才会被 AGP
  打包进 APK，安装期拷贝为 `$PREFIX/bin/anwind-reprefix`）

---

## 9. 许可证合规

- `terminal-emulator` / `terminal-view` 源码：**Apache-2.0**（termux-app 仓库
  LICENSE.md 的官方例外条款，源自 jackpal/Android-Terminal-Emulator）。
  已按 Apache-2.0 要求保留来源声明并注明修改（包名重命名），
  许可证全文见 `docs/LICENSE-APACHE-2.0.txt`。
- bootstrap 根文件系统内的二进制包遵循各自的上游许可证
  （termux-packages 构建产物，GPL/BSD/MIT 混合，见
  https://github.com/termux/termux-packages）。
- AnWind 自研部分（安装器/会话控制器/命令桥/UI）：随项目 MIT。

---

## 10. 文件清单

```
app/src/main/
├── cpp/termux/                    # 原生源码（NDK ndkBuild）
│   ├── termux.c                   # 上游 PTY/JNI（符号改名）
│   ├── anwind_bridge.c            # AnWind FIFO 桥
│   ├── anwind_reprefix.c          # 包前缀等长重写引擎（可执行）
│   └── Android.mk
├── java/com/anwind/termux/        # 上游 Java（包名改名，Apache-2.0）
│   ├── terminal/                  # 13 个类
│   └── view/                      # 7 个类 + textselection/
├── java/com/anwind/apps/terminal/
│   ├── TerminalApp.kt             # 真实终端 UI
│   ├── SimpleTerminalApp.kt       # 旧模拟终端（简易终端，并存）
│   └── termux/
│       ├── TermuxEnvironment.kt           # 路径/环境变量/架构
│       ├── TermuxBootstrapInstaller.kt    # 安装器+路径重写引擎
│       ├── TermuxSessionController.kt     # 会话+双 Client 接口实现
│       ├── AnWindShellBridge.kt           # shell→桌面命令桥
│       └── (TermuxBridge.kt 位于 termux/terminal/)
├── assets/termux/
│   ├── bootstrap-aarch64.zip      # 官方 bootstrap（SHA-256 校验）
│   └── scripts/                   # anwind.sh / motd / anwind-dpkg /
│                                  # anwind-debfix / anwind-glibc 定稿
└── res/
    ├── drawable/text_select_handle_*.xml  # 文本选择手柄（上游）
    └── values/strings.xml         # +3 条终端字符串
```
