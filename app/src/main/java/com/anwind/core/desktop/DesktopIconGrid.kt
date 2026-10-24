package com.anwind.core.desktop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * 桌面图标网格（v4 稳定单元格版）：完全对齐 Windows 桌面行为。
 *
 * v3 的问题是「乱跑」：
 *  - 只持久化被拖过的图标，未拖过的图标每次都按剩余空格重新流式布局 ——
 *    任何图标获得自定义位置/被删除后，剩余空格集合变化，其它未拖过的
 *    图标会集体跳位；
 *  - 删除的快捷方式在位置表中留下幽灵占位，新图标落到奇怪的位置；
 *  - DataStore 异步写回有时延，松手后图标先弹回原格再跳到新格。
 *
 * v4 的修复（与 Windows 一致）：
 *  - 位置一律以「单元格(col,row)」持久化（新格式 id:c:col,row，兼容读取
 *    旧版像素格式并自动换算），所有图标在首次布局后位置永久稳定；
 *  - 新出现的图标按「先列后行」落入第一个空格（同 Windows）；
 *  - 拖动只影响被拖图标与被占格图标（交换，同 Windows），其余图标不动；
 *  - 删除图标时同步清除其位置记录，不留幽灵占位；
 *  - 松手即时在本地快照层生效（不等待 DataStore 写回），零闪跳；
 *  - 网格尺寸变化（旋转/改图标大小）时旧坐标按最近格吸附并钳制回界内；
 *  - name/type 排序模式仍按序自动排列、禁用拖动（保持原行为）；
 *  - iconBounds 上报每个图标边界，供双指右键手势命中检测。
 */
