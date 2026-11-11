package com.anwind.core.window

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 标题栏附加按钮（v3.3.42）。
 *
 * 供需要在标题栏（非菜单、非最小化/最大化/关闭）放置专属按钮的应用使用 ——
 * 例如 X11 桌面用它在标题栏放"隐藏底部控制条"按钮，让游戏画面占满更大区域。
 */
data class TitleBarExtraButton(
    /**
     * 按钮图标（Material Icon）。
     *
     * 刻意做成 **函数** 而非常量：图标可随状态变化（如 X11 的
     * "隐藏/显示底部控制条"按钮在隐藏态显示"显示"图标）。
     * 该函数在组合期被调用，可安全读取 Compose 状态。
     */
    val icon: () -> ImageVector,
    /** 无障碍描述 / 调试用名。 */
    val label: String,
    /** 点击回调（Context 供弹窗/启动器使用）。 */
    val onClick: (Context) -> Unit
)

/**
 * 应用定义：注册到 AppRegistry 中，桌面/开始菜单/任务栏从中读取。
 *
 * @property id            唯一ID，例如 "browser" / "notepad"
 * @property displayName   显示名称（中文）
 * @property iconAsset     assets 中的图标路径（PNG/SVG）
 * @property launchMode    启动模式
 * @property defaultWidth  浮动模式默认宽度
 * @property defaultHeight 浮动模式默认高度
 * @property pinnedToTaskbar  是否固定到任务栏
 * @property pinnedToDesktop  是否在桌面显示图标
 * @property onTitleBarLongPress  标题栏长按回调（v2.22.4 fix11c：X11 用它
 *                                弹出"X11 设置"面板；null = 无长按行为）
 * @property onMenuButton         标题栏菜单按钮回调（v3.1.7：定义即在
 *                                最小化按钮左侧显示菜单按钮；X11 桌面
 *                                用它调出 X11 设置菜单，替代难发现的
 *                                长按手势）
 * @property titleBarExtraButtons 标题栏附加按钮（v3.3.42：渲染在菜单按钮
 *                                左侧；X11 桌面用它放"隐藏控制条"按钮）
 * @property content       该应用的 Composable 内容，接收 WindowContentScope 参数
 */
data class AppDef(
    val id: String,
    val displayName: String,
    val iconAsset: String,
    val launchMode: LaunchMode,
    val defaultWidth: Dp = 720.dp,
    val defaultHeight: Dp = 520.dp,
    val pinnedToTaskbar: Boolean = false,
    val pinnedToDesktop: Boolean = true,
    val onTitleBarLongPress: ((Context) -> Unit)? = null,
    val onMenuButton: ((Context) -> Unit)? = null,
    val titleBarExtraButtons: List<TitleBarExtraButton> = emptyList(),
    val content: @Composable (WindowContentScope) -> Unit
)

/**
 * 窗口内容的作用域，提供窗口状态和工具。
 */
class WindowContentScope(
    val windowState: WindowState,
    val onClose: () -> Unit,
    val onTitleChange: (String) -> Unit
)

/**
 * 应用注册表：全局单例，所有内置应用启动时注册自己。
 */
object AppRegistry {
    private val apps = mutableMapOf<String, AppDef>()

    fun register(app: AppDef) {
        apps[app.id] = app
    }

    fun get(id: String): AppDef? = apps[id]

    fun all(): List<AppDef> = apps.values.toList()

    fun desktopApps(): List<AppDef> = apps.values.filter { it.pinnedToDesktop }

    fun taskbarApps(): List<AppDef> = apps.values.filter { it.pinnedToTaskbar }
}
