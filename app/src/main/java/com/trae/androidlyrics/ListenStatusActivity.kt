package com.trae.androidlyrics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.trae.androidlyrics.databinding.ActivityListenStatusBinding
import com.trae.androidlyrics.databinding.ItemClockCardBinding
import com.trae.androidlyrics.databinding.ItemSessionCardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「监听状态」页。
 *
 * 参考 Capsulyric 首页（状态胶囊 + 当前播放会话卡片 + 左右滑动切换媒体会话 +
 * 页面指示器 + 播放控制），并按需求补充：
 * - 两种排列方式：**列表排列（默认）** 与 **左右滑动切换**；
 * - 每个会话显示观测时间、active=true/false、[#N 号会话] 编号
 *   （口径与调试信息悬浮窗一致）；
 * - **歌词应用内部计时固定在页面最上方**，同时又是左右滑动切换的**第一个页面**；
 * - 会话卡片带进度条与上一首/播放/下一首，并可直接重新匹配在线歌词；
 * - 卡片上的三个控制按钮按 sid 定向到各自会话，彼此完全隔离：
 *   不改变全局进度源，也不扩散到同包名的其它会话。
 */
class ListenStatusActivity : ThemedActivity() {

    private lateinit var binding: ActivityListenStatusBinding
    private lateinit var appearance: AppearancePrefs
    private lateinit var prefs: Prefs

    private var sessions: List<MediaSessionSnapshot> = emptyList()
    private var clock: InternalClockSnapshot = InternalClockSnapshot.empty()
    private lateinit var listAdapter: SessionListAdapter
    private lateinit var pagerAdapter: SessionPagerAdapter
    private var snapHelper: PagerSnapHelper? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var refreshing = false
    /** 用户正在拖动分页时暂缓整体刷新，避免打断 PagerSnapHelper 的吸附动画。 */
    private var pagerScrolling = false
    /** 「会话未响应控制指令」提示的节流时间戳，避免连点按钮时刷屏。 */
    private var lastNoResponseToastAtMs = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!refreshing) return
            refreshData()
            uiHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = AppearancePrefs(this)
        prefs = Prefs(this)
        binding = ActivityListenStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ls_title)

        setupDebugFloatSwitch()
        listAdapter = SessionListAdapter()
        pagerAdapter = SessionPagerAdapter()
        binding.sessionList.layoutManager = LinearLayoutManager(this)
        binding.sessionList.adapter = listAdapter

        val pagerLM = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.sessionPager.layoutManager = pagerLM
        binding.sessionPager.adapter = pagerAdapter
        val helper = PagerSnapHelper()
        helper.attachToRecyclerView(binding.sessionPager)
        snapHelper = helper
        binding.sessionPager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                pagerScrolling = newState != RecyclerView.SCROLL_STATE_IDLE
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // 拖动期间累积的 active/进度变更，在停稳后一次性补齐
                    pagerAdapter.notifyDataSetChanged()
                    updatePageIndicator(pagerLM, helper)
                }
            }
        })

        // 默认列表排列；用户上次的选择会被记住
        val listMode = appearance.listenLayout == AppearancePrefs.LAYOUT_LIST
        binding.layoutGroup.check(if (listMode) R.id.layoutList else R.id.layoutSwipe)
        binding.layoutGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            appearance.listenLayout = if (checkedId == R.id.layoutSwipe)
                AppearancePrefs.LAYOUT_SWIPE else AppearancePrefs.LAYOUT_LIST
            // 切换排列方式后卡片要按新布局重新量高，清掉上一轮的最大高度记录
            binding.sessionPager.resetStickyHeight()
            applyLayout()
        }

        // 会话卡片独立渲染的歌词行数：滑块 0~8 对应 1~9 行，默认 3 行
        binding.lyricLinesBar.progress = appearance.listenLyricLines - 1
        binding.lyricLinesValue.text = getString(
            R.string.ls_lyric_lines_value, appearance.listenLyricLines
        )
        binding.lyricLinesBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val lines = progress + 1
                appearance.listenLyricLines = lines
                binding.lyricLinesValue.text = getString(R.string.ls_lyric_lines_value, lines)
                if (fromUser) {
                    // 歌词行数改变了卡片内容高度，旧的「只增不减」记录不再适用
                    binding.sessionPager.resetStickyHeight()
                    refreshData()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnRefresh.setOnClickListener { refreshData() }
        applyLayout()
        refreshData()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // 磁贴 / 悬浮窗自身也可能改写 debugMode，回到本页时同步开关状态
        binding.debugModeSwitch.isChecked = prefs.debugMode
        refreshing = true
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        refreshing = false
        uiHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    // ---------------------------------------------------------------- 调试信息悬浮窗

    /**
     * 「调试信息悬浮窗」开关由「设置」页迁入，位于「应用内部计时」卡片上方。
     * 写入的仍是 Prefs.debugMode，与磁贴 [DebugInfoTileService] 的语义完全一致。
     */
    private fun setupDebugFloatSwitch() {
        binding.debugModeSwitch.isChecked = prefs.debugMode
        binding.debugModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                prefs.debugMode = false
                DebugFloatService.stop(this)
                DebugInfoTileService.requestUpdate(this)
                return@setOnCheckedChangeListener
            }
            if (canDrawOverlays()) {
                prefs.debugMode = true
                DebugFloatService.start(this)
                DebugInfoTileService.requestUpdate(this)
            } else {
                binding.debugModeSwitch.isChecked = false
                requestOverlayPermission()
            }
        }
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
            UiTips.show(this, R.string.overlay_permission_hint, Toast.LENGTH_LONG)
        }
    }

    // ---------------------------------------------------------------- 布局切换

    private fun applyLayout() {
        val listMode = appearance.listenLayout == AppearancePrefs.LAYOUT_LIST
        binding.sessionList.visibility = if (listMode) View.VISIBLE else View.GONE
        binding.sessionPager.visibility = if (listMode) View.GONE else View.VISIBLE
        binding.swipeHint.visibility = if (listMode) View.GONE else View.VISIBLE
        binding.pageIndicator.visibility = if (listMode) View.GONE else View.VISIBLE
        if (!listMode) pagerAdapter.notifyDataSetChanged()
        refreshEmptyState(listMode)
    }

    // ---------------------------------------------------------------- 数据刷新

    private fun refreshData() {
        sessions = MusicNotificationListenerService.sessionSnapshots()
        clock = MusicNotificationListenerService.internalClockSnapshot()

        val connected = MusicNotificationListenerService.isConnected()
        binding.statusPill.text = getString(
            if (connected) R.string.ls_status_connected else R.string.ls_status_disconnected
        ) + if (sessions.isNotEmpty()) " · " + getString(R.string.ls_session_count, sessions.size) else ""
        binding.sessionCount.text = getString(R.string.ls_session_count, sessions.size)

        bindClock(binding.clockCardTop, clock, compact = true)
        listAdapter.submit(sessions)
        pagerAdapter.submit(clock, sessions)
        // 与列表模式保持一致：会话的 active/时间/进度每 500ms 都会变，
        // 分页卡片也要跟着刷新；仅当用户正在拖动时暂缓，停稳后由滚动监听补齐。
        if (binding.sessionPager.visibility == View.VISIBLE && !pagerScrolling) {
            pagerAdapter.notifyDataSetChanged()
        } else {
            bindClockPageIfVisible()
        }
        refreshEmptyState(appearance.listenLayout == AppearancePrefs.LAYOUT_LIST)
        updatePageIndicator(
            binding.sessionPager.layoutManager as? LinearLayoutManager,
            snapHelper
        )
    }

    /** pager 的第 0 页也是内部计时卡片，刷新时同步那一张的内容。 */
    private fun bindClockPageIfVisible() {
        val holder = binding.sessionPager.findViewHolderForAdapterPosition(0)
        if (holder is SessionPagerAdapter.ClockVH) bindClock(holder.binding, clock, compact = false)
    }

    private fun refreshEmptyState(listMode: Boolean) {
        binding.emptyHint.visibility = if (listMode && sessions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updatePageIndicator(lm: LinearLayoutManager?, helper: PagerSnapHelper?) {
        if (lm == null || helper == null || binding.sessionPager.visibility != View.VISIBLE) return
        val view = helper.findSnapView(lm) ?: return
        val pos = lm.getPosition(view)
        binding.pageIndicator.text = getString(
            R.string.ls_page_indicator, pos + 1, pagerAdapter.itemCount
        )
    }

    // ---------------------------------------------------------------- 卡片绑定

    private fun bindClock(b: ItemClockCardBinding, c: InternalClockSnapshot, compact: Boolean) {
        b.clockTitle.text = if (compact) getString(R.string.ls_internal_clock)
        else getString(R.string.ls_page_clock) + " · " + getString(R.string.ls_internal_clock)
        b.clockTime.text = clockText(c.positionMs)
        b.clockMeta.text = buildString {
            append(if (c.isPlaying) "播放中" else "已暂停")
            append(" · ").append(OnlineLyrics.formatClock(c.durationMs))
            if (c.title.isNotBlank()) append(" · ").append(c.title)
        }
        b.clockProgress.progress = if (c.durationMs > 0L)
            ((c.positionMs.coerceAtLeast(0L).toDouble() / c.durationMs) * 1000).toInt().coerceIn(0, 1000)
        else 0

        b.clockRows.removeAllViews()
        val rows = if (compact) c.rows.take(6) else c.rows
        rows.forEach { (key, value) ->
            val row = TextView(b.root.context).apply {
                text = "$key：$value"
                textSize = 12f
                // 深浅主题下都可读的中性弱化色，避免依赖特定主题属性
                setTextColor(0xFF9AA2AB.toInt())
                setPadding(0, 3, 0, 3)
            }
            b.clockRows.addView(row)
        }
        // 「应用内部计时」按本机墙钟推进的进度单独渲染歌词，与会话卡片互不干扰
        bindLyricRows(
            b.clockLyricRows,
            c.lyricPositionMs,
            c.durationMs,
            b.clockTitle.currentTextColor,
        )
    }

    private fun clockText(ms: Long): String {
        val v = ms.coerceAtLeast(0L)
        val min = v / 60_000L
        val sec = (v / 1000L) % 60
        val cs = (v % 1000L) / 10
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", min, sec, cs)
    }

    private fun bindSession(b: ItemSessionCardBinding, s: MediaSessionSnapshot) {
        b.sessionNo.text = getString(R.string.ls_session_no, s.index)
        b.sessionActive.text = getString(
            if (s.active) R.string.ls_active_true else R.string.ls_active_false
        )
        (b.sessionActive.background?.mutate() as? GradientDrawable)?.setColor(
            if (s.active) 0xFF22C55E.toInt() else 0xFF8A8F98.toInt()
        )
        b.sessionTime.text = getString(
            R.string.ls_observed, timeFormat.format(Date(s.observedAtMs))
        )

        b.sessionSong.text = s.title.ifBlank { "—" }
        b.sessionArtist.text = s.artist.ifBlank { "—" }
        b.sessionApp.text = s.appLabel.ifBlank { s.pkg }
        s.cover?.let { b.sessionCover.setImageBitmap(it) }
            ?: b.sessionCover.setImageResource(android.R.drawable.ic_menu_gallery)

        b.sessionDetail.text = buildString {
            appendLine(getString(R.string.ls_state, s.stateName))
            appendLine(getString(R.string.ls_progress,
                OnlineLyrics.formatClock(s.posMs), OnlineLyrics.formatClock(s.durationMs)))
            appendLine(getString(R.string.ls_base_age,
                if (s.baseAgeMs >= 0) "${s.baseAgeMs}ms" else "-"))
            appendLine(getString(R.string.ls_pkg, s.pkg))
            append("sid=").append(s.sid)
            if (s.album.isNotBlank()) append(" · ").append(s.album)
        }.trim()

        b.sessionProgressBar.progress = if (s.durationMs > 0L && s.posMs >= 0L)
            ((s.posMs.toDouble() / s.durationMs) * 1000).toInt().coerceIn(0, 1000) else 0

        // 会话卡片按自己的进度独立渲染歌词（与主界面互不干扰）
        bindSessionLyrics(b, s)

        // 播放/暂停文案只反映本会话自己的状态，不同卡片互不影响
        b.btnPlayPause.text = getString(
            if (s.isPlaying) R.string.ls_action_pause else R.string.ls_action_play
        )

        // 控制指令严格绑定到本卡片对应的会话（sid 来自服务发布的快照），
        // 不会落到 activeController 或同包名的其它会话上。
        // 列表排列与左右滑动两种布局共用这份绑定，行为一致。
        // false：会话失效 / 明确不支持该动作；回调：指令已发出但会话静默丢弃。
        b.btnCopySession.setOnClickListener { copySession(s) }
        b.btnPrev.setOnClickListener {
            val sent = MusicNotificationListenerService.skipTrackFor(
                s.sid, next = false, onNoResponse = { showSessionNoResponse() }
            )
            if (!sent) toast(getString(R.string.ls_session_control_failed))
        }
        b.btnNext.setOnClickListener {
            val sent = MusicNotificationListenerService.skipTrackFor(
                s.sid, next = true, onNoResponse = { showSessionNoResponse() }
            )
            if (!sent) toast(getString(R.string.ls_session_control_failed))
        }
        b.btnPlayPause.setOnClickListener {
            val sent = MusicNotificationListenerService.togglePlayPauseFor(
                s.sid, "listen.status", onNoResponse = { showSessionNoResponse() }
            )
            if (!sent) toast(getString(R.string.ls_session_control_failed))
        }
    }

    // ---------------------------------------------------------------- 独立歌词渲染

    /** 会话卡片按自己上报的进度单独渲染歌词，与主界面互不干扰。 */
    private fun bindSessionLyrics(b: ItemSessionCardBinding, s: MediaSessionSnapshot) {
        // sessionApp 使用 ?attr/colorPrimary，直接取它的解析结果作为当前行高亮色
        bindLyricRows(b.sessionLyricRows, s.posMs, s.durationMs, b.sessionApp.currentTextColor)
    }

    /**
     * 独立渲染一份歌词：按传入的进度定位当前行。
     *
     * 「应用内部计时」卡片用本机墙钟推进的歌词进度，会话卡片用各自上报的进度，
     * 两份结果互不干扰；行数统一由页面滑块（[AppearancePrefs.listenLyricLines]）控制，
     * 默认 3 行 = 上一句 / 正在播放 / 下一句，当前行尽量居中：上方 (n-1)/2 行，其余放下方。
     */
    private fun bindLyricRows(
        rows: LinearLayout,
        positionMs: Long,
        durationMs: Long,
        primary: Int,
    ) {
        val count = appearance.listenLyricLines
        // 复用已有 TextView，避免每 500ms 刷新时反复创建视图
        while (rows.childCount > count) rows.removeViewAt(rows.childCount - 1)
        while (rows.childCount < count) rows.addView(newLyricRow(rows.context))

        val lines = Session.lyricLines.orEmpty()
        val dim = 0xFF9AA2AB.toInt()
        if (lines.isEmpty()) {
            for (i in 0 until count) {
                (rows.getChildAt(i) as TextView).apply {
                    text = if (i == count / 2) getString(R.string.ls_lyric_empty) else ""
                    setTextColor(dim)
                    typeface = android.graphics.Typeface.DEFAULT
                    alpha = 0.78f
                }
            }
            return
        }

        var idx = findLineIndex(lines, positionMs)
        val hasCurrent = idx >= 0
        if (idx < 0) idx = 0
        val above = (count - 1) / 2
        val start = idx - above

        for (i in 0 until count) {
            val lineIndex = start + i
            val tv = rows.getChildAt(i) as TextView
            val line = lines.getOrNull(lineIndex)
            if (line == null) {
                tv.text = ""
                continue
            }
            val current = hasCurrent && lineIndex == idx
            val role = when {
                !hasCurrent -> getString(R.string.ls_lyric_line_next)
                lineIndex < idx -> getString(R.string.ls_lyric_line_prev)
                lineIndex > idx -> getString(R.string.ls_lyric_line_next)
                else -> getString(R.string.ls_lyric_line_current)
            }
            val progress = if (current)
                lineProgress(line, lines.getOrNull(idx + 1), positionMs, durationMs) else null
            tv.text = buildString {
                append(role)
                append(" · ").append(OnlineLyrics.formatClock(line.time.coerceAtLeast(0L)))
                append("  ").append(line.text.ifBlank { "♪" })
                if (progress != null) append("  ").append(progress).append("%")
            }
            tv.setTextColor(if (current) primary else dim)
            tv.typeface = if (current) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
            tv.alpha = if (current) 1f else 0.78f
        }
    }

    /** 当前行在该进度下的完成度（0~100）；拿不到行尾时返回 null。 */
    private fun lineProgress(
        line: LyricLine,
        next: LyricLine?,
        positionMs: Long,
        durationMs: Long,
    ): Int? {
        val end = next?.time?.takeIf { it > line.time }
            ?: line.endTime?.takeIf { it > line.time }
            ?: durationMs.takeIf { it > line.time }
            ?: return null
        return (((positionMs - line.time).coerceAtLeast(0L).toFloat() / (end - line.time)) * 100)
            .toInt().coerceIn(0, 100)
    }

    private fun newLyricRow(context: Context): TextView = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textSize = 13f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, 3, 0, 3)
    }

    /** 按绝对毫秒进度二分定位歌词行；进度早于第一行时返回 -1。 */
    private fun findLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
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

    private fun copySession(s: MediaSessionSnapshot) {
        val text = buildString {
            appendLine("[#${s.index}] sid=${s.sid} pkg=${s.pkg}")
            appendLine("active=${s.active} state=${s.stateName}")
            appendLine("time=${timeFormat.format(Date(s.observedAtMs))}")
            appendLine("pos=${s.posMs} dur=${s.durationMs} baseAge=${s.baseAgeMs}ms")
            appendLine("title=${s.title} artist=${s.artist} album=${s.album}")
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("session", text))
        toast(getString(R.string.debug_copied))
    }

    /**
     * 定向指令已发出、但目标会话在复核窗口内没有任何状态/曲目变化的提示。
     * 这类会话「声明支持控制却静默丢弃指令」，与「会话失效」分开提示，
     * 便于直接判断是会话本身不受控还是卡片数据过期。
     */
    private fun showSessionNoResponse() {
        if (isFinishing || isDestroyed) return
        val now = System.currentTimeMillis()
        if (now - lastNoResponseToastAtMs < 1_500L) return
        lastNoResponseToastAtMs = now
        toast(getString(R.string.ls_session_no_response))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- 列表适配器

    private inner class SessionListAdapter : RecyclerView.Adapter<SessionListAdapter.VH>() {
        private var items: List<MediaSessionSnapshot> = emptyList()

        fun submit(list: List<MediaSessionSnapshot>) {
            val changed = items.size != list.size || items.zip(list).any { (a, b) -> a.sid != b.sid }
            items = list
            // 会话编号/active/时间每 500ms 都会变，直接整体刷新即可（条目数很少）
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemSessionCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = bindSession(holder.b, items[position])

        override fun getItemCount(): Int = items.size

        inner class VH(val b: ItemSessionCardBinding) : RecyclerView.ViewHolder(b.root)
    }

    // ---------------------------------------------------------------- 左右滑动适配器

    /**
     * 第 0 页固定是「应用内部计时」，其后依次是各媒体会话。
     * 用 RecyclerView + [LinearSnapHelper] 的子类 [PagerSnapHelper] 实现
     * 与 Capsulyric HorizontalPager 一致的整页吸附，无需引入 ViewPager2。
     */
    private inner class SessionPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<MediaSessionSnapshot> = emptyList()
        private var clockValue: InternalClockSnapshot = InternalClockSnapshot.empty()

        /** 只更新数据；刷新时机由页面统一控制（拖动时暂缓，避免打断吸附）。 */
        fun submit(c: InternalClockSnapshot, list: List<MediaSessionSnapshot>) {
            clockValue = c
            items = list
        }

        // inner class 不允许 companion object，页面类型常量直接作为实例字段
        private val typeClock = 0
        private val typeSession = 1

        override fun getItemViewType(position: Int): Int =
            if (position == 0) typeClock else typeSession

        // 第一页：应用内部计时；其余：媒体会话
        override fun getItemCount(): Int = items.size + 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == typeClock) {
                val b = ItemClockCardBinding.inflate(inflater, parent, false)
                b.root.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                ClockVH(b)
            } else {
                val b = ItemSessionCardBinding.inflate(inflater, parent, false)
                b.root.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                SessionVH(b)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // 让较短的卡片也填满已撑开的最大高度：划回信息少的卡片时容器不回缩，
            // 卡片自身也不出现「内容在上、下方空一块」的断层。
            holder.itemView.minimumHeight =
                binding.sessionPager.stickyItemMinHeight(holder.itemView)
            when (holder) {
                is ClockVH -> bindClock(holder.binding, clockValue, compact = false)
                is SessionVH -> items.getOrNull(position - 1)?.let { bindSession(holder.binding, it) }
            }
        }

        inner class ClockVH(val binding: ItemClockCardBinding) : RecyclerView.ViewHolder(binding.root)
        inner class SessionVH(val binding: ItemSessionCardBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
