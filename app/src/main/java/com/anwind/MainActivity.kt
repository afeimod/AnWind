package com.anwind

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.anwind.core.theme.WinThemeScope
import com.anwind.core.desktop.DesktopEnvironment
import com.anwind.data.prefs.SettingsStore
import com.anwind.util.ImmersiveMode

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全面屏沉浸式：内容绘制到状态栏/导航栏下方，并隐藏系统栏
        ImmersiveMode.applyTo(window)

        val app = AnWindApp.get(this)
        setContent {
            val theme by app.themeManager.activeTheme.collectAsState(
                initial = com.anwind.core.theme.Themes.Win11
            )
            val settingsStore = remember { app.settingsStore }
            val customWallpaper by settingsStore.customWallpaper.collectAsState(initial = null)
            val soundEnabled by settingsStore.soundEnabled.collectAsState(initial = true)
            // 全局 UI 缩放（乘到密度上，同时缩放所有 dp/sp）
            val uiScale by settingsStore.uiScale.collectAsState(initial = 1f)
            val orientation by settingsStore.displayOrientation.collectAsState(initial = "auto")

            // 应用显示方向设置：
            // - portrait / landscape: 锁定方向
            // - auto: FULL_SENSOR，跟随重力传感器旋转，不受系统旋转锁定影响
            LaunchedEffect(orientation) {
                requestedOrientation = when (orientation) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                }
            }

            // 通过覆盖 LocalDensity 实现全局 UI 缩放
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = baseDensity.density * uiScale,
                    fontScale = baseDensity.fontScale
                )
            ) {
                WinThemeScope(theme = theme) {
                    DesktopEnvironment(
                        theme = theme,
                        customWallpaperUri = customWallpaper,
                        soundEnabled = soundEnabled
                    )
                }
            }
        }
    }

    /**
     * 沉浸式防失效（v2.8 修复“全屏时上方还有状态栏和功能键”）：
     * MIUI / 部分系统在窗口焦点变化（弹输入法、视频全屏切换等）后会重新显示系统栏，
     * 每次重新获得焦点时重新断言隐藏，保证状态栏 + 功能键（导航键）不再常驻。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ImmersiveMode.applyTo(window)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理所有打开的窗口
        com.anwind.core.window.WindowManager.get().closeAll()
    }
}