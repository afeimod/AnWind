package com.anwind.core.desktop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.anwind.AnWindApp
import com.anwind.core.theme.LocalWinTheme
import com.anwind.core.window.AppRegistry
import com.anwind.core.window.WindowManager
import com.anwind.data.model.DesktopItem
import com.anwind.data.model.DesktopItemType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 桌面图标网格（v6 对齐到网格版）：对齐 Windows 桌面默认体验。
 *
 * v4 及之前的问题是「死板」（用户诉求 3）：
 *  - 拖动必须先长按 400ms（detectDragGesturesAfterLongPress）——手指
 *    直接拖图标毫无反应，手感僵硬；
 *  - 松手必吸附到网格单元格且强制与占用者交换。
 *
 * v5 改成了 Windows【关闭“对齐到网格”】的完全自由摆放（像素级落位、
 * 允许重叠）—— 用户 2026-11-12 反馈矫枉过正：「图标没有横平竖直的
 * 吸附感，有点太随便移动了，而且偶尔会出现图标图片重叠」。
 *
 * v6 的修复（Windows 默认语义：“对齐到网格”开启 + “自动排列”关闭）：
 *  - 拖动保持 v5 手感（detectDragGestures，按下越过 slop 即跟手，
 *    拖动中图标自由跟手、拎起放大置顶）；
 *  - 松手吸附到【最近单元格】（按图标中心换算）—— 图标永远横平
 *    竖直落在格点上；小幅度拖动（不足半格）松手即回原格，不再
 *    “随便挪动”；
 *  - 目标格被占时【就近外扩找空格】（曼哈顿距离最近的空闲格，
 *    Windows 溜边落位同款）—— 图标之间永不重叠；
 *  - v5 存量像素自由位在加载时一次性就近归位为单元格（整队后持久化，
 *    桌面即刻恢复横平竖直；解析兼容 v3/v5 全部旧格式）；
 *  - 新增图标仍按单元格 first-fit 自动落位（先列后行，同 Windows
 *    新图标落格）；name/type 排序模式仍按序自动排列、禁用拖动；
 *  - iconBounds 上报每个图标边界，供双指右键手势命中检测。
 */

/** 图标位置：单元格（自动落位）或像素自由位（用户拖放）。 */
private sealed interface IconPos {
    /** 单元格 (col,row)：first-fit 自动落位 / 旧 v4 数据。 */
    data class Cell(val col: Int, val row: Int) : IconPos

    /** 像素自由位（图标左上角，px）：用户拖放产生，Windows 式摆放。 */
    data class Free(val x: Int, val y: Int) : IconPos
}

