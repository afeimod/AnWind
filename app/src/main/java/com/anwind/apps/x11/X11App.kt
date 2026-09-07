package com.anwind.apps.x11

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
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
 */
val X11App = AppDef(
    id = "x11",
    displayName = "X11 桌面",
    iconAsset = "icons/x11.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 640.dp,
    defaultHeight = 480.dp,
    pinnedToDesktop = true
) { scope ->
    X11Surface(scope)
}
