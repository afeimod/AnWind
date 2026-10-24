package com.anwind.apps.qq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 经典 QQ游戏大厅 复刻组件库（对应原版截图 2008~2011 客户端）：
 *
 *  - [QqGame]               经典配色常量（大厅深蓝 / 橙木桌 / 深青牌桌 / 木纹棋盘…）
 *  - [QqPenguinAvatar]      经典 QQ 企鹅头像（Canvas 矢量：黑袍白肚红围巾）
 *  - [QqGreenButton]        "快速加入游戏"绿色渐变按钮
 *  - [QqWhiteButton]        "邀请好友"白底灰边按钮
 *  - [QqGoldButton]         "任务"金黄渐变按钮（拟物高光）
 *  - [QqPillButton]         快捷短语黄色胶囊按钮（宝宝/记牌器/表情…）
 *  - [QqBlueDiamondButton]  "蓝钻贵族"/"游戏商城"按钮
 *  - [QqOrangeTable]        大厅橙色木纹牌桌（透视梯形 + 木纹 + 中央牌区）
 *  - [QqLedTimer]           牌桌中央 LED 倒计时（黑底黄字 + 黄色指示三角）
 *  - [QqStoneButton]        悔棋/求和/道具/认输 石质浮雕按钮
 *  - [QqPanelHeader]        右侧经典面板浅蓝信息条
 *  - [QqPropsBar]           公共道具/专属道具栏（带翻页箭头）
 */

object QqGame {
    // ===== 大厅房间（截图1）=====
    val RoomBgTop = Color(0xFF0E5FA8)      // 房间深蓝渐变（上）
    val RoomBgBottom = Color(0xFF053A6E)   // 房间深蓝渐变（下）
    val WoodTop = Color(0xFFF5AE64)        // 橙木桌亮部
    val WoodBottom = Color(0xFFDE8628)     // 橙木桌暗部
    val WoodEdge = Color(0xFF9C5510)       // 桌沿深橙描边
    val WoodGrain = Color(0x2EFFFFFF)      // 木纹高光
    val TableCloth = Color(0xFFF6EEDC)     // 桌面中央浅色牌区

    // ===== 斗地主牌桌（截图2）=====
    val DdzFeltCenter = Color(0xFF1B6B9E)  // 深青桌面（中心）
    val DdzFeltEdge = Color(0xFF0F4C75)    // 深青桌面（边缘）
    val AlarmYellow = Color(0xFFFFC233)    // 倒计时闹钟黄
    val AlarmRed = Color(0xFFE02020)       // 倒计时数字红

    // ===== 欢乐麻将有（截图3）=====
    val MjFeltCenter = Color(0xFF2B7A96)
    val MjFeltEdge = Color(0xFF0F3D54)
    val TileBackGreen = Color(0xFF3FA24E)  // 牌墙绿
    val TileBackDark = Color(0xFF2C7A38)
    val LedBg = Color(0xFF15181C)          // LED 黑底
    val LedDigit = Color(0xFFFFE14D)       // LED 黄字

    // ===== 象棋（截图4）=====
    val ChessBg = Color(0xFFA8A8A8)        // 灰色窗口背景
    val ChessBoard = Color(0xFFE8D4A0)     // 米黄棋盘
    val ChessBoardDark = Color(0xFFDCC488) // 棋盘木纹暗部
    val ChessLine = Color(0xFF4A3B2A)      // 深褐网格线
    val ChessPanel = Color(0xFFF0EBDD)     // 左侧玩家面板米色

    // ===== 五子棋（截图5）=====
    val GmBgTop = Color(0xFF1E6CB4)
    val GmBgBottom = Color(0xFF4A9CD5)
    val GmBoard = Color(0xFFE8C98E)        // 浅暖黄木纹棋盘
    val GmBoardDark = Color(0xFFDDB97A)
    val GmLine = Color(0xFF4A3520)         // 深褐网格线

