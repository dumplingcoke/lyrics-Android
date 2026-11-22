package com.trae.androidlyrics

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

/**
 * 支持文字描边 + 逐字蒙版的自定义 TextView。
 *
 * 普通模式：在 onDraw 中先以 STROKE 样式画描边、再以 FILL 样式画填充，
 * 实现描边颜色与填充颜色不同的效果。
 *
 * 逐字蒙版模式（[setWordMask]）：与主界面 [MaskedLyricView] 使用同一套
 * [WordMaskProgress] 连续进度算法——底层画未唱颜色、上层按行裁剪出已唱部分，
 * 由 [Choreographer] 每帧推进，不重建 SpannableString、不触发重新布局，
 * 消除桌面歌词逐字高亮的跳字与卡顿。
 */
class OutlineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 描边颜色（ARGB int）。 */
    var outlineColor: Int = 0xFF000000.toInt()

    /** 填充颜色（ARGB int），与描边颜色完全独立，不依赖 TextView 内部 paint 状态。 */
    var fillColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    /** 描边宽度（像素），0f 表示不启用描边。 */
    var outlineWidthPx: Float = 0f

    /** 是否启用描边（outlineColor != 0 && outlineWidthPx > 0f 时生效）。 */
    var outlineEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 描边位置：内部 / 居中 / 外部，默认外部（与旧版「先描边后填充」一致）。 */
    var outlinePosition: Int = Prefs.DEFAULT_OUTLINE_POSITION
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 描边关闭时传 0 给绘制工具，走零额外开销的纯填充路径。 */
    private fun effectiveOutlineWidth(): Float =
        if (outlineEnabled) outlineWidthPx else 0f

    // ---------------------------------------------------------------- 逐字蒙版

    private var maskAxis: WordMaskProgress.Axis? = null
    private var maskWords: List<LyricWord>? = null
    private var maskExpectedText: String? = null
    private var maskSungColor: Int = 0xFFFFFFFF.toInt()
    private var maskDimColor: Int = 0x66FFFFFF
    private var maskSoftTransition = true
    private var maskSoftStrength = Prefs.DEFAULT_WORD_SOFT_TRANSITION_STRENGTH
    private var progressProvider: (() -> Long)? = null
    private var sungOffset = 0f
    private var frameScheduled = false

    /**
     * 每帧按实时播放进度推进蒙版；进度不变（暂停）时不重绘，只保留帧回调。
     * 逐帧插值让桌面歌词的扫光与主界面一样连续，而不是按整字跳变。
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            val axis = maskAxis
            val provider = progressProvider
            if (axis == null || axis.isEmpty || provider == null || !isAttachedToWindow) return
            val offset = WordMaskProgress.sungOffset(axis, provider())
            if (offset != sungOffset) {
                sungOffset = offset
                invalidate()
            }
            scheduleFrame()
        }
    }

    /**
     * 绑定当前行的逐字轴。重复绑定同一份字轴（服务每 200ms tick 会调用一次）
     * 不会重建布局，只更新颜色与进度源。
     *
     * @param expectedText 当前 TextView 应显示的文本，文本变化时蒙版自动失效，
     *                     避免旧字轴配新歌词出现错位高亮。
     * @param provider 实时播放进度（毫秒），由调用方提供已修正的进度。
     */
    fun setWordMask(
        words: List<LyricWord>,
        expectedText: String,
        sungColor: Int,
        dimColor: Int,
        softTransition: Boolean,
        softStrength: Int,
        provider: () -> Long,
    ) {
        val safeStrength = softStrength.coerceIn(0, 100)
        val appearanceChanged = maskSungColor != sungColor || maskDimColor != dimColor ||
            maskSoftTransition != softTransition || maskSoftStrength != safeStrength
        maskSungColor = sungColor
        maskDimColor = dimColor
        maskSoftTransition = softTransition
        maskSoftStrength = safeStrength
        progressProvider = provider
        if (maskWords != words || maskExpectedText != expectedText) {
            maskWords = words
            maskExpectedText = expectedText
            maskAxis = WordMaskProgress.build(words)
            sungOffset = WordMaskProgress.sungOffset(maskAxis!!, provider())
            invalidate()
        } else if (appearanceChanged) {
            invalidate()
        }
        scheduleFrame()
    }

    /** 关闭逐字蒙版：清掉状态、停止帧回调，恢复普通文字绘制。 */
    fun clearWordMask() {
        if (maskAxis == null && progressProvider == null) return
        maskAxis = null
        maskWords = null
        maskExpectedText = null
        progressProvider = null
        sungOffset = 0f
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
        invalidate()
    }

    private fun scheduleFrame() {
        if (frameScheduled || !isAttachedToWindow) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (maskAxis != null) scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    override fun onDraw(canvas: Canvas) {
        val axis = maskAxis
        if (axis != null && text?.toString() == maskExpectedText) {
            drawWordMask(canvas)
        } else {
            drawNormal(canvas)
        }
    }

    /** 普通模式：描边 + 填充（TextView 默认绘制）。 */
    private fun drawNormal(canvas: Canvas) {
        val widthPx = effectiveOutlineWidth()
        if (widthPx <= 0f) {
            super.onDraw(canvas)
            return
        }
        LyricTextStyle.drawWithOutline(
            canvas = canvas,
            width = width,
            height = height,
            paint = paint,
            outlineColor = outlineColor,
            outlineWidthPx = widthPx,
            position = outlinePosition,
            drawFill = { drawFillNormal(canvas) },
            drawStroke = { drawStrokePass(canvas) },
        )
    }

    /** 普通模式的填充层：与描边层共用同一套裁剪/平移，保证描边与文字严格对齐。 */
    private fun drawFillNormal(canvas: Canvas) {
        paint.color = fillColor
        val l = layout
        if (l == null) {
            super.onDraw(canvas)
            return
        }
        drawLayoutPass(canvas, l)
    }

    /**
     * 逐字蒙版绘制：填充层（未唱整行 + 裁剪的已唱部分）与描边层由
     * [LyricTextStyle] 按描边位置编排，两处歌词共用同一套裁剪算法。
     */
    private fun drawWordMask(canvas: Canvas) {
        val l = layout
        if (l == null) {
            drawNormal(canvas)
            return
        }
        LyricTextStyle.drawWithOutline(
            canvas = canvas,
            width = width,
            height = height,
            paint = paint,
            outlineColor = outlineColor,
            outlineWidthPx = effectiveOutlineWidth(),
            position = outlinePosition,
            drawFill = { drawMaskFill(canvas, l) },
            drawStroke = { drawStrokePass(canvas) },
        )
    }

    /** 逐字填充层：底层整行未唱色 + 上层按进度裁剪的已唱色。 */
    private fun drawMaskFill(canvas: Canvas, l: Layout) {
        val tp = paint
        tp.shader = null
        tp.color = maskDimColor
        drawLayoutPass(canvas, l)
        for (line in 0 until l.lineCount) {
            WordMaskProgress.drawSungLine(
                l,
                tp,
                line,
                sungOffset,
                maskSungColor,
                maskSoftTransition,
                maskSoftStrength,
            ) { clip ->
                drawLayoutPass(canvas, l, clip)
            }
        }
        tp.shader = null
    }

    /**
     * 手动绘制描边：复刻 TextView.onDraw 中文字区域的裁剪与平移，
     * 再用 Layout.draw 绘制，使 paint 描边颜色不被 TextView 内部重置。
     *
     * 兼容旧的逐字 Span 方案：描边阶段临时摘除 [ForegroundColorSpan]，
     * 避免 span 颜色污染描边色（新蒙版方案文本不带 span，此处为空操作）。
     */
    private fun drawStrokePass(canvas: Canvas) {
        val l = layout ?: return
        val spanned = l.text as? Spannable
        val saved = spanned?.let { s ->
            s.getSpans(0, s.length, ForegroundColorSpan::class.java).map { span ->
                SavedSpan(
                    span,
                    s.getSpanStart(span),
                    s.getSpanEnd(span),
                    s.getSpanFlags(span)
                )
            }
        }.orEmpty()
        saved.forEach { spanned?.removeSpan(it.span) }
        try {
            drawLayoutPass(canvas, l)
        } finally {
            saved.forEach { spanned?.setSpan(it.span, it.start, it.end, it.flags) }
        }
    }

    /**
     * 复刻 TextView 文字区域的裁剪/平移后绘制 [l]。
     * [clip] 为 layout 坐标系下的额外裁剪矩形（用于已唱部分蒙版），null 表示整行。
     */
    /**
     * 复刻 TextView 在非滚动状态下按 gravity 产生的水平偏移。
     *
     * 横向滚动时 Layout 宽度极大且强制 START，偏移为 0；不滚动（如桌面歌词
     * 前奏静止显示）时 TextView 原生绘制会按居中/右对齐平移文字，手绘描边
     * 若忽略该偏移就会与文字错位——表现为描边跑到文字左侧/下方。
     */
    private fun horizontalDrawOffset(l: Layout): Float {
        val avail = width - compoundPaddingLeft - compoundPaddingRight
        if (avail <= 0) return 0f
        val layoutWidth = l.width
        if (layoutWidth >= avail) return 0f
        return when (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.CENTER_HORIZONTAL -> ((avail - layoutWidth) / 2f)
            Gravity.RIGHT -> (avail - layoutWidth).toFloat()
            else -> 0f
        }
    }

    private fun drawLayoutPass(canvas: Canvas, l: Layout, clip: FloatArray? = null) {
        val scrollX = scrollX
        val scrollY = scrollY
        val cpLeft = compoundPaddingLeft
        val cpRight = compoundPaddingRight
        val cpTop = compoundPaddingTop
        val cpBottom = compoundPaddingBottom
        val extTop = extendedPaddingTop
        val extBottom = extendedPaddingBottom

        val vspace = height - cpBottom - cpTop
        val maxScrollY = l.height - vspace

        canvas.save()
        canvas.clipRect(
            (cpLeft + scrollX).toFloat(),
            (if (scrollY == 0) 0 else extTop + scrollY).toFloat(),
            (width - cpRight + scrollX).toFloat(),
            (height + scrollY - if (scrollY == maxScrollY) 0 else extBottom).toFloat()
        )

        val boxHeight = height - cpBottom - cpTop
        val voffset = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> boxHeight - l.height
            Gravity.CENTER_VERTICAL -> (boxHeight - l.height) / 2
            else -> 0
        }
        canvas.translate(
            cpLeft.toFloat() + horizontalDrawOffset(l),
            (extTop + voffset).toFloat()
        )
        if (clip != null) {
            canvas.clipRect(clip[0], clip[1], clip[2], clip[3])
        }
        l.draw(canvas)
        canvas.restore()
    }

    private data class SavedSpan(val span: Any, val start: Int, val end: Int, val flags: Int)
}
