package com.trae.androidlyrics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import com.google.android.material.card.MaterialCardView
import kotlin.math.abs

/**
 * 底部播放控制条的容器，在 ViewGroup 层统一识别手势，避免各子 View 互相抢事件。
 *
 * 对应 lyrics-android HomePlayer 的横屏三态交互，并按需求升级：
 * - **直接上滑**：完全展开；**直接下滑**：收起（横屏回到「仅封面」）。
 * - **横向快滑**：朝屏幕中间滑展开小的收起态，反向收回仅封面（方向随停靠侧翻转）。
 * - **长按封面/空白处拖动**：改变左右停靠位置，位置可记忆（仅横屏）。
 * - **长按歌名 / 作者**：计时到立即在线搜词（不等抬手）。
 *
 * 在线搜词只挂在「歌名 / 作者」标题区：其它区域长按不再触发，避免按控制条空白、
 * 封面、进度条时误入搜索页。
 *
 * ### 拖动拖影、闪烁、难拖动的根因与修复
 * 1. 旧实现用 `ev.x - downX` 计算位移。`ev.x` 是**卡片自身局部坐标**，会随
 *    `translationX` 一起移动；卡片跟着手指走时局部坐标反向变化，位移被自我抵消，
 *    形成反馈回路 —— 表现为拖不动、一顿一顿、留下拖影。这里统一改用
 *    [MotionEvent.getRawX] / [getRawY]（屏幕绝对坐标，不受 View 位移影响）。
 * 2. 手势期间宿主不再重算卡片几何 / 歌词侧边留白（[GestureCallback.onGestureStart]
 *    与 [GestureCallback.onGestureEnd] 之间），避免 `requestLayout` + 行间动效
 *    反复重绘造成的闪烁，也避免 `card.animate()` 与手动 `translationX` 打架。
 * 3. 位移只改 `translationX`，不改 LayoutParams，不触发整棵 contentRoot 重新布局。
 *
 * 进度滑块、上一首 / 播放 / 下一首与「桌面歌词」按钮落在 [hitControl] 排除区，
 * 保留原生触摸手感。
 */
class MediaControlsCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MaterialCardView(context, attrs) {

    interface GestureCallback {
        /** 手势接管开始；宿主在此期间暂停一切布局/位移刷新。 */
        fun onGestureStart()

        /** 手势结束（含取消）。 */
        fun onGestureEnd()

        /** 长按拖动开始，[startTranslationX] 是卡片当前的水平位移。 */
        fun onDockDragStart(startTranslationX: Float)

        /** 长按拖动过程中相对按下点的**屏幕绝对**水平位移。 */
        fun onDockDragBy(deltaX: Float)

        fun onDockDragEnd()

        fun onSwipeUp()
        fun onSwipeDown()

        /** 横向快滑，[dx] < 0 为左滑、> 0 为右滑（已过提交阈值）。 */
        fun onSwipeHorizontal(dx: Float)

        /**
         * 长按且未发生拖动 / 滑动。
         *
         * [fromTitle] 为 true 表示按在「歌名 / 作者」标题区，可据此触发在线搜词；
         * 为 false 表示按在控制条其它位置（横屏仅封面态的恢复入口）。
         */
        fun onLongPress(fromTitle: Boolean)
    }

    var gestureCallback: GestureCallback? = null

    /** 是否允许长按拖动改左右位置、横向快滑切状态：仅横屏开启。 */
    var dockDragEnabled = false

    /** 是否允许横向快滑（展开时按原项目禁用）。 */
    var horizontalSwipeEnabled = true

    /**
     * 长按命中这个 View（歌曲标题区）时，**计时到就立即兑现长按**，不等抬手，
     * 与改造前 `setOnLongClickListener` 的直觉一致。其它区域长按仍进入
     * 「可拖动」状态，用于横屏调整停靠位置。
     */
    var immediateLongPressView: View? = null

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = (ViewConfiguration.getLongPressTimeout() * 0.7f).toLong()
    private val verticalThresholdPx = 24f * density
    private val commitThresholdPx = 24f * density
    private val horizontalSlopPx = touchSlop * 1.2f

    private enum class Mode { NONE, DOCK_DRAG, VERTICAL, HORIZONTAL }

    private var mode = Mode.NONE
    private var rawDownX = 0f
    private var rawDownY = 0f
    private var lastDx = 0f
    private var skipGesture = false
    private var longPressArmed = false
    private var longPressOnTitle = false
    private var longPressFired = false
    private var swipeUp = false
    private var gestureActive = false

