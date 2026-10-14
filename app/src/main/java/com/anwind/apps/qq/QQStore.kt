package com.anwind.apps.qq

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * QQ2008 for AnWind —— 本地持久化（JSON 文件）。
 *
 * 目录：context.filesDir/qq/
 *  - accounts.json        全部本地账号
 *  - session.json         最后登录账号 / 自动登录
 *  - settings.json        AI 与界面设置（全局共享）
 *  - friends_<号码>.json   每账号的好友列表
 *  - history_<号码>.json   每账号的全部聊天记录（按会话分组）
 *
 * 全部方法同步阻塞 IO，调用方需在 Dispatchers.IO 上执行（UI 层一律包
 * withContext 或经 [QQSession] 的后台协程）。对象级锁保证并发写安全。
 */
object QQStore {

    private const val MAX_HISTORY_PER_CHAT = 500   // 每会话最多保留条数

    private lateinit var dir: File
    private val lock = Any()

    /** Application.onCreate 时调用一次（由 QQApp 首次使用前保证触发） */
    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "qq").apply { mkdirs() }
    }

    // ==================== 通用读写 ====================

    private fun file(name: String): File = File(dir, name)

    private fun readJson(name: String): JSONObject? = runCatching {
        val f = file(name)
        if (!f.exists()) return null
        JSONObject(f.readText())
    }.getOrNull()

    private fun writeJson(name: String, obj: JSONObject) {
        runCatching {
            val tmp = file("$name.tmp")
            tmp.writeText(obj.toString())
            if (file(name).exists()) file(name).delete()
            tmp.renameTo(file(name))
        }
    }

    // ==================== 账号 ====================

    fun loadAccounts(): MutableList<QQAccount> = synchronized(lock) {
        runCatching {
            val arr = readJson("accounts.json")?.optJSONArray("list") ?: return mutableListOf()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { QQAccount.fromJson(it) }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun saveAccounts(accounts: List<QQAccount>) = synchronized(lock) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        writeJson("accounts.json", JSONObject().put("list", arr))
    }

    /** 找账号（按号码） */
    fun findAccount(number: String): QQAccount? =
        loadAccounts().firstOrNull { it.number == number }

    /**
     * 注册新账号：号码自动生成（2008 开头 9 位），返回创建好的账号。
     */
    fun registerAccount(nickname: String, password: String, avatar: String, signature: String): QQAccount {
        val accounts = loadAccounts()
        val number = generateNumber(accounts)
        val acc = QQAccount(
            number = number,
            passwordHash = QQCrypto.sha256(password),
            nickname = nickname.ifBlank { "QQ用户${number.takeLast(4)}" },
            avatar = avatar,
            signature = signature.ifBlank { "群聚精彩，共享盛世" }
        )
        accounts.add(acc)
        saveAccounts(accounts)
        return acc
    }

    /** 更新账号资料（昵称/头像/签名/状态/密码） */
    fun updateAccount(updated: QQAccount) {
        val accounts = loadAccounts()
        val idx = accounts.indexOfFirst { it.number == updated.number }
        if (idx >= 0) {
            accounts[idx] = updated
            saveAccounts(accounts)
        }
    }

    private fun generateNumber(existing: List<QQAccount>): String {
        val used = existing.map { it.number }.toSet()
        while (true) {
            val n = "2008" + (100000..999999).random()
            if (n !in used) return n
        }
    }

    // ==================== 登录会话 ====================

    fun loadSessionPrefs(): QQSessionPrefs =
        readJson("session.json")?.let { QQSessionPrefs.fromJson(it) } ?: QQSessionPrefs()

    fun saveSessionPrefs(prefs: QQSessionPrefs) =
        writeJson("session.json", prefs.toJson())

    // ==================== 设置 ====================

    fun loadSettings(): QQSettings =
        readJson("settings.json")?.let { QQSettings.fromJson(it) } ?: QQSettings()

    fun saveSettings(settings: QQSettings) =
        writeJson("settings.json", settings.toJson())

    // ==================== 好友 ====================

    fun loadFriends(account: String): MutableList<QQFriend> = synchronized(lock) {
        runCatching {
            val arr = readJson("friends_$account.json")?.optJSONArray("list") ?: return mutableListOf()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { QQFriend.fromJson(it) }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun saveFriends(account: String, friends: List<QQFriend>) = synchronized(lock) {
        val arr = JSONArray()
        friends.forEach { arr.put(it.toJson()) }
        writeJson("friends_$account.json", JSONObject().put("list", arr))
    }

    /** 添加好友（已存在则忽略），返回是否新增 */
    fun addFriend(account: String, friend: QQFriend): Boolean = synchronized(lock) {
        val list = loadFriends(account)
        if (list.any { it.number == friend.number }) return false
        list.add(friend)
        saveFriends(account, list)
        true
    }

    fun removeFriend(account: String, number: String): Boolean = synchronized(lock) {
        val list = loadFriends(account)
        val removed = list.removeAll { it.number == number }
        if (removed) saveFriends(account, list)
        removed
    }

    fun updateFriend(account: String, friend: QQFriend): Boolean = synchronized(lock) {
        val list = loadFriends(account)
        val idx = list.indexOfFirst { it.number == friend.number }
        if (idx < 0) return false
        list[idx] = friend
        saveFriends(account, list)
        true
    }

    // ==================== 聊天记录 ====================

    /** 返回 每会话最后一条消息时间 + 消息列表 的全量结构 */
    private fun loadHistoryRaw(account: String): MutableMap<String, MutableList<QQMessage>> {
        val result = linkedMapOf<String, MutableList<QQMessage>>()
        runCatching {
            val root = readJson("history_$account.json") ?: return result
            val chats = root.optJSONObject("chats") ?: return result
            val keys = chats.keys()
            for (chatId in keys) {
                val arr = chats.optJSONArray(chatId) ?: continue
                val msgs = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { QQMessage.fromJson(it) }
                }.toMutableList()
                result[chatId] = msgs
            }
        }
        return result
    }

    private fun saveHistoryRaw(account: String, chats: Map<String, List<QQMessage>>) = synchronized(lock) {
        val obj = JSONObject()
        chats.forEach { (id, msgs) ->
            val arr = JSONArray()
            msgs.takeLast(MAX_HISTORY_PER_CHAT).forEach { arr.put(it.toJson()) }
            obj.put(id, arr)
        }
        writeJson("history_$account.json", JSONObject().put("chats", obj))
    }

    /** 读单个会话的消息（时间升序） */
    fun loadChat(account: String, chatId: String): List<QQMessage> =
        loadHistoryRaw(account)[chatId] ?: emptyList()

    /** 追加消息（自动裁剪到上限） */
    fun appendMessage(account: String, message: QQMessage) = synchronized(lock) {
        val chats = loadHistoryRaw(account)
        val list = chats.getOrPut(message.chatId) { mutableListOf() }
        list.add(message)
        chats[message.chatId] = list
        saveHistoryRaw(account, chats)
    }

    /** 每个会话的最后一条消息（用于最近联系人 / 预览） */
    fun lastMessageOf(account: String, chatId: String): QQMessage? =
        loadChat(account, chatId).lastOrNull()

    /** 会话按最后消息时间倒序 */
    fun recentChatIds(account: String): List<String> =
        loadHistoryRaw(account)
            .mapNotNull { (id, msgs) -> msgs.lastOrNull()?.let { id to it.time } }
            .sortedByDescending { it.second }
            .map { it.first }

    /** 清空某会话记录（聊天窗口右键"清空记录"用） */
    fun clearChat(account: String, chatId: String) = synchronized(lock) {
        val chats = loadHistoryRaw(account)
        if (chats.remove(chatId) != null) saveHistoryRaw(account, chats)
    }
}
