package com.anwind.core.desktop

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.23.4：自由窗口（Freeform）能力检测 / 自动开启 / Root 特性注入 / 用户确认闭环。
 *
 * ## v2.23.3 遗留的三处根因（本版全部实锤修复）
 *
 * 1. **Bundle 隐藏键名写错**：v2.23.2/3 用的是 `"android:activity.windowingMode"`
 *    （冒号），AOSP `ActivityOptions.KEY_LAUNCH_WINDOWING_MODE` 的真实字面值是
 *    **`"android.activity.windowingMode"`**（点号，见 frameworks/base
 *    core/java/android/app/ActivityOptions.java）。反射 `setLaunchWindowingMode`
 *    在 Android 9+（targetSdk 28）被隐藏 API 限制拦截时，这条"终极兜底"通道
 *    实际写入的是一个系统根本不认识的键 —— **窗口化请求从未送达 system_server**。
 *    这可以完整解释"授权成功、开关已开、重启多次，应用依旧全屏"。
 *    本版修正键名，并同时写 API 24~27 时代的 `"android.activity.launchStackId"`
 *    （TaskLaunchParamsModifier 在各版本只读自己认识的键，多余键被忽略，无副作用）。
 *
 * 2. **系统特性字符串写错**：v2.23.3 用 `"android.hardware.freeform_window_management"`，
 *    AOSP `PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT` 的真实字面值是
 *    **`"android.software.freeform_window_management"`**（software 不是 hardware）。
 *    原检测在所有设备上恒为 false，诊断面板"未声明"不可信。
 *
 * 3. **启动后验证通道无效**：`getRunningTasks`/`getRecentTasks` 对三方应用只返回
 *    自己的任务（AOSP `isGetTasksAllowed` 只放行系统 Recents 组件与持有
 *    REAL_GET_TASKS 签名权限者，**默认 Launcher 也不放行**），因此 v2.23.3 的
 *    "回读目标任务 windowingMode"永远返回 null → 走"不可验证"分支 → 一次次给出
 *    错误的"请重启"建议。本版：Root 设备改用 `su -c dumpsys activity` 真实回读；
 *    无 Root 时改为**一次性用户确认**（"应用是否已以窗口形式打开？"），不再冒充
 *    能自动验证。
 *
 * ## AOSP 生效链路（已逐行核对）
 *
 * - `mSupportsFreeformWindowManagement = hasSystemFeature(FEATURE_FREEFORM_WINDOW_MANAGEMENT)
 *   || enable_freeform_support != 0`（ATMS.retrieveSettings，**仅开机读取一次**）；
 * - 不可用时窗口化请求被**静默丢弃**（TaskLaunchParamsModifier 不应用 freeform，
 *   应用照常全屏，不抛异常）；
 * - 单个应用门槛：`!isResizeable() && !supportsNonResizableMultiWindow()` 会挡住
 *   声明了 `resizeableActivity=false` 的应用 —— 两个真实存在的全局设置可绕过：
 *   **`force_resizable_activities=1`**（使 isResizeable 恒真）与
 *   **`enable_non_resizable_multi_window=1`**（允许不可调整应用进多窗口，信箱模式）。
 *   本版把这两个键连同 `enable_freeform_support` 一起写入（v2.23.3 漏了第三个）。
 *
 * ## Root 特性注入（对付"ROM 忽略开关"的终极手段）
 *
 * 部分 OEM ROM 屏蔽了 `enable_freeform_support` 的开机读取路径（开关=1、重启
 * 多次仍全屏）。唯一绕过方法是把特性声明写进系统分区
 * `/system/etc/permissions/anwind_freeform.xml`，PackageManager 开机扫描后
 * `hasSystemFeature` 为真，retrieveSettings 直接成立。该操作需要 Root 且修改
 * 系统分区（dm-verity 设备可能被拒），因此**只由用户在解决方案弹窗里主动触发**
 * （[tryRootFeatureInjection]），成功后重启手机生效。
 *
 * ## v2.23.5："桌面窗口"形态
 *
 * 用户明确要求：手机应用要像**电脑程序窗口**一样摆在桌面上（四周留边、
 * 任务栏可见），不是手机系统那种小尺寸"小窗"。窗口形态（初始边界/
 * 任务栏避让/级联摆放）由 [AndroidApps.freeformOptions] 负责；本类只负责
 * 能力链路（检测/写入/验证）。厂商"小窗"引导已按用户要求移除。
 *
 * ## v2.23.6：“手机小窗”根因 —— force_resizable_activities
 *
 * 用户实测：窗口化已生效（应用确实以窗口打开），但窗口是**手机比例的小窗**
 * 而非桌面大窗口。AOSP 实锤（ActivityRecord.canForceResizeNonResizable）：
 * 绝大多数手机应用声明 resizeableActivity=false / 固定方向，在自由窗口中
 * 若系统未开 force_resizable_activities，会被**信箱化（letterbox）成手机
 * 比例小窗**而不是填满窗口边界；且该开关与 enable_freeform_support 一样
 * **仅在开机时读取一次**（ATMS.retrieveSettings，SettingsObserver 不监听）。
 * 因此“桌面大窗口”需要三个全局设置齐开：enable_freeform_support（能窗口化）
 * + force_resizable_activities（不可调整应用填满窗口）+
 * enable_non_resizable_multi_window（兑底放行）。本版：
 * [ensureAvailable]/[warmup] 在任一键缺失时即写入；新增 [REASON_PHONE_SHAPED]
 * 分流弹窗与 [manualAdbCommands] 手动路径；Root 新增 [findRootTaskInfo]/
 * [resizeRootTask]（am stack resize 把被 ROM 压小的窗口拉回桌面边界）。
 *
 * ## 线程模型
 *
 * - [ensureAvailable] / [exemptHiddenApis]：毫秒级，主线程可调；
 * - [warmup] / [suAvailable] / [verifyFreeformViaRoot] / [tryRootFeatureInjection]：
 *   含 su 子进程与看门狗，必须后台线程调用。
 */
