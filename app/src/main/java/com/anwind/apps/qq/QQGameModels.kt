package com.anwind.apps.qq

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.SortedMap

/**
 * QQ游戏大厅 —— 斗地主核心逻辑（无 Compose UI 依赖，仅状态容器用 snapshot state）。
 *
 * 规则（经典简化版）：
 *  - 54 张牌，三人各 17 张，底牌 3 张归地主；
 *  - 一轮叫分（不叫/1/2/3 分）定地主，全不叫自动重发；
 *  - 牌型：单张/对子/三张/三带一/三带对/顺子(≥5)/连对(≥3对)/飞机(可带翅膀)/
 *          四带二/四带两对/炸弹/王炸；
 *  - 炸弹、王炸出现一次倍数 ×2；春天/反春再 ×2；
 *  - 地主赢：地主 +2×底分×倍数，每农民 -1×…；农民赢：反之。
 */

// ==================== 扑克牌 ====================

/**
 * 一张牌。
 * @param rank 3~14 表示 3~A，15=2，16=小王，17=大王
 * @param suit 0♠ 1♥ 2♣ 3♦（王：0 小王 / 1 大王，仅配色用）
 */
data class DdCard(val rank: Int, val suit: Int) : Comparable<DdCard> {

    val isJoker: Boolean get() = rank >= 16

    /** 牌面文字 */
    val label: String
        get() = when (rank) {
            in 3..10 -> rank.toString()
            11 -> "J"; 12 -> "Q"; 13 -> "K"; 14 -> "A"; 15 -> "2"
            16 -> "小王"; else -> "大王"
        }

    /** 红色（♥/♦ 与大王） */
    val isRed: Boolean get() = if (isJoker) rank == 17 else suit == 1 || suit == 3

    val suitChar: String
        get() = when (suit) { 0 -> "♠"; 1 -> "♥"; 2 -> "♣"; else -> "♦" }

    override fun compareTo(other: DdCard): Int = rank.compareTo(other.rank)

    companion object {
        const val TWO = 15
        const val JOKER_S = 16
        const val JOKER_B = 17

        /** 一副 54 张 */
        fun fullDeck(): List<DdCard> {
            val deck = mutableListOf<DdCard>()
            for (r in 3..15) for (s in 0..3) deck.add(DdCard(r, s))
            deck.add(DdCard(JOKER_S, 0))
            deck.add(DdCard(JOKER_B, 1))
            return deck
        }
    }
}

/** 手牌排序：从大到小（QQ 牌桌习惯，大牌在左） */
fun sortDesc(cards: MutableList<DdCard>) {
    cards.sortWith(compareByDescending<DdCard> { it.rank }.thenBy { it.suit })
}

// ==================== 牌型 ====================

enum class DdComboType {
    SINGLE, PAIR, TRIO, TRIO_ONE, TRIO_PAIR,
    STRAIGHT, PAIR_STRAIGHT,
    PLANE, PLANE_ONE, PLANE_PAIR,
    FOUR_TWO, FOUR_TWO_PAIRS,
    BOMB, ROCKET
}

/** 一手打出的牌（type + 关键rank + 张数即可比较大小） */
data class DdPlay(val type: DdComboType, val cards: List<DdCard>, val keyRank: Int) {
    val size: Int get() = cards.size

    /** 桌面/日志里的人话描述 */
    fun describe(): String {
        val text = cards.sortedByDescending { it.rank }.joinToString("") {
            if (it.isJoker) it.label else "${it.label}${it.suitChar}"
        }
        val name = when (type) {
            DdComboType.SINGLE -> ""; DdComboType.PAIR -> "对"
            DdComboType.TRIO -> "三张"; DdComboType.TRIO_ONE -> "三带一"
            DdComboType.TRIO_PAIR -> "三带对"; DdComboType.STRAIGHT -> "顺子"
            DdComboType.PAIR_STRAIGHT -> "连对"; DdComboType.PLANE -> "飞机"
            DdComboType.PLANE_ONE -> "飞机带单"; DdComboType.PLANE_PAIR -> "飞机带对"
            DdComboType.FOUR_TWO -> "四带二"; DdComboType.FOUR_TWO_PAIRS -> "四带两对"
            DdComboType.BOMB -> "炸弹！"; DdComboType.ROCKET -> "王炸！！"
        }
        return if (name.isEmpty()) text else "$name $text"
    }
}

