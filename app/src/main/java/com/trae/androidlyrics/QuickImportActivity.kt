package com.trae.androidlyrics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * 透明中转页：桌面歌词悬浮窗无法直接拉起系统目录选择器时，由本页代为拉起。
 *
 * 用户选中目录后写入最近目录列表并默认勾选，随后立即结束（页面本身无界面）。
 */
class QuickImportActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    private val pickDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                addRecentDir(treeUri)
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (savedInstanceState == null) {
            pickDirLauncher.launch(null)
        }
    }

    /** 记录目录并默认勾选，便于回到面板后直接导入。 */
    private fun addRecentDir(treeUri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        runCatching {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val rawPath = treeDocId.split(':', limit = 2).lastOrNull().orEmpty()
            val fullPath = if (treeDocId.startsWith("primary:")) {
                if (rawPath.isEmpty()) "/内部存储" else "/内部存储/$rawPath"
            } else {
                "/$rawPath"
            }
            val dirName = rawPath.substringAfterLast('/', missingDelimiterValue = "")
                .ifEmpty { getString(R.string.recent_dir_internal) }
            prefs.addRecentDir(treeUri.toString(), dirName, fullPath)
            // 首次添加时保持"默认全选"语义：已保存过则沿用，否则把现有目录一并选中
            val selected = if (prefs.hasQuickImportSelectedDirs()) {
                prefs.getQuickImportSelectedDirs()
            } else {
                prefs.getRecentDirs().map { it.uri }.toSet()
            }
            prefs.setQuickImportSelectedDirs(selected + treeUri.toString())
            UiTips.show(this, R.string.quick_import_added_dir)
        }
    }

    companion object {
        /** 标记本次启动来自"添加目录"，保留以兼容调用方。 */
        const val EXTRA_PICK_DIR = "extra_pick_dir"
    }
}
