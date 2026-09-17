package com.anwind.core.desktop

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * v2.23.3：**根修 v2.23.2 的“重启盲区” + 启动结果验证**。
 *   AOSP 证实 enable_freeform_support 仅在系统启动时读取一次（ATMS.
 *   retrieveSettings，运行时无 Observer 监听该键）——v2.23.2 写开开关后
 *   误判“已生效”从此沉默，导致用户“授权成功却依旧全屏、弹窗也不再出现”。
 *   本版：
 *   - [FreeformCompat] 持久化跟踪写入时间（boot_count/uptime），
 *     本次开机内写入且未重启 → 明确引导重启，不再空试；
 *   - 窗口化启动后 300/800/1600ms 回读目标任务真实 windowingMode
 *     （[readTaskWindowingMode]），读到 freeform(5) 才算成功；读到全屏(1)
 *     → 分原因弹窗（需重启 / ROM 屏蔽）；读不到 → 升级用户一次性提示；
 *   - 决策弹窗分原因展示并附带实时诊断状态（见 DesktopEnvironment）。
 */
object AndroidApps {

    private const val TAG = "AnWind.AndroidApps"

    // v2.23.2：WINDOWING_MODE_FREEFORM(=5) 与隐藏 Bundle 键统一收敛到
    // FreeformCompat，供本对象与能力检测/弹窗共用（原局部常量已删除）

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
     * v2.23.3 状态机（在 v2.23.2 基础上补齐“生效”维度）：
     * 1. [FreeformCompat.ensureAvailable] 保“开关打开”（已授权时自动写入）；
     * 2. 开关未开且未抑制询问 → [FreeformCompat.REASON_NO_PERMISSION] 弹窗；
     * 3. 开关是本次开机内写入的（系统尚未读取）且本进程已验证过它不生效
     *    → 不再空试，直接 [FreeformCompat.REASON_NEEDS_REBOOT] 引导重启
     *    （首次尝试仍会走窗口化启动——照顾个别热加载该开关的 ROM）；
     * 4. 本进程已确认系统拒收窗口化请求（开关开、非本次开机写入仍全屏）
     *    → [FreeformCompat.REASON_STILL_FULLSCREEN] 弹窗排查；
     * 5. 其余 → 窗口化启动 + 启动后三轮 [scheduleFreeformVerifyAndEnforce]
     *    验证/强切，验证结果经 [FreeformCompat.noteVerified] 回写状态，
     *    失败时按原因弹窗，不存在静默失败路径。
     */
    private fun launchAndroidApp(context: Context, pkg: String, activity: String, label: String): Boolean {
        val intent = buildLaunchIntent(context, pkg, activity) ?: return false

        // ① 保能力：确保自由窗口开关处于打开状态（30s 缓存，快路径毫秒级）
        val freeformReady = FreeformCompat.ensureAvailable(context)

        if (!freeformReady) {
            // 用户已选"不再提示" → 直接全屏兜底
            if (FreeformCompat.suppressDecision) return launchPlain(context, intent)
            // 转入决策弹窗（返回 true 让开始菜单先收起，弹窗在桌面层显示）
            FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_NO_PERMISSION)
            return true
        }

