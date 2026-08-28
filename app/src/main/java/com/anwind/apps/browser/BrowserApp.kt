package com.anwind.apps.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

/** 桌面版 Chrome UA（不含 Mobile/Android，让服务器返回 PC 版页面） */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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

    // 启动参数：URL / 本地文件
    val launchUrl = scope.windowState.launchArgs["url"]
    val launchType = scope.windowState.launchArgs["type"]

    // 标签管理器
    val tabManager = remember { TabManager() }
    var activeTabId by remember { mutableStateOf<String?>(null) }
    var addressInput by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // 桌面/手机模式（持久化，切换后立即重载当前页生效）
    val uaMode by app.settingsStore.browserUaMode.collectAsState(initial = "desktop")
    fun toggleUaMode() {
        val next = if (uaMode == "desktop") "mobile" else "desktop"
        scope0.launch { app.settingsStore.setBrowserUaMode(next) }
    }
    // 用户设置的主页（默认 AnWind 速度页 anwind://home，符合用户截图 1 的预期）
    val defaultHome by app.settingsStore.defaultBrowserHome.collectAsState(initial = "anwind://home")

    // 启动时打开初始标签
    LaunchedEffect(Unit) {
        val initialUrl = when {
            launchUrl.isNullOrEmpty() -> defaultHome
            launchType == DesktopItemType.SHORTCUT_FILE.name && launchUrl.startsWith("content://") -> launchUrl
            else -> normalizeUrl(launchUrl)
        }
        val tab = tabManager.openTab(initialUrl)
        activeTabId = tab.id
        addressInput = if (initialUrl == "anwind://home") "" else initialUrl
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
                // 返回用户设置的主页（默认 anwind://home 速度页）
                tabManager.getTab(activeTabId)?.loadUrl(defaultHome)
                addressInput = if (defaultHome == "anwind://home") "" else defaultHome
            },
            onGo = {
                val url = normalizeUrl(addressInput)
                val tab = tabManager.getTab(activeTabId)
                if (tab != null) {
                    // 关键修复：不再直接调用 tab.webView?.loadUrl(url)。
                    // 原因：如果在这里直接 loadUrl，update 块在重组时还会因为
                    // url != lastRequestedUrl 再次 loadUrl，导致正在进行的加载被取消重试，
                    // 最终页面无法加载完成。现在让 update 块统一负责 loadUrl，并
                    // 通过 lastRequestedUrl 保证只加载一次。
                    // 对于 home → real URL 跳转：tab.webView 为 null，update 块不会
                    // loadUrl；但条件分支会切换到 WebViewContainer，AndroidView factory
                    // 块会创建新 WebView 并 loadUrl(url)。
                    tab.url = url
                    tab.title = url
                    addressInput = url
                    scope0.launch {
                        withContext(Dispatchers.IO) {
                            app.database.historyDao().insert(
                                HistoryEntity(title = url, url = url)
                            )
                        }
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
            onShowHistory = { showHistory = !showHistory },
            uaMode = uaMode,
            onToggleUaMode = { toggleUaMode() }
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
                            val tab = tabManager.getTab(activeTabId)
                            if (tab != null) {
                                // 关键修复：与 onGo 同理，不直接调用 tab.webView?.loadUrl。
                                // 更新 tab.url + addressInput 触发重组：
                                // - 若原先在 home 页（无 WebView），条件分支切换到 WebViewContainer，
                                //   AndroidView factory 会创建新 WebView 并 loadUrl(url)。
                                // - 若原先已有 WebView，update 块会调用 loadUrl(url) 并更新
                                //   lastRequestedUrl，保证只加载一次。
                                tab.url = finalUrl
                                tab.title = finalUrl
                                addressInput = finalUrl
                                scope0.launch {
                                    withContext(Dispatchers.IO) {
                                        app.database.historyDao().insert(
                                            HistoryEntity(title = finalUrl, url = finalUrl)
                                        )
                                    }
                                }
                            }
                        }
                    )
                } else {
                    WebViewContainer(
                        url = url,
                        tab = activeTab,
                        uaMode = uaMode,
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
                        },
                        onUrlSync = { newUrl ->
                            // 内部导航（后退/前进）触发的 URL 变化：只同步地址栏与 tab.url，不写历史
                            addressInput = newUrl
                            activeTab.url = newUrl
                        },
                        onTitleChanged = { newTitle ->
                            activeTab.title = newTitle
                        },
                        onRetry = { tabManager.getTab(activeTabId)?.refresh() }
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
 * 同时支持 http(s):// 和 content:// (本地 HTML 文件)。
 * 配置了完整的 WebSettings、加载进度、错误提示、JS 弹窗自动确认、标题同步。
 */
@Composable
private fun WebViewContainer(
    url: String,
    tab: BrowserTab,
    uaMode: String = "desktop",
    onUrlChanged: (String) -> Unit,
    onUrlSync: (String) -> Unit = {},
    onTitleChanged: (String) -> Unit = {},
    onRetry: () -> Unit = {}
) {
    val theme = LocalWinTheme.current
    var progress by remember { mutableStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // 记录已应用的 UA，切换模式时重载当前页
    var appliedUa by remember { mutableStateOf(uaMode) }
    // 保存默认移动版 UA，供手机模式恢复（用 holder 避免在 factory 内写 state）
    val mobileUaHolder = remember { arrayOfNulls<String>(1) }
    // 关键修复：跟踪"最后一次请求 WebView 加载的 URL"，避免 update 块在每次重组时
    // 都调用 loadUrl，导致正在进行的加载被反复取消重试，最终页面无法加载完成
    // （"浏览器输入搜索/网址不跳转"Bug）。
    // 该状态在以下时机更新：factory 初始加载 / shouldOverrideUrlLoading / onPageStarted /
    // update 块自身调用 loadUrl 时。这样 update 块只在 URL 确实变化且与最近一次请求不同时
    // 才会真正调用 loadUrl。
    var lastRequestedUrl by remember { mutableStateOf(url) }

    Box(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // 绑定到标签，让工具栏命令（后退/前进/刷新/加载）能操作真实 WebView
                    tab.webView = this
                    // 捕获默认移动版 UA，供手机模式恢复
                    val mobileUa = settings.userAgentString
                    mobileUaHolder[0] = mobileUa
                    // ===== WebSettings 完整配置（参考 gamehtml 项目对游戏网页的兼容）=====
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        // 多窗口支持：让 window.open() 能新建窗口（很多游戏/支付网页依赖）
                        setSupportMultipleWindows(true)
                        // 默认文本编码 UTF-8，避免中文乱码
                        defaultTextEncodingName = "UTF-8"
                        // 桌面模式强制 layout algorithm 以提高文本重排质量
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
                        // 地理位置（部分地图/定位网页依赖）
                        setGeolocationEnabled(true)
                        // 启用 SafeBrowsing（API 26+）
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            safeBrowsingEnabled = true
                        }
                        // 最小字号（避免某些网页字号太小）
                        textZoom = 100
                        // 应用 UA 模式
                        userAgentString = if (uaMode == "desktop") DESKTOP_UA else mobileUa
                    }
                    // 启用 WebView 内部数据库 (WebStorage) 自动管理
                    WebStorage.getInstance()
                    // 硬件加速（很多 HTML5 游戏/动画需要）
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    // Cookie（this 即当前 WebView）
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    cookieManager.flush()

                    // 文件下载监听（很多站点提供 APK/ZIP/图片下载）
                    setDownloadListener { url, userAgent, contentDisposition, mimetype, size ->
                        try {
                            val request = DownloadManager.Request(Uri.parse(url)).apply {
                                setMimeType(mimetype)
                                addRequestHeader("User-Agent", userAgent)
                                setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    contentDisposition?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                                        ?.substringAfter("filename=", "anwind_download")
                                        ?.trim('"') ?: "anwind_download"
                                )
                                setTitle(contentDisposition ?: "下载")
                                if (mimetype == "application/vnd.android.package-archive") {
                                    allowScanningByMediaScanner()
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                } else {
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                                }
                            }
                            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            dm.enqueue(request)
                            Toast.makeText(ctx, "开始下载到 Downloads/", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            // 在当前 WebView 内继续加载，并同步地址栏
                            val newUrl = request.url.toString()
                            // 处理特殊 scheme：intent://, weixin://, alipays://, mailto: 等
                            val scheme = request.url.scheme ?: "http"
                            if (scheme !in setOf("http", "https", "content", "file", "about", "javascript", "data")) {
                                // 外部 scheme：尝试启动外部应用
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "未安装可打开 $scheme 应用的程序", Toast.LENGTH_SHORT).show()
                                }
                                return true
                            }
                            // 在当前 WebView 内继续加载，并同步地址栏
                            // 标记 WebView 正在加载此 URL，避免 update 块重复 loadUrl
                            lastRequestedUrl = newUrl
                            onUrlChanged(newUrl)
                            return false
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            errorMessage = null
                            // 标记 WebView 已开始加载此 URL，避免 update 块重复 loadUrl
                            if (url != null) lastRequestedUrl = url
                            // 同步后退/前进可用性状态
                            view?.let { v ->
                                tab.canGoBack = v.canGoBack()
                                tab.canGoForward = v.canGoForward()
                            }
                            // 如果 WebView 内部加载的 URL 与 tab.url 不同（典型场景：用户点了后退/前进），
                            // 则同步 tab.url 和地址栏，避免 update 块重新 loadUrl 把页面又跳回原来的 URL
                            if (url != null && url != tab.url) {
                                tab.url = url
                                onUrlSync(url)
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 页面加载完成后同步状态，便于工具栏后退/前进按钮实时可用
                            view?.let { v ->
                                tab.canGoBack = v.canGoBack()
                                tab.canGoForward = v.canGoForward()
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            // 仅主框架错误才提示；忽略 -1（未知/取消）与 DNS 解析失败（WebView 会自行重试）
                            if (request?.isForMainFrame == true) {
                                val code = error?.errorCode ?: -1
                                if (code != -1 && code != WebViewClient.ERROR_HOST_LOOKUP) {
                                    errorMessage = error?.description?.toString() ?: "加载失败"
                                }
                            }
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

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (!title.isNullOrEmpty()) onTitleChanged(title)
                        }

                        // 处理 window.open() —— 4399/百度/微博等很多页面依赖
                        // 直接复用当前 WebView：将 src WebView 通过 transport 返回给框架，
                        // 框架会让 src 加载新 URL，随后 onPageStarted 触发地址栏同步。
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            view?.let { src ->
                                resultMsg?.let { msg ->
                                    val transport = msg.obj as? WebView.WebViewTransport
                                    transport?.webView = src
                                    msg.target?.sendToTarget()
                                }
                            }
                            return true
                        }

                        // 自动确认 JS 弹窗，避免网页交互卡住
                        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            result?.confirm()
                            return true
                        }

                        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                            result?.confirm()
                            return true
                        }

                        override fun onJsPrompt(
                            view: WebView?, url: String?, message: String?,
                            defaultValue: String?, result: JsPromptResult?
                        ): Boolean {
                            result?.confirm(defaultValue)
                            return true
                        }

                        // 隐藏 HTML5 全屏视频的默认控制器（让 ExoPlayer 或用户自处理）
                        override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                            // 简单实现：交给 WebView 默认行为
                            super.onShowCustomView(view, callback)
                        }

                        override fun onHideCustomView() {
                            super.onHideCustomView()
                        }

                        // 文件上传回调（input[type=file]）
                        private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            this.filePathCallback = filePathCallback
                            try {
                                val intent = fileChooserParams?.createIntent()
                                if (intent != null) {
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    (ctx as? android.app.Activity)?.startActivityForResult(intent, 0)
                                }
                            } catch (_: Exception) {}
                            return true
                        }
                    }

                    loadUrl(url)
                    // 标记 factory 已请求加载此 URL
                    lastRequestedUrl = url
                }
            },
            update = { webview ->
                // 重新绑定当前标签到共享 WebView，切换标签后命令仍可用
                tab.webView = webview
                // 同步后退/前进可用性
                tab.canGoBack = webview.canGoBack()
                tab.canGoForward = webview.canGoForward()
                // UA 模式切换：立即切换 UA 并重载，让手机/桌面模式页面生效
                if (appliedUa != uaMode) {
                    appliedUa = uaMode
                    webview.settings.userAgentString =
                        if (uaMode == "desktop") DESKTOP_UA else (mobileUaHolder[0] ?: webview.settings.userAgentString)
                    webview.reload()
                    lastRequestedUrl = url
                } else if (!url.startsWith("anwind://") && url != lastRequestedUrl) {
                    // 关键修复：只在"我们请求加载的 URL"与"当前要显示的 URL"不同时才调用 loadUrl。
                    // 之前的判断 webview.url != url 在 WebView 异步加载期间会一直为 true
                    // （webview.url 落后于实际正在加载的 URL），导致每次重组都 loadUrl，
                    // 反复取消正在进行的加载，最终页面加载不完。
                    // 新判断 url != lastRequestedUrl 保证了：只要我们已经请求过加载此 URL
                    // （无论是 factory、shouldOverrideUrlLoading、onPageStarted 还是这里），
                    // 就不会再次 loadUrl，让 WebView 安心完成加载。
                    errorMessage = null
                    webview.loadUrl(url)
                    lastRequestedUrl = url
                }
            },
            onRelease = { tab.webView = null },
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
                modifier = Modifier.weight(1f)
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
    onToggleUaMode: () -> Unit = {}
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
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 显眼 Go/搜索按钮：带主题背景色，确保用户能发现"如何前往"
        // 之前只是 36dp 的图标按钮，与后退/前进等按钮视觉权重一致，用户不易发现。
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
