package com.anwind.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Windows 主题枚举：覆盖 95 / XP / 7 / 10 / 11 五代
 */
enum class WindowsVariant {
    WIN95, WIN_XP, WIN7, WIN10, WIN11;

    val displayName: String
        get() = when (this) {
            WIN95  -> "Windows 95"
            WIN_XP -> "Windows XP"
            WIN7   -> "Windows 7"
            WIN10  -> "Windows 10"
            WIN11  -> "Windows 11"
        }

    companion object {
        fun fromName(name: String?): WindowsVariant =
            entries.firstOrNull { it.name == name } ?: WIN11
    }
}

/**
 * 任务栏布局风格
 */
enum class TaskbarAlignment { LEFT, CENTER }

/**
 * 窗口边框风格
 */
enum class WindowChrome {
    /** Win95 经典：粗边框 + 灰色标题栏 + 方角 */
    CLASSIC,
    /** XP Luna：蓝色渐变标题栏 + 圆角 */
    LUNA,
    /** Win7 Aero：半透明玻璃 + 圆角 */
    AERO,
    /** Win10：扁平 + 细边框 + 方角 */
    FLAT,
    /** Win11：Mica 材质 + 大圆角 + 居中控件 */
    MICA
}

/**
 * 单个 Windows 主题的完整视觉描述。
 * 所有 UI 组件都从这里读取颜色/形状/字体。
 */
data class WinTheme(
    val variant: WindowsVariant,
    val displayName: String,

    // === 桌面 ===
    val wallpaperAsset: String,          // assets 中壁纸路径
    val desktopIconTextColor: Color,
    val desktopIconTextShadow: Boolean,

    // === 任务栏 ===
    val taskbarAlignment: TaskbarAlignment,
    val taskbarColor: Color,
    val taskbarAlpha: Float,             // 0f..1f，半透明效果
    val taskbarHeight: Dp,
    val taskbarStartButtonColor: Color,
    val taskbarIconColor: Color,
    val taskbarClockColor: Color,
    val startButtonLabel: String?,       // null=不显示文字（Win10/11 风格）

    // === 开始菜单 ===
    val startMenuWidth: Dp,
    val startMenuColor: Color,
    val startMenuAlpha: Float,
    val startMenuShape: Shape,

    // === 窗口 ===
    val windowChrome: WindowChrome,
    val windowTitleBarColor: Color,
    val windowTitleBarTextColor: Color,
    val windowBackgroundColor: Color,
    val windowBorderColor: Color,
    val windowBorderWidth: Dp,
    val windowCornerSize: Dp,
    val windowTitleBarHeight: Dp,
    val windowControlButtonsOnLeft: Boolean,   // Win95/XP/7 在右，无所谓；这里保留兼容字段

    // === 控件 / 按钮 ===
    val buttonBackgroundColor: Color,
    val buttonTextColor: Color,
    val buttonCornerSize: Dp,
    val accentColor: Color,

    // === 字体 ===
    val fontFamily: FontFamily,
    val fontSizeSmall: androidx.compose.ui.unit.TextUnit,
    val fontSizeBody: androidx.compose.ui.unit.TextUnit,
    val fontSizeTitle: androidx.compose.ui.unit.TextUnit,
    val fontWeightTitle: FontWeight,

    // === 启动音效（assets 路径，可为空） ===
    val startupSoundAsset: String?,

    // === 整体氛围 ===
    val isDark: Boolean
) {
    val windowShape: Shape
        get() = when (windowChrome) {
            WindowChrome.CLASSIC -> RectangleShape
            WindowChrome.LUNA   -> RoundedCornerShape(8.dp)
            WindowChrome.AERO   -> RoundedCornerShape(8.dp)
            WindowChrome.FLAT   -> RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
            WindowChrome.MICA   -> RoundedCornerShape(8.dp)
        }

    val buttonShape: Shape
        get() = if (windowChrome == WindowChrome.CLASSIC) RectangleShape
                else RoundedCornerShape(buttonCornerSize)

    companion object {
        val Default = Themes.Win11
    }
}

