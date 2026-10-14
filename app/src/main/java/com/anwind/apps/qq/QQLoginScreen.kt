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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.window.WindowContentScope
import kotlinx.coroutines.launch

/**
 * QQ2008 登录界面。
 *
 * 还原 2008 登录框布局：
 *  - 顶部蓝色横幅：企鹅 + QQ2008 logo 文案 + "群聚精彩 共享盛世"
 *  - 白色登录卡：QQ账号（历史下拉 + 申请账号）/ QQ密码（忘了密码?）/
 *    状态下拉 + 自动登录 / 查杀木马 · 设置 · 登录
 *  - 底部信息框：登录错误 / 情怀提示（对应原版"查杀木马"黄条位置）
 */
@Composable
fun QQLoginScreen(scope: WindowContentScope) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = LocalFocusManager.current
    val coroutine = rememberCoroutineScope()

    var number by remember { mutableStateOf(QQStore.loadSessionPrefs().lastNumber) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(QQStore.loadSessionPrefs().lastStatus) }
    var autoLogin by remember { mutableStateOf(QQStore.loadSessionPrefs().autoLogin) }
    var accounts by remember { mutableStateOf(QQStore.loadAccounts()) }
    var accountMenu by remember { mutableStateOf(false) }
    var statusMenu by remember { mutableStateOf(false) }
    var showRegister by remember { mutableStateOf(false) }
    var showForgot by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // 窗口标题对齐原版（runCatching：窗口创建早期标题回调异常不应炸掉整个 app）
    LaunchedEffect(Unit) { runCatching { scope.onTitleChange("QQ用户登录") } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(QQ2008.panelBrush())
            .verticalScroll(rememberScrollState())
            .padding(10.dp)
    ) {
        // ===== 顶部横幅 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(QQ2008.titleBrush(), RoundedCornerShape(6.dp))
                .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "🐧", fontSize = 34.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "QQ",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "2008",
                        color = Color(0xFFFFE95C),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                Text(
                    text = "群聚精彩  共享盛世",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "AnWind网",
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(Color(0x33FFFFFF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(10.dp))

        // ===== 登录卡片 =====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(6.dp))
                .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(6.dp))
                .padding(14.dp)
        ) {
            // --- QQ账号 ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("QQ账号", color = QQ2008.TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box {
                    Row(
                        modifier = Modifier
                            .width(150.dp)
                            .height(26.dp)
                            .background(Color.White, RoundedCornerShape(3.dp))
                            .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(3.dp))
                            .clickable { accountMenu = true }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            number,
                            fontSize = 13.sp,
                            color = QQ2008.TextMain,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▾", color = QQ2008.TextSub, fontSize = 11.sp)
                    }
                    DropdownMenu(
                        expanded = accountMenu,
                        onDismissRequest = { accountMenu = false }
                    ) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text("${acc.number}  ${acc.nickname}", fontSize = 13.sp) },
                                onClick = {
                                    number = acc.number
                                    accountMenu = false
                                }
                            )
                        }
                        if (accounts.isNotEmpty()) {
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("清除历史", fontSize = 13.sp, color = Color.Gray) },
                                onClick = {
                                    // 仅清空输入框，不删账号
                                    number = ""
                                    accountMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "申请账号",
                    color = Color(0xFF0B62C4),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { showRegister = true }
                )
            }
            Spacer(Modifier.height(10.dp))

            // --- QQ密码 ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("QQ密码", color = QQ2008.TextMain, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(150.dp)
                        .height(26.dp)
                        .background(Color.White, RoundedCornerShape(3.dp))
                        .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = TextStyle(fontSize = 13.sp, color = QQ2008.TextMain),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (password.isEmpty()) {
                        Text("请输入密码", fontSize = 12.sp, color = Color(0xFFB8C6D4))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "忘了密码?",
                    color = Color(0xFF0B62C4),
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { showForgot = true }
                )
            }
            Spacer(Modifier.height(12.dp))

            // --- 状态 / 自动登录 ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(50.dp))
                Text("状态：", color = QQ2008.TextMain, fontSize = 13.sp)
                Box {
                    Row(
                        modifier = Modifier
                            .clickable { statusMenu = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QqStatusDot(status, 10.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(status.label, fontSize = 13.sp, color = QQ2008.TextMain)
                        Text("▾", fontSize = 10.sp, color = QQ2008.TextSub)
                    }
                    DropdownMenu(
                        expanded = statusMenu,
                        onDismissRequest = { statusMenu = false }
                    ) {
                        QQStatus.entries.forEach { s ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        QqStatusDot(s, 9.dp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(s.label, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    status = s
                                    statusMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { autoLogin = !autoLogin }
                ) {
                    QqCheckbox(checked = autoLogin)
                    Spacer(Modifier.width(4.dp))
                    Text("自动登录", fontSize = 13.sp, color = QQ2008.TextMain)
                }
            }
            Spacer(Modifier.height(14.dp))

            // --- 按钮行 ---
            Row(verticalAlignment = Alignment.CenterVertically) {
                QqBeigeButton("🛡 查杀木马", { }, enabled = false, height = 28.dp, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                QqBeigeButton("设置 ▾", { showSettings = true }, height = 28.dp, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                QqBeigeButton(
                    text = "登 录",
                    enabled = !QQSession.loggingIn && number.isNotBlank(),
                    height = 32.dp,
                    fontSize = 15.sp,
                    onClick = {
                        focus.clearFocus()
                        coroutine.launch {
                            val ok = QQSession.performLogin(number, password, status, autoLogin)
                            if (ok) {
                                QQSounds.login()
                                accounts = QQStore.loadAccounts()
                            }
                        }
                    }
                )
            }

            // --- 登录中 ---
            if (QQSession.loggingIn) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = QQ2008.TitleBottom
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在登录，请稍候……", fontSize = 12.sp, color = QQ2008.TextSub)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ===== 底部信息框（原版木马警告条位置） =====
        val err = QQSession.loginError
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (err != null) Brush.verticalGradient(listOf(Color(0xFFFFF3F3), Color(0xFFFFE2E2)))
                    else Brush.verticalGradient(listOf(Color(0xFFFFFBF0), Color(0xFFFFF3D9))),
                    RoundedCornerShape(6.dp)
                )
                .border(
                    1.dp,
                    if (err != null) Color(0xFFE0A0A0) else Color(0xFFE2CE9E),
                    RoundedCornerShape(6.dp)
                )
                .padding(10.dp)
        ) {
            Text(
                text = err ?: "提示：本窗口绝不弹出广告弹窗。账号仅保存在本机，"
                        + "输入新号码即自动开户；AI 好友全天在线，随时可以聊天。",
                color = if (err != null) Color(0xFF9C2B2B) else Color(0xFF8A6D3B),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }
    }

    // ===== 弹窗 =====
    if (showRegister) {
        QQRegisterDialog(
            onDismiss = { showRegister = false },
            onRegistered = { acc ->
                showRegister = false
                number = acc.number
                password = ""
                accounts = QQStore.loadAccounts()
            }
        )
    }
    if (showForgot) {
        QQInfoDialog(
            title = "忘了密码?",
            text = "本版本账号数据只保存在本机（filesDir/qq/accounts.json）。\n\n" +
                    "忘记密码时，可以换一个新号码重新申请账号；\n" +
                    "或在系统设置中清除 QQ 数据后重来。",
            onDismiss = { showForgot = false }
        )
    }
    if (showSettings) {
        QQSettingsDialog(onDismiss = { showSettings = false })
    }
}

/**
 * 复选框（QQ2008 小方块勾选样式）。
 */
@Composable
fun QqCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .background(Color.White, RoundedCornerShape(2.dp))
            .border(1.dp, QQ2008.PanelBorder, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Text("✔", fontSize = 10.sp, color = Color(0xFF1B7E1B), maxLines = 1)
        }
    }
}
