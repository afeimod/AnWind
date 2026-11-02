package com.anwind.apps.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anwind.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云音乐共享 UI 组件（v2.17）：
 * - [Mc] 固定配色（网易云音乐 PC 版风格：白底 + 红色 #EC4141 主色 + 歌词页深色）
 * - [AsyncCover] 封面图异步加载（内存缓存 + 失败负缓存，HttpURLConnection 实现）
 * - [EqBars] 播放中行的小均衡器动画
 * - [McSearchField] 顶部搜索框（BasicTextField 定制，回车触发搜索）
 * - [McEmpty] 空状态占位
 */

// ==================== 配色 ====================

/** 网易云音乐 PC 版风格固定配色 */
object Mc {
    val red = Color(0xFFEC4141)
    val redDeep = Color(0xFFC73535)
    val bg = Color(0xFFFCFCFD)            // 主内容背景
    val sidebarBg = Color(0xFFF4F5F7)     // 侧栏背景
    val divider = Color(0xFFE8E9EC)
    val textPrimary = Color(0xFF2B2B31)
    val textSecondary = Color(0xFF6C6C74)
    val textTertiary = Color(0xFFA2A2AA)
    val hover = Color(0xFFF1F1F4)
    val selectedRow = Color(0x14EC4141)  // 当前播放行红色 8% 叠加
    val searchFieldBg = Color(0xFFEFEFF1)

    // 歌词页深色系（对应图1 3D歌词秀）
    val lyricBg = Color(0xFF0B0B10)
    val lyricDim = Color(0xFFC9C9D2)
}

// ==================== 自定义背景与动态清晰度配色（v2.21.5 / v1.2） ====================

/**
 * 主页自定义背景（纯色/渐变/图片）激活标记，由 App 根部 CompositionLocalProvider 提供。
 * v1.2 起配合 [LocalAdaptiveScheme] 使用：菜单更透明的同时按背景明暗自动保证文字可读。
 */
val LocalHomeCustomBg = compositionLocalOf { false }

/**
 * v1.2 动态清晰度配色方案：
 * - 按自定义背景的等效亮度自动切换两套方案：
 *   亮背景 → 深色实心文字 + 白色玻璃面板 + 白色柔光光晕；
 *   暗背景 → 白色实心文字 + 黑色玻璃面板 + 黑色投影光晕。
 * - 光晕（Shadow）让文字直接压在花哨背景上时也保持局部对比度（类似视频字幕描边），
 *   实现「任意壁纸、任意透明度，文字都清晰」的动态清晰度。
 *
 * @param isCustom 自定义背景是否激活（未激活 = 标准白色主题，与旧版完全一致）
 * @param luminance 背景等效亮度 0~1（null = 默认背景）
 */
data class AdaptiveScheme(
    val isCustom: Boolean,
    val isLight: Boolean,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** 大面板玻璃：顶栏/播放条/底栏/设置卡片（v1.2 再降透：白 0.35 / 黑 0.45） */
    val glass: Color,
    /** 小表面玻璃：模式芯片/搜索框/输入框/辅助按钮 */
    val glassField: Color,
    val divider: Color,
    /** 色板/单选描边 */
    val border: Color,
    /** 滑条与进度条未激活轨道 */
    val track: Color,
    /** 文字光晕：亮背景白色柔光、暗背景黑色投影；未开启自定义背景为 null */
    val shadow: Shadow?
)

/** 标准方案（未开启自定义背景 = 原白色主题） */
private val StandardScheme = AdaptiveScheme(
    isCustom = false,
    isLight = true,
    textPrimary = Mc.textPrimary,
    textSecondary = Mc.textSecondary,
    textTertiary = Mc.textTertiary,
    glass = Color.White,
    glassField = Mc.searchFieldBg,
    divider = Mc.divider,
    border = Mc.divider,
    track = Color(0xFFE5E5E8),
    shadow = null
)

