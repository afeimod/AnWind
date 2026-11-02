package com.anwind.apps.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.WinConfirmDialog
import com.anwind.core.window.WinDialog
import com.anwind.core.window.WindowContentScope
import com.anwind.AnWindApp
import com.anwind.apps.containers.launcher.RootfsLauncher
import com.anwind.apps.containers.launcher.WineEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AnWind Wine 容器 — 管理界面（Compose）。
 *
 * 功能：
 *  - 容器列表（默认容器标 ★，运行中标 ●）
 *  - 新建/编辑/克隆/删除/设为默认
 *  - 运行 exe / wineboot 初始化 / winecfg / regedit / 停止
 *  - 体检（X11 / rootfs / wine / box64 / FEX / 容器）
 *  - Wine 输出日志查看
 *
 * 显示方案：X11。所有"运行"动作都会先确保 AnWind 内置 X11 桌面窗口
 * 就位（X server = termux-x11/libXlorie，socket $PREFIX/tmp/.X11-unix/Xn），
 * 容器内程序经该 socket 直连，无 Wayland/Weston 参与。
 */

@Composable
private fun themeBg() = LocalWinTheme.current.windowBackgroundColor
@Composable
private fun themeCard() = LocalWinTheme.current.cardBackgroundColor
@Composable
private fun themeAccent() = LocalWinTheme.current.accentColor
@Composable
private fun themeSec() = LocalWinTheme.current.secondaryTextColor

// ---------------------------------------------------------------------------
// 主内容
// ---------------------------------------------------------------------------

