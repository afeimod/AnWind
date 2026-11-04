package com.anwind.core.input.gamepad

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.AnWindApp
import com.anwind.core.input.TrackpadRouter
import com.anwind.core.input.boundsInWindowCompat
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt

/**
 * v3.2.23（用户问题 4）：虚拟手柄常驻悬浮球。
 *
 * 替代旧迷你工具条（⚙✎✕ 三按钮，已移除）—— 手柄的所有控制收拢到一个
 * 炫彩半透明圆球（默认右上角，可拖到任意边缘）：
 *
 * - **单击**：展开/收起控制菜单（显示/收起手柄、手柄设置、编辑布局、
 *   关闭手柄）；
 * - **拖动**：移动悬浮球位置（松手持久化进 gamepad_config v3 的
 *   ballX/ballY）；
 * - 菜单展开时点菜单外任意处收起（一次牺牲点击，不打扰底下窗口语义）。
 *
 * 命中登记（缺一不可，否则球/菜单的触摸会被各输入层误吞或误穿透）：
 * - `GamepadController.registerToolbarHit`（球本体）：X11 智能触摸桥
 *   （SmartTouchBridge.filterPadPointers）按 isOverPadUi 剥离落在球上的
 *   指针 —— 不登记则按住球的手指会被当成屏幕手势，菜单点不到；
 * - `GamepadController.registerMenuHit`（展开菜单）：同上；
 * - `TrackpadRouter.registerPassthrough("gpBall", …)`：触控板模式下
 *   真实手指直触球/菜单（不当成手势）；
 * - `WindowFocusHook.raiseAt` / `TrackpadRouter.onMotionEvent` 侧按
 *   isOverPadUi 直判（v3.2.23），不依赖这里的登记时机。
 *
 * 生命周期：gamepadEnabled=false 时整体退场（含球）——「关闭手柄」即
 * 完全退出，设置页/通知快捷开关可重新开启。
 */
