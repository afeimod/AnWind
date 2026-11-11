package com.anwind.apps.filemanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import android.os.StatFs

/**
 * v3.3.42（用户问题 2：Windows 7 界面 1:1 复刻）——
 * Windows 7 资源管理器（Aero）视图组件。
 *
 * 【目标截图】（用户提供的 Win7 Simu 参考图）里的「计算机」窗口：
 *   ┌───────────────────────────────────────────────────────────────┐
 *   │  [◀] [▶]   [🖥 ▶ 计算机            ▾]        [🔍 搜索 计算机] │ ← 命令行
 *   ├───────────────────────────────────────────────────────────────┤
 *   │ 组织 ▾                                        系统属性  卸载/更改程序 │ ← 组织栏
 *   ├──────────────┬────────────────────────────────────────────────┤
 *   │ ★ 快速访问   │  ▾ 硬盘 (2) ──────────────────────────────────  │
 *   │   桌面       │     [盘] Android (A:)                            │
 *   │   下载       │     ▬▬▬▬▬▬▬▭▭▭  54.2 GB 可用（共 462.4 GB）     │
 *   │   文件       │     [盘] System (C:)                              │
 *   │ ▾ 计算机     │     ▬▬▬▬▭▭▭▭▭  10.0 GB 可用（共 10.0 GB）       │
 *   │   Android A: │  ▾ 可移动存储设备 (1) ────────────────────────  │
 *   │   System C:  │     [光驱] DVD RW Drive (Z:)                     │
 *   └──────────────┴────────────────────────────────────────────────┘
 *
 * 【为什么单独成文件】文件资源管理器的新式（Win10/11）版式保持不动 ——
 * Win7 主题（windowChrome = AERO）下切换到本组件，其余主题维持原样，
 * 两套版式互不干扰。
 *
 * 【配色】取自 Win7 Aero 实际观感：
 *   - 命令行按钮：径向高光玻璃圆钮（#FFFFFF → #DCECF9 → #A8C9E8）
 *     + 1px 边框 #7FA6CE + 深蓝箭头 #1B4E86（禁用态整体灰化）
 *   - 地址栏 / 搜索框：白底 + 1px 边框 #AEC6DE
 *   - 组织栏：#FFFFFF → #EFEFEF 渐变 + 底部 1px #D6D6D6
 *   - 导航树选中项：#D5E6F7 底 + #7EB3E8 边（未获焦的 Win7 选中观感）
 *   - 容量条：已用段 #4FC3F7（青）/ 剩余段 #E8E8E8
 */

/** Win7 Aero 配色常量（与系统主题解耦，便于统一微调）。 */
private object Win7Aero {
    val RoundBtnTop = Color(0xFFFFFFFF)
    val RoundBtnMid = Color(0xFFDDEBFA)
    val RoundBtnBottom = Color(0xFFA9C9E9)
    val RoundBtnBorder = Color(0xFF7FA6CE)
    val Arrow = Color(0xFF1B4E86)
    val ArrowDisabled = Color(0xFFA6A6A6)
    val FieldBorder = Color(0xFFAEC6DE)
    val FieldBg = Color(0xFFFFFFFF)
    val FieldHint = Color(0xFF8C8C8C)
    val BandTop = Color(0xFFFFFFFF)
    val BandBottom = Color(0xFFEFEFEF)
    val BandBorder = Color(0xFFD6D6D6)
    val NavSelectedBg = Color(0xFFD5E6F7)
    val NavSelectedBorder = Color(0xFF7EB3E8)
    val GroupLine = Color(0xFF9DB7D5)
    val CapacityUsed = Color(0xFF4FC3F7)
    val CapacityFree = Color(0xFFE8E8E8)
    val TextPrimary = Color(0xFF1A1A1A)
    val TextSecondary = Color(0xFF6E6E6E)
    val TreeGlyph = Color(0xFF6F8FAE)
}

/**
 * Win7 命令行：Aero 圆形「后退 / 前进」按钮 + 地址栏 + 搜索框。
 */
