package com.anwind.apps.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import com.anwind.core.input.keyboardAware
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.anwind.AnWindApp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope
import com.anwind.core.window.AppDef
import com.anwind.core.window.WindowManager
import com.anwind.data.db.entity.BookmarkEntity
import com.anwind.data.model.DesktopItemType
import com.anwind.util.ImmersiveMode
import kotlinx.coroutines.launch

/**
 * 浏览器应用定义。
 *
 * v2.8 功能清单：
 * - 多标签页（TabManager，标签专属 WebView 常驻缓存，切换不重建）
 * - 前进/后退/刷新/主页
 * - 地址栏（自动补全 https://）
 * - 本地 HTML 文件读取（通过 SAF 选择文件）
 * - 书签管理（持久化到 Room）
 * - 历史记录（持久化到 Room）
 * - 首页快捷导航（百度/B站/GitHub/必应等）
 * - window.open / target=_blank / 表单弹窗 → 真实新标签
 * - 真全屏（隐藏状态栏/导航键/任务栏/标签栏/工具栏，双击或返回键恢复窗口）
 * - HTML5 视频全屏（DecorView 覆盖层，满屏播放）
 */
val BrowserApp = AppDef(
    id = "browser",
    displayName = "浏览器",
    iconAsset = "icons/browser.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 980.dp,
    defaultHeight = 640.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    BrowserContent(scope)
}

