package com.anwind.apps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.anwind.AnWindApp
import com.anwind.core.desktop.IconPainter
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.Themes
import com.anwind.core.theme.WindowsVariant
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val SettingsApp = AppDef(
    id = "settings",
    displayName = "设置",
    iconAsset = "icons/settings.png",
    launchMode = LaunchMode.FULLSCREEN,
    defaultWidth = 800.dp,
    defaultHeight = 560.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    SettingsContent(scope)
}

@Composable
private fun SettingsContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    var activeSection by remember {
        mutableStateOf(scope.windowState.launchArgs["section"] ?: "theme")
    }

    Row(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {
        // 左侧导航
        Column(
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .background(theme.buttonBackgroundColor.copy(alpha = 0.3f))
                .padding(12.dp)
        ) {
            Text(
                text = "⚙️ 设置",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            SettingsNavItem("🎨", "主题", active = activeSection == "theme") { activeSection = "theme" }
            SettingsNavItem("🖼️", "壁纸", active = activeSection == "wallpaper") { activeSection = "wallpaper" }
            SettingsNavItem("🖥️", "显示", active = activeSection == "display") { activeSection = "display" }
            SettingsNavItem("✨", "个性化", active = activeSection == "personal") { activeSection = "personal" }
            SettingsNavItem("🔊", "声音", active = activeSection == "sound") { activeSection = "sound" }
            SettingsNavItem("🖥️", "任务栏", active = activeSection == "taskbar") { activeSection = "taskbar" }
            SettingsNavItem("🌐", "浏览器", active = activeSection == "browser") { activeSection = "browser" }
            SettingsNavItem("ℹ️", "关于", active = activeSection == "about") { activeSection = "about" }
        }

        // 右侧内容
        Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
            when (activeSection) {
                "theme" -> ThemeSection()
                "wallpaper" -> WallpaperSection()
                "display" -> DisplaySection()
                "personal" -> PersonalSection()
                "sound" -> SoundSection()
                "taskbar" -> TaskbarSection()
                "browser" -> BrowserSection()
                "about" -> AboutSection()
            }
        }
    }
}

@Composable
private fun SettingsNavItem(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (active) theme.accentColor.copy(alpha = 0.2f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun ThemeSection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    var currentVariant by remember { mutableStateOf(theme.variant) }

    Column {
        Text(
            "主题",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "选择一个 Windows 主题来改变整个系统的外观",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(20.dp))

        // 5 个主题卡片
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Themes.all.forEach { themeOption ->
                ThemeCard(
                    themeOption = themeOption,
                    isSelected = currentVariant == themeOption.variant,
                    onClick = {
                        currentVariant = themeOption.variant
                        scope0.launch { app.themeManager.setTheme(themeOption.variant) }
                    }
                )
            }
        }
    }
}

@Composable
private fun ThemeCard(themeOption: com.anwind.core.theme.WinTheme, isSelected: Boolean, onClick: () -> Unit) {
    val currentTheme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isSelected) currentTheme.accentColor.copy(alpha = 0.15f) else currentTheme.buttonBackgroundColor.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) currentTheme.accentColor else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 缩略色块
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(20.dp).background(themeOption.taskbarColor))
            Box(Modifier.size(20.dp).background(themeOption.windowTitleBarColor))
            Box(Modifier.size(20.dp).background(themeOption.accentColor))
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                themeOption.displayName,
                color = if (currentTheme.isDark) Color.White else Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when (themeOption.variant) {
                    WindowsVariant.WIN95  -> "经典灰色界面 · 方角窗口 · 左侧任务栏"
                    WindowsVariant.WIN_XP -> "Luna 蓝色主题 · 圆角窗口 · 草地壁纸"
                    WindowsVariant.WIN7   -> "Aero 玻璃效果 · 半透明任务栏"
                    WindowsVariant.WIN10  -> "扁平化深色 · 方角窗口"
                    WindowsVariant.WIN11  -> "Mica 材质 · 居中任务栏 · 大圆角"
                },
                color = if (currentTheme.isDark) Color.White else Color.Black,
                fontSize = 11.sp
            )
        }
        if (isSelected) {
            Icon(Icons.Default.Check, "Selected", tint = currentTheme.accentColor)
        }
    }
}

@Composable
private fun WallpaperSection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val customWallpaper by app.settingsStore.customWallpaper.collectAsState(initial = null)

    // 从图库选择图片作为壁纸（OpenDocument 授予可持久化的读取权限）
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // 持久化读取权限，重启后仍可使用该壁纸
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope0.launch { app.settingsStore.setCustomWallpaper(uri.toString()) }
        }
    }

    Column {
        Text("壁纸", color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text("当前壁纸：${customWallpaper ?: "使用主题默认"}",
            color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { pickWallpaper.launch(arrayOf("image/*")) }) {
                Text("从图库选择壁纸")
            }
            if (customWallpaper != null) {
                Button(onClick = {
                    scope0.launch { app.settingsStore.setCustomWallpaper(null) }
                }) { Text("恢复默认壁纸") }
            }
        }
    }
}

/**
 * 显示设置：桌面图标大小、时钟显示秒、任务栏自动隐藏。
 */
