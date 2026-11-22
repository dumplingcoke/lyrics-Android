package com.trae.androidlyrics

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志抓取工具：将应用关键日志写入文件，便于排查问题。
 *
 * 存储路径：context.filesDir/logs/app.log（应用内部存储，不受外部存储权限影响）。
 * 最大日志大小：1MB，超过后保留最后 800KB。
 */
object LogCapture {

    private const val TAG = "LogCapture"
    private const val MAX_LOG_SIZE_BYTES = 1024 * 1024 // 1MB
    private const val RETAIN_SIZE_BYTES = 800 * 1024 // 保留最后 800KB
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    /** 抓取关闭时在内存中保留的最近日志行数：开启抓取时回填，保留“开启前”的现场。 */
    private const val BUFFER_MAX_LINES = 300

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    @Volatile private var contextRef: Context? = null

    /** 关闭抓取期间的环形缓冲（仅内存，不落盘）。 */
    private val pendingLines = ArrayDeque<String>()

    /** 初始化（在 Application.onCreate 中调用），保存 Context 引用用于访问 filesDir。 */
    fun init(context: Context) {
        contextRef = context.applicationContext
    }

    /**
     * 获取日志文件。
     * @return 日志文件对象；如果 LogCapture 未初始化则返回 null。
     */
    fun getLogFile(): File? {
        val ctx = contextRef ?: return null
        val dir = File(ctx.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_FILE)
    }

    /** 当前日志文件大小（字节），文件不存在返回 0。 */
    fun getLogSize(): Long {
        val file = getLogFile() ?: return 0
        return file.length()
    }

    /**
     * 写入一条日志（仅当日志抓取开关开启时生效）。
     *
     * @param tag 日志标签
     * @param level 日志级别：Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR
     * @param message 日志消息
     */
    fun write(tag: String, level: Int, message: String) {
        val ctx = contextRef ?: return

        val levelChar = when (level) {
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> 'V'
        }
        val timestamp = timeFormat.format(Date())
        val line = "$timestamp [$levelChar] $tag: $message\n"

        if (!Prefs(ctx).logCaptureEnabled) {
            // 未开启抓取：只保留在内存环形缓冲中，开启时回填，避免必须触发
            // 一次播放状态变化后才能拿到问题现场
            synchronized(pendingLines) {
                if (pendingLines.size >= BUFFER_MAX_LINES) pendingLines.removeFirst()
                pendingLines.addLast(line)
            }
            return
        }
        appendLine(line)
    }

    /** 开启抓取时调用：把关闭期间缓存的日志按顺序写入文件，随后补一条分隔标记。 */
    fun startCapture() {
        if (contextRef == null) return
        val buffered = synchronized(pendingLines) {
            val copy = pendingLines.toList()
            pendingLines.clear()
            copy
        }
        buffered.forEach { appendLine(it) }
        val timestamp = timeFormat.format(Date())
        appendLine("$timestamp [I] LogCapture: —— 日志抓取已开启，上方为开启前的缓存现场 ——\n")
    }

    /** 实际写入文件（不做开关判断，调用方保证已开启）。 */
    private fun appendLine(line: String) {
        runCatching {
            val file = getLogFile() ?: return@runCatching
            // 截断旧日志（超过 MAX_LOG_SIZE_BYTES 时保留最后 RETAIN_SIZE_BYTES）
            if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                trimLog(file)
            }
            FileWriter(file, true).use { it.write(line) }
        }.onFailure {
            Log.w(TAG, "写入日志失败: ${it.message}")
        }
    }

    /** 截断日志：保留最后 RETAIN_SIZE_BYTES 的内容。 */
    private fun trimLog(file: File) {
        try {
            val content = file.readText(charset = Charsets.UTF_8)
            val keep = content.takeLast(RETAIN_SIZE_BYTES)
            // 尽量从换行符开始，避免截断行中间
            val firstNewline = keep.indexOf('\n')
            val trimmed = if (firstNewline > 0) keep.substring(firstNewline + 1) else keep
            file.writeText(trimmed, charset = Charsets.UTF_8)
        } catch (e: IOException) {
            Log.w(TAG, "截断日志失败: ${e.message}")
        }
    }

    /** 清空日志文件。 */
    fun clear() {
        synchronized(pendingLines) { pendingLines.clear() }
        val file = getLogFile() ?: return
        runCatching {
            if (file.exists()) file.writeText("", charset = Charsets.UTF_8)
        }
    }

    /** 导出日志到指定文件。返回导出后的文件路径，失败返回 null。 */
    fun export(destFile: File): File? {
        val logFile = getLogFile() ?: return null
        if (!logFile.exists() || logFile.length() == 0L) return null
        return runCatching {
            destFile.parentFile?.mkdirs()
            logFile.copyTo(destFile, overwrite = true)
            destFile
        }.getOrNull()
    }

    /** 读取日志文件内容（限制最大 64KB，避免大文件 OOM）。 */
    fun readContent(): String {
        val file = getLogFile() ?: return ""
        if (!file.exists()) return ""
        val maxRead = 64 * 1024
        return runCatching {
            val content = file.readText(charset = Charsets.UTF_8)
            if (content.length > maxRead) content.takeLast(maxRead) else content
        }.getOrDefault("")
    }

    /** 当前是否正在把日志落盘（未开启抓取时只进内存环形缓冲）。 */
    fun isCapturing(): Boolean = contextRef?.let { Prefs(it).logCaptureEnabled } ?: false

    /**
     * 诊断专用写入：仅在日志抓取开启时落盘。
     * 信息量大或较频繁的诊断（如会话打分明细）用这个，避免未抓取时
     * 把 300 行内存环形缓冲里「开启前的问题现场」挤掉。
     */
    fun diag(tag: String, msg: String) {
        if (isCapturing()) i(tag, msg)
    }

    /** 便捷方法：写入 DEBUG 级别日志。 */
    fun d(tag: String, msg: String) = write(tag, Log.DEBUG, msg)

    /** 便捷方法：写入 INFO 级别日志。 */
    fun i(tag: String, msg: String) = write(tag, Log.INFO, msg)

    /** 便捷方法：写入 WARN 级别日志。 */
    fun w(tag: String, msg: String) = write(tag, Log.WARN, msg)

    /** 便捷方法：写入 ERROR 级别日志。 */
    fun e(tag: String, msg: String) = write(tag, Log.ERROR, msg)
}

