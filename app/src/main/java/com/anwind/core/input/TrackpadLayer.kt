package com.anwind.core.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.anwind.AnWindApp
import com.anwind.apps.browser.ZoomPinchLayout
import kotlin.math.abs

/**
 * 注入合成事件在【原生 View 层】使用的指针 id 基线。
 *
 * ⚠️ 重要：Compose 1.6.8 的 [MotionEventAdapter] 维护一个
 * `motionEventToComposePointerIdMap`（SparseLongArray），把 MotionEvent 的
 * 原始 pointerId 映射为内部自增 id（`nextId` 从 0 递增、永不回落）。
 * 因此注入事件的原始 id=99 进入 Compose 后会被重映射成 0/1/2… 的
 * 小数字 —— Compose 层（门禁、桌面手势层）**无法再用 `id < 99` 区分**
 * 注入流与真实手指，否则会把注入点击当真实手指消费 → endGesture→
 * tapClick 再注入 → 状态机错乱/循环，最终门禁失效、真实触摸泄漏
 * （"鼠标点一下就不动了，恢复成普通触控"的根因）。
 *
 * 正确判别维度是 [PointerType]：
 * - 真实手指：toolType=FINGER → Compose `type == PointerType.Touch`
 * - 注入合成（点击/拖拽）：toolType=MOUSE → `type == PointerType.Mouse`
 * - 注入滚轮：ACTION_SCROLL + SOURCE_MOUSE → Scroll 事件
 *
 * Compose 层一律按 `type == PointerType.Touch` 判定真实手指；
 * 原生 View 层（[com.anwind.apps.browser.ZoomPinchLayout] 等
 * dispatchTouchEvent 入口）拿到的是未经映射的原始 MotionEvent，
 * 仍可用 `MotionEvent.getPointerId() >= INJECTED_POINTER_ID` 判定注入指针。
 */
internal const val INJECTED_POINTER_ID = 99

/**
 * 触控板（Trackpad）指针移动模式 —— 门禁层。
 *
 * ## v2.19.5 重写：对齐 termux-x11 的「同步事件泵 + Handler 定时器」架构
 * （termux-x11 的 TouchInputHandler / TapGestureDetector，源自 Chromium remoting）
 *
 * v2.19.0~2.19.4 反复失灵的根因（"点击一次就退化成普通触摸"）：
 * 1. 【致命】门禁在 awaitPointerEventScope 内使用了 kotlinx.coroutines 的
 *    withTimeoutOrNull。Compose 的 handler 协程运行在 restricted suspension
 *    上下文（EmptyCoroutineContext：无父 Job、无调度器），kotlinx 的超时到期
 *    后在【Timer 线程】上同步内联恢复挂起的 awaitPointerEvent —— 门禁状态机
 *    从此跨线程执行：pointerAwaiter/awaitPass 的注册（Timer 线程）与事件派发
 *    （主线程 offerPointerEvent）变成无锁竞争，事件被静默跳过/丢失，真实触摸
 *    未经消费漏到下层 UI = "普通触摸逻辑"。长按（>320ms 静止）必然触发该路径。
 * 2. pointerInput(view, onTwoFingerTap) 把每次重组都是新实例的 lambda 当 key，
 *    SuspendPointerInputElement.equals 只比较 keys → 桌面每次重组（开窗口/
 *    弹菜单等）都触发 resetPointerInputHandler，门禁协程被反复取消重建。
 * 3. 多阶段 await 状态机（超时判定窗 + 主循环 + 收尾排水）跨阶段契约脆弱，
 *    历史上"不设防窗口""重入丢弃"等缺陷均源于此结构。
 *
 * termux-x11 的稳定之道（本次照搬）：
 * - 手势机是【同步事件泵】：每个事件到达后当场消费、当场分类，全程唯一的
 *   挂起点是 awaitPointerEvent —— 没有任何 withTimeout / delay / 嵌套等待；
 * - 长按用【Handler 定时器】（view.postDelayed），与事件泵同在主线程，
 *   天然串行互斥：定时器只可能在两次事件派发之间触发，不存在竞态；
 * - 手势机输出"鼠标语义动作"给独立注入器（本文件 TrackpadController，
 *   相当于 termux-x11 的 InputEventSender / X server），注入流按 pointer id
 *   与手势机彻底解耦，手势机永远看不到自己的输出。
 *
 * ## 手势集（真实手指 → 指针动作）
 * - 单指滑动：指针相对移动（× [TrackpadController.SENSITIVITY]）；
 * - 轻点（< 320ms 且各指位移 < 24dp）：指针处注入单击（连点两下 = 双击，
 *   由目标控件自行判定）；
 * - 按住不动 ≥ 320ms：进入拖拽 —— 注入 ACTION_DOWN，之后滑动 = 按住
 *   拖拽（拖窗口/拖滑块/划词），抬手注入 ACTION_UP；
 * - 双指滑动（超 16dp slop）：指针处注入滚轮（WebView 优先直达，60px = 1 tick）；
 * - 双指轻点（< 450ms、未滚动、未超 slop）：直接回调 onTwoFingerTap
 *   （DesktopEnvironment 直连 openContextMenu，零注入）。
 */
