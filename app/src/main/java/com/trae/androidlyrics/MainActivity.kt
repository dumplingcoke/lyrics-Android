package com.trae.androidlyrics

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ClickableSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivityMainBinding
import com.trae.androidlyrics.databinding.DialogEditMoreSettingsBinding
import com.trae.androidlyrics.databinding.DialogGlobalOffsetBinding
import com.trae.androidlyrics.databinding.DialogOffsetSettingsBinding
import com.trae.androidlyrics.databinding.DialogSaveAsBinding
import com.trae.androidlyrics.databinding.ItemOffsetAppBinding
import com.trae.androidlyrics.databinding.DialogSeekStepsBinding
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import kotlin.math.roundToLong

/**
 * 主界面：沉浸式歌词滚动 + 编辑模式。
 *
 * 播放模式下，RecyclerView 展示逐行歌词，跟随系统媒体播放进度滚动并高亮当前行；
 * LRC / WRC / TRC / KRC / QRC / YRC / VTT / TTML 等格式自带的逐字时间戳
 * 都支持逐字高亮动画（由设置中的「逐字动画」开关控制）。长按某行可进入编辑模式并定位到该行；
 * 兜底逻辑：长按空白处（列表为空、或按压点未落在任何歌词行上）也可进入编辑模式，
 * 并定位到距离按压点最近的一行歌词。
 *
 * 编辑模式以 EditText 为准，支持撤销/恢复、字体大小、自动换行、
 * 点击时间戳跳转播放进度校准，以及保存/另存为。
 */
class MainActivity : ThemedActivity() {

    private lateinit var prefs: Prefs
    private lateinit var appearance: AppearancePrefs
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: LyricAdapter
    private lateinit var layoutManager: LinearLayoutManager

    /** 自定义可点击标题，点击后弹出匹配到的歌词列表或最近打开文件列表（由设置决定）。 */
    private var titleTextView: MarqueeTextView? = null

    private var currentUri: Uri? = null

    /** 当前文档对应的内部存储副本（自动匹配/最近文件加载时非空），保存时直接写回该文件。 */
    private var internalFile: File? = null
    private var currentName: String? = null
    private var charset: Charset = StandardCharsets.UTF_8
    private var bom: ByteArray = ByteArray(0)
    private var dirty = false
    private var immersive = false

    /** 本实例的视图已按哪个主题版本 inflate；与 [App.themeVersion] 不同则需要重建一次。 */
    private var appliedThemeVersion = -1

    /** 背景图异步加载序号，避免旧任务的结果覆盖新结果。 */
    private var backgroundLoadSeq = 0

    /**
     * 上一帧「封面模糊背景」是否处于生效状态。
     * 「有封面 ↔ 无封面」切换等价于把它临时开关一次，切换帧必须立即重贴背景，
     * 否则帧循环会提前返回，画面一直停在上一首的模糊封面。
     */
    private var lastAlbumBlurEffective: Boolean? = null

    private var docText: String = ""
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentIndex = -1

    /** 已应用的解析选项版本：设置页改动「显示不带时间戳的行」后需要重新解析。 */
    private var lastParseVersion: Int = Session.parseVersion

    /** 系统文件选择器"另存为"时用户期望的文件名，用于纠正系统自动改名。 */
    private var pendingSaveAsName: String? = null

    /**
     * 新建歌词：弹窗里确定的文件名（含扩展名）。此时文档只处于编辑状态、尚未落盘，
     * 只有用户执行保存行为时才会据此在歌词目录新增文件。
     */
    private var pendingNewLyricName: String? = null

    /** "保存并继续新建"：保存是异步的，等保存结果返回后再弹出新建弹窗。 */
    private var pendingCreateNewAfterSave = false

    /** 新建歌词的文件正在创建中：防止连点保存生成多个同名/编号文件。 */
    private var savingNewLyric = false

    /** 编辑模式：是否自动换行。 */
    private var editWrap = true

    /**
     * 播放/暂停按钮的乐观显示文字：状态回调 / 每秒扫描可能要过一会才把
     * MusicProgress.isPlaying 刷新过来，点击后先立即切一次文字给出反馈；
     * 状态真正同步（或超时）后交回真实状态。
     */
    private var playPauseOptimisticLabel: String? = null
    private var playPauseOptimisticUntil = 0L

    /** 软键盘当前是否可见（由根布局可见高度差判定），用于「打开键盘/关闭键盘」按钮联动。 */
    private var keyboardShown = false

    /** 键盘可见性监听引用，退出时移除，避免泄漏。 */
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** 键盘按钮防抖：避免快速连点重复触发 showSoftInput。 */
    private var lastKeyboardToggleAt = 0L

    /**
     * 最近一次「整体小数点修改」的 (修改前全文, 修改后全文)，
     * 供弹窗内「撤销修改」按钮精确回滚；内容被再次改动后快照失效。
     */
    private var decimalModifySnapshot: Pair<String, String>? = null

    /** 编辑光标恢复：滚动停稳判定延时（毫秒），距上一帧滚动超过该值视为停止。 */
    private val SCROLL_SETTLE_MS = 150L

    /** 编辑光标保活：滚动进行期间按此间隔（毫秒）持续重启光标，保证拖动/惯性滚动中光标不消失。 */
    private val CURSOR_KEEPALIVE_MS = 250L

    /** 编辑光标保活：上次重启光标的时间戳（uptimeMillis），用于节流。 */
    private var lastCursorKeepaliveAt = 0L

    /**
     * 编辑光标保活：最近一次有效的光标位置。
     * 编辑框挂了 LinkMovementMethod（时间戳可点击），触摸/滚动经过它时可能清除
     * Selection，导致光标整个消失；此时用该位置恢复。
     */
    private var lastEditSelection = 0

    /**
     * 光标允许划出界外：滚动停稳后再解除"屏蔽自动回正"的标记。
     * 解除要晚于 [restoreCursorAfterScroll] 的最终恢复，否则该次 setSelection
     * 触发的 bringPointIntoView 仍会把视图拉回光标处。
     */
    private val clearCursorOutOfView = Runnable {
        binding.editorText.allowCursorOutOfView = false
    }

    /**
     * 编辑光标恢复：重启光标闪烁。
     *
     * 触摸拖动/惯性滚动会挂起系统的光标闪烁（Blink 回调被移除后不再自动重新排队），
     * 表现为"滚动过程中编辑光标丢失"。这里分两步重启：
     * 先关闭 cursorVisible 清理光标状态，下一帧再打开——setCursorVisible(true)
     * 内部会调用 makeBlink() 重新排队闪烁回调。跨帧执行可避免同帧内两次状态
     * 切换被合并导致恢复失效（部分系统/厂商 ROM 上会出现）。
     */
    private val restoreCursorAfterScroll = Runnable {
        val editor = binding.editorText
        if (!isEditMode()) return@Runnable
        editor.isCursorVisible = false
        editor.post {
            // 编辑模式可能已退出，避免无谓恢复
            if (isEditMode()) {
                // 焦点被滚动过程中的其他交互抢走时先取回，否则光标不会闪烁
                if (!editor.hasFocus()) editor.requestFocus()
                // 时间戳点击用的 LinkMovementMethod 会在触摸时移除 Selection，
                // 没有 Selection 就没有光标；用最近一次有效位置补回来
                if (editor.selectionStart < 0) {
                    editor.setSelection(lastEditSelection.coerceIn(0, editor.length()))
                }
                editor.isCursorVisible = true
                // 触发一次重绘，确保光标立刻出现而不用等下一个闪烁周期
                editor.invalidate()
            }
        }
    }

    /**
     * 编辑光标保活入口：滚动事件到达时调用。
     *
     * · 滚动进行中：按 [CURSOR_KEEPALIVE_MS] 节流持续执行光标重启，
     *   每次重启光标最多只隐藏一帧，拖动/惯性滚动全程光标可见；
     * · 同时防抖安排一次"滚动停稳后"的最终恢复，兜住所有滚动路径
     *   （内容滚动监听 + 触摸手势结束双路径都会汇入这里）。
     */
    private fun scheduleCursorRestore(v: View) {
        // 用户正在滑动浏览歌词：允许光标停在可视区外，屏蔽系统把光标强行滚回视野
        (v as? LyricEditText)?.allowCursorOutOfView = true
        // OnTouchListener 早于 View.onTouchEvent 执行，此刻记录的光标位置还是有效的
        val editor = v as? EditText
        if (editor != null && editor.selectionStart >= 0) {
            lastEditSelection = editor.selectionStart
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastCursorKeepaliveAt >= CURSOR_KEEPALIVE_MS) {
            lastCursorKeepaliveAt = now
            restoreCursorAfterScroll.run()
        }
        v.removeCallbacks(restoreCursorAfterScroll)
        v.postDelayed(restoreCursorAfterScroll, SCROLL_SETTLE_MS)
        // 滚动停稳、最终恢复执行完毕后才解除，避免恢复过程把视图回正
        v.removeCallbacks(clearCursorOutOfView)
        v.postDelayed(clearCursorOutOfView, SCROLL_SETTLE_MS + 200L)
    }

    /** 编辑模式：撤销/重做栈。 */
    private val undoStack = ArrayDeque<EditOp>()
    private val redoStack = ArrayDeque<EditOp>()
    private var suppressEditWatcher = false

    /** 待提交的编辑操作（连续同类操作会合并，空闲 600ms 后提交到撤销栈）。 */
    private var pendingOp: EditOp? = null
    private val commitRunnable = Runnable {
        pendingOp?.let {
            undoStack.add(it)
            pendingOp = null
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            syncMatchedLyrics()
            // 封面身份与切歌版本号独立于「自动匹配歌词」开关，必须在这里统一处理
            syncAlbumArtVersion()
            if (isPlayingMode()) {
                updatePlayback()
                updateLyricStatusHint()
                updateMediaControls()
            }
            if (isEditMode()) updateEditAssistUi()
            uiHandler.postDelayed(this, 50)
        }
    }

    /** 已同步应用的自动匹配歌曲 key，避免重复打开同一文件。 */
    private var appliedMatchKey: String? = null

    /** 已同步应用的自动匹配版本号，同一歌曲换绑不同歌词文件时用于检测刷新。 */
    private var appliedMatchVersion = -1

    /**
     * 已处理的切歌版本号。通知监听服务在真实切歌时立即作废 Session 歌词，
     * 主界面据此清空歌词区，不必等自动匹配（防抖 + 后台扫描）结果回来。
     */
    private var appliedSongChangeVersion = -1

    /**
     * 手动打开文件时歌曲信息尚未就绪（冷启动由"其他应用打开"拉起、通知监听服务还没连接），
     * 此时无法确定要保护的歌曲；置位后等首次拿到歌曲信息时再认领，避免被自动匹配覆盖。
     */
    private var manualSongPending = false

    /**
     * 手动打开文件时调用：把当前正在播放的歌曲标记为"用户已手动选择歌词"，
     * 防止稍后自动匹配的结果覆盖用户的选择。
     *
     * 冷启动由"其他应用打开"拉起时 [MusicProgress.title] 可能还是空（监听服务未连接），
     * 若直接取 [Session.matchedKey]（此时也为空），稍后服务连接写入匹配结果就会覆盖用户
     * 刚打开的文件——表现为"用其他应用打开却跳到了正在播放歌曲的歌词"。
     * 此时改为挂起标记，待首次拿到歌曲 key 后认领，随后切歌仍正常自动匹配。
     */
    private fun markManualOpen() {
        val title = MusicProgress.title?.trim().takeUnless { it.isNullOrEmpty() }
        if (title != null) {
            appliedMatchKey = "$title|${MusicProgress.artist?.trim().orEmpty()}"
            appliedMatchVersion = Session.matchVersion
            manualSongPending = false
        } else {
            appliedMatchKey = Session.matchedKey
            appliedMatchVersion = Session.matchVersion
            manualSongPending = true
        }
    }

    /**
     * 用户在标题栏列表 / 文件选择器里显式选中了一份歌词：把这份选择同步成
     * 进程级的「当前歌词」，而不只是改本界面的 [currentName]。
     *
     * 只改界面字段时，[Session.matchedFile] 仍是自动匹配到的旧文件；稍后
     * 重新匹配、元数据抖动或 Activity 重建（appliedMatch* 归零）都会让
     * [syncMatchedLyrics] 把界面切回旧文件，桌面歌词也会继续显示旧歌词。
     * 这里同步 matchedFile/matchedKey/matchVersion、写强制绑定、认领显式选择，
     * 让所有消费方与用户的选择保持一致。
     */
    private fun adoptExplicitLyricChoice(file: File) {
        val title = MusicProgress.title?.trim().takeUnless { it.isNullOrEmpty() }
        val artist = MusicProgress.artist?.trim().orEmpty()
        Session.lyricLines = lyricLines
        Session.matchedFile = file
        Session.matchState = LyricMatchState.MATCHED
        if (title != null) {
            Session.matchedKey = LyricBindings.keyOf(title, artist)
            // 候选文件名未必与歌名相似（「翻译版.lrc」「(1).lrc」等），
            // 用 put() 会被 nameMatches 拒掉，重新匹配时就会回到旧文件。
            LyricBindings.putForce(this, title, artist, LyricBindings.bindingValue(this, file))
            // 作者字段晚到时 key 会变，补一份仅标题的绑定兜底。
            if (artist.isNotEmpty()) LyricBindings.putForce(this, title, "", LyricBindings.bindingValue(this, file))
            Session.explicitLyricTitle = title
            Session.explicitLyricPath = file.absolutePath
        }
        Session.matchVersion++
        prefs.lastLyricFile = file.absolutePath
        appliedMatchKey = Session.matchedKey
        appliedMatchVersion = Session.matchVersion
        // 歌曲信息还没就绪时挂起，等首次拿到标题再由 syncMatchedLyrics 认领
        manualSongPending = title == null
    }

    /**
     * 把通知监听服务的自动匹配结果（写入 Session）同步到主界面：
     * 匹配到新文件且与当前打开的不同时自动切换。
     */
    private fun syncMatchedLyrics() {
        if (!prefs.autoMatchLyrics) return
        // 切歌瞬间先清空歌词区：此时服务已把 Session 歌词作废（MATCHING 且无歌词行），
        // 但 matchedKey 可能为空 / 匹配结果尚未写回，下面的版本同步还触发不了。
        // 不清的话防抖与后台扫描期间列表里仍是上一首歌词。
        val songVersion = Session.songChangeVersion
        if (songVersion != appliedSongChangeVersion) {
            appliedSongChangeVersion = songVersion
            // 用户手动打开的文件（歌曲信息尚未就绪时挂起认领）不能被当作旧歌词清掉
            if (!manualSongPending && !isEditMode() &&
                (Session.matchState == LyricMatchState.MATCHING ||
                    Session.matchState == LyricMatchState.FETCHING_ONLINE) &&
                Session.lyricLines.isNullOrEmpty() &&
                (lyricLines.isNotEmpty() || docText.isNotEmpty())
            ) {
                showEmptyState()
            }
        }
        val key = Session.matchedKey ?: return
        val version = Session.matchVersion
        if (manualSongPending) {
            // 手动打开时歌曲未知：把首次识别到的歌曲认领为"用户手动选择的歌曲"，
            // 不覆盖用户刚打开的文件；同时把当前打开的文件记录为该歌曲的绑定歌词。
            // 此刻自动匹配可能刚写入旧文件的结果，必须一并纠正 Session，
            // 否则桌面歌词显示旧歌词、下次版本变化又把主界面切回去。
            manualSongPending = false
            val title = MusicProgress.title?.trim().takeUnless { it.isNullOrEmpty() }
            val artist = MusicProgress.artist?.trim().orEmpty()
            internalFile?.let { f ->
                if (title != null) {
                    LyricBindings.putForce(this, title, artist, LyricBindings.bindingValue(this, f))
                    if (artist.isNotEmpty()) LyricBindings.putForce(this, title, "", LyricBindings.bindingValue(this, f))
                    Session.matchedFile = f
                    Session.lyricLines = lyricLines
                    Session.explicitLyricTitle = title
                    Session.explicitLyricPath = f.absolutePath
                    Session.matchVersion++
                    prefs.lastLyricFile = f.absolutePath
                }
            }
            appliedMatchKey = key
            appliedMatchVersion = Session.matchVersion
            return
        }
        if (key == appliedMatchKey && version == appliedMatchVersion) return
        if (isEditMode()) return
        appliedMatchKey = key
        appliedMatchVersion = version
        val file = Session.matchedFile
        if (file == null) {
            // 匹配结束仍无结果：清掉上一首歌的歌词，让歌词区显示「无匹配歌词」。
            // 只在匹配相关状态下处理，避免删除文件等其它路径顺带清空用户正在看的文档。
            if (Session.matchState == LyricMatchState.MATCHING ||
                Session.matchState == LyricMatchState.FETCHING_ONLINE ||
                Session.matchState == LyricMatchState.NO_MATCH
            ) {
                // 用户为当前歌曲显式选中的歌词必须保留，不因一次无结果被清掉。
                val explicitlyChosen = Session.explicitLyricPath?.let { path ->
                    path == internalFile?.absolutePath &&
                        Session.explicitLyricTitle == MusicProgress.title?.trim()
                } == true
                if (!explicitlyChosen && (lyricLines.isNotEmpty() || docText.isNotEmpty())) {
                    showEmptyState()
                }
            }
            return
        }
        if (currentName == file.name) return
        loadInternal(file)
        // loadInternal 里的 markManualOpen() 按「当前」歌曲信息登记 key，
        // 与本次同步的 Session.matchedKey 可能因元数据抖动略有出入；
        // 覆盖回本次的 key/version，避免下一帧误判未同步而每 50ms 重复加载
        appliedMatchKey = key
        appliedMatchVersion = Session.matchVersion
    }

