package com.anwind.apps.music

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 云音乐各页面（v2.17）：
 * - [SearchPage] 在线搜索（热门关键词 + 结果列表 + 加载更多）
 * - [LibraryPage] 我喜欢 / 最近播放（播放全部 + 列表）
 * - [LocalPage] 本地音乐（MediaStore 扫描）
 * - [DownloadsPage] 下载管理（进度 / 完成 / 失败重试）
 * - [SongRow] 统一歌曲行（序号/封面/标题歌手/收藏/下载/时长）
 */

// ==================== 搜索页 ====================

private val HOT_KEYWORDS = listOf("周杰伦", "林俊杰", "陈奕迅", "邓紫棋", "经典粤语", "轻音乐", "抖音热歌", "夏日限定")

@Composable
fun SearchPage(
    query: String,
    onQueryChange: (String) -> Unit,
    /** v2.24：当前搜索音源（0=酷我 / 1=简音） */
    searchSource: Int,
    /** v2.24：切换搜索音源（选择持久化） */
    onSourceChange: (Int) -> Unit,
    onSearch: (String) -> Unit,
    results: List<SongInfo>,
    searching: Boolean,
    error: String?,
    /** v2.24：是否显示“加载更多”（简音源一次返回全部结果，无分页） */
    canLoadMore: Boolean = true,
    onLoadMore: () -> Unit,
    favKeys: Set<String>,
    currentKey: String?,
    isPlaying: Boolean,
    onPlay: (SongInfo) -> Unit,
    onToggleFav: (SongInfo) -> Unit,
    onDownload: (SongInfo) -> Unit,
    // v2.21.4：直接下载该曲歌词（.lrc 存下载目录，无需播放）
    onDownloadLyric: (SongInfo) -> Unit,
    downloadOf: (String) -> DownloadItem?
) {
    // v1.2：自定义主页背景激活时热门词芯片/列表文字随明暗自适应+光晕
    val sch = LocalAdaptiveScheme.current
    Column(Modifier.fillMaxSize()) {
        // 顶部搜索框
        McSearchField(
            query = query,
            onQueryChange = onQueryChange,
            onSearch = onSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        // v2.24：搜索音源切换（酷我 / 简音），选择持久化到设置
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "音源",
                fontSize = 12.sp,
                color = if (sch.isCustom) sch.textSecondary else Mc.textSecondary,
                style = TextStyle(shadow = sch.shadow)
            )
            listOf(
                MusicSettings.SEARCH_SOURCE_KUWO to "酷我",
                MusicSettings.SEARCH_SOURCE_METING to "聚合"
            ).forEach { (src, label) ->
                val selected = searchSource == src
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                selected -> Mc.red
                                sch.isCustom -> sch.glassField
                                else -> Mc.hover
                            }
                        )
                        .clickable { onSourceChange(src) }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            selected -> Color.White
                            sch.isCustom -> sch.textPrimary
                            else -> Mc.textSecondary
                        },
                        style = TextStyle(shadow = sch.shadow)
                    )
                }
            }
            Text(
                text = if (searchSource == MusicSettings.SEARCH_SOURCE_METING)
                    "聚合音源 · 网易云/酷狗直连"
                else
                    "默认曲库",
                fontSize = 10.sp,
                color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                style = TextStyle(shadow = sch.shadow),
                maxLines = 1
            )
        }

        if (results.isEmpty()) {
            // 空态：热门关键词
            if (!searching && error == null) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "热门搜索",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = sch.textPrimary,
                        style = TextStyle(shadow = sch.shadow)
                    )
                    Spacer(Modifier.height(10.dp))
                    // 两行流式排布的简化版：分行排列
                    HOT_KEYWORDS.chunked(4).forEach { rowKeywords ->
                        Row(
                            Modifier.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowKeywords.forEach { kw ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (sch.isCustom) sch.glassField else Mc.hover)
                                        .clickable {
                                            onQueryChange(kw)
                                            onSearch(kw)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    // v1.2：芯片更透，文字实心自适应色+光晕保证可读
                                    Text(
                                        text = kw,
                                        fontSize = 12.sp,
                                        color = if (sch.isCustom) sch.textPrimary else Mc.textSecondary,
                                        style = TextStyle(shadow = sch.shadow)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (searching) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = Mc.red, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
            if (error != null) {
                Text(
                    text = error,
                    fontSize = 12.sp,
                    color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                    style = TextStyle(shadow = sch.shadow),
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            // 表头（对照图2：# / 标题 / 专辑 / 时长；v1.2 自适应+光晕）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val headerColor = if (sch.isCustom) sch.textSecondary else Mc.textTertiary
                Text("#", fontSize = 11.sp, color = headerColor, style = TextStyle(shadow = sch.shadow), modifier = Modifier.width(30.dp))
                Text("标题", fontSize = 11.sp, color = headerColor, style = TextStyle(shadow = sch.shadow), modifier = Modifier.weight(1f))
                Box(Modifier.weight(0.32f))
                Text("时长", fontSize = 11.sp, color = headerColor, style = TextStyle(shadow = sch.shadow), modifier = Modifier.width(46.dp))
                Spacer(Modifier.width(112.dp)) // 收藏+词+下载按钮空间（v2.21.4 加词芯片）
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { i, s -> "${s.key}_$i" }) { index, song ->
                    SongRow(
                        index = index + 1,
                        song = song,
                        isCurrent = song.key == currentKey,
                        isPlaying = isPlaying,
                        isFav = favKeys.contains(song.key),
                        download = downloadOf(song.key),
                        onPlay = { onPlay(song) },
                        onToggleFav = { onToggleFav(song) },
                        onDownload = { onDownload(song) },
                        onDownloadLyric = { onDownloadLyric(song) }
                    )
                }
                // 加载更多（v2.24：简音源无分页时隐藏）
                if (canLoadMore) {
                    item(key = "load_more") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onLoadMore)
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (searching) {
                                CircularProgressIndicator(color = Mc.red, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Text(
                                    "加载更多",
                                    fontSize = 12.sp,
                                    color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                                    style = TextStyle(shadow = sch.shadow)
                                )
                            }
                        }
                    }
                } else {
                    item(key = "list_footer") { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

// ==================== 歌单页（我喜欢 / 最近播放） ====================

@Composable
fun LibraryPage(
    title: String,
    songs: List<SongInfo>,
    favKeys: Set<String>,
    currentKey: String?,
    isPlaying: Boolean,
    onPlay: (SongInfo) -> Unit,
    onPlayAll: () -> Unit,
    onToggleFav: (SongInfo) -> Unit,
    onDownload: (SongInfo) -> Unit,
    downloadOf: (String) -> DownloadItem?
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = title,
            subtitle = "${songs.size} 首",
            actionText = "播放全部",
            onAction = onPlayAll
        )
        if (songs.isEmpty()) {
            McEmpty(if (title == "我喜欢") "还没有喜欢的音乐，去搜索页发现好歌吧" else "暂无播放记录")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(songs, key = { i, s -> "${s.key}_$i" }) { index, song ->
                    SongRow(
                        index = index + 1,
                        song = song,
                        isCurrent = song.key == currentKey,
                        isPlaying = isPlaying,
                        isFav = favKeys.contains(song.key),
                        download = if (song.isLocal) null else downloadOf(song.key),
                        onPlay = { onPlay(song) },
                        onToggleFav = { onToggleFav(song) },
                        onDownload = if (song.isLocal) null else ({ onDownload(song) })
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

// ==================== 本地音乐页 ====================

@Composable
fun LocalPage(
    songs: List<SongInfo>,
    scanning: Boolean,
    onScan: () -> Unit,
    favKeys: Set<String>,
    currentKey: String?,
    isPlaying: Boolean,
    onPlay: (SongInfo) -> Unit,
    onPlayAll: () -> Unit,
    onToggleFav: (SongInfo) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "本地音乐",
            subtitle = "${songs.size} 首",
            actionText = "播放全部",
            onAction = onPlayAll,
            secondaryText = if (scanning) null else "扫描",
            onSecondary = onScan
        )
        if (scanning) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = Mc.red, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        } else if (songs.isEmpty()) {
            McEmpty("未找到本机音乐，点击右上角「扫描」读取手机曲库")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(songs, key = { i, s -> "${s.key}_$i" }) { index, song ->
                    SongRow(
                        index = index + 1,
                        song = song,
                        isCurrent = song.key == currentKey,
                        isPlaying = isPlaying,
                        isFav = favKeys.contains(song.key),
                        download = null,
                        onPlay = { onPlay(song) },
                        onToggleFav = { onToggleFav(song) },
                        onDownload = null
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

// ==================== 下载管理页 ====================

@Composable
fun DownloadsPage(items: List<DownloadItem>, saveDir: String, onRetry: (DownloadItem) -> Unit) {
    // v1.2：自定义主页背景激活时进度轨道随明暗自适应
    val sch = LocalAdaptiveScheme.current
    val progressTrack = if (sch.isCustom) sch.track else Color(0xFFF0F0F2)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = "下载管理",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = sch.textPrimary,
                style = TextStyle(shadow = sch.shadow)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "保存目录：$saveDir",
                fontSize = 10.sp,
                color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                style = TextStyle(shadow = sch.shadow),
                maxLines = 2
            )
        }
        if (items.isEmpty()) {
            McEmpty("暂无下载任务，在歌曲行点击下载图标即可离线保存")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(items, key = { i, it -> "${it.song.key}_$i" }) { _, item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncCover(
                                song = item.song,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                EllipsisText(
                                    text = "${item.song.name} - ${item.song.artist}",
                                    fontSize = 13,
                                    color = sch.textPrimary,
                                    fontWeight = FontWeight.Medium,
                                    shadow = sch.shadow
                                )
                                Spacer(Modifier.height(2.dp))
                                when {
                                    item.done -> EllipsisText(
                                        text = "已完成：${item.file?.name ?: ""}（含歌词 .lrc）",
                                        fontSize = 10,
                                        color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                                        shadow = sch.shadow
                                    )
                                    item.failed -> EllipsisText(
                                        text = "失败：${item.error ?: "未知错误"}",
                                        fontSize = 10,
                                        color = Mc.red,
                                        shadow = sch.shadow
                                    )
                                    else -> EllipsisText(
                                        text = "下载中 ${(item.progress * 100).toInt()}%",
                                        fontSize = 10,
                                        color = sch.textSecondary,
                                        shadow = sch.shadow
                                    )
                                }
                            }
                            if (item.done) {
                                Icon(
                                    Icons.Filled.DownloadDone,
                                    contentDescription = "完成",
                                    tint = Mc.red,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else if (item.failed) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "重试",
                                    tint = sch.textSecondary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { onRetry(item) }
                                )
                            } else {
                                CircularProgressIndicator(
                                    color = Mc.red,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (!item.done && !item.failed) {
                            LinearProgressIndicator(
                                progress = { item.progress },
                                color = Mc.red,
                                trackColor = progressTrack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

// ==================== 页头（对照图2 标题+播放全部） ====================

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // v1.2：页头文字随背景明暗自适应+光晕
        val sch = LocalAdaptiveScheme.current
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = sch.textPrimary,
                style = TextStyle(shadow = sch.shadow)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                style = TextStyle(shadow = sch.shadow)
            )
        }
        if (secondaryText != null && onSecondary != null) {
            // v1.2：辅助按钮芯片随明暗自适应（更透+实心文字）
            val sch = LocalAdaptiveScheme.current
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (sch.isCustom) sch.glassField else Mc.hover)
                    .clickable(onClick = onSecondary)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = if (sch.isCustom) sch.textPrimary else Mc.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = secondaryText,
                    fontSize = 12.sp,
                    color = if (sch.isCustom) sch.textPrimary else Mc.textSecondary,
                    style = TextStyle(shadow = sch.shadow)
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        // 红色「播放全部」按钮
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Mc.red)
                .clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(text = actionText, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }
}

// ==================== 歌曲行（对照图2 列表行） ====================

@Composable
private fun SongRow(
    index: Int,
    song: SongInfo,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFav: Boolean,
    download: DownloadItem?,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit,
    onDownload: (() -> Unit)?,
    // v2.21.4：歌词下载动作（词芯片，仅搜索页传入；null = 不显示）
    onDownloadLyric: (() -> Unit)? = null
) {
    // v1.2：整行文字/图标随背景明暗自适应，直接压在背景上的文字加光晕
    val sch = LocalAdaptiveScheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .background(if (isCurrent) Mc.selectedRow else Color.Transparent)
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号 / 均衡器
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            if (isCurrent && isPlaying) {
                EqBars()
            } else {
                Text(
                    text = "%02d".format(index),
                    fontSize = 12.sp,
                    color = if (isCurrent) Mc.red
                            else if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                    style = TextStyle(shadow = sch.shadow)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 封面（自动解析在线网络图 / 本地内嵌封面，无图显示默认封面）
        AsyncCover(
            song = song,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(12.dp))
        // 标题 + 歌手
        Column(Modifier.weight(1f)) {
            EllipsisText(
                text = song.name,
                fontSize = 14,
                color = if (isCurrent) Mc.red else sch.textPrimary,
                fontWeight = FontWeight.Medium,
                shadow = if (isCurrent) null else sch.shadow
            )
            Spacer(Modifier.height(2.dp))
            EllipsisText(
                text = listOf(song.artist, song.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "未知歌手" },
                fontSize = 11,
                color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
                shadow = sch.shadow
            )
        }
        // 收藏
        Icon(
            imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = "喜欢",
            tint = if (isFav) Mc.red
                   else if (sch.isCustom) sch.textTertiary else Color(0xFFC9C9CF),
            modifier = Modifier
                .size(15.dp)
                .clickable(onClick = onToggleFav)
        )
        Spacer(Modifier.width(12.dp))
        // v2.21.4：歌词下载（词芯片，仅搜索页显示；文字芯片避免新增图标依赖）
        if (onDownloadLyric != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    // v1.2：芯片随明暗自适应（更透+实心文字）
                    .background(if (sch.isCustom) sch.glassField else Mc.hover)
                    .clickable(onClick = onDownloadLyric)
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "词",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (sch.isCustom) sch.textPrimary else Mc.textSecondary,
                    style = TextStyle(shadow = sch.shadow)
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        // 下载（仅在线歌曲）
        Box(Modifier.size(16.dp)) {
            when {
                onDownload == null -> {}
                download != null && !download.failed && !download.done ->
                    CircularProgressIndicator(color = Mc.red, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                download?.done == true ->
                    Icon(Icons.Filled.DownloadDone, contentDescription = "已下载", tint = Mc.red, modifier = Modifier.size(15.dp))
                else ->
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "下载",
                        tint = if (sch.isCustom) sch.textTertiary else Color(0xFFC9C9CF),
                        modifier = Modifier
                            .size(15.dp)
                            .clickable(onClick = onDownload)
                    )
            }
        }
        Spacer(Modifier.width(12.dp))
        // 时长
        Text(
            text = if (song.durationMs > 0) fmtTime(song.durationMs) else "--:--",
            fontSize = 11.sp,
            color = if (sch.isCustom) sch.textSecondary else Mc.textTertiary,
            style = TextStyle(shadow = sch.shadow),
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Right
        )
    }
}

// ==================== 本地音乐扫描（v2.18：全库 / 指定目录） ====================

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "ogg", "aac", "opus", "wma", "ape")

/**
 * 扫描本机音频（IO 线程调用）：
 * - [MusicSettings.SCAN_ALL]：MediaStore 全库扫描（is_music != 0，时长 > 30s）
 * - [MusicSettings.SCAN_DIRS]：仅遍历用户指定的目录（File 递归，
 *   依赖“所有文件访问”权限，避免把铃声/语音等无关音频扫进曲库）
 * - [downloadedPaths]：App 内下载完成的音频文件（v2.25 修复：搜索下载的音乐
 *   自动加入本地音乐库）——无论 MediaStore 是否已异步索引、扫描模式为何，
 *   下载的歌曲都会合并进结果，立即出现在「本地音乐」页
 */
fun queryLocalSongs(
    context: Context,
    scanMode: Int = MusicSettings.SCAN_ALL,
    scanDirs: List<String> = emptyList(),
    downloadedPaths: List<String> = emptyList()
): List<SongInfo> {
    val base = if (scanMode == MusicSettings.SCAN_DIRS && scanDirs.isNotEmpty()) {
        scanSpecifiedDirs(scanDirs)
    } else {
        scanMediaStore(context)
    }
    return mergeDownloadedSongs(base, downloadedPaths)
}

/**
 * 扫描结果合并 App 记录的已下载音频（v2.25）：
 * - 跳过已删除/非音频文件；按绝对路径去重（MediaStore/目录扫描已覆盖的不重复加）
 * - 下载的歌曲排在最前（最新下载在前），其余保持原扫描顺序
 */
private fun mergeDownloadedSongs(base: List<SongInfo>, downloadedPaths: List<String>): List<SongInfo> {
    if (downloadedPaths.isEmpty()) return base
    val knownPaths = base.mapNotNull { it.localPath }.toHashSet()
    val extra = mutableListOf<SongInfo>()
    for (path in downloadedPaths.asReversed()) {
        val f = File(path)
        if (!f.isFile) continue
        if (f.extension.lowercase() !in AUDIO_EXTENSIONS) continue
        if (!knownPaths.add(path)) continue
        extra.add(localSongFromFile(f))
    }
    if (extra.isEmpty()) return base
    return extra + base
}

/**
 * 由音频文件构造本地歌曲（优先读音频内真实标签：标题/歌手/专辑/时长，
 * 读取失败回退按文件名推断 "歌手 - 标题"；[mmr] 可传共享实例复用）。
 */
private fun localSongFromFile(f: File, mmr: MediaMetadataRetriever? = null): SongInfo {
    val path = f.absolutePath
    val meta = runCatching {
        val own = mmr ?: MediaMetadataRetriever()
        try {
            own.setDataSource(path)
            Triple(
                own.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                own.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                own.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            ) to own.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        } finally {
            if (mmr == null) runCatching { own.release() }
        }
    }.getOrNull()
    val fromName = parseNamesFromFileName(f.nameWithoutExtension)
    val title = meta?.first?.first?.takeIf { !it.isNullOrBlank() } ?: fromName.first
    val artist = meta?.first?.second?.takeIf { !it.isNullOrBlank() } ?: fromName.second
    val album = meta?.first?.third?.takeIf { !it.isNullOrBlank() } ?: f.parentFile?.name ?: "未知专辑"
    val duration = meta?.second?.toLongOrNull() ?: 0L
    return SongInfo(
        id = "file_$path",
        name = title,
        artist = artist,
        album = album,
        durationMs = duration,
        picUrl = "",
        source = SongInfo.SOURCE_LOCAL,
        localUri = Uri.fromFile(f).toString(),
        localPath = path
    )
}

/** MediaStore 全库扫描（读真实标签：标题/歌手/专辑/时长 + 绝对路径供内嵌封面提取） */
private fun scanMediaStore(context: Context): List<SongInfo> {
    val list = mutableListOf<SongInfo>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA
    )
    runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val path = runCatching { c.getString(dataCol) }.getOrNull()
                val fileNameTitle = path?.let { p -> File(p).nameWithoutExtension }
                val name = c.getString(titleCol)?.takeIf { it.isNotBlank() } ?: fileNameTitle ?: "未知标题"
                list.add(
                    SongInfo(
                        id = id.toString(),
                        name = name,
                        artist = (c.getString(artistCol) ?: "").ifBlank { "未知歌手" },
                        album = (c.getString(albumCol) ?: "").ifBlank { "未知专辑" },
                        durationMs = c.getLong(durCol),
                        picUrl = "",
                        source = SongInfo.SOURCE_LOCAL,
                        localUri = uri.toString(),
                        localPath = path?.takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }
    return list
}

/**
 * 指定目录扫描：递归遍历目录下的音频文件，
 * 用 MediaMetadataRetriever 读音频内真实标签（标题/歌手/专辑/时长），
 * 读取失败时回退按文件名推断（"歌手 - 标题.mp3"）。
 */
private fun scanSpecifiedDirs(dirs: List<String>): List<SongInfo> {
    val seen = HashSet<String>()
    val out = mutableListOf<SongInfo>()
    val mmr = MediaMetadataRetriever()
    try {
        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (!dir.isDirectory) continue
            runCatching {
                dir.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                    .forEach { f ->
                        val path = f.absolutePath
                        if (seen.add(path)) {
                            out.add(localSongFromFile(f, mmr))
                        }
                    }
            }
        }
    } finally {
        runCatching { mmr.release() }
    }
    return out
}

/** 从文件名推断 歌手/标题：“歌手 - 标题.mp3” 优先拆分，否则标题=文件名 */
private fun parseNamesFromFileName(base: String): Pair<String, String> {
    // v2.20：先去掉开头的曲目序号（"01." / "01_" / "01 - "），避免序号被误认为歌手
    val cleaned = base
        .replace(Regex("^\\d{1,4}\\s*[._]\\s*"), "")
        .replace(Regex("^\\d{1,4}\\s+-\\s+"), "")
        .trim()
    // 优先标准分隔："歌手 - 歌名"（含全角/长划变体）
    for (sep in listOf(" - ", " – ", " — ")) {
        val dash = cleaned.indexOf(sep)
        if (dash > 0) {
            val artist = cleaned.substring(0, dash).trim().ifBlank { "未知歌手" }
            val title = cleaned.substring(dash + sep.length).trim().ifBlank { cleaned }
            return title to artist
        }
    }
    // v2.20：兼容无空格连字符 "歌手-歌名"（中文文件名常见）
    val hyphen = cleaned.indexOf('-')
    if (hyphen > 0) {
        val artist = cleaned.substring(0, hyphen).trim()
        val title = cleaned.substring(hyphen + 1).trim()
        if (artist.isNotEmpty() && title.isNotEmpty() && artist.length <= 30) {
            return title to artist
        }
    }
    return cleaned to "未知歌手"
}
