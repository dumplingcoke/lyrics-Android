package com.trae.androidlyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 加密歌词文件解密：KRC（酷狗）与 QRC（QQ音乐）。
 *
 * 这两类文件从官方客户端下载时是二进制密文，直接按文本解码只会得到乱码，
 * 因此需要在 [DocumentIo.decode] 里于文本解码之前/失败之后尝试还原成明文歌词，
 * 再交给 [LyricsParser] 识别时间戳。
 *
 *  - KRC：跳过 4 字节 "krc1" 头，逐字节与 16 字节固定密钥异或，再 zlib 解压。
 *    另兼容三种变体：API 来源的 Base64 文本形式、部分资料描述的 8 字节头
 *    （magic + 4 字节附加字段）、以及解压后为 GB18030 的老歌词。
 *  - QRC：3DES-ECB（24 字节固定密钥）解密后 zlib 解压；本地文件还会先经过
 *    QMC1 异或并在开头带 11 字节 magic；部分来源为十六进制文本形式。
 *
 * 解密结果统一按 UTF-8 明文返回（KRC 允许 GB18030 回退）。编辑并保存这类
 * 文件时写回的是明文，[LyricsParser] 仍能识别明文 KRC/QRC，歌词内容不会丢失。
 */
object LyricDecryptor {

    private const val MAX_PLAIN_BYTES = 4 * 1024 * 1024