    private val armRunnable = Runnable {
        if (mode == Mode.NONE && !skipGesture) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (longPressOnTitle) {
                // 只有「歌名 / 作者」标题区长按才立刻兑现（在线搜词），不等抬手；
                // 顺手清掉按压态，避免随后的 ACTION_UP 又触发子 View 的 onClick。
                longPressFired = true
                settleChildPressStates(this)
                gestureCallback?.onLongPress(true)
                clearGesture()
            } else if (dockDragEnabled) {
                // 非标题区长按：仅横屏进入「可拖动」状态，移动即调整停靠位置；
                // 未移动时仍在 ACTION_UP 兑现（从仅封面恢复）。
                longPressArmed = true
            }
            // 竖屏非标题区：长按不做任何事，避免按空白处误触在线搜词。
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> resetGesture(ev)
            MotionEvent.ACTION_MOVE -> if (beginGesture(ev)) return true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 标题长按已在计时到时兑现：消费这次 UP，别让子 View 再响应点击。
                if (longPressFired) {
                    clearGesture()
                    return true
                }
                // 未被接管的手势在这里兑现长按；必须消费这次 ACTION_UP，
                // 否则子 View 还会再触发一次 onClick（歌名长按会顺带展开/收起）。
                if (longPressArmed && ev.actionMasked == MotionEvent.ACTION_UP && !skipGesture) {
                    gestureCallback?.onLongPress(false)
                    clearGesture()
                    return true
                }
                clearGesture()
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mode == Mode.NONE) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastDx = ev.rawX - rawDownX
                if (mode == Mode.DOCK_DRAG) gestureCallback?.onDockDragBy(lastDx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                finishGesture(ev.actionMasked == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    private fun resetGesture(ev: MotionEvent) {
        clearGesture()
        rawDownX = ev.rawX
        rawDownY = ev.rawY
        lastDx = 0f
        longPressFired = false
        longPressOnTitle = immediateLongPressView?.let {
            isHitInWindow(it, ev.rawX, ev.rawY)
        } ?: false
        skipGesture = hitControl(ev.x, ev.y)
        if (!skipGesture) postDelayed(armRunnable, longPressMs)
    }

    /** 用屏幕绝对坐标判断按下点是否落在指定 View 的可见区域内。 */
    private fun isHitInWindow(v: View, rawX: Float, rawY: Float): Boolean {
        if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) return false
        val rect = Rect()
        return v.getGlobalVisibleRect(rect) &&
            rect.contains(rawX.toInt(), rawY.toInt())
    }

    /** 返回 true 表示本次手势由容器接管，子 View 会收到 ACTION_CANCEL。 */
    private fun beginGesture(ev: MotionEvent): Boolean {
        if (mode != Mode.NONE) return true
        if (skipGesture) return false
        // 屏幕绝对坐标：卡片平移不会反向抵消手指位移
        val dx = ev.rawX - rawDownX
        val dy = ev.rawY - rawDownY
        val adx = abs(dx)
        val ady = abs(dy)
        val next = when {
            // 长按已武装：横向移动 = 调整停靠位置
            dockDragEnabled && longPressArmed &&
                adx > touchSlop && adx >= ady * 0.8f -> Mode.DOCK_DRAG
            // 明显竖向：上滑完全展开 / 下滑收起
            ady > verticalThresholdPx && ady > adx * 1.2f -> Mode.VERTICAL
            // 未长按的横向快滑：切「仅封面 / 小的收起态」
            dockDragEnabled && horizontalSwipeEnabled && !longPressArmed &&
                adx > horizontalSlopPx && adx > ady * 1.3f -> Mode.HORIZONTAL
            else -> Mode.NONE
        }
        if (next == Mode.NONE) return false

        mode = next
        lastDx = dx
        swipeUp = dy < 0f
        removeCallbacks(armRunnable)
        longPressArmed = false
        // 拦截 MOVE 会让子 View 收到 ACTION_CANCEL；歌名上的 ripple 会走一次
        // 淡出动画，看起来就是「闪一下」。先把按压态清掉并直接跳到稳态。
        settleChildPressStates(this)
        gestureActive = true
        gestureCallback?.onGestureStart()
        if (next == Mode.DOCK_DRAG) {
            gestureCallback?.onDockDragStart(translationX)
            gestureCallback?.onDockDragBy(dx)
        }
        return true
    }

    private fun finishGesture(released: Boolean) {
        if (!gestureActive) {
            clearGesture()
            return
        }
        if (released) {
            when (mode) {
                Mode.DOCK_DRAG -> gestureCallback?.onDockDragEnd()
                Mode.VERTICAL ->
                    if (swipeUp) gestureCallback?.onSwipeUp() else gestureCallback?.onSwipeDown()
                Mode.HORIZONTAL ->
                    if (abs(lastDx) >= commitThresholdPx) {
                        gestureCallback?.onSwipeHorizontal(lastDx)
                    }
                Mode.NONE -> Unit
            }
        } else if (mode == Mode.DOCK_DRAG) {
            gestureCallback?.onDockDragEnd()
        }
        gestureCallback?.onGestureEnd()
        gestureActive = false
        clearGesture()
    }

    private fun clearGesture() {
        removeCallbacks(armRunnable)
        longPressArmed = false
        skipGesture = false
        lastDx = 0f
        mode = Mode.NONE
        // 极少数情况下 onTouchEvent 没收到 UP（父 View 抢事件），也要解除宿主锁定
        if (gestureActive) {
            gestureActive = false
            gestureCallback?.onGestureEnd()
        }
    }

    /** 递归清掉按压态并把 drawable 直接落到稳态，避免 ACTION_CANCEL 触发涟漪淡出闪烁。 */
    private fun settleChildPressStates(v: View) {
        if (v.isPressed) v.isPressed = false
        v.jumpDrawablesToCurrentState()
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) settleChildPressStates(v.getChildAt(i))
        }
    }

    /** 按下点是否落在需要保留原生触摸行为的控件上。 */
    private fun hitControl(x: Float, y: Float): Boolean {
        val hit = findHit(this, x, y) ?: return false
        return hit is SeekBar || hit is ImageButton || hit is Button
    }

    private fun findHit(group: ViewGroup, x: Float, y: Float): View? {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val lx = x - child.left - child.translationX
            val ly = y - child.top - child.translationY
            if (lx < 0f || ly < 0f || lx > child.width || ly > child.height) continue
            if (child is ViewGroup) return findHit(child, lx, ly) ?: child
            return child
        }
        return group
    }
}
