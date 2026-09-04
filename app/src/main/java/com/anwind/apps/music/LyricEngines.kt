package com.anwind.apps.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * 补充歌词引擎（v2.18 新增）：
 *
 * 背景：酷我 / 网易云两站搜不到的冷门歌、纯音乐、外文歌越来越多，
 * 本文件补充两大免费词源，与酷我 / 网易云组成四级回退链：
 *
 * 1. [getQqLyric]    QQ 音乐（c.y.qq.com）—— 华语曲库覆盖极广，歌词含翻译
 *    - 搜索：https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=<kw>&format=json
 *    - 歌词：https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<mid>&g_tk=5381&format=json
 *      返回 base64 编码的 LRC（lyric）与翻译（trans），需带 Referer 头
 * 2. [getLrclibLyric] LRCLIB（lrclib.net）—— 免费开源歌词库，外文/冷门歌覆盖好
 *    - 搜索：https://lrclib.net/api/search?track_name=<t>&artist_name=<a>
 *      返回数组，取第一个带 syncedLyrics（逐行同步 LRC）的结果
 *
 * 实现均基于 HttpURLConnection + org.json，与 KuwoMusicApi 风格一致，不引入第三方依赖。
 */
object LyricEngines {

    private const val QQ_SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
    private const val QQ_LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
    private const val LRCLIB_SEARCH_URL = "https://lrclib.net/api/search"

    private val UA = KuwoMusicApi.UA_PC

    // ==================== QQ 音乐歌词 ====================

    /**
     * QQ 音乐歌词：关键词搜索歌曲 → 取第一条的 songmid → 拉取 LRC（原文 + 翻译）。
     * @return Pair(原文LRC文本, 翻译LRC文本可空)，或失败
     */
    suspend fun getQqLyric(keyword: String): Result<Pair<String, String?>> =
        withContext(Dispatchers.IO) {
            try {
                val kw = keyword.trim()
                if (kw.isEmpty()) return@withContext Result.failure(IOException("关键词为空"))

                // 1) 搜索歌曲拿 songmid
                val searchUrl = "$QQ_SEARCH_URL?w=${enc(kw)}&format=json&n=8"
                val searchConn = openFollowing(
                    searchUrl,
                    mapOf(
                        "User-Agent" to UA,
                        "Referer" to "https://c.y.qq.com/",
                        "Accept" to "application/json"
                    ),
                    readTimeoutMs = 10_000
                )
                val searchRoot = JSONObject(KuwoMusicApi.readText(searchConn))
                if (searchRoot.optInt("code", -1) != 0) {
                    return@withContext Result.failure(IOException("QQ音乐搜索失败"))
                }
                val songs = searchRoot.optJSONObject("data")
                    ?.optJSONObject("song")?.optJSONArray("list")
                var songMid: String? = null
                if (songs != null) {
                    for (i in 0 until songs.length()) {
                        val item = songs.optJSONObject(i) ?: continue
                        val mid = item.optString("songmid", "")
                        if (mid.isNotEmpty()) { songMid = mid; break }
                    }
                }
                if (songMid.isNullOrEmpty()) {
                    return@withContext Result.failure(IOException("未找到匹配歌曲"))
                }

                // 2) 拉取歌词（base64 编码的 LRC 原文 + 翻译）
                val lyricUrl = "$QQ_LYRIC_URL?songmid=$songMid&g_tk=5381&format=json"
                val lyricConn = openFollowing(
                    lyricUrl,
                    mapOf(
                        "User-Agent" to UA,
                        "Referer" to "https://c.y.qq.com/",
                        "Accept" to "application/json"
                    ),
                    readTimeoutMs = 10_000
                )
                val lyricRoot = JSONObject(KuwoMusicApi.readText(lyricConn))
                val main = decodeB64(lyricRoot.strOr("lyric"))
                if (main.isNullOrBlank()) {
                    return@withContext Result.failure(IOException("该歌曲暂无歌词"))
                }
                val trans = decodeB64(lyricRoot.strOr("trans"))?.takeIf { it.isNotBlank() }
                Result.success(main to trans)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** base64 解码（歌词接口返回编码文本），失败返回 null */
    private fun decodeB64(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            String(Base64.getDecoder().decode(text), Charsets.UTF_8)
        }.getOrNull()
    }

    // ==================== LRCLIB 歌词 ====================

    /**
     * LRCLIB 歌词（免费开源歌词库，无需鉴权）：
     * 先按 track_name + artist_name 精确搜索，无结果再用 q 全文搜索；
     * 取第一个带 syncedLyrics（逐行同步 LRC）的结果，退而求其次取 plainLyrics。
     * @return 同步 LRC 文本，或失败
     */
    suspend fun getLrclibLyric(track: String, artist: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val t = track.trim()
                if (t.isEmpty()) return@withContext Result.failure(IOException("关键词为空"))
                val a = artist.trim()
                val headers = mapOf(
                    "User-Agent" to "AnWind/2.18 (music player)",
                    "Accept" to "application/json"
                )

                // 1) track_name + artist_name 精确搜索
                val firstUrl = "$LRCLIB_SEARCH_URL?track_name=${enc(t)}" +
                    if (a.isNotEmpty()) "&artist_name=${enc(a)}" else ""
                var synced = searchLrclib(firstUrl, headers)
                // 2) q 全文搜索兜底
                if (synced == null) {
                    val q = if (a.isNotEmpty()) "$a $t" else t
                    synced = searchLrclib("$LRCLIB_SEARCH_URL?q=${enc(q)}", headers)
                }
                if (synced == null) {
                    return@withContext Result.failure(IOException("LRCLIB 未收录该歌曲"))
                }
                Result.success(synced)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 请求 LRCLIB 搜索接口，返回第一个可用歌词（优先 syncedLyrics，其次 plainLyrics） */
    private fun searchLrclib(url: String, headers: Map<String, String>): String? {
        return try {
            val conn = openFollowing(url, headers, readTimeoutMs = 10_000)
            val root = JSONArray(KuwoMusicApi.readText(conn))
            var plain: String? = null
            for (i in 0 until root.length()) {
                val item = root.optJSONObject(i) ?: continue
                val synced = item.strOr("syncedLyrics")
                if (!synced.isNullOrBlank()) return synced
                if (plain == null) {
                    plain = item.strOr("plainLyrics")?.takeIf { it.isNotBlank() }
                }
            }
            plain
        } catch (_: Exception) {
            null
        }
    }

    // ==================== HTTP / 工具 ====================

    /** 与 KuwoMusicApi.openFollowing 相同的重定向跟随逻辑（独立一份，避免耦合） */
    private fun openFollowing(
        urlStr: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 15_000
    ): HttpURLConnection {
        var current = urlStr
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Accept-Encoding", "identity")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrEmpty() || redirects >= 5) throw IOException("重定向次数过多")
                current = URL(URL(current), loc).toString()
                redirects++
                continue
            }
            return conn
        }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JSONObject.strOr(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = optString(key, "")
        return if (v.isEmpty()) null else v
    }
}
