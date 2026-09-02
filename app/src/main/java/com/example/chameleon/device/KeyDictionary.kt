package com.example.chameleon.device

/**
 * Mifare Classic 字典攻击密钥表。
 *
 * 内置常见弱密钥（出厂默认 + 各行业门禁遗留密钥），顺序即尝试顺序；
 * 全部密钥单帧送检（[ChameleonSession.MAX_KEYS_PER_REQUEST] 上限 83）。
 * 后续版本可扩展：从外部文件（assets / 用户导入）加载自定义字典。
 */
object KeyDictionary {

    /** 十六进制字符串解析为 6 字节密钥 */
    private fun keyOf(hex: String): ByteArray =
        ByteArray(ChameleonSession.MF1_KEY_SIZE) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    /** 当前使用的字典 */
    val keys: List<ByteArray> = listOf(
        "FFFFFFFFFFFF", // 出厂默认
        "A0A1A2A3A4A5", // MAD（Mifare Application Directory）
        "D3F7D3F7D3F7", // NDEF
        "000000000000",
        "B0B1B2B3B4B5",
        "4D3A99C351DD",
        "1A982C7E459A",
        "AABBCCDDEEFF",
        "714C5C886E97",
        "587EE5F9350F",
        "A0478CC39091",
        "533CB6C723F6",
        "8FD0A4F256E9",
    ).map(::keyOf)
}
