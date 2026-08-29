package com.anwind.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appPrefs by preferencesDataStore(name = "anwind_prefs")

/**
 * 应用通用偏好设置：壁纸、音量、是否启用启动音效等。
 *
 * 视觉重构后扩展：增加了蓝牙、鼠标、键盘、电源、应用默认程序、隐私权限等更多设置项，
 * 覆盖 Win11 风格设置中心的主要分类。
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

        // === v2.14：个性化（颜色 / 字体 / 任务栏） ===
        // 颜色模式：auto 跟随主题 / light 强制浅色 / dark 强制深色（作用于所有 Windows 主题）
        val APP_COLOR_MODE = stringPreferencesKey("app_color_mode")
        // 强调色覆盖：default 主题自带 / "#RRGGBB" 十六进制色值
        val APP_ACCENT = stringPreferencesKey("app_accent")
        // 全局字体缩放（0.85..1.4，乘到系统 fontScale 上，控制所有文字大小）
        val FONT_SCALE = floatPreferencesKey("font_scale")
        // 全局字体颜色：auto 跟随主题 / white 白色 / black 黑色
        val FONT_COLOR = stringPreferencesKey("font_color")
        // 全局字体样式：default 无衬线 / serif 衬线 / mono 等宽
        val FONT_STYLE = stringPreferencesKey("font_style")
        // 任务栏图标对齐：true 居中（Win11 风格）/ false 靠左（Win7 风格）
        val TASKBAR_CENTERED = booleanPreferencesKey("taskbar_centered")

        // === 刘海屏 / 任务栏 ===
        // 内容是否绘制进刘海屏区域（默认 true，占用刘海屏）
        val USE_CUTOUT = booleanPreferencesKey("use_cutout")
        // 任务栏高度（dp，36..80；0f 表示跟随主题默认）
        val TASKBAR_HEIGHT = floatPreferencesKey("taskbar_height")
        // === 托盘时钟样式（v2.10，长按任务栏时间弹出设置） ===
        // 显示模式：digital 数字 / clock 表盘 / lcd 液晶
        val TRAY_CLOCK_MODE = stringPreferencesKey("tray_clock_mode")
        // 时钟字号（sp，8..18）
        val TRAY_CLOCK_FONT_SIZE = floatPreferencesKey("tray_clock_font_size")
        // 是否显示日期
        val TRAY_SHOW_DATE = booleanPreferencesKey("tray_show_date")
        // 排版：stacked 时间/日期两行 / inline 单行
        val TRAY_LAYOUT = stringPreferencesKey("tray_layout")

        // === 桌面图标（v2.11 右键菜单"排序方式"） ===
        // 排序模式：default 默认（内置应用 + 快捷方式原序）/ name 按名称 / type 按类型
        val DESKTOP_SORT = stringPreferencesKey("desktop_sort")
        // v2.14：桌面图标自定义顺序（长按拖动排序后保存，逗号分隔的 item id 列表）
        val DESKTOP_ICON_ORDER = stringPreferencesKey("desktop_icon_order")

        // === 蓝牙与设备 ===
        val BLUETOOTH_ENABLED = booleanPreferencesKey("bluetooth_enabled")
        val MOUSE_POINTER_SPEED = floatPreferencesKey("mouse_pointer_speed")
        val KEYBOARD_VIBRATION = booleanPreferencesKey("keyboard_vibration")
        val TOUCH_FEEDBACK = booleanPreferencesKey("touch_feedback")

        // === 虚拟鼠标（v2.13：Windows 风格指针） ===
        // 是否显示鼠标指针
        val MOUSE_CURSOR_ENABLED = booleanPreferencesKey("mouse_cursor_enabled")
        // 指针主题：white 经典白 / black 经典黑 / blue 蓝色 / green 高对比绿
        val MOUSE_CURSOR_THEME = stringPreferencesKey("mouse_cursor_theme")
        // 指针大小（dp，16..48）
        val MOUSE_CURSOR_SIZE = floatPreferencesKey("mouse_cursor_size")
        // 图标打开方式：single 单击打开 / double 双击打开（默认 single）
        val MOUSE_CLICK_MODE = stringPreferencesKey("mouse_click_mode")
        // 右键手势：twofinger 双指轻点 / longpress 长按（默认 twofinger）
        val MOUSE_RIGHT_CLICK = stringPreferencesKey("mouse_right_click")

        // === 虚拟键盘（v2.13：全键盘） ===
        // 总开关：off 时文本框回退系统输入法
        val KEYBOARD_MASTER = booleanPreferencesKey("keyboard_master")
        // 显示功能键行（Esc + F1-F12）
        val KEYBOARD_FUNC_ROW = booleanPreferencesKey("keyboard_func_row")
        // 显示小键盘（含方向键）
        val KEYBOARD_NUMPAD = booleanPreferencesKey("keyboard_numpad")
        // 键盘缩放（0.75..1.35）
        val KEYBOARD_SCALE = floatPreferencesKey("keyboard_scale")
        // 键盘主题：light / dark / blue / glass
        val KEYBOARD_THEME = stringPreferencesKey("keyboard_theme")
        // 键盘位置（归一化 0..1，x 居中比例 / y 1.0=贴底）
        val KEYBOARD_POS_X = floatPreferencesKey("keyboard_pos_x")
        val KEYBOARD_POS_Y = floatPreferencesKey("keyboard_pos_y")
        // 允许拖动键盘
        val KEYBOARD_DRAG_ENABLED = booleanPreferencesKey("keyboard_drag_enabled")

        // === 系统：电源、通知 ===
        val POWER_SAVER = booleanPreferencesKey("power_saver")
        val BRIGHTNESS = floatPreferencesKey("brightness")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val DO_NOT_DISTURB = booleanPreferencesKey("do_not_disturb")

        // === 应用：默认程序 ===
        val DEFAULT_BROWSER = stringPreferencesKey("default_browser")
        val DEFAULT_FILE_MANAGER = stringPreferencesKey("default_file_manager")
        val AUTOSTART_APPS = stringPreferencesKey("autostart_apps")  // 逗号分隔的 app id

        // === 时间与语言 ===
        val TIME_FORMAT_24H = booleanPreferencesKey("time_format_24h")
        val LANGUAGE = stringPreferencesKey("language")

        // === 隐私和安全 ===
        val LOCATION_ENABLED = booleanPreferencesKey("location_enabled")
        val CAMERA_ACCESS = booleanPreferencesKey("camera_access")
        val MICROPHONE_ACCESS = booleanPreferencesKey("microphone_access")
        val DIAGNOSTICS_OPT_IN = booleanPreferencesKey("diagnostics_opt_in")

        // === Windows Update ===
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val UPDATE_CHANNEL = stringPreferencesKey("update_channel")  // stable / beta / dev
    }

    // === 基础设置 ===
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

    // === 刘海屏 / 任务栏 ===
    val useCutout: Flow<Boolean> = context.appPrefs.data.map { it[Keys.USE_CUTOUT] ?: true }
    val taskbarHeight: Flow<Float> = context.appPrefs.data.map { it[Keys.TASKBAR_HEIGHT] ?: 0f }

    // === 托盘时钟样式 ===
    val trayClockMode: Flow<String> = context.appPrefs.data.map { it[Keys.TRAY_CLOCK_MODE] ?: "digital" }
    val trayClockFontSize: Flow<Float> = context.appPrefs.data.map { it[Keys.TRAY_CLOCK_FONT_SIZE] ?: 0f }
    val trayShowDate: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TRAY_SHOW_DATE] ?: true }
    val trayLayout: Flow<String> = context.appPrefs.data.map { it[Keys.TRAY_LAYOUT] ?: "stacked" }

    // === 桌面图标 ===
    val desktopSort: Flow<String> = context.appPrefs.data.map { it[Keys.DESKTOP_SORT] ?: "default" }
    // v2.14：自定义图标顺序（空串 = 未自定义，按默认序）
    val desktopIconOrder: Flow<String> = context.appPrefs.data.map { it[Keys.DESKTOP_ICON_ORDER] ?: "" }

    // === v2.14：个性化 ===
    val appColorMode: Flow<String> = context.appPrefs.data.map { it[Keys.APP_COLOR_MODE] ?: "auto" }
    val appAccent: Flow<String> = context.appPrefs.data.map { it[Keys.APP_ACCENT] ?: "default" }
    val fontScale: Flow<Float> = context.appPrefs.data.map { it[Keys.FONT_SCALE] ?: 1.0f }
    val fontColor: Flow<String> = context.appPrefs.data.map { it[Keys.FONT_COLOR] ?: "auto" }
    val fontStyle: Flow<String> = context.appPrefs.data.map { it[Keys.FONT_STYLE] ?: "default" }
    val taskbarCentered: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TASKBAR_CENTERED] ?: true }

    // === 蓝牙与设备 ===
    val bluetoothEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.BLUETOOTH_ENABLED] ?: false }
    val mousePointerSpeed: Flow<Float> = context.appPrefs.data.map { it[Keys.MOUSE_POINTER_SPEED] ?: 1.0f }
    val keyboardVibration: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_VIBRATION] ?: true }
    val touchFeedback: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TOUCH_FEEDBACK] ?: false }

    // === 虚拟鼠标（v2.13） ===
    val mouseCursorEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_ENABLED] ?: true }
    val mouseCursorTheme: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_THEME] ?: "white" }
    val mouseCursorSize: Flow<Float> = context.appPrefs.data.map { it[Keys.MOUSE_CURSOR_SIZE] ?: 26f }
    val mouseClickMode: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_CLICK_MODE] ?: "single" }
    val mouseRightClick: Flow<String> = context.appPrefs.data.map { it[Keys.MOUSE_RIGHT_CLICK] ?: "twofinger" }

    // === 虚拟键盘（v2.13） ===
    // v2.13.2：keyboardMaster 默认关闭 —— 大多数场景手机系统输入法更顺手，
    // 需要应用内全键盘的用户在 设置→键盘 里主动开启
    val keyboardMaster: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_MASTER] ?: false }
    val keyboardFuncRow: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_FUNC_ROW] ?: true }
    val keyboardNumpad: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_NUMPAD] ?: true }
    val keyboardScale: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_SCALE] ?: 1.0f }
    val keyboardTheme: Flow<String> = context.appPrefs.data.map { it[Keys.KEYBOARD_THEME] ?: "dark" }
    val keyboardPosX: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_POS_X] ?: 0.5f }
    val keyboardPosY: Flow<Float> = context.appPrefs.data.map { it[Keys.KEYBOARD_POS_Y] ?: 1.0f }
    val keyboardDragEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.KEYBOARD_DRAG_ENABLED] ?: true }

    // === 系统 ===
    val powerSaver: Flow<Boolean> = context.appPrefs.data.map { it[Keys.POWER_SAVER] ?: false }
    val brightness: Flow<Float> = context.appPrefs.data.map { it[Keys.BRIGHTNESS] ?: 0.8f }
    val notificationsEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: true }
    val doNotDisturb: Flow<Boolean> = context.appPrefs.data.map { it[Keys.DO_NOT_DISTURB] ?: false }

    // === 应用 ===
    val defaultBrowser: Flow<String> = context.appPrefs.data.map { it[Keys.DEFAULT_BROWSER] ?: "browser" }
    val defaultFileManager: Flow<String> = context.appPrefs.data.map { it[Keys.DEFAULT_FILE_MANAGER] ?: "file_explorer" }
    val autostartApps: Flow<String> = context.appPrefs.data.map { it[Keys.AUTOSTART_APPS] ?: "" }

    // === 时间与语言 ===
    val timeFormat24h: Flow<Boolean> = context.appPrefs.data.map { it[Keys.TIME_FORMAT_24H] ?: true }
    val language: Flow<String> = context.appPrefs.data.map { it[Keys.LANGUAGE] ?: "zh-CN" }

    // === 隐私和安全 ===
    val locationEnabled: Flow<Boolean> = context.appPrefs.data.map { it[Keys.LOCATION_ENABLED] ?: false }
    val cameraAccess: Flow<Boolean> = context.appPrefs.data.map { it[Keys.CAMERA_ACCESS] ?: true }
    val microphoneAccess: Flow<Boolean> = context.appPrefs.data.map { it[Keys.MICROPHONE_ACCESS] ?: true }
    val diagnosticsOptIn: Flow<Boolean> = context.appPrefs.data.map { it[Keys.DIAGNOSTICS_OPT_IN] ?: false }

    // === Windows Update ===
    val autoUpdate: Flow<Boolean> = context.appPrefs.data.map { it[Keys.AUTO_UPDATE] ?: true }
    val updateChannel: Flow<String> = context.appPrefs.data.map { it[Keys.UPDATE_CHANNEL] ?: "stable" }

    // === 基础 setter ===
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

    // === 刘海屏 / 任务栏 setter ===
    suspend fun setUseCutout(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.USE_CUTOUT] = enabled }
    }

    suspend fun setTaskbarHeight(heightDp: Float) {
        context.appPrefs.edit { it[Keys.TASKBAR_HEIGHT] = heightDp.coerceIn(0f, 80f) }
    }

    // === 托盘时钟样式 setter ===
    suspend fun setTrayClockMode(mode: String) {
        context.appPrefs.edit { it[Keys.TRAY_CLOCK_MODE] = mode }
    }

    suspend fun setTrayClockFontSize(sizeSp: Float) {
        // 0 表示"自动"（跟随任务栏高度自适应），有效范围 8..18sp
        val v = if (sizeSp < 8f) 0f else sizeSp.coerceIn(8f, 18f)
        context.appPrefs.edit { it[Keys.TRAY_CLOCK_FONT_SIZE] = v }
    }

    suspend fun setTrayShowDate(show: Boolean) {
        context.appPrefs.edit { it[Keys.TRAY_SHOW_DATE] = show }
    }

    suspend fun setTrayLayout(layout: String) {
        context.appPrefs.edit { it[Keys.TRAY_LAYOUT] = layout }
    }

    // === 桌面图标 setter ===
    suspend fun setDesktopSort(mode: String) {
        val v = if (mode in setOf("default", "name", "type")) mode else "default"
        context.appPrefs.edit { it[Keys.DESKTOP_SORT] = v }
    }

    suspend fun setDesktopIconOrder(order: String) {
        context.appPrefs.edit { it[Keys.DESKTOP_ICON_ORDER] = order }
    }

    // === v2.14：个性化 setter ===
    suspend fun setAppColorMode(mode: String) {
        val v = if (mode in setOf("auto", "light", "dark")) mode else "auto"
        context.appPrefs.edit { it[Keys.APP_COLOR_MODE] = v }
    }

    suspend fun setAppAccent(accent: String) {
        context.appPrefs.edit { it[Keys.APP_ACCENT] = accent }
    }

    suspend fun setFontScale(scale: Float) {
        context.appPrefs.edit { it[Keys.FONT_SCALE] = scale.coerceIn(0.85f, 1.4f) }
    }

    suspend fun setFontColor(color: String) {
        val v = if (color in setOf("auto", "white", "black")) color else "auto"
        context.appPrefs.edit { it[Keys.FONT_COLOR] = v }
    }

    suspend fun setFontStyle(style: String) {
        val v = if (style in setOf("default", "serif", "mono")) style else "default"
        context.appPrefs.edit { it[Keys.FONT_STYLE] = v }
    }

    suspend fun setTaskbarCentered(centered: Boolean) {
        context.appPrefs.edit { it[Keys.TASKBAR_CENTERED] = centered }
    }

    // === 蓝牙与设备 setter ===
    suspend fun setBluetoothEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.BLUETOOTH_ENABLED] = enabled }
    }

    suspend fun setMousePointerSpeed(speed: Float) {
        context.appPrefs.edit { it[Keys.MOUSE_POINTER_SPEED] = speed.coerceIn(0.5f, 2.0f) }
    }

    suspend fun setKeyboardVibration(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_VIBRATION] = enabled }
    }

    suspend fun setTouchFeedback(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TOUCH_FEEDBACK] = enabled }
    }

    // === 虚拟鼠标 setter（v2.13） ===
    suspend fun setMouseCursorEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_ENABLED] = enabled }
    }

    suspend fun setMouseCursorTheme(theme: String) {
        val v = if (theme in setOf("white", "black", "blue", "green")) theme else "white"
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_THEME] = v }
    }

    suspend fun setMouseCursorSize(sizeDp: Float) {
        context.appPrefs.edit { it[Keys.MOUSE_CURSOR_SIZE] = sizeDp.coerceIn(16f, 48f) }
    }

    suspend fun setMouseClickMode(mode: String) {
        val v = if (mode in setOf("single", "double")) mode else "single"
        context.appPrefs.edit { it[Keys.MOUSE_CLICK_MODE] = v }
    }

    suspend fun setMouseRightClick(mode: String) {
        val v = if (mode in setOf("twofinger", "longpress")) mode else "twofinger"
        context.appPrefs.edit { it[Keys.MOUSE_RIGHT_CLICK] = v }
    }

    // === 虚拟键盘 setter（v2.13） ===
    suspend fun setKeyboardMaster(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_MASTER] = enabled }
    }

    suspend fun setKeyboardFuncRow(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_FUNC_ROW] = enabled }
    }

    suspend fun setKeyboardNumpad(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_NUMPAD] = enabled }
    }

    suspend fun setKeyboardScale(scale: Float) {
        context.appPrefs.edit { it[Keys.KEYBOARD_SCALE] = scale.coerceIn(0.75f, 1.35f) }
    }

    suspend fun setKeyboardTheme(theme: String) {
        val v = if (theme in setOf("light", "dark", "blue", "glass")) theme else "dark"
        context.appPrefs.edit { it[Keys.KEYBOARD_THEME] = v }
    }

    suspend fun setKeyboardPos(x: Float, y: Float) {
        context.appPrefs.edit {
            it[Keys.KEYBOARD_POS_X] = x.coerceIn(0f, 1f)
            it[Keys.KEYBOARD_POS_Y] = y.coerceIn(0f, 1f)
        }
    }

    suspend fun setKeyboardDragEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.KEYBOARD_DRAG_ENABLED] = enabled }
    }

    // === 系统 setter ===
    suspend fun setPowerSaver(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.POWER_SAVER] = enabled }
    }

    suspend fun setBrightness(value: Float) {
        // v2.12：下限放宽到 0.05（跟随设置中心/快速面板的真实滑块范围）
        context.appPrefs.edit { it[Keys.BRIGHTNESS] = value.coerceIn(0.05f, 1.0f) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setDoNotDisturb(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.DO_NOT_DISTURB] = enabled }
    }

    // === 应用 setter ===
    suspend fun setDefaultBrowser(id: String) {
        context.appPrefs.edit { it[Keys.DEFAULT_BROWSER] = id }
    }

    suspend fun setDefaultFileManager(id: String) {
        context.appPrefs.edit { it[Keys.DEFAULT_FILE_MANAGER] = id }
    }

    // === 时间与语言 setter ===
    suspend fun setTimeFormat24h(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.TIME_FORMAT_24H] = enabled }
    }

    suspend fun setLanguage(lang: String) {
        context.appPrefs.edit { it[Keys.LANGUAGE] = lang }
    }

    // === 隐私和安全 setter ===
    suspend fun setLocationEnabled(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.LOCATION_ENABLED] = enabled }
    }

    suspend fun setCameraAccess(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.CAMERA_ACCESS] = enabled }
    }

    suspend fun setMicrophoneAccess(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.MICROPHONE_ACCESS] = enabled }
    }

    suspend fun setDiagnosticsOptIn(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.DIAGNOSTICS_OPT_IN] = enabled }
    }

    // === Windows Update setter ===
    suspend fun setAutoUpdate(enabled: Boolean) {
        context.appPrefs.edit { it[Keys.AUTO_UPDATE] = enabled }
    }

    suspend fun setUpdateChannel(channel: String) {
        context.appPrefs.edit { it[Keys.UPDATE_CHANNEL] = channel }
    }

    /**
     * 清空全部偏好设置（设置中心"重置应用"使用）。
     * 注意：壁纸 URI 的持久化权限无法逐项回收，重置后由系统在重启时自动回收。
     */
    suspend fun clearAll() {
        context.appPrefs.edit { it.clear() }
    }
}