/**
 * 牌型判定与比较（纯函数）。
 */
object DdRules {

    /** 判定一组牌的牌型；非法返回 null */
    fun classify(cards: List<DdCard>): DdPlay? {
        if (cards.isEmpty()) return null
        val n = cards.size
        // rank -> 该 rank 的牌列表
        val byRank = sortedMapOf<Int, MutableList<DdCard>>()
        cards.forEach { byRank.getOrPut(it.rank) { mutableListOf() }.add(it) }
        val counts = byRank.values.map { it.size }

        // 王炸
        if (n == 2 && byRank.containsKey(DdCard.JOKER_S) && byRank.containsKey(DdCard.JOKER_B))
            return DdPlay(DdComboType.ROCKET, cards, DdCard.JOKER_B)

        // 炸弹
        if (n == 4 && counts == listOf(4))
            return DdPlay(DdComboType.BOMB, cards, byRank.firstKey())

        // 单张 / 对子 / 三张
        if (n == 1) return DdPlay(DdComboType.SINGLE, cards, cards[0].rank)
        if (n == 2 && counts == listOf(2)) return DdPlay(DdComboType.PAIR, cards, byRank.firstKey())
        if (n == 3 && counts == listOf(3)) return DdPlay(DdComboType.TRIO, cards, byRank.firstKey())

        // 三带一 / 三带对
        if (n == 4 && counts.sorted() == listOf(1, 3)) {
            val trio = byRank.entries.first { it.value.size == 3 }.key
            return DdPlay(DdComboType.TRIO_ONE, cards, trio)
        }
        if (n == 5 && counts.sorted() == listOf(2, 3)) {
            val trio = byRank.entries.first { it.value.size == 3 }.key
            return DdPlay(DdComboType.TRIO_PAIR, cards, trio)
        }

        // 顺子（≥5 张单连，2 和王不能进顺）
        if (n >= 5 && counts.all { it == 1 }) {
            val ranks = byRank.keys.toList()
            if (ranks.last() <= 14 && isConsecutive(ranks))
                return DdPlay(DdComboType.STRAIGHT, cards, ranks.last())
        }

        // 连对（≥3 对连）
        if (n >= 6 && n % 2 == 0 && counts.all { it == 2 }) {
            val ranks = byRank.keys.toList()
            if (ranks.last() <= 14 && isConsecutive(ranks))
                return DdPlay(DdComboType.PAIR_STRAIGHT, cards, ranks.last())
        }

        // 四带二 / 四带两对
        if (n == 6 && counts.sorted() == listOf(1, 1, 4)) {
            val quad = byRank.entries.first { it.value.size == 4 }.key
            return DdPlay(DdComboType.FOUR_TWO, cards, quad)
        }
        if (n == 8 && counts.sorted() == listOf(2, 2, 4)) {
            val quad = byRank.entries.first { it.value.size == 4 }.key
            return DdPlay(DdComboType.FOUR_TWO_PAIRS, cards, quad)
        }

        // 飞机家族：k 组连续三张（3..A）+ 可选翅膀
        return classifyPlane(cards, byRank)
    }

    private fun isConsecutive(ranks: List<Int>): Boolean {
        for (i in 1 until ranks.size) if (ranks[i] - ranks[i - 1] != 1) return false
        return true
    }

    private fun classifyPlane(
        cards: List<DdCard>,
        byRank: SortedMap<Int, MutableList<DdCard>>
    ): DdPlay? {
        val n = cards.size
        if (n < 6) return null
        // 找所有纯三张 rank（count==3），取 3..14 内的连续段
        val trioRanks = byRank.entries.filter { it.value.size >= 3 && it.key <= 14 }.map { it.key }
        if (trioRanks.size < 2) return null
        // 所有连续段（可能存在多段，取与总数匹配的最长段）
        val runs = mutableListOf<List<Int>>()
        var cur = mutableListOf<Int>()
        for (r in trioRanks) {
            if (cur.isEmpty() || r == cur.last() + 1) cur.add(r)
            else { if (cur.size >= 2) runs.add(cur); cur = mutableListOf(r) }
        }
        if (cur.size >= 2) runs.add(cur)
        // 从长到短尝试：优先解释为更长的飞机
        for (run in runs.sortedByDescending { it.size }) {
            for (end in run.size downTo 2) {
                val seg = run.subList(0, end)
                val top = seg.last()
                val k = seg.size
                val trioCards = seg.flatMap { byRank[it]!!.take(3) }
                val rest = cards.toSet() - trioCards.toSet()
                val restSize = rest.size
                when {
                    restSize == 0 && n == 3 * k ->
                        return DdPlay(DdComboType.PLANE, cards, top)
                    restSize == k && n == 4 * k ->
                        return DdPlay(DdComboType.PLANE_ONE, cards, top)
                    restSize == 2 * k && n == 5 * k -> {
                        // 剩余需能组成 k 个对子
                        val restList = rest.toList()
                        val rc = restList.groupingBy { it.rank }.eachCount()
                        if (rc.values.all { it == 2 } && rc.size == k)
                            return DdPlay(DdComboType.PLANE_PAIR, cards, top)
                    }
                }
            }
        }
        return null
    }

