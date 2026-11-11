package com.anwind.apps.x11

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.TitleBarExtraButton
import com.anwind.core.window.WindowContentScope

/**
 * v2.22.2 fix9.6：内置 X11 桌面（开始菜单/桌面图标入口）。
 *
 * fix9.6 起画面直接渲染在本桌面窗口内（不再默认拉起独立全屏 Activity）：
 * - 窗口可自由拖拽/8 向缩放/最大化，控制条一键真全屏（返回键退出）；
 * - 分辨率"跟随窗口"（native）或"固定分辨率"（exact）随时切换；
 * - X11 自带键盘栏默认关闭，控制条"键盘"按钮唤起系统输入法；
 * - 终端 `anwind-x11 :1` 启动服务后本窗口自动弹出并连接
 *   （见 X11WindowController：广播 → 开窗 → 取 fd → LorieView 渲染）；
 * - 等待连接页提供"兼容全屏模式"按钮，可拉起独立全屏 Activity
 *   （com.termux.x11.MainActivity，保留作排障兜底）。
 *
 * v3.3.42（用户需求 4）：标题栏新增「隐藏底部控制条」按钮 ——
 * 隐藏窗口下方的分辨率/切窗/键盘/游戏全屏/全屏设置栏，让 X11 画面
 * （游戏画面）占满更大区域；隐藏态在画面底部保留一条 3dp 的
 * 「▾ 显示设置栏」细条，轻点即可恢复（不必回到标题栏）。
 */
val X11App = AppDef(
    id = "x11",
    displayName = "X11 桌面",
    iconAsset = "icons/x11.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 640.dp,
    defaultHeight = 480.dp,
    pinnedToDesktop = true,
    // v2.22.4 fix11c：长按标题栏打开 X11 设置面板（分辨率/拉伸/剪贴板/握手重放）
    onTitleBarLongPress = { _ -> X11SettingsBridge.requestShow() },
    // v3.1.7：标题栏菜单按钮（最小化左边）—— 一键调出 X11 设置菜单
    // （旧入口只有长按标题栏 3 秒，难发现且易和拖动窗口冲突）
    onMenuButton = { _ -> X11SettingsBridge.requestShow() },
    // v3.3.42：标题栏「隐藏/显示底部设置栏」按钮（图标随状态变化）
    titleBarExtraButtons = listOf(
        TitleBarExtraButton(
            label = "隐藏/显示底部设置栏",
            icon = {
                if (X11UiPrefs.controlBarHidden) Icons.Default.Visibility
                else Icons.Default.VisibilityOff
            },
            onClick = { ctx ->
                X11UiPrefs.saveControlBarHidden(ctx, !X11UiPrefs.controlBarHidden)
            }
        )
    )
) { scope ->
    X11Surface(scope)
}
