package com.anwind.apps.x11

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.HandlerThread
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Display
import android.view.WindowManager as AndroidWindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.anwind.core.input.gamepad.GamepadController
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.WindowContentScope
import com.anwind.core.window.WindowManager
import com.termux.x11.LoriePreferences
import com.termux.x11.LorieView
import com.termux.x11.Prefs
import com.termux.x11.X11InputHub
import com.termux.x11.input.InputStub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG_X11 = "X11Surface"

/**
 * v2.22.2 fix9.6：X11 桌面的桌面窗口渲染面（开始菜单/桌面图标"X11 桌面"
 * 窗口内容，也可由终端 `anwind-x11` 广播自动弹出，见 X11WindowController）。
 *
 * v2.22.3 fix10/fix11b 更新：
 * - 分辨率握手：glibc-runner -d 分辨率经 X11ResolutionLink（文件协议）
 *   到达本窗口。无 -d = native：X 屏幕随桌面窗口尺寸变化拉伸全屏；
 *   有 -d = exact + stretch：X 屏幕保持指定分辨率（游戏真实全屏渲染），
 *   显示层同比例拉伸铺满窗口，无黑边；
 * - 虚拟手柄：直接使用桌面的悬浮虚拟手柄（GamepadOverlay/
 *   GamepadController），按键/鼠标经 X11InputHub 桥直注 X。
 *
 * v2.25 输入链路整体升级（对齐 LinBox X11 v2.30~v2.33.1）：
 * - 触摸方式（termux-x11 touchMode，X11 设置面板切换即时生效）：
 *   触控板（虚拟光标相对移动）/ 模拟触摸屏（双击吸附/按住拖拽/双指右键/
 *   滚轮）/ 直接触摸（XI2 多点触摸直注）—— SmartTouchBridge 输入策略切换；
 * - 主线程零 socket I/O：全部 X 输入写统一投递到专用输入线程
 *   （FIFO 保序），相对增量 16ms 合并冲刷 —— 根治 wine 满载时
 *   "滑屏掉帧/手势卡顿"；
 * - tap 单条 absolute 按钮消息（对齐上游 simulated touchscreen 同款
 *   sendMouseEvent(x, y, LEFT, true, false)）—— 修复 wine 点击失灵根；
 * - 卡键自愈三件套 + 输入诊断（X11 设置面板只读展示）；
 * - 手柄指针剥离：手柄元素的指针不再漏进触摸手势（按住手柄同时滑屏
 *   不再误判双指滚轮）；
 * - 附加键盘栏（showAdditionalKbd）：画面底部 ESC/方向键玻璃键行，
 *   点按直注 X（随 X11 窗口显示，X11Overlay）；
 * - keepScreenOn / 屏幕方向：窗口打开期间生效、关闭自动还原全局设置。
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

    // v2.25：LoriePreferences（不可观察）→ X11UiPrefs（可观察镜像），
    // 供附加键盘栏显隐 / keepScreenOn / 屏幕方向驱动副作用
    LaunchedEffect(prefs) { prefs?.let { X11UiPrefs.initIfNeed(it) } }

    // 智能鼠标桥与手柄层（随 LorieView 实例创建，见 factory）
    var touchBridge by remember { mutableStateOf<SmartTouchBridge?>(null) }
    val resState by X11ResolutionLink.state.collectAsState()

    // 连接建立即应用分辨率协议文件（兜底时序：glibc-runner 写文件可能
    // 早于/晚于窗口打开；FileObserver 只覆盖窗口已打开的情况）。
    LaunchedEffect(connState) {
        if (connState == X11WindowController.State.Connected) {
            X11ResolutionLink.applyFromFile()
        }
    }

    // v2.22.5 fix15/fix17：游戏窗口自适应铺满 —— X11FitClient 直连 X server
    // socket，把窗口化游戏平移铺满 X 屏幕（客户区 = 屏幕），根治"四周黑边
    // 烧在画面内部"。fix17 起固定分辨率与跟随窗口会话均启用（跟随窗口会话
    // fit 时 X 屏幕会被定为游戏客户区，控制条如实显示）；桌面环境（xfdesktop
    // 等大面积窗口）FitClient 内部自动跳过；窗口关闭/断开时自动停止。
    LaunchedEffect(connState) {
        val active = connState == X11WindowController.State.Connected
        if (active) X11FitClient.start() else X11FitClient.stop()
    }

    // 窗口关闭/最小化（内容离开组合）时断开渲染连接并抑制本会话自动弹窗；
    // 重新打开/还原窗口时 factory 重建 LorieView 并自动重连。
    // v2.22.5 fix15：窗口真正关闭（标题栏 X 按钮，最小化不触发 onClose）
    // 时终止 wine 会话 —— 用户反馈"关闭 x11 窗口没有关闭 wine"。
    DisposableEffect(scope.windowState.id) {
        scope.windowState.onClose = { X11Session.killWine() }
        onDispose {
            X11FitClient.stop()
            lorieViewRef = null
            X11ResolutionLink.attachView(null)
            // 触摸桥注册一并注销（防设置面板下发到已分离视图）
            SmartTouchBridge.activeBridge = null
            // 桥分离时清理悬挂冲刷与受跟踪指针（防迟到事件误发）
            touchBridge?.onDetached()
            // 桌面手柄 → X11 转发目标一并注销（内部会对仍按着的键补发 UP）
            X11InputHub.get(context).setActiveLorieView(null)
            X11WindowController.detachView(userClosed = true)
        }
    }

    // 真全屏时返回键退出全屏（对齐浏览器/播放器行为）
    BackHandler(enabled = isTrueFs) { wm.toggleTrueFullscreen(scope.windowState.id) }

    // ------------------------------------------------------------------
    // v2.25 窗口效果（termux-x11 keepScreenOn / forceOrientation 均应用在
    // 宿主 Activity 窗口上；X11 窗口关闭时还原全局设置，不影响桌面其它场景）。
    // ------------------------------------------------------------------
    val activity = context as? Activity

    // 保持亮屏（X11 窗口存在期间生效）
    DisposableEffect(X11UiPrefs.keepScreenOn) {
        val win = activity?.window
        if (X11UiPrefs.keepScreenOn) {
            win?.addFlags(AndroidWindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { win?.clearFlags(AndroidWindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 屏幕方向（面板切换即时生效；关闭 X11 窗口还原应用全局设置）
    LaunchedEffect(X11UiPrefs.orientation) {
        activity?.requestedOrientation = when (X11UiPrefs.orientation) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }
    DisposableEffect(scope.windowState.id) {
        onDispose {
            // 离开 X11：按应用全局显示设置还原方向（DataStore 异步读，
            // 主线程落窗；MainActivity 的 LaunchedEffect 只随全局设置
            // 变化重启，不会覆盖此处还原）。
            val app = context.applicationContext as? com.anwind.AnWindApp
            app?.applicationScope?.launch {
                val global = app.settingsStore.displayOrientation.first()
                withContext(Dispatchers.Main) {
                    activity?.requestedOrientation = when (global) {
                        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    }
                }
            }
        }
    }

    // 面板关闭后把"首选扫描码"等偏好同步进触摸桥（SharedPreferences 不可
    // 观察，以面板关闭为应用时机；面板内每次改动已即时下发，此处兜底）
    val settingsShowing by X11SettingsBridge.show.collectAsState()
    LaunchedEffect(settingsShowing) {
        if (!settingsShowing) {
            prefs?.let { touchBridge?.setPreferScancodes(it.preferScancodes.get()) }
            SmartTouchBridge.applyTouchPrefs()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== 画面区（附加键盘栏 / 等待页悬浮其上） =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    // 根容器：LorieView 铺满窗口（native 跟随窗口 / exact+stretch
                    // 拉伸铺满，均无黑边）。
                    // v2.22.5 fix12：Gravity.CENTER → FILL —— 画面靠左上锚定
                    // （用户反馈"黑边没有靠左"）；FILL = TOP|START|BOTTOM|END。
                    val root = FrameLayout(ctx)

                    val lv = LorieView(ctx)
                    root.addView(lv, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.FILL
                    ))

                    val bridge = SmartTouchBridge(ctx, lv)
                    // v2.25：同步触摸偏好（方式/触控板参数/首选扫描码）并注册
                    // 活跃桥 —— 设置面板改触摸偏好后经 applyTouchPrefs 即时下发
                    LoriePreferences.prefs?.let { p ->
                        bridge.setPreferScancodes(p.preferScancodes.get())
                        bridge.reloadTouchPrefs(p)
                    }
                    SmartTouchBridge.activeBridge = bridge
                    touchBridge = bridge

                    lv.setCallback { surfaceW, surfaceH, screenW, screenH ->
                        bridge.updateTransform(surfaceW, surfaceH, screenW, screenH)
                        // fix15：自适应客户端同步当前 X 屏幕尺寸（已铺满则跳过判断）
                        X11FitClient.screenW = screenW
                        X11FitClient.screenH = screenH
                        // 同步 Winlator 侧 xserver 状态（screenInfo 消费方依赖）。
                        try {
                            lv.screenInfo.handleHostSizeChanged(surfaceW, surfaceH)
                            lv.screenInfo.handleClientSizeChanged(screenW, screenH)
                        } catch (_: Exception) {}
                        val display = lv.display
                        val framerate = (display?.refreshRate ?: 60f).toInt()
                        val name = if (display == null || display.displayId == Display.DEFAULT_DISPLAY)
                            "Builtin Display" else "External Display"
                        LorieView.sendWindowChange(screenW, screenH, framerate, name)
                    }
                    lv.setOnTouchListener { _, e -> bridge.onTouch(e) }
                    // 按键直注 X（返回键不拦截，交给桌面处理全屏/关窗）
                    lv.setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_BACK) false
                        else bridge.sendKey(event) ?: false
                    }

                    lorieViewRef = lv
                    X11ResolutionLink.attachView(lv)
                    // 桌面虚拟手柄 → X11 直注通道（GamepadController 经
                    // X11InputHub 静态桥把按键/鼠标事件发到本视图）。
                    X11InputHub.get(ctx).setActiveLorieView(lv)

                    X11WindowController.connectLorieView(lv)
                    root
                }
            )

            // ===== 等待页（未连接时覆盖） =====
            if (connState != X11WindowController.State.Connected) {
                WaitingPanel(connState)
            }

            // ===== v2.25 附加键盘栏（showAdditionalKbd；连接后显示，真全屏时
            //      隐藏，imePadding 使其贴在输入法上沿） =====
            if (connState == X11WindowController.State.Connected &&
                X11UiPrefs.showAdditionalKbd && !isTrueFs
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .imePadding(),
                    contentAlignment = Alignment.BottomStart
                ) {
                    X11ExtraKeysBar()
                }
            }
        }

        // ===== 控制条（连接后显示） =====
        // v2.22.5 fix13：真全屏时隐藏控制条（用户需求：点"全屏"后底部菜单
        // 消失，画面独占整个窗口；按返回键退出全屏后控制条重新出现 ——
        // BackHandler 已在本 Composable 顶部处理）。
        if (connState == X11WindowController.State.Connected && prefs != null && !isTrueFs) {
            ControlBar(
                scope = scope,
                prefs = prefs,
                lorieView = lorieViewRef,
                isTrueFs = isTrueFs,
                resState = resState
            )
        }
    }

    // v2.22.4 fix11c：X11 设置面板（长按标题栏 / 常驻通知 "X11 设置" 动作）
    X11SettingsDialog()
}

@Composable
private fun WaitingPanel(state: X11WindowController.State) {
    val theme = LocalWinTheme.current

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
            "glibc-runner -d1280x720 game.exe  # 游戏按指定分辨率全屏渲染",
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
        // v2.26：移除"打开兼容全屏模式"入口 —— 兼容 Activity 与桌面窗口
        // 争夺连接 fd（谁先取用谁渲染），点了它反而抢走画面导致本窗口
        // 永远黑屏；X 服务改由容器启动自动拉起（WineSessionLauncher），
        // 本页仅作为极短过渡等待页存在。
        Text(
            text = "X 服务启动中，画面就绪后自动显示（显示号 :1）",
            color = Color(0xFF8A97A3),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ControlBar(
    scope: WindowContentScope,
    prefs: Prefs,
    lorieView: LorieView?,
    isTrueFs: Boolean,
    resState: X11ResolutionLink.ResolutionState
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val context = LocalContext.current

    var modeNative by remember(resState.mode) { mutableStateOf(resState.mode != "exact" && resState.mode != "scaled") }
    var resText by remember(resState.mode) {
        mutableStateOf(
            if (resState.mode == "exact" && resState.exact.isNotEmpty()) resState.exact
            else prefs.displayResolutionExact.get()
        )
    }
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
                    X11ResolutionLink.setNative()
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
                        .width(110.dp)
                        .height(48.dp),
                    placeholder = { Text("1280x720", fontSize = 10.sp) }
                )
                TextButton(
                    onClick = {
                        val m = Regex("(\\d{2,5})x(\\d{2,5})").find(resText.trim())
                        val (w, h) = m?.destructured ?: run { resError = true; return@TextButton }
                        if (w.toInt() < 160 || h.toInt() < 120) { resError = true; return@TextButton }
                        modeNative = false
                        X11ResolutionLink.setExact(w.toInt(), h.toInt())
                        resError = false
                        lorieView?.let { it.regenerate(); it.requestLayout() }
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) { Text("应用", fontSize = 11.sp) }
            }

            // 分辨率来源指示（glibc-runner 握手状态一目了然）
            Text(
                text = when {
                    resState.mode == "exact" -> "X:${resState.exact}"
                    resState.mode == "scaled" -> "X:缩放"
                    else -> "X:跟随窗口"
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (resState.fromGame) theme.accentColor else theme.windowTitleBarTextColor
            )

            Spacer(Modifier.weight(1f))

            // 软键盘开关
            TextButton(
                onClick = { toggleX11Ime(context) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("键盘", fontSize = 11.sp) }

            // v2.22.5 fix14：游戏全屏（Alt+Enter）—— 窗口化游戏（居中小窗+
            // 四周黑边）一键切换 wine/DXVK 全屏：X 屏幕经 RandR 自动切成游戏
            // 分辨率，画面铺满无黑边。
            TextButton(
                onClick = { X11InputHub.sendAltEnter() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text("游戏全屏", fontSize = 11.sp) }

            // 全屏切换（真全屏：隐藏标题栏与任务栏，返回键退出）
            TextButton(
                onClick = { wm.toggleTrueFullscreen(scope.windowState.id) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) { Text(if (isTrueFs) "退出全屏" else "全屏", fontSize = 11.sp) }
        }
    }
}

// ====================================================================
// 智能触摸桥（v2.25 全量升级，对齐 LinBox X11 v2.30~v2.33.1）
// ====================================================================

/**
 * 智能鼠标桥：Android 触摸 → X 真实鼠标事件。
 *
 * 为什么不直注原生 X 触摸（旧 DirectTouchBridge / sendTouchEvent）：
 * - wine（尤其 explorer/游戏菜单）大量场景只响应鼠标按下/抬起；
 *   XI2 触摸直注在 wine 下表现为"点不开文件夹/文件"；
 * - 双击要求两次点击落点足够近 —— 手指两次落点天然有偏差；
 * - 拖拽（移动 wine 窗口、拉滚动条）需要"按下期间持续移动"。
 *
 * 三种触摸方式（termux-x11 touchMode，设置面板即时切换）：
 * - 模拟触摸屏（默认）：按下即左键、拖拽相对增量（wine 游戏转视角
 *   不乱飘）、双击吸附、双指右键/滚轮；
 * - 触控板：手指滑动=光标相对移动（FPS 视角真实旋转）、轻点=左键、
 *   tapToMove 轻点拖拽、双指轻点右键/滚轮；
 * - 直接触摸：原始多点触摸按 XI2 Touch 直注 X（触摸类游戏可用）。
 *
 * 性能与可靠性（LinBox 实测修复集）：
 * - 全部 X 输入写统一投递到专用输入线程（FIFO 保序）——主线程触摸
 *   路径零 socket I/O，UI 帧率与 X 负载彻底解耦（滑屏掉帧根修）；
 * - 相对增量合并器：MOVE 只累加，16ms 节流冲刷，总量精确守恒；
 * - tap 单条 absolute 按钮消息（上游同款），修复 wine 点击失灵根；
 * - 卡键自愈三件套（孤儿按钮释放 / 悬挂按压归位 / 遗留触摸冲刷）；
 * - 手柄指针剥离：手柄元素内的指针对手势状态机完全不可见。
 */
