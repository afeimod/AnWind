package com.anwind.apps.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.view.View
import android.view.ViewGroup
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
import com.anwind.AnWindApp
import com.anwind.data.db.entity.HistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 浏览器内核引擎：WebView 的创建 / 配置 / 销毁，以及 WebViewClient、WebChromeClient。
 *
 * v2.8 重构要点：
 * 1. 移除 setLayerType(LAYER_TYPE_HARDWARE) —— WebView 默认已硬件加速，
 *    强制离屏硬件层在 MIUI / 部分 GPU 驱动上会导致"页面加载完成但首帧黑屏"。
 *    （录屏证实：DOM 已加载、标题已更新，但 WebView 永不绘制。）
 * 2. onCreateWindow 不再用"临时 WebView + 5 秒销毁"拦截 —— 那样：
 *    - 表单 POST / target=_blank 提交（百度"百度一下"）不回调
 *      shouldOverrideUrlLoading，导致点击完全没有反应；
 *    - 销毁临时 WebView 触发 cr_AwContents "Application attempted to call on
 *      a destroyed WebView"。
 *    现在：弹出的 WebView 直接作为新标签的【真实 WebView】通过 transport 交给
 *    Chromium，导航（GET/POST/JS 重定向）在真实标签中完成，全场景兼容。
 *
 * v2.14 灰屏修复（参考 gamehtml 容器方案 + 灰屏日志分析）：
 * 1. 渲染模式可切换：hardware（默认）/ software（软件渲染兼容）。
 *    日志特征：MIUI ForceDarkHelper 初始化 + 沙盒进程 RSS 飙到 400MB，
 *    页面有声曾但 GPU 合成失败（Surface 不上屏）→ 灰屏。
 * 2. 自动灰屏检测回退：onPageFinished 后采样 48px 缩略图，连续 2 次判定
 *    "整面单色"（方差≈0）→ 自动切换软件渲染 + reload + 持久化记忆。
 * 3. 禁用 WebView 算法暗色（MIUI 强制深色不干预网页渲染）。
 */
object BrowserEngine {

    /** 桌面版 Chrome UA（不含 Mobile/Android，让服务器返回 PC 版页面） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 系统默认（移动版）UA —— 首次创建 WebView 时捕获，供手机模式恢复 */
    @Volatile
    private var defaultMobileUa: String? = null

    private const val TAG_DESTROYED = "anwind_webview_destroyed"

    fun desiredUa(uaMode: String): String =
        if (uaMode == "desktop") DESKTOP_UA else (defaultMobileUa ?: DESKTOP_UA)

    /**
     * 获取（必要时创建）标签专属 WebView。
     *
     * - 已有缓存：直接复用（切换标签回来时保留页面状态/滚动位置/视频进度），
     *   并静默同步 UA 设置。
     * - 没有缓存：完整配置并绑定 client。
     */
    fun ensureWebView(context: Context, tab: BrowserTab, manager: TabManager): WebView {
        val existing = tab.webView
        if (existing != null) {
            applyUa(existing, manager.uaMode)
            applyRenderMode(existing, manager.renderMode)
            return existing
        }
        val wv = WebView(context)
        if (defaultMobileUa == null) {
            defaultMobileUa = wv.settings.userAgentString
        }
        configure(wv, tab, manager)
        tab.webView = wv
        return wv
    }