    /** [a] 能否压过 [b] */
    fun beats(a: DdPlay, b: DdPlay): Boolean = when {
        a.type == DdComboType.ROCKET -> true
        b.type == DdComboType.ROCKET -> false
        a.type == DdComboType.BOMB && b.type == DdComboType.BOMB -> a.keyRank > b.keyRank
        a.type == DdComboType.BOMB -> true
        b.type == DdComboType.BOMB -> false
        else -> a.type == b.type && a.size == b.size && a.keyRank > b.keyRank
    }
}

// ==================== AI ====================

object DdAi {

    /** 叫分评估：0~3 */
    fun bidPoints(hand: List<DdCard>): Int {
        var pts = 0
        val c = hand.groupingBy { it.rank }.eachCount()
        if (c.containsKey(DdCard.JOKER_B)) pts += 4
        if (c.containsKey(DdCard.JOKER_S)) pts += 3
        if ((c[DdCard.JOKER_S] ?: 0) + (c[DdCard.JOKER_B] ?: 0) == 2) pts += 2
        pts += (c[DdCard.TWO] ?: 0) * 2
        pts += (c[14] ?: 0)
        pts += c.values.count { it == 4 } * 5
        return when {
            pts >= 11 -> 3
            pts >= 8 -> 2
            pts >= 5 -> 1
            else -> 0
        }
    }

    /** rank -> 该 rank 的所有牌 */
    private fun rankMap(hand: List<DdCard>): SortedMap<Int, List<DdCard>> {
        val m = sortedMapOf<Int, MutableList<DdCard>>()
        hand.forEach { m.getOrPut(it.rank) { mutableListOf() }.add(it) }
        return m
    }

    /** 领出：选一手先出牌 */
    fun leadPlay(hand: List<DdCard>): List<DdCard> {
        val rm = rankMap(hand)
        // 一手出完直接赢
        DdRules.classify(hand)?.let { return it.cards.sortedByDescending { c -> c.rank } }

        val triples = rm.entries.filter { it.value.size >= 3 }
        val pairs = rm.entries.filter { it.value.size >= 2 && it.key < DdCard.TWO }
        val singles = rm.entries.filter { it.value.isNotEmpty() && it.key <= 14 }

        // 顺子（最长）
        longestRun(singles.map { it.key }, minLen = 5)?.let { run ->
            return run.flatMap { listOf(rm[it]!!.first()) }
        }
        // 连对（最长 ≥3 对）
        longestRun(pairs.map { it.key }, minLen = 3)?.let { run ->
            return run.flatMap { rm[it]!!.take(2) }
        }
        // 三带一 / 三带对 / 三张
        triples.firstOrNull()?.let { e ->
            val trio = e.value.take(3)
            val restRanks = rm.entries.filter { it.key != e.key }
            val kick = restRanks.firstOrNull { it.value.size == 1 }?.value?.first()
                ?: restRanks.lastOrNull()?.value?.last()
            if (kick != null) return trio + kick
            val kickPair = restRanks.firstOrNull { it.value.size == 2 }?.value?.take(2)
            if (kickPair != null) return trio + kickPair
            return trio
        }
        // 对子
        pairs.firstOrNull()?.let { return it.value.take(2) }
        // 单张（手里只剩大牌/王时也会走到这）
        val nonBomb = rm.entries.filter { it.value.size < 4 }
        if (nonBomb.isNotEmpty()) return listOf(nonBomb.first().value.first())
        // 只剩炸弹/王炸
        return hand.sortedByDescending { it.rank }.take(4)
    }

