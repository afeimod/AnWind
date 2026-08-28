package com.anwind.core.desktop

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.TaskbarAlignment
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务栏 - Win11 真实风格
 *
 * 视觉重构（参考 hyperdroid APK 截图）：
 * - 浮动圆角矩形（与底部留 4dp 间距）
 * - Mica/Aero 半透明材质 + 阴影
 * - **居中簇布局**：Start + 搜索条（药丸形）+ 浏览器/文件管理器/设置 + 运行中窗口
 * - 右侧系统托盘：wifi/音量/电池/时钟（与左侧簇用空白 spacer 分开）
 *
 * 点击行为：
 * - 点击时钟 → 打开 CalendarFlyout
 * - 点击 wifi/音量/电池 组 → 打开 QuickSettingsPanel
 * - 点击搜索条 → 直接输入回车跳转浏览器
 */
@Composable
fun Taskbar(
    theme: WinTheme,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    showSeconds: Boolean = false,
    modifier: Modifier = Modifier
) {
    var tick by remember { mutableStateOf(0L) }

    // 每秒刷新时钟
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    BoxWithConstraints(
        modifier = modifier
    ) {
        val taskbarColor = theme.taskbarColor.copy(alpha = theme.taskbarAlpha)
        val floatingWidth = maxWidth * 0.96f
        val bottomPadding = 4.dp

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .width(floatingWidth)
                    .height(theme.taskbarHeight)
                    .shadow(8.dp, RoundedCornerShape(theme.taskbarHeight.value / 2f))
                    .clip(RoundedCornerShape(theme.taskbarHeight.value / 2f))
                    .background(taskbarColor)
            ) {
                CenteredTaskbar(
                    theme = theme,
                    startMenuOpen = startMenuOpen,
                    onStartClick = onStartClick,
                    onOpenCalendar = onOpenCalendar,
                    onOpenQuickSettings = onOpenQuickSettings,
                    tick = tick,
                    showSeconds = showSeconds
                )
            }
        }
    }
}

@Composable
private fun CenteredTaskbar(
    theme: WinTheme,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    tick: Long,
    showSeconds: Boolean
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick, theme) { wm.windows }

    var searchText by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    Box(modifier = Modifier.fillMaxSize()) {
        // ===== 居中簇：Start + 搜索条 + 浏览器/文件管理器/设置 + 运行中窗口 =====
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Start 按钮
            StartButton(theme = theme, isOpen = startMenuOpen, onClick = onStartClick)
            Spacer(Modifier.width(2.dp))

            // 搜索条（药丸形，未激活时显示图标 + "搜索"，激活时变宽接受输入）
            PillSearchBar(
                theme = theme,
                text = searchText,
                active = searchActive,
                onActiveChange = { searchActive = it },
                onTextChange = { searchText = it },
                onSubmit = {
                    val q = searchText.trim()
                    if (q.isNotEmpty()) {
                        // 用 normalizeUrl 处理：网址直接打开，搜索关键词走 Bing
                        val target = if (q.contains(".") && !q.contains(" ")) {
                            if (q.startsWith("http")) q else "https://$q"
                        } else {
                            "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
                        }
                        // 直接打开新浏览器窗口，让 launchArgs["url"] 自动加载
                        wm.open(
                            appId = "browser",
                            title = "浏览器",
                            launchMode = com.anwind.core.window.LaunchMode.FLOATING,
                            launchArgs = mapOf("url" to target),
                            initialWidth = 980,
                            initialHeight = 640
                        )
                        searchText = ""
                        searchActive = false
                        keyboard?.hide()
                    }
                }
            )

            Spacer(Modifier.width(2.dp))

            // 紧凑分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(theme.taskbarIconColor.copy(alpha = 0.15f))
            )

            Spacer(Modifier.width(2.dp))

            // 仅显示固定在任务栏的核心三件套：浏览器/文件管理器/设置
            val pinnedApps = remember {
                AppRegistry.taskbarApps().filter { app ->
                    app.id in setOf("browser", "file_explorer", "settings")
                }
            }
            pinnedApps.forEach { app ->
                val isRunning = wm.windowsForApp(app.id).isNotEmpty()
                val isActive = isRunning && wm.topWindow()?.let { it.appId == app.id && it.isVisible } == true
                TaskbarAppIcon(
                    iconAsset = app.iconAsset,
                    theme = theme,
                    isRunning = isRunning,
                    isActive = isActive,
                    onClick = {
                        val existing = wm.windowsForApp(app.id)
                        if (existing.isEmpty()) {
                            wm.open(
                                appId = app.id,
                                title = app.displayName,
                                launchMode = app.launchMode,
                                initialWidth = app.defaultWidth.value.toInt(),
                                initialHeight = app.defaultHeight.value.toInt()
                            )
                        } else {
                            wm.taskbarClick(existing.first().id)
                        }
                    }
                )
            }

            // 其他运行中的窗口（非固定应用）
            val pinnedIds = pinnedApps.map { it.id }.toSet()
            val runningOnly = runningWindows.filter { it.appId !in pinnedIds }
            if (runningOnly.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(theme.taskbarIconColor.copy(alpha = 0.15f))
                )
                Spacer(Modifier.width(2.dp))
                runningOnly.forEach { w ->
                    val app = AppRegistry.get(w.appId)
                    if (app != null) {
                        TaskbarAppIcon(
                            iconAsset = app.iconAsset,
                            theme = theme,
                            isRunning = true,
                            isActive = w.isVisible && wm.topWindow()?.id == w.id,
                            onClick = { wm.taskbarClick(w.id) }
                        )
                    }
                }
            }
        }

        // ===== 右侧系统托盘（可点击打开 flyout） =====
        SystemTray(
            theme = theme,
            tick = tick,
            showSeconds = showSeconds,
            onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun StartButton(theme: WinTheme, isOpen: Boolean, onClick: () -> Unit) {
    val bgColor = if (isOpen) theme.accentColor.copy(alpha = 0.3f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(theme.taskbarHeight - 10.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Win 徽标：4 个色块
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFFF25022))
                )
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFF7FBA00))
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFF00A4EF))
                )
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFFFFB900))
                )
            }
        }
    }
}