internal class SmartTouchBridge(private val context: Context, private val view: LorieView) {
    private val renderData = com.termux.x11.input.RenderData()
    private val keySender = com.termux.x11.input.InputEventSender(view)

    companion object {
        // 触摸方式取值（对齐 termux-x11 touchscreenInputModesValues）
        const val TOUCH_TRACKPAD = 1
        const val TOUCH_SIMULATED = 2
        const val TOUCH_DIRECT = 3

        /**
         * 相对增量最小冲刷间隔（ms）：≈62.5Hz。PC 鼠标 125Hz 档的一半、
         * 不低于主流手机游戏在 wine 下的实际帧率，显示端 ≤60Hz 时逐帧
         * 对齐 —— 手感无损，事件量减半以上。
         */
        private const val MOTION_FLUSH_INTERVAL_MS = 16L

        /** XI_TouchEnd（对齐 InputEventSender 私有常量，公开 SDK 无此符号） */
        private const val XI_TOUCH_END = 20

        /**
         * 相对增量冲刷专用线程 —— sendMouseEvent 是 @FastNative 的 socket
         * 直写，wine 游戏打满 CPU 时 X server 输入线程消费变慢，主线程
         * 直写可能被 socket 缓冲顶住（UI 线程卡顿 = 掉帧）。冲刷移入独立
         * 线程后，主线程触摸路径零 socket I/O。进程级单例，桥实例重建
         * 共享复用。
         */
        private val flushThread by lazy {
            HandlerThread("anwind-x11-motion").apply { start() }
        }

        /** 活跃桥：设置面板修改触摸偏好后经 [applyTouchPrefs] 即时下发。 */
        @Volatile var activeBridge: SmartTouchBridge? = null

        /** 把 LoriePreferences 的触摸偏好（方式/触控板缩放/轻点拖拽）下发到活跃桥。 */
        fun applyTouchPrefs() {
            val p = LoriePreferences.prefs ?: return
            activeBridge?.reloadTouchPrefs(p)
        }

        /**
         * 桥外输入注入（虚拟手柄鼠标键/滚轮等）统一投递到桥的专用输入
         * 线程 —— 与触摸/按键/滚轮 FIFO 保序，且 X 输入 socket 回到
         * "单写者"模型。无活跃桥时同步执行原路径。
         */
        fun postX11Input(block: () -> Unit) {
            val b = activeBridge
            if (b != null) b.postSend(block) else block()
        }
    }