@Composable
fun TrackpadGate(
    modifier: Modifier = Modifier,
    onTwoFingerTap: ((Offset) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val app = remember { AnWindApp.get() }
    val controlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    val view = LocalView.current

    // v2.19.5：回调经 rememberUpdatedState 以稳定 State 引用传入门禁。
    // pointerInput 只以 view 为 key —— 桌面重组不再取消/重启门禁协程
    // （旧版以不稳定 lambda 为 key，每次重组都 reset 门禁 —— 根因之二）。
    val twoFingerTapState = rememberUpdatedState(onTwoFingerTap)

    Box(
        modifier = modifier.then(
            // touch 模式 = 纯容器零开销；trackpad 模式挂上门禁
            if (controlMode == "trackpad") Modifier.trackpadGate(view, twoFingerTapState) else Modifier
        )
    ) {
        content()
    }
}

/**
 * 触控板门禁（v2.19.5 同步事件泵版）。
 *
 * 与 termux-x11 的对应关系：
 * - 主循环            ↔ TouchInputHandler.handleTouchEvent（每事件同步走完）
 * - 逐指 slop 检测    ↔ TapGestureDetector.trackMoveEvent / mInitialPositions
 * - 长按定时器        ↔ TapGestureDetector 的 mHandler.sendEmptyMessageDelayed
 * - 单指滑动 → 指针   ↔ GestureListener.onScroll → moveCursorByOffset
 * - 双指滑动 → 滚轮   ↔ GestureListener.onScroll(2指) → InputStrategy.onScroll
 * - 轻点 → 单击       ↔ TapGestureDetector.onTap(1指) → TrackpadInputStrategy.onTap
 * - 双指轻点 → 右键   ↔ TapGestureDetector.onTap(2指) → BUTTON_RIGHT
 * - 长按 → 按住拖拽   ↔ onLongPress → onPressAndHold（mousedown 起流）
 *
 * 事件泵不变式：
 * 1. 真实手指（id < 99）的每一个事件都在 Initial pass 被无条件消费 ——
 *    等待按下、手势进行、残余收尾，无一例外，不存在"不设防窗口"；
 * 2. 注入流（id ≥ 99，含滚轮）零耦合：一眼过滤、不等待、不消费；
 * 3. 除 awaitPointerEvent 外无任何挂起点/定时等待 —— 状态机永远只在主线程
 *    的事件派发切片内执行。
 */
private fun Modifier.trackpadGate(
    view: View,
    twoFingerTapState: State<((Offset) -> Unit)?>
): Modifier = pointerInput(view) {
    val touchSlopPx = 24.dp.toPx()
    val scrollSlopPx = 16.dp.toPx()
    val longPressMs = 320L
    val twoFingerTapMs = 450L

    awaitPointerEventScope {
        // ===== 手势会话状态（termux-x11 TapGestureDetector 的对应物）=====
        var inGesture = false          // 当前是否有手势会话
        var maxPointers = 1            // 本会话出现过的最大按指数
        var moved = false              // 任一指超出各自起点 slop（tap 候选作废）
        var scrollMode = false         // 双指滚动已接管
        var dragMode = false           // 长按拖拽进行中（已注入 DOWN）
        var livePressed = 0            // 当前按着的真实指数（定时器防火墙）
        var gestureStartMs = 0L        // 会话起点事件时间戳
        var lastCentroid = Offset.Zero // 多指质心（滚轮增量基准）
        val startPositions = HashMap<PointerId, Offset>() // 每指起点（slop 检测）
        val lastPositions = HashMap<PointerId, Offset>()  // 每指上帧位置（增量基准）

        // ===== 长按定时器（termux-x11 TapGestureDetector 的 Handler 方案）=====
        // 主线程 postDelayed：只可能在两次事件派发之间触发，与事件泵天然互斥；
        // 到点时核对会话状态，满足"单指、未动、未滚、未拖"才进入拖拽。
        val longPressRunnable = Runnable {
            if (inGesture && !dragMode && !scrollMode && !moved &&
                maxPointers == 1 && livePressed == 1
            ) {
                dragMode = true
                moved = true
                MouseController.press(true)
                val cursor = MouseController.position
                TrackpadController.injectDragDown(view, cursor.x, cursor.y)
            }
        }

        fun cancelLongPress() {
            view.removeCallbacks(longPressRunnable)
        }

        /** 指针相对移动（灵敏度放大 + clamp 屏内） */
        fun moveCursorBy(delta: Offset) {
            if (delta.getDistance() <= 0.01f) return
            val cur = MouseController.position
            val nx = (cur.x + delta.x * TrackpadController.SENSITIVITY)
                .coerceIn(0f, size.width.toFloat())
            val ny = (cur.y + delta.y * TrackpadController.SENSITIVITY)
                .coerceIn(0f, size.height.toFloat())
            MouseController.update(nx, ny)
        }

        /** 多指质心（滚轮增量基准） */
        fun centroidOf(changes: List<PointerInputChange>): Offset {
            var x = 0f
            var y = 0f
            changes.forEach { c ->
                x += c.position.x
                y += c.position.y
            }
            return Offset(x / changes.size, y / changes.size)
        }

        /** 手势收尾：按会话累计状态判定轻点/双指轻点，并处理拖拽释放 */
        fun endGesture(nowMs: Long) {
            cancelLongPress()
            val duration = nowMs - gestureStartMs
            val cursor = MouseController.position
            when {
                dragMode -> {
                    // 拖拽收尾：视觉松压 + 注入 ACTION_UP
                    dragMode = false
                    MouseController.press(false)
                    TrackpadController.injectDragUp(view, cursor.x, cursor.y)
                }
                maxPointers == 1 && !moved && duration < longPressMs ->
                    // 单指轻点 = 指针处单击（tap 窗口对齐长按阈值，无缝隙）
                    TrackpadController.tapClick(view, cursor.x, cursor.y)
                maxPointers == 2 && !scrollMode && !moved && duration < twoFingerTapMs ->
                    // 双指轻点 = 右键菜单：直接回调，不注入合成流
                    twoFingerTapState.value?.invoke(cursor)
            }
        }

        try {
            // ===== 主循环：同步事件泵 =====
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // 真实手指 = Touch 型指针。注入流（Mouse 型点击/拖拽、Scroll 型滚轮）
                // 一律放行给下层窗口 —— 不能用 id<99 判定：Compose 的
                // MotionEventAdapter 会把注入的原始 id=99 重映射为自增小数字
                //（详见 INJECTED_POINTER_ID 注释），id 维度无法区分注入与真实。
                val real = event.changes.filter {
                    it.type == PointerType.Touch && it.id.value < INJECTED_POINTER_ID
                }
                // 纯注入事件（点击/拖拽/滚轮流）：完全无视，放行给下层
                if (real.isEmpty()) continue
                // 触控板模式：真实手指永远不到下层（无差别消费）
                real.forEach { it.consume() }

                val downs = real.filter { it.changedToDownIgnoreConsumed() }
                val ups = real.filter { it.changedToUpIgnoreConsumed() }
                val pressed = real.filter { it.pressed }
                livePressed = pressed.size

                // ---- 会话种子：新手势（carryOver 语义内联在此）----
                if (!inGesture) {
                    val seed = downs.firstOrNull() ?: continue // 残余 MOVE/UP：已消费，丢弃
                    inGesture = true
                    maxPointers = 1
                    moved = false
                    scrollMode = false
                    dragMode = false
                    startPositions.clear()
                    lastPositions.clear()
                    gestureStartMs = seed.uptimeMillis
                    lastCentroid = seed.position
                    view.postDelayed(longPressRunnable, longPressMs)
                }
                // 同帧多指落下（含 seed）逐指登记起点
                downs.forEach { d ->
                    startPositions.putIfAbsent(d.id, d.position)
                    lastPositions.putIfAbsent(d.id, d.position)
                }

                // ---- 指数升级：进入多指（单指长按拖拽作废、质心重播种）----
                if (pressed.size > maxPointers) {
                    maxPointers = pressed.size
                    if (maxPointers >= 2) {
                        cancelLongPress()
                        lastCentroid = centroidOf(pressed)
                    }
                }

                // ---- 逐指 slop 检测（TapGestureDetector.trackMoveEvent）----
                if (!moved) {
                    for (c in pressed) {
                        val start = startPositions[c.id]
                        if (start != null && (c.position - start).getDistance() > touchSlopPx) {
                            moved = true
                            cancelLongPress()
                            break
                        }
                    }
                }

                if (pressed.size >= 2) {
                    // ---- 双指：滑动 = 滚轮（onScroll → wheel）----
                    val centroid = centroidOf(pressed)
                    val d = centroid - lastCentroid
                    if (!scrollMode) {
                        if (d.getDistance() > scrollSlopPx) {
                            scrollMode = true
                            moved = true
                            cancelLongPress()
                        }
                    } else if (d.getDistance() > 0f) {
                        val cursor = MouseController.position
                        TrackpadController.injectScroll(view, cursor.x, cursor.y, d.x, d.y)
                    }
                    lastCentroid = centroid
                } else if (pressed.size == 1) {
                    // ---- 单指：移动指针；拖拽态同时注入 MOVE ----
                    // put 返回旧值：首见指只登记不移动（指数切换零跳变）
                    val c = pressed.first()
                    val last = lastPositions.put(c.id, c.position)
                    if (last != null) moveCursorBy(c.position - last)
                    if (dragMode) {
                        val cursor = MouseController.position
                        TrackpadController.injectDragMove(view, cursor.x, cursor.y)
                    }
                }

                // ---- 抬起的指移出登记表（后续增量基准不再引用）----
                ups.forEach { u ->
                    startPositions.remove(u.id)
                    lastPositions.remove(u.id)
                }

                // ---- 全部抬起：收尾分类，会话结束 ----
                if (pressed.isEmpty()) {
                    endGesture(real.maxOf { it.uptimeMillis })
                    inGesture = false
                }
            }
        } finally {
            // 门禁销毁（模式切换 / 节点 detach / 重组重置）：撤定时器并释放
            // 注入中的拖拽流，防止"幽灵长按"漏出一条没有 UP 的按下指针
            cancelLongPress()
            if (dragMode) {
                dragMode = false
                MouseController.press(false)
                val cursor = MouseController.position
                TrackpadController.injectDragUp(view, cursor.x, cursor.y)
            }
        }
    }
}

