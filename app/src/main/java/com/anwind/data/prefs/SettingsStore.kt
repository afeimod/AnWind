package com.anwind.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPrefs by preferencesDataStore(name = "anwind_prefs")

/**
 * 应用通用偏好设置：壁纸、音量、是否启用启动音效等。
 */
class SettingsStore(private val context: Context) {

    object Keys {
        val CUSTOM_WALLPAPER = stringPreferencesKey("custom_wallpaper")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val TASKBAR_AUTOHIDE = booleanPreferencesKey("taskbar_autohide")
        val SHOW_SECONDS = booleanPreferencesKey("show_seconds")
        val ICON_SIZE = floatPreferencesKey("icon_size")
        val DEFAULT_BROWSER_HOME = stringPreferencesKey("default_browser_home")
        // UI 缩放（整体缩放所有 dp/sp，>1 放大，<1 缩小）
        val UI_SCALE = floatPreferencesKey("ui_scale")
        // 显示方向：auto / portrait / landscape
        val DISPLAY_ORIENTATION = stringPreferencesKey("display_orientation")
        // 浏览器桌面/手机模式：desktop / mobile
        val BROWSER_UA_MODE = stringPreferencesKey("browser_ua_mode")
    }

    val customWallpaper: Flow<String?> = context.appPrefs.data.map { it[Keys.CUSTOM_WALLPAPER] }
    val soundEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.SOUND_ENABLED] ?: true }
    val taskbarAutohide: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TASKBAR_AUTOHIDE] ?: false }
    val showSeconds: Flow<Boolean> = context.appPrefs.data.map { it[Keys.SHOW_SECONDS] ?: false }
    val iconSize: Flow<Float> = context.appPrefs.data.map { it[Keys.ICON_SIZE] ?: 48f }
    val defaultBrowserHome: Flow<String> = context.appPrefs.data
        .map { it[Keys.DEFAULT_BROWSER_HOME] ?: "https://www.bing.com" }
    val uiScale: Flow<Float> = context.appPrefs.data.map { it[Keys.UI_SCALE] ?: 1.0f }
    val displayOrientation: Flow<String> = context.appPrefs.data
        .map { it[Keys.DISPLAY_ORIENTATION] ?: "auto" }
    val browserUaMode: Flow<String> = context.appPrefs.data
        .map { it[Keys.BROWSER_UA_MODE] ?: "desktop" }

    suspend fun setCustomWallpaper(uri: String?) {
        context.appPrefs.edit { prefs ->
            if (uri == null) prefs.remove(Keys.CUSTOM_WALLPAPER)
            else prefs[Keys.CUSTOM_WALLPAPER] = uri
        }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setTaskbarAutohide(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TASKBAR_AUTOHIDE] = enabled }
    }

    suspend fun setShowSeconds(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.SHOW_SECONDS] = enabled }
    }

    suspend fun setIconSize(size: Float) {
        context.appPrefs.edit { it[Keys.ICON_SIZE] = size }
    }

    suspend fun setDefaultBrowserHome(url: String) {
        context.appPrefs.edit { it[Keys.DEFAULT_BROWSER_HOME] = url }
    }

    suspend fun setUiScale(scale: Float) {
        context.appPrefs.edit { it[Keys.UI_SCALE] = scale.coerceIn(0.6f, 1.8f) }
    }

    suspend fun setDisplayOrientation(orientation: String) {
        context.appPrefs.edit { it[Keys.DISPLAY_ORIENTATION] = orientation }
    }

    suspend fun setBrowserUaMode(mode: String) {
        context.appPrefs.edit { it[Keys.BROWSER_UA_MODE] = mode }
    }
}
