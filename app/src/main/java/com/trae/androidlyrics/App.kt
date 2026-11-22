package com.trae.androidlyrics

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import java.lang.ref.WeakReference

/**
 * 应用入口：启动时根据用户设置的主题模式应用深色/浅色/跟随系统。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 初始化日志抓取（存储 Context 引用，后续按需写入）
        LogCapture.init(this)
        LogCapture.i("App", "应用启动")
        // 旧版本「已应用」的在线歌词只留在独立缓存目录，启动时在后台补写一份到
        // 歌词存储（一次性、带标记文件），不阻塞主线程也不影响联网去重逻辑。
        Thread({ OnlineLyricCache.syncAppliedToLyricsStore(applicationContext) }, "online-cache-sync")
            .apply { isDaemon = true }
            .start()
        // 亮屏 / 熄屏 / 解锁诊断（只写日志，不参与任何业务逻辑）
        registerScreenDiagnostics()
        // 启动时设置全局默认主题（仅应用进程启动时调用一次，不会触发重建）
        val prefs = Prefs(this)
        applyGlobalTheme(prefs.themeMode)
        // 解析选项由 Application 统一同步到解析器，通知监听/桌面歌词服务也是同一进程
        LyricsParser.showUntimedLines = prefs.showUntimedLines
        recoverNotificationListenerIfUpdated()
        recoverDesktopLyricsIfEnabled()
        recoverDebugFloatIfEnabled()
        // 按设置补发桌面歌词开关通知（无论桌面歌词是否已开启）
        DesktopLyricsService.postStartupToggleNotification(this)
    }

    /**
     * 亮屏 / 熄屏 / 解锁诊断：全项目仅此处注册系统屏幕广播，**只写日志**。
     *
     * 目的：定位「从锁屏打开屏幕时歌词中断一下」。日志里同时给出
     * `gap`（距上次屏幕事件）、`wallGap`（墙钟差）、`sleepDelta`
     * （elapsedRealtime 与 uptimeMillis 之差的增量，即 deep sleep 时长），
     * 由此区分：系统深睡 / 主线程停摆 / 通知回调积压 / NLS 断连重连。
     * 不读取也不改写任何播放状态，不触发刷新或重锚。
     */
    private fun registerScreenDiagnostics() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                val elapsed = SystemClock.elapsedRealtime()
                val uptime = SystemClock.uptimeMillis()
                val wall = System.currentTimeMillis()
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val interactive = runCatching { pm?.isInteractive }.getOrNull()
                val keyguardLocked = runCatching { km?.isKeyguardLocked }.getOrNull()
                val deviceLocked = runCatching { km?.isDeviceLocked }.getOrNull()
                val skew = elapsed - uptime
                val sleepDelta = skew - Diag.lastClockSkewMs
                val gap = Diag.sinceScreenEvent(elapsed)
                val wallGap = if (Diag.lastScreenEventWallMs > 0L)
                    wall - Diag.lastScreenEventWallMs else -1L
                Diag.lastClockSkewMs = skew
                Diag.lastScreenEvent = action
                Diag.lastScreenEventElapsedMs = elapsed
                Diag.lastScreenEventWallMs = wall
                when (action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Diag.lastScreenOnElapsedMs = elapsed
                        Diag.screenOnCount++
                    }
                    Intent.ACTION_SCREEN_OFF -> Diag.lastScreenOffElapsedMs = elapsed
                }
                LogCapture.i(
                    "Screen",
                    "event=$action elapsed=$elapsed wall=$wall gap=${gap}ms " +
                        "wallGap=${wallGap}ms sleepDelta=${sleepDelta}ms " +
                        "interactive=$interactive keyguardLocked=$keyguardLocked " +
                        "deviceLocked=$deviceLocked screenOnSeq=${Diag.screenOnCount} " +
                        "rebindSeq=${Diag.rebindSeq} sinceRebind=${Diag.sinceRebind(elapsed)}ms"
                )
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
        }.onFailure {
            LogCapture.w("Screen", "register screen diagnostics failed: ${it.message}")
        }
    }

    /** 调试模式开启时，应用进程重启后自动恢复歌曲信息悬浮窗。 */
    private fun recoverDebugFloatIfEnabled() {
        val prefs = Prefs(this)
        if (!prefs.debugMode) return
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else true
        if (canOverlay) {
            DebugFloatService.start(this)
        }
    }

    /**
     * 应用进程被杀（例如从最近任务直接划掉）后重新启动时，自动恢复桌面歌词。
     *
     * 这类「后台自动恢复」只在「桌面歌词进程保护」开启时生效。保护关闭时，
     * 无论进程是划掉任务、一键清理，还是通知监听重连 / 系统组件绑定引起的
     * 重启，后台一律不拉起桌面歌词；用户主动打开应用时，由
     * [DesktopLyricsService.recoverInForeground] 按「启用桌面歌词」开关
     * （开关关着时再看 [Prefs.autoStartDesktopLyrics]）在前台自启动。
     *
     * 关键：本函数一律不改写 [Prefs.desktopLyrics]——它是启动应用时判断是否
     * 自启动的依据，只能由用户主动开关写入。
     * 是否真正在显示以进程内的 [DesktopLyricsService.isRunning] 为准
     * （磁贴、通知栏开关都只看它）。
     *
     * 保护开启时恢复条件取两者之一：
     * 1. [Prefs.desktopLyrics] 为 true —— 进程被杀时 onDestroy / turnOffDesktopLyrics
     *    不会执行，这个开关会保持 true；
     * 2. [Prefs.autoStartDesktopLyrics] 为 true —— 原有的「启动时自动打开」。
     *
     * Android 8+（尤其 12+）可能拒绝后台启动前台服务，这里不直接抛异常，
     * 失败时留给 [MainActivity.onResume] 在前台重试（见 [DesktopLyricsService.recoverInForeground]）。
     */
    private fun recoverDesktopLyricsIfEnabled() {
        val prefs = Prefs(this)
        // 保护关闭：任何后台进程重启都不许自动恢复桌面歌词
        if (!prefs.desktopProcessGuard) return
        if (!prefs.desktopLyrics && !prefs.autoStartDesktopLyrics) return
        val canOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else true
        if (!canOverlay) return
        // 记录自动恢复时间点：若进程是被快捷入口（磁贴/快捷方式）拉起的，
        // 紧随其后的开关点击意图是"打开"而非"关闭"，由 TileActionActivity 识别
        DesktopLyricsService.autoRecoveredAt = android.os.SystemClock.elapsedRealtime()
        // 一次性标志：紧接着的快捷入口点击视为「唤起」而不是「关闭」；
        // 用户正常打开主界面后由 recoverInForeground 清除
        DesktopLyricsService.autoRecoveredPending = true
        // onCreate 阶段应用还没有前台 Activity，后台拉起 FGS 可能被系统拒绝：
        // 吞掉异常并登记"待前台恢复"，由 Activity 真正进入前台后补一次。
        val started = runCatching { DesktopLyricsService.start(this) }.isSuccess
        if (!started) DesktopLyricsService.recoveryPending = true
    }

    /**
     * 应用更新后系统常把通知监听绑定断开（授权条目仍在），导致自动匹配失效。
     * 检测到版本变化时标记"待重绑"，每次进程启动都尝试恢复绑定，
     * 直到监听服务真正重连成功（[MusicNotificationListenerService.onListenerConnected]
     * 清除标记）为止，避免单次 requestRebind 被系统吞掉后自动匹配永久失效。
     */
    private fun recoverNotificationListenerIfUpdated() {
        val prefs = Prefs(this)
        val currentVersion = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
        }.getOrNull() ?: return
        if (prefs.lastVersionCode != currentVersion) {
            prefs.lastVersionCode = currentVersion
            prefs.needsNlsRebind = true
        }
        if (prefs.needsNlsRebind && MusicNotificationListenerService.isEnabled(this)) {
            MusicNotificationListenerService.recover(this)
        }
    }

    companion object {

        /**
         * 主题版本号：全局主题的「实际亮/暗」发生变化时自增。
         * 后台 Activity 的视图不会随 setDefaultNightMode 自动重绘，
         * 回到前台时靠这个版本号判断是否需要重建一次，避免视图停留在旧配色。
         * 只在视觉真的变了时才递增，否则「跟随系统(当前暗) → 强制暗」也会让
         * 播放页回前台白白重建一次（正是黑屏一下的来源之一）。
         */
        @Volatile
        var themeVersion: Int = 0
            private set

        /** 主题切换过渡：recreate 间隙用旧界面快照盖住窗口，避免露出系统窗口底色（黑一下）。 */
        @Volatile private var themeSnapshot: Bitmap? = null
        private var themeSnapshotOwner: String? = null
        private var themeSnapshotTime = 0L
        private val uiHandler = Handler(Looper.getMainLooper())

        /**
         * 外观变更的合并窗口：AMOLED / 动态颜色 / 配色 / 自定义色都只写偏好，
         * 视图重建由这里在短时间窗内合并成一次，避免连点时多个 recreate 排队。
         */
        private const val RECREATE_MERGE_WINDOW_MS = 120L
        private var pendingRecreate: Runnable? = null

        /** 将偏好的主题值映射为 AppCompat 夜间模式常量。 */
        fun themeToNightMode(themeMode: Int): Int = when (themeMode) {
            Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        /** 按偏好模式算出期望的 uiMode 夜/昼标志；跟随系统时取系统当前配置。 */
        fun expectedNightFlag(themeMode: Int): Int = when (themeMode) {
            Prefs.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            Prefs.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            else -> systemNightFlag()
        }

        private fun nightFlagOfDefault(defaultMode: Int): Int = when (defaultMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            else -> systemNightFlag()
        }

        private fun systemNightFlag(): Int =
            Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        /**
         * 同步全局默认主题。启动时调用一次；切换主题时也调用，
         * 让此后新建的 Activity 从创建之初就处于正确的昼夜配置，
         * 不必在 onCreate 里再切一次 localNightMode 而触发额外的 Activity 重建。
         */
        fun applyGlobalTheme(themeMode: Int) {
            val nightMode = themeToNightMode(themeMode)
            val oldDefault = AppCompatDelegate.getDefaultNightMode()
            if (oldDefault == nightMode) return
            // 全局默认夜间模式本身会重建所有存活界面，挂起的局部重建已无意义；
            // 取消它可以避免「切主题 + 之前点了配色」时连续重建两次。
            cancelAppearanceRecreate()
            val visualChanged = nightFlagOfDefault(oldDefault) != expectedNightFlag(themeMode)
            AppCompatDelegate.setDefaultNightMode(nightMode)
            if (visualChanged) themeVersion++
        }

        /**
         * 确保当前 Activity 使用偏好主题。
         *
         * 关键：AppCompat 的 calculateNightMode 优先取 localNightMode——一旦在某个
         * Activity 上显式设置过（≠ UNSPECIFIED），它就被「钉死」在该值上，
         * [AppCompatDelegate.setDefaultNightMode] 对它彻底失效（不刷新也不重建），
         * 表现为设置页切主题当场不生效，必须退出重进、回到播放页才被兜底重建。
         *
         * 因此这里只在「全局默认与偏好不一致」的兜底场景才落 localNightMode
         * （setter 内部有比较，且 applyDayNight 在配置一致时不会重建，
         * 不会出现「进入设置页/返回主界面白白重建一次」）；
         * 默认一致时保持 UNSPECIFIED 跟随全局默认，让 setDefaultNightMode
         * 能按需重建所有存活界面（前台当场切换、后台界面在不可见时重建）。
         */
        fun applyThemeImmediate(activity: AppCompatActivity, themeMode: Int) {
            val nightMode = themeToNightMode(themeMode)
            if (AppCompatDelegate.getDefaultNightMode() == nightMode) {
                // 全局默认已能给出正确昼夜状态：不落 localNightMode；
                // 若历史路径已设置过，重置回 UNSPECIFIED（重新跟随全局默认）。
                if (activity.delegate.localNightMode != AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) {
                    activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
                }
                return
            }
            if (activity.delegate.localNightMode != nightMode) {
                activity.delegate.localNightMode = nightMode
            }
        }

        /**
         * 在因切换主题而 recreate 之前抓取当前界面。
         *
         * recreate 会销毁 DecorView，新窗口首帧绘制前只剩主题的 windowBackground，
         * 切到深色主题时就是明显的「黑一下」。把旧界面贴图盖到新窗口上，
         * 等新界面绘制完成后再揭开，过渡期间完全不露底色。
         *
         * 性能：旧实现按 1:1 全屏分配 RGB_565 位图并在主线程同步 decor.draw(Canvas)，
         * 1080×2400 约 5 MB 分配 + 整棵视图树重绘，正是切主题掉帧的主因。
         * 这里改为 1/2 分辨率（内存与绘制量都降到约 1/4），过渡层放大显示一帧，
         * 肉眼无法分辨；「减少动效」开启时直接跳过快照，连这点开销也不付。
         */
        fun freezeForThemeSwitch(activity: Activity) {
            // 减少动效：不生成位图、不加覆盖层，主题直接切换。
            if (AppearancePrefs(activity.applicationContext).reduceMotion) {
                releaseThemeSnapshot()
                return
            }
            val decor = activity.window?.decorView ?: return
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return
            val scale = 0.5f
            val bw = (w * scale).toInt().coerceAtLeast(1)
            val bh = (h * scale).toInt().coerceAtLeast(1)
            val bitmap = runCatching {
                Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565).also {
                    val canvas = Canvas(it)
                    canvas.scale(scale, scale)
                    decor.draw(canvas)
                }
            }.getOrNull() ?: return
            releaseThemeSnapshot()
            themeSnapshot = bitmap
            themeSnapshotOwner = activity.javaClass.name
            themeSnapshotTime = SystemClock.elapsedRealtime()
            // 没被消费（例如亮/暗实际没变、界面没重建）时自动回收，避免常驻内存
            uiHandler.postDelayed({
                if (themeSnapshot === bitmap) releaseThemeSnapshot()
            }, 2000)
        }

        /**
         * 合并外观类变更后重建视图。
         *
         * 参考 lyrics-android / Capsulyric 的做法：偏好写入与视图刷新解耦——
         * 开关、配色只写偏好并即时更新自身状态，视图重建按需合并为一次；
         * 而不是每个控件变化都立刻 recreate 整个 Activity 并连锁重建栈内其它页面。
         *
         * [WeakReference] 避免 Activity 已销毁时仍被 Handler 延迟任务持有。
         */
        fun scheduleAppearanceRecreate(activity: Activity) {
            pendingRecreate?.let { uiHandler.removeCallbacks(it) }
            val ref = WeakReference(activity)
            val task = Runnable {
                pendingRecreate = null
                val target = ref.get() ?: return@Runnable
                if (target.isFinishing || target.isDestroyed) return@Runnable
                freezeForThemeSwitch(target)
                target.recreate()
            }
            pendingRecreate = task
            uiHandler.postDelayed(task, RECREATE_MERGE_WINDOW_MS)
        }

        /** 全局夜间模式切换会自行重建界面，先取消尚未执行的局部重建。 */
        fun cancelAppearanceRecreate() {
            pendingRecreate?.let { uiHandler.removeCallbacks(it) }
            pendingRecreate = null
        }

        /** 重建后的新界面首帧贴上旧快照；由 [ThemedActivity] 在 setContentView 前调用。 */
        fun coverThemeSwitchIfNeeded(activity: Activity) {
            val bitmap = themeSnapshot ?: return
            if (themeSnapshotOwner != activity.javaClass.name) return   // 别抢其它页面的快照
            if (SystemClock.elapsedRealtime() - themeSnapshotTime > 1500) {
                releaseThemeSnapshot()
                return
            }
            val decor = activity.window?.decorView as? ViewGroup
            if (decor == null) {
                releaseThemeSnapshot()
                return
            }
            // 所有权转交给覆盖层，由覆盖层负责回收。
            themeSnapshot = null
            themeSnapshotOwner = null
            val overlay = View(activity).apply {
                background = BitmapDrawable(activity.resources, bitmap).apply {
                    gravity = Gravity.FILL      // 1/2 分辨率快照拉伸铺满整个窗口
                    setFilterBitmap(true)      // 放大时双线性过滤，过渡帧不出现锯齿
                }
                isClickable = false             // 过渡期间触摸仍可穿透到下面的新界面
            }
            if (runCatching {
                    decor.addView(
                        overlay,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }.isFailure) {
                bitmap.recycle()
                return
            }

            var removed = false
            fun removeOverlay() {
                if (removed) return
                removed = true
                (overlay.parent as? ViewGroup)?.removeView(overlay)
                overlay.background = null
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            // preDraw 表示新界面已经测量、布局并即将真正绘出。此时先把旧快照画完
            // 当前帧，再在下一帧前揭开；不再按固定 180ms 猜测初始化是否完成。
            decor.viewTreeObserver.addOnPreDrawListener(object :
                android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (decor.viewTreeObserver.isAlive) {
                        decor.viewTreeObserver.removeOnPreDrawListener(this)
                    }
                    decor.post { removeOverlay() }
                    return true
                }
            })
            // 页面异常未能绘制时也必须释放位图，避免覆盖层永久挡住新界面。
            uiHandler.postDelayed({ removeOverlay() }, 1500)
        }

        private fun releaseThemeSnapshot() {
            themeSnapshot?.recycle()
            themeSnapshot = null
            themeSnapshotOwner = null
        }
    }
}
