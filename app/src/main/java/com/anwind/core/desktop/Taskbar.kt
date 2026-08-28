package com.anwind.core.desktop

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
 * 任务栏 - Win11 风格
 *
 * 视觉重构：
 * - 浮动圆角矩形（与底部留 4dp 间距）
 * - Mica/Aero 半透明材质 + 阴影
 * - **左侧紧凑分布**：Start + 搜索 + 浏览器/文件管理器/设置 等固定应用
 * - 中间：其他运行中窗口
 * - 右侧系统托盘：wifi/音量/电池/时钟
 *
 * 点击行为（用户要求 #5）：
 * - 点击时钟 → 打开 CalendarFlyout
 * - 点击 wifi/音量/电池 组 → 打开 QuickSettingsPanel
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
        val floatingWidth = maxWidth * 0.95f  // 居中浮动的宽度
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
                LeftAlignedTaskbar(
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
private fun LeftAlignedTaskbar(
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

    Box(modifier = Modifier.fillMaxSize()) {
        // ===== 左侧：Start + 搜索 + 浏览器/文件管理器/设置 等固定应用 =====
        Row(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            StartButton(theme = theme, isOpen = startMenuOpen, onClick = onStartClick)
            Spacer(Modifier.width(4.dp))

            SearchButton(theme = theme)
            Spacer(Modifier.width(4.dp))

            // 仅显示固定在任务栏的核心三件套：浏览器/文件管理器/设置
            // 紧凑分布，符合 Win11 任务栏视觉
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

            // 分隔线（仅在同时有运行中非固定窗口时显示）
            val pinnedIds = pinnedApps.map { it.id }.toSet()
            val runningOnly = runningWindows.filter { it.appId !in pinnedIds }
            if (runningOnly.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(theme.taskbarIconColor.copy(alpha = 0.2f))
                )
                Spacer(Modifier.width(4.dp))
            }

            // 其他运行中的窗口（按打开顺序）
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
                    Spacer(Modifier.width(4.dp))
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

@Composable
private fun SearchButton(theme: WinTheme) {
    Box(
        modifier = Modifier
            .size(theme.taskbarHeight - 10.dp)
            .clip(RoundedCornerShape(50))
            .clickable {
                // 搜索按钮：暂时只是视觉占位
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "搜索",
            tint = theme.taskbarIconColor,
            modifier = Modifier.size(16.dp)
        )
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