/** 按自定义背景激活状态与等效亮度解析配色方案（亮度阈值 0.5） */
fun adaptiveScheme(isCustom: Boolean, luminance: Float?): AdaptiveScheme {
    if (!isCustom) return StandardScheme
    return if ((luminance ?: 0.75f) >= 0.5f) {
        AdaptiveScheme(
            isCustom = true, isLight = true,
            textPrimary = Color(0xFF15151B),
            textSecondary = Color(0xFF43434C),
            textTertiary = Color(0xFF5E5E68),
            glass = Color.White.copy(alpha = 0.35f),
            glassField = Color.White.copy(alpha = 0.42f),
            divider = Color.White.copy(alpha = 0.40f),
            border = Color.Black.copy(alpha = 0.20f),
            track = Color.Black.copy(alpha = 0.30f),
            shadow = Shadow(
                color = Color.White.copy(alpha = 0.65f),
                offset = Offset(0f, 2f),
                blurRadius = 8f
            )
        )
    } else {
        AdaptiveScheme(
            isCustom = true, isLight = false,
            textPrimary = Color.White,
            textSecondary = Color(0xFFE8E8EF),
            textTertiary = Color(0xFFCDCDD6),
            glass = Color(0xFF0E0E13).copy(alpha = 0.45f),
            glassField = Color(0xFF0E0E13).copy(alpha = 0.52f),
            divider = Color.White.copy(alpha = 0.18f),
            border = Color.White.copy(alpha = 0.35f),
            track = Color.White.copy(alpha = 0.28f),
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 2f),
                blurRadius = 8f
            )
        )
    }
}

/** 全局自适应配色方案（App 根部提供；未提供处取标准白色主题） */
val LocalAdaptiveScheme = staticCompositionLocalOf { StandardScheme }

/**
 * 位图平均亮度（0~1，约 32×32 网格取样）：
 * 用于主页自定义图片背景的明暗自适应（动态清晰度）。
 */
fun Bitmap.averageLuminance(): Float {
    var total = 0f
    var n = 0
    val stepX = (width / 32).coerceAtLeast(1)
    val stepY = (height / 32).coerceAtLeast(1)
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val c = getPixel(x, y)
            total += (0.2126f * android.graphics.Color.red(c) +
                0.7152f * android.graphics.Color.green(c) +
                0.0722f * android.graphics.Color.blue(c)) / 255f
            n++
            x += stepX
        }
        y += stepY
    }
    return if (n == 0) 1f else total / n
}

// ==================== 时间格式化 ====================

fun fmtTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

// ==================== 封面加载 ====================

/** 封面内存缓存（LRU 简化版），失败 URL 记入负缓存避免反复请求 */
object CoverCache {
    private const val MAX = 128
    private val map = LinkedHashMap<String, Bitmap?>()
    private val failed = HashSet<String>()
    private val lock = Any()

    fun get(url: String): Bitmap? = synchronized(lock) { map[url] }

    fun isResolved(url: String): Boolean = synchronized(lock) { map.containsKey(url) || failed.contains(url) }

    fun put(url: String, bmp: Bitmap?) = synchronized(lock) {
        if (bmp == null) {
            failed.add(url)
        } else {
            if (map.size >= MAX) {
                val it = map.entries.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            map[url] = bmp
        }
    }
}

/**
 * 网络加载位图，超限降采样到 maxPx 内；
 * 同时支持本地磁盘路径（内嵌封面缓存 / 自定义背景图等），非 http 一律按文件读。
 */
suspend fun loadBitmap(url: String, maxPx: Int = 600): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (!url.startsWith("http")) {
            // 本地文件：直接解码 + 降采样
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(url, opts)
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight)
            while (longest / sample > maxPx * 2) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                url, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@runCatching null
            val l = maxOf(bmp.width, bmp.height)
            if (l > maxPx) {
                val scale = maxPx.toFloat() / l
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bmp
            }
        } else {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                if (conn.responseCode !in 200..299) return@runCatching null
                val bmp = BitmapFactory.decodeStream(conn.inputStream, null, BitmapFactory.Options())
                    ?: return@runCatching null
                val longest = maxOf(bmp.width, bmp.height)
                if (longest > maxPx) {
                    val scale = maxPx.toFloat() / longest
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bmp
                }
            } finally {
                conn.disconnect()
            }
        }
    }.getOrNull()
}

// ==================== 本地歌曲内嵌封面（独立版新增） ====================