object FreeformCompat {

    private const val TAG = "AnWind.FreeformCompat"

    /** android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM（隐藏常量，各版本数值恒为 5） */
    const val WINDOWING_MODE_FREEFORM = 5

    /**
     * ActivityOptions Bundle 隐藏键（AOSP KEY_LAUNCH_WINDOWING_MODE，API 28+）。
     * ⚠ v2.23.3 曾错写成 "android:activity.windowingMode"（冒号）—— 系统不认识，
     * 整条兜底通道失效。正确字面值为点号，setLaunchWindowingMode 内部写的就是它。
     */
    const val KEY_LAUNCH_WINDOWING_MODE = "android.activity.windowingMode"

    /**
     * API 24~27 时代的等价隐藏键（AOSP KEY_LAUNCH_STACK_ID，值为
     * FREEFORM_WORKSPACE_STACK_ID=5）。与新键同时写入，各版本只读自己认识的那个。
     */
    const val KEY_LAUNCH_STACK_ID = "android.activity.launchStackId"

    /** PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT 的真实字面值（software！） */
    private const val FEATURE_FREEFORM = "android.software.freeform_window_management"

    /** 开发者选项「启用自由窗口」对应的全局设置键（AOSP：仅开机时读取一次） */
    private const val SETTING_ENABLE_FREEFORM = "enable_freeform_support"

    /** AOSP 真实设置（DEVELOPMENT_FORCE_RESIZABLE_ACTIVITIES）：强制所有 Activity 可调整大小 */
    private const val SETTING_FORCE_RESIZABLE = "force_resizable_activities"

    /** AOSP 真实设置（DEVELOPMENT_ENABLE_NON_RESIZABLE_MULTI_WINDOW）：允许不可调整应用进多窗口（信箱模式） */
    private const val SETTING_NON_RESIZABLE_MW = "enable_non_resizable_multi_window"

