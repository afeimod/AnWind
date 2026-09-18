package com.anwind.apps.containers

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope

/**
 * 内置"Wine 容器"应用（桌面/开始菜单入口）。
 *
 * 用途：可视化管理 Wine 容器 —— 新建/编辑/克隆/删除/设默认，
 * 以 box64 / FEXCore / Hangover 后端运行 Windows 程序，
 * 画面经 AnWind 内置 X11 桌面窗口输出（X11 显示方案）。
 *
 * 数据与终端 CLI（anwind-container / anwind-wine）完全共享，
 * App 里看到的容器与终端 `anwind-container list` 一致。
 */
val ContainersApp = AppDef(
    id = "wine-containers",
    displayName = "Wine 容器",
    iconAsset = "emoji:📦",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 560.dp,
    defaultHeight = 620.dp,
    pinnedToDesktop = true,
    pinnedToTaskbar = true
) { scope ->
    ContainerListContent(scope)
}
