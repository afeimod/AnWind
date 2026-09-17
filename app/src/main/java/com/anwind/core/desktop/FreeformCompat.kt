package com.anwind.core.desktop

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.23.2：自由窗口（Freeform）能力检测 / 自动开启 / 用户决策中枢。
 *
 * v2.23.3：已授权即无条件写入 force_resizable_activities（global+secure 双表）。
 *
 * v2.24.0：**升级为「桌面窗口引擎」**——多策略 + MIUI/HyperOS 加速 + 诊断体系。
 *
 * ## v2.24.0 为什么重构
 *
 * 用户实测（Android 12+ 国产 ROM）：即便 `enable_freeform_support` /
 * `force_resizable_activities` 全部写入成功，从桌面启动手机应用仍然
 * 「手机自带小窗 / 直接全屏」。经 AOSP 源码逐条验证，旧方案存在四个
 * 结构性缺陷：
 *
 * 1. **兜底 Bundle 键名写错**：`setLaunchWindowingMode` 落盘键为
 *    `android.activity.windowingMode`（**点号**），旧代码写的是
 *    `android:activity.windowingMode`（冒号）→ 所谓"终极兜底"从未生效；
 * 2. **`setTaskWindowingMode` 在 Android 12+ 已从系统服务移除**
 *    （被 TaskOrganizer/WindowContainerTransaction 取代，且后者仅系统
 *     进程可注册）→ 启动后三轮"强制切窗"全部空跑；
 * 3. **任务复用陷阱**：`FLAG_ACTIVITY_NEW_DOCUMENT` 不带
 *    `FLAG_ACTIVITY_MULTIPLE_TASK` 时，同一应用第二次启动只是把
 *    **既存任务**带到前台，不重新应用窗口模式 → 一旦某应用曾以全屏
 *    建过任务（比如从系统桌面点开过一次），之后无论怎么带参数启动
 *    都"只全屏、没任何窗口"（用户最新反馈的直接成因之一）；
 * 4. **国产 ROM 小窗策略层拦截**：MIUI/HyperOS/ColorOS 等在
 *    framework 里注入自家多窗口策略（`android.util.MiuiMultiWindowUtils`
 *    / `MiuiFreeFormStackDisplayStrategy` 等，见开源模块 MaxFreeForm
 *    的 hook 清单），裸 AOSP 参数会被改写成自家小窗/全屏。
 *
 * ## v2.24.0 对策
 *
 * - **设置面**：新增写入 `enable_non_resizable_multi_window`
 *   （Android 13+ 开发者选项第二个"不可调整大小多窗口"开关，
 *    AOSP `ATMS.mDevEnableNonResizableMultiWindow` 的数据源）；
 * - **反射面**：`dalvik.system.VMRuntime.setHiddenApiExemptions("L")`
 *   免豁免全部隐藏 API（LSPosed 同款纯 Java 通道，无需权限），
 *   失败时再借已授予的 WRITE_SECURE_SETTINGS 写 `hidden_api_policy=1`；
 * - **MIUI/HyperOS 加速**：豁免后反射
 *   `com.miui.launcher.utils.ActivityUtilsCompat.makeFreeformActivityOptions`
 *   / `android.util.MiuiMultiWindowUtils` 系列方法，拿到带 MIUI 私有
 *   extras 的 ActivityOptions（MIUI 自家小窗按钮走的就是这条通路），
 *   再把边界覆盖成桌面级大窗口 —— 骑在厂商自己的自由窗口通路上，
 *   而不是与之对抗；
 * - **卡全屏修复**：作为默认 Launcher 时读取最近任务的真实窗口模式，
 *   发现目标应用存在全屏任务 → 补 `FLAG_ACTIVITY_CLEAR_TASK` 重建
 *   任务使窗口模式生效；非 Launcher 用户可在设置页手动开启"总是新任务"；
 * - **诊断体系**：`snapshot()` 汇总设备/权限/开关/反射/最近一次启动
 *   的全部事实，设置页一键复制 —— 不再盲猜设备状态。
 */
object FreeformCompat {

    private const val TAG = "AnWind.FreeformCompat"

    /** android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM（隐藏常量，各版本数值恒为 5） */
    const val WINDOWING_MODE_FREEFORM = 5

    /** WINDOWING_MODE_FULLSCREEN（诊断用，AOSP 恒为 1） */
    const val WINDOWING_MODE_FULLSCREEN = 1

