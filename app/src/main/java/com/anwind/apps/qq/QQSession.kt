package com.anwind.apps.qq

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * QQ2008 for AnWind —— 全局会话（进程级单例）。
 *
 * 为什么不用窗口内状态：QQ 登录后主面板窗口与聊天窗口是两个独立
 * WindowState，聊天窗口由主面板打开（launchArgs["chat"]），登录状态、
 * 好友列表、未读数必须跨窗口共享 —— 因此统一放在这里。
 *
 * AI 回复协程运行在应用级 [aiScope] 上：即使聊天窗口被关闭，回复仍会
 * 送达并累计未读（对齐真实 QQ 的体验）。
 */
object QQSession {

    /** 应用级协程域：AI 回复不随窗口销毁而取消 */
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---------- 可观察状态（Compose snapshot） ----------

    /** 当前登录账号；null = 未登录（显示登录界面） */
    var account by mutableStateOf<QQAccount?>(null)
        private set

    var loggingIn by mutableStateOf(false)
        private set

    /** 登录失败信息（登录界面底部提示框显示） */
    var loginError by mutableStateOf<String?>(null)

    /** 好友列表（登录后加载；群也在列表内，isGroup=true） */
    val friends = mutableStateMapOf<String, QQFriend>()

    /** 未读消息数：chatId → 条数 */
    val unread = mutableStateMapOf<String, Int>()

    /** 各会话"对方正在输入"标记（聊天窗口显示） */
    val typing = mutableStateMapOf<String, Boolean>()

    /**
     * 内存消息缓存：chatId → 消息列表（时间升序，可观察）。
     * 聊天窗口直接观察此列表，新消息到达即时刷新；落库异步进行。
     */
    private val messageCache = mutableStateMapOf<String, SnapshotStateList<QQMessage>>()

    /** 最近一次事件时间戳（驱动最近联系人排序刷新） */
    var lastActivityTick by mutableStateOf(0L)
        private set

    /** 当前生效设置 */
    var settings by mutableStateOf(QQSettings())
        private set

    private var appContext: Context? = null

    /** QQ 包内异常落盘（filesDir/qq/crash.log），方便远程排查 */
    fun logCrash(where: String, e: Throwable) {
        runCatching {
            val ctx = appContext ?: return
            val f = File(ctx.filesDir, "qq/crash.log")
            f.appendText(
                "[${SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())}] $where: " +
                        "${e.javaClass.simpleName}: ${e.message}\n" +
                        e.stackTrace.take(10).joinToString("\n") { "  at $it" } + "\n\n"
            )
        }
    }

    /** 应用入口处调用（AnWindApp.onCreate 不侵入，改由 QQApp 首帧触发） */
    fun ensureInit(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // 整体兜底：这里运行在组合期，任何异常都会直接杀掉整个 app（启动闪退），
        // 因此全部套 runCatching，异常落盘 crash.log 供排查
        runCatching {
            QQStore.init(context.applicationContext)
            settings = QQStore.loadSettings()
            // 自动登录：异步执行，登录界面会先显示"正在登录"
            val prefs = QQStore.loadSessionPrefs()
            if (prefs.autoLogin && prefs.lastNumber.isNotBlank()) {
                val acc = QQStore.findAccount(prefs.lastNumber)
                if (acc != null) {
                    aiScope.launch {
                        // 保留用户勾选的自动登录开关（否则一次自动登录后开关就被关掉）
                        runCatching {
                            performLogin(acc.number, null, prefs.lastStatus, true)
                        }.onFailure { logCrash("autoLogin", it) }
                    }
                }
            }
        }.onFailure { logCrash("ensureInit", it) }
    }

    // ---------- 登录 / 退出 ----------

