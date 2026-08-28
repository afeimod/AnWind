package com.anwind.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务栏 - Win11 真实风格
 *
 * v2.9 更新：
 * - 高度可调（个性化设置 36..80dp，通过 taskbarHeight 参数传入，图标随高度自动缩放）
 * - 布局重构：主簇（开始+搜索+固定+运行任务）+ 系统托盘在同一水平流式布局中，
 *   两侧弹性空间夹持 —— 任务少时整体居中、托盘靠近主簇；
 *   打开的任务增多时运行任务区自动占满中间弹性区（可横向滑动），
 *   托盘随之往右移动，彻底避免窄屏下托盘与任务图标重叠。
 * - 时钟支持 12/24 小时制（跟随时间与语言设置）
 */
@Composable
fun Taskbar(
    theme: WinTheme,
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    showSeconds: Boolean = false,
    timeFormat24h: Boolean = true,
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
        // 窄屏（手机竖屏）阈值：宽度 < 600.dp 视为窄屏
        // 窄屏下隐藏任务栏搜索条 + 紧凑分隔线，腾出空间给运行任务与系统托盘
        val isNarrow = maxWidth < 600.dp

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
                    .height(taskbarHeight)
                    .shadow(8.dp, RoundedCornerShape(taskbarHeight.value / 2f))
                    .clip(RoundedCornerShape(taskbarHeight.value / 2f))
                    .background(taskbarColor)
            ) {
                CenteredTaskbar(
                    theme = theme,
                    taskbarHeight = taskbarHeight,
                    startMenuOpen = startMenuOpen,
                    onStartClick = onStartClick,
                    onOpenCalendar = onOpenCalendar,
                    onOpenQuickSettings = onOpenQuickSettings,
                    tick = tick,
                    showSeconds = showSeconds,
                    timeFormat24h = timeFormat24h,
                    isNarrow = isNarrow
                )
            }
        }
    }
}

@Composable
private fun CenteredTaskbar(
    theme: WinTheme,
    taskbarHeight: Dp,
    startMenuOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    isNarrow: Boolean
) {
    val wm = remember { WindowManager.get() }
    val runningWindows = remember(tick, theme) { wm.windows }

    var searchText by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    // ===== 主簇 + 系统托盘的流式布局 =====
    // [左弹性] 开始 + 搜索 + 固定图标 + 运行任务(中间弹性区,可滑动) [间距] 系统托盘 [右弹性]
    // 任务增多 → 中间弹性区扩张 → 托盘往右移动（用户要求的行为）
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 左侧弹性空间：把主簇推向中间
        Spacer(Modifier.weight(1f))

        // Start 按钮
        StartButton(theme = theme, height = taskbarHeight, isOpen = startMenuOpen, onClick = onStartClick)

        if (!isNarrow) {
            Spacer(Modifier.width(2.dp))

            // 搜索条（药丸形，未激活时显示图标 + "搜索"，激活时变宽接受输入）
            PillSearchBar(
                theme = theme,
                height = taskbarHeight,
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
        }

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
                height = taskbarHeight,
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

        // ===== 运行中的窗口（非固定应用）：中间弹性区，任务多时可横向滑动 =====
        // weight(2f, fill=false)：任务少时只占内容实际宽度（托盘紧随其后），
        // 任务多时最多占据中间弹性区全部剩余空间并变为可滚动
        val pinnedIds = pinnedApps.map { it.id }.toSet()
        val runningOnly = runningWindows.filter { it.appId !in pinnedIds }
        Box(
            modifier = Modifier
                .weight(2f, fill = false)
                .horizontalScroll(rememberScrollState())
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
                                height = taskbarHeight,
                                isRunning = true,
                                isActive = w.isVisible && wm.topWindow()?.id == w.id,
                                onClick = { wm.taskbarClick(w.id) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(6.dp))

        // ===== 右侧系统托盘（可点击打开 flyout），随任务数量右移 =====
        SystemTray(
            theme = theme,
            height = taskbarHeight,
            tick = tick,
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings
        )

        // 右侧弹性空间：与左侧对称，任务增多时收缩为 0
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun StartButton(theme: WinTheme, height: Dp, isOpen: Boolean, onClick: () -> Unit) {
    val bgColor = if (isOpen) theme.accentColor.copy(alpha = 0.3f) else Color.Transparent
    // Win 徽标色块随任务栏高度缩放
    val logoUnit = (height.value * 0.15f).dp.coerceIn(5.dp, 11.dp)
    Box(
        modifier = Modifier
            .size(height - 10.dp)
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
                        .size(logoUnit)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFFF25022))
                )
                Box(
                    Modifier
                        .size(logoUnit)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFF7FBA00))
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                Box(
                    Modifier
                        .size(logoUnit)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFF00A4EF))
                )
                Box(
                    Modifier
                        .size(logoUnit)
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
    height: Dp,
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
            .height(height - 12.dp)
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
    height: Dp,
    isRunning: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    // 图标尺寸随任务栏高度缩放（48dp 任务栏 ≈ 22dp 图标）
    val iconSize = (height.value * 0.46f).dp.coerceIn(16.dp, 34.dp)
    val bgColor = if (isActive) theme.taskbarIconColor.copy(alpha = 0.15f)
                  else if (isRunning) theme.taskbarIconColor.copy(alpha = 0.06f)
                  else Color.Transparent
    Box(
        modifier = Modifier
            .size(height - 10.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        IconPainter(iconAsset, size = iconSize)
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
    height: Dp,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit
) {
    val quickInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val calendarInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 托盘图标随任务栏高度轻微缩放
    val trayIconSize = (height.value * 0.31f).dp.coerceIn(13.dp, 20.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Wifi, contentDescription = "WiFi", tint = theme.taskbarClockColor, modifier = Modifier.size(trayIconSize))
            Icon(Icons.Default.VolumeUp, contentDescription = "音量", tint = theme.taskbarClockColor, modifier = Modifier.size(trayIconSize))
            Icon(Icons.Default.BatteryFull, contentDescription = "电池", tint = theme.taskbarClockColor, modifier = Modifier.size(trayIconSize))
        }

        Spacer(Modifier.width(2.dp))

        // 时钟组（点击触发 Calendar）；支持 12/24 小时制
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
            val timePattern = when {
                showSeconds && timeFormat24h -> "HH:mm:ss"
                showSeconds -> "hh:mm:ss a"
                timeFormat24h -> "HH:mm"
                else -> "hh:mm a"
            }
            val timeFormat = SimpleDateFormat(timePattern, Locale.getDefault())
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
