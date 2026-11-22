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
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 标题栏与内容区边界的柔化过渡，机制与 [LyricsFadeFrameLayout] 完全一致：
 *
 * ```
 * saveLayer == CompositingStrategy.Offscreen     -> 独立合成缓冲
 * drawContent(); drawRect(渐变, DstIn)            -> 只裁内容自身的 alpha
 * ```
 *
 * 与旧做法（`android:windowContentOverlay` 叠一条 12dp 的「背景色 → 透明」色带）
 * 的区别：
 *
 * 1. **不叠色带**。色带是 SRC_OVER，盖在内容上是一层「雾」：卡片、图片、按钮在
 *    带内被染成背景色，带的下沿还能看出起始边。这里只把内容的 alpha 按渐变裁掉，
 *    内容直接溶解回页面背景，边界处没有任何一条可见的「带」。
 * 2. **不常驻**。旧色带任何时刻都盖在内容最上方，紧贴标题栏的第一行文字
 *    （例如搜索框的提示语）会被压暗一截。这里的柔化强度由「内容已经卷到标题栏
 *    下方的距离」驱动：未滚动 → 0（内容完全清晰）；滚动满一个柔化带高度 → 1。
 *    因此静止页面与旧版完全无异，只有内容真的从标题栏下方穿过时才柔化。
 * 3. **强度连续**。强度随滚动距离线性上升，不会在「开始滚动」的那一帧跳变。
 *
 * 使用方式：把一个可上下滚动的容器作为唯一子 View 放进来（[ThemedActivity]
 * 在 setContentView 之后自动完成这一步），本层不负责布局策略，只负责绘制蒙版。
 */
class EdgeFadeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * 柔化带高度（dp）。取值与 [LyricsFadeFrameLayout.fadeTopDp] 一致（24dp），
     * 让标题栏下沿的过渡和「底部播放控制条上边缘」看上去是同一种力度。
     */
    var fadeHeightDp: Float = 24f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** DST_IN：只保留「内容 × 蒙版 alpha」，蒙版 RGB 不参与混合。 */
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    private var shader: LinearGradient? = null
    private var shaderHeight = -1
    private var shaderTopAlpha = -1

    /**
     * 柔化强度在**绘制时**按当前滚动位置现算，不依赖任何滚动回调。
     *
     * 最初用 ViewTreeObserver.OnScrollChangedListener + RecyclerView.OnScrollListener
     * 监听滚动再 invalidate，实测 NestedScrollView 一类的滚动拿不到稳定回调，蒙版
     * 会停在「未滚动」的强度上；而滚动位置一旦变化，内容必然重绘，所以放在
     * dispatchDraw 里现算既简单又不会漏。这里只读一个 int，代价可以忽略。
     */
    override fun dispatchDraw(canvas: Canvas) {
        val mask = ensureShader(currentProgress())
        if (mask == null) {
            super.dispatchDraw(canvas)
            return
        }
        maskPaint.shader = mask
        // saveLayer 只在已经进入柔化时发生：静止页面不产生任何离屏合成开销。
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(
            0f, 0f, width.toFloat(),
            min(height.toFloat(), fadeHeightPx()), maskPaint
        )
        canvas.restoreToCount(saveCount)
    }

    /** 内容已卷过顶边的距离 / 柔化带高度，得到 0~1 的强度。 */
    private fun currentProgress(): Float {
        val scroller = getChildAt(0) ?: return 0f
        val fadePx = fadeHeightPx()
        if (fadePx <= 0f) return 0f
        return (scrollOffsetOf(scroller) / fadePx).coerceIn(0f, 1f)
    }

    private fun fadeHeightPx(): Float = fadeHeightDp * resources.displayMetrics.density

    /**
     * 取出「内容已经卷过顶边的距离」。
     *
     * View.computeVerticalScrollOffset() 是 protected，无法按 View 统一调用，因此分类型取：
     * RecyclerView 公开重写、ScrollView / NestedScrollView 直接用 scrollY、
     * GridView / ListView 靠首个可见项估算。
     */
    private fun scrollOffsetOf(v: View): Int = when (v) {
        is RecyclerView -> v.computeVerticalScrollOffset().coerceAtLeast(0)
        is AbsListView -> absListOffset(v)
        else -> v.scrollY.coerceAtLeast(0)
    }

    /** AbsListView 通过移动子 View 滚动，scrollY 恒为 0，用「第几项 + 首项偏移」估算 px。 */
    private fun absListOffset(v: AbsListView): Int {
        if (v.childCount == 0) return 0
        val firstTop = v.getChildAt(0).top - v.paddingTop
        val itemHeight = v.getChildAt(0).height.coerceAtLeast(1)
        return (v.firstVisiblePosition * itemHeight - firstTop).coerceAtLeast(0)
    }

    /**
     * 顶部渐变蒙版：y=0 处 alpha = 255 × (1 - 强度)，到一个柔化带高度处回到全不透明，
     * 之下整段不透明（TileMode.CLAMP）。未滚动时返回 null，走零开销的普通绘制。
     */
    private fun ensureShader(progress: Float): Shader? {
        val h = height
        if (h <= 0 || progress <= 0f) {
            shader = null
            shaderHeight = -1
            shaderTopAlpha = -1
            return null
        }
        val fade = min(fadeHeightPx(), h.toFloat())
        if (fade <= 0f) return null
        val topAlpha = ((1f - progress) * 255f).roundToInt().coerceIn(0, 255)
        if (h == shaderHeight && topAlpha == shaderTopAlpha) return shader
        shaderHeight = h
        shaderTopAlpha = topAlpha
        shader = LinearGradient(
            0f, 0f, 0f, fade,
            Color.argb(topAlpha, 0, 0, 0), Color.BLACK,
            Shader.TileMode.CLAMP
        )
        return shader
    }

    companion object {
        /**
         * 把 [scroller] 原地套进一个 [EdgeFadeFrameLayout]（沿用原来的 LayoutParams，
         * 因此 layout_weight / margin / 固定高度全部保持不变）。
         *
         * @return 套好后新建（或已存在）的柔化层；[scroller] 还没有父容器时返回 null。
         */
        fun wrap(scroller: View): EdgeFadeFrameLayout? {
            val parent = scroller.parent as? ViewGroup ?: return null
            if (parent is EdgeFadeFrameLayout) return parent
            val index = parent.indexOfChild(scroller)
            // ViewGroup.generateDefaultLayoutParams() 是 protected，这里用等价的最小默认值。
            val lp = scroller.layoutParams
                ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            parent.removeViewAt(index)
            val fade = EdgeFadeFrameLayout(scroller.context)
            fade.addView(
                scroller,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
            parent.addView(fade, index, lp)
            return fade
        }
    }
}
