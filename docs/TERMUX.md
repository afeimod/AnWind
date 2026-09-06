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
### 2.4 官方源软件包的前缀重打包（v2.22.1 包工具链，fix8.4 修订）

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
>
> **fix8 补充的双重保险（path-exclude）**：即使 debfix 全链路正常，
> 官方 deb 偶发含未经重写的 `/data/data/com.termux` 成员（典型如
> 裸目录条目 `./data/data/com.termux`，无尾斜杠，早期 reprefix 的
> "前缀+尾斜杠"匹配恰好漏掉）——dpkg 以 instdir=/ 按成员路径
> lstat 时对官方包名前缀无权访问，报
> `unable to stat './data/data/com.termux': Permission denied`。
> fix8 写入两层配置让 dpkg 直接【跳过】这些成员而非报错（它们
> 本就不该落入 AnWind 沙箱，跳过无副作用）：
>
> | 文件 | 内容 | 作用 |
> |------|------|------|
> | `etc/dpkg/dpkg.cfg.d/99-anwind-fix` | `path-exclude=/data/data/com.termux`、`path-exclude=/data/data/com.termux/*`、`force-confold` | dpkg 真身直接读取，任何调用路径生效 |
> | `etc/apt/apt.conf.d/99-anwind-fix` | `DPkg::Options:: "--path-exclude=…"` ×2、`"--force-confold"` | apt 命令行再补一遍，dpkg.cfg.d 意外丢失也兑得住 |
>
> `force-confold` 同时消除 bash.bashrc / profile 升级时的
> Y/I/N/O/D/Z 交互提示。
>
> ⚠ 这两份配置含 `com.termux` 字面量，【不能】打进 bootstrap zip——
> 解压时全量重写会把 `com.termux` 改成 `com.anwind`，配置失效。
> 只能运行时写入，三路写入互为兑底：安装器 `writeDpkgPathExclude()`
> （全新安装 + 存量迁移，打开 App 即自动迁移）、
> `profile.d/anwind.sh`（每次会话启动）、`anwind-dpkg` 包装器
> （每次 dpkg 调用前检查，缺失才写）。
>
> **fix8.1 修正（实测推翻 path-exclude 兑底假设）**：在 Debian
> dpkg 1.22 上实测复现证明，dpkg 的 filter（path-exclude）检查发生在
> 成员 lstat **之后**——对 "unable to stat ... Permission denied"
> 的 EACCES 场景，无论配置文件还是命令行参数都**无效**（被排除的
> 成员仍会先 stat 并炸出）。因此兑底主力改为 **anwind-debfix v3**：
>
> - **全部静默失败路径 fail-loud + 不盖章可重试**：旧版在
>   `dpkg-deb -b` 重建失败时静默继续并照常盖章（记账污染），该 deb
>   从此不再重写，安装永远失败且无任何提示——正是同一批包反复
>   失败却查不到原因的结构性根因；
> - **目录改名降级保底**：reprefix 缺失/执行失败/静默失效时，至少
>   把树中 `data/data/com.termux` 目录改名——dpkg 报错的正是这个
>   目录成员，改名后 unpack 即可通过（沙箱对照实验：官方 deb
>   EACCES 失败，降级成品 rc=0 安装成功）；
> - **重建成品验收**：`dpkg-deb -b` 后用 `dpkg-deb -c` 复查成员，
>   仍含 `com.termux` 则不替换不盖章。
>
> debfix v3 盖章规则收敛为一条：**只有确认成品无官方前缀成员才
> 盖章**。存量安装修订号 +1（rev 6）触发迁移，同时清理历史盖章
> 缓存（旧版静默失败留下的污染章）。
>
> **fix8.2（rev 7，记账免疫）**：实测确认死闭环——v2 静默失败盖章的
> 官方 deb 一直留在 apt 下载缓存中（mtime 恒定），apt 硬链接复用时
> 记账（文件名+大小+mtime）命中直接跳过重写，同一批包永远失败且无
> 提示（bash/xxhash/apt 能成功恰因它们首次处理成功或无需重写）。
> 两道切断：① debfix v4 在记账命中时也快检成员（dpkg-deb -c 流式
> + grep -m1，官方 deb 的 com.termux 条目在 tar 前部秒停），残留即
> 强制重跑；② 迁移时清空 apt archives 下载缓存，强制重新下载。
>
> **fix8.3（rev 8，引擎级收口 + 部署指纹）**：容器复测证明脚本逻辑
> 无误后，剩余风险全部收敛到“设备端重写引擎自身的静默失效”。v5
> 把引擎与验收闭环做到无死角：① reprefix 只读权限文件（tar 保留的
> 0444/0555，如 man 页/部分配置）内容改写曾因 open(O_RDWR) EACCES
> 被【静默跳过】，现临时加写位重试、完成后还原权限；② 树内目录
> 改名失败不再静默（目标已存在/权限异常），--tree 以非零退出，
> debfix 据此走目录改名降级路径真正修复；③ debfix 重写完成后调用
> reprefix --verify 独立复检树中残留（目录/文件名/链接目标/文件
> 内容），一切静默失效显形为可见告警；④ 验收/记账快检 grep 收紧
> 为任意 com.termux 成员；⑤ 部署指纹：anwind-reprefix --version、
> anwind-debfix version 子命令与每个会话启动打印的
> “anwind: pkg 修复链路已激活 (fix8.3, rev 8)” 一行——看不到该行
> 即说明设备仍在运行旧版 APK（源码修复必须构建安装后才会生效，
> 这也是历次“改了却没变化”反馈的最常见原因）。
>
> **fix8.4（rev 9，检测层根治——消灭唯一静默分支）**：rev 8 上机
> 日志证明新 APK 已在设备运行（横幅 + `anwind-debfix v5` 摘要行），
> pcre 走完整重写路径成功（count=7，与宿主同引擎逐字一致），但同
> 批 13 包全部静默失败且零警告。逐分支排除后唯一自洽解释：记账
> 命中后的 `dpkg-deb -c` 快检——**-c 的成员列表由外部 `tar -tv`
> 生成**（解压内建、列表 exec "tar"，钩子法实测确认），设备端该
> 列表一旦为空/失败（2>/dev/null 吞掉报错），grep 匹配不到即被误判
> “干净”→ 脏 deb 永久静默跳过。v6 检测器重构：
>
> - **字节级扫描 scan_deb**：`dpkg-deb --fsys-tarfile`（内建解压，
>   实测不 exec 任何外部程序）导出数据 tar → `grep -aqm1
>   'com\.termux'` 字节级扫描（成员名+文件内容一次覆盖）；
>   dpkg-deb 失败/输出为空 → 返回“无法验证”，**绝不当作干净**；
> - **记账命中三分支**：干净→幂等跳过；脏→撕章强制重跑；无法
>   验证→提示后走完整重写（安全侧）；
> - **成品回验路径硬门**：重建 deb 先 `-R` 回解再跑与树侧相同的
>   `find -name '*com.termux*'` 检查，对【成品成员路径】直接负责
>   （不信任 -b 行为，不依赖外部 tar）；内容级残留仅大声告警
>   （不影响 unpack，由安装后 reprefix 安全网继续处理）；
> - **包装器兜底重试（v3）**：dpkg 失败且有 deb 参数时，对 deb 再
>   跑一轮 debfix（字节扫描命中即修复），确有修复发生时自动重试
>   一次——即使前置检测全部误判，同一命令内也能自愈；
> - 真实 deb 终测（TUNA 同批 15 包）：原始 nano 在 EACCES 根复现
>   设备故障签名 → v6 处理后解包零 stat 错误、落盘 com.anwind 路径；
>   15 包成品成员路径零残留；历史章+mtime 钉死+扫描器异常三重
>   恶劣叠加下 v6 强制重跑成功（v5 在此场景永久静默跳过）。

