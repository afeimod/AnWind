package com.anwind.core.desktop

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.anwind.AnWindApp
import com.anwind.core.theme.LocalWinTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.17：安卓手机应用读取（开始菜单"手机应用" / "系统应用"分页）。
 *
 * - 通过 PackageManager 查询所有带 MAIN/LAUNCHER 入口的应用；
 * - 系统应用判定：applicationInfo.Flags 含 FLAG_SYSTEM；
 * - 自身（AnWind）排除，避免在开始菜单里套娃；
 * - Manifest 已声明 <queries>（MAIN/LAUNCHER），Android 11+ 无需
 *   QUERY_ALL_PACKAGES 宽限权限；
 * - 结果进程内缓存一次；图标随列表在 IO 线程预解码为 Bitmap，
 *   网格滚动零卡顿。
 *
 * v2.23.0：从桌面启动安卓应用 → **强制在桌面窗口（freeform 自由窗口）打开**，
 * 不再全屏覆盖桌面（Windows 风格体验：启动的应用像一个普通窗口悬浮在
 * 桌面上，任务栏仍然可见）。实现见 [launch] / [freeformOptions]。
 *
 * v2.23.1：**强制升级**——多路径强制 freeform：
 *   1) Intent 加 `FLAG_ACTIVITY_NEW_DOCUMENT`（公开 API，新建独立
 *      document 任务，避免复用其他入口创建的 fullscreen 旧任务；
 *      后续同意图启动会复用这个 document 任务，不会重复建例）；
 *   2) `ActivityOptions` 同时尝试 `setLaunchWindowingMode(5)` 与
 *      `setLaunchStack(5)`（隐藏 API 反射）；
 *   3) `setLaunchBounds` 给一个明显小于全屏的初始窗口边界，
 *      很多 ROM 即便忽略 windowing mode 也会因 bounds 落到 freeform；
 *   4) 启动后 250ms 调用 `ActivityManager.setTaskWindowingMode(
 *      taskId, 5, true)`（反射）做兜底强制，针对一些 ROM 把
 *      目标任务"先按全屏建好再切"的行为；
 *   5) 失败也不抛异常，记录 warn，由调用方决定是否提示用户。
 *
 *   桌面快捷方式新增 [SHORTCUT_ANDROID_APP]（type=4，target = "pkg/activity"）
 *   → 走同一套强制窗口化路径，保证"从桌面启动手机应用一定进窗口"。
 *
 * v2.23.2：**根修——补齐"设备能力"前提，见 [FreeformCompat]**。
 *   v2.23.0/1 的全部启动参数技巧（windowing mode / stack / bounds /
 *   setTaskWindowingMode）都有一个共同前提：设备本身支持 freeform。
 *   多数 ROM 出厂未开启该能力，system_server 会直接丢弃
 *   WINDOWING_MODE_FREEFORM 请求 → 应用照常全屏盖住桌面（即
 *   "启动手机应用会调用出桌面"的根因）。
 *   本版启动前先经 [FreeformCompat.ensureAvailable] 保能力：
 *   - 已授予 WRITE_SECURE_SETTINGS（ADB/Shizuku 一次性授权）→ 自动
 *     写开 enable_freeform_support 开关，WMS 热加载，立即可用；
 *   - Root 设备 → 进程启动时后台已静默写开（[FreeformCompat.warmup]）；
 *   - 仍不可用 → 不再静默退化全屏，而是弹窗告知开启方式，由用户
 *     选择"仍以全屏启动"或"取消"（[FreeformCompat.pendingLaunch] +
 *     DesktopEnvironment 的 FreeformDecisionDialog）。
 *   同时强化 [freeformOptions]：反射被屏蔽时直接往 Bundle 写入
 *   KEY_LAUNCH_WINDOWING_MODE（与 setLaunchWindowingMode 落盘的键
 *   完全一致）；[scheduleFreeformEnforce] 由单次 250ms 改为
 *   300/800/1600ms 三轮重试。
 *
 * v2.23.3：**桌面窗口（电脑窗口）形态**，三个关键修改：
 *   1. [desktopWindowBounds]：启动边界从"72%×76% 通用居中矩形"升级为
 *      桌面级大窗口 —— 基于真实屏幕尺寸（含系统栏），宽约 92%、
 *      高度填满工作区（顶部避开状态栏、底部预留 AnWind 任务栏），
 *      并带 Windows 风格层叠偏移（连续开窗依次错开）。窄高手机比例
 *      的旧边界即使被采纳，看起来也像"手机小窗"，这是用户反馈
 *      "不是电脑窗口那种"的直接原因之一；
 *   2. 配合 [FreeformCompat] v2.23.3 无条件写入 force_resizable_activities
 *      （global+secure 双表），未适配多窗口的手机应用不再被厂商系统
 *      钉死在自带小窗（矩阵缩放、固定手机比例）；
 *   3. 新增授权建议：freeform 可用但 WRITE_SECURE_SETTINGS 未授予时，
 *      首次启动手机应用后提示一次 ADB 授权（会话级，不阻塞启动）。
 */
