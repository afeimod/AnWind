package com.anwind.apps.winlator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GraphicsDriverConfigParser
import org.json.JSONObject

/**
 * v2.23：容器创建/编辑面板 —— Winlator 全套设置（Winlator Cmod 7.1.4x 的
 * ContainerDetailFragment 字段全集，Compose 实现）：
 *
 * 常规：名称 / 屏幕分辨率（预设+自定义）
 * 图形：图形驱动 (turnip/wrapper/virgl/llvmpipe) / 驱动配置（版本、扩展黑名单、
 *       最大显存、帧同步） / DX 包装器 (wined3d/dxvk/vkd3d) / DXVK 配置
 *       （帧率上限、最大显存、async、gplasync） / DDraw 包装器 (wined3d/cnc-ddraw)
 * 音频：alsa / pulseaudio
 * 系统：Wine 版本 (proton-9.0-x86_64 / proton-9.0-arm64ec) / WoW64 模式 /
 *       启动服务等级（正常/必要/激进）
 * 组件：direct3d / directsound / directmusic / directshow / directplay / vcrun2010
 *       （原生=1 / 内置=0，与 Winlator wincomponents 一致）
 * 高级：盘符管理（X:→路径） / 环境变量（KEY=VALUE） / CPU 亲和（如 "0-3"） /
 *       Box64 预设 / 模拟器 (Box64/FEXCore) / FEXCore 版本
 *
 * 显示后端说明：本集成统一使用 AnWind X11（lorie）显示端 —— 容器屏幕分辨率
 * 经分辨率握手映射到 X 屏幕；DXVK/VKD3D+turnip 为 GPU 主路径。
 */
