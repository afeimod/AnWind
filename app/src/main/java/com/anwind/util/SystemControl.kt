package com.anwind.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import java.util.Locale

/**
 * 系统级状态读取与控制（v2.12 设置中心真实化）。
 *
 * - 亮度：Settings.System.SCREEN_BRIGHTNESS 真实读写。
 *   写入需要"修改系统设置"特殊权限（WRITE_SETTINGS + 用户在系统页手动授权），
 *   未授权时调用方回退为窗口级亮度。
 * - 电源：PowerManager 省电模式真实读写。AOSP 对三方应用禁用直接切换
 *   （需要系统权限），部分定制 ROM 允许；失败时调用方引导打开系统省电设置页。
 * - 电池/设备信息：Build / MemoryInfo / StatFs / 电池粘性广播，全部真实数据。
 */
object SystemControl {

    // ============================================================
    // 亮度
    // ============================================================

    /** 是否已授予"修改系统设置"特殊权限（写系统亮度必需） */
    fun canWriteSystemSettings(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /** 打开本应用的"允许修改系统设置"授权页 */
    fun openWriteSettingsPage(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            android.widget.Toast.makeText(context, "无法打开授权页面", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 系统是否处于自动亮度模式 */
    fun isBrightnessAuto(context: Context): Boolean = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    /** 设置自动亮度开/关（需"修改系统设置"授权）。@return true=成功 */
    fun setBrightnessAuto(context: Context, auto: Boolean): Boolean {
        if (!canWriteSystemSettings(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            true
        }.getOrDefault(false)
    }

    /** 读系统亮度（0..255）；失败返回 -1 */
    fun getSystemBrightness(context: Context): Int = runCatching {
        Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS
        )
    }.getOrDefault(-1)

    /**
     * 写系统亮度（同时切换到手动亮度模式，否则自动亮度会覆盖写入值）。
     * @param brightness01 亮度比例 0..1
     * @return true=成功写入系统；false=无权限（调用方应回退窗口级亮度）
     */
    fun setSystemBrightness(context: Context, brightness01: Float): Boolean {
        if (!canWriteSystemSettings(context)) return false
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                (brightness01.coerceIn(0.02f, 1f) * 255f).toInt().coerceIn(1, 255)
            )
            true
        }.getOrDefault(false)
    }

    // ============================================================
    // 电源（省电模式）
    // ============================================================

    /** 系统省电模式真实状态 */
    fun isPowerSaveMode(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    }.getOrDefault(false)

    /**
     * 切换系统省电模式。
     *
     * PowerManager#setPowerSaveMode 是 @SystemApi（需要系统级 DEVICE_POWER 权限），
     * 不在公开 android.jar 中，直接调用会编译报 Unresolved reference，因此用反射；
     * 绝大多数 ROM 上反射调用会因权限不足抛异常，部分定制 ROM 允许。
     * @return true=成功切换；false=无权限/被系统拦截（调用方应引导系统省电设置页）
     */
    fun setPowerSaveMode(context: Context, enable: Boolean): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val method = PowerManager::class.java
            .getMethod("setPowerSaveMode", java.lang.Boolean.TYPE)
        // 返回值：true=系统已切换；false 或抛异常=被拒绝
        (method.invoke(pm, enable) as? Boolean) ?: true
    }.getOrDefault(false)

    // ============================================================
    // 电池
    // ============================================================

    data class BatteryInfo(
        val percent: Int,   // -1 表示未知
        val charging: Boolean,
        val full: Boolean
    )

    /** 读取电池信息（粘性广播读取，无需注册/注销） */
    fun readBattery(context: Context): BatteryInfo = runCatching {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        BatteryInfo(
            percent = pct,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING,
            full = status == BatteryManager.BATTERY_STATUS_FULL
        )
    }.getOrDefault(BatteryInfo(-1, false, false))

    // ============================================================
    // 设备信息
    // ============================================================

    data class DeviceInfo(
        val deviceName: String,
        val brand: String,
        val androidVersion: String,
        val sdkInt: Int,
        val securityPatch: String,
        val cpuCores: Int,
        val cpuAbis: String,
        val totalRamBytes: Long,
        val availRamBytes: Long,
        val storageTotalBytes: Long,
        val storageAvailBytes: Long,
        val screenWidthPx: Int,
        val screenHeightPx: Int,
        val densityDpi: Int,
        val refreshRateHz: Float
    )

    /** 读取真实设备信息（Build/内存/存储/屏幕） */
    fun readDeviceInfo(context: Context): DeviceInfo = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val dm = context.resources.displayMetrics
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        }
        DeviceInfo(
            deviceName = Build.MODEL ?: "未知设备",
            brand = "${(Build.BRAND ?: "").uppercase(Locale.ROOT)} ${Build.MANUFACTURER ?: ""}".trim(),
            androidVersion = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "?",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuAbis = Build.SUPPORTED_ABIS?.joinToString("/") ?: "?",
            totalRamBytes = mem.totalMem,
            availRamBytes = mem.availMem,
            storageTotalBytes = stat.blockCountLong * stat.blockSizeLong,
            storageAvailBytes = stat.availableBlocksLong * stat.blockSizeLong,
            screenWidthPx = dm.widthPixels,
            screenHeightPx = dm.heightPixels,
            densityDpi = dm.densityDpi,
            refreshRateHz = display?.refreshRate ?: 60f
        )
    }.getOrDefault(
        DeviceInfo("未知设备", "?", "?", 0, "?", 0, "?", 0, 0, 0, 0, 0, 0, 0, 60f)
    )

    // ============================================================
    // 格式化工具
    // ============================================================

    /** 字节数 → 人类可读（GB/MB/KB） */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", bytes / 1024f / 1024f)
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }

    /** AnWind 设备 ID（基于 ANDROID_ID，真实且稳定） */
    fun deviceId(context: Context): String = runCatching {
        (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "")
            .uppercase(Locale.ROOT).takeLast(8).ifEmpty { "00000000" }
    }.getOrDefault("00000000")
}
