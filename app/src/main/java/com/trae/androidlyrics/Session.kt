package com.trae.androidlyrics

import android.net.Uri
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 跨配置变更（主题切换、旋转）与跨组件（桌面歌词服务）共享的内存会话。
 *
 * 把大文本与解析后的歌词行放在内存对象里，避免放进 savedInstanceState
 * 导致 TransactionTooLargeException；桌面歌词服务也通过它读取当前歌词。
 */
object Session {
    var uri: Uri? = null
    var name: String? = null
    /** 应用内部歌词副本路径（打开外部文件时也会生成），用于保存时优先写回内部目录。 */
    var internalPath: String? = null
    var text: String? = null
    var charset: Charset = StandardCharsets.UTF_8
    var bom: ByteArray = ByteArray(0)
    var dirty = false
    var hasDocument = false

    /** 新建歌词：仅进入编辑状态、尚未落盘时记住弹窗里确定的文件名。 */
    var pendingNewLyricName: String? = null

    /** 解析后的歌词行列表，供主界面与桌面歌词服务共用。 */
    var lyricLines: List<LyricLine>? = null

    /**
     * 解析选项版本号：改变「显示不带时间戳的行」等影响解析结果的设置时递增，
     * 供主界面在返回前台时按新设置重新解析当前文档。
     */
    @Volatile var parseVersion: Int = 0

    /** 自动匹配：当前处理过的歌曲标识（标题|作者），未匹配到文件时也标记以避免重复扫描。 */
    @Volatile var matchedKey: String? = null

    /** 自动匹配到的歌词文件（应用内部存储），供主界面同步打开；未匹配到时为 null。 */
    @Volatile var matchedFile: File? = null

    /**
     * 自动匹配的当前状态。主界面歌词区与桌面歌词在「没有歌词行可显示」时据此
     * 选择占位文案：正在匹配 →「正在匹配歌词」，无匹配且正在在线获取 →
     * 「无匹配歌词，正在在线获取歌词」，匹配结束仍无结果 →「无匹配歌词」，
     * 其余情况沿用原来的「暂无歌词」。
     *
     * 同一进程内共享（主界面 / 通知监听服务 / 桌面歌词服务均在默认进程）。
     */
    @Volatile var matchState: LyricMatchState = LyricMatchState.IDLE

    /** 匹配结果版本号：同一歌曲更换匹配文件时递增，供主界面检测并刷新。 */
    @Volatile var matchVersion: Int = 0

    /**
     * 用户显式选中的歌词（主界面标题栏列表 / 文件选择器 / 桌面面板候选列表）。
     *
     * 自动匹配只按「标题|作者」精确 key 记住绑定，而播放器的作者字段常常晚一步才上报，
     * 或活跃媒体会话在多个来源间抖动；一旦 key 变化就查不到绑定，重新按文件名相似度
     * 匹配时会回到用户刚刚替换掉的旧文件（表现为「选了新文件过一会又变回去」）。
     * 这里额外按标题记住这次显式选择：标题不变期间自动匹配必须继续使用它。
     *
     * 用绝对路径而不是 Activity 字段，界面重建（主题切换 / 旋转）后仍然有效；
     * 跨进程的持久化由 LyricBindings 的强制绑定负责，这里只兜 key 抖动的场景。
     */
    @Volatile var explicitLyricTitle: String? = null
    @Volatile var explicitLyricPath: String? = null

    /**
     * 歌曲切换版本号：每次检测到歌曲标题变化（含列表循环同一首歌重放）时递增。
     * 供桌面歌词服务检测"切歌"事件并强制重置行号、打断长歌词滚动动画。
     * 列表循环同一首歌时，tryAutoMatch() 因 key==matchedKey 提前返回、
     * Session.lyricLines 引用不变，此时仅靠 lines !== lastLines 检测不到切歌，
     * 必须依赖此版本号。
     */
    @Volatile var songChangeVersion: Int = 0

    /**
     * 「在线搜索歌词」选用后写下的待打开歌词文件路径。
     * 主界面在 onResume 时检查并加载，然后清空，避免跨组件传大文本。
     */
    @Volatile var pendingOnlineLyricPath: String? = null
}

/**
 * 自动匹配状态：
 * - [IDLE]：尚未开始匹配（手动打开、清空文档、关闭自动匹配等），显示原有「暂无歌词」；
 * - [MATCHING]：检测到新歌曲，正在防抖 / 扫描歌词文件，显示「正在匹配歌词」；
 * - [NO_MATCH]：匹配结束但没有匹配的歌词文件，显示「无匹配歌词」；
 * - [FETCHING_ONLINE]：本地无匹配且已开启「无匹配时自动在线搜索一次」，
 *   正在请求在线歌词源，显示「无匹配歌词，正在在线获取歌词」；
 * - [MATCHED]：已匹配到歌词文件。
 */
enum class LyricMatchState { IDLE, MATCHING, NO_MATCH, FETCHING_ONLINE, MATCHED }
