package com.anwind.apps.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.R
import com.anwind.apps.music.MusicStore as Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * 3D 歌词秀（v2.17 图1；v2.20.3 真透视歌词墙；v2.21 行内左右字体差 + KTV 渐进 + 自定义封面/光盘）
 * - 深色沉浸背景：封面大图模糊铺底 + 黑色渐变压暗（v2.21 默认接近清晰/最亮）
 * - 左侧：圆角封面卡片 + 右后方探出的旋转 CD（v2.21 封面/光盘均可自定义图片）
 * - 右侧：真 3D 透视歌词墙 —— 整面墙绕 X 轴俯仰 + 绕 Y 轴偏航；每行叠加
 *   「左右字体差」行内逐字字号渐变（行首小行尾大，v2.21.1 重做，替换行级 rotationY）；
 *   当前行支持高亮颜色与 KTV 渐进填色（按播放进度从左向右扫开）
 * - 左上角《歌名》— 歌手标题，右下角模式/上一首/播放/下一首/歌词下载控制
 *
 * v2.22 歌词秀双界面（点击封面/唱片互切，样式持久化）：
 * - ① 3D 歌词墙：封面与光盘嵌合 —— 光盘中心正好压在封面右边缘，仅探出右半圆；
 * - ② 黑胶唱片机：左黑胶唱片 + 唱针臂，右侧与默认界面同一套 3D 歌词墙
 *
 * v2.23 三项打磨：
 * - ① 默认界面：光盘中心圆改为覆盖层压在封面右缘上，半透明玻璃质感透出封面（更真实）；
 * - ② 黑胶界面：与默认同构的左右布局（左唱片/右 3D 歌词墙），横竖屏一致，共用 3D 墙；
 * - ③ 黑胶盘中心标贴加大（0.40r→0.55r），胶盘黑边明显收窄
 *
 * v2.19 设置即时生效机制保留：组合期在自身作用域直读快照 State（settingsProvider()），
 * 滑条一变直接失效重组/重绘，不依赖参数链传递。
 *
 * v2.25 控制条缩位（两个界面同步）：
 * - 原页面底部全宽控制条（模式/上一首/播放/下一首 + 进度）整体缩小约 35%，
 *   移到左列封面/光盘（黑胶界面为唱片）正下方，两界面同构；
 * - 页面下部完全让给 3D 歌词墙，沉浸感更强；
 * - 播放器主界面的音量条/进度条同步修复为标准安卓 Material 尺寸（thumb 不再被压扁）。
 *
 * v2.25.1 贴合与顺序修正（反馈修复）：
 * - 控制条内部恢复原序：按钮行（模式/上一首/播放/下一首）在上、进度条在下；
 * - 竖屏控制条紧贴光盘：CoverWithDisc 布局盒由固定 190dp 改为 190*fit（与
 *   graphicsLayer 视觉盒重合，内部子元素 requiredSize 锁设计尺寸），消除缩放差
 *   带来的上下空隙；左右两列改为「主体 + 控制条」整组垂直居中（Spacer 6dp）；
 * - 黑胶唱片直径高度预留控制条空间（列高 - 72dp 参与取小），横屏矮列不溢出。
 *
 * v1.2 三项新增：
 * - ① 自定义视频背景（BG_VIDEO）：主页与歌词页均可选本地视频静音循环铺底；
 * - ② 竖屏歌词优化（v1.2.2 定稿）：竖屏恢复左右分栏原版光盘位置，改为「歌词墙垫底」
 *   —— 歌词墙全宽铺底，行尾靠右；长行单行铺开不换行，尾部超出右侧隐藏，
 *   随行内播放进度逐帧左移滚入出现（与 KTV 扫色同速）；
 *   横屏保持左右分栏（封面列 38% / 歌词墙 62%）不变；
 * - ③ 非高亮歌词颜色自定义（lyricSubColor）：非当前行、翻译行与 KTV 未唱部分跟随该色。
 *
 * v1.3 竖屏三段式重构（本次修改）：
 * - 竖屏歌词页改为「封面光盘在上 / 3D 歌词墙居中 / 控制条贴底」上下三段式
 *   （PortraitColumnBody），替代 v1.2.1 的「歌词墙垫底 + 左列主体悬浮」OverlayBody；
 * - 3D 歌词墙与黑胶唱片机两界面共用同一竖屏结构，点击封面/唱片仍可互切样式；
 * - 底部控制条恢复页面级全宽定位（上一首/下一首/播放/模式 + 进度条），尺寸适度
 *   回调（v2.25 为收进 38% 窄列而缩小的幅度不再必要）；
 * - 黑胶唱片直径不再为正下方控制条预留 72dp（控制条已移至页面底部，
 *   横屏左列仍保留预留不变）。
 *
 * v1.3.1 竖屏歌词居中修正：
 * - 水平居中：竖屏三段式关闭单行滚动（singleLineScroll 的「行尾靠右 + 长行单行
 *   铺开」是 v1.2.2 歌词墙垫底布局的设计 —— 长行延伸至光盘背后）；光盘已上移、
 *   不再与歌词重叠，歌词改回水平居中，长行自动两行折行；
 * - 垂直居中：歌词区上下 padding 0.34 → 0.42 —— scrollToItem 把当前行顶部
 *   定位在顶部 padding 下缘，42% 时当前行块正好居于歌词区中部（旧值为全屏
 *   歌词墙的偏上构图，三段式歌词区变矮后偏高明显）。
 */