@Composable
private fun BrowserContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val app = AnWindApp.get()
    val scope0 = rememberCoroutineScope()
    val wm = remember { WindowManager.get() }
    // 监听 WindowManager 变化（用于 isTrueFullscreen 状态切换时工具栏图标立即更新）
    var wmRevision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmRevision++ } }
    val isFullscreen = remember(wmRevision) { scope.windowState.isTrueFullscreen }

    val launchUrl = scope.windowState.launchArgs["url"]
    val launchType = scope.windowState.launchArgs["type"]

    // ===== 会话（TabManager 挂在 BrowserSessions 注册表，跨最小化存活）=====
    val tabManager = remember(scope.windowState.id) {
        BrowserSessions.getOrCreate(scope.windowState.id)
    }
    // 窗口真正关闭时销毁全部 WebView（修复"关了浏览器还在响"复发）；
    // 注意不能挂在 onDispose 上 —— 最小化也会触发 onDispose
    DisposableEffect(scope.windowState.id) {
        scope.windowState.onClose = { BrowserSessions.destroy(scope.windowState.id) }
        onDispose { }
    }

    // 桌面/手机模式（持久化，切换后立即重载当前页生效）
    val uaMode by app.settingsStore.browserUaMode.collectAsState(initial = "desktop")
    SideEffect { tabManager.uaMode = uaMode }
    fun toggleUaMode() {
        val next = if (uaMode == "desktop") "mobile" else "desktop"
        scope0.launch { app.settingsStore.setBrowserUaMode(next) }
    }

    // v2.14：渲染模式（硬件加速 / 软件渲染）—— 同步到 TabManager 供新建 WebView 使用；
    // 切换时对所有已存在标签应用新 layerType 并重载（灰屏修复立即生效）
    val renderMode by app.settingsStore.browserRenderMode.collectAsState(initial = "hardware")
    LaunchedEffect(renderMode) {
        tabManager.renderMode = renderMode
        tabManager.tabs.forEach { t ->
            t.webView?.let { wv ->
                BrowserEngine.applyRenderMode(wv, renderMode)
                if (wv.layerType == android.view.View.LAYER_TYPE_SOFTWARE || renderMode == "software") {
                    // 切到软件渲染后重载，重新走软件合成路径
                    runCatching { wv.reload() }
                }
            }
        }
    }

    // 用户设置的主页（默认 AnWind 速度页 anwind://home）
    val defaultHome by app.settingsStore.defaultBrowserHome.collectAsState(initial = "anwind://home")

    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // 启动时打开初始标签（会话已存在标签时跳过 —— 最小化恢复不重开）
    LaunchedEffect(Unit) {
        if (tabManager.tabs.isEmpty()) {
            val initialUrl = when {
                launchUrl.isNullOrEmpty() -> defaultHome
                launchType == DesktopItemType.SHORTCUT_FILE.name && launchUrl.startsWith("content://") -> launchUrl
                else -> normalizeUrl(launchUrl)
            }
            tabManager.openTab(initialUrl)
        }
    }

    // 进入真全屏时重新断言隐藏系统状态栏/导航键（部分系统会重新显示系统栏）
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            ImmersiveMode.applyTo(context)
        }
    }
    // 视频全屏（DecorView 覆盖层）显示期间同样隐藏系统栏
    val inVideoFullscreen = tabManager.customView != null
    LaunchedEffect(inVideoFullscreen) {
        if (inVideoFullscreen) {
            ImmersiveMode.applyTo(context)
        }
    }

    // 真全屏时的返回键 → 退出全屏恢复窗口（双击页面同样恢复，见内容区手势）
    BackHandler(enabled = isFullscreen) {
        wm.toggleTrueFullscreen(scope.windowState.id)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
    ) {
        Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

            // ===== 标签栏（真全屏时隐藏，F11 风格纯页面） =====
            if (!isFullscreen) {
                TabBar(
                    tabs = tabManager.tabs,
                    activeTabId = tabManager.activeTabId,
                    onTabClick = { id -> tabManager.switchTo(id) },
                    onCloseTab = { id -> tabManager.closeTab(id) },
                    onNewTab = { tabManager.openTab("anwind://home") }
                )
            }

            // ===== 工具栏（真全屏时隐藏；双击页面或按返回键恢复窗口） =====
            if (!isFullscreen) {
                Toolbar(
                    address = tabManager.addressInput,
                    onAddressChange = { tabManager.addressInput = it },
                    onBack = { tabManager.getTab(tabManager.activeTabId)?.goBack() },
                    onForward = { tabManager.getTab(tabManager.activeTabId)?.goForward() },
                    onRefresh = { tabManager.getTab(tabManager.activeTabId)?.refresh() },
                    onHome = {
                        tabManager.getTab(tabManager.activeTabId)?.loadUrl(defaultHome)
                        tabManager.addressInput = if (defaultHome == "anwind://home") "" else defaultHome
                    },
                    onGo = {
                        val url = normalizeUrl(tabManager.addressInput)
                        val tab = tabManager.getTab(tabManager.activeTabId)
                        if (tab != null && url.isNotEmpty()) {
                            // 只更新 tab.url，由 WebViewContainer 的 update 块统一 loadUrl
                            //（历史记录由引擎在 onPageStarted 统一写入）
                            tab.url = url
                            tab.title = url
                            tabManager.addressInput = url
                        }
                    },
                    onBookmark = {
                        val url = tabManager.addressInput
                        if (url.isNotEmpty()) {
                            scope0.launch {
                                val dao = app.database.bookmarkDao()
                                val existing = dao.findByUrl(url)
                                if (existing == null) {
                                    dao.insert(BookmarkEntity(title = url, url = url))
                                } else {
                                    dao.delete(existing)
                                }
                            }
                        }
                    },
                    onShowBookmarks = { showBookmarks = !showBookmarks },
                    onShowHistory = { showHistory = !showHistory },
                    uaMode = uaMode,
                    onToggleUaMode = { toggleUaMode() },
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = { wm.toggleTrueFullscreen(scope.windowState.id) }
                )
            }

            // ===== 内容区 =====
            // 真全屏时：双击内容区恢复窗口（Initial pass 只观察不消费，
            // 不影响网页的正常触摸/滚动/点击）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(isFullscreen) {
                        if (!isFullscreen) return@pointerInput
                        var lastTapUptime = 0L
                        var lastTapPos = Offset.Zero
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            val downTime = down.uptimeMillis
                            val downPos = down.position
                            val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                            if (up != null) {
                                val dt = downTime - lastTapUptime
                                if (dt in 0..350 && (downPos - lastTapPos).getDistance() < 120f) {
                                    lastTapUptime = 0L
                                    // 视频全屏覆盖层在前台时把双击留给视频自身处理
                                    if (tabManager.customView == null) {
                                        wm.toggleTrueFullscreen(scope.windowState.id)
                                    }
                                } else {
                                    lastTapUptime = downTime
                                    lastTapPos = downPos
                                }
                            }
                        }
                    }
            ) {
                val activeTab = tabManager.getTab(tabManager.activeTabId)
                if (activeTab != null) {
                    val url = activeTab.url
                    if (url == "anwind://home" || url.isEmpty()) {
                        BrowserHomePage(
                            onNavigate = { target ->
                                val finalUrl = normalizeUrl(target)
                                val tab = tabManager.getTab(tabManager.activeTabId)
                                if (tab != null) {
                                    // home → 真实 URL：tab.webView 为空时 factory 创建并延迟加载；
                                    // 已有 WebView 时 update 块负责 loadUrl
                                    tab.url = finalUrl
                                    tab.title = finalUrl
                                    tabManager.addressInput = finalUrl
                                }
                            }
                        )
                    } else {
                        // key(activeTab.id)：每个标签独立的组合位置 → 各自的 AndroidView。
                        // WebView 本身常驻缓存（切换标签不销毁），切回时原样恢复。
                        key(activeTab.id) {
                            WebViewContainer(
                                tab = activeTab,
                                tabManager = tabManager,
                                uaMode = uaMode,
                                onRetry = { tabManager.getTab(tabManager.activeTabId)?.refresh() }
                            )
                        }
                    }
                }
            }

            // ===== 书签/历史侧边面板 =====
            if (showBookmarks) {
                BookmarksPanel(
                    onSelect = { url ->
                        tabManager.addressInput = url
                        tabManager.getTab(tabManager.activeTabId)?.loadUrl(url)
                        showBookmarks = false
                    },
                    onClose = { showBookmarks = false }
                )
            }
            if (showHistory) {
                HistoryPanel(
                    onSelect = { url ->
                        tabManager.addressInput = url
                        tabManager.getTab(tabManager.activeTabId)?.loadUrl(url)
                        showHistory = false
                    },
                    onClose = { showHistory = false }
                )
            }
        } // end Column
    } // end Box (browser root)
}

