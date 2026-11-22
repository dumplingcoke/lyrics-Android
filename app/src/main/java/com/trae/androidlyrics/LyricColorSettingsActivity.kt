package com.trae.androidlyrics

import android.os.Bundle
import android.view.Choreographer
import android.widget.SeekBar
import android.widget.TextView
import com.trae.androidlyrics.databinding.ActivityLyricColorSettingsBinding

/**
 * 歌词颜色设置：按「主界面歌词」「桌面歌词」两个模块分别配置全部文字外观。
 *
 * 两个模块各自持有：文字颜色、已唱颜色、文字不透明度、描边开关/颜色/粗细/位置。
 * 已唱颜色是两项独立偏好（[Prefs.mainSungTextColor] / [Prefs.desktopSungTextColor]）；
 * 桌面模块的文字/描边颜色、不透明度与桌面歌词悬浮面板共用同一份偏好，
 * 面板每次弹出会重新读取，回到本页时也会重新同步。
 *
 * 页面里有 6 个动态构建子 View 的 [ColorPickerView]，沿用设置页的分帧初始化，
 * 每帧只构建一个，避免进入页面时出现长帧卡顿。
 */
class LyricColorSettingsActivity : ThemedActivity() {

    private lateinit var prefs: Prefs
    private lateinit var binding: ActivityLyricColorSettingsBinding

    /** 待执行的初始化步骤，每帧执行一个。 */
    private val initSteps = mutableListOf<() -> Unit>()
    private var fullyInitialized = false
    private var pickersReady = false

    private data class PickerSpec(
        val view: ColorPickerView,
        val color: Int,
        val onChange: (Int) -> Unit,
    )

