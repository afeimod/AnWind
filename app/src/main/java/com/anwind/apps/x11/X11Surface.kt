package com.anwind.apps.x11

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Display
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.WindowContentScope
import com.anwind.core.window.WindowManager
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.RenderData

/**
 * v2.22.2 fix9.6：X11 桌面的桌面窗口渲染面（开始菜单/桌面图标"X11 桌面"
 * 窗口内容，也可由终端 `anwind-x11` 广播自动弹出，见 X11WindowController）。
 *
 * 结构：
 * - 画面区：LorieView（SurfaceView）经 AndroidView 嵌入 Compose 窗口，
 *   由 X11WindowController 建立与终端侧 X server 的渲染连接；
 * - 直触桥：把 Android 触摸事件按 视图尺寸→X 屏幕尺寸 缩放后经
 *   InputEventSender 直注 X（等价上游 NullInputStrategy 直触模式）；
 * - 控制条：分辨率模式（跟随窗口/固定分辨率）、分辨率输入、应用、
 *   软键盘开关、全屏切换（复用桌面窗口真全屏机制）；
 * - 等待页：未连接时显示连接状态与操作指引（含"兼容全屏模式"按钮，
 *   拉起独立全屏 Activity 作为兜底）。
 */
@Composable
fun X11Surface(scope: WindowContentScope) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val wm = remember { WindowManager.get() }
    val connState by X11WindowController.state.collectAsState()
    var lorieViewRef by remember { mutableStateOf<LorieView?>(null) }
    var wmRevision by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { wm.observe { wmRevision++ } }
    val isTrueFs = remember(wmRevision) { scope.windowState.isTrueFullscreen }

    val prefs = remember { LoriePreferences.prefs }

    // 直触桥与按键发送器（随 LorieView 实例创建，见 factory）
    var touchBridge by remember { mutableStateOf<DirectTouchBridge?>(null) }

    // 窗口关闭/最小化（内容离开组合）时断开渲染连接并抑制本会话自动弹窗；
    // 重新打开/还原窗口时 factory 重建 LorieView 并自动重连。
    DisposableEffect(scope.windowState.id) {
        onDispose {
            lorieViewRef = null
            X11WindowController.detachView(userClosed = true)
        }
    }

    // 真全屏时返回键退出全屏（对齐浏览器/播放器行为）
    BackHandler(enabled = isTrueFs) { wm.toggleTrueFullscreen(scope.windowState.id) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 画面区 =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    LorieView(ctx).also { lv ->
                        lorieViewRef = lv
                        touchBridge = DirectTouchBridge(lv)
                        lv.setCallback { surfaceW, surfaceH, screenW, screenH ->
                            touchBridge?.updateTransform(surfaceW, surfaceH, screenW, screenH)
                            val display = lv.display
                            val framerate = (display?.refreshRate ?: 60f).toInt()
                            val name = if (display == null || display.displayId == Display.DEFAULT_DISPLAY)
                                "Builtin Display" else "External Display"
                            LorieView.sendWindowChange(screenW, screenH, framerate, name)
                        }
                        lv.setOnTouchListener { _, e -> touchBridge?.onTouch(e) ?: true }
                        // 按键直注 X（返回键不拦截，交给桌面处理全屏/关窗）
                        lv.setOnKeyListener { _, keyCode, event ->
                            if (keyCode == KeyEvent.KEYCODE_BACK) false
                            else touchBridge?.sendKey(event) ?: false
                        }
                        X11WindowController.connectLorieView(lv)
                    }
                }
            )

            // ===== 等待页（未连接时覆盖） =====
            if (connState != X11WindowController.State.Connected) {
                WaitingPanel(connState)
            }
        }

        // ===== 控制条（连接后显示） =====
        if (connState == X11WindowController.State.Connected && prefs != null) {
            ControlBar(
                scope = scope,
                prefs = prefs,
                lorieView = lorieViewRef,
                isTrueFs = isTrueFs
            )
        }
    }
}

