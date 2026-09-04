package com.anwind.apps.music

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * 云音乐数据层（v2.18）：
 * - [SongInfo] 统一歌曲模型（在线 / 本地）
 * - [LrcParser] LRC 歌词解析（移植自 linboxyy.py LrcParser，支持一行多时间标签）
 * - [LyricsDoc] 歌词文档（原文 + 按时间合并的翻译行）
 * - [MusicStore] 收藏 / 最近播放 / 播放模式 / 播放器设置持久化（JSON 文件，filesDir 内）
 * - [MusicSettings] 播放器设置模型（v2.18：歌词秀背景 / 3D 倾斜 / 主页背景 / 扫描目录 / 词源引擎）
 * - 歌词缓存（cacheDir/music_lyrics）与四级词源回退获取 [fetchLyrics]
 */

// ==================== 歌曲模型 ====================

/** 统一歌曲模型：在线（source=kuwo）或本地（source=local，uri 非空） */
data class SongInfo(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val picUrl: String,
    val source: String = SOURCE_KUWO,
    /** 本地歌曲的 content:// 或 file:// URI */
    val localUri: String? = null,
    /** 已下载到本地的文件路径（下载完成后回填，仅在线歌有） */
    val downloadedPath: String? = null
) {
    /** 唯一键：收藏/最近播放/歌词缓存去重 */
    val key: String
        get() = if (source == SOURCE_LOCAL) "local_$localUri" else "${source}_$id"

    val isLocal: Boolean get() = source == SOURCE_LOCAL

    fun toOnlineSong(): KuwoMusicApi.Song? =
        if (source == SOURCE_KUWO) {
            KuwoMusicApi.Song(id, name, artist, album, durationMs, picUrl)
        } else null

    companion object {
        const val SOURCE_KUWO = "kuwo"
        const val SOURCE_LOCAL = "local"

        fun fromKuwoSong(s: KuwoMusicApi.Song) = SongInfo(
            id = s.id, name = s.name, artist = s.artist, album = s.album,
            durationMs = s.durationMs, picUrl = s.pic, source = SOURCE_KUWO
        )
    }
}

// ==================== LRC 解析 ====================

/** 一行歌词：时间戳 + 原文 + 翻译（可为空） */
data class LyricLine(val timeMs: Long, val text: String, val translation: String? = null)

