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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 中国象棋（QQ游戏大厅 · 本地游戏）：
 *  - 完整规则：车马炮相仕帅兵全走法、蹩马腿、塞象眼、过河兵、九宫限制、
 *    白脸将（双帅不能照面）、被将死/困毙判负；
 *  - AI：alpha-beta 剪枝 3 层，吃子优先排序，子力价值 + 过河兵/占中 位置分；
 *  - 玩家执红先行；积分存档 key = "象棋·昵称"（胜 +5 / 负 -5）。
 */

// ==================== 规则引擎 ====================

object ChessRules {
    const val RED = 1
    const val BLACK = -1

    // 类型：1帅/将 2仕/士 3相/象 4马 5车 6炮 7兵/卒（红为正，黑为负）
    private val ORTH = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private val DIAG1 = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
    private val DIAG2 = arrayOf(intArrayOf(2, 2), intArrayOf(2, -2), intArrayOf(-2, 2), intArrayOf(-2, -2))
    private val KNIGHT = arrayOf(
        intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(-2, 1), intArrayOf(-2, -1),
        intArrayOf(1, 2), intArrayOf(1, -2), intArrayOf(-1, 2), intArrayOf(-1, -2)
    )

    /** 标准开局：行 0 黑方底线（上），行 9 红方底线（下） */
    fun initial(): IntArray {
        val b = IntArray(90)
        val back = intArrayOf(5, 4, 3, 2, 1, 2, 3, 4, 5)
        for (c in 0..8) { b[c] = -back[c]; b[81 + c] = back[c] }
        b[19] = -6; b[25] = -6; b[64] = 6; b[70] = 6   // 黑炮第2行 / 红炮第7行
        for (c in 0..8 step 2) { b[27 + c] = -7; b[54 + c] = 7 }
        return b
    }

    private fun inBoard(r: Int, c: Int) = r in 0..9 && c in 0..8
    private fun inPalace(r: Int, c: Int, side: Int) =
        c in 3..5 && (if (side == RED) r in 7..9 else r in 0..2)
    private fun ownHalf(r: Int, side: Int) = if (side == RED) r >= 5 else r <= 4

    /** from 位的伪合法走法（不过滤送将），编码 from*90+to */
    fun pseudoTargets(b: IntArray, from: Int): List<Int> {
        val p = b[from]
        if (p == 0) return emptyList()
        val side = if (p > 0) RED else BLACK
        val t = abs(p)
        val r = from / 9
        val c = from % 9
        val out = mutableListOf<Int>()

        fun tryAdd(rr: Int, cc: Int) {
            if (!inBoard(rr, cc)) return
            val v = b[rr * 9 + cc]
            if (v == 0 || (v > 0) != (p > 0)) out.add(rr * 9 + cc)
        }

        when (t) {
            5 -> for (d in ORTH) {                       // 车
                var i = 1
                while (true) {
                    val rr = r + d[0] * i
                    val cc = c + d[1] * i
                    if (!inBoard(rr, cc)) break
                    val v = b[rr * 9 + cc]
                    if (v == 0) { out.add(rr * 9 + cc); i++; continue }
                    if ((v > 0) != (p > 0)) out.add(rr * 9 + cc)
                    break
                }
            }
            6 -> for (d in ORTH) {                       // 炮
                var i = 1
                var jumped = false
                while (true) {
                    val rr = r + d[0] * i
                    val cc = c + d[1] * i
                    if (!inBoard(rr, cc)) break
                    val v = b[rr * 9 + cc]
                    if (!jumped) {
                        if (v == 0) out.add(rr * 9 + cc) else jumped = true
                    } else if (v != 0) {
                        if ((v > 0) != (p > 0)) out.add(rr * 9 + cc)
                        break
                    }
                    i++
                }
            }
            4 -> for (d in KNIGHT) {                     // 马（蹩马腿）
                val lr = r + d[0] / 2
                val lc = c + d[1] / 2
                if (!inBoard(lr, lc) || b[lr * 9 + lc] != 0) continue
                tryAdd(r + d[0], c + d[1])
            }
            3 -> for (d in DIAG2) {                      // 相/象（塞象眼、不过河）
                val rr = r + d[0]
                val cc = c + d[1]
                if (!inBoard(rr, cc) || !ownHalf(rr, side)) continue
                val er = r + d[0] / 2
                val ec = c + d[1] / 2
                if (b[er * 9 + ec] != 0) continue
                tryAdd(rr, cc)
            }
            2 -> for (d in DIAG1) {                      // 仕/士
                val rr = r + d[0]
                val cc = c + d[1]
                if (inPalace(rr, cc, side)) tryAdd(rr, cc)
            }
            1 -> for (d in ORTH) {                       // 帅/将
                val rr = r + d[0]
                val cc = c + d[1]
                if (inPalace(rr, cc, side)) tryAdd(rr, cc)
            }
            7 -> {                                       // 兵/卒
                tryAdd(r - side, c)
                val crossed = if (side == RED) r <= 4 else r >= 5
                if (crossed) { tryAdd(r, c - 1); tryAdd(r, c + 1) }
            }
        }
        return out
    }

