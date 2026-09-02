package com.anwind.apps.browser

import android.os.SystemClock
import android.webkit.JavascriptInterface

/**
 * v2.16.2：浏览器"3D 视角旋转"（鼠标视角模式）控制器。
 *
 * ## 功能语义（v2.16.2 重做 —— 取代旧版整页 graphicsLayer 透视旋转）
 * 面向电脑网页游戏：开启后，在网页上【按住并拖动】等价于"按住鼠标移动视角"
 * —— 把手指拖动的像素增量合成为 mousedown / mousemove(movementX/Y) / mouseup
 * 事件注入页面，由游戏自身完成相机/视角旋转（上下拖 = 俯仰，左右拖 = 偏航）。
 * 页面本身不做任何视觉变换。
 *
 * ## 实现结构（低开销双向通道）
 * - Java → 页面：ZoomPinchLayout 在视角模式下拦截单指拖动，把增量喂给
 *   [beginDrag]/[moveDrag]/[endDrag]（UI 线程，只累积不跨 JNI）；
 * - 页面 → Java：引擎在每个页面注入 [LOOK_SETUP_SCRIPT]，脚本用
 *   requestAnimationFrame 循环调用 [Bridge.pull]（@JavascriptInterface 同步
 *   JNI 调用，单帧一次，取走累积的增量字符串），在 JS 侧派发鼠标事件。
 *   相比逐 move 事件 evaluateJavascript（跨 JNI 异步队列，60+/s 会排队堆积，
 *   ZoomPinchLayout/GameBox 的既有教训），该通道每帧固定一次同步调用，
 *   开销恒定且不积压。
 * - 仅【可见页面】的 rAF 会运行（后台标签 rAF 被 Chromium 暂停），
 *   因此增量只会被当前显示的页面取走，多标签/多窗口天然安全。
 *
 * ## JS 侧派发细节
 * - mousedown 在按下点（elementFromPoint 命中元素）触发一次并记住该元素，
 *   后续 mousemove 持续派发到同一元素（桌面"按住拖动"语义，bubbles 冒泡
 *   到 document，兼容挂在 document 上的监听）；
 * - mousemove 携带 movementX/movementY（增量）与递推的 clientX/clientY，
 *   同时兼容两类游戏实现（读 movement 的 pointer-lock 类 / 读 clientX 差值的
 *   拖拽类）；支持 PointerEvent 的页面额外派发 pointerdown/pointermove/
 *   pointerup（pointerType=mouse）；
 * - mouseup 后清空按下元素；一次拖动期间页面不产生任何 touch 事件
 *   （拦截后 WebView 收到 ACTION_CANCEL），不会出现"相机 + 滚动"双响应。
 */
object View3dController {

    /** 鼠标视角模式是否开启（BrowserContent 的 SideEffect 从设置同步） */
    @Volatile
    var enabled: Boolean = false

    /** 视角灵敏度（0.2..3.0）：拖动像素增量倍率 */
    @Volatile
    var sensitivity: Float = 1f

    // ===== 拖动状态（UI 线程写，JS 桥线程读 → 统一锁保护） =====
    private val lock = Any()
    /** 自上次 pull 以来累积的增量（已乘灵敏度） */
    private var accDx = 0f
    private var accDy = 0f
    /** 一次性标记：本帧取走后自动清除 */
    private var pressPending = false
    private var releasePending = false
    /** 按下点（view 坐标，与 GamepadController 的鼠标派发同一坐标系） */
    private var pressX = 0f
    private var pressY = 0f

    /** 视角拖动开始：在按下点派发 mousedown（游戏自此锁定视角控制） */
    fun beginDrag(x: Float, y: Float) {
        synchronized(lock) {
            pressX = x
            pressY = y
            pressPending = true
            releasePending = false
            accDx = 0f
            accDy = 0f
        }
    }

    /** 视角拖动进行中：累积像素增量（乘灵敏度；每帧由页面 rAF 取走） */
    fun moveDrag(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        synchronized(lock) {
            accDx += dx * sensitivity
            accDy += dy * sensitivity
        }
    }

    /** 视角拖动结束：派发 mouseup */
    fun endDrag() {
        synchronized(lock) {
            releasePending = true
            pressPending = false
        }
    }

