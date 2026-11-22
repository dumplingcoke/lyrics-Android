package com.trae.androidlyrics

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.trae.androidlyrics.databinding.ActivityHomeLyricsSettingsBinding

/**
 * 「主页歌词设置」二级页面。
 *
 * 由主页右上角「更多选项」的「主页歌词设置」入口进入，承载主页歌词的全部选项：
 * 显示模式、正在播放的高亮行位置、允许上下空白、歌词对齐、播放控制条、
 * 控制条背景 / 暂停按钮背景 / 上边缘柔化、横屏完全展开位置（上方 / 居中 / 下方）、
 * 保持亮屏、原文/译文字号、字重、行间距、文字预览，以及动效总开关
 * 「减少动效」与主页歌词动效（缓动滚动、逐行错位、行间缩放、远景模糊）。
 *
 * 「歌词对齐」与「减少动效」由原「应用外观」页迁入，本页现在是主页歌词相关设置的
 * 唯一入口；「应用外观」页只保留主题与配色。两者写入的仍是 [AppearancePrefs] 里
 * 原有的键，无需数据迁移。
 *
 * 所有写入都落到 [AppearancePrefs]；[MainActivity.applyHomeAppearance] 在返回前台时
 * 统一重新套用，因此这里的修改即时生效。
 */
class HomeLyricsSettingsActivity : ThemedActivity() {

    private lateinit var appearance: AppearancePrefs
    private lateinit var prefs: Prefs
    private lateinit var binding: ActivityHomeLyricsSettingsBinding
    private var suppressCallbacks = false

