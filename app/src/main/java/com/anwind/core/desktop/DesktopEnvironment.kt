package com.anwind.core.desktop

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anwind.AnWindApp
import com.anwind.core.input.MouseController
import com.anwind.core.input.MouseCursorOverlay
import com.anwind.core.input.VirtualKeyboardController
import com.anwind.core.input.VirtualKeyboardOverlay
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.WindowHost
import com.anwind.core.window.WindowManager
import com.anwind.data.model.DesktopItem
import java.io.IOException

/**
 * 桌面环境：壁纸 + 桌面图标网格 + 任务栏 + 开始菜单 + 浮动窗口。
 *
 * 视觉重构后：
 * - 任务栏浮在底部（留 4dp 空隙）
 * - 开始菜单居中浮在任务栏上方
 * - 浮动窗口层与任务栏/开始菜单层分离
 *
 * 这是应用启动后唯一的顶层 Composable。
 */
@Composable
fun DesktopEnvironment(
    theme: WinTheme,
    customWallpaperUri: String?,
    soundEnabled: Boolean
) {
    val context = LocalContext.current
    val wm = remember { WindowManager.get() }
    val app = AnWindApp.get()
    val density = LocalDensity.current

    // 显示设置
    val taskbarAutohide by app.settingsStore.taskbarAutohide.collectAsState(initial = false)
    val showSeconds by app.settingsStore.showSeconds.collectAsState(initial = false)
    val timeFormat24h by app.settingsStore.timeFormat24h.collectAsState(initial = true)
    // 任务栏高度（v2.9 可调节）：>=36 时用自定义高度，否则跟随主题默认
    val taskbarHeightPref by app.settingsStore.taskbarHeight.collectAsState(initial = 0f)

    // v2.13 虚拟鼠标：指针显示开关 / 右键手势 / 图标单击双击
    val mouseCursorEnabled by app.settingsStore.mouseCursorEnabled.collectAsState(initial = true)
    val mouseRightClick by app.settingsStore.mouseRightClick.collectAsState(initial = "twofinger")
    val mouseClickMode by app.settingsStore.mouseClickMode.collectAsState(initial = "single")

    // 任务栏自动隐藏：只在与阈值交界处翻转，避免每次指针移动都触发整体重组
    var taskbarShown by remember { mutableStateOf(true) }
    var bottomThresholdPx by remember { mutableStateOf(0f) }

    // 监听 WindowManager 变化：用于检测是否有窗口进入真全屏（F11），
    // 真全屏时隐藏任务栏 + 让浮动窗口层占满整屏
    var wmRevision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmRevision++ } }
    val anyTrueFullscreen = remember(wmRevision) { wm.anyTrueFullscreen() }

    // 启动音效
    LaunchedEffect(theme.variant) {
        if (soundEnabled) {
            theme.startupSoundAsset?.let { playStartupSound(context, it) }
        }
    }

    // 上下文菜单状态
    var contextMenu by remember { mutableStateOf<DesktopContextMenuData?>(null) }
    var startMenuOpen by remember { mutableStateOf(false) }
    // 系统托盘弹窗：calendar / quickSettings / null
    var trayPopup by remember { mutableStateOf<TrayPopup?>(null) }

    // v2.13 虚拟键盘：Win 徽标键 = 切换开始菜单（必须在 startMenuOpen 声明之后）
    SideEffect {
        VirtualKeyboardController.onWinKey = { startMenuOpen = !startMenuOpen }
    }

    // v2.11 右键菜单配套状态：
    // - refreshTick："刷新"计数器，key(refreshTick) 重建图标网格（重载图标位图）；
    // - desktopSort：排序模式（与设置中心共用 DataStore）；
    // - iconBounds：每个图标的屏幕边界注册表，双指右键时命中检测，
    //   命中图标 → 图标菜单（打开/重命名/删除/属性），空白处 → 桌面菜单。
    var refreshTick by remember { mutableStateOf(0) }
    val desktopSort by app.settingsStore.desktopSort.collectAsState(initial = "default")
    val iconBounds = remember { mutableStateMapOf<String, Pair<DesktopItem, Rect>>() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 仅在开启自动隐藏时监听指针位置（不消费事件），用于底部边缘呼出任务栏
            .pointerInput(taskbarAutohide) {
                if (taskbarAutohide) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val y = event.changes.firstOrNull()?.position?.y ?: continue
                            val visible = y >= bottomThresholdPx
                            if (visible != taskbarShown) taskbarShown = visible
                        }
                    }
                }
            }
            // v2.13 虚拟鼠标指针跟踪：纯观察者（从不消费事件），
            // 指针贴手指移动，快速轻点时触发点击涟漪
            .pointerInput(mouseCursorEnabled) {
                if (mouseCursorEnabled) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedChange = event.changes.lastOrNull { it.pressed }
                            val change = pressedChange ?: event.changes.lastOrNull()
                            if (change != null) {
                                MouseController.update(change.position.x, change.position.y)
                            }
                            MouseController.setPressed(event.changes.any { it.pressed })
                        }
                    }
                }
            }
    ) {
        val fullHeight = maxHeight
        // v2.9：任务栏高度可调（个性化设置 36..80dp），未设置时跟随主题
        val taskbarHeight = if (taskbarHeightPref >= 36f) taskbarHeightPref.dp else theme.taskbarHeight
        // 工作区高度 = 全屏高度 - 任务栏高度 - 任务栏底部 4dp - 任务栏顶部 4dp 空隙
        // 真全屏（F11）时浮动窗口层占满整屏，隐藏任务栏
        val workAreaHeight = if (anyTrueFullscreen) fullHeight else fullHeight - taskbarHeight - 8.dp

        // 计算底部边缘呼出阈值（屏幕高度 - 28dp），供指针监听协程读取；
        // 同时初始化虚拟鼠标指针位置（首次进入桌面时置于屏幕中部偏上）
        SideEffect {
            bottomThresholdPx = with(density) { fullHeight.toPx() } - with(density) { 28.dp.toPx() }
            if (mouseCursorEnabled) {
                MouseController.initialize(
                    with(density) { maxWidth.toPx() } * 0.4f,
                    with(density) { fullHeight.toPx() } * 0.32f
                )
            }
        }

        // 任务栏可见性：真全屏时彻底隐藏；否则按自动隐藏策略
        val taskbarVisible = !anyTrueFullscreen && (!taskbarAutohide || taskbarShown || startMenuOpen)
        val taskbarOffsetY = if (taskbarVisible) 0 else with(density) { taskbarHeight.toPx() }.toInt()

        // ===== 1. 壁纸层 =====
        WallpaperLayer(
            themeWallpaper = theme.wallpaperAsset,
            customWallpaperUri = customWallpaperUri,
            modifier = Modifier.fillMaxSize()
        )

        // ===== 2. 桌面图标层（占据任务栏上方） =====
        // v2.11 手势：双指轻点 = 右键菜单（命中图标 → 图标菜单，否则 → 桌面菜单）；单指轻点 = 关闭菜单
        // v2.13：右键手势可设（双指轻点 / 长按），图标打开方式可设（单击 / 双击）
        val openContextMenu: (Offset) -> Unit = { offset ->
            // 本层位于根 (0,0)，局部坐标与图标上报的 boundsInRoot 根坐标一致，可直接命中检测。
            val hit = iconBounds.entries.firstOrNull { it.value.second.contains(offset) }
            startMenuOpen = false
            contextMenu = DesktopContextMenuData(
                x = offset.x,
                y = offset.y,
                iconItem = hit?.value?.first
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(workAreaHeight)
                .desktopGestures(
                    onTap = {
                        // 点击空白处关闭开始菜单和右键菜单
                        startMenuOpen = false
                        contextMenu = null
                    },
                    onTwoFingerTap = openContextMenu,
                    enableTwoFinger = mouseRightClick == "twofinger",
                    enableLongPress = mouseRightClick == "longpress",
                    onLongPress = openContextMenu
                )
        ) {
            // key(refreshTick)：右键菜单"刷新"时重建网格，重新加载图标位图
            key(refreshTick) {
                DesktopIconGrid(
                    sortMode = desktopSort,
                    iconBounds = iconBounds,
                    clickMode = mouseClickMode
                )
            }
        }

        // ===== 3. 浮动窗口层（覆盖在桌面图标之上，任务栏之下） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(workAreaHeight)
        ) {
            WindowHost()
        }

        // ===== 4. 开始菜单层（居中浮动，位于任务栏上方） =====
        if (startMenuOpen) {
            // 背景遮罩：点击关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { startMenuOpen = false })
                    }
            )
            StartMenu(
                theme = theme,
                onDismiss = { startMenuOpen = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = taskbarHeight + 12.dp)
            )
        }

        // ===== 5. 任务栏（底部浮起） =====
        Taskbar(
            theme = theme,
            taskbarHeight = taskbarHeight,
            startMenuOpen = startMenuOpen,
            onStartClick = { startMenuOpen = !startMenuOpen },
            onOpenCalendar = { trayPopup = TrayPopup.CALENDAR },
            onOpenQuickSettings = { trayPopup = TrayPopup.QUICK_SETTINGS },
            onOpenClockStyle = { trayPopup = TrayPopup.CLOCK_STYLE },
            showSeconds = showSeconds,
            timeFormat24h = timeFormat24h,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(taskbarHeight)
                .offset { IntOffset(0, taskbarOffsetY) }
        )

        // ===== 5.5. 系统托盘弹窗（Calendar / QuickSettings / ClockStyle）=====
        // v2.11：托盘固定在任务栏最右侧，弹窗相应从右下角弹出；
        // heightIn 限制弹窗总高度不超过（屏幕高度 - 任务栏），小屏幕上不再溢出屏幕顶部。
        val maxPopupHeight = (fullHeight - taskbarHeight - 16.dp).coerceAtLeast(240.dp)
        trayPopup?.let { popup ->
            // 背景遮罩：点击关闭
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { trayPopup = null })
                    }
            )
            when (popup) {
                TrayPopup.CALENDAR -> CalendarFlyout(
                    theme = theme,
                    onDismiss = { trayPopup = null },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = taskbarHeight + 8.dp)
                        .heightIn(max = maxPopupHeight)
                )
                TrayPopup.QUICK_SETTINGS -> QuickSettingsPanel(
                    theme = theme,
                    onOpenSettings = {
                        trayPopup = null
                        wm.open(
                            appId = "settings",
                            title = "设置",
                            launchMode = com.anwind.core.window.LaunchMode.FLOATING,
                            initialWidth = 720,
                            initialHeight = 520
                        )
                    },
                    onDismiss = { trayPopup = null },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = taskbarHeight + 8.dp)
                        .heightIn(max = maxPopupHeight)
                )
                TrayPopup.CLOCK_STYLE -> TrayClockSettingsFlyout(
                    theme = theme,
                    onDismiss = { trayPopup = null },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = taskbarHeight + 8.dp)
                        .heightIn(max = maxPopupHeight)
                )
            }
        }

        // ===== 6. 右键上下文菜单 =====
        contextMenu?.let { data ->
            DesktopContextMenu(
                data = data,
                onDismiss = { contextMenu = null },
                onRefresh = { refreshTick++ },
                modifier = Modifier
                    .align(Alignment.TopStart)
            )
        }

        // ===== 7. 虚拟键盘层（v2.13：全键盘，可拖动） =====
        VirtualKeyboardOverlay()

        // ===== 8. 虚拟鼠标指针层（v2.13：Windows 风格指针 + 点击涟漪，最顶层） =====
        MouseCursorOverlay()
    }
}