/**
 * 本地歌曲封面解析：从音频文件提取内嵌封面图（MediaMetadataRetriever），
 * 磁盘缓存（filesDir/music_covers，key 命名）+ 内存失败表避免反复提取。
 * 在线歌曲直接用网络封面 URL；本地歌无内嵌封面时返回空串（UI 显示默认封面）。
 */
object LocalCover {
    private val failed = HashSet<String>()
    private val lock = Any()

    /** 封面源解析（IO 线程）：http URL 或本地缓存文件路径；无封面返回 "" */
    suspend fun sourceFor(context: Context, song: SongInfo?): String = withContext(Dispatchers.IO) {
        if (song == null) return@withContext ""
        // 在线歌（含已下载）优先网络封面
        if (!song.isLocal && song.picUrl.isNotBlank()) return@withContext song.picUrl
        // 本地歌 / 无网络封面的歌：内嵌封面（磁盘缓存命中或新提取）
        extractCached(context, song) ?: ""
    }

    /** 提取内嵌封面并写缓存；命中缓存直接返回；无内嵌封面返回 null */
    fun extractCached(context: Context, song: SongInfo): String? {
        val file = cacheFile(context, song)
        if (file.isFile && file.length() > 0) return file.absolutePath
        synchronized(lock) { if (failed.contains(song.key)) return null }

        val picture = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                val direct = song.localPath
                val fromDownload = song.downloadedPath
                val hasSource = when {
                    !direct.isNullOrBlank() && File(direct).isFile -> {
                        mmr.setDataSource(direct); true
                    }
                    !fromDownload.isNullOrBlank() && File(fromDownload).isFile -> {
                        mmr.setDataSource(fromDownload); true
                    }
                    !song.localUri.isNullOrBlank() -> {
                        // content://（MediaStore）与 file:// 均支持
                        mmr.setDataSource(context, Uri.parse(song.localUri)); true
                    }
                    else -> false
                }
                if (hasSource) mmr.embeddedPicture else null
            } finally {
                runCatching { mmr.release() }
            }
        }.getOrNull()

        if (picture == null || picture.isEmpty()) {
            synchronized(lock) { failed.add(song.key) }
            return null
        }
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(picture)
            file.absolutePath
        }.getOrNull()
    }

    private fun cacheFile(context: Context, song: SongInfo): File =
        File(File(context.filesDir, "music_covers"), Integer.toHexString(song.key.hashCode()) + ".img")
}

/**
 * 异步封面组件（URL 版）：有图显示封面（Crop 裁剪），无图/加载中显示默认封面图。
 * @param decorative 为 true 时同样显示默认封面（歌词页背景用，视觉一致）
 */
@Composable
fun AsyncCover(
    url: String?,
    modifier: Modifier = Modifier,
    decorative: Boolean = false
) {
    var bmp by remember(url) { mutableStateOf(CoverCache.get(url ?: "")) }
    LaunchedEffect(url) {
        if (url.isNullOrEmpty()) {
            bmp = null
        } else if (!CoverCache.isResolved(url)) {
            val loaded = loadBitmap(url)
            CoverCache.put(url, loaded)
            bmp = loaded
        } else {
            bmp = CoverCache.get(url)
        }
    }
    val currentBmp = bmp
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        // 默认封面：极简深蓝渐变 + 音符（本地无内嵌封面 / 在线图未加载时）
        Image(
            painter = painterResource(R.drawable.default_cover),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

/**
 * 异步封面组件（歌曲版，独立版新增）：自动解析在线网络封面 / 本地内嵌封面，
 * 无封面时回落默认封面。列表行 / 播放条 / 歌词页封面与光盘统一走此入口。
 */
@Composable
fun AsyncCover(
    song: SongInfo?,
    modifier: Modifier = Modifier,
    decorative: Boolean = false
) {
    val context = LocalContext.current
    // 初始值：在线歌直接用网络 URL 秒出图；本地歌先显示默认封面再异步替换
    var src by remember(song?.key) {
        mutableStateOf(
            if (song != null && !song.isLocal && song.picUrl.isNotBlank()) song.picUrl else ""
        )
    }
    LaunchedEffect(song?.key, song?.localPath, song?.downloadedPath) {
        src = LocalCover.sourceFor(context, song)
    }
    AsyncCover(url = src, modifier = modifier, decorative = decorative)
}

// ==================== 均衡器动画 ====================

/** 播放中行首的小均衡器（3 根跳动红条） */
@Composable
fun EqBars(
    modifier: Modifier = Modifier,
    color: Color = Mc.red
) {
    val inf = rememberInfiniteTransition(label = "eq")
    val a1 = inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(430, easing = LinearEasing), RepeatMode.Reverse),
        label = "a1"
    )
    val a2 = inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse),
        label = "a2"
    )
    val a3 = inf.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(490, easing = LinearEasing), RepeatMode.Reverse),
        label = "a3"
    )
    Row(
        modifier = modifier.height(14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(Modifier.size(3.dp, 12.dp * a1.value).background(color))
        Box(Modifier.size(3.dp, 12.dp * a2.value).background(color))
        Box(Modifier.size(3.dp, 12.dp * a3.value).background(color))
    }
}

