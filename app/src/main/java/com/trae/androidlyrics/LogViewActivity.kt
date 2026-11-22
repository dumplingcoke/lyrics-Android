package com.trae.androidlyrics

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivityLogViewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志文件查看：展示 [LogCapture] 记录的日志，支持导出与清除。
 */
class LogViewActivity : ThemedActivity() {

    private lateinit var binding: ActivityLogViewBinding

    /** 导出日志：使用系统"创建文档"选择器，用户选择保存位置后写入日志内容。 */
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri ?: return@registerForActivityResult
            exportTo(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogViewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.btnLogExport.setOnClickListener {
            val name = "androidlyrics_log_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt"
            exportLauncher.launch(name)
        }
        binding.btnLogCopy.setOnClickListener { copyToClipboard() }
        binding.btnLogClear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val content = LogCapture.readContent()
        binding.logContent.text = content.ifEmpty { getString(R.string.log_empty) }
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun exportTo(uri: Uri) {
        val text = LogCapture.readContent()
        if (text.isEmpty()) {
            UiTips.show(this, R.string.log_empty)
            return
        }
        val ok = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        UiTips.show(this, if (ok) R.string.log_export_done else R.string.log_export_failed)
    }

    /** 复制全部日志到系统剪贴板，便于直接粘贴分享。 */
    private fun copyToClipboard() {
        val text = LogCapture.readContent()
        if (text.isEmpty()) {
            UiTips.show(this, R.string.log_empty)
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val ok = runCatching {
            clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_copy_label), text))
        }.isSuccess && clipboard != null
        UiTips.show(this, if (ok) R.string.log_copied else R.string.log_copy_failed)
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.log_clear)
            .setMessage(R.string.log_clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.log_clear) { _, _ ->
                LogCapture.clear()
                reload()
                UiTips.show(this, R.string.log_cleared)
            }
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
