package com.anwind.core.boot

import android.media.AudioManager
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.File

/**
 * v3.2.14 启动动画（用户需求：项目根目录 start.mp4 作为 app 启动动画）。
 *
 * 行为约定（v3.2.15 更新：用户要求去掉倍速快进，原视频速度播放）：
 * - 内置动画：assets/anwind/boot/startup.mp4（约 5 秒）—— 按视频自身
 *   时长原速播放完整，不再加速。
 * - 自定义动画（设置→个性化→启动动画→自定义）：file:// 绝对路径的视频，
 *   同样原速播放完整。
 * - 交互：点击任意位置立即跳过。
 * - 硬保底：视频时长 + 1200ms 后无论播放状态如何强制结束；视频
 *   不可读/prepare 挂死（无任何回调）时 12 秒全局兕底，防止卡死启动。
 * - 播放失败（文件缺失/格式不支持/解码错误）：静默直接进入桌面。
 * - 进程级一次性：由调用方（MainActivity + AnWindApp 标记）控制 ——
 *   Activity 因旋转/主题切换重建不重播，app 冷启动重新播放。
 */
private const val ASSET_BOOT_VIDEO = "anwind/boot/startup.mp4"
private const val GLOBAL_FALLBACK_MS = 12_000L

@Composable
fun StartupAnimationOverlay(
    customVideoUri: String?,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    var fadeOut by remember { mutableStateOf(false) }
    // 视频真实时长（onPrepared 回调里写入），看门狗据此保底
    var videoDurationMs by remember { mutableStateOf(0L) }
    val alpha by animateFloatAsState(
        targetValue = if (fadeOut) 0f else 1f,
        animationSpec = tween(280),
        label = "bootFade",
        finishedListener = { if (it <= 0f) onFinished() }
    )

    // 播放器的各种异常路径统一收敛到"立即淡出结束"（静默容错，不阻塞启动）
    fun finishNow() { fadeOut = true }

    val player = remember {
        runCatching {
            MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setOnPreparedListener { mp ->
                    runCatching {
                        // v3.2.15：去掉倍速快进，原视频速度播放完整
                        videoDurationMs = mp.duration.toLong().coerceAtLeast(1)
                        mp.isLooping = false
                        mp.start()
                    }.onFailure { finishNow() }
                }
                setOnCompletionListener { finishNow() }
                setOnErrorListener { _, _, _ ->
                    finishNow(); true
                }
                // 数据源：自定义视频优先（校验存在），否则内置 assets
                val customPath = customVideoUri?.removePrefix("file://")
                if (!customPath.isNullOrBlank() && File(customPath).exists()) {
                    setDataSource(context, android.net.Uri.fromFile(File(customPath)))
                } else {
                    context.assets.openFd(ASSET_BOOT_VIDEO).use { afd ->
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }
                }
                prepareAsync()
            }
        }.onFailure { finishNow() }.getOrNull()
    }

    // 播放器释放（组合离开时；淡出动画由 alpha 监听回调收尾后组合即拆除）
    DisposableEffect(player) {
        onDispose {
            runCatching {
                player?.let {
                    if (it.isPlaying) it.stop()
                    it.release()
                }
            }
        }
    }

    // 硬保底看门狗：视频时长 + 1200ms 缓冲，无论播放状态强制收尾。
    // duration 状态在 onPrepared 后写入，LaunchedEffect 随之重启，保底
    // 时机始终基于真实时长（原速播放完整后 1.2s 内必结束）。
    LaunchedEffect(videoDurationMs) {
        if (videoDurationMs > 0) {
            delay(videoDurationMs + 1200)
            finishNow()
        }
    }

    // 极端兕底：数据源损坏/prepare 挂死（无任何回调）时全局超时，
    // 不让启动动画卡死启动流程。
    LaunchedEffect(player) {
        delay(GLOBAL_FALLBACK_MS)
        finishNow()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(Color.Black)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { finishNow() }
    ) {
        // TextureView（而非 SurfaceView）：独立合成层的 SurfaceView 会盖住
        // Compose 的 alpha 淡出；TextureView 参与正常视图层级可一起淡出。
        // surfaceTexture 就绪时机不保证与组合同步 —— SurfaceTextureListener
        // 的 onSurfaceTextureAvailable 里再挂 surface。
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: android.graphics.SurfaceTexture,
                            width: Int, height: Int
                        ) {
                            runCatching { player?.setSurface(Surface(surface)) }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: android.graphics.SurfaceTexture,
                            width: Int, height: Int
                        ) {}

                        override fun onSurfaceTextureDestroyed(
                            surface: android.graphics.SurfaceTexture
                        ): Boolean {
                            runCatching { player?.setSurface(null as Surface?) }
                            return true
                        }

                        override fun onSurfaceTextureUpdated(
                            surface: android.graphics.SurfaceTexture
                        ) {}
                    }
                }
            }
        )
    }
}
