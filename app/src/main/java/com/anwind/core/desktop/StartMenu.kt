package com.anwind.core.desktop

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountCircle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager

/**
 * 开始菜单：Win11 真实风格
 *
 * 参考 hyperdroid APK 截图布局：
 * - 浮动圆角矩形（圆角 ~12dp）
 * - Mica/Aero 半透明材质 + 阴影
 * - 顶部：搜索框（圆角药丸）
 * - 中部：固定应用网格（带 "Pinned" 标题 + "All apps >" 按钮）
 * - 底部：用户头像 + 用户名（左）+ 电源按钮（右）
 */
@Composable
fun StartMenu(
    theme: WinTheme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val allApps = remember { AppRegistry.all() }
    var searchText by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .width(theme.startMenuWidth)
            .heightIn(min = 460.dp, max = 580.dp)
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
        Column(modifier = Modifier.fillMaxSize()) {

            // ===== 顶部搜索栏 =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(theme.windowBackgroundColor)
                        .border(1.dp, theme.windowBorderColor.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        tint = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (theme.isDark) Color.White else Color.Black,
                            fontSize = 13.sp
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val q = searchText.trim()
                            if (q.isNotEmpty()) {
                                val target = if (q.contains(".") && !q.contains(" ")) {
                                    if (q.startsWith("http")) q else "https://$q"
                                } else {
                                    "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
                                }
                                wm.open(
                                    appId = "browser",
                                    title = "浏览器",
                                    launchMode = com.anwind.core.window.LaunchMode.FLOATING,
                                    launchArgs = mapOf("url" to target),
                                    initialWidth = 980,
                                    initialHeight = 640
                                )
                                onDismiss()
                            }
                        }),
                        modifier = Modifier.weight(1f),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor)
                    )
                    if (searchText.isEmpty()) {
                        Text(
                            text = "搜索应用和 Web",
                            color = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // ===== Pinned 标题 + All apps 按钮 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已固定",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { /* TODO: 切换到 "所有应用" 视图 */ }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "所有应用",
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        contentDescription = "All apps",
                        tint = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ===== 应用网格 =====
            val filteredApps = if (searchText.isBlank()) allApps
                              else allApps.filter { it.displayName.contains(searchText, ignoreCase = true) }

            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredApps, key = { it.id }) { app ->
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
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(theme.accentColor.copy(alpha = 0.10f)),
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

            // ===== 底部：用户头像 + 用户名（左）+ 电源按钮（右） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.windowBackgroundColor.copy(alpha = 0.5f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { /* TODO: 打开用户面板 */ }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = "用户",
                        tint = if (theme.isDark) Color.White else Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "AnWind",
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

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
