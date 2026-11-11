package com.anwind.core.input.gamepad

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.AnWindApp
import com.anwind.core.input.TrackpadRouter
import com.anwind.core.input.boundsInWindowCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 虚拟手柄设置面板（v3.2.23 用户问题 4 重制；v3.4.5 配置文件 + 居中）：
 *
 * **v3.4.5（用户："手柄配置界面太靠下" + 配置文件功能）**：
 * - 面板从贴底（BottomCenter + 8dp）改为【屏幕居中稍偏上】—— 展开
 *   的映射网格在视野中心，拇指不用下探；
 * - 新增配置文件（.vgp = Virtual GamePad 三字母缩写，用户指定）：
 *   「💾 保存.vgp」→ SAF 写入任意位置（建议 Download/）；「📂
 *   读取.vgp」→ 选择 .vgp → 即时装载（元素/映射/球位全套）。文件
 *   内容 = GamepadController.toJson() 同一 JSON（版本字段内含）。
 *
 * **旧版痛点**（用户反馈"映射界面太麻烦"）：右侧卡片 + 点元素 → 选中 →
 * 滚动到编辑区 → 点"按键映射"按钮 → 弹出二级全屏选择器 → 点分组 →
 * 点键，六级操作；位置 X/Y 滑杆微调极慢；按钮标签 chips 一排（只决定
 * 配色却占一屏注意力）。
 *
 * **重制设计（单层底栏面板）**：
 * - 面板贴底居中，一条窄顶栏（预设 / 添加 / 编辑模式 / 完成）+ 元素行
 *   列表，**一层结构到底**；
 * - 点元素行 → **原地展开**编辑区：
 *   - 按钮元素：分组 Tab + 按键网格**直接点选即完成映射**（当前映射
 *     高亮显示，不进二级页）；
 *   - 摇杆/十字键：方向模式（←↑→↓ / WASD）一键切换；
 *   - 尺寸 −/+ 步进按钮（替代滑杆）；
 *   - 删除该元素；
 * - **去 posX/posY 滑杆**：编辑模式（✎）下直接在屏幕上拖动元素定位，
 *   比滑杆直观且精确；
 * - **去标签配色 chips**：按钮配色由 label 自动决定（A绿/B红/X蓝/Y黄），
 *   表面显示映射按键名；
 * - 修改即时持久化；布局在面板上层实时可见（面板只占底部）。
 */