/**
 * 触控板注入器：把指针动作合成为真实 MotionEvent / 滚轮事件派发给目标窗口。
 * 所有触摸型注入使用 id ≥ [INJECTED_POINTER_ID] 的合成指针（门禁据此无视）。
 *
 * 相当于 termux-x11 架构中的 InputEventSender（动作的独立接收端）：无论事件
 * 走根 View 还是浏览器旁路容器，门禁状态机对注入流零耦合、不受任何影响。
 */
object TrackpadController {

    /** 指针移动灵敏度（手指像素 → 指针像素倍率） */
    const val SENSITIVITY = 1.6f

    /** 当前指针是否被触控板按住（视觉按压效果） */
    var pressing: Boolean
        get() = MouseController.pressed
        private set(value) {
            MouseController.press(value)
        }

    /** 轻点板面 = 指针处一次完整单击（视觉按压与注入绑定为原子动作） */
    fun tapClick(view: View, x: Float, y: Float) {
        pressing = true
        injectClick(view, x, y)
        pressing = false
    }

    /**
     * 在指定 View 上注入一次完整点击（DOWN → UP，间隔 60ms 事件时间）。
     *
     * ⚠️ 必须通过 view.post { ... } 延迟到下一个 looper 迭代再派发：
     * injectClick 从门禁事件泵内部被调用时，Compose 的
     * PointerInputEventProcessor 正在处理「真实手指的 UP 事件」，直接同步
     * dispatchTouchEvent 是重入调用，会被内部忙碌保护静默丢弃。
     * view.post 把派发推迟到下一个 looper 迭代（门禁对注入事件零耦合、
     * 完全无视），点击流直达目标控件。
     */
    fun injectClick(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.post {
            try {
                dispatchTouch(view, MotionEvent.ACTION_DOWN, now, 0L, x, y)
                dispatchTouch(view, MotionEvent.ACTION_UP, now, 60L, x, y)
            } catch (_: Exception) {
            }
        }
    }