/**
 * WebView 容器：渲染网页。
 *
 * v2.8 关键设计：
 * - WebView 由 [BrowserEngine.ensureWebView] 按【标签】缓存 —— 切换标签只是
 *   attach/detach；onRelease 仅在标签关闭（destroyPending）时销毁。
 * - 首次加载通过 view.post{} 推迟到 attach/layout 之后执行，修复
 *   "页面加载完成但 WebView 永不绘制（黑屏）"的首帧竞态。
 * - client 由引擎一次性创建，UI 回调经 TabManager.ui 路由到当前组合。
 * - HTML5 视频全屏时，返回键退出全屏。
 */
@Composable
private fun WebViewContainer(
    tab: BrowserTab,
    tabManager: TabManager,
    uaMode: String,
    onRetry: () -> Unit = {}
) {
    val theme = LocalWinTheme.current
    var progress by remember { mutableStateOf(if (tab.webView == null) 0 else 100) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 注册 UI 回调（带属主令牌：切换标签时只有仍持有路由的一方才清理，
    // 避免新标签的注册被旧标签的 onDispose 误清）
    val uiToken = remember { Any() }
    DisposableEffect(tab.id) {
        tabManager.ui = object : TabUiCallbacks {
            override fun onUrlChanged(url: String) {
                tabManager.addressInput = url
            }

            override fun onUrlSync(url: String) {
                tabManager.addressInput = url
            }

            override fun onTitleChanged(title: String) {
                // 标题经 tab.title 反映到标签栏
            }

            override fun onProgressChanged(p: Int) {
                progress = p
            }

            override fun onError(message: String?) {
                errorMessage = message
            }
        }
        tabManager.uiOwner = uiToken
        onDispose {
            if (tabManager.uiOwner === uiToken) {
                tabManager.ui = null
                tabManager.uiOwner = null
            }
        }
    }

    // 视频全屏（DecorView 覆盖层）期间：返回键退出视频全屏
    val inVideoFullscreen = tabManager.customView != null
    BackHandler(enabled = inVideoFullscreen) {
        tabManager.hideCustomView()
    }

    Box(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {
        AndroidView(
            factory = { ctx ->
                val wv = BrowserEngine.ensureWebView(ctx, tab, tabManager)
                // 首次加载：推迟到 attach/layout 之后（view.post），
                // 避免 Chromium 以 0x0 surface 开始加载导致首帧黑屏
                if (!tab.hasLoadedOnce) {
                    val target = tab.url
                    if (target.isNotEmpty() && !target.startsWith("anwind://") && !target.startsWith("about:")) {
                        tab.hasLoadedOnce = true
                        tab.lastRequestedUrl = target
                        wv.post {
                            if (tab.webView === wv) {
                                wv.loadUrl(target)
                            }
                        }
                    }
                }
                wv
            },
            update = { webview ->
                // 重新绑定（tab.webView 在 onRelease 后可能被置空）
                tab.webView = webview
                // UA 模式切换（当前激活标签实时生效）：设置后立即重载
                val desiredUa = BrowserEngine.desiredUa(uaMode)
                if (webview.settings.userAgentString != desiredUa) {
                    try {
                        webview.settings.userAgentString = desiredUa
                        webview.reload()
                    } catch (_: Exception) {
                    }
                }
                // 地址栏/首页/书签导航：目标 URL 变化且与最近请求不同时才加载，
                // 且同样通过 post{} 确保视图已 attach
                val target = tab.url
                if (target.isNotEmpty() && !target.startsWith("anwind://") && target != tab.lastRequestedUrl) {
                    errorMessage = null
                    tab.hasLoadedOnce = true
                    tab.lastRequestedUrl = target
                    webview.post {
                        if (tab.webView === webview) {
                            webview.loadUrl(target)
                        }
                    }
                }
            },
            onRelease = { webview ->
                // 仅当标签被关闭时销毁 WebView；
                // 普通切换标签保留（缓存复用，切回即恢复原状态）
                if (tab.destroyPending) {
                    BrowserEngine.destroyWebView(webview)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 加载进度条
        if (progress in 1 until 100) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .height(2.dp),
                color = theme.accentColor,
                trackColor = theme.accentColor.copy(alpha = 0.2f)
            )
        }

        // 错误提示层
        errorMessage?.let { msg ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("😕", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "页面加载失败",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) {
                    Text("重试")
                }
            }
        }
    }
}

/**
 * 浏览器首页：快捷导航 + 搜索框 + 历史/书签入口
 */
@Composable
private fun BrowserHomePage(onNavigate: (String) -> Unit) {
    val theme = LocalWinTheme.current
    var searchText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        // Logo
        Text(
            text = "🌐 AnWind Browser",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(24.dp))

        // 搜索框
        Row(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(44.dp)
                .background(theme.buttonBackgroundColor, RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = searchText,
                onValueChange = { searchText = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    val q = searchText.trim()
                    if (q.isNotEmpty()) {
                        // 直接把用户输入交给 onNavigate，由 normalizeUrl 判断：
                        // - 含 . 的视为网址 (例如 baidu.com → https://baidu.com)
                        // - 否则视为搜索关键词 (例如 你好 → Bing 搜索)
                        onNavigate(q)
                    }
                }),
                modifier = Modifier
                    .weight(1f)
                    .keyboardAware(
                        value = { searchText },
                        onValue = { searchText = it },
                        singleLine = true,
                        onEnter = {
                            val q = searchText.trim()
                            if (q.isNotEmpty()) onNavigate(q)
                        }
                    )
            )
            IconButton(onClick = {
                val q = searchText.trim()
                if (q.isNotEmpty()) {
                    // 与 IME 搜索回调保持一致：交给 onNavigate + normalizeUrl 判断
                    onNavigate(q)
                }
            }) {
                Icon(Icons.Default.Search, contentDescription = "搜索")
            }
        }

        Spacer(Modifier.height(32.dp))

        // 快捷导航
        val quickLinks = listOf(
            QuickLink("必应", "https://www.bing.com", "🔍"),
            QuickLink("百度", "https://www.baidu.com", "🅑"),
            QuickLink("B站", "https://www.bilibili.com", "📺"),
            QuickLink("GitHub", "https://github.com", "🐙"),
            QuickLink("知乎", "https://www.zhihu.com", "💡"),
            QuickLink("微博", "https://weibo.com", "🐦"),
            QuickLink("YouTube", "https://www.youtube.com", "▶️"),
            QuickLink("Wikipedia", "https://www.wikipedia.org", "📚")
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(quickLinks) { link ->
                QuickLinkItem(link = link, onClick = { onNavigate(link.url) })
            }
        }
    }
}

