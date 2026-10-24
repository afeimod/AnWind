package com.anwind.apps.qq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
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

// ==================== 牌桌 UI（截图5：QQ游戏 - 五子棋 2.01 复刻） ====================

/** 每方倒计时（截图5：小晖 06:15 / 潇潇春雨 07:50，初始各 10 分钟） */
private fun fmtClock(sec: Int): String =
    String.format("%02d:%02d", sec / 60, sec % 60)

@Composable
fun QQGomokuTableScreen(onExit: () -> Unit) {
    val acc = QQSession.account
    val selfName = acc?.nickname ?: "我"
    val selfScore = remember { QQGameScores.load()["五子棋·$selfName"] ?: 0 }
    val game = remember { GomokuGame(selfName, acc?.avatar ?: "emoji:🐧", selfScore) }

    // ---- 双方倒计时（截图5：各自 10 分钟，行动方递减）----
    var remainSec by remember { mutableStateOf(listOf(600, 600)) }
    LaunchedEffect(game.turn, game.winner, game.moveCount) {
        if (game.winner == 0 && game.turn in 1..2) {
            delay(1000)
            remainSec = remainSec.mapIndexed { i, v ->
                if (game.turn == i + 1) (v - 1).coerceAtLeast(0) else v
            }
        }
    }

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

    // v4 竖屏自适应：窄窗口（<560dp）紧凑纵向布局，棋盘完整可见
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(QqGame.GmBgTop, QqGame.GmBgBottom)))) {
        val compact = maxWidth < 560.dp
        // 修复：maxWidth 只能在 BoxWithConstraints 的直接作用域内经隐式接收者访问；
        // 进入嵌套的 Box/Row/Column 等子 lambda 后，Kotlin 无法再经外层隐式接收者
        // 解析（can't be called in this context by implicit receiver），
        // 故在作用域顶层捕获为局部 val，嵌套布局统一使用 maxW。
        val maxW = maxWidth
        if (compact) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 顶部：双玩家卡横排
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        GmPlayerCard(
                            game = game, idx = 0, black = true,
                            remain = fmtClock(remainSec[0]),
                            active = game.turn == 1 && game.winner == 0
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        GmPlayerCard(
                            game = game, idx = 1, black = false,
                            remain = fmtClock(remainSec[1]),
                            active = game.turn == 2 && game.winner == 0
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    QqStoneButton("重新开局", { QQSounds.click(); game.reset() })
                    Spacer(Modifier.width(8.dp))
                    QqStoneButton("退出牌桌", { QQSounds.click(); onExit() })
                }
                // 棋盘自适应：15 路 + 边距 ≈ 16.1 倍格宽
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    GmBoardArea(game, cell = ((maxW - 16.dp) / 16.1f).coerceAtMost(26.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    QqStoneButton("悔 棋", { QQSounds.click() })
                    Spacer(Modifier.width(8.dp))
                    QqStoneButton("和 棋", { QQSounds.click() })
                    Spacer(Modifier.width(8.dp))
                    QqStoneButton("认 输", { QQSounds.click() })
                }
                // 聊天区（紧凑高度）
                val chatScroll = rememberScrollState()
                LaunchedEffect(game.log.size) {
                    runCatching { chatScroll.animateScrollTo(chatScroll.maxValue) }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .height(64.dp)
                        .background(Color(0xCC0A3358), RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF2E86D5), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .verticalScroll(chatScroll)
                ) {
                    Text(
                        "系统消息：欢迎进入QQ游戏 - 五子棋2.01 Beta3 Build7",
                        fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFF9AD1F0)
                    )
                    game.log.forEach { line ->
                        Text(line, fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFFD8F0FA))
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                // ===== 左侧玩家面板（截图5：小晖 / 潇潇春雨 双卡 + 倒计时 + 局时）=====
                Column(
                    Modifier
                        .width(148.dp)
                        .fillMaxSize()
                        .background(Color(0xFF12467A))
                        .border(1.dp, Color(0xFF2E86D5))
                        .padding(4.dp)
                ) {
                    GmPlayerCard(
                        game = game, idx = 0, black = true,
                        remain = fmtClock(remainSec[0]),
                        active = game.turn == 1 && game.winner == 0
                    )
                    Spacer(Modifier.height(6.dp))
                    GmPlayerCard(
                        game = game, idx = 1, black = false,
                        remain = fmtClock(remainSec[1]),
                        active = game.turn == 2 && game.winner == 0
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "局时 10:00 / 方",
                        fontSize = 9.sp,
                        color = Color(0xFFBDE3FF),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    QqStoneButton("重新开局", { QQSounds.click(); game.reset() }, modifier = Modifier.fillMaxWidth())
                }

                // ===== 中央：棋盘 + 底部按钮 + 聊天（截图5）=====
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GmBoardArea(game, cell = 24.dp)
                    }
                    Spacer(Modifier.height(8.dp))

                    // ---- 底部按钮（截图5：悔棋 和棋 开始 认输）----
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QqStoneButton("悔 棋", { QQSounds.click() })
                        QqStoneButton("和 棋", { QQSounds.click() })
                        QqStoneButton("开 始", { QQSounds.click(); game.reset() })
                        QqStoneButton("认 输", { QQSounds.click() })
                    }
                    Spacer(Modifier.height(6.dp))

                    // ---- 聊天区（截图5：玩家进出房间的系统消息）----
                    val chatScroll = rememberScrollState()
                    LaunchedEffect(game.log.size) {
                        runCatching { chatScroll.animateScrollTo(chatScroll.maxValue) }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(76.dp)
                            .background(Color(0xCC0A3358), RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF2E86D5), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .verticalScroll(chatScroll)
                    ) {
                        Text(
                            "系统消息：欢迎进入QQ游戏 - 五子棋2.01 Beta3 Build7",
                            fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFF9AD1F0)
                        )
                        Text(
                            "系统消息：${game.players[0].name}进入了房间。",
                            fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFF9AD1F0)
                        )
                        Text(
                            "系统消息：${game.players[1].name}进入了房间。",
                            fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFF9AD1F0)
                        )
                        game.log.forEach { line ->
                            Text(line, fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFFD8F0FA))
                        }
                    }
                }

                // ===== 右侧信息面板（截图5；竖屏隐藏）=====
                GmSidePanel(game, selfName, onExit)
            }
        }
    }
}