/**
 * 跨组件的「亮屏 / 通知监听重连」诊断共享状态。
 *
 * **纯诊断**：只被日志读写，任何播放计时、会话选择、跳转、界面逻辑都不得依赖它。
 * 字段用 [Volatile]，因为屏幕广播注册在 Application，而读方是通知监听服务 /
 * 主界面 / 桌面服务的帧回调。用于定位「锁屏打开屏幕时歌词中断一下」：
 * 区分系统深睡、主线程停摆、通知回调积压、以及 onResume 的 requestRebind
 * 是否真的触发了断连重连（2026-11-11 21:30 日志）。
 */
object Diag {

    @Volatile var lastScreenEvent: String = "-"
    @Volatile var lastScreenEventElapsedMs: Long = 0L
    @Volatile var lastScreenEventWallMs: Long = 0L
    @Volatile var lastScreenOnElapsedMs: Long = 0L
    @Volatile var lastScreenOffElapsedMs: Long = 0L
    @Volatile var screenOnCount: Int = 0

    /**
     * 最近一次测得的 `elapsedRealtime - uptimeMillis`。两次事件间这个差值的
     * 增量即设备进入 deep sleep 的时长：深睡时 uptimeMillis 不走、
     * elapsedRealtime 照走。用于把「空档」归因到系统睡眠还是主线程阻塞。
     */
    @Volatile var lastClockSkewMs: Long = 0L

    /** 最近一次 requestRebind 的序号，用于把「请求」与随后的断连/重连配对。 */
    @Volatile var rebindSeq: Int = 0
    @Volatile var lastRebindElapsedMs: Long = 0L
    @Volatile var lastRebindFrom: String = "-"
    @Volatile var lastDisconnectElapsedMs: Long = 0L
    @Volatile var lastConnectElapsedMs: Long = 0L

    /** 登记一次重绑请求并返回序号（仅主线程调用）。 */
    @Synchronized
    fun nextRebind(from: String, atElapsedMs: Long): Int {
        rebindSeq++
        lastRebindElapsedMs = atElapsedMs
        lastRebindFrom = from
        return rebindSeq
    }

    fun sinceScreenEvent(atElapsedMs: Long): Long =
        if (lastScreenEventElapsedMs > 0L) atElapsedMs - lastScreenEventElapsedMs else -1L

    fun sinceScreenOn(atElapsedMs: Long): Long =
        if (lastScreenOnElapsedMs > 0L) atElapsedMs - lastScreenOnElapsedMs else -1L

    fun sinceRebind(atElapsedMs: Long): Long =
        if (lastRebindElapsedMs > 0L) atElapsedMs - lastRebindElapsedMs else -1L

    fun sinceConnect(atElapsedMs: Long): Long =
        if (lastConnectElapsedMs > 0L) atElapsedMs - lastConnectElapsedMs else -1L

    fun sinceDisconnect(atElapsedMs: Long): Long =
        if (lastDisconnectElapsedMs > 0L) atElapsedMs - lastDisconnectElapsedMs else -1L
}
