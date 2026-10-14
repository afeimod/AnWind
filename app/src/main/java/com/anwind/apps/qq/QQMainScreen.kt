package com.anwind.apps.qq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.window.LaunchMode
import com.anwind.core.window.WindowManager
import com.anwind.core.window.WindowContentScope

/**
 * QQ2008 主面板：好友列表 + 搜索 + 群/通讯录/最近联系人切换 + 底部工具条。
 *
 * 布局对照原版截图（从上到下）：
 *  1. 用户条：头像(状态菜单) + 昵称[状态] + 签名 | 天气
 *  2. 信息条：等级(☀🌙⭐) + ✉ + 💰
 *  3. 搜索框：搜索我的好友
 *  4. 列表区：QQ好友分组（可折叠）/ QQ群 / 通讯录 / 最近联系人
 *  5. 切换条：通讯录 | QQ群 | 最近联系人（三个经典浅蓝按钮）
 *  6. 图标工具条：空间/邮箱/音乐/浏览器/扫雷（部分直通 AnWind 内置应用）
 *  7. 底部条：提示音开关 + 查找 + 主菜单
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QQMainScreen(scope: WindowContentScope) {
    val acc = QQSession.account ?: return
    val wm = remember { WindowManager.get() }

    var view by remember { mutableStateOf("friends") }   // friends / contacts / groups / recent
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var statusMenu by remember { mutableStateOf(false) }
    var mainMenu by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showProfile by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var remarkTarget by remember { mutableStateOf<QQFriend?>(null) }

    LaunchedEffect(Unit) {
        scope.onTitleChange("QQ2008 - ${acc.nickname}")
        // 登录后切到窄长面板尺寸（对齐原版主面板比例）
        scope.windowState.width = 330
        scope.windowState.height = 660
        wm.commitChanges()
    }

    // 好友按分组归集（观察 QQSession.friends）
    val allFriends = QQSession.friends.values.toList()
    val filtered = if (query.isBlank()) allFriends else allFriends.filter {
        it.displayName.contains(query, true) || it.nickname.contains(query, true) ||
                it.number.contains(query)
    }
    val normalFriends = filtered.filter { !it.isGroup }
    val groupFriends = filtered.filter { it.isGroup }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QQ2008.panelBrush())
            .border(1.dp, QQ2008.PanelBorder)
    ) {
        // ===== 1. 用户条 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                QqAvatar(acc.avatar, 44.dp, acc.status, Modifier.clickable { statusMenu = true })
                DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    QQStatus.entries.forEach { s ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    QqStatusDot(s, 9.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.label, fontSize = 13.sp)
                                }
                            },
                            onClick = {
                                QQSession.updateStatus(s)
                                statusMenu = false
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        acc.nickname,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "[${acc.status.label}]",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
                Text(
                    acc.signature.ifBlank { "编辑个性签名，让大家认识你" },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { showProfile = true }
                )
            }
            // 天气挂件（装饰，2008 情怀）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("☀️", fontSize = 20.sp)
                Text("晴 26°", color = Color.White, fontSize = 10.sp)
            }
        }

        // ===== 2. 信息条（等级/邮箱/Q币） =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val h = (acc.number.hashCode() and 0xFFFF)
            val suns = h % 3
            val moons = (h / 3) % 4
            val stars = (h / 12) % 5
            Text(
                buildString {
                    repeat(suns) { append("☀") }
                    repeat(moons) { append("🌙") }
                    repeat(stars) { append("⭐") }
                } + " ${suns + moons * 4 + stars * 16}",
                fontSize = 11.sp
            )
            Spacer(Modifier.width(10.dp))
            Text("✉ ${4 + h % 9}", fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Text("💰 ${60 + h % 60}", fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "号码 ${acc.number}",
                fontSize = 10.sp,
                color = QQ2008.TextSub
            )
        }

        // ===== 3. 搜索框 =====
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .height(28.dp)
                .background(Color.White, RoundedCornerShape(14.dp))
                .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("🔍", fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = QQ2008.TextMain),
                    modifier = Modifier.weight(1f)
                )
                if (query.isNotEmpty()) {
                    Text(
                        "✕", fontSize = 12.sp, color = QQ2008.TextSub,
                        modifier = Modifier
                            .clickable { query = "" }
                            .padding(3.dp)
                    )
                }
            }
            if (query.isEmpty()) {
                Text(
                    "搜索我的好友 ...",
                    fontSize = 12.sp,
                    color = Color(0xFF9FB8CC),
                    modifier = Modifier.padding(start = 24.dp)
                )
            }
        }

        // ===== 4. 列表区 =====
        Box(Modifier.weight(1f)) {
            when (view) {
                "friends" -> FriendListView(
                    friends = normalFriends,
                    groups = groupFriends,
                    collapsed = collapsed,
                    selected = selected,
                    onToggleGroup = { g ->
                        collapsed = if (g in collapsed) collapsed - g else collapsed + g
                    },
                    onSelect = { selected = it },
                    onOpenChat = { f -> openChatWindow(wm, f) },
                    onRemark = { f -> remarkTarget = f },
                    onDelete = { f ->
                        QQSession.removeFriend(f.number)
                        if (selected == f.number) selected = null
                    }
                )
                "contacts" -> ContactsView(filtered)
                "groups" -> GroupListView { f -> openChatWindow(wm, f) }
                "recent" -> RecentView(onOpen = { f -> openChatWindow(wm, f) })
            }
        }

        // ===== 5. 切换条 =====
        Column(Modifier.padding(horizontal = 8.dp)) {
            SwitcherButton("通讯录", view == "contacts") {
                view = if (view == "contacts") "friends" else "contacts"
            }
            Spacer(Modifier.height(4.dp))
            SwitcherButton("QQ群", view == "groups") {
                view = if (view == "groups") "friends" else "groups"
            }
            Spacer(Modifier.height(4.dp))
            SwitcherButton("最近联系人", view == "recent") {
                view = if (view == "recent") "friends" else "recent"
            }
        }

        // ===== 6. 图标工具条 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ToolIcon("⭐", "QQ空间") { showAbout = true }
            ToolIcon("✉️", "QQ邮箱") { showAbout = true }
            ToolIcon("🎵", "音乐") { launchBuiltin(wm, "music", "QQ音乐") }
            ToolIcon("🌐", "浏览器") { launchBuiltin(wm, "browser", "TT浏览器") }
            ToolIcon("💣", "扫雷") { launchBuiltin(wm, "minesweeper", "扫雷") }
        }

        // ===== 7. 底部条 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush())
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (QQSession.settings.soundEnabled) "🔊" else "🔇",
                fontSize = 15.sp,
                modifier = Modifier.clickable {
                    QQSession.saveSettings(
                        QQSession.settings.copy(soundEnabled = !QQSession.settings.soundEnabled)
                    )
                }
            )
            Spacer(Modifier.width(12.dp))
            Box {
                Text(
                    "☰",
                    fontSize = 15.sp,
                    color = Color.White,
                    modifier = Modifier.clickable { mainMenu = true }
                )
                DropdownMenu(expanded = mainMenu, onDismissRequest = { mainMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("个人资料", fontSize = 13.sp) },
                        onClick = { showProfile = true; mainMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("系统设置", fontSize = 13.sp) },
                        onClick = { showSettings = true; mainMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("关于 QQ2008", fontSize = 13.sp) },
                        onClick = { showAbout = true; mainMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("退出登录", fontSize = 13.sp, color = Color(0xFFB33)) },
                        onClick = {
                            mainMenu = false
                            // 关闭本账号打开的所有聊天窗口，再回到登录界面
                            wm.windows
                                .filter { it.appId == "qq" && it.id != scope.windowState.id }
                                .forEach { wm.close(it.id) }
                            QQSession.logout()
                            scope.windowState.width = 380
                            scope.windowState.height = 560
                            wm.commitChanges()
                        }
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "查找",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { showFind = true }
            )
        }
    }

    // ===== 弹窗 =====
    if (showFind) QQFindDialog(onDismiss = { showFind = false })
    if (showSettings) QQSettingsDialog(onDismiss = { showSettings = false })
    if (showProfile) QQProfileDialog(onDismiss = { showProfile = false })
    if (showAbout) QQInfoDialog(
        title = "关于",
        text = "QQ2008 for AnWind v1.0\n\n" +
                "经典 2008 界面复刻，AI 好友全天在线。\n" +
                "QQ空间 / 邮箱等 2008 年的按钮还在穿越中，敬请期待。\n" +
                "音乐 / 浏览器 / 扫雷图标直通 AnWind 桌面应用。",
        onDismiss = { showAbout = false }
    )
    remarkTarget?.let { f ->
        QQRemarkDialog(friend = f, onDismiss = { remarkTarget = null })
    }
}

/** 打开（或聚焦）某联系人的聊天窗口 */
private fun openChatWindow(wm: WindowManager, friend: QQFriend) {
    val existing = wm.windows.firstOrNull {
        it.appId == "qq" && it.launchArgs["chat"] == friend.number
    }
    if (existing != null) {
        wm.focus(existing.id)
    } else {
        wm.open(
            appId = "qq",
            title = "与 ${friend.displayName} 聊天中",
            launchMode = LaunchMode.FLOATING,
            launchArgs = mapOf("chat" to friend.number),
            initialWidth = 660,
            initialHeight = 520
        )
    }
}

