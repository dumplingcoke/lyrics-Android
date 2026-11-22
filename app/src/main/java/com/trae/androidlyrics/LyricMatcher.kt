package com.trae.androidlyrics

import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 歌词文件自动匹配：根据当前播放歌曲的标题/作者，
 * 从应用歌词存储目录中选出相似度最高的歌词文件。
 *
 * 两套并行逻辑各算一次分，取较高分（原路径结果不会因新增逻辑而变差）：
 *  A. 原路径：保留符号的位置信息，用于「标题 + 分隔符 + 后缀」等变体判断；
 *  B. 纯文字路径：把标题与文件名里的**所有符号**去掉——标点、空白、
 *     ♪ ★ … · — 等装饰符号、全角括号、emoji 一律剥掉，只留文字
 *     （字母 / 数字 / 汉字 / 假名等），再按同一套规则重新匹配一次。
 *
 * 每条路径内部取最高分：
 *  - 完全相等 → 最高优先
 *  - 标题 + 符号 + 后缀 → 变体
 *  - 同时包含标题与作者
 *  - 与「标题 作者」整体的编辑距离相似度
 *  - 包含标题
 *  - 与标题的编辑距离相似度
 *
 * 多文件优化（不改变任何评分结果）：
 *  1. 文件名归一化结果由 [LyricIndex] 预计算并跨歌曲复用；
 *  2. 编辑距离带相似度阈值剪枝：长度比上界 + Ukkonen 带状 DP + 逐行最小值提前退出；
 *  3. DP 数组按线程复用，并交换长短串使宽度取 min(len)；
 *  4. bestMatch 流式取最大值，命中 1.0 立即终止，不再全量排序；
 *  5. 按 (目录版本, 歌曲, 阈值, 模式) 缓存匹配结果。
 *
 * 剪枝安全性：similarityAtLeast 只在真实相似度必然低于 [target] 时返回 0.0；
 * 凡真实值 ≥ target 一律精确计算。contains 类规则（0.8 / 0.98）与 DP 无关，
 * 始终照常执行。因此达标文件的分数与排序、以及 bestMatch 的选取完全一致。
 */
object LyricMatcher {

    /** 默认相似度阈值：低于该值不自动匹配，避免错误加载无关歌词。 */
    private const val MIN_SCORE = 0.55

    /** 原路径后缀变体允许的分隔符：下划线、半角/全角连字符、破折号。 */
    private val VARIANT_SEPARATORS = setOf('_', '-', '－', '—', '–')

    /**
     * 纯文字路径的分数上限：略低于 1.0，让「原文件名与标题字面完全一致」的文件
     * 始终排在「只是剥掉符号后一致」的文件前面（含自动匹配取 bestMatch 的场景）。
     */
    private const val PLAIN_PATH_MAX_SCORE = 0.99

    /** 结果缓存上限：按访问顺序淘汰，避免歌曲切换历史无限增长。 */
    private const val MAX_CACHED_RESULTS = 64

    private data class CacheKey(
        val dir: String,
        val title: String,
        val artist: String,
        val minScore: Double,
        val stamp: LyricIndex.Stamp,
        val bestOnly: Boolean,
    )

