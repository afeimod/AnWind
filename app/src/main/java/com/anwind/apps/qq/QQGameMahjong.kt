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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 欢乐麻将（QQ游戏大厅 · 本地游戏，简化川麻风）：
 *  - 只有 万/筒/条 三门花色共 108 张；4 人局（玩家 + 3 AI）；
 *  - 可碰、可杠（明杠/暗杠），不能吃；胡牌 = 4 搭子 + 对（含碰杠）；
 *  - 自摸 +6（每家 -2）、点炮 +4（点炮者 -4）；明杠 +1（点杠者付）、暗杠每家 +1；
 *  - 牌墙摸完流局；AI：留搭子拆孤张、见碰就碰、能胡就胡；
 *  - 积分存档 key = "麻将·昵称"。
 */

object MjTiles {
    val SUITS = listOf("万", "筒", "条")
    val RANKS = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
    fun label(code: Int): String = RANKS[code % 9] + SUITS[code / 9]
    fun fullWall(): MutableList<Int> {
        val w = mutableListOf<Int>()
        for (c in 0..26) repeat(4) { w.add(c) }
        w.shuffle()
        return w
    }
}

/** 副露：peng 碰 / gang 明杠 / angang 暗杠 */
data class MjMeld(val type: String, val tile: Int)

class MjPlayer(
    val name: String,
    val avatar: String,
    val isHuman: Boolean,
    val level: Int,
    var score: Int
)

// ==================== 胡牌判定（纯函数） ====================

object MjRules {

    /** concealed + 已有 melds 后是否成胡（4 搭子 + 对） */
    fun isWinningHand(concealed: List<Int>, melds: List<MjMeld>): Boolean {
        val need = 14 - melds.size * 3
        if (concealed.size != need) return false
        val cnt = IntArray(27)
        concealed.forEach { cnt[it]++ }
        return decompose(cnt, 4 - melds.size, true)
    }

    /** 递归拆解：sets 个搭子 + 一对将 */
    private fun decompose(cnt: IntArray, sets: Int, pairLeft: Boolean): Boolean {
        var i = 0
        while (i < 27 && cnt[i] == 0) i++
        if (i == 27) return sets == 0 && !pairLeft
        if (pairLeft && cnt[i] >= 2) {
            cnt[i] -= 2
            if (decompose(cnt, sets, false)) { cnt[i] += 2; return true }
            cnt[i] += 2
        }
        if (sets > 0) {
            if (cnt[i] >= 3) {                       // 刻子
                cnt[i] -= 3
                if (decompose(cnt, sets - 1, pairLeft)) { cnt[i] += 3; return true }
                cnt[i] += 3
            }
            if (i % 9 <= 6 && cnt[i + 1] > 0 && cnt[i + 2] > 0) {   // 顺子（同花色内）
                cnt[i]--; cnt[i + 1]--; cnt[i + 2]--
                if (decompose(cnt, sets - 1, pairLeft)) { cnt[i]++; cnt[i + 1]++; cnt[i + 2]++; return true }
                cnt[i]++; cnt[i + 1]++; cnt[i + 2]++
            }
        }
        return false
    }
}

// ==================== AI ====================

object MjAi {

    /** 出牌：返回要打掉的牌码 */
    fun discard(hand: List<Int>, melds: List<MjMeld>): Int {
        var best = hand.first()
        var bestScore = Int.MAX_VALUE
        for (t in hand.distinct()) {
            // 孤张先打、边张先打；留对子/搭子
            var s = keepScore(hand, t) + abs(t % 9 - 4)
            if (s < bestScore) { bestScore = s; best = t }
        }
        return best
    }

    /** 保留价值（越小越该打） */
    private fun keepScore(hand: List<Int>, t: Int): Int {
        val cnt = hand.groupingBy { it }.eachCount()
        var s = 0
        when (cnt[t] ?: 0) {
            3 -> s += 100          // 已是刻子，绝不拆
            2 -> s += 42           // 对子
            else -> s += 0
        }
        val r = t % 9
        fun has(code: Int) = code in 0..26 && (cnt[code] ?: 0) > 0
        var conn = 0
        if (has(t - 1) && r >= 1) conn++
        if (has(t + 1) && r <= 7) conn++
        if (has(t - 2) && r >= 2) conn++
        if (has(t + 2) && r <= 6) conn++
        s += conn * 18
        return s
    }

