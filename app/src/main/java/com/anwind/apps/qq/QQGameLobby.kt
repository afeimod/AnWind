package com.anwind.apps.qq

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.window.WindowContentScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * QQ游戏大厅（仿 QQ游戏2011）：
 *
 *  [QQGameRoot]  窗口路由：login → lobby → room → table（牌桌在 QQGameDoudizhu.kt）
 *  [GameLoginScreen]   蓝色登录框（截图1）：无处不在的快乐 + 帐号/密码 + 登录
 *  [QQGameLobbyScreen] 大厅（截图3/4）：左游戏树/房间列表 + 右简介 + 在线人数
 *  [QQGameRoomScreen]  房间（截图4）：桌子网格 + 玩家列表，点空位进桌
 */

/** 一个游戏房间 */
data class QQGameRoom(val area: String, val name: String)

private val GAME_AREAS = listOf(
    "斗地主新手区" to (1..6).map { "新手房$it" },
    "斗地主普通区" to (1..4).map { "普通房$it" }
)

@Composable
fun QQGameRoot(scope: WindowContentScope) {
    var screen by remember { mutableStateOf("login") }
    var room by remember { mutableStateOf(QQGameRoom("斗地主新手区", "新手房1")) }

    LaunchedEffect(screen) {
        runCatching {
            scope.onTitleChange(
                when (screen) {
                    "login" -> "QQ游戏2011"
                    "table" -> "QQ游戏 - 斗地主（角色版）"
                    else -> "QQ游戏 - ${room.area} · ${room.name}"
                }
            )
        }
    }

    when (screen) {
        "login" -> GameLoginScreen(onOk = { screen = "lobby" })
        "lobby" -> QQGameLobbyScreen(
            onEnterRoom = { room = it; screen = "room" }
        )
        "room" -> QQGameRoomScreen(
            room = room,
            onBack = { screen = "lobby" },
            onSit = { screen = "table" }
        )
        "table" -> QQGameTableScreen(onExit = { screen = "room" })
    }
}

// ==================== 游戏登录框（仿截图1） ====================

