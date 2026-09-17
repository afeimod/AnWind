# AnWind v2.24.0 升级说明（桌面窗口引擎）

## 本次改动内容

针对四轮实测反馈（"没窗口" → "厂商小窗" → "只全屏显示，没任何窗口"），
本次不是继续调参数，而是**换引擎**：四级启动级联 + 诊断中心。

### 修改/新增文件（相对 v2.23.3）

| 文件 | 状态 | 说明 |
|------|------|------|
| `app/src/main/java/com/anwind/core/desktop/FreeformCompat.kt` | 重写 | 引擎中枢：四键设置写入 / 隐藏 API 豁免（VMRuntime + hidden_api_policy 双通道）/ MIUI·HyperOS 专有加速反射 / 既存全屏任务检测 / 分 ROM 指引 / 诊断快照 |
| `app/src/main/java/com/anwind/core/desktop/AndroidApps.kt` | 重写 | 启动级联：MIUI 加速 → AOSP freeform（修正键名 + CLEAR_TASK 卡全屏修复）；删除 Android 12+ 已不存在的 setTaskWindowingMode 死代码；启动结果观测 |
| `app/src/main/java/com/anwind/apps/settings/WindowEnginePage.kt` | 新增 | 设置 → 桌面窗口 诊断中心（引擎状态 / 修复开关 / 一键复制诊断报告） |
| `app/src/main/java/com/anwind/apps/settings/SettingsApp.kt` | 修改 | 注册"桌面窗口"设置页（windowengine 路由 + 导航项） |
| `app/src/main/java/com/anwind/core/desktop/DesktopEnvironment.kt` | 修改 | 决策弹窗分 ROM 指引 + "打开桌面窗口设置"入口 |
| `app/build.gradle.kts` | 修改 | versionCode 51 / versionName 2.24.0 |
| `docs/DESKTOP_WINDOW.md` | 新增 | 桌面窗口引擎完整文档（问题复盘 / 方案 / 使用指引 / 已知边界） |
| `README.md` | 修改 | 特性列表补充"桌面窗口引擎" |

### 应用方式

本 zip 为「覆盖 app/ + README + docs」结构，与此前版本一致：
解压后把 `app/`、`README.md`、`docs/` 覆盖到仓库根目录即可。

## 装上新版后的操作顺序（重要）

1. 安装新构建的 APK，**授予悬浮窗权限**（如之前没给）；
2. 如之前执行过 ADB 授权，无需重复；如没有，电脑执行一次：
   `adb shell pm grant com.anwind android.permission.WRITE_SECURE_SETTINGS`
3. **彻底杀掉 AnWind 进程重新打开**（hidden_api_policy 写入后需要进程重启）；
4. 从桌面/开始菜单启动一个手机应用；
5. 打开 设置 → 桌面窗口：
   - 看引擎状态各项是否全 ✓；
   - 看"最近一次启动"的策略与"启动后窗口模式"；
   - 有问题点**复制诊断报告**发回即可精确定位（报告包含设备、ROM、
     开关读回、MIUI 检测、启动策略与结果观测）。

### 若应用仍被打开成小窗/全屏

MIUI/HyperOS 上：拖动小窗**底部横条或角部把手**可放大为大窗；
或者在 设置 → 桌面窗口 打开「总是以全新任务启动」后再试一次；
仍不行 → 复制诊断报告反馈（里面已有定位线索）。
