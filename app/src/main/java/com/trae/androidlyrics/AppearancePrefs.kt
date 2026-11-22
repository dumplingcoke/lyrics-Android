package com.trae.androidlyrics

import android.content.Context
import android.graphics.Color

/**
 * 「应用外观」页的全部偏好。
 *
 * 参考 lyrics-android 的 ThemePrefs / HomeLyricsPrefs / HomeMotionPrefs 拆成同一组可调项，
 * 用独立的 SharedPreferences 存放，避免把 20 多个键塞进原来的 lyrics_prefs。
 *
 * 所有取值都在读取时做范围收敛，用户导入旧配置或手动改文件也不会让界面崩掉。
 */
class AppearancePrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------ 主题与颜色

    /** 深色/浅色/跟随系统：沿用 [Prefs] 的常量与存储，外观页只负责展示与写入。 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME_MODE, Prefs.THEME_SYSTEM)
        set(value) = sp.edit().putInt(KEY_THEME_MODE, value).apply()

    /** AMOLED：深色纯黑、浅色纯白。 */
    var amoled: Boolean
        get() = sp.getBoolean(KEY_AMOLED, false)
        set(value) = sp.edit().putBoolean(KEY_AMOLED, value).apply()

    /** 动态颜色：Android 12+ 使用系统壁纸取色。 */
    var dynamicColor: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) = sp.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    /** 配色预设名，见 [COLOR_PRESETS]；"custom" 表示使用 [accentColor]。 */
    var colorPreset: String
        get() = sp.getString(KEY_COLOR_PRESET, PRESET_OCEAN) ?: PRESET_OCEAN
        set(value) = sp.edit().putString(KEY_COLOR_PRESET, value).apply()

    /** 自定义主调色（ARGB），仅在 colorPreset == "custom" 时使用。 */
    var accentColor: Int
        get() = sp.getInt(KEY_ACCENT_COLOR, Color.parseColor("#4C6FFF"))
        set(value) = sp.edit().putInt(KEY_ACCENT_COLOR, value).apply()

    /** 减少动效：关闭页面切换与面板动画、歌词错落动画。 */
    var reduceMotion: Boolean
        get() = sp.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = sp.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    /** 当前生效的主调色（动态颜色开启时由系统在运行时覆盖，这里仅作兜底）。 */
    val effectiveAccent: Int
        get() = if (colorPreset == PRESET_CUSTOM) accentColor
        else COLOR_PRESETS[colorPreset] ?: COLOR_PRESETS.getValue(PRESET_OCEAN)

    // ------------------------------------------------------------ 主页歌词

    /** 主页歌词显示模式：滚动 / 逐词跃动。 */
    var homeDisplayMode: String
        get() = sp.getString(KEY_HOME_DISPLAY_MODE, MODE_SCROLL).let {
            if (it == MODE_POP) MODE_POP else MODE_SCROLL
        }
        set(value) = sp.edit().putString(KEY_HOME_DISPLAY_MODE, value).apply()

    /** 使用当前播放的专辑封面作为主页模糊背景。 */
    var albumBlurBackground: Boolean
        get() = sp.getBoolean(KEY_ALBUM_BLUR_BG, false)
        set(value) = sp.edit().putBoolean(KEY_ALBUM_BLUR_BG, value).apply()

    /** 播放时保持亮屏。 */
    var keepAwake: Boolean
        get() = sp.getBoolean(KEY_KEEP_AWAKE, false)
        set(value) = sp.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /** 主页歌词对齐：0 左 / 1 中 / 2 右（与 [Prefs.ALIGN_LEFT] 等一致）。 */
    var homeAlign: Int
        get() = sp.getInt(KEY_HOME_ALIGN, Prefs.ALIGN_CENTER).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_HOME_ALIGN, value.coerceIn(0, 2)).apply()

    /** 原文歌词字号 sp（16~48）。 */
    var homeFontSize: Int
        get() = sp.getInt(KEY_HOME_FONT_SIZE, 22).coerceIn(16, 48)
        set(value) = sp.edit().putInt(KEY_HOME_FONT_SIZE, value.coerceIn(16, 48)).apply()

    /** 高亮行放大开关：开启后当前行比其它行大；原为硬编码 +6sp，现可配置。 */
    var highlightEnlarge: Boolean
        get() = sp.getBoolean(KEY_HIGHLIGHT_ENLARGE, true)
        set(value) = sp.edit().putBoolean(KEY_HIGHLIGHT_ENLARGE, value).apply()

    /** 高亮行相对原文字号放大的 sp 数值（0~20，默认 6 保持原有观感）。 */
    var highlightEnlargeSp: Int
        get() = sp.getInt(KEY_HIGHLIGHT_ENLARGE_SP, DEFAULT_HIGHLIGHT_ENLARGE_SP)
            .coerceIn(0, MAX_HIGHLIGHT_ENLARGE_SP)
        set(value) = sp.edit().putInt(
            KEY_HIGHLIGHT_ENLARGE_SP, value.coerceIn(0, MAX_HIGHLIGHT_ENLARGE_SP)
        ).apply()

    /** 译文歌词字号 sp（12~32）。 */
    var homeTranslationSize: Int
        get() = sp.getInt(KEY_HOME_TRANSLATION_SIZE, 14).coerceIn(12, 32)
        set(value) = sp.edit().putInt(KEY_HOME_TRANSLATION_SIZE, value.coerceIn(12, 32)).apply()

    /** 歌词字重（300~800，按 100 取整）。 */
    var homeFontWeight: Int
        get() = (sp.getInt(KEY_HOME_FONT_WEIGHT, 500) / 100 * 100).coerceIn(300, 800)
        set(value) = sp.edit().putInt(KEY_HOME_FONT_WEIGHT, (value / 100 * 100).coerceIn(300, 800)).apply()

    /** 歌词行间距 dp（0~28，按 2 取整）。 */
    var homeLineSpacing: Int
        get() = (sp.getInt(KEY_HOME_LINE_SPACING, 8) / 2 * 2).coerceIn(0, 28)
        set(value) = sp.edit().putInt(KEY_HOME_LINE_SPACING, (value / 2 * 2).coerceIn(0, 28)).apply()

    /** 显示底部媒体控制条（上一首/播放暂停/下一首 + 进度）。 */
    var showMediaControls: Boolean
        get() = sp.getBoolean(KEY_SHOW_MEDIA_CONTROLS, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_MEDIA_CONTROLS, value).apply()

    /**
     * 「底部播放控制条背景」：开启＝显示半透明卡片底色；关闭（默认）＝卡片透明，
     * 控制条与主页背景融为一体，与 lyrics-android 的 HomePlayer 一致。
     *
     * 原「关闭底部播放控制条背景」（语义为隐藏底色、默认关闭＝保留底色）已改为
     * 正向命名，默认值随之调整为 false；旧键不再读取，避免新旧语义互相打架。
     */
    var mediaControlsBackground: Boolean
        get() = sp.getBoolean(KEY_MEDIA_CONTROLS_BG, false)
        set(value) = sp.edit().putBoolean(KEY_MEDIA_CONTROLS_BG, value).apply()

    /** 「底部播放控制条暂停按钮背景」：开启＝播放/暂停键带胶囊底色；默认关闭＝仅图标。 */
    var mediaControlsPlayBackground: Boolean
        get() = sp.getBoolean(KEY_MEDIA_CONTROLS_PLAY_BG, false)
        set(value) = sp.edit().putBoolean(KEY_MEDIA_CONTROLS_PLAY_BG, value).apply()

    /** 「底部播放控制条上边缘柔化」：上边缘与主页背景做渐变过渡，默认开启。 */
    var mediaControlsSoftTopEdge: Boolean
        get() = sp.getBoolean(KEY_MEDIA_CONTROLS_SOFT_EDGE, true)
        set(value) = sp.edit().putBoolean(KEY_MEDIA_CONTROLS_SOFT_EDGE, value).apply()

    /** 底部播放控制条展开状态：重启应用 / 旋屏后按此值恢复，默认收起。 */
    var mediaControlsExpanded: Boolean
        get() = sp.getBoolean(KEY_MEDIA_CONTROLS_EXPANDED, false)
        set(value) = sp.edit().putBoolean(KEY_MEDIA_CONTROLS_EXPANDED, value).apply()

    /**
     * 横屏时控制条在底边的水平位置，0＝最左、1＝最右，默认靠右（与原项目一致）。
     * 长按拖动后按松手位置持久化，下次进入横屏直接恢复。
     */
    var mediaControlsDockFraction: Float
        get() = sp.getFloat(KEY_MEDIA_CONTROLS_DOCK_FRACTION, 1f).coerceIn(0f, 1f)
        set(value) = sp.edit().putFloat(KEY_MEDIA_CONTROLS_DOCK_FRACTION, value.coerceIn(0f, 1f)).apply()

    /** 横屏收起时是否只显示圆形封面（原项目 HOME_PLAYER_COVER），默认开启。 */
    var mediaControlsLandscapeCoverOnly: Boolean
        get() = sp.getBoolean(KEY_MEDIA_CONTROLS_LANDSCAPE_COVER, true)
        set(value) = sp.edit().putBoolean(KEY_MEDIA_CONTROLS_LANDSCAPE_COVER, value).apply()

    /**
     * 横屏完全展开时控制条的位置：
     * [EXPANDED_POS_TOP] 上方 / [EXPANDED_POS_CENTER] 居中（默认）/ [EXPANDED_POS_BOTTOM] 下方。
     *
     * 原项目横屏展开后，内容在接近全屏高度的 Surface 里垂直居中；这里保持
     * 「完全展开即居中」为默认，并把该位置开放成主页歌词设置里的分段控件。
     */
    var mediaControlsExpandedPosition: Int
        get() = sp.getInt(KEY_MEDIA_CONTROLS_EXPANDED_POS, EXPANDED_POS_CENTER)
            .let { if (it in EXPANDED_POS_TOP..EXPANDED_POS_BOTTOM) it else EXPANDED_POS_CENTER }
        set(value) = sp.edit().putInt(
            KEY_MEDIA_CONTROLS_EXPANDED_POS,
            value.coerceIn(EXPANDED_POS_TOP, EXPANDED_POS_BOTTOM)
        ).apply()

    /**
     * 正在播放的高亮行相对「列表垂直居中」的偏移行数。
     * 负值＝向上、正值＝向下；默认 -1，即比原先的居中默认位置向上高一行。
     */
    var highlightRowOffset: Int
        get() = sp.getInt(KEY_HIGHLIGHT_ROW_OFFSET, DEFAULT_HIGHLIGHT_ROW_OFFSET).coerceIn(-6, 6)
        set(value) = sp.edit().putInt(KEY_HIGHLIGHT_ROW_OFFSET, value.coerceIn(-6, 6)).apply()

    /**
     * 允许歌词列表上下留白：开启后顶部/底部按高亮行位置补齐内边距，
     * 使第一行歌词能从高亮行位置开始、最后一行歌词能在高亮行位置结束。
     */
    var allowVerticalBlank: Boolean
        get() = sp.getBoolean(KEY_ALLOW_VERTICAL_BLANK, true)
        set(value) = sp.edit().putBoolean(KEY_ALLOW_VERTICAL_BLANK, value).apply()

    /** 自定义背景图片模糊：开启后把用户添加的背景图做模糊处理再铺到主页。 */
    var customBackgroundBlur: Boolean
        get() = sp.getBoolean(KEY_CUSTOM_BG_BLUR, false)
        set(value) = sp.edit().putBoolean(KEY_CUSTOM_BG_BLUR, value).apply()

    /** 无专辑封面时回退到自定义背景图片，默认开启。 */
    var useCustomBgWhenNoAlbumArt: Boolean
        get() = sp.getBoolean(KEY_CUSTOM_BG_WHEN_NO_ALBUM_ART, true)
        set(value) = sp.edit().putBoolean(KEY_CUSTOM_BG_WHEN_NO_ALBUM_ART, value).apply()

    /**
     * 「无专辑封面时使用自定义背景图片」当前是否处于可生效状态：
     * 只有在「封面模糊背景」开启时才有意义。为 true 且当前无专辑封面时，
     * 主页会把「封面模糊背景」临时视为关闭，改走常态自定义背景图路径。
     * 封面模糊背景关闭时自定义背景图片本来就常态显示，此项无需参与。
     */
    val useCustomBgWhenNoArtEffective: Boolean
        get() = albumBlurBackground && useCustomBgWhenNoAlbumArt

    // ------------------------------------------------------------ 主页歌词动效

    /** 缓动滚动：切行时平滑滚动到中央。 */
    var easedScroll: Boolean
        get() = sp.getBoolean(KEY_EASED_SCROLL, true)
        set(value) = sp.edit().putBoolean(KEY_EASED_SCROLL, value).apply()

    var scrollDurationMs: Int
        get() = sp.getInt(KEY_SCROLL_DURATION, 800).coerceIn(300, 1500)
        set(value) = sp.edit().putInt(KEY_SCROLL_DURATION, value.coerceIn(300, 1500)).apply()

    /** 逐行错位：相邻歌词依次跟随当前行移动。 */
    var stagger: Boolean
        get() = sp.getBoolean(KEY_STAGGER, true)
        set(value) = sp.edit().putBoolean(KEY_STAGGER, value).apply()

    var staggerDelayMs: Int
        get() = sp.getInt(KEY_STAGGER_DELAY, 20).coerceIn(0, 120)
        set(value) = sp.edit().putInt(KEY_STAGGER_DELAY, value.coerceIn(0, 120)).apply()

    /** 行间缩放：缩小非当前行。 */
    var scale: Boolean
        get() = sp.getBoolean(KEY_SCALE, true)
        set(value) = sp.edit().putBoolean(KEY_SCALE, value).apply()

    var scalePercent: Int
        get() = sp.getInt(KEY_SCALE_PERCENT, 75).coerceIn(0, 200)
        set(value) = sp.edit().putInt(KEY_SCALE_PERCENT, value.coerceIn(0, 200)).apply()

    /** 远景模糊：以透明度模拟远离当前行的虚化层次。 */
    var depthBlur: Boolean
        get() = sp.getBoolean(KEY_DEPTH_BLUR, true)
        set(value) = sp.edit().putBoolean(KEY_DEPTH_BLUR, value).apply()

    var blurPercent: Int
        get() = sp.getInt(KEY_BLUR_PERCENT, 45).coerceIn(0, 200)
        set(value) = sp.edit().putInt(KEY_BLUR_PERCENT, value.coerceIn(0, 200)).apply()

    /** 动效是否整体启用：减少动效时所有主页动效一律关闭。 */
    val motionEnabled: Boolean get() = !reduceMotion

    // ------------------------------------------------------------ 监听状态页

    /** 监听状态排列方式：列表 / 左右滑动。默认列表。 */
    var listenLayout: String
        get() = sp.getString(KEY_LISTEN_LAYOUT, LAYOUT_LIST).let {
            if (it == LAYOUT_SWIPE) LAYOUT_SWIPE else LAYOUT_LIST
        }
        set(value) = sp.edit().putString(KEY_LISTEN_LAYOUT, value).apply()

    /** 会话卡片独立渲染的歌词行数（1~9，默认 3：上一句 / 正在播放 / 下一句）。 */
    var listenLyricLines: Int
        get() = sp.getInt(KEY_LISTEN_LYRIC_LINES, DEFAULT_LISTEN_LYRIC_LINES).coerceIn(1, 9)
        set(value) = sp.edit().putInt(KEY_LISTEN_LYRIC_LINES, value.coerceIn(1, 9)).apply()

    // ------------------------------------------------------------ 在线歌词源

    /** 已启用的在线歌词源 id 集合；空集合表示全部启用。 */
    var enabledSources: Set<String>
        get() = sp.getStringSet(KEY_ENABLED_SOURCES, null)?.toSet() ?: OnlineLyrics.ALL_SOURCES
        set(value) = sp.edit().putStringSet(KEY_ENABLED_SOURCES, value.toSet()).apply()

    /** Apple Music 逐字歌词所需的可选 media-user-token。 */
    var appleMusicToken: String
        get() = sp.getString(KEY_APPLE_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_APPLE_TOKEN, value).apply()

    /**
     * 无匹配歌词时自动执行一次在线搜索（默认关闭）。
     * 每首歌只请求一次，结果写入独立的 `online_lyrics/` 缓存目录；之后切回该歌曲
     * 直接读本地文件，不再向任何在线歌词接口发起请求。
     */
    var autoFetchOnNoMatch: Boolean
        get() = sp.getBoolean(KEY_AUTO_FETCH_ON_NO_MATCH, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_FETCH_ON_NO_MATCH, value).apply()

    /** 自动在线获取时优先选用带逐字数据的歌词版本（默认开启）。 */
    var preferWordTimedOnline: Boolean
        get() = sp.getBoolean(KEY_PREFER_WORD_TIMED_ONLINE, true)
        set(value) = sp.edit().putBoolean(KEY_PREFER_WORD_TIMED_ONLINE, value).apply()

    // ------------------------------------------------------------ 主题外观应用

    /**
     * 在 Activity 的 setContentView 之前调用：把配色预设覆盖到当前 theme。
     * 自定义主调色无法用静态 style 表达，因此改用运行时对窗口与关键控件着色，
     * 由 [applyRuntimeTint] 完成；这里只处理预设。
     */
    fun applyThemeOverlay(theme: android.content.res.Resources.Theme) {
        if (dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // 系统动态颜色由 DynamicColors 在 Application 层统一处理，此处不叠加预设
            return
        }
        val style = PRESET_STYLES[colorPreset]
        if (style != null) theme.applyStyle(style, true)
    }

    companion object {
        const val PREFS_NAME = "appearance_prefs_v1"

        const val MODE_SCROLL = "scroll"
        const val MODE_POP = "pop"
        const val LAYOUT_LIST = "list"
        const val LAYOUT_SWIPE = "swipe"
        const val DEFAULT_LISTEN_LYRIC_LINES = 3

        /** 横屏完全展开时控制条的位置：上方 / 居中（默认）/ 下方。 */
        const val EXPANDED_POS_TOP = 0
        const val EXPANDED_POS_CENTER = 1
        const val EXPANDED_POS_BOTTOM = 2

        /** 高亮行默认比居中位置向上 1 行（用户要求的新默认值）。 */
        const val DEFAULT_HIGHLIGHT_ROW_OFFSET = -1
        /** 高亮行位置滑块的可调范围（行数，正负对称）。 */
        const val HIGHLIGHT_ROW_OFFSET_MIN = -6
        const val HIGHLIGHT_ROW_OFFSET_MAX = 6
        /** 高亮行放大：默认值与滑块上限（sp）。 */
        const val DEFAULT_HIGHLIGHT_ENLARGE_SP = 6
        const val MAX_HIGHLIGHT_ENLARGE_SP = 20

        const val PRESET_OCEAN = "OCEAN"
        const val PRESET_MIDNIGHT = "MIDNIGHT"
        const val PRESET_NORDIC = "NORDIC"
        const val PRESET_FOREST = "FOREST"
        const val PRESET_SUNSET = "SUNSET"
        const val PRESET_ROSE = "ROSE"
        const val PRESET_LAVENDER = "LAVENDER"
        const val PRESET_SLATE = "SLATE"
        const val PRESET_CUSTOM = "custom"

        /** 参考 lyrics-android 的 8 套预设，取各家主色。 */
        val COLOR_PRESETS = linkedMapOf(
            PRESET_OCEAN to Color.parseColor("#4C6FFF"),
            PRESET_MIDNIGHT to Color.parseColor("#3F51B5"),
            PRESET_NORDIC to Color.parseColor("#5B8AA8"),
            PRESET_FOREST to Color.parseColor("#2E7D5B"),
            PRESET_SUNSET to Color.parseColor("#E8663D"),
            PRESET_ROSE to Color.parseColor("#D8527B"),
            PRESET_LAVENDER to Color.parseColor("#8A63D2"),
            PRESET_SLATE to Color.parseColor("#5A6472"),
        )

        val PRESET_LABELS = linkedMapOf(
            PRESET_OCEAN to "海洋",
            PRESET_MIDNIGHT to "午夜",
            PRESET_NORDIC to "北欧",
            PRESET_FOREST to "森林",
            PRESET_SUNSET to "日落",
            PRESET_ROSE to "玫瑰",
            PRESET_LAVENDER to "薰衣草",
            PRESET_SLATE to "岩灰",
        )

        /** 每套预设对应的 theme overlay（覆盖 colorPrimary 等，见 themes.xml）。 */
        val PRESET_STYLES = mapOf(
            PRESET_OCEAN to R.style.ColorOverlay_Ocean,
            PRESET_MIDNIGHT to R.style.ColorOverlay_Midnight,
            PRESET_NORDIC to R.style.ColorOverlay_Nordic,
            PRESET_FOREST to R.style.ColorOverlay_Forest,
            PRESET_SUNSET to R.style.ColorOverlay_Sunset,
            PRESET_ROSE to R.style.ColorOverlay_Rose,
            PRESET_LAVENDER to R.style.ColorOverlay_Lavender,
            PRESET_SLATE to R.style.ColorOverlay_Slate,
        )

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_AMOLED = "amoled"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_COLOR_PRESET = "color_preset"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_HOME_DISPLAY_MODE = "home_display_mode"
        private const val KEY_ALBUM_BLUR_BG = "album_blur_bg"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_HOME_ALIGN = "home_align"
        private const val KEY_HOME_FONT_SIZE = "home_font_size"
        private const val KEY_HIGHLIGHT_ENLARGE = "highlight_enlarge"
        private const val KEY_HIGHLIGHT_ENLARGE_SP = "highlight_enlarge_sp"
        private const val KEY_HOME_TRANSLATION_SIZE = "home_translation_size"
        private const val KEY_HOME_FONT_WEIGHT = "home_font_weight"
        private const val KEY_HOME_LINE_SPACING = "home_line_spacing"
        private const val KEY_SHOW_MEDIA_CONTROLS = "show_media_controls"
        // 旧键 hide_media_controls_background 已弃用：语义与新「底部播放控制条背景」相反。
        private const val KEY_MEDIA_CONTROLS_BG = "media_controls_background"
        private const val KEY_MEDIA_CONTROLS_PLAY_BG = "media_controls_play_background"
        private const val KEY_MEDIA_CONTROLS_SOFT_EDGE = "media_controls_soft_top_edge"
        private const val KEY_MEDIA_CONTROLS_EXPANDED = "media_controls_expanded"
        private const val KEY_MEDIA_CONTROLS_DOCK_FRACTION = "media_controls_dock_fraction"
        private const val KEY_MEDIA_CONTROLS_LANDSCAPE_COVER = "media_controls_landscape_cover"
        private const val KEY_MEDIA_CONTROLS_EXPANDED_POS = "media_controls_expanded_position"
        private const val KEY_HIGHLIGHT_ROW_OFFSET = "highlight_row_offset"
        private const val KEY_ALLOW_VERTICAL_BLANK = "allow_vertical_blank"
        private const val KEY_CUSTOM_BG_BLUR = "custom_background_blur"
        private const val KEY_CUSTOM_BG_WHEN_NO_ALBUM_ART = "custom_bg_when_no_album_art"
        private const val KEY_EASED_SCROLL = "eased_scroll"
        private const val KEY_SCROLL_DURATION = "scroll_duration_ms"
        private const val KEY_STAGGER = "stagger"
        private const val KEY_STAGGER_DELAY = "stagger_delay_ms"
        private const val KEY_SCALE = "scale"
        private const val KEY_SCALE_PERCENT = "scale_percent"
        private const val KEY_DEPTH_BLUR = "depth_blur"
        private const val KEY_BLUR_PERCENT = "blur_percent"
        private const val KEY_LISTEN_LAYOUT = "listen_layout"
        private const val KEY_LISTEN_LYRIC_LINES = "listen_lyric_lines"
        private const val KEY_ENABLED_SOURCES = "enabled_sources"
        private const val KEY_APPLE_TOKEN = "apple_music_token"
        private const val KEY_AUTO_FETCH_ON_NO_MATCH = "auto_fetch_on_no_match"
        private const val KEY_PREFER_WORD_TIMED_ONLINE = "prefer_word_timed_online"
    }
}