    private val KRC_MAGIC = byteArrayOf(0x6B, 0x72, 0x63, 0x31) // "krc1"
    private val KRC_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69
    )

    private val QRC_KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(StandardCharsets.US_ASCII)
    private val QRC_MAGIC = byteArrayOf(
        0x98.toByte(), 0x25, 0xB0.toByte(), 0xAC.toByte(), 0xE3.toByte(),
        0x02, 0x83.toByte(), 0x68, 0xE8.toByte(), 0xFC.toByte(), 0x6C
    )

    /** QQ音乐 QMC1 本地加密使用的 128 字节密钥表。 */
    private val QMC1_PRIVKEY: ByteArray = intArrayOf(
        0xC3, 0x4A, 0xD6, 0xCA, 0x90, 0x67, 0xF7, 0x52,
        0xD8, 0xA1, 0x66, 0x62, 0x9F, 0x5B, 0x09, 0x00,

        0xC3, 0x5E, 0x95, 0x23, 0x9F, 0x13, 0x11, 0x7E,
        0xD8, 0x92, 0x3F, 0xBC, 0x90, 0xBB, 0x74, 0x0E,

        0xC3, 0x47, 0x74, 0x3D, 0x90, 0xAA, 0x3F, 0x51,
        0xD8, 0xF4, 0x11, 0x84, 0x9F, 0xDE, 0x95, 0x1D,

        0xC3, 0xC6, 0x09, 0xD5, 0x9F, 0xFA, 0x66, 0xF9,
        0xD8, 0xF0, 0xF7, 0xA0, 0x90, 0xA1, 0xD6, 0xF3,

        0xC3, 0xF3, 0xD6, 0xA1, 0x90, 0xA0, 0xF7, 0xF0,
        0xD8, 0xF9, 0x66, 0xFA, 0x9F, 0xD5, 0x09, 0xC6,

        0xC3, 0x1D, 0x95, 0xDE, 0x9F, 0x84, 0x11, 0xF4,
        0xD8, 0x51, 0x3F, 0xAA, 0x90, 0x3D, 0x74, 0x47,

        0xC3, 0x0E, 0x74, 0xBB, 0x90, 0xBC, 0x3F, 0x92,
        0xD8, 0x7E, 0x11, 0x13, 0x9F, 0x23, 0x95, 0x5E,

        0xC3, 0x00, 0x09, 0x5B, 0x9F, 0x62, 0x66, 0xA1,
        0xD8, 0x52, 0xF7, 0x67, 0x90, 0xCA, 0xD6, 0x4A,
    ).map { it.toByte() }.toByteArray()

    private val PLAIN_LYRIC = Regex(
        "(?m)^\\s*\\[(?:\\d{1,8},\\d{1,8}|\\d{1,3}:\\d{1,2})"
    )

    /**
     * 尝试还原 KRC。主路径是本地二进制文件（"krc1" 开头）；
     * 另外兼容 API 返回的 Base64 文本、8 字节头变体、以及 GB18030 明文。
     * 所有分支都要求解开后「像歌词」才返回，普通文本歌词不会被误判。
     */
    fun tryKrc(bytes: ByteArray): String? {
        if (bytes.size <= 8) return null
        val candidates = ArrayList<ByteArray>(2)
        if (startsWith(bytes, KRC_MAGIC)) {
            candidates.add(bytes)
        } else {
            // 不是二进制头：可能是 API 来源把整个 KRC 做了 Base64 编码
            base64ToBytes(bytes)?.takeIf { startsWith(it, KRC_MAGIC) }?.let { candidates.add(it) }
        }
        for (candidate in candidates) {
            // 4 字节头是主流实现；8 字节头仅作为兼容兜底，避免漏掉带附加字段的文件
            for (headerSize in intArrayOf(4, 8)) {
                val plain = decryptKrcBody(candidate, headerSize) ?: continue
                val text = decodeKrcText(plain) ?: continue
                if (looksLikeLyrics(text)) return text
            }
        }
        return null
    }

    /** 跳过文件头后按 16 字节固定密钥循环异或，再 zlib 解压。 */
    private fun decryptKrcBody(bytes: ByteArray, headerSize: Int): ByteArray? {
        if (bytes.size <= headerSize + 2 || !startsWith(bytes, KRC_MAGIC)) return null
        val body = ByteArray(bytes.size - headerSize)
        for (i in headerSize until bytes.size) {
            val k = KRC_KEY[(i - headerSize) % KRC_KEY.size].toInt() and 0xFF
            body[i - headerSize] = (bytes[i].toInt() xor k).toByte()
        }
        return inflate(body)
    }

    /** 解压后的明文按 UTF-8 严格解码；部分老 KRC 是 ANSI（GBK），用 GB18030 回退。 */
    private fun decodeKrcText(plain: ByteArray): String? {
        val utf8 = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plain)).toString()
        }.getOrNull()
        if (utf8 != null) return utf8
        return runCatching { String(plain, Charset.forName("GB18030")) }.getOrNull()
    }

    /**
     * 手写 Base64 解码：minSdk 24 不能依赖 java.util.Base64（API 26+），
     * 也保持本类不依赖 Android SDK，方便单独编译验证。
     */
    private fun base64ToBytes(bytes: ByteArray): ByteArray? {
        if (bytes.size < 16 || bytes.size > 1_000_000) return null
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
            if (c !in 'A'..'Z' && c !in 'a'..'z' && c !in '0'..'9' &&
                c != '+' && c != '/' && c != '='
            ) return null
            sb.append(c)
        }
        if (sb.length < 16) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        var padding = false
        for (c in sb) {
            if (c == '=') {
                padding = true
                continue
            }
            if (padding) return null
            val v = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * 尝试还原 QRC。会依次尝试：十六进制文本形式、原始二进制、
     * 以及 QMC1 本地加密（解密后去掉 11 字节 magic）。
     */
    fun tryQrc(bytes: ByteArray): String? {
        if (bytes.size < 16) return null
        val candidates = ArrayList<ByteArray>(3)
        hexToBytes(bytes)?.let { candidates.add(it) }
        candidates.add(bytes)

        for (candidate in candidates) {
            val qmc = qmc1(candidate)
            val local = if (startsWith(qmc, QRC_MAGIC)) {
                qmc.copyOfRange(QRC_MAGIC.size, qmc.size)
            } else null
            val variants = listOfNotNull(local, candidate)
            for (v in variants) {
                val plain = runCatching { inflate(desEde(v)) }.getOrNull() ?: continue
                val text = String(plain, StandardCharsets.UTF_8)
                if (looksLikeLyrics(text)) return text
            }
        }
        return null
    }

    private fun looksLikeLyrics(text: String): Boolean {
        val head = text.take(20_000)
        return PLAIN_LYRIC.containsMatchIn(head) || head.contains("LyricContent=")
    }

    /** 3DES-ECB 解密（密文长度按 8 字节对齐，尾部不足部分舍弃）。 */
    private fun desEde(data: ByteArray): ByteArray {
        val len = (data.size / 8) * 8
        if (len == 0) throw IllegalArgumentException("QRC 密文过短")
        val input = if (len == data.size) data else data.copyOf(len)
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY, "DESede"))
        return cipher.doFinal(input)
    }

    private fun qmc1(data: ByteArray): ByteArray {
        val out = data.copyOf()
        for (i in out.indices) {
            val idx = if (i > 0x7FFF) (i % 0x7FFF) and 0x7F else i and 0x7F
            out[i] = (out[i].toInt() xor (QMC1_PRIVKEY[idx].toInt() and 0xFF)).toByte()
        }
        return out
    }

    /** zlib 解压，带输出上限防止异常文件撑爆内存。 */
    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val buf = ByteArray(4096)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_PLAIN_BYTES) throw IllegalArgumentException("解压数据过大")
                out.write(buf, 0, n)
            }
        }
        out.toByteArray()
    }.getOrNull()

    private fun hexToBytes(bytes: ByteArray): ByteArray? {
        if (bytes.size > 400_000) return null
        val sb = StringBuilder()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue
            if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') return null
            sb.append(c)
        }
        if (sb.length < 32 || sb.length % 2 != 0) return null
        val out = ByteArray(sb.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(sb[i * 2], 16)
            val lo = Character.digit(sb[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }
}