    /**
     * ActivityOptions.toBundle() 里窗口模式的隐藏键（KEY_LAUNCH_WINDOWING_MODE）。
     *
     * ⚠️ v2.24.0 修正：真实字面值为 `android.activity.windowingMode`
     * （**点号**分隔，frameworks/base ActivityOptions.java L244）。
     * 旧版误写成冒号 `android:activity.windowingMode`，系统解析不到，
     * "反射被屏蔽时的终极兜底"从未生效——v2.23.x 系列"只全屏/小窗"
     * 问题的成因之一。
     */
    const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"

    /** 开发者选项「启用自由窗口」对应的全局设置键（WMS 热加载，改完即生效） */
    private const val SETTING_ENABLE_FREEFORM = "enable_freeform_support"

    /**
     * 「强制将活动设为可调整大小」（AOSP 真实键）：
     * ATMS.retrieveSettings → mForceResizableActivities →
     * Task.isResizeable()/ActivityRecord.isResizeable() 直接短路为 true，
     * 未适配多窗口的应用不再被钉死在固定尺寸。
     */
    private const val SETTING_FORCE_RESIZABLE = "force_resizable_activities"

    /**
     * v2.24.0 新增：「启用不可调整大小的多窗口」（Android 13+ 开发者
     * 选项另一个开关）→ mDevEnableNonResizableMultiWindow，允许不可
     * 缩放应用进入多窗口（letterbox 兼容渲染）。与上一个键分别对应
     * 开发者选项里两个独立开关，必须同时写。
     */
    private const val SETTING_NON_RESIZABLE_MW = "enable_non_resizable_multi_window"

    /**
     * v2.24.0 新增：隐藏 API 限制策略（developer.android.com 官方逃生口）：
     * `settings put global hidden_api_policy 1` = 停用一切非 SDK 接口
     * 检测。借用户已授予的 WRITE_SECURE_SETTINGS 写入，用于解锁
     * MIUI/HyperOS framework 注入类（android.util.MiuiMultiWindowUtils
     * 等）的反射访问。与 [HiddenApi] 的 VMRuntime 通道互为保险。
     */
    private const val SETTING_HIDDEN_API_POLICY = "hidden_api_policy"

    /** v2.24.0：允许写入的 force_resizable 类键的旧社区写法（多写无副作用） */
    private val SETTING_FORCE_RESIZABLE_ALIASES = arrayOf("force_resizable")

    /** 推荐用户执行的 ADB 一次性授权命令（授予后 AnWind 自动开启并维持全部开关） */
    const val ADB_GRANT_COMMAND =
        "adb shell pm grant com.anwind android.permission.WRITE_SECURE_SETTINGS"

    /** 引擎级 SharedPreferences（独立于 DataStore，避免碰既有偏好结构） */
    private const val PREFS = "window_engine"

    /** 自由窗口不可用时的待决策启动请求（DesktopEnvironment 收集后弹窗） */
    data class PendingLaunch(
        val pkg: String,
        /** 启动 Activity 全类名；空 = 走 getLaunchIntentForPackage 兜底 */
        val activity: String,
        val label: String
    )

    /**
     * v2.23.3：授权建议提示（freeform 已可用、但 WRITE_SECURE_SETTINGS 未授予）。
     * 状态驱动（会话级，不持久化）：授权后永不弹，未授权每个进程最多提醒一次。
     */
    data class SetupHint(
        val pkg: String,
        val activity: String,
        val label: String
    )

    /**
     * v2.24.0：最近一次手机应用启动的完整事实记录（诊断页展示）。
     * 时间、目标、采用策略、参数是否带上、以及启动后 1.2s 观测到的
     * 任务真实窗口模式（仅默认 Launcher 可观测，其余为 null）。
     */
    data class LaunchReport(
        val at: Long,
        val pkg: String,
        val label: String,
        /** 策略名：MIUI_BOOST / AOSP_FREEFORM / FALLBACK_FULLSCREEN */
        val strategy: String,
        /** 启动边界（桌面级大窗口矩形） */
        val bounds: Rect?,
        /** 是否补了 FLAG_ACTIVITY_CLEAR_TASK（卡全屏修复） */
        val clearTask: Boolean,
        /** 启动后观测到的任务窗口模式；null = 无法观测（非默认 Launcher） */
        var observedWindowing: Int? = null,
        /** 观测是否成功读到 */
        var observed: Boolean = false
    )

    private val _pendingLaunch = MutableStateFlow<PendingLaunch?>(null)

    /** 当前待用户决策的启动请求；null = 无弹窗 */
    val pendingLaunch: StateFlow<PendingLaunch?> = _pendingLaunch

    private val _setupHint = MutableStateFlow<SetupHint?>(null)

    /** v2.23.3：当前待展示的授权建议；null = 无弹窗 */
    val setupHint: StateFlow<SetupHint?> = _setupHint

