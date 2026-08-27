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
 * 壁纸层：优先使用用户自定义壁纸（content URI），否则使用主题默认壁纸（assets）。
 *
 * 直接用 BitmapFactory 解码 assets/Uri，避免引入额外图片库依赖。
 * 解码失败时退回到深色纯色背景。
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
                context.contentResolver.openInputStream(Uri.parse(customWallpaperUri))
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
