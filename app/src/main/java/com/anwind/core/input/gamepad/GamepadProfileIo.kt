package com.anwind.core.input.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3.4.6（用户："虚拟手柄的保存和读取配置应该在悬浮球主菜单位置啊，
 * 不应该放在编辑界面啊，因为读取配置后才可以编辑对应配置啊"）：
 * `.vgp` 配置文件读写从「手柄设置面板顶栏」（编辑界面，v3.4.5 引入）
 * 迁移到**悬浮球主菜单**——先读取/保存配置，再进设置或编辑布局，
 * 操作层级与用户的实际流程一致。
 *
 * - **保存**：SAF CreateDocument（octet-stream），默认名
 *   `anwind-gamepad-MMdd-HHmm.vgp`；内容 = [GamepadController.toJson()]
 *   同一 JSON（版本字段内含）。
 * - **读取**：SAF OpenDocument（宽松 MIME —— SAF 对未注册的自定义
 *   后缀常报 octet-stream，多候选防滤空）；读入成功后立即持久化进
 *   DataStore（跨会话保留），随后可直接「编辑布局」微调。
 * - **结果提示**：[message]（"✅ 已保存 xxx.vgp" / "❌ …"）由宿主
 *   （悬浮球）以 [GamepadIoToast] 气泡展示，2.4s 自动消失 ——
 *   SAF 对话框关闭后悬浮球菜单也已收起，气泡挂在球旁仍可见。
 *
 * `.vgp` = Virtual GamePad 三字母缩写（用户指定）。
 */
class GamepadProfileIo internal constructor(
    private val saveLauncher: androidx.activity.compose.ManagedActivityResultLauncher<String, android.net.Uri?>,
    private val loadLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    private val messageState: MutableState<String?>
) {
    /** 最近一次保存/读取的结果提示（null = 无）。 */
    val message: State<String?> get() = messageState

    /** 调起 SAF 保存当前手柄布局（元素/映射/球位全套）为 .vgp。 */
    fun saveProfile() {
        saveLauncher.launch(defaultFileName())
    }

    /** 调起 SAF 选取 .vgp 并即时装载。 */
    fun loadProfile() {
        loadLauncher.launch(MIME_CANDIDATES)
    }

    /** 清除结果提示（气泡消失）。 */
    fun clearMessage() {
        messageState.value = null
    }

    companion object {
        /** .vgp 后缀（Virtual GamePad 三字母缩写，用户指定）。 */
        const val EXT = "vgp"

        /** SAF OpenDocument 的宽松 MIME 候选（防自定义后缀被滤空）。 */
        internal val MIME_CANDIDATES = arrayOf(
            "application/octet-stream", "application/json",
            "application/vnd.oasis.opendocument.text", "text/plain"
        )

        /** 默认保存文件名（含时间戳，避免 SAF 覆盖旧配置）。 */
        internal fun defaultFileName(): String =
            "anwind-gamepad-${
                java.text.SimpleDateFormat("MMdd-HHmm", java.util.Locale.US)
                    .format(java.util.Date())
            }.$EXT"
    }
}

/**
 * 悬浮球主菜单用的 .vgp 读写句柄（remember 化：SAF launcher 只创建一次，
 * 回调经协程作用域落盘/装载，结果写 [GamepadProfileIo.message]）。
 */
@Composable
fun rememberGamepadProfileIo(onPersistConfig: () -> Unit): GamepadProfileIo {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val message = remember { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(GamepadController.toJson().toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开输出流")
                    }.isSuccess
                }
                message.value = if (ok) {
                    "✅ 已保存 ${(uri.lastPathSegment ?: "配置").substringAfterLast('/')}"
                } else {
                    "❌ 保存失败（该位置不可写）"
                }
            }
        }
    }

    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            ins.readBytes().toString(Charsets.UTF_8)
                        } ?: ""
                    }.getOrDefault("")
                }
                val loaded = json.isNotBlank() &&
                    runCatching { GamepadController.loadFromJson(json); true }.getOrDefault(false)
                if (loaded) {
                    // 读入即持久化（跨会话保留）—— 读取后可直接「编辑布局」微调
                    onPersistConfig()
                    message.value =
                        "✅ 已读取 ${(uri.lastPathSegment ?: "配置").substringAfterLast('/')}"
                } else {
                    message.value = "❌ 无效 .vgp 文件"
                }
            }
        }
    }

    val io = remember { GamepadProfileIo(saveLauncher, loadLauncher, message) }
    // 结果提示 2.4s 自动消失
    LaunchedEffect(io.message.value) {
        if (io.message.value != null) {
            delay(2400)
            io.clearMessage()
        }
    }
    return io
}

/**
 * .vgp 保存/读取结果气泡（挂在悬浮球旁；深色胶囊 + 成功/失败双色）。
 */
@Composable
fun GamepadIoToast(message: String?, modifier: Modifier = Modifier) {
    if (message == null) return
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .shadow(6.dp, RoundedCornerShape(10.dp))
                .background(Color(0xE6101A24))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                message,
                color = if (message.startsWith("✅")) Color(0xFF7FE8DC) else Color(0xFFFFB4B4),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