    /** 由"手动添加歌词"入口发起的文件选择：选定后强制写入歌曲-歌词绑定。 */
    private var pendingManualAdd = false

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                // 用户取消选择：清空手动添加标记
                pendingManualAdd = false
                return@registerForActivityResult
            }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            load(uri)
        }

    /** 发起"手动添加歌词"的文件选择（系统文件选择器）。 */
    private fun startManualAddPick() {
        runCatching {
            openLauncher.launch(arrayOf("text/*", "*/*"))
        }.onFailure { pendingManualAdd = false }
    }

    /** 目录选择器：用于"其它位置"保存，获取持久化的目录读写权限，支持直接保存。 */
    private val treeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri == null) {
                pendingCreateNewAfterSave = false
                return@registerForActivityResult
            }
            val name = pendingSaveAsName ?: return@registerForActivityResult
            pendingSaveAsName = null
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            // 记录最近目录（tree URI 支持直接写入），保存完整路径便于区分同名目录
            runCatching {
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val rawPath = treeDocId.split(':', limit = 2).lastOrNull().orEmpty()
                val fullPath = if (treeDocId.startsWith("primary:")) {
                    if (rawPath.isEmpty()) "/内部存储" else "/内部存储/$rawPath"
                } else {
                    "/$rawPath"
                }
                val dirName = rawPath.substringAfterLast('/', missingDelimiterValue = "")
                    .ifEmpty { getString(R.string.recent_dir_internal) }
                prefs.addRecentDir(treeUri.toString(), dirName, fullPath)
            }
            saveToTree(treeUri, name)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        appearance = AppearancePrefs(this)
        appliedThemeVersion = App.themeVersion
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge()
        setSupportActionBar(binding.toolbar)
        // 使用自定义可点击标题：点击弹出匹配到的歌词列表或最近打开文件列表（由设置决定）
        supportActionBar?.setDisplayShowTitleEnabled(false)
        // 长文件名超出可用宽度时循环滚动，短名字保持静止（细节见 MarqueeTextView）
        titleTextView = MarqueeTextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(0, 0, 24, 0)
            setOnClickListener {
                if (prefs.titleListMode == Prefs.TITLE_LIST_RECENT) {
                    showRecentFilesPopup(it)
                } else {
                    showMatchedFilesPopup(it)
                }
            }
        }
        binding.toolbar.addView(
            titleTextView,
            androidx.appcompat.widget.Toolbar.LayoutParams(
                androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.START
            )
        )
        // 给标题留出右侧菜单图标的空间：宽度确定后滚动区域正好落在图标左边
        binding.toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyTitleWidthLimit()
        }
        updateTitle()

        setupLyricsRecycler()
        setupEditor()
        setupEditToolbar()
        setupKeyboardObserver()
        setupMediaControls()
        applyMoreSettingsUi()
        applyHomeAppearance()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    immersive -> applyImmersive(false)
                    isEditMode() && dirty -> confirmExit()
                    isEditMode() -> exitEditMode()
                    else -> finish()
                }
            }
        })

        val restoring = savedInstanceState != null && Session.hasDocument
        if (restoring) {
            restoreSession()
        } else {
            val handled = handleIntent(intent)
            if (!handled) {
                if (intent?.getBooleanExtra(EXTRA_RESTORE_LAST, false) == true) {
                    restoreForDesktopPanel()
                } else {
                    showEmptyState()
                }
            }
        }

        // 首次使用引导：未授予通知使用权时弹窗引导
        maybePromptNotificationAccess()

        // 从桌面歌词面板的"手动添加歌词"入口拉起：直接进入文件选择
        if (intent?.getBooleanExtra(EXTRA_MANUAL_ADD_LYRIC, false) == true) {
            pendingManualAdd = true
            startManualAddPick()
        }
    }

    override fun onResume() {
        super.onResume()
        // 兜底：正常情况下 setDefaultNightMode 已在后台把本页按新主题重建完毕，
        // 回来时版本号一致、直接显示新主题。仅当后台重建未发生（异常时序）时，
        // 在这里按主题版本补一次重建；先冻结旧画面过渡，重建后由 ThemedActivity 揭开。
        if (appliedThemeVersion != App.themeVersion) {
            appliedThemeVersion = App.themeVersion
            App.freezeForThemeSwitch(this)
            recreate()
            return
        }
        // 幂等确认主题（通常无需任何操作，不会触发额外重建）
        App.applyThemeImmediate(this, prefs.themeMode)
        // 返回前台时重新套用「应用外观」页的全部设置（主题/排版/动效/背景/控制条）
        applyHomeAppearance()
        // 从其它页面返回时桌面歌词可能已被启停，强制重算开关高亮
        overlayToggleStateApplied = null
        updateMediaControlsOverlayToggle(force = true)
        // 刷新双语歌词高亮行偏好
        adapter.setBilingualHighlight(prefs.bilingualHighlight)
        // 设置页可能改动了影响解析结果的选项（显示不带时间戳的行），按新设置重解析当前文档
        refreshLyricsIfOptionsChanged()
        // 在线搜索歌词选用后的结果
        consumePendingOnlineLyric()
        // 先清掉可能残留的旧链再启动：tickRunnable 内部无条件 postDelayed 自循环，
        // 分屏 / 多窗口 / 部分 ROM 的 onResume 重入会让两条链永久并行，进度更新
        // 在同一帧交错，与蒙版 setProgress 的时间戳短路叠加时会吞掉重绘。
        uiHandler.removeCallbacks(tickRunnable)
        uiHandler.post(tickRunnable)
        invalidateOptionsMenu()
        // 主题切换后重新确认状态栏图标的明暗（深/浅主题底不同）
        updateStatusBarIcons()
        // 启动应用进入前台时，按「启用桌面歌词」开关 / 「启动时自动打开桌面歌词」恢复悬浮窗
        recoverDesktopLyricsInForeground()
        // 用户可能刚在设置中开启了通知使用权，请求系统重新绑定监听服务
        if (MusicNotificationListenerService.isEnabled(this)) {
            if (MusicNotificationListenerService.isConnected()) {
                // 诊断：从锁屏回到前台必走这里；与 NLS 的 connected/disconnected
                // 日志按 seq 配对，确认 requestRebind 是否真的触发了断连重连。
                val at = SystemClock.elapsedRealtime()
                val seq = Diag.nextRebind("MainActivity.onResume", at)
                LogCapture.i(
                    "NLS",
                    "rebind requested seq=$seq from=MainActivity.onResume at=$at " +
                        "connected=true screen=${Diag.lastScreenEvent} " +
                        "sinceScreen=${Diag.sinceScreenEvent(at)}ms " +
                        "sinceScreenOn=${Diag.sinceScreenOn(at)}ms " +
                        "sinceConnect=${Diag.sinceConnect(at)}ms"
                )
                runCatching {
                    NotificationListenerService.requestRebind(
                        MusicNotificationListenerService.component(this)
                    )
                }
            } else {
                // 已授权但未连接（常见于应用更新后绑定被系统回收）：强制恢复绑定
                MusicNotificationListenerService.recover(this)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(tickRunnable)
        persistPosition()
    }

    /**
     * 启动应用进入前台时恢复桌面歌词：
     * 桌面歌词启动状态为开启就必须把被系统清理掉的悬浮窗补回来；
     * 状态为关闭时，只有「启动时自动打开桌面歌词」开启才自动打开。
     *
     * 清理后台不改写「启用桌面歌词」开关，这里（用户主动打开应用）正是靠它
     * 判断是否自启动；进程保护关闭时后台不会自动恢复，前台这条路径仍保留。
     * 保护开启时 [App] 在 Application.onCreate 里后台拉起前台服务又可能被系统拒绝，
     * 所以必须在这里（前台）补一次，保证「开关开着就一定能看到悬浮窗」。
     */
    private fun recoverDesktopLyricsInForeground() {
        DesktopLyricsService.recoverInForeground(this)
    }

    /**
     * 设置在返回前台时可能已被改动（如「显示不带时间戳的行」），
     * 解析器结果会随之变化，需要按新选项重新解析当前文档并刷新列表。
     */
    private fun refreshLyricsIfOptionsChanged() {
        if (lastParseVersion == Session.parseVersion) return
        lastParseVersion = Session.parseVersion
        if (docText.isEmpty()) return
        lyricLines = LyricsParser.parse(docText)
        Session.lyricLines = lyricLines
        adapter.submit(lyricLines)
        currentIndex = -1
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_MANUAL_ADD_LYRIC, false)) {
            pendingManualAdd = true
            startManualAddPick()
            return
        }
        if (!handleIntent(intent)) {
            // 从桌面歌词面板拉起且当前没有打开文档时，恢复正在播放歌曲的歌词
            if (intent.getBooleanExtra(EXTRA_RESTORE_LAST, false) && currentName == null) {
                restoreForDesktopPanel()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(tickRunnable)
        keyboardLayoutListener?.let {
            runCatching { window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        }
        keyboardLayoutListener = null
    }

    // ---------------------------------------------------------------- 模式

    private fun isPlayingMode(): Boolean = binding.editContainer.visibility != View.VISIBLE
    private fun isEditMode(): Boolean = binding.editContainer.visibility == View.VISIBLE

    private fun enterEditMode(targetLineIndex: Int = -1) {
        // 空白处长按触发时列表会立刻隐藏，抬手事件可能不再派发到 RecyclerView；
        // 这里强制清理触摸/长按状态，否则退出编辑后自动滚动会被永久暂停。
        userTouchingLyrics = false
        cancelBlankLongPress(binding.lyricsRecycler)
        binding.lyricsRecycler.visibility = View.GONE
        binding.lyricDisplayRoot.visibility = View.GONE
        binding.editContainer.visibility = View.VISIBLE
        // 编辑模式下 tickRunnable 不再走 updateMediaControls()，必须在这里立即收起
        // 底部媒体控制条，否则上一帧残留的控制条会一直盖在编辑器上。
        applyMediaControlsVisibility()
        binding.editorText.setText(docText)
        // 上一轮退出瞬间可能残留 cursorVisible=false（滚动恢复任务跨帧执行），
        // 进入编辑模式时强制恢复，保证光标可见；同时清掉上一轮残留的恢复任务与节流时间戳
        binding.editorText.removeCallbacks(restoreCursorAfterScroll)
        binding.editorText.removeCallbacks(clearCursorOutOfView)
        binding.editorText.allowCursorOutOfView = false
        lastCursorKeepaliveAt = 0L
        lastEditSelection = 0
        binding.editorText.isCursorVisible = true
        binding.editorText.textSize = prefs.editFontSize.toFloat()
        applyEditWrap()
        updateEditAssistUi()
        binding.editFontSize.progress = prefs.editFontSize
        updateSeekStepButtons()
        applyMoreSettingsUi()
        undoStack.clear()
        redoStack.clear()
        pendingOp = null
        decimalModifySnapshot = null
        uiHandler.removeCallbacks(commitRunnable)
        dirty = false

        // 定位到长按的那一行（或空白处长按兜底选出的最近一行）
        val offset = editOffsetForLine(targetLineIndex)
        if (offset >= 0) {
            binding.editorText.post {
                val sel = offset.coerceIn(0, binding.editorText.length())
                val layout = binding.editorText.layout
                if (layout != null) {
                    val lineNum = layout.getLineForOffset(sel)
                    val y = layout.getLineTop(lineNum) - binding.editorText.height / 3
                    binding.editorText.scrollTo(0, y.coerceAtLeast(0))
                }
                binding.editorText.setSelection(sel)
                scheduleCursorRestore(binding.editorText)
            }
        }

        binding.editorText.requestFocus()
        invalidateOptionsMenu()
        maybeShowEditTip()
    }

    /**
     * 每次进入编辑状态时提醒：打轴/调时间戳要用支持毫秒级进度的专业播放器。
     * 勾选「不再询问」后持久化，之后进入编辑状态不再弹窗。
     */
    private fun maybeShowEditTip() {
        if (prefs.editTipDontAsk || isFinishing || isDestroyed) return
        // 「新建歌词」是从另一个弹窗的按钮回调里直接进入编辑状态的，
        // 延后一帧再显示，避免与上一个弹窗的关闭动画叠加。
        uiHandler.post {
            if (isFinishing || isDestroyed || !isEditMode()) return@post
            val checkBox = CheckBox(this).apply {
                text = getString(R.string.edit_tip_dont_ask)
                setPadding(48, 8, 48, 8)
                setTextColor(resolvePrimaryTextColor())
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(checkBox)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.edit_tip_title)
                .setMessage(
                    getString(R.string.edit_tip_message) + "\n\n" +
                        getString(R.string.edit_tip_message_naming)
                )
                .setView(container)
                .setPositiveButton(R.string.edit_tip_ok) { _, _ ->
                    if (checkBox.isChecked) prefs.editTipDontAsk = true
                }
                // 点「我知道了」以外的方式关闭（返回键 / 点空白处）也尊重勾选
                .setOnDismissListener {
                    if (checkBox.isChecked) prefs.editTipDontAsk = true
                }
                .show()
        }
    }

    /**
     * 把解析器「统一换行符后」的字符偏移映射回 [docText] 原文坐标。
     * 原文可能是 CRLF，归一化后 \r\n 只占 1 个字符，直接换算会整体偏移。
     */
    private fun rawOffsetOf(normalizedOffset: Int): Int {
        var raw = 0
        var normalized = 0
        val n = docText.length
        while (raw < n && normalized < normalizedOffset) {
            if (docText[raw] == '\r' && raw + 1 < n && docText[raw + 1] == '\n') raw++
            normalized++
            raw++
        }
        return raw.coerceAtMost(n)
    }

    /**
     * 计算编辑模式下某歌词行的光标落点。
     *
     * 优先用解析器记录的 [LyricLine.sourceOffset]（空行、重复歌词、SRT 多行字幕
     * 都能精确定位，不再依赖"搜索正文前 8 个字"），再跳过行首空白和时间戳标签，
     * 让光标直接落在歌词正文起点。旧数据没有偏移时回退到正文搜索。
     */
    private fun editOffsetForLine(index: Int): Int {
        if (index !in lyricLines.indices) return -1
        val line = lyricLines[index]
        val normalizedOffset = line.sourceOffset
        var pos = if (normalizedOffset >= 0) {
            rawOffsetOf(normalizedOffset)
        } else {
            val probe = line.text.take(8)
            if (probe.isEmpty()) -1 else docText.indexOf(probe)
        }
        if (pos < 0) return -1
        pos = pos.coerceIn(0, docText.length)
        var p = pos
        while (p < docText.length && docText[p] != '\n') {
            val c = docText[p]
            if (c == ' ' || c == '\t' || c == '\r') {
                p++
                continue
            }
            if (c == '[' || c == '<') {
                val close = if (c == '[') ']' else '>'
                val end = docText.indexOf(close, p + 1)
                val nl = docText.indexOf('\n', p + 1)
                if (end > p && end < docText.length && (nl < 0 || end < nl)) {
                    p = end + 1
                    continue
                }
            }
            break
        }
        return p.coerceIn(0, docText.length)
    }

    private fun exitEditMode() {
        userTouchingLyrics = false
        binding.editorText.removeCallbacks(restoreCursorAfterScroll)
        binding.editorText.removeCallbacks(clearCursorOutOfView)
        binding.editorText.allowCursorOutOfView = false
        // 未保存的新歌词被丢弃：清掉待创建的文件名，不留下任何残留状态
        pendingNewLyricName = null
        decimalModifySnapshot = null
        hideKeyboard()
        binding.editContainer.visibility = View.GONE
        binding.lyricDisplayRoot.visibility = View.VISIBLE
        binding.lyricsRecycler.visibility = View.VISIBLE
        // 回到播放模式时立即按外观设置恢复底部媒体控制条，不必等到下一帧 tick。
        applyMediaControlsVisibility()
        // 重新解析歌词（编辑后可能改变了时间轴）
        docText = binding.editorText.text?.toString().orEmpty()
        lyricLines = LyricsParser.parse(docText)
        Session.lyricLines = lyricLines
        adapter.submit(lyricLines)
        invalidateOptionsMenu()
    }

    // ---------------------------------------------------------------- 歌词列表

    private fun setupLyricsRecycler() {
        adapter = LyricAdapter(
            onLongPress = { index -> enterEditMode(index) },
            onLineClick = { line ->
                // 点击歌词行：只发出进度跳转，目标就是该行的原始时间戳。
                // 「毫秒信息不可信」开关只影响计时，不再把歌词文件的 offset
                // 算进跳转目标；line.time 只用于点击瞬间的高亮钉行。
                val target = line.time.coerceAtLeast(0L)
                lastSeekRequestTime = SystemClock.elapsedRealtime()
                showSeekToast(target)
                // 只发跳转指令；歌词时间由应用自己从目标（或整秒）开始计算，
                // 不再等播放器回报落点。line.time 只用于点击瞬间的高亮钉行
                MusicNotificationListenerService.seekTo(target, line.time)
            }
        )
        adapter.setBilingualHighlight(prefs.bilingualHighlight)
        layoutManager = LinearLayoutManager(this)
        binding.lyricsRecycler.layoutManager = layoutManager
        binding.lyricsRecycler.adapter = adapter
        // 禁用默认 Item 动画，避免 notifyItemChanged 时的淡入淡出造成"拖影"
        binding.lyricsRecycler.itemAnimator = null
        // 歌词列表整体重建（切歌自动匹配、重新解析、打开/清空文件）后，复用或新附着的
        // View 会带着上一首歌遗留的 scale/alpha/translationY。统一在 onChanged 之后
        // 复位一次；切歌瞬间若进度仍在首行之前，靠这里保证前奏期间所有行外观一致。
        // 只响应 notifyDataSetChanged，不响应逐帧的 notifyItemRangeChanged。
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                binding.lyricsRecycler.post { applyHomeMotion() }
            }
        })

        // 触摸期间暂停自动滚动：程序滚动会取消用户正在进行的点击手势，
        // 导致"点击歌词无法跳转进度"。按下时暂停并立即停止正在进行的
        // 平滑滚动/惯性滑动（否则目标行会从指尖滑走，点击手势被系统判定
        // 为"视图移出手指"而取消），抬起/取消时恢复。
        //
        // 同时兜底实现"长按空白处进入编辑"：按下点未落在任何歌词行上时
        // 启动手动长按检测（RecyclerView 自身不触发 setOnLongClickListener），
        // 超过系统长按阈值且未移动出触摸阈值即进入编辑模式并定位最近一行。
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        binding.lyricsRecycler.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        userTouchingLyrics = true
                        // 惯性/平滑滚动进行中按下：立刻停住列表，保证按下与抬起
                        // 之间被点击的行不再移动，点击手势才能正常完成
                        if (rv.scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
                            rv.stopScroll()
                        }
                        // 空白处长按兜底：按压点下方没有歌词行时启动长按检测
                        cancelBlankLongPress(rv)
                        if (rv.findChildViewUnder(e.x, e.y) == null) {
                            blankDownX = e.x
                            blankDownY = e.y
                            val pressY = e.y
                            val action = Runnable {
                                blankLongPressAction = null
                                enterEditModeFromBlankPress(rv, pressY)
                            }
                            blankLongPressAction = action
                            rv.postDelayed(
                                action, ViewConfiguration.getLongPressTimeout().toLong()
                            )
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 手指移动超出触摸阈值（准备开始滚动）时取消空白长按
                        val action = blankLongPressAction
                        if (action != null) {
                            val dx = e.x - blankDownX
                            val dy = e.y - blankDownY
                            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                                cancelBlankLongPress(rv)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        userTouchingLyrics = false
                        cancelBlankLongPress(rv)
                    }
                }
                return false
            }
        })
        // 第二重保险：一旦列表开始被拖动滚动就取消空白长按，
        // 避免"按住屏幕滑动但没超过触摸阈值"被误判为长按进入编辑。
        binding.lyricsRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    cancelBlankLongPress(recyclerView)
                }
            }
        })
        // 动效即时生效的第二重保证：RecyclerView 复用或重新布局时，新附着的行
        // 直接按当前配置落到目标变换；否则修改动效后滚动一次列表，新行又变回无动效。
        // 进度尚未进入第一行（currentIndex == -1）时同样必须套用：此时目标变换是
        // 「无焦点行」的中性值，用来把复用 View 上残留的 scale/alpha 复位；
        // 旧实现在这里直接 return，导致切歌后个别行沿用上一首歌的远景缩放与虚化，
        // 表现为「歌曲还没唱到歌词时，某一行又小又暗」。
        binding.lyricsRecycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    if (!::layoutManager.isInitialized) return
                    val position = layoutManager.getPosition(view)
                    if (position == RecyclerView.NO_POSITION) return
                    applyMotionToChild(view, position, animate = false)
                }
                override fun onChildViewDetachedFromWindow(view: View) = Unit
            }
        )
        // 列表可用高度变化（横竖屏、系统栏显隐、媒体控制条展开）时重算上下留白。
        // 只在高度真的变化时触发，setPadding 引起的重新布局不会再进入这里，避免回环。
        binding.lyricsRecycler.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) applyLyricVerticalPadding()
        }
    }

    /**
     * 「正在播放的高亮行位置」以列表垂直居中为 0，负值向上、正值向下，单位为歌词行。
     * 默认 -1（比原先的居中默认位置向上高一行）。
     *
     * [applyLyricVerticalPadding] 在「允许上下空白」开启时，把 [highlightAnchorTopPx]
     * 作为顶部内边距、把剩余空间作为底部内边距：这样 `scrollToPositionWithOffset(index, 0)`
     * 恰好让**任意**一行（包括第一行与最后一行）落在高亮行位置上。关闭时内边距归零，
     * 歌词紧贴列表上下边缘，第一行/最后一行不再能滚到高亮行位置。
     */
    private fun lyricRowHeightPx(): Int {
        if (::layoutManager.isInitialized) {
            val current = layoutManager.findViewByPosition(currentIndex)
            val child = current ?: layoutManager.getChildAt(0)
            if (child != null && child.height > 0) return child.height
        }
        val d = resources.displayMetrics.density
        // 兜底估值：当前行字号 = 原文字号 + 高亮放大值，上下各 10dp padding，外加行间距额外量
        val highlightExtra = if (appearance.highlightEnlarge) appearance.highlightEnlargeSp else 0
        return ((appearance.homeFontSize + highlightExtra) * d * 1.25f + 20 * d +
            appearance.homeLineSpacing * d).toInt().coerceAtLeast((24 * d).toInt())
    }

    /** 高亮行顶部相对歌词列表顶部的像素位置。 */
    private fun highlightAnchorTopPx(): Int {
        val h = binding.lyricsRecycler.height
        if (h <= 0) return 0
        val row = lyricRowHeightPx()
        val centerTop = (h - row) / 2
        val offset = appearance.highlightRowOffset
        return (centerTop + offset * row).coerceIn(0, (h - row).coerceAtLeast(0))
    }

    /** 按「允许上下空白」与高亮行位置套用歌词列表上下内边距。 */
    private fun applyLyricVerticalPadding() {
        val rv = binding.lyricsRecycler
        if (rv.height <= 0) return
        val top: Int
        val bottom: Int
        if (appearance.allowVerticalBlank) {
            top = highlightAnchorTopPx()
            bottom = (rv.height - top - lyricRowHeightPx()).coerceAtLeast(0)
        } else {
            top = 0
            bottom = 0
        }
        if (rv.paddingTop != top || rv.paddingBottom != bottom) {
            rv.setPadding(rv.paddingLeft, top, rv.paddingRight, bottom)
            // 内边距变了，当前行的落点也要跟着回到高亮行位置。
            // 开启留白时 anchor == paddingTop（offset 为 0）；关闭留白时 paddingTop 为 0，
            // 必须把 anchor 本身作为 offset，否则当前行会被顶到列表最上方。
            if (::layoutManager.isInitialized && currentIndex in 0 until lyricLines.size) {
                layoutManager.scrollToPositionWithOffset(
                    currentIndex, highlightAnchorTopPx() - rv.paddingTop
                )
            }
        }
    }

    /** 用户正在触摸歌词列表：触摸期间暂停自动滚动，避免程序滚动取消点击手势。 */
    private var userTouchingLyrics = false

    // ------------------------------------------------ 空白处长按兜底（进入编辑）

    /** 空白处长按兜底：待触发的"进入编辑"任务（同一时刻最多一个）。 */
    private var blankLongPressAction: Runnable? = null

    /** 空白处长按兜底：按下时的坐标，用于移动超出阈值时取消。 */
    private var blankDownX = 0f
    private var blankDownY = 0f

    /** 取消待执行的空白处长按任务。 */
    private fun cancelBlankLongPress(rv: RecyclerView) {
        blankLongPressAction?.let { rv.removeCallbacks(it) }
        blankLongPressAction = null
    }

    /**
     * 空白处长按兜底：进入编辑模式并定位到距离按压点最近的一行歌词。
     * 按压点周围没有任何可见行时（如列表为空）回退到当前高亮行。
     */
    private fun enterEditModeFromBlankPress(rv: RecyclerView, y: Float) {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val pos = layoutManager.getPosition(child)
            if (pos !in lyricLines.indices) continue
            val center = child.top + child.height / 2f
            val dist = Math.abs(center - y)
            if (dist < bestDist) {
                bestDist = dist
                best = pos
            }
        }
        // 无可见行可参照：回退当前高亮行（可能也没有，则 -1 = 不定位直接进入编辑）
        if (best < 0 && currentIndex in lyricLines.indices) best = currentIndex
        enterEditMode(best)
    }

    /**
     * 播放模式下按自动匹配状态在歌词区居中显示占位提示：
     * 正在匹配时覆盖显示「正在匹配歌词」，无匹配且正在在线获取时显示
     * 「无匹配歌词，正在在线获取歌词」（两者都会隐藏上一首歌的歌词），
     * 匹配结束仍无结果且没有歌词行时显示「无匹配歌词」，其余情况隐藏。
     * 提示 View 不拦截触摸，长按空白处仍可进入编辑模式。
     */
    private fun updateLyricStatusHint() {
        val hintView = binding.lyricStatusHint
        val state = Session.matchState
        val matching = state == LyricMatchState.MATCHING ||
            state == LyricMatchState.FETCHING_ONLINE
        val text = when {
            // 系统里完全没有可用的播放来源：优先说明「没有检测到播放」，
            // 不能把原因归到「没有歌词」上。
            lyricLines.isEmpty() && !MusicProgress.hasPlaybackSession ->
                getString(R.string.desktop_no_playback)
            // 正在匹配 / 正在在线获取时优先显示状态提示，哪怕还残留着上一首歌的歌词
            state == LyricMatchState.FETCHING_ONLINE ->
                getString(R.string.desktop_fetching_online_lyric)
            matching -> getString(R.string.desktop_matching_lyric)
            lyricLines.isEmpty() && state == LyricMatchState.NO_MATCH ->
                getString(R.string.desktop_no_match_lyric)
            else -> null
        }
        // 正在匹配时把旧歌词隐藏，避免提示文字与歌词重叠；匹配结束立即恢复
        val recyclerVisibility = if (matching) View.INVISIBLE else View.VISIBLE
        if (binding.lyricsRecycler.visibility != recyclerVisibility) {
            binding.lyricsRecycler.visibility = recyclerVisibility
        }
        if (text == null) {
            if (hintView.visibility != View.GONE) hintView.visibility = View.GONE
            return
        }
        if (hintView.text?.toString() != text) hintView.text = text
        if (hintView.visibility != View.VISIBLE) hintView.visibility = View.VISIBLE
    }

    private fun updatePlayback() {
        if (lyricLines.isEmpty()) return
        releaseLyricFreezeIfPassedNextLine()
        // 点击后由应用自身计时推进；「毫秒信息不可信」应用的整秒自计时期间会冻结在目标
        var pos = MusicProgress.displayPositionMs()
        // 循环播放处理：解除 duration 钳制后估算可能超过总时长，
        // 取模把超出部分映射回 0~duration 区间，匹配到循环后的正确歌词行
        val dur = MusicProgress.durationMs
        if (dur > 0L && pos > dur) {
            pos = pos % dur
        }
        // 应用内 seek 宽限期内对齐到目标位置：播放器 seek 落点常略早于目标
        // （关键帧对齐），按落点找行会命中上一行，表现为"点击跳转高亮上一行"。
        // 宽限期结束后播放已自然越过目标行，不再需要对齐。
        // seek 宽限期内把进度对齐到目标位置，所有应用统一处理；
        // 「毫秒信息不可信」开关只影响计时，不影响这里的显示对齐。
        if (SystemClock.elapsedRealtime() < MusicProgress.seekUiTargetUntil &&
            pos < MusicProgress.seekUiTargetMs
        ) {
            pos = MusicProgress.seekUiTargetMs
        }
        // 整体偏移：正值延后歌词、负值提前，
        // 逐行高亮与逐字蒙版共用这一份进度
        pos = (pos - PlaybackOffset.delayMs(prefs)).coerceAtLeast(0L)
        var idx = findLineIndex(pos)
        // seek 未落地期间把高亮钉在被点击的这一行：关键帧对齐或整体偏移
        // 都可能让修正后的进度略早于目标行，直接按进度找行
        // 会命中上一行（或其它行），出现「点了 A 却高亮 B」。同一时间戳的
        // 双语组仍交给 isHighlighted 按设置决定组内哪几行高亮。
        if (MusicProgress.isSeekPending()) {
            val clickedTime = MusicProgress.seekPendingLineMs
            if (clickedTime >= 0L) {
                val pinned = findLineIndex(clickedTime)
                if (pinned in lyricLines.indices && lyricLines[pinned].time == clickedTime) {
                    idx = pinned
                }
            }
        }
        // 防回跳：进度估算依赖播放器上报的快照+时间基准，部分播放器的上报
        // 在行边界附近抖动，会使高亮在当前行与相邻行间来回跳。回退幅度小
        // 且非应用内发起的 seek 时保持当前行；点击歌词行/时间戳跳转后立即响应。
        if (idx >= 0 && idx < currentIndex && currentIndex < lyricLines.size) {
            val recentSeek = SystemClock.elapsedRealtime() - lastSeekRequestTime < 1_500L
            val backAmount = lyricLines[currentIndex].time - pos
            if (!recentSeek && backAmount in 1..LINE_FLICKER_GUARD_MS) {
                idx = currentIndex
            }
        }
        if (idx != currentIndex) {
            val prevIndex = currentIndex
            currentIndex = idx
            adapter.updateCurrent(idx, pos, prefs.wordByWord, prefs.forceWordByWord)
            scrollToCenter(idx)
            // 歌词前进到更后的行且时长缺失（网络波动导致元数据缺时长）：
            // 触发一次时长兜底重取（内部限流），恢复时长钳制与匹配
            if (idx > prevIndex) MusicNotificationListenerService.refreshMissingDuration()
        } else {
            // 不切行时也要把开关状态推下去：逐字动画开关切换后立即生效，
            // 否则当前行会保持上一次的蒙版状态直到切到下一行。
            adapter.updateCurrent(idx, pos, prefs.wordByWord, prefs.forceWordByWord)
        }
    }

    /** 应用内发起 seek 的时刻：seek 后短时间内允许高亮立即回退到目标行。 */
    private var lastSeekRequestTime = 0L

    /**
     * 点击歌词产生的高亮冻结：若播放时间已经推进到下一行（下一句真正不同的
     * 时间戳），继续钉住被点击行会把下一行的高亮整段跳过。这里按应用自己的
     * 实时进度判断，一旦越过下一行起点就强行解除全部冻结，让高亮跟上进度。
     *
     * 暂停时不解除：暂停态本来就是静止的，冻结高亮符合预期。
     */
    private fun releaseLyricFreezeIfPassedNextLine() {
        if (!MusicProgress.isPlaying) return
        val target = MusicProgress.lyricFreezeTargetMs()
        if (target < 0L) return
        val pinnedIdx = findLineIndex(target)
        if (pinnedIdx !in lyricLines.indices) return
        // 双语组共用一个时间戳：跳过整组，找下一句真正不同的歌词
        var nextIdx = pinnedIdx + 1
        while (nextIdx < lyricLines.size && lyricLines[nextIdx].time == lyricLines[pinnedIdx].time) {
            nextIdx++
        }
        val nextTime = lyricLines.getOrNull(nextIdx)?.time ?: return
        var raw = MusicProgress.currentPositionMs()
        val dur = MusicProgress.durationMs
        if (dur > 0L && raw > dur) raw %= dur
        raw = (raw - PlaybackOffset.delayMs(prefs)).coerceAtLeast(0L)
        if (raw >= nextTime) MusicProgress.releaseLyricFreeze()
    }

    /**
     * 点击歌词行 / 时间戳后的跳转提示：只显示应用自己使用的跳转目标。
     * 不再抓播放器 PlaybackState 的「实际进度」——快照位置和时间戳经常偏移
     * 几百毫秒，补进提示里既不准确，也会误导逐字动画的计时判断。
     */
    private fun showSeekToast(targetMs: Long) {
        if (!prefs.showSeekToast) return
        UiTips.show(this, getString(R.string.seek_to, LyricsParser.formatTimePrecise(targetMs)))
    }

    private fun findLineIndex(positionMs: Long): Int {
        var lo = 0
        var hi = lyricLines.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lyricLines[mid].time <= positionMs) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /**
     * 把指定行滚动到「正在播放的高亮行位置」。
     *
     * 位置由 [highlightAnchorTopPx] 给出（默认是列表中线再往上一行）。无动画路径用
     * `scrollToPositionWithOffset(index, anchor - paddingTop)`：LinearLayoutManager 会把
     * 目标行顶部放在 `paddingTop + offset`，正好等于 anchor。平滑滚动路径则覆写
     * `calculateDtToFit`，把行顶部直接对齐到 anchor，两条路径结果一致。
     */
    private fun scrollToCenter(index: Int) {
        // 切行 / 切歌 / seek 回前奏时先刷新动效：即使 index < 0（进度还没到第一行）
        // 也要执行，applyHomeMotion 会把可见行复位到「无焦点行」的中性变换，
        // 清掉复用 View 上残留的缩放与透明度。旧实现在 index < 0 时直接 return，
        // 这些残留变换在整个前奏期间都得不到清理。
        applyHomeMotion()
        // 用户正在触摸列表时不自动滚动：程序滚动会打断/取消用户正在进行的点击手势
        if (userTouchingLyrics) return
        // 进度在首行时间戳之前：把列表带回开头，让「即将唱到的首行」停在高亮行位置，
        // 不沿用上一首歌 / 上一次 seek 留下的滚动位置。
        if (index < 0) {
            if (lyricLines.isNotEmpty()) scrollToLineAnchor(0)
            return
        }
        scrollToLineAnchor(index)
    }

    /** 把指定行顶部对齐到高亮行位置，按「平滑滚动 / 切行动画」设置选择路径。 */
    private fun scrollToLineAnchor(index: Int) {
        val anchor = highlightAnchorTopPx()
        val noAnimation = !appearance.motionEnabled || !appearance.easedScroll ||
            prefs.scrollAnimation == Prefs.ANIM_NONE
        if (noAnimation) {
            layoutManager.scrollToPositionWithOffset(
                index, anchor - binding.lyricsRecycler.paddingTop
            )
        } else {
            smoothScrollToCenter(index, anchor)
        }
    }

    private fun smoothScrollToCenter(index: Int, anchorTopPx: Int) {
        val durationMs = appearance.scrollDurationMs
        val smoothScroller = object : LinearSmoothScroller(this) {
            /** 对齐目标行顶部到高亮行位置，而不是默认的「内容区居中」。 */
            override fun calculateDtToFit(
                viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int,
            ): Int = anchorTopPx - viewStart

            /** 「应用外观 → 切行时长」直接约束平滑滚动耗时。 */
            override fun calculateTimeForScrolling(dx: Int): Int =
                maxOf(60, minOf(super.calculateTimeForScrolling(dx), durationMs))
        }
        smoothScroller.targetPosition = index
        layoutManager.startSmoothScroll(smoothScroller)
    }

    // ---------------------------------------------------------------- 编辑器

    private fun setupEditor() {
        // EditText 滚动（触摸拖动/惯性/程序 scrollTo）后，系统会挂起光标闪烁回调，
        // 滚动停止也不再恢复绘制，表现为"编辑光标丢失"。
        // 双路径汇入 scheduleCursorRestore：内容滚动监听 + 触摸手势结束；
        // 滚动进行中按节流间隔持续保活（光标全程可见），滚动停稳后再做最终恢复。
        binding.editorText.setOnScrollChangeListener { v, _, _, _, _ ->
            scheduleCursorRestore(v)
        }
        // 兜底：部分滚动路径（如部分厂商 ROM 的手势滚动）不触发 onScrollChanged，
        // 直接把整个触摸手势接入光标保活——按下、拖动中、抬起/取消都调度一次，
        // 拖动全程保持光标可见（内部按 CURSOR_KEEPALIVE_MS 节流，不会频繁重启闪烁）。
        // onTouchListener 返回 false，不干扰编辑与文本选择。
        binding.editorText.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> scheduleCursorRestore(v)
            }
            false
        }
        binding.editorText.addTextChangedListener(object : TextWatcher {
            private var before = ""
            private var afterLen = 0
            private var start = 0
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (suppressEditWatcher) return
                this.before = s?.substring(start, start + count).orEmpty()
                this.start = start
                this.afterLen = after
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (suppressEditWatcher) return
                val after = s?.substring(start, start + afterLen).orEmpty()
                if (before != after) {
                    recordEdit(start, before, after)
                    dirty = true
                    // 用户开始输入：恢复正常的光标跟随，输入点应保持在视野内
                    binding.editorText.allowCursorOutOfView = false
                    binding.editorText.removeCallbacks(clearCursorOutOfView)
                }
                applyTimestampSpans()
            }
        })
    }

    /**
     * 记录一次编辑操作。连续同类操作（连续输入或连续删除）会合并为一个撤销步骤，
     * 切换操作类型或空闲 600ms 后提交到撤销栈。
     */
    private fun recordEdit(start: Int, before: String, after: String) {
        val current = pendingOp
        if (current != null && canMerge(current, start, before, after)) {
            pendingOp = mergeOps(current, start, before, after)
        } else {
            // 操作类型变化或不连续：先提交上一个操作，再开始新操作
            current?.let { undoStack.add(it) }
            pendingOp = EditOp(start, before, after)
        }
        redoStack.clear()
        // 重置提交计时器
        uiHandler.removeCallbacks(commitRunnable)
        uiHandler.postDelayed(commitRunnable, COMMIT_DELAY)
    }

    /** 判断新操作是否能与待提交操作合并（同类且位置连续）。 */
    private fun canMerge(op: EditOp, start: Int, before: String, after: String): Boolean {
        val curInsert = op.after.isNotEmpty() && op.before.isEmpty()
        val curDelete = op.before.isNotEmpty() && op.after.isEmpty()
        val newInsert = after.isNotEmpty() && before.isEmpty()
        val newDelete = before.isNotEmpty() && after.isEmpty()
        return when {
            curInsert && newInsert -> start == op.start + op.after.length
            curDelete && newDelete -> {
                // 正向删除（Delete 键）：start 不变
                // 退格删除（Backspace）：start 比上次少 1
                start == op.start || start == op.start - 1
            }
            else -> false
        }
    }

    /** 合并两个连续同类操作。 */
    private fun mergeOps(op: EditOp, start: Int, before: String, after: String): EditOp {
        val curInsert = op.after.isNotEmpty() && op.before.isEmpty()
        return if (curInsert) {
            EditOp(op.start, op.before, op.after + after)
        } else {
            // 删除：根据正向/退格决定拼接方向
            if (start == op.start) {
                // 正向删除：旧删除在前，新删除在后
                EditOp(op.start, op.before + before, op.after)
            } else {
                // 退格删除：新删除（左侧）在前，旧删除在后
                EditOp(start, before + op.before, op.after)
            }
        }
    }

    /** 将待提交操作立即入栈（撤销/重做前调用，确保最新操作可撤销）。 */
    private fun commitPending() {
        uiHandler.removeCallbacks(commitRunnable)
        pendingOp?.let {
            undoStack.add(it)
            pendingOp = null
        }
    }

    private fun setupEditToolbar() {
        binding.btnUndo.setOnClickListener { undo() }
        binding.btnRedo.setOnClickListener { redo() }
        binding.btnWrap.setOnClickListener {
            editWrap = !editWrap
            applyEditWrap()
            toast(if (editWrap) R.string.wrap_on else R.string.wrap_off)
        }
        binding.editFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.editFontSize = progress
                    binding.editorText.textSize = progress.toFloat()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.btnSave.setOnClickListener { saveCurrent() }
        binding.btnSaveAs.setOnClickListener { promptSaveAsName() }
        binding.btnTimestamp.setOnClickListener { applyOrUpdateTimestamp() }
        binding.btnRewind5.setOnClickListener { seekBy(-secondsToMs(prefs.seekRewindBigSec)) }
        binding.btnRewind1.setOnClickListener { seekBy(-secondsToMs(prefs.seekRewindSmallSec)) }
        binding.btnPlayPause.setOnClickListener {
            // 服务未连接 / 没有可用媒体会话时给出明确提示，避免点击后完全静默
            if (!MusicNotificationListenerService.togglePlayPause()) {
                toast(R.string.play_pause_no_session)
                return@setOnClickListener
            }
            // 立即切换到点击后的预期文字，不等状态回调 / 每秒扫描刷新
            playPauseOptimisticLabel = getString(
                if (MusicProgress.isPlaying) R.string.resume_playback else R.string.pause_playback
            )
            playPauseOptimisticUntil = SystemClock.elapsedRealtime() + 1200L
            playPauseOptimisticLabel?.let { binding.btnPlayPause.text = it }
        }
        binding.btnForward1.setOnClickListener { seekBy(secondsToMs(prefs.seekForwardSmallSec)) }
        binding.btnForward5.setOnClickListener { seekBy(secondsToMs(prefs.seekForwardBigSec)) }
        binding.btnPrevLine.setOnClickListener { moveCursorLine(-1) }
        binding.btnNextLine.setOnClickListener { moveCursorLine(1) }
        binding.btnGlobalOffset.setOnClickListener { showGlobalOffsetDialog() }
        binding.btnSeekStepSettings.setOnClickListener { showSeekStepsDialog() }
        binding.btnMoreSettings.setOnClickListener { showMoreSettingsDialog() }
        binding.btnOpenKeyboard.setOnClickListener { toggleKeyboard() }
        binding.btnAddBlankAbove.setOnClickListener { addBlankLineAbove() }
        binding.btnAddBlankBelow.setOnClickListener { addBlankLineBelow() }
        binding.btnDeleteAllBlankLines.setOnClickListener { deleteAllBlankLines() }
        binding.btnDeleteCurrentLine.setOnClickListener { deleteCurrentLine() }
        updateSeekStepButtons()
    }

    private fun applyEditWrap() {
        binding.editorText.setHorizontallyScrolling(!editWrap)
        binding.btnWrap.text = getString(if (editWrap) R.string.wrap_on else R.string.wrap_off)
        binding.btnWrap.alpha = if (editWrap) 1f else 0.5f
    }

    private fun undo() {
        commitPending()
        val op = undoStack.pollLast() ?: return
        val text = binding.editorText.text ?: return
        suppressEditWatcher = true
        text.replace(op.start, op.start + op.after.length, op.before)
        suppressEditWatcher = false
        redoStack.push(op)
        binding.editorText.setSelection(op.start + op.before.length)
        dirty = true
        applyTimestampSpans()
    }

    private fun redo() {
        val op = redoStack.pollLast() ?: return
        val text = binding.editorText.text ?: return
        suppressEditWatcher = true
        text.replace(op.start, op.start + op.before.length, op.after)
        suppressEditWatcher = false
        undoStack.push(op)
        binding.editorText.setSelection(op.start + op.after.length)
        dirty = true
        applyTimestampSpans()
    }

    /**
     * 给所有时间戳加上可点击 Span，点击后跳转播放进度。
     * 识别规则统一由 [LyricsParser.findTimestamps] 维护：除行首 [mm:ss.xx]、
     * 增强型 <mm:ss.xx> 外，还覆盖 WRC / TRC / KRC / QRC / YRC 的逐字时间戳
     * 以及 SRT / VTT / TTML 的时钟时间与时间轴。
     */
    private fun applyTimestampSpans() {
        val text = binding.editorText.text ?: return
        val spannable = text as? Spannable ?: return
        // 先移除旧的点击 Span
        spannable.getSpans(0, spannable.length, ClickableSpan::class.java)
            .forEach { spannable.removeSpan(it) }

        for (ts in LyricsParser.findTimestamps(text.toString())) {
            if (ts.start < 0 || ts.end > spannable.length || ts.start >= ts.end) continue
            val timeMs = ts.timeMs
            val span = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    lastSeekRequestTime = SystemClock.elapsedRealtime()
                    showSeekToast(timeMs)
                    MusicNotificationListenerService.seekTo(timeMs)
                }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = 0xFF66CCFF.toInt()
                    ds.isUnderlineText = true
                }
            }
            spannable.setSpan(span, ts.start, ts.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // 让点击生效
        binding.editorText.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    // ---------------------------------------------------------------- 打轴工具

    /** 行首时间戳匹配（添加/更新时间戳按钮用）：允许行首空白，支持 [..] 与 <..>。 */
    private val leadingTimestampPattern = java.util.regex.Pattern
        .compile("([ \\t]*)([\\[<])(\\d{1,2}:\\d{1,2}(?:[.:]\\d{1,3})?)([\\]>])")

    /** 返回光标所在行的区间 [lineStart, lineEnd)。 */
    private fun cursorLineRange(text: String, sel: Int): Pair<Int, Int> {
        val lineStart = if (sel <= 0) 0 else text.lastIndexOf('\n', sel - 1) + 1
        var lineEnd = text.indexOf('\n', lineStart)
        if (lineEnd < 0) lineEnd = text.length
        return lineStart to lineEnd
    }

    /**
     * 检测行首时间戳：返回匹配结果（括号类型需配对），无时间戳返回 null。
     * group(2)/group(4) 为开/闭括号，m.start(2) ~ m.end(4) 即时间戳整体。
     */
    private fun detectLeadingTimestamp(text: String, lineStart: Int, lineEnd: Int): java.util.regex.Matcher? {
        val m = leadingTimestampPattern.matcher(text).region(lineStart, lineEnd)
        if (!m.lookingAt()) return null
        val open = m.group(2)
        val close = m.group(4)
        val paired = (open == "[" && close == "]") || (open == "<" && close == ">")
        return if (paired) m else null
    }

    /**
     * 打轴：给光标所在行添加或更新时间戳（取决于该行是否已有时间戳），
     * 时间取当前播放进度，完成后自动跳到下一行行首，方便连续打轴。
     */
    private fun applyOrUpdateTimestamp() {
        if (!MusicProgress.hasValidProgress()) {
            toast(getString(R.string.no_playing_song))
            return
        }
        val text = binding.editorText.text ?: return
        val sel = binding.editorText.selectionStart
        if (sel < 0) return
        val s = text.toString()
        val (lineStart, lineEnd) = cursorLineRange(s, sel)
        val now = MusicProgress.currentPositionMs()
        // 先提交待合并操作，让本次打轴成为独立的一步撤销单元
        commitPending()
        val m = detectLeadingTimestamp(s, lineStart, lineEnd)
        if (m != null) {
            // 更新：保留原有括号类型，仅替换时间部分
            val token = (m.group(2) ?: "[") + formatEditTimestamp(now) + (m.group(4) ?: "]")
            text.replace(m.start(2), m.end(4), token)
        } else {
            // 添加：插入行首
            text.insert(lineStart, "[" + formatEditTimestamp(now) + "] ")
        }
        selectNextLine(lineStart)
        updateEditAssistUi()
    }

    /** 打轴后光标跳到下一行行首，并把光标行滚动进可视区。 */
    private fun selectNextLine(lineStart: Int) {
        val text = binding.editorText.text ?: return
        val nl = android.text.TextUtils.indexOf(text, '\n', lineStart)
        val nextSel = if (nl >= 0) nl + 1 else text.length
        binding.editorText.setSelection(nextSel)
        scrollSelectionIntoView()
    }

    /** 把光标所在行滚动进可视区（离开可视区或贴边时才滚动，避免连续操作时画面抖动）。 */
    private fun scrollSelectionIntoView() {
        binding.editorText.post {
            val layout = binding.editorText.layout ?: return@post
            val lineNum = layout.getLineForOffset(binding.editorText.selectionStart)
            val top = layout.getLineTop(lineNum)
            val bottom = layout.getLineBottom(lineNum)
            val viewH = binding.editorText.height
            val scrollY = binding.editorText.scrollY
            if (bottom > scrollY + viewH - 20 || top < scrollY + 20) {
                binding.editorText.scrollTo(0, (top - viewH / 3).coerceAtLeast(0))
            }
        }
    }

    /** 打轴辅助：相对当前播放进度快进/回退（毫秒）。 */
    private fun seekBy(deltaMs: Long) {
        if (!MusicProgress.hasValidProgress()) {
            toast(getString(R.string.no_playing_song))
            return
        }
        lastSeekRequestTime = SystemClock.elapsedRealtime()
        val target = (MusicProgress.currentPositionMs() + deltaMs).coerceAtLeast(0L)
        MusicNotificationListenerService.seekTo(target)
    }

    /**
     * 刷新打轴辅助控件：
     * · 时间戳按钮文案——光标行有时间戳显示「更新时间戳」，否则显示「添加时间戳」；
     * · 播放/暂停按钮文案——播放中显示「暂停」，否则显示「播放」。
     */
    private fun updateEditAssistUi() {
        val text = binding.editorText.text
        val hasTimestamp = if (text == null || text.isEmpty()) {
            false
        } else {
            val s = text.toString()
            val sel = binding.editorText.selectionStart
            if (sel < 0) {
                false
            } else {
                val (lineStart, lineEnd) = cursorLineRange(s, sel)
                detectLeadingTimestamp(s, lineStart, lineEnd) != null
            }
        }
        val label = getString(if (hasTimestamp) R.string.update_timestamp else R.string.add_timestamp)
        if (binding.btnTimestamp.text.toString() != label) binding.btnTimestamp.text = label
        val playLabel = getString(if (MusicProgress.isPlaying) R.string.pause_playback else R.string.resume_playback)
        // 乐观反馈：真实状态同步过来就撤掉；否则在短窗口内保留点击后的预期文字
        val shownLabel = playPauseOptimisticLabel?.let { optimistic ->
            if (SystemClock.elapsedRealtime() >= playPauseOptimisticUntil ||
                optimistic == playLabel
            ) {
                playPauseOptimisticLabel = null
                playLabel
            } else {
                optimistic
            }
        } ?: playLabel
        if (binding.btnPlayPause.text.toString() != shownLabel) binding.btnPlayPause.text = shownLabel
    }

    // ------------------------------------------------- 行跳转 / 进度调节时间设置 / 整体偏移

    /** LRC 时间戳（行首 [mm:ss.xx] 与逐字 <mm:ss.xx>），额外捕获秒与小数之间的分隔符以便偏移后原样还原。 */
    private val offsetLrcTimePattern = java.util.regex.Pattern
        .compile("([\\[<])(\\d{1,2}):(\\d{1,2})(?:([.:])(\\d{1,3}))?([\\]>])")

    /** SRT 时间轴时间 HH:MM:SS,mmm（分隔符 , 或 . 均支持）。 */
    private val offsetSrtTimePattern = java.util.regex.Pattern
        .compile("(\\d{1,2}):(\\d{2}):(\\d{2})([.,])(\\d{1,3})")

    /** 整体偏移弹窗中一条选项的视图引用。 */
    private class OffsetRowViews(
        val root: LinearLayout,
        val input: EditText,
        val spinner: Spinner,
    )

    /** 秒转毫秒（四舍五入）。 */
    private fun secondsToMs(sec: Float): Long = (sec * 1000f).roundToLong()

    /** 格式化秒数用于展示/回填：整数不带小数点，小数去掉多余的 0（如 0.5、1.25）。 */
    private fun formatSeconds(sec: Float): String =
        if (sec % 1f == 0f) sec.toLong().toString()
        else String.format(java.util.Locale.US, "%.3f", sec).trimEnd('0').trimEnd('.')

    /** 按当前设置刷新四个进度调节按钮文案（如 -5秒 / +0.5秒）。 */
    private fun updateSeekStepButtons() {
        binding.btnRewind5.text = getString(R.string.seek_rewind_fmt, formatSeconds(prefs.seekRewindBigSec))
        binding.btnRewind1.text = getString(R.string.seek_rewind_fmt, formatSeconds(prefs.seekRewindSmallSec))
        binding.btnForward1.text = getString(R.string.seek_forward_fmt, formatSeconds(prefs.seekForwardSmallSec))
        binding.btnForward5.text = getString(R.string.seek_forward_fmt, formatSeconds(prefs.seekForwardBigSec))
    }

    /**
     * 光标快速跳转到上一行/下一行行首（[delta] 为 -1 / 1），
     * 跳转后把目标行滚动进可视区，方便手动逐行校对。
     */
    private fun moveCursorLine(delta: Int) {
        val text = binding.editorText.text ?: return
        val s = text.toString()
        var sel = binding.editorText.selectionStart
        if (sel < 0) sel = 0
        val lineStart = if (sel <= 0) 0 else s.lastIndexOf('\n', sel - 1) + 1
        val target = if (delta < 0) {
            // 上一行行首：当前行首的前一个字符是换行，再向前找上一个换行
            if (lineStart <= 0) 0 else s.lastIndexOf('\n', lineStart - 2) + 1
        } else {
            // 下一行行首：从当前行行首向后找换行
            val nl = s.indexOf('\n', lineStart)
            if (nl >= 0) nl + 1 else s.length
        }
        binding.editorText.setSelection(target)
        scrollSelectionIntoView()
    }

    /** 进度调节时间设置弹窗：修改四个调节歌曲进度的秒数控件（支持小数）。 */
    private fun showSeekStepsDialog() {
        val dialogBinding = DialogSeekStepsBinding.inflate(layoutInflater)
        dialogBinding.inputRewindBig.setText(formatSeconds(prefs.seekRewindBigSec))
        dialogBinding.inputRewindSmall.setText(formatSeconds(prefs.seekRewindSmallSec))
        dialogBinding.inputForwardSmall.setText(formatSeconds(prefs.seekForwardSmallSec))
        dialogBinding.inputForwardBig.setText(formatSeconds(prefs.seekForwardBigSec))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.seek_step_settings)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val big = dialogBinding.inputRewindBig.text?.toString()?.trim()?.toFloatOrNull()
                val small = dialogBinding.inputRewindSmall.text?.toString()?.trim()?.toFloatOrNull()
                val fSmall = dialogBinding.inputForwardSmall.text?.toString()?.trim()?.toFloatOrNull()
                val fBig = dialogBinding.inputForwardBig.text?.toString()?.trim()?.toFloatOrNull()
                if (listOf(big, small, fSmall, fBig).any { it == null || it <= 0f }) {
                    toast(getString(R.string.seek_step_invalid))
                    return@setPositiveButton
                }
                prefs.seekRewindBigSec = big!!
                prefs.seekRewindSmallSec = small!!
                prefs.seekForwardSmallSec = fSmall!!
                prefs.seekForwardBigSec = fBig!!
                updateSeekStepButtons()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * 整体偏移弹窗：
     * · 左上角"添加"按钮新增选项，右上角 ✕ 关闭弹窗；
     * · 每条选项为一行：倒退(按钮) 数值框 单位(下拉：毫秒/秒) 前进(按钮) 删除(按钮)；
     * · 选项列表持久化记忆（含数值与单位），避免重复设置；
     * · 点击倒退/前进把全文时间戳整体平移对应量，且作为一步可撤销操作。
     */
    private fun showGlobalOffsetDialog() {
        val dialogBinding = DialogGlobalOffsetBinding.inflate(layoutInflater)
        val container = dialogBinding.offsetRowsContainer
        val rows = mutableListOf<OffsetRowViews>()
        val density = resources.displayMetrics.density

        // 主题色与波纹背景：弹窗内按钮与编辑工具栏风格保持一致
        val primary = android.util.TypedValue().let {
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, it, true)
            it.data
        }
        val ripple = android.util.TypedValue().let {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            it.resourceId
        }

        fun actionButton(textRes: Int, color: Int) = TextView(this).apply {
            text = getString(textRes)
            setTextColor(color)
            textSize = 14f
            val padH = (density * 12).toInt()
            val padV = (density * 8).toInt()
            setPadding(padH, padV, padH, padV)
            setBackgroundResource(ripple)
        }

        /** 把当前弹窗内所有选项（数值 + 单位）持久化。 */
        fun persistRows() {
            prefs.setGlobalOffsetOptions(rows.map {
                Prefs.OffsetOption(
                    it.input.text?.toString()?.trim().orEmpty(),
                    it.spinner.selectedItemPosition
                )
            })
        }

        /** 应用某一行选项：forward 为 true 表示前进，否则倒退。 */
        fun applyRow(row: OffsetRowViews, forward: Boolean) {
            val raw = row.input.text?.toString()?.trim().orEmpty()
            val value = raw.toDoubleOrNull()
            if (value == null || value <= 0.0) {
                toast(getString(R.string.offset_invalid_value))
                return
            }
            val unitMs = row.spinner.selectedItemPosition == Prefs.OffsetOption.UNIT_MS
            val ms = if (unitMs) value.toLong() else (value * 1000.0).roundToLong()
            if (ms <= 0L) {
                toast(getString(R.string.offset_invalid_value))
                return
            }
            // 先记住用户本次输入的数值与单位
            persistRows()
            applyGlobalOffset(if (forward) ms else -ms)
            val desc = raw + getString(if (unitMs) R.string.offset_unit_ms else R.string.offset_unit_s)
            toast(getString(if (forward) R.string.offset_applied_forward else R.string.offset_applied_back, desc))
        }

        fun addRow(value: String, unit: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (density * 4).toInt(), 0, 0)
            }
            val views = OffsetRowViews(row, EditText(this).apply {
                setText(value)
                hint = getString(R.string.offset_value_hint)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                maxLines = 1
                textSize = 14f
                gravity = android.view.Gravity.CENTER
            }, Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity, android.R.layout.simple_spinner_item,
                    listOf(getString(R.string.offset_unit_ms), getString(R.string.offset_unit_s))
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(unit)
            })
            val btnBack = actionButton(R.string.offset_rewind, primary)
            val btnFwd = actionButton(R.string.offset_forward, primary)
            val btnDel = actionButton(R.string.offset_delete, 0xFFE53935.toInt())
            btnBack.setOnClickListener { applyRow(views, forward = false) }
            btnFwd.setOnClickListener { applyRow(views, forward = true) }
            btnDel.setOnClickListener {
                rows.remove(views)
                container.removeView(row)
                persistRows()
            }
            row.addView(btnBack)
            row.addView(views.input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (density * 6).toInt()
                marginEnd = (density * 6).toInt()
            })
            row.addView(views.spinner)
            row.addView(btnFwd)
            row.addView(btnDel)
            container.addView(row)
            rows.add(views)
        }

        // 载入记忆的选项；从未设置过时为默认两条（500 毫秒 / 1 秒）
        prefs.getGlobalOffsetOptions().forEach { addRow(it.value, it.unit) }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        dialogBinding.btnAddOffsetOption.setOnClickListener {
            addRow("1", Prefs.OffsetOption.UNIT_S)
            persistRows()
        }
        dialogBinding.btnCloseOffsetDialog.setOnClickListener { dialog.dismiss() }
        // 关闭时再保存一次，记住用户手动修改但尚未应用的数值/单位
        dialog.setOnDismissListener { persistRows() }
        dialog.show()
    }

    /**
     * 整体偏移：把全文所有时间戳（LRC 行首 [mm:ss.xx]、逐字 <mm:ss.xx>、SRT 时间轴）
     * 统一平移 [deltaMs]（负值为倒退，结果不早于 0）。偏移后保留原有括号类型、
     * 分隔符与小数位数；整体替换记录为一步可撤销的编辑操作。
     */
    private fun applyGlobalOffset(deltaMs: Long) {
        val text = binding.editorText.text ?: return
        val old = text.toString()
        var count = 0

        fun fracToMs(fracRaw: String?): Long = when {
            fracRaw == null -> 0L
            fracRaw.length == 1 -> fracRaw.toLong() * 100
            fracRaw.length == 2 -> fracRaw.toLong() * 10
            else -> fracRaw.toLong()
        }

        // 第一遍：SRT 时间轴 HH:MM:SS,mmm
        val srt = StringBuffer()
        val sm = offsetSrtTimePattern.matcher(old)
        while (sm.find()) {
            val h = sm.group(1)?.toLongOrNull() ?: 0L
            val m = sm.group(2)?.toLongOrNull() ?: 0L
            val s = sm.group(3)?.toLongOrNull() ?: 0L
            val fracRaw = sm.group(5)
            val newMs = (h * 3_600_000 + m * 60_000 + s * 1000 + fracToMs(fracRaw) + deltaMs)
                .coerceAtLeast(0L)
            val sep = sm.group(4) ?: ","
            val token = formatSrtTime(newMs, sep, fracRaw?.length ?: 3)
            sm.appendReplacement(srt, java.util.regex.Matcher.quoteReplacement(token))
            count++
        }
        sm.appendTail(srt)

        // 第二遍：LRC 行首/逐字时间戳（只处理配对的 [..] 与 <..>）
        val lrc = StringBuffer()
        val lm = offsetLrcTimePattern.matcher(srt.toString())
        while (lm.find()) {
            val open = lm.group(1)
            val close = lm.group(6)
            val paired = (open == "[" && close == "]") || (open == "<" && close == ">")
            if (!paired) {
                lm.appendReplacement(lrc, java.util.regex.Matcher.quoteReplacement(lm.group()))
                continue
            }
            val min = lm.group(2)?.toLongOrNull() ?: 0L
            val sec = lm.group(3)?.toLongOrNull() ?: 0L
            val fracRaw = lm.group(5)
            val newMs = (min * 60_000 + sec * 1000 + fracToMs(fracRaw) + deltaMs).coerceAtLeast(0L)
            val token = open + formatLrcTime(newMs, lm.group(4) ?: ".", fracRaw) + close
            lm.appendReplacement(lrc, java.util.regex.Matcher.quoteReplacement(token))
            count++
        }
        lm.appendTail(lrc)

        val new = lrc.toString()
        if (count == 0 || new == old) {
            toast(getString(R.string.offset_no_timestamp))
            return
        }
        // 记录为一步可撤销的完整替换
        commitPending()
        val sel = binding.editorText.selectionStart
        suppressEditWatcher = true
        binding.editorText.setText(new)
        suppressEditWatcher = false
        undoStack.add(EditOp(0, old, new))
        redoStack.clear()
        decimalModifySnapshot = null
        dirty = true
        binding.editorText.setSelection(sel.coerceIn(0, new.length))
        applyTimestampSpans()
    }

    /** 格式化 LRC 时间戳的 mm:ss 部分（按原有小数位数还原，无小数则只到秒）。 */
    private fun formatLrcTime(ms: Long, sep: String, fracRaw: String?): String {
        val min = ms / 60_000
        val sec = (ms % 60_000) / 1000
        val rem = ms % 1000
        val base = String.format(java.util.Locale.US, "%02d:%02d", min, sec)
        if (fracRaw == null) return base
        val frac = when (fracRaw.length) {
            1 -> (rem / 100).toString()
            2 -> String.format(java.util.Locale.US, "%02d", rem / 10)
            else -> String.format(java.util.Locale.US, "%03d", rem)
        }
        return base + sep + frac
    }

    /** 格式化 SRT 时间戳 HH:MM:SS,mmm（按原有分隔符与小数位数还原）。 */
    private fun formatSrtTime(ms: Long, sep: String, fracDigits: Int): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val rem = ms % 1000
        val frac = when (fracDigits) {
            1 -> (rem / 100).toString()
            2 -> String.format(java.util.Locale.US, "%02d", rem / 10)
            else -> String.format(java.util.Locale.US, "%03d", rem)
        }
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s) + sep + frac
    }

    // ---------------------------------------------------------------- 编辑页「更多设置」

    /**
     * 按「更多设置」里选择的时间精度格式化打轴时间戳：
     * 0 位 → mm:ss，1 位 → mm:ss.x，2 位 → mm:ss.xx，3 位 → mm:ss.xxx。
     * 只作用于「添加/更新时间戳」按钮，不改变已有时间戳的写法。
     */
    private fun formatEditTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSec = safe / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        val rem = safe % 1000
        val base = String.format(java.util.Locale.US, "%02d:%02d", min, sec)
        return when (prefs.timestampDecimalDigits.coerceIn(0, 3)) {
            0 -> base
            1 -> "$base.${rem / 100}"
            2 -> base + String.format(java.util.Locale.US, ".%02d", rem / 10)
            else -> base + String.format(java.util.Locale.US, ".%03d", rem)
        }
    }

    /** 「更多设置」弹窗：时间精度、整体小数点修改、键盘行为与按钮显隐开关。 */
    private fun showMoreSettingsDialog() {
        val d = DialogEditMoreSettingsBinding.inflate(layoutInflater)

        /** Spinner 选中即写盘，避免弹窗被系统回收后丢失选择。 */
        fun bindSpinner(spinner: Spinner, initial: Int, save: (Int) -> Unit) {
            spinner.setSelection(initial, false)
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                ) = save(position)
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }

        /** 开关切换即写盘并立刻刷新编辑工具栏。 */
        fun bindSwitch(
            sw: android.widget.CompoundButton, initial: Boolean, save: (Boolean) -> Unit
        ) {
            sw.isChecked = initial
            sw.setOnCheckedChangeListener { _, checked ->
                save(checked)
                applyMoreSettingsUi()
            }
        }

        bindSpinner(d.spinnerTimestampDigits, prefs.timestampDecimalDigits) {
            prefs.timestampDecimalDigits = it
        }
        bindSpinner(d.spinnerDecimalRemove, prefs.decimalRemoveMode) {
            prefs.decimalRemoveMode = it
        }
        bindSpinner(d.spinnerDecimalMode, prefs.decimalRoundMode) {
            prefs.decimalRoundMode = it
        }
        bindSwitch(d.switchScrollCursorOnKeyboard, prefs.scrollCursorIntoViewOnKeyboard) {
            prefs.scrollCursorIntoViewOnKeyboard = it
        }
        bindSwitch(d.switchShowOpenKeyboardButton, prefs.showOpenKeyboardButton) {
            prefs.showOpenKeyboardButton = it
        }
        bindSwitch(d.switchShowAddBlankAbove, prefs.showAddBlankAboveButton) {
            prefs.showAddBlankAboveButton = it
        }
        bindSwitch(d.switchShowAddBlankBelow, prefs.showAddBlankBelowButton) {
            prefs.showAddBlankBelowButton = it
        }
        bindSwitch(d.switchShowDeleteAllBlank, prefs.showDeleteAllBlankLinesButton) {
            prefs.showDeleteAllBlankLinesButton = it
        }
        bindSwitch(d.switchShowDeleteCurrentLine, prefs.showDeleteCurrentLineButton) {
            prefs.showDeleteCurrentLineButton = it
        }

        // 编辑按钮布局分段控件：默认「自动换行」，切换后立刻重排底部按钮
        d.buttonLayoutGroup.check(
            if (prefs.editButtonLayout == Prefs.EDIT_BUTTONS_SCROLL) {
                R.id.btnLayoutScroll
            } else {
                R.id.btnLayoutWrap
            }
        )
        d.buttonLayoutGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.editButtonLayout = if (checkedId == R.id.btnLayoutScroll) {
                Prefs.EDIT_BUTTONS_SCROLL
            } else {
                Prefs.EDIT_BUTTONS_WRAP
            }
            applyMoreSettingsUi()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.more_settings_title)
            .setView(d.root)
            .setNegativeButton(R.string.more_settings_close, null)
            .create()

        d.btnStartDecimalModify.setOnClickListener {
            applyBulkDecimalModify(prefs.decimalRemoveMode, prefs.decimalRoundMode)
        }
        d.btnUndoDecimalModify.setOnClickListener { undoBulkDecimalModify() }
        dialog.setOnDismissListener { applyMoreSettingsUi() }
        dialog.show()
    }

    /** 按当前设置刷新编辑工具栏：按钮显隐、键盘按钮状态与「点击文本是否自动弹键盘」。 */
    private fun applyMoreSettingsUi() {
        fun visible(view: View, show: Boolean) {
            view.visibility = if (show) View.VISIBLE else View.GONE
        }
        visible(binding.btnOpenKeyboard, prefs.showOpenKeyboardButton)
        visible(binding.btnAddBlankAbove, prefs.showAddBlankAboveButton)
        visible(binding.btnAddBlankBelow, prefs.showAddBlankBelowButton)
        visible(binding.btnDeleteAllBlankLines, prefs.showDeleteAllBlankLinesButton)
        visible(binding.btnDeleteCurrentLine, prefs.showDeleteCurrentLineButton)
        // 显示「打开键盘」按钮时由按钮显式控制键盘，点击文本不再自动弹出键盘
        binding.editorText.showSoftInputOnFocus = !prefs.showOpenKeyboardButton
        applyEditButtonLayout()
        updateKeyboardButton()
    }

    /** 按「更多设置」里的分段控件切换编辑辅助按钮的排布方式。 */
    private fun applyEditButtonLayout() {
        val wrap = prefs.editButtonLayout == Prefs.EDIT_BUTTONS_WRAP
        if (binding.editExtraButtons.wrapEnabled != wrap) {
            binding.editExtraButtons.wrapEnabled = wrap
            binding.editExtraRow.scrollTo(0, 0)
        }
    }

    /** 键盘状态变化时更新按钮文案：键盘已打开时按钮变为「关闭键盘」。 */
    private fun updateKeyboardButton() {
        binding.btnOpenKeyboard.text = getString(
            if (keyboardShown) R.string.close_keyboard else R.string.open_keyboard
        )
    }

    /**
     * 监听软键盘显隐：adjustResize 下 content view 会被压小，因此用 DecorView 的
     * 根高度与「当前窗口可见帧」之差判断（DecorView 高度不随键盘变化）。
     */
    private fun setupKeyboardObserver() {
        val decor = window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = android.graphics.Rect()
            decor.getWindowVisibleDisplayFrame(rect)
            val diff = decor.rootView.height - rect.height()
            // 状态栏/导航栏也会占掉少量高度，阈值取 160dp 以避开它们；键盘一般高于 250dp
            val threshold = (160f * resources.displayMetrics.density).toInt()
            val nowShown = diff > threshold
            if (nowShown == keyboardShown) return@OnGlobalLayoutListener
            keyboardShown = nowShown
            updateKeyboardButton()
            if (nowShown && isEditMode() && prefs.scrollCursorIntoViewOnKeyboard) {
                // 等键盘引起的布局重算稳定后，再把光标选择行滚动进可视区
                binding.editorText.postDelayed({
                    if (!isFinishing && isEditMode() && keyboardShown &&
                        prefs.scrollCursorIntoViewOnKeyboard
                    ) scrollSelectionIntoView()
                }, 120L)
            }
        }
        keyboardLayoutListener = listener
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    /** 打开/关闭键盘按钮：键盘已打开时直接关闭，不会重复打开键盘。 */
    private fun toggleKeyboard() {
        val now = SystemClock.uptimeMillis()
        if (now - lastKeyboardToggleAt < 250L) return
        lastKeyboardToggleAt = now
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (keyboardShown) {
            // 键盘已打开：静默关闭即可，不弹提示、不重复打开键盘
            imm.hideSoftInputFromWindow(binding.editorText.windowToken, 0)
            return
        }
        if (!binding.editorText.hasFocus()) binding.editorText.requestFocus()
        binding.editorText.post {
            imm.showSoftInput(binding.editorText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * 整体小数点修改：把全文所有时间戳（LRC [mm:ss.xx] / <mm:ss.xx>、SRT HH:MM:SS,mmm）
     * 的小数位按选定规则移除。
     *
     * · 移除位数：最后 1 位 / 最后 2 位 / 全部；原有位数不足时按「全部移除」处理。
     * · 直接移除：截断低位不进位；四舍五入后移除：按目标位数四舍五入，允许向秒/分进位。
     * 保留原括号类型与小数分隔符，整次替换记为一步可撤销操作。
     */
    private fun applyBulkDecimalModify(removeMode: Int, roundMode: Int) {
        val editable = binding.editorText.text ?: return
        val old = editable.toString()
        val round = roundMode == Prefs.DECIMAL_MODE_ROUND
        var count = 0

        /** 小数字符串转毫秒：1 位=百毫秒、2 位=十毫秒、3 位=毫秒。 */
        fun fracToMs(frac: String): Long = when (frac.length) {
            1 -> frac.toLong() * 100L
            2 -> frac.toLong() * 10L
            else -> frac.toLong()
        }

        /** 目标位数对应的取整单位（毫秒）。 */
        fun scaleOf(digits: Int): Long = when (digits) {
            0 -> 1000L
            1 -> 100L
            2 -> 10L
            else -> 1L
        }

        fun targetDigits(orig: Int): Int {
            val remove = when (removeMode) {
                Prefs.DECIMAL_REMOVE_TWO -> 2
                Prefs.DECIMAL_REMOVE_ALL -> orig
                else -> 1
            }
            return (orig - remove).coerceAtLeast(0)
        }

        /** 按目标位数取舍总毫秒数；四舍五入时可能向秒进位。 */
        fun adjust(totalMs: Long, digits: Int): Long {
            val scale = scaleOf(digits)
            return if (round) ((totalMs + scale / 2) / scale) * scale
            else (totalMs / scale) * scale
        }

        /** 按目标位数输出小数部分；0 位时返回空串（连同分隔符一起去掉）。 */
        fun fracText(totalMs: Long, digits: Int): String {
            val rem = totalMs % 1000
            return when (digits) {
                0 -> ""
                1 -> (rem / 100).toString()
                2 -> String.format(java.util.Locale.US, "%02d", rem / 10)
                else -> String.format(java.util.Locale.US, "%03d", rem)
            }
        }

        // 第一遍：SRT / VTT 时间轴 HH:MM:SS,mmm
        val srtBuf = StringBuffer()
        val sm = offsetSrtTimePattern.matcher(old)
        while (sm.find()) {
            val fracRaw = sm.group(5).orEmpty()
            if (fracRaw.isEmpty()) {
                sm.appendReplacement(srtBuf, java.util.regex.Matcher.quoteReplacement(sm.group()))
                continue
            }
            val h = sm.group(1)?.toLongOrNull() ?: 0L
            val m = sm.group(2)?.toLongOrNull() ?: 0L
            val s = sm.group(3)?.toLongOrNull() ?: 0L
            val target = targetDigits(fracRaw.length)
            val total = h * 3_600_000 + m * 60_000 + s * 1000 + fracToMs(fracRaw)
            val adjusted = adjust(total, target)
            val clock = String.format(
                java.util.Locale.US, "%02d:%02d:%02d",
                adjusted / 3_600_000,
                (adjusted % 3_600_000) / 60_000,
                (adjusted % 60_000) / 1000
            )
            val tail = if (target == 0) "" else (sm.group(4) ?: ",") + fracText(adjusted, target)
            sm.appendReplacement(srtBuf, java.util.regex.Matcher.quoteReplacement(clock + tail))
            count++
        }
        sm.appendTail(srtBuf)

        // 第二遍：LRC 行首 [mm:ss.xx] 与逐字 <mm:ss.xx>
        val lrcBuf = StringBuffer()
        val lm = offsetLrcTimePattern.matcher(srtBuf.toString())
        while (lm.find()) {
            val open = lm.group(1)
            val close = lm.group(6)
            val paired = (open == "[" && close == "]") || (open == "<" && close == ">")
            val fracRaw = lm.group(5) ?: ""
            if (!paired || fracRaw.isEmpty()) {
                lm.appendReplacement(lrcBuf, java.util.regex.Matcher.quoteReplacement(lm.group()))
                continue
            }
            val min = lm.group(2)?.toLongOrNull() ?: 0L
            val sec = lm.group(3)?.toLongOrNull() ?: 0L
            val target = targetDigits(fracRaw.length)
            val total = min * 60_000 + sec * 1000 + fracToMs(fracRaw)
            val adjusted = adjust(total, target)
            val clock = String.format(
                java.util.Locale.US, "%02d:%02d",
                adjusted / 60_000, (adjusted % 60_000) / 1000
            )
            val tail = if (target == 0) "" else (lm.group(4) ?: ".") + fracText(adjusted, target)
            lm.appendReplacement(
                lrcBuf,
                java.util.regex.Matcher.quoteReplacement(open + clock + tail + close)
            )
            count++
        }
        lm.appendTail(lrcBuf)

        val new = lrcBuf.toString()
        if (count == 0 || new == old) {
            toast(R.string.decimal_no_timestamp)
            return
        }
        replaceWholeEditorText(old, new, snapshot = true)
        toast(getString(R.string.decimal_applied, count))
    }

    /** 「撤销修改」：把最近一次整体小数点修改精确回滚（内容被再次改动后不允许直接回滚）。 */
    private fun undoBulkDecimalModify() {
        val snapshot = decimalModifySnapshot
        if (snapshot == null) {
            toast(R.string.decimal_undo_unavailable)
            return
        }
        val (old, new) = snapshot
        if (binding.editorText.text?.toString().orEmpty() != new) {
            toast(R.string.decimal_undo_blocked)
            return
        }
        commitPending()
        suppressEditWatcher = true
        binding.editorText.setText(old)
        suppressEditWatcher = false
        // 把这一次整体替换从撤销栈移除，避免工具栏「撤销」又把它恢复回来
        if (undoStack.lastOrNull() == EditOp(0, old, new)) undoStack.pollLast()
        redoStack.clear()
        decimalModifySnapshot = null
        dirty = true
        binding.editorText.setSelection(binding.editorText.selectionStart.coerceIn(0, old.length))
        applyTimestampSpans()
        toast(R.string.decimal_undo_done)
    }

    /**
     * 一次性替换编辑框全文并压入一步撤销记录。
     * [snapshot] 为 true 时额外记录快照，供「撤销修改」按钮精确回滚。
     */
    private fun replaceWholeEditorText(old: String, new: String, snapshot: Boolean) {
        commitPending()
        val sel = binding.editorText.selectionStart
        suppressEditWatcher = true
        binding.editorText.setText(new)
        suppressEditWatcher = false
        undoStack.add(EditOp(0, old, new))
        redoStack.clear()
        decimalModifySnapshot = if (snapshot) old to new else null
        dirty = true
        binding.editorText.setSelection(sel.coerceIn(0, new.length))
        applyTimestampSpans()
    }

    /** 在光标所在行上方插入一个空行，光标仍停留在原歌词行。 */
    private fun addBlankLineAbove() {
        val text = binding.editorText.text ?: return
        val sel = binding.editorText.selectionStart.coerceAtLeast(0)
        val (lineStart, _) = cursorLineRange(text.toString(), sel)
        commitPending()
        text.insert(lineStart, "\n")
        binding.editorText.setSelection((sel + 1).coerceIn(0, text.length))
        commitPending()
        decimalModifySnapshot = null
        scrollSelectionIntoView()
    }

    /** 在光标所在行下方插入一个空行，光标保持不动。 */
    private fun addBlankLineBelow() {
        val text = binding.editorText.text ?: return
        val sel = binding.editorText.selectionStart.coerceAtLeast(0)
        val (_, lineEnd) = cursorLineRange(text.toString(), sel)
        commitPending()
        text.insert(lineEnd, "\n")
        binding.editorText.setSelection(sel.coerceIn(0, text.length))
        commitPending()
        decimalModifySnapshot = null
    }

    /** 删除全文所有空行（含只有空格/制表符的行），保留其它行的原有换行符。 */
    private fun deleteAllBlankLines() {
        val text = binding.editorText.text ?: return
        val old = text.toString()
        val blankLine = java.util.regex.Pattern.compile("(?m)^[ \\t]*\\r?\\n")
        val m = blankLine.matcher(old)
        val sb = StringBuffer()
        var count = 0
        while (m.find()) {
            m.appendReplacement(sb, "")
            count++
        }
        m.appendTail(sb)
        val new = sb.toString()
        if (count == 0) {
            toast(R.string.no_blank_lines)
            return
        }
        replaceWholeEditorText(old, new, snapshot = false)
        toast(getString(R.string.blank_lines_deleted, count))
    }

    /** 删除光标当前所在行（连同行尾换行），作为一步可撤销操作。 */
    private fun deleteCurrentLine() {
        val text = binding.editorText.text ?: return
        val s = text.toString()
        if (s.isEmpty()) return
        val sel = binding.editorText.selectionStart.coerceIn(0, s.length)
        val (lineStart, lineEnd) = cursorLineRange(s, sel)
        val end = if (lineEnd < s.length) (lineEnd + 1).coerceAtMost(s.length) else lineEnd
        if (end <= lineStart) return
        commitPending()
        text.replace(lineStart, end, "")
        commitPending()
        decimalModifySnapshot = null
        binding.editorText.setSelection(lineStart.coerceIn(0, text.length))
        scrollSelectionIntoView()
    }

    // ---------------------------------------------------------------- 文件

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return false
                load(data)
                return true
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (stream != null) {
                    load(stream)
                    return true
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    loadSharedText(text)
                    return true
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val stream: Uri? = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    ?.firstOrNull()
                if (stream != null) {
                    load(stream)
                    return true
                }
            }
        }
        return false
    }

    private fun loadSharedText(text: String) {
        markManualOpen()
        pendingNewLyricName = null
        currentUri = null
        internalFile = null
        currentName = getString(R.string.shared_text_name)
        charset = StandardCharsets.UTF_8
        bom = ByteArray(0)
        docText = text
        lyricLines = LyricsParser.parse(text)
        Session.lyricLines = lyricLines
        adapter.submit(lyricLines)
        dirty = true
        updateTitle()
    }

    private fun load(uri: Uri) {
        markManualOpen()
        pendingNewLyricName = null
        currentUri = uri
        internalFile = null
        Thread {
            val result = runCatching { DocumentIo.read(contentResolver, uri) }
            val name = runCatching { DocumentIo.displayName(contentResolver, uri) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { loaded ->
                        charset = loaded.charset
                        bom = loaded.bom
                        currentName = name
                        dirty = false
                        docText = loaded.text
                        lyricLines = LyricsParser.parse(loaded.text)
                        Session.lyricLines = lyricLines
                        adapter.submit(lyricLines)
                        currentIndex = -1
                        // 保存内部副本并记录到最近打开文件（内部路径不受 SAF 权限回收影响）
                        // 外部应用打开的文件通常没有写权限，保存时优先写回应用内部歌词目录；
                        // 目录里已有同名文件时不再静默覆盖，弹窗让用户选择处理方式
                        resolveInternalCopy(name, loaded.text)
                        updateTitle()
                    },
                    onFailure = { error ->
                        showEmptyState()
                        pendingManualAdd = false
                        toast(getString(R.string.open_failed, error.message.orEmpty()))
                    }
                )
            }
        }.start()
    }

    private fun showEmptyState() {
        pendingNewLyricName = null
        currentUri = null
        internalFile = null
        currentName = null
        dirty = false
        charset = StandardCharsets.UTF_8
        bom = ByteArray(0)
        docText = ""
        lyricLines = emptyList()
        Session.lyricLines = null
        // 没有打开的歌词了，撤销"用户显式选择"保护，别影响下一首歌的自动匹配
        Session.explicitLyricTitle = null
        Session.explicitLyricPath = null
        // 当前没有歌词可显示，同步清掉持久化记录，避免冷启动恢复出无关歌词
        prefs.lastLyricFile = null
        adapter.submit(emptyList())
        currentIndex = -1
        updateTitle()
    }

    /** 应用数据目录下的歌词副本目录。 */
    private fun lyricsStoreDir(): File = File(filesDir, "lyrics").apply { mkdirs() }

    /**
     * 打开外部歌词后保存内部副本的入口：目录里已有同名文件时不再静默覆盖，
     * 而是弹窗让用户选择【覆盖已有文件 / 打开已有文件 / 重命名保存 / 取消】。
     */
    private fun resolveInternalCopy(name: String?, content: String) {
        if (name == null) {
            // 拿不到文件名就无法落副本：仅本次查看，不登记任何记录
            pendingManualAdd = false
            return
        }
        val target = File(lyricsStoreDir(), name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        if (target.exists()) {
            // 已记住处理方式时直接执行不再弹窗（取消不可记忆；
            // 设置页「重置所有弹窗提示」可恢复询问）
            when (prefs.internalCopyConflictMode) {
                Prefs.INTERNAL_COPY_OVERWRITE -> adoptInternalCopy(target, content)
                Prefs.INTERNAL_COPY_OPEN_EXISTING -> openExistingInternalCopy(target)
                // 记住的只是操作类型：名字仍每次问用户，不自动编号落盘
                Prefs.INTERNAL_COPY_RENAME -> promptConflictRenameSave(target, content)
                Prefs.INTERNAL_COPY_CREATE_COPY ->
                    // 创建副本才自动按 主体(1).ext 递增取名
                    adoptInternalCopy(File(lyricsStoreDir(), nextFreeName(target.name)), content)
                else -> showInternalCopyConflictDialog(target, content)
            }
        } else {
            adoptInternalCopy(target, content)
        }
    }

    /** 把外部歌词内容写入内部副本并完成登记：最近打开、冷启动恢复、歌曲-歌词绑定。 */
    private fun adoptInternalCopy(f: File, content: String) {
        val ok = runCatching { f.writeText(content, Charsets.UTF_8) }.isSuccess
        if (!ok) {
            pendingManualAdd = false
            return
        }
        // 歌词目录内容变化，使目录快照与匹配结果缓存立即失效
        LyricIndex.invalidate()
        val manual = pendingManualAdd
        val hasSong = MusicProgress.title?.isNotBlank() == true
        internalFile = f
        // 当前文档已切换为这份内部副本，名字以磁盘实际文件名为准
        currentName = f.name
        prefs.lastLyricFile = f.absolutePath
        // 最近文件记录内部副本路径，保证下次可直接打开（不受 SAF 权限回收影响）
        prefs.addRecentFile(f.absolutePath, f.name)
        // 外部文件同样是用户显式选择：同步自动匹配状态，避免稍后切回旧歌词
        adoptExplicitLyricChoice(f)
        if (manual && hasSong) toast(getString(R.string.manual_add_bound))
        pendingManualAdd = false
        updateTitle()
    }

    /**
     * 打开外部歌词时目录里已有同名内部副本：弹窗询问处理方式。
     * 【覆盖已有文件】用外部内容替换副本；【打开已有文件】放弃外部内容、
     * 改为展示已有副本；【重命名保存】换名存新文件；【创建副本】按 主体(1).ext
     * 自动编号存新文件；【仅打开，不做任何处理】不存副本仅本次查看。
     * 本弹窗是流程第一步：按返回键＝执行系统返回（退出当前界面），点外部不关闭。
     */
    private fun showInternalCopyConflictDialog(target: File, content: String) {
        // 复选框走 setView（customPanel 独立槽位，与 setItems 列表可共存），
        // 样式与下载目录重名弹窗的「记住操作」复选框一致
        val checkBox = CheckBox(this).apply {
            text = getString(R.string.conflict_remember)
            setPadding(48, 0, 48, 0)
            setTextColor(0xFF808080.toInt())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(checkBox)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            // 点弹窗外部一律不关闭，避免悄悄跳过决策不落副本。
            // 返回键不是「关闭」：第一步按返回键＝执行系统返回（见下方 OnKeyListener）；
            // 第二步（重命名输入框）按返回键＝返回上一步、重新显示本弹窗。
            .setCancelable(false)
            // 文件名只能放在标题里：Material 弹窗一旦 setMessage 就不会再挂 setItems 的列表
            // （appcompat AlertController.setupContent 中 message 优先、列表被丢弃），
            // LyricStorageActivity 的导出重名弹窗正是 title+items 的可用范例。
            .setTitle(getString(R.string.internal_copy_conflict_title, target.name))
            .setItems(
                arrayOf(
                    getString(R.string.conflict_overwrite),
                    getString(R.string.conflict_open_existing),
                    getString(R.string.conflict_rename_save),
                    getString(R.string.conflict_create_copy)
                )
            ) { _, which ->
                val mode = when (which) {
                    0 -> Prefs.INTERNAL_COPY_OVERWRITE
                    1 -> Prefs.INTERNAL_COPY_OPEN_EXISTING
                    2 -> Prefs.INTERNAL_COPY_RENAME
                    else -> Prefs.INTERNAL_COPY_CREATE_COPY
                }
                if (checkBox.isChecked) prefs.internalCopyConflictMode = mode
                when (mode) {
                    Prefs.INTERNAL_COPY_OVERWRITE -> adoptInternalCopy(target, content)
                    Prefs.INTERNAL_COPY_OPEN_EXISTING -> openExistingInternalCopy(target)
                    Prefs.INTERNAL_COPY_RENAME -> promptConflictRenameSave(target, content)
                    else ->
                        // 创建副本：自动取不重名的编号名字，无需再问文件名
                        adoptInternalCopy(File(lyricsStoreDir(), nextFreeName(target.name)), content)
                }
            }
            // 「仅打开」：不保存副本，仅本次查看（不属于可记忆的操作，勾选也对它无效）
            .setNegativeButton(R.string.conflict_cancel_only_open) { _, _ -> pendingManualAdd = false }
            .setView(container)
            .create()
        // 双保险：setCancelable(false) 之外再明确禁掉外部点击，避免 ROM 差异
        dialog.setCanceledOnTouchOutside(false)
        // 流程第一步没有上一步可回：按返回键＝执行返回，即关闭弹窗并走 Activity
        // 既有的返回逻辑（退出沉浸/编辑模式，否则 finish 回到上一个界面/应用）。
        // 不落副本、不登记、不弹「仅打开」之外的隐式决策；手动添加标记在此复位。
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                pendingManualAdd = false
                dialog.dismiss()
                // 不在按键分发过程中直接销毁 Activity，post 到下一帧再执行返回
                Handler(Looper.getMainLooper()).post {
                    if (!isFinishing && !isDestroyed) {
                        this@MainActivity.onBackPressedDispatcher.onBackPressed()
                    }
                }
                true
            } else false
        }
        dialog.setOnShowListener {
            // M3 弹窗按钮默认 singleLine + ellipsize=end，这么长的提示会被截断成「…」；
            // 显示后解除单行限制让它折行（最多 3 行），保证文案完整可见
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                isSingleLine = false
                ellipsize = null
                maxLines = 3
            }
        }
        dialog.show()
    }

    /** 冲突弹窗「打开已有文件」：放弃刚载入的外部内容，改为打开目录中的已有副本。 */
    private fun openExistingInternalCopy(target: File) {
        val manual = pendingManualAdd
        // 从冲突弹窗选择已有文件属于用户主动打开：记最近打开并写歌曲-歌词绑定
        loadInternal(target, recordRecent = true)
        if (manual) {
            // 手动添加：用户的选择优先，覆盖上面的普通绑定（不受相似度校验限制）
            MusicProgress.title?.takeIf { it.isNotBlank() }?.let { title ->
                LyricBindings.putForce(this, title, MusicProgress.artist.orEmpty(),
                    LyricBindings.bindingValue(this, target))
                toast(getString(R.string.manual_add_bound))
            }
        }
        pendingManualAdd = false
    }

    /**
     * 冲突弹窗「重命名保存」：输入新名字后把外部内容保存为新文件。
     * 按返回键＝返回上一步（回到冲突操作选择弹窗重新选择处理方式），不是悄悄退出流程；
     * 点弹窗外部仍不可关闭，明确「取消」按钮才是放弃保存。
     */
    private fun promptConflictRenameSave(target: File, content: String) {
        val defaultName = nextFreeName(target.name)
        val density = resources.displayMetrics.density
        val pad = (density * 20).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.rename_hint)
            setText(defaultName)
            setSingleLine(true)
            setTextColor(resolvePrimaryTextColor())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(pad, pad / 2, pad, 0) }
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            // 默认禁止返回键 / 点外部关闭；返回键由下面的 OnKeyListener 接管为「返回上一步」
            .setCancelable(false)
            .setTitle(R.string.conflict_rename_save)
            .setView(container)
            .setPositiveButton(R.string.dialog_confirm, null)
            .setNegativeButton(R.string.dialog_cancel) { _, _ -> pendingManualAdd = false }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        // 返回键＝返回上一步：关掉输入框、重新显示冲突操作选择弹窗，让用户重选处理方式。
        // 即使是从「已记住重命名保存」直接进到本输入框，也回到选择弹窗；
        // 此路径不落副本、不写偏好、不复位 pendingManualAdd（流程还没结束）。
        // Dialog.dispatchKeyEvent 先派发给 OnKeyListener，返回 true 即消费掉 BACK，
        // 因此 setCancelable(false) 下仍只有这一条返回键出口。
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                showInternalCopyConflictDialog(target, content)
                true
            } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty()
                val newName = sanitizeLyricFileName(raw)
                when {
                    newName.isEmpty() -> {
                        input.error = getString(R.string.rename_empty)
                        return@setOnClickListener
                    }
                    newName == target.name -> {
                        // 与冲突文件同名：引导换名，避免绕过本次防护直接覆盖
                        input.error = getString(R.string.conflict_rename_exists)
                        return@setOnClickListener
                    }
                    File(lyricsStoreDir(), newName).exists() -> {
                        input.error = getString(R.string.conflict_rename_exists)
                        return@setOnClickListener
                    }
                }
                input.error = null
                dialog.dismiss()
                adoptInternalCopy(File(lyricsStoreDir(), newName), content)
            }
        }
        dialog.show()
        input.requestFocus()
        // 默认只选中主体部分，保留扩展名，改歌名时不必重新输入后缀
        val dot = defaultName.lastIndexOf('.')
        if (dot > 0) input.setSelection(0, dot) else input.setSelection(0, defaultName.length)
    }

    /** 为「重命名保存」生成默认候选名：主体(1).扩展、主体(2)…直至不重名。 */
    private fun nextFreeName(name: String): String {
        val dir = lyricsStoreDir()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else "lrc"
        var candidate = name
        var index = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base($index).$ext"
            index++
        }
        return candidate
    }

    /**
     * 打开应用内部存储的歌词副本。
     * [recordRecent] 为用户主动打开时置 true（记入最近打开列表）；
     * 自动匹配/恢复等系统驱动的加载不传，避免 churn 最近列表。
     */
    private fun loadInternal(file: File, recordRecent: Boolean = false) {
        // 内部目录中的文件可能来自原始字节拷贝（GBK 等编码），
        // 必须用编码探测读取并保留 charset/bom，保证写回时 round-trip 不乱码
        val loaded = runCatching { DocumentIo.decode(file.readBytes()) }.getOrNull() ?: return
        val text = loaded.text
        markManualOpen()
        pendingNewLyricName = null
        currentUri = null
        internalFile = file
        currentName = file.name
        charset = loaded.charset
        bom = loaded.bom
        dirty = false
        docText = text
        lyricLines = LyricsParser.parse(text)
        Session.lyricLines = lyricLines
        // 持久化最后显示的歌词文件，供桌面歌词服务冷启动恢复
        prefs.lastLyricFile = file.absolutePath
        adapter.submit(lyricLines)
        currentIndex = -1
        updateTitle()
        // 用户主动打开（标题栏列表、冲突弹窗）需刷新"最近一次打开"记录，
        // 否则自动匹配的最近打开加分落不到该文件上
        if (recordRecent) {
            prefs.addRecentFile(file.absolutePath, file.name)
            // 显式选择必须同步到自动匹配状态，否则稍后重新匹配 / 界面重建
            // 会用旧的 Session.matchedFile 把当前文件切回去
            adoptExplicitLyricChoice(file)
        }
    }

    /**
     * 从桌面歌词面板拉起界面时恢复歌词。
     *
     * 优先加载"当前播放歌曲自动匹配到的歌词"（[Session.matchedFile]）——桌面歌词显示的就是
     * 这份歌词，保证点开后界面与桌面歌词一致；若直接取最近打开记录，可能加载到上一首或
     * 更早的歌曲歌词（表现为跳到无关歌词）。自动匹配不可用或无匹配结果时，回退到最近打开副本。
     */
    private fun restoreForDesktopPanel() {
        val matched = if (prefs.autoMatchLyrics) Session.matchedFile else null
        if (matched != null && matched.exists()) {
            loadInternal(matched)
            return
        }
        restoreLastOpened()
    }

    /** 恢复最近打开的内部歌词副本（用于从桌面歌词面板拉起时）。 */
    private fun restoreLastOpened() {
        val recent = prefs.getRecentFiles().firstOrNull() ?: return
        val file = File(recent.uri)
        if (file.exists()) {
            loadInternal(file)
        } else {
            showEmptyState()
        }
    }

    private fun updateTitle() {
        // 未落盘的新歌词在标题上标明"未保存"，避免误以为文件已经存在
        titleTextView?.text = currentName
            ?: if (pendingNewLyricName != null) getString(R.string.new_lyric_unsaved)
            else getString(R.string.app_name)
    }

    /**
     * 把标题栏歌词文件名的最大宽度限制到「工具栏可用宽度 - 右侧菜单宽度」。
     *
     * 标题是加进 Toolbar 的普通子 View，Toolbar 量它时按整条工具栏的可用宽度给
     * AT_MOST，长文件名会一直延伸到菜单图标下面，看起来就是「显示不全」。
     * 限制 maxWidth 之后，标题的可见区域正好停在菜单左边，超长部分由
     * [MarqueeTextView] 循环滚动展示。
     *
     * 宽度没变化时不重复赋值：TextView.setMaxWidth 会无条件 requestLayout，
     * 与 onLayoutChange 监听叠加会导致无限重排。
     */
    private fun applyTitleWidthLimit() {
        val tv = titleTextView ?: return
        val bar = binding.toolbar
        // 右侧菜单实际占用的宽度；菜单还没 inflate 时先不动，等下一次布局回调再算
        val menuView = (0 until bar.childCount)
            .map { bar.getChildAt(it) }
            .filterIsInstance<androidx.appcompat.widget.ActionMenuView>()
            .firstOrNull { it.visibility == android.view.View.VISIBLE }
            ?: return
        val available = bar.width - bar.paddingLeft - bar.paddingRight - menuView.width
        if (available <= 0) return
        if (tv.maxWidth != available) tv.maxWidth = available
    }

    // ---------------------------------------------------------------- 重命名当前歌词

    /**
     * 标题栏列表最上方「重命名」：改的是当前打开歌词文件的名字。
     * 内部副本直接改磁盘文件名（后续保存仍写回该文件）；只有 SAF URI 时
     * 用 DocumentsContract.renameDocument 改原始文档。
     */
    private fun promptRenameCurrentLyric() {
        val oldName = currentName
        if (oldName.isNullOrEmpty() || (internalFile == null && currentUri == null)) {
            toast(R.string.rename_no_file)
            return
        }
        if (pendingNewLyricName != null) {
            toast(R.string.rename_unsaved)
            return
        }
        val density = resources.displayMetrics.density
        val pad = (density * 20).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.rename_hint)
            setText(oldName)
            setSingleLine(true)
            setTextColor(resolvePrimaryTextColor())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(pad, pad / 2, pad, 0) }
            )
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_confirm, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty()
                val newName = sanitizeLyricFileName(raw)
                when {
                    newName.isEmpty() -> {
                        input.error = getString(R.string.rename_empty)
                        return@setOnClickListener
                    }
                    newName == oldName -> {
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    renameTargetExists(newName) -> {
                        input.error = getString(R.string.rename_exists)
                        return@setOnClickListener
                    }
                }
                input.error = null
                dialog.dismiss()
                performRename(oldName, newName)
            }
        }
        dialog.show()
        input.requestFocus()
        // 默认只选中主体部分，保留扩展名，改歌名时不必重新输入后缀
        val dot = oldName.lastIndexOf('.')
        if (dot > 0) input.setSelection(0, dot) else input.setSelection(0, oldName.length)
    }

    /** 重命名输入清洗：路径分隔符与文件名非法字符统一替换为下划线。 */
    private fun sanitizeLyricFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimStart('.')

    /** 内部歌词目录里是否已存在同名文件（忽略大小写，排除自身）。 */
    private fun renameTargetExists(name: String): Boolean {
        val file = internalFile ?: return false
        val dir = file.parentFile ?: lyricsStoreDir()
        return dir.listFiles()?.any {
            it != file && it.name.equals(name, ignoreCase = true)
        } == true
    }

    private fun performRename(oldName: String, newName: String) {
        val file = internalFile
        if (file != null) {
            val target = File(file.parentFile ?: lyricsStoreDir(), newName)
            if (target.exists()) {
                toast(R.string.rename_exists)
                return
            }
            if (!runCatching { file.renameTo(target) }.getOrDefault(false)) {
                toast(R.string.rename_failed)
                return
            }
            LyricIndex.invalidate()
            finishRename(
                oldName, newName, file.absolutePath, target.absolutePath,
                newFile = target, newUri = null
            )
            return
        }
        val uri = currentUri ?: run {
            toast(R.string.rename_no_file)
            return
        }
        val renamed = runCatching {
            android.provider.DocumentsContract.renameDocument(contentResolver, uri, newName)
        }.getOrNull()
        // 部分 provider 改名后返回 null 表示 Uri 未变，用实际名字判断是否成功
        val finalUri = renamed ?: uri
        val actual = runCatching { DocumentIo.displayName(contentResolver, finalUri) }.getOrNull()
        if (actual == null || !actual.equals(newName, ignoreCase = true)) {
            toast(R.string.rename_failed)
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                finalUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        finishRename(
            oldName, newName, uri.toString(), finalUri.toString(),
            newFile = null, newUri = finalUri
        )
    }

    /**
     * 改名后同步所有按名字/路径记录的状态：当前文档、最近打开、歌曲-歌词绑定、
     * 桌面歌词冷启动恢复文件、自动匹配结果。绑定迁移必须紧跟 renameTo——
     * LyricBindings.get() 会清理指向已不存在文件的绑定。
     */
    private fun finishRename(
        oldName: String,
        newName: String,
        oldKey: String,
        newKey: String,
        newFile: File?,
        newUri: Uri?,
    ) {
        // 正在查看的文件改名后就是最近打开的第一项
        prefs.removeRecentFile(oldKey)
        prefs.addRecentFile(newKey, newName)
        LyricBindings.renameFile(this, oldName, newName)
        // 独立在线歌词缓存目录内的文件改名时同步自己的索引
        OnlineLyricCache.renameFile(this, oldName, newName)

        if (newFile != null) {
            internalFile = newFile
            currentUri = null
            prefs.lastLyricFile = newFile.absolutePath
            // 显式选择记录的是绝对路径，改名后要跟着迁移，否则文件已不存在、
            // 自动匹配会重新挑一份旧歌词
            if (Session.explicitLyricPath == oldKey) {
                Session.explicitLyricPath = newFile.absolutePath
            }
            Session.internalPath = newFile.absolutePath
            Session.uri = null
            // 自动匹配结果可能正指向旧文件，换成新路径并通知桌面歌词刷新
            val matched = Session.matchedFile
            if (matched != null &&
                (matched.absolutePath == oldKey || matched.name == oldName)
            ) {
                Session.matchedFile = newFile
                Session.matchVersion++
            }
        } else if (newUri != null) {
            currentUri = newUri
            internalFile = null
            Session.uri = newUri
            Session.internalPath = null
        }
        currentName = newName
        Session.name = newName
        // 同步匹配版本号，避免下一帧 tick 用旧结果重复加载同一份歌词
        appliedMatchVersion = Session.matchVersion
        updateTitle()
        toast(getString(R.string.rename_success, newName))
    }

    // ------------------------------------------------------------ 标题栏列表「当前播放」高亮

    /**
     * 当前主页正在显示（对应当下播放歌曲）的歌词文件身份。
     * 内部副本路径优先，其次是外部 SAF URI；两者都没有时（纯文本分享 / 新建未落盘）
     * 才退化为按文件名匹配。放进 Session.matchedFile 作为兜底，覆盖自动匹配刚完成、
     * 界面字段尚未同步的一帧。
     */
    private fun currentLyricPath(): String? =
        internalFile?.absolutePath ?: Session.internalPath ?: Session.matchedFile?.absolutePath

    private fun currentLyricUri(): String? = currentUri?.toString() ?: Session.uri?.toString()

    private fun currentLyricName(): String? = currentName ?: Session.name

    /** 判断列表条目是否为当前正在播放的歌词。 */
    private fun isCurrentLyricFile(pathOrUri: String?, name: String?): Boolean {
        val path = currentLyricPath()
        val uri = currentLyricUri()
        if (path != null && pathOrUri != null && pathOrUri.startsWith("/") &&
            File(pathOrUri).absolutePath == File(path).absolutePath
        ) return true
        if (uri != null && pathOrUri != null && pathOrUri == uri) return true
        if (path == null && uri == null) {
            val cur = currentLyricName()
            return !cur.isNullOrEmpty() && cur == name
        }
        return false
    }

    private fun isCurrentLyricFile(file: File): Boolean =
        isCurrentLyricFile(file.absolutePath, file.name)

    /** 显示最近打开文件的列表。内部副本路径不受 SAF 权限回收影响，可始终打开。 */
    private fun showRecentFilesPopup(anchor: View) {
        val files = prefs.getRecentFiles()
        // 正在播放的歌词置顶到「重命名」下方，其余条目仍按最近打开顺序排列
        val currentPos = files.indexOfFirst { isCurrentLyricFile(it.uri, it.name) }
        val displayFiles = if (currentPos >= 0) {
            listOf(files[currentPos]) + files.filterIndexed { i, _ -> i != currentPos }
        } else files
        // 最上方固定为「重命名当前歌词」
        val labels = mutableListOf(getString(R.string.action_rename))
        val disabled = mutableSetOf<Int>()
        if (displayFiles.isEmpty()) {
            labels.add(getString(R.string.recent_files_empty))
            disabled.add(1)
        } else {
            displayFiles.forEach { labels.add(it.name) }
        }
        // 置顶后高亮行固定在索引 1（顶部固定项之后）
        val highlightIndex = if (currentPos >= 0) 1 else null
        showTitleFilesPopup(anchor, labels, disabled, highlightIndex) { index ->
            when (index) {
                0 -> promptRenameCurrentLyric()
                else -> {
                    val idx = index - 1
                    if (idx in displayFiles.indices) {
                        val uriStr = displayFiles[idx].uri
                        if (uriStr.startsWith("/")) {
                            // 内部副本路径；从最近列表打开属于用户主动打开，
                            // 需刷新"最近一次打开"记录，让自动匹配加分生效
                            loadInternal(File(uriStr), recordRecent = true)
                        } else {
                            runCatching { load(Uri.parse(uriStr)) }
                        }
                    }
                }
            }
        }
    }

    /**
     * 显示与当前播放歌曲匹配到的歌词文件列表（标题栏点击列表的默认选项）。
     * 匹配逻辑与桌面歌词候选列表一致：按标题/作者相似度（用户设置的阈值）筛选。
     * 最上方固定为「重命名」，其下是手动添加入口与匹配到的歌词文件。
     */
    private fun showMatchedFilesPopup(anchor: View, showAll: Boolean = false) {
        val title = MusicProgress.title?.trim().takeUnless { it.isNullOrEmpty() }
        val artist = MusicProgress.artist?.trim().orEmpty()
        val files = if (title != null) {
            val dir = File(filesDir, "lyrics")
            val matched = LyricMatcher.matches(
                dir, title, artist,
                prefs.matchSimilarity / 100.0
            ).toMutableList()
            // 这里只扫描歌词存储目录 lyrics/，独立在线歌词缓存目录
            // online_lyrics/ 不参与本列表与相似度匹配，两者完全分开处理。
            // 手动添加/显式绑定过的文件可能低于相似度阈值而不在匹配结果中，
            // 并入列表置顶显示，确保用户的选择始终可见、可回切。
            // 绑定值可能带 online_lyrics/ 前缀，必须走统一解析。
            val boundName = LyricBindings.get(this, title, artist)
            if (boundName != null) {
                val boundFile = LyricBindings.resolveFile(this, boundName)
                if (boundFile.exists() && matched.none { it.absolutePath == boundFile.absolutePath }) {
                    matched.add(0, boundFile)
                }
            }
            matched
        } else {
            emptyList()
        }
        // 最上方固定为「重命名当前歌词」，其次是手动添加入口
        val labels = mutableListOf(
            getString(R.string.action_rename),
            getString(R.string.manual_add_lyric)
        )
        // 候选过多时限量渲染：匹配结果已走缓存，避免一次性构造上百个
        // HorizontalScrollView + TextView 拖慢弹窗弹出。
        val visible = if (showAll) files else files.take(20)
        val hasMore = !showAll && files.size > visible.size
        // 限量显示时当前播放项可能排在 20 条之外，也把它并进来，保证置顶生效
        val currentFile = files.firstOrNull { isCurrentLyricFile(it) }
        val candidates = if (!showAll && currentFile != null &&
            visible.none { it.absolutePath == currentFile.absolutePath }
        ) listOf(currentFile) + visible else visible
        // 正在播放的歌词置顶到两个固定入口下方，其余候选保持原顺序
        val currentPos = candidates.indexOfFirst { isCurrentLyricFile(it) }
        val displayFiles = if (currentPos > 0) {
            listOf(candidates[currentPos]) + candidates.filterIndexed { i, _ -> i != currentPos }
        } else candidates
        displayFiles.forEach { labels.add(it.name) }
        if (hasMore) labels.add(getString(R.string.match_show_all, files.size))
        // 置顶后高亮行固定在索引 2（重命名 + 手动添加之后）
        val highlightIndex = if (currentPos >= 0) 2 else null
        showTitleFilesPopup(anchor, labels, highlightIndex = highlightIndex) { index ->
            when (index) {
                0 -> promptRenameCurrentLyric()
                1 -> {
                    // 手动添加：系统文件选择器选定后强制绑定，计入最近打开，下次优先匹配
                    pendingManualAdd = true
                    startManualAddPick()
                }
                2 + displayFiles.size -> if (hasMore) showMatchedFilesPopup(anchor, showAll = true)
                else -> {
                    val idx = index - 2
                    if (idx in displayFiles.indices) {
                        // 从匹配列表打开属于用户主动打开：记入最近打开并写入歌曲-歌词绑定
                        // （loadInternal(recordRecent = true) 内部已处理绑定写入）
                        loadInternal(displayFiles[idx], recordRecent = true)
                    }
                }
            }
        }
    }

    /**
     * 标题栏歌词文件列表弹窗。
     *
     * 系统 PopupMenu 的菜单项固定单行 + ellipsize=end，文件名过长时后半段永远看不到。
     * 这里每行套一层 HorizontalScrollView，内里的 TextView 按全文宽度测量（不截断、
     * 不省略），超长时可以左右拖动查看完整文件名（渐隐边缘 + 滚动条提示）；
     * 轻触该行仍执行原来的打开/重命名逻辑，条目过多时整体上下滚动。
     *
     * [highlightIndex] 指向「当前正在播放的歌词」所在行：用主调色底色 + 加粗着色。
     * 调用方已把该行置顶到固定入口下方，弹窗默认从顶部显示，无需额外滚动。
     */
    private fun showTitleFilesPopup(
        anchor: View,
        labels: List<String>,
        disabledIndices: Set<Int> = emptySet(),
        highlightIndex: Int? = null,
        onPick: (index: Int) -> Unit,
    ) {
        if (labels.isEmpty()) return
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val textSizeSp = 15f
        val textColor = resolvePrimaryTextColor()
        val accentColor = appearance.effectiveAccent
        // 用同字号 TextView 的 Paint 量文本宽度：只在有超长文件名时才让弹窗变宽
        val measurePaint = TextView(this).apply { textSize = textSizeSp }.paint
        val padH = dp(16)
        val rowHeight = dp(48)
        val minWidth = dp(200)
        val maxWidth = (resources.displayMetrics.widthPixels - dp(16)).coerceAtLeast(minWidth)
        val longestText = labels.maxOf { measurePaint.measureText(it) }
        val popupWidth = (longestText.toInt() + padH * 2).coerceIn(minWidth, maxWidth)
        val contentHeight = labels.size * rowHeight + dp(8)
        val maxHeight = (resources.displayMetrics.heightPixels * 0.6f).toInt()
            .coerceAtLeast(rowHeight)
        val popupHeight = contentHeight.coerceAtMost(maxHeight)

        val selectableBg = android.util.TypedValue().let { value ->
            if (theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, value, true
                ) && value.resourceId != 0
            ) value.resourceId else 0
        }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        lateinit var popup: PopupWindow
        labels.forEachIndexed { index, label ->
            val enabled = index !in disabledIndices
            val isCurrent = index == highlightIndex
            val overlong = measurePaint.measureText(label) > popupWidth - padH * 2
            val hsv = HorizontalScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                )
                isHorizontalScrollBarEnabled = overlong && enabled
                isHorizontalFadingEdgeEnabled = overlong && enabled
                setFadingEdgeLength(dp(24))
                isClickable = enabled
                isFocusable = enabled
                contentDescription = if (isCurrent) {
                    "$label（${getString(R.string.title_list_current)}）"
                } else label
                when {
                    // 当前播放项：主调色淡底 + 圆角，替换掉默认的按压态背景
                    isCurrent -> background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(
                            Color.argb(
                                0x38, Color.red(accentColor),
                                Color.green(accentColor), Color.blue(accentColor)
                            )
                        )
                    }
                    selectableBg != 0 -> background = androidx.core.content.ContextCompat
                        .getDrawable(this@MainActivity, selectableBg)?.mutate()
                }
            }
            val tv = TextView(this).apply {
                text = label
                setTextColor(if (isCurrent) accentColor else textColor)
                if (isCurrent) typeface = android.graphics.Typeface.DEFAULT_BOLD
                alpha = if (enabled) 1f else 0.6f
                textSize = textSizeSp
                // 关键：单行但绝不 ellipsize，TextView 会按全文宽度测量，
                // 交由外层 HorizontalScrollView 左右拖动。
                // setSingleLine(true) 会顺带打开 TextView 自带的横向滚动
                // （测量宽度变成 VERY_WIDE），必须关掉，否则外层拖动范围会异常
                isSingleLine = true
                setHorizontallyScrolling(false)
                ellipsize = null
                gravity = Gravity.CENTER_VERTICAL
                setPadding(padH, 0, padH, 0)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            hsv.addView(tv)
            if (enabled) {
                var picked = false
                val pick = {
                    if (!picked) {
                        picked = true
                        popup.dismiss()
                        onPick(index)
                    }
                }
                hsv.setOnClickListener { pick() }
                val detector = GestureDetector(
                    this,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean = true
                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            hsv.performClick()
                            return true
                        }
                    }
                )
                hsv.setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    // 返回 false：把事件交回 HorizontalScrollView 处理横向拖动，
                    // 纵向拖动由外层 ScrollView 拦截，两者互不干扰
                    false
                }
            }
            rows.addView(hsv)
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = contentHeight > popupHeight
            addView(
                rows,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        // 当前播放项由调用方置顶，ScrollView 保持默认顶部位置即可，不做自动滚动
        val content = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(resolvePopupSurfaceColor())
            }
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        popup = PopupWindow(content, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            elevation = dp(6).toFloat()
            // PopupWindow 需要有背景才会创建可响应外部点击 / 返回键的容器
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        popup.showAsDropDown(anchor, 0, 0)
    }

    /** 解析 M3 弹窗背景色，供代码动态创建的标题栏列表使用。 */
    private fun resolvePopupSurfaceColor(): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainer, value, true
            )
        ) return 0xFF2B2930.toInt()
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    /**
     * 新建歌词：先弹出命名弹窗（取消 / 确认），同时可选择歌词格式
     * （LRC / SRT / TXT，记住上次的选择）。确认后只记住文件名并直接进入
     * 空白编辑状态，不创建任何文件；用户执行保存行为时才真正新增文件。
     */
    private fun createNewLyric() {
        val density = resources.displayMetrics.density
        val pad = (density * 20).toInt()
        // 代码创建的控件显式取主题主文字色，保证深色/浅色主题下都可读
        val textColor = resolvePrimaryTextColor()
        val input = EditText(this).apply {
            hint = getString(R.string.new_lyric_default_name)
            setText(getString(R.string.new_lyric_default_name))
            setSelectAllOnFocus(true)
            setPadding(pad, pad / 2, pad, pad / 2)
            setTextColor(textColor)
        }
        // 格式选择：LRC / SRT / TXT，默认记住上次的选择
        val spinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(
                this@MainActivity, android.R.layout.simple_spinner_item,
                listOf(
                    getString(R.string.new_lyric_format_lrc),
                    getString(R.string.new_lyric_format_srt),
                    getString(R.string.new_lyric_format_txt),
                )
            ) {
                override fun getView(
                    position: Int, convertView: View?, parent: android.view.ViewGroup,
                ): View = super.getView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(textColor)
                }

                override fun getDropDownView(
                    position: Int, convertView: View?, parent: android.view.ViewGroup,
                ): View = super.getDropDownView(position, convertView, parent).also {
                    (it as? TextView)?.setTextColor(textColor)
                }
            }.apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(prefs.newLyricFormat.coerceIn(FORMAT_LRC, FORMAT_TXT))
        }
        // 实时提示所选格式对应的扩展名，避免建完才发现格式不对
        val extHint = TextView(this).apply {
            textSize = 12f
            setTextColor(textColor)
            setPadding(pad, 0, pad, pad / 2)
        }
        fun syncExtHint() {
            val format = spinner.selectedItemPosition.coerceIn(FORMAT_LRC, FORMAT_TXT)
            extHint.text = getString(R.string.new_lyric_ext_hint, formatExt(format))
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long,
            ) = syncExtHint()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        syncExtHint()
        val formatRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.new_lyric_format)
                textSize = 14f
                setTextColor(textColor)
            })
            addView(spinner, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                marginStart = (density * 12).toInt()
            })
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(formatRow)
            addView(extHint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_new_lyric)
            .setView(container)
            .setPositiveButton(R.string.dialog_confirm) { _, _ ->
                val format = spinner.selectedItemPosition.coerceIn(FORMAT_LRC, FORMAT_TXT)
                prefs.newLyricFormat = format
                startNewLyric(input.text.toString().trim(), formatExt(format))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
        input.requestFocus()
        input.selectAll()
    }

    /** 解析当前主题的主文字颜色，用于代码动态创建的弹窗控件。 */
    private fun resolvePrimaryTextColor(): Int {
        val value = android.util.TypedValue()
        if (!theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)) {
            return 0xFFFFFFFF.toInt()
        }
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, value.resourceId)
        } else {
            value.data
        }
    }

    /** 新建歌词格式对应的文件扩展名。 */
    private fun formatExt(format: Int): String = when (format) {
        FORMAT_SRT -> "srt"
        FORMAT_TXT -> "txt"
        else -> "lrc"
    }

    /**
     * 进入"未保存的新歌词"编辑状态：只记住弹窗里确定的文件名，不创建文件。
     * 空名回退默认名、清洗非法字符、统一为所选格式（[ext]）的扩展名；
     * 真正的新增文件由 [savePendingNewLyric] 在用户执行保存时完成。
     */
    private fun startNewLyric(rawName: String, ext: String) {
        var name = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (name.isEmpty()) name = getString(R.string.new_lyric_default_name)
        // 统一为所选格式扩展名：不带该后缀（或带其他后缀）时统一替换
        if (!name.endsWith(".$ext", ignoreCase = true)) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            name = "$base.$ext"
        }
        pendingNewLyricName = name
        savingNewLyric = false
        markManualOpen()
        currentUri = null
        internalFile = null
        currentName = null
        charset = StandardCharsets.UTF_8
        bom = ByteArray(0)
        docText = ""
        lyricLines = emptyList()
        Session.lyricLines = lyricLines
        adapter.submit(lyricLines)
        currentIndex = -1
        updateTitle()
        // 此处不写文件、不记最近打开；dirty 由 enterEditMode 重置为 false
        enterEditMode()
    }

    /**
     * 用户第一次保存新建歌词时，才在歌词目录真正新增文件：
     * 重名编号加在文件名主体之后、扩展名之前，写入当前编辑内容并把当前文档
     * 切换为这份已保存文件（之后保存就直接写回该文件）。
     */
    private fun savePendingNewLyric(name: String) {
        if (savingNewLyric) return
        savingNewLyric = true
        val dir = lyricsStoreDir()
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1) else "lrc"
        var candidate = name
        var index = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base($index).$ext"
            index++
        }
        val f = File(dir, candidate)
        val text = docText
        Thread {
            val ok = runCatching { f.writeText(text, Charsets.UTF_8) }.isSuccess
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                savingNewLyric = false
                if (!ok) {
                    showSaveResult(false, getString(R.string.save_failed, candidate))
                    return@runOnUiThread
                }
                LyricIndex.invalidate()
                pendingNewLyricName = null
                currentUri = null
                internalFile = f
                currentName = f.name
                charset = StandardCharsets.UTF_8
                bom = ByteArray(0)
                dirty = false
                lyricLines = LyricsParser.parse(text)
                Session.lyricLines = lyricLines
                prefs.lastLyricFile = f.absolutePath
                adapter.submit(lyricLines)
                // 新建属于用户主动保存：此时才记入最近打开列表
                prefs.addRecentFile(f.absolutePath, f.name)
                updateTitle()
                showSaveResult(true, getString(R.string.saved))
            }
        }.start()
    }

    private fun saveCurrent() {
        docText = binding.editorText.text?.toString().orEmpty()
        // 新建歌词此前只进入了编辑状态、文件尚未创建：用户执行保存时才真正新增文件
        val pending = pendingNewLyricName
        if (pending != null && internalFile == null && currentUri == null) {
            savePendingNewLyric(pending)
            return
        }
        // 内部副本（含从其他应用打开后自动拉取到歌词目录的副本）优先写回：
        // 外部文档可能没有写权限，写原目录会失败
        val file = internalFile
        if (file != null) {
            Thread {
                val ok = runCatching { file.writeText(docText, Charsets.UTF_8) }.isSuccess
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (ok) {
                        LyricIndex.invalidate()
                        dirty = false
                        showSaveResult(true, getString(R.string.saved))
                    } else {
                        showSaveResult(false, getString(R.string.save_failed, "写入内部文件失败"))
                    }
                }
            }.start()
            return
        }
        val uri = currentUri
        if (uri != null) {
            writeTo(uri, switchToUri = false)
            return
        }
        promptSaveAsName()
    }

    private fun promptSaveAsName() {
        docText = binding.editorText.text?.toString().orEmpty()
        val dialogBinding = DialogSaveAsBinding.inflate(layoutInflater)
        // 未落盘的新歌词：用新建弹窗里确定的名字作为默认保存名
        val initial = currentName ?: pendingNewLyricName ?: "lyrics.lrc"
        dialogBinding.nameInput.setText(initial)
        val dot = initial.lastIndexOf('.')

        // 最近目录相关
        var sortDescending = true
        var dirAdapter: RecentDirAdapter? = null
        dirAdapter = RecentDirAdapter(
            onClick = { dir ->
                // 点击最近目录：直接保存到该目录（tree URI 已持有持久化写权限）
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()
                    .ifEmpty { initial }
                if (android.provider.DocumentsContract.isTreeUri(Uri.parse(dir.uri))) {
                    saveToTree(Uri.parse(dir.uri), name)
                } else {
                    // 旧数据可能是文件/目录 document URI，无写权限，回退到目录选择器
                    pendingSaveAsName = name
                    treeLauncher.launch(null)
                }
            },
            onSelectionChanged = {
                updateDirSelectionBar(dialogBinding, dirAdapter!!)
            }
        )
        dialogBinding.recentDirsRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        dialogBinding.recentDirsRecycler.adapter = dirAdapter
        refreshRecentDirs(dialogBinding, dirAdapter!!, sortDescending)

        // 排序切换
        dialogBinding.btnSortDirs.setOnClickListener {
            sortDescending = !sortDescending
            dialogBinding.btnSortDirs.text = getString(
                if (sortDescending) R.string.sort_newest else R.string.sort_oldest
            )
            refreshRecentDirs(dialogBinding, dirAdapter!!, sortDescending)
        }
        // 全选
        dialogBinding.btnSelectAllDirs.setOnClickListener { dirAdapter!!.selectAll() }
        // 取消全选
        dialogBinding.btnDeselectAllDirs.setOnClickListener { dirAdapter!!.deselectAll() }
        // 取消编辑
        dialogBinding.btnCancelDirEdit.setOnClickListener { dirAdapter!!.exitSelectMode() }
        // 删除选中
        dialogBinding.btnDeleteSelectedDirs.setOnClickListener {
            val uris = dirAdapter!!.getSelectedUris()
            if (uris.isNotEmpty()) {
                prefs.removeRecentDirs(uris)
                dirAdapter!!.exitSelectMode()
                refreshRecentDirs(dialogBinding, dirAdapter!!, sortDescending)
            }
        }
        // 全部删除
        dialogBinding.btnDeleteAllDirs.setOnClickListener {
            prefs.clearRecentDirs()
            dirAdapter!!.exitSelectMode()
            refreshRecentDirs(dialogBinding, dirAdapter!!, sortDescending)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_as_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save_as_to_downloads, null)
            .setNeutralButton(R.string.save_as_pick_location, null)
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                pendingCreateNewAfterSave = false
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    dialogBinding.nameLayout.error = getString(R.string.save_as_name_empty)
                    return@setOnClickListener
                }
                dialogBinding.nameLayout.error = null
                dialog.dismiss()
                saveAsWithName(name)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val name = dialogBinding.nameInput.text?.toString()?.trim().orEmpty()
                    .ifEmpty { initial }
                dialog.dismiss()
                pendingSaveAsName = name
                treeLauncher.launch(null)
            }
        }
        dialog.show()
        dialogBinding.nameInput.requestFocus()
        if (dot > 0) dialogBinding.nameInput.setSelection(0, dot)
        else dialogBinding.nameInput.setSelection(0, initial.length)
    }

    /** 刷新最近目录列表显示。 */
    private fun refreshRecentDirs(
        dialogBinding: DialogSaveAsBinding,
        adapter: RecentDirAdapter,
        sortDescending: Boolean,
    ) {
        val dirs = prefs.getRecentDirs()
        val sorted = if (sortDescending) dirs.sortedByDescending { it.timeMs } else dirs.sortedBy { it.timeMs }
        adapter.submit(sorted)
        val hasDirs = sorted.isNotEmpty()
        dialogBinding.recentDirsHeader.visibility = if (hasDirs) View.VISIBLE else View.GONE
        dialogBinding.recentDirsRecycler.visibility = if (hasDirs) View.VISIBLE else View.GONE
        updateDirSelectionBar(dialogBinding, adapter)
    }

    /** 根据选择模式与选中数量更新操作栏显示。 */
    private fun updateDirSelectionBar(
        dialogBinding: DialogSaveAsBinding,
        adapter: RecentDirAdapter,
    ) {
        val inSelect = adapter.isSelectMode()
        dialogBinding.selectionBar.visibility = if (inSelect) View.VISIBLE else View.GONE
        val hasSelection = adapter.selectedCount() > 0
        dialogBinding.btnDeleteSelectedDirs.alpha = if (hasSelection) 1f else 0.4f
    }

    private fun saveAsWithName(name: String) {
        val cleanName = name.substringAfterLast('/').substringAfterLast('\\')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (downloadsFileExists(cleanName)) {
                handleDuplicateDownload(cleanName)
            } else {
                val created = runCatching { createInDownloads(cleanName) }.getOrNull()
                if (created != null) {
                    writeTo(created, switchToUri = true, fallbackName = cleanName)
                    return
                }
            }
        }
        fallbackToSystemPicker(name)
    }

    /** 保存到下载目录时遇到重名，按用户设置的策略处理。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleDuplicateDownload(name: String) {
        when (prefs.duplicateStrategy) {
            Prefs.DUPLICATE_OVERWRITE -> overwriteDownload(name)
            Prefs.DUPLICATE_COPY -> copyDownload(name)
            else -> {
                val existing = findDownloadUri(name)
                showDuplicateDialog(
                    name,
                    onOverwrite = {
                        if (existing != null) writeTo(existing, switchToUri = true)
                        else overwriteDownload(name)
                    },
                    onCopy = { copyDownload(name) }
                )
            }
        }
    }

    /** 覆盖已存在的下载目录文件。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun overwriteDownload(name: String) {
        val existing = findDownloadUri(name)
        if (existing != null) {
            writeTo(existing, switchToUri = true, fallbackName = name)
        } else {
            // 找不到现有 uri，退化为新建
            val created = runCatching { createInDownloads(name) }.getOrNull()
            if (created != null) {
                writeTo(created, switchToUri = true, fallbackName = name)
            } else {
                fallbackToSystemPicker(name)
            }
        }
    }

    /** 创建带编号的副本文件。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyDownload(name: String) {
        val uniqueName = buildUniqueDownloadName(name)
        val created = runCatching { createInDownloads(uniqueName) }.getOrNull()
        if (created != null) {
            writeTo(created, switchToUri = true, fallbackName = uniqueName)
        } else {
            fallbackToSystemPicker(name)
        }
    }

    /** 重名时弹出对话框让用户选择覆盖或创建副本，可勾选"记住操作"。 */
    private fun showDuplicateDialog(
        name: String,
        onOverwrite: () -> Unit,
        onCopy: () -> Unit,
    ) {
        val checkBox = android.widget.CheckBox(this).apply {
            text = getString(R.string.duplicate_remember)
            setPadding(48, 0, 48, 0)
            setTextColor(0xFF808080.toInt())
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(checkBox)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.duplicate_title)
            .setMessage(getString(R.string.duplicate_message, name))
            .setView(container)
            .setPositiveButton(R.string.duplicate_overwrite_btn) { _, _ ->
                if (checkBox.isChecked) prefs.duplicateStrategy = Prefs.DUPLICATE_OVERWRITE
                onOverwrite()
            }
            .setNegativeButton(R.string.duplicate_copy_btn) { _, _ ->
                if (checkBox.isChecked) prefs.duplicateStrategy = Prefs.DUPLICATE_COPY
                onCopy()
            }
            .setNeutralButton(R.string.dialog_cancel, null)
            .show()
    }

    /** 回退到目录选择器保存。 */
    private fun fallbackToSystemPicker(name: String) {
        toast(getString(R.string.save_as_downloads_failed))
        pendingSaveAsName = name
        treeLauncher.launch(null)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createInDownloads(name: String): Uri? {
        val cleanName = name.substringAfterLast('/').substringAfterLast('\\')
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
            // 不传 text/plain 等会被 MediaProvider 按 MIME 补出 .txt 后缀的类型
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, CREATE_FILE_MIME)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
        }
        val uri = runCatching {
            contentResolver.insert(
                android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                ),
                values
            )
        }.getOrNull()
        return uri
    }

    /** 在下载目录中查找指定名称文件的 Uri，找不到返回 null。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findDownloadUri(displayName: String): Uri? {
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        runCatching {
            contentResolver.query(
                android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                ),
                projection, selection, arrayOf(displayName), null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    )
                    return android.provider.MediaStore.Downloads.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY, id
                    )
                }
            }
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildUniqueDownloadName(name: String): String {
        if (!downloadsFileExists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base($index)$ext"
            if (!downloadsFileExists(candidate)) return candidate
            index++
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun downloadsFileExists(displayName: String): Boolean {
        val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
        val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        runCatching {
            contentResolver.query(
                android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                ),
                projection, selection, arrayOf(displayName), null
            )?.use { return it.count > 0 }
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryDownloadDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    /**
     * 写入下载目录后校验实际文件名：
     * · MediaStore 遇到重名冲突会自动改名，且编号加在扩展名之后（如 name.lrc (1)）；
     * · 部分 ROM 会按 MIME 给名字补出多余后缀（如 name.lrc.txt、name.lrc.bin）。
     * 前者按 name(1).lrc 规则纠正，后者直接改回用户期望的名字；最后把创建时
     * 用的中立 MIME 改回真实类型，保证文件仍能被文本应用识别。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ensureDownloadDisplayName(uri: Uri, requestedName: String) {
        val actual = queryDownloadDisplayName(uri)
        if (actual == null || actual == requestedName) {
            restoreDownloadMimeType(uri, requestedName)
            return
        }
        // 实际名只是期望名后面多了一个扩展名：判定为系统自动追加，直接改回
        if (actual.startsWith(requestedName, ignoreCase = true)) {
            val suffix = actual.substring(requestedName.length)
            if (Regex("^\\.[A-Za-z0-9]{1,8}$").matches(suffix) &&
                !downloadsFileExists(requestedName)
            ) {
                val renamed = runCatching {
                    contentResolver.update(
                        uri,
                        android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
                        },
                        null, null
                    ) > 0
                }.getOrDefault(false)
                if (renamed && queryDownloadDisplayName(uri) == requestedName) {
                    restoreDownloadMimeType(uri, requestedName)
                    return
                }
            }
        }
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val ext = if (dot > 0) requestedName.substring(dot) else ""
        for (index in 1..99) {
            val candidate = "$base($index)$ext"
            if (candidate == actual) break
            if (downloadsFileExists(candidate)) continue
            val renamed = runCatching {
                contentResolver.update(
                    uri,
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, candidate)
                    },
                    null, null
                ) > 0
            }.getOrDefault(false)
            if (renamed && queryDownloadDisplayName(uri) == candidate) break
        }
        restoreDownloadMimeType(uri, requestedName)
    }

    /** 创建时用了中立 MIME，写入完成后把 MediaStore 里的类型改回真实类型。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun restoreDownloadMimeType(uri: Uri, name: String) {
        // 部分 ROM 不允许 update MIME_TYPE，失败时静默忽略：文件名正确才是关键
        runCatching {
            contentResolver.update(
                uri,
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, guessMimeType(name))
                },
                null, null
            )
        }
    }

    /** 保存到最近目录（SAF tree URI）：按重名策略直接创建/覆盖文件。 */
    private fun saveToTree(treeUri: Uri, name: String) {
        if (!android.provider.DocumentsContract.isTreeUri(treeUri)) {
            fallbackToSystemPicker(name)
            return
        }
        val cleanName = name.substringAfterLast('/').substringAfterLast('\\')
        when (prefs.duplicateStrategy) {
            Prefs.DUPLICATE_OVERWRITE -> {
                val existing = findChildUri(treeUri, cleanName)
                if (existing != null) writeTo(existing, switchToUri = true)
                else createInTreeAndWrite(treeUri, cleanName)
            }
            Prefs.DUPLICATE_COPY -> createInTreeAndWrite(treeUri, buildUniqueChildName(treeUri, cleanName))
            else -> {
                val existing = findChildUri(treeUri, cleanName)
                if (existing != null) {
                    showDuplicateDialog(cleanName,
                        onOverwrite = { writeTo(existing, switchToUri = true) },
                        onCopy = { createInTreeAndWrite(treeUri, buildUniqueChildName(treeUri, cleanName)) })
                } else createInTreeAndWrite(treeUri, cleanName)
            }
        }
    }

    private fun createInTreeAndWrite(treeUri: Uri, displayName: String) {
        val treeDocId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull()
        if (treeDocId == null) {
            showSaveResult(false, getString(R.string.save_failed, "createDocument"))
            return
        }
        val parentUri = android.provider.DocumentsContract
            .buildDocumentUriUsingTree(treeUri, treeDocId)
        // 中立 MIME 避免 provider 按 text/plain 给非 .txt 名字补出 .txt 后缀；
        // 少数 provider 不认自定义类型时退回真实 MIME 再试一次
        val created = runCatching {
            android.provider.DocumentsContract.createDocument(
                contentResolver, parentUri, CREATE_FILE_MIME, displayName)
        }.getOrNull() ?: runCatching {
            android.provider.DocumentsContract.createDocument(
                contentResolver, parentUri, guessMimeType(displayName), displayName)
        }.getOrNull()
        if (created != null) {
            // 创建后仍可能被 provider 补后缀，改回期望名并用返回的新 Uri 继续写入，
            // 保证 currentUri / currentName 与磁盘上的实际名字一致
            val target = ensureTreeDisplayName(created, displayName)
            runCatching {
                contentResolver.takePersistableUriPermission(
                    target,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            writeTo(target, switchToUri = true)
        } else {
            showSaveResult(false, getString(R.string.save_failed, "createDocument"))
        }
    }

    /**
     * SAF createDocument 后校验实际名字：部分 provider 会按 MIME 追加扩展名
     * （text/plain → .txt），把 xxx.lrc 建成 xxx.lrc.txt。这里只纠正「期望名
     * 后面多出一个扩展名」这一种情况，不破坏 provider 自己的重名编号策略。
     */
    private fun ensureTreeDisplayName(uri: Uri, expected: String): Uri {
        val actual = runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: return uri
        if (actual == expected) return uri
        val suffix = if (actual.startsWith(expected, ignoreCase = true)) {
            actual.substring(expected.length)
        } else ""
        if (!Regex("^\\.[A-Za-z0-9]{1,8}$").matches(suffix)) return uri
        return runCatching {
            android.provider.DocumentsContract.renameDocument(contentResolver, uri, expected)
        }.getOrNull() ?: uri
    }

    private fun findChildUri(treeUri: Uri, displayName: String): Uri? {
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        runCatching {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val found = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    if (found == displayName) {
                        val docId = cursor.getString(cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                        return android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        return null
    }

    /** 生成目录内不重名的文件名，编号加在主体之后、扩展名之前。 */
    private fun buildUniqueChildName(treeUri: Uri, name: String): String {
        if (findChildUri(treeUri, name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$base($index)$ext"
            if (findChildUri(treeUri, candidate) == null) return candidate
            index++
        }
    }

    private fun guessMimeType(name: String): String = LyricsParser.mimeTypeFor(name)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun clearPendingFlag(uri: Uri) {
        contentResolver.update(
            uri,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null, null
        )
    }

    /**
     * 把当前文本写入指定 uri。
     *
     * @param switchToUri 写入成功后是否把当前文档切换为该 uri
     * @param fallbackName 非空表示这是"直接保存到下载目录"的写入；失败时清理占位记录并回退到系统文件选择器
     */
    private fun writeTo(uri: Uri, switchToUri: Boolean, fallbackName: String? = null) {
        val text = docText
        val cs = charset
        val bomBytes = bom
        Thread {
            val result = runCatching {
                DocumentIo.write(contentResolver, uri, text, cs, bomBytes)
                // 写入成功后清除 IS_PENDING 标志，避免文件名出现 .pending-xxx- 前缀
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { clearPendingFlag(uri) }
                    // 直接保存到下载目录时，纠正系统重名自动改名（编号跑到扩展名后）的问题
                    if (fallbackName != null) {
                        runCatching { ensureDownloadDisplayName(uri, fallbackName) }
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        dirty = false
                        if (switchToUri) {
                            // 另存为成功：新歌词已经落盘，清掉"待新建"状态
                            pendingNewLyricName = null
                            currentUri = uri
                            internalFile = null
                            currentName = runCatching {
                                DocumentIo.displayName(contentResolver, uri)
                            }.getOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')
                            updateTitle()
                        }
                        showSaveResult(true, getString(R.string.saved))
                    },
                    onFailure = { error ->
                        if (fallbackName != null) {
                            // 直接写入下载目录失败：删除残留的占位记录，改用目录选择器保存
                            runCatching { contentResolver.delete(uri, null, null) }
                            toast(getString(R.string.save_as_downloads_failed))
                            pendingSaveAsName = fallbackName
                            treeLauncher.launch(null)
                        } else {
                            showSaveResult(false, getString(R.string.save_failed, error.message.orEmpty()))
                        }
                    }
                )
            }
        }.start()
    }

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_message)
            .setPositiveButton(R.string.dialog_save) { _, _ -> saveCurrent() }
            .setNegativeButton(R.string.dialog_discard) { _, _ ->
                dirty = false
                exitEditMode()
            }
            .setNeutralButton(R.string.dialog_cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- 背景 / 字体

    /**
     * 旧数据迁移与降采样解码放后台线程，避免 setImageURI 在主线程解码大图造成卡顿；
     * 但已解码过的内存缓存会在主线程先同步贴出，让主题切换重建后的首帧就有背景图。
     */
    /** 自定义背景图模糊结果缓存（按图片 id）。避免每次 onResume 都重做盒式模糊。 */
    @Volatile private var customBlurId: String? = null
    @Volatile private var customBlurBitmap: Bitmap? = null

    private fun cachedBlurredCustom(id: String): Bitmap? =
        customBlurBitmap?.takeIf { customBlurId == id && !it.isRecycled }

    /** 计算（或复用）某张自定义背景图的模糊版本；失败时返回 null，由调用方回退原图。 */
    private fun blurredCustom(id: String, src: Bitmap): Bitmap? {
        cachedBlurredCustom(id)?.let { return it }
        val out = HomeAppearance.blurredImage(src) ?: return null
        customBlurId = id
        // 不主动 recycle 旧缓存：它可能正被 ImageView 持有，交给 GC 回收更安全。
        customBlurBitmap = out
        return out
    }

    /**
     * 铺「自定义背景图片」里当前选中的图片（含模糊与显示位置变换）。
     * 只在 [albumBlurEffective] 为 false 时调用——既包括「封面模糊背景」关闭，
     * 也包括无专辑封面时按「封面模糊背景临时关闭」处理的情形。
     */
    private fun applyBackground() {
        val view = binding.backgroundImage
        // 自定义背景图由「修改图片显示位置」控制缩放/位移，基准必须是 fitCenter；
        // 封面模糊背景会临时改成 centerCrop，切回来时在这里还原。
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        val seq = ++backgroundLoadSeq
        // 「自定义背景图片模糊」开启时对自定义图做模糊；封面模糊背景生效时
        // 那一条路径会直接返回，自定义图（含其模糊结果）完全让位。
        val blurCustom = appearance.customBackgroundBlur
        // 封面到位 / 重新开启封面模糊背景时该值立即变 false，
        // 在途解码回调自行作废，不会盖住刚贴上的模糊封面。
        fun customAllowed(): Boolean = !albumBlurEffective
        // 防御性分支：本方法只在 albumBlurEffective == false 时被调用，正常流程
        // 不会进入这里。仅当调用后封面立即到位、或用户即时重新开启
        // 「封面模糊背景」时，用于隐藏自定义图并让在途解码任务因 seq 变化失效。
        if (!customAllowed()) {
            albumBlurAppliedVersion = -1
            albumBlurAppliedSongVersion = -1
            albumBlurShowing = false
            view.setImageDrawable(null)
            // 不能设成 GONE：FrameLayout 默认不 measure/layout GONE 子 View，
            // 从未显示过的 backgroundImage 宽高会恒为 0，模糊图永远算不出尺寸。
            // 无 Drawable 的 VISIBLE ImageView 不绘制任何像素，视觉上等同于隐藏，
            // 但会被正确测量，后续封面到位时能立刻拿到整屏画布尺寸。
            view.visibility = View.VISIBLE
            updateBackgroundMask()
            return
        }
        albumBlurShowing = false
        albumBlurAppliedVersion = -1
        albumBlurAppliedSongVersion = -1
        updateBackgroundMask()
        // 整屏尺寸（含状态栏 / 导航栏）：横屏判断与背景铺满都以它为口径，
        // 不能只用 resources.displayMetrics（它只是应用窗口可用区域）。
        val (screenW, screenH) = fullScreenSizeIncludingBars()
        // 主题切换 recreate 后若仍等后台解码回主线程，首帧会只剩窗口底色（黑一下）。
        // 内存里已有解码好的大图时先同步贴上，后台任务随后校准或替换。
        val cachedId = prefs.backgroundCurrentId
        val cachedBitmap = cachedId?.let { BackgroundStore.cachedScreenBitmap(it) }
        val cachedItem = cachedId?.let { BackgroundStore.find(prefs, it) }
        if (cachedId != null && cachedBitmap != null && cachedItem != null &&
            BackgroundStore.file(this, cachedId).exists()
        ) {
            // 首帧优先用已算好的模糊缓存；没有缓存时先贴原图，后台任务随后替换成模糊图，
            // 避免在线程里做全屏位图缩放与盒式模糊造成首帧卡顿。
            val shown = if (blurCustom) {
                cachedBlurredCustom(cachedId) ?: cachedBitmap
            } else cachedBitmap
            view.setImageBitmap(shown)
            view.visibility = View.VISIBLE
            view.post {
                if (seq == backgroundLoadSeq && !isDestroyed &&
                    customAllowed() && !albumBlurShowing
                ) {
                    applyCustomBackgroundTransform(view, cachedItem)
                }
            }
        }
        BackgroundStore.post {
            // 「自定义背景图片」按钮（背景图片管理页）所选中的当前项。无封面回退与
            // 常态显示共用这一份配置，不额外保存第二张图、也不另开选图入口。
            val item = BackgroundStore.currentItem(this, prefs)
            if (item == null) {
                // 开关可能在这段后台解码期间被打开；不能把刚贴好的模糊封面隐藏掉。
                view.post {
                    if (seq == backgroundLoadSeq && customAllowed() && !albumBlurShowing) {
                        view.visibility = View.GONE
                    }
                }
                return@post
            }
            val bitmap = BackgroundStore.screenBitmap(this, item.id, screenW, screenH)
            // 模糊计算放在后台线程；关闭开关或中途切到封面模糊背景时结果会被 seq 校验丢弃。
            val shown = if (bitmap != null && blurCustom) {
                blurredCustom(item.id, bitmap) ?: bitmap
            } else bitmap
            view.post {
                if (seq != backgroundLoadSeq || isDestroyed ||
                    !customAllowed() || albumBlurShowing
                ) return@post
                if (shown == null) {
                    view.visibility = View.GONE
                } else {
                    view.setImageBitmap(shown)
                    view.visibility = View.VISIBLE
                    view.post {
                        if (customAllowed() && !albumBlurShowing) {
                            applyCustomBackgroundTransform(view, item)
                        }
                    }
                }
            }
        }
    }

    /**
     * 整屏尺寸（含状态栏 / 导航栏区域）。
     *
     * 主界面是 edge-to-edge，背景层铺满整个窗口；部分 ROM 的
     * `resources.displayMetrics` 只给应用窗口可用尺寸（扣掉了系统栏），
     * 用它判断横屏或计算铺满比例会让背景在状态栏附近留白。这里优先取
     * 窗口 / decor 的实测尺寸，再用 WindowMetrics（含系统装饰区）兜底放大。
     */
    private fun fullScreenSizeIncludingBars(): Pair<Int, Int> {
        var w: Int
        var h: Int
        val canvas = backgroundCanvasSize()
        if (canvas != null) {
            w = canvas.first
            h = canvas.second
        } else {
            val metrics = resources.displayMetrics
            w = metrics.widthPixels
            h = metrics.heightPixels
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            w = maxOf(w, bounds.width())
            h = maxOf(h, bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val real = android.util.DisplayMetrics().also {
                windowManager.defaultDisplay.getRealMetrics(it)
            }
            w = maxOf(w, real.widthPixels)
            h = maxOf(h, real.heightPixels)
        }
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }

    /**
     * 横屏时按原比例把自定义背景图放大到左右边界。
     *
     * fitCenter 基准下，竖图在横屏里会按高度贴合、左右留黑边。这里以
     * fitCenter 实际绘制宽度为基准计算一个基础放大倍数（只放大不缩小），
     * 叠在用户设置的缩放/位移之上；宽度口径取整屏宽度（含状态栏区域）。
     */
    private fun landscapeFillScale(view: ImageView): Float {
        val drawable = view.drawable ?: return 1f
        val bw = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val bh = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val (screenW, screenH) = fullScreenSizeIncludingBars()
        // 只有横屏（宽 > 高）才铺满左右；竖屏保持 fitCenter 完整显示。
        if (screenW <= screenH) return 1f
        val vw = view.width.takeIf { it > 0 }?.toFloat() ?: screenW.toFloat()
        val vh = view.height.takeIf { it > 0 }?.toFloat() ?: screenH.toFloat()
        val fit = minOf(vw / bw, vh / bh)
        val drawnW = (bw * fit).coerceAtLeast(1f)
        val targetW = maxOf(vw, screenW.toFloat())
        return (targetW / drawnW).coerceAtLeast(1f)
    }

    /** 套用自定义背景图的缩放/位移，并在横屏时额外按原比例铺满左右边界。 */
    private fun applyCustomBackgroundTransform(view: ImageView, item: BackgroundStore.Item?) {
        if (item == null) {
            BackgroundStore.applyTransform(view, null)
            return
        }
        val fill = landscapeFillScale(view)
        view.scaleX = item.scaleX * fill
        view.scaleY = item.scaleY * fill
        view.translationX = item.offsetX * view.width
        view.translationY = item.offsetY * view.height
    }

    private fun applyFontSize() {
        // 「应用外观 → 原文字号」为主页歌词字号；设置页的字号滑块写入同一份偏好
        val size = appearance.homeFontSize.toFloat()
        adapter.setFontSize(size)
        // 无歌词时的状态提示跟随主界面字号，避免与歌词文字大小脱节
        binding.lyricStatusHint.textSize = size
        adapter.setAlignment(appearance.homeAlign)
        adapter.setHighlightEnlarge(appearance.highlightEnlarge, appearance.highlightEnlargeSp)
        adapter.applyHomeTextStyle(
            appearance.homeFontWeight,
            appearance.homeLineSpacing,
            appearance.homeTranslationSize,
        )
        adapter.setEmptyLineDisplay(prefs.emptyLineText())
        adapter.setForceWordByWord(prefs.forceWordByWord)
        adapter.applyMainAppearance(
            textColor = prefs.mainTextColor,
            textAlpha = prefs.mainTextAlpha,
            sungColor = prefs.mainSungTextColor,
            outlineEnabled = prefs.mainOutlineEnabled,
            outlineColor = prefs.mainOutlineColor,
            outlineWidthDp = prefs.mainOutlineWidth,
            outlinePosition = prefs.mainOutlinePosition,
        )
        adapter.setSoftTransition(prefs.wordSoftTransition, prefs.wordSoftTransitionStrength)
    }

    // ---------------------------------------------------------------- 应用外观

    /** 每次回到前台统一套用「应用外观」页的全部设置。 */
    private fun applyHomeAppearance() {
        // 从「自定义背景图片」管理页 / 更多设置页返回时，选中的图片、模糊开关或
        // 显示位置可能已变化；下面的 applyAlbumBackground() 会按当前
        // albumBlurEffective 重新贴一次（解码结果有内存缓存，不重复模糊运算）。
        applyFontSize()
        applyAlbumBackground()
        // onResume / onCreate 阶段 RecyclerView 可能尚未完成布局（childCount == 0），
        // 直接套用动效会静默无效 —— 表现为「改了动效回主页没反应，要等切行」。
        // post 到布局完成后再套用一次，保证修改即时可见。
        binding.lyricsRecycler.post { applyHomeMotion() }

        // 保持亮屏
        if (appearance.keepAwake && MusicProgress.isPlaying) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyMediaControlsVisibility()
        // 返回前台时按持久化状态重新套用展开 / 收起、横屏停靠位置与柔化蒙层
        updateMediaControlsState(animate = false)
        // 「高亮行位置 / 允许上下空白」：返回前台时重新套用，post 到布局完成后再算一次尺寸
        applyLyricVerticalPadding()
        binding.lyricsRecycler.post { applyLyricVerticalPadding() }
    }

    /** 单个歌词行在当前动效配置下的目标变换。 */
    private data class MotionTarget(
        val scale: Float,
        val alpha: Float,
        val translationY: Float,
    )

    /**
     * 主页歌词动效：对当前可见行按与 [currentIndex] 的距离应用缩放、远景虚化、
     * 逐行错位与「逐词跃动」模式下的额外放大。
     *
     * 不触发 notifyDataSetChanged —— 逐字蒙版动画靠 [LyricAdapter.updateCurrent]
     * 直接推进度，刷新列表会把正在进行的动画打断；这里只改 View 的变换属性。
     *
     * 变换计算与「套用到某个 View」拆开，是为了让新附着的行（滚动进出、修改设置后
     * 重新布局）也能立刻带上最新配置，而不是只在切行时被刷新一次。
     */
    private fun motionTarget(position: Int): MotionTarget {
        if (!appearance.motionEnabled || appearance.reduceMotion) {
            return MotionTarget(1f, 1f, 0f)
        }
        // 播放进度还没到第一行（findLineIndex 返回 -1），或切歌/重新解析后行号
        // 已失效：此时没有焦点行，所有行统一按无动效渲染。不能用
        // abs(position - (-1)) 当作「到焦点行的距离」——那会凭空造出虚拟焦点，
        // 把列表按 position + 1 逐行缩放、虚化，前奏期间整屏歌词忽大忽暗。
        if (currentIndex !in lyricLines.indices) {
            return MotionTarget(1f, 1f, 0f)
        }
        val density = resources.displayMetrics.density
        val scaleFactor = if (appearance.scale)
            1f - (appearance.scalePercent.coerceIn(0, 100) / 100f) * 0.22f else 1f
        val blurStep = if (appearance.depthBlur)
            (appearance.blurPercent.coerceIn(0, 100) / 100f) * 0.16f else 0f
        val staggerStep = if (appearance.stagger)
            (appearance.staggerDelayMs / 20f) * density else 0f
        val pop = appearance.homeDisplayMode == AppearancePrefs.MODE_POP
        val distance = kotlin.math.abs(position - currentIndex)
        val scale = when {
            distance == 0 -> if (pop) 1.10f else 1f
            else -> (scaleFactor - (distance - 1) * 0.02f).coerceAtLeast(0.65f)
        }
        val alpha = (1f - distance * blurStep).coerceIn(0.35f, 1f)
        val translation = if (distance == 0) 0f
            else (distance * staggerStep).coerceAtMost(24f * density)
        return MotionTarget(scale, alpha, translation)
    }

    /**
     * 缩放轴心：取「文字对齐基准点」在 item 坐标系里的 x。
     *
     * item_lyric.xml 的左右内边距均为 24dp，文字从 paddingStart 起（左对齐）、
     * 到 width-paddingEnd 止（右对齐）、或以中心为轴（居中）。
     * 把 pivotX 放在这个基准点上，缩放时该点不动，所有行的左右边距就恒定。
     */
    private fun motionPivotX(item: View): Float {
        val w = item.width.takeIf { it > 0 }
            ?: binding.lyricsRecycler.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        // 直接读歌词行 TextView 的真实左右 padding，避免与 item_lyric.xml 脱节；
        // paddingStart/End 同时也自动兼容 RTL 布局。
        val text = item.findViewById<View>(R.id.lyricLine)
        val fallback = 24f * resources.displayMetrics.density
        val startPad = text?.paddingStart?.toFloat() ?: fallback
        val endPad = text?.paddingEnd?.toFloat() ?: fallback
        return when (appearance.homeAlign) {
            Prefs.ALIGN_LEFT -> startPad
            Prefs.ALIGN_RIGHT -> w - endPad
            else -> w / 2f
        }
    }

    /** animate=false 用于新附着的 View：直接落到目标值，避免在滚动中启动动画。 */
    private fun applyMotionToChild(child: View, position: Int, animate: Boolean) {
        val t = motionTarget(position)
        // 行间缩放默认开启：当前行 scale=1（逐词跃动为 1.10），其它行 <1。
        // 旧实现用 View 默认的中心轴心，整个 item 连 24dp 内边距一起缩放，
        // 于是左/右对齐时当前行与其它行的起始/结束位置差出十几 dp ——
        // 居中对齐时文字仍居中所以察觉不到，这正是该 bug 只出现在左右对齐的原因。
        child.pivotX = motionPivotX(child)
        // 放大倍数还要受「左右安全边距」约束：轴心落在对齐基准点上，放大只会
        // 往另一边长，长句会顶到屏幕边缘被裁（见 [horizontalSafeScale]）。
        val scale = horizontalSafeScale(child).let {
            if (t.scale > 1f) minOf(t.scale, it.coerceAtLeast(1f)) else t.scale
        }
        child.animate().cancel()
        if (animate) {
            child.animate()
                .scaleX(scale).scaleY(scale)
                .alpha(t.alpha).translationY(t.translationY)
                .setDuration(appearance.scrollDurationMs.toLong().coerceIn(120L, 600L))
                .start()
        } else {
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = t.alpha
            child.translationY = t.translationY
        }
    }

    /**
     * 行间放大（[MotionTarget.scale] > 1）时，「左右两边各自保留 item 内边距」
     * 所允许的最大缩放倍数。
     *
     * 缩放轴心 [motionPivotX] 固定在文字的对齐基准点上，等比放大只让文字朝
     * 另一边变宽：左对齐时右缘外扩、右对齐时左缘外扩、居中时两边各扩一半。
     * 字号调大后长句本来就贴着内边距，放大后「另外一边」的边距会被吃掉一大截，
     * 表现为文字顶到屏幕边缘甚至被裁掉，而对齐侧仍留着完整的 24dp。
     *
     * 这里用 TextView 真实排版的左右极值（含描边外扩）反解倍数：
     * 记 p 为轴心、L / R 为文字左右缘、S 为左侧内边距、E 为右侧内边距，
     * 要求 p + (R - p)·s ≤ W - E 且 p + (L - p)·s ≥ S，取两者中更严的那个。
     * 文字本来就没填满时约束很宽松（返回一个大值），只有真的贴边的长句才会被压回 1 倍。
     */
    private fun horizontalSafeScale(item: View): Float {
        val w = item.width
        if (w <= 0) return Float.MAX_VALUE
        val tv = item.findViewById<OutlineTextView>(R.id.lyricLine) ?: return Float.MAX_VALUE
        // 逐字行由 MaskedLyricView 接管显示时 TextView 是 INVISIBLE，
        // 但它的排版仍然存在（用来占位），可以直接读。
        val l = tv.layout ?: return Float.MAX_VALUE
        if (l.lineCount <= 0) return Float.MAX_VALUE
        val strokePx = if (tv.outlineEnabled) tv.outlineWidthPx else 0f
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (i in 0 until l.lineCount) {
            left = minOf(left, tv.paddingLeft + l.getLineLeft(i))
            right = maxOf(right, tv.paddingLeft + l.getLineRight(i))
        }
        if (left == Float.MAX_VALUE) return Float.MAX_VALUE
        // 描边向外扩，算进文字边界，避免描边本身压到边距外
        left -= strokePx
        right += strokePx
        val startPad = tv.paddingStart.toFloat()
        val endPad = tv.paddingEnd.toFloat()
        val pivot = when (appearance.homeAlign) {
            Prefs.ALIGN_LEFT -> startPad
            Prefs.ALIGN_RIGHT -> w - endPad
            else -> w / 2f
        }
        var bound = Float.MAX_VALUE
        if (right > pivot) {
            bound = minOf(bound, (w - endPad - pivot) / (right - pivot))
        }
        if (left < pivot) {
            bound = minOf(bound, (pivot - startPad) / (pivot - left))
        }
        return bound
    }

    private fun applyHomeMotion() {
        if (!::layoutManager.isInitialized) return
        // 不再因 currentIndex < 0 提前返回：前奏期间 motionTarget 返回中性变换，
        // 这里必须主动把中性值套到所有可见行，才能清掉上一首歌 / 上一次高亮
        // 遗留在复用 View 上的 scale、alpha 与 translationY。
        for (i in 0 until layoutManager.childCount) {
            val child = layoutManager.getChildAt(i) ?: continue
            val position = layoutManager.getPosition(child)
            // 正在被回收/尚未绑定位置的 View 不参与距离计算，避免按 -1 算出错误焦点
            if (position == RecyclerView.NO_POSITION) continue
            applyMotionToChild(child, position, animate = true)
        }
    }

    /**
     * 背景画布的真实尺寸：优先取实测窗口尺寸，不能用 displayMetrics 作为主口径。
     *
     * 主界面是 edge-to-edge（[setupEdgeToEdge] 中 setDecorFitsSystemWindows(false)），
     * 背景 ImageView 铺满整个窗口，包含状态栏与导航栏区域；
     * resources.displayMetrics.heightPixels 不含状态栏（API 30+ 只给应用窗口可用高度），
     * 用它生成的模糊位图会偏矮：状态栏一带露底、封面整体被压扁。
     *
     * 关键修复：activity_main.xml 里 backgroundImage 初始是 GONE，而 FrameLayout
     * 默认不 measure/layout GONE 子 View —— 一个从未显示过的 GONE ImageView，
     * width/height 恒为 0。旧实现只取它自身尺寸，配合「等一帧重算」的 post 循环
     * 就变成了永远拿不到尺寸、永远不生成模糊图的死循环，表现为
     * 「开启封面模糊背景之后完全没效果」。
     * 恒为 VISIBLE 且 match_parent 的 [binding.root] 才是可靠的第一口径。
     */
    private fun backgroundCanvasSize(): Pair<Int, Int>? {
        val rootW = binding.root.width
        val rootH = binding.root.height
        if (rootW > 0 && rootH > 0) return rootW to rootH
        val ivW = binding.backgroundImage.width
        val ivH = binding.backgroundImage.height
        if (ivW > 0 && ivH > 0) return ivW to ivH
        val decorW = window.decorView.width
        val decorH = window.decorView.height
        if (decorW > 0 && decorH > 0) return decorW to decorH
        // 仅有窗口尚未完成布局时才会走到这里；CENTER_CROP 会兜住偏矮的画布。
        val metrics = resources.displayMetrics
        return if (metrics.widthPixels > 0 && metrics.heightPixels > 0)
            metrics.widthPixels to metrics.heightPixels else null
    }

    /**
     * 按 [albumBlurEffective] 在「模糊封面」与「自定义背景图片」之间切换。
     *
     * 关键约定（用户要求的语义）：无专辑封面且开启「无专辑封面时使用自定义
     * 背景图片」时，等价于把「封面模糊背景」临时关掉，整条链路统一走
     * [applyBackground] 的常态路径；不再有独立的回退渲染分支，因此不会出现
     * 自定义图闪一下又被封面模糊状态清掉的问题。封面到位后恢复模糊封面。
     * 若该开关关闭且无封面，则保持空白背景。
     *
     * 历史上这里有三处会让开关「开了却没效果」：
     * 1. 取尺寸只认 backgroundImage 自身的 width/height，而它初始是 GONE，
     *    FrameLayout 不测量 GONE 子 View，尺寸恒为 0 → post 重算死循环（见
     *    [backgroundCanvasSize]）。
     * 2. 自定义背景的解码回调与模糊生成共用 backgroundLoadSeq，任何一次
     *    applyBackground() 都会把在途模糊任务作废；模糊任务自己也会作废
     *    自定义背景解码。现拆成两个独立序号。
     * 3. 「修改图片显示位置」写在 View 上的 scaleX/translationX 不会自动清除，
     *    切到封面模糊背景后仍生效，模糊图会被缩放/平移到屏幕外。
     *
     * 切歌跟随（本轮修复）：根因是「用 Bitmap 对象地址代表封面身份」。
     * MediaSession 切歌或更新播放状态时，会用一个**全新的 Bitmap 对象**重发
     * 像素相同的封面（见 lyrics-android 的 HomeAlbumBackground 及其单元测试
     * repeatedArtworkKeepsThePreparedBitmapEvenWhenMetadataUsesANewInstance）。
     * 旧实现把 identityHashCode 拼进服务侧的封面身份 key，帧循环每 50ms 拉取
     * 一次就让版本号每帧递增；这里又用 frameAlbumArt !== art 判定异步结果过期，
     * 于是所有后台模糊结果都被丢弃 —— 形成「每帧重算、永不贴上」的活锁，
     * 背景一直停在上一首。
     *
     * 现在服务侧把封面归一化成稳定副本（按像素 sameAs 判重），版本号只在真正
     * 换封面时变化；这里改用版本号判定结果是否有效，且即使结果已过期也先贴上、
     * 再让下一帧用当前封面重算，任何情况下都不会停滞。
     */
    private fun applyAlbumBackground(artBitmap: Bitmap? = refreshFrameAlbumArt()) {
        val view = binding.backgroundImage
        // 显式传参时同步全局，保证 albumBlurEffective 与本次实际使用的封面一致。
        frameAlbumArt = artBitmap
        if (!albumBlurEffective) {
            // 「封面模糊背景」关闭，或无专辑封面时按「临时关闭」处理：
            // 统一走常态自定义背景图路径，直接显示「自定义背景图片」里选好的
            // 图片连同模糊、显示位置设置。不再有独立的回退渲染分支。
            albumBlurAppliedVersion = -1
            albumBlurAppliedSongVersion = -1
            albumBlurShowing = false
            lastAlbumBlurSize = null
            albumBlurInFlight = null
            updateBackgroundMask()
            applyBackground()
            return
        }
        // 从「临时关闭」切回封面模糊时，backgroundImage 可能已被 applyBackground()
        // 置为 GONE；先恢复 VISIBLE，否则背景画布取不到尺寸、模糊图贴不上。
        if (view.visibility != View.VISIBLE) {
            view.setImageDrawable(null)
            view.visibility = View.VISIBLE
        }
        val art = artBitmap?.takeIf { !it.isRecycled }
        if (art == null) {
            // 用户关闭「无专辑封面时使用自定义背景图片」且当前无封面：保持空白。
            albumBlurInFlight = null
            albumBlurAppliedVersion = -1
            albumBlurAppliedSongVersion = -1
            albumBlurShowing = false
            lastAlbumBlurSize = null
            resetBackgroundTransform(view)
            view.setImageDrawable(null)
            // 保持 VISIBLE（无 Drawable 不绘制任何像素），否则 View 永远不被测量。
            view.visibility = View.VISIBLE
            updateBackgroundMask()
            return
        }
        val size = backgroundCanvasSize()
        if (size == null) {
            if (!albumBlurLayoutPending) {
                albumBlurLayoutPending = true
                view.post {
                    albumBlurLayoutPending = false
                    if (!isFinishing && !isDestroyed && albumBlurEffective) {
                        applyAlbumBackground()
                    }
                }
            }
            return
        }
        val (w, h) = size
        val targetVersion = MusicNotificationListenerService.albumArtVersion()
        val targetSongVersion = Session.songChangeVersion
        if (targetVersion == albumBlurAppliedVersion &&
            targetSongVersion == albumBlurAppliedSongVersion &&
            albumBlurShowing && lastAlbumBlurSize == size &&
            view.visibility == View.VISIBLE
        ) return
        lastAlbumBlurSize = size
        // 帧循环每帧都会命中 needRedraw，且切歌时封面版本号可能在几帧内连续
        // 递增；只按版本去重仍会瞬间堆积多个各占两张全屏位图的任务（OOM 风险）。
        // 改为粗粒度去重：有任务在跑就等它结束，结果回来后由下一帧的幂等
        // 校验决定是否用新封面重算，最多只延迟一帧。任务身份用版本号，
        // 不再混入位图对象地址。
        val taskKey = "$targetVersion\u0001$targetSongVersion\u0001$w\u0001$h"
        if (albumBlurInFlight != null) return
        albumBlurInFlight = taskKey
        albumBlurInFlightAtMs = android.os.SystemClock.elapsedRealtime()
        Thread {
            val blurred = HomeAppearance.blurredCover(art, w, h)
            runOnUiThread {
                if (albumBlurInFlight == taskKey) {
                    albumBlurInFlight = null
                    albumBlurInFlightAtMs = 0L
                }
                if (isFinishing || isDestroyed || !albumBlurEffective) return@runOnUiThread
                // 当前已无封面（切到无封面的曲目 / 通知监听断连）：在途结果一律
                // 丢弃，保持 syncAlbumArtVersion 已清空的背景，避免旧封面闪回。
                if (frameAlbumArt == null) return@runOnUiThread
                // 结果是否仍对应当前封面。绝不能用「位图对象是否被替换」判定过期：
                // MediaSession 会用新对象重发同一封面，那会让所有结果被丢弃。
                val fresh = MusicNotificationListenerService.albumArtVersion() == targetVersion &&
                    Session.songChangeVersion == targetSongVersion
                if (blurred == null) {
                    // 记下已尝试过的版本，避免每帧立刻重试；由 500ms 节流兜底。
                    albumBlurShowing = false
                    lastAlbumBlurSize = null
                    view.setImageDrawable(null)
                    resetBackgroundTransform(view)
                    view.visibility = View.VISIBLE
                } else {
                    // 位图已按窗口真实尺寸生成；centerCrop 作为双保险，
                    // 系统栏高度随后变化时只会裁切，不会在状态栏处留白。
                    // 即使 fresh == false 也先贴上：旧结果只短暂显示，下一帧帧循环
                    // 会用当前封面重算，比对空白 / 停滞更可控。
                    resetBackgroundTransform(view)
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.setImageBitmap(blurred)
                    view.visibility = View.VISIBLE
                    albumBlurShowing = true
                }
                if (fresh) {
                    albumBlurAppliedVersion = targetVersion
                    albumBlurAppliedSongVersion = targetSongVersion
                } else {
                    // 生成期间又切了歌：强制下一帧用新封面重算，最多延迟一帧。
                    albumBlurAppliedVersion = -1
                    albumBlurAppliedSongVersion = -1
                }
                updateBackgroundMask()
            }
        }.apply { name = "album-blur" }.start()
    }

    /** 取当前封面并同步给控制条与背景共用，保证两者在同一帧看到同一张位图。 */
    private fun refreshFrameAlbumArt(): Bitmap? {
        frameAlbumArt = MusicNotificationListenerService.currentAlbumArt()
        return frameAlbumArt
    }

    /** 清掉「修改图片显示位置」残留的缩放/位移，否则模糊封面会跟着一起变形。 */
    private fun resetBackgroundTransform(view: View) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.rotation = 0f
    }

    /**
     * 遮罩过重（旧值 50% 黑）叠加模糊时 0.72 的压暗系数后只剩约 36% 亮度，
     * 深色封面上几乎看不出「背景变了」。模糊封面显示时改用更轻的遮罩。
     */
    private fun updateBackgroundMask() {
        binding.backgroundMask.setBackgroundColor(
            if (albumBlurShowing) 0x66000000 else 0x80000000.toInt()
        )
    }

    /** 当前已贴在背景上的模糊图对应的封面版本号；-1 表示尚未贴上。 */
    private var albumBlurAppliedVersion = -1

    /** 当前已贴在背景上的模糊图对应的切歌版本号；独立于封面元数据。 */
    private var albumBlurAppliedSongVersion = -1

    /** 当前 backgroundImage 显示的确实是模糊封面（而非自定义背景图）。 */
    private var albumBlurShowing = false

    /** 当前模糊位图的生成尺寸（含状态栏/导航栏）；窗口尺寸变化时必须重算。 */
    private var lastAlbumBlurSize: Pair<Int, Int>? = null

    /** 避免视图未布局期间重复 post 重算请求。 */
    private var albumBlurLayoutPending = false

    /** 当前正在后台生成的模糊任务身份；防止帧循环重复启动同一任务。 */
    private var albumBlurInFlight: String? = null

    /** 在途任务启动时刻；超过 2 秒未回调即强制解锁，避免异常情况下永久停滞。 */
    private var albumBlurInFlightAtMs = 0L

    // ---------------------------------------------------------------- 底部媒体控制条

    /** 已处理的封面身份版本号：切歌 / 封面迟到 / 封面消失都会触发重贴。 */
    private var appliedAlbumArtVersion = -1

    /** 本帧取到的活动会话封面，供控制条与背景共用一次查询结果。 */
    private var frameAlbumArt: android.graphics.Bitmap? = null

    /**
     * 「封面模糊背景」当前是否真正生效。
     *
     * 无专辑封面且「无专辑封面时使用自定义背景图片」开启时，等价于把
     * 「封面模糊背景」临时关掉：整条链路（帧循环、异步解码回调、自定义图加载）
     * 都按关闭状态运行，直接显示「自定义背景图片」里已选好的图片及其模糊、
     * 显示位置设置。封面一旦到位即恢复 true，下一帧重新贴模糊封面。
     *
     * 这样不再需要一条独立的「无封面回退」渲染分支，也不会出现自定义图
     * 闪一下又被封面模糊状态清掉的问题。
     */
    private val albumBlurEffective: Boolean
        get() = appearance.albumBlurBackground &&
            !(appearance.useCustomBgWhenNoArtEffective && frameAlbumArt == null)

    /** 展开区是否展开；取值持久化到 [AppearancePrefs.mediaControlsExpanded]，重启后恢复。 */
    private var mediaControlsExpanded = false

    /**
     * 展开动画的连续进度 0~1，对应 lyrics-android HomePlayer 的 `expansion` Animatable：
     * 同一个值驱动详情区高度、内容透明度与封面进度环淡出，避免 GONE/VISIBLE 突变造成的跳帧。
     */
    private var mediaExpandFraction = 0f
    private var mediaExpandAnimator: android.animation.ValueAnimator? = null
    private var mediaDetailsFullHeight = 0
    private var mediaDetailsMeasuredWidth = -1

    /** 长按拖动改变横屏左右位置时的起始位移与可移动范围。 */
    private var mediaDockDragActive = false
    private var mediaDockDragStartX = 0f
    /** 控制条手势进行中：暂停几何/留白重算，避免拖动时布局刷新造成拖影与闪烁。 */
    private var mediaGestureActive = false
    private var mediaDockAvailablePx = 0

    /** 当前是否横屏；横屏下控制条收窄、可停靠左右，并启用长按拖动。 */
    private var mediaBarLandscape = false
    private var lastRootWidth = 0
    private var lastRootHeight = 0

    /** 避免每帧重复套用同一套外观设置。 */
    private var appliedMediaBarBg: Boolean? = null
    private var appliedMediaPlayBg: Boolean? = null
    private var appliedMediaSoftEdge: Boolean? = null
    private var mediaBarShown = false

    /** 上一次实际套用到「桌面歌词」按钮的高亮状态；null 表示下一帧必须重算。 */
    private var overlayToggleStateApplied: Boolean? = null

    /** 用户正在拖动进度滑块时不再回写进度，避免滑块跳回。 */
    private var mediaSeeking = false

    private var lastMediaTitle: String? = null

    /** 贴在封面 View 上的封面版本号；0 表示占位图，-1 为「必须重贴」哨兵。 */
    private var lastMediaCoverId = 0
    private var lastMediaPlaying: Boolean? = null

    private fun setupMediaControls() {
        val b = binding.mediaControls
        b.mediaPrev.setOnClickListener {
            if (!MusicNotificationListenerService.skipTrack(false)) {
                toast(getString(R.string.play_pause_no_session))
            }
        }
        b.mediaNext.setOnClickListener {
            if (!MusicNotificationListenerService.skipTrack(true)) {
                toast(getString(R.string.play_pause_no_session))
            }
        }
        b.mediaPlay.setOnClickListener {
            if (!MusicNotificationListenerService.togglePlayPause("main.media")) {
                toast(getString(R.string.play_pause_no_session))
            }
        }
        // 参考实现：封面点击 = 播放/暂停；歌曲信息点击 = 展开/收起
        b.mediaCover.setOnClickListener {
            if (!MusicNotificationListenerService.togglePlayPause("main.cover")) {
                toast(getString(R.string.play_pause_no_session))
            }
        }
        b.mediaInfo.setOnClickListener { setMediaControlsExpanded(!mediaControlsExpanded) }
        // 手势统一交给容器识别：上滑完全展开、下滑收起；横滑按停靠侧朝屏幕中间
        // 展开小的收起态、反向收回仅封面；长按封面/空白拖动改停靠位置；
        // 长按标题到时间立即跳在线搜索（不等抬手）。
        // 手势期间宿主暂停几何重算，避免布局刷新与手动位移打架造成拖影/闪烁。
        // 子 View 上不再挂 setOnTouchListener / setOnLongClickListener，避免互相抢事件。
        b.mediaInfo.isLongClickable = false
        val card = b.root as MediaControlsCardView
        // 标题（歌名 / 作者）长按到时间立即跳在线搜索（不等抬手）；其它区域长按
        // 只保留横屏「可拖动」入口，不再触发在线搜索，避免误触。
        card.immediateLongPressView = b.mediaInfo
        // 横屏非「仅封面」时，控制条要像原项目那样浮在歌词层之上；卡片高度在
        // 展开动画里逐帧变化，负 topMargin 必须实时跟着变，歌词层才能稳定铺满底部。
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncLandscapeOverlap()
        }
        card.gestureCallback = object : MediaControlsCardView.GestureCallback {
            override fun onGestureStart() {
                mediaGestureActive = true
            }

            override fun onGestureEnd() {
                mediaGestureActive = false
                mediaDockDragActive = false
            }

            override fun onDockDragStart(startTranslationX: Float) {
                mediaDockDragActive = true
                mediaDockDragStartX = startTranslationX
                card.animate().cancel()
            }

            override fun onDockDragBy(deltaX: Float) {
                if (!mediaDockDragActive) return
                // deltaX 来自屏幕绝对坐标，不会被卡片自身位移反向抵消
                card.translationX = (mediaDockDragStartX + deltaX)
                    .coerceIn(0f, mediaDockAvailablePx.toFloat().coerceAtLeast(0f))
            }

            override fun onDockDragEnd() {
                if (!mediaDockDragActive) return
                mediaDockDragActive = false
                mediaGestureActive = false
                val fraction = if (mediaDockAvailablePx > 0) {
                    (card.translationX / mediaDockAvailablePx).coerceIn(0f, 1f)
                } else appearance.mediaControlsDockFraction
                // 位置落盘：下次进入横屏 / 重启应用直接恢复到松手处
                appearance.mediaControlsDockFraction = fraction
                updateMediaBarGeometry(animate = false)
            }

            override fun onSwipeUp() {
                mediaGestureActive = false
                // 直接上滑完全展开；「仅封面」状态同时恢复成完整控制条
                if (mediaBarLandscape && appearance.mediaControlsLandscapeCoverOnly) {
                    appearance.mediaControlsLandscapeCoverOnly = false
                }
                setMediaControlsExpanded(true)
            }

            override fun onSwipeDown() {
                mediaGestureActive = false
                // 先写状态再刷新，确保横屏这一次就回到参考项目的「仅封面」停靠态
                if (mediaBarLandscape) appearance.mediaControlsLandscapeCoverOnly = true
                setMediaControlsExpanded(false)
            }

            /**
             * 横向快滑，逐行对应原项目 `HomePlayer` 的 horizontalDragState：
             * - 起始「仅封面」：**朝屏幕中间滑**才展开小的收起态
             *   （停靠右侧 → 左滑；停靠左侧 → 右滑），向外滑保持仅封面；
             * - 起始「小的收起态」：横滑收回仅封面，并按滑动方向切换停靠侧
             *   （右滑 → 右侧、左滑 → 左侧），与原项目 onDockChange 一致。
             * 完全展开时容器已禁用横滑。
             */
            override fun onSwipeHorizontal(dx: Float) {
                mediaGestureActive = false
                if (!mediaBarLandscape || mediaControlsExpanded) return
                val coverOnly = appearance.mediaControlsLandscapeCoverOnly
                val dockRight = appearance.mediaControlsDockFraction >= 0.5f
                if (coverOnly) {
                    val inward = dx * if (dockRight) -1f else 1f
                    if (inward > 0f) {
                        appearance.mediaControlsLandscapeCoverOnly = false
                        setMediaControlsExpanded(false)
                    }
                } else {
                    appearance.mediaControlsDockFraction = if (dx > 0f) 1f else 0f
                    appearance.mediaControlsLandscapeCoverOnly = true
                    setMediaControlsExpanded(false)
                }
            }

            override fun onLongPress(fromTitle: Boolean) {
                if (fromTitle) {
                    // 只有按在歌名 / 作者标题区才进在线搜词，其它位置不再误触
                    OnlineSearchActivity.open(
                        this@MainActivity, MusicProgress.title, MusicProgress.artist
                    )
                    toast(getString(R.string.mc_online_search))
                } else if (mediaBarLandscape && appearance.mediaControlsLandscapeCoverOnly &&
                    !mediaControlsExpanded
                ) {
                    appearance.mediaControlsLandscapeCoverOnly = false
                    updateMediaControlsState(animate = true)
                }
            }
        }
        b.mediaOverlayToggle.setOnClickListener { toggleDesktopLyricsFromControls() }
        b.mediaProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val dur = MusicProgress.durationMs
                if (dur > 0L) {
                    b.mediaPosition.text = OnlineLyrics.formatClock((dur * progress / 1000f).toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                mediaSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                mediaSeeking = false
                val dur = MusicProgress.durationMs
                if (dur > 0L) {
                    val target = ((seekBar?.progress ?: 0) / 1000f * dur).toLong()
                    MusicNotificationListenerService.seekTo(target)
                }
            }
        })
        // 恢复上次保存的展开 / 收起、横屏停靠位置与「仅封面」状态：
        // 旋屏、切后台或重启应用都不需要用户重新调整。
        updateMediaControlsState(animate = false)
        // 旋屏、分屏、键盘收起都会改变窗口尺寸，重新套用横屏布局。
        // 歌词渐隐蒙版会随容器 onSizeChanged 自行重建渐变，无需在此手动对齐。
        binding.root.addOnLayoutChangeListener { _, l, t, r, btm, _, _, _, _ ->
            val w = r - l
            val h = btm - t
            if (w == lastRootWidth && h == lastRootHeight) return@addOnLayoutChangeListener
            lastRootWidth = w
            lastRootHeight = h
            if (mediaBarIsLandscape() != mediaBarLandscape) {
                updateMediaControlsState(animate = false)
            } else {
                updateMediaBarGeometry(animate = false)
            }
        }
    }

    /** 展开 / 收起并落盘，下次启动直接恢复。 */
    private fun setMediaControlsExpanded(expanded: Boolean, animate: Boolean = true) {
        appearance.mediaControlsExpanded = expanded
        mediaControlsExpanded = expanded
        if (expanded && mediaBarLandscape && appearance.mediaControlsLandscapeCoverOnly) {
            appearance.mediaControlsLandscapeCoverOnly = false
        }
        updateMediaControlsState(animate)
    }

    /**
     * 统一套用持久化状态：竖屏 = 收起 / 展开；横屏额外有「仅封面」停靠态。
     * 展开区动画、卡片宽度 / 停靠位置、歌词侧边留白都从这里派生，避免多处各改一半。
     */
    private fun updateMediaControlsState(animate: Boolean) {
        mediaBarLandscape = mediaBarIsLandscape()
        mediaControlsExpanded = appearance.mediaControlsExpanded
        val coverOnly = mediaBarLandscape && !mediaControlsExpanded &&
            appearance.mediaControlsLandscapeCoverOnly
        val b = binding.mediaControls
        b.mediaInfo.visibility = if (coverOnly) View.GONE else View.VISIBLE
        b.mediaOverlayToggle.visibility = if (coverOnly) View.GONE else View.VISIBLE
        val card = b.root as MediaControlsCardView
        // 原项目在完全展开时禁用横向拖动：长按拖动与横向快滑一起关掉，
        // 展开态保持停靠在左/右两侧，只由分段控件决定垂直位置。
        card.dockDragEnabled = mediaBarLandscape && !mediaControlsExpanded
        card.horizontalSwipeEnabled = mediaBarLandscape && !mediaControlsExpanded
        animateMediaDetailsTo(if (mediaControlsExpanded) 1f else 0f, animate)
        updateMediaBarGeometry(animate)
    }

    private fun mediaBarIsLandscape(): Boolean {
        val w = binding.root.width
        val h = binding.root.height
        return if (w > 0 && h > 0) w > h
        else resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * 按 lyrics-android 的做法用 0~1 的连续 fraction 驱动展开：
     * 详情区高度逐帧插值（不是 GONE→VISIBLE 突变）、内容淡入、封面进度环同步淡出，
     * 420ms + Material 标准缓动；取消后从当前 fraction 续接，快速反复点按不会跳帧。
     */
    private fun animateMediaDetailsTo(target: Float, animate: Boolean) {
        mediaExpandAnimator?.cancel()
        mediaExpandAnimator = null
        val details = binding.mediaControls.mediaDetails
        val density = resources.displayMetrics.density
        val applyFraction: (Float) -> Unit = { f ->
            mediaExpandFraction = f
            val lp = details.layoutParams
            when {
                f <= 0.001f -> {
                    details.visibility = View.GONE
                    if (lp.height != LinearLayout.LayoutParams.WRAP_CONTENT) {
                        lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                        details.layoutParams = lp
                    }
                    details.alpha = 1f
                    details.translationY = 0f
                }
                f >= 0.999f -> {
                    details.visibility = View.VISIBLE
                    if (lp.height != LinearLayout.LayoutParams.WRAP_CONTENT) {
                        lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                        details.layoutParams = lp
                    }
                    details.alpha = 1f
                    details.translationY = 0f
                }
                else -> {
                    details.visibility = View.VISIBLE
                    val h = (measureMediaDetailsHeight() * f).toInt().coerceAtLeast(1)
                    if (lp.height != h) {
                        lp.height = h
                        details.layoutParams = lp
                    }
                    details.alpha = f
                    details.translationY = (1f - f) * 8f * density
                }
            }
            // 展开后左侧封面不再有进度环，封面本身平滑放大补上环形留白
            binding.mediaControls.mediaCover.ringAlpha = 1f - f
            // 展开过程中同步上移，完全展开后落在「上方 / 居中 / 下方」所选位置
            applyExpandedVerticalPosition()
        }
        if (!animate || appearance.reduceMotion) {
            applyFraction(target)
            return
        }
        val animator = android.animation.ValueAnimator.ofFloat(mediaExpandFraction, target).apply {
            duration = 420L
            interpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
            addUpdateListener { applyFraction(it.animatedValue as Float) }
        }
        mediaExpandAnimator = animator
        animator.start()
    }

    /** 量一次详情区的自然高度；只在测量宽度变化时重算，避免每帧重复 measure。 */
    private fun measureMediaDetailsHeight(): Int {
        val details = binding.mediaControls.mediaDetails
        val card = binding.mediaControls.root
        val density = resources.displayMetrics.density
        val w = if (details.width > 0) details.width
        else (card.width - (24 * density).toInt()).coerceAtLeast(120)
        if (w != mediaDetailsMeasuredWidth || mediaDetailsFullHeight <= 0) {
            details.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            if (details.measuredHeight > 0) {
                mediaDetailsFullHeight = details.measuredHeight
                mediaDetailsMeasuredWidth = w
            }
        }
        return mediaDetailsFullHeight.coerceAtLeast((80 * density).toInt())
    }

    /**
     * 横屏：控制条收窄到 min(340dp, 42% 屏宽)，按记忆的 dockFraction 停靠在底边任意位置；
     * 「仅封面」态只留 80dp。竖屏：恢复满宽、位移归零。歌词列表按停靠侧留出侧边空白。
     *
     * 完全展开例外：水平方向按 dockRight 吸附左/右两端（不居中），只有垂直方向
     * 由 [applyExpandedVerticalPosition] 放到「上方 / 居中 / 下方」。
     */
    private fun updateMediaBarGeometry(animate: Boolean) {
        val card = binding.mediaControls.root as MediaControlsCardView
        val lp = card.layoutParams as? LinearLayout.LayoutParams ?: return
        val density = resources.displayMetrics.density
        val coverOnly = mediaBarLandscape && !mediaControlsExpanded &&
            appearance.mediaControlsLandscapeCoverOnly
        // contentRoot 左右内边距是刘海/挖孔安全区；可用宽度必须把两侧都扣掉，
        // 否则横屏时控制条与歌词会滑进摄像头区域。
        val contentRoot = binding.contentRoot
        val usableW = (contentRoot.width - contentRoot.paddingLeft -
            contentRoot.paddingRight).takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val targetWidth = when {
            !mediaBarLandscape -> LinearLayout.LayoutParams.MATCH_PARENT
            coverOnly -> (80 * density).toInt()
            else -> minOf((340 * density).toInt(), (usableW * 0.42f).toInt())
                .coerceAtLeast((180 * density).toInt())
        }
        if (lp.width != targetWidth) {
            lp.width = targetWidth
            card.layoutParams = lp
            mediaDetailsMeasuredWidth = -1
        }
        card.post {
            // 手势进行中：宿主暂停一切位移/留白重算。旧实现会在这里把
            // translationX 覆盖回记忆位置、并触发 RecyclerView 重新布局，
            // 正是拖动时拖影、闪烁、拖不动的直接原因。
            if (mediaGestureActive) return@post
            val available = (usableW - card.width -
                lp.marginStart - lp.marginEnd).coerceAtLeast(0)
            mediaDockAvailablePx = available
            val stored = if (mediaBarLandscape) appearance.mediaControlsDockFraction else 0f
            // 完全展开时只做「垂直居中」：水平方向照原项目 dockRight 的布尔语义
            // 吸附到左/右两端，不再跑到屏幕中央。分段控件只影响垂直位置。
            // 收起 / 仅封面态仍恢复用户长按拖动记忆的任意水平位置。
            val fraction = if (mediaBarLandscape && mediaControlsExpanded) {
                if (stored >= 0.5f) 1f else 0f
            } else stored
            val targetX = available * fraction
            card.animate().cancel()
            if (animate && !appearance.reduceMotion) {
                card.animate().translationX(targetX).setDuration(360L)
                    .setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f))
                    .start()
            } else {
                card.translationX = targetX
            }
            applyMediaLyricsSidePadding(coverOnly, fraction, card.width)
            syncLandscapeOverlap()
        }
    }

    /**
     * 原项目横屏时 `HomePlayer` 是 Box 里 `align(BottomStart)` 的浮层，歌词层
     * `fillMaxSize()` 只留 12dp 底部空白，横屏非「仅封面」时可一直铺到屏幕底部。
     *
     * 本项目控制条是 LinearLayout 的最后一个 child，会占掉底部一整行。这里用
     * 负 `topMargin` 抵消控制条自身高度：`LinearLayout` 认为它不占垂直空间，
     * 歌词层因此延伸到屏幕底部，而控制条仍贴底绘制（后绘制覆盖其上），
     * 歌词再用 [applyMediaLyricsSidePadding] 水平避开控制条所在一侧。
     *
     * 「仅封面」与竖屏时不抵消，沿用原来的挤压布局（对应原项目
     * `bottomSpace = playerSize.height + 20.dp`）。
     */
    private fun syncLandscapeOverlap() {
        val card = binding.mediaControls.root
        val lp = card.layoutParams as? LinearLayout.LayoutParams ?: return
        val coverOnly = mediaBarLandscape && !mediaControlsExpanded &&
            appearance.mediaControlsLandscapeCoverOnly
        val want = if (mediaBarLandscape && !coverOnly && card.height > 0) -card.height else 0
        if (lp.topMargin != want) {
            lp.topMargin = want
            card.layoutParams = lp
        }
        // 卡片高度在展开动画里逐帧变化，可移动范围也随之变化，这里同步刷新位置
        applyExpandedVerticalPosition()
    }

    /**
     * 横屏完全展开后**只**把控制条垂直放到「上方 / 居中 / 下方」，默认居中；
     * 水平方向仍停靠左/右两侧（见 [updateMediaBarGeometry]）。
     *
     * 对应原项目 HomePlayer 的横屏 `tall` 布局：展开时 Surface 高度增长到接近
     * 全屏，内容被放在 `(height - contentHeight) / 2`，视觉上从贴底变成居中，
     * 而 Surface 本身依旧是 `align(BottomStart)` + dockFraction 的左/右停靠。
     * 本项目控制条是贴底浮层，用 `translationY = -travel * factor` 等价实现；
     * travel 由歌词层高度与卡片实时高度算出，再乘 [mediaExpandFraction]，
     * 使展开动画期间平滑上移、收起时平滑回到贴底。
     */
    private fun applyExpandedVerticalPosition() {
        val card = binding.mediaControls.root
        val stage = binding.lyricDisplayRoot
        val f = mediaExpandFraction.coerceIn(0f, 1f)
        val targetY = if (!mediaBarLandscape || f <= 0f) 0f else {
            val travel = (stage.height - card.height).coerceAtLeast(0)
            val factor = when (appearance.mediaControlsExpandedPosition) {
                AppearancePrefs.EXPANDED_POS_TOP -> 1f
                AppearancePrefs.EXPANDED_POS_BOTTOM -> 0f
                else -> 0.5f
            }
            val topInset = 8f * resources.displayMetrics.density
            -((travel - topInset).coerceAtLeast(0f)) * factor * f
        }
        if (card.translationY != targetY) card.translationY = targetY
    }

    private fun applyMediaLyricsSidePadding(
        coverOnly: Boolean,
        dockFraction: Float,
        barWidth: Int,
    ) {
        val rv = binding.lyricsRecycler
        val density = resources.displayMetrics.density
        // 原项目在横屏非「仅封面」时始终按 playerLimit + 28dp 给歌词留侧边空白，
        // 完全展开态也一样（控制条仍停靠在左/右一侧，只是垂直方向居中）。
        val pad = if (mediaBarLandscape && !coverOnly)
            barWidth + (16 * density).toInt() else 0
        val left = if (pad > 0 && dockFraction < 0.5f) pad else 0
        val right = if (pad > 0 && dockFraction >= 0.5f) pad else 0
        if (rv.paddingLeft != left || rv.paddingRight != right) {
            rv.setPadding(left, rv.paddingTop, right, rv.paddingBottom)
        }
        // 状态提示（没有检测到播放 / 正在匹配 / 无匹配等）同样按控制条停靠侧留白，
        // 否则横屏浮层控制条会压在提示文字上；竖屏 pad=0 自动恢复整屏居中。
        val hint = binding.lyricStatusHint
        if (hint.paddingLeft != left || hint.paddingRight != right) {
            hint.setPadding(left, hint.paddingTop, right, hint.paddingBottom)
        }
    }

    /** 底部控制条上的「桌面歌词」开关，语义与设置页开关一致。 */
    private fun toggleDesktopLyricsFromControls() {
        if (prefs.desktopLyrics) {
            prefs.desktopLyrics = false
            DesktopLyricsService.stop(this)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                UiTips.show(this, R.string.overlay_permission_hint, android.widget.Toast.LENGTH_LONG)
                return
            }
            DesktopLyricsService.start(this)
        }
        DesktopLyricsTileService.requestUpdate(this)
        // 服务的 onCreate / onDestroy 是异步的：先立即刷一次，再延迟补刷一次；
        // 之后每帧的幂等刷新持续兜底，保证「关闭 → 重新开启」后高亮实时跟随。
        overlayToggleStateApplied = null
        updateMediaControlsOverlayToggle()
        uiHandler.postDelayed({ updateMediaControlsOverlayToggle() }, 300)
    }

    /**
     * 刷新底部控制条「桌面歌词」按钮的高亮。
     *
     * 原先只在点击和显示控制条时刷新，而服务启停是异步的：从通知栏 / 磁贴 /
     * 设置页关闭再开启桌面歌词后，按钮会一直停在旧的高亮状态。现在由每帧循环
     * 调用，并用 [overlayToggleStateApplied] 做幂等，状态一变当帧即生效。
     */
    private fun updateMediaControlsOverlayToggle(force: Boolean = false) {
        val on = prefs.desktopLyrics && DesktopLyricsService.isRunning
        if (!force && overlayToggleStateApplied == on) return
        overlayToggleStateApplied = on
        val toggle = binding.mediaControls.mediaOverlayToggle
        // 高亮沿用布局里原来的纯白色（app:tint="#FFFFFFFF"），不使用主题色：
        // 开启＝全不透明白色，关闭＝半透明白色，仅靠透明度区分状态。
        toggle.imageAlpha = if (on) 255 else 140
        toggle.contentDescription =
            getString(R.string.mc_desktop_lyrics) + if (on) "（已开启）" else "（已关闭）"
    }

    /**
     * 每帧都可能被调用（控制条隐藏时由 [updateMediaControls] 兜底刷新），
     * 因此三个外观开关都做幂等，只有取值变化才真正改 View 属性。
     */
    private fun applyMediaControlsVisibility() {
        mediaBarLandscape = mediaBarIsLandscape()
        val show = appearance.showMediaControls && isPlayingMode() && !immersive
        val b = binding.mediaControls
        b.root.visibility = if (show) View.VISIBLE else View.GONE
        // 控制条从隐藏恢复显示时（退出沉浸 / 从编辑模式返回 / 重新打开开关），
        // 卡片此前是 GONE、宽度为 0，必须重新套用一次宽度与停靠位置。
        if (show != mediaBarShown) {
            mediaBarShown = show
            if (show) updateMediaControlsState(animate = false)
        }

        // 「底部播放控制条背景」为正向开关：默认关闭＝卡片透明，与参考项目一致。
        val bgOn = appearance.mediaControlsBackground
        if (appliedMediaBarBg != bgOn) {
            appliedMediaBarBg = bgOn
            (b.root as MediaControlsCardView).setCardBackgroundColor(
                if (bgOn) 0x4D18181C else Color.TRANSPARENT
            )
        }
        // 「暂停按钮背景」：默认关闭＝只保留点击涟漪，不画胶囊底色。
        val playBgOn = appearance.mediaControlsPlayBackground
        if (appliedMediaPlayBg != playBgOn) {
            appliedMediaPlayBg = playBgOn
            b.mediaPlay.setBackgroundResource(
                if (playBgOn) R.drawable.bg_media_play_pill
                else R.drawable.bg_media_play_plain
            )
        }
        // 「上边缘柔化」：逐行照搬原项目 homeLyricsFade()——歌词层独立离屏合成，
        // 全高渐变 DstIn 只裁歌词文字 alpha；专辑背景与遮罩是另外两层，一个像素都不改。
        val fadeOn = appearance.mediaControlsSoftTopEdge
        if (appliedMediaSoftEdge != fadeOn) {
            appliedMediaSoftEdge = fadeOn
            binding.lyricDisplayRoot.fadeEnabled = fadeOn
        }
        // 从设置页返回时立即套用「横屏完全展开位置」；内部有幂等判断，每帧调用无副作用
        applyExpandedVerticalPosition()

        if (show) {
            updateMediaControlsOverlayToggle()
        } else overlayToggleStateApplied = null
    }

    /**
     * 每帧统一取一次封面：切歌、封面迟到、封面消失都会让服务侧的封面版本号递增。
     * 这里随即强制重贴控制条封面与模糊背景，杜绝沿用上一首的位图。
     *
     * 模糊背景不走「version 变化才重绘」这条窄路，而是每帧做幂等校验：
     * 只要「已贴在背景上的版本」不是当前封面版本 / 切歌版本，就重绘。
     * 生成任务被丢弃、封面迟到、服务重连、播放器复用同一个 Bitmap 对象等
     * 情况下都能自愈，不会停在上一首的模糊图。
     */
    private fun syncAlbumArtVersion() {
        // 服务侧已把封面按像素归一化成稳定副本：播放器用新 Bitmap 对象重发
        // 同一张封面时版本号不再抖动，下面的幂等校验才能真正收敛。
        frameAlbumArt = MusicNotificationListenerService.currentAlbumArt()
        val version = MusicNotificationListenerService.albumArtVersion()
        if (version != appliedAlbumArtVersion) {
            appliedAlbumArtVersion = version
            lastMediaTitle = null
            // 用 -1 作为「必须重贴」哨兵：不能置 0，否则新封面为 null 时
            // coverId(0) 会与缓存值相同而跳过回退占位图，旧封面就残留了。
            lastMediaCoverId = -1
        }
        val effective = albumBlurEffective
        if (effective != lastAlbumBlurEffective) {
            // 「有封面 ↔ 无封面」切换等价于把「封面模糊背景」临时开关一次：
            // 必须在本帧立即按新状态重贴。若直接 return，画面会一直停在
            // 上一首的模糊封面，或让刚贴上的自定义图被后续帧清掉。
            lastAlbumBlurEffective = effective
            applyAlbumBackground(frameAlbumArt)
            return
        }
        // 临时关闭期间帧循环不参与背景渲染：自定义背景图由 applyBackground()
        // 常态持有，任何重绘 / 版本号变化都不会把它覆盖掉。
        if (!effective) return

        val now = android.os.SystemClock.elapsedRealtime()
        // 在途任务异常卡住（线程被杀 / 极端 OOM）时强制解锁，否则
        // albumBlurInFlight 永远非空，背景会永久停在上一首。
        if (albumBlurInFlight != null && now - albumBlurInFlightAtMs > 2000L) {
            albumBlurInFlight = null
            albumBlurInFlightAtMs = 0L
        }
        val art = frameAlbumArt?.takeIf { !it.isRecycled }
        val songVersion = Session.songChangeVersion
        val needRedraw = art != null && (
            albumBlurAppliedVersion != version ||
                albumBlurAppliedSongVersion != songVersion
        )
        // 封面消失时必须立刻清掉旧模糊图，不能因为版本号没变而继续挂着。
        val needClear = art == null && albumBlurShowing
        // 无封面 / 生成失败时的低频兜底，避免只依赖版本信号。
        val retryDue = !albumBlurShowing && now - lastAlbumBlurRetryMs >= 500L
        if (needRedraw || needClear || retryDue) {
            if (retryDue) lastAlbumBlurRetryMs = now
            // 复用本帧已取到的封面，避免二次查询在 URI 解码期间返回 null。
            applyAlbumBackground(frameAlbumArt)
        }
    }

    /** 模糊背景未显示时的低频重试时间戳。 */
    private var lastAlbumBlurRetryMs = 0L

    /** 每帧更新进度环 / 滑块与歌曲信息（文本只在变化时赋值，避免无谓重绘）。 */
    private fun updateMediaControls() {
        val root = binding.mediaControls.root
        if (root.visibility != View.VISIBLE) {
            applyMediaControlsVisibility()
            return
        }
        // 桌面歌词可能由通知栏 / 磁贴 / 设置页异步启停，每帧校验一次高亮状态
        updateMediaControlsOverlayToggle()
        val b = binding.mediaControls
        val title = MusicProgress.title?.trim().orEmpty()
        val artist = MusicProgress.artist?.trim().orEmpty()
        val key = "$title|$artist"
        if (key != lastMediaTitle) {
            lastMediaTitle = key
            b.mediaTitle.text = title.ifBlank { getString(R.string.app_name) }
            b.mediaArtist.text = artist
        }

        // 封面与歌名文本解耦：null 必须回退占位图，不能沿用上一张位图。
        // 判重用服务侧封面版本号而非位图对象地址，避免播放器用新对象重发同一
        // 封面时每帧重建 BitmapShader；版本变化时 syncAlbumArtVersion 已置 -1
        // 强制重贴，不会漏掉真正的换封面。
        val art = frameAlbumArt?.takeIf { !it.isRecycled }
        val coverId = art?.let { appliedAlbumArtVersion } ?: 0
        if (coverId != lastMediaCoverId) {
            lastMediaCoverId = coverId
            b.mediaCover.setCover(art)
        }

        val playing = MusicProgress.isPlaying
        if (playing != lastMediaPlaying) {
            lastMediaPlaying = playing
            b.mediaPlay.setImageResource(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
        }

        val pos = MusicProgress.currentPositionMs()
        val dur = MusicProgress.durationMs
        val ratio = if (dur > 0L)
            (pos.coerceAtLeast(0L).toFloat() / dur).coerceIn(0f, 1f) else 0f
        b.mediaCover.progress = ratio
        if (!mediaSeeking) {
            b.mediaProgress.progress = (ratio * 1000).toInt()
            b.mediaPosition.text = OnlineLyrics.formatClock(pos)
        }
        b.mediaDuration.text = OnlineLyrics.formatClock(dur)
        if (appearance.keepAwake && playing) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- 在线歌词选用

    /** 在线搜索页面选用歌词后写下的待打开路径；加载后清空。 */
    private fun consumePendingOnlineLyric() {
        val path = Session.pendingOnlineLyricPath ?: return
        Session.pendingOnlineLyricPath = null
        val file = java.io.File(path)
        if (!file.exists()) return
        loadInternal(file, recordRecent = true)
    }

    // ---------------------------------------------------------------- 沉浸

    /**
     * 状态栏沉浸式：让背景图层 / 遮罩铺满整屏（含状态栏区域），内容容器按 insets
     * 让出顶部与底部，避免状态栏变成一条与内容割裂的深色横条。
     *
     * 注意：setDecorFitsSystemWindows(false) 后系统不再自动压缩窗口，
     * 因此键盘（IME）也需要由 contentRoot 的底部内边距来避让。
     */
    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 沉浸模式隐藏状态栏后，刘海/挖孔屏默认不允许窗口延伸进挖孔安全区，
        // 那一整块区域会被系统强制留黑（顶部「大黑边」）；
        // shortEdges 让窗口始终铺满短边挖孔区，黑边随之消失。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        updateStatusBarIcons()
        // 原项目用 WindowInsets.safeDrawing（systemBars + displayCutout + ime）
        // 包住整个主页内容；这里等价处理。只取 systemBars 会在横屏漏掉
        // 短边挖孔/摄像头区域，歌词与控制条就会被摄像头挡住。
        val safeTypes = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(binding.contentRoot) { v, insets ->
            val safe = insets.getInsets(safeTypes)
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            // 四个方向都要套用：横屏时安全区主要落在左右，正是摄像头所在侧。
            v.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, imeBottom))
            // 安全区变化会改变 contentRoot 可用宽度，重算控制条几何与停靠位置
            updateMediaBarGeometry(animate = false)
            // 系统栏显隐、分屏、键盘收起都可能改变背景画布的真实高度；
            // 只有尺寸确实变了才按新尺寸重算模糊封面，避免状态栏区域留白/拉伸。
            if (albumBlurEffective && albumBlurShowing) {
                val size = backgroundCanvasSize()
                if (size != null && size != lastAlbumBlurSize) applyAlbumBackground()
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.contentRoot)
    }

    /** 主界面文字恒为白色：深色主题用浅色状态栏图标，浅色主题用深色图标。 */
    private fun updateStatusBarIcons() {
        val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !night
    }

    private fun toggleImmersive() = applyImmersive(!immersive)

    private fun applyImmersive(on: Boolean) {
        immersive = on
        binding.toolbar.visibility = if (on) View.GONE else View.VISIBLE
        applyMediaControlsVisibility()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on && !prefs.immersiveShowBars) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        // 系统栏重新显示后图标明暗可能被系统重置，这里再确认一次
        updateStatusBarIcons()
        invalidateOptionsMenu()
    }

    // ---------------------------------------------------------------- 通知授权

    private fun maybePromptNotificationAccess() {
        // 已经授权则无需提示
        if (MusicNotificationListenerService.isEnabled(this)) {
            notifPromptShownThisLaunch = true
            return
        }
        // 应用更新后系统可能回收通知使用权；这里改为每次启动都检测，
        // 只要未授权就重新引导（每次启动最多提示一次），避免被旧的持久化标记永久抑制。
        if (notifPromptShownThisLaunch) return
        notifPromptShownThisLaunch = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notif_access_title)
            .setMessage(R.string.notif_access_message)
            .setPositiveButton(R.string.notif_access_go) { _, _ -> openNotificationAccessSettings() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    // ---------------------------------------------------------------- 位置记忆

    private fun persistPosition() {
        if (!prefs.rememberPosition) return
        val uri = currentUri ?: return
        if (isPlayingMode()) {
            prefs.savePosition(uri, currentIndex.coerceAtLeast(0))
        }
    }

    // ---------------------------------------------------------------- 会话

    private fun saveSession() {
        Session.uri = currentUri
        Session.name = currentName
        Session.internalPath = internalFile?.absolutePath
        Session.text = if (isEditMode()) binding.editorText.text?.toString().orEmpty() else docText
        Session.charset = charset
        Session.bom = bom
        Session.dirty = dirty
        Session.pendingNewLyricName = pendingNewLyricName
        // 未落盘的新歌词 docText 可能仍为空，也要让旋转/重建后能恢复编辑内容
        Session.hasDocument = docText.isNotEmpty() || pendingNewLyricName != null ||
            currentUri != null || internalFile != null || dirty
        Session.lyricLines = lyricLines
    }

    private fun restoreSession() {
        currentUri = Session.uri
        currentName = Session.name
        pendingNewLyricName = Session.pendingNewLyricName
        internalFile = Session.internalPath?.let { path ->
            File(path).takeIf { it.exists() }
        }
        charset = Session.charset
        bom = Session.bom
        dirty = Session.dirty
        docText = Session.text.orEmpty()
        lyricLines = Session.lyricLines ?: LyricsParser.parse(docText)
        Session.lyricLines = lyricLines
        adapter.submit(lyricLines)
        updateTitle()
    }

    // ---------------------------------------------------------------- 菜单

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_exit_edit)?.isVisible = isEditMode()
        // 「偏移设置」属于歌词播放界面的更多选项，编辑模式下隐藏
        menu.findItem(R.id.action_offset_settings)?.isVisible = !isEditMode()
        menu.findItem(R.id.action_immersive)?.setTitle(
            if (immersive) R.string.action_exit_immersive else R.string.action_immersive
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_open -> {
            hideKeyboard()
            openLauncher.launch(arrayOf("text/*", "*/*"))
            true
        }
        R.id.action_exit_edit -> {
            if (dirty) confirmExit() else exitEditMode()
            true
        }
        R.id.action_new_lyric -> {
            hideKeyboard()
            if (isEditMode() && dirty) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.unsaved_title)
                    .setMessage(R.string.unsaved_message)
                    .setPositiveButton(R.string.dialog_save) { _, _ ->
                        pendingCreateNewAfterSave = true
                        saveCurrent()
                    }
                    .setNegativeButton(R.string.dialog_discard) { _, _ ->
                        pendingCreateNewAfterSave = false
                        createNewLyric()
                    }
                    .setNeutralButton(R.string.dialog_cancel) { _, _ ->
                        pendingCreateNewAfterSave = false
                    }
                    .show()
            } else {
                createNewLyric()
            }
            true
        }
        R.id.action_immersive -> {
            toggleImmersive()
            true
        }
        R.id.action_offset_settings -> {
            showOffsetSettingsDialog()
            true
        }
        R.id.action_online_search -> {
            hideKeyboard()
            OnlineSearchActivity.open(
                this,
                MusicProgress.title?.trim()?.takeIf { it.isNotEmpty() },
                MusicProgress.artist?.trim()?.takeIf { it.isNotEmpty() },
            )
            true
        }
        R.id.action_listen_status -> {
            hideKeyboard()
            startActivity(Intent(this, ListenStatusActivity::class.java))
            true
        }
        R.id.action_home_lyrics_settings -> {
            hideKeyboard()
            startActivity(Intent(this, HomeLyricsSettingsActivity::class.java))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_about -> {
            hideKeyboard()
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * 「偏移设置」弹窗：
     * · 一个按当前播放应用包名记忆的开关：「毫秒信息不可信」，下方列出
     *   曾开启过它的应用，右侧按钮可关闭 / 重新开启；关闭弹窗时仍处于
     *   关闭状态的应用会被清出列表，下次打开不再出现；
     * · 切歌起点读取后的整首歌纯应用内计时对所有应用生效，无开关；
     * · 整体偏移用 -3000ms ~ +3000ms 的滑块控制，支持重置到中间 0ms。
     */
    private fun showOffsetSettingsDialog() {
        val b = DialogOffsetSettingsBinding.inflate(layoutInflater)
        val currentPkg = PlaybackOffset.activePackage()

        b.tvCurrentApp.text = if (currentPkg == null) {
            getString(R.string.app_ms_offset_no_app)
        } else {
            getString(R.string.app_ms_offset_current_app, PlaybackOffset.appLabel(this, currentPkg))
        }
        b.switchAppMsOffset.isEnabled = currentPkg != null
        b.switchAppMsOffset.isChecked =
            currentPkg != null && prefs.isAppMsOffsetEnabled(currentPkg)
        // Telegram 等默认开启的应用也要出现在下方列表里
        if (currentPkg != null && prefs.isAppMsOffsetEnabled(currentPkg)) {
            prefs.trackMsOffsetApp(currentPkg)
        }

        // 避免列表行同步开关状态时回调主开关监听器造成递归
        var suppressMsCallback = false

        /** 渲染一组「已开启应用」列表；行内按钮可直接关闭 / 重新开启该应用的开关。 */
        fun renderAppList(
            container: LinearLayout,
            emptyView: TextView,
            apps: Set<String>,
            isEnabled: (String) -> Boolean,
            toggle: (String) -> Unit,
            syncCurrentSwitch: (Boolean) -> Unit
        ) {
            container.removeAllViews()
            val sorted = apps.sortedBy { PlaybackOffset.appLabel(this, it).lowercase() }
            emptyView.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
            for (appPkg in sorted) {
                val row = ItemOffsetAppBinding.inflate(layoutInflater)
                row.tvAppName.text = PlaybackOffset.appLabel(this, appPkg)
                row.tvAppPackage.text = appPkg

                val syncRow: () -> Unit = {
                    val on = isEnabled(appPkg)
                    row.btnToggleApp.setText(
                        if (on) R.string.app_ms_offset_disable else R.string.app_ms_offset_enable
                    )
                    row.tvAppName.alpha = if (on) 1f else 0.5f
                    if (appPkg == currentPkg) syncCurrentSwitch(on)
                }
                syncRow()
                row.btnToggleApp.setOnClickListener {
                    toggle(appPkg)
                    syncRow()
                }
                container.addView(row.root)
            }
        }

        fun refreshMsList() = renderAppList(
            b.appListContainer,
            b.tvNoApps,
            prefs.getTrackedMsOffsetApps(),
            { pkg -> prefs.isAppMsOffsetEnabled(pkg) },
            { pkg -> prefs.setAppMsOffsetEnabled(pkg, !prefs.isAppMsOffsetEnabled(pkg)) },
            { on ->
                suppressMsCallback = true
                b.switchAppMsOffset.isChecked = on
                suppressMsCallback = false
            }
        )

        refreshMsList()

        b.switchAppMsOffset.setOnCheckedChangeListener { _, checked ->
            if (suppressMsCallback) return@setOnCheckedChangeListener
            val pkg = PlaybackOffset.activePackage() ?: currentPkg ?: run {
                UiTips.show(this, R.string.app_ms_offset_no_app)
                return@setOnCheckedChangeListener
            }
            prefs.setAppMsOffsetEnabled(pkg, checked)
            refreshMsList()
        }
        // 整体偏移：滑块 0..6000 映射到 -3000..+3000ms，中间位置为 0
        b.globalOffsetBar.max = PlaybackOffset.GLOBAL_OFFSET_RANGE
        b.globalOffsetBar.progress = prefs.globalOffsetMs - PlaybackOffset.MIN_GLOBAL_OFFSET_MS
        fun updateOffsetLabel() {
            val value = prefs.globalOffsetMs
            b.tvGlobalOffsetValue.text = if (value > 0) "+$value ms" else "$value ms"
        }
        updateOffsetLabel()
        b.globalOffsetBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.globalOffsetMs = progress + PlaybackOffset.MIN_GLOBAL_OFFSET_MS
                updateOffsetLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        b.btnResetGlobalOffset.setOnClickListener {
            prefs.globalOffsetMs = 0
            b.globalOffsetBar.progress = -PlaybackOffset.MIN_GLOBAL_OFFSET_MS
            updateOffsetLabel()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.offset_settings_title)
            .setView(b.root)
            .setPositiveButton(R.string.close, null)
            .create()
        // 关闭弹窗时清掉仍然关闭的应用：没被重新开启的下次不再出现
        dialog.setOnDismissListener {
            prefs.pruneTrackedMsOffsetApps()
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- 工具

    private fun hideKeyboard() {
        val view = currentFocus ?: binding.root
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    /** 所有轻量提示统一走 [UiTips]：系统 Toast 外观，固定显示在屏幕顶部。 */
    private fun toast(msg: String) = UiTips.show(this, msg)

    private fun toast(resId: Int) = UiTips.show(this, resId)

    /**
     * 保存结果提示：成功与页面里的其他提示统一走顶部 Toast；
     * 失败改用弹窗，避免一闪而过被忽略。
     */
    private fun showSaveResult(success: Boolean, message: String) {
        // "保存并继续新建"：等异步写入返回结果后再弹出新建弹窗，
        // 避免保存尚未完成就把文档状态切成另一份空白稿
        if (pendingCreateNewAfterSave) {
            pendingCreateNewAfterSave = false
            if (success) createNewLyric()
        }
        if (success) UiTips.show(this, message)
        else UiTips.showFailure(this, message)
    }

    /** 一次编辑操作，用于撤销/重做。 */
    private data class EditOp(val start: Int, val before: String, val after: String)

    companion object {
        /** 连续编辑空闲多久后提交为一个撤销步骤（毫秒）。 */
        private const val COMMIT_DELAY = 600L

        /** 高亮防回跳的回退幅度阈值（毫秒）：小于该幅度的回退视为估算抖动忽略。 */
        private const val LINE_FLICKER_GUARD_MS = 500L

        /** 从桌面歌词面板拉起时携带：若当前无打开文档则恢复最近打开的内部副本。 */
        const val EXTRA_RESTORE_LAST = "extra_restore_last"

        /** 从桌面歌词面板"手动添加歌词"入口拉起时携带：直接进入文件选择并强制绑定。 */
        const val EXTRA_MANUAL_ADD_LYRIC = "extra_manual_add_lyric"

        /**
         * 创建文件时使用的中立 MIME：MediaStore / SAF 的部分实现会按
         * MIME 对应的扩展名给 DISPLAY_NAME 自动补后缀（text/plain → .txt），
         * 把 xxx.lrc / xxx.krc 另存为后变成 xxx.lrc.txt。这个自定义类型
         * 在系统扩展名映射表里不存在，创建时不会被补后缀；写入完成后
         * 再把 MediaStore 记录的 MIME 改回真实类型（见 restoreDownloadMimeType）。
         */
        private const val CREATE_FILE_MIME = "application/x-androidlyrics"

        /** 新建歌词格式：LRC（标准歌词）。 */
        const val FORMAT_LRC = 0

        /** 新建歌词格式：SRT（字幕）。 */
        const val FORMAT_SRT = 1

        /** 新建歌词格式：TXT（纯文本，无时间轴）。 */
        const val FORMAT_TXT = 2

        /** 本次进程是否已经提示过通知使用权（进程结束后重置，应用更新后可再次引导）。 */
        @Volatile private var notifPromptShownThisLaunch = false

        /** 设置页「重置所有弹窗提示」时调用：清掉进程内的弹窗提示标记，让引导弹窗重新出现。 */
        @JvmStatic
        fun resetDialogPromptFlags() {
            notifPromptShownThisLaunch = false
        }
    }
}

/**
 * 底部播放控制条的圆形封面 + 播放进度环。
 *
 * 对应 lyrics-android 的 HomePlayerArtwork：56dp 外框画 2.5dp 环形进度，
 * 中心 46dp 圆形封面；无封面时显示音符占位，而不是保留上一首的位图。
 * 本项目是 View 体系，用单个自绘 View 实现，避免引入 Compose / Coil。
 */
class CoverArtView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val stroke = 2.5f * density
    private val trackPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        color = 0x29FFFFFF
    }
    private val progressPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = android.graphics.Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val placeholderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
    }
    private val coverPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val notePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = android.graphics.Paint.Align.CENTER
    }
    private val arcRect = android.graphics.RectF()
    private val shaderMatrix = android.graphics.Matrix()
    private var cover: android.graphics.Bitmap? = null
    private var shader: android.graphics.BitmapShader? = null

    /** 0~1 的播放进度，由 [MainActivity.updateMediaControls] 每帧推进。 */
    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            // 展开后进度环完全隐藏，此时不必再因进度刷新触发重绘。
            if (ringAlpha > 0.001f) invalidate()
        }

    /**
     * 进度环的不透明度，对应 lyrics-android 的 `ringAlpha = 1f - fraction`：
     * 收起时为 1（完整进度环），展开过程中平滑淡出，展开后封面铺满、没有进度条。
     */
    var ringAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v == field) return
            field = v
            invalidate()
        }

    /** 传 null 立即回退到占位图，杜绝切歌后残留上一首封面。 */
    fun setCover(bitmap: android.graphics.Bitmap?) {
        val next = bitmap?.takeIf { !it.isRecycled }
        if (next === cover) return
        cover = next
        shader = next?.let {
            android.graphics.BitmapShader(
                it, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = kotlin.math.min(width, height) / 2f - stroke / 2f
        if (radius <= 0f) return
        // 收起时完整画进度环；展开过程中整体淡出，展开后完全不画（参考项目 ringAlpha = 1 - fraction）
        if (ringAlpha > 0.001f) {
            trackPaint.alpha = (0x29 * ringAlpha).toInt().coerceIn(0, 255)
            progressPaint.alpha = (0xFF * ringAlpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, trackPaint)
            if (progress > 0f) {
                arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
            }
            trackPaint.alpha = 0x29
            progressPaint.alpha = 0xFF
        }
        // 进度环淡出时封面同步放大，补上原本留给环形的留白
        val ringGap = stroke + 3f * density
        val inner = (radius - ringGap + ringGap * (1f - ringAlpha)).coerceAtLeast(1f)
        canvas.drawCircle(cx, cy, inner, placeholderPaint)
        val bitmap = cover
        val currentShader = shader
        if (bitmap != null && currentShader != null) {
            val scale = (inner * 2f) / kotlin.math.min(bitmap.width, bitmap.height).toFloat()
            shaderMatrix.reset()
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(
                cx - bitmap.width * scale / 2f,
                cy - bitmap.height * scale / 2f
            )
            currentShader.setLocalMatrix(shaderMatrix)
            coverPaint.shader = currentShader
            canvas.drawCircle(cx, cy, inner, coverPaint)
            coverPaint.shader = null
        } else {
            notePaint.textSize = inner * 1.1f
            val fm = notePaint.fontMetrics
            canvas.drawText("♪", cx, cy - (fm.ascent + fm.descent) / 2f, notePaint)
        }
    }
}
