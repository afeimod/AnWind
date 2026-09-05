package com.anwind.apps.music

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.apps.music.MusicStore as Store
import kotlin.math.roundToInt

/**
 * 3D 歌词秀（v2.17，对应需求图1；v2.19 设置即时生效重构）：
 * - 深色沉浸背景：封面大图模糊铺底 + 黑色渐变压暗
 * - 左侧：圆角封面卡片 + 右后方旋转 CD 光盘（播放时旋转，暂停即停）
 * - 右侧：3D 透视歌词墙 —— 当前行放大高亮发光，其余行按距离做
 *   rotationX 倾斜 + 缩放 + 渐隐，整面墙带 rotateY 视角；
 *   行切换时用 animateFloatAsState 平滑过渡，点击任意行跳转播放
 * - 左上角《歌名》— 歌手标题，右下角模式/上一首/播放/下一首/歌词下载控制
 *
 * v2.19 修复“倾斜/视角等设置改动不立即生效”：settings 改为 () -> MusicSettings
 * 提供者 lambda。关键点：
 * - 组合期读取（背景模式/字号/发光/翻译）：在自身重组作用域内调用 settingsProvider()，
 *   读的是快照 State，变更直接失效所在作用域，不再依赖整条参数传递链的重组；
 * - 绘制期读取（倾斜/视角）：在 graphicsLayer 块内调用 settingsProvider()，
 *   块内读快照 State 会注册绘制失效，设置一变立即重绘图层，
 *   彻底避免旧版 lambda 捕获普通对象导致的陈旧值问题。
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
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onCycleMode: () -> Unit,
    onDownloadLyric: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 组合期读取：背景模式等参与重组的设置（v2.19：所在作用域直读快照 State）
    val settings = settingsProvider()

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
            else -> {
                // 封面模糊铺底（默认，对应图1）—— v2.20 模糊半径可调（0 = 清晰不模糊）
                AsyncCover(
                    url = song?.picUrl,
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
        Column(Modifier.fillMaxSize()) {
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
            }

            // ---- 主体：左封面+CD / 右 3D 歌词墙 ----
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左：封面 + 旋转CD（占 38%）
                Box(
                    Modifier
                        .fillMaxHeight()
                        .weight(0.38f),
                    contentAlignment = Alignment.Center
                ) {
                    CoverWithDisc(
                        coverUrl = song?.picUrl,
                        isPlaying = isPlaying
                    )
                }

                // 右：3D 歌词墙（占 62%）
                Box(Modifier.weight(0.62f).fillMaxHeight()) {
                    if (lyric != null && lyric.lines.isNotEmpty()) {
                        LyricsWall(
                            doc = lyric,
                            positionMs = positionMs,
                            settingsProvider = settingsProvider,
                            onSeek = onSeek,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            Modifier.fillMaxSize(),
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
                }
            }

            // ---- 底部控制条：模式/上一首/播放/下一首 + 进度 ----
            BottomControls(
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

// ==================== 封面 + 旋转 CD ====================

@Composable
private fun CoverWithDisc(coverUrl: String?, isPlaying: Boolean) {
    val cdAngle = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // 播放时持续旋转（8s/圈），暂停时停在当前角度
            while (true) {
                cdAngle.animateTo(
                    cdAngle.value + 360f,
                    tween(8000, easing = LinearEasing)
                )
                if (cdAngle.value >= Float.MAX_VALUE / 4f) break
            }
        }
    }

    Box(contentAlignment = Alignment.Center) {
        // CD 光盘：在封面右后方
        DiscCanvas(
            angle = cdAngle.value,
            modifier = Modifier
                .size(168.dp)
                .offset(x = 52.dp)
        )
        // 封面卡片（CD 左侧，压在光盘上）
        AsyncCover(
            url = coverUrl,
            modifier = Modifier
                .size(190.dp)
                .shadow(18.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
        )
    }
}

/** CD 光盘绘制：银色扫掠渐变盘面 + 同心纹理 + 中心标贴与孔 */
@Composable
private fun DiscCanvas(angle: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.graphicsLayer { rotationZ = angle }) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // 盘面：多段扫掠渐变模拟 CD 反光
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

        // 同心纹理圈
        for (i in 1..4) {
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = r * (0.92f - i * 0.13f),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // 中心标贴（深色）+ 红色环 + 中孔
        drawCircle(color = Color(0xFF23232B), radius = r * 0.30f, center = center)
        drawCircle(
            color = Color(0xFFEC4141).copy(alpha = 0.9f),
            radius = r * 0.20f,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(color = Color(0xFF0B0B10), radius = r * 0.055f, center = center)
        // 外缘描边
        drawCircle(
            color = Color.White.copy(alpha = 0.25f),
            radius = r,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )
    }
}