@Composable
internal fun Win7CommandRow(
    addressText: String,
    canBack: Boolean,
    canForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    searchHint: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AeroRoundButton(enabled = canBack, back = true, onClick = onBack)
        Spacer(Modifier.width(4.dp))
        AeroRoundButton(enabled = canForward, back = false, onClick = onForward)
        Spacer(Modifier.width(8.dp))

        // 地址栏（Win7：白底圆角框 + 图标 + 面包屑 + 右端下拉箭头）
        Row(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Win7Aero.FieldBg)
                .border(1.dp, Win7Aero.FieldBorder, RoundedCornerShape(2.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 小显示器图标（Win7 地址栏的"计算机"图标）
            Box(
                modifier = Modifier
                    .size(13.dp)
                    .drawBehind {
                        val w = size.width * 0.86f
                        val h = size.height * 0.62f
                        drawRect(
                            color = Win7Aero.TreeGlyph,
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.06f),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = Win7Aero.TreeGlyph,
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.06f + h),
                            size = Size(w, size.height * 0.16f)
                        )
                    }
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = addressText,
                color = Win7Aero.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 右端下拉箭头（Win7 地址栏右侧的展开三角）
            Text(
                text = "▾",
                color = Win7Aero.TextSecondary,
                fontSize = 9.sp
            )
        }

        Spacer(Modifier.width(8.dp))

        // 搜索框（Win7：白底圆角框 + 放大镜 + 提示文字）
        Row(
            modifier = Modifier
                .width(190.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Win7Aero.FieldBg)
                .border(1.dp, Win7Aero.FieldBorder, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = searchHint,
                color = Win7Aero.FieldHint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 放大镜（Win7 搜索框右端）
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .drawBehind {
                        val r = size.minDimension * 0.34f
                        drawCircle(
                            color = Win7Aero.TreeGlyph,
                            radius = r,
                            center = Offset(size.width * 0.42f, size.height * 0.42f),
                            style = Stroke(width = 1.2f)
                        )
                        drawLine(
                            color = Win7Aero.TreeGlyph,
                            start = Offset(size.width * 0.62f, size.height * 0.62f),
                            end = Offset(size.width * 0.95f, size.height * 0.95f),
                            strokeWidth = 1.4f
                        )
                    }
            )
        }
    }
}

/**
 * Win7 Aero 圆形「后退 / 前进」按钮（玻璃圆钮 + 深蓝箭头）。
 */
@Composable
private fun AeroRoundButton(
    enabled: Boolean,
    back: Boolean,
    onClick: () -> Unit
) {
    val size = 30.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Win7Aero.RoundBtnTop,
                        Win7Aero.RoundBtnMid,
                        Win7Aero.RoundBtnBottom
                    )
                )
            )
            .border(1.dp, if (enabled) Win7Aero.RoundBtnBorder else Color(0xFFBFBFBF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .drawBehind {
                // 箭头（Win7 的大三角箭头，指向左/右）
                val c = size.minDimension / 2f
                val arrow = if (enabled) Win7Aero.Arrow else Win7Aero.ArrowDisabled
                val w = size.minDimension * 0.30f
                val h = size.minDimension * 0.38f
                val path = Path()
                if (back) {
                    path.moveTo(c + w * 0.35f, c - h / 2f)
                    path.lineTo(c + w * 0.35f, c + h / 2f)
                    path.lineTo(c - w * 0.45f, c)
                } else {
                    path.moveTo(c - w * 0.35f, c - h / 2f)
                    path.lineTo(c - w * 0.35f, c + h / 2f)
                    path.lineTo(c + w * 0.45f, c)
                }
                path.close()
                drawPath(path, arrow)
            },
        contentAlignment = Alignment.Center
    ) {}
}

/**
 * Win7「组织」工具栏带（组织 ▾ + 右侧 系统属性 / 卸载、更改程序）。
 */
@Composable
internal fun Win7OrganizeBar(
    onOrganize: () -> Unit,
    onSystemProperties: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(25.dp)
            .background(
                Brush.verticalGradient(listOf(Win7Aero.BandTop, Win7Aero.BandBottom))
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 「组织 ▾」（Win7 的组织菜单入口）
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable { onOrganize() }
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "组织",
                color = Win7Aero.TextPrimary,
                fontSize = 12.sp
            )
            Spacer(Modifier.width(3.dp))
            Text(text = "▾", color = Win7Aero.TextPrimary, fontSize = 8.sp)
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = "系统属性",
            color = Win7Aero.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable { onSystemProperties() }
                .padding(horizontal = 6.dp, vertical = 1.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "卸载/更改程序",
            color = Win7Aero.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(2.dp))
                .clickable { onSystemProperties() }
                .padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
    // 底部分隔线（Win7 组织栏下方 1px 灰线）
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Win7Aero.BandBorder)
    )
}

/** 导航树条目模型。 */
internal data class Win7NavItem(
    val label: String,
    /** 图标种类：folder / desktop / drive / optical / computer / star。 */
    val icon: Win7NavIcon,
    /** 可展开的子节点（Win7 树的展开箭头）。 */
    val children: List<Win7NavItem> = emptyList(),
    /** 选中态。 */
    val selected: Boolean = false,
    /** 点击回调（null = 不可点）。 */
    val onClick: (() -> Unit)? = null
)

