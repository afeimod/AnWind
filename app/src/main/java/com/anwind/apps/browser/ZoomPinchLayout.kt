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
 * v2.14.6：双指缩放仲裁容器 —— 弥补 Chromium 无法缩小到 100% 以下的引擎级限制。
 *
 * ## 背景（为什么需要 View 级缩放）
 * Chromium 页面最小缩放 = max(meta minimum-scale, 窗口宽 / 布局画布宽)。
 * 画布宽 = viewport width = 页面排版宽度 —— 二者天然是同一个值，所以
 * "把页面缩小到 100% 以下"与"保持排版不变"在 viewport 层面【物理冲突】：
 * - v2.14.4 及之前：width=device-width（画布=窗口宽）→ 地板恒 100%，
 *   双指只能放大（用户主诉"不能缩小"）；
 * - v2.14.5：注入宽画布（窗口宽/0.3）→ 地板可达 30%，但页面按超宽画布
 *   排版 + 初始即全览 30% → 排版被破坏（4399 首页整页压扁、内容过小）。
 *
 * ## 方案（两段式缩放域）
 * - 【100% ~ 1000%】放大域：原生 Chromium 双指捏合（排版重排/文字回流），
 *   本类完全不干预，手势透传给 WebView；
 * - 【30% ~ 100%】缩小域：本类对 WebView 施加 View 变换（scaleX/Y + 居中
 *   平移）。只做缩小方向 → 缩小后整页完全可见、无任何裁切、无平移手势
 *   冲突；渲染为原尺寸超采样缩小，始终清晰。
 *
 * ## 手势仲裁（onInterceptTouchEvent）
 * 双指落下后先【观察】不拦截（第二指落下瞬间不抢占，避免断流缩小态下
 * 运行的双拇指游戏）：
 * - 100% 态、指距拉大（放大意图）→ 永久放行本次手势，原生捏合处理；
 * - 100% 态、指距缩小且页面 scale 仍在变化 → 原生正在缩小（固定宽画布
 *   页地板<100% 的场景），放行；监测的是【最近】响应 —— 原生从 250% 缩到
 *   自身地板后 scale 冻结，同一次手势内无缝接管进缩小域；
 * - 100% 态、指距缩小 ≥15% 且页面 scale 连续 2 帧无变化（原生已在地板上
 *   无法响应）→ 拦截接管，进入 View 缩放域；
 * - 已处于 View 缩小域（viewZoom<1）时，指距任一方向实质变化（≥5%）即
 *   接管（张开回 100%，收缩到 30%）；静止双指不接管（游戏不受扰）。
 * 单指点击/滚动/长按全程不拦截，原样透传（缩小态下 Android 框架自动把
 * 触点逆变换到页面坐标，点击/滚动位置精确）。
 *
 * ## 附带行为
 * - 缩小态的 WebView 居中显示，四周露出深灰边（Chrome 缩小态同款视觉）；
 * - 缩放状态存于 [BrowserTab.viewZoom]：切标签恢复、导航（onPageStarted）
 *   重置回 100%；
 * - 窗口尺寸变化（AnWind 窗口可拖拽缩放）时重算 pivot/平移量。
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
    // （v2.14.6 CI 修复：Kotlin 每个类仅允许一个 companion object。
    //  MODE_* 常量已并入文末 companion object，私有可见性不变）

    private var mode = MODE_IDLE
    /** 本次手势是否已判定为原生域（放大/原生可响应的缩小） */
    private var passThrough = false
    /** 观察期基线指距 */
    private var baseSpan = 0f
    /** 观察域：true = 已处于 View 缩小域（viewZoom<1），指距住任一方向
     *  实质变化即接管（双向）；false = 100% 态，缩小方向才可能接管 */
    private var observeViewDomain = false
    /** 最近一次采样的页面 scale（判断原生是否仍在响应） */
    private var lastScale = 0f
    /** 指距缩小超阈值且页面 scale 连续无变化的采样数 */
    private var deadSamples = 0
    /** 接管瞬间的指距与 View 缩放（增量计算基准） */
    private var seedSpan = 0f
    private var seedZoom = 1f
    /** 指数集变化（PINCH 中途抬指/加指）后待重置基准 */
    private var rebasePending = false

    /** 当前 View 级缩放（与 tab.viewZoom 同步） */
    private var viewZoom = 1f

    init {
        // 缩小态四周露出的深灰边（100% 时 WebView 满铺，不可见）
        setBackgroundColor(0xFF1E1F22.toInt())
    }

    /**
     * 绑定（或换绑）本容器承载的 WebView。重复调用安全：
     * 换绑时先把 WebView 从旧容器剥离（弹窗临时挂载/标签切换认领场景）。
     * restoreZoom 同步到内部状态（手势仲裁据此判断当前域）。
     */
    fun setWebView(target: WebView?, restoreZoom: Float = 1f) {
        viewZoom = restoreZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (webView === target) {
            if (target != null) applyZoom(target, viewZoom)
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
            applyZoom(target, viewZoom)
        }
    }

    /** 组合销毁（onRelease）时解除引用，防泄漏；WebView 生命周期由引擎管理 */
    fun detachWebView() {
        webView = null
    }

    /** 当前 View 级缩放比例（供恢复/展示用） */
    fun currentZoom(): Float = viewZoom

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldw2: Int) {
        super.onSizeChanged(w, h, oldw, oldw2)
        // 窗口尺寸变化：重算 pivot（缩放值不变）。post 到布局 pass 完成后
        // 执行，保证拿到的是 WebView 的新尺寸而非旧尺寸。
        if (viewZoom != 1f) {
            post { webView?.let { applyZoom(it, viewZoom) } }
        }
    }

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
                    // v2.14.6：不往第二指落下瞬间就拦截 —— 缩小态下运行
                    // 的双拇指游戏（两指静止/同步移动）不应被断流；
                    // 观察指距实际变化后再接管
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
                        // 已在 View 缩小域：指距住任一方向实质变化即接管
                        //（放大向回 100%，缩小向 30%），原生域不参与 ——
                        // 避免“页面 scale × View 缩放”双系统叠加的混乱态
                        if (span < baseSpan * 0.95f || span > baseSpan * 1.05f) {
                            return beginPinch(ev)
                        }
                        return false
                    }
                    val cur = webView?.scale ?: 0f
                    // 页面 scale 是否仍在变化（原生捏合在响应中）。
                    // 监控“最近”而非“手势开始以来”的响应：原生从 250% 缩到
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
                            // = 原生已到地板 → 接管进入 View 缩放域
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
                    mode = MODE_IDLE
                } else {
                    // 三指抬一指后捏合继续：下一帧重置基准
                    rebasePending = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mode = MODE_IDLE
                rebasePending = false
            }
        }
        return true
    }

    /** 从观察/空闲态切入 View 缩放域（此刻 WebView 收到 ACTION_CANCEL） */
    private fun beginPinch(ev: MotionEvent): Boolean {
        mode = MODE_PINCH
        seedSpan = spanOf(ev)
        seedZoom = viewZoom
        rebasePending = false
        return true
    }

    /** 施加缩放并回调（写回 tab.viewZoom） */
    private fun setZoom(zoom: Float) {
        viewZoom = zoom.coerceIn(minViewZoom, 1f)
        webView?.let { wv ->
            applyZoom(wv, viewZoom)
            onZoomChanged?.invoke(viewZoom)
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

    companion object {
        const val MIN_ZOOM = 0.3f
        const val MAX_ZOOM = 1.0f

        // 手势状态机（v2.14.6 CI 修复：自此合并入唯一 companion object）
        private const val MODE_IDLE = 0        // 无双指手势
        private const val MODE_OBSERVE = 1     // 双指观察中（未拦截，原生优先）
        private const val MODE_PINCH = 2       // 已拦截，View 缩放域接管中

        /**
         * 对任意 WebView 施加 View 级缩放（静态入口：引擎导航重置 /
         * 容器手势都走这里）。以视图中心为轴缩放 —— 天然居中且只缩小
         * 不放大，因此永远无裁切、无平移需求（以中心为 pivot 时
         * 0 与 W 两端对称收缩到 W(1-s)/2 与 W(1+s)/2，勿再叠加平移，
         * 否则会双重偏移）。
         */
        fun applyZoom(wv: WebView, zoom: Float) {
            val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            val w = wv.width.toFloat()
            val h = wv.height.toFloat()
            if (w > 0f && h > 0f) {
                // 显式设置中心 pivot（隐式 pivot 虽也是中心，但显式值
                // 不随后续尺寸变化自动更新，窗口缩放后需重算）
                wv.pivotX = w / 2f
                wv.pivotY = h / 2f
            }
            // 未布局时不动 pivot：隐式 pivot 恒等于当前尺寸中心，
            // 布局完成后自动居中
            wv.translationX = 0f
            wv.translationY = 0f
            wv.scaleX = z
            wv.scaleY = z
        }
    }
}
