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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
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

// ==================== 牌桌 UI（截图3：QQ欢乐麻将 复刻） ====================

/** 麻将级别称号（截图3：白板 / 一万…九万） */
private fun mjTitle(level: Int): String = when {
    level < 3 -> "白板"
    level < 6 -> "一万"
    level < 9 -> "二万"
    level < 12 -> "三万"
    level < 15 -> "四万"
    level < 18 -> "五万"
    level < 21 -> "六万"
    level < 24 -> "七万"
    level < 30 -> "八万"
    else -> "九万"
}

@Composable
fun QQMahjongTableScreen(onExit: () -> Unit) {
    val acc = QQSession.account
    val selfName = acc?.nickname ?: "我"
    val selfScore = remember { QQGameScores.load()["麻将·$selfName"] ?: 0 }
    val game = remember { MahjongGame(selfName, acc?.avatar ?: "emoji:🐧", selfScore) }
    var selIdx by remember { mutableStateOf(-1) }

    // ---- 倒计时（截图3 中央 LED 09；行动方从 15 起跳） ----
    var countdown by remember { mutableStateOf(15) }
    LaunchedEffect(game.turn, game.stage) {
        countdown = 15
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

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
                    QQSounds.card()
                }
            }
            "act" -> {
                val me = game.turn
                if (!game.players[me].isHuman) {
                    delay(Random.nextLong(700, 1200))
                    if (game.stage != "act" || game.winner != -1) return@LaunchedEffect
                    val t = MjAi.discard(game.hands[me], game.melds[me])
                    game.playerDiscard(me, t)
                    QQSounds.card()
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

    // ---- 入桌洗牌/发牌音效 ----
    LaunchedEffect(Unit) {
        QQSounds.shuffle()
        delay(600)
        QQSounds.deal()
    }

    // ---- 方向自适应 + 真实桌面底图 + 经典四家立牌（其他玩家排列修正）----
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cw = maxWidth
        val ch = maxHeight
        val portrait = cw < ch
        val tableW = if (portrait) cw else cw - 200.dp
        // 我的立牌尺寸：按桌宽自适配（13~14 张一手）
        val myTileW = ((tableW - 64.dp) / 14.5f).coerceIn(22.dp, 40.dp)
        // 侧家（左/右）立牌尺寸：整列 13 张竖排按可用高度自适配
        val sideAvailH = ch - 46.dp - 24.dp - 18.dp - 34.dp - (myTileW * 1.38f + 16.dp) - 26.dp
        val sideTileW = (sideAvailH / 13f / 1.42f).coerceIn(6.dp, 13.dp)

        Row(Modifier.fillMaxSize()) {
        // ================= 桌面区（真实麻将桌面底图，缺失回退纯色）=================
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(QqGame.MjFeltEdge)
        ) {
            QQGameAssetImage("qgame/mj_table.jpg", Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(4.dp)) {
                // ---- 对家（上方）：信息条 + 顶部牌墙（真实卧倒墙砖，随摸牌减少）----
                MjSeatTop(game, 2)
                Spacer(Modifier.height(1.dp))
                MjWallRow(count = (game.wall.size / 4 + 1).coerceAtMost(14))
                Spacer(Modifier.height(2.dp))

                // ---- 中区：左家竖排立牌 | 弃牌池+LED | 右家竖排立牌（经典排列）----
                Row(Modifier.weight(1f)) {
                    // 左家：紧凑座位 + 整列立牌背（张数=手牌数，随出牌减少）
                    MjSideSeat(game, 3, tileW = sideTileW)
                    Spacer(Modifier.width(2.dp))

                    // 中央弃牌池（截图3：牌墙围出的中央区，弃牌按行整齐排列）
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        MjDiscardPool(game, Modifier.fillMaxSize())
                        // 中央 LED 倒计时（截图3：黑底黄字 09 + 黄色指示三角）
                        QqLedTimer(
                            seconds = countdown,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    Spacer(Modifier.width(2.dp))

                    // 右家：紧凑座位 + 整列立牌背
                    MjSideSeat(game, 1, tileW = sideTileW)
                }
                Spacer(Modifier.height(2.dp))

                // ---- 下侧牌墙 ----
                MjWallRow(count = (game.wall.size / 4).coerceAtMost(14))

                // ---- 应答 / 操作按钮 ----
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        game.stage == "claim" && game.claimQueue.firstOrNull()?.first == 0 -> {
                            when (game.claimQueue.first().second) {
                                "hu" -> QqGreenButton("胡！", { game.resolveClaim(true) }, height = 30.dp)
                                "gang" -> QqGreenButton("杠", { game.resolveClaim(true) }, height = 30.dp)
                                else -> QqGreenButton("碰", { game.resolveClaim(true) }, height = 30.dp)
                            }
                            Spacer(Modifier.width(8.dp))
                            QqWhiteButton("过", { game.resolveClaim(false) }, height = 30.dp)
                        }
                        game.stage == "act" && game.turn == 0 -> {
                            if (game.canSelfHu(0)) {
                                QqGreenButton("自摸胡！", { game.selfHu() }, height = 30.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            val gangs = game.canAnGang(0)
                            if (gangs.isNotEmpty()) {
                                QqGreenButton("暗杠 ${MjTiles.label(gangs.first())}", { game.selfGang(gangs.first()) }, height = 30.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            QqGreenButton(
                                "出 牌",
                                {
                                    if (selIdx in game.hands[0].indices) {
                                        val t = game.hands[0][selIdx]
                                        selIdx = -1
                                        game.playerDiscard(0, t)
                                        QQSounds.card()
                                    }
                                },
                                height = 30.dp
                            )
                        }
                        else -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (game.winner != -1) "" else mjHint(game),
                                color = Color(0xFFD8F0FA), fontSize = 11.sp
                            )
                            if (portrait) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "« 退出牌桌",
                                    color = Color(0xFFFFE95C), fontSize = 11.sp,
                                    modifier = Modifier.clickable { QQSounds.click(); onExit() }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))

                // ---- 我的信息 + 手牌（截图3 底部）----
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
                                MjTileFace(c, myTileW, selected = selIdx == i) { selIdx = if (selIdx == i) -1 else i }
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
                            QqGreenButton("再来一局", { QQSounds.click(); QQSounds.shuffle(); selIdx = -1; game.startDeal() }, height = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            QqWhiteButton("退出牌桌", onExit, height = 32.dp)
                        }
                    }
                }
            }
        }

        // ================= 右侧面板（截图3 经典样式；竖屏收纳让位牌桌）=================
        if (!portrait) MjSidePanel(game, onExit)
        }
    }
}

