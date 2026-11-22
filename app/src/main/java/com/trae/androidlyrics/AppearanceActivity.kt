package com.trae.androidlyrics

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivityAppearanceBinding
import com.trae.androidlyrics.databinding.DialogCustomColorBinding

/**
 * 「应用外观」页。
 *
 * 只保留主题与颜色（主题模式 / AMOLED / 动态颜色 / 8 套配色预设 / 自定义主调色）。
 * 主页歌词排版、字号、动效已在 [HomeLyricsSettingsActivity]，封面模糊背景在
 * [SettingsActivity]；「减少动效」与「歌词对齐」也已迁入 [HomeLyricsSettingsActivity]。
 *
 * 主题类变更必须重建视图才能重新解析 ?attr，但重建本身较重：这里把
 * AMOLED / 动态颜色 / 配色 / 自定义色的重建统一交给
 * [App.scheduleAppearanceRecreate] 做短时间窗合并，避免连点时排队多次 recreate。
 */
class AppearanceActivity : ThemedActivity() {

    private lateinit var appearance: AppearancePrefs
    private lateinit var prefs: Prefs
    private lateinit var binding: ActivityAppearanceBinding
    private var suppressCallbacks = false

    /** 分段按钮未选中时的默认文字色：自定义配色下切换主题模式后要还原旧按钮。 */
    private var defaultToggleTextColor: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = AppearancePrefs(this)
        prefs = Prefs(this)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.appearance_title)

        setupTheme()
        setupColors()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        // 页面已退出时不再执行挂起的视图重建（recreate 自身的 onDestroy 此时已是空任务）。
        App.cancelAppearanceRecreate()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 主题

    private fun setupTheme() {
        suppressCallbacks = true
        binding.themeGroup.check(
            when (prefs.themeMode) {
                Prefs.THEME_DARK -> R.id.themeDark
                Prefs.THEME_LIGHT -> R.id.themeLight
                else -> R.id.themeSystem
            }
        )
        suppressCallbacks = false
        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeDark -> Prefs.THEME_DARK
                R.id.themeLight -> Prefs.THEME_LIGHT
                else -> Prefs.THEME_SYSTEM
            }
            if (mode == prefs.themeMode) return@addOnButtonCheckedListener
            prefs.themeMode = mode
            appearance.themeMode = mode
            applyAccentRuntime()
            App.freezeForThemeSwitch(this)
            App.applyGlobalTheme(mode)
        }

        bindSwitch(binding.amoledSwitch, appearance.amoled) { value ->
            appearance.amoled = value
            App.scheduleAppearanceRecreate(this)
        }
        bindSwitch(binding.dynamicColorSwitch, appearance.dynamicColor) { value ->
            appearance.dynamicColor = value
            App.scheduleAppearanceRecreate(this)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            binding.dynamicColorSwitch.isEnabled = false
        }
    }

    // ---------------------------------------------------------------- 配色

    private fun setupColors() {
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        val gap = (10 * density).toInt()
        AppearancePrefs.PRESET_LABELS.forEach { (key, label) ->
            val dot = View(this).apply {
                tag = key
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                contentDescription = label
                setOnClickListener { selectPreset(key) }
            }
            binding.colorPresetRow.addView(dot)
        }
        val custom = View(this).apply {
            tag = AppearancePrefs.PRESET_CUSTOM
            layoutParams = LinearLayout.LayoutParams(size, size)
            contentDescription = getString(R.string.ap_custom_color)
            setOnClickListener { showCustomColorDialog() }
        }
        binding.colorPresetRow.addView(custom)

        binding.btnCustomColor.setOnClickListener { showCustomColorDialog() }
        refreshPresetSelection()
    }

    /**
     * 圆点只创建一次，选中变化只更新描边与文案。
     * 旧实现每次点击都 removeAllViews + 重建 9 个 View 再 recreate 整页，连点时主线程
     * 会堆积多次布局与重建，是切换配色卡顿的主要来源。
     */
    private fun refreshPresetSelection() {
        val current = appearance.colorPreset
        for (i in 0 until binding.colorPresetRow.childCount) {
            val dot = binding.colorPresetRow.getChildAt(i)
            val key = dot.tag as? String ?: continue
            val color = if (key == AppearancePrefs.PRESET_CUSTOM) appearance.accentColor
                else AppearancePrefs.COLOR_PRESETS.getValue(key)
            dot.background = presetDot(color, key == current)
        }
        binding.colorPresetValue.text =
            if (current == AppearancePrefs.PRESET_CUSTOM) {
                getString(R.string.ap_custom_color) + " · " + hex(appearance.accentColor)
            } else {
                AppearancePrefs.PRESET_LABELS[current] ?: getString(R.string.ap_custom_color)
            }
        applyAccentRuntime()
    }

    /** 配色 / 自定义色先落盘并即时反馈，视图重建交给合并调度，连点只重建一次。 */
    private fun selectPreset(key: String) {
        if (appearance.colorPreset == key) return
        appearance.colorPreset = key
        refreshPresetSelection()
        App.scheduleAppearanceRecreate(this)
    }

    private fun presetDot(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (selected) setStroke((3 * resources.displayMetrics.density).toInt(), 0xFFFFFFFF.toInt())
        }

    /** 自定义主调色无法用静态 style 表达，运行时给关键控件着色。 */
    private fun applyAccentRuntime() {
        if (appearance.colorPreset != AppearancePrefs.PRESET_CUSTOM) return
        val color = appearance.accentColor
        val csl = ColorStateList.valueOf(color)
        for (i in 0 until binding.themeGroup.childCount) {
            val child = binding.themeGroup.getChildAt(i)
            if (child !is com.google.android.material.button.MaterialButton) continue
            // 首次调用时按钮尚未着色，取到的就是 XML 里的默认文字色。
            if (defaultToggleTextColor == null) {
                defaultToggleTextColor = child.textColors.defaultColor
            }
            child.setTextColor(if (child.isChecked) color else defaultToggleTextColor ?: color)
        }
        listOf(binding.amoledSwitch, binding.dynamicColorSwitch)
            .forEach { it.thumbTintList = csl; it.trackTintList = csl }
    }

    // ---------------------------------------------------------------- 自定义颜色（HSV）

    /**
     * 自定义主调色弹窗：除 HEX 输入框外，增加色相 / 饱和度 / 明度三个滑块。
     * 三个滑块与 HEX 输入双向联动，实时更新颜色预览；点击「应用」才写入偏好。
     */
    private fun showCustomColorDialog() {
        val d = DialogCustomColorBinding.inflate(layoutInflater)
        val hsv = FloatArray(3)
        Color.colorToHSV(appearance.accentColor, hsv)
        var updating = false

        fun currentColor(): Int = Color.HSVToColor(hsv)

        fun syncLabels() {
            d.hueLabel.text = getString(R.string.ap_custom_color_hue) + "  ${hsv[0].toInt()}°"
            d.saturationLabel.text =
                getString(R.string.ap_custom_color_saturation) + "  ${(hsv[1] * 100).toInt()}%"
            d.valueLabel.text =
                getString(R.string.ap_custom_color_value) + "  ${(hsv[2] * 100).toInt()}%"
        }

        /** 由 HSV 刷新预览色块、三个标签与（可选）HEX 输入框。 */
        fun syncFromHsv(syncHex: Boolean) {
            val color = currentColor()
            d.colorPreview.setBackgroundColor(color)
            syncLabels()
            if (syncHex) {
                updating = true
                d.hexInput.setText(hex(color))
                d.hexInput.setSelection(d.hexInput.text?.length ?: 0)
                updating = false
            }
        }

        /** 由 HSV 反向写回三个滑块的位置（输入 HEX 时用）。 */
        fun syncBarsFromHsv() {
            updating = true
            d.hueBar.progress = hsv[0].toInt().coerceIn(0, 360)
            d.saturationBar.progress = (hsv[1] * 100).toInt().coerceIn(0, 100)
            d.valueBar.progress = (hsv[2] * 100).toInt().coerceIn(0, 100)
            updating = false
        }

        syncBarsFromHsv()
        syncFromHsv(syncHex = true)

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating) return
                hsv[0] = d.hueBar.progress.toFloat()
                hsv[1] = d.saturationBar.progress / 100f
                hsv[2] = d.valueBar.progress / 100f
                syncFromHsv(syncHex = true)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        d.hueBar.setOnSeekBarChangeListener(seekListener)
        d.saturationBar.setOnSeekBarChangeListener(seekListener)
        d.valueBar.setOnSeekBarChangeListener(seekListener)

        d.hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val parsed = runCatching {
                    Color.parseColor(s?.toString()?.trim().orEmpty())
                }.getOrNull() ?: return
                Color.colorToHSV(parsed, hsv)
                syncBarsFromHsv()
                syncFromHsv(syncHex = false)
            }
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ap_custom_color)
            .setMessage(R.string.ap_custom_color_hint)
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.ap_dialog_apply) { _, _ ->
                appearance.accentColor = currentColor()
                appearance.colorPreset = AppearancePrefs.PRESET_CUSTOM
                refreshPresetSelection()
                App.scheduleAppearanceRecreate(this)
            }
            .show()
    }

    // ---------------------------------------------------------------- 工具

    private fun bindSwitch(
        switch: com.google.android.material.materialswitch.MaterialSwitch,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        suppressCallbacks = true
        switch.isChecked = initial
        suppressCallbacks = false
        switch.setOnCheckedChangeListener { _, checked ->
            if (!suppressCallbacks) onChange(checked)
        }
    }

    private fun hex(color: Int): String =
        "#" + String.format(java.util.Locale.getDefault(), "%06X", color and 0xFFFFFF)
}
