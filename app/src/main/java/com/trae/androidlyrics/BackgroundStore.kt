package com.trae.androidlyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 自定义背景图片库。
 *
 * · 图片导入后统一降采样压缩存到 filesDir/backgrounds/，不再依赖外部 URI 的持久授权，
 *   用户删掉原图也不会让背景消失；「删除 / 清空」可以真正删掉文件。
 * · 导入只做等比降采样，不按屏幕比例裁切；显示一律 fitCenter 保留整张图，
 *   是否放大 / 平移由用户在编辑页决定。
 * · 图片列表与每张图的显示变换（缩放、位移）以 JSON 存 SharedPreferences。
 * · 所有解码都走后台线程 + inSampleSize 降采样，并带内存缓存，
 *   避免大图在主线程解码造成主界面与设置页卡顿或 OOM。
 */
object BackgroundStore {

    /** 缩放变换的允许范围。 */
    const val MIN_SCALE = 0.2f
    const val MAX_SCALE = 8f

    /** 导入时图片长边上限，兼顾清晰度与内存占用。 */
    private const val IMPORT_MAX_EDGE = 2048

    private val executor = Executors.newFixedThreadPool(2)

    private val thumbCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 已解码的大图缓存，避免每次 onResume 重复解码同一张图。 */
    @Volatile private var screenBitmapId: String? = null
    @Volatile private var screenBitmap: Bitmap? = null

