package com.trae.androidlyrics

import android.content.Context

/**
 * 「当前应用的毫秒信息不可信」按应用开关与「整体偏移」的统一计算入口。
 *
 * - 按当前播放应用包名记忆开关：默认关闭、Telegram 系列默认开启；
 * - 开关开启表示该播放器上报的毫秒进度不可信：歌词时间只由应用内墙钟
 *   计时推进，仅在外部切歌读取起点 / 换源 / 调整进度时读播放器的整秒值
 *   重设基准；暂停只冻结当前内部进度（冻结值恢复整秒，对齐这类播放器
 *   量化到整秒的暂停点），恢复后从冻结值续走。状态切换被检测到的时刻
 *   与真实切换时刻不符而产生的相位差，由「恢复后一次性重锚」按快照自带
 *   时刻外推归位，写进基准的值一律落在整秒刻度；
 * - 开关开启时所有整秒对表都做**双边对齐**：应用内计时锚到整秒刻度的同时，
 *   播放器也 seekTo 到同一刻度，双方从同一已知原点起播。适用于桌面歌词
 *   「一键对齐」、暂停→恢复、切歌读取起点、外部事件（换源 / 播放器内跳转），
 *   以及点击歌词行 / 时间戳 / 快退快进等 app 内跳转（这类跳转仍走自己的
 *   seekTo 送达校验链，只是指令目标取同一个整秒值，且**不登记落地守卫**）；
 *   允许进度回退到整秒，但不允许歌曲与歌词进度错开。其它应用仍只重锚歌词、
 *   不发额外的跳转指令（见 MusicNotificationListenerService.applyBothSidesAlign）；
 * - **开关不参与跳转目标选取、延迟量与高亮对齐**：点哪行就跳哪行的原始
 *   时间戳，不做 offset 折算；只有双边对齐时发给播放器的指令与内部计时
 *   基准取整秒，显示进度的毫秒零头由 seek catchup 冻结补偿。
 *   歌词文件的 [offset:ms] 元数据（[LyricsParser.lastOffsetMs]）只作解析
 *   信息记录，不当作延迟量；
 * - 切歌读取起点后的「整首歌纯应用内计时」对所有应用生效，不再由开关控制；
 * - 「整体偏移」是用户在「偏移设置」里设定的全局延迟，对所有应用生效。
 *
 * 播放进度统一按 `当前进度 - 整体偏移` 计算：正值延后歌词、负值提前。
 */
object PlaybackOffset {

    const val MIN_GLOBAL_OFFSET_MS = -3000
    const val MAX_GLOBAL_OFFSET_MS = 3000

    /** 整体偏移滑块的取值区间长度，滑块进度 0 对应 -3000ms。 */
    const val GLOBAL_OFFSET_RANGE = MAX_GLOBAL_OFFSET_MS - MIN_GLOBAL_OFFSET_MS

    /** 当前正在播放的应用包名（由通知监听服务在选定会话时写入）。 */
    fun activePackage(): String? = MusicProgress.packageName?.takeIf { it.isNotBlank() }

    /** Telegram 官方客户端 / Telegram X / 常见分支包名。 */
    fun isTelegram(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val p = pkg.lowercase()
        return p.startsWith("org.telegram.") ||
            p == "org.thunderdog.challegram" ||
            p.startsWith("org.thunderdog.challegram.")
    }

    /** 该应用是否标记「毫秒信息不可信」。未显式设置过时 Telegram 默认开启。 */
    fun isMsUntrusted(prefs: Prefs, pkg: String? = activePackage()): Boolean =
        !pkg.isNullOrBlank() && prefs.isAppMsOffsetEnabled(pkg)

    /** 当前生效的显示延迟（毫秒）：只有用户在「偏移设置」里设定的整体偏移。 */
    fun delayMs(prefs: Prefs): Long = prefs.globalOffsetMs.toLong().coerceIn(
        MIN_GLOBAL_OFFSET_MS.toLong(), MAX_GLOBAL_OFFSET_MS.toLong()
    )

    /** 展示用应用名：拿不到系统标签时回退到可读的短名称。 */
    fun appLabel(context: Context, pkg: String): String {
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        return label ?: friendlyName(pkg)
    }

    private fun friendlyName(pkg: String): String = when {
        isTelegram(pkg) -> "Telegram"
        pkg == "com.netease.cloudmusic" -> "网易云音乐"
        pkg == "com.tencent.qqmusic" -> "QQ 音乐"
        pkg == "com.kugou.android" -> "酷狗音乐"
        pkg == "com.spotify.music" -> "Spotify"
        pkg == "com.google.android.apps.youtube.music" -> "YouTube Music"
        else -> pkg.substringAfterLast('.')
    }
}
