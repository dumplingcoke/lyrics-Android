package com.trae.androidlyrics

/**
 * 歌词数据模型。
 *
 * - 普通行（LRC / SRT / VTT / TTML / 各厂商格式）：[time] 为该行起点毫秒数，
 *   [text] 为歌词正文，[words] 为空。
 * - 逐字行（增强型 LRC、WRC、TRC、KRC、QRC、YRC、VTT 内联、TTML span）：
 *   [words] 保存每个字（词）的绝对起始毫秒数，用于逐字高亮动画。
 *   是否启用动画由设置里的「逐字动画」开关决定。
 * - TXT 纯文本：[time] 为该行的序号位置（秒），用于在无时间轴时仍可顺序展示。
 */
data class LyricWord(
    val text: String,
    val startTime: Long,
)

data class LyricLine(
    /** 该行起点（毫秒）。无时间轴的 TXT 行用“行号 × 固定间隔”填充。 */
    val time: Long,
    /** 该行完整文本（含时间戳标签已剥离）。 */
    val text: String,
    /** 逐字时间戳（绝对毫秒），带逐字轴的格式才有；普通行取 null。 */
    val words: List<LyricWord>? = null,
    /**
     * 该行在原始歌词文本中的字符偏移（解析器统一换行符后的坐标系）。
     * 编辑模式长按某行/空白处时据此精确定位光标；-1 表示未知，需要回退搜索。
     * 空行、重复歌词、SRT 多行字幕等场景不能靠搜索正文来定位。
     */
    val sourceOffset: Int = -1,
    /**
     * 该行原文是否自带时间戳。false 表示「显示不带时间戳的行」开关开启时
     * 由解析器保留下来的过渡台词/说明行，其 [time] 是按前后时间轴推算出来的，
     * 不应作为「下一行歌词的开始时间」参与逐字时间推算。
     */
    val hasTimestamp: Boolean = true,
    /**
     * 显式行结尾（毫秒）。SPL 用行尾时间戳或独立的纯时间戳行声明本行结束；
     * KRC / QRC / YRC 则由行标签 `[起点,时长]` 的第二个数给出。
     * 有值时「强行兼容逐字动画」按它推算字轴，不必等到下一句歌词开始。
     */
    val endTime: Long? = null,
) {
    val isEmpty: Boolean get() = text.isBlank()
}
