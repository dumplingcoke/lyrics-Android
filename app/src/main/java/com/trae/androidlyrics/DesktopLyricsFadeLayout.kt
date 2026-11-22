package com.trae.androidlyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * 桌面歌词悬浮窗的内容层：给**文字区域**盖一层水平蒙版，把文字左右两端按线性 alpha
 * 羽化掉，让长句滚动进出、或贴边静止时不再出现硬切的断口。
 *
 * 整体思路只有三句话：
 *
 * 1. 只淡文字，不动背景。蒙版在 [dispatchDraw] 里画：`saveLayer` 圈出文字层，
 *    画完子 View 后叠一个水平渐变做 `DST_IN`，只削文字的 alpha。悬浮窗的圆角黑底在
 *    背景阶段就已经落在画布上、不在这个图层里，所以背景和圆角一个像素都不会变。
 * 2. 羽化带贴**可见文字区**的两端。「可见文字区」= 文字子 View 去掉自身内边距后的
 *    并集（含滚动位移）再裁到窗口内容区。裁这一步很关键：长句滚动时文字 View 会整体
 *    移出窗口，若按它自己的边界量带宽，羽化带会跑到屏幕外、看起来完全没效果。
 *    短句居中时两者重合，效果不受影响。
 * 3. 带宽按**字**算（[edgeFadeChars]，1~6）：1 = 左右各半个字逐渐消失，2 = 左右各
 *    一个字；字宽用当前字体的实际全角字宽量出来，所以换字号后比例不变。
 */
class DesktopLyricsFadeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 左右边缘渐隐开关；默认开启。 */
    var edgeFadeEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 羽化范围，按半个字计：1 = 左右各半个字，2 = 左右各一个字，最大 6，默认 1。 */
    var edgeFadeChars: Int = Prefs.DEFAULT_DESKTOP_EDGE_FADE_CHARS
        set(value) {
            val safe = value.coerceIn(
                Prefs.MIN_DESKTOP_EDGE_FADE_CHARS,
                Prefs.MAX_DESKTOP_EDGE_FADE_CHARS
            )
            if (field == safe) return
            field = safe
            invalidateMask()
        }

    /** DST_IN：结果只保留「文字 × 蒙版 alpha」，蒙版自身的 RGB 不参与混合。 */
    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        isAntiAlias = false
    }

    /** 复用的数组与几何对象，避免滚动时每帧分配。 */
    private val stopPositions = FloatArray(STOP_COUNT * 2)
    private val stopColors = IntArray(STOP_COUNT * 2)
    private val visibleArea = RectF()

    /** 缓存当前蒙版，避免每帧重建渐变；[maskKey] 变化即失效。 */
    private var maskShader: LinearGradient? = null
    private var maskKey = 0L

    override fun dispatchDraw(canvas: Canvas) {
        val area = visibleTextArea()
        val band = fadeBandPx(area)
        if (!edgeFadeEnabled || area == null || band <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val shader = obtainShader(area, band) ?: run {
            super.dispatchDraw(canvas)
            return
        }

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        maskPaint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
    }

    /**
     * 可见文字区的横向范围：所有可见文字子 View 去掉自身左右内边距后的并集
     * （含 translationX 滚动位移），再裁到窗口内容区（去掉本容器的 padding）。
     * 没有可测的子 View 时返回 null，此时不做任何淡化。
     */
    private fun visibleTextArea(): RectF? {
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var found = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE || child.width <= 0) continue
            val l = child.left + child.translationX + child.paddingLeft
            val r = child.left + child.translationX + child.width - child.paddingRight
            if (r <= l) continue
            left = min(left, l)
            right = max(right, r)
            found = true
        }
        if (!found) return null

        // 裁到内容区：文字被 clipToPadding 裁掉的部分不会再显示，羽化带也应该只按
        // 屏幕上真实能看到的那一段算。
        val clipLeft = paddingLeft.toFloat()
        val clipRight = (width - paddingRight).toFloat()
        val visibleLeft = max(left, clipLeft)
        val visibleRight = min(right, clipRight)
        if (visibleRight <= visibleLeft) return null

        visibleArea.set(visibleLeft, 0f, visibleRight, height.toFloat())
        return visibleArea
    }

    /**
     * 单侧羽化带宽（px）= 数值 × 半个字宽，并且不超过可见文字宽度的一半（留 1px），
     * 保证两段羽化带之间始终有一段完全实心的文字。
     */
    private fun fadeBandPx(area: RectF?): Float {
        if (area == null) return 0f
        val halfChar = charWidthPx() / 2f
        if (halfChar <= 0f) return 0f
        val wanted = edgeFadeChars * halfChar
        val limit = area.width() / 2f - 1f
        return min(wanted, limit)
    }

    /** 当前字体的全角字宽（px）：用文字 View 的 Paint 实测；量不到时退回字号 / 窗口 1/12。 */
    private fun charWidthPx(): Float {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !is TextView || child.visibility != View.VISIBLE) continue
            val measured = child.paint.measureText(FULL_WIDTH_CHAR)
            if (measured > 0f) return measured
            if (child.textSize > 0f) return child.textSize
        }
        return if (width > 0) width / 12f else 0f
    }

    /**
     * 水平蒙版：文字左边缘 alpha 0 → 带宽处 255 → 中间实心 → 右侧对称 → 右边缘 alpha 0。
     * 渐变坐标系直接取可见文字区，CLAMP 模式让区外保持边缘色（那里本来也没有文字）。
     */
    private fun obtainShader(area: RectF, band: Float): LinearGradient? {
        val left = area.left
        val right = area.right
        val textWidth = area.width()
        if (textWidth <= 0f) return null

        val key = maskKeyOf(area, band)
        if (maskShader != null && maskKey == key) return maskShader

        val leftEnd = band / textWidth
        val rightStart = 1f - band / textWidth
        if (leftEnd >= rightStart) return null

        for (i in 0 until STOP_COUNT) {
            val t = i.toFloat() / (STOP_COUNT - 1)
            stopPositions[i] = leftEnd * t
            stopColors[i] = alphaColor(t)
            stopPositions[STOP_COUNT + i] = rightStart + (1f - rightStart) * t
            stopColors[STOP_COUNT + i] = alphaColor(1f - t)
        }

        val shader = LinearGradient(
            left, 0f, right, 0f,
            stopColors, stopPositions, Shader.TileMode.CLAMP
        )
        maskShader = shader
        maskKey = key
        return shader
    }

    /** 缓存键：窗口尺寸 + 数值 + 可见区两端 + 带宽（带宽已含字宽，字号变化会自动失效）。 */
    private fun maskKeyOf(area: RectF, band: Float): Long {
        var h = width.toLong() * 31 + height
        h = h * 31 + edgeFadeChars
        h = h * 31 + area.left.toInt()
        h = h * 31 + area.right.toInt()
        h = h * 31 + (band * 1000f).toInt()
        return h
    }

    /**
     * alpha 随 t（0 = 可见文字区边缘，1 = 羽化带末尾）线性上升。
     * 不用 smoothstep 之类的缓动：那条曲线前半段几乎透明，边缘的字会像"凭空消失"，
     * 而"半个字逐渐消失"要的是边缘仍然看得见、只是越来越淡。
     */
    private fun alphaColor(t: Float): Int {
        val alpha = (t.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        return Color.argb(alpha, 0, 0, 0)
    }

    private fun invalidateMask() {
        maskShader = null
        maskKey = 0L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 窗口尺寸变化（单/双行切换、字号变化、自适应宽度、手动改宽度）要重建蒙版
        maskShader = null
        maskKey = 0L
    }

    private companion object {
        /** 单侧渐变的采样点数量（越多越平滑）。 */
        const val STOP_COUNT = 12

        /** 用于量全角字宽的代表字符。 */
        const val FULL_WIDTH_CHAR = "国"
    }
}
