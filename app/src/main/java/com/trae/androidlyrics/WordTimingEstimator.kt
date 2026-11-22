package com.trae.androidlyrics

/**
 * 「强行兼容逐字动画」共用的逐字时间轴判定与推算逻辑。
 *
 * 主界面蒙版（[LyricAdapter]）与桌面歌词着色（[DesktopLyricsService]）各自有
 * 一个独立开关，但两者必须走**同一套判断**：
 * - 歌词行自带逐字时间戳 → 原样使用（不受强行兼容开关影响）；
 * - 无逐字时间戳且强行兼容开启 → 按「本行起点 → 下一行带时间轴歌词起点」均分推算；
 * - 无逐字时间戳且强行兼容关闭 / 空行 → 不做逐字动画。
 */
object WordTimingEstimator {

    /** 推算逐字时间时，末行没有下一行时间轴可用的兜底时长（毫秒）。 */
    const val ESTIMATED_DEFAULT_MS = 4000L

    /** 推算逐字时间允许的最大跨度（毫秒），避免长间奏把动画拖到几乎不动。 */
    const val ESTIMATED_MAX_MS = 12000L

    /**
     * 取 [index] 行可用于逐字动画的字轴；无可用字轴时返回 null。
     *
     * @param force 对应的「强行兼容逐字动画」开关是否开启
     */
    fun effectiveWords(
        lines: List<LyricLine>,
        index: Int,
        force: Boolean,
    ): List<LyricWord>? {
        val line = lines.getOrNull(index) ?: return null
        if (!line.words.isNullOrEmpty()) return line.words
        if (!force) return null
        return estimate(lines, index)
    }

    /**
     * 强行兼容：把本行文字按「本行起点 → 下一行带时间戳歌词的起点」均分，
     * 生成逐字起点，让不带逐字时间戳的格式也能走逐字动画。
     * 推算依据只有整行时长，精度很差（设置里已用小字提醒）。
     * 末尾追加空文本哨兵字，让最后一个字在其时间片内平滑扫到行尾。
     */
    private fun estimate(lines: List<LyricLine>, index: Int): List<LyricWord>? {
        val line = lines.getOrNull(index) ?: return null
        val text = line.text
        // 空行（间奏占位）仍交给调用方按设置显示 ♬ / ~ / 伴奏，不做逐字动画
        if (text.isBlank()) return null
        var end = -1L
        for (i in index + 1 until lines.size) {
            val next = lines[i]
            // 跳过解析器推算出来的无时间戳行，只认原文自带时间轴的下一行
            if (next.hasTimestamp && next.time > line.time) {
                end = next.time
                break
            }
        }
        // SPL 的显式行结尾优先，但不能越过下一句歌词的起点
        val explicit = line.endTime?.takeIf { it > line.time }
        if (explicit != null) {
            end = if (end > line.time) minOf(explicit, end) else explicit
        }
        if (end <= line.time) end = line.time + ESTIMATED_DEFAULT_MS
        // 过长的间奏会让推算结果慢到几乎不动，钳制最大跨度
        end = minOf(end, line.time + ESTIMATED_MAX_MS)
        val chars = text.map { it.toString() }
        val n = chars.size
        val span = end - line.time
        val count = n.toLong()
        val words = ArrayList<LyricWord>(n + 1)
        for (i in 0 until n) {
            words.add(LyricWord(chars[i], line.time + span * i.toLong() / count))
        }
        words.add(LyricWord("", end))
        return words
    }
}
