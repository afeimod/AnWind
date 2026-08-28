package com.anwind.core.desktop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.WinTheme
import com.anwind.util.L
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * v2.14 锁屏控制器：跨组合共享的锁定状态
 * （设置→个性化→锁屏界面 / 开始菜单电源→锁定 都能触发）。
 */
object LockController {
    var locked by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun lock() { locked = true }

    fun unlock() { locked = false }
}

/**
 * v2.14 锁屏界面：
 * - 全屏壁纸 + 深色遮罩（模糊观感，无真实 blur 保证低端机流畅）
 * - 中央大时钟 + 日期 + 周几（跟随 12/24 小时制设置）
 * - 上滑超过 120dp 或直接点击任意处解锁；拦截返回键
 */
@Composable
fun LockScreenLayer(
    theme: WinTheme,
    customWallpaperUri: String?,
    timeFormat24h: Boolean,
    onUnlock: () -> Unit
) {
    // 拦截系统返回键：锁屏状态下不允许返回退出
    BackHandler(enabled = true) { /* 吞掉返回事件 */ }

    // 每秒刷新时钟
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            delay(1000)
        }
    }

    // 上滑手势累计量：向上滑超过阈值触发解锁，同时让内容跟随手指上移（跟手体验）
    var dragUpPx by remember { mutableFloatStateOf(0f) }

    val cal = remember(tick) { Calendar.getInstance() }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val timeText = if (timeFormat24h) {
        String.format("%02d:%02d", hour, minute)
    } else {
        val h = if (hour % 12 == 0) 12 else hour % 12
        String.format("%d:%02d %s", h, minute, if (hour < 12) "AM" else "PM")
    }
    val dateText = remember(tick) {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val dowZh = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")[dow - 1]
        val dowEn = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[dow - 1]
        if (com.anwind.util.L10n.current == "en-US") {
            "$dowEn, $m/$d/$y"
        } else {
            "$y 年 $m 月 $d 日 $dowZh"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 上滑解锁手势：不消费点击（点击由下层 detectTapGestures 处理）
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        if (amount < 0) dragUpPx -= amount   // 向上滑为负值，累计正值
                    },
                    onDragEnd = {
                        val threshold = 120.dp.toPx()
                        if (dragUpPx > threshold) onUnlock() else dragUpPx = 0f
                    }
                )
            }
            // 点击任意处直接解锁（移动端最顺手的方式）
            .pointerInput(Unit) {
                androidx.compose.foundation.gestures.detectTapGestures(
                    onTap = { onUnlock() }
                )
            }
    ) {
        // 壁纸 + 深色遮罩（上滑时整体跟手上移 + 渐隐）
        val unlockProgress = (dragUpPx / 480.dp.toPx()).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -unlockProgress * 180f
                    alpha = 1f - unlockProgress * 0.85f
                }
        ) {
            WallpaperLayer(
                themeWallpaper = theme.wallpaperAsset,
                customWallpaperUri = customWallpaperUri,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            )
        }

        // 中央时钟 + 日期（跟随上滑位移，产生视差）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(bottom = 96.dp)
                .graphicsLayer { translationY = -unlockProgress * 320f }
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = L("已锁定"),
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 18.dp)
            )
            Text(
                text = timeText,
                color = Color.White,
                fontSize = 76.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = dateText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )
        }

        // 底部解锁提示（跟随上滑位移）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    translationY = -unlockProgress * 260f
                    alpha = 1f - unlockProgress
                }
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = L("上滑或点击解锁"),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
    }
}