@Composable
fun GamepadSettingsWindow(modifier: Modifier = Modifier) {
    if (!GamepadController.settingsOpen) return

    // v2.20 语义保留：设置面板登记为触控板直通区（真实手指可直接操作）；
    // 关闭/离开组合时注销
    DisposableEffect(Unit) {
        onDispose { TrackpadRouter.registerPassthrough("gpSettings", null) }
    }

    val app = AnWindApp.get()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val elements = GamepadController.elements

    // 展开的元素行（v3.2.23：原地展开，单层结构；null = 全收起）
    var expandedId by remember { mutableStateOf<String?>(null) }

    // v3.4.5：.vgp 配置文件读写（SAF）。保存用 CreateDocument（默认名
    // 含时间戳），读取用 OpenDocument（宽松 MIME —— SAF 对未注册的
    // 自定义后缀常报 octet-stream，多候选防滤空）。读入成功后立即
    // 持久化进 DataStore（跨会话保留）。
    var ioMsg by remember { mutableStateOf<String?>(null) }
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(GamepadController.toJson().toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开输出流")
                    }.isSuccess
                }
                ioMsg = if (ok) "✅ 已保存 ${(uri.lastPathSegment ?: "配置").substringAfterLast('/')}"
                else "❌ 保存失败（该位置不可写）"
            }
        }
    }
    val loadLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            ins.readBytes().toString(Charsets.UTF_8)
                        } ?: ""
                    }.getOrDefault("")
                }
                val loaded = json.isNotBlank() &&
                    runCatching { GamepadController.loadFromJson(json); true }.getOrDefault(false)
                if (loaded) {
                    app.settingsStore.setGamepadConfig(json)
                    ioMsg = "✅ 已读取 ${(uri.lastPathSegment ?: "配置").substringAfterLast('/')}"
                } else {
                    ioMsg = "❌ 无效 .vgp 文件"
                }
            }
        }
    }
    LaunchedEffect(ioMsg) {
        if (ioMsg != null) {
            kotlinx.coroutines.delay(2400)
            ioMsg = null
        }
    }

    fun persist() {
        scope.launch { app.settingsStore.setGamepadConfig(GamepadController.toJson()) }
    }

    fun updateElement(id: String, transform: (GamepadController.PadElement) -> GamepadController.PadElement) {
        GamepadController.updateElements(elements.map { if (it.id == id) transform(it) else it })
        persist()
    }

    fun addElement(type: GamepadController.ElementType) {
        val newElement = when (type) {
            GamepadController.ElementType.BUTTON -> GamepadController.PadElement(
                type = type, label = "A",
                action = GamepadController.PadAction.key(android.view.KeyEvent.KEYCODE_SPACE, "Space"),
                posX = 0.5f, posY = 0.5f, sizeDp = 60f
            )
            GamepadController.ElementType.JOYSTICK -> GamepadController.PadElement(
                type = type, label = "摇杆",
                posX = 0.2f, posY = 0.5f, sizeDp = 150f
            )
            GamepadController.ElementType.DPAD -> GamepadController.PadElement(
                type = type, label = "十字键",
                posX = 0.8f, posY = 0.5f, sizeDp = 120f
            )
        }
        GamepadController.updateElements(elements + newElement)
        expandedId = newElement.id
        persist()
    }

    fun applyPreset(which: String) {
        GamepadController.updateElements(
            if (which == "simple") GamepadController.simpleLayout() else GamepadController.classicLayout()
        )
        GamepadController.selectElement(null)
        expandedId = null
        persist()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ===== 底栏面板（贴底居中，最大宽 520dp，最大高 55%） =====
        Column(
            modifier = modifier
                .align(Alignment.Center)
                .padding(bottom = 40.dp)
                .widthIn(min = 300.dp, max = 520.dp)
                .heightIn(max = 460.dp)
                .onGloballyPositioned {
                    TrackpadRouter.registerPassthrough("gpSettings", it.boundsInWindowCompat())
                }
                .shadow(14.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xF0162030))
                .border(1.dp, Color(0x592DD4BF), RoundedCornerShape(18.dp))
        ) {
            // ---- 顶栏：预设 / 添加 / 编辑模式 / 完成 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x262DD4BF))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GpChip("经典", false, compact = true) { applyPreset("classic") }
                GpChip("简单", false, compact = true) { applyPreset("simple") }
                GpVDivider()
                // v3.4.5：.vgp 配置文件（Virtual GamePad 三字母缩写）
                GpChip("💾 保存.vgp", false, compact = true) {
                    saveLauncher.launch("anwind-gamepad-${
                        java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
                            .format(java.util.Date())
                    }.vgp")
                }
                GpChip("📂 读取.vgp", false, compact = true) {
                    loadLauncher.launch(arrayOf(
                        "application/octet-stream", "application/json",
                        "application/vnd.oasis.opendocument.text", "text/plain"
                    ))
                }
                GpVDivider()
                GpChip("＋键", false, compact = true) { addElement(GamepadController.ElementType.BUTTON) }
                GpChip("＋摇杆", false, compact = true) { addElement(GamepadController.ElementType.JOYSTICK) }
                GpChip("＋十字", false, compact = true) { addElement(GamepadController.ElementType.DPAD) }
                GpVDivider()
                GpChip(
                    if (GamepadController.editMode) "✓ 拖动元素" else "✎ 编辑模式",
                    active = GamepadController.editMode, compact = true
                ) {
                    GamepadController.editMode = !GamepadController.editMode
                    if (!GamepadController.editMode) GamepadController.selectElement(null)
                }
                Spacer(Modifier.width(4.dp))
                GpChip("完成", false, compact = true) { GamepadController.settingsOpen = false }
            }

            // ---- v3.4.5：保存/读取 .vgp 结果提示条 ----
            if (ioMsg != null) {
                Text(
                    ioMsg ?: "",
                    color = if (ioMsg.orEmpty().startsWith("✅")) Color(0xFF7FE8DC) else Color(0xFFFFB4B4),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x1AFFFFFF))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // ---- 元素行列表（可滚动） ----
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (elements.isEmpty()) {
                    GpHint("暂无元素 —— 用顶栏 ＋ 添加，或套用「经典」预设")
                }
                elements.forEach { el ->
                    GpElementRowV2(
                        element = el,
                        expanded = el.id == expandedId,
                        onToggle = { expandedId = if (expandedId == el.id) null else el.id },
                        onMap = { action ->
                            updateElement(el.id) { it.copy(action = action) }
                        },
                        onDirMode = { mode ->
                            updateElement(el.id) { it.copy(dirMode = mode) }
                        },
                        onSize = { delta ->
                            updateElement(el.id) {
                                it.copy(sizeDp = (it.sizeDp + delta).coerceIn(40f, 220f))
                            }
                        },
                        onDelete = {
                            GamepadController.updateElements(elements.filter { it.id != el.id })
                            if (expandedId == el.id) expandedId = null
                            persist()
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ============================================================
// 组件（v3.2.23 单层重制）
// ============================================================

/** 元素行：一行摘要 + 原地展开编辑区（映射网格 / 方向模式 / 尺寸步进）。 */
@Composable
private fun GpElementRowV2(
    element: GamepadController.PadElement,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMap: (GamepadController.PadAction) -> Unit,
    onDirMode: (GamepadController.DirMode) -> Unit,
    onSize: (Float) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (expanded) Color(0x1A2DD4BF) else Color(0x14FFFFFF))
            .border(1.dp, if (expanded) Color(0x662DD4BF) else Color(0x1FFFFFFF), RoundedCornerShape(12.dp))
    ) {
        // ---- 行摘要：图标 + 类型 + 当前映射 + 尺寸步进 + 删除 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val icon = when (element.type) {
                GamepadController.ElementType.JOYSTICK -> "🕹"
                GamepadController.ElementType.DPAD -> "✛"
                GamepadController.ElementType.BUTTON -> "⏺"
            }
            Text(icon, fontSize = 15.sp, color = Color(0xFF7FE8DC))
            Text(
                typeLabel(element),
                color = Color(0xFFE2F5FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            // 当前映射（按钮=按键名；摇杆/十字键=方向模式）
            Text(
                if (element.type == GamepadController.ElementType.BUTTON) {
                    "→ ${element.action.label.ifEmpty { "未映射" }}"
                } else {
                    "→ ${if (element.dirMode == GamepadController.DirMode.WASD) "WASD" else "←↑→↓"}"
                },
                color = Color(0xFF9AD8F5),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            // 尺寸步进（−/+，替代滑杆）
            GpStepButton("−") { onSize(-10f) }
            Text(
                "${element.sizeDp.roundToInt()}dp",
                color = Color(0xFFB8C7D9),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(38.dp)
            )
            GpStepButton("＋") { onSize(+10f) }
            // 删除
            GpStepButton("🗑", danger = true, onClick = onDelete)
        }

        // ---- 原地展开的编辑区 ----
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (element.type == GamepadController.ElementType.BUTTON) {
                    // 按键网格：分组 Tab + 点键即完成映射（单层，无二级页）
                    KeyGridInline(
                        current = element.action,
                        onPick = onMap
                    )
                } else {
                    // 方向模式一键切换
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        GpChip(
                            "←↑→↓ 方向键",
                            active = element.dirMode == GamepadController.DirMode.ARROWS,
                            compact = true
                        ) { onDirMode(GamepadController.DirMode.ARROWS) }
                        GpChip(
                            "WASD",
                            active = element.dirMode == GamepadController.DirMode.WASD,
                            compact = true
                        ) { onDirMode(GamepadController.DirMode.WASD) }
                    }
                    GpHint("位置：顶栏 ✎ 编辑模式下直接在屏幕上拖动元素")
                }
            }
        }
    }
}

