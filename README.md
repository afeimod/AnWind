# AnWind

> 一个 Android 平台的 Windows 风格桌面模拟器，支持 Win95 / XP / 7 / 10 / 11 五代主题切换，内置浏览器、文件管理器、终端等 9 个应用，支持自定义快捷方式。

## 项目简介

AnWind 是一个用 **Kotlin + Jetpack Compose** 编写的 Android 桌面启动器（Launcher），将手机桌面改造为 Windows 操作系统的视觉体验。整个项目作为独立 GitHub 仓库交付，可克隆、编译、二次开发。

### 核心特性

- **5 套 Windows 主题切换** — Windows 95 / XP / 7 / 10 / 11，每套主题都有独立的任务栏样式、窗口边框、配色方案和壁纸，切换后整个 UI 焕然一新
- **混合窗口模型** — 主要应用（浏览器/文件管理器）全屏运行，辅助应用（记事本/计算器/设置）以可拖拽、可缩放、可最小化/最大化的浮动窗口运行
- **完整浏览器** — 基于 WebView 重新实现，支持多标签页、前进/后退/刷新、地址栏、书签、历史记录、首页快捷导航，**支持通过 SAF 读取本地 HTML 文件并渲染**
- **快捷方式系统** — 长按桌面空白处弹出右键菜单 → 新建快捷方式，支持网页 URL / 本地 HTML 文件 / 应用 三种类型，可自定义名称和 emoji 图标
- **9 个内置应用** — 浏览器、文件资源管理器、设置、记事本、计算器、系统信息、图片查看器、时钟、终端
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
| 目标 SDK | 34 (Android 14) |
| 构建 | Gradle 8.7 + AGP 8.5.2 |

## 项目结构

```
AnWind/
├── app/
│   ├── build.gradle.kts                    # 应用构建配置
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml             # 注册为 Launcher
│       ├── java/com/anwind/
│       │   ├── AnWindApp.kt                # Application 入口，初始化 DB/主题/应用注册
│       │   ├── MainActivity.kt             # 唯一 Activity，承载桌面
│       │   ├── core/
│       │   │   ├── theme/
│       │   │   │   ├── WinTheme.kt         # 主题数据类 + 5 套主题定义
│       │   │   │   ├── ThemeManager.kt     # DataStore 持久化主题选择
│       │   │   │   └── WinThemeScope.kt    # CompositionLocal 主题注入
│       │   │   ├── window/
│       │   │   │   ├── WindowManager.kt    # 窗口管理器单例
│       │   │   │   ├── WindowChrome.kt     # 单个窗口的标题栏+内容
│       │   │   │   ├── WindowHost.kt       # 窗口容器，按 zIndex 渲染
│       │   │   │   └── AppRegistry.kt      # 应用注册表
│       │   │   └── desktop/
│       │   │       ├── DesktopEnvironment.kt   # 桌面顶层 Composable
│       │   │       ├── WallpaperLayer.kt       # 壁纸层
│       │   │       ├── DesktopIconGrid.kt      # 桌面图标网格
│       │   │       ├── Taskbar.kt              # 任务栏
│       │   │       ├── StartMenu.kt            # 开始菜单
│       │   │       ├── DesktopContextMenu.kt   # 右键菜单
│       │   │       └── ShortcutCreateDialog.kt # 快捷方式创建对话框
│       │   ├── data/
│       │   │   ├── db/
│       │   │   │   ├── AppDatabase.kt       # Room 数据库
│       │   │   │   ├── entity/Entities.kt   # 实体类
│       │   │   │   └── dao/Daos.kt           # DAO 接口
│       │   │   ├── model/
│       │   │   │   └── DesktopItem.kt       # 桌面项数据模型
│       │   │   └── prefs/
│       │   │       └── SettingsStore.kt     # 偏好设置
│       │   ├── apps/
│       │   │   ├── AppBootstrap.kt          # 应用注册入口
│       │   │   ├── browser/                  # 浏览器（多标签+本地HTML+书签+历史）
│       │   │   │   ├── BrowserApp.kt
│       │   │   │   └── TabManager.kt
│       │   │   ├── filemanager/             # 文件管理器
│       │   │   │   ├── FileExplorerApp.kt
│       │   │   │   └── VirtualFileSystem.kt
│       │   │   ├── settings/SettingsApp.kt  # 设置中心（含主题切换）
│       │   │   ├── notepad/NotepadApp.kt
│       │   │   ├── calculator/CalculatorApp.kt
│       │   │   ├── sysinfo/SysInfoApp.kt
│       │   │   ├── imageviewer/ImageViewerApp.kt
│       │   │   ├── clock/ClockApp.kt
│       │   │   └── terminal/TerminalApp.kt  # 终端（支持 theme 命令切换主题）
│       │   └── util/EmojiPainter.kt
│       ├── res/
│       │   ├── values/                      # strings, colors, themes
│       │   ├── xml/                         # backup rules
│       │   └── mipmap-*/                    # 启动图标（程序生成）
│       └── assets/
│           ├── wallpapers/                  # 5 套主题壁纸（程序生成）
│           ├── sounds/                      # 启动音效目录（需用户自备）
│           └── filesystem/                  # 虚拟文件系统
├── .github/workflows/
│   └── build.yml                            # GitHub Actions 手动触发构建
├── build.gradle.kts                         # 根构建配置
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── gradlew / gradlew.bat                    # Gradle wrapper 脚本
├── .gitignore
├── LICENSE
└── README.md
```

