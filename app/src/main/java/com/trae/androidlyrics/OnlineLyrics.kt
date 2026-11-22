package com.trae.androidlyrics

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 在线歌词搜索：合并 lyrics-android 与 Capsulyric 两项目的歌词源，互补取长。
 *
 * - lyrics-android 侧：LRCLIB / QQ 音乐 / 网易云音乐 / 酷狗音乐 / iTunes 身份桥接，
 *   并移植其**逐字歌词获取链路**：QQ QRC（lyric_download.fcg → 非标 3DES + zlib →
 *   `[ms,dur]字(ms,dur)` 逐字轨）与酷狗 KRC（krcs.kugou.com → KRC1 XOR + zlib →
 *   `[ms,dur]<相对ms,dur,0>字` 逐字轨）；解密后的明文直接交给 [LyricsParser]。
 * - Capsulyric 侧：LrcApi / 汽水音乐 / Musixmatch / Apple Music（逐字 TTML）/ 网易云 eapi 兜底。
 *
 * 两种加密逐字轨都保留「失败即回退普通 LRC」的策略；排序按 lyrics-android 的
 * qualityRank 思路加入「逐字覆盖率」权重，覆盖率不足时借 iTunes 身份桥接补查一轮。
 *
 * 两个项目的实现分别用 OkHttp + 协程，本项目为了保证**离线可构建**不引入任何网络库，
 * 全部改用 Android 内置的 HttpURLConnection + org.json，异步用固定线程池。
 *
 * 每个源都是独立的 [Callable]，任何一个源失败/超时都不影响其它源；结果按
 * 标题、歌手、时长、逐字时间轴、译文完整度统一打分排序，供用户挑选版本。
 */
object OnlineLyrics {

    const val SOURCE_LRCLIB = "LRCLIB"
    const val SOURCE_QQ = "QQ音乐"
    const val SOURCE_NETEASE = "网易云音乐"
    const val SOURCE_KUGOU = "酷狗音乐"
    const val SOURCE_LRCAPI = "LrcApi"
    const val SOURCE_SODA = "汽水音乐"
    const val SOURCE_MUSIXMATCH = "Musixmatch"
    const val SOURCE_APPLE = "Apple Music"

    /** 默认全部启用；用户可在「歌词源」对话框里关闭部分源。 */
    val ALL_SOURCES: Set<String> = linkedSetOf(
        SOURCE_QQ, SOURCE_NETEASE, SOURCE_KUGOU, SOURCE_LRCLIB,
        SOURCE_LRCAPI, SOURCE_SODA, SOURCE_MUSIXMATCH, SOURCE_APPLE
    )

    /** 不需要额外凭据即可使用的源。 */
    val ANONYMOUS_SOURCES: Set<String> = linkedSetOf(
        SOURCE_QQ, SOURCE_NETEASE, SOURCE_KUGOU, SOURCE_LRCLIB,
        SOURCE_LRCAPI, SOURCE_SODA, SOURCE_MUSIXMATCH
    )