@Composable
fun DesktopIconGrid(
    sortMode: String = "default",
    iconBounds: SnapshotStateMap<String, Pair<DesktopItem, Rect>>? = null,
    clickMode: String = "double"
) {
    val app = AnWindApp.get()
    val shortcuts by app.database.shortcutDao().observeAll().collectAsState(initial = emptyList())
    val builtinApps = remember { AppRegistry.desktopApps() }
    val iconSize by app.settingsStore.iconSize.collectAsState(initial = 48f)
    // v2.14：自定义图标顺序（未拖动过的图标自动布局时沿用）
    val iconOrder by app.settingsStore.desktopIconOrder.collectAsState(initial = "")
    // v4：稳定单元格位置。
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

    // ============ v4：稳定单元格布局（Windows 式） ============

    // 网格几何（px）：单元 = 图标单元宽/高 + 8dp 间距；边距 8dp
    val cellWpx = with(density) { ((iconSize + 24f).dp + 8.dp).toPx() }
    val cellHpx = with(density) { ((iconSize + 46f).dp + 8.dp).toPx() }
    val gridMarginPx = with(density) { 8.dp.toPx() }
    val freeDrag = sortMode == "default"

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridWpx = constraints.maxWidth.toFloat()
        val gridHpx = constraints.maxHeight.toFloat()
        val perCol = (((gridHpx - 2 * gridMarginPx) / cellHpx).toInt()).coerceAtLeast(1)
        val perRow = (((gridWpx - 2 * gridMarginPx) / cellWpx).toInt()).coerceAtLeast(1)

        // 网格单元坐标 → 像素（左上角）
        fun cellPx(col: Int, row: Int): Offset {
            val c = col.coerceIn(0, perRow - 1)
            val r = row.coerceIn(0, perCol - 1)
            return Offset(gridMarginPx + c * cellWpx, gridMarginPx + r * cellHpx)
        }

        // 像素 → 最近网格单元（列/行）
        fun cellOf(p: Offset): Pair<Int, Int> {
            val col = ((p.x - gridMarginPx + cellWpx / 2f) / cellWpx).toInt().coerceIn(0, perRow - 1)
            val row = ((p.y - gridMarginPx + cellHpx / 2f) / cellHpx).toInt().coerceIn(0, perCol - 1)
            return col to row
        }

        // ---- 持久化位置的解析与序列化 ----
        // 新格式："id:c:col,row;..."（单元格）
        // 旧格式："id:x,y;..."（像素，v3 遗留）—— 按当前网格几何换算为单元格
        fun parsePositions(raw: String): Map<String, Pair<Int, Int>> {
            val m = mutableMapOf<String, Pair<Int, Int>>()
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
                                m[id] = if (isCellFormat) {
                                    (a to b)
                                } else {
                                    // 旧像素坐标 → 最近单元格（并随网格尺寸自动钳制）
                                    cellOf(Offset(a.toFloat(), b.toFloat()))
                                }
                            }
                        }
                    }
                }
            }
            return m
        }

        fun serializePositions(cells: Map<String, Pair<Int, Int>>): String =
            cells.entries.joinToString(";") { (id, c) -> "${id}:c:${c.first},${c.second}" }

        // 解析持久化位置（iconPositions / 网格几何 变化时重算，旧像素自动迁移）
        val savedCells = remember(iconPositions, perRow, perCol) {
            parsePositions(iconPositions ?: "")
        }

        // 即时覆盖层：拖拽落格后同步生效，不等 DataStore 写回（消除闪跳）
        // 外部清空（设置里恢复默认布局）时一并清空本地层
        val liveCells = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
        LaunchedEffect(iconPositions) {
            if (iconPositions?.isBlank() != false) liveCells.clear()
        }

        // ---- 完整布局计算：有格的保持（live 优先），缺格的先列后行 first-fit ----
        // 返回 Pair(全部单元格表, 本次新分配的表)；新分配立即持久化 → 位置永久稳定
        // 注意：不能包 remember —— liveCells 是状态表，拖拽落格后的即时生效依赖
        // 它的读取被组合跟踪；计算本身仅百次量级循环，每次重组重算开销可忽略
        val (cells, newlyAssigned) = run {
            if (freeDrag) {
                val merged = mutableMapOf<String, Pair<Int, Int>>()
                // 1) 已有位置（含即时层覆盖）的图标先占格；同格冲突时先到先得
                val taken = mutableSetOf<Pair<Int, Int>>()
                sortedItems.forEach { item ->
                    val c = liveCells[item.id] ?: savedCells[item.id] ?: return@forEach
                    if (c in taken) return@forEach   // 钳制后撞格：让位给 first-fit
                    merged[item.id] = c
                    taken.add(c)
                }
                // 2) 无位置图标按「先列后行」落入剩余空格（同 Windows 新图标落位）
                val newOnes = mutableMapOf<String, Pair<Int, Int>>()
                var cellIdx = 0
                sortedItems.forEach { item ->
                    if (merged.containsKey(item.id)) return@forEach
                    while (cellIdx < perRow * perCol) {
                        val col = cellIdx / perCol
                        val row = cellIdx % perCol
                        cellIdx++
                        val cell = col to row
                        if (cell !in taken) {
                            merged[item.id] = cell
                            newOnes[item.id] = cell
                            taken.add(cell)
                            break
                        }
                    }
                }
                merged to newOnes
            } else {
                // 排序模式：全部按序自动排列（不写位置、忽略自定义位置）
                val m = mutableMapOf<String, Pair<Int, Int>>()
                sortedItems.forEachIndexed { i, item ->
                    m[item.id] = (i / perCol) to (i % perCol)
                }
                // v4.2 修复（CI 编译错误）：emptyMap() 必须带显式类型参数。
                // Kotlin 1.9 推断器把无参 emptyMap() 的 K/V 留成未推断变量，
                // 与另一分支 newOnes 的 MutableMap<String, Pair<Int,Int>> 无法
                // 统一，run 块的公共超类型求解失败 → "Type mismatch: inferred
                // type is Map<???, ???> but MutableMap<String, Pair<Int, Int>>
                // was expected"（233/269 三处报错）。显式类型参数后两分支统一
                // 为 Pair<MutableMap<...>, Map<String, Pair<Int,Int>>，编译通过。
                m to emptyMap<String, Pair<Int, Int>>()
            }
        }

        // ---- 持久化 ----
        fun persistPositions(map: Map<String, Pair<Int, Int>>) {
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
            if (iconPositions != null && newlyAssigned.isNotEmpty()) persistPositions(cells)
        }

        // 清理消失图标的幽灵占位（删除快捷方式后不留空洞记录）
        // 基于完整布局（含即时层）而非仅持久化层，避免与拖拽写入相互覆盖
        LaunchedEffect(cells, items) {
            val ids = items.map { it.id }.toSet()
            val stale = cells.keys.filter { it !in ids }
            if (stale.isNotEmpty()) {
                persistPositions(cells.filterKeys { it in ids })
            }
        }

        // ---- 拖动落格：按图标中心定位目标格 + 占位交换 + 即时生效 + 持久化 ----
        // v4.1 修复：不再信任调用方传入的 baseCell —— pointerInput 手势协程
        // 持有的是首次组合时的闭包，传入的 baseCell 可能是过期快照（这是
        // “拖动后位置乱跑”的根源之一）；这里直接从当前 cells 表取真实基准格。
        fun onDropIcon(item: DesktopItem, deltaPx: Offset) {
            if (deltaPx == Offset.Zero) return
            val baseCell = cells[item.id] ?: return
            // 图标中心松手位置 → 目标格（与 Windows 的光标落点语义最接近）
            val base = cellPx(baseCell.first, baseCell.second)
            val center = Offset(
                base.x + deltaPx.x + cellWpx / 2f,
                base.y + deltaPx.y + cellHpx / 2f
            )
            val target = cellOf(center)
            if (target == baseCell) return           // 原格：无需任何变动

            // 目标格占用者：按单元格索引精确匹配（整数比较，无浮点误差）
            val occupantId = cells.entries.firstOrNull { it.key != item.id && it.value == target }?.key
            val updated = cells.toMutableMap()
            updated[item.id] = target
            if (occupantId != null) updated[occupantId] = baseCell   // Windows 同款交换

            // 即时生效（不等 DataStore 回流）+ 持久化
            liveCells[item.id] = target
            if (occupantId != null) liveCells[occupantId] = baseCell
            persistPositions(updated)
        }

        sortedItems.forEach { item ->
            val cell = cells[item.id] ?: (0 to 0)
            DesktopIcon(
                item = item,
                iconSize = iconSize,
                basePx = cellPx(cell.first, cell.second),
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
                wm.open(
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
                wm.open(
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

    // v4 自由拖动状态：拖动中的累计偏移（px），松手归零并上报父级计算落格
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val dragging = dragOffset != Offset.Zero

    // v4.1 修复（核心）：pointerInput 的手势协程只在 key 变化时重启，
    // 块内直接引用的 onDrop 会停留在【首次组合时】的闭包里 —— 图标
    // 落格/加载保存布局后 cells 已变，但手势回调仍拿旧 cell/旧 cells
    // 计算，从第二次拖动起落点全错（“位置乱跑”的真正根源）。
    // rememberUpdatedState 让回调永远调用最新一次重组的 onDrop。
    val currentOnDrop by rememberUpdatedState(onDrop)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // v4：绝对定位（父级计算的网格坐标）
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
            // 注意：上报的是【布局位置】（不含拖动偏移），松手落格后自动更新。
            .onGloballyPositioned { coords ->
                if (boundsRegistry != null) {
                    val rect = coords.boundsInRoot()
                    val prev = boundsRegistry[item.id]
                    if (prev == null || prev.first != item || prev.second != rect) {
                        boundsRegistry[item.id] = item to rect
                    }
                }
            }
            // v4：长按 400ms 进入自由拖动 → 图标跟手 → 松手吸附最近网格落位
            .pointerInput(item.id, dragEnabled) {
                if (!dragEnabled) return@pointerInput
                detectDragGesturesAfterLongPress(
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