    /**
     * v2.14：应用渲染模式。
     * - hardware：默认硬件加速（LAYER_TYPE_NONE，交由窗口系统合成）
     * - software：软件渲染（修复部分 MIUI / GPU 驱动上页面有声曾但灰屏不合成）
     */
    fun applyRenderMode(wv: WebView, mode: String) {
        val layerType = if (mode == "software") {
            View.LAYER_TYPE_SOFTWARE
        } else {
            View.LAYER_TYPE_NONE
        }
        if (wv.layerType != layerType) {
            try {
                wv.setLayerType(layerType, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun applyUa(wv: WebView, uaMode: String) {
        val desired = desiredUa(uaMode)
        if (wv.settings.userAgentString != desired) {
            try {
                wv.settings.userAgentString = desired
            } catch (_: Exception) {
            }
        }
    }

    private fun configure(wv: WebView, tab: BrowserTab, manager: TabManager) {
        val ctx = wv.context
        wv.settings.apply {
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
            // 多窗口模式：window.open / target=_blank → onCreateWindow
            // （弹窗作为真实新标签处理，见 TabChromeClient.onCreateWindow）
            setSupportMultipleWindows(true)
            defaultTextEncodingName = "UTF-8"
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            setGeolocationEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            textZoom = 100
            userAgentString = desiredUa(manager.uaMode)
        }

        // ⚠️ v2.8：不再调用 setLayerType(LAYER_TYPE_HARDWARE)。
        // WebView 自身默认硬件加速；强制加离屏硬件层在部分设备（MIUI/部分 GPU 驱动）
        // 上首帧不合成，表现为页面加载完成（标题更新）但一直黑屏。

        // v2.14：渲染模式（软件渲染修复灰屏，见 applyRenderMode）
        applyRenderMode(wv, manager.renderMode)

        // v2.14：禁用算法暗色 —— MIUI 强制深色会介入 WebView 渲染管线，
        // 配合 GPU 合成问题放大灰屏概率（灰屏日志出现 ForceDarkHelperStubImpl 初始化）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { wv.settings.isAlgorithmicDarkeningAllowed = false }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            runCatching { wv.settings.forceDark = WebSettings.FORCE_DARK_OFF }
        }

        // 启用 WebView 内部数据库 (WebStorage) 自动管理
        WebStorage.getInstance()
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)
        cookieManager.flush()

        // 文件下载监听（很多站点提供 APK/ZIP/图片下载）
        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
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

        wv.webViewClient = TabWebViewClient(ctx, tab, manager)
        wv.webChromeClient = TabChromeClient(ctx, tab, manager)
    }

    /**
     * 安全销毁 WebView（幂等）：
     * onPause 暂停媒体 → stopLoading 中止加载 → 脱离视图树 → destroy 释放原生资源。
     * （保持旧版"浏览器关了还在响"修复效果，且不会对 attached 视图直接 destroy）
     */
    fun destroyWebView(wv: WebView) {
        if (wv.tag == TAG_DESTROYED) return
        try {
            wv.tag = TAG_DESTROYED
        } catch (_: Exception) {
        }
        try {
            wv.onPause()
        } catch (_: Exception) {
        }
        try {
            wv.stopLoading()
        } catch (_: Exception) {
        }
        (wv.parent as? ViewGroup)?.removeView(wv)
        try {
            wv.destroy()
        } catch (_: Exception) {
            // 某些设备上 destroy() 可能抛 IllegalStateException，吞掉即可
        }
    }

    /** 记录历史（Room）。所有主导航统一在 onPageStarted 记录，天然去重于 update 块。 */
    internal fun recordHistory(url: String?) {
        if (url.isNullOrEmpty() || url == "about:blank" || url.startsWith("data:")) return
        engineScope.launch {
            try {
                AnWindApp.get().database.historyDao()
                    .insert(HistoryEntity(title = url, url = url))
            } catch (_: Exception) {
            }
        }
    }

    // ====================================================================
    // WebViewClient：每个标签一次性创建，通过 manager 路由 UI 回调
    // ====================================================================
    private class TabWebViewClient(
        private val ctx: Context,
        private val tab: BrowserTab,
        private val manager: TabManager
    ) : WebViewClient() {

        private fun isActive() = manager.activeTabId == tab.id

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val newUrl = request.url.toString()
            // 处理特殊 scheme：intent://, weixin://, alipays://, mailto: 等
            val scheme = request.url.scheme ?: "http"
            if (scheme !in setOf("http", "https", "content", "file", "about", "javascript", "data")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, request.url)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(ctx, "未安装可打开 $scheme 应用的程序", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            // 在当前 WebView 内继续加载：登记 lastRequestedUrl 防 update 块重复加载
            tab.lastRequestedUrl = newUrl
            tab.url = newUrl
            if (isActive()) {
                manager.ui?.onUrlChanged(newUrl)
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null) {
                // 登记 lastRequestedUrl：页面内部导航（含弹窗首跳）后
                // update 块不会重复 loadUrl 把页面拉回旧地址
                tab.lastRequestedUrl = url
                if (url != tab.url) {
                    tab.url = url
                    if (isActive()) {
                        manager.ui?.onUrlSync(url)
                    }
                }
                // 后退/前进/刷新不写入历史
                val suppress = tab.suppressNextHistory
                tab.suppressNextHistory = false
                if (!suppress) {
                    recordHistory(url)
                }
            }
            view?.let {
                tab.canGoBack = it.canGoBack()
                tab.canGoForward = it.canGoForward()
            }
            if (isActive()) {
                manager.ui?.onError(null)
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.let {
                tab.canGoBack = it.canGoBack()
                tab.canGoForward = it.canGoForward()
            }
            // v2.14：灰屏自动检测（仅硬件加速模式，软件渲染已是回退态）
            if (manager.renderMode == "hardware" && url != null &&
                url != "about:blank" && !url.startsWith("data:")
            ) {
                scheduleBlankDetection(view)
            }
        }

        // ====================================================================
        // v2.14 灰屏自动检测回退（参考 gamehtml 容器思路）
        //
        // 原理：页面加载完成（有标题、音频在播）但 GPU 合成失败时，把 WebView
        // draw 到 48px 宽的软件画布上得到的也是整面单色。采样像素亮度方差≈0
        // 即判定疑似灰屏；连续 2 次（间隔 1.6s）确认后自动切换软件渲染并重载。
        // 正常网页缩略图必有文字/图片结构，方差远大于阈值，不会误判。
        // ====================================================================
        private val blankCheckDelayMs = 1600L

        private fun scheduleBlankDetection(view: WebView?) {
            if (view == null || view.tag == TAG_DESTROYED) return
            val weak = WeakReference(view)
            view.postDelayed({
                val wv = weak.get() ?: return@postDelayed
                if (wv.tag == TAG_DESTROYED) return@postDelayed
                val isBlank = runCatching { isViewBlank(wv) }.getOrDefault(false)
                if (!isBlank) {
                    tab.blankStrikes = 0
                    return@postDelayed
                }
                tab.blankStrikes++
                if (tab.blankStrikes >= 2) {
                    // 两次确认灰屏：自动回退软件渲染 + 持久化 + 重载
                    tab.blankStrikes = 0
                    manager.renderMode = "software"
                    applyRenderMode(wv, "software")
                    engineScope.launch {
                        runCatching { AnWindApp.get().settingsStore.setBrowserRenderMode("software") }
                    }
                    Toast.makeText(
                        ctx,
                        "检测到页面灰屏，已自动切换为软件渲染并重新加载",
                        Toast.LENGTH_LONG
                    ).show()
                    runCatching { wv.reload() }
                } else {
                    // 首次疑似：延迟后复检一次，避免纯色开场页误判
                    scheduleBlankDetection(wv)
                }
            }, blankCheckDelayMs)
        }

        /** 采样 48px 缩略图，判定是否整面单色（灰/白/黑） */
        private fun isViewBlank(wv: WebView): Boolean {
            if (wv.width <= 0 || wv.height <= 0) return false
            val w = 48
            val h = maxOf(1, 48 * wv.height / wv.width)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.save()
            canvas.scale(w.toFloat() / wv.width, h.toFloat() / wv.height)
            wv.draw(canvas)
            canvas.restore()
            var rSum = 0f; var gSum = 0f; var bSum = 0f
            var n = 0
            for (x in 0 until w step 2) {
                for (y in 0 until h step 2) {
                    val p = bmp.getPixel(x, y)
                    rSum += (p shr 16 and 0xFF); gSum += (p shr 8 and 0xFF); bSum += (p and 0xFF)
                    n++
                }
            }
            if (n == 0) return false
            val rAvg = rSum / n; val gAvg = gSum / n; val bAvg = bSum / n
            // 平均色亮度方差：真实网页缩略图 > 6，整面单色 < 2
            var variance = 0f
            for (x in 0 until w step 2) {
                for (y in 0 until h step 2) {
                    val p = bmp.getPixel(x, y)
                    val lum = 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
                    val avg = 0.299f * rAvg + 0.587f * gAvg + 0.114f * bAvg
                    variance += (lum - avg) * (lum - avg)
                }
            }
            variance /= n
            bmp.recycle()
            return variance < 4f
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            // 仅主框架错误才提示；忽略 -1（未知/取消）与 DNS 解析失败（WebView 会自行重试）
            if (request?.isForMainFrame == true && isActive()) {
                val code = error?.errorCode ?: -1
                if (code != -1 && code != WebViewClient.ERROR_HOST_LOOKUP) {
                    manager.ui?.onError(error?.description?.toString() ?: "加载失败")
                }
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            // 支持本地 content:// URI 的 HTML 文件读取
            val uri = request.url
            if (uri.scheme == "content") {
                return try {
                    val mime = ctx.contentResolver.getType(uri) ?: "text/html"
                    val stream = ctx.contentResolver.openInputStream(uri)
                    WebResourceResponse(mime, "UTF-8", stream)
                } catch (_: Exception) {
                    null
                }
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    // ====================================================================
    // WebChromeClient：多标签弹窗 + JS 弹窗 + 视频全屏 + 文件选择
    // ====================================================================
    private class TabChromeClient(
        private val ctx: Context,
        private val tab: BrowserTab,
        private val manager: TabManager
    ) : WebChromeClient() {

        private fun isActive() = manager.activeTabId == tab.id

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (isActive()) {
                manager.ui?.onProgressChanged(newProgress)
            }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            if (!title.isNullOrEmpty()) {
                tab.title = title
                if (isActive()) {
                    manager.ui?.onTitleChanged(title)
                }
            }
        }

        /**
         * window.open / target=_blank 链接 / 新窗口表单提交（百度"百度一下"）统一入口。
         *
         * v2.8：弹出的 WebView 直接作为【新标签的真实 WebView】：
         * 1) 创建 WebView 并完整配置（client 绑定到新 tab）
         * 2) 登记为新标签并激活 —— Compose 随即将其上屏
         * 3) 通过 transport 交给 Chromium —— 表单 POST / JS 重定向等
         *    一切导航在真实标签中自然完成（旧版临时 WebView 拦截不到 POST，
         *    导致"点击百度一下没反应"）
         * 4) 页面加载后由其自身 client 同步 tab.url / 标题 / 地址栏
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            if (resultMsg == null) return false
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popupWebView = WebView(ctx)
            val popupTab = manager.openTab("about:blank", popupWebView)
            configure(popupWebView, popupTab, manager)
            transport.webView = popupWebView
            resultMsg.sendToTarget()
            return true
        }

        /** window.close()：JS 自动关闭的弹窗标签 → 关闭对应标签 */
        override fun onCloseWindow(window: WebView?) {
            if (tab.isPopup) {
                manager.closeTab(tab.id)
            }
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
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: JsPromptResult?
        ): Boolean {
            result?.confirm(defaultValue)
            return true
        }

        /**
         * HTML5 全屏（视频站点播放器的全屏按钮）：
         * 把页面提交的自定义视图挂到 DecorView 最顶层 → 盖过任务栏/状态栏，
         * 真正满屏播放（v2.8 修复，旧版仅调 super() 等于什么都不做）。
         */
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null) {
                callback?.onCustomViewHidden()
                return
            }
            manager.showCustomView(view, callback)
        }

        override fun onShowCustomView(
            view: View?,
            requestedOrientation: Int,
            callback: CustomViewCallback?
        ) {
            // 带方向的变体同样处理（忽略页面请求的方向，遵循用户显示设置）
            onShowCustomView(view, callback)
        }

        override fun onHideCustomView() {
            manager.hideCustomView()
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
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    (ctx as? android.app.Activity)?.startActivityForResult(intent, 0)
                }
            } catch (_: Exception) {
            }
            return true
        }
    }
}
