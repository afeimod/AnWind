package com.anwind.core.window

import java.util.UUID

/**
 * 应用启动模式：
 * - FULLSCREEN: 占满整个工作区（任务栏上方），适合浏览器/文件管理器
 * - FLOATING: 可拖拽/缩放/最小化的浮动窗口，适合记事本/计算器/设置
 */
enum class LaunchMode { FULLSCREEN, FLOATING }

/**
 * 窗口状态：单个打开的窗口
 */
data class WindowState(
    val id: String = UUID.randomUUID().toString(),
    val appId: String,
    val title: String,
    val launchMode: LaunchMode,
    // 浮动窗口位置/大小（FULLSCREEN 模式忽略）
    var x: Int = 60,
    var y: Int = 80,
    var width: Int = 720,
    var height: Int = 520,
    var isMinimized: Boolean = false,
    var isMaximized: Boolean = false,
    // 保存最大化前的位置，用于还原
    var prevX: Int = x,
    var prevY: Int = y,
    var prevWidth: Int = width,
    var prevHeight: Int = height,
    var zIndex: Int = 0,
    // 应用专属数据（如浏览器要打开的URL、文件管理器要打开的路径）
    val launchArgs: Map<String, String> = emptyMap(),
    // 关闭时回调清理资源
    var onClose: (() -> Unit)? = null
) {
    /** 当前窗口是否可见（未最小化） */
    val isVisible: Boolean get() = !isMinimized
}

/**
 * 窗口管理器：单例，管理所有打开的窗口。
 *
 * 设计要点：
 * - 全部窗口存在 MutableList 中，按 zIndex 排序
 * - 顶部窗口 zIndex 最大
 * - 任务栏点击最小化/还原
 * - 同一个 appId 可重复打开（允许多窗口）
 */
class WindowManager {

    private val _windows = mutableListOf<WindowState>()
    val windows: List<WindowState> get() = _windows.toList()

    private var listeners = mutableListOf<() -> Unit>()

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    /** 打开一个新窗口。 */
    fun open(
        appId: String,
        title: String,
        launchMode: LaunchMode,
        launchArgs: Map<String, String> = emptyMap(),
        initialX: Int = 80 + (_windows.size * 30) % 200,
        initialY: Int = 80 + (_windows.size * 30) % 150,
        initialWidth: Int = 720,
        initialHeight: Int = 520
    ): String {
        val z = (_windows.maxOfOrNull { it.zIndex } ?: 0) + 1
        val state = WindowState(
            appId = appId,
            title = title,
            launchMode = launchMode,
            x = initialX,
            y = initialY,
            width = initialWidth,
            height = initialHeight,
            prevX = initialX, prevY = initialY,
            prevWidth = initialWidth, prevHeight = initialHeight,
            zIndex = z,
            launchArgs = launchArgs
        )
        _windows.add(state)
        notifyChanged()
        return state.id
    }

    fun close(windowId: String) {
        val idx = _windows.indexOfFirst { it.id == windowId }
        if (idx >= 0) {
            _windows[idx].onClose?.invoke()
            _windows.removeAt(idx)
            notifyChanged()
        }
    }

    fun focus(windowId: String) {
        val maxZ = _windows.maxOfOrNull { it.zIndex } ?: 0
        _windows.firstOrNull { it.id == windowId }?.let {
            it.zIndex = maxZ + 1
            it.isMinimized = false
            notifyChanged()
        }
    }

    fun minimize(windowId: String) {
        _windows.firstOrNull { it.id == windowId }?.let {
            it.isMinimized = true
            notifyChanged()
        }
    }

    fun toggleMaximize(windowId: String) {
        _windows.firstOrNull { it.id == windowId }?.let { w ->
            if (w.isMaximized) {
                w.x = w.prevX; w.y = w.prevY
                w.width = w.prevWidth; w.height = w.prevHeight
                w.isMaximized = false
            } else {
                w.prevX = w.x; w.prevY = w.y
                w.prevWidth = w.width; w.prevHeight = w.height
                w.isMaximized = true
            }
            notifyChanged()
        }
    }

    fun move(windowId: String, dx: Int, dy: Int) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let {
            it.x += dx
            it.y += dy
            // 不直接 notifyChanged，拖拽中频繁通知会卡顿
        }
    }

    fun resize(windowId: String, dw: Int, dh: Int) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let {
            it.width = (it.width + dw).coerceAtLeast(280)
            it.height = (it.height + dh).coerceAtLeast(200)
        }
    }

    /** 拖拽结束后调用，触发 UI 刷新 */
    fun commitChanges() {
        notifyChanged()
    }

    /** 任务栏点击某窗口的行为：可见则最小化，最小化则还原+聚焦 */
    fun taskbarClick(windowId: String) {
        val w = _windows.firstOrNull { it.id == windowId } ?: return
        if (w.isMinimized) {
            focus(windowId)
        } else {
            // 如果是顶部窗口，最小化；否则聚焦
            val topZ = _windows.maxOfOrNull { it.zIndex } ?: 0
            if (w.zIndex == topZ) {
                minimize(windowId)
            } else {
                focus(windowId)
            }
        }
    }

    fun topWindow(): WindowState? = _windows
        .filter { it.isVisible }
        .maxByOrNull { it.zIndex }

    fun windowsForApp(appId: String): List<WindowState> =
        _windows.filter { it.appId == appId }

    fun closeAll() {
        _windows.forEach { it.onClose?.invoke() }
        _windows.clear()
        notifyChanged()
    }

    companion object {
        // 全局单例（应用生命周期）
        @Volatile private var INSTANCE: WindowManager? = null
        fun get(): WindowManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: WindowManager().also { INSTANCE = it }
        }
    }
}
