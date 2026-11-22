package com.trae.androidlyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.max

/**
 * 逐行对应 lyrics-android 的 `Modifier.homeLyricsFade()`：
 *
 * ```
 * graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }  -> [setLayerType] 独立合成层
 * .drawWithCache { Brush.verticalGradient(                                 -> [buildMask] 全高渐变
 *      0f -> Transparent, top -> Black, 1-bottom -> Black, 1f -> Transparent) }
 * onDrawWithContent { drawContent(); drawRect(mask, BlendMode.DstIn) }     -> [dispatchDraw] saveLayer + DST_IN
 * ```
 *
 * 关键前提与原项目一致：**这一层是透明的，只画歌词**。专辑模糊背景与
 * `#80000000` 遮罩是 [com.trae.androidlyrics.MainActivity] 布局里另外两个兄弟 View，
 * 控制条也在这层之外。因此 `DST_IN` 只裁歌词文字的 alpha，背景像素一个都不改，
 * 从歌词区到控制条区不会出现任何亮度突变或横向硬边。
 *
 * 渐变铺满整个层高、两端 alpha 归零、中间不透明，所以不存在「一条带的起止边」——
 * 这正是第一版叠加「透明→深色」SRC_OVER 色带产生硬边的原因。
 */
class LyricsFadeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 由「底部播放控制条上边缘柔化」开关控制；默认开启即生效，不依赖首次赋值时机。 */
    var fadeEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** 顶部渐隐长度（dp），与原项目 24.dp 一致。 */
    var fadeTopDp: Float = 24f
        set(value) {
            if (field == value) return
            field = value
            buildMask(width, height)
            invalidate()
        }

    /** 底部渐隐长度（dp），与原项目 40.dp 一致。 */
    var fadeBottomDp: Float = 40f
        set(value) {
            if (field == value) return
            field = value
            buildMask(width, height)
            invalidate()
        }

    /**
     * 本项目歌词列表有较大的上下留白（尤其开启「允许上下空白」时底部可达半屏），
     * 原项目固定 40dp 可能整段落在空白里，看起来像「开关没生效」。
     * 因此底部渐隐带额外保证至少覆盖可视区的一定比例，机制仍是同一个 DstIn 蒙版。
     * 上限 20% 沿用原项目 `.coerceAtMost(.2f)`。
     */
    var fadeBottomMinFraction: Float = 0.12f
        set(value) {
            if (field == value) return
            field = value.coerceIn(0f, 0.2f)
            buildMask(width, height)
            invalidate()
        }

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var maskReady = false

    init {
        setWillNotDraw(false)
        // CompositingStrategy.Offscreen：歌词内容先进独立合成缓冲，
        // DST_IN 才不会把下面整块窗口（含专辑背景）一起擦掉。
        setLayerType(LAYER_TYPE_HARDWARE, null)
        buildMask(0, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 控制条展开/收起、旋屏、键盘收起都会改变歌词层高度，渐变自动跟着重建，
        // 不需要像旧的「蒙层 View」那样手动对齐控制条上沿。
        buildMask(w, h)
    }

    private fun buildMask(w: Int, h: Int) {
        if (h <= 0) {
            maskReady = false
            return
        }
        val d = resources.displayMetrics.density
        val topPx = fadeTopDp * d
        // 底部：取「原项目 40dp」与「可视区最小比例」中较大的，再按原项目封顶 20%
        val bottomPx = max(fadeBottomDp * d, h * fadeBottomMinFraction)
            .coerceAtMost(h * 0.2f)
        val top = (topPx / h).coerceAtMost(0.2f)
        val bottom = (bottomPx / h).coerceAtMost(0.2f)
        maskPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,   // 0f
                Color.BLACK,         // top
                Color.BLACK,         // 1f - bottom
                Color.TRANSPARENT    // 1f
            ),
            floatArrayOf(0f, top, 1f - bottom, 1f),
            Shader.TileMode.CLAMP
        )
        maskReady = true
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!fadeEnabled || !maskReady) {
            super.dispatchDraw(canvas)
            return
        }
        // saveLayer == Offscreen 缓冲；drawContent() == super.dispatchDraw(歌词 RecyclerView)；
        // drawRect(mask, DstIn) == 只保留歌词文字的 alpha。
        val saveCount = canvas.saveLayer(
            0f, 0f, width.toFloat(), height.toFloat(), null
        )
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(saveCount)
    }
}
