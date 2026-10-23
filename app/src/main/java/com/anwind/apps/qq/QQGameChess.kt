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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 *  - AI：迭代加深 alpha-beta（2~3 层 + 1.2s 时间预算）+ 看门狗兜底强制落子，永不卡死；
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
    private val DIRS4 = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
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
        for (d in DIRS4) {
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
    private var deadline = 0L

    private class SearchTimeout : Exception()

    /**
     * 返回 side 方的最佳走法（from*90+to），无棋可走返回 null。
     * 迭代加深（2→maxDepth 层）+ 时间预算：慢设备上超时自动退回浅层结果，
     * 保证无论如何都能在 budgetMs 毫秒内返回，不会“一直思考”。
     */
    fun best(b: IntArray, side: Int, maxDepth: Int = 4, budgetMs: Long = 2000L): Int? {
        val moves = ChessRules.allLegalMoves(b, side)
        if (moves.isEmpty()) return null
        deadline = System.currentTimeMillis() + budgetMs
        // 兕底：吃子优先的第一步（即使第一层就超时也有解）
        var bestMv: Int = moves.sortedByDescending { abs(b[it % 90]) }.first()
        try {
            for (d in 2..maxDepth) {
                var alpha = -INF
                var layerBest = -1
                val sorted = moves.sortedByDescending { abs(b[it % 90]) }
                for (mv in sorted) {
                    val f = mv / 90
                    val t = mv % 90
                    val p = b[f]
                    val cap = b[t]
                    b[t] = p; b[f] = 0
                    // try/finally：超时异常穿层时必须还原棋盘，否则 b 被中间状态污染
                    val s = try {
                        -negamax(b, -side, d - 1, -INF, -alpha)
                    } finally {
                        b[f] = p; b[t] = cap
                    }
                    if (s > alpha) { alpha = s; layerBest = mv }
                }
                if (layerBest >= 0) {
                    bestMv = layerBest
                    if (alpha > 90000) break          // 已找到将死，无需更深
                }
            }
        } catch (e: SearchTimeout) {
            // 超时：用上一个完成深度的走法
        }
        return bestMv
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
        if (System.currentTimeMillis() > deadline) throw SearchTimeout()
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
            val s = try {
                -negamax(b, -side, depth - 1, -beta, -alpha)
            } finally {
                b[f] = p; b[t] = cap
            }
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
    /** 棋盘内容版本号：每次落子/重置递增。Canvas 读取它 → 任何棋盘变化必然触发重绘 */
    var boardVersion by mutableStateOf(0)
        private set
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

    /** 走棋（含合法性校验）。legal 缓存与棋盘不一致时自愈重算，绝不误拒合法看步 */
    fun play(from: Int, to: Int): Boolean {
        if (winner != -1) return false
        if ((board[from] > 0) != (turn == 0) || board[from] == 0) return false
        val mv = from * 90 + to
        if (mv !in legal) {
            // 自愈：缓存过期时按当前棋盘重算一遍再判（防任何不可预见的状怇不同步）
            legal = ChessRules.allLegalMoves(board, turn)
            if (mv !in legal) return false
        }

        val mover = turn
        val p = board[from]
        val captured = board[to]
        board[to] = p
        board[from] = 0
        lastFrom = from; lastTo = to
        ply++
        boardVersion++
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
        boardVersion++
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

// ==================== 牌桌 UI（截图4：QQ中国象棋 复刻） ====================

/** 象棋级别称号（截图4：县丞 / 都尉…官职体系） */
private fun chessTitle(level: Int): String = when {
    level < 5 -> "县丞"
    level < 10 -> "驿丞"
    level < 15 -> "主簿"
    level < 20 -> "县尉"
    level < 25 -> "都尉"
    level < 30 -> "校尉"
    else -> "将军"
}

/** LCD 计时器（截图4：浅黄绿底电子表） */
@Composable
private fun ChessLcdClock(text: String, red: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 24.dp)
            .background(Color(0xFFF0F0D0))
            .border(1.dp, Color(0xFF8A8A6A)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (red) Color(0xFFCC2222) else Color(0xFF333333),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun QQChessTableScreen(onExit: () -> Unit) {
    val acc = QQSession.account
    val selfName = acc?.nickname ?: "我"
    val selfScore = remember { QQGameScores.load()["象棋·$selfName"] ?: 0 }
    val game = remember { ChessGame(selfName, acc?.avatar ?: "emoji:🐧", selfScore) }
    var selected by mutableStateOf(-1)

    // ---- 局时（截图4：双方各自局时 LCD 00:03 / 00:00）----
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(game.winner) {
        if (game.winner == -1) {
            while (true) {
                delay(1000)
                elapsed++
            }
        }
    }
    fun fmt(sec: Int) = String.format("%02d:%02d", sec / 60, sec % 60)

    val cell = 33.dp
    val pad = 20.dp
    val boardW = cell * 8f + pad * 2f
    val boardH = cell * 9f + pad * 2f

    // 棋子位图素材（assets/qgame/chess/，红字/黑字圆棋子）
    val ctx = LocalContext.current
    val pieceBmps = remember {
        (1..7).associate { t ->
            t to (QQGameAssets.load(ctx, "qgame/chess/cp_r$t.png") to QQGameAssets.load(ctx, "qgame/chess/cp_b$t.png"))
        }
    }

    // ---- AI 行动（与五子棋同款同步驱动） ----
    // v3 根治重构：不再使用 withContext(Dispatchers.Default)+runCatching（该组合在取消
    // 场景会吞掉 CancellationException 令协程僵死，是"AI 不走棋"的元凶）；改为与
    // 五子棋完全一致的主线程同步搜索——引擎自带硬性时间预算（800ms 超时自动降层），
    // 实测毫秒级返回，既不卡 UI 也绝不停摆。取消防护性 runCatching，取消异常正常上抛。
    LaunchedEffect(game.turn, game.winner, game.ply, game.boardVersion) {
        if (game.winner != -1 || game.turn != 1) return@LaunchedEffect
        delay(Random.nextLong(400, 900))
        if (game.winner != -1 || game.turn != 1) return@LaunchedEffect
        val mv = ChessAi.best(game.board, ChessRules.BLACK, maxDepth = 3, budgetMs = 800L)
            ?: ChessRules.allLegalMoves(game.board, ChessRules.BLACK).randomOrNull()
        if (game.winner == -1 && game.turn == 1 && mv != null) {
            if (!game.play(mv / 90, mv % 90)) {
                // 引擎走法被拒（理论不可达）：随机合法着法兜底，绝不停摆
                val fallback = ChessRules.allLegalMoves(game.board, ChessRules.BLACK).randomOrNull()
                if (fallback != null) game.play(fallback / 90, fallback % 90)
            }
        }
    }

    // ---- 看门狗：任何原因导致 AI 回合停留超 8 秒，直接强制落子 ----
    LaunchedEffect(game.turn, game.winner, game.ply, game.boardVersion) {
        if (game.winner != -1 || game.turn != 1) return@LaunchedEffect
        delay(8000)
        if (game.winner == -1 && game.turn == 1) {
            val mv = ChessRules.allLegalMoves(game.board, ChessRules.BLACK).randomOrNull()
            if (mv != null) game.play(mv / 90, mv % 90)
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

    Row(Modifier.fillMaxSize().background(QqGame.ChessBg)) {
        // ===== 左侧玩家信息面板（截图4：上下两个玩家卡 + 局时 LCD）=====
        Column(
            Modifier
                .width(150.dp)
                .fillMaxSize()
                .background(QqGame.ChessPanel)
                .border(1.dp, Color(0xFF8A8A72))
                .padding(4.dp)
        ) {
            ChessPlayerCard(
                game = game, idx = 1, red = false,
                clock = fmt(if (game.turn == 1 && game.winner == -1) elapsed else 0)
            )
            Spacer(Modifier.height(6.dp))
            ChessPlayerCard(
                game = game, idx = 0, red = true,
                clock = fmt(if (game.turn == 0 && game.winner == -1) elapsed else 0)
            )
            Spacer(Modifier.weight(1f))
            // 表情按钮（截图4 面板底部）
            QqStoneButton("表 情", { QQSounds.click() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            QqStoneButton("重新开局", { QQSounds.click(); game.reset() }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                "QQ游戏 - 中国象棋 角色版 在线",
                fontSize = 8.sp,
                color = Color(0xFF8A8A72),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ===== 中央棋盘（截图4：米黄木纹 + 楚河漢界）=====
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
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
                        drawChessBoard()
                        // 读取版本号：棋盘任何变化（含 AI 落子）必然触发本次重绘
                        val boardVer = game.boardVersion
                        val padPx = pad.toPx()
                        val sx = (size.width - 2 * padPx) / 8f
                        val sy = (size.height - 2 * padPx) / 9f
                        fun px(r: Int, c: Int) = Offset(padPx + c * sx, padPx + r * sy)

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
                            drawCircle(Color(0x88E53935), radius = sx * 0.12f, center = px(game.lastFrom / 9, game.lastFrom % 9))
                            drawCircle(Color(0x88E53935), radius = sx * 0.12f, center = px(game.lastTo / 9, game.lastTo % 9))
                        }

                        // 棋子（楷书圆棋子位图，红方/黑方各 7 种）
                        val pieceSize = sx * 0.94f
                        for (i in 0..89) {
                            val p = game.board[i]   // boardVersion 已读，棋盘变化必然重绘
                            if (p == 0) continue
                            val bmps = pieceBmps[abs(p)] ?: continue
                            val center = px(i / 9, i % 9)
                            drawAsset(
                                if (p > 0) bmps.first else bmps.second,
                                center.x - pieceSize / 2f,
                                center.y - pieceSize / 2f,
                                pieceSize,
                                pieceSize
                            )
                        }
                    }
                    // 楚河漢界（截图4：左漢界 右楚河，书法体）
                    Text(
                        "漢 界",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3B2A18),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = pad + cell * 1.1f)
                    )
                    Text(
                        "楚 河",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3B2A18),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = pad + cell * 1.1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                // ---- 底部按钮（截图4：悔棋 求和 道具 认输 石质浮雕）----
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QqStoneButton("悔 棋", { QQSounds.click() })
                    QqStoneButton("求 和", { QQSounds.click() })
                    QqStoneButton("道 具", { QQSounds.click() })
                    QqStoneButton("认 输", { QQSounds.click() })
                }
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
                            .background(Color(0xFFF5F0E0).copy(alpha = 0.97f), RoundedCornerShape(10.dp))
                            .border(2.dp, Color(0xFF8A6A2A), RoundedCornerShape(10.dp))
                            .padding(horizontal = 26.dp, vertical = 18.dp)
                    ) {
                        val myWin = game.winner == 0
                        Text(
                            if (myWin) "🎉 胜 利 🎉" else "😅 失 败",
                            color = if (myWin) Color(0xFFC3272B) else Color(0xFF444444),
                            fontSize = 26.sp, fontWeight = FontWeight.Black
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (myWin) "黑方被将死，红方胜" else "红方被将死，黑方胜",
                            color = Color(0xFF5A4A2A), fontSize = 12.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Row {
                            QqStoneButton("再来一局", { QQSounds.click(); game.reset() }, height = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            QqStoneButton("退出牌桌", onExit, height = 32.dp)
                        }
                    }
                }
            }
        }

        ChessSidePanel(game, selfName, onExit)
    }
}

/** 米黄木纹棋盘绘制（截图4：#E8D4A0 底 + 深褐线 + 九宫 + 炮兵位曲尺） */
private fun DrawScope.drawChessBoard() {
    val w = size.width
    val h = size.height
    val padPx = 20.dp.toPx()
    val sx = (w - 2 * padPx) / 8f
    val sy = (h - 2 * padPx) / 9f

    // 木底（米黄渐变 + 横向木纹）
    drawRoundRect(
        Brush.verticalGradient(listOf(QqGame.ChessBoard, QqGame.ChessBoardDark)),
        cornerRadius = CornerRadius(6f, 6f)
    )
    // 木纹（横线若干，半透明深色）
    for (i in 1..9) {
        val y = h * i / 10f
        drawLine(Color(0x14A08040), Offset(0f, y), Offset(w, y), 1.5f)
    }
    // 外框（深褐厚边）
    drawRoundRect(Color(0xFF6B4A26), cornerRadius = CornerRadius(6f, 6f), style = Stroke(width = 4f))
    drawRect(Color(0xFF4A3B2A), topLeft = Offset(padPx - 3f, padPx - 3f), size = Size(w - 2 * padPx + 6f, h - 2 * padPx + 6f), style = Stroke(2.5f))

    // 网格（10 行横线全部完整画出——楚河汉界处横线不断，只有纵线断开）
    for (r in 0..9) {
        drawLine(QqGame.ChessLine, Offset(padPx, padPx + r * sy), Offset(w - padPx, padPx + r * sy), 1.4f)
    }
    for (c in 0..8) {
        // 纵线在界河（第4~5行之间）断开
        drawLine(QqGame.ChessLine, Offset(padPx + c * sx, padPx), Offset(padPx + c * sx, padPx + 4 * sy), 1.4f)
        drawLine(QqGame.ChessLine, Offset(padPx + c * sx, padPx + 5 * sy), Offset(padPx + c * sx, h - padPx), 1.4f)
    }

    // 九宫斜线（上下）
    for (top in listOf(0f, 7f)) {
        drawLine(QqGame.ChessLine, Offset(padPx + 3 * sx, padPx + top * sy), Offset(padPx + 5 * sx, padPx + (top + 2f) * sy), 1.2f)
        drawLine(QqGame.ChessLine, Offset(padPx + 5 * sx, padPx + top * sy), Offset(padPx + 3 * sx, padPx + (top + 2f) * sy), 1.2f)
    }

    // 炮位 / 兵位曲尺标记
    val marks: List<Pair<Int, Int>> = listOf(
        1 to 2, 7 to 2,   // 黑炮
        1 to 7, 7 to 7,   // 红炮
        0 to 3, 2 to 3, 4 to 3, 6 to 3, 8 to 3,     // 黑卒
        0 to 6, 2 to 6, 4 to 6, 6 to 6, 8 to 6      // 红兵
    )
    marks.forEach { (c, r) ->
        val x = padPx + c * sx
        val y = padPx + r * sy
        val d = 4.5f
        val gap = 3f
        for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
            if (c == 0 && dx < 0) continue
            if (c == 8 && dx > 0) continue
            val x0 = x + dx * gap
            val y0 = y + dy * gap
            drawLine(QqGame.ChessLine, Offset(x0, y0), Offset(x0 + dx * d, y0), 1.2f)
            drawLine(QqGame.ChessLine, Offset(x0, y0), Offset(x0, y0 + dy * d), 1.2f)
        }
    }
}

/** 左侧玩家卡（截图4：头像 + 昵称[QQ号] + 积分/级别 + 局时 LCD） */
@Composable
private fun ChessPlayerCard(game: ChessGame, idx: Int, red: Boolean, clock: String) {
    val p = game.players[idx]
    val myTurn = game.winner == -1 && game.turn == idx
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (myTurn) Color(0xFFFFF3D6) else Color.White)
            .border(1.dp, if (myTurn) Color(0xFFC8A03C) else Color(0xFFB0AC9A))
            .padding(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 头像
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0xFFE8E8E0), RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0xFF9A9A8A), RoundedCornerShape(3.dp))
                    .padding(2.dp)
            ) {
                QqPenguinAvatar(size = 38.dp, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name,
                        color = if (red) Color(0xFFCC2222) else Color(0xFF222222),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "[${100000000 + (p.name.hashCode() and 0x7FFFFFFF) % 899999999}]",
                    color = Color(0xFF8A8A72),
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }
            // 执子标记（红帥 / 黑將）
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        Brush.radialGradient(listOf(Color(0xFFF5E6C8), Color(0xFFD4B878)))
                    )
                    .border(1.dp, Color(0xFF8B7355), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (red) "帥" else "將",
                    color = if (red) Color(0xFFCC0000) else Color(0xFF000000),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        // 积分 / 级别 / 局时（截图4：积分411 级别都尉 局时00:03）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("积分: ${p.score}", fontSize = 10.sp, color = Color(0xFF444444))
                Text("级别: ${chessTitle(p.level)}    总局数: ${30 + (p.level * 7)}", fontSize = 10.sp, color = Color(0xFF444444))
            }
            ChessLcdClock(clock, red = red)
        }
    }
}