    /**
     * 一张背景图。
     *
     * [offsetX] / [offsetY] 是相对容器宽/高归一化的位移，
     * 这样「手机屏幕比例预览」和实际主界面（尺寸不同）能复现同样的显示位置。
     */
    data class Item(
        val id: String,
        var scaleX: Float = 1f,
        var scaleY: Float = 1f,
        var offsetX: Float = 0f,
        var offsetY: Float = 0f,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("sx", scaleX.toDouble())
            put("sy", scaleY.toDouble())
            put("ox", offsetX.toDouble())
            put("oy", offsetY.toDouble())
        }
    }

    private fun parse(json: String): MutableList<Item> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id", "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Item(
                id = id,
                scaleX = o.optDouble("sx", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                scaleY = o.optDouble("sy", 1.0).toFloat().coerceIn(MIN_SCALE, MAX_SCALE),
                offsetX = o.optDouble("ox", 0.0).toFloat(),
                offsetY = o.optDouble("oy", 0.0).toFloat(),
            )
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun items(prefs: Prefs): MutableList<Item> = parse(prefs.backgroundItems)

    fun saveItems(prefs: Prefs, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.backgroundItems = arr.toString()
    }

    fun find(prefs: Prefs, id: String?): Item? =
        if (id.isNullOrEmpty()) null else items(prefs).firstOrNull { it.id == id }

    /** 新增或更新一张图的变换参数。 */
    fun updateItem(prefs: Prefs, item: Item) {
        val list = items(prefs)
        val index = list.indexOfFirst { it.id == item.id }
        if (index >= 0) list[index] = item else list.add(item)
        saveItems(prefs, list)
    }

    fun file(context: Context, id: String): File =
        File(File(context.filesDir, "backgrounds"), "$id.jpg")

    private fun ensureDir(context: Context) {
        val dir = File(context.filesDir, "backgrounds")
        if (!dir.exists()) dir.mkdirs()
    }

    /** 在后台线程提交任务。 */
    fun post(task: () -> Unit) {
        executor.execute(task)
    }

    /**
     * 从 SAF URI 导入图片到内部存储（长边压缩到 [IMPORT_MAX_EDGE] 以内、JPEG 保存）。
     * 须在后台线程调用；失败返回 null。
     */
    fun import(context: Context, uri: Uri): Item? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > IMPORT_MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val bitmap = applyExifRotation(bytes, decoded)

        ensureDir(context)
        val id = UUID.randomUUID().toString()
        val target = file(context, id)
        val ok = runCatching {
            FileOutputStream(target).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }.getOrDefault(false)
        if (bitmap !== decoded) decoded.recycle()
        bitmap.recycle()
        if (!ok) {
            target.delete()
            return null
        }
        return Item(id)
    }

    /** 相册竖拍照片带 EXIF 方向，解码后需旋正，否则背景会横躺。 */
    private fun applyExifRotation(bytes: ByteArray, decoded: Bitmap): Bitmap {
        val rotation = runCatching {
            val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return decoded
        val matrix = Matrix().apply { postRotate(rotation) }
        return runCatching {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrDefault(decoded)
    }

    /** 删除单张图片的文件与缓存。 */
    fun deleteFile(context: Context, id: String) {
        file(context, id).delete()
        thumbCache.remove(id)
        if (screenBitmapId == id) {
            screenBitmapId = null
            screenBitmap = null
        }
    }

    fun clearCache() {
        thumbCache.evictAll()
        screenBitmapId = null
        screenBitmap = null
    }

    /** 宫格缩略图（带缓存），须在后台线程调用。 */
    fun thumbnail(context: Context, id: String, sizePx: Int): Bitmap? {
        thumbCache.get(id)?.let { return it }
        val source = file(context, id)
        if (!source.exists()) return null
        val bitmap = decodeSampled(source, sizePx, sizePx) ?: return null
        thumbCache.put(id, bitmap)
        return bitmap
    }

    /**
     * 已解码大图的同步读取：主题切换 recreate 后在主线程立刻贴出旧图，
     * 不必等后台解码任务回主线程，避免首帧露出窗口底色（黑一下）。
     */
    fun cachedScreenBitmap(id: String): Bitmap? =
        if (screenBitmapId == id) screenBitmap else null

    /** 主界面 / 编辑预览用的背景图（按 id 缓存），须在后台线程调用。 */
    fun screenBitmap(context: Context, id: String, width: Int, height: Int): Bitmap? {
        if (screenBitmapId == id) screenBitmap?.let { return it }
        val source = file(context, id)
        if (!source.exists()) return null
        val req = maxOf(width, height, 720)
        val bitmap = decodeSampled(source, req, req) ?: return null
        screenBitmapId = id
        screenBitmap = bitmap
        return bitmap
    }

    /**
     * 降采样解码：两个维度都还超过目标 2 倍时才继续缩小，
     * 保证 fitCenter 完整显示之后再放大时仍有足够清晰度。
     */
    private fun decodeSampled(source: File, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // 以长边为准：fitCenter 由长边决定缩放，短边留白，不按容器比例裁切。
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val reqLongest = maxOf(reqW, reqH)
        var sample = 1
        while (longest / sample > reqLongest * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(source.absolutePath, opts) }.getOrNull()
    }

    /** 把图片的缩放/位移应用到 ImageView（以 fitCenter 完整显示的内容为基准）。 */
    fun applyTransform(view: View, item: Item?) {
        if (item == null) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            return
        }
        view.scaleX = item.scaleX
        view.scaleY = item.scaleY
        view.translationX = item.offsetX * view.width
        view.translationY = item.offsetY * view.height
    }

    /** 从 ImageView 当前变换写回归一化参数。 */
    fun readTransform(view: View, item: Item) {
        item.scaleX = view.scaleX
        item.scaleY = view.scaleY
        item.offsetX = if (view.width > 0) view.translationX / view.width else 0f
        item.offsetY = if (view.height > 0) view.translationY / view.height else 0f
    }

    /**
     * 旧版直接把 SAF URI 存进 bg_uri：这里一次性导入图片库并设为当前背景。
     * 须在后台线程调用；无论成功与否都只执行一次。
     */
    fun migrateLegacy(context: Context, prefs: Prefs) {
        if (prefs.backgroundLegacyMigrated) return
        prefs.backgroundLegacyMigrated = true
        val uriStr = prefs.backgroundUri ?: return
        prefs.backgroundUri = null
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return
        val item = runCatching { import(context, uri) }.getOrNull() ?: return
        val list = items(prefs)
        list.add(item)
        saveItems(prefs, list)
        prefs.backgroundCurrentId = item.id
    }

    /** 当前生效的背景图（顺带完成旧数据迁移）；文件失效时返回 null。 */
    fun currentItem(context: Context, prefs: Prefs): Item? {
        migrateLegacy(context, prefs)
        val id = prefs.backgroundCurrentId ?: return null
        val item = find(prefs, id) ?: return null
        return item.takeIf { file(context, it.id).exists() }
    }
}
