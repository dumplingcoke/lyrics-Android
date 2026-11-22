package com.trae.androidlyrics

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors

/**
 * 所有界面的基类：在视图创建之前套用用户选择的主题与「应用外观」配置。
 *
 * 全局默认夜间模式在进程启动（[App.onCreate]）和用户切换主题时都会同步，
 * 因此正常情况下这里只是幂等地确认一次（[App.applyThemeImmediate] 内部有比较），
 * 不会触发 Activity 重建；仅当系统主题变化等边缘情况需要时才补正。
 *
 * 叠加顺序（都发生在子类 setContentView 之前，因此 inflate 时已生效）：
 * 1. 深色/浅色/跟随系统；
 * 2. 动态颜色（Android 12+）或 8 套配色预设之一；
 * 3. AMOLED 纯黑/纯白背景；
 * 4. 设置类界面的系统栏安全区处理（[applySettingsWindowInsets]）。
 *
 * 主题套用之后，界面内容本身还有一步收尾：子类 setContentView 完成后由
 * [installContentEdgeFade] 给可滚动内容套上 [EdgeFadeFrameLayout]，
 * 让内容与标题栏的交界像「底部播放控制条上边缘柔化」那样自然淡出。
 */
open class ThemedActivity : AppCompatActivity() {

    companion object {
        /** 设置项搜索跳转时携带的目标控件 id（View 的 R.id 值，0 表示不定位）。 */
        const val EXTRA_SETTING_ANCHOR = "extra_setting_anchor"
    }

    /** 等待 onResume 后执行的定位锚点；消费一次即清零，避免每次回到前台都重复高亮。 */
    private var pendingAnchorId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingAnchorId = intent.getIntExtra(EXTRA_SETTING_ANCHOR, 0)
        // 必须早于 super.onCreate：AppCompat 在 super 里才按夜间模式创建 delegate 的视图，
        // 放在之后会先按旧主题建一遍再重建，表现为闪一下旧颜色。
        App.applyThemeImmediate(this, Prefs(this).themeMode)
        super.onCreate(savedInstanceState)

        val appearance = AppearancePrefs(this)
        if (appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            DynamicColors.isDynamicColorAvailable()
        ) {
            DynamicColors.applyToActivityIfAvailable(this)
        } else {
            appearance.applyThemeOverlay(theme)
        }
        if (appearance.amoled) {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            theme.applyStyle(
                if (night) R.style.AmoledDarkOverlay else R.style.AmoledLightOverlay, true
            )
        }

        // 设置 / 监听状态 / 致谢等界面统一让出状态栏、标题栏与导航栏安全区；
        // 主页有自己的沉浸式逻辑（setupEdgeToEdge），在此直接跳过。
        applySettingsWindowInsets()

