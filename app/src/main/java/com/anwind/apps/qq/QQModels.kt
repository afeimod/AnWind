package com.anwind.apps.qq

import org.json.JSONObject

/**
 * QQ2008 for AnWind —— 数据模型层
 *
 * 设计说明：
 * - 全部持久化走 [QQStore]（filesDir/qq/ 下的 JSON 文件），不接入 Room：
 *   宿主 AppDatabase 目前 version=1 且被多个模块共用，新增表需要全链路
 *   迁移，风险过高；聊天数据体量小，JSON 文件完全够用。
 * - 网络层沿用项目惯例（KuwoMusicApi 模式）：HttpURLConnection + org.json，
 *   不引入任何第三方依赖。
 */

/**
 * QQ 在线状态（对齐 QQ2008 六态）。
 *
 * @param label  界面显示文本（状态下拉框 / 主面板昵称后缀）
 * @param dot    状态圆点颜色（ARGB）
 */
enum class QQStatus(val label: String, val dot: Long) {
    ONLINE("我在线上", 0xFF37C837),
    QME("Q我吧", 0xFFFFB400),
    AWAY("离开", 0xFFF0A830),
    BUSY("忙碌", 0xFFE05048),
    DND("请勿打扰", 0xFFCC332F),
    INVISIBLE("隐身", 0xFF9AA6B4);

    companion object {
        /** JSON 名 → 枚举（未知值回落 ONLINE） */
        fun fromName(name: String?): QQStatus =
            entries.firstOrNull { it.name == name } ?: ONLINE
    }
}

/** 消息类型 */
object QQMsgType {
    const val TEXT = "text"
    const val NUDGE = "nudge"      // 窗口抖动
    const val SYSTEM = "system"    // 系统提示（居中灰字）
}

/**
 * 本地账号。旧版 JSON 兼容：若存在明文 "password" 字段则登录时自动升级为哈希。
 */