@Composable
fun DesktopIconGrid(
    sortMode: String = "default",
    iconBounds: SnapshotStateMap<String, Pair<DesktopItem, Rect>>? = null,
    clickMode: String = "double"
) {
    val app = AnWindApp.get()
    val shortcuts by app.database.shortcutDao().observeAll().collectAsState(initial = emptyList())
    // v3.3.10（用户问题 2）：桌面固定应用集合（默认 6 件套；从开始菜单
    // 拖入追加、右键移除），取代静态的 AppRegistry.desktopApps()
    val pinnedAppsRaw by app.settingsStore.desktopPinnedApps.collectAsState(initial = null)
    val builtinApps = remember(pinnedAppsRaw) {
        val pinned = DesktopPins.parse(pinnedAppsRaw)
        pinned.mapNotNull { id -> AppRegistry.get(id) }
    }
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
    // v2.14：自定义图标顺序（未拖动过的图标自动布局时沿用）
    val iconOrder by app.settingsStore.desktopIconOrder.collectAsState(initial = "")
    // v4/v5：稳定位置（v5 = 单元格 + 像素自由位双形态）。
    // v4.1 修复：initial 必须用 null（而非 ""）—— DataStore 首次发射前
    // "" 占位帧会把 first-fit 布局当成"已加载的空布局"立即落盘，覆盖
    // 用户拖动保存的自定义位置（冷启动后图标"跑回"默认排列的竞态根源）。
    val iconPositions by app.settingsStore.desktopIconPositions.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 合并桌面项
    val items: List<DesktopItem> = remember(shortcuts, builtinApps) {
        val builtin = builtinApps.mapIndexed { idx, app ->
            DesktopItem(
                id = "app_${app.id}",
                label = app.displayName,
                iconAsset = app.iconAsset,
                type = DesktopItemType.BUILTIN_APP,
                target = app.id,
                sortOrder = idx
            )
        }
        val shortcutItems = shortcuts.map { entity ->
            DesktopItem(
                id = "shortcut_${entity.id}",
                label = entity.label,
                iconAsset = entity.iconAsset,
                type = when (entity.type) {
                    1 -> DesktopItemType.SHORTCUT_URL
                    2 -> DesktopItemType.SHORTCUT_FILE
                    3 -> DesktopItemType.SHORTCUT_APP
                    // v2.23.1：手机应用桌面快捷方式 → 强制 freeform 窗口启动
                    4 -> DesktopItemType.SHORTCUT_ANDROID_APP
                    else -> DesktopItemType.SHORTCUT_URL
                },
                target = entity.target,
                sortOrder = entity.sortOrder
            )
        }
        builtin + shortcutItems
    }

    // 排序（v2.11 右键菜单"排序方式"；v2.14 default 模式优先应用拖动自定义顺序）
    val sortedItems = remember(items, sortMode, iconOrder) {
        when (sortMode) {
            "name" -> items.sortedWith(compareBy({ it.label }, { it.id }))
            "type" -> items.sortedWith(compareBy({ it.type }, { it.label }, { it.id }))
            else -> {
                if (iconOrder.isNotBlank()) {
                    val orderIds = iconOrder.split(",").filter { it.isNotBlank() }
                    val pos = orderIds.withIndex().associate { (i, id) -> id to i }
                    // 已记录顺序的图标在前（按记录序），新出现的图标追加在后（保持默认序）
                    items.sortedBy { pos[it.id] ?: Int.MAX_VALUE }
                } else {
                    items   // 默认：内置应用 + 快捷方式原序
                }
            }
        }
    }

    // 同步命中注册表：移除已从桌面消失的图标（删除快捷方式后）
    if (iconBounds != null) {
        LaunchedEffect(items) {
            val ids = items.map { it.id }.toSet()
            iconBounds.keys.toList().forEach { key ->
                if (key !in ids) iconBounds.remove(key)
            }
        }
    }

    // ============ v5：稳定位置布局（Windows 式自由摆放） ============

    // 网格几何（px）：单元 = 图标单元宽/高 + 8dp 间距；边距 8dp
    val cellWpx = with(density) { ((iconSize + 24f).dp + 8.dp).toPx() }
    val cellHpx = with(density) { ((iconSize + 46f).dp + 8.dp).toPx() }
    val gridMarginPx = with(density) { 8.dp.toPx() }
    // v6.1：图标"视觉块"尺寸（px）—— DesktopIcon 实际渲染的图标+标签范围：
    // 宽 (iconSize+24)dp；高 4dp内边距+图标+2dp间隙+两行11sp标签(≈26dp,
    // 随系统字体缩放)+4dp内边距 ≈ (iconSize+36)dp，取 38dp 留 2dp 余量。
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val blockWpx = with(density) { (iconSize + 24f).dp.toPx() }
    val blockHpx = with(density) { iconSize.dp.toPx() + (38f.dp.toPx() * fontScale) }
    val freeDrag = sortMode == "default"

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridWpx = constraints.maxWidth.toFloat()
        val gridHpx = constraints.maxHeight.toFloat()
        // v6.1（用户反馈：底部明明还放得下一整格图标，落点却跳去第二列）：
        // 旧行整格整除截断 —— 末格自带的 8dp 格间距也要完整预留，底部
        // 0.5~0.9 格的可见空间全被废弃。新判据：顶部 8dp 边距 + (n-1) 个
        // 整格 + 末格仅占"视觉块"高度 ≤ 网格高/宽；末格块底按构造保证
        // 不越网格底缘（标签绝不压进任务栏），能多排一行就多排一行。
        val perCol = (1 + ((gridHpx - gridMarginPx - blockHpx) / cellHpx).toInt()).coerceAtLeast(1)
        val perRow = (1 + ((gridWpx - gridMarginPx - blockWpx) / cellWpx).toInt()).coerceAtLeast(1)

        // 网格单元坐标 → 像素（左上角）
        fun cellPx(col: Int, row: Int): Offset {
            val c = col.coerceIn(0, perRow - 1)
            val r = row.coerceIn(0, perCol - 1)
            return Offset(gridMarginPx + c * cellWpx, gridMarginPx + r * cellHpx)
        }

        // ---- v6：吸附换算（Windows "对齐到网格"开启语义） ----
        // 位置 → 渲染像素（左上角）：单元格走网格几何；自由位直接像素并
        // 钳制回界内（旋转/改图标大小后网格尺寸变化时防越界，v4 同款防御）
        // （注意：须定义在 freePosCell 之前 —— 局部函数不允许前向引用）
        fun posPx(p: IconPos): Offset = when (p) {
            is IconPos.Cell -> cellPx(p.col, p.row)
            is IconPos.Free -> Offset(
                p.x.toFloat().coerceIn(0f, (gridWpx - cellWpx).coerceAtLeast(0f)),
                p.y.toFloat().coerceIn(0f, (gridHpx - cellHpx).coerceAtLeast(0f))
            )
        }

        // 像素坐标（图标左上角）→ 最近单元格（按图标中心判定，四舍五入
        // 到最近格点；不足半格的拖动松手即回原格 —— "不随便移动"的关键）
        fun snapCellFromPx(pxX: Float, pxY: Float): Pair<Int, Int> {
            val col = (((pxX + cellWpx / 2f - gridMarginPx) / cellWpx).toInt())
                .coerceIn(0, perRow - 1)
            val row = (((pxY + cellHpx / 2f - gridMarginPx) / cellHpx).toInt())
                .coerceIn(0, perCol - 1)
            return col to row
        }

        // 像素自由位（v5 存量）覆盖的单元格（左上角所在格）—— 占位判定用
        fun freePosCell(p: IconPos.Free): Pair<Int, Int> {
            val px = posPx(p)
            return ((px.x / cellWpx).toInt().coerceIn(0, perRow - 1)) to
                ((px.y / cellHpx).toInt().coerceIn(0, perCol - 1))
        }

        // 目标格被占时向外扩找最近空格（曼哈顿距离最近；Windows 溜边落位
        // 同款语义：绝不与已有图标重叠）。全满时退回目标格（极端兜底）。
        fun nearestFreeCell(want: Pair<Int, Int>, taken: Set<Pair<Int, Int>>): Pair<Int, Int> {
            if (want !in taken) return want
            var best: Pair<Int, Int>? = null
            var bestDist = Int.MAX_VALUE
            val maxRing = perRow + perCol
            for (ring in 1..maxRing) {
                for (dc in -ring..ring) for (dr in -ring..ring) {
                    if (maxOf(Math.abs(dc), Math.abs(dr)) != ring) continue // 只看当前环
                    val c = want.first + dc
                    val r = want.second + dr
                    if (c !in 0 until perRow || r !in 0 until perCol) continue
                    val cell = c to r
                    if (cell in taken) continue
                    val d = Math.abs(dc) + Math.abs(dr)
                    if (d < bestDist) { bestDist = d; best = cell }
                }
                if (best != null) return best!! // 最近的非空环即答案
            }
            return want
        }

        // ---- 持久化位置的解析与序列化 ----
        // "id:c:col,row" → 单元格；"id:x,y" → 像素自由位（v5 语义；恰与
        // v3 旧像素格式同构 —— 旧数据自动升级为自由位，无需迁移）
        fun parsePositions(raw: String): Map<String, IconPos> {
            val m = mutableMapOf<String, IconPos>()
            raw.split(';').forEach { seg ->
                seg.trim().takeIf { it.isNotBlank() }?.let { s ->
                    val idx = s.lastIndexOf(':')
                    if (idx > 0) {
                        val id = s.substring(0, idx)
                        val rest = s.substring(idx + 1)
                        val isCellFormat = rest.startsWith("c:")
                        val xy = rest.removePrefix("c:").split(',')
                        if (xy.size == 2) {
                            val a = xy[0].trim().toIntOrNull()
                            val b = xy[1].trim().toIntOrNull()
                            if (a != null && b != null) {
                                m[id] = if (isCellFormat) IconPos.Cell(a, b)
                                else IconPos.Free(a, b)
                            }
                        }
                    }
                }
            }
            return m
        }

        fun serializePositions(positions: Map<String, IconPos>): String =
            positions.entries.joinToString(";") { (id, p) ->
                when (p) {
                    is IconPos.Cell -> "${id}:c:${p.col},${p.row}"
                    is IconPos.Free -> "${id}:${p.x},${p.y}"
                }
            }

        // 解析持久化位置（iconPositions / 网格几何 变化时重算）
        val savedPositions = remember(iconPositions, perRow, perCol) {
            parsePositions(iconPositions ?: "")
        }

        // 即时覆盖层：拖拽落位后同步生效，不等 DataStore 写回（消除闪跳）
        // 外部清空（设置里恢复默认布局）时一并清空本地层
        val livePositions = remember { mutableStateMapOf<String, IconPos>() }
        LaunchedEffect(iconPositions) {
            if (iconPositions?.isBlank() != false) livePositions.clear()
        }

        // ---- 完整布局计算：有位的保持（live 优先），缺位的先列后行 first-fit ----
        // 返回 Pair(全部位置表, 本次新分配的表)；新分配立即持久化 → 位置永久稳定
        // 注意：不能包 remember —— livePositions 是状态表，拖拽落位后的即时
        // 生效依赖它的读取被组合跟踪；计算本身仅百次量级循环，每次重组重算
        // 开销可忽略
        val (positions, newlyAssigned) = run {
            if (freeDrag) {
                val merged = LinkedHashMap<String, IconPos>()
                sortedItems.forEach { item ->
                    (livePositions[item.id] ?: savedPositions[item.id])?.let { merged[item.id] = it }
                }
                val newOnes = mutableMapOf<String, IconPos>()
                // 占位集合：有效单元格照记；v5 自由位以其覆盖格参与占位
                // （防归位前新图标塞进同一格）；越界单元格不参与占位
                // （自身即将重归位）。
                val takenCells = mutableSetOf<Pair<Int, Int>>()
                merged.values.forEach { p ->
                    when (p) {
                        is IconPos.Cell ->
                            if (p.col in 0 until perRow && p.row in 0 until perCol)
                                takenCells.add(p.col to p.row)
                        is IconPos.Free -> takenCells.add(freePosCell(p))
                    }
                }
                // v6.1：存量位置重归位（就近吸附到最近空格，绝不重叠）。两类：
                // ① v5 像素自由位 → 一次性归位为单元格（"横平竖直"整队）；
                // ② 越界单元格 —— 旋转屏幕/改图标大小/网格行数变化后，旧
                //   Cell(col,row) 超出当前网格范围。旧行为在 cellPx() 里静默
                //   钳制回界内 → 直接压在别的图标上（"偶尔图标重叠"的根源）。
                // 归位结果并入 newOnes 持久化 —— 旧格式数据（v3 像素 /
                // v5 自由位 / 旧几何单元格）加载即自动升级，无需迁移工具。
                val needsRefit = merged.entries.filter { (_, p) ->
                    p is IconPos.Free ||
                        (p is IconPos.Cell && (p.col !in 0 until perRow || p.row !in 0 until perCol))
                }
                for ((id, p) in needsRefit) {
                    val px = posPx(p)
                    val want = snapCellFromPx(px.x, px.y)
                    val cell = nearestFreeCell(want, takenCells)
                    val cellPos = IconPos.Cell(cell.first, cell.second)
                    merged[id] = cellPos
                    newOnes[id] = cellPos
                    takenCells.add(cell)
                }
                // 无位置图标按「先列后行」落入剩余空格（同 Windows 新图标
                // 落格；靠前的空位优先被"补上"）
                var cellIdx = 0
                var overflowIdx = 0
                sortedItems.forEach { item ->
                    if (merged.containsKey(item.id)) return@forEach
                    var placed = false
                    while (cellIdx < perRow * perCol) {
                        val col = cellIdx / perCol
                        val row = cellIdx % perCol
                        cellIdx++
                        val cell = col to row
                        if (cell !in takenCells) {
                            merged[item.id] = IconPos.Cell(col, row)
                            newOnes[item.id] = IconPos.Cell(col, row)
                            takenCells.add(cell)
                            placed = true
                            break
                        }
                    }
                    // v6.1：网格全满（图标总数 > 格数，极端场景）兜底 ——
                    // 旧行为漏分配 → 渲染层 Cell(0,0) 兜底 → 全部叠死在
                    // 第一格（重叠）。改为按序散排首列各格，至少错开可视
                    // 认领；不落盘（动态极端态），腾出空格后自动回正。
                    if (!placed) {
                        merged[item.id] = IconPos.Cell(0, overflowIdx % perCol)
                        overflowIdx++
                    }
                }
                merged to newOnes
            } else {
                // 排序模式：全部按序自动排列（不写位置、忽略自定义位置）
                val m = mutableMapOf<String, IconPos>()
                sortedItems.forEachIndexed { i, item ->
                    m[item.id] = IconPos.Cell(i / perCol, i % perCol)
                }
                m to emptyMap<String, IconPos>()
            }
        }

        // ---- 持久化 ----
        fun persistPositions(map: Map<String, IconPos>) {
            scope.launch {
                runCatching {
                    app.settingsStore.setDesktopIconPositions(serializePositions(map))
                }
            }
        }

        // 首次布局分配的格立即持久化（此后位置永久稳定，不再随其它变化重排）。
        // v4.1 修复：必须等 DataStore 真正发射（iconPositions != null）才允许落盘，
        // 否则启动占位帧的 first-fit 会把用户保存的布局覆盖掉；
        // iconPositions 也作为 key：null→""（无存量布局）时重跑一次把
        // first-fit 固化下来（新用户首次布局仍立即稳定）。
        LaunchedEffect(newlyAssigned, iconPositions) {
            if (iconPositions != null && newlyAssigned.isNotEmpty()) persistPositions(positions)
        }

        // 清理消失图标的幽灵占位（删除快捷方式后不留空洞记录）
        // 基于完整布局（含即时层）而非仅持久化层，避免与拖拽写入相互覆盖
        LaunchedEffect(positions, items) {
            val ids = items.map { it.id }.toSet()
            val stale = positions.keys.filter { it !in ids }
            if (stale.isNotEmpty()) {
                persistPositions(positions.filterKeys { it in ids })
            }
        }

        // ---- 拖动落位：吸附到最近单元格（v6，Windows "对齐到网格"语义） ----
        // v6：拖动累计偏移加到当前渲染位置上，钳制在桌面界内，再按图标中心
        // 换算到最近单元格吸附落位；目标格被其它图标占用时就近外扩找空格
        // —— 图标永远横平竖直、互不重叠（v5 的像素自由位与重叠问题就此
        // 收敛；不足半格的小拖动松手即回原格）。v4.1 教训保留：从当前
        // positions 表取真实基准位（不信任过期闭包）。
        fun occupiedCellsExcluding(excludeId: String): MutableSet<Pair<Int, Int>> {
            val taken = mutableSetOf<Pair<Int, Int>>()
            positions.forEach { (id, p) ->
                if (id == excludeId) return@forEach
                when (p) {
                    is IconPos.Cell -> taken += p.col to p.row
                    // v5 存量自由位：以覆盖格参与占位（防归位前重叠）
                    is IconPos.Free -> taken += freePosCell(p)
                }
            }
            return taken
        }

        fun onDropIcon(item: DesktopItem, deltaPx: Offset) {
            if (deltaPx == Offset.Zero) return
            val base = positions[item.id] ?: return
            val basePx = posPx(base)
            val nx = (basePx.x + deltaPx.x)
                .coerceIn(0f, (gridWpx - cellWpx).coerceAtLeast(0f))
            val ny = (basePx.y + deltaPx.y)
                .coerceIn(0f, (gridHpx - cellHpx).coerceAtLeast(0f))
            val want = snapCellFromPx(nx, ny)
            val cell = nearestFreeCell(want, occupiedCellsExcluding(item.id))
            val cellPos = IconPos.Cell(cell.first, cell.second)
            if (cellPos == base) return

            // 即时生效（不等 DataStore 回流）+ 持久化
            livePositions[item.id] = cellPos
            persistPositions(positions + (item.id to cellPos))
        }

        // ---- v3.3.10（用户问题 2）：开始菜单拖入落位（DesktopDragState 总线） ----
        // 菜单瓦片长按拖出 → 影子跟手（DesktopEnvironment 顶层）→ 桌面松手：
        // 此处把应用加入固定集合（DesktopPins.pin 持久化），并把松手坐标
        // （root 坐标 ≈ 全屏网格坐标）吸附到最近空格（v6：与桌面图标拖拽
        // 同一套吸附语义；影子中心换算到图标左上角半格偏移后吸附）。
        // 首个 collectAsState 回流前用 live 层即时占位，图标即刻出现在
        // 吸附格不闪跳。
        DesktopDragState.onDrop = { appId, dropX, dropY ->
            val itemId = "app_$appId"
            val nx = (dropX - cellWpx / 2f)
                .coerceIn(0f, (gridWpx - cellWpx).coerceAtLeast(0f))
            val ny = (dropY - cellHpx / 2f)
                .coerceIn(0f, (gridHpx - cellHpx).coerceAtLeast(0f))
            val want = snapCellFromPx(nx, ny)
            val cell = nearestFreeCell(want, occupiedCellsExcluding(itemId))
            val cellPos = IconPos.Cell(cell.first, cell.second)
            livePositions[itemId] = cellPos
            persistPositions(positions + (itemId to cellPos))
            DesktopPins.pin(appId, scope)
        }
        // 网格离开组合时注销总线回调（防泄漏/跨会话残留）
        DisposableEffect(Unit) {
            onDispose { DesktopDragState.onDrop = null }
        }

        sortedItems.forEach { item ->
            val pos = positions[item.id] ?: IconPos.Cell(0, 0)
            DesktopIcon(
                item = item,
                iconSize = iconSize,
                basePx = posPx(pos),
                boundsRegistry = iconBounds,
                clickMode = clickMode,
                dragEnabled = freeDrag,
                onDrop = { delta -> onDropIcon(item, delta) }
            )
        }
    }
}

