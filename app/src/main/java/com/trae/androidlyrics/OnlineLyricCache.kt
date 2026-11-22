package com.trae.androidlyrics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 「在线搜索歌词」的独立缓存目录。
 *
 * 与用户现成的歌词存储目录 `filesDir/lyrics/` 分工明确、索引互不交叉：
 * - `online_lyrics/`（本类）：只存放在线接口返回的歌词文件和一份 `index.json` 索引，
 *   专用于「联网前去重」。索引同时记录「已查过但没有结果」的负缓存，避免同一首歌
 *   在切歌、重启、作者字段迟到等场景下反复向公开歌词接口发起请求。
 * - `lyrics/`：用户导入、编辑、保存的歌词，由 LyricStorageActivity / LyricMatcher /
 *   LyricIndex 扫描与匹配。仅当在线歌词被「应用」时，才由 [copyToLyricsStore] 从本目录
 *   **复制一份**过去，方便在歌词存储统一管理；本类不会把 `lyrics/` 纳入缓存判定，
 *   LyricMatcher 也不会扫描 `online_lyrics/`。
 */
object OnlineLyricCache {

    /** 独立缓存目录名（相对 filesDir）。 */
    const val DIR_NAME = "online_lyrics"

    private const val INDEX_NAME = "index.json"

    /** 「无结果」负缓存的有效期：到期后允许重新联网查一次。 */
    private const val EMPTY_TTL_MS = 3L * 24 * 60 * 60 * 1000

    /** 查询结果三态。 */
    sealed class Lookup {
        /** 命中本地缓存文件，应直接读取，不再发起任何网络请求。 */
        data class Hit(val file: File, val entry: Entry) : Lookup()

        /** 之前查过且确认没有结果（负缓存仍在有效期内）。 */
        object Empty : Lookup()

        /** 从未查过，或负缓存已过期，可以联网。 */
        object None : Lookup()
    }

    data class Entry(
        val key: String,
        val fileName: String,
        val found: Boolean,
        val hasWordTiming: Boolean,
        val source: String,
        val timeMs: Long,
    )

    /** 索引只在首次访问时读盘，后续走内存；写入统一由同步方法串行化。 */
    private var indexCache: LinkedHashMap<String, Entry>? = null

