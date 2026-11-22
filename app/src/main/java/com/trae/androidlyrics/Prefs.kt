package com.trae.androidlyrics

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用设置：主题、字体大小、歌词动画、桌面歌词、背景图、通知授权等。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("lyrics_prefs", Context.MODE_PRIVATE)

    /** 主题模式：深色 / 浅色 / 跟随系统 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME, THEME_SYSTEM)
        set(value) = sp.edit().putInt(KEY_THEME, value).apply()

    /** 播放（阅读）模式字体大小（sp） */
    var fontSize: Int
        get() = sp.getInt(KEY_FONT_SIZE, 20)
        set(value) = sp.edit().putInt(KEY_FONT_SIZE, value).apply()

    /** 播放模式歌词文字对齐方式：0 左 / 1 中 / 2 右 */
    var lyricAlignment: Int
        get() = sp.getInt(KEY_LYRIC_ALIGNMENT, ALIGN_CENTER)
        set(value) = sp.edit().putInt(KEY_LYRIC_ALIGNMENT, value).apply()

    /** 编辑模式字体大小（sp），与播放模式独立 */
    var editFontSize: Int
        get() = sp.getInt(KEY_EDIT_FONT_SIZE, 16)
        set(value) = sp.edit().putInt(KEY_EDIT_FONT_SIZE, value).apply()

    /** 新建歌词的默认格式：0 LRC / 1 SRT / 2 TXT（记住上次的选择） */
    var newLyricFormat: Int
        get() = sp.getInt(KEY_NEW_LYRIC_FORMAT, 0)
        set(value) = sp.edit().putInt(KEY_NEW_LYRIC_FORMAT, value).apply()

    /** 是否开启逐字动画：对所有带逐字时间戳的歌词格式生效 */
    var wordByWord: Boolean
        get() = sp.getBoolean(KEY_WORD_BY_WORD, true)
        set(value) = sp.edit().putBoolean(KEY_WORD_BY_WORD, value).apply()

    /** 逐字动画的已唱颜色蒙版是否使用柔化前缘；主界面与桌面歌词共用，默认开启。 */
    var wordSoftTransition: Boolean
        get() = sp.getBoolean(KEY_WORD_SOFT_TRANSITION, true)
        set(value) = sp.edit().putBoolean(KEY_WORD_SOFT_TRANSITION, value).apply()

    /** 柔化前缘强度（0~100）；仅改变已唱颜色蒙版，不改变未唱颜色。 */
    var wordSoftTransitionStrength: Int
        get() = sp.getInt(
            KEY_WORD_SOFT_TRANSITION_STRENGTH, DEFAULT_WORD_SOFT_TRANSITION_STRENGTH
        ).coerceIn(MIN_WORD_SOFT_TRANSITION_STRENGTH, MAX_WORD_SOFT_TRANSITION_STRENGTH)
        set(value) = sp.edit().putInt(
            KEY_WORD_SOFT_TRANSITION_STRENGTH,
            value.coerceIn(MIN_WORD_SOFT_TRANSITION_STRENGTH, MAX_WORD_SOFT_TRANSITION_STRENGTH)
        ).apply()

    /**
     * 强行兼容逐字动画：歌词格式不带逐字时间戳时，仍按「本行起点 → 下一行起点」
     * 推算每个字的起始时间并驱动逐字动画。默认关闭（推算结果通常不准）。
     */
    var forceWordByWord: Boolean
        get() = sp.getBoolean(KEY_FORCE_WORD_BY_WORD, false)
        set(value) = sp.edit().putBoolean(KEY_FORCE_WORD_BY_WORD, value).apply()

    /**
     * 是否显示不带时间戳的歌词行（过渡台词、说明等）。
     * 开启时解析器会保留这些行并按前后时间轴推算显示时机；默认开启。
     */
    var showUntimedLines: Boolean
        get() = sp.getBoolean(KEY_SHOW_UNTIMED_LINES, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_UNTIMED_LINES, value).apply()

    /** 进入编辑状态时是否已勾选「不再询问」歌词编辑提示，默认 false（每次都提示）。 */
    var editTipDontAsk: Boolean
        get() = sp.getBoolean(KEY_EDIT_TIP_DONT_ASK, false)
        set(value) = sp.edit().putBoolean(KEY_EDIT_TIP_DONT_ASK, value).apply()

    // ---------------------------------------------------------------- 编辑页「更多设置」

    /**
     * 添加/更新时间戳的时间精度（小数点位数）：0 不要小数点 / 1 / 2 / 3。
     * 默认 2 位，与旧版固定输出 mm:ss.xx 的行为一致。
     */
    var timestampDecimalDigits: Int
        get() = sp.getInt(KEY_TIMESTAMP_DECIMAL_DIGITS, 2).coerceIn(0, 3)
        set(value) = sp.edit().putInt(KEY_TIMESTAMP_DECIMAL_DIGITS, value.coerceIn(0, 3)).apply()

    /** 整体小数点修改：移除位数（[DECIMAL_REMOVE_ONE] / [DECIMAL_REMOVE_TWO] / [DECIMAL_REMOVE_ALL]）。 */
    var decimalRemoveMode: Int
        get() = sp.getInt(KEY_DECIMAL_REMOVE_MODE, DECIMAL_REMOVE_ONE).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_DECIMAL_REMOVE_MODE, value.coerceIn(0, 2)).apply()

    /** 整体小数点修改：移除方式（[DECIMAL_MODE_DIRECT] 直接移除 / [DECIMAL_MODE_ROUND] 四舍五入后移除）。 */
    var decimalRoundMode: Int
        get() = sp.getInt(KEY_DECIMAL_ROUND_MODE, DECIMAL_MODE_DIRECT).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_DECIMAL_ROUND_MODE, value.coerceIn(0, 1)).apply()

    /** 升起键盘时，把光标选择行移动到可视区域，默认开启。 */
    var scrollCursorIntoViewOnKeyboard: Boolean
        get() = sp.getBoolean(KEY_SCROLL_CURSOR_ON_KEYBOARD, true)
        set(value) = sp.edit().putBoolean(KEY_SCROLL_CURSOR_ON_KEYBOARD, value).apply()

    /** 显示「打开键盘」按钮，默认开启。开启后点击文本不再自动打开键盘。 */
    var showOpenKeyboardButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_OPEN_KEYBOARD_BUTTON, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_OPEN_KEYBOARD_BUTTON, value).apply()

    /** 显示「在上方添加空行」按钮，默认开启。 */
    var showAddBlankAboveButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_ADD_BLANK_ABOVE, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_ADD_BLANK_ABOVE, value).apply()

    /** 显示「在下方添加空行」按钮，默认开启。 */
    var showAddBlankBelowButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_ADD_BLANK_BELOW, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_ADD_BLANK_BELOW, value).apply()

    /** 显示「删除所有空行」按钮，默认开启。 */
    var showDeleteAllBlankLinesButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_DELETE_ALL_BLANK, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_DELETE_ALL_BLANK, value).apply()

    /** 显示「删除当前选中行」按钮，默认开启。 */
    var showDeleteCurrentLineButton: Boolean
        get() = sp.getBoolean(KEY_SHOW_DELETE_CURRENT_LINE, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_DELETE_CURRENT_LINE, value).apply()

    /**
     * 编辑页辅助按钮的布局方式：[EDIT_BUTTONS_WRAP] 自动换行（默认）、
     * [EDIT_BUTTONS_SCROLL] 左右滚动。仅在按钮一行放不下时体现差异。
     */
    var editButtonLayout: Int
        get() = sp.getInt(KEY_EDIT_BUTTON_LAYOUT, EDIT_BUTTONS_WRAP).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_EDIT_BUTTON_LAYOUT, value.coerceIn(0, 1)).apply()

    /**
     * 重置所有「不再询问」类弹窗提示：
     * · 歌词编辑提示（勾选「不再询问」后不再弹出）
     * · 下载目录重名弹窗（勾选「记住操作，不再询问」后不再弹出）
     * · 打开外部歌词的同名副本冲突弹窗（勾选「记住这次操作，不再询问」后不再弹出）
     *
     * 重置后这些弹窗会重新出现。新增此类偏好时请同步在此处补充。
     */
    fun resetAllDialogPrompts() {
        sp.edit()
            .putBoolean(KEY_EDIT_TIP_DONT_ASK, false)
            .putInt(KEY_DUPLICATE_STRATEGY, DUPLICATE_ASK)
            .putInt(KEY_INTERNAL_COPY_CONFLICT_MODE, INTERNAL_COPY_ASK)
            .apply()
    }

    /** 歌词滚动动画：0 平滑 1 无动画 2 居中淡入 */
    var scrollAnimation: Int
        get() = sp.getInt(KEY_SCROLL_ANIM, ANIM_SMOOTH)
        set(value) = sp.edit().putInt(KEY_SCROLL_ANIM, value).apply()

    /**
     * 设置页「启用桌面歌词」开关记录的用户意图。只由用户主动操作写入
     * （设置页 / 通知栏 / 磁贴 / 悬浮面板），或由用户显式开启的
     * [autoStartDesktopLyrics] 在启动应用自动打开时同步置 true。
     *
     * 划掉后台 / 进程被清理 / 系统重启进程一律不改写它：启动应用时要靠这个
     * 开关判断是否自启动桌面歌词，清理后台把它关掉会让用户下次打开应用时
     * 丢失自启动依据。是否真正在显示以 DesktopLyricsService.isRunning 为准；
     * 清理后台后是否在「后台」自动恢复只由 [desktopProcessGuard] 决定。
     */
    var desktopLyrics: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_LYRICS, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_LYRICS, value).apply()

    /**
     * 「启用桌面歌词」开关关着时，启动应用是否仍自动打开桌面歌词（默认开启）。
     * 开关开着时无论本设置如何，启动应用都会恢复桌面歌词悬浮窗。
     */
    var autoStartDesktopLyrics: Boolean
        get() = sp.getBoolean(KEY_AUTO_START_DESKTOP, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_START_DESKTOP, value).apply()

    /**
     * 桌面歌词进程保护（默认关闭）。开启后，划掉应用后台 / 一键清理后台时
     * 由系统闹钟把桌面歌词服务重新拉起，悬浮窗不会消失。
     */
    var desktopProcessGuard: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_PROCESS_GUARD, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_PROCESS_GUARD, value).apply()

    /** 是否允许桌面歌词划入系统状态栏（默认关闭）。 */
    var desktopAllowStatusBar: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_ALLOW_STATUS_BAR, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_ALLOW_STATUS_BAR, value).apply()

    /** 是否在通知栏提供桌面歌词开关（默认关闭）。 */
    var notifyToggleDesktop: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_TOGGLE_DESKTOP, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIFY_TOGGLE_DESKTOP, value).apply()

    /**
     * 启动时是否发送桌面歌词开关通知（默认关闭）。
     * 开启后无论桌面歌词当前是否开启，应用启动时都会补发一条带开关的通知。
     */
    var notifyToggleOnStart: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_TOGGLE_ON_START, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIFY_TOGGLE_ON_START, value).apply()

    /** 调试模式：显示当前歌曲信息悬浮窗，默认关闭。 */
    var debugMode: Boolean
        get() = sp.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    /** 日志抓取开关：开启后将应用关键日志写入文件，便于排查问题。 */
    var logCaptureEnabled: Boolean
        get() = sp.getBoolean(KEY_LOG_CAPTURE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_LOG_CAPTURE_ENABLED, value).apply()

    /** 桌面歌词字体大小（sp） */
    var desktopFontSize: Int
        get() = sp.getInt(KEY_DESKTOP_FONT_SIZE, 16)
        set(value) = sp.edit().putInt(KEY_DESKTOP_FONT_SIZE, value).apply()

    /** 桌面歌词是否显示背景 */
    var desktopBackground: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_BG, true)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_BG, value).apply()

    /**
     * 桌面歌词背景的不透明度（0~100，默认 [DEFAULT_DESKTOP_BACKGROUND_ALPHA]）。
     * 0 表示完全透明，100 表示完全不透明；与「显示背景」开关配合生效。
     */
    var desktopBackgroundAlpha: Int
        get() = sp.getInt(KEY_DESKTOP_BG_ALPHA, DEFAULT_DESKTOP_BACKGROUND_ALPHA)
            .coerceIn(MIN_DESKTOP_BG_ALPHA, MAX_DESKTOP_BG_ALPHA)
        set(value) = sp.edit().putInt(
            KEY_DESKTOP_BG_ALPHA, value.coerceIn(MIN_DESKTOP_BG_ALPHA, MAX_DESKTOP_BG_ALPHA)
        ).apply()

    /**
     * 桌面歌词文字不透明度（0~100，默认 100 完全不透明）。
     * 只作用于文字、描边与逐字动画的已唱/未唱颜色，不影响桌面歌词背景。
     */
    var desktopTextAlpha: Int
        get() = sp.getInt(KEY_DESKTOP_TEXT_ALPHA, DEFAULT_DESKTOP_TEXT_ALPHA)
            .coerceIn(MIN_TEXT_ALPHA, MAX_TEXT_ALPHA)
        set(value) = sp.edit().putInt(
            KEY_DESKTOP_TEXT_ALPHA, value.coerceIn(MIN_TEXT_ALPHA, MAX_TEXT_ALPHA)
        ).apply()

    /**
     * 桌面歌词左右边缘渐隐：默认开启。
     *
     * 悬浮窗在固定宽度模式下，超长歌词由 [LineScroller] 左右滚动进出，
     * 两端是硬切的边。开启后只在窗口左右边缘把文字自身的 alpha 按水平渐变裁掉
     * （背景与圆角不动），滚动进出与静止贴边的字都不会出现突然断开的截面。
     */
    var desktopEdgeFade: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_EDGE_FADE, true)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_EDGE_FADE, value).apply()

    /**
     * 桌面歌词左右边缘羽化的范围，按**半个字**计：1 = 左右各半个字逐渐消失，
     * 2 = 左右各一个字，依次到 6（左右各三个字）。默认 [DEFAULT_DESKTOP_EDGE_FADE_CHARS]。
     *
     * 渲染上就是给**文字区域**盖一层水平蒙版，把文字左右两端按线性 alpha 羽化掉；
     * 带宽以 [DesktopLyricsFadeLayout.visibleTextArea]（文字子 View 去内边距后与窗口内容区的
     * 交集）两端的可见边缘为基准向内量，字宽用当前字体实测的全角字宽，
     * 所以换字号后"半个字"始终是当前字的一半。
     */
    var desktopEdgeFadeChars: Int
        get() = sp.getInt(KEY_DESKTOP_EDGE_FADE_CHARS, DEFAULT_DESKTOP_EDGE_FADE_CHARS)
            .coerceIn(MIN_DESKTOP_EDGE_FADE_CHARS, MAX_DESKTOP_EDGE_FADE_CHARS)
        set(value) = sp.edit().putInt(
            KEY_DESKTOP_EDGE_FADE_CHARS,
            value.coerceIn(MIN_DESKTOP_EDGE_FADE_CHARS, MAX_DESKTOP_EDGE_FADE_CHARS)
        ).apply()

    /** 桌面歌词逐字歌词：带逐字时间戳的格式按播放进度逐字高亮，默认关闭。 */
    var desktopWordByWord: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_WORD_BY_WORD, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_WORD_BY_WORD, value).apply()

    /**
     * 桌面歌词强行兼容逐字动画：格式不带逐字时间戳时，仍按整行时长推算逐字进度。
     * 与主界面的「强行兼容逐字动画」共用 [WordTimingEstimator] 判断逻辑，
     * 但两个开关彼此独立；仅在 [desktopWordByWord] 开启时生效，默认关闭。
     */
    var desktopForceWordByWord: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_FORCE_WORD_BY_WORD, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_FORCE_WORD_BY_WORD, value).apply()

    /**
     * 旧版「已唱歌词颜色」（ARGB int）：主界面与桌面歌词共用。
     * 现在已拆分为 [mainSungTextColor] / [desktopSungTextColor]，本项只作为两者
     * 「尚未单独设置」时的默认值保留，保证覆盖安装后外观与旧版一致；界面不再写入。
     */
    var sungTextColor: Int
        get() = sp.getInt(KEY_SUNG_TEXT_COLOR, DEFAULT_SUNG_TEXT_COLOR)
        set(value) = sp.edit().putInt(KEY_SUNG_TEXT_COLOR, value).apply()

    /** 主界面歌词逐字动画的已唱颜色（ARGB int）；未单独设置时沿用旧版共用色。 */
    var mainSungTextColor: Int
        get() = sp.getInt(KEY_MAIN_SUNG_TEXT_COLOR, sungTextColor)
        set(value) = sp.edit().putInt(KEY_MAIN_SUNG_TEXT_COLOR, value).apply()

    /** 桌面歌词逐字动画的已唱颜色（ARGB int）；未单独设置时沿用旧版共用色。 */
    var desktopSungTextColor: Int
        get() = sp.getInt(KEY_DESKTOP_SUNG_TEXT_COLOR, sungTextColor)
        set(value) = sp.edit().putInt(KEY_DESKTOP_SUNG_TEXT_COLOR, value).apply()

    // ---------------------------------------------------------------- 主界面歌词文字外观

    /** 主界面歌词文字颜色（ARGB int），默认白色；非当前行与逐字未唱层按此色降级。 */
    var mainTextColor: Int
        get() = sp.getInt(KEY_MAIN_TEXT_COLOR, 0xFFFFFFFF.toInt())
        set(value) = sp.edit().putInt(KEY_MAIN_TEXT_COLOR, value).apply()

    /**
     * 主界面歌词文字不透明度（0~100，默认 100）：同时作用于普通行、逐字动画的
     * 已唱/未唱颜色与描边颜色；与桌面歌词的 [desktopTextAlpha] 互不影响。
     */
    var mainTextAlpha: Int
        get() = sp.getInt(KEY_MAIN_TEXT_ALPHA, DEFAULT_DESKTOP_TEXT_ALPHA)
            .coerceIn(MIN_TEXT_ALPHA, MAX_TEXT_ALPHA)
        set(value) = sp.edit().putInt(
            KEY_MAIN_TEXT_ALPHA, value.coerceIn(MIN_TEXT_ALPHA, MAX_TEXT_ALPHA)
        ).apply()

    /** 主界面歌词是否启用文字描边（默认关闭，与桌面歌词独立）。 */
    var mainOutlineEnabled: Boolean
        get() = sp.getBoolean(KEY_MAIN_OUTLINE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_MAIN_OUTLINE_ENABLED, value).apply()

    /** 主界面歌词文字描边颜色（ARGB int）。 */
    var mainOutlineColor: Int
        get() = sp.getInt(KEY_MAIN_OUTLINE_COLOR, 0xFF000000.toInt())
        set(value) = sp.edit().putInt(KEY_MAIN_OUTLINE_COLOR, value).apply()

    /** 主界面歌词文字描边粗细（dp，1~10，默认 2）。 */
    var mainOutlineWidth: Int
        get() = sp.getInt(KEY_MAIN_OUTLINE_WIDTH, DEFAULT_OUTLINE_WIDTH)
            .coerceIn(MIN_OUTLINE_WIDTH, MAX_OUTLINE_WIDTH)
        set(value) = sp.edit().putInt(
            KEY_MAIN_OUTLINE_WIDTH, value.coerceIn(MIN_OUTLINE_WIDTH, MAX_OUTLINE_WIDTH)
        ).apply()

    /** 主界面歌词描边位置：[OUTLINE_POSITION_INSIDE] / CENTER / OUTSIDE，默认外部。 */
    var mainOutlinePosition: Int
        get() = sp.getInt(KEY_MAIN_OUTLINE_POSITION, DEFAULT_OUTLINE_POSITION)
            .coerceIn(OUTLINE_POSITION_INSIDE, OUTLINE_POSITION_OUTSIDE)
        set(value) = sp.edit().putInt(
            KEY_MAIN_OUTLINE_POSITION,
            value.coerceIn(OUTLINE_POSITION_INSIDE, OUTLINE_POSITION_OUTSIDE)
        ).apply()

    /** 桌面歌词文字颜色（ARGB int） */
    var desktopTextColor: Int
        get() = sp.getInt(KEY_DESKTOP_TEXT_COLOR, 0xFFFFFFFF.toInt())
        set(value) = sp.edit().putInt(KEY_DESKTOP_TEXT_COLOR, value).apply()

    /** 桌面歌词是否启用文字描边 */
    var desktopOutlineEnabled: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_OUTLINE_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_OUTLINE_ENABLED, value).apply()

    /** 桌面歌词文字描边颜色（ARGB int） */
    var desktopOutlineColor: Int
        get() = sp.getInt(KEY_DESKTOP_OUTLINE_COLOR, 0xFF000000.toInt())
        set(value) = sp.edit().putInt(KEY_DESKTOP_OUTLINE_COLOR, value).apply()

    /** 桌面歌词文字描边粗细（dp，1~10，默认 2） */
    var desktopOutlineWidth: Int
        get() = sp.getInt(KEY_DESKTOP_OUTLINE_WIDTH, DEFAULT_OUTLINE_WIDTH)
        set(value) = sp.edit().putInt(KEY_DESKTOP_OUTLINE_WIDTH, value).apply()

    /** 桌面歌词描边位置：[OUTLINE_POSITION_INSIDE] / CENTER / OUTSIDE，默认外部（旧版效果）。 */
    var desktopOutlinePosition: Int
        get() = sp.getInt(KEY_DESKTOP_OUTLINE_POSITION, DEFAULT_OUTLINE_POSITION)
            .coerceIn(OUTLINE_POSITION_INSIDE, OUTLINE_POSITION_OUTSIDE)
        set(value) = sp.edit().putInt(
            KEY_DESKTOP_OUTLINE_POSITION,
            value.coerceIn(OUTLINE_POSITION_INSIDE, OUTLINE_POSITION_OUTSIDE)
        ).apply()

    /**
     * 快捷导入时勾选的最近目录 URI 集合（记忆勾选状态）。
     * 仅记录 URI 字符串，实际目录信息仍以最近目录列表为准。
     *
     * [hasQuickImportSelectedDirs] 用于区分"从未设置过（默认全选）"与
     * "用户主动取消全部勾选（空集合）"。
     */
    fun hasQuickImportSelectedDirs(): Boolean = sp.contains(KEY_QUICK_IMPORT_SELECTED)

    fun getQuickImportSelectedDirs(): Set<String> {
        val raw = sp.getString(KEY_QUICK_IMPORT_SELECTED, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun setQuickImportSelectedDirs(uris: Set<String>) {
        val arr = JSONArray()
        uris.forEach { arr.put(it) }
        sp.edit().putString(KEY_QUICK_IMPORT_SELECTED, arr.toString()).apply()
    }

    /**
     * 从目录导入时勾选的歌词格式集合（lrc / srt 等），记忆勾选状态。
     * 从未设置过时返回 null，由调用方决定默认值。
     */
    fun getQuickImportFormats(): Set<String>? {
        val raw = sp.getString(KEY_QUICK_IMPORT_FORMATS, null) ?: return null
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(null)
    }

    fun setQuickImportFormats(formats: Set<String>) {
        val arr = JSONArray()
        formats.forEach { arr.put(it) }
        sp.edit().putString(KEY_QUICK_IMPORT_FORMATS, arr.toString()).apply()
    }

    /** 从目录导入时勾选"不限格式"：勾选后忽略具体格式，导入目录内全部文件。 */
    var quickImportAnyFormat: Boolean
        get() = sp.getBoolean(KEY_QUICK_IMPORT_ANY_FORMAT, false)
        set(value) = sp.edit().putBoolean(KEY_QUICK_IMPORT_ANY_FORMAT, value).apply()

    /**
     * 桌面歌词水平锚点（绝对像素，屏幕左上角为原点）：左对齐时为左缘，
     * 居中时为中心，右对齐时为右缘。设置页以屏幕中心为 0 显示相对偏移，
     * 换算见 [DESKTOP_POSITION_MIN_PX]。默认值沿用，保证升级后位置不跳变。
     */
    var desktopOffsetX: Int
        get() = sp.getInt(KEY_DESKTOP_OFFSET_X, 100)
        set(value) = sp.edit().putInt(KEY_DESKTOP_OFFSET_X, value).apply()

    /**
     * 桌面歌词垂直位置（绝对像素，屏幕左上角为原点，即窗口顶部 y）。
     * 设置页以屏幕中心为 0 显示相对偏移。默认值沿用，保证升级后位置不跳变。
     */
    var desktopOffsetY: Int
        get() = sp.getInt(KEY_DESKTOP_OFFSET_Y, 300)
        set(value) = sp.edit().putInt(KEY_DESKTOP_OFFSET_Y, value).apply()

    /** 桌面歌词最大宽度（dp），0 表示不限 */
    var desktopWidth: Int
        get() = sp.getInt(KEY_DESKTOP_WIDTH, DEFAULT_DESKTOP_WIDTH)
        set(value) = sp.edit().putInt(KEY_DESKTOP_WIDTH, value).apply()

    /** 桌面歌词自适应宽度：开启后按歌词长度自动调整宽度，手动宽度设置不可用 */
    var desktopAutoFitWidth: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_AUTO_FIT_WIDTH, false)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_AUTO_FIT_WIDTH, value).apply()

    /** 自适应宽度的上限（dp）：超出后按最大宽度显示并滚动 */
    var desktopAutoFitMaxWidth: Int
        get() = sp.getInt(KEY_DESKTOP_AUTO_FIT_MAX_WIDTH, DEFAULT_AUTO_FIT_MAX_WIDTH)
        set(value) = sp.edit().putInt(KEY_DESKTOP_AUTO_FIT_MAX_WIDTH, value).apply()

    /** 自适应宽度时歌词内容的左右边距（dp） */
    var desktopAutoFitPadding: Int
        get() = sp.getInt(KEY_DESKTOP_AUTO_FIT_PADDING, DEFAULT_AUTO_FIT_PADDING)
        set(value) = sp.edit().putInt(KEY_DESKTOP_AUTO_FIT_PADDING, value).apply()

    /** 自动匹配相似度阈值（百分比 30~90），低于该值不自动匹配 */
    var matchSimilarity: Int
        get() = sp.getInt(KEY_MATCH_SIMILARITY, DEFAULT_MATCH_SIMILARITY)
        set(value) = sp.edit().putInt(KEY_MATCH_SIMILARITY, value).apply()

    /** 歌词空行显示模式 */
    var emptyLineMode: Int
        get() = sp.getInt(KEY_EMPTY_LINE_MODE, EMPTY_LINE_NONE)
        set(value) = sp.edit().putInt(KEY_EMPTY_LINE_MODE, value).apply()

    /** 自定义空行显示文字（emptyLineMode == EMPTY_LINE_CUSTOM 时生效） */
    var emptyLineCustom: String
        get() = sp.getString(KEY_EMPTY_LINE_CUSTOM, "").orEmpty()
        set(value) = sp.edit().putString(KEY_EMPTY_LINE_CUSTOM, value).apply()

    /** 当前设置下空行应显示的文字；null 表示不做任何处理。 */
    fun emptyLineText(): String? = when (emptyLineMode) {
        EMPTY_LINE_BEAT -> "♬"
        EMPTY_LINE_TILDE -> "~"
        EMPTY_LINE_ACCOMPANIMENT -> "伴奏"
        EMPTY_LINE_CUSTOM -> emptyLineCustom.trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    /** 桌面歌词显示行数：1 单行 / 2 双行 */
    var desktopLineCount: Int
        get() = sp.getInt(KEY_DESKTOP_LINE_COUNT, 1)
        set(value) = sp.edit().putInt(KEY_DESKTOP_LINE_COUNT, value).apply()

    /** 桌面歌词对齐方式：0 左 / 1 中 / 2 右 */
    var desktopAlignment: Int
        get() = sp.getInt(KEY_DESKTOP_ALIGNMENT, 1)
        set(value) = sp.edit().putInt(KEY_DESKTOP_ALIGNMENT, value).apply()

    /** 桌面歌词是否显示翻译（若歌词有翻译行） */
    var desktopShowTranslation: Boolean
        get() = sp.getBoolean(KEY_DESKTOP_TRANSLATION, true)
        set(value) = sp.edit().putBoolean(KEY_DESKTOP_TRANSLATION, value).apply()

    /** 双语歌词高亮行：0 仅第一行 1 仅第二行 2 两行都高亮 */
    var bilingualHighlight: Int
        get() = sp.getInt(KEY_BILINGUAL_HIGHLIGHT, BILINGUAL_BOTH)
        set(value) = sp.edit().putInt(KEY_BILINGUAL_HIGHLIGHT, value).apply()

    /**
     * 桌面歌词双语显示行：0 仅第一行 1 仅第二行 2 两行都显示。
     * 默认沿用旧行为（同一时间戳组内显示最后一行，即仅第二行）。
     */
    var desktopBilingualLine: Int
        get() = sp.getInt(KEY_DESKTOP_BILINGUAL_LINE, BILINGUAL_SECOND)
        set(value) = sp.edit().putInt(KEY_DESKTOP_BILINGUAL_LINE, value).apply()

    /** 保存到下载目录时遇到重名的处理策略：0 询问 1 覆盖 2 创建副本 */
    var duplicateStrategy: Int
        get() = sp.getInt(KEY_DUPLICATE_STRATEGY, DUPLICATE_ASK)
        set(value) = sp.edit().putInt(KEY_DUPLICATE_STRATEGY, value).apply()

    /**
     * 打开外部歌词、内部歌词目录已有同名副本时的处理策略：
     * [INTERNAL_COPY_ASK] 询问（默认）、[INTERNAL_COPY_OVERWRITE] 覆盖、
     * [INTERNAL_COPY_OPEN_EXISTING] 打开已有文件、[INTERNAL_COPY_RENAME] 重命名保存、
     * [INTERNAL_COPY_CREATE_COPY] 创建副本。
     * 勾选弹窗「记住这次操作，不再询问」后写入，重置弹窗提示后恢复询问。
     * 注意：记住的只是「操作类型」，RENAME 仍会每次弹输入框让用户定名字。
     */
    var internalCopyConflictMode: Int
        get() = sp.getInt(KEY_INTERNAL_COPY_CONFLICT_MODE, INTERNAL_COPY_ASK)
        set(value) = sp.edit().putInt(KEY_INTERNAL_COPY_CONFLICT_MODE, value).apply()

    /** 播放界面标题栏点击弹出的列表：0 匹配到的歌词 1 最近打开的歌词 */
    var titleListMode: Int
        get() = sp.getInt(KEY_TITLE_LIST_MODE, TITLE_LIST_MATCHED)
        set(value) = sp.edit().putInt(KEY_TITLE_LIST_MODE, value).apply()

    /** 点击歌词跳转时是否显示时间提示 Toast，默认关闭 */
    var showSeekToast: Boolean
        get() = sp.getBoolean(KEY_SHOW_SEEK_TOAST, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_SEEK_TOAST, value).apply()

    /** 旧版自定义背景图 URI：仅用于一次性迁移到背景图片库，迁移后置空。 */
    var backgroundUri: String?
        get() = sp.getString(KEY_BG_URI, null)
        set(value) = sp.edit().putString(KEY_BG_URI, value).apply()

    /** 背景图片库：JSON 数组，每项含图片 id 与缩放/位移参数。 */
    var backgroundItems: String
        get() = sp.getString(KEY_BG_ITEMS, "[]") ?: "[]"
        set(value) = sp.edit().putString(KEY_BG_ITEMS, value).apply()

    /** 当前正在使用的背景图片 id；为空表示使用默认背景。 */
    var backgroundCurrentId: String?
        get() = sp.getString(KEY_BG_CURRENT_ID, null)
        set(value) = sp.edit().putString(KEY_BG_CURRENT_ID, value).apply()

    /** 旧版 bg_uri 是否已迁移，避免重复导入。 */
    var backgroundLegacyMigrated: Boolean
        get() = sp.getBoolean(KEY_BG_MIGRATED, false)
        set(value) = sp.edit().putBoolean(KEY_BG_MIGRATED, value).apply()

    /** 编辑预览「界外」图片不透明度百分比，默认 10%。 */
    var backgroundGhostAlpha: Int
        get() = sp.getInt(KEY_BG_GHOST_ALPHA, 10).coerceIn(0, 100)
        set(value) = sp.edit().putInt(KEY_BG_GHOST_ALPHA, value.coerceIn(0, 100)).apply()

    /** 编辑预览是否叠加示例歌词，默认关闭。 */
    var backgroundPreviewLyrics: Boolean
        get() = sp.getBoolean(KEY_BG_PREVIEW_LYRICS, false)
        set(value) = sp.edit().putBoolean(KEY_BG_PREVIEW_LYRICS, value).apply()

    /** 编辑预览是否显示屏幕边界描边与状态栏提示线，默认开启。 */
    var backgroundPreviewBorder: Boolean
        get() = sp.getBoolean(KEY_BG_PREVIEW_BORDER, true)
        set(value) = sp.edit().putBoolean(KEY_BG_PREVIEW_BORDER, value).apply()

    /** 是否记忆上次播放/编辑位置 */
    var rememberPosition: Boolean
        get() = sp.getBoolean(KEY_REMEMBER_POSITION, true)
        set(value) = sp.edit().putBoolean(KEY_REMEMBER_POSITION, value).apply()

    /** 沉浸模式下是否保留系统栏 */
    var immersiveShowBars: Boolean
        get() = sp.getBoolean(KEY_IMMERSIVE_BARS, false)
        set(value) = sp.edit().putBoolean(KEY_IMMERSIVE_BARS, value).apply()

    /** 歌曲切换时是否自动匹配应用存储目录下的歌词文件（按文件名/标题/作者相似度） */
    var autoMatchLyrics: Boolean
        get() = sp.getBoolean(KEY_AUTO_MATCH_LYRICS, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_MATCH_LYRICS, value).apply()

    /** 上次启动时的版本号，用于检测应用更新（-1 表示首次运行） */
    var lastVersionCode: Long
        get() = sp.getLong(KEY_LAST_VERSION_CODE, -1L)
        set(value) = sp.edit().putLong(KEY_LAST_VERSION_CODE, value).apply()

    // ---------------------------------------------------------------- 播放偏移设置

    /**
     * 「偏移设置」里的整体播放偏移（毫秒，[PlaybackOffset.MIN_GLOBAL_OFFSET_MS] ~
     * [PlaybackOffset.MAX_GLOBAL_OFFSET_MS]，默认 0）。
     * 正值延后歌词显示、负值提前；对所有应用生效，与按应用的毫秒信息不可信开关叠加。
     */
    var globalOffsetMs: Int
        get() = sp.getInt(KEY_GLOBAL_OFFSET_MS, 0).coerceIn(
            PlaybackOffset.MIN_GLOBAL_OFFSET_MS, PlaybackOffset.MAX_GLOBAL_OFFSET_MS
        )
        set(value) = sp.edit().putInt(
            KEY_GLOBAL_OFFSET_MS, value.coerceIn(
                PlaybackOffset.MIN_GLOBAL_OFFSET_MS, PlaybackOffset.MAX_GLOBAL_OFFSET_MS
            )
        ).apply()

    /**
     * 按播放应用包名记忆「当前应用的毫秒信息不可信」。
     * 从未显式设置过时：Telegram 系列默认开启，其它应用默认关闭。
     */
    fun isAppMsOffsetEnabled(packageName: String): Boolean =
        sp.getBoolean(
            "$KEY_APP_MS_OFFSET_PREFIX$packageName",
            PlaybackOffset.isTelegram(packageName)
        )

    /** 写入某应用的毫秒信息不可信开关；开启时把它登记到「已开启应用」列表。 */
    fun setAppMsOffsetEnabled(packageName: String, enabled: Boolean) {
        if (enabled) trackMsOffsetApp(packageName)
        sp.edit().putBoolean("$KEY_APP_MS_OFFSET_PREFIX$packageName", enabled).apply()
    }

    /** 登记一个应用，使其出现在「偏移设置」的已开启应用列表里。 */
    fun trackMsOffsetApp(packageName: String) = trackApp(KEY_APP_MS_OFFSET_TRACKED, packageName)

    /** 「偏移设置」展示用的应用列表（曾开启过该开关的应用）。 */
    fun getTrackedMsOffsetApps(): Set<String> = readTrackedApps(KEY_APP_MS_OFFSET_TRACKED)

    /**
     * 关闭「偏移设置」弹窗时调用：仍为关闭状态的应用从列表里移除，
     * 下次打开弹窗不再出现；弹窗内重新开启过的应用会保留。
     */
    fun pruneTrackedMsOffsetApps() =
        pruneTrackedApps(KEY_APP_MS_OFFSET_TRACKED, ::isAppMsOffsetEnabled)

    // ------------------------------------------------ 按应用开关的列表存取

    private fun trackApp(key: String, packageName: String) {
        if (packageName.isBlank()) return
        val tracked = readTrackedApps(key).toMutableSet()
        if (tracked.add(packageName)) writeTrackedApps(key, tracked)
    }

    private fun readTrackedApps(key: String): Set<String> {
        val raw = sp.getString(key, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeTrackedApps(key: String, packages: Set<String>) {
        sp.edit().putString(key, encodePackages(packages)).apply()
    }

    private fun pruneTrackedApps(key: String, enabled: (String) -> Boolean) {
        writeTrackedApps(key, readTrackedApps(key).filter(enabled).toSet())
    }

    private fun encodePackages(packages: Set<String>): String {
        val arr = JSONArray()
        packages.sorted().forEach { arr.put(it) }
        return arr.toString()
    }

    // ---------------------------------------------------------------- 编辑模式：进度调节 / 整体偏移

    /** 编辑页进度调节：回退大步（秒，支持小数）。 */
    var seekRewindBigSec: Float
        get() = sp.getFloat(KEY_SEEK_REWIND_BIG, 5f)
        set(value) = sp.edit().putFloat(KEY_SEEK_REWIND_BIG, value).apply()

    /** 编辑页进度调节：回退小步（秒，支持小数）。 */
    var seekRewindSmallSec: Float
        get() = sp.getFloat(KEY_SEEK_REWIND_SMALL, 1f)
        set(value) = sp.edit().putFloat(KEY_SEEK_REWIND_SMALL, value).apply()

    /** 编辑页进度调节：前进小步（秒，支持小数）。 */
    var seekForwardSmallSec: Float
        get() = sp.getFloat(KEY_SEEK_FORWARD_SMALL, 1f)
        set(value) = sp.edit().putFloat(KEY_SEEK_FORWARD_SMALL, value).apply()

    /** 编辑页进度调节：前进大步（秒，支持小数）。 */
    var seekForwardBigSec: Float
        get() = sp.getFloat(KEY_SEEK_FORWARD_BIG, 5f)
        set(value) = sp.edit().putFloat(KEY_SEEK_FORWARD_BIG, value).apply()

    /** 整体偏移弹窗中的一条选项：数值（字符串原样记忆）+ 单位。 */
    data class OffsetOption(val value: String, val unit: Int) {
        companion object {
            /** 单位：毫秒。 */
            const val UNIT_MS = 0

            /** 单位：秒。 */
            const val UNIT_S = 1
        }
    }

    /**
     * 整体偏移弹窗的选项列表（记忆，避免重复设置）。
     * 从未设置过时返回默认两条：500 毫秒、1 秒。
     */
    fun getGlobalOffsetOptions(): List<OffsetOption> {
        val raw = sp.getString(KEY_GLOBAL_OFFSET_OPTIONS, null) ?: return listOf(
            OffsetOption("500", OffsetOption.UNIT_MS),
            OffsetOption("1", OffsetOption.UNIT_S),
        )
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OffsetOption(o.getString("value"), o.getInt("unit"))
            }
        }.getOrDefault(emptyList())
    }

    /** 保存整体偏移选项列表。 */
    fun setGlobalOffsetOptions(options: List<OffsetOption>) {
        val arr = JSONArray()
        options.forEach {
            arr.put(JSONObject().put("value", it.value).put("unit", it.unit))
        }
        sp.edit().putString(KEY_GLOBAL_OFFSET_OPTIONS, arr.toString()).apply()
    }

    /**
     * 应用更新后通知监听绑定待恢复标记。
     * 更新会断开监听绑定，单次 requestRebind 可能被系统吞掉导致自动匹配失效；
     * 置位后每次进程启动都尝试恢复，直到监听服务真正重连成功才清除。
     */
    var needsNlsRebind: Boolean
        get() = sp.getBoolean(KEY_NEEDS_NLS_REBIND, false)
        set(value) = sp.edit().putBoolean(KEY_NEEDS_NLS_REBIND, value).apply()

    /** 读取指定文件的阅读位置。 */
    fun position(uri: Uri?): Int {
        if (uri == null) return 0
        return sp.getInt("pos_$uri", 0)
    }

    /** 记录指定文件的阅读位置。 */
    fun savePosition(uri: Uri?, value: Int) {
        if (uri == null) return
        sp.edit().putInt("pos_$uri", value).apply()
    }

    // ---------------------------------------------------------------- 最近目录 / 最近文件

    /** 最近选择的保存目录（按时间倒序）。path 为完整相对路径，用于区分同名目录。 */
    data class RecentDir(val uri: String, val name: String, val timeMs: Long, val path: String = "")

    fun getRecentDirs(): List<RecentDir> {
        val raw = sp.getString(KEY_RECENT_DIRS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentDir(o.getString("uri"), o.optString("name", ""), o.getLong("time"), o.optString("path", ""))
            }
        }.getOrDefault(emptyList())
    }

    /** 添加/更新一个最近目录（同名 uri 会更新时间并移到首位）。 */
    fun addRecentDir(uri: String, name: String, path: String = "") {
        val list = getRecentDirs().filter { it.uri != uri }.toMutableList()
        list.add(0, RecentDir(uri, name, System.currentTimeMillis(), path))
        val trimmed = list.take(MAX_RECENT)
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name)
                .put("time", it.timeMs).put("path", it.path))
        }
        sp.edit().putString(KEY_RECENT_DIRS, arr.toString()).apply()
    }

    fun removeRecentDirs(uris: Collection<String>) {
        val set = uris.toSet()
        val list = getRecentDirs().filter { it.uri !in set }
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name)
                .put("time", it.timeMs).put("path", it.path))
        }
        sp.edit().putString(KEY_RECENT_DIRS, arr.toString()).apply()
    }

    fun clearRecentDirs() {
        sp.edit().remove(KEY_RECENT_DIRS).apply()
    }

    /** 最近打开的文件（按时间倒序）。 */
    data class RecentFile(val uri: String, val name: String, val timeMs: Long)

    fun getRecentFiles(): List<RecentFile> {
        val raw = sp.getString(KEY_RECENT_FILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                RecentFile(o.getString("uri"), o.optString("name", ""), o.getLong("time"))
            }
        }.getOrDefault(emptyList())
    }

    /** 添加/更新一个最近打开的文件。 */
    fun addRecentFile(uri: String, name: String) {
        val list = getRecentFiles().filter { it.uri != uri }.toMutableList()
        list.add(0, RecentFile(uri, name, System.currentTimeMillis()))
        val trimmed = list.take(MAX_RECENT_FILES)
        val arr = JSONArray()
        trimmed.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.timeMs)) }
        sp.edit().putString(KEY_RECENT_FILES, arr.toString()).apply()
    }

    fun removeRecentFile(uri: String) {
        val list = getRecentFiles().filter { it.uri != uri }
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.timeMs)) }
        sp.edit().putString(KEY_RECENT_FILES, arr.toString()).apply()
    }

    /**
     * 桌面歌词最后显示的歌词文件（应用内部副本绝对路径）。
     * 进程被快捷入口冷启动拉起、通知监听尚未重连时，桌面歌词服务据此
     * 立即从磁盘恢复上次的歌词，避免长时间停留在「暂无歌词」。
     */
    var lastLyricFile: String?
        get() = sp.getString(KEY_LAST_LYRIC_FILE, null)
        set(value) = sp.edit().putString(KEY_LAST_LYRIC_FILE, value).apply()

    // ---------------------------------------------------------------- 设置项搜索历史

    /**
     * 「搜索设置」页最近用过的关键词（按时间倒序，忽略大小写去重）。
     *
     * 只在用户真正提交一次搜索时写入：按下键盘搜索键、点开某条结果，或复用一个
     * 历史词。逐字输入的过程不写，否则历史里全是同一句话的前缀。
     */
    fun getRecentSearches(): List<String> {
        val raw = sp.getString(KEY_RECENT_SEARCHES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /** 记录一次搜索：去掉首尾空白，同义词（忽略大小写）移到最前，最多保留 8 条。 */
    fun addRecentSearch(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        val rest = getRecentSearches().filterNot { it.equals(text, ignoreCase = true) }
        saveRecentSearches((listOf(text) + rest).take(MAX_RECENT_SEARCHES))
    }

    fun removeRecentSearch(query: String) {
        saveRecentSearches(getRecentSearches().filterNot { it.equals(query, ignoreCase = true) })
    }

    fun clearRecentSearches() {
        sp.edit().remove(KEY_RECENT_SEARCHES).apply()
    }

    private fun saveRecentSearches(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString(KEY_RECENT_SEARCHES, arr.toString()).apply()
    }

    companion object {
        const val THEME_DARK = 0
        const val THEME_LIGHT = 1
        const val THEME_SYSTEM = 2

        const val ANIM_SMOOTH = 0
        const val ANIM_NONE = 1
        const val ANIM_FADE = 2

        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2

        /**
         * 桌面歌词位置在设置页的可调范围（px）：以屏幕中心为 0，负值向左/上。
         * 存储层仍是「屏幕左上角为原点的绝对锚点」（见 DesktopLyricsService 的
         * updateWindowPosition / 拖动写回），界面层换算：
         * 显示值 = 绝对锚点 - 屏幕中心；写入值 = 屏幕中心 + 显示值。
         */
        const val DESKTOP_POSITION_MIN_PX = -3000
        const val DESKTOP_POSITION_MAX_PX = 3000
        const val DESKTOP_POSITION_RANGE_PX =
            DESKTOP_POSITION_MAX_PX - DESKTOP_POSITION_MIN_PX

        const val BILINGUAL_FIRST = 0
        const val BILINGUAL_SECOND = 1
        const val BILINGUAL_BOTH = 2

        /** 下载目录重名时的处理策略：0 询问 1 覆盖 2 创建副本 */
        const val DUPLICATE_ASK = 0
        const val DUPLICATE_OVERWRITE = 1
        const val DUPLICATE_COPY = 2

        /** 打开外部歌词遇同名内部副本的处理策略：0 询问 1 覆盖 2 打开已有 3 重命名 4 创建副本 */
        const val INTERNAL_COPY_ASK = 0
        const val INTERNAL_COPY_OVERWRITE = 1
        const val INTERNAL_COPY_OPEN_EXISTING = 2
        const val INTERNAL_COPY_RENAME = 3
        const val INTERNAL_COPY_CREATE_COPY = 4

        /** 整体小数点修改：移除最后 1 位 / 移除最后 2 位 / 移除所有小数位。 */
        const val DECIMAL_REMOVE_ONE = 0
        const val DECIMAL_REMOVE_TWO = 1
        const val DECIMAL_REMOVE_ALL = 2

        /** 整体小数点修改：直接移除（截断） / 四舍五入后移除。 */
        const val DECIMAL_MODE_DIRECT = 0
        const val DECIMAL_MODE_ROUND = 1

        /** 播放界面标题栏点击弹出的列表：0 匹配到的歌词 1 最近打开的歌词 */
        const val TITLE_LIST_MATCHED = 0
        const val TITLE_LIST_RECENT = 1

        /**
         * 桌面歌词宽度在设置页的可调范围（dp）：固定宽度与自适应最大宽度共用。
         * minSdk 24 上 SeekBar 的 android:min 不生效，滑块统一用偏移映射：
         * progress = 值 - MIN、写入值 = progress + MIN，android:max 取 RANGE。
         */
        const val DESKTOP_WIDTH_MIN_DP = 1
        const val DESKTOP_WIDTH_MAX_DP = 600
        const val DESKTOP_WIDTH_RANGE_DP = DESKTOP_WIDTH_MAX_DP - DESKTOP_WIDTH_MIN_DP

        /** 桌面歌词默认宽度（dp）。 */
        const val DEFAULT_DESKTOP_WIDTH = 280

        /** 自适应宽度默认上限（dp）。 */
        const val DEFAULT_AUTO_FIT_MAX_WIDTH = 280

        /** 自适应宽度默认左右边距（dp）。 */
        const val DEFAULT_AUTO_FIT_PADDING = 8

        /** 编辑页按钮布局：0 自动换行（默认） / 1 左右滚动。 */
        const val EDIT_BUTTONS_WRAP = 0
        const val EDIT_BUTTONS_SCROLL = 1

        /** 桌面歌词背景不透明度默认值与范围（百分比），对应旧版 #CC000000。 */
        const val DEFAULT_DESKTOP_BACKGROUND_ALPHA = 80
        const val MIN_DESKTOP_BG_ALPHA = 0
        const val MAX_DESKTOP_BG_ALPHA = 100

        /**
         * 桌面歌词左右边缘羽化范围，按半个字计：1 = 左右各半个字，6 = 左右各三个字，默认 1。
         */
        const val DEFAULT_DESKTOP_EDGE_FADE_CHARS = 1
        const val MIN_DESKTOP_EDGE_FADE_CHARS = 1
        const val MAX_DESKTOP_EDGE_FADE_CHARS = 6

        /** 桌面歌词文字不透明度默认值与范围（百分比），默认完全不透明。 */
        const val DEFAULT_DESKTOP_TEXT_ALPHA = 100
        const val MIN_TEXT_ALPHA = 0
        const val MAX_TEXT_ALPHA = 100

        /** 已唱歌词默认颜色：白色。 */
        const val DEFAULT_SUNG_TEXT_COLOR = 0xFFFFFFFF.toInt()

        /** 逐字动画柔化强度：默认 16%，范围为无柔化到一个字宽。 */
        const val DEFAULT_WORD_SOFT_TRANSITION_STRENGTH = 16
        const val MIN_WORD_SOFT_TRANSITION_STRENGTH = 0
        const val MAX_WORD_SOFT_TRANSITION_STRENGTH = 100

        /** 自动匹配相似度默认阈值（百分比）。 */
        const val DEFAULT_MATCH_SIMILARITY = 55

        /** 匹配相似度允许的调节范围（百分比）：1% 即文件名只需对上标题中 1 个字即可匹配。 */
        const val MIN_MATCH_SIMILARITY = 1
        const val MAX_MATCH_SIMILARITY = 99

        /** 歌词空行显示模式：不处理 / ♬ / ~ / 伴奏 / 自定义文字。 */
        const val EMPTY_LINE_NONE = 0
        const val EMPTY_LINE_BEAT = 1
        const val EMPTY_LINE_TILDE = 2
        const val EMPTY_LINE_ACCOMPANIMENT = 3
        const val EMPTY_LINE_CUSTOM = 4

        /** 最近目录最多保留条数。 */
        const val MAX_RECENT = 20

        /** 最近打开文件最多保留条数：需覆盖更多歌曲的历史，供自动匹配"最近打开加分"使用。 */
        const val MAX_RECENT_FILES = 100

        /** 「搜索设置」页最近搜索最多保留条数：够回看最近几次查找，又不至于占满屏。 */
        const val MAX_RECENT_SEARCHES = 8

        /** 桌面歌词默认文字描边粗细（dp）。 */
        const val DEFAULT_OUTLINE_WIDTH = 2

        /** 描边粗细允许范围（dp）。 */
        const val MIN_OUTLINE_WIDTH = 1
        const val MAX_OUTLINE_WIDTH = 10

        /**
         * 文字描边位置：内部 / 居中 / 外部。
         * 默认「外部」，与旧版「先画 STROKE(2w) 再画 FILL」的效果完全一致。
         */
        const val OUTLINE_POSITION_INSIDE = 0
        const val OUTLINE_POSITION_CENTER = 1
        const val OUTLINE_POSITION_OUTSIDE = 2
        const val DEFAULT_OUTLINE_POSITION = OUTLINE_POSITION_OUTSIDE

        /**
         * 桌面歌词文字/描边预设色卡（ARGB int 数组），与旧版本保持一致的 6 种颜色。
         * 设置界面和桌面歌词面板共用此数组，保证两处色卡数量与顺序完全一致；
         * 其他颜色通过自定义 HSL 拖动条调节。
         */
        val DESKTOP_COLOR_PALETTE: IntArray = intArrayOf(
            0xFFFFFFFF.toInt(), // 白
            0xFFFFD54F.toInt(), // 黄
            0xFF81C784.toInt(), // 绿
            0xFF4DD0E1.toInt(), // 青
            0xFFEF5350.toInt(), // 红
            0xFFF48FB1.toInt()  // 粉
        )

        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LYRIC_ALIGNMENT = "lyric_alignment"
        private const val KEY_EDIT_FONT_SIZE = "edit_font_size"
        private const val KEY_NEW_LYRIC_FORMAT = "new_lyric_format"
        private const val KEY_WORD_BY_WORD = "word_by_word"
        private const val KEY_WORD_SOFT_TRANSITION = "word_soft_transition"
        private const val KEY_WORD_SOFT_TRANSITION_STRENGTH = "word_soft_transition_strength"
        private const val KEY_FORCE_WORD_BY_WORD = "force_word_by_word"
        private const val KEY_SHOW_UNTIMED_LINES = "show_untimed_lines"
        private const val KEY_EDIT_TIP_DONT_ASK = "edit_tip_dont_ask"
        private const val KEY_SCROLL_ANIM = "scroll_anim"
        private const val KEY_DESKTOP_LYRICS = "desktop_lyrics"
        private const val KEY_AUTO_START_DESKTOP = "auto_start_desktop_lyrics"
        private const val KEY_DESKTOP_PROCESS_GUARD = "desktop_process_guard"
        private const val KEY_DESKTOP_ALLOW_STATUS_BAR = "desktop_allow_status_bar"
        private const val KEY_NOTIFY_TOGGLE_DESKTOP = "notify_toggle_desktop_lyrics"
        private const val KEY_NOTIFY_TOGGLE_ON_START = "notify_toggle_on_start"
        private const val KEY_DEBUG_MODE = "debug_mode"
        private const val KEY_LOG_CAPTURE_ENABLED = "log_capture_enabled"
        private const val KEY_DESKTOP_FONT_SIZE = "desktop_font_size"
        private const val KEY_DESKTOP_BG = "desktop_bg"
        private const val KEY_DESKTOP_BG_ALPHA = "desktop_bg_alpha"
        private const val KEY_DESKTOP_EDGE_FADE = "desktop_edge_fade"
        private const val KEY_DESKTOP_EDGE_FADE_CHARS = "desktop_edge_fade_chars"
        /** 已废弃的像素键（数值语义改按半个字计，旧值不再沿用）。 */
        private const val KEY_DESKTOP_EDGE_FADE_PX = "desktop_edge_fade_px"
        private const val KEY_DESKTOP_WORD_BY_WORD = "desktop_word_by_word"
        private const val KEY_DESKTOP_FORCE_WORD_BY_WORD = "desktop_force_word_by_word"
        private const val KEY_SUNG_TEXT_COLOR = "sung_text_color"
        private const val KEY_MAIN_SUNG_TEXT_COLOR = "main_sung_text_color"
        private const val KEY_DESKTOP_SUNG_TEXT_COLOR = "desktop_sung_text_color"
        private const val KEY_DESKTOP_TEXT_COLOR = "desktop_text_color"
        private const val KEY_DESKTOP_OUTLINE_ENABLED = "desktop_outline_enabled"
        private const val KEY_DESKTOP_OUTLINE_COLOR = "desktop_outline_color"
        private const val KEY_DESKTOP_OUTLINE_WIDTH = "desktop_outline_width"
        private const val KEY_DESKTOP_OUTLINE_POSITION = "desktop_outline_position"
        private const val KEY_MAIN_TEXT_COLOR = "main_text_color"
        private const val KEY_MAIN_TEXT_ALPHA = "main_text_alpha"
        private const val KEY_MAIN_OUTLINE_ENABLED = "main_outline_enabled"
        private const val KEY_MAIN_OUTLINE_COLOR = "main_outline_color"
        private const val KEY_MAIN_OUTLINE_WIDTH = "main_outline_width"
        private const val KEY_MAIN_OUTLINE_POSITION = "main_outline_position"
        private const val KEY_QUICK_IMPORT_SELECTED = "quick_import_selected_dirs"
        private const val KEY_QUICK_IMPORT_FORMATS = "quick_import_formats"
        private const val KEY_QUICK_IMPORT_ANY_FORMAT = "quick_import_any_format"
        private const val KEY_DESKTOP_OFFSET_X = "desktop_offset_x"
        private const val KEY_DESKTOP_OFFSET_Y = "desktop_offset_y"
        private const val KEY_DESKTOP_WIDTH = "desktop_width"
        private const val KEY_DESKTOP_AUTO_FIT_WIDTH = "desktop_auto_fit_width"
        private const val KEY_DESKTOP_AUTO_FIT_MAX_WIDTH = "desktop_auto_fit_max_width"
        private const val KEY_DESKTOP_AUTO_FIT_PADDING = "desktop_auto_fit_padding"
        private const val KEY_MATCH_SIMILARITY = "match_similarity"
        private const val KEY_EMPTY_LINE_MODE = "empty_line_mode"
        private const val KEY_EMPTY_LINE_CUSTOM = "empty_line_custom"
        private const val KEY_DESKTOP_LINE_COUNT = "desktop_line_count"
        private const val KEY_DESKTOP_ALIGNMENT = "desktop_alignment"
        private const val KEY_DESKTOP_TRANSLATION = "desktop_translation"
        private const val KEY_BILINGUAL_HIGHLIGHT = "bilingual_highlight"
        private const val KEY_DESKTOP_BILINGUAL_LINE = "desktop_bilingual_line"
        private const val KEY_DUPLICATE_STRATEGY = "duplicate_strategy"
        private const val KEY_INTERNAL_COPY_CONFLICT_MODE = "internal_copy_conflict_mode"
        private const val KEY_TITLE_LIST_MODE = "title_list_mode"
        private const val KEY_SHOW_SEEK_TOAST = "show_seek_toast"
        private const val KEY_BG_URI = "bg_uri"
        private const val KEY_BG_ITEMS = "bg_items"
        private const val KEY_BG_CURRENT_ID = "bg_current_id"
        private const val KEY_BG_MIGRATED = "bg_legacy_migrated"
        private const val KEY_BG_GHOST_ALPHA = "bg_ghost_alpha"
        private const val KEY_BG_PREVIEW_LYRICS = "bg_preview_lyrics"
        private const val KEY_BG_PREVIEW_BORDER = "bg_preview_border"
        private const val KEY_REMEMBER_POSITION = "remember_position"
        private const val KEY_IMMERSIVE_BARS = "immersive_bars"
        private const val KEY_AUTO_MATCH_LYRICS = "auto_match_lyrics"
        private const val KEY_LAST_VERSION_CODE = "last_version_code"
        private const val KEY_NEEDS_NLS_REBIND = "needs_nls_rebind"
        private const val KEY_RECENT_DIRS = "recent_dirs"
        private const val KEY_RECENT_FILES = "recent_files"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val KEY_LAST_LYRIC_FILE = "last_lyric_file"
        private const val KEY_SEEK_REWIND_BIG = "seek_rewind_big_sec"
        private const val KEY_SEEK_REWIND_SMALL = "seek_rewind_small_sec"
        private const val KEY_SEEK_FORWARD_SMALL = "seek_forward_small_sec"
        private const val KEY_SEEK_FORWARD_BIG = "seek_forward_big_sec"
        private const val KEY_GLOBAL_OFFSET_OPTIONS = "global_offset_options"
        private const val KEY_TIMESTAMP_DECIMAL_DIGITS = "timestamp_decimal_digits"
        private const val KEY_DECIMAL_REMOVE_MODE = "decimal_remove_mode"
        private const val KEY_DECIMAL_ROUND_MODE = "decimal_round_mode"
        private const val KEY_SCROLL_CURSOR_ON_KEYBOARD = "scroll_cursor_on_keyboard"
        private const val KEY_SHOW_OPEN_KEYBOARD_BUTTON = "show_open_keyboard_button"
        private const val KEY_SHOW_ADD_BLANK_ABOVE = "show_add_blank_above"
        private const val KEY_SHOW_ADD_BLANK_BELOW = "show_add_blank_below"
        private const val KEY_SHOW_DELETE_ALL_BLANK = "show_delete_all_blank"
        private const val KEY_SHOW_DELETE_CURRENT_LINE = "show_delete_current_line"
        private const val KEY_EDIT_BUTTON_LAYOUT = "edit_button_layout"
        private const val KEY_DESKTOP_TEXT_ALPHA = "desktop_text_alpha"
        private const val KEY_GLOBAL_OFFSET_MS = "global_offset_ms"
        /** 按包名存布尔值：KEY_APP_MS_OFFSET_PREFIX + packageName */
        private const val KEY_APP_MS_OFFSET_PREFIX = "app_ms_offset_"
        /** 「偏移设置」已开启应用列表（JSON 数组） */
        private const val KEY_APP_MS_OFFSET_TRACKED = "app_ms_offset_tracked"
    }
}
