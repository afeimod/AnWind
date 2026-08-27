package com.anwind.apps.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.anwind.AnWindApp
import com.anwind.core.desktop.IconPainter
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.theme.Themes
import com.anwind.core.theme.WindowsVariant
import com.anwind.core.window.AppDef
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val SettingsApp = AppDef(
    id = "settings",
    displayName = "设置",
    iconAsset = "icons/settings.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 880.dp,
    defaultHeight = 600.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    SettingsContent(scope)
}

/**
 * 设置中心 - Win11 风格重构
 *
 * 左侧：垂直导航栏（系统、蓝牙和设备、个性化、应用、账户、时间和语言、
 *        隐私和安全、Windows 更新、关于）
 * 右侧：内容区，垂直滚动，每项以卡片形式展示
 */
@Composable
private fun SettingsContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    var activeSection by remember {
        mutableStateOf(scope.windowState.launchArgs["section"] ?: "system")
    }

    Row(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 左侧导航栏（Win11 风格） =====
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(theme.cardBackgroundColor)
                .padding(12.dp)
        ) {
            // 顶部标题 + 应用图标
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(theme.accentColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "设置",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 搜索框
            OutlinedTextField(
                value = "",
                onValueChange = { },
                placeholder = { Text("查找设置", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(14.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(6.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
            )

            // 导航项列表
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsNavItem("系统", Icons.Default.Computer, active = activeSection == "system") { activeSection = "system" }
                SettingsNavItem("蓝牙和设备", Icons.Default.Bluetooth, active = activeSection == "bluetooth") { activeSection = "bluetooth" }
                SettingsNavItem("网络和 Internet", Icons.Default.Wifi, active = activeSection == "network") { activeSection = "network" }
                SettingsNavItem("个性化", Icons.Default.Palette, active = activeSection == "personalization") { activeSection = "personalization" }
                SettingsNavItem("应用", Icons.Default.Apps, active = activeSection == "apps") { activeSection = "apps" }
                SettingsNavItem("账户", Icons.Default.Person, active = activeSection == "accounts") { activeSection = "accounts" }
                SettingsNavItem("时间和语言", Icons.Default.Schedule, active = activeSection == "time") { activeSection = "time" }
                SettingsNavItem("游戏", Icons.Default.SportsEsports, active = activeSection == "gaming") { activeSection = "gaming" }
                SettingsNavItem("辅助功能", Icons.Default.Accessibility, active = activeSection == "accessibility") { activeSection = "accessibility" }
                SettingsNavItem("隐私和安全", Icons.Default.Security, active = activeSection == "privacy") { activeSection = "privacy" }
                SettingsNavItem("Windows 更新", Icons.Default.Update, active = activeSection == "update") { activeSection = "update" }
                SettingsNavItem("关于", Icons.Default.Info, active = activeSection == "about") { activeSection = "about" }
            }
        }

        // ===== 右侧内容区 =====
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (activeSection) {
                    "system" -> SystemSection()
                    "bluetooth" -> BluetoothSection()
                    "network" -> NetworkSection()
                    "personalization" -> PersonalizationSection()
                    "apps" -> AppsSection()
                    "accounts" -> AccountsSection()
                    "time" -> TimeLanguageSection()
                    "gaming" -> GamingSection()
                    "accessibility" -> AccessibilitySection()
                    "privacy" -> PrivacySection()
                    "update" -> WindowsUpdateSection()
                    "about" -> AboutSection()
                }
            }
        }
    }
}

/**
 * Win11 风格导航项：左侧图标 + 文字，选中项左侧有彩色指示条
 */
@Composable
private fun SettingsNavItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(if (active) theme.accentColor.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
    }
}

// === 内容区头部组件 ===

@Composable
private fun SectionHeader(title: String, description: String? = null) {
    val theme = LocalWinTheme.current
    Text(
        title,
        color = if (theme.isDark) Color.White else Color.Black,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
    )
    if (description != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            color = theme.secondaryTextColor,
            fontSize = 12.sp
        )
    }
    Spacer(Modifier.height(20.dp))
}

/**
 * Win11 风格设置卡片：行布局，左侧图标 + 标题/副标题，右侧控件
 */