    // ===== 拖拽流：DOWN（长按超时）→ MOVE…（跟随指针）→ UP（抬手） =====
    private var dragDownTime = 0L

    fun injectDragDown(view: View, x: Float, y: Float) {
        dragDownTime = SystemClock.uptimeMillis()
        val dt = dragDownTime
        view.post {
            runCatching { dispatchTouch(view, MotionEvent.ACTION_DOWN, dt, 0L, x, y) }
        }
    }

    fun injectDragMove(view: View, x: Float, y: Float) {
        if (dragDownTime == 0L) return
        val dt = dragDownTime
        val elapsed = SystemClock.uptimeMillis() - dragDownTime
        view.post {
            runCatching {
                dispatchTouch(view, MotionEvent.ACTION_MOVE, dt, elapsed, x, y)
            }
        }
    }

    fun injectDragUp(view: View, x: Float, y: Float) {
        if (dragDownTime == 0L) return
        val dt = dragDownTime
        val elapsed = SystemClock.uptimeMillis() - dragDownTime
        dragDownTime = 0L
        view.post {
            runCatching {
                dispatchTouch(view, MotionEvent.ACTION_UP, dt, elapsed, x, y)
            }
        }
    }

    /**
     * 注入一次滚轮事件（ACTION_SCROLL + AXIS_VSCROLL/HSCROLL）。
     * dx/dy 为手指双指滑动增量（像素）：双指上滑（dy<0）= 向下翻页，
     * 与触控板自然方向一致。每 60px 折算 1 个滚轮 tick。
     *
     * 派发目标：指针正下方的最深子 View（WebView 等原生 View 自带滚轮
     * 处理，直达最稳）；不是 WebView 则回根 View 走 Compose 滚动管线。
     */
    fun injectScroll(view: View, x: Float, y: Float, dx: Float, dy: Float) {
        if (abs(dx) < 0.4f && abs(dy) < 0.4f) return
        val now = SystemClock.uptimeMillis()
        // 滚轮事件同样携带合成指针 id（门禁与手势层按 id < 99 过滤真实指针）
        val p = MotionEvent.PointerProperties().apply {
            id = INJECTED_POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val c = MotionEvent.PointerCoords().apply {
            // this. 前缀必须显式：函数参数 x/y 会遮蔽接收者同名成员
            this.x = x; this.y = y; this.pressure = 1f; this.size = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / 60f)
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy / 60f)
        }
        view.post {
            try {
                val ev = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_SCROLL,
                    1, arrayOf(p), arrayOf(c),
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
                )
                val target = deepestViewAt(view, x, y)
                if (target is WebView) {
                    // 坐标换算到目标 View 局部
                    val rootLoc = IntArray(2); view.getLocationInWindow(rootLoc)
                    val tgtLoc = IntArray(2); target.getLocationInWindow(tgtLoc)
                    ev.offsetLocation(
                        -(tgtLoc[0] - rootLoc[0]).toFloat(),
                        -(tgtLoc[1] - rootLoc[1]).toFloat()
                    )
                    target.dispatchGenericMotionEvent(ev)
                } else {
                    // 滚轮属通用（generic）motion 事件：Compose 滚动组件按鼠标
                    // 滚轮路径响应
                    view.dispatchGenericMotionEvent(ev)
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 单指触摸注入（合成指针 id = INJECTED_POINTER_ID，toolType = MOUSE）。
     * toolType 用 MOUSE 而非 FINGER：Compose 的 MotionEventAdapter 会把原始
     * pointerId=99 重映射为自增小数字，id 维度无法区分注入与真实手指；改用
     * MOUSE 后 Compose 的 PointerInputChange.type = PointerType.Mouse，门禁与
     * 桌面手势层据此（type == PointerType.Touch）可靠区分，注入流不再被误吞。
     */
    private fun dispatchTouch(view: View, action: Int, downTime: Long, eventDelta: Long, x: Float, y: Float) {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = INJECTED_POINTER_ID
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; this.pressure = 1f; this.size = 1f
            }
        )
        dispatchTouchAt(
            view,
            MotionEvent.obtain(
                downTime, downTime + eventDelta, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            ),
            x, y
        )
    }

