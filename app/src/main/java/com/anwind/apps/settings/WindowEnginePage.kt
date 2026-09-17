package com.anwind.apps.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anwind.core.desktop.FreeformCompat
import com.anwind.core.theme.LocalWinTheme
import com.anwind.util.L

/**
 * v2.24.0：设置 → 桌面窗口（窗口化引擎状态 / 修复开关 / 诊断报告）。
 *
 * 背景：四轮用户实测反馈（"厂商小窗" → "只全屏"）证明，在国产 ROM 上
 * 引擎能否生效取决于大量设备侧事实（权限、系统开关、ROM 策略层、任务
 * 既有状态……）。本页把这些事实**全部摊开**：
 *
 * - 引擎状态卡：权限 / 系统开关读回 / MIUI 检测 / 隐藏 API 豁免，
 *   每项红绿分明；
 * - 修复开关：卡全屏自动修复（CLEAR_TASK 重建任务）、总是新任务；
 * - 分 ROM 指引：按制造商给出开发者选项开关名；
 * - 一键复制诊断报告：用户把文本发回来即可精确定位，不再盲猜。
 */
@Composable
internal fun WindowEngineSection() {
    val theme = LocalWinTheme.current
    val context = LocalContext.current
    val appContext = context.applicationContext

    // 进入页面即触发一次能力探测（含设置写入，已授权时毫秒级幂等）
    LaunchedEffect(Unit) {
        runCatching { FreeformCompat.ensureAvailable(appContext) }
    }

    var refreshTick by remember { mutableStateOf(0) }
    val autoFix = remember(refreshTick) { FreeformCompat.isAutoFixStuckTask(appContext) }
    val alwaysNew = remember(refreshTick) { FreeformCompat.isAlwaysNewTask(appContext) }
    val lastLaunch by FreeformCompat.lastLaunch.collectAsState()

    fun readGlobal(key: String) = runCatching {
        AndroidSettings.Global.getInt(appContext.contentResolver, key, -1)
    }.getOrDefault(-1)

    // 各状态项（remember + refreshTick 驱动手动刷新；注意 remember 必须直接
    // 出现在 @Composable 上下文，不能封装进未标注 @Composable 的局部函数）
    val secureGranted = remember(refreshTick) { FreeformCompat.hasSecureSettings(appContext) }
    val overlayGranted = remember(refreshTick) { FreeformCompat.hasOverlayPermission(appContext) }
    val isLauncher = remember(refreshTick) { FreeformCompat.isDefaultLauncher(appContext) }
    val miui = remember(refreshTick) { FreeformCompat.probeMiuiClasses() }
    val hiddenLifted = remember(refreshTick) { FreeformCompat.HiddenApi.isLifted }
    val ffOn = remember(refreshTick) { readGlobal("enable_freeform_support") == 1 }
    val frOn = remember(refreshTick) { readGlobal("force_resizable_activities") == 1 }
    val nmOn = remember(refreshTick) { readGlobal("enable_non_resizable_multi_window") == 1 }

    SectionHeader(L("桌面窗口"), L("手机应用以电脑窗口形态运行的引擎状态与修复"))

    // ===== 引擎状态 =====
    SettingsBlock(L("引擎状态")) {
        StatusRow(L("悬浮窗权限"), overlayGranted, L("设置 → 应用 → AnWind → 显示在其他应用上层"))
        StatusRow(L("ADB 写设置权限"), secureGranted, L("adb shell pm grant com.anwind android.permission.WRITE_SECURE_SETTINGS"))
        StatusRow(L("自由窗口 (enable_freeform_support)"), ffOn, L("开发者选项「启用自由窗口」"))
        StatusRow(L("强制可调整大小"), frOn, L("开发者选项「强制将活动设为可调整大小」"))
        StatusRow(L("允许不可调整大小多窗口"), nmOn, L("Android 13+ 开发者选项第二个多窗口开关"))
        StatusRow(L("默认 Launcher"), isLauncher, L("影响：能否自动检测并修复“只全屏”的既存任务"))
        if (miui) {
            StatusRow(L("MIUI / HyperOS 专有通道"), true, L("检测到小米系 framework，将优先以厂商专有参数启动大窗"))
        }
        StatusRow(L("隐藏 API 豁免"), hiddenLifted, L("VMRuntime 通道，用于访问厂商专有启动参数"))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    FreeformCompat.invalidateCache()
                    runCatching { FreeformCompat.ensureAvailable(appContext) }
                    refreshTick++
                },
                shape = RoundedCornerShape(6.dp)
            ) { Text(L("重新检测"), fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    val report = FreeformCompat.snapshot(appContext)
                    runCatching {
                        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("anwind-window-engine", report))
                        Toast.makeText(appContext, "诊断报告已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(appContext, "复制失败：${it.message}", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(6.dp)
            ) { Text(L("复制诊断报告"), fontSize = 12.sp) }
        }
    }

    // ===== 最近一次启动 =====
    SettingsBlock(L("最近一次启动")) {
        val lp = lastLaunch
        if (lp == null) {
            Text(
                L("本进程还没有从桌面启动过手机应用。打开开始菜单 → 手机应用，任选一个应用启动后回到这里查看结果。"),
                color = theme.secondaryTextColor, fontSize = 11.sp, lineHeight = 16.sp
            )
        } else {
            KeyValue(L("应用"), "${lp.label} (${lp.pkg})")
            KeyValue(L("策略"), lp.strategy)
            KeyValue(L("桌面级边界"), lp.bounds?.toString() ?: L("无"))
            KeyValue(L("重建任务 (CLEAR_TASK)"), if (lp.clearTask) L("是") else L("否"))
            KeyValue(
                L("启动后窗口模式"),
                when {
                    !lp.observed -> L("无法观测（AnWind 不是默认 Launcher）")
                    lp.observedWindowing == FreeformCompat.WINDOWING_MODE_FREEFORM ->
                        L("freeform ✓ 已是桌面窗口")
                    lp.observedWindowing == FreeformCompat.WINDOWING_MODE_FULLSCREEN ->
                        L("fullscreen ✗ 仍被系统全屏化")
                    else -> lp.observedWindowing.toString()
                }
            )
        }
    }

    // ===== 修复开关 =====
    SettingsBlock(L("修复开关")) {
        ToggleRow(
            title = L("卡全屏自动修复"),
            subtitle = L("检测到应用存在全屏任务时，以全新任务启动使窗口模式生效（推荐开启）"),
            checked = autoFix
        ) { v ->
            FreeformCompat.setAutoFixStuckTask(appContext, v)
            refreshTick++
        }
        Spacer(Modifier.height(6.dp))
        ToggleRow(
            title = L("总是以全新任务启动"),
            subtitle = L("每次启动都重建任务，代价是应用每次从头启动；仅作最后手段（自动修复无效时开启）"),
            checked = alwaysNew
        ) { v ->
            FreeformCompat.setAlwaysNewTask(appContext, v)
            refreshTick++
        }
    }

    // ===== 分 ROM 指引 =====
    val guidance = remember { FreeformCompat.romGuidance() }
    SettingsBlock(
        if (guidance.romName.isBlank()) L("开启指引（通用）")
        else L("开启指引（${guidance.romName}）")
    ) {
        Text(
            L("若引擎状态有未亮项，请在系统设置里打开对应开关后回到本页点「重新检测」："),
            color = theme.secondaryTextColor, fontSize = 11.sp, lineHeight = 16.sp
        )
        Spacer(Modifier.height(6.dp))
        guidance.toggles.forEach { t ->
            Text(
                "• $t",
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 11.sp, lineHeight = 16.sp
            )
        }
        if (guidance.extra.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(guidance.extra, color = theme.secondaryTextColor, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            L("ADB 一次性授权（授权后 AnWind 自动写入并维持全部开关）："),
            color = theme.secondaryTextColor, fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            FreeformCompat.ADB_GRANT_COMMAND,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = theme.accentColor,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(theme.windowBackgroundColor)
                .padding(8.dp)
        )
    }

    Spacer(Modifier.height(12.dp))
    InfoCard(
        L("说明：桌面窗口基于系统自由窗口（freeform）能力。窗口出现后，可拖动窗口边框/底栏调整大小；" +
            "在 MIUI/HyperOS 上小窗可拖动底部横条或角部把手放大。若应用仍被系统改写成小窗或全屏，" +
            "请点「复制诊断报告」并把内容反馈给开发者。")
    )
}

/** 状态行：绿 ✓ / 红 ✗ + 说明 */
@Composable
private fun StatusRow(title: String, ok: Boolean, hint: String) {
    val theme = LocalWinTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(18.dp)
        )
        Column {
            Text(
                title,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp
            )
            Text(hint, color = theme.secondaryTextColor, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

/** 键值行 */
@Composable
private fun KeyValue(key: String, value: String) {
    val theme = LocalWinTheme.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "$key：",
            color = theme.secondaryTextColor,
            fontSize = 11.sp,
            modifier = Modifier.width(120.dp)
        )
        Text(
            value,
            color = if (theme.isDark) Color.White else Color.Black,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 开关行 */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    val theme = LocalWinTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (theme.isDark) Color.White else Color.Black,
                fontSize = 12.sp
            )
            Text(subtitle, color = theme.secondaryTextColor, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
