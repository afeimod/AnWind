package com.anwind.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CancelPresentation
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.anwind.AnWindApp
import com.anwind.core.input.keyboardAware
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.Themes
import com.anwind.core.theme.WindowsVariant
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowManager
import com.anwind.data.db.entity.ShortcutEntity
import com.anwind.data.model.DesktopItem
import com.anwind.data.model.DesktopItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 桌面右键菜单数据
 *
 * v2.11：iconItem 非空时弹出"图标右键菜单"（打开/重命名/删除/属性），
 * 为空时弹出"桌面右键菜单"（Win11 标准布局）。
 */
data class DesktopContextMenuData(
    val x: Float,
    val y: Float,
    val iconItem: DesktopItem? = null
)

/**
 * 桌面右键上下文菜单（v2.11 全面重构，Win11 标准布局）。
 *
 * 桌面空白处（双指轻点）：
 * - 查看        → 子菜单：大/中等/小图标（写入 iconSize 设置，与设置中心一致）
 * - 排序方式    → 子菜单：默认 / 按名称 / 按类型（持久化，与设置中心共用 DataStore）
 * - 刷新        → 真正触发桌面图标网格重建（重新加载图标位图）
 * - 新建快捷方式 → 快捷方式创建向导
 * - 切换主题    → 子菜单：直接切换 Win95/XP/7/10/11（与"设置-个性化"同一入口，
 *                修复旧版打开不存在的 "theme" 设置分区导致内容空白的不一致 Bug）
 * - 显示设置    → 设置中心-系统
 * - 个性化      → 设置中心-个性化（修复旧版打开"系统"分区的不一致）
 * - 打开终端 / 任务管理器 / 关闭所有窗口
 *
 * 桌面图标上（双指轻点命中图标）：
 * - 打开 / 重命名（快捷方式）/ 删除（快捷方式）/ 属性
 *
 * 菜单通过 PopupPositionProvider 自动钳制在屏幕内，边缘呼出不会溢出。
 */