/**
 * 主题工厂：5 个 Windows 主题的具体定义。
 *
 * 每个主题的视觉特征都尽量贴近原版：
 * - Win95：灰色 #C0C0C0、方角、粗边框、Tahoma/Sans 字体
 * - XP：Luna 蓝色渐变标题栏、绿色草地壁纸、QEMU 字体
 * - Win7：Aero 玻璃、半透明、Harmony 字体
 * - Win10：扁平、深色或亮色、左对齐任务栏
 * - Win11：Mica、大圆角、居中任务栏
 */
object Themes {

    val Win95 = WinTheme(
        variant = WindowsVariant.WIN95,
        displayName = "Windows 95",
        wallpaperAsset = "wallpapers/win95.png",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        taskbarColor = Color(0xFFC0C0C0),
        taskbarAlpha = 1.0f,
        taskbarHeight = 36.dp,
        taskbarStartButtonColor = Color(0xFF808080),
        taskbarIconColor = Color.Black,
        taskbarClockColor = Color.Black,
        startButtonLabel = "Start",
        startMenuWidth = 240.dp,
        startMenuColor = Color(0xFFC0C0C0),
        startMenuAlpha = 1.0f,
        startMenuShape = RectangleShape,
        windowChrome = WindowChrome.CLASSIC,
        windowTitleBarColor = Color(0xFF000080),    // 经典海军蓝
        windowTitleBarTextColor = Color.White,
        windowBackgroundColor = Color(0xFFC0C0C0),
        windowBorderColor = Color(0xFF808080),
        windowBorderWidth = 2.dp,
        windowCornerSize = 0.dp,
        windowTitleBarHeight = 24.dp,
        windowControlButtonsOnLeft = false,
        buttonBackgroundColor = Color(0xFFC0C0C0),
        buttonTextColor = Color.Black,
        buttonCornerSize = 0.dp,
        accentColor = Color(0xFF000080),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 11.sp,
        fontSizeBody = 12.sp,
        fontSizeTitle = 14.sp,
        fontWeightTitle = FontWeight.Bold,
        startupSoundAsset = "sounds/win95.mp3",
        isDark = false
    )

    val WinXP = WinTheme(
        variant = WindowsVariant.WIN_XP,
        displayName = "Windows XP",
        wallpaperAsset = "wallpapers/winxp_bliss.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        taskbarColor = Color(0xFF245EDB),
        taskbarAlpha = 1.0f,
        taskbarHeight = 36.dp,
        taskbarStartButtonColor = Color(0xFF2D7A3E),
        taskbarIconColor = Color.White,
        taskbarClockColor = Color.White,
        startButtonLabel = "start",
        startMenuWidth = 280.dp,
        startMenuColor = Color(0xFFFFFFFF),
        startMenuAlpha = 1.0f,
        startMenuShape = RoundedCornerShape(8.dp),
        windowChrome = WindowChrome.LUNA,
        windowTitleBarColor = Color(0xFF0058E6),
        windowTitleBarTextColor = Color.White,
        windowBackgroundColor = Color(0xFFECE9D8),
        windowBorderColor = Color(0xFF0058E6),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 28.dp,
        windowControlButtonsOnLeft = false,
        buttonBackgroundColor = Color(0xFFECE9D8),
        buttonTextColor = Color.Black,
        buttonCornerSize = 4.dp,
        accentColor = Color(0xFF0058E6),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 11.sp,
        fontSizeBody = 12.sp,
        fontSizeTitle = 14.sp,
        fontWeightTitle = FontWeight.Bold,
        startupSoundAsset = "sounds/winxp.mp3",
        isDark = false
    )

    val Win7 = WinTheme(
        variant = WindowsVariant.WIN7,
        displayName = "Windows 7",
        wallpaperAsset = "wallpapers/win7.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        taskbarColor = Color(0xFF1F1F1F),
        taskbarAlpha = 0.85f,
        taskbarHeight = 40.dp,
        taskbarStartButtonColor = Color(0xFF00BCF2),
        taskbarIconColor = Color.White,
        taskbarClockColor = Color.White,
        startButtonLabel = null,
        startMenuWidth = 320.dp,
        startMenuColor = Color(0xFF2A2A2A),
        startMenuAlpha = 0.95f,
        startMenuShape = RoundedCornerShape(8.dp),
        windowChrome = WindowChrome.AERO,
        windowTitleBarColor = Color(0xFF9BB5E2),
        windowTitleBarTextColor = Color.Black,
        windowBackgroundColor = Color(0xFFF0F0F0),
        windowBorderColor = Color(0xFF9BB5E2),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 30.dp,
        windowControlButtonsOnLeft = false,
        buttonBackgroundColor = Color(0xFFE1E8F2),
        buttonTextColor = Color.Black,
        buttonCornerSize = 4.dp,
        accentColor = Color(0xFF1BA1E2),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 11.sp,
        fontSizeBody = 13.sp,
        fontSizeTitle = 15.sp,
        fontWeightTitle = FontWeight.SemiBold,
        startupSoundAsset = "sounds/win7.mp3",
        isDark = false
    )

