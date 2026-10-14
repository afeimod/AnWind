package com.anwind.apps.qq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * QQ2008 for AnWind —— AI 引擎层。
 *
 * 架构：UI → [QQSession.sendAndAwaitReply] → [ChatBackend.reply]
 *
 * 双引擎：
 *  - [LocalAiEngine]   内置离线规则引擎（默认）：无需网络 / Key，按人格模板 +
 *                      规则匹配（问候/时间/算术/笑话/诗词/聊天兜底）生成回复。
 *  - [RemoteAiEngine]  OpenAI 兼容 Chat Completions（可配 base URL / Key / 模型），
 *                      兼容 OpenAI / DeepSeek / 智谱 GLM / Ollama / vLLM 等端点。
 *
 * 服务器版预留：后续接入真实 IM 服务器时，新增一个 ServerBackend 实现
 * [ChatBackend]（长连接收发），UI 层无需改动。
 */
object QQAi {

    /** 聊天后端接口 —— 服务器版只需新增实现，UI 不动 */
    interface ChatBackend {
        /**
         * 生成回复。
         * @param contact  联系人（含人格 key；群聊时 isGroup=true）
         * @param history  该会话历史消息（时间升序，含刚发送的用户消息）
         * @return 回复文本
         */
        suspend fun reply(contact: QQFriend, history: List<QQMessage>): String
    }

    // ==================== 内置人格 ====================

    /** 内置 AI 联系人（首次登录自动加为好友） */
    object Personas {

        data class Persona(
            val key: String,
            val number: String,
            val nickname: String,
            val avatar: String,
            val signature: String,
            val systemPrompt: String
        )

        val ASSISTANT = Persona(
            key = "assistant", number = "10000", nickname = "QQ小助手",
            avatar = "emoji:🤖", signature = "官方小助手，有问题找我！",
            systemPrompt = "你是 QQ小助手（号码10000），2008 年经典 QQ 的官方助手机器人。" +
                    "说话热情、简洁、乐于助人，偶尔使用颜文字。回答控制在 80 字内。"
        )
        val XIAOBING = Persona(
            key = "xiaobing", number = "10001", nickname = "小冰",
            avatar = "emoji:❄️", signature = "冰雪聪明，一秒回你~",
            systemPrompt = "你是小冰（号码10001），一个活泼可爱的少女 AI。" +
                    "喜欢用叠词、卖萌和颜文字（如 (＾▽＾)、(＞﹏＜)），语气俏皮，回答不超过 60 字。"
        )
        val LIBAI = Persona(
            key = "libai", number = "10002", nickname = "青莲居士",
            avatar = "emoji:🍶", signature = "举杯邀明月，对影成三人",
            systemPrompt = "你是李白（号码10002），大唐诗仙穿越到网络时代。" +
                    "说话半文半白，爱好作诗与饮酒，遇到话题常引用或即兴创作诗句，回答不超过 100 字。"
        )
        val TURING = Persona(
            key = "turing", number = "10003", nickname = "图灵博士",
            avatar = "emoji:🧠", signature = "机器也会做梦吗？",
            systemPrompt = "你是图灵博士（号码10003），一位理性严谨的科学家 AI。" +
                    "擅长科学、数学、逻辑类问题，说话条理清晰，偶尔幽默地提到图灵测试，回答不超过 100 字。"
        )

        /** 怀旧茶馆群（群聊：多个 AI 成员一起回复） */
        const val GROUP_NUMBER = "20080001"
        val GROUP_MEMBERS = listOf(ASSISTANT, XIAOBING, LIBAI, TURING)

        val all: List<Persona> = listOf(ASSISTANT, XIAOBING, LIBAI, TURING)

        fun byKey(key: String): Persona =
            all.firstOrNull { it.key == key } ?: ASSISTANT

        fun byNumber(number: String): Persona? =
            all.firstOrNull { it.number == number }

        /** 转成好友对象（写入好友列表） */
        fun toFriend(p: Persona): QQFriend = QQFriend(
            number = p.number,
            nickname = p.nickname,
            avatar = p.avatar,
            signature = p.signature,
            status = QQStatus.ONLINE,
            groupName = "我的好友",
            isAi = true,
            personaKey = p.key
        )

        /** 群对象 */
        fun groupFriend(): QQFriend = QQFriend(
            number = GROUP_NUMBER,
            nickname = "怀旧茶馆",
            avatar = "emoji:🍵",
            signature = "群聚精彩，共享盛世",
            status = QQStatus.ONLINE,
            groupName = "我的群组",
            isAi = true,
            personaKey = "group",
            isGroup = true,
            groupMembers = GROUP_MEMBERS.map { it.number }
        )
    }