/** 导航树图标种类。 */
internal enum class Win7NavIcon { FOLDER, DESKTOP, DRIVE, OPTICAL, COMPUTER, STAR }

/**
 * Win7 左侧导航树（快速访问 / 计算机 两组，可展开）。
 */
@Composable
internal fun Win7NavPane(
    items: List<Win7NavItem>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(Color(0xFFFFFFFF))
            .padding(top = 4.dp)
    ) {
        items.forEach { item ->
            Win7NavNode(item = item, depth = 0)
        }
    }
}

@Composable
private fun Win7NavNode(item: Win7NavItem, depth: Int) {
    val indent = 6.dp + depth * 13.dp
    val selectable = item.onClick != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent, end = 4.dp, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (item.selected) Win7Aero.NavSelectedBg else Color.Transparent)
            .then(
                if (item.selected)
                    Modifier.border(1.dp, Win7Aero.NavSelectedBorder, RoundedCornerShape(2.dp))
                else Modifier
            )
            .then(if (selectable) Modifier.clickable { item.onClick?.invoke() } else Modifier)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 展开箭头（Win7 树的 ▸ / ▾）
        if (item.children.isNotEmpty()) {
            Text(
                text = "▾",
                color = Win7Aero.TextSecondary,
                fontSize = 8.sp,
                modifier = Modifier.width(9.dp)
            )
        } else {
            Spacer(Modifier.width(9.dp))
        }
        Spacer(Modifier.width(3.dp))
        Win7NavIconView(item.icon)
        Spacer(Modifier.width(5.dp))
        Text(
            text = item.label,
            color = Win7Aero.TextPrimary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    item.children.forEach { child ->
        Win7NavNode(item = child, depth = depth + 1)
    }
}

/** 导航树小图标（矢量绘制，避免位图依赖）。 */
@Composable
private fun Win7NavIconView(icon: Win7NavIcon) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .drawBehind {
                when (icon) {
                    Win7NavIcon.FOLDER -> {
                        val w = size.width * 0.86f
                        val h = size.height * 0.62f
                        drawRect(
                            color = Color(0xFFF5C242),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.3f),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = Color(0xFFE0A82E),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.16f),
                            size = Size(w * 0.42f, size.height * 0.16f)
                        )
                    }
                    Win7NavIcon.DESKTOP -> {
                        val w = size.width * 0.9f
                        val h = size.height * 0.6f
                        drawRect(
                            color = Color(0xFF7FB2E5),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.1f),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = Color(0xFF4A7FB5),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.7f),
                            size = Size(w, size.height * 0.16f)
                        )
                    }
                    Win7NavIcon.DRIVE -> {
                        val w = size.width * 0.94f
                        val h = size.height * 0.44f
                        drawRect(
                            color = Color(0xFFD8DEE6),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.34f),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = Color(0xFF98A4B4),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.34f),
                            size = Size(w, h * 0.35f)
                        )
                    }
                    Win7NavIcon.OPTICAL -> {
                        val w = size.width * 0.9f
                        val h = size.height * 0.42f
                        drawRect(
                            color = Color(0xFFCBD3DD),
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.3f),
                            size = Size(w, h)
                        )
                        drawCircle(
                            color = Color(0xFF7E8B9C),
                            radius = size.minDimension * 0.13f,
                            center = Offset(size.width * 0.5f, size.height * 0.51f)
                        )
                    }
                    Win7NavIcon.COMPUTER -> {
                        val w = size.width * 0.86f
                        val h = size.height * 0.6f
                        drawRect(
                            color = Win7Aero.TreeGlyph,
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.08f),
                            size = Size(w, h)
                        )
                        drawRect(
                            color = Win7Aero.TreeGlyph,
                            topLeft = Offset((size.width - w) / 2f, size.height * 0.08f + h),
                            size = Size(w, size.height * 0.18f)
                        )
                    }
                    Win7NavIcon.STAR -> {
                        val r = size.minDimension * 0.42f
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val path = Path()
                        for (i in 0 until 10) {
                            val rad = if (i % 2 == 0) r else r * 0.42f
                            val ang = Math.PI / 2.0 + i * Math.PI / 5.0
                            val px = c.x + (rad * kotlin.math.cos(ang)).toFloat()
                            val py = c.y - (rad * kotlin.math.sin(ang)).toFloat()
                            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                        }
                        path.close()
                        drawPath(path, Color(0xFFE8A33D))
                    }
                }
            }
    )
}

