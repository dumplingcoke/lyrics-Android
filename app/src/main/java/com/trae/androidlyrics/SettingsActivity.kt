package com.trae.androidlyrics

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Choreographer
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivitySettingsBinding

/**
 * 设置界面：主题、字体、动画、桌面歌词、背景图、通知授权。
 * 文字 / 已唱 / 描边颜色统一由二级页面 [LyricColorSettingsActivity] 管理。
 */
class SettingsActivity : ThemedActivity() {

    private lateinit var prefs: Prefs
    private lateinit var binding: ActivitySettingsBinding

    /** 待执行的初始化步骤，每帧执行一个。 */
    private val initSteps = mutableListOf<() -> Unit>()
    private var fullyInitialized = false

    /**
     * 通知权限申请：开启"通知栏显示桌面歌词开关"需要通知权限，
     * 授权成功后写入设置并刷新桌面歌词通知。
     */
    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingNotifyEnable = false
                prefs.notifyToggleDesktop = true
                binding.notifyToggleDesktopSwitch.isChecked = true
                DesktopLyricsService.refreshNotification(this)
            } else {
                // 未授权：跳转到系统通知设置页获取权限（与桌面歌词面板一致）
                binding.notifyToggleDesktopSwitch.isChecked = false
                openAppNotificationSettings()
            }
        }

    /** 用户尝试开启"通知栏显示桌面歌词开关"但尚未取得通知权限。 */
    private var pendingNotifyEnable = false

    /**
     * 通知权限申请：开启"启动时发送桌面歌词开关通知"同样需要通知权限，
     * 授权成功后写入设置并立即补发一次通知。
     */
    private val startupNotifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingStartupNotifyEnable = false
                prefs.notifyToggleOnStart = true
                binding.notifyToggleOnStartSwitch.isChecked = true
                DesktopLyricsService.postStartupToggleNotification(this)
            } else {
                // 保留 pending 标记：用户可能在系统设置页里授权，回来后继续完成开启
                prefs.notifyToggleOnStart = false
                binding.notifyToggleOnStartSwitch.isChecked = false
                openAppNotificationSettings()
            }
        }

    /** 用户尝试开启"启动时发送桌面歌词开关通知"但尚未取得通知权限。 */
    private var pendingStartupNotifyEnable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
        binding.btnSettingsSearch.setOnClickListener {
            startActivity(Intent(this, SettingsSearchActivity::class.java))
        }
        scheduleInit()
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

    /**
     * 分帧初始化：整页布局、数十次偏好读取与跨进程状态查询若全压在 onCreate，
     * 首帧必然超出一帧预算，「点设置卡一下」和主题重建时的顿挫都来自这里。
     * 拆成连续几帧后界面立即可见，控件在随后几帧依次就绪。
     * 颜色选择器已移入 [LyricColorSettingsActivity]，本页不再动态构建 View。
     */
    private fun scheduleInit() {
        initSteps.clear()
        fullyInitialized = false
        initSteps += ::setupAppearanceEntry
        initSteps += ::setupAppearance
        initSteps += ::setupDesktop
        initSteps += ::setupPermissions
        initSteps += ::setupOther
        initSteps += {
            fullyInitialized = true
            refreshRuntimeStatus()
        }
        runNextInitStep()
    }

    private fun runNextInitStep() {
        if (isFinishing || isDestroyed || initSteps.isEmpty()) return
        initSteps.removeAt(0).invoke()
        Choreographer.getInstance().postFrameCallback { runNextInitStep() }
    }

    override fun onResume() {
        super.onResume()
        if (fullyInitialized) refreshRuntimeStatus()
    }

    /** 通知使用权 / 通知权限都是跨进程 Binder 查询，放后台线程避免阻塞进入设置页的首帧。 */
    private fun refreshRuntimeStatus() {
        Thread {
            val listenerGranted = MusicNotificationListenerService.isEnabled(this)
            val notifGranted = notificationsEnabled()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateNotifAccessStatus(listenerGranted)
                syncNotifyToggleState(notifGranted)
                updateLogFileSize()
            }
        }.start()
    }

    /** 从系统设置返回后同步通知权限状态：已授予则完成开启，被撤销则关闭开关。 */
    private fun syncNotifyToggleState(granted: Boolean = notificationsEnabled()) {
        if (granted) {
            if (pendingNotifyEnable) {
                pendingNotifyEnable = false
                prefs.notifyToggleDesktop = true
                DesktopLyricsService.refreshNotification(this)
            }
            if (pendingStartupNotifyEnable) {
                pendingStartupNotifyEnable = false
                prefs.notifyToggleOnStart = true
                DesktopLyricsService.postStartupToggleNotification(this)
            }
        } else {
            // 系统层面撤销了通知权限：两个通知开关都无法生效，同步置灰状态。
            // pending 标记保留，用户从系统设置页授权回来后仍可自动完成开启。
            prefs.notifyToggleDesktop = false
            prefs.notifyToggleOnStart = false
        }
        binding.notifyToggleDesktopSwitch.isChecked = prefs.notifyToggleDesktop
        binding.notifyToggleOnStartSwitch.isChecked = prefs.notifyToggleOnStart
    }

    private fun notificationsEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

    /**
     * 「应用外观」入口。原「外观主题」分段控件已按需求移入 [AppearanceActivity]，
     * 这里只保留跳转；主题值仍写在 Prefs.themeMode，两边共享。
     */
    private fun setupAppearanceEntry() {
        binding.btnAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        // 「主页歌词设置」入口已迁往主页「更多选项」菜单，见 menu_main.xml。
    }

    private fun setupAppearance() {
        // 「封面模糊背景」由应用外观页迁入本页：开启时主页优先使用当前播放封面，
        // 不再回退到自定义背景图片（重启后也不会再出现自定义背景图）。
        val appearance = AppearancePrefs(this)
        binding.albumBlurSwitch.isChecked = appearance.albumBlurBackground
        binding.albumBlurSwitch.setOnCheckedChangeListener { _, checked ->
            appearance.albumBlurBackground = checked
            updateNoAlbumArtCustomBgUi()
        }

        // 「无专辑封面时使用自定义背景图片」：默认开启，但只在「封面模糊背景」开启时
        // 可编辑并生效；关闭封面模糊背景时自定义背景图片本来就常态显示，此项无意义。
        binding.noAlbumArtCustomBgSwitch.isChecked = appearance.useCustomBgWhenNoAlbumArt
        binding.noAlbumArtCustomBgSwitch.setOnCheckedChangeListener { _, checked ->
            appearance.useCustomBgWhenNoAlbumArt = checked
        }
        updateNoAlbumArtCustomBgUi()

        // 「自定义背景图片模糊」：只对用户添加的背景图生效；封面模糊背景开启时由封面接管。
        binding.customBgBlurSwitch.isChecked = appearance.customBackgroundBlur
        binding.customBgBlurSwitch.setOnCheckedChangeListener { _, checked ->
            appearance.customBackgroundBlur = checked
        }

        binding.wordByWordSwitch.isChecked = prefs.wordByWord
        binding.wordByWordSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.wordByWord = checked
            updateForceWordUi()
        }

        // 强行兼容逐字动画：仅在「逐字动画」开启时可用，默认关闭
        binding.forceWordByWordSwitch.isChecked = prefs.forceWordByWord
        binding.forceWordByWordSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.forceWordByWord = checked
        }
        updateForceWordUi()

        // 柔化只作用于已唱颜色蒙版；播放界面与桌面歌词读取同一份开关和强度。
        binding.wordSoftTransitionSwitch.isChecked = prefs.wordSoftTransition
        binding.wordSoftTransitionSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.wordSoftTransition = checked
            updateWordSoftTransitionUi()
        }
        binding.wordSoftTransitionStrengthBar.progress = prefs.wordSoftTransitionStrength
        binding.wordSoftTransitionStrengthValue.text =
            getString(R.string.alpha_percent, prefs.wordSoftTransitionStrength)
        binding.wordSoftTransitionStrengthBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.wordSoftTransitionStrength = progress
                    binding.wordSoftTransitionStrengthValue.text =
                        getString(R.string.alpha_percent, progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        binding.btnResetWordSoftTransitionStrength.setOnClickListener {
            prefs.wordSoftTransitionStrength = Prefs.DEFAULT_WORD_SOFT_TRANSITION_STRENGTH
            binding.wordSoftTransitionStrengthBar.progress = prefs.wordSoftTransitionStrength
            binding.wordSoftTransitionStrengthValue.text =
                getString(R.string.alpha_percent, prefs.wordSoftTransitionStrength)
            UiTips.show(this, R.string.word_soft_transition_reset_done)
        }
        updateWordSoftTransitionUi()

        // 歌词颜色设置：文字 / 已唱 / 描边颜色统一在二级页面里按两大模块分别配置
        binding.btnLyricColorSettings.setOnClickListener {
            startActivity(Intent(this, LyricColorSettingsActivity::class.java))
        }

        // 显示不带时间戳的行：默认开启；改动后重新解析当前已加载的歌词
        binding.showUntimedLinesSwitch.isChecked = prefs.showUntimedLines
        binding.showUntimedLinesSwitch.setOnCheckedChangeListener { _, checked ->
            applyShowUntimedLines(checked)
        }

        binding.seekToastSwitch.isChecked = prefs.showSeekToast
        binding.seekToastSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.showSeekToast = checked
        }

        // 「滚动动画」与「双语歌词高亮行」只作用于主页歌词，已迁往
        // HomeLyricsSettingsActivity（仍写入 Prefs.scrollAnimation / bilingualHighlight）。

        // 标题栏点击列表（匹配到的歌词/最近打开的歌词）
        binding.titleListGroup.check(
            if (prefs.titleListMode == Prefs.TITLE_LIST_RECENT) R.id.titleListRecent else R.id.titleListMatched
        )
        binding.titleListGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.titleListMode = if (checkedId == R.id.titleListRecent) Prefs.TITLE_LIST_RECENT else Prefs.TITLE_LIST_MATCHED
        }

        binding.btnBackground.setOnClickListener {
            startActivity(Intent(this, BackgroundManageActivity::class.java))
        }
        binding.btnClearBackground.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_background)
                .setMessage(R.string.confirm_restore_default_background)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.backgroundCurrentId = null
                    UiTips.show(this, R.string.background_cleared)
                }
                .show()
        }
    }

    /**
     * 「无专辑封面时使用自定义背景图片」依附于「封面模糊背景」：
     * 后者关闭时前者置灰不可编辑，且 [AppearancePrefs.useCustomBgWhenNoArtEffective]
     * 会一并返回 false，保证设置不生效；已保存的取值保留，重新开启后原样恢复。
     */
    private fun updateNoAlbumArtCustomBgUi() {
        val on = binding.albumBlurSwitch.isChecked
        binding.noAlbumArtCustomBgSwitch.isEnabled = on
        binding.noAlbumArtCustomBgSwitch.alpha = if (on) 1f else 0.4f
    }

    /** 「强行兼容逐字动画」依附于「逐字动画」：后者关闭时置灰前者。 */
    private fun updateForceWordUi() {
        val on = prefs.wordByWord
        binding.forceWordByWordSwitch.isEnabled = on
        binding.forceWordByWordSwitch.alpha = if (on) 1f else 0.4f
    }

    /** 关闭柔化时保留强度值，但禁用滑块与恢复按钮。 */
    private fun updateWordSoftTransitionUi() {
        val on = binding.wordSoftTransitionSwitch.isChecked
        binding.wordSoftTransitionOptions.alpha = if (on) 1f else 0.4f
        binding.wordSoftTransitionStrengthBar.isEnabled = on
        binding.btnResetWordSoftTransitionStrength.isEnabled = on
    }

    /** 「桌面歌词强行兼容逐字动画」依附于「桌面歌词逐字歌词」：后者关闭时置灰前者。 */
    private fun updateDesktopForceWordUi() {
        val on = prefs.desktopWordByWord
        binding.desktopForceWordByWordSwitch.isEnabled = on
        binding.desktopForceWordByWordSwitch.alpha = if (on) 1f else 0.4f
    }

    /**
     * 切换「显示不带时间戳的行」：更新持久化设置与解析器进程级选项，
     * 并让已加载的歌词立即按新选项重新解析（主界面回到前台时也会重新解析）。
     */
    private fun applyShowUntimedLines(checked: Boolean) {
        prefs.showUntimedLines = checked
        LyricsParser.showUntimedLines = checked
        Session.parseVersion++
        val file = Session.matchedFile?.takeIf { it.exists() } ?: return
        runCatching {
            Session.lyricLines = LyricsParser.parse(DocumentIo.decode(file.readBytes()).text)
        }
    }

    private fun setupDesktop() {
        binding.desktopLyricsSwitch.isChecked = prefs.desktopLyrics
        binding.desktopLyricsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopLyrics = checked
            if (checked) {
                if (canDrawOverlays()) {
                    DesktopLyricsService.start(this)
                } else {
                    prefs.desktopLyrics = false
                    binding.desktopLyricsSwitch.isChecked = false
                    requestOverlayPermission()
                }
            } else {
                DesktopLyricsService.stop(this)
            }
            DesktopLyricsTileService.requestUpdate(this)
        }

        binding.autoStartDesktopSwitch.isChecked = prefs.autoStartDesktopLyrics
        binding.autoStartDesktopSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoStartDesktopLyrics = checked
        }

        // 默认关闭并保持原有顶部边界；开启后允许继续向上划入状态栏。
        binding.desktopAllowStatusBarSwitch.isChecked = prefs.desktopAllowStatusBar
        binding.desktopAllowStatusBarSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopAllowStatusBar = checked
        }

        // 桌面歌词逐字歌词：只对带逐字时间戳的歌词格式生效
        binding.desktopWordByWordSwitch.isChecked = prefs.desktopWordByWord
        binding.desktopWordByWordSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopWordByWord = checked
            updateDesktopForceWordUi()
        }

        // 桌面歌词强行兼容逐字动画：与主界面同一套推算逻辑，
        // 仅在「桌面歌词逐字歌词」开启时可用，默认关闭
        binding.desktopForceWordByWordSwitch.isChecked = prefs.desktopForceWordByWord
        binding.desktopForceWordByWordSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopForceWordByWord = checked
        }
        updateDesktopForceWordUi()

        // 通知栏桌面歌词开关：无通知权限时跳转到获取权限的界面（与桌面歌词面板一致）
        binding.notifyToggleDesktopSwitch.isChecked = prefs.notifyToggleDesktop
        binding.notifyToggleDesktopSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                pendingNotifyEnable = false
                prefs.notifyToggleDesktop = false
                DesktopLyricsService.refreshNotification(this)
                return@setOnCheckedChangeListener
            }
            if (notificationsEnabled()) {
                prefs.notifyToggleDesktop = true
                DesktopLyricsService.refreshNotification(this)
                return@setOnCheckedChangeListener
            }
            // 无通知权限：Android 13+ 先申请运行时权限，被拒后引导到系统通知设置页
            pendingNotifyEnable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                binding.notifyToggleDesktopSwitch.isChecked = false
                openAppNotificationSettings()
            }
        }

        // 启动时发送桌面歌词开关通知：与桌面歌词是否开启无关，需要通知权限
        binding.notifyToggleOnStartSwitch.isChecked = prefs.notifyToggleOnStart
        binding.notifyToggleOnStartSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                pendingStartupNotifyEnable = false
                prefs.notifyToggleOnStart = false
                DesktopLyricsService.refreshNotification(this)
                return@setOnCheckedChangeListener
            }
            if (notificationsEnabled()) {
                prefs.notifyToggleOnStart = true
                DesktopLyricsService.postStartupToggleNotification(this)
                return@setOnCheckedChangeListener
            }
            pendingStartupNotifyEnable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startupNotifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                binding.notifyToggleOnStartSwitch.isChecked = false
                openAppNotificationSettings()
            }
        }

        binding.desktopFontSizeBar.progress = prefs.desktopFontSize
        binding.desktopFontSizeValue.text = "${prefs.desktopFontSize}sp"
        binding.desktopFontSizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.desktopFontSize = progress
                binding.desktopFontSizeValue.text = "${progress}sp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // 桌面歌词背景开关
        binding.desktopBackgroundSwitch.isChecked = prefs.desktopBackground
        binding.desktopBackgroundSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopBackground = checked
        }

        // 背景透明度（0%~100%）+ 恢复默认：与桌面歌词面板共用同一份设置
        binding.backgroundAlphaBar.progress = prefs.desktopBackgroundAlpha
        binding.backgroundAlphaValue.text =
            getString(R.string.alpha_percent, prefs.desktopBackgroundAlpha)
        binding.backgroundAlphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.desktopBackgroundAlpha = progress
                binding.backgroundAlphaValue.text = getString(R.string.alpha_percent, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.btnResetBackgroundAlpha.setOnClickListener {
            prefs.desktopBackgroundAlpha = Prefs.DEFAULT_DESKTOP_BACKGROUND_ALPHA
            binding.backgroundAlphaBar.progress = prefs.desktopBackgroundAlpha
            binding.backgroundAlphaValue.text =
                getString(R.string.alpha_percent, prefs.desktopBackgroundAlpha)
            UiTips.show(this, R.string.background_alpha_reset_done)
        }

        // 左右边缘渐隐（默认开启）：只淡化文字两端，服务 tick 检测到开关或羽化范围
        // 变化会即时套用到悬浮窗，不必重开桌面歌词。
        // 滑块范围 1~6（按半个字计：1 = 左右各半个字逐渐消失，2 = 左右各一个字），
        // 右侧只读标签显示当前数值。
        binding.desktopEdgeFadeSwitch.isChecked = prefs.desktopEdgeFade
        binding.desktopEdgeFadeSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopEdgeFade = checked
            updateEdgeFadeUi()
        }
        binding.desktopEdgeFadeBar.max = Prefs.MAX_DESKTOP_EDGE_FADE_CHARS - Prefs.MIN_DESKTOP_EDGE_FADE_CHARS
        binding.desktopEdgeFadeBar.progress = prefs.desktopEdgeFadeChars - Prefs.MIN_DESKTOP_EDGE_FADE_CHARS
        binding.desktopEdgeFadeValue.text = prefs.desktopEdgeFadeChars.toString()
        binding.desktopEdgeFadeBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + Prefs.MIN_DESKTOP_EDGE_FADE_CHARS
                    binding.desktopEdgeFadeValue.text = value.toString()
                    if (fromUser) prefs.desktopEdgeFadeChars = value
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        binding.btnResetEdgeFade.setOnClickListener {
            prefs.desktopEdgeFadeChars = Prefs.DEFAULT_DESKTOP_EDGE_FADE_CHARS
            binding.desktopEdgeFadeBar.progress =
                prefs.desktopEdgeFadeChars - Prefs.MIN_DESKTOP_EDGE_FADE_CHARS
            binding.desktopEdgeFadeValue.text = prefs.desktopEdgeFadeChars.toString()
            UiTips.show(this, R.string.edge_fade_reset_done)
        }
        updateEdgeFadeUi()

        // 文字颜色 / 文字不透明度 / 描边开关、颜色、粗细已移入「歌词颜色设置」页
        // （与桌面歌词悬浮面板共用同一份偏好，由该页负责同步）。

        // 桌面歌词宽度：范围 1~600dp，拖到最左即 1dp。
        // 不用 android:min（minSdk 24 上不生效），照位置滑块的偏移映射处理：
        // progress = 值 - MIN、写入值 = progress + MIN、max = RANGE。
        val widthMinDp = Prefs.DESKTOP_WIDTH_MIN_DP
        val widthMaxDp = Prefs.DESKTOP_WIDTH_MAX_DP
        fun displayWidth(): Int = prefs.desktopWidth.coerceIn(widthMinDp, widthMaxDp)
        fun showWidth() { binding.desktopWidthValue.text = "${displayWidth()}dp" }
        binding.desktopWidthBar.max = Prefs.DESKTOP_WIDTH_RANGE_DP
        binding.desktopWidthBar.progress = displayWidth() - widthMinDp
        showWidth()
        binding.desktopWidthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 初始化 / 重置时设置 progress 也会回调，别把偏移值写回偏好
                if (!fromUser) return
                prefs.desktopWidth = (progress + widthMinDp).coerceIn(widthMinDp, widthMaxDp)
                showWidth()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // -1 / +1 微调：与滑块共用 1~600dp 范围，写偏好后由服务 tick 实时应用
        fun stepWidth(delta: Int) {
            val next = (prefs.desktopWidth + delta).coerceIn(widthMinDp, widthMaxDp)
            prefs.desktopWidth = next
            binding.desktopWidthBar.progress = next - widthMinDp
            showWidth()
        }
        binding.btnWidthMinus.setOnClickListener { stepWidth(-1) }
        binding.btnWidthPlus.setOnClickListener { stepWidth(1) }

        // 恢复默认宽度
        binding.btnResetWidth.setOnClickListener {
            prefs.desktopWidth = Prefs.DEFAULT_DESKTOP_WIDTH
            binding.desktopWidthBar.progress = displayWidth() - widthMinDp
            showWidth()
            UiTips.show(this, R.string.width_reset_done)
        }

        // 自适应宽度：开启后按歌词长度自动调整，手动宽度不可用
        binding.desktopAutoFitSwitch.isChecked = prefs.desktopAutoFitWidth
        binding.desktopAutoFitSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.desktopAutoFitWidth = checked
            updateAutoFitUi()
        }
        // 自适应最大宽度与固定宽度共用 1~600dp 范围和同一套偏移映射
        fun displayMaxWidth(): Int = prefs.desktopAutoFitMaxWidth.coerceIn(widthMinDp, widthMaxDp)
        fun showMaxWidth() { binding.desktopMaxWidthValue.text = "${displayMaxWidth()}dp" }
        binding.desktopMaxWidthBar.max = Prefs.DESKTOP_WIDTH_RANGE_DP
        binding.desktopMaxWidthBar.progress = displayMaxWidth() - widthMinDp
        showMaxWidth()
        binding.desktopMaxWidthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.desktopAutoFitMaxWidth =
                    (progress + widthMinDp).coerceIn(widthMinDp, widthMaxDp)
                showMaxWidth()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.desktopAutoFitPaddingBar.progress = prefs.desktopAutoFitPadding
        binding.desktopAutoFitPaddingValue.text = "${prefs.desktopAutoFitPadding}dp"
        binding.desktopAutoFitPaddingBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.desktopAutoFitPadding = progress
                binding.desktopAutoFitPaddingValue.text = "${progress}dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updateAutoFitUi()

        // 双语歌词显示行（桌面歌词）
        binding.desktopBilingualGroup.check(
            when (prefs.desktopBilingualLine) {
                Prefs.BILINGUAL_FIRST -> R.id.desktopBilingualFirst
                Prefs.BILINGUAL_BOTH -> R.id.desktopBilingualBoth
                else -> R.id.desktopBilingualSecond
            }
        )
        binding.desktopBilingualGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.desktopBilingualLine = when (checkedId) {
                R.id.desktopBilingualFirst -> Prefs.BILINGUAL_FIRST
                R.id.desktopBilingualBoth -> Prefs.BILINGUAL_BOTH
                else -> Prefs.BILINGUAL_SECOND
            }
        }

        // 显示行数（单行/双行）
        binding.desktopLineCountGroup.check(
            if (prefs.desktopLineCount >= 2) R.id.btnLineDouble else R.id.btnLineSingle
        )
        binding.desktopLineCountGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                prefs.desktopLineCount = if (checkedId == R.id.btnLineDouble) 2 else 1
            }
        }

        // 对齐方式（左/中/右）
        binding.desktopAlignGroup.check(
            when (prefs.desktopAlignment) {
                Prefs.ALIGN_LEFT -> R.id.btnAlignLeft
                Prefs.ALIGN_RIGHT -> R.id.btnAlignRight
                else -> R.id.btnAlignCenter
            }
        )
        binding.desktopAlignGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                prefs.desktopAlignment = when (checkedId) {
                    R.id.btnAlignLeft -> Prefs.ALIGN_LEFT
                    R.id.btnAlignRight -> Prefs.ALIGN_RIGHT
                    else -> Prefs.ALIGN_CENTER
                }
            }
        }

        // 水平 / 垂直位置：存储介质是「屏幕左上角为原点的绝对锚点」（见
        // DesktopLyricsService 的 updateWindowPosition 与拖动写回），界面以屏幕中心
        // 为 0 显示相对偏移，范围 -3000 ~ +3000 px。两个方向各自独立重置。
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2
        val centerY = dm.heightPixels / 2
        val minPx = Prefs.DESKTOP_POSITION_MIN_PX
        val maxPx = Prefs.DESKTOP_POSITION_MAX_PX
        val midProgress = -minPx

        fun positionText(v: Int): String = if (v > 0) "+$v px" else "$v px"
        fun displayX(): Int = (prefs.desktopOffsetX - centerX).coerceIn(minPx, maxPx)
        fun displayY(): Int = (prefs.desktopOffsetY - centerY).coerceIn(minPx, maxPx)
        fun showX() { binding.desktopOffsetXValue.text = positionText(displayX()) }
        fun showY() { binding.desktopOffsetYValue.text = positionText(displayY()) }

        binding.desktopOffsetXBar.max = Prefs.DESKTOP_POSITION_RANGE_PX
        binding.desktopOffsetYBar.max = Prefs.DESKTOP_POSITION_RANGE_PX
        binding.desktopOffsetXBar.progress = displayX() - minPx
        binding.desktopOffsetYBar.progress = displayY() - minPx
        showX()
        showY()

        binding.desktopOffsetXBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.desktopOffsetX = centerX + (progress + minPx).coerceIn(minPx, maxPx)
                showX()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.desktopOffsetYBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.desktopOffsetY = centerY + (progress + minPx).coerceIn(minPx, maxPx)
                showY()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // -1 / +1 微调：按 1px 步进相对偏移，写入仍是「屏幕中心 + 显示值」的绝对锚点
        fun stepX(delta: Int) {
            val next = (displayX() + delta).coerceIn(minPx, maxPx)
            prefs.desktopOffsetX = centerX + next
            binding.desktopOffsetXBar.progress = next - minPx
            showX()
        }
        fun stepY(delta: Int) {
            val next = (displayY() + delta).coerceIn(minPx, maxPx)
            prefs.desktopOffsetY = centerY + next
            binding.desktopOffsetYBar.progress = next - minPx
            showY()
        }
        binding.btnPositionXMinus.setOnClickListener { stepX(-1) }
        binding.btnPositionXPlus.setOnClickListener { stepX(1) }
        binding.btnPositionYMinus.setOnClickListener { stepY(-1) }
        binding.btnPositionYPlus.setOnClickListener { stepY(1) }

        // 两个重置按钮单独控制：只把对应方向的锚点写回屏幕中心，互不影响。
        // 服务 tick 检测到偏好变化会立即 applyPosition()，无需广播。
        binding.btnResetPositionX.setOnClickListener {
            prefs.desktopOffsetX = centerX
            binding.desktopOffsetXBar.progress = midProgress
            showX()
            UiTips.show(this, R.string.desktop_position_x_reset_done)
        }
        binding.btnResetPositionY.setOnClickListener {
            prefs.desktopOffsetY = centerY
            binding.desktopOffsetYBar.progress = midProgress
            showY()
            UiTips.show(this, R.string.desktop_position_y_reset_done)
        }
    }

    /**
     * 左右边缘渐隐开关关闭时，强度滑块、数值框、标题与「恢复默认强度」一起置灰
     * （只改可操作性，用户已调好的强度值保留，重新开启即恢复原样）。
     */
    private fun updateEdgeFadeUi() {
        val on = prefs.desktopEdgeFade
        val alpha = if (on) 1f else 0.4f
        binding.desktopEdgeFadeBar.isEnabled = on
        binding.btnResetEdgeFade.isEnabled = on
        binding.desktopEdgeFadeBar.alpha = alpha
        binding.desktopEdgeFadeStrengthTitle.alpha = alpha
        binding.desktopEdgeFadeValue.alpha = alpha
        binding.btnResetEdgeFade.alpha = alpha
    }

    /** 自适应宽度开/关后的界面联动：禁用手动宽度行及右侧微调按钮，显示/隐藏自适应参数。 */
    private fun updateAutoFitUi() {
        val auto = prefs.desktopAutoFitWidth
        val manualAlpha = if (auto) 0.4f else 1f
        binding.desktopWidthBar.isEnabled = !auto
        binding.btnWidthMinus.isEnabled = !auto
        binding.btnWidthPlus.isEnabled = !auto
        binding.btnResetWidth.isEnabled = !auto
        binding.desktopWidthRow.alpha = manualAlpha
        binding.btnWidthMinus.alpha = manualAlpha
        binding.btnWidthPlus.alpha = manualAlpha
        binding.btnResetWidth.alpha = manualAlpha
        binding.desktopAutoFitOptions.visibility = if (auto) View.VISIBLE else View.GONE
    }

    private fun setupPermissions() {
        binding.btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun updateNotifAccessStatus(
        granted: Boolean = MusicNotificationListenerService.isEnabled(this),
    ) {
        binding.notifAccessStatus.text = getString(
            if (granted) R.string.notif_access_granted else R.string.notif_access_denied
        )
        binding.notifAccessStatus.setTextColor(
            if (granted) getColor(R.color.seed) else 0xFFFF5252.toInt()
        )
    }

    private fun setupOther() {
        // 下载目录重名处理策略
        binding.duplicateGroup.check(
            when (prefs.duplicateStrategy) {
                Prefs.DUPLICATE_OVERWRITE -> R.id.dupOverwrite
                Prefs.DUPLICATE_COPY -> R.id.dupCopy
                else -> R.id.dupAsk
            }
        )
        binding.duplicateGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.duplicateStrategy = when (checkedId) {
                R.id.dupOverwrite -> Prefs.DUPLICATE_OVERWRITE
                R.id.dupCopy -> Prefs.DUPLICATE_COPY
                else -> Prefs.DUPLICATE_ASK
            }
        }

        binding.immersiveBarsSwitch.isChecked = prefs.immersiveShowBars
        binding.immersiveBarsSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.immersiveShowBars = checked
        }

        // 重置所有「不再询问」类弹窗提示：歌词编辑提示、下载目录重名弹窗会重新出现，
        // 同时清掉本次进程内「通知使用权引导只提示一次」的标记。
        binding.btnResetDialogPrompts.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_dialog_prompts)
                .setMessage(R.string.confirm_reset_dialog_prompts)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.resetAllDialogPrompts()
                    MainActivity.resetDialogPromptFlags()
                    binding.duplicateGroup.check(R.id.dupAsk)
                    UiTips.show(this, R.string.reset_dialog_prompts_done)
                }
                .show()
        }

        binding.autoMatchSwitch.isChecked = prefs.autoMatchLyrics
        binding.autoMatchSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.autoMatchLyrics = checked
        }
        binding.btnMatchRules.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.match_rules_title)
                .setMessage(R.string.match_rules_content)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        // 匹配相似度阈值：松手后按新阈值重新匹配当前歌曲
        binding.matchSimilarityBar.progress = prefs.matchSimilarity
        binding.matchSimilarityValue.text = getString(R.string.similarity_percent, prefs.matchSimilarity)
        binding.matchSimilarityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.matchSimilarity = progress
                binding.matchSimilarityValue.text = getString(R.string.similarity_percent, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                MusicNotificationListenerService.rematchNow()
            }
        })
        binding.btnResetSimilarity.setOnClickListener {
            prefs.matchSimilarity = Prefs.DEFAULT_MATCH_SIMILARITY
            binding.matchSimilarityBar.progress = prefs.matchSimilarity
            binding.matchSimilarityValue.text =
                getString(R.string.similarity_percent, prefs.matchSimilarity)
            MusicNotificationListenerService.rematchNow()
            UiTips.show(this, R.string.similarity_reset_done)
        }

        // 歌词空行显示模式
        binding.emptyLineGroup.check(
            when (prefs.emptyLineMode) {
                Prefs.EMPTY_LINE_BEAT -> R.id.emptyLineBeat
                Prefs.EMPTY_LINE_TILDE -> R.id.emptyLineTilde
                Prefs.EMPTY_LINE_ACCOMPANIMENT -> R.id.emptyLineAccompaniment
                Prefs.EMPTY_LINE_CUSTOM -> R.id.emptyLineCustom
                else -> R.id.emptyLineNone
            }
        )
        updateEmptyLineCustomUi()
        binding.emptyLineGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.emptyLineMode = when (checkedId) {
                R.id.emptyLineBeat -> Prefs.EMPTY_LINE_BEAT
                R.id.emptyLineTilde -> Prefs.EMPTY_LINE_TILDE
                R.id.emptyLineAccompaniment -> Prefs.EMPTY_LINE_ACCOMPANIMENT
                R.id.emptyLineCustom -> Prefs.EMPTY_LINE_CUSTOM
                else -> Prefs.EMPTY_LINE_NONE
            }
            updateEmptyLineCustomUi()
        }
        binding.emptyLineCustomInput.setText(prefs.emptyLineCustom)
        binding.emptyLineCustomInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.emptyLineCustom = s?.toString().orEmpty()
            }
        })

        binding.btnStorageManage.setOnClickListener {
            startActivity(Intent(this, LyricStorageActivity::class.java))
        }

        // 程序数据管理：像文件资源管理器一样浏览/删除应用数据
        binding.btnDataManage.setOnClickListener {
            startActivity(Intent(this, DataManagerActivity::class.java))
        }
        val showDataWarning = View.OnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.data_manage)
                .setMessage(R.string.data_manage_warning)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.btnDataManageInfo.setOnClickListener(showDataWarning)

        // 日志抓取：开关 + 日志文件查看（导出/清除在日志页内）
        binding.logCaptureSwitch.isChecked = prefs.logCaptureEnabled
        binding.logCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.logCaptureEnabled = checked
            if (checked) {
                // 立即回填关闭期间的内存缓存并输出当前状态，无需等播放状态变化
                LogCapture.startCapture()
                MusicNotificationListenerService.requestStateDump("日志抓取开启")
            } else {
                LogCapture.i("LogCapture", "日志抓取已关闭")
            }
            updateLogFileSize()
        }
        binding.btnLogFile.setOnClickListener {
            startActivity(Intent(this, LogViewActivity::class.java))
        }
        updateLogFileSize()

        // 「调试信息悬浮窗」开关已迁往 ListenStatusActivity（「应用内部计时」卡片上方），
        // 仍写入 prefs.debugMode，并由 DebugInfoTileService 同步磁贴状态。
    }

    /** 空行显示选"自定义"时显示文字输入框。 */
    private fun updateEmptyLineCustomUi() {
        binding.emptyLineCustomLayout.visibility =
            if (prefs.emptyLineMode == Prefs.EMPTY_LINE_CUSTOM) View.VISIBLE else View.GONE
    }

    /** 刷新"日志文件"右侧的大小提示。 */
    private fun updateLogFileSize() {
        val size = LogCapture.getLogSize()
        binding.logFileSize.text = if (size <= 0L) "" else formatSize(size)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            UiTips.show(this, R.string.overlay_permission_hint, android.widget.Toast.LENGTH_LONG)
        }
    }

    /**
     * 引导用户到系统通知设置页。
     *
     * 优先打开本应用的通知设置（[Settings.ACTION_APP_NOTIFICATION_SETTINGS]），
     * 部分 ROM 不支持时会弹出"在已安装应用的列表中找不到该应用"，此时回退到应用详情页。
     * extras 同时写入新、旧两套键名，兼容不同 ROM（新键见 [Settings.EXTRA_APP_PACKAGE]）。
     */
    private fun openAppNotificationSettings() {
        val notifIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra("app_package", packageName)
            putExtra("app_uid", applicationInfo.uid)
        }
        val detailIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        val opened = runCatching { startActivity(notifIntent) }.isSuccess ||
            runCatching { startActivity(detailIntent) }.isSuccess
        if (!opened) {
            UiTips.show(this, R.string.notify_permission_denied, android.widget.Toast.LENGTH_LONG)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
