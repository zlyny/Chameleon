package com.example.chameleon.jni

/**
 * ChameleonUltra 原生库（libchameleon.so）的 JNI 桥接。
 *
 * 定位：承载计算密集型的卡安全算法，避免在 Kotlin 层拖慢 UI 线程。
 * 算法移植自 ChameleonUltra 上位机 software/src（crapto1 / nested_util）。
 *
 * 当前能力：
 * - Static Nested 攻击求解（Static PRNG 卡，staticnested.c）
 * - Nested 攻击求解（Weak PRNG 卡，nested.c）
 * - mfkey32 攻击求解与单条记录复核（mfkey32v2.c，模拟卡认证日志离线破解）
 *
 * 规划中（对应 MF1_DARKSIDE_ACQUIRE 命令）：
 * - Crypto1 密钥流恢复（darkside）
 */
object ChameleonNative {

    init {
        System.loadLibrary("chameleon")
    }

    /**
     * Static Nested 攻击求解。
     *
     * @param uid        卡片 UID 的无符号 32 位数值
     * @param targetType 目标密钥类型（0x60=KeyA / 0x61=KeyB，见 [com.example.chameleon.device.KeyType]）
     * @param ntPairs    采集到的 NT 对，每个元素打包 (nt shl 32) or ntEnc
     * @return 候选密钥列表（48bit 密钥数值，按命中概率降序）；非 Static 漏洞卡返回空
     */
    external fun staticnestedRecover(uid: Long, targetType: Int, ntPairs: LongArray): LongArray

    /**
     * Nested 攻击求解（Weak PRNG 卡）。
     *
     * @param uid      卡片 UID 的无符号 32 位数值
     * @param dist     固件测得的 PRNG 前进步数（MF1_DETECT_NT_DIST）
     * @param ntPairs  采集到的 NT 对，每个元素打包 (nt shl 32) or ntEnc
     * @param parities 与 ntPairs 一一对应的奇偶位字节（低 3bit 有效）
     * @return 候选密钥列表（48bit 密钥数值，按命中概率降序）；无可信 NT 组合返回空（可重采）
     */
    external fun nestedRecover(uid: Long, dist: Int, ntPairs: LongArray, parities: ByteArray): LongArray

    /**
     * mfkey32 攻击求解（模拟卡认证日志离线破解，移植自 mfkey32v2.c）。
     *
     * 输入同一 (uid, block, key) 分组内的全部认证记录：记录两两组合求解
     * （一条恢复候选、一条验证），命中的密钥再对全组记录复核，全部失败的
     * 视为误报丢弃。
     *
     * @param uid 卡片 UID 的无符号 32 位数值
     * @param nts 每条记录的明文 NT
     * @param nrs 每条记录的密文 NR（读卡器挑战）
     * @param ars 每条记录的密文 AR（读卡器应答）
     * @return 去重后的密钥列表（48bit 密钥数值）；记录不足 2 条返回空
     */
    external fun mfkey32Recover(uid: Long, nts: LongArray, nrs: LongArray, ars: LongArray): LongArray

    /**
     * mfkey32 单条记录复核：验证密钥能否解释该条认证记录
     * （uid ^ nt 前向走密钥流，比较 ar）。供上层统计"复核通过 n/m 条记录"。
     */
    external fun mfkey32Verify(uid: Long, nt: Long, nr: Long, ar: Long, key: Long): Boolean
}