@Composable
fun DesktopContextMenu(
    data: DesktopContextMenuData,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    var showShortcutDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<DesktopItem?>(null) }
    var showPropsDialog by remember { mutableStateOf<DesktopItem?>(null) }

    val positionProvider = remember(data.x, data.y) { ClampedMenuPositionProvider(data.x, data.y) }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = modifier
                .width(252.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .padding(5.dp)
        ) {
            if (data.iconItem != null) {
                // ==================== 图标右键菜单 ====================
                val item = data.iconItem
                val isShortcut = item.type != DesktopItemType.BUILTIN_APP
                ContextMenuEntry(icon = Icons.Default.Launch, label = "打开") {
                    launchDesktopItem(item, wm)
                    onDismiss()
                }
                if (isShortcut) {
                    ContextMenuDivider()
                    ContextMenuEntry(icon = Icons.Default.DriveFileRenameOutline, label = "重命名") {
                        showRenameDialog = item
                        onDismiss()
                    }
                    ContextMenuEntry(icon = Icons.Default.Delete, label = "删除") {
                        val shortcutId = item.id.removePrefix("shortcut_").toLongOrNull()
                        if (shortcutId != null) {
                            scope0.launch {
                                withContext(Dispatchers.IO) {
                                    app.database.shortcutDao().deleteById(shortcutId)
                                }
                            }
                        }
                        onDismiss()
                    }
                }
                ContextMenuDivider()
                ContextMenuEntry(icon = Icons.Default.Info, label = "属性") {
                    showPropsDialog = item
                    onDismiss()
                }
            } else {
                // ==================== 桌面右键菜单 ====================
                // —— 当前偏好（与设置中心共享同一 DataStore，保证处处一致） ——
                val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
                val sortMode by app.settingsStore.desktopSort.collectAsState(initial = "default")
                val activeTheme by app.themeManager.activeTheme.collectAsState(initial = Themes.Win11)

                // 展开中的子菜单（"view" / "sort" / "theme" / null）
                var expandedSub by remember { mutableStateOf<String?>(null) }

                // ===== 查看（图标大小） =====
                SubmenuEntry(
                    icon = Icons.Default.GridView,
                    label = "查看",
                    expanded = expandedSub == "view",
                    onToggle = { expandedSub = if (expandedSub == "view") null else "view" }
                ) {
                    RadioOption("大图标", iconSize >= 60f) {
                        scope0.launch { app.settingsStore.setIconSize(64f) }
                    }
                    RadioOption("中等图标", iconSize in 40f..59f) {
                        scope0.launch { app.settingsStore.setIconSize(48f) }
                    }
                    RadioOption("小图标", iconSize < 40f) {
                        scope0.launch { app.settingsStore.setIconSize(36f) }
                    }
                }

                // ===== 排序方式 =====
                SubmenuEntry(
                    icon = Icons.Default.SortByAlpha,
                    label = "排序方式",
                    expanded = expandedSub == "sort",
                    onToggle = { expandedSub = if (expandedSub == "sort") null else "sort" }
                ) {
                    RadioOption("默认（内置应用优先）", sortMode == "default") {
                        scope0.launch { app.settingsStore.setDesktopSort("default") }
                    }
                    RadioOption("按名称", sortMode == "name") {
                        scope0.launch { app.settingsStore.setDesktopSort("name") }
                    }
                    RadioOption("按类型", sortMode == "type") {
                        scope0.launch { app.settingsStore.setDesktopSort("type") }
                    }
                }

                // ===== 刷新（真正重建桌面网格，重载图标位图） =====
                ContextMenuEntry(icon = Icons.Default.Refresh, label = "刷新") {
                    onRefresh()
                    onDismiss()
                }

                ContextMenuDivider()

                // ===== 新建快捷方式 =====
                ContextMenuEntry(icon = Icons.Default.Link, label = "新建快捷方式") {
                    showShortcutDialog = true
                    onDismiss()
                }

                ContextMenuDivider()

                // ===== 切换主题（直接切换，与设置-个性化同一入口） =====
                SubmenuEntry(
                    icon = Icons.Default.FormatPaint,
                    label = "切换主题",
                    expanded = expandedSub == "theme",
                    onToggle = { expandedSub = if (expandedSub == "theme") null else "theme" }
                ) {
                    WindowsVariant.values().forEach { variant ->
                        RadioOption(variant.displayName, activeTheme.variant == variant) {
                            scope0.launch { app.themeManager.setTheme(variant) }
                        }
                    }
                }

                // ===== 显示设置 / 个性化（跳到设置中心对应分区） =====
                ContextMenuEntry(icon = Icons.Default.Monitor, label = "显示设置") {
                    wm.open(
                        appId = "settings",
                        title = "设置",
                        launchMode = LaunchMode.FLOATING,
                        launchArgs = mapOf("section" to "system")
                    )
                    onDismiss()
                }
                ContextMenuEntry(icon = Icons.Default.Palette, label = "个性化") {
                    wm.open(
                        appId = "settings",
                        title = "设置",
                        launchMode = LaunchMode.FLOATING,
                        launchArgs = mapOf("section" to "personalization")
                    )
                    onDismiss()
                }

                ContextMenuDivider()

                // ===== 打开终端 / 任务管理器 / 关闭所有窗口 =====
                ContextMenuEntry(icon = Icons.Default.Terminal, label = "打开终端") {
                    openApp("terminal", wm)
                    onDismiss()
                }
                ContextMenuEntry(icon = Icons.Default.Analytics, label = "任务管理器") {
                    openApp("sysinfo", wm)
                    onDismiss()
                }
                if (wm.windows.isNotEmpty()) {
                    ContextMenuEntry(icon = Icons.Default.CancelPresentation, label = "关闭所有窗口") {
                        wm.closeAll()
                        onDismiss()
                    }
                }
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

    // 重命名对话框（快捷方式）
    showRenameDialog?.let { item ->
        RenameShortcutDialog(
            item = item,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newLabel ->
                val shortcutId = item.id.removePrefix("shortcut_").toLongOrNull()
                if (shortcutId != null) {
                    scope0.launch {
                        withContext(Dispatchers.IO) {
                            val dao = app.database.shortcutDao()
                            dao.getAll().firstOrNull { it.id == shortcutId }?.let { entity ->
                                dao.update(entity.copy(label = newLabel.trim()))
                            }
                        }
                    }
                }
                showRenameDialog = null
            }
        )
    }

    // 属性对话框
    showPropsDialog?.let { item ->
        ShortcutPropertiesDialog(
            item = item,
            onDismiss = { showPropsDialog = null }
        )
    }
}

/** 从 AppRegistry 启动一个内置应用（右键菜单"打开终端/任务管理器"用） */
private fun openApp(appId: String, wm: WindowManager) {
    val appDef = com.anwind.core.window.AppRegistry.get(appId) ?: return
    wm.open(
        appId = appDef.id,
        title = appDef.displayName,
        launchMode = appDef.launchMode,
        initialWidth = appDef.defaultWidth.value.toInt(),
        initialHeight = appDef.defaultHeight.value.toInt()
    )
}

/**
 * 菜单定位器：以双指中点为锚点，自动钳制在屏幕内。
 * 旧版用固定 offset，靠近屏幕右/下缘呼出时菜单会溢出屏幕。
 */
