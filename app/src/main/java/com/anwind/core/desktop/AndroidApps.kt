package com.anwind.core.desktop

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
import android.widget.Toast
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
 * v2.23.0 ~ v2.23.3：freeform 窗口化启动（详见 [FreeformCompat] 头注释的
 * 演进史——四轮实测反馈：先"没窗口"，再"厂商小窗"，最终"只全屏"）。
 *
 * v2.24.0：**启动级联重构**，对应 [FreeformCompat] 的引擎化：
 *
 * 1. **MIUI/HyperOS 加速优先**：检测到小米系 framework
 *    （`android.util.MiuiMultiWindowUtils` 存在）时，先走
 *    [FreeformCompat.miuiBoostedOptions] 拿"带 MIUI 私有 extras"的
 *    启动参数（MIUI 自家小窗按钮的通路），再覆盖成桌面级大窗口
 *    边界——骑在厂商自己的自由窗口通路上，而不是被其小窗策略
 *    改写成"手机小窗"或全屏；
 * 2. **AOSP freeform 兜底**：修正了 v2.23.x 写错的
 *    `KEY_LAUNCH_WINDOWING_MODE`（点号而非冒号），"反射被屏蔽时的
 *    Bundle 直写"这条兜底通道首次真正可用；
 * 3. **卡全屏修复**：[FreeformCompat.shouldClearStuckTask] 发现目标
 *    应用存在全屏既存任务时补 `FLAG_ACTIVITY_CLEAR_TASK` 重建任务
 *    （旧版 NEW_DOCUMENT 不带 MULTIPLE_TASK 只是bring-to-front，
 *    窗口模式永不重新应用——"只全屏显示，没任何窗口"的直接成因）；
 * 4. **移除死代码**：删除 v2.23.1 的"启动后三轮 setTaskWindowingMode
 *    强制切窗"——该服务端方法在 Android 12+ 已被系统移除，反射必然
 *    失败，纯空跑；
 * 5. **启动结果观测**：默认 Launcher 身份下，启动 1.2s 后读取目标任务
 *    的真实窗口模式写进 [FreeformCompat.lastLaunch]（诊断页展示），
 *    若仍全屏则 Toast 引导用户到 设置→桌面窗口 查看分 ROM 方案。
 */
object AndroidApps {

    private const val TAG = "AnWind.AndroidApps"