    /**
     * JS 桥：页面注入脚本每帧调用一次，取走累积的视角增量。
     * 返回格式："dx,dy,press,pressX,pressY,release"（6 字段字符串），
     * 取走即清零（读-清原子）。模式关闭时恒返回全 0，脚本零派发。
     */
    class Bridge {
        @JavascriptInterface
        fun pull(): String {
            // 模式关闭：不再派发任何事件（脚本空转，无事件开销）
            if (!enabled) return "0,0,0,0,0,0"
            return synchronized(View3dController.lock) {
                val s = buildString {
                    append(accDx.toInt());append(',')
                    append(accDy.toInt());append(',')
                    append(if (pressPending) "1" else "0");append(',')
                    append(pressX.toInt());append(',')
                    append(pressY.toInt());append(',')
                    append(if (releasePending) "1" else "0")
                }
                accDx = 0f
                accDy = 0f
                pressPending = false
                releasePending = false
                s
            }
        }
    }

    /** 注册到每个 WebView 的 JS 桥（window.__anwindLookBridge） */
    val bridge = Bridge()

    /**
     * 页面注入脚本（onPageStarted，http/file 页）：启动 rAF 轮询循环。
     * - 幂等（window.__anwindLookLoop 防重复注入）；
     * - 后台标签 rAF 自动暂停 → 只有可见页消费增量；
     * - pull 全 0 时直接进入下一帧，零事件开销。
     */
    val LOOK_SETUP_SCRIPT: String =
        "(function(){try{" +
            "if(window.__anwindLookLoop)return;" +
            "var b=window.__anwindLookBridge;" +
            "if(!b)return;" +
            "var pos=null,el=null,pressed=false;" +
            "function fire(type,mx,my){" +
            "if(!el)return;" +
            "var up=(type==='mouseup');" +
            "var init={bubbles:true,cancelable:true,composed:true,view:window," +
            "clientX:pos[0],clientY:pos[1],screenX:pos[0],screenY:pos[1]," +
            "button:0,buttons:(up?0:1),movementX:mx||0,movementY:my||0,detail:1};" +
            "el.dispatchEvent(new MouseEvent(type,init));" +
            "if(typeof PointerEvent==='function'){" +
            "try{" +
            "var pt=(type==='mousedown')?'pointerdown':(up?'pointerup':'pointermove');" +
            "var p={};for(var k in init)p[k]=init[k];" +
            "p.pointerId=1;p.pointerType='mouse';p.isPrimary=true;" +
            "el.dispatchEvent(new PointerEvent(pt,p));" +
            "}catch(e){}}" +
            "}" +
            "function loop(){" +
            "try{" +
            "var r=b.pull();" +
            "if(r&&r!=='0,0,0,0,0,0'){" +
            "var a=r.split(',');" +
            "var dx=parseFloat(a[0])||0,dy=parseFloat(a[1])||0;" +
            "var press=a[2]==='1',up=a[5]==='1';" +
            "if(press&&!up){" +
            "pos=[parseFloat(a[3])||0,parseFloat(a[4])||0];" +
            "el=document.elementFromPoint(pos[0],pos[1])||document.body||document.documentElement||document;" +
            "pressed=true;fire('mousedown',0,0);" +
            "}" +
            "if(pressed&&pos&&(dx!==0||dy!==0)){" +
            "pos[0]+=dx;pos[1]+=dy;fire('mousemove',dx,dy);" +
            "}" +
            "if(up&&pressed){fire('mouseup',0,0);pressed=false;el=null;pos=null;}" +
            "}" +
            "}catch(e){}" +
            "requestAnimationFrame(loop);" +
            "}" +
            "window.__anwindLookLoop=true;" +
            "requestAnimationFrame(loop);" +
            "}catch(e){}})()"

    /** 页面拉取帧的最小理论间隔（仅用于文档说明；实际由 rAF 驱动） */
    const val FRAME_HINT_MS: Long = 16L

    /** 上次视角事件时间戳（调试/日志用途保留） */
    var lastEventAt: Long = 0L
        private set

    /** 更新最后事件时间（beginDrag/endDrag 调用） */
    internal fun touchEventClock() {
        lastEventAt = SystemClock.uptimeMillis()
    }
}