    /**
     * 把事件派发给包含 (x,y) 的【最顶层窗口】。
     * Compose 的 Popup / DropdownMenu / Dialog 渲染在独立窗口里，只向主
     * 窗口注入时点不到菜单项 —— 这里反射枚举本进程全部窗口根
     * （WindowManagerGlobal.mViews，后加入者 z 序更高），命中最顶层的
     * 一个派发（坐标同步换算）；反射失败回退主窗口。真实多窗口路由
     * 语义与系统一致：点在弹窗上 → 弹窗处理；点在弹窗外 → 主窗口处理。
     *
     * v2.19.3 起：落点在原生 interop 容器（[ZoomPinchLayout]，浏览器画布）
     * 上时【旁路派发】——直接 dispatchTouchEvent 给容器，绕开 Compose
     * 门禁与 PointerInteropFilter（否则过滤器在门禁消费真实手指后会把
     * 注入流闩死并向 WebView 发 CANCEL）。v2.19.4 起门禁对注入流零耦合，
     * 本旁路只服务 interop 过滤器，仍是必需的。
     */
    private fun dispatchTouchAt(view: View, event: MotionEvent, x: Float, y: Float) {
        try {
            val mainLoc = IntArray(2); view.getLocationOnScreen(mainLoc)
            val roots = allWindowRoots(view)
            var i = roots.size - 1
            while (i >= 0) {
                val root = roots[i]
                val loc = IntArray(2); root.getLocationOnScreen(loc)
                val lx = x + mainLoc[0] - loc[0]
                val ly = y + mainLoc[1] - loc[1]
                if (lx >= 0f && ly >= 0f && lx < root.width && ly < root.height) {
                    val bypass = findBypassTarget(root, lx, ly)
                    if (bypass != null) {
                        // 换算到容器局部坐标后直接派发（绕过 interop 过滤器）
                        val bLoc = IntArray(2); bypass.getLocationOnScreen(bLoc)
                        val bx = x + mainLoc[0] - bLoc[0]
                        val by = y + mainLoc[1] - bLoc[1]
                        event.offsetLocation(bx - event.x, by - event.y)
                        bypass.dispatchTouchEvent(event)
                    } else {
                        event.offsetLocation(lx - event.x, ly - event.y)
                        root.dispatchTouchEvent(event)
                    }
                    return
                }
                i--
            }
        } catch (_: Exception) {
        }
        view.dispatchTouchEvent(event)
    }

