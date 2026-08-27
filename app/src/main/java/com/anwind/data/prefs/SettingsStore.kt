package com.anwind.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        val DEFAULT_BROWSER_HOME = stringPreferencesKey("default_browser_home")
    }

    val customWallpaper: Flow<String?> = context.appPrefs.data.map { it[Keys.CUSTOM_WALLPAPER] }
    val soundEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.SOUND_ENABLED] ?: true }
    val taskbarAutohide: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TASKBAR_AUTOHIDE] ?: false }
    val showSeconds: Flow<Boolean> = context.appPrefs.data.map { it[Keys.SHOW_SECONDS] ?: false }
    val defaultBrowserHome: Flow<String> = context.appPrefs.data
        .map { it[Keys.DEFAULT_BROWSER_HOME] ?: "https://www.bing.com" }

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

    suspend fun setDefaultBrowserHome(url: String) {
        context.appPrefs.edit { it[Keys.DEFAULT_BROWSER_HOME] = url }
    }
}
