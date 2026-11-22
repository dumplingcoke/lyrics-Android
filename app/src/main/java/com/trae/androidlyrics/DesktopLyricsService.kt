package com.trae.androidlyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trae.androidlyrics.databinding.FloatPanelBinding
import java.io.File
import kotlin.concurrent.thread

/**
 * 桌面歌词服务：通过 WindowManager 在所有应用之上显示当前歌词。
 *
 * 支持设置实时更新（字体大小、颜色、背景、宽度、行数、对齐、位置），
 * 无需重启服务。文本超出宽度时以跑马灯方式滚动显示。
 */
class DesktopLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private var floatView: View? = null
    private var floatRoot: DesktopLyricsFadeLayout? = null
    private var textView: OutlineTextView? = null
    private var textViewSecond: OutlineTextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 主行 / 次行滚动控制器：文本超宽时自定义滚动，替代系统跑马灯。 */
    private lateinit var scrollerMain: LineScroller
    private lateinit var scrollerSecond: LineScroller

    /** 点击桌面歌词后弹出的操作面板（独立悬浮窗，位于歌词下方）。
     *  窗口在服务启动时即以隐藏状态常驻挂载（先于歌词窗口），保证 z 序
     *  恒在歌词窗口之下；弹出/收起只改窗口参数与可见性，不重挂歌词窗口。 */
    private var panelView: View? = null
    private var panelLayoutParams: WindowManager.LayoutParams? = null

    /** 面板内容绑定：窗口创建时 inflate 一次，复用至服务销毁。 */
    private var panelBinding: FloatPanelBinding? = null

    /** 面板当前是否可见（不可见时窗口缩为 1px 且不可触摸，等同于隐藏）。 */
    private var panelVisible = false

    private val uiHandler = Handler(Looper.getMainLooper())

    // 缓存上一次的设置值，用于检测变化并实时更新
    private var lastFontSize = -1
    private var lastTextColor = 0
    private var lastTextAlpha = -1
    private var lastOutlineEnabled = false
    private var lastOutlineColor = 0
    private var lastOutlineWidth = -1
    private var lastOutlinePosition = -1
    private var lastBackground = true
    private var lastBackgroundAlpha = -1
    private var lastEdgeFade = true
    private var lastEdgeFadeChars = Int.MIN_VALUE
    private var lastWidth = -1
    private var lastAutoFit = false
    private var lastAutoFitMax = -1
    private var lastAutoFitPadding = -1
    private var lastLineCount = -1
    private var lastBilingualLine = -1
    private var lastAlignment = -1
    private var lastOffsetX = -1
    private var lastOffsetY = -1
    private var lastDesktopAllowStatusBar = false
    /** 进程保护开关的上次值：设置页/悬浮面板改动后立即重建或撤销守护闹钟。 */
    private var lastProcessGuard = false
    private var lastEmptyMode = -1
    private var lastEmptyCustom: String? = null

    /** 桌面歌词逐字开关 / 强行兼容开关 / 已唱颜色的上次值，用于强制重渲染当前行。 */
    private var lastDesktopWordByWord = false
    private var lastDesktopForceWordByWord = false
    private var lastSungColor = 0

    private val tickRunnable = object : Runnable {
        override fun run() {
            updateLyric()
            applySettingsIfChanged()
            // 面板展开期间同步匹配状态文字（正在匹配 / 无匹配 / 当前文件）
            if (panelVisible) panelBinding?.let { updateMatchState(it) }
            uiHandler.postDelayed(this, 200)
        }
    }

    private var currentLineIndex = -1

    /** 上一帧的修正进度：检测真实回退，让长歌词滚动跟着倒退（切歌时复位为 -1）。 */
    private var lastAdjustedPosMs = -1L

    /**
     * 当前主行可用的逐字轴：长行横向滚动与桌面逐字蒙版共用同一份字轴，
     * null 表示该行没有逐字动画（未开开关 / 无时间戳且未开强行兼容 / 空行）。
     */
    private var currentWordWords: List<LyricWord>? = null

    /** 上一次渲染的歌词列表引用，用于检测列表被替换（自动匹配/打开新文件）。 */
    private var lastLines: List<LyricLine>? = null

    /** 无歌词时上一次显示的占位文案：匹配状态变化后即使行号未变也要刷新文字。 */
    private var lastNoLyricHint: String? = null

    /** 上一次处理的歌曲切换版本号，用于检测切歌事件（含列表循环同一首歌重放）。 */
    private var lastSongChangeVersion: Int = -1

    /**
     * 本服务实例是否由用户主动开启（[start] / 通知栏 ACTION_TURN_ON）拉起。
     *
     * 进程保护关闭时的「后台误启动守卫」只能拦截纯后台拉起的实例：AMS 按旧的
     * START_STICKY 补投的 null intent、残留守护闹钟。用户刚点磁贴开启服务时，
     * 这些迟到的系统命令也可能紧接着到达，若不区分就会把刚显示的悬浮窗又停掉。
     * 进程内字段即可：进程被杀后自动归零，正好符合「后台不自动恢复」。
     */
    private var userStarted = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = Prefs(this)
        // 启动状态只由用户主动开关 / 应用前台自启动写入，这里不改写：
        // sticky 重启、残留守护闹钟若在后台误拉起服务，不能顺带把开关洗成开启。
        DesktopLyricsTileService.requestUpdate(this)
        startForegroundCompat()
        // 进程保护：随服务常驻一条由系统持有的守护闹钟，进程被静默清理时也能自恢复；
        // 保护关闭时主动清掉任何残留闹钟，不能让它绕过开关把服务拉回来
        if (prefs.desktopProcessGuard) scheduleGuardWatchdog(this)
        else cancelGuardAlarms(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showFloatWindow()
        uiHandler.post(tickRunnable)
        restoreLyricsIfNeeded()
    }

    /**
     * 进程经快捷入口（磁贴/应用快捷方式）冷启动拉起本服务时，[Session.lyricLines]
     * 为空：主界面没有打开过文档，通知监听的绑定又可能已被系统回收且不会自动重连，
     * 自动匹配迟迟不执行，桌面歌词会长时间停留在「暂无歌词」。
     *
     * 这里做两件事：
     * 1. 通知监听已授权但未连接时主动恢复绑定（requestRebind），让自动匹配
     *    尽快接管当前播放的歌曲；
     * 2. 后台线程从磁盘恢复上次显示的歌词（优先内存中的匹配结果 → 持久化的
     *    最后歌词文件 → 最近打开的内部副本），让歌词立即出现。监听重连后
     *    自动匹配会用正在播放的歌曲替换，无需用户干预。
     */
    private fun restoreLyricsIfNeeded() {
        if (MusicNotificationListenerService.isEnabled(this) &&
            !MusicNotificationListenerService.isConnected()
        ) {
            MusicNotificationListenerService.recover(this)
        }
        if (!Session.lyricLines.isNullOrEmpty()) return
        // 记录恢复开始时的会话版本：磁盘读取期间可能已经切歌并作废歌词，
        // 此时绝不能把上一首的歌词重新写回 Session（表现为切歌后旧歌词残留）。
        val startMatchVersion = Session.matchVersion
        val startSongVersion = Session.songChangeVersion
        thread(name = "DesktopLyricsRestore") {
            val file = Session.matchedFile?.takeIf { it.exists() }
                ?: prefs.lastLyricFile?.let(::File)?.takeIf { it.exists() }
                ?: prefs.getRecentFiles().firstOrNull { File(it.uri).exists() }
                    ?.let { File(it.uri) }
            if (file == null) return@thread
            val lines = runCatching {
                LyricsParser.parse(DocumentIo.decode(file.readBytes()).text)
            }.getOrNull().orEmpty()
            if (lines.isEmpty()) return@thread
            // 磁盘加载期间自动匹配可能已先一步填充（监听恰好重连），不覆盖其结果
            if (!Session.lyricLines.isNullOrEmpty()) return@thread
            if (Session.matchVersion != startMatchVersion ||
                Session.songChangeVersion != startSongVersion ||
                Session.matchState == LyricMatchState.MATCHING ||
                Session.matchState == LyricMatchState.FETCHING_ONLINE
            ) return@thread
            Session.lyricLines = lines
            if (Session.matchedFile == null) Session.matchedFile = file
        }
    }

    /** 创建通知渠道（幂等）。 */
    private fun ensureChannel() = ensureChannelFor(this)

    /**
     * 桌面歌词通知是否应带上开关按钮。
     * 「通知栏显示桌面歌词开关」常驻显示，或「启动时发送桌面歌词开关通知」开启时都生效。
     */
    private fun toggleNotificationEnabled(): Boolean =
        prefs.notifyToggleDesktop || prefs.notifyToggleOnStart

    /**
     * 构建桌面歌词通知。
     *
     * [desktopOn] 为 true 表示桌面歌词开启，按钮动作为"关闭"；
     * 为 false 表示已关闭（保留通知以便再次开启），按钮动作为"开启"。
     * 仅当用户开启"通知栏显示桌面歌词开关"时才附带开关控件。
     */
    private fun buildNotification(desktopOn: Boolean): Notification {
        val builder = notifyBuilder(this, desktopOn)
            .setContentIntent(openAppPendingIntent(this))
        if (toggleNotificationEnabled()) {
            // 普通通知的 action 按钮折叠时不显示，改用自定义正文让开关常驻可见
            builder.setCustomContentView(contentView(this, desktopOn))
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        }
        return builder.build()
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification(desktopOn = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先登记「用户主动开启」：这条标记必须早于下面的误启动守卫。
        // 用户点磁贴/快捷方式开启时，AMS 补投的 sticky 重启或残留守护闹钟可能
        // 紧接着到达同一服务实例，守卫不得把它们当成理由把刚开启的服务停掉。
        if (intent?.action == ACTION_USER_START || intent?.action == ACTION_TURN_ON) {
            userStarted = true
        }
        // 保护关闭时，服务此前未运行却收到 null intent，只可能是 AMS 按旧的
        // START_STICKY 策略重启，一律撤销本次误启动。不改写「启用桌面歌词」
        // 开关（persist=false）：它是用户主动打开应用时判断是否自启动的依据。
        // 关键：本实例若已被用户主动开启，无论后续收到什么系统命令都不撤销。
        if (!prefs.desktopProcessGuard && !userStarted && startId == 1 && intent == null) {
            cancelGuardAlarms(this)
            turnOffDesktopLyrics(persist = false)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_USER_START -> Unit // 用户主动开启：onCreate 已建好悬浮窗
            ACTION_TURN_OFF -> {
                userStarted = false
                turnOffDesktopLyrics()
                return START_NOT_STICKY
            }
            ACTION_TURN_ON -> {
                // 用户从通知栏主动开启：onCreate 已建好悬浮窗，这里同步启动状态
                prefs.desktopLyrics = true
                DesktopLyricsTileService.requestUpdate(this)
            }
            ACTION_REFRESH -> runCatching { startForegroundCompat() }
            ACTION_SYNC_STICKY -> Unit // 只为让最后一次返回值反映当前进程保护开关
            ACTION_GUARD_WAKE -> {
                // 守护闹钟触发：只有「进程保护开启且桌面歌词仍应显示」才续上下一跳。
                // 保护关闭时必须撤销闹钟；若这次唤醒刚把服务创建出来（startId == 1，
                // 说明服务此前并不在运行），还要立刻停掉它，防止残留闹钟绕过
                // 开关把桌面歌词重新拉起。服务本来就在跑时只撤闹钟、不打扰用户。
                if (prefs.desktopProcessGuard && prefs.desktopLyrics) {
                    scheduleGuardWatchdog(this)
                } else {
                    cancelGuardAlarms(this)
                    // 用户刚主动开启的服务只撤闹钟，绝不停服：残留闹钟可能恰好在
                    // 开启后触发，此时它只是「唤醒」，不是要撤销用户的操作。
                    if (startId == 1 && !userStarted) {
                        // 这不是用户主动关闭，只撤销本次误启动，不改写「启用桌面歌词」开关
                        turnOffDesktopLyrics(persist = false)
                        return START_NOT_STICKY
                    }
                }
            }
        }
        // 系统级 START_STICKY 同样属于「自动恢复」：进程保护关闭时返回 NOT_STICKY，
        // 否则系统会在内存回收 / 划掉任务后自作主张重启服务并显示悬浮窗。
        // AMS 只记最后一次返回值，所以开关切换时要补发一次 [ACTION_SYNC_STICKY] 刷新。
        return if (prefs.desktopProcessGuard) START_STICKY else START_NOT_STICKY
    }

    /**
     * 进程保护开关变化后补发一次空的启动命令：AMS 只记最后一次 [onStartCommand]
     * 的返回值，光改 prefs 不会刷新「进程被杀后是否重启本服务」。不补这一次，
     * 从「开启」切到「关闭」后系统仍按旧的 START_STICKY 把服务拉回来。
     */
    private fun syncStickyPolicy() {
        if (!isRunning) return
        runCatching {
            startService(
                Intent(this, DesktopLyricsService::class.java).setAction(ACTION_SYNC_STICKY)
            )
        }
    }

    /**
     * 用户从最近任务划掉应用 / 一键清理后台：保护开启时登记系统闹钟，约 1 秒后
     * 重新拉起前台服务；保护关闭时撤销守护闹钟并停服，让悬浮窗随清理一起消失。
     *
     * 例外：透明中转页（磁贴 / 应用快捷方式）所在的独立任务结束也会触发本回调，
     * 那只是中转页 finish，不是清理后台，必须原样放行，否则刚开启的桌面歌词
     * 会被立刻停掉。
     *
     * 不改写 [Prefs.desktopLyrics]：它是用户主动打开应用时判断是否自启动的依据。
     * 保护关闭时后台不会再恢复（App 不后台拉起、返回 START_NOT_STICKY、闹钟已撤销）；
     * 用户主动打开应用时由 [recoverInForeground] 按开关自启动。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 透明中转页（磁贴 / 应用快捷方式）是 taskAffinity="" + excludeFromRecents
        // 的独立任务，它执行完开关动作并 finish 后，系统同样会回调本方法。
        // 这**不是**用户清理后台，绝不能在这里撤销刚开启的悬浮窗，也不能登记
        // 守护闹钟重启——否则表现为「点磁贴开启后桌面歌词立刻自动关闭」。
        val root = rootIntent?.component?.className
        if (root == TileActionActivity::class.java.name ||
            root == QuickImportActivity::class.java.name
        ) {
            super.onTaskRemoved(rootIntent)
            return
        }
        if (prefs.desktopProcessGuard && prefs.desktopLyrics) {
            scheduleGuardRestart(this)
        } else {
            cancelGuardAlarms(this)
            DesktopLyricsTileService.requestUpdate(this)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 关闭桌面歌词：隐藏悬浮窗并停止服务；
     * 若用户开启了"通知栏显示桌面歌词开关"，保留一条可再次开启的通知。
     *
     * [persist] 为 false 时只停掉本次运行（撤销守护闹钟误拉起的服务），
     * 不改写桌面歌词启动状态。
     */
    private fun turnOffDesktopLyrics(persist: Boolean = true) {
        if (persist) prefs.desktopLyrics = false
        // 关闭时撤销守护闹钟，不能让保护逻辑把服务又拉回来
        cancelGuardAlarms(this)
        DesktopLyricsTileService.requestUpdate(this)
        removePanelWindow()
        stopScrollers()
        floatView?.let { runCatching { windowManager.removeView(it) } }
        floatView = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        postToggleNotification(desktopOn = false)
        stopSelf()
    }

    /** 关闭后保留可再次开启的通知（仅当用户开启通知栏开关时）。 */
    private fun postToggleNotification(desktopOn: Boolean) {
        if (!toggleNotificationEnabled()) return
        runCatching {
            ensureChannel()
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(desktopOn))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        uiHandler.removeCallbacks(tickRunnable)
        removePanelWindow()
        stopScrollers()
        floatView?.let { runCatching { windowManager.removeView(it) } }
        floatView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showFloatWindow() {
        val view = View.inflate(this, R.layout.float_lyrics, null)
        // 固定宽度模式下直接用目标宽度初始化，避免初始 WRAP_CONTENT 窗口过窄
        // （此时若 updateViewLayout 未生效/延迟，TextView 实际宽度远小于预估可用宽度，
        // 文本会被裁剪或 ellipsize）
        val initialWidth =
            if (!prefs.desktopAutoFitWidth && prefs.desktopWidth > 0) dp(prefs.desktopWidth)
            else WindowManager.LayoutParams.WRAP_CONTENT
        val params = WindowManager.LayoutParams(
            initialWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.desktopOffsetX
            y = prefs.desktopOffsetY
        }

        floatRoot = view.findViewById(R.id.floatRoot)
        textView = view.findViewById(R.id.floatText)
        textViewSecond = view.findViewById(R.id.floatTextSecond)
        // 只有主行有逐字轴：次行（翻译 / 下一行）没有对应字时间戳，仍按匀速滚动
        scrollerMain = LineScroller(textView!!, wordDriven = true)
        scrollerSecond = LineScroller(textViewSecond!!, wordDriven = false)

        // 内容宽度/高度变化（换歌词、改字号、切换单行双行）后按对齐锚点重新定位，
        // 并同步操作面板位置。
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = (right - left) != (oldRight - oldLeft)
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (widthChanged) {
                updateWindowPosition(prefs.desktopOffsetX, prefs.desktopOffsetY)
                // 首次布局完成后窗口尺寸已知，若钳制改变了实际位置，
                // 把校正后的锚点回写到设置，避免旋转/拖出屏幕后每次重启都消失
                val viewW = right - left
                val viewH = bottom - top
                if (viewW > 0 && viewH > 0) {
                    val correctedX = params.x + anchorOffsetOf(prefs.desktopAlignment, viewW)
                    val correctedY = clampWindowY(params.y)
                    if (prefs.desktopOffsetX != correctedX || prefs.desktopOffsetY != correctedY) {
                        prefs.desktopOffsetX = correctedX
                        prefs.desktopOffsetY = correctedY
                        lastOffsetX = correctedX
                        lastOffsetY = correctedY
                    }
                }
            } else if (heightChanged) {
                updatePanelPosition()
            }
        }

        // 先挂载（隐藏状态的）面板窗口，再挂载歌词窗口：同类型悬浮窗的层级
        // 由添加顺序决定，后添加的歌词窗口恒在面板之上，之后弹出/收起面板
        // 只改面板自身的窗口参数，无需重挂歌词窗口，消除点击时的闪烁。
        ensurePanelWindow()
        windowManager.addView(view, params)
        // 关键：必须先赋值 floatView/layoutParams 再应用设置。
        // applyWidth 依赖 layoutParams 字段，若在其赋值前调用会读到 null 而跳过宽度设置，
        // 且 lastWidth 已被同步导致后续 tick 检测不到变化——表现为重开桌面歌词后宽度被重置。
        floatView = view
        layoutParams = params

        // 初始应用设置
        applyAllSettings()

        // 拖动悬浮窗：拖动的是"锚点"（含义随对齐方式变化），松手后保存位置。
        // 短按（移动距离小于系统触摸阈值）视为点击，弹出操作面板。
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startAnchorX = 0
        var startAnchorY = 0
        var curAnchorX = 0
        var curAnchorY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startAnchorX = prefs.desktopOffsetX
                    startAnchorY = prefs.desktopOffsetY
                    curAnchorX = startAnchorX
                    curAnchorY = startAnchorY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        moved = true
                    }
                    if (moved) {
                        curAnchorX = startAnchorX + dx.toInt()
                        curAnchorY = startAnchorY + dy.toInt()
                        updateWindowPosition(curAnchorX, curAnchorY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // updateWindowPosition 已钳制 params 到屏幕内，
                        // 这里按钳制后的实际左缘反推锚点保存，避免越界位置被持久化
                        val viewW = view.width
                        val anchorX = params.x + anchorOffsetOf(prefs.desktopAlignment, viewW)
                        val anchorY = params.y
                        prefs.desktopOffsetX = anchorX
                        prefs.desktopOffsetY = anchorY
                        lastOffsetX = anchorX
                        lastOffsetY = anchorY
                    } else {
                        // 点击：切换操作面板显示
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 一次性应用所有设置（首次创建时调用）。 */
    private fun applyAllSettings() {
        applyFontSize()
        applyTextColor()
        applyBackground()
        applyEdgeFade()
        applyWidth()
        applyLineCount()
        applyAlignment()
        applyPosition()
        lastDesktopWordByWord = prefs.desktopWordByWord
        lastDesktopForceWordByWord = prefs.desktopForceWordByWord
        lastSungColor = prefs.desktopSungTextColor
        lastTextAlpha = prefs.desktopTextAlpha
        lastProcessGuard = prefs.desktopProcessGuard
    }

    /** 每次 tick 检测设置变化并实时更新。 */
    private fun applySettingsIfChanged() {
        // 进程保护开关可能在面板或其它入口被改动：立即补齐/撤销守护闹钟，
        // 并让系统同步 sticky 策略（否则关掉保护后服务仍会被系统自动重启）
        if (prefs.desktopProcessGuard != lastProcessGuard) {
            lastProcessGuard = prefs.desktopProcessGuard
            if (lastProcessGuard) scheduleGuardWatchdog(this) else cancelGuardAlarms(this)
            syncStickyPolicy()
        }
        if (prefs.desktopFontSize != lastFontSize) applyFontSize()
        if (prefs.desktopTextColor != lastTextColor ||
            prefs.desktopTextAlpha != lastTextAlpha ||
            prefs.desktopOutlineEnabled != lastOutlineEnabled ||
            prefs.desktopOutlineColor != lastOutlineColor ||
            prefs.desktopOutlineWidth != lastOutlineWidth ||
            prefs.desktopOutlinePosition != lastOutlinePosition
        ) {
            applyTextColor()
            // 文字颜色决定逐字动画「未唱」部分的暗色，变化后强制重渲染当前行
            currentLineIndex = Int.MIN_VALUE
        }
        if (prefs.desktopBackground != lastBackground ||
            prefs.desktopBackgroundAlpha != lastBackgroundAlpha
        ) applyBackground()
        // 左右边缘渐隐：只改内容层的两个属性，开关/强度变化不需要重挂窗口；
        // 渐隐尺寸由视图按文字层当前宽度换算，改宽度或字号时 onSizeChanged 会自行重建渐变。
        if (prefs.desktopEdgeFade != lastEdgeFade ||
            prefs.desktopEdgeFadeChars != lastEdgeFadeChars
        ) applyEdgeFade()
        if (prefs.desktopWidth != lastWidth ||
            prefs.desktopAutoFitWidth != lastAutoFit ||
            prefs.desktopAutoFitMaxWidth != lastAutoFitMax ||
            prefs.desktopAutoFitPadding != lastAutoFitPadding
        ) {
            applyWidth()
        }
        if (prefs.desktopLineCount != lastLineCount) applyLineCount()
        if (prefs.desktopBilingualLine != lastBilingualLine) applyLineCount()
        if (prefs.desktopAlignment != lastAlignment) applyAlignment()
        if (prefs.desktopOffsetX != lastOffsetX || prefs.desktopOffsetY != lastOffsetY ||
            prefs.desktopAllowStatusBar != lastDesktopAllowStatusBar
        ) {
            applyPosition()
        }
        // 空行显示设置变化后强制重渲染当前行
        if (prefs.emptyLineMode != lastEmptyMode || prefs.emptyLineCustom != lastEmptyCustom) {
            lastEmptyMode = prefs.emptyLineMode
            lastEmptyCustom = prefs.emptyLineCustom
            currentLineIndex = Int.MIN_VALUE
        }
        // 逐字开关 / 强行兼容开关 / 已唱颜色变化：重渲染当前行，让蒙版立即按新设置重绑
        if (prefs.desktopWordByWord != lastDesktopWordByWord ||
            prefs.desktopForceWordByWord != lastDesktopForceWordByWord ||
            prefs.desktopSungTextColor != lastSungColor
        ) {
            lastDesktopWordByWord = prefs.desktopWordByWord
            lastDesktopForceWordByWord = prefs.desktopForceWordByWord
            lastSungColor = prefs.desktopSungTextColor
            currentLineIndex = Int.MIN_VALUE
        }
    }

    private fun applyFontSize() {
        val size = prefs.desktopFontSize
        textView?.textSize = size.toFloat()
        textViewSecond?.textSize = (size - 3).toFloat()
        lastFontSize = size
        // 字号变化影响文本测量与自适应窗口宽度，滚动距离也需重新计算
        if (prefs.desktopAutoFitWidth) applyWidth()
        scrollerMain.refresh()
        scrollerSecond.refresh()
    }

    private fun applyTextColor() {
        val alphaPercent = prefs.desktopTextAlpha
        // 文字不透明度按比例缩放颜色的 alpha 通道；描边与主行同步变淡
        val color = withTextAlpha(prefs.desktopTextColor, alphaPercent)
        val outlineEnabled = prefs.desktopOutlineEnabled
        val outlineColor = withTextAlpha(prefs.desktopOutlineColor, alphaPercent)
        val outlineWidth = prefs.desktopOutlineWidth
        val outlinePosition = prefs.desktopOutlinePosition
        applyTextColorTo(textView, color, outlineEnabled, outlineColor, outlineWidth, outlinePosition)
        // 第二行用同色但透明度降为 0xB0（在已缩放的 alpha 基础上再乘一次）
        val faded = scaleColorAlpha(color, 0xB0)
        val fadedOutline = scaleColorAlpha(outlineColor, 0xB0)
        applyTextColorTo(
            textViewSecond, faded, outlineEnabled, fadedOutline, outlineWidth, outlinePosition
        )
        lastTextColor = prefs.desktopTextColor
        lastTextAlpha = alphaPercent
        lastOutlineEnabled = outlineEnabled
        lastOutlineColor = prefs.desktopOutlineColor
        lastOutlineWidth = outlineWidth
        lastOutlinePosition = outlinePosition
        // 逐字动画颜色也依赖文字 alpha，强制重绑当前行
        currentLineIndex = Int.MIN_VALUE
    }

    /** 按百分比缩放 ARGB 颜色的 alpha 通道。 */
    private fun withTextAlpha(color: Int, percent: Int): Int {
        val a = (android.graphics.Color.alpha(color) * percent / 100f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /** 在已有 alpha 上再乘一个 0~255 的系数，用于次行淡色。 */
    private fun scaleColorAlpha(color: Int, factor: Int): Int {
        val a = (android.graphics.Color.alpha(color) * factor / 255f).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * 给指定 OutlineTextView 设置文字颜色与可选描边。
     * 使用自定义 OutlineTextView 的 outlineEnabled/outlineColor/outlineWidthPx 属性，
     * 在 onDraw 中先画 STROKE 描边再画 FILL 填充，实现真正的多方向描边效果。
     * 文字颜色与描边颜色互相独立，描边粗细由设置中的 dp 值决定。
     */
    private fun applyTextColorTo(
        tv: OutlineTextView?,
        color: Int,
        outlineEnabled: Boolean,
        outlineColor: Int,
        outlineWidthDp: Int,
        outlinePosition: Int,
    ) {
        tv ?: return
        tv.setTextColor(color)
        tv.fillColor = color
        tv.outlineEnabled = outlineEnabled
        tv.outlineColor = outlineColor
        tv.outlineWidthPx = if (outlineEnabled) dp(outlineWidthDp).toFloat() else 0f
        tv.outlinePosition = outlinePosition
        tv.invalidate()
    }

    /**
     * 桌面歌词背景：保留旧版「黑色 + 12dp 圆角」的外观，不透明度由
     * 「背景透明度」设置（0~100，默认 80 即旧版 #CC000000）决定。
     */
    private fun applyBackground() {
        val show = prefs.desktopBackground
        if (show) {
            val alpha = (prefs.desktopBackgroundAlpha * 255f / 100f).toInt().coerceIn(0, 255)
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(alpha, 0, 0, 0))
                cornerRadius = dp(12).toFloat()
            }
            floatRoot?.background = bg
        } else {
            floatRoot?.background = null
        }
        lastBackground = show
        lastBackgroundAlpha = prefs.desktopBackgroundAlpha
    }

    /**
     * 桌面歌词左右边缘渐隐：把开关与强度（%）同步到内容层
     * （[DesktopLyricsFadeLayout]）。只改两个属性，窗口本身不重挂，
     * 因此拖动、滚动过程中切换设置不会闪。
     */
    private fun applyEdgeFade() {
        floatRoot?.edgeFadeEnabled = prefs.desktopEdgeFade
        floatRoot?.edgeFadeChars = prefs.desktopEdgeFadeChars
        lastEdgeFade = prefs.desktopEdgeFade
        lastEdgeFadeChars = prefs.desktopEdgeFadeChars
    }

    private fun applyWidth() {
        if (prefs.desktopAutoFitWidth) {
            // 自适应模式：按当前显示文本计算窗口宽度
            applyAutoFitWindowFor(
                textView?.text?.toString().orEmpty(),
                textViewSecond?.text?.toString().orEmpty()
            )
        } else {
            applyFixedWidth()
        }
        // 宽度变化后重新评估当前文本是否需要滚动、滚动距离是否变化
        scrollerMain.refresh()
        scrollerSecond.refresh()
        lastWidth = prefs.desktopWidth
        lastAutoFit = prefs.desktopAutoFitWidth
        lastAutoFitMax = prefs.desktopAutoFitMaxWidth
        lastAutoFitPadding = prefs.desktopAutoFitPadding
    }

    /** 固定宽度模式：窗口宽度取用户设定值，恢复默认左右边距。 */
    private fun applyFixedWidth() {
        val view = floatView ?: return
        val params = layoutParams ?: return
        val widthDp = prefs.desktopWidth
        val widthPx = if (widthDp <= 0) 0 else dp(widthDp)
        applyWindowWidth(view, params, widthPx)
        applyRootPadding(dp(DEFAULT_ROOT_PAD_DP))
    }

    /** 自适应模式：按两行文本的最大宽度调整窗口宽度与左右边距。 */
    private fun applyAutoFitWindowFor(main: String, second: String) {
        val view = floatView ?: return
        val params = layoutParams ?: return
        val pad = dp(prefs.desktopAutoFitPadding)
        val cap = autoFitCapPx()
        var textW = textView?.paint?.measureText(main) ?: 0f
        if (secondRowEnabled()) {
            textW = maxOf(textW, textViewSecond?.paint?.measureText(second) ?: 0f)
        }
        // 窗口宽度 = 文本宽 + 2×边距，钳制到最大宽度；超限时文本滚动显示
        val windowW = (textW.toInt() + pad * 2).coerceAtMost(cap)
        applyWindowWidth(view, params, windowW)
        applyRootPadding(pad)
    }

    /** 应用窗口宽度并按对齐锚点重定位；width<=0 表示 WRAP_CONTENT（不限宽）。 */
    private fun applyWindowWidth(view: View, params: WindowManager.LayoutParams, widthPx: Int) {
        params.width = if (widthPx <= 0) WindowManager.LayoutParams.WRAP_CONTENT else widthPx
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        // 宽度变化后按对齐锚点重新计算左上角位置，保持锚点视觉位置一致
        val effectiveWidth = if (widthPx <= 0) view.width else widthPx
        params.x = prefs.desktopOffsetX - anchorOffsetOf(prefs.desktopAlignment, effectiveWidth)
        params.y = prefs.desktopOffsetY
        clampWindowToScreen(view, params)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * 把悬浮窗钳制在屏幕内：FLAG_LAYOUT_NO_LIMITS 允许完全移出屏幕，
     * 一旦误拖或屏幕旋转导致窗口完全跑出视野，歌词会"消失"且难以找回。
     * 保证至少保留 [MIN_VISIBLE_DP] 宽/高的可见区域，让用户能拖回或重新交互。
     */
    private fun clampWindowToScreen(view: View, params: WindowManager.LayoutParams) {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val w = view.width.takeIf { it > 0 }
            ?: params.width.takeIf { it > 0 }
            ?: 0
        val h = view.height.takeIf { it > 0 }
            ?: params.height.takeIf { it > 0 }
            ?: 0
        val minVisible = dp(MIN_VISIBLE_DP)
        if (w > 0) {
            val minX = -w + minVisible
            val maxX = screenW - minVisible
            params.x = params.x.coerceIn(minX, maxX)
        }
        if (h > 0) {
            params.y = clampWindowY(params.y)
        }
    }

    /** 默认沿用原来的顶部边界；仅开启后允许通过负坐标向上划入状态栏。 */
    private fun clampWindowY(y: Int): Int {
        val minY = if (prefs.desktopAllowStatusBar) -statusBarHeightPx() else 0
        val maxY = (resources.displayMetrics.heightPixels - dp(MIN_VISIBLE_DP)).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }

    private fun statusBarHeightPx(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    /** 设置根布局左右边距（上下保持 10dp）。 */
    private fun applyRootPadding(padPx: Int) {
        floatRoot?.setPadding(padPx, dp(10), padPx, dp(10))
    }

    /** 自适应宽度的上限（px）：min(设置的最大宽度, 屏幕宽 90%)。 */
    private fun autoFitCapPx(): Int {
        val dm = resources.displayMetrics
        val maxDp = prefs.desktopAutoFitMaxWidth
        val byPref = if (maxDp <= 0) Int.MAX_VALUE else dp(maxDp)
        return minOf(byPref, (dm.widthPixels * 0.9f).toInt())
    }

    /**
     * 当前设置下歌词文本可视宽度（px）；null 表示不限宽（文本永不溢出，无需滚动）。
     * 自适应模式返回上限减去两边距：文本超过该宽度说明已触顶钳制，需要滚动。
     */
    private fun availableViewPx(): Int? {
        return if (prefs.desktopAutoFitWidth) {
            (autoFitCapPx() - dp(prefs.desktopAutoFitPadding) * 2).takeIf { it > 0 }
        } else {
            val widthDp = prefs.desktopWidth
            if (widthDp <= 0) return null
            (dp(widthDp) - dp(DEFAULT_ROOT_PAD_DP) * 2).takeIf { it > 0 }
        }
    }

    /** 是否启用第二行：用户设置双行，或双语歌词选择"两行都显示"（后者会额外增加高度）。 */
    private fun secondRowEnabled(): Boolean =
        prefs.desktopLineCount >= 2 || prefs.desktopBilingualLine == Prefs.BILINGUAL_BOTH

    private fun applyLineCount() {
        val count = prefs.desktopLineCount
        textViewSecond?.visibility = if (secondRowEnabled()) View.VISIBLE else View.GONE
        lastLineCount = count
        lastBilingualLine = prefs.desktopBilingualLine
        // 行数变化后：自适应窗口需按新文本集合重新计算，且次行内容需重新渲染
        if (prefs.desktopAutoFitWidth) applyWidth()
        currentLineIndex = Int.MIN_VALUE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 用户设置的对齐方式对应的文本重力。 */
    private fun alignGravity(): Int {
        val align = prefs.desktopAlignment
        return (when (align) {
            Prefs.ALIGN_LEFT -> Gravity.START
            Prefs.ALIGN_RIGHT -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }) or Gravity.CENTER_VERTICAL
    }

    private fun applyAlignment() {
        val align = prefs.desktopAlignment
        val gravity = alignGravity()
        textView?.gravity = gravity
        textViewSecond?.gravity = gravity
        // 根布局内的子 View 对齐
        floatRoot?.gravity = gravity
        // 切换对齐时保持视觉位置不变：把锚点从旧对齐的参照系换算到新对齐
        val old = lastAlignment
        if (old != -1 && old != align) {
            val w = floatView?.width ?: 0
            if (w > 0) {
                val leftEdge = prefs.desktopOffsetX - anchorOffsetOf(old, w)
                prefs.desktopOffsetX = leftEdge + anchorOffsetOf(align, w)
            }
        }
        lastAlignment = align
        applyPosition()
        // 滚动中的行被强制为左对齐（见 LineScroller.applyDisplay），对齐方式
        // 变化后需重新评估，把未溢出行恢复为用户对齐、溢出行保持左对齐
        if (this::scrollerMain.isInitialized) scrollerMain.refresh()
        if (this::scrollerSecond.isInitialized) scrollerSecond.refresh()
    }

    /** 对齐方式对应的锚点偏移：锚点 - 偏移 = 窗口左上角 x。 */
    private fun anchorOffsetOf(align: Int, width: Int): Int = when (align) {
        Prefs.ALIGN_CENTER -> width / 2
        Prefs.ALIGN_RIGHT -> width
        else -> 0
    }

    /**
     * 根据锚点与对齐方式更新窗口位置。
     * 左对齐：锚点为左缘；居中：锚点为中心；右对齐：锚点为右缘。
     */
    private fun updateWindowPosition(anchorX: Int, anchorY: Int) {
        val params = layoutParams ?: return
        val view = floatView ?: return
        params.x = anchorX - anchorOffsetOf(prefs.desktopAlignment, view.width)
        params.y = anchorY
        clampWindowToScreen(view, params)
        runCatching { windowManager.updateViewLayout(view, params) }
        updatePanelPosition()
    }

    private fun applyPosition() {
        updateWindowPosition(prefs.desktopOffsetX, prefs.desktopOffsetY)
        lastOffsetX = prefs.desktopOffsetX
        lastOffsetY = prefs.desktopOffsetY
        lastDesktopAllowStatusBar = prefs.desktopAllowStatusBar
    }

    /**
     * 显示状态提示并彻底清掉旧歌词：取消滚动动画、丢弃挂起文本，再立即贴新提示，
     * 避免旧歌词因动画/挂起文本继续残留在悬浮窗上。
     */
    private fun showStatusHint(hint: String) {
        val alreadyShown = currentLineIndex == NO_LYRIC_INDEX && hint == lastNoLyricHint
        if (!alreadyShown) {
            scrollerMain.cancel()
            scrollerSecond.cancel()
            currentLineIndex = NO_LYRIC_INDEX
            lastNoLyricHint = hint
            scrollerMain.display(hint)
            scrollerSecond.display("")
        }
        textView?.clearWordMask()
    }

    private fun updateLyric() {
        // 没有任何播放来源时，状态优先于歌词内容：即便 Session 里还残留上一首
        // 的歌词行，桌面歌词也必须清掉旧歌词并显示「没有检测到播放」。
        if (!MusicProgress.hasPlaybackSession) {
            lastAdjustedPosMs = -1L
            currentWordWords = null
            showStatusHint(getString(R.string.desktop_no_playback))
            return
        }
        // 正在匹配新歌：优先显示状态提示，不继续滚动上一首歌的歌词。
        // 匹配结束（拿到新歌词 / 判定无匹配）后由列表引用或切歌版本号触发重新渲染。
        if (Session.matchState == LyricMatchState.MATCHING ||
            Session.matchState == LyricMatchState.FETCHING_ONLINE
        ) {
            lastAdjustedPosMs = -1L
            currentWordWords = null
            val hint = if (Session.matchState == LyricMatchState.FETCHING_ONLINE)
                getString(R.string.desktop_fetching_online_lyric)
            else getString(R.string.desktop_matching_lyric)
            showStatusHint(hint)
            return
        }
        val lines = Session.lyricLines
        // 切歌检测：
        // 1. 歌词列表引用被替换（自动匹配到不同歌/打开新文件）
        // 2. 歌曲切换版本号递增（含列表循环同一首歌重放——此时歌词列表引用不变，
        //    tryAutoMatch() 因 key==matchedKey 提前返回，仅靠引用比较检测不到）
        val songChanged = (Session.songChangeVersion != lastSongChangeVersion)
        if (lines !== lastLines || songChanged) {
            lastLines = lines
            lastSongChangeVersion = Session.songChangeVersion
            // 强制重置行号，并打断正在进行的长歌词滚动动画
            currentLineIndex = Int.MIN_VALUE
            scrollerMain.cancel()
            scrollerSecond.cancel()
            lastAdjustedPosMs = -1L
        }
        if (lines.isNullOrEmpty()) {
            lastAdjustedPosMs = -1L
            currentWordWords = null
            // 没有歌词行时按自动匹配状态给出明确提示：
            // 正在匹配 →「正在匹配歌词」；正在在线获取 →「无匹配歌词，正在在线获取歌词」；
            // 匹配结束仍无结果 →「无匹配歌词」；其它情况沿用原来的「暂无歌词」。
            val hint = when (Session.matchState) {
                LyricMatchState.MATCHING -> getString(R.string.desktop_matching_lyric)
                LyricMatchState.FETCHING_ONLINE -> getString(R.string.desktop_fetching_online_lyric)
                LyricMatchState.NO_MATCH -> getString(R.string.desktop_no_match_lyric)
                else -> getString(R.string.desktop_no_lyric)
            }
            showStatusHint(hint)
            return
        }
        // 点击歌词后的高亮冻结若已被播放时间越过下一行，先强行解除，
        // 避免下一行的高亮在桌面歌词上也被整段跳过。
        releaseLyricFreezeIfPassedNextLine(lines)
        // 循环播放取模、应用内 seek 宽限期对齐，与逐字蒙版共用同一份修正进度
        val pos = adjustedPositionMs()
        // 进度真实回退（app 内跳转 / 双边对齐 / 播放器内跳转）：歌词行会退回去，
        // 长歌词的横向滚动也必须倒退同样的时间量，否则长句仍停在行尾与音频错开。
        val prevPos = lastAdjustedPosMs
        val rewindMs = if (prevPos >= 0L && pos < prevPos - SCROLL_REWIND_MIN_MS) {
            prevPos - pos
        } else 0L
        lastAdjustedPosMs = pos
        var idx = findLineIndex(lines, pos)
        // seek 未落地期间把高亮钉在被点击的这一行，避免关键帧对齐 / 偏移
        // 让修正后的进度落到上一行（表现为高亮了点击行以外的歌词行）
        if (MusicProgress.isSeekPending()) {
            val clickedTime = MusicProgress.seekPendingLineMs
            if (clickedTime >= 0L) {
                val pinned = findLineIndex(lines, clickedTime)
                if (pinned >= 0 && lines[pinned].time == clickedTime) idx = pinned
            }
        }
        // 防回跳：进度估算在行边界附近抖动时，小幅度回退保持当前行，
        // 避免桌面歌词在当前句与相邻句之间来回跳。真实 seek 回退幅度大，不受影响。
        if (idx >= 0 && currentLineIndex in lines.indices && idx < currentLineIndex) {
            val backAmount = lines[currentLineIndex].time - pos
            if (backAmount in 1..LINE_FLICKER_GUARD_MS) {
                idx = currentLineIndex
            }
        }
        // 当前主行的逐字轴：长行横向滚动与逐字蒙版共用同一份，必须在
        // displayLine（会据此决定滚动模式）之前算好。空行不做逐字动画。
        currentWordWords = if (prefs.desktopWordByWord) {
            val mainIdx = mainLineIndex(lines, idx)
            if (lines.getOrNull(mainIdx)?.text.isNullOrBlank()) {
                null
            } else {
                WordTimingEstimator.effectiveWords(
                    lines, mainIdx, prefs.desktopForceWordByWord
                )?.takeIf { it.isNotEmpty() }
            }
        } else null
        if (idx != currentLineIndex) {
            val prevIndex = currentLineIndex
            // seek 或切歌导致的行号大跳跃（回退或跨越多行）：
            // 强制打断正在进行的长歌词滚动动画，让目标行立即显示。
            // 正常前进到下一行（idx == prevIndex + 1）时不打断，保持"滚完再切"的体验。
            val isJump = idx < prevIndex || (idx - prevIndex) > 1 ||
                SystemClock.elapsedRealtime() < MusicProgress.seekUiTargetUntil
            if (isJump) {
                scrollerMain.cancel()
                scrollerSecond.cancel()
            }
            currentLineIndex = idx
            // 传入同一份列表快照：Session.lyricLines 可能被通知监听线程随时替换，
            // 重新读取会导致行号与新列表错位、getOrNull 落空而瞬时显示空白
            displayLine(idx, lines, pos)
            // 歌词前进到更后的行且时长缺失（网络波动导致元数据缺时长）：
            // 触发一次时长兜底重取（内部限流），恢复时长钳制与匹配
            if (idx > prevIndex) MusicNotificationListenerService.refreshMissingDuration()
        } else if (rewindMs > 0L && idx >= 0) {
            // 同行内的回退：行号没变，display() 会因文本相同直接跳过，必须显式
            // 把滚动位置按回退时长倒退；回退到其它行时上面的 isJump 已从行首重滚。
            scrollerMain.rewindBy(rewindMs)
            if (secondRowEnabled()) scrollerSecond.rewindBy(rewindMs)
        }
        // 逐字轴有无变化（开关 / 强行兼容切换）时即时启停逐字驱动滚动，不必等下一行
        scrollerMain.syncWordDriven(currentWordWords)
        // 逐字蒙版：只绑定当前行的字轴，进度由 View 逐帧从 [adjustedPositionMs] 读取
        // （带逐字时间戳，或开启「桌面歌词强行兼容逐字动画」后按整行时长推算）
        if (prefs.desktopWordByWord) refreshDesktopWordMask(lines, idx)
        else textView?.clearWordMask()
    }

    /**
     * 点击歌词产生的高亮冻结：播放时间已经推进到下一行（下一句真正不同的
     * 时间戳）时强行解除，避免下一行的高亮被跳过。暂停时不解除。
     */
    private fun releaseLyricFreezeIfPassedNextLine(lines: List<LyricLine>) {
        if (!MusicProgress.isPlaying) return
        val target = MusicProgress.lyricFreezeTargetMs()
        if (target < 0L) return
        val pinnedIdx = findLineIndex(lines, target)
        if (pinnedIdx !in lines.indices) return
        // 双语组共用一个时间戳：跳过整组，找下一句真正不同的歌词
        var nextIdx = pinnedIdx + 1
        while (nextIdx < lines.size && lines[nextIdx].time == lines[pinnedIdx].time) {
            nextIdx++
        }
        val nextTime = lines.getOrNull(nextIdx)?.time ?: return
        var raw = MusicProgress.currentPositionMs()
        val dur = MusicProgress.durationMs
        if (dur > 0L && raw > dur) raw %= dur
        raw = (raw - PlaybackOffset.delayMs(prefs)).coerceAtLeast(0L)
        if (raw >= nextTime) MusicProgress.releaseLyricFreeze()
    }

    /**
     * 修正后的实时播放进度：循环播放按总时长取模，点击后由应用自身计时推进。
     * 行号判定与逐字蒙版共用这一份进度，避免两者错位；「毫秒信息不可信」应用的整秒
     * 自计时期间会冻结在目标，暂停时 [MusicProgress] 不推进。
     */
    private fun adjustedPositionMs(): Long {
        var pos = MusicProgress.displayPositionMs()
        val dur = MusicProgress.durationMs
        if (dur > 0L && pos > dur) pos %= dur
        // seek 宽限期内把进度对齐到目标位置，所有应用统一处理
        if (SystemClock.elapsedRealtime() < MusicProgress.seekUiTargetUntil &&
            pos < MusicProgress.seekUiTargetMs
        ) {
            pos = MusicProgress.seekUiTargetMs
        }
        // 整体偏移：正值延后歌词、负值提前
        return (pos - PlaybackOffset.delayMs(prefs)).coerceAtLeast(0L)
    }

    /**
     * 给主行绑定逐字蒙版：已唱部分使用「已唱歌词颜色」，未唱部分使用文字颜色的
     * 半透明版本。是否具备逐字轴由 [WordTimingEstimator] 统一判定，进度算法由
     * [WordMaskProgress] 与主界面共用，实际绘制交给 [OutlineTextView] 逐帧完成。
     *
     * 这里每 200ms tick 只做「绑定/换绑」，不再重建文本或 Spannable，
     * 因此不会触发重新布局，也不会出现整字跳变。
     */
    private fun refreshDesktopWordMask(lines: List<LyricLine>?, idx: Int) {
        val tv = textView ?: return
        if (lines.isNullOrEmpty()) {
            tv.clearWordMask()
            return
        }
        val line = lines.getOrNull(mainLineIndex(lines, idx)) ?: return
        val raw = line.text
        val text = raw.takeIf { it.isNotBlank() } ?: prefs.emptyLineText().orEmpty()
        // 与长行横向滚动共用同一份字轴（判定集中在 updateLyric），避免两处
        // 各自推算导致「高亮位置」与「滚动位置」依据的时间轴不一致。
        val words = currentWordWords
        if (words.isNullOrEmpty() || text.isEmpty()) {
            tv.clearWordMask()
            return
        }
        val alphaPercent = prefs.desktopTextAlpha
        val sungColor = withTextAlpha(prefs.desktopSungTextColor, alphaPercent)
        val dimColor = withTextAlpha(
            (prefs.desktopTextColor and 0x00FFFFFF) or (0x66 shl 24), alphaPercent
        )
        tv.setWordMask(
            words = words,
            expectedText = text,
            sungColor = sungColor,
            dimColor = dimColor,
            softTransition = prefs.wordSoftTransition,
            softStrength = prefs.wordSoftTransitionStrength,
            provider = ::adjustedPositionMs,
        )
    }

    /**
     * 渲染指定行（含空行替换与滚动控制）。
     * idx == -1 表示进度在首行时间戳之前（前奏、刚切歌、seek 回开头）：
     * 此时显示即将唱到的首行而非空白，避免桌面歌词文字消失。
     * 文本超宽时由 LineScroller 接管滚动：有逐字轴时跟随高亮字符位置，
     * 无逐字轴时按当前行剩余时长自适应匀速，保证一次滚动在该行唱完前完成，
     * 否则超长行会迟迟赖在桌面歌词上，而实际进度早已进入后面的行。
     */
    private fun displayLine(idx: Int, lines: List<LyricLine>?, pos: Long) {
        val empty = prefs.emptyLineText()
        val safeIdx = if (idx < 0) 0 else idx
        // 双语歌词：同一时间戳的连续多行视为一组，按设置决定显示第一行/第二行/两行
        val group = groupRangeOf(lines, safeIdx)
        val groupSize = group.last - group.first + 1
        val mode = prefs.desktopBilingualLine
        val mainIdx = mainLineIndex(lines.orEmpty(), idx)
        // 次行对应的歌词行号：除了取文本，也用来生成「行身份」——相邻两行
        // 文本完全相同时（重复副歌）仍要让滚动知道已经换到下一句。
        val secondIdx = when {
            // 双语"两行都显示"且确实存在第二行：次行显示组内最后一行（翻译）
            mode == Prefs.BILINGUAL_BOTH && groupSize >= 2 -> group.last
            prefs.desktopLineCount >= 2 -> group.last + 1
            else -> -1
        }
        val rawMain = lines?.getOrNull(mainIdx)?.text.orEmpty()
        val main = if (rawMain.isBlank() && empty != null) empty else rawMain
        val rawSecond = if (secondIdx >= 0) lines?.getOrNull(secondIdx)?.text.orEmpty() else ""
        val second = if (rawSecond.isBlank() && empty != null) empty else rawSecond
        // 当前行剩余展示时长：到下一时间戳行为止（无下一行则不限速，用自然速度）
        val nextTime = lines?.getOrNull(group.last + 1)?.time
        val remainingMs = if (nextTime != null) (nextTime - pos).coerceAtLeast(0L)
        else Long.MAX_VALUE
        // 自适应模式：先按新文本调整窗口宽度，再决定是否滚动（保证可视宽度一致）
        // 进度还在首行之前（前奏 / 刚切歌 / seek 回开头）：只把即将唱到的首行
        // 显示出来，但不允许开始横向滚动——否则第一句还没唱到就已经滚到末尾。
        val allowScroll = idx >= 0
        if (prefs.desktopAutoFitWidth) applyAutoFitWindowFor(main, second)
        scrollerMain.display(main, remainingMs, lineKeyOf(lines, mainIdx), allowScroll)
        if (secondRowEnabled()) {
            scrollerSecond.display(second, remainingMs, lineKeyOf(lines, secondIdx), allowScroll)
        } else {
            scrollerSecond.display("")
        }
    }

    /**
     * 一句歌词在列表里的身份（时间戳 + 行号）。只看文本无法区分「同一句被重复渲染」
     * 和「换到了文本一模一样的下一句」，后者常见于重复副歌（例如连续两句
     * "扁担宽 板凳长 扁担想绑在板凳上"）。时间戳用高位、行号用低 20 位，
     * 同一时间戳的双语组靠行号区分；越界（无对应歌词行）返回 null。
     */
    private fun lineKeyOf(lines: List<LyricLine>?, index: Int): Long? {
        val line = lines?.getOrNull(index) ?: return null
        return (line.time shl 20) xor (index.toLong() and 0xFFFFFL)
    }

    /** 返回 [idx] 所在的双语组范围（连续相同时间戳的行）；越界时返回仅包含 idx 的退化范围。 */
    private fun groupRangeOf(lines: List<LyricLine>?, idx: Int): IntRange {
        if (lines.isNullOrEmpty()) return IntRange.EMPTY
        val safe = idx.coerceIn(0, lines.size - 1)
        val t = lines[safe].time
        var start = safe
        while (start > 0 && lines[start - 1].time == t) start--
        var end = safe
        while (end < lines.size - 1 && lines[end + 1].time == t) end++
        return start..end
    }

    /** [idx] 所在双语组里主行应取的行号（由双语显示设置决定取组内首行还是末行）。 */
    private fun mainLineIndex(lines: List<LyricLine>, idx: Int): Int {
        if (lines.isEmpty()) return 0
        val safeIdx = if (idx < 0) 0 else idx.coerceAtMost(lines.size - 1)
        val group = groupRangeOf(lines, safeIdx)
        val mode = prefs.desktopBilingualLine
        return if (mode == Prefs.BILINGUAL_FIRST || mode == Prefs.BILINGUAL_BOTH) {
            group.first
        } else {
            group.last
        }
    }

    /** 二分查找当前进度对应的歌词行。 */
    private fun findLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].time <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /** 停止主/次行滚动动画与挂起回调（关闭悬浮窗或服务销毁时调用）。 */
    private fun stopScrollers() {
        if (this::scrollerMain.isInitialized) scrollerMain.cancel()
        if (this::scrollerSecond.isInitialized) scrollerSecond.cancel()
    }

    /**
     * 单行文本横向滚动控制器：替代系统跑马灯。
     *
     * 文本超出可视宽度时滚动到行尾；滚动过程中到来的新行会挂起（[pendingText]），
     * 等上一行滚动完毕才切换——保证"跳到下一行前，起码把上一行滚动完"。长行
     * 只滚动一遍：滚到行尾后停在末尾，不再回到行首反复滚动（循环滚动已移除）。
     *
     * 两种驱动模式：主行有逐字轴且逐字动画开启时，滚动位置由当前高亮字符决定
     * （[applyWordDrivenScroll]），与逐字蒙版共享同一条时间轴；没有逐字轴时
     * 走行首停留 + 匀速 [ValueAnimator]（[beginAnimate]）。歌词进度回退时，
     * 前者重启帧回调自然倒退，后者按回退时长倒退像素（见 [rewindBy]）。
     *
     * 是否滚动的判定是同步且确定性的：可视宽度直接用与窗口宽度同源的计算值
     * （[availableViewPx]：固定宽度=窗口宽-边距，自适应=上限-边距），布局完成后
     * 视图实测宽度必然等于该值。若改用 tv.post 后的实测宽度判定，post 的 runnable
     * 会在 performTraversals 早期执行、读到布局前的旧宽度——误判"未溢出"会关掉
     * 横向滚动，singleLine 文本被硬裁剪；误判距离则会把文字整个滚出可视区域。
     */
    private inner class LineScroller(
        private val tv: TextView,
        /** 是否允许用主行逐字轴驱动横向滚动（次行没有对应字轴，恒为 false）。 */
        private val wordDriven: Boolean,
    ) {

        /** 一次滚动是否已结束（true 表示可立即切换新行）。 */
        var settled = true
            private set

        /** 滚动过程中挂起的新文本：等本次滚动结束后切换。 */
        private var pendingText: String? = null

        /** 挂起文本入队时的剩余时长与时刻，应用时折算成当时的真实剩余。 */
        private var pendingRemainingMs = Long.MAX_VALUE
        private var pendingAtMs = 0L

        /** 挂起文本入队时是否允许横向滚动。 */
        private var pendingAllowScroll = true

        /** 当前文本是否允许横向滚动：进度未到首行（前奏）时禁止，避免未唱先滚完。 */
        private var scrollAllowed = true

        /** 当前内容应在其时间内滚完的剩余时长（Long.MAX_VALUE = 不限速）。 */
        private var remainingMs = Long.MAX_VALUE

        /** 当前内容开始显示的时刻（elapsedRealtime），用于折算剩余时长。 */
        private var displayAtMs = 0L

        /** 本次滚动的总距离与匀速时长：进度回退时按「回退时长 × 速度」倒退像素。 */
        private var scrollDistancePx = 0f
        private var scrollDurationMs = 0L

        private var animator: ValueAnimator? = null
        private var holdRunnable: Runnable? = null

        /** 逐字驱动：当前行字轴；null 表示回退到匀速 ValueAnimator。 */
        private var wordAxis: WordMaskProgress.Axis? = null

        /** 逐字帧回调是否已注册（模式切换判定）。 */
        private var wordFrameActive = false

        /**
         * 本帧是否已排队下一次回调。滚到行尾结算时 [settleScroll] 可能立刻
         * [applyDisplay] 并重挂载帧回调，此标记保证同一帧只留一个回调，
         * 否则 Choreographer 队列里同一对象会被重复投递、每帧翻倍。
         */
        private var wordFrameScheduled = false

        /**
         * 逐字驱动的帧时钟：每帧按 [WordMaskProgress.sungOffset] 得到的高亮字符
         * 位置决定 scrollX，与桌面蒙版共用同一份修正进度和同一套字轴插值。
         */
        private val wordFrameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!wordFrameActive) return
                wordFrameScheduled = false
                applyWordDrivenScroll()
                if (wordFrameActive && !wordFrameScheduled) {
                    wordFrameScheduled = true
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }

        /** 代际计数：display/cancel 时递增，使旧动画与延迟回调全部失效。 */
        private var gen = 0

        /** 当前显示内容对应的歌词行身份；null = 无身份（无歌词提示、空次行、已取消）。 */
        private var lineKey: Long? = null

        /**
         * 显示文本。[remainingMs] 为该行的剩余展示时长（到下一行时间戳），
         * 用于决定滚动速度：行将结束时还没滚完则加速，保证唱完前滚完。
         * 若正在滚动中，文本进入挂起队列，滚动完毕后切换。
         *
         * 文本与当前显示（或已挂起）内容相同时直接跳过，避免强制重渲染路径
         * （列表对象替换、行号在首行前/首行之间过渡）对同一句重启滚动，造成
         * 行首停留 + 重新滚动的文字闪断。
         *
         * 但「文本相同」不等于「还是同一句」：连续两句完全一样的歌词（重复副歌）
         * 必须重开一遍滚动，否则第二行会沿用第一行滚到行尾的 scrollX、沿用旧字轴，
         * 表现为长歌词停在行尾不回到开头。[lineKey] 由 [lineKeyOf] 提供，用它
         * 区分这两种情况；身份变化时即使文本相同也走重启 / 挂起流程。
         */
        fun display(
            text: String,
            remainingMs: Long = Long.MAX_VALUE,
            lineKey: Long? = null,
            allowScroll: Boolean = true,
        ) {
            val newLine = lineKey != null && lineKey != this.lineKey
            if (lineKey != null) this.lineKey = lineKey
            val allowChanged = allowScroll != scrollAllowed
            scrollAllowed = allowScroll
            if (newLine && text == tv.text?.toString().orEmpty()) {
                LogCapture.d("Lyrics", "scroll: same text on new line, restart scroll")
            }
            if (!settled) {
                // 滚动许可变化（前奏 → 首行开始唱 / 反向前奏）：立即重渲染，
                // 该停就停在行首，该滚就重新起滚，不能继续沿用旧动画。
                if (allowChanged) {
                    applyDisplay(text, remainingMs)
                    return
                }
                if (newLine || (pendingText != text && tv.text?.toString().orEmpty() != text)) {
                    pendingText = text
                    pendingRemainingMs = remainingMs
                    pendingAtMs = SystemClock.elapsedRealtime()
                    pendingAllowScroll = allowScroll
                    // 过期保护：当前行分配的展示时间已耗尽（播放进度早已进入新
                    // 行），但旧行的滚动动画仍未结束（延迟回调丢失、宽度变化等）
                    // ——不能再无限挂起，立即终止旧动画切到新行，
                    // 否则歌词会一直停在旧行，表现为"歌词卡住"。
                    if (currentLineExpired()) {
                        LogCapture.d("Lyrics", "scroll expired：强制切到新行")
                        applyDisplay(text, remainingMs)
                    }
                }
                return
            }
            if (allowChanged || newLine || tv.text?.toString().orEmpty() != text) {
                applyDisplay(text, remainingMs)
            }
        }

        /** 当前内容分配的展示时间是否已耗尽（[remainingMs] 为不限时则永不过期）。 */
        private fun currentLineExpired(): Boolean {
            if (remainingMs == Long.MAX_VALUE) return false
            return SystemClock.elapsedRealtime() - displayAtMs >= remainingMs
        }

        /** 视图宽度等外部条件变化后，重新评估当前（或挂起）文本的滚动状态。 */
        fun refresh() {
            val pending = pendingText
            if (pending != null) {
                scrollAllowed = pendingAllowScroll
                applyDisplay(pending, pendingLeftMs())
            } else {
                // 重渲染当前文本，剩余时长按已显示时间折算
                val left = (remainingMs - (SystemClock.elapsedRealtime() - displayAtMs))
                    .coerceAtLeast(0L)
                applyDisplay(tv.text?.toString().orEmpty(), left)
            }
        }

        /** 停止滚动并复位。 */
        fun cancel() {
            gen++
            stopWordFrames()
            wordAxis = null
            animator?.cancel()
            animator = null
            holdRunnable?.let { tv.removeCallbacks(it) }
            holdRunnable = null
            pendingText = null
            pendingAllowScroll = true
            scrollAllowed = true
            settled = true
            scrollDistancePx = 0f
            scrollDurationMs = 0L
            lineKey = null
            tv.scrollTo(0, 0)
        }

        /** 挂起文本折算到当前时刻的剩余时长。 */
        private fun pendingLeftMs(): Long =
            (pendingRemainingMs - (SystemClock.elapsedRealtime() - pendingAtMs)).coerceAtLeast(0L)

        private fun applyDisplay(text: String, remaining: Long = Long.MAX_VALUE) {
            gen++
            val myGen = gen
            stopWordFrames()
            // 新行到达时决定本次滚动模式：主行且有匹配的逐字轴 → 跟随高亮；否则匀速。
            // 「进度未到首行」只是不启动滚动动画，仍绑定字轴用于蒙版着色，
            // 保持文字颜色/描边与滚动状态无关。
            wordAxis = wordAxisFor(currentWordWords, text)
            animator?.cancel()
            animator = null
            holdRunnable?.let { tv.removeCallbacks(it) }
            holdRunnable = null
            pendingText = null
            remainingMs = remaining
            displayAtMs = SystemClock.elapsedRealtime()
            scrollDistancePx = 0f
            scrollDurationMs = 0L
            tv.scrollTo(0, 0)
            // 横向滚动模式只看文本是否溢出，与「是否允许滚动」解耦：
            // 前奏静止显示时也必须保持与滚动状态一致的 Layout/gravity，
            // 否则 TextView 的原生绘制与手绘描边使用不同的平移基准，描边会错位。
            val avail = availableViewPx()
            val overflow = text.isNotEmpty() && avail != null &&
                tv.paint.measureText(text) > avail + OVERFLOW_FUDGE_PX
            tv.setHorizontallyScrolling(overflow)
            // XML 里 singleLine=true 会隐式带上 ellipsize=END，且部分系统版本
            // 在横向滚动模式下仍按视图宽度截断文本并追加"…"（实测 Layout 宽 2^20
            // 而 lineRight 仅 616px），导致滚动到截断点之后整行空白。
            // 溢出滚动由我们手动控制，ellipsize 必须显式清除。
            tv.ellipsize = null
            // 关键：横向滚动时 TextView 内部 Layout 宽度是巨大值（实测 2^20），
            // 居中/右对齐会把文本绘制到 Layout 中部/右端（x≈52 万像素处），
            // 而视口最多只能 scrollTo(textW-avail)（千级像素）——永远够不到文本，
            // 表现为滚动正常但文字彻底消失。滚动时必须强制左对齐让文本从 x=0 绘制。
            tv.gravity = if (overflow) Gravity.START or Gravity.CENTER_VERTICAL
                         else alignGravity()
            tv.text = text
            if (!scrollAllowed || !overflow) {
                // 前奏 / 未溢出：停在行首，不进入任何滚动或逐字位移动画
                tv.scrollTo(0, 0)
                settled = true
                return
            }
            settled = false
            if (wordAxis != null) {
                // 逐字驱动：位置由高亮字符决定，行首停留也交给「第一个字唱到哪」
                // 自然决定，不再跑匀速动画与固定 HOLD_START_MS。
                startWordFrames()
            } else {
                startPass(myGen)
            }
        }

        /** 一次完整滚动：行首停留 → 匀速滚动到行尾 → 结算（切行或停在行尾）。 */
        private fun startPass(myGen: Int) {
            settled = false
            val hold = Runnable {
                if (myGen != gen) return@Runnable
                beginAnimate(myGen)
            }
            holdRunnable = hold
            tv.postDelayed(hold, HOLD_START_MS)
        }

        private fun beginAnimate(myGen: Int) {
            if (myGen != gen) return
            // 动画在行首停留（HOLD_START_MS）后启动，此时布局已完成，实测宽度可靠；
            // 极端时序下实测仍不可用时，回退到与窗口同源的计算宽度，不再无限重试
            val actualAvailable = (tv.width - tv.paddingLeft - tv.paddingRight)
                .takeIf { it > 0 } ?: availableViewPx() ?: 0
            val textW = tv.paint.measureText(tv.text.toString())
            val distance = (textW - actualAvailable).coerceAtLeast(0f)
            if (actualAvailable <= 0 || distance <= 0f) {
                // 实际未溢出：关闭滚动恢复对齐，并消费可能挂起的新行
                tv.setHorizontallyScrolling(false)
                tv.gravity = alignGravity()
                tv.scrollTo(0, 0)
                settled = true
                val pending = pendingText
                if (pending != null) {
                    scrollAllowed = pendingAllowScroll
                    applyDisplay(pending, pendingLeftMs())
                }
                return
            }
            val passDurationMs = passDurationFor(distance)
            scrollDistancePx = distance
            scrollDurationMs = passDurationMs
            startScroll(myGen, 0f, passDurationMs, distance)
        }

        /**
         * 从 [fromX] 匀速滚到行尾：正常起滚与进度回退后的续滚共用。
         * 动画期间每帧按实测宽度钳制滚动上限，避免窗口宽度变化把文字滚出可视区域。
         */
        private fun startScroll(myGen: Int, fromX: Float, durationMs: Long, distance: Float) {
            val textW = tv.paint.measureText(tv.text.toString())
            val anim = ValueAnimator.ofFloat(fromX, distance)
            anim.duration = durationMs
            anim.interpolator = LinearInterpolator()
            anim.addUpdateListener { a ->
                if (myGen != gen) return@addUpdateListener
                val liveAvail = (tv.width - tv.paddingLeft - tv.paddingRight)
                    .takeIf { it > 0 } ?: availableViewPx() ?: 0
                val maxScroll = (textW - liveAvail).coerceAtLeast(0f)
                val x = (a.animatedValue as Float).coerceIn(0f, maxScroll)
                tv.scrollTo(x.toInt(), 0)
            }
            anim.addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled || myGen != gen) return
                    animator = null
                    val pending = pendingText
                    if (pending != null) {
                        // 上一行已滚动完毕：立即切换挂起的新行
                        scrollAllowed = pendingAllowScroll
                        applyDisplay(pending, pendingLeftMs())
                    } else {
                        // 滚完一遍即停在行尾，不再回到行首循环滚动。
                        // settled = true 让下一行到达时由 display() 立即切换，
                        // 避免实际进度已进入下一行时，上一行又快速重滚一遍才切走。
                        holdRunnable = null
                        settled = true
                    }
                }
            })
            animator = anim
            anim.start()
        }

        /**
         * 歌词进度回退：逐字驱动模式重启帧回调，由回退后的高亮位置自然倒退；
         * 匀速模式把已滚过的距离按回退时长倒退，再按原速继续滚到行尾。
         *
         * 匀速时滚动位置与「行内已过时间」成正比，所以回退时长 × 本次速度
         * 就是要倒退的像素；用 `tv.scrollX` 取当前位置，动画进行中与已停在
         * 行尾都适用。文本不变、不重走行首停留，也不会整段重滚；回退量超过
         * 一整遍时自然回到行首。长行「只滚一遍」的不变量保持不变。
         */
        fun rewindBy(deltaMs: Long) {
            if (deltaMs <= 0L || !scrollAllowed) return
            if (wordAxis != null) {
                // 滚到行尾后帧回调已停；重启即可按新进度把 scrollX 拉回去。
                // settled 置回 false，让本次滚动重新走一遍「到行尾」的结算。
                pendingText = null
                settled = false
                startWordFrames()
                return
            }
            val distance = scrollDistancePx
            val totalMs = scrollDurationMs
            if (distance <= 0f || totalMs <= 0L) return
            val currentX = tv.scrollX.toFloat()
            val pxPerMs = distance / totalMs
            val fromX = (currentX - deltaMs.toFloat() * pxPerMs).coerceAtLeast(0f)
            if (fromX >= currentX - 1f) return
            gen++
            val myGen = gen
            animator?.cancel()
            animator = null
            holdRunnable?.let { tv.removeCallbacks(it) }
            holdRunnable = null
            // 进度已回退，原本挂起等待的下一行不该再抢先切换
            pendingText = null
            tv.scrollTo(fromX.toInt(), 0)
            settled = false
            val leftMs = ((distance - fromX) / pxPerMs).toLong()
                .coerceAtLeast(1L).coerceAtMost(MAX_SCROLL_MS)
            startScroll(myGen, fromX, leftMs, distance)
        }

        /**
         * 构建本次滚动可用的字轴：只有主行、开关开启、且字轴拼出的文本与当前
         * 显示文本长度一致才启用逐字驱动。长度不符（占位文本、列表替换等）时
         * 一律降级匀速，避免旧字轴配新歌词造成高亮与滚动错位。
         */
        private fun wordAxisFor(words: List<LyricWord>?, text: String): WordMaskProgress.Axis? {
            if (!wordDriven || words.isNullOrEmpty() || text.isEmpty()) return null
            val axis = WordMaskProgress.build(words)
            return axis.takeIf { !it.isEmpty && it.fullTextLen == text.length }
        }

        /**
         * 每个 tick 同步逐字轴：字轴「有无」变化时即时切换滚动模式，不必等下一行。
         * 切换时保留当前 scrollX，避免文字跳回行首；已滚到行尾（settled）的本行
         * 不再重启，下一行会按新模式起滚。模式未变时只更新字轴引用。
         */
        fun syncWordDriven(words: List<LyricWord>?) {
            // 前奏阶段禁止滚动：不绑定逐字轴，等进度到首行由 applyDisplay 重新起滚。
            if (!scrollAllowed) return
            // 新行已挂起时当前字轴仍属于上一行，不能提前替换，否则旧行会按
            // 新行的高亮位置错滚；等滚动结束 applyDisplay(pending) 再换新轴。
            // 已滚到行尾的本行同样不再改轴：保持原轴让 rewindBy 还能倒退，
            // 新模式由下一行的 applyDisplay 决定。
            if (pendingText != null || settled) return
            val axis = wordAxisFor(words, tv.text?.toString().orEmpty())
            val hadAxis = wordAxis != null
            wordAxis = axis
            if (hadAxis == (axis != null)) return
            if (axis != null) {
                // 匀速 → 逐字：取消动画与行首停留，保留当前滚动位置
                gen++
                animator?.cancel()
                animator = null
                holdRunnable?.let { tv.removeCallbacks(it) }
                holdRunnable = null
                scrollDistancePx = 0f
                scrollDurationMs = 0L
                settled = false
                startWordFrames()
                LogCapture.d("Lyrics", "scroll: word-driven on")
            } else {
                // 逐字 → 匀速：从当前位置按剩余时长续滚到行尾
                stopWordFrames()
                resumeTimedScroll()
                LogCapture.d("Lyrics", "scroll: word-driven off")
            }
        }

        private fun startWordFrames() {
            if (wordFrameActive) return
            wordFrameActive = true
            wordFrameScheduled = true
            Choreographer.getInstance().postFrameCallback(wordFrameCallback)
        }

        private fun stopWordFrames() {
            if (!wordFrameActive) return
            wordFrameActive = false
            wordFrameScheduled = false
            Choreographer.getInstance().removeFrameCallback(wordFrameCallback)
        }

        /**
         * 逐字驱动的一帧：把「当前唱到的字符」换算成文本内 x，让高亮前缘保持在
         * 视口 [WORD_ANCHOR_FRACTION] 处；滚到行尾即结算，只滚一遍。
         *
         * 字轴插值本身连续（[WordMaskProgress.sungOffset] 在相邻两字之间线性
         * 插值），所以滚动天然连续：唱得快滚得快、长音时停住等字，进度回退时
         * 高亮回退也会把文字倒着滚回去，不需要再按墙钟估算像素。
         */
        private fun applyWordDrivenScroll() {
            // 前奏（进度未到首行）禁止滚动：已排队的帧直接停掉，保持在行首
            if (!scrollAllowed) {
                stopWordFrames()
                tv.scrollTo(0, 0)
                return
            }
            val axis = wordAxis
            if (axis == null || axis.isEmpty) {
                stopWordFrames()
                return
            }
            val text = tv.text?.toString().orEmpty()
            val avail = (tv.width - tv.paddingLeft - tv.paddingRight)
                .takeIf { it > 0 } ?: availableViewPx() ?: 0
            if (text.isEmpty() || avail <= 0) return
            val textW = tv.paint.measureText(text)
            val distance = (textW - avail).coerceAtLeast(0f)
            if (distance <= 0f) {
                tv.scrollTo(0, 0)
                settleScroll()
                return
            }
            val sungX = sungPixelX(axis, adjustedPositionMs(), text, textW)
            val target = (sungX - avail * WORD_ANCHOR_FRACTION).coerceIn(0f, distance)
            tv.scrollTo(target.toInt(), 0)
            if (target >= distance - 1f) settleScroll()
        }

        /**
         * 浮点字符偏移 → 文本内像素 x。用 paint 前缀测量而不是 Layout：横向滚动
         * 时 TextView 内部 Layout 行宽是巨大值（约 2^20），行尾会返回错误坐标。
         */
        private fun sungPixelX(
            axis: WordMaskProgress.Axis,
            positionMs: Long,
            text: String,
            textW: Float,
        ): Float {
            val offset = WordMaskProgress.sungOffset(axis, positionMs)
                .coerceIn(0f, text.length.toFloat())
            val base = offset.toInt().coerceIn(0, text.length)
            val frac = offset - base
            val x0 = if (base <= 0) 0f else tv.paint.measureText(text, 0, base)
            val x1 = if (base >= text.length) textW else tv.paint.measureText(text, 0, base + 1)
            return (x0 + (x1 - x0) * frac).coerceIn(0f, textW)
        }

        /** 滚到行尾：停帧、标记可立即切行，并消费挂起的新行。 */
        private fun settleScroll() {
            stopWordFrames()
            animator = null
            holdRunnable = null
            settled = true
            val pending = pendingText
            if (pending != null) {
                scrollAllowed = pendingAllowScroll
                applyDisplay(pending, pendingLeftMs())
            }
        }

        /** 逐字驱动关闭后，从当前 scrollX 按剩余时长匀速续滚到行尾。 */
        private fun resumeTimedScroll() {
            val avail = (tv.width - tv.paddingLeft - tv.paddingRight)
                .takeIf { it > 0 } ?: availableViewPx() ?: 0
            val textW = tv.paint.measureText(tv.text.toString())
            val distance = (textW - avail).coerceAtLeast(0f)
            if (avail <= 0 || distance <= 0f) {
                tv.setHorizontallyScrolling(false)
                tv.gravity = alignGravity()
                tv.scrollTo(0, 0)
                settleScroll()
                return
            }
            val fromX = tv.scrollX.toFloat().coerceIn(0f, distance)
            if (fromX >= distance - 1f) {
                tv.scrollTo(distance.toInt(), 0)
                settleScroll()
                return
            }
            val fullMs = passDurationFor(distance)
            val duration = (fullMs * (distance - fromX) / distance).toLong()
                .coerceIn(MIN_SCROLL_MS, MAX_SCROLL_MS)
            scrollDistancePx = distance
            scrollDurationMs = fullMs
            settled = false
            startScroll(gen, fromX, duration, distance)
        }

        /**
         * 匀速模式一整遍的滚动时长：自然速度（约每秒 35dp）滚不完时按本行剩余
         * 时长加速，保证在该行唱完前结束；预算里留一点余量给切行。
         */
        private fun passDurationFor(distance: Float): Long {
            val naturalMs = (distance / scrollPxPerMs()).toLong()
            val leftMs = (remainingMs - (SystemClock.elapsedRealtime() - displayAtMs))
                .coerceAtLeast(0L)
            val budgetMs = (leftMs - SCROLL_BUDGET_RESERVE_MS).coerceAtLeast(MIN_SCROLL_MS)
            return minOf(naturalMs, budgetMs).coerceIn(MIN_SCROLL_MS, MAX_SCROLL_MS)
        }

        /** 滚动速度（px/毫秒）：约每秒 35dp，接近系统跑马灯观感。 */
        private fun scrollPxPerMs(): Float = 35f * resources.displayMetrics.density / 1000f
    }

    // ---------------------------------------------------------------- 操作面板

    /** 切换操作面板显示/隐藏。 */
    private fun togglePanel() {
        if (panelVisible) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    /**
     * 创建（或复用）面板窗口：以 1px、不可触摸的隐藏状态挂载。
     * 必须在歌词窗口之前添加，保证面板 z 序恒在歌词窗口之下。
     */
    private fun ensurePanelWindow() {
        if (panelView != null) return
        // Service 的 context 没有应用主题，直接 inflate 含 ?attr/ 引用的布局会解析失败。
        // 用 ContextThemeWrapper 包装应用主题，保证 selectableItemBackground 等 attr 可解析。
        val binding = runCatching {
            val themed = android.view.ContextThemeWrapper(this, R.style.Theme_Androidlyrics)
            FloatPanelBinding.inflate(android.view.LayoutInflater.from(themed))
        }.getOrNull() ?: return
        val panel = binding.root
        panel.visibility = View.GONE
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { windowManager.addView(panel, params) }.onSuccess {
            panelBinding = binding
            panelView = panel
            panelLayoutParams = params
        }
    }

    /** 显示操作面板（位于歌词悬浮窗正下方）。 */
    private fun showPanel() {
        if (panelVisible) return
        val binding = panelBinding ?: return
        val panel = panelView ?: return
        val panelParams = panelLayoutParams ?: return
        val float = floatView ?: return
        val params = layoutParams ?: return

        // 每次弹出前重新绑定状态与监听（监听器为覆盖式设置，重复绑定无副作用）；
        // 内容填充（快捷导入常驻展开）在测量前完成，保证面板高度按实际内容计算
        setupPanelActions(binding)

        // 先测量面板实际尺寸，用于防溢出钳制
        panel.visibility = View.VISIBLE
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        // 面板默认占满屏幕宽度（旧版行为），高度仍按内容自适应
        val panelW = resources.displayMetrics.widthPixels
        val panelH = panel.measuredHeight
        // 面板内容较多（颜色/描边/快捷导入）时限制最大高度，超出部分由 ScrollView 滚动。
        // 常用功能「快捷导入新增文件」已上移到面板顶部区域，无需额外加高面板。
        val maxPanelH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val windowH = minOf(panelH, maxPanelH)

        panelParams.width = WindowManager.LayoutParams.MATCH_PARENT
        panelParams.height = if (windowH > 0) windowH else WindowManager.LayoutParams.WRAP_CONTENT
        val (cx, cy) = clampPanelPosition(
            params.x, computePanelY(params.y, float.height, windowH), panelW, windowH)
        panelParams.x = cx
        panelParams.y = cy
        // 恢复可触摸并把窗口扩到实际尺寸
        panelParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        runCatching { windowManager.updateViewLayout(panel, panelParams) }.onSuccess {
            panelVisible = true
            // updateViewLayout 后真实布局尺寸才确定，再钳制一次，确保面板立即回正到屏幕内
            panel.post { updatePanelPosition() }
        }
    }

    /** 隐藏操作面板：窗口保留挂载（缩为 1px、不可触摸），仅收起内容。 */
    private fun hidePanel() {
        if (!panelVisible) return
        panelVisible = false
        val panel = panelView ?: return
        val panelParams = panelLayoutParams ?: return
        panel.visibility = View.GONE
        panelParams.width = 1
        panelParams.height = 1
        panelParams.x = 0
        panelParams.y = 0
        panelParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    /** 服务停止时彻底移除面板窗口。 */
    private fun removePanelWindow() {
        panelVisible = false
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelLayoutParams = null
        panelBinding = null
    }

    /**
     * 计算面板期望的 y 坐标：默认在歌词悬浮窗下方；
     * 歌词贴近屏幕底部导致下方放不下时，改为显示在歌词上方。
     */
    private fun computePanelY(floatY: Int, floatH: Int, panelH: Int): Int {
        val screenH = resources.displayMetrics.heightPixels
        val below = floatY + floatH
        val above = floatY - panelH
        return if (below + panelH > screenH && above >= 0) above else below
    }

    /** 把面板位置钳制在屏幕范围内，避免溢出。 */
    private fun clampPanelPosition(x: Int, y: Int, panelW: Int, panelH: Int): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val maxX = (screenW - panelW).coerceAtLeast(0)
        val maxY = (screenH - panelH).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /** 把面板定位到歌词悬浮窗下方（底部时改为上方），并钳制在屏幕内。 */
    private fun updatePanelPosition() {
        if (!panelVisible) return
        val panelParams = panelLayoutParams ?: return
        val params = layoutParams ?: return
        val float = floatView ?: return
        val panel = panelView ?: return
        val (cx, cy) = clampPanelPosition(
            params.x,
            computePanelY(params.y, float.height, panel.height.takeIf { it > 0 } ?: panel.measuredHeight),
            panel.width.takeIf { it > 0 } ?: panel.measuredWidth,
            panel.height.takeIf { it > 0 } ?: panel.measuredHeight
        )
        panelParams.x = cx
        panelParams.y = cy
        runCatching { windowManager.updateViewLayout(panel, panelParams) }
    }

    /** 绑定面板上各按钮的点击事件。 */
    private fun setupPanelActions(binding: FloatPanelBinding) {
        // 打开歌词界面：显式拉起 MainActivity 并置前，复用已有实例，不丢失已打开的歌词
        binding.btnOpenApp.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(MainActivity.EXTRA_RESTORE_LAST, true)
            }
            runCatching { startActivity(intent) }
            hidePanel()
        }
        // 自动打开开关（右侧状态文字可点击）
        updateAutoStartState(binding)
        val toggleAutoStart = View.OnClickListener {
            prefs.autoStartDesktopLyrics = !prefs.autoStartDesktopLyrics
            updateAutoStartState(binding)
        }
        binding.tvAutoStartState.setOnClickListener(toggleAutoStart)

        // 桌面歌词进程保护（默认关闭）：开启后划掉后台 / 一键清理会自动恢复悬浮窗
        updateProcessGuardState(binding)
        val toggleProcessGuard = View.OnClickListener {
            val turnOn = !prefs.desktopProcessGuard
            prefs.desktopProcessGuard = turnOn
            updateProcessGuardState(binding)
            if (turnOn) scheduleGuardWatchdog(this) else cancelGuardAlarms(this)
            UiTips.show(
                this,
                if (turnOn) R.string.desktop_process_guard_on else R.string.desktop_process_guard_off
            )
        }
        binding.btnToggleProcessGuard.setOnClickListener(toggleProcessGuard)
        binding.tvProcessGuardState.setOnClickListener(toggleProcessGuard)

        // 通知栏桌面歌词开关（右侧状态文字可点击；无通知权限时先引导去授权）
        updateNotifyToggleState(binding)
        binding.tvNotifyToggleState.setOnClickListener {
            val turnOn = !prefs.notifyToggleDesktop
            if (turnOn && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                openNotificationSettings()
                return@setOnClickListener
            }
            prefs.notifyToggleDesktop = turnOn
            updateNotifyToggleState(binding)
            runCatching { startForegroundCompat() }
        }

        // 匹配歌词版本：显示当前匹配到的文件名，点击展开/收起候选列表
        updateMatchState(binding)
        val toggleMatchList = View.OnClickListener {
            if (binding.matchListContainer.visibility == View.VISIBLE) {
                binding.matchListContainer.visibility = View.GONE
            } else {
                showMatchCandidates(binding)
            }
        }
        binding.btnMatchLyric.setOnClickListener(toggleMatchList)
        binding.tvMatchState.setOnClickListener(toggleMatchList)

        // 关闭桌面歌词：隐藏悬浮窗并停止服务，保留"启动时自动打开"设置
        binding.btnCloseDesktop.setOnClickListener {
            turnOffDesktopLyrics()
        }
        // 一键对齐：按播放器当前快照重锚歌词计时。两个服务同进程，直接调通知
        // 监听服务的静态入口即可；返回 false 说明会话未连接或快照不可用。
        binding.btnResyncLyric.setOnClickListener {
            val ok = MusicNotificationListenerService.requestManualResync()
            UiTips.show(
                this,
                if (ok) R.string.desktop_resync_done else R.string.desktop_resync_failed
            )
        }
        // 文字颜色：6 个预设色卡 + 自定义色相/饱和度/明度（面板占满屏宽，滑块常驻显示）
        binding.textColorPicker.configure(
            initialColor = prefs.desktopTextColor,
            collapsibleSliders = false,
        ) { color ->
            prefs.desktopTextColor = color
        }

        // 已唱歌词颜色：只写桌面偏好，主界面不受影响。
        // setupPanelActions 每次弹出面板都会重新 configure，能自动同步设置页里的改动。
        binding.sungColorPicker.configure(
            initialColor = prefs.desktopSungTextColor,
            collapsibleSliders = false,
        ) { color ->
            prefs.desktopSungTextColor = color
        }

        // 描边开关 + 描边颜色 + 描边粗细 + 描边位置
        updateOutlineState(binding)
        val toggleOutline = View.OnClickListener {
            prefs.desktopOutlineEnabled = !prefs.desktopOutlineEnabled
            updateOutlineState(binding)
        }
        binding.btnToggleOutline.setOnClickListener(toggleOutline)
        binding.tvOutlineState.setOnClickListener(toggleOutline)

        binding.outlineColorPicker.configure(
            initialColor = prefs.desktopOutlineColor,
            collapsibleSliders = false,
        ) { color ->
            prefs.desktopOutlineColor = color
        }

        // 描边粗细：实时写入设置，桌面歌词按该值绘制描边
        binding.outlineWidthBar.progress =
            prefs.desktopOutlineWidth.coerceIn(Prefs.MIN_OUTLINE_WIDTH, Prefs.MAX_OUTLINE_WIDTH)
        binding.outlineWidthValue.text =
            getString(R.string.outline_width_value, prefs.desktopOutlineWidth)
        binding.outlineWidthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val width = progress.coerceIn(Prefs.MIN_OUTLINE_WIDTH, Prefs.MAX_OUTLINE_WIDTH)
                prefs.desktopOutlineWidth = width
                binding.outlineWidthValue.text = getString(R.string.outline_width_value, width)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        // 描边位置：内部 / 居中 / 外部（面板固定深色背景，用浅色文字配色）
        binding.outlinePositionGroup.usePanelStyle()
        binding.outlinePositionGroup.setPosition(prefs.desktopOutlinePosition)
        binding.outlinePositionGroup.onPositionChanged = { position ->
            prefs.desktopOutlinePosition = position
        }
        // 背景开关（"显示背景"文字与右侧"开/关"状态文字均可点击切换）
        updatePanelBackgroundState(binding)
        val toggleBackground = View.OnClickListener {
            prefs.desktopBackground = !prefs.desktopBackground
            updatePanelBackgroundState(binding)
        }
        binding.btnToggleBackground.setOnClickListener(toggleBackground)
        binding.tvBackgroundState.setOnClickListener(toggleBackground)

        // 背景透明度（0%~100%）：拖动即写入设置，桌面歌词背景实时跟随
        updateBackgroundAlphaState(binding)
        binding.backgroundAlphaBar.progress = prefs.desktopBackgroundAlpha
        binding.backgroundAlphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.desktopBackgroundAlpha = progress
                updateBackgroundAlphaState(binding)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.btnBackgroundAlphaReset.setOnClickListener {
            prefs.desktopBackgroundAlpha = Prefs.DEFAULT_DESKTOP_BACKGROUND_ALPHA
            binding.backgroundAlphaBar.progress = prefs.desktopBackgroundAlpha
            updateBackgroundAlphaState(binding)
            UiTips.show(this, R.string.background_alpha_reset_done)
        }

        // 文字不透明度（0%~100%）：只影响文字/描边/逐字动画，不影响背景
        updateTextAlphaState(binding)
        binding.textAlphaBar.progress = prefs.desktopTextAlpha
        binding.textAlphaBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.desktopTextAlpha = progress
                updateTextAlphaState(binding)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.btnTextAlphaReset.setOnClickListener {
            prefs.desktopTextAlpha = Prefs.DEFAULT_DESKTOP_TEXT_ALPHA
            binding.textAlphaBar.progress = prefs.desktopTextAlpha
            updateTextAlphaState(binding)
            UiTips.show(this, R.string.text_alpha_reset_done)
        }

        // 当前应用的毫秒信息不可信：按包名记忆，Telegram 默认开启
        updateAppMsOffsetState(binding)
        val toggleAppMsOffset = View.OnClickListener {
            val pkg = PlaybackOffset.activePackage()
            if (pkg == null) {
                UiTips.show(this, R.string.app_ms_offset_no_app)
            } else {
                prefs.setAppMsOffsetEnabled(pkg, !prefs.isAppMsOffsetEnabled(pkg))
                updateAppMsOffsetState(binding)
            }
        }
        binding.btnToggleAppMsOffset.setOnClickListener(toggleAppMsOffset)
        binding.tvAppMsOffsetState.setOnClickListener(toggleAppMsOffset)

        // 匹配相似度：与设置界面一致的滑块，松手后按新阈值重新匹配当前歌曲
        updateSimilarityState(binding)
        binding.similarityBar.progress = prefs.matchSimilarity.coerceIn(
            Prefs.MIN_MATCH_SIMILARITY, Prefs.MAX_MATCH_SIMILARITY
        )
        binding.similarityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.matchSimilarity = progress.coerceIn(
                    Prefs.MIN_MATCH_SIMILARITY, Prefs.MAX_MATCH_SIMILARITY
                )
                updateSimilarityState(binding)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                MusicNotificationListenerService.rematchNow()
            }
        })
        binding.btnSimilarityReset.setOnClickListener {
            prefs.matchSimilarity = Prefs.DEFAULT_MATCH_SIMILARITY
            binding.similarityBar.progress = prefs.matchSimilarity
            onSimilarityChanged(binding)
        }

        // 歌词空行显示：点击循环切换各模式（自定义未填文字时跳过）
        updateEmptyLineState(binding)
        binding.tvEmptyLineState.setOnClickListener {
            var mode = (prefs.emptyLineMode + 1) % (Prefs.EMPTY_LINE_CUSTOM + 1)
            if (mode == Prefs.EMPTY_LINE_CUSTOM && prefs.emptyLineCustom.isBlank()) {
                mode = Prefs.EMPTY_LINE_NONE
            }
            prefs.emptyLineMode = mode
            updateEmptyLineState(binding)
            currentLineIndex = Int.MIN_VALUE
        }

        // 快捷导入新增文件：常驻展开最近目录列表（第一行为"添加目录"），
        // 不再随标题点击折叠，避免用户每次打开面板都要重新展开
        showQuickImport(binding)
    }

    /**
     * 显示快捷导入列表：
     * 第一行"添加目录"跳系统目录选择器；其后为最近目录（带勾选框，勾选状态持久记忆）；
     * 末尾为"导入选中目录的新文件"按钮，只导入应用内尚不存在的新名称歌词文件。
     */
    private fun showQuickImport(binding: FloatPanelBinding) {
        val container = binding.quickImportContainer
        container.removeAllViews()

        val addDir = TextView(this).apply {
            text = getString(R.string.quick_import_add_dir)
            textSize = 12f
            setTextColor(0xFFFFB74D.toInt())
            setPadding(16, 12, 16, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { launchQuickImportPickDir() }
        }
        container.addView(addDir)

        val dirs = prefs.getRecentDirs()
        // 记忆勾选：从未设置过时默认全选；之后严格按用户勾选状态（含全部取消）
        val saved = if (!prefs.hasQuickImportSelectedDirs()) {
            dirs.map { it.uri }.toSet().also { prefs.setQuickImportSelectedDirs(it) }
        } else {
            prefs.getQuickImportSelectedDirs()
        }
        val checked = saved.toMutableSet()

        dirs.forEach { dir ->
            val cb = android.widget.CheckBox(this).apply {
                text = dir.path.ifEmpty { dir.name }
                textSize = 12f
                setTextColor(0xCCFFFFFF.toInt())
                isChecked = dir.uri in checked
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.START
                setPadding(12, 4, 12, 4)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) checked.add(dir.uri) else checked.remove(dir.uri)
                    prefs.setQuickImportSelectedDirs(checked.toSet())
                }
            }
            container.addView(cb)
        }

        val importBtn = TextView(this).apply {
            text = getString(R.string.quick_import_action)
            textSize = 12f
            setTextColor(0xFF66CCFF.toInt())
            setPadding(16, 12, 16, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { importNewFromDirs(checked) }
        }
        container.addView(importBtn)
        container.visibility = View.VISIBLE
    }

    /** 从悬浮窗添加目录：拉起透明中转页处理系统目录选择器，结果写入最近目录。 */
    private fun launchQuickImportPickDir() {
        val intent = Intent(this, QuickImportActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(QuickImportActivity.EXTRA_PICK_DIR, true)
        }
        runCatching { startActivity(intent) }
        hidePanel()
    }

    /**
     * 从勾选的最近目录导入"新名称"歌词文件：
     * 只复制应用歌词目录中尚不存在同名文件的歌词文件，避免覆盖用户已编辑的文件。
     */
    private fun importNewFromDirs(dirUris: Set<String>) {
        if (dirUris.isEmpty()) {
            UiTips.show(this, R.string.quick_import_no_dir)
            return
        }
        UiTips.show(this, R.string.quick_import_running)
        Thread {
            var count = 0
            val store = File(filesDir, "lyrics").apply { mkdirs() }
            val existing = store.listFiles()?.map { it.name }.orEmpty().toMutableSet()
            dirUris.forEach { uriString ->
                runCatching {
                    val treeUri = android.net.Uri.parse(uriString)
                    val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    val childrenUri = android.provider.DocumentsContract
                        .buildChildDocumentsUriUsingTree(treeUri, treeDocId)
                    contentResolver.query(
                        childrenUri,
                        arrayOf(
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        ),
                        null, null, null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1) ?: continue
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (ext !in LyricsParser.SUPPORTED_EXTENSIONS) continue
                            if (name in existing) continue
                            val docUri = android.provider.DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                            val bytes = contentResolver.openInputStream(docUri)
                                ?.use { it.readBytes() } ?: continue
                            File(store, name).writeBytes(bytes)
                            existing.add(name)
                            count++
                        }
                    }
                }
            }
            if (count > 0) LyricIndex.invalidate()
            uiHandler.post {
                UiTips.show(this@DesktopLyricsService, getString(R.string.quick_import_done, count))
            }
        }.start()
    }

    /** 更新面板上的背景透明度显示。 */
    private fun updateBackgroundAlphaState(binding: FloatPanelBinding) {
        binding.tvBackgroundAlphaValue.text =
            getString(R.string.alpha_percent, prefs.desktopBackgroundAlpha)
    }

    /** 更新面板上的文字不透明度显示。 */
    private fun updateTextAlphaState(binding: FloatPanelBinding) {
        binding.tvTextAlphaValue.text = getString(R.string.alpha_percent, prefs.desktopTextAlpha)
    }

    /** 更新面板上「当前应用的毫秒信息不可信」开关与当前应用名。 */
    private fun updateAppMsOffsetState(binding: FloatPanelBinding) {
        val pkg = PlaybackOffset.activePackage()
        binding.tvAppMsOffsetApp.text = if (pkg == null) {
            getString(R.string.app_ms_offset_no_app)
        } else {
            getString(R.string.app_ms_offset_current_app, PlaybackOffset.appLabel(this, pkg))
        }
        val on = pkg != null && prefs.isAppMsOffsetEnabled(pkg)
        binding.tvAppMsOffsetState.text =
            getString(if (on) R.string.desktop_bg_on else R.string.desktop_bg_off)
        binding.tvAppMsOffsetState.setTextColor(if (on) 0xFF66CCFF.toInt() else 0x80FFFFFF.toInt())
    }

    /** 更新面板上的匹配相似度显示。 */
    private fun updateSimilarityState(binding: FloatPanelBinding) {
        binding.tvSimilarityValue.text = getString(R.string.similarity_percent, prefs.matchSimilarity)
    }

    /** 相似度变更后的联动：刷新显示并按新阈值重新匹配当前歌曲。 */
    private fun onSimilarityChanged(binding: FloatPanelBinding) {
        updateSimilarityState(binding)
        MusicNotificationListenerService.rematchNow()
    }

    /** 更新面板上的空行显示模式文字。 */
    private fun updateEmptyLineState(binding: FloatPanelBinding) {
        val text = when (prefs.emptyLineMode) {
            Prefs.EMPTY_LINE_BEAT -> getString(R.string.empty_line_beat)
            Prefs.EMPTY_LINE_TILDE -> getString(R.string.empty_line_tilde)
            Prefs.EMPTY_LINE_ACCOMPANIMENT -> getString(R.string.empty_line_accompaniment)
            Prefs.EMPTY_LINE_CUSTOM -> getString(R.string.empty_line_custom)
            else -> getString(R.string.empty_line_none)
        }
        binding.tvEmptyLineState.text = text
    }

    /** 更新面板上匹配歌词的状态文字。 */
    private fun updateMatchState(binding: FloatPanelBinding) {
        val matched = Session.matchedFile
        val label = when {
            matched != null -> getString(R.string.desktop_match_current, matched.name)
            Session.matchState == LyricMatchState.MATCHING ->
                getString(R.string.desktop_matching_lyric)
            Session.matchState == LyricMatchState.FETCHING_ONLINE ->
                getString(R.string.desktop_fetching_online_lyric)
            Session.matchState == LyricMatchState.NO_MATCH ->
                getString(R.string.desktop_no_match_lyric)
            else -> getString(R.string.desktop_match_none)
        }
        // tick 会周期性调用本方法，文本没变就不重复刷新 TextView
        if (binding.tvMatchState.text?.toString() != label) binding.tvMatchState.text = label
        binding.tvMatchState.setTextColor(
            if (matched != null) 0xFF66CCFF.toInt() else 0x80FFFFFF.toInt()
        )
    }

    /**
     * 展开候选歌词列表：首行为"手动添加"入口，其后列出与当前播放歌曲
     * 相似度达标（按用户设置的匹配相似度阈值）的所有歌词文件。
     * 用户选择后写入歌曲-歌词绑定并立即切换桌面歌词显示。
     */
    private fun showMatchCandidates(binding: FloatPanelBinding, showAll: Boolean = false) {
        val container = binding.matchListContainer
        container.removeAllViews()

        val title = MusicProgress.title?.trim().takeUnless { it.isNullOrEmpty() }
        val artist = MusicProgress.artist?.trim().orEmpty()
        if (title == null) {
            container.visibility = View.GONE
            return
        }

        val dir = File(filesDir, "lyrics")
        val candidates = LyricMatcher.matches(dir, title, artist, prefs.matchSimilarity / 100.0)
            .toMutableList()
        // 注意：这里只扫描歌词存储目录 lyrics/，绝不把独立在线歌词缓存目录
        // online_lyrics/ 并入候选列表或相似度匹配，两个目录保持彼此独立。
        // 用户手动添加/显式绑定过的文件可能低于相似度阈值而不在匹配结果中，
        // 需并入候选列表置顶显示，确保用户的选择始终可见、可回切。
        // 绑定值可能带 online_lyrics/ 前缀，必须走统一解析而不是拼 lyrics/ 目录。
        val boundName = LyricBindings.get(this, title, artist)
        if (boundName != null) {
            val boundFile = LyricBindings.resolveFile(this, boundName)
            if (boundFile.exists() && candidates.none { it.absolutePath == boundFile.absolutePath }) {
                candidates.add(0, boundFile)
            }
        }
        val current = Session.matchedFile
        // 候选过多时先渲染前 20 个：匹配结果本身已由缓存加速，
        // 剩下的一次性创建上百个 TextView 才是主线程的主要开销。
        val visible = if (showAll) candidates else candidates.take(20)

        // 首行固定为手动添加入口：系统文件选择器选定的歌词会强制绑定，
        // 下次自动匹配优先使用，且计入最近打开记录
        val manual = TextView(this).apply {
            text = getString(R.string.manual_add_lyric)
            textSize = 12f
            setTextColor(0xFFFFB74D.toInt())
            setPadding(16, 12, 16, 12)
            setBackgroundResource(android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { launchManualAdd() }
        }
        container.addView(manual)

        visible.forEach { file ->
            val tv = TextView(this).apply {
                text = file.name
                textSize = 12f
                setTextColor(if (file == current) 0xFF66CCFF.toInt() else 0xCCFFFFFF.toInt())
                setPadding(16, 12, 16, 12)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectMatchedLyric(binding, title, artist, file)
                }
            }
            container.addView(tv)
        }
        if (!showAll && candidates.size > visible.size) {
            container.addView(TextView(this).apply {
                text = getString(R.string.match_show_all, candidates.size)
                textSize = 12f
                setTextColor(0xFF66CCFF.toInt())
                setPadding(16, 12, 16, 12)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                setOnClickListener { showMatchCandidates(binding, showAll = true) }
            })
        }
        container.visibility = View.VISIBLE
    }

    /** 从悬浮窗发起"手动添加歌词"：拉起主界面并自动进入文件选择。 */
    private fun launchManualAdd() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_RESTORE_LAST, true)
            putExtra(MainActivity.EXTRA_MANUAL_ADD_LYRIC, true)
        }
        runCatching { startActivity(intent) }
        hidePanel()
    }

    /** 用户从面板选择了匹配歌词版本：记录绑定并切换当前显示。 */
    private fun selectMatchedLyric(
        binding: FloatPanelBinding,
        title: String,
        artist: String,
        file: File,
    ) {
        runCatching {
            Session.lyricLines = LyricsParser.parse(DocumentIo.decode(file.readBytes()).text)
            Session.matchedFile = file
            Session.matchState = LyricMatchState.MATCHED
            // 认领当前歌曲：切歌防抖窗口内若已排期，未到期时不应把用户刚选的
            // 歌词重新切回「正在匹配歌词」或按相似度另选一份
            Session.matchedKey = LyricBindings.keyOf(title, artist)
            Session.matchVersion++
            // 面板里的选择也是用户显式选择：覆盖主界面先前记下的那份，
            // 保证两处显示同一份歌词，且重新匹配不会切回更早的文件
            Session.explicitLyricTitle = title
            Session.explicitLyricPath = file.absolutePath
            currentLineIndex = Int.MIN_VALUE
            prefs.lastLyricFile = file.absolutePath
        }
        // 用户显式选择优先于文件名相似度校验：候选列表可能包含低于默认
        // 相似度但用户认可的文件，静默丢弃绑定会导致"记忆"失效
        LyricBindings.putForce(this, title, artist, LyricBindings.bindingValue(this, file))
        binding.matchListContainer.visibility = View.GONE
        updateMatchState(binding)
    }

    /** 更新面板上自动打开开关的显示状态。 */
    private fun updateAutoStartState(binding: FloatPanelBinding) {
        val on = prefs.autoStartDesktopLyrics
        binding.tvAutoStartState.text = getString(if (on) R.string.desktop_bg_on else R.string.desktop_bg_off)
        binding.tvAutoStartState.setTextColor(if (on) 0xFF66CCFF.toInt() else 0x80FFFFFF.toInt())
    }

    /** 更新面板上进程保护开关的显示状态。 */
    private fun updateProcessGuardState(binding: FloatPanelBinding) {
        val on = prefs.desktopProcessGuard
        binding.tvProcessGuardState.text =
            getString(if (on) R.string.desktop_bg_on else R.string.desktop_bg_off)
        binding.tvProcessGuardState.setTextColor(
            if (on) 0xFF66CCFF.toInt() else 0x80FFFFFF.toInt()
        )
    }

    /** 更新面板上背景开关的显示状态。 */
    private fun updatePanelBackgroundState(binding: FloatPanelBinding) {
        val on = prefs.desktopBackground
        binding.tvBackgroundState.text = getString(if (on) R.string.desktop_bg_on else R.string.desktop_bg_off)
        binding.tvBackgroundState.setTextColor(if (on) 0xFF66CCFF.toInt() else 0x80FFFFFF.toInt())
    }

    /** 更新面板上通知栏桌面歌词开关的显示状态。 */
    private fun updateNotifyToggleState(binding: FloatPanelBinding) {
        val on = prefs.notifyToggleDesktop
        binding.tvNotifyToggleState.text =
            getString(if (on) R.string.desktop_bg_on else R.string.desktop_bg_off)
        binding.tvNotifyToggleState.setTextColor(
            if (on) 0xFF66CCFF.toInt() else 0x80FFFFFF.toInt()
        )
    }

    /** 更新面板上文字描边开关的显示状态。 */
    private fun updateOutlineState(binding: FloatPanelBinding) {
        val on = prefs.desktopOutlineEnabled
        binding.tvOutlineState.text = getString(if (on) R.string.desktop_bg_on else R.string.desktop_bg_off)
        binding.tvOutlineState.setTextColor(if (on) 0xFF66CCFF.toInt() else 0x80FFFFFF.toInt())
    }

    /**
     * 无通知权限时跳转系统应用通知设置页，让用户授予权限。
     *
     * extras 必须带上包名，否则系统设置页会提示"在已安装应用的列表中找不到该应用"；
     * 同时写入新、旧两套键名以兼容不同 ROM，系统不支持通知设置页时回退到应用详情页。
     */
    private fun openNotificationSettings() {
        val notifIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra("app_package", packageName)
            putExtra("app_uid", applicationInfo.uid)
        }
        val detailIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = android.net.Uri.parse("package:$packageName")
        }
        val opened = runCatching { startActivity(notifIntent) }.isSuccess ||
            runCatching { startActivity(detailIntent) }.isSuccess
        if (!opened) {
            UiTips.show(this, R.string.notify_permission_denied, Toast.LENGTH_LONG)
        }
        hidePanel()
    }

    companion object {

        /** 高亮防回跳的回退幅度阈值（毫秒）：小于该幅度的回退视为估算抖动忽略。 */
        private const val LINE_FLICKER_GUARD_MS = 500L

        /** 长歌词滚动回退的判定阈值（毫秒）：略高于逐帧抖动，双边对齐的整秒回退也能触发。 */
        private const val SCROLL_REWIND_MIN_MS = 200L

        /** "暂无歌词"占位状态对应的行号标记（区别于 -1 的"首行之前"）。 */
        private const val NO_LYRIC_INDEX = -2

        /** 固定宽度模式根布局的左右边距（dp），与 float_lyrics.xml 保持一致。 */
        private const val DEFAULT_ROOT_PAD_DP = 16

        /** 悬浮窗必须保留的最小可见像素（dp），防止被完全拖/旋转出屏幕外。 */
        private const val MIN_VISIBLE_DP = 24

        /** 滚动前行首停留时长（毫秒），仅匀速模式使用。 */
        private const val HOLD_START_MS = 1000L

        /** 逐字驱动时高亮前缘保持在视口内的相对位置（0 = 左缘，1 = 右缘）。 */
        private const val WORD_ANCHOR_FRACTION = 0.28f

        /** 滚动速度预算里为切行预留的余量（毫秒）。 */
        private const val SCROLL_BUDGET_RESERVE_MS = 800L

        /** 溢出判定余量（px）：测量与排版的微小误差不触发滚动，避免边框线文本抖动。 */
        private const val OVERFLOW_FUDGE_PX = 2f

        /** 单次滚动时长下限/上限（毫秒），避免过短闪烁或过长不动。 */
        private const val MIN_SCROLL_MS = 1200L
        private const val MAX_SCROLL_MS = 20_000L
        private const val CHANNEL_ID = "desktop_lyrics"
        private const val NOTIF_ID = 1001

        /** 通知栏开关：关闭桌面歌词。 */
        private const val ACTION_TURN_OFF = "com.trae.androidlyrics.action.TURN_OFF_DESKTOP"

        /** 用户主动开启桌面歌词（start() 发起的前台服务启动命令）。 */
        private const val ACTION_USER_START = "com.trae.androidlyrics.action.USER_START_DESKTOP"

        /** 通知栏开关：开启桌面歌词。 */
        private const val ACTION_TURN_ON = "com.trae.androidlyrics.action.TURN_ON_DESKTOP"

        /** 刷新通知（用户切换"通知栏显示桌面歌词开关"后调用）。 */
        private const val ACTION_REFRESH = "com.trae.androidlyrics.action.REFRESH_DESKTOP_NOTIF"

        /** 进程保护守护闹钟：唤醒并重新拉起桌面歌词服务。 */
        private const val ACTION_GUARD_WAKE = "com.trae.androidlyrics.action.GUARD_WAKE_DESKTOP"

        /** 进程保护开关变化后的空指令：只用于刷新 AMS 记录的 sticky 策略。 */
        private const val ACTION_SYNC_STICKY = "com.trae.androidlyrics.action.SYNC_DESKTOP_STICKY"

        private const val REQ_OPEN_APP = 11
        private const val REQ_TURN_ON = 12
        private const val REQ_TURN_OFF = 13
        private const val REQ_GUARD_RESTART = 14
        private const val REQ_GUARD_WATCHDOG = 15

        /** 进程被清理后重新拉起服务的延迟。 */
        private const val GUARD_RESTART_DELAY_MS = 900L

        /** 守护闹钟周期：进程被静默杀死且没有回调 onTaskRemoved 时的兜底自恢复。 */
        private const val GUARD_WATCHDOG_MS = 60_000L

        /** 服务当前是否在运行（onCreate 置 true，onDestroy 置 false）。 */
        @Volatile var isRunning: Boolean = false
            private set

        /**
         * 进程启动时被 App 自动恢复（"启动时自动打开桌面歌词"）的时间点
         *（elapsedRealtime 毫秒），0 表示本次进程生命周期内未发生自动恢复。
         * 用于区分"用户开启的服务"与"进程刚拉起时被自动恢复的服务"：
         * 后者若紧跟一次快捷入口开关点击，该点击的意图是"打开"而非"关闭"。
         */
        @Volatile var autoRecoveredAt: Long = 0L

        /**
         * 本次进程启动是否自动恢复过桌面歌词，且尚未被快捷入口消费。
         *
         * 进程被清理后，用户点击「打开桌面歌词」快捷开关/磁贴时，App 会在点击
         * 生效前先把服务自动拉起：这次点击的真实意图是「唤起」而不是「关闭」。
         * 用一次性标志识别，取代原先按时间窗猜测的方案（冷启动慢时会误判成关闭）。
         * 用户正常打开应用后由 [recoverInForeground] 清除，此后点击恢复为切换开关。
         */
        @Volatile var autoRecoveredPending: Boolean = false

        /** 消费一次「自动恢复待确认」标志。 */
        fun consumeAutoRecoveredPending(): Boolean {
            if (!autoRecoveredPending) return false
            autoRecoveredPending = false
            return true
        }

        /**
         * [App.onCreate] 处于后台时拉起前台服务可能被系统拒绝，标记为「待恢复」，
         * 等 Activity 真正进入前台后由 [recoverInForeground] 补一次。
         */
        @Volatile var recoveryPending: Boolean = false

        /**
         * 本次进程启动后是否已经完成过一次「启动应用时恢复桌面歌词」的判定。
         * 只在第一次进入前台时按启动规则恢复，避免用户手动关掉开关后返回主界面
         * 又被 autoStart 立刻自动打开。进程重启（含划掉后台）会自动重置。
         */
        @Volatile private var foregroundRecoveryChecked: Boolean = false

        fun start(context: Context) {
            // 所有「决定要显示桌面歌词」的入口（设置页、磁贴、快捷方式、应用前台
            // 自启动、保护开启时的后台恢复）都经过这里，同步「启用桌面歌词」开关。
            Prefs(context).desktopLyrics = true
            // 带明确的 action：服务据此识别「用户主动开启」，与 AMS sticky 重启的
            // null intent、守护闹钟的 ACTION_GUARD_WAKE 区分开，避免刚开启就被
            // 后台误启动守卫撤销。
            ContextCompat.startForegroundService(
                context,
                Intent(context, DesktopLyricsService::class.java).setAction(ACTION_USER_START)
            )
        }

        // ------------------------------------------------------------ 进程保护

        /**
         * 守护闹钟统一用 `getForegroundService` 投递：进程被清理后没有 Activity 在前台，
         * 普通 startService 会被 Android 8+ 的后台启动限制拦下。
         */
        private fun guardPendingIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, DesktopLyricsService::class.java)
                .setAction(ACTION_GUARD_WAKE)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        }

        private fun guardAlarmManager(context: Context): AlarmManager? =
            context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        /** 延迟 [delayMs] 投递一次守护闹钟；未获精确闹钟权限时自动退回非精确。 */
        private fun setGuardAlarm(
            context: Context,
            requestCode: Int,
            delayMs: Long,
            exact: Boolean,
        ) {
            val am = guardAlarmManager(context) ?: return
            val pi = guardPendingIntent(context, requestCode)
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            val canExact = exact && (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            )
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (canExact) {
                        am.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                        )
                    } else {
                        am.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi
                        )
                    }
                } else if (canExact) {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            }.onFailure {
                // 个别 ROM 会拒绝 idle 窗口的闹钟，退回普通非精确闹钟
                runCatching { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
            }
        }

        /**
         * 划掉后台后尽快恢复：优先用系统闹钟时钟（免精确闹钟权限、不受 Doze 影响），
         * 失败再退回精确 / 非精确闹钟。闹钟由系统持有，进程被杀也不会丢失。
         */
        fun scheduleGuardRestart(context: Context) {
            val am = guardAlarmManager(context) ?: return
            val pi = guardPendingIntent(context, REQ_GUARD_RESTART)
            val triggerAt = System.currentTimeMillis() + GUARD_RESTART_DELAY_MS
            val scheduled = runCatching {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, null), pi)
                true
            }.getOrDefault(false)
            if (!scheduled) {
                setGuardAlarm(context, REQ_GUARD_RESTART, GUARD_RESTART_DELAY_MS, exact = true)
            }
        }

        /** 常驻守护：服务每次被闹钟唤醒都会续上下一跳，覆盖「没有 onTaskRemoved 回调」的清理方式。 */
        fun scheduleGuardWatchdog(context: Context) {
            setGuardAlarm(context, REQ_GUARD_WATCHDOG, GUARD_WATCHDOG_MS, exact = false)
        }

        /** 撤销全部守护闹钟（用户主动关闭桌面歌词 / 关闭进程保护时调用）。 */
        fun cancelGuardAlarms(context: Context) {
            val am = guardAlarmManager(context) ?: return
            am.cancel(guardPendingIntent(context, REQ_GUARD_RESTART))
            am.cancel(guardPendingIntent(context, REQ_GUARD_WATCHDOG))
        }

        /**
         * 用户启动应用、主界面进入前台时恢复桌面歌词悬浮窗。
         *
         * 规则只看桌面歌词的启动状态：
         * 1. 状态为开启 —— 无论进程保护 / 启动时自动打开如何设置，都把被系统清理掉的
         *    悬浮窗补回来，保证「开关开着就一定能看到悬浮窗」；
         * 2. 状态为关闭 —— 只有用户显式开启「启动时自动打开桌面歌词」才自动打开；
         *    两项都关着时一律不恢复。
         *
         * 进程保护关闭时清理后台不会改写启动状态，只是后台不再自动恢复；用户
         * 主动打开应用时按上述规则自启动（这正是「靠开关决定是否自启动」的路径）。
         * 只在本次进程启动后第一次进入前台时执行这条启动规则：之后用户手动关掉
         * 桌面歌词再返回主界面，不应被 autoStart 立刻重新打开。
         * 进程保护开启时 [App] 后台拉起 FGS 被拒登记的 [recoveryPending] 也在这里一并重试。
         */
        fun recoverInForeground(context: Context) {
            if (isRunning) {
                recoveryPending = false
                // 用户已经正常打开应用（主界面回到前台），此后快捷入口的点击
                // 就是普通的「切换开关」，不再按「刚被清理后唤起」处理
                autoRecoveredPending = false
                foregroundRecoveryChecked = true
                return
            }
            // 无论是否真的恢复，用户已主动打开应用，此后快捷入口按普通切换处理
            autoRecoveredPending = false
            val prefs = Prefs(context)
            if (foregroundRecoveryChecked) {
                recoveryPending = false
                return
            }
            // 启动状态是用户意图：清理后台一律不改写它，保护开启/关闭都按它判断。
            // 关着时只认 autoStart 这一显式授权。recoveryPending 只是保护开启时
            // 后台恢复失败的重试保险，它置位时前两项必有一项为真，不会绕过开关。
            val allowRecovery = prefs.desktopLyrics || prefs.autoStartDesktopLyrics ||
                (prefs.desktopProcessGuard && recoveryPending)
            if (!allowRecovery) {
                recoveryPending = false
                foregroundRecoveryChecked = true
                return
            }
            val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else true
            // 悬浮窗权限未授予：不消耗本次判定，用户授权后回到前台再试
            if (!canOverlay) return
            foregroundRecoveryChecked = true
            prefs.desktopLyrics = true
            recoveryPending = false
            runCatching { start(context) }
        }

        fun stop(context: Context) {
            // 用户主动关闭：无论服务是否还在运行，都撤销进程保护的守护闹钟，
            // 否则进程被清理后闹钟会把刚关掉的桌面歌词重新拉起
            cancelGuardAlarms(context)
            if (isRunning) {
                // 服务运行中：发送关闭命令让其走正常关闭流程（隐藏悬浮窗、
                // 移除前台通知并在"通知栏显示桌面歌词开关"开启时保留一条
                // 可再次开启的通知）。直接 stopService 会连同服务通知一起销毁，
                // 导致通知栏常驻通知消失。
                val delivered = runCatching {
                    context.startService(
                        Intent(context, DesktopLyricsService::class.java).setAction(ACTION_TURN_OFF)
                    )
                }.isSuccess
                if (delivered) return
                // 兜底：指令投递失败时直接停服，并补发一条独立通知保住开关入口
                context.stopService(Intent(context, DesktopLyricsService::class.java))
                val p = Prefs(context)
                if (p.notifyToggleDesktop || p.notifyToggleOnStart) {
                    postStandaloneToggleNotification(context)
                }
            } else {
                context.stopService(Intent(context, DesktopLyricsService::class.java))
            }
        }

        /**
         * 同步"通知栏显示桌面歌词开关"的变更：
         * - 服务运行中：重建前台通知，使开关按钮立即出现/消失；
         * - 服务未运行且开关开启：保留一条独立通知，可在通知栏开启桌面歌词；
         * - 服务未运行且开关关闭：移除该通知。
         */
        fun refreshNotification(context: Context) {
            val prefs = Prefs(context)
            // 仅在"桌面歌词确实开启 + 服务仍在运行"时才让服务重建前台通知。
            // 若桌面歌词已关闭，isRunning 可能残留为 true，此时 startService 会凭空拉起
            // 已停止的服务，导致关闭开关反而弹出"桌面歌词已开启"的旧通知。
            val refreshed = prefs.desktopLyrics && isRunning && runCatching {
                context.startService(
                    Intent(context, DesktopLyricsService::class.java).setAction(ACTION_REFRESH)
                )
            }.isSuccess
            if (refreshed) return
            if (prefs.notifyToggleDesktop || prefs.notifyToggleOnStart) {
                postStandaloneToggleNotification(context)
            } else {
                runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
            }
        }

        /**
         * 应用启动时按「启动时发送桌面歌词开关通知」设置补发通知：
         * - 桌面歌词服务正在运行：刷新前台通知，保证通知里带上开关（桌面歌词已开启）；
         * - 桌面歌词未开启 / 服务未运行：补发一条独立的「开启桌面歌词」通知。
         * 两种情形都不依赖 [Prefs.notifyToggleDesktop]，除非系统未授予通知权限。
         */
        fun postStartupToggleNotification(context: Context) {
            val prefs = Prefs(context)
            if (!prefs.notifyToggleOnStart) return
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val refreshed = isRunning && prefs.desktopLyrics && runCatching {
                context.startService(
                    Intent(context, DesktopLyricsService::class.java).setAction(ACTION_REFRESH)
                )
            }.isSuccess
            if (!refreshed) postStandaloneToggleNotification(context)
        }

        /**
         * 服务未运行时也保留一条可开关桌面歌词的通知。
         * 调用方负责判断是否需要（通知栏开关 / 启动通知开关任一开启）；
         * 桌面歌词此时必然是关闭状态，因此按钮文案为"开启桌面歌词"。
         */
        private fun postStandaloneToggleNotification(context: Context) {
            runCatching {
                ensureChannelFor(context)
                val notification = notifyBuilder(context, desktopOn = false)
                    .setContentIntent(openAppPendingIntent(context))
                    .setCustomContentView(contentView(context, desktopOn = false))
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .build()
                NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
            }
        }

        /** 创建通知渠道（幂等）。 */
        private fun ensureChannelFor(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "桌面歌词", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "桌面歌词悬浮窗" }
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }
        }

        /** 桌面歌词通知的基础构建器（标题、正文文案、常驻）. */
        private fun notifyBuilder(context: Context, desktopOn: Boolean): NotificationCompat.Builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(
                    context.getString(
                        if (desktopOn) R.string.notif_desktop_on_text
                        else R.string.notif_desktop_off_text
                    )
                )
                .setOngoing(true)

        /** 点击通知正文打开歌词界面。 */
        private fun openAppPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(MainActivity.EXTRA_RESTORE_LAST, true)
            }
            return PendingIntent.getActivity(
                context, REQ_OPEN_APP, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * 自定义通知正文：把开关按钮直接放在通知内容区，
         * 使其在折叠状态下同样可见、可点击（普通 action 按钮需展开通知才显示）。
         */
        private fun contentView(context: Context, desktopOn: Boolean): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.notification_desktop_lyrics)
            rv.setTextViewText(
                R.id.notifStatusText,
                context.getString(
                    if (desktopOn) R.string.notif_desktop_on_text
                    else R.string.notif_desktop_off_text
                )
            )
            rv.setTextViewText(
                R.id.notifToggleBtn,
                context.getString(
                    if (desktopOn) R.string.notif_action_turn_off else R.string.notif_action_turn_on
                )
            )
            rv.setOnClickPendingIntent(
                R.id.notifToggleBtn,
                togglePendingIntent(
                    context,
                    if (desktopOn) ACTION_TURN_OFF else ACTION_TURN_ON,
                    if (desktopOn) REQ_TURN_OFF else REQ_TURN_ON
                )
            )
            return rv
        }

        /** 通知栏开关按钮的 PendingIntent（开启走前台服务，关闭走普通服务）。 */
        private fun togglePendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, DesktopLyricsService::class.java).setAction(action)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (action == ACTION_TURN_ON) {
                PendingIntent.getForegroundService(context, requestCode, intent, flags)
            } else {
                PendingIntent.getService(context, requestCode, intent, flags)
            }
        }
    }
}
