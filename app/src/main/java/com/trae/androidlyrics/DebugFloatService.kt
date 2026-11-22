package com.trae.androidlyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * 调试模式悬浮窗：悬浮展示当前播放歌曲的全部信息（含匹配到的歌词文件名），
 * 每 500ms 刷新一次；标题区点击弹出"复制"选项，把当前信息复制到剪贴板；
 * 右上角关闭控件可直接关闭悬浮窗（同步关掉「调试模式」开关）。
 *
 * 视图全部用代码构建，避免无主题 Service Context inflate 带主题属性布局崩溃。
 */
class DebugFloatService : Service() {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var infoText: TextView? = null
    private var copyButton: TextView? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private val hideCopyButton = Runnable { copyButton?.visibility = View.GONE }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            infoText?.text = MusicNotificationListenerService.debugSnapshot()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundCompat()
        showWindow()
        uiHandler.post(refreshRunnable)
    }

    override fun onDestroy() {
        isRunning = false
        uiHandler.removeCallbacks(refreshRunnable)
        hideWindow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 开关已关但被启动（如系统恢复）：直接自停，避免悬浮窗残留
        if (!Prefs(this).debugMode) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun showWindow() {
        if (floatView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val dm = resources.displayMetrics
        val density = dm.density

        val text = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt())
        }
        infoText = text

        // 标题栏标题区：按住拖动移动窗口，轻点显示复制按钮
        val headerTitle = TextView(this).apply {
            this.text = "调试信息（点我显示复制）"
            textSize = 12f
            setTextColor(0xFF8ECAFF.toInt())
            setPadding((12 * density).toInt(), (6 * density).toInt(),
                (4 * density).toInt(), (6 * density).toInt())
        }

        // 右上角关闭控件：关闭悬浮窗并同步关掉「调试模式」开关，
        // 与设置页开关、应用快捷方式保持同一状态口径
        val closeButton = TextView(this).apply {
            this.text = "✕"
            textSize = 14f
            setTextColor(0xFF8ECAFF.toInt())
            setPadding((12 * density).toInt(), (4 * density).toInt(),
                (12 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                Prefs(this@DebugFloatService).debugMode = false
                DebugInfoTileService.requestUpdate(this@DebugFloatService)
                stopSelf()
            }
        }

        // 标题栏：标题区（可拖动）+ 右上角关闭控件
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                headerTitle,
                android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            )
            addView(
                closeButton,
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // 复制选项：内嵌在悬浮窗内（PopupMenu 需要窗口焦点，而无焦点窗口
        // 是系统返回手势正常工作的前提，故不用 PopupMenu）
        val copyBtn = TextView(this).apply {
            // 必须用 this.text：外层局部变量 text（TextView）会遮蔽接收者属性
            this.text = getString(R.string.copy)
            textSize = 13f
            setTextColor(0xFF202529.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF8ECAFF.toInt())
                cornerRadius = 8 * density
            }
            setPadding((16 * density).toInt(), (6 * density).toInt(),
                (16 * density).toInt(), (6 * density).toInt())
            visibility = View.GONE
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(
                    ClipData.newPlainText(
                        "debug_song_info",
                        MusicNotificationListenerService.debugSnapshot()
                    )
                )
                UiTips.show(this@DebugFloatService, R.string.debug_copied)
                visibility = View.GONE
            }
        }
        copyButton = copyBtn

        // 内容超过窗口高度时内部滚动
        val scroll = ScrollView(this).apply { addView(text) }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xB3202529.toInt())
                cornerRadius = 12 * density
            }
            addView(header)
            addView(copyBtn)
            addView(scroll)
        }
        floatView = root

        val params = WindowManager.LayoutParams(
            (dm.widthPixels * 0.8f).toInt(),
            (dm.heightPixels * 0.4f).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE 必须有：可聚焦的悬浮窗会接管系统返回事件，
            // 导致返回手势失效；触摸点击在无焦点窗口下不受影响。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).toInt()
            y = (80 * density).toInt()
        }

        // 拖动/轻点监听挂在标题区：关闭控件独立响应点击，不参与拖动
        attachTouch(headerTitle, root, params)
        runCatching { wm.addView(root, params) }
            .onFailure {
                floatView = null
                stopSelf()
            }
    }

    private fun hideWindow() {
        floatView?.let { runCatching { windowManager?.removeView(it) } }
        floatView = null
        infoText = null
    }

    /**
     * 拖动移动窗口；按下-抬起未超过系统触摸阈值则视为点击，弹出复制选项。
     *
     * @param dragHandle 监听触摸的视图（标题区，不含右上角关闭控件）
     * @param target     实际更新窗口位置的根视图（WindowManager 只认 addView 时的根视图）
     */
    private fun attachTouch(
        dragHandle: View,
        target: View,
        params: WindowManager.LayoutParams
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && kotlin.math.abs(dx) <= touchSlop &&
                        kotlin.math.abs(dy) <= touchSlop
                    ) {
                        return@setOnTouchListener true
                    }
                    dragging = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windowManager?.updateViewLayout(target, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) toggleCopyOption()
                    true
                }
                else -> false
            }
        }
    }

    /** 点击标题栏：切换显示/隐藏内嵌复制按钮，显示 3 秒后自动隐藏。 */
    private fun toggleCopyOption() {
        val btn = copyButton ?: return
        if (btn.visibility == View.VISIBLE) {
            btn.visibility = View.GONE
            uiHandler.removeCallbacks(hideCopyButton)
        } else {
            btn.visibility = View.VISIBLE
            uiHandler.removeCallbacks(hideCopyButton)
            uiHandler.postDelayed(hideCopyButton, 3_000)
        }
    }

    // ---------------------------------------------------------------- 前台服务

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.debug_mode))
            .setContentText(getString(R.string.debug_mode_summary))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "调试悬浮窗", NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "debug_float"
        private const val NOTIF_ID = 1003

        /** 服务当前是否在运行。 */
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, DebugFloatService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DebugFloatService::class.java))
        }
    }
}
