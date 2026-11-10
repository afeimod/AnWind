package com.anwind.core.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.AnWindApp
import com.anwind.core.theme.WinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowManager
import com.anwind.util.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v3.3.40：Windows 7 经典双栏开始菜单（用户需求 5 —— 按用户提供的
 * win7simu 截图重制 Win7 主题 UI）。
 *
 * 对照截图（Win7 Simu 3.0.2）逐面复刻：
 *  - **左栏（白底程序列表）**：固定程序纵向列表（图标 + 名称，悬停高亮），
 *    底部「所有程序 ▸」行展开全部应用（含手机应用，列表滚动）；
 *  - **右栏（玻璃蓝面板）**：顶部用户头像 + 用户名（点击 → 桌面设置），
 *    下方链接列（文档 / 图片 / 音乐 / 计算机 / 控制面板 / 默认程序 /
 *    帮助和支持）；
 *  - **底栏（横跨两栏）**：左侧「搜索程序和文件」搜索框 + 右侧
 *    「关机 ▸」按钮（箭头弹出：锁定 / 切换用户 / 关闭所有窗口）；
 *  - 视觉：左白右蓝玻璃双色 + 细描边 + 圆角 + 阴影，尺寸全部 dp/sp
 *    （经全局 UI 缩放 LocalDensity 覆盖随整体缩放生效）。
 *
 * 触发：[StartMenu] 在 theme.variant == WindowsVariant.WIN7 时路由到本页。
 */
