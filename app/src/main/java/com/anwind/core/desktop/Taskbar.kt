package com.anwind.core.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.AnWindApp
import com.anwind.core.input.keyboardAware
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * 任务栏 - Win11 真实风格
 *
 * v2.11 更新：
 * - 系统托盘（无线/音量/电池/时间）固定在任务栏最右侧（Win11 标准布局）；
 *   布局顺序：开始 → 搜索 → 固定图标 → 运行任务（中间弹性区）→ 系统托盘（最右）
 * - 时钟显示样式可自定义：长按任务栏时间 → 弹出样式设置
 *   （显示模式：数字/表盘/液晶；字号；是否显示日期；排版：两行/单行）
 *
 * v2.10 更新：
 * - 高度可调（个性化设置 36..80dp，通过 taskbarHeight 参数传入，图标随高度自动缩放）
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
    onOpenClockStyle: () -> Unit = {},
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
                    onOpenClockStyle = onOpenClockStyle,
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
    onOpenClockStyle: () -> Unit,
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

    // ===== 托盘时钟样式偏好（v2.10，长按任务栏时间可弹出设置） =====
    val app = AnWindApp.get()
    val clockMode by app.settingsStore.trayClockMode.collectAsState(initial = "digital")
    val clockFontSize by app.settingsStore.trayClockFontSize.collectAsState(initial = 0f)
    val trayShowDate by app.settingsStore.trayShowDate.collectAsState(initial = true)
    val trayLayout by app.settingsStore.trayLayout.collectAsState(initial = "stacked")

    // ===== 布局（v2.11）：开始 → 搜索 → 固定图标 → 运行任务(中间弹性区) → 系统托盘(最右) =====
    // 托盘固定在最右端（Win11 标准），不随任务数量移动；运行任务在中间弹性区，任务多时可横向滑动
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Start 按钮（最左）
        StartButton(theme = theme, height = taskbarHeight, isOpen = startMenuOpen, onClick = onStartClick)

        if (!isNarrow) {
            Spacer(Modifier.width(4.dp))

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

            Spacer(Modifier.width(4.dp))

            // 紧凑分隔线（搜索与固定图标之间）
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(theme.taskbarIconColor.copy(alpha = 0.15f))
            )

            Spacer(Modifier.width(4.dp))
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

        // ===== 运行中的窗口（非固定应用）：中间弹性区，任务少时靠左，任务多时可横向滑动 =====
        // weight(1f)：占满固定图标与系统托盘之间的全部剩余空间；图标在区内靠左排列，
        // 超出宽度后变为可滚动 —— 托盘因此始终稳定贴在最右端。
        val pinnedIds = pinnedApps.map { it.id }.toSet()
        val runningOnly = runningWindows.filter { it.appId !in pinnedIds }
        Box(
            modifier = Modifier
                .weight(1f)
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

        // ===== 系统托盘（v2.11：固定在最右端，不随任务数量移动） =====
        // 紧凑分隔线（运行任务与托盘之间）
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(theme.taskbarIconColor.copy(alpha = 0.15f))
        )

        Spacer(Modifier.width(2.dp))

        SystemTray(
            theme = theme,
            height = taskbarHeight,
            tick = tick,
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            clockMode = clockMode,
            clockFontSize = clockFontSize,
            showDate = trayShowDate,
            trayLayout = trayLayout,
            onOpenCalendar = onOpenCalendar,
            onOpenQuickSettings = onOpenQuickSettings,
            onOpenClockStyle = onOpenClockStyle
        )
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
                modifier = Modifier
                    .weight(1f)
                    .keyboardAware(
                        value = { text },
                        onValue = onTextChange,
                        singleLine = true,
                        onEnter = onSubmit
                    ),
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
 * 系统托盘（v2.11：固定在任务栏最右侧，Win11 标准位置）
 * - 点击 wifi/音量/电池 图标组 → Quick Settings
 * - 点击时钟 → Calendar
 * - 长按时钟 → 托盘时钟样式设置（显示模式/字号/日期/排版）
 * - 时钟显示模式：digital 数字 / clock 表盘 / lcd 液晶
 */