## 快速开始

### 环境要求

- **Android Studio** Hedgehog (2023.1.1) 或更高版本
- **JDK 17**
- **Android SDK** API 34（编译）/ API 24+（运行）
- **Gradle 8.7**（项目自带 wrapper 配置）

### 构建步骤

1. **克隆仓库**

   ```bash
   git clone https://github.com/yourname/AnWind.git
   cd AnWind
   ```

2. **生成 Gradle Wrapper**（仓库不含 `gradle-wrapper.jar` 二进制）

   如果系统已安装 Gradle 8.7+：

   ```bash
   gradle wrapper --gradle-version 8.7
   ```

   或直接用 Android Studio 打开项目，IDE 会自动生成。

3. **使用 Android Studio 构建**

   - 打开 Android Studio → `File` → `Open` → 选择 `AnWind` 目录
   - 等待 Gradle 同步完成（首次会下载依赖）
   - 连接 Android 设备（开启 USB 调试）或启动模拟器
   - 点击 `Run` 按钮（▶）

4. **使用命令行构建**

   ```bash
   # Debug 版本
   ./gradlew assembleDebug

   # 生成的 APK 位于
   # app/build/outputs/apk/debug/app-debug.apk

   # Release 版本（需配置签名）
   ./gradlew assembleRelease
   ```

5. **安装到设备**

   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### 通过 GitHub Actions 构建（手动触发）

项目自带 GitHub Actions workflow，**仅手动触发**，不会在 push/PR 时自动运行。

**操作步骤**：

1. 将仓库推送到 GitHub
2. 进入仓库 → `Actions` 标签页
3. 左侧选择 `Build AnWind APK` workflow
4. 点击右侧 `Run workflow` 按钮
5. 在弹出的下拉框中选择参数：
   - **build_type**：`debug` / `release` / `both`
   - **java_version**：`17`（推荐）或 `21`
   - **gradle_version**：`8.7`（默认，可改）
   - **upload_apk**：是否上传 APK 作为 artifact
6. 点击绿色 `Run workflow` 按钮

**构建产物**：

- 构建完成后，在该 run 详情页底部的 `Artifacts` 区域下载 APK
- Debug APK 文件名：`AnWind-debug-<run_number>`
- Release APK 文件名：`AnWind-release-<run_number>`
- 构建失败时，自动上传 `build-reports` 用于排查

**关键设计**：

- workflow 使用 `gradle/actions/setup-gradle@v3` 直接安装指定 Gradle 版本，**不依赖 `gradle-wrapper.jar`**（因为该二进制文件无法用文本工具生成，首次推送时常缺失）
- 步骤 4 会检测 wrapper jar 是否存在，缺失时自动用 `gradle wrapper` 命令生成
- 同一分支的并发构建会自动排队（`concurrency.cancel-in-progress: false`），避免重复消耗 Actions 配额

### 设为默认 Launcher（可选）

应用在 Manifest 中已注册 `HOME` category。安装后：

- 按下 Home 键 → 系统弹出 Launcher 选择器 → 选择 `AnWind`
- 或在 `设置 → 应用 → 默认应用 → 桌面` 中选择 `AnWind`

## 使用指南

### 切换 Windows 主题

**方法一：设置中心**

打开开始菜单 → 设置 → 主题 → 选择任意主题（Win95/XP/7/10/11）→ 整个界面立即变换

**方法二：终端命令**

打开开始菜单 → 终端 → 输入：

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

### 终端命令

| 命令 | 作用 |
|------|------|
| `help` | 显示帮助 |
| `ver` | 系统版本 |
| `date` / `time` | 当前日期/时间 |
| `dir` / `ls` | 列出目录 |
| `cd <path>` | 切换目录 |
| `cls` | 清屏 |
| `theme <variant>` | 切换主题（win95/xp/win7/win10/win11） |
| `start <app>` | 启动应用（browser/files/notepad/calc/settings/music） |
| `exit` | 关闭终端 |

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

### 数据库迁移

修改 `AppDatabase` 的 `version`，添加 `Migration` 对象。当前使用 `fallbackToDestructiveMigration`（开发期方便），生产环境请改为正式迁移。

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

## 路线图

- [ ] 真正的窗口边缘吸附
- [ ] 桌面图标拖拽排序
- [ ] 文件管理器支持创建/删除文件
- [ ] 图片查看器支持打开本地图片
- [ ] 通知中心（系统托盘点击展开）
- [ ] 应用商店（安装第三方 web 应用为 PWA）
- [ ] 多窗口之间拖拽数据

## 许可证

MIT License — 见 [LICENSE](LICENSE)

## 致谢

- Jetpack Compose 团队提供的现代声明式 UI 框架
- Android Room 团队的数据持久化方案
- 所有 Windows 版本的设计师，他们的工作启发了本项目
- ruffle针对flash的优化

---

**注意**：本项目是独立的 Windows 风格桌面模拟器，不包含任何微软公司的代码、资源或商标。所有视觉元素均为程序化生成的原创作品，仅用于学习和致敬。
