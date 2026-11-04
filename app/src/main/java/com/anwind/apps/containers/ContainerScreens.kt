package com.anwind.apps.containers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.apps.containers.launcher.RootfsLauncher
import com.anwind.apps.containers.launcher.WineEnvironment
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.WinConfirmDialog
import com.anwind.core.window.WinDialog
import com.anwind.core.window.WindowContentScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AnWind Wine 容器 — 管理界面（v3.2.18 · wineguanli.py 完整重制）。
 *
 * 本文件是 wineguanli.py（PyQt5 容器管理器，6357 行）的 Compose 逐面
 * 移植：主窗口（工具栏 + 左右分栏 + 五选项卡 + 底部操作行）、高级设置
 * 页（DY 配置 / Box 配置 / Wine 安装 / MANGOHUD）、更多设置对话框
 * （CPU 核心 / 函数库 / 环境变量 / 高级功能 / Direct3D + AnWind 原生）、
 * 新建容器对话框、MANGOHUD 配置对话框、多选对话框、快捷方式。
 *
 * ## 全宽度适配（wineguanli.py 的 QSplitter 可以实现所有宽度，本重制
 * 做同样承诺 —— 用户 v3.2.17 反馈："没有那么宽也用，直接缩小 ui，
 * 加入滑动"）：
 *  - 布局恒为左右分栏（左列 280dp + 右列 ≥712dp），不再有"窄屏回落
 *    旧卡片"的分支 —— 旧实现 maxWidth<700dp 走旧 UI，手机上永远看不到
 *    重制界面，正是用户"UI 没变"的根源；
 *  - 缩放系数 scale = 可用宽度 / 1024（wineguanli 主窗口 setMinimum
 *    Size(1024,600) 的等比适配），夹在 0.6..1.0：横屏平板 1:1，横屏
 *    手机 ~0.8，竖屏手机 0.6；
 *  - 内容最小宽度 = 1024 × scale：可用宽度放得下就恰好铺满（不滚动），
 *    放不下（竖屏手机）→ 外层横向滑动（horizontalScroll）+ 顶部提示；
 *  - 全部新组件经 [gdp]/[gsp] 读 [LocalGuanliScale]，间距字号等比缩小。
 *
 * ## 字段 → container.conf 的移植映射（对照 wineguanli.py）
 *  - 基本设置：架构(x86_64/arm64ec → backend=box64/arm64ec)、Wine 版本
 *    (wine=)、分辨率(全屏→screen=native / WxH→screen=WxH)、语言(lang=)、
 *    帧数限制(frame=)、独立窗口+分辨率(dmode=f/d/off)、EXE 文件(exe=)、
 *    高级启动选项七勾选(flags= csv)
 *  - 图形驱动：Turnip/Zink/WineD3D(turnip=/zink=/wined3d=，/opt/tz、
 *    /opt/zink、/opt/vkord3d 扫描)、DXVK(dxvk=，默认=auto / 不使用=off /
 *    /opt/dxvk1 槽位)
 *  - CPU 核心：cores=（wineguanli 写 /opt/conf/cores.conf，AnWind 写
 *    容器键 + PRIMARY_CORES/SECONDARY_CORES 环境注入，语义一致）
 *  - 函数库：dsound=/dshow=/dpi=/winver=（注册表落地见 WineLibrary）
 *  - DY 配置：env= 内 BOX64_* 变量（wineguanli 写全局 /opt/conf/box64.conf，
 *    AnWind 容器级 env= 由启动链 applyExtraEnv 最后叠加，可覆盖一切）
 *  - 环境变量：MESA_GL_VERSION/MESA_GLSL_VERSION/VKD3D_FEATURE_LEVEL/
 *    MESA_EXTENSION_OVERRIDE/DXVK_HUD/TU_DEBUG/MANGOHUD_CONFIG → env=/mangohud=
 *  - Direct3D / CNC DDraw：HKCU AppDefaults\<exe>\DllOverrides（wine 原生
 *    按程序覆盖机制，等效 wineguanli 的"复制 DLL 到 exe 目录"）
 */

// ===========================================================================
// 缩放系统：所有 wineguanli 面的尺寸经 LocalGuanliScale 等比缩放
// ===========================================================================

/** 当前 wineguanli 面的尺寸缩放系数（1f = wineguanli 桌面原始尺寸）。 */
val LocalGuanliScale = staticCompositionLocalOf { 1f }

/**
 * v3.2.23（用户问题 5）：字号缩放系数 —— 与尺寸缩放分离。
 *
 * 背景：主窗默认尺寸 1060→900dp（用户反馈“太长太宽，拉小”），若仍按
 * 单一 scale = maxWidth/1024 缩，字号会连带缩到 88% —— 与“字体保持
 * 默认”诉求相悖。分离后：
 *  - gdp（尺寸）：基准仍是 1024 —— 窄窗时布局尺寸等比缩，防溢出；
 *  - gsp（字号）：基准 900 —— 默认 900dp 窗字号 = 旧 1060dp 窗完全
 *    一致（都是 1f，叠加 GuanliUiK 密度收紧后即用户当前看到的字号），
 *    仅当窗口拖到比 900 还窄时字号才同步缩（窄窗适配语义保留）。
 * 弹窗体经 [GuanliDialogScale] 同步拿到两个系数。
 */
val LocalGuanliFontScale = staticCompositionLocalOf { 1f }

/** 字号缩放基准（主窗默认宽）：与 ContainerApp.defaultWidth 对齐。 */
const val GuanliFontBaseDp = 900f

/** dp × 尺寸缩放。 */
@Composable
fun gdp(d: Dp): Dp = (d.value * LocalGuanliScale.current).dp

/** sp × 字号缩放（v3.2.23 起独立于尺寸缩放，见 LocalGuanliFontScale）。 */
@Composable
fun gsp(s: TextUnit): TextUnit = (s.value * LocalGuanliFontScale.current).sp

/**
 * v3.2.21 全局收紧系数：用户反馈“整体 UI 太大，内部字体以及框框都太大，
 * 很不舒服，甚至一些窗口的框框非常大”（图二创建新容器弹窗）——
 * Guanli 面所有内容（含 Material 组件与弹窗）统一缩至 80%。
 *
 * 实现分工（不叠加）：
 *  - gdp/gsp 自定义控件：LocalGuanliScale 原有适配不变，主窗根部的
 *    LocalDensity 覆盖（density × K）把 dp/sp→px 换算收紧 20%；
 *  - Material 组件（OutlinedTextField/Button/TabRow/Checkbox 等，不走
 *    gdp/gsp）：同一 LocalDensity 覆盖同步收紧 20%；
 *  - 弹窗（WinDialog 内容在弹窗层组合，继承不到本窗密度/缩放）：
 *    调用点 width × K + 内容包 [GuanliDialogScale]（v3.2.20 之前弹窗层
 *    拿到的是 scale=1 + 原始密度，所以图二弹窗比主窗还大一圈 ——
 *    56dp 的 Material 输入框 + 未缩放字号双重叠加）。
 * 只调这一个常量即可继续微调整面尺寸。
 */
const val GuanliUiK = 0.8f

/**
 * 弹窗内容收紧：弹窗内容在 WinDialogHost 子树组合（另一棵组合树），
 * 组合局部变量按树位置解析，继承不到容器窗口的 LocalDensity/LocalGuanliScale
 * —— 在弹窗内容根部自建同幅缩放。
 * @param winScale 调用方窗口的 LocalGuanliScale（尺寸缩放）
 * @param winFontScale 调用方窗口的 LocalGuanliFontScale（字号缩放，
 *        v3.2.23 起与尺寸分离；缺省退回 winScale 保持旧语义）
 */
@Composable
fun GuanliDialogScale(winScale: Float, winFontScale: Float = winScale, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * GuanliUiK, base.fontScale),
        LocalGuanliScale provides winScale,
        LocalGuanliFontScale provides winFontScale
    ) { content() }
}

/** wineguanli.py Qt 样式表的配色（QToolBar/QGroupBox/QPushButton 原色）。 */
private object GuanliColors {
    val toolbar = Color(0xFF2C3E50)        // QToolBar 背景
    val toolbarHover = Color(0xFF34495E)   // 工具栏按钮 hover
    val accent = Color(0xFF3498DB)         // QPushButton 主蓝
    val accentDark = Color(0xFF2980B9)
    val accentPressed = Color(0xFF21618C)
    val green = Color(0xFF2ECC71)          // ▶ 运行
    val greenDeep = Color(0xFF28A745)      // 💾 立即应用/保存配置
    val greenDeepHover = Color(0xFF218838)
    val red = Color(0xFFE74C3C)            // 删除类
    val redFab = Color(0xFFF44336)         // DY 删除变量
    val blueFab = Color(0xFF2196F3)        // Qt "选择…"类按钮
    val grey = Color(0xFF9E9E9E)           // 重置默认
    val info = Color(0xFF7F8C8D)           // 说明文字
    val panelBg = Color(0xFFF5F7FA)        // 渐变窗口底（浅端）
    val listSel = Color(0xFFD6EAF8)        // QListWidget::item:selected 浅
    val rowAlt = Color(0xFFF8F9FA)         // 信息底
}

// ===========================================================================
// 入口（WindowContentScope 契约不变）
// ===========================================================================

@Composable
private fun themeBg() = LocalWinTheme.current.windowBackgroundColor
@Composable
private fun themeCard() = LocalWinTheme.current.cardBackgroundColor
@Composable
private fun themeAccent() = LocalWinTheme.current.accentColor
@Composable
private fun themeSec() = LocalWinTheme.current.secondaryTextColor
@Composable
private fun themeFg() = LocalWinTheme.current.windowTitleBarTextColor