    /** 分段按钮未选中时的默认文字色：自定义配色下取消选中后要还原，避免残留强调色。 */
    private var defaultToggleTextColor: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = AppearancePrefs(this)
        prefs = Prefs(this)
        binding = ActivityHomeLyricsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.home_lyrics_settings_title)
        binding.btnSettingsSearch.setOnClickListener {
            startActivity(Intent(this, SettingsSearchActivity::class.java))
        }

        setupHome()
        setupHighlightRow()
        setupHomeLyricsPrefs()
        setupAlign()
        setupMotion()
        updatePreview()
        applyAccentRuntime()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_settings_search, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings_search) {
            startActivity(Intent(this, SettingsSearchActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------- 主页歌词

    private fun setupHome() {
        suppressCallbacks = true
        binding.displayModeGroup.check(
            if (appearance.homeDisplayMode == AppearancePrefs.MODE_POP) R.id.modePop else R.id.modeScroll
        )
        suppressCallbacks = false

        binding.displayModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            appearance.homeDisplayMode = if (checkedId == R.id.modePop)
                AppearancePrefs.MODE_POP else AppearancePrefs.MODE_SCROLL
        }

        bindSwitch(binding.mediaControlsSwitch, appearance.showMediaControls) {
            appearance.showMediaControls = it
        }
        // 「底部播放控制条背景」为正向开关：开启显示卡片底色，默认关闭＝透明。
        bindSwitch(binding.mediaControlsBgSwitch, appearance.mediaControlsBackground) {
            appearance.mediaControlsBackground = it
        }
        bindSwitch(binding.mediaControlsPlayBgSwitch, appearance.mediaControlsPlayBackground) {
            appearance.mediaControlsPlayBackground = it
        }
        bindSwitch(binding.mediaControlsSoftEdgeSwitch, appearance.mediaControlsSoftTopEdge) {
            appearance.mediaControlsSoftTopEdge = it
        }
        // 横屏完全展开后的位置：上方 / 居中（默认）/ 下方
        suppressCallbacks = true
        binding.mediaExpandedPosGroup.check(
            when (appearance.mediaControlsExpandedPosition) {
                AppearancePrefs.EXPANDED_POS_TOP -> R.id.mediaExpandedPosTop
                AppearancePrefs.EXPANDED_POS_BOTTOM -> R.id.mediaExpandedPosBottom
                else -> R.id.mediaExpandedPosCenter
            }
        )
        suppressCallbacks = false
        binding.mediaExpandedPosGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            appearance.mediaControlsExpandedPosition = when (checkedId) {
                R.id.mediaExpandedPosTop -> AppearancePrefs.EXPANDED_POS_TOP
                R.id.mediaExpandedPosBottom -> AppearancePrefs.EXPANDED_POS_BOTTOM
                else -> AppearancePrefs.EXPANDED_POS_CENTER
            }
            applyAccentRuntime()
        }
        bindSwitch(binding.keepAwakeSwitch, appearance.keepAwake) { appearance.keepAwake = it }

        bindSeekBar(binding.homeFontSizeBar, binding.homeFontSizeValue,
            appearance.homeFontSize, "sp") { appearance.homeFontSize = it; updatePreview() }
        // 高亮行放大：开关 + 放大数值（sp），关闭后所有行使用基准字号
        bindSwitch(binding.highlightEnlargeSwitch, appearance.highlightEnlarge) {
            appearance.highlightEnlarge = it
            updateHighlightEnlargeEnabled()
            updatePreview()
        }
        bindSeekBar(binding.highlightEnlargeBar, binding.highlightEnlargeValue,
            appearance.highlightEnlargeSp, "sp") {
            appearance.highlightEnlargeSp = it
            updatePreview()
        }
        updateHighlightEnlargeEnabled()
        bindSeekBar(binding.homeTranslationSizeBar, binding.homeTranslationSizeValue,
            appearance.homeTranslationSize, "sp") {
            appearance.homeTranslationSize = it; updatePreview()
        }
        // 字重滑块 3~8 代表 300~800
        bindSeekBar(binding.homeFontWeightBar, binding.homeFontWeightValue,
            appearance.homeFontWeight / 100, "") { v ->
            appearance.homeFontWeight = v * 100
            binding.homeFontWeightValue.text = weightLabel(v * 100)
            updatePreview()
        }
        binding.homeFontWeightValue.text = weightLabel(appearance.homeFontWeight)
        // 行距滑块 0~14 代表 0~28dp（步长 2）
        bindSeekBar(binding.homeLineSpacingBar, binding.homeLineSpacingValue,
            appearance.homeLineSpacing / 2, "") { v ->
            appearance.homeLineSpacing = v * 2
            binding.homeLineSpacingValue.text = "${v * 2}dp"
        }
        binding.homeLineSpacingValue.text = "${appearance.homeLineSpacing}dp"
    }

    // ---------------------------------------------------------------- 高亮行位置 / 上下空白

    /**
     * 「正在播放的高亮行位置」以列表居中为 0，负值向上、正值向下，单位为歌词行。
     * 默认 -1（比原先的居中默认位置向上高一行）。
     *
     * 「允许上下空白」默认开启：主页按高亮行位置给歌词列表补上下内边距，
     * 于是第一行歌词可以从高亮行位置开始、最后一行歌词可以在高亮行位置结束；
     * 关闭后不补空白，歌词紧贴列表上下边缘。
     */
    private fun setupHighlightRow() {
        val min = AppearancePrefs.HIGHLIGHT_ROW_OFFSET_MIN
        val max = AppearancePrefs.HIGHLIGHT_ROW_OFFSET_MAX
        // progress 0..(max-min) 映射 min..max，避开 minSdk 24 上 android:min 不生效的问题
        binding.highlightRowBar.max = max - min
        suppressCallbacks = true
        binding.highlightRowBar.progress = (appearance.highlightRowOffset - min).coerceIn(0, max - min)
        suppressCallbacks = false
        binding.highlightRowValue.text = highlightRowLabel(appearance.highlightRowOffset)
        binding.highlightRowBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (min + progress).coerceIn(min, max)
                binding.highlightRowValue.text = highlightRowLabel(value)
                if (!suppressCallbacks) appearance.highlightRowOffset = value
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        bindSwitch(binding.allowVerticalBlankSwitch, appearance.allowVerticalBlank) {
            appearance.allowVerticalBlank = it
        }
    }

    private fun highlightRowLabel(offset: Int): String = when {
        offset == 0 -> getString(R.string.ap_highlight_row_center)
        offset < 0 -> getString(R.string.ap_highlight_row_up, -offset)
        else -> getString(R.string.ap_highlight_row_down, offset)
    }

    // ---------------------------------------------------------------- 主页歌词专属偏好

    /**
     * 「滚动动画」与「双语歌词高亮行」由「设置」页迁入。二者只影响主页歌词
     * （[MainActivity] 的滚动动画与 [LyricAdapter] 的双语高亮），写入的仍是
     * [Prefs] 原有键，桌面歌词继续用各自的 desktop* 选项。
     */
    private fun setupHomeLyricsPrefs() {
        suppressCallbacks = true
        binding.animGroup.check(
            when (prefs.scrollAnimation) {
                Prefs.ANIM_NONE -> R.id.animNone
                Prefs.ANIM_FADE -> R.id.animFade
                else -> R.id.animSmooth
            }
        )
        binding.bilingualGroup.check(
            when (prefs.bilingualHighlight) {
                Prefs.BILINGUAL_FIRST -> R.id.bilingualFirst
                Prefs.BILINGUAL_SECOND -> R.id.bilingualSecond
                else -> R.id.bilingualBoth
            }
        )
        suppressCallbacks = false

        binding.animGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            prefs.scrollAnimation = when (checkedId) {
                R.id.animNone -> Prefs.ANIM_NONE
                R.id.animFade -> Prefs.ANIM_FADE
                else -> Prefs.ANIM_SMOOTH
            }
        }
        binding.bilingualGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            prefs.bilingualHighlight = when (checkedId) {
                R.id.bilingualFirst -> Prefs.BILINGUAL_FIRST
                R.id.bilingualSecond -> Prefs.BILINGUAL_SECOND
                else -> Prefs.BILINGUAL_BOTH
            }
        }
    }

    // ---------------------------------------------------------------- 歌词对齐

    /** 「歌词对齐」由「应用外观」迁入：写入 homeAlign 后立刻刷新本页文字预览。 */
    private fun setupAlign() {
        suppressCallbacks = true
        binding.homeAlignGroup.check(
            when (appearance.homeAlign) {
                Prefs.ALIGN_LEFT -> R.id.homeAlignLeft
                Prefs.ALIGN_RIGHT -> R.id.homeAlignRight
                else -> R.id.homeAlignCenter
            }
        )
        suppressCallbacks = false
        binding.homeAlignGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            appearance.homeAlign = when (checkedId) {
                R.id.homeAlignLeft -> Prefs.ALIGN_LEFT
                R.id.homeAlignRight -> Prefs.ALIGN_RIGHT
                else -> Prefs.ALIGN_CENTER
            }
            updatePreview()
            applyAccentRuntime()
        }
    }

    private fun weightLabel(weight: Int): String = getString(
        when (weight) {
            300 -> R.string.ap_weight_light
            400 -> R.string.ap_weight_normal
            500 -> R.string.ap_weight_medium
            600 -> R.string.ap_weight_semibold
            700 -> R.string.ap_weight_bold
            else -> R.string.ap_weight_heavy
        }
    )

    // ---------------------------------------------------------------- 动效

    private fun setupMotion() {
        // 总开关由「应用外观」迁入：开启后主页所有动效统一停用，本页子项置灰。
        bindSwitch(binding.reduceMotionSwitch, appearance.reduceMotion) {
            appearance.reduceMotion = it
            updateMotionEnabledState()
        }
        bindSwitch(binding.easedScrollSwitch, appearance.easedScroll) { appearance.easedScroll = it }
        bindSwitch(binding.staggerSwitch, appearance.stagger) { appearance.stagger = it }
        bindSwitch(binding.scaleSwitch, appearance.scale) { appearance.scale = it }
        bindSwitch(binding.depthBlurSwitch, appearance.depthBlur) { appearance.depthBlur = it }

        bindSeekBar(binding.scrollDurationBar, binding.scrollDurationValue,
            appearance.scrollDurationMs, "ms") { appearance.scrollDurationMs = it }
        bindSeekBar(binding.staggerDelayBar, binding.staggerDelayValue,
            appearance.staggerDelayMs, "ms") { appearance.staggerDelayMs = it }
        bindSeekBar(binding.scalePercentBar, binding.scalePercentValue,
            appearance.scalePercent, "%") { appearance.scalePercent = it }
        bindSeekBar(binding.blurPercentBar, binding.blurPercentValue,
            appearance.blurPercent, "%") { appearance.blurPercent = it }

        updateMotionEnabledState()
    }

    /** 减少动效时子项置灰但保留已保存的取值，关闭总开关后原样恢复。 */
    private fun updateMotionEnabledState() {
        val enabled = appearance.motionEnabled
        listOf<View>(
            binding.easedScrollSwitch, binding.staggerSwitch,
            binding.scaleSwitch, binding.depthBlurSwitch,
            binding.scrollDurationBar, binding.staggerDelayBar,
            binding.scalePercentBar, binding.blurPercentBar,
        ).forEach { it.isEnabled = enabled }
    }

    // ---------------------------------------------------------------- 预览

    /** 关闭「高亮行放大」时置灰数值控件，保留已保存的取值以便重新开启后恢复。 */
    private fun updateHighlightEnlargeEnabled() {
        val enabled = appearance.highlightEnlarge
        binding.highlightEnlargeBar.isEnabled = enabled
        binding.highlightEnlargeValue.alpha = if (enabled) 1f else 0.4f
        binding.highlightEnlargeLabel.alpha = if (enabled) 1f else 0.4f
    }

    private fun updatePreview() {
        // 预览按「当前高亮行」的字号渲染，直观体现放大开关与数值
        val extra = if (appearance.highlightEnlarge) appearance.highlightEnlargeSp else 0
        binding.previewOriginal.textSize = (appearance.homeFontSize + extra).toFloat()
        binding.previewOriginal.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            weightToStyle(appearance.homeFontWeight)
        )
        binding.previewOriginal.gravity = when (appearance.homeAlign) {
            Prefs.ALIGN_LEFT -> Gravity.START
            Prefs.ALIGN_RIGHT -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        binding.previewTranslation.textSize = appearance.homeTranslationSize.toFloat()
        binding.previewTranslation.gravity = binding.previewOriginal.gravity
        binding.previewTranslation.setLineSpacing(appearance.homeLineSpacing.toFloat(), 1f)
    }

    /** 自定义主调色是运行时值，静态 style 表达不了，这里给开关与分段按钮着色。 */
    private fun applyAccentRuntime() {
        if (appearance.colorPreset != AppearancePrefs.PRESET_CUSTOM) return
        val color = appearance.accentColor
        val csl = ColorStateList.valueOf(color)
        listOf(
            binding.mediaControlsSwitch, binding.mediaControlsBgSwitch,
            binding.mediaControlsPlayBgSwitch, binding.mediaControlsSoftEdgeSwitch,
            binding.keepAwakeSwitch, binding.allowVerticalBlankSwitch,
            binding.highlightEnlargeSwitch,
            binding.reduceMotionSwitch, binding.easedScrollSwitch,
            binding.staggerSwitch, binding.scaleSwitch, binding.depthBlurSwitch
        ).forEach { it.thumbTintList = csl; it.trackTintList = csl }
        listOf(
            binding.displayModeGroup, binding.homeAlignGroup,
            binding.animGroup, binding.bilingualGroup,
            binding.mediaExpandedPosGroup,
        ).forEach { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child !is com.google.android.material.button.MaterialButton) continue
                // 首次调用时按钮尚未被着色，取到的就是 XML 里的默认文字色。
                if (defaultToggleTextColor == null) {
                    defaultToggleTextColor = child.textColors.defaultColor
                }
                child.setTextColor(
                    if (child.isChecked) color else defaultToggleTextColor ?: color
                )
            }
        }
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

    private fun bindSeekBar(
        bar: SeekBar,
        label: TextView,
        initial: Int,
        unit: String,
        onChange: (Int) -> Unit,
    ) {
        suppressCallbacks = true
        bar.progress = initial.coerceIn(0, bar.max)
        if (unit.isNotEmpty()) label.text = "$initial$unit"
        suppressCallbacks = false
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (unit.isNotEmpty()) label.text = "$progress$unit"
                if (!suppressCallbacks) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun weightToStyle(weight: Int): Int = when {
        weight >= 700 -> android.graphics.Typeface.BOLD
        weight >= 500 -> android.graphics.Typeface.BOLD
        else -> android.graphics.Typeface.NORMAL
    }
}
