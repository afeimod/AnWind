package com.anwind.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager

/**
 * 开始菜单：固定应用网格 + 搜索框 + 电源按钮。
 *
 * 样式根据主题调整：
 * - Win95/XP: 经典两栏式（左侧应用列表 + 顶部用户栏）
 * - Win7/10: 单栏扁平列表
 * - Win11: 圆角浮动卡片 + 居中应用网格
 */
@Composable
fun StartMenu(
    theme: WinTheme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val allApps = remember { AppRegistry.all() }

    Box(
        modifier = modifier
            .width(theme.startMenuWidth)
            .heightIn(min = 360.dp, max = 480.dp)
            .shadow(8.dp, theme.startMenuShape)
            .background(theme.startMenuColor.copy(alpha = theme.startMenuAlpha), theme.startMenuShape)
            .border(1.dp, theme.windowBorderColor, theme.startMenuShape)
            .clickable(
                // 点击菜单自身不关闭，点击外部才关闭
                onClick = {}
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {

            // ===== 搜索栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(theme.windowBackgroundColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🔍 搜索应用...",
                    color = theme.buttonTextColor.copy(alpha = 0.5f),
                    fontSize = theme.fontSizeBody
                )
            }

            Spacer(Modifier.height(12.dp))

            // ===== 应用网格 =====
            Text(
                text = "所有应用",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = theme.fontSizeSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(allApps, key = { it.id }) { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable {
                                wm.open(
                                    appId = app.id,
                                    title = app.displayName,
                                    launchMode = app.launchMode,
                                    initialWidth = app.defaultWidth.value.toInt(),
                                    initialHeight = app.defaultHeight.value.toInt()
                                )
                                onDismiss()
                            }
                            .padding(4.dp)
                    ) {
                        IconPainter(app.iconAsset, size = 32.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = app.displayName,
                            color = if (theme.isDark) Color.White else Color.Black,
                            fontSize = 10.sp,
                            maxLines = 1,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== 底部电源 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Power",
                    tint = if (theme.isDark) Color.White else Color.Black,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            // 简化：关闭所有窗口，回到桌面
                            wm.closeAll()
                            onDismiss()
                        }
                )
            }
        }
    }
}
