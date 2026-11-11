package com.anwind.apps.x11

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.WinDialog
import com.anwind.core.window.WindowManager
import com.termux.x11.LoriePreferences
import com.termux.x11.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v2.22.4 fix11c：X11 设置面板。
 *
 * 用户需求："x11 长按状态栏的设置等" —— 三个入口：
 * 1. 长按 "X11 桌面" 浮动窗口标题栏（AppDef.onTitleBarLongPress，见 X11App）；
 * 2. 常驻通知（X11KeepAliveService）的 "X11 设置" 动作 —— 顺带把 App
 *    拉回前台并打开/聚焦 X11 窗口；
 * 3. v3.1.7：X11 窗口标题栏菜单按钮（最小化左边，AppDef.onMenuButton）。
 *
 * v3.1.7：面板从平台 AlertDialog 改为 Windows 风格可移动小窗口
 * （WinDialog）—— 与桌面窗口同一视觉/交互（标题栏可拖动、点击置前、
 * X 关闭），不再是"大大的死窗口"。
 *
 * v3.2.13（用户问题 1）：全面重排 ——
 *   * 窗宽 340 → 420dp（旧宽度下选项行/开关说明大量换行挤压）；
 *   * 状态行升级为状态卡片（当前 X 屏幕一目了然）；
 *   * 各分区（显示/窗口/键盘/触摸/剪贴板/操作）改为统一分区卡片
 *     （左侧强调条 + 标题 + 圆角内容区），与容器管理器同一视觉语言；
 *   * 文本按钮组改为分段控件（XpSegmented：连体圆角组，选中强调色
 *     填充），三档选项一行放下且选中态醒目；
 *   * 开关行间距/字号统一，说明文字不再与开关换行打架。
 */
object X11SettingsBridge {
    private val _show = MutableStateFlow(false)

    /** 面板显示标志（粘性：通知入口先于窗口打开时依然生效）。 */
    val show: StateFlow<Boolean> = _show

    fun requestShow() { _show.value = true }
    fun dismiss() { _show.value = false }
}

/**
 * v2.25：LoriePreferences 的 Compose 可观察镜像。
 *
 * SharedPreferences 不是可观察存储，而方向 / 亮屏 / 附加键盘栏需要驱动
 * Compose 副作用（窗口标志、requestedOrientation、键行显隐），故设置
 * 面板写入 LoriePreferences 的同时同步本对象；X11Surface 进入时
 * [initIfNeed] 一次性从真实偏好回填（保留用户在兼容全屏模式里的既有
 * 选择）。
 */
object X11UiPrefs {
    private var initialized = false

    /** 屏幕方向：auto / portrait / landscape（reverse 归并为对应主方向）。 */
    var orientation by mutableStateOf("auto")

    /** 保持亮屏（X11 窗口存在期间生效）。 */
    var keepScreenOn by mutableStateOf(true)

    /** 附加键盘栏（X11 画面底部 ESC/TAB/方向键行）。 */
    var showAdditionalKbd by mutableStateOf(false)

    /**
     * v3.3.42：底部控制条（分辨率/切窗/键盘/游戏全屏/全屏）隐藏标志。
     *
     * 用户需求：X11 窗口上方加"隐藏"按钮，隐藏窗口下方的分辨率等设置栏，
     * 让游戏画面占满更大区域（标题栏按钮一键切换，状态持久化）。
     *
     * 持久化落 X11 自己的 SharedPreferences（不占 LoriePreferences 的键位
     * —— 那边是 termux-x11 的偏好域，改键会与上游冲突）。
     */
    var controlBarHidden by mutableStateOf(false)

    private const val SP_NAME = "anwind_x11_ui"
    private const val SP_KEY_CONTROL_BAR_HIDDEN = "control_bar_hidden"

    /** 读取持久化的控制条隐藏状态（进程内首次调用即可）。 */
    fun loadControlBarHidden(context: Context): Boolean {
        val v = try {
            context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .getBoolean(SP_KEY_CONTROL_BAR_HIDDEN, false)
        } catch (_: Exception) {
            false
        }
        controlBarHidden = v
        return v
    }

    /** 写入控制条隐藏状态（标题栏按钮切换时调用）。 */
    fun saveControlBarHidden(context: Context, hidden: Boolean) {
        controlBarHidden = hidden
        try {
            context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(SP_KEY_CONTROL_BAR_HIDDEN, hidden).apply()
        } catch (_: Exception) {}
    }

