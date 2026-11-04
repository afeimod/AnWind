package com.anwind.apps.containers

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope

/**
 * 内置"Wine 容器"应用（桌面/开始菜单入口）。
 *
 * v3.2.18（wineguanli.py 完全重制）：窗口默认尺寸对齐 wineguanli
 * 主窗口（1024×600 最小）；窄窗口内界面自动等比缩小，放不下时
 * 横向滑动（LocalGuanliScale，见 ContainerScreens.kt 文件头）。
 *
 * 数据与终端 CLI（anwind-container / anwind-wine）完全共享，
 * App 里看到的容器与终端 `anwind-container list` 一致。
 */
val ContainersApp = AppDef(
    id = "wine-containers",
    displayName = "Wine 容器",
    iconAsset = "emoji:📦",
    launchMode = LaunchMode.FLOATING,
    // v3.2.23（用户问题 5）：默认尺寸 1060×680 → 900×600 —— 用户反馈
    // "主界面太长太宽，拉小，字体保持默认"。字号经 LocalGuanliFontScale
    // 以 900 为基准（见 ContainerScreens.kt），默认窗下与旧 1060dp 窗
    // 完全同字号，仅窗口本体与布局尺寸收紧。
    defaultWidth = 900.dp,
    defaultHeight = 600.dp,
    pinnedToDesktop = true,
    pinnedToTaskbar = true
) { scope ->
    ContainerListContent(scope)
}