    /** 根据设置选择后端；远程失败时由调用方回落本地 */
    fun backendFor(settings: QQSettings): ChatBackend =
        if (settings.aiEngine == QQSettings.ENGINE_REMOTE && settings.apiKey.isNotBlank())
            RemoteAiEngine(settings) else LocalAiEngine

    // ==================== 本地离线引擎 ====================

    object LocalAiEngine : ChatBackend {

        private val rnd = Random(System.currentTimeMillis())

        private val jokes = listOf(
            "从前有座山，山里有座庙，庙里有个老和尚在跟小和尚讲：从前有座山……",
            "程序员最讨厌的四件事：写文档、写注释、别人不写文档、别人不写注释。",
            "我问电脑：我胖吗？电脑说：你只是像素比较高。",
            "2008 年最大的烦恼：QQ 农场的菜总被人偷。现在最大的烦恼：没有菜可偷了。"
        )

        private val poems = listOf(
            "床前明月光，疑是地上霜。\n举头望明月，低头思故乡。",
            "故人西辞黄鹤楼，烟花三月下扬州。\n孤帆远影碧空尽，唯见长江天际流。",
            "两个黄鹂鸣翠柳，一行白鹭上青天。\n窗含西岭千秋雪，门泊东吴万里船。",
            "人生得意须尽欢，莫使金樽空对月。\n天生我材必有用，千金散尽还复来。"
        )

        override suspend fun reply(contact: QQFriend, history: List<QQMessage>): String =
            withContext(Dispatchers.Default) {
                if (contact.isGroup) replyGroup(contact, history)
                else replySingle(contact, history)
            }

        private fun replySingle(contact: QQFriend, history: List<QQMessage>): String {
            val last = history.lastOrNull { !it.isSelf } ?: history.lastOrNull()
            val text = last?.text?.trim() ?: ""
            val p = Personas.byKey(contact.personaKey)
            val flavor = p.nickname
            return when {
                text.isEmpty() -> "在吗？(＾▽＾)"
                isGreeting(text) -> pick(listOf(
                    "你好呀～我是$flavor，很高兴认识你！(＾▽＾)",
                    "在的在的～今天过得怎么样？",
                    "嗨～好久不见，最近可好？"
                ))
                text.contains("你是谁") || text.contains("介绍") ->
                    "我是$flavor —— ${contact.signature} 有什么想聊的尽管说！"
                text.contains("几点") || text.contains("时间") || text.contains("日期") -> {
                    val now = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date())
                    "现在是 $now。（别忘了 QQ 农场收菜～）"
                }
                text.contains("天气") ->
                    pick(listOf(
                        "我这边的天气是：代码运行正常，晴转多云。你那里呢？",
                        "出门记得带伞，毕竟 2008 年的手机还不能看天气预报～"
                    ))
                isMath(text) -> solveMath(text) ?: "这道题有点难住我了，你算出来是多少？"
                text.contains("笑话") || text.contains("搞笑") -> pick(jokes)
                text.contains("诗") || text.contains("古诗") || text.contains("念一首") ->
                    if (contact.personaKey == "libai") "即兴一首：\n" + pick(poems)
                    else "我不会作诗，帮你请了位大师：\n" + pick(poems)
                text.contains("谢谢") -> pick(listOf("不客气～(＾▽＾)", "小事一桩，随时找我！"))
                text.contains("再见") || text.contains("拜拜") -> "拜拜～记得常来找我聊天哦！"
                text.contains("?") || text.contains("？") -> pick(listOf(
                    "这个问题嘛……让我想想 (´・ω・`) 你觉得呢？",
                    "唔，说来话长。先说说你为什么好奇这个？"
                ))
                else -> pick(listOf(
                    "嗯嗯，然后呢？我听着呢～",
                    "哈哈，有意思。继续说说？",
                    "原来如此！对了，你最近都在忙什么呀？",
                    "收到收到～（小助手已上线，输入\"笑话\"\"时间\"或算术题试试）",
                    "听着挺有意思的～多聊聊！"
                ))
            }
        }

        /** 群聊：随机挑 1~2 位成员各自回复 */
        private fun replyGroup(contact: QQFriend, history: List<QQMessage>): String {
            val speakers = Personas.GROUP_MEMBERS.shuffled(rnd)
                .take(if (rnd.nextBoolean()) 1 else 2)
            return speakers.joinToString("\n\n") { p ->
                val one = replySingle(
                    contact.copy(personaKey = p.key, nickname = p.nickname),
                    history
                )
                "${p.nickname}：$one"
            }
        }

