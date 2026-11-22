package com.trae.androidlyrics

import java.io.File
import java.text.Normalizer

/**
 * 歌词文件名归一化工具。
 *
 * 由 [LyricIndex] 在建立目录快照时批量调用、由 [LyricMatcher] 在单文件名校验时调用，
 * 保证两条路径使用完全相同的归一化结果。正则预编译，避免每个文件重复 Pattern.compile。
 */
internal object LyricText {

    /** 原路径归一化：小写并移除空白与常见标点，符号内容仍在串里。 */
    private val NORMALIZE_REGEX = Regex("[\\s\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]+")

    fun normalize(s: String): String = s.lowercase().replace(NORMALIZE_REGEX, "")

    /**
     * 纯文字过滤结果。
     * [value] 只保留字母/数字；[symbolAfter] 按「文字字符序号」记录该字符之后
     * 紧跟的是否为非文字符号，等价于旧实现 followedBySymbol(p, wordCount) 的查询。
     */
    class PlainInfo(val value: String, val symbolAfter: BooleanArray)

    /**
     * 只保留文字：先 NFKC 兼容归一（全角字母数字 → 半角）再小写，
     * 非「字母/数字」的字符（标点、符号、空白、emoji、组合音标）全部丢弃。
     */
    fun plainInfo(s: String): PlainInfo {
        val src = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val value = StringBuilder(src.length)
        val after = BooleanArray(src.length)
        var count = 0
        var i = 0
        while (i < src.length) {
            val cp = src.codePointAt(i)
            val width = Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) {
                value.appendCodePoint(cp)
                val next = i + width
                after[count++] = next < src.length &&
                    !Character.isLetterOrDigit(src.codePointAt(next))
            }
            i += width
        }
        return PlainInfo(value.toString(), after.copyOf(count))
    }
}

/** 一个歌词文件的预计算结果：文件名相关字段只算一次，后续所有歌曲查询复用。 */
internal class LyricEntry(
    val file: File,
    val name: String,
    val lowerStem: String,
    val normStem: String,
    val plain: LyricText.PlainInfo,
    val mtime: Long,
    val size: Long,
)

/**
 * 歌词目录快照索引。
 *
 * 多文件场景下，旧实现每切一首歌都要对目录内每个文件重做 NFKC、正则替换、
 * 小写化和临时对象分配。这里把「与歌曲无关」的归一化结果缓存为不可变条目，
 * 目录未变化时一次 stat 即可复用；目录变化时按 (name, mtime, size) 增量重建，
 * 只重新计算真正变化的文件。
 *
 * 失效判定双保险：
 *  - 目录 mtime：新增 / 删除 / 重命名会改变目录项时间；
 *  - [invalidate] 递增 epoch：应用内每次写入歌词文件后主动调用，
 *    覆盖同毫秒内连续变更、以及文件系统时间戳精度不足的情况。
 */
object LyricIndex {

    internal data class Stamp(val mtime: Long, val epoch: Long)

    internal class Snapshot(val stamp: Stamp, val entries: List<LyricEntry>)

    @Volatile private var epoch = 0L
    private var cached: Snapshot? = null
    private var cachedDir: String? = null

    /** 歌词目录内容发生变化时调用：丢弃快照并使所有匹配结果缓存的 key 失效。 */
    @Synchronized
    fun invalidate() {
        epoch++
        cached = null
        cachedDir = null
    }

    /** 取目录快照；目录未变化时 O(1) 复用，变化时增量重建。 */
    @Synchronized
    internal fun snapshot(dir: File): Snapshot {
        val path = dir.absolutePath
        val startEpoch = epoch
        val startStamp = Stamp(runCatching { dir.lastModified() }.getOrDefault(0L), startEpoch)
        val existing = cached
        if (existing != null && cachedDir == path && existing.stamp == startStamp) return existing

        val files = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in LyricsParser.SUPPORTED_EXTENSIONS }
            .orEmpty()
        val previous = existing?.entries?.associateBy { it.name }.orEmpty()
        val entries = ArrayList<LyricEntry>(files.size)
        for (f in files) {
            val mtime = f.lastModified()
            val size = f.length()
            val kept = previous[f.name]
            entries.add(
                if (kept != null && kept.mtime == mtime && kept.size == size) kept
                else fromFile(f, mtime, size)
            )
        }

        // 重建期间可能又发生了写入：此时不缓存，下次查询重新扫描。
        val finalStamp = Stamp(
            runCatching { dir.lastModified() }.getOrDefault(startStamp.mtime),
            epoch
        )
        val result = Snapshot(finalStamp, entries)
        if (epoch == startEpoch) {
            cached = result
            cachedDir = path
        }
        return result
    }

    private fun fromFile(f: File, mtime: Long, size: Long): LyricEntry = fromStem(
        name = f.name,
        stem = f.nameWithoutExtension,
        file = f,
        mtime = mtime,
        size = size,
    )

    /** 单文件名校验用：不进入索引，仅生成一次性的可打分条目。 */
    internal fun fromName(name: String): LyricEntry = fromStem(
        name = name,
        stem = name.substringBeforeLast('.'),
        file = File(name),
    )

    private fun fromStem(
        name: String,
        stem: String,
        file: File,
        mtime: Long = 0L,
        size: Long = 0L,
    ): LyricEntry = LyricEntry(
        file = file,
        name = name,
        lowerStem = stem.trim().lowercase(),
        normStem = LyricText.normalize(stem),
        plain = LyricText.plainInfo(stem),
        mtime = mtime,
        size = size,
    )
}