        // ② 开关是本次开机内写开的：系统还没读到（AOSP 仅开机读取）。
        //    本进程验证过它确实不生效后直接引导重启，不再空试；
        //    首次仍会落到⑤尝试（个别 ROM 对该开关做了热加载）
        if (FreeformCompat.pendingRebootSinceOurWrite(context) &&
            FreeformCompat.probeTried && !FreeformCompat.probeSucceeded
        ) {
            if (FreeformCompat.suppressDecision) return launchPlain(context, intent)
            FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_NEEDS_REBOOT)
            return true
        }

        // ③ 本进程已确认系统把窗口化请求丢弃（开关开、非本次开机写入仍全屏）
        if (FreeformCompat.brokenConfirmed) {
            if (FreeformCompat.suppressDecision) return launchPlain(context, intent)
            FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_STILL_FULLSCREEN)
            return true
        }

        // ④ 窗口化启动 + 启动后三轮验证/强切
        val ok = runCatching {
            context.startActivity(intent, freeformOptions(context))
            true
        }.getOrElse { false }
        if (!ok) return false

        FreeformCompat.noteLaunchAttempt()
        scheduleFreeformVerifyAndEnforce(context, pkg, activity, label)
        return true
    }

    /** 普通全屏启动（用户已知情选择全屏，或"不再提示"后的兜底） */
    private fun launchPlain(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { false }

    /**
     * v2.23.3：启动后验证 + 兜底强切（取代 v2.23.2 的纯 enforce）。
     *
     * 300/800/1600ms 三轮，每轮：
     * 1. 定位目标包名的最近任务，回读其真实 windowingMode（[readTaskWindowingMode]，
     *    TaskInfo 隐藏字段，@UnsupportedAppUsage 浅灰名单，多数设备可读）；
     * 2. 已是 FREEFORM → 验证成功（[FreeformCompat.noteVerified] 记录）；
     * 3. 非 FREEFORM → 反射 `ActivityTaskManager.setTaskWindowingMode(taskId, 5,
     *    true)` 尝试强切，再回读一次，以回读结果为准（针对一些 ROM 把任务
     *    "先按全屏建好"的时序；系统不支持 freeform 时强切同样被拒绝，
     *    回读仍为全屏 → 如实上报失败）；
     * 4. 最后一轮仍读不到（任务不可见/反射被屏蔽）→ [handleUnverifiable]：
     *    本次开机内写过开关 → 重启引导；否则升级存量用户一次性轻提示。
     *
     * 多轮重试针对"任务先全屏建好、再异步初始化完成"的时序：单次扫描
     * 时机太早会扑空。整个过程不抛异常。
     */
    private fun scheduleFreeformVerifyAndEnforce(
        context: Context,
        pkg: String,
        activity: String,
        label: String
    ) {
        val main = Handler(Looper.getMainLooper())
        val delays = longArrayOf(300L, 800L, 1600L)
        delays.forEachIndexed { index, at ->
            main.postDelayed({
                runCatching {
                    val isLast = index == delays.lastIndex
                    val mode = forceFreeformOnRunningTask(context, pkg)
                    when {
                        mode == FreeformCompat.WINDOWING_MODE_FREEFORM ->
                            // 明确读到 freeform：窗口化真实生效（含强切成功）
                            FreeformCompat.noteVerified(context, true)

                        mode != null -> {
                            // 明确读到非 freeform（1=fullscreen 等）：请求被系统丢弃
                            FreeformCompat.noteVerified(context, false)
                            maybeRequestFailureDecision(context, pkg, activity, label)
                        }

                        else ->
                            // 任务未找到或模式不可读；只在最后一轮做补偿处理
                            if (isLast) handleUnverifiable(context, pkg, activity, label)
                    }
                }.onFailure { Log.w(TAG, "verify/enforce failed: ${it.message}") }
            }, at)
        }
    }

    /** 验证明确失败 → 按是否本次开机写入开关分流弹窗（需重启 / ROM 屏蔽） */
    private fun maybeRequestFailureDecision(
        context: Context,
        pkg: String,
        activity: String,
        label: String
    ) {
        if (FreeformCompat.suppressDecision) return
        if (FreeformCompat.pendingLaunch.value != null) return
        val reason = if (FreeformCompat.pendingRebootSinceOurWrite(context)) {
            FreeformCompat.REASON_NEEDS_REBOOT
        } else {
            FreeformCompat.REASON_STILL_FULLSCREEN
        }
        FreeformCompat.requestDecision(pkg, activity, label, reason)
    }

    /**
     * 验证通道不可用（任务不可见 / windowingMode 反射被屏蔽）的补偿处理：
     * - 本次开机内写过开关 → 大概率确实需要重启，直接重启引导；
     * - 否则若历史从未验证成功过（v2.23.2 升级存量用户：开关已 =1 但无从
     *   判断是否重启过）→ 一次性轻提示；
     * - 其余情况静默（不无端打扰）。
     */
    private fun handleUnverifiable(
        context: Context,
        pkg: String,
        activity: String,
        label: String
    ) {
        if (FreeformCompat.suppressDecision) return
        if (FreeformCompat.pendingLaunch.value != null) return
        if (FreeformCompat.pendingRebootSinceOurWrite(context)) {
            FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_NEEDS_REBOOT)
            return
        }
        if (FreeformCompat.everVerifiedWorking(context)) return
        if (FreeformCompat.firstHintAlreadyShown(context)) return
        FreeformCompat.markFirstHintShown(context)
        FreeformCompat.requestDecision(pkg, activity, label, FreeformCompat.REASON_FIRST_HINT)
    }

    /**
     * v2.23.3：定位目标任务 → 回读真实 windowingMode，必要时强切 freeform。
     *
     * 返回值：目标任务当前的 windowingMode（5=FREEFORM 生效中）；
     * null = 任务未找到或模式不可读（验证通道不可用）。
     *
     * 1) 找最近的任务 —— getRunningTasks 在 Android 5+ 只能看到自己应用的
     *    任务，但作为 Launcher (HOME category) 我们可以拿到全部任务（系统
     *    对默认 Launcher 放行）；取不到时再尝试 getRecentTasks 兼容路径；
     * 2) 已是 freeform 直接返回；否则反射 setTaskWindowingMode 强切后回读。
     */
    private fun forceFreeformOnRunningTask(context: Context, targetPkg: String): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null

        fun findTaskAndId(): Pair<Any?, Int> {
            runCatching {
                @Suppress("DEPRECATION")
                val running = am.getRunningTasks(16) ?: emptyList<ActivityManager.RunningTaskInfo>()
                running.firstOrNull {
                    it.topActivity?.packageName == targetPkg ||
                        it.baseActivity?.packageName == targetPkg
                }?.let { return it to it.id }
                @Suppress("DEPRECATION")
                val recent = am.getRecentTasks(16, ActivityManager.RECENT_WITH_EXCLUDED)
                    ?: emptyList<ActivityManager.RecentTaskInfo>()
                recent.firstOrNull {
                    it.baseIntent?.component?.packageName == targetPkg
                }?.let { return it to it.id }
            }.onFailure { Log.w(TAG, "forceFreeform: 任务查询失败: ${it.message}") }
            return null to -1
        }

        val (first, taskId) = findTaskAndId()
        val task = first ?: run {
            Log.w(TAG, "forceFreeform: 目标任务未找到 (pkg=$targetPkg)")
            return null
        }

        // 已在 freeform → 直接确认成功
        readTaskWindowingMode(task)?.let { mode ->
            if (mode == FreeformCompat.WINDOWING_MODE_FREEFORM) return mode
        }

        // 反射强切（hidden API；系统不支持时同样会被拒绝，以回读为准）
        if (taskId >= 0) invokeSetTaskWindowingMode(am, taskId)

        // 回读强切后的真实状态
        val (after, _) = findTaskAndId()
        return after?.let { readTaskWindowingMode(it) }
    }

    /**
     * 反射调用 `ActivityTaskManager.setTaskWindowingMode(taskId, 5, true)`。
     * 不同 Android 版本类名略有差异：API 29+ 在 android.app.ActivityTaskManager，
     * API 24~28 在 android.app.ActivityManager（同方法签名）。失败只记日志。
     */
    private fun invokeSetTaskWindowingMode(am: ActivityManager, taskId: Int) {
        val atmClz = runCatching { Class.forName("android.app.ActivityTaskManager") }
            .getOrNull() ?: ActivityManager::class.java
        val method = runCatching {
            atmClz.getMethod(
                "setTaskWindowingMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
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
            Log.i(TAG, "forceFreeform: taskId=$taskId → FREEFORM 强切请求已发")
        }.onFailure { Log.w(TAG, "forceFreeform invoke failed: ${it.message}") }
    }

    /**
     * 读取任务真实 windowingMode（5=freeform，1=fullscreen）。
     *
     * `TaskInfo.windowingMode` 为隐藏 API（@UnsupportedAppUsage 浅灰名单，
     * 多数设备可反射读取）：优先尝试 getter（部分 ROM 暴露），失败后沿类
     * 层级查找同名字段；全部失败返回 null（验证通道不可用，调用方按
     * "不可验证"处理，不会误报成功/失败）。
     */
    private fun readTaskWindowingMode(task: Any): Int? {
        runCatching {
            task.javaClass.getMethod("getWindowingMode").invoke(task) as? Int
        }.getOrNull()?.let { return it }
        var c: Class<*>? = task.javaClass
        while (c != null) {
            // 局部 val 快照：避免 var 被 runCatching lambda 捕获导致 smart cast 失败
            val cls: Class<*> = c
            val field = runCatching { cls.getDeclaredField("windowingMode") }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.getInt(task)
                }.getOrNull()
            }
            c = cls.superclass
        }
        return null
    }

    /**
     * v2.23.0：构建"桌面窗口"启动参数（ActivityOptions）。
     *
     * v2.23.1 强化：两步组合 + 多重 fallback：
     * 1. 强制窗口模式 —— ActivityOptions.setLaunchWindowingMode(5) 为
     *    hidden API，反射调用；API 24/25 时代的等价入口是
     *    setLaunchStack(FREEFORM_WORKSPACE_STACK)。两处都被 ROM 屏蔽时
     *    仅退化为"设窗口边界"，再不行则全屏兜底（调用方拿到 null）。
     * 2. 初始窗口边界 —— setLaunchBounds(Rect) 为公开 API（API 24+），
     *    屏幕居中、72% 宽 × 76% 高，最小 420×560（保证小屏上仍一眼
     *    可辨"是个窗口"）。freeform 模式 + 边界 = 应用以指定大小的
     *    浮动窗口出现在桌面中央。
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

        // 2) 初始窗口边界：居中、72% × 76%，小屏不低于 420×560。
        //    即便 ROM 忽略了窗口模式，边界设置本身也无副作用。
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.72f).toInt()
            .coerceAtLeast(minOf(420, dm.widthPixels))
            .coerceAtMost(dm.widthPixels)
        val h = (dm.heightPixels * 0.76f).toInt()
            .coerceAtLeast(minOf(560, dm.heightPixels))
            .coerceAtMost(dm.heightPixels)
        val left = (dm.widthPixels - w) / 2
        val top = (dm.heightPixels - h) / 2
        options.setLaunchBounds(Rect(left, top, left + w, top + h))

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
