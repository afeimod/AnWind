package com.anwind.core.desktop

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.23.3：自由窗口（Freeform）能力检测 / 自动开启 / **生效状态跟踪** / 用户决策中枢。
 *
 * ## v2.23.2 的教训（本版根因修复）
 *
 * v2.23.2 已正确补上"设备能力"前提（检测 + WRITE_SECURE_SETTINGS / Root
 * 写开 enable_freeform_support），但有一个错误假设：**以为开关写入后 WMS
 * 会热加载、改完即生效**。经 AOSP 源码核实（services/core/java/com/android/
 * server/wm/ActivityTaskManagerService.java）：
 *
 * - 该开关只在系统启动时被读取一次（retrieveSettings() 写入
 *   mSupportsFreeformWindowManagement 后不再更新）；
 * - 运行时 SettingsObserver 只监听字体缩放 / 错误对话框 / 字重，
 *   **不监听**自由窗口开关；
 * - 也就是说：**写开开关后必须重启手机一次，system_server 才会真正放行
 *   WINDOWING_MODE_FREEFORM 请求**；在此之前所有窗口化请求都被静默丢弃，
 *   应用照常全屏启动。
 *
 * 这解释了用户实测"ADB 授权成功、弹窗不再出现、但应用仍不能窗口化启动"：
 * 授权 → 开关写入 → 读回 =1 → 能力检测判"可用" → 从此不再弹窗；但系统从未
 * 在本次开机内重新读取该开关，窗口化请求全部被丢。此外该开关是**全局系统
 * 设置**，卸载重装 AnWind 不会清除（SettingsProvider 独立存储），所以重装后
 * 同样是"看似可用、实际未生效、不再提示"的死局。
 *
 * ## v2.23.3 方案
 *
 * 1. **写入时间跟踪**：写开关时持久化记录当时的 boot_count + uptimeMillis。
 *    若本次开机内写过且尚未重启 → 明确知道系统还没读到 → 引导用户重启，
 *    不再误判"已生效"后沉默；
 * 2. **启动后验证**：窗口化启动后 300/800/1600ms 回读目标任务真实
 *    windowingMode（TaskInfo 隐藏字段，浅灰名单多数设备可读）：
 *    读到 freeform(=5) → 记录"真实生效"；读到全屏(=1) → 按是否本次开机
 *    写入分流弹窗（需重启 / ROM 屏蔽）；读不到 → 一次性轻提示（覆盖从
 *    v2.23.2 升级、开关已 =1 但无从判断是否重启过的存量用户）；
 * 3. **决策弹窗分原因展示**（[REASON_NO_PERMISSION] / [REASON_NEEDS_REBOOT]
 *    / [REASON_STILL_FULLSCREEN] / [REASON_FIRST_HINT]），并附带实时诊断
 *    状态，用户随时能看到"缺哪一步"，不再存在静默失败路径。
 *
 * ## 线程模型
 *
 * - [ensureAvailable]：毫秒级的特性/设置读取 + （已授权时的）同步设置写入，
 *   主线程可直接调用（与既有 getLaunchIntentForPackage 同量级）；
 * - [warmup]：含 su 子进程（4 秒看门狗），必须在 IO 线程调用
 *   （AnWindApp.onCreate → applicationScope.launch(Dispatchers.IO)）。
 */
object FreeformCompat {

    private const val TAG = "AnWind.FreeformCompat"

    /** android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM（隐藏常量，各版本数值恒为 5） */
    const val WINDOWING_MODE_FREEFORM = 5

    /**
     * ActivityOptions.toBundle() 里窗口模式的隐藏键（KEY_LAUNCH_WINDOWING_MODE，
     * 字面值 "android:activity.windowingMode"）。setLaunchWindowingMode
     * 内部写的就是这个键 —— 直接 putInt 完全等效，作为反射被个别 ROM
     * 屏蔽时的终极兜底通道。
     */
    const val KEY_LAUNCH_WINDOWING_MODE = "android:activity.windowingMode"

    /** 开发者选项「启用自由窗口」对应的全局设置键（AOSP：仅开机时读取一次） */
    private const val SETTING_ENABLE_FREEFORM = "enable_freeform_support"

    /**
     * 「强制活动可调整大小」的全局设置键。AOSP 常量 FORCE_RESIZABLES 的
     * 字面值在不同版本/ROM 文档里有两种写法，这里两个都写（多写的未知键
     * 只是躺在 global 表里，无任何副作用），确保不可调整大小的应用也能
     * 被放进 freeform 窗口（系统会替它做兼容缩放）。
     */
    private val SETTING_FORCE_RESIZABLES = arrayOf(
        "force_resizable_activities",
        "force_resizable"
    )

    // ============================================================
    // 决策原因（弹窗按此分流文案）
    // ============================================================