@Composable
fun Lyrics3DPage(
    song: SongInfo?,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    lyric: LyricsDoc?,
    lyricLoading: Boolean,
    playMode: Int,
    /** v2.19：设置提供者，每次调用返回最新 MusicSettings（快照读，即时生效） */
    settingsProvider: () -> MusicSettings,
    /** v2.21：实时进度提供者（直读 MediaPlayer，供 KTV 逐字填充平滑扫色） */
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCycleMode: () -> Unit,
    onDownloadLyric: () -> Unit,
    onClose: () -> Unit,
    /** v2.22：设置更新回调 —— 歌词秀双界面切换后经此持久化 */
    onUpdateSettings: (MusicSettings) -> Unit = {},
    /** v2.21.3：所在窗口是否处于真全屏（控制按钮图标切换） */
    isTrueFullscreen: Boolean = false,
    /** v2.21.3：全屏按钮回调 —— 切换窗口真全屏，返回键恢复 */
    onToggleFullscreen: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 组合期读取：背景模式等参与重组的设置（v2.19：所在作用域直读快照 State）
    val settings = settingsProvider()

    // v2.22：歌词秀双界面 —— 0 = 3D 歌词墙（封面嵌合 CD），1 = 黑胶唱片机；
    // 点击封面/唱片互切并经 updateSettings 持久化（即改即生效，重启保留）
    val vinylStyle = settings.lyricStyle == MusicSettings.LYRIC_STYLE_VINYL
    val toggleStyle: () -> Unit = {
        val next = if (settingsProvider().lyricStyle == MusicSettings.LYRIC_STYLE_VINYL)
            MusicSettings.LYRIC_STYLE_WALL
        else
            MusicSettings.LYRIC_STYLE_VINYL
        onUpdateSettings(settingsProvider().copy(lyricStyle = next))
    }

    // ===== 封面源解析（独立版新增）：在线网络封面 / 本地内嵌封面，无图回落默认封面 =====
    val coverContext = LocalContext.current
    var coverSrc by remember(song?.key) {
        mutableStateOf(if (song != null && !song.isLocal && song.picUrl.isNotBlank()) song.picUrl else "")
    }
    LaunchedEffect(song?.key, song?.localPath, song?.downloadedPath) {
        coverSrc = LocalCover.sourceFor(coverContext, song)
    }

    Box(modifier.fillMaxSize().background(Mc.lyricBg)) {
        // ===== 背景（v2.18 可自定义）：封面模糊 / 纯色 / 渐变 / 自定义图片 =====
        when (settings.lyricBgMode) {
            MusicSettings.BG_SOLID -> {
                Box(Modifier.matchParentSize().background(Color(settings.lyricBgColor)))
            }
            MusicSettings.BG_GRADIENT -> {
                val pair = LyricBgGradients.getOrElse(settings.lyricBgGradient) { LyricBgGradients[0] }
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(pair))
                )
            }
            MusicSettings.BG_IMAGE -> {
                BgImage(settings.lyricBgImage, Modifier.matchParentSize())
            }
            MusicSettings.BG_VIDEO -> {
                // v1.2 自定义视频背景：静音循环铺底，随歌词页生命周期自动暂停/续播
                BgVideo(settings.lyricBgVideo, Modifier.matchParentSize())
            }
            else -> {
                // 封面模糊铺底（默认，对应图1）—— v2.20 模糊半径可调（0 = 清晰不模糊）
                AsyncCover(
                    url = coverSrc,
                    modifier = Modifier
                        .matchParentSize()
                        .then(
                            if (settings.coverBlur > 0.5f) Modifier.blur(settings.coverBlur.dp)
                            else Modifier
                        )
                        .background(Mc.lyricBg)
                )
            }
        }
        // 深色渐变压暗（图片/封面模式下加重且 v2.20 强度可调，纯色/渐变模式轻微提 readability）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        if (settings.lyricBgMode == MusicSettings.BG_SOLID ||
                            settings.lyricBgMode == MusicSettings.BG_GRADIENT
                        ) {
                            listOf(Color(0x33000000), Color(0x55000000), Color(0x77000000))
                        } else {
                            // v2.20：压暗强度可调（默认 0.85 与旧版视觉一致）
                            val d = settings.lyricBgDim
                            listOf(
                                Color.Black.copy(alpha = (d - 0.15f).coerceAtLeast(0f)),
                                Color.Black.copy(alpha = d),
                                Color.Black.copy(alpha = (d + 0.13f).coerceAtMost(0.96f))
                            )
                        }
                    )
                )
        )

        // ===== 内容 =====
        // 背景/封面延伸到状态栏/导航栏/刘海后面（edge-to-edge 占用刘海），
        // 内容避让系统栏；真全屏（系统栏隐藏）时 insets 归零，自动铺满全屏
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ---- 顶部标题栏：返回 + 《歌名》 + 歌手 + 歌词下载 ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 14.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "《${song?.name ?: "未在播放"}》",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = song?.artist?.takeIf { it.isNotBlank() } ?: "—",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 歌词来源标记
                if (lyric != null) {
                    Text(
                        text = when (lyric.source) {
                            "netease" -> "词源 网易云"
                            "kuwo" -> "词源 酷我"
                            "qq" -> "词源 QQ音乐"
                            "lrclib" -> "词源 LRCLIB"
                            "meting" -> "词源 聚合"
                            else -> "已缓存"
                        },
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDownloadLyric) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载歌词",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                // v2.21.3：全屏/退出全屏（真全屏隐藏标题栏与任务栏，返回键恢复）
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isTrueFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = if (isTrueFullscreen) "退出全屏" else "全屏",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ---- 主体：v2.22 歌词秀双界面 —— 3D 歌词墙 / 黑胶唱片机 ----
            // v1.3 竖屏三段式：封面光盘在上 / 3D 歌词墙居中 / 控制条贴底
            // （两界面同构，点击封面/唱片仍可互切样式）；
            // 横屏保持左右分栏（主体列 38% / 歌词墙 62%）不变
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val portrait = maxHeight > maxWidth
                if (vinylStyle) {
                    if (portrait) {
                        // 竖屏黑胶唱片机：唱片在上 / 歌词墙居中 / 控制条贴底（v1.3 三段式）
                        PortraitColumnBody(
                            lyric = lyric,
                            lyricLoading = lyricLoading,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            isPlaying = isPlaying,
                            isPreparing = isPreparing,
                            playMode = playMode,
                            settingsProvider = settingsProvider,
                            positionProvider = positionProvider,
                            onSeek = onSeek,
                            onToggle = onToggle,
                            onNext = onNext,
                            onPrev = onPrev,
                            onCycleMode = onCycleMode,
                            mainUnit = {
                                VinylDiscUnit(
                                    coverUrl = coverSrc,
                                    customCover = settings.coverImage,
                                    customDisc = settings.discImage,
                                    isPlaying = isPlaying,
                                    onToggleStyle = toggleStyle,
                                    // v1.3：控制条已移至页面底部，唱片不再为其预留高度
                                    reservedBottom = 0f
                                )
                            }
                        )
                    } else {
                        // 黑胶唱片机（对照参考图2）：左黑胶 + 唱针 + 左列底部紧凑控制条，点击唱片切回 3D 墙
                        VinylBody(
                            coverUrl = coverSrc,
                            customCover = settings.coverImage,
                            customDisc = settings.discImage,
                            isPlaying = isPlaying,
                            lyric = lyric,
                            lyricLoading = lyricLoading,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            isPreparing = isPreparing,
                            playMode = playMode,
                            settingsProvider = settingsProvider,
                            positionProvider = positionProvider,
                            onSeek = onSeek,
                            onToggle = onToggle,
                            onNext = onNext,
                            onPrev = onPrev,
                            onCycleMode = onCycleMode,
                            onToggleStyle = toggleStyle,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (portrait) {
                    // 竖屏 3D 歌词墙：封面光盘在上 / 歌词墙居中 / 控制条贴底（v1.3 三段式）
                    PortraitColumnBody(
                        lyric = lyric,
                        lyricLoading = lyricLoading,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        isPreparing = isPreparing,
                        playMode = playMode,
                        settingsProvider = settingsProvider,
                        positionProvider = positionProvider,
                        onSeek = onSeek,
                        onToggle = onToggle,
                        onNext = onNext,
                        onPrev = onPrev,
                        onCycleMode = onCycleMode,
                        mainUnit = {
                            CoverWithDisc(
                                coverUrl = coverSrc,
                                customCover = settings.coverImage,
                                customDisc = settings.discImage,
                                isPlaying = isPlaying,
                                onSwitchStyle = toggleStyle
                            )
                        }
                    )
                } else {
                    // 3D 歌词墙（横屏）：左封面+CD / 右 3D 歌词墙
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左：封面 + 旋转CD（嵌合）+ 其正下方的紧凑控制条（占 38%）
                        // v2.25.1：整组（封面/光盘 + 控制条）垂直居中，控制条紧贴封面/光盘
                        // 视觉底缘（CoverWithDisc 布局盒已同步缩放，竖屏不再有额外空隙）
                        Column(
                            Modifier
                                .fillMaxHeight()
                                .weight(0.38f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                CoverWithDisc(
                                    coverUrl = coverSrc,
                                    customCover = settings.coverImage,
                                    customDisc = settings.discImage,
                                    isPlaying = isPlaying,
                                    onSwitchStyle = toggleStyle
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            // v2.25：控制条缩小后放封面/光盘正下方
                            CompactControls(
                                positionMs = positionMs,
                                durationMs = durationMs,
                                isPlaying = isPlaying,
                                isPreparing = isPreparing,
                                playMode = playMode,
                                onSeek = onSeek,
                                onToggle = onToggle,
                                onNext = onNext,
                                onPrev = onPrev,
                                onCycleMode = onCycleMode
                            )
                        }

                        // 右：3D 歌词墙（占 62%）
                        LyricsWallArea(
                            lyric = lyric,
                            lyricLoading = lyricLoading,
                            positionMs = positionMs,
                            settingsProvider = settingsProvider,
                            positionProvider = positionProvider,
                            onSeek = onSeek,
                            modifier = Modifier.weight(0.62f).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

// ==================== 封面 + 旋转 CD ====================

/** v2.22：唱片旋转角共享动画 —— 播放时 8s/圈匀速旋转，暂停停在当前角度。
 *  返回 Animatable（本 Compose 版本未实现 State 接口，调用方取 .value） */
@Composable
private fun rememberSpinAngle(isPlaying: Boolean): Animatable<Float, AnimationVector1D> {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                angle.animateTo(
                    angle.value + 360f,
                    tween(8000, easing = LinearEasing)
                )
                if (angle.value >= Float.MAX_VALUE / 4f) break
            }
        }
    }
    return angle
}

/**
 * v2.22：盘面图解析共享 —— 自定义光盘图片 > 自定义封面图片 > 歌曲封面
 * （在线网络/本地内嵌） > 默认封面；3D 墙 CD 与黑胶唱片机共用（v2.20.3 起
 * 与 AsyncCover 共用 CoverCache，同 URL 只下载一次）。
 */
@Composable
private fun rememberDiscArtwork(coverUrl: String?, discSrc: String?): State<Bitmap?> {
    val context = LocalContext.current
    val bmp = remember(coverUrl, discSrc) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverUrl, discSrc) {
        if (!discSrc.isNullOrEmpty()) {
            bmp.value = loadBackgroundBitmap(context, discSrc, 600)
        } else if (coverUrl.isNullOrEmpty()) {
            // 无任何封面：默认盘面用极简默认封面图（与占位封面/背景同图）
            bmp.value = runCatching {
                BitmapFactory.decodeResource(context.resources, R.drawable.default_cover)
            }.getOrNull()
        } else if (!CoverCache.isResolved(coverUrl)) {
            val loaded = loadBitmap(coverUrl)
            CoverCache.put(coverUrl, loaded)
            bmp.value = loaded
        } else {
            bmp.value = CoverCache.get(coverUrl)
        }
    }
    return bmp
}

@Composable
private fun CoverWithDisc(
    coverUrl: String?,
    customCover: String?,
    customDisc: String?,
    isPlaying: Boolean,
    /** v2.22：点击封面/光盘切到黑胶唱片机界面 */
    onSwitchStyle: () -> Unit
) {
    val cdAngle = rememberSpinAngle(isPlaying)
    val discBmp = rememberDiscArtwork(coverUrl, customDisc ?: customCover)

    // v2.22 嵌合式封面+光盘（对照新参考）：光盘中心正好落在封面右边缘上，
    // 仅探出右半圆 —— 相比旧版（中心在封面内侧 29dp）整体外拉、嵌为一体；
    // 整体（封面 190 + 光盘探出 89 ≈ 279dp）超宽时按容器宽等比缩小防溢出
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        // v2.24.1：下限 0.60 → 0.34 —— 竖屏窄列按真实容器宽等比缩小，不再硬撑溢出
        val fit = (maxWidth.value / 288f).coerceIn(0.34f, 1f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // v2.25.1：布局尺寸同步缩放（原为固定 190dp 布局 + graphicsLayer 视觉缩放，
                // 竖屏 fit≈0.4 时缩掉的约 112dp 化作上下空隙，下方控制条贴不住光盘）——
                // 布局盒改为 190*fit 后与视觉盒重合；内部子元素改用 requiredSize 锁定
                // 设计尺寸（不受父约束压缩），溢出居中、graphicsLayer 缩后恰好填满布局盒
                .requiredSize((190 * fit).dp)
                .graphicsLayer { scaleX = fit; scaleY = fit }
                // 整体视觉重心居中：嵌合体外探 89dp，回拉约半探出量
                .offset(x = (-44).dp * fit)
                .clickable(onClick = onSwitchStyle)
        ) {
            // 光盘在下层：offset 95dp = 封面半宽，即盘心压在封面右边缘
            DiscCanvas(
                angle = cdAngle.value,
                cover = discBmp.value,
                modifier = Modifier
                    .requiredSize(178.dp)
                    .offset(x = 95.dp)
            )
            // 封面卡片（压在光盘上）：v2.21 自定义封面图片优先
            if (!customCover.isNullOrEmpty()) {
                BgImage(
                    customCover,
                    Modifier
                        .requiredSize(190.dp)
                        .shadow(18.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                AsyncCover(
                    url = coverUrl,
                    modifier = Modifier
                        .requiredSize(190.dp)
                        .shadow(18.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                )
            }
            // v2.23：光盘中心圆覆盖层 —— 与盘面同位（178dp + 95dp 偏移），压在封面
            // 右缘上方，半透明玻璃质感让封面透出来（更真实）；同心圆与旋转无关，
            // 无需随盘转动；仅封面盘面分支使用（无封面银盘回退保留自身中心标贴）
            if (discBmp.value != null) {
                DiscCenterOverlay(
                    modifier = Modifier
                        .requiredSize(178.dp)
                        .offset(x = 95.dp)
                )
            }
        }
    }
}

/**
 * CD 光盘绘制（v2.20.3；v2.23 封面分支的中心圆移至 DiscCenterOverlay 覆盖层）：
 * 有封面时盘面铺封面图（圆形裁剪）+ 同心唱片暗纹 + 扫掠高光；无封面（未加载/无图）
 * 回退银色反光盘面（自带中心标贴与中孔）。
 */
@Composable
private fun DiscCanvas(angle: Float, cover: Bitmap?, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { rotationZ = angle }) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        if (cover != null) {
            // 封面居中裁剪铺满盘面（参考图3：盘面即封面图案，随 CD 一起旋转）
            val srcMin = minOf(cover.width, cover.height)
            val srcOff = IntOffset((cover.width - srcMin) / 2, (cover.height - srcMin) / 2)
            val dstR = (2 * r).roundToInt().coerceAtLeast(1)
            val discRect = Rect(center.x - r, center.y - r, center.x + r, center.y + r)
            clipPath(Path().apply { addOval(discRect) }) {
                drawImage(
                    image = cover.asImageBitmap(),
                    srcOffset = srcOff,
                    srcSize = IntSize(srcMin, srcMin),
                    dstOffset = IntOffset((center.x - r).roundToInt(), (center.y - r).roundToInt()),
                    dstSize = IntSize(dstR, dstR)
                )
                // 唱片纹理：同心暗纹（半透明，不遮封面主色）
                for (i in 1..6) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.10f),
                        radius = r * (0.94f - i * 0.10f),
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
                // 扫掠高光模拟盘面反光
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.White.copy(alpha = 0.26f), Color.Transparent,
                            Color.White.copy(alpha = 0.14f), Color.Transparent,
                            Color.Transparent, Color.White.copy(alpha = 0.26f)
                        ),
                        center
                    ),
                    radius = r,
                    center = center
                )
            }
        } else {
            // 无封面回退：银色扫掠渐变盘面
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color(0xFFE9E9EF), Color(0xFF9C9CAC), Color(0xFFEDEDF3),
                        Color(0xFF80808F), Color(0xFFE2E2EA), Color(0xFF9A9AAA),
                        Color(0xFFE9E9EF)
                    ),
                    center
                ),
                radius = r,
                center = center
            )
            for (i in 1..4) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.06f),
                    radius = r * (0.92f - i * 0.13f),
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            drawCircle(color = Color(0xFF23232B), radius = r * 0.30f, center = center)
            drawCircle(
                color = Color(0xFFEC4141).copy(alpha = 0.9f),
                radius = r * 0.20f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(color = Color(0xFF0B0B10), radius = r * 0.055f, center = center)
        }
        // 外缘描边（两种分支共用）
        drawCircle(
            color = Color.White.copy(alpha = 0.28f),
            radius = r,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

/**
 * v2.23：光盘中心圆覆盖层 —— 画在封面卡片之上、与盘面同位（178dp + 95dp 偏移），
 * 中心圆压在封面右缘上；半透明轻压暗 + 玻璃扫光 + 细环 + 透空中孔，封面从中心圆
 * 中透出，更接近真实 CD 叠在封面边上的观感（同心圆与旋转无关，无需随盘转动）。
 */
@Composable
private fun DiscCenterOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        // 标贴区：轻压暗 + 玻璃扫光（封面清晰透过）
        drawCircle(color = Color.Black.copy(alpha = 0.22f), radius = r * 0.30f, center = c)
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    Color.White.copy(alpha = 0.16f), Color.Transparent,
                    Color.Transparent, Color.White.copy(alpha = 0.10f),
                    Color.Transparent, Color.White.copy(alpha = 0.16f)
                ),
                c
            ),
            radius = r * 0.30f,
            center = c
        )
        // 标贴细环
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = r * 0.30f,
            center = c,
            style = Stroke(width = 1.dp.toPx())
        )
        // 中孔：透空（只画孔缘环，封面/盘面从中透出）
        drawCircle(
            color = Color.Black.copy(alpha = 0.40f),
            radius = r * 0.055f,
            center = c,
            style = Stroke(width = 1.dp.toPx())
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.25f),
            radius = r * 0.085f,
            center = c,
            style = Stroke(width = 0.8.dp.toPx())
        )
    }
}

