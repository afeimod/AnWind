package com.anwind.core.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.anwind.AnWindApp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 注入合成事件使用的指针 id 基线。
 * 真实手指的 pointer id 恒为 0..9；所有合成事件（点击/拖动/双指轻点）
 * 一律使用 id ≥ [INJECTED_POINTER_ID] 的指针 —— 触控板门禁据此区分
 * "真实手指（拦截）" 与 "自己注入的事件（放行给下层窗口）"。
 */
internal const val INJECTED_POINTER_ID = 99

/**
 * v2.19 触控板（Trackpad）指针移动模式 —— 门禁层。
 *
 * ## v2.19 架构修复（v2.18 "操作完全无效"的根因）
 * v2.18 把触控板层做成【全屏兄弟节点】盖在窗口层之上：Compose 命中测试
 * 只沿"最顶层命中链"派发事件，覆盖层一旦存在，其下的窗口/图标全部离开
 * 命中路径 —— 真实触摸被吃掉、注入的合成事件也只回到覆盖层自身被 guard
 * 吞掉，下层什么都收不到。
 *
 * v2.19 改为【祖先门禁】（[TrackpadGate] 包裹壁纸~右键菜单全部桌面内容）：
 * - 门禁是所有内容的祖先 → 任何真实触摸的事件都会经过它（祖先永远在
 *   命中路径上），在 Initial pass（先于全部子节点）消费 → 下层窗口在
 *   Main pass 看到已消费事件，标准手势检测器全部跳过 —— 触控板完全接管；
 * - 注入的合成事件（指针 id ≥ 99）门禁【不消费】→ 同一次派发继续流向
 *   下层窗口/WebView（PointerInterop 桥只挡"被消费"的事件）→ 点击、
 *   拖拽、滚动真实到达目标控件；
 * - 虚拟键盘/手柄/指针/锁屏是门禁的【兄弟】且 z 序更高，命中它们时门禁
 *   不在命中路径上 → 键盘手柄锁屏完全不受影响。
 *
 * ## 手势集（真实手指 → 指针动作）
 * - 单指滑动：指针相对移动（× [TrackpadController.SENSITIVITY]）；
 * - 轻点（< 260ms 且位移 < 24dp）：指针处注入单击（连点两下 = 双击，
 *   由目标控件自行判定）；
 * - 按住不动 ≥ 320ms：进入拖拽 —— 注入 ACTION_DOWN，之后滑动 = 按住
 *   拖拽（拖窗口/拖滑块/划词），抬手注入 ACTION_UP；
 * - 双指滑动：指针处注入滚轮（WebView 优先直达，60px = 1 tick）；
 * - 双指轻点：指针处注入双指轻点 → 桌面手势层弹右键菜单。
 */
@Composable
fun TrackpadGate(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val app = remember { AnWindApp.get() }
    val controlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    val view = LocalView.current

    Box(
        modifier = modifier.then(
            // touch 模式 = 纯容器零开销；trackpad 模式挂上门禁
            if (controlMode == "trackpad") Modifier.trackpadGate(view) else Modifier
        )
    ) {
        content()
    }
}

/**
 * 触控板门禁手势机（Initial pass 消费真实事件 + 相对手势判定 + 合成注入）。
 */
