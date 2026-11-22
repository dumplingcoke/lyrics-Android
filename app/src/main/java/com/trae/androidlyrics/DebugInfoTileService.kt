package com.trae.androidlyrics

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 系统下拉快捷开关「歌词调试信息」：点按切换调试信息悬浮窗的显示状态。
 *
 * 磁贴点击时应用通常处于后台，Android 8+ 会限制后台启动前台服务，
 * 因此实际开关动作交给透明中转页 [TileActionActivity] 在前台完成；
 * 中转页同时负责悬浮窗权限缺失时的引导。
 */
class DebugInfoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, TileActionActivity::class.java).apply {
            action = TileActionActivity.ACTION_TOGGLE_DEBUG
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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

    /** 按当前调试悬浮窗状态刷新磁贴（开启=高亮，关闭=灰显）。 */
    private fun refreshState() {
        val tile = qsTile ?: return
        // 只看进程内真实状态：与设置页「调试模式」开关、悬浮窗关闭控件同口径
        val on = DebugFloatService.isRunning
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (on) R.string.tile_desktop_on else R.string.tile_desktop_off
            )
        }
        tile.updateTile()
    }

    companion object {
        private const val REQ_TOGGLE = 31

        /**
         * 其它入口（磁贴中转页、设置页、悬浮窗关闭控件）改变开关状态后，
         * 请求系统重新拉取磁贴状态，避免磁贴显示与实际状态不一致。
         */
        fun requestUpdate(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, DebugInfoTileService::class.java)
                )
            }
        }
    }
}