    private fun kingSquare(b: IntArray, side: Int): Int {
        val k = side * 1
        for (i in 0..89) if (b[i] == k) return i
        return -1
    }

    /** side 方被将军（含双帅照面） */
    fun inCheck(b: IntArray, side: Int): Boolean {
        val ks = kingSquare(b, side)
        if (ks < 0) return true
        val kr = ks / 9
        val kc = ks % 9

        // 双帅照面
        val os = kingSquare(b, -side)
        if (os >= 0) {
            val or = os / 9
            if (or == kr) {
                var clear = true
                for (rr in (minOf(kr, or) + 1) until maxOf(kr, or)) {
                    if (b[rr * 9 + kc] != 0) { clear = false; break }
                }
                if (clear) return true
            }
        }

        // 车 / 炮（四方向扫描：第一个子看车，隔一个子看炮）
        for (d in ORTH) {
            var blocked = false
            var i = 1
            while (true) {
                val rr = kr + d[0] * i
                val cc = kc + d[1] * i
                if (!inBoard(rr, cc)) break
                val v = b[rr * 9 + cc]
                if (v != 0) {
                    val enemy = (v > 0) != (side > 0)
                    if (!blocked) {
                        if (enemy && abs(v) == 5) return true
                        blocked = true
                    } else {
                        if (enemy && abs(v) == 6) return true
                        break
                    }
                }
                i++
            }
        }

        // 马（注意蹩腿是马位与目标之间）
        for (d in KNIGHT) {
            val nr = kr + d[0]
            val nc = kc + d[1]
            if (!inBoard(nr, nc)) continue
            val v = b[nr * 9 + nc]
            if (v != 0 && (v > 0) != (side > 0) && abs(v) == 4) {
                val lr = nr + (-d[0]) / 2
                val lc = nc + (-d[1]) / 2
                if (b[lr * 9 + lc] == 0) return true
            }
        }

        // 兵/卒
        val ep = -side * 7
        val enemySide = -side
        for (d in arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))) {
            val rr = kr + d[0]
            val cc = kc + d[1]
            if (!inBoard(rr, cc)) continue
            val v = b[rr * 9 + cc]
            if (v != ep) continue
            if (d[1] != 0) {
                // 横向将：兵必须已过河（按兵自己的行判断）
                val crossed = if (enemySide == RED) rr <= 4 else rr >= 5
                if (crossed) return true
            } else {
                // 纵向：只有兵朝前一步吃到帅（红兵在帅下方 / 黑兵在帅上方）
                val attacks = if (enemySide == RED) d[0] == 1 else d[0] == -1
                if (attacks) return true
            }
        }
        return false
    }

    /** side 的全部合法走法（过滤送将），编码 from*90+to */
    fun allLegalMoves(b: IntArray, side: Int): List<Int> {
        val out = mutableListOf<Int>()
        for (from in 0..89) {
            val p = b[from]
            if (p == 0 || (p > 0) != (side > 0)) continue
            for (to in pseudoTargets(b, from)) {
                val cap = b[to]
                b[to] = p; b[from] = 0
                if (!inCheck(b, side)) out.add(from * 90 + to)
                b[from] = p; b[to] = cap
            }
        }
        return out
    }
}

// ==================== AI（alpha-beta 3 层） ====================

object ChessAi {
    private val VAL = intArrayOf(0, 10000, 200, 200, 400, 900, 450, 100)
    private const val INF = 1_000_000

    /** 返回 side 方的最佳走法（from*90+to），无棋可走返回 null */
    fun best(b: IntArray, side: Int, depth: Int = 3): Int? {
        val moves = ChessRules.allLegalMoves(b, side)
        if (moves.isEmpty()) return null
        val sorted = moves.sortedByDescending { abs(b[it % 90]) }
        var alpha = -INF
        val scored = mutableListOf<Pair<Int, Int>>()
        for (mv in sorted) {
            val f = mv / 90
            val t = mv % 90
            val p = b[f]
            val cap = b[t]
            b[t] = p; b[f] = 0
            val s = -negamax(b, -side, depth - 1, -INF, -alpha)
            b[f] = p; b[t] = cap
            scored.add(mv to s)
            if (s > alpha) alpha = s
        }
        val top = scored.maxOf { it.second }
        return scored.filter { it.second >= top - 10 }.random().first
    }

