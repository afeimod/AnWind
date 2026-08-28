package com.anwind.apps.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.input.keyboardAware
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope
import com.anwind.core.window.WindowManager
import com.anwind.AnWindApp
import com.anwind.core.theme.WindowsVariant

val TerminalApp = AppDef(
    id = "terminal",
    displayName = "终端",
    iconAsset = "emoji:💻",
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 640.dp,
    defaultHeight = 400.dp,
    pinnedToDesktop = true
) { scope ->
    TerminalContent(scope)
}

@Composable
private fun TerminalContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val app = AnWindApp.get()

    var currentPath by remember { mutableStateOf("C:\\Users\\User") }
    var input by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<TerminalLine>() }
    val scrollState = rememberLazyListState()

    // 命令提交（v2.13：上移声明 —— 虚拟键盘 onEnter 回调在输入行声明之前引用）
    val handleSubmit: () -> Unit = {
        val cmd = input.trim()
        lines.add(TerminalLine("$currentPath> $cmd", LineType.INPUT))
        input = ""

        if (cmd.isNotEmpty()) {
            val output = executeCommand(cmd, currentPath, app)
            output.forEach { lines.add(TerminalLine(it, LineType.OUTPUT)) }
            // 处理 cd 命令
            if (cmd.startsWith("cd ") || cmd == "cd") {
                val target = if (cmd == "cd") "C:\\Users\\User" else cmd.removePrefix("cd ").trim()
                currentPath = target.ifEmpty { currentPath }
            }
            if (cmd == "cls" || cmd == "clear") {
                lines.clear()
                lines.add(TerminalLine("", LineType.SYSTEM))
            }
            if (cmd == "exit") {
                scope.onClose()
            }
        }
        lines.add(TerminalLine("", LineType.SYSTEM))
    }

    // 欢迎信息
    LaunchedEffect(Unit) {
        lines.add(TerminalLine("AnWind Terminal [版本 1.0.0]", LineType.SYSTEM))
        lines.add(TerminalLine("(c) AnWind Corporation. 保留所有权利。", LineType.SYSTEM))
        lines.add(TerminalLine("", LineType.SYSTEM))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(lines) { line ->
                TerminalLineView(line)
            }
            // 输入行
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$currentPath> ",
                        color = Color(0xFF00FF00),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00FF00)),
                        modifier = Modifier
                            .weight(1f)
                            .keyboardAware(
                                value = { input },
                                onValue = { input = it },
                                singleLine = true,
                                onEnter = { handleSubmit() }
                            )
                    )
                }
            }
        }

        // 自动滚动到底部
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) {
                scrollState.animateScrollToItem(lines.size)
            }
        }
    }

    // 命令处理通过键盘事件，简化：当输入包含换行符时执行
    // 实际 Android 上 BasicTextField 需要外接键盘或软键盘的回车
    // 这里监听键盘 enter 事件需要用 Modifier.onKeyEvent
    // 简化版：提供一个"执行"按钮（暂时隐藏，依赖回车键）

    // v2.13：命令提交已上移（供虚拟键盘 onEnter 与隐藏回车输入框共用）；
    // 隐藏输入框保留：兼容系统输入法的回车提交路径。
    Box(modifier = Modifier.offset(x = 9999.dp)) {
        BasicTextField(
            value = input,
            onValueChange = { newVal ->
                if (newVal.endsWith("\n")) {
                    input = newVal.removeSuffix("\n")
                    handleSubmit()
                } else {
                    input = newVal
                }
            },
            textStyle = TextStyle(fontSize = 1.sp),
            modifier = Modifier.size(1.dp)
        )
    }
}

data class TerminalLine(val text: String, val type: LineType)
enum class LineType { SYSTEM, INPUT, OUTPUT, ERROR }

@Composable
private fun TerminalLineView(line: TerminalLine) {
    val color = when (line.type) {
        LineType.SYSTEM -> Color(0xFFAAAAAA)
        LineType.INPUT  -> Color(0xFFFFFFCC)
        LineType.OUTPUT -> Color.White
        LineType.ERROR  -> Color(0xFFFF6666)
    }
    Text(
        text = line.text,
        color = color,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.fillMaxWidth()
    )
}

private fun executeCommand(cmd: String, currentPath: String, app: AnWindApp): List<String> {
    val parts = cmd.split(" ", limit = 2)
    val command = parts[0].lowercase()
    val args = if (parts.size > 1) parts[1] else ""

    return when (command) {
        "help" -> listOf(
            "可用命令:",
            "  help      显示帮助",
            "  ver       显示系统版本",
            "  date      显示当前日期",
            "  time      显示当前时间",
            "  echo      回显文本",
            "  dir / ls  列出目录内容",
            "  cd <path> 切换目录",
            "  cls       清屏",
            "  theme <variant>  切换主题(win95/xp/win7/win10/win11)",
            "  start <app> 启动应用(browser/files/notepad/calc)",
            "  exit      关闭终端"
        )
        "ver" -> listOf("AnWind [版本 1.0.0]", "(c) AnWind Corporation.")
        "date" -> listOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()))
        "time" -> listOf(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
        "echo" -> listOf(args)
        "dir", "ls" -> listOf(
            " C:\\Users\\User 的目录",
            "",
            "<DIR>          Desktop",
            "<DIR>          Documents",
            "<DIR>          Downloads",
            "<DIR>          Pictures",
            "<DIR>          Music",
            "<DIR>          Videos"
        )
        "cd" -> listOf()
        "cls", "clear" -> listOf()
        "theme" -> {
            val variant = when (args.lowercase()) {
                "win95", "95" -> WindowsVariant.WIN95
                "xp", "winxp" -> WindowsVariant.WIN_XP
                "win7", "7" -> WindowsVariant.WIN7
                "win10", "10" -> WindowsVariant.WIN10
                "win11", "11" -> WindowsVariant.WIN11
                else -> null
            }
            if (variant != null) {
                kotlinx.coroutines.runBlocking { app.themeManager.setTheme(variant) }
                listOf("主题已切换为 ${variant.displayName}")
            } else {
                listOf("未知主题: $args", "可选: win95/xp/win7/win10/win11")
            }
        }
        "start" -> {
            val target = when (args.lowercase()) {
                "browser", "ie", "explorer" -> "browser"
                "files", "filemanager" -> "file_explorer"
                "notepad" -> "notepad"
                "calc", "calculator" -> "calculator"
                "settings" -> "settings"
                else -> null
            }
            if (target != null) {
                val appDef = com.anwind.core.window.AppRegistry.get(target)
                if (appDef != null) {
                    WindowManager.get().open(
                        appId = appDef.id,
                        title = appDef.displayName,
                        launchMode = appDef.launchMode,
                        initialWidth = appDef.defaultWidth.value.toInt(),
                        initialHeight = appDef.defaultHeight.value.toInt()
                    )
                    listOf("正在启动 ${appDef.displayName}...")
                } else listOf("应用未注册: $target")
            } else listOf("未知应用: $args")
        }
        "exit" -> listOf("再见！")
        "" -> listOf()
        else -> listOf("'$command' 不是内部或外部命令，也不是可运行的程序。", "输入 'help' 查看可用命令。")
    }
}
