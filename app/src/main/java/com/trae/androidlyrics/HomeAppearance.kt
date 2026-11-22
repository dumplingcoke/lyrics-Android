package com.trae.androidlyrics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * 主页「封面模糊背景」：把当前播放的专辑封面做降采样 + 盒式模糊后铺满屏幕。
 *
 * 参考 lyrics-android 的 HomeAlbumBackground（Compose 用 blur modifier）。本项目是
 * XML View 体系，且不能引入 RenderScript / Coil 等额外依赖，因此用纯 Kotlin 在
 * 缩小后的位图上做两轮盒式模糊，再放大回屏幕尺寸；小图尺寸只有约 1/16 屏，
 * 单次耗时在毫秒级，且按封面对象缓存，滚动歌词时不会重复计算。
 */
object HomeAppearance {

    /**
     * 生成铺满屏幕的模糊封面；失败时返回 null，由调用方保持纯背景色。
     *
     * MediaMetadata 经 Binder 传来的位图可能是 HARDWARE 配置（部分播放器/ROM），
     * `createScaledBitmap` / `getPixels` 都不支持硬件位图，旧实现会被 runCatching
     * 静默吞掉异常返回 null，界面上表现为「开关打开了却没有任何背景」。
     * 这里先统一转成软件位图，并把失败原因落到日志，便于后续定位。
     */
    fun blurredCover(src: Bitmap, screenW: Int, screenH: Int, radius: Int = 6): Bitmap? {
        if (src.isRecycled || screenW <= 0 || screenH <= 0 ||
            src.width <= 0 || src.height <= 0
        ) return null
        return runCatching {
            val software = toSoftware(src)
            try {
                val smallW = (screenW / 16).coerceAtLeast(8)
                val smallH = (screenH / 16).coerceAtLeast(8)
                val small = Bitmap.createScaledBitmap(software, smallW, smallH, true)
                val blurred = boxBlur(small, radius.coerceIn(1, 12))
                val scaled = Bitmap.createScaledBitmap(blurred, screenW, screenH, true)
                if (blurred !== small) blurred.recycle()
                if (small !== scaled) small.recycle()

                // 压暗并轻微去饱和，保证白色歌词在任意封面上都可读
                val out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                        setSaturation(0.85f)
                        // 亮度系数 0.72
                        postConcat(ColorMatrix(floatArrayOf(
                            0.72f, 0f, 0f, 0f, 0f,
                            0f, 0.72f, 0f, 0f, 0f,
                            0f, 0f, 0.72f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        )))
                    })
                }
                canvas.drawBitmap(scaled, 0f, 0f, paint)
                scaled.recycle()
                out
            } finally {
                if (software !== src) software.recycle()
            }
        }.getOrElse {
            android.util.Log.w("HomeAppearance", "blurredCover failed: ${it.message}", it)
            null
        }
    }

    /**
     * 「自定义背景图片模糊」：把用户导入的背景图做降采样 + 盒式模糊，再放大回原尺寸。
     *
     * 与 [blurredCover] 的区别：
     * - 不按屏幕尺寸裁切/拉伸，保持原图宽高比（ImageView 仍是 fitCenter）；
     * - 不叠加压暗与去饱和，遮罩仍由主页的 backgroundMask 统一负责，
     *   避免与封面模糊背景的 0.72 亮度系数叠加后过暗。
     */
    fun blurredImage(src: Bitmap, radius: Int = 6): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        return runCatching {
            val software = toSoftware(src)
            try {
                val smallW = (src.width / 8).coerceAtLeast(8)
                val smallH = (src.height / 8).coerceAtLeast(8)
                val small = Bitmap.createScaledBitmap(software, smallW, smallH, true)
                val blurred = boxBlur(small, radius.coerceIn(1, 12))
                val scaled = Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
                if (blurred !== small && blurred !== scaled) blurred.recycle()
                if (small !== scaled) small.recycle()
                scaled
            } finally {
                if (software !== src) software.recycle()
            }
        }.getOrElse {
            android.util.Log.w("HomeAppearance", "blurredImage failed: ${it.message}", it)
            null
        }
    }

    /** 把 HARDWARE 位图安全转成可读像素的软件位图；已是软件位图则原样返回。 */
    private fun toSoftware(src: Bitmap): Bitmap {
        // HARDWARE 是 API 26 才加入的枚举常量，minSdk 24 上不能无条件引用。
        val hardware = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            src.config == Bitmap.Config.HARDWARE
        if (!hardware) return src
        val copied = runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }
            .getOrNull()?.takeIf { !it.isRecycled }
        if (copied != null) return copied
        // 少数 ROM 的 nativeCopy 对硬件位图返回 null；退回用 Canvas 读回像素。
        val fallback = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(fallback).drawBitmap(src, 0f, 0f, null)
        return fallback
    }

    /** 两轮盒式模糊（水平 + 垂直），在小位图上足够接近高斯模糊观感。 */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)

        repeat(2) {
            // 水平
            for (y in 0 until h) {
                val row = y * w
                var r = 0; var g = 0; var b = 0; var count = 0
                for (x in -radius..radius) {
                    val i = (x.coerceIn(0, w - 1))
                    val c = pixels[row + i]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
                for (x in 0 until w) {
                    tmp[row + x] = (pixels[row + x] and 0xFF000000.toInt()) or
                        (r / count shl 16) or (g / count shl 8) or (b / count)
                    val remove = (x - radius).coerceIn(0, w - 1)
                    val add = (x + radius + 1).coerceIn(0, w - 1)
                    val rc = pixels[row + remove]
                    val ac = pixels[row + add]
                    r += ((ac shr 16) and 0xFF) - ((rc shr 16) and 0xFF)
                    g += ((ac shr 8) and 0xFF) - ((rc shr 8) and 0xFF)
                    b += (ac and 0xFF) - (rc and 0xFF)
                }
            }
            // 垂直
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (y in -radius..radius) {
                    val yy = y.coerceIn(0, h - 1)
                    val c = tmp[yy * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
                for (y in 0 until h) {
                    val idx = y * w + x
                    out[idx] = (tmp[idx] and 0xFF000000.toInt()) or
                        (r / count shl 16) or (g / count shl 8) or (b / count)
                    val remove = (y - radius).coerceIn(0, h - 1)
                    val add = (y + radius + 1).coerceIn(0, h - 1)
                    val rc = tmp[remove * w + x]
                    val ac = tmp[add * w + x]
                    r += ((ac shr 16) and 0xFF) - ((rc shr 16) and 0xFF)
                    g += ((ac shr 8) and 0xFF) - ((rc shr 8) and 0xFF)
                    b += (ac and 0xFF) - (rc and 0xFF)
                }
            }
            // 下一轮以上一轮输出为输入
            System.arraycopy(out, 0, pixels, 0, out.size)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