        private fun isGreeting(t: String): Boolean {
            val lower = t.lowercase()
            return listOf("你好", "您好", "hi", "hello", "在吗", "在么", "早上好", "晚上好", "下午好", "嗨", "哈喽", "hi~")
                .any { lower.contains(it) }
        }

        private fun isMath(t: String): Boolean {
            val cleaned = t.replace("计算", "").replace("等于多少", "").replace("?", "").replace("？", "").trim()
            return Regex("^[0-9+\\-*/×÷().\\s]+$").matches(cleaned) &&
                    Regex("[0-9]").containsMatchIn(cleaned) &&
                    Regex("[+\\-*/×÷]").containsMatchIn(cleaned)
        }

        /** 安全四则运算（逐字符解析，不用 eval） */
        private fun solveMath(t: String): String? = runCatching {
            val expr = t.replace("计算", "").replace("等于多少", "")
                .replace("?", "").replace("？", "")
                .replace("×", "*").replace("÷", "/").replace(" ", "").trim()
            val result = evalSimple(expr)
            if (abs(result - result.toLong()) < 1e-9) "= ${result.toLong()}" else "= $result"
        }.getOrNull()

        private fun evalSimple(expr: String): Double {
            // 递归下降：expr = term (+|- term)* ; term = factor (*|/ factor)* ; factor = num | (expr)
            var pos = 0
            fun parseExpr(): Double {
                var v = parseTerm()
                while (pos < expr.length && (expr[pos] == '+' || expr[pos] == '-')) {
                    val op = expr[pos++]
                    val r = parseTerm()
                    v = if (op == '+') v + r else v - r
                }
                return v
            }
            fun parseTerm(): Double {
                var v = parseFactor()
                while (pos < expr.length && (expr[pos] == '*' || expr[pos] == '/')) {
                    val op = expr[pos++]
                    val r = parseFactor()
                    v = if (op == '*') v * r else v / r
                }
                return v
            }
            fun parseFactor(): Double {
                if (expr[pos] == '(') {
                    pos++
                    val v = parseExpr()
                    pos++ // skip ')'
                    return v
                }
                val start = pos
                while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
                return expr.substring(start, pos).toDouble()
            }
            return parseExpr()
        }

        private fun pick(list: List<String>): String = list[rnd.nextInt(list.size)]
    }

    // ==================== 远程 OpenAI 兼容引擎 ====================

    class RemoteAiEngine(private val settings: QQSettings) : ChatBackend {

        override suspend fun reply(contact: QQFriend, history: List<QQMessage>): String =
            withContext(Dispatchers.IO) {
                val systemPrompt = buildSystemPrompt(contact)
                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                history.takeLast(12).forEach { m ->
                    messages.put(
                        JSONObject()
                            .put("role", if (m.isSelf) "user" else "assistant")
                            .put("content", if (m.isSelf) m.text else "${m.senderName}：${m.text}")
                    )
                }
                // 群聊时把非当前联系人发言并入 user 视角，上面已带名字前缀

                val body = JSONObject()
                    .put("model", settings.apiModel)
                    .put("messages", messages)
                    .put("temperature", 0.8)
                    .put("max_tokens", 300)

                val conn = (URL(settings.apiBaseUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
                }
                try {
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText() ?: ""
                    if (code !in 200..299) {
                        throw RuntimeException("API $code: ${text.take(200)}")
                    }
                    val root = JSONObject(text)
                    val content = root.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                    if (content.isNullOrBlank()) throw RuntimeException("API 返回为空")
                    content.trim()
                } finally {
                    conn.disconnect()
                }
            }

        private fun buildSystemPrompt(contact: QQFriend): String {
            if (contact.isGroup) {
                val members = contact.groupMembers.mapNotNull { Personas.byNumber(it) }
                return "这是 2008 年风格的 QQ 群聊「${contact.nickname}」。群成员：" +
                        members.joinToString("、") { it.nickname } +
                        "。请以其中 1~2 位成员的口吻回复，回复格式：\"昵称：内容\"，" +
                        "保持 2008 年网络聊天氛围，回复不超过 120 字。" +
                        members.joinToString("\n") { "人格设定 - ${it.nickname}：${it.systemPrompt}" }
            }
            val p = Personas.byKey(contact.personaKey)
            return "${p.systemPrompt}\n你在 QQ 上与好友聊天，直接回复消息内容，不要加自己的名字前缀。"
        }
    }
}