    // ===== 右侧经典面板通用 =====
    val PanelBlueTop = Color(0xFFAEE0F8)   // 面板浅蓝顶条
    val PanelBlueBottom = Color(0xFF8CC9EC)
    val PanelLightBg = Color(0xFFF2F8FC)   // 面板浅底
    val GoldTop = Color(0xFFFFE082)        // 金黄按钮
    val GoldBottom = Color(0xFFF5A623)
    val GoldBorder = Color(0xFFB07A18)
    val GoldText = Color(0xFF6B3E00)

    /** 昵称颜色（按 hash 稳定，模拟原版彩色昵称） */
    private val SeatNickColors = listOf(
        Color(0xFFFFF3C4), Color(0xFFC8F7C5), Color(0xFFBDE3FF),
        Color(0xFFFFD1DC), Color(0xFFE7D1FF), Color(0xFFFFE9B8)
    )

    fun seatNickColor(name: String): Color =
        SeatNickColors[(name.hashCode() and 0x7FFFFFFF) % SeatNickColors.size]
}

// ==================== 经典 QQ 企鹅头像 ====================

/**
 * 经典 QQ 企鹅（2008 版：黑袍、白肚、红围巾、橙喙、黄蹼）。
 * Canvas 矢量绘制，任何尺寸都清晰；[scarf] 可换围巾颜色做房主标记。
 */
@Composable
fun QqPenguinAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    scarf: Color = Color(0xFFD9001B),
    showFace: Boolean = false
) {
    Canvas(modifier = modifier.size(size)) {
        drawQqPenguin(scarf, showFace)
    }
}

/** 企鹅绘制（100×100 逻辑坐标系） */
fun DrawScope.drawQqPenguin(scarf: Color = Color(0xFFD9001B), showFace: Boolean = false) {
    val w = this.size.width
    val h = this.size.height
    fun x(v: Float) = v / 100f * w
    fun y(v: Float) = v / 100f * h
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) {
        drawOval(
            color,
            topLeft = Offset(x(cx - rx), y(cy - ry)),
            size = Size(x(cx + rx) - x(cx - rx), y(cy + ry) - y(cy - ry))
        )
    }

    val black = Color(0xFF151A22)
    // 翅膀
    oval(19f, 58f, 6.5f, 14f, black)
    oval(81f, 58f, 6.5f, 14f, black)
    // 脚（黄蹼）
    oval(38f, 89f, 10f, 4.6f, Color(0xFFF5A623))
    oval(62f, 89f, 10f, 4.6f, Color(0xFFF5A623))
    // 身体（头 + 躯干两椭圆重叠）
    oval(50f, 33f, 25f, 22f, black)
    oval(50f, 61f, 28f, 27f, black)
    // 白肚皮
    oval(50f, 66f, 18.5f, 19.5f, Color(0xFFFCFCFC))
    // 围巾（红）：脖颈一圈 + 垂下的围巾尾
    drawRoundRect(
        scarf,
        topLeft = Offset(x(30f), y(51f)),
        size = Size(x(70f) - x(30f), y(58f) - y(51f)),
        cornerRadius = CornerRadius(x(3f), x(3f))
    )
    drawRoundRect(
        scarf,
        topLeft = Offset(x(43f), y(56f)),
        size = Size(x(52f) - x(43f), y(74f) - y(56f)),
        cornerRadius = CornerRadius(x(2f), x(2f))
    )
    // 围巾尾白条纹
    drawRect(Color(0xFFFFFFFF), topLeft = Offset(x(44f), y(66f)), size = Size(x(51f) - x(44f), y(68.5f) - y(66f)))
    // 眼睛
    oval(41.5f, 28f, 5.4f, 6.4f, Color.White)
    oval(58.5f, 28f, 5.4f, 6.4f, Color.White)
    oval(42.6f, 29.8f, 2.4f, 2.8f, Color(0xFF101018))
    oval(57.4f, 29.8f, 2.4f, 2.8f, Color(0xFF101018))
    // 橙喙（菱形）
    val beak = Path().apply {
        moveTo(x(50f), y(35.5f))
        lineTo(x(43.6f), y(41.5f))
        lineTo(x(50f), y(49f))
        lineTo(x(56.4f), y(41.5f))
        close()
    }
    drawPath(beak, Color(0xFFF5A623))
    // 脸颊（可选用）
    if (showFace) {
        oval(35f, 40f, 3.2f, 2.2f, Color(0xFFF08A8A))
        oval(65f, 40f, 3.2f, 2.2f, Color(0xFFF08A8A))
    }
}