    /** 连续 rank 段（限定 3..14），返回最长一段的牌 rank 列表；不足 minLen 返回 null */
    private fun longestRun(ranks: List<Int>, minLen: Int): List<Int>? {
        val sorted = ranks.filter { it in 3..14 }.distinct().sorted()
        var best: List<Int> = emptyList()
        var cur = mutableListOf<Int>()
        for (r in sorted) {
            if (cur.isEmpty() || r == cur.last() + 1) cur.add(r)
            else { if (cur.size > best.size) best = cur; cur = mutableListOf(r) }
        }
        if (cur.size > best.size) best = cur
        return if (best.size >= minLen) best else null
    }

    /** 找所有能压过 [last] 的打法（同型在前按小到大，炸弹次之，王炸最后） */
    fun findBeats(hand: List<DdCard>, last: DdPlay): List<DdPlay> {
        val rm = rankMap(hand)
        val out = mutableListOf<DdPlay>()

        fun mk(type: DdComboType, cards: List<DdCard>, key: Int) =
            DdPlay(type, cards.sortedByDescending { it.rank }, key)

        when (last.type) {
            DdComboType.SINGLE ->
                rm.entries.firstOrNull { it.key > last.keyRank }
                    ?.let { out.add(mk(DdComboType.SINGLE, listOf(it.value.first()), it.key)) }

            DdComboType.PAIR ->
                rm.entries.firstOrNull { it.key > last.keyRank && it.value.size >= 2 }
                    ?.let { out.add(mk(DdComboType.PAIR, it.value.take(2), it.key)) }

            DdComboType.TRIO ->
                rm.entries.firstOrNull { it.key > last.keyRank && it.value.size >= 3 }
                    ?.let { out.add(mk(DdComboType.TRIO, it.value.take(3), it.key)) }

            DdComboType.TRIO_ONE -> {
                rm.entries.firstOrNull { it.key > last.keyRank && it.value.size >= 3 }?.let { e ->
                    val kick = smallestKicker(rm, exclude = e.key, needPairs = 0, singles = 1)
                    if (kick.isNotEmpty()) out.add(mk(DdComboType.TRIO_ONE, e.value.take(3) + kick, e.key))
                }
            }

            DdComboType.TRIO_PAIR -> {
                rm.entries.firstOrNull { it.key > last.keyRank && it.value.size >= 3 }?.let { e ->
                    val kick = smallestKicker(rm, exclude = e.key, needPairs = 1, singles = 0)
                    if (kick.isNotEmpty()) out.add(mk(DdComboType.TRIO_PAIR, e.value.take(3) + kick, e.key))
                }
            }

            DdComboType.STRAIGHT -> {
                val len = last.size
                for (top in (last.keyRank + 1)..14) {
                    val start = top - len + 1
                    if (start < 3) break
                    if ((start..top).all { rm[it]?.isNotEmpty() == true }) {
                        val cards = (start..top).map { rm[it]!!.first() }
                        out.add(mk(DdComboType.STRAIGHT, cards, top))
                    }
                }
            }

            DdComboType.PAIR_STRAIGHT -> {
                val len = last.size / 2
                for (top in (last.keyRank + 1)..14) {
                    val start = top - len + 1
                    if (start < 3) break
                    if ((start..top).all { (rm[it]?.size ?: 0) >= 2 }) {
                        val cards = (start..top).flatMap { rm[it]!!.take(2) }
                        out.add(mk(DdComboType.PAIR_STRAIGHT, cards, top))
                    }
                }
            }

            DdComboType.PLANE, DdComboType.PLANE_ONE, DdComboType.PLANE_PAIR -> {
                val k = when (last.type) {
                    DdComboType.PLANE -> last.size / 3
                    DdComboType.PLANE_ONE -> last.size / 4
                    else -> last.size / 5
                }
                for (top in 14 downTo last.keyRank + 1) {
                    val start = top - k + 1
                    if (start < 3) break
                    if ((start..top).all { (rm[it]?.size ?: 0) >= 3 }) {
                        val trios = (start..top).flatMap { rm[it]!!.take(3) }
                        val wings = when (last.type) {
                            DdComboType.PLANE -> emptyList()
                            DdComboType.PLANE_ONE -> smallestKicker(rm, excludeTrios = start..top, needPairs = 0, singles = k)
                            else -> smallestKicker(rm, excludeTrios = start..top, needPairs = k, singles = 0)
                        }
                        val need = when (last.type) {
                            DdComboType.PLANE -> 0
                            DdComboType.PLANE_ONE -> k
                            else -> 2 * k
                        }
                        if (wings.size == need) out.add(mk(last.type, trios + wings, top))
                    }
                }
            }

            DdComboType.FOUR_TWO -> {
                rm.entries.firstOrNull { it.key > last.keyRank && it.value.size == 4 }?.let { e ->
                    val kick = smallestKicker(rm, exclude = e.key, needPairs = 0, singles = 2)
                    if (kick.size == 2) out.add(mk(DdComboType.FOUR_TWO, e.value + kick, e.key))
                }
            }

            DdComboType.FOUR_TWO_PAIRS -> {
                rm.entries.firstOrNull { it.key > last.keyRank && it.value.size == 4 }?.let { e ->
                    val kick = smallestKicker(rm, exclude = e.key, needPairs = 2, singles = 0)
                    if (kick.size == 4) out.add(mk(DdComboType.FOUR_TWO_PAIRS, e.value + kick, e.key))
                }
            }

            DdComboType.BOMB -> {
                rm.entries.filter { it.value.size == 4 && it.key > last.keyRank }
                    .forEach { out.add(mk(DdComboType.BOMB, it.value, it.key)) }
            }

            DdComboType.ROCKET -> { /* 无解 */ }
        }

        // 炸弹 / 王炸兜底
        if (last.type != DdComboType.ROCKET) {
            if (last.type != DdComboType.BOMB) {
                rm.entries.filter { it.value.size == 4 }
                    .forEach { out.add(mk(DdComboType.BOMB, it.value, it.key)) }
            }
            if (rm[DdCard.JOKER_S]?.isNotEmpty() == true && rm[DdCard.JOKER_B]?.isNotEmpty() == true) {
                out.add(mk(DdComboType.ROCKET, listOf(rm[DdCard.JOKER_S]!![0], rm[DdCard.JOKER_B]!![0]), DdCard.JOKER_B))
            }
        }

        // 排序：同型 < 炸弹 < 王炸，同类按 keyRank 升序
        return out.sortedWith(compareBy(
            { p: DdPlay -> when (p.type) { DdComboType.BOMB -> 1; DdComboType.ROCKET -> 2; else -> 0 } },
            { p: DdPlay -> p.keyRank }
        ))
    }

