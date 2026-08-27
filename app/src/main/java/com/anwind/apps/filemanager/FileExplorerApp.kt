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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    launchMode = LaunchMode.FLOATING,
    defaultWidth = 920.dp,
    defaultHeight = 620.dp,
    pinnedToTaskbar = true,
    pinnedToDesktop = true
) { scope ->
    FileExplorerContent(scope)
}

/**
 * 文件资源管理器 - Win11 风格重构
 *
 * - 顶部工具栏：后退/前进/刷新 + 地址栏 + 操作按钮
 * - 左侧导航栏：此电脑、手机存储、文档、图片、音乐、视频、下载
 * - 中部文件区：网格视图（文件夹大图标 + 文件名）
 * - 底部状态栏：项目数 + 选择状态
 */
@Composable
private fun FileExplorerContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val vfs = remember { VirtualFileSystem(context) }

    var currentPath by remember { mutableStateOf("C:\\") }
    var isGridView by remember { mutableStateOf(true) }
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
    var realStack by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    val browsingReal = realStack.isNotEmpty()
    val storagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(context, uri)
            if (root != null) realStack = listOf(root)
        }
    }

    val realItems = if (browsingReal) {
        remember(realStack) { realStack.last().listFiles()?.toList() ?: emptyList() }
    } else emptyList()

    Column(modifier = Modifier.fillMaxSize().background(theme.windowBackgroundColor)) {

        // ===== 顶部工具栏（Win11 风格） =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 后退/前进/上 / 刷新按钮组
            ToolbarIconButton(Icons.Default.ArrowBack, "后退", theme) {
                when {
                    browsingReal && realStack.size > 1 -> realStack = realStack.dropLast(1)
                    browsingReal -> realStack = emptyList()
                    else -> vfs.parent(currentPath)?.let { currentPath = it }
                }
            }
            ToolbarIconButton(Icons.Default.ArrowForward, "前进", theme) { }
            ToolbarIconButton(Icons.Default.KeyboardArrowUp, "向上", theme) {
                if (browsingReal && realStack.size > 1) realStack = realStack.dropLast(1)
                else if (browsingReal) realStack = emptyList()
                else vfs.parent(currentPath)?.let { currentPath = it }
            }
            ToolbarIconButton(Icons.Default.Refresh, "刷新", theme) {
                if (browsingReal) realStack = realStack.toList() else currentPath = currentPath
            }

            Spacer(Modifier.width(8.dp))

            // 地址栏（Win11 风格面包屑导航）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(theme.cardBackgroundColor)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Computer,
                        null,
                        tint = theme.accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (browsingReal) "手机存储 / ${realStack.joinToString(" / ") { it.name ?: "" }}"
                               else currentPath,
                        color = if (theme.isDark) Color.White else Color.Black,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // 视图切换：网格 / 列表
            ToolbarIconButton(
                if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                "切换视图", theme
            ) { isGridView = !isGridView }
            // 安装 APK
            ToolbarIconButton(Icons.Default.Add, "安装 APK", theme) {
                apkPickerLauncher.launch(arrayOf("application/vnd.android.package-archive"))
            }
        }

        // ===== 主体：左侧栏 + 文件区 =====
        Row(modifier = Modifier.weight(1f)) {
            // 左侧导航栏
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .background(theme.cardBackgroundColor)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                NavSection("快速访问")
                SidebarItem("🏠 主页", currentPath == "C:\\", theme) { currentPath = "C:\\" }
                SidebarItem("🖥️ 桌面", false, theme) { currentPath = "C:\\Users\\User\\Desktop" }
                SidebarItem("⬇️ 下载", false, theme) { currentPath = "C:\\Users\\User\\Downloads" }
                SidebarItem("📄 文档", false, theme) { currentPath = "C:\\Users\\User\\Documents" }
                SidebarItem("🖼️ 图片", false, theme) { currentPath = "C:\\Users\\User\\Pictures" }
                SidebarItem("🎵 音乐", false, theme) { currentPath = "C:\\Users\\User\\Music" }
                SidebarItem("📺 视频", false, theme) { currentPath = "C:\\Users\\User\\Videos" }

                Spacer(Modifier.height(12.dp))
                NavSection("设备")
                SidebarItem("💻 此电脑", currentPath == "C:\\", theme) { currentPath = "C:\\" }
                SidebarItem("📱 手机存储", browsingReal, theme) { storagePicker.launch(null) }
                SidebarItem("💿 C: 系统盘", false, theme) { currentPath = "C:\\" }
                SidebarItem("📀 D: 数据盘", false, theme) { currentPath = "D:\\" }
            }

            // ===== 文件区 =====
            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                if (browsingReal) {
                    if (realItems.isEmpty()) {
                        EmptyState(theme, "此文件夹为空")
                    } else if (isGridView) {
                        FileGrid(realItems, theme) { file ->
                            if (file is DocumentFile) {
                                if (file.isDirectory) realStack = realStack + file
                                else openRealFile(context, file)
                            }
                        }
                    } else {
                        FileList(realItems, theme) { file ->
                            if (file is DocumentFile) {
                                if (file.isDirectory) realStack = realStack + file
                                else openRealFile(context, file)
                            }
                        }
                    }
                } else if (items.isEmpty()) {
                    EmptyState(theme, "此文件夹为空")
                } else if (isGridView) {
                    FileGrid(items, theme) { file ->
                        if (file is VirtualFile) {
                            if (file.isDirectory) {
                                currentPath = file.path
                            } else if (file.extension == "html" || file.extension == "htm") {
                                WindowManager.get().open(
                                    appId = "browser", title = "Browser",
                                    launchMode = LaunchMode.FLOATING,
                                    launchArgs = mapOf("url" to "content://${file.assetPath}")
                                )
                            } else if (file.extension == "apk" && file.realUri != null) {
                                installApk(context, file.realUri)
                            } else {
                                Toast.makeText(context, "暂不支持打开该类型文件", Toast.LENGTH_SHORT).show()
                            }
                        } else if (file is DocumentFile) {
                            if (file.isDirectory) {
                                realStack = realStack + file
                            } else {
                                openRealFile(context, file)
                            }
                        }
                    }
                } else {
                    FileList(items, theme) { file ->
                        if (file is VirtualFile) {
                            if (file.isDirectory) {
                                currentPath = file.path
                            } else if (file.extension == "html" || file.extension == "htm") {
                                WindowManager.get().open(
                                    appId = "browser", title = "Browser",
                                    launchMode = LaunchMode.FLOATING,
                                    launchArgs = mapOf("url" to "content://${file.assetPath}")
                                )
                            } else if (file.extension == "apk" && file.realUri != null) {
                                installApk(context, file.realUri)
                            } else {
                                Toast.makeText(context, "暂不支持打开该类型文件", Toast.LENGTH_SHORT).show()
                            }
                        } else if (file is DocumentFile) {
                            if (file.isDirectory) {
                                realStack = realStack + file
                            } else {
                                openRealFile(context, file)
                            }
                        }
                    }
                }
            }
        }

        // ===== 底部状态栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (browsingReal) "${realItems.size} 个项目" else "${items.size} 个项目",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (isGridView) "网格视图" else "列表视图",
                color = theme.secondaryTextColor,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun NavSection(title: String) {
    val theme = LocalWinTheme.current
    Text(
        title,
        color = theme.secondaryTextColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 4.dp)
    )
}

