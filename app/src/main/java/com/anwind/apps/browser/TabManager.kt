package com.anwind.apps.browser

import android.webkit.WebView
import androidx.compose.runtime.mutableStateListOf
import java.util.UUID

/**
 * 浏览器标签数据
 */
data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var title: String = "新标签",
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    var webView: WebView? = null
)

/**
 * 标签管理器
 *
 * 注意：WebView 实例由 AndroidView 管理，这里只持有引用用于命令式调用。
 * TabManager 自身只维护标签元数据。
 */
class TabManager {
    private val _tabs = mutableStateListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs

    fun openTab(initialUrl: String): BrowserTab {
        val tab = BrowserTab(url = initialUrl, title = if (initialUrl == "anwind://home") "新标签" else initialUrl)
        _tabs.add(tab)
        return tab
    }

    fun getTab(id: String?): BrowserTab? = _tabs.firstOrNull { it.id == id }

    fun closeTab(id: String) {
        _tabs.firstOrNull { it.id == id }?.let { tab ->
            // WebView 的销毁由 AndroidView 的 dispose 处理
            _tabs.remove(tab)
        }
    }

    fun openOrSwitchHome(activeId: String?): BrowserTab {
        val existing = _tabs.firstOrNull { it.url == "anwind://home" }
        if (existing != null) return existing
        return openTab("anwind://home")
    }
}

/**
 * WebView 操作扩展：通过 command API 触发
 *
 * 注意：loadUrl 扩展不再直接调用 webView?.loadUrl。
 * 原因：直接调用会与 WebViewContainer 的 update 块的 lastRequestedUrl 检查冲突，
 * 导致正在进行的加载被取消重试。现在只更新 tab.url + tab.title，
 * 由调用方设置 addressInput 触发重组，让 update 块统一负责 loadUrl。
 * 对于 home → real URL 跳转，条件分支切换会触发 AndroidView factory 创建新 WebView 并 loadUrl。
 */
fun BrowserTab.loadUrl(url: String) {
    this.url = url
    this.title = url
    // 实际 loadUrl 由 WebViewContainer 的 update 块 / factory 块负责
}

fun BrowserTab.goBack() {
    webView?.let { if (it.canGoBack()) it.goBack() }
}

fun BrowserTab.goForward() {
    webView?.let { if (it.canGoForward()) it.goForward() }
}

fun BrowserTab.refresh() {
    webView?.reload()
}