    data class Candidate(
        val source: String,
        val id: String,
        val title: String,
        val artist: String,
        val album: String = "",
        val durationMs: Long = 0L,
        val raw: String = "",
        val translation: String = "",
        val coverUrl: String = "",
        val hasWordTiming: Boolean = false,
        val score: Int = 0,
    ) {
        val summary: String
            get() = buildString {
                append(source)
                if (album.isNotBlank()) append(" · ").append(album)
                if (durationMs > 0L) append(" · ").append(formatClock(durationMs))
                // 明确标出「是否带逐字数据」：旧版只在有逐字时追加标记，
                // 无逐字的结果与尚未判定的结果看起来完全一样。
                append(if (hasWordTiming) " · 带逐字数据" else " · 无逐字数据")
                if (translation.isNotBlank()) append(" · 译文")
            }
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private const val TIMEOUT_CONNECT = 6_000
    private const val TIMEOUT_READ = 8_000
    private const val SEARCH_DEADLINE_MS = 12_000L
    private const val MAX_RESULTS_PER_SOURCE = 3
    /** 译文就近对齐容差：网易云 YRC 行起点与 tlyric 时间戳实测相差约 400~540ms。 */
    private const val TRANSLATION_TOLERANCE_MS = 1_200L

    /** 搜索状态回调（子线程调用，UI 侧自行切回主线程）。 */
    fun interface StatusListener {
        fun onStatus(text: String)
    }

    /**
     * 并行查询所有启用的歌词源。
     *
     * @param appleToken Apple Music 可选 media-user-token；为空时自动跳过该源。
     * @return 去重、打分、排序后的候选列表（可能为空）。
     */
    fun search(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long = 0L,
        enabledSources: Set<String> = ALL_SOURCES,
        appleToken: String = "",
        preferredSource: String? = null,
        listener: StatusListener? = null,
    ): List<Candidate> {
        val queryTitle = title.trim()
        val queryArtist = artist.trim()
        if (queryTitle.isBlank() && queryArtist.isBlank()) return emptyList()

        val sources = ALL_SOURCES.filter { it in enabledSources }.let { list ->
            if (appleToken.isBlank()) list.filterNot { it == SOURCE_APPLE } else list
        }
        if (sources.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(sources.size.coerceAtMost(8)) { r ->
            Thread(r, "online-lyrics").apply { isDaemon = true }
        }
        return try {
            val first = runSources(
                pool, sources, queryTitle, queryArtist, album, durationMs, appleToken, listener
            )
            listener?.onStatus("正在整理结果…")
            val ranked = rank(first, queryTitle, queryArtist, durationMs, preferredSource)
            // lyrics-android 的 needsEnrichment 思路：命中结果里前三名都没有完整逐字轨时，
            // 再走一次身份桥接补查；已有高覆盖率逐字版本或结果本来就为空时按原逻辑处理。
            val needWordEnrichment = ranked.isNotEmpty() && ranked[0].score >= 90 &&
                ranked.take(3).none { wordCoverage(it.raw) >= 80 }
            if (ranked.isNotEmpty() && !needWordEnrichment) return ranked

            // lyrics-android 的「身份桥接」：原名没命中 / 逐字补全时，用 iTunes Search API
            // 拿到本地化/规范化歌名后，挑几个快源再试一轮（覆盖外文歌、别名、译名）。
            listener?.onStatus("正在用标准歌名重试…")
            val identity = resolveItunesIdentity(queryTitle, queryArtist)
            if (identity == null) return ranked
            val (t2, a2) = identity
            if (normalize(t2) == normalize(queryTitle) && normalize(a2) == normalize(queryArtist)) {
                return ranked
            }
            val retrySources = sources.filter {
                it in setOf(
                    SOURCE_LRCLIB, SOURCE_LRCAPI, SOURCE_NETEASE, SOURCE_QQ, SOURCE_KUGOU
                )
            }
            if (retrySources.isEmpty()) return ranked
            val second = runSources(
                pool, retrySources, t2, a2, album, durationMs, appleToken, listener
            )
            rank(first + second, t2, a2, durationMs, preferredSource)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 并行跑一批歌词源，任何单源失败/超时都不影响其它源。 */
    private fun runSources(
        pool: java.util.concurrent.ExecutorService,
        sources: List<String>,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        appleToken: String,
        listener: StatusListener?,
    ): List<Candidate> {
        val tasks = sources.map { source ->
            Callable {
                listener?.onStatus("正在查询 $source…")
                runCatching {
                    when (source) {
                        SOURCE_LRCLIB -> searchLrcLib(title, artist, album)
                        SOURCE_QQ -> searchQq(title, artist)
                        SOURCE_NETEASE -> searchNetEase(title, artist)
                        SOURCE_KUGOU -> searchKuGou(title, artist, durationMs)
                        SOURCE_LRCAPI -> searchLrcApi(title, artist)
                        SOURCE_SODA -> searchSoda(title, artist)
                        SOURCE_MUSIXMATCH -> searchMusixmatch(title, artist)
                        SOURCE_APPLE -> searchApple(title, artist, appleToken)
                        else -> emptyList()
                    }
                }.onFailure { e ->
                    LogCapture.w("OnlineLyrics", "$source failed: ${e.javaClass.simpleName}: ${e.message}")
                }.getOrDefault(emptyList())
            }
        }
        if (tasks.isEmpty()) return emptyList()
        val futures = pool.invokeAll(tasks, SEARCH_DEADLINE_MS, TimeUnit.MILLISECONDS)
        val all = ArrayList<Candidate>()
        futures.forEach { future ->
            runCatching { all += future.get() }.onFailure {
                // 单个源超时不影响其它源
            }
        }
        return all
    }

    /**
     * Capsulyric 的「按播放器优先源」：同一个播放器官方源查到的歌词、
     * 逐字轨与译文通常最全，排序时给对应源加权。
     */
    fun preferredSourceFor(pkg: String?): String? = when (pkg) {
        "com.tencent.qqmusic", "com.tencent.qqmusictv" -> SOURCE_QQ
        "com.netease.cloudmusic" -> SOURCE_NETEASE
        "com.kugou.android", "com.kugou.android.lite" -> SOURCE_KUGOU
        "com.apple.android.music", "com.apple.android.music.tv" -> SOURCE_APPLE
        "com.ss.android.ugc.aweme", "com.luna.music" -> SOURCE_SODA
        else -> null
    }

    /** iTunes Search API 取规范歌名 / 歌手（lyrics-android 的身份桥接思路）。 */
    private fun resolveItunesIdentity(title: String, artist: String): Pair<String, String>? =
        runCatching {
            val term = if (artist.isBlank()) title else "$title $artist"
            val url = "https://itunes.apple.com/search?term=${enc(term)}" +
                "&entity=song&limit=8&country=cn"
            val results = JSONObject(http(url, mapOf("User-Agent" to UA)))
                .optJSONArray("results") ?: return null
            var best: Pair<String, String>? = null
            var bestScore = 0.0
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val t = item.optString("trackName")
                val a = item.optString("artistName")
                if (t.isBlank()) continue
                val score = similarity(t, title) + similarity(a, artist) * 0.5
                if (score > bestScore) {
                    bestScore = score
                    best = t to a
                }
            }
            best?.takeIf { bestScore > 0.35 }
        }.getOrNull()

    /** 统一打分、去重、排序。 */
    private fun rank(
        input: List<Candidate>,
        title: String,
        artist: String,
        expectedDurationMs: Long,
        preferredSource: String? = null,
    ): List<Candidate> {
        val keyTitle = normalize(title)
        val keyArtist = normalize(artist)
        return input.asSequence()
            .filter { it.raw.isNotBlank() }
            .map { c ->
                var score = 0
                val ct = normalize(c.title)
                val ca = normalize(c.artist)
                score += when {
                    ct == keyTitle && keyTitle.isNotBlank() -> 45
                    keyTitle.isNotBlank() && (ct.contains(keyTitle) || keyTitle.contains(ct)) -> 28
                    similarity(ct, keyTitle) > 0.6 -> 18
                    else -> 0
                }
                if (keyArtist.isNotBlank()) score += when {
                    ca == keyArtist -> 22
                    ca.contains(keyArtist) || keyArtist.contains(ca) -> 14
                    else -> 0
                }
                if (expectedDurationMs > 0L && c.durationMs > 0L) {
                    val delta = abs(expectedDurationMs - c.durationMs)
                    score += when {
                        delta <= 3_000L -> 15
                        delta <= 8_000L -> 6
                        delta > 20_000L -> -18
                        else -> 0
                    }
                }
                // QRC/KRC/YRC 用 `[ms,dur]` 毫秒制行首，同样属于有效时间轴，
                // 不能因为不是 `[mm:ss.xx]` 就丢掉这 16 分。
                if (hasLrcTimeTag(c.raw) || MS_TIMED_LINE.containsMatchIn(c.raw)) score += 16
                // lyrics-android qualityRank 的「逐字覆盖率」：按带逐字标记的歌词行
                // 占比加权（0~20 分），覆盖率 >= 80% 再给一次完整逐字轨奖励。
                // 这样「只有几行带逐字」的残缺版本不会压过完整逐字版本。
                val coverage = wordCoverage(c.raw)
                score += coverage / 5
                if (coverage >= 80) score += 15
                if (c.translation.isNotBlank()) score += 10
                // 当前播放器对应的官方源优先（Capsulyric 的按包名排序策略）
                if (preferredSource != null && c.source == preferredSource) score += 8
                // 逐字时间轴 + 译文齐备的版本优先展示（两项目互补的重点）
                if (coverage >= 80 && c.translation.isNotBlank()) score += 6
                c.copy(score = score)
            }
            .distinctBy { "${it.source}|${it.id}" }
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.source })
            .toList()
    }

    // ---------------------------------------------------------------- LRCLIB
    // lyrics-android 用 /api/search 多计划检索；Capsulyric 用 /api/get 精确取词，
    // 这里合并：先按 track_name/artist_name，再退回 q= 宽搜，保证外文/别名歌曲命中。

    private fun searchLrcLib(title: String, artist: String, album: String): List<Candidate> {
        val headers = mapOf("Accept" to "application/json", "User-Agent" to "Androidlyrics/1.0")
        val urls = buildList {
            add("https://lrclib.net/api/search?track_name=${enc(title)}" +
                if (artist.isBlank()) "" else "&artist_name=${enc(artist)}")
            if (album.isNotBlank()) {
                add("https://lrclib.net/api/search?track_name=${enc(title)}&album_name=${enc(album)}")
            }
            if (artist.isNotBlank()) add("https://lrclib.net/api/search?q=${enc("$title $artist")}")
            add("https://lrclib.net/api/search?q=${enc(title)}")
            // Capsulyric 的精确接口：拿到 instrumental / syncedLyrics 兜底
            if (artist.isNotBlank()) {
                add("https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}")
            }
        }.distinct()

        val out = ArrayList<Candidate>()
        for (url in urls) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val body = runCatching { http(url, headers) }.getOrNull() ?: continue
            val items = JSONArray()
            val trimmed = body.trim()
            when {
                trimmed.startsWith("[") -> {
                    val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: continue
                    for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { items.put(it) }
                }
                trimmed.startsWith("{") -> {
                    val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
                    if (!obj.has("error")) items.put(obj)
                }
            }
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val synced = item.optString("syncedLyrics")
                val plain = item.optString("plainLyrics")
                val raw = synced.takeIf { it.isNotBlank() } ?: plain
                if (raw.isBlank()) continue
                val seconds = item.optDouble("duration", 0.0)
                out += Candidate(
                    source = SOURCE_LRCLIB,
                    id = item.optLong("id").toString().ifBlank { "$title-${out.size}" },
                    title = item.optString("trackName").ifBlank { title },
                    artist = item.optString("artistName").ifBlank { artist },
                    album = item.optString("albumName"),
                    durationMs = (seconds * 1000).toLong(),
                    raw = raw,
                    coverUrl = "",
                    hasWordTiming = detectWordTiming(raw),
                )
                if (out.size >= MAX_RESULTS_PER_SOURCE) break
            }
        }
        return out.distinctBy { it.raw.hashCode() }.take(MAX_RESULTS_PER_SOURCE)
    }

    // ---------------------------------------------------------------- QQ 音乐
    // lyrics-android: search_for_qq_cp + fcg_query_lyric_new.fcg（含 QRC 逐字）
    // Capsulyric: u.y.qq.com/cgi-bin/musicu.fcg 兜底，这里保留主路径并补译文。

    private fun searchQq(title: String, artist: String): List<Candidate> {
        val headers = mapOf("Referer" to "https://y.qq.com/", "User-Agent" to UA, "Accept" to "application/json")
        val query = if (artist.isBlank()) title else "$title $artist"
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp" +
            "?format=json&p=1&n=10&w=${enc(query)}&g_tk=0&jsonpCallback=callback"
        val body = unwrapJsonp(http(searchUrl, headers))
        val songs = JSONObject(body).optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("list") ?: return emptyList()

        val out = ArrayList<Candidate>()
        // QRC 解密接口比普通 LRC 慢，最多试前 2 首，避免挤占并行搜索的 12s 窗口
        var qrcAttempts = 0
        for (i in 0 until songs.length()) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val song = songs.optJSONObject(i) ?: continue
            val mid = song.optString("songmid").ifBlank { song.optString("mid") }
            if (mid.isBlank()) continue
            val singers = song.optJSONArray("singer")
            val singerName = (0 until (singers?.length() ?: 0))
                .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }.joinToString(" / ")

            // ① 优先走 lyrics-android 的 QRC 富歌词接口：拿到的是带逐字时间戳的
            //    `[ms,dur]字(ms,dur)` 轨，比 fcg_query_lyric_new.fcg 的普通 LRC 完整。
            val songId = song.optLong("songid", song.optLong("id", 0L))
            val qrc = if (songId > 0L && qrcAttempts < 2) {
                qrcAttempts++
                runCatching { fetchQqRichLyrics(songId, headers) }.getOrNull()
            } else null
            val qrcRaw = qrc?.first.orEmpty()
            // 逐字覆盖率低于 70% 视为残缺（上游同样用 70% 门槛），回退普通 LRC。
            val useQrc = qrcRaw.isNotBlank() && wordCoverage(qrcRaw) >= 70

            val raw: String
            val trans: String
            if (useQrc) {
                raw = qrcRaw
                trans = qrc?.second.orEmpty()
            } else {
                val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=${enc(mid)}&format=json&nobase64=0&trans=1&g_tk=0"
                val lyricRoot = runCatching { JSONObject(unwrapJsonp(http(lyricUrl, headers))) }.getOrNull()
                    ?: continue
                raw = decodeLyric(lyricRoot.optString("lyric"))
                if (raw.isBlank()) continue
                trans = decodeLyric(lyricRoot.optString("trans"))
            }
            val interval = song.optLong("interval", 0L)
            out += Candidate(
                source = SOURCE_QQ,
                id = mid,
                title = song.optString("songname").ifBlank { title },
                artist = singerName.ifBlank { artist },
                album = song.optString("albumname"),
                durationMs = if (interval in 1..86_400) interval * 1000L else interval,
                raw = raw,
                translation = trans,
                coverUrl = albumArtUrl(song.optString("albummid")),
                hasWordTiming = if (useQrc) true else detectWordTiming(raw),
            )
        }
        return out
    }

    /**
     * QQ 音乐 QRC 富歌词：`lyric_download.fcg`（lrctype=4）返回加密的 hex 串，
     * 用非标 3DES 解密 + zlib 解压后得到 QRC XML，再取出 LyricContent 明文。
     * 同时取 contentts（译文 QRC）并转换成普通 LRC，供主页双语合并使用。
     */
    private fun fetchQqRichLyrics(
        songId: Long,
        headers: Map<String, String>,
    ): Pair<String, String>? {
        val response = postForm(
            "https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg",
            headers + ("Referer" to "https://c.y.qq.com/"),
            mapOf(
                "version" to "15",
                "miniversion" to "82",
                "lrctype" to "4",
                "musicid" to songId.toString()
            ),
            connectMs = 3_000,
            readMs = 5_000
        )
        if (!Regex("<result>\\s*0\\s*</result>").containsMatchIn(response)) return null
        val encrypted = extractQqTrack(response, "content")
        if (encrypted.isBlank()) return null
        val original = extractQqLyricContent(decodeQqTrack(encrypted))
        if (original.isBlank()) return null
        val translated = extractQqTrack(response, "contentts")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { qrcToLrc(extractQqLyricContent(decodeQqTrack(it))) }.getOrNull() }
            .orEmpty()
        return original to translated
    }

    /** QRC 响应里的 CDATA 字段（content = 原文，contentts = 译文）。 */
    private fun extractQqTrack(response: String, tag: String): String =
        Regex(
            "<${Regex.escape(tag)}(?:\\s[^>]*)?><!\\[CDATA\\[([\\s\\S]*?)]]></${Regex.escape(tag)}>",
            setOf(RegexOption.IGNORE_CASE)
        ).find(response)?.groupValues?.getOrNull(1).orEmpty().trim()

    /** 纯 hex 串交给 [QqQrcDecoder] 解密；已是明文时原样返回。 */
    private fun decodeQqTrack(payload: String): String {
        val v = payload.trim()
        val isHex = v.length >= 16 && v.length % 2 == 0 &&
            v.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        return if (isHex) QqQrcDecoder.decode(v) else v
    }

    /** 去掉 QRC 的 XML 外壳，只留 `[ms,dur]...` 歌词正文，并还原换行实体。 */
    private fun extractQqLyricContent(payload: String): String {
        val content = Regex(
            "LyricContent\\s*=\\s*\"([\\s\\S]*?)\"", RegexOption.IGNORE_CASE
        ).find(payload)?.groupValues?.getOrNull(1) ?: payload
        return content.replace("&#10;", "\n").replace("&#13;", "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    /** QRC → 普通 LRC：剥掉逐字标记，把毫秒行首换成 `[mm:ss.mmm]`。 */
    private fun qrcToLrc(payload: String): String {
        if (payload.isBlank()) return ""
        val qrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
        val marker = Regex("\\(\\d+,\\d+(?:,\\d+)?\\)")
        return payload.replace("\r", "").lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val m = qrcLine.matchEntire(line)
            if (m == null) {
                line.takeIf { hasLrcTimeTag(it) }
            } else {
                val start = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val text = m.groupValues[3].replace(marker, "").trim()
                if (text.isBlank()) null else "${formatMsLrc(start)}$text"
            }
        }.joinToString("\n")
    }

    private fun formatMsLrc(ms: Long): String {
        val v = ms.coerceAtLeast(0L)
        return String.format(Locale.US, "[%02d:%02d.%03d]", v / 60_000, (v / 1_000) % 60, v % 1_000)
    }

    private fun albumArtUrl(albumMid: String): String =
        if (albumMid.isBlank()) "" else "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"

    // ---------------------------------------------------------------- 网易云音乐

    private fun searchNetEase(title: String, artist: String): List<Candidate> {
        val headers = mapOf(
            "Referer" to "https://music.163.com/", "User-Agent" to UA,
            "Cookie" to "appver=2.0.2", "Accept" to "application/json"
        )
        val query = if (artist.isBlank()) title else "$title $artist"
        val searchUrl = "https://music.163.com/api/search/get/web?s=${enc(query)}&type=1&limit=10&offset=0"
        val songs = JSONObject(http(searchUrl, headers)).optJSONObject("result")
            ?.optJSONArray("songs") ?: return emptyList()

        val out = ArrayList<Candidate>()
        for (i in 0 until songs.length()) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optLong("id", 0L)
            if (id <= 0L) continue
            // 与 lyrics-android 一致的公开 Web 端点：os=pc 明文 GET，无需 eapi/weapi 加密。
            // yv=-1 是服务端返回 yrc（新版逐字歌词）的开关，tv=-1 返回 tlyric（译文）。
            // 缺 yv 时响应里根本没有 yrc 字段，这是此前「网易云没有逐字歌词」的主因。
            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$id" +
                "&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1"
            val root = runCatching { JSONObject(http(lyricUrl, headers)) }.getOrNull() ?: continue
            val raw = lyricTrack(root, "lrc")
            if (raw.isBlank()) continue
            val trans = lyricTrack(root, "tlyric")
            // 网易云 yrc.lyric 是字符串（此前误写成 yrc.lyric.lyric，导致恒为空）。
            // yrc 本身即 [行起点ms,持续ms](字绝对起点ms,持续ms,0)字 的规范逐字格式，
            // 直接交给 LyricsParser 即可；仅在缺失时回退 klyric。
            val wordRaw = normalizeNetEaseYrc(lyricTrack(root, "yrc"))
                .ifBlank { lyricTrack(root, "klyric") }
            val bestRaw = if (wordRaw.isNotBlank() && detectWordTiming(wordRaw)) wordRaw else raw
            val singers = song.optJSONArray("artists")
            val singerName = (0 until (singers?.length() ?: 0))
                .mapNotNull { singers?.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }.joinToString(" / ")
            out += Candidate(
                source = SOURCE_NETEASE,
                id = id.toString(),
                title = song.optString("name").ifBlank { title },
                artist = singerName.ifBlank { artist },
                album = song.optJSONObject("album")?.optString("name").orEmpty(),
                durationMs = song.optLong("duration", 0L),
                raw = bestRaw,
                translation = trans,
                coverUrl = song.optJSONObject("album")?.optJSONObject("picUrl")?.optString("picUrl").orEmpty().let {
                    // 老接口偶尔返回 picUrl 字符串而不是对象
                    if (it.isNotBlank()) it else song.optJSONObject("album")?.optString("picUrl").orEmpty()
                },
                hasWordTiming = detectWordTiming(bestRaw),
            )
        }
        return out
    }

    /**
     * 读取网易云歌词轨：正常响应是 `{ "lrc": { "lyric": "..." } }`，个别版本会把
     * lyric 再包一层对象。统一兼容两种形状，避免把 JSON 对象转成字符串当成歌词。
     */
    private fun lyricTrack(root: JSONObject, key: String): String {
        val track = root.optJSONObject(key) ?: return ""
        track.optJSONObject("lyric")?.let { return it.optString("lyric", "") }
        val value = track.opt("lyric") ?: return ""
        return if (value is String) value else ""
    }

    /**
     * 把两参数逐字标记 `(a,b)` 补成解析器要求的三参数 `(a,b,0)`：本项目
     * [LyricsParser] 用 `(a,b,0)` 区分「时间在前」的 YRC，用 `(a,b)` 区分
     * 「文字在前」的 QRC，旧版 YRC 若省略标志位会被误判成 QRC 导致字轴错位。
     * 网易云现行返回均为三参数，此处是兼容旧版本的防御性处理。
     */
    private val NETEASE_WORD_2 = Regex("""\((\d{1,8}),(\d{1,8})\)""")

    private fun normalizeNetEaseYrc(raw: String): String =
        NETEASE_WORD_2.replace(raw) { m -> "(${m.groupValues[1]},${m.groupValues[2]},0)" }

    // ---------------------------------------------------------------- 酷狗音乐

    private fun searchKuGou(title: String, artist: String, expectedDurationMs: Long): List<Candidate> {
        val headers = mapOf("User-Agent" to UA, "Accept" to "application/json")
        val query = if (artist.isBlank()) title else "$title - $artist"

        // ① 优先取 KRC 逐字轨（lyrics-android 的 krcs.kugou.com → lyrics2.kugou.com 链路）。
        //    KRC1 解密后是 `[ms,dur]<相对ms,dur,0>字`，正是 LyricsParser 支持的 KRC 语法。
        val krc = searchKuGouKrc(query, title, artist, expectedDurationMs, headers)
        if (krc.isNotEmpty()) return krc

        // ② 损坏 / 无 KRC / 解密失败：回退到原来的普通 LRC，保证搜索结果不为空。
        val searchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc" +
            "&keyword=${enc(query)}" +
            if (expectedDurationMs > 0L) "&duration=$expectedDurationMs" else ""
        val candidates = JSONObject(http(searchUrl, headers)).optJSONArray("candidates")
            ?: return emptyList()

        val out = ArrayList<Candidate>()
        for (i in 0 until candidates.length()) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val c = candidates.optJSONObject(i) ?: continue
            val id = c.optString("id")
            val key = c.optString("accesskey")
            if (id.isBlank() || key.isBlank()) continue
            val downloadUrl = "https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc" +
                "&ver=1&id=${enc(id)}&accesskey=${enc(key)}"
            val content = JSONObject(http(downloadUrl, headers)).optString("content")
            if (content.isBlank() || content.equals("null", true)) continue
            val raw = runCatching {
                String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8).removePrefix("\uFEFF")
            }.getOrNull() ?: continue
            if (raw.isBlank()) continue
            out += Candidate(
                source = SOURCE_KUGOU,
                id = id,
                title = c.optString("song").ifBlank { title },
                artist = c.optString("singer").ifBlank { artist },
                durationMs = normalizeKugouDuration(c.optLong("duration", 0L), expectedDurationMs),
                raw = raw,
                hasWordTiming = detectWordTiming(raw),
            )
        }
        return out
    }

    /**
     * 酷狗 KRC 逐字候选：`krcs.kugou.com/search` 找带 hash 的候选，
     * `lyrics2.kugou.com/download?fmt=krc` 拿到 base64 的 KRC1 二进制，
     * 交由 [KugouKrcDecoder] 解密成明文 KRC。
     */
    private fun searchKuGouKrc(
        query: String,
        title: String,
        artist: String,
        expectedDurationMs: Long,
        headers: Map<String, String>,
    ): List<Candidate> {
        val searchUrl = "https://krcs.kugou.com/search?ver=1&man=no&client=pc&lrctxt=1" +
            "&keyword=${enc(query)}" +
            if (expectedDurationMs > 0L) "&duration=$expectedDurationMs" else ""
        val candidates = runCatching {
            JSONObject(http(searchUrl, headers)).optJSONArray("candidates")
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<Candidate>()
        // KRC 下载 + 解密比 LRC 重，最多试前 2 个候选
        val limit = minOf(candidates.length(), 2)
        for (i in 0 until limit) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val c = candidates.optJSONObject(i) ?: continue
            val id = c.optString("download_id").ifBlank { c.optString("id") }
            val key = c.optString("accesskey")
            if (id.isBlank() || key.isBlank()) continue
            val downloadUrl = "https://lyrics2.kugou.com/download?fmt=krc&charset=utf8&client=pc" +
                "&ver=1&id=${enc(id)}&accesskey=${enc(key)}"
            val content = runCatching { JSONObject(http(downloadUrl, headers)).optString("content") }
                .getOrNull()
            if (content.isNullOrBlank() || content.equals("null", true)) continue
            val bytes = runCatching { Base64.decode(content, Base64.DEFAULT) }.getOrNull() ?: continue
            val raw = when {
                KugouKrcDecoder.isKrc1(bytes) -> KugouKrcDecoder.decode(bytes)
                // 少数响应直接给明文 KRC，不经 KRC1 封装
                else -> runCatching { String(bytes, Charsets.UTF_8).removePrefix("\uFEFF") }
                    .getOrNull()?.takeIf { it.contains("[") }
            } ?: continue
            // 覆盖率 < 70% 视为逐字轨残缺（与 QQ QRC / 上游 LyricParser 同一门槛）
            if (wordCoverage(raw) < 70) continue
            out += Candidate(
                source = SOURCE_KUGOU,
                id = id,
                title = c.optString("song").ifBlank { title },
                artist = c.optString("singer").ifBlank { artist },
                durationMs = normalizeKugouDuration(c.optLong("duration", 0L), expectedDurationMs),
                raw = raw,
                hasWordTiming = true,
            )
        }
        return out
    }

    /** 酷狗 duration 有秒 / 毫秒两种口径，按期望时长选更接近的一种。 */
    private fun normalizeKugouDuration(raw: Long, expected: Long): Long =
        if (raw in 1..86_400L && (expected <= 0L || abs(raw * 1000 - expected) < abs(raw - expected)))
            raw * 1000L else raw

    // ---------------------------------------------------------------- LrcApi（Capsulyric 独有）

    private fun searchLrcApi(title: String, artist: String): List<Candidate> {
        val url = "https://api.lrc.cx/lyrics?title=${enc(title)}&artist=${enc(artist)}"
        val raw = http(url, mapOf("User-Agent" to UA))
        if (raw.isBlank() || raw.contains("Lyrics not found", true)) return emptyList()
        if (raw.trim().startsWith("{") && runCatching { JSONObject(raw.trim()).has("detail") }.getOrDefault(false)) {
            return emptyList()
        }
        return listOf(
            Candidate(
                source = SOURCE_LRCAPI,
                id = "$title|$artist",
                title = title,
                artist = artist,
                raw = raw,
                hasWordTiming = detectWordTiming(raw),
            )
        )
    }

    // ---------------------------------------------------------------- 汽水音乐（Capsulyric 独有）

    private fun searchSoda(title: String, artist: String): List<Candidate> {
        val query = if (artist.isBlank()) title else "$title $artist"
        val url = "https://api.qishui.com/luna/pc/search/track?aid=386088&app_name=music" +
            "&q=${enc(query)}&cursor=0&search_method=input&page=1&page_size=5"
        val body = runCatching { http(url, mapOf("User-Agent" to UA, "Referer" to "https://api.qishui.com/")) }
            .getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val tracks = findArray(root, "track_list", "tracks", "data", "results") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until tracks.length()) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val t = tracks.optJSONObject(i) ?: continue
            val lrc = firstString(t, "lyrics", "lyric", "lrc", "lyric_content")
            if (lrc.isBlank()) continue
            out += Candidate(
                source = SOURCE_SODA,
                id = firstString(t, "id", "track_id", "rid").ifBlank { "$i" },
                title = firstString(t, "title", "name", "track_name").ifBlank { title },
                artist = firstString(t, "artist", "singer", "author").ifBlank { artist },
                durationMs = t.optLong("duration", 0L),
                raw = lrc,
                hasWordTiming = detectWordTiming(lrc),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- Musixmatch（Capsulyric 独有）

    private fun searchMusixmatch(title: String, artist: String): List<Candidate> {
        val now = System.currentTimeMillis()
        val base = "https://apic-desktop.musixmatch.com/ws/1.1"
        val tokenRoot = JSONObject(
            http("$base/token.get?app_id=web-desktop-app-v1.0&t=$now", mapOf("User-Agent" to UA))
        )
        val status = tokenRoot.optJSONObject("message")?.optJSONObject("header")?.optInt("status_code", 0)
        if (status != 200) return emptyList()
        val token = tokenRoot.optJSONObject("message")?.optJSONObject("body")
            ?.optString("user_token").orEmpty()
        if (token.isBlank()) return emptyList()

        val url = "$base/macro.search?app_id=web-desktop-app-v1.0&usertoken=${enc(token)}" +
            "&q_track=${enc(title)}&q_artist=${enc(artist)}&page_size=5&page=1" +
            "&format=json&part=lyrics,track_lyrics"
        val list = JSONObject(http(url, mapOf("User-Agent" to UA)))
            .optJSONObject("message")?.optJSONObject("body")
            ?.optJSONArray("macro_result_list") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until list.length()) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val entry = list.optJSONObject(i) ?: continue
            val track = entry.optJSONObject("track") ?: continue
            val body = entry.optJSONObject("lyrics") ?: entry.optJSONObject("track_lyrics") ?: continue
            val raw = body.optString("lyrics_body").orEmpty()
            if (raw.isBlank()) continue
            out += Candidate(
                source = SOURCE_MUSIXMATCH,
                id = track.optLong("track_id", 0L).toString().ifBlank { "$i" },
                title = track.optString("track_name").ifBlank { title },
                artist = track.optString("artist_name").ifBlank { artist },
                album = track.optString("album_name"),
                durationMs = track.optLong("track_length", 0L) * 1000L,
                raw = plainToLrc(raw),
                hasWordTiming = false,
            )
        }
        return out
    }

    // ---------------------------------------------------------------- Apple Music（Capsulyric 独有，逐字 TTML）

    private fun searchApple(title: String, artist: String, token: String): List<Candidate> {
        if (token.isBlank()) return emptyList()
        val headers = mapOf(
            "Media-User-Token" to token, "User-Agent" to UA,
            "Referer" to "https://music.apple.com/", "Accept" to "application/json"
        )
        val query = if (artist.isBlank()) title else "$title $artist"
        val root = JSONObject(
            http("https://amp-api.music.apple.com/v1/catalog/cn/search?term=${enc(query)}&types=songs&limit=5", headers)
        )
        val songs = root.optJSONObject("results")?.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until songs.length()) {
            if (out.size >= MAX_RESULTS_PER_SOURCE) break
            val song = songs.optJSONObject(i) ?: continue
            val id = song.optString("id")
            val attrs = song.optJSONObject("attributes") ?: continue
            val lyricRoot = runCatching {
                JSONObject(http("https://amp-api.music.apple.com/v1/catalog/cn/songs/$id/lyrics", headers))
            }.getOrNull() ?: continue
            val ttml = lyricRoot.optJSONArray("data")?.optJSONObject(0)
                ?.optJSONObject("attributes")?.optString("ttml").orEmpty()
            if (ttml.isBlank()) continue
            out += Candidate(
                source = SOURCE_APPLE,
                id = id,
                title = attrs.optString("name").ifBlank { title },
                artist = attrs.optString("artistName").ifBlank { artist },
                album = attrs.optString("albumName"),
                durationMs = attrs.optLong("durationInMillis", 0L),
                raw = ttml,
                hasWordTiming = true,
                coverUrl = attrs.optString("artwork").orEmpty(),
            )
        }
        return out
    }

    // ---------------------------------------------------------------- 工具

    /** 简单 GET，返回响应文本；非 2xx 直接抛异常。 */
    private fun http(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_CONNECT
            readTimeout = TIMEOUT_READ
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val stream: InputStream = conn.inputStream
            val encoding = conn.contentEncoding
            val bytes = stream.use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bos.write(buf, 0, n)
                }
                bos.toByteArray()
            }
            if (encoding?.contains("gzip", true) == true) {
                return java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** POST application/x-www-form-urlencoded，返回响应文本（QQ QRC 接口用）。 */
    private fun postForm(
        url: String,
        headers: Map<String, String>,
        form: Map<String, String>,
        connectMs: Int = TIMEOUT_CONNECT,
        readMs: Int = TIMEOUT_READ,
    ): String {
        val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectMs
            readTimeout = readMs
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** 去掉 QQ 音乐等返回的 JSONP 回调壳。 */
    private fun unwrapJsonp(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val start = trimmed.indexOf('(')
        val end = trimmed.lastIndexOf(')')
        return if (start in 0 until end) trimmed.substring(start + 1, end) else trimmed
    }

    /** QQ 音乐歌词字段可能是 base64，也可能是纯文本/JSONP 片段。 */
    private fun decodeLyric(value: String): String {
        if (value.isBlank() || value.equals("null", true)) return ""
        if (value.trim().startsWith("[") || value.contains("[00:") || value.contains("<")) return value
        return runCatching {
            String(Base64.decode(value.trim(), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(value)
    }

    /** Musixmatch 只有纯文本，包装成最简 LRC 以便本项目解析器处理。 */
    private fun plainToLrc(text: String): String =
        if (hasLrcTimeTag(text)) text
        else buildString {
            appendLine("[ti:在线歌词]")
            text.lineSequence().forEach { appendLine("[00:00.00]$it") }
        }

    private fun hasLrcTimeTag(text: String): Boolean =
        Regex("\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?]").containsMatchIn(text)

    /** 毫秒制行首 `[ms,dur]`（QRC / KRC / YRC / Lyricify Lines）。 */
    private val MS_TIMED_LINE = Regex("""\[\d{1,8},\d{1,8}]""")

    /** 带时间轴的歌词行行首（LRC `[mm:ss.xx]` 或毫秒制 `[ms,dur]`）。 */
    private val TIMED_LINE = Regex("""^\s*(?:\[\d{1,3}:\d{2}(?:[.:]\d{1,3})?]|\[\d{1,8},\d{1,8}])""")

    /** 行内逐字标记：YRC/QRC 的 `(a,b[,c])`、KRC 的 `<a,b[,c]>`、增强型 LRC 的 `<mm:ss.xx>`、TTML span。 */
    private val WORD_MARKER = Regex(
        """(?:\(\d{1,8},\d{1,8}(?:,\d+)?\)|<-?\d{1,8},-?\d{1,8}(?:,\d+)?>|<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>|<span[^>]*\bbegin\s*=|"type"\s*:\s*"syllable")""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 逐字覆盖率：带逐字标记的歌词行 / 全部带时间轴歌词行（0~100）。
     *
     * 这是 lyrics-android `coverage()` 的等价实现，用于替代原来的 `hasWordTiming`
     * 布尔加权——只有零星几行带逐字的残缺版本不该与整首逐字版本同分。
     */
    fun wordCoverage(raw: String): Int {
        if (raw.isBlank()) return 0
        if (raw.contains("<span", true) && raw.contains("begin", true)) return 100 // TTML
        var timed = 0
        var worded = 0
        raw.lineSequence().forEach { line ->
            if (!TIMED_LINE.containsMatchIn(line)) return@forEach
            timed++
            val body = TIMED_LINE.replaceFirst(line, "")
            if (WORD_MARKER.containsMatchIn(body)) worded++
        }
        return if (timed == 0) 0 else (worded * 100 / timed).coerceIn(0, 100)
    }

    /** 识别 KRC/QRC/YRC/WRC/TRC/TTML 等带逐字时间轴的歌词。 */
    fun detectWordTiming(text: String): Boolean = when {
        text.contains("<span", true) && text.contains("begin", true) -> true   // TTML
        WORD_MARKER.containsMatchIn(text) -> true
        Regex("<\\[?\\d{1,4}[,:]\\d{1,5}[,:]?\\d*\\]?>").containsMatchIn(text) -> true
        Regex("\\[\\d{1,4},\\d{1,6}(?:,\\d+)?]").containsMatchIn(text) -> true  // YRC / 逐字
        text.contains("\"type\"", true) && text.contains("syllable", true) -> true
        else -> false
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[\\s\\p{Punct}\\uFF00-\\uFFEF]"), "")
        .replace(Regex("\\((live|remix|cover|伴奏|纯音乐|inst\\.?|official|版|feat)[^)]*\\)"), "")
        .trim()

    /** 归一后的最长公共子序列比例，用于标题近似匹配。 */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val prev = IntArray(y.length + 1)
        val cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            for (j in 1..y.length) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            prev.indices.forEach { prev[it] = cur[it] }
            cur.fill(0)
        }
        return 2.0 * prev[y.length] / (x.length + y.length)
    }

    private fun findArray(root: JSONObject, vararg names: String): JSONArray? {
        for (name in names) {
            val direct = root.optJSONArray(name)
            if (direct != null) return direct
            val nested = root.optJSONObject(name) ?: continue
            for (inner in arrayOf("list", "items", "tracks", "data", "track_list")) {
                nested.optJSONArray(inner)?.let { return it }
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg names: String): String {
        for (name in names) {
            val value = obj.optString(name, "")
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    /** 毫秒 → mm:ss。 */
    fun formatClock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
    }

    // ---------------------------------------------------------------- 译文合并

    /**
     * 把独立译文轨按时间戳并入主歌词：本项目把「连续相同时间戳的多行」识别为双语组，
     * 因此只要在原文行后插入同时间戳的译文行即可获得双语显示。
     *
     * 主歌词既可能是 LRC（`[mm:ss.xx]`），也可能是 QQ QRC / 酷狗 KRC / 网易云 YRC
     * 的毫秒制（`[ms,dur]`）。两种行首都换算成绝对毫秒后查表，插入的译文行沿用**同一种**
     * 行首，这样原文的逐字时间轴不会被破坏，双语分组仍然生效。
     *
     * 对齐策略参照 lyrics-android：
     *  1. 精确时间戳 / 10ms 归桶 —— 原文与译文来自同一份 LRC 时二者时间戳完全一致；
     *  2. 双指针就近匹配（容差 1200ms）—— 网易云 YRC 的行起点是「起唱时刻」，而
     *     tlyric 沿用 LRC 时间戳，实测二者系统性相差约 400~540ms，仅做精确匹配会让
     *     译文全部丢失，因此必须允许就近匹配；
     *  3. 译文无时间戳时按行号对齐（纯文本译文）。
     *
     * 手动选用与自动在线获取共用此实现，保证写入 [OnlineLyricCache] 的内容一致。
     */
    fun mergedLyric(candidate: Candidate): String {
        val main = candidate.raw
        val trans = candidate.translation
        if (trans.isBlank()) return main

        val stampRe = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)")
        val timed = ArrayList<Pair<Long, String>>()
        stampRe.findAll(trans).forEach { m ->
            val min = m.groupValues[1].toLongOrNull() ?: return@forEach
            val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
            val frac = m.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
            // 同一行可能带多个时间戳，剥掉正文里残留的行首标签再存
            val text = Regex("^\\[[^]]*]").replace(m.groupValues[4], "").trim()
            if (text.isNotEmpty()) timed.add(((min * 60 + sec) * 1000 + frac) to text)
        }
        if (timed.isEmpty()) return mergePlainTranslation(main, trans)

        timed.sortBy { it.first }
        // 10ms 归桶：容忍同一时间戳在原文/译文里的毫秒级写法差异
        val bucket = HashMap<Long, String>()
        timed.forEach { (ms, text) -> bucket[(ms + 5L) / 10L] = text }
        var cursor = 0

        fun resolve(ms: Long): String? {
            bucket[(ms + 5L) / 10L]?.let { return it }
            while (cursor + 1 < timed.size &&
                abs(timed[cursor + 1].first - ms) <= abs(timed[cursor].first - ms)
            ) cursor++
            val nearest = timed.getOrNull(cursor) ?: return null
            return if (abs(nearest.first - ms) <= TRANSLATION_TOLERANCE_MS) nearest.second else null
        }

        return buildString {
            var lastStamp = ""
            main.lineSequence().forEach { line ->
                appendLine(line)
                val stamp = TIMED_LINE.find(line)?.value ?: return@forEach
                if (stamp == lastStamp) return@forEach
                lastStamp = stamp
                val ms = headToMs(stamp) ?: return@forEach
                val text = resolve(ms) ?: return@forEach
                if (text.isNotBlank()) appendLine("$stamp$text")
            }
        }
    }

    /** 无时间戳的纯文本译文：按行号与原文带时间轴的行依次对齐。 */
    private fun mergePlainTranslation(main: String, trans: String): String {
        val plain = trans.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("[") }
            .toList()
        if (plain.isEmpty()) return main
        var index = 0
        return buildString {
            var lastStamp = ""
            main.lineSequence().forEach { line ->
                appendLine(line)
                val stamp = TIMED_LINE.find(line)?.value ?: return@forEach
                if (stamp == lastStamp) return@forEach
                lastStamp = stamp
                val text = plain.getOrNull(index++) ?: return@forEach
                appendLine("$stamp$text")
            }
        }
    }

    /** 歌词行行首（LRC `[mm:ss.xx]` 或 QRC/KRC/YRC 的 `[ms,dur]`）→ 绝对毫秒。 */
    private fun headToMs(head: String): Long? {
        val clock = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]").find(head)
        if (clock != null) {
            val min = clock.groupValues[1].toLongOrNull() ?: return null
            val sec = clock.groupValues[2].toLongOrNull() ?: return null
            val frac = clock.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
            return (min * 60 + sec) * 1000 + frac
        }
        return Regex("\\[(\\d{1,8}),\\d{1,8}]").find(head)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

}