    /** 历史缓存补写歌词存储：每个进程只尝试一次，靠目录内的标记文件跨进程去重。 */
    @Volatile private var storeSyncTried = false

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), INDEX_NAME)

    /** 缓存键：与歌曲绑定同样的「标题|作者」语义，统一小写以免大小写抖动重复请求。 */
    fun keyOf(title: String, artist: String): String =
        "${title.trim().lowercase(Locale.ROOT)}|${artist.trim().lowercase(Locale.ROOT)}"

    /** 标题别名键：作者字段晚到时 key 会变，用别名复用同一次在线结果。 */
    private fun aliasKeyOf(title: String): String = "${title.trim().lowercase(Locale.ROOT)}|"

    private fun idOf(key: String): String = runCatching {
        val bytes = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        buildString(bytes.size * 2) {
            for (b in bytes.take(8)) append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xff))
        }
    }.getOrDefault(Integer.toHexString(key.hashCode()))

    private fun loadIndex(context: Context): LinkedHashMap<String, Entry> {
        indexCache?.let { return it }
        val map = LinkedHashMap<String, Entry>()
        runCatching {
            val f = indexFile(context)
            if (!f.exists()) return@runCatching
            val obj = JSONObject(f.readText(Charsets.UTF_8))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val o = obj.optJSONObject(id) ?: continue
                map[id] = Entry(
                    key = o.optString("key", ""),
                    fileName = o.optString("file", ""),
                    found = o.optBoolean("found", false),
                    hasWordTiming = o.optBoolean("word", false),
                    source = o.optString("source", ""),
                    timeMs = o.optLong("time", 0L),
                )
            }
        }
        indexCache = map
        return map
    }

    private fun writeIndex(context: Context, map: LinkedHashMap<String, Entry>) {
        runCatching {
            val obj = JSONObject()
            map.forEach { (id, e) ->
                obj.put(
                    id, JSONObject()
                        .put("key", e.key)
                        .put("file", e.fileName)
                        .put("found", e.found)
                        .put("word", e.hasWordTiming)
                        .put("source", e.source)
                        .put("time", e.timeMs)
                )
            }
            val target = indexFile(context)
            val tmp = File(target.parentFile, "$INDEX_NAME.tmp")
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            // renameTo 在个别文件系统上覆盖已存在文件会失败，失败时退回直接写入
            if (!tmp.renameTo(target)) target.writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    /**
     * 联网前查询缓存。优先返回可用文件；只有所有键都是「确认无结果」时才返回 Empty。
     * 缓存文件被外部删除时清理陈旧索引，允许重新联网补齐。
     */
    @Synchronized
    fun lookup(context: Context, title: String, artist: String): Lookup {
        val map = loadIndex(context)
        val keys = linkedSetOf(keyOf(title, artist), aliasKeyOf(title))
        var negative = false
        val now = System.currentTimeMillis()
        for (k in keys) {
            val id = idOf(k)
            val e = map[id] ?: continue
            if (!e.found) {
                if (now - e.timeMs < EMPTY_TTL_MS) negative = true
                continue
            }
            val f = File(dir(context), e.fileName)
            if (f.isFile && f.length() > 0L) return Lookup.Hit(f, e)
            map.remove(id)
            writeIndex(context, map)
        }
        return if (negative) Lookup.Empty else Lookup.None
    }

    /**
     * 把在线歌词写入独立缓存目录并登记索引（含标题别名），返回实际落盘的文件。
     * 同一首歌再次保存会覆盖同名文件，不会产生垃圾文件堆积。
     */
    @Synchronized
    fun save(
        context: Context,
        title: String,
        artist: String,
        candidate: OnlineLyrics.Candidate,
        content: String,
    ): File? {
        if (content.isBlank()) return null
        val key = keyOf(title, artist)
        val id = idOf(key)
        val file = File(dir(context), "${safeStem(title, artist, candidate)}-$id.lrc")
        val ok = runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) file.writeText(content, Charsets.UTF_8)
        }.isSuccess
        if (!ok || !file.isFile || file.length() == 0L) return null

        val entry = Entry(
            key = key,
            fileName = file.name,
            found = true,
            hasWordTiming = candidate.hasWordTiming,
            source = candidate.source,
            timeMs = System.currentTimeMillis(),
        )
        val map = loadIndex(context)
        map[id] = entry
        val alias = aliasKeyOf(title)
        if (alias != key) map[idOf(alias)] = entry.copy(key = alias)
        writeIndex(context, map)
        return file
    }

    /**
     * 把「已应用」的在线歌词从独立缓存目录复制一份到 `lyrics/` 歌词存储目录。
     *
     * 文件名沿用旧版点击「使用此歌词」时的规则：`歌曲名 - 歌手.lrc`（过滤非法字符、
     * 最多 80 字），重名时依次追加 ` (1)`、` (2)`…；**不带缓存哈希后缀**，因此歌词存储页
     * 与主页标题栏显示的都是可读的歌曲名，而不是缓存名。同名且内容完全一致时直接复用
     * 已有文件，避免反复应用同一份在线歌词堆出多个副本。
     *
     * 这里只做文件复制，不改变两个目录各自的索引职责：联网去重仍只查 `online_lyrics/`。
     */
    @Synchronized
    fun copyToLyricsStore(
        context: Context,
        cacheFile: File,
        candidate: OnlineLyrics.Candidate,
    ): File? = copyNamed(
        context, cacheFile,
        "${candidate.title} - ${candidate.artist}".trim().ifBlank { candidate.title }
    )

    /** 按给定名称把缓存文件复制进 `lyrics/`；手动选用与历史补写共用同一套规则。 */
    @Synchronized
    private fun copyNamed(context: Context, cacheFile: File, rawName: String): File? {
        if (!cacheFile.isFile || cacheFile.length() == 0L) return null
        val app = context.applicationContext
        val dir = File(app.filesDir, "lyrics").apply { mkdirs() }
        val safeName = rawName
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
            .take(80)
            .trim()
            .ifBlank { "在线歌词" }
        val bytes = runCatching { cacheFile.readBytes() }.getOrNull() ?: return null
        val base = File(dir, "$safeName.lrc")
        if (base.isFile && runCatching { base.readBytes().contentEquals(bytes) }.getOrDefault(false)) {
            LyricIndex.invalidate()
            return base
        }
        var target = base
        var index = 1
        while (target.exists()) target = File(dir, "$safeName (${index++}).lrc")
        val ok = runCatching {
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) target.writeBytes(bytes)
            if (tmp.exists()) tmp.delete()
        }.isSuccess
        if (!ok || !target.isFile || target.length() == 0L) return null
        // lyrics/ 内容变化：让目录快照与匹配结果缓存立即失效
        LyricIndex.invalidate()
        return target
    }

    /**
     * 版本兼容：把旧版本「已应用」但只留在 `online_lyrics/` 的歌词补一份到歌词存储。
     *
     * 旧缓存索引里没有候选曲名，因此从缓存文件名 `歌曲名 - 歌手-<hash>.lrc` 还原可读
     * 名称，再走与新流程完全相同的复制 / 去重逻辑；只执行一次（标记文件），用户之后
     * 在歌词存储里删掉的文件不会被反复重建。
     */
    @Synchronized
    fun syncAppliedToLyricsStore(context: Context) {
        if (storeSyncTried) return
        storeSyncTried = true
        val app = context.applicationContext
        val cacheDir = dir(app)
        val marker = File(cacheDir, ".lyrics_store_synced")
        if (marker.exists()) return
        runCatching {
            val unique = LinkedHashMap<String, File>()
            loadIndex(app).values.forEach { e ->
                if (!e.found || e.fileName.isBlank()) return@forEach
                File(cacheDir, e.fileName).takeIf { it.isFile }?.let { unique[e.fileName] = it }
            }
            unique.forEach { (name, cacheFile) ->
                // 去掉 .lrc 与末尾的 -<16位哈希>，保留可读的「歌曲名 - 歌手」
                val stem = name.removeSuffix(".lrc").substringBeforeLast("-").trim()
                if (stem.isNotEmpty()) copyNamed(app, cacheFile, stem)
            }
            marker.createNewFile()
        }.onFailure { LogCapture.w("OnlineCache", "历史在线歌词补写失败：${it.message}") }
    }

    /** 在线搜索确实执行过但没有结果：写入负缓存，避免反复打接口。 */
    @Synchronized
    fun markEmpty(context: Context, title: String, artist: String) {
        val key = keyOf(title, artist)
        val entry = Entry(key, "", false, false, "", System.currentTimeMillis())
        val map = loadIndex(context)
        map[idOf(key)] = entry
        val alias = aliasKeyOf(title)
        if (alias != key) map[idOf(alias)] = entry.copy(key = alias)
        writeIndex(context, map)
    }

    /** 独立缓存目录内的文件被改名时同步索引，避免索引仍指向旧文件名。 */
    @Synchronized
    fun renameFile(context: Context, oldName: String, newName: String) {
        val map = loadIndex(context)
        val changed = map.entries.filter { it.value.fileName == oldName }
        if (changed.isEmpty()) return
        changed.forEach { entry ->
            map[entry.key] = entry.value.copy(fileName = newName)
        }
        writeIndex(context, map)
    }

    /** 清掉所有「无结果」负缓存：用户重新开启自动获取开关时调用，让设置立即生效。 */
    @Synchronized
    fun clearEmptyRecords(context: Context) {
        val map = loadIndex(context)
        val stale = map.filterValues { !it.found }.keys.toList()
        if (stale.isEmpty()) return
        stale.forEach { map.remove(it) }
        writeIndex(context, map)
    }

    private fun safeStem(title: String, artist: String, candidate: OnlineLyrics.Candidate): String {
        val base = "${title.trim().ifBlank { candidate.title }} - ${artist.trim().ifBlank { candidate.artist }}"
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .take(50)
            .trim()
        return base.ifBlank { "在线歌词" }
    }
}