@Composable
private fun SettingsCard(
    icon: ImageVector,
    iconBackgroundColor: Color,
    title: String,
    subtitle: String,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(iconBackgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

@Composable
private fun ToggleSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}

// === 各内容区域 ===

@Composable
private fun SystemSection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
    val showSeconds by app.settingsStore.showSeconds.collectAsState(initial = false)
    val taskbarAutohide by app.settingsStore.taskbarAutohide.collectAsState(initial = false)
    val uiScale by app.settingsStore.uiScale.collectAsState(initial = 1f)
    val orientation by app.settingsStore.displayOrientation.collectAsState(initial = "auto")
    val brightness by app.settingsStore.brightness.collectAsState(initial = 0.8f)
    val powerSaver by app.settingsStore.powerSaver.collectAsState(initial = false)
    val notificationsEnabled by app.settingsStore.notificationsEnabled.collectAsState(initial = true)
    val doNotDisturb by app.settingsStore.doNotDisturb.collectAsState(initial = false)

    SectionHeader("系统", "显示、声音、通知、电源")

    // 顶部系统信息卡片
    SettingsCard(
        icon = Icons.Default.Computer,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "AnWind 设备",
        subtitle = "Android 模拟 Windows · 处理器 8 核 · 内存 4 GB"
    )
    Spacer(Modifier.height(8.dp))

    // 显示设置卡片
    SettingsCard(
        icon = Icons.Default.BrightnessMedium,
        iconBackgroundColor = Color(0xFF0067C0),
        title = "显示",
        subtitle = "显示器、亮度、夜间模式、显示配置文件",
        onClick = { /* 可进入二级页面 */ }
    )
    Spacer(Modifier.height(8.dp))

    // 亮度滑块（直接展开）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("亮度", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessMedium, null, tint = theme.secondaryTextColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
                Slider(
                    value = brightness,
                    onValueChange = { scope0.launch { app.settingsStore.setBrightness(it) } },
                    valueRange = 0.2f..1.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("${(brightness * 100).roundToInt()}%", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 声音
    SettingsCard(
        icon = Icons.Default.VolumeUp,
        iconBackgroundColor = Color(0xFF00B294),
        title = "声音",
        subtitle = "音量级别、输出设备、输入设备"
    )
    Spacer(Modifier.height(8.dp))

    // 通知
    SettingsCard(
        icon = Icons.Default.Notifications,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "通知",
        subtitle = "来自应用和系统的通知提醒",
        trailingContent = {
            ToggleSwitch(notificationsEnabled) { v -> scope0.launch { app.settingsStore.setNotificationsEnabled(v) } }
        }
    )
    Spacer(Modifier.height(8.dp))

    // 免打扰
    SettingsCard(
        icon = Icons.Default.DoNotDisturbOn,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "专注助手",
        subtitle = "免打扰模式，隐藏所有通知",
        trailingContent = {
            ToggleSwitch(doNotDisturb) { v -> scope0.launch { app.settingsStore.setDoNotDisturb(v) } }
        }
    )
    Spacer(Modifier.height(8.dp))

    // 电源
    SettingsCard(
        icon = Icons.Default.PowerSettingsNew,
        iconBackgroundColor = Color(0xFFF7630C),
        title = "电源和电池",
        subtitle = if (powerSaver) "节能模式 · 电池使用时间延长" else "电池 · 节能模式",
        trailingContent = {
            ToggleSwitch(powerSaver) { v -> scope0.launch { app.settingsStore.setPowerSaver(v) } }
        }
    )
    Spacer(Modifier.height(8.dp))

    // 存储
    SettingsCard(
        icon = Icons.Default.Storage,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "存储",
        subtitle = "存储空间 · 驱动器 · 配置规则"
    )
    Spacer(Modifier.height(8.dp))

    // 多任务处理
    SettingsCard(
        icon = Icons.Default.Tab,
        iconBackgroundColor = Color(0xFF8E8CD8),
        title = "多任务处理",
        subtitle = "窗口布局 · 贴靠布局 · 虚拟桌面"
    )
    Spacer(Modifier.height(8.dp))

    // 桌面图标大小（嵌入卡片）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("桌面图标大小", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = iconSize,
                    onValueChange = { scope0.launch { app.settingsStore.setIconSize(it) } },
                    valueRange = 28f..72f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("${iconSize.roundToInt()}dp", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // UI 缩放
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("UI 缩放", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = uiScale,
                    onValueChange = { scope0.launch { app.settingsStore.setUiScale(it) } },
                    valueRange = 0.6f..1.8f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("${(uiScale * 100).roundToInt()}%", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("缩放整个桌面的图标、文字和窗口大小。", color = theme.secondaryTextColor, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(8.dp))

    // 显示方向
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("显示方向", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("自动", orientation == "auto") { scope0.launch { app.settingsStore.setDisplayOrientation("auto") } }
                SegmentedOption("竖屏", orientation == "portrait") { scope0.launch { app.settingsStore.setDisplayOrientation("portrait") } }
                SegmentedOption("横屏", orientation == "landscape") { scope0.launch { app.settingsStore.setDisplayOrientation("landscape") } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 时钟显示秒
    SettingsCard(
        icon = Icons.Default.Schedule,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "时钟显示秒",
        subtitle = "在任务栏时钟中显示秒数",
        trailingContent = { ToggleSwitch(showSeconds) { v -> scope0.launch { app.settingsStore.setShowSeconds(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    // 任务栏自动隐藏
    SettingsCard(
        icon = Icons.Default.ViewDay,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "任务栏自动隐藏",
        subtitle = "指针移到底部边缘时任务栏自动出现",
        trailingContent = { ToggleSwitch(taskbarAutohide) { v -> scope0.launch { app.settingsStore.setTaskbarAutohide(v) } } }
    )
}

@Composable
private fun BluetoothSection() {
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val bluetooth by app.settingsStore.bluetoothEnabled.collectAsState(initial = false)
    val mouseSpeed by app.settingsStore.mousePointerSpeed.collectAsState(initial = 1f)
    val keyboardVib by app.settingsStore.keyboardVibration.collectAsState(initial = true)
    val touchFeedback by app.settingsStore.touchFeedback.collectAsState(initial = false)

    SectionHeader("蓝牙和设备", "设备管理、鼠标、键盘、触摸")

    // 添加设备
    SettingsCard(
        icon = Icons.Default.Add,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "添加设备",
        subtitle = "搜索并配对附近的蓝牙设备"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Bluetooth,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "蓝牙",
        subtitle = if (bluetooth) "已开启 · 可被发现" else "关闭",
        trailingContent = { ToggleSwitch(bluetooth) { v -> scope0.launch { app.settingsStore.setBluetoothEnabled(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Mouse,
        iconBackgroundColor = Color(0xFF00B294),
        title = "鼠标",
        subtitle = "鼠标光标速度、点击操作"
    )
    Spacer(Modifier.height(8.dp))

    // 鼠标光标速度滑块
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("鼠标光标速度", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = mouseSpeed,
                    onValueChange = { scope0.launch { app.settingsStore.setMousePointerSpeed(it) } },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("${(mouseSpeed * 100).roundToInt()}%", color = theme.secondaryTextColor, fontSize = 11.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Keyboard,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "键盘",
        subtitle = "键盘振动、布局、输入法"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Vibration,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "键盘振动",
        subtitle = "按键时振动反馈",
        trailingContent = { ToggleSwitch(keyboardVib) { v -> scope0.launch { app.settingsStore.setKeyboardVibration(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.TouchApp,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "触摸反馈",
        subtitle = "触摸屏幕时振动",
        trailingContent = { ToggleSwitch(touchFeedback) { v -> scope0.launch { app.settingsStore.setTouchFeedback(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Print,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "打印机和扫描仪",
        subtitle = "添加、管理或连接打印机和扫描仪"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Cast,
        iconBackgroundColor = Color(0xFF8E8CD8),
        title = "无线显示器",
        subtitle = "将设备连接到无线显示器"
    )
}

@Composable
private fun NetworkSection() {
    SectionHeader("网络和 Internet", "Wi-Fi、飞行模式、VPN、数据使用量")

    SettingsCard(
        icon = Icons.Default.Wifi,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "Wi-Fi",
        subtitle = "已连接 · AnWind-Network"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.NetworkCell,
        iconBackgroundColor = Color(0xFF00B294),
        title = "以太网",
        subtitle = "已连接 · 互联网"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.VpnKey,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "VPN",
        subtitle = "添加或连接 VPN"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.MobileFriendly,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "移动热点",
        subtitle = "与其他设备共享 Internet 连接"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.AirplanemodeActive,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "飞行模式",
        subtitle = "停止所有无线通信"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.DataUsage,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "数据使用量",
        subtitle = "本月已使用 0 GB / 无限制"
    )
}

@Composable
private fun PersonalizationSection() {
    val theme = LocalWinTheme.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    var currentVariant by remember { mutableStateOf(theme.variant) }
    val customWallpaper by app.settingsStore.customWallpaper.collectAsState(initial = null)
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                app.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            scope0.launch { app.settingsStore.setCustomWallpaper(uri.toString()) }
        }
    }

    SectionHeader("个性化", "背景、颜色、主题、锁屏界面")

    // 主题选择
    Text("主题", color = if (theme.isDark) Color.White else Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Themes.all.forEach { themeOption ->
            ThemeCard(
                themeOption = themeOption,
                isSelected = currentVariant == themeOption.variant,
                onClick = {
                    currentVariant = themeOption.variant
                    scope0.launch { app.themeManager.setTheme(themeOption.variant) }
                }
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    // 背景
    Text("背景", color = if (theme.isDark) Color.White else Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    SettingsCard(
        icon = Icons.Default.Image,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "壁纸",
        subtitle = "当前：${customWallpaper ?: "主题默认"}",
        onClick = { pickWallpaper.launch(arrayOf("image/*")) }
    )
    Spacer(Modifier.height(8.dp))

    if (customWallpaper != null) {
        SettingsCard(
            icon = Icons.Default.Restore,
            iconBackgroundColor = Color(0xFFCA5010),
            title = "恢复默认壁纸",
            subtitle = "使用主题自带的默认壁纸",
            onClick = { scope0.launch { app.settingsStore.setCustomWallpaper(null) } }
        )
        Spacer(Modifier.height(8.dp))
    }

    // 颜色
    SettingsCard(
        icon = Icons.Default.Palette,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "颜色",
        subtitle = "强调色：${theme.accentColor.toArgb().ushr(16).toInt().toString(16)}"
    )
    Spacer(Modifier.height(8.dp))

    // 锁屏界面
    SettingsCard(
        icon = Icons.Default.Lock,
        iconBackgroundColor = Color(0xFF00B294),
        title = "锁屏界面",
        subtitle = "锁屏壁纸、状态显示"
    )
    Spacer(Modifier.height(8.dp))

    // 主题
    SettingsCard(
        icon = Icons.Default.Style,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "主题",
        subtitle = "当前主题：${theme.displayName}"
    )
    Spacer(Modifier.height(8.dp))

    // 字体
    SettingsCard(
        icon = Icons.Default.TextFields,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "字体",
        subtitle = "系统字体大小、字形"
    )
    Spacer(Modifier.height(8.dp))

    // 任务栏
    SettingsCard(
        icon = Icons.Default.ViewDay,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "任务栏",
        subtitle = "对齐：${if (theme.taskbarAlignment == com.anwind.core.theme.TaskbarAlignment.CENTER) "居中" else "左对齐"}"
    )
}

@Composable
private fun ThemeCard(themeOption: com.anwind.core.theme.WinTheme, isSelected: Boolean, onClick: () -> Unit) {
    val currentTheme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(
                if (isSelected) currentTheme.accentColor.copy(alpha = 0.1f) else currentTheme.cardBackgroundColor
            )
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) currentTheme.accentColor else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(themeOption.taskbarColor))
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(themeOption.windowTitleBarColor))
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)).background(themeOption.accentColor))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                themeOption.displayName,
                color = if (currentTheme.isDark) Color.White else Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                when (themeOption.variant) {
                    WindowsVariant.WIN95  -> "经典灰色 · 圆角窗口 · 居中任务栏"
                    WindowsVariant.WIN_XP -> "Luna 蓝色调 · 现代圆角"
                    WindowsVariant.WIN7   -> "Aero 玻璃 · 半透明任务栏"
                    WindowsVariant.WIN10  -> "扁平深色 · 居中任务栏"
                    WindowsVariant.WIN11  -> "Mica 材质 · 大圆角 · 居中任务栏"
                },
                color = currentTheme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
        if (isSelected) {
            Icon(Icons.Default.Check, "已选择", tint = currentTheme.accentColor, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AppsSection() {
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val defaultBrowser by app.settingsStore.defaultBrowser.collectAsState(initial = "browser")
    val uaMode by app.settingsStore.browserUaMode.collectAsState(initial = "desktop")
    val home by app.settingsStore.defaultBrowserHome.collectAsState(initial = "https://www.bing.com")
    var homeInput by remember { mutableStateOf(home) }

    SectionHeader("应用", "已安装的应用、默认应用、可选功能")

    // 已安装应用列表
    val allApps = remember { AppRegistry.all() }
    Text("已安装应用 (${allApps.size})", color = if (LocalWinTheme.current.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        allApps.forEach { appDef ->
            SettingsCard(
                icon = Icons.Default.Apps,
                iconBackgroundColor = Color(0xFF0078D7),
                title = appDef.displayName,
                subtitle = "ID: ${appDef.id} · ${appDef.launchMode.name}"
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    // 默认应用
    Text("默认应用", color = if (LocalWinTheme.current.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    SettingsCard(
        icon = Icons.Default.Public,
        iconBackgroundColor = Color(0xFF00B294),
        title = "默认浏览器",
        subtitle = "$defaultBrowser · 用于打开网页链接",
        onClick = { scope0.launch { app.settingsStore.setDefaultBrowser("browser") } }
    )
    Spacer(Modifier.height(8.dp))

    // 浏览器 UA 模式
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("浏览器 UA 模式", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("桌面模式", uaMode == "desktop") { scope0.launch { app.settingsStore.setBrowserUaMode("desktop") } }
                SegmentedOption("手机模式", uaMode == "mobile") { scope0.launch { app.settingsStore.setBrowserUaMode("mobile") } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // 浏览器主页
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("浏览器主页", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = homeInput,
                onValueChange = { homeInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(6.dp)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { scope0.launch { app.settingsStore.setDefaultBrowserHome(homeInput) } },
                shape = RoundedCornerShape(6.dp)
            ) { Text("保存") }
        }
    }
    Spacer(Modifier.height(16.dp))

    SettingsCard(
        icon = Icons.Default.Extension,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "可选功能",
        subtitle = "安装额外功能、字体、工具"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.AppShortcut,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "应用执行别名",
        subtitle = "管理应用的可执行别名"
    )
}

@Composable
private fun AccountsSection() {
    SectionHeader("账户", "账户信息、登录选项、邮箱、同步")

    // 用户卡片
    SettingsCard(
        icon = Icons.Default.AccountCircle,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "AnWind 用户",
        subtitle = "本地账户 · 管理员权限"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Login,
        iconBackgroundColor = Color(0xFF00B294),
        title = "登录选项",
        subtitle = "PIN、密码、生物识别"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Email,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "电子邮件和账户",
        subtitle = "Microsoft 账户、邮箱账户"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Sync,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "同步你的设置",
        subtitle = "在多台设备间同步个性化设置"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.FamilyRestroom,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "家庭和家庭安全",
        subtitle = "管理家庭成员、儿童安全设置"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Schedule,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "Windows 备份",
        subtitle = "备份账户和设置到云"
    )
}

@Composable
private fun TimeLanguageSection() {
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val timeFormat24h by app.settingsStore.timeFormat24h.collectAsState(initial = true)
    val language by app.settingsStore.language.collectAsState(initial = "zh-CN")

    SectionHeader("时间和语言", "日期和时间、语言区域、输入")

    SettingsCard(
        icon = Icons.Default.Schedule,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "日期和时间",
        subtitle = "时区、自动设置时间、自动设置夏令时"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Schedule,
        iconBackgroundColor = Color(0xFF00B294),
        title = "24 小时制",
        subtitle = "使用 24 小时制时间显示",
        trailingContent = { ToggleSwitch(timeFormat24h) { v -> scope0.launch { app.settingsStore.setTimeFormat24h(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Language,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "语言和区域",
        subtitle = "当前语言：${if (language == "zh-CN") "中文（简体）" else "English (US)"}"
    )
    Spacer(Modifier.height(8.dp))

    // 语言切换
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("显示语言", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("中文", language == "zh-CN") { scope0.launch { app.settingsStore.setLanguage("zh-CN") } }
                SegmentedOption("English", language == "en-US") { scope0.launch { app.settingsStore.setLanguage("en-US") } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Keyboard,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "输入",
        subtitle = "键盘、字典、自动更正"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.RecordVoiceOver,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "语音",
        subtitle = "语音输入、文本到语音"
    )
}

@Composable
private fun GamingSection() {
    SectionHeader("游戏", "Xbox Game Bar、游戏模式、屏幕捕获")

    SettingsCard(
        icon = Icons.Default.SportsEsports,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "Xbox Game Bar",
        subtitle = "在游戏中打开覆盖层、性能监控"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.VideogameAsset,
        iconBackgroundColor = Color(0xFF00B294),
        title = "游戏模式",
        subtitle = "优化系统性能以获得更好的游戏体验",
        trailingContent = { ToggleSwitch(true) { } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.PhotoCamera,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "屏幕捕获",
        subtitle = "录制屏幕截图、视频"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.GraphicEq,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "游戏音频",
        subtitle = "空间音效、麦克风监控"
    )
}

@Composable
private fun AccessibilitySection() {
    SectionHeader("辅助功能", "视觉、听觉、交互辅助")

    SettingsCard(
        icon = Icons.Default.Visibility,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "视觉",
        subtitle = "文本大小、对比度、滤镜"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Hearing,
        iconBackgroundColor = Color(0xFF00B294),
        title = "听觉",
        subtitle = "字幕、单声道音频"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.AccessibilityNew,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "交互",
        subtitle = "语音、键盘、鼠标"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Psychology,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "认知",
        subtitle = "减少动画、关注指示器"
    )
}

@Composable
private fun PrivacySection() {
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val location by app.settingsStore.locationEnabled.collectAsState(initial = false)
    val camera by app.settingsStore.cameraAccess.collectAsState(initial = true)
    val mic by app.settingsStore.microphoneAccess.collectAsState(initial = true)
    val diagnostics by app.settingsStore.diagnosticsOptIn.collectAsState(initial = false)

    SectionHeader("隐私和安全", "位置、相机、麦克风、诊断")

    SettingsCard(
        icon = Icons.Default.Security,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "Windows 安全中心",
        subtitle = "病毒防护、防火墙、账户保护"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.LocationSearching,
        iconBackgroundColor = Color(0xFF00B294),
        title = "查找我的设备",
        subtitle = "定位、远程锁定或清除设备",
        trailingContent = { ToggleSwitch(location) { v -> scope0.launch { app.settingsStore.setLocationEnabled(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.LocationOn,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "位置",
        subtitle = if (location) "已开启 · 应用可访问位置信息" else "关闭",
        trailingContent = { ToggleSwitch(location) { v -> scope0.launch { app.settingsStore.setLocationEnabled(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Camera,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "相机",
        subtitle = "允许应用访问相机",
        trailingContent = { ToggleSwitch(camera) { v -> scope0.launch { app.settingsStore.setCameraAccess(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Mic,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "麦克风",
        subtitle = "允许应用访问麦克风",
        trailingContent = { ToggleSwitch(mic) { v -> scope0.launch { app.settingsStore.setMicrophoneAccess(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Notifications,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "通知",
        subtitle = "应用通知、推送、横幅"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.History,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "活动历史记录",
        subtitle = "记录设备使用历史"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.BugReport,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "诊断和反馈",
        subtitle = "发送诊断数据、获取反馈",
        trailingContent = { ToggleSwitch(diagnostics) { v -> scope0.launch { app.settingsStore.setDiagnosticsOptIn(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.ForwardToInbox,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "反馈中心",
        subtitle = "提交反馈、报告问题"
    )
}

@Composable
private fun WindowsUpdateSection() {
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val autoUpdate by app.settingsStore.autoUpdate.collectAsState(initial = true)
    val channel by app.settingsStore.updateChannel.collectAsState(initial = "stable")

    SectionHeader("Windows 更新", "获取最新更新、安全补丁、新功能")

    // 状态卡片
    SettingsCard(
        icon = Icons.Default.CheckCircle,
        iconBackgroundColor = Color(0xFF00B294),
        title = "你使用的是最新版本",
        subtitle = "上次检查时间：刚刚"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Refresh,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "检查更新",
        subtitle = "立即检查 AnWind 是否有新版本",
        onClick = { /* 触发检查 */ }
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Update,
        iconBackgroundColor = Color(0xFF8764B8),
        title = "自动更新",
        subtitle = "在 Wi-Fi 时自动下载并安装更新",
        trailingContent = { ToggleSwitch(autoUpdate) { v -> scope0.launch { app.settingsStore.setAutoUpdate(v) } } }
    )
    Spacer(Modifier.height(8.dp))

    // 更新通道
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp)
    ) {
        Column {
            Text("更新通道", color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentedOption("稳定版", channel == "stable") { scope0.launch { app.settingsStore.setUpdateChannel("stable") } }
                SegmentedOption("测试版", channel == "beta") { scope0.launch { app.settingsStore.setUpdateChannel("beta") } }
                SegmentedOption("开发版", channel == "dev") { scope0.launch { app.settingsStore.setUpdateChannel("dev") } }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.History,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "更新历史记录",
        subtitle = "查看已安装的更新列表"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Pause,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "暂停更新",
        subtitle = "暂停 1 周 · 1 个月 · 35 天"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Tune,
        iconBackgroundColor = Color(0xFF00B7C3),
        title = "高级选项",
        subtitle = "传递优化、活动时间、可选更新"
    )
}

@Composable
private fun AboutSection() {
    val theme = LocalWinTheme.current
    SectionHeader("关于", "AnWind 系统信息")

    SettingsCard(
        icon = Icons.Default.Info,
        iconBackgroundColor = Color(0xFF0078D7),
        title = "AnWind",
        subtitle = "Android 上模拟 Windows 操作系统的桌面环境"
    )
    Spacer(Modifier.height(8.dp))

    AboutRow("设备名称", "AnWind 设备")
    AboutRow("处理器", "Snapdragon 8 Gen X · 8 核")
    AboutRow("已安装的 RAM", "4.00 GB")
    AboutRow("设备 ID", "ANWIND-${System.currentTimeMillis().toString().takeLast(8)}")
    AboutRow("产品 ID", "00ANWIND-00000-00000-00000")
    AboutRow("系统类型", "ARM64 Android")
    AboutRow("应用名称", "AnWind")
    AboutRow("版本", "1.0.0")
    AboutRow("包名", "com.anwind")
    AboutRow("当前主题", theme.displayName)
    AboutRow("minSdk", "24 (Android 7.0)")
    AboutRow("targetSdk", "34 (Android 14)")
    AboutRow("项目", "AnWind - Android Windows Simulator")
    AboutRow("License", "MIT")
    Spacer(Modifier.height(16.dp))

    // 重置 / 备份
    SettingsCard(
        icon = Icons.Default.RestorePage,
        iconBackgroundColor = Color(0xFFCA5010),
        title = "重置应用",
        subtitle = "清除所有数据，恢复初始状态"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Backup,
        iconBackgroundColor = Color(0xFF00B294),
        title = "备份数据",
        subtitle = "导出应用设置到本地"
    )
    Spacer(Modifier.height(8.dp))

    SettingsCard(
        icon = Icons.Default.Code,
        iconBackgroundColor = Color(0xFF6B69D6),
        title = "查看源码",
        subtitle = "GitHub: AnWind 项目仓库"
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(theme.cardBackgroundColor)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (theme.isDark) Color.White else Color.Black, fontSize = 12.sp)
        Text(value, color = theme.secondaryTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * Win11 风格的分段选项按钮
 */
@Composable
private fun SegmentedOption(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) theme.accentColor.copy(alpha = 0.15f) else theme.windowBackgroundColor
            )
            .border(
                width = if (selected) 1.dp else 1.dp,
                color = if (selected) theme.accentColor else theme.dividerColor,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (selected) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
        )
    }
}