@Composable
internal fun StartMenuWin7(
    theme: WinTheme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = remember { AnWindApp.get() }
    val allApps = remember { AppRegistry.all() }
    var searchText by remember { mutableStateOf("") }
    var showAllPrograms by remember { mutableStateOf(false) }

    val userName by app.settingsStore.userName.collectAsState(initial = "AnWind")
    val userAvatar by app.settingsStore.userAvatar.collectAsState(initial = "")
    val lockEnabled by app.settingsStore.lockEnabled.collectAsState(initial = true)

    // 手机应用（所有程序列表合入；IO 线程）
    val androidApps by produceState<List<AndroidApps.AppInfo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { AndroidApps.load(context) }.getOrNull() ?: emptyList()
        }
    }

    fun launchApp(appDef: AppDef) {
        wm.openOrRestore(
            appId = appDef.id,
            title = appDef.displayName,
            launchMode = appDef.launchMode,
            initialWidth = appDef.defaultWidth.value.toInt(),
            initialHeight = appDef.defaultHeight.value.toInt()
        )
        onDismiss()
    }

    fun openLink(appId: String, title: String, section: String? = null) {
        wm.open(
            appId = appId,
            title = title,
            launchMode = LaunchMode.FLOATING,
            launchArgs = if (section != null) mapOf("section" to section) else emptyMap(),
            initialWidth = 900,
            initialHeight = 620
        )
        onDismiss()
    }

    val submitSearch: () -> Unit = {
        val q = searchText.trim()
        if (q.isNotEmpty()) {
            val target = if (q.contains(".") && !q.contains(" ")) {
                if (q.startsWith("http")) q else "https://$q"
            } else {
                "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
            }
            wm.open(
                appId = "browser", title = "浏览器",
                launchMode = LaunchMode.FLOATING,
                launchArgs = mapOf("url" to target),
                initialWidth = 980, initialHeight = 640
            )
            onDismiss()
        }
    }

    // ===== 双栏主体（外框 chrome 由 [StartMenuWin7Root] 提供） =====
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 396.dp, max = 520.dp)
    ) {
        // ================= 左栏：程序列表（白底） =================
        Column(
            modifier = Modifier
                .weight(1.25f)
                .fillMaxHeight()
                .background(if (theme.isDark) Color(0xFF2A2A2A) else Color(0xFFFFFFFF))
                .padding(vertical = 4.dp)
        ) {
            val query = searchText.trim()
            val list = when {
                query.isNotEmpty() -> allApps.filter {
                    it.displayName.contains(query, ignoreCase = true)
                }
                showAllPrograms -> allApps
                else -> allApps.filter { it.pinnedToTaskbar || it.pinnedToDesktop }.ifEmpty { allApps.take(7) }
            }
            val androidMatches = if (query.isNotEmpty()) {
                (androidApps ?: emptyList()).filter { it.label.contains(query, ignoreCase = true) }
            } else if (showAllPrograms) {
                androidApps ?: emptyList()
            } else emptyList()

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
            ) {
                items(list, key = { "bi:${it.id}" }) { appDef ->
                    Win7ProgramRow(appDef, theme) { launchApp(appDef) }
                }
                items(androidMatches, key = { "an:${it.pkg}/${it.activity}" }) { info ->
                    Win7AndroidRow(info, theme) {
                        if (AndroidApps.launch(context, info)) onDismiss()
                    }
                }
                if (query.isEmpty() && !showAllPrograms) {
                    item {
                        // 空隙提示（左栏不满时保持视觉重心）
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // ===== 所有程序 ▸ =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (showAllPrograms) theme.accentColor.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { showAllPrograms = !showAllPrograms }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        L("所有程序"),
                        color = if (theme.isDark) Color.White else Color(0xFF1A3E62),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        tint = if (theme.isDark) Color.White else Color(0xFF1A3E62),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // ================= 右栏：玻璃蓝链接面板 =================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            theme.accentColor.copy(alpha = 0.22f),
                            theme.accentColor.copy(alpha = 0.34f),
                            theme.accentColor.copy(alpha = 0.18f)
                        )
                    )
                )
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ===== 用户头像 + 用户名（点击 → 桌面设置） =====
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        openLink("settings", "设置", section = "desktop")
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.9f))
                        .border(1.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (userAvatar.isNotBlank()) {
                        Text(
                            userAvatar,
                            fontSize = 26.sp,
                            modifier = Modifier.size(44.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    } else {
                        Icon(
                            Icons.Outlined.AccountCircle, null,
                            tint = Color(0xFF1A3E62),
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    userName,
                    color = if (theme.isDark) Color.White else Color(0xFF1A3E62),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.82f)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.45f))
            )
            Spacer(Modifier.height(4.dp))

            // ===== 右栏链接列（截图同款 7 项） =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp)
            ) {
                // 标签在组合期求值（L 为 @Composable）；点击 lambda 内用字面量
                //（openLink 的 title 仅为窗口标题兜底）
                val linkDoc = L("文档")
                val linkPic = L("图片")
                val linkMusic = L("音乐")
                val linkPc = L("计算机")
                val linkPanel = L("控制面板")
                val linkDefault = L("默认程序")
                val linkHelp = L("帮助和支持")
                Win7SideLink(linkDoc, theme) { openLink("file_explorer", "文档") }
                Win7SideLink(linkPic, theme) { openLink("file_explorer", "图片") }
                Win7SideLink(linkMusic, theme) { openLink("music", "音乐") }
                Win7SideLink(linkPc, theme) { openLink("file_explorer", "此电脑") }
                Win7SideLink(linkPanel, theme) { openLink("settings", "设置") }
                Win7SideLink(linkDefault, theme) { openLink("settings", "设置", section = "system") }
                Win7SideLink(linkHelp, theme) { openLink("sysinfo", "关于") }
            }
        }

    }
}

/**
 * Win7 开始菜单完整结构（双栏 + 底栏搜索/关机 —— 截图布局的完整复刻）。
 * [DesktopEnvironment] 经 [StartMenu] 路由到这里。
 */
