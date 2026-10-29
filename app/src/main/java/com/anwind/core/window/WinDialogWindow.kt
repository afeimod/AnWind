package com.anwind.core.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.WinTheme
import kotlin.math.roundToInt

/**
 * v3.1.7 Windows 风格可移动小窗口（对话框层）。
 *
 * 背景（用户 v3.1.6 反馈）：
 *  - 容器创建/编辑/运行/克隆/体检/日志等弹窗用 Material3 AlertDialog，
 *    一堆选择器把对话框撑成"大大的死窗口"：占满屏宽、不可移动、无窗口感；
 *  - 用户要"Windows 那种可移动小窗口，类似桌面上打开的软件窗口，
 *    好看点方便点"。
 *
 * 设计（对齐 WindowChrome 视觉体系，但不进 WindowManager 应用窗口体系）：
 *  - 标题栏（主题色 + 16dp 图标位 + 标题 + 红色关闭 X）：按住拖动整窗；
 *  - 圆角 + 阴影 + 边框（复用主题 windowShape / windowShadowElevation）；
 *  - 宽度约束：min(工作区宽 - 32dp, 420dp)；高度：内容自适应，
 *    超过工作区高 65% 时内容区内部滚动（标题栏常留窗外）；
 *  - 初始居中；拖动钳制在工作区内（不跑出屏幕）；
 *  - 半透明 scrim（点击关闭，对齐 Windows 模态对话框手感）；
 *  - 多个 WinDialogWindow 共存时，后按下的置顶（WinDialogHost 管理 z 序）。
 */
object WinDialogHost {
    private val zOrder = mutableMapOf<String, Int>()

    /** 对话框层内置顶（按下/拖动开始时调用） */
    fun bringToFront(dialogId: String) {
        val max = zOrder.values.maxOrNull() ?: 0
        zOrder[dialogId] = max + 1
    }

    /** 对话框关闭时清理 */
    fun remove(dialogId: String) {
        zOrder.remove(dialogId)
    }
}

@Composable
fun WinDialogWindow(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dialogId: String = title,
    icon: (@Composable () -> Unit)? = null,
    dismissOnOutsideClick: Boolean = true,
    maxContentHeightFraction: Float = 0.65f,
    content: @Composable () -> Unit
) {
    WinDialogWindowImpl(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        dialogId = dialogId,
        icon = icon,
        dismissOnOutsideClick = dismissOnOutsideClick,
        maxContentHeightFraction = maxContentHeightFraction,
        innerContent = content
    )
}

@Composable
private fun WinDialogWindowImpl(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    dialogId: String,
    icon: (@Composable () -> Unit)?,
    dismissOnOutsideClick: Boolean,
    maxContentHeightFraction: Float,
    innerContent: @Composable () -> Unit
) {
    val theme = LocalWinTheme.current
    val density = LocalDensity.current

    // 关闭时清理 z 序
    androidx.compose.runtime.DisposableEffect(dialogId) {
        onDispose { WinDialogHost.remove(dialogId) }
    }

    // 半透明 scrim：模态感 + 点击外部关闭
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .then(
                if (dismissOnOutsideClick)
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { onDismiss() }
                else Modifier
            )
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val workW = constraints.maxWidth.toFloat()
            val workH = constraints.maxHeight.toFloat()
            val workWdp = with(density) { constraints.maxWidth.toDp() }
            val maxWdp = (workWdp - 32.dp).coerceAtMost(420.dp)
            val maxHdp = with(density) { constraints.maxHeight.toDp() } * maxContentHeightFraction

            // 窗口位置（px）：初始居中
            var pos by remember(dialogId) {
                val estW = with(density) { maxWdp.toPx() }
                val estH = with(density) { (maxHdp * 0.72f).toPx() }
                mutableStateOf(
                    Offset(
                        ((workW - estW) / 2f).coerceAtLeast(0f),
                        ((workH - estH) / 2f).coerceAtLeast(0f)
                    )
                )
            }

            fun clampPos(x: Float, y: Float): Offset = Offset(
                x.coerceIn(0f, (workW - 80f).coerceAtLeast(0f)),
                y.coerceIn(0f, (workH - 48f).coerceAtLeast(0f))
            )

            Box(
                modifier = modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                    .width(maxWdp)
                    // 任意按下即置顶（不消费事件，不影响内容区交互）
                    .pointerInput(dialogId) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            WinDialogHost.bringToFront(dialogId)
                            // 等待手势结束（不消费任何事件）
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { it.changedToUp() }) break
                            }
                        }
                    }
                    // 吃掉命中窗口但未被内容消费的 tap：防止点击窗口空白处
                    // 穿透到底层 scrim 导致对话框误关闭（子组件已消费的事件
                    // 不会到达这里，内容区按钮/输入框交互不受影响）
                    .pointerInput(dialogId + "_swallow") {
                        detectTapGestures { }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .shadow(theme.windowShadowElevation, theme.windowShape, clip = false)
                        .clip(theme.windowShape)
                        .background(theme.windowBackgroundColor)
                        .border(
                            theme.windowBorderWidth,
                            theme.windowBorderColor,
                            theme.windowShape
                        )
                ) {
                    // ===== 标题栏（拖动 = 移动整窗；对齐 WindowChrome 视觉） =====
                    DialogTitleBar(
                        theme = theme,
                        title = title,
                        icon = icon,
                        onDismiss = onDismiss,
                        onDrag = { dx, dy ->
                            pos = clampPos(pos.x + dx, pos.y + dy)
                        }
                    )

                    // ===== 内容区（超高内部滚动） =====
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxHdp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            innerContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTitleBar(
    theme: WinTheme,
    title: String,
    icon: (@Composable () -> Unit)?,
    onDismiss: () -> Unit,
    onDrag: (Float, Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(theme.windowTitleBarHeight)
            .background(theme.windowTitleBarColor)
            .pointerInput(title) {
                detectDragGestures(
                    onDragStart = { /* 按下即进入拖动；z 序置顶由窗口级 tap 处理 */ }
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(theme.windowTitleBarHeight)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) icon() else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(theme.windowTitleBarIconColor)
                )
            }
        }
        Text(
            text = title,
            color = theme.windowTitleBarTextColor,
            fontSize = theme.fontSizeBody,
            fontWeight = theme.fontWeightTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = theme.windowTitleBarHeight)
                .pointerInput(title) {
                    detectTapGestures(onTap = { onDismiss() })
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color(0xFFE81123),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
