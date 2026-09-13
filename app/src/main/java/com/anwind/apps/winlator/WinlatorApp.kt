package com.anwind.apps.winlator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.apps.filemanager.FilePickBus
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowManager
import com.anwind.core.window.WindowContentScope
import com.winlator.cmod.container.Container
import com.winlator.cmod.session.WinlatorSession

/**
 * v2.23：Winlator 容器（开始菜单/桌面图标入口）。
 *
 * 桌面容器创建（Winlator Cmod 7.1.4x 引擎数据模型，全套设置）+ 运行 exe，
 * 游戏画面显示在 AnWind 自带的 X11 浮动窗口（termux-x11/lorie + LorieView）。
 * 会话由 [WinlatorSession]（:winlator 模块）编排，本页只负责 UI。
 */
val WinlatorApp = AppDef(
    id = "winlator",
    displayName = "Winlator 容器",
    iconAsset = "icons/winlator.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 720.dp,
    defaultHeight = 520.dp,
    pinnedToDesktop = true,
    onTitleBarLongPress = { _ -> }
) { scope ->
    WinlatorHomeScreen(scope)
}

@Composable
fun WinlatorHomeScreen(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val wm = remember { WindowManager.get() }

    LaunchedEffect(Unit) { WinlatorController.init(context) }

    val uiState by WinlatorController.state.collectAsState()

    // 容器列表（containersRevision 是对象上的 mutableStateOf —— 读取即订阅，
    // refreshContainers() 自增后本 Composable 自动重组重读）
    val revision = WinlatorController.containersRevision
    val containers = remember(revision) { WinlatorController.containers() }

    // 编辑页状态（null = 列表；非空 = 编辑该容器）
    var editing by remember { mutableStateOf<Container?>(null) }
    var creating by remember { mutableStateOf(false) }

    // exe 选择（FilePickBus：文件资源管理器 exe 模式回传）。
    // 单监听器 + 模式标志：同一选择入口服务"运行 exe"与"生成快捷方式"。
    var pickingFor by remember { mutableStateOf<Container?>(null) }
    var shortcutMode by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val dereg = FilePickBus.listen(
            targetApp = "winlator",
            targetWindow = scope.windowState.id
        ) { path ->
            val c = pickingFor
            pickingFor = null
            if (c != null) {
                if (shortcutMode) {
                    WinlatorController.createDesktopShortcut(
                        context, c, path,
                        path.substringAfterLast('/').substringBeforeLast('.')
                    )
                    ToastHelper.show(context, "桌面快捷方式已创建")
                } else {
                    // 选定 exe：直接运行（画面显示在 AnWind X11 浮动窗口）
                    WinlatorController.runExe(context, c, path)
                }
            }
            shortcutMode = false
        }
        onDispose { dereg() }
    }

    // 编辑器窗口内容（直接本窗口内切换，与 X11 设置面板同风格）
    val showEditor = creating || editing != null
    if (showEditor) {
        ContainerEditorSheet(
            container = editing,
            onDismiss = { creating = false; editing = null }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (theme.isDark) Color(0xFF14181D) else Color(0xFFF6F7F9))
    ) {
        // ===== 顶部工具条 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Winlator 容器",
                color = theme.windowTitleBarTextColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (uiState.state) {
                    WinlatorSession.State.RUNNING -> "会话运行中"
                    WinlatorSession.State.STARTING -> "启动中…"
                    WinlatorSession.State.INSTALLING -> "安装引擎…"
                    WinlatorSession.State.STOPPING -> "停止中…"
                    WinlatorSession.State.ERROR -> "错误"
                    else -> "空闲"
                },
                color = if (uiState.state == WinlatorSession.State.RUNNING) Color(0xFF7BD88F)
                else theme.windowTitleBarTextColor.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                creating = true
            }, enabled = uiState.imageFsReady) {
                Text("＋ 新建容器", fontSize = 11.sp)
            }
            TextButton(onClick = { WinlatorController.installImageFs(context) }) {
                Text(
                    if (uiState.imageFsReady) "重装引擎" else "安装引擎资产",
                    fontSize = 11.sp
                )
            }
        }

        // ===== 引擎资产安装进度 =====
        if (uiState.installingPercent > 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    uiState.installingMessage.ifEmpty { "安装引擎资产 ${uiState.installingPercent}%" },
                    color = theme.windowTitleBarTextColor, fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                @Suppress("DEPRECATION")
                androidx.compose.foundation.LinearProgressIndicator(
                    progress = uiState.installingPercent / 100f,
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = theme.accentColor
                )
            }
        }

        // ===== 错误/消息条 =====
        if (uiState.state == WinlatorSession.State.ERROR && uiState.message.isNotEmpty()) {
            Text(
                uiState.message,
                color = Color(0xFFE57373),
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33E57373))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // ===== 会话运行横幅 =====
        if (uiState.state == WinlatorSession.State.RUNNING || uiState.state == WinlatorSession.State.STARTING) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.accentColor.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    uiState.message.ifEmpty { uiState.state.name },
                    color = theme.windowTitleBarTextColor, fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { WinlatorController.stopSession() }) {
                    Text("停止会话", fontSize = 11.sp, color = Color(0xFFE57373))
                }
            }
        }

        // ===== 容器列表 =====
        if (containers.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("暂无容器", color = theme.windowTitleBarTextColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (uiState.imageFsReady) "点击右上角「＋ 新建容器」创建第一个容器\n创建完成后在容器内选择 exe 即可运行游戏\n（游戏画面将显示在 AnWind 的 X11 窗口）"
                    else "首次使用请先点击右上角「安装引擎资产」\n（imagefs/proton 系统 + Box64 运行时，首次解压约 2-5 分钟）",
                    color = theme.windowTitleBarTextColor.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(containers, key = { it.id }) { c ->
                    ContainerCard(
                        container = c,
                        onRun = {
                            pickingFor = c
                            // 打开文件资源管理器（exe 选择模式）
                            wm.open(
                                appId = "file_explorer",
                                title = "选择 exe",
                                launchMode = LaunchMode.FLOATING,
                                launchArgs = mapOf(
                                    "pickMode" to "exe",
                                    "targetApp" to "winlator",
                                    "targetWindow" to scope.windowState.id
                                )
                            )
                        },
                        onEdit = { editing = c },
                        onShortcut = {
                            pickingFor = c
                            shortcutMode = true
                            wm.open(
                                appId = "file_explorer",
                                title = "选择 exe（生成快捷方式）",
                                launchMode = LaunchMode.FLOATING,
                                launchArgs = mapOf(
                                    "pickMode" to "exe",
                                    "targetApp" to "winlator",
                                    "targetWindow" to scope.windowState.id
                                )
                            )
                        },
                        onDelete = { WinlatorController.deleteContainer(c) },
                        onDuplicate = { WinlatorController.duplicateContainer(c) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContainerCard(
    container: Container,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onShortcut: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val theme = LocalWinTheme.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (theme.isDark) Color(0xFF1B222B) else Color.White,
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                container.getName(),
                color = theme.windowTitleBarTextColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Wine " + container.getWineVersion().removePrefix("proton-").removeSuffix("-x86_64"),
                color = theme.windowTitleBarTextColor.copy(alpha = 0.6f),
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "屏幕 ${container.getScreenSize()} · 图形 ${container.getGraphicsDriver()} · " +
                "${container.getDXWrapper()} · 音频 ${container.getAudioDriver()}" +
                (if (container.isWoW64Mode()) " · WoW64" else ""),
            color = theme.windowTitleBarTextColor.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onRun, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("▶ 运行 exe", fontSize = 11.sp, color = theme.accentColor)
            }
            TextButton(onClick = onShortcut, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("生成快捷方式", fontSize = 11.sp)
            }
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("设置", fontSize = 11.sp)
            }
            TextButton(onClick = onDuplicate, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("复制", fontSize = 11.sp)
            }
            TextButton(onClick = { confirmDelete = !confirmDelete }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("删除", fontSize = 11.sp, color = Color(0xFFE57373))
            }
        }
        if (confirmDelete) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "确认删除容器及其全部数据？",
                    color = Color(0xFFE57373), fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { confirmDelete = false }) { Text("取消", fontSize = 11.sp) }
                TextButton(onClick = onDelete) { Text("确认删除", fontSize = 11.sp, color = Color(0xFFE57373)) }
            }
        }
    }
}

/** 简易 Toast 帮手（非 Composable 上下文用）。 */
object ToastHelper {
    fun show(context: android.content.Context, msg: String) {
        try {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
