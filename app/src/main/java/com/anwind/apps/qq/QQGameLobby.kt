package com.anwind.apps.qq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.window.WindowContentScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * QQ游戏大厅（仿 QQ游戏2011）：
 *
 *  [QQGameRoot]  窗口路由：login → lobby → room → table
 *  [GameLoginScreen]   蓝色登录框：无处不在的快乐 + 帐号/密码 + 登录
 *  [QQGameLobbyScreen] 大厅：左游戏树（4 款本地游戏）/房间列表 + 右简介 + 在线人数
 *  [QQGameRoomScreen]  房间：桌子（按游戏人数 三角/方形/对坐 排列）+ 玩家列表
 *  牌桌：斗地主 QQGameTableScreen / 象棋 QQChessTableScreen /
 *        五子棋 QQGomokuTableScreen / 麻将 QQMahjongTableScreen
 *
 *  背景音乐：大厅/房间/各游戏各一首（QQBgm），右上角 🎵 可开关。
 */

// ==================== 游戏注册表 ====================

/** 一款本地游戏 */
data class GameDef(
    val id: String,
    val title: String,
    val emoji: String,
    val seats: Int,
    val tagline: String,
    val intro: String,
    val areas: List<Pair<String, List<String>>>
)

val GAME_DEFS = listOf(
    GameDef(
        id = "ddz",
        title = "斗地主（角色版）",
        emoji = "🃏",
        seats = 3,
        tagline = "叫地主 · 抢地主 · 炸弹翻倍",
        intro = "【简介】\n斗地主是一种 3 人扑克牌玩法：一方为地主，另两方为农民，先出完手牌的一方获胜。" +
                "炸弹和王炸可使积分倍数翻番，春天再翻一番。\n\n【玩法】\n1. 进入房间，点击桌子上的空位入座；\n" +
                "2. 叫分（1/2/3 分）决定地主，地主拿 3 张底牌；\n" +
                "3. 地主先出牌，压不过可以\"不出\"；\n4. 最先出完手牌的一方获胜。\n\n" +
                "【积分】地主赢：+2×底分×倍数；农民赢：每人 +1×底分×倍数。",
        areas = listOf(
            "斗地主新手区" to (1..6).map { "新手房$it" },
            "斗地主普通区" to (1..4).map { "普通房$it" }
        )
    ),
    GameDef(
        id = "chess",
        title = "中国象棋",
        emoji = "♟️",
        seats = 2,
        tagline = "楚河汉界 · 马炮争雄",
        intro = "【简介】\n中国象棋是流传千年的双人棋类：车马炮各司其职，帅（将）不可照面，" +
                "将被将死或无路可走的一方告负。\n\n【玩法】\n1. 进入房间，点击座位对弈；\n" +
                "2. 你执红先行：点选己方棋子，再点绿点走子、红圈吃子；\n" +
                "3. AI 深算 3 层，小心它的卧槽马与铁门栓！\n\n" +
                "【积分】胜 +5，负 -5（自动存入战绩）。",
        areas = listOf(
            "楚河区" to listOf("当头炮房", "屏风马房", "仙人指路房"),
            "汉界区" to listOf("飞相局房", "过宫炮房")
        )
    ),
    GameDef(
        id = "gomoku",
        title = "五子棋",
        emoji = "⚫",
        seats = 2,
        tagline = "黑白对弈 · 五连即胜",
        intro = "【简介】\n五子棋规则简单、变化无穷：十五路棋盘上，横、竖或斜先连成五子者胜。\n\n" +
                "【玩法】\n1. 进入房间，点击座位开局；\n2. 你执黑先行，点击棋盘交叉点落子；\n" +
                "3. AI 会堵你的活三冲四，想赢要多做「双三」棋形。\n\n" +
                "【积分】胜 +2，负 -2（自动存入战绩）。",
        areas = listOf(
            "五子棋新手区" to listOf("初学房1", "初学房2", "初学房3"),
            "五子棋高手区" to listOf("高手房1", "高手房2")
        )
    ),
    GameDef(
        id = "mahjong",
        title = "欢乐麻将",
        emoji = "🀄",
        seats = 4,
        tagline = "碰杠胡牌 · 自摸翻倍",
        intro = "【简介】\n欢乐麻将只用 万/筒/条 三门花色（108 张），节奏明快：可以碰、可以杠，" +
                "不能吃；凑齐四搭子加一对将即胡牌。\n\n【玩法】\n1. 进入房间，四人桌即开；\n" +
                "2. 点选手牌后按\"出牌\"打出；能碰/杠/胡时按按钮确认；\n" +
                "3. 自摸每家付 2 分（+6），点炮点炮者付 4 分；明杠/暗杠另计分。\n\n" +
                "【积分】每局自动结算并存入战绩。",
        areas = listOf(
            "欢乐麻将区" to listOf("血战房1", "血战房2", "血战房3"),
            "欢乐自摸区" to listOf("自摸房1", "自摸房2")
        )
    )
)