private data class QuickLink(val name: String, val url: String, val emoji: String)

@Composable
private fun QuickLinkItem(link: QuickLink, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp)
            .width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(theme.accentColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(link.emoji, fontSize = 24.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = link.name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

/**
 * 标签栏
 */
@Composable
private fun TabBar(
    tabs: List<BrowserTab>,
    activeTabId: String?,
    onTabClick: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(theme.windowTitleBarColor.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(tabs, key = { it.id }) { tab ->
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .background(
                            if (tab.id == activeTabId) theme.windowBackgroundColor
                            else theme.buttonBackgroundColor.copy(alpha = 0.5f),
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onTabClick(tab.id) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tab.title.take(15),
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 11.sp,
                        modifier = Modifier.width(100.dp)
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = if (theme.isDark) Color.White else Color.Black,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable { onCloseTab(tab.id) }
                    )
                }
            }
        }
        IconButton(onClick = onNewTab, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "New Tab",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
    }
}

/**
 * 工具栏
 */
@Composable
private fun Toolbar(
    address: String,
    onAddressChange: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onGo: () -> Unit,
    onBookmark: () -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    uaMode: String = "desktop",
    onToggleUaMode: () -> Unit = {},
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(theme.windowBackgroundColor)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onForward, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ArrowForward, contentDescription = "Forward",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onHome, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Home, contentDescription = "Home",
                tint = if (theme.isDark) Color.White else Color.Black)
        }

        // 地址栏
        Box(
            modifier = Modifier
                .weight(1f)
                .height(30.dp)
                .background(theme.buttonBackgroundColor, RoundedCornerShape(15.dp))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (address.isEmpty()) {
                Text(
                    "搜索或输入网址",
                    color = theme.buttonTextColor.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
            }
            BasicTextField(
                value = address,
                onValueChange = onAddressChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 13.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onGo() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .keyboardAware(
                        value = { address },
                        onValue = onAddressChange,
                        singleLine = true,
                        onEnter = onGo
                    )
            )
        }

        // 显眼 Go/搜索按钮：带主题背景色，确保用户能发现"如何前往"
        Box(
            modifier = Modifier
                .height(30.dp)
                .background(theme.accentColor, RoundedCornerShape(15.dp))
                .clickable(onClick = onGo)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "前往",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "前往",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        IconButton(onClick = onBookmark, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.StarBorder, contentDescription = "Bookmark",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onShowBookmarks, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.BookmarkBorder, contentDescription = "Bookmarks",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        IconButton(onClick = onShowHistory, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.History, contentDescription = "History",
                tint = if (theme.isDark) Color.White else Color.Black)
        }
        // 桌面/手机模式切换
        IconButton(onClick = onToggleUaMode, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (uaMode == "desktop") Icons.Default.Computer else Icons.Default.Smartphone,
                contentDescription = if (uaMode == "desktop") "桌面模式" else "手机模式",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
        // 真全屏切换（F11 风格）：隐藏状态栏/导航键/任务栏/标签栏/工具栏，浏览器占满整屏；
        // 全屏中双击页面或按返回键恢复窗口
        IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "退出全屏" else "全屏",
                tint = if (theme.isDark) Color.White else Color.Black
            )
        }
    }
}