/**
 * 棋盘区域（v4 抽取）：木纹棋盘 + 立体棋子 + 点选交互。
 * 横屏/竖屏共用；cell 由调用方按可用宽度自适应传入。
 */
@Composable
private fun GmBoardArea(game: GomokuGame, cell: Dp) {
    val pad = cell * 0.55f
    val boardSize = cell * 15f + pad * 2f
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
                    // 木底（截图5：浅暖黄 #E8C98E + 横向木纹）
                    drawRoundRect(
                        Brush.verticalGradient(listOf(QqGame.GmBoard, QqGame.GmBoardDark)),
                        cornerRadius = CornerRadius(5f, 5f)
                    )
                    for (i in 1..14) {
                        val y = size.height * i / 15f
                        drawLine(Color(0x1E8A5A20), Offset(0f, y), Offset(size.width, y), 1.5f)
                    }
                    drawRoundRect(
                        Color(0xFF8A5A20),
                        cornerRadius = CornerRadius(5f, 5f),
                        style = Stroke(width = 3f)
                    )
                    // 15x15 网格（深褐 #4A3520）
                    for (i in 0..14) {
                        val p = padPx + i * step
                        drawLine(QqGame.GmLine, Offset(padPx, p), Offset(size.width - padPx, p), 1.3f)
                        drawLine(QqGame.GmLine, Offset(p, padPx), Offset(p, size.height - padPx), 1.3f)
                    }
                    // 星位（天元 + 四星）
                    listOf(3 to 3, 3 to 11, 11 to 3, 11 to 11, 7 to 7).forEach { (r, c) ->
                        drawCircle(
                            QqGame.GmLine, radius = step * 0.1f,
                            center = Offset(padPx + c * step, padPx + r * step)
                        )
                    }
                    // 棋子（立体感：径向渐变，左上高光）
                    for (idx in 0..224) {
                        val v = game.board[idx]
                        if (v == 0) continue
                        val center = Offset(padPx + (idx % 15) * step, padPx + (idx / 15) * step)
                        val r = step * 0.44f
                        // 投影
                        drawCircle(Color(0x33000000), radius = r, center = center + Offset(1.5f, 2f))
                        if (v == 1) {
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color(0xFF5A5A5A), Color(0xFF151515)),
                                    center = center - Offset(r * 0.35f, r * 0.35f),
                                    radius = r * 1.5f
                                ),
                                radius = r, center = center
                            )
                        } else {
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color(0xFFFFFFFF), Color(0xFFB9BEC5)),
                                    center = center - Offset(r * 0.35f, r * 0.35f),
                                    radius = r * 1.5f
                                ),
                                radius = r, center = center
                            )
                            drawCircle(Color(0xFF8A8F96), radius = r, center = center, style = Stroke(1f))
                        }
                    }
                    // 最后一手红点
                    if (game.lastMove >= 0 && game.winner == 0) {
                        drawCircle(
                            Color(0xFFE53935), radius = step * 0.13f,
                            center = Offset(padPx + (game.lastMove % 15) * step, padPx + (game.lastMove / 15) * step)
                        )
                    }
                    // 胜利连线
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


