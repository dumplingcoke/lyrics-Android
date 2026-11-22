package com.trae.androidlyrics

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 文本文件读写：包含编码探测（BOM / UTF-8 / GBK 回退）。
 *
 * 保存时必须与原文件保持字节级一致，否则会出现「打开正常、保存后乱码」：
 *  - 原文件的 BOM（UTF-8 / UTF-16）要在写回时补上，否则 UTF-16 文件会因丢失
 *    BOM 被当成 UTF-8 读取、UTF-8 BOM 文件在其它编辑器里会被当作 ANSI 显示；
 *  - 探测得到的 charset 要与写回时使用的 charset 相同，保证 round-trip 一致。
 */
object DocumentIo {

    class Loaded(val text: String, val charset: Charset, val bom: ByteArray)

    private val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    private val NO_BOM = ByteArray(0)

    /** 从 uri 读取文本内容。 */
    fun read(resolver: ContentResolver, uri: Uri): Loaded {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("无法读取文件")
        return decode(bytes)
    }

    /** 将文本写回 uri（含原文件 BOM）。部分 Provider 不支持 "rwt"，回退到 "w"。 */
    fun write(
        resolver: ContentResolver,
        uri: Uri,
        text: String,
        charset: Charset,
        bom: ByteArray,
    ) {
        val body = text.toByteArray(charset)
        val bytes = if (bom.isEmpty()) body else bom + body
        var lastError: Exception? = null
        for (mode in listOf("rwt", "w")) {
            try {
                val stream = resolver.openOutputStream(uri, mode)
                    ?: throw IllegalStateException("无法写入文件")
                stream.use { it.write(bytes) }
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("无法写入文件")
    }

    /**
     * 解码字节数组：优先按 BOM 判断，其次严格 UTF-8。
     * 严格 UTF-8 失败时，用手动扫描统计「无效字节」与「合法多字节序列」：
     *  - 无效字节极少且存在多个合法多字节序列 → 文件本是 UTF-8 仅个别字节损坏，
     *    保留宽松 UTF-8，避免整文件被按 GBK 解码造成大面积乱码；
     *  - 否则按简体中文 ANSI（GB18030，GBK 超集）解码。
     *
     * 无论走哪条分支，返回的 charset/bom 都与原始字节可逆，保证保存后不产生乱码。
     *
     * 例外：加密的 KRC / QRC 二进制歌词会先由 [LyricDecryptor] 还原成 UTF-8 明文。
     * 这类文件本质不是文本，保存时写回的是明文（[LyricsParser] 同样能解析明文格式）。
     */
    fun decode(bytes: ByteArray): Loaded {
        // 加密歌词（KRC 带 "krc1" 头）优先按二进制还原，避免被当文本解成乱码。
        LyricDecryptor.tryKrc(bytes)?.let {
            return Loaded(it, StandardCharsets.UTF_8, NO_BOM)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return Loaded(
                String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                BOM_UTF8
            )
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Loaded(
                String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE),
                StandardCharsets.UTF_16LE,
                BOM_UTF16LE
            )
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Loaded(
                String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE),
                StandardCharsets.UTF_16BE,
                BOM_UTF16BE
            )
        }

        // 严格 UTF-8：全部字节合法时直接采用
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            return Loaded(text, StandardCharsets.UTF_8, NO_BOM)
        } catch (_: CharacterCodingException) {
            // 继续判断：可能是 UTF-8 文件带少量坏字节、GBK/ANSI 文本，或加密的 QRC
        }

        // 严格 UTF-8 失败：先尝试按加密 QRC 还原（正常文本不会走到这一步）。
        LyricDecryptor.tryQrc(bytes)?.let {
            return Loaded(it, StandardCharsets.UTF_8, NO_BOM)
        }

        // 手动扫描 UTF-8：invalid 为非法位置数，nonAscii 为所有非 ASCII 起点数。
        var invalid = 0
        var nonAscii = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            nonAscii++
            val len = when {
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> 0
            }
            var ok = len > 0 && i + len <= bytes.size
            if (ok) {
                val c1 = bytes[i + 1].toInt() and 0xFF
                if (c1 < 0x80 || c1 > 0xBF) {
                    ok = false
                } else if (len >= 3) {
                    val c2 = bytes[i + 2].toInt() and 0xFF
                    if (c2 < 0x80 || c2 > 0xBF) {
                        ok = false
                    } else if (b == 0xE0 && c1 < 0xA0) {
                        ok = false
                    } else if (b == 0xED && c1 > 0x9F) {
                        ok = false
                    } else if (len == 4) {
                        val c3 = bytes[i + 3].toInt() and 0xFF
                        if (c3 < 0x80 || c3 > 0xBF) {
                            ok = false
                        } else if (b == 0xF0 && c1 < 0x90) {
                            ok = false
                        } else if (b == 0xF4 && c1 > 0x8F) {
                            ok = false
                        }
                    }
                }
            }
            if (ok) i += len else {
                invalid++
                i++
            }
        }

        // 非法位置极少且存在多个合法多字节序列 → 判定为「UTF-8 带个别损坏字节」
        if (invalid <= 2 && nonAscii - invalid >= 3) {
            return Loaded(String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8, NO_BOM)
        }

        // 否则按简体中文 ANSI 解码（GB18030 为 GBK 超集，覆盖更全）
        val ansi = runCatching { Charset.forName("GB18030") }
            .recoverCatching { Charset.forName("GBK") }
            .getOrDefault(StandardCharsets.ISO_8859_1)
        return Loaded(String(bytes, ansi), ansi, NO_BOM)
    }

    /** 获取用于展示的文件名。 */
    fun displayName(resolver: ContentResolver, uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.lastPathSegment ?: uri.toString()
        }
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        // content URI 的 lastPathSegment 常形如 "primary:Music/xxx.lrc"，剥离卷名与路径前缀
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?: uri.toString()
    }
}