    /** 挑最小的带牌（单张或对子），不拆炸弹、不拆王炸 */
    private fun smallestKicker(
        rm: SortedMap<Int, List<DdCard>>,
        exclude: Int = -1,
        excludeTrios: IntRange? = null,
        needPairs: Int,
        singles: Int
    ): List<DdCard> {
        val result = mutableListOf<DdCard>()
        val banned = mutableSetOf<Int>()
        excludeTrios?.forEach { banned.add(it) }
        if (exclude >= 0) banned.add(exclude)
        // 王炸保护：两个王都在时不拆
        val hasRocket = rm[DdCard.JOKER_S]?.isNotEmpty() == true && rm[DdCard.JOKER_B]?.isNotEmpty() == true
        if (needPairs > 0) {
            rm.entries.filter { it.key !in banned && it.value.size == 2 }
                .sortedBy { it.key }
                .take(needPairs)
                .forEach { result.addAll(it.value.take(2)) }
        }
        if (singles > 0) {
            // 优先散单，再拆对
            val singlePool = rm.entries
                .filter { it.key !in banned && !(hasRocket && it.key >= DdCard.JOKER_S) }
                .sortedBy { it.key }
            for (e in singlePool) {
                if (result.size >= singles + needPairs * 2) break
                if (e.value.size == 1) result.add(e.value.first())
                else if (e.value.size in 2..3) {
                    // 拆对补单（炸弹不拆）
                    for (c in e.value) {
                        if (result.size >= singles + needPairs * 2) break
                        if (c !in result) result.add(c)
                    }
                }
            }
        }
        return result
    }