    /** 从 LoriePreferences 一次性同步（仅首次）。 */
    fun initIfNeed(prefs: Prefs) {
        if (initialized) return
        initialized = true
        orientation = normalizeOrientation(prefs.forceOrientation.get())
        keepScreenOn = prefs.keepScreenOn.get()
        showAdditionalKbd = prefs.showAdditionalKbd.get()
    }

    /** forceOrientation 的五档（含 reverse）归并为三档。 */
    fun normalizeOrientation(v: String): String = when (v) {
        "portrait", "reverse portrait" -> "portrait"
        "landscape", "reverse landscape" -> "landscape"
        else -> "auto"
    }
}

private val RES_PRESETS = listOf("640x480", "800x600", "1280x720", "1600x900", "1920x1080")

// ---------------------------------------------------------------------------
// v3.2.13 · 面板通用组件（与容器管理器 WinSectionCard/WinOptionPills 同视觉）
// ---------------------------------------------------------------------------

/** 分区卡片：强调条 + 标题 + 圆角内容区。 */
@Composable
private fun XpSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalWinTheme.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 13.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(theme.accentColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = theme.windowTitleBarTextColor
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(theme.cardBackgroundColor)
                .border(0.5.dp, theme.windowBorderColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

/** 字段行：标签（+可选说明）在上，控件在下。 */
@Composable
private fun XpField(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit
) {
    val theme = LocalWinTheme.current
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.windowTitleBarTextColor)
        if (hint != null) {
            Text(hint, fontSize = 10.sp, color = theme.secondaryTextColor)
        }
        Spacer(Modifier.height(5.dp))
        content()
    }
}

/**
 * 分段控件（连体圆角组）：替代旧文本按钮行 —— 选中项强调色填充、
 * 未选中透明，整组一个圆角边框，视觉上是一个控件而非一排按钮。
 */