    /**
     * 登录。
     *
     * @param password 明文密码；null 表示"自动登录"（跳过校验，账号已存在）
     */
    suspend fun performLogin(
        number: String,
        password: String?,
        status: QQStatus,
        autoLogin: Boolean
    ): Boolean {
        val num = number.trim()
        if (num.isEmpty()) {
            loginError = "请输入 QQ 账号"
            return false
        }
        loggingIn = true
        loginError = null
        delay(600)   // 模拟 2008 年的登录等待，情怀拉满（放 runCatching 外，取消时正常向上传播）
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val existing = QQStore.findAccount(num)
                val acc: QQAccount? = when {
                    existing != null && password != null ->
                        if (QQCrypto.sha256(password) == existing.passwordHash) existing
                        else {
                            loginError = "密码不正确，请重新输入（忘了密码？可重新申请账号）"
                            null
                        }
                    existing != null -> existing
                    else -> {
                        // 全新号码：自动开户（2008 年注册 QQ 也是即注册即登录）
                        loginError = null
                        QQStore.registerAccount(
                            nickname = "QQ用户${num.takeLast(4)}",
                            password = password ?: "123456",
                            avatar = "emoji:🐧",
                            signature = "群聚精彩，共享盛世"
                        )
                    }
                }
                if (acc == null) return@runCatching false   // 密码错误等业务失败
                val updated = acc.copy(status = status)
                QQStore.updateAccount(updated)
                QQStore.saveSessionPrefs(
                    QQSessionPrefs(lastNumber = acc.number, autoLogin = autoLogin, lastStatus = status)
                )
                seedBuiltinContacts(acc.number)
                // 刷新会话
                friends.clear()
                QQStore.loadFriends(acc.number).forEach { friends[it.number] = it }
                unread.clear()
                typing.clear()
                messageCache.clear()
                account = updated
                true
            }.getOrElse { e ->
                logCrash("performLogin", e)
                loginError = "登录失败：${e.message ?: "未知错误"}"
                false
            }
        }
        loggingIn = false   // 所有路径统一复位（此前密码错误分支会永久卡在登录中）
        return ok
    }

    /** 首次登录：注入内置 AI 好友与怀旧茶馆群 */
    private fun seedBuiltinContacts(account: String) {
        val existing = QQStore.loadFriends(account)
        var dirty = false
        QQAi.Personas.all.forEach { p ->
            if (existing.none { it.number == p.number }) {
                existing.add(QQAi.Personas.toFriend(p)); dirty = true
            }
        }
        val group = QQAi.Personas.groupFriend()
        if (existing.none { it.number == group.number }) {
            existing.add(group); dirty = true
        }
        if (dirty) QQStore.saveFriends(account, existing)
    }

    /** 退出登录：清空会话，关闭全部 QQ 聊天窗口由调用方处理 */
    fun logout() {
        account = null
        friends.clear()
        unread.clear()
        typing.clear()
        messageCache.clear()
        QQStore.saveSessionPrefs(QQSessionPrefs())
    }

    /**
     * 取某会话的可观察消息列表（首次访问时从磁盘加载，升序）。
     */
    fun chatMessages(chatId: String): SnapshotStateList<QQMessage> {
        val acc = account
        return messageCache.getOrPut(chatId) {
            val list = mutableStateListOf<QQMessage>()
            if (acc != null) {
                list.addAll(QQStore.loadChat(acc.number, chatId))
            }
            list
        }
    }

    private fun cacheMessages(chatId: String, vararg msgs: QQMessage) {
        chatMessages(chatId).addAll(msgs)
    }

    // ---------- 资料 / 设置 ----------

    fun updateProfile(nickname: String, signature: String, avatar: String, status: QQStatus) {
        val acc = account ?: return
        val updated = acc.copy(nickname = nickname, signature = signature, avatar = avatar, status = status)
        account = updated
        aiScope.launch(Dispatchers.IO) { QQStore.updateAccount(updated) }
    }

    fun updateStatus(status: QQStatus) {
        val acc = account ?: return
        val updated = acc.copy(status = status)
        account = updated
        aiScope.launch(Dispatchers.IO) {
            QQStore.updateAccount(updated)
            QQStore.saveSessionPrefs(
                QQSessionPrefs(lastNumber = acc.number, autoLogin = loadAutoLogin(), lastStatus = status)
            )
        }
    }

    private fun loadAutoLogin(): Boolean = QQStore.loadSessionPrefs().autoLogin

    fun saveSettings(newSettings: QQSettings) {
        settings = newSettings
        aiScope.launch(Dispatchers.IO) { QQStore.saveSettings(newSettings) }
    }

    // ---------- 好友操作 ----------

    fun addFriend(friend: QQFriend): Boolean {
        val acc = account ?: return false
        val ok = QQStore.addFriend(acc.number, friend)
        if (ok) friends[friend.number] = friend
        return ok
    }

    fun removeFriend(number: String): Boolean {
        val acc = account ?: return false
        val ok = QQStore.removeFriend(acc.number, number)
        if (ok) friends.remove(number)
        return ok
    }

    fun updateFriendRemark(number: String, remark: String) {
        val acc = account ?: return
        val f = friends[number] ?: return
        val updated = f.copy(remark = remark)
        friends[number] = updated
        aiScope.launch(Dispatchers.IO) { QQStore.updateFriend(acc.number, updated) }
    }

    // ---------- 消息收发 ----------

    /** 发送一条消息并落库（UI 立即调用；AI 回复由 [requestAiReply] 单独触发） */
    fun sendText(chatId: String, text: String): QQMessage? {
        val acc = account ?: return null
        val msg = QQMessage(
            id = System.currentTimeMillis(),
            chatId = chatId,
            senderId = acc.number,
            senderName = acc.nickname,
            text = text,
            time = System.currentTimeMillis(),
            isSelf = true
        )
        cacheMessages(chatId, msg)
        aiScope.launch(Dispatchers.IO) { QQStore.appendMessage(acc.number, msg) }
        bumpActivity()
        return msg
    }

    fun sendNudge(chatId: String) {
        val acc = account ?: return
        val msg = QQMessage(
            id = System.currentTimeMillis(), chatId = chatId,
            senderId = acc.number, senderName = acc.nickname,
            text = "您给对方发送了一个窗口抖动", time = System.currentTimeMillis(),
            isSelf = true, type = QQMsgType.NUDGE
        )
        cacheMessages(chatId, msg)
        aiScope.launch(Dispatchers.IO) { QQStore.appendMessage(acc.number, msg) }
        bumpActivity()
    }

    /** 请求 AI 回复：后台执行，完成后落库 + 未读/声音由聊天界面观察 */
    fun requestAiReply(chatId: String) {
        val acc = account ?: return
        if (!settings.autoReply) return
        val contact = friends[chatId] ?: return
        typing[chatId] = true
        aiScope.launch {
            delay(settings.replyDelayMs.coerceAtLeast(300).toLong())
            val replyText = runCatching {
                // AI 上下文只保留普通文本消息（系统提示/抖动不参与）
                val history = withContext(Dispatchers.IO) {
                    QQStore.loadChat(acc.number, chatId).filter { it.type == QQMsgType.TEXT }
                }
                QQAi.backendFor(settings).reply(contact, history)
            }.getOrElse {
                "（AI 开小差了：${it.message?.take(160) ?: "未知错误"}）\n可在 系统设置 → AI 设置 中测试连接或切换引擎。"
            }
            typing[chatId] = false
            val reply = QQMessage(
                id = System.currentTimeMillis(),
                chatId = chatId,
                senderId = contact.number,
                senderName = contact.nickname,
                text = replyText,
                time = System.currentTimeMillis(),
                isSelf = false
            )
            cacheMessages(chatId, reply)
            withContext(Dispatchers.IO) { QQStore.appendMessage(acc.number, reply) }
            // 未读交给聊天窗口：窗口开着会立刻 clearUnread，关着则累计
            unread[chatId] = (unread[chatId] ?: 0) + 1
            bumpActivity()
        }
    }

    /** 清除某会话未读（打开聊天窗口时调用） */
    fun clearUnread(chatId: String) {
        if (unread[chatId] != null) unread[chatId] = 0
    }

    /** 清空某会话内存缓存（配合 QQStore.clearChat 使用） */
    fun clearChatCache(chatId: String) {
        messageCache.remove(chatId)
    }

    /** 打招呼（添加好友后自动发一条系统提示） */
    fun appendSystemMessage(chatId: String, text: String) {
        val acc = account ?: return
        val msg = QQMessage(
            id = System.currentTimeMillis(), chatId = chatId,
            senderId = "system", senderName = "系统",
            text = text, time = System.currentTimeMillis(),
            isSelf = false, type = QQMsgType.SYSTEM
        )
        cacheMessages(chatId, msg)
        aiScope.launch(Dispatchers.IO) { QQStore.appendMessage(acc.number, msg) }
        bumpActivity()
    }

    private fun bumpActivity() {
        lastActivityTick = System.currentTimeMillis()
    }
}
