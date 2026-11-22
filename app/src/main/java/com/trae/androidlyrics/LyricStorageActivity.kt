package com.trae.androidlyrics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivityLyricStorageBinding
import com.trae.androidlyrics.databinding.ItemLyricFileBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 歌词文件存储管理：管理应用数据目录（files/lyrics）下保存的歌词副本。
 *
 * 支持导入外部歌词文件、导出到用户选择的目录；
 * 点击文件可查看 / 重命名 / 删除，长按进入选择状态后可全选、取消全选、
 * 导出选中、删除选中，以及通过「更多操作」批量重命名选中文件。
 * 选择状态下系统返回键与标题栏返回只退出选择状态，不关闭本页。
 */
class LyricStorageActivity : ThemedActivity() {

    private lateinit var binding: ActivityLyricStorageBinding
    private lateinit var adapter: FileAdapter
    private lateinit var prefs: Prefs

    /** 全量文件（未过滤），搜索时作为过滤输入。 */
    private var allFiles: List<File> = emptyList()
    /** 当前搜索框文本。 */
    private var searchQuery: String = ""
    /** 本次导出限定为选中的文件名；null 表示导出全部。 */
    private var pendingExportNames: Set<String>? = null
    /** 文件名+修改时间 → 歌词正文，供内容搜索使用（后台构建，避免阻塞 UI）。 */
    private val contentCache = HashMap<String, String>()
    @Volatile private var contentScanRunning = false

    private fun storeDir(): File = File(filesDir, "lyrics").apply { mkdirs() }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            importFiles(uris)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            val names = pendingExportNames
            pendingExportNames = null
            if (treeUri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            exportFiles(treeUri, names)
        }

    /** 最近目录为空时的目录选择器：选中目录即导入歌词，并记入最近目录。 */
    private val dirImportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            treeUri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            // 记入最近目录（与主界面保存时一致，持久化权限支持后续直接读取）
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
            importFromDirs(listOf(treeUri), selectedImportExts())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityLyricStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = FileAdapter(
            // 普通点击弹出文件信息 / 查看 / 重命名 / 删除弹窗（重命名仅本页提供）
            onClick = { file ->
                FileInfoDialog.show(
                    this,
                    file,
                    file.absolutePath,
                    onRename = { target -> promptRenameSingle(target) },
                    onDeleted = { refresh() }
                )
            },
            onSelectionChanged = { updateSelectionBar() }
        )
        binding.filesRecycler.layoutManager = LinearLayoutManager(this)
        binding.filesRecycler.adapter = adapter

        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        binding.btnImportFromDir.setOnClickListener {
            showImportFromDirDialog()
        }
        binding.btnExport.setOnClickListener {
            if (storeDir().listFiles().isNullOrEmpty()) return@setOnClickListener
            pendingExportNames = null
            exportLauncher.launch(null)
        }

