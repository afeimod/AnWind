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
     * 「强制活动可调整大小」的全局设置键。AOSP 常量 FORCE_RESIZABLES 的
     * 字面值在不同版本/ROM 文档里有两种写法，这里两个都写（多写的未知键
     * 只是躺在 global 表里，无任何副作用），确保不可调整大小的应用也能
     * 被放进 freeform 窗口（系统会替它做兼容缩放）。
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

    private val _pendingLaunch = MutableStateFlow<PendingLaunch?>(null)

    /** 当前待用户决策的启动请求；null = 无弹窗 */
    val pendingLaunch: StateFlow<PendingLaunch?> = _pendingLaunch

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

    // ============================================================
    // 能力保障（ensureAvailable / warmup）
    // ============================================================

    /**
     * 确保"设备支持自由窗口"，返回当前是否可用。
     *
     * - 命中缓存（30 秒内）直接返回；
     * - 未授予 WRITE_SECURE_SETTINGS 时只做检测；
     * - 已授予时同步写开全局开关（ContentResolver binder 调用，毫秒级），
     *   WMS 热加载后，紧接着的 startActivity(FREEFORM) 请求即被放行。
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
                    if (available) "成功" else "后仍未生效（ROM 特殊，走弹窗引导）"
            )
        }
        cachedAvailable = available
        cachedAt = now
        return available
    }

    /** 用已授予的 WRITE_SECURE_SETTINGS 写开自由窗口相关全局设置（幂等） */
    private fun writeFreeformSettings(context: Context) {
        runCatching {
            Settings.Global.putInt(context.contentResolver, SETTING_ENABLE_FREEFORM, 1)
            SETTING_FORCE_RESIZABLES.forEach { key ->
                runCatching { Settings.Global.putInt(context.contentResolver, key, 1) }
            }
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
                Log.i(TAG, "Root：自由窗口开关已写入")
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