@Composable
fun ContainerListContent(scope: WindowContentScope) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var tick by remember { mutableIntStateOf(0) }                 // 刷新触发器
    var containers by remember { mutableStateOf<List<ContainerData>>(emptyList()) }
    var rootfsReady by remember { mutableStateOf(true) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }

    // wineguanli.py：QStackedWidget 两个视图（容器管理 / 高级设置）
    var advancedView by remember { mutableStateOf(false) }

    // 弹窗路由（同屏最多一个主弹窗 + 若干子选择器）
    var showCreate by remember { mutableStateOf(false) }
    var showDoctor by remember { mutableStateOf(false) }
    var showRunFor by remember { mutableStateOf<ContainerData?>(null) }
    var showLogFor by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf<ContainerData?>(null) }
    var renameFor by remember { mutableStateOf<ContainerData?>(null) }
    var moreFor by remember { mutableStateOf<ContainerData?>(null) }       // 更多设置
    var mangoFor by remember { mutableStateOf<ContainerData?>(null) }      // MANGOHUD 配置
    var shortcutFor by remember { mutableStateOf<ContainerData?>(null) }   // 创建快捷方式
    var multiSelect by remember { mutableStateOf<MultiSelectRequest?>(null) }

    // v3.1.6 前缀构建日志：构建进行中自动弹出；也可从面板手动打开
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
            wines = ContainerManager.listWines()
            com.anwind.apps.terminal.termux.AnWindTzstAssets
                .applyDxvkWhenPrefixReady(context)
        }
    }

    fun refresh() { tick++ }

    // 当前选中容器（右侧面板数据源；wineguanli.py container_list 选中项）
    var selectedName by remember { mutableStateOf<String?>(null) }
    val sel = containers.firstOrNull { it.name == selectedName } ?: containers.firstOrNull()

    // v3.2.18：桌面快捷方式自动运行（launchArgs.autorun = 容器名；
    // ShortcutDialog 固定的快捷方式经 MainActivity 带名打开本窗口）
    val autorun = scope.windowState.launchArgs["autorun"]
    var autorunDone by remember { mutableStateOf(false) }
    LaunchedEffect(autorun, containers.isNotEmpty()) {
        if (autorunDone || autorun.isNullOrBlank() || containers.isEmpty()) return@LaunchedEffect
        val c = containers.firstOrNull { it.name == autorun } ?: return@LaunchedEffect
        autorunDone = true
        selectedName = autorun
        if (c.exe.isNotBlank()) {
            withContext(Dispatchers.IO) {
                val err = WineSessionLauncher.runExe(context, c, c.exe)
                withContext(Dispatchers.Main) {
                    err?.let {
                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            showRunFor = c
        }
    }

    fun refreshAndKeep(name: String?) {
        selectedName = name
        tick++
    }

    // ---------- 运行类动作（先解压 /opt 驱动包，再走原生启动链） ----------
    fun extractOptDriversThen(cont: ContainerData, andThen: () -> Unit) {
        cs.launch(Dispatchers.IO) {
            // wineguanli.py run_container：运行前确保 turnip/zink/dxvk/
            // wined3d 选中的包已解压（extract_package → /usr/local）
            listOf(
                cont.turnip to "tz", cont.zink to "zink",
                cont.wined3d to "vkord3d"
            ).forEach { (slot, dir) ->
                if (slot.isNotBlank() && slot != "off") {
                    GuanliSupport.scanOpt(dir)
                        .firstOrNull { it.display == slot }
                        ?.let { tar ->
                            val err = GuanliSupport.extractIntoRootfsLocal(tar.path)
                            if (err != null) {
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        context, "驱动 ${tar.display} 解压失败：$err",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                }
            }
            withContext(Dispatchers.Main) { andThen() }
        }
    }

    fun runContainer(cont: ContainerData) {
        if (cont.exe.isNotBlank()) {
            extractOptDriversThen(cont) {
                cs.launch(Dispatchers.IO) {
                    val err = WineSessionLauncher.runExe(context, cont, cont.exe)
                    withContext(Dispatchers.Main) {
                        err?.let {
                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                        }
                        refresh()
                    }
                }
            }
        } else {
            showRunFor = cont
        }
    }

    fun winebootContainer(cont: ContainerData) {
        extractOptDriversThen(cont) {
            cs.launch(Dispatchers.IO) {
                val err = WineSessionLauncher.initializePrefix(context, cont)
                withContext(Dispatchers.Main) {
                    err?.let {
                        android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
        }
    }

    // ---------- 全宽度适配（见文件头说明） ----------
    // v3.2.21 全局收紧（用户反馈“整体 UI 太大，内部字体以及框框都太大”）：
    // ① 主窗：根级 LocalDensity 覆盖（density × GuanliUiK）——gdp/gsp 自定义
    //    控件与 Material 组件（不走 gdp/gsp）同步缩小 20%，不叠加
    //    （dp/sp 数值不变，仅 dp/sp→px 换算收紧）；
    // ② 弹窗：WinDialog 内容在弹窗层组合，继承不到这里的密度/缩放 ——
    //    winScale 状态提升 + 根级 LocalGuanliScale 提供给弹窗体，
    //    各弹窗内容包 GuanliDialogScale + width×K（见各弹窗定义）。
    var winScale by remember { mutableStateOf(1f) }
    var winFontScale by remember { mutableStateOf(1f) }
    val baseDens = LocalDensity.current
    CompositionLocalProvider(
        LocalGuanliScale provides winScale,
        LocalGuanliFontScale provides winFontScale
    ) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(themeBg())
    ) {
        val scale = (maxWidth.value / 1024f).coerceIn(0.6f, 1f)
        // v3.2.23（用户问题 5）：尺寸/字号双轨 —— 布局尺寸按 1024 基准缩
        //（防溢出），字号按 900 基准缩（默认窗 900dp 下与旧 1060dp 窗字号
        // 一致，满足“窗口拉小、字体保持默认”）。
        val fontScale = (maxWidth.value / GuanliFontBaseDp).coerceIn(0.6f, 1f)
        // 组合期回写：本 Box 在弹窗调用之前组合（同函数代码顺序），
        // 弹窗体经根级 provider 读到当前窗口的适配缩放
        winScale = scale
        winFontScale = fontScale
        val minW = 1024.dp * scale
        // v3.2.20 修复：maxWidth 在 Column/CompositionLocalProvider 嵌套 lambda
        // 里不能经隐式接收者解析（CI 实报 337:39 "'val maxWidth: Dp' can't be
        // called in this context by implicit receiver"）—— 在 BoxWithConstraints
        // 作用域内捕获为局部 val，嵌套 lambda 按闭包捕获继续可用。
        val availW = maxWidth

        CompositionLocalProvider(
            LocalGuanliScale provides scale,
            LocalGuanliFontScale provides fontScale,
            LocalDensity provides Density(baseDens.density * GuanliUiK, baseDens.fontScale)
        ) {
            Column(Modifier.fillMaxSize()) {
                // ---------------- 工具栏（wineguanli.py QToolBar 同款） ----------------
                GuanliToolbar(
                    advancedView = advancedView,
                    onSwitchView = { advancedView = it },
                    onCloseWindow = { scope.onClose() },
                    onDoctor = { showDoctor = true },
                    onCreate = { showCreate = true },
                    onShortcuts = { shortcutFor = sel },
                    canShortcuts = sel != null
                )

                if (!rootfsReady) {
                    RootfsMissingHint()
                }

                // ---------------- 主内容（两视图切换） ----------------
                if (!advancedView) {
                    GuanliContainerPage(
                        containers = containers,
                        wines = wines,
                        sel = sel,
                        running = { name -> WineSessionLauncher.activeSessions.containsKey(name) },
                        onSelect = { selectedName = it },
                        onRefresh = { refresh() },
                        onRun = { runContainer(it) },
                        onWineboot = { winebootContainer(it) },
                        onStop = { c ->
                            cs.launch(Dispatchers.IO) {
                                WineSessionLauncher.stopContainer(context, c.name)
                                withContext(Dispatchers.Main) { refresh() }
                            }
                        },
                        onShowLog = { showLogFor = it.name },
                        onPrefixLog = { prefixLogFor = it.name },
                        onRemove = { confirmRemove = it },
                        onRename = { renameFor = it },
                        onMore = { moreFor = it },
                        onMango = { mangoFor = it },
                        onShortcut = { shortcutFor = it },
                        onSetDefault = { c ->
                            cs.launch(Dispatchers.IO) {
                                ContainerManager.setDefault(c.name)
                                withContext(Dispatchers.Main) { refresh() }
                            }
                        },
                        onSwitchLang = { c, lang ->
                            cs.launch(Dispatchers.IO) {
                                ContainerManager.update(c.copy(lang = lang))
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        context, "容器 '${c.name}' 语言已切换（已保存）",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    refresh()
                                }
                            }
                        },
                        minWidth = minW,
                        needHScroll = availW < minW,
                        onCreate = { showCreate = true }
                    )
                } else {
                    GuanliAdvancedPage(
                        containers = containers,
                        sel = sel,
                        onRefresh = { refresh() },
                        onMango = { mangoFor = it },
                        multiSelect = { req -> multiSelect = req }
                    )
                }
            }
        }
    }

    // ---------------- 弹窗层 ----------------
    // 创建后待安装前缀的容器（等列表刷新到包含它再触发，避免时序竞争）
    var pendingWineboot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingWineboot, containers) {
        val target = pendingWineboot ?: return@LaunchedEffect
        val c = containers.firstOrNull { it.name == target } ?: return@LaunchedEffect
        pendingWineboot = null
        // wineguanli.py：创建成功后询问"是否立即安装Wine前缀？"（AnWind 直接触发）
        winebootContainer(c)
    }
    if (showCreate) {
        NewContainerDialog(
            wines = wines,
            onDismiss = { showCreate = false },
            onCreated = { name ->
                showCreate = false
                refreshAndKeep(name)
                pendingWineboot = name
            }
        )
    }
    showRunFor?.let { c ->
        RunContainerDialog(
            container = c,
            onDismiss = { showRunFor = null },
            onLaunched = { showRunFor = null; refresh() }
        )
    }
    renameFor?.let { c ->
        RenameContainerDialog(
            container = c,
            onDismiss = { renameFor = null },
            onRenamed = { old, new ->
                renameFor = null
                cs.launch(Dispatchers.IO) {
                    val err = ContainerManager.rename(old, new)
                    withContext(Dispatchers.Main) {
                        err?.let {
                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
                        }
                        refreshAndKeep(if (err == null) new else old)
                    }
                }
            }
        )
    }
    moreFor?.let { c ->
        MoreSettingsDialog(
            container = c,
            onDismiss = { moreFor = null },
            onSaved = { moreFor = null; refresh() }
        )
    }
    mangoFor?.let { c ->
        MangoHudConfigDialog(
            container = c,
            onDismiss = { mangoFor = null },
            onSaved = { mangoFor = null; refresh() }
        )
    }
    shortcutFor?.let { c ->
        ShortcutDialog(
            container = c,
            onDismiss = { shortcutFor = null }
        )
    }
    multiSelect?.let { req ->
        MultiSelectDialog(
            title = req.title,
            options = req.options,
            initial = req.selected,
            onDismiss = { multiSelect = null },
            onOk = {
                req.onOk(it)
                multiSelect = null
            }
        )
    }
    confirmRemove?.let { c ->
        WinConfirmDialog(
            title = "删除容器",
            message = "确定删除容器 \"${c.name}\"？其 WINEPREFIX 与已安装程序将一并删除，不可恢复。",
            confirmText = "删除",
            danger = true,
            uiScale = GuanliUiK,
            onConfirm = {
                confirmRemove = null
                cs.launch(Dispatchers.IO) {
                    ContainerManager.remove(c.name)
                    withContext(Dispatchers.Main) { refreshAndKeep(null) }
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
    } // 根级 LocalGuanliScale provider（v3.2.21：弹窗体读 winScale 用）
}

/** 多选对话框请求（延迟打开，选中结果回投）。 */
data class MultiSelectRequest(
    val title: String,
    val options: List<String>,
    val selected: List<String>,
    val onOk: (List<String>) -> Unit
)

// ===========================================================================
// 工具栏（wineguanli.py create_toolbar：☰ 菜单 + 标题 + 创建/快捷方式）
// ===========================================================================

@Composable
private fun GuanliToolbar(
    advancedView: Boolean,
    onSwitchView: (Boolean) -> Unit,
    onCloseWindow: () -> Unit,
    onDoctor: () -> Unit,
    onCreate: () -> Unit,
    onShortcuts: () -> Unit,
    canShortcuts: Boolean
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(GuanliColors.toolbar)
            .padding(horizontal = gdp(10.dp), vertical = gdp(6.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ☰ 菜单（QToolButton InstantPopup）
        Box {
            Text(
                "☰",
                color = Color.White,
                fontSize = gsp(20.sp),
                modifier = Modifier
                    .clip(RoundedCornerShape(gdp(4.dp)))
                    .border(gdp(0.5.dp), GuanliColors.toolbarHover, RoundedCornerShape(gdp(4.dp)))
                    .clickable { menuOpen = true }
                    .padding(horizontal = gdp(12.dp), vertical = gdp(4.dp))
            )
            GDropdownMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                GDropdownMenuItem("🏠 容器管理") { menuOpen = false; onSwitchView(false) }
                GDropdownMenuItem("⚙️ 高级设置") { menuOpen = false; onSwitchView(true) }
                GDropdownMenuDivider()
                GDropdownMenuItem("🩺 容器体检") { menuOpen = false; onDoctor() }
                GDropdownMenuDivider()
                GDropdownMenuItem("🚪 退出") { menuOpen = false; onCloseWindow() }
            }
        }
        Spacer(Modifier.width(gdp(10.dp)))
        Text(
            if (advancedView) "⚙️ 高级设置" else "🏠 容器管理",
            color = Color.White,
            fontSize = gsp(20.sp),
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        GTextButton("➕ 创建容器", onEnabled = onCreate)
        Spacer(Modifier.width(gdp(8.dp)))
        GTextButton(
            "📄 快捷方式",
            onEnabled = if (canShortcuts) onShortcuts else null
        )
    }
}

/** 工具栏文字按钮（Qt 白字透明底 + hover 深色块）。 */
@Composable
private fun GTextButton(label: String, onEnabled: (() -> Unit)?) {
    Text(
        label,
        color = if (onEnabled != null) Color.White else Color.White.copy(alpha = 0.4f),
        fontSize = gsp(14.sp),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(gdp(4.dp)))
            .background(Color.Transparent)
            .border(gdp(0.5.dp), GuanliColors.toolbarHover, RoundedCornerShape(gdp(4.dp)))
            .clickable(enabled = onEnabled != null) { onEnabled?.invoke() }
            .padding(horizontal = gdp(12.dp), vertical = gdp(7.dp))
    )
}

// ===========================================================================
// 容器管理页（wineguanli.py create_container_page：QSplitter 左右分栏）
// ===========================================================================

@Composable
private fun GuanliContainerPage(
    containers: List<ContainerData>,
    wines: List<WineInstall>,
    sel: ContainerData?,
    running: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onRun: (ContainerData) -> Unit,
    onWineboot: (ContainerData) -> Unit,
    onStop: (ContainerData) -> Unit,
    onShowLog: (ContainerData) -> Unit,
    onPrefixLog: (ContainerData) -> Unit,
    onRemove: (ContainerData) -> Unit,
    onRename: (ContainerData) -> Unit,
    onMore: (ContainerData) -> Unit,
    onMango: (ContainerData) -> Unit,
    onShortcut: (ContainerData) -> Unit,
    onSetDefault: (ContainerData) -> Unit,
    onSwitchLang: (ContainerData, String) -> Unit,
    onCreate: () -> Unit,
    minWidth: Dp,
    needHScroll: Boolean
) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .then(if (needHScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
        ) {
            // ---------------- 左侧面板（280dp，wineguanli create_left_panel） ----------------
            Column(
                Modifier
                    .width(gdp(280.dp))
                    .fillMaxHeight()
                    .padding(gdp(10.dp))
            ) {
                Text(
                    "📦 已创建的容器",
                    fontSize = gsp(20.sp),
                    fontWeight = FontWeight.Bold,
                    color = GuanliColors.toolbar
                )
                Spacer(Modifier.height(gdp(8.dp)))
                LeftContainerListBox(
                    modifier = Modifier.weight(1f),
                    containers = containers,
                    selectedName = sel?.name,
                    running = running,
                    isDefault = { ContainerManager.isDefault(it) },
                    onSelect = onSelect,
                    onRun = onRun,
                    onWineboot = onWineboot,
                    onRename = onRename,
                    onRemove = onRemove,
                    onShortcut = onShortcut,
                    onSwitchLang = onSwitchLang
                )
                Spacer(Modifier.height(gdp(8.dp)))
                // wineguanli.py create_left_panel：新建/删除/重命名 + 创建快捷方式
                Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
                    GSmallButton("➕ 新建", GuanliColors.accent, Modifier.weight(1f)) { onCreate() }
                    GSmallButton(
                        "🗑️ 删除", GuanliColors.red, Modifier.weight(1f),
                        enabled = sel != null
                    ) { sel?.let(onRemove) }
                    GSmallButton(
                        "✏️ 重命名", GuanliColors.grey, Modifier.weight(1f),
                        enabled = sel != null
                    ) { sel?.let(onRename) }
                }
                Spacer(Modifier.height(gdp(6.dp)))
                GSmallButton(
                    "📄 创建快捷方式", GuanliColors.accentDark, Modifier.fillMaxWidth(),
                    enabled = sel != null
                ) { sel?.let(onShortcut) }
            }

            Spacer(Modifier.width(gdp(8.dp)))

            // ---------------- 右侧面板（wineguanli create_right_panel） ----------------
            if (sel != null) {
                RightConfigPanel(
                    container = sel,
                    wines = wines,
                    running = running(sel.name),
                    modifier = if (needHScroll) Modifier.width(gdp(716.dp)) else Modifier.weight(1f),
                    onSaved = onRefresh,
                    onRun = { onRun(sel) },
                    onWineboot = { onWineboot(sel) },
                    onStop = { onStop(sel) },
                    onShowLog = { onShowLog(sel) },
                    onPrefixLog = { onPrefixLog(sel) },
                    onMore = { onMore(sel) },
                    onMango = { onMango(sel) },
                    onSetDefault = { onSetDefault(sel) }
                )
            } else {
                Column(
                    Modifier
                        .then(if (needHScroll) Modifier.width(gdp(716.dp)) else Modifier.weight(1f))
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🍷", fontSize = gsp(40.sp))
                    Spacer(Modifier.height(gdp(8.dp)))
                    Text("未选择容器", fontSize = gsp(16.sp), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(gdp(4.dp)))
                    Text(
                        "在左侧选择或新建一个容器（wineguanli.py 同款左右分栏）",
                        fontSize = gsp(12.sp), color = GuanliColors.info
                    )
                }
            }
        }
        // 窄屏提示横幅（可横滑）
        if (needHScroll) {
            Text(
                "↔ 屏幕比容器管理器窄：界面已按 ${"%.0f".format((LocalGuanliScale.current * 100))}% 缩放，左右滑动查看完整面板",
                fontSize = gsp(10.sp),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = gdp(4.dp))
                    .clip(RoundedCornerShape(gdp(8.dp)))
                    .background(GuanliColors.toolbar.copy(alpha = 0.85f))
                    .padding(horizontal = gdp(10.dp), vertical = gdp(4.dp))
            )
        }
    }
}

// ===========================================================================
// 左侧容器列表（wineguanli.py container_list：双击运行 + 右键菜单）
// ===========================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LeftContainerListBox(
    containers: List<ContainerData>,
    selectedName: String?,
    running: (String) -> Boolean,
    isDefault: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onRun: (ContainerData) -> Unit,
    onWineboot: (ContainerData) -> Unit,
    onRename: (ContainerData) -> Unit,
    onRemove: (ContainerData) -> Unit,
    onShortcut: (ContainerData) -> Unit,
    onSwitchLang: (ContainerData, String) -> Unit,
    // v3.2.20 修复：weight() 只在 Row/Column 内容作用域内可用（CI 实报 709:14
    // "Expression 'weight' cannot be invoked as a function"），本函数体没有
    // ColumnScope 接收者 —— 占满剩余高度的 weight 交由调用点（在 Column 内）
    // 经 modifier 注入，本函数只负责默认不占权重。
    modifier: Modifier = Modifier
) {
    var menuFor by remember { mutableStateOf<ContainerData?>(null) }
    LazyColumn(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(gdp(8.dp)))
            .border(gdp(0.5.dp), themeSec().copy(alpha = 0.3f), RoundedCornerShape(gdp(8.dp))),
        verticalArrangement = Arrangement.spacedBy(gdp(4.dp)),
        contentPadding = PaddingValues(gdp(6.dp))
    ) {
        items(containers, key = { it.name }) { c ->
            val isSel = c.name == selectedName
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(gdp(6.dp)))
                    .background(
                        if (isSel) GuanliColors.accent.copy(alpha = 0.18f)
                        else themeCard()
                    )
                    .border(
                        gdp(0.5.dp),
                        if (isSel) GuanliColors.accent else themeSec().copy(alpha = 0.25f),
                        RoundedCornerShape(gdp(6.dp))
                    )
                    .combinedClickable(
                        onClick = { onSelect(c.name) },
                        onLongClick = { menuFor = c }
                    )
                    .padding(horizontal = gdp(10.dp), vertical = gdp(8.dp))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📦 ${c.name}",
                        fontSize = gsp(14.sp),
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                        color = themeFg(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isDefault(c.name)) {
                        Text(" ★", fontSize = gsp(12.sp), color = GuanliColors.accent)
                    }
                    Spacer(Modifier.weight(1f))
                    if (running(c.name)) {
                        Text("● 运行中", fontSize = gsp(10.sp), color = Color(0xFF4CAF50))
                    }
                }
                Spacer(Modifier.height(gdp(2.dp)))
                Text(
                    buildString {
                        append("[")
                        append(if (c.screen == "native") "全屏" else c.screen)
                        append("]")
                        if (c.wine.isNotBlank()) append(" 🍷${c.wine.removePrefix("wine-")}")
                        append(" ${WindowsVersions.fromId(c.winver).label}")
                    },
                    fontSize = gsp(10.sp),
                    color = themeSec(),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (containers.isEmpty()) {
            item {
                Text(
                    "暂无容器。点击上方\"➕ 创建容器\"创建第一个 Wine 容器。",
                    fontSize = gsp(12.sp), color = themeSec(),
                    modifier = Modifier.padding(vertical = gdp(20.dp))
                )
            }
        }
    }

    // 长按菜单（wineguanli.py show_container_context_menu 同款条目）
    menuFor?.let { c ->
        var langOpen by remember { mutableStateOf(false) }
        GDropdownMenu(expanded = true, onDismiss = { menuFor = null }) {
            GDropdownMenuItem("▶️ 运行容器") { menuFor = null; onRun(c) }
            GDropdownMenuItem("🔧 安装前缀") { menuFor = null; onWineboot(c) }
            GDropdownMenuItem("⚙️ 设置（选中并在右侧编辑）") { menuFor = null; onSelect(c.name) }
            GDropdownMenuItem("🌐 切换语言…") { menuFor = null; langOpen = true }
            GDropdownMenuDivider()
            GDropdownMenuItem("🗑️ 删除") { menuFor = null; onRemove(c) }
            GDropdownMenuDivider()
            GDropdownMenuItem("✏️ 重命名") { menuFor = null; onRename(c) }
            GDropdownMenuItem("📄 创建快捷方式") { menuFor = null; onShortcut(c) }
        }
        if (langOpen) {
            GDropdownMenu(expanded = true, onDismiss = { langOpen = false; menuFor = null }) {
                ContainerLangs.CHOICES.zip(ContainerLangs.DISPLAY).forEach { (code, label) ->
                    GDropdownMenuItem(label) {
                        langOpen = false; menuFor = null
                        onSwitchLang(c, code)
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 右侧配置面板（wineguanli.py create_right_panel：标题 + 5 选项卡 + 底部按钮）
// ===========================================================================

@Composable
private fun RightConfigPanel(
    container: ContainerData,
    wines: List<WineInstall>,
    running: Boolean,
    modifier: Modifier = Modifier,
    onSaved: () -> Unit,
    onRun: () -> Unit,
    onWineboot: () -> Unit,
    onStop: () -> Unit,
    onShowLog: () -> Unit,
    onPrefixLog: () -> Unit,
    onMore: () -> Unit,
    onMango: () -> Unit,
    onSetDefault: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    // 工作副本（切换容器时随 key 重建）
    var edit by remember(container.name) { mutableStateOf(container) }
    var tab by remember(container.name) { mutableStateOf(0) }
    var saveErr by remember { mutableStateOf<String?>(null) }
    val dirty = edit != container

    Column(
        modifier
            .fillMaxHeight()
            .padding(end = gdp(10.dp), top = gdp(10.dp), bottom = gdp(10.dp))
    ) {
        // ---- 标题行（wineguanli container_title：容器名 + 运行中/默认角标） ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (edit.name.isBlank()) "未选择容器" else edit.name,
                fontSize = gsp(20.sp), fontWeight = FontWeight.Bold, color = themeFg(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (running) {
                Spacer(Modifier.width(gdp(8.dp)))
                Text("● 运行中", fontSize = gsp(11.sp), color = Color(0xFF4CAF50))
            }
            if (ContainerManager.isDefault(container.name)) {
                Spacer(Modifier.width(gdp(6.dp)))
                Text("★ 默认", fontSize = gsp(11.sp), color = GuanliColors.accent)
            }
            if (running) {
                Spacer(Modifier.width(gdp(8.dp)))
                GSmallButton("■ 停止", GuanliColors.red, Modifier) { onStop() }
            }
            Spacer(Modifier.weight(1f))
            GSmallButton("⧉ 设为默认", GuanliColors.grey, Modifier) { onSetDefault() }
            GSmallButton("📋 会话日志", GuanliColors.grey, Modifier) { onShowLog() }
            GSmallButton("🧱 前缀日志", GuanliColors.grey, Modifier) { onPrefixLog() }
        }
        if (dirty) {
            Text(
                "● 有未保存的更改（点底部\"保存配置\"生效）",
                fontSize = gsp(10.sp), color = GuanliColors.red
            )
        }
        if (saveErr != null) {
            Text(saveErr ?: "", fontSize = gsp(11.sp), color = GuanliColors.red)
        }

        Spacer(Modifier.height(gdp(6.dp)))

        // ---- 五选项卡（wineguanli.py 顺序与文案） ----
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = gdp(0.dp),
            containerColor = themeCard(),
            contentColor = themeFg()
        ) {
            listOf("📋 基本设置", "🎮 图形驱动", "🖥️ CPU核心", "📚 函数库", "🔧 更多配置")
                .forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = gsp(12.sp), maxLines = 1) }
                    )
                }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(top = gdp(10.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(10.dp))
        ) {
            when (tab) {
                // ================= 0 📋 基本设置（create_basic_tab） =================
                0 -> BasicSettingsTab(
                    edit = edit,
                    wines = wines,
                    onChange = { edit = it }
                )
                // ================= 1 🎮 图形驱动（create_graphics_tab） =================
                1 -> GraphicsDriverTab(
                    edit = edit,
                    onChange = { edit = it }
                )
                // ================= 2 🖥️ CPU核心（create_cpu_tab_for_container） =================
                2 -> CpuCoresTab(
                    edit = edit,
                    onChange = { edit = it }
                )
                // ================= 3 📚 函数库（create_library_tab_for_container） =================
                3 -> LibraryTab(
                    edit = edit,
                    onChange = { edit = it },
                    onApplyNow = {
                        cs.launch(Dispatchers.IO) {
                            val err = com.anwind.apps.containers.launcher.WineLibrary.applyNow(edit)
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    context, err ?: "函数库设置已应用到注册表",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                )
                // ================= 4 🔧 更多配置（create_advanced_tab） =================
                4 -> MoreConfigTab(edit = edit, onOpenMore = onMore, onOpenMango = onMango)
            }
            Spacer(Modifier.height(gdp(12.dp)))
        }

        // ---- 底部按钮行（wineguanli.py：▶️运行 / 🔧安装前缀 / 💾保存配置） ----
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gdp(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GSmallButton("▶️ 运行", GuanliColors.green, Modifier) { onRun() }
            GSmallButton("🔧 安装前缀", GuanliColors.accent, Modifier) { onWineboot() }
            GSmallButton(
                "💾 保存配置", GuanliColors.accent, Modifier,
                enabled = dirty
            ) {
                cs.launch(Dispatchers.IO) {
                    val err = ContainerManager.update(edit)
                    withContext(Dispatchers.Main) {
                        if (err == null) {
                            saveErr = null
                            android.widget.Toast.makeText(
                                context, "配置已保存", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        } else {
                            saveErr = err
                        }
                    }
                }
            }
            Spacer(Modifier.width(gdp(4.dp)))
            Text(
                "wineguanli.py 同款布局 · AnWind 原生启动链",
                fontSize = gsp(10.sp), color = themeSec()
            )
        }
    }
}

// ===========================================================================
// 选项卡 0：基本设置（wineguanli.py create_basic_tab 逐组移植）
// ===========================================================================

@Composable
private fun BasicSettingsTab(
    edit: ContainerData,
    wines: List<WineInstall>,
    onChange: (ContainerData) -> Unit
) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }

    // wineguanli.py：QFormLayout 基本配置组
    GGroup("基本配置") {
        GField("容器名称:") {
            Text(edit.name, fontSize = gsp(14.sp), fontWeight = FontWeight.Bold, color = themeFg())
        }
        GField("🏗️ 架构:") {
            GComboBox(
                options = listOf("x86_64", "arm64ec"),
                selected = if (edit.backend == ContainerBackend.ARM64EC) "arm64ec" else "x86_64",
                onSelect = { arch ->
                    onChange(
                        edit.copy(
                            backend = if (arch == "arm64ec") ContainerBackend.ARM64EC
                            else ContainerBackend.BOX64
                        )
                    )
                }
            )
        }
        GField("🍷 Wine版本:") {
            GComboBox(
                options = listOf("（默认）") + wines.map { it.displayLabel },
                selected = if (edit.wine.isBlank()) "（默认）" else wines.firstOrNull { it.name == edit.wine }?.displayLabel ?: edit.wine,
                onSelect = { label ->
                    val slot = wines.firstOrNull { it.displayLabel == label }?.name ?: ""
                    onChange(edit.copy(wine = if (label == "（默认）") "" else slot))
                }
            )
        }
        GField("🖥️ 分辨率:") {
            // wineguanli：可编辑下拉（全屏 + 8 档 + 自定义输入）
            val resOptions = listOf("全屏", "800x600", "1024x768", "1280x720", "1366x768", "1600x900", "1920x1080", "2560x1440")
            val cur = if (edit.screen == "native") "全屏" else edit.screen
            GComboBox(
                options = resOptions,
                selected = if (cur in resOptions) cur else resOptions.first(),
                onSelect = { v ->
                    applyResolution(edit, v, onChange, edit.flagSet.contains(ContainerFlags.INDEPENDENT))
                },
                customLabel = if (cur !in resOptions) cur else null
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cur,
                    onValueChange = { v ->
                        applyResolution(edit, v, onChange, edit.flagSet.contains(ContainerFlags.INDEPENDENT))
                    },
                    label = { Text("自定义（如 1280x1024）", fontSize = gsp(10.sp)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        GField("🌐 语言:") {
            GComboBox(
                options = ContainerLangs.DISPLAY,
                selected = ContainerLangs.CHOICES.zip(ContainerLangs.DISPLAY)
                    .firstOrNull { it.first == edit.lang }?.second ?: ContainerLangs.DISPLAY.first(),
                onSelect = { label ->
                    val idx = ContainerLangs.DISPLAY.indexOf(label)
                    if (idx >= 0) onChange(edit.copy(lang = ContainerLangs.CHOICES[idx]))
                }
            )
        }
        GField("⏱️ 帧数限制:") {
            GComboBox(
                options = listOf("默认(不限制)", "60", "120"),
                selected = when (edit.frame) { "60" -> "60"; "120" -> "120"; else -> "默认(不限制)" },
                onSelect = { v ->
                    onChange(edit.copy(frame = when (v) { "60" -> "60"; "120" -> "120"; else -> "0" }))
                }
            )
        }
    }

    // wineguanli.py：窗口模式组
    GGroup("窗口模式") {
        val independent = edit.flagSet.contains(ContainerFlags.INDEPENDENT)
        GCheckRow("🪟 独立窗口模式 (不支持proton)", independent) { on ->
            val flags = edit.flagSet.toMutableSet()
            if (on) flags.add(ContainerFlags.INDEPENDENT) else flags.remove(ContainerFlags.INDEPENDENT)
            onChange(edit.copy(flags = ContainerFlags.serialize(flags)))
            applyResolution(edit, if (edit.screen == "native") "全屏" else edit.screen, onChange, on)
        }
        GNote("💡 独立窗口模式: 使用 -f 参数；非独立窗口使用 -d 参数")
    }

    // wineguanli.py：EXE 文件组（留空则运行时再选）
    GGroup("EXE文件 (留空则运行时选择)") {
        DisposableEffect(edit.name) {
            val unregister = com.anwind.apps.filemanager.FilePickBus.listen(
                targetApp = "containers_basic_${edit.name}",
                targetWindow = ""
            ) { path ->
                onChange(edit.copy(exe = path))
                picking = false
            }
            onDispose { unregister() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = edit.exe,
                onValueChange = { onChange(edit.copy(exe = it)) },
                placeholder = { Text("选择要运行的EXE文件，留空则运行时选择…", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(gdp(8.dp)))
            GSmallButton("📂 浏览", GuanliColors.blueFab, Modifier) {
                picking = true
                com.anwind.core.window.WindowManager.get().open(
                    appId = "file_explorer",
                    title = "选择EXE",
                    launchMode = com.anwind.core.window.LaunchMode.FLOATING,
                    launchArgs = mapOf(
                        "pickMode" to "exe",
                        "targetApp" to "containers_basic_${edit.name}",
                        "targetWindow" to ""
                    )
                )
            }
        }
        if (picking) {
            GNote("已在文件资源管理器中选择…（点击 exe 文件自动回填）")
        }
    }

    // wineguanli.py：高级启动选项组（7 勾选，Qt 隐藏 IB）
    GGroup("高级启动选项") {
        GCheckFlow(
            items = listOf(
                ContainerFlags.CLOSESERVICE to "关闭service服务",
                ContainerFlags.DEBUG to "强制DX11启动",
                ContainerFlags.OPENGL to "OpenGL驱动",
                ContainerFlags.FEXMODE to "FEX模式(仅arm64ec)",
                ContainerFlags.F5TASK to "F5任务管理器",
                ContainerFlags.UNITY to "Unity单线程渲染"
            ),
            checked = { it in edit.flagSet },
            onToggle = { flag, on ->
                val flags = edit.flagSet.toMutableSet()
                if (on) flags.add(flag) else flags.remove(flag)
                onChange(edit.copy(flags = ContainerFlags.serialize(flags)))
            }
        )
        GNote(
            "Unity单线程渲染 → BOX64_UNITYPLAYER=1；FEX模式 → 后端 fexcore；" +
                "其余写入 container.conf（flags=），供 /opt 生态脚本消费"
        )
    }
}

/**
 * wineguanli.py 的分辨率/窗口模式 → AnWind screen/dmode 联动：
 *  独立窗口 + 全屏   → screen=native, dmode=F（-f）
 *  独立窗口 + WxH    → screen=WxH,   dmode=F（-f WxH）
 *  非独立 + WxH      → screen=WxH,   dmode=D（-d WxH）
 *  非独立 + 全屏     → screen=native, dmode=OFF
 */
private fun applyResolution(
    edit: ContainerData,
    resLabel: String,
    onChange: (ContainerData) -> Unit,
    independent: Boolean
) {
    val screen = if (resLabel == "全屏") "native" else resLabel
    val dmode = when {
        independent -> DisplayMode.F
        screen == "native" -> DisplayMode.OFF
        else -> DisplayMode.D
    }
    onChange(edit.copy(screen = screen, dmode = dmode))
}

// ===========================================================================
// 选项卡 1：图形驱动（wineguanli.py create_graphics_tab：四个 /opt 下拉）
// ===========================================================================

@Composable
private fun GraphicsDriverTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit
) {
    // wineguanli.py load_driver_versions：默认/不使用 + 目录内 tar 包
    var tzTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var zinkTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var dxvkTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var wined3dTars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            tzTars = GuanliSupport.scanOpt("tz")
            zinkTars = GuanliSupport.scanOpt("zink")
            dxvkTars = GuanliSupport.scanOpt("dxvk1")
            wined3dTars = GuanliSupport.scanOpt("vkord3d")
        }
    }

    val dirNote = "目录内未发现 tar 包。把驱动 tar.gz 放进 rootfs/opt/<目录> 后重进本页即可选择"

    GGroup("Turnip 驱动") {
        DriverCombo(
            tars = tzTars, value = edit.turnip,
            dirNote = dirNote,
            onSelect = { onChange(edit.copy(turnip = it)) }
        )
    }
    GGroup("Zink 驱动") {
        DriverCombo(
            tars = zinkTars, value = edit.zink,
            dirNote = dirNote,
            onSelect = { onChange(edit.copy(zink = it)) }
        )
    }
    GGroup("DXVK") {
        DriverCombo(
            tars = dxvkTars,
            value = if (edit.dxvk == ContainerData.DXVK_AUTO) "" else if (edit.dxvk == ContainerData.DXVK_OFF) "off" else edit.dxvk,
            dirNote = "默认=APK 内置资产自动注入；不使用=关闭注入；tar 槽位由 /opt 生态脚本消费（rootfs/opt/dxvk1）",
            onSelect = { v ->
                onChange(
                    edit.copy(
                        dxvk = when (v) {
                            "" -> ContainerData.DXVK_AUTO
                            "off" -> ContainerData.DXVK_OFF
                            else -> v
                        }
                    )
                )
            }
        )
    }
    GGroup("WineD3D") {
        DriverCombo(
            tars = wined3dTars, value = edit.wined3d,
            dirNote = dirNote,
            onSelect = { onChange(edit.copy(wined3d = it)) }
        )
    }
    GNote(
        "wineguanli.py 图形驱动页同款：Turnip(/opt/tz)、Zink(/opt/zink)、DXVK(/opt/dxvk1)、" +
            "WineD3D(/opt/vkord3d)。运行容器时选中的包自动解压进 rootfs /usr/local"
    )
}

/** 驱动下拉：默认 / 不使用 + /opt 内 tar 包列表（wineguanli load_driver_versions）。 */
@Composable
private fun DriverCombo(
    tars: List<GuanliSupport.OptTar>,
    value: String,
    dirNote: String,
    onSelect: (String) -> Unit
) {
    val options = buildList {
        add("默认" to "")
        add("不使用" to "off")
        tars.forEach { add(it.display to it.display) }
    }
    val selectedLabel = options.firstOrNull { it.second == value }?.first ?: "默认"
    GComboBox(
        options = options.map { it.first },
        selected = selectedLabel,
        onSelect = { label -> options.firstOrNull { it.first == label }?.second?.let(onSelect) }
    )
    if (tars.isEmpty()) {
        GNote(dirNote)
    }
}

// ===========================================================================
// 选项卡 2：CPU 核心（wineguanli.py create_cpu_tab_for_container）
// ===========================================================================

@Composable
private fun CpuCoresTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit
) {
    val total = Runtime.getRuntime().availableProcessors()
    val primary = WineEnvironment.parseCoreSpec(edit.cores)
        .filter { it in 0 until total }.toSortedSet()
    val secondary = (0 until total).filter { it !in primary }

    GGroup("CPU核心分配（勾选为主核心）") {
        GCheckFlow(
            items = (0 until total).map { it.toString() to "核心$it" },
            checked = { it.toIntOrNull() in primary },
            onToggle = { name, on ->
                val idx = name.toIntOrNull() ?: return@GCheckFlow
                val s = primary.toMutableSet()
                if (on) s.add(idx) else s.remove(idx)
                onChange(edit.copy(cores = WineEnvironment.formatCoreSpec(s.sorted())))
            }
        )
        Spacer(Modifier.height(gdp(4.dp)))
        // wineguanli.py：核心配置信息组（主核心/副核心）
        Text(
            "主核心: ${WineEnvironment.formatCoreSpec(primary.toList()).ifEmpty { "无" }}\n" +
                "副核心: ${WineEnvironment.formatCoreSpec(secondary).ifEmpty { "无" }}",
            fontSize = gsp(12.sp), color = themeFg(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(gdp(5.dp)))
                .background(GuanliColors.rowAlt)
                .padding(gdp(10.dp))
        )
    }
    GGroup("说明") {
        GNote(
            "• 勾选的核心将分配给Wine/Windows应用程序使用\n" +
                "• 未勾选的核心分配给系统和后台任务使用\n" +
                "• 配置保存到容器（cores=），启动链以 PRIMARY_CORES/SECONDARY_CORES 注入\n" +
                "• 设备共 $total 核心（wineguanli.py 固定 8 核视图，这里展示真实核心数）"
        )
    }
}

// ===========================================================================
// 选项卡 3：函数库（wineguanli.py create_library_tab_for_container）
// ===========================================================================

@Composable
private fun LibraryTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit,
    onApplyNow: () -> Unit
) {
    GGroup("DLL 加载序（由 /opt/prefix 安装包决定）") {
        GNote(
            "v3.2.27 起 App 不再写任何 DllOverrides —— DLL 加载序完全由\n" +
                "rootfs/opt/prefix/ 安装包的 user.reg / system.reg 决定，\n" +
                "与 startwine.sh 参考链路一致（避免内置覆盖表引发 media 错误）。\n" +
                "如需自定义，请在容器 env= 里加 WINEDLLOVERRIDES 或重导 /opt/prefix 注册表"
        )
    }
    GGroup("DPI设置 (屏幕缩放)") {
        val dpi = edit.dpi.toIntOrNull() ?: 96
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DPI值:", fontSize = gsp(13.sp), color = themeFg())
            Spacer(Modifier.width(gdp(8.dp)))
            Text(
                "$dpi (${dpi * 100 / 96}%)",
                fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = GuanliColors.blueFab
            )
        }
        Slider(
            value = dpi.toFloat(),
            onValueChange = { onChange(edit.copy(dpi = it.toInt().coerceIn(60, 240).toString())) },
            valueRange = 60f..240f,
            steps = (240 - 60) / 20 - 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
            listOf("72 (75%)" to 72, "96 (100%)" to 96, "120 (125%)" to 120, "144 (150%)" to 144, "192 (200%)" to 192)
                .forEach { (label, v) ->
                    GSmallButton(label, Color(0xFFF0F0F0), Modifier, fg = themeFg()) {
                        onChange(edit.copy(dpi = v.toString()))
                    }
                }
        }
    }
    GGroup("Windows版本切换") {
        GComboBox(
            options = WindowsVersions.ALL.map { it.label },
            selected = WindowsVersions.fromId(edit.winver).label,
            onSelect = { label ->
                WindowsVersions.ALL.firstOrNull { it.label == label }?.let {
                    onChange(edit.copy(winver = it.id))
                }
            }
        )
    }
    GGroup("说明") {
        GNote(
            "1. DLL 加载序（dsound/quartz 等）不再由 App 写入注册表 ——\n" +
                "   完全按 rootfs/opt/prefix/ 安装包的注册表执行（v3.2.27）\n" +
                "2. DPI设置控制Windows应用程序的显示缩放比例\n" +
                "3. Windows版本设置会修改system.reg中的Windows版本信息\n" +
                "4. 配置保存到容器（dpi/winver），启动链自动写入注册表"
        )
    }
    // wineguanli.py：💾 立即应用设置
    GSmallButton("💾 立即应用设置", GuanliColors.greenDeep, Modifier.fillMaxWidth()) { onApplyNow() }
    GNote("立即应用 = 保存后当场写入前缀注册表（前缀未构建时由启动链自动补）")
}

// ===========================================================================
// 选项卡 4：更多配置（wineguanli.py create_advanced_tab：说明 + 打开按钮 + 状态）
// ===========================================================================

@Composable
private fun MoreConfigTab(
    edit: ContainerData,
    onOpenMore: () -> Unit,
    onOpenMango: () -> Unit
) {
    var statusTick by remember { mutableIntStateOf(0) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "更多设置说明：\n\n点击下方按钮打开完整的更多设置对话框\n包含：CPU核心分配、函数库设置、DPI设置、\nWindows版本切换、环境变量配置等功能",
            fontSize = gsp(12.sp), color = themeFg(), lineHeight = gsp(18.sp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(gdp(5.dp)))
                .background(GuanliColors.rowAlt)
                .border(gdp(0.5.dp), themeSec().copy(alpha = 0.2f), RoundedCornerShape(gdp(5.dp)))
                .padding(gdp(16.dp))
        )
        Spacer(Modifier.height(gdp(10.dp)))
        GSmallButton("⚙️ 打开更多设置", GuanliColors.accent, Modifier.fillMaxWidth().height(gdp(48.dp))) {
            onOpenMore()
        }
        Spacer(Modifier.height(gdp(12.dp)))
    }
    // 当前配置状态（wineguanli.py refresh_advanced_config_status）
    GGroup("当前配置状态") {
        val envList = GuanliSupport.parseEnv(edit.env)
        Text(
            buildString {
                appendLine("架构: ${edit.backend.label}　Wine: ${edit.wine.ifEmpty { "默认" }}")
                appendLine("分辨率: ${if (edit.screen == "native") "全屏" else edit.screen}（${edit.dmode.label}）")
                appendLine("CPU主核心: ${edit.cores.ifEmpty { "全部（不隔离）" }}")
                appendLine("DPI=${edit.dpi}（dsound/quartz 加载序随 /opt/prefix 安装包）")
                appendLine("Windows: ${WindowsVersions.fromId(edit.winver).label}")
                appendLine("MANGOHUD: ${edit.mangohud.ifEmpty { "未配置" }}")
                appendLine("env 变量数: ${envList.size}" + if (envList.isEmpty()) "" else "（${envList.joinToString(", ") { it.first }}）")
            },
            fontSize = gsp(11.sp), color = themeSec(), lineHeight = gsp(16.sp)
        )
        if (edit.mangohud.isBlank()) {
            Spacer(Modifier.height(gdp(4.dp)))
            GSmallButton("📊 配置 MANGOHUD", GuanliColors.blueFab, Modifier) { onOpenMango() }
        }
        Spacer(Modifier.height(gdp(4.dp)))
        GSmallButton("🔄 刷新配置状态", GuanliColors.grey, Modifier) { statusTick++ }
    }
}

// ===========================================================================
// 高级设置页（wineguanli.py create_advanced_widget：4 选项卡）
// ⚡ DY配置 / 📦 Box配置 / 🍷 Wine安装 / 📊 MANGOHUD配置
// ===========================================================================

@Composable
private fun GuanliAdvancedPage(
    containers: List<ContainerData>,
    sel: ContainerData?,
    onRefresh: () -> Unit,
    onMango: (ContainerData) -> Unit,
    multiSelect: (MultiSelectRequest) -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // 页头：生效容器选择（wineguanli 写全局 /opt/conf；AnWind 落到容器）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(12.dp), vertical = gdp(6.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "生效容器:", fontSize = gsp(13.sp), color = themeFg(),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(gdp(8.dp)))
            if (containers.isEmpty()) {
                Text("（无容器）", fontSize = gsp(13.sp), color = GuanliColors.info)
            } else {
                GComboBox(
                    options = containers.map { it.name },
                    selected = sel?.name ?: containers.first().name,
                    onSelect = { }
                )
                Spacer(Modifier.width(gdp(10.dp)))
                Text(
                    "wineguanli.py 写全局 /opt/conf/*.conf；AnWind 原生链路把同等配置落到所选容器（container.conf），启动时注入",
                    fontSize = gsp(10.sp), color = themeSec()
                )
            }
        }
        if (sel == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("先创建一个容器再使用高级设置", fontSize = gsp(14.sp), color = GuanliColors.info)
            }
            return@Column
        }

        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = gdp(0.dp),
            containerColor = themeCard(),
            contentColor = themeFg()
        ) {
            listOf("⚡ DY配置", "📦 Box配置", "🍷 Wine安装", "📊 MANGOHUD配置")
                .forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = gsp(12.sp), maxLines = 1) }
                    )
                }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(gdp(12.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(10.dp))
        ) {
            when (tab) {
                0 -> DyConfigTab(sel = sel, onSaved = onRefresh)
                1 -> BoxConfigTab()
                2 -> WineInstallTab(onRefresh = onRefresh)
                3 -> MangoHudTab(
                    sel = sel,
                    onMango = onMango,
                    onSetMango = { c, cfg ->
                        cs.launch(Dispatchers.IO) {
                            ContainerManager.update(c.copy(mangohud = cfg))
                            withContext(Dispatchers.Main) { onRefresh() }
                        }
                    }
                )
            }
        }
    }
}

