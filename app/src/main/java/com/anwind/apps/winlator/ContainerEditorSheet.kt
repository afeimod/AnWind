package com.anwind.apps.winlator

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.winlator.cmod.contents.AdrenotoolsManager
import com.winlator.cmod.contents.ContentProfile
import com.winlator.cmod.contents.ContentsManager
import com.winlator.cmod.core.DefaultVersion
import com.winlator.cmod.core.GraphicsDriverConfigParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val context = androidx.compose.ui.platform.LocalContext.current

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

    // ---- v8：key=value 配置串读写（graphicsDriverConfig 用 ';' 分隔，
    //      DXVK/VKD3D 配置用 ',' 分隔；与引擎侧解析器格式一致）----
    // v9 修复：Kotlin 局部函数必须先声明后使用，此二者原声明位于下方使用点
    // 之后，导致 CI 报 Unresolved reference: getConfigKey 及委托类型推断失败
    fun setConfigKey(config: String, key: String, value: String, sep: Char = ';'): String {
        val map = LinkedHashMap<String, String>()
        config.split(sep).forEach { p ->
            val kv = p.split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) map[kv[0]] = kv[1]
        }
        map[key] = value
        return map.entries.joinToString(sep.toString()) { "${it.key}=${it.value}" }
    }
    fun getConfigKey(config: String, key: String, sep: Char = ';'): String =
        config.split(sep).firstOrNull { it.startsWith("$key=") }?.substringAfter('=', "") ?: ""

    // ---- v8：驱动配置（graphicsDriverConfig）子字段（此前有状态无 UI）----
    var driverVersion by remember {
        mutableStateOf(getConfigKey(graphicsDriverConfig, "version").ifEmpty { "System" })
    }
    var driverBlacklist by remember { mutableStateOf(getConfigKey(graphicsDriverConfig, "blacklistedExtensions")) }
    var driverMaxMem by remember { mutableStateOf(getConfigKey(graphicsDriverConfig, "maxDeviceMemory")) }
    var driverFrameSync by remember {
        mutableStateOf(if (getConfigKey(graphicsDriverConfig, "frameSync") == "1") "1" else "0")
    }

    // ---- v8：DXVK/VKD3D 版本与已安装 Adreno 驱动列表（后台加载）----
    var dxvkVersions by remember { mutableStateOf(listOf(DefaultVersion.DXVK)) }
    var vkd3dVersions by remember { mutableStateOf(listOf(DefaultVersion.VKD3D)) }
    var installedDrivers by remember { mutableStateOf(listOf<String>()) }
    // 选中的 DX 包装器版本（dxvk 用 version= 键；vkd3d 用 vkd3dVersion= 键
    // —— v10 修复：引擎侧 VKD3DConfig/setupWineSystemFiles 读 vkd3dVersion，
    // 此前写 version 键导致 vkd3d 版本永不生效）
    var dxvkVersion by remember {
        mutableStateOf(getConfigKey(dxwrapperConfig, "version", ',').ifEmpty { DefaultVersion.DXVK })
    }
    var vkd3dVersion by remember {
        mutableStateOf(
            getConfigKey(dxwrapperConfig, "vkd3dVersion", ',')
                .ifEmpty { getConfigKey(dxwrapperConfig, "version", ',') }
                .ifEmpty { DefaultVersion.VKD3D }
        )
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val cm = ContentsManager(context)
                cm.syncContents()
                val dx = cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK)
                    ?.map { it.verName }.orEmpty()
                val vk = cm.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_VKD3D)
                    ?.map { it.verName }.orEmpty()
                val drv = AdrenotoolsManager(context).enumarateInstalledDrivers().toList()
                dxvkVersions = (listOf(DefaultVersion.DXVK) + dx).distinct()
                vkd3dVersions = (listOf(DefaultVersion.VKD3D) + vk).distinct()
                installedDrivers = drv
            } catch (_: Exception) {}
        }
    }

    // ---- v10：预设选择器（点击弹列表，替代手输文本框）----
    var showDxvkPreset by remember { mutableStateOf(false) }
    var showMaxMemPreset by remember { mutableStateOf(false) }
    var showBlacklistPreset by remember { mutableStateOf(false) }
    var dxvkConfigLabel by remember { mutableStateOf(if (dxwrapperConfig.isEmpty()) "默认" else "自定义") }

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
                    // v8：驱动配置由 UI 子字段回写（驱动版本/黑名单/最大显存/帧同步）
                    var gc = graphicsDriverConfig
                    gc = setConfigKey(gc, "version", driverVersion)
                    gc = setConfigKey(gc, "blacklistedExtensions", driverBlacklist)
                    gc = setConfigKey(gc, "maxDeviceMemory", driverMaxMem)
                    gc = setConfigKey(gc, "frameSync", driverFrameSync)
                    graphicsDriverConfig = gc
                    data.put("graphicsDriverConfig", gc)
                    // v8：DXVK/VKD3D 版本选择回写；v10 修复键名——dxvk 用
                    // "version"，vkd3d 用 "vkd3dVersion"+"vkd3dLevel"（引擎侧
                    // setupWineSystemFiles/VKD3DConfig 按这两个键读取）
                    if (dxwrapper == "dxvk" || dxwrapper == "vkd3d") {
                        val base = dxwrapperConfig.ifEmpty {
                            if (dxwrapper == "dxvk") "version=${DefaultVersion.DXVK}"
                            else "vkd3dVersion=${DefaultVersion.VKD3D},vkd3dLevel=12_1"
                        }
                        if (dxwrapper == "dxvk") {
                            dxwrapperConfig = setConfigKey(base, "version", dxvkVersion, ',')
                        } else {
                            var cfg = setConfigKey(base, "vkd3dVersion", vkd3dVersion, ',')
                            if (getConfigKey(cfg, "vkd3dLevel", ',').isEmpty())
                                cfg = setConfigKey(cfg, "vkd3dLevel", "12_1", ',')
                            dxwrapperConfig = cfg
                        }
                    }
                    data.put("dxwrapper", dxwrapper)
                    if (dxwrapperConfig.isNotEmpty()) data.put("dxwrapperConfig", dxwrapperConfig)
                    data.put("ddrawrapper", ddrawrapper)
                    data.put("audioDriver", audioDriver)
                    data.put("wincomponents", wincomponents)
                    data.put("drives", sanitizeDrives(drives))
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
                    // v2.25：创建失败（回调参数为 null）时 Toast 提示，不再静默关闭 ——
                    // 典型原因：APK 缺少 <wine版本>_container_pattern.tzst 容器模板
                    WinlatorController.createContainer(data) { created ->
                        if (created == null) {
                            Toast.makeText(
                                context,
                                "容器创建失败：引擎缺少容器模板（wineprefix pattern）或配置异常，请更换最新 CI 构建并看 logcat",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        onDismiss()
                    }
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
                        container.setDrives(sanitizeDrives(drives))
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
                // v8：驱动版本选择（graphicsDriverConfig 的 version 键）——
                // System = 系统驱动；其余为已安装的 Adreno 驱动包（turnip）
                FieldRow("驱动版本", labelColor, subColor) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        listOf("System").forEach { d ->
                            Chip(d, driverVersion == d, theme) { driverVersion = d }
                        }
                        installedDrivers.forEach { d ->
                            Chip(d, driverVersion == d, theme) { driverVersion = d }
                        }
                    }
                }
                Text(
                    if (installedDrivers.isEmpty())
                        "仅内置系统驱动；安装 Adreno 驱动包后此处会出现可选版本（保存后对容器生效）"
                    else
                        "System = 系统驱动；其余为已安装的 Adreno 驱动包，启动容器时自动挂载",
                    color = subColor, fontSize = 9.sp, lineHeight = 12.sp
                )
                // v10：点击弹列表选择，替代手输文本框
                FieldRow("扩展黑名单", labelColor, subColor) {
                    Chip(
                        if (driverBlacklist.isEmpty()) "无（点击选择）"
                        else if (driverBlacklist.length > 16) driverBlacklist.take(15) + "…"
                        else driverBlacklist,
                        false, theme
                    ) { showBlacklistPreset = true }
                }
                // v10：点击弹列表选择，替代手输文本框
                FieldRow("最大显存(MB)", labelColor, subColor) {
                    Chip(
                        if (driverMaxMem.isEmpty() || driverMaxMem == "0") "不限（点击选择）"
                        else "${driverMaxMem} MB",
                        false, theme
                    ) { showMaxMemPreset = true }
                }
                FieldRow("帧同步", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("0" to "关", "1" to "开").forEach { (v, t) ->
                            Chip(t, driverFrameSync == v, theme) { driverFrameSync = v }
                        }
                    }
                }
                FieldRow("DX 包装器", labelColor, subColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("dxvk", "vkd3d", "wined3d").forEach { d ->
                            Chip(d, dxwrapper == d, theme) { dxwrapper = d }
                        }
                    }
                }
                if (dxwrapper == "dxvk" || dxwrapper == "vkd3d") {
                    // v8：DXVK/VKD3D 版本选择（默认内置版本 + 内容管理器已安装版本）
                    FieldRow(
                        if (dxwrapper == "dxvk") "DXVK 版本" else "VKD3D 版本",
                        labelColor, subColor
                    ) {
                        val versions = if (dxwrapper == "dxvk") dxvkVersions else vkd3dVersions
                        val selected = if (dxwrapper == "dxvk") dxvkVersion else vkd3dVersion
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            versions.forEach { v ->
                                Chip(v, selected == v, theme) {
                                    if (dxwrapper == "dxvk") dxvkVersion = v else vkd3dVersion = v
                                }
                            }
                        }
                    }
                    // v10：点击弹预设列表（帧率上限/异步着色/显存上限），
                    // 替代手输配置串；选中版本始终随预设一并写入
                    FieldRow("DXVK 配置", labelColor, subColor) {
                        Chip(dxvkConfigLabel, false, theme) { showDxvkPreset = true }
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
                    // v10 修复（exe 启动失败元凶）：drives 协议为"段=盘符字母
                    // +':'+路径，段间直接拼接、无分隔符"（Container.drivesIterator
                    // 以"每个冒号前一字符"定位盘符）。此前插入 ':' 分隔符会把
                    // 上一段路径截掉末位字符并产生幽灵盘符 → exe 的 DOS 路径
                    // 错误 → wine 找不到 exe。另：同盘符重复添加时先移除旧项。
                    val cleaned = rebuildDrives(drives, letter, null)
                    drives = if (cleaned.isBlank()) "$letter:$path" else "${cleaned}${letter}:$path"
                }
                Text(
                    "格式：盘符字母直接拼接在上一段路径之后（如 D:/pathE:/path2），" +
                        "段间无分隔符（与 Winlator drives 协议一致）。" +
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

    // ================= v10 预设选择对话框 =================
    if (showDxvkPreset) {
        if (dxwrapper == "vkd3d") {
            PickerDialog(
                title = "VKD3D 配置预设",
                options = listOf(
                    "vkd3dLevel=12_1" to "默认（特性等级 12_1）",
                    "vkd3dLevel=12_0" to "特性等级 12_0（兼容老游戏）",
                    "vkd3dLevel=12_2" to "特性等级 12_2（最高）"
                ),
                current = getConfigKey(dxwrapperConfig, "vkd3dLevel", ','),
                onDismiss = { showDxvkPreset = false }
            ) { value ->
                var cfg = setConfigKey(
                    dxwrapperConfig.ifEmpty { "vkd3dVersion=$vkd3dVersion" },
                    "vkd3dVersion", vkd3dVersion, ','
                )
                cfg = setConfigKey(cfg, "vkd3dLevel", value.substringAfter('='), ',')
                dxwrapperConfig = cfg
                dxvkConfigLabel = value.substringAfter('=')
                showDxvkPreset = false
            }
        } else {
            // 帧率上限 / 异步着色 / 显存上限 —— 与 DXVKConfig.setEnvVars 消费键一致
            PickerDialog(
                title = "DXVK 配置预设（DXVK $dxvkVersion）",
                options = listOf(
                    "0|0|0|0" to "默认（不限帧，无异步）",
                    "60|0|0|0" to "60 帧上限",
                    "30|0|0|0" to "30 帧上限（省电/降热）",
                    "120|0|0|0" to "120 帧上限（高刷屏）",
                    "0|0|1|1" to "异步着色（async 补丁版专用，减少卡顿）",
                    "0|4096|0|0" to "显存上限 4GB（老设备防杀后台）"
                ),
                current = listOf(
                    getConfigKey(dxwrapperConfig, "framerate", ','),
                    getConfigKey(dxwrapperConfig, "maxDeviceMemory", ','),
                    getConfigKey(dxwrapperConfig, "async", ','),
                    getConfigKey(dxwrapperConfig, "asyncCache", ',')
                ).joinToString("|"),
                onDismiss = { showDxvkPreset = false }
            ) { value ->
                val parts = value.split('|')
                var cfg = setConfigKey(
                    dxwrapperConfig.ifEmpty { "version=$dxvkVersion" },
                    "version", dxvkVersion, ','
                )
                cfg = setConfigKey(cfg, "framerate", parts[0], ',')
                cfg = setConfigKey(cfg, "maxDeviceMemory", parts[1], ',')
                cfg = setConfigKey(cfg, "async", parts[2], ',')
                cfg = setConfigKey(cfg, "asyncCache", parts[3], ',')
                dxwrapperConfig = cfg
                dxvkConfigLabel = when {
                    parts[0] != "0" -> "帧率上限 ${parts[0]}"
                    parts[2] == "1" -> "异步着色"
                    parts[1] != "0" -> "显存上限 ${parts[1]}MB"
                    else -> "默认"
                }
                showDxvkPreset = false
            }
        }
    }
    if (showMaxMemPreset) {
        PickerDialog(
            title = "最大显存(MB)",
            options = listOf(
                "0" to "不限",
                "512" to "512 MB",
                "1024" to "1024 MB",
                "2048" to "2048 MB",
                "4096" to "4096 MB",
                "8192" to "8192 MB"
            ),
            current = driverMaxMem,
            onDismiss = { showMaxMemPreset = false }
        ) { value ->
            driverMaxMem = value
            showMaxMemPreset = false
        }
    }
    if (showBlacklistPreset) {
        PickerDialog(
            title = "Vulkan 扩展黑名单",
            options = listOf(
                "" to "无（不屏蔽任何扩展）",
                "VK_KHR_external_memory_fd" to "屏蔽 VK_KHR_external_memory_fd",
                "VK_KHR_external_memory_fd,VK_EXT_external_memory_host" to
                    "屏蔽 external_memory_fd + external_memory_host"
            ),
            current = driverBlacklist,
            onDismiss = { showBlacklistPreset = false }
        ) { value ->
            driverBlacklist = value
            showBlacklistPreset = false
        }
    }
}