    /** 未开启且无自动开启手段 → ADB 授权 / 开发者选项 / Root 引导 */
    const val REASON_NO_PERMISSION = 0

    /** 开关已写入但本次开机内系统尚未读取（AOSP：仅开机读取）→ 重启引导 */
    const val REASON_NEEDS_REBOOT = 1

    /** 开关已开（且非本次开机写入）但验证到任务仍全屏 → 先重启、再 ROM 排查 */
    const val REASON_STILL_FULLSCREEN = 2

    /** 开关已开、验证通道不可用、历史从未验证成功 → 一次性轻提示（升级存量用户） */
    const val REASON_FIRST_HINT = 3

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

    /** 本进程已尝试过窗口化启动（进入启动后验证流程） */
    @Volatile
    var probeTried: Boolean = false
        private set

    /** 本进程内验证到过"窗口化真实生效"（读到任务 windowingMode=FREEFORM） */
    @Volatile
    var probeSucceeded: Boolean = false
        private set

    /** 本进程内验证到过"任务仍全屏"（系统拒收窗口化请求） */
    @Volatile
    var brokenConfirmed: Boolean = false
        private set

    /** 能力缓存（30 秒 TTL，避免每次点图标都读 SettingsProvider） */
    @Volatile
    private var cachedAvailable: Boolean? = null

    @Volatile
    private var cachedAt: Long = 0L

    private const val CACHE_TTL_MS = 30_000L

    /** Root 尝试只做一次/进程（避免每次启动手机应用都弹 su 授权框打扰用户） */
    private val rootAttempted = AtomicBoolean(false)

    // ============================================================
    // 写入时间跟踪（SharedPreferences：判断"开关是本次开机内写的吗"）
    // ============================================================

    private const val PREFS = "freeform_compat"
    private const val KEY_WRITE_BOOT = "write_boot_count"
    private const val KEY_WRITE_UPTIME = "write_uptime"
    private const val KEY_WORKING_BOOT = "working_boot_count"
    private const val KEY_FIRST_HINT_SHOWN = "first_hint_shown"

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
     * - 读不到 boot_count 的 ROM 退回 uptimeMillis 单调性：同一次开机内
     *   uptime 只增不减，重启后归零 —— 当前 uptime >= 写入时 uptime 即未重启。
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

    /** 历史上是否验证过"窗口化真实生效"过至少一次 */
    fun everVerifiedWorking(context: Context): Boolean =
        prefs(context).getInt(KEY_WORKING_BOOT, -1) >= 0