```
pkg install X
  └─ apt 下载 deb（官方源，校验签名/哈希）
       └─ apt.conf.d/99anwind: Dir::Bin::dpkg → libexec/anwind/dpkg
            （apt/pkg 永远经包装器；libexec/anwind 不属于任何包，
              升级永不覆盖 —— 与 bin/dpkg 状态无关）
            ├─ anwind-debfix <deb>          ① 安装前：重打包（v6：字节级
            │    dpkg-deb -R 解包             快检+回验硬门；无法验证
            │    anwind-reprefix --tree       一律按脏处理）
            │    （等长改写目录名/文件内容/链接目标）
            │    dpkg 包自升级时：新真身同步刷新 dpkg.real
            │    dpkg-deb -b 原子回写       （成员路径变为 com.anwind）
            ├─ dpkg.real --unpack …         ② 解包到 /data/data/com.anwind/...
            │    dpkg.cfg.d/99-anwind-fix  path-exclude 兑底：跳过漏网的
            │    │                          com.termux 成员（fix8）
            │    └─ 失败且有 deb 参数 → debfix 再扫一轮，确有修复自动
            │                               重试一次（fix8.4 兕底）
            └─ anwind-reprefix --quiet      ③ 装完后：按 dpkg info/*.list
                                               增量重写（安全网，幂等）
```

**dpkg 包装器三层布局**（`assets/termux/scripts/anwind-dpkg` v3）：

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
  替换引擎，`--file`（单文件）/ `--tree`（目录树）/ `--verify`（复检残留）/
  `--version`（版本指纹）/ 清单增量（stamp 记账）多种模式；只对含
  `com.termux` 的文件做 mmap 原地写，其余零改动；只读权限文件自动
  加写位改写后还原（v5），树内改名失败会置错误位并以非零退出（v5）。
- **等长改名**：deb 解包后 `data/data/com.termux/` 目录直接
  `rename()` 为 `com.anwind/`（同名等长，内容不改）；
- **维护者脚本权限归一**：`dpkg-deb -b` 要求 preinst/postinst/prerm/postrm
  权限在 0555–0775，社区 deb 常有 644 脚本，debfix 重建前统一 chmod；
- **幂等记账**：debfix 按 `文件名+大小+mtime` 记账（`var/lib/anwind/debfix/`）。
  fix7 加固：anwind-reprefix 缺失或执行失败时**不写 stamp**（旧版会把
  未重写的 deb 永久标记为已处理——盖章污染），下次调用自动重试；
  fix8.4 加固：命中记账也必须字节级验证（干净才跳过；扫描器异常
  视为未处理，走完整重写）——v5 时代设备端 13 包静默失败的唯一
  分支已在此消灭。
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
# - 官方源 deb 由 dpkg 包装器自动重打包为 com.anwind 前缀（见 §2.4）；
#   path-exclude 双保险跳过漏网的 com.termux 成员（fix8）

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
