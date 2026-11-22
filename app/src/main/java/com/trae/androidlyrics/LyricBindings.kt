package com.trae.androidlyrics

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 歌曲-歌词绑定：把"用户为当前播放歌曲显式选择过的歌词文件"持久化到内部存储文件，
 * 替代原来基于最近打开列表的加分制匹配，避免最近列表条目过多导致加分失效。
 *
 * 仅在用户主动打开/切换当前播放歌曲的歌词时记录；自动匹配结果不写入，
 * 避免污染绑定数据。
 */
object LyricBindings {

    private const val FILE_NAME = "lyric_bindings.json"
    private const val MAX_BINDINGS = 500

    private var cache: LinkedHashMap<String, Entry>? = null

    private data class Entry(val fileName: String, val timeMs: Long)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    private fun load(context: Context): LinkedHashMap<String, Entry> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Entry>()
        runCatching {
            val text = file(context).takeIf { it.exists() }?.readText() ?: return@runCatching
            val obj = JSONObject(text)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = obj.optJSONObject(k) ?: continue
                val fileName = o.optString("file", "").takeIf { it.isNotEmpty() } ?: continue
                val time = o.optLong("time", 0L)
                map[k] = Entry(fileName, time)
            }
        }
        cache = map
        return map
    }

    /** 生成绑定键：与 [Session.matchedKey] 保持一致。 */
    fun keyOf(title: String, artist: String): String = "$title|$artist"

    /**
     * 把绑定表里存的值解析成实际文件。
     *
     * 普通值是 `lyrics/` 目录下的文件名；带 `online_lyrics/` 前缀的值来自
     * [OnlineLyricCache] 的独立在线歌词缓存目录。后者仍只是「绑定指向哪份歌词」，
     * 不参与 `lyrics/` 的扫描 / 匹配 / 歌词管理，两个目录不会被合并处理。
     */
    fun resolveFile(context: Context, stored: String): File =
        if (stored.startsWith("${OnlineLyricCache.DIR_NAME}/")) {
            File(context.applicationContext.filesDir, stored)
        } else {
            File(context.applicationContext.filesDir, "lyrics/$stored")
        }

    /** 由实际文件反推绑定值：独立缓存目录里的文件必须带上目录前缀。 */
    fun bindingValue(context: Context, file: File): String {
        val onlineDir = OnlineLyricCache.dir(context)
        return if (file.parentFile?.absolutePath == onlineDir.absolutePath) {
            "${OnlineLyricCache.DIR_NAME}/${file.name}"
        } else file.name
    }

    /**
     * 查询指定歌曲是否有用户显式绑定过的歌词文件名。
     * 文件不存在时自动清理该条绑定。
     */
    @Synchronized
    fun get(context: Context, title: String, artist: String): String? {
        val key = keyOf(title, artist)
        val entry = load(context)[key] ?: return null
        val target = resolveFile(context, entry.fileName)
        return if (target.exists()) {
            entry.fileName
        } else {
            // 绑定指向的文件已被清理，移除失效绑定避免永远优先命中缺失文件
            load(context).remove(key)
            save(context)
            null
        }
    }

    /**
     * 记录绑定：用户在播放 [title]/[artist] 时显式打开了 [fileName]。
     * 仅当文件名与歌曲信息匹配时才写入，避免把无关文件一股脑记入。
     */
    @Synchronized
    fun put(context: Context, title: String, artist: String, fileName: String) {
        if (!LyricMatcher.nameMatches(fileName, title, artist)) return
        record(context, title, artist, fileName)
    }

    /**
     * 强制记录绑定：用于"手动添加歌词"等用户明确指定的场景，
     * 不受文件名相似度校验限制（用户的选择优先）。
     */
    @Synchronized
    fun putForce(context: Context, title: String, artist: String, fileName: String) {
        record(context, title, artist, fileName)
    }

    private fun record(context: Context, title: String, artist: String, fileName: String) {
        val map = load(context)
        val key = keyOf(title, artist)
        map.remove(key)
        map[key] = Entry(fileName, System.currentTimeMillis())
        // 超出上限时淘汰最旧的记录
        while (map.size > MAX_BINDINGS) {
            val oldest = map.entries.minByOrNull { it.value.timeMs }?.key ?: break
            map.remove(oldest)
        }
        save(context)
    }

    /**
     * 清理指向已不存在歌词文件的绑定。
     *
     * 用于数据管理页面删除歌词文件后同步，避免绑定继续指向缺失文件。
     */
    @Synchronized
    fun purgeMissing(context: Context) {
        val map = load(context)
        val missing = map.keys.filter { key ->
            val entry = map[key] ?: return@filter true
            !resolveFile(context, entry.fileName).exists()
        }
        if (missing.isEmpty()) return
        missing.forEach { map.remove(it) }
        save(context)
    }

    /**
     * 歌词文件重命名后迁移所有指向旧文件名的歌曲绑定。
     *
     * 必须紧跟文件改名同步调用：[get] 会清理指向不存在文件的绑定，
     * 晚一步执行就会把绑定整条丢掉，自动匹配随之失效。
     */
    @Synchronized
    fun renameFile(context: Context, oldName: String, newName: String) {
        if (oldName == newName) return
        val map = load(context)
        // 绑定值可能是纯文件名（lyrics/），也可能带 online_lyrics/ 前缀；
        // 两种都按「末段文件名」比较，并保留原有前缀
        val toRename = map.entries.filter {
            it.value.fileName == oldName || it.value.fileName.substringAfterLast('/') == oldName
        }
        if (toRename.isEmpty()) return
        toRename.forEach { entry ->
            val stored = entry.value.fileName
            val prefix = stored.substringBeforeLast('/', "")
                .let { if (it.isEmpty()) "" else "$it/" }
            map[entry.key] = Entry(prefix + newName, entry.value.timeMs)
        }
        save(context)
    }

    @Synchronized
    private fun save(context: Context) {
        val map = cache ?: return
        runCatching {
            val obj = JSONObject()
            map.forEach { (k, v) ->
                obj.put(k, JSONObject().put("file", v.fileName).put("time", v.timeMs))
            }
            file(context).writeText(obj.toString())
        }
    }
}
