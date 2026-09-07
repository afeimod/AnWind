package com.anwind.apps.x11

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope

/**
 * v2.22.2 内置 X11 桌面（开始菜单/桌面图标入口）。
 *
 * 窗口本体只承担"说明 + 打开"职责：真正的 X11 画面由独立全屏
 * Activity（com.termux.x11.MainActivity）渲染——X11 不嵌入终端界面，
 * 而是以整个桌面形态出现，由终端 `anwind-x11` 命令调起显示，
 * 画面尺寸随设备分辨率/窗口自动调节。
 */
val X11App = AppDef(
    id = "x11",
    displayName = "X11 桌面",
    iconAsset = "icons/x11.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 520.dp,
    defaultHeight = 470.dp,
    pinnedToDesktop = true
) { scope ->
    X11Content(scope)
}

@Composable
private fun X11Content(_scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.windowBackgroundColor)
            .padding(18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "X11 桌面",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "以整个桌面形态运行的图形显示端（内置 X server），不占用终端界面。" +
                "在终端里运行 anwind-x11 启动服务，本机会自动弹出全屏 X11 桌面；" +
                "也可以点击下方按钮先打开桌面等待连接。",
            color = if (theme.isDark) Color(0xFFB8C4CE) else Color(0xFF444444),
            fontSize = 13.sp,
            lineHeight = 19.sp
        )

        Button(
            onClick = { X11Desktop.open(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("打开 X11 桌面")
        }

        HorizontalDivider(color = if (theme.isDark) Color(0xFF3A4450) else Color(0xFFDDDDDD))

        Text(
            "快速上手（终端）",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        val steps = listOf(
            "pkg install xfce4 dbus       # 首次使用先装桌面环境（也可用 openbox 等）",
            "anwind-x11 :1                # 启动 X11 服务并自动弹出桌面",
            "env DISPLAY=:1 xfce4-session # 未自动启动会话时手动启动",
            "anwind-x11-stop              # 停止 X11 服务"
        )
        steps.forEach { line ->
            Text(
                line,
                color = if (theme.isDark) Color(0xFF9FE29F) else Color(0xFF1B6E1B),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (theme.isDark) Color(0xFF14231A) else Color(0xFFEFF7EF),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }

        Text(
            "提示：DISPLAY=:1 与其他 X11 应用（如 Termux:X11）互不冲突；" +
                "画面分辨率默认跟随本机屏幕（设置里可选 exact/native/scaled 模式）。",
            color = if (theme.isDark) Color(0xFF8A97A3) else Color(0xFF777777),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}
