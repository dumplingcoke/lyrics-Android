package com.trae.androidlyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.InflaterInputStream

/**
 * 酷狗 KRC1 逐字歌词解码：KRC1 头 → 16 字节循环 XOR → zlib inflate → UTF-8 明文。
 *
 * 明文形如 `[12300,600]<0,200,0>你<200,400,0>好`（时间戳相对行起点），
 * 正好是 [LyricsParser] 已支持的 KRC 语法，因此这里只负责解密，不做格式转换：
 * 解密结果可直接作为候选歌词的原文交给解析器，逐字时间轴会落到 LyricLine.words。
 *
 * 该格式与密钥是公开的 KRC1 规范，实现按规范独立编写；解密失败时返回 null，
 * 由调用方回退到普通 LRC（上游 lyrics-android 同样采取「KRC 失败即回退 LRC」）。
 */
object KugouKrcDecoder {

    private val XOR_KEY = intArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2D, 0xCE, 0xD2, 0x6E, 0x69,
    )

    private const val MAX_BYTES = 2 * 1024 * 1024

    /** 是否已经带 KRC1 二进制头。 */
    fun isKrc1(raw: ByteArray): Boolean =
        raw.size > 4 && raw[0] == 0x6B.toByte() && raw[1] == 0x72.toByte() &&
            raw[2] == 0x63.toByte() && raw[3] == 0x31.toByte()

    /** 返回 KRC 明文；非 KRC1 或解压/解码失败返回 null。 */
    fun decode(raw: ByteArray): String? {
        if (raw.isEmpty() || raw.size > MAX_BYTES || !isKrc1(raw)) return null
        return runCatching {
            val compressed = ByteArray(raw.size - 4) { i ->
                (raw[i + 4].toInt() xor XOR_KEY[i % XOR_KEY.size]).toByte()
            }
            val inflated = InflaterInputStream(ByteArrayInputStream(compressed)).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > MAX_BYTES) error("KRC payload too large")
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(inflated))
                .toString()
                .removePrefix("\uFEFF")
        }.getOrElse {
            LogCapture.w("KugouKrcDecoder", "decode failed: ${it.javaClass.simpleName}: ${it.message}")
            null
        }
    }
}