/** 一个游戏房间 */
data class QQGameRoom(val gameId: String, val area: String, val name: String)

@Composable
fun QQGameRoot(scope: WindowContentScope) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf("login") }
    var room by remember { mutableStateOf(QQGameRoom("ddz", "斗地主新手区", "新手房1")) }
    val gameDef = GAME_DEFS.first { it.id == room.gameId }

    LaunchedEffect(screen) {
        runCatching {
            scope.onTitleChange(
                when (screen) {
                    "login" -> "QQ游戏2011"
                    "table" -> "QQ游戏 - ${gameDef.title} 角色版"
                    else -> "QQ游戏 - ${gameDef.title}[${room.name}]"
                }
            )
        }
    }

    // ---- 背景音乐：按场景切曲；离开游戏窗口停掉 ----
    LaunchedEffect(screen, room) {
        when (screen) {
            "lobby" -> QQBgm.scene(context, "bgm_lobby")
            "room" -> QQBgm.scene(context, "bgm_room")
            "table" -> QQBgm.scene(
                context,
                when (room.gameId) {
                    "ddz" -> "bgm_ddz"
                    "mahjong" -> "bgm_mj"
                    else -> "bgm_board"
                }
            )
            else -> QQBgm.stop()
        }
    }
    DisposableEffect(Unit) {
        onDispose { QQBgm.stop() }
    }

    when (screen) {
        "login" -> GameLoginScreen(onOk = { screen = "lobby" })
        "lobby" -> QQGameLobbyScreen(onEnterRoom = { room = it; screen = "room" })
        "room" -> QQGameRoomScreen(
            room = room,
            onBack = { screen = "lobby" },
            onSit = { screen = "table" }
        )
        "table" -> when (room.gameId) {
            "chess" -> QQChessTableScreen(onExit = { screen = "room" })
            "gomoku" -> QQGomokuTableScreen(onExit = { screen = "room" })
            "mahjong" -> QQMahjongTableScreen(onExit = { screen = "room" })
            else -> QQGameTableScreen(onExit = { screen = "room" })
        }
    }
}

/** 大厅/房间通用的音乐开关（点击切换，下方滑块调音量） */
@Composable
fun MusicToggle(playingScene: String) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(QQSession.settings.musicEnabled) }
    var vol by remember { mutableStateOf(QQSession.settings.musicVolume.coerceIn(0, 100)) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(56.dp)
    ) {
        Text(
            if (on) "🎵" else "🔇", fontSize = 15.sp,
            modifier = Modifier.clickable {
                QQSounds.click()
                on = QQBgm.toggle(context)
                if (on) QQBgm.scene(context, playingScene)
            }
        )
        Text(if (on) "音乐" else "静音", color = Color.White, fontSize = 9.sp, maxLines = 1)
        if (on) {
            Slider(
                value = vol / 100f,
                onValueChange = {
                    vol = (it * 100).toInt().coerceIn(0, 100)
                    QQBgm.applyVolume(vol)
                },
                onValueChangeFinished = {
                    QQSession.saveSettings(QQSession.settings.copy(musicVolume = vol))
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFE95C),
                    activeTrackColor = Color(0xFFFFE95C),
                    inactiveTrackColor = Color(0x66FFFFFF)
                ),
                modifier = Modifier.width(52.dp).height(18.dp)
            )
        }
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
                "提示：与 QQ2008 共用登录状态；斗地主 / 中国象棋 / 五子棋 / 欢乐麻将 已就绪，桌上的其它玩家由 AI 扮演。",
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