/**
 * 启动一个桌面项（图标双击 / 右键菜单"打开"共用同一入口，保证行为一致）。
 *
 * v2.23.1：新增 [DesktopItemType.SHORTCUT_ANDROID_APP] 分支 ——
 * target 编码 `pkg/activity`，调用 [AndroidApps.launchByComponent]
 * 走强制 freeform 窗口化路径（Intent NEW_DOCUMENT + 反射
 * setLaunchWindowingMode + 启动后 setTaskWindowingMode 兜底强制）。
 */
fun launchDesktopItem(item: DesktopItem, wm: WindowManager) {
    when (item.type) {
        DesktopItemType.BUILTIN_APP -> {
            AppRegistry.get(item.target)?.let { a ->
                // v3.2.23（用户问题 3）：最小化窗口优先恢复（不再叠开重复窗）
                wm.openOrRestore(
                    appId = a.id,
                    title = a.displayName,
                    launchMode = a.launchMode,
                    initialWidth = a.defaultWidth.value.toInt(),
                    initialHeight = a.defaultHeight.value.toInt()
                )
            }
        }
        DesktopItemType.SHORTCUT_URL,
        DesktopItemType.SHORTCUT_FILE -> {
            // 通过浏览器打开
            wm.open(
                appId = "browser",
                title = "Browser",
                launchMode = AppRegistry.get("browser")?.launchMode
                    ?: com.anwind.core.window.LaunchMode.FLOATING,
                launchArgs = mapOf(
                    "url" to item.target,
                    "type" to item.type.name
                )
            )
        }
        DesktopItemType.SHORTCUT_APP -> {
            AppRegistry.get(item.target)?.let { a ->
                // v3.2.23（用户问题 3）：最小化窗口优先恢复（不再叠开重复窗）
                wm.openOrRestore(
                    appId = a.id,
                    title = a.displayName,
                    launchMode = a.launchMode,
                    initialWidth = a.defaultWidth.value.toInt(),
                    initialHeight = a.defaultHeight.value.toInt()
                )
            }
        }
        DesktopItemType.SHORTCUT_ANDROID_APP -> {
            // v2.23.1：手机应用桌面快捷方式 → 强制 freeform 窗口启动
            val ctx = runCatching { com.anwind.AnWindApp.get() }.getOrNull() ?: return
            val (pkg, activity) = AndroidApps.parseShortcutTarget(item.target) ?: return
            AndroidApps.launchByComponent(ctx, pkg, activity)
        }
    }
}