// ===========================================================================
// ⚡ DY 配置（wineguanli.py DynarecEditor 完整移植）
// ===========================================================================

@Composable
private fun DyConfigTab(
    sel: ContainerData,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    // 变量列表 = 容器 env= 的 K=V 视图（工作副本，应用时落盘）
    var pairs by remember(sel.name, sel.env) {
        mutableStateOf(GuanliSupport.parseEnv(sel.env).toMutableList())
    }
    var presetSel by remember { mutableStateOf("自定义") }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var delIdx by remember { mutableStateOf(-1) }

    // wineguanli.py：预设配置组
    GGroup("预设配置（容器：${sel.name}）") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("预设:", fontSize = gsp(13.sp), color = themeFg())
            Spacer(Modifier.width(gdp(8.dp)))
            GComboBox(
                options = listOf("自定义") + GuanliSupport.DY_PRESETS.keys.toList(),
                selected = presetSel,
                onSelect = { name ->
                    presetSel = name
                    // wineguanli.py apply_preset：预设值整体替换 BOX64_* 变量
                    GuanliSupport.DY_PRESETS[name]?.let { preset ->
                        val others = pairs.filter { !it.first.startsWith("BOX64_") }
                        pairs = (others + preset.entries.map { it.key to it.value }).toMutableList()
                    }
                }
            )
            Spacer(Modifier.weight(1f))
            GSmallButton("重置默认", GuanliColors.grey, Modifier) {
                pairs = GuanliSupport.parseEnv(sel.env).toMutableList()
                presetSel = "自定义"
            }
        }
        GNote("预设值来自 wineguanli.py DynarecEditor（兼容/性能/平衡各 9 项 BOX64_DYNAREC_* 变量）")
    }

    // wineguanli.py：变量列表（QListWidget 行）
    GGroup("变量列表（env=）") {
        if (pairs.isEmpty()) {
            Text("（空）—— 未配置任何环境变量", fontSize = gsp(12.sp), color = GuanliColors.info)
        }
        pairs.forEachIndexed { i, (k, v) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(gdp(4.dp)))
                    .background(if (i % 2 == 0) GuanliColors.rowAlt else themeCard())
                    .border(
                        gdp(0.5.dp),
                        if (delIdx == i) GuanliColors.redFab else themeSec().copy(alpha = 0.15f),
                        RoundedCornerShape(gdp(4.dp))
                    )
                    .clickable { delIdx = if (delIdx == i) -1 else i }
                    .padding(horizontal = gdp(10.dp), vertical = gdp(6.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(k, fontSize = gsp(12.sp), fontWeight = FontWeight.Bold, color = themeFg())
                Text(" = ", fontSize = gsp(12.sp), color = themeSec())
                Text(v, fontSize = gsp(12.sp), color = themeFg(), modifier = Modifier.weight(1f))
                if (delIdx == i) {
                    GSmallButton("删除变量", GuanliColors.redFab, Modifier) {
                        pairs = pairs.toMutableList().apply { removeAt(i) }
                        delIdx = -1
                        presetSel = "自定义"
                    }
                }
            }
        }
        Spacer(Modifier.height(gdp(6.dp)))
        // wineguanli.py：添加变量
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = { Text("变量名", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = { Text("值", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.weight(1f)
            )
            GSmallButton("添加变量", Color(0xFF4CAF50), Modifier) {
                val k = newKey.trim().uppercase()
                if (k.isNotEmpty() && newValue.isNotBlank()) {
                    pairs = pairs.toMutableList().apply {
                        val idx = indexOfFirst { it.first == k }
                        if (idx >= 0) this[idx] = k to newValue.trim() else add(k to newValue.trim())
                    }
                    newKey = ""; newValue = ""
                    presetSel = "自定义"
                }
            }
        }
    }

    // wineguanli.py：应用配置
    GSmallButton("应用配置", GuanliColors.blueFab, Modifier.fillMaxWidth().height(gdp(44.dp))) {
        cs.launch(Dispatchers.IO) {
            val err = ContainerManager.update(sel.copy(env = GuanliSupport.serializeEnv(pairs)))
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context, err ?: "DY 配置已应用到容器 ${sel.name}", android.widget.Toast.LENGTH_SHORT
                ).show()
                if (err == null) onSaved()
            }
        }
    }
    GNote(
        "wineguanli.py 写 /opt/conf/box64.conf（全局）；AnWind 把同等变量写进容器 env=，" +
            "启动链 applyExtraEnv 最后叠加 —— 优先级高于一切预设，可逐容器差异化"
    )
}