// ==================== v2.22 黑胶唱片机界面 ====================

/**
 * 黑胶唱片机样式歌词页（v2.23 与默认 3D 墙同构的左右布局）：
 * - 左：旋转黑胶 + 唱针臂（播放搭在纹路上/暂停抬起），点击切回 3D 歌词墙界面；
 *   v2.25 控制条整体缩小后移到黑胶正下方（与默认界面的封面下方位置同构）；
 * - 右：与默认界面完全同一套 3D 透视歌词墙（俯仰/偏航/纵深/KTV 渐进），点击行跳转；
 * - 横屏保持左右排布（与默认界面一致），切换界面时歌词始终在右侧、主体在左侧
 *   （v1.3 起竖屏由主体处改用 [PortraitColumnBody] 上下三段式布局）；
 * - 样式经 settings.json 持久化（toggleStyle 由 Lyrics3DPage 注入）
 */
@Composable
private fun VinylBody(
    coverUrl: String?,
    customCover: String?,
    customDisc: String?,
    isPlaying: Boolean,
    lyric: LyricsDoc?,
    lyricLoading: Boolean,
    positionMs: Long,
    durationMs: Long,
    isPreparing: Boolean,
    playMode: Int,
    settingsProvider: () -> MusicSettings,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCycleMode: () -> Unit,
    onToggleStyle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左：黑胶唱片 + 其正下方的紧凑控制条（列宽占比与默认界面的封面列一致）
        // v2.25.1：整组（唱片 + 控制条）垂直居中，控制条紧贴唱片底缘
        Column(
            Modifier
                .fillMaxHeight()
                .weight(0.38f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            VinylDiscUnit(
                coverUrl = coverUrl,
                customCover = customCover,
                customDisc = customDisc,
                isPlaying = isPlaying,
                onToggleStyle = onToggleStyle
                // v2.25.1：不再 fillMaxSize —— 包裹内容尺寸随唱片实际大小，
                // 控制条才能直接贴在唱片下方（唱针臂溢出绘制不受影响）
            )
            Spacer(Modifier.height(6.dp))
            // v2.25：控制条缩小后放黑胶唱片正下方
            CompactControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                isPreparing = isPreparing,
                playMode = playMode,
                onSeek = onSeek,
                onToggle = onToggle,
                onNext = onNext,
                onPrev = onPrev,
                onCycleMode = onCycleMode
            )
        }
        // 右：3D 歌词墙（与默认界面同一套）
        LyricsWallArea(
            lyric = lyric,
            lyricLoading = lyricLoading,
            positionMs = positionMs,
            settingsProvider = settingsProvider,
            positionProvider = positionProvider,
            onSeek = onSeek,
            modifier = Modifier.weight(0.62f).fillMaxHeight()
        )
    }
}

