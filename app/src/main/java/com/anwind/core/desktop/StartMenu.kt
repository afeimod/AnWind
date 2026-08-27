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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager

/**
 * 开始菜单：固定应用网格 + 搜索框 + 电源按钮。
 *
 * 视觉重构后所有主题统一采用 Win11 风格：
 * - 浮动圆角矩形（与任务栏居中对齐）
 * - Mica/Aero 半透明材质 + 阴影
 * - 搜索框（顶部居中）
 * - 固定应用网格（中部）
 * - 推荐区（底部可选）
 * - 电源按钮（右下）
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
            .heightIn(min = 420.dp, max = 540.dp)
            .shadow(12.dp, theme.startMenuShape)
            .clip(theme.startMenuShape)
            .background(theme.startMenuColor.copy(alpha = theme.startMenuAlpha))
            .border(1.dp, theme.windowBorderColor, theme.startMenuShape)
            .pointerInput(Unit) {
                // 阻止点击菜单自身时关闭
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            // ===== 搜索栏（顶部居中） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(theme.windowBackgroundColor)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "搜索",
                    tint = if (theme.isDark) Color.White else Color.Gray,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "搜索应用",
                    color = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.5f),
                    fontSize = theme.fontSizeSmall
                )
            }

            Spacer(Modifier.height(16.dp))

            // ===== 固定应用标题 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "所有应用",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = theme.fontSizeSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))

            // ===== 应用网格 =====
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
                            .clip(RoundedCornerShape(6.dp))
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
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(theme.accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            IconPainter(app.iconAsset, size = 28.dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = app.displayName,
                            color = if (theme.isDark) Color.White else Color.Black,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== 底部电源按钮 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(theme.buttonBackgroundColor)
                        .clickable {
                            // 关闭所有窗口，回到桌面
                            wm.closeAll()
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "电源",
                        tint = if (theme.isDark) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
