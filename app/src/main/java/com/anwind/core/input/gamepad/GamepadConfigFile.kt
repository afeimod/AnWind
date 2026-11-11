package com.anwind.core.input.gamepad

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v3.3.42（用户问题 3）：虚拟手柄**配置文件**存取。
 *
 * 用户需求："加入配置文件功能，可以保存和读取手柄现有的配置，后缀用
 * 虚拟手柄英文缩写字母最好三个字母" —— 故定后缀 **.vgp**
 * （**V**irtual **G**ame **P**ad）。
 *
 * 文件内容 = [GamepadController.toJson()] 的原样 JSON（v3 版本号布局：
 * elements 元素表 + padVisible 手柄显隐 + ballX/ballY 悬浮球位置），
 * 与 App 内持久化的 gamepad_config 同格式 —— 因此保存出来的文件可直接
 * 读回，读取的文件也可直接覆盖当前配置。
 *
 * 存取走系统 SAF（Storage Access Framework）：
 * - 保存：CreateDocument（用户自选目录，文件名默认 anwind_gamepad.vgp）
 * - 读取：OpenDocument（按 .vgp / json / 任意类型过滤）
 *
 * 另提供「App 私有目录」的同名文件读写（[exportToPrivateFile] /
 * [importFromPrivateFile]）—— 供终端/调试脚本直接落地一份备份。
 */
object GamepadConfigFile {

    /** 文件后缀：Virtual GamePad（三字母缩写）。 */
    const val EXT = "vgp"

    /** 默认文件名（SAF 保存时的建议名）。 */
    const val DEFAULT_FILE_NAME = "anwind_gamepad.$EXT"

    /** MIME 类型：自定义后缀，用通用二进制类型（SAF 不认识 .vgp）。 */
    const val MIME = "application/octet-stream"

    private const val TAG = "GamepadConfigFile"

    /**
     * 导出当前配置到指定 URI（SAF CreateDocument 回调）。
     * @return 是否成功
     */
    suspend fun export(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val json = GamepadController.toJson()
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return@runCatching false
            true
        }.getOrElse { e ->
            Log.w(TAG, "导出手柄配置失败", e)
            false
        }
    }

    /**
     * 从指定 URI 读取配置（SAF OpenDocument 回调）。
     * @return 配置 JSON；失败返回 null
     */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrElse { e ->
            Log.w(TAG, "读取手柄配置失败", e)
            null
        }
    }

    /**
     * 把配置写入 App 私有目录下的 .vgp 文件（终端/调试用）。
     * @return 落盘路径；失败返回 null
     */
    suspend fun exportToPrivateFile(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = java.io.File(context.filesDir, "gamepad").apply { mkdirs() }
            val f = java.io.File(dir, DEFAULT_FILE_NAME)
            f.writeText(GamepadController.toJson(), Charsets.UTF_8)
            f.absolutePath
        }.getOrElse { e ->
            Log.w(TAG, "写入私有目录配置失败", e)
            null
        }
    }

    /** 从 App 私有目录的 .vgp 文件读取配置（终端/调试用）。 */
    suspend fun importFromPrivateFile(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = java.io.File(java.io.File(context.filesDir, "gamepad"), DEFAULT_FILE_NAME)
            if (f.exists()) f.readText(Charsets.UTF_8) else null
        }.getOrElse { e ->
            Log.w(TAG, "读取私有目录配置失败", e)
            null
        }
    }

    /**
     * 校验并套用配置 JSON（读回文件后调用）。
     * @return 是否套用成功
     */
    fun applyConfig(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return runCatching {
            // 先用临时解析校验（loadFromJson 内部已做容错），再正式套用
            GamepadController.loadFromJson(json)
            true
        }.getOrElse { e ->
            Log.w(TAG, "手柄配置解析失败", e)
            false
        }
    }

    /** 统一的提示（成功/失败）。 */
    fun toast(context: Context, ok: Boolean, okText: String, failText: String) {
        Toast.makeText(context, if (ok) okText else failText, Toast.LENGTH_SHORT).show()
    }
}
