package com.trae.androidlyrics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Xfermode
import android.text.TextPaint

/**
 * 歌词文字外观的公共绘制工具：颜色 alpha 合成 + 三种描边位置的绘制编排。
 *
 * [OutlineTextView]（桌面歌词）与 [MaskedLyricView]（主界面逐字行）共用，
 * 保证「内部 / 居中 / 外部」在两处表现一致。
 *
 * Paint.Style.STROKE 画文字时描的是**字形轮廓线**，线宽以字形边界为中心
 * 内外各占一半，所以三种位置只需调整线宽与绘制顺序：
 * - 外部（默认）：STROKE(2w) → FILL。填充覆盖内侧 w，只剩字形外 w 的描边，
 *   与旧版「先描边后填充」完全等价。
 * - 居中：FILL → STROKE(w)。描边跨字形边界，内外各 w/2，字形中心仍是填充色。
 * - 内部：离屏 layer 内先 FILL，再以 SRC_ATOP 画 STROKE(2w)。SRC_ATOP 让
 *   轮廓线只落在 layer 中已有 alpha 的区域（即字形内部），外部那一半被丢弃，
 *   得到字形内 w 的描边。用 layer + Xfermode 而不是 clipPath，是因为文字轮廓
 *   是非凸 Path，API 24~27 的硬件加速不支持 clipPath 非凸路径。
 *
 * 逐字蒙版模式下 drawFill 一次会画完「未唱整行 + 已唱裁剪」两层；描边始终
 * 编排在最外，因此居中/内部描边不会被后续 FILL 覆盖。
 */
object LyricTextStyle {

    /** 按百分比（0~100）缩放颜色的 alpha 通道。 */
    fun withAlpha(color: Int, percent: Int): Int {
        val a = (Color.alpha(color) * percent / 100f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** 在已有 alpha 上再乘一个 0~255 的系数（非当前行淡色、未唱层淡色）。 */
    fun scaleAlpha(color: Int, factor: Int): Int {
        val a = (Color.alpha(color) * factor / 255f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private class PaintState(
        val style: Paint.Style,
        val strokeWidth: Float,
        val color: Int,
        val cap: Paint.Cap,
        val join: Paint.Join,
        val miter: Float,
        val shader: Shader?,
        val xfermode: Xfermode?,
    )

    private val insideMode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)

    /**
     * 绘制一遍文字（含可选描边）。两个回调都使用同一支 [paint] 绘制同一个 Layout：
     * - [drawFill]：paint 已切到 FILL / 无 shader / 无 xfermode，回调内部自行设置
     *   每一层的颜色（普通模式一种，逐字模式未唱与已唱两种）。
     * - [drawStroke]：paint 已切到 STROKE 且颜色/线宽按位置设好。
     *
     * [outlineWidthPx] <= 0 表示不描边，此时只调 [drawFill]，零额外开销。
     */
    fun drawWithOutline(
        canvas: Canvas,
        width: Int,
        height: Int,
        paint: TextPaint,
        outlineColor: Int,
        outlineWidthPx: Float,
        position: Int,
        drawFill: () -> Unit,
        drawStroke: () -> Unit,
    ) {
        val saved = PaintState(
            paint.style, paint.strokeWidth, paint.color,
            paint.strokeCap, paint.strokeJoin, paint.strokeMiter,
            paint.shader, paint.xfermode,
        )
        try {
            if (outlineWidthPx <= 0f) {
                setFill(paint)
                drawFill()
                return
            }
            when (position) {
                Prefs.OUTLINE_POSITION_INSIDE -> {
                    val layer = canvas.saveLayer(
                        0f, 0f,
                        width.toFloat().coerceAtLeast(1f),
                        height.toFloat().coerceAtLeast(1f),
                        null,
                    )
                    try {
                        setFill(paint)
                        drawFill()
                        setStroke(paint, outlineColor, outlineWidthPx * 2f)
                        paint.xfermode = insideMode
                        drawStroke()
                    } finally {
                        paint.xfermode = null
                        canvas.restoreToCount(layer)
                    }
                }
                Prefs.OUTLINE_POSITION_CENTER -> {
                    setFill(paint)
                    drawFill()
                    setStroke(paint, outlineColor, outlineWidthPx)
                    drawStroke()
                }
                else -> { // OUTSIDE（也是旧版的默认效果）
                    setStroke(paint, outlineColor, outlineWidthPx * 2f)
                    drawStroke()
                    setFill(paint)
                    drawFill()
                }
            }
        } finally {
            paint.style = saved.style
            paint.strokeWidth = saved.strokeWidth
            paint.color = saved.color
            paint.strokeCap = saved.cap
            paint.strokeJoin = saved.join
            paint.strokeMiter = saved.miter
            paint.shader = saved.shader
            paint.xfermode = saved.xfermode
        }
    }

    private fun setFill(paint: TextPaint) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.xfermode = null
    }

    private fun setStroke(paint: TextPaint, color: Int, widthPx: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = widthPx
        paint.color = color
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeMiter = widthPx * 2f
        paint.shader = null
        paint.xfermode = null
    }
}
