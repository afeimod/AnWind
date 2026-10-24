package com.anwind.apps.qq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 斗地主牌桌（截图2：QQ斗地主 角色版 复刻）：
 *  - 深青径向渐变牌桌；座位：0=我（下方）、1=右侧、2=左侧；出牌顺序 0→1→2
 *  - 左/右侧玩家：头像 + 昵称 + 等级 + 竖叠牌背 + "剩余 N 张牌"（截图2 样式）
 *  - 中央出牌区 + 黄壳红字倒计时闹钟；顶部底牌 + 底分/倍数
 *  - 右侧经典面板：浅蓝信息条 / 记分板 / 道具栏 / 蓝钻·任务按钮 / 快捷短语胶囊
 *  - 结束时结算浮层
 *
 * AI 行动由 LaunchedEffect(phase, turn, bidTurn, bidCount) 驱动，带 0.8~1.5s 思考延迟。
 */
@Composable
fun QQGameTableScreen(onExit: () -> Unit) {
    val acc = QQSession.account
    val selfName = acc?.nickname ?: "我"
    val selfScore = remember { QQGameScores.load()[selfName] ?: 0 }
    val game = remember { DoudizhuGame(selfName, acc?.avatar ?: "emoji:🐧", selfScore) }
    val selected = remember { mutableStateListOf<DdCard>() }

    // 游戏音效/语音系统就绪（参考APK素材）
    val sndCtx = androidx.compose.ui.platform.LocalContext.current
    remember { QQGameSound.ensureInit(sndCtx); true }

    // ---- 倒计时（我的回合从 30 起跳；非我回合显示 AI 思考剩余） ----
    var countdown by remember { mutableStateOf(30) }
    LaunchedEffect(game.phase, game.turn, game.bidTurn) {
        countdown = 30
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    // ---- AI 行动驱动 ----
    LaunchedEffect(game.phase, game.turn, game.bidTurn, game.bidCount) {
        when {
            game.phase == DdPhase.BIDDING && !game.players[game.bidTurn].isHuman -> {
                delay(Random.nextLong(700, 1300))
                game.makeBid(game.bidTurn, DdAi.bidPoints(game.players[game.bidTurn].hand))
            }
            game.phase == DdPhase.PLAYING && !game.players[game.turn].isHuman -> {
                delay(Random.nextLong(800, 1500))
                val idx = game.turn
                val counts = IntArray(3) { game.players[it].hand.size }
                val move = DdAi.decide(
                    idx = idx,
                    hand = game.players[idx].hand.toList(),
                    landlord = game.landlord,
                    last = game.lastPlay,
                    lastWinner = game.lastWinner,
                    handCounts = counts
                )
                if (move == null) game.passTurn(idx) else game.playCards(idx, move)
            }
        }
    }

    // ---- 重发时清空手牌选择 ----
    LaunchedEffect(game.phase, game.bidCount) {
        if (game.phase == DdPhase.BIDDING && game.bidCount == 0) selected.clear()
    }

    // ---- 结算：音效 + 积分落盘 ----
    LaunchedEffect(game.result) {
        val r = game.result ?: return@LaunchedEffect
        val delta = r.deltas[0]
        runCatching { QQGameScores.add(selfName, delta) }
        if (delta > 0) QQGameSound.win() else QQGameSound.lose()
    }

    // v4 竖屏自适应：窄窗口隐藏右侧经典面板，牌桌占满全宽
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 520.dp
        Row(Modifier.fillMaxSize()) {
            // ================= 桌面区（参考APK牌桌背景图，超宽时裁切） =================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                QQGameAssetImage("qgame/bg/ddz_table.jpg", Modifier.fillMaxSize())
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // ---- v4 手牌自适应放大：17 张牌 + 60% 重叠 ≈ 10.6 倍牌宽 ----
                    val badgeW = 56.dp
                    val handZoneW = maxWidth - badgeW - 20.dp
                    val cardW = (handZoneW / 10.6f).coerceIn(40.dp, 62.dp)
                    val step = cardW * 0.6f
                    val handH = cardW * 1.42f + 14.dp
                    // 出牌区卡宽（窄屏防溢出）
                    val playCardW = if (maxWidth < 420.dp) 19.dp else 24.dp
                    val sideBackW = if (maxWidth < 420.dp) 16.dp else 20.dp

                    Column(Modifier.fillMaxSize().padding(8.dp)) {
                        // ---- 顶行：底牌 + 局况（居中；左右对手已移至两侧中部）----
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // 底牌（截图2 顶部）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("底牌", color = Color(0xFFFFE95C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        game.bottomCards.forEach { c ->
                                            if (game.bottomRevealed) DdCardFace(c, 20.dp, selected = false, onClick = null)
                                            else DdCardBack(20.dp)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "底分 ${game.baseScore}  倍数 ${game.multiplier}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(4.dp))

                        // ---- 中区：左家 | 三家出牌+倒计时 | 右家（经典 QQ 座位）----
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // 左家（贴左缘垂直居中）
                            Box(Modifier.align(Alignment.CenterStart)) {
                                DdSidePlayer(game, 2, backW = sideBackW)
                            }
                            // 右家（贴右缘垂直居中）
                            Box(Modifier.align(Alignment.CenterEnd)) {
                                DdSidePlayer(game, 1, backW = sideBackW)
                            }
                            // 三家出牌（居中竖排：右家上 / 左家中 / 我下）
                            Column(
                                Modifier.align(Alignment.Center).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                DdPlaySlot(game, 1, cardW = playCardW)      // 右家出的牌（上）
                                Spacer(Modifier.height(10.dp))
                                DdPlaySlot(game, 2, cardW = playCardW)      // 左家出的牌（中）
                                Spacer(Modifier.height(10.dp))
                                DdPlaySlot(game, 0, cardW = playCardW)      // 我出的牌（下）
                            }
                            // 倒计时闹钟（我的回合才亮，截图2 左下黄色闹钟29）
                            val myAction = (game.phase == DdPhase.PLAYING && game.turn == 0) ||
                                    (game.phase == DdPhase.BIDDING && game.bidTurn == 0)
                            if (myAction) {
                                QqAlarmTimer(
                                    seconds = countdown,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 10.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))

                        // ---- 我的出牌/身份行 + 手牌（v4 自适应大牌）----
                        Row(verticalAlignment = Alignment.Bottom) {
                            DdSelfBadge(game)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(handH)
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                val hand = game.players[0].hand
                                Box(
                                    Modifier
                                        .width(step * (hand.size.coerceAtLeast(1) - 1) + cardW)
                                        .height(handH)
                                ) {
                                    hand.forEachIndexed { i, c ->
                                        val isSel = selected.contains(c)
                                        Box(
                                            Modifier
                                                .align(Alignment.BottomStart)
                                                .offset(x = step * i, y = if (isSel) (-12).dp else 0.dp)
                                        ) {
                                            DdCardFace(c, cardW, selected = isSel) {
                                                if (isSel) selected.remove(c) else selected.add(c)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))

                        // ---- 操作按钮（截图2：绿色出牌按钮）----
                        DdActionButtons(game, selected)
                    }
                }

                // ---- 结算浮层 ----
                game.result?.let { r ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .background(Color(0xFF12365C).copy(alpha = 0.95f), RoundedCornerShape(10.dp))
                                .border(2.dp, Color(0xFFFFE95C), RoundedCornerShape(10.dp))
                                .padding(horizontal = 26.dp, vertical = 18.dp)
                        ) {
                            val myWin = r.deltas[0] > 0
                            Text(
                                if (myWin) "🎉 胜 利 🎉" else "😅 失 败",
                                color = if (myWin) Color(0xFFFFE95C) else Color(0xFFBFE4FB),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${if (r.landlordWon) "地主" else "农民"}阵营获胜 · 底分${r.base} × 倍数${r.multiplier}" +
                                        if (r.spring) " · 春天！" else "",
                                color = Color.White, fontSize = 12.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            game.players.forEachIndexed { i, p ->
                                val d = r.deltas[i]
                                Text(
                                    "${p.name}  ${if (d >= 0) "+" else ""}$d 分",
                                    color = if (d >= 0) Color(0xFF7CE87C) else Color(0xFFFF9A9A),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Row {
                                QqGreenButton(
                                    "再来一局",
                                    {
                                        selected.clear()
                                        game.dealAndStartBid()
                                    },
                                    height = 32.dp
                                )
                                Spacer(Modifier.width(10.dp))
                                QqWhiteButton("退出牌桌", onExit, height = 32.dp)
                            }
                        }
                    }
                }
            }

            // ================= 右侧面板（截图2 经典样式；竖屏隐藏）=================
            if (!compact) {
                DdSidePanel(game, onExit)
            }
        }
    }
}


// ==================== 牌面（标准扑克位图素材 assets/qgame/cards/） ====================

/** 牌面素材路径（rank 3..10/11=J/12=Q/13=K/14=A/15=2；suit 0=♠ 1=♥ 2=♣ 3=♦） */
private fun ddCardAsset(card: DdCard): String {
    if (card.isJoker) {
        return if (card.rank == DdCard.JOKER_B) "qgame/cards/joker_b.png" else "qgame/cards/joker_s.png"
    }
    val lbl = when (card.rank) {
        in 3..10 -> card.rank.toString()
        11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; else -> "2"
    }
    val s = when (card.suit) { 0 -> "s"; 1 -> "h"; 2 -> "c"; else -> "d" }
    return "qgame/cards/c_${lbl}${s}.png"
}

@Composable
private fun DdCardFace(card: DdCard, w: Dp, selected: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(w * 0.09f)
    Box(
        modifier = Modifier
            .width(w)
            .height(w * 1.42f)
            .clip(shape)
            .border(1.dp, if (selected) Color(0xFFFFB400) else Color(0xFFB9C4CE), shape)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        QQGameAssetImage(ddCardAsset(card), Modifier.fillMaxSize())
    }
}

@Composable
private fun DdCardBack(w: Dp) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .width(w)
            .height(w * 1.42f)
            .clip(shape)
            .border(1.dp, Color(0xFFBFE4FB), shape)
    ) {
        QQGameAssetImage("qgame/cards/back.png", Modifier.fillMaxSize())
    }
}