@Composable
private fun DesktopIcon(
    item: DesktopItem,
    iconSize: Float,
    basePx: Offset,
    boundsRegistry: SnapshotStateMap<String, Pair<DesktopItem, Rect>>? = null,
    clickMode: String = "double",
    dragEnabled: Boolean = false,
    onDrop: (Offset) -> Unit = { }
) {
    val theme = LocalWinTheme.current
    val wm = remember { WindowManager.get() }
    val iconPx = iconSize.dp
    val cellWidth = (iconSize + 24).dp
    val haptic = LocalHapticFeedback.current

    // v5 自由拖动状态：拖动中的累计偏移（px），松手归零并上报父级计算落位
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val dragging = dragOffset != Offset.Zero

    // v4.1 修复（核心）：pointerInput 的手势协程只在 key 变化时重启，
    // 块内直接引用的 onDrop 会停留在【首次组合时】的闭包里 —— 图标
    // 落位/加载保存布局后 positions 已变，但手势回调仍拿旧位置计算，
    // 从第二次拖动起落点全错（“位置乱跑”的真正根源）。
    // rememberUpdatedState 让回调永远调用最新一次重组的 onDrop。
    val currentOnDrop by rememberUpdatedState(onDrop)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // v5：绝对定位（单元格或像素自由位，父级计算）
            .offset { IntOffset(basePx.x.roundToInt(), basePx.y.roundToInt()) }
            .width(cellWidth)
            // 拖动中置顶 + 轻微放大，视觉上"拎起来"
            .zIndex(if (dragging) 10f else 0f)
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                val s = if (dragging) 1.08f else 1f
                scaleX = s
                scaleY = s
                alpha = if (dragging) 0.85f else 1f
            }
            // 上报图标边界（根坐标）：供双指右键手势命中检测；
            // 值未变化时不重复写入，避免无谓的快照失效。
            // 注意：上报的是【布局位置】（不含拖动偏移），松手落位后自动更新。
            .onGloballyPositioned { coords ->
                if (boundsRegistry != null) {
                    val rect = coords.boundsInRoot()
                    val prev = boundsRegistry[item.id]
                    if (prev == null || prev.first != item || prev.second != rect) {
                        boundsRegistry[item.id] = item to rect
                    }
                }
            }
            // v5（用户诉求 3）：按下即可拖动（Windows 式）—— 手指越过
            // touch slop 即开始移动图标，跟手；松手由父级像素级自由落位。
            // 旧版 detectDragGesturesAfterLongPress（长按 400ms 才能拖）
            // 是"移动死板"的根源。点按打开不受影响：tap 位移小于 slop，
            // 不会进入拖动手势（clickMode 的 detectTapGestures 照常工作）。
            .pointerInput(item.id, dragEnabled) {
                if (!dragEnabled) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    },
                    onDragEnd = {
                        if (dragOffset != Offset.Zero) currentOnDrop(dragOffset)
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = { dragOffset = Offset.Zero }
                )
            }
            // v2.13：clickMode 进入手势 key —— 单击模式立即启动，双击模式保持 v2.11 行为
            .pointerInput(item.id, clickMode) {
                if (clickMode == "single") {
                    detectTapGestures(
                        onTap = {
                            // 单击启动（与右键菜单"打开"共用同一入口）
                            launchDesktopItem(item, wm)
                        }
                    )
                } else {
                    detectTapGestures(
                        onDoubleTap = {
                            // 双击启动（与右键菜单"打开"共用同一入口）
                            launchDesktopItem(item, wm)
                        }
                    )
                }
            }
            .padding(4.dp)
    ) {
        // 图标
        IconPainter(item.iconAsset, size = iconPx)

        Spacer(Modifier.height(2.dp))

        // 文字
        Text(
            text = item.label,
            color = theme.desktopIconTextColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(cellWidth)
        )
    }
}