    // 触摸偏好（termux-x11：touchMode / scaleTouchpad / tapToMove）
    private var touchMode = TOUCH_SIMULATED
    private var scaleTouchpad = true
    private var tapToMove = false

    /** 轻点拖拽（tapToMove）：左键已按下、等待"再轻点"释放。 */
    private var padPressed = false

    /**
     * 从偏好同步触摸方式与触控板参数（真实生效：本桥是 AnWind X11 的
     * 触摸事件入口，等效上游 TouchInputHandler.reloadPreferences）。
     * 方式切换时释放悬挂的按压/手势状态，防止旧模式的左键"按住不放"。
     */
    fun reloadTouchPrefs(p: Prefs) {
        val newMode = p.touchMode.get().toIntOrNull() ?: TOUCH_SIMULATED
        if (newMode != touchMode) {
            if (pressedLeft) { sendButton(InputStub.BUTTON_LEFT, false); pressedLeft = false }
            if (padPressed) { sendButton(InputStub.BUTTON_LEFT, false); padPressed = false }
            mode = Mode.IDLE
            // 冲掉直接触摸可能遗留的 X 侧活跃触摸（0..9）——否则触摸
            // 仿真按钮 1 永久卡按，此后一切左键按下被 dix 丢弃（点击全
            // 失灵）；未激活的 TouchEnd 在 X 侧被丢弃，零副作用。
            closeAllTouches()
            synchronized(dtLock) { dtCount = 0; dtDirty = false }
            flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
            // 切换方式时丢弃未冲刷的相对增量，防止旧模式残余在新模式生效
            dropPendingMoves()
        }
        touchMode = newMode
        scaleTouchpad = p.scaleTouchpad.get()
        tapToMove = p.tapToMove.get()
    }

