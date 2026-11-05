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
    // v3.3.3（用户问题 2）：默认尺寸 900×600 → 800×560 —— 用户反馈
    // "比例太大了，按照图三（linbox 容器管理器）这种比例重新做，包括窗口
    // 和字体"。三件套联动：窗口 800×560 + 字号缩放基准同步 800
    // （GuanliFontBaseDp，默认窗下 fontScale=1）+ 全局收紧系数 GuanliUiK。
    // v3.3.4（用户问题 2）：GuanliUiK 0.68 → 0.72（框体微放）+ 新增
    // GuanliFontBoost=1.35 字号补偿（用户反馈"界面字体太小"—— 0.68 把
    // 字号也缩到 68%，现框体保持紧凑而字号补回设计尺寸 ≈0.97）。
    defaultWidth = 800.dp,
    defaultHeight = 560.dp,
    pinnedToDesktop = true,
    pinnedToTaskbar = true
) { scope ->
    ContainerListContent(scope)
}
