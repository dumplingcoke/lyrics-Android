package com.trae.androidlyrics

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.trae.androidlyrics.databinding.ItemRecentDirBinding

/**
 * 最近目录列表适配器，支持点击跳转、长按进入多选、勾选删除。
 */
class RecentDirAdapter(
    private val onClick: (Prefs.RecentDir) -> Unit,
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<RecentDirAdapter.VH>() {

    private var items: List<Prefs.RecentDir> = emptyList()
    private val selected = HashSet<String>()
    private var selectMode = false

    fun submit(list: List<Prefs.RecentDir>) {
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

    fun toggleSelect(uri: String) {
        if (selected.contains(uri)) selected.remove(uri) else selected.add(uri)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectAll() {
        selected.addAll(items.map { it.uri })
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

    fun getSelectedUris(): Set<String> = selected.toSet()

    fun selectedCount(): Int = selected.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRecentDirBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemRecentDirBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(dir: Prefs.RecentDir) {
            // 显示完整路径，便于区分不同父级下的同名目录；默认滚到最右优先露出目录名
            binding.dirName.text = dir.path.ifEmpty { dir.name }
            binding.dirCheck.visibility = if (selectMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.dirCheck.isChecked = selected.contains(dir.uri)
            binding.dirScroll.post { binding.dirScroll.fullScroll(android.view.View.FOCUS_RIGHT) }

            // HorizontalScrollView 的 onTouchEvent 无条件消费触摸，路径文字被
            // fillViewport 拉伸铺满整个滚动区，落在文字上的轻点/长按到不了行根视图，
            // 需给文字设置同一套监听才能保证整行可点；横向拖动仍由滚动区正常拦截
            val clickAction = android.view.View.OnClickListener {
                if (selectMode) {
                    toggleSelect(dir.uri)
                } else {
                    onClick(dir)
                }
            }
            val longAction = android.view.View.OnLongClickListener {
                if (!selectMode) {
                    enterSelectMode()
                    toggleSelect(dir.uri)
                }
                true
            }
            binding.root.setOnClickListener(clickAction)
            binding.root.setOnLongClickListener(longAction)
            binding.dirName.setOnClickListener(clickAction)
            binding.dirName.setOnLongClickListener(longAction)
        }
    }
}
