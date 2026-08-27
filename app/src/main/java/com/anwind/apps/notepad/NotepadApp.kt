package com.anwind.apps.notepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope

val NotepadApp = AppDef(
    id = "notepad",
    displayName = "记事本",
    iconAsset = "icons/notepad.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 560.dp,
    defaultHeight = 400.dp,
    pinnedToDesktop = true
) { scope ->
    NotepadContent(scope)
}

@Composable
private fun NotepadContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    var text by remember { mutableStateOf("") }
    var savedAt by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {
        // 顶部菜单
        Row(
            modifier = Modifier.fillMaxWidth().height(32.dp).background(theme.buttonBackgroundColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { text = "" }) { Text("新建", fontSize = 12.sp) }
            TextButton(onClick = { savedAt = System.currentTimeMillis() }) { Text("保存", fontSize = 12.sp) }
            TextButton(onClick = {
                // 简化：直接清空剪贴板逻辑，复制到剪贴板需要 Context
            }) { Text("编辑", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            savedAt?.let {
                Text(
                    "已保存 · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))}",
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // 编辑区
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
        )

        // 状态栏
        Row(
            modifier = Modifier.fillMaxWidth().height(20.dp).background(theme.buttonBackgroundColor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "行 ${text.count { it == '\n' } + 1}  字符 ${text.length}",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                "UTF-8",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
    }
}
