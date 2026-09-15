package com.example.chameleon.protocol

/**
 * 十六进制 / 大端整数字节序工具。
 *
 * 协议多字节字段均为大端（网络字节序），编解码集中在同一处实现——
 * 这类位运算历史上出过两次方向性错误（HF14A_RAW 位域、字典攻击掩码），
 * 分散成多份副本就意味着多几处可能改漏的地方。
 */
object HexUtils {

    /** 字节数组格式化为十六进制字符串，如 `11 EF 04 01` */
    fun format(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    /** 解析十六进制字符串为字节数组，分隔符支持空格 / 冒号 / 逗号或无分隔 */
    fun parse(text: String): ByteArray? {
        val cleaned = text.replace(Regex("[:\\s,]+"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        return try {
            ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 大端读取 2 字节无符号整数 */
    fun readU16(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 8) or (src[offset + 1].toInt() and 0xFF)

    /**
     * 大端读取 4 字节无符号整数。
     *
     * 结果用 Long 承载：UInt 在 JNI 边界与日志格式化上不便，
     * 且 0x80000000 以上的值放进 Int 会变成负数。
     */
    fun readU32(src: ByteArray, offset: Int): Long =
        ((src[offset].toLong() and 0xFF) shl 24) or
            ((src[offset + 1].toLong() and 0xFF) shl 16) or
            ((src[offset + 2].toLong() and 0xFF) shl 8) or
            (src[offset + 3].toLong() and 0xFF)

    /** 大端写入 2 字节无符号整数 */
    fun writeU16(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value ushr 8).toByte()
        dst[offset + 1] = (value and 0xFF).toByte()
    }

    /** 无符号 16 位整数的大端编码（HF14A_RAW 等命令的协议字段） */
    fun u16be(value: Int): ByteArray =
        byteArrayOf((value shr 8).toByte(), value.toByte())

    /** 无符号 32 位整数的大端编码（MF1_GET_DETECTION_LOG 的索引参数） */
    fun u32be(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )
}