// ===========================================================================
// 📦 Box 配置（wineguanli.py create_box_tab：版本下拉 + 当前信息 + 应用）
// ===========================================================================

@Composable
private fun BoxConfigTab() {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var box64Tars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var box86Tars by remember { mutableStateOf<List<GuanliSupport.OptTar>>(emptyList()) }
    var box64Sel by remember { mutableStateOf("") }
    var box86Sel by remember { mutableStateOf("") }
    var box64Info by remember { mutableStateOf("Box64: 检测中…") }
    var box86Info by remember { mutableStateOf("Box86: 未检测到") }

    fun refreshInfo() {
        cs.launch(Dispatchers.IO) {
            val b64 = GuanliSupport.boxVersion("box64")
            val b86 = GuanliSupport.boxVersion("box86")
            withContext(Dispatchers.Main) { box64Info = b64; box86Info = b86 }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            box64Tars = GuanliSupport.scanOpt("box")
            box86Tars = GuanliSupport.scanOpt("box86")
        }
        refreshInfo()
    }

    GGroup("Box64 版本") {
        GComboBox(
            options = listOf("默认") + box64Tars.map { it.display },
            selected = box64Sel.ifEmpty { "默认" },
            onSelect = { box64Sel = if (it == "默认") "" else it }
        )
        if (box64Tars.isEmpty()) GNote("rootfs/opt/box 内未发现 tar 包")
    }
    GGroup("Box86 版本") {
        GComboBox(
            options = listOf("默认") + box86Tars.map { it.display },
            selected = box86Sel.ifEmpty { "默认" },
            onSelect = { box86Sel = if (it == "默认") "" else it }
        )
        GNote("aarch64 设备一般用不到 box86（wineguanli.py 同款入口，无包时无操作）")
    }
    GGroup("当前Box信息") {
        Text(box64Info, fontSize = gsp(12.sp), color = themeFg())
        Text(box86Info, fontSize = gsp(12.sp), color = themeFg())
        Spacer(Modifier.height(gdp(4.dp)))
        GSmallButton("🔄 刷新信息", GuanliColors.grey, Modifier) { refreshInfo() }
    }
    GSmallButton("✅ 应用Box配置", GuanliColors.greenDeep, Modifier.fillMaxWidth()) {
        cs.launch(Dispatchers.IO) {
            val errs = mutableListOf<String>()
            listOf("box" to box64Sel, "box86" to box86Sel).forEach { (dir, sel) ->
                if (sel.isNotBlank()) {
                    GuanliSupport.scanOpt(dir).firstOrNull { it.display == sel }?.let { tar ->
                        GuanliSupport.extractIntoRootfsLocal(tar.path)?.let { errs.add(it) }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (errs.isEmpty()) {
                    android.widget.Toast.makeText(
                        context, "✅ Box配置已应用（解压到 rootfs/usr/local）",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    refreshInfo()
                } else {
                    android.widget.Toast.makeText(
                        context, errs.joinToString("\n"), android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}

// ===========================================================================
// 🍷 Wine 安装（wineguanli.py create_wine_install_tab：本地/URL + 版本管理）
// ===========================================================================

@Composable
private fun WineInstallTab(onRefresh: () -> Unit) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    var arch by remember { mutableStateOf("x86_64") }
    var fromUrl by remember { mutableStateOf(false) }
    var filePath by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var slotName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wines by remember { mutableStateOf<List<WineInstall>>(emptyList()) }
    var winesTick by remember { mutableIntStateOf(0) }
    var selSlot by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }

    fun reload() {
        cs.launch(Dispatchers.IO) {
            val w = ContainerManager.listWines()
            withContext(Dispatchers.Main) { wines = w }
        }
    }
    LaunchedEffect(winesTick) { reload() }

    fun doInstall(srcPath: String) {
        busy = true
        cs.launch(Dispatchers.IO) {
            val err = GuanliSupport.WineOps.installFromTar(srcPath, slotName, arch)
            withContext(Dispatchers.Main) {
                busy = false
                android.widget.Toast.makeText(
                    context, err ?: "✅ Wine 安装完成（/usr/opt）", android.widget.Toast.LENGTH_LONG
                ).show()
                if (err == null) { slotName = ""; filePath = ""; url = ""; winesTick++ }
            }
        }
    }

    GGroup("选择安装架构") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            // v3.2.22：紧凑定宽下拉（选项仅两项，fillMaxWidth 会把同行提示挤没）
            GComboBox(options = listOf("x86_64", "arm64ec"), selected = arch, onSelect = { arch = it }, width = 150.dp)
            Text("(arm64ec 装到 wine-<名> 槽位)", fontSize = gsp(10.sp), color = GuanliColors.info)
        }
    }
    GGroup("选择安装方式") {
        GRadioRow("📁 从本地文件安装", !fromUrl) { fromUrl = false }
        GRadioRow("🌐 从网络链接安装", fromUrl) { fromUrl = true }
    }
    if (!fromUrl) {
        GGroup("选择Wine安装文件") {
            DisposableEffect(Unit) {
                val unregister = com.anwind.apps.filemanager.FilePickBus.listen(
                    targetApp = "containers_wineinstall",
                    targetWindow = ""
                ) { path ->
                    filePath = path
                    picking = false
                }
                onDispose { unregister() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = filePath,
                    onValueChange = { filePath = it },
                    placeholder = { Text("选择或输入文件路径…", fontSize = gsp(10.sp)) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(gdp(8.dp)))
                GSmallButton("📂 浏览…", GuanliColors.blueFab, Modifier) {
                    picking = true
                    com.anwind.core.window.WindowManager.get().open(
                        appId = "file_explorer",
                        title = "选择Wine包",
                        launchMode = com.anwind.core.window.LaunchMode.FLOATING,
                        launchArgs = mapOf(
                            "pickMode" to "text",
                            "targetApp" to "containers_wineinstall",
                            "targetWindow" to ""
                        )
                    )
                }
            }
            GNote("支持: .tar .tar.gz .tar.xz（wine 目录树，bin/wine 在顶层）")
        }
    } else {
        GGroup("输入下载链接") {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("https://example.com/wine.tar.gz", fontSize = gsp(10.sp)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    GGroup("槽位名（可选）") {
        OutlinedTextField(
            value = slotName,
            onValueChange = { slotName = it },
            label = { Text("如 wine-9.20（默认取包名）", fontSize = gsp(10.sp)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
            modifier = Modifier.fillMaxWidth()
        )
    }
    GSmallButton(
        if (busy) "⏳ 安装中…" else "🚀 开始安装Wine",
        GuanliColors.accent, Modifier.fillMaxWidth().height(gdp(44.dp)),
        enabled = !busy
    ) {
        if (!fromUrl && filePath.isNotBlank()) {
            doInstall(filePath)
        } else if (fromUrl && url.isNotBlank()) {
            busy = true
            cs.launch(Dispatchers.IO) {
                try {
                    val f = GuanliSupport.WineOps.download(url) { }
                    withContext(Dispatchers.Main) {
                        doInstall(f.absolutePath)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        busy = false
                        android.widget.Toast.makeText(
                            context, "下载失败：${e.message}", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } else {
            android.widget.Toast.makeText(
                context, if (fromUrl) "请输入下载链接" else "请选择文件", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    GGroup("🍷 已安装的Wine版本（/usr/opt）") {
        if (wines.isEmpty()) {
            Text("（未发现 wine 槽位）", fontSize = gsp(12.sp), color = GuanliColors.info)
        }
        wines.forEach { w ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(gdp(4.dp)))
                    .background(if (selSlot == w.name) GuanliColors.listSel else GuanliColors.rowAlt)
                    .clickable { selSlot = w.name }
                    .padding(horizontal = gdp(10.dp), vertical = gdp(7.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(w.displayLabel, fontSize = gsp(12.sp), color = themeFg(), modifier = Modifier.weight(1f))
                if (ContainerManager.defaultWineName() == w.name) {
                    Text("默认", fontSize = gsp(10.sp), color = GuanliColors.accent)
                }
            }
        }
        Spacer(Modifier.height(gdp(6.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
            GSmallButton("🔄 刷新列表", GuanliColors.grey, Modifier.weight(1f)) { reload() }
            GSmallButton(
                "⭐ 设为默认", GuanliColors.accent, Modifier.weight(1f),
                enabled = selSlot.isNotBlank()
            ) {
                cs.launch(Dispatchers.IO) {
                    val err = GuanliSupport.WineOps.setDefaultWine(selSlot)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context, err ?: "默认 wine 槽位 = $selSlot", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        reload()
                    }
                }
            }
            GSmallButton(
                "🗑️ 删除选中版本", GuanliColors.red, Modifier.weight(1f),
                enabled = selSlot.isNotBlank()
            ) {
                cs.launch(Dispatchers.IO) {
                    val err = GuanliSupport.WineOps.deleteWine(selSlot)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context, err ?: "已删除 $selSlot", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        selSlot = ""
                        reload()
                        onRefresh()
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 📊 MANGOHUD 配置（wineguanli.py create_mangohud_tab）
// ===========================================================================

@Composable
private fun MangoHudTab(
    sel: ContainerData,
    onMango: (ContainerData) -> Unit,
    onSetMango: (ContainerData, String) -> Unit
) {
    GNote(
        "MANGOHUD配置需要在环境变量中设置 MANGOHUD_CONFIG 变量\n" +
            "（AnWind：写入容器 mangohud=，启动时注入 MANGOHUD=1 + MANGOHUD_CONFIG）"
    )
    GGroup("MANGOHUD配置（容器：${sel.name}）") {
        GSmallButton("⚙️ 配置MANGOHUD", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            onMango(sel)
        }
        Spacer(Modifier.height(gdp(6.dp)))
        Text(
            "当前配置: ${sel.mangohud.ifEmpty { "未设置" }}",
            fontSize = gsp(13.sp), color = themeFg(),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(gdp(5.dp)))
                .background(GuanliColors.rowAlt)
                .border(gdp(0.5.dp), themeSec().copy(alpha = 0.2f), RoundedCornerShape(gdp(5.dp)))
                .padding(gdp(10.dp))
        )
        if (sel.mangohud.isNotBlank()) {
            Spacer(Modifier.height(gdp(6.dp)))
            GSmallButton("关闭 MANGOHUD", GuanliColors.grey, Modifier) {
                onSetMango(sel, "")
            }
        }
    }
}

// ===========================================================================
// 更多设置对话框（wineguanli.py AdvancedSettingsDialog：650×950，5 选项卡
// + AnWind 原生扩展页 —— 不丢任何 AnWind 既有能力）
// ===========================================================================

@Composable
private fun MoreSettingsDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var edit by remember(container.name) { mutableStateOf(container) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    // 多选对话框请求（MESA扩展/DXVK HUD/TU_DEBUG）
    var multiSelect by remember { mutableStateOf<MultiSelectRequest?>(null) }
    multiSelect?.let { req ->
        MultiSelectDialog(
            title = req.title,
            options = req.options,
            initial = req.selected,
            onDismiss = { multiSelect = null },
            onOk = {
                req.onOk(it)
                multiSelect = null
            }
        )
    }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "更多设置 · ${container.name}",
        onDismiss = onDismiss,
        width = 620.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(Modifier.fillMaxWidth()) {
            ScrollableTabRow(
                selectedTabIndex = tab,
                edgePadding = gdp(0.dp),
                containerColor = themeCard(),
                contentColor = themeFg()
            ) {
                listOf(
                    "CPU核心", "函数库", "环境变量", "高级功能", "Direct3D", "🧩 AnWind 原生"
                ).forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = gsp(11.sp), maxLines = 1) }
                    )
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
                verticalArrangement = Arrangement.spacedBy(gdp(10.dp))
            ) {
                when (tab) {
                    0 -> {
                        // wineguanli.py create_cpu_tab（与容器 CPU 页同构）
                        GGroup("CPU核心分配（勾选为主核心）") {
                            val total = Runtime.getRuntime().availableProcessors()
                            val primary = WineEnvironment.parseCoreSpec(edit.cores)
                                .filter { it in 0 until total }.toSortedSet()
                            GCheckFlow(
                                items = (0 until total).map { it.toString() to "核心$it" },
                                checked = { it.toIntOrNull() in primary },
                                onToggle = { name, on ->
                                    val idx = name.toIntOrNull() ?: return@GCheckFlow
                                    val s = primary.toMutableSet()
                                    if (on) s.add(idx) else s.remove(idx)
                                    edit = edit.copy(cores = WineEnvironment.formatCoreSpec(s.sorted()))
                                }
                            )
                            Text(
                                "主核心: ${WineEnvironment.formatCoreSpec(primary.toList()).ifEmpty { "无" }}",
                                fontSize = gsp(12.sp), color = themeFg(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(gdp(5.dp)))
                                    .background(GuanliColors.rowAlt)
                                    .padding(gdp(8.dp))
                            )
                        }
                    }
                    1 -> {
                        // wineguanli.py create_library_tab（对话框版，全局语义 → 容器）
                        GGroup("函数库设置") {
                            // v3.2.27：DLL 加载序（dsound/quartz）不再由 App 写注册表
                            GNote(
                                "DLL 加载序完全由 rootfs/opt/prefix/ 安装包注册表决定\n" +
                                    "（v3.2.27 起 App 不写 DllOverrides，防 media 错误）；\n" +
                                    "自定义请用容器 env= 的 WINEDLLOVERRIDES"
                            )
                            GField("DPI (LogPixels)") {
                                val dpi = edit.dpi.toIntOrNull() ?: 96
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "$dpi (${dpi * 100 / 96}%)",
                                        fontSize = gsp(13.sp), fontWeight = FontWeight.Bold,
                                        color = GuanliColors.blueFab
                                    )
                                    Spacer(Modifier.weight(1f))
                                }
                                Slider(
                                    value = dpi.toFloat(),
                                    onValueChange = {
                                        edit = edit.copy(dpi = it.toInt().coerceIn(60, 240).toString())
                                    },
                                    valueRange = 60f..240f,
                                    steps = (240 - 60) / 20 - 1
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
                                    listOf(72, 96, 120, 144, 192).forEach { v ->
                                        GSmallButton("$v", Color(0xFFF0F0F0), Modifier, fg = themeFg()) {
                                            edit = edit.copy(dpi = v.toString())
                                        }
                                    }
                                }
                            }
                            GField("Windows 版本") {
                                GComboBox(
                                    options = WindowsVersions.ALL.map { it.label },
                                    selected = WindowsVersions.fromId(edit.winver).label,
                                    onSelect = { label ->
                                        WindowsVersions.ALL.firstOrNull { it.label == label }
                                            ?.let { edit = edit.copy(winver = it.id) }
                                    }
                                )
                            }
                            GSmallButton("💾 立即应用设置", GuanliColors.greenDeep, Modifier.fillMaxWidth()) {
                                cs.launch(Dispatchers.IO) {
                                    ContainerManager.update(edit)
                                    val err = com.anwind.apps.containers.launcher.WineLibrary.applyNow(edit)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            context, err ?: "函数库设置已应用到注册表",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                    2 -> EnvVarsTab(edit = edit, onChange = { edit = it }, openMulti = { multiSelect = it })
                    3 -> AdvancedFuncTab(container = edit)
                    4 -> D3dTab(container = edit)
                    5 -> NativeTab(edit = edit, onChange = { edit = it }, onSaved = onSaved)
                }
                Spacer(Modifier.height(gdp(8.dp)))
            }
            if (saveMsg != null) {
                Text(
                    saveMsg ?: "", fontSize = gsp(11.sp), color = GuanliColors.red,
                    modifier = Modifier.padding(horizontal = gdp(14.dp))
                )
            }
            // wineguanli.py：保存设置 / 取消
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(gdp(8.dp)))
                Button(
                    onClick = {
                        cs.launch(Dispatchers.IO) {
                            val err = ContainerManager.update(edit)
                            withContext(Dispatchers.Main) {
                                if (err == null) {
                                    android.widget.Toast.makeText(
                                        context, "设置已保存", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    onSaved()
                                } else {
                                    saveMsg = err
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("保存设置") }
            }
        }
            }
    }
}

// ===========================================================================
// 环境变量选项卡（wineguanli.py create_env_tab：MESA/MANGOHUD/DXVK/TU_DEBUG）
// ===========================================================================

@Composable
private fun EnvVarsTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit,
    openMulti: (MultiSelectRequest) -> Unit
) {
    fun setEnvVar(key: String, value: String?) {
        // null = 移除该变量
        val cur = GuanliSupport.parseEnv(edit.env).toMutableList()
        cur.removeAll { it.first == key }
        if (value != null) cur.add(key to value)
        onChange(edit.copy(env = GuanliSupport.serializeEnv(cur)))
    }

    // ---- MESA配置（wineguanli：MESA版本/GLSL版本/VKD3D特性 三下拉） ----
    GGroup("MESA配置") {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(10.dp))) {
            Column(Modifier.weight(1f)) {
                Text("MESA版本:", fontSize = gsp(11.sp), color = themeFg())
                GComboBox(
                    options = listOf("（默认）", "2.1", "3.1", "3.3", "3.3COMPAT", "4.6", "4.6COMPAT"),
                    selected = GuanliSupport.envValue(edit.env, "MESA_GL_VERSION") ?: "（默认）",
                    onSelect = { v -> setEnvVar("MESA_GL_VERSION", if (v == "（默认）") null else v) }
                )
            }
            Column(Modifier.weight(1f)) {
                Text("GLSL版本:", fontSize = gsp(11.sp), color = themeFg())
                GComboBox(
                    options = listOf("（默认）", "210", "310", "330", "430", "460"),
                    selected = GuanliSupport.envValue(edit.env, "MESA_GLSL_VERSION") ?: "（默认）",
                    onSelect = { v -> setEnvVar("MESA_GLSL_VERSION", if (v == "（默认）") null else v) }
                )
            }
            Column(Modifier.weight(1f)) {
                Text("VKD3D特性:", fontSize = gsp(11.sp), color = themeFg())
                GComboBox(
                    options = listOf("（默认）", "12_1", "12_2"),
                    selected = GuanliSupport.envValue(edit.env, "VKD3D_FEATURE_LEVEL") ?: "（默认）",
                    onSelect = { v -> setEnvVar("VKD3D_FEATURE_LEVEL", if (v == "（默认）") null else v) }
                )
            }
        }
    }
    // ---- MESA扩展配置（wineguanli select_mesa_extensions 三选项多选） ----
    GGroup("MESA扩展配置") {
        val cur = GuanliSupport.envValue(edit.env, "MESA_EXTENSION_OVERRIDE")?.split(' ') ?: emptyList()
        GSmallButton("点击选择MESA扩展", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            openMulti(
                MultiSelectRequest(
                    title = "MESA扩展配置",
                    options = listOf(
                        "-GL_EXT_texture_sRGB_decode",
                        "GL_EXT_polygon_offset_clamp",
                        "-GL_EXT_texture_compression_s3tc"
                    ),
                    selected = cur,
                    onOk = { sel -> setEnvVar("MESA_EXTENSION_OVERRIDE", sel.joinToString(" ").ifEmpty { null }) }
                )
            )
        }
        GNote(cur.joinToString(" ").ifEmpty { "未选择" })
    }
    // ---- MANGOHUD（启用 = mango key 非空；详细配置走 MANGOHUD 对话框） ----
    GGroup("MANGOHUD配置") {
        GCheckRow("启用MANGOHUD", edit.mangohud.isNotBlank()) { on ->
            onChange(edit.copy(mangohud = if (on) "fps,frametime,cpu_stats,gpu_stats" else ""))
        }
        GNote("当前配置: ${edit.mangohud.ifEmpty { "未配置" }}（高级设置页 → MANGOHUD配置 可详细定制）")
    }
    // ---- DXVK HUD（wineguanli select_dxvk_items） ----
    GGroup("DXVK HUD配置") {
        val cur = GuanliSupport.envValue(edit.env, "DXVK_HUD")?.split(',') ?: emptyList()
        GSmallButton("选择DXVK监控项", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            openMulti(
                MultiSelectRequest(
                    title = "DXVK监控项",
                    options = listOf("devinfo", "fps", "gpuload", "api", "version"),
                    selected = cur,
                    onOk = { sel -> setEnvVar("DXVK_HUD", sel.joinToString(",").ifEmpty { null }) }
                )
            )
        }
        GNote(cur.joinToString(",").ifEmpty { "未选择" })
    }
    // ---- TU_DEBUG（wineguanli select_tu_debug） ----
    GGroup("TU_DEBUG配置") {
        val cur = GuanliSupport.envValue(edit.env, "TU_DEBUG")?.split(',') ?: emptyList()
        GSmallButton("选择TU_DEBUG选项", GuanliColors.blueFab, Modifier.fillMaxWidth()) {
            openMulti(
                MultiSelectRequest(
                    title = "TU_DEBUG选项",
                    options = listOf("nofsdt", "gmem", "sysmem", "noconform"),
                    selected = cur,
                    onOk = { sel -> setEnvVar("TU_DEBUG", sel.joinToString(",").ifEmpty { null }) }
                )
            )
        }
        GNote(cur.joinToString(",").ifEmpty { "未选择" })
    }
    GNote("以上全部落入容器 env=（启动链注入；DY 配置页可查看完整变量列表）")
}

// ===========================================================================
// 高级功能选项卡（wineguanli.py create_adv_tab：CNC DDraw）
// ===========================================================================

@Composable
private fun AdvancedFuncTab(container: ContainerData) {
    val context = LocalContext.current
    var exeName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    GGroup("CNC DDraw配置") {
        OutlinedTextField(
            value = exeName,
            onValueChange = { exeName = it },
            label = { Text("目标游戏 exe 文件名（如 game.exe）", fontSize = gsp(10.sp)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            GSmallButton("应用CNC配置", GuanliColors.blueFab, Modifier.weight(1f), enabled = !busy) {
                busy = true
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    val err = GuanliSupport.AppDefaults.cncDdraw(container, exeName.trim())
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        busy = false
                        android.widget.Toast.makeText(
                            context, err ?: "CNC DDraw 配置已写入（ddraw/d3d9/dxgi 原生优先）",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            GSmallButton("清除CNC配置", GuanliColors.redFab, Modifier.weight(1f), enabled = !busy) {
                busy = true
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                    val err = GuanliSupport.AppDefaults.clear(container, exeName.trim())
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        busy = false
                        android.widget.Toast.makeText(
                            context, err ?: "已清除", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        GNote(
            "说明：\n1. CNC DDraw配置用于改进老游戏的图形渲染\n" +
                "2. 通过 wine AppDefaults 注册表为该 exe 设置 ddraw/d3d9/dxgi 原生优先\n" +
                "3. cnc-ddraw.dll 由用户放入游戏目录（版权原因不随 APK 分发）"
        )
    }
}

// ===========================================================================
// Direct3D 选项卡（wineguanli.py create_d3d_tab：强制 D3D9/D3D11 + 清除）
// ===========================================================================

@Composable
private fun D3dTab(container: ContainerData) {
    val context = LocalContext.current
    var exeName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun run(action: suspend () -> String?) {
        busy = true
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val err = action()
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                busy = false
                android.widget.Toast.makeText(
                    context, err ?: "操作完成", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    GGroup("Direct3D 9配置") {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            GSmallButton("强制D3D9模式", GuanliColors.blueFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.forceD3d(container, exeName.trim()) }
            }
            GSmallButton("清除D3D9配置", GuanliColors.redFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.clear(container, exeName.trim()) }
            }
        }
    }
    GGroup("Direct3D 11配置") {
        Row(horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
            GSmallButton("强制D3D11模式", GuanliColors.blueFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.forceD3d11(container, exeName.trim()) }
            }
            GSmallButton("清除D3D11配置", GuanliColors.redFab, Modifier.weight(1f), enabled = !busy) {
                run { GuanliSupport.AppDefaults.clear(container, exeName.trim()) }
            }
        }
    }
    GGroup("目标EXE文件") {
        OutlinedTextField(
            value = exeName,
            onValueChange = { exeName = it },
            placeholder = { Text("输入EXE文件名以应用配置（如 game.exe）", fontSize = gsp(10.sp)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
            modifier = Modifier.fillMaxWidth()
        )
    }
    GNote(
        "说明：\n1. 强制D3D9模式：将游戏强制使用Direct3D 9渲染\n" +
            "2. 强制D3D11模式：将游戏强制使用Direct3D 11渲染\n" +
            "3. 清除配置：删除对应 exe 的覆盖项\n" +
            "4. 经 wine AppDefaults 注册表实现（等效 wineguanli 的复制 DLL 方案，不动用户文件）"
    )
}

// ===========================================================================
// 🧩 AnWind 原生选项卡（wineguanli 之外 AnWind 特有能力，避免功能回退）
// ===========================================================================

@Composable
private fun NativeTab(
    edit: ContainerData,
    onChange: (ContainerData) -> Unit,
    onSaved: () -> Unit
) {
    GGroup("CPU 翻译后端（AnWind 原生）") {
        GCheckFlow(
            items = ContainerBackend.entries.map { it.id to it.label },
            checked = { it == edit.backend.id },
            onToggle = { id, _ -> onChange(edit.copy(backend = ContainerBackend.fromId(id))) }
        )
        GNote("wineguanli 的架构下拉映射 x86_64→box64 / arm64ec→arm64ec；此处为完整后端集（auto/hangover/fexcore/native）")
    }
    if (edit.backend == ContainerBackend.BOX64 || edit.backend == ContainerBackend.AUTO) {
        GGroup("Box64 预设（AnWind 精简两档）") {
            GCheckFlow(
                items = Box64Preset.entries.map { it.id to it.label },
                checked = { it == edit.preset.id },
                onToggle = { id, _ -> onChange(edit.copy(preset = Box64Preset.fromId(id))) }
            )
            GNote("细粒度 BOX64_DYNAREC_* 请用高级设置 → ⚡DY配置（wineguanli 三预设 + 自定义变量）")
        }
    }
    GGroup("Mesa 渲染器 / DXVK 注入 / 音频（AnWind 原生）") {
        GField("Mesa 渲染器") {
            GCheckFlow(
                items = GalliumDriver.entries.map { it.id to it.label },
                checked = { it == edit.gallium },
                onToggle = { id, _ -> onChange(edit.copy(gallium = id)) }
            )
        }
        GField("DXVK 图形加速（APK 内置资产）") {
            GCheckFlow(
                items = listOf(
                    ContainerData.DXVK_AUTO to "开启",
                    ContainerData.DXVK_OFF to "关闭"
                ),
                checked = { it == if (edit.dxvk == ContainerData.DXVK_OFF) ContainerData.DXVK_OFF else ContainerData.DXVK_AUTO },
                onToggle = { v, _ -> onChange(edit.copy(dxvk = v)) }
            )
        }
        GField("音频") {
            GCheckFlow(
                items = ContainerAudio.entries.map { it.id to it.label },
                checked = { it == edit.audio.id },
                onToggle = { id, _ -> onChange(edit.copy(audio = ContainerAudio.fromId(id))) }
            )
        }
    }
    GNote("保存 = 写入 container.conf（与 wineguanli 字段同一文件；AnWind 启动链接键消费）")
}

// ===========================================================================
// MANGOHUD 配置对话框（wineguanli.py MangohudConfigDialog 完整移植）
// ===========================================================================

@Composable
private fun MangoHudConfigDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val cs = rememberCoroutineScope()

    val (initToggles, initNoDisplay, _) = GuanliSupport.MangoHud.parseConfig(container.mangohud)
    val mFont = Regex("font_size=(\\d+)").find(container.mangohud)?.groupValues?.get(1)?.toIntOrNull() ?: 24
    val mPos = Regex("position=([a-z-]+)").find(container.mangohud)?.groupValues?.get(1) ?: "top-left"
    val mAlpha = Regex("background_alpha=([0-9.]+)").find(container.mangohud)?.groupValues?.get(1)?.toFloatOrNull()
        ?.let { (it * 100).toInt() } ?: 50

    var toggles by remember { mutableStateOf(initToggles) }
    var noDisplay by remember { mutableStateOf(initNoDisplay) }
    var fontSize by remember { mutableStateOf(mFont) }
    var position by remember { mutableStateOf(mPos) }
    var alpha by remember { mutableStateOf(mAlpha) }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "配置 MANGOHUD · ${container.name}",
        onDismiss = onDismiss,
        width = 520.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            // 基本性能指标（wineguanli 八勾选）
            GGroup("基本性能指标") {
                GuanliSupport.MangoHud.TOGGLES.filter { it.first != "horizontal" && it.first != "no_display" }
                    .chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gdp(10.dp))) {
                            rowItems.forEach { (key, label) ->
                                GCheckRow(
                                    label, key in toggles,
                                    Modifier.weight(1f)
                                ) { on ->
                                    toggles = if (on) toggles + key else toggles - key
                                }
                            }
                            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                GCheckRow("横屏显示", "horizontal" in toggles) { on ->
                    toggles = if (on) toggles + "horizontal" else toggles - "horizontal"
                }
                GCheckRow("无显示模式", noDisplay) { noDisplay = it }
            }
            GGroup("布局设置") {
                GField("字体大小（10-36）") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$fontSize", fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = GuanliColors.blueFab)
                        Spacer(Modifier.width(gdp(10.dp)))
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { fontSize = it.toInt().coerceIn(10, 36) },
                            valueRange = 10f..36f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                GField("显示位置") {
                    Row(horizontalArrangement = Arrangement.spacedBy(gdp(6.dp))) {
                        GuanliSupport.MangoHud.POSITIONS.forEach { p ->
                            GSmallButton(
                                p, if (position == p) GuanliColors.blueFab else Color(0xFFF0F0F0),
                                Modifier, fg = if (position == p) Color.White else themeFg()
                            ) { position = p }
                        }
                    }
                }
                GField("背景透明度（0-100）") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$alpha", fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = GuanliColors.blueFab)
                        Spacer(Modifier.width(gdp(10.dp)))
                        Slider(
                            value = alpha.toFloat(),
                            onValueChange = { alpha = it.toInt().coerceIn(0, 100) },
                            valueRange = 0f..100f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    val config = GuanliSupport.MangoHud.buildConfig(toggles, fontSize, position, alpha, noDisplay)
                    cs.launch(Dispatchers.IO) {
                        ContainerManager.update(container.copy(mangohud = config))
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                context, "MANGOHUD配置已保存", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            onSaved()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) { Text("保存") }
        }
            }
    }
}

// ===========================================================================
// 多选对话框（wineguanli.py MultiSelectDialog）
// ===========================================================================

@Composable
private fun MultiSelectDialog(
    title: String,
    options: List<String>,
    initial: List<String>,
    onDismiss: () -> Unit,
    onOk: (List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(initial.toSet()) }
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = title,
        onDismiss = onDismiss,
        width = 420.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
        ) {
            options.forEach { opt ->
                GCheckRow(opt, opt in selected, Modifier.fillMaxWidth()) { on ->
                    selected = if (on) selected + opt else selected - opt
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(onClick = { onOk(selected.toList()) }) { Text("确定") }
        }
            }
    }
}

// ===========================================================================
// 新建容器对话框（wineguanli.py show_new_container_dialog 逐字段移植）
// ===========================================================================

@Composable
private fun NewContainerDialog(
    wines: List<WineInstall>,
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var arch by remember { mutableStateOf("x86_64") }
    var wine by remember { mutableStateOf("") }
    var res by remember { mutableStateOf("全屏") }
    var lang by remember { mutableStateOf(ContainerLangs.DISPLAY.first()) }
    var frame by remember { mutableStateOf("默认(不限制)") }
    var error by remember { mutableStateOf<String?>(null) }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "创建新容器",
        onDismiss = onDismiss,
        // v3.2.22：总尺寸收紧（用户反馈“总大小也要降低点”）——
        // 520→430dp（×K 后 344dp），内边距 14/10→12/8、行距 8→6
        width = 430.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(12.dp), vertical = gdp(8.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gdp(6.dp))
        ) {
            GField("容器名称:") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    isError = error != null,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(13.sp)),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) Text(error ?: "", color = GuanliColors.red, fontSize = gsp(11.sp))
            }
            // v3.2.22：架构选择去重组框（GGroup 边框+上下 24dp 内衬是弹窗
            // “大得离谱”的次要来源），改 GField 轻量行 + 紧凑定宽下拉
            // （150dp 容得下 arm64ec + 箭头；原 fillMaxWidth 在 Row 里抢占整行）
            GField("架构:") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gdp(8.dp))) {
                    GComboBox(options = listOf("x86_64", "arm64ec"), selected = arch, onSelect = { arch = it }, width = 150.dp)
                    Text("(arm64ec使用fexwine)", fontSize = gsp(10.sp), color = GuanliColors.info)
                }
            }
            GField("Wine版本:") {
                GComboBox(
                    options = listOf("（默认）") + wines.map { it.displayLabel },
                    selected = if (wine.isBlank()) "（默认）" else wines.firstOrNull { it.name == wine }?.displayLabel ?: wine,
                    onSelect = { label ->
                        wine = if (label == "（默认）") "" else wines.firstOrNull { it.displayLabel == label }?.name ?: ""
                    }
                )
                if (wines.isEmpty()) GNote("未发现已安装 wine（/usr/opt）—— 高级设置 → Wine安装 可先装")
            }
            GField("分辨率:") {
                GComboBox(
                    options = listOf("全屏", "800x600", "1024x768", "1280x720", "1366x768", "1600x900", "1920x1080", "2560x1440"),
                    selected = res,
                    onSelect = { res = it }
                )
            }
            GField("语言:") {
                GComboBox(
                    options = ContainerLangs.DISPLAY,
                    selected = lang,
                    onSelect = { lang = it }
                )
            }
            GField("帧数限制:") {
                GComboBox(
                    options = listOf("默认(不限制)", "60", "120"),
                    selected = frame,
                    onSelect = { frame = it }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    val n = name.trim()
                    if (n.isEmpty()) { error = "请输入容器名称"; return@Button }
                    val backend = if (arch == "arm64ec") ContainerBackend.ARM64EC else ContainerBackend.BOX64
                    val data = ContainerData(
                        name = n,
                        backend = backend,
                        wine = wine,
                        screen = if (res == "全屏") "native" else res,
                        dmode = if (res == "全屏") DisplayMode.OFF else DisplayMode.D,
                        lang = ContainerLangs.CHOICES[ContainerLangs.DISPLAY.indexOf(lang).coerceAtLeast(0)],
                        frame = when (frame) { "60" -> "60"; "120" -> "120"; else -> "0" }
                    )
                    val err = ContainerManager.create(data)
                    if (err == null) onCreated(n) else error = err
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuanliColors.accent)
            ) { Text("确定") }
        }
            }
    }
}

// ===========================================================================
// 重命名容器（wineguanli.py rename_container）
// ===========================================================================

@Composable
private fun RenameContainerDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onRenamed: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(container.name) }
    var error by remember { mutableStateOf<String?>(null) }
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "重命名容器 · ${container.name}",
        onDismiss = onDismiss,
        width = 440.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("新容器名") },
                isError = error != null,
                singleLine = true
            )
            if (error != null) Text(error ?: "", color = GuanliColors.red, fontSize = gsp(11.sp))
            GNote("重命名 = 目录改名，WINEPREFIX 路径同步变化（运行中的容器先停止）")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(onClick = {
                val n = name.trim()
                if (n == container.name) { onDismiss() } else onRenamed(container.name, n)
            }) { Text("重命名") }
        }
            }
    }
}

// ===========================================================================
// 快捷方式对话框（wineguanli.py ShortcutDialog 的 Android 版）
// ===========================================================================

@Composable
private fun ShortcutDialog(
    container: ContainerData,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(container.name) }
    var exe by remember { mutableStateOf(container.exe) }
    var msg by remember { mutableStateOf<String?>(null) }

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "创建桌面快捷方式",
        onDismiss = onDismiss,
        width = 480.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            GField("快捷方式名称：") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            GField("容器：") {
                Text(container.name, fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = themeFg())
            }
            GField("启动程序（空 = 打开容器窗口再选）：") {
                OutlinedTextField(
                    value = exe, onValueChange = { exe = it },
                    placeholder = { Text("C:\\windows\\notepad.exe", fontSize = gsp(10.sp)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            GNote(
                "固定到 Android 启动器（ShortcutManager，系统弹确认框）。\n" +
                    "点按快捷方式会打开 AnWind 容器窗口；容器 exe 已配置时直接运行。"
            )
            if (msg != null) Text(msg ?: "", color = GuanliColors.red, fontSize = gsp(11.sp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    if (exe.isNotBlank()) {
                        // 把 exe 记进容器（快捷方式与"▶ 运行"共用）
                        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                            ContainerManager.update(container.copy(exe = exe))
                        }
                    }
                    val ok = GuanliSupport.Shortcuts.pinRunContainer(context, container, exe)
                    msg = if (ok) null else "本设备/启动器不支持固定快捷方式（系统会话管理器未授权）"
                    if (ok) onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuanliColors.accent)
            ) { Text("创建") }
        }
            }
    }
}

// ===========================================================================
// 运行 exe（保留 v3.2.13 的文件选择回填；默认填容器 exe）
// ===========================================================================

@Composable
private fun RunContainerDialog(
    container: ContainerData,
    onDismiss: () -> Unit,
    onLaunched: () -> Unit
) {
    val context = LocalContext.current
    var exe by remember(container.name) {
        mutableStateOf(container.exe.ifBlank { "C:\\windows\\notepad.exe" })
    }
    var args by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

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

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "运行 · ${container.name}",
        onDismiss = onDismiss,
        width = 560.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
        ) {
            GField("程序路径", "Windows 路径（C:\\…）或容器内绝对路径（/storage/…）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = exe, onValueChange = { exe = it; msg = null },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = gsp(12.sp)),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(gdp(8.dp)))
                    GSmallButton(if (picking) "选择中…" else "浏览…", GuanliColors.blueFab, Modifier) {
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
                    }
                }
            }
            GField("启动参数", "传给程序的命令行参数（可空）") {
                OutlinedTextField(
                    value = args, onValueChange = { args = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            GNote("点击\"运行\"将自动打开 X11 桌面窗口并经 box64/FEXCore 启动 Wine。")
            if (msg != null) {
                Text(msg ?: "", color = GuanliColors.red, fontSize = gsp(12.sp))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            Spacer(Modifier.width(gdp(8.dp)))
            Button(
                onClick = {
                    val err = WineSessionLauncher.runExe(context, container, exe.trim(), args.trim())
                    if (err == null) onLaunched() else msg = err
                },
                colors = ButtonDefaults.buttonColors(containerColor = GuanliColors.green)
            ) { Text("运行") }
        }
            }
    }
}

// ===========================================================================
// wineguanli 风格共享组件（QGroupBox / QComboBox / QPushButton / QCheckBox）
// 全部经 gdp/gsp 缩放，任意窗口宽度下等比适配
// ===========================================================================

/** QGroupBox：圆角描边 + 左上角蓝色标题片（Qt 样式表同款视觉）。 */
@Composable
private fun GGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(gdp(8.dp)))
            .background(themeCard())
            .border(gdp(1.dp), GuanliColors.accent.copy(alpha = 0.45f), RoundedCornerShape(gdp(8.dp)))
            .padding(top = gdp(14.dp), bottom = gdp(10.dp), start = gdp(12.dp), end = gdp(12.dp)),
        verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
    ) {
        Text(
            title,
            fontSize = gsp(13.sp), fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(gdp(4.dp)))
                .background(GuanliColors.accent)
                .padding(horizontal = gdp(10.dp), vertical = gdp(2.dp))
        )
        content()
    }
}

/** 字段行：标签在上（QFormLayout 行 / QLabel）。 */
@Composable
private fun GField(
    label: String,
    // v3.2.20 修复：2906/2930 两处调用为三参形态 GField(标签, 说明) { … }，
    // 原二参签名报 "Too many arguments" + "String 但 () -> Unit" ——
    // 补可选 note 参数（Qt 表单的 field 说明小字），旧二参调用不受影响。
    note: String? = null,
    content: @Composable () -> Unit
) {
    Column {
        Text(label, fontSize = gsp(12.sp), fontWeight = FontWeight.Medium, color = themeFg())
        if (note != null) GNote(note)
        else Spacer(Modifier.height(gdp(4.dp)))
        content()
    }
}

/** 说明小字（Qt note 样式 #7f8c8d）。 */
@Composable
private fun GNote(text: String) {
    Text(
        text,
        fontSize = gsp(10.sp), color = GuanliColors.info, lineHeight = gsp(14.sp)
    )
}

/** Qt 风格彩色按钮（QPushButton：圆角 6dp、粗体、白字、min-width）。 */
@Composable
private fun GSmallButton(
    label: String,
    bg: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fg: Color = Color.White,
    onClick: () -> Unit
) {
    Text(
        label,
        color = if (enabled) fg else fg.copy(alpha = 0.45f),
        fontSize = gsp(12.sp), fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(gdp(6.dp)))
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = gdp(12.dp), vertical = gdp(7.dp))
    )
}

