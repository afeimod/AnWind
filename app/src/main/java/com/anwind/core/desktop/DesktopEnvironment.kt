package com.anwind.core.desktop

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anwind.AnWindApp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.WindowHost
import com.anwind.core.window.WindowManager
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

    // 任务栏自动隐藏：只在与阈值交界处翻转，避免每次指针移动都触发整体重组
    var taskbarShown by remember { mutableStateOf(true) }
    var bottomThresholdPx by remember { mutableStateOf(0f) }

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
    ) {
        val fullHeight = maxHeight
        val taskbarHeight = theme.taskbarHeight
        // 工作区高度 = 全屏高度 - 任务栏高度 - 任务栏底部 4dp - 任务栏顶部 4dp 空隙
        val workAreaHeight = fullHeight - taskbarHeight - 8.dp

        // 计算底部边缘呼出阈值（屏幕高度 - 28dp），供指针监听协程读取
        SideEffect {
            bottomThresholdPx = with(density) { fullHeight.toPx() } - with(density) { 28.dp.toPx() }
        }

        // 任务栏可见性：未开启自动隐藏时始终显示；开启时指针靠近底部或开始菜单打开才显示
        val taskbarVisible = !taskbarAutohide || taskbarShown || startMenuOpen
        val taskbarOffsetY = if (taskbarVisible) 0 else with(density) { taskbarHeight.toPx() }.toInt()

        // ===== 1. 壁纸层 =====
        WallpaperLayer(
            themeWallpaper = theme.wallpaperAsset,
            customWallpaperUri = customWallpaperUri,
            modifier = Modifier.fillMaxSize()
        )

        // ===== 2. 桌面图标层（占据任务栏上方） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(workAreaHeight)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            // 弹出桌面右键菜单
                            contextMenu = DesktopContextMenuData(
                                x = offset.x,
                                y = offset.y
                            )
                        },
                        onTap = {
                            // 点击空白处关闭开始菜单和右键菜单
                            startMenuOpen = false
                            contextMenu = null
                        }
                    )
                }
        ) {
            DesktopIconGrid()
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
            startMenuOpen = startMenuOpen,
            onStartClick = { startMenuOpen = !startMenuOpen },
            onOpenCalendar = { trayPopup = TrayPopup.CALENDAR },
            onOpenQuickSettings = { trayPopup = TrayPopup.QUICK_SETTINGS },
            showSeconds = showSeconds,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(taskbarHeight)
                .offset { IntOffset(0, taskbarOffsetY) }
        )

        // ===== 5.5. 系统托盘弹窗（Calendar / QuickSettings） =====
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
                )
            }
        }

        // ===== 6. 右键上下文菜单 =====
        contextMenu?.let { data ->
            DesktopContextMenu(
                data = data,
                onDismiss = { contextMenu = null },
                modifier = Modifier
                    .align(Alignment.TopStart)
            )
        }
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
 */
private enum class TrayPopup { CALENDAR, QUICK_SETTINGS }
