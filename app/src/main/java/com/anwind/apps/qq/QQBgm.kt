package com.anwind.apps.qq

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * QQ游戏大厅背景音乐：
 *  - 每个场景一首循环曲（assets/qq_sounds/ 下 mp3，v4 全部重制为原创轻音乐）：
 *      bgm_lobby  大厅     C大调 112BPM 明快欢迎曲（八音盒+贝斯+轻鼓）
 *      bgm_room   房间     F大调 88BPM  舒缓等待曲（电钢+柔和垫音）
 *      bgm_ddz    斗地主   G大调 122BPM 俏皮紧张曲（八音盒+拨弦贝斯）
 *      bgm_board  象棋/五子棋 G宫五声 72BPM 静谧对弈曲（古筝+云雾垫音）
 *      bgm_mj     麻将     D宫五声 108BPM 喜庆国风（古筝拨弦+国风鼓）
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
                // 内置曲目：按 wav → mp3 → ogg 顺序探测（v2 起新增参考 APK 的 mp3 曲目）
                val builtin = listOf("wav", "mp3", "ogg")
                    .map { "qq_sounds/$name.$it" }
                    .firstOrNull { runCatching { context.assets.open(it).close() }.isSuccess }
                    ?: "qq_sounds/$name.wav"
                val afd = context.assets.openFd(builtin)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
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