    /** 策略名（诊断用） */
    private const val STRATEGY_MIUI = "MIUI_BOOST（厂商专有通道 + 桌面级边界）"
    private const val STRATEGY_AOSP = "AOSP_FREEFORM（系统自由窗口 + 桌面级边界）"
    private const val STRATEGY_PLAIN = "PLAIN（普通启动，未窗口化）"

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
     * getLaunchIntentForPackage。
     *
     * ⚠️ 不在这里加 CLEAR_TASK——是否重建任务由 v2.24.0 的
     * [FreeformCompat.shouldClearStuckTask] 按既存任务状态动态决定
     * （见 [launchAndroidApp] 步骤 ②）。
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
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT
        )
    }

    /**
     * 手机应用启动主路径（开始菜单 / 桌面快捷方式共用入口），v2.24.0 级联：
     *
     * ① [FreeformCompat.ensureAvailable] 保能力（已授权时同步写入四键开关）；
     *    不可用 → 决策弹窗 / 抑制后直接全屏（沿用 v2.23.2 行为）。
     * ② [FreeformCompat.shouldClearStuckTask]：既存全屏任务检测 →
     *    需要 CLEAR_TASK 时给 Intent 补标志，重建任务让窗口模式生效。
     * ③ MIUI/HyperOS 加速：小米系设备优先拿厂商专有 options（成功率高时
     *    窗口不被改写成小窗/全屏），失败回退 AOSP freeform options。
     * ④ 启动 → 记录 [FreeformCompat.LaunchReport] → 1.2s 后观测任务
     *    真实窗口模式（默认 Launcher 时），仍全屏则 Toast 引导。
     * ⑤ 授权建议（v2.23.3 逻辑保留）：freeform 可用但 ADB 未授权时
     *    每进程提示一次。
     */
    private fun launchAndroidApp(context: Context, pkg: String, activity: String, label: String): Boolean {
        val intent = buildLaunchIntent(context, pkg, activity) ?: return false
        val ctx = context.applicationContext

        // ① 保能力
        val freeformReady = FreeformCompat.ensureAvailable(ctx)
        if (!freeformReady) {
            if (FreeformCompat.suppressDecision) {
                return runCatching {
                    ctx.startActivity(intent)
                    true
                }.getOrElse { false }
            }
            FreeformCompat.requestDecision(pkg, activity, label)
            return true
        }

        // ② 卡全屏修复：目标应用存在全屏既存任务 → 重建任务
        val clearTask = FreeformCompat.shouldClearStuckTask(ctx, pkg)
        if (clearTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            Log.i(TAG, "既存全屏任务检测命中 → 补 FLAG_ACTIVITY_CLEAR_TASK 重建任务")
        }

        // ③ 桌面级大窗口边界（v2.23.3 逻辑）
        val bounds = desktopWindowBounds(ctx)

        // ④ MIUI/HyperOS 加速 → AOSP freeform 兜底
        var strategy = STRATEGY_AOSP
        var options: Bundle? = null
        if (FreeformCompat.hasMiuiFramework()) {
            options = FreeformCompat.miuiBoostedOptions(ctx, pkg, bounds)
            if (options != null) strategy = STRATEGY_MIUI
        }
        if (options == null) {
            options = freeformOptions(bounds)
        }

        val launched = runCatching {
            if (options != null) {
                ctx.startActivity(intent, options)
            } else {
                ctx.startActivity(intent)
                strategy = STRATEGY_PLAIN
            }
            true
        }.getOrElse { false }

        if (launched) {
            val report = FreeformCompat.LaunchReport(
                at = System.currentTimeMillis(),
                pkg = pkg,
                label = label,
                strategy = strategy,
                bounds = bounds,
                clearTask = clearTask
            )
            FreeformCompat.reportLaunch(report)
            scheduleResultObservation(ctx, pkg, report)
        }

        // ⑤ 授权建议（freeform 已可用、但 WRITE_SECURE_SETTINGS 未授予：
        //    四键开关无法自动写入，未适配应用更易被厂商小窗策略改写）
        if (launched &&
            !FreeformCompat.hasSecureSettings(ctx) &&
            !FreeformCompat.suppressSetupHint &&
            !setupHintShown
        ) {
            setupHintShown = true
            FreeformCompat.requestSetupHint(pkg, activity, label)
        }
        return launched
    }

    /**
     * v2.24.0：启动结果观测（仅默认 Launcher 有效）。
     *
     * 1.2s 后读取目标任务的真实窗口模式：
     * - freeform(5) → 报告记 ✓（诊断页可见，用户可自行确认引擎工作正常）；
     * - fullscreen(1) → Toast 提示 + 引导到 设置→桌面窗口（分 ROM 方案）；
     * - 读不到 → 静默（非默认 Launcher 属正常，无扰）。
     */
    private fun scheduleResultObservation(context: Context, pkg: String, report: FreeformCompat.LaunchReport) {
        Handler(Looper.getMainLooper()).postDelayed({
            val mode = runCatching {
                FreeformCompat.recentTaskWindowingMode(context, pkg)
            }.getOrNull() ?: return@postDelayed
            report.observed = true
            report.observedWindowing = mode
            if (mode == FreeformCompat.WINDOWING_MODE_FULLSCREEN) {
                Log.w(TAG, "启动后观测：任务仍为全屏（pkg=$pkg）")
                runCatching {
                    Toast.makeText(
                        context,
                        "「${report.label}」未进入桌面窗口（系统仍按全屏打开）\n" +
                            "请到 设置 → 桌面窗口 查看解决方案与诊断报告",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else if (mode == FreeformCompat.WINDOWING_MODE_FREEFORM) {
                Log.i(TAG, "启动后观测：任务已进入 freeform 桌面窗口（pkg=$pkg）")
            }
        }, 1200L)
    }

    /**
     * v2.24.0：构建 AOSP freeform 启动参数。
     *
     * 1. 窗口模式：反射 `setLaunchWindowingMode(5)`（@hide，unsupported
     *    浅灰名单，三方应用普遍可用；Taskbar 同款）；失败再试 API 24/25
     *    时代的 `setLaunchStack(5)`；
     * 2. 桌面级启动边界：公开 API `setLaunchBounds`；
     * 3. Bundle 直写兜底：**修正后的** `android.activity.windowingMode`
     *    键（v2.23.x 误写冒号版本，系统解析不到，从未生效）。
     *
     * 返回 null 表示构建失败（极少见），调用方回退普通启动。
     */
    private fun freeformOptions(bounds: Rect): Bundle? = runCatching {
        val options = ActivityOptions.makeBasic()

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

        options.setLaunchBounds(bounds)

        val bundle = options.toBundle() ?: return@runCatching null
        bundle.putInt(
            FreeformCompat.KEY_LAUNCH_WINDOWING_MODE,
            FreeformCompat.WINDOWING_MODE_FREEFORM
        )
        bundle
    }.getOrNull()

    /**
     * v2.23.3：计算"电脑窗口"级别的启动边界（v2.24.0 沿用）。
     *
     * - 大：宽约 92%，高度填满工作区（顶部安全边距 → 底部任务栏上缘）；
     * - 避开状态栏：顶部边距 ≥ 14dp；
     * - 预留任务栏：高度读取用户设置（36..80dp），未设置取 50dp；
     * - 层叠错开：连续开窗依次右下偏移（5 级循环）；
     * - 横竖屏自适应：基于真实屏幕尺寸（含系统栏）。
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

        // 2) 任务栏预留
        val taskbarPx = (readTaskbarHeightDp(app) * density).toInt() + (6 * density).toInt()

        // 3) 边距
        val topPx = maxOf((14 * density).toInt(), (sh * 0.03f).toInt())
        val sidePx = maxOf((10 * density).toInt(), (sw * 0.04f).toInt())

        // 4) 窗口本体
        val width = (sw * 0.92f).toInt().coerceAtLeast(sw / 3)
        val height = (sh - taskbarPx - topPx).coerceAtLeast(sh / 3)

        // 5) Windows 风格层叠偏移
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
     * DataStore 同步读（带 250ms 超时）；异常/超时回退 50dp。
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
