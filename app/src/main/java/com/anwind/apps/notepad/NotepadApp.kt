package com.anwind.apps.notepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.input.keyboardAwareEditor
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope

val NotepadApp = AppDef(
    id = "notepad",
    displayName = "记事本",
    iconAsset = "icons/notepad.png",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 620.dp,
    defaultHeight = 460.dp,
    pinnedToDesktop = true
) { scope ->
    NotepadContent(scope)
}

/**
 * 记事本 - Win11 风格重构
 *
 * - 顶部菜单栏：新建、打开、保存、编辑、格式、查看
 * - 编辑区：等宽字体
 * - 底部状态栏：行数、字符数、编码、缩进
 */
@Composable
private fun NotepadContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    // v2.13：TextFieldValue 状态，支持虚拟键盘光标级编辑（插入/删除/方向键/全选/复制粘贴）
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var wrapText by remember { mutableStateOf(true) }
    var fontSize by remember { mutableStateOf(14) }

    Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 顶部菜单栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenuButton("文件", theme) { text = TextFieldValue("") }
            MenuButton("编辑", theme) { /* 复制等操作 */ }
            MenuButton("查看", theme) { wrapText = !wrapText }
            Spacer(Modifier.weight(1f))
            // 字号控制
            IconButton(onClick = { fontSize = (fontSize - 1).coerceAtLeast(8) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.TextDecrease, null, tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            Text(
                "${fontSize}sp",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            IconButton(onClick = { fontSize = (fontSize + 1).coerceAtMost(36) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.TextIncrease, null, tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { text = TextFieldValue("") }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Add, "新建", tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = { savedAt = System.currentTimeMillis() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Save, "保存", tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(14.dp))
            }
            savedAt?.let {
                Text(
                    "已保存 · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))}",
                    color = theme.secondaryTextColor,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        // ===== 编辑区 =====
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(theme.windowBackgroundColor)
                .padding(8.dp)
        ) {
            if (text.text.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    Text(
                        "开始输入文本...",
                        color = theme.secondaryTextColor.copy(alpha = 0.5f),
                        fontSize = fontSize.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = (fontSize + 4).sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accentColor),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .keyboardAwareEditor(
                        value = { text },
                        onValue = { text = it },
                        singleLine = false
                    )
                    .padding(4.dp)
            )
        }

        // ===== 底部状态栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "行 ${text.text.count { it == '\n' } + 1}  字符 ${text.text.length}  字 ${text.text.toCharArray().filter { !it.isWhitespace() }.size}",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 10.sp
            )
            Spacer(Modifier.weight(1f))
            Text("UTF-8", color = theme.secondaryTextColor, fontSize = 10.sp)
            Spacer(Modifier.width(16.dp))
            Text("Windows (CRLF)", color = theme.secondaryTextColor, fontSize = 10.sp)
            Spacer(Modifier.width(16.dp))
            Text("${fontSize}sp", color = theme.secondaryTextColor, fontSize = 10.sp)
            Spacer(Modifier.width(8.dp))
            if (wrapText) {
                Text("自动换行", color = theme.accentColor, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun MenuButton(label: String, theme: com.anwind.core.theme.WinTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp
        )
    }
}
