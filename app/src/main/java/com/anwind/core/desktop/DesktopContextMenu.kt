package com.anwind.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.anwind.AnWindApp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.WindowManager
import com.anwind.data.model.DesktopItemType
import com.anwind.data.model.Shortcut
import com.anwind.data.db.entity.ShortcutEntity

/**
 * 桌面右键菜单数据
 */
data class DesktopContextMenuData(
    val x: Float,
    val y: Float
)

/**
 * 桌面右键上下文菜单。
 *
 * 主要功能：
 * - 新建快捷方式（弹出向导对话框）
 * - 切换主题
 * - 打开设置
 * - 刷新桌面
 */
@Composable
fun DesktopContextMenu(
    data: DesktopContextMenuData,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    var showShortcutDialog by remember { mutableStateOf(false) }

    Popup(
        alignment = androidx.compose.ui.Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(data.x.toInt(), data.y.toInt()),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .width(220.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(4.dp))
                .padding(4.dp)
        ) {
            ContextMenuItem("新建快捷方式") {
                showShortcutDialog = true
                onDismiss()
            }
            ContextMenuItem("刷新") {
                // 简单触发 recompose：关闭再无操作
                onDismiss()
            }
            ContextMenuDivider()
            ContextMenuItem("个性化设置") {
                wm.open(
                    appId = "settings",
                    title = "设置",
                    launchMode = com.anwind.core.window.LaunchMode.FLOATING
                )
                onDismiss()
            }
            ContextMenuItem("切换主题") {
                wm.open(
                    appId = "settings",
                    title = "设置",
                    launchMode = com.anwind.core.window.LaunchMode.FLOATING,
                    launchArgs = mapOf("section" to "theme")
                )
                onDismiss()
            }
        }
    }

    // 快捷方式创建对话框
    if (showShortcutDialog) {
        ShortcutCreateDialog(
            onDismiss = { showShortcutDialog = false },
            onCreate = { shortcut ->
                scope0.launch {
                    withContext(Dispatchers.IO) {
                        app.database.shortcutDao().insert(
                            ShortcutEntity(
                                label = shortcut.label,
                                iconAsset = shortcut.iconAsset,
                                type = when (shortcut.type) {
                                    DesktopItemType.SHORTCUT_URL -> 1
                                    DesktopItemType.SHORTCUT_FILE -> 2
                                    DesktopItemType.SHORTCUT_APP -> 3
                                    else -> 1
                                },
                                target = shortcut.target,
                                launchArgs = shortcut.launchArgs,
                                sortOrder = 0
                            )
                        )
                    }
                }
                showShortcutDialog = false
            }
        )
    }
}

@Composable
private fun ContextMenuItem(label: String, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ContextMenuDivider() {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.windowBorderColor.copy(alpha = 0.3f))
            .padding(vertical = 4.dp)
    )
}