object AndroidApps {

    private const val TAG = "AnWind.AndroidApps"

    // v2.23.2：WINDOWING_MODE_FREEFORM(=5) 与隐藏 Bundle 键统一收敛到
    // FreeformCompat，供本对象与能力检测/弹窗共用（原局部常量已删除）

    /** v2.23.3：桌面窗口层叠序号（Windows 风格：连续开窗依次右下错开） */
    private val cascadeIndex = AtomicInteger(0)

    /** v2.23.3：授权建议弹窗是否已展示过（仅会话级，重启 AnWind 后可再见） */
    @Volatile
    private var setupHintShown = false

    /** 单个安卓应用条目 */
    data class AppInfo(
        val label: String,
        val pkg: String,
        /** 启动 Activity 全类名（用显式 Component 启动，避免 ROM 对 getLaunchIntentForPackage 的差异） */
        val activity: String,
        val isSystem: Boolean,
        val icon: Bitmap?
    )

    @Volatile
    private var cached: List<AppInfo>? = null

    /** 查询所有可启动应用（同步，应在 IO 线程调用；结果缓存） */
    fun load(context: Context): List<AppInfo> {
        cached?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        }.getOrNull() ?: emptyList()

        val self = context.packageName
        val list = infos.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val pkg = ai.packageName ?: return@mapNotNull null
            val activity = ai.name ?: return@mapNotNull null
            if (pkg == self) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg
            val isSystem = (ai.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
            AppInfo(
                label = label,
                pkg = pkg,
                activity = activity,
                isSystem = isSystem,
                icon = runCatching { decodeIcon(pm.getApplicationIcon(pkg)) }.getOrNull()
            )
        }
            .distinctBy { it.pkg to it.activity }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        cached = list
        return list
    }

    /** 清空缓存（安装/卸载应用后可调用；当前开始菜单每次打开重新拉取，用不到） */
    fun invalidate() { cached = null }

    /** Drawable → 96x96 Bitmap（含 AdaptiveIcon 兼容，直接画到画布即可） */
    private fun decodeIcon(drawable: Drawable): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * 桌面快捷方式 target 编码：`pkg/activity`。
     * 解析失败时把整个字符串当 pkg，activity 留空（仅用于查询提示）。
     */
    fun parseShortcutTarget(target: String): Pair<String, String>? {
        val t = target.trim()
        if (t.isEmpty()) return null
        val idx = t.indexOf('/')
        return if (idx > 0 && idx < t.length - 1) {
            t.substring(0, idx) to t.substring(idx + 1)
        } else {
            t to ""
        }
    }

    /** 把 AppInfo 编码为可存入数据库的 target 字符串。 */
    fun toShortcutTarget(app: AppInfo): String = "${app.pkg}/${app.activity}"

    /**
     * 用 pkg/activity（来自桌面快捷方式 target）启动安卓应用，强制 freeform 窗口。
     * 返回是否已处理（已启动 / 已转入决策弹窗）；Intent 构造失败返回 false，
     * 由调用方决定是否给用户提示。
     */
    fun launchByComponent(context: Context, pkg: String, activity: String): Boolean =
        launchAndroidApp(context, pkg, activity, pkg)

    /**
     * 启动一个手机应用（开始菜单"手机应用/系统应用"分页入口）。
     * 返回是否已处理（已窗口化启动 / 已转入决策弹窗）。
     */
    fun launch(context: Context, app: AppInfo): Boolean =
        launchAndroidApp(context, app.pkg, app.activity, app.label)

    /**
     * v2.23.2：决策弹窗里"仍以全屏启动"的入口 —— 跳过 freeform 与询问，
     * 直接普通启动（用户已知情且明确选择全屏）。
     */
    fun launchFullscreen(context: Context, pkg: String, activity: String): Boolean {
        val intent = buildLaunchIntent(context, pkg, activity) ?: return false
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { false }
    }

    /**
     * 构造启动 Intent：优先显式 Component；activity 为空时退回
     * getLaunchIntentForPackage（两分支统一带 NEW_TASK / RESET_TASK_IF_NEEDED /
     * NEW_DOCUMENT，语义见 [launchAndroidApp]）。
     */
    private fun buildLaunchIntent(context: Context, pkg: String, activity: String): Intent? {
        val base = if (activity.isBlank()) {
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        } else {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg, activity)
        } ?: return null
        return base.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                // v2.23.1：新建独立 document 任务（公开 API）—— 避开复用
                // 其他入口创建的 fullscreen 旧任务，让本次任务直接落入
                // freeform 窗口模式；同意图后续启动复用该任务。
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
        )
    }

    /**
     * 手机应用启动主路径（开始菜单 / 桌面快捷方式共用入口）。
     *
     * v2.23.2 流程：
     * 1. [FreeformCompat.ensureAvailable] 保"设备支持自由窗口"前提
     *    （已授予 WRITE_SECURE_SETTINGS 时同步写开开关，毫秒级）；
     * 2. 可用 → 带 [freeformOptions] 启动 + 启动后三轮 [scheduleFreeformEnforce]
     *    兜底强制切窗；
     * 3. 不可用且未抑制询问 → [FreeformCompat.requestDecision] 转弹窗，
     *    由用户选择"仍以全屏启动 / 取消"（不再静默全屏）；
     * 4. 不可用且用户已选"不再提示" → 直接全屏启动。
     *
     * v2.23.3 追加：步骤 1 的写入改为无条件（见 [FreeformCompat]），
     * 步骤 2 的启动边界升级为桌面级大窗口（[desktopWindowBounds]），
     * 启动成功后按需触发一次授权建议弹窗（见下方 ③）。
     */
    private fun launchAndroidApp(context: Context, pkg: String, activity: String, label: String): Boolean {
        val intent = buildLaunchIntent(context, pkg, activity) ?: return false

        // ① 保能力：确保设备支持自由窗口（30s 缓存，快路径毫秒级）
        val freeformReady = FreeformCompat.ensureAvailable(context)

        if (!freeformReady) {
            // 用户已选"不再提示" → 直接全屏兜底
            if (FreeformCompat.suppressDecision) {
                return runCatching {
                    context.startActivity(intent)
                    true
                }.getOrElse { false }
            }
            // 转入决策弹窗（返回 true 让开始菜单先收起，弹窗在桌面层显示）
            FreeformCompat.requestDecision(pkg, activity, label)
            return true
        }

        // ② 窗口化启动 + 多轮兜底强制
        val launched = runCatching {
            context.startActivity(intent, freeformOptions(context))
            scheduleFreeformEnforce(context, pkg)
            true
        }.getOrElse { false }

        // ③ v2.23.3：freeform 已可用但设置无法自动写入（未授予 ADB 权限）
        //    → 首次启动后建议一次授权（不阻塞本次启动；会话内不重复；
        //      状态驱动，授权后永不弹 —— 修复旧版"只提示一次/再也不弹"）
        if (launched &&
            !FreeformCompat.hasSecureSettings(context) &&
            !FreeformCompat.suppressSetupHint &&
            !setupHintShown
        ) {
            setupHintShown = true
            FreeformCompat.requestSetupHint(pkg, activity, label)
        }
        return launched
    }

    /**
     * v2.23.1 → v2.23.2：启动后兜底强制 freeform。
     *
     * 一些 ROM（尤其是国产定制 ROM）会忽略 [ActivityOptions.setLaunchWindowingMode]，
     * 把任务先按 fullscreen 建起来；这里在 300/800/1600ms 三轮扫描最近任务，
     * 命中目标包名时反射调用 `ActivityTaskManager.setTaskWindowingMode(
     * taskId, 5, true)` 把它从 fullscreen 改成 freeform，再配一个最小尺寸
     * 的边界，让应用以"小窗口"形态呈现。多轮重试针对"任务先全屏建好、
     * 再异步初始化完成"的时序：单次 250ms 扫描时机太早会扑空。
     *
     * 整个过程不抛异常（被屏蔽就 warn 退出）；返回值仅用于调试观察。
     */
    private fun scheduleFreeformEnforce(context: Context, targetPkg: String) {
        val main = Handler(Looper.getMainLooper())
        longArrayOf(300L, 800L, 1600L).forEach { at ->
            main.postDelayed({
                runCatching {
                    forceFreeformOnRunningTask(context, targetPkg)
                }.onFailure { Log.w(TAG, "force freeform enforce failed: ${it.message}") }
            }, at)
        }
    }

    /** 反射尝试把最近一个属于 [targetPkg] 的任务切到 freeform 模式。 */
    private fun forceFreeformOnRunningTask(context: Context, targetPkg: String) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        // 1) 找最近的任务 id —— getRecentTasks 在 Android 5+ 只能看到自己应用的任务，
        //    但作为 Launcher (HOME category) 我们可以拿到全部任务（系统对默认 Launcher 放行）。
        val taskId: Int = runCatching {
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(8) ?: emptyList()
            tasks.firstOrNull { it.topActivity?.packageName == targetPkg }?.id
                ?: tasks.firstOrNull { it.baseActivity?.packageName == targetPkg }?.id
        }.getOrNull() ?: runCatching {
            // getRunningTasks 在 Android 5+ 仅返回调用方自己任务，作为 Launcher 仍可拿到；
            // 取不到时再尝试 getRecentTasks（deprecated 但仍可用，Launcher 同样有权限）
            @Suppress("DEPRECATION")
            val recent = am.getRecentTasks(8, ActivityManager.RECENT_WITH_EXCLUDED)
            recent.firstOrNull {
                it.baseIntent?.component?.packageName == targetPkg
            }?.id
        }.getOrNull() ?: -1

        if (taskId < 0) {
            Log.w(TAG, "forceFreeform: 目标任务未找到 (pkg=$targetPkg)")
            return
        }

        // 2) ActivityTaskManager.setTaskWindowingMode(taskId, windowingMode, toTop)
        //    反射调用（hidden API）；不同 Android 版本类名略有差异：
        //    - API 29+ ：android.app.ActivityTaskManager
        //    - API 24~28：android.app.ActivityManager (同方法签名)
        val atmClz = runCatching { Class.forName("android.app.ActivityTaskManager") }
            .getOrNull() ?: ActivityManager::class.java
        val method = runCatching {
            atmClz.getMethod("setTaskWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }.getOrNull()
        if (method == null) {
            Log.w(TAG, "forceFreeform: setTaskWindowingMode 反射失败，ROM 可能屏蔽")
            return
        }
        val atmInstance = runCatching {
            atmClz.getMethod("getInstance").invoke(null) as? Any
        }.getOrNull() ?: am
        runCatching {
            method.invoke(atmInstance, taskId, FreeformCompat.WINDOWING_MODE_FREEFORM, true)
            Log.i(TAG, "forceFreeform: taskId=$taskId → FREEFORM 已强制")
        }.onFailure { Log.w(TAG, "forceFreeform invoke failed: ${it.message}") }
    }

    /**
     * v2.23.0：构建"桌面窗口"启动参数（ActivityOptions）。
     *
     * v2.23.1 强化：两步组合 + 多重 fallback：
     * 1. 强制窗口模式 —— ActivityOptions.setLaunchWindowingMode(5) 为
     *    hidden API，反射调用；API 24/25 时代的等价入口是
     *    setLaunchStack(FREEFORM_WORKSPACE_STACK)。两处都被 ROM 屏蔽时
     *    仅退化为"设窗口边界"，再不行则全屏兜底（调用方拿到 null）。
     * 2. 初始窗口边界 —— setLaunchBounds(Rect) 为公开 API（API 24+）。
     *
     * v2.23.3：边界升级为**桌面级大窗口**（见 [desktopWindowBounds]）：
     * 真实屏幕尺寸的 92% 宽 × 填满工作区高、预留任务栏、避开状态栏、
     * Windows 风格层叠错开。此前的 72%×76% 通用居中矩形在竖屏设备上
     * 是窄高手机比例，即使被系统采纳也像"手机小窗"；配合 FreeformCompat
     * 无条件写入的 force_resizable_activities，窗口不再被厂商系统压缩回
     * 固定手机比例。
     *
     * 返回 null 表示构建失败（极少见），调用方回退普通启动。
     */
    private fun freeformOptions(context: Context): Bundle? = runCatching {
        val options = ActivityOptions.makeBasic()

        // 1) 窗口模式：FREEFORM（隐藏 API 反射；setLaunchWindowingMode
        //    失败时改用 API 24/25 时代的等价入口 setLaunchStack 再试）
        try {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, FreeformCompat.WINDOWING_MODE_FREEFORM)
        } catch (t: Throwable) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchStack", Int::class.javaPrimitiveType)
                    .invoke(options, FreeformCompat.WINDOWING_MODE_FREEFORM)
            }
        }

        // 2) v2.23.3：桌面级启动边界（替换旧 72%×76% 居中矩形）
        val bounds = desktopWindowBounds(context)
        Log.i(TAG, "桌面窗口边界：$bounds")
        options.setLaunchBounds(bounds)

        val bundle = options.toBundle() ?: return@runCatching null
        // 3) v2.23.2 终极兜底：直接写入隐藏 Bundle 键 ——
        //    setLaunchWindowingMode 内部写的就是这个键，无条件 putInt：
        //    反射成功时是幂等覆盖；反射被 ROM 屏蔽时这里成为唯一生效通道，
        //    与 FreeformCompat 的能力保障配合完成"强制窗口化"
        bundle.putInt(
            FreeformCompat.KEY_LAUNCH_WINDOWING_MODE,
            FreeformCompat.WINDOWING_MODE_FREEFORM
        )
        bundle
    }.getOrNull()

    /**
     * v2.23.3：计算"电脑窗口"级别的启动边界。
     *
     * 设计目标（对标 Windows 桌面应用窗口的观感）：
     * - **大**：宽约 92%，高度填满工作区（顶部安全边距 → 底部任务栏上缘），
     *   与"手机小窗"（约 40% 高、固定手机比例、悬浮在角落）拉开肉眼差距；
     * - **避开状态栏**：顶部边距 ≥ 14dp，否则窗口被状态栏覆盖无法拖动
     *   （framework 实测过的坑：坐标被状态栏遮盖 → 不可拖拽）；
     * - **预留任务栏**：系统 freeform 窗口浮在本应用桌面上层，不预留会
     *   盖住 AnWind 任务栏；高度读取用户设置（36..80dp），未设置时
     *   取 50dp 安全值（宁可有缝隙也不覆盖任务栏）；
     * - **层叠错开**：连续开窗依次右下偏移（5 级循环），像 Windows 桌面
     *   连续开多个程序窗口的效果；
     * - **横竖屏自适应**：基于真实屏幕尺寸（含系统栏），跟随当前旋转。
     */
    private fun desktopWindowBounds(context: Context): Rect {
        val app = context.applicationContext
        val dm = app.resources.displayMetrics
        val density = dm.density

        // 1) 真实屏幕尺寸（含系统栏；freeform 边界使用显示坐标系）
        var sw = dm.widthPixels
        var sh = dm.heightPixels
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val b = wm?.maximumWindowMetrics?.bounds
                if (b != null && !b.isEmpty && b.width() > 0 && b.height() > 0) {
                    sw = b.width()
                    sh = b.height()
                }
            }
        } else {
            runCatching {
                val p = Point()
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.getRealSize(p)
                if (p.x > 0 && p.y > 0) {
                    sw = p.x
                    sh = p.y
                }
            }
        }

        // 2) 任务栏预留（用户设置 36..80dp，否则主题默认 44~48dp，取 50dp 安全值）
        val taskbarPx = (readTaskbarHeightDp(app) * density).toInt() + (6 * density).toInt()

        // 3) 边距：顶部避开状态栏；左右对称留出桌面边缘
        val topPx = maxOf((14 * density).toInt(), (sh * 0.03f).toInt())
        val sidePx = maxOf((10 * density).toInt(), (sw * 0.04f).toInt())

        // 4) 窗口本体：92% 宽 × 工作区全高（顶部边距 → 任务栏上缘）
        val width = (sw * 0.92f).toInt().coerceAtLeast(sw / 3)
        val height = (sh - taskbarPx - topPx).coerceAtLeast(sh / 3)

        // 5) Windows 风格层叠偏移（连续开窗依次右下错开，5 级循环，收尾回到起点）
        val step = (18 * density).toInt()
        val idx = cascadeIndex.getAndIncrement() % 5
        val maxLeft = (sw - width - (4 * density).toInt()).coerceAtLeast(0)
        val maxTop = (sh - taskbarPx - height).coerceAtLeast(0)
        val left = (sidePx + idx * step).coerceAtMost(maxLeft)
        val top = (topPx + (idx * step) / 2).coerceAtMost(maxTop)
        return Rect(left, top, left + width, top + height)
    }

    /**
     * 读取任务栏高度设置（用户自定义 36..80dp；未设置 = 跟随主题 44~48dp）。
     * DataStore 同步读（带 250ms 超时，启动点击时调用一次，实测毫秒级）；
     * 任何异常/超时回退 50dp（宁可用缝隙换任务栏可见）。
     */
    private fun readTaskbarHeightDp(app: Context): Float = runCatching {
        val store = (app as? AnWindApp)?.settingsStore ?: return@runCatching 50f
        val pref = runBlocking { withTimeoutOrNull(250L) { store.taskbarHeight.first() } } ?: 0f
        if (pref in 36f..80f) pref else 50f
    }.getOrDefault(50f)
}

/**
 * v2.17：安卓应用图标渲染（开始菜单网格用）。
 * 图标 Bitmap 已在 AndroidApps.load 的 IO 线程解码，这里直接绘制；
 * 解码失败时用首字符 + 主题色兜底。
 */
@Composable
fun AndroidAppIcon(packageName: String, icon: Bitmap?, size: Dp) {
    val theme = LocalWinTheme.current
    val painter = remember(packageName, icon) {
        icon?.asImageBitmap()?.let { BitmapPainter(it) }
    }
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size / 5))
                .background(theme.accentColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                text = packageName.take(1).uppercase(),
                fontSize = (size.value * 0.45f).sp,
                color = theme.accentColor
            )
        }
    }
}
