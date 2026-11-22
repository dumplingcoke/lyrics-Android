package com.trae.androidlyrics

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 全局统一的轻量提示。
 *
 * 外观沿用系统 Toast 的深色圆角胶囊，位置统一固定在屏幕顶部。
 * Android 11（API 30）起系统渲染的纯文本 Toast 会忽略 [Toast.setGravity]，
 * 因此在前台 Activity 上改用「系统 Toast 同款外观的自定义视图 Toast」，
 * 由应用自己控制 gravity；低版本与后台服务场景仍退回纯文本 Toast。
 *
 * 保存失败属于必须让用户明确知晓的结果，单独走 [showFailure] 的弹窗。
 */
object UiTips {

    /** 顶部提示距屏幕上边缘的距离（dp），避开状态栏图标。 */
    private const val TOP_OFFSET_DP = 56

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        showUpdatable(context, message, duration)
    }

    /**
     * 与 [show] 外观、位置完全一致，但返回一个可以原地改文案的 [Tip]。
     *
     * 用于「先显示跳转目标、稍后拿到播放器实测落点再补充实际进度」这类异步
     * 补充：落点可能几百毫秒后才回报，另起一条 Toast 会叠在前一条上，而这里
     * 可以在同一个胶囊里补上第二行并重新计时显示。
     */
    fun showUpdatable(
        context: Context,
        message: CharSequence,
        duration: Int = Toast.LENGTH_SHORT
    ): Tip {
        if (message.isEmpty()) return Tip(null, null)
        val appContext = context.applicationContext
        val yOffset = (TOP_OFFSET_DP * appContext.resources.displayMetrics.density).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context is Activity) {
            // 纯文本 Toast 在 R+ 无法改 gravity；用同款外观的自定义视图 Toast 固定在顶部。
            val view = LayoutInflater.from(context).inflate(R.layout.toast_top, null)
            val textView = view.findViewById<TextView>(R.id.toastText)
            textView.text = message
            @Suppress("DEPRECATION")
            val toast = Toast(appContext).apply {
                setView(view)
                this.duration = duration
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, yOffset)
            }
            toast.show()
            return Tip(toast, textView)
        }
        val toast = Toast.makeText(appContext, message, duration).apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, yOffset)
            }
        }
        toast.show()
        return Tip(toast, null)
    }

    /** 一次可更新的顶部提示；[update] 复用同一个 Toast 并重新计时显示。 */
    class Tip internal constructor(
        private val toast: Toast?,
        private val textView: TextView?
    ) {
        fun update(message: CharSequence) {
            val t = toast ?: return
            if (message.isEmpty()) return
            runCatching {
                if (textView != null) {
                    // 自定义视图分支必须改 TextView：Toast.setText 会把整个 mNextView
                    // 换成系统文本视图，顶部固定位置的自定义胶囊就丢了。
                    textView.text = message
                } else {
                    t.setText(message)
                }
                t.show()
            }
        }
    }

    fun show(context: Context, @StringRes resId: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(resId), duration)
    }

    /** 失败类结果用弹窗强提示，避免一闪而过后被忽略。 */
    fun showFailure(context: Context, message: CharSequence) {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return
        runCatching {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.save_failed_title)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show()
        }.onFailure { show(context, message) }
    }
}