    /** 是否愿碰 */
    fun wantsPeng(hand: List<Int>): Boolean = true   // 有对子才会进队列，见碰就碰
}

// ==================== 对局状态机 ====================

class MahjongGame(selfName: String, selfAvatar: String, selfScore: Int) {

    val players: List<MjPlayer> = run {
        val usedNames = mutableSetOf(selfName)
        val usedAvatars = mutableSetOf(selfAvatar)
        listOf(MjPlayer(selfName, selfAvatar, true, 8, selfScore)) + (1..3).map {
            val n = GamePeople.randomName(usedNames)
            val a = GamePeople.randomAvatar(usedAvatars)
            usedNames.add(n); usedAvatars.add(a)
            MjPlayer(n, a, false, 3 + Random.nextInt(30), 60 + Random.nextInt(900))
        }
    }

    var wall = MjTiles.fullWall()
    val hands = List(4) { mutableListOf<Int>() }
    val melds = List(4) { mutableListOf<MjMeld>() }
    val rivers = List(4) { mutableListOf<Int>() }

    var dealer by mutableStateOf(0)
    var turn by mutableStateOf(0)
    var stage by mutableStateOf("draw")          // draw | act | claim | over
    var lastDrawn by mutableStateOf(-1)          // 刚摸的牌（-1 无）
    var pendingTile by mutableStateOf(-1)        // 待碰/杠/胡的那张
    var pendingFrom by mutableStateOf(-1)
    var claimQueue by mutableStateOf<List<Pair<Int, String>>>(emptyList())
    var winner by mutableStateOf(-1)             // -1 进行中 / 0..3 胡牌者 / 4 流局
    var winTile by mutableStateOf(-1)
    var winSelfDrawn by mutableStateOf(false)
    var winPayer by mutableStateOf(-1)
    var tick by mutableStateOf(0)                // 手牌等非观察态变更计数（触发重组）
    var selfDelta by mutableStateOf(0)           // 本局玩家累计分差（结算落盘）
    val log = mutableStateListOf<String>()

    init {
        startDeal()
    }

    private fun addLog(text: String) {
        log.add(text)
        while (log.size > 80) log.removeAt(0)
    }

    private fun bump() { tick++ }

    /** 发牌开局（再来一局也走这里） */
    fun startDeal() {
        wall = MjTiles.fullWall()
        for (i in 0..3) { hands[i].clear(); melds[i].clear(); rivers[i].clear() }
        winner = -1; winTile = -1; winSelfDrawn = false; winPayer = -1
        pendingTile = -1; pendingFrom = -1; claimQueue = emptyList()
        lastDrawn = -1; selfDelta = 0
        dealer = Random.nextInt(4)
        for (i in 0..3) repeat(13) { hands[i].add(wall.removeAt(wall.size - 1)) }
        for (i in 0..3) hands[i].sort()
        turn = dealer
        stage = "draw"
        bump()
        addLog("—— 新一局开始，庄家：${players[dealer].name} ——")
    }

    // ---------- 摸牌 ----------

    fun drawTile() {
        if (stage != "draw" || winner != -1) return
        if (wall.isEmpty()) { settleDraw(); return }
        val t = wall.removeAt(wall.size - 1)
        hands[turn].add(t)
        hands[turn].sort()
        lastDrawn = t
        stage = "act"
        bump()
    }

    // ---------- 出牌（自摸后 / 碰后） ----------

    fun canSelfHu(i: Int): Boolean = lastDrawn >= 0 && MjRules.isWinningHand(hands[i], melds[i])

    fun canAnGang(i: Int): List<Int> =
        hands[i].groupingBy { it }.eachCount().filter { it.value == 4 }.keys.toList()

    fun playerDiscard(i: Int, tile: Int) {
        if (winner != -1 || turn != i || stage != "act") return
        if (!hands[i].contains(tile)) return
        hands[i].remove(tile)
        rivers[i].add(tile)
        lastDrawn = -1
        stage = "claim"
        pendingTile = tile
        pendingFrom = i
        bump()
        QQSounds.card()
        addLog("${players[i].name} 打 ${MjTiles.label(tile)}")

        // 优先胡
        val q = mutableListOf<Pair<Int, String>>()
        for (k in 1..3) {
            val p = (i + k) % 4
            if (MjRules.isWinningHand(hands[p] + tile, melds[p])) q.add(p to "hu")
        }
        if (q.isNotEmpty()) { claimQueue = q; return }
        // 再杠/碰（按座位顺序问）
        for (k in 1..3) {
            val p = (i + k) % 4
            val cnt = hands[p].count { it == tile }
            if (cnt == 3) q.add(p to "gang")
            else if (cnt == 2) q.add(p to "peng")
        }
        if (q.isNotEmpty()) { claimQueue = q } else nextTurn()
    }