/**
 * Win7「硬盘 / 可移动存储设备」分组（标题 + 贯穿横线 + 驱动器条目）。
 */
@Composable
internal fun Win7DriveGroup(
    title: String,
    drives: List<Win7DriveEntry>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "▾",
                color = Win7Aero.TextSecondary,
                fontSize = 8.sp,
                modifier = Modifier.width(10.dp)
            )
            Text(
                text = title,
                color = Win7Aero.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(Modifier.width(6.dp))
            // Win7 分组标题后的贯穿横线
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Win7Aero.GroupLine)
            )
        }
        Spacer(Modifier.height(6.dp))
        drives.forEach { drive ->
            Win7DriveRow(drive)
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Win7 驱动器条目模型。 */
internal data class Win7DriveEntry(
    val label: String,
    /** 磁盘图标（硬盘 / 光驱）。 */
    val optical: Boolean = false,
    /** 可用空间字节。 */
    val freeBytes: Long,
    /** 总空间字节。 */
    val totalBytes: Long,
    val onClick: () -> Unit
)

/**
 * Win7 驱动器条目：图标 + 名称 + 容量条 + 容量文字。
 */
@Composable
private fun Win7DriveRow(drive: Win7DriveEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 驱动器图标（Win7 的立体硬盘/光驱图标）
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .drawBehind {
                        val w = size.width * 0.8f
                        val h = size.height * 0.42f
                        val left = (size.width - w) / 2f
                        val top = size.height * 0.36f
                        // 机箱主体
                        drawRect(
                            color = Color(0xFFE3E8EF),
                            topLeft = Offset(left, top),
                            size = Size(w, h)
                        )
                        // 顶面
                        drawPath(
                            Path().apply {
                                moveTo(left, top)
                                lineTo(left + w * 0.16f, top - h * 0.28f)
                                lineTo(left + w * 1.16f, top - h * 0.28f)
                                lineTo(left + w, top)
                                close()
                            },
                            color = Color(0xFFF6F8FB)
                        )
                        // 前面板
                        drawRect(
                            color = Color(0xFFB9C3D0),
                            topLeft = Offset(left, top + h * 0.62f),
                            size = Size(w, h * 0.38f)
                        )
                        if (drive.optical) {
                            // 光驱托盘孔
                            drawCircle(
                                color = Color(0xFF8E9AAB),
                                radius = size.minDimension * 0.09f,
                                center = Offset(size.width * 0.62f, size.height * 0.55f)
                            )
                        } else {
                            // 硬盘指示灯
                            drawCircle(
                                color = Color(0xFF63C17A),
                                radius = size.minDimension * 0.035f,
                                center = Offset(size.width * 0.22f, size.height * 0.78f)
                            )
                        }
                    }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = drive.label,
                color = Win7Aero.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(4.dp))
        // 容量条（Win7：细圆角条，已用段青色 + 剩余段浅灰）
        val total = drive.totalBytes.coerceAtLeast(1L)
        val used = (drive.totalBytes - drive.freeBytes).coerceIn(0L, drive.totalBytes)
        val ratio = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Win7Aero.CapacityFree)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Win7Aero.CapacityUsed)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "${formatWin7Size(drive.freeBytes)} 可用（共 ${formatWin7Size(drive.totalBytes)}）",
            color = Win7Aero.TextSecondary,
            fontSize = 11.sp
        )
    }
}

/** Win7 风格容量文本（保留一位小数，GB/MB 自动切换）。 */
internal fun formatWin7Size(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val v = bytes.toDouble()
    return when {
        v >= gb -> String.format("%.1f GB", v / gb)
        v >= mb -> String.format("%.1f MB", v / mb)
        else -> String.format("%.0f KB", v / kb)
    }
}

/**
 * 读取路径所在卷的可用/总空间（StatFs；失败返回 0）。
 */
internal fun win7DriveSpace(path: File): Pair<Long, Long> = runCatching {
    val stat = StatFs(path.absolutePath)
    val blockSize = stat.blockSizeLong
    val avail = stat.availableBlocksLong * blockSize
    val total = stat.blockCountLong * blockSize
    avail to total
}.getOrElse { 0L to 0L }

/**
 * v3.3.42：Win7「计算机」主面板 —— 硬盘 / 可移动存储设备 分组 + 容量条。
 *
 * 分组对应 Win7 计算机窗口：
 *   硬盘 (2)          —— Android (A:)（共享存储）+ System (C:)（应用内部存储）
 *   可移动存储设备 (2) —— Download (D:) + exagear (E:)（外置存储子目录）
 */
