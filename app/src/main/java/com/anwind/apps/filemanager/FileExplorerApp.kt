package com.anwind.apps.filemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppDef
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowContentScope
import com.anwind.core.window.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * - 直接访问真实文件系统 /storage/emulated/0/，不再调用 SAF 文件选择器
 * - 侧边栏：图片/音乐/视频/文档/下载/内部存储 等常用目录快捷入口
 * - 顶部：后退/前进/向上/刷新 + 地址栏 + 视图切换 + 安装 APK
 * - 主体：左侧导航栏 + 文件网格/列表
 * - 需 MANAGE_EXTERNAL_STORAGE 权限
 */
@Composable
private fun FileExplorerContent(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val vfs = remember { VirtualFileSystem(context) }

    // 虚拟 C:\ 路径
    var currentPath by remember { mutableStateOf("C:\\") }
    // 真实路径栈：用于后退/前进
    var backStack by remember { mutableStateOf<List<File>>(emptyList()) }
    var forwardStack by remember { mutableStateOf<List<File>>(emptyList()) }
    // 当前真实路径（null = 浏览虚拟 C:\）
    var currentRealDir by remember { mutableStateOf<File?>(null) }
    val browsingReal = currentRealDir != null

    var isGridView by remember { mutableStateOf(true) }

    // MANAGE_EXTERNAL_STORAGE 权限检查
    var hasAllFilesAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        // 用户从系统设置返回后重新检查权限
        hasAllFilesAccess = Environment.isExternalStorageManager()
        if (hasAllFilesAccess && currentRealDir == null) {
            // 授权后直接进入内部存储
            val root = File("/storage/emulated/0/")
            backStack = emptyList()
            forwardStack = emptyList()
            currentRealDir = root
        }
    }
    fun requestAllFilesAccess() {
        runCatching {
            // Android 11+ (API 30) 提供按应用授权的入口；旧版本会抛 NoSuchField，进入 fallback
            val action = Settings::class.java
                .getField("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION")
                .get(null) as String
            val intent = Intent(action).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            allFilesPermissionLauncher.launch(intent)
        }.onFailure {
            // 回退到通用“所有文件访问权限”设置（API 26+）
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                allFilesPermissionLauncher.launch(intent)
            }.onFailure { e ->
                Toast.makeText(context, "无法打开权限设置: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 虚拟文件系统列表
    val virtualItems by produceState(initialValue = emptyList<VirtualFile>(), currentPath) {
        value = withContext(Dispatchers.IO) { vfs.list(currentPath) }
    }
    // 真实文件列表
    val realItems by produceState(initialValue = emptyList<File>(), currentRealDir) {
        val dir = currentRealDir ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { dir.listFiles()?.toList() ?: emptyList() }.getOrDefault(emptyList())
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    // APK 文件选择器（用于安装）
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) installApk(context, uri)
    }

    fun navigateToReal(target: File) {
        val current = currentRealDir
        if (current != null) backStack = backStack + current
        else backStack = backStack // 跳过虚拟到真实的过渡
        forwardStack = emptyList()
        currentRealDir = target
    }

    fun navigateToVirtual(path: String) {
        // 切换到虚拟路径前清空真实栈
        currentRealDir?.let { backStack = backStack + it }
        forwardStack = emptyList()
        currentRealDir = null
        currentPath = path
    }

    fun goBack() {
        if (backStack.isEmpty()) {
            // 在真实路径但没有后退历史，回到虚拟 C:\
            if (browsingReal) {
                forwardStack = forwardStack + (currentRealDir ?: return)
                currentRealDir = null
                currentPath = "C:\\"
            }
            return
        }
        val current = currentRealDir
        if (current != null) forwardStack = forwardStack + current
        val prev = backStack.last()
        backStack = backStack.dropLast(1)
        currentRealDir = prev
        if (prev.parentFile == null || backStack.isEmpty()) {
            // 退到根路径
        }
    }

    fun goForward() {
        if (forwardStack.isEmpty()) return
        val current = currentRealDir
        if (current != null) backStack = backStack + current
        val next = forwardStack.last()
        forwardStack = forwardStack.dropLast(1)
        currentRealDir = next
    }

    fun goUp() {
        val current = currentRealDir
        if (current != null) {
            val parent = current.parentFile
            if (parent != null && parent.canRead()) {
                backStack = backStack + current
                forwardStack = emptyList()
                currentRealDir = parent
            } else if (parent == null || !parent.exists()) {
                // 已经在根，回到虚拟 C:\
                backStack = backStack + current
                forwardStack = emptyList()
                currentRealDir = null
                currentPath = "C:\\"
            }
        } else {
            vfs.parent(currentPath)?.let { navigateToVirtual(it) }
        }
    }

    fun refresh() {
        // 触发 produceState 重计算
        val cur = currentRealDir
        currentRealDir = null
        currentRealDir = cur
        val p = currentPath
        currentPath = ""
        currentPath = p
    }

    // 内部存储根目录
    val storageRoot = remember { File("/storage/emulated/0/") }
    fun tryEnterRealStorage() {
        if (!hasAllFilesAccess) {
            requestAllFilesAccess()
            return
        }
        backStack = if (currentRealDir != null) backStack + currentRealDir!! else backStack
        forwardStack = emptyList()
        currentRealDir = storageRoot
    }

    fun browseRealDir(target: File) {
        if (!hasAllFilesAccess) {
            requestAllFilesAccess()
            return
        }
        if (target.exists() && target.isDirectory) {
            navigateToReal(target)
        } else {
            Toast.makeText(context, "目录不存在: ${target.absolutePath}", Toast.LENGTH_SHORT).show()
        }
    }

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
            ToolbarIconButton(Icons.Default.ArrowBack, "后退", theme) { goBack() }
            ToolbarIconButton(Icons.Default.ArrowForward, "前进", theme) { goForward() }
            ToolbarIconButton(Icons.Default.KeyboardArrowUp, "向上", theme) { goUp() }
            ToolbarIconButton(Icons.Default.Refresh, "刷新", theme) { refresh() }

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
                    val displayPath = currentRealDir?.absolutePath ?: currentPath
                    Text(
                        text = displayPath,
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
                SidebarItem("🏠 主页", currentPath == "C:\\" && !browsingReal, theme) {
                    navigateToVirtual("C:\\")
                }
                SidebarItem("🖼️ 图片", false, theme) {
                    browseRealDir(File(storageRoot, "Pictures"))
                }
                SidebarItem("🎵 音乐", false, theme) {
                    browseRealDir(File(storageRoot, "Music"))
                }
                SidebarItem("📺 视频", false, theme) {
                    browseRealDir(File(storageRoot, "Movies"))
                }
                SidebarItem("📄 文档", false, theme) {
                    browseRealDir(File(storageRoot, "Documents"))
                }
                SidebarItem("⬇️ 下载", false, theme) {
                    browseRealDir(File(storageRoot, "Download"))
                }
                SidebarItem("📷 相册 (DCIM)", false, theme) {
                    browseRealDir(File(storageRoot, "DCIM"))
                }

                Spacer(Modifier.height(12.dp))
                NavSection("设备")
                SidebarItem("💻 此电脑", !browsingReal && currentPath == "C:\\", theme) {
                    navigateToVirtual("C:\\")
                }
                SidebarItem(
                    "📱 内部存储",
                    browsingReal && currentRealDir?.absolutePath == storageRoot.absolutePath,
                    theme
                ) { tryEnterRealStorage() }
                SidebarItem("💿 C: 系统盘", !browsingReal && currentPath == "C:\\", theme) {
                    navigateToVirtual("C:\\")
                }
            }

            // ===== 文件区 =====
            Box(modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp)) {
                if (browsingReal) {
                    if (!hasAllFilesAccess) {
                        // 未授权，显示授权提示
                        PermissionRequiredState(theme) { requestAllFilesAccess() }
                    } else if (realItems.isEmpty()) {
                        EmptyState(theme, "此文件夹为空")
                    } else if (isGridView) {
                        RealFileGrid(realItems, theme) { file ->
                            if (file.isDirectory) navigateToReal(file)
                            else openRealFile(context, file)
                        }
                    } else {
                        RealFileList(realItems, theme) { file ->
                            if (file.isDirectory) navigateToReal(file)
                            else openRealFile(context, file)
                        }
                    }
                } else if (virtualItems.isEmpty()) {
                    EmptyState(theme, "此文件夹为空")
                } else if (isGridView) {
                    VirtualFileGrid(virtualItems, theme) { file ->
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
                    }
                } else {
                    VirtualFileList(virtualItems, theme) { file ->
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
                if (browsingReal) "${realItems.size} 个项目" else "${virtualItems.size} 个项目",
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
private fun PermissionRequiredState(theme: com.anwind.core.theme.WinTheme, onGrant: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Icon(
                Icons.Default.Lock, null,
                tint = theme.accentColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "需要文件访问权限",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "为了让文件资源管理器直接读取 /storage/emulated/0 下的真实文件（图片、音乐、视频、文档等），请授予「所有文件访问权限」。",
                color = theme.secondaryTextColor,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant) {
                Text("授予权限")
            }
        }
    }
}

// ============================================================
// 虚拟文件系统（C:\ 演示目录）的网格/列表渲染
// ============================================================

@Composable
private fun VirtualFileGrid(items: List<VirtualFile>, theme: com.anwind.core.theme.WinTheme, onClick: (VirtualFile) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items, key = { it.path }) { item ->
            FileGridCell(
                name = item.name,
                isDir = item.isDirectory,
                extension = item.extension,
                sizeText = if (item.isDirectory) "" else item.sizeText,
                theme = theme
            ) { onClick(item) }
        }
    }
}