// ==================== 大厅（左游戏树 + 右简介） ====================

@Composable
private fun QQGameLobbyScreen(onEnterRoom: (QQGameRoom) -> Unit) {
    val acc = QQSession.account
    var expandedGame by remember { mutableStateOf("ddz") }
    var introGame by remember { mutableStateOf("ddz") }
    var search by remember { mutableStateOf("") }
    var infoText by remember { mutableStateOf<String?>(null) }
    val scores = remember(acc?.nickname) { QQGameScores.load() }
    // 同时在线人数（进大厅时随机一个 2008 味十足的数）
    val online = remember { 4_800_000 + Random.nextInt(900_000) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QQ2008.panelBrush())
    ) {
        // ===== 顶部：用户 + 音乐 + 入口按钮 =====
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
                val total = scores.values.sum()   // 斗地主 + 象棋 + 五子棋 + 麻将 全部战绩
                Text(
                    "总战绩积分：$total",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.weight(1f))
            MusicToggle(playingScene = "bgm_lobby")
            listOf("🏠" to "官方网站", "🛒" to "游戏商城", "💎" to "蓝钻贵族").forEach { (emoji, label) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable {
                            QQSounds.click()
                            infoText = when (label) {
                                "官方网站" -> "QQ游戏 2011 · AnWind 复刻版。\n官方网站正在穿越 2008 年的路上…"
                                "游戏商城" -> "商城售卖回忆：皮肤、道具、欢乐豆。\n（本版本暂未开放购买）"
                                else -> "蓝钻贵族：全游戏专属身份标识，\n每周免费送欢乐豆！（敬请期待）"
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
                TreeRow("📁 我的游戏 (4)", gray = true)
                TreeRow("📂 本地游戏 (4款可玩)")
                val kw = search.trim()
                GAME_DEFS.filter { kw.isBlank() || it.title.contains(kw) }.forEach { g ->
                    TreeRow(
                        if (expandedGame == g.id) "▼ ${g.emoji} ${g.title}" else "▶ ${g.emoji} ${g.title}"
                    ) {
                        introGame = g.id
                        expandedGame = if (expandedGame == g.id) "" else g.id
                        QQSounds.click()
                    }
                    if (expandedGame == g.id) {
                        g.areas.forEach { (area, rooms) ->
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
                                val cnt = remember(g.id, r) { Random.nextInt(0, g.seats + 1) }
                                TreeRow("$r  ($cnt/${g.seats}人)", indent = 34) {
                                    QQSounds.click()
                                    onEnterRoom(QQGameRoom(g.id, area, r))
                                }
                            }
                        }
                    }
                }
                TreeRow("📌 更多游戏 敬请期待", gray = true)
            }
            Spacer(Modifier.width(6.dp))

            // --- 右侧简介 ---
            val g = GAME_DEFS.first { it.id == introGame }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.emoji, fontSize = 30.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(g.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = QQ2008.TextMain)
                        Text("本地游戏 ·  ${g.seats} 人桌 · 即点即玩", fontSize = 11.sp, color = QQ2008.TextSub)
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
                        Text(g.emoji, fontSize = 26.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            g.tagline,
                            color = Color(0xFFFFE95C),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    g.intro,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = QQ2008.TextMain
                )
                Spacer(Modifier.height(12.dp))
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

// ==================== 房间（截图1：橙色木桌大厅 + 企鹅头像座位） ====================

/** 截图1 大厅桌位上的经典昵称池 */
private val LOBBY_NICKS = listOf(
    "金木", "好心情", "天空", "大鱼儿", "李学霸", "子龙", "山人", "老牛",
    "华哥", "懒散", "玉谦", "郭军", "龙蔡", "风雨同路", "笑看人生", "沉默是金",
    "快乐男孩", "一生有你", "淡然", "追梦人", "逍遥子", "小虾米", "夜风", "独孤剑"
)

@Composable
private fun QQGameRoomScreen(room: QQGameRoom, onBack: () -> Unit, onSit: () -> Unit) {
    val def = GAME_DEFS.first { it.id == room.gameId }
    val acc = QQSession.account
    val scores = remember { QQGameScores.load() }
    val myScore = when (def.id) {
        "ddz" -> scores[acc?.nickname] ?: -327
        "chess" -> scores["象棋·${acc?.nickname}"] ?: 411
        "gomoku" -> scores["五子棋·${acc?.nickname}"] ?: -84
        else -> scores["麻将·${acc?.nickname}"] ?: -20
    }
    // 胜率 38%（截图1 顶栏样式，取个 2008 味的数）
    val winRate = remember { 35 + Random.nextInt(20) }

    // 20 张桌子（5 列 × 4 行，对应截图1 的桌子阵列）
    val tables = remember(room) {
        val list = List(20) { Random.nextInt(0, def.seats + 1) }
        if (list.all { it >= def.seats }) list.toMutableList().also { it[0] = def.seats - 1 } else list
    }
    // 每桌座位上的昵称（同一桌内不重复）
    val tableNicks = remember(room, tables) {
        tables.map { occ ->
            val used = mutableSetOf<String>()
            List(occ) {
                val pool = LOBBY_NICKS.filter { it !in used }
                val n = if (pool.isNotEmpty()) pool.random() else "玩家${(1000..9999).random()}"
                used.add(n); n
            }
        }
    }
    val watchers = remember(room) { 1 + Random.nextInt(14) }   // "N人正在看"

    Box(Modifier.fillMaxSize()) {
        // 参考APK大厅背景图
        QQGameAssetImage("qgame/bg/hall.jpg", Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
        ) {
        // ===== 顶部信息栏（截图1：企鹅头像 + 游戏名 + QQ号/积分/胜率）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF2D9AC7), Color(0xFFA6DBE9))))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 白色描边头像框
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .border(2.dp, Color(0xFF2E86D5), RoundedCornerShape(4.dp))
                    .padding(2.dp)
            ) {
                QqPenguinAvatar(size = 40.dp, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        def.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "⬅ 返回大厅",
                        color = Color(0xFF123A5E),
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { QQSounds.click(); onBack() }
                    )
                }
                Text(
                    "昵称：${acc?.nickname ?: "游客"}   QQ号：${acc?.number ?: "17713"}",
                    color = Color(0xFF123A5E),
                    fontSize = 11.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("积分：", color = Color(0xFF123A5E), fontSize = 11.sp)
                    Text("$myScore", color = Color(0xFFC3272B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(10.dp))
                    Text("胜率：", color = Color(0xFF123A5E), fontSize = 11.sp)
                    Text("$winRate%", color = Color(0xFF2E7D32), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            MusicToggle(playingScene = "bgm_room")
            Spacer(Modifier.width(6.dp))
            // 右上：负分清零 / 更多功能（截图1 顶栏右侧）
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { QQSounds.click() }) {
                Text("💰", fontSize = 14.sp)
                Text("负分清零", color = Color(0xFF123A5E), fontSize = 9.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { QQSounds.click() }) {
                Text("⚙️", fontSize = 14.sp)
                Text("更多功能", color = Color(0xFF123A5E), fontSize = 9.sp)
            }
        }

        // ===== 导航路径条（截图1：斗地主＞普通防作弊场＞防作弊房间3）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFE8F4F8))
                .border(0.5.dp, Color(0xFF6FA6D8))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "当前位置：",
                color = Color(0xFF5B7B99),
                fontSize = 11.sp
            )
            Text(
                "${def.title}＞${room.area}＞${room.name}",
                color = Color(0xFF123A5E),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${def.emoji} ${def.seats}人桌 · ${tableNicks.sumOf { it.size }}人游戏中",
                color = Color(0xFF5B7B99),
                fontSize = 10.sp
            )
        }

        // ===== 功能按钮行（截图1：绿色"快速加入游戏" + 白底"邀请好友"）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF2F8FC))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QqGreenButton(
                "快速加入游戏",
                {
                    QQSounds.ding()
                    onSit()
                }
            )
            Spacer(Modifier.width(8.dp))
            QqWhiteButton("邀请好友", { QQSounds.click() })
            Spacer(Modifier.weight(1f))
            Text(
                "双击桌子上的空位即可加入游戏",
                color = Color(0xFF8FA8BE),
                fontSize = 10.sp
            )
        }

        // ===== 桌子区（v4 列数自适应：窄窗口自动减列，纵向滚动）=====
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
        ) {
            val tableCols = (maxWidth / 170.dp).toInt().coerceIn(2, 5)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tables.chunked(tableCols).forEachIndexed { rowIdx, row ->
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    row.forEachIndexed { colIdx, occupied ->
                        GameTableItem(
                            def = def,
                            no = rowIdx * tableCols + colIdx + 1,
                            occupied = occupied,
                            nicks = tableNicks[rowIdx * tableCols + colIdx],
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
        }   // v4 桌子区 BoxWithConstraints 结束（底部状态栏保持在外层）

        // ===== 底部状态栏（截图1 左下："2人正在看"）=====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xFF164B7E), Color(0xFF0A3358))))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "👁 $watchers 人正在看",
                color = Color(0xFFBFE4FB),
                fontSize = 11.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${def.title} · ${room.area}${room.name}",
                color = Color(0xFF8FB8D8),
                fontSize = 10.sp
            )
        }
        }   // 外层 Column 结束
    }
}

