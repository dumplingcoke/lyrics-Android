package com.trae.androidlyrics

import android.graphics.Bitmap

/**
 * 「监听状态」页的媒体会话快照。
 *
 * 字段口径与调试信息悬浮窗（[MusicNotificationListenerService.buildDebugSnapshot]）
 * 保持一致：[#i] 会话编号、active=true/false、pos/dur、baseAge、title/artist，
 * 另外补上会话观测时间，便于横向对比多个残留会话。
 *
 * 快照是不可变数据，由监听服务在主线程发布，界面只读，避免跨线程访问
 * MediaController 集合带来的并发问题。
 */
data class MediaSessionSnapshot(
    /** 会话编号：调试悬浮窗里的 #0 / #1 / #2 … */
    val index: Int,
    /** 会话对象标识（System.identityHashCode），对应调试信息的 sid。 */
    val sid: Int,
    val pkg: String,
    val appLabel: String,
    /** 是否为当前选中的进度源，调试信息里显示为 active=true。 */
    val active: Boolean,
    val stateName: String,
    val isPlaying: Boolean,
    val posMs: Long,
    val durationMs: Long,
    /** 播放器状态基准距本次观测的时间差（毫秒）。 */
    val baseAgeMs: Long,
    val title: String,
    val artist: String,
    val album: String,
    /** 快照观测时刻（墙钟毫秒），界面格式化成 HH:mm:ss。 */
    val observedAtMs: Long,
    val cover: Bitmap? = null,
)

/**
 * 应用内部计时快照：调试悬浮窗「—— 应用内状态 ——」段的精简版，
 * 供「监听状态」页最上方卡片与左右滑动的第一页展示。
 */
data class InternalClockSnapshot(
    val observedAtMs: Long,
    val rows: List<Pair<String, String>>,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val title: String,
    val artist: String,
    /** 实际用于定位歌词行的进度（含用户偏移），与主页/桌面歌词同口径。 */
    val lyricPositionMs: Long = 0L,
) {
    companion object {
        fun empty() = InternalClockSnapshot(
            observedAtMs = System.currentTimeMillis(),
            rows = listOf("监听服务" to "未连接"),
            positionMs = 0L,
            durationMs = 0L,
            isPlaying = false,
            title = "",
            artist = "",
        )
    }
}
