package com.anwind.apps.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

/**
 * 简音同款 Meting 聚合音源（v2.24 新增，搜索页第二音源）。
 *
 * 参考开源项目简音（github.com/qianqianhhh2/jianyin）的 MetingApi 用法：
 * - 聚合端点：https://api.qijieya.cn/meting/?server=<netease|kugou>&type=<search|url|pic|lrc>&id=<关键词或ID>
 * - 搜索（type=search）返回 JSON 数组：[{name, artist, url, pic, lrc}]，
 *   其中 url/pic/lrc 为同域代理直链（302 跳转到真实资源），歌曲 ID 可从 url 的 id 参数提取
 * - 播放（type=url）：302 → 网易云/酷狗真实音频直链（手动跟随重定向解析出最终地址交给播放器）
 * - 歌词（type=lrc）：200 text/plain 直接返回 LRC 全文
 * - 实测该部署支持 server=netease / kugou（其余 server 返回空或回落 netease），
 *   搜索默认 netease，空结果自动回落 kugou，命中的 server 随歌曲记入 source 持久化
 *
 * 全部通过 HttpURLConnection + org.json 实现（复用 KuwoMusicApi 的 HTTP 工具），不引入第三方依赖。
 */
object MetingMusicApi {

    private const val METING_URL = "https://api.qijieya.cn/meting/"

    /** 搜索尝试的 server 顺序（netease 优先，空结果回落 kugou） */
    private val SEARCH_SERVERS = listOf("netease", "kugou")

    /** UA 与简音/浏览器一致，避免被聚合端点的简单 UA 过滤拦截 */
    private val UA = KuwoMusicApi.UA_PC

    // ==================== 数据模型 ====================

    /** Meting 搜索结果中的一首歌曲 */
    data class Song(
        val id: String,
        val name: String,
        val artist: String,
        /** 命中的 Meting server（netease / kugou），随 SongInfo.source 持久化 */
        val server: String,
        /** 封面代理直链（type=pic，302 到真实图片） */
        val pic: String
    )

    // ==================== 搜索 ====================

    /**
     * 关键词搜索歌曲（简音音源）。
     * 依次尝试 [SEARCH_SERVERS]，任一源非空即返回；全部无结果返回空列表。
     */
    suspend fun search(keyword: String): Result<List<Song>> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext Result.success(emptyList())

            var lastError: Exception? = null
            for (server in SEARCH_SERVERS) {
                try {
                    val url = "$METING_URL?server=$server&type=search&id=${KuwoMusicApi.encodeQuery(kw)}"
                    val conn = KuwoMusicApi.openFollowing(
                        url,
                        mapOf("User-Agent" to UA, "Accept" to "application/json, text/plain, */*")
                    )
                    val arr = JSONArray(KuwoMusicApi.readText(conn))
                    val songs = parseSearchResponse(arr, server)
                    if (songs.isNotEmpty()) return@withContext Result.success(songs)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (lastError != null) Result.failure(lastError)
            else Result.success(emptyList())
        }

    /** 解析搜索响应：[{name, artist, url, pic, lrc}] → Song 列表 */
    private fun parseSearchResponse(arr: JSONArray, server: String): List<Song> {
        val songs = mutableListOf<Song>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.strOr("name") ?: continue
            // 歌曲 ID 从代理播放直链的 id 参数提取（搜索响应不含独立 id 字段）
            val id = queryParam(item.strOr("url"), "id")
            if (id.isEmpty()) continue
            songs.add(
                Song(
                    id = id,
                    name = name,
                    artist = item.strOr("artist") ?: "",
                    server = server,
                    pic = item.strOr("pic") ?: ""
                )
            )
        }
        return songs
    }

    // ==================== 播放直链 ====================

    /**
     * 解析播放直链：type=url 端点 302 → 真实音频地址。
     * 手动跟随重定向取最终 URL（KuwoMusicApi.openFollowing，最多 5 跳）；
     * 若端点直接 200（代理流式）则返回代理地址本身，由系统播放栈自行处理。
     */
    suspend fun resolvePlayUrl(server: String, songId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$METING_URL?server=$server&type=url&id=${KuwoMusicApi.encodeQuery(songId)}"
                val conn = KuwoMusicApi.openFollowing(
                    url,
                    mapOf("User-Agent" to UA),
                    readTimeoutMs = 10_000
                )
                val finalUrl = conn.url.toString()
                conn.disconnect()
                if (finalUrl.startsWith("http")) Result.success(finalUrl)
                else Result.failure(IOException("播放直链解析失败"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ==================== 歌词 ====================

    /** 直接拉取 Meting LRC 歌词全文（type=lrc 端点 200 返回纯文本） */
    suspend fun fetchLrc(server: String, songId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$METING_URL?server=$server&type=lrc&id=${KuwoMusicApi.encodeQuery(songId)}"
                val conn = KuwoMusicApi.openFollowing(
                    url,
                    mapOf("User-Agent" to UA),
                    readTimeoutMs = 10_000
                )
                val text = KuwoMusicApi.readText(conn)
                if (text.isBlank()) Result.failure(IOException("该歌曲暂无歌词"))
                else Result.success(text)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ==================== 工具 ====================

    /** 从 URL 查询串中取指定参数值（缺失返回 ""） */
    private fun queryParam(url: String?, key: String): String {
        if (url.isNullOrEmpty()) return ""
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return ""
        for (pair in url.substring(qIndex + 1).split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0 && pair.substring(0, idx) == key) {
                val raw = pair.substring(idx + 1)
                return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            }
        }
        return ""
    }

    private fun JSONObject.strOr(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }
}