    /**
     * 出牌决策（跟牌或领出）。
     * @param handCounts 三家剩余张数（含自己）
     * @return null = 过
     */
    fun decide(
        idx: Int,
        hand: List<DdCard>,
        landlord: Int,
        last: DdPlay?,
        lastWinner: Int,
        handCounts: IntArray
    ): List<DdCard>? {
        val prev = last
        if (prev == null || lastWinner == idx) return leadPlay(hand)   // 领出

        val beats = findBeats(hand, prev)
        if (beats.isEmpty()) return null

        val iAmLandlord = idx == landlord
        // 农家配合：同伴出的牌不小 / 同伴快跑完了 → 让牌
        val partnerLed = !iAmLandlord && lastWinner != idx && lastWinner != landlord
        if (partnerLed) {
            if (prev.type == DdComboType.BOMB || prev.type == DdComboType.ROCKET) return null
            if (prev.keyRank >= 12) return null              // 同伴出 Q 以上，不抢
            if (handCounts[lastWinner] <= 4) return null      // 同伴要跑了，递牌
        }

        val best = beats.first()
        // 一手打完直接赢
        if (best.cards.size == hand.size) return best.cards

        // 炸弹节制：敌人手牌还多时留着
        if (best.type == DdComboType.BOMB || best.type == DdComboType.ROCKET) {
            val minOpp = if (iAmLandlord)
                min(handCounts[(idx + 1) % 3], handCounts[(idx + 2) % 3])
            else handCounts[landlord]
            if (minOpp > 2 && prev.type != DdComboType.BOMB) return null
        }

        // 对手告急（≤2 张）：单/对尽量顶大牌
        val enemyLow = if (iAmLandlord)
            min(handCounts[(idx + 1) % 3], handCounts[(idx + 2) % 3]) <= 2
        else handCounts[landlord] <= 2
        if (enemyLow && best.type == DdComboType.SINGLE) {
            // 换更大的单张顶
            beats.lastOrNull { it.type == DdComboType.SINGLE && it.keyRank >= 14 }?.let { return it.cards }
        }
        return best.cards
    }
}

// ==================== 玩家 / 局势 ====================

enum class DdPhase { BIDDING, PLAYING, OVER }

/** 结算结果 */
data class DdResult(
    val landlordIdx: Int,
    val landlordWon: Boolean,
    val deltas: List<Int>,      // 与座位顺序对应
    val spring: Boolean,
    val base: Int,
    val multiplier: Int
)

/** QQ 斗地主角色称号（按等级） */
fun ddTitle(level: Int): String = when {
    level < 3 -> "包身工"
    level < 6 -> "短工"
    level < 10 -> "贫农"
    level < 15 -> "中农"
    level < 22 -> "富农"
    level < 30 -> "掌柜"
    else -> "大地主"
}

/** 牌桌玩家 */
class DdPlayer(
    val name: String,
    val avatar: String,
    val isHuman: Boolean,
    val level: Int,
    var score: Int
) {
    val hand = mutableStateListOf<DdCard>()
}

/**
 * 一局斗地主的可观察状态容器。
 * 座位：0 = 玩家（下方），1 = 右侧 AI，2 = 左侧 AI；出牌顺序 0 → 1 → 2。
 */