/**
 * 播放启动音效。
 * 因为 assets/sounds 中的 mp3 文件不一定存在，需 try/catch。
 */
private fun playStartupSound(context: Context, assetPath: String) {
    try {
        val mp = MediaPlayer()
        val afd = context.assets.openFd(assetPath) ?: return
        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        mp.setOnPreparedListener { it.start() }
        mp.setOnCompletionListener { it.release() }
        mp.setOnErrorListener { _, _, _ -> true }
        mp.prepareAsync()
    } catch (_: IOException) {
        // 资源缺失时静默跳过
    } catch (_: Exception) {
        // 其他异常同样跳过
    }
}

/**
 * 系统托盘弹窗类型：
 * - CALENDAR：点击时钟后显示的 Win11 风格日历
 * - QUICK_SETTINGS：点击 wifi/电池/音量后显示的 Win11 风格快速设置面板
 * - CLOCK_STYLE：长按时钟弹出的托盘时钟样式设置（v2.10）
 */
private enum class TrayPopup { CALENDAR, QUICK_SETTINGS, CLOCK_STYLE }

/**
 * 桌面手势（v2.10，v2.13 扩展）：
 * - 单指轻点：关闭开始菜单/右键菜单（不消费事件，不影响子组件手势）
 * - 双指轻点：两指几乎同时按下、位移小于 slop、持续 < 800ms，
 *   在双指中点触发桌面右键菜单（右键手势设为"双指"时启用）
 * - 长按（v2.13）：单指按下不动超过系统长按阈值（右键手势设为"长按"时启用），
 *   在按压位置触发右键菜单
 *
 * 实现为纯观察者（从不消费事件），与图标双击启动、图标区横向滚动完全兼容；
 * 双指即使其中一指落在图标上，本层也能收到全部两根指针的事件（祖先命中路径）。
 */