/** 按键网格（内嵌版）：分组 Tab + 5 列网格，点键即完成映射。 */
@Composable
private fun KeyGridInline(
    current: GamepadController.PadAction,
    onPick: (GamepadController.PadAction) -> Unit
) {
    var activeGroup by remember { mutableStateOf(0) }
    val groups = GamepadController.ACTION_GROUPS

    Column(modifier = Modifier.fillMaxWidth()) {
        // 分组 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            groups.forEachIndexed { i, (name, _) ->
                GpChip(name, active = i == activeGroup, compact = true) { activeGroup = i }
            }
        }
        Spacer(Modifier.height(6.dp))

        // 网格（5 列；当前映射高亮）
        val actions = groups[activeGroup].second
        actions.chunked(5).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowActions.forEach { action ->
                    val isCurrent = action.keyCode == current.keyCode && action.kind == current.kind
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCurrent) Color(0x662DD4BF) else Color(0x1FFFFFFF))
                            .border(
                                1.dp,
                                if (isCurrent) Color(0xFF2DD4BF) else Color(0x26FFFFFF),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onPick(action) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = action.label,
                            color = if (isCurrent) Color.White else Color(0xFFE2F5FF),
                            fontSize = 12.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
                repeat(5 - rowActions.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 小型步进/操作按钮（−/＋/🗑）。 */
@Composable
private fun GpStepButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    pressed -> if (danger) Color(0x66FF6B6B) else Color(0x332DD4BF)
                    danger -> Color(0x1FFF6B6B)
                    else -> Color(0x1AFFFFFF)
                }
            )
            .clickable(onClick = onClick)
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (danger) Color(0xFFFFB4B4) else Color(0xFFE2F5FF),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun GpVDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(18.dp)
            .background(Color(0x33FFFFFF))
    )
}

@Composable
private fun GpChip(label: String, active: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    active -> Color(0x662DD4BF)
                    pressed -> Color(0x332DD4BF)
                    else -> Color(0x1AFFFFFF)
                }
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 4.dp else 6.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (active) Color.White else Color(0xFFDCEBF7),
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun GpHint(text: String) {
    Text(
        text,
        color = Color(0x99B8C7D9),
        fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

private fun typeLabel(element: GamepadController.PadElement): String = when (element.type) {
    GamepadController.ElementType.JOYSTICK -> "摇杆"
    GamepadController.ElementType.DPAD -> "十字键"
    GamepadController.ElementType.BUTTON -> "按钮「${element.label}」"
}