    // 视图→X 坐标变换参数
    private var surfaceW = 0
    private var surfaceH = 0
    private var screenW = 0
    private var screenH = 0
    private var scaleX = 1f
    private var scaleY = 1f

    // 手势状态
    private var mode = Mode.IDLE
    private var pressedLeft = false
    private var downX = 0f; private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var curX = 0f; private var curY = 0f
    // 本手势 tap 落点（X 坐标）—— 按下与释放共用同一条 absolute
    // 坐标（释放零位移）；双击吸附记录同一落点（吸附语义对齐按下瞬间）
    private var tapX = 0f; private var tapY = 0f

    // 双指
    private var twoStartAt = 0L
    private var twoMoved = false
    private var lastTwoY = 0f
    private var scrollAccum = 0f

    // 双击吸附（X 坐标）
    private var lastTapX = -1f; private var lastTapY = -1f
    private var lastTapAt = 0L

    private val slopPx = (ViewConfiguration.get(context).scaledTouchSlop * 1.5f)
    private val tapMaxMs = 260L
    private val dblTapMaxMs = 450L
    private val wheelStep = 40f

    // ---- 相对增量合并器（motion coalescer）/ 后台线程冲刷 ----
    // MOVE 事件只累加 pending（零 JNI），由专用后台线程 Handler 以
    // MOTION_FLUSH_INTERVAL_MS 最小间隔冲刷（一条合并后的 relative
    // sendCursorMove）。pending 由 pendLock 保护：冲刷线程与主线程
    // （手势结束的同步兜底冲刷）并发排水，总量精确守恒不重不漏。
    private val pendLock = Any()
    private var pendX = 0f
    private var pendY = 0f
    @Volatile private var flushPending = false
    private val flushHandler = Handler(flushThread.looper)
    private val flushRunnable = Runnable { flushPendingMoves() }

    /** 所有 X 输入 socket 写统一投递到专用输入线程（FIFO 保序）。 */
    private fun postSend(block: () -> Unit) {
        flushHandler.post(block)
    }

    /** 主线程手势收尾改为异步冲刷（FIFO 保证先于后续按钮释放执行） */
    private fun postFlushMoves() {
        flushHandler.post { flushPendingMoves() }
    }

    private fun accumulateMove(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        synchronized(pendLock) {
            pendX += dx
            pendY += dy
        }
        if (!flushPending) {
            flushPending = true
            flushHandler.postDelayed(flushRunnable, MOTION_FLUSH_INTERVAL_MS)
        }
    }

    /** 输入线程执行（冲刷定时器/手势收尾的异步冲刷任务）；排空增量总量精确守恒。 */
    private fun flushPendingMoves() {
        flushHandler.removeCallbacks(flushRunnable)
        flushPending = false
        val dx: Float
        val dy: Float
        synchronized(pendLock) {
            dx = pendX
            dy = pendY
            pendX = 0f; pendY = 0f
        }
        if (dx == 0f && dy == 0f) return
        if (!LorieView.connected()) return
        keySender.sendCursorMove(dx, dy, true)
    }

    /** 取消未冲刷的增量（手势系统取消/模式切换时，避免迟到冲刷发出意外移动）。 */
    private fun dropPendingMoves() {
        flushHandler.removeCallbacks(flushRunnable)
        flushPending = false
        synchronized(pendLock) { pendX = 0f; pendY = 0f }
    }