// ==================== v1.3 竖屏上下三段式布局 ====================

/**
 * v1.3 竖屏布局 ——「封面光盘在上 / 歌词居中 / 控制条在下」三段式
 * （3D 歌词墙 / 黑胶唱片机两界面共用，替代 v1.2.1 的歌词墙垫底 OverlayBody）：
 * - 顶部：主体单元（封面+光盘嵌合体 或 黑胶唱片，经 [mainUnit] 注入）水平垂直居中，
 *   约占去掉控制条后剩余高度的 35%，点击仍可切换 3D 墙 / 黑胶界面；
 * - 中部：3D 歌词墙占据其余全部空间 —— 与横屏共用同一套 [LyricsWall]
 *   （透视/纵深/KTV 渐进/点击跳转全部一致），歌词行水平居中、长行两行折行
 *   （v1.3.1 起关闭单行滚动：行尾靠右是旧「歌词墙垫底」布局的设计）；
 *   当前行经大对称 contentPadding（42%）+ scrollToItem 自动垂直居中；
 * - 底部：紧凑控制条（模式/上一首/播放/下一首 + 进度条）全宽贴底。
 */
@Composable
private fun PortraitColumnBody(
    lyric: LyricsDoc?,
    lyricLoading: Boolean,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    playMode: Int,
    settingsProvider: () -> MusicSettings,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCycleMode: () -> Unit,
    /** 顶部主体单元：封面+光盘嵌合体（3D 墙界面）或黑胶唱片（唱片机界面） */
    mainUnit: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // 顶部：封面光盘 / 黑胶唱片（居中；约占去掉控制条后剩余空间的 35%）
        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.35f),
            contentAlignment = Alignment.Center
        ) {
            mainUnit()
        }
        // 中部：3D 歌词墙（歌词水平居中；占据顶部以下、控制条以上的全部空间）；
        // v1.3.1 关闭单行滚动 —— 行尾靠右是 v1.2.2「歌词墙垫底」布局的设计
        // （长行延伸至光盘背后），三段式中光盘已上移，歌词改回居中 + 两行折行
        LyricsWallArea(
            lyric = lyric,
            lyricLoading = lyricLoading,
            positionMs = positionMs,
            settingsProvider = settingsProvider,
            positionProvider = positionProvider,
            onSeek = onSeek,
            singleLineScroll = false,
            modifier = Modifier
                .weight(0.65f)
                .fillMaxWidth()
        )
        // 底部：紧凑控制条（按钮行在上、进度条在下，全宽贴底）
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 6.dp)
        ) {
            CompactControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                isPreparing = isPreparing,
                playMode = playMode,
                onSeek = onSeek,
                onToggle = onToggle,
                onNext = onNext,
                onPrev = onPrev,
                onCycleMode = onCycleMode
            )
        }
    }
}