    private val pendingPickers = mutableListOf<PickerSpec>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityLyricColorSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        scheduleInit()
    }

    private fun scheduleInit() {
        initSteps.clear()
        fullyInitialized = false
        pickersReady = false
        pendingPickers.clear()
        initSteps += ::setupMainSection
        initSteps += ::setupDesktopSection
        initSteps += ::setupColorPickers
        initSteps += {
            fullyInitialized = true
            syncFromPrefs()
        }
        runNextInitStep()
    }

    private fun runNextInitStep() {
        if (isFinishing || isDestroyed || initSteps.isEmpty()) return
        initSteps.removeAt(0).invoke()
        Choreographer.getInstance().postFrameCallback { runNextInitStep() }
    }

    private fun deferColorPicker(view: ColorPickerView, color: Int, onChange: (Int) -> Unit) {
        pendingPickers += PickerSpec(view, color, onChange)
    }

    /** ColorPickerView 会动态建几十个 View，延后且每帧只创建一个。 */
    private fun setupColorPickers() {
        if (pendingPickers.isEmpty()) {
            pickersReady = true
            return
        }
        val next = pendingPickers.removeAt(0)
        next.view.configure(
            initialColor = next.color,
            collapsibleSliders = false,
            onColorChanged = next.onChange,
        )
        if (pendingPickers.isNotEmpty()) {
            // 插到收尾步骤之前，保证所有选择器完成后才做整体状态同步。
            initSteps.add(0, ::setupColorPickers)
        } else {
            pickersReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        // 桌面歌词悬浮面板可能改过同一份文字/描边/描边位置设置，回来后重新对齐。
        if (fullyInitialized) syncFromPrefs()
    }

    /** 把当前偏好同步到所有控件（不触发写入回调）。 */
    private fun syncFromPrefs() {
        if (!pickersReady) return
        binding.mainTextColorPicker.setCurrentColor(prefs.mainTextColor)
        binding.mainSungColorPicker.setCurrentColor(prefs.mainSungTextColor)
        binding.mainOutlineColorPicker.setCurrentColor(prefs.mainOutlineColor)
        binding.textColorPicker.setCurrentColor(prefs.desktopTextColor)
        binding.desktopSungColorPicker.setCurrentColor(prefs.desktopSungTextColor)
        binding.outlineColorPicker.setCurrentColor(prefs.desktopOutlineColor)

        binding.mainOutlineSwitch.isChecked = prefs.mainOutlineEnabled
        binding.desktopOutlineSwitch.isChecked = prefs.desktopOutlineEnabled

        syncAlpha(binding.mainTextAlphaBar, binding.mainTextAlphaValue, prefs.mainTextAlpha)
        syncAlpha(binding.textAlphaBar, binding.textAlphaValue, prefs.desktopTextAlpha)

        syncWidth(binding.mainOutlineWidthBar, binding.mainOutlineWidthValue, prefs.mainOutlineWidth)
        syncWidth(binding.outlineWidthBar, binding.outlineWidthValue, prefs.desktopOutlineWidth)

        binding.mainOutlinePositionGroup.setPosition(prefs.mainOutlinePosition)
        binding.desktopOutlinePositionGroup.setPosition(prefs.desktopOutlinePosition)
    }

    private fun syncAlpha(bar: SeekBar, value: TextView, percent: Int) {
        bar.progress = percent
        value.text = getString(R.string.alpha_percent, percent)
    }

    private fun syncWidth(bar: SeekBar, value: TextView, width: Int) {
        val safe = width.coerceIn(Prefs.MIN_OUTLINE_WIDTH, Prefs.MAX_OUTLINE_WIDTH)
        bar.progress = safe
        value.text = getString(R.string.outline_width_value, safe)
    }

    // ---------------------------------------------------------------- 主界面歌词

    private fun setupMainSection() {
        deferColorPicker(binding.mainTextColorPicker, prefs.mainTextColor) { color ->
            prefs.mainTextColor = color
        }
        // 主界面的已唱颜色只写主界面偏好，不影响桌面歌词。
        deferColorPicker(binding.mainSungColorPicker, prefs.mainSungTextColor) { color ->
            prefs.mainSungTextColor = color
        }
        deferColorPicker(binding.mainOutlineColorPicker, prefs.mainOutlineColor) { color ->
            prefs.mainOutlineColor = color
        }

        binding.mainOutlineSwitch.isChecked = prefs.mainOutlineEnabled
        binding.mainOutlineSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.mainOutlineEnabled = checked
        }

        setupAlphaControl(
            binding.mainTextAlphaBar, binding.mainTextAlphaValue, binding.btnResetMainTextAlpha,
            read = { prefs.mainTextAlpha },
            write = { prefs.mainTextAlpha = it },
        )
        setupWidthControl(
            binding.mainOutlineWidthBar, binding.mainOutlineWidthValue,
            read = { prefs.mainOutlineWidth },
            write = { prefs.mainOutlineWidth = it },
        )
        binding.mainOutlinePositionGroup.setPosition(prefs.mainOutlinePosition)
        binding.mainOutlinePositionGroup.onPositionChanged = { position ->
            prefs.mainOutlinePosition = position
        }
    }

    // ---------------------------------------------------------------- 桌面歌词

    private fun setupDesktopSection() {
        deferColorPicker(binding.textColorPicker, prefs.desktopTextColor) { color ->
            prefs.desktopTextColor = color
        }
        deferColorPicker(binding.desktopSungColorPicker, prefs.desktopSungTextColor) { color ->
            prefs.desktopSungTextColor = color
        }
        deferColorPicker(binding.outlineColorPicker, prefs.desktopOutlineColor) { color ->
            prefs.desktopOutlineColor = color
        }

        binding.desktopOutlineSwitch.isChecked = prefs.desktopOutlineEnabled
        binding.desktopOutlineSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopOutlineEnabled = checked
        }

        setupAlphaControl(
            binding.textAlphaBar, binding.textAlphaValue, binding.btnResetTextAlpha,
            read = { prefs.desktopTextAlpha },
            write = { prefs.desktopTextAlpha = it },
        )
        setupWidthControl(
            binding.outlineWidthBar, binding.outlineWidthValue,
            read = { prefs.desktopOutlineWidth },
            write = { prefs.desktopOutlineWidth = it },
        )
        binding.desktopOutlinePositionGroup.setPosition(prefs.desktopOutlinePosition)
        binding.desktopOutlinePositionGroup.onPositionChanged = { position ->
            prefs.desktopOutlinePosition = position
        }
    }

    // ---------------------------------------------------------------- 复用控件

    /** 文字不透明度滑块 + 数值 + 恢复默认；两个模块各一份，读写回调区分偏好。 */
    private fun setupAlphaControl(
        bar: SeekBar,
        value: TextView,
        reset: TextView,
        read: () -> Int,
        write: (Int) -> Unit,
    ) {
        bar.progress = read()
        value.text = getString(R.string.alpha_percent, read())
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                write(progress)
                value.text = getString(R.string.alpha_percent, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        reset.setOnClickListener {
            write(Prefs.DEFAULT_DESKTOP_TEXT_ALPHA)
            bar.progress = read()
            value.text = getString(R.string.alpha_percent, read())
            UiTips.show(this@LyricColorSettingsActivity, R.string.text_alpha_reset_done)
        }
    }

    /** 描边粗细滑块 + 数值；两个模块各一份。 */
    private fun setupWidthControl(
        bar: SeekBar,
        value: TextView,
        read: () -> Int,
        write: (Int) -> Unit,
    ) {
        val initial = read().coerceIn(Prefs.MIN_OUTLINE_WIDTH, Prefs.MAX_OUTLINE_WIDTH)
        bar.progress = initial
        value.text = getString(R.string.outline_width_value, initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val safe = progress.coerceIn(Prefs.MIN_OUTLINE_WIDTH, Prefs.MAX_OUTLINE_WIDTH)
                write(safe)
                value.text = getString(R.string.outline_width_value, safe)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