    /** 一次性轻提示（升级存量用户）是否已展示过（安装级，一次性） */
    fun firstHintAlreadyShown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FIRST_HINT_SHOWN, false)

    /** 标记一次性轻提示已展示（安装级） */
    fun markFirstHintShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_FIRST_HINT_SHOWN, true).apply()
    }

    /** 推荐用户执行的 ADB 一次性授权命令（按实际包名生成，防止 fork 改包名后失效） */
    fun adbGrantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    // ============================================================
    // 能力检测
    // ============================================================

    /**
     * 设备是否原生声明支持自由窗口（DeX / 部分国产 ROM / 模拟器等）。
     *
     * 注：PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT 常量历史上为
     * 隐藏 API，为兼容各 compileSdk 直接使用字面值（hasSystemFeature 为
     * 公开 API，接收任意特性字符串）。
     */
    private fun hasFreeformFeature(context: Context): Boolean = runCatching {
        context.packageManager
            .hasSystemFeature("android.hardware.freeform_window_management")
    }.getOrDefault(false)

    /** 全局「启用自由窗口」开关是否已打开（开发者选项或本类写入） */
    private fun isFreeformSettingOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING_ENABLE_FREEFORM, 0) != 0
    }.getOrDefault(false)

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
     * - 已授予时同步写开全局开关（ContentResolver binder 调用，毫秒级）。
     *   ⚠ v2.23.3 修正：写入成功 ≠ 立即生效 —— 系统只在**下次开机**时读取
     *   该开关（见类注释），生效状态由 [pendingRebootSinceOurWrite] /
     *   启动后验证流程单独跟踪，本方法只回答"开关是否处于打开状态"。
     *
     * 每次从桌面启动手机应用前由 [AndroidApps] 调用。
     */
    fun ensureAvailable(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        cachedAvailable?.let { cached ->
            if (now - cachedAt < CACHE_TTL_MS) return cached
        }
        val ctx = context.applicationContext
        var available = probe(ctx)
        if (!available && hasSecureSettings(ctx)) {
            writeFreeformSettings(ctx)
            available = probe(ctx)
            Log.i(
                TAG,
                "WRITE_SECURE_SETTINGS 已授予：自由窗口开关写入" +
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
            Settings.Global.putInt(context.contentResolver, SETTING_ENABLE_FREEFORM, 1)
            SETTING_FORCE_RESIZABLES.forEach { key ->
                runCatching { Settings.Global.putInt(context.contentResolver, key, 1) }
            }
            recordWrite(context)
        }.onFailure { Log.w(TAG, "写自由窗口设置失败：${it.message}") }
    }

    /**
     * 失效能力缓存，下次 [ensureAvailable] 立即重新探测。
     * 决策弹窗关闭时调用：用户可能刚按指引授予了 ADB 权限或打开了
     * 开发者选项开关，无需等 30 秒 TTL 过期。
     */
    fun invalidateCache() {
        cachedAvailable = null
        cachedAt = 0L
    }

    /**
     * 进程启动时后台预热：检测能力；不可用且尚未尝试过 Root 时，
     * 用 su 静默写开同样的全局开关（Magisk / 模拟器等一次放行即成功）。
     *
     * ⚠ Root 写入同样只在**下次开机**被系统读取（见类注释），写入后
     * [pendingRebootSinceOurWrite] 会如实返回 true。
     *
     * 必须在 IO 线程调用（su 子进程 + 4 秒看门狗）。
     */
    fun warmup(context: Context) {
        val ctx = context.applicationContext
        ensureAvailable(ctx)
        if (cachedAvailable == false && !rootAttempted.getAndSet(true)) {
            val cmd = buildString {
                append("settings put global $SETTING_ENABLE_FREEFORM 1")
                SETTING_FORCE_RESIZABLES.forEach { append("; settings put global $it 1") }
            }
            if (runSuCommands(cmd)) {
                Log.i(TAG, "Root：自由窗口开关已写入（重启手机后生效）")
                recordWrite(ctx)
                // 失效缓存，下次 ensureAvailable 重新探测
                cachedAvailable = null
                cachedAt = 0L
            } else {
                Log.i(TAG, "Root 不可用或被拒绝（非 Root 设备的正常路径，走 ADB / 开发者选项引导）")
            }
        }
    }

    // ============================================================
    // 启动后验证回执（AndroidApps 在验证回调里调用）
    // ============================================================

    /** 记录"本进程已发起过一次窗口化启动尝试" */
    fun noteLaunchAttempt() {
        probeTried = true
    }

    /**
     * 启动后验证结果回执。
     *
     * @param freeform true = 读到目标任务 windowingMode=FREEFORM(5)，
     *   记录"真实生效"（含启动后强切成功的场景）；false = 读到全屏(1)，
     *   标记"系统拒收窗口化请求"。
     */
    fun noteVerified(context: Context, freeform: Boolean) {
        if (freeform) {
            probeSucceeded = true
            brokenConfirmed = false
            val p = prefs(context)
            val boot = currentBoot(context)
            if (boot >= 0 && p.getInt(KEY_WORKING_BOOT, -1) != boot) {
                p.edit().putInt(KEY_WORKING_BOOT, boot).apply()
            }
        } else {
            brokenConfirmed = true
        }
    }

    // ============================================================
    // 用户决策弹窗
    // ============================================================

    /** 请求弹窗决策：暂存本次启动请求（含原因码）并通知桌面层弹窗 */
    fun requestDecision(pkg: String, activity: String, label: String, reason: Int) {
        _pendingLaunch.value = PendingLaunch(pkg, activity, label, reason)
    }

    /** 关闭决策弹窗（取消 / 已决策） */
    fun cancelPendingLaunch() {
        _pendingLaunch.value = null
    }

    // ============================================================
    // 实时诊断（决策弹窗展示，用户随时能看到缺哪一步）
    // ============================================================

    fun diagnosticLines(context: Context): List<String> {
        val ctx = context.applicationContext
        val lines = mutableListOf<String>()
        lines += "系统 Freeform 特性：${if (hasFreeformFeature(ctx)) "已声明" else "未声明"}"
        lines += "自由窗口开关：${if (isFreeformSettingOn(ctx)) "已开启 (=1)" else "未开启 (=0)"}"
        lines += "ADB 授权：${if (hasSecureSettings(ctx)) "已授予" else "未授予"}"
        if (pendingRebootSinceOurWrite(ctx)) {
            lines += "开关写入：本次开机内写入，重启后系统才会读取"
        }
        if (everVerifiedWorking(ctx)) {
            lines += "窗口化验证：历史成功过"
        }
        return lines
    }

    // ============================================================
    // Root 辅助
    // ============================================================

    /**
     * 执行 `su -c <cmd>`；返回是否成功（exit 0）。
     *
     * - 先探测常见 su 路径，无 Root 环境直接返回 false，不白起进程；
     * - 4 秒看门狗 destroy：Magisk 首次授权弹窗等待 / su 挂起时兜底退出，
     *   不阻塞调用线程的协程池；
     * - 一切异常（IOException / SELinux 拒绝）都吞掉返回 false。
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
