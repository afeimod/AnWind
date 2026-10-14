package com.anwind.apps.qq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 斗地主牌桌（仿 QQ游戏 斗地主角色版截图）：
 *  - 蓝色桌面；座位：0=我（下方）、1=右侧 AI、2=左侧 AI；出牌顺序 0→1→2
 *  - 左上底牌；各玩家：头像/称号/剩余张数/地主标识/"不出"气泡
 *  - 底部：我的手牌（点选出牌，选中的牌上浮）+ 叫分/出牌/不出/提示按钮
 *  - 右侧面板：积分排名 + 游戏动态 + 快捷表情；结束时结算浮层
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
        if (delta > 0) QQSounds.win() else QQSounds.lose()
    }

    Row(Modifier.fillMaxSize()) {
        // ================= 桌面区 =================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF2C5A8F), Color(0xFF1B3A61)))
                )
        ) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                // ---- 顶行：底牌 + 局况 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("底牌", color = Color(0xFFFFE95C), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        game.bottomCards.forEach { c ->
                            if (game.bottomRevealed) DdCardFace(c, 20.dp, selected = false, onClick = null)
                            else DdCardBack(20.dp)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "底分 ${game.baseScore} · 倍数 ${game.multiplier}",
                        color = Color(0xFFFFE95C),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))

                // ---- 中区：左家 | 桌面出牌 | 右家 ----
                Row(Modifier.weight(1f)) {
                    DdSidePlayer(game, 2, vertical = true)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        Column(
                            Modifier.align(Alignment.Center).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 右家出的牌
                            DdPlaySlot(game, 1)
                            Spacer(Modifier.height(10.dp))
                            DdPlaySlot(game, 2)
                            Spacer(Modifier.height(10.dp))
                            DdPlaySlot(game, 0)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    DdSidePlayer(game, 1, vertical = true)
                }
                Spacer(Modifier.height(6.dp))

                // ---- 我的出牌/身份行 + 手牌 ----
                Row(verticalAlignment = Alignment.Bottom) {
                    DdSelfBadge(game)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(66.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        val hand = game.players[0].hand
                        val cardW = 42.dp
                        val step = 25.dp
                        Box(
                            Modifier
                                .width(step * (hand.size.coerceAtLeast(1) - 1) + cardW)
                                .height(66.dp)
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

                // ---- 操作按钮 ----
                DdActionButtons(game, selected)
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
                            QqBeigeButton("再来一局", {
                                selected.clear()
                                game.dealAndStartBid()
                            }, height = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            QqBeigeButton("退出牌桌", onExit, height = 32.dp)
                        }
                    }
                }
            }
        }

        // ================= 右侧面板 =================
        DdSidePanel(game, onExit)
    }
}

// ==================== 牌面 ====================