/** 歌词文档：解析后的行列表 + 来源描述 */
data class LyricsDoc(
    val lines: List<LyricLine>,
    /** "kuwo" / "netease" / "local"（本地 .lrc 文件） */
    val source: String
) {
    /** 二分查找当前播放行索引（timeMs <= pos 的最后一行），无歌词返回 -1 */
    fun indexAt(posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= posMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/**
 * LRC 歌词解析器 —— 移植自 linboxyy.py 的 LrcParser 类：
 * - 支持一行多个时间标签 [mm:ss.xx][mm:ss.xx]文本
 * - 支持两位/三位毫秒
 * - 解析结果按时间升序排列
 */
object LrcParser {

    private val TIME_TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]")
    private val ALL_TAGS = Pattern.compile("\\[\\d+:\\d+(?:[.:]\\d+)?]")

    /** 解析 LRC 文本为 (时间ms, 文本) 列表 */
    fun parse(content: String): List<Pair<Long, String>> {
        val timestamps = mutableListOf<Long>()
        val lyrics = mutableListOf<String>()

        for (rawLine in content.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val matcher = TIME_TAG.matcher(line)
            val tags = mutableListOf<Long>()
            while (matcher.find()) {
                val minutes = matcher.group(1)?.toLongOrNull() ?: continue
                val seconds = matcher.group(2)?.toLongOrNull() ?: continue
                val fracStr = matcher.group(3) ?: "0"
                // 两位毫秒按 x10，三位及以上直接截取前三位
                val ms = if (fracStr.length <= 2) {
                    fracStr.padEnd(2, '0').toLongOrNull() ?: 0L
                } else {
                    fracStr.take(3).toLongOrNull() ?: 0L
                }
                tags.add(minutes * 60_000L + seconds * 1000L + ms)
            }
            if (tags.isEmpty()) continue

            // 移除所有时间标签得到纯文本
            val text = ALL_TAGS.matcher(line).replaceAll("").trim()
            for (t in tags) {
                timestamps.add(t)
                lyrics.add(text)
            }
        }

        // 按时间排序（稳定排序，与 python 版行为一致）
        val idx = timestamps.indices.sortedBy { timestamps[it] }
        return idx.map { timestamps[it] to lyrics[it] }
    }

    /**
     * 解析原文 + 翻译两份 LRC，按时间戳合并为 LyricsDoc。
     * 翻译行匹配原行时间戳（容差 60ms）。
     */
    fun merge(mainContent: String, translationContent: String?, source: String): LyricsDoc {
        val main = parse(mainContent)
        val transMap = HashMap<Long, String>()
        if (!translationContent.isNullOrBlank()) {
            for ((t, text) in parse(translationContent)) {
                if (text.isNotBlank()) transMap[t] = text
            }
        }
        val lines = main.map { (t, text) ->
            val tr = transMap[t]
                ?: if (transMap.isNotEmpty()) {
                    // 容差查找最近翻译（±60ms）
                    var best: Pair<Long, String>? = null
                    for ((tt, txt) in transMap) {
                        if (Math.abs(tt - t) <= 60) {
                            if (best == null || Math.abs(tt - t) < Math.abs(best!!.first - t)) {
                                best = tt to txt
                            }
                        }
                    }
                    best?.second
                } else null
            LyricLine(timeMs = t, text = text, translation = tr)
        }
        return LyricsDoc(lines = lines, source = source)
    }

    /** LyricsDoc → 可保存的 LRC 文本（翻译写入 [tt:...] 不标准，直接原文行） */
    fun toLrcText(doc: LyricsDoc): String = buildString {
        for (line in doc.lines) {
            val m = line.timeMs / 60000
            val s = (line.timeMs % 60000) / 1000
            val ms = line.timeMs % 1000
            append("[%02d:%02d.%03d]%s\n".format(m, s, ms, line.text))
        }
    }
}

// ==================== 播放器设置 ====================

/**
 * 播放器设置（v2.18 新增，设置中心持久化）：
 * - 歌词秀：背景（封面模糊/纯色/渐变/自定义图片）、3D 倾斜强度与上限、歌词墙视角、
 *   当前行字号、行切换动画、高亮发光、翻译显示
 * - 主页：背景（默认/纯色/渐变/自定义图片）与图片压暗
 * - 本地扫描：全库扫描 / 仅扫描指定目录（目录为绝对路径，依赖“所有文件访问”权限）
 * - 词源引擎：智能回退 / 指定优先词源（酷我、网易云、QQ 音乐、LRCLIB）
 */
data class MusicSettings(
    // ---- 歌词秀背景 ----
    val lyricBgMode: Int = BG_COVER,
    val lyricBgColor: Int = 0xFF191922.toInt(),
    val lyricBgGradient: Int = 0,
    val lyricBgImage: String? = null,
    // ---- 3D 歌词 ----
    /** 每行倾斜系数（度/行距），0 为平面 */
    val tilt3d: Float = 9f,
    /** 单行最大倾斜角（度） */
    val tilt3dMax: Float = 44f,
    /** 整面歌词墙绕 Y 轴视角（度），0 为正对 */
    val wallRotateY: Float = -14f,
    /** 当前行字号（sp），非当前行按比例缩小 */
    val lyricFontSize: Int = 22,
    /** 行切换平滑动画 */
    val lyricDynamic: Boolean = true,
    /** 当前行高亮发光 */
    val lyricGlow: Boolean = true,
    /** 显示翻译行 */
    val showTranslation: Boolean = true,
    /** 词源引擎：auto / kuwo / netease / qq / lrclib */
    val lyricEngine: String = ENGINE_AUTO,
    // ---- 主页背景 ----
    val homeBgMode: Int = HOME_BG_DEFAULT,
    val homeBgColor: Int = 0xFFFCFCFD.toInt(),
    val homeBgGradient: Int = 0,
    val homeBgImage: String? = null,
    /** 自定义图片时的压暗系数 0..0.8 */
    val homeImageDim: Float = 0.25f,
    // ---- 本地扫描 ----
    val scanMode: Int = SCAN_ALL,
    /** 指定目录扫描的绝对路径列表 */
    val scanDirs: List<String> = emptyList()
) {
    @Suppress("unused")
    companion object {
        // 歌词秀背景模式
        const val BG_COVER = 0      // 封面模糊铺底（默认）
        const val BG_SOLID = 1      // 纯色
        const val BG_GRADIENT = 2   // 渐变预设
        const val BG_IMAGE = 3      // 自定义图片

        // 主页背景模式
        const val HOME_BG_DEFAULT = 0

        // 本地扫描模式
        const val SCAN_ALL = 0      // 全库（MediaStore）
        const val SCAN_DIRS = 1     // 仅指定目录

        // 词源引擎
        const val ENGINE_AUTO = "auto"
        const val ENGINE_KUWO = "kuwo"
        const val ENGINE_NETEASE = "netease"
        const val ENGINE_QQ = "qq"
        const val ENGINE_LRCLIB = "lrclib"

        /** 词源显示名（设置页 / 词源标记共用） */
        val ENGINE_LABELS = linkedMapOf(
            ENGINE_AUTO to "智能回退（推荐）",
            ENGINE_KUWO to "酷我音乐",
            ENGINE_NETEASE to "网易云",
            ENGINE_QQ to "QQ 音乐",
            ENGINE_LRCLIB to "LRCLIB"
        )
    }
}

// ==================== 本地持久化 ====================

/**
 * 收藏 / 最近播放 / 播放模式持久化。
 * 存储位置：context.filesDir/music/（应用私有，无需存储权限）。
 */
class MusicStore(private val context: Context) {

    private val dir: File by lazy {
        File(context.filesDir, "music").apply { mkdirs() }
    }
    private val favoritesFile: File by lazy { File(dir, "favorites.json") }
    private val recentFile: File by lazy { File(dir, "recent.json") }
    private val settingsFile: File by lazy { File(dir, "settings.json") }

    private val lock = Any()

    // ---------- 收藏 ----------

    fun loadFavorites(): MutableList<SongInfo> = loadSongs(favoritesFile)

    fun saveFavorites(list: List<SongInfo>) = saveSongs(favoritesFile, list)

    fun isFavorite(song: SongInfo): Boolean = synchronized(lock) {
        loadSongs(favoritesFile).any { it.key == song.key }
    }

    fun toggleFavorite(song: SongInfo): Boolean {
        synchronized(lock) {
            val list = loadSongs(favoritesFile)
            val removed = list.removeAll { it.key == song.key }
            if (!removed) list.add(0, song)
            saveSongs(favoritesFile, list)
            return !removed
        }
    }

    // ---------- 最近播放 ----------

    fun loadRecent(): MutableList<SongInfo> = loadSongs(recentFile)

    fun pushRecent(song: SongInfo) {
        synchronized(lock) {
            val list = loadSongs(recentFile)
            list.removeAll { it.key == song.key }
            list.add(0, song)
            while (list.size > 100) list.removeAt(list.size - 1)
            saveSongs(recentFile, list)
        }
    }

    // ---------- 设置（播放模式 + 播放器设置中心） ----------

    private fun readSettingsJson(): JSONObject =
        runCatching { JSONObject(settingsFile.readText()) }.getOrDefault(JSONObject())

    private fun writeSettingsJson(o: JSONObject) {
        runCatching { settingsFile.writeText(o.toString()) }
    }

    fun loadPlayMode(): Int = readSettingsJson().optInt("playMode", MODE_ORDER)

    fun savePlayMode(mode: Int) {
        writeSettingsJson(readSettingsJson().put("playMode", mode))
    }

    /** 读取播放器设置中心（缺失字段全部回退默认值） */
    fun loadMusicSettings(): MusicSettings {
        val o = readSettingsJson()
        val dirs = mutableListOf<String>()
        runCatching {
            val arr = o.optJSONArray("scanDirs")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotEmpty()) dirs.add(s)
                }
            }
        }
        return MusicSettings(
            lyricBgMode = o.optInt("lyricBgMode", MusicSettings.BG_COVER),
            lyricBgColor = o.optInt("lyricBgColor", 0xFF191922.toInt()),
            lyricBgGradient = o.optInt("lyricBgGradient", 0),
            lyricBgImage = o.optString("lyricBgImage", "").takeIf { it.isNotEmpty() },
            tilt3d = o.optDouble("tilt3d", 9.0).toFloat().coerceIn(0f, 20f),
            tilt3dMax = o.optDouble("tilt3dMax", 44.0).toFloat().coerceIn(0f, 90f),
            wallRotateY = o.optDouble("wallRotateY", -14.0).toFloat().coerceIn(-40f, 0f),
            lyricFontSize = o.optInt("lyricFontSize", 22).coerceIn(14, 40),
            lyricDynamic = o.optBoolean("lyricDynamic", true),
            lyricGlow = o.optBoolean("lyricGlow", true),
            showTranslation = o.optBoolean("showTranslation", true),
            lyricEngine = o.optString("lyricEngine", MusicSettings.ENGINE_AUTO),
            homeBgMode = o.optInt("homeBgMode", MusicSettings.HOME_BG_DEFAULT),
            homeBgColor = o.optInt("homeBgColor", 0xFFFCFCFD.toInt()),
            homeBgGradient = o.optInt("homeBgGradient", 0),
            homeBgImage = o.optString("homeBgImage", "").takeIf { it.isNotEmpty() },
            homeImageDim = o.optDouble("homeImageDim", 0.25).toFloat().coerceIn(0f, 0.8f),
            scanMode = o.optInt("scanMode", MusicSettings.SCAN_ALL),
            scanDirs = dirs
        )
    }

    /** 保存播放器设置中心（保留 playMode） */
    fun saveMusicSettings(s: MusicSettings) {
        runCatching {
            val dirs = JSONArray()
            for (d in s.scanDirs) dirs.put(d)
            writeSettingsJson(
                readSettingsJson()
                    .put("lyricBgMode", s.lyricBgMode)
                    .put("lyricBgColor", s.lyricBgColor)
                    .put("lyricBgGradient", s.lyricBgGradient)
                    .put("lyricBgImage", s.lyricBgImage ?: "")
                    .put("tilt3d", s.tilt3d.toDouble())
                    .put("tilt3dMax", s.tilt3dMax.toDouble())
                    .put("wallRotateY", s.wallRotateY.toDouble())
                    .put("lyricFontSize", s.lyricFontSize)
                    .put("lyricDynamic", s.lyricDynamic)
                    .put("lyricGlow", s.lyricGlow)
                    .put("showTranslation", s.showTranslation)
                    .put("lyricEngine", s.lyricEngine)
                    .put("homeBgMode", s.homeBgMode)
                    .put("homeBgColor", s.homeBgColor)
                    .put("homeBgGradient", s.homeBgGradient)
                    .put("homeBgImage", s.homeBgImage ?: "")
                    .put("homeImageDim", s.homeImageDim.toDouble())
                    .put("scanMode", s.scanMode)
                    .put("scanDirs", dirs)
            )
        }
    }

    // ---------- 歌词缓存 ----------

    private val lyricCacheDir: File by lazy {
        File(context.cacheDir, "music_lyrics").apply { mkdirs() }
    }

    fun cachedLyrics(song: SongInfo): LyricsDoc? {
        val main = File(lyricCacheDir, "${song.key}.lrc")
        if (!main.isFile) return null
        val trans = File(lyricCacheDir, "${song.key}.tr.lrc").takeIf { it.isFile }?.readText()
        return runCatching {
            LrcParser.merge(main.readText(), trans, "cache")
        }.getOrNull()
    }

    fun cacheLyrics(song: SongInfo, mainText: String, transText: String?) {
        runCatching {
            File(lyricCacheDir, "${song.key}.lrc").writeText(mainText)
            if (transText != null) {
                File(lyricCacheDir, "${song.key}.tr.lrc").writeText(transText)
            }
        }
    }

    // ---------- 序列化 ----------

    private fun loadSongs(file: File): MutableList<SongInfo> {
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<SongInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    SongInfo(
                        id = o.optString("id", ""),
                        name = o.optString("name", ""),
                        artist = o.optString("artist", ""),
                        album = o.optString("album", ""),
                        durationMs = o.optLong("durationMs", 0L),
                        picUrl = o.optString("picUrl", ""),
                        source = o.optString("source", SongInfo.SOURCE_KUWO),
                        localUri = o.optString("localUri", "").takeIf { it.isNotEmpty() },
                        downloadedPath = o.optString("downloadedPath", "").takeIf { it.isNotEmpty() }
                    )
                )
            }
            list
        }.getOrDefault(mutableListOf())
    }

    private fun saveSongs(file: File, list: List<SongInfo>) {
        runCatching {
            val arr = JSONArray()
            for (s in list) {
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("name", s.name)
                        .put("artist", s.artist)
                        .put("album", s.album)
                        .put("durationMs", s.durationMs)
                        .put("picUrl", s.picUrl)
                        .put("source", s.source)
                        .put("localUri", s.localUri ?: "")
                        .put("downloadedPath", s.downloadedPath ?: "")
                )
            }
            file.writeText(arr.toString())
        }
    }

    companion object {
        /** 播放模式 */
        const val MODE_ORDER = 0
        const val MODE_LOOP_ONE = 1
        const val MODE_SHUFFLE = 2
    }
}