/** v2.23：无歌词/加载占位（默认界面与黑胶界面共用） */
@Composable
private fun NoLyricHint(lyric: LyricsDoc?, lyricLoading: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (lyricLoading) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = if (lyricLoading) "正在获取歌词…" else "暂无歌词，请欣赏",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp
        )
    }
}

/** v2.24.1：歌词墙/无词占位区域（默认界面横竖屏与黑胶界面共用，消重复分支） */
@Composable
private fun LyricsWallArea(
    lyric: LyricsDoc?,
    lyricLoading: Boolean,
    positionMs: Long,
    settingsProvider: () -> MusicSettings,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    /** v1.2.2 竖屏歌词墙垫底布局：行尾靠右；长行单行随播放滚动，不换行 */
    singleLineScroll: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        if (lyric != null && lyric.lines.isNotEmpty()) {
            LyricsWall(
                doc = lyric,
                positionMs = positionMs,
                settingsProvider = settingsProvider,
                positionProvider = positionProvider,
                onSeek = onSeek,
                singleLineScroll = singleLineScroll,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            NoLyricHint(
                lyric = lyric,
                lyricLoading = lyricLoading,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 黑胶唱片单元：旋转盘面 + 唱针臂 + 切换轻提示；整块可点击切换界面（尺寸随容器自适应） */
@Composable
private fun VinylDiscUnit(
    coverUrl: String?,
    customCover: String?,
    customDisc: String?,
    isPlaying: Boolean,
    onToggleStyle: () -> Unit,
    modifier: Modifier = Modifier,
    /** v1.3：为正下方控制条预留的高度（dp）；竖屏上下布局中控制条已贴底，传 0f */
    reservedBottom: Float = 72f
) {
    val spin = rememberSpinAngle(isPlaying)
    val artwork = rememberDiscArtwork(coverUrl, customDisc ?: customCover)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // 盘面直径取容器宽/高的较小值留出余量；尺寸值先在直接作用域算好再进嵌套
        // lambda（maxWidth/maxHeight 不跨 composable lambda 隐式访问，v2.22.1 编译约束）
        // v2.25.1：高度按「容器高 - 底部紧凑控制条约 72dp（reservedBottom）」参与取小
        // —— 横屏矮列时唱片+控制条合计不超出列高；v1.3 竖屏上下布局传 0f（控制条已贴底）
        val discSize = minOf(maxWidth.value, maxHeight.value - reservedBottom)
            .times(0.86f)
            .coerceIn(96f, 260f)
            .dp
        Box(Modifier.size(discSize).clickable(onClick = onToggleStyle)) {
            VinylDiscCanvas(
                angle = spin.value,
                artwork = artwork.value,
                modifier = Modifier.size(discSize)
            )
            // 唱针臂：与盘面同尺寸画布，允许溢出边界绘制（Compose Canvas 默认不裁剪）
            TonearmCanvas(
                isPlaying = isPlaying,
                modifier = Modifier.size(discSize)
            )
            // 轻提示：点击唱片可切回 3D 歌词墙
            Text(
                text = "轻触唱片切换样式",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
            )
        }
    }
}

/**
 * 黑胶唱片绘制（v2.22；v2.24.1 标贴再加大 0.55r→0.72r 黑边再度收窄）：深色胶盘基底 +
 * 同心唱纹 + 两道扫掠高光；中心标贴为专辑封面（圆形裁剪，随盘旋转），含标贴细环、
 * 中孔与外缘描边。
 */
@Composable
private fun VinylDiscCanvas(angle: Float, artwork: Bitmap?, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { rotationZ = angle }) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)

        // 胶盘基底：深灰黑径向渐变
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFF2C2C33), Color(0xFF1B1B21), Color(0xFF101015)),
                c, radius = r
            ),
            radius = r,
            center = c
        )
        // 同心唱纹（细环交替明暗）
        for (i in 0..9) {
            drawCircle(
                color = if (i % 2 == 0) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.25f),
                radius = r * (0.745f + i * 0.022f),
                center = c,
                style = Stroke(width = 1.dp.toPx())
            )
        }
        // 扫掠高光：两道柔和扇形反光（模拟胶盘反光）
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    Color.White.copy(alpha = 0.10f), Color.Transparent, Color.Transparent,
                    Color.White.copy(alpha = 0.06f), Color.Transparent, Color.Transparent,
                    Color.White.copy(alpha = 0.10f)
                ),
                c
            ),
            radius = r,
            center = c
        )
        // 中心标贴：专辑封面圆形裁剪（自定义光盘图/封面图已在 rememberDiscArtwork 解析）
        // v2.24.1：标贴 0.55r → 0.72r，胶盘黑边再度收窄（用户反馈 v2.23 仍偏大）
        val lr = r * 0.72f
        if (artwork != null) {
            val srcMin = minOf(artwork.width, artwork.height)
            val srcOff = IntOffset((artwork.width - srcMin) / 2, (artwork.height - srcMin) / 2)
            val lrInt = (2 * lr).roundToInt().coerceAtLeast(1)
            clipPath(Path().apply { addOval(Rect(c.x - lr, c.y - lr, c.x + lr, c.y + lr)) }) {
                drawImage(
                    image = artwork.asImageBitmap(),
                    srcOffset = srcOff,
                    srcSize = IntSize(srcMin, srcMin),
                    dstOffset = IntOffset((c.x - lr).roundToInt(), (c.y - lr).roundToInt()),
                    dstSize = IntSize(lrInt, lrInt)
                )
            }
        } else {
            drawCircle(color = Color(0xFF23232B), radius = lr, center = c)
        }
        // 标贴细环 + 唱纹内圈亮环
        drawCircle(color = Color.White.copy(alpha = 0.25f), radius = lr, center = c, style = Stroke(1.dp.toPx()))
        drawCircle(color = Color.White.copy(alpha = 0.10f), radius = r * 0.735f, center = c, style = Stroke(1.dp.toPx()))
        // 中孔 + 外缘
        drawCircle(color = Color(0xFF0B0B10), radius = r * 0.05f, center = c)
        drawCircle(color = Color.White.copy(alpha = 0.30f), radius = r * 0.05f, center = c, style = Stroke(0.8.dp.toPx()))
        drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(1.2.dp.toPx()))
        drawCircle(color = Color.White.copy(alpha = 0.16f), radius = r - 0.6.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
    }
}

