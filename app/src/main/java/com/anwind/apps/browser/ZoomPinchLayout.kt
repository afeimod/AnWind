package com.anwind.apps.browser

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v2.14.7：双指缩放仲裁容器 —— 弥补 Chromium 无法缩小到 100% 以下的引擎级限制。
 *
 * ## 方案演进（为什么是 JS body.zoom）
 * - v2.14.5 宽画布 viewport：破坏默认排版（用户否决）；
 * - v2.14.6 WebView View 变换（scaleX/Y）：实测【内容不跟随缩放、仅被缩小后
 *   的边界裁切】（用户截图证实：视口缩了、四周露深灰边，页面内容仍按原尺寸
 *   渲染被切掉右/下）。原因：Chromium 合成层不随 View 变换矩阵缩放；
 *   加 LAYER_TYPE_HARDWARE 强制离屏层虽可让变换生效，但 AnWind v2.8 已
 *   录屏证实该配置在 Compose 多窗口嵌套下会"页面加载完成但永不绘制"
 *   （灰屏），v2.14.3 已撤销 —— View 变换这条路两头都是死路；
 * - v2.14.7（GameBox applyPageZoom 同源方案）：缩小域改为 JS 注入
 *   document.body.style.zoom —— Chromium 原生整页 layout 缩放，对带
 *   viewport meta 的 4399 页面也生效，立即生效无需 reload（GameBox 注：
 *   "WebKit 私有 API，Chromium WebView 支持，终极方案"）。默认 100% 时
 *   不注入任何东西，默认排版/缩放与 v2.14.4 完全一致。
 *
 * ## 两段式缩放域
 * - 【100% 以上】放大域：原生 Chromium 双指捏合（排版重排/文字回流），
 *   本类完全不干预，手势透传给 WebView；
 * - 【30% ~ 100%】缩小域：本类拦截手势流，把指距增量映射为 body.zoom
 *   值并节流注入。页面所有元素（含 position:fixed 游戏全屏层、canvas、
 *   ruffle 播放器）等比缩小，命中测试/滚动由 Chromium 原生处理。
 *
 * ## 手势仲裁（onInterceptTouchEvent）——与 v2.14.6 相同的已验证逻辑
 * 双指落下后先【观察】不拦截（第二指落下瞬间不抢占，避免断流缩小态下
 * 运行的双拇指游戏）：
 * - 100% 态、指距拉大（放大意图）→ 永久放行本次手势，原生捏合处理；
 * - 100% 态、指距缩小且页面 scale 仍在变化 → 原生正在缩小（固定宽画布
 *   页地板<100% 的场景），放行；监测的是【最近】响应 —— 原生从 250% 缩到
 *   自身地板后 scale 冻结，同一次手势内无缝接管进缩小域；
 * - 100% 态、指距缩小 ≥15% 且页面 scale 连续 2 帧无变化（原生已在地板上
 *   无法响应）→ 拦截接管，进入 body.zoom 缩小域；
 * - 已处于缩小域（viewZoom<1）时，指距任一方向实质变化（≥5%）即接管
 *   （张开回 100%，收缩到 30%）；静止双指不接管（游戏不受扰）。
 * 单指点击/滚动/长按全程不拦截，原样透传。
 *
 * ## 注入节流
 * evaluateJavascript 是跨 JNI 异步调用，逐帧注入（60+/s）会排队堆积
 * （GameBox 在 mousemove 分发上的教训）。捏合过程中按【间隔 ≥80ms 且
 * 增量 ≥0.02】节流；手势结束/换绑恢复时强制精确应用一次。
 *
 * ## 附带行为
 * - 缩放状态存于 [BrowserTab.viewZoom]：切标签恢复（重绑后重注入 zoom）、
 *   导航（onPageStarted）重置回 100%（新文档天然无 zoom，同文档锚点
 *   导航由引擎的 reset 脚本清除）；
 * - 缩放值域 [MIN_ZOOM=0.3, 1.0]，达 100% 时移除 zoom 样式（非设为
 *   100%，避免残留内联样式影响页面自身布局）。
 */
class ZoomPinchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** 缩小地板（用户需求：支持缩到 30%） */
    var minViewZoom: Float = MIN_ZOOM

    /** 缩放变化回调（同步写回 BrowserTab.viewZoom） */
    var onZoomChanged: ((Float) -> Unit)? = null

    private var webView: WebView? = null

    // ===== 手势状态机 =====
    private var mode = MODE_IDLE
    /** 本次手势是否已判定为原生域（放大/原生可响应的缩小） */
    private var passThrough = false
    /** 观察期基线指距 */
    private var baseSpan = 0f
    /** 观察域：true = 已处于 body.zoom 缩小域（viewZoom<1），指距任一方向
     *  实质变化即接管（双向）；false = 100% 态，缩小方向才可能接管 */
    private var observeViewDomain = false
    /** 最近一次采样的页面 scale（判断原生是否仍在响应） */
    private var lastScale = 0f
    /** 指距缩小超阈值且页面 scale 连续无变化的采样数 */
    private var deadSamples = 0
    /** 接管瞬间的指距与缩放（增量计算基准） */
    private var seedSpan = 0f
    private var seedZoom = 1f
    /** 指数集变化（PINCH 中途抬指/加指）后待重置基准 */
    private var rebasePending = false

    /** 当前缩放比例（与 tab.viewZoom 同步） */
    private var viewZoom = 1f

    // ===== JS 注入节流状态 =====
    /** 上次实际注入 body.zoom 的时间戳（uptimeMillis） */
    private var lastJsApplyAt = 0L
    /** 上次实际注入的 zoom 值 */
    private var lastJsZoom = 1f

    /**
     * 绑定（或换绑）本容器承载的 WebView。重复调用安全：
     * 换绑时先把 WebView 从旧容器剥离（弹窗临时挂载/标签切换认领场景）。
     * restoreZoom 同步到内部状态（手势仲裁据此判断当前域），<100% 时
     * 立即重注入 body.zoom（切标签恢复缩放态）。
     */
    fun setWebView(target: WebView?, restoreZoom: Float = 1f) {
        viewZoom = restoreZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (webView === target) {
            // 同绑定：AndroidView 的 update 块每次重组都会走到这里，
            // 仅在缩放值确有变化时才注入（如导航重置 0.5→1），避免
            // 无意义的 JNI 调用churn
            if (target != null && abs(viewZoom - lastJsZoom) > 0.001f) {
                applyZoom(target, viewZoom, force = true)
            }
            return
        }
        webView?.let { old ->
            if (old.parent === this) removeView(old)
        }
        webView = target
        if (target != null) {
            (target.parent as? ViewGroup)?.removeView(target)
            addView(
                target,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            applyZoom(target, viewZoom, force = true)
        }
    }

    /** 组合销毁（onRelease）时解除引用，防泄漏；WebView 生命周期由引擎管理 */
    fun detachWebView() {
        webView = null
    }

    /** 当前缩放比例（供恢复/展示用） */
    fun currentZoom(): Float = viewZoom

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = MODE_IDLE
                passThrough = false
                deadSamples = 0
                baseSpan = 0f
                lastScale = 0f
                observeViewDomain = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    if (passThrough) return false
                    // 不在第二指落下瞬间拦截 —— 缩小态下运行的双拇指游戏
                    // （两指静止/同步移动）不应被断流；观察指距实际变化后再接管
                    mode = MODE_OBSERVE
                    observeViewDomain = viewZoom < 1f
                    baseSpan = spanOf(ev)
                    lastScale = webView?.scale ?: 0f
                    deadSamples = 0
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == MODE_OBSERVE && ev.pointerCount == 2 && !passThrough) {
                    val span = spanOf(ev)
                    if (observeViewDomain) {
                        // 已在缩小域：指距任一方向实质变化即接管（放大向回
                        // 100%，缩小向 30%），原生域不参与 —— 避免"页面
                        // scale × body.zoom"双系统叠加的混乱态
                        if (span < baseSpan * 0.95f || span > baseSpan * 1.05f) {
                            return beginPinch(ev)
                        }
                        return false
                    }
                    val cur = webView?.scale ?: 0f
                    // 页面 scale 是否仍在变化（原生捏合在响应中）。
                    // 监控"最近"而非"手势开始以来"的响应：原生从 250% 缩到
                    // 100% 地板后 scale 冻结，同一次手势可无缝接管进 30%。
                    val scaleMoved =
                        lastScale > 0f && cur > 0f && abs(cur - lastScale) > lastScale * 0.002f
                    lastScale = cur
                    if (span > baseSpan * 1.05f) {
                        // 放大意图 → 原生捏合域，本次手势不再介入
                        passThrough = true
                        mode = MODE_IDLE
                    } else if (span < baseSpan * 0.85f) {
                        if (scaleMoved) {
                            // 固定宽画布页（地板<100%）：原生正在缩小，放行
                            deadSamples = 0
                        } else {
                            deadSamples++
                            // 连续 2 帧指距缩小而页面 scale 纹丝不动
                            // = 原生已到地板 → 接管进入缩小域
                            if (deadSamples >= 2) return beginPinch(ev)
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = ev.pointerCount - 1
                if (remaining < 2) {
                    mode = MODE_IDLE
                } else if (mode == MODE_OBSERVE) {
                    // 指数变化（3 指余 2）：重基线，不丢失后续合法捏合
                    baseSpan = spanOf(ev)
                    lastScale = webView?.scale ?: 0f
                    deadSamples = 0
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == MODE_PINCH) {
                    // 手势结束：强制精确应用最终 zoom 值（过程值是节流的）
                    finishPinch()
                }
                mode = MODE_IDLE
                passThrough = false
                deadSamples = 0
                observeViewDomain = false
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // 拦截接管后才收到事件流（WebView 满铺为子 View，单指事件永远命中
        // WebView；此处只需处理 MODE_PINCH 态的捏合流）
        if (mode != MODE_PINCH) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    if (rebasePending) {
                        // 指数集变化后的首个 MOVE：重置增量基准，防止
                        //（新/旧）指数集的 span 跳变引起缩放跳变
                        rebasePending = false
                        seedSpan = spanOf(ev)
                        seedZoom = viewZoom
                    } else if (seedSpan > 0f) {
                        setZoom(seedZoom * (spanOf(ev) / seedSpan))
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // PINCH 中途加指：下一帧重置基准
                rebasePending = true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount - 1 < 2) {
                    // 抬指后不足双指：手势实质结束，精确应用最终值
                    finishPinch()
                    mode = MODE_IDLE
                } else {
                    // 三指抬一指后捏合继续：下一帧重置基准
                    rebasePending = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishPinch()
                mode = MODE_IDLE
                rebasePending = false
            }
        }
        return true
    }

    /** 从观察/空闲态切入缩小域（此刻 WebView 收到 ACTION_CANCEL） */
    private fun beginPinch(ev: MotionEvent): Boolean {
        mode = MODE_PINCH
        seedSpan = spanOf(ev)
        seedZoom = viewZoom
        rebasePending = false
        // 接管即应用一次（消除从原生地板到缩小域第一跳的延迟感）
        lastJsApplyAt = 0L
        return true
    }

    /** 手势结束：强制精确注入最终 zoom（过程值经过节流） */
    private fun finishPinch() {
        webView?.let { wv ->
            applyZoom(wv, viewZoom, force = true)
            onZoomChanged?.invoke(viewZoom)
        }
    }

    /** 捏合过程中设定目标缩放（节流注入 body.zoom 并回调写回标签） */
    private fun setZoom(zoom: Float) {
        viewZoom = zoom.coerceIn(minViewZoom, 1f)
        val now = android.os.SystemClock.uptimeMillis()
        val settled = abs(viewZoom - lastJsZoom) >= 0.02f
        if (now - lastJsApplyAt >= JS_INTERVAL_MS && settled) {
            webView?.let { wv ->
                applyZoom(wv, viewZoom, force = false)
                onZoomChanged?.invoke(viewZoom)
            }
        }
    }

    /** 任意两指间最大距离（span） */
    private fun spanOf(ev: MotionEvent): Float {
        val n = ev.pointerCount
        if (n < 2) return 0f
        var max = 0f
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = ev.getX(i) - ev.getX(j)
                val dy = ev.getY(i) - ev.getY(j)
                val d = sqrt(dx * dx + dy * dy)
                if (d > max) max = d
            }
        }
        return if (max > 1f) max else 1f
    }

    /**
     * 把缩放注入到页面（body.style.zoom）。force=true 精确注入当前值
     * （手势结束/换绑恢复）；否则受 [JS_INTERVAL_MS] 节流。
     * 100% 时移除 zoom 内联样式（清干净，不留残余影响页面自身布局）。
     */
    private fun applyZoom(wv: WebView, zoom: Float, force: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!force) {
            if (now - lastJsApplyAt < JS_INTERVAL_MS) return
            if (abs(zoom - lastJsZoom) < 0.02f) return
        }
        lastJsApplyAt = now
        lastJsZoom = zoom
        wv.evaluateJavascript(zoomScript(zoom), null)
    }

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 1.0f

        /** body.zoom 注入最小间隔（ms）：防 JNI 排队堆积 */
        private const val JS_INTERVAL_MS = 80L

        /** 手势状态机 */
        private const val MODE_IDLE = 0        // 无双指手势
        private const val MODE_OBSERVE = 1     // 双指观察中（未拦截，原生优先）
        private const val MODE_PINCH = 2       // 已拦截，缩小域接管中

        /**
         * body.zoom 注入脚本（静态入口：引擎导航重置也用 [RESET_SCRIPT]）。
         * GameBox applyPageZoom 同源方案：Chromium 原生整页 layout 缩放，
         * 命中测试/滚动/固定层全部原生正确，带 viewport meta 页面亦生效。
         */
        fun zoomScript(zoom: Float): String {
            return if (zoom >= 0.999f) RESET_SCRIPT
            else "(function(){try{var b=document.body;if(b)b.style.zoom='$zoom';" +
                "else if(document.documentElement)document.documentElement.style.zoom='$zoom';" +
                "}catch(e){}})()"
        }

        /** 重置缩放脚本：移除 zoom 内联样式（引擎 onPageStarted 导航重置用） */
        const val RESET_SCRIPT: String =
            "(function(){try{if(document.body)document.body.style.zoom='';" +
                "if(document.documentElement)document.documentElement.style.zoom='';}catch(e){}})()"
    }
}