// ==================== 座位面板（截图2 样式） ====================

/** 侧位玩家（左家 2 / 右家 1）：头像 + 昵称 + 等级 + 竖叠牌背 + 剩余张数 */
/** 侧位玩家（左家 2 / 右家 1）—— v4 经典布局：贴桌侧垂直居中，
 * 头像+昵称在上，其下是全量竖向牌背堆（剩余几张显示几张），最下"剩余 N 张牌" */
@Composable
private fun DdSidePlayer(game: DoudizhuGame, idx: Int, backW: Dp = 20.dp) {
    val p = game.players[idx]
    val isTurn = game.phase == DdPhase.PLAYING && game.turn == idx
    val isBidding = game.phase == DdPhase.BIDDING && game.bidTurn == idx
    val backH = backW * 1.42f
    val stackStep = backH * 0.38f          // 竖向堆叠步进（38% 露出）
    val n = p.hand.size.coerceAtMost(20)  // 剩余牌全量显示（经典 QQ 样式）
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(78.dp)
            .background(
                if (isTurn || isBidding) Color(0x33FFE95C) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .padding(vertical = 4.dp)
    ) {
        // 头像（白边框，截图2 角色头像位）
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(2.dp, Color(0xFFBFE4FB), RoundedCornerShape(4.dp))
                .padding(2.dp)
        ) {
            QqPenguinAvatar(
                size = 40.dp,
                modifier = Modifier.fillMaxSize(),
                scarf = if (game.landlord == idx) Color(0xFFC3272B) else Color(0xFF1E6CB4)
            )
            if (game.landlord == idx) {
                Text("👑", fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart).offset(x = (-4).dp, y = (-4).dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(p.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("等级: ${ddTitle(p.level)}", color = Color(0xFFFFE95C), fontSize = 9.sp)
        Spacer(Modifier.height(4.dp))
        // 竖向牌背堆（经典 QQ：剩余牌全部竖着叠出，从上往下）
        Box(
            Modifier
                .width(backW)
                .height(backH + stackStep * (n - 1))
        ) {
            repeat(n) { i ->
                Box(Modifier.align(Alignment.TopStart).offset(y = stackStep * i)) {
                    DdCardBack(backW)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        // 剩余张数（截图2：数字红色加粗）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("剩余 ", color = Color.White, fontSize = 10.sp)
            Text("${p.hand.size}", color = Color(0xFFFF6B6B), fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(" 张", color = Color.White, fontSize = 10.sp)
        }
        if (game.passed[idx] == true && game.phase == DdPhase.PLAYING) {
            Spacer(Modifier.height(2.dp))
            Text("不出", color = Color(0xFFFFE95C), fontSize = 12.sp, fontStyle = FontStyle.Italic)
        }
    }
}


/** 我的信息（手牌左侧，截图2 底部左角） */
@Composable
private fun DdSelfBadge(game: DoudizhuGame) {
    val p = game.players[0]
    val isTurn = game.phase == DdPhase.PLAYING && game.turn == 0
    val isBidding = game.phase == DdPhase.BIDDING && game.bidTurn == 0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(2.dp, if (isTurn || isBidding) Color(0xFFFFE95C) else Color(0xFFBFE4FB), RoundedCornerShape(4.dp))
                .padding(2.dp)
        ) {
            QqPenguinAvatar(
                size = 42.dp,
                modifier = Modifier.fillMaxSize(),
                scarf = if (game.landlord == 0) Color(0xFFC3272B) else Color(0xFF2E7D32)
            )
            if (game.landlord == 0) {
                Text("👑", fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart).offset(x = (-4).dp, y = (-4).dp))
            }
        }
        Text(p.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("等级: ${ddTitle(p.level)}", color = Color(0xFFFFE95C), fontSize = 9.sp)
    }
}

/** 桌面上某家最近出的牌（或"不出"） */
/** 桌面上某家最近出的牌（或"不出"）；v4：卡宽自适应 */
@Composable
private fun DdPlaySlot(game: DoudizhuGame, idx: Int, cardW: Dp = 24.dp) {
    val cards = game.shown[idx]
    val who = game.players[idx].name
    when {
        game.passed[idx] == true -> Text(
            "「$who 不出」",
            color = Color(0xFFFFE95C),
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic
        )
        cards != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(who, color = Color(0xFFBFE4FB), fontSize = 9.sp)
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                cards.forEach { DdCardFace(it, cardW, selected = false, onClick = null) }
            }
        }
        game.landlord == idx && game.phase == DdPhase.PLAYING -> Text(
            "「$who 思考中…」",
            color = Color(0xFF9DB6CC),
            fontSize = 11.sp,
            fontStyle = FontStyle.Italic
        )
    }
}

// ==================== 操作按钮（截图2：绿色主按钮） ====================

@Composable
private fun DdActionButtons(game: DoudizhuGame, selected: MutableList<DdCard>) {
    val myBidTurn = game.phase == DdPhase.BIDDING && game.bidTurn == 0
    val myPlayTurn = game.phase == DdPhase.PLAYING && game.turn == 0

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            myBidTurn -> {
                Text("轮到你叫分：", color = Color(0xFFFFE95C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                QqWhiteButton("不叫", { game.makeBid(0, 0) })
                Spacer(Modifier.width(6.dp))
                listOf(1, 2, 3).forEach { s ->
                    QqGreenButton("$s 分", { game.makeBid(0, s) }, height = 28.dp)
                    Spacer(Modifier.width(6.dp))
                }
            }
            game.phase == DdPhase.BIDDING -> Text(
                "等待${game.players[game.bidTurn].name}叫分…",
                color = Color(0xFFBFE4FB), fontSize = 12.sp, fontStyle = FontStyle.Italic
            )
            myPlayTurn -> {
                val play = DdRules.classify(selected.toList())
                val mustBeat = game.mustBeat(0)
                val canPlay = play != null && (!mustBeat || DdRules.beats(play, game.lastPlay!!))
                QqGreenButton(
                    "出 牌",
                    {
                        if (canPlay) {
                            game.playCards(0, selected.toList())
                            selected.clear()
                        }
                    },
                    height = 32.dp,
                    fontSize = 15.sp
                )
                Spacer(Modifier.width(8.dp))
                QqWhiteButton(
                    "不出",
                    { game.passTurn(0) },
                    enabled = mustBeat,
                    height = 32.dp
                )
                Spacer(Modifier.width(8.dp))
                QqWhiteButton(
                    "提示",
                    {
                        val h = game.hint()
                        selected.clear()
                        selected.addAll(h)
                    },
                    height = 32.dp
                )
            }
            game.phase == DdPhase.PLAYING -> Text(
                "等待${game.players[game.turn].name}出牌…",
                color = Color(0xFFBFE4FB), fontSize = 12.sp, fontStyle = FontStyle.Italic
            )
        }
    }
}

// ==================== 右侧面板（截图2 经典样式） ====================

@Composable
private fun DdSidePanel(game: DoudizhuGame, onExit: () -> Unit) {
    val logScroll = rememberScrollState()
    val self = game.players[0]
    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxSize()
            .background(QqGame.PanelLightBg)
            .border(1.dp, Color(0xFF6FA6D8))
    ) {
        // ---- 顶部浅蓝信息条（截图2：企鹅头像 + 积分/游戏币）----
        QqPanelHeader(
            nickname = self.name,
            scoreText = "${self.score}",
            coinText = "游戏币: 0"
        )
        // 退出
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFDCEFFB))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("级别: ${ddTitle(self.level)}", color = Color(0xFF2E6288), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "退出",
                color = Color(0xFF0B62C4),
                fontSize = 11.sp,
                modifier = Modifier.clickable { QQSounds.click(); onExit() }
            )
        }

        // ---- 记分板（截图2：昵称 | 游戏币 | 积分 表格）----
        QqPanelSection("记分板")
        // 表头
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFE4EFF7))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("昵称", fontSize = 10.sp, color = Color(0xFF5B7B99), modifier = Modifier.weight(1.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("游戏币", fontSize = 10.sp, color = Color(0xFF5B7B99), modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
            Text("积分", fontSize = 10.sp, color = Color(0xFF5B7B99), modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
        }
        game.players.sortedByDescending { it.score }.forEach { p ->
            val isMe = p.isHuman
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isMe) Color(0xFFBFE4FB) else Color.White)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                QqPenguinAvatar(size = 16.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    p.name + if (isMe) "（你）" else "",
                    fontSize = 10.sp,
                    color = QQ2008.TextMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.2f)
                )
                Text("0", fontSize = 10.sp, color = QQ2008.TextSub, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
                Text(
                    "${p.score}",
                    fontSize = 10.sp,
                    color = Color(0xFFC3272B),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.8f),
                    textAlign = TextAlign.End
                )
            }
        }

        // ---- 道具栏（截图2：公共道具 / 专属道具）----
        QqPanelSection("道具")
        QqPropsBar(title = "公共道具", propCount = 2)
        QqPropsBar(title = "专属道具", propCount = 0)

        // ---- 蓝钻 / 送礼 / 任务（截图2 按钮）----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QqBlueDiamondButton("蓝钻贵族", diamond = true, onClick = { QQSounds.click() }, modifier = Modifier.weight(1f))
            QqBlueDiamondButton("游戏商城", diamond = false, onClick = { QQSounds.click() }, modifier = Modifier.weight(1f))
        }
        QqGoldButton(
            "任 务",
            { QQSounds.click() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            height = 34.dp,
            fontSize = 15.sp
        )

        // ---- 快捷短语（截图2：宝宝/记牌器/表情/托管/摊牌/上一轮 黄色胶囊）----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf("宝宝", "记牌器", "表情", "托管", "摊牌").forEach { t ->
                QqPillButton(t, { QQSounds.msgOut(); game.say("[$t]") }, modifier = Modifier.weight(1f), height = 22.dp, fontSize = 9.sp)
            }
        }

        // ---- 游戏动态（滚动日志）----
        QqPanelSection("游戏动态")
        LaunchedEffect(game.log.size) {
            runCatching { logScroll.animateScrollTo(logScroll.maxValue) }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(logScroll)
                .padding(horizontal = 8.dp)
        ) {
            game.log.forEach { line ->
                Text(
                    line,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = QQ2008.TextMain,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
