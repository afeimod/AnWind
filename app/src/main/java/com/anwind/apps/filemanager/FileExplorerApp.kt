package com.anwind.apps.filemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
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

    // APK 文件选择器
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            installApk(context, uri)
        }
    }

    // ===== 真实手机存储（SAF）浏览 =====
    // realStack 非空时浏览真实存储：栈底为根目录，越往后越深
    var realStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    val browsingReal get() = realStack.isNotEmpty()
    val storagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // 持久化读取权限，重启后仍可访问
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root != null) realStack = listOf(root)
        }
    }

    // 真实存储当前目录下的文件
    val realItems = if (browsingReal) {
        remember(realStack) { realStack.last().listFiles()?.toList() ?: emptyList() }
    } else emptyList()

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
                when {
                    // 真实存储：逐级返回，到根目录后回到虚拟文件系统
                    browsingReal && realStack.size > 1 -> realStack = realStack.dropLast(1)
                    browsingReal -> realStack = emptyList()
                    else -> vfs.parent(currentPath)?.let { currentPath = it }
                }
            }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = if (theme.isDark) Color.White else Color.Black)
            }
            IconButton(onClick = { /* forward */ }) {
                Icon(Icons.Default.ArrowForward, "Forward", tint = if (theme.isDark) Color.White else Color.Black)
            }
            IconButton(onClick = {
                // 刷新：真实存储重新读取当前目录，虚拟 FS 重新加载
                if (browsingReal) realStack = realStack.toList() else currentPath = currentPath
            }) {
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
                    text = if (browsingReal)
                        "📱 手机存储 / ${realStack.joinToString(" / ") { it.name ?: "" }}"
                    else currentPath,
                    color = if (theme.isDark) Color.White else Color.Black,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            // 安装 APK 按钮
            IconButton(onClick = {
                apkPickerLauncher.launch(arrayOf("application/vnd.android.package-archive"))
            }) {
                Icon(Icons.Default.Add, "安装APK", tint = if (theme.isDark) Color.White else Color.Black)
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
                SidebarItem("📱 手机存储", browsingReal) { storagePicker.launch(null) }
                SidebarItem("📁 文档", false) { currentPath = "C:\\Users\\User\\Documents" }
                SidebarItem("🖼️ 图片", false) { currentPath = "C:\\Users\\User\\Pictures" }
                SidebarItem("🎵 音乐", false) { currentPath = "C:\\Users\\User\\Music" }
                SidebarItem("📺 视频", false) { currentPath = "C:\\Users\\User\\Videos" }
                SidebarItem("⬇️ 下载", false) { currentPath = "C:\\Users\\User\\Downloads" }
            }

            // ===== 文件列表 =====
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (browsingReal) {
                    // 真实手机存储
                    if (realItems.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "此文件夹为空",
                                color = if (theme.isDark) Color.White else Color.Black,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        LazyColumn {
                            items(realItems, key = { it.uri.toString() }) { file ->
                                RealFileRow(
                                    file = file,
                                    onClick = {
                                        if (file.isDirectory) {
                                            realStack = realStack + file
                                        } else {
                                            openRealFile(context, file)
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else if (items.isEmpty()) {
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
                                    } else if (file.extension == "apk") {
                                        // 安装 APK（通过文件选择器选择的 URI）
                                        if (file.realUri != null) {
                                            installApk(context, file.realUri)
                                        } else {
                                            Toast.makeText(context, "APK 文件路径不可用，请使用工具栏的安装按钮选择文件", Toast.LENGTH_SHORT).show()
                                        }
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
                if (browsingReal) "${realItems.size} 个项目" else "${items.size} 个项目",
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

/**
 * 真实存储文件行：使用 SAF DocumentFile 渲染。
 */
@Composable
private fun RealFileRow(file: DocumentFile, onClick: () -> Unit) {
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
            text = file.name ?: "未命名",
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!file.isDirectory) {
            val len = file.length()
            val sizeText = when {
                len < 1024 -> "$len B"
                len < 1024 * 1024 -> "${len / 1024} KB"
                else -> "${len / (1024 * 1024)} MB"
            }
            Text(
                text = sizeText,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * 打开真实存储中的文件：HTML 用浏览器，APK 启动安装，其余提示。
 */
private fun openRealFile(context: Context, file: DocumentFile) {
    val name = (file.name ?: "").lowercase()
    when {
        name.endsWith(".html") || name.endsWith(".htm") -> {
            WindowManager.get().open(
                appId = "browser",
                title = "Browser",
                launchMode = LaunchMode.FULLSCREEN,
                launchArgs = mapOf("url" to file.uri.toString())
            )
        }
        name.endsWith(".apk") -> installApk(context, file.uri)
        else -> Toast.makeText(context, "暂不支持打开该类型文件", Toast.LENGTH_SHORT).show()
    }
}

private fun iconForExtension(ext: String): String = when (ext.lowercase()) {
    "apk" -> "📦"
    "html", "htm" -> "🌐"
    "txt" -> "📄"
    "jpg", "png", "gif" -> "🖼️"
    "mp3", "wav" -> "🎵"
    "mp4", "avi" -> "📺"
    "pdf" -> "📕"
    "exe", "msi" -> "⚙️"
    "zip", "rar", "7z" -> "🗜️"
    else -> "📄"
}

/**
 * 启动系统安装程序安装 APK 文件
 */
private fun installApk(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "无法启动安装程序: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
