package com.anwind.core.desktop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.AnWindApp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager
import com.anwind.data.model.DesktopItem
import com.anwind.data.model.DesktopItemType
import com.anwind.data.model.Shortcut
import java.io.IOException

/**
 * 桌面图标网格：靠左上角、**先竖向填充再横向换列**排列（Windows 桌面真实样式）。
 *
 * 与之前用 FlowRow 不同：Windows 桌面图标是按列组织的——从上到下铺满一列后再
 * 跳到右边下一列继续从上到下，而不是从左到右铺满一行后再换行。
 * 这样在窄屏（手机竖屏）下也能合理利用屏幕高度。
 *
 * 数据来源：
 * 1. AppRegistry 中 pinnedToDesktop=true 的内置应用
 * 2. 数据库中保存的快捷方式
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopIconGrid() {
    val app = AnWindApp.get()
    val shortcuts by app.database.shortcutDao().observeAll().collectAsState(initial = emptyList())
    val builtinApps = remember { AppRegistry.desktopApps() }
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)

    // 合并桌面项
    val items: List<DesktopItem> = remember(shortcuts, builtinApps) {
        val builtin = builtinApps.mapIndexed { idx, app ->
            DesktopItem(
                id = "app_${app.id}",
                label = app.displayName,
                iconAsset = app.iconAsset,
                type = DesktopItemType.BUILTIN_APP,
                target = app.id,
                sortOrder = idx
            )
        }
        val shortcutItems = shortcuts.map { entity ->
            DesktopItem(
                id = "shortcut_${entity.id}",
                label = entity.label,
                iconAsset = entity.iconAsset,
                type = when (entity.type) {
                    1 -> DesktopItemType.SHORTCUT_URL
                    2 -> DesktopItemType.SHORTCUT_FILE
                    3 -> DesktopItemType.SHORTCUT_APP
                    else -> DesktopItemType.SHORTCUT_URL
                },
                target = entity.target,
                sortOrder = entity.sortOrder
            )
        }
        builtin + shortcutItems
    }

    // 使用 FlowColumn：先竖向（从上到下）填充，超出屏幕高度后自动换到下一列
    // （从左到右），完整还原 Windows 桌面图标的排列方式。
    // 外层 horizontalScroll 让超出屏幕右边界的列可水平滚动访问。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .horizontalScroll(rememberScrollState())
    ) {
        FlowColumn(
            modifier = Modifier
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { item ->
                DesktopIcon(item = item, iconSize = iconSize)
            }
        }
    }
}

@Composable
private fun DesktopIcon(item: DesktopItem, iconSize: Float) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val app = AppRegistry.get(item.target) // 内置应用
    val iconPx = iconSize.dp
    val cellWidth = (iconSize + 24).dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(cellWidth)
            .pointerInput(item.id) {
                detectTapGestures(
                    onDoubleTap = {
                        // 双击启动
                        when (item.type) {
                            DesktopItemType.BUILTIN_APP -> {
                                app?.let { a ->
                                    wm.open(
                                        appId = a.id,
                                        title = a.displayName,
                                        launchMode = a.launchMode,
                                        initialWidth = a.defaultWidth.value.toInt(),
                                        initialHeight = a.defaultHeight.value.toInt()
                                    )
                                }
                            }
                            DesktopItemType.SHORTCUT_URL,
                            DesktopItemType.SHORTCUT_FILE -> {
                                // 通过浏览器打开
                                wm.open(
                                    appId = "browser",
                                    title = "Browser",
                                    launchMode = AppRegistry.get("browser")?.launchMode
                                        ?: com.anwind.core.window.LaunchMode.FLOATING,
                                    launchArgs = mapOf(
                                        "url" to item.target,
                                        "type" to item.type.name
                                    )
                                )
                            }
                            DesktopItemType.SHORTCUT_APP -> {
                                val targetApp = AppRegistry.get(item.target)
                                targetApp?.let { a ->
                                    wm.open(
                                        appId = a.id,
                                        title = a.displayName,
                                        launchMode = a.launchMode,
                                        initialWidth = a.defaultWidth.value.toInt(),
                                        initialHeight = a.defaultHeight.value.toInt()
                                    )
                                }
                            }
                        }
                    }
                )
            }
            .padding(4.dp)
    ) {
        // 图标
        IconPainter(item.iconAsset, size = iconPx)

        Spacer(Modifier.height(2.dp))

        // 文字
        Text(
            text = item.label,
            color = theme.desktopIconTextColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(cellWidth)
        )
    }
}

/**
 * 统一图标加载：支持 assets 路径 / "emoji:🚀" / 内置 drawable。
 */
@Composable
fun IconPainter(asset: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current

    if (asset.startsWith("emoji:")) {
        val emoji = asset.removePrefix("emoji:")
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = (size.value * 0.7f).sp)
        }
        return
    }

    val painter = remember(asset) {
        runCatching {
            context.assets.open(asset).use {
                BitmapPainter(BitmapFactory.decodeStream(it).asImageBitmap())
            }
        }.getOrNull()
    }

    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        // 兜底：使用 emoji 占位
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(size)
                .background(LocalWinTheme.current.accentColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = 14.sp, color = LocalWinTheme.current.accentColor)
        }
    }
}
