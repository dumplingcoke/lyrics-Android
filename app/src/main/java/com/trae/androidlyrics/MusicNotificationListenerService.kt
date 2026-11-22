package com.trae.androidlyrics

import android.app.Notification
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.File

/**
 * 全局播放进度持有者。
 *
 * 由 [MusicNotificationListenerService] 从当前活跃的媒体会话中采集，
 * 供主界面歌词滚动与桌面歌词读取。
 */
object MusicProgress {
    @Volatile var positionMs: Long = 0L
    @Volatile var isPlaying: Boolean = false
    @Volatile var durationMs: Long = 0L
    @Volatile var title: String? = null
    @Volatile var artist: String? = null

    /**
     * 当前进度来源所属应用包名。按应用的「毫秒信息不可信」开关以此为准，
     * 由 [MusicNotificationListenerService] 在选定活跃会话时同步写入。
     */
    @Volatile var packageName: String? = null

    /** 播放速度（倍速播放时位置推算需要乘上）。 */
    @Volatile var speed: Float = 1f

    /** positionMs 快照对应的 elapsedRealtime 基准时刻，用于推算播放中的实时位置。 */
    @Volatile var stateTimeMs: Long = 0L

    /** 记录上一份快照时的本机 elapsedRealtime：用于判断新快照是否"跳跃式前进"。 */
    @Volatile var snapshotElapsedMs: Long = 0L

    /** 当前快照的时间基准是否可信（播放器基准与本机时钟同源）；不可信时兜底采样。 */
    @Volatile var baseTrusted: Boolean = false

    /**
     * 应用内 seek 的目标位置与宽限截止时刻：宽限期内消费端（主界面/桌面歌词）
     * 以此对齐高亮——播放器 seek 落点常略早于目标（关键帧对齐），直接按估算
     * 进度找行会命中上一行，表现为"点击跳转后高亮上一行歌词"。
     */
    @Volatile var seekUiTargetMs: Long = -1L
    @Volatile var seekUiTargetUntil: Long = 0L

    /**
     * 暂停态 seek 锁：暂停时点击歌词行发起 seek 后，播放器的 PlaybackState.position
     * 常不立即更新（回调里仍是 seek 前旧位置或 0 占位值），若按正常快照处理会
     * 把乐观目标覆盖回旧位置，导致高亮回跳到暂停前的行。锁定期间强制 positionMs
     * 维持目标值、忽略陈旧快照，直到恢复播放后由真实快照校正。-1 表示未锁定。
     */
    @Volatile var pausedSeekTargetMs: Long = -1L

    /**
     * 应用内 seek 的「高亮钉行会话」。
     *
     * 点击歌词行只做两件事：发出 seekTo、把估算基准平移到目标（或整秒基准）
     * 后由应用自己按墙钟计时。**不再读取播放器快照来判定落点或校正时间**——
     * PlaybackState.position / lastPositionUpdateTime 的偏移很常见（跳转提示里
     * 抓到的「实际进度」经常偏几百毫秒），拿它计时会把逐字动画带偏。
     *
     * 这里保留的只是短钉行窗口：点击瞬间把高亮固定在被点击行，防止偏移把
     * 找行结果擦到上一行。进度本身由 [currentPositionMs] 连续推进，不冻结。
     */
    @Volatile var seekPendingTargetMs: Long = -1L
    @Volatile var seekPendingUntilMs: Long = 0L

    /** 被点击歌词行的原始时间戳（未叠加任何偏移），用于把高亮钉在这一行。 */
    @Volatile var seekPendingLineMs: Long = -1L

    /**
     * 「按整秒计算时间」：开启「当前应用的毫秒信息不可信」的应用不支持毫秒
     * seek（会把 19.611 吸附到 19.000）。这类应用点击跳转后，应用自身计时也
     * 从整秒落点起步，显示进度冻结在 [seekCatchupTargetMs]（请求目标），等自己
     * 按墙钟走完这段毫秒差才放行逐字动画；冻结期间高亮钉在被点击行。
     */
    @Volatile var seekCatchupTargetMs: Long = -1L

    /** 补偿的绝对超时上限（仅兜底，放行以音频位置为准）；暂停时逐帧顺延。 */
    @Volatile var seekCatchupUntilMs: Long = 0L

    /** 补偿实际放行后的高亮钉行截止，与上面的超时上限解耦。 */
    @Volatile var seekCatchupPinUntilMs: Long = 0L

    /** 补偿结束后继续钉住高亮的一小段宽限，避免整体偏移让进度擦到上一行。 */
    const val SEEK_CATCHUP_PIN_TAIL_MS = 300L

    /** 补偿结束后兜底复检的静默期：期间不把「落点偏早」误判为 seek 未生效。 */
    private const val SEEK_CATCHUP_RECHECK_GUARD_MS = 1_500L

    /** 暂停期间补偿计时不推进，每帧顺延的切片长度（需覆盖恢复播放后的剩余差）。 */
    private const val SEEK_CATCHUP_PAUSE_SLICE_MS = 5_000L

    /** 超时上限在预期等待时长之外额外留出的余量。 */
    private const val SEEK_CATCHUP_TIMEOUT_SLACK_MS = 2_500L

    /**
     * seek 相关的高亮钉行是否仍有效（短钉行窗口 / 整秒自计时 / 放行宽限）。
     * 消费端据此把高亮钉在 [seekPendingLineMs]，避免偏移造成回跳上一行。
     */
    fun isSeekPending(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (seekPendingTargetMs >= 0L && now < seekPendingUntilMs) return true
        if (isSeekCatchupActive()) return true
        return seekCatchupPinUntilMs > 0L && now < seekCatchupPinUntilMs
    }

    /** 登记一次应用内 seek 的短钉行会话；[lineTimeMs] >= 0 表示由点击歌词行发起。 */
    fun markSeekPending(targetMs: Long, lineTimeMs: Long, untilMs: Long) {
        seekPendingTargetMs = targetMs
        seekPendingLineMs = lineTimeMs
        seekPendingUntilMs = untilMs
        // 新 seek 覆盖上一轮尚未结束的补偿
        seekCatchupTargetMs = -1L
        seekCatchupUntilMs = 0L
        seekCatchupPinUntilMs = 0L
    }

    /**
     * 延长钉行会话（快速连点同一行时调用），保住被点击行时间戳。
     * 只影响高亮，不冻结进度：进度始终由应用自身计时基准推进。
     */
    fun extendSeekPending(untilMs: Long) {
        if (seekPendingTargetMs < 0L) return
        seekPendingUntilMs = maxOf(seekPendingUntilMs, untilMs)
    }

    /**
     * 毫秒信息不可信的应用按整秒计时：从「目标的整秒基准」走到目标需要 [holdMs]
     * 墙钟时间（已按倍速折算），期间显示进度冻结在目标、逐字动画不提前起跑。
     */
    fun startSeekCatchup(targetMs: Long, holdMs: Long, now: Long) {
        // 结束短钉行会话，但保留被点击行时间戳供高亮钉行使用
        seekPendingTargetMs = -1L
        seekPendingUntilMs = 0L
        val wait = holdMs.coerceAtLeast(0L)
        seekCatchupTargetMs = targetMs
        seekCatchupUntilMs = now + wait + SEEK_CATCHUP_TIMEOUT_SLACK_MS
        seekCatchupPinUntilMs = now + wait + SEEK_CATCHUP_PIN_TAIL_MS
    }

    /** 补偿是否正在冻结显示进度。 */
    fun isSeekCatchupActive(): Boolean =
        seekCatchupTargetMs >= 0L && SystemClock.elapsedRealtime() < seekCatchupUntilMs

    /** 最近发生过补偿（含结束后静默期）：兜底复检需跳过，避免误补发 seek。 */
    fun isSeekCatchupRecent(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (isSeekCatchupActive()) return true
        return seekCatchupPinUntilMs > 0L &&
            now < seekCatchupPinUntilMs + SEEK_CATCHUP_RECHECK_GUARD_MS
    }

    /** 清除 seek 相关状态（外部跳转 / 切歌 / 超时 / 补偿被新操作打断时调用）。 */
    fun clearSeekPending() {
        seekPendingTargetMs = -1L
        seekPendingLineMs = -1L
        seekCatchupTargetMs = -1L
        seekCatchupUntilMs = 0L
        seekCatchupPinUntilMs = 0L
    }

    /**
     * 歌词消费端使用的显示进度：点击后完全由应用自身计时基准推进，不再冻结
     * 等待播放器落点。唯一例外是「毫秒信息不可信」应用的整秒自计时——基准从整秒起步，
     * 显示进度冻结在目标，等自身计时走完毫秒差才放行逐字动画；暂停时基准
     * 不推进，冻结自然延续到恢复播放。超时只是兜底，避免异常时永久钉住。
     */
    fun displayPositionMs(): Long {
        val now = SystemClock.elapsedRealtime()
        val catchTarget = seekCatchupTargetMs
        if (catchTarget >= 0L) {
            val estimated = currentPositionMs()
            if (estimated < catchTarget && now < seekCatchupUntilMs) {
                // 暂停时音频没有前进：顺延超时上限，否则墙钟会把冻结提前放行
                if (!isPlaying) seekCatchupUntilMs = now + SEEK_CATCHUP_PAUSE_SLICE_MS
                return catchTarget
            }
            // 超时兜底：估算仍落后时把基准挂到目标，避免放行瞬间进度倒退
            if (estimated < catchTarget) {
                positionMs = catchTarget
                stateTimeMs = now
            }
            seekCatchupTargetMs = -1L
            seekCatchupPinUntilMs = now + SEEK_CATCHUP_PIN_TAIL_MS
        }
        // 短钉行会话只管高亮：到期清掉被点击行时间戳，进度照常由自身计时推进
        if (seekPendingTargetMs >= 0L && now >= seekPendingUntilMs) {
            seekPendingTargetMs = -1L
            seekPendingLineMs = -1L
        }
        return currentPositionMs()
    }

    /** 根据播放状态推算当前实时位置（毫秒）。 */
    fun currentPositionMs(): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0L)
        val elapsed = ((SystemClock.elapsedRealtime() - stateTimeMs) * speed).toLong()
        val pos = (positionMs + elapsed).coerceAtLeast(0L)
        // 移除 duration 钳制：播放器循环播放时（如 Telegram 内置播放器）会持续上报
        // position=duration 作为快照，钳制会把估算永远钉在末尾导致歌词卡住。
        // 解除钳制后估算自由增长，消费端对超过 duration 的值取模即可匹配正确的循环位置。
        return pos
    }

    /** 是否已经拿到有效的媒体会话进度。 */
    fun hasValidProgress(): Boolean = stateTimeMs > 0L

    /**
     * 当前系统里是否检测到可用的播放来源（媒体会话）。
     *
     * 与 [hasValidProgress] 不同：后者只表示「本次进程曾经拿到过进度」，
     * 播放器退出 / 通知被移除后仍然为 true；这里随活跃会话列表实时更新，
     * 供主页歌词与桌面歌词在完全没有任何播放来源时显示「没有检测到播放」。
     */
    @Volatile var hasPlaybackSession: Boolean = false

    /**
     * 当前由「点击歌词」产生的高亮冻结目标时间（毫秒）；-1 表示没有冻结。
     *
     * 冻结可能来自四处：短钉行窗口、seek 宽限对齐、整秒自计时的显示冻结、
     * 暂停态 seek 锁。消费端据此判断播放时间是否已经推进到下一行。
     */
    fun lyricFreezeTargetMs(): Long {
        val now = SystemClock.elapsedRealtime()
        if (seekCatchupTargetMs >= 0L && now < seekCatchupUntilMs) return seekCatchupTargetMs
        if (seekUiTargetMs >= 0L && now < seekUiTargetUntil) return seekUiTargetMs
        if (isSeekPending() && seekPendingLineMs >= 0L) return seekPendingLineMs
        if (pausedSeekTargetMs >= 0L) return pausedSeekTargetMs
        return -1L
    }

    /**
     * 强行解除「点击歌词」产生的高亮冻结。
     *
     * 点击跳转后高亮会短暂钉在被点击行；若播放时间已经越过下一行歌词，
     * 继续钉住会把下一行的高亮整个跳过。此时调用本方法清掉所有冻结来源，
     * 让消费端按真实进度重新找行。
     */
    fun releaseLyricFreeze() {
        seekPendingTargetMs = -1L
        seekPendingLineMs = -1L
        seekPendingUntilMs = 0L
        seekUiTargetMs = -1L
        seekUiTargetUntil = 0L
        pausedSeekTargetMs = -1L
        seekCatchupTargetMs = -1L
        seekCatchupUntilMs = 0L
        seekCatchupPinUntilMs = 0L
    }
}

/**
 * 通知监听服务：监听系统媒体通知，从中提取 MediaSession.Token，
 * 创建 MediaController 读取播放进度与状态，驱动歌词滚动。
 *
 * 为提高可靠性，除了监听通知变化外，还会定时扫描当前所有通知，
 * 确保在服务连接前已存在的媒体会话也能被捕获。
 */
class MusicNotificationListenerService : NotificationListenerService() {

    private var activeController: MediaController? = null

    /** 已连接的会话控制器（LinkedHashMap 保证遍历顺序稳定，不随 HashMap 哈希分布变化）。 */
    private val controllers = LinkedHashMap<MediaSession.Token, MediaController>()
    private val callbacks = HashMap<MediaSession.Token, MediaController.Callback>()
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 系统媒体会话管理器：直接查询所有活跃媒体会话，不依赖通知携带 token。 */
    private var sessionManager: MediaSessionManager? = null

    /** 自动匹配用的设置（SharedPreferences 有内存缓存，创建开销可忽略）。 */
    private val autoMatchPrefs by lazy { Prefs(this) }

    /** 歌词自动匹配防抖延迟：切歌时多播放器来源短暂抖动，防抖避免来回闪歌词。 */
    private val autoMatchDebounceMs = 400L

    /** 待执行的自动匹配 runnable：防抖期间如果有新匹配请求，取消旧 runnable 重新排期。 */
    private var pendingAutoMatchRunnable: Runnable? = null

    /**
     * 当前防抖排期对应的歌曲 key。仅当目标歌曲变化时才重新排期：
     * [updateFromController] 会被 PlaybackState 回调高频触发，若每帧都取消并
     * 重排 400ms 任务，高频上报的播放器会让防抖永远不到期，新歌歌词迟迟不出现。
     */
    private var pendingMatchKey: String? = null

    /** 上一次已作废旧歌词的歌曲标题：作者字段晚到时避免重复清空、重复递增版本号。 */
    private var lastInvalidatedTrackTitle: String? = null

    /**
     * chooseActive 跟踪每个 controller 的位置是否长期卡在某个占位值。
     * key = System.identityHashCode(controller)，value = StuckInfo。
     * 用于区分"切歌时 position=0 的短暂过渡"和"MediaSession 真正卡住不再推进"。
     */
    private data class StuckInfo(val pos: Long, val sinceMs: Long)
    private val positionStuckMap = HashMap<Int, StuckInfo>()
    private val STALLED_TRACK_MIN_MS = 2000L

    /** 会话列表连续为空的起始时刻与宽限期：防止状态在通知刷新瞬间来回闪。 */
    private var noSessionSinceMs = 0L
    private var sessionPresentSinceMs = 0L
    private val NO_SESSION_GRACE_MS = 2500L
    private val SESSION_ON_GRACE_MS = 600L

    /** 定时扫描：每 1 秒重新扫描通知并挑选活跃会话，兜底覆盖不主动更新的播放器。 */
    private val scanRunnable = object : Runnable {
        override fun run() {
            // 诊断：扫描是主线程 Handler 循环，空档即代表主线程/服务停摆或系统深睡。
            // sleepDelta（elapsed 差 − uptime 差）用于区分两者。
            val elapsed = SystemClock.elapsedRealtime()
            val uptime = SystemClock.uptimeMillis()
            val gap = if (lastScanElapsedMs > 0L) elapsed - lastScanElapsedMs else -1L
            val upGap = if (lastScanUptimeMs > 0L) uptime - lastScanUptimeMs else -1L
            lastScanElapsedMs = elapsed
            lastScanUptimeMs = uptime
            if (gap >= SCAN_GAP_DIAG_MS) {
                LogCapture.i(
                    "NLS",
                    "scan gap=${gap}ms uptimeGap=${upGap}ms sleepDelta=${gap - upGap}ms " +
                        "controllers=${controllers.size} " +
                        "active=${System.identityHashCode(activeController)} " +
                        "screen=${Diag.lastScreenEvent} " +
                        "sinceScreen=${Diag.sinceScreenEvent(elapsed)}ms " +
                        "sinceScreenOn=${Diag.sinceScreenOn(elapsed)}ms " +
                        "rebindSeq=${Diag.rebindSeq} sinceRebind=${Diag.sinceRebind(elapsed)}ms"
                )
            }
            scanActiveNotifications()
            uiHandler.postDelayed(this, 1000)
        }
    }