/** 拉起 AnWind 内置应用（找不到时忽略） */
private fun launchBuiltin(wm: WindowManager, appId: String, retroName: String) {
    if (wm.windows.none { it.appId == appId }) {
        wm.open(appId = appId, title = retroName, launchMode = LaunchMode.FLOATING)
    } else {
        wm.windows.filter { it.appId == appId }.forEach { wm.focus(it.id) }
    }
}

/** 切换条按钮（通讯录 / QQ群 / 最近联系人） */
@Composable
private fun SwitcherButton(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(
                if (active) QQ2008.btnBrush(true) else QQ2008.btnBrush(false),
                RoundedCornerShape(4.dp)
            )
            .border(1.dp, QQ2008.BtnBorder, RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color(0xFF2E5FA3) else Color(0xFF3B5A78)
        )
    }
}

/** 底部小工具图标 */
@Composable
private fun ToolIcon(emoji: String, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(Color.White.copy(alpha = 0.75f), CircleShape)
            .border(1.dp, QQ2008.PanelBorder.copy(alpha = 0.6f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 15.sp)
    }
}

// ==================== 好友列表视图 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendListView(
    friends: List<QQFriend>,
    groups: List<QQFriend>,
    collapsed: Set<String>,
    selected: String?,
    onToggleGroup: (String) -> Unit,
    onSelect: (String) -> Unit,
    onOpenChat: (QQFriend) -> Unit,
    onRemark: (QQFriend) -> Unit,
    onDelete: (QQFriend) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // --- 我的好友分组 ---
        GroupHeader(
            title = "我的好友",
            countText = "${friends.count { it.status == QQStatus.ONLINE }}/${friends.size}",
            collapsed = "我的好友" in collapsed,
            onClick = { onToggleGroup("我的好友") }
        )
        if ("我的好友" !in collapsed) {
            friends.forEach { f ->
                FriendRow(
                    friend = f,
                    selected = selected == f.number,
                    onClick = { onSelect(f.number) },
                    onDoubleClick = { onOpenChat(f) },
                    onLongPress = { },
                    onMenuAction = { action ->
                        when (action) {
                            "chat" -> onOpenChat(f)
                            "remark" -> onRemark(f)
                            "delete" -> onDelete(f)
                        }
                    }
                )
            }
        }
        // --- 我的群组 ---
        GroupHeader(
            title = "我的群组",
            countText = "${groups.size}",
            collapsed = "我的群组" in collapsed,
            onClick = { onToggleGroup("我的群组") }
        )
        if ("我的群组" !in collapsed) {
            groups.forEach { g ->
                FriendRow(
                    friend = g,
                    selected = selected == g.number,
                    onClick = { onSelect(g.number) },
                    onDoubleClick = { onOpenChat(g) },
                    onLongPress = { },
                    onMenuAction = { action ->
                        when (action) {
                            "chat" -> onOpenChat(g)
                            "delete" -> onDelete(g)
                        }
                    }
                )
            }
        }
        if (friends.isEmpty() && groups.isEmpty()) {
            Text(
                "没有找到匹配的好友",
                fontSize = 12.sp, color = QQ2008.TextSub,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 分组标题条 */
@Composable
private fun GroupHeader(title: String, countText: String, collapsed: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(QQ2008.GroupBarTop, QQ2008.GroupBarBottom)
                )
            )
            .border(0.5.dp, QQ2008.PanelBorder.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (collapsed) "▶" else "▼",
            fontSize = 9.sp,
            color = QQ2008.TextSub
        )
        Spacer(Modifier.width(4.dp))
        QqAvatar("emoji:🐧", 18.dp, null)
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 13.sp, color = QQ2008.TextMain, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text("($countText)", fontSize = 11.sp, color = QQ2008.TextSub)
    }
}

