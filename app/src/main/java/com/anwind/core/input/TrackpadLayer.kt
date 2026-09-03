package com.anwind.core.input

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.anwind.AnWindApp
import kotlin.math.abs

/**
 * v2.18 触控板（Trackpad）指针移动模式。
 *
 * ## 与 touch 模式的区别
 * - touch（默认）：指针贴着手指移动，手指直接点按 UI；
 * - trackpad：整个屏幕当作笔记本电脑触控板 —— 手指在"板面"上滑动，
 *   指针按相对增量移动（灵敏度放大），轻点"板面"= 在指针位置注入一次
 *   真实点击（连点两下 = 双击，交给目标控件的双击检测判定），
 *   双指轻点 = 在指针位置注入双指轻点（触发桌面右键菜单），
 *   双指滑动 = 在指针位置注入滚轮事件（滚动窗口/列表/网页）。
 *
 * ## 拦截与注入
 * - 覆盖层位于窗口/任务栏/开始菜单之上、虚拟键盘/手柄/指针/锁屏之下：
 *   键盘与手柄 z 序更高，Main pass 先处理并消费自己的事件，本层跳过
 *   已消费事件，二者不受影响；
 * - 本层消费所有真实手指事件（下层 UI 不再直接响应触摸），
 *   一切操作经由指针完成 —— 与真实触控板语义一致；
 * - 点击/滚动通过向 Compose 根 View 注入合成 MotionEvent 实现，
 *   注入期间设置 [injectGuardUntil] 时间戳，本层对注入事件造成的手势流
 *   跳过处理（防反馈死循环：注入的 tap 不会再被当成手指轻点而二次注入）。
 *
 * ## 开关联动（SettingsStore.setMouseControlMode）
 * 切到 trackpad 时自动落地"双击打开 + 双指右键"（用户约定默认习惯）；
 * 切回 touch 时恢复"单击打开"。
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

    /** 在指定 View 上注入一次完整点击（DOWN → UP，间隔 60ms 事件时间） */
    fun injectClick(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        injectGuardUntil = now + 200L
        try {
            view.dispatchTouchEvent(
                MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            )
            view.dispatchTouchEvent(
                MotionEvent.obtain(now, now + 60L, MotionEvent.ACTION_UP, x, y, 0)
            )
        } catch (_: Exception) {
        } finally {
            // 事件时间上的"流"在 now+60 结束，多留余量
            injectGuardUntil = SystemClock.uptimeMillis() + 120L
        }
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
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val p1 = MotionEvent.PointerProperties().apply {
            id = 1
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        fun coords(px: Float, py: Float) = MotionEvent.PointerCoords().apply {
            x = px; y = py; pressure = 1f; size = 1f
        }
        val c0 = coords(x - 16f, y)
        val c1 = coords(x + 16f, y)
        val idx1 = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        try {
            view.dispatchTouchEvent(
                MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1, arrayOf(p0), arrayOf(c0), 0, 0, 1f, 1f, 0, 0)
            )
            view.dispatchTouchEvent(
                MotionEvent.obtain(now, now + 40L, MotionEvent.ACTION_POINTER_DOWN or idx1, 2, arrayOf(p0, p1), arrayOf(c0, c1), 0, 0, 1f, 1f, 0, 0)
            )
            view.dispatchTouchEvent(
                MotionEvent.obtain(now, now + 90L, MotionEvent.ACTION_POINTER_UP or idx1, 2, arrayOf(p0, p1), arrayOf(c0, c1), 0, 0, 1f, 1f, 0, 0)
            )
            view.dispatchTouchEvent(
                MotionEvent.obtain(now, now + 130L, MotionEvent.ACTION_UP, 1, arrayOf(p0), arrayOf(c0), 0, 0, 1f, 1f, 0, 0)
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
            x = x; y = y; pressure = 1f; size = 1f
            setAxis(MotionEvent.AXIS_HSCROLL, dx / 60f)
            setAxis(MotionEvent.AXIS_VSCROLL, dy / 60f)
        }
        try {
            val ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_SCROLL,
                1, arrayOf(p), arrayOf(c),
                0, 0, 1f, 1f, 0, 0
            )
            // 滚轮属通用（generic）motion 事件：Compose 滚动组件与 WebView
            // 均按鼠标滚轮路径响应
            view.dispatchGenericMotionEvent(ev)
        } catch (_: Exception) {
        } finally {
            injectGuardUntil = SystemClock.uptimeMillis() + 80L
        }
    }
}