@Composable
fun GamepadFloatingBall(onPersistConfig: () -> Unit) {
    val app = AnWindApp.get()
    val scope = rememberCoroutineScope()

    var expanded by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    // 闭包内保持最新引用（拖动结束时读取基准位置）
    val baseX by rememberUpdatedState(GamepadController.ballPosX)
    val baseY by rememberUpdatedState(GamepadController.ballPosY)

    val ballSize = 54.dp
    val menuWidth = 216.dp

    // 按压反馈
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = tween(120), label = "gpBallPress"
    )
    // 展开/收起时球心的强调色
    val coreColor by animateColorAsState(
        targetValue = if (expanded) Color(0xFFFFF7AD) else Color(0xB3FFFFFF),
        animationSpec = tween(180), label = "gpBallCore"
    )

    // 退场清理：注销球/菜单命中与直通区（防陈旧矩形吞掉后续正常触摸）
    DisposableEffect(Unit) {
        onDispose {
            TrackpadRouter.registerPassthrough("gpBall", null)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val freeWpx = with(density) { (maxWidth - ballSize).toPx() }
        val freeHpx = with(density) { (maxHeight - ballSize).toPx() }
        val menuWpx = with(density) { menuWidth.toPx() }
        val ballPx = with(density) { ballSize.toPx() }

        // 球左上角（px）：基准（归一化）+ 拖动瞬态，钳制在屏内
        fun clampedPos(): Offset {
            val x = freeWpx * baseX + dragOffset.x
            val y = freeHpx * baseY + dragOffset.y
            return Offset(x.coerceIn(0f, freeWpx), y.coerceIn(0f, freeHpx))
        }

        val pos = clampedPos()

        // 菜单出现在球下方（球在下半屏时翻到上方），水平钳制在屏内
        val screenWpxTotal = with(density) { maxWidth.toPx() }
        val screenHpxTotal = with(density) { maxHeight.toPx() }
        val menuLeft = (pos.x + ballPx / 2 - menuWpx / 2)
            .coerceIn(0f, screenWpxTotal - menuWpx)
        val ballCenterY = pos.y + ballPx / 2
        val menuTop = if (ballCenterY < screenHpxTotal / 2) {
            pos.y + ballPx + with(density) { 10.dp.toPx() }
        } else {
            pos.y - with(density) { 10.dp.toPx() } - menuHeightEstimate(density)
        }

        // ===== v3.2.24（用户问题 2）：点菜单外任意处收起的透明捕获层 =====
        // 【z 序修复】必须声明在菜单与悬浮球【之前】：Compose 的 Box 子
        // 件按声明顺序绘制 z 序（后声明者在上）。v3.2.23 把它放在最后 →
        // 全屏捕获层盖在菜单上方，四个菜单项的点击全部被它拦截 →
        // “悬浮球四个按键没有一个能用”（点击只触发收起，onClick 永不执行）。
        // 现在顺序：捕获层（最底）→ 菜单（中）→ 悬浮球（最顶，展开时
        // 仍可拖动/点击）。
        if (expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { expanded = false }
                    }
            )
        }

        // ===== 展开的控制菜单（在捕获层之上，点击直达菜单项） =====
        if (expanded) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(menuLeft.roundToInt(), menuTop.roundToInt()) }
                    .width(menuWidth)
                    .shadow(10.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xE61E2836))
                    .padding(vertical = 6.dp)
                    // 菜单登记：X11 触摸剥离 + 触控板直通（见类 KDoc）
                    .onGloballyPositioned {
                        val b = it.boundsInWindowCompat()
                        GamepadController.registerMenuHit(b.left, b.top, b.right, b.bottom)
                        TrackpadRouter.registerPassthrough("gpBall", b)
                    }
            ) {
                BallMenuItem("🎮", if (GamepadController.visible) "收起手柄" else "显示手柄") {
                    GamepadController.releaseAllKeys()
                    GamepadController.visible = !GamepadController.visible
                    onPersistConfig()
                    expanded = false
                }
                BallMenuItem("⚙", "手柄设置") {
                    GamepadController.releaseAllKeys()
                    GamepadController.settingsOpen = true
                    expanded = false
                }
                BallMenuItem("✎", "编辑布局") {
                    GamepadController.releaseAllKeys()
                    GamepadController.editMode = !GamepadController.editMode
                    if (!GamepadController.editMode) GamepadController.selectElement(null)
                    expanded = false
                }
                BallMenuItem("✕", "关闭手柄", danger = true) {
                    GamepadController.releaseAllKeys()
                    GamepadController.editMode = false
                    expanded = false
                    scope.launch { app.settingsStore.setGamepadEnabled(false) }
                }
            }
        }

        // ===== 悬浮球本体 =====
        Box(
            modifier = Modifier
                .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                .size(ballSize)
                .scale(pressScale)
                .shadow(8.dp, CircleShape)
                // 炫彩半透明：径向渐变球体 + 旋转扫光
                .clip(CircleShape)
                .drawBehind {
                    val r = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0x66FF5F9E), Color(0x667C5CFF),
                                Color(0x662FC8FF), Color(0x4D12B8A0)
                            ),
                            center = Offset(r * 0.7f, r * 0.35f),
                            radius = r * 1.35f
                        )
                    )
                    // 高光
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x8CFFFFFF), Color.Transparent),
                            center = Offset(r * 0.68f, r * 0.32f),
                            radius = r * 0.55f
                        )
                    )
                    // 球心指示（展开时点亮）
                    drawCircle(color = coreColor, radius = r * 0.16f)
                }
                // 命中登记（X11 触摸剥离 + 触控板直通）：菜单未展开时球本体
                // 占用 gpBall 直通槽位；展开后由菜单登记（面积大、覆盖球心）
                .onGloballyPositioned {
                    val b = it.boundsInWindowCompat()
                    GamepadController.registerToolbarHit(b.left, b.top, b.right, b.bottom)
                    if (!expanded) {
                        TrackpadRouter.registerPassthrough("gpBall", b)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try { awaitRelease() } finally { pressed = false }
                        },
                        onTap = { expanded = !expanded }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { expanded = false },
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount
                        },
                        onDragEnd = {
                            // 归一化新基准并持久化（钳制 0..1）
                            val nx = ((freeWpx * baseX + dragOffset.x) / freeWpx)
                                .coerceIn(0f, 1f)
                            val ny = ((freeHpx * baseY + dragOffset.y) / freeHpx)
                                .coerceIn(0f, 1f)
                            GamepadController.ballPosX = nx
                            GamepadController.ballPosY = ny
                            dragOffset = Offset.Zero
                            onPersistConfig()
                        },
                        onDragCancel = { dragOffset = Offset.Zero }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "🎮",
                fontSize = 22.sp,
                color = Color.White
            )
        }
    }
}

/** 菜单项行。 */
@Composable
private fun BallMenuItem(
    icon: String,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    pressed -> Color(0x33FFFFFF)
                    danger -> Color(0x1FFF6B6B)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try { awaitRelease() } finally { pressed = false }
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        Text(icon, fontSize = 16.sp, color = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (danger) Color(0xFFFFB4B4) else Color(0xFFF2F6FF)
        )
    }
}

/** 菜单高度估算（4 行 × ~42dp + 边距），用于下半屏时菜单翻到球上方。 */
private fun menuHeightEstimate(density: androidx.compose.ui.unit.Density): Float =
    with(density) { 190.dp.toPx() }