// ==================== 歌词获取编排 ====================

/**
 * 获取当前歌曲歌词（带磁盘缓存，v2.18 升级为四级词源回退链）：
 * 1. 酷我逐行歌词（在线歌首选，按 musicId 直查）
 * 2. 网易云搜索兜底（linboxyy.py LyricDownloader 逻辑，含翻译）
 * 3. QQ 音乐（v2.18 新增，华语曲库覆盖广，含翻译）
 * 4. LRCLIB（v2.18 新增，开源歌词库，外文/冷门歌覆盖好）
 *
 * @param force 跳过缓存强制刷新
 * @param engine 词源偏好：auto 按默认顺序；指定词源时该源优先，其余按默认顺序兜底
 */
suspend fun fetchLyrics(
    store: MusicStore,
    song: SongInfo,
    force: Boolean = false,
    engine: String = MusicSettings.ENGINE_AUTO
): LyricsDoc? {
    if (!force) {
        store.cachedLyrics(song)?.let { return it }
    }

    // 关键词构造：在线歌用"名 歌手"；本地歌用文件名清理（去扩展名/括号注释）
    val cleanName = cleanLocalName(song.name)
    val keyword = if (song.isLocal) cleanName else "${song.name} ${song.artist}".trim()
    val artist = song.artist.takeIf { it.isNotBlank() && it != "未知歌手" } ?: ""

    for (eng in engineOrder(engine)) {
        val pair: Pair<String, String?>? = runCatching {
            when (eng) {
                "kuwo" -> fetchKuwo(song)
                "netease" -> KuwoMusicApi.getNeteaseLyric(keyword).getOrNull()
                "qq" -> LyricEngines.getQqLyric(keyword).getOrNull()
                "lrclib" -> LyricEngines.getLrclibLyric(cleanName, artist)
                    .getOrNull()?.let { it to null }
                else -> null
            }
        }.getOrNull()

        if (pair != null && pair.first.isNotBlank()) {
            val (main, trans) = pair
            val doc = LrcParser.merge(main, trans, eng)
            store.cacheLyrics(song, main, trans)
            return doc
        }
    }
    return null
}