/**
 * 书签面板
 */
@Composable
private fun BookmarksPanel(onSelect: (String) -> Unit, onClose: () -> Unit) {
    val app = AnWindApp.get()
    val bookmarks by app.database.bookmarkDao().observeAll().collectAsState(initial = emptyList())
    val theme = LocalWinTheme.current

    PopupPanel(title = "书签", onClose = onClose) {
        if (bookmarks.isEmpty()) {
            Text("暂无书签", color = if (theme.isDark) Color.White else Color.Black)
        } else {
            bookmarks.forEach { bm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(bm.url) }
                        .padding(8.dp)
                ) {
                    Text("⭐", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(bm.title, color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, maxLines = 1)
                        Text(bm.url, color = theme.buttonTextColor.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryPanel(onSelect: (String) -> Unit, onClose: () -> Unit) {
    val app = AnWindApp.get()
    val history by app.database.historyDao().observeRecent(50).collectAsState(initial = emptyList())
    val theme = LocalWinTheme.current

    PopupPanel(title = "历史记录", onClose = onClose) {
        if (history.isEmpty()) {
            Text("暂无历史", color = if (theme.isDark) Color.White else Color.Black)
        } else {
            history.forEach { h ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(h.url) }
                        .padding(8.dp)
                ) {
                    Text("🕘", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(h.title, color = if (theme.isDark) Color.White else Color.Black, fontSize = 13.sp, maxLines = 1)
                        Text(h.url, color = theme.buttonTextColor.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupPanel(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalWinTheme.current
    val scrollState = androidx.compose.foundation.rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(onClick = onClose)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .size(width = 320.dp, height = 360.dp)
                .background(theme.windowBackgroundColor, RoundedCornerShape(8.dp))
                .border(1.dp, theme.windowBorderColor, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close",
                            tint = if (theme.isDark) Color.White else Color.Black)
                    }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    content = content
                )
            }
        }
    }
}

private fun normalizeUrl(input: String): String {
    if (input.isEmpty()) return ""
    if (input.startsWith("http://") || input.startsWith("https://") || input.startsWith("content://")) return input
    if (input.startsWith("anwind://")) return input
    if (input.contains(".") && !input.contains(" ")) {
        // v2.14：内网地址（IP / localhost / 带端口号）默认 http ——
        // 路由器/NAS/本地服务大多不支持 https，自动加 https:// 会直接挂掉。
        // 其余域名仍默认 https。
        val isBareHost = input.substringBefore("/")
        val isIp = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d+)?").matches(isBareHost)
        val isLocalhost = isBareHost.startsWith("localhost") || isBareHost.endsWith(".local")
        val hasPort = isBareHost.contains(":")
        return if (isIp || isLocalhost || hasPort) "http://$input" else "https://$input"
    }
    return "https://www.bing.com/search?q=" + android.net.Uri.encode(input)
}
