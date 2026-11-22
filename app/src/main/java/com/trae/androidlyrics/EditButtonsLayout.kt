package com.trae.androidlyrics

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 编辑页辅助按钮容器：支持两种排布方式（由「更多设置」里的分段控件切换）。
 *
 * - [wrapEnabled] = true（默认，自动换行）：按钮按父容器可用宽度从左往右排，
 *   放不下时折到下一行，外层 HorizontalScrollView 因内容不超宽而不会滚动。
 * - [wrapEnabled] = false（左右滚动）：所有按钮排成一行，测量宽度为内容总宽，
 *   由外层 HorizontalScrollView 提供左右滚动。
 *
 * 不引入第三方 Flexbox 依赖，自己实现测量与布局；子 View 支持 margin。
 */
class EditButtonsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /** true 自动换行 / false 单行横向滚动。 */
    var wrapEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    /**
     * 自动换行模式下 HorizontalScrollView 会用 UNSPECIFIED 宽度测量本容器，但 spec 里
     * 携带的 size 正是父容器扣掉内边距后的可用宽度；直接取该值最精确。
     * 极端情况（size 为 0）回退到父容器实测宽度或屏幕宽度。
     */
    private fun availableWidth(widthMeasureSpec: Int): Int {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        if (size > 0) return size
        val parentView = parent as? View
        return parentView?.measuredWidth?.takeIf { it > 0 }
            ?: parentView?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
    }

    /** 测量一行或换行后的内容尺寸。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = availableWidth(widthMeasureSpec)
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        var maxWidth = paddingLeft

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            // 按钮统一按内容测量：避免依赖 style 中声明的 layout_width/height，
            // 同时保证「左右滚动」模式下按钮不会被压缩或换行。
            child.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val (cw, ch) = childSize(child)
            if (wrapEnabled && x > paddingLeft && x + cw > limit) {
                maxWidth = maxOf(maxWidth, x)
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
            }
            x += cw
            rowHeight = maxOf(rowHeight, ch)
            maxWidth = maxOf(maxWidth, x)
        }
        val contentHeight = y + rowHeight + paddingBottom

        val measuredW = if (wrapEnabled) {
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                MeasureSpec.getSize(widthMeasureSpec)
            } else {
                limit
            }
        } else {
            maxWidth + paddingRight
        }
        val measuredH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            contentHeight
        }
        setMeasuredDimension(measuredW, measuredH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val limit = measuredWidth
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val (cw, ch) = childSize(child)
            if (wrapEnabled && x > paddingLeft && x + cw > limit) {
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
            }
            val lp = child.layoutParams as? MarginLayoutParams
            val left = x + (lp?.leftMargin ?: 0)
            val top = y + (lp?.topMargin ?: 0)
            child.layout(
                left,
                top,
                left + child.measuredWidth,
                top + child.measuredHeight
            )
            x += cw
            rowHeight = maxOf(rowHeight, ch)
        }
    }

    /** 子 View 占宽/占高（含 margin）。 */
    private fun childSize(child: View): Pair<Int, Int> {
        val lp = child.layoutParams as? MarginLayoutParams
        val w = child.measuredWidth + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
        val h = child.measuredHeight + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
        return w to h
    }
}