@Composable
private fun GameLoginScreen(onOk: () -> Unit) {
    val acc = QQSession.account
    var entering by remember { mutableStateOf(false) }
    var infoText by remember { mutableStateOf<String?>(null) }
    val coroutine = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF9BD1F5), Color(0xFF4A9BDE))))
            .padding(10.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ===== 横幅 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 14.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🐧", fontSize = 42.sp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = "无处不在的快乐!",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontWeight = FontWeight.Medium
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "QQ游戏",
                            color = Color.White,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Black,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                        Text(
                            text = "2011",
                            color = Color(0xFFFFE95C),
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Black,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    }
                }
            }

            // ===== 登录卡片 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF2E86D5), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    "请输入帐号和密码：",
                    color = Color(0xFF1A3A5C),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))

                // 帐号行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("帐号：", color = Color(0xFF1A3A5C), fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(170.dp)
                            .height(26.dp)
                            .background(Color.White, RoundedCornerShape(3.dp))
                            .border(1.dp, Color(0xFF6FA6D8), RoundedCornerShape(3.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            acc?.number ?: "请输入QQ号码或绑定的Email帐号",
                            fontSize = 12.sp,
                            color = if (acc != null) Color(0xFF1A3A5C) else Color(0xFFB8C6D4),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "申请帐号",
                        color = Color(0xFF0B62C4),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            infoText = "游戏大厅直接使用当前 QQ 登录的帐号（${acc?.number ?: "未登录"}），无需另外申请。"
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))

                // 密码行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("密码：", color = Color(0xFF1A3A5C), fontSize = 13.sp)
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .width(170.dp)
                            .height(26.dp)
                            .background(Color.White, RoundedCornerShape(3.dp))
                            .border(1.dp, Color(0xFF6FA6D8), RoundedCornerShape(3.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = "",
                            onValueChange = {},
                            singleLine = true,
                            readOnly = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFF1A3A5C)),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("●●●●●●●●", fontSize = 11.sp, color = Color(0xFF8FA8BE))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "忘了密码?",
                        color = Color(0xFF0B62C4),
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            infoText = "游戏大厅与 QQ2008 共用帐号密码，在 QQ 登录界面找回即可。"
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))

                // 勾选行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var rememberPwd by remember { mutableStateOf(true) }
                    var invisible by remember { mutableStateOf(false) }
                    var agree by remember { mutableStateOf(true) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { rememberPwd = !rememberPwd }
                    ) {
                        QqCheckbox(rememberPwd)
                        Spacer(Modifier.width(3.dp))
                        Text("记住密码", fontSize = 12.sp, color = Color(0xFF1A3A5C))
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { invisible = !invisible }
                    ) {
                        QqCheckbox(invisible)
                        Spacer(Modifier.width(3.dp))
                        Text("隐身登录", fontSize = 12.sp, color = Color(0xFF1A3A5C))
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { agree = !agree }
                    ) {
                        QqCheckbox(agree)
                        Spacer(Modifier.width(3.dp))
                        Text("同意条款", fontSize = 12.sp, color = Color(0xFF1A3A5C))
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 按钮行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QqBeigeButton("设置 ▾", { }, enabled = false, height = 28.dp, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    QqBeigeButton(
                        text = if (entering) "登录中…" else "登 录",
                        enabled = !entering,
                        height = 30.dp,
                        fontSize = 15.sp,
                        onClick = {
                            QQSounds.click()
                            entering = true
                            coroutine.launch {
                                delay(500)   // 2008 年的登录等待
                                QQSounds.ding()
                                onOk()
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "提示：游戏大厅与 QQ2008 共用登录状态；斗地主里其它玩家暂时由 AI 扮演。",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    infoText?.let {
        QQInfoDialog(title = "QQ游戏", text = it, onDismiss = { infoText = null })
    }
}

// ==================== 大厅（仿截图3/4） ====================

@Composable
private fun QQGameLobbyScreen(onEnterRoom: (QQGameRoom) -> Unit) {
    val acc = QQSession.account
    var localExpand by remember { mutableStateOf(true) }
    var gameExpand by remember { mutableStateOf(true) }
    var showIntro by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    var infoText by remember { mutableStateOf<String?>(null) }
    // 同时在线人数（进大厅时随机一个 2008 味十足的数）
    val online = remember { 4_800_000 + Random.nextInt(900_000) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QQ2008.panelBrush())
    ) {
        // ===== 顶部：用户 + 入口按钮 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QqAvatar(avatar = acc?.avatar ?: "emoji:🐧", size = 34.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    acc?.nickname ?: "游客",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    "斗地主积分：${QQGameScores.load()[acc?.nickname ?: ""] ?: 0}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.weight(1f))
            listOf("🏠" to "官方网站", "🛒" to "游戏商城", "💎" to "蓝钻贵族").forEach { (emoji, label) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable {
                            QQSounds.click()
                            infoText = when (label) {
                                "官方网站" -> "QQ游戏 2011 · AnWind 复刻版。\n官方网站正在穿越 2008 年的路上…"
                                "游戏商城" -> "商城售卖回忆：皮肤、道具、欢乐豆。\n（本版本暂未开放购买）"
                                else -> "蓝钻贵族：斗地主专属身份标识，\n每周免费送欢乐豆！（敬请期待）"
                            }
                        }
                        .padding(horizontal = 8.dp)
                ) {
                    Text(emoji, fontSize = 16.sp)
                    Text(label, color = Color.White, fontSize = 9.sp, maxLines = 1)
                }
            }
        }

        // ===== 搜索条 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .background(Color.White, RoundedCornerShape(13.dp))
                    .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(13.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, color = QQ2008.TextMain),
                    modifier = Modifier.fillMaxWidth()
                )
                if (search.isEmpty()) {
                    Text("🔍 查找游戏…", fontSize = 11.sp, color = Color(0xFF9AAFC2))
                }
            }
        }

        // ===== 主体 =====
        Row(Modifier.weight(1f)) {
            // --- 左侧游戏树 ---
            Column(
                modifier = Modifier
                    .width(198.dp)
                    .fillMaxSize()
                    .background(Color.White)
                    .border(width = 1.dp, color = QQ2008.PanelBorder)
                    .verticalScroll(rememberScrollState())
            ) {
                TreeRow("📁 我的游戏 (0)", gray = true)
                TreeRow(if (localExpand) "📂 本地游戏" else "▶ 本地游戏") { localExpand = !localExpand }
                if (localExpand) {
                    if ("斗地主".contains(search.trim()) || search.isBlank()) {
                        TreeRow(if (gameExpand) "▼ 🃏 斗地主（角色版）" else "▶ 🃏 斗地主（角色版）") {
                            showIntro = true
                            gameExpand = !gameExpand
                        }
                        if (gameExpand) {
                            GAME_AREAS.forEach { (area, rooms) ->
                                Text(
                                    text = "🚩 $area",
                                    fontSize = 12.sp,
                                    color = QQ2008.TextMain,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF2FAFE))
                                        .padding(start = 22.dp, top = 4.dp, bottom = 4.dp)
                                )
                                rooms.forEach { r ->
                                    val online2 = remember(r) { Random.nextInt(0, 4) }
                                    TreeRow("$r  ($online2/3人)", indent = 34) {
                                        QQSounds.click()
                                        onEnterRoom(QQGameRoom(area, r))
                                    }
                                }
                            }
                        }
                    }
                    TreeRow("🎲 悠悠球（敬请期待）", gray = true)
                    TreeRow("♟️ 中国象棋（敬请期待）", gray = true)
                    TreeRow("📌 更多游戏 敬请期待", gray = true)
                }
            }
            Spacer(Modifier.width(6.dp))

            // --- 右侧简介 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                if (showIntro) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🃏", fontSize = 30.sp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("斗地主（角色版）", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QQ2008.TextMain)
                            Text("本地游戏 ·  3 人桌 · 即点即玩", fontSize = 11.sp, color = QQ2008.TextSub)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .background(
                                Brush.verticalGradient(listOf(Color(0xFF2C5A8F), Color(0xFF1B3A61))),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🃏 🂡 🂱", fontSize = 30.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "叫地主 · 抢地主 · 炸弹翻倍",
                                color = Color(0xFFFFE95C),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "【简介】\n斗地主是一种 3 人扑克牌玩法：一方为地主，另两方为农民，先出完手牌的一方获胜。" +
                                "炸弹和王炸可使积分倍数翻番，春天再翻一番。\n\n【玩法】\n1. 进入房间，点击桌子上的空位入座；\n" +
                                "2. 叫分（1/2/3 分）决定地主，地主拿 3 张底牌；\n" +
                                "3. 地主先出牌，压不过可以\"不出\"；\n4. 最先出完手牌的一方获胜。\n\n" +
                                "【积分】地主赢：+2×底分×倍数；农民赢：每人 +1×底分×倍数。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = QQ2008.TextMain
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Text("🎉 欢迎来到 QQ游戏 2011！", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QQ2008.TextMain)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "· 左侧选择游戏，点击房间即可开局\n· 斗地主（角色版）已就绪，桌上的其它玩家暂时由 AI 扮演\n" +
                                "· 更多游戏陆续穿越中，敬请期待",
                        fontSize = 12.sp, lineHeight = 19.sp, color = QQ2008.TextMain
                    )
                }
            }
        }

        // ===== 底部状态条 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush())
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "🌐 同时在线人数：${String.format("%,d", online)}",
                color = Color.White,
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text("QQ游戏 2011 · AnWind 复刻版", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
        }
    }

    infoText?.let {
        QQInfoDialog(title = "QQ游戏", text = it, onDismiss = { infoText = null })
    }
}

