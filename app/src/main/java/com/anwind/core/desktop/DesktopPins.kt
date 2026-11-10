package com.anwind.core.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.anwind.AnWindApp
import com.anwind.core.window.AppDef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3.3.10（用户问题 2）：桌面固定应用集合 + 开始菜单拖拽到桌面。
 *
 * ## 背景
 *
 * v3.3.10 起桌面只保留 6 个默认图标（此电脑/浏览器/终端/X11 桌面/
 * 云音乐/Wine 容器，见各 AppDef 的 pinnedToDesktop=false 精简），
 * 其余应用全部收进左下角开始菜单。本对象承担两件事：
 *
 *  1. **桌面固定集合管理**（[DesktopPins]）：
 *     - 默认集合 = v3.3.9 及以前 pinnedToDesktop=true 的 6 个应用；
 *     - 用户从开始菜单【拖拽到桌面】→ 集合追加该应用（持久化到
 *       SettingsStore.desktopPinnedApps）；
 *     - 桌面图标右键【从桌面移除】→ 集合剔除（应用回到仅菜单可见）；
 *     - 集合为空串 = 恢复默认 6 件套（与"全部移除"语义区分开）。
 *
 *  2. **跨组件拖拽总线**（[DesktopDragState]）：
 *     开始菜单瓦片（StartMenu.BuiltInTile）长按拖出 → 菜单关闭、
 *     拖拽影子跟手（DesktopEnvironment 顶层渲染）→ 桌面区域松手 →
 *     [DesktopPins.pin] + 落位坐标回调（DesktopIconGrid 写
 *     IconPos.Free）。开始菜单与桌面网格分属不同 Compose 子树，
 *     手势无法直接传递 —— 用全局单例状态总线桥接（数值状态驱动
 *     影子层渲染，松手事件驱动落位）。
 */
object DesktopPins {

    /** v3.3.10 桌面默认 6 件套（用户点名保留）。 */
    val DEFAULT_PINS: List<String> = listOf(
        "file_explorer",   // 此电脑
        "browser",         // 浏览器
        "terminal",        // 终端
        "x11",             // X11 桌面
        "music",           // 云音乐
        "wine-containers"  // Bionic 容器（v3.3.39 更名，id 不变）
    )

    /**
     * 解析持久化的固定集合：空串/未配置 → 默认 6 件套；
     * 非空 → 按记录序（仅保留当前仍注册的应用，防卸载残留）。
     */
    fun parse(raw: String?): List<String> {
        val ids = raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (ids.isEmpty()) return DEFAULT_PINS
        val registered = com.anwind.core.window.AppRegistry.all().map { it.id }.toSet()
        return ids.filter { it in registered }
    }

    /** 集合是否包含指定应用（菜单瓦片的"已在桌面"标记）。 */
    fun isPinned(raw: String?, appId: String): Boolean = parse(raw).contains(appId)

    /** 追加一个应用到桌面集合（幂等）。 */
    fun pin(appId: String, scope: CoroutineScope, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val app = AnWindApp.get()
                val current = parse(runCatching { app.settingsStore.desktopPinnedApps.first() }.getOrDefault(""))
                if (!current.contains(appId)) {
                    app.settingsStore.setDesktopPinnedApps((current + appId).joinToString(","))
                }
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** 从桌面集合剔除一个应用（剔到空 → 写单逗号占位，不回默认套）。 */
    fun unpin(appId: String, scope: CoroutineScope, onDone: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val app = AnWindApp.get()
                val current = parse(runCatching { app.settingsStore.desktopPinnedApps.first() }.getOrDefault(""))
                val next = current.filter { it != appId }
                // 全部移除时写占位（区别于"恢复默认"的空串）
                app.settingsStore.setDesktopPinnedApps(
                    if (next.isEmpty()) "," else next.joinToString(",")
                )
            }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** 恢复默认 6 件套（桌面右键菜单"恢复默认图标"）。 */
    fun resetToDefault(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching { AnWindApp.get().settingsStore.setDesktopPinnedApps("") }
        }
    }
}

/**
 * 跨组件拖拽总线：开始菜单瓦片 → 桌面落位。
 *
 * 生命周期：begin（长按拖出，菜单关闭）→ update（跟手，root 坐标 px）
 * → end（松手：在桌面回调区触发落位）/ cancel（拖到界外/取消）。
 * [onDrop] 由桌面侧（DesktopEnvironment 注入）注册，坐标即松手点。
 */
object DesktopDragState {
    /** 正在拖拽的应用定义（null = 空闲）。 */
    var dragging: AppDef? by mutableStateOf(null)

    /** 拖拽影子中心坐标（root/window 坐标，px）。 */
    var x: Float by mutableStateOf(0f)
    var y: Float by mutableStateOf(0f)

    /** 桌面侧注册的落位回调（appId + 松手坐标）。 */
    var onDrop: ((appId: String, x: Float, y: Float) -> Unit)? by mutableStateOf(null)

    /** 开始拖拽（StartMenu 瓦片长按拖出时调用）。 */
    fun begin(app: AppDef, startX: Float, startY: Float) {
        dragging = app
        x = startX
        y = startY
    }

    /** 拖动跟手。 */
    fun update(px: Float, py: Float) {
        x = px
        y = py
    }

    /** 松手：桌面回调存在且命中 → 落位；否则静默取消。 */
    fun end() {
        val app = dragging
        dragging = null
        if (app != null) {
            onDrop?.invoke(app.id, x, y)
        }
    }

    /** 取消（不触发落位）。 */
    fun cancel() {
        dragging = null
    }
}