        // 必须在子类 setContentView 之前同步挂上快照。若再用 post 延迟到消息队列，
        // 新窗口可能已经先绘出一帧空底色，仍会看到黑屏。
        // 平时没有快照时仅做一次空检查，不影响正常进入页面。
        App.coverThemeSwitchIfNeeded(this)
    }

    // ---------------------------------------------------------------- 标题栏边界柔化

    /**
     * 设置类界面的标题栏与内容区边界过渡：在 [setContentView] 之后把一个
     * [EdgeFadeFrameLayout] 套到页面里可上下滚动的那一层上。
     *
     * 只包滚动层、不包整页，是为了让「固定不动的搜索框 / 输入框」留在柔化带之外：
     * 这些控件紧贴标题栏，一旦被套进蒙版，用户一滚动结果列表它们的顶边就会跟着
     * 变淡（旧版那条常驻色带正是这样压暗了搜索框提示语的）。
     *
     * 目前由本基类派生、使用 `Theme.Androidlyrics.Settings` 的页面（更多设置、
     * 主页歌词设置、在线搜索歌词、监听状态、搜索设置、致谢、应用外观、歌词颜色、
     * 背景管理、歌词文件存储、程序数据、日志等）自动获得该效果，新增页面无需改动。
     */
    private fun installContentEdgeFade() {
        // 主页自行实现沉浸式布局，底部控制条歌词层已有 LyricsFadeFrameLayout，
        // 这里不再叠加；两个透明中转页直接继承 AppCompatActivity，不会走到这里。
        if (this is MainActivity) return
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        val scroller = findVerticalScroller(root) ?: return
        EdgeFadeFrameLayout.wrap(scroller)
    }

    /**
     * 找出页面里第一个「上下滚动」的容器：优先最外层的，跳过隐藏（GONE）分支。
     * 只认垂直滚动容器，横向列表（如监听状态里的会话翻页器）不参与柔化。
     */
    private fun findVerticalScroller(v: View): View? {
        if (v.visibility == View.GONE) return null
        if (v is NestedScrollView || v is ScrollView || v is AbsListView) return v
        if (v is RecyclerView) {
            // layoutManager 尚未挂上（子类在 setContentView 之后才设置）时按垂直处理：
            // 目前所有页面里的结果列表都是纵向的。
            return if (v.layoutManager?.canScrollVertically() != false) v else null
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findVerticalScroller(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        installContentEdgeFade()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        installContentEdgeFade()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        installContentEdgeFade()
    }

    /** CLEAR_TOP + SINGLE_TOP 复用已在栈中的设置页时，从新 Intent 里取回锚点。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAnchorId = intent.getIntExtra(EXTRA_SETTING_ANCHOR, 0)
    }

    override fun onResume() {
        super.onResume()
        applyPendingAnchor()
    }

    /**
     * 设置项搜索的落地定位：滚动到目标控件并用一次短暂的强调色蒙层提示。
     * 用 decorView.post 等布局完成后再算坐标，否则 ScrollView 内的 top 还是 0。
     */
    private fun applyPendingAnchor() {
        val anchorId = pendingAnchorId
        if (anchorId == 0) return
        pendingAnchorId = 0
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            // 控件可能位于暂时隐藏的容器里：向上找到第一个可见祖先再滚动。
            var target: View = findViewById(anchorId) ?: return@post
            while (target.visibility != View.VISIBLE && target.parent is View) {
                target = target.parent as View
            }
            // 再延一帧：等 ScrollView 内完成 layout，top 才是真实坐标。
            target.post {
                if (isFinishing || isDestroyed) return@post
                scrollToAnchor(target)
                highlightAnchor(target)
            }
        }
    }

    /** 找到最近的 ScrollView / NestedScrollView，把目标滚到略靠上的位置。 */
    private fun scrollToAnchor(target: View) {
        var p = target.parent
        while (p is View && p !is ScrollView && p !is NestedScrollView) {
            p = p.parent
        }
        val scroll = p as? View ?: return
        var y = 0
        var v: View? = target
        while (v != null && v !== scroll) {
            y += v.top
            v = v.parent as? View
        }
        val offset = (y - (16 * resources.displayMetrics.density).toInt()).coerceAtLeast(0)
        when (scroll) {
            is ScrollView -> scroll.smoothScrollTo(0, offset)
            is NestedScrollView -> scroll.smoothScrollTo(0, offset)
        }
    }

    /** 用 ViewOverlay 叠一层强调色，避免改写控件自身背景（MaterialSwitch 等）。 */
    private fun highlightAnchor(target: View) {
        val color = MaterialColors.getColor(
            target, com.google.android.material.R.attr.colorPrimary, 0xFF4C6FFF.toInt()
        )
        val overlay = ColorDrawable(color)
        overlay.setBounds(0, 0, target.width, target.height)
        overlay.alpha = 0
        target.overlay.add(overlay)
        ValueAnimator.ofInt(0, 80, 80, 0).apply {
            duration = 1800L
            addUpdateListener {
                overlay.alpha = it.animatedValue as Int
                target.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    target.overlay.remove(overlay)
                }
            })
            start()
        }
    }

    /**
     * 设置类界面的系统栏处理：内容必须避开状态栏与标题栏。
     *
     * 修复记录：这里曾为消除「横屏状态栏黑边」而对所有设置页调用
     * `setDecorFitsSystemWindows(false)` 并把系统栏设成透明。但 AppCompat 的
     * ActionBar 与内容区并不保证在这种 edge-to-edge 组合下自动让出安全区，
     * 实测每个设置页顶部第一行内容（搜索卡片、标题、输入框）都被状态栏和
     * 标题栏盖住，只露出半行文字。
     *
     * 现在恢复框架默认的 `decorFitsSystemWindows = true`：由 PhoneWindow 统一
     * 为状态栏、ActionBar、导航栏留出空间，全部设置类界面不再被遮挡。
     * 横屏挖孔黑边单独靠下面两项消除，与是否 edge-to-edge 无关：
     * - `SHORT_EDGES` 允许窗口延伸进短边挖孔区，系统不再补黑边；
     * - 关闭 Android 10+ 对系统栏强制叠加的对比度蒙层。
     * 状态栏 / 导航栏颜色沿用主题里不透明的 `?attr/colorSurfaceContainer` /
     * `?attr/colorSurface`，与窗口背景、ActionBar 同色，不会出现割裂色带。
     */
    private fun applySettingsWindowInsets() {
        // 主页自己实现沉浸式（含 IME / 挖孔安全区），不要在这里重复处理；
        // 两个透明中转页直接继承 AppCompatActivity，不会走到这里。
        // 其余由本基类派生的设置类页面全部使用 Theme.Androidlyrics.Settings。
        if (this is MainActivity) return

        // 显式恢复：即便主题或 ROM 曾把窗口设成全屏布局，也强制框架避让系统栏，
        // 保证内容从 ActionBar 下方开始绘制。
        WindowCompat.setDecorFitsSystemWindows(window, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        // 系统栏图标明暗跟随主题，否则浅色主题下白图标看不清。
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}
