package com.anwind.core.desktop

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.WinTheme
import java.util.Calendar

/**
 * Win11 风格日历弹窗 - 点击任务栏时钟后显示
 *
 * - 顶部：当前日期 + 下拉箭头
 * - 月份导航：← 2026年8月 →
 * - 月历网格：星期一~星期日，今日高亮（圆形背景）
 * - 半透明 Mica/Acrylic 风格
 */
@Composable
fun CalendarFlyout(
    theme: WinTheme,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    val now = remember { Calendar.getInstance() }
    var displayYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var displayMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) }

    val today = Triple(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))

    val monthCalendar = remember(displayYear, displayMonth) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, displayYear)
            set(Calendar.MONTH, displayMonth)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        cal
    }
    val daysInMonth = monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    // 一=0,二=1,...,日=6
    val firstDayOfWeek = (monthCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7

    val weekdayNames = listOf("日", "一", "二", "三", "四", "五", "六")
    val monthNames = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
    val headerText = "星期${weekdayNames[now.get(Calendar.DAY_OF_WEEK) - 1]}, " +
            "${now.get(Calendar.DAY_OF_MONTH)} ${monthNames[now.get(Calendar.MONTH)]}"

    val popupColor = if (theme.isDark) Color(0xE6323232) else Color(0xE6F9F9F9)

    Box(
        modifier = modifier
            .width(320.dp)
            .height(360.dp)
            .shadow(16.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(popupColor)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部：日期 + 下拉箭头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerText,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // 月份导航
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ChevronLeft, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            var m = displayMonth - 1
                            var y = displayYear
                            if (m < 0) { m = 11; y-- }
                            displayMonth = m; displayYear = y
                        }
                        .padding(2.dp)
                )
                Text(
                    text = "${displayYear}年${displayMonth + 1}月",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Icon(
                    Icons.Default.ChevronRight, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable {
                            var m = displayMonth + 1
                            var y = displayYear
                            if (m > 11) { m = 0; y++ }
                            displayMonth = m; displayYear = y
                        }
                        .padding(2.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            // 星期表头
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
                    Text(
                        text = day,
                        color = theme.secondaryTextColor,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 日期网格 - 6 行
            for (week in 0..5) {
                if (week * 7 - firstDayOfWeek + 1 > daysInMonth) break
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)) {
                    for (dow in 0..6) {
                        val dayIndex = week * 7 + dow - firstDayOfWeek + 1
                        if (dayIndex < 1 || dayIndex > daysInMonth) {
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            val isToday = displayYear == today.first &&
                                    displayMonth == today.second &&
                                    dayIndex == today.third
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isToday) theme.accentColor else Color.Transparent
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dayIndex.toString(),
                                        color = if (isToday) Color.White
                                                else if (theme.isDark) Color.White else Color.Black,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Win11 风格快速设置面板 - 点击任务栏 wifi/电池/音量后显示
 *
 * - 顶部 3x2 toggle 网格：WiFi/Internet/Bluetooth/Nearby sharing/Theme/Accessibility
 * - 中部：音量滑块
 * - 底部：电池 % + 设置齿轮
 */
@Composable
fun QuickSettingsPanel(
    theme: WinTheme,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val popupColor = if (theme.isDark) Color(0xE6323232) else Color(0xE6F9F9F9)

    // 状态：从系统读取
    var wifiEnabled by remember {
        mutableStateOf(runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.isWifiEnabled
        }.getOrDefault(false))
    }
    var bluetoothEnabled by remember {
        mutableStateOf(runCatching {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        }.getOrDefault(false))
    }
    var volume by remember {
        mutableStateOf(runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0))
    }
    val maxVolume = remember {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(15)
    }
    val batteryPct = remember {
        runCatching {
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, ifilter)
            val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else 100
        }.getOrDefault(100)
    }

    Box(
        modifier = modifier
            .width(320.dp)
            .height(380.dp)
            .shadow(16.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(popupColor)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 顶部 3x2 toggle 网格 =====
            val toggles = listOf(
                QuickToggle("WiFi", if (wifiEnabled) "已连接" else "关闭",
                    Icons.Default.Wifi, wifiEnabled,
                    onClick = {
                        runCatching {
                            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                            @Suppress("DEPRECATION")
                            wm.isWifiEnabled = !wm.isWifiEnabled
                            wifiEnabled = wm.isWifiEnabled
                            Toast.makeText(context, if (wifiEnabled) "已开启 WiFi" else "已关闭 WiFi", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(context, "无法切换 WiFi: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }),
                QuickToggle("Internet", "互联网", Icons.Default.Cloud, false, onClick = {}),
                QuickToggle("蓝牙", if (bluetoothEnabled) "已连接" else "关闭",
                    Icons.Default.Bluetooth, bluetoothEnabled,
                    onClick = {
                        runCatching {
                            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                            if (adapter != null) {
                                if (adapter.isEnabled) adapter.disable() else adapter.enable()
                                bluetoothEnabled = !bluetoothEnabled
                            }
                        }.onFailure {
                            Toast.makeText(context, "无法切换蓝牙: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                    }),
                QuickToggle("附近共享", "关闭", Icons.Default.Share, false, onClick = {}),
                QuickToggle("主题", if (theme.isDark) "深色" else "浅色", Icons.Default.Palette, theme.isDark, onClick = {}),
                QuickToggle("辅助功能", "关闭", Icons.Default.Accessibility, false, onClick = {})
            )

            // 用 Column + Row 模拟 3x2 网格
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                toggles.chunked(3).forEach { rowToggles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowToggles.forEach { toggle ->
                            QuickToggleCell(toggle, theme, Modifier.weight(1f))
                        }
                        // 凑齐 3 列
                        repeat(3 - rowToggles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ===== 音量滑块 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (theme.isDark) Color(0x33FFFFFF) else Color(0x11000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.VolumeUp, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = volume.toFloat(),
                    onValueChange = { v ->
                        val newVol = v.toInt()
                        if (newVol != volume) {
                            runCatching {
                                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            }
                            volume = newVol
                        }
                    },
                    valueRange = 0f..maxVolume.toFloat(),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.weight(1f))

            // ===== 底部电池 + 设置 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.BatteryFull, null,
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$batteryPct%",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenSettings),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Settings, null,
                        tint = if (theme.isDark) Color.White else Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private data class QuickToggle(
    val label: String,
    val state: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val active: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun QuickToggleCell(
    toggle: QuickToggle,
    theme: WinTheme,
    modifier: Modifier = Modifier
) {
    val bg = if (toggle.active) theme.accentColor
             else if (theme.isDark) Color(0x22FFFFFF) else Color(0x11000000)
    val contentColor = if (toggle.active) Color.White
                       else if (theme.isDark) Color.White else Color.Black

    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = toggle.onClick
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            toggle.icon, null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = toggle.label,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = toggle.state,
            color = contentColor.copy(alpha = 0.7f),
            fontSize = 9.sp
        )
    }
}
