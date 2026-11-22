package com.trae.androidlyrics

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 系统下拉快捷设置磁贴「显示歌词悬浮窗」：点按切换桌面歌词悬浮窗的显示状态。
 *
 * 磁贴点击时应用通常处于后台，Android 8+ 会限制后台启动前台服务，
 * 因此实际开关动作交给透明中转页 [TileActionActivity] 在前台完成；
 * 中转页同时负责悬浮窗权限缺失时的引导。
 */
class DesktopLyricsTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, TileActionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(TileActionActivity.EXTRA_TOGGLE_DESKTOP, true)
            // 以用户此刻看到的磁贴状态为准：点「灰」是开启，点「亮」才是关闭。
            // 不能让中转页用 isRunning 猜：进程内状态与磁贴显示不同步时，
            // 一次「开启」会被判成「关闭」，表现为开启后立刻自动关闭。
            putExtra(TileActionActivity.EXTRA_TILE_ACTIVE, qsTile?.state == Tile.STATE_ACTIVE)
            // 每次点击带唯一序号：系统若重复投递同一次点击（CLEAR_TOP 复用中转页），
            // 中转页据此丢弃重复意图，避免连着执行「开启 → 关闭」两次。
            putExtra(TileActionActivity.EXTRA_CLICK_TOKEN, ++clickSeq)
        }
        // Android 14 起需改用 PendingIntent 版本，旧版本沿用 Intent 版本。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, REQ_TOGGLE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /** 按当前桌面歌词状态刷新磁贴（开启=高亮，关闭=灰显）。 */
    private fun refreshState() {
        val tile = qsTile ?: return
        // 只看进程内真实状态：进程被杀后 prefs.desktopLyrics 可能残留 true，
        // 但悬浮窗已随进程消失，磁贴若按 prefs 高亮会与实际状态相反。
        val on = DesktopLyricsService.isRunning
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (on) R.string.tile_desktop_on else R.string.tile_desktop_off
            )
        }
        tile.updateTile()
    }

    companion object {
        private const val REQ_TOGGLE = 21

        /** 每次点击的序号，供中转页去重系统重复投递的同一次点击。 */
        @Volatile private var clickSeq: Long = 0L

        /**
         * 其它入口（设置页、通知栏、桌面歌词服务自身）改变开关状态后，
         * 请求系统重新拉取磁贴状态，避免磁贴显示与实际状态不一致。
         */
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, DesktopLyricsTileService::class.java)
                )
            }
        }
    }
}