/**
 * 唱针臂（v2.22）：轴承固定在盘面右上外侧（相对盘心 +0.60D / -0.48D）。
 * 规范画法针臂沿水平向左（180°），旋转 -38.7° 后针尖落在盘面唱纹上；
 * 暂停时绕轴承顺时针抬起 26°（-12.7°）到盘缘外。含投影、配重、双段臂身、
 * 唱头与针尖、轴承高光，全 Canvas 矢量绘制。
 */
@Composable
private fun TonearmCanvas(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val armDeg by animateFloatAsState(
        targetValue = if (isPlaying) -38.7f else -12.7f,
        animationSpec = tween(450),
        label = "tonearm"
    )
    Canvas(modifier) {
        val d = size.minDimension
        val pivot = Offset(size.width / 2f + 0.60f * d, size.height / 2f - 0.48f * d)
        val armLen = 0.329f * d
        val px = 1.dp.toPx()
        rotate(degrees = armDeg, pivot = pivot) {
            val dir = Offset(-1f, 0f) // 规范方向：水平向左，rotate 已负责实际角度
            val elbow = pivot + dir * (armLen * 0.52f)
            val tip = pivot + dir * armLen
            // 投影
            drawLine(
                Color.Black.copy(alpha = 0.30f),
                pivot + Offset(2f * px, 3f * px),
                tip + Offset(2f * px, 3f * px),
                strokeWidth = 5f * px,
                cap = StrokeCap.Round
            )
            // 配重（轴承另一侧短粗段）
            drawLine(
                Color(0xFF5A5A66),
                pivot,
                pivot - dir * (0.10f * armLen),
                strokeWidth = 7f * px,
                cap = StrokeCap.Round
            )
            // 后段粗臂（轴承→肘部）银色渐变
            drawLine(
                brush = Brush.linearGradient(
                    listOf(Color(0xFFD6D6E0), Color(0xFF8E8E9C)),
                    start = pivot,
                    end = elbow
                ),
                start = pivot,
                end = elbow,
                strokeWidth = 6f * px,
                cap = StrokeCap.Round
            )
            // 前段细管（肘部→唱头）
            drawLine(Color(0xFFC2C2CE), elbow, tip, strokeWidth = 3.2f * px, cap = StrokeCap.Round)
            // 唱头（圆角小方块贴盘面）+ 针尖
            drawRoundRect(
                color = Color(0xFF3A3A46),
                topLeft = tip + Offset(-1f * px, -4.5f * px),
                size = Size(14f * px, 9f * px),
                cornerRadius = CornerRadius(3f * px)
            )
            drawLine(
                Color(0xFFEDEDF4),
                tip + Offset(4f * px, 4.5f * px),
                tip + Offset(4f * px, 7.5f * px),
                strokeWidth = 1.6f * px
            )
            // 轴承：银色外环 + 深色芯 + 左上高光点
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0xFFEDEDF5), Color(0xFF9A9AA8)),
                    pivot,
                    radius = 10f * px
                ),
                radius = 10f * px,
                center = pivot
            )
            drawCircle(Color(0xFF2E2E38), radius = 4.5f * px, center = pivot)
            drawCircle(
                Color.White.copy(alpha = 0.55f),
                radius = 1.6f * px,
                center = pivot + Offset(-3f * px, -3f * px)
            )
        }
    }
}

// ==================== 3D 歌词墙 ====================

/**
 * 3D 透视歌词墙（v2.20.3 重构；v2.21 行内左右字体差 + KTV 渐进）：
 * - 整面墙真透视：绕 X 轴俯仰（wallTiltX，默认 16° 顶部向后倒）+ 绕 Y 轴偏航
 *   （wallRotateY，默认 -14°）+ 透视相机拉近到 500*density —— 远行自然变小、
 *   行距自然收拢，朝右上角消失点汇聚，倾斜/视角滑条一动就有明显视觉反馈
 * - v2.21.1「左右字体差」：行内逐字字号渐变（lineYaw3d，%）—— 行首字符最小、
 *   行尾字符最大线性插值，所见即所得的左小右大（v2.21 的行级 rotationY 透视
 *   在窄行+远相机下肉眼不可见，已废弃）
 * - 每行仅按纵深强度做轻量额外缩小/变暗 + 朝消失点方向的横向漂移；
 *   行切换通过 animateFloatAsState 平滑过渡（可关闭）
 * - 当前行高亮颜色可调；开启 KTV 模式后按播放进度从左向右渐进出色（clipRect 扫掠）
 * - 设置在组合期直读快照 State，滑条一变直接重组生效（v2.19 机制）
 */
@Composable
private fun LyricsWall(
    doc: LyricsDoc,
    positionMs: Long,
    settingsProvider: () -> MusicSettings,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    singleLineScroll: Boolean = false,
    modifier: Modifier = Modifier
) {
    val idx = remember(positionMs, doc) { doc.indexAt(positionMs) }
    val listState = rememberLazyListState()

    // 当前行滚动到视口中部
    LaunchedEffect(idx, doc) {
        if (idx >= 0) {
            listState.animateScrollToItem(idx)
        }
    }

    // 组合期直读：俯仰/偏航/纵深任一滑条变化 → 本作用域重组 → 图层参数更新
    val wallSettings = settingsProvider()

    BoxWithConstraints(modifier) {
        // v1.3.1：0.34f → 0.42f —— scrollToItem 将当前行顶部定位在顶部 padding 下缘，
        // 上下各留 42% 使当前行块（约 16%）垂直居中；0.34f 是全屏歌词墙的偏上构图，
        // 竖屏三段式歌词区变矮后当前行明显偏高
        val padV = maxHeight * 0.42f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = padV, bottom = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 真透视：俯仰 + 偏航 + 拉近的相机（500*density，旧值 1000 透视过弱
                    // 是“视角没变化”的主因）；裁剪发生在图层内容内部，旋转后超出
                    // 原边界的部分不会被 LazyColumn 裁掉，梯形透视完整可见
                    rotationX = wallSettings.wallTiltX
                    rotationY = wallSettings.wallRotateY
                    cameraDistance = 500f * density
                }
        ) {
            itemsIndexed(doc.lines, key = { i, _ -> i }) { i, line ->
                LyricLineItem(
                    line = line,
                    distance = i - idx,
                    lineEndMs = doc.lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 6000L),
                    settingsProvider = settingsProvider,
                    positionProvider = positionProvider,
                    singleLineScroll = singleLineScroll,
                    onClick = { onSeek(line.timeMs) }
                )
            }
        }
    }
}

/**
 * v2.21.1 左右字体差：逐字字号渐变（替换 v2.21 行级 rotationY —— 窄行+远相机下不可见）。
 * 行首字符最小、行尾字符最大线性插值；diff = 最大差比例（0..0.45，来自 lineYaw3d/100）。
 * diff 为 0 或单字符时原样返回；代理对（emoji 等）合并为一个跨度避免拆散字形。
 */