    val Win10 = WinTheme(
        variant = WindowsVariant.WIN10,
        displayName = "Windows 10",
        wallpaperAsset = "wallpapers/win10.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.LEFT,
        taskbarColor = Color(0xFF000000),
        taskbarAlpha = 0.85f,
        taskbarHeight = 40.dp,
        taskbarStartButtonColor = Color(0xFF0078D7),
        taskbarIconColor = Color.White,
        taskbarClockColor = Color.White,
        startButtonLabel = null,
        startMenuWidth = 360.dp,
        startMenuColor = Color(0xFF1F1F1F),
        startMenuAlpha = 0.98f,
        startMenuShape = RectangleShape,
        windowChrome = WindowChrome.FLAT,
        windowTitleBarColor = Color(0xFF000000),
        windowTitleBarTextColor = Color.White,
        windowBackgroundColor = Color(0xFF1F1F1F),
        windowBorderColor = Color(0xFF0078D7),
        windowBorderWidth = 1.dp,
        windowCornerSize = 0.dp,
        windowTitleBarHeight = 32.dp,
        windowControlButtonsOnLeft = false,
        buttonBackgroundColor = Color(0xFF2D2D2D),
        buttonTextColor = Color.White,
        buttonCornerSize = 0.dp,
        accentColor = Color(0xFF0078D7),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 12.sp,
        fontSizeBody = 14.sp,
        fontSizeTitle = 16.sp,
        fontWeightTitle = FontWeight.Medium,
        startupSoundAsset = "sounds/win10.mp3",
        isDark = true
    )

    val Win11 = WinTheme(
        variant = WindowsVariant.WIN11,
        displayName = "Windows 11",
        wallpaperAsset = "wallpapers/win11_bloom.jpg",
        desktopIconTextColor = Color.White,
        desktopIconTextShadow = true,
        taskbarAlignment = TaskbarAlignment.CENTER,
        taskbarColor = Color(0xFFF3F3F3),
        taskbarAlpha = 0.78f,
        taskbarHeight = 48.dp,
        taskbarStartButtonColor = Color(0xFF0067C0),
        taskbarIconColor = Color(0xFF1F1F1F),
        taskbarClockColor = Color(0xFF1F1F1F),
        startButtonLabel = null,
        startMenuWidth = 380.dp,
        startMenuColor = Color(0xFFF5F5F5),
        startMenuAlpha = 0.95f,
        startMenuShape = RoundedCornerShape(12.dp),
        windowChrome = WindowChrome.MICA,
        windowTitleBarColor = Color(0xFFEEEEEE),
        windowTitleBarTextColor = Color(0xFF1F1F1F),
        windowBackgroundColor = Color(0xFFFAFAFA),
        windowBorderColor = Color(0xFFE5E5E5),
        windowBorderWidth = 1.dp,
        windowCornerSize = 8.dp,
        windowTitleBarHeight = 32.dp,
        windowControlButtonsOnLeft = false,
        buttonBackgroundColor = Color(0xFFE9E9E9),
        buttonTextColor = Color(0xFF1F1F1F),
        buttonCornerSize = 4.dp,
        accentColor = Color(0xFF0067C0),
        fontFamily = FontFamily.SansSerif,
        fontSizeSmall = 12.sp,
        fontSizeBody = 14.sp,
        fontSizeTitle = 16.sp,
        fontWeightTitle = FontWeight.SemiBold,
        startupSoundAsset = "sounds/win11.mp3",
        isDark = false
    )

    val all: List<WinTheme> = listOf(Win95, WinXP, Win7, Win10, Win11)
}