@Composable
fun ContainerListContent(scope: WindowContentScope) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var tick by remember { mutableIntStateOf(0) }                 // 刷新触发器
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    var rootfsReady by remember { mutableStateOf(true) }

    // 弹窗路由（同屏最多一个）
    var showCreate by remember { mutableStateOf(false) }
    var showDoctor by remember { mutableStateOf(false) }
    var showRunFor by remember { mutableStateOf<ContainerData?>(null) }
    var showEditFor by remember { mutableStateOf<ContainerData?>(null) }
    var showCloneFor by remember { mutableStateOf<ContainerData?>(null) }
    var showLogFor by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ContainerData?>(null) }

    // v3.1.6 前缀构建日志：构建进行中自动弹出；也可从容器卡片手动打开
    var prefixLogFor by remember { mutableStateOf<String?>(null) }
    val prefixBuilding by RootfsLauncher.prefixBuildFlow.collectAsState()
    LaunchedEffect(prefixBuilding) {
        prefixBuilding?.let { prefixLogFor = it }
    }

    // 列表加载（tick 驱动刷新）
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) {
            containers = ContainerManager.list()
            rootfsReady = ContainerManager.rootfsReady()
            // v2.26 rev40：进入容器界面即补压 DXVK 资产——用户首次
            // wineboot 构建前缀后无需重启 App，回到本界面即自动把
            // 内置 dxvk-*.tzst 解压进新前缀的 drive_c/windows
            // （system32/syswow64 布局直解；未构建前缀自动跳过）。
            com.anwind.apps.terminal.termux.AnWindTzstAssets
                .applyDxvkWhenPrefixReady(context)
        }
    }

    fun refresh() { tick++ }

    // v3.2.15（wineguanli.py 重制）：当前选中容器（宽屏右侧面板的数据源）
    var selectedName by remember { mutableStateOf<String?>(null) }

    // v3.2.15（wineguanli.py 重制）：左右分栏布局 —— 宽屏（≥700dp）左侧
    // 容器列表 + 右侧配置选项卡（wineguanli.py 同款信息架构：基本/CPU/
    // 函数库/图形/环境变量）；窄屏回落为原卡片列表（长按菜单操作齐备）。
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(themeBg())
    ) {
        if (maxWidth >= 700.dp) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                // ---------------- 左：容器列表 ----------------
                Column(
                    Modifier
                        .width(288.dp)
                        .fillMaxHeight()
                ) {
                    ContainerTitleRow(
                        count = containers.size,
                        rootfsReady = rootfsReady,
                        onDoctor = { showDoctor = true },
                        onCreate = { showCreate = true }
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!rootfsReady) {
                        RootfsMissingHint()
                        Spacer(Modifier.height(8.dp))
                    }
                    LeftContainerList(
                        containers = containers,
                        selectedName = selectedName ?: containers.firstOrNull()?.name,
                        running = { name -> WineSessionLauncher.activeSessions.containsKey(name) },
                        isDefault = { name -> ContainerManager.isDefault(name) },
                        onSelect = { selectedName = it }
                    )
                }
                Spacer(Modifier.width(12.dp))
                // ---------------- 右：配置选项卡 ----------------
                val sel = containers.firstOrNull { it.name == selectedName }
                    ?: containers.firstOrNull()
                if (sel != null) {
                    ContainerConfigPanel(
                        container = sel,
                        modifier = Modifier.weight(1f),
                        onSaved = { refresh() },
                        onRun = { showRunFor = sel },
                        onStop = {
                            cs.launch(Dispatchers.IO) {
                                WineSessionLauncher.stopContainer(context, sel.name)
                                withContext(Dispatchers.Main) { refresh() }
                            }
                        },
                        onWineBoot = {
                            cs.launch(Dispatchers.IO) {
                                val err = WineSessionLauncher.initializePrefix(context, sel)
                                withContext(Dispatchers.Main) {
                                    err?.let {
                                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    refresh()
                                }
                            }
                        },
                        onClone = { showCloneFor = sel },
                        onRemove = { confirmRemove = sel },
                        onShowLog = { showLogFor = sel.name },
                        onPrefixLog = { prefixLogFor = sel.name },
                        onWineCfg = {
                            cs.launch(Dispatchers.IO) {
                                val err = WineSessionLauncher.openWineCfg(context, sel)
                                withContext(Dispatchers.Main) {
                                    err?.let {
                                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    refresh()
                                }
                            }
                        },
                        onRegEdit = {
                            cs.launch(Dispatchers.IO) {
                                val err = WineSessionLauncher.openRegEdit(context, sel)
                                withContext(Dispatchers.Main) {
                                    err?.let {
                                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    refresh()
                                }
                            }
                        },
                        onSetDefault = {
                            cs.launch(Dispatchers.IO) {
                                ContainerManager.setDefault(sel.name)
                                withContext(Dispatchers.Main) { refresh() }
                            }
                        }
                    )
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("🍷", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("暂无容器", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("点击左上角\"新建容器\"创建第一个 Wine 容器", fontSize = 12.sp, color = themeSec())
                    }
                }
            }
        } else {
            // ---------------- 窄屏：原卡片列表 ----------------
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                ContainerTitleRow(
                    count = containers.size,
                    rootfsReady = rootfsReady,
                    onDoctor = { showDoctor = true },
                    onCreate = { showCreate = true }
                )
                Spacer(Modifier.height(8.dp))
                if (!rootfsReady) {
                    RootfsMissingHint()
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(containers, key = { it.name }) { c ->
                        ContainerCard(
                            data = c,
                            running = WineSessionLauncher.activeSessions.containsKey(c.name),
                            isDefault = ContainerManager.isDefault(c.name),
                            onRun = { showRunFor = c },
                            onStop = {
                                cs.launch(Dispatchers.IO) {
                                    WineSessionLauncher.stopContainer(context, c.name)
                                    withContext(Dispatchers.Main) { refresh() }
                                }
                            },
                            onEdit = { showEditFor = c },
                            onClone = { showCloneFor = c },
                            onRemove = { confirmRemove = c },
                            onSetDefault = {
                                cs.launch(Dispatchers.IO) {
                                    ContainerManager.setDefault(c.name)
                                    withContext(Dispatchers.Main) { refresh() }
                                }
                            },
                            onWineBoot = {
                                cs.launch(Dispatchers.IO) {
                                    val err = WineSessionLauncher.initializePrefix(context, c)
                                    withContext(Dispatchers.Main) {
                                        err?.let {
                                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        refresh()
                                    }
                                }
                            },
                            onPrefixLog = { prefixLogFor = c.name },
                            onWineCfg = {
                                cs.launch(Dispatchers.IO) {
                                    val err = WineSessionLauncher.openWineCfg(context, c)
                                    withContext(Dispatchers.Main) {
                                        err?.let {
                                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        refresh()
                                    }
                                }
                            },
                            onRegEdit = {
                                cs.launch(Dispatchers.IO) {
                                    val err = WineSessionLauncher.openRegEdit(context, c)
                                    withContext(Dispatchers.Main) {
                                        err?.let {
                                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                                        }
                                        refresh()
                                    }
                                }
                            },
                            onShowLog = { showLogFor = c.name }
                        )
                    }
                    if (containers.isEmpty() && rootfsReady) {
                        item {
                            Text(
                                "暂无容器。点击右上角\"新建容器\"创建第一个 Wine 容器。",
                                fontSize = 13.sp, color = themeSec(),
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- 弹窗（v3.1.7：全部为 Windows 风格可移动小窗口） ----------------
    if (showCreate) {
        CreateContainerDialog(
            onDismiss = { showCreate = false },
            onCreated = { showCreate = false; refresh() }
        )
    }
    showRunFor?.let { c ->
        RunContainerDialog(
            container = c,
            onDismiss = { showRunFor = null },
            onLaunched = { showRunFor = null; refresh() }
        )
    }
    showEditFor?.let { c ->
        EditContainerDialog(
            container = c,
            onDismiss = { showEditFor = null },
            onSaved = { showEditFor = null; refresh() }
        )
    }
    showCloneFor?.let { c ->
        CloneContainerDialog(
            source = c,
            onDismiss = { showCloneFor = null },
            onCloned = { showCloneFor = null; refresh() }
        )
    }
    confirmRemove?.let { c ->
        WinConfirmDialog(
            title = "删除容器",
            message = "确定删除容器 \"${c.name}\"？其 WINEPREFIX 与已安装程序将一并删除，不可恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                confirmRemove = null
                cs.launch(Dispatchers.IO) {
                    ContainerManager.remove(c.name)
                    withContext(Dispatchers.Main) { refresh() }
                }
            },
            onDismiss = { confirmRemove = null }
        )
    }
    if (showDoctor) {
        DoctorDialog(onDismiss = { showDoctor = false })
    }
    showLogFor?.let { key ->
        LogDialog(
            title = "Wine 输出 · $key",
            log = WineSessionLauncher.lastLogs[key]?.toString() ?: "（暂无输出）",
            onDismiss = { showLogFor = null }
        )
    }
    prefixLogFor?.let { name ->
        PrefixBuildLogDialog(
            containerName = name,
            running = prefixBuilding == name,
            onDismiss = { prefixLogFor = null }
        )
    }
}

// ---------------------------------------------------------------------------
// 单个容器卡片
// ---------------------------------------------------------------------------

@Composable
private fun ContainerCard(
    data: ContainerData,
    running: Boolean,
    isDefault: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onClone: () -> Unit,
    onRemove: () -> Unit,
    onSetDefault: () -> Unit,
    onWineBoot: () -> Unit,
    onWineCfg: () -> Unit,
    onRegEdit: () -> Unit,
    onShowLog: () -> Unit,
    onPrefixLog: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    val theme = LocalWinTheme.current

    // v3.2.13（用户问题 1）：卡片重做 —— 图标 + 名称行 / 信息徽章网格 /
    // 主操作行（日志、前缀日志等低频入口收进 ⋯ 菜单，按钮行不再拥挤）。
    Card(
        colors = CardDefaults.cardColors(containerColor = themeCard()),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (running) theme.accentColor.copy(alpha = 0.55f) else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(10.dp)
            )
    ) {
        Column(Modifier.padding(12.dp)) {
            // 名称行：图标 + 名称（★ 默认 / ● 运行中）+ 设为默认
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🍷", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (if (isDefault) "★ " else "") + data.name,
                            fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            color = LocalWinTheme.current.windowTitleBarTextColor
                        )
                        if (running) {
                            Spacer(Modifier.width(6.dp))
                            Text("● 运行中", fontSize = 11.sp, color = themeAccent())
                        }
                    }
                    Text(
                        "创建于 " + data.created.ifEmpty { "—" },
                        fontSize = 10.sp, color = themeSec(),
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isDefault) "默认容器" else "设为默认",
                    fontSize = 10.sp,
                    color = if (isDefault) theme.accentColor else theme.secondaryTextColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isDefault) theme.accentColor.copy(alpha = 0.14f)
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSetDefault
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // 信息徽章网格（2 行 3 列）：后端 / 显示 / 渲染 / wine / 语言·音频 / DX
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                WinInfoBadge("后端", data.backend.label)
                WinInfoBadge(
                    "显示",
                    data.dmode.label + if (data.dmode != DisplayMode.OFF && data.screen != "native") " ${data.screen}" else ""
                )
                WinInfoBadge("渲染", GalliumDriver.fromId(data.gallium).label)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                WinInfoBadge("wine", data.wine.ifEmpty { "默认" })
                WinInfoBadge("音频", if (data.audio == ContainerAudio.OFF) "静音" else "PulseAudio")
                WinInfoBadge(
                    "图形 API",
                    buildString {
                        if (data.dxvk != "off") append("DXVK ${data.dxvk}")
                        if (data.vkd3d != "off") {
                            if (isNotEmpty()) append(" · ")
                            append("VKD3D ${data.vkd3d}")
                        }
                        if (isEmpty()) append("wine 内建")
                    }
                )
            }

            Spacer(Modifier.height(10.dp))

            // 操作行：高频动作保留按钮，低频动作收进 ⋯ 菜单
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onRun,
                    colors = ButtonDefaults.buttonColors(containerColor = themeAccent()),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp)
                ) { Text("运行 exe", fontSize = 12.sp) }

                OutlinedButton(
                    onClick = onWineCfg,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) { Text("winecfg", fontSize = 12.sp) }

                OutlinedButton(
                    onClick = onShowLog,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                ) { Text("日志", fontSize = 12.sp) }

                if (running) {
                    OutlinedButton(
                        onClick = onStop,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    ) { Text("停止", fontSize = 12.sp) }
                }

                Box {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)
                    ) { Text("⋯", fontSize = 12.sp) }

                    if (menuOpen) {
                        WinCompactMenu(onDismiss = { menuOpen = false }) { close ->
                            WinCompactMenuItem("初始化前缀 (wineboot -i)") { close(); onWineBoot() }
                            WinCompactMenuItem("注册表编辑器 (regedit)") { close(); onRegEdit() }
                            WinCompactMenuItem("前缀日志") { close(); onPrefixLog() }
                            WinCompactMenuDivider()
                            WinCompactMenuItem("编辑配置") { close(); onEdit() }
                            WinCompactMenuItem("克隆容器") { close(); onClone() }
                            WinCompactMenuDivider()
                            WinCompactMenuItem("删除容器", isDanger = true) { close(); onRemove() }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// v3.2.0：紧凑 Win 风格弹出菜单（用户诉求 1 —— 三点菜单太大）
// ---------------------------------------------------------------------------

/**
 * 紧凑弹出菜单容器：Popup 锚定在包裹按钮的右上、点外关闭、focusable
 * （捕获返回键/点击外部统一 dismiss）。视觉与桌面右键菜单同语言：
 * 主题底色 + 细描边 + 阴影 + 3dp 内边距 + 4dp 圆角。
 */
@Composable
private fun WinCompactMenu(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val theme = LocalWinTheme.current
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
        alignment = Alignment.TopEnd
    ) {
        Column(
            modifier = Modifier
                .width(216.dp)
                .shadow(6.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(theme.windowBackgroundColor)
                .border(0.5.dp, theme.windowBorderColor, RoundedCornerShape(4.dp))
                .padding(3.dp)
        ) {
            content(onDismiss)
        }
    }
}

/** 紧凑菜单项：行高 30dp / 11sp，按压高亮（无涟漪，同桌面右键菜单）*/
@Composable
private fun WinCompactMenuItem(
    label: String,
    isDanger: Boolean = false,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fg = when {
        isDanger -> MaterialTheme.colorScheme.error
        theme.isDark -> Color.White
        else -> Color.Black
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (pressed) theme.accentColor.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = fg, maxLines = 1)
    }
}

/** 紧凑菜单分隔线 */
@Composable
private fun WinCompactMenuDivider() {
    val theme = LocalWinTheme.current
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        thickness = 0.5.dp,
        color = theme.windowBorderColor.copy(alpha = 0.6f)
    )
}

// ---------------------------------------------------------------------------
// v3.2.13 · Win 风格分区表单组件（用户问题 1：容器创建/编辑窗口 UI 重做）
// ---------------------------------------------------------------------------
// 旧 UI：全部选项是横向 FilterChip 长条（单行横滚）+ 一根到底的纵向滚动，
// 标签挤在一起、关联选项不相邻、无视觉分组 —— "太丑了"。
// 新 UI：Windows 属性表风格 ——
//   WinSectionCard  分区卡片（左侧强调条 + 标题 + 圆角卡片内容区）
//   WinOptionPills  选项药丸组（FlowRow 自动换行，替代 FilterChip 横滚行）
//   WinInfoBadge    轻量信息徽章（后端/版本等信息陈列）
// ---------------------------------------------------------------------------

/** 分区卡片：左侧强调条 + 标题（+可选副标题）+ 卡片化内容区。 */
@Composable
private fun WinSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalWinTheme.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 14.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(theme.accentColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = LocalWinTheme.current.windowTitleBarTextColor)
            Spacer(Modifier.weight(1f))
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = theme.secondaryTextColor)
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(theme.cardBackgroundColor)
                .border(0.5.dp, theme.windowBorderColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

/** 字段行：标签在上（含可选说明），控件在下。 */
@Composable
private fun WinField(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit
) {
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LocalWinTheme.current.windowTitleBarTextColor)
        if (hint != null) {
            Text(hint, fontSize = 10.sp, color = themeSec())
        }
        Spacer(Modifier.height(5.dp))
        content()
    }
}

/**
 * 选项药丸组（FlowRow 自动换行，不再横向滚动）。
 *
 * @param options (值, 展示名) 列表
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WinOptionPills(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        when {
                            pressed && sel -> theme.accentColor.copy(alpha = 0.85f)
                            sel -> theme.accentColor
                            pressed -> theme.accentColor.copy(alpha = 0.18f)
                            else -> theme.windowBackgroundColor
                        }
                    )
                    .border(
                        0.5.dp,
                        if (sel) theme.accentColor else theme.windowBorderColor.copy(alpha = 0.7f),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null
                    ) { onSelect(value) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    // 选中态文字固定白（强调色底），未选中跟随主题前景
                    color = if (sel) Color.White
                    else if (theme.isDark) Color.White else Color.Black,
                    maxLines = 1
                )
            }
        }
    }
}

/** 信息徽章：后端/版本等键值陈列（容器卡片与运行选择器共用）。 */
@Composable
private fun WinInfoBadge(label: String, value: String) {
    val theme = LocalWinTheme.current
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(theme.windowBackgroundColor.copy(alpha = if (theme.isDark) 0.35f else 0.6f))
            .border(0.5.dp, theme.windowBorderColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .fillMaxWidth()
    ) {
        Text(label, fontSize = 9.sp, color = theme.secondaryTextColor)
        Spacer(Modifier.height(1.dp))
        Text(
            value,
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (theme.isDark) Color.White else Color.Black,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// v3.2.15（wineguanli.py 重制）：标题行 / rootfs 提示 / 左侧列表 / 配置面板
// ---------------------------------------------------------------------------

/** 标题行（图标 + 计数徽章 + 体检/新建；宽窄屏共用）。 */
@Composable
private fun ContainerTitleRow(
    count: Int,
    rootfsReady: Boolean,
    onDoctor: () -> Unit,
    onCreate: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("🍷", fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            "Wine 容器",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = LocalWinTheme.current.windowTitleBarTextColor
        )
        Spacer(Modifier.width(8.dp))
        if (count > 0) {
            Text(
                "$count 个容器 · 运行中 ${WineSessionLauncher.activeSessions.size}",
                fontSize = 11.sp,
                color = themeSec(),
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(themeCard())
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDoctor) { Text("体检") }
        Button(
            onClick = onCreate,
            enabled = rootfsReady,
            colors = ButtonDefaults.buttonColors(containerColor = themeAccent())
        ) { Text("+ 新建") }
    }
}

/** rootfs 缺失提示（宽窄屏共用）。 */
@Composable
private fun RootfsMissingHint() {
    Card(
        colors = CardDefaults.cardColors(containerColor = themeCard()),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("未检测到 bionic rootfs", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            Text(
                "未检测到 bionic rootfs。若本 APK 内置了 rootfs 资产（assets/anwind/rootfs-*.tzst），" +
                    "启动时会自动解压到 /data/data/com.anwind/files/rootfs（请稍候重进）；" +
                    "也可在终端用 anwind-tarxz 导入 build-bionic-rootfs.yml 构建的 rootfs 包（内含 anwind-container CLI）。",
                fontSize = 12.sp, color = themeSec()
            )
        }
    }
}

/**
 * 左侧容器列表（wineguanli.py 同款条目：📦 名称 [分辨率] 🍷版本，运行中 ●、默认 ★）。
 */
@Composable
private fun LeftContainerList(
    containers: List<ContainerData>,
    selectedName: String?,
    running: (String) -> Boolean,
    isDefault: (String) -> Boolean,
    onSelect: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    LazyColumn(
        Modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(containers, key = { it.name }) { c ->
            val sel = c.name == selectedName
            val isRun = running(c.name)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (sel) theme.accentColor.copy(alpha = 0.18f)
                        else theme.cardBackgroundColor
                    )
                    .border(
                        0.5.dp,
                        if (sel) theme.accentColor else theme.windowBorderColor.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable { onSelect(c.name) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📦 ${c.name}${if (isDefault(c.name)) " ★" else ""}",
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        color = LocalWinTheme.current.windowTitleBarTextColor,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    if (isRun) {
                        Text("●", color = Color(0xFF4CAF50), fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append("[")
                        append(if (c.screen == "native") "跟随窗口" else c.screen)
                        append("]")
                        if (c.wine.isNotBlank()) append(" 🍷${c.wine.removePrefix("wine-")}")
                        append(" ${WindowsVersions.fromId(c.winver).label}")
                    },
                    fontSize = 10.sp,
                    color = themeSec(),
                    maxLines = 1
                )
            }
        }
        if (containers.isEmpty()) {
            item {
                Text(
                    "暂无容器。点击上方\"新建\"创建第一个 Wine 容器。",
                    fontSize = 12.sp, color = themeSec(),
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            }
        }
    }
}

/** wineguanli.py 高级启动开关的展示名。 */
private val FLAG_LABELS: Map<String, String> = mapOf(
    ContainerFlags.UNITY to "Unity 单线程渲染",
    ContainerFlags.OPENGL to "OpenGL 驱动",
    ContainerFlags.F5TASK to "F5 任务管理器",
    ContainerFlags.IB to "启动 IB 输入",
    ContainerFlags.DEBUG to "强制 DX11 启动",
    ContainerFlags.INDEPENDENT to "独立窗口模式",
    ContainerFlags.CLOSESERVICE to "关闭 service 服务"
)

/**
 * 右侧配置面板（wineguanli.py 信息架构：操作工具栏 + 5 选项卡）。
 * 编辑工作副本，保存时整体落盘（ContainerManager.update）。
 */
@Composable
private fun ContainerConfigPanel(
    container: ContainerData,
    // v3.2.16 修复：Modifier.weight 是 RowScope/ColumnScope 的成员扩展，
    // 组件函数体内不可直接调用（否则解析到 foundation 内部的
    // RowColumnParentData.weight 属性，报 invoke 错误）。改由调用方
    // （宽屏 Row 内）通过本参数传入 Modifier.weight(1f)。
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onWineBoot: () -> Unit,
    onClone: () -> Unit,
    onRemove: () -> Unit,
    onShowLog: () -> Unit,
    onPrefixLog: () -> Unit,
    onWineCfg: () -> Unit,
    onRegEdit: () -> Unit,
    onSetDefault: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    val theme = LocalWinTheme.current

    // 工作副本（切换容器时随 key 重建；保存后由外部 refresh 传回新对象）
    var edit by remember(container.name) { mutableStateOf(container) }
    var tab by remember(container.name) { mutableStateOf(0) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    var saveErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) { wines = ContainerManager.listWines() }
    }

    val dirty = edit != container
    val running = WineSessionLauncher.activeSessions.containsKey(container.name)

    Column(
        modifier
            .fillMaxHeight()
    ) {
        // ---------------- 面板头：容器名 + 状态 + 保存 ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "📦 ${container.name}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = LocalWinTheme.current.windowTitleBarTextColor
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                Text(
                    "● 运行中",
                    fontSize = 10.sp, color = Color(0xFF4CAF50),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(themeCard())
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (ContainerManager.isDefault(container.name)) {
                Text(" ★默认", fontSize = 10.sp, color = themeSec())
            }
            Spacer(Modifier.weight(1f))
            if (dirty) {
                Text("未保存更改", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
            }
            Button(
                onClick = {
                    cs.launch(Dispatchers.IO) {
                        val err = ContainerManager.update(edit)
                        withContext(Dispatchers.Main) {
                            if (err == null) {
                                saveErr = null
                                android.widget.Toast.makeText(context, "配置已保存", android.widget.Toast.LENGTH_SHORT).show()
                                onSaved()
                            } else {
                                saveErr = err
                            }
                        }
                    }
                },
                enabled = dirty,
                colors = ButtonDefaults.buttonColors(containerColor = themeAccent())
            ) { Text("保存") }
        }
        if (saveErr != null) {
            Text(saveErr ?: "", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }

        // ---------------- 操作工具栏（wineguanli.py 工具栏同位） ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PanelAction("▶ 运行", primary = true, onClick = onRun)
            if (running) PanelAction("■ 停止", onClick = onStop)
            PanelAction("⟳ wineboot", onClick = onWineBoot)
            PanelAction("🧰 winecfg", onClick = onWineCfg)
            PanelAction("📝 regedit", onClick = onRegEdit)
            PanelAction("📋 会话日志", onClick = onShowLog)
            PanelAction("🧱 前缀日志", onClick = onPrefixLog)
            PanelAction("⧉ 克隆", onClick = onClone)
            PanelAction("★ 设为默认", onClick = onSetDefault)
            PanelAction("🗑 删除", danger = true, onClick = onRemove)
        }

        Spacer(Modifier.height(8.dp))

        // ---------------- 选项卡 ----------------
        TabRow(
            selectedTabIndex = tab,
            containerColor = themeCard(),
            contentColor = LocalWinTheme.current.windowTitleBarTextColor
        ) {
            listOf("基本信息", "CPU 核心", "函数库", "图形与 Wine", "高级").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = { Text(label, fontSize = 12.sp, maxLines = 1) }
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (tab) {
                // ================= 0 基本信息 =================
                0 -> {
                    WinSectionCard("基本配置") {
                        WineSlotSelector(wines, edit.wine) { edit = edit.copy(wine = it) }
                        WinField("虚拟分辨率", "非\"跟随窗口\"时自动切固定分辨率") {
                            WinOptionPills(
                                options = ContainerData.SCREEN_CHOICES.map { it to resolutionLabel(it) },
                                selected = edit.screen,
                                onSelect = { v ->
                                    edit = edit.copy(screen = v)
                                    if (v != "native" && edit.dmode == DisplayMode.OFF) {
                                        edit = edit.copy(dmode = DisplayMode.D)
                                    }
                                }
                            )
                        }
                        WinField("容器语言", "与终端会话语言隔离") {
                            WinOptionPills(
                                options = ContainerLangs.CHOICES.zip(ContainerLangs.DISPLAY),
                                selected = edit.lang,
                                onSelect = { edit = edit.copy(lang = it) }
                            )
                        }
                        WinField("帧数限制", "DXVK_FRAME_RATE（wineguanli.py 同款）") {
                            WinOptionPills(
                                options = ContainerData.FRAME_CHOICES.map {
                                    it to if (it == "0") "默认(不限)" else it
                                },
                                selected = edit.frame,
                                onSelect = { edit = edit.copy(frame = it) }
                            )
                        }
                    }
                    WinSectionCard("高级启动选项", subtitle = "wineguanli.py 勾选组") {
                        WinCheckChipFlow(
                            items = ContainerFlags.ALL.map { it to (FLAG_LABELS[it] ?: it) },
                            checked = { it in edit.flagSet },
                            onToggle = { flag, on ->
                                val s = edit.flagSet.toMutableSet()
                                if (on) s.add(flag) else s.remove(flag)
                                edit = edit.copy(flags = ContainerFlags.serialize(s))
                            }
                        )
                        Text(
                            "Unity 单线程渲染 → BOX64_UNITYPLAYER=1；其余开关写入 container.conf（flags=），" +
                                "供自带 /opt 生态脚本消费",
                            fontSize = 10.sp, color = themeSec()
                        )
                    }
                }
                // ================= 1 CPU 核心 =================
                1 -> {
                    val total = Runtime.getRuntime().availableProcessors()
                    val primary = WineEnvironment.parseCoreSpec(edit.cores)
                        .filter { it in 0 until total }.toSortedSet()
                    val secondary = (0 until total).filter { it !in primary }
                    WinSectionCard("CPU 核心分配（勾选为主核心）") {
                        WinCheckChipFlow(
                            items = (0 until total).map { it.toString() to "核心$it" },
                            checked = { it.toIntOrNull() in primary },
                            onToggle = { name, on ->
                                val idx = name.toIntOrNull() ?: return@WinCheckChipFlow
                                val s = primary.toMutableSet()
                                if (on) s.add(idx) else s.remove(idx)
                                edit = edit.copy(cores = WineEnvironment.formatCoreSpec(s.sorted()))
                            }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "主核心: ${WineEnvironment.formatCoreSpec(primary.toList()).ifEmpty { "无" }}　" +
                                "副核心: ${WineEnvironment.formatCoreSpec(secondary).ifEmpty { "无" }}",
                            fontSize = 11.sp,
                            color = LocalWinTheme.current.windowTitleBarTextColor,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(themeBg())
                                .padding(8.dp)
                        )
                        Text(
                            "勾选的核心分配给 Wine/Windows 应用；未勾选留给系统与后台。写入 container.conf " +
                                "（cores=）并以 PRIMARY_CORES/SECONDARY_CORES 环境变量注入启动链（wineguanli.py " +
                                "/opt/conf/cores.conf 同款语义，供 /opt 生态脚本与调度器消费）。",
                            fontSize = 10.sp, color = themeSec()
                        )
                    }
                }
                // ================= 2 函数库 =================
                2 -> {
                    WinSectionCard("函数库设置", subtitle = "写入容器前缀注册表") {
                        WinField("DirectSound (dsound)", "音频播放 DLL 加载序（user.reg）") {
                            WinOptionPills(
                                // v3.2.16 修复：WinOptionPills.options 要求 List<Pair<value,label>>
                                options = listOf(
                                    "builtin,native" to "内置优先 (builtin,native)",
                                    "native,builtin" to "原生优先 (native,builtin)"
                                ),
                                selected = edit.dsound,
                                onSelect = { edit = edit.copy(dsound = it) }
                            )
                        }
                        WinField("DirectShow (quartz)", "多媒体播放 DLL 加载序（user.reg）") {
                            WinOptionPills(
                                options = listOf(
                                    "builtin,native" to "内置优先 (builtin,native)",
                                    "native,builtin" to "原生优先 (native,builtin)"
                                ),
                                selected = edit.dshow,
                                onSelect = { edit = edit.copy(dshow = it) }
                            )
                        }
                        val dpi = edit.dpi.toIntOrNull() ?: 96
                        WinField("DPI 屏幕缩放", "LogPixels（user.reg）") {
                            Text(
                                "${dpi} (${dpi * 100 / 96}%)",
                                fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                color = themeAccent()
                            )
                            Slider(
                                value = dpi.toFloat(),
                                onValueChange = {
                                    edit = edit.copy(dpi = it.toInt().coerceIn(60, 240).toString())
                                },
                                valueRange = 60f..240f,
                                steps = 8
                            )
                            WinOptionPills(
                                options = ContainerData.DPI_CHOICES.map { it.toString() to "${it} (${it * 100 / 96}%)" },
                                selected = edit.dpi,
                                onSelect = { edit = edit.copy(dpi = it) }
                            )
                        }
                        WinField("Windows 版本", "CurrentVersion/ProductName/CurrentBuild（system.reg）") {
                            WinOptionPills(
                                options = WindowsVersions.ALL.map { it.id to it.label },
                                selected = edit.winver,
                                onSelect = { edit = edit.copy(winver = it) }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    cs.launch(Dispatchers.IO) {
                                        val target = edit
                                        val err = com.anwind.apps.containers.launcher.WineLibrary.applyNow(target)
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(
                                                context,
                                                err ?: "函数库设置已应用到注册表",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28A745))
                            ) { Text("💾 立即应用设置") }
                            Spacer(Modifier.width(8.dp))
                            Text("先保存再应用；未构建前缀时由启动链自动补", fontSize = 10.sp, color = themeSec())
                        }
                    }
                }
                // ================= 3 图形与 Wine =================
                3 -> {
                    WinSectionCard("运行环境", subtitle = "CPU 翻译后端") {
                        WinField("CPU 翻译后端", edit.backend.desc) {
                            WinOptionPills(
                                options = ContainerBackend.entries.map { it.id to it.label },
                                selected = edit.backend.id,
                                onSelect = { edit = edit.copy(backend = ContainerBackend.fromId(it)) }
                            )
                        }
                        if (edit.backend == ContainerBackend.BOX64) {
                            WinField("Box64 动态重编译预设", edit.preset.desc) {
                                WinOptionPills(
                                    options = Box64Preset.entries.map { it.id to it.label },
                                    selected = edit.preset.id,
                                    onSelect = { edit = edit.copy(preset = Box64Preset.fromId(it)) }
                                )
                            }
                        }
                        WinField("显示模式", edit.dmode.desc) {
                            WinOptionPills(
                                options = DisplayMode.entries.map { it.id to it.label },
                                selected = edit.dmode.id,
                                onSelect = { edit = edit.copy(dmode = DisplayMode.fromId(it)) }
                            )
                        }
                        WinField("Mesa 渲染器", "Adreno 设备推荐 Freedreno 或 Zink") {
                            WinOptionPills(
                                options = GalliumDriver.entries.map { it.id to it.label },
                                selected = edit.gallium,
                                onSelect = { edit = edit.copy(gallium = it) }
                            )
                        }
                        WinField("DXVK 图形加速", "前缀构建时自动解压 APK 内置资产到 C:\\windows") {
                            WinOptionPills(
                                options = listOf(
                                    ContainerData.DXVK_AUTO to "开启",
                                    ContainerData.DXVK_OFF to "关闭"
                                ),
                                selected = if (edit.dxvk == ContainerData.DXVK_OFF) ContainerData.DXVK_OFF else ContainerData.DXVK_AUTO,
                                onSelect = { edit = edit.copy(dxvk = it) }
                            )
                        }
                        WinField("音频", edit.audio.desc) {
                            WinOptionPills(
                                options = ContainerAudio.entries.map { it.id to it.label },
                                selected = edit.audio.id,
                                onSelect = { edit = edit.copy(audio = ContainerAudio.fromId(it)) }
                            )
                        }
                    }
                    WinSectionCard("驱动版本槽位", subtitle = "对接 /opt 生态（tar 包名）") {
                        OutlinedTextField(
                            value = edit.turnip,
                            onValueChange = { edit = edit.copy(turnip = it) },
                            label = { Text("Turnip 驱动（/opt/tz 包名，可空）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = edit.zink,
                            onValueChange = { edit = edit.copy(zink = it) },
                            label = { Text("Zink 驱动（/opt/zink 包名，可空）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = edit.wined3d,
                            onValueChange = { edit = edit.copy(wined3d = it) },
                            label = { Text("WineD3D（/opt/vkord3d 包名，可空）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "wineguanli.py 同款槽位：填 /opt 下 tar 包名（如 dxvk-2.3.1），由你自带的解压脚本" +
                                "消费（AnWind 原生链的 DXVK 注入走 APK assets/dxvk，与本槽位互不冲突）。",
                            fontSize = 10.sp, color = themeSec()
                        )
                    }
                }
                // ================= 4 高级 =================
                4 -> {
                    WinSectionCard("环境变量") {
                        OutlinedTextField(
                            value = edit.env,
                            onValueChange = { edit = edit.copy(env = it) },
                            label = { Text("附加环境变量（K=V 空格分隔，可覆盖一切）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = edit.mangohud,
                            onValueChange = { edit = edit.copy(mangohud = it) },
                            label = { Text("MangoHud 配置（fps,cpu_stats,gpu_stats...；空=关）") },
                            placeholder = { Text("fps,frametime,cpu_stats,gpu_stats,font_size=24") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "非空时注入 MANGOHUD=1 + MANGOHUD_CONFIG（rootfs 内置 MangoHud 时生效）；" +
                                "DXVK 帧率限制用基本信息页的\"帧数限制\"",
                            fontSize = 10.sp, color = themeSec()
                        )
                    }
                    WinSectionCard("容器信息") {
                        WinInfoBadge("架构/后端", edit.backend.label)
                        WinInfoBadge("Windows 版本", WindowsVersions.fromId(edit.winver).label)
                        WinInfoBadge("DPI", "${edit.dpi} (${(edit.dpi.toIntOrNull() ?: 96) * 100 / 96}%)")
                        WinInfoBadge("创建时间", edit.created.ifEmpty { "未知" })
                        WinInfoBadge("前缀路径", edit.prefixPath)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 面板操作按钮（工具栏紧凑款）。 */
@Composable
private fun PanelAction(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val theme = LocalWinTheme.current
    Text(
        label,
        fontSize = 11.sp,
        color = when {
            primary -> Color.White
            danger -> MaterialTheme.colorScheme.error
            else -> LocalWinTheme.current.windowTitleBarTextColor
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    primary -> theme.accentColor
                    else -> theme.cardBackgroundColor
                }
            )
            .border(0.5.dp, theme.windowBorderColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** 可勾选药丸流（核心选择 / 启动开关）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WinCheckChipFlow(
    items: List<Pair<String, String>>,
    checked: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    val theme = LocalWinTheme.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (value, label) ->
            val on = checked(value)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) theme.accentColor.copy(alpha = 0.2f) else theme.cardBackgroundColor)
                    .border(
                        0.5.dp,
                        if (on) theme.accentColor else theme.windowBorderColor.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp)
                    )
                    .clickable { onToggle(value, !on) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (on) "✓ " else "",
                    fontSize = 11.sp,
                    color = theme.accentColor
                )
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (on) theme.accentColor else LocalWinTheme.current.windowTitleBarTextColor
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 新建容器（v3.2.13 UI 重做；v3.2.15 补帧率限制/语言）
// ---------------------------------------------------------------------------

@Composable
private fun CreateContainerDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf(ContainerBackend.AUTO) }
    var preset by remember { mutableStateOf(Box64Preset.PERFORMANCE) }
    var screen by remember { mutableStateOf(ContainerData.DEFAULT_SCREEN) }
    var dmode by remember { mutableStateOf(DisplayMode.OFF) }
    var gallium by remember { mutableStateOf(ContainerData.DEFAULT_GALLIUM) }
    var dxvk by remember { mutableStateOf(ContainerData.DXVK_AUTO) }
    var wine by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(ContainerLangs.DEFAULT) }
    var audio by remember { mutableStateOf(ContainerAudio.PULSE) }
    var env by remember { mutableStateOf("") }
    // v3.2.15（wineguanli.py 重制）：新建对话框补帧率限制
    var frame by remember { mutableStateOf(ContainerData.FRAME_DEFAULT) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    var dxvkAssetDesc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // wine 版本列表（rootfs /usr/opt 扫描；无 wine 时选择器只剩"默认"）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            wines = ContainerManager.listWines()
            // v3.2.14：检测 APK 内置 DXVK 资产（assets/dxvk/dxvk-<ver>.tar.gz）
            val ctx = AnWindApp.get()
            val assets = com.anwind.apps.terminal.termux.AnWindTzstAssets.scanDxvkAssets(ctx)
            dxvkAssetDesc = if (assets.isEmpty()) "未检测到内置 DXVK（assets/dxvk/ 为空）"
            else "内置：${assets.joinToString(", ") { it.name }}"
        }
    }

    WinDialog(
        title = "新建 Wine 容器",
        onDismiss = onDismiss,
        width = 640.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 基本信息 ----
            WinSectionCard("基本信息") {
                WinField(
                    "容器名",
                    "字母/数字开头，作为前缀目录名"
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        isError = error != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }

            // ---- 运行环境 ----
            WinSectionCard("运行环境", subtitle = "CPU 翻译后端") {
                WinField("CPU 翻译后端", backend.desc) {
                    WinOptionPills(
                        options = ContainerBackend.entries.map { it.id to it.label },
                        selected = backend.id,
                        onSelect = { backend = ContainerBackend.fromId(it) }
                    )
                }
                if (backend == ContainerBackend.BOX64) {
                    WinField("Box64 动态重编译预设", preset.desc) {
                        WinOptionPills(
                            options = Box64Preset.entries.map { it.id to it.label },
                            selected = preset.id,
                            onSelect = { preset = Box64Preset.fromId(it) }
                        )
                    }
                }
            }

            // ---- 显示与分辨率 ----
            WinSectionCard("显示与分辨率") {
                WinField("显示模式", dmode.desc) {
                    WinOptionPills(
                        options = DisplayMode.entries.map { it.id to it.label },
                        selected = dmode.id,
                        onSelect = { dmode = DisplayMode.fromId(it) }
                    )
                }
                WinField(
                    "虚拟分辨率",
                    "选非\"跟随窗口\"时若显示模式仍为跟随，自动切换为固定分辨率"
                ) {
                    WinOptionPills(
                        options = ContainerData.SCREEN_CHOICES.map { it to resolutionLabel(it) },
                        selected = screen,
                        onSelect = { v ->
                            screen = v
                            // v3.2.1（用户问题 3）：分辨率与显示模式联动 —— 选非 native
                            // 分辨率时若仍为"跟随窗口"，握手永远写 native，分辨率毫无
                            // 效果；自动切到固定分辨率。
                            if (v != "native" && dmode == DisplayMode.OFF) {
                                dmode = DisplayMode.D
                            }
                        }
                    )
                }
            }

            // ---- 图形与 Wine ----
            WinSectionCard("图形与 Wine") {
                WineSlotSelector(wines, wine) { wine = it }
                WinField("Mesa 渲染器", "Adreno 设备推荐 Freedreno 或 Zink") {
                    WinOptionPills(
                        options = GalliumDriver.entries.map { it.id to it.label },
                        selected = gallium,
                        onSelect = { gallium = it }
                    )
                }
                // v3.2.14（用户问题 3）：DXVK 组件开关 —— 前缀构建时自动把
                // APK 内置 dxvk-<ver>.tar.gz 解压进 C:\windows
                WinField("DXVK 图形加速", dxvkAssetDesc.ifEmpty { "前缀构建时自动解压到 C:\\windows（system32/syswow64）" }) {
                    WinOptionPills(
                        options = listOf(
                            ContainerData.DXVK_AUTO to "开启",
                            ContainerData.DXVK_OFF to "关闭"
                        ),
                        selected = dxvk,
                        onSelect = { dxvk = it }
                    )
                }
            }

            // ---- 语言与音频 ----
            WinSectionCard("语言与音频") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        WinField("容器语言", "与终端隔离") {
                            WinOptionPills(
                                options = ContainerLangs.CHOICES.zip(ContainerLangs.DISPLAY),
                                selected = lang,
                                onSelect = { lang = it }
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        // v3.2.15（wineguanli.py 重制）：新建即选帧率限制
                        WinField("帧数限制", "DXVK_FRAME_RATE") {
                            WinOptionPills(
                                options = ContainerData.FRAME_CHOICES.map {
                                    it to if (it == "0") "默认(不限)" else it
                                },
                                selected = frame,
                                onSelect = { frame = it }
                            )
                        }
                    }
                }
                WinField("音频", audio.desc) {
                    WinOptionPills(
                        options = ContainerAudio.entries.map { it.id to it.label },
                        selected = audio.id,
                        onSelect = { audio = ContainerAudio.fromId(it) }
                    )
                }
            }

            // ---- 高级 ----
            WinSectionCard("高级", subtitle = "可选") {
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    label = { Text("附加环境变量（K=V 空格分隔，可空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "显示：X11（AnWind 内置 X 服务，启动容器自动拉起，DISPLAY=:1）；" +
                        "环境/音频/语言均由容器自持，不受终端影响",
                    fontSize = 11.sp, color = themeSec()
                )
            }
        }
        // 底部按钮（右对齐，与原 AlertDialog confirm/dismiss 等价）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val err = ContainerManager.create(
                    ContainerData(
                        name = name.trim(), backend = backend,
                        preset = preset, screen = screen, dmode = dmode,
                        gallium = gallium, wine = wine, dxvk = dxvk,
                        lang = lang, audio = audio, env = env.trim(),
                        frame = frame
                    )
                )
                if (err == null) onCreated() else error = err
            }) { Text("创建") }
        }
    }
}

/** 分辨率展示名：native → 跟随窗口，其余原样。 */
private fun resolutionLabel(v: String): String =
    if (v == "native") "跟随窗口" else v

// ---------------------------------------------------------------------------
// 编辑容器
// ---------------------------------------------------------------------------

@Composable
private fun EditContainerDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var backend by remember { mutableStateOf(container.backend) }
    var preset by remember { mutableStateOf(container.preset) }
    var screen by remember { mutableStateOf(container.screen) }
    var dmode by remember { mutableStateOf(container.dmode) }
    var gallium by remember { mutableStateOf(container.gallium) }
    // v3.2.14：DXVK 开关（版本戳归一为开启态；保存时 off → off，其余 → auto，
    // 实际版本戳由注入器回写，不在此处丢失）
    var dxvk by remember {
        mutableStateOf(if (container.dxvk == ContainerData.DXVK_OFF) ContainerData.DXVK_OFF else ContainerData.DXVK_AUTO)
    }
    var env by remember { mutableStateOf(container.env) }
    var wine by remember { mutableStateOf(container.wine) }
    var lang by remember { mutableStateOf(container.lang) }
    var audio by remember { mutableStateOf(container.audio) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    var dxvkAssetDesc by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // wine 版本列表（rootfs /usr/opt 扫描）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            wines = ContainerManager.listWines()
            // v3.2.14：检测 APK 内置 DXVK 资产
            val ctx = AnWindApp.get()
            val assets = com.anwind.apps.terminal.termux.AnWindTzstAssets.scanDxvkAssets(ctx)
            dxvkAssetDesc = if (assets.isEmpty()) "未检测到内置 DXVK（assets/dxvk/ 为空）"
            else "内置：${assets.joinToString(", ") { it.name }}"
        }
    }

    WinDialog(
        title = "编辑容器 · ${container.name}",
        onDismiss = onDismiss,
        width = 640.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 运行环境 ----
            WinSectionCard("运行环境", subtitle = "CPU 翻译后端") {
                WinField("CPU 翻译后端", backend.desc) {
                    WinOptionPills(
                        options = ContainerBackend.entries.map { it.id to it.label },
                        selected = backend.id,
                        onSelect = { backend = ContainerBackend.fromId(it) }
                    )
                }
                if (backend == ContainerBackend.BOX64) {
                    WinField("Box64 动态重编译预设", preset.desc) {
                        WinOptionPills(
                            options = Box64Preset.entries.map { it.id to it.label },
                            selected = preset.id,
                            onSelect = { preset = Box64Preset.fromId(it) }
                        )
                    }
                }
            }

            // ---- 显示与分辨率 ----
            WinSectionCard("显示与分辨率") {
                WinField("显示模式", dmode.desc) {
                    WinOptionPills(
                        options = DisplayMode.entries.map { it.id to it.label },
                        selected = dmode.id,
                        onSelect = { dmode = DisplayMode.fromId(it) }
                    )
                }
                WinField(
                    "虚拟分辨率",
                    "选非\"跟随窗口\"时若显示模式仍为跟随，自动切换为固定分辨率"
                ) {
                    WinOptionPills(
                        options = ContainerData.SCREEN_CHOICES.map { it to resolutionLabel(it) },
                        selected = screen,
                        onSelect = { v ->
                            screen = v
                            // v3.2.1（用户问题 3）：分辨率与显示模式联动（同新建容器）
                            if (v != "native" && dmode == DisplayMode.OFF) {
                                dmode = DisplayMode.D
                            }
                        }
                    )
                }
            }

            // ---- 图形与 Wine ----
            WinSectionCard("图形与 Wine") {
                WineSlotSelector(wines, wine) { wine = it }
                WinField("Mesa 渲染器", "Adreno 设备推荐 Freedreno 或 Zink") {
                    WinOptionPills(
                        options = GalliumDriver.entries.map { it.id to it.label },
                        selected = gallium,
                        onSelect = { gallium = it }
                    )
                }
                // v3.2.14（用户问题 3）：DXVK 组件开关（编辑可随时切换；
                // 关闭后再开启，下次启动时重新解压资产）
                WinField("DXVK 图形加速", dxvkAssetDesc.ifEmpty { "前缀构建时自动解压到 C:\\windows（system32/syswow64）" }) {
                    WinOptionPills(
                        options = listOf(
                            ContainerData.DXVK_AUTO to "开启",
                            ContainerData.DXVK_OFF to "关闭"
                        ),
                        selected = dxvk,
                        onSelect = { dxvk = it }
                    )
                }
            }

            // ---- 语言与音频 ----
            WinSectionCard("语言与音频") {
                WinField("容器语言", "与终端隔离") {
                    WinOptionPills(
                        options = ContainerLangs.CHOICES.zip(ContainerLangs.DISPLAY),
                        selected = lang,
                        onSelect = { lang = it }
                    )
                }
                WinField("音频", audio.desc) {
                    WinOptionPills(
                        options = ContainerAudio.entries.map { it.id to it.label },
                        selected = audio.id,
                        onSelect = { audio = ContainerAudio.fromId(it) }
                    )
                }
            }

            // ---- 高级 ----
            WinSectionCard("高级", subtitle = "可选") {
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    label = { Text("附加环境变量（K=V 空格分隔）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "wine 新版本：本窗下方分类在线安装，或终端 anwind-container install-wine <类别|URL>（wine-catalog 查看构建目录）",
                    fontSize = 11.sp, color = themeSec()
                )
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val err = ContainerManager.update(
                    container.copy(
                        backend = backend, preset = preset,
                        screen = screen, dmode = dmode, gallium = gallium,
                        // v3.2.14：DXVK 开关保留原版本戳语义 —— 原值非 off
                        // 且新值开启时维持原戳（注入器幂等不重解压）；关闭
                        // 时写 off；重新开启时 auto（指纹变化会重新解压）
                        dxvk = when {
                            dxvk == ContainerData.DXVK_OFF -> ContainerData.DXVK_OFF
                            container.dxvk == ContainerData.DXVK_OFF -> ContainerData.DXVK_AUTO
                            else -> container.dxvk   // 保持原版本戳（已注入）
                        },
                        env = env.trim(), wine = wine, lang = lang, audio = audio
                    )
                )
                if (err == null) onSaved() else error = err
            }) { Text("保存") }
        }
    }
}

// ---------------------------------------------------------------------------
// 运行 exe（v3.2.13：新增文件管理器选择 exe/bat —— 用户问题 2）
// ---------------------------------------------------------------------------

@Composable
private fun RunContainerDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onLaunched: () -> Unit
) {
    val context = LocalContext.current
    var exe by remember { mutableStateOf("C:\\windows\\notepad.exe") }
    var args by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    // v3.2.13（用户问题 2）：文件选择回填 —— 拉起文件资源管理器
    // （pickMode=exe，直达 Download），用户点选 exe/bat/msi/cmd/com 后
    // 经 FilePickBus 把【完整路径】投递回本窗口的"程序路径"输入框。
    // 发起窗口可能被容器管理器自身遮挡，选择器以独立浮窗打开（与
    // 记事本/媒体播放器同一选择总线，不重复造轮子）。
    DisposableEffect(container.name) {
        val unregister = com.anwind.apps.filemanager.FilePickBus.listen(
            targetApp = "containers_run_${container.name}",
            targetWindow = ""
        ) { path ->
            exe = path
            picking = false
        }
        onDispose { unregister() }
    }

    WinDialog(
        title = "运行 · ${container.name}",
        onDismiss = onDismiss,
        width = 560.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                WinField(
                    "程序路径",
                    "Windows 路径（C:\\…）或容器内绝对路径（/storage/…）"
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = exe, onValueChange = { exe = it; msg = null },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                picking = true
                                com.anwind.core.window.WindowManager.get().open(
                                    appId = "file_explorer",
                                    title = "选择程序",
                                    launchMode = com.anwind.core.window.LaunchMode.FLOATING,
                                    launchArgs = mapOf(
                                        "pickMode" to "exe",
                                        "targetApp" to "containers_run_${container.name}",
                                        "targetWindow" to ""
                                    )
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text(if (picking) "选择中…" else "浏览…", fontSize = 12.sp) }
                    }
                }
                WinField("启动参数", "传给程序的命令行参数（可空）") {
                    OutlinedTextField(
                        value = args, onValueChange = { args = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "点击\"运行\"将自动打开 X11 桌面窗口并经 box64/FEXCore 启动 Wine。",
                    fontSize = 11.sp, color = themeSec()
                )
                if (msg != null) {
                    Text(msg ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val err = WineSessionLauncher.runExe(context, container, exe.trim(), args.trim())
                if (err == null) onLaunched() else msg = err
            }) { Text("运行") }
        }
    }
}

// ---------------------------------------------------------------------------
// 克隆容器
// ---------------------------------------------------------------------------

@Composable
private fun CloneContainerDialog(
    source: ContainerData,
    onDismiss: () -> Unit,
    onCloned: () -> Unit
) {
    var name by remember { mutableStateOf("${source.name}-copy") }
    var error by remember { mutableStateOf<String?>(null) }

    WinDialog(
        title = "克隆容器 · ${source.name}",
        onDismiss = onDismiss,
        width = 440.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("新容器名") },
                    isError = error != null, singleLine = true
                )
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val err = ContainerManager.clone(source.name, name.trim())
                if (err == null) onCloned() else error = err
            }) { Text("克隆") }
        }
    }
}

// ---------------------------------------------------------------------------
// 体检
// ---------------------------------------------------------------------------

@Composable
private fun DoctorDialog(onDismiss: () -> Unit) {
    // v3.2.13：体检含 box64 运行探活（真实 exec，最长 5 秒）—— 从主线程
    // remember 改为 IO 线程加载 + 加载态，避免探活期间卡死弹窗（ANR）。
    var checks by remember { mutableStateOf<List<DoctorCheck>?>(null) }
    LaunchedEffect(Unit) {
        checks = withContext(Dispatchers.IO) { ContainerManager.doctor() }
    }
    val loaded = checks

    WinDialog(
        title = "AnWind Wine 容器体检",
        onDismiss = onDismiss,
        width = 560.dp
    ) {
        if (loaded == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = themeAccent()
                )
                Spacer(Modifier.height(10.dp))
                Text("正在体检（box64 运行探活最长 5 秒）…", fontSize = 12.sp, color = themeSec())
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(loaded.size) { i ->
                    val c = loaded[i]
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (c.ok) "✓" else "✗",
                            color = if (c.ok) themeAccent() else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(c.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(c.detail, fontSize = 11.sp, color = themeSec())
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("好的") }
        }
    }
}

// ---------------------------------------------------------------------------
// 日志
// ---------------------------------------------------------------------------

@Composable
private fun LogDialog(title: String, log: String, onDismiss: () -> Unit) {
    WinDialog(
        title = title,
        onDismiss = onDismiss,
        width = 620.dp
    ) {
        // v3.1.7：Windows 小窗口 + 等宽字体对齐日志列（弹窗层钳制高度，
        // 超出自动滚动）
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                log.takeLast(20000),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = themeSec(),
                lineHeight = 15.sp
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    }
}

// ---------------------------------------------------------------------------
// v3.1.6 前缀构建日志（仅限 wineboot -i 构建前缀阶段；用户诉求）
// ---------------------------------------------------------------------------

/**
 * 前缀构建日志窗口：实时滚动显示 wineboot -i 的输出。
 *
 * 打开时机：①容器首次运行/手动初始化触发前缀构建时自动弹出
 * （RootfsLauncher.prefixBuildFlow 驱动）；②容器卡片「前缀日志」按钮
 * 手动查看最近一次构建日志。构建结束（含超时强杀）后标题切换为
 * 「已结束」，内容停止刷新但保留全文。
 */
@Composable
private fun PrefixBuildLogDialog(
    containerName: String,
    running: Boolean,
    onDismiss: () -> Unit
) {
    var text by remember(containerName) { mutableStateOf(RootfsLauncher.prefixLogOf(containerName)) }
    LaunchedEffect(containerName) {
        while (true) {
            text = RootfsLauncher.prefixLogOf(containerName)
            delay(400)
        }
    }
    WinDialog(
        title = "构建 Wine 前缀 · $containerName" + if (running) "（进行中…）" else "（已结束）",
        onDismiss = onDismiss,
        width = 620.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text.ifEmpty { "（暂无输出——wineboot 启动后此处逐行显示；若长时间空白说明 wine 挂在启动早期）" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = themeSec(),
                lineHeight = 15.sp
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    }
}

// ---------------------------------------------------------------------------
// 通用选择器（v3.2.13：旧 FilterChip 横滚组由 WinOptionPills 取代；
// 仅保留 Wine 槽位选择器 —— 含在线安装入口，重做为 WinField 风格）
// ---------------------------------------------------------------------------

/** Wine 版本选择器（多版本：默认/主槽位/wine-<名> 槽位）+ 在线安装。 */
@Composable
private fun WineSlotSelector(
    wines: List<WineInstall>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    WinField(
        "Wine 版本",
        if (wines.isEmpty()) "未装多版本（/usr/opt 槽位），可点下方分类在线安装"
        else "已装 ${wines.size} 个槽位，点选切换"
    ) {
        WinOptionPills(
            options = buildList {
                add("" to "默认")
                wines.forEach { add(it.name to it.displayLabel) }
            },
            selected = selected,
            onSelect = onSelect
        )
        // v2.27：在线构建目录一键安装（wine-catalog：bionic 双形态 /
        // 普通 Wine / Proton；CLI 自动取最新 Release 资产并命名槽位，
        // 下载进度在"Wine 输出"面板查看，装完重开本窗即可选择）
        Spacer(Modifier.height(2.dp))
        Text(
            "在线安装（自动取最新构建，进度见\"Wine 输出\"面板）:",
            fontSize = 10.sp, color = themeSec()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScrollIfNeeded()
        ) {
            listOf(
                "bionic-arm64ec" to "arm64ec 原生",
                "bionic-x86_64" to "x86_64 (box64)",
                "wine-arm64" to "普通Wine ARM64",
                "wine-x86_64" to "普通Wine x86_64",
                "proton" to "Proton"
            ).forEach { (key, label) ->
                OutlinedButton(
                    onClick = { WineSessionLauncher.installWine(context, key) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) { Text(label, fontSize = 10.sp) }
            }
        }
    }
}

/** 弹窗内横向可滚动的选项行（选项过多时不挤爆窗口宽度）。 */
@Composable
private fun Modifier.horizontalScrollIfNeeded(): Modifier =
    this.horizontalScroll(rememberScrollState())
