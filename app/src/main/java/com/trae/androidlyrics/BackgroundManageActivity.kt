package com.trae.androidlyrics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.trae.androidlyrics.databinding.ActivityBackgroundManageBinding
import com.trae.androidlyrics.databinding.ItemBackgroundImageBinding

/**
 * 背景图片管理：先看已添加的图片，点一下选中，再决定应用 / 调整位置；
 * 没有心仪的图片才用「添加图片」去系统里找图。
 */
class BackgroundManageActivity : ThemedActivity() {

    private lateinit var prefs: Prefs
    private lateinit var binding: ActivityBackgroundManageBinding
    private var items = listOf<BackgroundStore.Item>()
    private var selectedId: String? = null
    private var importing = false

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityBackgroundManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setupButtons()
        loadItems()
        // 旧版直接存 URI 的背景图在这里也迁移进图片库，保证一进管理页就能看到
        BackgroundStore.post {
            BackgroundStore.migrateLegacy(this, prefs)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) loadItems()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「修改图片显示位置」返回时刷新变换参数
        if (::prefs.isInitialized) loadItems()
    }

    private fun loadItems() {
        items = BackgroundStore.items(prefs)
            .filter { BackgroundStore.file(this, it.id).exists() }
        if (selectedId == null) selectedId = prefs.backgroundCurrentId
        if (items.none { it.id == selectedId }) selectedId = items.firstOrNull()?.id
        binding.emptyHint.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.gridImages.adapter = GridAdapter()
        updateButtonState()
    }

    private fun updateButtonState() {
        val hasSelection = selectedId != null
        binding.btnDelete.isEnabled = hasSelection
        binding.btnEditPosition.isEnabled = hasSelection
        binding.btnApply.isEnabled = hasSelection
        binding.btnClearAll.isEnabled = items.isNotEmpty()
    }

    private fun setupButtons() {
        binding.btnAdd.setOnClickListener {
            pickImagesLauncher.launch(arrayOf("image/*"))
        }

        binding.btnApply.setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            prefs.backgroundCurrentId = id
            UiTips.show(this, R.string.background_applied)
            finish()
        }

        binding.btnEditPosition.setOnClickListener {
            val id = selectedId
            if (id == null) {
                UiTips.show(this, R.string.background_none_selected)
                return@setOnClickListener
            }
            startActivity(
                Intent(this, BackgroundEditActivity::class.java)
                    .putExtra(BackgroundEditActivity.EXTRA_ID, id)
            )
        }

        binding.btnDelete.setOnClickListener {
            val id = selectedId ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background_delete)
                .setMessage(R.string.confirm_delete_background)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> deleteSelected(id) }
                .show()
        }

        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.background_clear_all)
                .setMessage(R.string.confirm_clear_backgrounds)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> clearAll() }
                .show()
        }
    }

    /** 导入在系统选择器里挑中的图片：解码压缩写内部存储，全部放后台线程。 */
    private fun importImages(uris: List<Uri>) {
        if (importing) return
        importing = true
        UiTips.show(this, R.string.background_importing)
        BackgroundStore.post {
            val added = uris.mapNotNull { uri ->
                // 异步读取前尽量拿到持久读权限，避免部分 ROM 在回调结束后收回授权
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                runCatching { BackgroundStore.import(this, uri) }.getOrNull()
            }
            runOnUiThread {
                importing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (added.isEmpty()) {
                    UiTips.show(this, R.string.background_add_failed)
                    return@runOnUiThread
                }
                val all = BackgroundStore.items(prefs)
                all.addAll(added)
                BackgroundStore.saveItems(prefs, all)
                // 多选时以最后添加的一张作为当前选中项
                val newId = added.last().id
                selectedId = newId
                loadItems()
                UiTips.show(this, getString(R.string.background_added, added.size))
                // 新图按 fitCenter 完整显示（不自动裁切），添加成功后自动进入一次编辑状态，
                // 让用户自己决定缩放与位置；返回本页时 onResume 会刷新变换参数。
                // 只在导入成功的这一刻启动一次，onResume 不会重复进入。
                startActivity(
                    Intent(this, BackgroundEditActivity::class.java)
                        .putExtra(BackgroundEditActivity.EXTRA_ID, newId)
                )
            }
        }
    }

    private fun deleteSelected(id: String) {
        BackgroundStore.deleteFile(this, id)
        BackgroundStore.saveItems(
            prefs, BackgroundStore.items(prefs).filter { it.id != id }
        )
        if (prefs.backgroundCurrentId == id) prefs.backgroundCurrentId = null
        selectedId = null
        loadItems()
        UiTips.show(this, R.string.background_deleted)
    }

    private fun clearAll() {
        items.forEach { BackgroundStore.deleteFile(this, it.id) }
        BackgroundStore.saveItems(prefs, emptyList())
        BackgroundStore.clearCache()
        prefs.backgroundCurrentId = null
        selectedId = null
        loadItems()
        UiTips.show(this, R.string.background_all_cleared)
    }

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): BackgroundStore.Item = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = if (convertView == null) {
                ItemBackgroundImageBinding.inflate(layoutInflater, parent, false).also {
                    it.root.tag = it
                }
            } else {
                convertView.tag as ItemBackgroundImageBinding
            }
            val item = items[position]
            val selected = item.id == selectedId
            row.selectedOverlay.visibility = if (selected) View.VISIBLE else View.GONE
            row.checkMark.visibility = if (selected) View.VISIBLE else View.GONE

            // 缩略图异步解码，用 tag 防止复用时串图
            row.thumb.tag = item.id
            row.thumb.setImageBitmap(null)
            val thumb = row.thumb
            BackgroundStore.post {
                val bitmap = BackgroundStore.thumbnail(this@BackgroundManageActivity, item.id, 240)
                thumb.post {
                    if (thumb.tag == item.id) thumb.setImageBitmap(bitmap)
                }
            }

            row.root.setOnClickListener {
                selectedId = item.id
                notifyDataSetChanged()
                updateButtonState()
            }
            return row.root
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
