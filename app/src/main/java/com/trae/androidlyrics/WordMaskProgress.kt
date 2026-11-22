package com.trae.androidlyrics

import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.Layout
import android.text.TextPaint

/**
 * 逐字动画的连续进度算法：主界面蒙版（[MaskedLyricView]）与桌面歌词
 * （[OutlineTextView] 的逐字蒙版）共用同一套实现，保证两者视觉一致。
 *
 * 核心是「按字时间戳在相邻两字之间线性插值」得到浮点字符偏移 [sungOffset]，
 * 再由 [sungX] 换算成该行已唱到的 x 坐标，绘制时按行裁剪出已唱部分。
 * 与旧的「按整字重建 SpannableString」相比不会跳字、不会触发重新布局。
 */
object WordMaskProgress {

    /** 最后一个字开始后扫到行尾的时长（毫秒）。 */
    const val TAIL_SWEEP_MS = 400f

    /** 一行的逐字轴快照：字列表 + 每个字在整行文本中的起始字符下标 + 总字符数。 */
    class Axis(
        val words: List<LyricWord>,
        val charStarts: IntArray,
        val fullTextLen: Int,
    ) {
        val isEmpty: Boolean get() = words.isEmpty()
    }

    fun build(words: List<LyricWord>): Axis {
        val sb = StringBuilder()
        val starts = IntArray(words.size)
        words.forEachIndexed { i, w ->
            starts[i] = sb.length
            sb.append(w.text)
        }
        return Axis(words, starts, sb.length)
    }

    /** 根据播放进度计算蒙版应推进到的字符位置（浮点，支持字内插值）。 */
    fun sungOffset(axis: Axis, positionMs: Long): Float {
        val words = axis.words
        if (words.isEmpty()) return 0f
        // 找到最后一个已开始的字
        var k = -1
        for (i in words.indices) {
            if (positionMs >= words[i].startTime) k = i else break
        }
        if (k < 0) return 0f
        return if (k == words.size - 1) {
            // 最后一个字：按固定时长平滑推进到文本末尾，让整行自然唱满
            val frac = ((positionMs - words[k].startTime).toFloat() / TAIL_SWEEP_MS)
                .coerceIn(0f, 1f)
            axis.charStarts[k] + (axis.fullTextLen - axis.charStarts[k]) * frac
        } else {
            val w0 = words[k].startTime
            val w1 = words[k + 1].startTime
            val frac = if (w1 > w0) {
                ((positionMs - w0).toFloat() / (w1 - w0)).coerceIn(0f, 1f)
            } else 1f
            axis.charStarts[k] + (axis.charStarts[k + 1] - axis.charStarts[k]) * frac
        }
    }

    /**
     * 把字符偏移换算成 [layout] 中指定行已唱到的 x 坐标。
     * 自动换行的多行文字只有真正唱到的部分高亮；行尾字符钳制到本行右缘，
     * 避免居中/右对齐时蒙版出现回退动画。
     */
    fun sungX(l: Layout, line: Int, sungOffset: Float): Float {
        val lineStart = l.getLineStart(line)
        val lineEnd = l.getLineEnd(line)
        val lineLeft = l.getLineLeft(line).toFloat()
        val lineRight = l.getLineRight(line).toFloat()
        return when {
            sungOffset <= lineStart -> lineLeft
            sungOffset >= lineEnd -> lineRight
            else -> {
                val base = sungOffset.toInt().coerceIn(lineStart, lineEnd - 1)
                val frac = sungOffset - base
                val x0 = l.getPrimaryHorizontal(base)
                val x1 = if (base + 1 >= lineEnd) lineRight else l.getPrimaryHorizontal(base + 1)
                x0 + (x1 - x0) * frac
            }
        }
    }

    /** 强度 0~100 对应 0~1 个字号的柔化宽度，随两处各自字号等比缩放。 */
    fun softEdgeWidthPx(textSizePx: Float, strength: Int): Float =
        textSizePx * strength.coerceIn(0, 100) / 100f

    /**
     * 绘制一行的已唱颜色蒙版。柔化开启时仅给已唱颜色的前缘追加透明渐变，
     * 未唱底层不参与计算；[drawLine] 负责按给定 layout 坐标裁剪并绘制文字。
     */
    fun drawSungLine(
        l: Layout,
        paint: TextPaint,
        line: Int,
        sungOffset: Float,
        sungColor: Int,
        softTransition: Boolean,
        softStrength: Int,
        drawLine: (clip: FloatArray) -> Unit,
    ) {
        val left = l.getLineLeft(line).toFloat()
        val right = l.getLineRight(line).toFloat()
        val x = sungX(l, line, sungOffset)
        if (x <= left) return

        val feather = if (softTransition && x < right) {
            softEdgeWidthPx(paint.textSize, softStrength)
        } else 0f
        val clipRight = (x + feather).coerceAtMost(right)
        paint.color = sungColor
        paint.shader = if (feather > 0f && clipRight > x) {
            LinearGradient(
                x, 0f, clipRight, 0f,
                sungColor,
                sungColor and 0x00FFFFFF,
                Shader.TileMode.CLAMP,
            )
        } else null
        try {
            drawLine(
                floatArrayOf(
                    left,
                    l.getLineTop(line).toFloat(),
                    clipRight,
                    l.getLineBottom(line).toFloat(),
                )
            )
        } finally {
            // 同一支画笔还要绘制未唱层和普通文本，不能遗留蒙版 shader。
            paint.shader = null
        }
    }
}