    // ---- 手柄指针剥离（pad pointer stripping）----
    // 桌面虚拟手柄元素（摇杆/十字键/按钮）不消费自己的指针事件时，
    // 未消费的手柄指针会漏进本桥：按住手柄（开火/摇杆）时另一根手指
    // 滑动屏幕，桥看到 2 指流 → 误判双指滚轮 → 视角不旋转。这里按
    // pointerId 跟踪"屏幕手势指针"（DOWN 落点不在手柄元素命中矩形内
    // 的指针），构造只含这些指针的干净 MotionEvent 交给手势逻辑。
    // 坐标系：命中矩形登记的是 positionInWindow 窗口坐标，onTouch 里把
    // 事件坐标加上 LorieView 的窗口偏移后比对。
    private val trackedPadIds = ArrayList<Int>(4)
    private val stripProps = Array(10) { MotionEvent.PointerProperties() }
    private val stripCoords = Array(10) { MotionEvent.PointerCoords() }
    private val stripIdx = IntArray(10)
    private val viewWinPos = IntArray(2) // LorieView 相对窗口原点偏移
    // 偏移缓存 —— 每个事件都调 getLocationInWindow（遍历视图树 + 拿
    // 布局锁），120Hz 触摸下有可测开销。布局位置不变期间直接复用缓存。
    @Volatile private var viewWinPosDirty = true
    private val viewWinPosWatcher = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        viewWinPosDirty = true
    }.also { view.addOnLayoutChangeListener(it) }

    /** 桥与视图分离（X11 窗口关闭/最小化）时清理手势痕迹与悬挂冲刷。 */
    fun onDetached() {
        dropPendingMoves()
        synchronized(dtLock) { dtCount = 0; dtDirty = false }
        flushHandler.removeCallbacks(dtFlushRunnable)
        dtFlushPending = false
        // 分离前补发孤儿按钮释放 + 冲掉可能遗留的 X 侧活跃触摸
        //（下一会话重建桥时 healOrphanButtons 会再次自愈，双保险）
        if (LorieView.connected()) {
            postSend {
                view.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, false, true)
                view.sendMouseEvent(0f, 0f, InputStub.BUTTON_MIDDLE, false, true)
                view.sendMouseEvent(0f, 0f, InputStub.BUTTON_RIGHT, false, true)
                for (id in 0..9) view.sendTouchEvent(XI_TOUCH_END, id, 0, 0)
            }
        }
        trackedPadIds.clear()
        view.removeOnLayoutChangeListener(viewWinPosWatcher)
    }

    /**
     * 剥离"落在手柄元素/工具条内"的指针。返回 null = 本事件与桥无关；
     * 返回原事件 = 无需剥离（零分配直通）；否则构造只含受跟踪指针的
     * 子集事件（action 相应重映射）。
     */
    private fun filterPadPointers(event: MotionEvent): MotionEvent? {
        if (!GamepadController.hasElementHits() && !GamepadController.hasToolbarHit()) {
            trackedPadIds.clear()
            return event
        }
        val action = event.actionMasked
        val aIdx = event.actionIndex
        val hadTracked = trackedPadIds.isNotEmpty()
        var forward = -1 // 转发的 action；-1 = 丢弃本事件
        var includeActionPointer = false // POINTER_UP 子集需包含抬起指针

        // 视图坐标→窗口坐标（缓存化，布局不变期间零查询）
        if (viewWinPosDirty) {
            view.getLocationInWindow(viewWinPos)
            viewWinPosDirty = false
        }
        val winX = viewWinPos[0].toFloat()
        val winY = viewWinPos[1].toFloat()

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                trackedPadIds.clear()
                if (!GamepadController.isOverPadUi(event.x + winX, event.y + winY))
                    trackedPadIds.add(event.getPointerId(0))
                if (trackedPadIds.isNotEmpty()) forward = MotionEvent.ACTION_DOWN
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                if (!GamepadController.isOverPadUi(event.getX(i) + winX, event.getY(i) + winY))
                    trackedPadIds.add(event.getPointerId(i))
                forward = when {
                    trackedPadIds.isEmpty() -> -1
                    hadTracked -> MotionEvent.ACTION_POINTER_DOWN
                    else -> MotionEvent.ACTION_DOWN // 桥视角：这是手势的第一根手指
                }
            }
            MotionEvent.ACTION_MOVE ->
                if (trackedPadIds.isNotEmpty()) forward = MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_POINTER_UP -> {
                // indexOf+removeAt：规避 ArrayList<Int>.remove 的索引/按值重载歧义
                val li = trackedPadIds.indexOf(event.getPointerId(aIdx))
                val lifted = li >= 0
                if (lifted) trackedPadIds.removeAt(li)
                when {
                    lifted && trackedPadIds.isNotEmpty() -> {
                        forward = MotionEvent.ACTION_POINTER_UP
                        includeActionPointer = true
                    }
                    lifted -> {
                        forward = MotionEvent.ACTION_UP // 桥内最后一根手指抬起
                        includeActionPointer = true
                    }
                    else -> if (hadTracked) forward = MotionEvent.ACTION_MOVE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                // 恒转发：记账在 POINTER_UP 时已被掏空，同指随后的系统级 UP
                // 必然 hadTracked=false —— 吞掉会让手势状态机收不到 UP
                // （左键卡按/tap 检测不触发）。IDLE 下收到多余 UP 仅归位状态。
                forward = action
        }

        if (forward < 0) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                trackedPadIds.clear()
            return null
        }

        // 子集 = 全部受跟踪指针（POINTER_UP 额外包含抬起的指针）
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (trackedPadIds.contains(event.getPointerId(i))) {
                stripIdx[count++] = i
                if (count == stripIdx.size) break
            }
        }
        if (includeActionPointer && count < stripIdx.size &&
            event.actionIndex < event.pointerCount && !trackedPadIds.contains(event.getPointerId(aIdx))
        ) stripIdx[count++] = event.actionIndex
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
            trackedPadIds.clear()
        if (count == 0) return null
        if (count == event.pointerCount && forward == action) return event // 无需剥离

        var fa = forward
        if (forward == MotionEvent.ACTION_POINTER_DOWN || forward == MotionEvent.ACTION_POINTER_UP) {
            var pos = 0
            for (j in 0 until count) if (stripIdx[j] == aIdx) { pos = j; break }
            fa = forward or (pos shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        for (j in 0 until count) {
            event.getPointerProperties(stripIdx[j], stripProps[j])
            event.getPointerCoords(stripIdx[j], stripCoords[j])
        }
        return MotionEvent.obtain(
            event.downTime, event.eventTime, fa, count,
            stripProps, stripCoords, event.metaState, event.buttonState,
            event.xPrecision, event.yPrecision, event.deviceId, event.edgeFlags,
            event.source, event.flags
        )
    }

    private enum class Mode { IDLE, ONE, TWO, TWO_DONE }

    fun updateTransform(surfaceW: Int, surfaceH: Int, screenW: Int, screenH: Int) {
        this.surfaceW = surfaceW
        this.surfaceH = surfaceH
        this.screenW = screenW
        this.screenH = screenH
        scaleX = if (surfaceW > 0) screenW.toFloat() / surfaceW else 1f
        scaleY = if (surfaceH > 0) screenH.toFloat() / surfaceH else 1f
        renderData.imageWidth = surfaceW
        renderData.imageHeight = surfaceH
        renderData.screenWidth = screenW
        renderData.screenHeight = screenH
        renderData.scale.set(scaleX, scaleY)
    }

    private fun toXpx(vx: Float, vy: Float): FloatArray {
        val x = (vx * scaleX).toInt().coerceIn(0, (screenW - 1).coerceAtLeast(0))
        val y = (vy * scaleY).toInt().coerceIn(0, (screenH - 1).coerceAtLeast(0))
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    private fun sendMoveX(x: Int, y: Int) {
        // 入队输入线程（绝对定位 motion，主线程零 socket I/O）
        val xf = x.toFloat()
        val yf = y.toFloat()
        postSend {
            if (LorieView.connected())
                view.sendMouseEvent(xf, yf, InputStub.BUTTON_UNDEFINED, false, false)
        }
    }

    private fun sendButton(button: Int, down: Boolean) {
        // (0,0)+relative = 纯按键事件（在当前指针位置按下/抬起），
        // 与 InputDeviceManager 的注入路径完全一致。
        // 入队输入线程（与 motion 冲刷 FIFO 保序，主线程零阻塞）
        postSend {
            if (LorieView.connected())
                view.sendMouseEvent(0f, 0f, button, down, true)
        }
    }

    // ---- 点击可靠性自愈 ----
    // 病灶模型：X 侧 lorieMouse/lalieTouch 按钮状态一旦卡在"按下"
    //（直接触摸遗留活跃触摸的仿真按钮、任一层丢失的 UP、剥离漏记账），
    // dix 对重复 ButtonPress 直接丢弃、孤儿 ButtonRelease 直接丢弃
    // —— 此后一切左键/右键（触摸点击与手柄鼠标键同路）全部失灵，
    // 而相对运动不受影响。自愈三件套全部基于"未按下的释放被 dix 安全
    // 丢弃"这一语义，零副作用：
    // 1) 首次输入前补发 1/2/3 键孤儿释放（healOrphanButtons）
    // 2) 新手势 DOWN 前强制归位桥内悬挂按压（resyncStuckPress）
    // 3) 切换触摸方式时补发 XI_TouchEnd(0..9) 冲掉直接触摸遗留的
    //    活跃触摸（closeAllTouches，解除触摸仿真按钮的永久卡按）

    // 诊断计数（X11 设置面板"输入诊断"只读展示；UI 线程专用）
    var diagDowns = 0; private set
    var diagUps = 0; private set
    var diagResyncs = 0; private set
    var diagTaps = 0; private set
    var diagHeals = 0; private set

    /** 首次输入前的孤儿按钮释放（healDone 后零开销） */
    private var healDone = false
    private fun healOrphanButtons() {
        if (healDone) return
        if (!LorieView.connected()) return
        healDone = true
        diagHeals++
        // 入队输入线程（FIFO 先于本手势的按下事件执行）
        postSend {
            view.sendMouseEvent(0f, 0f, InputStub.BUTTON_LEFT, false, true)
            view.sendMouseEvent(0f, 0f, InputStub.BUTTON_MIDDLE, false, true)
            view.sendMouseEvent(0f, 0f, InputStub.BUTTON_RIGHT, false, true)
        }
    }

    /** 新手势起点归位悬挂按压：卡键自愈 + 重复按下防抖（X 侧会被丢弃的按下不再发出） */
    private fun resyncStuckPress() {
        if (pressedLeft) {
            sendButton(InputStub.BUTTON_LEFT, false)
            pressedLeft = false
            diagResyncs++
        }
        if (padPressed) {
            sendButton(InputStub.BUTTON_LEFT, false)
            padPressed = false
            diagResyncs++
        }
    }

    /** 冲掉 0..9 号触摸的 X 侧活跃状态（解除触摸仿真按钮 1 的永久卡按） */
    private fun closeAllTouches() {
        // 入队输入线程（与后续触摸事件 FIFO 保序）
        postSend {
            if (LorieView.connected()) {
                for (id in 0..9) view.sendTouchEvent(XI_TOUCH_END, id, 0, 0)
            }
        }
    }

    /** 输入诊断文本（X11 设置面板"输入诊断"只读展示） */
    fun diagText(): String = buildString {
        append("触摸方式=")
        append(
            when (touchMode) {
                TOUCH_TRACKPAD -> "触控板"
                TOUCH_DIRECT -> "直接触摸"
                else -> "模拟触摸"
            }
        )
        append("  X连接="); append(if (LorieView.connected()) "是" else "否")
        append('\n')
        append("DOWN="); append(diagDowns)
        append("  UP="); append(diagUps)
        append("  轻点="); append(diagTaps)
        append('\n')
        append("卡键归位="); append(diagResyncs)
        append("  自愈="); append(diagHeals)
        append("  桥按压态: 左键="); append(if (pressedLeft) "按下" else "释放")
        append(" 轻点拖拽="); append(if (padPressed) "按下" else "释放")
    }

    /** 双击吸附：若处于双击窗口内且落点接近上次轻点，返回上次落点。 */
    private fun snapPoint(vx: Float, vy: Float): FloatArray {
        val now = android.os.SystemClock.uptimeMillis()
        val px = toXpx(vx, vy)
        if (lastTapAt > 0 && now - lastTapAt <= dblTapMaxMs) {
            val dx = px[0] - lastTapX
            val dy = px[1] - lastTapY
            val slopX = slopPx * scaleX
            val slopY = slopPx * scaleY
            if (dx * dx + dy * dy <= slopX * slopX + slopY * slopY) {
                return floatArrayOf(lastTapX, lastTapY)
            }
        }
        return px
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (!LorieView.connected() || screenW <= 0 || screenH <= 0) return true
        // 首次输入前补发孤儿按钮释放（卡键自愈；未卡则被 dix 丢弃）
        healOrphanButtons()
        // 剥离手柄元素指针（手柄未显示时零开销直通）
        val ev = filterPadPointers(event) ?: return true
        try {
            // 触摸方式分发（termux-x11 touchMode；X11 设置面板切换后即时生效）
            return when (touchMode) {
                TOUCH_TRACKPAD -> onTouchTrackpad(ev)
                TOUCH_DIRECT -> onTouchDirect(ev)
                else -> onTouchSimulated(ev)
            }
        } finally {
            if (ev !== event) ev.recycle()
        }
    }

    /**
     * 模式 2 —— 模拟触摸屏（termux-x11 "Simulated touchscreen"）：
     * 手指位置即鼠标位置（按下即左键、拖拽跟随、双击吸附、双指右键/滚轮）。
     */
    private fun onTouchSimulated(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 新手势起点归位悬挂按压（丢失 UP 的卡键自愈）
                resyncStuckPress()
                mode = Mode.ONE
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                downAt = android.os.SystemClock.uptimeMillis()
                moved = false
                diagDowns++
                val p = snapPoint(downX, downY)
                // tap 失灵根修：定位 + 左键按下合并为单条 absolute 按钮消息
                //（对齐上游 TouchInputHandler simulated touchscreen 同款
                // sendMouseEvent(x, y, LEFT, true, false)）。旧实现"absolute
                // 移动 + (0,0) relative 按钮"是混合序列，wine 下 tap 失灵。
                // 局部 val 捕获快照（post 执行时 tapX/tapY 已可被新手势改写）。
                val px = p[0]
                val py = p[1]
                tapX = px; tapY = py
                postSend {
                    if (LorieView.connected())
                        view.sendMouseEvent(px, py, InputStub.BUTTON_LEFT, true, false)
                }
                pressedLeft = true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.ONE && event.pointerCount >= 2) {
                    mode = Mode.TWO
                    // 拖拽被双指手势接管前，立即冲刷残余增量（不丢尾段）
                    postFlushMoves()
                    twoStartAt = android.os.SystemClock.uptimeMillis()
                    twoMoved = false
                    scrollAccum = 0f
                    lastTwoY = (event.getY(0) + event.getY(1)) / 2f
                    if (pressedLeft) {
                        sendButton(InputStub.BUTTON_LEFT, false)
                        pressedLeft = false
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.ONE -> {
                        // 拖拽期间发相对增量（relative=true → libXlorie XI2 raw
                        // relative motion）。旧实现逐帧发绝对坐标：wine FPS 按住
                        // 转视角时游戏每帧 XWarpPointer 回窗口中心，绝对事件把
                        // 指针强拽到指尖位置 → 游戏读到巨大跳变（视角猛转）。
                        // 相对增量不受 warp 影响：桌面拖拽与游戏视角都真实。
                        // 累加进合并器，16ms 节流冲刷 —— 事件量减半以上，
                        // 消除 wine 游戏滑动掉帧；总量精确守恒。
                        if (!moved) {
                            val ddx = event.x - downX
                            val ddy = event.y - downY
                            moved = ddx * ddx + ddy * ddy > slopPx * slopPx
                            if (moved)
                                accumulateMove(ddx * scaleX, ddy * scaleY)
                        } else {
                            accumulateMove((event.x - curX) * scaleX, (event.y - curY) * scaleY)
                        }
                        curX = event.x; curY = event.y
                    }
                    Mode.TWO -> {
                        if (event.pointerCount >= 2) {
                            val avgY = (event.getY(0) + event.getY(1)) / 2f
                            val dy = (lastTwoY - avgY) * scaleY // 手指上滑 → 正值 → 内容下滚
                            lastTwoY = avgY
                            if (kotlin.math.abs(dy) > 0.5f) twoMoved = true
                            scrollAccum += dy
                            if (kotlin.math.abs(scrollAccum) >= wheelStep) {
                                val steps = (scrollAccum / wheelStep).toInt()
                                // 滚轮入队输入线程（主线程零 socket I/O）
                                val delta = steps * wheelStep
                                postSend {
                                    if (LorieView.connected())
                                        view.sendMouseWheelEvent(0f, delta)
                                }
                                scrollAccum -= steps * wheelStep
                            }
                        }
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TWO) {
                    // 双指轻点（未滑动、时间短）→ 右键单击（单条 absolute 消息）
                    if (!twoMoved && android.os.SystemClock.uptimeMillis() - twoStartAt < 320L) {
                        val p = toXpx(curX, curY)
                        val px = p[0]
                        val py = p[1]
                        postSend {
                            if (LorieView.connected()) {
                                view.sendMouseEvent(px, py, InputStub.BUTTON_RIGHT, true, false)
                                view.sendMouseEvent(px, py, InputStub.BUTTON_RIGHT, false, false)
                            }
                        }
                    }
                    mode = Mode.TWO_DONE
                }
            }

            MotionEvent.ACTION_UP -> {
                // 手势结束先冲刷残余增量（不丢拖拽尾段）再抬键（FIFO 保证顺序）
                diagUps++
                if (pressedLeft) {
                    postFlushMoves()
                    if (!moved && mode == Mode.ONE) {
                        // tap 释放：absolute 同位置（与按下点一致，零位移、零 warp）
                        val rx = tapX
                        val ry = tapY
                        postSend {
                            if (LorieView.connected())
                                view.sendMouseEvent(rx, ry, InputStub.BUTTON_LEFT, false, false)
                        }
                    } else {
                        // 拖拽/双指后释放：当前指针位置抬键（relative 0,0，
                        // 不回 warp —— 拖拽中游戏 warp 循环不受扰动）
                        sendButton(InputStub.BUTTON_LEFT, false)
                    }
                    pressedLeft = false
                }
                if (mode == Mode.ONE) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!moved && now - downAt <= tapMaxMs) {
                        diagTaps++
                        // 完整轻点 → 记录落点（X 坐标）供双击吸附（同按下瞬间落点）
                        lastTapX = tapX; lastTapY = tapY; lastTapAt = now
                    } else {
                        lastTapAt = 0L
                    }
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                // 系统取消手势 → 丢弃未冲刷增量（不发出意外移动）
                dropPendingMoves()
                if (pressedLeft) {
                    // CANCEL：当前位置抬键（不 warp；tap 未发生不产生点击）
                    sendButton(InputStub.BUTTON_LEFT, false)
                    pressedLeft = false
                }
                mode = Mode.IDLE
            }
        }
        return true
    }

    // ---- 直接触摸（TOUCH_DIRECT）MOVE 合并器 ----
    // 每 MOVE 事件逐指针直发 XI_TouchUpdate 在 120~240Hz 触摸下事件洪泛，
    // X server 输入线程被逐事件唤醒，wine 满载时抢占渲染时间片 → 滑屏
    // 掉帧。改为"最新全量快照 + 16ms 节流"：MOVE 只更新快照（零 JNI），
    // 后台线程按最小间隔把快照构造成一条 ACTION_MOVE 直发（触摸屏语义：
    // 中间采样可丢，绝对坐标快照天然幂等）。DOWN/POINTER_DOWN/POINTER_UP/
    // UP/CANCEL 是 touch 序列边界，必须即时直发不可合并。快照由 dtLock
    // 保护（主线程手势收尾与冲刷线程并发排水，与相对合并器同款模式）。
    private val dtLock = Any()
    private val dtIds = IntArray(10)
    private val dtProps = Array(10) { MotionEvent.PointerProperties() }
    private val dtCoords = Array(10) { MotionEvent.PointerCoords() }
    /** UP/CANCEL 收尾用的残留槽位快照（锁内拷贝、锁外发 TouchEnd） */
    private val dtCloseIds = IntArray(10)
    private var dtCount = 0
    private var dtDirty = false
    private var dtDownTime = 0L
    private var dtDeviceId = 0
    private var dtSource = 0
    @Volatile private var dtFlushPending = false
    private val dtFlushRunnable = Runnable { flushDirectMoves() }

    private fun flushDirectMoves() {
        flushHandler.removeCallbacks(dtFlushRunnable)
        dtFlushPending = false
        var snap: MotionEvent? = null
        synchronized(dtLock) {
            if (!dtDirty || dtCount == 0) return
            dtDirty = false
            snap = MotionEvent.obtain(
                dtDownTime, android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE, dtCount, dtProps, dtCoords,
                0, 0, 1f, 1f, dtDeviceId, 0, dtSource, 0
            )
        }
        try {
            if (LorieView.connected()) keySender.sendTouchEvent(snap!!, renderData)
        } finally {
            snap!!.recycle()
        }
    }

    /**
     * direct 模式触摸序列边界（DOWN/POINTER_DOWN/POINTER_UP/UP/CANCEL）
     * 入队输入线程发送。系统 MotionEvent 在 dispatch 返回后会被
     * ViewRootImpl 回收复用，不能跨线程持有 —— obtain 一份副本入队，
     * 输入线程用完回收（每手势 2~3 次小分配；MOVE 走零分配快照合并器）。
     */
    private fun postTouchEvent(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        postSend {
            try {
                if (LorieView.connected()) keySender.sendTouchEvent(copy, renderData)
            } finally {
                copy.recycle()
            }
        }
    }

    /**
     * 模式 3 —— 直接触摸（termux-x11 "Direct touch"）：
     * 原始多点触摸不经手势转换，按 XI2 Touch 事件直注 X（坐标经
     * renderData 视图→X 屏幕变换）—— 触摸类游戏/应用获得真实多点触摸，
     * X server 同时向老程序模拟鼠标（xinput 保真，等效上游 NullInputStrategy）。
     */
    private fun onTouchDirect(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 新手势起点：重置快照并记录构造参数（downTime/deviceId/source）
                flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
                synchronized(dtLock) {
                    dtCount = 0; dtDirty = false
                    dtDownTime = event.downTime
                    dtDeviceId = event.deviceId
                    dtSource = event.source
                }
                postTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                // 快照更新（零 JNI）：按 pointerId 槽位覆盖最新属性/坐标
                synchronized(dtLock) {
                    val n = event.pointerCount.coerceAtMost(dtIds.size)
                    for (i in 0 until n) {
                        val id = event.getPointerId(i)
                        var slot = dtIds.indexOf(id)
                        if (slot < 0 || slot >= dtCount) {
                            if (dtCount >= dtIds.size) continue
                            slot = dtCount++
                            dtIds[slot] = id
                        }
                        event.getPointerProperties(i, dtProps[slot])
                        event.getPointerCoords(i, dtCoords[slot])
                    }
                    dtDirty = true
                }
                if (!dtFlushPending) {
                    dtFlushPending = true
                    flushHandler.postDelayed(dtFlushRunnable, MOTION_FLUSH_INTERVAL_MS)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 该指 touch 序列结束：丢弃未冲刷快照（可能含已抬指旧
                // 坐标）并把该指移出快照槽（交换压缩），再直发 TouchEnd
                flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
                synchronized(dtLock) {
                    dtDirty = false
                    val si = dtIds.indexOf(event.getPointerId(event.actionIndex))
                    if (si >= 0 && si < dtCount) {
                        for (j in si until dtCount - 1) {
                            dtIds[j] = dtIds[j + 1]
                            val tp = dtProps[j]; dtProps[j] = dtProps[j + 1]; dtProps[j + 1] = tp
                            val tc = dtCoords[j]; dtCoords[j] = dtCoords[j + 1]; dtCoords[j + 1] = tc
                        }
                        dtCount--
                    }
                }
                // 子事件原样发送，X 侧按 actionIndex 结束对应指
                postTouchEvent(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // UP 前先冲刷最新位置（不丢拖拽尾段）；CANCEL 只清态
                diagUps++
                if (event.actionMasked == MotionEvent.ACTION_UP) postSend { flushDirectMoves() }
                flushHandler.removeCallbacks(dtFlushRunnable); dtFlushPending = false
                var leftover = 0
                synchronized(dtLock) {
                    // 残留触摸槽位记入 leftover（多指异常直发 UP 时，
                    // 其余手指的 X 侧触摸不能悬挂 —— 悬挂触摸的仿真按钮 1
                    // 会永久卡按，此后一切左键点击全失灵）
                    for (i in 0 until dtCount) dtCloseIds[i] = dtIds[i]
                    leftover = dtCount
                    dtCount = 0; dtDirty = false
                }
                postTouchEvent(event)
                // 原始 UP/CANCEL 已按 actionIndex 结束对应指；其余槽位 +
                // 重复的抬起指补发 TouchEnd（X 侧丢弃未激活的 TouchEnd，
                // 零副作用）
                for (i in 0 until leftover) {
                    val id = dtCloseIds[i]
                    postSend {
                        if (LorieView.connected()) view.sendTouchEvent(XI_TOUCH_END, id, 0, 0)
                    }
                }
            }
            else -> postTouchEvent(event) // POINTER_DOWN 等
        }
        return true
    }

    /**
     * 模式 1 —— 触控板（termux-x11 "Trackpad"）：
     * 手指滑动 = 虚拟光标相对移动（scaleTouchpad 开启时位移按视图→X
     * 屏幕拉伸比例放大，与上游 TouchInputHandler 一致）；
     * 轻点 = 左键单击；tapToMove（轻点拖拽）= 轻点按下左键、移动拖拽、
     * 再轻点释放；双指轻点 = 右键单击；双指滑动 = 滚轮。
     */
    private fun onTouchTrackpad(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 新手势起点归位悬挂按压（丢失 UP 的卡键自愈）
                resyncStuckPress()
                mode = Mode.ONE
                downX = event.x; downY = event.y
                curX = downX; curY = downY
                downAt = android.os.SystemClock.uptimeMillis()
                moved = false
                diagDowns++
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.ONE && event.pointerCount >= 2) {
                    mode = Mode.TWO
                    // 拖拽被双指手势接管前，立即冲刷残余增量（不丢尾段）
                    postFlushMoves()
                    twoStartAt = android.os.SystemClock.uptimeMillis()
                    twoMoved = false
                    scrollAccum = 0f
                    lastTwoY = (event.getY(0) + event.getY(1)) / 2f
                    // 拖拽中落下第二根手指 → 结束拖拽（真实触控板习惯）
                    if (padPressed) { sendButton(InputStub.BUTTON_LEFT, false); padPressed = false }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.ONE -> {
                        val dx = event.x - curX
                        val dy = event.y - curY
                        curX = event.x; curY = event.y
                        if (!moved) {
                            val ddx = curX - downX
                            val ddy = curY - downY
                            moved = ddx * ddx + ddy * ddy > slopPx * slopPx
                        }
                        // 发真实相对运动（sendMouseEvent relative=true →
                        // libXlorie XI2 raw relative motion，上游
                        // TrackpadInputStrategy 同款）：wine FPS 按住转视角时
                        // 游戏每帧 XWarpPointer 回窗口中心，绝对事件会把指针
                        // 强拽回虚拟光标位置 → 视角猛转/卡死。相对增量不受
                        // warp 影响。slop 内丢弃（防手抖）。
                        // scaleTouchpad：位移按视图→X 拉伸比例放大（对齐上游）。
                        // 累加进合并器，16ms 节流冲刷；总量精确守恒，手感不变。
                        if (moved) {
                            val mulX = if (scaleTouchpad) scaleX else 1f
                            val mulY = if (scaleTouchpad) scaleY else 1f
                            accumulateMove(dx * mulX, dy * mulY)
                        }
                    }
                    Mode.TWO -> {
                        if (event.pointerCount >= 2) {
                            val avgY = (event.getY(0) + event.getY(1)) / 2f
                            val dy = (lastTwoY - avgY) * scaleY // 双指上滑 → 滚轮下滚
                            lastTwoY = avgY
                            if (kotlin.math.abs(dy) > 0.5f) twoMoved = true
                            scrollAccum += dy
                            if (kotlin.math.abs(scrollAccum) >= wheelStep) {
                                val steps = (scrollAccum / wheelStep).toInt()
                                // 滚轮入队输入线程（主线程零 socket I/O）
                                val delta = steps * wheelStep
                                postSend {
                                    if (LorieView.connected())
                                        view.sendMouseWheelEvent(0f, delta)
                                }
                                scrollAccum -= steps * wheelStep
                            }
                        }
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.TWO) {
                    // 双指轻点（未滑动、时间短）→ 右键单击（当前 X 指针位置）
                    if (!twoMoved && android.os.SystemClock.uptimeMillis() - twoStartAt < 320L) {
                        sendButton(InputStub.BUTTON_RIGHT, true)
                        sendButton(InputStub.BUTTON_RIGHT, false)
                    }
                    mode = Mode.TWO_DONE
                }
            }

            MotionEvent.ACTION_UP -> {
                // 手势结束先冲刷残余增量（不丢拖拽尾段）
                diagUps++
                if (moved) postFlushMoves()
                if (mode == Mode.ONE) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!moved && now - downAt <= tapMaxMs) {
                        if (tapToMove) {
                            // 轻点拖拽：轻点=按下（随后移动即拖拽），再轻点=释放
                            if (padPressed) {
                                sendButton(InputStub.BUTTON_LEFT, false)
                                padPressed = false
                            } else {
                                sendButton(InputStub.BUTTON_LEFT, true)
                                padPressed = true
                            }
                        } else {
                            // 轻点 = 左键单击
                            sendButton(InputStub.BUTTON_LEFT, true)
                            sendButton(InputStub.BUTTON_LEFT, false)
                        }
                    }
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                // 系统取消手势 → 丢弃未冲刷增量（不发出意外移动）
                dropPendingMoves()
                if (padPressed) { sendButton(InputStub.BUTTON_LEFT, false); padPressed = false }
                mode = Mode.IDLE
            }
        }
        return true
    }

    fun sendKey(event: KeyEvent): Boolean = keySender.sendKeyEvent(event)

    /** 同步 termux-x11 "首选扫描码"偏好到按键发送器（即时生效）。 */
    fun setPreferScancodes(v: Boolean) {
        keySender.preferScancodes = v
    }
}
