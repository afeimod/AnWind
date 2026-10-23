package com.anwind.core.desktop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import com.anwind.data.model.Shortcut
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToInt

/**
 * 桌面图标网格（v3 自由定位版）：Windows 式随意拖放。
 *
 * - 长按图标 400ms（震动反馈）进入拖动，图标跟手自由移动；
 * - 松手时吸附到最近的网格单元并持久化位置（desktop_icon_positions）；
 * - 若目标格已有图标则与之交换位置（Windows 同款行为）；
 * - 未拖过/新出现的图标自动流式排到剩余空格（先列后行，同 Windows）；
 * - 排序方式 name/type 时忽略自定义位置按序排列，拖动禁用；
 * - iconBounds 上报每个图标边界，供双指右键手势命中检测。
 */
@OptIn(ExperimentalLayoutApi::class)
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
    // v3：自由定位坐标
    val iconPositions by app.settingsStore.desktopIconPositions.collectAsState(initial = "")
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
            "type" -> items.sortedWith(compareBy({ it.type }, { it.label }))
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

    // ============ v3：自由定位布局（Windows 式） ============

    // 网格几何（px）：单元 = 图标单元宽/高 + 8dp 间距；边距 8dp
    val cellWpx = with(density) { ((iconSize + 24f).dp + 8.dp).toPx() }
    val cellHpx = with(density) { ((iconSize + 46f).dp + 8.dp).toPx() }
    val gridMarginPx = with(density) { 8.dp.toPx() }

    // 解析持久化位置："id:x,y;id:x,y"
    val customPos = remember(iconPositions) {
        val m = mutableMapOf<String, Offset>()
        iconPositions.split(';').forEach { seg ->
            seg.trim().takeIf { it.isNotBlank() }?.let { s ->
                val idx = s.lastIndexOf(':')
                if (idx > 0) {
                    val id = s.substring(0, idx)
                    val xy = s.substring(idx + 1).split(',')
                    if (xy.size == 2) {
                        val x = xy[0].toFloatOrNull(); val y = xy[1].toFloatOrNull()
                        if (x != null && y != null) m[id] = Offset(x, y)
                    }
                }
            }
        }
        m
    }
    val freeDrag = sortMode == "default"

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridWpx = constraints.maxWidth.toFloat()
        val gridHpx = constraints.maxHeight.toFloat()
        val perCol = (((gridHpx - 2 * gridMarginPx) / cellHpx).toInt()).coerceAtLeast(1)
        val perRow = (((gridWpx - 2 * gridMarginPx) / cellWpx).toInt()).coerceAtLeast(1)

        // 网格单元坐标 → 像素
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

        // 已占格（自定义位置吸附后的单元）
        val occupiedCells = remember(customPos, perRow, perCol) {
            customPos.values.map { cellOf(it) }.toSet()
        }

        // 未定位图标自动流入剩余空格（先列后行，同 Windows 新图标落位）
        val positions = remember(sortedItems, customPos, occupiedCells, perRow, perCol, freeDrag) {
            val m = mutableMapOf<String, Offset>()
            if (freeDrag) {
                sortedItems.forEach { if (customPos.containsKey(it.id)) m[it.id] = customPos[it.id]!! }
                val freeCells = buildList {
                    for (c in 0 until perRow) for (r in 0 until perCol) {
                        val cell = c to r
                        if (cell !in occupiedCells) add(cell)
                    }
                }
                sortedItems.filter { !customPos.containsKey(it.id) }.forEachIndexed { i, item ->
                    if (i < freeCells.size) m[item.id] = cellPx(freeCells[i].first, freeCells[i].second)
                }
            } else {
                // 排序模式：全部按序自动排列（忽略自定义位置）
                sortedItems.forEachIndexed { i, item ->
                    val col = i / perCol
                    val row = i % perCol
                    m[item.id] = cellPx(col, row)
                }
            }
            m
        }

        // 拖动落格：吸附 + 占位交换 + 持久化
        fun onDropIcon(item: DesktopItem, basePx: Offset, deltaPx: Offset) {
            if (deltaPx == Offset.Zero) return
            val newCenter = basePx + deltaPx
            val (col, row) = cellOf(newCenter)
            val snapped = cellPx(col, row)
            // 目标格占用者（按吸附后坐标精确匹配）
            val occupant = sortedItems.firstOrNull { other ->
                other.id != item.id && positions[other.id] == snapped
            }
            val updated = customPos.toMutableMap()
            updated[item.id] = snapped
            if (occupant != null) updated[occupant.id] = basePx   // 被占者换到我原来的格
            scope.launch {
                app.settingsStore.setDesktopIconPositions(
                    updated.entries.joinToString(";") { "${it.key}:${it.value.x},${it.value.y}" }
                )
            }
        }

        sortedItems.forEach { item ->
            val pos = positions[item.id] ?: cellPx(0, 0)
            DesktopIcon(
                item = item,
                iconSize = iconSize,
                basePx = pos,
                boundsRegistry = iconBounds,
                clickMode = clickMode,
                dragEnabled = freeDrag,
                onDrop = { delta -> onDropIcon(item, pos, delta) }
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

    // v3 自由拖动状态：拖动中的累计偏移（px），松手归零并上报父级计算落格
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val dragging = dragOffset != Offset.Zero

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // v3：绝对定位（父级计算的网格坐标）
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
            // v3：长按 400ms 进入自由拖动 → 图标跟手 → 松手吸附最近网格落位
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
                        if (dragOffset != Offset.Zero) onDrop(dragOffset)
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
