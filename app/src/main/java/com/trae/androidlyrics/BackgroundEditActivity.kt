package com.trae.androidlyrics

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivityBackgroundEditBinding
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 修改背景图片的显示位置：预览区域按用户手机「整屏」的宽高比呈现，
 * 单指拖动、双指缩放，也可用滑块精确位移；可选「比例缩放」（默认）与「自由缩放」。
 *
 * 预览框对应播放界面的可见屏幕，框外一圈「界外」区域把超出屏幕的图片半透明画出来，
 * 方便判断放大/平移是否真的铺满；预览尺寸含状态栏与导航栏区域。
 */
class BackgroundEditActivity : ThemedActivity() {

    companion object {
        const val EXTRA_ID = "background_id"
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_ZOOM = 2

        /** 预览框四周留出的界外可视带，图片超出的部分半透明显示在这里。 */
        private const val PREVIEW_GUTTER_DP = 26f

        /** 位移滑块中点：progress 0~200 映射到归一化位移 -1~+1。 */
        private const val MOVE_SLIDER_CENTER = 100
    }

    private lateinit var prefs: Prefs
    private lateinit var binding: ActivityBackgroundEditBinding
    private var item: BackgroundStore.Item? = null

    private var freeScale = false
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var ghostAlpha = 10
    private var guideAvailable = false
    private var syncingSliders = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ghostAlpha = prefs.backgroundGhostAlpha
        item = BackgroundStore.find(prefs, intent.getStringExtra(EXTRA_ID))
        if (item == null) {
            finish()
            return
        }
        binding = ActivityBackgroundEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        item!!.let {
            scaleX = it.scaleX
            scaleY = it.scaleY
            offsetX = it.offsetX
            offsetY = it.offsetY
        }