    /** 需要写开的全部全局设置（拿到 WRITE_SECURE_SETTINGS / Root 后一并写入） */
    private val ALL_SETTINGS = arrayOf(
        SETTING_ENABLE_FREEFORM,
        SETTING_FORCE_RESIZABLE,
        SETTING_NON_RESIZABLE_MW
    )

    // ============================================================
    // 决策原因（弹窗按此分流文案）
    // ============================================================

    /** 未开启且无自动开启手段 → ADB 授权 / 开发者选项 / Root 引导 */
    const val REASON_NO_PERMISSION = 0

    /** 开关是本次开机内写入的（AOSP：仅开机读取）→ 重启引导（仅提示，不阻断启动） */
    const val REASON_NEEDS_REBOOT = 1

    /** 确认/验证窗口化未生效 → 解决方案（Root 注入 / 换应用测试与排查） */
    const val REASON_SOLUTIONS = 2

    /** 一次性用户确认：应用是否真的以窗口形式打开了（无 Root 时唯一的"验证"手段） */
    const val REASON_USER_CONFIRM = 3

    /**
     * v2.23.6：窗口化已生效但 force_resizable_activities=0 ——
     * 手机应用被系统信箱化成**手机比例小窗**，引导开启「强制应用可调整大小」
     * （开启 + 重启后应用填满桌面大窗口）。
     */
    const val REASON_PHONE_SHAPED = 4

    /** 自由窗口不可用/未生效时的待决策启动请求（DesktopEnvironment 收集后弹窗） */
    data class PendingLaunch(
        val pkg: String,
        /** 启动 Activity 全类名；空 = 走 getLaunchIntentForPackage 兜底 */
        val activity: String,
        val label: String,
        /** 分流文案的原因码（见 REASON_* 常量） */
        val reason: Int
    )

    private val _pendingLaunch = MutableStateFlow<PendingLaunch?>(null)

    /** 当前待用户决策的启动请求；null = 无弹窗 */
    val pendingLaunch: StateFlow<PendingLaunch?> = _pendingLaunch

    /** 本次进程内"不再询问"（弹窗里点过后，之后直接全屏启动，不再打扰） */
    @Volatile
    var suppressDecision: Boolean = false

    /** 本次进程内已给出过"重启生效"提示（每进程至多一次，避免唠叨） */
    @Volatile
    private var rebootAdviceShown = false

    /** 能力缓存（30 秒 TTL，避免每次点图标都读 SettingsProvider） */
    @Volatile
    private var cachedAvailable: Boolean? = null

    @Volatile
    private var cachedAt: Long = 0L

    private const val CACHE_TTL_MS = 30_000L

    /** Root 探测只做一次/进程（避免反复弹 su 授权框） */
    @Volatile
    private var rootChecked = false

    @Volatile
    private var rootOk = false

    /** 隐藏 API 豁免（VMRuntime.setHiddenApiExemptions）只做一次/进程 */
    @Volatile
    private var hiddenApiExempted = false

    // ============================================================
    // 持久化状态（SharedPreferences）
    // ============================================================

    private const val PREFS = "freeform_compat"
    private const val KEY_WRITE_BOOT = "write_boot_count"
    private const val KEY_WRITE_UPTIME = "write_uptime"
    private const val KEY_WORKING_BOOT = "working_boot_count"
    /** 复用 v2.23.3 的 key：一次性用户确认是否已问过（安装级） */
    private const val KEY_CONFIRM_ASKED = "first_hint_shown"
    /** 用户确认"仍全屏"（或 Root dumpsys 实锤全屏）→ 本 ROM 忽略自由窗口开关 */
    private const val KEY_ROM_IGNORES_SWITCH = "rom_ignores_switch"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** boot_count 进程内缓存（同一次开机内不会变化） */
    @Volatile
    private var cachedBoot: Int = Int.MIN_VALUE

    private fun currentBoot(context: Context): Int {
        val cached = cachedBoot
        if (cached != Int.MIN_VALUE) return cached
        val boot = runCatching {
            @Suppress("DEPRECATION")
            Settings.Global.getInt(
                context.applicationContext.contentResolver,
                Settings.Global.BOOT_COUNT,
                -1
            )
        }.getOrDefault(-1)
        cachedBoot = boot
        return boot
    }