// ==================== 3D 歌词墙 ====================

/**
 * 3D 透视歌词墙（v2.18 参数可调；v2.19 绘制期直读快照状态，设置即时生效）：
 * - 整面墙 rotateY 视角可调（默认 -14°，对应图1 歌词平面）
 * - 每行按与当前行的距离做 rotationX 圆弧倾斜 + 缩放 + 渐隐，强度/上限可调
 * - 行切换通过 animateFloatAsState 平滑过渡（可关闭）
 */
@Composable
private fun LyricsWall(
    doc: LyricsDoc,
    positionMs: Long,
    settingsProvider: () -> MusicSettings,
    onSeek: (Long) -> Unit,
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

    BoxWithConstraints(modifier) {
        val padV = maxHeight * 0.34f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = padV, bottom = padV),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 歌词墙整体绕 Y 轴倾斜，营造图1 的侧视角（角度可调）
                    // v2.19：绘制期读快照 State，视角滑条一变立即重绘
                    rotationY = settingsProvider().wallRotateY
                    cameraDistance = 1000f * density
                }
        ) {
            itemsIndexed(doc.lines, key = { i, _ -> i }) { i, line ->
                LyricLineItem(
                    line = line,
                    distance = i - idx,
                    settingsProvider = settingsProvider,
                    onClick = { onSeek(line.timeMs) }
                )
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    distance: Int,
    settingsProvider: () -> MusicSettings,
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

    val scale = if (active) 1.18f else (1f - (absDist * 0.05f)).coerceIn(0.78f, 1f)
    // v2.20：渐隐放缓（0.17→0.15/行，下限提高到 0.14），远处倾斜行更可见，立体感更明显
    val lineAlpha = if (active) 1f else (1f - absDist * 0.15f).coerceIn(0.14f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp)
            .graphicsLayer {
                // v2.19/v2.20：绘制期实时读取最新设置 —— 倾斜强度/最大倾角滑条一变，
                // 快照读触发图层失效重绘，立即生效（不再依赖重组传播）。
                // v2.20 立体感增强：① 透视相机 1000→700（同样角度纵深翻倍）；
                // ② 远行随倾斜强度额外缩小（景深线索，形成纵深隧道感）
                val s = settingsProvider()
                rotationX = (-animDist * s.tilt3d).coerceIn(-s.tilt3dMax, s.tilt3dMax)
                cameraDistance = 700f * density
                // v2.20.2 修复：GraphicsLayerScope（Compose UI 1.6.x）没有 translationZ 属性，
                // 纵深改用等价景深线索——远行随倾斜强度额外缩小（隧道感保留）；
                // 图层块内读倾斜快照，滑条一变立即生效（与 v2.19 机制一致）
                val depthScale = 1f - (absDist * s.tilt3d * 0.006f).coerceIn(0f, 0.22f)
                scaleX = scale * depthScale
                scaleY = scale * depthScale
                alpha = lineAlpha
            }
    ) {
        Text(
            text = line.text.ifBlank { "···" },
            fontSize = if (active) settings.lyricFontSize.sp
            else (settings.lyricFontSize * 0.77f).roundToInt().sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color.White else Color(0xFFD5D5DE),
            style = if (active && settings.lyricGlow) {
                TextStyle(
                    shadow = Shadow(
                        color = Color.White.copy(alpha = 0.75f),
                        blurRadius = 22f
                    )
                )
            } else {
                TextStyle.Default
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (settings.showTranslation && !line.translation.isNullOrBlank()) {
            Text(
                text = line.translation,
                fontSize = if (active) 13.sp else 11.sp,
                color = Color.White.copy(alpha = if (active) 0.6f else 0.35f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ==================== 底部控制条 ====================

@Composable
private fun BottomControls(
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
            .padding(horizontal = 26.dp, vertical = 10.dp)
    ) {
        // 按钮行
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 播放模式
            IconButton(onClick = onCycleMode) {
                Icon(
                    imageVector = when (playMode) {
                        Store.MODE_LOOP_ONE -> Icons.Filled.RepeatOne
                        Store.MODE_SHUFFLE -> Icons.Filled.Shuffle
                        else -> Icons.Filled.Repeat
                    },
                    contentDescription = "播放模式",
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            // 上一首
            IconButton(onClick = onPrev) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            // 播放/暂停（圆环按钮，对应图1 左下）
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            // 进度时间
            Text(
                text = "${fmtTime(positionMs)} / ${fmtTime(durationMs)}",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
        }

        // 细进度条
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
                .fillMaxWidth()
                .height(22.dp)
        )
    }
}
