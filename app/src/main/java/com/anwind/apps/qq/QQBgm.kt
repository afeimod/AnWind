package com.anwind.apps.qq

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * QQ游戏大厅背景音乐：
 *  - 每个场景一首循环曲（assets/qq_sounds/ 下 Python 合成的 WAV）：
 *      bgm_lobby  大厅     轻快欢畅
 *      bgm_room   房间     舒缓等待
 *      bgm_ddz    斗地主   紧张明快
 *      bgm_board  象棋/五子棋 古风拨弦
 *      bgm_mj     麻将     喜庆国风
 *  - 开关持久化在 settings.musicEnabled，大厅/房间右上角 🎵 可切换；
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
                val afd = context.assets.openFd("qq_sounds/$name.wav")
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
            mp.setAudioAttributes(attrs)
            mp.isLooping = true
            mp.setVolume(0.38f, 0.38f)
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
