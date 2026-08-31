package com.example.chameleon.protocol

/** 十六进制工具 */
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
}
