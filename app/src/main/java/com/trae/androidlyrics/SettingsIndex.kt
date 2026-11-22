package com.trae.androidlyrics

import android.app.Activity
import androidx.annotation.IdRes
import androidx.annotation.StringRes

/**
 * 设置项统一索引：「主页歌词设置」与「更多设置」共用同一份数据、同一个搜索页面。
 *
 * 设计要点：
 * 1. 每一条记录同时描述「显示名称」「所属页面路径」「目标界面」「定位锚点」。
 *    路径用字符串资源列表表示，二级页面自然呈现为「更多设置/应用外观」。
 * 2. 锚点是目标布局里真实存在的 View id，跳转后由 [ThemedActivity] 滚动到该控件并高亮。
 *    anchorId == 0 表示只打开目标页面（用于页面级入口）。
 * 3. 这里是纯静态数据，不持有 Context；文案一律走字符串资源，只有不展示的检索别名
 *    使用普通字符串。
 *
 * 新增设置项时，只需在此登记一条，两个页面的搜索结果会同时更新。
 */
object SettingsIndex {

    data class Entry(
        @param:StringRes val titleRes: Int,
        val pathRes: List<Int>,
        val target: Class<out Activity>,
        @param:IdRes val anchorId: Int = 0,
        @param:StringRes val qualifierRes: Int = 0,
        val keywords: String = "",
    )

    /** 已经解析成文本的结果项，供 RecyclerView 直接展示与过滤。 */
    data class Resolved(
        val entry: Entry,
        val title: String,
        val path: String,
        val haystack: String,
    )

    // ---------------------------------------------------------------- 目标页面
    private val C_MORE = SettingsActivity::class.java
    private val C_HOME = HomeLyricsSettingsActivity::class.java
    private val C_APPEARANCE = AppearanceActivity::class.java
    private val C_COLOR = LyricColorSettingsActivity::class.java
    private val C_BG = BackgroundManageActivity::class.java
    private val C_STORAGE = LyricStorageActivity::class.java
    private val C_DATA = DataManagerActivity::class.java
    private val C_LOG = LogViewActivity::class.java

    // ---------------------------------------------------------------- 页面路径
    private val P_MORE = listOf(R.string.action_settings)
    private val P_HOME = listOf(R.string.home_lyrics_settings_title)
    private val P_APPEARANCE = P_MORE + R.string.appearance_title
    private val P_COLOR = P_MORE + R.string.lyric_color_settings
    private val P_BG = P_MORE + R.string.background_manage_title
    private val P_STORAGE = P_MORE + R.string.storage_manage
    private val P_DATA = P_MORE + R.string.data_manage
    private val P_LOG = P_MORE + R.string.log_view_title

    private fun MutableList<Entry>.item(
        @StringRes titleRes: Int,
        path: List<Int>,
        target: Class<out Activity>,
        @IdRes anchorId: Int = 0,
        keywords: String = "",
        @StringRes qualifierRes: Int = 0,
    ) {
        add(Entry(titleRes, path, target, anchorId, qualifierRes, keywords))
    }

