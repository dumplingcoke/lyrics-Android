package com.trae.androidlyrics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivityOnlineSearchBinding
import com.trae.androidlyrics.databinding.ItemOnlineResultBinding

/**
 * 在线搜索歌词页：合并 lyrics-android 与 Capsulyric 的歌词源，给出可挑选的多个版本。
 *
 * 搜索结果支持预览、复制、选用；选用后把歌词（含译文）写入独立的
 * [OnlineLyricCache] 目录（不写入用户现成的 `lyrics/` 歌词存储目录），
 * 绑定当前播放歌曲，并交由 [MainActivity] 在返回前台时打开。
 *
 * 上一次搜索结果在退出界面后仍保留，只有发起下一次搜索时才清除。
 */
class OnlineSearchActivity : ThemedActivity() {

    private lateinit var binding: ActivityOnlineSearchBinding
    private lateinit var appearance: AppearancePrefs
    private val results = ArrayList<OnlineLyrics.Candidate>()
    private lateinit var adapter: ResultAdapter
    private var searching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appearance = AppearancePrefs(this)
        binding = ActivityOnlineSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.os_title)

        adapter = ResultAdapter(
            onPreview = { showPreview(it) },
            onUse = { useLyric(it) },
        )
        binding.resultList.layoutManager = LinearLayoutManager(this)
        binding.resultList.adapter = adapter

        // 自动带入当前播放歌曲，减少输入
        binding.songInput.setText(intent.getStringExtra(EXTRA_TITLE) ?: MusicProgress.title.orEmpty())
        binding.artistInput.setText(intent.getStringExtra(EXTRA_ARTIST) ?: MusicProgress.artist.orEmpty())

        binding.btnSearch.setOnClickListener { startSearch() }
        binding.btnSources.setOnClickListener { showSourcesDialog() }
        binding.songInput.setOnEditorActionListener { _, _, _ -> startSearch(); true }
        binding.artistInput.setOnEditorActionListener { _, _, _ -> startSearch(); true }

        // 「无匹配时自动在线搜索一次」及其子开关「优先带逐字数据的歌词」。
        // 前者关闭时子开关不可操作，但保留用户此前的取值。
        binding.autoFetchSwitch.isChecked = appearance.autoFetchOnNoMatch
        binding.preferWordSwitch.isChecked = appearance.preferWordTimedOnline
        binding.preferWordSwitch.isEnabled = binding.autoFetchSwitch.isChecked
        binding.autoFetchSwitch.setOnCheckedChangeListener { _, checked ->
            appearance.autoFetchOnNoMatch = checked
            binding.preferWordSwitch.isEnabled = checked
            if (checked) {
                // 重新开启时清掉进程内去重记录与「无结果」负缓存，让设置立即生效
                MusicNotificationListenerService.resetOnlineFetchHistory()
                OnlineLyricCache.clearEmptyRecords(this)
            } else if (Session.matchState == LyricMatchState.FETCHING_ONLINE) {
                // 关掉开关时若还有在途请求，别让界面永远停在「正在在线获取歌词」
                Session.matchState = LyricMatchState.NO_MATCH
                Session.matchVersion++
            }
        }
        binding.preferWordSwitch.setOnCheckedChangeListener { _, checked ->
            appearance.preferWordTimedOnline = checked
        }

        // 退出本界面不清空上次结果：进程内保留，直到下一次真正发起搜索才替换
        restoreLastResults()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------- 搜索

    private fun startSearch() {
        val title = binding.songInput.text?.toString()?.trim().orEmpty()
        val artist = binding.artistInput.text?.toString()?.trim().orEmpty()
        if (title.isBlank()) {
            toast(getString(R.string.os_empty_query))
            return
        }
        if (searching) return
        searching = true
        binding.searchProgress.visibility = android.view.View.VISIBLE
        binding.btnSearch.isEnabled = false
        binding.searchStatus.text = getString(R.string.os_searching)
        // 只有「下一次搜索」才清掉上次结果：退出界面返回时仍能看到上一次的列表
        clearRememberedResults()
        results.clear()
        adapter.notifyDataSetChanged()

        val sources = appearance.enabledSources
        val token = appearance.appleMusicToken
        val expectedDuration = MusicProgress.durationMs
        Thread {
            val found = runCatching {
                OnlineLyrics.search(
                    title = title,
                    artist = artist,
                    durationMs = expectedDuration,
                    enabledSources = sources,
                    appleToken = token,
                    preferredSource = OnlineLyrics.preferredSourceFor(MusicProgress.packageName),
                    listener = OnlineLyrics.StatusListener { text ->
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            binding.searchStatus.text = text
                        }
                    },
                )
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                searching = false
                binding.searchProgress.visibility = android.view.View.GONE
                binding.btnSearch.isEnabled = true
                results.clear()
                results.addAll(found)
                adapter.notifyDataSetChanged()
                val status = if (found.isEmpty()) getString(R.string.os_no_result)
                else getString(
                    R.string.os_result_count_with_word,
                    found.size,
                    found.count { it.hasWordTiming }
                )
                binding.searchStatus.text = status
                rememberResults(found, status)
            }
        }.apply { name = "online-search-ui" }.start()
    }

    // ---------------------------------------------------------------- 上次搜索结果

    /** 重新进入界面时恢复列表与状态；结果只在下一次 startSearch() 时清除。 */
    private fun restoreLastResults() {
        results.clear()
        results.addAll(lastResults)
        adapter.notifyDataSetChanged()
        binding.searchStatus.text = if (lastResults.isEmpty()) getString(R.string.os_empty_hint)
        else getString(R.string.os_last_result) + " · " + lastStatusText.orEmpty()
    }

    private fun rememberResults(found: List<OnlineLyrics.Candidate>, status: String) {
        lastResults.clear()
        lastResults.addAll(found)
        lastStatusText = status
    }

    private fun clearRememberedResults() {
        lastResults.clear()
        lastStatusText = null
    }

    // ---------------------------------------------------------------- 歌词源

    private fun showSourcesDialog() {
        val all = OnlineLyrics.ALL_SOURCES.toList()
        val enabled = appearance.enabledSources
        val checked = BooleanArray(all.size) { all[it] in enabled }
        val labels = all.map { source ->
            if (source == OnlineLyrics.SOURCE_APPLE && appearance.appleMusicToken.isBlank()) {
                "$source（需凭据）"
            } else source
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.os_sources_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNeutralButton(R.string.os_apple_token) { _, _ -> showAppleTokenDialog() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.ap_dialog_apply) { _, _ ->
                appearance.enabledSources = all.filterIndexed { i, _ -> checked[i] }.toSet()
                toast(getString(R.string.os_result_count, appearance.enabledSources.size))
            }
            .show()
    }

    private fun showAppleTokenDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(appearance.appleMusicToken)
            hint = "media-user-token"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.os_apple_token)
            .setMessage(R.string.os_apple_token_hint)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.ap_dialog_apply) { _, _ ->
                appearance.appleMusicToken = input.text.toString().trim()
            }
            .show()
    }

    // ---------------------------------------------------------------- 预览 / 使用

    private fun showPreview(candidate: OnlineLyrics.Candidate) {
        val content = OnlineLyrics.mergedLyric(candidate)
        val tv = TextView(this).apply {
            text = content
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle("${candidate.title} · ${candidate.source}")
            .setView(scroll)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setNeutralButton(R.string.os_copy) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("lyrics", content))
                toast(getString(R.string.os_copied))
            }
            .setPositiveButton(R.string.os_use) { _, _ -> useLyric(candidate) }
            .show()
    }

    /**
     * 选用在线歌词：
     * 1. 先写入独立的 `online_lyrics/` 缓存目录并登记索引，之后该歌曲无需再次联网请求；
     * 2. 再从缓存文件复制一份到 `lyrics/` 歌词存储目录，文件名沿用旧版「使用此歌词」
     *    的 `歌曲名 - 歌手.lrc` 规则（重名时 (1)(2)…），不带缓存哈希后缀；
     * 3. 绑定、当前文档路径与提示都指向这份可读文件名的副本，界面不显示缓存名。
     */
    private fun useLyric(candidate: OnlineLyrics.Candidate) {
        val content = OnlineLyrics.mergedLyric(candidate)
        val title = MusicProgress.title?.trim().orEmpty().ifBlank { candidate.title }
        val artist = MusicProgress.artist?.trim().orEmpty().ifBlank { candidate.artist }
        val cacheFile = OnlineLyricCache.save(this, title, artist, candidate, content)
        if (cacheFile == null) {
            toast(getString(R.string.os_save_failed))
            return
        }
        // 复制失败时退回缓存文件本身，保证「使用此歌词」至少仍然可用
        val applied = OnlineLyricCache.copyToLyricsStore(this, cacheFile, candidate) ?: cacheFile
        val binding = LyricBindings.bindingValue(this, applied)
        LyricBindings.putForce(this, title, artist, binding)
        if (artist.isNotEmpty()) LyricBindings.putForce(this, title, "", binding)
        Session.explicitLyricTitle = title
        Session.explicitLyricPath = applied.absolutePath
        Session.pendingOnlineLyricPath = applied.absolutePath
        Session.matchState = LyricMatchState.MATCHED
        toast(getString(R.string.os_saved, applied.name))
        // 回到主界面（singleTask，会走 onResume 加载 pendingOnlineLyricPath）
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- 结果列表

    private inner class ResultAdapter(
        private val onPreview: (OnlineLyrics.Candidate) -> Unit,
        private val onUse: (OnlineLyrics.Candidate) -> Unit,
    ) : RecyclerView.Adapter<ResultAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(ItemOnlineResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(results[position])

        override fun getItemCount(): Int = results.size

        inner class VH(private val b: ItemOnlineResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(c: OnlineLyrics.Candidate) {
                b.resultTitle.text = c.title.ifBlank { getString(R.string.os_title) }
                b.resultArtist.text = c.artist.ifBlank { "-" }
                b.resultSummary.text = c.summary
                b.resultPreview.text = previewLines(OnlineLyrics.mergedLyric(c))
                b.btnPreview.setOnClickListener { onPreview(c) }
                b.btnUse.setOnClickListener { onUse(c) }
            }

            private fun previewLines(text: String): String = text.lineSequence()
                .map { Regex("^\\[[^]]*]").replace(it, "").trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .joinToString("\n")
                .ifBlank { "（无可预览文本）" }
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_online_title"
        const val EXTRA_ARTIST = "extra_online_artist"

        /**
         * 上次搜索结果（进程内保存）。退出界面时不清除，重新进入直接恢复；
         * 只在下一次 startSearch() 发起搜索时清空，避免界面来回切换就丢失结果。
         */
        private val lastResults = ArrayList<OnlineLyrics.Candidate>()
        private var lastStatusText: String? = null

        fun open(context: Context, title: String? = null, artist: String? = null) {
            context.startActivity(Intent(context, OnlineSearchActivity::class.java).apply {
                title?.let { putExtra(EXTRA_TITLE, it) }
                artist?.let { putExtra(EXTRA_ARTIST, it) }
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