/** 桌上的一个座位（截图1）：有人显示经典企鹅头像 + 昵称，空位显示半透明虚位 ＋ */
@Composable
private fun SeatSlot(
    occupiedHere: Boolean,
    nick: String,
    modifier: Modifier = Modifier,
    seatSize: Dp = 26.dp
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (occupiedHere) {
            Box(
                modifier = Modifier
                    .size(seatSize)
                    .background(Color.White, RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0xFF6FA6D8), RoundedCornerShape(3.dp))
                    .padding(1.dp)
            ) {
                QqPenguinAvatar(size = seatSize - 2.dp, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(1.dp))
            Text(
                nick,
                color = Color.White,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center
            )
        } else {
            Box(
                modifier = Modifier
                    .size(seatSize)
                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(3.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("＋", color = Color(0xFFFFE95C), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(1.dp))
            Text("空位", color = Color(0xFF8FB8D8), fontSize = 9.sp)
        }
    }
}

/** 大厅一张桌（截图1）：橙色透视木桌 + 周围座位（3人左上/右上/底中，2人左右，4人四角） */
@Composable
private fun GameTableItem(
    def: GameDef,
    no: Int,
    occupied: Int,
    nicks: List<String>,
    onSit: () -> Unit
) {
    val full = occupied >= def.seats
    Box(modifier = Modifier.size(width = 148.dp, height = 104.dp)) {
        // ---- 桌号徽章 ----
        Text(
            "$no",
            color = Color(0xFFFFE95C),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color(0x88123A5E), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        // ---- 橙色木桌（透视梯形，点击入座）----
        QqOrangeTable(
            width = 92.dp,
            height = 54.dp,
            modifier = Modifier.align(Alignment.Center).padding(top = 12.dp),
            onClick = { if (!full) onSit() }
        )
        // ---- 座位（截图1：左上 / 右上 / 底中）----
        val nickAt = { i: Int -> nicks.getOrNull(i) ?: "" }
        when (def.seats) {
            3 -> {
                SeatSlot(occupied > 0, nickAt(0), Modifier.align(Alignment.TopStart).padding(start = 4.dp))
                SeatSlot(occupied > 1, nickAt(1), Modifier.align(Alignment.TopEnd).padding(end = 4.dp))
                SeatSlot(occupied > 2, nickAt(2), Modifier.align(Alignment.BottomCenter))
            }
            2 -> {
                SeatSlot(occupied > 0, nickAt(0), Modifier.align(Alignment.CenterStart))
                SeatSlot(occupied > 1, nickAt(1), Modifier.align(Alignment.CenterEnd))
            }
            else -> {
                SeatSlot(occupied > 0, nickAt(0), Modifier.align(Alignment.TopStart).padding(start = 4.dp))
                SeatSlot(occupied > 1, nickAt(1), Modifier.align(Alignment.TopEnd).padding(end = 4.dp))
                SeatSlot(occupied > 2, nickAt(2), Modifier.align(Alignment.BottomStart).padding(start = 4.dp))
                SeatSlot(occupied > 3, nickAt(3), Modifier.align(Alignment.BottomEnd).padding(end = 4.dp))
            }
        }
        // 满员标记（桌角灰字）
        if (full) {
            Text(
                "游戏中",
                color = Color(0xFF9DB6CC),
                fontSize = 8.sp,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}
