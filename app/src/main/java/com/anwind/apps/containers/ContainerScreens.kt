package com.anwind.apps.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.WindowContentScope
import kotlinx.coroutines.Dispatchers
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

    Column(
        Modifier
            .fillMaxSize()
            .background(themeBg())
            .padding(12.dp)
    ) {
        // ---------------- 标题行 ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Wine 容器",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = LocalWinTheme.current.windowTitleBarTextColor
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "X11 · box64 / FEXCore / Hangover",
                fontSize = 11.sp,
                color = themeSec()
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showDoctor = true }) { Text("体检") }
            Button(
                onClick = { showCreate = true },
                enabled = rootfsReady,
                colors = ButtonDefaults.buttonColors(containerColor = themeAccent())
            ) { Text("+ 新建容器") }
        }

        Spacer(Modifier.height(8.dp))

        // ---------------- rootfs 缺失提示 ----------------
        if (!rootfsReady) {
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
            Spacer(Modifier.height(8.dp))
        }

        // ---------------- 容器列表 ----------------
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

    // ---------------- 弹窗 ----------------
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
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("删除容器") },
            text = { Text("确定删除容器 \"${c.name}\"？其 WINEPREFIX 与已安装程序将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    cs.launch(Dispatchers.IO) {
                        ContainerManager.remove(c.name)
                        withContext(Dispatchers.Main) { refresh() }
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("取消") }
            }
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
    onShowLog: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = themeCard()),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            // 名称行
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
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = onSetDefault,
                    label = { Text(if (isDefault) "默认容器" else "设为默认", fontSize = 10.sp) }
                )
            }
            Spacer(Modifier.height(4.dp))
            // 摘要行
            Text(
                buildString {
                    append("后端 ${data.backend.label}")
                    if (data.backend == ContainerBackend.BOX64) append(" · ${data.preset.label}")
                    append(" · 显示 ${data.dmode.label}")
                    if (data.dmode != DisplayMode.OFF && data.screen != "native") append(" ${data.screen}")
                    append(" · 渲染 ${data.gallium}")
                    if (data.wine.isNotEmpty()) append(" · wine ${data.wine}")
                    if (data.lang.isNotEmpty() && data.lang != ContainerLangs.DEFAULT) append(" · ${data.lang}")
                    if (data.audio == ContainerAudio.OFF) append(" · 静音")
                    if (data.dxvk != "off") append(" · DXVK ${data.dxvk}")
                    if (data.vkd3d != "off") append(" · VKD3D ${data.vkd3d}")
                },
                fontSize = 11.sp, color = themeSec()
            )

            Spacer(Modifier.height(8.dp))

            // 操作行
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onRun,
                    colors = ButtonDefaults.buttonColors(containerColor = themeAccent()),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) { Text("运行 exe", fontSize = 12.sp) }

                OutlinedButton(
                    onClick = onWineCfg,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("winecfg", fontSize = 12.sp) }

                if (running) {
                    OutlinedButton(
                        onClick = onStop,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("停止", fontSize = 12.sp) }
                }

                OutlinedButton(
                    onClick = onShowLog,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("日志", fontSize = 12.sp) }

                Box {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) { Text("⋯", fontSize = 12.sp) }

                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("初始化前缀 (wineboot -i)") }, onClick = { menuOpen = false; onWineBoot() })
                        DropdownMenuItem(text = { Text("注册表编辑器 (regedit)") }, onClick = { menuOpen = false; onRegEdit() })
                        DropdownMenuItem(text = { Text("编辑配置") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("克隆容器") }, onClick = { menuOpen = false; onClone() })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除容器", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onRemove() }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 新建容器
// ---------------------------------------------------------------------------

