package com.trae.androidlyrics

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 可复用的颜色选择组件：预设色卡 + 自定义色相/饱和度/明度拖动条。
 *
 * 桌面歌词面板与设置界面共用，保证两处色卡数量、顺序与交互完全一致。
 * 文字颜色与描边颜色各自持有独立实例，互不影响。
 *
 * 预设色卡固定为旧版本的 6 种颜色（见 [Prefs.DESKTOP_COLOR_PALETTE]）：
 * 其他颜色通过拖动横条调节色相（H）、饱和度（S）、明度（V）获得。
 */
class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 颜色变化回调。 */
    private var onColorChanged: ((Int) -> Unit)? = null

    /** 预设色卡圆点及其颜色。 */
    private val presetDots = mutableListOf<Pair<View, Int>>()

    private var hueBar: SeekBar? = null
    private var satBar: SeekBar? = null
    private var valBar: SeekBar? = null
    private var hueValue: TextView? = null
    private var satValue: TextView? = null
    private var valValue: TextView? = null
    private var sliderPanel: LinearLayout? = null
    private var toggleCustom: TextView? = null

    /** 当前颜色（HSV）。 */
    private val hsv = floatArrayOf(0f, 0f, 1f)

    /** 当前颜色（ARGB）。 */
    private var currentColor: Int = Color.WHITE

    /** 程序化更新滑块时抑制回调，避免递归触发。 */
    private var updating = false

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * 配置组件。
     *
     * @param initialColor 初始颜色
     * @param collapsibleSliders 是否将自定义滑块折叠（面板空间有限时使用）
     * @param onColorChanged 颜色变化回调
     */
    fun configure(
        initialColor: Int,
        collapsibleSliders: Boolean,
        onColorChanged: (Int) -> Unit,
    ) {
        this.onColorChanged = onColorChanged
        removeAllViews()
        presetDots.clear()

        buildPresetRow()
        if (collapsibleSliders) buildCollapsibleSliders() else buildSliders()
        setCurrentColor(initialColor, notify = false)
    }

    /** 以编程方式更新当前颜色（不触发回调）。 */
    fun setCurrentColor(color: Int, notify: Boolean = false) {
        val argb = color or 0xFF000000.toInt()
        currentColor = argb
        Color.colorToHSV(argb, hsv)
        updating = true
        hueBar?.progress = hsv[0].toInt().coerceIn(0, 360)
        satBar?.progress = (hsv[1] * 100).toInt().coerceIn(0, 100)
        valBar?.progress = (hsv[2] * 100).toInt().coerceIn(0, 100)
        updateSliderLabels()
        updating = false
        refreshDotHighlight()
        if (notify) onColorChanged?.invoke(argb)
    }

    // ---------------------------------------------------------------- 预设色卡

    private fun buildPresetRow() {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val size = dp(22)
        val margin = dp(6)
        Prefs.DESKTOP_COLOR_PALETTE.forEach { color ->
            val dot = View(context).apply {
                layoutParams = LayoutParams(size, size).apply {
                    marginEnd = margin
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { onPresetSelected(color) }
            }
            presetDots.add(dot to color)
            row.addView(dot)
        }
        addView(
            row,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    private fun onPresetSelected(color: Int) {
        setCurrentColor(color, notify = true)
        // 选中预设色后收起滑块（面板模式下），避免遮挡
        if (toggleCustom != null) {
            sliderPanel?.visibility = View.GONE
            toggleCustom?.text = getString(R.string.color_custom_expand)
        }
    }

    /** 选中圆点加描边高亮，其余仅保留细边框；按 View 身份判定，避免跨色卡值互相串扰。 */
    private fun refreshDotHighlight() {
        presetDots.forEach { (view, color) ->
            val selected = (color == currentColor)
            view.background = dotDrawable(color, selected)
        }
    }

    private fun dotDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) {
                setStroke(dp(2), 0xFF66CCFF.toInt())
            } else {
                setStroke(dp(1), 0x66FFFFFF)
            }
        }

    // ---------------------------------------------------------------- 自定义滑块

    private fun buildCollapsibleSliders() {
        toggleCustom = TextView(context).apply {
            text = getString(R.string.color_custom_expand)
            textSize = 12f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(6), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val panel = sliderPanel ?: return@setOnClickListener
                val show = panel.visibility != View.VISIBLE
                panel.visibility = if (show) View.VISIBLE else View.GONE
                text = getString(
                    if (show) R.string.color_custom_collapse else R.string.color_custom_expand
                )
            }
        }
        addView(toggleCustom)
        buildSliders()
        sliderPanel?.visibility = View.GONE
    }

    private fun buildSliders() {
        val panel = LinearLayout(context).apply { orientation = VERTICAL }
        sliderPanel = panel

        val hue = addSlider(panel, getString(R.string.color_hue), 0, 360) { p ->
            onCustomChanged(h = p.toFloat())
        }
        hueBar = hue.first
        hueValue = hue.second

        val sat = addSlider(panel, getString(R.string.color_saturation), 0, 100) { p ->
            onCustomChanged(s = p / 100f)
        }
        satBar = sat.first
        satValue = sat.second

        val value = addSlider(panel, getString(R.string.color_brightness), 0, 100) { p ->
            onCustomChanged(v = p / 100f)
        }
        valBar = value.first
        valValue = value.second

        addView(panel)
    }

    /**
     * 添加一行"标签 + 拖动条 + 数值"。
     * @return Pair(SeekBar, 数值 TextView)
     */
    private fun addSlider(
        parent: LinearLayout,
        label: String,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit,
    ): Pair<SeekBar, TextView> {
        // 紧凑排布：标签按两个汉字的实际宽度收敛、SeekBar 去掉平台默认留白，
        // 数值仍保持定宽，避免 0% / 100% 切换时滑块长度跳动。
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val name = TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            layoutParams = LayoutParams(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(2)
            }
        }
        val bar = SeekBar(context).apply {
            this.min = min
            this.max = max
            // 平台 SeekBar 左右各有约 16dp 内边距给 thumb 留位，是"文字离滑块很远"
            // 的主因；收到 12dp 后 thumb 在两端仍完整，轨道明显贴近标签与数值。
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (updating) return
                    onChange(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        val value = TextView(context).apply {
            textSize = 11f
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            gravity = Gravity.END
            layoutParams = LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(2)
            }
        }
        row.addView(name)
        row.addView(bar)
        row.addView(value)
        parent.addView(row)
        return bar to value
    }

    /** 自定义滑块变化：更新 HSV 并回调，同时清除预设高亮。 */
    private fun onCustomChanged(h: Float? = null, s: Float? = null, v: Float? = null) {
        if (updating) return
        h?.let { hsv[0] = it }
        s?.let { hsv[1] = it }
        v?.let { hsv[2] = it }
        currentColor = Color.HSVToColor(hsv)
        updateSliderLabels()
        refreshDotHighlight()
        onColorChanged?.invoke(currentColor)
    }

    private fun updateSliderLabels() {
        hueValue?.text = hsv[0].toInt().toString()
        satValue?.text = "${(hsv[1] * 100).toInt()}%"
        valValue?.text = "${(hsv[2] * 100).toInt()}%"
    }

    /** 从当前主题取文字颜色，浅色/深色模式与桌面面板都保持足够对比度。 */
    private fun themeColor(attr: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return 0xFF888888.toInt()
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun getString(resId: Int): String = context.getString(resId)
}
