package com.anwind.core.desktop

import android.content.Context
import android.content.pm.PackageManager
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
 * ## 背景：为什么需要这个类
 *
 * v2.23.0 ~ v2.23.1 只在"启动参数"上做文章（反射 setLaunchWindowingMode(5)
 * + NEW_DOCUMENT + 启动后 setTaskWindowingMode 兜底），但忽略了一个前提：
 * **绝大多数 ROM 出厂并未开启 freeform 支持**。system_server 的
 * ActivityStarter 在收到 WINDOWING_MODE_FREEFORM 请求时会先检查设备能力，
 * 不支持就直接丢弃该请求、照常以全屏建起任务 —— 表现为"点桌面图标 →
 * 应用全屏盖住桌面"，也就是用户反馈的"启动手机应用会调用出（系统）桌面"。
 *
 * Android 判定"设备支持 freeform"的依据（见 RootWindowContainer /
 * WindowManagerService#supportsFreeformWindowing）：
 * - A) 设备声明了系统特性 android.hardware.freeform_window_management
 *      （Samsung DeX、部分国产 ROM、模拟器等）；或
 * - B) 全局设置 enable_freeform_support == 1（即开发者选项里的
 *      "启用自由窗口"开关；WMS 通过设置监听热加载，改完即生效）。
 *
 * 因此本类按优先级补齐"设备能力"这一缺失环节：
 * 1. 普通安装态：只做检测（A / B 任一成立即可用）；
 * 2. 用户经 ADB / Shizuku 授予 WRITE_SECURE_SETTINGS 后：本应用直接
 *    Settings.Global.putInt 写开 B 开关（附带"强制活动可调整大小"），
 *    一劳永逸，每次启动自动维持；
 * 3. Root 设备：进程启动时后台静默 su 写开同样的开关（一次尝试）。
 *
 * v2.23.3 根修「厂商小窗」问题：MIUI / HyperOS 等厂商 ROM 出厂即声明
 * freeform 特性（自带小窗即基于 freeform 实现），导致 v2.23.2 的写入
 * 逻辑（仅当"不可用"才写）永远不会触发，force_resizable_activities
 * 从未写入 → 未适配多窗口的手机应用被系统按"不可缩放"处理，钉死在
 * 厂商小窗（矩阵缩放、固定手机比例）。本版改为：已授权即**无条件**写入
 * 全部开关（global + secure 双表、每进程一次、幂等），配合 [AndroidApps]
 * 的桌面级大窗口边界，让手机应用以电脑窗口形态运行在桌面上。
 *
 * 以上全部失败时，启动手机应用前通过 [pendingLaunch] 通知
 * DesktopEnvironment 弹窗告知用户开启方式（ADB 命令 / 开发者选项），
 * 不再静默退化为全屏。
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

    /** 开发者选项「启用自由窗口」对应的全局设置键（WMS 热加载，改完即生效） */
    private const val SETTING_ENABLE_FREEFORM = "enable_freeform_support"

    /**
     * 「强制活动可调整大小」的全局设置键（AOSP 真实键，社区/厂商文档通用写法）。
     * 部分 ROM / 文档记录从 secure 表读取，因此两个表都写
     * （多写的表项只是闲置，无任何副作用），确保不可调整大小的应用
     * 也能被放进 freeform 窗口（系统会替它做兼容缩放）。
     */
    private val SETTING_FORCE_RESIZABLES = arrayOf(
        "force_resizable_activities",
        "force_resizable"
    )

    /** 推荐用户执行的 ADB 一次性授权命令（授予后 AnWind 自动开启并维持自由窗口） */
    const val ADB_GRANT_COMMAND =
        "adb shell pm grant com.anwind android.permission.WRITE_SECURE_SETTINGS"

    /** 自由窗口不可用时的待决策启动请求（DesktopEnvironment 收集后弹窗） */
    data class PendingLaunch(
        val pkg: String,
        /** 启动 Activity 全类名；空 = 走 getLaunchIntentForPackage 兜底 */
        val activity: String,
        val label: String
    )

    /**
     * v2.23.3：授权建议提示（freeform 已可用、但 WRITE_SECURE_SETTINGS 未授予）。
     *
     * 此状态下 force_resizable_activities 无法自动写入，未适配多窗口的
     * 手机应用会被厂商系统钉死在自带「小窗」（矩阵缩放、固定手机比例），
     * 而不是桌面级大窗口。弹窗建议用户执行一次 ADB 授权（会话内不重复，
     * 不做持久化 —— 状态驱动，授权后永不弹，未授权每个进程最多提醒一次）。
     */
    data class SetupHint(
        val pkg: String,
        val activity: String,
        val label: String
    )

    private val _pendingLaunch = MutableStateFlow<PendingLaunch?>(null)

    /** 当前待用户决策的启动请求；null = 无弹窗 */
    val pendingLaunch: StateFlow<PendingLaunch?> = _pendingLaunch

    private val _setupHint = MutableStateFlow<SetupHint?>(null)

    /** v2.23.3：当前待展示的授权建议；null = 无弹窗 */
    val setupHint: StateFlow<SetupHint?> = _setupHint

    /** 本次进程内"授权建议不再提示"（仅会话级，不持久化） */
    @Volatile
    var suppressSetupHint: Boolean = false

    /**
     * v2.23.3：设置写入是否已成功完成（每进程一次）。
     *
     * v2.23.2 的缺陷：writeFreeformSettings 只在「freeform 不可用」时才执行。
     * 而 MIUI / HyperOS 等厂商 ROM 出厂就声明 freeform 特性（其自带小窗
     * 即基于 freeform 实现），probe() 直接返回可用 → **force_resizable_activities
     * 从未被写入** → 未适配多窗口的手机应用（固定竖屏 / resizeableActivity=false）
     * 被系统按「不可缩放」处理，钉死在厂商小窗的固定手机比例 —— 这正是
     * 用户反馈「还是手机自带的小窗口、不是桌面窗口」的根因。
     *
     * v2.23.3：只要 WRITE_SECURE_SETTINGS 已授予就无条件写入（幂等，
     * 成功才置位本标记；未授权时不消耗，授权后首次启动即写入）。
     */
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

    /** 当前设备自由窗口能力探测（A / B 任一成立即可用） */
    private fun probe(context: Context): Boolean =
        hasFreeformFeature(context) || isFreeformSettingOn(context)

    /** WRITE_SECURE_SETTINGS 是否已通过 ADB / Shizuku 授予本应用 */
    fun hasSecureSettings(context: Context): Boolean = runCatching {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 悬浮窗（显示在其他应用上层）权限是否已授予 —— Android 10+ 第三方
     *  请求 freeform 窗口模式被系统采纳的前置条件之一（Taskbar 同款方案） */
    fun hasOverlayPermission(context: Context): Boolean = runCatching {
        Settings.canDrawOverlays(context)
    }.getOrDefault(false)

    // ============================================================
    // 能力保障（ensureAvailable / warmup）
    // ============================================================

    /**
     * 确保"设备支持自由窗口"，返回当前是否可用。
     *
     * - 命中缓存（30 秒内）直接返回；
     * - v2.23.3：已授予 WRITE_SECURE_SETTINGS 时**无条件**写入全部开关
     *   （每进程一次，幂等）—— 不再只在"不可用"时才写，修复厂商 ROM
     *   自带 freeform 特性时 force_resizable_activities 永远缺失的问题；
     * - 写入后 WMS 热加载，紧接着的 startActivity(FREEFORM) 请求即被放行。
     *
     * 每次从桌面启动手机应用前由 [AndroidApps] 调用。
     */
    fun ensureAvailable(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        cachedAvailable?.let { cached ->
            if (now - cachedAt < CACHE_TTL_MS) return cached
        }
        val ctx = context.applicationContext
        val secure = hasSecureSettings(ctx)
        if (secure && settingsWritten.compareAndSet(false, true)) {
            val ok = writeFreeformSettings(ctx)
            Log.i(
                TAG,
                "WRITE_SECURE_SETTINGS 已授予：桌面窗口开关写入" +
                    if (ok) "成功（freeform + 强制可缩放，双表）" else "失败（部分键被 ROM 拒绝，详见 warn 日志）"
            )
        }
        val available = probe(ctx)
        if (!available && secure) {
            // 写入后仍未生效（ROM 特殊，走弹窗引导）；不重试写入（上面已每进程一次）
            Log.i(TAG, "自由窗口开关写入后仍未生效（ROM 特殊，走弹窗引导）")
        }
        cachedAvailable = available
        cachedAt = now
        return available
    }

    /**
     * 用已授予的 WRITE_SECURE_SETTINGS 写开自由窗口相关全部设置（幂等）。
     *
     * - enable_freeform_support → global 表（AOSP 标准位置，WMS 热加载）；
     * - force_resizable_activities / force_resizable → global + secure 双表
     *   （社区文档两种记录都存在，双表覆盖；多余表项只是闲置无副作用）。
     *
     * 返回 global 表是否全部写入成功（用于日志与排查）。
     */
    private fun writeFreeformSettings(context: Context): Boolean {
        val cr = context.contentResolver
        var globalOk = true
        runCatching {
            Settings.Global.putInt(cr, SETTING_ENABLE_FREEFORM, 1)
        }.onFailure {
            globalOk = false
            Log.w(TAG, "写 enable_freeform_support 失败：${it.message}")
        }
        SETTING_FORCE_RESIZABLES.forEach { key ->
            runCatching { Settings.Global.putInt(cr, key, 1) }.onFailure {
                globalOk = false
                Log.w(TAG, "写 global/$key 失败：${it.message}")
            }
            runCatching { Settings.Secure.putInt(cr, key, 1) }.onFailure {
                // secure 表失败不致命（多数 ROM 只读 global）
                Log.w(TAG, "写 secure/$key 失败（可忽略）：${it.message}")
            }
        }
        // 回读校验（日志用，方便远程排查时 adb logcat 对照）
        runCatching {
            val ff = Settings.Global.getInt(cr, SETTING_ENABLE_FREEFORM, 0)
            val fr = Settings.Global.getInt(cr, SETTING_FORCE_RESIZABLES.first(), 0)
            Log.i(TAG, "当前开关回读：enable_freeform_support=$ff, force_resizable_activities=$fr")
        }
        return globalOk
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
     * 进程启动时后台预热：
     * - 已授予 WRITE_SECURE_SETTINGS → [ensureAvailable] 里已无条件写入全部开关；
     * - 未授予但设备有 Root → v2.23.3 修复：**不再只在"不可用"时才尝试**。
     *   厂商 ROM 自带 freeform 特性时同样需要 force_resizable_activities
     *   （否则未适配应用钉死在厂商小窗），故未授予 secure 权限时一律
     *   后台静默 su 写一次（Magisk / 模拟器等一次放行即成功）。
     *
     * 必须在 IO 线程调用（su 子进程 + 4 秒看门狗）。
     */
    fun warmup(context: Context) {
        val ctx = context.applicationContext
        ensureAvailable(ctx)
        if (!hasSecureSettings(ctx) && !rootAttempted.getAndSet(true)) {
            val cmd = buildString {
                append("settings put global $SETTING_ENABLE_FREEFORM 1")
                SETTING_FORCE_RESIZABLES.forEach {
                    append("; settings put global $it 1")
                    append("; settings put secure $it 1")
                }
            }
            if (runSuCommands(cmd)) {
                Log.i(TAG, "Root：桌面窗口全部开关已写入（global+secure）")
                // 失效缓存，下次 ensureAvailable 重新探测（Root 写入即生效）
                cachedAvailable = null
                cachedAt = 0L
            } else {
                Log.i(TAG, "Root 不可用或被拒绝（非 Root 设备的正常路径，走 ADB / 开发者选项引导）")
            }
        }
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