/** 好友行：头像 + 彩色昵称 + 签名 + 未读角标；长按/右键弹操作菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendRow(
    friend: QQFriend,
    selected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongPress: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    val unread = QQSession.unread[friend.number] ?: 0
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) QQ2008.SelectedRow else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = { onLongPress(); menu = true }
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QqAvatar(friend.avatar, 38.dp, friend.status)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    friend.displayName,
                    color = QQ2008.nickColor(friend.number),
                    fontSize = 14.sp,
                    fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (friend.isGroup) {
                    Spacer(Modifier.width(4.dp))
                    Text("[群]", fontSize = 10.sp, color = QQ2008.TextSub)
                }
            }
            Text(
                friend.signature,
                fontSize = 11.sp,
                color = QQ2008.TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (unread > 0) {
            Box(
                modifier = Modifier
                    .background(Color(0xFFE23B3B), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    if (unread > 99) "99+" else unread.toString(),
                    color = Color.White, fontSize = 10.sp
                )
            }
        }
        // 行内操作菜单
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("发送即时消息", fontSize = 13.sp) },
                onClick = { menu = false; onMenuAction("chat") }
            )
            if (!friend.isGroup) {
                DropdownMenuItem(
                    text = { Text("修改备注名称", fontSize = 13.sp) },
                    onClick = { menu = false; onMenuAction("remark") }
                )
                DropdownMenuItem(
                    text = { Text("删除好友", fontSize = 13.sp, color = Color(0xFFB33)) },
                    onClick = { menu = false; onMenuAction("delete") }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("退出群聊", fontSize = 13.sp, color = Color(0xFFB33)) },
                    onClick = { menu = false; onMenuAction("delete") }
                )
            }
        }
    }
}

// ==================== 通讯录 ====================

@Composable
private fun ContactsView(friends: List<QQFriend>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 6.dp)
    ) {
        // 表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.GroupBarBottom)
                .border(0.5.dp, QQ2008.PanelBorder.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("昵称", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = QQ2008.TextMain, modifier = Modifier.weight(1.2f))
            Text("QQ号码", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = QQ2008.TextMain, modifier = Modifier.weight(1f))
            Text("签名", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = QQ2008.TextMain, modifier = Modifier.weight(1.6f))
        }
        friends.forEach { f ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.7f))
                    .border(0.5.dp, QQ2008.PanelBorder.copy(alpha = 0.3f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                    QqAvatar(f.avatar, 22.dp, f.status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        f.displayName + if (f.isGroup) " [群]" else "",
                        fontSize = 12.sp,
                        color = QQ2008.nickColor(f.number),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(f.number, fontSize = 12.sp, color = QQ2008.TextMain, modifier = Modifier.weight(1f))
                Text(
                    f.signature, fontSize = 11.sp, color = QQ2008.TextSub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.6f)
                )
            }
        }
        if (friends.isEmpty()) {
            Text("暂无联系人", fontSize = 12.sp, color = QQ2008.TextSub, modifier = Modifier.padding(12.dp))
        }
    }
}

// ==================== 群列表 ====================

@Composable
private fun GroupListView(onOpen: (QQFriend) -> Unit) {
    val groups = QQSession.friends.values.filter { it.isGroup }
    Column(Modifier.fillMaxSize()) {
        QqSectionBar(title = "我的群组", trailing = "${groups.size}个")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (groups.isEmpty()) {
                Text(
                    "还没有加入任何群\n点下方「查找」可以找到怀旧茶馆群",
                    fontSize = 12.sp, color = QQ2008.TextSub,
                    modifier = Modifier.padding(12.dp), lineHeight = 18.sp
                )
            }
            groups.forEach { g ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.65f))
                        .clickable { onOpen(g) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QqAvatar(g.avatar, 40.dp, g.status)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            g.displayName,
                            color = QQ2008.nickColor(g.number),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "群成员 ${g.groupMembers.size} 人 · ${g.signature}",
                            fontSize = 11.sp, color = QQ2008.TextSub,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ==================== 最近联系人 ====================

@Composable
private fun RecentView(onOpen: (QQFriend) -> Unit) {
    // 观察活动时间戳以在收发消息时刷新排序；一次性读取最近会话，避免每行磁盘 IO
    val tick = QQSession.lastActivityTick
    val acc = QQSession.account
    val recentList = remember(tick, acc?.number) {
        if (acc == null) emptyList()
        else QQStore.recentChatIds(acc.number).mapNotNull { id ->
            QQSession.friends[id]?.let { f ->
                Triple(f, QQStore.lastMessageOf(acc.number, id), id)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        QqSectionBar(title = "最近联系人", trailing = "${recentList.size}人")
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            if (recentList.isEmpty()) {
                Text(
                    "最近还没有聊过天\n双击好友即可开始会话",
                    fontSize = 12.sp, color = QQ2008.TextSub,
                    modifier = Modifier.padding(12.dp), lineHeight = 18.sp
                )
            }
            recentList.forEach { (f, last, _) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.65f))
                        .clickable { onOpen(f) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QqAvatar(f.avatar, 38.dp, f.status)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            f.displayName,
                            color = QQ2008.nickColor(f.number),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            last?.text?.lineSequence()?.firstOrNull() ?: "",
                            fontSize = 11.sp, color = QQ2008.TextSub,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    last?.let {
                        Text(
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                                .format(java.util.Date(it.time)),
                            fontSize = 10.sp, color = QQ2008.TimeText
                        )
                    }
                }
            }
        }
    }
}