// ==================== 大厅橙色木纹牌桌 ====================

/**
 * 大厅桌子（截图1 风格）：橙色透视梯形木桌面 + 木纹 + 中央浅色牌区，
 * 外圈由调用方摆座位（企鹅头像 + 昵称）。
 */
@Composable
fun QqOrangeTable(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier
            .size(width, height)
            .then(if (onClick != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() } else Modifier)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawOrangeWoodTable()
        }
        content()
    }
}

/** 橙色木纹桌绘制（透视梯形） */
fun DrawScope.drawOrangeWoodTable() {
    val w = size.width
    val h = size.height
    // 桌底阴影
    drawOval(
        Color(0x33000000),
        topLeft = Offset(w * 0.08f, h * 0.55f),
        size = Size(w * 0.84f, h * 0.5f)
    )
    // 桌面透视梯形
    val table = Path().apply {
        moveTo(w * 0.135f, h * 0.66f)   // 左下
        lineTo(w * 0.215f, h * 0.06f)   // 左上
        lineTo(w * 0.785f, h * 0.06f)   // 右上
        lineTo(w * 0.865f, h * 0.66f)   // 右下
        close()
    }
    drawPath(table, Brush.verticalGradient(listOf(QqGame.WoodTop, QqGame.WoodBottom)))
    // 木纹竖线（浅色半透明）
    val grain = Path()
    for (i in 1..6) {
        val t = i / 7f
        val xTop = w * (0.215f + (0.785f - 0.215f) * t)
        val xBottom = w * (0.135f + (0.865f - 0.135f) * t)
        grain.moveTo(xTop, h * 0.08f)
        grain.lineTo(xBottom, h * 0.64f)
    }
    drawPath(grain, Color(0x24FFFFFF))
    // 桌沿描边
    drawPath(table, QqGame.WoodEdge, style = Stroke(width = 1.6f))
    // 桌面前缘（厚边）
    drawRoundRect(
        Color(0xFFB5641A),
        topLeft = Offset(w * 0.135f, h * 0.64f),
        size = Size(w * 0.73f, h * 0.09f),
        cornerRadius = CornerRadius(h * 0.04f)
    )
    // 中央浅色牌区（圆角矩形，内嵌白框）
    val cx = w * 0.5f
    val clothW = w * 0.34f
    val clothH = h * 0.30f
    val clothTop = h * 0.17f
    drawRoundRect(
        QqGame.TableCloth,
        topLeft = Offset(cx - clothW / 2f, clothTop),
        size = Size(clothW, clothH),
        cornerRadius = CornerRadius(w * 0.03f)
    )
    drawRoundRect(
        Color(0xFF8A4A0E),
        topLeft = Offset(cx - clothW / 2f, clothTop),
        size = Size(clothW, clothH),
        cornerRadius = CornerRadius(w * 0.03f),
        style = Stroke(width = 1.2f)
    )
    // 牌区中央：两张交叉的小牌背（QQ 大厅桌布图案）
    val pw = clothW * 0.42f
    val ph = clothH * 0.62f
    rotate45Card(cx - pw * 0.22f, clothTop + clothH * 0.22f, pw, ph)
    rotate45Card(cx + pw * 0.1f, clothTop + clothH * 0.28f, pw, ph)
}