/**
 * 药丸形搜索条：未激活时宽 60dp（仅显示放大镜 + 文字），激活时宽 220dp 接受输入。
 * 参考真实 Win11 任务栏搜索条视觉。
 */
@Composable
private fun PillSearchBar(
    theme: WinTheme,
    text: String,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val width = if (active) 220.dp else 80.dp
    Row(
        modifier = Modifier
            .width(width)
            .height(theme.taskbarHeight - 12.dp)
            .clip(RoundedCornerShape(50))
            .background(theme.taskbarIconColor.copy(alpha = if (active) 0.12f else 0.06f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                onActiveChange(true)
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "搜索",
            tint = theme.taskbarIconColor,
            modifier = Modifier.size(14.dp)
        )
        if (active) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = theme.taskbarClockColor,
                    fontSize = 12.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor)
            )
            if (text.isEmpty()) {
                Text(
                    text = "搜索",
                    color = theme.taskbarIconColor.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        } else {
            Text(
                text = "搜索",
                color = theme.taskbarIconColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
private fun TaskbarAppIcon(
    iconAsset: String,
    theme: WinTheme,
    isRunning: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) theme.taskbarIconColor.copy(alpha = 0.15f)
                  else if (isRunning) theme.taskbarIconColor.copy(alpha = 0.06f)
                  else Color.Transparent
    Box(
        modifier = Modifier
            .size(theme.taskbarHeight - 10.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        IconPainter(iconAsset, size = 22.dp)
        // 运行中标记：底部圆点指示器
        if (isRunning) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp)
                    .size(width = if (isActive) 12.dp else 4.dp, height = 3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(theme.taskbarIconColor.copy(alpha = if (isActive) 1f else 0.6f))
            )
        }
    }
}

/**
 * 系统托盘 - 点击 wifi/电池 组合打开 Quick Settings，点击时钟打开 Calendar
 */
@Composable
private fun SystemTray(
    theme: WinTheme,
    tick: Long,
    showSeconds: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val quickInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val calendarInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Row(
        modifier = modifier
            .padding(end = 12.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 系统图标组（点击触发 QuickSettings）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = quickInteraction,
                    indication = null,
                    onClick = onOpenQuickSettings
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Wifi, contentDescription = "WiFi", tint = theme.taskbarClockColor, modifier = Modifier.size(15.dp))
            Icon(Icons.Default.VolumeUp, contentDescription = "音量", tint = theme.taskbarClockColor, modifier = Modifier.size(15.dp))
            Icon(Icons.Default.BatteryFull, contentDescription = "电池", tint = theme.taskbarClockColor, modifier = Modifier.size(15.dp))
        }

        Spacer(Modifier.width(2.dp))

        // 时钟组（点击触发 Calendar）
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(
                    interactionSource = calendarInteraction,
                    indication = null,
                    onClick = onOpenCalendar
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            val timeFormat = SimpleDateFormat(if (showSeconds) "HH:mm:ss" else "HH:mm", Locale.getDefault())
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeStr = timeFormat.format(Date(tick))
            val dateStr = dateFormat.format(Date(tick))
            Text(
                text = timeStr,
                color = theme.taskbarClockColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal
            )
            Text(
                text = dateStr,
                color = theme.taskbarClockColor,
                fontSize = 10.sp
            )
        }
    }
}