/**
 * v10：通用单选列表对话框（Compose 版"下拉选择"）——替代容器设置里
 * 原先需要手动输入的配置项。选中项高亮，点击即应用并关闭。
 */
@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 11.sp) }
        },
        title = { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (value == current) "● " else "○ ",
                            color = if (value == current) theme.accentColor else Color.Gray,
                            fontSize = 11.sp
                        )
                        Text(
                            label,
                            fontSize = 11.sp,
                            fontWeight = if (value == current) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    )
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
    value: String, onValueChange: (String) -> Unit, fieldBg: Color, textColor: Color,
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
    // v10 修复：段=盘符字母+':'+路径，段间直接拼接（字母即分隔）——
    // 不得插入额外 ':' 分隔符（会截断上一段路径末位并产生幽灵盘符），
    // 也不得丢失字母后的 ':'（整串会变成零个盘符段）
    val kept = parseDrives(drives)
        .filter { removeLetter == null || it.first != removeLetter }
        .joinToString("") { "${it.first}:${it.second}" }
    return kept
}

/**
 * v10：盘符串消毒 —— 丢弃路径不以 "/" 开头或盘符非单字母的畸形段
 * （v8-v9 曾以 ':' 分隔导致段错位，此处对已损坏存量数据在保存时自愈）。
 */
internal fun sanitizeDrives(drives: String): String =
    parseDrives(drives)
        .filter { (letter, path) ->
            letter.length == 1 && letter[0].isLetter() && path.startsWith("/")
        }
        .joinToString("") { "${it.first}:${it.second}" }

/** 新容器默认盘符（与 Winlator Container.DEFAULT_DRIVES 语义一致，指向本应用包名）。 */
private fun defaultDrives(): String {
    val downloads = android.os.Environment
        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)?.path
        ?: "/storage/emulated/0/Download"
    val storage = "/data/data/com.anwind/storage"
    return "D:${downloads}E:$storage"
}
