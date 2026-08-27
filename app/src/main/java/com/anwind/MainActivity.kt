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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.anwind.core.theme.WinThemeScope
import com.anwind.core.desktop.DesktopEnvironment
import com.anwind.data.prefs.SettingsStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全面屏沉浸式：内容绘制到状态栏/导航栏下方，并隐藏系统栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

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

            // 应用显示方向设置
            LaunchedEffect(orientation) {
                requestedOrientation = when (orientation) {
                    "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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

    override fun onDestroy() {
        super.onDestroy()
        // 清理所有打开的窗口
        com.anwind.core.window.WindowManager.get().closeAll()
    }
}