@Composable
private fun SystemTray(
    theme: WinTheme,
    height: Dp,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    clockMode: String,
    clockFontSize: Float,
    showDate: Boolean,
    trayLayout: String,
    onOpenCalendar: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenClockStyle: () -> Unit
) {
    val quickInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
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

        // ===== 时钟组（v2.10 可自定义样式）=====
        TrayClock(
            theme = theme,
            height = height,
            tick = tick,
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            mode = clockMode,
            fontSizePref = clockFontSize,
            showDate = showDate,
            layout = trayLayout,
            onOpenCalendar = onOpenCalendar,
            onOpenClockStyle = onOpenClockStyle
        )
    }
}

/**
 * 托盘时钟（v2.10）：
 * - 点击 → 日历弹窗；长按 → 样式设置弹窗
 * - 数字/液晶模式：时间 + 日期文本（两行或单行排版）
 * - 表盘模式：Canvas 绘制模拟时钟（时针/分针/秒针）
 */
@Composable
private fun TrayClock(
    theme: WinTheme,
    height: Dp,
    tick: Long,
    showSeconds: Boolean,
    timeFormat24h: Boolean,
    mode: String,
    fontSizePref: Float,
    showDate: Boolean,
    layout: String,
    onOpenCalendar: () -> Unit,
    onOpenClockStyle: () -> Unit
) {
    val timePattern = when {
        showSeconds && timeFormat24h -> "HH:mm:ss"
        showSeconds -> "hh:mm:ss a"
        timeFormat24h -> "HH:mm"
        else -> "hh:mm a"
    }
    val timeStr = SimpleDateFormat(timePattern, Locale.getDefault()).format(Date(tick))
    val dateStr = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(tick))

    // 字号：未设置(0)时按任务栏高度自适应
    val timeFontSize = if (fontSizePref >= 8f) fontSizePref.sp
    else (height.value * 0.24f).coerceIn(9f, 13f).sp
    val dateFontSize = (timeFontSize.value - 1f).coerceAtLeast(8f).sp

    // 点击 → 日历；长按 → 时钟样式设置
    val gestureModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(
            onTap = { onOpenCalendar() },
            onLongPress = { onOpenClockStyle() }
        )
    }

    when (mode) {
        // ===== 表盘时钟 =====
        "clock" -> {
            val clockSize = (height.value * 0.52f).dp.coerceIn(18.dp, 30.dp)
            val content: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnalogClockCanvas(
                        size = clockSize,
                        tick = tick,
                        showSeconds = showSeconds,
                        color = theme.taskbarClockColor,
                        accent = theme.accentColor
                    )
                    if (showDate) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = dateStr,
                            color = theme.taskbarClockColor,
                            fontSize = dateFontSize,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(gestureModifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) { content() }
        }
        // ===== 液晶时钟（等宽字体 + 辉光效果） =====
        "lcd" -> {
            val lcdColor = if (theme.isDark) Color(0xFF4AF2A1) else Color(0xFF0B7A4B)
            val lcdStyle = TextStyle(
                color = lcdColor,
                fontSize = timeFontSize,
                fontFamily = FontFamily.Monospace,
                shadow = Shadow(color = lcdColor.copy(alpha = 0.75f), blurRadius = 7f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(gestureModifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showDate && layout == "inline") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = timeStr, style = lcdStyle)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = dateStr,
                            style = lcdStyle.copy(
                                fontSize = dateFontSize,
                                shadow = Shadow(color = lcdColor.copy(alpha = 0.5f), blurRadius = 5f)
                            )
                        )
                    }
                } else if (showDate) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(text = timeStr, style = lcdStyle)
                        Text(
                            text = dateStr,
                            style = lcdStyle.copy(
                                fontSize = dateFontSize,
                                shadow = Shadow(color = lcdColor.copy(alpha = 0.5f), blurRadius = 5f)
                            )
                        )
                    }
                } else {
                    Text(text = timeStr, style = lcdStyle)
                }
            }
        }
        // ===== 数字时钟（默认） =====
        else -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(gestureModifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                if (showDate && layout == "inline") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = timeStr,
                            color = theme.taskbarClockColor,
                            fontSize = timeFontSize,
                            fontWeight = FontWeight.Normal
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = dateStr,
                            color = theme.taskbarClockColor.copy(alpha = 0.75f),
                            fontSize = dateFontSize
                        )
                    }
                } else if (showDate) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = timeStr,
                            color = theme.taskbarClockColor,
                            fontSize = timeFontSize,
                            fontWeight = FontWeight.Normal
                        )
                        Text(
                            text = dateStr,
                            color = theme.taskbarClockColor.copy(alpha = 0.75f),
                            fontSize = dateFontSize
                        )
                    }
                } else {
                    Text(
                        text = timeStr,
                        color = theme.taskbarClockColor,
                        fontSize = timeFontSize,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 表盘时钟（v2.10）：Canvas 绘制圆形表盘 + 时针/分针/秒针
 * （internal：TrayClockSettingsFlyout 预览复用）
 */
@Composable
internal fun AnalogClockCanvas(
    size: Dp,
    tick: Long,
    showSeconds: Boolean,
    color: Color,
    accent: Color
) {
    val cal = remember(tick) { Calendar.getInstance() }
    Canvas(modifier = Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        // 表盘边框
        drawCircle(
            color = color.copy(alpha = 0.9f),
            radius = r,
            center = center,
            style = Stroke(width = r * 0.09f)
        )
        // 12 个刻度
        for (i in 0 until 12) {
            val angle = (Math.PI * 2 * i / 12.0) - Math.PI / 2
            val outer = Offset(
                (center.x + r * 0.82f * cos(angle)).toFloat(),
                (center.y + r * 0.82f * sin(angle)).toFloat()
            )
            val inner = Offset(
                (center.x + r * 0.66f * cos(angle)).toFloat(),
                (center.y + r * 0.66f * sin(angle)).toFloat()
            )
            drawLine(
                color = color.copy(alpha = if (i % 3 == 0) 0.9f else 0.45f),
                start = inner,
                end = outer,
                strokeWidth = r * (if (i % 3 == 0) 0.09f else 0.05f),
                cap = StrokeCap.Round
            )
        }
        // 时针（含分的 fraction）
        val hourF = (cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f) / 12f
        val hourAngle = (Math.PI * 2 * hourF) - Math.PI / 2
        drawLine(
            color = color,
            start = center,
            end = Offset(
                (center.x + r * 0.42f * cos(hourAngle)).toFloat(),
                (center.y + r * 0.42f * sin(hourAngle)).toFloat()
            ),
            strokeWidth = r * 0.11f,
            cap = StrokeCap.Round
        )
        // 分针
        val minF = (cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f) / 60f
        val minAngle = (Math.PI * 2 * minF) - Math.PI / 2
        drawLine(
            color = color,
            start = center,
            end = Offset(
                (center.x + r * 0.62f * cos(minAngle)).toFloat(),
                (center.y + r * 0.62f * sin(minAngle)).toFloat()
            ),
            strokeWidth = r * 0.08f,
            cap = StrokeCap.Round
        )
        // 秒针（强调色，更细）
        if (showSeconds) {
            val secF = (cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f) / 60f
            val secAngle = (Math.PI * 2 * secF) - Math.PI / 2
            drawLine(
                color = accent,
                start = center,
                end = Offset(
                    (center.x + r * 0.72f * cos(secAngle)).toFloat(),
                    (center.y + r * 0.72f * sin(secAngle)).toFloat()
                ),
                strokeWidth = r * 0.045f,
                cap = StrokeCap.Round
            )
        }
        // 中心点
        drawCircle(color = color, radius = r * 0.07f, center = center)
    }
}
