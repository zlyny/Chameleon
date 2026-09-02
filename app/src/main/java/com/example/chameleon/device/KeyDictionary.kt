package com.example.chameleon.device

/**
 * Mifare Classic 字典攻击密钥表。
 *
 * 当前版本仅内置全 FF 密钥（出厂默认密钥），后续版本可扩展为：
 * - 内置常见弱密钥列表；
 * - 从外部文件（assets / 用户导入）加载自定义字典。
 * 密钥顺序即尝试顺序，与 [ChameleonSession.MAX_KEYS_PER_REQUEST] 的
 * 批量约束配合使用。
 */
object KeyDictionary {

    /** 当前使用的字典：仅含全 FF 出厂默认密钥 */
    val keys: List<ByteArray> = listOf(
        byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
    )
}
