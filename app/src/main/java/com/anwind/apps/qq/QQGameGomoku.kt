package com.anwind.apps.qq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 五子棋（QQ游戏大厅 · 本地游戏）：
 *  - 15 路棋盘，玩家执黑先行，AI 执白；
 *  - AI：空点四方向连形评分（冲四/活四/活三…），攻守加权取最高点；
 *  - 横/竖/两条斜线先连成五子者胜，胜利连线金色高亮；
 *  - 积分存档 key = "五子棋·昵称"（胜 +2 / 负 -2）。
 */

// ==================== 对局状态 ====================

class GomokuGame(selfName: String, selfAvatar: String, selfScore: Int) {

    val players = listOf(
        DdPlayer(selfName, selfAvatar, true, 8, selfScore),
        DdPlayer(GamePeople.randomName(), GamePeople.randomAvatar(), false, 3 + Random.nextInt(30), 60 + Random.nextInt(900))
    )

    /** 0 空 1 黑(玩家) 2 白(AI) */
    val board = IntArray(225)
    var turn by mutableStateOf(1)
    var winner by mutableStateOf(0)          // 0 进行中 / 1 黑胜 / 2 白胜 / 3 和棋
    var lastMove by mutableStateOf(-1)
    var winLine by mutableStateOf<List<Int>>(emptyList())
    var moveCount by mutableStateOf(0)
    val log = mutableStateListOf<String>()

    init {
        addLog("—— 对局开始，你执黑先行 ——")
    }

    private fun addLog(text: String) {
        log.add(text)
        while (log.size > 60) log.removeAt(0)
    }

    /** 落子（含校验）。返回是否成功 */
    fun place(idx: Int): Boolean {
        if (winner != 0 || idx !in 0..224 || board[idx] != 0) return false
        board[idx] = turn
        lastMove = idx
        moveCount++
        val who = if (turn == 1) players[0].name else players[1].name
        addLog("$who：${'A' + idx % 15}${idx / 15 + 1}")
        val line = findWin(idx, turn)
        if (line != null) {
            winner = turn
            winLine = line
            addLog(if (turn == 1) "五连！你赢了 🎉" else "AI 五连，你输了")
        } else if (moveCount >= 225) {
            winner = 3
            addLog("棋盘已满，和棋")
        } else {
            turn = 3 - turn
        }
        QQSounds.card()
        return true
    }

    /** 重开一局 */
    fun reset() {
        for (i in board.indices) board[i] = 0
        turn = 1; winner = 0; lastMove = -1; winLine = emptyList(); moveCount = 0
        log.clear()
        addLog("—— 新对局开始，你执黑先行 ——")
    }

    /** 从 idx 出发找 side 方的五连（返回连线格子；不足五连返回 null） */
    private fun findWin(idx: Int, side: Int): List<Int>? {
        val r0 = idx / 15
        val c0 = idx % 15
        for (d in DIRS) {
            val cells = mutableListOf(idx)
            for (sign in intArrayOf(1, -1)) {
                var i = 1
                while (true) {
                    val rr = r0 + d[0] * i * sign
                    val cc = c0 + d[1] * i * sign
                    if (rr !in 0..14 || cc !in 0..14) break
                    if (board[rr * 15 + cc] != side) break
                    cells.add(rr * 15 + cc)
                    i++
                }
            }
            if (cells.size >= 5) return cells
        }
        return null
    }

    companion object {
        private val DIRS = arrayOf(
            intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1)
        )
    }
}

// ==================== AI ====================

object GmAi {
    private val DIRS = arrayOf(
        intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1)
    )

    /** 返回 AI（side）的落子点 */
    fun move(board: IntArray, side: Int): Int {
        if (board.all { it == 0 }) return 7 * 15 + 7   // 空盘落天元
        val opp = 3 - side
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (idx in 0..224) {
            if (board[idx] != 0) continue
            val atk = pointScore(board, idx, side)
            val def = pointScore(board, idx, opp)
            // 自己成五 / 挡对方成五优先；进攻略加权
            val s = atk * 1.05 + def + Random.nextInt(0, 30)
            if (s > bestScore) { bestScore = s; best = idx }
        }
        return best
    }

    /** 假设 side 在空点 idx 落子，四个方向连形总分 */
    private fun pointScore(board: IntArray, idx: Int, side: Int): Long {
        val r0 = idx / 15
        val c0 = idx % 15
        var total = 0L
        for (d in DIRS) {
            var cnt = 1
            var open = 0
            for (sign in intArrayOf(1, -1)) {
                var i = 1
                while (true) {
                    val rr = r0 + d[0] * i * sign
                    val cc = c0 + d[1] * i * sign
                    if (rr !in 0..14 || cc !in 0..14) break
                    val v = board[rr * 15 + cc]
                    if (v == side) { i++; continue }
                    if (v == 0) open++
                    break
                }
                cnt += i - 1
            }
            total += when {
                cnt >= 5 -> 10_000_000L
                cnt == 4 && open >= 2 -> 1_000_000L
                cnt == 4 && open == 1 -> 130_000L
                cnt == 3 && open >= 2 -> 52_000L
                cnt == 3 && open == 1 -> 6_500L
                cnt == 2 && open >= 2 -> 900L
                cnt == 2 && open == 1 -> 90L
                else -> 12L
            }
        }
        return total
    }
}

// ==================== 牌桌 UI ====================