    val entries: List<Entry> = buildList {
        // ---------------------------------------------------------- 二级页面入口
        item(R.string.appearance_entry, P_MORE, C_APPEARANCE,
            keywords = "主题 颜色 配色 AMOLED 动态颜色")
        item(R.string.lyric_color_settings, P_MORE, C_COLOR,
            keywords = "文字颜色 已唱 描边 不透明度")
        item(R.string.background_manage_title, P_MORE, C_BG,
            keywords = "自定义背景图片 背景图库 添加图片")
        item(R.string.storage_manage, P_MORE, C_STORAGE,
            keywords = "歌词文件 导入 导出")
        item(R.string.data_manage, P_MORE, C_DATA,
            keywords = "应用数据 缓存 删除数据")
        item(R.string.log_file, P_MORE, C_LOG,
            keywords = "日志文件 导出 清除")

        // ---------------------------------------------------------- 更多设置：显示效果
        item(R.string.word_by_word, P_MORE, C_MORE, R.id.wordByWordSwitch,
            keywords = "逐字动画 卡拉OK 逐字")
        item(R.string.force_word_by_word, P_MORE, C_MORE, R.id.forceWordByWordSwitch,
            keywords = "强行兼容 逐字 推算")
        item(R.string.word_soft_transition, P_MORE, C_MORE, R.id.wordSoftTransitionSwitch,
            keywords = "柔化 已唱 蒙版 过渡")
        item(R.string.word_soft_transition_strength, P_MORE, C_MORE, R.id.wordSoftTransitionOptions,
            keywords = "柔化强度 滑块 恢复默认")
        item(R.string.show_untimed_lines, P_MORE, C_MORE, R.id.showUntimedLinesSwitch,
            keywords = "无时间戳 过渡台词 说明")
        item(R.string.seek_toast, P_MORE, C_MORE, R.id.seekToastSwitch,
            keywords = "点击歌词 跳转 时间提示")
        item(R.string.title_list_mode, P_MORE, C_MORE, R.id.titleListGroup,
            keywords = "标题栏 匹配到的歌词 最近打开")

        // ---------------------------------------------------------- 更多设置：背景
        item(R.string.ap_album_blur, P_MORE, C_MORE, R.id.albumBlurSwitch,
            keywords = "封面 专辑 模糊 背景")
        item(R.string.ap_no_album_art_custom_bg, P_MORE, C_MORE, R.id.noAlbumArtCustomBgSwitch,
            keywords = "无封面 回退 自定义背景图片")
        item(R.string.ap_custom_bg_blur, P_MORE, C_MORE, R.id.customBgBlurSwitch,
            keywords = "背景图片 模糊 自定义")
        item(R.string.pick_background, P_MORE, C_MORE, R.id.btnBackground,
            keywords = "背景图片 添加 选择")
        item(R.string.clear_background, P_MORE, C_MORE, R.id.btnClearBackground,
            keywords = "恢复默认背景 清除")

        // ---------------------------------------------------------- 更多设置：桌面歌词
        item(R.string.desktop_lyrics, P_MORE, C_MORE, R.id.desktopLyricsSwitch,
            keywords = "悬浮窗 启用 桌面歌词")
        item(R.string.auto_start_desktop_lyrics, P_MORE, C_MORE, R.id.autoStartDesktopSwitch,
            keywords = "开机 启动 自启")
        item(R.string.notify_toggle_desktop, P_MORE, C_MORE, R.id.notifyToggleDesktopSwitch,
            keywords = "通知栏 快捷开关 通知权限")
        item(R.string.notify_toggle_on_start, P_MORE, C_MORE, R.id.notifyToggleOnStartSwitch,
            keywords = "通知 启动 开关")
        item(R.string.desktop_word_by_word, P_MORE, C_MORE, R.id.desktopWordByWordSwitch,
            keywords = "桌面 逐字 时间戳")
        item(R.string.desktop_force_word_by_word, P_MORE, C_MORE, R.id.desktopForceWordByWordSwitch,
            keywords = "桌面 强行兼容 逐字")
        item(R.string.desktop_font_size, P_MORE, C_MORE, R.id.desktopFontSizeBar,
            keywords = "桌面 字号 字体大小")
        item(R.string.desktop_background, P_MORE, C_MORE, R.id.desktopBackgroundSwitch,
            keywords = "桌面 背景 显示")
        item(R.string.desktop_background_alpha, P_MORE, C_MORE, R.id.backgroundAlphaBar,
            keywords = "桌面 背景 透明度")
        item(R.string.desktop_edge_fade, P_MORE, C_MORE, R.id.desktopEdgeFadeSwitch,
            keywords = "桌面 边缘 渐隐 淡化 两端 开关")
        item(R.string.desktop_edge_fade_strength, P_MORE, C_MORE, R.id.desktopEdgeFadeBar,
            keywords = "桌面 渐隐范围 像素 滑块 恢复默认")
        item(R.string.desktop_width, P_MORE, C_MORE, R.id.desktopWidthRow,
            keywords = "桌面 宽度 微调 恢复默认")
        item(R.string.desktop_auto_fit_width, P_MORE, C_MORE, R.id.desktopAutoFitSwitch,
            keywords = "桌面 自适应 宽度")
        item(R.string.desktop_auto_fit_max_width, P_MORE, C_MORE, R.id.desktopAutoFitSwitch,
            keywords = "桌面 最大宽度 自适应")
        item(R.string.desktop_auto_fit_padding, P_MORE, C_MORE, R.id.desktopAutoFitSwitch,
            keywords = "桌面 左右边距 自适应")
        item(R.string.desktop_bilingual_line, P_MORE, C_MORE, R.id.desktopBilingualGroup,
            keywords = "桌面 双语 翻译 显示行")
        item(R.string.desktop_line_count, P_MORE, C_MORE, R.id.desktopLineCountGroup,
            keywords = "桌面 单行 双行 行数")
        item(R.string.desktop_alignment, P_MORE, C_MORE, R.id.desktopAlignGroup,
            keywords = "桌面 左对齐 居中 右对齐")
        item(R.string.desktop_allow_status_bar, P_MORE, C_MORE, R.id.desktopAllowStatusBarSwitch,
            keywords = "桌面 状态栏 划入")
        item(R.string.desktop_position_x, P_MORE, C_MORE, R.id.desktopOffsetXBar,
            keywords = "桌面 水平位置 横坐标 重置")
        item(R.string.desktop_position_y, P_MORE, C_MORE, R.id.desktopOffsetYBar,
            keywords = "桌面 垂直位置 纵坐标 重置")

        // ---------------------------------------------------------- 更多设置：权限 / 其他
        item(R.string.notif_access_manage, P_MORE, C_MORE, R.id.btnNotifAccess,
            keywords = "通知使用权 通知监听 授权")
        item(R.string.duplicate_strategy, P_MORE, C_MORE, R.id.duplicateGroup,
            keywords = "重名 覆盖 创建副本 每次询问")
        item(R.string.auto_match_lyrics, P_MORE, C_MORE, R.id.autoMatchSwitch,
            keywords = "自动匹配 歌词文件")
        item(R.string.view_match_rules, P_MORE, C_MORE, R.id.btnMatchRules,
            keywords = "匹配规则 相似度")
        item(R.string.match_similarity, P_MORE, C_MORE, R.id.matchSimilarityBar,
            keywords = "相似度 阈值 匹配")
        item(R.string.empty_line_display, P_MORE, C_MORE, R.id.emptyLineGroup,
            keywords = "空行 伴奏 自定义空行")
        item(R.string.immersive_show_bars, P_MORE, C_MORE, R.id.immersiveBarsSwitch,
            keywords = "沉浸模式 状态栏信息")
        item(R.string.reset_dialog_prompts, P_MORE, C_MORE, R.id.btnResetDialogPrompts,
            keywords = "不再询问 弹窗 重置")
        item(R.string.log_capture, P_MORE, C_MORE, R.id.logCaptureSwitch,
            keywords = "日志抓取 排查问题")

        // ---------------------------------------------------------- 主页歌词设置
        item(R.string.ap_display_mode, P_HOME, C_HOME, R.id.displayModeGroup,
            keywords = "滚动 逐词跃动 显示模式")
        item(R.string.ap_highlight_row, P_HOME, C_HOME, R.id.highlightRowBar,
            keywords = "高亮行 正在播放 位置")
        item(R.string.ap_allow_vertical_blank, P_HOME, C_HOME, R.id.allowVerticalBlankSwitch,
            keywords = "上下空白 留白 内边距")
        item(R.string.ap_section_home_align, P_HOME, C_HOME, R.id.homeAlignGroup,
            keywords = "左对齐 居中 右对齐 歌词对齐")
        item(R.string.ap_media_controls, P_HOME, C_HOME, R.id.mediaControlsSwitch,
            keywords = "底部播放控制条 进度 封面")
        item(R.string.ap_media_controls_bg, P_HOME, C_HOME, R.id.mediaControlsBgSwitch,
            keywords = "控制条 卡片底色 半透明")
        item(R.string.ap_media_controls_play_bg, P_HOME, C_HOME, R.id.mediaControlsPlayBgSwitch,
            keywords = "控制条 播放按钮 胶囊底色")
        item(R.string.ap_media_controls_soft_edge, P_HOME, C_HOME, R.id.mediaControlsSoftEdgeSwitch,
            keywords = "控制条 上边缘 柔化 过渡")
        item(R.string.ap_media_controls_expanded_pos, P_HOME, C_HOME, R.id.mediaExpandedPosGroup,
            keywords = "横屏 完全展开 上方 居中 下方")
        item(R.string.ap_keep_awake, P_HOME, C_HOME, R.id.keepAwakeSwitch,
            keywords = "保持亮屏 常亮")
        item(R.string.ap_original_size, P_HOME, C_HOME, R.id.homeFontSizeBar,
            keywords = "原文 字号 字体大小")
        item(R.string.ap_highlight_enlarge, P_HOME, C_HOME, R.id.highlightEnlargeSwitch,
            keywords = "高亮行 放大 字号")
        item(R.string.ap_highlight_enlarge_value, P_HOME, C_HOME, R.id.highlightEnlargeBar,
            keywords = "高亮行 放大字号 数值")
        item(R.string.ap_translation_size, P_HOME, C_HOME, R.id.homeTranslationSizeBar,
            keywords = "译文 翻译 字号")
        item(R.string.ap_font_weight, P_HOME, C_HOME, R.id.homeFontWeightBar,
            keywords = "字重 粗体 细体")
        item(R.string.ap_line_spacing, P_HOME, C_HOME, R.id.homeLineSpacingBar,
            keywords = "行间距 行距")
        item(R.string.ap_preview, P_HOME, C_HOME, R.id.previewOriginal,
            keywords = "文字预览 示例")
        item(R.string.scroll_animation, P_HOME, C_HOME, R.id.animGroup,
            keywords = "平滑 无动画 淡入")
        item(R.string.bilingual_highlight, P_HOME, C_HOME, R.id.bilingualGroup,
            keywords = "双语 高亮行 第一行 第二行")
        item(R.string.ap_reduce_motion, P_HOME, C_HOME, R.id.reduceMotionSwitch,
            keywords = "减少动效 动画 总开关")
        item(R.string.ap_eased_scroll, P_HOME, C_HOME, R.id.easedScrollSwitch,
            keywords = "缓动 平滑滚动 切行")
        item(R.string.ap_scroll_duration, P_HOME, C_HOME, R.id.scrollDurationBar,
            keywords = "切行时长 动画时长 ms")
        item(R.string.ap_stagger, P_HOME, C_HOME, R.id.staggerSwitch,
            keywords = "逐行错位 错落")
        item(R.string.ap_stagger_delay, P_HOME, C_HOME, R.id.staggerDelayBar,
            keywords = "错位延迟 ms")
        item(R.string.ap_scale, P_HOME, C_HOME, R.id.scaleSwitch,
            keywords = "行间缩放 缩小非当前行")
        item(R.string.ap_scale_percent, P_HOME, C_HOME, R.id.scalePercentBar,
            keywords = "缩放强度 百分比")
        item(R.string.ap_depth_blur, P_HOME, C_HOME, R.id.depthBlurSwitch,
            keywords = "远景模糊 淡化")
        item(R.string.ap_blur_percent, P_HOME, C_HOME, R.id.blurPercentBar,
            keywords = "模糊强度 百分比")

        // ---------------------------------------------------------- 更多设置/应用外观
        item(R.string.theme_section, P_APPEARANCE, C_APPEARANCE, R.id.themeGroup,
            keywords = "主题模式 深色 浅色 跟随系统")
        item(R.string.ap_amoled, P_APPEARANCE, C_APPEARANCE, R.id.amoledSwitch,
            keywords = "纯黑 纯白 AMOLED")
        item(R.string.ap_dynamic_color, P_APPEARANCE, C_APPEARANCE, R.id.dynamicColorSwitch,
            keywords = "壁纸取色 动态配色 Android 12")
        item(R.string.ap_color_preset, P_APPEARANCE, C_APPEARANCE, R.id.colorPresetRow,
            keywords = "配色方案 海洋 午夜 北欧 森林 日落 玫瑰 薰衣草 岩灰")
        item(R.string.ap_custom_color, P_APPEARANCE, C_APPEARANCE, R.id.btnCustomColor,
            keywords = "自定义颜色 HEX 十六进制 色相")

        // ---------------------------------------------------------- 更多设置/歌词颜色设置
        val mainLyric = R.string.main_lyric_section
        val desktop = R.string.desktop_section
        item(R.string.desktop_text_color, P_COLOR, C_COLOR, R.id.mainTextColorPicker,
            qualifierRes = mainLyric, keywords = "主界面 文字颜色")
        item(R.string.sung_text_color, P_COLOR, C_COLOR, R.id.mainSungColorPicker,
            qualifierRes = mainLyric, keywords = "主界面 已唱颜色")
        item(R.string.desktop_text_alpha, P_COLOR, C_COLOR, R.id.mainTextAlphaBar,
            qualifierRes = mainLyric, keywords = "主界面 不透明度")
        item(R.string.desktop_text_outline, P_COLOR, C_COLOR, R.id.mainOutlineSwitch,
            qualifierRes = mainLyric, keywords = "主界面 描边开关")
        item(R.string.desktop_outline_color, P_COLOR, C_COLOR, R.id.mainOutlineColorPicker,
            qualifierRes = mainLyric, keywords = "主界面 描边颜色")
        item(R.string.desktop_outline_width, P_COLOR, C_COLOR, R.id.mainOutlineWidthBar,
            qualifierRes = mainLyric, keywords = "主界面 描边粗细")
        item(R.string.outline_position, P_COLOR, C_COLOR, R.id.mainOutlinePositionGroup,
            qualifierRes = mainLyric, keywords = "主界面 内部 居中 外部")
        item(R.string.desktop_text_color, P_COLOR, C_COLOR, R.id.textColorPicker,
            qualifierRes = desktop, keywords = "桌面 文字颜色")
        item(R.string.sung_text_color, P_COLOR, C_COLOR, R.id.desktopSungColorPicker,
            qualifierRes = desktop, keywords = "桌面 已唱颜色")
        item(R.string.desktop_text_alpha, P_COLOR, C_COLOR, R.id.textAlphaBar,
            qualifierRes = desktop, keywords = "桌面 不透明度")
        item(R.string.desktop_text_outline, P_COLOR, C_COLOR, R.id.desktopOutlineSwitch,
            qualifierRes = desktop, keywords = "桌面 描边开关")
        item(R.string.desktop_outline_color, P_COLOR, C_COLOR, R.id.outlineColorPicker,
            qualifierRes = desktop, keywords = "桌面 描边颜色")
        item(R.string.desktop_outline_width, P_COLOR, C_COLOR, R.id.outlineWidthBar,
            qualifierRes = desktop, keywords = "桌面 描边粗细")
        item(R.string.outline_position, P_COLOR, C_COLOR, R.id.desktopOutlinePositionGroup,
            qualifierRes = desktop, keywords = "桌面 内部 居中 外部")

        // ---------------------------------------------------------- 更多设置/背景图片管理
        item(R.string.background_add, P_BG, C_BG, R.id.btnAdd, keywords = "添加图片 背景")
        item(R.string.background_apply, P_BG, C_BG, R.id.btnApply, keywords = "应用图片 生效")
        item(R.string.background_delete, P_BG, C_BG, R.id.btnDelete, keywords = "删除图片")
        item(R.string.background_clear_all, P_BG, C_BG, R.id.btnClearAll, keywords = "清空图片")
        item(R.string.background_edit_position, P_BG, C_BG, R.id.btnEditPosition,
            keywords = "显示位置 缩放 拖动")

        // ---------------------------------------------------------- 更多设置/歌词文件存储
        item(R.string.import_lyrics, P_STORAGE, C_STORAGE, R.id.btnImport, keywords = "导入歌词")
        item(R.string.export_lyrics, P_STORAGE, C_STORAGE, R.id.btnExport, keywords = "导出歌词")
        item(R.string.import_from_dir, P_STORAGE, C_STORAGE, R.id.btnImportFromDir,
            keywords = "目录导入 最近目录")
        item(R.string.storage_search_hint, P_STORAGE, C_STORAGE, R.id.searchLayout,
            keywords = "搜索 文件名 歌词内容")

        // ---------------------------------------------------------- 更多设置/程序数据管理
        item(R.string.data_up, P_DATA, C_DATA, R.id.btnUp, keywords = "返回上级 目录")

        // ---------------------------------------------------------- 更多设置/日志文件
        item(R.string.log_export, P_LOG, C_LOG, R.id.btnLogExport, keywords = "导出日志")
        item(R.string.log_copy, P_LOG, C_LOG, R.id.btnLogCopy, keywords = "复制日志 剪贴板")
        item(R.string.log_clear, P_LOG, C_LOG, R.id.btnLogClear, keywords = "清除日志")
    }