private fun Modifier.trackpadGate(view: View): Modifier = pointerInput(view) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        /** 排空当前手势流（不消费 —— 让注入流完整到达下层窗口） */
        suspend fun drainStream() {
            while (true) {
                val ev = awaitPointerEvent(pass = PointerEventPass.Initial)
                if (ev.type == PointerEventType.Scroll) continue
                if (ev.changes.none { it.pressed }) return
            }
        }

        // 注入流 / 已被更高层消费的流：放行，不参与指针控制
        if (first.id >= INJECTED_POINTER_ID || first.isConsumed) {
            drainStream()
            return@awaitEachGesture
        }

        /** 等真实手指全部抬起（不消费，留给拥有该流的高层） */
        suspend fun drainRealUntilUp() {
            while (true) {
                val ev = awaitPointerEvent(pass = PointerEventPass.Initial)
                if (ev.type == PointerEventType.Scroll) continue
                val real = ev.changes.filter { it.id < INJECTED_POINTER_ID }
                if (real.isNotEmpty() && real.none { it.pressed }) return
            }
        }

        val tapSlopPx = 24.dp.toPx()
        val scrollSlopPx = 16.dp.toPx()
        val longPressMs = 320L
        val startMs = first.uptimeMillis
        val startPos = first.position
        var lastPos = first.position
        var lastPointerId = first.id
        var lastCentroid = first.position
        var maxPointers = 1
        var scrollMode = false
        var dragMode = false
        var gestureEnded = false

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

        // ===== 阶段 A：按下后 longPressMs 内判定意图 =====
        // null = 静止超时（→ 拖拽）；1 = 移动；2 = 双指；3 = 已抬手（待判轻点）；-1 = 被高层接管
        val decision: Int? = withTimeoutOrNull(longPressMs) {
            var result = 1
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) continue
                val real = event.changes.filter { it.id < INJECTED_POINTER_ID }
                if (real.isEmpty()) continue
                if (real.any { it.isConsumed }) {
                    result = -1
                    return@withTimeoutOrNull result
                }
                real.forEach { it.consume() }
                val pressed = real.count { it.pressed }
                if (pressed > maxPointers) maxPointers = pressed
                if (pressed == 0) {
                    result = 3
                    return@withTimeoutOrNull result
                }
                if (pressed >= 2) {
                    result = 2
                    return@withTimeoutOrNull result
                }
                val change = real.first { it.pressed }
                if (change.id != lastPointerId) {
                    // 指数切换：重置基线不跳变
                    lastPointerId = change.id
                    lastPos = change.position
                    continue
                }
                val delta = change.position - lastPos
                lastPos = change.position
                moveCursorBy(delta)
                if ((change.position - startPos).getDistance() > tapSlopPx) {
                    result = 1
                    return@withTimeoutOrNull result
                }
            }
            @Suppress("UNREACHABLE_CODE")
            result
        }

        /** 手势收尾：按累计状态判定轻点/双指轻点，并处理拖拽释放 */
        fun finishGesture() {
            val duration = SystemClock.uptimeMillis() - startMs
            val moveDist = (lastPos - startPos).getDistance()
            val cursor = MouseController.position
            if (dragMode) {
                MouseController.press(false)
                TrackpadController.injectDragUp(view, cursor.x, cursor.y)
            } else if (maxPointers == 1 && duration < 260L && moveDist < tapSlopPx) {
                TrackpadController.tapClick(view, cursor.x, cursor.y)
            } else if (maxPointers == 2 && !scrollMode && duration < 450L && moveDist < tapSlopPx) {
                TrackpadController.injectTwoFingerTap(view, cursor.x, cursor.y)
            }
        }

        when (decision) {
            -1 -> {
                drainRealUntilUp()
                gestureEnded = true
            }
            3 -> {
                finishGesture()
                gestureEnded = true
            }
            null -> {
                // 静止超时 → 进入拖拽：视觉按压 + 注入 ACTION_DOWN
                dragMode = true
                MouseController.press(true)
                val cursor = MouseController.position
                TrackpadController.injectDragDown(view, cursor.x, cursor.y)
            }
            // 1（移动）/ 2（双指）→ 进入阶段 B 主循环
        }

        if (!gestureEnded) {
            while (true) {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll) continue
                val real = event.changes.filter { it.id < INJECTED_POINTER_ID }
                if (real.isEmpty()) continue
                if (real.any { it.isConsumed }) {
                    // 键盘/手柄等更高层接管：先释放可能按下的拖拽再排空
                    if (dragMode) {
                        MouseController.press(false)
                        val cursor = MouseController.position
                        TrackpadController.injectDragUp(view, cursor.x, cursor.y)
                    }
                    drainRealUntilUp()
                    break
                }
                real.forEach { it.consume() }
                val pressed = real.count { it.pressed }
                if (pressed > maxPointers) maxPointers = pressed

                if (pressed == 0) {
                    finishGesture()
                    break
                }

                if (pressed >= 2) {
                    // ===== 双指：滑动 = 滚轮；静止短促 = 待抬手判右键 =====
                    val pts = real.filter { it.pressed }
                    if (pts.isNotEmpty()) {
                        val cx = pts.sumOf { it.position.x.toDouble() }.toFloat() / pts.size
                        val cy = pts.sumOf { it.position.y.toDouble() }.toFloat() / pts.size
                        val centroid = Offset(cx, cy)
                        val d = centroid - lastCentroid
                        if (!scrollMode && d.getDistance() > scrollSlopPx) {
                            scrollMode = true
                            lastCentroid = centroid
                        } else if (scrollMode) {
                            val cursor = MouseController.position
                            TrackpadController.injectScroll(view, cursor.x, cursor.y, d.x, d.y)
                            lastCentroid = centroid
                        }
                    }
                } else {
                    // ===== 单指：移动指针；拖拽态同时注入 MOVE =====
                    val change = real.first { it.pressed }
                    if (change.id != lastPointerId) {
                        lastPointerId = change.id
                        lastPos = change.position
                        continue
                    }
                    val delta = change.position - lastPos
                    lastPos = change.position
                    moveCursorBy(delta)
                    if (dragMode) {
                        val cursor = MouseController.position
                        TrackpadController.injectDragMove(view, cursor.x, cursor.y)
                    }
                }
            }
        }
    }
}

/**
 * 触控板注入器：把指针动作合成为真实 MotionEvent / 滚轮事件派发给 Compose 根 View。
 * 所有触摸型注入使用 id ≥ [INJECTED_POINTER_ID] 的合成指针（门禁据此放行）。
 */
object TrackpadController {

    /** 指针移动灵敏度（手指像素 → 指针像素倍率） */
    const val SENSITIVITY = 1.6f