/** QCheckBox 行。 */
@Composable
private fun GCheckRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(gdp(4.dp)))
            .clickable { onChange(!checked) }
            .padding(horizontal = gdp(2.dp), vertical = gdp(2.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onChange(it) },
            modifier = Modifier
                .requiredSize(gdp(18.dp))
                .padding(end = 0.dp)
        )
        Spacer(Modifier.width(gdp(6.dp)))
        Text(label, fontSize = gsp(12.sp), color = themeFg())
    }
}

/** 勾选药丸流（核心/开关组，FlowRow 自动换行）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GCheckFlow(
    items: List<Pair<String, String>>,
    checked: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(gdp(6.dp)),
        verticalArrangement = Arrangement.spacedBy(gdp(6.dp))
    ) {
        items.forEach { (value, label) ->
            val on = checked(value)
            Row(
                Modifier
                    .clip(RoundedCornerShape(gdp(14.dp)))
                    .background(if (on) GuanliColors.accent.copy(alpha = 0.18f) else themeCard())
                    .border(
                        gdp(0.5.dp),
                        if (on) GuanliColors.accent else themeSec().copy(alpha = 0.3f),
                        RoundedCornerShape(gdp(14.dp))
                    )
                    .clickable { onToggle(value, !on) }
                    .padding(horizontal = gdp(10.dp), vertical = gdp(5.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (on) "✓ " else "", fontSize = gsp(11.sp), color = GuanliColors.accent)
                Text(
                    label, fontSize = gsp(11.sp),
                    color = if (on) GuanliColors.accent else themeFg(),
                    maxLines = 1
                )
            }
        }
    }
}

/** QRadioButton 行。 */
@Composable
private fun GRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(gdp(4.dp)))
            .clickable { onClick() }
            .padding(vertical = gdp(2.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(gdp(4.dp)))
        Text(label, fontSize = gsp(12.sp), color = themeFg())
    }
}