/** 斜放 45° 的小牌背（桌布装饰） */
private fun DrawScope.rotate45Card(x0: Float, y0: Float, pw: Float, ph: Float) {
    val cx = x0 + pw / 2f
    val cy = y0 + ph / 2f
    val k = 0.7071f
    val corners = listOf(
        Offset(x0, y0),
        Offset(x0 + pw, y0),
        Offset(x0 + pw, y0 + ph),
        Offset(x0, y0 + ph)
    )
    val card = Path().apply {
        corners.forEachIndexed { i, p ->
            val rx = cx + (p.x - cx) * k - (p.y - cy) * k
            val ry = cy + (p.x - cx) * k + (p.y - cy) * k
            if (i == 0) moveTo(rx, ry) else lineTo(rx, ry)
        }
        close()
    }
    drawPath(card, Color(0xFF3E8ECC))
    drawPath(card, Color(0xFF23557E), style = Stroke(1f))
}

// ==================== 按钮家族（原版拟物渐变） ====================

/** "快速加入游戏"：鲜绿渐变圆角按钮，白字加粗 */
@Composable
fun QqGreenButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    fontSize: TextUnit = 13.sp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF6FB028), Color(0xFF8FD145))
                    else listOf(Color(0xFF8FD145), Color(0xFF6FB028))
                ),
                RoundedCornerShape(height / 2)
            )
            .border(1.dp, Color(0xFF4E8A1C), RoundedCornerShape(height / 2))
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** "邀请好友"：白底灰边按钮 */
@Composable
fun QqWhiteButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 30.dp,
    fontSize: TextUnit = 13.sp,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .background(
                if (pressed) Color(0xFFE4E4E4) else Color(0xFFF7F7F7),
                RoundedCornerShape(height / 2)
            )
            .border(1.dp, Color(0xFFB8B8B8), RoundedCornerShape(height / 2))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Color(0xFF333333) else Color(0xFF999999), fontSize = fontSize, maxLines = 1)
    }
}

/** "任务"：金黄渐变大按钮（拟物高光 + 内描边） */
@Composable
fun QqGoldButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    fontSize: TextUnit = 14.sp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(QqGame.GoldBottom, QqGame.GoldTop)
                    else listOf(QqGame.GoldTop, QqGame.GoldBottom)
                ),
                RoundedCornerShape(height / 2)
            )
            .border(1.5.dp, QqGame.GoldBorder, RoundedCornerShape(height / 2))
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = QqGame.GoldText,
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

