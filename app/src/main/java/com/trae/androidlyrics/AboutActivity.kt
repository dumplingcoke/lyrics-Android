package com.trae.androidlyrics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import com.trae.androidlyrics.databinding.ActivityAboutBinding

/**
 * 致谢页：只列出参考 / 使用的开源项目，卡片点击后用浏览器打开对应仓库。
 *
 * 由主页右上角「更多选项」→「关于」进入；沿用设置页主题，自动带返回箭头。
 */
class AboutActivity : ThemedActivity() {

    private lateinit var binding: ActivityAboutBinding

    private companion object {
        const val URL_LYRICS_ANDROID = "https://github.com/LuoH-AN/lyrics-android"
        const val URL_CAPSULYRIC = "https://github.com/FrancoGiudans/Capsulyric"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.projectLyricsAndroid.setOnClickListener { openUrl(URL_LYRICS_ANDROID) }
        binding.projectCapsulyric.setOnClickListener { openUrl(URL_CAPSULYRIC) }
    }

    /** 用系统浏览器打开仓库链接；设备上没有可处理的应用时给出提示。 */
    private fun openUrl(url: String) {
        val ok = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.isSuccess
        if (!ok) UiTips.show(this, R.string.about_open_failed)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
