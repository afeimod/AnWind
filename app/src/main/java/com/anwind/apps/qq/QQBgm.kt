package com.anwind.apps.qq

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * QQ游戏大厅背景音乐：
 *  - 每个场景一首循环曲（assets/qq_sounds/ 下，取自经典老乡 DDZ 项目的真实乐曲）：
 *      bgm_lobby  大厅     大厅主题曲（mp3）
 *      bgm_room   房间     轻快等待曲（mp3）
 *      bgm_ddz    斗地主   游戏主题曲（mp3）
 *      bgm_board  象棋/五子棋 悠扬棋类曲（ogg）
 *      bgm_mj     麻将     喜庆麻将曲（ogg）
 *  - 加载时自动按 mp3 / ogg / wav 顺序尝试（旧版本仅支持 wav）；
 *  - 开关持久化在 settings.musicEnabled，音量在 settings.musicVolume（0~100），
 *    大厅/房间右上角 🎵 可切换，下方滑块可调音量；
 *  - 想用自己喜欢的歌：把网上搜到的 mp3/ogg 命名为 bgm_lobby.mp3 等，
 *    放到 filesDir/qq/bgm/ 目录即可自动替换内置曲目。
 */
object QQBgm {
    private var player: MediaPlayer? = null
    private var current: String? = null

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun enabled(): Boolean = QQSession.settings.musicEnabled

    /** 音量（0~100 线性映射到 MediaPlayer 的 0~1） */
    private fun vol(): Float = QQSession.settings.musicVolume.coerceIn(0, 100) / 100f

    /**
     * 播放/切换背景音乐（name 形如 "bgm_lobby"）。
     * 同曲已在播则忽略；音乐开关关闭时停止并返回。
     */
    fun play(context: android.content.Context, name: String) {
        if (!enabled()) { stop(); return }
        if (current == name && player != null) return
        stop()
        runCatching {
            val mp = MediaPlayer()
            // 优先用户自定义：filesDir/qq/bgm/bgm_xxx.mp3|ogg|wav|m4a
            val dir = File(context.filesDir, "qq/bgm")
            val user = listOf("mp3", "ogg", "wav", "m4a", "flac")
                .map { File(dir, "$name.$it") }
                .firstOrNull { it.exists() && it.length() > 0 }
            if (user != null) {
                mp.setDataSource(user.absolutePath)
            } else {
                // 内置曲目：按 mp3 / ogg / wav 顺序在 assets/qq_sounds/ 里找
                var opened = false
                for (ext in listOf("mp3", "ogg", "wav")) {
                    val afd = runCatching { context.assets.openFd("qq_sounds/$name.$ext") }.getOrNull()
                        ?: continue
                    runCatching {
                        mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    }.onFailure { afd.close() }
                        .onSuccess { opened = true }
                    if (opened) { afd.close(); break }
                }
                check(opened) { "bgm asset not found: $name" }
            }
            mp.setAudioAttributes(attrs)
            mp.isLooping = true
            val v = vol()
            mp.setVolume(v, v)
            mp.setOnPreparedListener { p ->
                runCatching { if (current == name) p.start() }
            }
            mp.setOnErrorListener { p, _, _ ->
                runCatching { p.release() }
                if (current == name) { player = null; current = null }
                true
            }
            player = mp
            current = name
            mp.prepareAsync()
        }.onFailure {
            runCatching { player?.release() }
            player = null
            current = null
        }
    }

    /** 实时调节音量（滑块拖动时调用，无需重播）；pct 0~100 */
    fun applyVolume(pct: Int) {
        val v = pct.coerceIn(0, 100) / 100f
        runCatching { player?.setVolume(v, v) }
    }

    /** 停止背景音乐（离开游戏窗口 / 关闭开关时） */
    fun stop() {
        val p = player
        player = null
        current = null
        runCatching {
            p?.stop()
            p?.release()
        }
    }

    /** 切换音乐开关（大厅/房间顶部 🎵 按钮）。返回切换后的状态。 */
    fun toggle(context: android.content.Context): Boolean {
        val on = !enabled()
        QQSession.saveSettings(QQSession.settings.copy(musicEnabled = on))
        if (!on) {
            stop()
        } else {
            // 恢复播放当前场景曲目由调用方（LaunchedEffect）完成；
            // 这里兜底重新触发一次当前记录的场景，避免等待界面切换。
            lastScene?.let { play(context, it) }
        }
        return on
    }

    /** 最近一次请求的场景（toggle 恢复播放用） */
    var lastScene: String? = null
        private set

    /** 场景请求统一入口：记录场景并按开关播放 */
    fun scene(context: android.content.Context, name: String) {
        lastScene = name
        play(context, name)
    }
}
