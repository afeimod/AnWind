package com.anwind.core.desktop

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.IOException

/**
 * 壁纸层：优先使用用户自定义壁纸（content:// 或 file:// URI），否则使用主题默认壁纸（assets）。
 *
 * 直接用 BitmapFactory 解码 assets/Uri，避免引入额外图片库依赖。
 * v2.14：支持 file:// 路径（壁纸改为从应用内文件资源管理器选择，存真实路径）；
 * ContentResolver 失败时 File 直读兜底。解码失败时退回到深色纯色背景。
 */
@Composable
fun WallpaperLayer(
    themeWallpaper: String,
    customWallpaperUri: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val painter = remember(themeWallpaper, customWallpaperUri) {
        runCatching {
            val input = if (!customWallpaperUri.isNullOrEmpty()) {
                when {
                    // v2.14：应用内文件管理器选择的真实文件路径
                    customWallpaperUri.startsWith("file://") ->
                        java.io.File(Uri.parse(customWallpaperUri).path ?: "").takeIf { it.exists() }
                            ?.inputStream()
                    // 系统 SAF 选择的历史 content URI（兼容旧数据）
                    else -> context.contentResolver.openInputStream(Uri.parse(customWallpaperUri))
                }
            } else {
                context.assets.open(themeWallpaper)
            }
            input?.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()?.let { BitmapPainter(it) }
        }.getOrNull()
    }

    Box(
        modifier = modifier.background(
            Color(0xFF1A1A2E)  // 兜底深蓝灰色
        )
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = "Wallpaper",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