// ==================== 搜索框 ====================

/** 顶部搜索框：灰底圆角 + 放大镜 + 可清空，回车/搜索键触发 */
@Composable
fun McSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索歌曲、歌手、专辑"
) {
    // v1.2：自定义背景激活时搜索框用「小表面玻璃」并随背景明暗自适应，文字叠加光晕
    val sch = LocalAdaptiveScheme.current
    val fieldBg = if (sch.isCustom) sch.glassField else Mc.searchFieldBg
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(fieldBg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = if (sch.isCustom) sch.textTertiary else Mc.textTertiary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    color = if (sch.isCustom) sch.textTertiary else Mc.textTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    style = TextStyle(shadow = sch.shadow)
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = sch.textPrimary,
                    fontSize = 13.sp,
                    shadow = sch.shadow
                ),
                cursorBrush = SolidColor(Mc.red),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "清空",
                    tint = if (sch.isCustom) sch.textTertiary else Mc.textTertiary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ==================== 空状态 ====================

/** 列表空状态占位（自定义背景下文字颜色随明暗自适应） */
@Composable
fun McEmpty(text: String, modifier: Modifier = Modifier) {
    val sch = LocalAdaptiveScheme.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = if (sch.isCustom) sch.textTertiary else Color(0xFFD9D9DF),
            modifier = Modifier.size(44.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = text,
            color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
            fontSize = 13.sp,
            style = TextStyle(shadow = sch.shadow)
        )
    }
}

// ==================== 文本溢出省略 ====================

/** 单行省略文本（列表行标题/歌手共用；shadow = 自定义背景下的可读性光晕） */
@Composable
fun EllipsisText(
    text: String,
    fontSize: Int,
    color: Color,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
    modifier: Modifier = Modifier,
    shadow: Shadow? = null
) {
    Text(
        text = text,
        fontSize = fontSize.sp,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(shadow = shadow),
        modifier = modifier
    )
}

/** 滚动跑马灯文本（底栏歌名过长时滚动） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    fontSize: Int,
    color: Color,
    modifier: Modifier = Modifier,
    shadow: Shadow? = null
) {
    Text(
        text = text,
        fontSize = fontSize.sp,
        color = color,
        maxLines = 1,
        style = TextStyle(shadow = shadow),
        modifier = modifier.fillMaxWidth().basicMarquee()
    )
}

// ==================== 背景自定义（v2.18 设置中心） ====================

/**
 * 歌词秀背景渐变预设（深色系，叠加在歌词页底层）。
 * 每项为自上而下的颜色对，用于 Brush.verticalGradient。
 */
val LyricBgGradients: List<List<Color>> = listOf(
    listOf(Color(0xFF1B1B2F), Color(0xFF0B0B10)),   // 深夜蓝紫
    listOf(Color(0xFF2D1B2E), Color(0xFF0B0B10)),   // 暗夜玫瑰
    listOf(Color(0xFF12302B), Color(0xFF0B0B10)),   // 墨绿
    listOf(Color(0xFF33261A), Color(0xFF0B0B10)),   // 咖啡
    listOf(Color(0xFF101C33), Color(0xFF0B0B10)),   // 深海
    listOf(Color(0xFF1F1F1F), Color(0xFF000000))    // 石墨
)