/** 词源尝试顺序：指定词源优先，其余按默认顺序（酷我→网易→QQ→LRCLIB）兜底 */
private fun engineOrder(engine: String): List<String> {
    val default = listOf("kuwo", "netease", "qq", "lrclib")
    if (engine in default) {
        return listOf(engine) + default.filter { it != engine }
    }
    return default
}

/** 酷我逐行歌词（仅在线歌；返回 LRC 文本） */
private suspend fun fetchKuwo(song: SongInfo): Pair<String, String?>? {
    if (song.isLocal) return null
    val lines = KuwoMusicApi.getKuwoLyric(song.id).getOrNull() ?: return null
    if (lines.isEmpty()) return null
    val text = buildString {
        for ((t, txt) in lines) {
            val m = t / 60000
            val s = (t % 60000) / 1000
            val ms = t % 1000
            append("[%02d:%02d.%03d]%s\n".format(m, s, ms, txt))
        }
    }
    return text to null
}

/** 清理本地文件名为可搜索歌名（去扩展名 / 括号注释） */
fun cleanLocalName(name: String): String =
    name
        .replace(Regex("\\.(mp3|flac|wav|m4a|ogg|aac|opus|wma|ape)$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ")
        .trim()

// ==================== 下载目录 ====================

/**
 * 歌曲下载目录：优先公共 Music/AnWindMusic（配合 MANAGE_EXTERNAL_STORAGE），
 * 不可写时回退应用专属外部目录 getExternalFilesDir(Music)/AnWindMusic。
 */
fun musicDownloadDir(context: Context): File {
    val candidates = listOf(
        runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                ?.let { File(it, "AnWindMusic") }
        }.getOrNull(),
        context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let { File(it, "AnWindMusic") },
        File(context.filesDir, "music_downloads")
    )
    for (dir in candidates) {
        if (dir == null) continue
        if (dir.isDirectory && dir.canWrite()) return dir
        if (!dir.exists()) {
            if (dir.mkdirs() || (dir.isDirectory && dir.canWrite())) return dir
        }
    }
    return File(context.filesDir, "music_downloads").apply { mkdirs() }
}

/** 清理文件名中的非法字符 */
fun sanitizeFileName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifEmpty { "未命名" }
