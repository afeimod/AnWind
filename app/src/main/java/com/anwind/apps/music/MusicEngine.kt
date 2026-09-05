package com.anwind.apps.music

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 云音乐播放引擎（v2.17）：
 * - 基于 android.media.MediaPlayer 的流媒体播放（酷我解析直链 / 本地 URI）
 * - 播放代号（gen）防竞态：连续切歌时旧任务的异步回调全部静默丢弃
 *   （与项目中媒体播放器 v2.10 的 playGen 方案一致）
 * - 播放队列 + 三种模式：顺序播放 / 单曲循环 / 随机播放
 * - 500ms 步进的进度上报（Compose State，UI 直接读取）
 */
class MusicEngine(context: Context) {

    private val appContext = context.applicationContext
    val store = MusicStore(appContext)

    // ==================== UI 可观察状态 ====================

    /** 当前歌曲（点击后立即置位，链接解析中 UI 即可反馈） */
    var currentSong by mutableStateOf<SongInfo?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isPreparing by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    /** 播放失败提示（UI 展示 Toast / 内联提示后调用 clearError） */
    var playError by mutableStateOf<String?>(null)
        private set

    /** 播放模式：顺序 / 单曲循环 / 随机 */
    var playMode by mutableStateOf(store.loadPlayMode())
        private set

    private val volumeState = mutableStateOf(0.8f)

    /** 音量 0..1 */
    var volume: Float
        get() = volumeState.value
        set(value) {
            val v = value.coerceIn(0f, 1f)
            volumeState.value = v
            runCatching { player.setVolume(v, v) }
        }

    /** 当前播放队列与索引（供上一曲/下一曲可用性判断） */
    val queue = mutableStateListOf<SongInfo>()
    var queueIndex by mutableStateOf(-1)
        private set

    /**
     * 实时进度（v2.21）：直读 MediaPlayer 原生位置，供 KTV 逐字填充等高频场景；
     * 暂停/读取失败时回退到 500ms 步进的 positionMs 状态
     */
    fun rawPositionMs(): Long = runCatching {
        if (isPlaying) player.currentPosition.toLong() else positionMs
    }.getOrDefault(positionMs)

    // ==================== 内部 ====================

    private val player = MediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    /** 播放代号：每次发起加载自增，旧回调直接丢弃 */
    private var gen = 0

    /** 当前正在 prepare 的代号（onPrepared/onError 回调时与 gen 比对防竞态） */
    private var pendingGen = 0

    init {
        player.setOnCompletionListener { onCompleted() }
        player.setOnPreparedListener { mp ->
            // 仅当仍是当前这次加载时才生效（快速切歌时旧回调丢弃）
            if (isPreparing && pendingGen == gen) {
                isPreparing = false
                durationMs = runCatching { mp.duration.toLong() }.getOrDefault(durationMs)
                runCatching { mp.start() }
                isPlaying = true
                startTicker()
                currentSong?.let { store.pushRecent(it) }
            }
        }
        player.setOnErrorListener { _, what, extra ->
            if (pendingGen == gen && gen > 0) {
                isPreparing = false
                isPlaying = false
                playError = "播放出错 (code=$what/$extra)"
            }
            true
        }
    }

    // ==================== 播放入口 ====================

    /**
     * 播放指定歌曲。
     * @param list 非空时替换整个播放队列（点击列表行 / 播放全部）
     */
    fun play(song: SongInfo, list: List<SongInfo>? = null) {
        if (list != null) {
            queue.clear()
            queue.addAll(list)
            queueIndex = list.indexOfFirst { it.key == song.key }.takeIf { it >= 0 } ?: 0
        } else {
            val exist = queue.indexOfFirst { it.key == song.key }
            if (exist < 0) {
                queue.add(song)
                queueIndex = queue.size - 1
            } else {
                queueIndex = exist
            }
        }
        loadAndPlay(song)
    }

