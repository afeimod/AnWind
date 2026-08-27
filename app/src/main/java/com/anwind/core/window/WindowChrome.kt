package com.anwind.core.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Maximize
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anwind.core.theme.LocalWinTheme
import kotlin.math.roundToInt

/**
 * 单个窗口的 chrome（标题栏 + 内容区）。
 *
 * 由 WindowHost 调用，传入内容 composable。
 * 拖拽/最小化/最大化/关闭 都委托给 WindowManager。
 *
 * @param maxWidth  工作区最大宽（像素）—— 由 WindowHost 通过 BoxWithConstraints 提供
 * @param maxHeight 工作区最大高
 */
@Composable
fun WindowChrome(
    state: WindowState,
    workAreaWidth: Int,
    workAreaHeight: Int,
    content: @Composable BoxScope.() -> Unit
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }

    // 根据是否最大化决定尺寸/位置
    val finalX = if (state.isMaximized) 0 else state.x
    val finalY = if (state.isMaximized) 0 else state.y
    val finalW = if (state.isMaximized) workAreaWidth else state.width
    val finalH = if (state.isMaximized) workAreaHeight else state.height

    // 拖拽偏移：只在本地 Compose 状态中累加，避免拖拽中每帧触发全局重组导致卡顿。
    // 松手时一次性提交给 WindowManager。
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset { IntOffset(finalX + dragOffset.x.roundToInt(), finalY + dragOffset.y.roundToInt()) }
            .size(width = finalW.dp, height = finalH.dp)
            .background(theme.windowBackgroundColor, theme.windowShape)
            .border(theme.windowBorderWidth, theme.windowBorderColor, theme.windowShape)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 标题栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(theme.windowTitleBarHeight)
                    .background(theme.windowTitleBarColor)
                    .pointerInput(state.id) {
                        detectDragGestures(
                            onDragStart = {
                                wm.focus(state.id)
                                dragOffset = Offset.Zero
                            },
                            onDragEnd = {
                                // 拖拽结束，把累计位移一次性提交，触发一次全局刷新
                                if (dragOffset != Offset.Zero) {
                                    wm.move(state.id, dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                                    wm.commitChanges()
                                }
                                dragOffset = Offset.Zero
                            },
                            onDragCancel = { dragOffset = Offset.Zero }
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        }
                    }
                    .pointerInput(state.id) {
                        detectTapGestures(onDoubleTap = { wm.toggleMaximize(state.id) })
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.title,
                    color = theme.windowTitleBarTextColor,
                    fontSize = theme.fontSizeBody,
                    fontWeight = theme.fontWeightTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                ControlButton(icon = Icons.Default.Minimize) { wm.minimize(state.id) }
                ControlButton(icon = Icons.Default.Maximize) { wm.toggleMaximize(state.id) }
                ControlButton(icon = Icons.Default.Close, isClose = true) { wm.close(state.id) }
            }
            // ===== 内容区 =====
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isClose: Boolean = false,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    IconButton(onClick = onClick, modifier = Modifier.size(theme.windowTitleBarHeight)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isClose) androidx.compose.ui.graphics.Color.White else theme.windowTitleBarTextColor,
            modifier = Modifier.size(14.dp)
        )
    }
}
