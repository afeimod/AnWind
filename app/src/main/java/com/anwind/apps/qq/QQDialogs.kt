package com.anwind.apps.qq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * QQ 通用弹窗集合：注册账号 / 系统设置(AI) / 查找好友 / 个人资料 / 信息提示。
 *
 * 弹窗统一 QQ2008 风格：蓝色标题条 + 浅蓝面板 + 米黄按钮。
 */

/** 弹窗骨架：蓝色标题条 + 内容，全屏遮罩，点遮罩关闭 */
@Composable
fun QQDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .background(QQ2008.panelBrush(), RoundedCornerShape(6.dp))
                .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                    indication = null
                ) { }   // 消费点击，防止穿透到遮罩导致误关闭
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(QQ2008.titleBrush())
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🐧", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "✕", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(4.dp)
                )
            }
            Column(
                modifier = Modifier
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                content()
            }
        }
    }
}

/** 白底输入行 */
@Composable
private fun QQFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    hint: String = ""
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = QQ2008.TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(26.dp)
                .background(Color.White, RoundedCornerShape(3.dp))
                .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(fontSize = 13.sp, color = QQ2008.TextMain),
                modifier = Modifier.fillMaxWidth()
            )
            if (value.isEmpty() && hint.isNotEmpty()) {
                Text(hint, fontSize = 12.sp, color = Color(0xFFB8C6D4))
            }
        }
    }
}

// ==================== 申请账号 ====================