    /** 播放全部 */
    fun playAll(list: List<SongInfo>) {
        if (list.isEmpty()) return
        play(list[0], list)
    }

    /** 播放/暂停切换 */
    fun toggle() {
        if (currentSong == null) return
        runCatching {
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                ticker?.cancel()
            } else {
                player.start()
                isPlaying = true
                startTicker()
            }
        }
    }

    /** 跳转播放位置 */
    fun seekTo(ms: Long) {
        val safe = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        runCatching {
            player.seekTo(safe.toInt())
            positionMs = safe
        }
    }

    /** 上一曲（用户点击，循环队列） */
    fun prev() {
        if (queue.isEmpty()) return
        val i = if (queueIndex <= 0) queue.size - 1 else queueIndex - 1
        queueIndex = i
        loadAndPlay(queue[i])
    }

    /** 下一曲；auto=true 表示自动连播（顺序模式播完即停） */
    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && playMode == MusicStore.MODE_ORDER) {
            if (queueIndex + 1 >= queue.size) {
                // 顺序播放到队尾：停止
                isPlaying = false
                positionMs = durationMs
                return
            }
        }
        val i = when {
            playMode == MusicStore.MODE_SHUFFLE && queue.size > 1 -> {
                var r = queueIndex
                while (r == queueIndex) r = (0 until queue.size).random()
                r
            }
            queueIndex + 1 >= queue.size -> if (auto) return else 0
            else -> queueIndex + 1
        }
        queueIndex = i
        loadAndPlay(queue[i])
    }

    /** 切换播放模式并持久化 */
    fun cycleMode() {
        playMode = (playMode + 1) % 3
        store.savePlayMode(playMode)
    }

    fun clearError() {
        playError = null
    }

    // ==================== 内部实现 ====================

    private fun loadAndPlay(song: SongInfo) {
        gen++
        val myGen = gen
        currentSong = song
        isPlaying = false
        isPreparing = true
        playError = null
        positionMs = 0L
        durationMs = song.durationMs
        ticker?.cancel()

        scope.launch {
            // 解析播放源优先级：本地 URI > 已下载文件 > 在线解析直链
            val source: String? = when {
                song.isLocal -> song.localUri
                song.downloadedPath != null && java.io.File(song.downloadedPath).isFile ->
                    Uri.fromFile(java.io.File(song.downloadedPath)).toString()
                else -> KuwoMusicApi.getPlayUrl(song.id).getOrNull()
            }
            if (myGen != gen) return@launch
            if (source.isNullOrEmpty()) {
                isPreparing = false
                playError = if (song.isLocal) "本地文件无法访问" else "获取播放链接失败，请稍后重试"
                return@launch
            }
            try {
                player.reset()
                // 音频流类型需在 idle 态设置（reset 之后、setDataSource 之前）
                player.setAudioStreamType(AudioManager.STREAM_MUSIC)
                player.setDataSource(appContext, Uri.parse(source))
                pendingGen = myGen
                player.prepareAsync()
            } catch (e: Exception) {
                if (myGen == gen) {
                    isPreparing = false
                    playError = "加载失败: ${e.message ?: "未知错误"}"
                }
            }
        }
    }

    /** 播放完成：按模式自动接续 */
    private fun onCompleted() {
        isPlaying = false
        when {
            playMode == MusicStore.MODE_LOOP_ONE -> {
                currentSong?.let { seekTo(0); runCatching { player.start() }; isPlaying = true; startTicker() }
            }
            else -> next(auto = true)
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && isPlaying) {
                positionMs = runCatching { player.currentPosition.toLong() }.getOrDefault(positionMs)
                delay(300)
            }
        }
    }

    /** 窗口关闭时释放资源（UI 层在 DisposableEffect 中调用） */
    fun dispose() {
        ticker?.cancel()
        scope.cancel()
        runCatching {
            if (player.isPlaying) player.stop()
        }
        runCatching { player.release() }
    }
}
