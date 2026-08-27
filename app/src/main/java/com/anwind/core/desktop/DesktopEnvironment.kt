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
import androidx.compose.ui.unit.dp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.WindowHost
import com.anwind.core.window.WindowManager
import java.io.IOException

/**
 * 桌面环境：壁纸 + 桌面图标网格 + 任务栏 + 开始菜单 + 浮动窗口。
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

    // 启动音效
    LaunchedEffect(theme.variant) {
        if (soundEnabled) {
            theme.startupSoundAsset?.let { playStartupSound(context, it) }
        }
    }

    // 上下文菜单状态
    var contextMenu by remember { mutableStateOf<DesktopContextMenuData?>(null) }
    var startMenuOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val fullHeight = maxHeight
        val taskbarHeight = theme.taskbarHeight
        val workAreaHeight = fullHeight - taskbarHeight

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

        // ===== 4. 开始菜单层 =====
        if (startMenuOpen) {
            StartMenu(
                theme = theme,
                onDismiss = { startMenuOpen = false },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = taskbarHeight + 4.dp)
            )
        }

        // ===== 5. 任务栏（底部固定） =====
        Taskbar(
            theme = theme,
            startMenuOpen = startMenuOpen,
            onStartClick = { startMenuOpen = !startMenuOpen },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(taskbarHeight)
        )

        // ===== 6. 右键上下文菜单 =====
        contextMenu?.let { data ->
            DesktopContextMenu(
                data = data,
                onDismiss = { contextMenu = null },
                modifier = Modifier
                    .align(Alignment.TopStart)  // 用 absoluteOffset 调整
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
