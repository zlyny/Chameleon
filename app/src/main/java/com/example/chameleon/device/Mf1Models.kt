package com.example.chameleon.device

import com.example.chameleon.protocol.HexUtils

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

    /**
     * dump 文件名中的 PRNG 编码段（文件名格式 `<UID>_<SAK>_<ATQA>_<PRNG>.eml`）。
     * 读卡时检测的 PRNG 随 dump 一起保存，「加载」回读卡页时无需重新检测。
     */
    val fileNameCode: Int
        get() = when (this) {
            STATIC -> 1
            WEAK -> 2
            HARD -> 3
            UNKNOWN -> 0
        }

    companion object {
        fun of(raw: Int): PrngType = entries.firstOrNull { it.raw == raw } ?: UNKNOWN

        /** 从文件名编码段解析 PRNG（0=未知 1=Static 2=Weak 3=Hard） */
        fun ofFileNameCode(code: Int): PrngType = when (code) {
            1 -> STATIC
            2 -> WEAK
            3 -> HARD
            else -> UNKNOWN
        }
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

    /** 4 字节 UID 的无符号 32 位数值（mfkey32 等离线求解参数）；非 4 字节 UID 为 0 */
    val uidValue: Long
        get() = if (uid.size == 4) HexUtils.readU32(uid, 0) else 0L
}

/** 单个扇区单个密钥位的恢复状态 */
enum class KeyStatus {
    /** 未检测（初始状态，灰色圆圈） */
    UNKNOWN,

    /** 字典 / Nested / mfkey32 攻击恢复（绿色对号） */
    FOUND,

    /** 字典攻击未命中（红色叉号，可点击发起 Nested 攻击） */
    MISSING,

    /** FOUND 基础上经 Dump 全扇区读取成功验证（蓝色对号，密钥确实可访问该扇区） */
    VERIFIED,
}

/** 一个密钥位的完整状态：[status] 为 FOUND/VERIFIED 时 [key] 为恢复出的 6 字节密钥 */
data class KeyState(val status: KeyStatus, val key: ByteArray? = null) {
    /** 密钥是否已恢复（FOUND 或经 Dump 验证的 VERIFIED，均携带真实密钥） */
    val isFound: Boolean
        get() = status == KeyStatus.FOUND || status == KeyStatus.VERIFIED

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
        get() = HexUtils.readU32(uid, 0)
}

/** 一组 Nested 采集随机数（Weak PRNG 卡）：明文 NT、加密 NT 与传输奇偶位 */
data class NtTriple(val nt: Long, val ntEnc: Long, val par: Int)

/** NT dist 检测结果（Weak PRNG 卡）：UID 数值 + 认证后 PRNG 前进步数 */
data class NtDist(val uid: Long, val dist: Int)

/**
 * 模拟卡认证日志条目（MF1_GET_DETECTION_LOG 响应，每条 18 字节）：
 * `block[1] + bitfield[1] + uid[4] + nt[4] + nr[4] + ar[4]`（多字节大端）。
 *
 * 模拟卡开启认证日志（MF1_SET_DETECTION_ENABLE）后，被读卡器认证时固件
 * 记录认证四元组 (uid, nt, nr, ar)；同块同密钥类型的记录 ≥2 条即可经
 * mfkey32 离线恢复密钥（见 MainViewModel.mfkey32 与 NDK mfkey32Recover）。
 */
data class AuthLog(
    /** 被认证的块号 */
    val block: Int,
    /** bitfield bit0：读卡器使用 KeyB 认证 */
    val isKeyB: Boolean,
    /** bitfield bit1：嵌套认证（NT 为密文，不适用 mfkey32 的明文 NT 假设，破解时过滤） */
    val isNested: Boolean,
    val uid: Long,
    /** 卡生成的明文 NT */
    val nt: Long,
    /** 读卡器挑战 nr（密文） */
    val nr: Long,
    /** 读卡器应答 ar（密文） */
    val ar: Long,
) {
    /** 认证块所在扇区（1K 卡：块号 / 4） */
    val sector: Int
        get() = block / ChameleonSession.MF1_BLOCKS_PER_SECTOR
}
