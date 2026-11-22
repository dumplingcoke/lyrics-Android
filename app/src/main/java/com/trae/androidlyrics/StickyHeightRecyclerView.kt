package com.trae.androidlyrics

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 「高度只增不减」的横向分页 RecyclerView。
 *
 * 监听状态页的左右滑动卡片高度按内容自适应（wrap_content）。信息多的卡片会把
 * 容器撑高，划回信息少的卡片时 RecyclerView 按新内容重新测量又会缩回去，
 * 表现为整块区域上下跳动。这里记录本次会话中量到过的最大高度，并在后续测量
 * 中把结果抬回该高度；卡片同时在绑定时被赋予同样的 minimumHeight，
 * 因此较短的卡片也会填满已撑开的高度，而不是顶部留一块空白。
 *
 * 高度记录是「本次会话」级别的：切换排列方式或改动卡片歌词行数时由页面调用
 * [resetStickyHeight] 清零，避免旧配置留下的高度永久占位。
 */
class StickyHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    @Volatile
    var stickyHeight: Int = 0
        private set

    fun resetStickyHeight() {
        stickyHeight = 0
        // 已附着的卡片仍带着上一轮写入的 minimumHeight；不清空的话下一次测量
        // 会把旧高度重新量成「最大高度」，reset 等于没做。
        for (i in 0 until childCount) getChildAt(i).minimumHeight = 0
        requestLayout()
    }

    /**
     * 卡片内容应达到的最小高度：容器最大高度减去该卡片的上下外边距与列表内边距。
     * 直接把 [stickyHeight] 赋给卡片，会把 margin/padding 一并算进卡片内容高度，
     * 于是「卡片变高 → 容器变高 → 卡片再变高」形成反馈式增高。
     */
    fun stickyItemMinHeight(view: View): Int {
        val lp = view.layoutParams as? LayoutParams
        val verticalDecor = paddingTop + paddingBottom +
            (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
        return (stickyHeight - verticalDecor).coerceAtLeast(0)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val measured = measuredHeight
        if (measured > stickyHeight) stickyHeight = measured
        if (stickyHeight > measured) {
            setMeasuredDimension(measuredWidth, stickyHeight)
        }
    }
}
