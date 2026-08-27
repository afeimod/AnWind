package com.anwind.apps.filemanager

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.desktop.IconPainter
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope
import com.anwind.core.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val FileExplorerApp = AppDef(
    id = "file_explorer",
    displayName = "文件资源管理器",
    iconAsset = "icons/file_explorer.png",
    launchMode = LaunchMode.FULLSCREEN,
    defaultWidth = 900.dp,
    defaultHeight = 600.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    FileExplorerContent(scope)
}

@Composable
private fun FileExplorerContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val vfs = remember { VirtualFileSystem(context) }

    var currentPath by remember { mutableStateOf("C:\\") }
    val items by produceState(initialValue = emptyList<VirtualFile>(), currentPath) {
        value = withContext(Dispatchers.IO) { vfs.list(currentPath) }
    }

    Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 工具栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(theme.windowTitleBarColor.copy(alpha = 0.1f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val parent = vfs.parent(currentPath)
                if (parent != null) currentPath = parent
            }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = if (theme.isDark) Color.White else Color.Black)
            }
            IconButton(onClick = { /* forward */ }) {
                Icon(Icons.Default.ArrowForward, "Forward", tint = if (theme.isDark) Color.White else Color.Black)
            }
            IconButton(onClick = { /* refresh */ }) {
                Icon(Icons.Default.Refresh, "Refresh", tint = if (theme.isDark) Color.White else Color.Black)
            }
            Spacer(Modifier.width(8.dp))
            // 地址栏
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .background(theme.buttonBackgroundColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = currentPath,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp
                )
            }
        }

        // ===== 左侧导航栏 =====
        Row(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxHeight()
                    .background(theme.buttonBackgroundColor.copy(alpha = 0.3f))
                    .padding(8.dp)
            ) {
                SidebarItem("🖥️ 此电脑", currentPath == "C:\\") { currentPath = "C:\\" }
                SidebarItem("📁 文档", false) { currentPath = "C:\\Users\\User\\Documents" }
                SidebarItem("🖼️ 图片", false) { currentPath = "C:\\Users\\User\\Pictures" }
                SidebarItem("🎵 音乐", false) { currentPath = "C:\\Users\\User\\Music" }
                SidebarItem("📺 视频", false) { currentPath = "C:\\Users\\User\\Videos" }
                SidebarItem("⬇️ 下载", false) { currentPath = "C:\\Users\\User\\Downloads" }
            }

            // ===== 文件列表 =====
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "此文件夹为空",
                            color = if (theme.isDark) Color.White else Color.Black,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    LazyColumn {
                        items(items, key = { it.path }) { file ->
                            FileRow(
                                file = file,
                                onClick = {
                                    if (file.isDirectory) {
                                        currentPath = file.path
                                    } else if (file.extension == "html" || file.extension == "htm") {
                                        // 用浏览器打开 HTML 文件
                                        val url = "content://${file.assetPath}"
                                        WindowManager.get().open(
                                            appId = "browser",
                                            title = "Browser",
                                            launchMode = LaunchMode.FULLSCREEN,
                                            launchArgs = mapOf("url" to url)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // ===== 状态栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(theme.windowTitleBarColor.copy(alpha = 0.1f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${items.size} 个项目",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SidebarItem(label: String, active: Boolean, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (active) theme.accentColor.copy(alpha = 0.2f) else Color.Transparent,
                RoundedCornerShape(4.dp)
            )
            .padding(8.dp)
    ) {
        Text(
            text = label,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun FileRow(file: VirtualFile, onClick: () -> Unit) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (file.isDirectory) "📁" else iconForExtension(file.extension),
            fontSize = 16.sp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = file.name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!file.isDirectory) {
            Text(
                text = file.sizeText,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp
            )
        }
    }
}

private fun iconForExtension(ext: String): String = when (ext.lowercase()) {
    "html", "htm" -> "🌐"
    "txt" -> "📄"
    "jpg", "png", "gif" -> "🖼️"
    "mp3", "wav" -> "🎵"
    "mp4", "avi" -> "📺"
    "pdf" -> "📕"
    else -> "📄"
}
