package com.anwind.apps.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color as AwtColor
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 桌面歌词跨组件总线（v2.21）：
 * - 应用内播放器（MusicContent）在每次播放进度 tick 时写入当前歌词行状态；
 * - 悬浮窗服务（LyricOverlayService）每 200ms 轮询读取并刷新文字/颜色/KTV 扫色；
 * - 设置页改动桌面歌词偏好后调用 applySettings 即时推送（同一进程内 volatile 可见）。
 */
object DesktopLyricBus {

    /** 当前歌词行列表与行索引（-1 = 前奏/无歌词） */
    @Volatile var lines: List<LyricLine> = emptyList()
    @Volatile var index: Int = -1

    /** 当前歌曲名（无歌词/未播放时悬浮窗显示歌名待机文案） */
    @Volatile var songName: String = ""
    @Volatile var playing: Boolean = false

    // ---- 悬浮窗偏好镜像（由 MusicSettings 驱动） ----
    @Volatile var fullscreen: Boolean = false
    @Volatile var textColor: Int = 0xFFFFFFFF.toInt()
    @Volatile var bgAlpha: Float = 0f
    @Volatile var sizeSp: Float = 22f
    /** KTV 逐字变色（v2.21.1）：两种模式均生效 */
    @Volatile var ktv: Boolean = true
    /** 全屏横幅显示行数（v2.21.1，围绕当前行取词） */
    @Volatile var linesCount: Int = 4

    // ---- KTV 进度源（v2.21.1）：播放器每次 tick 写入，服务侧按墙钟外插平滑 ----
    @Volatile var positionMs: Long = 0L
    @Volatile var lineStartMs: Long = 0L
    @Volatile var lineEndMs: Long = 0L
    @Volatile var posUpdatedAt: Long = 0L

    fun applySettings(s: MusicSettings) {
        fullscreen = s.desktopLyricFullscreen
        textColor = s.desktopLyricColor
        bgAlpha = s.desktopLyricBgAlpha
        sizeSp = s.desktopLyricSize
        ktv = s.desktopLyricKtv
        linesCount = s.desktopLyricLines
    }

    fun lineTextAt(i: Int): String = lines.getOrNull(i)?.text.orEmpty()

    fun currentText(): String = lineTextAt(index)

    fun nextText(): String = lineTextAt(index + 1)

    /** 当前行 KTV 扫色进度 0..1（暂停时冻结，播放中按墙钟外插平滑） */
    fun ktvFraction(): Float {
        val span = lineEndMs - lineStartMs
        if (span <= 0L) return 0f
        val pos = if (playing)
            positionMs + (SystemClock.uptimeMillis() - posUpdatedAt).coerceAtLeast(0L)
        else positionMs
        return ((pos - lineStartMs).toFloat() / span).coerceIn(0f, 1f)
    }
}