    private fun evaluate(b: IntArray): Int {
        var e = 0
        for (i in 0..89) {
            val p = b[i]
            if (p == 0) continue
            val t = abs(p)
            val s = if (p > 0) 1 else -1
            val r = i / 9
            val c = i % 9
            var v = VAL[t]
            when (t) {
                7 -> {
                    val crossed = if (s == 1) r <= 4 else r >= 5
                    if (crossed) v += 100 + (if (s == 1) 4 - r else r - 5) * 12
                }
                4, 6 -> if (r in 2..7 && c in 2..6) v += 15
                5 -> if (c in 2..6) v += 5
            }
            e += s * v
        }
        return e
    }

    private fun negamax(b: IntArray, side: Int, depth: Int, alphaIn: Int, beta: Int): Int {
        var alpha = alphaIn
        if (depth == 0) return side * evaluate(b)
        val moves = ChessRules.allLegalMoves(b, side)
        if (moves.isEmpty()) return -99000 - depth      // 将死/困毙
        val sorted = moves.sortedByDescending { abs(b[it % 90]) }
        var best = -INF
        for (mv in sorted) {
            val f = mv / 90
            val t = mv % 90
            val p = b[f]
            val cap = b[t]
            b[t] = p; b[f] = 0
            val s = -negamax(b, -side, depth - 1, -beta, -alpha)
            b[f] = p; b[t] = cap
            if (s > best) best = s
            if (s > alpha) alpha = s
            if (alpha >= beta) break
        }
        return best
    }
}

// ==================== 对局状态 ====================

class ChessGame(selfName: String, selfAvatar: String, selfScore: Int) {

    val players = listOf(
        DdPlayer(selfName, selfAvatar, true, 8, selfScore),
        DdPlayer(GamePeople.randomName(), GamePeople.randomAvatar(), false, 3 + Random.nextInt(30), 60 + Random.nextInt(900))
    )

    var board: IntArray = ChessRules.initial()
        private set
    var turn by mutableStateOf(0)            // 0 红方（玩家） 1 黑方（AI）
    var winner by mutableStateOf(-1)         // -1 进行中 / 0 红胜 / 1 黑胜
    var lastFrom by mutableStateOf(-1)
    var lastTo by mutableStateOf(-1)
    var ply by mutableStateOf(0)
    val log = mutableStateListOf<String>()

    private var legal = ChessRules.allLegalMoves(board, ChessRules.RED)

    init {
        addLog("—— 对局开始，你执红先行 ——")
    }

    private fun addLog(text: String) {
        log.add(text)
        while (log.size > 60) log.removeAt(0)
    }

    /** from 点（当前行动方）可走的目标 */
    fun legalTargets(from: Int): List<Int> =
        legal.filter { it / 90 == from }.map { it % 90 }

    fun canSelect(from: Int): Boolean =
        winner == -1 && board[from] != 0 && ((board[from] > 0) == (turn == 0))

    /** 走棋（含合法性校验） */
    fun play(from: Int, to: Int): Boolean {
        if (winner != -1) return false
        if ((board[from] > 0) != (turn == 0) || board[from] == 0) return false
        val mv = from * 90 + to
        if (mv !in legal) return false

        val mover = turn
        val p = board[from]
        val captured = board[to]
        board[to] = p
        board[from] = 0
        lastFrom = from; lastTo = to
        ply++
        turn = 1 - turn
        legal = ChessRules.allLegalMoves(board, turn)

        val sideName = if (mover == 0) "红" else "黑"
        val ch = pieceChar(p)
        val mvText = "$ch ${'a' + from % 9}${9 - from / 9}→${'a' + to % 9}${9 - to / 9}"
        addLog("$sideName ${players[mover].name}：$mvText" + if (captured != 0) "（吃 ${pieceChar(captured)}）" else "")

        if (legal.isEmpty()) {
            winner = mover
            addLog(if (mover == 0) "对方无路可走，你赢了 🎉" else "你无路可走，AI 获胜")
        } else if (ChessRules.inCheck(board, turn)) {
            addLog("将军！")
            QQSounds.ding()
        }
        QQSounds.card()
        return true
    }

    fun reset() {
        board = ChessRules.initial()
        turn = 0; winner = -1; lastFrom = -1; lastTo = -1; ply = 0
        legal = ChessRules.allLegalMoves(board, ChessRules.RED)
        log.clear()
        addLog("—— 新对局开始，你执红先行 ——")
    }

