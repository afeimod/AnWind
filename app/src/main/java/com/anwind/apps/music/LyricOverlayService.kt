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
import android.provider.Settings
import android.text.TextUtils
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
 * - 悬浮窗服务（LyricOverlayService）每 200ms 轮询读取并刷新两个 TextView；
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
    @Volatile var bgAlpha: Float = 0.35f
    @Volatile var sizeSp: Float = 22f

    fun applySettings(s: MusicSettings) {
        fullscreen = s.desktopLyricFullscreen
        textColor = s.desktopLyricColor
        bgAlpha = s.desktopLyricBgAlpha
        sizeSp = s.desktopLyricSize
    }

    fun currentText(): String = lines.getOrNull(index)?.text.orEmpty()

    fun nextText(): String = lines.getOrNull(index + 1)?.text.orEmpty()
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
                layout.getLineBaseline(i),
                strokePaint
            )
        }
        super.onDraw(canvas)
    }
}

/**
 * 桌面歌词悬浮窗服务（v2.21）：
 * - 前台服务（specialUse）+ TYPE_APPLICATION_OVERLAY 悬浮窗，需「显示在应用上层」权限
 * - 两行模式（对照参考图4）：两个独立小浮条 —— 当前行左上、下一行右侧居中，均可拖动
 * - 桌面全屏歌词：通屏宽横幅居中，当前行大字 + 下一行小字
 * - 每 200ms 轮询 DesktopLyricBus 刷新文字/颜色/背景；模式切换重建窗口
 */
class LyricOverlayService : Service() {

    companion object {
        private const val NOTIF_ID = 20417
        private const val CHANNEL_ID = "desktop_lyric"
        private const val POLL_MS = 200L
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    /** 当前添加到窗口的所有根视图（两行模式 2 个 / 全屏 1 个） */
    private val roots = ArrayList<View>()
    private var line1: OutlineTextView? = null
    private var line2: OutlineTextView? = null

    /** 当前窗口布局所用模式（false 两行 / true 全屏），与总线不一致时重建 */
    private var builtFullscreen: Boolean = false

    private val params1 = WindowManager.LayoutParams()
    private val params2 = WindowManager.LayoutParams()

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

    /** 构建一行浮条：圆角半透明背景 + 描边文字 */
    private fun buildLineWindow(): Pair<FrameLayout, OutlineTextView> {
        val root = FrameLayout(this)
        root.setPadding(dp(10f), dp(6f), dp(10f), dp(7f))
        val text = OutlineTextView(this)
        text.maxLines = 1
        text.ellipsize = TextUtils.TruncateAt.END
        text.typeface = Typeface.DEFAULT_BOLD
        text.includeFontPadding = false
        root.addView(text)
        return root to text
    }

    private fun bgOf(view: View, alpha: Float) {
        val d = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(AwtColor.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), 8, 8, 12))
        }
        view.background = d
    }

    private fun rebuild() {
        for (v in roots) runCatching { wm.removeView(v) }
        roots.clear()

        val fs = DesktopLyricBus.fullscreen
        builtFullscreen = fs

        if (!fs) {
            // ---- 两行模式（参考图4）：当前行左上浮条 + 下一行右侧居中浮条 ----
            val (root1, t1) = buildLineWindow()
            params1.set(baseParams())
            params1.gravity = Gravity.TOP or Gravity.START
            params1.x = dp(42f)
            params1.y = dp(120f)
            wm.addView(root1, params1)
            makeDraggable(root1, params1)
            line1 = t1
            roots.add(root1)

            val (root2, t2) = buildLineWindow()
            params2.set(baseParams())
            params2.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            params2.x = dp(48f)
            params2.y = dp(150f)
            wm.addView(root2, params2)
            makeDraggable(root2, params2)
            line2 = t2
            roots.add(root2)
        } else {
            // ---- 桌面全屏歌词：通屏宽横幅，屏幕垂直居中 ----
            val container = LinearLayout(this)
            container.orientation = LinearLayout.VERTICAL
            container.setPadding(dp(16f), dp(10f), dp(16f), dp(12f))
            val t1 = OutlineTextView(this)
            t1.maxLines = 1
            t1.ellipsize = TextUtils.TruncateAt.END
            t1.typeface = Typeface.DEFAULT_BOLD
            t1.gravity = Gravity.CENTER
            t1.includeFontPadding = false
            val t2 = OutlineTextView(this)
            t2.maxLines = 1
            t2.ellipsize = TextUtils.TruncateAt.END
            t2.gravity = Gravity.CENTER
            t2.includeFontPadding = false
            container.addView(
                t1,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            container.addView(
                t2,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6f) }
            )
            val p = baseParams()
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.gravity = Gravity.CENTER
            p.x = 0
            p.y = 0
            wm.addView(container, p)
            makeDraggable(container, p)
            line1 = t1
            line2 = t2
            roots.add(container)
        }
        renderFrame()
    }

    private fun rebuildIfNeeded() {
        if (roots.isEmpty() || builtFullscreen != DesktopLyricBus.fullscreen) rebuild()
    }

    // ==================== 刷新 ====================

    private fun renderFrame() {
        val t1 = line1 ?: return
        val cur = DesktopLyricBus.currentText()
        val nxt = DesktopLyricBus.nextText()
        val standby = if (DesktopLyricBus.songName.isNotBlank())
            "♪ ${DesktopLyricBus.songName}"
        else
            "AnWind 云音乐 · 桌面歌词"

        t1.text = cur.ifBlank { standby }
        line2?.text = nxt.ifBlank { if (cur.isBlank()) "开启播放后显示歌词" else "···" }

        val color = DesktopLyricBus.textColor
        val size = DesktopLyricBus.sizeSp
        t1.setTextColor(color)
        t1.textSize = size
        t1.outlinePx = (size / 6f) * resources.displayMetrics.density

        line2?.let { t2 ->
            // 下一行：同色 60% 透明度 + 0.7 倍字号
            t2.setTextColor((color and 0x00FFFFFF) or 0x99000000.toInt())
            t2.textSize = size * 0.7f
            t2.outlinePx = (size * 0.7f / 6f) * resources.displayMetrics.density
        }

        // 背景不透明度实时刷新（0 = 全透明仅描边字）
        for (v in roots) bgOf(v, DesktopLyricBus.bgAlpha)
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