@Composable
private fun CreateContainerDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf(ContainerBackend.AUTO) }
    var preset by remember { mutableStateOf(Box64Preset.PERFORMANCE) }
    var screen by remember { mutableStateOf(ContainerData.DEFAULT_SCREEN) }
    var dmode by remember { mutableStateOf(DisplayMode.OFF) }
    var gallium by remember { mutableStateOf(ContainerData.DEFAULT_GALLIUM) }
    var wine by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf(ContainerLangs.DEFAULT) }
    var audio by remember { mutableStateOf(ContainerAudio.PULSE) }
    var env by remember { mutableStateOf("") }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    // wine 版本列表（rootfs /usr/opt 扫描；无 wine 时选择器只剩"默认"）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            wines = ContainerManager.listWines()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Wine 容器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("容器名（字母/数字开头）") },
                    isError = error != null,
                    singleLine = true
                )
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                LabeledEnumSelector("CPU 翻译后端", backend, ContainerBackend.entries.toList()) { backend = it }
                LabeledEnumSelector("Box64 预设（仅 box64 后端）", preset, Box64Preset.entries.toList()) { preset = it }
                LabeledEnumSelector("显示模式（glibc-runner -d/-v/-f）", dmode, DisplayMode.entries.toList()) { dmode = it }
                LabeledChoiceSelector("虚拟分辨率", screen, ContainerData.SCREEN_CHOICES) { screen = it }
                LabeledWineSelector(wines, wine) { wine = it }
                LabeledChoiceSelector(
                    "Mesa 渲染器", gallium,
                    GalliumDriver.entries.map { it.id }, GalliumDriver.entries.map { it.label }
                ) { gallium = it }
                LabeledChoiceSelector("容器语言（与终端隔离）", lang, ContainerLangs.CHOICES, ContainerLangs.DISPLAY) { lang = it }
                LabeledEnumSelector("音频", audio, ContainerAudio.entries.toList()) { audio = it }
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    label = { Text("附加环境变量（K=V 空格分隔，可空）") },
                    singleLine = true
                )
                Text(
                    "显示：X11（AnWind 内置 X 服务，启动容器自动拉起，DISPLAY=:1）；" +
                        "环境/音频/语言均由容器自持，不受终端影响",
                    fontSize = 11.sp, color = themeSec()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val err = ContainerManager.create(
                    ContainerData(
                        name = name.trim(), backend = backend,
                        preset = preset, screen = screen, dmode = dmode,
                        gallium = gallium, wine = wine,
                        lang = lang, audio = audio, env = env.trim()
                    )
                )
                if (err == null) onCreated() else error = err
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

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
    var env by remember { mutableStateOf(container.env) }
    var wine by remember { mutableStateOf(container.wine) }
    var lang by remember { mutableStateOf(container.lang) }
    var audio by remember { mutableStateOf(container.audio) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    // wine 版本列表（rootfs /usr/opt 扫描）
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            wines = ContainerManager.listWines()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑容器 · ${container.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledEnumSelector("CPU 翻译后端", backend, ContainerBackend.entries.toList()) { backend = it }
                LabeledEnumSelector("Box64 预设（仅 box64 后端）", preset, Box64Preset.entries.toList()) { preset = it }
                LabeledEnumSelector("显示模式（glibc-runner -d/-v/-f）", dmode, DisplayMode.entries.toList()) { dmode = it }
                LabeledChoiceSelector("虚拟分辨率", screen, ContainerData.SCREEN_CHOICES) { screen = it }
                LabeledWineSelector(wines, wine) { wine = it }
                LabeledChoiceSelector(
                    "Mesa 渲染器", gallium,
                    GalliumDriver.entries.map { it.id }, GalliumDriver.entries.map { it.label }
                ) { gallium = it }
                LabeledChoiceSelector("容器语言（与终端隔离）", lang, ContainerLangs.CHOICES, ContainerLangs.DISPLAY) { lang = it }
                LabeledEnumSelector("音频", audio, ContainerAudio.entries.toList()) { audio = it }
                OutlinedTextField(
                    value = env,
                    onValueChange = { env = it },
                    label = { Text("附加环境变量（K=V 空格分隔）") },
                    singleLine = true
                )
                Text(
                    "wine 新版本：本窗下方分类在线安装，或终端 anwind-container install-wine <类别|URL>（wine-catalog 查看构建目录）",
                    fontSize = 11.sp, color = themeSec()
                )
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val err = ContainerManager.update(
                    container.copy(
                        backend = backend, preset = preset,
                        screen = screen, dmode = dmode, gallium = gallium,
                        env = env.trim(), wine = wine, lang = lang, audio = audio
                    )
                )
                if (err == null) onSaved() else error = err
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------------------
// 运行 exe
// ---------------------------------------------------------------------------

@Composable
private fun RunContainerDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onLaunched: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    var exe by remember { mutableStateOf("C:\\windows\\notepad.exe") }
    var args by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行 · ${container.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = exe, onValueChange = { exe = it },
                    label = { Text("程序路径（Windows 路径或容器内绝对路径）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = args, onValueChange = { args = it },
                    label = { Text("启动参数（可空）") },
                    singleLine = true
                )
                Text(
                    "点击\"运行\"将自动打开 X11 桌面窗口并经 box64/FEXCore 启动 Wine。",
                    fontSize = 11.sp, color = themeSec()
                )
                if (msg != null) {
                    Text(msg ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val err = WineSessionLauncher.runExe(context, container, exe.trim(), args.trim())
                if (err == null) onLaunched() else msg = err
            }) { Text("运行") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("克隆容器 · ${source.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text("新容器名") },
                    isError = error != null, singleLine = true
                )
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val err = ContainerManager.clone(source.name, name.trim())
                if (err == null) onCloned() else error = err
            }) { Text("克隆") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------------------
// 体检
// ---------------------------------------------------------------------------

@Composable
private fun DoctorDialog(onDismiss: () -> Unit) {
    val checks = remember { ContainerManager.doctor() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AnWind Wine 容器体检") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(checks.size) { i ->
                    val c = checks[i]
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
        },
        confirmButton = { Button(onClick = onDismiss) { Text("好的") } }
    )
}

// ---------------------------------------------------------------------------
// 日志
// ---------------------------------------------------------------------------

@Composable
private fun LogDialog(title: String, log: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            LazyColumn {
                item {
                    Text(
                        log.takeLast(6000),
                        fontSize = 11.sp,
                        color = themeSec(),
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } }
    )
}

// ---------------------------------------------------------------------------
// 通用选择器
// ---------------------------------------------------------------------------

/** 枚举单选组（标题 + 横向流式选项）。 */
@Composable
private fun <T> LabeledEnumSelector(
    label: String,
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit
) where T : Enum<T> {
    Column {
        Text(label, fontSize = 12.sp, color = themeSec())
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScrollIfNeeded()
        ) {
            options.forEach { opt ->
                val sel = opt == selected
                val label2 = when (opt) {
                    is ContainerBackend -> opt.label
                    is Box64Preset -> opt.label
                    else -> opt.name
                }
                FilterChip(
                    selected = sel,
                    onClick = { onSelect(opt) },
                    label = { Text(label2, fontSize = 11.sp) }
                )
            }
        }
    }
}

/** 字符串单选组（可选值列表 + 可选展示名）。 */
@Composable
private fun LabeledChoiceSelector(
    label: String,
    selected: String,
    values: List<String>,
    displayNames: List<String>? = null,
    onSelect: (String) -> Unit
) {
    Column {
        Text(label, fontSize = 12.sp, color = themeSec())
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScrollIfNeeded()
        ) {
            values.forEachIndexed { i, v ->
                FilterChip(
                    selected = v == selected,
                    onClick = { onSelect(v) },
                    label = { Text(displayNames?.getOrNull(i) ?: v, fontSize = 11.sp) }
                )
            }
        }
    }
}

/** Wine 版本选择器（多版本：默认/主槽位/wine-<名> 槽位）。 */
@Composable
private fun LabeledWineSelector(
    wines: List<WineInstall>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    Column {
        Text("Wine 版本（/usr/opt 槽位）", fontSize = 12.sp, color = themeSec())
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScrollIfNeeded()
        ) {
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onSelect("") },
                label = { Text("默认", fontSize = 11.sp) }
            )
            wines.forEach { w ->
                FilterChip(
                    selected = selected == w.name,
                    onClick = { onSelect(w.name) },
                    label = { Text(w.name, fontSize = 11.sp) }
                )
            }
            if (wines.isEmpty()) {
                Text(
                    "（未装多版本，可点下方分类在线安装）",
                    fontSize = 10.sp, color = themeSec(),
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }
        // v2.27：在线构建目录一键安装（wine-catalog：bionic 双形态 /
        // 普通 Wine / Proton；CLI 自动取最新 Release 资产并命名槽位，
        // 下载进度在"Wine 输出"面板查看，装完重开本窗即可选择）
        Text(
            "在线安装（自动取最新构建，进度见\"Wine 输出\"面板）:",
            fontSize = 10.sp, color = themeSec(),
            modifier = Modifier.padding(top = 6.dp)
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