        setupSearch()
        // 旋转屏幕 / 系统回收后再回到本页时恢复搜索词；SAF 目录选择器期间也保留导出选中集
        savedInstanceState?.getStringArray(STATE_PENDING_EXPORT)?.let {
            pendingExportNames = it.toHashSet()
        }
        searchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty()
        if (searchQuery.isNotEmpty()) binding.searchInput.setText(searchQuery)

        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }
        binding.btnDeselectAll.setOnClickListener { adapter.deselectAll() }
        binding.btnExportSelected.setOnClickListener {
            val names = adapter.getSelectedFiles().map { it.name }.toSet()
            if (names.isEmpty()) {
                UiTips.show(this, R.string.export_selected_none)
                return@setOnClickListener
            }
            pendingExportNames = names
            exportLauncher.launch(null)
        }
        binding.btnDeleteSelected.setOnClickListener {
            // 只作用于当前可见（过滤后）列表中的选中项，避免误删被搜索隐藏的文件
            val files = adapter.getSelectedFiles()
            if (files.isEmpty()) return@setOnClickListener
            // 删除不可恢复：先弹窗确认
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_selected_confirm, files.size))
                .setPositiveButton(R.string.dialog_confirm_delete) { _, _ ->
                    files.forEach { it.delete() }
                    LyricIndex.invalidate()
                    adapter.exitSelectMode()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.btnMoreActions.setOnClickListener { showMoreActions() }

        // 选择状态优先消费返回键：先退出选择状态，未进入选择状态时才返回设置页
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectMode()) adapter.exitSelectMode() else finish()
            }
        })
    }

    /** 当前生效的导入格式：勾选"不限格式"返回 null（全部文件），否则按上次勾选的具体格式。 */
    private fun selectedImportExts(): Set<String>? =
        if (prefs.quickImportAnyFormat) null
        else prefs.getQuickImportFormats() ?: LyricsParser.SUPPORTED_EXTENSIONS

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SEARCH_QUERY, searchQuery)
        pendingExportNames?.let {
            outState.putStringArray(STATE_PENDING_EXPORT, it.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        allFiles = storeDir().listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        applyFilter()
        scanContentsAsync(allFiles)
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 搜索语法（与「AI 提示词管理」一致）：
     *  - 普通关键词：命中任意一个即可（OR）；
     *  - `+关键词`：必须同时包含（AND）；
     *  - `-关键词`：必须不包含（NOT）。
     * 多个词用空格分隔，匹配范围＝文件名 + 歌词正文，忽略大小写。
     */
    private data class SearchToken(val word: String, val op: Char)

    private fun parseSearchQuery(raw: String): List<SearchToken> =
        raw.trim().split(Regex("\\s+")).mapNotNull { part ->
            if (part.isEmpty()) return@mapNotNull null
            val op = when (part[0]) {
                '+', '＋' -> '+'
                '-', '－', '−' -> '-'
                else -> ' '
            }
            val word = if (op == ' ') part else part.substring(1)
            if (word.isEmpty()) null else SearchToken(word, op)
        }

    private fun matchesQuery(haystack: String, tokens: List<SearchToken>): Boolean {
        if (tokens.isEmpty()) return true
        var hasOr = false
        var orHit = false
        for (t in tokens) {
            val hit = haystack.contains(t.word, ignoreCase = true)
            when (t.op) {
                '-' -> if (hit) return false
                '+' -> if (!hit) return false
                else -> {
                    hasOr = true
                    if (hit) orHit = true
                }
            }
        }
        return !hasOr || orHit
    }

    private fun searchKey(file: File): String = "${file.name}|${file.lastModified()}"

    private fun applyFilter() {
        val tokens = parseSearchQuery(searchQuery)
        val shown = if (tokens.isEmpty()) allFiles else allFiles.filter { file ->
            val content = synchronized(contentCache) { contentCache[searchKey(file)].orEmpty() }
            matchesQuery("${file.name}\n$content", tokens)
        }
        adapter.submit(shown)
        binding.emptyText.text = getString(
            if (tokens.isEmpty() || allFiles.isEmpty()) R.string.no_lyric_files
            else R.string.storage_search_no_result
        )
        binding.emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.filesRecycler.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        updateSelectionBar()
    }

    /** 后台建立「文件名 → 歌词正文」缓存；完成后若缓存有变化再刷新一次过滤结果。 */
    private fun scanContentsAsync(files: List<File>) {
        if (contentScanRunning) return
        contentScanRunning = true
        Thread {
            var changed = false
            files.forEach { file ->
                val key = searchKey(file)
                if (synchronized(contentCache) { contentCache.containsKey(key) }) return@forEach
                val text = runCatching {
                    if (file.length() > MAX_SEARCH_BYTES) ""
                    else DocumentIo.decode(file.readBytes()).text
                }.getOrDefault("")
                synchronized(contentCache) { contentCache[key] = text }
                changed = true
            }
            val live = files.map { searchKey(it) }.toHashSet()
            synchronized(contentCache) { contentCache.keys.retainAll(live) }
            runOnUiThread {
                contentScanRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (changed) applyFilter()
                // 扫描期间若列表被导入/删除刷新过，补扫新出现的文件
                val missing = allFiles.any {
                    synchronized(contentCache) { !contentCache.containsKey(searchKey(it)) }
                }
                if (missing) scanContentsAsync(allFiles)
            }
        }.start()
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyFilter()
            }
        })
        binding.searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                v.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }
        binding.btnSearchHelp.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.storage_search_help_title)
                .setMessage(R.string.storage_search_help_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun updateSelectionBar() {
        binding.selectionBar.visibility =
            if (adapter.isSelectMode()) View.VISIBLE else View.GONE
        val hasSelection = adapter.selectedCount() > 0
        binding.btnDeleteSelected.alpha = if (hasSelection) 1f else 0.4f
        binding.btnExportSelected.alpha = if (hasSelection) 1f else 0.4f
    }

    /** 导入外部文件到应用数据目录：追加写入（不清空已有），同名时先列出并询问。 */
    private fun importFiles(uris: List<Uri>) {
        Thread {
            val items = uris.mapNotNull { uri ->
                runCatching {
                    val name = DocumentIo.displayName(contentResolver, uri)
                        ?.substringAfterLast('/')?.substringAfterLast('\\')
                        ?: return@runCatching null
                    val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    PendingImport(safe, uri)
                }.getOrNull()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (items.isNotEmpty()) confirmAndImport(items)
            }
        }.start()
    }

    /** 导出重名处理模式。 */
    private enum class ExportMode { SKIP, OVERWRITE, COPY }

    /**
     * 导出到用户选择的目录：先检测重名，再按用户选择处理。
     * [names] 为 null 时导出全部，否则只导出选中（且当前仍存在）的文件。
     */
    private fun exportFiles(treeUri: Uri, names: Set<String>?) {
        val files = storeDir().listFiles()
            ?.filter { it.isFile && (names == null || it.name in names) }
            .orEmpty()
        if (files.isEmpty()) {
            UiTips.show(this, getString(R.string.exported_count, 0))
            return
        }
        Thread {
            val existing = runCatching { listTreeFileNames(treeUri) }.getOrDefault(emptyMap())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val dupes = files.map { it.name }.filter { existing.containsKey(it) }
                if (dupes.isEmpty()) {
                    doExport(treeUri, files, ExportMode.OVERWRITE, existing)
                    return@runOnUiThread
                }
                // 检测到重名：四个选项——全部跳过 / 全部覆盖 / 创建副本 / 取消操作
                val options = arrayOf(
                    getString(R.string.skip_all),
                    getString(R.string.overwrite_all),
                    getString(R.string.copy_all),
                    getString(R.string.cancel_op)
                )
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.export_duplicates_msg, dupes.size))
                    .setItems(options) { d, which ->
                        when (which) {
                            0 -> doExport(treeUri, files, ExportMode.SKIP, existing)
                            1 -> doExport(treeUri, files, ExportMode.OVERWRITE, existing)
                            2 -> doExport(treeUri, files, ExportMode.COPY, existing)
                            else -> d.dismiss()
                        }
                    }
                    .show()
            }
        }.start()
    }

    /** 实际导出：按 mode 处理与目标目录同名的文件。 */
    private fun doExport(treeUri: Uri, files: List<File>, mode: ExportMode, existing: Map<String, Uri>) {
        Thread {
            var count = 0
            // 本次导出过程中产生的副本名也要纳入占位，避免副本之间再重名
            val taken = existing.keys.toMutableSet()
            files.forEach { file ->
                runCatching {
                    val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                    val existingUri = existing[file.name]
                    if (existingUri != null && mode == ExportMode.SKIP) return@runCatching
                    val mime = LyricsParser.mimeTypeFor(file.name)
                    val target = when {
                        existingUri != null && mode == ExportMode.OVERWRITE -> existingUri
                        else -> {
                            val requested = if (existingUri != null) {
                                uniqueCopyName(file.name, taken)
                            } else file.name
                            // text/plain 会让部分 SAF provider 按 MIME 给非 .txt 名字补出
                            // .txt 后缀（xxx.krc → xxx.krc.txt）。优先用中立 MIME 创建，
                            // 少数 provider 不认自定义类型时再退回真实 MIME 重试。
                            val created = runCatching {
                                android.provider.DocumentsContract.createDocument(
                                    contentResolver, parentUri, NEUTRAL_CREATE_MIME, requested)
                            }.getOrNull() ?: runCatching {
                                android.provider.DocumentsContract.createDocument(
                                    contentResolver, parentUri, mime, requested)
                            }.getOrNull() ?: return@runCatching
                            // 创建后仍可能被补后缀或由 provider 自行编号（编号追加在
                            // 扩展名之后，如 xxx.yrc (2)）：统一纠正为规范名，并用
                            // renameDocument 返回的新 Uri 写入，旧 Uri 可能已失效。
                            ensureExportDisplayName(created, file.name, requested, treeUri, taken)
                        }
                    }
                    contentResolver.openOutputStream(target, "wt")?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: return@runCatching
                    count++
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                UiTips.show(this, getString(R.string.exported_count, count))
            }
        }.start()
    }

    /**
     * 校验并纠正 createDocument 后的实际显示名。SAF provider 有两类自动改名：
     *  1. 按 MIME 补出多余扩展名（xxx.lrc → xxx.lrc.txt / xxx.lrc.bin）：改回期望名；
     *  2. 自行去重时把编号追加在扩展名之后（xxx.yrc → xxx.yrc (2)）：编号位置错误，
     *     统一改成「主体 (n).扩展」的规范副本名，避免再导出、再导入时后缀错乱。
     * renameDocument 可能返回新 Uri，必须作为后续写入目标返回，否则写入会落空。
     */
    private fun ensureExportDisplayName(
        created: Uri,
        originName: String,
        requested: String,
        treeUri: Uri,
        taken: MutableSet<String>,
    ): Uri {
        var current = created
        var actual: String = queryDisplayName(current) ?: run {
            taken.add(requested)
            return current
        }
        if (actual.equals(requested, ignoreCase = true)) {
            taken.add(requested)
            return current
        }
        // 1) 只是末尾多出一个扩展名：直接改回期望名
        val extra = if (actual.startsWith(requested, ignoreCase = true)) {
            actual.substring(requested.length)
        } else ""
        if (Regex("^\\.[A-Za-z0-9]{1,8}$").matches(extra)) {
            current = renameExportDocument(current, requested)
            actual = queryDisplayName(current) ?: run {
                taken.add(requested)
                return current
            }
            if (actual.equals(requested, ignoreCase = true)) {
                taken.add(requested)
                return current
            }
        }
        // 2) 编号被追加在扩展名之后（也可能先补扩展名再编号）：
        //    "BY2 - 我知道.yrc (2)" / "BY2 - 我知道.yrc (2).txt"
        val remainder = if (actual.startsWith(requested, ignoreCase = true)) {
            actual.substring(requested.length)
        } else ""
        if (!Regex("""^\s*\(\d+\)(?:\.[A-Za-z0-9]{1,8})?$""").matches(remainder)) {
            // 未知改名规则：保留 provider 结果，仅占位避免后续副本撞名
            taken.add(actual)
            return current
        }
        val occupied = (taken + runCatching { listTreeFileNames(treeUri).keys }
            .getOrDefault(emptySet())).toMutableSet()
        repeat(99) {
            val candidate = uniqueCopyName(originName, occupied)
            if (candidate.equals(actual, ignoreCase = true)) {
                taken.add(actual)
                return current
            }
            current = renameExportDocument(current, candidate)
            val renamed = queryDisplayName(current)
            if (renamed != null && renamed.equals(candidate, ignoreCase = true)) {
                taken.add(candidate)
                return current
            }
            occupied.add(candidate)
            if (renamed != null) actual = renamed
        }
        taken.add(actual)
        return current
    }

    /** 读取 SAF 文档的显示名；provider 不支持时返回 null，交由调用方按期望名处理。 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** renameDocument 返回 null 表示 Uri 未变，仍沿用传入的 Uri。 */
    private fun renameExportDocument(uri: Uri, name: String): Uri =
        runCatching {
            android.provider.DocumentsContract.renameDocument(contentResolver, uri, name)
        }.getOrNull() ?: uri

    /** 列出 SAF tree 目录下所有文件：显示名 → 文档 URI。 */
    private fun listTreeFileNames(treeUri: Uri): Map<String, Uri> {
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val map = HashMap<String, Uri>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                map[name] = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
            }
        }
        return map
    }

    /**
     * 拆分文件名主体与扩展名；兼容 provider 曾把副本编号追加在扩展名之后的历史文件
     * （如 "BY2 - 我知道.yrc (2)"），先剥掉尾部编号再找真实后缀。
     */
    private fun splitName(name: String): Pair<String, String> {
        val stripped = name.replace(Regex("""\s*\(\d+\)$"""), "")
        val dot = stripped.lastIndexOf('.')
        return if (dot > 0) stripped.substring(0, dot) to stripped.substring(dot)
        else stripped to ""
    }

    /** 生成不冲突的副本名：编号添加在文件名主体之后、扩展名之前（如 "歌曲 (1).lrc"）。 */
    private fun uniqueCopyName(name: String, taken: Set<String>): String {
        val (base, ext) = splitName(name)
        var n = 1
        var candidate = "$base ($n)$ext"
        while (taken.any { it.equals(candidate, ignoreCase = true) }) {
            n++
            candidate = "$base ($n)$ext"
        }
        return candidate
    }

    /** 列出 SAF tree 目录下指定格式的歌词文件（名称 → 文档 URI）；exts 为 null 时不限格式（全部文件）。 */
    private fun listTreeLyrics(treeUri: Uri, exts: Set<String>?): List<Pair<String, Uri>> {
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val result = mutableListOf<Pair<String, Uri>>()
        contentResolver.query(
            childrenUri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (exts == null) {
                    val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                    result.add(name to docUri)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in exts) {
                        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                        result.add(name to docUri)
                    }
                }
            }
        }
        return result.sortedBy { it.first }
    }

    /**
     * 从最近目录导入：复选框管理参与导入的目录列表；
     * 格式复选框（lrc / srt）决定导入时检测哪些格式。
     */
    private fun showImportFromDirDialog() {
        val dirs = prefs.getRecentDirs()
        if (dirs.isEmpty()) {
            // 无最近目录：直接跳系统文件夹选择器，选中目录即导入并记入最近目录
            dirImportLauncher.launch(null)
            return
        }
        val pad = (resources.displayMetrics.density * 20).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        // 格式复选框：按解析器支持的格式动态生成（每行 4 个），记忆上次勾选状态
        container.addView(
            TextView(this).apply { text = getString(R.string.import_select_formats) })
        val savedFormats = prefs.getQuickImportFormats()
        // 从未设置过时默认全选所有支持的格式
        val formatChecked = (savedFormats ?: LyricsParser.SUPPORTED_EXTENSIONS).toMutableSet()
        val formatDensity = resources.displayMetrics.density
        val formatBoxes = LinkedHashMap<String, MaterialCheckBox>()
        val formatsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var currentRow: LinearLayout? = null
        LyricsParser.SUPPORTED_EXTENSIONS.forEachIndexed { index, ext ->
            if (index % 4 == 0) {
                currentRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                formatsContainer.addView(currentRow)
            }
            val cb = MaterialCheckBox(this).apply {
                text = ext
                isChecked = ext in formatChecked
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index % 4 != 0) marginStart = (formatDensity * 10).toInt() }
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) formatChecked.add(ext) else formatChecked.remove(ext)
                    prefs.setQuickImportFormats(formatChecked)
                }
            }
            formatBoxes[ext] = cb
            currentRow?.addView(cb)
        }
        // 不限格式：勾选后导入目录内全部文件，无法再勾选具体格式
        fun setFormatCheckboxesEnabled(enabled: Boolean) {
            formatBoxes.values.forEach {
                it.isEnabled = enabled
                it.alpha = if (enabled) 1f else 0.4f
            }
        }
        val cbAny = MaterialCheckBox(this).apply {
            text = getString(R.string.import_format_any)
            isChecked = prefs.quickImportAnyFormat
            setOnCheckedChangeListener { _, isChecked ->
                prefs.quickImportAnyFormat = isChecked
                setFormatCheckboxesEnabled(!isChecked)
            }
        }
        container.addView(formatsContainer)
        container.addView(cbAny)
        setFormatCheckboxesEnabled(!cbAny.isChecked)

        // 目录复选框：勾选哪些最近目录参与导入；支持把勾选的目录从列表中移除
        val dirHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dirHeader.addView(
            TextView(this).apply {
                text = getString(R.string.import_select_dirs)
                setPadding(0, (resources.displayMetrics.density * 8).toInt(), 0, 0)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val btnRemoveSelected = TextView(this).apply {
            text = getString(R.string.remove_selected_dirs)
            setTextColor(0xFFFF6B6B.toInt())
            textSize = 13f
            setPadding(24, 12, 0, 0)
            background = null
        }
        dirHeader.addView(btnRemoveSelected)
        container.addView(dirHeader)

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listHost) }
        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.35f).toInt()
            )
        )
        val currentDirs = dirs.toMutableList()
        val dirCheckboxes = mutableListOf<MaterialCheckBox>()
        // 记忆勾选：从未设置过时默认全选；之后严格按用户上次勾选状态（含全部取消）
        val checked = if (prefs.hasQuickImportSelectedDirs()) {
            prefs.getQuickImportSelectedDirs().toMutableSet()
        } else {
            currentDirs.map { it.uri }.toMutableSet().also {
                prefs.setQuickImportSelectedDirs(it)
            }
        }

        fun persistChecked() {
            prefs.setQuickImportSelectedDirs(checked)
        }

        fun allAreSelected(): Boolean =
            currentDirs.isNotEmpty() && currentDirs.all { it.uri in checked }

        fun rebuildDirList() {
            listHost.removeAllViews()
            dirCheckboxes.clear()
            currentDirs.forEach { dir ->
                dirCheckboxes.add(
                    MaterialCheckBox(this).apply {
                        text = dir.path.ifEmpty { dir.name }
                        isChecked = dir.uri in checked
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.START
                        setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) checked.add(dir.uri) else checked.remove(dir.uri)
                            persistChecked()
                        }
                    }.also { listHost.addView(it) }
                )
            }
        }
        rebuildDirList()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_from_dir_title)
            .setView(container)
            .create()

        btnRemoveSelected.setOnClickListener {
            val selected = currentDirs.filterIndexed { i, _ -> dirCheckboxes.getOrNull(i)?.isChecked == true }
            if (selected.isEmpty()) return@setOnClickListener
            prefs.removeRecentDirs(selected.map { it.uri })
            currentDirs.removeAll(selected.toSet())
            selected.forEach { checked.remove(it.uri) }
            persistChecked()
            if (currentDirs.isEmpty()) {
                dialog.dismiss()
                UiTips.show(this, R.string.no_recent_dirs)
            } else {
                rebuildDirList()
            }
        }

        // 底部操作行：左侧全选切换；右侧依次 取消 / 导入 / 添加新目录（位于导入右侧）
        val density = resources.displayMetrics.density
        fun actionButton(textRes: Int) = TextView(this).apply {
            text = getString(textRes)
            setTextColor(0xFF8ECAFF.toInt())
            textSize = 14f
            setPadding(
                (density * 14).toInt(), (density * 10).toInt(),
                (density * 14).toInt(), (density * 10).toInt()
            )
        }
        val btnToggleAll = actionButton(R.string.select_all)
        val btnCancel = actionButton(android.R.string.cancel)
        val btnDoImport = actionButton(R.string.import_lyrics)
        val btnAddNewDir = actionButton(R.string.add_new_dir)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (density * 12).toInt(), 0, (density * 4).toInt())
            addView(
                btnToggleAll,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(btnCancel)
            addView(btnDoImport)
            addView(btnAddNewDir)
        }
        container.addView(actionRow)

        btnToggleAll.alpha = if (allAreSelected()) 1f else 0.5f
        btnToggleAll.setOnClickListener { btn ->
            // 目录全选/取消全选切换：不关闭对话框
            val target = !allAreSelected()
            if (target) checked.addAll(currentDirs.map { it.uri })
            else checked.clear()
            persistChecked()
            dirCheckboxes.forEach { it.isChecked = target }
            btn.alpha = if (target) 1f else 0.5f
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDoImport.setOnClickListener {
            // 勾选"不限格式"时导入全部文件，否则按勾选的具体格式过滤
            val exts: Set<String>? = if (cbAny.isChecked) null
            else formatBoxes.filterValues { it.isChecked }.keys.toSet()
            val selectedDirs = currentDirs.filterIndexed { i, _ -> dirCheckboxes.getOrNull(i)?.isChecked == true }
            if ((exts != null && exts.isEmpty()) || selectedDirs.isEmpty()) {
                UiTips.show(this, R.string.import_nothing_selected)
                return@setOnClickListener
            }
            dialog.dismiss()
            importFromDirs(selectedDirs.map { Uri.parse(it.uri) }, exts)
        }
        btnAddNewDir.setOnClickListener {
            // 添加新目录：跳系统文件夹选择器，选中即导入并记入最近目录
            dialog.dismiss()
            dirImportLauncher.launch(null)
        }
        dialog.show()
    }

    /** 待导入文件：目标文件名 + 来源 URI。 */
    private data class PendingImport(val name: String, val uri: Uri)

    /** 从多个 SAF tree 目录收集勾选格式的歌词文件，重名确认后追加导入（不清空已有）；exts 为 null 时不限格式。 */
    private fun importFromDirs(treeUris: List<Uri>, exts: Set<String>?) {
        Thread {
            val items = treeUris.flatMap { treeUri ->
                runCatching { listTreeLyrics(treeUri, exts) }.getOrDefault(emptyList())
            }.map { (name, docUri) -> PendingImport(name, docUri) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                confirmAndImport(items)
            }
        }.start()
    }

    /** 导入前检查同名文件：存在则列出重名文件并询问全部跳过/全部覆盖。 */
    private fun confirmAndImport(items: List<PendingImport>) {
        val dupes = items.map { it.name }.distinct().filter { File(storeDir(), it).exists() }
        if (dupes.isEmpty()) {
            doImport(items, overwrite = true)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.duplicate_files_title)
            .setMessage(getString(R.string.duplicate_files_msg, dupes.joinToString("\n")))
            .setPositiveButton(R.string.overwrite_all) { _, _ -> doImport(items, overwrite = true) }
            .setNegativeButton(R.string.skip_all) { _, _ -> doImport(items, overwrite = false) }
            .show()
    }

    /** 实际写入：追加到存储目录；同名文件按 overwrite 决定覆盖或跳过。 */
    private fun doImport(items: List<PendingImport>, overwrite: Boolean) {
        Thread {
            var count = 0
            items.forEach { item ->
                runCatching {
                    val target = File(storeDir(), item.name)
                    if (target.exists() && !overwrite) return@runCatching
                    val bytes = contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                        ?: return@runCatching
                    target.writeBytes(bytes)
                    count++
                }
            }
            // 导入结束后一次性失效索引，避免每个文件都触发重建
            if (count > 0) LyricIndex.invalidate()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                UiTips.show(this, getString(R.string.imported_count, count))
                refresh()
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        // 与系统返回键保持一致：选择状态下先退出选择状态
        if (adapter.isSelectMode()) adapter.exitSelectMode() else finish()
        return true
    }

    // ---------------------------------------------------------------- 重命名

    /** 选择模式「更多操作」列表：目前仅提供「重命名选中」。 */
    private fun showMoreActions() {
        val options = arrayOf(getString(R.string.storage_rename_selected))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_more_actions)
            .setItems(options) { _, which ->
                if (which == 0) promptRenameSelected()
            }
            .show()
    }

    /** 单文件重命名：输入完整文件名（含扩展名），默认只选中主体部分。 */
    private fun promptRenameSingle(file: File) {
        if (!file.exists()) {
            UiTips.show(this, R.string.rename_no_file)
            return
        }
        val oldName = file.name
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
            .setTitle(R.string.storage_rename_file_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_confirm, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = sanitizeFileName(input.text?.toString()?.trim().orEmpty())
                when {
                    newName.isEmpty() -> {
                        input.error = getString(R.string.rename_empty)
                        return@setOnClickListener
                    }
                    newName == oldName -> {
                        dialog.dismiss()
                        return@setOnClickListener
                    }
                    renameTargetExists(newName, file) -> {
                        input.error = getString(R.string.rename_exists)
                        return@setOnClickListener
                    }
                }
                input.error = null
                dialog.dismiss()
                if (renameOne(file, newName)) {
                    UiTips.show(this, getString(R.string.rename_success, newName))
                    adapter.exitSelectMode()
                    refresh()
                } else {
                    UiTips.show(this, R.string.rename_failed)
                }
            }
        }
        dialog.show()
        input.requestFocus()
        // 默认只选中主体部分，保留扩展名，改歌名时不必重新输入后缀
        val dot = oldName.lastIndexOf('.')
        if (dot > 0) input.setSelection(0, dot) else input.setSelection(0, oldName.length)
    }

    /**
     * 「重命名选中」：只选中 1 个文件时沿用单文件重命名；多个文件时输入一个基础名称，
     * 按列表顺序依次命名为「名称 (1).ext」「名称 (2).ext」…，各自保留原扩展名。
     */
    private fun promptRenameSelected() {
        val files = adapter.getSelectedFiles()
        if (files.isEmpty()) {
            UiTips.show(this, R.string.storage_rename_selected_none)
            return
        }
        if (files.size == 1) {
            promptRenameSingle(files[0])
            return
        }
        val density = resources.displayMetrics.density
        val pad = (density * 20).toInt()
        val input = EditText(this).apply {
            hint = getString(R.string.storage_rename_base_hint)
            setSingleLine(true)
            setTextColor(resolvePrimaryTextColor())
        }
        val note = TextView(this).apply {
            text = getString(R.string.storage_rename_batch_note, files.size)
            textSize = 12f
            setPadding(pad, (density * 8).toInt(), pad, 0)
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
            addView(note)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.storage_rename_batch_title)
            .setView(container)
            .setPositiveButton(R.string.dialog_confirm, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = sanitizeFileName(input.text?.toString()?.trim().orEmpty())
                val base = stripKnownExtension(typed)
                if (base.isEmpty()) {
                    input.error = getString(R.string.rename_empty)
                    return@setOnClickListener
                }
                input.error = null
                dialog.dismiss()
                val ok = doBatchRename(files, base)
                when {
                    ok == files.size -> UiTips.show(
                        this, getString(R.string.storage_rename_batch_success, ok)
                    )
                    ok > 0 -> UiTips.show(
                        this, getString(R.string.storage_rename_batch_partial, ok, files.size - ok)
                    )
                    else -> UiTips.show(this, R.string.rename_failed)
                }
                adapter.exitSelectMode()
                refresh()
            }
        }
        dialog.show()
        input.requestFocus()
    }

    /** 依次生成「名称 (n).扩展」并改名，跳过已被占用或与自身旧名相同的名字。 */
    private fun doBatchRename(files: List<File>, base: String): Int {
        var number = 1
        var success = 0
        files.forEach { file ->
            if (!file.exists()) return@forEach
            val ext = splitName(file.name).second
            var candidate: String
            var guard = 0
            do {
                candidate = "$base ($number)$ext"
                number++
                guard++
            } while (
                guard < 999 &&
                (candidate.equals(file.name, ignoreCase = true) || renameTargetExists(candidate, file))
            )
            if (renameOne(file, candidate)) success++
        }
        return success
    }

    /** 单个文件改名，并把内容搜索缓存与所有按名字/路径记录的状态迁移到新名字。 */
    private fun renameOne(file: File, newName: String): Boolean {
        val oldName = file.name
        if (newName == oldName) return true
        val oldPath = file.absolutePath
        val stamp = file.lastModified()
        val target = File(file.parentFile ?: storeDir(), newName)
        if (target.exists() && target.absolutePath != oldPath) return false
        if (!runCatching { file.renameTo(target) }.getOrDefault(false)) return false
        // 改名不改变 lastModified，直接把搜索缓存的 key 从旧名迁到新名
        val cached = synchronized(contentCache) { contentCache.remove("$oldName|$stamp") }
        if (cached != null) {
            synchronized(contentCache) { contentCache["$newName|${target.lastModified()}"] = cached }
        }
        LyricIndex.invalidate()
        LyricBindings.renameFile(this, oldName, newName)
        OnlineLyricCache.renameFile(this, oldName, newName)
        val newPath = target.absolutePath
        prefs.getRecentFiles().forEach { recent ->
            if (recent.uri == oldPath) {
                prefs.removeRecentFile(oldPath)
                prefs.addRecentFile(newPath, newName)
            }
        }
        if (prefs.lastLyricFile == oldPath) prefs.lastLyricFile = newPath
        if (Session.internalPath == oldPath) Session.internalPath = newPath
        if (Session.explicitLyricPath == oldPath) Session.explicitLyricPath = newPath
        val matched = Session.matchedFile
        if (matched != null && (matched.absolutePath == oldPath || matched.name == oldName)) {
            Session.matchedFile = target
            Session.matchVersion++
        }
        if (Session.name == oldName) Session.name = newName
        return true
    }

    /** 目录内是否已有同名文件（忽略大小写，可排除自身）。 */
    private fun renameTargetExists(name: String, ignore: File? = null): Boolean =
        storeDir().listFiles()?.any {
            it.isFile && it != ignore && it.name.equals(name, ignoreCase = true)
        } == true

    /** 文件名清洗：路径分隔符与非法字符替换为下划线，并去掉首尾空白与首尾点。 */
    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim().trimStart('.').trimEnd('.', ' ')

    /** 批量命名的基础名不写扩展名；若用户仍填了已知歌词后缀，去掉它避免重复后缀。 */
    private fun stripKnownExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return name
        return if (name.substring(dot + 1).lowercase() in LyricsParser.SUPPORTED_EXTENSIONS) {
            name.substring(0, dot)
        } else name
    }

    /** 解析当前主题的主文字颜色，用于代码动态创建的输入框。 */
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

    /** 歌词文件列表适配器：长按进入多选，勾选删除。 */
    inner class FileAdapter(
        private val onClick: (File) -> Unit,
        private val onSelectionChanged: () -> Unit,
    ) : RecyclerView.Adapter<FileAdapter.VH>() {

        private var items: List<File> = emptyList()
        private val selected = HashSet<String>()
        private var selectMode = false

        fun submit(list: List<File>) {
            items = list
            notifyDataSetChanged()
        }

        fun isSelectMode(): Boolean = selectMode

        fun enterSelectMode() {
            if (!selectMode) {
                selectMode = true
                notifyDataSetChanged()
                onSelectionChanged()
            }
        }

        fun exitSelectMode() {
            if (selectMode) {
                selectMode = false
                selected.clear()
                notifyDataSetChanged()
                onSelectionChanged()
            }
        }

        fun toggleSelect(name: String) {
            if (selected.contains(name)) selected.remove(name) else selected.add(name)
            notifyDataSetChanged()
            onSelectionChanged()
        }

        fun selectAll() {
            selected.addAll(items.map { it.name })
            notifyDataSetChanged()
            onSelectionChanged()
        }

        fun deselectAll() {
            if (selected.isNotEmpty()) {
                selected.clear()
                notifyDataSetChanged()
                onSelectionChanged()
            }
        }

        /** 当前可见（过滤后）列表中的选中文件，删除 / 导出选中都以此为准。 */
        fun getSelectedFiles(): List<File> = items.filter { it.name in selected }

        fun selectedCount(): Int = items.count { it.name in selected }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemLyricFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val binding: ItemLyricFileBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(file: File) {
                binding.fileName.text = file.name
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(file.lastModified()))
                binding.fileInfo.text = "${formatSize(file.length())} · $time"
                binding.fileCheck.visibility = if (selectMode) View.VISIBLE else View.GONE
                binding.fileCheck.isChecked = selected.contains(file.name)

                binding.root.setOnClickListener {
                    if (selectMode) toggleSelect(file.name) else onClick(file)
                }
                binding.root.setOnLongClickListener {
                    if (!selectMode) {
                        enterSelectMode()
                        toggleSelect(file.name)
                    }
                    true
                }
            }
        }
    }

    companion object {
        /**
         * 创建文件时的中立 MIME：不在系统扩展名映射表里，SAF provider 不会按
         * text/plain 给 .lrc / .krc 等名字补出 .txt 后缀（与 MainActivity 的
         * 「另存为」采取同一策略）。
         */
        private const val NEUTRAL_CREATE_MIME = "application/x-androidlyrics"

        /** 内容搜索时跳过的超大文件阈值。 */
        private const val MAX_SEARCH_BYTES = 512L * 1024L
        private const val STATE_SEARCH_QUERY = "state_search_query"
        private const val STATE_PENDING_EXPORT = "state_pending_export"

        private fun formatSize(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
