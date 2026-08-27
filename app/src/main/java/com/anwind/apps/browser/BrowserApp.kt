package com.anwind.apps.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.anwind.AnWindApp
import com.anwind.core.desktop.IconPainter
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope
import com.anwind.core.window.AppDef
import com.anwind.core.window.WindowManager
import com.anwind.data.db.entity.HistoryEntity
import com.anwind.data.model.DesktopItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 浏览器应用定义。
 *
 * 功能清单：
 * - 多标签页（TabManager）
 * - 前进/后退/刷新/主页
 * - 地址栏（自动补全 https://）
 * - 本地 HTML 文件读取（通过 SAF 选择文件）
 * - 书签管理（持久化到 Room）
 * - 历史记录（持久化到 Room）
 * - 首页快捷导航（百度/B站/GitHub/必应等）
 */
val BrowserApp = AppDef(
    id = "browser",
    displayName = "浏览器",
    iconAsset = "icons/browser.png",
    launchMode = LaunchMode.FULLSCREEN,
    defaultWidth = 1080.dp,
    defaultHeight = 720.dp,
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

    // 启动参数：URL / 本地文件
    val launchUrl = scope.windowState.launchArgs["url"]
    val launchType = scope.windowState.launchArgs["type"]

    // 标签管理器
    val tabManager = remember { TabManager() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var addressInput by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // 启动时打开初始标签
    LaunchedEffect(Unit) {
        val initialUrl = when {
            launchUrl.isNullOrEmpty() -> null
            launchType == DesktopItemType.SHORTCUT_FILE.name && launchUrl.startsWith("content://") -> launchUrl
            else -> normalizeUrl(launchUrl)
        }
        val tab = tabManager.openTab(initialUrl ?: "anwind://home")
        activeTabId = tab.id
        addressInput = initialUrl ?: ""
    }

    Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 标签栏 =====
        TabBar(
            tabs = tabManager.tabs,
            activeTabId = activeTabId,
            onTabClick = { id ->
                activeTabId = id
                addressInput = tabManager.getTab(id)?.url ?: ""
            },
            onCloseTab = { id ->
                tabManager.closeTab(id)
                if (activeTabId == id) {
                    activeTabId = tabManager.tabs.firstOrNull()?.id
                    addressInput = tabManager.tabs.firstOrNull()?.url ?: ""
                }
            },
            onNewTab = {
                val tab = tabManager.openTab("anwind://home")
                activeTabId = tab.id
                addressInput = ""
            }
        )

        // ===== 工具栏 =====
        Toolbar(
            address = addressInput,
            onAddressChange = { addressInput = it },
            onBack = { tabManager.getTab(activeTabId)?.goBack() },
            onForward = { tabManager.getTab(activeTabId)?.goForward() },
            onRefresh = { tabManager.getTab(activeTabId)?.refresh() },
            onHome = {
                val tab = tabManager.openOrSwitchHome(activeTabId)
                activeTabId = tab.id
                addressInput = ""
            },
            onGo = {
                val url = normalizeUrl(addressInput)
                tabManager.getTab(activeTabId)?.loadUrl(url)
                scope0.launch {
                    withContext(Dispatchers.IO) {
                        app.database.historyDao().insert(
                            HistoryEntity(title = url, url = url)
                        )
                    }
                }
            },
            onBookmark = {
                val url = addressInput
                if (url.isNotEmpty()) {
                    scope0.launch {
                        withContext(Dispatchers.IO) {
                            val existing = app.database.bookmarkDao().findByUrl(url)
                            if (existing == null) {
                                app.database.bookmarkDao().insert(
                                    com.anwind.data.db.entity.BookmarkEntity(
                                        title = url,
                                        url = url
                                    )
                                )
                            } else {
                                app.database.bookmarkDao().delete(existing)
                            }
                        }
                    }
                }
            },
            onShowBookmarks = { showBookmarks = !showBookmarks },
            onShowHistory = { showHistory = !showHistory }
        )

        // ===== 内容区 =====
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val activeTab = tabManager.getTab(activeTabId)
            if (activeTab != null) {
                val url = activeTab.url
                if (url == "anwind://home" || url.isEmpty()) {
                    BrowserHomePage(
                        onNavigate = { target ->
                            val finalUrl = normalizeUrl(target)
                            tabManager.getTab(activeTabId)?.loadUrl(finalUrl)
                            addressInput = finalUrl
                            scope0.launch {
                                withContext(Dispatchers.IO) {
                                    app.database.historyDao().insert(
                                        HistoryEntity(title = finalUrl, url = finalUrl)
                                    )
                                }
                            }
                        }
                    )
                } else {
                    WebViewContainer(
                        url = url,
                        onUrlChanged = { newUrl ->
                            addressInput = newUrl
                            activeTab.url = newUrl
                            activeTab.title = newUrl
                            scope0.launch {
                                withContext(Dispatchers.IO) {
                                    app.database.historyDao().insert(
                                        HistoryEntity(title = newUrl, url = newUrl)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        // ===== 书签/历史侧边面板 =====
        if (showBookmarks) {
            BookmarksPanel(
                onSelect = { url ->
                    addressInput = url
                    tabManager.getTab(activeTabId)?.loadUrl(url)
                    showBookmarks = false
                },
                onClose = { showBookmarks = false }
            )
        }
        if (showHistory) {
            HistoryPanel(
                onSelect = { url ->
                    addressInput = url
                    tabManager.getTab(activeTabId)?.loadUrl(url)
                    showHistory = false
                },
                onClose = { showHistory = false }
            )
        }
    }
}

/**
 * WebView 容器：渲染网页。
 *
 * 同时支持 http(s):// 和 content:// (本地 HTML 文件)
 */
@Composable
private fun WebViewContainer(
    url: String,
    onUrlChanged: (String) -> Unit
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val newUrl = request.url.toString()
                        onUrlChanged(newUrl)
                        return false
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        // 支持本地 content:// URI 的 HTML 文件读取
                        val uri = request.url
                        if (uri.scheme == "content") {
                            return try {
                                val mime = ctx.contentResolver.getType(uri) ?: "text/html"
                                val stream = ctx.contentResolver.openInputStream(uri)
                                WebResourceResponse(mime, "UTF-8", stream)
                            } catch (_: Exception) { null }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                loadUrl(url)
            }
        },
        update = { webview ->
            // 仅当URL不同时才加载，避免无限循环
            if (webview.url != url && !url.startsWith("anwind://")) {
                webview.loadUrl(url)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
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
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                val q = searchText.trim()
                if (q.isNotEmpty()) {
                    val url = if (q.startsWith("http")) q
                              else "https://www.bing.com/search?q=" + Uri.encode(q)
                    onNavigate(url)
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
    onShowHistory: () -> Unit
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
                modifier = Modifier.fillMaxWidth()
            )
        }

        IconButton(onClick = onGo, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.NavigateNext, contentDescription = "Go",
                tint = if (theme.isDark) Color.White else Color.Black)
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
    if (input.contains(".") && !input.contains(" ")) return "https://$input"
    return "https://www.bing.com/search?q=" + android.net.Uri.encode(input)
}
