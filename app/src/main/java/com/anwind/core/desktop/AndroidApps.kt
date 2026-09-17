package com.anwind.core.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
import com.anwind.core.theme.LocalWinTheme

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
 * v2.23.1~v2.23.3 演进（细节见 [FreeformCompat] 类注释）：
 * - v2.23.1 多路径强制（windowing mode / stack / bounds / setTaskWindowingMode）；
 * - v2.23.2 补"设备能力"前提（检测 + WRITE_SECURE_SETTINGS / Root 写开关）；
 * - v2.23.3 补"重启盲区"跟踪与启动后验证 —— 但验证通道（getRunningTasks 回读）
 *   实际对三方应用无效（AOSP 只放行系统 Recents），且 Bundle 隐藏键名写错，
 *   "终极兜底"从未生效。
 *
 * v2.23.4：**三处根因修复后的最终形态**：
 * 1. [freeformOptions] 写入 AOSP 实测的**正确隐藏键**（点号
 *    "android.activity.windowingMode" + 旧版 "android.activity.launchStackId"），
 *    并先做隐藏 API 豁免再反射 setLaunchWindowingMode —— 窗口化请求
 *    从此真正可靠地送达 system_server（这也是 v2.23.2/3 "开关已开却仍全屏"
 *    的最可能根因）；
 * 2. **弹窗永不阻断启动**：能力可用（特性声明或开关=1）时一律先尝试窗口化
 *    启动，跟进弹窗在启动后按状态触发（无 Root → 一次性用户确认；
 *    Root → dumpsys 真实验证；开关刚写入 → 一次性重启提示）；
 * 3. 删除 v2.23.3 的任务回读验证（getRunningTasks/getRecentTasks 对三方
 *    应用只返回自己的任务，永远读不到目标应用 —— AOSP isGetTasksAllowed
 *    只放行系统 Recents 与 REAL_GET_TASKS 签名权限持有者）。
 *
 * v2.23.5：**“桌面窗口”形态定稿**（用户明确要求：要的是像电脑程序窗口
 * 一样摆在桌面上的“桌面窗口”，不是手机系统那种小尺寸“小窗”）：
 * - [freeformOptions] 的初始边界改为**桌面工作区计算**：屏幕可见区域
 *   去掉底部任务栏，窗口取工作区 82% 宽 × 72% 高，水平居中、纵向居中
 *   略偏上，四周留出可见的桌面边距，任务栏永不被窗口盖住；
 * - **级联错位**：连续打开的窗口按 Windows 经典阶梯摆放；
 * - 桌面层每次重组把最新任务栏高度发布到 [updateTaskbarReserve]
 *   （DesktopEnvironment 的 SideEffect），未发布时按 56dp 保守估计。
 *
 * v2.23.6：**“仍是手机小窗”根因修复 —— 方向感知窗口 + force_resizable**：
 * 用户实测窗口化已生效但窗口是手机比例小窗。AOSP 实锤：手机应用多声明
 * resizeableActivity=false/固定方向，系统未开 force_resizable_activities
 * 时会将其信箱化成手机比例小窗（见 [FreeformCompat] 类注释）。本版：
 * - [freeformOptions] 按目标应用锁定的方向给出**同比例的桌面大窗口**：
 *   竖屏应用 → 高 82% 工作区、宽 9:16 的"高窗口"（桌面尺寸却匹配应用
 *   自身布局，即使未开 force_resizable 也不会被信箱化成小窗）；
 *   横屏应用 → 宽 82% 屏宽、高 9:16 的"宽窗口"；未指定 → 82%×72%；
 * - [fallBackToHints] 新增 [FreeformCompat.REASON_PHONE_SHAPED] 分流：
 *   自由窗口可用但 force_resizable=0 时一次性引导开启（开启+重启后
 *   应用填满桌面大窗口）；
 * - Root 设备启动后比对 dumpsys 真实边界，被 ROM 压小的窗口用
 *   am stack resize 强制拉回桌面边界（[enforceDesktopBoundsViaRoot]）。
 */
object AndroidApps {

    private const val TAG = "AnWind.AndroidApps"

    // ============================================================
    // v2.23.5：桌面窗口几何（任务栏避让 + 级联摆放）
    // ============================================================

    /**
     * 桌面任务栏高度（px），由桌面层每次重组经 [updateTaskbarReserve] 发布。
     *
     * freeform 窗口是**系统级窗口**，会浮在包括 AnWind 在内的一切应用之上
     * —— 想让任务栏始终可见（“桌面窗口”的核心观感），窗口边界必须主动
     * 避开任务栏区域。桌面层未发布前（或发布失效）按 56dp 保守估计。
     */
    @Volatile
    private var taskbarReservePx: Int = -1

