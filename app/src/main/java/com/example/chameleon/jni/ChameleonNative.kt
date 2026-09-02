package com.example.chameleon.jni

/**
 * ChameleonUltra 原生库（libchameleon.so）的 JNI 桥接。
 *
 * 定位：承载计算密集型的卡安全算法，避免在 Kotlin 层拖慢 UI 线程。
 * 算法移植自 ChameleonUltra 上位机 software/src（crapto1 / nested_util）。
 *
 * 当前能力：
 * - Static Nested 攻击求解（Static PRNG 卡）
 * - 库版本查询（JNI 链路连通性验证）
 *
 * 规划中（对应 MF1_NESTED_ACQUIRE / MF1_DARKSIDE_ACQUIRE 命令）：
 * - Weak PRNG 卡的 nested attack 密钥恢复
 * - Crypto1 密钥流恢复（darkside）
 */
object ChameleonNative {

    init {
        System.loadLibrary("chameleon")
    }

    /** 获取原生库版本号 */
    external fun nativeVersion(): String

    /**
     * Static Nested 攻击求解。
     *
     * @param uid        卡片 UID 的无符号 32 位数值
     * @param targetType 目标密钥类型（0x60=KeyA / 0x61=KeyB，见 [com.example.chameleon.device.KeyType]）
     * @param ntPairs    采集到的 NT 对，每个元素打包 (nt shl 32) or ntEnc
     * @return 候选密钥列表（48bit 密钥数值，按命中概率降序）；非 Static 漏洞卡返回空
     */
    external fun staticnestedRecover(uid: Long, targetType: Int, ntPairs: LongArray): LongArray
}