    // ---------- 碰/杠/胡 应答 ----------

    /** UI/AI 对队列头的应答；accept=false 表示过 */
    fun resolveClaim(accept: Boolean) {
        if (stage != "claim" || claimQueue.isEmpty()) return
        val (p, type) = claimQueue.first()
        val rest = claimQueue.drop(1)
        if (!accept) {
            if (rest.isNotEmpty()) { claimQueue = rest; bump(); return }
            nextTurn()
            return
        }
        when (type) {
            "hu" -> settleHu(p, pendingFrom, selfDrawn = false, tile = pendingTile)
            "gang" -> {                          // 明杠
                repeat(3) { hands[p].remove(pendingTile) }
                melds[p].add(MjMeld("gang", pendingTile))
                rivers[pendingFrom].remove(pendingTile)
                players[p].score += 1
                players[pendingFrom].score -= 1
                if (p == 0) selfDelta += 1
                if (pendingFrom == 0) selfDelta -= 1
                addLog("${players[p].name} 明杠 ${MjTiles.label(pendingTile)}（${players[pendingFrom].name} 付 1 分）")
                claimQueue = emptyList()
                pendingTile = -1; pendingFrom = -1
                turn = p
                stage = "draw"                   // 杠后补摸
                bump()
            }
            "peng" -> {
                repeat(2) { hands[p].remove(pendingTile) }
                melds[p].add(MjMeld("peng", pendingTile))
                rivers[pendingFrom].remove(pendingTile)
                addLog("${players[p].name} 碰 ${MjTiles.label(pendingTile)}")
                claimQueue = emptyList()
                pendingTile = -1; pendingFrom = -1
                turn = p
                stage = "act"                    // 碰后直接出牌
                bump()
            }
        }
        QQSounds.card()
    }

    // ---------- 自摸动作 ----------

    fun selfHu() {
        if (stage != "act" || !canSelfHu(turn)) return
        settleHu(turn, -1, selfDrawn = true, tile = lastDrawn)
    }

    fun selfGang(tile: Int) {
        if (stage != "act" || hands[turn].count { it == tile } < 4) return
        repeat(4) { hands[turn].remove(tile) }
        melds[turn].add(MjMeld("angang", tile))
        for (k in 1..3) {
            val p = (turn + k) % 4
            players[p].score -= 1
            if (p == 0) selfDelta -= 1
        }
        players[turn].score += 3
        if (turn == 0) selfDelta += 3
        addLog("${players[turn].name} 暗杠 ${MjTiles.label(tile)}（每家付 1 分）")
        lastDrawn = -1
        stage = "draw"                           // 补摸
        bump()
        QQSounds.card()
    }

    // ---------- 结算 ----------

    private fun nextTurn() {
        claimQueue = emptyList()
        pendingTile = -1; pendingFrom = -1
        turn = (turn + 1) % 4
        stage = "draw"
        bump()
    }

    private fun settleHu(w: Int, payer: Int, selfDrawn: Boolean, tile: Int) {
        stage = "over"
        winner = w
        winTile = tile
        winSelfDrawn = selfDrawn
        winPayer = payer
        if (selfDrawn) {
            players[w].score += 6
            if (w == 0) selfDelta += 6
            for (k in 1..3) {
                val p = (w + k) % 4
                players[p].score -= 2
                if (p == 0) selfDelta -= 2
            }
            addLog("🎉 ${players[w].name} 自摸 ${MjTiles.label(tile)}！每家付 2 分")
        } else {
            players[w].score += 4
            players[payer].score -= 4
            if (w == 0) selfDelta += 4
            if (payer == 0) selfDelta -= 4
            addLog("🎉 ${players[w].name} 胡 ${MjTiles.label(tile)}（${players[payer].name} 点炮）")
        }
        bump()
    }

    private fun settleDraw() {
        stage = "over"
        winner = 4
        addLog("牌墙已摸完，流局")
        bump()
    }
}

// ==================== 牌桌 UI ====================

