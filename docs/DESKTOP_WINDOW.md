# 桌面窗口引擎（v2.24.0）

> 让从 AnWind 桌面启动的**手机应用**以**电脑窗口**形态运行：桌面级大尺寸、
> 悬浮在桌面上、任务栏保持可见 —— 而不是被系统改写成"厂商小窗"或直接全屏盖住桌面。

## 一、为什么之前不生效（v2.23.x 四轮实测复盘）

用户在 Android 12+ 国产 ROM 上依次反馈：*"没窗口"* → *"手机自带的小窗口"* →
*"只全屏显示了，没任何窗口"*。经 AOSP 源码逐条核对，v2.23.x 存在四个结构性缺陷：

| # | 缺陷 | 依据（frameworks/base） |
|---|------|------------------------|
| 1 | `setLaunchWindowingMode` 的 Bundle 兜底键名写错：写成 `android:activity.windowingMode`（冒号），真实值是 `android.activity.windowingMode`（**点号**） | `ActivityOptions.java` L244 |
| 2 | "启动后强制切窗" 反射的 `ActivityTaskManager.setTaskWindowingMode` 在 **Android 12+ 已从系统服务移除**，注定空跑 | Android 13 `WMS.java`/`ATMS.java` 全文检索无此方法 |
| 3 | `FLAG_ACTIVITY_NEW_DOCUMENT` 不带 `MULTIPLE_TASK` 时，同应用第二次启动只是 **bring-to-front 既存任务**，窗口模式不会重新应用 —— 应用一旦曾以全屏建过任务（比如从系统桌面开过一次），之后永远"只全屏" | `ActivityStarter` recycled-task 路径 |
| 4 | 国产 ROM 在 framework 注入自家多窗口策略（`android.util.MiuiMultiWindowUtils`、`MiuiFreeFormStackDisplayStrategy` 等），裸 AOSP freeform 参数被改写成小窗/全屏 | 开源 Xposed 模块 MaxFreeForm 的 hook 清单 |

另外确认了三条"死路"，避免再走弯路：

- `com.android.wm.shell.TaskView` 是 Shell 内部类，三方应用无法访问（Android 12+）；
- `android.app.ActivityView` 在 Android 12 已从 framework 移除；
- 虚拟显示器路线被双重锁死：非信任 display 上跨应用启动要求目标应用声明
  `allowEmbedded` **且**调用方持有签名级 `ACTIVITY_EMBEDDING` 权限
  （`ActivityTaskSupervisor#isCallerAllowedToLaunchOnDisplay`）。

**结论：非 Root、非系统应用唯一可行的通路就是系统自由窗口（freeform），
v2.24.0 的全部工作就是把这条路走对、走稳。**

## 二、v2.24.0 方案：四级启动级联

```
点击桌面/开始菜单里的手机应用
 │
 ├─ ① 能力保障 ensureAvailable()
 │     已授予 WRITE_SECURE_SETTINGS → 无条件写入四键（每进程一次，幂等）：
 │       · enable_freeform_support = 1        （启用自由窗口）
 │       · force_resizable_activities = 1     （强制可调整大小）
 │       · force_resizable / force_resizable = 1（旧社区写法，双保险）
 │       · enable_non_resizable_multi_window = 1（Android 13+ 第二个多窗口开关）
 │       · hidden_api_policy = 1              （停用隐藏 API 检测，见 ③）
 │     不可用且未抑制 → 决策弹窗（分 ROM 指引）
 │
 ├─ ② 卡全屏修复 shouldClearStuckTask()
 │     默认 Launcher 身份下读到目标应用存在全屏既存任务
 │     → 给 Intent 补 FLAG_ACTIVITY_CLEAR_TASK 重建任务，窗口模式随之生效
 │     （对应缺陷 #3；非 Launcher 用户可在设置页开"总是新任务"）
 │
 ├─ ③ MIUI / HyperOS 加速（检测到小米系 framework 时）
 │     先豁免隐藏 API：VMRuntime.setHiddenApiExemptions("L")（免权限）
 │     → 反射 com.miui.launcher.utils.ActivityUtilsCompat
 │       .makeFreeformActivityOptions(Context, pkg)（MIUI 自家小窗按钮的通路）
 │     → 失败再枚举 android.util.MiuiMultiWindowUtils 的工厂方法
 │     → 拿到带 MIUI 私有 extras 的 options 后，统一覆盖为
 │       桌面级大窗口边界 + WINDOWING_MODE_FREEFORM
 │     （骑在厂商自己的自由窗口通路上，而不是与其对抗；对应缺陷 #4）
 │
 └─ ④ AOSP freeform（MIUI 加速不可用 / 非小米设备）
       setLaunchWindowingMode(5)（反射，unsupported 浅灰名单）
       + setLaunchStack(5)（API 24/25 时代等价入口，二重保险）
       + setLaunchBounds(桌面级边界：宽 92% × 工作区全高，层叠错开)
       + Bundle 直写 android.activity.windowingMode（修正后的键名；对应缺陷 #1）
       启动后 1.2s 观测任务真实窗口模式（默认 Launcher 时）：
       仍全屏 → Toast 引导到 设置→桌面窗口；结果写入诊断报告
```