@Composable
private fun VirtualFileList(items: List<VirtualFile>, theme: com.anwind.core.theme.WinTheme, onClick: (VirtualFile) -> Unit) {
    LazyColumn {
        items(items, key = { it.path }) { item ->
            FileListRow(
                name = item.name, isDir = item.isDirectory, extension = item.extension,
                sizeText = if (item.isDirectory) "" else item.sizeText,
                theme = theme
            ) { onClick(item) }
        }
    }
}

// ============================================================
// 真实文件系统（File）的网格/列表渲染
// ============================================================

@Composable
private fun RealFileGrid(items: List<File>, theme: com.anwind.core.theme.WinTheme, onClick: (File) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items, key = { it.absolutePath }) { item ->
            FileGridCell(
                name = item.name,
                isDir = item.isDirectory,
                extension = item.extension,
                sizeText = if (item.isDirectory) "" else formatSize(item.length()),
                theme = theme
            ) { onClick(item) }
        }
    }
}

@Composable
private fun RealFileList(items: List<File>, theme: com.anwind.core.theme.WinTheme, onClick: (File) -> Unit) {
    LazyColumn {
        items(items, key = { it.absolutePath }) { item ->
            FileListRow(
                name = item.name, isDir = item.isDirectory,
                extension = item.extension,
                sizeText = if (item.isDirectory) "" else formatSize(item.length()),
                theme = theme
            ) { onClick(item) }
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
 * 打开真实文件：
 * - 图片 → 内置图片查看器
 * - HTML/HTM → 内置浏览器
 * - APK → 系统安装器（FileProvider）
 * - 音频/视频/文本/PDF → 系统 ACTION_VIEW Intent（FileProvider）
 */
private fun openRealFile(context: Context, file: File) {
    val name = file.name.lowercase()
    val ext = file.extension.lowercase()
    try {
        when {
            ext in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> {
                // 用内置图片查看器
                WindowManager.get().open(
                    appId = "image_viewer",
                    title = file.name,
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("path" to file.absolutePath)
                )
            }
            ext == "html" || ext == "htm" -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                WindowManager.get().open(
                    appId = "browser", title = "Browser",
                    launchMode = LaunchMode.FLOATING,
                    launchArgs = mapOf("url" to uri.toString())
                )
            }
            ext == "apk" -> {
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                installApk(context, uri)
            }
            else -> {
                // 用 mimeType + FileProvider 启动系统 ACTION_VIEW
                val mime = mimeForExtension(ext)
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    Toast.makeText(context, "未找到可打开 ${file.extension} 文件的应用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "无法获取文件 URI: ${e.message}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "打开文件失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun mimeForExtension(ext: String): String = when (ext) {
    "mp3", "wav", "ogg", "m4a", "flac" -> "audio/*"
    "mp4", "avi", "mov", "mkv", "3gp", "webm" -> "video/*"
    "txt", "log", "md" -> "text/plain"
    "pdf" -> "application/pdf"
    "doc", "docx" -> "application/msword"
    "xls", "xlsx" -> "application/vnd.ms-excel"
    "ppt", "pptx" -> "application/vnd.ms-powerpoint"
    "zip", "rar", "7z" -> "application/zip"
    else -> "*/*"
}

private fun iconForExtension(ext: String): String = when (ext.lowercase()) {
    "apk" -> "📦"
    "html", "htm" -> "🌐"
    "txt" -> "📄"
    "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "🖼️"
    "mp3", "wav", "ogg", "m4a", "flac" -> "🎵"
    "mp4", "avi", "mov", "mkv", "3gp", "webm" -> "📺"
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