    private val resultCache = object : LinkedHashMap<CacheKey, List<File>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, List<File>>?,
        ): Boolean = size > MAX_CACHED_RESULTS
    }

    /**
     * 返回目录下所有与歌曲相似度达标的歌词文件，按相似度降序排列。
     * [minScore] 为相似度阈值（0..1），由设置中的"匹配相似度"控制。
     */
    fun matches(dir: File, title: String, artist: String, minScore: Double = MIN_SCORE): List<File> =
        query(dir, title, artist, minScore, bestOnly = false)

    /**
     * 判断给定文件名是否足以匹配歌曲信息（相似度达到自动匹配阈值）。
     * 用于决定"用户主动打开的文件"是否值得记录为歌曲-歌词绑定。
     */
    fun nameMatches(fileName: String, title: String, artist: String): Boolean {
        val q = buildQuery(title, artist) ?: return false
        val entry = LyricIndex.fromName(fileName)
        return score(entry, q, MIN_SCORE) >= MIN_SCORE
    }

    /**
     * 在目录中选出与歌曲最匹配的歌词文件；无达标结果返回 null。
     */
    fun bestMatch(dir: File, title: String, artist: String, minScore: Double = MIN_SCORE): File? =
        query(dir, title, artist, minScore, bestOnly = true).firstOrNull()

    /**
     * 一次匹配共用的查询串：标题/作者只归一化一次，目录下的候选文件逐个复用。
     */
    private class Query(title: String, artist: String) {
        val lowerTitle = title.trim().lowercase()
        val nTitle = LyricText.normalize(title)
        val nArtist = LyricText.normalize(artist)
        val nTitleArtist = if (nArtist.isEmpty()) nTitle else nTitle + nArtist
        val plainTitle = LyricText.plainInfo(title).value
        val plainArtist = LyricText.plainInfo(artist).value
        val plainTitleArtist = plainTitle + plainArtist
    }

    private fun buildQuery(title: String, artist: String): Query? {
        if (title.isBlank()) return null
        // 标题里一个文字都没有（纯符号）时无从匹配
        return Query(title, artist).takeIf { it.plainTitle.isNotEmpty() }
    }

    /** 缓存优先：目录版本变化会自动使旧 key 失配，无需显式清理。 */
    @Synchronized
    private fun query(
        dir: File,
        title: String,
        artist: String,
        minScore: Double,
        bestOnly: Boolean,
    ): List<File> {
        val q = buildQuery(title, artist) ?: return emptyList()
        val snapshot = LyricIndex.snapshot(dir)
        val key = CacheKey(dir.absolutePath, title, artist, minScore, snapshot.stamp, bestOnly)
        resultCache[key]?.let { return it }
        val result = compute(snapshot.entries, q, minScore, bestOnly)
        resultCache[key] = result
        return result
    }

    private fun compute(
        entries: List<LyricEntry>,
        q: Query,
        minScore: Double,
        bestOnly: Boolean,
    ): List<File> {
        if (bestOnly) {
            // 流式取最大值：target 随当前最优分递增，剪枝越来越激进。
            var best: LyricEntry? = null
            var bestScore = minScore
            for (e in entries) {
                val s = score(e, q, bestScore)
                if (s >= bestScore && s >= minScore) {
                    best = e
                    bestScore = s
                }
                if (bestScore >= 1.0) break
            }
            return best?.let { listOf(it.file) } ?: emptyList()
        }
        val scored = ArrayList<Pair<File, Double>>()
        for (e in entries) {
            val s = score(e, q, minScore)
            if (s >= minScore) scored.add(e.file to s)
        }
        // 与旧实现同为稳定降序排序，遍历顺序来自同一份目录快照。
        scored.sortByDescending { it.second }
        return scored.map { it.first }
    }

    /**
     * 两路各算一次，取较高分：原路径结果只升不降。
     * [target] 为「本次计算所需的最低有意义分数」，仅用于剪枝。
     */
    private fun score(e: LyricEntry, q: Query, target: Double): Double {
        val original = originalScore(e, q, target)
        if (original >= 1.0) return 1.0
        // 纯文字路径最高只有 0.99，此时取 max 不可能改变结果
        if (original >= PLAIN_PATH_MAX_SCORE || target > PLAIN_PATH_MAX_SCORE) return original
        return max(original, min(plainTextScore(e, q, target), PLAIN_PATH_MAX_SCORE))
    }

    /** 原路径：小写/归一化，符号内容仍在串里，保留原有的分隔符变体判断。 */
    private fun originalScore(e: LyricEntry, q: Query, target: Double): Double {
        // 标题可能只有全角字母数字（旧 normalize 会全部删掉），此时本路径无从比较
        if (q.nTitle.isEmpty()) return 0.0
        val lowerName = e.lowerStem
        if (lowerName.isEmpty()) return 0.0
        // 规则1：文件名与歌曲标题完全一样 → 直接匹配
        if (lowerName == q.lowerTitle) return 1.0
        // 规则2/3：文件名 = 标题 + 分隔符（_、- 等）+ 后缀 → 视为该歌曲的歌词
        if (lowerName.length > q.lowerTitle.length &&
            lowerName.startsWith(q.lowerTitle) &&
            lowerName[q.lowerTitle.length] in VARIANT_SEPARATORS
        ) return 0.9
        // 规则4/5：模糊相似度
        val n = e.normStem
        if (n.isEmpty()) return 0.0
        var s = similarityAtLeast(n, q.nTitle, target)
        if (q.nArtist.isNotEmpty()) {
            if (n.contains(q.nTitle) && n.contains(q.nArtist)) s = maxOf(s, 0.98)
            s = maxOf(s, similarityAtLeast(n, q.nTitleArtist, target))
        }
        if (n.contains(q.nTitle)) s = maxOf(s, 0.8)
        return s
    }

    /**
     * 纯文字路径：两边都只保留文字字符，再完整匹配一次。
     * 这是解决「符号差异」的第二套逻辑，与原路径并列、取高分。
     */
    private fun plainTextScore(e: LyricEntry, q: Query, target: Double): Double {
        val p = e.plain
        val name = p.value
        val title = q.plainTitle
        if (name.isEmpty() || title.isEmpty()) return 0.0
        // 规则1：符号全部剥离后完全一致 → 直接匹配
        if (name == title) return 1.0
        // 规则2/3：纯文字以标题打头，且原标题之后紧跟的是任意符号
        val followedBySymbol = title.length in 1..p.symbolAfter.size &&
            p.symbolAfter[title.length - 1]
        if (name.length > title.length && name.startsWith(title) && followedBySymbol) return 0.9
        // 规则4/5：模糊相似度
        var s = similarityAtLeast(name, title, target)
        if (q.plainArtist.isNotEmpty()) {
            if (name.contains(title) && name.contains(q.plainArtist)) s = maxOf(s, 0.98)
            s = maxOf(s, similarityAtLeast(name, q.plainTitleArtist, target))
        }
        if (name.contains(title)) s = maxOf(s, 0.8)
        return s
    }

    /**
     * 带阈值的编辑距离相似度：真实值 ≥ [target] 时返回精确值，否则返回 0.0。
     *
     * 长度上界：similarity ≤ min(lenA,lenB) / max(lenA,lenB)，
     * 该上界低于 target 时无需进入 DP。
     */
    private fun similarityAtLeast(a: String, b: String, target: Double): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = max(a.length, b.length)
        val minLen = min(a.length, b.length)
        if (minLen.toDouble() / maxLen < target) return 0.0
        if (a == b) return 1.0
        // 允许的最大编辑距离；ceil 保证不会把「刚好达标」的组合误剪掉。
        val maxDist = ceil((1.0 - target) * maxLen).toInt().coerceAtLeast(0)
        val dist = levenshteinWithin(a, b, maxDist)
        if (dist > maxDist) return 0.0
        return 1.0 - dist.toDouble() / maxLen
    }

    private class DpBuffer(var a: IntArray, var b: IntArray)

    /** DP 数组按线程复用，避免每个文件 × 每条规则都分配两个 IntArray。 */
    private val dpBuffer = ThreadLocal<DpBuffer>()

    /**
     * Ukkonen 带状 Levenshtein：只计算距对角线不超过 [k] 的单元，
     * 任一行最小值超过 k 即判定不可达并返回 k+1。
     * 交换长短串使 DP 宽度取 min(len)；编辑距离对称，不影响结果。
     */
    private fun levenshteinWithin(a: String, b: String, k: Int): Int {
        if (a == b) return 0
        val unreachable = k + 1
        if (a.isEmpty()) return if (b.length > k) unreachable else b.length
        if (b.isEmpty()) return if (a.length > k) unreachable else a.length
        val long = if (a.length >= b.length) a else b
        val short = if (a.length >= b.length) b else a
        val m = long.length
        val n = short.length
        if (m - n > k) return unreachable

        val buf = dpBuffer.get() ?: DpBuffer(IntArray(n + 1), IntArray(n + 1))
            .also { dpBuffer.set(it) }
        if (buf.a.size < n + 1) {
            buf.a = IntArray(n + 1)
            buf.b = IntArray(n + 1)
        }
        var prev = buf.a
        var cur = buf.b
        java.util.Arrays.fill(prev, unreachable)
        for (j in 0..min(n, k)) prev[j] = j

        for (i in 1..m) {
            java.util.Arrays.fill(cur, unreachable)
            cur[0] = if (i <= k) i else unreachable
            val from = max(1, i - k)
            val to = min(n, i + k)
            var rowMin = cur[0]
            for (j in from..to) {
                val cost = if (long[i - 1] == short[j - 1]) 0 else 1
                val v = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > k) return unreachable
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return if (prev[n] > k) unreachable else prev[n]
    }
}