@Composable
private fun DdCardFace(card: DdCard, w: Dp, selected: Boolean, onClick: (() -> Unit)?) {
    val h = w * 1.42f
    val color = if (card.isRed) Color(0xFFD32F2F) else Color(0xFF1A1A1A)
    Box(
        modifier = Modifier
            .width(w)
            .height(h)
            .background(Color.White, RoundedCornerShape(3.dp))
            .border(1.dp, if (selected) Color(0xFFFFB400) else Color(0xFF9DB6CC), RoundedCornerShape(3.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(2.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column(Modifier.padding(1.dp)) {
            Text(
                card.label,
                color = color,
                fontSize = (w.value * 0.34f).sp,
                fontWeight = FontWeight.Black,
                lineHeight = (w.value * 0.36f).sp
            )
            if (!card.isJoker) {
                Text(
                    card.suitChar,
                    color = color,
                    fontSize = (w.value * 0.28f).sp,
                    lineHeight = (w.value * 0.30f).sp
                )
            }
        }
        if (card.isJoker) {
            Text(
                if (card.rank == DdCard.JOKER_B) "☀" else "🌙",
                fontSize = (w.value * 0.42f).sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun DdCardBack(w: Dp) {
    Box(
        modifier = Modifier
            .width(w)
            .height(w * 1.42f)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF3E7CB8), Color(0xFF2A5A8C))),
                RoundedCornerShape(3.dp)
            )
            .border(1.dp, Color(0xFFBFE4FB), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("🐧", fontSize = (w.value * 0.4f).sp)
    }
}

// ==================== 座位面板 ====================

/** 侧位玩家（左家 2 / 右家 1） */
@Composable
private fun DdSidePlayer(game: DoudizhuGame, idx: Int, vertical: Boolean) {
    val p = game.players[idx]
    val isTurn = game.phase == DdPhase.PLAYING && game.turn == idx
    val isBidding = game.phase == DdPhase.BIDDING && game.bidTurn == idx
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(86.dp)
            .background(
                if (isTurn || isBidding) Color(0x33FFE95C) else Color.Transparent,
                RoundedCornerShape(6.dp)
            )
            .padding(vertical = 4.dp)
    ) {
        Box {
            QqAvatar(avatar = p.avatar, size = 44.dp)
            if (game.landlord == idx) {
                Text("👑", fontSize = 15.sp, modifier = Modifier.align(Alignment.TopStart).padding(0.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(p.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("${ddTitle(p.level)}·Lv${p.level}", color = Color(0xFFFFE95C), fontSize = 9.sp)
        Text("剩 ${p.hand.size} 张", color = Color(0xFFBFE4FB), fontSize = 10.sp)
        if (game.passed[idx] == true && game.phase == DdPhase.PLAYING) {
            Spacer(Modifier.height(2.dp))
            Text("不出", color = Color.White, fontSize = 12.sp, fontStyle = FontStyle.Italic)
        }
    }
}

/** 我的信息（手牌左侧小徽标） */
@Composable
private fun DdSelfBadge(game: DoudizhuGame) {
    val p = game.players[0]
    val isTurn = game.phase == DdPhase.PLAYING && game.turn == 0
    val isBidding = game.phase == DdPhase.BIDDING && game.bidTurn == 0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            QqAvatar(avatar = p.avatar, size = 44.dp)
            if (game.landlord == 0) {
                Text("👑", fontSize = 15.sp, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        Text(p.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isTurn || isBidding) {
                Box(Modifier.size(6.dp).background(Color(0xFF7CE87C), RoundedCornerShape(50)))
                Spacer(Modifier.width(3.dp))
            }
            Text("剩${p.hand.size}张", color = Color(0xFFBFE4FB), fontSize = 9.sp)
        }
    }
}

/** 桌面上某家最近出的牌（或"不出"） */
@Composable
private fun DdPlaySlot(game: DoudizhuGame, idx: Int) {
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
                cards.forEach { DdCardFace(it, 24.dp, selected = false, onClick = null) }
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

// ==================== 操作按钮 ====================

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
                QqBeigeButton("不叫", { game.makeBid(0, 0) }, height = 30.dp)
                Spacer(Modifier.width(6.dp))
                listOf(1, 2, 3).forEach { s ->
                    QqBeigeButton("$s 分", { game.makeBid(0, s) }, height = 30.dp)
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
                QqBeigeButton(
                    "出 牌",
                    {
                        if (canPlay) {
                            game.playCards(0, selected.toList())
                            selected.clear()
                        }
                    },
                    enabled = canPlay,
                    height = 32.dp
                )
                Spacer(Modifier.width(8.dp))
                QqBeigeButton(
                    "不出",
                    { game.passTurn(0) },
                    enabled = mustBeat,
                    height = 32.dp
                )
                Spacer(Modifier.width(8.dp))
                QqBeigeButton(
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

// ==================== 右侧面板 ====================

@Composable
private fun DdSidePanel(game: DoudizhuGame, onExit: () -> Unit) {
    val logScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .width(196.dp)
            .fillMaxSize()
            .background(QQ2008.panelBrush())
            .border(1.dp, QQ2008.PanelBorder)
    ) {
        // 退出
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🃏 斗地主（角色版）", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "退出",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.clickable { QQSounds.click(); onExit() }
            )
        }

        // 积分排名
        QqSectionBar(title = "积分排名", leading = "🏆")
        game.players.sortedByDescending { it.score }.forEachIndexed { i, p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("${i + 1}.", fontSize = 11.sp, color = QQ2008.TextSub, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text(p.avatar.removePrefix("emoji:"), fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    p.name + if (p.isHuman) "（你）" else "",
                    fontSize = 11.sp,
                    color = QQ2008.TextMain,
                    maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Text("${p.score}", fontSize = 11.sp, color = Color(0xFFC3272B), fontWeight = FontWeight.Bold)
            }
        }

        // 游戏动态
        QqSectionBar(title = "游戏动态", leading = "📣")
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

        // 快捷表情
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFE8F4FD))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("👍", "😂", "🙏", "😎", "🔥", "💣").forEach { e ->
                Text(
                    e,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable {
                        game.say("[$e]")
                        QQSounds.msgOut()
                    }
                )
            }
        }
    }
}
