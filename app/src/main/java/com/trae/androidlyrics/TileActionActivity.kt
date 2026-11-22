package com.trae.androidlyrics

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 快捷入口动作的透明中转页（本身无界面、不进最近任务）。
 *
 * 两个入口共用本页：下拉快捷设置磁贴（PendingIntent）、磁贴/快捷方式传入的
 * 动作 Intent（按 action 区分）。
 * 磁贴点击时应用处于后台，直接启动前台服务会被 Android 8+ 限制；
 * 先由本页在前台完成开关动作再立即结束，同时负责悬浮窗权限缺失时的引导。
 */
class TileActionActivity : AppCompatActivity() {

    /** 防止 onResume 因权限页返回、Activity 重建等原因重复执行开关。 */
    private var handled = false

    /** 上一次已处理的点击序号，用于丢弃系统重复投递的同一次点击。 */
    private var lastClickToken: Long = -1L

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // 结果回调时应用尚未回到前台，不适合在此启动前台服务；
            // 直接结束，用户再点一次磁贴即可开启（Toast 已给出提示）。
            handled = true
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // 从权限设置页返回时本页可能被重建，不重复执行开关动作
            handled = true
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 磁贴连点时复用同一中转页实例，只有「新的点击」才允许再执行一次；
        // 系统重复投递同一次点击（序号相同）时保持 handled=true，避免
        // 连续执行「开启 → 关闭」两遍。
        val token = intent.getLongExtra(EXTRA_CLICK_TOKEN, -1L)
        // 应用快捷方式不带序号（token < 0），保持原来的「每次点击都生效」；
        // 只有带序号且重复的磁贴点击才被丢弃。
        if (token < 0L || token != lastClickToken) handled = false
    }

    override fun onResume() {
        super.onResume()
        if (handled) return
        handled = true
        lastClickToken = intent?.getLongExtra(EXTRA_CLICK_TOKEN, -1L) ?: -1L
        when {
            intent?.action == ACTION_OPEN_LYRICS -> openLyrics()
            intent?.action == ACTION_TOGGLE_DEBUG -> {
                // 与桌面歌词开关同理：前台完成动作，避免后台启动前台服务被拦截
                window.decorView.post { toggleDebugFloat() }
            }
            intent?.action == ACTION_TOGGLE_DESKTOP ||
                intent?.getBooleanExtra(EXTRA_TOGGLE_DESKTOP, false) == true -> {
                // 延后一帧执行：此时 Activity 已真正进入前台，前台服务启动不会被系统拦截
                window.decorView.post { toggleDesktopLyrics() }
            }
            else -> finish()
        }
    }

    /** 兼容旧版已固定的「打开歌词界面」桌面图标：与桌面歌词面板保持同一套加载逻辑。 */
    private fun openLyrics() {
        val open = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(MainActivity.EXTRA_RESTORE_LAST, true)
        }
        runCatching { startActivity(open) }
        finish()
    }

    private fun toggleDesktopLyrics() {
        if (isFinishing || isDestroyed) return
        val prefs = Prefs(this)
        // 磁贴点击带用户此刻看到的状态；应用快捷方式不带，按「启用桌面歌词」
        // 开关判断。两种入口都先看进程内 isRunning，并用 autoRecoveredPending
        // 修正「进程刚自动恢复、点击其实是唤起」的情形。
        // 关键：用户明确要「开启」时（磁贴是灰态），本次点击绝不能关闭服务。
        val tileActive = intent?.takeIf { it.hasExtra(EXTRA_TILE_ACTIVE) }
            ?.getBooleanExtra(EXTRA_TILE_ACTIVE, false)
        // 任何入口都要消费这个标志：进程刚被自动恢复时服务已在运行，但用户此刻
        // 看到的磁贴/快捷方式还是「关闭」，这次点击的意图只能是开启。
        val recovered = DesktopLyricsService.consumeAutoRecoveredPending()
        val running = DesktopLyricsService.isRunning
        // 只有同时满足「服务确实在跑」「不是刚自动恢复」「不是用户明确要开启」
        // 才允许关闭。磁贴传入灰态（tileActive=false）时一律按开启处理，
        // 绝不因为状态刷新 / 重复投递把刚开启的桌面歌词又关掉。
        val turnOff = running && !recovered && when (tileActive) {
            true -> true                 // 用户看到的是亮态：正常切换为关闭
            false -> false               // 用户看到的是灰态：只能开启
            else -> prefs.desktopLyrics  // 应用快捷方式：按开关记录判断
        }

        if (turnOff) {
            prefs.desktopLyrics = false
            DesktopLyricsService.stop(this)
            DesktopLyricsTileService.requestUpdate(this)
            UiTips.show(this, R.string.notif_desktop_off_text)
            finish()
            return
        }

        // 本次点击意图是「开启」。服务其实已在运行（磁贴状态未刷新 / 冷启动
        // 自动恢复）时，只同步开关与磁贴，绝不重复启动、更不关闭服务。
        if (DesktopLyricsService.isRunning) {
            prefs.desktopLyrics = true
            DesktopLyricsTileService.requestUpdate(this)
            UiTips.show(this, R.string.notif_desktop_on_text)
            finish()
            return
        }
        if (!canDrawOverlays()) {
            UiTips.show(this, R.string.tile_overlay_permission_hint, android.widget.Toast.LENGTH_LONG)
            if (!launchOverlayPermission()) finish()
            return
        }
        prefs.desktopLyrics = true
        DesktopLyricsService.start(this)
        DesktopLyricsTileService.requestUpdate(this)
        UiTips.show(this, R.string.notif_desktop_on_text)
        finish()
    }

    /**
     * 通知栏快捷开关「歌词调试信息」：切换调试信息悬浮窗的显示状态。
     *
     * 与设置页的「调试模式」开关同一套语义：开关写入 prefs.debugMode，
     * 悬浮窗服务随之启停；悬浮窗权限缺失时先引导授权。
     */
    private fun toggleDebugFloat() {
        if (isFinishing || isDestroyed) return
        val prefs = Prefs(this)
        // 是否正在显示只看进程内真实状态，与桌面歌词开关同一口径
        if (DebugFloatService.isRunning) {
            prefs.debugMode = false
            DebugFloatService.stop(this)
            DebugInfoTileService.requestUpdate(this)
            UiTips.show(this, R.string.debug_float_off)
            finish()
            return
        }
        if (!canDrawOverlays()) {
            UiTips.show(this, R.string.tile_overlay_permission_hint, android.widget.Toast.LENGTH_LONG)
            if (!launchOverlayPermission()) finish()
            return
        }
        prefs.debugMode = true
        DebugFloatService.start(this)
        DebugInfoTileService.requestUpdate(this)
        UiTips.show(this, R.string.debug_float_on)
        finish()
    }

    /** 跳转系统「显示在其他应用上层」权限页；ROM 无该权限页时返回 false。 */
    private fun launchOverlayPermission(): Boolean {
        val permIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        if (permIntent.resolveActivity(packageManager) != null) {
            overlayPermissionLauncher.launch(permIntent)
            return true
        }
        return false
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    companion object {
        /** 标记本次启动来自「显示歌词悬浮窗」磁贴（PendingIntent extra）。 */
        const val EXTRA_TOGGLE_DESKTOP = "extra_toggle_desktop"

        /** 磁贴点击瞬间用户看到的状态：true=高亮(该关)，false=灰显(该开)。 */
        const val EXTRA_TILE_ACTIVE = "extra_tile_active"

        /** 每次磁贴点击的唯一序号，用于丢弃系统重复投递的同一次点击。 */
        const val EXTRA_CLICK_TOKEN = "extra_click_token"

        /** 应用快捷方式动作：切换桌面歌词悬浮窗。 */
        const val ACTION_TOGGLE_DESKTOP = "com.trae.lyrics.action.TOGGLE_DESKTOP"

        /** 通知栏快捷开关动作：切换调试信息悬浮窗。 */
        const val ACTION_TOGGLE_DEBUG = "com.trae.lyrics.action.TOGGLE_DEBUG"

        /** 兼容旧版本已固定到桌面的「打开歌词界面」快捷图标；新版本不再提供该入口。 */
        const val ACTION_OPEN_LYRICS = "com.trae.lyrics.action.OPEN_LYRICS"
    }
}