## 三、设置 → 桌面窗口（诊断中心）

新增设置页，把引擎的全部设备侧事实摊开，**不再盲猜**：

- **引擎状态**：悬浮窗权限 / ADB 写设置权限 / 四个系统开关读回 / 是否默认
  Launcher / MIUI 专有通道检测 / 隐藏 API 豁免状态，逐项 ✓/✗；
- **最近一次启动**：目标应用、采用策略、桌面级边界、是否重建任务、
  启动后任务的真实窗口模式；
- **修复开关**：卡全屏自动修复（推荐开启）、总是以全新任务启动（最后手段）；
- **分 ROM 指引**：按制造商列出开发者选项里需要打开的开关名；
- **一键复制诊断报告**：把全部事实复制到剪贴板，反馈给开发者即可精确定位。

## 四、用户操作指引

### 快速路径（推荐）

电脑上执行一次（授权后 AnWind 自动开启并一直维持全部开关）：

```
adb shell pm grant com.anwind android.permission.WRITE_SECURE_SETTINGS
```

然后**完全杀掉 AnWind 重新打开**（`hidden_api_policy` 需要进程重启生效），
从桌面/开始菜单启动任意手机应用。

### 无电脑路径

系统设置 → 开发者选项，打开（各 ROM 名称不同，设置页会按品牌提示）：

- **强制将活动设为可调整大小**（各家通用名）
- **启用自由窗口 / 启用可自由调整的窗口**（MIUI/HyperOS/ColorOS 等有此项）
- **允许不可调整大小的多窗口**（Android 13+）

回到 AnWind → 设置 → 桌面窗口 → **重新检测**。

### 如果还是小窗 / 全屏

1. 打开 设置 → 桌面窗口，看"引擎状态"哪项是 ✗，按提示补开；
2. 全部 ✓ 仍不生效 → 点**复制诊断报告**，把文本反馈给开发者
   （报告包含设备、Android 版本、开关读回、MIUI 检测、最近一次启动的
   策略与启动后窗口模式，可直接定位卡点）；
3. MIUI/HyperOS 上应用被打开成系统小窗时，可拖动小窗**底部横条或角部
   把手**放大为大窗（系统手势，与引擎无关）。

## 五、代码索引

| 文件 | 职责 |
|------|------|
| `core/desktop/FreeformCompat.kt` | 引擎中枢：四键写入 / HiddenApi 豁免 / MIUI 探测与加速 / 既存任务检测 / 分 ROM 指引 / 诊断快照 |
| `core/desktop/AndroidApps.kt` | 启动级联：MIUI 加速 → AOSP freeform（+CLEAR_TASK 修复）→ 决策弹窗；启动结果观测 |
| `apps/settings/WindowEnginePage.kt` | 设置→桌面窗口 诊断中心 |
| `core/desktop/DesktopEnvironment.kt` | 决策/授权弹窗（分 ROM 指引 + 诊断入口） |

## 六、已知边界（如实告知）

- 桌面窗口的**移动/缩放**由系统提供的窗口装饰承担（AOSP 自带标题栏；
  MIUI/HyperOS 为小窗顶/底栏）。三方应用无权在启动后程序化移动/缩放
  其他应用的窗口（`resizeTask`/`setTaskWindowingMode` 需签名级
  `MANAGE_ACTIVITY_TASKS`）；
- 竖屏锁定的手机应用在横屏窗口中会以 letterbox 呈现（与三星 DeX 行为一致）；
- 个别 ROM（尤其华为系旧版）完全不支持第三方自由窗口，此时引擎只能
  引导用户使用全屏或分屏 —— 诊断报告会如实反映。