/** 主页背景渐变预设（浅色系，替代默认白底） */
val HomeBgGradients: List<List<Color>> = listOf(
    listOf(Color(0xFFFFF5F5), Color(0xFFFDE8E8)),   // 淡樱红
    listOf(Color(0xFFF3F6FF), Color(0xFFE4EBFF)),   // 雾蓝
    listOf(Color(0xFFF2FBF4), Color(0xFFDFF2E6)),   // 薄荷绿
    listOf(Color(0xFFFFF8EC), Color(0xFFFFEDD3)),   // 奶油橙
    listOf(Color(0xFFF7F3FF), Color(0xFFE8DEFF)),   // 淡紫
    listOf(Color(0xFFF5F6F8), Color(0xFFE6E8EE))    // 岩灰
)

/** 主页背景纯色预设（浅色系） */
val HomeBgColors: List<Color> = listOf(
    Color(0xFFFCFCFD),
    Color(0xFFFFF1F1),
    Color(0xFFF0F5FF),
    Color(0xFFEFFAF2),
    Color(0xFFFFF7EA),
    Color(0xFFF4F2FF),
    Color(0xFF26262E)   // 深色模式感
)

/** 歌词秀背景纯色预设（深色系） */
val LyricBgColors: List<Color> = listOf(
    Color(0xFF0B0B10),
    Color(0xFF191922),
    Color(0xFF1B1B2F),
    Color(0xFF12251F),
    Color(0xFF2B1D1D),
    Color(0xFF000000)
)

/** 读取用户选择的背景位图（content:// URI 或绝对路径），超限降采样，IO 线程 */
suspend fun loadBackgroundBitmap(context: android.content.Context, src: String, maxPx: Int = 1280): Bitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val input = if (src.startsWith("content:")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(src))
            } else {
                java.io.FileInputStream(src)
            }
            input?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            val longest = maxOf(opts.outWidth, opts.outHeight)
            while (longest / sample > maxPx * 2) sample *= 2
            val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val input2 = if (src.startsWith("content:")) {
                context.contentResolver.openInputStream(android.net.Uri.parse(src))
            } else {
                java.io.FileInputStream(src)
            }
            input2?.use { BitmapFactory.decodeStream(it, null, opts2) }
        }.getOrNull()
    }

/**
 * 自定义背景图组件（v2.18）：根据 content:// URI 或文件路径异步加载并铺满显示。
 * 加载中 / 失败时透明（由调用方叠加兜底底色）。
 */
