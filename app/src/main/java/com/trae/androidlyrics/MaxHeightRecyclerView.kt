package com.trae.androidlyrics

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * 支持 maxHeight 的 RecyclerView：内容超过最大高度时出现滚动条，
 * 未超过时按内容自适应高度。用于"最近目录"列表超过 4 项后可滚动。
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    private var maxHeightPx = 0

    init {
        context.theme.obtainStyledAttributes(attrs, R.styleable.MaxHeightRecyclerView, defStyleAttr, 0)
            .run {
                try {
                    maxHeightPx = getDimension(R.styleable.MaxHeightRecyclerView_maxHeight, 0f).toInt()
                } finally {
                    recycle()
                }
            }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (maxHeightPx > 0) {
            val hSpecMode = MeasureSpec.getMode(heightSpec)
            val hSpecSize = MeasureSpec.getSize(heightSpec)
            if (hSpecSize > maxHeightPx) {
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.EXACTLY)
                )
                return
            }
            if (hSpecMode == MeasureSpec.UNSPECIFIED) {
                super.onMeasure(
                    widthSpec,
                    MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
                )
                return
            }
        }
        super.onMeasure(widthSpec, heightSpec)
    }
}