@Composable
private fun WaitingPanel(state: X11WindowController.State) {
    val theme = LocalWinTheme.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (state == X11WindowController.State.Waiting) "正在连接 X 服务…"
            else "未发现 X 服务",
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "在终端执行以下命令启动桌面（本窗口将自动亮起）：",
            color = Color(0xFFB8C4CE),
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(6.dp))
        val steps = listOf(
            "anwind-x11 :1                  # 启动 X 服务并自动打开本窗口",
            "env DISPLAY=:1 xfce4-session   # 未自动起会话时手动执行",
            "anwind-x11 doctor              # 连不上时一键体检"
        )
        steps.forEach { line ->
            Text(
                text = line,
                color = Color(0xFF9FE29F),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14231A), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { X11Desktop.open(context) }) {
            Text("打开兼容全屏模式", fontSize = 12.sp)
        }
        Text(
            text = "兼容模式 = 独立全屏 Activity（排查窗口模式问题时使用）",
            color = Color(0xFF8A97A3),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ControlBar(
    scope: WindowContentScope,
    prefs: com.termux.x11.Prefs,
    lorieView: LorieView?,
    isTrueFs: Boolean
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val context = LocalContext.current

    var modeNative by remember { mutableStateOf(prefs.displayResolutionMode.get() != "exact") }
    var resText by remember { mutableStateOf(prefs.displayResolutionExact.get()) }
    var resError by remember { mutableStateOf(false) }
    val barBg = if (theme.isDark) Color(0xFF1B222B) else Color(0xFFF2F4F7)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .border(0.dp, Color.Transparent)
    ) {
        HorizontalDivider(color = if (theme.isDark) Color(0xFF3A4450) else Color(0xFFDDDDDD))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 分辨率模式切换
            TextButton(
                onClick = {
                    modeNative = true
                    prefs.displayResolutionMode.put("native")
                    lorieView?.let { it.regenerate(); it.requestLayout() }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (modeNative) theme.accentColor else theme.windowTitleBarTextColor
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("跟随窗口", fontSize = 11.sp) }

            TextButton(
                onClick = { modeNative = false },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (!modeNative) theme.accentColor else theme.windowTitleBarTextColor
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("固定分辨率", fontSize = 11.sp) }

            if (!modeNative) {
                OutlinedTextField(
                    value = resText,
                    onValueChange = { resText = it; resError = false },
                    enabled = !modeNative,
                    isError = resError,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (theme.isDark) Color.White else Color.Black
                    ),
                    modifier = Modifier
                        .width(120.dp)
                        .height(48.dp),
                    placeholder = { Text("1280x720", fontSize = 10.sp) }
                )
                TextButton(
                    onClick = {
                        val m = Regex("(\\d{2,5})x(\\d{2,5})").find(resText.trim())
                        val (w, h) = m?.destructured ?: run { resError = true; return@TextButton }
                        if (w.toInt() < 160 || h.toInt() < 120) { resError = true; return@TextButton }
                        prefs.displayResolutionMode.put("exact")
                        prefs.displayResolutionExact.put("$w x $h".replace(" ", ""))
                        resError = false
                        lorieView?.let { it.regenerate(); it.requestLayout() }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("应用", fontSize = 11.sp) }
            }

            Spacer(Modifier.weight(1f))

            // 软键盘开关
            TextButton(
                onClick = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    @Suppress("DEPRECATION")
                    imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("键盘", fontSize = 11.sp) }

            // 全屏切换（真全屏：隐藏标题栏与任务栏，返回键退出）
            TextButton(
                onClick = { wm.toggleTrueFullscreen(scope.windowState.id) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(if (isTrueFs) "退出全屏" else "全屏", fontSize = 11.sp) }
        }
    }
}

/**
 * 直触桥：Android 触摸事件 → X 触摸事件。
 * 坐标变换与上游 TouchInputHandler.resetTransformation 一致：
 * scale = X 屏幕尺寸 / 视图尺寸（renderData.image* = 视图尺寸）。
 */
private class DirectTouchBridge(private val view: LorieView) {
    private val renderData = RenderData()
    private val sender = InputEventSender(view)

    fun updateTransform(surfaceW: Int, surfaceH: Int, screenW: Int, screenH: Int) {
        renderData.imageWidth = surfaceW
        renderData.imageHeight = surfaceH
        renderData.screenWidth = screenW
        renderData.screenHeight = screenH
        if (surfaceW > 0 && surfaceH > 0) {
            renderData.scale.set(screenW.toFloat() / surfaceW, screenH.toFloat() / surfaceH)
        }
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (renderData.imageWidth <= 0 || renderData.imageHeight <= 0) return true
        sender.sendTouchEvent(event, renderData)
        return true
    }

    fun sendKey(event: KeyEvent): Boolean = sender.sendKeyEvent(event)
}
