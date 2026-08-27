package com.anwind

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.anwind.core.theme.WinThemeScope
import com.anwind.core.desktop.DesktopEnvironment
import com.anwind.data.prefs.SettingsStore

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 沉浸式：扩展到状态栏/导航栏下方
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
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

            WinThemeScope(theme = theme) {
                DesktopEnvironment(
                    theme = theme,
                    customWallpaperUri = customWallpaper,
                    soundEnabled = soundEnabled
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理所有打开的窗口
        com.anwind.core.window.WindowManager.get().closeAll()
    }
}
