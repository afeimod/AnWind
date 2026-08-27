package com.anwind.core.window

import java.util.UUID

/**
 * 应用启动模式：
 * - FULLSCREEN: 占满整个工作区（任务栏上方），适合浏览器/文件管理器
 * - FLOATING: 可拖拽/缩放/最小化的浮动窗口，适合记事本/计算器/设置
 */
enum class LaunchMode { FULLSCREEN, FLOATING }

/**
 * 调整大小的方向：8 个边缘 + 4 个角落 = 12 个区域（但实际只取 8 个）
 * - LEFT/RIGHT/TOP/BOTTOM: 单边调整
 * - TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT: 角落同时调整两个方向
 */
enum class ResizeEdge {
    LEFT, RIGHT, TOP, BOTTOM,
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    /** 是否影响左边（即 x 坐标变化） */
    val affectsLeft: Boolean
        get() = this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT

    /** 是否影响右边（即 width 变化） */
    val affectsRight: Boolean
        get() = this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT

    /** 是否影响顶边（即 y 坐标变化） */
    val affectsTop: Boolean
        get() = this == TOP || this == TOP_LEFT || this == TOP_RIGHT

    /** 是否影响底边（即 height 变化） */
    val affectsBottom: Boolean
        get() = this == BOTTOM || this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/**
 * 窗口状态：单个打开的窗口
 *
 * 视觉重构后：
 * - 支持拖拽移动（dragOffset 提交一次性）
 * - 支持 8 方向调整大小
 * - 支持最小化/最大化
 * - 支持调整 z-index 焦点
 */
data class WindowState(
    val id: String = UUID.randomUUID().toString(),
    val appId: String,
    var title: String,
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
 * 视觉重构：
 * - 全部窗口存在 MutableList 中，按 zIndex 排序
 * - 顶部窗口 zIndex 最大
 * - 任务栏点击最小化/还原
 * - 同一个 appId 可重复打开（允许多窗口）
 * - 新增 8 方向调整大小逻辑，支持从任意边缘/角落缩放
 * - 调整大小时遵循最小尺寸限制
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

    /** 拖拽窗口标题栏：相对位移加到当前位置 */
    fun move(windowId: String, dx: Int, dy: Int) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let {
            it.x += dx
            it.y += dy
            // 不直接 notifyChanged，拖拽中频繁通知会卡顿
        }
    }

    /**
     * 设置窗口的绝对位置（拖拽时直接设置，避免累加误差）
     * 配合 WindowChrome 的本地 dragOffset 使用：松手时把"窗口原位置 + dragOffset"作为绝对位置提交。
     */
    fun setAbsolutePosition(windowId: String, x: Int, y: Int) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let {
            it.x = x
            it.y = y
        }
    }

    /**
     * 调整大小：根据指定的边缘方向，应用 delta 到对应的边。
     * 支持 8 个方向（4 边 + 4 角），自动遵守最小尺寸 280x180。
     */
    fun resize(
        windowId: String,
        edge: ResizeEdge,
        dx: Int,
        dy: Int,
        workAreaWidth: Int = Int.MAX_VALUE,
        workAreaHeight: Int = Int.MAX_VALUE
    ) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let { w ->
            val minWidth = 280
            val minHeight = 180

            // 处理左右边
            if (edge.affectsLeft) {
                // 左边移动：x 和 width 同时变化
                val newWidth = w.width - dx
                if (newWidth >= minWidth) {
                    w.x += dx
                    w.width = newWidth
                } else {
                    // 触底最小宽度：把 x 推到 width=minWidth 的位置
                    val fixDx = w.width - minWidth
                    w.x += fixDx
                    w.width = minWidth
                }
            }
            if (edge.affectsRight) {
                val newWidth = w.width + dx
                w.width = newWidth.coerceIn(minWidth, (workAreaWidth - w.x).coerceAtLeast(minWidth))
            }
            // 处理上下边
            if (edge.affectsTop) {
                val newHeight = w.height - dy
                if (newHeight >= minHeight) {
                    w.y += dy
                    w.height = newHeight
                } else {
                    val fixDy = w.height - minHeight
                    w.y += fixDy
                    w.height = minHeight
                }
            }
            if (edge.affectsBottom) {
                val newHeight = w.height + dy
                w.height = newHeight.coerceIn(minHeight, (workAreaHeight - w.y).coerceAtLeast(minHeight))
            }
            // 不立即 notifyChanged，避免调整大小时频繁重组
        }
    }

    /** 设置窗口的绝对尺寸（备用接口） */
    fun setAbsoluteSize(windowId: String, width: Int, height: Int) {
        _windows.firstOrNull { it.id == windowId && !it.isMaximized }?.let {
            it.width = width.coerceAtLeast(280)
            it.height = height.coerceAtLeast(180)
        }
    }

    /** 拖拽/调整大小结束后调用，触发 UI 刷新 */
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
