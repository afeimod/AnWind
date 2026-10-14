package com.anwind.apps.qq

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.window.WindowContentScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * QQ2008 聊天窗口（独立 WindowState，launchArgs["chat"] = 对方号码）。
 *
 * 还原 2008 聊天窗布局：
 *  - 菜单栏：聊天 / 娱乐 / 应用 / 工具
 *  - 工具条：短信 视频 语音 传文件 邀请 举报（2008 装饰项）+ 抖动
 *  - 消息区：经典消息样式（头像 + 彩色昵称(号码) + 时间 + 内容）
 *  - 输入区：A字号 / 表情面板 / 窗口抖动 / 聊天记录 + 输入框 + 发送(S)
 *  - 宽窗口时右侧显示"对方形象"卡片
 *
 * 抖动动画作用于窗口内容（不操纵 WindowState，避免与拖拽提交冲突）。
 */
@Composable
fun QQChatScreen(scope: WindowContentScope, chatId: String) {
    val acc = QQSession.account
    val contact = QQSession.friends[chatId]
    if (acc == null || contact == null) {
        // 未登录 / 联系人已删除
        Box(
            modifier = Modifier.fillMaxSize().background(QQ2008.panelBrush()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (acc == null) "请先从主面板登录 QQ" else "该联系人不存在或已被删除",
                color = QQ2008.TextSub, fontSize = 13.sp
            )
        }
        return
    }

    val wm = remember { com.anwind.core.window.WindowManager.get() }
    val messages = QQSession.chatMessages(chatId)
    var input by remember { mutableStateOf("") }
    var fontScale by remember { mutableStateOf(0) }       // 0=正常 1=大 2=特大
    var emojiOpen by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf<String?>(null) }    // 当前打开的菜单名（null=全部关闭；"showSettings"=设置弹层）
    val shake = remember { Animatable(0f) }
    val listState = rememberScrollState()

    LaunchedEffect(Unit) {
        runCatching {
            scope.onTitleChange("与 ${contact.displayName} 聊天中")
            scope.windowState.width = 660
            scope.windowState.height = 530
            wm.commitChanges()
            // 初次打开且无记录：AI 先打招呼（2008 情怀：好友主动问好）
            if (messages.isEmpty()) {
                QQSession.appendSystemMessage(chatId, "已与 ${contact.displayName} 建立会话")
                QQSession.requestAiReply(chatId)
            }
        }.onFailure { QQSession.logCrash("chatScreenLayout", it) }
    }

    // 新消息到达：滚到底 + 清未读 + 提示音（跳过首帧历史加载）
    val initialSize = remember { messages.size }
    LaunchedEffect(messages.size) {
        kotlinx.coroutines.delay(80)   // 等布局完成后再取 maxValue
        listState.animateScrollTo(listState.maxValue)
        QQSession.clearUnread(chatId)
        if (messages.size > initialSize) {
            val last = messages.lastOrNull()
            if (last != null && !last.isSelf && last.type == QQMsgType.TEXT) QQSounds.msgIn()
            if (last != null && last.type == QQMsgType.NUDGE && !last.isSelf) {
                QQSounds.nudge()
                shake.snapTo(0f)
                shake.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 550
                        0f at 0
                        -10f at 60
                        10f at 120
                        -8f at 190
                        8f at 260
                        -5f at 330
                        5f at 400
                        -2f at 470
                        0f at 550
                    }
                )
            }
        }
    }

    // toast 自动消失
    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(1800)
            toast = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(QQ2008.panelBrush())
            .border(1.dp, QQ2008.PanelBorder)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(shake.value.roundToInt().dp.roundToPx(), 0) }
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            // ===== 菜单栏 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QQ2008.ChatMenuBg, RoundedCornerShape(4.dp))
                    .border(0.5.dp, QQ2008.PanelBorder.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QQMenuBar("聊天", menuOpen, { menuOpen = it }) { close ->
                    DropdownMenuItem(
                        text = { Text("清空聊天记录", fontSize = 13.sp) },
                        onClick = {
                            close()
                            QQStore.clearChat(QQSession.account?.number ?: "", chatId)
                            QQSession.clearChatCache(chatId)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("关闭窗口", fontSize = 13.sp) },
                        onClick = { close(); wm.close(scope.windowState.id) }
                    )
                }
                QQMenuBar("娱乐", menuOpen, { menuOpen = it }) { close ->
                    DropdownMenuItem(
                        text = { Text("窗口抖动", fontSize = 13.sp) },
                        onClick = {
                            close()
                            QQSession.sendNudge(chatId)
                            QQSounds.nudge()
                            QQSession.requestAiReply(chatId)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("表情", fontSize = 13.sp) },
                        onClick = { close(); emojiOpen = !emojiOpen }
                    )
                }
                QQMenuBar("应用", menuOpen, { menuOpen = it }) { close ->
                    DropdownMenuItem(
                        text = { Text("截图 (Ctrl+Alt+A)", fontSize = 13.sp, color = Color.Gray) },
                        onClick = { close(); toast = "截图功能正在穿越中，敬请期待" }
                    )
                    DropdownMenuItem(
                        text = { Text("QQ农场", fontSize = 13.sp, color = Color.Gray) },
                        onClick = { close(); toast = "你的菜已经 18 年没种了…" }
                    )
                }
                QQMenuBar("工具", menuOpen, { menuOpen = it }) { close ->
                    DropdownMenuItem(
                        text = { Text("系统设置", fontSize = 13.sp) },
                        onClick = { close(); /* 用整窗弹层，见底部 */ menuOpen = "showSettings" }
                    )
                }
            }

            // ===== 工具条 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButton("✉ 短信") { toast = "短信功能是 2008 的浪漫，服务器版开放" }
                ToolButton("📹 视频") { toast = "对方视频头信号穿越中…" }
                ToolButton("🎤 语音") { toast = "语音聊天将在服务器版开放" }
                ToolButton("📎 传文件") { toast = "传文件将在服务器版开放" }
                ToolButton("👥 邀请") { toast = "多人会话将在服务器版开放" }
                ToolButton("⚠ 举报") { toast = "AI 好友都很乖，不用举报 (＾▽＾)" }
                Spacer(Modifier.weight(1f))
                Text(
                    if (contact.isGroup) "群：${contact.groupMembers.size}人" else "对方号码 ${contact.number}",
                    fontSize = 10.sp, color = QQ2008.TextSub
                )
            }

            // ===== 消息区 + （宽窗）对方形象 =====
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                MessageArea(
                    messages = messages,
                    contact = contact,
                    selfName = acc.nickname,
                    selfNumber = acc.number,
                    fontScale = fontScale,
                    showHistory = showHistory,
                    modifier = Modifier.weight(1f)
                )
                // 右侧形象栏（仅在宽窗口显示）
                if (scope.windowState.width >= 560) {
                    Column(
                        modifier = Modifier
                            .width(110.dp)
                            .padding(start = 4.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        QqSectionBar("对方形象", Modifier.fillMaxWidth(), leading = null)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .border(1.dp, QQ2008.PanelBorder.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            QqAvatar(contact.avatar, 72.dp, contact.status)
                        }
                        Text(
                            contact.displayName,
                            fontSize = 12.sp, color = QQ2008.TextMain,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            contact.signature, fontSize = 10.sp,
                            color = QQ2008.TextSub, maxLines = 3,
                            lineHeight = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        QqSectionBar("我的形象", Modifier.fillMaxWidth(), leading = null)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .border(1.dp, QQ2008.PanelBorder.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                .padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            QqAvatar(acc.avatar, 72.dp, acc.status)
                        }
                        Text(
                            acc.nickname, fontSize = 12.sp, color = QQ2008.TextMain,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ===== 正在输入 =====
            if (QQSession.typing[chatId] == true) {
                Text(
                    "${contact.displayName} 正在输入…",
                    fontSize = 11.sp, color = QQ2008.TextSub,
                    modifier = Modifier.padding(start = 4.dp, top = 1.dp)
                )
            }

            // ===== 表情面板 =====
            if (emojiOpen) {
                EmojiPanel(
                    onPick = { e -> input += e },
                    onClose = { emojiOpen = false }
                )
            }

            // ===== 输入区 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .background(QQ2008.InputAreaBg, RoundedCornerShape(4.dp))
                    .border(1.dp, QQ2008.PanelBorder.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            ) {
                // 输入工具行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(QQ2008.ChatMenuBg)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 字号循环：正常 → 大 → 特大
                    Text(
                        "A", fontSize = (12 + fontScale * 3).sp,
                        fontWeight = FontWeight.Bold, color = QQ2008.TextMain,
                        modifier = Modifier
                            .clickable { fontScale = (fontScale + 1) % 3 }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        "😊", fontSize = 14.sp,
                        modifier = Modifier
                            .clickable { emojiOpen = !emojiOpen }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        "🫨 抖动", fontSize = 12.sp,
                        modifier = Modifier
                            .clickable {
                                QQSession.sendNudge(chatId)
                                QQSounds.nudge()
                                QQSession.requestAiReply(chatId)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Text(
                        if (showHistory) "📋 收起记录" else "📋 聊天记录",
                        fontSize = 12.sp, color = QQ2008.TextMain,
                        modifier = Modifier
                            .clickable { showHistory = !showHistory }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "输入字数 ${input.length}",
                        fontSize = 10.sp, color = QQ2008.TimeText,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                // 发送动作（按钮与键盘 IME 共用）
                val sendAction: () -> Unit = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        QQSession.sendText(chatId, text)
                        QQSounds.msgOut()
                        QQSession.requestAiReply(chatId)
                        input = ""
                    }
                }
                // 输入框（Enter 发送）
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(fontSize = (14 + fontScale * 2).sp, color = Color(0xFF222222), lineHeight = (20 + fontScale * 3).sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendAction() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .padding(8.dp)
                )
                // 底部按钮行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "按 Enter 发送消息",
                        fontSize = 10.sp, color = QQ2008.TimeText
                    )
                    Spacer(Modifier.weight(1f))
                    QqBeigeButton(
                        "关闭(C)",
                        { wm.close(scope.windowState.id) },
                        Modifier.width(76.dp), height = 28.dp, fontSize = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    QqBeigeButton(
                        "发送(S)",
                        sendAction,
                        Modifier.width(76.dp), height = 28.dp, fontSize = 12.sp,
                        enabled = input.isNotBlank()
                    )
                }
            }
        }

        // toast
        toast?.let { t ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(Color(0xEE3A4A5A), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(t, color = Color.White, fontSize = 12.sp)
            }
        }

        // 工具菜单里的"系统设置"弹层
        if (menuOpen == "showSettings") {
            QQSettingsDialog(onDismiss = { menuOpen = null })
        }
    }
}

/**
 * 菜单栏项：文字 + 下拉。
 *
 * @param render 菜单项渲染；参数 close 用于点击后关闭菜单
 */
@Composable
private fun QQMenuBar(
    label: String,
    openMenu: String?,
    setOpen: (String?) -> Unit,
    items: @Composable ((close: () -> Unit) -> Unit)
) {
    Box {
        Text(
            label,
            fontSize = 13.sp,
            color = QQ2008.TextMain,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clickable { setOpen(if (openMenu == label) null else label) }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        DropdownMenu(
            expanded = openMenu == label,
            onDismissRequest = { setOpen(null) }
        ) {
            items { setOpen(null) }
        }
    }
}

/** 工具条按钮 */
@Composable
private fun ToolButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        color = QQ2008.TextMain,
        modifier = Modifier
            .clickable { onClick() }
            .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
            .border(0.5.dp, QQ2008.PanelBorder.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/**
 * 经典消息区：QQ2008 默认模式 ——
 *   [头像] 昵称(号码)  时间
 *          消息内容
 *   --------------------------------
 * 自己昵称蓝色、对方暗红；nudge/system 居中灰字。
 */
@Composable
private fun MessageArea(
    messages: List<QQMessage>,
    contact: QQFriend,
    selfName: String,
    selfNumber: String,
    fontScale: Int,
    showHistory: Boolean,
    modifier: Modifier = Modifier
) {
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA) }
    val bodyFontSize = (14 + fontScale * 2).sp

    Column(modifier = modifier) {
        if (showHistory) {
            // 聊天记录面板（只读全量，浅黄底）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(Color(0xFFFCF7E8), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFE2CE9E), RoundedCornerShape(4.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Text("聊天记录（${messages.size} 条）", fontSize = 11.sp, color = Color(0xFF8A6D3B))
                messages.forEach { m ->
                    Text(
                        "[${timeFmt.format(Date(m.time))}] ${if (m.isSelf) "我" else m.senderName}：${m.text.take(60)}",
                        fontSize = 10.sp,
                        color = Color(0xFF6B5B33),
                        lineHeight = 14.sp
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(QQ2008.ChatAreaBg, RoundedCornerShape(4.dp))
                .border(1.dp, QQ2008.PanelBorder.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (messages.isEmpty()) {
                Text(
                    "暂无消息，输入内容开始聊天吧～",
                    fontSize = 12.sp, color = QQ2008.TimeText
                )
            }
            messages.forEach { m ->
                when (m.type) {
                    QQMsgType.SYSTEM -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(m.text, fontSize = 11.sp, color = QQ2008.TimeText)
                    }
                    QQMsgType.NUDGE -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            " (${m.text}) ",
                            fontSize = 11.sp,
                            color = Color(0xFFB0642A),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    else -> {
                        val isSelf = m.isSelf
                        // 单聊显示对方头像；群聊按发言人匹配内置人格头像
                        val senderAvatar = if (isSelf) {
                            QQSession.account?.avatar ?: "emoji:🐧"
                        } else {
                            QQAi.Personas.byNumber(m.senderId)?.avatar ?: contact.avatar
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            QqAvatar(senderAvatar, 32.dp, null)
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        if (isSelf) selfName else m.senderName,
                                        color = if (isSelf) QQ2008.SelfNick else QQ2008.FriendNick,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "(${if (isSelf) selfNumber else m.senderId})",
                                        fontSize = 10.sp, color = QQ2008.TimeText
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        timeFmt.format(Date(m.time)),
                                        fontSize = 10.sp, color = QQ2008.TimeText
                                    )
                                }
                                // 群聊多行回复按行拆分渲染
                                m.text.split("\n\n").forEach { part ->
                                    Text(
                                        part,
                                        color = Color(0xFF222222),
                                        fontSize = bodyFontSize,
                                        lineHeight = (bodyFontSize.value * 1.45f).sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                        }
                        // 分隔线（经典样式）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(QQ2008.PanelBorder.copy(alpha = 0.25f))
                        )
                    }
                }
            }
        }
    }
}

/**
 * 表情面板（经典 QQ 泛黄底 + emoji 网格）。
 */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit, onClose: () -> Unit) {
    val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
        "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😙",
        "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔",
        "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😴",
        "😪", "😵", "🤯", "🥱", "😷", "🤒", "🤕", "🥺", "😢", "😭",
        "😤", "😠", "😡", "🤬", "😱", "😨", "😰", "😥", "😓", "😳",
        "👍", "👎", "👌", "✌️", "🤞", "🤝", "🙏", "💪", "🌹", "🍻",
        "🌸", "🌈", "☀️", "🌙", "⭐", "🔥", "💧", "🎁", "🎂", "🐧"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp)
            .background(Color(0xFFFCF7E8), RoundedCornerShape(4.dp))
            .border(1.dp, Color(0xFFE2CE9E), RoundedCornerShape(4.dp))
            .padding(6.dp)
    ) {
        Row {
            Text("经典表情", fontSize = 11.sp, color = Color(0xFF8A6D3B), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "收起 ▴", fontSize = 11.sp, color = Color(0xFF8A6D3B),
                modifier = Modifier.clickable { onClose() }.padding(2.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            emojis.chunked(10).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { e ->
                        Text(
                            e,
                            fontSize = 19.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .clickable { onPick(e) }
                                .padding(2.dp)
                        )
                    }
                }
            }
        }
    }
}