@Composable
private fun TreeRow(text: String, indent: Int = 10, gray: Boolean = false, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = if (gray) Color(0xFF9AA6B4) else QQ2008.TextMain,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(start = indent.dp, top = 5.dp, bottom = 5.dp)
    )
}

// ==================== 房间（仿截图4：桌子网格 + 玩家列表） ====================

@Composable
private fun QQGameRoomScreen(room: QQGameRoom, onBack: () -> Unit, onSit: () -> Unit) {
    // 12 张桌子，随机已入座人数（保证至少一张有空位）
    val tables = remember(room) {
        val list = List(12) { Random.nextInt(0, 4) }
        if (list.all { it >= 3 }) list.toMutableList().also { it[0] = 2 } else list
    }
    val roomPlayers = remember(room) {
        List(Random.nextInt(12, 25)) { i ->
            val pool = listOf("林中漫步者", "了了", "眼暗", "问天", "随风而逝", "一品白衫", "半支烟", "糖醋里脊", "水木清华", "披着狼皮的羊")
            pool[i % pool.size] + (if (i >= pool.size) "${i / pool.size}" else "")
        }.distinct()
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF1E4E86))) {
        // ===== 顶部 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "⬅ 返回大厅",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { QQSounds.click(); onBack() }
            )
            Spacer(Modifier.weight(1f))
            Text("$room · ${roomPlayers.size}人在线", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("🃏 斗地主（角色版）", color = Color(0xFFFFE95C), fontSize = 12.sp)
        }

        Row(Modifier.weight(1f)) {
            // ===== 桌子区 =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                tables.chunked(3).forEachIndexed { rowIdx, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEachIndexed { colIdx, occupied ->
                            val tableNo = rowIdx * 3 + colIdx + 1
                            GameTableItem(
                                no = tableNo,
                                occupied = occupied,
                                onSit = {
                                    QQSounds.ding()
                                    onSit()
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // ===== 右侧玩家列表 =====
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .fillMaxSize()
                    .background(Color(0xFFEAF6FE))
                    .border(1.dp, QQ2008.PanelBorder)
            ) {
                QqSectionBar(title = "房间内玩家", leading = "👥")
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    roomPlayers.forEachIndexed { i, name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("😀", fontSize = 13.sp)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                name,
                                fontSize = 11.sp,
                                color = QQ2008.TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.weight(1f))
                            Text(ddTitle(3 + (i * 7) % 35), fontSize = 9.sp, color = QQ2008.TextSub)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameTableItem(no: Int, occupied: Int, onSit: () -> Unit) {
    val full = occupied >= 3
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 132.dp, height = 74.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF3E7CB8), Color(0xFF2A5A8C))),
                    RoundedCornerShape(50)
                )
                .border(2.dp, if (full) Color(0xFF7C93A8) else Color(0xFFBFE4FB), RoundedCornerShape(50))
                .clickable(enabled = !full) { onSit() },
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { seat ->
                    if (seat < occupied) {
                        Text("🐧", fontSize = 15.sp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("＋", color = Color(0xFFFFE95C), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "桌 $no · ${if (full) "满员" else "$occupied/3人 · 点击空位加入"}",
            fontSize = 10.sp,
            color = if (full) Color(0xFF9DB6CC) else Color(0xFFFFE95C)
        )
    }
}
