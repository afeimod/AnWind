package com.anwind.apps.qq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
         * @param onDelta  流式增量回调：在线引擎边生成边回调（参数为本次新增的文本
         *                 片段），实现“打字机”效果；本地引擎瞬时生成，不回调
         * @return 完整回复文本（流式时 = 所有增量的累计结果）
         */
        suspend fun reply(
            contact: QQFriend,
            history: List<QQMessage>,
            onDelta: (String) -> Unit = {}
        ): String
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
                    "喜欢用叠词、卖萌和颜文字（如 (＾▽＾)、(＞﹏＜)），语气俏皮。"
        )
        val LIBAI = Persona(
            key = "libai", number = "10002", nickname = "青莲居士",
            avatar = "emoji:🍶", signature = "举杯邀明月，对影成三人",
            systemPrompt = "你是李白（号码10002），大唐诗仙穿越到网络时代。" +
                    "说话半文半白，爱好作诗与饮酒，遇到话题常引用或即兴创作诗句。"
        )
        val TURING = Persona(
            key = "turing", number = "10003", nickname = "图灵博士",
            avatar = "emoji:🧠", signature = "机器也会做梦吗？",
            systemPrompt = "你是图灵博士（号码10003），一位理性严谨的科学家 AI。" +
                    "擅长科学、数学、逻辑、编程类问题，说话条理清晰，偶尔幽默地提到图灵测试。"
        )
        val PIPI = Persona(
            key = "pipi", number = "10004", nickname = "皮皮",
            avatar = "emoji:🐵", signature = "段子手皮皮，专治不开心",
            systemPrompt = "你是皮皮（号码10004），2008 年论坛最火的段子手。" +
                    "幽默、爱接梗、会自嘲，擅长讲笑话、吐槽和搞笑段子，偶尔抖机灵，但不低俗不冒犯。"
        )
        val ZHILE = Persona(
            key = "zhile", number = "10005", nickname = "知了",
            avatar = "emoji:📚", signature = "行走的百科全书",
            systemPrompt = "你是知了（号码10005），行走的百科全书。" +
                    "擅长科普、历史、地理、生活小常识和知识问答，讲解通俗易懂，喜欢用生活化的比喻。"
        )

        /** 怀旧茶馆群（群聊：多个 AI 成员一起回复） */
        const val GROUP_NUMBER = "20080001"
        val GROUP_MEMBERS = listOf(ASSISTANT, XIAOBING, LIBAI, TURING, PIPI, ZHILE)

        val all: List<Persona> = listOf(ASSISTANT, XIAOBING, LIBAI, TURING, PIPI, ZHILE)

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

    // ==================== 远程 HTTP 公用工具（reply 与测试连接共用） ====================

    /**
     * 接口地址规范化：填 base 地址或完整端点都能用。
     *  - "api.deepseek.com"                      → https://api.deepseek.com/v1/chat/completions
     *  - "https://api.deepseek.com"              → https://api.deepseek.com/v1/chat/completions
     *  - "https://api.openai.com/v1"             → https://api.openai.com/v1/chat/completions
     *  - "https://…/v1/chat/completions"          → 原样
     */
    private fun endpointFor(raw: String): String {
        var u = raw.trim().ifBlank { "https://api.openai.com/v1/chat/completions" }
        if (!u.startsWith("http")) u = "https://$u"
        u = u.trimEnd('/')
        return when {
            u.endsWith("/chat/completions") -> u
            Regex("/v[1-9]$").containsMatchIn(u) -> "$u/chat/completions"
            else -> "$u/v1/chat/completions"
        }
    }

    /** 密钥清洗：去首尾空白、去误粘的 Bearer 前缀 */
    private fun cleanKey(raw: String): String =
        raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()

    /**
     * 安全字符串提取：JSON 值为 null（或字面量 "null"）时返回 null。
     *
     * ⚠️ Android org.json 的 optString() 对 JSON null 返回的是字符串 "null"，
     * 它不是空白串，能绕过 isNullOrBlank() 检查直接显示在聊天里 ——
     * 这正是“让 AI 说长一点就回复一个 null”的元凶。
     * 所有面向展示/入参的字符串字段一律用本函数取。
     */
    private fun JSONObject.safeString(name: String): String? {
        if (isNull(name)) return null
        val v = optString(name)
        return if (v == "null") null else v
    }

    /** 发送 POST，返回 (HTTP 状态码, 响应体) —— 供「测试连接」等非流式调用使用 */
    private fun httpPost(endpoint: String, key: String, body: JSONObject): Pair<Int, String> {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 120000   // 慢服务生成大回复时不误报超时（此前 60s 不够）
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    /** HTTP 状态码 → 人话 */
    private fun friendlyHttp(code: Int): String = when (code) {
        400 -> "请求格式被拒（检查模型名是否拼写正确）"
        401 -> "密钥无效或未授权"
        403 -> "无权访问（检查密钥权限 / 地区限制 / 是否欠费）"
        404 -> "接口路径不存在（检查接口地址）"
        429 -> "请求过于频繁或额度用尽"
        in 500..599 -> "服务端错误（稍后重试）"
        else -> ""
    }

    /** 网络异常 → 人话 */
    private fun friendlyError(e: Throwable): String = when {
        e is UnknownHostException -> "无法解析主机名（检查网络和地址拼写）"
        e is ConnectException -> "连接被拒绝（服务未启动 / 端口不对 / 防火墙拦截）"
        e is SocketTimeoutException -> "连接或读取超时（网络慢或服务无响应）"
        e is SSLException -> "SSL/TLS 握手失败（证书或协议问题）"
        e is java.io.IOException && e.message?.contains("Cleartext", true) == true ->
            "明文 HTTP 被系统拦截：请改用 https:// 地址"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
    }

    /**
     * 测试在线 API 连通性：发一个 max_tokens=8 的最小请求，
     * 返回人话结果（供设置对话框「测试连接」按钮显示）。
     */
    suspend fun testConnection(settings: QQSettings): String = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        try {
            val body = JSONObject()
                .put("model", settings.apiModel.ifBlank { "gpt-4o-mini" })
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))
                .put("max_tokens", 8)
            val endpoint = endpointFor(settings.apiBaseUrl)
            val (code, text) = httpPost(endpoint, cleanKey(settings.apiKey), body)
            val ms = System.currentTimeMillis() - t0
            if (code in 200..299) {
                val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.safeString("content")
                "连接成功（${ms}ms）" + if (content != null) "，模型已正常响应"
                    else "（模型未输出内容：疑似推理模型或 max_tokens 过小，聊天可能慢/空，建议换普通对话模型）"
            } else {
                "HTTP $code：${friendlyHttp(code)}${if (text.isNotBlank()) "\n服务返回：${text.take(160)}" else ""}"
            }
        } catch (e: Exception) {
            friendlyError(e)
        }
    }

    // ==================== 本地离线引擎 ====================

    object LocalAiEngine : ChatBackend {

        private val rnd = Random(System.currentTimeMillis())

        private val jokes = listOf(
            "从前有座山，山里有座庙，庙里有个老和尚在跟小和尚讲：从前有座山……",
            "程序员最讨厌的四件事：写文档、写注释、别人不写文档、别人不写注释。",
            "我问电脑：我胖吗？电脑说：你只是像素比较高。",
            "2008 年最大的烦恼：QQ 农场的菜总被人偷。现在最大的烦恼：没有菜可偷了。",
            "面试官：你最大的缺点是什么？我：诚实。面试官：我不觉得这是缺点。我：我不在乎你怎么觉得。",
            "医生：你得多喝水。我：喝的是奶茶。医生：那你还来看什么病。",
            "我的钱包就像洋葱，每次打开都让我泪流满面。"
        )

        private val poems = listOf(
            "床前明月光，疑是地上霜。\n举头望明月，低头思故乡。",
            "故人西辞黄鹤楼，烟花三月下扬州。\n孤帆远影碧空尽，唯见长江天际流。",
            "两个黄鹂鸣翠柳，一行白鹭上青天。\n窗含西岭千秋雪，门泊东吴万里船。",
            "人生得意须尽欢，莫使金樽空对月。\n天生我材必有用，千金散尽还复来。",
            "落霞与孤鹜齐飞，秋水共长天一色。",
            "海上生明月，天涯共此时。\n情人怨遥夜，竟夕起相思。"
        )

        private val riddles = listOf(
            "什么东西越洗越脏？——水。",
            "什么车寸步难行？——风车。",
            "小明从不念书却得了模范生，为什么？——他是聋哑学生（用手语）。",
            "什么东西有头无脚？——砖头。",
            "什么门永远关不上？——球门。",
            "什么东西天气越热它爬得越高？——温度计。"
        )

        private val idioms = listOf(
            "塞翁失马，焉知非福 —— 比喻坏事在一定条件下可能变为好事。",
            "画蛇添足 —— 做多余的事，反而弄巧成拙。",
            "亡羊补牢，为时不晚 —— 出了问题及时补救，还不算迟。",
            "守株待兔 —— 比喻不主动努力，妄想凭侥幸得到成功。",
            "胸有成竹 —— 做事之前已有通盘考虑，很有把握。",
            "水滴石穿 —— 只要坚持不懈，细微之力也能做出很难办的事。"
        )

        private val stories = listOf(
            "从前有个小企鹅，天天在冰上滑来滑去。有一天它滑得太远，到了赤道，\n它摸摸脑袋说：\"原来 QQ 也可以这么热。\"后来它开了家冷饮店，生意可好了。",
            "2008 年，有个孩子的 QQ 密码是 123456，第二天发现签名变成了：\n\"谢谢那位帮我改密码还给我充了十年会员的大哥。\"",
            "有一只蜗牛想去环球旅行。朋友都笑它太慢。\n十年后它回来了，说：\"我没环游世界，但我看清了自家院子里的每一片叶子。\""
        )

        private val quotes = listOf(
            "天生我材必有用，千金散尽还复来。 —— 李白",
            "世上无难事，只怕有心人。",
            "宝剑锋从磨砺出，梅花香自苦寒来。",
            "不怕慢，就怕站。一步一步走，总会到的。",
            "今天不开心没关系，反正明天也不一定开心，但总有一天真会开心。"
        )

        private val fortunes = listOf(
            "今日运势：★★★★☆ 贵人运旺，适合抢地主！",
            "今日运势：★★★★★ 大吉！出门捡到 Q 币的日子到了。",
            "今日运势：★★★☆☆ 平稳。别偷菜了，先把自己的作业写完。",
            "今日运势：★★☆☆☆ 宜潜水，忌抬杠。",
            "今日运势：★★★★☆ 桃花运暗涌，记得把头像换好看点。"
        )

        private val phrasebook = mapOf(
            "你好" to "hello / hi", "谢谢" to "thank you", "再见" to "goodbye / bye",
            "我爱你" to "I love you", "早上好" to "good morning", "晚上好" to "good evening",
            "对不起" to "sorry", "没关系" to "never mind", "加油" to "come on / go for it",
            "你好吗" to "how are you?", "多少钱" to "how much?", "吃饭" to "have a meal",
            "朋友" to "friend", "电脑" to "computer", "手机" to "mobile phone"
        )

        override suspend fun reply(
            contact: QQFriend,
            history: List<QQMessage>,
            onDelta: (String) -> Unit
        ): String =
            withContext(Dispatchers.Default) {
                // 本地引擎瞬时生成，无需流式；不回调 onDelta，会话层走一次性展示路径
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
                text.contains("帮助") || text.contains("你会什么") || text.contains("什么功能") ||
                        text.contains("功能") || text.contains("怎么玩") ->
                    "我会的可多啦：\n· 算算术：输入 3+5*2 试试\n· 讲笑话 / 讲故事 / 作诗\n" +
                            "· 出谜语 / 成语 / 名言\n· 报时间 / 占卜今日运势\n· 猜拳（石头剪刀布）\n" +
                            "· 简单中译英（试试：翻译 你好）\n· 或者随便找我聊天～"
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
                text.contains("谜语") || text.contains("脑筋急转弯") -> pick(riddles)
                text.contains("成语") -> pick(idioms)
                text.contains("故事") || text.contains("讲故事") -> pick(stories)
                text.contains("名言") || text.contains("励志") || text.contains("鸡汤") -> pick(quotes)
                text.contains("运势") || text.contains("占卜") || text.contains("算命") -> pick(fortunes)
                text.contains("石头剪刀布") || text.contains("猜拳") ->
                    pick(listOf("我出石头！你出什么？", "剪刀！耶我赢了…啊你说你出什么了？", "布！哈哈，快说你出了什么～"))
                text.startsWith("翻译") -> {
                    val word = text.removePrefix("翻译").trim()
                    val hit = phrasebook.entries.firstOrNull { word.contains(it.key) }
                    if (hit != null) "「${hit.key}」的英文是：${hit.value}"
                    else "我的电子词典是 2008 年版的，只收录常用词～\n试试：翻译 你好 / 谢谢 / 加油"
                }
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
                    "收到收到～（输入\"帮助\"看我都会啥；在线 API 模式下我更聪明哦）",
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
            val result = SimpleParser(expr).parse()
            if (abs(result - result.toLong()) < 1e-9) "= ${result.toLong()}" else "= $result"
        }.getOrNull()

        /**
         * 极简四则运算解析器（递归下降）：
         *   expr   = term  (("+"|"-") term)*
         *   term   = factor (("*"|"/") factor)*
         *   factor = ("+"|"-") factor | number | "(" expr ")"
         *
         * 用嵌套类而非局部函数：Kotlin 局部函数不能前向引用，
         * 而这三个文法层级互相递归（factor → expr → term → factor），
         * 必须是类成员函数才能互相调用。
         */
        private class SimpleParser(private val expr: String) {
            private var pos = 0

            fun parse(): Double {
                val v = parseExpr()
                if (pos < expr.length) throw IllegalArgumentException("位置 $pos 有多余字符 '${expr[pos]}'")
                return v
            }

            private fun parseExpr(): Double {
                var v = parseTerm()
                while (pos < expr.length && (expr[pos] == '+' || expr[pos] == '-')) {
                    val op = expr[pos++]
                    val r = parseTerm()
                    v = if (op == '+') v + r else v - r
                }
                return v
            }

            private fun parseTerm(): Double {
                var v = parseFactor()
                while (pos < expr.length && (expr[pos] == '*' || expr[pos] == '/')) {
                    val op = expr[pos++]
                    val r = parseFactor()
                    v = if (op == '*') v * r else v / r
                }
                return v
            }

            private fun parseFactor(): Double {
                if (pos >= expr.length) throw IllegalArgumentException("表达式意外结束")
                // 一元正负号：支持 "-3+5"、"3*(-2)" 等
                if (expr[pos] == '+' || expr[pos] == '-') {
                    val negative = expr[pos] == '-'
                    pos++
                    val v = parseFactor()
                    return if (negative) -v else v
                }
                if (expr[pos] == '(') {
                    pos++
                    val v = parseExpr()
                    if (pos >= expr.length || expr[pos] != ')') throw IllegalArgumentException("缺少右括号")
                    pos++ // skip ')'
                    return v
                }
                val start = pos
                while (pos < expr.length && (expr[pos].isDigit() || expr[pos] == '.')) pos++
                if (start == pos) throw IllegalArgumentException("位置 $pos 缺少数字")
                return expr.substring(start, pos).toDouble()
            }
        }

        private fun pick(list: List<String>): String = list[rnd.nextInt(list.size)]
    }

    // ==================== 远程 OpenAI 兼容引擎 ====================

    class RemoteAiEngine(private val settings: QQSettings) : ChatBackend {

        override suspend fun reply(
            contact: QQFriend,
            history: List<QQMessage>,
            onDelta: (String) -> Unit
        ): String =
            withContext(Dispatchers.IO) {
                val systemPrompt = buildSystemPrompt(contact)
                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
                // 上下文 12 → 20 条：长对话/大任务时不容易"忘事"
                history.takeLast(20).forEach { m ->
                    // 单聊：双方都用纯文本（此前给 assistant 消息加"昵称："前缀，
                    // 等于教模型给自己加前缀，还会污染上下文）；
                    // 群聊：所有发言都带说话人名字，模型才能分清谁在说话
                    val content = if (contact.isGroup) "${m.senderName}：${m.text}" else m.text
                    messages.put(
                        JSONObject()
                            .put("role", if (m.isSelf) "user" else "assistant")
                            .put("content", content)
                    )
                }

                val body = JSONObject()
                    .put("model", settings.apiModel)
                    .put("messages", messages)
                    .put("temperature", 0.8)
                    // 此前 300：回复稍长就被截断；推理类模型的"思考"也计入该额度，
                    // 全烧完后 content 返回 JSON null → 聊天里出现 "null"
                    // 1024 → 2048：让"写多一点"的长内容（作文/故事/代码）能完整输出
                    .put("max_tokens", 2048)
                    // 流式输出：边生成边上屏，是"反应特别慢"体感问题的根本解法
                    .put("stream", true)

                val endpoint = endpointFor(settings.apiBaseUrl)
                val key = cleanKey(settings.apiKey)
                try {
                    streamChat(endpoint, key, body, onDelta)
                } catch (e: SocketTimeoutException) {
                    // 部分网关对 stream 支持差（首包就超时）：换非流式重试一次，
                    // 失败再把原异常抛出去显示人话错误
                    val fb = JSONObject(body.toString()).put("stream", false)
                    streamChat(endpoint, key, fb, onDelta)
                }
            }

        /**
         * 流式请求（SSE）：边读边回调 [onDelta]，返回完整文本。
         * 兼容处理：
         *  - 端点不支持 stream（返回整段 application/json）→ 按普通响应解析，一次性回调；
         *  - 中途断流但已有部分文本 → 返回部分文本 + 截断提示，不让已生成内容白等；
         *  - 无任何输出且 finish_reason=length → 人话提示"额度被思考耗尽"（null 问题根治后的指引）。
         */
        private fun streamChat(
            endpoint: String,
            key: String,
            body: JSONObject,
            onDelta: (String) -> Unit
        ): String {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 120000   // 流式下每次收到数据都会重置；慢服务最长等 2 分钟静默
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream, application/json")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    throw RuntimeException(
                        "HTTP $code：${friendlyHttp(code)}${if (err.isNotBlank()) "\n服务返回：${err.take(160)}" else ""}"
                    )
                }
                val contentType = conn.contentType ?: ""
                val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
                return if (contentType.contains("text/event-stream")) {
                    readSse(reader, onDelta)
                } else {
                    // 端点不支持流式（忽略 stream 参数，返回整段 JSON）
                    extractNonStreamContent(reader.use { it.readText() }).also(onDelta)
                }
            } finally {
                conn.disconnect()
            }
        }

        /** SSE 流式读取：逐行解析 "data: {...}"，把 delta.content 片段实时回调 */
        private fun readSse(reader: java.io.BufferedReader, onDelta: (String) -> Unit): String {
            val sb = StringBuilder()
            var finishReason: String? = null
            var sawData = false
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith(":") || !t.startsWith("data:")) continue
                    sawData = true
                    val data = t.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    chunk.optJSONObject("error")?.let { err ->
                        throw RuntimeException("API 错误：${err.safeString("message") ?: "未知错误"}")
                    }
                    val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    choice.safeString("finish_reason")?.let { finishReason = it }
                    val delta = choice.optJSONObject("delta")
                        ?: choice.optJSONObject("message")   // 个别端点流式里直接发整条 message
                    val piece = delta?.safeString("content")
                    if (!piece.isNullOrEmpty()) {
                        sb.append(piece)
                        onDelta(piece)
                    }
                    // reasoning_content（思维链）刻意不展示不累计
                }
            } catch (e: java.io.IOException) {
                if (sb.isNotEmpty()) {
                    // 断连/超时但已收到部分文本：展示已有部分 + 提示，比全部丢弃友好
                    return sb.toString() + "\n\n（网络异常中断，重发消息可继续）"
                }
                throw e
            }
            if (sb.isEmpty()) {
                throw RuntimeException(
                    when {
                        finishReason == "length" ->
                            "模型没有输出正文：回复额度（max_tokens）全被模型\"思考\"耗尽。" +
                                    "推理模型（deepseek-reasoner、o1 系列等）会这样，请在设置里换成普通对话模型"
                        !sawData ->
                            "流式响应为空（端点可能不支持流式输出），请更换接口地址或切换引擎后重试"
                        else ->
                            "API 返回内容为空${if (finishReason != null) "（finish_reason=$finishReason）" else ""}"
                    }
                )
            }
            return sb.toString()
        }

        /** 非流式响应解析（端点不支持 stream 时走这里） */
        private fun extractNonStreamContent(text: String): String {
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw RuntimeException("响应不是合法 JSON：${text.take(120)}")
            }
            root.optJSONObject("error")?.let { err ->
                throw RuntimeException("API 错误：${err.safeString("message") ?: "未知错误"}")
            }
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
            val finish = choice?.safeString("finish_reason")
            val content = choice?.optJSONObject("message")?.safeString("content")
            if (content.isNullOrBlank()) {
                throw RuntimeException(
                    when (finish) {
                        "length" -> "模型没有输出正文：回复额度（max_tokens）全被模型\"思考\"耗尽，建议换成普通对话模型"
                        else -> "API 返回内容为空${if (finish != null) "（finish_reason=$finish）" else ""}"
                    }
                )
            }
            return content.trim()
        }

        private fun buildSystemPrompt(contact: QQFriend): String {
            val now = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date())
            if (contact.isGroup) {
                val members = contact.groupMembers.mapNotNull { Personas.byNumber(it) }
                return "这是 2008 年风格的 QQ 群聊「${contact.nickname}」。群成员：" +
                        members.joinToString("、") { it.nickname } +
                        "。请以其中 1~2 位成员的口吻回复，回复格式：\"昵称：内容\"，" +
                        "保持 2008 年网络聊天氛围，回复不超过 200 字。" +
                        members.joinToString("\n") { "人格设定 - ${it.nickname}：${it.systemPrompt} " } +
                        "\n当前时间：$now。"
            }
            val p = Personas.byKey(contact.personaKey)
            return "${p.systemPrompt}\n" +
                    "你现在是 QQ 聊天里的好友，正在和用户聊天。直接回复消息内容，不要加自己的名字前缀。\n" +
                    "你的能力：闲聊、讲故事、讲笑话、作诗、写作文/文案、翻译、出谜语、算数、科普、" +
                    "角色扮演、情感陪聊、给建议等 —— 用户让你做什么就尽力做好。\n" +
                    "用户让你写长内容（作文、故事、段子、代码、攻略等）时放开写、认真完成，" +
                    "不要偷懒缩水；日常闲聊则自然简短，像真人打字。\n" +
                    "不要输出 markdown 标题/列表符号，可用换行和颜文字，像 2008 年的网友一样说话。\n" +
                    "当前时间：$now（回答时间/日期类问题时参考）。"
        }
    }
}
