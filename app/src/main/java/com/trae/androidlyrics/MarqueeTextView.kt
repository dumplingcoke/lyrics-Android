package com.trae.androidlyrics

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView

/**
 * 单行文本超出控件宽度时循环滚动的 TextView。
 *
 * 主页标题栏的歌词文件名（左上角）与底部播放控制条的歌曲名共用本控件：
 * 文字放得下时正常静止显示；真的溢出时整段文字持续向左滑出，滑完后紧跟一段空白，
 * 下一份文字从右侧接进来，首尾相接无限循环（不是滚到头再跳回开头）。
 *
 * 滚动完全由本控件自己绘制，不依赖系统/TextView 的两种内置机制：
 *  - 不用 `ellipsize = MARQUEE`：内置 Marquee 要求 `isFocused() || isSelected()`、
 *    可见、`canMarquee()` 同时成立，而播放控制条是「先 GONE、之后才 VISIBLE」的，
 *    Marquee 在不可见那一帧会静默停住（tick 里条件不成立时只是不再投递下一帧，
 *    状态仍是 RUNNING，之后 startMarquee 也会因为「未 stopped」拒绝重启）；
 *  - 不用 `scrollX`：TextView 自身的裁剪窗口跟着 scrollX 走，但文字绘制是否被
 *    整体平移取决于外层绘制路径，实测只会看到裁剪边界在动、文字位置基本不动，
 *    观感就是抖动而不是滚动。
 *
 * 所以这里直接接管 onDraw：按 [offsetPx] 先裁剪到内容框，再把画布整体左移
 * [offsetPx]，最后画已经排好版的单行文本。位置由自己完全决定，与焦点、
 * selected、视图滚动量都无关。
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 滚动速度（dp/秒），与系统 marquee 的 30dp/s 保持一致。 */
    private val scrollDpPerSecond = 30f

    private val density = resources.displayMetrics.density

    private val pixelsPerMs = scrollDpPerSecond * density / 1000f

    /** 一轮结束时下一份文字与上一份之间的空白，避免两段文字直接连成一串。 */
    private val repeatGapPx = 40f * density

    /** 小于这个溢出量（约 4dp）直接当放得下，不为几个像素的尾巴一直循环。 */
    private val minScrollPx = 4f * density

    private var scrolling = false
    private var frameScheduled = false
    private var offsetPx = 0f

    /** 本轮循环的起点时刻（uptimeMillis），0 表示尚未起算。 */
    private var scrollStartMs = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (!scrolling) return
            step(frameTimeNanos / 1_000_000L)
        }
    }

    init {
        setSingleLine(true)
        // 不折行、不省略：整段文字由本控件平移展示
        ellipsize = null
        setHorizontallyScrolling(true)
    }

    override fun setText(text: CharSequence?, type: android.widget.TextView.BufferType?) {
        // 只是重复赋同一段文字（例如每帧刷新播控信息）时不要重置进度，否则会一直从头开始
        val changed = this.text?.toString() != text?.toString()
        super.setText(text, type)
        if (changed) resetScrollState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resetScrollState()
    }

    override fun onDetachedFromWindow() {
        stopScroll()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 可用宽度变了（横竖屏切换、卡片展开收起、标题给右侧菜单让位）就按新宽度从头滚
        if (w != oldw) resetScrollState()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        // 播放控制条从 GONE 变为 VISIBLE 时会走到这里，重新评估一次是否需要滚动
        if (isVisible) resetScrollState() else stopScroll()
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout ?: return
        val left = compoundPaddingLeft
        val right = width - compoundPaddingRight
        if (right <= left || layout.lineCount <= 0) return

        val top = extendedPaddingTop + verticalOffset(layout)
        val period = periodPx()
        if (period <= 0) return
        // 只有真的溢出、需要循环播放时才画多份；短文本只画一份，否则会在同一行看到两个标题
        val repeat = overflowPx() > minScrollPx
        // TextView 的颜色是在它自己绘制时才刷到 paint 上的，这里直接用 layout 绘制就得手动同步，
        // 否则会退化成 paint 的默认黑色（白字变黑字，看起来像"样式变了"）
        paint.color = currentTextColor

        canvas.save()
        // 先裁到内容框：多出来的文字只能在框内出现
        canvas.clipRect(left.toFloat(), 0f, right.toFloat(), height.toFloat())
        // 循环滚动时按需画多份：当前这份向左滑出，下一份紧跟在空白之后从右侧进入
        var x = left - offsetPx
        var copies = 0
        while (true) {
            canvas.save()
            canvas.translate(x, top.toFloat())
            layout.draw(canvas)
            canvas.restore()
            copies++
            x += period
            if (!repeat || x >= right || copies >= 8) break
        }
        canvas.restore()

        // 绘制发生在布局之后：这里按最新的文本宽度/控件宽度决定要不要滚
        ensureScrolling()
    }

    /** 单行文本的垂直位置：默认顶对齐，兼容 CENTER_VERTICAL / BOTTOM 两种重力。 */
    private fun verticalOffset(layout: Layout): Int {
        val space = height - extendedPaddingTop - extendedPaddingBottom - layout.height
        if (space <= 0) return 0
        return when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.CENTER_VERTICAL -> space / 2
            Gravity.BOTTOM -> space
            else -> 0
        }
    }

    /**
     * 溢出的像素数：文本行宽超出可视内容宽度即为溢出。
     * 文本按横向滚动布局排版，行宽就是整段文字的宽度，不会被控件宽度截断。
     */
    private fun overflowPx(): Int {
        val layout = layout ?: return 0
        if (layout.lineCount <= 0) return 0
        val viewport = width - compoundPaddingLeft - compoundPaddingRight
        if (viewport <= 0) return 0
        return (layout.getLineWidth(0) - viewport).toInt()
    }

    /**
     * 一个重复单元的长度：整段文字宽度 + 末尾空白。
     * 位移按这个长度取模，到末尾时无缝接上下一份文字，所以不会出现"回到开头"的跳变。
     */
    private fun periodPx(): Int {
        val layout = layout ?: return 0
        if (layout.lineCount <= 0) return 0
        return (layout.getLineWidth(0) + repeatGapPx).toInt().coerceAtLeast(1)
    }

    /** 绘制后检查：可见且溢出才滚动，否则保证静止在起点。 */
    private fun ensureScrolling() {
        if (!isVisibleOnScreen() || overflowPx() <= minScrollPx) {
            stopScroll()
            return
        }
        if (!scrolling) startScroll()
    }

    /** 自己是否真的显示在屏幕上：自身与所有父容器 VISIBLE、已附着、所在窗口可见。 */
    private fun isVisibleOnScreen(): Boolean =
        isAttachedToWindow && isShown && windowVisibility == android.view.View.VISIBLE

    private fun startScroll() {
        scrolling = true
        scrollStartMs = 0L
        schedule()
    }

    private fun schedule() {
        if (frameScheduled) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * 位移按「起点到现在的真实时间 × 速度」算，再对一个重复单元取模：
     *  - 不逐帧累加，掉帧时不会积累偏差，也就不会一顿一顿的；
     *  - 取模后到末尾无缝接上下一份文字，全程连续左移，不存在"滚到头再跳回开头"。
     */
    private fun step(nowMs: Long) {
        if (!isVisibleOnScreen()) {
            stopScroll()
            return
        }
        if (overflowPx() <= minScrollPx) {
            stopScroll()
            return
        }
        val period = periodPx()
        if (period <= 0) {
            stopScroll()
            return
        }
        if (scrollStartMs == 0L) scrollStartMs = nowMs

        val periodMs = (period / pixelsPerMs).toLong().coerceAtLeast(1L)
        val elapsed = (nowMs - scrollStartMs).coerceAtLeast(0L)
        val next = ((elapsed % periodMs) * pixelsPerMs).coerceIn(0f, period.toFloat())
        if (next != offsetPx) {
            offsetPx = next
            invalidate()
        }
        schedule()
    }

    /** 停止滚动并回到起点（文本变化、宽度变化、不可见时调用）。 */
    private fun stopScroll() {
        scrolling = false
        scrollStartMs = 0L
        if (frameScheduled) {
            frameScheduled = false
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        if (offsetPx != 0f) {
            offsetPx = 0f
            invalidate()
        }
    }

    /** 回到起点等下一次绘制重判：滚动被打断后由 [ensureScrolling] 重新起滚。 */
    private fun resetScrollState() {
        stopScroll()
        // 触发一次重绘，让 onDraw 里的判断在布局更新后立刻生效
        invalidate()
    }
}
