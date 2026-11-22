package com.trae.androidlyrics

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「程序数据管理」的文件弹窗逻辑，供程序数据管理页与歌词文件存储页共用：
 * 点击文件 → 文件信息（大小 / 修改时间 / 路径）→ 查看文本 / 删除；
 * 删除前二次确认，删除后同步清理歌词绑定、当前匹配文件与最近打开记录。
 *
 * 传入 [onRename] 时（歌词文件存储页）改用自定义操作行，额外提供「重命名」；
 * 「程序数据管理」不传，保持原来的三按钮弹窗不变。
 */
object FileInfoDialog {

    /** 打开单个文件的信息弹窗。[onDeleted] 用于让调用方刷新列表。 */
    fun show(
        activity: AppCompatActivity,
        file: File,
        pathLabel: CharSequence,
        onRename: ((File) -> Unit)? = null,
        onDeleted: () -> Unit = {},
    ) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(file.lastModified()))
        val info = "${formatSize(file.length())}\n$time\n$pathLabel"
        if (onRename != null) {
            showWithRename(activity, file, info, onRename, onDeleted)
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(file.name)
            .setMessage(info)
            .setNeutralButton(R.string.data_view) { _, _ -> viewFile(activity, file) }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.data_delete) { _, _ ->
                confirmDelete(activity, file, onDeleted)
            }
            .show()
    }

    /**
     * 含「重命名」的文件信息弹窗。AlertDialog 最多只有三个按钮，且 Material 弹窗里
     * setMessage 会吞掉 setItems 的列表，因此信息文字与四个操作项统一放在自定义视图里。
     */
    private fun showWithRename(
        activity: AppCompatActivity,
        file: File,
        info: CharSequence,
        onRename: (File) -> Unit,
        onDeleted: () -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val accent = resolveAccentColor(activity)
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        container.addView(TextView(activity).apply {
            text = info
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(file.name)
            .setView(container)
            .create()
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun addAction(labelRes: Int, onClick: () -> Unit) {
            val item = TextView(activity).apply {
                text = activity.getString(labelRes)
                setTextColor(accent)
                textSize = 14f
                isSingleLine = true
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val ripple = android.util.TypedValue()
                if (activity.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, ripple, true
                    ) && ripple.resourceId != 0
                ) {
                    background = androidx.core.content.ContextCompat.getDrawable(
                        activity, ripple.resourceId
                    )
                }
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
            actions.addView(item)
        }
        addAction(R.string.data_view) { viewFile(activity, file) }
        addAction(R.string.action_rename) { onRename(file) }
        addAction(R.string.data_delete) { confirmDelete(activity, file, onDeleted) }
        addAction(android.R.string.cancel) { }
        container.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        dialog.show()
    }

    /** 解析当前主题的 colorPrimary，用于代码动态创建的弹窗操作项。 */
    private fun resolveAccentColor(activity: AppCompatActivity): Int {
        val value = android.util.TypedValue()
        return if (activity.theme.resolveAttribute(
                com.google.android.material.R.attr.colorPrimary, value, true
            )
        ) {
            if (value.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(activity, value.resourceId)
            } else {
                value.data
            }
        } else {
            0xFF8ECAFF.toInt()
        }
    }

    /** 长按列表项时可直接进入删除确认，同样是「程序数据管理」的弹窗逻辑。 */
    fun confirmDelete(
        activity: AppCompatActivity,
        file: File,
        onDeleted: () -> Unit = {},
    ) {
        val message = activity.getString(
            if (file.isDirectory) R.string.data_delete_dir_message else R.string.data_delete_message,
            file.name
        )
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.data_delete_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.data_delete) { _, _ ->
                val ok = runCatching {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }.getOrDefault(false)
                if (ok) {
                    LyricIndex.invalidate()
                    cleanupAfterDelete(activity)
                    UiTips.show(
                        activity,
                        activity.getString(R.string.data_deleted, file.name)
                    )
                    onDeleted()
                }
            }
            .show()
    }

    /** 以文本方式预览文件内容（歌词、json、日志等），自动识别编码。 */
    private fun viewFile(activity: AppCompatActivity, file: File) {
        if (file.length() > 2L * 1024 * 1024) {
            UiTips.show(activity, R.string.data_view_too_large)
            return
        }
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null) {
            UiTips.show(activity, activity.getString(R.string.open_failed, file.name))
            return
        }
        val content = if (bytes.any { it == 0.toByte() }) {
            activity.getString(R.string.data_view_binary)
        } else {
            DocumentIo.decode(bytes).text.ifEmpty { activity.getString(R.string.log_empty) }
        }
        val density = activity.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val textView = TextView(activity).apply {
            text = content
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(textView) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    /** 删除后的跨位置同步：歌词绑定、当前匹配文件、最近打开记录中的失效条目。 */
    fun cleanupAfterDelete(context: Context) {
        LyricBindings.purgeMissing(context)
        val matched = Session.matchedFile
        if (matched != null && !matched.exists()) {
            Session.matchedFile = null
            Session.matchedKey = null
            Session.matchVersion++
        }
        val prefs = Prefs(context)
        val store = File(context.filesDir, "lyrics")
        val prefix = store.absolutePath + File.separator
        prefs.getRecentFiles().forEach { recent ->
            if (recent.uri.startsWith(prefix) && !File(recent.uri).exists()) {
                prefs.removeRecentFile(recent.uri)
            }
        }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