/**
 * 统一图标加载：支持 assets 路径 / "emoji:🚀" / 内置 drawable。
 *
 * v2.15 主题图标：图标引用先经 [com.anwind.core.theme.ThemeIcons.resolve]
 * 解析为当前 Windows 主题的专属图标（icons/win95|winxp|win10|win11/<id>.png），
 * 无专属图标时回退通用路径 —— 切换主题时桌面/任务栏/开始菜单图标即时换肤。
 */
@Composable
fun IconPainter(asset: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val theme = LocalWinTheme.current
    // 主题图标解析（assets.list 有缓存，重组开销可忽略）
    val resolvedAsset = remember(asset, theme.variant) {
        com.anwind.core.theme.ThemeIcons.resolve(context, asset, theme.variant)
    }

    if (resolvedAsset.startsWith("emoji:")) {
        val emoji = resolvedAsset.removePrefix("emoji:")
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Text(text = emoji, fontSize = (size.value * 0.7f).sp)
        }
        return
    }

    val painter = remember(resolvedAsset) {
        runCatching {
            context.assets.open(resolvedAsset).use {
                BitmapPainter(BitmapFactory.decodeStream(it).asImageBitmap())
            }
        }.getOrNull()
    }

    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.size(size)
        )
    } else {
        // 兜底：使用 emoji 占位
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(size)
                .background(theme.accentColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = 14.sp, color = theme.accentColor)
        }
    }
}