    /** v2.24.0：最近一次启动报告（诊断页实时展示） */
    private val _lastLaunch = MutableStateFlow<LaunchReport?>(null)
    val lastLaunch: StateFlow<LaunchReport?> = _lastLaunch

    /** 本次进程内"授权建议不再提示"（仅会话级，不持久化） */
    @Volatile
    var suppressSetupHint: Boolean = false

    /** 设置写入是否已成功完成（每进程一次） */
    private val settingsWritten = AtomicBoolean(false)

    /** 本次进程内"不再询问"（弹窗里点过后，之后直接全屏启动，不再打扰） */
    @Volatile
    var suppressDecision: Boolean = false

    /** 能力缓存（30 秒 TTL，避免每次点图标都读 SettingsProvider） */
    @Volatile
    private var cachedAvailable: Boolean? = null

    @Volatile
    private var cachedAt: Long = 0L

    private const val CACHE_TTL_MS = 30_000L

    /** Root 尝试只做一次/进程（避免每次启动手机应用都弹 su 授权框打扰用户） */
    private val rootAttempted = AtomicBoolean(false)

    // ============================================================
    // v2.24.0：引擎设置（SharedPreferences 持久化）
    // ============================================================

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 「卡全屏自动修复」：默认 Launcher 状态下，检测到目标应用存在
     * 全屏既存任务时，自动补 FLAG_ACTIVITY_CLEAR_TASK 重建任务让
     * 窗口模式重新生效。默认开启（对应 v2.23.x 用户"只全屏"主诉）。
     */
    fun isAutoFixStuckTask(context: Context): Boolean =
        prefs(context).getBoolean("auto_fix_stuck_task", true)

    fun setAutoFixStuckTask(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_fix_stuck_task", value).apply()
    }

    /**
     * 「总是以全新任务启动」：无视检测结果，每次启动手机应用都带
     * CLEAR_TASK（非默认 Launcher 用户无法自动检测时的手动兜底；
     * 代价是每次点击图标应用都从头启动）。
     */
    fun isAlwaysNewTask(context: Context): Boolean =
        prefs(context).getBoolean("always_new_task", false)

    fun setAlwaysNewTask(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("always_new_task", value).apply()
    }

    // ============================================================
    // 能力检测
    // ============================================================

    /**
     * 设备是否原生声明支持自由窗口（DeX / 部分国产 ROM / 模拟器等）。
     * 注：MIUI/HyperOS 声明了该特性（其小窗即基于 freeform 实现），
     * 因此「特性存在」≠「裸 AOSP freeform 参数不会被改写」。
     */
    private fun hasFreeformFeature(context: Context): Boolean = runCatching {
        context.packageManager
            .hasSystemFeature("android.hardware.freeform_window_management")
    }.getOrDefault(false)