@Composable
fun QQMahjongTableScreen(onExit: () -> Unit) {
    val acc = QQSession.account
    val selfName = acc?.nickname ?: "我"
    val selfScore = remember { QQGameScores.load()["麻将·$selfName"] ?: 0 }
    val game = remember { MahjongGame(selfName, acc?.avatar ?: "emoji:🐧", selfScore) }
    var selIdx by remember { mutableStateOf(-1) }

    // ---- AI 驱动（摸牌 / 出牌 / 应答） ----
    LaunchedEffect(game.turn, game.stage, game.pendingTile, game.claimQueue, game.lastDrawn, game.winner, game.tick) {
        if (game.winner != -1) return@LaunchedEffect
        when (game.stage) {
            "draw" -> {
                val me = game.turn
                if (game.players[me].isHuman) {
                    delay(250)
                    game.drawTile()
                } else {
                    delay(Random.nextLong(700, 1300))
                    if (game.stage != "draw" || game.winner != -1) return@LaunchedEffect
                    game.drawTile()
                    if (game.winner != -1) return@LaunchedEffect
                    if (game.canSelfHu(me)) { game.selfHu(); return@LaunchedEffect }
                    val gangs = game.canAnGang(me)
                    if (gangs.isNotEmpty()) { game.selfGang(gangs.first()); return@LaunchedEffect }
                    val t = MjAi.discard(game.hands[me], game.melds[me])
                    game.playerDiscard(me, t)
                }
            }
            "act" -> {
                val me = game.turn
                if (!game.players[me].isHuman) {
                    delay(Random.nextLong(700, 1200))
                    if (game.stage != "act" || game.winner != -1) return@LaunchedEffect
                    val t = MjAi.discard(game.hands[me], game.melds[me])
                    game.playerDiscard(me, t)
                }
            }
            "claim" -> {
                val head = game.claimQueue.firstOrNull() ?: return@LaunchedEffect
                if (!game.players[head.first].isHuman) {
                    delay(Random.nextLong(600, 1100))
                    if (game.stage == "claim" && game.claimQueue.firstOrNull() == head) {
                        game.resolveClaim(true)
                    }
                }
            }
        }
    }

    // ---- 结算：音效 + 积分落盘 ----
    LaunchedEffect(game.winner) {
        when (game.winner) {
            0 -> QQSounds.win()
            in 1..3 -> QQSounds.lose()
            4 -> QQSounds.ding()
        }
        if (game.winner != -1 && game.selfDelta != 0) {
            runCatching { QQGameScores.add("麻将·$selfName", game.selfDelta) }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // ================= 桌面区 =================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF2C5A8F), Color(0xFF1B3A61))))
        ) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                // ---- 对家（上方）----
                MjSeatBar(game, 2)
                Spacer(Modifier.height(4.dp))

                // ---- 中区：左家 | 牌池 | 右家 ----
                Row(Modifier.weight(1f)) {
                    MjSeatSide(game, 3)
                    Spacer(Modifier.width(6.dp))
                    Column(
                        Modifier.weight(1f).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "余 ${game.wall.size} 张 · 庄家：${game.players[game.dealer].name}",
                            color = Color(0xFFBFE4FB), fontSize = 11.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        // 牌池：最新打出的牌
                        if (game.stage == "claim" && game.pendingTile >= 0) {
                            Text(
                                "${game.players[game.pendingFrom].name} 打出：",
                                color = Color(0xFFFFE95C), fontSize = 11.sp
                            )
                            Spacer(Modifier.height(3.dp))
                            MjTileFace(game.pendingTile, 30.dp)
                        } else {
                            val hint = when {
                                game.winner != -1 -> "本局结束"
                                game.stage == "draw" && game.turn == 0 -> "轮到你摸牌…"
                                game.stage == "act" && game.turn == 0 -> "轮到你出牌"
                                game.stage == "claim" && game.claimQueue.firstOrNull()?.first == 0 ->
                                    "你要" + when (game.claimQueue.first().second) {
                                        "hu" -> "胡吗？"
                                        "gang" -> "杠吗？"
                                        else -> "碰吗？"
                                    }
                                else -> "等待${game.players[game.turn].name}…"
                            }
                            Text(hint, color = Color(0xFFFFE95C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    MjSeatSide(game, 1)
                }
                Spacer(Modifier.height(4.dp))

                // ---- 我的牌河 ----
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    game.rivers[0].chunked(16).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { MjTileFace(it, 15.dp) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                // ---- 应答 / 操作按钮 ----
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        game.stage == "claim" && game.claimQueue.firstOrNull()?.first == 0 -> {
                            when (game.claimQueue.first().second) {
                                "hu" -> QqBeigeButton("胡！", { game.resolveClaim(true) }, height = 32.dp)
                                "gang" -> QqBeigeButton("杠", { game.resolveClaim(true) }, height = 32.dp)
                                else -> QqBeigeButton("碰", { game.resolveClaim(true) }, height = 32.dp)
                            }
                            Spacer(Modifier.width(8.dp))
                            QqBeigeButton("过", { game.resolveClaim(false) }, height = 32.dp)
                        }
                        game.stage == "act" && game.turn == 0 -> {
                            if (game.canSelfHu(0)) {
                                QqBeigeButton("自摸胡！", { game.selfHu() }, height = 32.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            val gangs = game.canAnGang(0)
                            if (gangs.isNotEmpty()) {
                                QqBeigeButton("暗杠 ${MjTiles.label(gangs.first())}", { game.selfGang(gangs.first()) }, height = 32.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            QqBeigeButton(
                                "出 牌",
                                {
                                    if (selIdx in game.hands[0].indices) {
                                        val t = game.hands[0][selIdx]
                                        selIdx = -1
                                        game.playerDiscard(0, t)
                                    }
                                },
                                enabled = selIdx >= 0,
                                height = 32.dp
                            )
                        }
                        else -> Text(
                            if (game.winner != -1) "" else "…",
                            color = Color(0xFFBFE4FB), fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                // ---- 我的手牌 ----
                Row(verticalAlignment = Alignment.Bottom) {
                    MjSelfBadge(game)
                    Spacer(Modifier.width(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        game.hands[0].forEachIndexed { i, c ->
                            Box(Modifier.offset(y = if (selIdx == i) (-10).dp else 0.dp)) {
                                MjTileFace(c, 34.dp, selected = selIdx == i) { selIdx = if (selIdx == i) -1 else i }
                            }
                        }
                    }
                }
            }

            // ---- 结算浮层 ----
            if (game.winner != -1) {
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
                        val myWin = game.winner == 0
                        Text(
                            when {
                                game.winner == 4 -> "〰 流 局 〰"
                                myWin -> "🎉 你胡了！🎉"
                                else -> "😅 ${game.players[game.winner].name} 胡了"
                            },
                            color = if (myWin) Color(0xFFFFE95C) else Color(0xFFBFE4FB),
                            fontSize = 24.sp, fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                game.winner == 4 -> "牌墙摸完，本局不计分"
                                game.winSelfDrawn -> "自摸 ${MjTiles.label(game.winTile)} · 每家付 2 分"
                                else -> "胡 ${MjTiles.label(game.winTile)} · ${game.players[game.winPayer].name} 点炮付 4 分"
                            },
                            color = Color.White, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "本局你的得分：${if (game.selfDelta >= 0) "+" else ""}${game.selfDelta}",
                            color = if (game.selfDelta >= 0) Color(0xFF7CE87C) else Color(0xFFFF9A9A),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(14.dp))
                        Row {
                            QqBeigeButton("再来一局", { QQSounds.click(); selIdx = -1; game.startDeal() }, height = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            QqBeigeButton("退出牌桌", onExit, height = 32.dp)
                        }
                    }
                }
            }
        }

        // ================= 右侧面板 =================
        MjSidePanel(game, onExit)
    }
}

@Composable
private fun MjSelfBadge(game: MahjongGame) {
    val p = game.players[0]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            QqAvatar(avatar = p.avatar, size = 40.dp)
            if (game.dealer == 0) {
                Text("👑", fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        Text(p.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("副露 ${game.melds[0].size}", color = Color(0xFFBFE4FB), fontSize = 9.sp)
    }
}

/** 上方玩家条 */
@Composable
private fun MjSeatBar(game: MahjongGame, idx: Int) {
    val p = game.players[idx]
    val active = game.winner == -1 && (game.turn == idx) &&
            (game.stage == "draw" || game.stage == "act" || (game.stage == "claim" && game.pendingFrom == idx))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) Color(0x33FFE95C) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            QqAvatar(avatar = p.avatar, size = 36.dp)
            if (game.dealer == idx) {
                Text("👑", fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.width(92.dp)) {
            Text(p.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${ddTitle(p.level)}·Lv${p.level} · ${p.score}分", color = Color(0xFFFFE95C), fontSize = 9.sp)
        }
        Spacer(Modifier.weight(1f))
        // 副露
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            game.melds[idx].forEach { m ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    repeat(if (m.type == "angang") 4 else 3) { MjTileFace(m.tile, 13.dp) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // 手牌（背面）
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(game.hands[idx].size.coerceAtMost(13)) { MjTileBack(13.dp) }
        }
        Spacer(Modifier.width(6.dp))
        Text("剩${game.hands[idx].size}张", color = Color(0xFFBFE4FB), fontSize = 10.sp)
    }
}

/** 左/右家 */
@Composable
private fun MjSeatSide(game: MahjongGame, idx: Int) {
    val p = game.players[idx]
    val active = game.winner == -1 && (game.turn == idx) &&
            (game.stage == "draw" || game.stage == "act" || (game.stage == "claim" && game.pendingFrom == idx))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(92.dp)
            .fillMaxSize()
            .background(if (active) Color(0x33FFE95C) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(vertical = 3.dp)
    ) {
        Box {
            QqAvatar(avatar = p.avatar, size = 34.dp)
            if (game.dealer == idx) {
                Text("👑", fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart))
            }
        }
        Text(p.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${p.score}分", color = Color(0xFFFFE95C), fontSize = 9.sp)
        Spacer(Modifier.height(3.dp))
        // 副露
        game.melds[idx].forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(if (m.type == "angang") 4 else 3) { MjTileFace(m.tile, 13.dp) }
            }
            Spacer(Modifier.height(1.dp))
        }
        Spacer(Modifier.weight(1f))
        // 牌河（竖排小牌）
        Column(
            Modifier
                .weight(2f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            game.rivers[idx].chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.forEach { MjTileFace(it, 13.dp) }
                }
            }
        }
        Text("剩${game.hands[idx].size}张", color = Color(0xFFBFE4FB), fontSize = 9.sp)
    }
}

@Composable
private fun MjTileFace(code: Int, w: Dp, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val color = when (code / 9) {
        0 -> Color(0xFFC62828)
        1 -> Color(0xFF1565C0)
        else -> Color(0xFF2E7D32)
    }
    Box(
        modifier = Modifier
            .width(w)
            .height(w * 1.35f)
            .background(Color.White, RoundedCornerShape(3.dp))
            .border(1.dp, if (selected) Color(0xFFFFB400) else Color(0xFF9DB6CC), RoundedCornerShape(3.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                MjTiles.RANKS[code % 9], color = color,
                fontSize = (w.value * 0.42f).sp, fontWeight = FontWeight.Bold,
                lineHeight = (w.value * 0.46f).sp
            )
            Text(
                MjTiles.SUITS[code / 9], color = color,
                fontSize = (w.value * 0.36f).sp,
                lineHeight = (w.value * 0.40f).sp
            )
        }
    }
}

@Composable
private fun MjTileBack(w: Dp) {
    Box(
        modifier = Modifier
            .width(w)
            .height(w * 1.35f)
            .background(
                Brush.verticalGradient(listOf(Color(0xFF3E7CB8), Color(0xFF2A5A8C))),
                RoundedCornerShape(2.dp)
            )
            .border(0.5.dp, Color(0xFFBFE4FB), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("🀄", fontSize = (w.value * 0.5f).sp)
    }
}

@Composable
private fun MjSidePanel(game: MahjongGame, onExit: () -> Unit) {
    val logScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .width(196.dp)
            .fillMaxSize()
            .background(QQ2008.panelBrush())
            .border(1.dp, QQ2008.PanelBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🀄 欢乐麻将", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "退出", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clickable { QQSounds.click(); onExit() }
            )
        }

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
                    fontSize = 11.sp, color = QQ2008.TextMain, maxLines = 1
                )
                Spacer(Modifier.weight(1f))
                Text("${p.score}", fontSize = 11.sp, color = Color(0xFFC3272B), fontWeight = FontWeight.Bold)
            }
        }

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
                Text(line, fontSize = 10.sp, lineHeight = 14.sp, color = QQ2008.TextMain)
            }
            Spacer(Modifier.height(4.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFE8F4FD))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            listOf("👍", "😂", "🙏", "😎", "🀄", "🔥").forEach { e ->
                Text(
                    e, fontSize = 16.sp,
                    modifier = Modifier.clickable {
                        game.log.add("${game.players[0].name}：[$e]")
                        QQSounds.msgOut()
                    }
                )
            }
        }
    }
}
