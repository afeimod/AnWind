package com.anwind.core.desktop

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
 * - 系统应用判定：applicationInfo.flags 含 FLAG_SYSTEM；
 * - 自身（AnWind）排除，避免在开始菜单里套娃；
 * - Manifest 已声明 <queries>（MAIN/LAUNCHER），Android 11+ 无需
 *   QUERY_ALL_PACKAGES 宽限权限；
 * - 结果进程内缓存一次；图标随列表在 IO 线程预解码为 Bitmap，
 *   网格滚动零卡顿。
 *
 * v2.23.0：从桌面启动安卓应用 → **强制在桌面窗口（freeform 自由窗口）打开**，
 * 不再全屏覆盖桌面（Windows 风格体验：启动的应用像一个普通窗口悬浮在
 * 桌面上，任务栏仍然可见）。实现见 [launch] / [freeformOptions]。
 */
object AndroidApps {

    /**
     * v2.23.0：android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM。
     * 隐藏常量（AOSP 自 API 26 起定义，API 24/25 对应的
     * FREEFORM_WORKSPACE_STACK_ID 同为 5），各版本数值稳定，直接硬编码。
     */
    private const val WINDOWING_MODE_FREEFORM = 5

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
     * 用显式 Component 启动应用；返回是否启动成功。
     *
     * v2.23.0：携带 [freeformOptions] 强制以桌面窗口（freeform）模式启动：
     * - 目标任务已存在时，系统同样会把它切换到请求的窗口模式，
     *   保证"从桌面启动一定进窗口"；
     * - 旧平台（API < 24）或个别 ROM 屏蔽隐藏 API 时优雅降级为
     *   原普通启动（全屏），不影响可用性。
     */
    fun launch(context: Context, app: AppInfo): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.pkg, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent, freeformOptions(context))
        true
    }.getOrDefault(false)

    /**
     * v2.23.0：构建"桌面窗口"启动参数（ActivityOptions）。
     *
     * 两步组合：
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
                .invoke(options, WINDOWING_MODE_FREEFORM)
        } catch (t: Throwable) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchStack", Int::class.javaPrimitiveType)
                    .invoke(options, WINDOWING_MODE_FREEFORM)
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

        options.toBundle()
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