/** 左侧玩家卡（截图5：头像 + 昵称 + 大字倒计时 + 局时） */
@Composable
private fun GmPlayerCard(game: GomokuGame, idx: Int, black: Boolean, remain: String, active: Boolean) {
    val p = game.players[idx]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) Color(0xFF1E6CB4) else Color(0xFF0A3358))
            .border(1.dp, if (active) Color(0xFFFFE95C) else Color(0xFF2E86D5))
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.White, RoundedCornerShape(3.dp))
                    .border(1.dp, Color(0xFF9FC2DC), RoundedCornerShape(3.dp))
                    .padding(2.dp)
            ) {
                QqPenguinAvatar(size = 32.dp, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    p.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 棋子颜色标记（截图5：黑白执子）
                Canvas(Modifier.size(width = 14.dp, height = 9.dp)) {
                    drawCircle(
                        if (black) Color(0xFF151515) else Color.White,
                        radius = size.minDimension / 2f,
                        center = Offset(size.height / 2f, size.height / 2f)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // 倒计时（截图5：大字 06:15）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color(0xFFDDE9F4))
                .border(1.dp, Color(0xFF6A87A0)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                remain,
                color = if (active) Color(0xFFC3272B) else Color(0xFF2A4A66),
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        Text("局时 10:00", fontSize = 9.sp, color = Color(0xFFBDE3FF))
    }
}

@Composable
private fun GmSidePanel(game: GomokuGame, selfName: String, onExit: () -> Unit) {
    val p = game.players[0]
    Column(
        modifier = Modifier
            .width(180.dp)
            .fillMaxSize()
            .background(Color(0xFF12467A))
            .border(1.dp, Color(0xFF2E86D5))
            .padding(6.dp)
    ) {
        // ---- 我的资料卡（截图5：昵称/积分/级别/总盘数/胜负平）----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFEAF6FE))
                .border(1.dp, Color(0xFF9FC2DC), RoundedCornerShape(4.dp))
                .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QqPenguinAvatar(size = 40.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("昵称：${p.name}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = QQ2008.TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("级别：${p.level}级", fontSize = 10.sp, color = QQ2008.TextSub)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("积分：${p.score}", fontSize = 11.sp, color = Color(0xFFC3272B), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // 战绩（截图5：总盘数29 胜4 负25 平0）
            val totalGames = 25 + (abs(p.score) / 2)
            val wins = (totalGames * 0.16f).toInt().coerceAtLeast(2)
            val draws = (totalGames * 0.05f).toInt()
            Column {
                Text("总盘数：$totalGames", fontSize = 10.sp, color = QQ2008.TextMain)
                Row {
                    Text("胜：$wins", fontSize = 10.sp, color = Color(0xFF2E7D32))
                    Spacer(Modifier.width(8.dp))
                    Text("负：${(totalGames - wins - draws).coerceAtLeast(0)}", fontSize = 10.sp, color = Color(0xFFC3272B))
                    Spacer(Modifier.width(8.dp))
                    Text("平：$draws", fontSize = 10.sp, color = QQ2008.TextSub)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---- 按钮组（截图5：购买道具 / 兑换游戏币）----
        QqBlueDiamondButton(
            "购买道具",
            diamond = true,
            onClick = { QQSounds.click() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        QqBlueDiamondButton(
            "兑换游戏币",
            diamond = false,
            onClick = { QQSounds.click() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        // ---- 对局状态 ----
        Text("— 对局状态 —", fontSize = 10.sp, color = Color(0xFFBDE3FF), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        val status = when {
            game.winner == 1 -> "🎉 五连！你赢了"
            game.winner == 2 -> "AI 五连，再接再厉"
            game.winner == 3 -> "棋盘已满，和棋"
            game.turn == 1 -> "轮到你（黑）落子"
            else -> "${game.players[1].name} 思考中…"
        }
        Text(
            status,
            color = if (game.winner == 0) Color.White else Color(0xFFFFE95C),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        // ---- 对手信息 ----
        Text("— 对手 —", fontSize = 10.sp, color = Color(0xFFBDE3FF), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x33000000), RoundedCornerShape(4.dp))
                .padding(6.dp)
        ) {
            QqPenguinAvatar(size = 28.dp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(game.players[1].name, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("执白 · ${game.players[1].score}分", fontSize = 9.sp, color = Color(0xFFBDE3FF))
            }
        }
        Spacer(Modifier.weight(1f))

        // ---- 退出 ----
        QqWhiteButton("退出牌桌", { QQSounds.click(); onExit() }, modifier = Modifier.fillMaxWidth())
    }
}
