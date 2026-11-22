package com.trae.androidlyrics

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trae.androidlyrics.databinding.ActivitySettingsSearchBinding
import com.trae.androidlyrics.databinding.ItemRecentSearchChipBinding
import com.trae.androidlyrics.databinding.ItemSettingSearchResultBinding

/**
 * 「主页歌词设置」与「更多设置」共用的设置项搜索页面。
 *
 * 两个入口打开的是同一个页面、同一份 [SettingsIndex] 索引，因此无论从哪里进入，
 * 都能搜到全部设置项（包括另一个页面以及「更多设置」下的二级页面），点击后跳转并定位。
 * 每条结果在功能名称下方显示所属页面路径，例如「更多设置」或「更多设置/应用外观」。
 *
 * 搜索框为空时额外展示「最近搜索」（[Prefs.getRecentSearches]）：这些词只在用户
 * 真正提交一次搜索时记录，点一条即可复用，长按移除单条，右侧「清空」一次清完。
 */
class SettingsSearchActivity : ThemedActivity() {

    private lateinit var binding: ActivitySettingsSearchBinding
    private lateinit var all: List<SettingsIndex.Resolved>
    private lateinit var adapter: ResultAdapter
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivitySettingsSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_search_title)

        all = SettingsIndex.resolveAll(resolve = { resId -> getString(resId) })
        adapter = ResultAdapter { open(it) }
        binding.resultsRecycler.layoutManager = LinearLayoutManager(this)
        binding.resultsRecycler.adapter = adapter

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = applyQuery(s?.toString().orEmpty())
        })
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // 键盘上的「搜索」＝一次明确的提交，记入最近搜索
                rememberSearch(binding.searchInput.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else false
        }
        binding.recentClear.setOnClickListener {
            prefs.clearRecentSearches()
            updateRecentSection(forceRebuild = true)
            UiTips.show(this, getString(R.string.settings_search_recent_cleared))
        }
        applyQuery("")
        binding.searchInput.post {
            binding.searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun applyQuery(query: String) {
        val found = SettingsIndex.filter(all, query)
        adapter.submit(found)
        binding.resultCount.text = getString(R.string.settings_search_count, found.size)
        binding.emptyText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        updateRecentSection()
    }

    /** 记录一次搜索：只有非空关键词才入历史，并把它移到最前。 */
    private fun rememberSearch(query: String) {
        if (query.isBlank()) return
        prefs.addRecentSearch(query)
        updateRecentSection(forceRebuild = true)
    }

    /**
     * 最近搜索只在搜索框为空时出现——此时结果列表等于全部设置项，历史词是最有用的
     * 入口；一旦开始输入就收起，把纵向空间让给结果列表。
     */
    private fun updateRecentSection(forceRebuild: Boolean = false) {
        // 先看输入框：有内容就不必再读偏好（每敲一个字都会走到这里）
        if (!binding.searchInput.text.isNullOrBlank()) {
            binding.recentSection.visibility = View.GONE
            return
        }
        val history = prefs.getRecentSearches()
        if (history.isEmpty()) {
            binding.recentSection.visibility = View.GONE
            return
        }
        val wasVisible = binding.recentSection.visibility == View.VISIBLE
        binding.recentSection.visibility = View.VISIBLE
        if (wasVisible && !forceRebuild) return
        binding.recentChips.removeAllViews()
        history.forEach { keyword ->
            binding.recentChips.addView(buildRecentChip(keyword))
        }
    }

    private fun buildRecentChip(keyword: String): View {
        val chip = ItemRecentSearchChipBinding
            .inflate(layoutInflater, binding.recentChips, false).root
        chip.text = keyword
        chip.contentDescription = getString(R.string.settings_search_recent_item, keyword)
        chip.setOnClickListener {
            // setText 会触发 TextWatcher → applyQuery，结果即时刷新
            binding.searchInput.setText(keyword)
            binding.searchInput.setSelection(keyword.length)
            rememberSearch(keyword)
            hideKeyboard()
        }
        chip.setOnLongClickListener {
            prefs.removeRecentSearch(keyword)
            updateRecentSection(forceRebuild = true)
            UiTips.show(
                this, getString(R.string.settings_search_recent_removed, keyword)
            )
            true
        }
        return chip
    }

    /** 跳转到结果所在的界面；CLEAR_TOP + SINGLE_TOP 让已在栈中的来源页复用实例。 */
    private fun open(item: SettingsIndex.Resolved) {
        // 点开某条结果说明这次查询有效，记入最近搜索后再离开
        rememberSearch(binding.searchInput.text?.toString().orEmpty())
        val intent = Intent(this, item.entry.target).apply {
            if (item.entry.anchorId != 0) {
                putExtra(ThemedActivity.EXTRA_SETTING_ANCHOR, item.entry.anchorId)
            }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        hideKeyboard()
        startActivity(intent)
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class ResultAdapter(
        private val onClick: (SettingsIndex.Resolved) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.VH>() {

        private var items: List<SettingsIndex.Resolved> = emptyList()

        fun submit(list: List<SettingsIndex.Resolved>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemSettingSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemSettingSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: SettingsIndex.Resolved) {
                b.resultTitle.text = item.title
                b.resultPath.text = item.path
                b.root.setOnClickListener { onClick(item) }
            }
        }
    }
}
