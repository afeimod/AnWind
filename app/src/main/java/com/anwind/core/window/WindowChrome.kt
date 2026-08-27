package com.anwind.core.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anwind.core.theme.LocalWinTheme
import kotlin.math.roundToInt

/**
 * 单个窗口的 chrome（标题栏 + 内容区）。
 *
 * 视觉重构后：
 * - 圆角阴影（Win11 风格 drop shadow + rounded corners）
 * - 标题栏支持拖拽移动、双击最大化
 * - 8 方向调整大小：4 个边缘 + 4 个角落
 * - 内容区可滚动（应用层主动用 verticalScroll 包裹）
 * - 标题栏左侧应用图标 + 标题
 * - 标题栏右侧最小化/最大化/关闭按钮
 *
 * @param workAreaWidth  工作区最大宽（像素）—— 由 WindowHost 通过 BoxWithConstraints 提供
 * @param workAreaHeight 工作区最大高
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

    // 根据是否最大化决定尺寸/位置（FULLSCREEN 模式也占满工作区）
    val isMaximized = state.isMaximized || state.launchMode == LaunchMode.FULLSCREEN
    val finalX = if (isMaximized) 0 else state.x
    val finalY = if (isMaximized) 0 else state.y
    val finalW = if (isMaximized) workAreaWidth else state.width
    val finalH = if (isMaximized) workAreaHeight else state.height

    // 拖拽偏移：只在本地 Compose 状态中累加，避免拖拽中每帧触发全局重组导致卡顿。
    // 松手时一次性提交给 WindowManager。
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // 调整大小中的当前边缘（用于实时显示，松手时清空）
    var activeResizeEdge by remember { mutableStateOf<ResizeEdge?>(null) }
    var resizeOffset by remember { mutableStateOf(Offset.Zero) }

    // 最大化状态下不显示阴影，因为窗口已经贴边
    val shadowElevation = if (isMaximized) 0.dp else theme.windowShadowElevation

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    finalX + dragOffset.x.roundToInt(),
                    finalY + dragOffset.y.roundToInt()
                )
            }
            // 调整大小时窗口尺寸会变化，这里我们让 Box 的尺寸在调整中保持原值，
            // 由 resizeOffset 单独影响视觉位置（通过额外的 offset）
            .size(width = finalW.dp, height = finalH.dp)
            .shadow(shadowElevation, theme.windowShape, clip = false)
            .clip(theme.windowShape)
            .background(theme.windowBackgroundColor)
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
                                    wm.setAbsolutePosition(
                                        state.id,
                                        finalX + dragOffset.x.roundToInt(),
                                        finalY + dragOffset.y.roundToInt()
                                    )
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
                        detectTapGestures(
                            onDoubleTap = {
                                // 仅 FLOATING 模式支持最大化
                                if (state.launchMode == LaunchMode.FLOATING) {
                                    wm.toggleMaximize(state.id)
                                }
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧应用图标占位（Win11 风格标题栏彩色徽标）
                Box(
                    modifier = Modifier
                        .size(theme.windowTitleBarHeight)
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(theme.windowTitleBarIconColor)
                    )
                }

                Text(
                    text = state.title,
                    color = theme.windowTitleBarTextColor,
                    fontSize = theme.fontSizeBody,
                    fontWeight = theme.fontWeightTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 标题栏右侧控制按钮组
                if (state.launchMode == LaunchMode.FLOATING) {
                    ControlButton(icon = Icons.Default.Minimize, theme = theme) { wm.minimize(state.id) }
                    ControlButton(
                        icon = if (state.isMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        theme = theme
                    ) { wm.toggleMaximize(state.id) }
                }
                ControlButton(icon = Icons.Default.Close, theme = theme, isClose = true) { wm.close(state.id) }
            }

            // ===== 内容区 =====
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        }

        // ===== 8 方向调整大小手柄（覆盖在窗口最上层，仅在 FLOATING 模式且未最大化时） =====
        if (state.launchMode == LaunchMode.FLOATING && !state.isMaximized) {
            ResizeHandles(
                state = state,
                workAreaWidth = workAreaWidth,
                workAreaHeight = workAreaHeight,
                onResizeStart = { edge -> activeResizeEdge = edge; resizeOffset = Offset.Zero },
                onResize = { edge, dx, dy ->
                    resizeOffset += Offset(dx, dy)
                    wm.resize(state.id, edge, dx.roundToInt(), dy.roundToInt(), workAreaWidth, workAreaHeight)
                },
                onResizeEnd = {
                    activeResizeEdge = null
                    resizeOffset = Offset.Zero
                    wm.commitChanges()
                }
            )
        }
    }
}

/**
 * 8 方向调整大小手柄：4 个边缘 + 4 个角落
 * - 边缘：宽/高 6dp 的长条，悬停在窗口边缘
 * - 角落：12x12dp 的小方块，悬停在窗口角落
 */
@Composable
private fun BoxScope.ResizeHandles(
    state: WindowState,
    workAreaWidth: Int,
    workAreaHeight: Int,
    onResizeStart: (ResizeEdge) -> Unit,
    onResize: (ResizeEdge, Float, Float) -> Unit,
    onResizeEnd: () -> Unit
) {
    val handleThickness = 6.dp
    val cornerSize = 14.dp

    // 上边
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.TOP) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.TOP, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 下边
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.BOTTOM) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.BOTTOM, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 左边
    Box(
        modifier = Modifier
            .align(Alignment.CenterStart)
            .fillMaxHeight()
            .width(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.LEFT) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.LEFT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 右边
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(handleThickness)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.RIGHT) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.RIGHT, dragAmount.x, dragAmount.y)
                }
            }
    )

    // 4 个角落（更大、更易抓取）
    // 左上
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.TOP_LEFT) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.TOP_LEFT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 右上
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.TOP_RIGHT) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.TOP_RIGHT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 左下
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.BOTTOM_LEFT) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.BOTTOM_LEFT, dragAmount.x, dragAmount.y)
                }
            }
    )
    // 右下
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .size(cornerSize)
            .pointerInput(state.id) {
                detectDragGestures(
                    onDragStart = { onResizeStart(ResizeEdge.BOTTOM_RIGHT) },
                    onDragEnd = onResizeEnd,
                    onDragCancel = onResizeEnd
                ) { change, dragAmount ->
                    change.consume()
                    onResize(ResizeEdge.BOTTOM_RIGHT, dragAmount.x, dragAmount.y)
                }
            }
    )
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    theme: com.anwind.core.theme.WinTheme,
    isClose: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = theme.windowTitleBarHeight)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isClose) Color(0xFFE81123) else theme.windowTitleBarTextColor,
            modifier = Modifier.size(12.dp)
        )
    }
}
