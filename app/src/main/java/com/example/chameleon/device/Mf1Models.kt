package com.example.chameleon.device

/** ChameleonUltra 设备工作模式 */
enum class DeviceMode {
    UNKNOWN,
    READER,
    EMULATOR,
}

/** Mifare Classic 卡的伪随机数发生器类型（决定可用的密钥恢复手段） */
enum class PrngType(val raw: Int, val label: String) {
    /** 静态随机数：卡可直接读取，支持 static nested 等攻击 */
    STATIC(0, "Static"),

    /** 弱随机数（LFSR）：支持 nested / darkside 攻击 */
    WEAK(1, "Weak"),

    /** 强随机数：难以离线破解 */
    HARD(2, "Hard"),

    UNKNOWN(-1, "未知"),
    ;

    companion object {
        fun of(raw: Int): PrngType = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/** Mifare Classic 密钥类型（认证指令码沿用 PICC_AUTHENT1A/1B） */
enum class KeyType(val code: Int) {
    A(0x60),
    B(0x61),
}

/**
 * StaticNested 漏洞代次（Static PRNG 卡的进一步细分）。
 *
 * 判定方式：向卡发送 Mifare 认证指令 60 00（对齐 CLI `hf 14a raw -s -c -d 6000`），
 * 卡应答的首个 NT 为固定值——0x01200145 即 GEN1、0x009080A2 即 GEN2
 * （与 native-lib.cpp 的 kStaticGen1Nt/kStaticGen2Nt 对应）。
 * GEN2 卡攻击 KeyB 时 PRNG 前进步数与 GEN1 不同，由 NDK 侧自行适配。
 */
enum class StaticNestedGen(val nt: Long, val label: String) {
    GEN1(0x01200145L, "Static GEN1"),
    GEN2(0x009080A2L, "Static GEN2"),
    ;

    companion object {
        fun ofNt(nt: Long): StaticNestedGen? = entries.firstOrNull { it.nt == nt }
    }
}

/** 读到的 14A 标签信息（对应命令 HF14A_SCAN + MF1_DETECT_PRNG 的结果） */
data class TagInfo(
    /** 卡片 UID（4~10 字节，原始字节序） */
    val uid: ByteArray,
    /** ATQA 原始 2 字节（线上字节序，如 04 00 表示数值 0x0004） */
    val atqa: ByteArray,
    val sak: Int,
    /** ATS 应答，非 ISO14443-4 卡为空 */
    val ats: ByteArray,
    val prng: PrngType,
    /** StaticNested 漏洞代次（仅 [prng] 为 Static 时读卡流程会进一步检测） */
    val staticGen: StaticNestedGen? = null,
) {
    /** UID 十六进制大写（用于展示与文件命名），如 1E6FE3A6 */
    val uidHex: String
        get() = uid.joinToString("") { "%02X".format(it) }

    /** ATQA 十六进制展示（线上字节序，对齐 CLI `hf 14a info`，如 0400） */
    val atqaHex: String
        get() = atqa.joinToString("") { "%02X".format(it) }

    /** SAK 十六进制展示 */
    val sakHex: String
        get() = "%02X".format(sak)

    /** 依据 SAK 推测卡型（与 CLI hf 14a info 的猜测逻辑一致，仅展示用） */
    val guessedType: String
        get() = when (sak) {
            0x08 -> "MIFARE Classic 1K"
            0x09 -> "MIFARE Mini"
            0x10 -> "MIFARE Classic 2K"
            0x11 -> "MIFARE Classic 4K"
            else -> "未知"
        }
}

/** 单个扇区单个密钥位的恢复状态 */
enum class KeyStatus { UNKNOWN, FOUND, MISSING }

/** 一个密钥位的完整状态：[status] 为 FOUND 时 [key] 为恢复出的 6 字节密钥 */
data class KeyState(val status: KeyStatus, val key: ByteArray? = null) {
    companion object {
        val UNKNOWN_STATE = KeyState(KeyStatus.UNKNOWN)
    }
}

/** 一个扇区的 A/B 密钥状态 */
data class SectorKeys(
    val sector: Int,
    val keyA: KeyState = KeyState.UNKNOWN_STATE,
    val keyB: KeyState = KeyState.UNKNOWN_STATE,
)

/** 一组 Static Nested 采集随机数：明文 NT 与加密 NT */
data class NtPair(val nt: Long, val ntEnc: Long)

/** Static Nested 采集结果：UID（4 字节）+ 2 组 NT 对 */
data class StaticNestedAcquire(
    val uid: ByteArray,
    val ntPairs: List<NtPair>,
) {
    /** UID 的无符号 32 位数值（JNI 求解参数） */
    val uidValue: Long
        get() = ((uid[0].toLong() and 0xFF) shl 24) or
            ((uid[1].toLong() and 0xFF) shl 16) or
            ((uid[2].toLong() and 0xFF) shl 8) or
            (uid[3].toLong() and 0xFF)
}

/** 一组 Nested 采集随机数（Weak PRNG 卡）：明文 NT、加密 NT 与传输奇偶位 */
data class NtTriple(val nt: Long, val ntEnc: Long, val par: Int)

/** NT dist 检测结果（Weak PRNG 卡）：UID 数值 + 认证后 PRNG 前进步数 */
data class NtDist(val uid: Long, val dist: Int)
