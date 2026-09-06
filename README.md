# AnWind

> 一个 Android 平台的 Windows 风格桌面模拟器，支持 Win95 / XP / 7 / 10 / 11 五代主题切换，内置浏览器、文件管理器、**真实 Termux 终端**等 10 个应用，支持自定义快捷方式。

## 项目简介

AnWind 是一个用 **Kotlin + Jetpack Compose** 编写的 Android 桌面启动器（Launcher），将手机桌面改造为 Windows 操作系统的视觉体验。整个项目作为独立 GitHub 仓库交付，可克隆、编译、二次开发。

## 演示图

* 简单演示图

![donate](https://github.com/afeimod/AnWind/blob/main/Screenshot_2026-09-06-15-57-23-134_com.anwind.jpg?raw=true)


### 核心特性

- **5 套 Windows 主题切换** — Windows 95 / XP / 7 / 10 / 11，每套主题都有独立的任务栏样式、窗口边框、配色方案和壁纸，切换后整个 UI 焕然一新
- **真实 Termux 终端（v2.22）** — 完整移植官方 Termux（termux-app v0.118.0）：真实 PTY + login shell、官方 bootstrap 根文件系统（apt/dpkg/pkg 全家桶）、pkg 安装真实软件包（python/git/openssh…）、两排快捷键栏、AnWind 桌面联动命令（theme/start/open），详见 [docs/TERMUX.md](docs/TERMUX.md)
- **混合窗口模型** — 主要应用（浏览器/文件管理器）全屏运行，辅助应用（记事本/计算器/设置）以可拖拽、可缩放、可最小化/最大化的浮动窗口运行
- **完整浏览器** — 基于 WebView 重新实现，支持多标签页、前进/后退/刷新、地址栏、书签、历史记录、首页快捷导航，**支持通过 SAF 读取本地 HTML 文件并渲染**
- **快捷方式系统** — 长按桌面空白处弹出右键菜单 → 新建快捷方式，支持网页 URL / 本地 HTML 文件 / 应用 三种类型，可自定义名称和 emoji 图标
- **9+1 个内置应用** — 浏览器、文件资源管理器、设置、记事本、计算器、系统信息、图片查看器、时钟、终端（真实 Termux + 简易终端并存）
- **可作为默认 Launcher** — 在 Manifest 中注册了 `HOME` category，可选择设为系统桌面
- **持久化存储** — 使用 Room 数据库保存快捷方式/书签/历史记录，DataStore 保存偏好设置

## 技术栈

| 维度 | 选择 |
|------|------|
| 语言 | Kotlin 1.9.25 |
| UI 框架 | Jetpack Compose (BOM 2024.06) |
| 架构 | 单 Activity + Compose + 全局 WindowManager 单例 |
| 数据库 | Room 2.6.1 + KSP |
| 偏好存储 | DataStore Preferences |
| 异步 | Kotlin Coroutines + Flow |
| 浏览器内核 | Android WebView + WebKit |
| 最小 SDK | 24 (Android 7.0) |
| 目标 SDK | 28（Termux W^X 约束，同官方 Termux；详见 docs/TERMUX.md §3） |
| 原生库 | NDK 26.3 ndkBuild → libtermux.so（PTY/FIFO 桥） |
| 构建 | Gradle 8.7 + AGP 8.5.2 |

## 快速开始

### 环境要求

- **Android Studio** Hedgehog (2023.1.1) 或更高版本
- **JDK 17**
- **Android SDK** API 34（编译）/ API 24+（运行）
- **NDK 26.3.11579264**（Termux 原生库编译，SDK Manager 安装同名版本）
- **Gradle 8.7**（项目自带 wrapper 配置）

### 设为默认 Launcher（可选）

应用在 Manifest 中已注册 `HOME` category。安装后：

- 按下 Home 键 → 系统弹出 Launcher 选择器 → 选择 `AnWind`
- 或在 `设置 → 应用 → 默认应用 → 桌面` 中选择 `AnWind`

## 使用指南

### 切换 Windows 主题

**方法一：设置中心**

打开开始菜单 → 设置 → 主题 → 选择任意主题（Win95/XP/7/10/11）→ 整个界面立即变换

**方法二：终端命令（真实 Termux shell）**

打开开始菜单 → 终端 → 首次自动安装 Termux 环境（约 30-60 秒，仅需一次）后输入：

```
theme win95
theme xp
theme win7
theme win10
theme win11
```

### 创建快捷方式

1. **长按桌面空白处** → 弹出右键菜单
2. 点击 **新建快捷方式**
3. 选择类型：
   - **网址** — 输入 URL（如 `https://github.com`）
   - **本地HTML** — 点击"选择文件"，通过 SAF 选择 `.html` 文件
   - **应用** — 从列表选择已注册的应用
4. 输入名称，选择 emoji 图标
5. 点击 **创建** — 快捷方式立即出现在桌面

### 浏览器使用

- **多标签**：点击 `+` 新建标签，点击标签切换，点击 `×` 关闭
- **本地 HTML**：在文件管理器中点击 `.html` 文件，自动用浏览器打开
- **书签**：点击 ☆ 添加/移除当前页书签，点击书签图标查看所有书签
- **历史**：点击历史图标查看最近访问，自动记录每次访问
- **快捷导航**：首页包含必应/百度/B站/GitHub/知乎/微博/YouTube/Wikipedia 等常用网站
- **真全屏**：点击全屏图标进入浏览器全屏；点击**左上角悬浮圆钮**或按返回键退出（v2.16 起不再支持双击页面退出，避免与网页游戏的双击操作冲突）
- **3D 视角旋转**（v2.16）：点击工具栏 3D 旋转图标打开"浏览器设置"，可对整个网页做俯仰(X)/偏航(Y)/滚转(Z)三轴 3D 透视旋转并调节透视视距，用于电脑网页游戏的 3D 视角观察，设置自动保存

### 显示缩放（v2.16）

- **100% = 紧凑布局**：设置 → 显示 → UI 缩放的 100% 档位现在以旧版 60% 的效果渲染，整体桌面（任务栏、窗口、图标、文字、右键菜单）更小巧；仍显示为 100% 选项
- **范围扩展**：滑杆范围扩至 60%–300%，需要旧版大小或更大界面时可向上调节（300% ≈ 旧版 180%）
- **右键菜单同步**：桌面右键菜单在 v2.16 缩小约一半（宽 216→112dp），并继续跟随 UI 缩放同步缩放

### 云音乐（v2.17 新增）

网易云音乐 PC 版风格的在线音乐播放器，界面对照网易云电脑版（左侧导航栏 + 歌曲列表 + 底部播放条），歌词秀为 3D 透视样式。

- **在线搜索播放** — 酷我音乐 API（搜索 / 多源播放链接解析），点击即播、加载更多分页
- **3D 歌词秀** — 底栏「词」按钮或点击封面进入：封面卡片 + 旋转 CD 光盘 + 3D 透视歌词墙（当前行放大发光、其余行按距离倾斜渐隐），点击任意行跳转播放；歌词自动匹配（酷我逐行歌词 → 网易云歌词兜底，含翻译）
- **我喜欢 / 最近播放** — 收藏红心、播放记录自动持久化（应用私有目录 JSON）
- **本地音乐** — 扫描手机曲库（MediaStore），支持收藏与歌词匹配
- **下载管理** — 歌曲下载（自动附带 .lrc 歌词文件）、失败重试、进度显示；优先保存到 `Music/AnWindMusic`
- **播放控制** — 顺序 / 单曲循环 / 随机、音量调节、进度拖拽、自动连播

### 终端（真实 Termux，v2.22）

终端现在是完整的官方 Termux 移植（详见 [docs/TERMUX.md](docs/TERMUX.md)）：

```bash
pkg update && pkg install python git vim openssh   # 真实 apt 官方源，安装真实软件包
pkg search ffmpeg                                    # 搜索
ls / cp / mv / grep / sed / curl …                   # 全套 GNU 工具链（71 个预装包）
termux-info                                          # 环境信息
```

**AnWind 桌面联动命令**（注入到真实 bash 的 profile.d）：

| 命令 | 作用 |
|------|------|
| `theme <variant>` | 切换主题（win95/xp/win7/win10/win11） |
| `start <app>` | 启动应用（browser/files/notepad/calc/settings/music…） |
| `apps` | 列出可启动应用 |
| `open <url>` | 用 AnWind 浏览器打开网址 |
| `winver` | 显示版本信息 |

**快捷键栏**：第一排 ESC/CTRL/ALT/TAB/方向键；第二排 HOME/PGUP/PGDN/END/⇧/FN，
SYM 键切换符号层。修饰键点击=粘滞一次，长按=锁定（对齐官方 ExtraKeys）。

**旧版模拟终端**保留为独立的“简易终端”应用（不占桌面图标，开始菜单可找到），
无需安装 bootstrap 即可用。

## 主题视觉差异

| 主题 | 任务栏 | 窗口风格 | 壁纸 |
|------|--------|----------|------|
| Win95 | 灰色左对齐 | 方角粗边框，海军蓝标题栏 | 经典青绿色 |
| XP | 蓝色左对齐，绿色开始按钮 | Luna 蓝色渐变标题栏，圆角 | 蓝天草地 |
| Win7 | 深色半透明左对齐 | Aero 玻璃效果，圆角 | 蓝色光晕 |
| Win10 | 黑色半透明左对齐 | 扁平深色，方角 | 深蓝抽象 |
| Win11 | 浅色半透明居中 | Mica 材质，大圆角 | Bloom 花瓣 |

## 二次开发

### 添加新应用

1. 在 `app/src/main/java/com/anwind/apps/` 下新建包，例如 `myapp/`
2. 创建 `MyApp.kt`，定义 `AppDef`：

   ```kotlin
   val MyApp = AppDef(
       id = "my_app",
       displayName = "我的应用",
       iconAsset = "emoji:🚀",  // 或 assets 路径
       launchMode = LaunchMode.FLOATING,
       defaultWidth = 480.dp,
       defaultHeight = 360.dp,
       pinnedToDesktop = true
   ) { scope ->
       // 你的 Composable 内容
       Text("Hello from MyApp!")
   }
   ```

3. 在 `AppBootstrap.kt` 中注册：

   ```kotlin
   AppRegistry.register(MyApp)
   ```

4. 重新构建，应用会自动出现在桌面和开始菜单

### 添加新主题

1. 在 `WinTheme.kt` 的 `WindowsVariant` 枚举中添加新值
2. 在 `Themes` object 中添加新的 `WinTheme(...)` 定义
3. 在 `ThemeManager.variantToTheme()` 中添加映射
4. 重新构建

## 启动音效（可选）

由于版权原因，项目不附带微软原始启动音效。如需启用：

1. 合法获取各 Windows 版本启动音效 mp3
2. 重命名并放入 `app/src/main/assets/sounds/`：
   - `win95.mp3`
   - `winxp.mp3`
   - `win7.mp3`
   - `win10.mp3`
   - `win11.mp3`
3. 重新构建

音效文件缺失时应用静默跳过，不影响功能。

## 已知限制

- **WebView 命令式操作**：当前 `goBack()` / `goForward()` / `refresh()` 通过 WebView 实例引用调用，配置变更后可能需要重新绑定（多数场景下正常工作）
- **浮动窗口拖拽**：使用 `detectDragGestures` 直接更新坐标，未实现边缘吸附
- **文件管理器虚拟 FS**：当前为硬编码模拟结构，未持久化用户创建的文件
- **启动音效**：需用户自行放入 mp3 文件
- **图标渲染**：使用 emoji 作为应用图标兜底，正式发布建议替换为矢量图标

## 许可证

MIT License — 见 [LICENSE](LICENSE)

## 捐赠支持

* 想捐钱我喝杯热水（¥0.01 起捐）

![donate](https://github.com/afeimod/NesStation/blob/main/IMG_20260906_153806.jpg?raw=true)

![donate](https://github.com/afeimod/NesStation/blob/main/IMG_20260906_153816.jpg?raw=true)


## 致谢

- Jetpack Compose 团队提供的现代声明式 UI 框架
- Android Room 团队的数据持久化方案
- 所有 Windows 版本的设计师，他们的工作启发了本项目
- ruffle针对flash的优化

---

**注意**：本项目是独立的 Windows 风格桌面模拟器，不包含任何微软公司的代码、资源或商标。所有视觉元素均为程序化生成的原创作品，仅用于学习和致敬。