/** 快捷短语黄色胶囊（宝宝 / 记牌器 / 表情 / 托管…） */
@Composable
fun QqPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 24.dp,
    fontSize: TextUnit = 11.sp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFFE8A80C), Color(0xFFFFCE3D))
                    else listOf(Color(0xFFFFCE3D), Color(0xFFE8A80C))
                ),
                RoundedCornerShape(height / 2)
            )
            .border(1.dp, Color(0xFFA8760A), RoundedCornerShape(height / 2))
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(0xFF5A3A00), fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** "蓝钻贵族" / "游戏商城"：深蓝椭圆宝石按钮 */
@Composable
fun QqBlueDiamondButton(
    text: String,
    diamond: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 26.dp,
    fontSize: TextUnit = 11.sp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .background(
                Brush.verticalGradient(
                    if (pressed) listOf(Color(0xFF0B3E75), Color(0xFF2E86D5))
                    else listOf(Color(0xFF2E86D5), Color(0xFF0B3E75))
                ),
                RoundedCornerShape(height / 2)
            )
            .border(1.dp, Color(0xFF0A5296), RoundedCornerShape(height / 2))
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (diamond) {
                Canvas(Modifier.size(width = height * 0.36f, height = height * 0.36f)) {
                    val d = Path().apply {
                        moveTo(size.width * 0.5f, 0f)
                        lineTo(size.width, size.height * 0.38f)
                        lineTo(size.width * 0.5f, size.height)
                        lineTo(0f, size.height * 0.38f)
                        close()
                    }
                    drawPath(d, Color(0xFF6FD1FF))
                    drawPath(d, Color(0xFFBDE9FF), style = Stroke(1f))
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(text, color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** 悔棋/求和/道具/认输：石质浮雕按钮（灰白岩面 + 深投影） */
@Composable
fun QqStoneButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 30.dp,
    fontSize: TextUnit = 13.sp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .background(
                Brush.verticalGradient(
                    when {
                        !enabled -> listOf(Color(0xFFD6D6D6), Color(0xFFC2C2C2))
                        pressed -> listOf(Color(0xFFB9B9B9), Color(0xFFD8D8D8))
                        else -> listOf(Color(0xFFE8E8E8), Color(0xFFBDBDBD))
                    }
                ),
                RoundedCornerShape(height / 2)
            )
            .border(
                1.dp,
                if (enabled) Color(0xFF8A8A8A) else Color(0xFFAAAAAA),
                RoundedCornerShape(height / 2)
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFF444444) else Color(0xFF999999),
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ==================== LED 倒计时（麻将/通用） ====================

/**
 * 牌桌中央 LED 倒计时：黑底 + 黄色 LED 数字 + 顶部黄色指示三角（指向行动玩家）。
 */
@Composable
fun QqLedTimer(
    seconds: Int,
    modifier: Modifier = Modifier,
    width: Dp = 46.dp,
    height: Dp = 34.dp,
    arrow: Boolean = true
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (arrow) {
            Canvas(Modifier.size(width = 14.dp, height = 8.dp)) {
                val tri = Path().apply {
                    moveTo(size.width / 2f, size.height)
                    lineTo(0f, 0f)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(tri, Color(0xFFFFE14D))
            }
            Spacer(Modifier.height(1.dp))
        }
        Box(
            modifier = Modifier
                .size(width, height)
                .background(Color(0xFF15181C), RoundedCornerShape(5.dp))
                .border(1.dp, Color(0xFF4A4A4A), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                String.format("%02d", seconds.coerceIn(0, 99)),
                color = QqGame.LedDigit,
                fontSize = (height.value * 0.56f).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }
    }
}

// ==================== 右侧经典面板零件 ====================

/** 面板顶部浅蓝信息条（昵称 + 积分 / 游戏币） */
@Composable
fun QqPanelHeader(
    nickname: String,
    scoreText: String,
    coinText: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(QqGame.PanelBlueTop, QqGame.PanelBlueBottom)))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QqPenguinAvatar(size = 30.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(nickname, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF123A5E), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("积分: $scoreText    $coinText", fontSize = 10.sp, color = Color(0xFF2E6288), maxLines = 1)
        }
    }
}

/** 公共道具 / 专属道具栏：格子 + 翻页箭头 */
@Composable
fun QqPropsBar(
    title: String,
    propCount: Int,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 10.sp, color = Color(0xFF2E6288), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("‹", fontSize = 12.sp, color = Color(0xFF5B7B99))
            Spacer(Modifier.width(6.dp))
            Text("›", fontSize = 12.sp, color = Color(0xFF5B7B99))
        }
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(Color.White)
                        .border(1.dp, Color(0xFF9FC2DC), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (i < propCount) Text(if (i == 0) "👕" else if (i == 1) "🌶️" else "🎁", fontSize = 14.sp)
                }
            }
        }
    }
}

/** 面板小节标题（灰底凹槽条） */
@Composable
fun QqPanelSection(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFFE4EFF7), Color(0xFFD2E4F2))))
            .border(1.dp, Color(0xFFAFCADD))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(title, fontSize = 11.sp, color = Color(0xFF123A5E), fontWeight = FontWeight.Bold)
    }
}

/** 结算/信息用：牌桌上的倒计时闹钟（黄壳 + 红数字） */
@Composable
fun QqAlarmTimer(seconds: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(Color(0xFFFFC233), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            // 钟面
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFFFFF6DC), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${seconds.coerceIn(0, 99)}",
                    color = QqGame.AlarmRed,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