@Composable
private fun XpSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val theme = LocalWinTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.windowBackgroundColor.copy(alpha = if (theme.isDark) 0.4f else 0.55f))
            .border(0.5.dp, theme.windowBorderColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(3.dp)
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            pressed && sel -> theme.accentColor.copy(alpha = 0.85f)
                            sel -> theme.accentColor
                            pressed -> theme.accentColor.copy(alpha = 0.15f)
                            else -> Color.Transparent
                        }
                    )
                    .clickable(interactionSource = interaction, indication = null) { onSelect(value) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (sel) Color.White
                    else if (theme.isDark) Color.White else Color.Black,
                    maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 面板主体
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun X11SettingsDialog() {
    val show by X11SettingsBridge.show.collectAsState()
    if (!show) return

    val theme = LocalWinTheme.current
    val prefs = remember { LoriePreferences.prefs } ?: run {
        X11SettingsBridge.dismiss()
        return
    }
    val resState by X11ResolutionLink.state.collectAsState()

    var modeNative by remember(resState.mode) { mutableStateOf(resState.mode != "exact" && resState.mode != "scaled") }
    var modeScaled by remember(resState.mode) { mutableStateOf(resState.mode == "scaled") }
    var resText by remember(resState.mode) {
        mutableStateOf(
            if (resState.mode == "exact" && resState.exact.isNotEmpty()) resState.exact
            else prefs.displayResolutionExact.get()
        )
    }
    var scalePercent by remember(resState.mode) {
        mutableFloatStateOf(prefs.displayScale.get().coerceIn(50, 200).toFloat())
    }
    var stretch by remember(resState.mode) { mutableStateOf(prefs.displayStretch.get()) }
    var clipboard by remember { mutableStateOf(prefs.clipboardEnable.get()) }
    var scancodes by remember { mutableStateOf(prefs.preferScancodes.get()) }
    var charInput by remember { mutableStateOf(prefs.enforceCharBasedInput.get()) }
    // 触摸方式与触控板参数（termux-x11 touchMode/scaleTouchpad/tapToMove）
    var touchMode by remember { mutableStateOf(prefs.touchMode.get().toIntOrNull() ?: 2) }
    var padScale by remember { mutableStateOf(prefs.scaleTouchpad.get()) }
    var tapMove by remember { mutableStateOf(prefs.tapToMove.get()) }
    var resError by remember { mutableStateOf(false) }

    val labelColor = theme.windowTitleBarTextColor
    val accent = theme.accentColor
    val hintColor = theme.secondaryTextColor

    WinDialog(
        title = "X11 设置",
        onDismiss = { X11SettingsBridge.dismiss() },
        width = 420.dp,
        iconAsset = "icons/x11.png"
    ) {
        // 内容可滚动 —— 高度不足时上下滑动，不被截断（弹窗层钳制
        // 最大高度为工作区 82%）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ---- 状态卡片（当前 X 屏幕） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (resState.fromGame) accent.copy(alpha = 0.14f)
                        else theme.cardBackgroundColor
                    )
                    .border(
                        0.5.dp,
                        if (resState.fromGame) accent.copy(alpha = 0.5f)
                        else theme.windowBorderColor.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🖥", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "当前 X 屏幕",
                        fontSize = 10.sp, color = hintColor
                    )
                    Text(
                        when {
                            resState.mode == "exact" ->
                                resState.exact + "（" + if (resState.fromGame) "游戏握手）" else "手动）"
                            resState.mode == "scaled" -> "缩放 ${prefs.displayScale.get()}%"
                            else -> "跟随窗口（随 X11 窗口尺寸变化）"
                        },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (resState.fromGame) accent else labelColor
                    )
                }
            }

            // ================= 显示 =================
            XpSectionCard("显示") {
                XpField("分辨率模式") {
                    XpSegmented(
                        options = listOf(
                            "native" to "跟随窗口",
                            "scaled" to "缩放",
                            "exact" to "固定分辨率"
                        ),
                        selected = when {
                            modeNative -> "native"
                            modeScaled -> "scaled"
                            else -> "exact"
                        }
                    ) { v ->
                        when (v) {
                            "native" -> {
                                modeNative = true; modeScaled = false
                                X11ResolutionLink.setNative()
                            }
                            "scaled" -> {
                                modeNative = false; modeScaled = true
                                X11ResolutionLink.setScale(scalePercent.toInt())
                            }
                            else -> {
                                modeNative = false; modeScaled = false
                            }
                        }
                    }
                }

                if (modeScaled) {
                    // ---- 缩放比例（termux-x11 displayScale：X 屏幕 = 窗口×100/scale） ----
                    XpField(
                        "缩放比例 ${scalePercent.toInt()}%",
                        ">100% 界面元素放大，<100% 桌面内容更多"
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = scalePercent,
                                onValueChange = { scalePercent = it },
                                valueRange = 50f..200f,
                                steps = 29,
                                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                                modifier = Modifier.weight(1f).height(26.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = { X11ResolutionLink.setScale(scalePercent.toInt()) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("应用", fontSize = 11.sp, color = accent) }
                        }
                    }
                }

                if (!modeNative && !modeScaled) {
                    // ---- 预设分辨率（FlowRow 自动换行） ----
                    XpField("分辨率", "点选预设或输入自定义 宽x高") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            RES_PRESETS.forEach { preset ->
                                val sel = resText == preset
                                val interaction = remember { MutableInteractionSource() }
                                val pressed by interaction.collectIsPressedAsState()
                                Text(
                                    preset,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (sel) accent else labelColor,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (sel || pressed) accent.copy(alpha = if (sel) 0.16f else 0.1f)
                                            else theme.windowBackgroundColor.copy(alpha = 0.5f)
                                        )
                                        .clickable(
                                            interactionSource = interaction,
                                            indication = null
                                        ) { resText = preset; resError = false }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = resText,
                                onValueChange = { resText = it; resError = false },
                                isError = resError,
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (theme.isDark) Color.White else Color.Black
                                ),
                                modifier = Modifier.width(120.dp).height(46.dp),
                                placeholder = { Text("1280x720", fontSize = 9.sp) }
                            )
                            TextButton(
                                onClick = {
                                    val m = Regex("(\\d{2,5})x(\\d{2,5})").find(resText.trim())
                                    val (w, h) = m?.destructured ?: run { resError = true; return@TextButton }
                                    if (w.toInt() < 160 || h.toInt() < 120) { resError = true; return@TextButton }
                                    X11ResolutionLink.setExact(w.toInt(), h.toInt())
                                    resError = false
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) { Text("应用", fontSize = 11.sp) }
                        }
                    }
                }

                // ---- 拉伸铺满 ----
                XpSwitchRow(
                    title = "拉伸铺满（无黑边）",
                    desc = "固定/缩放分辨率时画面拉伸至整个 X11 窗口（宽高比不同时允许轻微变形）",
                    checked = stretch,
                    enabled = !modeNative,
                    onChecked = {
                        stretch = it
                        prefs.displayStretch.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent, hintColor = hintColor
                )
            }

            // ================= 窗口 =================
            XpSectionCard("窗口") {
                // ---- 全屏（桌面真全屏：隐藏标题栏/任务栏/控制条，画面独占，返回键退出） ----
                val wm = remember { WindowManager.get() }
                val x11WindowId = remember { wm.windowsForApp("x11").firstOrNull()?.id }
                // 本地镜像：toggleTrueFullscreen 走 WindowManager 通知流，
                // 对话框不订阅该流，故用本地状态即时回显开关。
                var fsChecked by remember {
                    mutableStateOf(
                        x11WindowId?.let { id -> wm.windows.firstOrNull { it.id == id }?.isTrueFullscreen } == true
                    )
                }
                XpSwitchRow(
                    title = "全屏显示",
                    desc = "隐藏标题栏与控制条，画面独占整个屏幕；返回键退出全屏",
                    checked = fsChecked,
                    enabled = x11WindowId != null,
                    onChecked = {
                        fsChecked = it
                        x11WindowId?.let { id -> wm.toggleTrueFullscreen(id) }
                    },
                    labelColor = labelColor, accent = accent, hintColor = hintColor
                )

                // ---- 保持亮屏（termux-x11 keepScreenOn） ----
                XpSwitchRow(
                    title = "保持亮屏",
                    desc = "X11 桌面运行时屏幕不自动熄灭（关闭 X11 窗口自动恢复）",
                    checked = X11UiPrefs.keepScreenOn,
                    enabled = true,
                    onChecked = {
                        X11UiPrefs.keepScreenOn = it
                        prefs.keepScreenOn.put(it)
                    },
                    labelColor = labelColor, accent = accent, hintColor = hintColor
                )

                // ---- 屏幕方向（termux-x11 forceOrientation） ----
                // v3.2.15："auto" 改为跟随桌面方向（不再跟随系统传感器）
                XpField("屏幕方向") {
                    XpSegmented(
                        options = listOf(
                            "auto" to "跟随桌面",
                            "portrait" to "竖屏",
                            "landscape" to "横屏"
                        ),
                        selected = X11UiPrefs.orientation
                    ) { v ->
                        X11UiPrefs.orientation = v
                        prefs.forceOrientation.put(v)
                    }
                }
            }

            // ================= 键盘 =================
            XpSectionCard("键盘") {
                // ---- 附加键盘栏（termux-x11 showAdditionalKbd） ----
                XpSwitchRow(
                    title = "附加键盘栏",
                    desc = "画面底部显示 ESC / TAB / CTRL / 方向键等实体快捷键行（点按直注 X）",
                    checked = X11UiPrefs.showAdditionalKbd,
                    enabled = true,
                    onChecked = {
                        X11UiPrefs.showAdditionalKbd = it
                        prefs.showAdditionalKbd.put(it)
                    },
                    labelColor = labelColor, accent = accent, hintColor = hintColor
                )

                // ---- 首选扫描码（termux-x11 preferScancodes） ----
                XpSwitchRow(
                    title = "首选扫描码（外接键盘）",
                    desc = "物理键盘按键按扫描码上报，游戏/远程桌面兼容性更好",
                    checked = scancodes,
                    enabled = true,
                    onChecked = {
                        scancodes = it
                        prefs.preferScancodes.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent, hintColor = hintColor
                )

                // ---- 强制字符输入（termux-x11 enforceCharBasedInput，LorieView 消费） ----
                XpSwitchRow(
                    title = "强制字符输入",
                    desc = "输入法按字符而非按键上报，修复部分中文输入法丢键",
                    checked = charInput,
                    enabled = true,
                    onChecked = {
                        charInput = it
                        prefs.enforceCharBasedInput.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent, hintColor = hintColor
                )
            }

            // ================= 触摸（termux-x11 touchMode） =================
            XpSectionCard("触摸") {
                // ---- 触摸方式（1=触控板 2=模拟触摸屏 3=直接触摸） ----
                XpField("触摸方式", "wine 游戏推荐触控板或模拟触摸") {
                    XpSegmented(
                        options = listOf(
                            "2" to "模拟触摸",
                            "1" to "触控板",
                            "3" to "直接触摸"
                        ),
                        selected = touchMode.toString()
                    ) { v ->
                        touchMode = v.toInt()
                        prefs.touchMode.put(v)
                        // 即时下发到活跃 SmartTouchBridge —— 输入策略真实切换
                        SmartTouchBridge.applyTouchPrefs()
                    }
                }
                Text(
                    text = when (touchMode) {
                        1 -> "触控板：手指滑动=相对移动光标（游戏/FPS 视角真实旋转，wine 游戏推荐）；轻点=左键，双指轻点=右键，双指滑动=滚轮"
                        3 -> "直接触摸：触摸按 X 触摸事件直注（多点触摸），触摸类游戏/应用真实可用"
                        else -> "模拟触摸：点按=手指位置；按住拖拽=相对增量（wine 游戏转视角不乱飘）；双击吸附/双指右键/滚轮"
                    },
                    fontSize = 9.sp,
                    color = hintColor,
                    lineHeight = 12.sp
                )

                // ---- 触控板专属参数（仅触控板方式显示，切换后即时生效） ----
                if (touchMode == 1) {
                    XpSwitchRow(
                        title = "触控板缩放",
                        desc = "光标相对位移按画面拉伸比例放大（关闭 = 手指 1:1 位移，对齐上游 scaleTouchpad）",
                        checked = padScale,
                        enabled = true,
                        onChecked = {
                            padScale = it
                            prefs.scaleTouchpad.put(it)
                            SmartTouchBridge.applyTouchPrefs()
                        },
                        labelColor = labelColor, accent = accent, hintColor = hintColor
                    )
                    XpSwitchRow(
                        title = "轻点拖拽",
                        desc = "轻点=按下左键，移动=拖拽，再轻点=释放（对齐上游 tapToMove）",
                        checked = tapMove,
                        enabled = true,
                        onChecked = {
                            tapMove = it
                            prefs.tapToMove.put(it)
                            SmartTouchBridge.applyTouchPrefs()
                        },
                        labelColor = labelColor, accent = accent, hintColor = hintColor
                    )
                }

                // ---- 输入诊断（只读；点击失灵时截图反馈用） ----
                val diagSnapshot = remember(touchMode) { SmartTouchBridge.activeBridge?.diagText() }
                if (diagSnapshot != null) {
                    XpField("输入诊断") {
                        Text(
                            text = diagSnapshot,
                            fontSize = 9.sp,
                            color = hintColor,
                            lineHeight = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ================= 剪贴板 =================
            XpSectionCard("剪贴板") {
                XpSwitchRow(
                    title = "剪贴板双向同步",
                    desc = "Android 与 X11 应用共享剪贴板（复制粘贴互通）",
                    checked = clipboard,
                    enabled = true,
                    onChecked = {
                        clipboard = it
                        prefs.clipboardEnable.put(it)
                        X11ResolutionLink.pokeActiveView()
                    },
                    labelColor = labelColor, accent = accent, hintColor = hintColor
                )
            }

            // ================= 操作 =================
            XpSectionCard("操作") {
                // ---- 重放游戏分辨率握手 ----
                TextButton(
                    onClick = { X11ResolutionLink.applyFromFile() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("重新应用游戏分辨率（glibc-runner -d）", fontSize = 11.sp, color = accent)
                }
                // ---- 游戏全屏（Alt+Enter）----
                TextButton(
                    onClick = { com.termux.x11.X11InputHub.sendAltEnter() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("游戏全屏（Alt+Enter，窗口游戏切全屏铺满）", fontSize = 11.sp, color = accent)
                }
                Text(
                    text = "提示：-d1280x720 game.exe 会把 X 屏幕设为 1280x720；窗口化游戏点「游戏全屏」（Alt+Enter）切全屏后，X 屏幕自动跟随游戏分辨率，画面铺满无黑边；" +
                          "无 -d 时 X 屏幕跟随窗口。会话常驻：通知栏 \"X11 运行中\" 防止后台被杀。",
                    fontSize = 9.sp,
                    color = hintColor,
                    lineHeight = 13.sp
                )
            }

            // ---- 底部完成按钮（与标题栏 X 关闭同效） ----
            HorizontalDivider(color = theme.windowBorderColor.copy(alpha = 0.5f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { X11SettingsBridge.dismiss() }) {
                    Text("完成", fontSize = 12.sp)
                }
            }
        }
    }
}

/** 开关行（v3.2.13：说明文字不再与开关挤行 —— 左侧标题+说明，右侧开关）。 */
@Composable
private fun XpSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    labelColor: Color,
    accent: Color,
    hintColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) labelColor else labelColor.copy(alpha = 0.5f))
            Text(desc, fontSize = 9.sp, color = hintColor, lineHeight = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = accent),
            modifier = Modifier.height(22.dp)
        )
    }
}