private fun Modifier.desktopGestures(
    onTap: (Offset) -> Unit,
    onTwoFingerTap: (Offset) -> Unit,
    enableTwoFinger: Boolean,
    enableLongPress: Boolean,
    onLongPress: (Offset) -> Unit
): Modifier = pointerInput(enableTwoFinger, enableLongPress) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val startTime = first.uptimeMillis
        val startPositions = mutableMapOf(first.id to first.position)
        var maxPointers = 1
        var moved = false
        var expired = false
        var longPressFired = false
        var twoFingerCentroid: Offset? = null
        // 注意：Compose ViewConfiguration 没有 pointerSlop 公开属性（v2.10.0 编译错误根因）。
        // PointerInputScope 本身实现 Density，直接用 dp 容差换算像素最稳妥；
        // 双指轻点手势两指天然抖动大，18dp 比系统 touchSlop(约8dp) 更宽容。
        val slop = 18.dp.toPx()

        while (true) {
            val event = awaitPointerEvent()
            // 记录新按下的手指；检测任一手指位移超过 slop
            event.changes.forEach { c ->
                if (c.pressed) {
                    val start = startPositions[c.id]
                    if (start == null) {
                        startPositions[c.id] = c.position
                    } else if ((c.position - start).getDistance() > slop) {
                        moved = true
                    }
                }
            }
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount > maxPointers) maxPointers = pressedCount
            // 记录双指中点（首次达到 2 指时）
            if (pressedCount == 2 && twoFingerCentroid == null) {
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    twoFingerCentroid = Offset(
                        (pressed[0].position.x + pressed[1].position.x) / 2f,
                        (pressed[0].position.y + pressed[1].position.y) / 2f
                    )
                }
            }
            val elapsed = event.changes.maxOf { it.uptimeMillis } - startTime
            // 长按右键（v2.13）：单指、未移动、未过期、超过系统长按阈值，只触发一次
            if (enableLongPress && !longPressFired && !moved && !expired &&
                maxPointers == 1 && elapsed > viewConfiguration.longPressTimeoutMillis
            ) {
                longPressFired = true
                onLongPress(first.position)
            }
            if (pressedCount == 0) {
                // 全部手指抬起：判定轻点 / 双指轻点
                val duration = event.changes.maxOf { it.uptimeMillis } - startTime
                if (!moved && !expired && !longPressFired) {
                    if (enableTwoFinger && maxPointers == 2 && twoFingerCentroid != null &&
                        duration < viewConfiguration.longPressTimeoutMillis * 2
                    ) {
                        onTwoFingerTap(twoFingerCentroid!!)
                    } else if (maxPointers == 1 &&
                        duration < viewConfiguration.doubleTapTimeoutMillis
                    ) {
                        onTap(first.position)
                    }
                }
                break
            }
            // 按压超过 800ms：不再是"轻点"，等待抬手后结束（不触发轻点回调）
            if (elapsed > 800L) {
                expired = true
            }
        }
    }
}
