package com.trae.androidlyrics

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trae.androidlyrics.databinding.ItemLyricBinding

/**
 * 歌词列表适配器。
 *
 * - 普通行：灰色小字。
 * - 当前行：高亮大字。
 * - 逐字模式：当前行交给 [MaskedLyricView] 用蒙版方式从左到右显示高亮，
 *   进度由 [updateCurrent] 直接推进，不触发 item 重建，动画流畅。
 * - 双语模式：连续相同时间戳的多行视为一组，根据 [bilingualHighlight] 决定高亮哪一行。
 */
class LyricAdapter(
    private val onLongPress: (Int) -> Unit,
    private val onLineClick: (LyricLine) -> Unit,
) : RecyclerView.Adapter<LyricAdapter.VH>() {

    private var lines: List<LyricLine> = emptyList()
    private var currentIndex = -1
    private var currentPositionMs = 0L
    private var wordByWord = true

    /** 强行兼容逐字动画：格式不带逐字时间戳时按整行时长推算，默认关闭。 */
    private var forceWordByWord = false
    private var fontSizePx: Float = 20f
    private var bilingualHighlight: Int = Prefs.BILINGUAL_BOTH

    /** 歌词文字对齐方式：左 / 中 / 右。 */
    private var lyricAlignment: Int = Prefs.ALIGN_CENTER

    /** 空行占位显示文字；null 表示不做任何处理。 */
    private var emptyLineDisplay: String? = null

    /** 主界面逐字动画的已唱颜色（原始偏好值，绘制时再叠加文字不透明度）。 */
    private var sungColor: Int = Prefs.DEFAULT_SUNG_TEXT_COLOR

    /** 主界面文字外观：由 [applyMainAppearance] 从 Prefs 同步。 */
    private var textColor: Int = 0xFFFFFFFF.toInt()
    private var textAlpha: Int = Prefs.MAX_TEXT_ALPHA
    private var outlineEnabled = false
    private var outlineColor: Int = 0xFF000000.toInt()
    private var outlineWidthDp = Prefs.DEFAULT_OUTLINE_WIDTH
    private var outlinePosition = Prefs.DEFAULT_OUTLINE_POSITION

    private var softTransition = true
    private var softStrength = Prefs.DEFAULT_WORD_SOFT_TRANSITION_STRENGTH

    /** 「应用外观 → 主页歌词」的文字排版：字重 / 行间距 / 译文字号。 */
    private var homeFontWeight = 500
    private var homeLineSpacingDp = 8
    private var homeTranslationSizeSp = 14f
    private var homeTextStyleEnabled = true

    /** 高亮行放大：是否启用 + 相对原文字号的放大值（sp，默认 6 保持原有观感）。 */
    private var highlightEnlarge = true
    private var highlightEnlargeSp = 6f

    /** 已绑定蒙版视图的行（key 为 position）：逐字进度直接推给这些视图，无需 notify。 */
    private val maskViews = HashMap<Int, MaskedLyricView>()

    fun submit(lines: List<LyricLine>) {
        this.lines = lines
        currentIndex = -1
        maskViews.clear()
        notifyDataSetChanged()
    }

    fun setFontSize(sp: Float) {
        // 与其它 setter 一致：值没变就不要重建列表。
        // applyFontSize() 在每次 onResume 都会调用，无条件 notifyDataSetChanged
        // 会打断正在进行的逐字蒙版动画（并让画面回退到重建那一刻的进度）。
        if (fontSizePx == sp) return
        this.fontSizePx = sp
        maskViews.clear()
        notifyDataSetChanged()
    }

    /** 同步「主页歌词设置 → 高亮行放大」；关闭时所有行使用基准字号。 */
    fun setHighlightEnlarge(enabled: Boolean, extraSp: Int) {
        val extra = extraSp.coerceIn(0, AppearancePrefs.MAX_HIGHLIGHT_ENLARGE_SP).toFloat()
        if (highlightEnlarge == enabled && highlightEnlargeSp == extra) return
        highlightEnlarge = enabled
        highlightEnlargeSp = extra
        maskViews.clear()
        notifyDataSetChanged()
    }

    fun setAlignment(value: Int) {
        if (lyricAlignment != value) {
            lyricAlignment = value
            maskViews.clear()
            notifyDataSetChanged()
        }
    }

    fun setBilingualHighlight(value: Int) {
        if (bilingualHighlight != value) {
            bilingualHighlight = value
            maskViews.clear()
            notifyDataSetChanged()
        }
    }

    /** 强行兼容逐字动画开关变化时必须重新绑定当前行（蒙版在 bind 时决定）。 */
    fun setForceWordByWord(value: Boolean) {
        if (forceWordByWord != value) {
            forceWordByWord = value
            maskViews.clear()
            notifyDataSetChanged()
        }
    }

    /**
     * 同步主界面歌词的整套文字外观（文字/已唱/描边颜色、不透明度、描边位置）。
     * 只在值真的变化时刷新列表，避免 onResume 每次都打断正在进行的逐字动画。
     */
    fun applyMainAppearance(
        textColor: Int,
        textAlpha: Int,
        sungColor: Int,
        outlineEnabled: Boolean,
        outlineColor: Int,
        outlineWidthDp: Int,
        outlinePosition: Int,
    ) {
        val safeAlpha = textAlpha.coerceIn(Prefs.MIN_TEXT_ALPHA, Prefs.MAX_TEXT_ALPHA)
        val safeWidth = outlineWidthDp.coerceIn(Prefs.MIN_OUTLINE_WIDTH, Prefs.MAX_OUTLINE_WIDTH)
        val safePosition = outlinePosition.coerceIn(
            Prefs.OUTLINE_POSITION_INSIDE, Prefs.OUTLINE_POSITION_OUTSIDE
        )
        val changed = this.textColor != textColor || this.textAlpha != safeAlpha ||
            this.sungColor != sungColor || this.outlineEnabled != outlineEnabled ||
            this.outlineColor != outlineColor || this.outlineWidthDp != safeWidth ||
            this.outlinePosition != safePosition
        if (!changed) return
        this.textColor = textColor
        this.textAlpha = safeAlpha
        this.sungColor = sungColor
        this.outlineEnabled = outlineEnabled
        this.outlineColor = outlineColor
        this.outlineWidthDp = safeWidth
        this.outlinePosition = safePosition
        maskViews.clear()
        notifyDataSetChanged()
    }

    /** 柔化参数变化时直接更新当前蒙版，无需重建列表或打断动画。 */
    fun setSoftTransition(enabled: Boolean, strength: Int) {
        val safeStrength = strength.coerceIn(0, 100)
        if (softTransition != enabled || softStrength != safeStrength) {
            softTransition = enabled
            softStrength = safeStrength
            maskViews.values.forEach { it.setSoftTransition(enabled, safeStrength) }
        }
    }

    /**
     * 同步「应用外观」页的主页歌词排版。
     * 只在值变化时刷新列表，避免每次 onResume 都打断正在进行的逐字蒙版动画。
     */
    fun applyHomeTextStyle(fontWeight: Int, lineSpacingDp: Int, translationSizeSp: Int) {
        val weight = (fontWeight / 100 * 100).coerceIn(300, 800)
        val spacing = lineSpacingDp.coerceIn(0, 28)
        val changed = homeFontWeight != weight || homeLineSpacingDp != spacing ||
            homeTranslationSizeSp != translationSizeSp.toFloat()
        if (!changed) return
        homeFontWeight = weight
        homeLineSpacingDp = spacing
        homeTranslationSizeSp = translationSizeSp.toFloat()
        maskViews.clear()
        notifyDataSetChanged()
    }

    /** 空行占位显示文字（♬ / ~ / 伴奏 / 自定义）；null 表示不做任何处理。 */
    fun setEmptyLineDisplay(text: String?) {
        if (emptyLineDisplay != text) {
            emptyLineDisplay = text
            maskViews.clear()
            notifyDataSetChanged()
        }
    }

    fun updateCurrent(index: Int, positionMs: Long, wordByWord: Boolean, forceWordByWord: Boolean) {
        val old = currentIndex
        // 逐字动画开关变化时必须重新绑定当前行：useMask 在 bind 时决定，
        // 只更新字段不会让已显示的蒙版消失 / 重新出现。
        val modeChanged = this.wordByWord != wordByWord || this.forceWordByWord != forceWordByWord
        this.currentIndex = index
        this.currentPositionMs = positionMs
        this.wordByWord = wordByWord
        this.forceWordByWord = forceWordByWord
        if (old != index || modeChanged) {
            // 双语模式下高亮行可能不是 currentIndex 本身（如"仅第一行"），
            // 必须刷新旧组与新组的完整范围，否则会出现高亮停在第一组不再更新。
            // index < 0（前奏、进度未到第一行、seek 回开头）时 groupRangeOf 返回
            // IntRange.EMPTY（值为 1..0），不能让 EMPTY 的 first/last 参与 min/max：
            // 那会把刷新范围算错，旧高亮行的蒙版可能残留不解绑。此时只需刷新旧组。
            val oldRange = groupRangeOf(old)
            val newRange = groupRangeOf(index)
            val start = when {
                !oldRange.isEmpty() && !newRange.isEmpty() -> minOf(oldRange.first, newRange.first)
                !oldRange.isEmpty() -> oldRange.first
                !newRange.isEmpty() -> newRange.first
                else -> -1
            }
            val end = when {
                !oldRange.isEmpty() && !newRange.isEmpty() -> maxOf(oldRange.last, newRange.last)
                !oldRange.isEmpty() -> oldRange.last
                !newRange.isEmpty() -> newRange.last
                else -> -1
            }
            if (start in lines.indices) {
                val last = end.coerceAtMost(lines.lastIndex)
                if (last >= start) notifyItemRangeChanged(start, last - start + 1)
            }
            // 切行后旧位置登记的蒙版引用全部失效，rebind 会按新位置重新登记。
            // 双语组尤其重要：旧组里的译文行 key 与新组位置不同，不清理会永久残留。
            maskViews.clear()
        } else if (wordByWord && index >= 0) {
            // 蒙版方案：进度变化直接推给已绑定的蒙版视图，
            // 视图内部仅在蒙版 x 变化时 invalidate，无 notify、无重建，动画流畅。
            //
            // 不能用 lines[index].words 是否为空来决定要不要推进度：
            // findLineIndex 返回「时间戳 <= 进度的最后一行」，双语歌词里同时间戳
            // 的原文行与译文行构成一组，currentIndex 落在组内**最后一行**（译文行）。
            // 译文行由 LyricsParser / mergedLyric 生成时 words 恒为 null，一旦据此
            // return，组内原文行已绑定的蒙版就整句收不到进度，表现为逐字动画失效；
            // 直到回前台重建列表才用新进度一次性跳到正确位置。
            // maskViews 中的视图都是 bind 时确认过有有效字轴的，直接推进即可。
            maskViews.values.forEach { it.setProgress(positionMs) }
        }
        // 没有当前行时不再持有任何蒙版引用：旧高亮行已在上面刷新时解绑，
        // 残留引用只会在后续复用中把进度推给已经隐藏的视图。
        if (index < 0) maskViews.clear()
    }

    /**
     * 取一行可用于蒙版动画的字时间戳。
     * 与桌面歌词共用 [WordTimingEstimator]，保证两个「强行兼容」开关判断完全一致。
     */
    private fun effectiveWords(index: Int): List<LyricWord>? =
        WordTimingEstimator.effectiveWords(lines, index, forceWordByWord)

    /** 字重：API 28+ 支持任意权重，低版本退化为粗体/常规。 */
    private fun homeTypeface(): android.graphics.Typeface =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, homeFontWeight, false
            )
        } else {
            android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                if (homeFontWeight >= 600) android.graphics.Typeface.BOLD
                else android.graphics.Typeface.NORMAL
            )
        }

    /** 同时间戳组的第二行起视为译文行，按译文字号显示。 */
    private fun isSecondaryLine(position: Int): Boolean =
        position > 0 && lines.getOrNull(position)?.time == lines.getOrNull(position - 1)?.time

    /** 返回与 [index] 行同时间戳的整组范围；index 越界时返回空范围。 */
    private fun groupRangeOf(index: Int): IntRange {
        if (index < 0 || index >= lines.size) return IntRange.EMPTY
        val t = lines[index].time
        var s = index
        while (s > 0 && lines[s - 1].time == t) s--
        var e = index
        while (e < lines.size - 1 && lines[e + 1].time == t) e++
        return s..e
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLyricBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val line = lines.getOrNull(position) ?: return
        val isCurrent = isHighlighted(position)
        // 只有当前高亮行需要蒙版；强行兼容时 effectiveWords 会给出推算出的逐字轴
        val maskWords = if (isCurrent && wordByWord) effectiveWords(position) else null
        // 传入本次绑定的稳定 position：不要用 getAdapterPosition() 做 maskViews 的
        // key，它在 adapter 数据变更瞬间可能返回 NO_POSITION(-1)，会留下删不掉的
        // 幽灵引用，让进度推给已经被隐藏的蒙版。
        holder.bind(position, line, isCurrent, currentPositionMs, maskWords)
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        // 移除对该 holder 蒙版视图的引用，避免复用后推进度到错误的视图
        maskViews.entries.removeAll { it.value === holder.mask }
        holder.detach()
    }

    override fun getItemCount(): Int = lines.size

    /**
     * 判断指定行是否应高亮显示。
     *
     * 当前行索引 [currentIndex] 指向时间戳 <= 播放进度的最后一行。
     * 若存在多行共享同一时间戳（双语歌词），则按 [bilingualHighlight] 决定：
     *  - BILINGUAL_FIRST：仅高亮该组的第一行
     *  - BILINGUAL_SECOND：仅高亮该组的最后一行（即 currentIndex）
     *  - BILINGUAL_BOTH：高亮该组所有行
     */
    private fun isHighlighted(position: Int): Boolean {
        if (position < 0 || currentIndex < 0) return false
        val currentTime = lines.getOrNull(currentIndex)?.time ?: return false
        val lineTime = lines.getOrNull(position)?.time ?: return false
        // 不在当前时间组内
        if (lineTime != currentTime) return false
        // 找到当前时间组的起始索引
        var groupStart = currentIndex
        while (groupStart > 0 && lines.getOrNull(groupStart - 1)?.time == currentTime) {
            groupStart--
        }
        val groupEnd = currentIndex
        val groupSize = groupEnd - groupStart + 1
        return when (bilingualHighlight) {
            Prefs.BILINGUAL_FIRST -> position == groupStart
            Prefs.BILINGUAL_SECOND -> position == groupEnd
            else -> true // BILINGUAL_BOTH：组内所有行都高亮；单行时自然也高亮
        }
    }

    inner class VH(private val binding: ItemLyricBinding) : RecyclerView.ViewHolder(binding.root) {
        val mask: MaskedLyricView = binding.lyricMask
        private var boundLine: LyricLine? = null

        init {
            binding.root.setOnClickListener {
                boundLine?.let { onLineClick(it) }
            }
            binding.root.setOnLongClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) onLongPress(pos)
                true
            }
        }

        fun bind(position: Int, line: LyricLine, isCurrent: Boolean, positionMs: Long, maskWords: List<LyricWord>?) {
            boundLine = line
            val tv = binding.lyricLine
            val gravity = when (lyricAlignment) {
                Prefs.ALIGN_LEFT -> Gravity.START
                Prefs.ALIGN_RIGHT -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            // 文字不透明度统一作用于当前行、非当前行、逐字未唱/已唱色与描边色。
            // 默认（白色 + 100%）与旧版硬编码颜色一致：当前行 #FFFFFFFF、
            // 其它行 #80FFFFFF、逐字未唱 #66FFFFFF。
            val density = tv.resources.displayMetrics.density
            val base = LyricTextStyle.withAlpha(textColor, textAlpha)
            val currentFill = base
            val otherFill = LyricTextStyle.scaleAlpha(base, 0x80)
            val dimColor = LyricTextStyle.scaleAlpha(base, 0x66)
            val sung = LyricTextStyle.withAlpha(sungColor, textAlpha)
            val outline = LyricTextStyle.withAlpha(outlineColor, textAlpha)
            val strokePx = if (outlineEnabled) outlineWidthDp * density else 0f
            val useMask = isCurrent && !maskWords.isNullOrEmpty()
            if (useMask) {
                // 逐字行：TextView 隐藏（保留占位），蒙版视图负责显示
                tv.visibility = View.INVISIBLE
                mask.visibility = View.VISIBLE
                mask.textAlign = lyricAlignment
                mask.dimColor = dimColor
                mask.sungColor = sung
                mask.outlineColor = outline
                mask.outlineWidthPx = strokePx
                mask.outlinePosition = outlinePosition
                mask.setSoftTransition(softTransition, softStrength)
                if (homeTextStyleEnabled) {
                    mask.applyTypeface(homeTypeface())
                    val extra = (homeLineSpacingDp * density / 2).toInt()
                    mask.setPadding(mask.paddingLeft, extra, mask.paddingRight, extra)
                }
                mask.setLine(maskWords!!, fontSizePx + highlightExtraSp())
                mask.setProgress(positionMs)
                maskViews[position] = mask
            } else {
                mask.visibility = View.GONE
                mask.clearLine()
                if (maskViews[position] === mask) maskViews.remove(position)
                tv.visibility = View.VISIBLE
                tv.gravity = gravity
                // 「应用外观」：双语组里同时间戳的后续行按译文字号显示
                val secondary = homeTextStyleEnabled && isSecondaryLine(position)
                tv.textSize = when {
                    secondary -> homeTranslationSizeSp
                    isCurrent -> fontSizePx + highlightExtraSp()
                    else -> if (highlightEnlarge) fontSizePx - 2f else fontSizePx
                }
                if (homeTextStyleEnabled) {
                    tv.typeface = homeTypeface()
                    tv.setLineSpacing(homeLineSpacingDp * density, 1f)
                }
                val fill = if (isCurrent) currentFill else otherFill
                tv.setTextColor(fill)
                tv.fillColor = fill
                tv.outlineEnabled = outlineEnabled
                tv.outlineColor = outline
                tv.outlineWidthPx = strokePx
                tv.outlinePosition = outlinePosition
                // outlineColor / outlineWidthPx 是无刷新 setter，颜色改完后需主动重绘
                tv.invalidate()
                // 空行按用户设置显示占位内容（♬ / ~ / 伴奏 / 自定义），"不处理"时保持原样
                tv.text = line.text.takeIf { it.isNotBlank() } ?: emptyLineDisplay.orEmpty()
            }
        }

        /** 解绑时清理（配合 onViewRecycled）。 */
        fun detach() {
            boundLine = null
            // 清掉残留字轴：即使有引用漏在 maskViews 里，setProgress 也会因
            // axis 为空直接返回，不会把上一行的进度画到复用后的 View 上。
            mask.clearLine()
        }
    }

    /** 当前行相对原文字号的实际放大值；关闭开关时为 0。 */
    private fun highlightExtraSp(): Float = if (highlightEnlarge) highlightEnlargeSp else 0f

}
