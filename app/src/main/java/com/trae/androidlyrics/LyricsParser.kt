package com.trae.androidlyrics

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 歌词解析器：支持 LRC（标准 / 增强型逐字 / WRC 方括号逐字 / WRC 尖括号二元逐字）、
 * SPL（Salt Player Lyrics）、SRT、VTT、TTML、KRC（酷狗）、QRC（QQ音乐）、
 * YRC（网易云）、TRC（天天动听）以及无时间轴的 TXT。
 *
 * 所有时间轴统一换算为毫秒；所有逐字时间戳统一填入 [LyricLine.words]，
 * 因此任意格式都能直接驱动逐字动画。是否启用逐字动画由渲染层的
 * 「逐字动画」开关（[Prefs.wordByWord]）决定——解析阶段始终保留逐字信息，
 * 这样开关切换时无需重新解析歌词，也不会丢失逐字轴。
 *
 * 各格式的语法约定（均为解密/解码后的明文）：
 *  - LRC        [mm:ss.xx]正文；增强型 <mm:ss.xx>字<mm:ss.xx>字（时间戳在后文字之前）
 *  - SPL        LRC 增强版（2026-11-02 版）：分 1~3 位、秒 1~2 位、毫秒 1~6 位；
 *               行尾时间戳 / 独立纯时间戳行 = 显式行结尾；同时间戳或紧邻的
 *               无时间戳行 = 翻译；行内 [mm:ss.xx]字 或 <mm:ss.xx>字 为逐字起点，
 *               首字可延迟开始；独占一行的 [awlrc:...] 兼容性字段按标准忽略
 *  - WRC        ① 行首 [mm:ss.xxx]，正文内每字前再用 [mm:ss.xxx] 标记该字起点
 *               ② 行首 [mm:ss.xxx]，正文内每字前用 <累计码值,起始码值>（酷我 / AI 生成变体）
 *  - TRC        [mm:ss.xx]<250>字<300>字（尖括号内为该字的持续毫秒，需累加）
 *  - KRC        [行起点ms,行时长ms]<相对起点ms,该字时长ms,0>字...（起点相对行起点）
 *  - QRC        [行起点ms,行时长ms]字(绝对起点ms,该字时长ms)字(...)（时间戳在文字之后）
 *  - YRC        [行起点ms,行时长ms](绝对起点ms,该字时长ms,0)字...（时间戳在文字之前）
 *
 * 毫秒制这三种格式的行时长会写入 [LyricLine.endTime]，逐字标签里的「该字时长」
 * 用来在字轴末尾补一个空文本哨兵字，让最后一字按真实时长扫完，而不是套用
 * [WordMaskProgress] 的固定兜底时长。第 3 个参数（KRC/YRC 的备用位）仍按规范忽略。
 *  - VTT        WEBVTT + HH:MM:SS.mmm --> ...；正文中 <HH:MM:SS.mmm> 为逐字起点
 *  - TTML       <p begin=... end=...><span begin=...>字</span>...</p>
 *  - SRT        HH:MM:SS,mmm --> HH:MM:SS,mmm
 *
 * 解析前统一换行符为 \n，每行记录 [LyricLine.sourceOffset]，供编辑模式长按定位；
 * 该偏移是「统一换行符后」的坐标系，用于原文时需要做一次 CRLF 映射。
 */
object LyricsParser {

    /** 识别出的歌词格式，供日志/排查使用；解析结果统一为 List<LyricLine>。 */
    enum class Format { LRC, SPL, WRC, TRC, SRT, VTT, TTML, KRC, QRC, YRC, TXT }

    data class ParseResult(
        val lines: List<LyricLine>,
        val format: Format,
        /** 歌词文件里的毫秒偏移信息（主要来自 [offset:ms] 元数据）。 */
        val offsetMs: Long = 0L,
    )

    /** 编辑模式下可点击跳转的时间戳区间（[start],[end) 为全文字符偏移）。 */
    data class TimestampSpan(val start: Int, val end: Int, val timeMs: Long)

    /**
     * 进程级解析选项：是否保留不带时间戳的歌词行（过渡台词、说明等）。
     * 由 [App] 在启动时从 [Prefs.showUntimedLines] 同步，设置页改动时即时更新；
     * 这样所有 parse 调用方（主界面、桌面歌词服务、通知监听服务）无需逐个传参。
     */
    @Volatile var showUntimedLines: Boolean = true

    /**
     * 最近一次解析出的歌词文件毫秒偏移（[offset:ms] 元数据等）。
     * **只作解析信息记录与诊断展示**：[PlaybackOffset] 不再把它当作延迟量，
     * 「毫秒信息不可信」开关只影响计时链路。
     * 所有 parse 入口都汇聚到 [parseWithInfo]，因此这里就是「当前歌词文件」的值。
     */
    @Volatile var lastOffsetMs: Long = 0L
        private set

    /** 应用支持的歌词文件扩展名（小写，不含点），供自动匹配 / 导入 / 复制统一使用。 */
    val SUPPORTED_EXTENSIONS: Set<String> = linkedSetOf(
        "lrc", "spl", "txt", "srt", "krc", "qrc", "yrc", "trc", "ttml", "vtt", "wrc"
    )

    /** 按文件名返回导出 / 分享时使用的 MIME 类型。 */
    fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "lrc" -> "text/lrc"
        "srt" -> "application/x-subrip"
        "txt", "spl", "krc", "qrc", "yrc", "trc", "wrc" -> "text/plain"
        "ttml" -> "application/ttml+xml"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

    // ---------------------------------------------------------------- 正则

