package com.trae.androidlyrics

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View

/**
 * 逐字歌词蒙版视图：两层绘制 + 裁剪实现"从左到右"的高亮蒙版。
 *
 * 底层以暗色绘制整行文字；上层以亮色绘制同一文字，并按布局的每一行
 * 分别裁剪到该行已唱进度的 x 坐标（按字时间戳在相邻两字之间线性插值），
 * 自动换行的多行文字只有真正唱到的部分高亮。
 *
 * 文字在布局内的排列方式由 [textAlign] 决定（与设置中的歌词对齐方式一致）。
 *
 * 相比逐字 Span 方案的优势：不重建 SpannableString、不触发 notifyItemChanged，
 * 进度推进只 invalidate 自身，消除了逐字刷新带来的卡顿感。
 *
 * 连续进度与按行裁剪由 [WordMaskProgress] 提供，桌面歌词（[OutlineTextView]）
 * 复用同一套实现，两处动画表现一致。
 */
class MaskedLyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    private var layout: StaticLayout? = null
    /** 与桌面歌词共用 [WordMaskProgress] 的连续进度算法。 */
    private var axis = WordMaskProgress.build(emptyList())
    private var builtWidth = -1

    /** 歌词文字对齐方式：Prefs.ALIGN_LEFT / ALIGN_CENTER / ALIGN_RIGHT。 */
    var textAlign: Int = Prefs.ALIGN_CENTER
        set(value) {
            if (field != value) {
                field = value
                layout = null
                builtWidth = -1
                requestLayout()
                invalidate()
            }
        }

    /** 当前蒙版推进到的字符位置（浮点，支持字内插值）。 */
    private var sungOffset = 0f
    private var lastProgressMs = Long.MIN_VALUE

    /** 已唱文字颜色，由设置界面「主界面歌词 → 已唱歌词颜色」控制。 */
    var sungColor: Int = COLOR_SUNG
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 未唱文字颜色，由主界面「文字颜色 + 文字不透明度」合成。 */
    var dimColor: Int = COLOR_DIM
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 描边颜色；描边是否启用由 [outlineWidthPx] > 0 表达。 */
    var outlineColor: Int = 0xFF000000.toInt()
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 描边宽度（像素），0 表示不描边。 */
    var outlineWidthPx: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 描边位置：内部 / 居中 / 外部。 */
    var outlinePosition: Int = Prefs.DEFAULT_OUTLINE_POSITION
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var softTransition = true
    private var softStrength = Prefs.DEFAULT_WORD_SOFT_TRANSITION_STRENGTH

    /** 与桌面歌词共用同一组柔化设置，仅影响已唱颜色蒙版的前缘。 */
    fun setSoftTransition(enabled: Boolean, strength: Int) {
        val safeStrength = strength.coerceIn(0, 100)
        if (softTransition != enabled || softStrength != safeStrength) {
            softTransition = enabled
            softStrength = safeStrength
            invalidate()
        }
    }

    init {
        paint.color = COLOR_DIM
    }

    /** 「应用外观」页的字重：逐字蒙版与普通行共用同一套字体。 */
    fun applyTypeface(tf: android.graphics.Typeface) {
        if (paint.typeface == tf) return
        paint.typeface = tf
        layout = null
        builtWidth = -1
        requestLayout()
        invalidate()
    }

    /** 绑定一行逐字歌词；sizeSp 为当前行字号（sp）。 */
    fun setLine(lineWords: List<LyricWord>, sizeSp: Float) {
        axis = WordMaskProgress.build(lineWords)
        paint.textSize = sizeSp * resources.displayMetrics.density
        layout = null
        builtWidth = -1
        // 复位为哨兵值：View 会被 RecyclerView 复用，若沿用上一行末尾的
        // sungOffset，新行首帧算出的 offset 恰好相等时就会吞掉首次 invalidate。
        sungOffset = SUNG_OFFSET_INVALID
        lastProgressMs = Long.MIN_VALUE
        requestLayout()
        invalidate()
    }

    /** 清空绑定（复用为普通行时）。 */
    fun clearLine() {
        axis = WordMaskProgress.build(emptyList())
        layout = null
        builtWidth = -1
        sungOffset = SUNG_OFFSET_INVALID
        lastProgressMs = Long.MIN_VALUE
        invalidate()
    }

    /**
     * 推进播放进度：仅当蒙版位置变化时重绘。
     *
     * [layout] 只在 measure / sizeChanged 阶段构建，而 [setLine] 会把它置空。
     * 若复用期间 measure 尚未发生（View 刚从 GONE 变 VISIBLE、父布局抑制了本次
     * requestLayout 等），只 invalidate 会因 onDraw 里 `layout ?: return` 整行不画。
     * 因此布局未就绪时补一次 requestLayout，保证至少有一次完整 traversal。
     */
    fun setProgress(positionMs: Long) {
        if (axis.isEmpty) return
        if (positionMs == lastProgressMs) return
        lastProgressMs = positionMs
        val offset = WordMaskProgress.sungOffset(axis, positionMs)
        val needLayout = layout == null
        if (offset != sungOffset || needLayout) {
            sungOffset = offset
            if (needLayout) requestLayout()
            invalidate()
        }
    }

    /** 构建文字布局（宽度/对齐变化或重新绑定时重建）。 */
    private fun ensureLayout(width: Int) {
        val avail = width - paddingLeft - paddingRight
        if (axis.isEmpty || avail <= 0) return
        if (layout != null && builtWidth == avail) return
        val text = axis.words.joinToString("") { it.text }
        val alignment = when (textAlign) {
            Prefs.ALIGN_LEFT -> Layout.Alignment.ALIGN_NORMAL
            Prefs.ALIGN_RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, avail)
            .setAlignment(alignment)
            .setLineSpacing(resources.displayMetrics.density * 4f, 1f)
            .build()
        builtWidth = avail
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        ensureLayout(w)
        val textH = layout?.height ?: 0
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (hMode == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (paddingTop + paddingBottom + textH)
                .coerceAtMost(if (hMode == MeasureSpec.AT_MOST) MeasureSpec.getSize(heightMeasureSpec) else Int.MAX_VALUE)
        }
        setMeasuredDimension(w, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureLayout(w)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        // 与桌面歌词共用同一套描边位置编排；无描边时只走填充，零额外开销。
        LyricTextStyle.drawWithOutline(
            canvas = canvas,
            width = width,
            height = height,
            paint = paint,
            outlineColor = outlineColor,
            outlineWidthPx = outlineWidthPx,
            position = outlinePosition,
            drawFill = { drawMaskFill(canvas, l) },
            drawStroke = { l.draw(canvas) },
        )
        canvas.restore()
    }

    /** 填充层：底层整行未唱色 + 上层按进度裁剪的已唱色（前缘柔化）。 */
    private fun drawMaskFill(canvas: Canvas, l: StaticLayout) {
        paint.color = dimColor
        l.draw(canvas)
        for (line in 0 until l.lineCount) {
            val lineStart = l.getLineStart(line)
            val lineEnd = l.getLineEnd(line)
            if (lineEnd <= lineStart) continue
            WordMaskProgress.drawSungLine(
                l, paint, line, sungOffset, sungColor, softTransition, softStrength
            ) { clip ->
                canvas.save()
                canvas.clipRect(clip[0], clip[1], clip[2], clip[3])
                l.draw(canvas)
                canvas.restore()
            }
        }
        paint.shader = null
    }

    companion object {
        /** 未初始化哨兵：保证 setLine / clearLine 后的第一次 setProgress 必然重绘。 */
        private const val SUNG_OFFSET_INVALID = -1f

        /** 未唱/已唱文字颜色（与原逐字 Span 方案一致）。 */
        const val COLOR_DIM: Int = 0x66FFFFFF
        const val COLOR_SUNG: Int = 0xFFFFFFFF.toInt()
    }
}
