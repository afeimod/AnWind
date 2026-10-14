package com.anwind.apps.qq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope

/**
 * QQ2008 —— AnWind 桌面聊天应用。
 *
 * 单 AppDef 多窗口架构：
 *  - 无 launchArgs → 登录界面（未登录）/ 主面板（已登录）
 *  - launchArgs["chat"] = 号码 → 聊天窗口（由主面板双击好友打开，可多个）
 *  - launchArgs["game"] = 1   → QQ游戏大厅窗口（主面板 🎮 按钮打开）
 *
 * 登录状态保存在进程级 [QQSession]，多窗口共享；AI 回复运行在应用级
 * 协程域，聊天窗口关闭后回复仍会计入未读。
 */
val QQApp = AppDef(
    id = "qq",
    displayName = "QQ2008",
    iconAsset = "app:qq",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 380.dp,
    defaultHeight = 560.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    QQRootContent(scope)
}

@Composable
private fun QQRootContent(scope: WindowContentScope) {
    val context = LocalContext.current
    // 初始化存储 + 自动登录（remember 保证组合期同步执行，必须先于任何
    // QQStore 读取 —— 登录界面 remember 初值会在同一帧读账号文件）。
    // ensureInit 内部幂等，多窗口/重组重复调用安全。
    remember(context) { QQSession.ensureInit(context); true }

    val chatId = scope.windowState.launchArgs["chat"]
    val gameId = scope.windowState.launchArgs["game"]
    val account = QQSession.account

    when {
        chatId != null -> QQChatScreen(scope, chatId)
        gameId != null -> QQGameRoot(scope)
        account != null -> QQMainScreen(scope)
        else -> QQLoginScreen(scope)
    }
}