@Composable
private fun DisplaySection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
    val showSeconds by app.settingsStore.showSeconds.collectAsState(initial = false)
    val taskbarAutohide by app.settingsStore.taskbarAutohide.collectAsState(initial = false)
    val uiScale by app.settingsStore.uiScale.collectAsState(initial = 1f)
    val orientation by app.settingsStore.displayOrientation.collectAsState(initial = "auto")

    Column {
        Text("显示", color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        // 桌面图标大小
        Text("桌面图标大小", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = iconSize,
                onValueChange = {
                    scope0.launch { app.settingsStore.setIconSize(it) }
                },
                valueRange = 28f..72f
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${iconSize.roundToInt()}dp",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(16.dp))

        // UI 缩放（整体缩放所有界面元素和文字）
        Text("UI 缩放", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = uiScale,
                onValueChange = {
                    scope0.launch { app.settingsStore.setUiScale(it) }
                },
                valueRange = 0.6f..1.8f
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${(uiScale * 100).roundToInt()}%",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp
            )
        }
        Text(
            "缩放整个桌面的图标、文字和窗口大小。默认 100%，调小后界面更紧凑、可显示更多内容。",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))

        // 显示方向
        Text("显示方向", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrientationOption("自动", orientation == "auto") {
                scope0.launch { app.settingsStore.setDisplayOrientation("auto") }
            }
            OrientationOption("竖屏", orientation == "portrait") {
                scope0.launch { app.settingsStore.setDisplayOrientation("portrait") }
            }
            OrientationOption("横屏", orientation == "landscape") {
                scope0.launch { app.settingsStore.setDisplayOrientation("landscape") }
            }
        }
        Spacer(Modifier.height(16.dp))

        // 时钟显示秒
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("时钟显示秒", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = showSeconds,
                onCheckedChange = { v -> scope0.launch { app.settingsStore.setShowSeconds(v) } }
            )
        }
        Spacer(Modifier.height(8.dp))

        // 任务栏自动隐藏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("任务栏自动隐藏", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = taskbarAutohide,
                onCheckedChange = { v -> scope0.launch { app.settingsStore.setTaskbarAutohide(v) } }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "开启后，指针移到底部边缘时任务栏自动出现，离开后自动隐藏。",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp
        )
    }
}

/**
 * 单选项按钮（用于显示方向、浏览器模式等二/三选一设置）。
 */
@Composable
private fun OrientationOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) theme.accentColor.copy(alpha = 0.2f) else theme.buttonBackgroundColor.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) theme.accentColor else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 13.sp
        )
    }
}

/**
 * 个性化设置：浏览器默认模式、浏览器主页等。
 */
@Composable
private fun PersonalSection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val uaMode by app.settingsStore.browserUaMode.collectAsState(initial = "desktop")
    val home by app.settingsStore.defaultBrowserHome.collectAsState(initial = "https://www.bing.com")
    var homeInput by remember { mutableStateOf(home) }

    Column {
        Text("个性化", color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("自定义浏览与桌面体验", color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        // 浏览器默认模式
        Text("浏览器默认模式", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrientationOption("桌面模式", uaMode == "desktop") {
                scope0.launch { app.settingsStore.setBrowserUaMode("desktop") }
            }
            OrientationOption("手机模式", uaMode == "mobile") {
                scope0.launch { app.settingsStore.setBrowserUaMode("mobile") }
            }
        }
        Text(
            "桌面模式显示完整 PC 版网页，手机模式显示移动版网页。可在浏览器工具栏随时切换。",
            color = if (theme.isDark) Color.White else Color.Black, fontSize = 11.sp
        )
        Spacer(Modifier.height(16.dp))

        // 浏览器主页
        Text("浏览器主页网址", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = homeInput,
            onValueChange = { homeInput = it },
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { scope0.launch { app.settingsStore.setDefaultBrowserHome(homeInput) } }) {
            Text("保存")
        }
    }
}

@Composable
private fun SoundSection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val soundEnabled by app.settingsStore.soundEnabled.collectAsState(initial = true)
    val scope0 = rememberCoroutineScope()

    Column {
        Text("声音", color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启动音效", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = soundEnabled,
                onCheckedChange = { v -> scope0.launch { app.settingsStore.setSoundEnabled(v) } }
            )
        }
    }
}

@Composable
private fun TaskbarSection() {
    val theme = LocalWinTheme.current
    Text("任务栏", color = if (theme.isDark) Color.White else Color.Black,
        fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(20.dp))
    Text("任务栏样式跟随当前主题：${theme.displayName}",
        color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
    Spacer(Modifier.height(8.dp))
    Text("对齐方式：${if (theme.taskbarAlignment == com.anwind.core.theme.TaskbarAlignment.CENTER) "居中" else "左侧"}",
        color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
}

@Composable
private fun BrowserSection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val home by app.settingsStore.defaultBrowserHome.collectAsState(initial = "https://www.bing.com")
    var input by remember { mutableStateOf(home) }
    val scope0 = rememberCoroutineScope()

    Column {
        Text("浏览器", color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text("主页网址", color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { scope0.launch { app.settingsStore.setDefaultBrowserHome(input) } }) {
            Text("保存")
        }
    }
}

@Composable
private fun AboutSection() {
    val theme = LocalWinTheme.current
    Column {
        Text("关于 AnWind", color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        AboutRow("应用名称", "AnWind")
        AboutRow("版本", "1.0.0")
        AboutRow("包名", "com.anwind")
        AboutRow("当前主题", theme.displayName)
        AboutRow("minSdk", "24 (Android 7.0)")
        AboutRow("targetSdk", "34 (Android 14)")
        AboutRow("项目", "GitHub: AnWind")
        AboutRow("License", "MIT")
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
        Text(value, color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}