data class QQAccount(
    val number: String,
    val passwordHash: String,
    val nickname: String,
    val avatar: String,          // "emoji:🐧" 形式
    val signature: String,
    val status: QQStatus = QQStatus.ONLINE,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("passwordHash", passwordHash)
        put("nickname", nickname)
        put("avatar", avatar)
        put("signature", signature)
        put("status", status.name)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): QQAccount {
            val stored = o.optString("passwordHash", "")
            // 兼容历史明文 password 字段（正常情况不会出现）
            val legacy = o.optString("password", "")
            return QQAccount(
                number = o.optString("number", ""),
                passwordHash = if (legacy.isNotEmpty()) QQCrypto.sha256(legacy) else stored,
                nickname = o.optString("nickname", "QQ用户"),
                avatar = o.optString("avatar", "emoji:🐧"),
                signature = o.optString("signature", ""),
                status = QQStatus.fromName(o.optString("status", "ONLINE")),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * 好友 / 群联系人。
 *
 * @param isGroup   true 表示这是一个群（聊天时走群聊 AI 逻辑）
 * @param isAi      是否 AI 联系人（当前版本所有联系人均为 AI，服务器版再扩展）
 * @param personaKey AI 人格键，见 [QQAi.Personas]
 */
data class QQFriend(
    val number: String,
    val nickname: String,
    val avatar: String,
    val signature: String,
    val status: QQStatus = QQStatus.ONLINE,
    val groupName: String = "我的好友",
    val isAi: Boolean = true,
    val personaKey: String = "assistant",
    val isGroup: Boolean = false,
    val groupMembers: List<String> = emptyList(),   // 群成员（QQ号）
    val remark: String = ""
) {
    /** 展示名：备注优先 */
    val displayName: String get() = remark.ifBlank { nickname }

    fun toJson(): JSONObject = JSONObject().apply {
        put("number", number)
        put("nickname", nickname)
        put("avatar", avatar)
        put("signature", signature)
        put("status", status.name)
        put("groupName", groupName)
        put("isAi", isAi)
        put("personaKey", personaKey)
        put("isGroup", isGroup)
        put("groupMembers", org.json.JSONArray(groupMembers))
        put("remark", remark)
    }

    companion object {
        fun fromJson(o: JSONObject): QQFriend = QQFriend(
            number = o.optString("number", ""),
            nickname = o.optString("nickname", "网友"),
            avatar = o.optString("avatar", "emoji:🐧"),
            signature = o.optString("signature", ""),
            status = QQStatus.fromName(o.optString("status", "ONLINE")),
            groupName = o.optString("groupName", "我的好友"),
            isAi = o.optBoolean("isAi", true),
            personaKey = o.optString("personaKey", "assistant"),
            isGroup = o.optBoolean("isGroup", false),
            groupMembers = buildList {
                val arr = o.optJSONArray("groupMembers") ?: return@buildList
                for (i in 0 until arr.length()) add(arr.optString(i))
            },
            remark = o.optString("remark", "")
        )
    }
}

/** 单条聊天消息 */
data class QQMessage(
    val id: Long,
    val chatId: String,        // 对方 QQ号 / 群号
    val senderId: String,
    val senderName: String,
    val text: String,
    val time: Long,
    val isSelf: Boolean,
    val type: String = QQMsgType.TEXT
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("chatId", chatId)
        put("senderId", senderId)
        put("senderName", senderName)
        put("text", text)
        put("time", time)
        put("isSelf", isSelf)
        put("type", type)
    }

    companion object {
        fun fromJson(o: JSONObject): QQMessage = QQMessage(
            id = o.optLong("id", 0L),
            chatId = o.optString("chatId", ""),
            senderId = o.optString("senderId", ""),
            senderName = o.optString("senderName", ""),
            text = o.optString("text", ""),
            time = o.optLong("time", 0L),
            isSelf = o.optBoolean("isSelf", false),
            type = o.optString("type", QQMsgType.TEXT)
        )
    }
}

/** AI / 后端设置 */
data class QQSettings(
    /** "local" = 内置离线 AI；"remote" = OpenAI 兼容在线 API */
    val aiEngine: String = ENGINE_LOCAL,
    val apiBaseUrl: String = "https://api.openai.com/v1/chat/completions",
    val apiKey: String = "",
    val apiModel: String = "gpt-4o-mini",
    val autoReply: Boolean = true,
    val replyDelayMs: Int = 900,
    val soundEnabled: Boolean = true
) {
    companion object {
        const val ENGINE_LOCAL = "local"
        const val ENGINE_REMOTE = "remote"

        fun fromJson(o: JSONObject): QQSettings = QQSettings(
            aiEngine = o.optString("aiEngine", ENGINE_LOCAL),
            apiBaseUrl = o.optString("apiBaseUrl", "https://api.openai.com/v1/chat/completions"),
            apiKey = o.optString("apiKey", ""),
            apiModel = o.optString("apiModel", "gpt-4o-mini"),
            autoReply = o.optBoolean("autoReply", true),
            replyDelayMs = o.optInt("replyDelayMs", 900),
            soundEnabled = o.optBoolean("soundEnabled", true)
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("aiEngine", aiEngine)
        put("apiBaseUrl", apiBaseUrl)
        put("apiKey", apiKey)
        put("apiModel", apiModel)
        put("autoReply", autoReply)
        put("replyDelayMs", replyDelayMs)
        put("soundEnabled", soundEnabled)
    }
}

/** 登录会话持久化（最后登录的账号 + 自动登录开关） */
data class QQSessionPrefs(
    val lastNumber: String = "",
    val autoLogin: Boolean = false,
    val lastStatus: QQStatus = QQStatus.ONLINE
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lastNumber", lastNumber)
        put("autoLogin", autoLogin)
        put("lastStatus", lastStatus.name)
    }

    companion object {
        fun fromJson(o: JSONObject): QQSessionPrefs = QQSessionPrefs(
            lastNumber = o.optString("lastNumber", ""),
            autoLogin = o.optBoolean("autoLogin", false),
            lastStatus = QQStatus.fromName(o.optString("lastStatus", "ONLINE"))
        )
    }
}

/** 简易哈希工具（本地密码摘要，非安全场景） */
object QQCrypto {
    fun sha256(text: String): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(text.hashCode().toString())
}