    /**
     * 每个会话独立的回调：携带所属 token，会话销毁时能精确定位并移除
     * （共享回调无法区分是哪个会话销毁，残留的失效会话被选中后 seek 会静默无效）。
     */
    private inner class ControllerCallback(
        private val token: MediaSession.Token,
        private val controller: MediaController,
    ) : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // 任一会话状态变化都可能影响"当前进度源"的选择（如别的应用开始播放）
            chooseActive()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (controller === activeController) updateFromController(controller)
            else chooseActive()
        }

        override fun onSessionDestroyed() {
            removeController(token)
            chooseActive()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "Notification listener connected")
        // 诊断：与 MainActivity.onResume 的 requestRebind 配对，确认亮屏回前台
        // 是否真的触发了断连重连（重连会清空全部计时状态）。
        val connectedAt = SystemClock.elapsedRealtime()
        Diag.lastConnectElapsedMs = connectedAt
        LogCapture.i(
            "NLS",
            "listener connected at=$connectedAt " +
                "sinceDisconnect=${Diag.sinceDisconnect(connectedAt)}ms " +
                "sinceRebind=${Diag.sinceRebind(connectedAt)}ms " +
                "rebindSeq=${Diag.rebindSeq} rebindFrom=${Diag.lastRebindFrom} " +
                "screen=${Diag.lastScreenEvent} sinceScreen=${Diag.sinceScreenEvent(connectedAt)}ms " +
                "sinceScreenOn=${Diag.sinceScreenOn(connectedAt)}ms controllers=${controllers.size}"
        )
        // 监听绑定已真正恢复：清除"更新后待重绑"标记，停止后续进程的重试
        if (Prefs(this).needsNlsRebind) Prefs(this).needsNlsRebind = false
        // 注册系统媒体会话监听：部分播放器（如 OriginOS 上的网易云/QQ音乐）
        // 的通知不携带 EXTRA_MEDIA_SESSION，必须通过 MediaSessionManager 获取
        runCatching {
            sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener, component(this), uiHandler
            )
        }.onFailure { Log.w(TAG, "register sessions listener failed: ${it.message}") }
        scanActiveNotifications()
        uiHandler.postDelayed(scanRunnable, 1000)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val disconnectedAt = SystemClock.elapsedRealtime()
        Diag.lastDisconnectElapsedMs = disconnectedAt
        LogCapture.i(
            "NLS",
            "listener disconnected at=$disconnectedAt " +
                "sinceConnect=${Diag.sinceConnect(disconnectedAt)}ms " +
                "sinceRebind=${Diag.sinceRebind(disconnectedAt)}ms " +
                "rebindSeq=${Diag.rebindSeq} rebindFrom=${Diag.lastRebindFrom} " +
                "screen=${Diag.lastScreenEvent} sinceScreen=${Diag.sinceScreenEvent(disconnectedAt)}ms " +
                "controllers=${controllers.size} clearingState=true"
        )
        uiHandler.removeCallbacks(scanRunnable)
        // 断连后未到期的匹配任务属于旧会话，重连后会按新会话重新排期
        cancelPendingAutoMatch()
        lastInvalidatedTrackTitle = null
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        sessionManager = null
        controllers.keys.toList().forEach { removeController(it) }
        activeController = null
        publishedSnapshots = emptyList()
        // 监听断连：系统里已没有任何可靠来源，歌词区回到「没有检测到播放」
        MusicProgress.hasPlaybackSession = false
        noSessionSinceMs = 0L
        // 断开连接后旧会话的 seek 现场已失效，清除暂停锁与补发登记，避免重连后进度被钉死
        MusicProgress.pausedSeekTargetMs = -1L
        MusicProgress.clearSeekPending()
        localSeekPendingUntil = 0L
        // 断连：点击跳转的落地观测会话一并作废（重连后按换源重新对表）
        disarmSeekLanding("listener disconnected")
        disarmTrackLanding("listener disconnected")
        pausedSeekResumeDeadline = 0L
        resyncPending = false
        lastResyncTargetMs = -1L
        lastResyncAtMs = 0L
        lastResyncReason = "-"
        lastResyncDeltaMs = 0L
        lastSecondSyncBackMs = 0L
        lastSecondSyncAtMs = 0L
        pendingSeekRetryTargetMs = -1L
        pendingSeekRetryUntil = 0L
        pendingSeekRetryIssued = false
        // 断开连接后本首歌的切歌等待 / 纯应用内计时状态一并作废：重连后的
        // 首个会话按换源重新读播放器位置，不沿用旧歌的起点与等待窗口
        trackChangedPending = false
        trackStartPendingReal = false
        trackSelfTimed = false
        trackStartReadPosMs = -1L
        lastAdoptBaseMs = 0L
        trackStartSawZeroReport = false
        // 断连后不能让 UI 继续沿用上一首封面
        AlbumArtCache.clear()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        val token = extractSessionToken(sbn.notification) ?: return
        ensureController(token)
        chooseActive()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return
        val token = extractSessionToken(sbn.notification) ?: return
        // 通知被移除时移除对应会话；若会话实际仍活跃，下一次周期扫描会重新发现并恢复
        removeController(token)
        chooseActive()
    }

    /** 会话变化监听：任何应用开始/停止播放媒体时系统回调。 */
    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            runCatching {
                list?.forEach { ensureController(it.sessionToken) }
                chooseActive()
            }
        }

    /** 扫描活跃媒体会话：系统 API 为主路径，通知提取为兜底；同时清理已失效的会话。 */
    private fun scanActiveNotifications() {
        val tokens = HashSet<MediaSession.Token>()
        // 主路径：直接查询系统活跃媒体会话（不依赖通知样式）
        runCatching {
            sessionManager?.getActiveSessions(component(this))?.forEach {
                tokens += it.sessionToken
            }
        }
        // 兜底：个别系统 getActiveSessions 有遗漏，保留通知扫描
        runCatching {
            activeNotifications?.forEach { sbn ->
                extractSessionToken(sbn.notification)?.let { tokens += it }
            }
        }
        tokens.forEach { ensureController(it) }
        // 清理已不在活跃列表中的残留会话：播放器重建会话后旧 token 不一定回调销毁，
        // 残留会话一旦被误选为进度源，进度读取正常但 seek 静默失效（点击无法跳转）
        pruneControllers(tokens)
        chooseActive()
        // 「监听状态」页读的是这里发布的不可变快照，不直接碰 controllers
        publishSnapshots()
    }

    /**
     * 发布媒体会话快照。字段与调试悬浮窗一致（#编号 / active / pos / baseAge …），
     * 另带观测时间，供「监听状态」页逐个展示。
     */
    private fun publishSnapshots() {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val source = activeController
        val list = ArrayList<MediaSessionSnapshot>(controllers.size)
        controllers.values.forEachIndexed { i, c ->
            val st = c.playbackState
            val md = c.metadata
            val stateName = when (st?.state) {
                PlaybackState.STATE_PLAYING -> "PLAYING"
                PlaybackState.STATE_PAUSED -> "PAUSED"
                PlaybackState.STATE_STOPPED -> "STOPPED"
                PlaybackState.STATE_BUFFERING -> "BUFFERING"
                null -> "null"
                else -> "${st.state}"
            }
            list += MediaSessionSnapshot(
                index = i,
                sid = System.identityHashCode(c),
                pkg = c.packageName.orEmpty(),
                appLabel = runCatching {
                    val ai = packageManager.getApplicationInfo(c.packageName.orEmpty(), 0)
                    packageManager.getApplicationLabel(ai).toString()
                }.getOrDefault(c.packageName.orEmpty()),
                active = c === source,
                stateName = stateName,
                isPlaying = st?.state == PlaybackState.STATE_PLAYING,
                posMs = st?.position ?: -1L,
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                baseAgeMs = if (st != null && st.lastPositionUpdateTime > 0L)
                    now - st.lastPositionUpdateTime else -1L,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                observedAtMs = wall,
                cover = albumArtOf(md),
            )
        }
        publishedSnapshots = list
    }

    /**
     * 取会话封面。优先用元数据里的内存位图；不少播放器（尤其 Android 13+ 的
     * 在线音乐应用）只下发 ALBUM_ART_URI / ART_URI / DISPLAY_ICON_URI，
     * 此时按 URI 在后台解码并缓存，避免主线程阻塞。
     *
     * 返回 null 表示「本首歌没有封面或尚未解码完成」，调用方必须把 null 当作
     * 清空封面处理，不能继续保留上一张位图 —— 这正是「切歌后仍显示上一首封面」的根因。
     */
    private fun albumArtOf(md: MediaMetadata?): Bitmap? {
        md ?: return null
        val inMemory = runCatching {
            md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull()?.takeIf { !it.isRecycled }
        if (inMemory != null) return inMemory
        val uri = albumArtUriOf(md) ?: return null
        return AlbumArtCache.get(uri, contentResolver)
    }

    /** 元数据里的封面 URI（按参考实现同样的优先级取第一个非空值）。 */
    private fun albumArtUriOf(md: MediaMetadata?): String? {
        md ?: return null
        return runCatching {
            sequenceOf(
                MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
            ).mapNotNull { md.getString(it)?.takeIf(String::isNotBlank) }.firstOrNull()
        }.getOrNull()
    }

    /**
     * 当前活动会话的「曲目身份」。只包含与 Bitmap 对象无关的元数据字段：
     * 标题、歌手、专辑、封面 URI。
     *
     * 这里**不能**把 [System.identityHashCode] (bitmap) 拼进 key。MediaSession 在
     * 切歌或播放状态更新时，经常用一个**全新的 Bitmap 对象**重发像素完全相同
     * 的封面（参考 lyrics-android 的 HomeAlbumBackground 注释及其单元测试
     * repeatedArtworkKeepsThePreparedBitmapEvenWhenMetadataUsesANewInstance）。
     * 把对象地址当作封面身份会让主界面每 50ms 拉取时 [albumArtVersion] 每帧递增，
     * 后台模糊结果被永久判为过期，表现为「切歌后模糊背景停在上一首」。
     * 封面像素本身的变化改由 [AlbumArtCache.publish] 用 [Bitmap.sameAs] 判定。
     */
    private fun albumArtKeyOf(md: MediaMetadata?): String {
        if (md == null) return "<no-session>"
        return runCatching {
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
            "$title\u0001$artist\u0001$album\u0001${albumArtUriOf(md).orEmpty()}"
        }.getOrDefault("<unreadable>")
    }

    /**
     * 应用内部计时快照：对应调试悬浮窗的「应用内状态」段。
     * 「歌词应用内部计时」即 [MusicProgress] 由本机墙钟推进的估算进度，
     * 而不是播放器上报的快照位置。
     */
    private fun buildInternalClockSnapshot(): InternalClockSnapshot {
        val now = SystemClock.elapsedRealtime()
        val pos = MusicProgress.currentPositionMs()
        val dur = MusicProgress.durationMs
        val rows = listOf(
            "应用内部计时" to "${formatMs(pos)} (${pos}ms)",
            "显示进度" to "${formatMs(MusicProgress.displayPositionMs())} (${MusicProgress.displayPositionMs()}ms)",
            "播放状态" to if (MusicProgress.isPlaying) "播放中" else "已暂停 / 未播放",
            "总时长" to "${formatMs(dur)} (${dur}ms)",
            "倍速" to "${MusicProgress.speed}",
            "基准可信" to "${MusicProgress.baseTrusted}",
            "内部计时基准" to "${MusicProgress.positionMs}ms @ ${MusicProgress.stateTimeMs}",
            "基准年龄" to if (MusicProgress.stateTimeMs > 0L) "${now - MusicProgress.stateTimeMs}ms" else "-",
            "切歌起点" to "${trackStartReadPosMs}ms",
            "整首歌纯应用内计时" to "$trackSelfTimed",
            "当前播放应用" to (MusicProgress.packageName ?: "-"),
            "歌曲" to (MusicProgress.title ?: "-"),
            "艺术家" to (MusicProgress.artist ?: "-"),
            "已加载歌词行数" to "${Session.lyricLines?.size ?: 0}",
        )
        return InternalClockSnapshot(
            observedAtMs = System.currentTimeMillis(),
            rows = rows,
            positionMs = pos,
            durationMs = dur,
            isPlaying = MusicProgress.isPlaying,
            title = MusicProgress.title.orEmpty(),
            artist = MusicProgress.artist.orEmpty(),
            lyricPositionMs = MusicProgress.displayPositionMs(),
        )
    }

    private fun formatMs(value: Long): String {
        val total = (value.coerceAtLeast(0L) / 1000L).toInt()
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
    }

    private fun extractSessionToken(notification: Notification?): MediaSession.Token? {
        notification ?: return null
        val extra = notification.extras?.get(Notification.EXTRA_MEDIA_SESSION)
        return extra as? MediaSession.Token
    }

    private fun ensureController(token: MediaSession.Token) {
        if (controllers.containsKey(token)) return
        val controller = MediaController(this, token)
        val cb = ControllerCallback(token, controller)
        runCatching { controller.registerCallback(cb, uiHandler) }
        controllers[token] = controller
        callbacks[token] = cb
    }

    private fun removeController(token: MediaSession.Token) {
        val controller = controllers.remove(token) ?: return
        callbacks.remove(token)?.let { cb ->
            runCatching { controller.unregisterCallback(cb) }
        }
        if (activeController === controller) activeController = null
    }

    /** 移除不在 [activeTokens] 中的残留会话控制器。 */
    private fun pruneControllers(activeTokens: Set<MediaSession.Token>) {
        controllers.keys.toList().forEach { if (it !in activeTokens) removeController(it) }
    }

    /**
     * 从所有已连接的会话中挑出当前进度源：
     * 优先"正在播放"的会话；同为播放/非播放时取状态最近变化的——
     * 旧实现取遍历到的"第一个"，顺序随机，可能选中播放器重建后残留的
     * 旧会话，导致进度正常但 seek 落到失效会话上、点击跳转概率性无反应。
     *
     * 增加滞后切换：当前源仍在会话列表中且未被明显超越时保持不换，
     * 避免同一播放器残留的多个会话/系统抖动导致进度源来回切换，
     * 进而让估算进度在两个会话的快照间跳动、高亮乱跳到其他行。
     */
    private fun chooseActive() {
        val now = SystemClock.elapsedRealtime()
        val current = activeController
        val currentTitle = current?.let { controllerTitle(it) }

        // 1. 更新每个 controller 的位置跟踪记录，同时清理已消失的 controller。
        //    位置没变化 → 保留首次冻结时间；位置变了 → 重置记录。
        val liveIds = HashSet<Int>()
        for (c in controllers.values) {
            val id = System.identityHashCode(c)
            liveIds.add(id)
            val pos = c.playbackState?.position ?: -1L
            val prev = positionStuckMap[id]
            if (prev == null || prev.pos != pos) {
                positionStuckMap[id] = StuckInfo(pos, now)
            }
        }
        positionStuckMap.keys.retainAll { it in liveIds }

        // 切歌后位置一致性判定：真实播放会话在切歌瞬间应"从头开始"，其位置
        // 与切歌经过的时间基本一致；残留会话则沿用上一首的位置（超前可达十几
        // 秒到几分钟）。仅在存在"位置与经过时间一致"的同包同标题会话时才把
        // 偏离过大的会话判为残留——避免把"播放器从中间续播"误判（此时所有
        // 会话都偏离，不满足前置条件，不标记）。
        val sinceTitleChange = if (trackTitleChangedAt > 0L) now - trackTitleChangedAt else Long.MAX_VALUE
        // 切歌后若已发起过应用内 seek，真实会话的位置已不再是"从头播放"的自然
        // 推进值，此时无法再用位置推断真假，直接跳过判定（避免把真实会话误判为残留）
        if (sinceTitleChange in 0..TRACK_RESET_WINDOW_MS && localSeekIssuedAt <= trackTitleChangedAt) {
            val peers = ArrayList<Pair<Int, Boolean>>()
            for (c in controllers.values) {
                val st = c.playbackState ?: continue
                // 参与判定的会话必须带有效时长：切歌后位置贴 0、时长为 0 的冻结
                // 占位会话（如 Telegram 旧 MediaSessionHelper 实例）在冻结检测
                // 生效前（不足 2 秒）位置 ≈ 切歌经过时间，会被误当作"从头播放"
                // 的判定基准，进而把真正推进的新会话误判为残留并整曲排除。
                if (controllerDuration(c) <= 0L) continue
                val pos = st.position
                if (pos < 0L) continue
                val t = controllerTitle(c)
                if (t.isNullOrEmpty() || t != lastTrackTitle) continue
                val spd = st.playbackSpeed.takeIf { it > 0f } ?: 1f
                val expected = (sinceTitleChange * spd.coerceIn(1f, 3f)).toLong()
                // 位置与经过时间一致，且不是在"贴在开头的冻结占位值"（后者
                // 位置虽小但不是真实播放会话，不能作为判定基准）
                val stuck = positionStuckMap[System.identityHashCode(c)]
                val frozenMs = if (stuck != null && stuck.pos == pos) now - stuck.sinceMs else 0L
                val advancing = st.state != PlaybackState.STATE_PLAYING ||
                    frozenMs < STALLED_TRACK_MIN_MS
                peers.add(
                    System.identityHashCode(c) to
                        (pos - expected <= TRACK_RESET_TOLERANCE_MS && advancing)
                )
            }
            if (peers.any { it.second }) {
                val stale = peers.filterNot { it.second }.map { it.first }
                if (stale.any { it !in staleSessionIds }) {
                    LogCapture.d(
                        "Source",
                        "stale mark sid=$stale since=$sinceTitleChange title=${lastTrackTitle?.take(10)}"
                    )
                }
                staleSessionIds.addAll(stale)
            } else if (peers.isNotEmpty() && staleSessionIds.isNotEmpty()) {
                // 撤销残留标记：同标题会话都在，但已不存在"位置与经过时间一致"
                // 的可信基准（典型：此前的基准其实是位置贴 0 的冻结占位会话，
                // 冻结检测生效或时长判定后不再合格）——据此做出的残留判定失去
                // 依据，全部撤销，交由打分机制重新选择，避免健康会话被误标后
                // 整曲无法接管进度源（表现为歌词整首对不上、循环后卡住）。
                LogCapture.d(
                    "Source",
                    "stale revoke title=${lastTrackTitle?.take(10)} ids=$staleSessionIds"
                )
                staleSessionIds.clear()
            }
        }
        staleSessionIds.retainAll { it in liveIds }

        // 同包同标题多会话的进度分歧检测（覆盖服务中途接入、没有切歌锚点的场景）：
        // 同一播放器残留的两个会话可能长期相差几十秒却都在按 1x 推进，只靠
        // "标题+播放状态"无法区分。连续多次快照仍然分歧时，判定位置更靠前者
        // 为沿用旧进度的残留会话并排除（实测该会话的位置整首都在真实进度之前）。
        // 应用内 seek 之后位置会被人为跳变，静默期内不做判定，避免把真实会话误判。
        val divPkg = current?.packageName
        if (divPkg != null && sinceTitleChange > TRACK_RESET_WINDOW_MS &&
            (localSeekIssuedAt <= 0L || now - localSeekIssuedAt > DIVERGENCE_SEEK_QUIET_MS)
        ) {
            var lowId = -1
            var lowPos = Long.MAX_VALUE
            var highId = -1
            var highPos = Long.MIN_VALUE
            var qualified = 0
            for (c in controllers.values) {
                if (c.packageName != divPkg) continue
                val st = c.playbackState ?: continue
                if (st.state != PlaybackState.STATE_PLAYING) continue
                if (controllerDuration(c) <= 0L) continue
                val t = controllerTitle(c)
                if (t.isNullOrEmpty() || t != lastTrackTitle) continue
                val pos = st.position
                if (pos < 0L) continue
                val id = System.identityHashCode(c)
                val stuck = positionStuckMap[id]
                // 只比较"位置在推进"的会话：冻结占位值不代表真实进度
                if (stuck != null && stuck.pos == pos && now - stuck.sinceMs >= STALLED_TRACK_MIN_MS) continue
                qualified++
                if (pos < lowPos) {
                    lowPos = pos
                    lowId = id
                }
                if (pos > highPos) {
                    highPos = pos
                    highId = id
                }
            }
            if (qualified >= 2 && highPos - lowPos > DIVERGENCE_TOLERANCE_MS) {
                divergenceStrikes++
                if (divergenceStrikes >= DIVERGENCE_STRIKES) {
                    if (highId !in staleSessionIds) {
                        LogCapture.d(
                            "Source",
                            "divergence stale sid=$highId high=$highPos low=$lowId low=$lowPos"
                        )
                    }
                    staleSessionIds.add(highId)
                    divergenceStrikes = 0
                }
            } else {
                divergenceStrikes = 0
            }
        }

        // 会话可信度打分：同一播放器常残留多个会话，其中部分会话（如 Telegram
        // 的 MediaSessionHelper 旧实例）播放中位置恒为 0、也无时长，但状态时间戳
        // 频繁刷新。若仅按时间戳新鲜度选择，会一直选中这类死会话——估算进度
        // 只能靠本机时钟自走，与真实进度越差越远（歌词停在第一行）。
        // baseScoreOf 不含"残留会话"惩罚：供豁免判定使用（被标记的会话是否是
        // 当前唯一的健康候选），惩罚的施加条件见 scoreOf。
        fun baseScoreOf(c: MediaController): Int {
            val st = c.playbackState ?: return -4
            var score = 0
            val playing = st.state == PlaybackState.STATE_PLAYING
            if (playing) score += 4
            val dur = controllerDuration(c)
            if (dur > 0L) score += 3
            val pos = st.position
            if (pos > STALLED_POSITION_MAX_MS) score += 2

            // 时间化冻结位置惩罚：只有位置连续没变 >= 2 秒才惩罚。
            // 区分：切歌时 position=0 短暂过渡（通常 200ms 内推进）vs
            // MediaSession 真正卡住不再推进（持续 2+ 秒位置不变）。
            val id = System.identityHashCode(c)
            val stuckInfo = positionStuckMap[id]
            val stuckForMs = if (stuckInfo != null && stuckInfo.pos == pos && pos >= 0) {
                now - stuckInfo.sinceMs
            } else 0L
            val atStart = pos in 0..STALLED_POSITION_MAX_MS
            val atEnd = dur > 0L && pos in (dur - STALLED_POSITION_MAX_MS)..dur
            if (playing && (atStart || atEnd) && stuckForMs >= STALLED_TRACK_MIN_MS) {
                score -= 3
            }
            return score
        }
        // 当前源已死（冻结占位：无时长贴开头/带时长贴结尾不动，基础分 <= 4）时的
        // 残留惩罚豁免：若不存在任何"未标记且健康"的候选（基础分 >= 5：带时长
        // 的播放中/已暂停会话），被误标过的健康会话仍需能接管——否则冻结源会把
        // 持进度源整曲不放，估算只能靠本机时钟自走（表现为整首歌歌词对不上、
        // 循环后卡死，只能手动暂停恢复）。一旦出现未标记的健康候选，惩罚自动
        // 恢复，真正的残留会话仍会被排除。
        val currentSourceDead = current != null && controllers.containsValue(current) &&
            baseScoreOf(current) <= 4
        val liftStalePenalty = currentSourceDead && controllers.values.none {
            it !== current && System.identityHashCode(it) !in staleSessionIds &&
                baseScoreOf(it) >= 5
        }
        fun scoreOf(c: MediaController): Int {
            var score = baseScoreOf(c)
            // 切歌后位置未复位的残留会话：本曲内持续重罚，避免它凭"位置靠前"
            // 的高分在滞后切换阈值之外重新被选中（其位置整首都在真实进度之前）
            if (System.identityHashCode(c) in staleSessionIds && !liftStalePenalty) {
                score -= STALE_SESSION_PENALTY
            }
            return score
        }
        var target: MediaController? = null
        var targetScore = Int.MIN_VALUE
        var targetTime = -1L
        val curPkg = current?.packageName
        for (c in controllers.values) {
            // 标题一致性过滤仅同包生效：同包内的残留会话（如 Telegram 的旧
            // MediaSessionHelper 实例）常停留在上一首，需要过滤；同包无标题的
            // 过渡会话（播放器重建会话时短暂出现）也不得竞争——当前源暂停掉分
            // 时（丢失"播放中"的 4 分），这类会话会凭"播放中+带时长"抢走
            // 进度源，把进度重置到无关位置。但跨包的不同播放器各自播放不同
            // 曲目是正常场景，必须保留竞争资格，否则用户从 Telegram 切到
            // SaltPlayer/网易云会被此过滤挡掉。
            val pkg = c.packageName
            val title = controllerTitle(c)
            if (current != null && curPkg != null && pkg == curPkg &&
                !currentTitle.isNullOrEmpty() &&
                (title.isNullOrEmpty() || title != currentTitle)
            ) continue
            val score = scoreOf(c)
            val time = c.playbackState?.lastPositionUpdateTime ?: 0L
            if (target == null || score > targetScore ||
                (score == targetScore && time > targetTime)
            ) {
                target = c
                targetScore = score
                targetTime = time
            }
        }
        if (current != null && target !== current && controllers.containsValue(current)) {
            val curScore = scoreOf(current)
            val curState = current.playbackState
            val curPlaying = curState?.state == PlaybackState.STATE_PLAYING
            val curTime = curState?.lastPositionUpdateTime ?: 0L
            // 挑战者需明显更可信才换源（避免来回抖动）；但当前源明显不可信
            // （无时长且位置贴在开头）时，只要有播放中且带时长的会话就立即换。
            val currentDead = curScore <= 4
            val challengerBetter = targetScore >= curScore + 2 ||
                (currentDead && targetScore >= 4) ||
                (targetScore >= 4 && !curPlaying && targetScore > curScore) ||
                (targetScore == curScore && targetTime > curTime + SWITCH_HYSTERESIS_MS)
            if (!challengerBetter) target = current
        }
        if (now - lastSourceDumpAt >= 3000L &&
            (autoMatchPrefs.debugMode || LogCapture.isCapturing())
        ) {
            lastSourceDumpAt = now
            val dump = controllers.values.joinToString(" | ") {
                val st = it.playbackState
                "sid=${System.identityHashCode(it)} t=${controllerTitle(it)?.take(6)} " +
                    "ps=${st?.state} pos=${st?.position} dur=${controllerDuration(it)} sc=${scoreOf(it)}"
            }
            val summary = "cur=${System.identityHashCode(current)} " +
                "pick=${System.identityHashCode(target)} :: $dump"
            if (autoMatchPrefs.debugMode) Log.d(TAG, "srcs $summary")
            // 抓取开启时落盘：否则打分明细只在 Logcat，导出日志看不到会话分叉
            LogCapture.diag("Source", "pick $summary")
        }
        if (target !== activeController) {
            // 换源后相邻快照来自不同会话，不能与上一会话的位置比较（否则会把
            // 残留会话的静止占位值当成 seek 跳变直接采用）。重置快照跟踪，
            // 让新会话的首条快照按自身是否推进来判定可信度。
            lastReportedPos = -1L
            lastSnapshotWallMs = 0L
            lastPosChangeAt = 0L
            stalledSinceAt = 0L
            // 换源：旧会话的待重锚与诊断记录一并作废，避免用上一来源的
            // 时间戳去判断新来源的快照是否「新上报」
            cancelPendingResync("source switch")
            lastResyncTargetMs = -1L
            lastResyncAtMs = 0L
            lastResyncReason = "-"
            lastResyncDeltaMs = 0L
            // 新源接管时清除其"残留"标记（能被选为源说明标记已失效）：否则下一
            // 轮打分它又被压回 -20 分，会被冻结旧源反超、随后旧源再被判死、
            // 标记再被豁免……源在两个会话间每秒来回切换，歌词行疯狂跳动。
            target?.let { staleSessionIds.remove(System.identityHashCode(it)) }
            activeController = target
            MusicProgress.packageName = target?.packageName
            sourceSwitched = true
            // 诊断：区分「真的换了播放器」与「同包多会话漂移」，并给出两端
            // 此刻的位置 / 时长 / 时间戳分叉，供定位换源引发的整秒重锚。
            val oldSid = System.identityHashCode(current)
            val newSid = System.identityHashCode(target)
            val oldState = current?.playbackState
            val newState = target?.playbackState
            LogCapture.d(
                "Source",
                "switch sid=$oldSid->$newSid " +
                    "pkg=${current?.packageName}->${target?.packageName} " +
                    "samePkg=${current?.packageName != null && current?.packageName == target?.packageName} " +
                    "title=${target?.let { controllerTitle(it) }} " +
                    "sc=${current?.let { scoreOf(it) }}->$targetScore " +
                    "stamp=${oldState?.lastPositionUpdateTime}->$targetTime " +
                    "pos=${oldState?.position}->${newState?.position} " +
                    "dur=${current?.let { controllerDuration(it) }}->${target?.let { controllerDuration(it) }} " +
                    "ps=${oldState?.state}->${newState?.state} " +
                    "live=${controllers.size} " +
                    "gap=${if (lastSourceSwitchAtMs > 0L) now - lastSourceSwitchAtMs else -1}ms " +
                    "sinceScreen=${Diag.sinceScreenEvent(now)}ms rebindSeq=${Diag.rebindSeq} " +
                    "sessions=[${sessionDiag(now)}]"
            )
            lastSourceSwitchAtMs = now
        }
        updateFromController(target)
        // 会话列表实时变化：没有任何媒体会话时，主页 / 桌面歌词显示
        // 「没有检测到播放」而不是把原因归到歌词缺失上。
        // 加一段宽限期：媒体会话在切歌 / 通知刷新 / 监听重连瞬间常短暂为空，
        // 立刻翻转会让状态文字来回闪；只有持续空缺才判定为真的没有播放。
        val hasSessions = controllers.isNotEmpty()
        if (hasSessions) {
            noSessionSinceMs = 0L
            if (MusicProgress.hasPlaybackSession) {
                sessionPresentSinceMs = 0L
            } else {
                // 重新出现会话也要稳定一小段时间才算恢复，避免陈旧会话闪现导致
                // 「没有检测到播放」与空白状态来回闪。
                if (sessionPresentSinceMs <= 0L) sessionPresentSinceMs = now
                if (now - sessionPresentSinceMs >= SESSION_ON_GRACE_MS) {
                    MusicProgress.hasPlaybackSession = true
                    sessionPresentSinceMs = 0L
                }
            }
        } else {
            sessionPresentSinceMs = 0L
            if (noSessionSinceMs <= 0L) noSessionSinceMs = now
            if (MusicProgress.hasPlaybackSession &&
                now - noSessionSinceMs >= NO_SESSION_GRACE_MS
            ) {
                MusicProgress.hasPlaybackSession = false
            }
        }
    }

    private fun controllerTitle(c: MediaController): String? =
        runCatching { c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) }.getOrNull()

    private fun controllerDuration(c: MediaController): Long =
        runCatching {
            (c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
                .takeIf { it > 0L } ?: cachedDurationNonCreating(controllerTitle(c))
        }.getOrDefault(0L)

    /**
     * 诊断：所有会话此刻的位置快照。用于对比 seekTo 发出 / 落地时各会话的
     * 位置与时长分叉（21:30 日志里两个 Telegram 会话长期相差约 1 秒）。
     * 只被日志调用，不参与任何选源或计时判断。
     */
    private fun sessionDiag(now: Long): String = controllers.values.joinToString(" | ") { c ->
        val st = runCatching { c.playbackState }.getOrNull()
        val stamp = st?.lastPositionUpdateTime ?: 0L
        "sid=${System.identityHashCode(c)} act=${c === activeController} " +
            "ps=${st?.state} pos=${st?.position} dur=${controllerDuration(c)} " +
            "stamp=$stamp age=${if (stamp > 0L) now - stamp else -1} " +
            "t=${controllerTitle(c)?.take(8) ?: "-"}"
    }

    private fun cachedDurationNonCreating(title: String?): Long {
        val key = title?.trim()?.takeUnless { it.isEmpty() } ?: return 0L
        return durationCache.entries.firstOrNull { it.key.startsWith("$key|") }?.value ?: 0L
    }


    private var lastTrackTitle: String? = null

    /**
     * 最近一次检测到切歌的本机时刻：用于识别"切歌后位置没有复位"的残留会话。
     *
     * 同一播放器常残留多个会话（如 Telegram 的两个 MediaSession），切歌时其中
     * 一个会话的 position 仍沿用上一首的位置并继续按 1x 增长，而真实播放会话
     * 会从头开始。若残留会话被选为进度源，整首歌的歌词都会超前几十秒，只有
     * 暂停（播放器重新对齐两个会话的位置）才会恢复。
     */
    private var trackTitleChangedAt = 0L

    /** 本曲内被判定为"切歌后位置未复位"的残留会话（id 见 [System.identityHashCode]）。 */
    private val staleSessionIds = HashSet<Int>()

    /** 同包同标题会话进度分歧的连续命中次数（用于判定残留会话）。 */
    private var divergenceStrikes = 0

    /** 上次回调中播放器上报的进度快照原始值：用于区分"正常播放"与"seek/快照更新"。 */
    private var lastReportedPos: Long = -1L

    /** 上次帧尾记录的播放器状态时间戳原始值：与切歌瞬间的快照对比，识别新歌上报。 */
    private var lastSeenStateStampMs: Long = 0L

    /** 上次帧尾记录的播放器播放态原始值（PlaybackState.STATE_*）：识别新歌上报。 */
    private var lastSeenPlayState: Int = 0

    /**
     * 切歌瞬间（改写前）的播放器状态三要素：位置 / 状态时间戳 / 播放态。
     * 切歌后的快照与之完全一致 = 播放器自切歌起没有任何新上报（长时间播放
     * 为优化性能不上报/漏报），读到的是上一首残留状态，绝不能当新歌起点采用。
     */
    private var trackStartPrevPos: Long = -1L
    private var trackStartPrevStampMs: Long = 0L
    private var trackStartPrevPlayState: Int = 0

    /** 当前 [trackChangedPending] 是否来自真实切歌（服务接入后首个标题不算）。 */
    private var trackStartPendingReal = false

    /**
     * 切歌后读取到的首条新歌进度起点（ms）；-1 表示整首歌都没有可读进度。
     *
     * 仅用于诊断展示，**不参与计时**。计时基准永远是 [MusicProgress.positionMs]
     * / [MusicProgress.stateTimeMs]，由 [adoptBase] 统一写入。外部对表
     * （secondAdopt / 换源 / 手动对齐）之后这里仍是切歌时读到的旧值，日志里
     * 出现「startPos=153000 但 est=0」属于正常，不代表基准没被重置。
     */
    private var trackStartReadPosMs: Long = -1L

    /** 最近一次 [adoptBase] 写入的计时基准位置（仅诊断展示，与上面的 startPos 区分）。 */
    private var lastAdoptBaseMs: Long = 0L

    /** 等待首条有效进度期间是否观察到「有上报但位置恒为 0」（进度锁定为 0）。 */
    private var trackStartSawZeroReport = false

    /** 上次收到快照对应的本机 elapsedRealtime，用于 seek 检测的墙钟推进量计算。 */
    private var lastSnapshotWallMs: Long = 0L

    /** 当前进度源是否刚发生切换：切换后第一个快照直接采用，不做前向约束。 */
    private var sourceSwitched = false

    // 停播对齐（暂停 / 恢复时反向 seekTo 播放器）已整块移除：改为恢复后
    // 一次性重锚，状态与诊断字段见 resyncPending / lastResync*（与
    // resyncFromSnapshot 相邻）。保留这段说明，避免以后又把对齐逻辑捡回来。

    /**
     * 刚发生切歌：等待「新歌首条有效进度上报」，只读取这一次作为本首歌起点。
     * 切歌时进度已归零并按当前速度外推，若新歌快照只能走「仅前向吸附」的
     * 常规路径，落后于外推值的快照会被全部忽略，表现为高亮卡在开头或脱离
     * 播放器进度自行滚动（尤其是会记住单曲进度、从中间继续播放的播放器）。
     * 无论什么应用都要读这一次：读到起点后立刻转入 [trackSelfTimed] 整首歌
     * 纯应用内计时；「毫秒信息不可信」的应用读取时只取整秒值。
     *
     * 「属于新歌」的判定：状态三要素（位置/时间戳/播放态）相对切歌瞬间
     * （[trackStartPrevPos] 等）发生过变化，且位置 > 0（0 是占位值 / 播放器
     * 把进度锁定在 0，没有可读起点）。长时间播放不上报或一直上报 0 的播放器
     * 会在 [TRACK_START_FRESH_WAIT_MS] 宽限窗内由应用内计时从切歌归零处推进
     * （期间不采用任何快照），超窗仍无有效进度则直接转入 [trackSelfTimed]。
     */
    private var trackChangedPending = false

    /**
     * 本首歌是否已进入「读一次起点 + 整首歌纯应用内计时」状态。
     *
     * 对所有应用生效，不再由用户开关控制：切歌后 [trackChangedPending] 等到
     * 首条有效新歌进度并读取一次后立刻置 true，之后播放器周期快照一律不写
     * 基准，歌词时间只按应用内墙钟推进；暂停只固化当前估算值、恢复后续走，
     * 只有在播放器内调整进度、换源等外部事件才重新读播放器位置对表
     * （毫秒不可信的应用走整秒对表）。
     *
     * 切歌后一直等不到有效进度（不上报或位置锁定为 0）时也会置 true：此时
     * 没有可读取的起点，直接用切歌归零那一刻作为起点，完全相信应用内计时。
     */
    private var trackSelfTimed = false

    /**
     * 点击后的「应用自计时保护窗口」截止时刻：窗口内周期快照一律不改写估算
     * 基准，歌词时间完全由本机墙钟推进。避免播放器上报的偏移位置（跳转提示
     * 里抓到的「实际进度」常偏几百毫秒）把点击后的行时间 / 逐字动画带偏。
     * 窗口长度固定为整 1 秒（见 [SEEK_SELF_TIMED_WINDOW_MS]），与不支持毫秒
     * 的播放器的整秒节拍对齐，结束时不会出现非整秒零头造成的进度跳变。
     *
     * 「毫秒信息不可信」的应用不靠这个窗口恢复周期校正：窗口结束后仍只信
     * 应用内计时，只有外部事件才读播放器的整秒值对表（见 [updateFromController]）。
     */
    private var localSeekPendingUntil: Long = 0L

    /** 应用内 seek 的目标位置（毫秒）。 */
    private var localSeekTargetMs: Long = -1L

    /** 本次 seek 由点击歌词行发起时，该行的原始时间戳；-1 表示其它 seek 来源。 */
    private var localSeekLineMs: Long = -1L

    /** 应用内 seek 发出的本机时刻：仅用于作废过期回调 / 折叠快速连点。 */
    private var localSeekIssuedAt: Long = 0L

    /**
     * 正常播放中快照连续落后于估算的次数：正常滞后属个别现象，连续多次落后
     * 说明估算已"棘轮式"超前漂移（正常路径只允许前向吸附，正向抖动被不断
     * 累积），需校正对齐回播放器真实进度，否则高亮会跑到播放进度之前。
     */
    private var driftStrikes = 0

    /**
     * 时长缺失兜底值：网络波动等原因导致播放器元数据缺时长（MusicProgress
     * 时长为 0）时，从同应用其他会话补获的时长。补获后需在此暂存——当前源
     * 的元数据仍为 0，若不暂存会在下一轮 updateFromController 被覆盖回 0。
     * 拿到播放器自身上报的有效时长后清零。
     */
    private var missingDurationFallbackMs = 0L

    /** 上次时长兜底重取时刻：限流，避免歌词频繁切行时反复扫描。 */
    private var lastMissingDurationRefreshAt = 0L

    /**
     * 播放中占位/冻结快照的起始时刻（0 表示当前不是占位快照）。
     * 用时间而非回调次数累计：播放器的状态回调常成簇到达（1 秒一次扫描 +
     * 紧随其后的多次回调），按次数累计会被同簇内的后续回调清零，导致
     * 冻结检测永远不触发。
     */
    private var stalledSinceAt = 0L

    /** 播放器上报的位置上一次发生变化的时刻：用于判定位置冻结时长。 */
    private var lastPosChangeAt = 0L

    /** 上次输出会话候选诊断日志的时刻：限流。 */
    private var lastSourceDumpAt = 0L

    /** 诊断：上次换源时刻，用于打印两次换源之间的间隔（抖动检测）。 */
    private var lastSourceSwitchAtMs = 0L

    /**
     * 同曲时长缓存（"标题|艺术家" → 时长毫秒）：部分播放器播放中元数据缺
     * 时长、仅暂停时才上报真实值（如 Telegram 内置播放器）。暂停一次后把
     * 真实时长记入缓存，恢复播放元数据再缺时长时直接命中，无需再次暂停。
     * 播放器自身上报有效时长时刷新对应条目。
     */
    private val durationCache = HashMap<String, Long>()

    /** 时长缓存持久化：仅内存缓存在进程重启后失效，会再次要求"暂停一次"。 */
    private val durationPrefs by lazy { getSharedPreferences("song_durations", MODE_PRIVATE) }

    private fun rememberDuration(trackKey: String, durationMs: Long) {
        if (durationMs <= 0L) return
        durationCache[trackKey] = durationMs
        runCatching { durationPrefs.edit().putLong(trackKey, durationMs).apply() }
    }

    private fun cachedDuration(trackKey: String): Long {
        durationCache[trackKey]?.let { return it }
        val stored = runCatching { durationPrefs.getLong(trackKey, 0L) }.getOrDefault(0L)
        if (stored > 0L) durationCache[trackKey] = stored
        return stored
    }

    // 歌曲文件名解析缓存：调试快照每 500ms 刷新，不能每次查 MediaStore
    private var fileNameCacheKey: String? = null
    private var fileNameCache: Pair<String?, String>? = null

    /**
     * 尽量解析当前歌曲的文件名称（仅标题可用时兜底）：
     * 1. METADATA_KEY_MEDIA_URI（file:// 取路径名，content:// 查 DISPLAY_NAME）；
     * 2. METADATA_KEY_MEDIA_ID 按媒体库 ID 查 MediaStore。
     * @return 文件名（未获取到时为 null）to 获取状态描述
     */
    private fun resolveMediaFileName(metadata: MediaMetadata?): Pair<String?, String> {
        metadata ?: return null to "无媒体元数据"
        val uriStr = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val cacheKey = "$uriStr|$mediaId"
        if (cacheKey == fileNameCacheKey) {
            return fileNameCache ?: (null to "无")
        }
        val result = runCatching { doResolveMediaFileName(uriStr, mediaId) }
            .getOrElse { null to "查询异常：${it.javaClass.simpleName}" }
        fileNameCacheKey = cacheKey
        fileNameCache = result
        return result
    }

    private fun doResolveMediaFileName(uriStr: String?, mediaId: String?): Pair<String?, String> {
        if (!uriStr.isNullOrEmpty()) {
            val name = queryDisplayName(Uri.parse(uriStr))
            if (!name.isNullOrEmpty()) return name to "已获取（来源：媒体URI）"
            return null to "媒体URI无法解析文件名"
        }
        if (!mediaId.isNullOrEmpty()) {
            val id = mediaId.toLongOrNull()
                ?: return null to "媒体ID非数字，无法查询媒体库"
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
            val name = queryDisplayName(uri)
            if (!name.isNullOrEmpty()) return name to "已获取（来源：媒体库查询）"
            return null to "媒体库查询无结果（可能缺少媒体读取权限）"
        }
        return null to "播放器未提供文件信息"
    }

    /** 查询 content/file URI 的显示文件名；无权限或失败时返回 null。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return@runCatching uri.lastPathSegment?.substringAfterLast('/')
        }
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return@runCatching c.getString(idx)
                }
                null
            }
    }.getOrNull()

    /** 调试模式：汇总当前媒体会话与歌词匹配状态的全部信息。 */
    private fun buildDebugSnapshot(): String = buildString {
        val c = activeController
        appendLine("通知监听：已连接")
        appendLine("活跃会话数：${controllers.size}")
        // 逐个列出所有活跃会话的详细信息，便于排查同播放器多残留会话
        val nowMs = SystemClock.elapsedRealtime()
        controllers.values.forEachIndexed { i, c2 ->
            val st2 = c2.playbackState
            val md2 = c2.metadata
            val stStateName = when (st2?.state) {
                PlaybackState.STATE_PLAYING -> "PLAYING"
                PlaybackState.STATE_PAUSED -> "PAUSED"
                PlaybackState.STATE_STOPPED -> "STOPPED"
                PlaybackState.STATE_BUFFERING -> "BUFFERING"
                null -> "null"
                else -> "${st2.state}"
            }
            val age = if (st2 != null && st2.lastPositionUpdateTime > 0L)
                nowMs - st2.lastPositionUpdateTime else -1L
            val stuck = positionStuckMap[System.identityHashCode(c2)]
            val frozenMs = if (stuck != null && stuck.pos == st2?.position && st2?.position != null && st2.position >= 0)
                nowMs - stuck.sinceMs else -1L
            appendLine(
                "  [#$i] sid=${System.identityHashCode(c2)} pkg=${c2.packageName} " +
                    "active=${activeController === c2} state=$stStateName " +
                    "pos=${st2?.position} dur=${md2?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L} " +
                    "baseAge=${age}ms frozenMs=$frozenMs " +
                    "title=${md2?.getString(MediaMetadata.METADATA_KEY_TITLE)?.take(30) ?: "-"} " +
                    "artist=${md2?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(20) ?: "-"}"
            )
        }
        appendLine("当前会话包名：${c?.packageName ?: "-"}")
        val st = c?.playbackState
        appendLine("播放状态：${when (st?.state) {
            PlaybackState.STATE_PLAYING -> "播放中"
            PlaybackState.STATE_PAUSED -> "已暂停"
            PlaybackState.STATE_STOPPED -> "已停止"
            PlaybackState.STATE_BUFFERING -> "缓冲中"
            null -> "-"
            else -> "其他(${st.state})"
        }}")
        appendLine("快照进度：${st?.position ?: "-"} ms")
        appendLine("快照基准时间：${st?.lastPositionUpdateTime ?: "-"}")
        // 基准年龄 = 本机时钟 - 播放器基准：远大于真实播放时长即说明基准原点不可信
        appendLine("基准年龄：${
            if (st != null && st.lastPositionUpdateTime > 0)
                SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
            else "-"
        } ms")
        appendLine("基准可信：${MusicProgress.baseTrusted}")
        appendLine("倍速：${st?.playbackSpeed ?: "-"}")
        val md = c?.metadata
        appendLine("标题：${md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "-"}")
        appendLine("艺术家：${md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "-"}")
        appendLine("专辑：${md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "-"}")
        appendLine("时长：${md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L} ms")
        // 只有标题等少量信息时也尽量解析歌曲文件名，并展示获取状态
        val (fileName, fileStatus) = resolveMediaFileName(md)
        appendLine("歌曲文件名：${fileName ?: "-"}")
        appendLine("文件名获取状态：$fileStatus")
        appendLine("—— 应用内状态 ——")
        appendLine("MusicProgress 标题：${MusicProgress.title ?: "-"}")
        appendLine("MusicProgress 艺术家：${MusicProgress.artist ?: "-"}")
        appendLine("MusicProgress 播放中：${MusicProgress.isPlaying}")
        appendLine("MusicProgress 估算进度：${MusicProgress.currentPositionMs()} ms")
        appendLine("MusicProgress 时长：${MusicProgress.durationMs} ms")
        appendLine("MusicProgress 兜底时长：${missingDurationFallbackMs} ms")
        appendLine("MusicProgress 倍速：${MusicProgress.speed}")
        appendLine("匹配键：${Session.matchedKey ?: "-"}")
        appendLine("匹配歌词文件：${Session.matchedFile?.name ?: "-"}")
        appendLine("匹配文件路径：${Session.matchedFile?.absolutePath ?: "-"}")
        appendLine("待补发 seek：${pendingSeekRetryTargetMs} 已补发：${pendingSeekRetryIssued}")
        appendLine(
            "seek 毫秒补偿：冻结=${MusicProgress.seekCatchupTargetMs} " +
                "截止=${MusicProgress.seekCatchupUntilMs} " +
                "生效=${MusicProgress.isSeekCatchupActive()}"
        )
        appendLine(
            "seek 钉行：line=${MusicProgress.seekPendingLineMs} " +
                "有效=${MusicProgress.isSeekPending()}"
        )
        appendLine(
            "点击后自计时：目标=$localSeekTargetMs " +
                "估算基准=${MusicProgress.positionMs} " +
                "保护截止=$localSeekPendingUntil " +
                "毫秒不可信=${PlaybackOffset.isMsUntrusted(autoMatchPrefs, MusicProgress.packageName)} " +
                "生效=${SystemClock.elapsedRealtime() < localSeekPendingUntil}"
        )
        appendLine(
            "切歌计时：已读起点=$trackStartReadPosMs ms（-1=无可读取进度，仅诊断） " +
                "内部计时基准=$lastAdoptBaseMs ms（真实计时原点） " +
                "等待首条上报=$trackChangedPending 整首歌纯应用内计时=$trackSelfTimed"
        )
        appendLine(
            "消费端延迟：整体偏移=${PlaybackOffset.delayMs(autoMatchPrefs)}ms " +
                "歌词文件 offset=${LyricsParser.lastOffsetMs}ms（不参与计时）"
        )
        appendLine(
            "最近读秒对表：回退=${lastSecondSyncBackMs}ms " +
                "距今=${if (lastSecondSyncAtMs > 0L) nowMs - lastSecondSyncAtMs else -1L}ms"
        )
        appendLine("暂停 seek 锁目标：${MusicProgress.pausedSeekTargetMs}")
        appendLine(
            "恢复重锚：待归位=$resyncPending " +
                "上次目标=$lastResyncTargetMs 原因=$lastResyncReason " +
                "拉移=${lastResyncDeltaMs}ms " +
                "距今=${if (lastResyncAtMs > 0L) nowMs - lastResyncAtMs else -1L}ms " +
                "当前内部计时=${MusicProgress.currentPositionMs()}ms"
        )
        appendLine("恢复后锁释放截止：${pausedSeekResumeDeadline}")
        appendLine("已加载歌词行数：${Session.lyricLines?.size ?: 0}")
        appendLine("当前界面歌词：${Session.name ?: "-"}")
    }

    /** 立即把当前状态快照写入日志（用于开启日志抓取时捕获"开启前"的现场）。 */
    fun dumpCurrentState(reason: String) {
        LogCapture.i("State", "—— 状态快照（$reason）——")
        buildDebugSnapshot().lineSequence().filter { it.isNotBlank() }.forEach {
            LogCapture.i("State", it)
        }
    }

    /**
     * 只取「秒」：丢弃毫秒部分，向下取整到整秒刻度。
     *
     * 两个用途：①「当前应用的毫秒信息不可信」的应用（不支持毫秒 seek 的
     * 播放器）点击跳转后，应用内计时同样从整秒起步，逐字动画不领先真实音频；
     * ② 外部事件读播放器进度时只采纳整秒值，绝不把播放器的毫秒数写进基准。
     */
    private fun wholeSecondBase(targetMs: Long): Long = targetMs - targetMs % 1000L

    /**
     * 快速连点同一行时的折叠处理：不改 [localSeekIssuedAt]、不重发 seekTo、
     * 不动应用自身计时基准，只把保护窗口与钉行窗口顺延。连点期间高亮始终
     * 钉在被点击行，逐字动画按第一次点击建立的基准继续走。
     */
    private fun collapseRapidRepeatSeek(
        positionMs: Long,
        lineTimeMs: Long,
        now: Long,
    ) {
        if (lineTimeMs >= 0L) {
            localSeekLineMs = lineTimeMs
            MusicProgress.seekPendingLineMs = lineTimeMs
        }
        localSeekPendingUntil = now + SEEK_SELF_TIMED_WINDOW_MS
        MusicProgress.seekUiTargetMs = positionMs
        MusicProgress.seekUiTargetUntil = now + SEEK_PIN_WINDOW_MS
        if (MusicProgress.isSeekCatchupActive()) {
            // 整秒自计时期间不能重登记（会清掉补偿），只顺延钉行
            MusicProgress.extendSeekPending(now + SEEK_PIN_WINDOW_MS)
        } else {
            MusicProgress.markSeekPending(
                positionMs,
                lineTimeMs.takeIf { it >= 0L } ?: MusicProgress.seekPendingLineMs,
                now + SEEK_PIN_WINDOW_MS
            )
        }
        LogCapture.d(
            "Seek",
            "seek rapid repeat collapsed target=$positionMs " +
                "sinceIssued=${now - localSeekIssuedAt}ms " +
                "selfTimed=${SystemClock.elapsedRealtime() < localSeekPendingUntil} " +
                "catchup=${MusicProgress.isSeekCatchupActive()}"
        )
    }

    /**
     * 点击歌词行的跳转：只向播放器发出 seekTo（含必要的指令送达校验与补发），
     * 歌词时间完全由应用自己按墙钟计算，绝不读播放器快照判定落点或校正——
     * 这些值经常偏移几百毫秒，是逐字动画忽前忽后的根源。
     *
     * 计时基准：普通应用直接用请求目标；「毫秒信息不可信」的应用（不支持
     * 毫秒 seek）从目标向下取整的整秒起步，显示进度冻结在目标，等自身计时
     * 走完毫秒差再放行逐字动画。
     *
     * 「毫秒信息不可信」的应用在这里同样执行双边对齐：应用内计时与发给
     * 播放器的 seekTo 都锚到同一个整秒值（timingBase），与一键对齐 / 恢复 /
     * 切歌 / 外部事件同口径；指令仍走 trySeek 的多会话送达校验链，不重复发
     * 一遍。**点击路径不登记双边对齐的落地守卫**（8 秒守卫窗口已移除），落地帧
     * 仍由本地 seek 静默期与 nearLocalSeekTarget 处理，用户随后在播放器里的
     * 手动跳转不会被吞掉。显示进度的毫秒零头由 startSeekCatchup 冻结补偿，
     * 点击瞬间高亮照旧钉在被点行。普通应用路径完全不变。
     */
    private fun seekWithFallback(positionMs: Long, lineTimeMs: Long = -1L) {
        val now = SystemClock.elapsedRealtime()
        // 新的跳转由本次请求接管：恢复后待办的一次性重锚作废，避免把用户
        // 刚指定的新位置按播放器旧快照重新拉回去。
        cancelPendingResync("local seek")
        // 快速连点同一行：窗口内一律折叠，不重发 seekTo、不改计时基准。
        // 超过窗口允许重发一次，避免上一次跳转被播放器静默丢弃。
        val sameTarget = localSeekIssuedAt > 0L && localSeekTargetMs == positionMs
        // 诊断字段：首次点击时 localSeekIssuedAt 为 0，直接相减会打出十余天的
        // 虚假 sincePrev（21:30 日志里 1025042040ms）。sameTarget 已保证 > 0，
        // 因此这里的 -1 不影响连点折叠判断，只修诊断输出。
        val rapidElapsed = if (localSeekIssuedAt > 0L) now - localSeekIssuedAt else -1L
        if (sameTarget && rapidElapsed < SEEK_RAPID_RESEND_MS &&
            pendingSeekRetryTargetMs < 0L
        ) {
            collapseRapidRepeatSeek(positionMs, lineTimeMs, now)
            return
        }
        if (controllers.isEmpty()) scanActiveNotifications()
        chooseActive()
        val primary = activeController ?: return

        localSeekTargetMs = positionMs
        localSeekLineMs = lineTimeMs
        localSeekIssuedAt = now
        localSeekPendingUntil = now + SEEK_SELF_TIMED_WINDOW_MS
        // 新 seek 发起：清除上一轮「未生效补发」登记，避免误补旧目标
        pendingSeekRetryTargetMs = -1L
        pendingSeekRetryUntil = 0L
        pendingSeekRetryIssued = false

        val msUntrusted = PlaybackOffset.isMsUntrusted(autoMatchPrefs, primary.packageName)
        val speed = MusicProgress.speed.takeIf { it > 0f } ?: 1f
        val timingBase = if (msUntrusted) wholeSecondBase(positionMs) else positionMs
        val remainMs = (positionMs - timingBase).coerceAtLeast(0L)
        // 点击瞬间把高亮对齐到目标行（短窗口，随后由自身计时自然推进）
        MusicProgress.seekUiTargetMs = positionMs
        MusicProgress.seekUiTargetUntil = now + SEEK_PIN_WINDOW_MS
        MusicProgress.markSeekPending(positionMs, lineTimeMs, now + SEEK_PIN_WINDOW_MS)
        // 唯一的计时动作：以目标（或整秒）为原点，之后完全由本机墙钟推进
        adoptBase(timingBase, now, speed, now)
        // 「毫秒信息不可信」：播放器指令一律发同一个整秒值（见下方 trySeek /
        // 复检 / 暂停补发），双方从已知同一原点起播；但**不登记落地守卫**，
        // 点击后不留 8 秒静默窗口。开头的 cancelPendingResync 只清掉旧登记。
        // 开关关闭时不改指令目标，普通应用路径完全不变。
        // 整秒自计时：显示进度冻结在目标，等自己走完毫秒差再放行逐字动画
        if (remainMs > 0L) {
            MusicProgress.startSeekCatchup(positionMs, (remainMs / speed).toLong(), now)
            MusicProgress.seekUiTargetUntil = maxOf(
                MusicProgress.seekUiTargetUntil,
                MusicProgress.seekCatchupPinUntilMs
            )
        }
        // 落地观测（仅「毫秒信息不可信」+ 播放中）：大跳转的落地延迟会让应用
        // 内计时恒定领先播放器（点击后无法自愈），等落地帧到达时按一键对齐
        // 逻辑重锚一次。普通应用靠周期快照校正自愈，不武装；暂停态点击走
        // 暂停锁 / 恢复补发路径（恢复帧自带双边对齐），也不武装。新点击一律
        // 覆盖上一轮会话。
        if (msUntrusted && MusicProgress.isPlaying) {
            seekLandingBaseMs = timingBase
            seekLandingIssuedAtMs = now
            seekLandingPrevRawMs = runCatching {
                primary.playbackState?.position ?: -1L
            }.getOrDefault(-1L)
            seekLandingNearAtLaunch = seekLandingPrevRawMs >= 0L &&
                kotlin.math.abs(seekLandingPrevRawMs - timingBase) <= USER_SEEK_JUMP_MS
            LogCapture.d(
                "Seek",
                "seek landing watch armed target=$timingBase prevRaw=$seekLandingPrevRawMs " +
                    "nearAtLaunch=$seekLandingNearAtLaunch sessions=[${sessionDiag(now)}]"
            )
        } else {
            disarmSeekLanding("seek not armed")
        }
        // 点击是更新的事件：切歌对齐的二次微调观测一并取消（点击自带落地观测）
        disarmTrackLanding("local seek")
        // 暂停态 seek 锁：暂停时播放器快照不可靠，只保留「未确认」标记与补发
        // 登记，不再拿快照位置覆盖应用自己的计时基准
        if (!MusicProgress.isPlaying) {
            // 暂停态补发同样用整秒目标，避免恢复后又把带毫秒的位置写回播放器
            MusicProgress.pausedSeekTargetMs = timingBase
            pendingSeekRetryTargetMs = timingBase
            pendingSeekRetryUntil = now + PENDING_SEEK_RETRY_WINDOW_MS
            pendingSeekRetryIssued = false
            pausedSeekResumeDeadline = 0L
        } else {
            MusicProgress.pausedSeekTargetMs = -1L
            pausedSeekResumeDeadline = 0L
        }
        val candidates = listOf(primary) + controllers.values
            .filter { it !== primary && it.packageName == primary.packageName }
            .sortedByDescending { it.playbackState?.lastPositionUpdateTime ?: 0L }
        // 发给播放器的目标 = 内部计时基准：开关开启时是整秒，普通应用是原始目标
        // 诊断：记录指令发出前后的多会话位置，定位「两个会话相差约 1 秒」的起点
        val sessionsBeforeSeek = sessionDiag(now)
        trySeek(candidates, 0, timingBase, now)
        scheduleSeekRecheck(timingBase, now)
        LogCapture.i(
            "Seek",
            "seek issued target=$positionMs seekTarget=$timingBase timingBase=$timingBase " +
                "wholeSecond=$msUntrusted alignBoth=$msUntrusted remain=${remainMs}ms " +
                "isPlaying=${MusicProgress.isPlaying} pkg=${primary.packageName} " +
                "selfTimed=${SEEK_SELF_TIMED_WINDOW_MS}ms sincePrev=${rapidElapsed}ms " +
                "delay=${PlaybackOffset.delayMs(autoMatchPrefs)}ms " +
                "sessionsBefore=[$sessionsBeforeSeek] sessionsAfter=[${sessionDiag(now)}]"
        )
    }

    /**
     * 用播放器快照把应用内计时重锚一次（恢复后自愈 / 桌面歌词「一键对齐」共用）。
     *
     * 暂停 / 播放状态只能靠回调或每秒扫描「检测」到，真实切换时刻未知：暂停帧
     * 多算、恢复帧少算会在内部计时里留下固定相位差，而整首歌纯应用内计时又
     * 关闭了周期校正，偏差不会自愈。这里不猜检测延迟，改用**快照自带的时刻**
     * 外推到当前：`position + (now - lastPositionUpdateTime) × speed`。
     * 快照的 position 数值可能滞后 / 整秒量化，但「快照时刻 → 现在」这段墙钟
     * 关系是可靠的，正是对它外推才消掉检测延迟；晚多久看到快照都不影响结果。
     *
     * 暂停期间快照不推进，绝不能按快照龄外推（会得到「暂停点 + 整个暂停时长」），
     * 此时直接用裸位置。播放中没有可用的新时间戳就放弃本次重锚，宁可保持现状
     * 也不把歌词拉回暂停时的旧位置。
     *
     * [minStampMs] 用于自动重锚：只有时间戳比上一帧更新，才证明这是恢复后
     * 新上报的状态，而不是暂停时残留的旧值。[maxSnapshotAgeMs] 在手动对齐时
     * 放宽，覆盖 Telegram 这类每 5 秒才上报一次的播放器——外推公式正好补上
     * 这段上报间隔。不复用 snapshotStalled 判据：慢上报会被它误判成冻结快照，
     * 这里只认时间戳是否前进。
     *
     * 「当前应用的毫秒信息不可信」开启时额外做两件事：① 写进基准的值一律
     * 落在整秒刻度，不带播放器假毫秒、也不带墙钟外推的毫秒零头；② 快照
     * 可用时把播放器一起 seek 到同一整秒刻度（见 [applyBothSidesAlign]），
     * 双方从已知的同一原点起播。恢复重锚、暂停态手动对齐同样适用；允许
     * 进度回退到整秒，但两边不再错开。其余应用与其它路径完全不变。
     */
    private fun resyncFromSnapshot(
        reason: String,
        minStampMs: Long = 0L,
        maxSnapshotAgeMs: Long = SNAPSHOT_FRESH_MS,
    ): Boolean {
        val state = activeController?.playbackState ?: return false
        if (state.position < 0L) return false
        val now = SystemClock.elapsedRealtime()
        val stamp = state.lastPositionUpdateTime
        val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
        val playing = state.state == PlaybackState.STATE_PLAYING
        val stampUsable = stamp > 0L && stamp <= now &&
            now - stamp <= maxSnapshotAgeMs && stamp > minStampMs
        if (playing && !stampUsable) return false
        // 暂停态：音频不推进，position 就是当前位置，不做任何外推
        val target = if (playing) {
            state.position + ((now - stamp) * speed).toLong()
        } else {
            state.position
        }
        val duration = MusicProgress.durationMs
        if (target < 0L || (duration > 0L && target >= duration + RUNAWAY_GRACE_MS)) {
            LogCapture.w(
                "Progress",
                "resync $reason skipped invalid target=$target dur=$duration " +
                    "raw=${state.position} stamp=$stamp age=${now - stamp}ms"
            )
            return false
        }
        // 「毫秒信息不可信」：写进基准的值一律落在整秒刻度。先按快照时间戳
        // 外推到当前（慢上报播放器不能倒退回几秒前），再向下取整秒——这就是
        // 「播放器 19 秒多、应用 20 秒多 → 两边一起回到 19 秒」的那个刻度。
        val msUntrusted = PlaybackOffset.isMsUntrusted(autoMatchPrefs)
        val finalTarget = if (msUntrusted) wholeSecondBase(target) else target
        val before = MusicProgress.currentPositionMs()
        adoptBase(finalTarget, now, speed, now)
        if (reason == RESYNC_REASON_MANUAL) {
            // 手动对齐以播放器为准：结束本地 seek 的乐观自计时，避免钉行 /
            // catchup 冻结把刚重锚的进度盖住
            localSeekPendingUntil = 0L
            MusicProgress.clearSeekPending()
        } else if (reason == RESYNC_REASON_SEEK_LANDING) {
            // 点击落地重锚：结束点击的乐观自计时（落地已确认）。
            //
            // 关键：adoptBase 会把 est 回拉「指令发出 → 音频起播」的落地延迟
            // （Telegram 大跳转实测 1.5s）。落地帧往往在点击 1.5s 后才到达，
            // 此时点击时的短钉行窗口（400ms / catchup tail）早已过期。若不
            // 重新钉住，回拉后的 est 会落到点击行之前的歌词，表现为「点了 A
            // 却高亮 B」——点击行不跳走的视觉就是这样失效的。
            // 这里用被点击行的时间戳重新登记一段钉行窗口：只钉高亮，不冻结
            // 进度（进度由 adoptBase 后的自身计时照常推进），窗口内高亮保持
            // 在点击行，到期自然释放交给真实进度。
            localSeekPendingUntil = 0L
            if (localSeekLineMs >= 0L) {
                MusicProgress.markSeekPending(
                    finalTarget, localSeekLineMs, now + SEEK_UI_PENDING_MS
                )
            }
        }
        // 开关开启且快照可用时一律双边对齐（手动 / 恢复重锚 / 暂停态手动）：
        // 整秒是这类播放器唯一能精确执行的跳转目标，双方从同一已知原点起播。
        // 快照太旧时退化为单向整秒重锚，绝不拿陈旧位置去 seek 播放器；
        // 其它应用与其它路径完全不变。
        val alignBoth = msUntrusted && stampUsable
        val seekSent = if (alignBoth) {
            applyBothSidesAlign(finalTarget, now, speed, reason)
        } else 0
        lastResyncTargetMs = finalTarget
        lastResyncAtMs = now
        lastResyncReason = reason
        lastResyncDeltaMs = before - finalTarget
        // 一次恢复只归位一次；手动对齐同样消费掉待办，避免随后又跳一次
        resyncPending = false
        LogCapture.i(
            "Progress",
            "resync $reason target=$finalTarget raw=${state.position} " +
                "rawMod=${state.position % 1000} stamp=$stamp age=${now - stamp}ms before=$before " +
                "delta=${lastResyncDeltaMs}ms ps=${state.state} speed=$speed " +
                "secOnly=$msUntrusted alignBoth=$alignBoth seekSent=$seekSent " +
                "sessions=[${sessionDiag(now)}]"
        )
        return true
    }

    /**
     * 把当前应用的所有有效媒体会话一起 seek 到整秒刻度 [targetMs]。
     *
     * 与 [togglePlayback] 同口径发给 primary + 同包名其它有效会话：部分播放器
     * 常驻多个 MediaSession，被选为进度源的会话未必是响应控制命令的那一个。
     * 这里只发指令、不做落地确认与补发——应用内计时已独立锚到同一刻度，
     * 落地帧由 [bothSidesAlignTargetMs] 登记吞掉，不再按旧快照写基准。
     *
     * @return 成功发出指令的会话数；0 表示会话不可用，退化为单向重锚。
     */
    private fun seekBothSidesTo(targetMs: Long, issuedAt: Long): Int {
        val primary = activeController ?: return 0
        val targets = listOf(primary) + controllers.values.filter { c ->
            c !== primary && c.packageName == primary.packageName &&
                runCatching { c.playbackState }.getOrNull() != null
        }
        val sessionsBefore = sessionDiag(issuedAt)
        var sent = 0
        targets.forEach { c ->
            val prePos = c.playbackState?.position
            val ok = runCatching { c.transportControls.seekTo(targetMs) }
                .onFailure { Log.w(TAG, "both-sides align seek failed: ${it.message}") }
                .isSuccess
            if (ok) sent++
            LogCapture.d(
                "Seek",
                "both-sides align seekTo sid=${System.identityHashCode(c)} " +
                    "act=${c === activeController} target=$targetMs prePos=$prePos " +
                    "ok=$ok issuedAt=$issuedAt"
            )
        }
        LogCapture.i(
            "Seek",
            "both-sides align seek target=$targetMs sessions=${targets.size} sent=$sent " +
                "pkg=${primary.packageName} issuedAt=$issuedAt " +
                "sessionsBefore=[$sessionsBefore] sessionsAfter=[${sessionDiag(issuedAt)}]"
        )
        return sent
    }

    /**
     * 双边对齐的统一入口：登记落地守卫，再把播放器 seek 到整秒刻度。
     *
     * 四个场景共用——桌面歌词「一键对齐」、恢复播放、切歌读取起点、外部事件；
     * 点击歌词行只把两边锚到同一整秒（见 [seekWithFallback]），**不登记守卫**，
     * 点击后的 8 秒守卫窗口已按用户要求移除。调用方各自先
     * `adoptBase(同一整秒值, now, ...)`，保证两边锚到同一个原点。
     * 必须**先登记再发指令**：个别会话会同步回调 onPlaybackStateChanged，
     * 落地帧到达时守卫必须已生效，否则会被当成外部跳转写回旧基准。
     */
    private fun applyBothSidesAlign(
        targetMs: Long,
        now: Long,
        speed: Float,
        reason: String,
    ): Int {
        bothSidesAlignTargetMs = targetMs
        bothSidesAlignIssuedAtMs = now
        val sent = seekBothSidesTo(targetMs, now)
        LogCapture.i(
            "Progress",
            "both-sides align $reason target=$targetMs sent=$sent speed=$speed"
        )
        return sent
    }

    /**
     * 切换播放/暂停：播放中暂停，暂停/停止时恢复播放（编辑歌词打轴用）。
     *
     * 方向判断取当前进度源会话的真实 PlaybackState，不用 [MusicProgress.isPlaying]：
     * 后者由状态回调末尾写入，多个会话交替时可能滞后于刚选中的进度源，
     * 导致点「暂停」却发了 play（已经在播放，表现为按钮点了没反应）。
     *
     * 指令还要发给同包名的其它有效会话：部分播放器（如 Telegram Web）常驻
     * 多个 MediaSession，被选为进度源的会话未必是响应控制命令的那一个，
     * 只发 activeController 会静默无效；与跳转的 [trySeek] 多会话送达同口径。
     *
     * @return 是否至少成功发出了一次指令。
     */
    private fun togglePlayback(source: String): Boolean {
        if (controllers.isEmpty()) scanActiveNotifications()
        chooseActive()
        val primary = activeController ?: return false
        val primaryState = runCatching { primary.playbackState }.getOrNull()
        val playing = if (primaryState != null) {
            primaryState.state == PlaybackState.STATE_PLAYING
        } else {
            // 会话尚未下发 PlaybackState 时退回全局标记，保证仍能发出指令
            MusicProgress.isPlaying
        }
        val targets = listOf(primary) + controllers.values.filter { c ->
            c !== primary && c.packageName == primary.packageName &&
                runCatching { c.playbackState }.getOrNull() != null
        }
        // 记录来源：状态翻转日志据此判断「暂停/播放是 app 发的指令，还是
        // 播放器自身的动作（媒体按钮 / 通知 / 第三方应用）」。必须在实际
        // 发指令之前记，个别会话会同步回调 onPlaybackStateChanged。
        val action = if (playing) "pause" else "play"
        lastPlayToggleAtMs = SystemClock.elapsedRealtime()
        lastPlayToggleSource = source
        lastPlayToggleAction = action
        var sent = false
        targets.forEach { c ->
            val ok = runCatching {
                if (playing) c.transportControls.pause() else c.transportControls.play()
            }.onFailure { Log.w(TAG, "play/pause failed: ${it.message}") }.isSuccess
            if (ok) sent = true
        }
        LogCapture.i(
            "Play",
            "toggle play/pause action=$action source=$source " +
                "targets=${targets.size} pkg=${primary.packageName} " +
                "flag=${MusicProgress.isPlaying} sent=$sent"
        )
        return sent
    }

    // ---------------------------------------------------------------- 全局切歌（方案 A）

    /**
     * 【全局控制通道】上一首 / 下一首：发给进度源 + 同包名其它有效会话。
     *
     * 与 [togglePlayback] / [seekBothSidesTo] 同口径。此前只发 activeController，
     * 而 Telegram Web 一类播放器常驻多个 MediaSession，被选为进度源的会话往往
     * 只上报状态、不接受控制指令（日志中 sid=93522155 连续 5 次 play/pause
     * 均 sent=true 却无状态翻转），于是切歌表现为「点了没反应」。
     *
     * 非幂等风险：skipToNext/Previous 与 seek/pause 不同，多个会话都响应时可能
     * 连跳两首。这里先按 [PlaybackState.getActions] 过滤掉「明确声明不支持」的
     * 会话，并在日志里留下 sessions/sent 供实测核对；实测中哑会话不响应指令，
     * 因此真正生效的仍只有一个会话。
     */
    private fun skipTrackGlobal(next: Boolean): Boolean {
        if (controllers.isEmpty()) scanActiveNotifications()
        chooseActive()
        val primary = activeController ?: return false
        val wanted = if (next) PlaybackState.ACTION_SKIP_TO_NEXT
        else PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val candidates = listOf(primary) + controllers.values.filter { c ->
            c !== primary && c.packageName == primary.packageName &&
                runCatching { c.playbackState }.getOrNull() != null
        }
        val targets = candidates.filter { supportsDeclaredAction(it, wanted) }
            .ifEmpty { listOf(primary) }
        val direction = if (next) "next" else "prev"
        var sent = 0
        targets.forEach { c ->
            val ok = runCatching {
                if (next) c.transportControls.skipToNext()
                else c.transportControls.skipToPrevious()
                true
            }.onFailure { Log.w(TAG, "global skip failed: ${it.message}") }.getOrDefault(false)
            if (ok) sent++
            LogCapture.d(
                "Play",
                "skip (global) direction=$direction sid=${System.identityHashCode(c)} " +
                    "act=${c === activeController} pkg=${c.packageName} ok=$ok"
            )
        }
        LogCapture.i(
            "Play",
            "skip (global) direction=$direction candidates=${candidates.size} " +
                "sessions=${targets.size} sent=$sent pkg=${primary.packageName}"
        )
        return sent > 0
    }

    // ---------------------------------------------------------------- 控制能力判定与结果复核

    /**
     * 会话是否「没有明确声明不支持」该动作。
     *
     * PlaybackState.actions 为 0 表示播放器根本没声明能力（相当常见），此时必须
     * 保守放行，否则会把本来可用的会话全部误判为不可控。只有 actions 非 0 且
     * 不含任一所需位时，才判定为不支持。
     */
    private fun supportsDeclaredAction(c: MediaController, vararg actions: Long): Boolean {
        val declared = runCatching { c.playbackState?.actions ?: 0L }.getOrDefault(0L)
        if (declared == 0L) return true
        return actions.any { (declared and it) != 0L }
    }

    /**
     * 会话的「控制结果指纹」：播放状态 + 曲目标识 + 时长。
     * 播放/暂停会改变状态，切歌会改变曲目或时长；不含 position，避免正常播放
     * 推进被误判成「已响应」。
     */
    private fun sessionControlFingerprint(c: MediaController): String {
        val st = runCatching { c.playbackState }.getOrNull()
        val md = runCatching { c.metadata }.getOrNull()
        return listOf(
            st?.state,
            md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            md?.getLong(MediaMetadata.METADATA_KEY_DURATION),
        ).joinToString("|")
    }

    /** 连点定向按钮时只保留最后一次复核，避免连弹多条提示。 */
    private var isolatedCheckSeq = 0

    /**
     * 定向指令发出后短时复核：目标会话的指纹若完全没变，说明它「声明支持但
     * 静默丢弃」了指令。仅记日志并回调界面提示，**不向其它会话补发**——补发会
     * 破坏「监听状态」页的会话隔离。
     */
    private fun scheduleIsolatedResponseCheck(
        sid: Int,
        label: String,
        before: String,
        onNoResponse: (() -> Unit)?,
    ) {
        val seq = ++isolatedCheckSeq
        uiHandler.postDelayed({
            if (seq != isolatedCheckSeq) return@postDelayed
            val c = controllerBySid(sid)
            val after = c?.let { sessionControlFingerprint(it) }
            val responded = c != null && after != before
            LogCapture.i(
                "Play",
                "control (isolated) response label=$label sid=$sid " +
                    "responded=$responded sessionAlive=${c != null} " +
                    "before=[$before] after=[$after]"
            )
            if (!responded) onNoResponse?.invoke()
        }, ISOLATED_RESPONSE_CHECK_MS)
    }

    // ---------------------------------------------------------------- 按会话定向控制

    /**
     * 按快照里的 sid 找到对应的 MediaController。
     *
     * sid 即 [System.identityHashCode]，与调试信息悬浮窗、「监听状态」卡片显示的 sid 同口径。
     * 会话被销毁或重建后旧 sid 自然查不到，此时调用方应提示刷新，**绝不能退回**
     * activeController —— 否则点非活跃会话卡片上的按钮会控制到另一个会话上。
     */
    private fun controllerBySid(sid: Int): MediaController? =
        controllers.values.firstOrNull { System.identityHashCode(it) == sid }

    /**
     * 只向指定会话发播放/暂停指令（「监听状态」卡片专用，方案 B）。
     *
     * 与全局 [togglePlayback] 的三点区别，都是为了会话隔离：
     * 1. 方向只取该会话自己的 PlaybackState，不读全局 [MusicProgress.isPlaying]；
     * 2. 指令只发该会话，不扩散到同包名的其它会话；
     * 3. 不调用 [chooseActive]、不重建会话列表，即不改变当前进度源。
     *
     * 发送前按 actions 判断能力（明确不支持则直接失败并让界面提示）；发送后
     * 由 [scheduleIsolatedResponseCheck] 复核结果，覆盖「声明支持但静默丢弃」的
     * 哑会话。全程不跨会话补发，隔离语义不变。
     *
     * 状态翻转的「来源归因」标记仅在目标恰好是进度源时写入，避免把非活跃会话的
     * 翻转误判成「应用暂停/播放了当前歌曲」而污染日志与后续判断。
     */
    private fun togglePlaybackFor(
        sid: Int,
        source: String,
        onNoResponse: (() -> Unit)?,
    ): Boolean {
        val controller = controllerBySid(sid) ?: run {
            LogCapture.i("Play", "toggle play/pause (isolated) rejected reason=session-gone sid=$sid")
            return false
        }
        val state = runCatching { controller.playbackState }.getOrNull()
        // 会话尚未下发 PlaybackState 时按「未在播放」处理（发 play），
        // 不使用全局标记，避免把别的会话的状态套到本会话上。
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val action = if (playing) "pause" else "play"
        val wanted = if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        if (!supportsDeclaredAction(controller, wanted, PlaybackState.ACTION_PLAY_PAUSE)) {
            LogCapture.i(
                "Play",
                "toggle play/pause (isolated) rejected reason=unsupported action=$action " +
                    "sid=$sid actions=${state?.actions}"
            )
            return false
        }
        val before = sessionControlFingerprint(controller)
        if (controller === activeController) {
            lastPlayToggleAtMs = SystemClock.elapsedRealtime()
            lastPlayToggleSource = source
            lastPlayToggleAction = action
        }
        val ok = runCatching {
            if (playing) controller.transportControls.pause()
            else controller.transportControls.play()
        }.onFailure { Log.w(TAG, "session play/pause failed: ${it.message}") }.isSuccess
        LogCapture.i(
            "Play",
            "toggle play/pause (isolated) action=$action source=$source sid=$sid " +
                "pkg=${controller.packageName} isActive=${controller === activeController} sent=$ok"
        )
        if (ok) scheduleIsolatedResponseCheck(sid, "toggle:$action", before, onNoResponse)
        return ok
    }

    /** 只向指定会话发上一首 / 下一首；next=true 为下一首。能力与结果复核同上。 */
    private fun skipTrackForSession(
        sid: Int,
        next: Boolean,
        onNoResponse: (() -> Unit)?,
    ): Boolean {
        val controller = controllerBySid(sid) ?: run {
            LogCapture.i("Play", "skip (isolated) rejected reason=session-gone sid=$sid")
            return false
        }
        val wanted = if (next) PlaybackState.ACTION_SKIP_TO_NEXT
        else PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val state = runCatching { controller.playbackState }.getOrNull()
        if (!supportsDeclaredAction(controller, wanted)) {
            LogCapture.i(
                "Play",
                "skip (isolated) rejected reason=unsupported direction=${if (next) "next" else "prev"} " +
                    "sid=$sid actions=${state?.actions}"
            )
            return false
        }
        val before = sessionControlFingerprint(controller)
        val ok = runCatching {
            if (next) controller.transportControls.skipToNext()
            else controller.transportControls.skipToPrevious()
            true
        }.onFailure { Log.w(TAG, "session skip failed: ${it.message}") }.getOrDefault(false)
        LogCapture.i(
            "Play",
            "skip (isolated) direction=${if (next) "next" else "prev"} sid=$sid " +
                "pkg=${controller.packageName} isActive=${controller === activeController} sent=$ok"
        )
        if (ok) {
            scheduleIsolatedResponseCheck(
                sid, "skip:${if (next) "next" else "prev"}", before, onNoResponse
            )
        }
        return ok
    }

    /**
     * 兜底复检：只负责「跳转指令有没有被播放器接收」，不参与歌词计时。
     * 校验循环依赖候选会话回报的位置判断成败，存在漏网情形（播放器静默
     * 丢弃 seek、校验期间快照恰好未更新等），全部落空时点击跳转表现为
     * "还在唱上一句"。稍后核对进度源会话的快照，仍明显落后目标才补发
     * 一次 seekTo；应用自身计时基准不会因此改变。
     */
    private fun scheduleSeekRecheck(positionMs: Long, issuedAt: Long) {
        uiHandler.postDelayed({
            // 仅对最近一次 seek 复检；期间发起了新的 seek 则交给新流程
            if (localSeekIssuedAt != issuedAt) return@postDelayed
            // 正在做整秒自计时 / 刚结束：此时快照必然落后目标，不能据此补发
            if (MusicProgress.isSeekCatchupRecent()) return@postDelayed
            val source = activeController ?: return@postDelayed
            val state = source.playbackState ?: return@postDelayed
            // 快照位置是最后一次状态回调的静态值，需按快照龄外推到当前，
            // 否则 seek 成功但快照滞后时会误判失败；时间戳可疑（异常时钟）
            // 时退回原始快照值
            val stamp = state.lastPositionUpdateTime
            val age = (SystemClock.elapsedRealtime() - stamp).coerceAtLeast(0L)
            val speed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
            // 仅播放中才按快照龄外推：暂停时快照龄随暂停时长无限增长，
            // 外推会把位置推到远超实际值，误判"已到达目标"而跳过补发，
            // 暂停态的 seek 失败因此漏网
            val projected = if (state.state == PlaybackState.STATE_PLAYING &&
                stamp > 0L && age <= 5_000L
            ) {
                state.position + (age * speed).toLong()
            } else {
                state.position
            }
            // 快照基准过旧时无法外推，原始位置又常是 seek 前的旧值，不能拿它
            // 判定「跳转失败」并重发（那会打断已经生效的播放）
            if (stamp <= 0L || age > 5_000L) return@postDelayed
            // 已到达或越过目标附近：seek 生效（落点略早在可消化范围内），无需处理
            if (projected >= positionMs - SEEK_RECHECK_TOLERANCE_MS) return@postDelayed
            LogCapture.d("Seek", "recheck re-seek target=$positionMs projected=$projected")
            runCatching { source.transportControls.seekTo(positionMs) }
                .onFailure { Log.w(TAG, "seek recheck failed: ${it.message}") }
        }, SEEK_RECHECK_DELAY_MS)
    }

    /**
     * 恢复后一次性重锚：暂停 / 播放的真实切换时刻无法获知，检测到时内部计时
     * 已经偏离音频。恢复帧登记一次等待，后续拿到「时间戳前进」的新快照就
     * 重锚一次并立即关闭；超时仍无新上报则放弃（最坏维持现状，不会更差）。
     * 用户跳转 / 切歌 / 换源 / 点击歌词行会直接作废，避免覆盖用户指定的位置。
     */
    private var resyncPending = false
    private var resyncIssuedAtMs = 0L

    /** 最近一次重锚的诊断数据（自动 / 手动），只用于日志展示，不参与判定。 */
    private var lastResyncTargetMs = -1L
    private var lastResyncAtMs = 0L
    private var lastResyncReason = "-"
    private var lastResyncDeltaMs = 0L

    /**
     * 「毫秒信息不可信」应用的双边对齐：把播放器与应用内计时一起锚到同一个
     * 整秒刻度后，登记这次对齐的目标（一键对齐 / 恢复 / 切歌 / 外部事件共用；
     * 点击歌词行不登记，不留 8 秒守卫窗口）。随后 seekTo 的落地帧位置会跳回
     * 该刻度，它不是外部跳转，不能被 secondSyncEvent 按旧快照把刚对齐的基准
     * 写回去（慢上报播放器会因此倒退好几秒）。窗口过后自动失效，见下方守卫。
     */
    private var bothSidesAlignTargetMs = -1L
    private var bothSidesAlignIssuedAtMs = 0L

    /**
     * 点击跳转（「毫秒信息不可信」应用）的落地观测会话。
     *
     * 点击发出的 seekTo 从指令到播放器真正执行隔着落地延迟（大跳转实测
     * Telegram 约 1.5 秒），应用内计时从「发出时刻」起算，恒定领先这段延迟
     * 且无法自愈（自计时模式关闭了周期校正）。会话窗口内等一帧「位置跳进
     * 目标邻域」的快照——那就是播放器真正起播的时刻——按「一键对齐」的
     * 逻辑重锚（外推 → 整秒 → 双边微调 seek）：重锚目标在播放器脚下，微调
     * 跳变量 <1 秒、落地延迟毫秒级，两边从同一整秒重新起播，偏移清零。
     * 点击瞬间的钉行 / catchup 冻结视觉不变；普通应用不武装本会话。
     */
    private var seekLandingBaseMs = -1L

    /** 落地观测会话的发出时刻（= 点击时刻）。0 表示无观测会话。 */
    private var seekLandingIssuedAtMs = 0L

    /** 点击瞬间播放器快照位置：区分「落地跳变」与「点击时就在目标邻域」。 */
    private var seekLandingPrevRawMs = -1L

    /** 点击瞬间播放器已在目标邻域（原地重锚场景：无需等待跳变即可对表）。 */
    private var seekLandingNearAtLaunch = false

    /**
     * 最近一次 app 主动发出的 play/pause 指令时刻（elapsedRealtime）与来源，
     * 仅用于状态翻转日志归因：区分「播放器自身的暂停/恢复」与「本 app 指令
     * 造成的暂停/恢复」。0 / 空串表示尚无指令。
     */
    private var lastPlayToggleAtMs = 0L
    private var lastPlayToggleSource = ""
    private var lastPlayToggleAction = ""

    /** 结束落地观测会话（触发 / 超窗 / 切歌 / 换源 / 断连 / 新点击重武装）。 */
    private fun disarmSeekLanding(reason: String) {
        if (seekLandingIssuedAtMs == 0L) return
        LogCapture.d(
            "Progress",
            "seek landing watch end reason=$reason target=$seekLandingBaseMs"
        )
        seekLandingIssuedAtMs = 0L
        seekLandingBaseMs = -1L
        seekLandingPrevRawMs = -1L
        seekLandingNearAtLaunch = false
    }

    /**
     * 切歌双边对齐的「二次微调」观测会话（仅毫秒不可信应用）。
     *
     * 切歌起点对齐把两边锚到同一整秒，但那次微调 seekTo 自己也有落地延迟
     * （实测 3~90ms），应用内计时恒定领先这段残差且无法自愈。好在它可直接
     * 观测：`est − 外推raw` 在落地前因整秒 floor 恒为负、落地后恒为正（正值
     * 就是残差 Δ）。观测到正 Δ 后按「偏移 ×2」的宽限等到播放器彻底稳定，
     * 再执行一次一键对齐（外推 → 整秒 → 双边微调）把残差也清零。二次微调
     * 同样会把播放器拉回整秒刻度（音频回拉 ≤1 秒零头，发生在切歌后一两秒
     * 内，与首次对齐同代价）。让位所有权威事件与点击落地观测。
     */
    private var trackLandingWatchBaseMs = -1L

    /** 二次微调会话的武装时刻（= 切歌对齐发出时刻）。 */
    private var trackLandingArmedAtMs = 0L

    /** 观测到的正偏移（app 领先量）。0 = 尚未观测到落地。 */
    private var trackLandingDeltaMs = 0L

    /** 到点执行一键对齐的时刻。0 = 观测阶段；> 0 = 等待执行阶段。 */
    private var trackLandingExecuteAtMs = 0L

    /** 结束二次微调会话（触发 / 超窗 / 切歌 / 点击 / 断连）。 */
    private fun disarmTrackLanding(reason: String) {
        if (trackLandingWatchBaseMs < 0L) return
        LogCapture.d(
            "Progress",
            "track landing watch end reason=$reason base=$trackLandingWatchBaseMs " +
                "delta=${trackLandingDeltaMs}ms"
        )
        trackLandingWatchBaseMs = -1L
        trackLandingArmedAtMs = 0L
        trackLandingDeltaMs = 0L
        trackLandingExecuteAtMs = 0L
    }

    /** 作废恢复后的待重锚：用户已指定新位置时不能再自动覆盖基准。 */
    private fun cancelPendingResync(reason: String) {
        if (resyncPending) {
            LogCapture.d("Progress", "resync cancelled reason=$reason")
        }
        resyncPending = false
        // 用户主动跳转 / 切歌 / 换源 / 断连 / 暂停等新事件一律作废双边对齐
        // 登记，避免随后 8 秒内的落地守卫吞掉用户真正指定的新位置。
        // app 内新 seek 只清掉旧登记，不再登记新的守卫（见 seekWithFallback）。
        bothSidesAlignTargetMs = -1L
    }

    /** 暂停态等场景下「跳转指令未确认」的目标：恢复播放时核对并补发一次。 */
    private var pendingSeekRetryTargetMs = -1L

    /** 补发任务的截止时刻：超过后放弃（用户可能已手动跳转或切歌）。 */
    private var pendingSeekRetryUntil = 0L

    /** 补发只执行一次，避免播放器持续忽略时无限重试打断播放。 */
    private var pendingSeekRetryIssued = false

    /** 暂停态 seek 锁在"恢复播放"后的释放截止时刻：到期强制解锁并补发一次，避免永久锁定。 */
    private var pausedSeekResumeDeadline = 0L

    /** 进度诊断日志限流时刻：每 5 秒输出一次当前进度全貌。 */
    private var lastProgressLogAt = 0L

    // —— 以下均为纯诊断状态，不参与计时 / 选源 / 跳转的任何判断 ——
    /** 上一帧 updateFromController 的时钟基准，用于识别「亮屏 / 长空档后的首帧」。 */
    private var lastFrameElapsedMs = 0L
    private var lastFrameUptimeMs = 0L
    private var lastFrameWallMs = 0L
    private var lastFrameRawMs = -1L

    /** 上一次 1 秒扫描的时刻，用于发现熄屏期间主线程 / 服务停摆的空档。 */
    private var lastScanElapsedMs = 0L
    private var lastScanUptimeMs = 0L

    /** 最近一次读秒对表把应用内计时往回拉了多少（仅诊断，不参与计时）。 */
    private var lastSecondSyncBackMs = 0L

    /** 最近一次读秒对表的本机时刻（仅诊断）。 */
    private var lastSecondSyncAtMs = 0L

    /**
     * 未生效 seek 的补发核对：暂停态播放器常忽略 seekTo 且不上报状态，登记
     * 目标后待出现新鲜的"播放中"快照时核对真实位置，仍未接近目标则补发一次
     * ——让"暂停时点击歌词跳转"在恢复播放后落到目标行开始唱。这里只判断
     * 「指令是否被接收」，补发不会改应用自身计时基准。
     */
    private fun maybeRetryPendingSeek(state: PlaybackState, now: Long) {
        val target = pendingSeekRetryTargetMs
        if (target < 0L || pendingSeekRetryIssued) return
        if (now > pendingSeekRetryUntil) {
            pendingSeekRetryTargetMs = -1L
            return
        }
        // 整秒自计时仍在进行：先等补偿走完再核对，不丢弃补发登记（暂停态点击
        // 时播放器可能确实忽略了跳转，恢复播放后仍需要把指令补上）
        if (MusicProgress.isSeekCatchupActive() ||
            now < localSeekPendingUntil
        ) return
        // 仅在播放中且快照新鲜时处理：暂停中的陈旧快照不代表恢复后的位置，
        // 等恢复播放后的第一条新快照再核对
        if (state.state != PlaybackState.STATE_PLAYING) return
        val stamp = state.lastPositionUpdateTime
        if (stamp <= 0L || now - stamp > SNAPSHOT_FRESH_MS) return
        // 已接近目标：seek 实际已生效（如恢复播放时自动落到目标），清除任务
        if (kotlin.math.abs(state.position - target) <= SEEK_RECHECK_TOLERANCE_MS) {
            pendingSeekRetryTargetMs = -1L
            return
        }
        pendingSeekRetryIssued = true
        LogCapture.w("Seek", "retry pending seek target=$target snap=${state.position}")
        // 补发只重发跳转指令、顺延钉行与自计时保护窗口；应用自身计时基准
        // 保持不动，不因为播放器快照的偏移位置重算歌词时间
        localSeekPendingUntil = now + SEEK_SELF_TIMED_WINDOW_MS
        MusicProgress.markSeekPending(target, localSeekLineMs, now + SEEK_UI_PENDING_MS)
        runCatching { activeController?.transportControls?.seekTo(target) }
            .onFailure { Log.w(TAG, "pending seek retry failed: ${it.message}") }
    }

    private fun trySeek(
        candidates: List<MediaController>,
        index: Int,
        positionMs: Long,
        issuedAt: Long,
    ) {
        val controller = candidates.getOrNull(index) ?: return
        val prePos = controller.playbackState?.position
        runCatching { controller.transportControls.seekTo(positionMs) }
            .onFailure { Log.w(TAG, "seekTo failed: ${it.message}") }
        // 诊断：多会话送达链上每个会话在指令发出前后的位置
        val at = SystemClock.elapsedRealtime()
        LogCapture.d(
            "Seek",
            "trySeek seekTo idx=$index/${candidates.size} " +
                "sid=${System.identityHashCode(controller)} target=$positionMs " +
                "prePos=$prePos postPos=${controller.playbackState?.position} " +
                "at=$at issuedAt=$issuedAt sessions=[${sessionDiag(at)}]"
        )
        if (index + 1 >= candidates.size) return
        // 校验：短时间后读回进度，需同时满足"位置接近目标"与"会话存活"。
        // 残留的失效会话不会响应 seek：position 停留在跳转前的值，而点击歌词
        // 行的跳距通常只有 1~3 秒，若仅按位置接近判定（容差偏宽）会把失效
        // 会话误判成功，seek 静默无效——播放器继续唱原句。存活判据：状态
        // 时间戳在 seek 发出后有更新，或位置相对 seek 前发生了明显移动。
        uiHandler.postDelayed({
            // 快速连点 / 乱点时，上一次 seek 的校验回调可能在新 seek 发出后
            // 才执行；必须作废，否则它会按旧目标继续向下一个会话补发 seekTo，
            // 把刚跳过去的进度拉回旧行（连点时「跳转不生效」的主因之一）。
            if (localSeekIssuedAt != issuedAt) return@postDelayed
            val state = controller.playbackState
            val pos = state?.position
            val nearTarget = pos != null &&
                kotlin.math.abs(pos - positionMs) <= SEEK_TOLERANCE_MS
            val stampFresh = state != null &&
                state.lastPositionUpdateTime >= issuedAt - SEEK_STAMP_SLACK_MS
            val moved = pos != null && prePos != null &&
                kotlin.math.abs(pos - prePos) > SEEK_MOVE_MIN_MS
            if (!nearTarget || !(stampFresh || moved)) {
                trySeek(candidates, index + 1, positionMs, issuedAt)
            }
        }, SEEK_VERIFY_DELAY_MS)
    }

    private fun updateFromController(controller: MediaController?) {
        controller ?: return
        val state = controller.playbackState
        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val now = SystemClock.elapsedRealtime()
        // —— 诊断：记录本帧三种时钟基准，识别「熄屏 / 长空档后的唤醒首帧」 ——
        // elGap 与 upGap 的差值即 deep sleep 时长：深睡时 uptimeMillis 不走。
        // 此处只记录，不改动任何计时或状态判断。
        val frameUptimeMs = SystemClock.uptimeMillis()
        val frameWallMs = System.currentTimeMillis()
        val frameElapsedGapMs =
            if (lastFrameElapsedMs > 0L) now - lastFrameElapsedMs else -1L
        val frameUptimeGapMs =
            if (lastFrameUptimeMs > 0L) frameUptimeMs - lastFrameUptimeMs else -1L
        val frameWallGapMs =
            if (lastFrameWallMs > 0L) frameWallMs - lastFrameWallMs else -1L
        val frameSleepDeltaMs = frameElapsedGapMs - frameUptimeGapMs
        val frameWake = lastFrameElapsedMs > 0L &&
            (frameElapsedGapMs >= FRAME_WAKE_GAP_MS ||
                frameSleepDeltaMs >= FRAME_WAKE_SLEEP_MS)
        val framePrevRawMs = lastFrameRawMs
        val frameSourceSwitched = sourceSwitched
        lastFrameElapsedMs = now
        lastFrameUptimeMs = frameUptimeMs
        lastFrameWallMs = frameWallMs
        lastFrameRawMs = state?.position ?: -1L
        // 上一轮的播放状态：用于识别"暂停→播放"过渡（末尾才写回 isPlaying，
        // 此处读到的仍是旧值）
        val wasPlaying = MusicProgress.isPlaying
        // 仅在拿到非空新标题时才判定切歌：部分播放器在切歌间隙会瞬时上报 null
        // 标题，若据此归零进度，高亮会先跳到开头再跳回，表现为"乱跳"。
        // 新歌曲标题缺失时依靠 position 快照回落到 0 自然归位，无需强制重置。
        // 进度处理分支名：用于 Progress 诊断日志，跨 if/else 块可见。
        var branch = "trackChanged"
        if (!title.isNullOrEmpty() && title != lastTrackTitle) {
            // 切歌：上一首的 position 可能残留，先归零，再等「新歌首条有效进度
            // 上报」读取一次作为起点（含播放器记住的单曲进度），读完即整首歌
            // 纯应用内计时——对所有应用一致，不再有开关。
            // 「毫秒信息不可信」的应用读取起点时只取整秒值。
            // 倍速沿用播放器当前值，保持外推与真实播放速度一致。
            val previousTitle = lastTrackTitle
            lastTrackTitle = title
            // 记录切歌时刻并清空残留会话标记：新歌需要重新按"位置是否与
            // 经过时间一致"判定真实播放会话与沿用上一首位置的残留会话。
            // 首次拿到标题（服务刚启动、中途接入播放）不算切歌——此时曲目早已
            // 播放到中途，用"从现在起的经过时间"作为参照会把真实会话误判为残留，
            // 也不能按 0 起算，仍要读播放器位置。
            trackTitleChangedAt = if (previousTitle != null) now else 0L
            // 真实切歌（服务中途接入拿到首个标题不算）要重新走「等首条有效
            // 进度 → 读一次 → 整首歌纯应用内计时」流程；接入时曲目已播到中途，
            // 仍由常规分支直接读播放器当前位置。
            val realTrackChange = previousTitle != null
            // 捕获切歌瞬间（尚未被本帧改写）的播放器状态三要素：切歌后的
            // 快照若与之完全一致，说明播放器自切歌起没有任何新上报（长时间
            // 播放后为优化性能不上报/漏报），读到的是上一首残留状态，绝不能
            // 当作新歌起点采用。
            trackStartPrevPos = lastReportedPos
            trackStartPrevStampMs = lastSeenStateStampMs
            trackStartPrevPlayState = lastSeenPlayState
            trackStartPendingReal = realTrackChange
            // 起点尚未读取：先由应用内计时从归零处推进，等首条有效进度读到后
            // 才转 trackSelfTimed；始终读不到则在宽限窗结束时直接转。
            trackSelfTimed = false
            trackStartReadPosMs = -1L
            trackStartSawZeroReport = false
            staleSessionIds.clear()
            divergenceStrikes = 0
            lastReportedPos = 0L
            lastSnapshotWallMs = now
            trackChangedPending = true
            // 歌曲切换版本号递增：列表循环同一首歌时，tryAutoMatch() 因
            // key==matchedKey 提前返回、Session.lyricLines 引用不变，
            // 桌面歌词仅靠 lines !== lastLines 检测不到切歌，必须依赖此版本号
            // 强制重置 currentLineIndex 并打断长歌词滚动动画
            Session.songChangeVersion++
            // 切歌瞬间立即作废上一首歌词：自动匹配还有 400ms 防抖 + 后台文件扫描，
            // 若等匹配结果写回才清，主界面与桌面歌词这段时间内会继续显示旧歌词。
            if (realTrackChange) invalidateLyricsOnTrackChange(metadata)
            LogCapture.i(
                "Music",
                "切歌：$title / ${metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()}" +
                    " 等待首条有效进度=$trackChangedPending " +
                    "纯应用内计时=$trackSelfTimed " +
                    "切歌前快照(pos=${trackStartPrevPos} stamp=${trackStartPrevStampMs} " +
                    "ps=${trackStartPrevPlayState}) " +
                    "raw=${state?.position} prevTitle=$previousTitle " +
                    "sinceScreen=${Diag.sinceScreenEvent(now)}ms " +
                    "sinceConnect=${Diag.sinceConnect(now)}ms " +
                    "sessions=[${sessionDiag(now)}]"
            )
            pendingSeekRetryTargetMs = -1L
            MusicProgress.pausedSeekTargetMs = -1L
            // 切歌：上一首遗留的待重锚与诊断记录立即作废
            cancelPendingResync("track change")
            lastResyncTargetMs = -1L
            lastResyncAtMs = 0L
            lastResyncReason = "-"
            lastResyncDeltaMs = 0L
            // 切歌：结束上一首的强保护窗口；新歌首条有效上报会读取一次起点
            // （毫秒不可信的应用只读整秒）
            localSeekPendingUntil = 0L
            // 切歌：上一首点击跳转的落地观测一并作废（新歌起点自带双边对齐）
            disarmSeekLanding("track change")
            // 切歌：上一首歌的二次微调会话也作废（本首歌对齐后另行武装）
            disarmTrackLanding("track change")
            // 切歌：上一首遗留的「未落地冻结 / 毫秒落点补偿」必须立即解除，
            // 否则显示进度会一直冻在旧歌的目标位置，新歌歌词卡住不动
            MusicProgress.clearSeekPending()
            pausedSeekResumeDeadline = 0L
            // 新歌的时长需重新获取：清掉上一首的兜底值，避免把旧歌时长
            // 错误地套到新歌上（钳制进度上限）
            missingDurationFallbackMs = 0L
            stalledSinceAt = 0L
            lastPosChangeAt = 0L
            // 切歌归零是直接写 MusicProgress、不经 adoptBase，诊断基准同步归零
            lastAdoptBaseMs = 0L
            MusicProgress.positionMs = 0L
            MusicProgress.stateTimeMs = now
            MusicProgress.snapshotElapsedMs = now
            MusicProgress.speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        } else if (state != null) {
            var trackJustChanged = trackChangedPending
            // 「新歌首条状态上报」判定：状态三要素（位置/时间戳/播放态）相对
            // 切歌瞬间发生过变化，才是新歌的上报；一字未变说明播放器自切歌起
            // 没有任何新上报（长时间播放后为优化性能不上报/漏报），当前读到
            // 的只是上一首的残留状态。
            val newSongStateArrived = !trackJustChanged || sourceSwitched ||
                state.position != trackStartPrevPos ||
                state.lastPositionUpdateTime != trackStartPrevStampMs ||
                state.state != trackStartPrevPlayState
            // 只有位置 > 0 才是「可读取的新歌进度」：0（或 -1）是占位值 / 播放器
            // 把进度锁定在 0，没有起点可读，继续由应用内计时从切歌归零处推进。
            // 状态冻结防护：部分播放器（如 Telegram Web）暂停后不释放 MediaSession，
            // 也不再刷新 PlaybackState，残留的是很久以前的整秒位置（日志里状态
            // 时间戳 40s~250s 未更新）。换源重新选中这类会话、标题又被判为切歌时，
            // 这个残留值既不能当「切歌首条有效进度」，也不能用于换源对表。
            // 判定只看状态本身：时间戳长时间未刷新，或会话位置长时间静止；
            // 阈值需显著大于慢节拍播放器的上报间隔（Telegram 约 5 秒）。
            val updateTime = state.lastPositionUpdateTime.takeIf { it > 0 }
            val stateAgeMs = updateTime?.let { (now - it).coerceAtLeast(0L) } ?: 0L
            val stateStuckMs = positionStuckMap[System.identityHashCode(controller)]?.let { stuck ->
                if (stuck.pos == state.position) (now - stuck.sinceMs).coerceAtLeast(0L) else 0L
            } ?: 0L
            val stateFrozen = stateAgeMs >= FROZEN_STATE_AGE_MS ||
                stateStuckMs >= FROZEN_STATE_AGE_MS
            // 只在「真实切歌」帧生效：服务中途接入首个标题不算切歌，仍按原路径
            // 读播放器位置；纯换源但标题未变也不是换歌，不干预既有对表规则。
            val frozenTrackStart = stateFrozen && trackJustChanged && trackStartPendingReal
            if (frozenTrackStart && now - trackTitleChangedAt < 1_000L) {
                LogCapture.w(
                    "Progress",
                    "frozen state on track start: ignore snapshot " +
                        "ageMs=$stateAgeMs stuckMs=$stateStuckMs " +
                        "pos=${state.position} stamp=${state.lastPositionUpdateTime} " +
                        "src=$sourceSwitched title=${lastTrackTitle?.take(10)} " +
                        "selfBase=${MusicProgress.positionMs}"
                )
            }
            // 切歌起点贴到时长末尾（pos >= dur - 1s）= 上一首播完卡在末尾的残留
            // 值，不是新歌真实起点。锁屏积压唤醒时实测 active 会话 pos==dur=
            // 194000（另一真在播会话才 49203），把它当起点会双边 seekTo(末尾)
            // 打断播放器，还会让 stale 判定把真会话误标为残留。这类帧既不读
            // 起点、也不按换源对表，走等待宽限窗（随后真新歌标题到达再切歌）。
            val trackStartEndResidue = trackJustChanged && trackStartPendingReal &&
                state.position > 0L && run {
                    val dur = controllerDuration(controller)
                    dur > 0L && state.position >= dur - TRACK_START_END_GUARD_MS
                }
            // 冻结状态下即便位置 > 0 也只是残留值，同样不算有效进度。
            val newSongProgressReady = newSongStateArrived && state.position > 0L &&
                !frozenTrackStart && !trackStartEndResidue
            if (trackJustChanged && trackStartPendingReal && newSongStateArrived &&
                state.position <= 0L
            ) {
                trackStartSawZeroReport = true
            }
            // 切歌后等待首条有效进度：任何应用都只读取这一次新歌起点（含记忆
            // 进度）。宽限窗内不采用任何快照，进度由应用内计时从归零处推进
            // （防冻结——不上报或进度锁定为 0 的播放器歌词照常滚动）；
            // 宽限窗耗尽仍无有效进度则不再读，切歌归零即本首歌最终起点。
            // 换源属于必须重新对表的外部事件，通常不经此等待；但换到状态冻结
            // 的残留会话时例外（见 frozenTrackStart），此时同样只信内部计时。
            // 服务中途接入（首个标题）不是真实切歌，仍按原路径直接读播放器位置。
            val trackStartHold: Boolean
            if (trackJustChanged && trackStartPendingReal && !trackSelfTimed &&
                // 冻结状态被重新选中时即便 sourceSwitched 也要走宽限等待：换源原本
                // 必须重新对表，但此刻对表的对象是陈旧的残留快照，只能由应用内计时
                // 从切歌归零处推进。
                (!sourceSwitched || frozenTrackStart) && !newSongProgressReady
            ) {
                if (now - trackTitleChangedAt < TRACK_START_FRESH_WAIT_MS) {
                    // 继续等有效进度：本帧不按切歌落地处理，也不采用任何快照
                    trackChangedPending = true
                    trackJustChanged = false
                    trackStartHold = true
                } else {
                    // 长时间无上报 / 进度一直锁定为 0：没有可读取的起点，
                    // 切歌归零即本首歌最终起点，完全相信应用内计时
                    trackChangedPending = false
                    trackSelfTimed = true
                    trackStartHold = false
                    LogCapture.w(
                        "Progress",
                        "track start no-progress: selfTimed engaged " +
                            "wait=${now - trackTitleChangedAt}ms " +
                            "zeroLocked=$trackStartSawZeroReport " +
                            "pos=${state.position} stamp=${state.lastPositionUpdateTime} " +
                            "title=${lastTrackTitle?.take(10)}"
                    )
                }
            } else {
                trackChangedPending = false
                trackStartHold = false
            }
            // 切歌后首条快照：等待有效进度的宽限帧、以及宽限窗耗尽判定
            // 「没有可读进度」的那一帧，连播放器位置都不读（残留位置会把
            // 进度拉离新歌开头）；换源属于必须重新对表的外部事件，不在此
            // 抑制范围内。
            val trackStartSnapshotSuppressed = (!sourceSwitched || frozenTrackStart) &&
                (trackStartHold || (trackJustChanged && trackSelfTimed))
            // 暂停→播放过渡：旧基准停留在暂停时刻，若直接沿用，恢复播放后的
            // 外推会把整段暂停时长计入进度（估算大幅超前、高亮跳到后面的行）。
            // 先把基准时刻平移到当前（进度值保持连续）；本帧只续走应用内进度，
            // 不再让随后的快照分支覆盖这个基准。
            val justResumed = !wasPlaying && state.state == PlaybackState.STATE_PLAYING
            if (justResumed) {
                if (MusicProgress.stateTimeMs > 0L) MusicProgress.stateTimeMs = now
                // 暂停态 seek 锁恢复播放后设置释放截止：部分播放器（如 Telegram）
                // 恢复后长期不刷新状态时间戳，补发核对永远等不到新鲜快照，
                // 锁会永久驻留并把进度钉死在某一行（观感为歌词停在最后一行）。
                if (MusicProgress.pausedSeekTargetMs >= 0L) {
                    pausedSeekResumeDeadline = now + PAUSED_SEEK_RESUME_GRACE_MS
                    LogCapture.d(
                        "Seek",
                        "pausedSeekLock resume target=${MusicProgress.pausedSeekTargetMs} " +
                            "snap=${state.position} deadline=${PAUSED_SEEK_RESUME_GRACE_MS}ms"
                    )
                }
            }
            // 未生效 seek 的补发核对：播放器恢复/继续播放时补一次被忽略的跳转
            maybeRetryPendingSeek(state, now)
            // 暂停态：播放器快照不可靠（旧位置 / 0 占位），但只用来判断「要不要
            // 补发指令」；歌词时间始终由应用自己的计时基准推进，绝不拿快照位置
            // 覆盖基准。pausedSeekTargetMs 在这里仅是「跳转未确认」的标记。
            val pausedSeekLocked = MusicProgress.pausedSeekTargetMs >= 0L
            if (pausedSeekLocked) {
                val pausedTarget = MusicProgress.pausedSeekTargetMs
                // 持续刷新 UI 目标宽限，确保消费端高亮对齐到目标行
                MusicProgress.seekUiTargetMs = pausedTarget
                MusicProgress.seekUiTargetUntil = now + 5_000L
                LogCapture.d(
                    "Seek",
                    "pausedSeekLock hold target=$pausedTarget snap=${state.position} " +
                        "selfBase=${MusicProgress.positionMs} " +
                        "ps=${state.state} stampAge=${
                            if (state.lastPositionUpdateTime > 0)
                                now - state.lastPositionUpdateTime else -1
                        } dur=${MusicProgress.durationMs} speed=${state.playbackSpeed} " +
                        "catchup=${MusicProgress.isSeekCatchupActive()} " +
                        "retryIssued=$pendingSeekRetryIssued deadline=$pausedSeekResumeDeadline"
                )
            } else {
            val newSpeed = state.playbackSpeed.takeIf { it > 0f } ?: 1f
            // 当前估算值（改基准前的旧值）：即本机时钟对"当前真实位置"的推算
            val prevEstimate = MusicProgress.currentPositionMs()
            val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            // 基准时间合法性校验：部分播放器的 lastPositionUpdateTime 用了错误的
            // 时间基准（如 currentTimeMillis，远大于 elapsedRealtime）或是过小的
            // 残留值。前者使 (now - 基准) 为负、进度被钳到 0（跳首行）；后者使
            // 估算进度爆冲、被钳到时长上限（跳最后一行）。判定：基准不得是未来
            // 时间；已知时长时，按基准推算的进度不得超出时长+宽限。
            val baselineValid = updateTime != null && updateTime <= now &&
                (durationMs <= 0L ||
                    state.position + ((now - updateTime) * newSpeed).toLong() <=
                        durationMs + RUNAWAY_GRACE_MS)
            // 跳跃防护：把播放器基准直接当本机时钟原点只在其同源时成立。若按基准
            // 推算出的位置远超"本机时钟预期位置"，说明基准原点不可信（如播放器
            // 用 uptimeMillis 等另一时钟、或选中的是过期残留会话）。prevEstimate
            // 已经包含到当前时刻的全部推算，不能再叠加 elapsed-since-snapshot，
            // 否则预期值被双重放大，防护失效。
            val candidate = if (updateTime != null) {
                state.position + ((now - updateTime) * newSpeed).toLong()
            } else 0L
            // 播放中占位/冻结快照判定（不依赖 durationMs：时长恢复后播放器
            // 仍可能持续上报贴在开头或停滞的位置，若此时失去保护，漂移校正
            // 会把估算进度拉回该值——表现为歌词持续跳回第一行）：
            // a) 播放中位置贴在开头（0~1.5s）——多数播放器的占位值；
            // b) 播放中位置长时间（≥2s）没有变化——冻结快照（如 Telegram
            //    内置播放器每 5 秒才上报一次、期间位置静止）。
            // 用时间累计而非回调次数：回调常成簇到达，按次数累计会被清零。
            val playingNow = state.state == PlaybackState.STATE_PLAYING
            if (state.position != lastReportedPos) lastPosChangeAt = now
            val frozenMs = if (lastPosChangeAt > 0L) now - lastPosChangeAt else 0L
            val placeholderActive = playingNow && (
                state.position in 0..STALLED_POSITION_MAX_MS ||
                    frozenMs >= STALLED_FREEZE_MS
                )
            if (placeholderActive) {
                if (stalledSinceAt == 0L) {
                    stalledSinceAt = if (frozenMs >= STALLED_FREEZE_MS) lastPosChangeAt else now
                }
            } else {
                stalledSinceAt = 0L
            }
            val snapshotStalled = stalledSinceAt > 0L &&
                now - stalledSinceAt >= STALLED_MIN_MS
            val baseTrustworthy = updateTime != null && baselineValid &&
                candidate - prevEstimate <= JUMP_TOLERANCE_MS && !snapshotStalled &&
                !frozenTrackStart
            MusicProgress.baseTrusted = baseTrustworthy
            // seek 判定：快照新鲜（基准时间接近当前）且相邻快照推进量与墙钟
            // 推进量明显不符，说明发生了 seek/换源，而非正常播放的周期上报。
            val wallDelta = ((now - lastSnapshotWallMs) * newSpeed).toLong()
            val snapDelta = state.position - lastReportedPos
            val snapshotFresh = updateTime != null && now - updateTime <= SNAPSHOT_FRESH_MS
            // 快照位置是否可信：
            // 1. PlaybackState 未提供位置时 position 为 -1，不代表真实进度；
            // 2. snapshotStalled 已在上面判定（占位值/冻结快照）。
            // 切歌帧命中冻结防护时，整个快照都不参与读起点与换源对表。
            val positionCredible = state.position >= 0L && !snapshotStalled &&
                !frozenTrackStart
            branch = "idle"
            // seek 判定：冻结快照下位置没变（snapDelta=0）不是 seek，跳过判定避免误触发。
            val seekDetected = !snapshotStalled && lastSnapshotWallMs > 0L && snapshotFresh &&
                kotlin.math.abs(snapDelta - wallDelta) > SEEK_DISCONTINUITY_MS
            // 「毫秒信息不可信」应用的一键对齐：seekTo 落地后位置跳回整秒目标，
            // 慢上报播放器要几秒后才上报。这不是外部跳转，绝不能让
            // secondSyncEvent 按旧快照把刚对齐的基准写回去（会倒退好几秒）。
            if (bothSidesAlignTargetMs >= 0L &&
                now - bothSidesAlignIssuedAtMs > ALIGN_BOTH_QUIET_MS
            ) {
                bothSidesAlignTargetMs = -1L
            }
            val alignLanding = bothSidesAlignTargetMs >= 0L &&
                kotlin.math.abs(state.position - bothSidesAlignTargetMs) <=
                    USER_SEEK_JUMP_MS
            // 明确的外部事件：换源 / 切歌 / 用户在播放器里手动跳转（点击后短
            // 时间内的间断跳变是本次 seek 的落地结果，用静默期排除）。
            // 停播对齐 / 点击跳转刚发出后用户立刻拖进度条：位置间断落在本地
            // seek 的静默期里，既有 externalSeek 会把它当成「本次 seek 的落地」
            // 吞掉，内部计时不跟随，对齐确认还会补发 seekTo 把用户的新位置
            // 拉回旧目标。用跳变量级区分：seek 落地 / 未落地的相位差最多
            // 1~2 秒，用户拖动通常远大于此；落在本地目标附近的帧一律算落地。
            val nearLocalSeekTarget = localSeekTargetMs >= 0L &&
                kotlin.math.abs(state.position - localSeekTargetMs) <=
                    USER_SEEK_JUMP_MS
            val userSeekDuringQuiet = seekDetected && positionCredible &&
                now - localSeekIssuedAt <= SEEK_EXTERNAL_SEEK_QUIET_MS &&
                kotlin.math.abs(state.position - prevEstimate) > USER_SEEK_JUMP_MS &&
                !nearLocalSeekTarget
            val externalSeek = !alignLanding && ((seekDetected &&
                now - localSeekIssuedAt > SEEK_EXTERNAL_SEEK_QUIET_MS &&
                kotlin.math.abs(state.position - prevEstimate) > SEEK_DISCONTINUITY_MS) ||
                userSeekDuringQuiet)
            // 切歌首条有效进度由下面的 trackStartRead 分支独占读取；读完即
            // trackSelfTimed，之后本首歌只按应用内计时推进，只有换源 /
            // 播放器内手动跳转才重新读播放器位置。
            val explicitExternal = sourceSwitched ||
                (trackJustChanged && !trackSelfTimed) || externalSeek
            if (explicitExternal) {
                // 换源 / 播放器内手动跳转：结束本次 seek 的自计时保护窗口
                localSeekPendingUntil = 0L
                // 用户已指定新位置：恢复后的待重锚一并作废，绝不能按旧快照
                // 把歌词重新拉走（userSeekDuringQuiet 仍让内部计时跟随新位置）
                cancelPendingResync(
                    if (userSeekDuringQuiet) "user seek during quiet" else "external event"
                )
            }
            // 播放 / 暂停状态切换不是「对表事件」：暂停只冻结应用当前的内部
            // 估算进度；普通应用恢复后从冻结值继续，固定相位差由「恢复后
            // 一次性重锚」消掉（见 when 之后的 resyncPending 处理）。
            // 「毫秒信息不可信」的应用例外：恢复帧直接双边对齐（主动 seek 到
            // 整秒 + 应用内计时锚到同一刻度），用一次已知起点抹掉检测延迟与
            // 播放器起播点不一致，不再等新快照重锚；实机验证同步良好。
            // 暂停帧仍只冻结整秒，避免在两端各发一次跳转。
            val playStateChanged =
                (state.state == PlaybackState.STATE_PLAYING) != wasPlaying
            // 纯暂停 / 恢复必须优先于所有快照采用分支。快速切换时，同一帧里的
            // 位置间断可能被误判为 externalSeek，或周期快照仍带着暂停时长；
            // 若继续走 secondAdopt / adoptSwitch / snapAdopt，就会覆盖 justResumed
            // 刚平移的基准。切歌与换源不是单纯播放状态切换，仍按原规则对表。
            val playStateTransition = playStateChanged &&
                !sourceSwitched && !trackJustChanged
            // 「当前应用的毫秒信息不可信」：只信应用内计时，仅在切歌 / 换源 /
            // 播放器内调整进度时读播放器的整秒值重设基准。读进来的值一律丢弃
            // 毫秒（wholeSecondBase），且不用 lastPositionUpdateTime 外推——
            // 那会重新引入毫秒级偏移。暂停 / 恢复绝不在这里重设基准。
            // 切歌首条有效进度由 trackStartRead 分支读取，同样只取整秒。
            val msUntrusted = PlaybackOffset.isMsUntrusted(autoMatchPrefs)
            val secondPos = wholeSecondBase(state.position)
            // 换源帧的目标会话本身必须能代表真实进度。chooseActive() 换源时会
            // 清空冻结跟踪（stalledSinceAt / lastPosChangeAt 归零），于是新源首帧
            // 「位置恒为 0、也没有时长」的残留 MediaSession 占位值不会被判成
            // snapshotStalled，positionCredible 反而是 true；若据此整秒对表，
            // 基准被写成 0、歌词瞬间跳回第 0 行，随后换回真实会话又跳回中后段。
            // 这类会话在打分体系里本就是死会话（baseScoreOf <= 4），不作为对表依据。
            // 被挡住的帧落到 appTimed，保持应用内计时不变（切歌归零时本就在 0 附近）。
            val sourceSyncUsable = !sourceSwitched ||
                controllerDuration(controller) > 0L ||
                state.position > STALLED_POSITION_MAX_MS
            val secondSyncEvent = msUntrusted && positionCredible &&
                !trackStartSnapshotSuppressed && explicitExternal && sourceSyncUsable &&
                !trackStartEndResidue
            // 普通应用（毫秒信息可信）：点击后的「应用自计时」只覆盖整 1 秒
            // 强保护窗口（含整秒 catchup 冻结），窗口结束后恢复周期快照校正。
            // 「毫秒信息不可信」的应用由下面的 appTimed / secondAdopt 分支接管，
            // 不走这一套周期校正。
            val selfTimedActive =
                now < localSeekPendingUntil || MusicProgress.isSeekCatchupActive()

            // 「整首歌纯应用内计时」期间的外部对表事件：换源、播放器内手动跳转。
            // 暂停 / 恢复不参与对表，由下面的 appPauseFreeze 固化并续走内部进度。
            // 毫秒信息不可信的应用由上面的 secondSyncEvent 走整秒对表，不会进
            // 下面这条分支。
            val trackEventSync = trackSelfTimed && !msUntrusted &&
                !trackStartSnapshotSuppressed && positionCredible &&
                (sourceSwitched || externalSeek) && !trackStartEndResidue

            // 切歌后读到首条有效新歌进度：只读取这一次作为本首歌起点，读完
            // 立即转入整首歌纯应用内计时（见 when 之后的置位）。位置 <= 0
            // （占位 / 锁定为 0）不算有效进度，继续等待；服务中途接入（首个
            // 标题）也不走这里，仍由常规分支读播放器当前位置。
            val trackStartRead = trackJustChanged && trackStartPendingReal &&
                !trackStartHold && !trackStartSnapshotSuppressed && newSongProgressReady

            // 普通应用的恢复后一次性重锚放在 when 之后执行：要能覆盖状态切换
            // 分支刚写下的内部计时基准，且不能抢在切歌 / 外部跳转等更权威的
            // 事件之前。msUntrusted 应用已在 playStateTransition 内双边对齐，
            // 不再登记 resyncPending。
            when {
                // 暂停：固化本机时钟估算值；恢复：从冻结值继续（justResumed
                // 已将基准时刻平移到 now）。两种切换都不读取本帧播放器位置。
                // 内部计时不加任何补偿：暂停帧 prevEstimate 已按播放态外推到
                // now，恢复帧就是暂停冻结值；不再按暂停时长补回墙钟窗口。
                playStateTransition -> {
                    // 「毫秒信息不可信」：暂停时把应用内部计时恢复整秒刻度（这类
                    // 播放器的暂停点本身量化到整秒）；恢复播放时同样按整秒
                    // 内部计时对齐（正常路径暂停已冻结在整秒，此处幂等）。
                    val internalPosition = if (msUntrusted) {
                        wholeSecondBase(prevEstimate)
                    } else {
                        prevEstimate
                    }
                    branch = if (playingNow) "appResumeContinue" else "appPauseFreeze"
                    adoptBase(internalPosition, now, newSpeed, now)
                    // 「毫秒信息不可信」+ 恢复播放：直接做一次双边对齐，把音频
                    // 与应用内计时一起锚到上面这个整秒值。状态切换是「检测到」
                    // 而非「发生」，检测延迟、播放器恢复起点 ≠ app 冻结值这两
                    // 个偏移根源都被这次主动 seek 抹掉，不必再等新快照重锚。
                    // 暂停帧音频不推进、播放器也常忽略暂停态 seekTo，只冻结
                    // 整秒，由恢复帧统一对齐（一次跳转，不在两端各跳一次）。
                    var alignSent = -1
                    if (playingNow && !trackStartHold) {
                        if (msUntrusted) {
                            alignSent = applyBothSidesAlign(
                                internalPosition, now, newSpeed, "resume"
                            )
                            // 不能再等新快照做单向重锚，否则会覆盖刚对齐的
                            // 基准；也不能调 cancelPendingResync（它会清掉
                            // 刚登记的落地守卫）。
                            resyncPending = false
                        } else {
                            // 其它应用：恢复后等首份时间戳前进的新快照，
                            // 按快照自带时刻外推归位（when 之后执行）。
                            resyncPending = true
                            resyncIssuedAtMs = now
                        }
                    } else {
                        cancelPendingResync("pause / track start hold")
                    }
                    LogCapture.d(
                        "Progress",
                        "app play state transition playing=$playingNow " +
                            "pos=$internalPosition frozen=$prevEstimate " +
                            "raw=${state.position} msUntrusted=$msUntrusted " +
                            "resync=$resyncPending alignSent=$alignSent " +
                            "trackSelfTimed=$trackSelfTimed"
                    )
                }
                // 切歌后的「唯一一次」进度读取：以播放器上报的新歌位置（含记住的
                // 单曲进度）作为起点。毫秒不可信的应用不用
                // lastPositionUpdateTime 外推；其余应用在基准可信时按快照时间外推。
                // 读完后本帧末尾即转入整首歌纯应用内计时，周期快照不再写基准。
                //
                // 【不再发 seekTo】这类播放器不支持毫秒 seek，过去把音频回拉到
                // 裸位置向下取整的整秒刻度做「双边对齐」，回拉量恒等于
                // raw % 1000（0~999ms 随机），亮屏唤醒时就是听感上的「顿一下」，
                // 随后 trackLanding 还会按落地延迟再回退一次 est（实测 261ms）。
                // 播放器本来就在正确的位置播，整秒量化是惩罚而不是对齐：改为
                // 让应用计时直接锚到裸 raw，双方仍从同一位置起播（est−raw 只剩
                // 快照年龄 1~6ms），既没有音频回拉，也没有落地延迟要观测。
                // 换源 / 播放器内跳转仍由 secondSyncEvent 发 seekTo 拉齐两个会话。
                trackStartRead -> {
                    branch = "trackStartRead"
                    driftStrikes = 0
                    MusicProgress.clearSeekPending()
                    localSeekPendingUntil = 0L
                    val startPos = if (msUntrusted) {
                        wholeSecondBase(state.position)
                    } else {
                        state.position
                    }
                    // 不用「positionMs 取整秒 + stateTimeMs 前移零头」的等价写法：
                    // currentPositionMs() 的外推带 speed，倍速下须再除 speed，
                    // 否则 est 会凭空偏出 z*(speed−1)。直接锚裸值最稳。
                    val startBase = if (msUntrusted) state.position else startPos
                    if (!msUntrusted && baseTrustworthy) {
                        adoptBase(state.position, updateTime!!, newSpeed, now)
                    } else {
                        adoptBase(startBase, now, newSpeed, now)
                    }
                    trackStartReadPosMs = startPos
                    if (msUntrusted) {
                        lastSecondSyncBackMs = prevEstimate - startBase
                        lastSecondSyncAtMs = now
                        // 不发 seekTo ⇒ 不登记 bothSidesAlign* 守卫（否则
                        // ALIGN_BOTH_QUIET_MS 内会把真实用户拖动误吞成落地帧），
                        // 也不武装 trackLanding*（没有指令落地延迟需要观测）。
                    }
                    LogCapture.d(
                        "Progress",
                        "track start read pos=$startBase raw=${state.position} " +
                            "whole=$startPos frac=${state.position % 1000L} " +
                            "prev=$prevEstimate secOnly=$msUntrusted src=$sourceSwitched " +
                            "noSeek=1 alignSent=0 wait=${now - trackTitleChangedAt}ms " +
                            "title=${lastTrackTitle?.take(10)} " +
                            "sinceScreen=${Diag.sinceScreenEvent(now)}ms " +
                            "sessions=[${sessionDiag(now)}]"
                    )
                }
                // 外部事件：只读秒重设应用内计时基准，之后继续由墙钟推进。
                secondSyncEvent -> {
                    branch = "secondAdopt"
                    driftStrikes = 0
                    if (explicitExternal) MusicProgress.clearSeekPending()
                    adoptBase(secondPos, now, newSpeed, now)
                    // 外部事件（换源 / 播放器内手动跳转）也按同一整秒刻度双边
                    // 对齐：只让歌词跟过去会留下播放器整秒量化的零头，两边错开。
                    val alignSent = applyBothSidesAlign(secondPos, now, newSpeed, "external")
                    // 诊断：back>0 表示本次对表把应用内计时往回拉了多少（即注入的滞后），
                    // snapAge 是播放器快照从生成到被读到的年龄。两者都不参与计时。
                    lastSecondSyncBackMs = prevEstimate - secondPos
                    lastSecondSyncAtMs = now
                    val snapAgeMs = if (state.lastPositionUpdateTime > 0L)
                        now - state.lastPositionUpdateTime else -1L
                    LogCapture.d(
                        "Progress",
                        "second sync src=$sourceSwitched track=$trackJustChanged " +
                            "extSeek=$externalSeek playState=$playStateChanged " +
                            "sec=$secondPos raw=${state.position} prev=$prevEstimate " +
                            "back=${lastSecondSyncBackMs}ms snapAge=${snapAgeMs}ms " +
                            "alignSent=$alignSent " +
                            "delay=${PlaybackOffset.delayMs(autoMatchPrefs)}ms " +
                            "target=$localSeekTargetMs " +
                            "sessions=[${sessionDiag(now)}]"
                    )
                }
                // 纯应用内计时（毫秒不可信 / 切歌已读完起点）下暂停：不读播放器位置，
                // 只把当前内部估算值固化进基准。否则 currentPositionMs() 在暂停态
                // 只返回 positionMs，会跳回上一次对表基准。恢复时 justResumed 仅
                // 平移基准时刻，从这里的冻结值继续，不做任何暂停 / 恢复对表。
                // 毫秒不可信的应用与最高优先级的暂停分支一致：冻结值恢复整秒。
                (msUntrusted || trackSelfTimed) && !trackStartHold &&
                    playStateChanged && !playingNow &&
                    !secondSyncEvent && !trackEventSync -> {
                    branch = "appPauseFreeze"
                    val freezePos = if (msUntrusted) wholeSecondBase(prevEstimate) else prevEstimate
                    adoptBase(freezePos, now, newSpeed, now)
                    LogCapture.d(
                        "Progress",
                        "app pause freeze pos=$freezePos raw=${state.position} " +
                            "msUntrusted=$msUntrusted trackSelfTimed=$trackSelfTimed"
                    )
                }
                // 切歌后等待有效进度的宽限帧（不上报 / 进度锁定为 0）：残留快照
                // 一律不写基准，进度从归零起点由应用内计时推进（防冻结——歌词
                // 照常滚动，等不到有效进度也不卡住）。等待期间暂停则把估算值
                // 固化，避免暂停帧跳回归零起点；倍速变化平移基准保持进度连续。
                trackStartHold -> {
                    branch = if (frozenTrackStart) "frozenTrackStartHold" else "trackStartHold"
                    val holdPause = playStateChanged && !playingNow
                    if (holdPause) {
                        branch = "trackStartHoldPause"
                        adoptBase(prevEstimate, now, newSpeed, now)
                    } else if (newSpeed != MusicProgress.speed) {
                        adoptBase(prevEstimate, now, newSpeed, now)
                    }
                }
                // 「毫秒信息不可信」：周期快照一律不写基准，歌词时间只由应用内
                // 墙钟计时推进（包括点击跳转后的整秒 catchup）。
                msUntrusted -> {
                    branch = "appTimed"
                    // 保护窗口在这里只用于排除「本次 seek 落地」被误判成外部跳转，
                    // 到期后不再恢复周期校正，顺手清掉标记。
                    if (localSeekPendingUntil > 0L && now >= localSeekPendingUntil) {
                        localSeekPendingUntil = 0L
                    }
                    if (newSpeed != MusicProgress.speed) {
                        // 倍速变化：以应用自身当前估算值为新起点平移基准，进度连续
                        adoptBase(prevEstimate, now, newSpeed, now)
                    }
                }
                selfTimedActive -> {
                    branch = "selfTimed"
                    if (newSpeed != MusicProgress.speed) {
                        // 以应用自身当前估算值为新起点平移基准，进度保持连续
                        adoptBase(prevEstimate, now, newSpeed, now)
                    }
                }
                // 自计时窗口结束：只清会话标记，不采用快照位置。窗口为整 1 秒，
                // 边界与整秒刻度对齐；切歌读完起点后的纯应用内计时不会恢复
                // 周期校正，只有普通应用的常规帧才在下一帧继续快照校正。
                localSeekPendingUntil > 0L -> {
                    branch = "selfTimedEnd"
                    localSeekPendingUntil = 0L
                    driftStrikes = 0
                    LogCapture.d(
                        "Seek",
                        "self timed end target=$localSeekTargetMs " +
                            "est=${MusicProgress.currentPositionMs()} " +
                            "snap=${state.position} " +
                            "gap=${state.position - MusicProgress.currentPositionMs()}"
                    )
                }
                // 切歌读取起点后：整首歌只在用户操作（换源 / 播放器内手动跳转）
                // 时重新对表；暂停 / 恢复只冻结并续走应用内进度，其余周期快照
                // 一律不写基准。
                trackEventSync -> {
                    branch = "trackEventSync"
                    driftStrikes = 0
                    if (explicitExternal) MusicProgress.clearSeekPending()
                    if (baseTrustworthy) {
                        adoptBase(state.position, updateTime!!, newSpeed, now)
                    } else {
                        adoptBase(state.position, now, newSpeed, now)
                    }
                    val snapAgeMs = if (updateTime != null) now - updateTime else -1L
                    LogCapture.d(
                        "Progress",
                        "track event sync src=$sourceSwitched extSeek=$externalSeek " +
                            "playState=$playStateChanged pos=${state.position} " +
                            "prev=$prevEstimate snapAge=${snapAgeMs}ms"
                    )
                }
                // 切歌读取起点后 / 无有效进度时的常规帧：歌词进度只由应用内
                // 墙钟推进，播放器周期快照（占位 0、冻结值、残留会话位置、
                // 整秒滞后值）一律忽略。
                trackSelfTimed -> {
                    branch = "trackAppTimed"
                    if (newSpeed != MusicProgress.speed) {
                        // 倍速变化：以应用自身当前估算值为新起点平移基准，进度连续
                        adoptBase(prevEstimate, now, newSpeed, now)
                    }
                }
                // 换源 / 外部 seek / 服务接入后首条快照：此时没有进行中的点击
                // 会话，才重新采用播放器基准（位置不可信时不采用）。
                // 位置不可信（-1 / 静止占位值）时换到残留会话会把高亮拉到第一行。
                positionCredible &&
                    (sourceSwitched || seekDetected || trackJustChanged) -> {
                    branch = "adoptSwitch"
                    driftStrikes = 0
                    MusicProgress.clearSeekPending()
                    if (baseTrustworthy) {
                        adoptBase(state.position, updateTime!!, newSpeed, now)
                    } else {
                        adoptBase(state.position, now, newSpeed, now)
                    }
                }
                // 正常播放的周期快照：基准可信但仅允许前向吸附。播放器上报的
                // 快照常滞后于真实进度（内容采集早于时间戳/回调延迟），若每次
                // 都向后吸附，估算进度会被反复拉回，高亮在当前句与相邻句之间
                // 来回乱跳。滞后快照直接忽略，沿用本机时钟继续推算。
                baseTrustworthy -> {
                    if (candidate >= prevEstimate - BACKWARD_SNAP_GUARD_MS) {
                        branch = "snapAdopt"
                        driftStrikes = 0
                        adoptBase(state.position, updateTime!!, newSpeed, now)
                    } else if (newSpeed != MusicProgress.speed) {
                        branch = "speedShift"
                        // 倍速变化：以当前估算值为新起点平移基准，保证进度连续
                        adoptBase(prevEstimate, now, newSpeed, now)
                    } else {
                        // 快照落后于估算：个别滞后忽略；但连续多次落后（或落后
                        // 幅度过大）说明估算已超前漂移——此前只允许前向吸附，
                        // 正向抖动被不断累积，估算进度会越跑越快，最终超过
                        // 播放器真实进度（音乐未播完高亮却停在无歌词的位置）。
                        // 此时校正一次，对齐回播放器真实进度。
                        driftStrikes++
                        val behind = prevEstimate - candidate
                        if (snapshotFresh && positionCredible &&
                            (driftStrikes >= DRIFT_CORRECT_STRIKES ||
                                behind > DRIFT_AHEAD_CORRECT_MS)
                        ) {
                            branch = "driftCorrect"
                            driftStrikes = 0
                            adoptBase(state.position, updateTime!!, newSpeed, now)
                        } else {
                            branch = "driftIgnore"
                        }
                    }
                }
                // 无基准/基准不可信但快照本身有更新：采用快照+本机时刻（同样仅前向）
                updateTime == null ||
                    kotlin.math.abs(snapDelta) > SNAPSHOT_CHANGE_THRESHOLD_MS -> {
                    if (state.position >= 0L &&
                        state.position >= prevEstimate - BACKWARD_SNAP_GUARD_MS
                    ) {
                        branch = "rawAdopt"
                        driftStrikes = 0
                        adoptBase(state.position, now, newSpeed, now)
                    } else if (positionCredible && (driftStrikes >= DRIFT_CORRECT_STRIKES ||
                        prevEstimate - state.position > DRIFT_AHEAD_CORRECT_MS)
                    ) {
                        // 与基准可信路径相同：连续落后说明估算超前漂移，校正对齐
                        branch = "rawDriftCorrect"
                        driftStrikes = 0
                        adoptBase(state.position, now, newSpeed, now)
                    } else {
                        branch = "rawDriftIgnore"
                        driftStrikes++
                    }
                }
                // 倍速变化但进度快照未变：以当前估算值为新起点平移基准
                newSpeed != MusicProgress.speed -> {
                    branch = "speedTick"
                    adoptBase(prevEstimate, now, newSpeed, now)
                }
                // 基准不可信且快照未变：什么都不做，沿用上次的本机时钟基准继续推算
            }
            // 恢复后一次性重锚：放在 when 之后，才能覆盖状态切换分支刚写下的
            // 基准；且必须让位给切歌 / 换源 / 用户跳转这些更权威的事件。
            // 判据不用 snapshotStalled（慢上报会被误判成冻结快照），只要求
            // 时间戳比上一帧前进 + 快照新鲜 + 正在播放，见 resyncFromSnapshot。
            if (resyncPending && playingNow && !explicitExternal &&
                !trackJustChanged && !sourceSwitched && !trackStartHold
            ) {
                if (now - resyncIssuedAtMs > RESYNC_WINDOW_MS) {
                    // 一直没有新上报：放弃，维持内部计时（最坏与改造前一致）
                    cancelPendingResync("resync window expired")
                    LogCapture.w(
                        "Progress",
                        "resync resume timeout held=${now - resyncIssuedAtMs}ms " +
                            "stamp=${state.lastPositionUpdateTime} " +
                            "lastStamp=$lastSeenStateStampMs"
                    )
                } else if (resyncFromSnapshot(
                        RESYNC_REASON_RESUME,
                        minStampMs = lastSeenStateStampMs
                    )
                ) {
                    branch = "resumeResync"
                }
            }
            // 切歌双边对齐的二次微调（毫秒不可信）：切歌对齐后的帧里
            // `est − 外推raw` 落地前恒为负（整秒 floor）、落地后恒为正（残差
            // Δ）。观测到正 Δ 后等「偏移 ×2」的宽限再执行一键对齐，把首次
            // 微调 seekTo 的落地延迟也清零。让位所有权威事件与点击落地观测
            //（点击期间不观测，见 seekWithFallback 的取消）。
            if (trackLandingWatchBaseMs >= 0L && msUntrusted && playingNow &&
                !playStateChanged && !explicitExternal && !secondSyncEvent &&
                !trackJustChanged && !sourceSwitched && positionCredible &&
                snapshotFresh && seekLandingIssuedAtMs == 0L
            ) {
                if (now - trackLandingArmedAtMs > TRACK_LANDING_WATCH_WINDOW_MS) {
                    LogCapture.w(
                        "Progress",
                        "track landing watch timeout base=$trackLandingWatchBaseMs " +
                            "raw=${state.position}"
                    )
                    disarmTrackLanding("window expired")
                } else if (trackLandingExecuteAtMs <= 0L) {
                    // 观测阶段：落地后的帧才出现正偏移（外推到当前再比）
                    val extrap = state.position +
                        (((now - (updateTime ?: now)) * newSpeed)).toLong()
                    val delta = prevEstimate - extrap
                    if (delta >= TRACK_LANDING_MIN_DELTA_MS) {
                        trackLandingDeltaMs = delta
                        trackLandingExecuteAtMs = now + 2L * delta
                        LogCapture.d(
                            "Progress",
                            "track landing delta observed delta=${delta}ms " +
                                "base=$trackLandingWatchBaseMs executeIn=${2L * delta}ms " +
                                "raw=${state.position} sessions=[${sessionDiag(now)}]"
                        )
                    }
                } else if (now >= trackLandingExecuteAtMs) {
                    val base = trackLandingWatchBaseMs
                    val delta = trackLandingDeltaMs
                    val beforeAlign = prevEstimate
                    disarmTrackLanding("aligned")
                    // 二次微调绝不能调 resyncFromSnapshot：它按播放器「当前」
                    // raw 取整秒，而首次对齐的 seekTo 早已落地、raw 已远高于
                    // 起始整秒（实测 raw=1360 / 起点=1000），取整秒会把这段已
                    // 播放的 360ms 零头全丢掉，还再发一次同目标 seekTo（Telegram
                    // 直接忽略）→ est 被拉回、播放器照常走，反而制造 +60~363ms
                    // 的反向偏移。观测到的 delta 才是首次对齐后 est 相对播放器
                    // 的真实领先（实测仅 6~16ms）：把它从 est 里扣掉即可，播放
                    // 器本就在正确轨道，无需也不应再发 seek。
                    val corrected = (beforeAlign - delta).coerceAtLeast(0L)
                    adoptBase(corrected, now, newSpeed, now)
                    branch = "trackLanding"
                    LogCapture.i(
                        "Progress",
                        "track landing aligned=true base=$base delta=${delta}ms " +
                            "raw=${state.position} prevEst=$beforeAlign corrected=$corrected " +
                            "moved=${beforeAlign - corrected}ms sessions=[${sessionDiag(now)}]"
                    )
                }
            }
            // 点击跳转（毫秒不可信）的落地观测：让位给切歌 / 换源 / 播放器内
            // 手动跳转 / 播放状态切换这些更权威的事件（它们有自己的对表机制），
            // 只在纯周期帧里寻找「位置跳进点击目标邻域」的落地特征。落地即按
            // 一键对齐逻辑重锚并结束会话；超窗放弃，维持自计时（不比修复前差）。
            if (seekLandingIssuedAtMs > 0L && msUntrusted && playingNow &&
                !playStateChanged && !explicitExternal && !secondSyncEvent &&
                !trackJustChanged && !sourceSwitched && positionCredible
            ) {
                if (now - seekLandingIssuedAtMs > SEEK_LANDING_WINDOW_MS) {
                    disarmSeekLanding("window expired")
                    LogCapture.w(
                        "Progress",
                        "seek landing watch timeout target=$seekLandingBaseMs " +
                            "raw=${state.position} prev=${prevEstimate}"
                    )
                } else {
                    // 落地 = 播放器位置真正进入目标整秒的 1 秒邻域。不能只看
                    // 「相对点击时位置有跳变」或「点击时就在邻域」就放行：seek
                    // 刚发出时播放器可能仍停在原地（raw 尚未更新），此时若按
                    // 旧位置取整秒重锚，会把应用锁到错误的整秒（实测 −2 秒）。
                    // 原地点击时 raw 本就在邻域内，nearLanding 自然成立，无需
                    // 额外的 nearAtLaunch 免责分支。
                    val nearLanding = kotlin.math.abs(state.position - seekLandingBaseMs) <=
                        SEEK_LANDING_NEIGHBOR_MS
                    // 下列两项仅用于诊断日志，不再作为放行条件。
                    val jumped = seekLandingPrevRawMs >= 0L &&
                        kotlin.math.abs(state.position - seekLandingPrevRawMs) >
                            SEEK_DISCONTINUITY_MS
                    if (nearLanding) {
                        val landingBase = seekLandingBaseMs
                        val landedByJump = jumped
                        val landedAtLaunch = seekLandingNearAtLaunch
                        val beforeAlign = prevEstimate
                        disarmSeekLanding("landing observed")
                        // 落地已确认：点击的未生效补发 / 暂停锁补发登记一并作废
                        pendingSeekRetryTargetMs = -1L
                        pendingSeekRetryUntil = 0L
                        pendingSeekRetryIssued = false
                        var aligned = resyncFromSnapshot(
                            RESYNC_REASON_SEEK_LANDING,
                            maxSnapshotAgeMs = RESYNC_MANUAL_MAX_SNAPSHOT_AGE_MS
                        )
                        if (!aligned) {
                            // 快照时间戳不可用（罕见）：恢复守卫，下一帧再试，
                            // 窗口内落地帧通常不止一条
                            seekLandingBaseMs = landingBase
                            seekLandingIssuedAtMs = now
                            seekLandingPrevRawMs = state.position
                            seekLandingNearAtLaunch = true
                        } else {
                            branch = "seekLanding"
                        }
                        LogCapture.i(
                            "Progress",
                            "seek landing aligned=$aligned base=$landingBase " +
                                "raw=${state.position} jumped=$landedByJump " +
                                "nearAtLaunch=$landedAtLaunch prevEst=$beforeAlign " +
                                "delta=${beforeAlign - MusicProgress.currentPositionMs()}ms " +
                                "sessions=[${sessionDiag(now)}]"
                        )
                    }
                }
            }
            // 诊断日志：仅调试模式下输出，避免正式使用时的持续日志开销
            if (autoMatchPrefs.debugMode) {
                Log.d(
                    TAG,
                    "tick br=$branch sid=${System.identityHashCode(controller)} ps=${state.state} " +
                        "pos=${state.position} dur=$durationMs prev=$prevEstimate cur=${MusicProgress.currentPositionMs()} " +
                        "stall=$snapshotStalled cred=$positionCredible bTrust=$baseTrustworthy " +
                        "snapD=$snapDelta wallD=$wallDelta track=${title?.take(8)}"
                )
            }
            }
            // 切歌首条有效进度已读取（trackStartRead 分支），或换源帧已对表：
            // 本首歌从此只信应用内计时，周期快照一律不再写基准。没有可读取
            // 起点的场景已在宽限窗结束时单独置位，这里不会重复处理；放在
            // 暂停锁 if/else 之外，保证暂停锁帧也不会漏掉状态切换。
            if (trackJustChanged && trackStartPendingReal &&
                !trackStartHold && !trackStartSnapshotSuppressed
            ) {
                trackSelfTimed = true
            }
            lastReportedPos = state.position
            lastSeenStateStampMs = state.lastPositionUpdateTime
            lastSeenPlayState = state.state
            lastSnapshotWallMs = now
        }
        sourceSwitched = false
        val nowPlaying = state?.state == PlaybackState.STATE_PLAYING
        if (nowPlaying != MusicProgress.isPlaying) {
            // 来源归因：2 秒内有同向的 app toggle 指令 → 这次翻转是 app 造成的；
            // 否则是播放器自身 / 媒体按钮 / 通知 / 第三方应用触发。
            val nowMs = SystemClock.elapsedRealtime()
            val expectedAction = if (nowPlaying) "play" else "pause"
            val byApp = lastPlayToggleAtMs > 0L &&
                nowMs - lastPlayToggleAtMs <= 2_000L &&
                lastPlayToggleAction == expectedAction
            val origin = if (byApp) "app:${lastPlayToggleSource}" else "external"
            LogCapture.i(
                "Play",
                "${if (nowPlaying) "播放" else "暂停"} pos=${state?.position} " +
                    "title=${title?.take(12)} origin=$origin " +
                    "lastToggle=${lastPlayToggleAction}@${lastPlayToggleSource}/" +
                    "${if (lastPlayToggleAtMs > 0L) nowMs - lastPlayToggleAtMs else -1}ms"
            )
        }
        // 暂停态 seek 锁的解除：仅在恢复播放后处理（暂停态快照不可靠，不能据此解除）。
        // - 播放器位置已接近目标（seek 生效）：解除锁，由真实快照接管
        // - 补发重试已发出：解除锁，交由真实位置（重试生效后自然对齐目标）
        // - 恢复播放后的释放截止已到：强制解除并补发一次，避免锁永久驻留
        if (nowPlaying && MusicProgress.pausedSeekTargetMs >= 0L) {
            val target = MusicProgress.pausedSeekTargetMs
            val curPos = state?.position ?: -1L
            val nearTarget = curPos >= 0L &&
                kotlin.math.abs(curPos - target) <= PAUSED_SEEK_RELEASE_TOLERANCE_MS
            val deadlineReached = pausedSeekResumeDeadline > 0L && now >= pausedSeekResumeDeadline
            if (nearTarget || pendingSeekRetryIssued || deadlineReached) {
                // 到期仍未接近且尚未补发：在解锁前对进度源再跳一次，避免解锁后
                // 立刻显示播放器未响应的旧位置（音画分叉）
                if (deadlineReached && !nearTarget && !pendingSeekRetryIssued &&
                    !MusicProgress.isSeekCatchupActive()
                ) {
                    pendingSeekRetryIssued = true
                    // 由本次补发接管：恢复后的待重锚不得再按旧快照动基准
                    cancelPendingResync("paused seek lock retry")
                    // 补发后重新进入自计时保护窗口，避免解锁后偏移快照把高亮拉回旧位置
                    localSeekTargetMs = target
                    localSeekIssuedAt = now
                    localSeekPendingUntil = now + SEEK_SELF_TIMED_WINDOW_MS
                    MusicProgress.seekUiTargetMs = target
                    MusicProgress.seekUiTargetUntil = now + SEEK_PIN_WINDOW_MS
                    MusicProgress.markSeekPending(
                        target, localSeekLineMs, now + SEEK_UI_PENDING_MS
                    )
                    LogCapture.w(
                        "Seek",
                        "pausedSeekLock timeout re-seek target=$target snap=$curPos"
                    )
                    runCatching { activeController?.transportControls?.seekTo(target) }
                        .onFailure { Log.w(TAG, "paused seek timeout re-seek failed: ${it.message}") }
                    scheduleSeekRecheck(target, now)
                }
                LogCapture.d(
                    "Seek",
                    "pausedSeekLock release target=$target pos=$curPos retry=$pendingSeekRetryIssued " +
                        "timeout=$deadlineReached"
                )
                MusicProgress.pausedSeekTargetMs = -1L
                pausedSeekResumeDeadline = 0L
            }
        }
        MusicProgress.isPlaying = nowPlaying
        val metaDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val artistMeta = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val trackKey = title?.trim()?.takeUnless { it.isEmpty() }
            ?.let { "$it|${artistMeta?.trim().orEmpty()}" }
        MusicProgress.durationMs = if (metaDuration > 0L) {
            // 播放器自身提供了有效时长：记入缓存，兜底值作废
            if (trackKey != null) rememberDuration(trackKey, metaDuration)
            missingDurationFallbackMs = 0L
            metaDuration
        } else {
            // 元数据缺时长（网络波动，或部分播放器播放中不上报时长、仅暂停时
            // 上报）：依次回退到同曲缓存（此前暂停时上报过）、跨会话兜底值
            val cached = trackKey?.let { cachedDuration(it) } ?: 0L
            val effective = when {
                cached > 0L -> cached
                missingDurationFallbackMs > 0L -> {
                    // 跨会话补获的时长同样入缓存，后续直接命中
                    trackKey?.let { rememberDuration(it, missingDurationFallbackMs) }
                    missingDurationFallbackMs
                }
                else -> 0L
            }
            if (effective > 0L) missingDurationFallbackMs = 0L
            effective
        }
        MusicProgress.title = title
        MusicProgress.artist = artistMeta
        // 周期进度诊断：每 5 秒输出一次完整进度状态，便于区分"估算超前/被时长钳制"
        // 与"暂停锁驻留"导致的歌词停行。仅在日志抓取开启时落盘（LogCapture 内部判断）。
        if (state != null && now - lastProgressLogAt >= PROGRESS_LOG_INTERVAL_MS) {
            lastProgressLogAt = now
            val est = MusicProgress.currentPositionMs()
            val lyricLines = Session.lyricLines
            val lineIdx = lyricLines?.indexOfLast { it.time <= est } ?: -1
            val sessionDump = controllers.values.joinToString(" | ") { sc ->
                val sst = sc.playbackState
                val smd = sc.metadata
                val sfrozen = positionStuckMap[System.identityHashCode(sc)]?.let { stuck ->
                    if (stuck.pos == sst?.position && sst?.position != null && sst.position >= 0)
                        now - stuck.sinceMs else -1L
                } ?: -1L
                "sid=${System.identityHashCode(sc)} act=${activeController === sc} " +
                    "ps=${sst?.state} pos=${sst?.position} dur=${smd?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L} " +
                    "age=${if (sst != null && sst.lastPositionUpdateTime > 0) now - sst.lastPositionUpdateTime else -1} " +
                    "frz=$sfrozen t=${controllerTitle(sc)?.take(10) ?: "-"}"
            }
            LogCapture.d(
                "Progress",
                "br=$branch est=$est raw=${state.position} ps=${state.state} playing=$nowPlaying " +
                    "dur=${MusicProgress.durationMs} speed=${state.playbackSpeed} " +
                    "secGap=${est - wholeSecondBase(state.position)}ms " +
                    "delay=${PlaybackOffset.delayMs(autoMatchPrefs)}ms " +
                    "stampAge=${
                        if (state.lastPositionUpdateTime > 0)
                            now - state.lastPositionUpdateTime else -1
                    } lock=${MusicProgress.pausedSeekTargetMs} " +
                    "localSeek=${if (now < localSeekPendingUntil) localSeekTargetMs else -1} " +
                    "selfTimed=$trackSelfTimed trackWait=$trackChangedPending " +
                    "startPos(仅诊断)=$trackStartReadPosMs base=$lastAdoptBaseMs " +
                    "stale=${staleSessionIds.size} " +
                    "line=$lineIdx/${lyricLines?.size ?: 0} title=${title?.take(12)} " +
                    "sessions[$sessionDump]"
            )
        }
        // 诊断：长空档 / 亮屏后的首帧，一次性打印本帧走了哪个分支、快照年龄、
        // 墙钟与快照推进的差值，以及所有会话的位置，用于定位「开屏中断一下」。
        if (frameWake) {
            val rawNow = state?.position ?: -1L
            val rawDeltaMs = if (rawNow >= 0L && framePrevRawMs >= 0L)
                rawNow - framePrevRawMs else -1L
            val stamp = state?.lastPositionUpdateTime ?: 0L
            val frameSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
            val expectedRawDeltaMs = (frameElapsedGapMs * frameSpeed).toLong()
            LogCapture.i(
                "Frame",
                "wake elGap=${frameElapsedGapMs}ms upGap=${frameUptimeGapMs}ms " +
                    "wallGap=${frameWallGapMs}ms sleepDelta=${frameSleepDeltaMs}ms " +
                    "branch=$branch raw=$rawNow prevRaw=$framePrevRawMs " +
                    "rawDelta=${rawDeltaMs}ms expected=${expectedRawDeltaMs}ms " +
                    "snapDelta=${rawDeltaMs - expectedRawDeltaMs}ms " +
                    "est=${MusicProgress.currentPositionMs()} " +
                    "stamp=$stamp stampAge=${if (stamp > 0L) now - stamp else -1}ms " +
                    "ps=${state?.state} playing=$nowPlaying " +
                    "screen=${Diag.lastScreenEvent} sinceScreen=${Diag.sinceScreenEvent(now)}ms " +
                    "sinceScreenOn=${Diag.sinceScreenOn(now)}ms " +
                    "rebindSeq=${Diag.rebindSeq} sinceRebind=${Diag.sinceRebind(now)}ms " +
                    "sinceConnect=${Diag.sinceConnect(now)}ms " +
                    "srcSwitched=$frameSourceSwitched selfTimed=$trackSelfTimed " +
                    "trackWait=$trackChangedPending " +
                    "sessions=[${sessionDiag(now)}]"
            )
        }
        tryAutoMatch()
    }

    /** 采用新的进度基准。 */
    private fun adoptBase(positionMs: Long, baseTimeMs: Long, speed: Float, now: Long) {
        lastAdoptBaseMs = positionMs
        MusicProgress.positionMs = positionMs
        MusicProgress.stateTimeMs = baseTimeMs
        MusicProgress.snapshotElapsedMs = now
        MusicProgress.speed = speed
    }

    /**
     * 时长缺失兜底重取：网络波动等原因导致播放器元数据缺时长（MusicProgress
     * 时长为 0）时重新获取，由界面在"歌词跳到下一行"时刻调用（此时必然已
     * 在播放且需要时长参与钳制/匹配）。
     *
     * 两条路径：
     * 1. 重新扫描会话——系统侧的会话元数据可能已随网络恢复而更新，重新
     *    查询并触发一次 updateFromController 即可拿到新值；
     * 2. 扫描后仍缺失时，从同应用的其他会话（播放器重建过会话的残留）的
     *    元数据中补获时长——同应用会话描述的是同一首歌，时长可信。
     */
    fun forceRefreshMissingDuration() {
        if (MusicProgress.durationMs > 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMissingDurationRefreshAt < MISSING_DURATION_REFRESH_INTERVAL_MS) return
        lastMissingDurationRefreshAt = now
        // 路径 1：重新扫描（含 chooseActive → updateFromController 刷新元数据）
        runCatching { scanActiveNotifications() }
        if (MusicProgress.durationMs > 0L) return
        // 路径 2：仅限与当前进度源同应用的会话，避免拿到无关应用的时长
        val pkg = activeController?.packageName ?: return
        val candidate = controllers.values
            .filter { it.packageName == pkg }
            .firstNotNullOfOrNull { c ->
                runCatching {
                    c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                }.getOrNull()?.takeIf { it > 0L }
            }
        if (candidate != null) missingDurationFallbackMs = candidate
    }

    /** 取消尚未到期的自动匹配任务（切歌 / 断连 / 重新匹配 / 关闭自动匹配时调用）。 */
    private fun cancelPendingAutoMatch() {
        pendingAutoMatchRunnable?.let { uiHandler.removeCallbacks(it) }
        pendingAutoMatchRunnable = null
        pendingMatchKey = null
    }

    /**
     * 真实切歌（服务接入后的首个标题不算）时立即作废上一首的歌词与在途匹配任务。
     *
     * 自动匹配还有 400ms 防抖 + 后台文件扫描，若等结果写回才清歌词，主界面与
     * 桌面歌词在这段窗口内会继续滚动上一首的歌词（桌面歌词的长行横向滚动甚至
     * 会把新文本挂起到动画结束），表现为「切歌后还停在上一首歌词一会」。
     *
     * 只清共享的 Session 状态与显式选择保护；不改动 [Session.matchedKey]，
     * 由 [tryAutoMatch] 按新 key 走防抖与匹配，避免元数据抖动写错归属。
     * 列表循环同一首歌（key 未变）时保留歌词，只由 songChangeVersion 复位行号。
     */
    private fun invalidateLyricsOnTrackChange(metadata: MediaMetadata?) {
        if (!autoMatchPrefs.autoMatchLyrics) return
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val key = "$title|$artist"
        if (key == Session.matchedKey) return
        // 同一首歌的作者字段晚到会再次进入这里，避免重复清空与重复递增版本号
        if (title == lastInvalidatedTrackTitle) return
        lastInvalidatedTrackTitle = title
        cancelPendingAutoMatch()
        matchGeneration++
        LogCapture.i("Match", "切歌作废旧歌词：${Session.matchedKey ?: "-"} -> $key")
        Session.matchState = LyricMatchState.MATCHING
        Session.matchedFile = null
        Session.lyricLines = null
        // 显式选择只对上一首有效；持久化绑定仍由 LyricBindings 按歌曲保留
        Session.explicitLyricTitle = null
        Session.explicitLyricPath = null
        // 主界面据此立即清空歌词区，不等自动匹配结果
        Session.matchVersion++
    }

    /**
     * 歌曲切换时自动匹配应用歌词存储目录下的歌词文件。
     *
     * 放在通知监听服务中执行（常驻后台，不依赖主界面是否打开）：
     * 匹配结果写入 Session，桌面歌词与主界面都能立即感知。
     *
     * 带 400ms 防抖：切歌瞬间多播放器来源可能短暂抖动，防抖确保
     * 只有稳定的最终歌曲才会真正加载歌词文件，避免来回闪歌词。
     * 防抖只在「目标歌曲 key 变化」时重新排期，避免高频进度帧无限推迟。
     */
    private fun tryAutoMatch() {
        if (!autoMatchPrefs.autoMatchLyrics) {
            // 自动匹配被关掉：撤掉可能残留的排期与「正在匹配」状态
            cancelPendingAutoMatch()
            if (Session.matchState == LyricMatchState.MATCHING ||
                Session.matchState == LyricMatchState.FETCHING_ONLINE
            ) {
                Session.matchState = LyricMatchState.IDLE
            }
            return
        }
        val title = MusicProgress.title?.trim().takeUnless { it.isNullOrEmpty() } ?: run {
            if (Session.matchState == LyricMatchState.MATCHING ||
                Session.matchState == LyricMatchState.FETCHING_ONLINE
            ) {
                Session.matchState = LyricMatchState.IDLE
            }
            return
        }
        val artist = MusicProgress.artist?.trim().orEmpty()
        val key = "$title|$artist"
        val scheduledKey = pendingMatchKey
        if (key == Session.matchedKey) {
            // 防抖期间又切回上一首：撤掉为中间歌曲排期的任务。若其歌词已在
            // 切歌时作废，必须立即补一次匹配，否则会永远停在「正在匹配歌词」。
            if (scheduledKey != null && scheduledKey != key) {
                cancelPendingAutoMatch()
                if (Session.matchedFile == null && Session.lyricLines.isNullOrEmpty()) {
                    doAutoMatchNow()
                } else {
                    Session.matchState = LyricMatchState.MATCHED
                }
            }
            return
        }

        // 检测到新歌曲：先切到「正在匹配」，防抖期间主界面与桌面歌词都显示状态提示
        Session.matchState = LyricMatchState.MATCHING

        val previousKey = Session.matchedKey
        // 首次加载跳过防抖：matchedKey 为空时只有一首歌，不存在来源抖动
        if (previousKey == null) {
            cancelPendingAutoMatch()
            doAutoMatchNow()
            return
        }
        // 目标歌曲没变就不重复排期：否则每帧重排 400ms 会让新歌歌词迟迟出不来
        if (scheduledKey == key) return
        cancelPendingAutoMatch()
        pendingMatchKey = key
        val runnable = Runnable { doAutoMatchNow() }
        pendingAutoMatchRunnable = runnable
        uiHandler.postDelayed(runnable, autoMatchDebounceMs)
    }

    /**
     * 防抖到期后执行实际的歌词匹配。
     * 执行时重新读取 MusicProgress 的最新标题/艺术家——防抖期间如果
     * 来源又切了，用最新歌曲而不是 400ms 前那首。
     *
     * 打分、文件读取与解析放在单线程后台执行，主线程只提交结果；
     * 每次请求带一个递增的 generation，切歌 / 阈值变化后旧任务的结果直接丢弃，
     * 防止慢的旧歌结果覆盖新歌。
     */
    private fun doAutoMatchNow() {
        // 本次排期已消费
        pendingAutoMatchRunnable = null
        pendingMatchKey = null
        if (!autoMatchPrefs.autoMatchLyrics) {
            if (Session.matchState == LyricMatchState.MATCHING ||
                Session.matchState == LyricMatchState.FETCHING_ONLINE
            ) {
                Session.matchState = LyricMatchState.IDLE
            }
            return
        }
        val title = MusicProgress.title?.trim().takeUnless { it.isNullOrEmpty() } ?: run {
            matchGeneration++
            Session.matchState = LyricMatchState.IDLE
            return
        }
        val artist = MusicProgress.artist?.trim().orEmpty()
        val key = "$title|$artist"
        // 防抖期间可能又切回来了，key 没变就不重复加载；
        // 同时恢复该歌曲上一次的匹配结论，避免界面一直停在「正在匹配歌词」。
        // 例外：切歌时歌词已被作废（matchedFile/lyricLines 都为空），此时不能
        // 恢复成「无匹配歌词」，必须重新匹配一次把歌词找回来。
        val lyricsInvalidated = Session.matchedFile == null && Session.lyricLines.isNullOrEmpty()
        if (key == Session.matchedKey && !lyricsInvalidated) {
            Session.matchState = LyricMatchState.MATCHED
            return
        }
        Session.matchState = LyricMatchState.MATCHING
        Session.matchedKey = key
        val generation = ++matchGeneration
        val minScore = autoMatchPrefs.matchSimilarity / 100.0
        val startedAt = SystemClock.elapsedRealtime()
        MATCH_EXECUTOR.execute {
            if (generation != matchGeneration) return@execute
            val dir = File(filesDir, "lyrics")
            // 优先使用用户显式绑定过的歌词文件（优先级最高），绑定失效或无绑定时回退到相似度匹配。
            // 作者字段晚到 / 多会话来源抖动会让 key 从「标题|」变成「标题|作者」，
            // 此时补查一次仅标题的绑定，避免把用户刚选的文件丢掉。
            val boundName = LyricBindings.get(this@MusicNotificationListenerService, title, artist)
                ?: if (artist.isNotEmpty()) {
                    LyricBindings.get(this@MusicNotificationListenerService, title, "")
                } else null
            val bound = boundName?.let { LyricBindings.resolveFile(this@MusicNotificationListenerService, it) }
            // 仍查不到绑定时，认进程内「用户为当前标题显式选中的歌词」：
            // 标题未变就绝不因为相似度更高而自动切回旧文件。
            val explicit = Session.explicitLyricPath
                ?.takeIf { Session.explicitLyricTitle == title }
                ?.let { File(it).takeIf { f -> f.exists() } }
            val localBest = bound ?: explicit ?: LyricMatcher.bestMatch(dir, title, artist, minScore)
            // 独立的在线歌词缓存目录：命中就直接读本地文件，绝不发起网络请求。
            // 它与 lyrics/ 完全分离，不参与 LyricMatcher 的目录快照与相似度打分。
            val onlineCached = (
                OnlineLyricCache.lookup(this@MusicNotificationListenerService, title, artist)
                    as? OnlineLyricCache.Lookup.Hit
                )?.file
            val best = localBest ?: onlineCached
            var failure: Throwable? = null
            val lines = best?.let { f ->
                // 内部目录中的文件可能来自原始字节拷贝（GBK 等编码），
                // 必须用编码探测读取，直接按 UTF-8 读会乱码
                runCatching { LyricsParser.parse(DocumentIo.decode(f.readBytes()).text) }
                    .onFailure { failure = it }
                    .getOrNull()
            }
            val costMs = SystemClock.elapsedRealtime() - startedAt
            uiHandler.post {
                if (generation != matchGeneration) return@post
                applyMatchResult(key, best, lines, failure, costMs, title, artist)
            }
        }
    }

    /** 主线程提交自动匹配结果：只有最新一次任务允许写回 Session。 */
    private fun applyMatchResult(
        key: String,
        best: File?,
        lines: List<LyricLine>?,
        failure: Throwable?,
        costMs: Long,
        title: String,
        artist: String,
    ) {
        if (best != null && lines != null) {
            Session.lyricLines = lines
            Session.matchedFile = best
            Session.matchState = LyricMatchState.MATCHED
            Session.matchVersion++
            // 持久化最后显示的歌词文件：进程被快捷入口冷启动拉起时，
            // 桌面歌词服务据此立即从磁盘恢复，不必等监听重连后重新匹配
            autoMatchPrefs.lastLyricFile = best.absolutePath
            LogCapture.i("Match", "匹配歌词：$key -> ${best.name}（${costMs}ms）")
            return
        }
        if (best == null) {
            LogCapture.w("Match", "未匹配到歌词：$key（${costMs}ms）")
        } else {
            LogCapture.e("Match", "解析歌词失败：${best.name}，${failure?.message}（${costMs}ms）")
        }
        // 没有匹配歌词 / 解析失败时必须清掉上一首歌的歌词行，
        // 否则主界面和桌面歌词会继续显示旧歌词，而不是「无匹配歌词」提示
        Session.matchedFile = null
        Session.lyricLines = null
        // 本地（含独立在线缓存）都没有：按开关决定是否联网查一次。
        // 发起后状态切到「无匹配歌词，正在在线获取歌词」，由结果回调收尾。
        if (startOnlineFetchIfNeeded(key, title, artist)) return
        Session.matchState = LyricMatchState.NO_MATCH
        Session.matchVersion++
    }

    /**
     * 无匹配歌词时自动执行一次在线搜索。
     *
     * 三重去重保证不会对公开歌词接口造成重复压力：
     * 1. [OnlineLyricCache] 的独立缓存目录已有歌词文件 → 前面已直接命中，根本不会走到这里；
     * 2. 索引里记着「已查过但无结果」的负缓存 → 不再联网；
     * 3. 进程内 in-flight / 已请求集合 → 作者字段迟到导致的 key 抖动不会重复请求。
     *
     * 结果无论当前歌曲是否已切换都会写入独立缓存目录，之后切回这首歌可直接命中本地。
     */
    private fun startOnlineFetchIfNeeded(key: String, title: String, artist: String): Boolean {
        val prefs = AppearancePrefs(this)
        if (!prefs.autoFetchOnNoMatch) return false
        val normalizedTitle = title.trim().lowercase(java.util.Locale.ROOT)
        if (key in onlineFetchInFlight || normalizedTitle in onlineFetchAttemptedTitles) return false
        if (OnlineLyricCache.lookup(this, title, artist) is OnlineLyricCache.Lookup.Empty) return false

        onlineFetchInFlight.add(key)
        onlineFetchAttemptedTitles.add(normalizedTitle)
        Session.matchState = LyricMatchState.FETCHING_ONLINE
        Session.matchVersion++

        val generation = matchGeneration
        val songVersion = Session.songChangeVersion
        val sources = prefs.enabledSources
        val appleToken = prefs.appleMusicToken
        val preferWordTiming = prefs.preferWordTimedOnline
        val durationMs = MusicProgress.durationMs
        val preferredSource = OnlineLyrics.preferredSourceFor(MusicProgress.packageName)
        LogCapture.i("OnlineFetch", "无匹配歌词，开始在线获取：$key")

        ONLINE_FETCH_EXECUTOR.execute {
            var searchError: Throwable? = null
            val found = runCatching {
                OnlineLyrics.search(
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    enabledSources = sources,
                    appleToken = appleToken,
                    preferredSource = preferredSource,
                )
            }.onFailure { searchError = it }.getOrDefault(emptyList())
            if (searchError != null) {
                LogCapture.w("OnlineFetch", "在线请求失败：${searchError?.message}")
            }
            val chosen = pickOnlineCandidate(found, preferWordTiming)
            // 无论歌曲是否已切换都落盘：切走后切回来时无需再请求接口。
            // 另外从缓存复制一份到 lyrics/ 歌词存储（沿用旧版「使用此歌词」的
            // 文件名规则，不带缓存哈希后缀），应用与绑定都指向这份副本。
            val file = chosen?.let {
                val cacheFile = OnlineLyricCache.save(
                    this@MusicNotificationListenerService,
                    title, artist, it, OnlineLyrics.mergedLyric(it)
                )
                if (cacheFile == null) null
                else OnlineLyricCache.copyToLyricsStore(
                    this@MusicNotificationListenerService, cacheFile, it
                ) ?: cacheFile
            }
            // 只有「请求成功但确实没有结果」才写负缓存；网络异常不写，
            // 下次启动仍可重试，避免一次断网把这首歌锁死三天
            if (found.isEmpty() && searchError == null) {
                OnlineLyricCache.markEmpty(this@MusicNotificationListenerService, title, artist)
            }
            val parsed = file?.let { f ->
                runCatching { LyricsParser.parse(DocumentIo.decode(f.readBytes()).text) }
                    .onFailure { e -> LogCapture.e("OnlineFetch", "解析在线歌词失败：${e.message}") }
                    .getOrNull()
            }
            uiHandler.post {
                onlineFetchInFlight.remove(key)
                // 期间切歌、重新匹配、用户手动选词或关掉开关：结果仍已缓存，
                // 但不再写回界面状态，避免覆盖更新的状态
                if (generation != matchGeneration ||
                    songVersion != Session.songChangeVersion ||
                    key != Session.matchedKey ||
                    !autoMatchPrefs.autoMatchLyrics ||
                    !AppearancePrefs(this@MusicNotificationListenerService).autoFetchOnNoMatch ||
                    Session.matchState != LyricMatchState.FETCHING_ONLINE
                ) return@post
                if (file != null && !parsed.isNullOrEmpty()) {
                    Session.lyricLines = parsed
                    Session.matchedFile = file
                    Session.matchState = LyricMatchState.MATCHED
                    Session.matchVersion++
                    autoMatchPrefs.lastLyricFile = file.absolutePath
                    LyricBindings.putForce(
                        this@MusicNotificationListenerService,
                        title, artist,
                        LyricBindings.bindingValue(this@MusicNotificationListenerService, file)
                    )
                    LogCapture.i("OnlineFetch", "在线歌词已应用：${file.name}")
                } else {
                    Session.matchState = LyricMatchState.NO_MATCH
                    Session.matchVersion++
                    LogCapture.w("OnlineFetch", "在线未找到歌词：$key")
                }
            }
        }
        return true
    }

    /** 按「优先逐字数据」开关挑选自动应用的版本；搜索结果已按综合得分降序排好。 */
    private fun pickOnlineCandidate(
        found: List<OnlineLyrics.Candidate>,
        preferWordTiming: Boolean,
    ): OnlineLyrics.Candidate? {
        if (found.isEmpty()) return null
        if (!preferWordTiming) return found.first()
        return found.firstOrNull { it.hasWordTiming } ?: found.first()
    }

    companion object {
        private const val TAG = "MusicNotifListener"

        /**
         * 自动匹配后台执行器：单线程执行打分、文件读取与歌词解析，
         * 避免多文件目录在主线程造成掉帧，也避免并发解析占满 CPU。
         */
        private val MATCH_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "lyric-auto-match").apply { isDaemon = true }
        }

        /** 自动在线获取专用线程：与本地匹配互不阻塞，串行执行避免并发打多个接口。 */
        private val ONLINE_FETCH_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "online-lyrics-auto").apply { isDaemon = true }
        }

        /** 进程内已发起 / 正在发起在线获取的歌曲 key，防止同一首歌重复请求。 */
        private val onlineFetchInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

        /** 按标题去重：作者字段晚到导致 key 变化时不再补发一次请求。 */
        private val onlineFetchAttemptedTitles = java.util.Collections.synchronizedSet(HashSet<String>())

        /** 用户重新开启「无匹配时自动在线搜索」时清空进程内去重记录。 */
        fun resetOnlineFetchHistory() {
            onlineFetchInFlight.clear()
            onlineFetchAttemptedTitles.clear()
        }

        /** 自动匹配任务代号：仅最新一次任务的结果允许写回 Session。 */
        @Volatile private var matchGeneration = 0L

        /**
         * 「监听状态」定向控制的无响应复核窗口（毫秒）：指令发出后等待播放器
         * 回调状态/元数据变化，超时仍无变化则判定该会话静默丢弃了指令。
         */
        private const val ISOLATED_RESPONSE_CHECK_MS = 1_200L

        /** 暂停态 seek 锁在恢复播放后的释放宽限（毫秒）。 */
        private const val PAUSED_SEEK_RESUME_GRACE_MS = 2_500L

        /** 暂停态 seek 锁释放的位置容差：部分播放器（Telegram）以整秒上报，需放宽。 */
        private const val PAUSED_SEEK_RELEASE_TOLERANCE_MS = 1_200L

        /** 进度诊断日志输出间隔（毫秒）。 */
        private const val PROGRESS_LOG_INTERVAL_MS = 5_000L

        /** 诊断：帧间隔超过此值即视为「长空档 / 唤醒后的首帧」。 */
        private const val FRAME_WAKE_GAP_MS = 2_000L

        /** 诊断：elapsedRealtime 与 uptimeMillis 差值增量超过此值视为发生过 deep sleep。 */
        private const val FRAME_WAKE_SLEEP_MS = 500L

        /** 诊断：1 秒扫描空档超过此值时输出一条（正常约 1000ms）。 */
        private const val SCAN_GAP_DIAG_MS = 3_000L

        /** 两次恢复尝试的最小间隔（毫秒）。 */
        private const val RECOVER_THROTTLE_MS = 10_000L

        /** 连续落后快照达到该次数判定估算超前漂移，校正对齐真实进度。 */
        private const val DRIFT_CORRECT_STRIKES = 4

        /** 快照落后估算超过该幅度时立即校正（覆盖极端漂移）。 */
        private const val DRIFT_AHEAD_CORRECT_MS = 5_000L

        @Volatile private var instance: MusicNotificationListenerService? = null

        /**
         * 「监听状态」页读取的媒体会话快照。由服务在主线程发布不可变列表，
         * 界面只读，避免跨线程访问 MediaController 集合。
         */
        @Volatile private var publishedSnapshots: List<MediaSessionSnapshot> = emptyList()

        fun sessionSnapshots(): List<MediaSessionSnapshot> = publishedSnapshots

        /** 应用内部计时快照；服务未连接时给出占位提示而不是崩溃。 */
        fun internalClockSnapshot(): InternalClockSnapshot =
            instance?.buildInternalClockSnapshot() ?: InternalClockSnapshot.empty()

        /**
         * 当前活动会话的专辑封面（主页模糊背景 / 底部控制条共用）。
         *
         * 返回的是 [AlbumArtCache] 发布的**稳定副本**：同一张封面（即使播放器
         * 每次用新的 Bitmap 对象重发）始终返回同一个对象，[albumArtVersion]
         * 也不会抖动；切歌、封面像素变化、封面迟到或消失时才换副本并递增版本，
         * UI 据此立即重绘，避免沿用上一首的模糊图。
         */
        fun currentAlbumArt(): Bitmap? {
            val svc = instance
            val md = if (svc == null) null else runCatching { svc.activeController?.metadata }.getOrNull()
            val key = svc?.albumArtKeyOf(md) ?: "<no-session>"
            val raw = if (svc == null) null else svc.albumArtOf(md)
            return AlbumArtCache.publish(key, raw)
        }

        /** 封面身份版本号；变化即代表需要重新贴封面（含回退占位图）。 */
        fun albumArtVersion(): Int = AlbumArtCache.version()

        /**
         * 封面 URI 解码缓存 + 已发布封面的身份归一化。
         *
         * 除按 URI 缓存解码结果外，这里还负责把 MediaSession 下发的位图收敛成
         * 一份由本进程持有的「稳定副本」，使 [currentAlbumArt] 在同一张封面的
         * 生命周期内始终返回同一个对象、同一个 [version]。判重依据是封面像素
         * （[Bitmap.sameAs]），而非 Bitmap 对象地址 —— 与参考实现
         * lyrics-android 的 prepareHomeAlbumBackground 思路一致。
         */
        private object AlbumArtCache {
            @Volatile private var versionValue = 0
            private val cached = LinkedHashMap<String, Bitmap?>()
            private val loading = HashSet<String>()
            private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "album-art-loader").apply { isDaemon = true }
            }

            /** 已发布的稳定封面副本；不主动 recycle，交给 GC（UI 可能仍在引用）。 */
            @Volatile private var published: Bitmap? = null
            private var publishedKey: String? = null
            /** 上次传入的原始位图，用于跳过重复的全像素比较。 */
            private var lastSource: Bitmap? = null
            private var lastCheckedAtMs = 0L

            fun version(): Int = versionValue

            @Synchronized
            private fun bump() { versionValue++ }

            /**
             * 归一化并返回当前应展示的封面。
             *
             * - 曲目身份相同且像素相同 → 复用已发布副本，版本不变；播放器「换对象
             *   重发同一封面」不会再让 UI 每帧重算。
             * - 曲目身份变化 / 像素变化（含播放器原地改写同一个 Bitmap）/ 封面
             *   出现或消失 → 生成新副本并递增版本，UI 立即重绘。
             *
             * 持有副本而非原始位图还有一层必要性：播放器原地改写像素时副本不受
             * 影响，[Bitmap.sameAs] 才能识别出变化（参考实现
             * HomeAlbumBackgroundTest#changedArtworkReplacesThePreparedBitmap...）。
             */
            @Synchronized
            fun publish(key: String?, raw: Bitmap?): Bitmap? {
                val source = raw?.takeIf { !it.isRecycled }
                val current = published?.takeIf { !it.isRecycled }
                if (source == null) {
                    lastSource = null
                    if (current == null && key == publishedKey) return null
                    published = null
                    publishedKey = key
                    bump()
                    return null
                }
                val now = SystemClock.elapsedRealtime()
                // 同一首歌、同一个原始位图对象：250ms 内直接复用，避免每帧做一次
                // 全像素比较；超过 250ms 仍要比较，以捕捉播放器原地改写位图。
                if (current != null && key == publishedKey && source === lastSource &&
                    now - lastCheckedAtMs < 250L
                ) return current
                lastSource = source
                lastCheckedAtMs = now
                if (current != null && key == publishedKey &&
                    runCatching { current.sameAs(source) }.getOrDefault(false)
                ) return current

                val copy = softwareCopy(source)
                if (copy == null) {
                    // 极少数 ROM 上无法复制位图：仍以曲目身份为准保持版本稳定，
                    // 返回原始位图，由 UI 侧的版本号判定负责刷新，杜绝活锁。
                    if (current != null && key == publishedKey) return current
                    published = null
                    lastSource = null
                    publishedKey = key
                    bump()
                    return source
                }
                published = copy
                publishedKey = key
                bump()
                return copy
            }

            /** 生成软件配置的独立副本；兼容 HARDWARE 位图与部分 ROM 的 copy 失败。 */
            private fun softwareCopy(src: Bitmap): Bitmap? {
                runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }
                    .getOrNull()?.takeIf { !it.isRecycled }?.let { return it }
                return runCatching {
                    Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                        .also { Canvas(it).drawBitmap(src, 0f, 0f, null) }
                }.getOrNull()?.takeIf { !it.isRecycled }
            }

            fun get(uri: String, resolver: ContentResolver): Bitmap? {
                synchronized(cached) {
                    if (cached.containsKey(uri)) return cached[uri]?.takeIf { !it.isRecycled }
                    if (!loading.add(uri)) return null
                }
                io.execute {
                    val bitmap = decode(uri, resolver)
                    synchronized(cached) {
                        loading.remove(uri)
                        if (cached.size >= 12) cached.keys.firstOrNull()?.let { cached.remove(it) }
                        cached[uri] = bitmap
                    }
                    bump()
                }
                return null
            }

            private fun decode(uri: String, resolver: ContentResolver): Bitmap? = runCatching {
                val parsed = Uri.parse(uri)
                val scheme = parsed.scheme?.lowercase()
                if (scheme != ContentResolver.SCHEME_CONTENT &&
                    scheme != ContentResolver.SCHEME_FILE
                ) {
                    null
                } else {
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    resolver.openInputStream(parsed)?.use {
                        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        null
                    } else {
                        var sample = 1
                        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
                            sample *= 2
                        }
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = sample
                        }
                        resolver.openInputStream(parsed)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, opts)
                        }
                    }
                }
            }.getOrNull()

            fun clear() {
                synchronized(cached) { cached.clear(); loading.clear() }
                synchronized(this) {
                    published = null
                    publishedKey = null
                    lastSource = null
                    lastCheckedAtMs = 0L
                    versionValue++
                }
            }
        }

        /** 当前组件名，用于在设置页跳转到授权界面。 */
        fun component(context: Context): ComponentName =
            ComponentName(context.packageName, MusicNotificationListenerService::class.java.name)

        /** 检查通知监听权限是否已授予。 */
        fun isEnabled(context: Context): Boolean {
            val enabled = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context)
            return enabled.contains(context.packageName)
        }

        /** 监听服务当前是否已与系统建立连接。 */
        fun isConnected(): Boolean = instance != null

        /**
         * 匹配相似度等参数变化后调用：清空当前匹配标记并立即按新参数
         * 重新匹配当前歌曲，桌面歌词与主界面通过 matchVersion 感知刷新。
         */
        fun rematchNow() {
            Session.matchedKey = null
            val svc = instance
            if (svc == null) {
                // 监听未连接时无从匹配：不要留下一个永远不结束的「正在匹配歌词」
                Session.matchState = LyricMatchState.IDLE
                return
            }
            // 清掉旧排期：否则 pendingMatchKey 与本次 key 相同时 tryAutoMatch 会
            // 认为已排期而直接返回，界面永远停在「正在匹配歌词」
            svc.cancelPendingAutoMatch()
            svc.lastInvalidatedTrackTitle = null
            Session.matchState = LyricMatchState.MATCHING
            runCatching { svc.tryAutoMatch() }
        }

        /**
         * 歌词行前进时由界面调用：时长缺失（网络波动导致元数据缺时长等）时
         * 重新获取。服务未连接时静默跳过。
         */
        fun refreshMissingDuration() {
            instance?.let { svc -> runCatching { svc.forceRefreshMissingDuration() } }
        }

        /** 上次执行恢复的时间，避免频繁切换组件。 */
        @Volatile private var lastRecoverAt = 0L

        /**
         * 恢复通知监听绑定：应用更新或系统回收后，授权可能仍在但绑定已断开，
         * 表现为"需要重新授权"。通过禁用/启用组件 + requestRebind 强制系统
         * 重新绑定，无需用户手动开关授权。
         */
        fun recover(context: Context) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastRecoverAt < RECOVER_THROTTLE_MS) return
            lastRecoverAt = now
            val component = component(context)
            runCatching {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    component,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
                )
            }
            val seq = Diag.nextRebind("NLS.recover", now)
            LogCapture.i(
                "NLS",
                "recover requested seq=$seq from=NLS.recover at=$now " +
                    "screen=${Diag.lastScreenEvent} sinceScreen=${Diag.sinceScreenEvent(now)}ms"
            )
            runCatching { NotificationListenerService.requestRebind(component) }
        }

        /** 跳转后读回校验的延迟：等待播放器处理 seek 并回报新进度。 */
        private const val SEEK_VERIFY_DELAY_MS = 550L

        /**
         * 判定「会话是否响应了跳转」的进度误差容忍（毫秒）。**只用于决定要不要
         * 向下一个同应用会话补发 seekTo，绝不用于歌词计时**；取值需覆盖不支持
         * 毫秒 seek 的播放器把目标吸附到整秒（最多 999ms）以及快照本身的偏移。
         */
        private const val SEEK_TOLERANCE_MS = 1_200L

        /** 会话存活判据：状态时间戳允许早于 seek 发出时刻的余量（毫秒）。 */
        private const val SEEK_STAMP_SLACK_MS = 300L

        /** 会话存活判据：位置相对 seek 前至少移动该幅度才算"有响应"（毫秒）。 */
        private const val SEEK_MOVE_MIN_MS = 150L

        /**
         * 播放器未提供基准时间时，上报的进度快照变化超过该阈值才认为
         * 发生了 seek/快照更新并重置进度基准；小于该值视为抖动忽略。
         */
        private const val SNAPSHOT_CHANGE_THRESHOLD_MS = 250L

        /**
         * 播放中占位位置的上限（毫秒）：会话声称的位置低于该值视为"贴在开头"。
         */
        private const val STALLED_POSITION_MAX_MS = 1_500L

        /**
         * 播放中位置持续多久不变即视为冻结快照（毫秒）：部分播放器（如
         * Telegram 内置播放器）每 5 秒才上报一次位置，期间位置静止，这类
         * 快照不能用于重新锚定进度。
         */
        private const val STALLED_FREEZE_MS = 2_000L

        /**
         * 占位/冻结状态持续多久才认定为不可信（毫秒）：避免刚切歌、
         * seek 后的瞬时状态被误判。
         */
        private const val STALLED_MIN_MS = 1_200L

        /**
         * 切歌后位置一致性判定的时间窗（毫秒）：只在切歌后这段时间内评估各会话
         * 的位置是否与"从头播放"一致（越过该窗后位置可被 seek 改变，无法判定）。
         */
        private const val TRACK_RESET_WINDOW_MS = 12_000L

        /**
         * 切歌后允许的位置偏差（毫秒）：播放器位置相对"切歌经过时间"超前超过该值
         * 即视为沿用了上一首的位置（残留会话）。取值需覆盖元数据回调的检测延迟。
         */
        private const val TRACK_RESET_TOLERANCE_MS = 6_000L

        /** 残留会话的持续降权分值：高于其余所有分值之和，确保本曲内不再被选中。 */
        private const val STALE_SESSION_PENALTY = 20

        /**
         * 切歌后等待「新歌首条有效进度上报」的宽限窗（毫秒）：所有应用都要靠
         * 切歌后的新上报读取一次新歌起点（含播放器记住的单曲进度）。部分播放器
         * 长时间播放后为优化性能不再上报状态（或漏报），切歌瞬间能读到的仍是
         * 上一首的残留状态（位置/时间戳/播放态一字未变）；还有播放器的进度
         * 一直锁定为 0（只有占位值，没有可读起点）。宽限窗内先按切歌归零起点
         * 由应用内计时推进、不采用任何快照；超过该窗仍无有效进度，则判定
         * 「无可读起点」：切歌归零即本首歌最终起点，整首歌纯应用内计时；
         * 暂停 / 恢复只冻结并续走，直到用户在播放器里调整进度或换源才重新对表。
         * 取值需覆盖慢节拍播放器（如 Telegram 每 5 秒才上报一次）的切歌上报延迟。
         */
        private const val TRACK_START_FRESH_WAIT_MS = 8_000L

        /**
         * 状态冻结判定阈值（毫秒）：PlaybackState 时间戳超过该时长未刷新，或会话
         * 位置持续该时长未变化，就认为这个会话的状态已经冻结（残留会话）。换歌
         * 重新选中这类会话时只相信应用内部计时，不把残留位置当新歌起点或对表基准。
         * 取值需显著大于慢节拍播放器的上报间隔（如 Telegram 约 5 秒），避免把
         * 正常播放中两次上报之间的间隙误判为冻结。
         */
        private const val FROZEN_STATE_AGE_MS = 8_000L

        /**
         * 切歌起点「末尾残留」守卫（毫秒）：上报位置距时长末尾不足该值时，
         * 视为上一首播完卡在末尾的残留（锁屏积压唤醒实测 pos==dur），不当作
         * 新歌起点、也不据其对表。取 1 秒可覆盖正常歌曲结尾播放，又不会把
         * 中途接入的合法进度误伤。
         */
        private const val TRACK_START_END_GUARD_MS = 1_000L

        /**
         * 同包同标题会话的进度分歧阈值（毫秒）：两个播放中的会话位置相差超过该值
         * 才视为异常（正常快照延迟最多几百毫秒，seek 也会同步更新所有会话）。
         */
        private const val DIVERGENCE_TOLERANCE_MS = 15_000L

        /** 分歧需连续命中的次数（约每秒一次判定），避免单次异常采样就换源。 */
        private const val DIVERGENCE_STRIKES = 3

        /** 应用内 seek 后的静默期（毫秒）：位置被人为跳变期间不做分歧判定。 */
        private const val DIVERGENCE_SEEK_QUIET_MS = 12_000L

        /** 时长缺失兜底重取的最小间隔（毫秒）：歌词快速切行时限流。 */
        private const val MISSING_DURATION_REFRESH_INTERVAL_MS = 2_000L

        /** 进度基准校验的宽限（毫秒）：按基准推算的进度超出时长该值以上才判定基准非法。 */
        private const val RUNAWAY_GRACE_MS = 15_000L

        /**
         * 跳跃防护阈值（毫秒）：按播放器基准推算的位置超出"本机时钟预期位置"
         * 该值以上时，判定基准原点不可信（异构时钟或过期会话），改走本机时钟兜底。
         */
        private const val JUMP_TOLERANCE_MS = 2_000L

        /** 进度源切换滞后（毫秒）：挑战者会话的状态时间需显著超过当前源才允许换源。 */
        private const val SWITCH_HYSTERESIS_MS = 3_000L

        /** 快照新鲜度窗口（毫秒）：基准时间距当前超过该值视为陈旧快照，不参与 seek 判定。 */
        private const val SNAPSHOT_FRESH_MS = 3_000L

        /** seek 间断阈值（毫秒）：相邻快照推进量与墙钟推进量差异超过该值判定为 seek。 */
        private const val SEEK_DISCONTINUITY_MS = 1_500L

        /** 正常播放时允许向后吸附的最大偏移（毫秒）：低于消费端 500ms 守卫，避免可见回跳。 */
        private const val BACKWARD_SNAP_GUARD_MS = 300L

        /**
         * 点击后的「应用自计时保护窗口」：期间播放器快照一律不参与歌词计时，
         * 进度完全由本机墙钟从跳转目标（或整秒基准）推进。覆盖播放器处理
         * seekTo 的常见延迟（实测约 573ms）。普通应用窗口结束后恢复周期快照
         * 校正；「毫秒信息不可信」的应用结束后仍只信应用内计时。
         *
         * **必须是整秒（1000ms），不能带毫秒零头**：不支持毫秒 seek 的播放器
         * 位置只在整秒刻度上更新，应用自身计时也从整秒基准起步；窗口若取 1.2s，
         * 结束点会落在两次整秒快照之间，恢复校正那一帧会把这 200ms 零头一次
         * 性吞进基准（或拉回上一个整秒），逐字动画因此突然加速 / 回跳。取整到
         * 1s 后窗口边界与整秒刻度对齐，恢复校正时两者本就一致，无跳变。
         */
        private const val SEEK_SELF_TIMED_WINDOW_MS = 1_000L

        /**
         * 点击后的「外部跳转静默期」：这段时间内播放器上报的间断跳变视为本次
         * seekTo 的落地结果；超过后才认作用户在播放器里手动跳转（或循环播放
         * 归零），按外部事件重新读秒对表。
         */
        private const val SEEK_EXTERNAL_SEEK_QUIET_MS = 2_000L

        /** 点击瞬间的短钉行窗口：只固定高亮，不冻结进度、不阻挡自然换行。 */
        private const val SEEK_PIN_WINDOW_MS = 400L

        /**
         * 快速连点同一行的重发阈值：窗口内的重复点击一律折叠（不重发 seekTo、
         * 不改应用自身计时基准）；超过该值允许再发一次，避免上一次跳转被
         * 播放器静默丢弃后「一直点一直不跳」。
         */
        private const val SEEK_RAPID_RESEND_MS = 600L

        /** 补发路径的钉行窗口（同样不冻结进度）。 */
        private const val SEEK_UI_PENDING_MS = 3_000L

        /** 兜底复检的延迟：留出跳转指令落地的时间后再核对是否完全没生效。 */
        private const val SEEK_RECHECK_DELAY_MS = 1_600L

        /**
         * 兜底复检的落后容忍（毫秒）：快照仍落后目标超过该值才补发 seek。
         * 与消费端防回跳守卫（500ms）对齐——小幅落后可被守卫与自然推进消化，
         * 补发反而会重播一小段；点击相邻歌词行的跳距常只有几百毫秒，容忍
         * 过宽会把真实的"seek 未生效"误判为可消化的小幅落后而漏网。
         */
        private const val SEEK_RECHECK_TOLERANCE_MS = 500L

        /** 恢复后等待「时间戳前进的新快照」的窗口：覆盖 Telegram 约 5 秒的上报间隔。 */
        private const val RESYNC_WINDOW_MS = 8_000L

        /**
         * 手动对齐放宽的快照上限：慢上报播放器的最后一份快照可能已有数秒，
         * 但按 (now - stamp) 外推正好补上这段间隔，因此比自动重锚更宽松。
         */
        private const val RESYNC_MANUAL_MAX_SNAPSHOT_AGE_MS = 6_000L

        /**
         * 双边对齐后吞掉 seek 落地帧的窗口：与 [RESYNC_WINDOW_MS] 同量级，
         * 覆盖 Telegram 约 5 秒的慢上报；窗口内落在目标附近的帧一律视为
         * 本次对齐的落地，不当成外部跳转。用户新 seek / 切歌 / 换源会作废登记。
         */
        private const val ALIGN_BOTH_QUIET_MS = 8_000L

        private const val RESYNC_REASON_RESUME = "resume"
        private const val RESYNC_REASON_MANUAL = "manual"

        /** 点击跳转（毫秒不可信）落地观测触发的重锚：按一键对齐逻辑对表。 */
        private const val RESYNC_REASON_SEEK_LANDING = "seekLanding"

        /**
         * 点击跳转落地观测窗口：播放器执行大跳转的落地延迟（实测 Telegram
         * 约 1.5 秒）加慢上报滞后都在此范围内；超窗放弃（指令被忽略 / 播放器
         * 未执行），维持应用内自计时，不比修复前更差。
         */
        private const val SEEK_LANDING_WINDOW_MS = 8_000L

        /**
         * 落地判定的邻域半径：播放器执行 seekTo 后位置必须「真的」进入目标
         * 整秒的一个刻度内才算落地。必须是 1 秒量级——放宽到数秒会把「seek
         * 刚发出、播放器尚在原位置」的帧误判成落地，于是拿旧位置取整秒把
         * 应用基准锚到错误的整秒（实测点击 5000 时 raw 还在 2835 却按 2000
         * 对齐，造成恒定 −2 秒偏移）。Telegram 上报约每秒一次，1 秒邻域既
         * 能捕获真正落地帧，又不会漏等。
         */
        private const val SEEK_LANDING_NEIGHBOR_MS = 1_000L

        /** 切歌二次微调观测窗口：落地帧应在首个上报周期内到达，超窗放弃。 */
        private const val TRACK_LANDING_WATCH_WINDOW_MS = 5_000L

        /** 切歌二次微调的最小偏移：小于此值不值得再跳一次（感知阈以下）。 */
        private const val TRACK_LANDING_MIN_DELTA_MS = 5L

        /**
         * 用户手动拖进度条的跳变量级：本地 seek 落地 / 未落地的相位差最多
         * 1~2 秒，超过该值就认为播放器已经在用户指定的新位置，静默期内的
         * 这类跳变仍要并入 externalSeek，让内部计时跟随用户的新位置。
         */
        private const val USER_SEEK_JUMP_MS = 3_000L

        /** 未生效 seek 的补发窗口：超过后放弃（用户可能已手动跳转或切歌）。 */
        private const val PENDING_SEEK_RETRY_WINDOW_MS = 30_000L

        /**
         * 让当前播放的媒体跳转到指定进度（毫秒）。
         * 用于编辑歌词时点击时间戳校准，以及播放模式下点击歌词行跳转。
         */
        fun seekTo(positionMs: Long, lineTimeMs: Long = -1L) {
            val service = instance ?: return
            service.seekWithFallback(positionMs, lineTimeMs)
        }

        /**
         * 桌面歌词悬浮面板「一键对齐」：以播放器当前快照重锚歌词计时，手动
         * 消除暂停 / 播放之后残留的固定偏移。两个服务同进程，直接调实例即可，
         * 不需要广播。手动路径放宽快照新鲜度，覆盖慢上报播放器。
         *
         * 「当前应用的毫秒信息不可信」开启时走双边对齐：按快照时间戳外推
         * 到当前后向下取整秒，应用内计时与播放器一起 seek 到该整秒刻度；
         * 恢复播放、切歌起点、外部事件同样适用。其余应用仍只重锚歌词、
         * 不动音频。见 [resyncFromSnapshot] / [applyBothSidesAlign]。
         *
         * @return 是否已成功重锚；无会话 / 快照不可用时返回 false，供界面提示。
         */
        fun requestManualResync(): Boolean {
            val service = instance ?: return false
            return service.resyncFromSnapshot(
                RESYNC_REASON_MANUAL,
                maxSnapshotAgeMs = RESYNC_MANUAL_MAX_SNAPSHOT_AGE_MS
            )
        }

        /**
         * 【全局控制通道】切换当前播放音乐的播放/暂停状态。
         *
         * 调用方是「监听状态」页**以外**的所有入口：主界面打轴按钮、底部媒体
         * 控制条、封面点击等。指令作用于当前进度源 + 同包名其它会话，语义是
         * 全局的，不得改成按会话定向。
         * 仅「监听状态」页的会话卡片使用 [togglePlayPauseFor]。
         *
         * @return 是否成功发出指令；服务未连接 / 无可用媒体会话时为 false，
         *         界面据此给出提示，避免点击后完全静默、让人以为按钮失效。
         */
        fun togglePlayPause(source: String = "ui.button"): Boolean {
            val service = instance ?: return false
            return service.togglePlayback(source)
        }

        /**
         * 【全局控制通道】主界面底部控制条的上一首 / 下一首。
         * 语义是全局的：发给进度源 + 同包名其它有效会话，不得改成按会话定向；
         * 仅「监听状态」页使用 [skipTrackFor]。
         */
        fun skipTrack(next: Boolean): Boolean {
            val service = instance ?: return false
            return service.skipTrackGlobal(next)
        }

        /**
         * 【仅监听状态页】卡片按会话定向的播放/暂停：只控制卡片对应的那个媒体会话。
         * 唯一调用方是 [ListenStatusActivity] 的会话卡片（列表 / 左右滑动两种布局共用）；
         * 其它界面一律走 [togglePlayPause] 全局通道，两者互不干扰。
         *
         * @param sid 卡片快照里的 sid；会话已销毁/重建、不支持该动作或服务未连接时
         *            返回 false，界面据此提示，不会静默落到别的会话上。
         * @param onNoResponse 指令已发出但目标会话在 [ISOLATED_RESPONSE_CHECK_MS]
         *                     内毫无变化时，在主线程回调，供界面提示「会话未响应」。
         */
        fun togglePlayPauseFor(
            sid: Int,
            source: String = "listen.status",
            onNoResponse: (() -> Unit)? = null,
        ): Boolean {
            val service = instance ?: return false
            return service.togglePlaybackFor(sid, source, onNoResponse)
        }

        /**
         * 【仅监听状态页】卡片按会话定向的上一首 / 下一首；next=true 为下一首。
         * 唯一调用方是 [ListenStatusActivity]；其它界面一律走 [skipTrack] 全局通道。
         * [onNoResponse] 语义同 [togglePlayPauseFor]。
         */
        fun skipTrackFor(
            sid: Int,
            next: Boolean,
            onNoResponse: (() -> Unit)? = null,
        ): Boolean {
            val service = instance ?: return false
            return service.skipTrackForSession(sid, next, onNoResponse)
        }

        /** 调试模式：获取当前歌曲全部信息的文本快照（服务未连接时返回提示）。 */
        fun debugSnapshot(): String =
            instance?.buildDebugSnapshot() ?: "通知监听：未连接（服务未运行）"

        /** 请求立即输出当前状态快照（开启日志抓取时调用，无需等播放状态变化）。 */
        fun requestStateDump(reason: String) {
            instance?.dumpCurrentState(reason)
        }
    }
}