    companion object {
        private val CH_RED = mapOf(1 to "帅", 2 to "仕", 3 to "相", 4 to "马", 5 to "车", 6 to "炮", 7 to "兵")
        private val CH_BLACK = mapOf(1 to "将", 2 to "士", 3 to "象", 4 to "马", 5 to "车", 6 to "炮", 7 to "卒")

        fun pieceChar(p: Int): String =
            if (p > 0) CH_RED[abs(p)] ?: "?" else CH_BLACK[abs(p)] ?: "?"
    }
}

// ==================== 牌桌 UI ====================

@Composable
fun QQChessTableScreen(onExit: () -> Unit) {
    val acc = QQSession.account
    val selfName = acc?.nickname ?: "我"
    val selfScore = remember { QQGameScores.load()["象棋·$selfName"] ?: 0 }
    val game = remember { ChessGame(selfName, acc?.avatar ?: "emoji:🐧", selfScore) }
    var selected by mutableStateOf(-1)

    val cell = 33.dp
    val pad = 20.dp
    val boardW = cell * 8f + pad * 2f
    val boardH = cell * 9f + pad * 2f
    val textMeasurer = rememberTextMeasurer()
    val redStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFFC3272B))
    val blackStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color(0xFF232323))

    // ---- AI 行动（后台线程搜索，避免卡界面） ----
    LaunchedEffect(game.turn, game.winner, game.ply) {
        if (game.winner == -1 && game.turn == 1) {
            delay(Random.nextLong(500, 1000))
            val mv = withContext(Dispatchers.Default) { ChessAi.best(game.board, ChessRules.BLACK) }
            if (mv != null && game.winner == -1 && game.turn == 1) {
                game.play(mv / 90, mv % 90)
            }
        }
    }

    // ---- 结算 ----
    LaunchedEffect(game.winner) {
        when (game.winner) {
            0 -> {
                game.players[0].score += 5
                game.players[1].score -= 5
                QQSounds.win()
                runCatching { QQGameScores.add("象棋·$selfName", 5) }
            }
            1 -> {
                game.players[0].score -= 5
                game.players[1].score += 5
                QQSounds.lose()
                runCatching { QQGameScores.add("象棋·$selfName", -5) }
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF2C5A8F), Color(0xFF1B3A61)))),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .width(boardW)
                    .height(boardH)
                    .pointerInput(Unit) {
                        detectTapGestures { pos ->
                            if (game.winner != -1 || game.turn != 0) return@detectTapGestures
                            val padPx = pad.toPx()
                            val sx = (size.width - 2 * padPx) / 8f
                            val sy = (size.height - 2 * padPx) / 9f
                            val c = ((pos.x - padPx) / sx).roundToInt()
                            val r = ((pos.y - padPx) / sy).roundToInt()
                            if (r !in 0..9 || c !in 0..8) { selected = -1; return@detectTapGestures }
                            // 容差：点得偏离交叉点太远不响应
                            val dx = abs(pos.x - (padPx + c * sx))
                            val dy = abs(pos.y - (padPx + r * sy))
                            if (dx > sx * 0.48f || dy > sy * 0.48f) return@detectTapGestures
                            val idx = r * 9 + c
                            when {
                                selected >= 0 && game.legalTargets(selected).contains(idx) -> {
                                    if (game.play(selected, idx)) selected = -1
                                }
                                game.canSelect(idx) -> { selected = idx; QQSounds.click() }
                                else -> selected = -1
                            }
                        }
                    }
            ) {
                val padPx = pad.toPx()
                val sx = (size.width - 2 * padPx) / 8f
                val sy = (size.height - 2 * padPx) / 9f
                fun px(r: Int, c: Int) = Offset(padPx + c * sx, padPx + r * sy)

                drawRect(Color(0xFFE8C684))
                // 外框
                drawRect(
                    Color(0xFF5C4423), topLeft = Offset(padPx - 6f, padPx - 6f),
                    size = androidx.compose.ui.geometry.Size(size.width - 2 * padPx + 12f, size.height - 2 * padPx + 12f),
                    style = Stroke(width = 2.5f)
                )
                // 横线 10 条
                for (r in 0..9) drawLine(Color(0xFF5C4423), px(r, 0), px(r, 8), 1.6f)
                // 竖线：中间列在楚河汉界断开
                for (c in 0..8) {
                    if (c == 0 || c == 8) {
                        drawLine(Color(0xFF5C4423), px(0, c), px(9, c), 1.6f)
                    } else {
                        drawLine(Color(0xFF5C4423), px(0, c), px(4, c), 1.6f)
                        drawLine(Color(0xFF5C4423), px(5, c), px(9, c), 1.6f)
                    }
                }
                // 九宫斜线
                drawLine(Color(0xFF5C4423), px(0, 3), px(2, 5), 1.2f)
                drawLine(Color(0xFF5C4423), px(0, 5), px(2, 3), 1.2f)
                drawLine(Color(0xFF5C4423), px(7, 3), px(9, 5), 1.2f)
                drawLine(Color(0xFF5C4423), px(7, 5), px(9, 3), 1.2f)

                // 选中 / 目标 / 上一手标记
                if (selected >= 0) {
                    val (sr, sc) = selected / 9 to selected % 9
                    drawCircle(Color(0xFFFFB400), radius = sx * 0.52f, center = px(sr, sc), style = Stroke(width = 3f))
                    game.legalTargets(selected).forEach { t ->
                        val tr = t / 9
                        val tc = t % 9
                        val capture = game.board[t] != 0
                        drawCircle(
                            if (capture) Color(0xFFE53935) else Color(0xFF66BB6A),
                            radius = sx * (if (capture) 0.48f else 0.14f),
                            center = px(tr, tc),
                            style = if (capture) Stroke(width = 3f) else Fill
                        )
                    }
                }
                if (game.lastFrom >= 0) {
                    drawCircle(
                        Color(0x88E53935), radius = sx * 0.12f, center = px(game.lastFrom / 9, game.lastFrom % 9)
                    )
                    drawCircle(
                        Color(0x88E53935), radius = sx * 0.12f, center = px(game.lastTo / 9, game.lastTo % 9)
                    )
                }

                // 棋子
                for (i in 0..89) {
                    val p = game.board[i]
                    if (p == 0) continue
                    val center = px(i / 9, i % 9)
                    drawCircle(Color(0xFFF7E9C6), radius = sx * 0.46f, center)
                    drawCircle(
                        if (selected == i) Color(0xFFFFB400) else Color(0xFF8A6B3D),
                        radius = sx * 0.46f, center, style = Stroke(width = if (selected == i) 3f else 2f)
                    )
                    val ch = ChessGame.pieceChar(p)
                    drawText(
                        textMeasurer = textMeasurer,
                        text = ch,
                        topLeft = Offset(center.x - sx * 0.36f, center.y - sy * 0.38f),
                        style = if (p > 0) redStyle else blackStyle
                    )
                }
            }

            // 楚河汉界
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = pad + cell * 4.5f - 10.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 34.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("楚  河", color = Color(0x995C4423), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("汉  界", color = Color(0x995C4423), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            // 结算浮层
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
                            if (myWin) "🎉 胜 利 🎉" else "😅 失 败",
                            color = if (myWin) Color(0xFFFFE95C) else Color(0xFFBFE4FB),
                            fontSize = 26.sp, fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (myWin) "黑方被将死，红方胜" else "红方被将死，黑方胜",
                            color = Color.White, fontSize = 12.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row {
                            QqBeigeButton("再来一局", { QQSounds.click(); game.reset() }, height = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            QqBeigeButton("退出牌桌", onExit, height = 32.dp)
                        }
                    }
                }
            }
        }

        ChessSidePanel(game, selfName, onExit)
    }
}

@Composable
private fun ChessSidePanel(game: ChessGame, selfName: String, onExit: () -> Unit) {
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
            Text("♟️ 中国象棋", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "退出", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clickable { QQSounds.click(); onExit() }
            )
        }

        QqSectionBar(title = "对局双方", leading = "👥")
        game.players.forEachIndexed { i, p ->
            val myTurn = game.winner == -1 && game.turn == i
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
                        "执${if (i == 0) "红 🔴" else "黑 ⚫"} · ${ddTitle(p.level)}·Lv${p.level}",
                        fontSize = 9.sp, color = QQ2008.TextSub
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${p.score}", fontSize = 12.sp, color = Color(0xFFC3272B), fontWeight = FontWeight.Bold)
            }
        }

        QqSectionBar(title = "对局状态", leading = "📣")
        val status = when {
            game.winner == 0 -> "🎉 你赢了（黑方被将死）"
            game.winner == 1 -> "AI 获胜（红方被将死）"
            game.turn == 0 -> "轮到你（红）走棋"
            else -> "AI 思考中…"
        }
        Text(
            status,
            color = if (game.winner == -1) QQ2008.TextMain else Color(0xFFC3272B),
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