/**
 * QComboBox 等价物：显示框 + 下拉菜单。
 * @param customLabel 选中值不在选项里时显示的自定义文本（可编辑下拉的当前值）
 * @param width 紧凑宽度（v3.2.22：不传 = 占满整行，表单字段用；
 * 传入则按 gdp(width) 定宽 —— 选项少的短下拉（架构选择 x86_64/arm64ec
 * 两项）在 Row 里与提示文字并排时用，避免 fillMaxWidth 把同行
 * 后续元素挤没、下拉框拉得比弹窗还宽（用户实测“大得离谱”））
 */
@Composable
private fun GComboBox(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    customLabel: String? = null,
    width: Dp? = null
) {
    var open by remember { mutableStateOf(false) }
    val display = customLabel
        ?: options.firstOrNull { it == selected } ?: selected
    Box {
        Row(
            Modifier
                .then(if (width != null) Modifier.width(gdp(width)) else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(gdp(4.dp)))
                .background(themeCard())
                .border(gdp(1.dp), GuanliColors.accent, RoundedCornerShape(gdp(4.dp)))
                .clickable { open = true }
                .padding(horizontal = gdp(10.dp), vertical = gdp(8.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                display, fontSize = gsp(13.sp), color = themeFg(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(" ▾", fontSize = gsp(12.sp), color = GuanliColors.accent)
        }
        GDropdownMenu(expanded = open, onDismiss = { open = false }) {
            options.forEach { opt ->
                GDropdownMenuItem(opt) {
                    open = false
                    onSelect(opt)
                }
            }
        }
    }
}

/** 下拉菜单容器（material3 DropdownMenu 包一层）。 */
@Composable
private fun GDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // v3.2.20 修复：material3 1.2.1（compose-bom 2024.06.00）的 DropdownMenu
    // 尚无 containerColor 参数（CI 实报 "Cannot find a parameter with this
    // name: containerColor"）—— 菜单底色改为包一层同色 Column 实现，
    // 菜单尺寸本就包裹内容（IntrinsicSize.Min），视觉等效。
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(IntrinsicSize.Min)
    ) {
        Column(Modifier.background(themeCard(), RoundedCornerShape(gdp(4.dp)))) {
            content()
        }
    }
}

/** 下拉菜单项（QMenu::item 样式：选中蓝底白字）。 */
@Composable
private fun GDropdownMenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontSize = gsp(12.sp), color = themeFg()) },
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = gdp(14.dp), vertical = gdp(4.dp))
    )
}