        // 默认比例缩放：先 check 再挂监听，避免初始化回调
        binding.scaleModeGroup.check(R.id.btnScaleProportional)
        binding.scaleModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            freeScale = checkedId == R.id.btnScaleFree
            if (!freeScale) {
                // 从自由缩放切回时统一 x/y，去掉拉伸状态
                val uniform = (scaleX + scaleY) / 2f
                scaleX = uniform
                scaleY = uniform
                applyTransform()
            }
        }

        setupMoveSliders()
        setupPreview()
        setupGestures()
        applyPreviewOptions()

        binding.btnQuickActions.setOnClickListener { showQuickActions(it) }
        // 重置＝按比例缩放到上下边界（覆盖范围优先，左右可以溢出到界外）
        binding.btnReset.setOnClickListener { fitToEdges(byWidth = false, proportional = true) }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener {
            val target = item ?: return@setOnClickListener
            target.scaleX = scaleX
            target.scaleY = scaleY
            target.offsetX = offsetX
            target.offsetY = offsetY
            BackgroundStore.updateItem(prefs, target)
            UiTips.show(this, R.string.background_edit_saved)
            finish()
        }
    }

    /** 预览框按「整屏」宽高比摆放，让手势调整的效果与实际主界面一致。 */
    private fun setupPreview() {
        val metrics = resources.displayMetrics
        val gutterPx = (PREVIEW_GUTTER_DP * metrics.density).toInt()
        binding.previewContainer.post {
            // 整屏尺寸要等窗口 attach / insets 分发后再取，早取可能仍是不含系统栏的应用尺寸
            val (screenW, screenH) = fullScreenSize()
            val availW = binding.previewContainer.width - gutterPx * 2
            val availH = binding.previewContainer.height - gutterPx * 2
            if (availW <= 0 || availH <= 0) return@post
            val ratio = screenH.toFloat() / screenW.toFloat().coerceAtLeast(1f)
            var width = availW.toFloat()
            var height = width * ratio
            if (height > availH) {
                height = availH.toFloat()
                width = height / ratio
            }
            val w = width.toInt().coerceAtLeast(1)
            val h = height.toInt().coerceAtLeast(1)
            sizePreviewChild(binding.previewGhost, w, h)
            sizePreviewChild(binding.previewFrame, w, h)
            updateStatusBarGuide(screenH, h)
            // 等新尺寸真正 layout 完成再算归一化位移，避免用旧宽高导致预览错位
            binding.previewFrame.doOnLayout { applyTransform() }
        }
        // 解码尺寸只影响采样精度，与预览框比例无关，不必等上面那次 layout
        loadPreviewBitmap(metrics.widthPixels, metrics.heightPixels)
    }

    /** 虚线标出状态栏区域下边界：让「这部分也算进预览」看得见，不参与实际显示。 */
    private fun updateStatusBarGuide(screenH: Int, frameH: Int) {
        val guide = binding.statusBarGuide
        val density = resources.displayMetrics.density
        val insetsTop = ViewCompat.getRootWindowInsets(binding.root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
        val statusPx = if (insetsTop > 0) insetsTop else runCatching {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        guideAvailable = statusPx > 0 && screenH > 0 && frameH >= 8
        if (guideAvailable) {
            val lp = guide.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = (statusPx.toFloat() / screenH * frameH).toInt()
                .coerceIn(0, frameH - 2)
            guide.layoutParams = lp
            guide.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    Color.argb(150, 255, 255, 255),
                    6f * density,
                    4f * density
                )
            }
        }
        applyPreviewOptions()
    }

    /**
     * 主界面是 edge-to-edge：背景 ImageView 铺满整块屏幕，状态栏与导航栏下方同样有图。
     * 而 resources.displayMetrics.heightPixels 通常只是「应用窗口」高度（已扣掉系统栏），
     * 拿它算比例会让预览框比真实屏幕矮；图片刚贴到预览上下边界时，播放界面仍会空出
     * 上下两条（状态栏 / 导航栏区域）。这里统一取含系统栏的整屏高度。
     */
    private fun fullScreenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val appW = metrics.widthPixels
        val appH = metrics.heightPixels
        var w = appW
        var h = appH
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // currentWindowMetrics.bounds 含状态栏 / 导航栏等系统装饰区
            val bounds = windowManager.currentWindowMetrics.bounds
            w = maxOf(w, bounds.width())
            h = maxOf(h, bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val real = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            w = maxOf(w, real.widthPixels)
            h = maxOf(h, real.heightPixels)
        }
        // 少数 ROM 的 WindowMetrics 也不含系统栏：再用系统栏 insets 补一次
        if (w <= appW || h <= appH) {
            val bars = ViewCompat.getRootWindowInsets(binding.root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
            if (bars != null) {
                w = maxOf(w, appW + bars.left + bars.right)
                h = maxOf(h, appH + bars.top + bars.bottom)
            }
        }
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    private fun sizePreviewChild(view: View, width: Int, height: Int) {
        val lp = view.layoutParams as FrameLayout.LayoutParams
        lp.width = width
        lp.height = height
        lp.gravity = Gravity.CENTER
        view.layoutParams = lp
    }

    private fun loadPreviewBitmap(screenW: Int, screenH: Int) {
        val preview = binding.previewImage
        val id = item!!.id
        BackgroundStore.post {
            val bitmap = BackgroundStore.screenBitmap(this, id, screenW, screenH)
            preview.post {
                preview.setImageBitmap(bitmap)
                binding.previewGhost.setImageBitmap(bitmap)
                applyTransform()
            }
        }
    }

    // ---------------------------------------------------------------- 预览选项

    private fun alphaOf(percent: Int): Int =
        (percent.coerceIn(0, 100) * 255 / 100f).roundToInt()

    private fun previewBorder(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        setStroke(
            (resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1),
            Color.argb(170, 255, 255, 255)
        )
    }

    /** 界外不透明度、示例歌词、边界标记三个开关统一在这里落地。 */
    private fun applyPreviewOptions() {
        // 用 drawable alpha 而不是 View alpha：不生成离屏 layer，
        // 放大/平移后超出自身边界的部分照样能画到「界外」区域
        binding.previewGhost.imageAlpha = alphaOf(ghostAlpha)
        binding.previewLyrics.visibility =
            if (prefs.backgroundPreviewLyrics) View.VISIBLE else View.GONE
        val showBorder = prefs.backgroundPreviewBorder
        binding.previewFrame.foreground = if (showBorder) previewBorder() else null
        binding.statusBarGuide.visibility =
            if (showBorder && guideAvailable) View.VISIBLE else View.GONE
    }

    /** 快捷操作：缩放吸附、示例歌词、界外透明度、边界标记。 */
    private fun showQuickActions(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val labels = listOf(
            getString(R.string.background_fit_width),
            getString(R.string.background_fill_screen),
            getString(
                if (prefs.backgroundPreviewLyrics) R.string.background_preview_lyrics_off
                else R.string.background_preview_lyrics_on
            ),
            getString(R.string.background_ghost_alpha),
            getString(
                if (prefs.backgroundPreviewBorder) R.string.background_border_off
                else R.string.background_border_on
            ),
        )
        labels.forEachIndexed { index, label -> popup.menu.add(0, index, index, label) }
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> fitToEdges(byWidth = true, proportional = true)
                1 -> fitToEdges(byWidth = false, proportional = false)
                2 -> {
                    prefs.backgroundPreviewLyrics = !prefs.backgroundPreviewLyrics
                    applyPreviewOptions()
                }

                3 -> showGhostAlphaDialog()
                4 -> {
                    prefs.backgroundPreviewBorder = !prefs.backgroundPreviewBorder
                    applyPreviewOptions()
                }
            }
            true
        }
        popup.show()
    }

    private fun showGhostAlphaDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val valueText = TextView(this).apply {
            text = "$ghostAlpha%"
            textSize = 15f
            gravity = Gravity.CENTER
        }
        val bar = SeekBar(this).apply {
            max = 100
            progress = ghostAlpha
            setPadding(pad, 0, pad, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    ghostAlpha = progress
                    valueText.text = "$progress%"
                    applyPreviewOptions()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, (8 * density).toInt())
            addView(valueText)
            addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_ghost_alpha_title)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { prefs.backgroundGhostAlpha = ghostAlpha }
            .show()
    }

    /**
     * fitCenter 下图片按较短边完整显示，代码里用 scaleX/scaleY 放大。
     * 这里算出「刚好贴到左右 / 上下边界」所需的缩放值，供吸附与重置使用。
     */
    private fun edgeFitScales(): Pair<Float, Float> {
        val view = binding.previewImage
        val drawable = view.drawable
        if (drawable == null || view.width <= 0 || view.height <= 0) return 1f to 1f
        val bw = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val bh = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        val fit = minOf(vw / bw, vh / bh)
        val drawnW = (bw * fit).coerceAtLeast(1f)
        val drawnH = (bh * fit).coerceAtLeast(1f)
        return (vw / drawnW) to (vh / drawnH)
    }

    /** 吸附到边界：按比例时 x/y 取同一个值；非按比例时各自取对应轴的值（拉伸填满）。 */
    private fun fitToEdges(byWidth: Boolean, proportional: Boolean) {
        binding.scaleModeGroup.check(
            if (proportional) R.id.btnScaleProportional else R.id.btnScaleFree
        )
        val (fitW, fitH) = edgeFitScales()
        if (proportional) {
            val uniform = (if (byWidth) fitW else fitH)
                .coerceIn(BackgroundStore.MIN_SCALE, BackgroundStore.MAX_SCALE)
            scaleX = uniform
            scaleY = uniform
        } else {
            scaleX = fitW.coerceIn(BackgroundStore.MIN_SCALE, BackgroundStore.MAX_SCALE)
            scaleY = fitH.coerceIn(BackgroundStore.MIN_SCALE, BackgroundStore.MAX_SCALE)
        }
        // 同时贴住两侧边界必然居中，清掉之前的平移
        offsetX = 0f
        offsetY = 0f
        applyTransform()
    }

    // ---------------------------------------------------------------- 位移滑块

    private fun setupMoveSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || syncingSliders || sb == null) return
                val offset = (progress - MOVE_SLIDER_CENTER) / 100f
                when (sb.id) {
                    R.id.moveXBar -> offsetX = offset
                    R.id.moveYBar -> offsetY = offset
                    else -> return
                }
                applyTransform()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        binding.moveXBar.setOnSeekBarChangeListener(listener)
        binding.moveYBar.setOnSeekBarChangeListener(listener)
        syncMoveSliders()
    }

    /** 手势 / 快捷操作改动位移后把滑块拉回同步，避免 next 触摸时跳变。 */
    private fun syncMoveSliders() {
        syncingSliders = true
        binding.moveXBar.progress =
            ((offsetX * 100).roundToInt().coerceIn(-MOVE_SLIDER_CENTER, MOVE_SLIDER_CENTER)
                    + MOVE_SLIDER_CENTER)
        binding.moveYBar.progress =
            ((offsetY * 100).roundToInt().coerceIn(-MOVE_SLIDER_CENTER, MOVE_SLIDER_CENTER)
                    + MOVE_SLIDER_CENTER)
        binding.moveXValue.text = "${(offsetX * 100).roundToInt()}%"
        binding.moveYValue.text = "${(offsetY * 100).roundToInt()}%"
        syncingSliders = false
    }

    // ---------------------------------------------------------------- 变换与手势

    private fun applyTransform() {
        val view = binding.previewImage
        val tx = offsetX * view.width
        val ty = offsetY * view.height
        view.scaleX = scaleX
        view.scaleY = scaleY
        view.translationX = tx
        view.translationY = ty
        // 界外图层与框内图片完全同步，只是不透明度低；用框内图片的宽高换算保证像素级一致
        val ghost = binding.previewGhost
        ghost.scaleX = scaleX
        ghost.scaleY = scaleY
        ghost.translationX = tx
        ghost.translationY = ty
        syncMoveSliders()
    }

    /** 手势位移换算成相对容器尺寸的归一化值，便于在不同尺寸的预览/主界面复现。 */
    private fun applyPan(dx: Float, dy: Float) {
        val w = binding.previewImage.width.toFloat().coerceAtLeast(1f)
        val h = binding.previewImage.height.toFloat().coerceAtLeast(1f)
        offsetX += dx / w
        offsetY += dy / h
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        var mode = MODE_NONE
        var lastX = 0f
        var lastY = 0f
        var startDistance = 0f
        var startDx = 1f
        var startDy = 1f
        var startScaleX = 1f
        var startScaleY = 1f
        var centerX = 0f
        var centerY = 0f

        val listener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = MODE_DRAG
                    lastX = event.x
                    lastY = event.y
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        mode = MODE_ZOOM
                        startDistance = pointerDistance(event)
                        // 下限避免两指几乎水平/垂直时除以接近 0 的数导致缩放暴走
                        startDx = kotlin.math.abs(event.getX(0) - event.getX(1)).coerceAtLeast(48f)
                        startDy = kotlin.math.abs(event.getY(0) - event.getY(1)).coerceAtLeast(48f)
                        startScaleX = scaleX
                        startScaleY = scaleY
                        centerX = (event.getX(0) + event.getX(1)) / 2f
                        centerY = (event.getY(0) + event.getY(1)) / 2f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (mode == MODE_ZOOM && event.pointerCount >= 2) {
                        if (freeScale) {
                            val dx = kotlin.math.abs(event.getX(0) - event.getX(1)).coerceAtLeast(48f)
                            val dy = kotlin.math.abs(event.getY(0) - event.getY(1)).coerceAtLeast(48f)
                            scaleX = (startScaleX * dx / startDx)
                                .coerceIn(BackgroundStore.MIN_SCALE, BackgroundStore.MAX_SCALE)
                            scaleY = (startScaleY * dy / startDy)
                                .coerceIn(BackgroundStore.MIN_SCALE, BackgroundStore.MAX_SCALE)
                        } else {
                            val factor = pointerDistance(event) / startDistance.coerceAtLeast(1f)
                            val uniform = (startScaleX * factor)
                                .coerceIn(BackgroundStore.MIN_SCALE, BackgroundStore.MAX_SCALE)
                            scaleX = uniform
                            scaleY = uniform
                        }
                        val cx = (event.getX(0) + event.getX(1)) / 2f
                        val cy = (event.getY(0) + event.getY(1)) / 2f
                        applyPan(cx - centerX, cy - centerY)
                        centerX = cx
                        centerY = cy
                        applyTransform()
                    } else if (mode == MODE_DRAG && event.pointerCount == 1) {
                        applyPan(event.x - lastX, event.y - lastY)
                        lastX = event.x
                        lastY = event.y
                        applyTransform()
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        val remaining = if (event.actionIndex == 0) 1 else 0
                        lastX = event.getX(remaining)
                        lastY = event.getY(remaining)
                        mode = MODE_DRAG
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = MODE_NONE
            }
            true
        }
        // 必须同时挂在预览框上：事件会先派发给可点击的子 View，
        // 只挂外层容器时框内的 ACTION_DOWN 被 previewFrame 吃掉，拖动/缩放完全失效。
        // 两者共用同一个 listener，保证一次手势的状态（mode / 上次坐标）连续。
        binding.previewFrame.setOnTouchListener(listener)
        binding.previewContainer.setOnTouchListener(listener)
    }

    private fun pointerDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