private fun ltrSizedText(text: String, baseSp: Float, diff: Float): AnnotatedString {
    if (diff <= 0.005f || text.length < 2) return AnnotatedString(text)
    val n = text.length
    return buildAnnotatedString {
        append(text)
        var i = 0
        while (i < n) {
            var j = i + 1
            if (Character.isHighSurrogate(text[i]) && j < n && Character.isLowSurrogate(text[j])) j++
            val t = (i + j - 1).toFloat() / (n - 1).coerceAtLeast(1)
            addStyle(SpanStyle(fontSize = (baseSp * (1f + diff * (t - 0.5f) * 2f)).sp), i, j)
            i = j
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    distance: Int,
    lineEndMs: Long,
    settingsProvider: () -> MusicSettings,
    positionProvider: () -> Long,
    /** v1.2.2 竖屏歌词墙垫底布局：行尾靠右；长行单行随播放滚动，不换行 */
    singleLineScroll: Boolean = false,
    onClick: () -> Unit
) {
    // 组合期读取（v2.19）：字号/发光/翻译/动画开关 —— 所在作用域直读快照 State
    val settings = settingsProvider()
    // 距离做动画：切换当前行时整面墙平滑流动（可在设置中关闭）
    val animDist by animateFloatAsState(
        targetValue = distance.toFloat(),
        animationSpec = if (settings.lyricDynamic) tween(280) else snap(),
        label = "lyrDist"
    )
    val absDist = kotlin.math.abs(animDist)
    val active = distance == 0

    // v2.20.3：整墙透视已负责“远小近大”的主体效果，每行只做轻量额外收敛，
    // 彻底去掉每行 rotationX（旧版把矮行自转 = 行被压扁的“挤压感”元凶）
    val scale = if (active) 1.12f else (1f - (absDist * 0.045f)).coerceIn(0.62f, 1f)
    val lineAlpha = if (active) 1f else (1f - absDist * 0.13f).coerceIn(0.12f, 1f)

    Column(
        horizontalAlignment = if (singleLineScroll) Alignment.End else Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
            .graphicsLayer {
                // 纵深强度 tilt3d（0-45）：控制远行额外缩小/变暗与消失点漂移幅度，
                // 图层块内直读快照 State，滑条一变立即重绘（v2.19 机制）
                val s = settingsProvider()
                val k = (absDist * s.tilt3d / 120f).coerceIn(0f, 1f)
                val sc = scale * (1f - 0.30f * k)
                scaleX = sc
                scaleY = sc
                alpha = lineAlpha * (1f - 0.25f * k)
                // 朝消失点漂移（对照参考图3）：上方行向右上、下方行向左下
                //（漂移量与纵深强度联动；0°/纵深 0 时完全复原平面模式）
                translationX = (-animDist * s.tilt3d * 0.6f * density)
                    .coerceIn(-140f * density, 140f * density)
                // v2.21.1：行级 rotationY 透视已移除（窄行+远相机下肉眼不可见），
                // 左右字体差改为逐字字号渐变，见 ltrSizedText()
            }
    ) {
        if (active && settings.ktvMode) {
            // v2.21 KTV 渐进样式：未唱字用「非高亮颜色」半透明打底，已唱高亮色按播放进度从左向右扫开
            if (singleLineScroll) {
                // v1.2.2 竖屏单行滚动：扫色边界与滚动窗口同一线性进度，
                // 未唱部分藏在窗口右缘外，播放到即随扫色一并出现
                ScrollLyricLine(
                    active = active,
                    lineStartMs = line.timeMs,
                    lineEndMs = lineEndMs,
                    positionProvider = positionProvider
                ) {
                    KtvSweepText(
                        text = line.text.ifBlank { "···" },
                        fontSizeSp = settings.lyricFontSize,
                        diff = settings.lineYaw3d / 100f,
                        fillColor = Color(settings.highlightColor),
                        baseColor = Color(settings.lyricSubColor),
                        glow = settings.lyricGlow,
                        lineStartMs = line.timeMs,
                        lineEndMs = lineEndMs,
                        positionProvider = positionProvider,
                        singleLine = true
                    )
                }
            } else {
                KtvSweepText(
                    text = line.text.ifBlank { "···" },
                    fontSizeSp = settings.lyricFontSize,
                    diff = settings.lineYaw3d / 100f,
                    fillColor = Color(settings.highlightColor),
                    baseColor = Color(settings.lyricSubColor),
                    glow = settings.lyricGlow,
                    lineStartMs = line.timeMs,
                    lineEndMs = lineEndMs,
                    positionProvider = positionProvider
                )
            }
        } else if (singleLineScroll) {
            // v1.2.2 竖屏单行滚动（非 KTV 样式）：单行铺开不换行，超出右侧由滚动容器隐藏
            ScrollLyricLine(
                active = active,
                lineStartMs = line.timeMs,
                lineEndMs = lineEndMs,
                positionProvider = positionProvider
            ) {
                Text(
                    text = ltrSizedText(
                        line.text.ifBlank { "···" },
                        (if (active) settings.lyricFontSize else settings.lyricFontSize * 0.77f).toFloat(),
                        settings.lineYaw3d / 100f
                    ),
                    fontSize = if (active) settings.lyricFontSize.sp
                    else (settings.lyricFontSize * 0.77f).roundToInt().sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) Color(settings.highlightColor) else Color(settings.lyricSubColor),
                    style = if (active && settings.lyricGlow) {
                        TextStyle(
                            shadow = Shadow(
                                color = Color(settings.highlightColor).copy(alpha = 0.75f),
                                blurRadius = 22f
                            )
                        )
                    } else {
                        TextStyle.Default
                    },
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Visible
                )
            }
        } else {
            Text(
                text = ltrSizedText(
                    line.text.ifBlank { "···" },
                    (if (active) settings.lyricFontSize else settings.lyricFontSize * 0.77f).toFloat(),
                    settings.lineYaw3d / 100f
                ),
                fontSize = if (active) settings.lyricFontSize.sp
                else (settings.lyricFontSize * 0.77f).roundToInt().sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                // v2.21：当前行高亮颜色可自定义；v1.2：非当前行颜色也可自定义
                color = if (active) Color(settings.highlightColor) else Color(settings.lyricSubColor),
                style = if (active && settings.lyricGlow) {
                    TextStyle(
                        shadow = Shadow(
                            color = Color(settings.highlightColor).copy(alpha = 0.75f),
                            blurRadius = 22f
                        )
                    )
                } else {
                    TextStyle.Default
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (settings.showTranslation && !line.translation.isNullOrBlank()) {
            Text(
                text = line.translation,
                fontSize = if (active) 13.sp else 11.sp,
                // v1.2：翻译行随「非高亮歌词颜色」同色半透明（当前行更亮、非当前行更淡）
                color = Color(settings.lyricSubColor).copy(alpha = if (active) 0.72f else 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== v1.2.2 竖屏长歌词单行滚动 ====================

/**
 * v1.2.2a 竖屏长歌词单行滚动容器（歌词墙垫底布局专用）：
 * - 行尾靠右：短行（不超宽）右移贴右缘静止显示（与 1.1 一致的靠右锚定）；
 *   长行单行铺开（不换行不省略），行首从左缘起显，尾部超出右侧被裁剪隐藏；
 * - 当前行按行内播放进度逐帧左移：行起始滚动量 0（行首在左缘），行末滚动量
 *   overflow（行尾贴右缘），右侧隐藏的部分播放到即滚入出现（与 KTV 扫色
 *   同一线性进度，扫色边界始终在窗口内）；
 * - 位移经指数平滑逼近目标（激活/ seek 约 0.25s 内滑到位），切行后 220ms
 *   缓动回行首；withFrameNanos 逐帧驱动 + Animatable 状态，位移在
 *   graphicsLayer 层消费，不触发行重组；positionProvider 直读播放引擎
 *   原始位置（非 300ms tick）。
 */
@Composable
private fun ScrollLyricLine(
    active: Boolean,
    lineStartMs: Long,
    lineEndMs: Long,
    positionProvider: () -> Long,
    content: @Composable () -> Unit
) {
    var boxW by remember { mutableStateOf(0f) }
    var textW by remember { mutableStateOf(0f) }
    val overflow = (textW - boxW).coerceAtLeast(0f)
    val dx = remember { Animatable(0f) }
    LaunchedEffect(active, lineStartMs, lineEndMs, overflow) {
        if (!active || overflow <= 0.5f) {
            // 非当前行 / 短行：滚动量归零（长行回行首起显，短行无滚动）
            if (dx.value != 0f) dx.animateTo(0f, tween(220))
            return@LaunchedEffect
        }
        val span = (lineEndMs - lineStartMs).coerceAtLeast(1000L)
        while (isActive) {
            withFrameNanos { }
            val p = ((positionProvider() - lineStartMs).toFloat() / span).coerceIn(0f, 1f)
            // 目标滚动量：行起始 0（行首在左缘起显），行末 overflow（行尾贴右缘）
            val target = overflow * p
            // 指数平滑：帧率自适应地逼近目标（0.25/帧 ≈ 0.25s 内到位），收敛后免重绘
            val next = dx.value + (target - dx.value) * 0.25f
            dx.snapTo(if (kotlin.math.abs(target - next) < 0.5f) target else next)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { boxW = it.width.toFloat() }
            .clipToBounds()
    ) {
        // 内层盒子按文本真实宽度布局（unbounded 突破父宽约束），不做任何 layout
        // 对齐，位置完全交给 graphicsLayer：短行右移 (boxW-textW) 贴右缘（靠右），
        // 长行随播放左移 dx 让右侧隐藏部分滚入；超出部分由外层 clipToBounds 裁剪
        Box(
            Modifier
                .wrapContentWidth(unbounded = true)
                .graphicsLayer {
                    // 绘制期直读实时尺寸（不经组合期闭包，避免首帧错位）：
                    // 超宽 → 随播放左移滚入右侧内容；未超宽 → 右移贴右缘（靠右）
                    val ov = textW - boxW
                    translationX = if (ov > 0.5f) -dx.value
                    else (boxW - textW).coerceAtLeast(0f)
                }
                .onSizeChanged { textW = it.width.toFloat() }
        ) {
            content()
        }
    }
}

/**
 * KTV 渐进填充文本（v2.21）：底层未唱字 + 上层高亮色已唱文字，
 * clipRect 按行内播放进度从左向右扫开。
 * 50ms 节拍直读 positionProvider（MusicEngine.rawPositionMs()，MediaPlayer 原生位置，
 * 非 300ms 状态 tick），扫色平滑；状态读发生在绘制阶段，不触发重组。
 * v1.2：未唱打底色由固定白改为「非高亮歌词颜色」半透明（lyricSubColor）。
 * v1.2.2：singleLine 时真单行布局（不换行不省略），超出部分由外层滚动容器裁剪滚动。
 */
@Composable
private fun KtvSweepText(
    text: String,
    fontSizeSp: Int,
    diff: Float,
    fillColor: Color,
    /** 未唱部分打底色（非高亮歌词颜色），以固定透明度叠加 */
    baseColor: Color,
    glow: Boolean,
    lineStartMs: Long,
    lineEndMs: Long,
    positionProvider: () -> Long,
    /** v1.2.2 竖屏单行滚动：真单行（不换行不省略），超出由外层滚动容器裁剪 */
    singleLine: Boolean = false
) {
    var frac by remember(lineStartMs, lineEndMs) { mutableStateOf(0f) }
    LaunchedEffect(lineStartMs, lineEndMs) {
        val span = (lineEndMs - lineStartMs).coerceAtLeast(1L)
        while (true) {
            frac = ((positionProvider() - lineStartMs).toFloat() / span).coerceIn(0f, 1f)
            delay(50)
        }
    }
    val glowStyle = if (glow) {
        TextStyle(shadow = Shadow(color = fillColor.copy(alpha = 0.75f), blurRadius = 22f))
    } else {
        TextStyle.Default
    }
    // v2.21.1：逐字字号渐变（左右字体差），与扫色裁剪叠加；diff 变化时重建
    val sized = remember(text, diff) { ltrSizedText(text, fontSizeSp.toFloat(), diff) }
    Box {
        Text(
            text = sized,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = baseColor.copy(alpha = 0.40f),
            style = glowStyle,
            softWrap = !singleLine,
            maxLines = if (singleLine) 1 else 2,
            overflow = if (singleLine) TextOverflow.Visible else TextOverflow.Ellipsis
        )
        Text(
            text = sized,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = fillColor,
            style = glowStyle,
            softWrap = !singleLine,
            maxLines = if (singleLine) 1 else 2,
            overflow = if (singleLine) TextOverflow.Visible else TextOverflow.Ellipsis,
            modifier = Modifier.drawWithContent {
                clipRect(right = size.width * frac) { this@drawWithContent.drawContent() }
            }
        )
    }
}

// ==================== 紧凑控制条（v2.25） ====================

/**
 * 紧凑控制条（v2.25 引入；v1.3 位置与尺寸调整）：
 * - 位置：竖屏为歌词页底部全宽（v1.3 三段式贴底）；横屏仍在左列封面/光盘正下方；
 * - 尺寸：v2.25 曾为收进 38% 窄列整体缩小约 35%；v1.3 控制条回到页面级全宽后
 *   适度回调（播放键 34→40dp、前后曲 28/19→32/22dp、模式 24/14→26/15dp、
 *   进度条 20→24dp、时间 9→10sp、按钮间距 2→6dp），仍保持紧凑可点击 Icon；
 * - 内部顺序：按钮行（模式/上一首/播放/下一首）在上、进度条（已播 | 滑条 | 总时长）
 *   在下。
 */
@Composable
private fun CompactControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    playMode: Int,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCycleMode: () -> Unit
) {
    var userSeeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }
    val maxPos = durationMs.coerceAtLeast(1L).toFloat()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 0.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 按钮行：播放模式 / 上一首 / 播放暂停 / 下一首（紧凑居中，与原底部控制条同序：按钮在上）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 播放模式
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onCycleMode),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (playMode) {
                        Store.MODE_LOOP_ONE -> Icons.Filled.RepeatOne
                        Store.MODE_SHUFFLE -> Icons.Filled.Shuffle
                        else -> Icons.Filled.Repeat
                    },
                    contentDescription = "播放模式",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(15.dp)
                )
            }
            // 上一首
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPrev),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            // 播放/暂停（圆环按钮）
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            // 下一首
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNext),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // 细进度条 + 两端时间（已播 | 滑条 | 总时长），置于按钮行下方（v2.25.1 修正上下序）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fmtTime(if (userSeeking) seekPos.toLong() else positionMs),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp
            )
            Slider(
                value = if (userSeeking) seekPos else positionMs.coerceAtMost(durationMs).toFloat(),
                valueRange = 0f..maxPos,
                onValueChange = {
                    userSeeking = true
                    seekPos = it
                },
                onValueChangeFinished = {
                    onSeek(seekPos.toLong())
                    userSeeking = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.9f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .padding(horizontal = 6.dp)
            )
            Text(
                text = fmtTime(durationMs),
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp
            )
        }
    }
}