@Composable
private fun ToolbarIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, theme: com.anwind.core.theme.WinTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, desc, tint = if (theme.isDark) Color.White else Color.Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SidebarItem(label: String, active: Boolean, theme: com.anwind.core.theme.WinTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .background(if (active) theme.accentColor.copy(alpha = 0.15f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (active) theme.accentColor else (if (theme.isDark) Color.White else Color.Black),
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
        )
    }
}

@Composable
private fun EmptyState(theme: com.anwind.core.theme.WinTheme, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.FolderOpen, null,
                tint = theme.secondaryTextColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(message, color = theme.secondaryTextColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FileGrid(items: List<Any>, theme: com.anwind.core.theme.WinTheme, onClick: (Any) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items, key = { it.hashCode() }) { item ->
            when (item) {
                is VirtualFile -> FileGridCell(
                    name = item.name,
                    isDir = item.isDirectory,
                    extension = item.extension,
                    sizeText = if (item.isDirectory) "" else item.sizeText,
                    theme = theme
                ) { onClick(item) }
                is DocumentFile -> FileGridCell(
                    name = item.name ?: "未命名",
                    isDir = item.isDirectory,
                    extension = item.name?.substringAfterLast('.', "") ?: "",
                    sizeText = if (item.isDirectory) "" else formatSize(item.length()),
                    theme = theme
                ) { onClick(item) }
            }
        }
    }
}

@Composable
private fun FileGridCell(
    name: String, isDir: Boolean, extension: String, sizeText: String,
    theme: com.anwind.core.theme.WinTheme, onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center
        ) {
            // 文件夹用彩色方块图标，文件用 emoji
            if (isDir) {
                Icon(
                    Icons.Default.Folder, null,
                    tint = Color(0xFFDCA84A),
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Text(
                    text = iconForExtension(extension),
                    fontSize = 36.sp
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (sizeText.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                sizeText,
                color = theme.secondaryTextColor,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun FileList(items: List<Any>, theme: com.anwind.core.theme.WinTheme, onClick: (Any) -> Unit) {
    LazyColumn {
        items(items, key = { it.hashCode() }) { item ->
            when (item) {
                is VirtualFile -> FileListRow(
                    name = item.name, isDir = item.isDirectory, extension = item.extension,
                    sizeText = if (item.isDirectory) "" else item.sizeText,
                    theme = theme
                ) { onClick(item) }
                is DocumentFile -> FileListRow(
                    name = item.name ?: "未命名", isDir = item.isDirectory,
                    extension = item.name?.substringAfterLast('.', "") ?: "",
                    sizeText = if (item.isDirectory) "" else formatSize(item.length()),
                    theme = theme
                ) { onClick(item) }
            }
        }
    }
}

@Composable
private fun FileListRow(
    name: String, isDir: Boolean, extension: String, sizeText: String,
    theme: com.anwind.core.theme.WinTheme, onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDir) {
            Icon(Icons.Default.Folder, null, tint = Color(0xFFDCA84A), modifier = Modifier.size(18.dp))
        } else {
            Text(text = iconForExtension(extension), fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (sizeText.isNotEmpty()) {
            Text(sizeText, color = theme.secondaryTextColor, fontSize = 11.sp)
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
                launchMode = LaunchMode.FLOATING,
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
    "doc", "docx" -> "📘"
    "xls", "xlsx" -> "📗"
    "ppt", "pptx" -> "📙"
    else -> "📄"
}

private fun formatSize(len: Long): String = when {
    len < 1024 -> "$len B"
    len < 1024 * 1024 -> "${len / 1024} KB"
    else -> "${len / (1024 * 1024)} MB"
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