@Composable
internal fun Win7ThisPcPane(
    storageRoot: File,
    internalFiles: File,
    driveD: File,
    driveE: File,
    hasAllFilesAccess: Boolean,
    onRequestPermission: () -> Unit,
    onOpenFolder: (File) -> Unit
) {
    val scrollState = rememberScrollState()
    val android = storageRoot
    val system = internalFiles

    // 容量数据（StatFs；无权限时为 0 → Win7 同款显示 0.0 GB）
    val androidSpace = remember(android.absolutePath) { win7DriveSpace(android) }
    val systemSpace = remember(system.absolutePath) { win7DriveSpace(system) }
    val driveDSpace = remember(driveD.absolutePath) { win7DriveSpace(driveD) }
    val driveESpace = remember(driveE.absolutePath) { win7DriveSpace(driveE) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Win7DriveGroup(
            title = "硬盘 (2)",
            drives = listOf(
                Win7DriveEntry(
                    label = "Android (A:)",
                    freeBytes = androidSpace.first,
                    totalBytes = androidSpace.second,
                    onClick = {
                        if (hasAllFilesAccess) onOpenFolder(android) else onRequestPermission()
                    }
                ),
                Win7DriveEntry(
                    label = "System (C:)",
                    freeBytes = systemSpace.first,
                    totalBytes = systemSpace.second,
                    onClick = { onOpenFolder(system) }
                )
            )
        )
        Spacer(Modifier.height(10.dp))
        Win7DriveGroup(
            title = "可移动存储设备 (2)",
            drives = listOf(
                Win7DriveEntry(
                    label = "Download (D:)",
                    freeBytes = driveDSpace.first,
                    totalBytes = driveDSpace.second,
                    onClick = {
                        if (hasAllFilesAccess) onOpenFolder(driveD) else onRequestPermission()
                    }
                ),
                Win7DriveEntry(
                    label = "exagear (E:)",
                    optical = true,
                    freeBytes = driveESpace.first,
                    totalBytes = driveESpace.second,
                    onClick = {
                        if (!hasAllFilesAccess) { onRequestPermission(); return@Win7DriveEntry }
                        if (!driveE.isDirectory) runCatching { driveE.mkdirs() }
                        onOpenFolder(driveE)
                    }
                )
            )
        )
        Spacer(Modifier.height(14.dp))
        // Win7 计算机窗口底部：文件系统类型说明
        Text(
            text = "文件系统：内部存储（应用私有）· 外部存储（共享）",
            color = Win7Aero.TextSecondary,
            fontSize = 11.sp
        )
    }
}

/**
 * v3.3.42：构造 Win7 左侧导航树（快速访问 / 计算机 两组）。
 */
internal fun buildWin7NavItems(
    isThisPcHome: Boolean,
    currentPath: String?,
    storageRoot: File,
    internalFiles: File,
    driveD: File,
    driveE: File,
    onHome: () -> Unit,
    onOpen: (File) -> Unit
): List<Win7NavItem> {
    val isSel = { f: File -> !isThisPcHome && currentPath == f.absolutePath }
    return listOf(
        Win7NavItem(
            label = "快速访问",
            icon = Win7NavIcon.STAR,
            children = listOf(
                Win7NavItem("下载", Win7NavIcon.FOLDER, onClick = { onOpen(driveD) }),
                Win7NavItem("文档", Win7NavIcon.FOLDER, onClick = { onOpen(File(storageRoot, "Documents")) }),
                Win7NavItem("图片", Win7NavIcon.FOLDER, onClick = { onOpen(File(storageRoot, "Pictures")) }),
                Win7NavItem("音乐", Win7NavIcon.FOLDER, onClick = { onOpen(File(storageRoot, "Music")) }),
                Win7NavItem("视频", Win7NavIcon.FOLDER, onClick = { onOpen(File(storageRoot, "Movies")) })
            )
        ),
        Win7NavItem(
            label = "计算机",
            icon = Win7NavIcon.COMPUTER,
            selected = isThisPcHome,
            onClick = onHome,
            children = listOf(
                Win7NavItem("Android (A:)", Win7NavIcon.DRIVE, selected = isSel(storageRoot), onClick = { onOpen(storageRoot) }),
                Win7NavItem("System (C:)", Win7NavIcon.DRIVE, selected = isSel(internalFiles), onClick = { onOpen(internalFiles) }),
                Win7NavItem("Download (D:)", Win7NavIcon.DRIVE, selected = isSel(driveD), onClick = { onOpen(driveD) }),
                Win7NavItem("exagear (E:)", Win7NavIcon.OPTICAL, selected = isSel(driveE), onClick = { onOpen(driveE) })
            )
        )
    )
}

