package com.trae.androidlyrics

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「内部 / 居中 / 外部」描边位置分段控件。
 *
 * 歌词颜色设置页的两个模块与桌面歌词悬浮面板共用同一个控件，
 * 面板背景固定为深色，调用 [usePanelStyle] 切成浅色文字 + 蓝色高亮，
 * 避免浅色主题下用主题色导致对比度不足。
 */
class OutlinePositionGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private data class Option(val position: Int, val labelRes: Int)

    private val options = listOf(
        Option(Prefs.OUTLINE_POSITION_INSIDE, R.string.outline_position_inside),
        Option(Prefs.OUTLINE_POSITION_CENTER, R.string.outline_position_center),
        Option(Prefs.OUTLINE_POSITION_OUTSIDE, R.string.outline_position_outside),
    )
    private val buttons = mutableListOf<TextView>()
    private var current = Prefs.DEFAULT_OUTLINE_POSITION
    private var panelStyle = false

    /** 用户点击分段时回调；[setPosition] 程序化同步不会触发。 */
    var onPositionChanged: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        options.forEach { opt ->
            val b = TextView(context).apply {
                text = context.getString(opt.labelRes)
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(4), dp(6), dp(4), dp(6))
                setOnClickListener {
                    if (current != opt.position) {
                        current = opt.position
                        updateButtons()
                        onPositionChanged?.invoke(opt.position)
                    }
                }
            }
            buttons += b
            addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        updateButtons()
    }

    /** 切换到桌面歌词悬浮面板的深色配色。 */
    fun usePanelStyle() {
        panelStyle = true
        updateButtons()
    }

    /** 程序化同步选中项（页面 onResume / 面板创建时），不触发回调。 */
    fun setPosition(value: Int) {
        val safe = value.coerceIn(
            Prefs.OUTLINE_POSITION_INSIDE, Prefs.OUTLINE_POSITION_OUTSIDE
        )
        if (current == safe) return
        current = safe
        updateButtons()
    }

    private fun updateButtons() {
        val accent = if (panelStyle) {
            0xFF66CCFF.toInt()
        } else {
            themeColor(com.google.android.material.R.attr.colorPrimary)
        }
        val normal = if (panelStyle) {
            0xB0FFFFFF.toInt()
        } else {
            themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        val selectedBg = if (panelStyle) {
            0x3366CCFF
        } else {
            (accent and 0x00FFFFFF) or (0x28 shl 24)
        }
        options.forEachIndexed { i, opt ->
            val b = buttons[i]
            val selected = opt.position == current
            b.setTextColor(if (selected) accent else normal)
            b.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            b.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (selected) selectedBg else 0)
            }
        }
    }

    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return 0xFF888888.toInt()
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
