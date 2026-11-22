package com.trae.androidlyrics

import android.content.Context
import android.util.AttributeSet
import android.widget.EditText

/**
 * 歌词编辑框：允许编辑光标停留在可视区之外，不被系统强行"回正"。
 *
 * TextView 在选区变化、焦点变化、布局与文本变化后会调用 [bringPointIntoView]
 * 把光标所在行自动滚回可视区。编辑框挂了 LinkMovementMethod（时间戳可点击），
 * 触摸/滚动会清掉 Selection，光标保活逻辑重建 Selection 时又会触发一次自动回正，
 * 表现为「上下滑动浏览歌词时视图被拉回光标处」。
 *
 * 用户自由滑动期间把 [allowCursorOutOfView] 置 true 屏蔽该自动滚动；
 * 滑动停稳、或用户开始输入后恢复系统默认行为。进入编辑、打轴、上一行/下一行
 * 等需要主动定位光标的场景由 MainActivity 显式 scrollTo，不受影响。
 */
class LyricEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    /** 为 true 时允许光标留在可视区之外，屏蔽系统把光标滚回视野的自动回正。 */
    var allowCursorOutOfView = false

    override fun bringPointIntoView(offset: Int): Boolean =
        if (allowCursorOutOfView) false else super.bringPointIntoView(offset)

    override fun bringPointIntoView(offset: Int, requestRectWithoutFocus: Boolean): Boolean =
        if (allowCursorOutOfView) false
        else super.bringPointIntoView(offset, requestRectWithoutFocus)
}