    /**
     * 查找 (x,y)（root 局部坐标）处可旁路的原生 interop 容器。
     * 自最深可见子 View 沿 parent 上溯，命中 [ZoomPinchLayout] 即返回
     * （浏览器画布）；到达 root 仍未命中 → null（走普通根派发路径）。
     * 其它 AndroidView（如有）不上溯命中，保持原有行为。
     */
    private fun findBypassTarget(root: View, x: Float, y: Float): View? {
        val deepest = deepestViewAt(root, x, y) ?: return null
        var cur: View = deepest
        while (cur !== root) {
            if (cur is ZoomPinchLayout) return cur
            cur = cur.parent as? View ?: return null
        }
        return null
    }

    /** 本进程全部已 attach 的窗口根 View（主窗口 + Popup/Dialog 子窗口） */
    private fun allWindowRoots(fallback: View): List<View> {
        return try {
            val global = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getInstance").invoke(null)
            val views = global.javaClass.getDeclaredField("mViews")
                .apply { isAccessible = true }
                .get(global) as? List<*>
            val list = views?.filterIsInstance<View>()
                ?.filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
                .orEmpty()
            if (list.isEmpty()) listOf(fallback) else list
        } catch (_: Exception) {
            listOf(fallback)
        }
    }

    /**
     * 找到根 View 坐标系下 (x,y) 处的最深可见子 View（供滚轮直达 WebView）。
     * 仅按 left/top/scroll/translation 平移换算（本应用视图树无旋转/缩放）。
     */
    private fun deepestViewAt(root: View, x: Float, y: Float): View? {
        var current: View = root
        var cx = x
        var cy = y
        while (current is ViewGroup) {
            val g = current
            var child: View? = null
            var i = g.childCount - 1
            while (i >= 0) {
                val c = g.getChildAt(i)
                if (c.visibility == View.VISIBLE && c.width > 0 && c.height > 0) {
                    val lx = cx - c.left + g.scrollX - c.translationX
                    val ly = cy - c.top + g.scrollY - c.translationY
                    if (lx >= 0f && ly >= 0f && lx < c.width && ly < c.height) {
                        child = c
                        cx = lx
                        cy = ly
                        break
                    }
                }
                i--
            }
            if (child == null) break
            current = child
        }
        return if (current === root) null else current
    }
}
