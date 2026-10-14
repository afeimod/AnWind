package com.anwind.apps.qq

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * QQ 游戏图片素材中心（assets/qgame/**）：
 *  - cards/   斗地主 54 张标准扑克牌面 + 牌背（蓝底格纹企鹅徽章）
 *  - chess/   中国象棋木纹棋盘（楚河汉界已按标准坐标烘焙）+ 14 枚楷书棋子
 *  - mahjong/ 麻将 27 张万/筒/条象牙牌面
 * 所有素材按路径解码一次并进程级缓存；位图替换 = 重制牌面，不再使用代码手绘。
 */
object QQGameAssets {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun load(context: Context, path: String): ImageBitmap =
        cache.getOrPut(path) {
            runCatching {
                context.assets.open(path).use { BitmapFactory.decodeStream(it) }.asImageBitmap()
            }.getOrElse {
                // 素材缺失时返回 1x1 透明位图兜底，绝不因 IO 异常炸掉牌桌
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }
}

/** 组合期取素材位图（按 path 记忆，同一窗口生命周期内仅解码一次） */
@Composable
fun rememberGameAsset(path: String): ImageBitmap {
    val ctx = LocalContext.current
    return remember(path) { QQGameAssets.load(ctx.applicationContext, path) }
}

/** 牌面/麻将牌这类直接铺满容器的素材图 */
@Composable
fun QQGameAssetImage(path: String, modifier: Modifier = Modifier) {
    val bmp = rememberGameAsset(path)
    Image(
        bitmap = bmp,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.FillBounds
    )
}

/** Canvas 内按目标矩形绘制素材（象棋棋盘/棋子用），自动取整并保证尺寸 >=1 */
fun DrawScope.drawAsset(image: ImageBitmap, x: Float, y: Float, w: Float, h: Float) {
    drawImage(
        image,
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium
    )
}