    /** 全局「启用自由窗口」开关是否已打开（开发者选项或本类写入） */
    private fun isFreeformSettingOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING_ENABLE_FREEFORM, 0) != 0
    }.getOrDefault(false)

    /** 当前设备自由窗口能力探测（A / B 任一成立即可用） */
    private fun probe(context: Context): Boolean =
        hasFreeformFeature(context) || isFreeformSettingOn(context)

    /** WRITE_SECURE_SETTINGS 是否已通过 ADB / Shizuku 授予本应用 */
    fun hasSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 悬浮窗（显示在其他应用上层）权限是否已授予 */
    fun hasOverlayPermission(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    /** 本应用是否为当前默认 Launcher（决定能否观测/读取其他应用任务） */
    fun isDefaultLauncher(context: Context): Boolean = runCatching {
        val cur = context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME),
            0
        )
        cur?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    // ============================================================
    // v2.24.0：隐藏 API 豁免（双通道）
    // ============================================================

    /**
     * 隐藏 API 豁免（进程内、免权限）。
     *
     * 原理：`dalvik.system.VMRuntime.setHiddenApiExemptions(String[])`
     * 接受类名前缀数组，`"L"` 即 JNI 签名里所有类的公共前缀
     * （"Ljava/lang/..."）→ 豁免全部类。该方法本身在隐藏 API的
     * unsupported（浅灰）名单里，P+ 可直接反射，是 LSPosed /
     * Tasker 等工具的通用做法。每进程只需成功一次（结果缓存）。
     *
     * 与 [SETTING_HIDDEN_API_POLICY]（设置表通道）互为保险：本方法
     * 只影响本进程；设置表全局生效但需要 WRITE_SECURE_SETTINGS。
     */
    object HiddenApi {
        @Volatile
        private var lifted = false

        @Volatile
        private var attempted = false

        /** 是否已成功豁免（本进程） */
        val isLifted: Boolean get() = lifted

        /** 尝试豁免（幂等，每进程至多真正尝试一次；失败缓存不再重试） */
        fun lift(): Boolean {
            if (lifted) return true
            if (attempted) return false
            synchronized(this) {
                if (lifted) return true
                if (attempted) return false
                attempted = true
                lifted = runCatching {
                    val vmRuntime = Class.forName("dalvik.system.VMRuntime")
                    val getRuntime = vmRuntime.getDeclaredMethod("getRuntime")
                    getRuntime.isAccessible = true
                    val setExemptions = vmRuntime.getMethod(
                        "setHiddenApiExemptions", Array<String>::class.java
                    )
                    setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
                    Log.i(TAG, "HiddenApi: VMRuntime.setHiddenApiExemptions(\"L\") 已生效")
                    true
                }.getOrElse {
                    Log.w(TAG, "HiddenApi: VMRuntime 豁免失败（${it.message}），" +
                        "回退 hidden_api_policy 设置通道")
                    false
                }
                return lifted
            }
        }
    }

    // ============================================================
    // v2.24.0：MIUI / HyperOS 探测与加速
    // ============================================================

    /**
     * MIUI/HyperOS 系 framework 注入类是否存在于 boot classpath。
     * 存在即说明设备运行小米系 ROM，其多窗口策略层会改写裸 AOSP
     * freeform 参数（小窗接管/全屏回退）——应优先走 MIUI 加速通路。
     */
    fun hasMiuiFramework(): Boolean = runCatching {
        Class.forName("android.util.MiuiMultiWindowUtils", false, this.javaClass.classLoader) != null
    }.getOrDefault(false)

    /**
     * 豁免后再次探测 MIUI 类（未豁免时 Class.forName 可能被隐藏 API
     * 限制拦截——严格说 forName 查询 boot classpath 类不受限，但部分
     * ROM 行为特殊，双保险）。
     */
    fun probeMiuiClasses(): Boolean {
        HiddenApi.lift()
        return hasMiuiFramework()
    }

    /**
     * MIUI 加速：拿到「带 MIUI 私有 extras」的 freeform ActivityOptions。
     *
     * 通路一（首选）：`com.miui.launcher.utils.ActivityUtilsCompat
     * .makeFreeformActivityOptions(Context, String pkg)`——MIUI 自家
     * 桌面长按图标"小窗"按钮启动应用的方法（开源模块 MaxFreeForm 的
     * AddFreeformShortcut 即 hook 此方法构造启动参数）。它在部分版本
     * 位于 boot classpath（com.miui.launcher.utils 包），可被本应用
     * 反射调用；不在时静默跳过。
     *
     * 通路二（备用）：枚举 `android.util.MiuiMultiWindowUtils` 的静态
     * 方法，寻找返回 ActivityOptions、形参为 (Context, String, ...) 的
     * 工厂方法逐个尝试（不同 MIUI 版本签名有差异，按名字与签名模糊
     * 匹配，全部失败返回 null）。
     *
     * 拿到 options 后统一覆盖：`setLaunchBounds(桌面级大窗口)` +
     * `setLaunchWindowingMode(5)`——MIUI 的 extras 负责让系统策略层
     * 放行自由窗口，边界/模式由我们决定（桌面级大窗口，而非小窗）。
     *
     * 返回 null = MIUI 加速不可用（走 AOSP freeform 通路）。
     */
    fun miuiBoostedOptions(context: Context, pkg: String, bounds: Rect): android.os.Bundle? {
        HiddenApi.lift()
        val ctx = context.applicationContext
        var options: ActivityOptions? = null

        // 通路一：ActivityUtilsCompat.makeFreeformActivityOptions(Context, String)
        runCatching {
            val clz = Class.forName("com.miui.launcher.utils.ActivityUtilsCompat")
            val m = clz.getMethod("makeFreeformActivityOptions", Context::class.java, String::class.java)
            @Suppress("UNUSED_VARIABLE")
            val raw = m.invoke(null, ctx, pkg)
            if (raw is ActivityOptions) options = raw
        }.onFailure { Log.i(TAG, "MIUI boost: ActivityUtilsCompat 不可用（${it.message}）") }

        // 通路二：MiuiMultiWindowUtils 的 ActivityOptions 工厂方法模糊匹配
        //（不同 MIUI 版本签名有差异：只尝试 (Context, String[, Int|Boolean]) 组合；
        //  带其他类型参数的方法无法安全构造实参，跳过）
        if (options == null) {
            runCatching {
                val clz = Class.forName("android.util.MiuiMultiWindowUtils")
                clz.declaredMethods
                    .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .filter { it.returnType == ActivityOptions::class.java }
                    .filter { m -> m.parameterTypes.firstOrNull() == Context::class.java }
                    .filter { m -> m.parameterTypes.drop(1).all { p ->
                        p == String::class.java ||
                            p == Int::class.javaPrimitiveType ||
                            p == Boolean::class.javaPrimitiveType
                    } }
                    .sortedBy { it.parameterTypes.size }
                    .forEach { m ->
                        if (options != null) return@forEach
                        runCatching {
                            m.isAccessible = true
                            val args: Array<Any?> = m.parameterTypes.map { t ->
                                when (t) {
                                    Context::class.java -> ctx
                                    String::class.java -> pkg
                                    Int::class.javaPrimitiveType -> 0
                                    Boolean::class.javaPrimitiveType -> false
                                    else -> null
                                }
                            }.toTypedArray()
                            val raw = m.invoke(null, *args)
                            if (raw is ActivityOptions) {
                                Log.i(TAG, "MIUI boost: ${clz.simpleName}.${m.name} 命中")
                                options = raw
                            }
                        }
                    }
            }.onFailure { Log.i(TAG, "MIUI boost: MiuiMultiWindowUtils 不可用（${it.message}）") }
        }

        val opts = options ?: return null
        // 统一覆盖为桌面级大窗口 + freeform 模式
        runCatching { opts.setLaunchBounds(bounds) }
        runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(opts, WINDOWING_MODE_FREEFORM)
        }
        return runCatching { opts.toBundle() }.getOrNull()
    }

    // ============================================================
    // 能力保障（ensureAvailable / warmup）
    // ============================================================

    /**
     * 确保"设备支持自由窗口"，返回当前是否可用。
     *
     * v2.24.0：已授予 WRITE_SECURE_SETTINGS 时无条件写入**全部四个**
     * 开关（每进程一次，幂等）：
     * - enable_freeform_support（启用自由窗口）
     * - force_resizable_activities + force_resizable（强制可调整大小）
     * - enable_non_resizable_multi_window（允许不可调整大小的多窗口）
     * - hidden_api_policy=1（停用隐藏 API 检测，解锁 MIUI 反射）
     */
    fun ensureAvailable(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        cachedAvailable?.let { cached ->
            if (now - cachedAt < CACHE_TTL_MS) return cached
        }
        val ctx = context.applicationContext
        HiddenApi.lift()
        val secure = hasSecureSettings(ctx)
        if (secure && settingsWritten.compareAndSet(false, true)) {
            val ok = writeFreeformSettings(ctx)
            Log.i(
                TAG,
                "WRITE_SECURE_SETTINGS 已授予：桌面窗口开关写入" +
                    if (ok) "成功（四键全量）" else "失败（部分键被 ROM 拒绝，详见 warn 日志）"
            )
        }
        val available = probe(ctx)
        cachedAvailable = available
        cachedAt = now
        return available
    }

    /**
     * 用已授予的 WRITE_SECURE_SETTINGS 写开自由窗口相关全部设置（幂等）。
     * v2.24.0：键集合从 2 个扩到 4 个（+enable_non_resizable_multi_window、
     * +hidden_api_policy），全部写 global 表（WMS/运行时均从 global 读取）。
     */
    private fun writeFreeformSettings(context: Context): Boolean {
        val cr = context.contentResolver
        var ok = true
        fun put(key: String, value: Int, critical: Boolean = true) {
            runCatching { Settings.Global.putInt(cr, key, value) }.onFailure {
                if (critical) ok = false
                Log.w(TAG, "写 $key 失败：${it.message}")
            }
        }
        put(SETTING_ENABLE_FREEFORM, 1)
        put(SETTING_FORCE_RESIZABLE, 1)
        SETTING_FORCE_RESIZABLE_ALIASES.forEach { put(it, 1, critical = false) }
        put(SETTING_NON_RESIZABLE_MW, 1, critical = false)
        // hidden_api_policy：官方逃生口（developer.android.com 记载），
        // 个别 ROM 可能拒绝 —— 失败不阻断主流程（VMRuntime 通道兜底）
        put(SETTING_HIDDEN_API_POLICY, 1, critical = false)
        runCatching {
            val ff = Settings.Global.getInt(cr, SETTING_ENABLE_FREEFORM, 0)
            val fr = Settings.Global.getInt(cr, SETTING_FORCE_RESIZABLE, 0)
            val nm = Settings.Global.getInt(cr, SETTING_NON_RESIZABLE_MW, 0)
            Log.i(TAG, "开关回读：freeform=$ff force_resizable=$fr non_resizable_mw=$nm")
        }
        return ok
    }

    /** 失效能力缓存（授权/设置变更后调用，立即重新探测） */
    fun invalidateCache() {
        cachedAvailable = null
        cachedAt = 0L
    }

    /**
     * 进程启动时后台预热（IO 线程）：
     * - HiddenApi 豁免先行（后续一切反射的前提）；
     * - 已授予 WRITE_SECURE_SETTINGS → ensureAvailable 里已全量写入；
     * - 未授予但设备有 Root → 后台静默 su 写同样的开关。
     */
    fun warmup(context: Context) {
        val ctx = context.applicationContext
        HiddenApi.lift()
        ensureAvailable(ctx)
        if (!hasSecureSettings(ctx) && !rootAttempted.getAndSet(true)) {
            val cmd = buildString {
                append("settings put global $SETTING_ENABLE_FREEFORM 1")
                append("; settings put global $SETTING_FORCE_RESIZABLE 1")
                SETTING_FORCE_RESIZABLE_ALIASES.forEach { append("; settings put global $it 1") }
                append("; settings put global $SETTING_NON_RESIZABLE_MW 1")
                append("; settings put global $SETTING_HIDDEN_API_POLICY 1")
            }
            if (runSuCommands(cmd)) {
                Log.i(TAG, "Root：桌面窗口全部开关已写入（五键）")
                invalidateCache()
            } else {
                Log.i(TAG, "Root 不可用或被拒绝（非 Root 设备的正常路径，走 ADB / 开发者选项引导）")
            }
        }
    }

    // ============================================================
    // v2.24.0：既存任务检测（卡全屏修复 + 启动结果观测）
    // ============================================================

    /**
     * 读取目标应用最近任务的窗口模式。
     *
     * 只有默认 Launcher 能通过 getRecentTasks 看到其他应用的任务
     * （Android 5.1+ 对三方应用的限制，Launcher 豁免）；RecentTaskInfo
     * 继承 TaskInfo，windowingMode 为隐藏字段——HiddenApi 豁免后
     * 直接反射读取。
     *
     * 返回 null：非默认 Launcher / 未找到任务 / 读取失败。
     */
    fun recentTaskWindowingMode(context: Context, pkg: String): Int? {
        if (!isDefaultLauncher(context)) return null
        HiddenApi.lift()
        return runCatching {
            val am = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
            @Suppress("DEPRECATION")
            val recents = am.getRecentTasks(16, ActivityManager.RECENT_WITH_EXCLUDED) ?: emptyList()
            val info = recents.firstOrNull { it.baseIntent?.component?.packageName == pkg }
                ?: return null
            // RecentTaskInfo extends TaskInfo；windowingMode 为隐藏 int 字段
            val field = runCatching {
                info.javaClass.getField("windowingMode")
            }.getOrNull() ?: runCatching {
                // 部分 ROM 上 getDeclaredField 需要沿父类找
                var c: Class<*>? = info.javaClass
                while (c != null) {
                    val f = runCatching { c.getDeclaredField("windowingMode") }.getOrNull()
                    if (f != null) { f.isAccessible = true; return@runCatching f }
                    c = c.superclass
                }
                null
            }.getOrNull() ?: return null
            runCatching { field.get(info) as? Int }.getOrNull()
        }.getOrNull()
    }

    /**
     * 是否应给本次启动补 FLAG_ACTIVITY_CLEAR_TASK（卡全屏修复）。
     *
     * 条件（自动模式）：本应用是默认 Launcher 且目标应用存在**全屏**
     * 既存任务（windowingMode == 1）。此时普通启动只会把全屏任务带到
     * 前台、窗口模式永不重新应用——这正是"只全屏显示，没任何窗口"
     * 的直接成因；CLEAR_TASK 强制重建任务，launch options 里的
     * freeform 模式随之生效。
     */
    fun shouldClearStuckTask(context: Context, pkg: String): Boolean {
        if (isAlwaysNewTask(context)) return true
        if (!isAutoFixStuckTask(context)) return false
        val mode = recentTaskWindowingMode(context, pkg) ?: return false
        return mode == WINDOWING_MODE_FULLSCREEN
    }

    /** 记录一次启动报告（诊断页展示） */
    fun reportLaunch(report: LaunchReport) {
        _lastLaunch.value = report
    }

    // ============================================================
    // v2.24.0：分 ROM 开发者选项指引
    // ============================================================

    /**
     * 分 ROM 的自由窗口开启指引（决策弹窗/诊断页展示）。
     * 国产 ROM 开发者选项里相关开关的命名各不相同，统一列出。
     */
    data class RomGuidance(
        /** ROM 识别名（空 = 未识别，显示通用指引） */
        val romName: String,
        /** 开发者选项里需要打开的开关名列表 */
        val toggles: List<String>,
        /** 额外提示 */
        val extra: String
    )

    fun romGuidance(): RomGuidance {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        val miui = m.contains("xiaomi") || m.contains("redmi") || b.contains("xiaomi") ||
            b.contains("redmi") || hasMiuiFramework()
        return when {
            miui -> RomGuidance(
                romName = "MIUI / HyperOS（小米系）",
                toggles = listOf(
                    "开发者选项 → 强制将活动设为可调整大小",
                    "开发者选项 → 启用自由窗口（如有）"
                ),
                extra = "MIUI/HyperOS 的小窗与自由窗口共用系统 freeform 通道。若应用被打开成" +
                    "系统小窗，可拖动小窗底部横条/角落把手放大至大窗；新版 HyperOS 支持" +
                    "小窗自由拉伸。引擎也会尝试通过 MIUI 专有通道直接以大窗启动。"
            )
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ||
                b.contains("oppo") || b.contains("oneplus") || b.contains("realme") -> RomGuidance(
                romName = "ColorOS / OxygenOS / realme UI",
                toggles = listOf(
                    "设置 → 系统 → 开发者选项 → 强制将活动设为可调整大小",
                    "设置 → 系统 → 开发者选项 → 启用可自由调整的窗口"
                ),
                extra = "ColorOS 系需要同时打开开发者选项里的这两个开关，" +
                    "手机应用才能以自由窗口（电脑窗口）形态启动。"
            )
            m.contains("huawei") || m.contains("honor") || b.contains("huawei") ||
                b.contains("honor") -> RomGuidance(
                romName = "EMUI / MagicOS（华为系）",
                toggles = listOf(
                    "设置 → 系统和更新 → 开发人员选项 → 强制将活动设为可调整大小"
                ),
                extra = "华为系部分版本不支持第三方自由窗口，若无效请使用引擎的" +
                    "ADB 授权通道（写入 enable_freeform_support）。"
            )
            m.contains("vivo") || m.contains("iqoo") || b.contains("vivo") ||
                b.contains("iqoo") -> RomGuidance(
                romName = "OriginOS / Funtouch（vivo 系）",
                toggles = listOf(
                    "设置 → 系统管理 → 开发者选项 → 强制将活动设为可调整大小"
                ),
                extra = "vivo 系小窗基于 freeform 实现，窗口可手动放大；" +
                    "引擎会尝试直接以大窗启动。"
            )
            m.contains("samsung") || b.contains("samsung") -> RomGuidance(
                romName = "One UI（三星）",
                toggles = listOf(
                  "设置 → 开发者选项 → 强制活动可调整大小（Resize activities）"
                ),
                extra = "三星设备建议连接 DeX 获得完整桌面体验；单机自由窗口" +
                    "亦可用本引擎开启。"
            )
            else -> RomGuidance(
                romName = "",
                toggles = listOf(
                    "设置 → 系统 → 开发者选项 → 强制将活动设为可调整大小",
                    "设置 → 系统 → 开发者选项 → 启用自由窗口 / 自由形式窗口（如有）"
                ),
                extra = "未识别的设备类型，按通用 AOSP 指引操作；或直接使用 ADB 授权通道。"
            )
        }
    }

    // ============================================================
    // v2.24.0：诊断快照
    // ============================================================

    /**
     * 生成引擎诊断快照（多行文本，设置页一键复制回报）。
     * 目的：终结"盲猜设备状态"——用户把这段文本发回来，引擎的全部
     * 事实一目了然。
     */
    fun snapshot(context: Context): String {
        val ctx = context.applicationContext
        HiddenApi.lift()
        val sb = StringBuilder()
        fun line(s: String) = sb.append(s).append('\n')

        line("===== AnWind 桌面窗口引擎 诊断 v2.24.0 =====")
        line("时间: ${System.currentTimeMillis()}")
        line("设备: ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
        line("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("制造商: ${Build.MANUFACTURER}")

        line("")
        line("[Launcher 身份]")
        line("- 本应用为默认 Launcher: ${isDefaultLauncher(ctx)}" +
            "  (决定能否检测其他应用任务的窗口模式)")

        line("")
        line("[权限]")
        line("- 悬浮窗 SYSTEM_ALERT_WINDOW: ${if (hasOverlayPermission(ctx)) "已授予" else "未授予"}")
        line("- WRITE_SECURE_SETTINGS: ${if (hasSecureSettings(ctx)) "已授予" else "未授予（ADB 通道未开启）"}")

        line("")
        line("[系统开关读回]")
        fun read(key: String) = runCatching {
            Settings.Global.getInt(ctx.contentResolver, key, -1)
        }.getOrDefault(-1)
        line("- enable_freeform_support = ${read(SETTING_ENABLE_FREEFORM)}  (1=自由窗口已开启)")
        line("- force_resizable_activities = ${read(SETTING_FORCE_RESIZABLE)}  (1=强制可调整大小)")
        line("- force_resizable = ${read("force_resizable")}")
        line("- enable_non_resizable_multi_window = ${read(SETTING_NON_RESIZABLE_MW)}  (1=允许不可调整大小多窗口)")
        line("- hidden_api_policy = ${read(SETTING_HIDDEN_API_POLICY)}  (1=隐藏API限制已停用)")
        line("- 系统特性 freeform_window_management = ${hasFreeformFeature(ctx)}")
        line("- 自由窗口能力探测(ensureAvailable) = ${probe(ctx)}")

        line("")
        line("[隐藏 API 豁免]")
        line("- VMRuntime.setHiddenApiExemptions: ${if (HiddenApi.isLifted) "已生效" else "失败/未尝试"}")

        line("")
        line("[MIUI / HyperOS 探测]")
        val miui = hasMiuiFramework()
        line("- android.util.MiuiMultiWindowUtils 存在: $miui")
        if (miui) {
            line("- MIUI 加速通道可用性: 见下方『最近一次启动』的策略字段")
        }

        line("")
        line("[引擎设置]")
        line("- 卡全屏自动修复: ${isAutoFixStuckTask(ctx)}")
        line("- 总是以全新任务启动: ${isAlwaysNewTask(ctx)}")

        line("")
        line("[最近一次启动]")
        val lp = _lastLaunch.value
        if (lp == null) {
            line("- （本进程尚未从桌面启动过手机应用）")
        } else {
            line("- 应用: ${lp.pkg} (${lp.label})")
            line("- 策略: ${lp.strategy}")
            line("- 桌面级边界: ${lp.bounds}")
            line("- 补 CLEAR_TASK(卡全屏修复): ${lp.clearTask}")
            line("- 启动后任务窗口模式: " + when {
                !lp.observed -> "无法观测（非默认 Launcher）"
                lp.observedWindowing == WINDOWING_MODE_FREEFORM -> "5 (freeform ✓ 桌面窗口已生效)"
                lp.observedWindowing == WINDOWING_MODE_FULLSCREEN -> "1 (fullscreen ✗ 仍被系统全屏化)"
                else -> "${lp.observedWindowing} (?)"
            })
        }

        line("")
        line("[ROM 指引]")
        val g = romGuidance()
        line("- 识别: ${g.romName.ifBlank { "未识别（通用 AOSP）" }}")
        g.toggles.forEach { line("- 需开启: $it") }
        line("===== 诊断结束 =====")
        return sb.toString()
    }

    // ============================================================
    // 用户决策弹窗（自由窗口不可用时的启动请求）
    // ============================================================

    /** 请求弹窗决策：自由窗口不可用时，暂存本次启动请求并通知桌面层弹窗 */
    fun requestDecision(pkg: String, activity: String, label: String) {
        _pendingLaunch.value = PendingLaunch(pkg, activity, label)
    }

    /** 关闭决策弹窗（取消 / 已决策） */
    fun cancelPendingLaunch() {
        _pendingLaunch.value = null
    }

    /** v2.23.3：请求展示授权建议（freeform 可用但设置无法自动写入时） */
    fun requestSetupHint(pkg: String, activity: String, label: String) {
        _setupHint.value = SetupHint(pkg, activity, label)
    }

    /** v2.23.3：关闭授权建议弹窗 */
    fun cancelSetupHint() {
        _setupHint.value = null
    }

    // ============================================================
    // Root 辅助
    // ============================================================

    /**
     * 执行 `su -c <cmd>`；返回是否成功（exit 0）。
     * 4 秒看门狗；一切异常吞掉返回 false。
     */
    private fun runSuCommands(cmd: String): Boolean = runCatching {
        val suExists = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su"
        ).any { File(it).exists() }
        if (!suExists) return@runCatching false

        val process = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val finished = AtomicBoolean(false)
        Thread {
            try {
                Thread.sleep(4000L)
                if (!finished.get()) process.destroy()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
        val exit = runCatching { process.waitFor() }.getOrDefault(-1)
        finished.set(true)
        exit == 0
    }.getOrDefault(false)
}