    /**
     * 本次开机内我们写过开关（且尚未重启）。
     *
     * AOSP 的 enable_freeform_support 只在开机时读取，因此此时窗口化请求
     * 一定被系统丢弃（个别 ROM 自行加了热加载除外）。判断依据：
     * - 首选 boot_count（公开全局设置，API 24+）：写开关时的 boot_count
     *   与当前一致 = 未重启；
     * - 读不到 boot_count 的 ROM 退回 uptimeMillis 单调性。
     */
    fun pendingRebootSinceOurWrite(context: Context): Boolean {
        val p = prefs(context)
        val wroteBoot = p.getInt(KEY_WRITE_BOOT, -1)
        if (wroteBoot < 0) return false
        val boot = currentBoot(context)
        if (boot >= 0) return boot == wroteBoot
        val wroteUptime = p.getLong(KEY_WRITE_UPTIME, -1L)
        return wroteUptime >= 0L && wroteUptime <= SystemClock.uptimeMillis()
    }

    /** 记录"刚刚写开开关"（供 ADB 授权写入与 Root 写入两条路径调用） */
    private fun recordWrite(context: Context) {
        val ctx = context.applicationContext
        prefs(ctx).edit()
            .putInt(KEY_WRITE_BOOT, currentBoot(ctx))
            .putLong(KEY_WRITE_UPTIME, SystemClock.uptimeMillis())
            .apply()
    }

    /** 历史上是否确认过"窗口化真实生效"（用户确认或 Root 验证，任一） */
    fun everVerifiedWorking(context: Context): Boolean =
        prefs(context).getInt(KEY_WORKING_BOOT, -1) >= 0