class DoudizhuGame(
    selfName: String,
    selfAvatar: String,
    selfScore: Int
) {
    companion object {
        private val AI_POOL = listOf(
            "林中漫步者" to "emoji:🌳", "了了" to "emoji:🐱", "眼暗" to "emoji:😎",
            "问天" to "emoji:🌙", "冷月葬花魂" to "emoji:🌸", "随风而逝" to "emoji:🍃",
            "一品白衫" to "emoji:🎯", "江湖小虾米" to "emoji:🦐", "半支烟" to "emoji:🚬",
            "独孤求败" to "emoji:⚔️", "我爱斗地主" to "emoji:🃏", "水木清华" to "emoji:🏔️",
            "老衲用飘柔" to "emoji:🧘", "披着狼皮的羊" to "emoji:🐺", "糖醋里脊" to "emoji:🍖"
        )
        private val rnd = kotlin.random.Random(System.currentTimeMillis())
    }

    val players: List<DdPlayer> = listOf(
        DdPlayer(selfName, selfAvatar, true, 8, selfScore),
        run {
            val (n, a) = AI_POOL[rnd.nextInt(AI_POOL.size)]
            DdPlayer(n, a, false, 3 + rnd.nextInt(30), 60 + rnd.nextInt(900))
        },
        run {
            val (n, a) = AI_POOL[rnd.nextInt(AI_POOL.size)]
            DdPlayer(n, a, false, 3 + rnd.nextInt(30), 60 + rnd.nextInt(900))
        }
    )

    var phase by mutableStateOf(DdPhase.BIDDING)
    var landlord by mutableStateOf(-1)
    var bottomCards = mutableListOf<DdCard>()
    var bottomRevealed by mutableStateOf(false)

    var bidTurn by mutableStateOf(0)
    var bidCount by mutableStateOf(0)
    var bidMax by mutableStateOf(0)
    var bidWinner by mutableStateOf(-1)
    var baseScore by mutableStateOf(1)
    var multiplier by mutableStateOf(1)

    var turn by mutableStateOf(0)
    var lastPlay by mutableStateOf<DdPlay?>(null)
    var lastWinner by mutableStateOf(-1)
    var passCount by mutableStateOf(0)

    /** 桌面上各家最近打出的牌 / 是否"不出" */
    val shown = mutableStateMapOf<Int, List<DdCard>>()
    val passed = mutableStateMapOf<Int, Boolean>()

    /** 右侧面板游戏动态 */
    val log = mutableStateListOf<String>()

    var result by mutableStateOf<DdResult?>(null)
    private val playCounts = IntArray(3)

    init {
        dealAndStartBid()
    }

    private fun addLog(text: String) {
        log.add(text)
        while (log.size > 60) log.removeAt(0)
    }

    /** 玩家在牌桌发言（快捷表情等） */
    fun say(text: String) = addLog("${players[0].name}：$text")

    private fun nextSeat(i: Int) = (i + 1) % 3

    /** 发牌并进入叫分阶段（"再来一局"也走这里） */
    fun dealAndStartBid() {
        players.forEach { it.hand.clear() }
        shown.clear(); passed.clear()
        bottomCards.clear()
        bottomRevealed = false
        landlord = -1; lastPlay = null; lastWinner = -1
        passCount = 0; bidCount = 0; bidMax = 0; bidWinner = -1
        multiplier = 1; baseScore = 1
        playCounts.fill(0)
        result = null

        val deck = DdCard.fullDeck().shuffled()
        for (i in 0..2) {
            players[i].hand.addAll(deck.subList(i * 17, i * 17 + 17))
            sortDesc(players[i].hand)
        }
        bottomCards.addAll(deck.subList(51, 54))

        addLog("—— 新一局开始，发牌完毕 ——")
        bidTurn = rnd.nextInt(3)
        phase = DdPhase.BIDDING
        addLog("等待${players[bidTurn].name}叫分…")
    }

    /** 叫分（score=0 不叫）。全不叫自动重发，有人叫 3 分立即定庄 */
    fun makeBid(idx: Int, score: Int) {
        if (phase != DdPhase.BIDDING || idx != bidTurn) return
        val s = if (score > bidMax) score else 0
        addLog("${players[idx].name}：" + if (s == 0) "不叫" else "叫 $s 分")
        if (s > bidMax) { bidMax = s; bidWinner = idx }
        bidCount++
        if (s == 3 || bidCount == 3) {
            if (bidMax == 0) {
                addLog("无人叫地主，重新发牌～")
                dealAndStartBid()
                return
            }
            becomeLandlord(bidWinner, if (bidMax == 0) 1 else bidMax)
        } else {
            bidTurn = nextSeat(bidTurn)
        }
    }

    private fun becomeLandlord(idx: Int, score: Int) {
        landlord = idx
        baseScore = score
        players[idx].hand.addAll(bottomCards)
        sortDesc(players[idx].hand)
        bottomRevealed = true
        multiplier = 1
        turn = idx
        phase = DdPhase.PLAYING
        addLog("${players[idx].name} 以 $score 分当选地主，获得底牌！")
        addLog("地主先出牌")
        QQSounds.ding()
    }

    /** 当前行动者是否必须压上一手（领出者不用） */
    fun mustBeat(idx: Int): Boolean = lastPlay != null && lastWinner != idx

    /** 打牌（含校验）。非法打牌静默忽略（UI 层已先行校验） */
    fun playCards(idx: Int, cards: List<DdCard>) {
        if (phase != DdPhase.PLAYING || idx != turn) return
        val play = DdRules.classify(cards) ?: return
        if (mustBeat(idx) && !DdRules.beats(play, lastPlay!!)) return
        val hand = players[idx].hand
        if (!cards.all { c -> hand.any { it == c } }) return
        cards.forEach { hand.remove(it) }

        shown[idx] = cards.sortedByDescending { it.rank }
        passed.remove(idx)
        lastPlay = play
        lastWinner = idx
        passCount = 0
        playCounts[idx]++

        if (play.type == DdComboType.BOMB || play.type == DdComboType.ROCKET) {
            multiplier *= 2
            QQSounds.bomb()
        } else {
            QQSounds.card()
        }

        addLog("${players[idx].name}：${play.describe()}" + if (multiplier > 1 && (play.type == DdComboType.BOMB || play.type == DdComboType.ROCKET)) "（倍数 ×$multiplier）" else "")

        if (hand.isEmpty()) { settle(idx); return }
        turn = nextSeat(turn)
    }

    /** 过牌（领出者不允许过） */
    fun passTurn(idx: Int) {
        if (phase != DdPhase.PLAYING || idx != turn) return
        if (!mustBeat(idx)) return   // 领出不能过
        shown.remove(idx)
        passed[idx] = true
        passCount++
        addLog("${players[idx].name}：不出")
        if (passCount >= 2) {
            // 一圈没人要 → 清桌，上家继续领出
            lastPlay = null
            passCount = 0
            shown.clear()
            turn = lastWinner
            addLog("没人要，轮到${players[lastWinner].name}继续出")
        } else {
            turn = nextSeat(turn)
        }
    }

    /** UI"提示"按钮：返回一手建议（空列表 = 只能过） */
    fun hint(): List<DdCard> {
        val hand = players[0].hand
        return if (mustBeat(0)) {
            DdAi.findBeats(hand, lastPlay!!).firstOrNull()?.cards ?: emptyList()
        } else {
            DdAi.leadPlay(hand)
        }
    }

    private fun settle(winner: Int) {
        phase = DdPhase.OVER
        bottomRevealed = true
        val landlordWon = winner == landlord
        // 春天 / 反春
        val farmers = listOf((landlord + 1) % 3, (landlord + 2) % 3)
        var spring = false
        if (landlordWon && farmers.all { playCounts[it] == 0 }) spring = true
        if (!landlordWon && playCounts[landlord] == 1) spring = true
        val finalMult = multiplier * if (spring) 2 else 1
        val unit = baseScore * finalMult
        val deltas = List(3) { i ->
            when {
                i == landlord -> if (landlordWon) 2 * unit else -2 * unit
                else -> if (landlordWon) -unit else unit
            }
        }
        deltas.forEachIndexed { i, d -> players[i].score += d }
        result = DdResult(landlord, landlordWon, deltas, spring, baseScore, finalMult)
        addLog(
            (if (landlordWon) "地主胜" else "农民胜") +
                    "（底分 $baseScore × 倍数 $finalMult${if (spring) "，春天！" else ""}）"
        )
    }

    /** 本局"我"的输赢（1 胜 / -1 负 / 0 未结束） */
    fun selfOutcome(): Int {
        val r = result ?: return 0
        val myDelta = r.deltas[0]
        return if (myDelta > 0) 1 else -1
    }
}