    /**
     * 注入保护时间戳（uptimeMillis）：该时刻之前开始的手势流视为注入流，
     * 直接忽略。0 = 无保护。仅主线程读写。
     */
    @Volatile
    var injectGuardUntil: Long = 0L

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

    /** 在指定 View 上注入一次完整点击（DOWN → UP，间隔 60ms 事件时间） */
    fun injectClick(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        injectGuardUntil = now + 200L
        try {
            dispatchTouch(view, MotionEvent.ACTION_DOWN, now, 0L, x, y)
            dispatchTouch(view, MotionEvent.ACTION_UP, now, 60L, x, y)
        } catch (_: Exception) {
        } finally {
            // 事件时间上的"流"在 now+60 结束，多留余量
            injectGuardUntil = SystemClock.uptimeMillis() + 120L
        }
    }

    // ===== 拖拽流：DOWN（长按超时）→ MOVE…（跟随指针）→ UP（抬手） =====
    private var dragDownTime = 0L

    fun injectDragDown(view: View, x: Float, y: Float) {
        dragDownTime = SystemClock.uptimeMillis()
        injectGuardUntil = dragDownTime + 250L
        runCatching { dispatchTouch(view, MotionEvent.ACTION_DOWN, dragDownTime, 0L, x, y) }
    }

    fun injectDragMove(view: View, x: Float, y: Float) {
        if (dragDownTime == 0L) return
        runCatching {
            dispatchTouch(view, MotionEvent.ACTION_MOVE, dragDownTime, SystemClock.uptimeMillis() - dragDownTime, x, y)
        }
    }

    fun injectDragUp(view: View, x: Float, y: Float) {
        if (dragDownTime == 0L) return
        runCatching {
            dispatchTouch(view, MotionEvent.ACTION_UP, dragDownTime, SystemClock.uptimeMillis() - dragDownTime, x, y)
        }
        dragDownTime = 0L
        injectGuardUntil = SystemClock.uptimeMillis() + 120L
    }

    /**
     * 注入一次双指轻点（DOWN → POINTER_DOWN → POINTER_UP → UP），
     * 两指关于指针位置对称，质心即指针位置 —— 桌面手势层
     * （desktopGestures 的 onTwoFingerTap）据此在指针处弹出右键菜单。
     */
    fun injectTwoFingerTap(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        injectGuardUntil = now + 300L
        val p0 = MotionEvent.PointerProperties().apply {
            id = INJECTED_POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val p1 = MotionEvent.PointerProperties().apply {
            id = INJECTED_POINTER_ID + 1
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        fun coords(px: Float, py: Float) = MotionEvent.PointerCoords().apply {
            // this. 前缀必须显式：外层函数参数 x/y 会遮蔽接收者同名成员
            this.x = px; this.y = py; this.pressure = 1f; this.size = 1f
        }
        val c0 = coords(x - 16f, y)
        val c1 = coords(x + 16f, y)
        val idx1 = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        try {
            dispatchTouchAt(
                view, multiTouch(now, now, MotionEvent.ACTION_DOWN, arrayOf(p0), arrayOf(c0)), x, y
            )
            dispatchTouchAt(
                view,
                multiTouch(now, now + 40L, MotionEvent.ACTION_POINTER_DOWN or idx1, arrayOf(p0, p1), arrayOf(c0, c1)),
                x, y
            )
            dispatchTouchAt(
                view,
                multiTouch(now, now + 90L, MotionEvent.ACTION_POINTER_UP or idx1, arrayOf(p0, p1), arrayOf(c0, c1)),
                x, y
            )
            dispatchTouchAt(
                view, multiTouch(now, now + 130L, MotionEvent.ACTION_UP, arrayOf(p0), arrayOf(c0)), x, y
            )
        } catch (_: Exception) {
        } finally {
            injectGuardUntil = SystemClock.uptimeMillis() + 120L
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
        injectGuardUntil = now + 120L
        val p = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val c = MotionEvent.PointerCoords().apply {
            // this. 前缀必须显式：函数参数 x/y 会遮蔽接收者同名成员
            this.x = x; this.y = y; this.pressure = 1f; this.size = 1f
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / 60f)
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy / 60f)
        }
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
        } finally {
            injectGuardUntil = SystemClock.uptimeMillis() + 80L
        }
    }

    /** 单指触摸注入（合成指针 id = INJECTED_POINTER_ID） */
    private fun dispatchTouch(view: View, action: Int, downTime: Long, eventDelta: Long, x: Float, y: Float) {
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = INJECTED_POINTER_ID
                toolType = MotionEvent.TOOL_TYPE_FINGER
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
                    event.offsetLocation(lx - event.x, ly - event.y)
                    root.dispatchTouchEvent(event)
                    return
                }
                i--
            }
        } catch (_: Exception) {
        }
        view.dispatchTouchEvent(event)
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

    /** 多指触摸注入（PointerProperties 14 参重载） */
    private fun multiTouch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        props: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>
    ): MotionEvent = MotionEvent.obtain(
        downTime, eventTime, action, props.size, props, coords,
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
    )

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