// ==================== 座位（截图3 样式） ====================

/** 我的徽标（手牌左侧：头像 + 昵称 + 欢乐豆） */
@Composable
private fun MjSelfBadge(game: MahjongGame) {
    val p = game.players[0]
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(2.dp, if (game.turn == 0 && game.winner == -1) Color(0xFFFFE95C) else Color(0xFFBFE4FB), RoundedCornerShape(4.dp))
                .padding(2.dp)
        ) {
            QqPenguinAvatar(
                size = 40.dp,
                modifier = Modifier.fillMaxSize(),
                scarf = if (game.dealer == 0) Color(0xFFC3272B) else Color(0xFF2E7D32)
            )
            if (game.dealer == 0) {
                Text("👑", fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).offset(x = (-3).dp, y = (-3).dp))
            }
        }
        Text(p.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text("欢乐豆 2480", color = Color(0xFFFFE95C), fontSize = 9.sp)
    }
}

/** 对家（上方玩家条：头像 + 昵称 + 等级 + 花数 + 手牌背，截图3 顶部） */
@Composable
private fun MjSeatTop(game: MahjongGame, idx: Int) {
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
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(2.dp, Color(0xFFBFE4FB), RoundedCornerShape(4.dp))
                .padding(2.dp)
        ) {
            QqPenguinAvatar(
                size = 34.dp, modifier = Modifier.fillMaxSize(),
                scarf = if (game.dealer == idx) Color(0xFFC3272B) else Color(0xFF1E6CB4)
            )
            if (game.dealer == idx) {
                Text("👑", fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).offset(x = (-3).dp, y = (-3).dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.width(96.dp)) {
            Text(p.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text("等级: ${mjTitle(p.level)}", color = Color(0xFFFFE95C), fontSize = 9.sp)
                Spacer(Modifier.width(4.dp))
                Text("花 x 0", color = Color(0xFFBDE3FF), fontSize = 9.sp)
            }
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
        Text("剩${game.hands[idx].size}张", color = Color(0xFFD8F0FA), fontSize = 10.sp)
    }
}

/** 左/右家（经典竖排列）：紧凑座位卡在上 + 整列立牌背（真实牌背贴图，张数=手牌数）+ 底部副露 */
@Composable
private fun MjSideSeat(game: MahjongGame, idx: Int, tileW: Dp) {
    val p = game.players[idx]
    val active = game.winner == -1 && (game.turn == idx) &&
            (game.stage == "draw" || game.stage == "act" || (game.stage == "claim" && game.pendingFrom == idx))
    val seatW = (tileW * 3.4f).coerceIn(30.dp, 48.dp)
    val av = (tileW * 2.4f).coerceAtLeast(24.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(seatW)
            .fillMaxHeight()
            .background(if (active) Color(0x33FFE95C) else Color(0x22000000), RoundedCornerShape(4.dp))
            .padding(vertical = 2.dp, horizontal = 1.dp)
    ) {
        // 头像（庄家红围巾 + 皇冠）
        Box(
            modifier = Modifier
                .size(av)
                .background(Color.White, RoundedCornerShape(3.dp))
                .border(1.dp, Color(0xFFBFE4FB), RoundedCornerShape(3.dp))
                .padding(1.dp)
        ) {
            QqPenguinAvatar(
                size = av - 2.dp, modifier = Modifier.fillMaxSize(),
                scarf = if (game.dealer == idx) Color(0xFFC3272B) else Color(0xFF1E6CB4)
            )
            if (game.dealer == idx) {
                Text("👑", fontSize = 9.sp, modifier = Modifier.align(Alignment.TopStart).offset(x = (-3).dp, y = (-3).dp))
            }
        }
        Text(p.name, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("剩${game.hands[idx].size}张", color = Color(0xFFFFE95C), fontSize = 8.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        // 整列立牌背（真实贴图，随出牌实时减少）
        Column(
            verticalArrangement = Arrangement.spacedBy(0.5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            repeat(game.hands[idx].size) { MjTileBack(tileW) }
        }
        Spacer(Modifier.weight(1f))
        // 副露（碰/杠小牌，底部竖排）
        game.melds[idx].forEach { m ->
            Row(horizontalArrangement = Arrangement.spacedBy(0.5.dp)) {
                repeat(if (m.type == "angang") 4 else 3) { MjTileFace(m.tile, tileW * 0.8f) }
            }
        }
    }
}

// ==================== 牌墙（真实卧倒墙砖贴图，截图3 绿牌墙围合） ====================

/** 横向牌墙（上/下两侧，真实墙砖） */
@Composable
private fun MjWallRow(count: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        repeat(count) {
            QQGameAssetImage("qgame/mahjong/mj_wall_h.png", Modifier.size(15.dp, 10.dp))
        }
    }
}

/** 中央弃牌池（截图3：四家弃牌分区整齐排列，每行 6 张） */
@Composable
private fun MjDiscardPool(game: MahjongGame, modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(Color(0x1EFFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 对家弃牌（每行 6 张）
        game.rivers[2].chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { MjTileFace(it, 15.dp) }
            }
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // 左家弃牌（每行 2 张）
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                game.rivers[3].takeLast(8).chunked(2).forEach { col ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        col.forEach { MjTileFace(it, 13.dp) }
                    }
                }
            }
            Spacer(Modifier.weight(0.6f))
            // 中央信息（最新打出的牌 + 余牌数）
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (game.stage == "claim" && game.pendingTile >= 0) {
                    Text(
                        "${game.players[game.pendingFrom].name} 打出：",
                        color = Color(0xFFFFE95C), fontSize = 9.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    MjTileFace(game.pendingTile, 24.dp)
                }
                Text("余 ${game.wall.size} 张", color = Color(0xFFD8F0FA), fontSize = 9.sp)
            }
            Spacer(Modifier.weight(0.6f))
            // 右家弃牌（每行 2 张）
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                game.rivers[1].takeLast(8).chunked(2).forEach { col ->
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        col.forEach { MjTileFace(it, 13.dp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // 我的弃牌（每行 6 张）
        game.rivers[0].chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { MjTileFace(it, 15.dp) }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

private fun mjHint(game: MahjongGame): String = when {
    game.winner != -1 -> "本局结束"
    game.stage == "claim" && game.claimQueue.firstOrNull()?.first == 0 ->
        "你要" + when (game.claimQueue.first().second) {
            "hu" -> "胡吗？"
            "gang" -> "杠吗？"
            else -> "碰吗？"
        }
    game.stage == "draw" && game.turn == 0 -> "轮到你摸牌"
    game.stage == "act" && game.turn == 0 -> "轮到你出牌"
    else -> "等待${game.players[game.turn].name}…"
}

// ==================== 牌面（标准麻将位图素材 assets/qgame/mahjong/） ====================

/** 牌面素材路径（code/9：0=万 1=筒 2=条；code%9+1 = 1..9） */
private fun mjTileAsset(code: Int): String {
    val s = when (code / 9) { 0 -> "w"; 1 -> "t"; else -> "s" }
    return "qgame/mahjong/mj_${s}${code % 9 + 1}.png"
}

@Composable
private fun MjTileFace(code: Int, w: Dp, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .width(w)
            .height(w * 1.38f)
            .clip(shape)
            .border(1.dp, if (selected) Color(0xFFFFB400) else Color(0xFF8FA8BC), shape)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
    ) {
        QQGameAssetImage(mjTileAsset(code), Modifier.fillMaxSize())
    }
}

@Composable
private fun MjTileBack(w: Dp) {
    Box(
        modifier = Modifier
            .width(w)
            .height(w * 1.38f)
    ) {
        QQGameAssetImage("qgame/mahjong/mj_back.png", Modifier.fillMaxSize())
    }
}

// ==================== 右侧面板（截图3 经典样式） ====================

@Composable
private fun MjSidePanel(game: MahjongGame, onExit: () -> Unit) {
    val logScroll = rememberScrollState()
    val self = game.players[0]
    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxSize()
            .background(QqGame.PanelLightBg)
            .border(1.dp, Color(0xFF6FA6D8))
    ) {
        // ---- 顶部浅蓝信息条（截图3：企鹅头像 + 昵称 + 积分/级别/欢乐豆）----
        QqPanelHeader(
            nickname = self.name,
            scoreText = "${self.score}",
            coinText = "欢乐豆: 2480"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFDCEFFB))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("级别: ${mjTitle(self.level)}", color = Color(0xFF2E6288), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "退出",
                color = Color(0xFF0B62C4),
                fontSize = 11.sp,
                modifier = Modifier.clickable { QQSounds.click(); onExit() }
            )
        }

        // ---- 积分排行（截图3：昵称 | 欢乐豆 | 积分）----
        QqPanelSection("积分排行")
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFE4EFF7))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text("昵称", fontSize = 10.sp, color = Color(0xFF5B7B99), modifier = Modifier.weight(1.4f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("欢乐豆", fontSize = 10.sp, color = Color(0xFF5B7B99), modifier = Modifier.weight(0.9f), textAlign = TextAlign.End)
            Text("积分", fontSize = 10.sp, color = Color(0xFF5B7B99), modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
        }
        game.players.sortedByDescending { it.score }.forEach { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (p.isHuman) Color(0xFFBFE4FB) else Color.White)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                QqPenguinAvatar(size = 16.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    p.name + if (p.isHuman) "（你）" else "",
                    fontSize = 10.sp, color = QQ2008.TextMain, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.4f)
                )
                Text(
                    "${1200 + (p.score * 37).coerceAtLeast(0)}",
                    fontSize = 10.sp, color = QQ2008.TextSub,
                    modifier = Modifier.weight(0.9f), textAlign = TextAlign.End, maxLines = 1
                )
                Text(
                    "${p.score}",
                    fontSize = 10.sp, color = Color(0xFFC3272B), fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.8f), textAlign = TextAlign.End
                )
            }
        }

        // ---- 道具栏（截图3：公共道具 / 专属道具 + 翻页箭头）----
        QqPanelSection("道具")
        QqPropsBar(title = "公共道具", propCount = 1)
        QqPropsBar(title = "专属道具", propCount = 0)

        // ---- 蓝钻 / 商城（截图3）----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QqBlueDiamondButton("蓝钻贵族", diamond = true, onClick = { QQSounds.click() }, modifier = Modifier.weight(1f))
            QqBlueDiamondButton("游戏商城", diamond = false, onClick = { QQSounds.click() }, modifier = Modifier.weight(1f))
        }
        // 获取欢乐豆（截图3 手牌区上方蓝色高亮按钮）
        QqBlueDiamondButton(
            "获取欢乐豆",
            diamond = false,
            onClick = { QQSounds.click() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )

        // ---- 聊天区（截图3："快点吧，我等到花儿也谢了"）----
        QqPanelSection("聊天")
        LaunchedEffect(game.log.size) {
            runCatching { logScroll.animateScrollTo(logScroll.maxValue) }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(logScroll)
                .background(Color.White)
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            game.log.forEach { line ->
                Text(line, fontSize = 10.sp, lineHeight = 14.sp, color = QQ2008.TextMain)
            }
            Text(
                "顽石：快点吧，我等到花儿也谢了",
                fontSize = 10.sp, lineHeight = 14.sp,
                color = Color(0xFFC3272B)
            )
            Spacer(Modifier.height(4.dp))
        }
        // 输入行 + 表情按钮（截图3 底部）
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFEAF6FE))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .background(Color.White, RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0xFF9FC2DC), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text("Hi~，快出牌吧！", fontSize = 9.sp, color = Color(0xFF9AAFC2), modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(Modifier.width(4.dp))
            listOf("😊", "🀄").forEach { e ->
                Text(
                    e, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        game.log.add("${game.players[0].name}：$e")
                        QQSounds.msgOut()
                    }
                )
                Spacer(Modifier.width(2.dp))
            }
        }

        // ---- 任务提示（截图3：番种任务 / 吃碰牌任务）----
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF7E0))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text("📌 任务：番种任务 · 吃碰牌任务", fontSize = 9.sp, color = Color(0xFF8A6D2B), maxLines = 1)
        }
    }
}