// ==================== 积分存档 ====================

/**
 * 斗地主积分存档（filesDir/qq/game_scores.json）：
 * { "scores": { "玩家名或号码": 积分 } }
 */
object QQGameScores {
    private var dir: File? = null

    fun init(context: android.content.Context) {
        if (dir == null) dir = File(context.filesDir, "qq").apply { mkdirs() }
    }

    fun load(): MutableMap<String, Int> = runCatching {
        val f = File(dir, "game_scores.json")
        if (!f.exists()) return mutableMapOf()
        val root = JSONObject(f.readText())
        val obj = root.optJSONObject("scores") ?: return mutableMapOf()
        val m = mutableMapOf<String, Int>()
        obj.keys().forEach { k -> m[k] = obj.optInt(k, 0) }
        m
    }.getOrDefault(mutableMapOf())

    /** 加减分并持久化，返回新积分 */
    fun add(name: String, delta: Int): Int {
        val m = load()
        val v = (m[name] ?: 0) + delta
        m[name] = v
        runCatching {
            val obj = JSONObject()
            val scores = JSONObject()
            m.forEach { (k, s) -> scores.put(k, s) }
            obj.put("scores", scores)
            val d = dir ?: return
            val tmp = File(d, "game_scores.json.tmp")
            tmp.writeText(obj.toString())
            File(d, "game_scores.json").delete()
            tmp.renameTo(File(d, "game_scores.json"))
        }
        return v
    }
}