    /** 把资源 id 解析成可直接展示/检索的文本，只执行一次。 */
    fun resolveAll(resolve: (Int) -> String, pathSeparator: String = "/"): List<Resolved> =
        entries.map { e ->
            val qualifier = e.qualifierRes.takeIf { it != 0 }?.let(resolve)
            val rawTitle = resolve(e.titleRes)
            val title = if (qualifier != null) {
                resolve(R.string.settings_search_qualified).format(qualifier, rawTitle)
            } else rawTitle
            val path = e.pathRes.joinToString(pathSeparator) { resolve(it) }
            val haystack = buildString {
                append(title).append(' ')
                append(rawTitle).append(' ')
                append(path).append(' ')
                append(e.keywords)
            }.lowercase()
            Resolved(e, title, path, haystack)
        }

    /**
     * 多关键词按「全部命中」过滤（空格分隔），结果按名称匹配相关度排序。
     * 路径也参与匹配，因此输入「应用外观」即可列出该二级页面的全部设置。
     */
    fun filter(all: List<Resolved>, query: String): List<Resolved> {
        val tokens = query.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return all
        val first = tokens.first()
        return all.mapNotNull { r ->
            if (!tokens.all { r.haystack.contains(it) }) return@mapNotNull null
            val t = r.title.lowercase()
            val score = when {
                t == first -> 0
                t.startsWith(first) -> 1
                t.contains(first) -> 2
                else -> 3
            }
            score to r
        }.sortedBy { it.first }.map { it.second }
    }
}
