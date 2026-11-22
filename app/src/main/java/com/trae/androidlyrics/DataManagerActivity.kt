package com.trae.androidlyrics

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.trae.androidlyrics.databinding.ActivityDataManagerBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 程序数据管理：像文件资源管理器一样浏览、删除应用数据目录下的文件。
 *
 * 根目录包括内部数据、缓存、外部数据目录；支持进入子目录、返回上级、删除文件/目录。
 * 删除后会同步清理歌词绑定、最近打开记录与被删除的匹配文件，避免其他位置残留失效条目。
 */
class DataManagerActivity : ThemedActivity() {

    private lateinit var binding: ActivityDataManagerBinding

    /** 当前浏览路径栈，空表示根目录列表。 */
    private val stack = ArrayDeque<File>()

    private val items = mutableListOf<Item>()
    private lateinit var adapter: DataAdapter

    private data class Item(val label: String, val detail: String, val file: File?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = DataAdapter()
        binding.dataList.adapter = adapter
        binding.btnUp.setOnClickListener { goUp() }

        binding.dataList.setOnItemClickListener { _, _, position, _ ->
            val file = items.getOrNull(position)?.file ?: return@setOnItemClickListener
            if (file.isDirectory) {
                stack.addLast(file)
                refresh()
            } else {
                // 与「歌词文件存储」共用同一套文件信息 / 查看 / 删除弹窗逻辑
                FileInfoDialog.show(this, file, displayPath(file), onDeleted = { refresh() })
            }
        }
        binding.dataList.setOnItemLongClickListener { _, _, position, _ ->
            val file = items.getOrNull(position)?.file ?: return@setOnItemLongClickListener false
            FileInfoDialog.confirmDelete(this, file) { refresh() }
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.isNotEmpty()) {
                    stack.removeLast()
                    refresh()
                } else {
                    finish()
                }
            }
        })
        refresh()
    }

    private fun goUp() {
        if (stack.isNotEmpty()) {
            stack.removeLast()
            refresh()
        }
    }

    private fun refresh() {
        val dir = stack.lastOrNull()
        items.clear()
        items.addAll(if (dir == null) rootItems() else dirItems(dir))
        adapter.notifyDataSetChanged()
        binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.btnUp.visibility = if (dir == null) View.INVISIBLE else View.VISIBLE
        binding.currentPath.text = dir?.let { displayPath(it) } ?: getString(R.string.data_manage)
    }

    private fun rootItems(): List<Item> = buildList {
        add(Item(getString(R.string.data_root_internal), "", filesDir))
        add(Item(getString(R.string.data_root_cache), "", cacheDir))
        getExternalFilesDir(null)?.let { add(Item(getString(R.string.data_root_external), "", it)) }
    }

    private fun dirItems(dir: File): List<Item> {
        val files = dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()
        return files.map { file ->
            val detail = if (file.isDirectory) {
                ""
            } else {
                "${formatSize(file.length())} · ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(file.lastModified()))
                }"
            }
            Item(file.name, detail, file)
        }
    }

    /** 把绝对路径转换为以根目录名称开头的可读路径。 */
    private fun displayPath(file: File): String {
        val abs = file.absolutePath
        val internal = filesDir.absolutePath
        val cache = cacheDir.absolutePath
        val external = getExternalFilesDir(null)?.absolutePath
        return when {
            abs == internal -> getString(R.string.data_root_internal)
            abs.startsWith(internal) ->
                getString(R.string.data_root_internal) + abs.removePrefix(internal)
            abs == cache -> getString(R.string.data_root_cache)
            abs.startsWith(cache) ->
                getString(R.string.data_root_cache) + abs.removePrefix(cache)
            external != null && abs == external -> getString(R.string.data_root_external)
            external != null && abs.startsWith(external) ->
                getString(R.string.data_root_external) + abs.removePrefix(external)
            else -> abs
        }
    }

    private fun formatSize(bytes: Long): String = FileInfoDialog.formatSize(bytes)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** 文件列表适配器：两行显示名称与大小/时间。 */
    private inner class DataAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Item = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView ?: LinearLayout(this@DataManagerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                addView(
                    TextView(this@DataManagerActivity).apply {
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    }
                )
                addView(
                    TextView(this@DataManagerActivity).apply {
                        textSize = 12f
                        alpha = 0.6f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                )
            }) as LinearLayout

            val item = items[position]
            (view.getChildAt(0) as TextView).text = item.label
            val detail = view.getChildAt(1) as TextView
            detail.text = item.detail
            detail.visibility = if (item.detail.isEmpty()) View.GONE else View.VISIBLE
            return view
        }
    }
}