    /** 桌面层发布最新任务栏高度（px）；<=0 视为“恢复默认估计” */
    fun updateTaskbarReserve(px: Int) {
        taskbarReservePx = px
    }

    /**
     * 级联计数器：连续打开的窗口按 28dp 阶梯右下错位（Windows 经典多窗口
     * 摆放），第 5 个窗口后从头计数，避免无限漂移出屏。
     */
    private val cascade = AtomicInteger(0)

    /**
     * v2.23.6：最近一次经 [freeformOptions] 请求的桌面窗口边界。
     * Root 设备启动后用它与 dumpsys 读到的真实边界比对，若 ROM 把窗口
     * 压成手机尺寸小窗，则用 am stack resize 强制拉回。
     */
    @Volatile
    private var lastRequestedBounds: Rect? = null

    /** v2.23.6：目标应用方向查询缓存（pkg/activity → 1=竖屏 2=横屏 0=未指定/未知） */
    private val orientationCache = ConcurrentHashMap<String, Int>()

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
     * 决策弹窗里"仍以全屏启动"的入口 —— 跳过 freeform 与询问，
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
                // 新建独立 document 任务（公开 API）—— 避开复用
                // 其他入口创建的 fullscreen 旧任务，让本次任务直接落入
                // freeform 窗口模式；同意图后续启动复用该任务。
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
        )
    }

    /**
     * 手机应用启动主路径（开始菜单 / 桌面快捷方式共用入口）。
     *
     * v2.23.4 状态机（核心原则：**弹窗永不阻断窗口化尝试**）：
     * 1. [FreeformCompat.ensureAvailable] 保"开关打开"（已授权/Root 时自动写入）；
     * 2. 不可用且未抑制询问 → REASON_NO_PERMISSION 弹窗（唯一的前置弹窗）；
     * 3. 可用（特性声明 或 开关=1）→ **一律先窗口化启动**（系统不支持时
     *    AOSP 会静默降级全屏，不抛异常，尝试零成本）；
     * 4. 启动后 [postLaunchFollowUp] 跟进：Root → dumpsys 真实验证；
     *    无 Root → 开关刚写入给一次性重启提示，否则一次性用户确认
     *    （"是否已以窗口打开？"），确认失败转解决方案弹窗。
     */
    private fun launchAndroidApp(context: Context, pkg: String, activity: String, label: String): Boolean {
        val intent = buildLaunchIntent(context, pkg, activity) ?: return false

        // ① 保能力：确保自由窗口开关处于打开状态（30s 缓存，快路径毫秒级）
        if (!FreeformCompat.ensureAvailable(context)) {
            // 用户已选"不再提示" → 直接全屏兜底
            if (FreeformCompat.suppressDecision) return launchPlain(context, intent)
            // 转入决策弹窗（返回 true 让开始菜单先收起，弹窗在桌面层显示）
            FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_NO_PERMISSION)
            return true
        }

        // ② 窗口化启动（v2.23.4：无论开关是刚写入还是历史开启，都先尝试 ——
        //    个别 ROM 热加载开关，且尝试本身零成本）
        val ok = runCatching {
            context.startActivity(intent, freeformOptions(context, pkg, activity))
            true
        }.getOrElse { false }
        if (!ok) return false

        postLaunchFollowUp(context, pkg, activity, label)
        return true
    }

    /** 普通全屏启动（用户已知情选择全屏，或"不再提示"后的兜底） */
    private fun launchPlain(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { false }

    /**
     * v2.23.4：启动后跟进（取代 v2.23.3 失效的"任务回读验证"）。
     *
     * 在后台线程延迟 ~1.2 秒执行（等目标任务建好）：
     * - **Root 设备**：[FreeformCompat.verifyFreeformViaRoot] 用 dumpsys 回读
     *   目标任务真实 windowingMode —— freeform → 记录生效；全屏 → 按是否
     *   本次开机写入分流（重启提示 / 解决方案 + 标记 ROM 忽略开关）；
     * - **无 Root**：开关是本次开机写入 → 一次性重启提示；否则若本安装从未
     *   确认过 → 一次性**用户确认**弹窗（无 Root 时唯一可靠的"验证"）。
     *
     * 弹窗全部在启动之后出现，只提供信息与后续选择，绝不阻断启动本身。
     */
    private fun postLaunchFollowUp(context: Context, pkg: String, activity: String, label: String) {
        if (FreeformCompat.suppressDecision) return
        Thread {
            runCatching {
                Thread.sleep(1200L)
                val ctx = context.applicationContext
                // Root：dumpsys 真实验证
                if (FreeformCompat.suAvailable()) {
                    when (FreeformCompat.verifyFreeformViaRoot(pkg)) {
                        true -> {
                            FreeformCompat.noteVerified(ctx, true)
                            // v2.23.6：ROM 把 freeform 窗口压成手机小窗时
                            //（dumpsys 真实边界 < 请求的 80%），强制拉回桌面边界
                            enforceDesktopBoundsViaRoot(pkg)
                        }
                        false -> {
                            FreeformCompat.noteVerified(ctx, false)
                            if (FreeformCompat.pendingLaunch.value == null) {
                                val reason =
                                    if (FreeformCompat.pendingRebootSinceOurWrite(ctx)) {
                                        FreeformCompat.REASON_NEEDS_REBOOT
                                    } else {
                                        FreeformCompat.REASON_SOLUTIONS
                                    }
                                FreeformCompat.requestDecision(pkg, activity, label, reason)
                            }
                        }
                        null -> fallBackToHints(ctx, pkg, activity, label)
                    }
                    return@runCatching
                }
                fallBackToHints(ctx, pkg, activity, label)
            }.onFailure { Log.w(TAG, "postLaunchFollowUp failed: ${it.message}") }
        }.apply { isDaemon = true }.start()
    }

    /** 无 Root / dumpsys 不可判定时的提示分流（每类至多一次，不唠叨） */
    private fun fallBackToHints(context: Context, pkg: String, activity: String, label: String) {
        if (FreeformCompat.suppressDecision) return
        if (FreeformCompat.pendingLaunch.value != null) return
        when {
            // 开关是本次开机内写入的：系统还没读到，一次性重启提示
            FreeformCompat.pendingRebootSinceOurWrite(context) ->
                FreeformCompat.maybeShowRebootAdvice(context, pkg, activity, label)

            // v2.23.6：窗口化已生效但「强制应用可调整大小」未开 ——
            // 手机应用会被信箱化成手机比例小窗（用户实测现象），
            // 一次性引导开启（开启 + 重启后应用填满桌面大窗口）
            !FreeformCompat.isForceResizableOn(context) &&
                !FreeformCompat.everVerifiedWorking(context) &&
                !FreeformCompat.userConfirmAlreadyAsked(context) -> {
                FreeformCompat.markUserConfirmAsked(context)
                FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_PHONE_SHAPED)
            }

            // 本安装从未确认过效果：一次性用户确认（回答后不再问）
            !FreeformCompat.everVerifiedWorking(context) &&
                !FreeformCompat.userConfirmAlreadyAsked(context) -> {
                FreeformCompat.markUserConfirmAsked(context)
                FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_USER_CONFIRM)
            }

            // 其余（已确认过 / 已标记 ROM 忽略开关）：静默，不再打扰
        }
    }

    /**
     * v2.23.6：目标应用是否锁定了竖屏/横屏（公开字段
     * ActivityInfo.screenOrientation，查询无需权限）。绝大多数手机应用锁竖屏 ——
     * 给这些应用开**同比例的桌面大窗口**（高窗口），既匹配应用自身布局，
     * 又不会被系统信箱化成手机小窗 —— 即使 force_resizable_activities
     * 未开，也能获得最佳桌面窗口观感。
     *
     * @return true=锁竖屏；false=锁横屏；null=未指定/查询失败
     */
    private fun lockedOrientation(context: Context, pkg: String, activity: String): Boolean? {
        if (activity.isBlank()) return null
        val key = "$pkg/$activity"
        orientationCache[key]?.let { return it == 1 }
        val result = runCatching {
            when (context.packageManager
                .getActivityInfo(ComponentName(pkg, activity), 0).screenOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> true

                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> false

                else -> null
            }
        }.getOrNull()
        orientationCache[key] = when (result) { true -> 1; false -> 2; null -> 0 }
        return result
    }

    /**
     * v2.23.6，Root 专属兑底：比对任务真实边界（dumpsys）与请求的桌面窗口边界，
     * 若 ROM 把 freeform 窗口压成手机比例小窗（小于请求的 80%），用
     * am stack resize 强制拉回。后台线程调用，全失败路径静默（best effort）。
     */
    private fun enforceDesktopBoundsViaRoot(pkg: String) {
        runCatching {
            val requested = lastRequestedBounds ?: return
            val task = FreeformCompat.findRootTaskInfo(pkg) ?: return
            if (!task.freeform) return
            val tb = task.bounds ?: return
            val shrunk = tb.width() < requested.width() * 0.8f ||
                tb.height() < requested.height() * 0.8f
            if (!shrunk) return
            val adjusted = FreeformCompat.resizeRootTask(task.id, requested)
            Log.i(
                TAG,
                "Root 桌面边界校正（$pkg）：请求=${requested.toShortString()} " +
                    "实际=${tb.toShortString()} ${if (adjusted) "已调整" else "失败（任务不可调/ROM 拒绝）"}"
            )
        }
    }

    /**
     * v2.23.0：构建"桌面窗口"启动参数（ActivityOptions）。
     *
     * v2.23.5：**桌面窗口形态定稿** —— 用户要的是"像电脑程序窗口一样摆在
     * 桌面上"，而不是手机 ROM 那种小尺寸居中的"小窗"：
     * - 初始边界基于**桌面工作区**（屏幕可见区域去掉底部任务栏）计算：
     *   宽 82% 屏宽、高 72% 工作区高，水平居中、纵向居中；四周留出
     *   可见的桌面边距，任务栏永不被盖住 —— 一眼可辨"这是桌面上的
     *   程序窗口"，与系统小窗（小尺寸、贴顶悬浮）明显区分；
     * - 连续打开的窗口按 28dp 级联右下错位（Windows 经典多窗口摆放），
     *   第 5 个窗口后从头计数；
     * - 坐标系：优先用 Activity 内容视图的**可见 frame**（屏幕坐标系，
     *   天然对齐状态栏/导航条的实际占位，无论是否边到边都准确），
     *   拿不到时回退真实显示尺寸。
     *
     * v2.23.4 的送达链路保持不变（已对 AOSP 逐行核对）：
     * 1. **隐藏 API 豁免**：先调 [FreeformCompat.exemptHiddenApis]
     *    （VMRuntime.setHiddenApiExemptions，同 farmerbb/Taskbar 的做法），
     *    解除 Android 9+ 对三方应用的反射限制；
     * 2. **窗口模式反射三连**：setLaunchWindowingMode（API 28+）→
     *    setLaunchStackId（API 24~27）→ setLaunchStack（极旧 ROM 兜底），
     *    命中任意一个即可，值均为 5（FREEFORM）；
     * 3. **双 Bundle 隐藏键兜底**：toBundle() 后直接 putInt 两个真实键
     *    （"android.activity.windowingMode" API 28+ /
     *    "android.activity.launchStackId" API 24~27）—— setLaunchWindowingMode
     *    内部写的就是同一个键，反射被屏蔽时这里是等效主通道。
     *
     * 返回 null 表示构建失败（极少见），调用方回退普通启动。
     *
     * v2.23.6：**方向感知尺寸** —— 按目标应用锁定的方向给同比例桌面大窗口
     * （竖屏应用 → 高窗口：高 82% 工作区、宽 9:16；横屏应用 → 宽窗口：
     * 宽 82% 屏宽、高 9:16；未指定 → 82% 宽 × 72% 高）。竖屏高窗口既匹配
     * 手机应用自身布局，又不会被系统信箱化成手机小窗；
     * force_resizable_activities 开启后应用直接填满这些窗口。
     */
    private fun freeformOptions(context: Context, pkg: String, activity: String): Bundle? = runCatching {
        // 1) 解除隐藏 API 限制（Android 9+；失败无碍，Bundle 键通道不依赖反射）
        FreeformCompat.exemptHiddenApis()

        val options = ActivityOptions.makeBasic()
        val cls = ActivityOptions::class.java
        val mode = FreeformCompat.WINDOWING_MODE_FREEFORM

        // 2) 窗口模式：按版本找任一可用的隐藏方法
        listOf("setLaunchWindowingMode", "setLaunchStackId", "setLaunchStack")
            .asSequence()
            .mapNotNull { name ->
                runCatching {
                    cls.getMethod(name, Int::class.javaPrimitiveType)
                }.getOrNull()
            }
            .firstOrNull()
            ?.invoke(options, mode)

        // 3) 桌面窗口边界（v2.23.5 起）：屏幕可见区域去掉底部任务栏 = 桌面工作区，
        //    尺寸按目标应用方向给出同比例桌面大窗口（见 3.3，v2.23.6）。
        val dm = context.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)

        // 3.1) 屏幕坐标系里的桌面可用区域：优先 Activity 内容视图的可见
        //      frame（已扣除状态栏/导航条的真实占位），失败回退显示尺寸。
        val frame = Rect()
        (context as? Activity)?.window
            ?.findViewById<View>(android.R.id.content)
            ?.getWindowVisibleDisplayFrame(frame)
        val screenLeft: Int
        val screenTop: Int
        val screenRight: Int
        val screenBottom: Int
        if (!frame.isEmpty()) {
            screenLeft = frame.left; screenTop = frame.top
            screenRight = frame.right; screenBottom = frame.bottom
        } else {
            screenLeft = 0; screenTop = 0
            screenRight = dm.widthPixels; screenBottom = dm.heightPixels
        }
        val screenW = (screenRight - screenLeft).coerceAtLeast(1)
        val screenH = (screenBottom - screenTop).coerceAtLeast(1)

        // 3.2) 任务栏避让：桌面层发布的实时高度（未发布时 56dp 估计）+ 8dp 间隙
        val taskbarPx = (if (taskbarReservePx > 0) taskbarReservePx
            else (56 * density).toInt()) + (8 * density).toInt()
        val workTop = screenTop
        val workBottom = (screenBottom - taskbarPx).coerceAtLeast(screenTop + screenH / 2)
        val workH = (workBottom - workTop).coerceAtLeast(1)

        // 3.3) v2.23.6 桌面窗口尺寸（方向感知）：
        //      - 竖屏锁定应用（绝大多数手机应用）→ 高窗口：高 82% 工作区，
        //        宽 = 高 × 9/16 —— 桌面尺寸且匹配应用布局，不会被信箱化成
        //        手机小窗（就像在电脑上开一个竖版应用窗口）；
        //      - 横屏锁定应用（游戏等）→ 宽窗口：宽 82% 屏宽，高 = 宽 × 9/16；
        //      - 未指定方向 → 通用桌面窗口（82% 宽 × 72% 高，同 v2.23.5）。
        //        force_resizable_activities 开启后应用直接填满这些窗口。
        val w: Int
        val h: Int
        when (lockedOrientation(context, pkg, activity)) {
            true -> {
                h = (workH * 0.82f).toInt()
                    .coerceAtLeast(minOf(560, workH))
                    .coerceAtMost(workH)
                w = (h * 9f / 16f).toInt()
                    .coerceAtLeast(minOf(400, screenW))
                    .coerceAtMost((screenW * 0.92f).toInt().coerceAtLeast(minOf(400, screenW)))
            }
            false -> {
                w = (screenW * 0.82f).toInt()
                    .coerceAtLeast(minOf(420, screenW))
                    .coerceAtMost(screenW)
                h = (w * 9f / 16f).toInt()
                    .coerceAtLeast(minOf(320, workH))
                    .coerceAtMost((workH * 0.80f).toInt().coerceAtLeast(minOf(320, workH)))
            }
            null -> {
                w = (screenW * 0.82f).toInt()
                    .coerceAtLeast(minOf(420, screenW))
                    .coerceAtMost(screenW)
                h = (workH * 0.72f).toInt()
                    .coerceAtLeast(minOf(560, workH))
                    .coerceAtMost(workH)
            }
        }

        // 3.4) 居中 + 级联错位（每窗右下移 28dp，5 级循环），整体钳回工作区内
        val step = (28 * density).toInt()
        val n = ((cascade.getAndIncrement() % 5) + 5) % 5
        val left = (screenLeft + (screenW - w) / 2 + n * step)
            .coerceIn(screenLeft, (screenLeft + screenW - w).coerceAtLeast(screenLeft))
        val top = (workTop + (workH - h) / 2 + n * step)
            .coerceIn(workTop, (workBottom - h).coerceAtLeast(workTop))
        lastRequestedBounds = Rect(left, top, left + w, top + h)
        options.setLaunchBounds(Rect(left, top, left + w, top + h))

        val bundle = options.toBundle() ?: return@runCatching null
        // 4) 双键兜底（AOSP 实测字面值；各版本只读自己认识的键，多余键被忽略）
        bundle.putInt(FreeformCompat.KEY_LAUNCH_WINDOWING_MODE, mode)
        bundle.putInt(FreeformCompat.KEY_LAUNCH_STACK_ID, mode)
        bundle
    }.getOrNull()
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