@Composable
fun ContainerEditorSheet(container: Container?, onDismiss: () -> Unit) {
    val theme = LocalWinTheme.current

    // ================= 表单状态（新建时取 Winlator 默认值） =================
    var name by remember { mutableStateOf(container?.getName() ?: "") }
    var screenSize by remember { mutableStateOf(container?.getScreenSize() ?: Container.DEFAULT_SCREEN_SIZE) }
    var graphicsDriver by remember { mutableStateOf(container?.getGraphicsDriver() ?: Container.DEFAULT_GRAPHICS_DRIVER) }
    var graphicsDriverConfig by remember { mutableStateOf(
        container?.getGraphicsDriverConfig()?.takeIf { it.isNotEmpty() } ?: GraphicsDriverConfigParser.DEFAULT_CONFIG
    ) }
    var dxwrapper by remember { mutableStateOf(container?.getDXWrapper() ?: Container.DEFAULT_DXWRAPPER) }
    var dxwrapperConfig by remember { mutableStateOf(container?.getDXWrapperConfig() ?: "") }
    var ddrawrapper by remember { mutableStateOf(container?.getDDrawWrapper() ?: "wined3d") }
    var audioDriver by remember { mutableStateOf(container?.getAudioDriver() ?: Container.DEFAULT_AUDIO_DRIVER) }
    var wincomponents by remember { mutableStateOf(container?.getWinComponents() ?: Container.DEFAULT_WINCOMPONENTS) }
    var drives by remember { mutableStateOf(container?.getDrives() ?: defaultDrives()) }
    var envVarsText by remember { mutableStateOf(container?.getEnvVars() ?: Container.DEFAULT_ENV_VARS) }
    var cpuList by remember { mutableStateOf(container?.getCPUList() ?: "") }
    var box64Preset by remember { mutableStateOf(container?.getBox64Preset() ?: "compatibility") }
    var emulator by remember { mutableStateOf(container?.getEmulator() ?: "box64") }
    var wineVersion by remember { mutableStateOf(container?.getWineVersion() ?: "proton-9.0-x86_64") }
    var wow64 by remember { mutableStateOf(container?.isWoW64Mode() ?: true) }
    var startupSelection by remember { mutableStateOf(container?.getStartupSelection()?.toInt() ?: 1) }
    var showFPS by remember { mutableStateOf(container?.isShowFPS() ?: false) }

    val dark = theme.isDark
    val cardBg = if (dark) Color(0xFF1B222B) else Color.White
    val labelColor = theme.windowTitleBarTextColor
    val subColor = labelColor.copy(alpha = 0.65f)
    val fieldBg = if (dark) Color(0xFF232B36) else Color(0xFFEFF2F5)

    fun applyWinComponent(id: String, value: Boolean) {
        val map = LinkedHashMap<String, String>()
        wincomponents.split(',').forEach { p ->
            val kv = p.split('=', limit = 2)
            if (kv.size == 2) map[kv[0]] = kv[1]
        }
        map[id] = if (value) "1" else "0"
        wincomponents = map.entries.joinToString(",") { "${it.key}=${it.value}" }
    }
    fun winComponent(id: String): Boolean =
        wincomponents.split(',').firstOrNull { it.startsWith("$id=") }?.substringAfterLast('=') == "1"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (dark) Color(0xFF14181D) else Color(0xFFF6F7F9))
    ) {
        // ===== 标题行 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.windowTitleBarColor)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (container == null) "新建容器" else "容器设置 · ${container.getName()}",
                color = theme.windowTitleBarTextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("取消", fontSize = 11.sp) }
            TextButton(onClick = {
                val data = JSONObject()
                try {
                    if (container != null) data.put("id", container.id)
                    data.put("name", name.ifEmpty { "Container" })
                    data.put("screenSize", screenSize)
                    data.put("graphicsDriver", graphicsDriver)
                    data.put("graphicsDriverConfig", graphicsDriverConfig)
                    data.put("dxwrapper", dxwrapper)
                    if (dxwrapperConfig.isNotEmpty()) data.put("dxwrapperConfig", dxwrapperConfig)
                    data.put("ddrawrapper", ddrawrapper)
                    data.put("audioDriver", audioDriver)
                    data.put("wincomponents", wincomponents)
                    data.put("drives", drives)
                    data.put("envVars", envVarsText)
                    if (cpuList.isNotEmpty()) data.put("cpuList", cpuList)
                    data.put("box64Preset", box64Preset)
                    data.put("emulator", emulator)
                    data.put("wineVersion", wineVersion)
                    data.put("wow64Mode", wow64)
                    data.put("startupSelection", startupSelection)
                    data.put("showFPS", showFPS)
                } catch (_: Exception) {}

                if (container == null) {
                    WinlatorController.createContainer(data) { onDismiss() }
                } else {
                    try {
                        container.setName(data.optString("name", container.getName()))
                        container.setScreenSize(screenSize)
                        container.setGraphicsDriver(graphicsDriver)
                        container.setGraphicsDriverConfig(graphicsDriverConfig)
                        container.setDXWrapper(dxwrapper)
                        container.setDXWrapperConfig(dxwrapperConfig)
                        container.setDDrawWrapper(ddrawrapper)
                        container.setAudioDriver(audioDriver)
                        container.setWinComponents(wincomponents)
                        container.setDrives(drives)
                        container.setEnvVars(envVarsText)
                        container.setCPUList(cpuList)
                        container.setBox64Preset(box64Preset)
                        container.setEmulator(emulator)
                        container.setWineVersion(wineVersion)
                        container.setWoW64Mode(wow64)
                        container.setStartupSelection(startupSelection.toByte())
                        container.setShowFPS(showFPS)
                    } catch (_: Exception) {}
                    WinlatorController.saveContainer(container)
                    onDismiss()
                }
            }) { Text("保存", fontSize = 11.sp, color = theme.accentColor) }
        }

        // ===== 表单体 =====
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---------- 常规 ----------
            SectionTitle("常规", labelColor)
            SectionCard(cardBg) {
                FieldRow("名称", labelColor, subColor) {
                    SmallTextField(name, { name = it }, fieldBg, labelColor, "Container")
                }
                FieldRow("屏幕分辨率", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("800x600", "1280x720", "1280x800", "1920x1080", "native").forEach { r ->
                            Chip(r, screenSize == r, theme) { screenSize = r }
                        }
                    }
                }
                Text(
                    "native = X 屏幕跟随窗口尺寸（推荐）；其余为固定分辨率（经分辨率握手映射到 X11 屏幕）",
                    color = subColor, fontSize = 9.sp, lineHeight = 12.sp
                )
            }

            // ---------- 图形 ----------
            SectionTitle("图形", labelColor)
            SectionCard(cardBg) {
                FieldRow("图形驱动", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("turnip", "wrapper", "virgl", "llvmpipe").forEach { d ->
                            Chip(d, graphicsDriver == d, theme) { graphicsDriver = d }
                        }
                    }
                }
                Text(
                    "AnWind X11 显示端：turnip（Adreno GPU，DXVK/VKD3D 主路径）推荐；" +
                        "wrapper/virgl 依赖 Winlator 自带 X server 的路径不可用，将回落 turnip；" +
                        "llvmpipe 为软件渲染",
                    color = subColor, fontSize = 9.sp, lineHeight = 12.sp
                )
                FieldRow("DX 包装器", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("dxvk", "vkd3d", "wined3d").forEach { d ->
                            Chip(d, dxwrapper == d, theme) { dxwrapper = d }
                        }
                    }
                }
                if (dxwrapper == "dxvk" || dxwrapper == "vkd3d") {
                    FieldRow("DXVK 配置", labelColor, subColor) {
                        SmallTextField(
                            dxwrapperConfig,
                            { dxwrapperConfig = it },
                            fieldBg, labelColor,
                            "version=${DefaultVersion.DXVK},framerate=0,maxDeviceMemory=0,async=0,asyncCache=0",
                            singleLine = false
                        )
                    }
                }
                FieldRow("DDraw 包装器", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("wined3d", "cnc-ddraw").forEach { d ->
                            Chip(d, ddrawrapper == d, theme) { ddrawrapper = d }
                        }
                    }
                }
                FieldRow("音频驱动", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("alsa", "pulseaudio").forEach { d ->
                            Chip(d, audioDriver == d, theme) { audioDriver = d }
                        }
                    }
                }
            }

            // ---------- 系统 ----------
            SectionTitle("系统", labelColor)
            SectionCard(cardBg) {
                FieldRow("Wine 版本", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("proton-9.0-x86_64", "proton-9.0-arm64ec").forEach { w ->
                            Chip(w, wineVersion == w, theme) { wineVersion = w }
                        }
                    }
                }
                FieldRow("WoW64 模式", labelColor, subColor) {
                    SwitchChip(wow64, theme) { wow64 = it }
                }
                FieldRow("启动服务", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(0 to "正常", 1 to "必要", 2 to "激进").forEach { (v, t) ->
                            Chip(t, startupSelection == v, theme) { startupSelection = v }
                        }
                    }
                }
                FieldRow("性能覆盖显示", labelColor, subColor) {
                    SwitchChip(showFPS, theme) { showFPS = it }
                }
            }

            // ---------- Windows 组件 ----------
            SectionTitle("Windows 组件", labelColor)
            SectionCard(cardBg) {
                listOf(
                    "direct3d" to "Direct3D",
                    "directsound" to "DirectSound",
                    "directmusic" to "DirectMusic",
                    "directshow" to "DirectShow",
                    "directplay" to "DirectPlay",
                    "vcrun2010" to "VC++ 2010 运行库"
                ).forEach { (id, label) ->
                    FieldRow(label, labelColor, subColor) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (winComponent(id)) "原生" else "内置",
                                color = if (winComponent(id)) theme.accentColor else subColor,
                                fontSize = 10.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            SwitchChip(winComponent(id), theme) { applyWinComponent(id, it) }
                        }
                    }
                }
            }

            // ---------- 盘符 ----------
            SectionTitle("盘符管理", labelColor)
            SectionCard(cardBg) {
                val driveList = remember(drives) { parseDrives(drives) }
                if (driveList.isEmpty()) Text("（无自定义盘符）", color = subColor, fontSize = 10.sp)
                driveList.forEach { drive ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            drive.first.uppercase() + ": → " + drive.second,
                            color = labelColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "移除", color = Color(0xFFE57373), fontSize = 10.sp,
                            modifier = Modifier
                                .clickable {
                                    drives = rebuildDrives(drives, drive.first, null)
                                }
                                .padding(4.dp)
                        )
                    }
                }
                AddDriveRow(fieldBg, labelColor, subColor) { letter, path ->
                    drives = if (drives.isBlank()) "$letter$path" else "$drives:$letter$path"
                }
                Text(
                    "格式：盘符字母后跟路径，段间以 \":\" 分隔（与 Winlator drives 协议一致）。" +
                        "运行时自动补 c:/z: 标准盘符；exe 位于 /storage 时自动绑定 y: 盘。",
                    color = subColor, fontSize = 9.sp, lineHeight = 12.sp
                )
            }

            // ---------- 高级 ----------
            SectionTitle("高级", labelColor)
            SectionCard(cardBg) {
                FieldRow("环境变量", labelColor, subColor) {
                    SmallTextField(
                        envVarsText, { envVarsText = it }, fieldBg, labelColor,
                        "KEY=value 空格分隔", singleLine = false
                    )
                }
                FieldRow("CPU 亲和", labelColor, subColor) {
                    SmallTextField(cpuList, { cpuList = it }, fieldBg, labelColor, "0-3", Modifier.width(140.dp))
                }
                FieldRow("Box64 预设", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("performance", "compatibility", "intermediate", "stability", "custom").forEach { p ->
                            Chip(p.take(4), box64Preset == p, theme) { box64Preset = p }
                        }
                    }
                }
                FieldRow("模拟器", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("box64", "fexcore").forEach { e ->
                            Chip(e, emulator == e, theme) { emulator = e }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "显示后端：AnWind X11（termux-x11/lorie + LorieView 浮动窗口）。\n" +
                    "启动后 X11 桌面窗口自动弹出并连接；容器分辨率经握手映射到 X 屏幕。",
                color = subColor, fontSize = 9.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ================= 小组件 =================

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SectionCard(bg: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun FieldRow(label: String, labelColor: Color, subColor: Color, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = labelColor, fontSize = 11.sp, modifier = Modifier.width(90.dp))
        content()
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, theme: com.anwind.core.theme.WinTheme, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.White else theme.windowTitleBarTextColor,
        fontSize = 10.sp,
        modifier = Modifier
            .background(
                if (selected) theme.accentColor
                else theme.windowTitleBarTextColor.copy(alpha = 0.08f),
                RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

@Composable
private fun SwitchChip(checked: Boolean, theme: com.anwind.core.theme.WinTheme, onChange: (Boolean) -> Unit) {
    Text(
        if (checked) "开" else "关",
        color = if (checked) Color.White else theme.windowTitleBarTextColor,
        fontSize = 10.sp,
        modifier = Modifier
            .background(
                if (checked) theme.accentColor
                else theme.windowTitleBarTextColor.copy(alpha = 0.15f),
                RoundedCornerShape(6.dp)
            )
            .clickable { onChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun SmallTextField(
    value: String, onValueChange: (String), fieldBg: Color, textColor: Color,
    placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = textColor),
        placeholder = { Text(placeholder, fontSize = 9.sp) },
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp, max = 120.dp)
    )
}

@Composable
private fun AddDriveRow(fieldBg: Color, labelColor: Color, subColor: Color, onAdd: (String, String) -> Unit) {
    var letter by remember { mutableStateOf("d") }
    var path by remember { mutableStateOf("") }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.material3.OutlinedTextField(
                value = letter, onValueChange = { letter = it.take(1).lowercase() },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                modifier = Modifier.width(64.dp)
            )
            androidx.compose.material3.OutlinedTextField(
                value = path, onValueChange = { path = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                placeholder = { Text("/storage/emulated/0/...", fontSize = 9.sp) },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                if (letter.isNotBlank() && path.isNotBlank()) {
                    onAdd(letter.lowercase(), path.trimEnd('/'))
                    path = ""
                }
            }) { Text("添加盘符", fontSize = 10.sp) }
        }
    }
}

/**
 * 盘符串解析（与 Container.drivesIterator 同源算法）：格式
 * "D:/path/E:/path2"，盘符字母 = 每个冒号前的字符。
 */
internal fun parseDrives(drives: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var idx = drives.indexOf(':')
    while (idx != -1) {
        if (idx == 0) { idx = drives.indexOf(':', idx + 1); continue }
        val letter = drives[idx - 1].toString()
        val next = drives.indexOf(':', idx + 1)
        val path = drives.substring(idx + 1, if (next == -1) drives.length else next - 1)
        if (path.isNotBlank()) out.add(letter to path)
        idx = next
    }
    return out
}

/** 重建盘符串：[removeLetter] 传 null 表示无移除（保留原样）。 */
internal fun rebuildDrives(drives: String, removeLetter: String?, keep: String?): String {
    val kept = parseDrives(drives)
        .filter { removeLetter == null || it.first != removeLetter }
        .joinToString(":") { "${it.first}${it.second}" }
    return kept
}

/** 新容器默认盘符（与 Winlator Container.DEFAULT_DRIVES 语义一致，指向本应用包名）。 */
private fun defaultDrives(): String {
    val downloads = android.os.Environment
        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)?.path
        ?: "/storage/emulated/0/Download"
    val storage = "/data/data/com.anwind/storage"
    return "D:${downloads}E:$storage"
}
