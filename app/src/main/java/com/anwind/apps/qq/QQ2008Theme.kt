package com.anwind.apps.qq

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * QQ2008 经典皮肤：颜色常量 + 复用控件。
 *
 * 配色取自 2008 年 QQ 原版截图：
 *  - 窗口头部 / 标题：天蓝渐变（#6CBDF4 → #2E86D5）
 *  - 面板底色：浅蓝（#EAF6FE → #CBE7FB），边框 #6FA6D8
 *  - 好友列表选中：亮蓝 #BFE4FB
 *  - 按钮：米黄渐变（#FDF6E0 → #EBD9A8）+ 棕边框 —— 经典"登录/发送"按钮
 *  - 昵称彩色（红/橙/绿/紫），签名灰蓝
 */
object QQ2008 {
    // 渐变与底色
    val TitleTop = Color(0xFF6CBDF4)
    val TitleBottom = Color(0xFF2E86D5)
    val PanelTop = Color(0xFFEAF6FE)
    val PanelBottom = Color(0xFFCBE7FB)
    val PanelBorder = Color(0xFF6FA6D8)
    val InnerCard = Color(0xFFFDFEFF)
    val GroupBarTop = Color(0xFFF2FAFE)
    val GroupBarBottom = Color(0xFFDCEFFB)
    val SelectedRow = Color(0xFFBFE4FB)
    val HoverRow = Color(0xFFE3F2FD)

    // 按钮（米黄渐变）
    val BtnTop = Color(0xFFFDF6E0)
    val BtnBottom = Color(0xFFEBD9A8)
    val BtnBorder = Color(0xFFB09A62)

    // 文本
    val TextMain = Color(0xFF1A3A5C)
    val TextSub = Color(0xFF5B7B99)
    val TextOnBlue = Color.White

    // 昵称彩色轮换（QQ2008 好友昵称五颜六色）
    val NickColors = listOf(
        Color(0xFFC3272B), Color(0xFFD26900), Color(0xFF2E7D32),
        Color(0xFF7B1FA2), Color(0xFF0277BD), Color(0xFFAD1457)
    )

    // 聊天窗口
    val ChatMenuBg = Color(0xFFE8F4FD)
    val ChatAreaBg = Color.White
    val InputAreaBg = Color.White
    val SelfNick = Color(0xFF0B62C4)     // 自己昵称蓝
    val FriendNick = Color(0xFF8B2A2A)   // 对方昵称暗红
    val TimeText = Color(0xFF8FA8BE)

    /** 昵称颜色（按号码稳定分配） */
    fun nickColor(number: String): Color {
        val idx = (number.hashCode() and 0x7FFFFFFF) % NickColors.size
        return NickColors[idx]
    }

    /** 头部蓝渐变 */
    fun titleBrush() = Brush.verticalGradient(listOf(TitleTop, TitleBottom))

    /** 面板蓝渐变 */
    fun panelBrush() = Brush.verticalGradient(listOf(PanelTop, PanelBottom))

    /** 按钮米黄渐变（pressed 时反转渐变方向模拟按压） */
    fun btnBrush(pressed: Boolean = false) =
        Brush.verticalGradient(
            if (pressed) listOf(BtnBottom, BtnTop) else listOf(BtnTop, BtnBottom)
        )
}

/**
 * 提示音：用 ToneGenerator 模拟经典 QQ "滴滴" 声，不依赖音频资产。
 */
object QQSounds {
    private var tone: ToneGenerator? = null

    private fun beep(t: Int, durationMs: Int = 150) {
        runCatching {
            val gen = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
            gen.startTone(t, durationMs)
        }
    }

    /** 收到消息：短促滴 */
    fun msgIn() = beep(ToneGenerator.TONE_PROP_BEEP, 80)

    /** 发出消息：一声确认 */
    fun msgOut() = beep(ToneGenerator.TONE_PROP_ACK, 60)

    /** 窗口抖动：错误音拉长当"哐哐" */
    fun nudge() = beep(ToneGenerator.TONE_PROP_NACK, 120)

    /** 上线：确认双音 */
    fun login() = beep(ToneGenerator.TONE_SUP_CONFIRM, 200)
}

/**
 * QQ 头像：emoji 头像渲染在浅蓝渐变底上，可带右下角状态圆点。
 *
 * @param avatar  "emoji:🐧" 形式（其余前缀按原样渲染文本）
 * @param status  非 null 时绘制状态圆点
 */
@Composable
fun QqAvatar(
    avatar: String,
    size: Dp,
    status: QQStatus? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(QQ2008.panelBrush(), RoundedCornerShape(4.dp))
                .border(1.dp, QQ2008.PanelBorder.copy(alpha = 0.6f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = avatar.removePrefix("emoji:").ifEmpty { "🐧" },
                fontSize = (size.value * 0.62f).sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        if (status != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size.value * 0.34f).dp)
                    .background(Color.White, RoundedCornerShape(50))
                    .padding(1.dp)
                    .background(Color(status.dot), RoundedCornerShape(50))
            )
        }
    }
}

/**
 * 经典米黄按钮（登录 / 发送 / 设置）。按下时渐变反转模拟 2008 年的按压感。
 */
@Composable
fun QqBeigeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 30.dp,
    fontSize: TextUnit = 14.sp
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .height(height)
            .background(
                if (enabled) QQ2008.btnBrush(pressed)
                else Brush.verticalGradient(listOf(Color(0xFFF0EFEA), Color(0xFFE2E0D8))),
                RoundedCornerShape(4.dp)
            )
            .border(1.dp, QQ2008.BtnBorder, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color(0xFF3B2F10) else Color(0xFF9C988B),
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 蓝色渐变小标题条（面板内区块标题，如 "QQ好友"），可点击（收起/展开分组）。
 */
@Composable
fun QqSectionBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: String? = "🐧",
    trailing: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .background(QQ2008.titleBrush())
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Text(text = leading, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = title,
            color = QQ2008.TextOnBlue,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(text = trailing, color = QQ2008.TextOnBlue, fontSize = 11.sp)
        }
    }
}

/** 状态圆点（下拉/列表内小图标） */
@Composable
fun QqStatusDot(status: QQStatus, size: Dp = 10.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(Color(status.dot), RoundedCornerShape(50))
            .border(0.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(50))
    )
}
