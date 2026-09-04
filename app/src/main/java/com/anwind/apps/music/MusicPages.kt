package com.anwind.apps.music

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
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
    onSearch: (String) -> Unit,
    results: List<SongInfo>,
    searching: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    favKeys: Set<String>,
    currentKey: String?,
    isPlaying: Boolean,
    onPlay: (SongInfo) -> Unit,
    onToggleFav: (SongInfo) -> Unit,
    onDownload: (SongInfo) -> Unit,
    downloadOf: (String) -> DownloadItem?
) {
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

        if (results.isEmpty()) {
            // 空态：热门关键词
            if (!searching && error == null) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = "热门搜索",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Mc.textPrimary
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
                                        .background(Mc.hover)
                                        .clickable {
                                            onQueryChange(kw)
                                            onSearch(kw)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(text = kw, fontSize = 12.sp, color = Mc.textSecondary)
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
                    color = Mc.textTertiary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            // 表头（对照图2：# / 标题 / 专辑 / 时长）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#", fontSize = 11.sp, color = Mc.textTertiary, modifier = Modifier.width(30.dp))
                Text("标题", fontSize = 11.sp, color = Mc.textTertiary, modifier = Modifier.weight(1f))
                Box(Modifier.weight(0.32f))
                Text("时长", fontSize = 11.sp, color = Mc.textTertiary, modifier = Modifier.width(46.dp))
                Spacer(Modifier.width(76.dp)) // 收藏+下载按钮空间
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
                        onDownload = { onDownload(song) }
                    )
                }
                // 加载更多
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
                            Text("加载更多", fontSize = 12.sp, color = Mc.textTertiary)
                        }
                    }
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
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(text = "下载管理", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Mc.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(text = "保存目录：$saveDir", fontSize = 10.sp, color = Mc.textTertiary, maxLines = 2)
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
                                url = item.song.picUrl,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                EllipsisText(
                                    text = "${item.song.name} - ${item.song.artist}",
                                    fontSize = 13,
                                    color = Mc.textPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(2.dp))
                                when {
                                    item.done -> EllipsisText(
                                        text = "已完成：${item.file?.name ?: ""}（含歌词 .lrc）",
                                        fontSize = 10,
                                        color = Mc.textTertiary
                                    )
                                    item.failed -> EllipsisText(
                                        text = "失败：${item.error ?: "未知错误"}",
                                        fontSize = 10,
                                        color = Mc.red
                                    )
                                    else -> EllipsisText(
                                        text = "下载中 ${(item.progress * 100).toInt()}%",
                                        fontSize = 10,
                                        color = Mc.textSecondary
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
                                    tint = Mc.textSecondary,
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
                                trackColor = Color(0xFFF0F0F2),
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
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Mc.textPrimary)
            Text(text = subtitle, fontSize = 11.sp, color = Mc.textTertiary)
        }
        if (secondaryText != null && onSecondary != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Mc.hover)
                    .clickable(onClick = onSecondary)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = Mc.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(text = secondaryText, fontSize = 12.sp, color = Mc.textSecondary)
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
    onDownload: (() -> Unit)?
) {
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
                    color = if (isCurrent) Mc.red else Mc.textTertiary
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        // 封面
        AsyncCover(
            url = song.picUrl.takeIf { it.isNotEmpty() },
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
                color = if (isCurrent) Mc.red else Mc.textPrimary,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(2.dp))
            EllipsisText(
                text = listOf(song.artist, song.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "未知歌手" },
                fontSize = 11,
                color = Mc.textTertiary
            )
        }
        // 收藏
        Icon(
            imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = "喜欢",
            tint = if (isFav) Mc.red else Color(0xFFC9C9CF),
            modifier = Modifier
                .size(15.dp)
                .clickable(onClick = onToggleFav)
        )
        Spacer(Modifier.width(12.dp))
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
                        tint = Color(0xFFC9C9CF),
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
            color = Mc.textTertiary,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Right
        )
    }
}

// ==================== 本地音乐扫描（MediaStore） ====================

/** 扫描本机音频（MediaStore，is_music != 0），在 IO 线程调用 */
fun queryLocalSongs(context: Context): List<SongInfo> {
    val list = mutableListOf<SongInfo>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION
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
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                list.add(
                    SongInfo(
                        id = id.toString(),
                        name = c.getString(titleCol) ?: "未知标题",
                        artist = (c.getString(artistCol) ?: "").ifBlank { "未知歌手" },
                        album = (c.getString(albumCol) ?: "").ifBlank { "未知专辑" },
                        durationMs = c.getLong(durCol),
                        picUrl = "",
                        source = SongInfo.SOURCE_LOCAL,
                        localUri = uri.toString()
                    )
                )
            }
        }
    }
    return list
}