/**
 * 触控板输入覆盖层（DesktopEnvironment 挂载，位于窗口层之上、
 * 虚拟键盘/手柄/指针/锁屏之下）。
 *
 * touch 模式不渲染（零开销）；trackpad 模式拦截全部真实触摸并：
 * - 单指滑动 → 指针相对移动（× [TrackpadController.SENSITIVITY]）；
 * - 单指轻点 → 指针处注入单击（快速连点两下 = 双击）；
 * - 双指轻点 → 指针处注入双指轻点（右键菜单）；
 * - 双指滑动 → 指针处注入滚轮（滚动网页/列表）。
 */
@Composable
fun TrackpadInputOverlay() {
    val app = remember { AnWindApp.get() }
    val controlMode by app.settingsStore.mouseControlMode.collectAsState(initial = "touch")
    if (controlMode != "trackpad") return

    val view = LocalView.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(view)
            {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val tapSlopPx = 24.dp.toPx()
                    val scrollSlopPx = 16.dp.toPx()

                    // 更高层（虚拟键盘/手柄/锁屏）已消费本次按下 → 该手指由它们
                    // 处理，本层不移动指针、不注入点击
                    if (first.isConsumed) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }

                    // ===== 注入流保护：由 injectClick/injectTwoFingerTap 造成的
                    // 合成手势流直接吞掉，防止"注入 tap → 误判轻点 → 再注入"死循环
                    if (first.uptimeMillis <= TrackpadController.injectGuardUntil) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }

                    var lastPos = first.position
                    var lastPointerId = first.id
                    var lastCentroid = first.position
                    var maxPointers = 1
                    var scrollMode = false
                    val startMs = first.uptimeMillis
                    val startPos = first.position

                    while (true) {
                        val event = awaitPointerEvent()
                        // 键盘/手柄/锁屏等更高层已接管（消费）本事件 → 放弃本流
                        if (event.changes.any { it.isConsumed }) {
                            // 等手指全部抬起，避免残留半截流
                            while (true) {
                                val tail = awaitPointerEvent()
                                if (tail.changes.none { it.pressed }) break
                            }
                            break
                        }

                        val pressed = event.changes.count { it.pressed }
                        if (pressed > maxPointers) maxPointers = pressed

                        if (pressed == 0) {
                            // ===== 手势结束：判定轻点 / 双指轻点 =====
                            val end = event.changes.maxOf { it.uptimeMillis }
                            val duration = end - startMs
                            val moveDist = (event.changes
                                .firstOrNull { it.id == first.id }
                                ?.position ?: startPos) - startPos
                            val smallMove = moveDist.getDistance() < tapSlopPx
                            val cursor = MouseController.position
                            if (maxPointers == 1 && duration < 260L && smallMove) {
                                // 单指轻点 → 指针处单击（视觉按压 + 涟漪）
                                TrackpadController.pressing = true
                                TrackpadController.injectClick(view, cursor.x, cursor.y)
                                TrackpadController.pressing = false
                            } else if (maxPointers == 2 && !scrollMode &&
                                duration < 450L && smallMove
                            ) {
                                // 双指轻点 → 指针处右键（桌面手势层弹菜单）
                                TrackpadController.injectTwoFingerTap(view, cursor.x, cursor.y)
                            }
                            break
                        }

                        if (pressed >= 2) {
                            // ===== 双指：滑动 = 滚轮；静止短促 = 待抬手判右键 =====
                            val pts = event.changes.filter { it.pressed }
                            if (pts.isNotEmpty()) {
                                val cx = pts.sumOf { it.position.x.toDouble() }.toFloat() / pts.size
                                val cy = pts.sumOf { it.position.y.toDouble() }.toFloat() / pts.size
                                val centroid = androidx.compose.ui.geometry.Offset(cx, cy)
                                val d = centroid - lastCentroid
                                if (!scrollMode && d.getDistance() > scrollSlopPx) {
                                    scrollMode = true
                                    lastCentroid = centroid
                                } else if (scrollMode) {
                                    val cursor = MouseController.position
                                    TrackpadController.injectScroll(
                                        view, cursor.x, cursor.y, d.x, d.y
                                    )
                                    lastCentroid = centroid
                                }
                            }
                        } else {
                            // ===== 单指：相对移动指针 =====
                            val change = event.changes.firstOrNull { it.pressed } ?: continue
                            if (change.id != lastPointerId) {
                                // 指数切换（滚动后抬一指等）：重置基线不跳变
                                lastPointerId = change.id
                                lastPos = change.position
                                continue
                            }
                            val delta = change.position - lastPos
                            lastPos = change.position
                            if (delta.getDistance() > 0.01f) {
                                val cur = MouseController.position
                                val nx = (cur.x + delta.x * TrackpadController.SENSITIVITY)
                                    .coerceIn(0f, size.width.toFloat())
                                val ny = (cur.y + delta.y * TrackpadController.SENSITIVITY)
                                    .coerceIn(0f, size.height.toFloat())
                                MouseController.update(nx, ny)
                            }
                        }
                    }
                }
            }
    )
}