@Composable
fun QQGomokuTableScreen(onExit: () -> Unit) {
    val acc = QQSession.account
    val selfName = acc?.nickname ?: "我"
    val selfScore = remember { QQGameScores.load()["五子棋·$selfName"] ?: 0 }
    val game = remember { GomokuGame(selfName, acc?.avatar ?: "emoji:🐧", selfScore) }
    val cell = 23.dp
    val pad = cell * 0.55f
    val boardSize = cell * 15f + pad * 2f

    // ---- AI 行动 ----
    LaunchedEffect(game.turn, game.winner, game.moveCount) {
        if (game.winner == 0 && game.turn == 2) {
            delay(Random.nextLong(500, 1100))
            val mv = GmAi.move(game.board, 2)
            if (mv >= 0) game.place(mv)
        }
    }

    // ---- 结算：积分 + 音效 ----
    LaunchedEffect(game.winner) {
        when (game.winner) {
            1 -> {
                game.players[0].score += 2
                game.players[1].score -= 2
                QQSounds.win()
                runCatching { QQGameScores.add("五子棋·$selfName", 2) }
            }
            2 -> {
                game.players[0].score -= 2
                game.players[1].score += 2
                QQSounds.lose()
                runCatching { QQGameScores.add("五子棋·$selfName", -2) }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // ===== 棋盘 =====
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF2C5A8F), Color(0xFF1B3A61)))),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .size(boardSize)
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            if (game.winner != 0 || game.turn != 1) return@detectTapGestures
                            val padPx = pad.toPx()
                            val stepPx = (size.width - 2 * padPx) / 14f
                            val c = ((pos.x - padPx) / stepPx).roundToInt()
                            val r = ((pos.y - padPx) / stepPx).roundToInt()
                            if (r in 0..14 && c in 0..14) game.place(r * 15 + c)
                        }
                    }
            ) {
                val padPx = pad.toPx()
                val step = (size.width - 2 * padPx) / 14f
                drawRect(Color(0xFFE3B96D))
                for (i in 0..14) {
                    val p = padPx + i * step
                    drawLine(
                        Color(0xFF5C4423), Offset(padPx, p), Offset(size.width - padPx, p), 1.2f
                    )
                    drawLine(
                        Color(0xFF5C4423), Offset(p, padPx), Offset(p, size.height - padPx), 1.2f
                    )
                }
                listOf(3 to 3, 3 to 11, 11 to 3, 11 to 11, 7 to 7).forEach { (r, c) ->
                    drawCircle(
                        Color(0xFF5C4423), radius = step * 0.09f,
                        center = Offset(padPx + c * step, padPx + r * step)
                    )
                }
                for (idx in 0..224) {
                    val v = game.board[idx]
                    if (v == 0) continue
                    val center = Offset(padPx + (idx % 15) * step, padPx + (idx / 15) * step)
                    drawCircle(
                        if (v == 1) Color(0xFF1A1A1A) else Color.White,
                        radius = step * 0.44f, center = center
                    )
                    if (v == 2) drawCircle(
                        Color(0xFFB9BEC5), radius = step * 0.44f, center = center,
                        style = Stroke(width = 1.5f)
                    )
                }
                if (game.lastMove >= 0 && game.winner == 0) {
                    drawCircle(
                        Color(0xFFE53935), radius = step * 0.14f,
                        center = Offset(padPx + (game.lastMove % 15) * step, padPx + (game.lastMove / 15) * step)
                    )
                }
                if (game.winLine.isNotEmpty()) {
                    val first = game.winLine.first()
                    val last = game.winLine.last()
                    drawLine(
                        Color(0xFFFFC400),
                        start = Offset(padPx + (first % 15) * step, padPx + (first / 15) * step),
                        end = Offset(padPx + (last % 15) * step, padPx + (last / 15) * step),
                        strokeWidth = 4f, cap = StrokeCap.Round
                    )
                }
            }
        }

        // ===== 右侧面板 =====
        GmSidePanel(game, selfName, onExit)
    }
}

@Composable
private fun GmSidePanel(game: GomokuGame, selfName: String, onExit: () -> Unit) {
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
            Text("⚫ 五子棋", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "退出", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clickable { QQSounds.click(); onExit() }
            )
        }

        QqSectionBar(title = "对局双方", leading = "👥")
        game.players.forEachIndexed { i, p ->
            val myTurn = game.winner == 0 && game.turn == i + 1
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (myTurn) QQ2008.SelectedRow else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                QqAvatar(avatar = p.avatar, size = 34.dp)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        p.name + if (p.isHuman) "（你）" else "",
                        fontSize = 11.sp, color = QQ2008.TextMain,
                        fontWeight = FontWeight.Bold, maxLines = 1
                    )
                    Text(
                        "执${if (i == 0) "黑 ⚫" else "白 ⚪"} · ${ddTitle(p.level)}·Lv${p.level}",
                        fontSize = 9.sp, color = QQ2008.TextSub
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${p.score}", fontSize = 12.sp, color = Color(0xFFC3272B), fontWeight = FontWeight.Bold)
            }
        }

        QqSectionBar(title = "对局状态", leading = "📣")
        val status = when {
            game.winner == 1 -> "🎉 五连！你赢了"
            game.winner == 2 -> "AI 五连，再接再厉"
            game.winner == 3 -> "棋盘已满，和棋"
            game.turn == 1 -> "轮到你（黑）落子"
            else -> "AI 思考中…"
        }
        Text(
            status,
            color = if (game.winner == 0) QQ2008.TextMain else Color(0xFFC3272B),
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

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
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            QqBeigeButton("重新开局", { QQSounds.click(); game.reset() }, height = 30.dp)
        }
    }
}