private class ClampedMenuPositionProvider(
    private val anchorX: Float,
    private val anchorY: Float
) : PopupPositionProvider {
    /**
     * Compose UI 1.6（BOM 2024.06.00）的接口签名：
     * (anchorBounds, windowSize, layoutDirection, popupContentSize) -> IntOffset
     */
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        var x = anchorX.roundToInt()
        var y = anchorY.roundToInt()
        if (x + popupContentSize.width > windowSize.width) {
            x = windowSize.width - popupContentSize.width
        }
        if (y + popupContentSize.height > windowSize.height) {
            y = windowSize.height - popupContentSize.height
        }
        if (x < 0) x = 0
        if (y < 0) y = 0
        return IntOffset(x, y)
    }
}

// ============================================================
// 菜单项组件
// ============================================================

/** 普通菜单项：图标 + 文字，按压时高亮，点击执行动作 */
@Composable
private fun ContextMenuEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (theme.isDark) Color.White else Color.Black
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (pressed) theme.accentColor.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon, null,
            tint = if (theme.isDark) Color(0xCCFFFFFF) else Color(0x99000000),
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp
        )
    }
}

/** 可展开子菜单项：图标 + 文字 + 旋转箭头，点击原地展开选项列表 */
@Composable
private fun SubmenuEntry(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalWinTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (theme.isDark) Color.White else Color.Black
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    when {
                        expanded -> theme.accentColor.copy(alpha = 0.10f)
                        pressed -> theme.accentColor.copy(alpha = 0.14f)
                        else -> Color.Transparent
                    }
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onToggle
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (theme.isDark) Color(0xCCFFFFFF) else Color(0x99000000),
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = fg,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ExpandMore, null,
                tint = if (theme.isDark) Color(0x99FFFFFF) else Color(0x66000000),
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
            ) {
                content()
            }
        }
    }
}

/** 子菜单单选项：勾选标记 + 文字 */
@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = if (theme.isDark) Color.White else Color.Black
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(
                when {
                    selected -> theme.accentColor.copy(alpha = 0.12f)
                    pressed -> theme.accentColor.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 勾选标记占位（保持文字对齐）
        Box(modifier = Modifier.size(17.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    Icons.Default.Check, null,
                    tint = theme.accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 12.5.sp
        )
    }
}

@Composable
private fun ContextMenuDivider() {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(1.dp)
            .background(theme.windowBorderColor.copy(alpha = 0.35f))
    )
}

// ============================================================
// 重命名 / 属性对话框
// ============================================================

/** 重命名快捷方式对话框 */
@Composable
private fun RenameShortcutDialog(
    item: DesktopItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    val fg = if (theme.isDark) Color.White else Color.Black
    var text by remember(item.id) { mutableStateOf(item.label) }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(
                "重命名快捷方式",
                color = fg,
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = fg, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (theme.isDark) Color(0x22FFFFFF) else Color(0x11000000),
                        RoundedCornerShape(6.dp)
                    )
                    .keyboardAware(
                        value = { text },
                        onValue = { text = it },
                        singleLine = true
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { if (text.isNotBlank()) onConfirm(text) },
                    enabled = text.isNotBlank()
                ) { Text("确定", color = theme.accentColor) }
            }
        }
    }
}

/** 快捷方式/应用 属性对话框 */
@Composable
private fun ShortcutPropertiesDialog(
    item: DesktopItem,
    onDismiss: () -> Unit
) {
    val theme = LocalWinTheme.current
    val typeName = when (item.type) {
        DesktopItemType.BUILTIN_APP -> "内置应用"
        DesktopItemType.SHORTCUT_URL -> "网址快捷方式"
        DesktopItemType.SHORTCUT_FILE -> "文件快捷方式"
        DesktopItemType.SHORTCUT_APP -> "应用快捷方式"
    }
    val appDef = com.anwind.core.window.AppRegistry.get(item.target)

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(
                "${item.label} 属性",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 16.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.height(14.dp))
            PropertyRow("名称", item.label)
            PropertyRow("类型", typeName)
            if (appDef != null) {
                PropertyRow("目标应用", appDef.displayName)
                PropertyRow("启动模式", if (appDef.launchMode == LaunchMode.FULLSCREEN) "全屏" else "浮动窗口")
            } else if (item.type != DesktopItemType.BUILTIN_APP) {
                PropertyRow("目标", item.target)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("关闭", color = theme.accentColor) }
            }
        }
    }
}

@Composable
private fun PropertyRow(key: String, value: String) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$key：",
            color = theme.secondaryTextColor,
            fontSize = 12.sp,
            modifier = Modifier.width(76.dp)
        )
        Text(
            text = value,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