@Composable
private fun GDropdownMenuDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = themeSec().copy(alpha = 0.25f),
        modifier = Modifier.padding(vertical = gdp(2.dp))
    )
}

// ---------------------------------------------------------------------------
// rootfs 缺失提示（保留）
// ---------------------------------------------------------------------------

@Composable
private fun RootfsMissingHint() {
    Card(
        colors = CardDefaults.cardColors(containerColor = themeCard()),
        shape = RoundedCornerShape(gdp(8.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gdp(12.dp), vertical = gdp(4.dp))
    ) {
        Column(Modifier.padding(gdp(12.dp))) {
            Text("未检测到 bionic rootfs", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(gdp(4.dp)))
            Text(
                "未检测到 bionic rootfs。若本 APK 内置了 rootfs 资产（assets/anwind/rootfs-*.tzst），" +
                    "启动时会自动解压到 /data/data/com.anwind/files/rootfs（请稍候重进）；" +
                    "也可在终端用 anwind-tarxz 导入 build-bionic-rootfs.yml 构建的 rootfs 包（内含 anwind-container CLI）。",
                fontSize = gsp(11.sp), color = themeSec()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 体检（保留 v3.2.13：box64 运行探活）
// ---------------------------------------------------------------------------

@Composable
private fun DoctorDialog(onDismiss: () -> Unit) {
    var checks by remember { mutableStateOf<List<DoctorCheck>?>(null) }
    LaunchedEffect(Unit) {
        checks = withContext(Dispatchers.IO) { ContainerManager.doctor() }
    }
    val loaded = checks

    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "AnWind Wine 容器体检",
        onDismiss = onDismiss,
        width = 560.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        if (loaded == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gdp(14.dp), vertical = gdp(20.dp)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(gdp(28.dp)),
                    strokeWidth = gdp(2.5.dp),
                    color = themeAccent()
                )
                Spacer(Modifier.height(gdp(10.dp)))
                Text("正在体检（box64 运行探活最长 5 秒）…", fontSize = gsp(12.sp), color = themeSec())
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp)),
                verticalArrangement = Arrangement.spacedBy(gdp(8.dp))
            ) {
                items(loaded.size) { i ->
                    val c = loaded[i]
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            if (c.ok) "✓" else "✗",
                            color = if (c.ok) themeAccent() else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(gdp(8.dp)))
                        Column {
                            Text(c.title, fontWeight = FontWeight.Bold, fontSize = gsp(13.sp))
                            Text(c.detail, fontSize = gsp(11.sp), color = themeSec())
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("好的") }
        }
            }
    }
}