/** 启动桌面歌词悬浮窗（无悬浮窗权限时静默忽略；由设置页负责先引导授权） */
fun startDesktopLyricService(context: Context) {
    if (!Settings.canDrawOverlays(context)) return
    runCatching {
        val intent = Intent(context, LyricOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
    }
}

/** 停止桌面歌词悬浮窗 */
fun stopDesktopLyricService(context: Context) {
    runCatching { context.stopService(Intent(context, LyricOverlayService::class.java)) }
}

/**
 * 带描边的歌词 TextView（经典桌面歌词样式）：
 * 先用黑色粗描边把整句描一圈，再画正常填充文字 —— 任意壁纸上都清晰可读。
 */
private class OutlineTextView(context: Context) : TextView(context) {

    /** 描边宽度（px，随字号缩放设置） */
    var outlinePx: Float = 3f

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return super.onDraw(canvas)
        strokePaint.set(paint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = outlinePx
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.color = AwtColor.argb(215, 0, 0, 0)
        for (i in 0 until layout.lineCount) {
            canvas.drawText(
                text.toString(),
                layout.getLineLeft(i),
                layout.getLineBaseline(i).toFloat(),
                strokePaint
            )
        }
        super.onDraw(canvas)
    }
}

/**
 * 桌面歌词悬浮窗服务（v2.21.2）：
 * - 前台服务（specialUse）+ TYPE_APPLICATION_OVERLAY 悬浮窗，需「显示在应用上层」权限
 * - 两行模式：一个可拖动窗口 —— 垂直 LinearLayout 当前行顶左、下一行右下（错位不重叠），
 *   两行字号相同，行索引推进时两行同时轮换；背景贴字自适应宽度（不固定宽）
 * - 桌面全屏歌词：行数可设（1-15 行，默认 4 行，围绕当前行取词），横幅宽度随歌词自适应，
 *   当前行大字 + KTV 扫色，其余行 0.7 倍
 * - 两种模式均可 KTV 逐字变色（进度由播放器写入总线，服务侧墙钟外插平滑）
 * - 每 200ms 轮询 DesktopLyricBus 刷新文字/颜色/背景；模式/行数变化重建窗口
 * - 生命周期跟随播放器：播放器窗口关闭/应用退出即随之关闭（Manifest stopWithTask 双保险）
 */
class LyricOverlayService : Service() {

    companion object {
        private const val NOTIF_ID = 20417
        private const val CHANNEL_ID = "desktop_lyric"
        private const val POLL_MS = 200L
        /** KTV 未唱部分颜色：60% 白（描边保证可读） */
        private val KTV_UNSONG_COLOR = 0x99FFFFFF.toInt()
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    /** 当前添加到窗口的所有根视图（两行模式 1 个 / 全屏 1 个） */
    private val roots = ArrayList<View>()

    /** 两行模式的两个文字视图（同一窗口：左上当前行 / 右下下一行，同字号同窗轮换） */
    private var line1: OutlineTextView? = null
    private var line2: OutlineTextView? = null
    /** 两行模式贴字药丸背景（背景不透明度实时刷，宽度随歌词自适应） */
    private val pills = ArrayList<View>()

    /** 全屏模式的行槽（行数可设，围绕当前行滚动取词） */
    private val slots = ArrayList<OutlineTextView>()

    /** 构建窗口时记录的偏好，与总线不一致时重建（模式/行数） */
    private var builtFullscreen: Boolean = false
    private var builtLines: Int = 0

    private var pairParams = WindowManager.LayoutParams()

    private val tick = object : Runnable {
        override fun run() {
            renderFrame()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 双保险：无悬浮窗权限直接退出（设置页已引导授权）
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
        DesktopLyricBus.applySettings(MusicStore(this).loadMusicSettings())
        rebuild()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 设置变更后再次 start 时同步偏好并按需重建窗口
        DesktopLyricBus.applySettings(MusicStore(this).loadMusicSettings())
        handler.post { rebuildIfNeeded() }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        for (v in roots) runCatching { wm.removeView(v) }
        roots.clear()
        super.onDestroy()
    }

    // ==================== 窗口构建 ====================

    private fun baseParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    /** 构建一行浮条：圆角半透明背景 + 描边文字（超宽行按屏宽 88% 截断省略） */
    private fun buildLineWindow(): Pair<FrameLayout, OutlineTextView> {
        val root = FrameLayout(this)
        root.setPadding(dp(10f), dp(6f), dp(10f), dp(7f))
        val text = OutlineTextView(this)
        text.maxLines = 1
        text.ellipsize = TextUtils.TruncateAt.END
        text.typeface = Typeface.DEFAULT_BOLD
        text.includeFontPadding = false
        // v2.21.2：两行模式同样限宽 —— 背景/文字随歌词自适应但永不顶出屏幕
        text.maxWidth = (resources.displayMetrics.widthPixels * 0.88f).toInt() - dp(20f)
        root.addView(text)
        return root to text
    }

    private fun bgOf(view: View, alpha: Float) {
        val d = GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat()
            setColor(AwtColor.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), 8, 8, 12))
        }
        view.background = d
    }

    private fun rebuild() {
        for (v in roots) runCatching { wm.removeView(v) }
        roots.clear()
        pills.clear()
        slots.clear()
        line1 = null
        line2 = null

        val fs = DesktopLyricBus.fullscreen
        builtFullscreen = fs
        builtLines = DesktopLyricBus.linesCount

        if (!fs) {
            // ---- 两行模式（v2.21.2 修重叠）：一个可拖动窗口，垂直 LinearLayout ——
            // 当前行 pill 顶左（START 对齐）+ 下一行 pill 右下（END 对齐 + 6dp 上间距），
            // 窗口高度 = 两行之和，彻底避免两行重叠；两行字号相同、整体拖动；
            // 各自贴字药丸背景（宽度随歌词自适应），窗口本身全透明；
            // 行索引推进时两行同时轮换（line1←当前，line2←下一行）
            val root = LinearLayout(this)
            root.orientation = LinearLayout.VERTICAL
            val (pill1, t1) = buildLineWindow()
            root.addView(
                pill1,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.START }
            )
            val (pill2, t2) = buildLineWindow()
            root.addView(
                pill2,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.END
                    topMargin = dp(6f)
                }
            )
            pairParams = baseParams()
            pairParams.gravity = Gravity.TOP or Gravity.START
            pairParams.x = dp(42f)
            pairParams.y = dp(120f)
            wm.addView(root, pairParams)
            makeDraggable(root, pairParams)
            line1 = t1
            line2 = t2
            pills.add(pill1)
            pills.add(pill2)
            roots.add(root)
        } else {
            // ---- 桌面全屏歌词（v2.21.2 行数上限扩到 15）：行数可设（默认 4 行，围绕当前行取词），
            // 横幅宽度随歌词自适应（超宽行按屏宽 88% 截断省略），屏幕居中，整幅可拖动
            val n = DesktopLyricBus.linesCount.coerceIn(1, 15)
            val container = LinearLayout(this)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding(dp(14f), dp(8f), dp(14f), dp(10f))
            val maxTextPx = (resources.displayMetrics.widthPixels * 0.88f).toInt() - dp(28f)
            for (j in 0 until n) {
                val t = OutlineTextView(this)
                t.maxLines = 1
                t.ellipsize = TextUtils.TruncateAt.END
                t.typeface = Typeface.DEFAULT_BOLD
                t.gravity = Gravity.CENTER_HORIZONTAL
                t.includeFontPadding = false
                t.maxWidth = maxTextPx
                container.addView(
                    t,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        if (j > 0) topMargin = dp(4f)
                    }
                )
            }
            val p = baseParams()
            p.gravity = Gravity.CENTER
            p.x = 0
            p.y = 0
            wm.addView(container, p)
            makeDraggable(container, p)
            for (j in 0 until n) slots.add(container.getChildAt(j) as OutlineTextView)
            roots.add(container)
        }
        renderFrame()
    }

    private fun rebuildIfNeeded() {
        if (roots.isEmpty() ||
            builtFullscreen != DesktopLyricBus.fullscreen ||
            builtLines != DesktopLyricBus.linesCount
        ) rebuild()
    }

    // ==================== 刷新 ====================

    private fun renderFrame() {
        val color = DesktopLyricBus.textColor
        val size = DesktopLyricBus.sizeSp
        if (!builtFullscreen) {
            // ---- 两行模式：两行同字号同窗口，当前行 KTV 扫色，行推进时两行同时轮换 ----
            val t1 = line1 ?: return
            val cur = DesktopLyricBus.currentText()
            val nxt = DesktopLyricBus.nextText()
            setLine(t1, cur.ifBlank { standbyText() }, size, color, isCurrent = true)
            line2?.let {
                setLine(
                    it,
                    nxt.ifBlank { if (cur.isBlank()) "开启播放后显示歌词" else "···" },
                    size, color, isCurrent = false
                )
            }
            for (p in pills) bgOf(p, DesktopLyricBus.bgAlpha)
        } else {
            // ---- 全屏模式：围绕当前行取 n 行，当前行大字 + KTV 扫色，其余行 0.7 倍 ----
            if (slots.isEmpty()) return
            val n = slots.size
            val idx = DesktopLyricBus.index
            val has = DesktopLyricBus.lines.isNotEmpty()
            val start = (idx - (n - 1) / 2).coerceAtLeast(0)
            for (j in 0 until n) {
                val li = start + j
                val txt = when {
                    has -> DesktopLyricBus.lineTextAt(li)
                    j == (n - 1) / 2 -> standbyText()
                    else -> ""
                }
                val isCur = has && li == idx
                setLine(slots[j], txt, if (isCur) size else size * 0.7f, color, isCurrent = isCur)
            }
            for (v in roots) bgOf(v, DesktopLyricBus.bgAlpha)
        }
    }

    private fun standbyText(): String = if (DesktopLyricBus.songName.isNotBlank())
        "♪ ${DesktopLyricBus.songName}"
    else
        "AnWind 云音乐 · 桌面歌词"

    /** 刷新一行文字：字号/描边宽度跟随设置；KTV 开启时当前行按播放进度逐字扫色 */
    private fun setLine(tv: OutlineTextView, text: String, sizeSp: Float, color: Int, isCurrent: Boolean) {
        val wantPx = sizeSp * resources.displayMetrics.scaledDensity
        if (kotlin.math.abs(tv.textSize - wantPx) > 0.5f) {
            tv.textSize = sizeSp
            tv.outlinePx = (sizeSp / 6f) * resources.displayMetrics.density
        }
        if (DesktopLyricBus.ktv) {
            if (isCurrent) {
                applyKtvSpans(tv, text, DesktopLyricBus.ktvFraction(), color, KTV_UNSONG_COLOR)
                return
            }
            tv.setTextColor(KTV_UNSONG_COLOR)
        } else {
            tv.setTextColor(color)
        }
        tv.text = text
    }

    /**
     * KTV 逐字变色（v2.21.1）：按字符宽度累计中点是否越过扫色前沿，
     * 逐字设置已唱（主色）/未唱（半透明白）颜色；代理对合并为一个跨度。
     */
    private fun applyKtvSpans(tv: TextView, text: String, frac: Float, sungColor: Int, baseColor: Int) {
        if (text.isEmpty()) {
            tv.text = ""
            return
        }
        val paint = tv.paint
        val total = paint.measureText(text).coerceAtLeast(0.001f)
        val span = SpannableString(text)
        var acc = 0f
        var i = 0
        while (i < text.length) {
            var j = i + 1
            if (Character.isHighSurrogate(text[i]) && j < text.length && Character.isLowSurrogate(text[j])) j++
            val w = paint.measureText(text, i, j)
            val sung = acc + w * 0.5f <= frac * total
            acc += w
            span.setSpan(
                ForegroundColorSpan(if (sung) sungColor else baseColor),
                i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            i = j
        }
        tv.setTextColor(sungColor)
        tv.text = span
    }

    // ==================== 拖动 ====================

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var origX = 0
        var origY = 0
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    origX = params.x
                    origY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = origX + (e.rawX - downX).toInt()
                    params.y = origY + (e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, params) }
                    true
                }
                else -> false
            }
        }
    }

    // ==================== 前台通知 ====================

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "桌面歌词",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val iconId = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_media_play
        @Suppress("DEPRECATION")
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(iconId)
                .setContentTitle("AnWind 桌面歌词已开启")
                .setContentText("正在桌面显示歌词 · 回到应用可关闭")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setSmallIcon(iconId)
                .setContentTitle("AnWind 桌面歌词已开启")
                .setContentText("正在桌面显示歌词 · 回到应用可关闭")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