@Composable
fun QQRegisterDialog(
    onDismiss: () -> Unit,
    onRegistered: (QQAccount) -> Unit
) {
    var nickname by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("emoji:🐧") }
    val avatarChoices = listOf("🐧", "😺", "🐼", "🐸", "🐯", "🐰", "🦊", "🐨", "🐵", "⭐", "🌸", "🚀")

    QQDialogFrame(title = "申请账号", onDismiss = onDismiss) {
        Text(
            "免费申请一个本地 QQ 账号，号码自动分配（2008 开头）。",
            fontSize = 12.sp, color = QQ2008.TextSub, lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        QQFieldRow("昵 称", nickname, { nickname = it }, hint = "给自己取个拉风的名字")
        Spacer(Modifier.height(8.dp))
        QQFieldRow("密 码", password, { password = it }, isPassword = true, hint = "登录口令")
        Spacer(Modifier.height(8.dp))
        QQFieldRow("个 性", signature, { signature = it }, hint = "个性签名（可选）")
        Spacer(Modifier.height(12.dp))
        Text("选择头像：", fontSize = 13.sp, color = QQ2008.TextMain, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            avatarChoices.chunked(6).forEach { rowAvatars ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowAvatars.forEach { e ->
                        val selected = avatar == "emoji:$e"
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (selected) QQ2008.SelectedRow else Color.White,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (selected) QQ2008.TitleBottom else QQ2008.PanelBorder.copy(alpha = 0.5f),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { avatar = "emoji:$e" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(e, fontSize = 20.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Spacer(Modifier.weight(1f))
            QqBeigeButton("取 消", { onDismiss() }, Modifier.width(76.dp))
            Spacer(Modifier.width(10.dp))
            QqBeigeButton(
                "确 定",
                {
                    val acc = QQStore.registerAccount(nickname, password.ifBlank { "123456" }, avatar, signature)
                    onRegistered(acc)
                },
                Modifier.width(76.dp)
            )
        }
    }
}

// ==================== 信息提示 ====================

@Composable
fun QQInfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    QQDialogFrame(title = title, onDismiss = onDismiss) {
        Text(text, fontSize = 13.sp, color = QQ2008.TextMain, lineHeight = 20.sp)
        Spacer(Modifier.height(14.dp))
        Row {
            Spacer(Modifier.weight(1f))
            QqBeigeButton("我知道了", { onDismiss() }, Modifier.width(96.dp))
        }
    }
}

// ==================== 系统设置（AI 配置） ====================

@Composable
fun QQSettingsDialog(onDismiss: () -> Unit) {
    val s = QQSession.settings
    var engine by remember { mutableStateOf(s.aiEngine) }
    var baseUrl by remember { mutableStateOf(s.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.apiModel) }
    var autoReply by remember { mutableStateOf(s.autoReply) }
    var sound by remember { mutableStateOf(s.soundEnabled) }
    var musicOn by remember { mutableStateOf(s.musicEnabled) }
    var musicVol by remember { mutableStateOf(s.musicVolume.coerceIn(0, 100)) }
    var delayMs by remember { mutableStateOf(s.replyDelayMs.toString()) }
    val coroutine = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    QQDialogFrame(title = "系统设置", onDismiss = onDismiss) {
        // --- AI 引擎 ---
        Text("AI 引擎", fontSize = 13.sp, color = QQ2008.TextMain, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row {
            val localSel = engine == QQSettings.ENGINE_LOCAL
            QqBeigeButton(
                "本地内置（离线）",
                { engine = QQSettings.ENGINE_LOCAL },
                Modifier.weight(1f),
                enabled = !localSel
            )
            Spacer(Modifier.width(8.dp))
            val remoteSel = engine == QQSettings.ENGINE_REMOTE
            QqBeigeButton(
                "在线 API（OpenAI 兼容）",
                { engine = QQSettings.ENGINE_REMOTE },
                Modifier.weight(1f),
                enabled = !remoteSel
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (engine == QQSettings.ENGINE_LOCAL)
                "本地模式：无需网络与密钥，内置人格离线回复。"
            else "在线模式：任意 OpenAI 兼容接口（OpenAI / DeepSeek / 智谱 GLM / Ollama 等）。Key 只保存在本机。",
            fontSize = 11.sp, color = QQ2008.TextSub, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))

        // --- 在线 API 配置 ---
        QQFieldRow("接口", baseUrl, { baseUrl = it; testResult = null },
            hint = "api.deepseek.com 或完整 …/chat/completions 均可")
        Spacer(Modifier.height(8.dp))
        QQFieldRow("密钥", apiKey, { apiKey = it; testResult = null }, isPassword = true, hint = "sk-…")
        Spacer(Modifier.height(8.dp))
        QQFieldRow("模型", model, { model = it; testResult = null },
            hint = "gpt-4o-mini / deepseek-chat / glm-4-flash …")
        Spacer(Modifier.height(8.dp))

        // --- 测试连接（在线模式） ---
        if (engine == QQSettings.ENGINE_REMOTE) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QqBeigeButton(
                    if (testing) "测试中…" else "✉ 测试连接",
                    {
                        testing = true
                        testResult = null
                        coroutine.launch {
                            testResult = QQAi.testConnection(
                                QQSettings(
                                    aiEngine = QQSettings.ENGINE_REMOTE,
                                    apiBaseUrl = baseUrl,
                                    apiKey = apiKey,
                                    apiModel = model
                                )
                            )
                            testing = false
                        }
                    },
                    enabled = !testing, height = 26.dp, fontSize = 12.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "填 base 地址或完整接口都行，保存前可先测试",
                    fontSize = 10.sp, color = QQ2008.TextSub
                )
            }
            testResult?.let { r ->
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (r.startsWith("HTTP") || !r.startsWith("连接")) "✘ " else "✔ ") + r,
                    fontSize = 11.sp,
                    color = if (r.startsWith("连接成功")) Color(0xFF1B7E1B) else Color(0xFF9C2B2B),
                    lineHeight = 15.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(10.dp))

        // --- 行为开关 ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自动回复", fontSize = 13.sp, color = QQ2008.TextMain)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { autoReply = !autoReply }
            ) {
                QqCheckbox(checked = autoReply)
                Spacer(Modifier.width(4.dp))
                Text(if (autoReply) "开" else "关", fontSize = 12.sp, color = QQ2008.TextSub)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("消息提示音", fontSize = 13.sp, color = QQ2008.TextMain)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { sound = !sound }
            ) {
                QqCheckbox(checked = sound)
                Spacer(Modifier.width(4.dp))
                Text(if (sound) "开" else "关", fontSize = 12.sp, color = QQ2008.TextSub)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("游戏背景音乐", fontSize = 13.sp, color = QQ2008.TextMain)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { musicOn = !musicOn }
            ) {
                QqCheckbox(checked = musicOn)
                Spacer(Modifier.width(4.dp))
                Text(if (musicOn) "开" else "关", fontSize = 12.sp, color = QQ2008.TextSub)
            }
        }
        if (musicOn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音乐音量", fontSize = 12.sp, color = QQ2008.TextSub)
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = musicVol / 100f,
                    onValueChange = { musicVol = (it * 100).toInt().coerceIn(0, 100) },
                    colors = SliderDefaults.colors(
                        thumbColor = QQ2008.TitleBottom,
                        activeTrackColor = QQ2008.TitleBottom,
                        inactiveTrackColor = Color(0xFFBFD8EA)
                    ),
                    modifier = Modifier.weight(1f).height(20.dp)
                )
                Text("$musicVol%", fontSize = 11.sp, color = QQ2008.TextSub, modifier = Modifier.width(34.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        QQFieldRow("回复延迟", delayMs, { delayMs = it.filter { c -> c.isDigit() }.take(5) }, hint = "毫秒")
        Spacer(Modifier.height(14.dp))

        Row {
            Spacer(Modifier.weight(1f))
            QqBeigeButton("取 消", { onDismiss() }, Modifier.width(76.dp))
            Spacer(Modifier.width(10.dp))
            QqBeigeButton(
                "保 存",
                {
                    QQSession.saveSettings(
                        QQSession.settings.copy(
                            aiEngine = engine,
                            apiBaseUrl = baseUrl.ifBlank { QQSettings().apiBaseUrl },
                            apiKey = apiKey.trim(),
                            apiModel = model.ifBlank { QQSettings().apiModel },
                            autoReply = autoReply,
                            soundEnabled = sound,
                            replyDelayMs = delayMs.toIntOrNull() ?: 900,
                            musicEnabled = musicOn,
                            musicVolume = musicVol
                        )
                    )
                    onDismiss()
                },
                Modifier.width(76.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "QQ2008 for AnWind v1.0 —— 暂时内置 AI，服务器版敬请期待。",
            fontSize = 10.sp, color = QQ2008.TextSub,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
    }
}

// ==================== 查找好友 ====================

@Composable
fun QQFindDialog(onDismiss: () -> Unit) {
    var keyword by remember { mutableStateOf("") }
    // 查找范围：全部内置 AI 人格 + 群
    val pool = remember {
        QQAi.Personas.all.map { QQAi.Personas.toFriend(it) } + QQAi.Personas.groupFriend()
    }
    val result = if (keyword.isBlank()) pool else pool.filter {
        it.nickname.contains(keyword, ignoreCase = true) || it.number.contains(keyword)
    }

    QQDialogFrame(title = "查找 - 联系人/群", onDismiss = onDismiss) {
        Text(
            "输入昵称或号码，看看谁在线上（全部为 AI 联系人，秒回）。",
            fontSize = 12.sp, color = QQ2008.TextSub
        )
        Spacer(Modifier.height(10.dp))
        QQFieldRow("关 键", keyword, { keyword = it }, hint = "如：小冰 / 10002 / 茶馆")
        Spacer(Modifier.height(10.dp))
        // 结果列表
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            result.forEach { f ->
                val added = QQSession.friends.containsKey(f.number)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(4.dp))
                        .border(1.dp, QQ2008.PanelBorder.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QqAvatar(f.avatar, 34.dp, f.status)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (f.isGroup) "[群] ${f.nickname}" else f.nickname,
                                color = QQ2008.nickColor(f.number),
                                fontSize = 13.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("(${f.number})", fontSize = 11.sp, color = QQ2008.TextSub)
                        }
                        Text(f.signature, fontSize = 11.sp, color = QQ2008.TextSub, maxLines = 1)
                    }
                    QqBeigeButton(
                        if (added) "已添加" else "+ 加好友",
                        {
                            if (!added) {
                                QQSession.addFriend(f)
                                QQSession.appendSystemMessage(
                                    f.number,
                                    "你们已经成为好友，现在可以开始聊天了。"
                                )
                            }
                        },
                        Modifier.width(84.dp),
                        enabled = !added,
                        height = 26.dp,
                        fontSize = 12.sp
                    )
                }
            }
            if (result.isEmpty()) {
                Text("没有找到匹配的联系人", fontSize = 12.sp, color = QQ2008.TextSub)
            }
        }
    }
}

// ==================== 个人资料 ====================

@Composable
fun QQProfileDialog(onDismiss: () -> Unit) {
    val acc = QQSession.account ?: return
    var nickname by remember { mutableStateOf(acc.nickname) }
    var signature by remember { mutableStateOf(acc.signature) }
    var avatar by remember { mutableStateOf(acc.avatar) }
    val avatarChoices = listOf("🐧", "😺", "🐼", "🐸", "🐯", "🐰", "🦊", "🐨", "🐵", "⭐", "🌸", "🚀")

    QQDialogFrame(title = "个人资料", onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QqAvatar(avatar, 44.dp, null)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("QQ号码：${acc.number}", fontSize = 13.sp, color = QQ2008.TextMain)
                Text("注册时间：${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(acc.createdAt))}",
                    fontSize = 11.sp, color = QQ2008.TextSub)
            }
        }
        Spacer(Modifier.height(10.dp))
        QQFieldRow("昵 称", nickname, { nickname = it })
        Spacer(Modifier.height(8.dp))
        QQFieldRow("签 名", signature, { signature = it })
        Spacer(Modifier.height(12.dp))
        Text("更换头像：", fontSize = 13.sp, color = QQ2008.TextMain, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            avatarChoices.chunked(6).forEach { rowAvatars ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    rowAvatars.forEach { e ->
                        val selected = avatar == "emoji:$e"
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (selected) QQ2008.SelectedRow else Color.White,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    1.dp,
                                    if (selected) QQ2008.TitleBottom else QQ2008.PanelBorder.copy(alpha = 0.5f),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { avatar = "emoji:$e" },
                            contentAlignment = Alignment.Center
                        ) { Text(e, fontSize = 20.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Spacer(Modifier.weight(1f))
            QqBeigeButton("取 消", { onDismiss() }, Modifier.width(76.dp))
            Spacer(Modifier.width(10.dp))
            QqBeigeButton(
                "保 存",
                {
                    QQSession.updateProfile(
                        nickname = nickname.ifBlank { acc.nickname },
                        signature = signature,
                        avatar = avatar,
                        status = acc.status
                    )
                    onDismiss()
                },
                Modifier.width(76.dp)
            )
        }
    }
}

// ==================== 好友备注 ====================

@Composable
fun QQRemarkDialog(
    friend: QQFriend,
    onDismiss: () -> Unit
) {
    var remark by remember { mutableStateOf(friend.remark) }
    QQDialogFrame(title = "修改备注 - ${friend.nickname}", onDismiss = onDismiss) {
        QQFieldRow("备 注", remark, { remark = it }, hint = "留空恢复显示昵称")
        Spacer(Modifier.height(14.dp))
        Row {
            Spacer(Modifier.weight(1f))
            QqBeigeButton("取 消", { onDismiss() }, Modifier.width(76.dp))
            Spacer(Modifier.width(10.dp))
            QqBeigeButton(
                "确 定",
                {
                    QQSession.updateFriendRemark(friend.number, remark.trim())
                    onDismiss()
                },
                Modifier.width(76.dp)
            )
        }
    }
}
