package com.anwind.core.desktop

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * 任务栏：开始按钮 + 搜索 + 固定应用 + 运行中窗口 + 系统托盘 + 时钟。
 *
 * 根据主题的 taskbarAlignment 决定整体布局（Win10 左 / Win11 中）。
 */
@Composable
fun Taskbar(
    theme: WinTheme,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    var tick by remember { mutableStateOf(0L) }

    // 每秒刷新时钟
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }

    val bgColor = theme.taskbarColor.copy(alpha = theme.taskbarAlpha)

    Box(
        modifier = modifier
            .background(bgColor)
    ) {
        when (theme.taskbarAlignment) {
            TaskbarAlignment.LEFT -> LeftAlignedTaskbar(theme, startMenuOpen, onStartClick, tick)
            TaskbarAlignment.CENTER -> CenterAlignedTaskbar(theme, startMenuOpen, onStartClick, tick)
        }
    }
}

@Composable
private fun LeftAlignedTaskbar(
    theme: WinTheme,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    tick: Long
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick, theme) { wm.windows }  // 注意：wm 变更时通过外层 recompose 传入

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 开始按钮
        StartButton(theme = theme, isOpen = startMenuOpen, onClick = onStartClick)

        Spacer(Modifier.width(4.dp))

        // 搜索框（简化版）
        SearchBox(theme = theme)

        Spacer(Modifier.width(4.dp))

        // 固定到任务栏的应用
        val pinnedApps = remember { AppRegistry.taskbarApps() }
        pinnedApps.forEach { app ->
            TaskbarAppIcon(
                iconAsset = app.iconAsset,
                theme = theme,
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
            Spacer(Modifier.width(2.dp))
        }

        // 运行中的窗口（不包括已固定的）
        val pinnedIds = pinnedApps.map { it.id }.toSet()
        val runningOnly = runningWindows.filter { it.appId !in pinnedIds }
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
                Spacer(Modifier.width(2.dp))
            }
        }

        Spacer(Modifier.weight(1f))

        // 系统托盘 + 时钟
        SystemTray(theme = theme, tick = tick)
    }
}

@Composable
private fun CenterAlignedTaskbar(
    theme: WinTheme,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    tick: Long
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick, theme) { wm.windows }

    Box(modifier = Modifier.fillMaxSize()) {
        // 中间区域：开始 + 固定应用 + 运行中
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StartButton(theme = theme, isOpen = startMenuOpen, onClick = onStartClick)
            Spacer(Modifier.width(8.dp))

            val pinnedApps = remember { AppRegistry.taskbarApps() }
            pinnedApps.forEach { app ->
                TaskbarAppIcon(
                    iconAsset = app.iconAsset,
                    theme = theme,
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
                Spacer(Modifier.width(4.dp))
            }

            // 分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(theme.taskbarIconColor.copy(alpha = 0.3f))
            )
            Spacer(Modifier.width(4.dp))

            // 运行中（非固定）
            val pinnedIds = pinnedApps.map { it.id }.toSet()
            val runningOnly = runningWindows.filter { it.appId !in pinnedIds }
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

        // 右侧系统托盘
        SystemTray(
            theme = theme,
            tick = tick,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun StartButton(theme: WinTheme, isOpen: Boolean, onClick: () -> Unit) {
    val bgColor = if (isOpen) theme.accentColor.copy(alpha = 0.3f) else Color.Transparent
    Box(
        modifier = Modifier
            .size(theme.taskbarHeight - 8.dp)
            .background(bgColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Win 徽标：4 个色块
        Row {
            Box(Modifier.size(8.dp).background(Color(0xFFF25022)))  // 红
            Box(Modifier.size(8.dp).background(Color(0xFF7FBA00)))  // 绿
        }
        Row {
            Box(Modifier.size(8.dp).background(Color(0xFF00A4EF)))  // 蓝
            Box(Modifier.size(8.dp).background(Color(0xFFFFB900)))  // 黄
        }
    }
}

@Composable
private fun SearchBox(theme: WinTheme) {
    Row(
        modifier = Modifier
            .height(theme.taskbarHeight - 12.dp)
            .width(180.dp)
            .background(theme.taskbarIconColor.copy(alpha = 0.08f), RoundedCornerShape(theme.taskbarHeight.value / 2f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Search",
            tint = theme.taskbarIconColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "搜索",
            color = theme.taskbarIconColor.copy(alpha = 0.6f),
            fontSize = theme.fontSizeBody
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
                  else if (isRunning) theme.taskbarIconColor.copy(alpha = 0.05f)
                  else Color.Transparent
    Box(
        modifier = Modifier
            .size(theme.taskbarHeight - 8.dp)
            .background(bgColor, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        IconPainter(iconAsset, size = 24.dp)
        // 运行中标记
        if (isRunning) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .width(if (isActive) 16.dp else 6.dp)
                    .height(2.dp)
                    .background(theme.taskbarIconColor)
            )
        }
    }
}

@Composable
private fun SystemTray(theme: WinTheme, tick: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(end = 12.dp, start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Wifi, contentDescription = "WiFi", tint = theme.taskbarClockColor, modifier = Modifier.size(16.dp))
        Icon(Icons.Default.VolumeUp, contentDescription = "Volume", tint = theme.taskbarClockColor, modifier = Modifier.size(16.dp))
        Icon(Icons.Default.BatteryFull, contentDescription = "Battery", tint = theme.taskbarClockColor, modifier = Modifier.size(16.dp))

        // 时钟
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeStr = timeFormat.format(Date(tick))
        val dateStr = dateFormat.format(Date(tick))
        Column(horizontalAlignment = Alignment.End) {
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