// ---------------------------------------------------------------------------
// 日志（保留）
// ---------------------------------------------------------------------------

@Composable
private fun LogDialog(title: String, log: String, onDismiss: () -> Unit) {
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = title,
        onDismiss = onDismiss,
        width = 620.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                log.takeLast(20000),
                fontSize = gsp(11.sp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = themeSec(),
                lineHeight = gsp(15.sp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("关闭") }
        }
            }
    }
}

// ---------------------------------------------------------------------------
// v3.1.6 前缀构建日志（保留）
// ---------------------------------------------------------------------------

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
            kotlinx.coroutines.delay(400)
        }
    }
    // 弹窗内容在弹窗层组合，LocalGuanliScale 按树位置解析为默认 1f ——
    // 必须在窗口树位置（本函数体）先捕获，闭包带进弹窗层
    val dlgScale = LocalGuanliScale.current
    val dlgFontScale = LocalGuanliFontScale.current   // v3.2.23：字号系数随行
    WinDialog(
        title = "构建 Wine 前缀 · $containerName" + if (running) "（进行中…）" else "（已结束）",
        onDismiss = onDismiss,
        width = 620.dp * GuanliUiK
    ) {
        GuanliDialogScale(dlgScale, dlgFontScale) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(10.dp))
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text.ifEmpty { "（暂无输出——wineboot 启动后此处逐行显示；若长时间空白说明 wine 挂在启动早期）" },
                fontSize = gsp(11.sp),
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = themeSec(),
                lineHeight = gsp(15.sp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = gdp(14.dp), vertical = gdp(4.dp)),
            horizontalArrangement = Arrangement.End
        ) {
            Button(onClick = onDismiss) { Text("关闭") }
        }
            }
    }
}