@Composable
private fun ChessSidePanel(game: ChessGame, selfName: String, onExit: () -> Unit) {
    val logScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .width(186.dp)
            .fillMaxSize()
            .background(QqGame.PanelLightBg)
            .border(1.dp, Color(0xFF6FA6D8))
    ) {
        // ---- 顶部信息条 ----
        QqPanelHeader(
            nickname = game.players[0].name,
            scoreText = "${game.players[0].score}",
            coinText = "游戏币: 0"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFDCEFFB))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("级别: ${chessTitle(game.players[0].level)}", color = Color(0xFF2E6288), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "退出",
                color = Color(0xFF0B62C4),
                fontSize = 11.sp,
                modifier = Modifier.clickable { QQSounds.click(); onExit() }
            )
        }

        // ---- 房间玩家列表（截图4 右侧：玩家 + 积分 + 胜率）----
        QqPanelSection("房间内玩家")
        game.players.forEachIndexed { i, p ->
            val myTurn = game.winner == -1 && game.turn == i
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (myTurn) Color(0xFFBFE4FB) else Color.White)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                QqPenguinAvatar(size = 28.dp)
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        p.name + if (p.isHuman) "（你）" else "",
                        fontSize = 11.sp, color = QQ2008.TextMain,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "积分 ${p.score} · 胜率 ${55 + (i * 13) % 40}%",
                        fontSize = 9.sp, color = QQ2008.TextSub
                    )
                }
            }
        }
        // 观战玩家（截图4：房间还有 gfhgfo 等）
        listOf("观战：金木", "观战：好心情").forEach { n ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("👁", fontSize = 10.sp)
                Spacer(Modifier.width(6.dp))
                Text(n, fontSize = 10.sp, color = QQ2008.TextSub)
            }
        }

        // ---- 道具 / 商城 ----
        QqPanelSection("道具")
        QqPropsBar(title = "公共道具", propCount = 2)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QqBlueDiamondButton("蓝钻贵族", diamond = true, onClick = { QQSounds.click() }, modifier = Modifier.weight(1f))
            QqBlueDiamondButton("游戏商城", diamond = false, onClick = { QQSounds.click() }, modifier = Modifier.weight(1f))
        }

        // ---- 聊天 / 系统消息（截图4："欢迎进入QQ游戏中心"）----
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
            Text(
                "系统消息：欢迎进入QQ游戏中心 - 中国象棋角色版，祝你游戏愉快！",
                fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFF2E7D32)
            )
            game.log.forEach { line ->
                Text(line, fontSize = 10.sp, lineHeight = 14.sp, color = QQ2008.TextMain)
            }
            Spacer(Modifier.height(4.dp))
        }
        // 输入行
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
                Text("和大家打个招呼吧…", fontSize = 9.sp, color = Color(0xFF9AAFC2), modifier = Modifier.padding(horizontal = 4.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("😊", fontSize = 13.sp, modifier = Modifier.clickable { QQSounds.msgOut() })
        }
    }
}