@Composable
fun BgImage(src: String?, modifier: Modifier = Modifier) {
    if (src.isNullOrEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    var bmp by remember(src) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(src) {
        bmp = loadBackgroundBitmap(context, src)
    }
    val current = bmp
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

/**
 * 自定义背景视频组件（v1.2 新增）：播放本地视频文件，静音 + 循环 + 无控件，
 * 等比 cover 铺满裁剪（ContentScale.Crop 等效），加载中/失败时透明由调用方兜底。
 * - TextureView + MediaPlayer：TextureView 属普通视图层，缩放/透明度/层序全兼容；
 *   不用 VideoView（其 SurfaceView 在 Android 12 以下不参与 View 变换，cover 会失效留黑边）
 * - cover 实现：按视频宽高比直接计算 TextureView 布局尺寸（短边贴边、长边溢出居中），
 *   参与真实 measure，旋转/分屏随重组即时更新，溢出由 clipToBounds 裁掉
 * - 静音：setVolume(0,0) 且不请求音频焦点，背景视频不打断/不混入音乐播放
 * - 生命周期：退后台/锁屏自动暂停省电，回前台自动续播；src 变化整体重建播放器；
 *   组件离开组合时 release 释放解码器
 * - active=false（所在页被覆盖，如主页背景被歌词页盖住）时暂停并保留最后一帧，
 *   恢复 true 时续播 —— 过渡无闪白且不后台空耗
 * - 解码失败（不支持编码等）静默降级为透明，不影响上层内容可读性
 */
@Composable
fun BgVideo(src: String?, modifier: Modifier = Modifier, active: Boolean = true) {
    if (src.isNullOrEmpty()) return
    val lifecycleOwner = LocalLifecycleOwner.current
    BoxWithConstraints(modifier) {
        // 容器像素尺寸（cover 布局计算用，旋转/分屏时随重组刷新）
        val cw = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val ch = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        key(src) {
            // 视频尺寸 / prepared / surface 就绪（驱动 cover 布局与播放控制）
            var videoSize by remember { mutableStateOf<Size?>(null) }
            var prepared by remember { mutableStateOf(false) }
            var surfaceReady by remember { mutableStateOf(false) }

            // MediaPlayer：key(src) 使 src 变化时整体重建；离开组合 release
            val player = remember {
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(src)
                        setOnPreparedListener { mp ->
                            prepared = true
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                videoSize = Size(mp.videoWidth.toFloat(), mp.videoHeight.toFloat())
                            }
                        }
                        // 解码/播放失败：吞掉系统错误弹窗，透明兜底
                        setOnErrorListener { _, _, _ -> true }
                        setLooping(true)
                        setVolume(0f, 0f)   // 静音：背景视频不干扰音乐播放
                        prepareAsync()
                    }
                }.getOrNull()
            }

            // 播放控制：active + prepared + surface 就绪 → 播放；任一不满足 → 暂停
            // （覆盖起播/切 active/被覆盖后恢复/组合重建等全部时序）
            LaunchedEffect(player, prepared, surfaceReady, active) {
                val p = player ?: return@LaunchedEffect
                if (!prepared) return@LaunchedEffect
                if (active && surfaceReady) {
                    runCatching { p.start() }
                } else {
                    runCatching { if (p.isPlaying) p.pause() }
                }
            }

            // 生命周期：退后台/锁屏暂停省电，回前台续播（仅 active 且已就绪时）
            // key 含 active：active 是重组参数，observer 闭包需随 active 变化重建，
            // 否则歌词页打开（active=false）后回前台仍会按旧值误恢复主页视频
            DisposableEffect(lifecycleOwner, player, active) {
                val obs = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE ->
                            player?.let { p -> runCatching { if (p.isPlaying) p.pause() } }
                        Lifecycle.Event.ON_RESUME ->
                            if (active && prepared && surfaceReady) {
                                player?.let { p -> runCatching { p.start() } }
                            }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            // 离开组合 / src 变化：释放解码器与播放器
            DisposableEffect(player) {
                onDispose { player?.let { p -> runCatching { p.release() } } }
            }

            // TextureView：视频尺寸未知时先铺满（首帧前 stretch 预备），已知后按
            // cover 等比给出布局尺寸并居中，溢出被 clipToBounds 裁掉；
            // isOpaque=false 让首帧前透明，不闪黑块
            Box(
                Modifier
                    .matchParentSize()
                    .clipToBounds(),
                contentAlignment = Alignment.Center
            ) {
                val vs = videoSize
                val density = LocalDensity.current
                val contentModifier = if (vs != null) {
                    val k = maxOf(cw / vs.width, ch / vs.height)
                    with(density) {
                        Modifier.size((vs.width * k).toDp(), (vs.height * k).toDp())
                    }
                } else {
                    Modifier.matchParentSize()
                }
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            isOpaque = false
                            surfaceTextureListener =
                                object : TextureView.SurfaceTextureListener {
                                    override fun onSurfaceTextureAvailable(
                                        st: android.graphics.SurfaceTexture, w: Int, h: Int
                                    ) {
                                        player?.let { p ->
                                            runCatching { p.setSurface(Surface(st)) }
                                        }
                                        surfaceReady = true
                                    }

                                    override fun onSurfaceTextureSizeChanged(
                                        st: android.graphics.SurfaceTexture, w: Int, h: Int
                                    ) {
                                    }

                                    override fun onSurfaceTextureDestroyed(
                                        st: android.graphics.SurfaceTexture
                                    ): Boolean {
                                        player?.let { p ->
                                            runCatching { p.setSurface(null) }
                                        }
                                        surfaceReady = false
                                        return true
                                    }

                                    override fun onSurfaceTextureUpdated(
                                        st: android.graphics.SurfaceTexture
                                    ) {
                                    }
                                }
                        }
                    },
                    modifier = contentModifier
                )
            }
        }
    }
}