    /** 一次性用户确认是否已问过（安装级；卸载重装会重置，重新确认） */
    fun userConfirmAlreadyAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_ASKED, false)

    /** 标记一次性用户确认已问过 */
    fun markUserConfirmAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_ASKED, true).apply()
    }

    /** 用户/Root 已实锤本 ROM 忽略自由窗口开关（此后静默，仅解决方案弹窗可再触发） */
    fun romIgnoresSwitch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROM_IGNORES_SWITCH, false)

    /** 标记本 ROM 忽略开关 */
    fun markRomIgnoresSwitch(context: Context) {
        prefs(context).edit().putBoolean(KEY_ROM_IGNORES_SWITCH, true).apply()
    }

    /** 推荐用户执行的 ADB 一次性授权命令（按实际包名生成，防止 fork 改包名后失效） */
    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    /**
     * v2.23.6：不想授权给 AnWind 的手动替代命令（电脑逐行执行，效果与授权后
     * 自动写入相同，同样需重启一次生效）。不依赖 pm grant，卸载重装不丢。
     */
    fun manualAdbCommands(): String = ALL_SETTINGS.joinToString("\n") {
        "adb shell settings put global $it 1"
    }

    // ============================================================
    // 能力检测
    // ============================================================

    /** 设备是否原生声明支持自由窗口（DeX / 部分国产 ROM / 模拟器等） */
    private fun hasFreeformFeature(context: Context): Boolean = runCatching {
        context.packageManager.hasSystemFeature(FEATURE_FREEFORM)
    }.getOrDefault(false)

    /** 全局「启用自由窗口」开关是否已打开（开发者选项或本类写入） */
    private fun isFreeformSettingOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING_ENABLE_FREEFORM, 0) != 0
    }.getOrDefault(false)

    /** 读一个全局设置（读 Settings.Global 无需任何权限；失败当 0） */
    private fun readGlobalInt(context: Context, key: String): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, key, 0)
    }.getOrDefault(0)

    /**
     * v2.23.6：「强制应用可调整大小」是否已开启 —— **桌面大窗口的关键开关**。
     * 未开启时，声明 resizeableActivity=false / 固定方向的手机应用在自由窗口
     * 中会被系统信箱化成手机比例小窗（AOSP ActivityRecord.
     * canForceResizeNonResizable）。读取无需权限。
     */
    fun isForceResizableOn(context: Context): Boolean =
        readGlobalInt(context, SETTING_FORCE_RESIZABLE) != 0

    /** 「允许不可调整应用进多窗口」是否已开启（兜底放行，读取无需权限） */
    private fun isNonResizableMwOn(context: Context): Boolean =
        readGlobalInt(context, SETTING_NON_RESIZABLE_MW) != 0

    /** 当前设备自由窗口能力探测（系统特性声明 / 全局开关任一成立即可用） */
    private fun probe(context: Context): Boolean =
        hasFreeformFeature(context) || isFreeformSettingOn(context)

    /** WRITE_SECURE_SETTINGS 是否已通过 ADB / Shizuku 授予本应用（诊断/弹窗用） */
    fun hasSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    // ============================================================
    // 能力保障（ensureAvailable / warmup）
    // ============================================================

    /**
     * 确保"设备支持自由窗口"，返回当前是否可用。
     *
     * - 命中缓存（30 秒内）直接返回；
     * - 未授予 WRITE_SECURE_SETTINGS 时只做检测；
     * - 已授予时同步写开三个全局设置（binder 调用，毫秒级）。
     *   v2.23.6：三个设置**任一缺失**即补写（此前仅"自由窗口不可用"才写
     *   —— 开关已开而 force_resizable_activities=0 的设备会永远停在手机
     *   比例小窗）；
     *   ⚠ 写入成功 ≠ 立即生效 —— 系统只在**下次开机**时读取该开关（见类注释），
     *   生效状态由 [pendingRebootSinceOurWrite] 与启动后跟进流程单独跟踪。
     */
    fun ensureAvailable(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        cachedAvailable?.let { cached ->
            if (now - cachedAt < CACHE_TTL_MS) return cached
        }
        val ctx = context.applicationContext
        var available = probe(ctx)
        if (hasSecureSettings(ctx) &&
            (!available || !isForceResizableOn(ctx) || !isNonResizableMwOn(ctx))
        ) {
            writeFreeformSettings(ctx)
            available = probe(ctx)
            Log.i(
                TAG,
                "WRITE_SECURE_SETTINGS 已授予：桌面窗口设置写入" +
                    if (available) "成功（重启手机后系统才会读取生效）" else "失败（走弹窗引导）"
            )
        }
        cachedAvailable = available
        cachedAt = now
        return available
    }

    /** 用已授予的 WRITE_SECURE_SETTINGS 写开自由窗口相关全局设置（幂等，记录写入时间） */
    private fun writeFreeformSettings(context: Context) {
        runCatching {
            ALL_SETTINGS.forEach { key ->
                runCatching { Settings.Global.putInt(context.contentResolver, key, 1) }
            }
            recordWrite(context)
        }.onFailure { Log.w(TAG, "写自由窗口设置失败：${it.message}") }
    }

    /** 失效能力缓存，下次 [ensureAvailable] 立即重新探测（决策弹窗关闭时调用） */
    fun invalidateCache() {
        cachedAvailable = null
        cachedAt = 0L
    }

    /**
     * 进程启动时后台预热：检测能力；任一桌面窗口设置缺失且设备有 Root 时，
     * 用 su 静默写开缺失的全局设置（Magisk / 模拟器等一次放行即成功）。
     * 必须在后台线程调用（su 子进程 + 看门狗）。
     */
    fun warmup(context: Context) {
        val ctx = context.applicationContext
        ensureAvailable(ctx)
        // v2.23.6：自由窗口可用但 force_resizable 等未开时也要写
        //（否则手机应用永远停在手机比例小窗）
        val missing = ALL_SETTINGS.filter { readGlobalInt(ctx, it) == 0 }
        if (missing.isEmpty()) return
        if (!suAvailable()) return
        val cmd = missing.joinToString("; ") { "settings put global $it 1" }
        if (runSu(cmd, 4000L)?.first == 0) {
            Log.i(TAG, "Root：桌面窗口设置已写入（重启手机后生效）")
            recordWrite(ctx)
            cachedAvailable = null
            cachedAt = 0L
        } else {
            Log.i(TAG, "Root 写入失败（被拒绝），走 ADB / 开发者选项引导")
        }
    }

    // ============================================================
    // 启动后跟进（Root 验证 / 用户确认 / 重启提示）
    // ============================================================

    /** 记录一次验证/确认结果：true = 窗口化真实生效；false = 仍全屏（ROM 忽略开关） */
    fun noteVerified(context: Context, freeform: Boolean) {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        if (freeform) {
            val boot = currentBoot(ctx)
            p.edit()
                .putBoolean(KEY_ROM_IGNORES_SWITCH, false)
                .apply()
            if (boot >= 0 && p.getInt(KEY_WORKING_BOOT, -1) != boot) {
                p.edit().putInt(KEY_WORKING_BOOT, boot).apply()
            }
        } else {
            p.edit().putBoolean(KEY_ROM_IGNORES_SWITCH, true).apply()
        }
    }

    /** 每进程至多一次的"重启生效"提示（开关是本次开机内写入时） */
    fun maybeShowRebootAdvice(context: Context, pkg: String, activity: String, label: String) {
        if (rebootAdviceShown) return
        if (suppressDecision) return
        if (pendingLaunch.value != null) return
        if (!pendingRebootSinceOurWrite(context)) return
        rebootAdviceShown = true
        requestDecision(pkg, activity, label, REASON_NEEDS_REBOOT)
    }

    /** 请求弹窗决策：暂存本次启动请求（含原因码）并通知桌面层弹窗 */
    fun requestDecision(pkg: String, activity: String, label: String, reason: Int) {
        _pendingLaunch.value = PendingLaunch(pkg, activity, label, reason)
    }

    /** 关闭决策弹窗（取消 / 已决策） */
    fun cancelPendingLaunch() {
        _pendingLaunch.value = null
    }

    // ============================================================
    // 实时诊断（决策弹窗展示）
    // ============================================================

    fun diagnosticLines(context: Context): List<String> {
        val ctx = context.applicationContext
        val lines = mutableListOf<String>()
        lines += "设备：${Build.MANUFACTURER} ${Build.MODEL}（Android ${Build.VERSION.RELEASE}）"
        lines += "系统 Freeform 特性：${if (hasFreeformFeature(ctx)) "已声明" else "未声明"}"
        lines += "自由窗口开关：${if (isFreeformSettingOn(ctx)) "已开启 (=1)" else "未开启 (=0)"}"
        lines += "强制应用可调整大小：${if (isForceResizableOn(ctx)) "已开启 (=1)" else "未开启 (=0)"}" +
            " ← 桌面大窗口关键（未开则手机应用被压成手机比例小窗）"
        lines += "ADB 授权：${if (hasSecureSettings(ctx)) "已授予" else "未授予"}"
        lines += "Root：${if (rootChecked && rootOk) "可用" else "未知/不可用"}"
        if (pendingRebootSinceOurWrite(ctx)) {
            lines += "开关写入：本次开机内写入，重启后系统才会读取"
        }
        if (romIgnoresSwitch(ctx)) {
            lines += "本 ROM：已确认忽略自由窗口开关"
        }
        if (everVerifiedWorking(ctx)) {
            lines += "窗口化验证：历史成功过"
        }
        return lines
    }

    // ============================================================
    // 隐藏 API 豁免（Android 9+ 反射放行）
    // ============================================================

    /**
     * 解除本进程的隐藏 API 访问限制（VMRuntime.setHiddenApiExemptions("L")，
     * 参考成熟项目 farmerbb/Taskbar 的同款做法）。Android 9 上该反射本身可能
     * 被拦（返回 false，无碍 —— Bundle 键才是主通道）；Android 10+ 均可成功。
     * 即使完全失败也不影响启动：Bundle 隐藏键写入不依赖反射。
     */
    fun exemptHiddenApis(): Boolean {
        if (hiddenApiExempted) return true
        hiddenApiExempted = runCatching {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vm.getDeclaredMethod("getRuntime")
            val setExemptions = vm.getDeclaredMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
            true
        }.getOrDefault(false)
        return hiddenApiExempted
    }

    // ============================================================
    // Root 辅助（后台线程调用）
    // ============================================================

    /**
     * 执行 `su -c <cmd>`，返回 (exit code, 合并输出)；无 su 二进制或超时返回 null。
     * 看门狗 destroy 防止 Magisk 授权弹窗等待时挂死调用线程。
     */
    private fun runSu(cmd: String, timeoutMs: Long): Pair<Int, String>? = runCatching {
        val suExists = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su"
        ).any { File(it).exists() }
        if (!suExists) return@runCatching null

        val process = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        val finished = AtomicBoolean(false)
        Thread {
            try {
                Thread.sleep(timeoutMs)
                if (!finished.get()) process.destroy()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
        val output = runCatching {
            process.inputStream.readBytes().toString(Charsets.UTF_8)
        }.getOrDefault("")
        val exit = runCatching { process.waitFor() }.getOrDefault(-1)
        finished.set(true)
        exit to output
    }.getOrDefault(null)

    /** 设备是否有可用 Root（结果进程内缓存；首次调用可能弹 su 授权框） */
    fun suAvailable(): Boolean {
        if (rootChecked) return rootOk
        rootOk = runSu("id", 4000L)
            ?.let { it.first == 0 && it.second.contains("uid=0") } == true
        rootChecked = true
        Log.i(TAG, "Root 探测：${if (rootOk) "可用" else "不可用"}")
        return rootOk
    }

    /**
     * Root 专属启动后验证：`dumpsys activity activities` 回读目标包任务的真实
     * windowingMode。返回 true（freeform）/ false（全屏）/ null（dumpsys 不可用
     * 或未找到目标任务）。dumpsys 的任务头行（`* Task{... mode=freeform ...}`）
     * 与 Activity 行（`ActivityRecord{... pkg/.Activity ...}`）在不同行，逐行
     * 跟踪最近的 Task 头即可关联。
     */
    fun verifyFreeformViaRoot(pkg: String): Boolean? {
        val out = runSu("dumpsys activity activities", 8000L)?.second ?: return null
        var sawTarget = false
        var sawFreeform = false
        var headerFreeform = false
        out.lineSequence().forEach { raw ->
            val t = raw.trim()
            if (t.startsWith("* Task{") || t.startsWith("Task{")) {
                headerFreeform = t.contains("mode=freeform") ||
                    t.contains("windowingMode=freeform") ||
                    t.contains("windowingMode=5")
            } else if (t.contains("ActivityRecord{") && t.contains("$pkg/")) {
                sawTarget = true
                if (headerFreeform) sawFreeform = true
            }
        }
        return when {
            sawFreeform -> true
            sawTarget -> false
            else -> null
        }
    }

    /** Root 任务快照（v2.23.6，dumpsys 解析结果） */
    data class RootTaskInfo(
        /** Task id（am stack resize 用） */
        val id: Int,
        /** 是否处于自由窗口模式 */
        val freeform: Boolean,
        /** 任务当前边界（屏幕坐标系） */
        val bounds: Rect?
    )

    /**
     * v2.23.6，Root 专属：查找目标包的任务及其真实边界（dumpsys 解析）。
     *
     * 任务头行（`* Task{... #46 ... mode=freeform ...}`）携带 id 与窗口模式，
     * 其后的 `bounds=[l,t][r,b]` 行携带边界，ActivityRecord 行（
     * `ActivityRecord{... pkg/.Act ...}`）用于关联目标包。优先返回
     * freeform 任务；找不到返回 null。用于启动后比对：ROM 是否把窗口
     * 压成了手机比例小窗。
     */
    fun findRootTaskInfo(pkg: String): RootTaskInfo? {
        val out = runSu("dumpsys activity activities", 8000L)?.second ?: return null
        var curId = -1
        var curFreeform = false
        var curBounds: Rect? = null
        var fallback: RootTaskInfo? = null
        out.lineSequence().forEach { raw ->
            val t = raw.trim()
            if (t.startsWith("* Task{") || t.startsWith("Task{")) {
                curId = Regex("#(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                curFreeform = t.contains("mode=freeform") ||
                    t.contains("windowingMode=freeform") ||
                    t.contains("windowingMode=5")
                curBounds = null
            } else if (curId >= 0 && curBounds == null) {
                val m = Regex("bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(t)
                if (m != null) {
                    val g = m.groupValues
                    curBounds = Rect(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt())
                }
            }
            if (curId >= 0 && t.contains("ActivityRecord{") && t.contains("$pkg/")) {
                val info = RootTaskInfo(curId, curFreeform, curBounds)
                if (curFreeform) return info
                if (fallback == null) fallback = info
            }
        }
        return fallback
    }

    /**
     * v2.23.6，Root 专属：强制任务边界（`am stack resize`）。部分 ROM 会把
     * freeform 窗口限制成厂商“小窗”尺寸（忽略我们传入的启动边界），启动后
     * 用它把窗口拉回桌面窗口边界。返回是否成功（best effort，失败无副作用）。
     */
    fun resizeRootTask(taskId: Int, bounds: Rect): Boolean {
        val cmd = "am stack resize $taskId " +
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        val res = runSu(cmd, 6000L) ?: return false
        return res.first == 0 &&
            !res.second.contains("Exception", ignoreCase = true) &&
            !res.second.contains("Error", ignoreCase = true)
    }

    /** Root 特性注入结果 */
    enum class InjectionResult {
        /** 写入成功，重启手机后特性声明被 PackageManager 扫描生效 */
        SUCCESS_NEEDS_REBOOT,

        /** 无可用 Root */
        NO_ROOT,

        /** 写入失败（多为 dm-verity / EROFS 写保护） */
        FAILED
    }

    /**
     * Root 特性注入：把
     * `<permissions><feature name="android.software.freeform_window_management" />
     * </permissions>` 写入 `/system/etc/permissions/anwind_freeform.xml`。
     *
     * 这是"ROM 忽略 enable_freeform_support 开关"时的唯一通用绕过：特性声明
     * 被 PackageManager 开机扫描后，ATMS.retrieveSettings 的第一个条件直接
     * 成立，无需依赖开发者选项开关。经 base64 传输内容规避 shell 转义问题；
     * 先尝试 remount 再写入，写入后 cat 校验。**必须后台线程调用。**
     */
    fun tryRootFeatureInjection(): InjectionResult {
        if (!suAvailable()) return InjectionResult.NO_ROOT
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<permissions>\n" +
            "    <feature name=\"$FEATURE_FREEFORM\" />\n" +
            "</permissions>\n"
        val b64 = Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val path = "/system/etc/permissions/anwind_freeform.xml"
        val cmd = "mount -o rw,remount / 2>/dev/null; " +
            "mount -o rw,remount /system 2>/dev/null; " +
            "mkdir -p /system/etc/permissions; " +
            "echo $b64 | base64 -d > $path && cat $path"
        val res = runSu(cmd, 10_000L) ?: return InjectionResult.FAILED
        val ok = res.first == 0 && res.second.contains(FEATURE_FREEFORM)
        if (ok) {
            // 写回只读，保持系统分区常态（即使失败也无碍，重启前保持 rw 无风险）
            runSu(
                "mount -o ro,remount / 2>/dev/null; mount -o ro,remount /system 2>/dev/null",
                4000L
            )
            Log.i(TAG, "Root 特性注入成功：$path（重启后生效）")
        } else {
            Log.w(TAG, "Root 特性注入失败（写保护/dm-verity）：${res.second.take(200)}")
        }
        return if (ok) InjectionResult.SUCCESS_NEEDS_REBOOT else InjectionResult.FAILED
    }
}
