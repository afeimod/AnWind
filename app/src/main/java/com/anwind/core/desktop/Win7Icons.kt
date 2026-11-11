package com.anwind.core.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Win7 拟物图标库（v3.4.5 Win7 1:1 重制）：
 *
 * 对照 Win7Simu 截图逐件自绘（不依赖 Material 扁平图标 —— 那是
 * 图A“安卓皮”的病根）。全部 Canvas 绘制，一处配色处处复用：
 *  - [Win7HardDiskIcon]：资源管理器 3D 硬盘（灰蓝金属盘体 + 顶面
 *    高光 + 正面 LED + 标签条）；
 *  - [Win7FolderIcon]：Win7 黄色文件夹（背片深一档 + 前片满色 +
 *    顶缘高光线 + 底部投影）；
 *  - [Win7DvdIcon]：可移动存储（银盘 + 中心孔 + 彩虹反光弧）。
 *
 * 视觉基准（Win7 imageres.dll 同款观感）：
 *  - 文件夹黄 #F7C46C→#E8A33D，描边 #8F6B1E；
 *  - 硬盘体 #C7D3DD→#8DA2B5，顶面亮 #E4EBF2，LED #35C24E；
 *  - 阴影统一右下 6% 黑。
 */
object Win7Icons {

    /** Win7 黄色文件夹（[size] 建议 32~48dp）。 */
    @Composable
    fun Folder(size: Dp, modifier: Modifier = Modifier) {
        Box(modifier.then(Modifier.size(size)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val s = this.size.minDimension
                val u = s / 48f               // 以 48 网格设计
                // 底部投影
                drawRoundRect(
                    Color(0x14000000),
                    topLeft = Offset(2.5f * u, 3.5f * u),
                    size = Size(43f * u, 30f * u),
                    cornerRadius = CornerRadius(2.5f * u)
                )
                // 背片（深一档 + 后侧露出）
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFFEDB95E), Color(0xFFDDA03C))
                    ),
                    topLeft = Offset(2f * u, 6f * u),
                    size = Size(44f * u, 32f * u),
                    cornerRadius = CornerRadius(2.5f * u),
                    style = Fill
                )
                drawRoundRect(
                    Color(0xFF8F6B1E),
                    topLeft = Offset(2f * u, 6f * u),
                    size = Size(44f * u, 32f * u),
                    cornerRadius = CornerRadius(2.5f * u),
                    style = Stroke(0.8f * u)
                )
                // 前片（满色，稍窄、更靠前）
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFFFCE9B8), Color(0xFFF7C46C), Color(0xFFE8A33D))
                    ),
                    topLeft = Offset(4f * u, 16f * u),
                    size = Size(40f * u, 24f * u),
                    cornerRadius = CornerRadius(2f * u)
                )
                drawRoundRect(
                    Color(0xFF9A7423),
                    topLeft = Offset(4f * u, 16f * u),
                    size = Size(40f * u, 24f * u),
                    cornerRadius = CornerRadius(2f * u),
                    style = Stroke(0.8f * u)
                )
                // 前片顶缘高光线
                drawLine(
                    Color(0xFFFFF7E0),
                    Offset(5.5f * u, 17.6f * u),
                    Offset(42.5f * u, 17.6f * u),
                    strokeWidth = 1.2f * u
                )
            }
        }
    }

    /** Win7 3D 硬盘（[size] 建议 36~44dp）。 */
    @Composable
    fun HardDisk(size: Dp, modifier: Modifier = Modifier) {
        Box(modifier.then(Modifier.size(size)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val s = this.size.minDimension
                val u = s / 48f
                // 投影
                drawRoundRect(
                    Color(0x16000000),
                    topLeft = Offset(3f * u, 4.5f * u),
                    size = Size(42f * u, 34f * u),
                    cornerRadius = CornerRadius(2.5f * u)
                )
                // 盘体（正视：灰蓝金属渐变）
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFFE4EBF2), Color(0xFFC7D3DD), Color(0xFF8DA2B5))
                    ),
                    topLeft = Offset(2f * u, 5f * u),
                    size = Size(42f * u, 33f * u),
                    cornerRadius = CornerRadius(2.5f * u)
                )
                drawRoundRect(
                    Color(0xFF5D7185),
                    topLeft = Offset(2f * u, 5f * u),
                    size = Size(42f * u, 33f * u),
                    cornerRadius = CornerRadius(2.5f * u),
                    style = Stroke(0.9f * u)
                )
                // 顶面高光带
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Color(0x66FFFFFF), Color.Transparent)
                    ),
                    topLeft = Offset(3.5f * u, 6.5f * u),
                    size = Size(39f * u, 7f * u),
                    cornerRadius = CornerRadius(1.8f * u)
                )
                // 正面下沿暗带（金属厚度）
                drawRoundRect(
                    Color(0x26334656),
                    topLeft = Offset(3f * u, 33f * u),
                    size = Size(40f * u, 4.5f * u),
                    cornerRadius = CornerRadius(1.5f * u)
                )
                // LED（右下角，绿色发光）
                drawCircle(Color(0xFF35C24E), radius = 1.8f * u, center = Offset(38.5f * u, 34f * u))
                drawCircle(
                    Color(0x6635C24E), radius = 3.4f * u,
                    center = Offset(38.5f * u, 34f * u)
                )
                // 标签条（盘体上半的浅色带）
                drawRoundRect(
                    Color(0xFFF6F9FC),
                    topLeft = Offset(8f * u, 15f * u),
                    size = Size(20f * u, 8f * u),
                    cornerRadius = CornerRadius(1.2f * u)
                )
                drawRoundRect(
                    Color(0xFF9DB0C1),
                    topLeft = Offset(8f * u, 15f * u),
                    size = Size(20f * u, 8f * u),
                    cornerRadius = CornerRadius(1.2f * u),
                    style = Stroke(0.7f * u)
                )
            }
        }
    }

    /** Win7 DVD（可移动存储；[size] 建议 36~44dp）。 */
    @Composable
    fun Dvd(size: Dp, modifier: Modifier = Modifier) {
        Box(modifier.then(Modifier.size(size)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val s = this.size.minDimension
                val u = s / 48f
                val c = Offset(24f * u, 24f * u)
                val r = 21f * u
                // 投影
                drawCircle(Color(0x14000000), radius = r, center = Offset(25f * u, 25.5f * u))
                // 盘体（银色径向）
                drawCircle(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFEDF2F7),
                            0.55f to Color(0xFFB9C6D2),
                            1.0f to Color(0xFF7E93A6)
                        ),
                        center = Offset(20f * u, 20f * u), radius = r * 1.35f
                    ),
                    radius = r, center = c
                )
                drawCircle(Color(0xFF64788A), radius = r, center = c, style = Stroke(0.9f * u))
                // 彩虹反光弧（两条细弧）
                rotate(degrees = -30f, pivot = c) {
                    drawArc(
                        Brush.sweepGradient(
                            listOf(
                                Color(0x00FFFFFF), Color(0x66D9F0FF), Color(0x55FFE9C8),
                                Color(0x00FFFFFF)
                            )
                        ),
                        startAngle = 200f, sweepAngle = 160f, useCenter = false,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(2.2f * u)
                    )
                }
                // 中心孔
                drawCircle(Color(0xFFD7E1EA), radius = 5.5f * u, center = c)
                drawCircle(Color(0xFF8FA1B2), radius = 5.5f * u, center = c, style = Stroke(0.9f * u))
                drawCircle(Color(0xFFEDF2F7), radius = 2.2f * u, center = c)
            }
        }
    }

    /** Win7 开始球内的四色旗（波浪形四格，AeroStartOrb 专用）。 */
    fun flagPathCells(u: Float): List<Pair<Rect, Color>> = listOf(
        // 以 24×24 网格：四格随 Win7 旗的轻微错位（上排左低右高、下排左低右高）
        Rect(Offset(3f * u, 9f * u), Size(8.4f * u, 8.4f * u)) to Color(0xFFED4C3C),   // 左上 红
        Rect(Offset(12.6f * u, 6.6f * u), Size(8.4f * u, 8.4f * u)) to Color(0xFF7DB93A), // 右上 绿（微抬）
        Rect(Offset(3f * u, 15.4f * u), Size(8.4f * u, 8.4f * u)) to Color(0xFF2D7DE0),  // 左下 蓝
        Rect(Offset(12.6f * u, 13f * u), Size(8.4f * u, 8.4f * u)) to Color(0xFFF7C343)  // 右下 黄
    )
}