@Composable
internal fun StartMenuWin7Root(
    theme: WinTheme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wm = remember { WindowManager.get() }
    val app = remember { AnWindApp.get() }
    var searchText by remember { mutableStateOf("") }
    val lockEnabled by app.settingsStore.lockEnabled.collectAsState(initial = true)

    Column(
        modifier = modifier
            .width(theme.startMenuWidth + 40.dp)
            .heightIn(min = 460.dp, max = 580.dp)
            .shadow(12.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(theme.startMenuColor.copy(alpha = theme.startMenuAlpha))
            .border(1.dp, theme.windowBorderColor, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { }, onDoubleTap = { }, onLongPress = { }
                )
            }
    ) {
        // 双栏主体（占满除底栏外的全部高度）
        StartMenuWin7(theme, onDismiss, Modifier.weight(1f))

        // ===== 底栏：搜索框（左） + 关机（右） =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (theme.isDark) Color(0xFF252525) else Color(0xFFEFF5FB)
                )
                .border(0.5.dp, theme.windowBorderColor.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 搜索框（win7 底部搜索同款）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (theme.isDark) Color(0xFF2E2E2E) else Color.White)
                    .border(1.dp, theme.windowBorderColor, RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search, null,
                    tint = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.55f),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 12.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            val q = searchText.trim()
                            if (q.isNotEmpty()) {
                                wm.open(
                                    appId = "browser", title = "浏览器",
                                    launchMode = LaunchMode.FLOATING,
                                    launchArgs = mapOf(
                                        "url" to "https://www.bing.com/search?q=" + android.net.Uri.encode(q)
                                    ),
                                    initialWidth = 980, initialHeight = 640
                                )
                                onDismiss()
                            }
                        }
                    ),
                    modifier = Modifier.weight(1f),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor)
                )
                if (searchText.isEmpty()) {
                    Text(
                        L("搜索程序和文件"),
                        color = (if (theme.isDark) Color.White else Color.Black).copy(alpha = 0.45f),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // ===== 关机按钮 + ▸ 箭头（截图：右下角「关机」+ 右侧小箭头） =====
            var powerMenuOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.verticalGradient(
                                if (theme.isDark) listOf(Color(0xFF454545), Color(0xFF303030))
                                else listOf(Color(0xFFF2F6FA), Color(0xFFDCE6F0))
                            )
                        )
                        .border(0.5.dp, Color(0xFF7A96B5).copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                        .clickable { powerMenuOpen = true }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text(
                        L("关机"),
                        color = if (theme.isDark) Color.White else Color(0xFF16324E),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 1.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.verticalGradient(
                                if (theme.isDark) listOf(Color(0xFF454545), Color(0xFF303030))
                                else listOf(Color(0xFFF2F6FA), Color(0xFFDCE6F0))
                            )
                        )
                        .border(0.5.dp, Color(0xFF7A96B5).copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                        .clickable { powerMenuOpen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "▸",
                        color = if (theme.isDark) Color.White else Color(0xFF16324E),
                        fontSize = 10.sp
                    )
                }

                DropdownMenu(
                    expanded = powerMenuOpen,
                    onDismissRequest = { powerMenuOpen = false }
                ) {
                    if (lockEnabled) {
                        DropdownMenuItem(
                            text = { Text(L("锁定")) },
                            leadingIcon = { Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp)) },
                            onClick = {
                                powerMenuOpen = false
                                onDismiss()
                                LockController.lock()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(L("切换用户")) },
                            leadingIcon = {
                                Icon(Icons.Outlined.AccountCircle, null, modifier = Modifier.size(16.dp))
                            },
                            onClick = {
                                powerMenuOpen = false
                                onDismiss()
                                LockController.lock()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(L("关闭所有窗口")) },
                        leadingIcon = {
                            Icon(Icons.Default.PowerSettingsNew, null, modifier = Modifier.size(16.dp))
                        },
                        onClick = {
                            powerMenuOpen = false
                            wm.closeAll()
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

// ============================================================
// Win7 开始菜单行组件
// ============================================================

/** 左栏程序行（图标 + 名称；悬停高亮蓝）。 */
@Composable
private fun Win7ProgramRow(
    appDef: AppDef,
    theme: WinTheme,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(theme.accentColor.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            IconPainter(appDef.iconAsset, size = 22.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            appDef.displayName,
            color = if (theme.isDark) Color.White else Color(0xFF16324E),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 左栏手机应用行（所有程序展开时合入）。 */
@Composable
private fun Win7AndroidRow(
    info: AndroidApps.AppInfo,
    theme: WinTheme,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) {
            AndroidAppIcon(packageName = info.pkg, icon = info.icon, size = 24.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            info.label,
            color = if (theme.isDark) Color.White else Color(0xFF16324E),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 右栏链接行（白字半透明底，悬停高亮）。 */
@Composable
private fun Win7SideLink(
    label: String,
    theme: WinTheme,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (theme.isDark) Color.White else Color(0xFF16324E),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