    /**
     * LRC 行首 / WRC 行内逐字时间戳：[mm:ss.xx]。
     * SPL 允许毫秒部分 1~6 位（不足 3 位按末尾补 0 解释，超过 3 位按毫秒截断），
     * 这里统一放宽到 6 位，parseTime 会取前 3 位作为毫秒。
     */
    private val LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?]")

    /** 增强型 LRC / SPL 逐字时间戳：<mm:ss.xx>。 */
    private val ANGLE_TIME = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?>")

    /** TRC 逐字标签：<250>，数字为该字的持续毫秒数。 */
    private val TRC_DUR = Pattern.compile("<(\\d{1,6})>")

    /**
     * WRC 二元逐字标签：<累计码值,起始码值>字，例如 <687,-687>蝴<1772,518>蝶。
     * 第一个数是该字的「结束刻度」，逐字递增；第二个可为负。不同来源的刻度单位
     * 并不统一（有的是相对毫秒，有的被放大数倍），解析时按本行时间窗口归一化。
     */
    private val WRC_PAIR = Pattern.compile("<(-?\\d{1,8}),(-?\\d{1,8})>")

    /** 元数据标签：[ti:] [ar:] [offset:] [total:] [hash:] [language:] 等。 */
    private val META_TAG = Pattern.compile("\\[[A-Za-z][A-Za-z0-9_-]*:.*]")
    /** LRC 毫秒偏移元数据：[offset:±数值] / [offset:±数值ms]。 */
    private val OFFSET_META = Pattern.compile(
        "\\[\\s*offset\\s*:\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*(ms)?\\s*]",
        Pattern.CASE_INSENSITIVE
    )

    /** SRT 时间轴：HH:MM:SS,mmm --> HH:MM:SS,mmm。 */
    private val SRT_TIME = Pattern.compile(
        "(\\d{1,3}):(\\d{2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(\\d{1,3}):(\\d{2}):(\\d{2})[.,](\\d{1,3})"
    )

    /** 通用字幕时钟：支持 MM:SS.mmm 与 HH:MM:SS.mmm（VTT 时间轴 / 内联逐字）。 */
    private const val CLOCK = "(?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3}"
    private val VTT_TIMELINE = Pattern.compile("($CLOCK)\\s*-->\\s*($CLOCK)")
    private val VTT_SHORT_TIMELINE = Pattern.compile("(?m)^\\s*\\d{1,2}:\\d{2}[.,]\\d{1,3}\\s*-->")
    private val VTT_INLINE_CLOCK = Pattern.compile("^" + CLOCK + "$")
    private val VTT_INLINE_TAG = Pattern.compile("<(?:\\d{1,2}:)?\\d{1,2}:\\d{2}[.,]\\d{1,3}>")
    private val ANY_TAG = Pattern.compile("<([^>]*)>")

    /** 毫秒制行首：[起点ms,时长ms]，KRC / QRC / YRC / Lyricify Lines 共用。 */
    private val MS_LINE = Pattern.compile("^\\s*\\[(\\d{1,8}),(\\d{1,8})]([\\s\\S]*)$")
    private val MS_LINE_HEAD = Pattern.compile("(?m)^\\s*\\[\\d{1,8},\\d{1,8}]")
    private val MS_LINE_LOOSE = Pattern.compile("^\\s*(\\[(\\d{1,8}),(\\d{1,8})])")

    /** QRC 的 XML 包裹：<Lyric_1 LyricType="1" LyricContent="..."/> */
    private val QRC_XML = Pattern.compile(
        "<Lyric_1\\b[^>]*?LyricContent\\s*=\\s*\"([\\s\\S]*?)\"\\s*/>",
        Pattern.CASE_INSENSITIVE
    )

    /** YRC 前置三元逐字：(起点ms,时长ms,0)字 —— 绝对时间。 */
    private val PRE_YRC = Pattern.compile("\\((\\d{1,8}),(\\d{1,8}),\\d+\\)")
    /** KRC 前置三元逐字：<相对起点ms,时长ms,0>字 —— 相对行起点。 */
    private val PRE_KRC = Pattern.compile("<(\\d{1,8}),(\\d{1,8}),\\d+>")
    /** QRC 后置二元逐字：字(起点ms,时长ms) —— 通常绝对，也可能是相对。 */
    private val POST_QRC = Pattern.compile("\\((\\d{1,8}),(\\d{1,8})\\)")
    /** 纯时间戳的间奏行，例如 QRC 的 [123,456](123,456)。 */
    private val WORD_TS_ONLY = Pattern.compile("^\\s*\\(\\d{1,8},\\d{1,8}\\)\\s*$")

    /** TRC 行特征：行首 LRC 时间戳 + 正文中的 <纯数字>。 */
    private val TRC_HEAD = Pattern.compile(
        "(?m)^\\s*\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?][^\\n]*<\\d{1,6}>"
    )

    /** TTML 相关。 */
    private val TTML_ROOT = Pattern.compile("<(?:[A-Za-z_][\\w.-]*:)?tt\\b", Pattern.CASE_INSENSITIVE)
    private val TTML_P = Pattern.compile(
        "<(?:\\w+:)?p\\b([^>]*)>([\\s\\S]*?)</(?:\\w+:)?p\\s*>", Pattern.CASE_INSENSITIVE
    )
    private val TTML_SPAN_OPEN = Pattern.compile(
        "<(?:\\w+:)?span\\b([^>]*)>", Pattern.CASE_INSENSITIVE
    )
    /** 译文 / 音译 span 整块：主行文字要排除它，另作为双语行显示。 */
    private val TTML_SUB_SPAN = Pattern.compile(
        "<(?:\\w+:)?span\\b[^>]*role\\s*=\\s*[\"'][^\"']*x-(?:translation|roman)[^\"']*[\"'][^>]*>" +
            "[\\s\\S]*?</(?:\\w+:)?span\\s*>",
        Pattern.CASE_INSENSITIVE
    )
    private val ATTR_TIME = Pattern.compile(
        "(?:begin|end|dur)\\s*=\\s*([\"'])($CLOCK|[+-]?\\d+(?:\\.\\d+)?(?:ms|s|m|h))\\1",
        Pattern.CASE_INSENSITIVE
    )
    private val OFFSET_UNIT = Pattern.compile(
        "^([+-]?\\d+(?:\\.\\d+)?)(ms|s|m|h|f|t)$", Pattern.CASE_INSENSITIVE
    )
    private val XML_ENTITY = Pattern.compile("&(?:#([xX]?[0-9A-Fa-f]+)|([A-Za-z]+));")
    private val XML_TAG = Pattern.compile("<[^>]*>")

    /**
     * SPL 兼容性字段：独占一行的 [awlrc:子字段,子字段...]。
     * 按 2026-11-02 版标准，识别为 LX Music 的 Base64 容器后整行忽略，
     * 既不能当歌词/翻译，也不能拿其中的时间信息替换可见歌词。
     */
    private val AWLRC_FIELD = Pattern.compile(
        "^\\s*\\[\\s*awlrc\\s*:([^\\]]*)]\\s*$", Pattern.CASE_INSENSITIVE
    )
    private val AWLRC_SUBFIELD = Pattern.compile("^\\s*([^:,\\s]+)\\s*:\\s*(\\S*)\\s*$")
    private val BASE64_VALUE = Pattern.compile("[A-Za-z0-9+/]+={0,2}")

    // ---------------------------------------------------------------- 入口

    /**
     * 解析歌词文本，根据内容自动判断格式。
     *
     * @param text 原始歌词文本
     * @return 按时间升序排列的歌词行列表
     */
    fun parse(text: String): List<LyricLine> = parseWithInfo(text).lines

    /** 与 [parse] 相同，额外返回识别出的格式（供日志与排查使用）。 */
    fun parseWithInfo(text: String): ParseResult {
        val normalized = normalize(text)
        val format = detect(normalized)
        // 歌词文件自带的毫秒偏移信息（[offset:ms]）：旧版解析时直接跳过元数据显示，
        // 这里单独抽出来只作记录 / 诊断展示，不参与播放进度或延迟计算。
        val offsetMs = extractOffsetMs(normalized)
        lastOffsetMs = offsetMs
        // 只对「带时间轴的歌词格式」处理无时间戳行；TXT 本身没有时间轴，
        // 全部行都按虚拟时间轴展示，不受该开关影响。VTT / TTML / SRT 的非时间轴行
        // 多为序号、NOTE、样式等结构内容，不作为歌词行保留。
        val keepUntimed = showUntimedLines
        val lines = when (format) {
            Format.TTML -> parseTtml(normalized)
            Format.VTT -> parseVtt(normalized)
            Format.SRT -> parseSrt(normalized)
            Format.QRC -> parseQrc(normalized, keepUntimed)
            Format.KRC, Format.YRC -> parseMsLines(normalized, 0, keepUntimed)
            Format.TRC, Format.WRC, Format.LRC -> parseLrc(normalized, keepUntimed)
            Format.SPL -> parseSpl(normalized, keepUntimed)
            Format.TXT -> parseTxt(normalized)
        }
        // sortedBy 为稳定排序：同一时间戳的原文行 / 译文行保持文件顺序（双语歌词）
        return ParseResult(lines.sortedBy { it.time }, format, offsetMs)
    }

    /**
     * 读取元数据里的毫秒偏移。有些歌词文件会在头部写 [offset:500]（毫秒），
     * 也可能写成 [offset:500ms]；多个标签累加，异常大值钳到 ±30 秒避免把进度算飞。
     */
    private fun extractOffsetMs(text: String): Long {
        val m = OFFSET_META.matcher(text)
        var total = 0.0
        while (m.find()) {
            total += m.group(1)?.toDoubleOrNull() ?: continue
        }
        return total.toLong().coerceIn(-30_000L, 30_000L)
    }

    /** 与 [parse] 内部一致的换行符归一化，供调用方把偏移映射回原文。 */
    fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n")

    // ---------------------------------------------------------------- 格式判定

    private fun detect(text: String): Format {
        val head = text.lineSequence().take(60).joinToString("\n")
        val trimmed = text.trimStart()
        val xmlHead = trimmed.take(4000)

        if (TTML_ROOT.matcher(xmlHead).find() && xmlHead.contains("begin=", ignoreCase = true)) {
            return Format.TTML
        }
        if (trimmed.startsWith("WEBVTT") || VTT_SHORT_TIMELINE.matcher(head).find() ||
            (head.contains("-->") && VTT_INLINE_TAG.matcher(head).find())
        ) {
            return Format.VTT
        }
        if (SRT_TIME.matcher(head).find()) return Format.SRT
        if (QRC_XML.matcher(text).find()) return Format.QRC
        if (MS_LINE_HEAD.matcher(head).find()) {
            return when {
                PRE_YRC.matcher(head).find() -> Format.YRC
                PRE_KRC.matcher(head).find() -> Format.KRC
                // 头部 60 行可能全是 ID 标签 / 间奏行，扩大到全文再判一次，
                // 否则纯 KRC 会被标成 QRC（虽然三种格式共用 parseMsLines，结果相同）
                PRE_YRC.matcher(text).find() -> Format.YRC
                PRE_KRC.matcher(text).find() -> Format.KRC
                else -> Format.QRC
            }
        }
        if (TRC_HEAD.matcher(head).find()) return Format.TRC
        if (LRC_TIME.matcher(head).find()) {
            // WRC 二元标签 <a,b> 的特征最明确，优先于其它 LRC 系格式
            if (looksLikeWrcPair(head)) return Format.WRC
            if (looksLikeSpl(head)) return Format.SPL
            return if (looksLikeWrc(head)) Format.WRC else Format.LRC
        }
        return Format.TXT
    }

    /** WRC 特征：剥离行首连续时间戳后，正文中仍存在逐字标签。 */
    private fun looksLikeWrc(head: String): Boolean {
        for (raw in head.split("\n")) {
            val (times, bodyStart) = splitLeadingTimes(raw)
            if (times.isEmpty()) continue
            val body = raw.substring(bodyStart)
            if (LRC_TIME.matcher(body).find() || WRC_PAIR.matcher(body).find()) return true
        }
        return false
    }

    /** WRC 二元标签 <累计码值,起始码值>：与 SPL 的 <mm:ss.xx> 不兼容，优先识别。 */
    private fun looksLikeWrcPair(head: String): Boolean {
        for (raw in head.split("\n")) {
            val (times, bodyStart) = splitLeadingTimes(raw)
            if (times.isEmpty()) continue
            if (WRC_PAIR.matcher(raw.substring(bodyStart)).find()) return true
        }
        return false
    }

    /**
     * SPL 特征判定。SPL 基于并兼容（增强型）LRC，纯 LRC 语法的文件继续走 LRC 路径；
     * 只有出现 SPL 特有的标记才切到专用解析器：
     *  - 独占一行的 [awlrc:...] 兼容性字段；
     *  - 独立的纯时间戳行 / 行尾时间戳（显式行结尾）；
     *  - 紧跟在主歌词行之后、省略时间戳的翻译行。
     */
    private fun looksLikeSpl(head: String): Boolean {
        var strong = false
        var sawInlineAngleTag = false
        var sawAdjacentUntimed = false
        var sawLyricLine = false
        for (raw in head.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (isAwlrcField(line)) return true
            val (times, bodyStart) = splitLeadingTimes(raw)
            if (times.isEmpty()) {
                // 省略时间戳的翻译行。单独的弱特征：普通 LRC 里的
                // "不带时间戳的过渡台词" 语法完全相同，必须与行内逐字标签
                // 同时出现才判为 SPL，避免改变既有 LRC 文件的展示语义。
                if (sawLyricLine && !META_TAG.matcher(line).matches() &&
                    looksLikeUntimedText(line)
                ) {
                    sawAdjacentUntimed = true
                }
                continue
            }
            sawLyricLine = true
            val body = raw.substring(bodyStart).trimEnd()
            if (body.isEmpty()) return true   // 独立纯时间戳行
            if (ANGLE_TIME.matcher(body).find()) sawInlineAngleTag = true
            val last = splTimeTags(body).lastOrNull()
            if (last != null && last.end >= body.length) strong = true   // 行尾时间戳
        }
        return strong || (sawAdjacentUntimed && sawInlineAngleTag)
    }

    /** 判断是否为按标准需要忽略的 LX Music `awlrc` 兼容性字段（整行、子字段、Base64 值）。 */
    private fun isAwlrcField(line: String): Boolean {
        val m = AWLRC_FIELD.matcher(line)
        if (!m.matches()) return false
        val inner = m.group(1) ?: return false
        var hasKnown = false
        for (part in inner.split(",")) {
            val sf = AWLRC_SUBFIELD.matcher(part)
            if (!sf.matches()) return false
            val name = sf.group(1)?.lowercase() ?: return false
            val value = sf.group(2) ?: return false
            if (value.isEmpty() || !BASE64_VALUE.matcher(value).matches()) return false
            if (name == "lrc" || name == "awlrc" || name == "tlrc" || name == "rlrc") {
                hasKnown = true
            }
        }
        return hasKnown
    }

    // ---------------------------------------------------------------- SPL（Salt Player Lyrics）

    private data class SplTag(val start: Int, val end: Int, val time: Long)

    private data class SplRow(
        val sourceOffset: Int,
        val times: List<Long>,
        val body: String,
        /** 整行应忽略（兼容性字段、元数据等），且不打断翻译的相邻关系。 */
        val ignored: Boolean,
        /** >= 0 表示这是一行纯时间戳的「显式行结尾」。 */
        val endMarkerMs: Long,
        /** 无时间戳但像歌词的文本行（省略时间戳的翻译）。 */
        val untimedText: String?,
    )

    private data class SplLine(
        val display: String,
        val words: List<LyricWord>?,
        val endTime: Long?,
    )

    /** 收集正文中所有 SPL 时间戳（方括号 / 尖括号两种写法），按出现位置排序。 */
    private fun splTimeTags(body: String): List<SplTag> {
        val tags = ArrayList<SplTag>()
        val bm = LRC_TIME.matcher(body)
        while (bm.find()) tags.add(SplTag(bm.start(), bm.end(), parseTime(bm)))
        val am = ANGLE_TIME.matcher(body)
        while (am.find()) tags.add(SplTag(am.start(), am.end(), parseTime(am)))
        tags.sortBy { it.start }
        return tags
    }

    /** 去掉 SPL 时间戳与其它尖括号标签，得到可见歌词文本。 */
    private fun cleanSplText(s: String): String =
        cleanTagText(ANGLE_TIME.matcher(LRC_TIME.matcher(s).replaceAll("")).replaceAll(""))

    /** 预处理 SPL 原文：区分歌词行、纯时间戳结束标记、兼容性字段与翻译行。 */
    private fun parseSplRows(text: String, showUntimed: Boolean): List<SplRow> {
        val rows = ArrayList<SplRow>()
        var offset = 0
        for (raw in text.split("\n")) {
            val lineStart = offset
            offset += raw.length + 1
            val line = raw.trimEnd()
            if (line.isBlank()) {
                rows.add(SplRow(lineStart, emptyList(), "", false, -1L, null))
                continue
            }
            if (isAwlrcField(line) || META_TAG.matcher(line.trim()).matches() ||
                line.startsWith("{") || line.startsWith("<?xml") ||
                line.startsWith("<QrcInfos")
            ) {
                rows.add(SplRow(lineStart, emptyList(), "", true, -1L, null))
                continue
            }
            val (times, bodyStart) = splitLeadingTimes(raw)
            val body = raw.substring(bodyStart).trimEnd()
            if (times.isNotEmpty() && body.isEmpty()) {
                // 时间戳后不接文本 = 纯粹的结束标记：不产生歌词行，也不参与翻译识别
                rows.add(SplRow(lineStart, times, "", false, times.last(), null))
                continue
            }
            if (times.isEmpty()) {
                val plain = decodeXmlEntities(stripTags(line.trim())).trim()
                val untimed = if (showUntimed && looksLikeUntimedText(plain)) plain else null
                rows.add(SplRow(lineStart, emptyList(), "", false, -1L, untimed))
                continue
            }
            rows.add(SplRow(lineStart, times, body, false, -1L, null))
        }
        return rows
    }

    /** 把独立的纯时间戳行挂到前面最近的一条歌词行，作为它的显式结束时间。 */
    private fun attachSplExplicitEnds(rows: List<SplRow>): LongArray {
        val ends = LongArray(rows.size) { -1L }
        for (i in rows.indices) {
            val marker = rows[i].endMarkerMs
            if (marker < 0L) continue
            for (j in i - 1 downTo 0) {
                val prev = rows[j]
                if (prev.ignored || prev.times.isEmpty() || prev.endMarkerMs >= 0L) continue
                if (ends[j] < 0L) ends[j] = marker
                break
            }
        }
        return ends
    }

    /** 每一行之后最近的「真正歌词行」起点（跳过结束标记与兼容性字段），用于隐式行结尾。 */
    private fun followingSplLyricTimes(rows: List<SplRow>): LongArray {
        val next = LongArray(rows.size) { -1L }
        var upcoming = -1L
        for (i in rows.indices.reversed()) {
            next[i] = upcoming
            val r = rows[i]
            if (r.ignored || r.times.isEmpty() || r.endMarkerMs >= 0L) continue
            upcoming = r.times.minOrNull() ?: continue
        }
        return next
    }

    /**
     * 解析 SPL 歌词行正文：
     * - 行尾时间戳（[mm:ss.xx] 或 <mm:ss.xx>）是显式行结尾，不作为字起点；
     * - 其余行内时间戳是紧随其后文字的起点，允许「行已到达、首字未开始」；
     * - 不在 [行起点, 行终点] 区间内或小于前一个字起点的时间戳按标准忽略，
     *   但其文字仍并入前一个字，保证显示文本不丢失、蒙版不回退；
     * - 末尾追加空文本哨兵，让最后一个字平滑扫到行尾。
     */
    private fun buildSplLine(
        body: String,
        lineTime: Long,
        implicitEnd: Long,
        explicitEnd: Long?,
    ): SplLine {
        val tags = splTimeTags(body)
        var end = explicitEnd
        val wordTags: List<SplTag>
        val last = tags.lastOrNull()
        if (last != null && last.end >= body.length) {
            if (last.time > lineTime) end = last.time
            wordTags = tags.dropLast(1)
        } else {
            wordTags = tags
        }
        if (end == null || end <= lineTime) end = implicitEnd.takeIf { it > lineTime }
        val lineEnd = end?.takeIf { it > lineTime }

        if (wordTags.isEmpty()) {
            return SplLine(cleanSplText(body).trim(), null, lineEnd)
        }
        val words = ArrayList<LyricWord>()
        val head = cleanSplText(body.substring(0, wordTags[0].start))
        if (head.isNotBlank()) words.add(LyricWord(head, lineTime))
        var lastAccepted = lineTime
        for ((k, tag) in wordTags.withIndex()) {
            val nextStart = if (k + 1 < wordTags.size) wordTags[k + 1].start else body.length
            val piece = cleanSplText(body.substring(tag.end, nextStart))
            if (piece.isEmpty()) continue
            val valid = tag.time >= lineTime && tag.time >= lastAccepted &&
                (lineEnd == null || tag.time <= lineEnd)
            if (valid) {
                words.add(LyricWord(piece, tag.time))
                lastAccepted = tag.time
            } else {
                // 时间戳被忽略：文字并入上一个字（保持同一节拍）
                val prev = words.lastOrNull()
                if (prev == null) words.add(LyricWord(piece, lineTime))
                else words[words.lastIndex] = prev.copy(text = prev.text + piece)
            }
        }
        trimWordEdges(words)
        if (words.isEmpty()) return SplLine(cleanSplText(body).trim(), null, lineEnd)
        val sentinel = if (lineEnd != null && lineEnd > words.last().startTime) {
            lineEnd
        } else {
            words.last().startTime + 400L
        }
        words.add(LyricWord("", sentinel))
        return SplLine(words.joinToString("") { it.text }, words, lineEnd)
    }

    /**
     * SPL 解析主流程（标准 2026-11-02 版）：
     * 显式/隐式行结尾、多时间戳重复行、同时间戳翻译、紧邻的无时间戳翻译
     * （多行翻译同样支持）、行内逐字轴与首字延迟开始。
     */
    private fun parseSpl(text: String, showUntimed: Boolean): List<LyricLine> {
        val rows = parseSplRows(text, showUntimed)
        val explicitEnds = attachSplExplicitEnds(rows)
        val nextTimes = followingSplLyricTimes(rows)
        val result = ArrayList<LyricLine>()
        val pending = ArrayList<Pair<String, Int>>()
        var lastTimedTime: Long? = null
        var lastMainTime: Long? = null
        // 省略时间戳的翻译必须与主歌词紧挨；兼容性字段与空行不打断相邻关系
        var translationAdjacent = false

        for ((i, row) in rows.withIndex()) {
            if (row.ignored || row.endMarkerMs >= 0L) continue
            if (row.times.isEmpty()) {
                val untimed = row.untimedText
                if (untimed == null) continue
                if (lastMainTime != null && translationAdjacent) {
                    result.add(
                        LyricLine(
                            lastMainTime, untimed, null, row.sourceOffset,
                            hasTimestamp = false,
                        )
                    )
                } else {
                    pending.add(untimed to row.sourceOffset)
                }
                continue
            }
            val firstTime = row.times.minOrNull() ?: row.times.first()
            flushUntimedLines(result, pending, lastTimedTime, firstTime)
            val lineStart = row.times.first()
            val built = buildSplLine(
                body = row.body,
                lineTime = lineStart,
                implicitEnd = nextTimes[i],
                explicitEnd = explicitEnds[i].takeIf { it >= 0L },
            )
            // 多时间戳简写的重复行：逐字时间戳是绝对时间，只对第一行有效；
            // 其余行按标准忽略字轴（显示文本保持不变）
            for ((n, t) in row.times.withIndex()) {
                result.add(
                    LyricLine(
                        t, built.display,
                        if (n == 0) built.words else null,
                        row.sourceOffset,
                        endTime = if (n == 0) built.endTime else null,
                    )
                )
                lastTimedTime = maxOf(lastTimedTime ?: t, t)
            }
            lastMainTime = lineStart
            translationAdjacent = true
        }
        flushUntimedLines(result, pending, lastTimedTime, null)
        return result.sortedBy { it.time }
    }

    // ---------------------------------------------------------------- LRC / WRC / TRC

    /** 剥离行首连续的时间戳（一行可有多个），返回时间列表与正文起点。 */
    private fun splitLeadingTimes(line: String): Pair<List<Long>, Int> {
        val times = ArrayList<Long>()
        var cursor = 0
        val m = LRC_TIME.matcher(line)
        while (cursor < line.length) {
            var ws = cursor
            while (ws < line.length && (line[ws] == ' ' || line[ws] == '\t')) ws++
            if (ws >= line.length) break
            m.region(ws, line.length)
            if (!m.lookingAt()) break
            times.add(parseTime(m))
            cursor = m.end()
        }
        return times to cursor
    }

    /**
     * 预扫描：返回每一行「之后最近的带时间轴歌词行」的起点毫秒（-1 表示后面没有了）。
     * WRC 二元标签的码值只是相对刻度，需要本行的时间窗口才能换算成可用的绝对时间。
     */
    private fun followingLrcTimes(rows: List<String>): LongArray {
        val next = LongArray(rows.size) { -1L }
        var upcoming = -1L
        for (i in rows.indices.reversed()) {
            next[i] = upcoming
            val line = rows[i].trimEnd()
            if (line.isBlank() || META_TAG.matcher(line).matches()) continue
            val t = splitLeadingTimes(line).first.firstOrNull() ?: continue
            upcoming = t
        }
        return next
    }

    private fun parseLrc(text: String, showUntimed: Boolean): List<LyricLine> {
        val rows = text.split("\n")
        val nextTimes = followingLrcTimes(rows)
        val result = ArrayList<LyricLine>()
        // 连续出现的无时间戳行先攒起来，等遇到下一条带时间轴的行再统一推算落点
        val pending = ArrayList<Pair<String, Int>>()
        var lastTimedTime: Long? = null
        var offset = 0
        for ((rowIndex, raw) in rows.withIndex()) {
            val lineStart = offset
            offset += raw.length + 1
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            if (META_TAG.matcher(line).matches()) continue
            // YRC 的 JSON 元数据、QRC 的 XML 外壳等不属于歌词行
            if (line.startsWith("{") || line.startsWith("<?xml") || line.startsWith("<QrcInfos")) continue

            val (times, bodyStart) = splitLeadingTimes(line)
            if (times.isEmpty()) {
                if (showUntimed) {
                    val trimmed = line.trim()
                    // 带缩进的元数据行（如 "  [ar:xxx]"）不属于歌词
                    if (META_TAG.matcher(trimmed).matches()) continue
                    val plain = decodeXmlEntities(stripTags(trimmed)).trim()
                    if (looksLikeUntimedText(plain)) pending.add(plain to lineStart)
                }
                continue
            }
            val firstTime = times.minOrNull() ?: times.first()
            flushUntimedLines(result, pending, lastTimedTime, firstTime)
            val body = line.substring(bodyStart)
            val (display, words) = buildInlineWords(body, times.first(), nextTimes[rowIndex])
            // 一行多个时间戳会展开成多行，共享同一原文偏移。
            // 逐字时间戳是绝对时间，只对第一个时间戳对应的那一遍有效；
            // 其余重复行的字轴必然乱序（SPL 标准明确说明应被忽略），
            // 保留会让蒙版在错误的时间点回退。
            for ((n, t) in times.withIndex()) {
                result.add(LyricLine(t, display, if (n == 0) words else null, lineStart))
                lastTimedTime = maxOf(lastTimedTime ?: t, t)
            }
        }
        flushUntimedLines(result, pending, lastTimedTime, null)
        return result.sortedBy { it.time }
    }

    /** 无时间戳行末尾顺延的固定间隔（毫秒）。 */
    private const val UNTIMED_TAIL_STEP_MS = 2000L

    /**
     * 把连续的无时间戳行插入结果列表：
     * - 位于第一条带时间戳行之前：紧贴 0ms 显示（前奏期间可见），用递增 1ms 保证顺序；
     * - 位于两条带时间戳行之间：按间隔均匀分布，让过渡台词在本句唱完后依次显示；
     * - 位于文件末尾：按固定间隔顺延，保证播放到结尾也能显示。
     */
    private fun flushUntimedLines(
        result: MutableList<LyricLine>,
        pending: MutableList<Pair<String, Int>>,
        prevTime: Long?,
        nextTime: Long?,
    ) {
        if (pending.isEmpty()) return
        val from = prevTime ?: 0L
        val n = pending.size
        for (i in 0 until n) {
            val step = (i + 1).toLong()
            val t = when {
                prevTime == null -> i.toLong()
                nextTime != null && nextTime > from -> from + (nextTime - from) * step / (n + 1).toLong()
                else -> from + UNTIMED_TAIL_STEP_MS * step
            }
            val (body, src) = pending[i]
            result.add(LyricLine(t, body, null, src, hasTimestamp = false))
        }
        pending.clear()
    }

    /** 过滤纯数字 / 纯 ASCII 标点（字幕序号、残留时间标签等），只保留像歌词正文的内容。 */
    private fun looksLikeUntimedText(s: String): Boolean =
        s.isNotBlank() && s.any { it.isLetter() || it.code > 0x7F }

    private const val KIND_ABS = 0   // <mm:ss.xx> / [mm:ss.xx]：其后文字的绝对起点
    private const val KIND_DUR = 1   // <250>：其后文字的持续时长（TRC，需累加）

    /** 末行没有下一行时间轴时，WRC 逐字轴使用的兜底时长（毫秒）。 */
    private const val WRC_DEFAULT_SPAN_MS = 4000L
    /** 长间奏不把 WRC 逐字动画拖到几乎不动，与强行兼容推算保持一致。 */
    private const val WRC_MAX_SPAN_MS = 12000L

    private data class InlineTag(val start: Int, val end: Int, val value: Long, val kind: Int)

    /**
     * 把 WRC 二元标签的码值序列换算成绝对毫秒。
     *
     * 这类文件的刻度在不同来源间并不统一（有的是相对毫秒，有的被放大数倍），
     * 因此以「本行起点 → 下一行起点」为时间窗口归一化：
     * - 码值跨度不超过窗口时保持原刻度；超出窗口则整体等比压缩，动画不会拖到下一行；
     * - 强制单调递增，避免个别异常码值让蒙版进度回退。
     */
    private fun mapWrcTimes(codes: List<Long>, lineTime: Long, lineEnd: Long): LongArray {
        val n = codes.size
        val mono = LongArray(n)
        var prev = 0L
        for (i in 0 until n) {
            val v = maxOf(codes[i].coerceAtLeast(0L), prev)
            mono[i] = v
            prev = v + 1
        }
        val last = mono.getOrNull(n - 1)?.coerceAtLeast(1L) ?: 1L
        val span = if (lineEnd > lineTime) {
            (lineEnd - lineTime).coerceIn(300L, WRC_MAX_SPAN_MS)
        } else WRC_DEFAULT_SPAN_MS
        val scale = minOf(1.0, span.toDouble() / last.toDouble())
        return LongArray(n) { i -> lineTime + (mono[i].toDouble() * scale).toLong() }
    }

    /** 去掉逐字文本里残留的尖括号标签，避免正文出现 <114,-114> 这类内容。 */
    private fun cleanTagText(s: String): String = XML_TAG.matcher(s).replaceAll("")

    /**
     * 解析 WRC 二元标签 <a,b>字：a 是该字的「结束刻度」，因此第 i 个字的起点取
     * a[i-1]，首字严格对齐行首；末尾追加空文本哨兵字，让最后一个字平滑扫到行尾
     * （与 [WordTimingEstimator] 的推算轴结构一致）。
     */
    private fun buildWrcPairWords(
        body: String,
        lineTime: Long,
        lineEnd: Long,
    ): Pair<String, List<LyricWord>?>? {
        val codes = ArrayList<Long>()
        val spans = ArrayList<IntArray>()
        val m = WRC_PAIR.matcher(body)
        while (m.find()) {
            codes.add(m.group(1)?.toLongOrNull() ?: 0L)
            spans.add(intArrayOf(m.start(), m.end()))
        }
        if (codes.isEmpty()) return null
        val times = mapWrcTimes(codes, lineTime, lineEnd)
        val words = ArrayList<LyricWord>(codes.size + 2)
        val head = body.substring(0, spans[0][0])
        val firstEnd = if (spans.size > 1) spans[1][0] else body.length
        val firstPiece = cleanTagText(head + body.substring(spans[0][1], firstEnd))
        if (firstPiece.isNotEmpty()) words.add(LyricWord(firstPiece, lineTime))
        for (i in 1 until spans.size) {
            val end = if (i + 1 < spans.size) spans[i + 1][0] else body.length
            val piece = cleanTagText(body.substring(spans[i][1], end))
            if (piece.isNotEmpty()) words.add(LyricWord(piece, times[i - 1]))
        }
        trimWordEdges(words)
        if (words.isEmpty()) return null
        words.add(LyricWord("", times[times.lastIndex]))
        return words.joinToString("") { it.text } to words
    }

    /**
     * 从 LRC 行正文中提取行内逐字时间戳，统一支持：
     *  - 增强型 LRC `<mm:ss.xx>字`（绝对起点）
     *  - WRC `[mm:ss.xxx]字`（绝对起点）
     *  - TRC `<250>字`（该字持续毫秒，从行起点累加）
     *  - WRC `<累计码值,起始码值>字`（另走 [buildWrcPairWords] 归一化分支）
     *
     * 保证 words 拼接后的文本与返回的展示文本完全一致，避免开关逐字动画时文字跳动。
     */
    private fun buildInlineWords(
        body: String,
        lineTime: Long,
        lineEnd: Long,
    ): Pair<String, List<LyricWord>?> {
        // WRC 二元标签的码值不是时间，不能与绝对时间标签混算；命中时单独走归一化分支
        if (WRC_PAIR.matcher(body).find() &&
            !ANGLE_TIME.matcher(body).find() && !LRC_TIME.matcher(body).find() &&
            !TRC_DUR.matcher(body).find()
        ) {
            buildWrcPairWords(body, lineTime, lineEnd)?.let { return it }
        }
        val tags = ArrayList<InlineTag>()
        fun collect(p: Pattern, kind: Int, isDuration: Boolean) {
            val m = p.matcher(body)
            while (m.find()) {
                val v = if (isDuration) m.group(1)!!.toLongOrNull() ?: 0L else parseTime(m)
                tags.add(InlineTag(m.start(), m.end(), v, kind))
            }
        }
        collect(ANGLE_TIME, KIND_ABS, false)
        collect(LRC_TIME, KIND_ABS, false)
        collect(TRC_DUR, KIND_DUR, true)
        if (tags.isEmpty()) return cleanTagText(body).trim() to null

        tags.sortBy { it.start }
        val words = ArrayList<LyricWord>()
        val head = cleanTagText(body.substring(0, tags[0].start))
        if (head.isNotEmpty()) words.add(LyricWord(head, lineTime))
        var clock = lineTime
        for (i in tags.indices) {
            val tag = tags[i]
            val next = if (i + 1 < tags.size) tags[i + 1].start else body.length
            val piece = cleanTagText(body.substring(tag.end, next))
            val start = if (tag.kind == KIND_DUR) {
                val s = clock
                clock += tag.value
                s
            } else tag.value
            if (piece.isNotEmpty()) words.add(LyricWord(piece, start))
        }
        trimWordEdges(words)
        if (words.isEmpty()) return cleanTagText(body).trim() to null
        return words.joinToString("") { it.text } to words
    }

    /** 去掉逐字文本首尾的空白，保证与普通模式的展示文本一致。 */
    private fun trimWordEdges(words: MutableList<LyricWord>) {
        if (words.isEmpty()) return
        val first = words[0]
        val head = first.text.trimStart()
        if (head != first.text) {
            if (head.isEmpty()) words.removeAt(0) else words[0] = first.copy(text = head)
        }
        if (words.isEmpty()) return
        val lastIdx = words.lastIndex
        val last = words[lastIdx]
        val tail = last.text.trimEnd()
        if (tail != last.text) {
            if (tail.isEmpty()) words.removeAt(lastIdx) else words[lastIdx] = last.copy(text = tail)
        }
    }

    // ---------------------------------------------------------------- KRC / QRC / YRC

    private fun parseQrc(text: String, showUntimed: Boolean): List<LyricLine> {
        val m = QRC_XML.matcher(text)
        if (m.find()) {
            // XML 包裹：先做实体解码（换行常写成 &#10;），再按毫秒制明文解析。
            // 注意：实体解码会改变字符长度，此时 sourceOffset 只能定位到歌词区域附近。
            val content = decodeXmlEntities(m.group(1).orEmpty())
            return parseMsLines(content, m.start(1), showUntimed)
        }
        return parseMsLines(text, 0, showUntimed)
    }

    private fun parseMsLines(text: String, offsetBase: Int, showUntimed: Boolean): List<LyricLine> {
        val result = ArrayList<LyricLine>()
        val pending = ArrayList<Pair<String, Int>>()
        var lastTimedTime: Long? = null
        var offset = offsetBase
        for (raw in text.split("\n")) {
            val lineStart = offset
            offset += raw.length + 1
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (META_TAG.matcher(line).matches()) continue
            // YRC 头部的 JSON 元数据行、QRC 外壳标签行
            if (line.startsWith("{") || line.startsWith("<")) continue
            val m = MS_LINE.matcher(line)
            if (!m.matches()) {
                if (showUntimed) {
                    val plain = decodeXmlEntities(stripTags(line)).trim()
                    if (looksLikeUntimedText(plain)) pending.add(plain to lineStart)
                }
                continue
            }
            val start = m.group(1)!!.toLongOrNull() ?: continue
            // 行标签第二个数是本行持续时长（KRC / QRC / YRC 通用），
            // 换算成绝对行尾保留下来，供逐字轴与「强行兼容」推算使用。
            val lineEnd = m.group(2)?.toLongOrNull()?.takeIf { it > 0 }?.let { start + it }
            flushUntimedLines(result, pending, lastTimedTime, start)
            lastTimedTime = maxOf(lastTimedTime ?: start, start)
            val content = m.group(3).orEmpty()
            // 空行 / 纯时间戳行是间奏，保留为带时间戳的空行
            if (content.isBlank() || WORD_TS_ONLY.matcher(content).matches()) {
                result.add(LyricLine(start, "", null, lineStart, endTime = lineEnd))
                continue
            }
            val (display, words) = buildMsWords(content, start, lineEnd)
            result.add(LyricLine(start, display, words, lineStart, endTime = lineEnd))
        }
        flushUntimedLines(result, pending, lastTimedTime, null)
        return result.sortedBy { it.time }
    }

    private data class MsTag(
        val start: Int,
        val end: Int,
        val time: Long,
        val relative: Boolean,
        /** 逐字标签里的「该字持续毫秒」：KRC / QRC / YRC 都带，旧实现未使用。 */
        val duration: Long = 0L,
    )

    /**
     * 解析毫秒制行正文里的逐字时间戳（自动识别 YRC / KRC / QRC 三种写法）。
     * [lineEnd] 来自行标签 `[起点,时长]`，与逐字时长一起补出真实行尾。
     */
    private fun buildMsWords(
        content: String,
        lineTime: Long,
        lineEnd: Long?,
    ): Pair<String, List<LyricWord>?> {
        // 1) 前置逐字标签（时间戳在文字之前），三种写法混排在同一个行正文里：
        //    - `(a,b,0)字`：YRC 的绝对起点；
        //    - `<a,b,0>字`：KRC 的「相对行起点 + 该字时长」；
        //    - `[mm:ss.xx]字` / `<mm:ss.xx>字`：酷狗部分 KRC 把「整行只写一个
        //      `<0,行时长,0>`、逐字起点改用 LRC 时钟」的混排变体。旧实现只认前两种，
        //      第三种会被当成普通文字留在歌词里（界面上能看到 `[00:03.00]` 这类时间戳），
        //      逐字轴也退化成整行一个字。
        val pre = ArrayList<MsTag>()
        val clockTimes = ArrayList<Long>()
        fun collectPre(p: Pattern, relative: Boolean, clock: Boolean) {
            val m = p.matcher(content)
            while (m.find()) {
                if (clock) {
                    val t = parseTime(m)
                    pre.add(MsTag(m.start(), m.end(), t, false, 0L))
                    clockTimes.add(t)
                } else {
                    pre.add(
                        MsTag(
                            m.start(), m.end(),
                            m.group(1)!!.toLongOrNull() ?: 0L,
                            relative,
                            m.group(2)?.toLongOrNull() ?: 0L,
                        )
                    )
                }
            }
        }
        collectPre(PRE_YRC, false, false)
        collectPre(PRE_KRC, true, false)
        collectPre(LRC_TIME, false, true)
        collectPre(ANGLE_TIME, false, true)
        if (pre.isNotEmpty()) {
            // 行内时钟标签一般是绝对时间；少数来源会写成相对行起点的偏移。
            // 用「首个时钟标签早于行起点」判定偏移写法，整行统一按偏移累加，
            // 否则字轴会落到上一句的时间区间里。
            val clockAsOffset = clockTimes.isNotEmpty() && clockTimes[0] < lineTime
            pre.sortBy { it.start }
            val words = ArrayList<LyricWord>()
            val head = cleanTagText(content.substring(0, pre[0].start)).trimStart()
            if (head.isNotEmpty()) words.add(LyricWord(head, lineTime))
            for (i in pre.indices) {
                val tag = pre[i]
                val next = if (i + 1 < pre.size) pre[i + 1].start else content.length
                val piece = cleanTagText(content.substring(tag.end, next))
                val t = if (tag.relative || clockAsOffset) lineTime + tag.time else tag.time
                if (piece.isNotEmpty()) words.add(LyricWord(piece, t))
            }
            // 混排数据里个别字的时间可能早于前一个字（不规范但真实存在）：
            // 钳到前一个字之后，保证字轴单调，蒙版不会往回跳。
            for (i in 1 until words.size) {
                val prev = words[i - 1].startTime
                if (words[i].startTime < prev) {
                    words[i] = words[i].copy(startTime = prev)
                }
            }
            trimWordEdges(words)
            if (words.isEmpty()) return stripTags(content).trim() to null
            // 逐字标签自带每字时长：用它补出最后一个字的结束时刻。
            appendMsTail(words, pre.last(), lineEnd)
            return words.joinToString("") { it.text } to words
        }

        // 2) 后置二元：字(a,b)（QRC）。a 通常是绝对毫秒，明显小于行起点时视为相对。
        val post = ArrayList<MsTag>()
        val pm = POST_QRC.matcher(content)
        while (pm.find()) {
            post.add(
                MsTag(
                    pm.start(), pm.end(),
                    pm.group(1)!!.toLongOrNull() ?: 0L,
                    false,
                    pm.group(2)?.toLongOrNull() ?: 0L,
                )
            )
        }
        if (post.isNotEmpty()) {
            val words = ArrayList<LyricWord>()
            var cursor = 0
            var lastStart = lineTime
            for (tag in post) {
                val piece = content.substring(cursor, tag.start)
                val t = if (tag.time < lineTime) lineTime + tag.time else tag.time
                if (piece.isNotEmpty()) words.add(LyricWord(piece, t))
                lastStart = t
                cursor = tag.end
            }
            val tail = content.substring(cursor)
            if (tail.isNotEmpty()) words.add(LyricWord(tail, lastStart))
            trimWordEdges(words)
            if (words.isEmpty()) return stripTags(content).trim() to null
            appendMsTail(words, post.last(), lineEnd)
            return words.joinToString("") { it.text } to words
        }

        // 3) 无逐字标记：YRC 译文行、Lyricify Lines 等按整行处理
        return stripTags(content).trim() to null
    }

    /**
     * 用逐字标签自带的「该字持续时长」补出本行真实结束时刻，在字轴末尾追加
     * 一个空文本哨兵字（与 SPL / WRC 的做法一致）。这样 [WordMaskProgress]
     * 会按「末字起点 → 真实行尾」插值扫完最后一字，而不是套固定的 400ms 兜底。
     *
     * 取「末字起点 + 末字时长」与「行标签声明的行尾」中较早者，避免越过行区间；
     * 两种信息只剩一种时用能拿到的那种：混排 KRC 的行内时钟标签不带逐字时长，
     * 此时靠行标签的行尾让最后一字扫满，不要退回固定 400ms 兜底。
     */
    private fun appendMsTail(words: MutableList<LyricWord>, lastTag: MsTag, lineEnd: Long?) {
        val lastStart = words.lastOrNull()?.startTime ?: return
        val byWord = lastTag.duration.takeIf { it > 0L }?.let { lastStart + it }
        val declared = lineEnd?.takeIf { it > lastStart }
        val tail = when {
            byWord != null && declared != null -> minOf(byWord, declared)
            byWord != null -> byWord
            declared != null -> declared
            else -> return
        }
        if (tail > lastStart) words.add(LyricWord("", tail))
    }

    // ---------------------------------------------------------------- VTT

    private fun parseVtt(text: String): List<LyricLine> {
        val result = ArrayList<LyricLine>()
        val rawLines = text.split("\n")
        val offsets = IntArray(rawLines.size)
        var cursorOffset = 0
        rawLines.forEachIndexed { i, l ->
            offsets[i] = cursorOffset
            cursorOffset += l.length + 1
        }

        var i = 0
        var inNote = false
        while (i < rawLines.size) {
            val line = rawLines[i].trim()
            if (line.startsWith("NOTE")) {
                inNote = true
                i++
                continue
            }
            if (inNote) {
                if (line.isEmpty()) inNote = false
                i++
                continue
            }
            val tm = VTT_TIMELINE.matcher(line)
            if (!tm.find()) {
                i++
                continue
            }
            val start = parseClock(tm.group(1)!!) ?: 0L
            i++
            val textLines = ArrayList<Pair<Int, String>>()
            while (i < rawLines.size && rawLines[i].trim().isNotEmpty()) {
                if (VTT_TIMELINE.matcher(rawLines[i].trim()).find()) break
                textLines.add(offsets[i] to rawLines[i].trim())
                i++
            }
            if (textLines.isEmpty()) continue
            val content = textLines.joinToString(" ") { it.second }
            val (display, words) = buildVttWords(content, start)
            result.add(LyricLine(start, display, words, textLines.first().first))
        }
        return result.sortedBy { it.time }
    }

    /** VTT 正文：内联 <HH:MM:SS.mmm> 标记其后文字的起点，其余标签只剥离。 */
    private fun buildVttWords(raw: String, lineStart: Long): Pair<String, List<LyricWord>?> {
        val pieces = ArrayList<Pair<String, Long>>()
        var cursor = 0
        var current = lineStart
        var foundTime = false
        val tag = ANY_TAG.matcher(raw)
        while (tag.find()) {
            val gap = raw.substring(cursor, tag.start())
            if (gap.isNotEmpty()) pieces.add(gap to current)
            val inner = tag.group(1).orEmpty().trim()
            if (VTT_INLINE_CLOCK.matcher(inner).matches()) {
                current = parseClock(inner) ?: current
                foundTime = true
            }
            cursor = tag.end()
        }
        val tail = raw.substring(cursor)
        if (tail.isNotEmpty()) pieces.add(tail to current)
        if (!foundTime) return decodeXmlEntities(stripTags(raw)).trim() to null

        val words = ArrayList<LyricWord>()
        for ((txt, t) in pieces) {
            val decoded = decodeXmlEntities(txt)
            if (decoded.isEmpty()) continue
            if (words.isNotEmpty() && words.last().startTime == t) {
                words[words.lastIndex] = words.last().copy(text = words.last().text + decoded)
            } else {
                words.add(LyricWord(decoded, t))
            }
        }
        trimWordEdges(words)
        if (words.isEmpty()) return decodeXmlEntities(stripTags(raw)).trim() to null
        return words.joinToString("") { it.text } to words
    }

    // ---------------------------------------------------------------- TTML

    private data class TtmlSpan(
        val start: Int,
        val contentEnd: Int,
        val begin: Long?,
        val role: String,
        val text: String,
    )

    private fun parseTtml(text: String): List<LyricLine> {
        val result = ArrayList<LyricLine>()
        val m = TTML_P.matcher(text)
        while (m.find()) {
            val begin = attrValue(m.group(1).orEmpty(), "begin")?.let { parseClock(it) } ?: continue
            val inner = m.group(2).orEmpty()
            val pStart = m.start()

            // 译文 / 音译 span 单独成行：必须从主体里整块剔除，
            // 否则它们的文字会被算进主行尾部，逐字动画也会把译文一起高亮。
            val allSpans = collectTtmlSpans(inner)
            val trans = allSpans
                .filter { it.role.contains("x-translation") || it.role.contains("x-roman") }
                .joinToString(" ") { decodeXmlEntities(stripTags(it.text)).trim() }
                .trim()
            val mainInner = TTML_SUB_SPAN.matcher(inner).replaceAll("")
            val spans = collectTtmlSpans(mainInner)

            val timed = spans.filter { it.begin != null }
            if (timed.isNotEmpty()) {
                val words = ArrayList<LyricWord>()
                var cursor = 0
                var lastTime = begin
                for (sp in timed) {
                    val gap = stripTags(mainInner.substring(cursor, sp.start))
                    if (gap.isNotEmpty()) words.add(LyricWord(decodeXmlEntities(gap), lastTime))
                    val txt = decodeXmlEntities(stripTags(sp.text))
                    if (txt.isNotEmpty()) words.add(LyricWord(txt, sp.begin!!))
                    lastTime = sp.begin!!
                    cursor = sp.contentEnd
                }
                val tail = stripTags(mainInner.substring(cursor))
                if (tail.isNotEmpty()) words.add(LyricWord(decodeXmlEntities(tail), lastTime))
                val merged = ArrayList<LyricWord>()
                for (w in words) {
                    if (merged.isNotEmpty() && merged.last().startTime == w.startTime) {
                        merged[merged.lastIndex] = merged.last().copy(text = merged.last().text + w.text)
                    } else merged.add(w)
                }
                trimWordEdges(merged)
                if (merged.isNotEmpty()) {
                    result.add(LyricLine(begin, merged.joinToString("") { it.text }, merged, pStart))
                } else {
                    result.add(LyricLine(begin, decodeXmlEntities(stripTags(mainInner)).trim(), null, pStart))
                }
            } else {
                result.add(LyricLine(begin, decodeXmlEntities(stripTags(mainInner)).trim(), null, pStart))
            }

            if (trans.isNotEmpty()) result.add(LyricLine(begin, trans, null, pStart))
        }
        return result.sortedBy { it.time }
    }

    /** 收集 <span> 开始标签信息；带 begin 的是逐字，role 标记译文/音译/背景人声。 */
    private fun collectTtmlSpans(inner: String): List<TtmlSpan> {
        val spans = ArrayList<TtmlSpan>()
        val sm = TTML_SPAN_OPEN.matcher(inner)
        while (sm.find()) {
            val a = sm.group(1).orEmpty()
            val role = attrValue(a, "role").orEmpty()
            val b = attrValue(a, "begin")?.let { parseClock(it) }
            val contentEnd = inner.indexOf('<', sm.end()).let { if (it < 0) inner.length else it }
            spans.add(TtmlSpan(sm.start(), contentEnd, b, role, inner.substring(sm.end(), contentEnd)))
        }
        return spans
    }

    /** 读取 XML 属性（兼容命名空间前缀与单/双引号）。 */
    private fun attrValue(s: String, name: String): String? {
        val p = Pattern.compile(
            "(?:^|\\s)(?:[A-Za-z_][\\w.-]*:)?" + Pattern.quote(name) + "\\s*=\\s*([\"'])(.*?)\\1",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(s)
        return if (m.find()) m.group(2) else null
    }

    // ---------------------------------------------------------------- SRT

    private fun parseSrt(text: String): List<LyricLine> {
        val result = ArrayList<LyricLine>()
        // 按空行分块，每块是一条字幕；同时保留每块在原文中的起始偏移
        val separator = Regex("\\n\\s*\\n")
        val blocks = ArrayList<Pair<Int, String>>()
        var cursor = 0
        for (m in separator.findAll(text)) {
            blocks.add(cursor to text.substring(cursor, m.range.first))
            cursor = m.range.last + 1
        }
        blocks.add(cursor to text.substring(cursor))
        for ((blockStart, block) in blocks) {
            val rawLines = ArrayList<Pair<Int, String>>()
            var lineOffset = blockStart
            for (raw in block.split("\n")) {
                rawLines.add(lineOffset to raw.trim())
                lineOffset += raw.length + 1
            }
            val lines = rawLines.filter { it.second.isNotBlank() }
            if (lines.isEmpty()) continue
            val timeLineIdx = lines.indexOfFirst { SRT_TIME.matcher(it.second).find() }
            if (timeLineIdx < 0) continue
            val m = SRT_TIME.matcher(lines[timeLineIdx].second)
            if (!m.find()) continue
            val start = parseSrtTime(m)
            val textLines = lines.subList(timeLineIdx + 1, lines.size)
            val content = decodeXmlEntities(stripTags(textLines.joinToString(" ") { it.second })).trim()
            if (content.isEmpty()) continue
            val contentStart = textLines.firstOrNull()?.first ?: blockStart
            result.add(LyricLine(start, content, null, contentStart))
        }
        return result.sortedBy { it.time }
    }

    // ---------------------------------------------------------------- TXT

    private fun parseTxt(text: String): List<LyricLine> {
        val result = ArrayList<LyricLine>()
        var offset = 0
        for ((index, line) in text.split("\n").withIndex()) {
            val lineStart = offset
            offset += line.length + 1
            val content = line.trim()
            if (content.isEmpty()) continue
            // SPL 兼容性字段 [awlrc:...] 在纯文本展示时同样忽略
            if (isAwlrcField(content)) continue
            // 无时间轴：按行号给一个递增的虚拟时间，便于顺序展示
            result.add(LyricLine(index * 2000L, content, null, lineStart))
        }
        return result
    }

    // ---------------------------------------------------------------- 时间换算

    /** 解析 LRC 型时间戳的 Matcher（[mm:ss.xx] / <mm:ss.xx>）。 */
    private fun parseTime(m: Matcher): Long {
        val min = m.group(1)?.toLongOrNull() ?: 0L
        val sec = m.group(2)?.toLongOrNull() ?: 0L
        val fracRaw = m.group(3)
        val frac = when {
            fracRaw == null -> 0L
            fracRaw.length == 1 -> fracRaw.toLong() * 100
            fracRaw.length == 2 -> fracRaw.toLong() * 10
            else -> fracRaw.take(3).toLong()
        }
        return min * 60_000 + sec * 1000 + frac
    }

    private fun parseSrtTime(m: Matcher): Long {
        val h = m.group(1)?.toLongOrNull() ?: 0L
        val min = m.group(2)?.toLongOrNull() ?: 0L
        val sec = m.group(3)?.toLongOrNull() ?: 0L
        val msRaw = m.group(4).orEmpty()
        val ms = when (msRaw.length) {
            1 -> msRaw.toLong() * 100
            2 -> msRaw.toLong() * 10
            else -> msRaw.toLongOrNull() ?: 0L
        }
        return h * 3_600_000 + min * 60_000 + sec * 1000 + ms
    }

    /**
     * 解析字幕/TTML 时钟：HH:MM:SS.mmm、MM:SS.mmm、SS.mmm、HH:MM:SS:FF（帧），
     * 以及 TTML 的偏移量写法 1.5s / 500ms / 2m / 1h。
     */
    private fun parseClock(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val u = OFFSET_UNIT.matcher(s)
        if (u.matches()) {
            val v = u.group(1)!!.toDoubleOrNull() ?: return null
            return when (u.group(2)!!.lowercase()) {
                "h" -> (v * 3_600_000).toLong()
                "m" -> (v * 60_000).toLong()
                "s" -> (v * 1000).toLong()
                "ms" -> v.toLong()
                "f", "t" -> (v * 1000.0 / 30.0).toLong()
                else -> null
            }
        }
        val parts = s.split(":").toMutableList()
        if (parts.size !in 1..4) return null
        // TTML 帧时间 HH:MM:SS:FF，按 30fps 近似
        var frameMs = 0L
        if (parts.size == 4) {
            frameMs = (parts.removeAt(3).toLongOrNull() ?: 0L) * 1000L / 30L
        }
        val last = parts.last()
        val dot = last.indexOfAny(charArrayOf('.', ','))
        val secStr = if (dot >= 0) last.substring(0, dot) else last
        val fracStr = if (dot >= 0) last.substring(dot + 1).takeWhile { it.isDigit() } else ""
        val sec = secStr.toLongOrNull() ?: return null
        val fracMs = when (fracStr.length) {
            0 -> 0L
            1 -> fracStr.toLong() * 100
            2 -> fracStr.toLong() * 10
            else -> fracStr.take(3).toLongOrNull() ?: 0L
        }
        val h = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val m = when (parts.size) {
            3 -> parts[1].toLongOrNull() ?: return null
            2 -> parts[0].toLongOrNull() ?: return null
            else -> 0L
        }
        return h * 3_600_000L + m * 60_000L + sec * 1000L + fracMs + frameMs
    }

    // ---------------------------------------------------------------- XML 辅助

    private fun stripTags(s: String): String {
        val withBreaks = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        val noTags = XML_TAG.matcher(withBreaks).replaceAll("")
        return noTags.replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ")
    }

    private fun decodeXmlEntities(s: String): String {
        val m = XML_ENTITY.matcher(s)
        val sb = StringBuffer()
        while (m.find()) {
            val replacement: String = when {
                m.group(1) != null -> {
                    val num = m.group(1)!!
                    val code = if (num.startsWith("x") || num.startsWith("X")) {
                        num.substring(1).toIntOrNull(16)
                    } else num.toIntOrNull()
                    code?.let {
                        runCatching { String(Character.toChars(it)) }.getOrNull()
                    } ?: m.group()
                }
                else -> when (m.group(2)) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> m.group()
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement))
        }
        m.appendTail(sb)
        return sb.toString()
    }

    // ---------------------------------------------------------------- 编辑模式时间戳

    /**
     * 找出全文所有可点击跳转的时间戳，供编辑模式使用。
     * 除 LRC / 增强型 LRC 外，还覆盖 KRC/QRC/YRC/TRC 的逐字时间戳、WRC 二元标签、
     * VTT/TTML 的时钟时间与 SRT 时间轴；KRC/TRC 的相对时间与 WRC 的相对码值
     * 都会按所在行起点 / 行时间窗口换算。
     */
    fun findTimestamps(text: String): List<TimestampSpan> {
        val candidates = ArrayList<TimestampSpan>()
        val rows = text.split("\n")
        val nextTimes = followingLrcTimes(rows)
        var offset = 0
        for ((rowIndex, raw) in rows.withIndex()) {
            val lineStart = offset
            offset += raw.length + 1
            var lineTime = 0L
            val lineEnd = nextTimes[rowIndex]

            // 1) 行基准时间：毫秒制行首 [a,b]（KRC / QRC / YRC）
            val ms = MS_LINE_LOOSE.matcher(raw)
            val hasMs = ms.find()
            if (hasMs) {
                lineTime = ms.group(2)?.toLongOrNull() ?: 0L
                candidates.add(TimestampSpan(lineStart + ms.start(1), lineStart + ms.end(1), lineTime))
            }

            // 2) LRC 行首 / WRC 行内方括号时间戳
            val lm = LRC_TIME.matcher(raw)
            var firstLrc = true
            var hasLrc = false
            while (lm.find()) {
                hasLrc = true
                val t = parseTime(lm)
                if (firstLrc && !hasMs) lineTime = t
                firstLrc = false
                candidates.add(TimestampSpan(lineStart + lm.start(), lineStart + lm.end(), t))
            }

            // 3) 增强型 LRC 逐字时间戳 <mm:ss.xx>
            val am = ANGLE_TIME.matcher(raw)
            while (am.find()) {
                candidates.add(TimestampSpan(lineStart + am.start(), lineStart + am.end(), parseTime(am)))
            }

            // 4) VTT / SRT 时间轴
            val tm = VTT_TIMELINE.matcher(raw)
            if (tm.find()) {
                val t = parseClock(tm.group(1)!!) ?: lineTime
                if (!hasMs && !hasLrc) lineTime = t
                candidates.add(TimestampSpan(lineStart + tm.start(1), lineStart + tm.end(1), t))
                candidates.add(TimestampSpan(lineStart + tm.start(2), lineStart + tm.end(2),
                    parseClock(tm.group(2)!!) ?: t))
            }

            // 5) TTML 的 begin/end/dur 属性时间
            val attr = ATTR_TIME.matcher(raw)
            while (attr.find()) {
                val t = parseClock(attr.group(2)!!) ?: continue
                candidates.add(TimestampSpan(lineStart + attr.start(2), lineStart + attr.end(2), t))
            }

            // 6) VTT 内联逐字时间戳 <HH:MM:SS.mmm>（三段时钟；两段已由规则 3 覆盖）
            val anyTag = ANY_TAG.matcher(raw)
            while (anyTag.find()) {
                val inner = anyTag.group(1).orEmpty().trim()
                if (inner.count { it == ':' } >= 2 && VTT_INLINE_CLOCK.matcher(inner).matches()) {
                    val t = parseClock(inner) ?: continue
                    candidates.add(TimestampSpan(lineStart + anyTag.start(), lineStart + anyTag.end(), t))
                }
            }

            // 7) YRC 前置三元 (a,b,0)：绝对时间
            val py = PRE_YRC.matcher(raw)
            while (py.find()) {
                val t = py.group(1)?.toLongOrNull() ?: continue
                candidates.add(TimestampSpan(lineStart + py.start(), lineStart + py.end(), t))
            }

            // 8) KRC 前置三元 <a,b,0>：a 相对行起点，b 是该字时长（跳转只用起点）
            val pk = PRE_KRC.matcher(raw)
            while (pk.find()) {
                val t = lineTime + (pk.group(1)?.toLongOrNull() ?: 0L)
                candidates.add(TimestampSpan(lineStart + pk.start(), lineStart + pk.end(), t))
            }

            // 9) QRC 后置二元 (a,b)：绝对（明显小于行起点时按相对）
            val pq = POST_QRC.matcher(raw)
            while (pq.find()) {
                val a = pq.group(1)?.toLongOrNull() ?: continue
                val t = if (a < lineTime) lineTime + a else a
                candidates.add(TimestampSpan(lineStart + pq.start(), lineStart + pq.end(), t))
            }

            // 10) TRC 持续时长 <250>：按出现顺序累加得到每个字的起点
            var clock = lineTime
            val td = TRC_DUR.matcher(raw)
            while (td.find()) {
                candidates.add(TimestampSpan(lineStart + td.start(), lineStart + td.end(), clock))
                clock += td.group(1)?.toLongOrNull() ?: 0L
            }

            // 11) WRC 二元标签 <a,b>：码值按本行时间窗口归一化，点击跳到该字起点
            if (WRC_PAIR.matcher(raw).find()) {
                val codes = ArrayList<Long>()
                val cm = WRC_PAIR.matcher(raw)
                while (cm.find()) codes.add(cm.group(1)?.toLongOrNull() ?: 0L)
                val mapped = mapWrcTimes(codes, lineTime, lineEnd)
                var i = 0
                val wm = WRC_PAIR.matcher(raw)
                while (wm.find()) {
                    val t = if (i == 0) lineTime else mapped[i - 1]
                    candidates.add(TimestampSpan(lineStart + wm.start(), lineStart + wm.end(), t))
                    i++
                }
            }
        }
        // 按位置排序并去掉重叠区间（同一段文本可能被多条规则命中）
        candidates.sortBy { it.start }
        val result = ArrayList<TimestampSpan>()
        var lastEnd = -1
        for (c in candidates) {
            if (c.start >= lastEnd) {
                result.add(c)
                lastEnd = c.end
            }
        }
        return result
    }

    /**
     * 把毫秒格式化为 mm:ss.xx 字符串，用于编辑模式下点击时间戳跳转。
     */
    fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val frac = (ms % 1000) / 10
        return String.format("%02d:%02d.%02d", min, sec, frac)
    }

    /**
     * 把毫秒格式化为 mm:ss.SSS（保留完整三位毫秒小数）。
     *
     * 与 [formatTime] 的 mm:ss.xx（编辑器时间戳惯用格式）区分：跳转时间提示要
     * 把「请求目标」与「播放器实测落点」放在一起对比，两者精度必须一致，否则
     * 19.611 会被截成 19.61、无法和 19.016 直接对照。
     */
    fun formatTimePrecise(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSec = safe / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d.%03d", min, sec, safe % 1000)
    }
}
