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
 * v2.14.9：双指缩放仲裁容器 —— 弥补 Chromium 无法缩小到 100% 以下的引擎级限制。
 *
 * ## 方案演进（为什么是"动态 viewport 宽画布"）
 * - v2.14.5 加载期宽画布 viewport：改变默认排版（用户否决：4399 首页按超宽
 *   画布排版整页压扁、初始即 30% 全览）；
 * - v2.14.6 WebView View 变换（scaleX/Y）：内容不随变换矩阵缩放、仅被缩小后
 *   的边界裁切（否决；强制离屏层在 Compose 嵌套下灰屏，v2.8 已录屏证实）；
 * - v2.14.7 JS 注入 document.body.style.zoom：缩放生效但内容钉死左上角、
 *   右侧大片空白（否决）。根因：zoom 只缩渲染不改 window.innerWidth ——
 *   H5 游戏画布按 JS innerWidth 定尺寸，坐标系扩宽后画布不重排；
 * - v2.14.8 body.zoom + 钉宽居中：对称 letterbox —— 用户实测否决（截图
 *   实证：游戏缩成中间窄条、两侧大片灰白 WebView 底色，"没有整体缩放、
 *   网页没有全屏显示"）。结构性缺陷：满宽页面等比缩小后，空出的区域
 *   只能露出 WebView 底色 —— 钉左（v2.14.7）还是居中（v2.14.8）只是
 *   决定灰边长在哪，无法消灭灰边；
 * - v2.14.9（当前）：只在【缩小域激活期间】动态改写 viewport meta：
 *   width = 原排版宽 / z，同时 minimum-scale = maximum-scale =
 *   接管时页面 scale × z。语义与桌面 Chrome Ctrl+减号 完全一致 ——
 *   排版视口按 1/z 变宽，页面 scale 被钳到"窗口宽/画布宽"= z 相对比例：
 *   * 整页所有内容等比变小（整体缩放）；
 *   * 画布宽 = 窗口宽 / z × z = 窗口宽 —— 任何比例下都【精确铺满窗口宽】，
 *     letterbox 在物理上不可能出现；
 *   * window.innerWidth/innerHeight 原生同步变宽 → H5 游戏的 resize 监听
 *     拿到真实新尺寸自动重排画布（v2.14.7/8 的"画布按 innerWidth 定尺寸
 *     不重排"根因被原生消除，无需伪造任何 API）；
 *   * position:fixed 游戏全屏层以视口为包含块自然跟随（无需 v2.14.8 的
 *     identity transform 包含块 hack）；
 *   * 命中测试/滚动/Chromium 合成全部原生路径。
 *   回 100% 时逐字还原原 meta（自建的整段移除），页面 scale 被 Chromium
 *   按"窗口宽/画布宽"地板自动钳回初始满宽态。100% 默认态零注入、零
 *   viewport 改写，排版与旧版完全一致 —— v2.14.5 改默认排版的教训不重蹈：
 *   注入只发生在用户主动捏合缩小之后，且随回 100%/导航即时撤销。
 *
 * ## 两段式缩放域
 * - 【100% 以上】放大域：原生 Chromium 双指捏合（排版重排/文字回流），
 *   本类完全不干预，手势透传给 WebView；
 * - 【30% ~ 100%】缩小域：本类拦截手势流，把指距增量映射为 z 并节流改写
 *   viewport meta（每步 = 一次 meta 改写 + 整页重排，桌面 Chrome 缩放的
 *   同款代价，由 80ms 节流兜底）。
 *
 * ## 手势仲裁（onInterceptTouchEvent）——与 v2.14.6~8 相同的已验证逻辑
 * 双指落下后先【观察】不拦截（第二指落下瞬间不抢占，避免断流缩小态下
 * 运行的双拇指游戏）：
 * - 100% 态、指距拉大（放大意图）→ 永久放行本次手势，原生捏合处理；
 * - 100% 态、指距缩小且页面 scale 仍在变化 → 原生正在缩小（固定宽画布
 *   页地板<100% 的场景），放行；监测的是【最近】响应 —— 原生从 250% 缩到
 *   自身地板后 scale 冻结，同一次手势内无缝接管进缩小域；
 * - 100% 态、指距缩小 ≥15% 且页面 scale 连续 2 帧无变化（原生已在地板上
 *   无法响应）→ 拦截接管，进入缩小域；
 * - 已处于缩小域（viewZoom<1）时，指距任一方向实质变化（≥5%）即接管
 *   （张开回 100%，收缩到 30%）；静止双指不接管（游戏不受扰）。
 *   缩小域内 meta 已把 minimum-scale=maximum-scale 钳死，原生捏合天然
 *   失效，不存在"页面 scale × 注入缩放"双系统叠加的混乱态。
 * 单指点击/滚动/长按全程不拦截，原样透传。
 *
 * ## 注入节流
 * evaluateJavascript 是跨 JNI 异步调用，逐帧注入（60+/s）会排队堆积
 * （GameBox 在 mousemove 分发上的教训）。捏合过程中按【间隔 ≥80ms 且
 * 增量 ≥0.02】节流；手势结束/换绑恢复时强制精确应用一次。
 *
 * ## 附带行为
 * - 缩放状态存于 [BrowserTab.viewZoom]：切标签恢复（重绑后重注入 meta）、
 *   导航（onPageStarted）重置回 100%（新文档天然无注入，同文档锚点导航
 *   由引擎的 RESET_SCRIPT 还原原 meta）；
 * - 缩放值域 [MIN_ZOOM=0.3, 1.0]，达 100% 时按快照还原 meta 并清 __az；
 * - 窗口尺寸变化（旋转/拖拽调整窗口）会使注入的 width=原宽/z 与新窗口
 *   失配（画布不再精确铺满）→ onSizeChanged 直接重置回 100%，用户重新
 *   捏合即可按新尺寸缩放；
 * - 极端缩小 + 超宽原画布时 width 可能触及 Chromium 画布宽上限（约
 *   10000px），此时画布被钳制、页面变为可横向滚动的宽页（桌面 Chrome
 *   同款行为），不再有灰边。
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
    /** 观察域：true = 已处于注入缩小域（viewZoom<1），指距任一方向
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
    /** 上次实际注入 viewport meta 的时间戳（uptimeMillis） */
    private var lastJsApplyAt = 0L
    /** 上次实际注入的缩放值 */
    private var lastJsZoom = 1f

    /**
     * 绑定（或换绑）本容器承载的 WebView。重复调用安全：
     * 换绑时先把 WebView 从旧容器剥离（弹窗临时挂载/标签切换认领场景）。
     * restoreZoom 同步到内部状态（手势仲裁据此判断当前域），<100% 时
     * 立即重注入 viewport meta（切标签恢复缩放态，快照 __az 随页面存续，
     * 原 meta/原画布宽/接管 scale 均从快照复用，无需重新测量）。
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
        // v2.14.10：先把 WebView 从本容器剥离（彻底脱离视图树），
        // 避免销毁后的 WebView 仍挂在已废弃容器上阻碍 GC
        webView?.let { if (it.parent === this) removeView(it) }
        webView = null
    }

    /** 当前缩放比例（供恢复/展示用） */
    fun currentZoom(): Float = viewZoom

    /**
     * v2.14.9：窗口尺寸变化（旋转/AnWind 窗口拖拽调整）时，注入中的
     * width=原宽/z 已与新窗口失配（画布不再精确铺满、比例漂移）——
     * 直接重置回 100%（还原 meta，Chromium 钳回初始满宽态），
     * 用户重新捏合即按新尺寸获得精确铺满的缩放。
     * oldw/oldh>0 守卫：首次布局（0→实际尺寸）不触发。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh) &&
            viewZoom < 1f && webView != null
        ) {
            viewZoom = 1f
            webView?.let { wv ->
                applyZoom(wv, 1f, force = true)
                onZoomChanged?.invoke(1f)
            }
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
                        // 100%，缩小向 30%）。meta 已把 scale 钳死在
                        // minimum=maximum，原生不会响应，直接接管无双系统叠加
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

    /** 捏合过程中设定目标缩放（节流改写 viewport meta 并回调写回标签） */
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
     * 把缩放注入到页面（动态改写 viewport meta：width=原宽/z + 钳制
     * scale）。force=true 精确注入当前值（手势结束/换绑恢复）；否则受
     * [JS_INTERVAL_MS] 节流。100% 时按快照还原原 meta（不盲清，页面
     * 自设的 viewport 逐字还原）。
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

        /** viewport meta 注入最小间隔（ms）：防 JNI 排队堆积 */
        private const val JS_INTERVAL_MS = 80L

        /** 手势状态机 */
        private const val MODE_IDLE = 0        // 无双指手势
        private const val MODE_OBSERVE = 1     // 双指观察中（未拦截，原生优先）
        private const val MODE_PINCH = 2       // 已拦截，缩小域接管中

        /**
         * 缩小域注入脚本（静态入口：引擎导航重置也用 [RESET_SCRIPT]）。
         * v2.14.9 动态 viewport 宽画布 —— 桌面 Chrome Ctrl+减号 同源语义：
         * - 首次接管快照（window.__az）：原 viewport meta（可能不存在）、
         *   原 content 字符串、原排版宽 cw（此刻尚未注入，clientWidth 仍是
         *   页面原生值）、接管瞬间页面 scale s0（= 原生地板 = 窗口宽/cw）；
         * - 每次注入：width = cw/z（排版视口按 1/z 变宽 → 整页等比变小，
         *   所有固定 px 元素统一缩小 = 整体缩放），minimum-scale =
         *   maximum-scale = s0*z（把页面 scale 钳到"窗口宽/画布宽"——
         *   画布宽 × scale = 窗口宽，任何比例下精确铺满窗口宽，letterbox
         *   物理上不可能出现）；
         * - innerWidth/innerHeight 由 Chromium 原生随排版视口变宽，H5 游戏
         *   的 resize 监听拿到真实新尺寸自动重排画布/重算布局，fixed 全屏层
         *   以视口为包含块自然铺满 —— 无需伪造 API、无需包含块 hack；
         * - 幂等可重入（捏合节流逐次重注入、切标签恢复重注入）：快照缓存于
         *   __az，重复注入只改写 width/scale 数值，无重复测量开销；
         * - 回 100%/导航重置走 [RESET_SCRIPT]（还原原 meta、清 __az）。
         */
        fun zoomScript(zoom: Float): String {
            return if (zoom >= 0.999f) RESET_SCRIPT
            else "(function(){try{" +
                "var d=document,z=$zoom;" +
                "if(!d.documentElement)return;" +
                "var S=window.__az;" +
                "if(!S){" +
                // 首次接管：此刻 meta 尚未被改写，layout/scale 均为页面
                // 原生值 —— 快照必须在此刻完成（之后 clientWidth 是注入
                // 后的宽画布值，不可再作基准）
                "var m=d.querySelector('meta[name=viewport]');" +
                "var cw=(d.documentElement&&d.documentElement.clientWidth)||window.innerWidth;" +
                "var s0=(window.visualViewport&&window.visualViewport.scale)||1;" +
                "if(!(s0>0)||!isFinite(s0))s0=1;" +
                "if(!m){" +
                // 页面无 viewport meta（VIEWPORT_FIT_SCRIPT 失效的非 http 页
                // 等）：自建一个，还原时整段移除，页面状态零残留
                "m=d.createElement('meta');m.setAttribute('name','viewport');" +
                "(d.head||d.documentElement).appendChild(m);" +
                "S={meta:m,content:null,cw:cw,s0:s0,created:true};" +
                "}else{" +
                "S={meta:m,content:m.getAttribute('content'),cw:cw,s0:s0,created:false};" +
                "}" +
                "window.__az=S;" +
                "}" +
                "if(!(S.cw>0))return;" +
                // 目标：画布宽 = cw/z（等比变小且铺满），页面 scale = s0*z
                //（= 窗口宽/画布宽，Chromium 按新约束钳制生效）
                "var w=Math.round(S.cw/z);" +
                "var sc=S.s0*z;" +
                "S.meta.setAttribute('content'," +
                "'width='+w+',minimum-scale='+sc+',maximum-scale='+sc+',user-scalable=yes');" +
                "}catch(e){}})()"
        }

        /**
         * 重置缩放脚本：按快照还原 viewport meta（自建的整段移除、原存在
         * 的逐字还原 content），并清 __az。还原后画布宽回到原生值，
         * Chromium 按"窗口宽/画布宽"地板把当前 scale 钳回初始满宽态
         * （device-width 页回 100%，固定宽 PC 页回全览态）。
         * 引擎 onPageStarted 导航重置用（新文档天然干净；同文档锚点导航
         * 由此完整还原页面自设 viewport）。
         */
        const val RESET_SCRIPT: String =
            "(function(){try{" +
                "var d=document,S=window.__az;" +
                "if(S&&S.meta){" +
                "if(S.created){if(S.meta.parentNode)S.meta.parentNode.removeChild(S.meta);}" +
                "else if(S.content==null)S.meta.removeAttribute('content');" +
                "else S.meta.setAttribute('content',S.content);" +
                "}" +
                "window.__az=null;" +
                "}catch(e){}})()"
    }
}
